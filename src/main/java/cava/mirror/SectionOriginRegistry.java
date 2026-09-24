package cava.mirror;

import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **方块变更失效源**（缺陷 1 的修复件，2026-09-24 由 P1-FIX 流加）。
 *
 * <p><b>为什么需要它</b>：{@code RegionMirror.pushForSolve} 的复用判据原本是"同矩形 + 自上次上传以来
 * 没有失效事件"，而失效事件只有 {@code onBlockChanged / onSectionUnloaded / onWorldChanged} 三个入口，
 * 这三个方法在 {@code src/main/java} 的生产路径里**一次都没被调用过**（只有 bench 诊断在调）
 * ⇒ 地形变了原生不知道。实测后果：{@code detour128/reuse} 腿原生路径**穿过 8 格实心石头**
 * （{@code collisionNodes=8 firstBadNode=(96,71,0)}，见 {@code docs/CAVA-pathfind-perf.md} §6.3）。
 *
 * <p>本类把契约（{@code docs/CAVA-hook-points.md} 第 17 行）指定的**主钩子
 * {@code ChunkSection.setBlockState(method_12256)}** 接到镜像失效上。钩子本体是
 * {@code cava.mixin.mirror.ChunkSectionSetBlockStateMixin}（只观察、不 cancel、不 redirect）。
 *
 * <p><b>坐标问题（本机 javap 实测）</b>：{@code ChunkSection.setBlockState(int,int,int,BlockState,boolean)}
 * 收的是**区段局部坐标**，而 {@code ChunkSection} 类里**没有任何位置字段**（实测只有
 * {@code nonEmptyBlockCount/randomTickableBlockCount/nonEmptyFluidCount/blockStateContainer/biomeContainer}）
 * ⇒ 光靠这个钩子拿不到世界坐标。解法：镜像推送时（{@code ServerWorldRegionReader.fill} 逐区段读世界）
 * 把"区段实例 → 区段原点"登记进本类的**身份表**；钩子用 {@code this}（区段实例）做身份查表得到原点，
 * 再判断落点是否在当前缓存矩形内。**身份比较而不是 equals**：{@code ChunkSection} 没有覆写 equals，
 * 用 IdentityHashMap 语义明确且更快。
 *
 * <p><b>热路径纪律</b>：每次方块写入只做
 * ①一条 volatile 读（没有缓存区域就直接返回）；
 * ②一次"上一次命中的区段"身份比较（世界生成/Axiom 填充都是聚簇写同一区段，这一条几乎总命中）；
 * ③未命中才做一次 IdentityHashMap.get。
 * 计数只在**已登记区段**上做（世界生成写的未登记区段走最便宜的返回路径，不计数）。
 */
public final class SectionOriginRegistry {

    /** 失效源总开关：{@code -Dcava.mirror.invalidation=false} 关掉（**只给可证伪对照用**）。 */
    public static final String PROP_INVALIDATION = "cava.mirror.invalidation";

    private static final Logger LOG = LoggerFactory.getLogger("cava/mirror");

    /** 已发布的快照：**一次 volatile 写**整体替换（不可变内容）。 */
    private static volatile Snapshot published;

    /** 待登记（一次 push 的两阶段：begin → reader 登记 → publish）。 */
    private static IdentityHashMap<Object, int[]> pending;

    /** 单条快速路径：写入通常聚簇在同一个区段。 */
    private static volatile Hit lastHit;

    private static final LongAdder sectionHits = new LongAdder();
    private static final LongAdder inWindowHits = new LongAdder();
    private static final LongAdder outsideWindow = new LongAdder();
    private static final LongAdder invalidations = new LongAdder();
    private static final LongAdder disabledSkips = new LongAdder();
    private static final AtomicLong hookErrors = new AtomicLong();
    private static final AtomicLong publishes = new AtomicLong();

    private static final class Snapshot {
        final RegionRect rect;
        final IdentityHashMap<Object, int[]> origins;

        Snapshot(RegionRect rect, IdentityHashMap<Object, int[]> origins) {
            this.rect = rect;
            this.origins = origins;
        }
    }

    private static final class Hit {
        final Object section;
        final int ox;
        final int oy;
        final int oz;

        Hit(Object section, int ox, int oy, int oz) {
            this.section = section;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }
    }

    private SectionOriginRegistry() {
    }

    // ------------------------------------------------------------------
    // 登记（镜像推送侧）
    // ------------------------------------------------------------------

    /** 开始一次推送的登记（丢弃上一次未发布的登记内容）。 */
    static void beginWindow() {
        pending = new IdentityHashMap<>();
    }

    /**
     * 登记一个"本次推送覆盖到的区段"。
     *
     * <p><b>必须把所有覆盖到的区段都登记**（含 {@code isEmpty()} 的空区段）**：空区段现在全是空气，
     * 但它随时可能被写入 —— 漏登记就等于漏失效。
     */
    static void register(Object section, int originX, int originY, int originZ) {
        if (section == null) {
            return;
        }
        IdentityHashMap<Object, int[]> map = pending;
        if (map != null) {
            map.put(section, new int[] {originX, originY, originZ});
        }
    }

    /** 推送成功后发布：此后这些区段里的写入都能被发现。 */
    static void publish(RegionRect rect) {
        IdentityHashMap<Object, int[]> map = pending;
        pending = null;
        if (rect == null || map == null) {
            return;
        }
        published = new Snapshot(rect, map);
        lastHit = null;
        publishes.incrementAndGet();
    }

    /**
     * 放弃本次未发布的登记（推送失败时用）：**保留上一次已发布的快照**。
     *
     * <p>为什么不能在这里清空：一次失败的推送（OOM / ARG / 形状守卫拒绝）**不会**改掉原生里的内容
     * （ABI 约定：错误码路径不部分写入），而 {@code RegionMirror.lastRect} 也仍然指向那个旧矩形 ⇒
     * 旧矩形里的写入必须继续让它失效。清空登记表会留下"旧矩形还在用、但写入不再失效"的洞。
     */
    static void abortWindow() {
        pending = null;
    }

    /** 原生侧区域被清空（换世界/换维度/显式 clear）：此后没有任何缓存内容需要失效。 */
    static void clearWindow() {
        pending = null;
        published = null;
        lastHit = null;
    }

    /** 当前缓存矩形（没有 = null）。诊断用。 */
    public static RegionRect cachedRect() {
        Snapshot s = published;
        return s == null ? null : s.rect;
    }

    // ------------------------------------------------------------------
    // 钩子入口（mixin 调用）
    // ------------------------------------------------------------------

    /**
     * {@code ChunkSection.setBlockState} 的观察入口（**局部坐标**）。
     *
     * <p>绝不抛异常：它挂在方块写入路径上，抛出去就是世界写入被中断。
     *
     * @param section 区段实例（mixin 里的 {@code this}），只做身份比较
     * @param lx      区段局部坐标 0..15
     */
    public static void onSectionBlockWrite(Object section, int lx, int ly, int lz) {
        Snapshot s = published;
        if (s == null) {
            return;   // 没有缓存区域：这次写入不可能让镜像变陈旧
        }
        try {
            Hit h = lastHit;
            int ox;
            int oy;
            int oz;
            if (h != null && h.section == section) {
                ox = h.ox;
                oy = h.oy;
                oz = h.oz;
            } else {
                int[] o = s.origins.get(section);
                if (o == null) {
                    return;   // 未登记的区段（世界生成/邻区）—— 不在缓存矩形里
                }
                ox = o[0];
                oy = o[1];
                oz = o[2];
                lastHit = new Hit(section, ox, oy, oz);
            }
            sectionHits.increment();
            int x = ox + lx;
            int y = oy + ly;
            int z = oz + lz;
            if (!s.rect.contains(x, y, z)) {
                outsideWindow.increment();
                return;   // 在缓存矩形外：推的内容里没有这一格，不需要失效
            }
            inWindowHits.increment();
            if (!invalidationEnabled()) {
                disabledSkips.increment();
                return;
            }
            RegionMirror mirror = mirror();
            if (mirror == null) {
                return;
            }
            mirror.onBlockChanged(x, y, z);
            invalidations.increment();
        } catch (Throwable t) {
            if (hookErrors.incrementAndGet() <= 3) {
                LOG.error("[cava/mirror] 方块变更钩子抛出异常（已吞掉，绝不中断世界写入；第 {} 次）",
                        hookErrors.get(), t);
            }
        }
    }

    private static RegionMirror mirror() {
        return MirrorFactory.instance() instanceof RegionMirror m ? m : null;
    }

    /** 失效源是否开启（默认开；{@code -Dcava.mirror.invalidation=false} 关闭，**只给对照**）。 */
    public static boolean invalidationEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP_INVALIDATION, "true"));
    }

    // ------------------------------------------------------------------
    // 台账（金丝雀 / 回执）
    // ------------------------------------------------------------------

    public static long sectionHits() {
        return sectionHits.sum();
    }

    /** **失效命中次数**：写入落在缓存矩形内（钩子真的起作用的那一档）。 */
    public static long inWindowHits() {
        return inWindowHits.sum();
    }

    public static long outsideWindow() {
        return outsideWindow.sum();
    }

    /** 实际调了 {@code RegionMirror.onBlockChanged} 的次数。 */
    public static long invalidations() {
        return invalidations.sum();
    }

    public static long disabledSkips() {
        return disabledSkips.sum();
    }

    public static long hookErrors() {
        return hookErrors.get();
    }

    public static long publishes() {
        return publishes.get();
    }

    /** 一行台账。 */
    public static String stats() {
        RegionRect r = cachedRect();
        return "方块变更钩子: 登记窗口 " + publishes.get() + " 次（当前 "
                + (r == null ? "(无)" : r.describe()) + "）"
                + " / 已登记区段命中 " + sectionHits.sum()
                + " / **窗口内命中 " + inWindowHits.sum() + "**"
                + " / 窗口外 " + outsideWindow.sum()
                + " / 失效 " + invalidations.sum()
                + " / 关开关跳过 " + disabledSkips.sum()
                + " / 异常 " + hookErrors.get()
                + " / 失效源=" + (invalidationEnabled() ? "开" : "**关**");
    }

    // ------------------------------------------------------------------
    // 单测入口（生产路径不走）
    // ------------------------------------------------------------------

    /** 单测：直接装一个快照（不需要真实世界）。 */
    public static void installForTest(RegionRect rect, Object section, int originX, int originY, int originZ) {
        IdentityHashMap<Object, int[]> map = new IdentityHashMap<>();
        map.put(section, new int[] {originX, originY, originZ});
        published = new Snapshot(rect, map);
        lastHit = null;
    }

    /** 单测：往已装的快照里再加一个区段。 */
    public static void addSectionForTest(Object section, int originX, int originY, int originZ) {
        Snapshot s = published;
        if (s == null) {
            throw new IllegalStateException("先调 installForTest");
        }
        s.origins.put(section, new int[] {originX, originY, originZ});
        lastHit = null;
    }

    /** 单测：清空快照与计数。 */
    public static void resetForTest() {
        published = null;
        pending = null;
        lastHit = null;
        sectionHits.reset();
        inWindowHits.reset();
        outsideWindow.reset();
        invalidations.reset();
        disabledSkips.reset();
    }
}
