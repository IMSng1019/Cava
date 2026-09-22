package cava.mixin.push;

import cava.push.PushRuntime;
import cava.push.SectionMirror;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.util.function.LazyIterationConsumer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.entity.EntityTrackingSection;
import net.minecraft.world.entity.SectionedEntityCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P2 第 2 核的 **broadphase**：{@code SectionedEntityCache.forEachInBox}。
 *
 * <p>原版对每个 x 列做一次 {@code LongAVLTreeSet.subSet(x 列)} 再逐元素过滤 y/z ——
 * 在 forceload 大面积区块的服务器上扫的是"整列的全部区段"，而真正命中的常常只有几个。
 * 本 mixin 把访问计划交给原生一次算出（{@code cava_push_section_plan}）。
 *
 * <p><b>顺序为什么一定相同</b>：原生返回的是输入有序数组的一个**升序子序列**，
 * 而输入数组就是 {@code trackedPositions} 本身的迭代序。子序列保序 ⇒ 与原版访问顺序逐一相同。
 * shadow 模式用**原版自己的 {@code ChunkSectionPos}** 逐条转写一遍做同 call 对拍。
 *
 * <p><b>回退默认安全</b>：镜像条数对不上原集合（漂移）/ 原生错误码 / 计划装不下 / 重入 ⇒
 * 一律**不 cancel**，原版照跑，并且不改变任何原版状态。
 *
 * <p><b>已知偏差（写明，未验证）</b>：如果 {@code consumer.accept} 在遍历途中改动了
 * {@code trackedPositions}（实体增删导致 addSection/removeSection），原版是边遍历边看，
 * 本路径用的是计划快照。原版在那种情况下的行为本身依赖 fastutil 迭代器的未定义细节；
 * 本次实测没有触发过，也没有构造出用例。
 */
@Mixin(SectionedEntityCache.class)
public abstract class SectionedEntityCacheMixin {

    @Shadow @Final private LongSortedSet trackedPositions;
    @Shadow @Final private Long2ObjectMap trackingSections;

    @Unique private SectionMirror cava$sectionMirror;

    @Inject(method = "addSection", at = @At("TAIL"))
    private void cava$onAddSection(long pos, CallbackInfoReturnable<Object> cir) {
        if (cava$sectionMirror != null) {
            cava$sectionMirror.add(pos);
        }
    }

    @Inject(method = "removeSection", at = @At("HEAD"))
    private void cava$onRemoveSection(long pos, CallbackInfo ci) {
        if (cava$sectionMirror != null) {
            cava$sectionMirror.remove(pos);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "forEachInBox", at = @At("HEAD"), cancellable = true)
    private void cava$forEachInBox(Box box, LazyIterationConsumer consumer, CallbackInfo ci) {
        if (!PushRuntime.broadphaseLive() && !PushRuntime.broadphaseShadow()) {
            return;
        }
        if (PushRuntime.bpReentrant()) {
            PushRuntime.bpFallback.incrementAndGet();
            return;
        }
        PushRuntime.maybeInstall();

        // 惰性建镜像 + 每次 O(1) 对账：条数不等就一定漂移了。
        SectionMirror m = cava$sectionMirror;
        if (m == null) {
            m = new SectionMirror(trackedPositions, Math.max(64, trackedPositions.size() * 2));
            cava$sectionMirror = m;
        } else if (!m.inSync(trackedPositions.size())) {
            PushRuntime.bpDesync.incrementAndGet();
            m.syncFrom(trackedPositions);
        }

        PushRuntime.enterBp();
        try {
            final long t0 = PushRuntime.BENCH ? System.nanoTime() : 0L;
            final int n = m.plan(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            if (PushRuntime.BENCH) {
                PushRuntime.bpNativeNs.addAndGet(System.nanoTime() - t0);
            }
            PushRuntime.bpCalls.incrementAndGet();
            if (n < 0) {
                PushRuntime.bpFallback.incrementAndGet();
                return;                              // 原生失败/装不下 ⇒ 原版照跑
            }
            PushRuntime.bpVisited.addAndGet(n);

            if (PushRuntime.broadphaseShadow()) {
                cava$shadowPlan(box, m, n);
                return;
            }

            for (int i = 0; i < n; i++) {
                // 金丝雀（只允许诊断用）：故意丢掉计划里的最后一个区段。
                // 它必须让"被丢掉的那个区段里的实体对所有查询不可见"—— 包括 RCON 的 @e。
                // 能红的测试才是测试：这条 flag 一开，场景 dump 必须大面积变成 "No entity was found"。
                if ("drop-last-section".equals(PushRuntime.CANARY) && i == n - 1) {
                    PushRuntime.bpCanary.incrementAndGet();
                    continue;
                }
                final Object secObj = trackingSections.get(m.outAt(i));
                if (secObj == null) {
                    continue;                        // 与原版一致的三个过滤（顺序也一致）
                }
                final EntityTrackingSection sec = (EntityTrackingSection) secObj;
                if (sec.isEmpty() || !sec.getStatus().shouldTrack()) {
                    continue;
                }
                if (consumer.accept(sec).shouldAbort()) {
                    return;                          // 原版在 shouldAbort 时立即 return
                }
            }
            PushRuntime.bpTakeovers.incrementAndGet();
            ci.cancel();
        } finally {
            PushRuntime.exitBp();
        }
    }

    /**
     * shadow：用**原版自己的 ChunkSectionPos** 把 {@code forEachInBox} 的字节码逐条转写一遍，
     * 与原生计划逐元素对拍。这同时是"打包/移位常数有没有读反"的独立验证 ——
     * 因为这里一个常数都没有自己写，全部走原版 API。
     */
    @SuppressWarnings("rawtypes")
    private void cava$shadowPlan(Box box, SectionMirror m, int nativeCount) {
        final long[] ref = PushRuntime.bpRef();
        final long t0 = PushRuntime.BENCH ? System.nanoTime() : 0L;
        final int refCount = cava$vanillaPlan(box, ref);
        if (PushRuntime.BENCH) {
            PushRuntime.bpVanillaPlanNs.addAndGet(System.nanoTime() - t0);
            PushRuntime.bpTimed.incrementAndGet();
        }
        PushRuntime.bpShadowCompared.incrementAndGet();
        boolean same = refCount == nativeCount;
        for (int i = 0; same && i < refCount; i++) {
            same = ref[i] == m.outAt(i);
        }
        if (!same) {
            PushRuntime.bpShadowMismatch.incrementAndGet();
        }
    }

    /** 字节码 33-146 的逐条转写（容量不足返回 -1）。 */
    private int cava$vanillaPlan(Box box, long[] out) {
        final int xMin = ChunkSectionPos.getSectionCoord(box.minX - 2.0);
        final int yMin = ChunkSectionPos.getSectionCoord(box.minY - 4.0);
        final int zMin = ChunkSectionPos.getSectionCoord(box.minZ - 2.0);
        final int xMax = ChunkSectionPos.getSectionCoord(box.maxX + 2.0);
        final int yMax = ChunkSectionPos.getSectionCoord(box.maxY + 0.0);
        final int zMax = ChunkSectionPos.getSectionCoord(box.maxZ + 2.0);
        int k = 0;
        for (int x = xMin; x <= xMax; x++) {
            final long minKey = ChunkSectionPos.asLong(x, 0, 0);
            final long maxKey = ChunkSectionPos.asLong(x, -1, -1);
            for (long pos : trackedPositions.subSet(minKey, maxKey + 1)) {
                final int y = ChunkSectionPos.unpackY(pos);
                final int z = ChunkSectionPos.unpackZ(pos);
                if (y < yMin || y > yMax || z < zMin || z > zMax) {
                    continue;
                }
                if (k >= out.length) {
                    return -1;
                }
                out[k++] = pos;
            }
        }
        return k;
    }
}
