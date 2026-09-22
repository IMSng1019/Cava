package cava.mirror;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区域镜像：把"寻路窗口"这个有界长方体的 state id 推给原生侧（{@code cava_region_upload}）。
 *
 * <p><b>三个已定决策</b>（理由见 docs/CAVA-mirror-notes.md §3）：
 * <ol>
 *   <li>窗口尺寸由 {@link RegionRect#forSolve} **统一推导**（边距出处写在那个类里），
 *       并把"推送耗时 vs 窗口尺寸"实测成表（{@code RegionMirrorPerfTest}）。</li>
 *   <li><b>默认每次求解都重推</b>（数据必然新鲜）；同 tick 同矩形可选用
 *       {@link #pushReusingSameTick}（快，但会漏掉 tick 内的方块变化）。</li>
 *   <li><b>区段卸载 / 世界变更</b>：本轮只做"最简失效" —— {@link #onSectionUnloaded} /
 *       {@link #onBlockChanged} / {@link #onWorldChanged} 只记数 + 让同 tick 复用失效，
 *       是留给后续脏跟踪流的**口子**。</li>
 * </ol>
 *
 * <p>所有失败（原生不可用 / 表未上传 / 区块未加载 / 区域过大 / 原生返回错误码）都抛
 * {@link MirrorUnavailableException} —— 调用方据此**回退原逻辑**，绝不静默降级。
 */
public final class RegionMirror implements RegionSource {

    /** 区域体积上限（方块数）；超过即回退。默认 2M 方块 = 8 MB int 数组。 */
    public static final String PROP_MAX_VOLUME = "cava.mirror.region.max.volume";
    /** 默认上限。 */
    public static final int DEFAULT_MAX_VOLUME = 1 << 21;

    private static final Logger LOG = LoggerFactory.getLogger("cava/mirror");

    private final RegionReader reader;
    private final RegionUploader uploader;
    private final StateTableGate table;
    private final Object lock = new Object();

    private int[] buffer = new int[1 << 14];

    private RegionRect lastRect;
    private String lastDim = "";
    private long lastTick = Long.MIN_VALUE;
    private int lastCells;

    private long pushes;
    private long failures;
    private long reuseSkips;
    private long cellsCopied;
    private long fillNanos;
    private long allocNanos;
    private long uploadNanos;
    private long totalNanos;
    private long invalidationCount;
    private String lastInvalidation = "(无)";
    private volatile PushDetail lastDetail;

    public RegionMirror(RegionReader reader, RegionUploader uploader, StateTableGate table) {
        this.reader = reader;
        this.uploader = uploader;
        this.table = table;
    }

    /** 真实服务器世界的镜像。 */
    public static RegionMirror forWorld(ServerWorld world) {
        return new RegionMirror(new ServerWorldRegionReader(world), new NativeRegionUploader(), BlockStateTable.get());
    }

    /** 一次推送的耗时分解（实测台账用）。 */
    public record PushDetail(RegionRect rect, long fillNanos, long allocNanos, long uploadNanos, long totalNanos) {
        /** 总耗时（毫秒，便于直接打表）。 */
        public double totalMillis() {
            return totalNanos / 1_000_000.0;
        }
    }

    // ------------------------------------------------------------------
    // 推送
    // ------------------------------------------------------------------

    @Override
    public Pushed push(int minX, int minY, int minZ, int dimX, int dimY, int dimZ) {
        return push(new RegionRect(minX, minY, minZ, dimX, dimY, dimZ));
    }

    /** 推送一个矩形（每次都会真的重推；失败抛异常）。 */
    public Pushed push(RegionRect rect) {
        synchronized (lock) {
            long begin = System.nanoTime();
            int maxVolume = Integer.getInteger(PROP_MAX_VOLUME, DEFAULT_MAX_VOLUME);
            if (rect.volume() > maxVolume) {
                failures++;
                throw new MirrorUnavailableException("区域过大: " + rect.volume() + " > " + maxVolume + " "
                        + rect.describe());
            }
            if (!table.uploadIfNeeded()) {
                failures++;
                throw new MirrorUnavailableException("状态表未就绪: " + table.failure());
            }
            if (!uploader.available()) {
                failures++;
                throw new MirrorUnavailableException("原生不可用");
            }
            if (!reader.isReady(rect.minX(), rect.minY(), rect.minZ(), rect.dimX(), rect.dimY(), rect.dimZ())) {
                failures++;
                throw new MirrorUnavailableException("区块未加载，无法推送区域: " + rect.describe());
            }
            int cells = (int) rect.volume();
            ensureBuffer(cells);

            long t0 = System.nanoTime();
            reader.fill(rect.minX(), rect.minY(), rect.minZ(), rect.dimX(), rect.dimY(), rect.dimZ(),
                    buffer, table.airStateId());
            long t1 = System.nanoTime();

            long allocCost;
            long uploadCost;
            int rc;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, cells);
                MemorySegment.copy(buffer, 0, seg, ValueLayout.JAVA_INT, 0L, cells);
                long t2 = System.nanoTime();
                rc = uploader.upload(uploader.handle(), rect.dimX(), rect.dimY(), rect.dimZ(),
                        rect.minX(), rect.minY(), rect.minZ(), seg, cells);
                long t3 = System.nanoTime();
                allocCost = t2 - t1;
                uploadCost = t3 - t2;
            }
            if (rc != CavaLayouts.CAVA_OK) {
                failures++;
                throw new MirrorUnavailableException("cava_region_upload → " + CavaLayouts.errorName(rc)
                        + " " + rect.describe());
            }
            long end = System.nanoTime();
            fillNanos += t1 - t0;
            allocNanos += allocCost;
            uploadNanos += uploadCost;
            totalNanos += end - begin;
            pushes++;
            cellsCopied += cells;
            lastRect = rect;
            lastDim = reader.dimensionId();
            lastTick = reader.currentTick();
            lastCells = cells;
            lastDetail = new PushDetail(rect, t1 - t0, allocCost, uploadCost, end - begin);
            if (pushes <= 3 || (pushes % 2000) == 0) {
                LOG.info("[cava/mirror] 区域推送 #{} {} cells={} 填={}us 分配拷贝={}us 上传={}us 总={}us",
                        pushes, rect.describe(), cells, (t1 - t0) / 1000, allocCost / 1000, uploadCost / 1000,
                        (end - begin) / 1000);
            }
            return new Pushed(rect.dimX(), rect.dimY(), rect.dimZ(), rect.minX(), rect.minY(), rect.minZ(),
                    cells, end - begin);
        }
    }

    /**
     * 同 tick / 同维度 / 同矩形时复用上一次推送（**可选优化**，默认不用）。
     *
     * <p>风险：同一 tick 内的方块变化（玩家/其他 mod 放置）会被漏掉 —— 所以默认路径是 {@link #push}。
     */
    public Pushed pushReusingSameTick(RegionRect rect) {
        synchronized (lock) {
            if (lastRect != null && lastRect.equals(rect) && lastDim.equals(reader.dimensionId())
                    && lastTick == reader.currentTick() && table.ready() && uploader.available()) {
                reuseSkips++;
                return new Pushed(rect.dimX(), rect.dimY(), rect.dimZ(), rect.minX(), rect.minY(), rect.minZ(),
                        lastCells, 0L);
            }
            return push(rect);
        }
    }

    /** 从"起点/终点 + 生物体型"直接推一个窗口（调用方不必自己算边距）。 */
    public Pushed pushForSolve(int sx, int sy, int sz, int tx, int ty, int tz,
                               float width, float height, int safeFallDistance) {
        return push(RegionRect.forSolve(sx, sy, sz, tx, ty, tz, width, height, safeFallDistance,
                reader.minY(), reader.maxY()));
    }

    @Override
    public void clear() {
        synchronized (lock) {
            lastRect = null;
            lastDim = "";
            lastTick = Long.MIN_VALUE;
            if (uploader.available()) {
                int rc = uploader.clear(uploader.handle());
                if (rc != CavaLayouts.CAVA_OK) {
                    LOG.warn("[cava/mirror] cava_region_clear → {}", CavaLayouts.errorName(rc));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 失效口子（后续脏跟踪流的接入点）
    // ------------------------------------------------------------------

    /** 某个区段被卸载 —— 本轮只让同 tick 复用失效。 */
    public void onSectionUnloaded(int chunkX, int chunkZ) {
        invalidate("区段卸载 (" + chunkX + "," + chunkZ + ")");
    }

    /** 某个方块变了 —— 本轮只让同 tick 复用失效。 */
    public void onBlockChanged(int x, int y, int z) {
        invalidate("方块变化 (" + x + "," + y + "," + z + ")");
    }

    /** 换世界 / 换维度 —— 本轮清掉原生区域缓存。 */
    public void onWorldChanged(String reason) {
        clear();
        invalidate("世界变更: " + reason);
    }

    private void invalidate(String reason) {
        lastTick = Long.MIN_VALUE;
        invalidationCount++;
        lastInvalidation = reason;
    }

    // ------------------------------------------------------------------
    // 生物档案
    // ------------------------------------------------------------------

    @Override
    public boolean isProfileReadyForSolve(long profileKey) {
        MobProfileSpec spec = MobProfiles.get(profileKey);
        return spec != null && spec.ready() && table.ready() && uploader.available();
    }

    @Override
    public boolean uploadProfileForSolve(long handle, long profileKey) {
        MobProfileSpec spec = MobProfiles.get(profileKey);
        if (spec == null) {
            MobProfiles.reportMissing(profileKey);
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(CavaLayouts.MOB_PROFILE);
            spec.writeTo(seg);
            int rc = uploader.mobProfileUpload(handle, seg);
            if (rc != CavaLayouts.CAVA_OK) {
                LOG.error("[cava/mirror] cava_mob_profile_upload → {}（本次求解必须回退）", CavaLayouts.errorName(rc));
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOG.error("[cava/mirror] 上传生物档案抛出异常（本次求解必须回退）", t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 台账
    // ------------------------------------------------------------------

    private void ensureBuffer(int cells) {
        if (buffer.length < cells) {
            buffer = new int[Math.max(cells, buffer.length * 2)];
        }
    }

    /** 最近一次推送的耗时分解。 */
    public PushDetail lastDetail() {
        return lastDetail;
    }

    /** 已推送次数。 */
    public long pushes() {
        return pushes;
    }

    /** 失败次数（每次都会抛异常给调用方）。 */
    public long failures() {
        return failures;
    }

    /** 同 tick 复用命中次数。 */
    public long reuseSkips() {
        return reuseSkips;
    }

    /** 累计推送的方块数。 */
    public long cellsCopied() {
        return cellsCopied;
    }

    /** 累计失效次数（区段卸载 / 方块变化 / 世界变更）。 */
    public long invalidationCount() {
        return invalidationCount;
    }

    /** 最近一次失效原因。 */
    public String lastInvalidation() {
        return lastInvalidation;
    }

    /** 多行台账（启动/收尾报告用）。 */
    public String report() {
        synchronized (lock) {
            long n = Math.max(1, pushes);
            return "区域镜像: 推送 " + pushes + " 次 / 失败 " + failures + " / 同tick复用 " + reuseSkips
                    + " / 方块 " + cellsCopied + '\n'
                    + "  平均每次: 总 " + String.format("%.2f", totalNanos / 1000.0 / n) + " us"
                    + "（填 " + String.format("%.2f", fillNanos / 1000.0 / n) + " us"
                    + " / 分配拷贝 " + String.format("%.2f", allocNanos / 1000.0 / n) + " us"
                    + " / 上传 " + String.format("%.2f", uploadNanos / 1000.0 / n) + " us）" + '\n'
                    + "  失效 " + invalidationCount + " 次，最近: " + lastInvalidation;
        }
    }
}
