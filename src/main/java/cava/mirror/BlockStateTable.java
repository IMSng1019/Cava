package cava.mirror;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 方块状态表：**每个 state id → 一条 {@code CavaStateRecord}**，一次性上传给原生侧。
 *
 * <p>三条纪律（缺一不可）：
 * <ol>
 *   <li><b>键是 state id</b>（{@code Block.getRawIdFromState} / {@code getStateFromRawId}），
 *       <b>禁止对象身份</b> —— FerriteCore 的 blockstateCacheDeduplication 会让内容相同的状态共享实例。</li>
 *   <li><b>碰撞盒是扁平 AABB 集合</b>（{@code VoxelShape.getBoundingBoxes()}），不是体素近似。</li>
 *   <li><b>一次性上传</b>，绝不每次寻路重传（表是几千到上万条）。</li>
 * </ol>
 *
 * <p>{@code Block.getRawIdFromState(Blocks.AIR.getDefaultState()) == 0} 这条契约假设**在启动时实测**，
 * 不成立时打 ERROR 并写进台账（不静默）——注意映射本身不依赖它（id 是不透明整数，
 * 原生按 id 查表），受影响的只是"区域外 = 空气"这条内核约定。
 */
public final class BlockStateTable implements StateTableGate {

    private static final Logger LOG = LoggerFactory.getLogger("cava/mirror");
    private static final BlockStateTable INSTANCE = new BlockStateTable();

    public static BlockStateTable get() {
        return INSTANCE;
    }

    private StateTableData data;
    private StateTableStats stats;
    private String failure = "(尚未构建)";
    private int epoch;
    private boolean uploaded;

    private BlockStateTable() {
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /** 构建状态表（纯 Java + 注册表，不需要原生库）。幂等。 */
    public synchronized boolean build() {
        if (data != null) {
            return true;
        }
        try {
            StateTableBuilder.Result r = StateTableBuilder.build(new McStateProbe());
            data = r.data();
            stats = r.stats();
            int airId = Block.getRawIdFromState(Blocks.AIR.getDefaultState());
            stats.airStateId = airId;
            stats.airIsZero = airId == 0;
            stats.notes.add("碰撞形状采样视图 = ProbeWorldView.MODE_EMPTY（邻居=空气；见 docs/CAVA-mirror-notes.md §3）");
            stats.notes.add("ShapeContext = absent()（原版真实碰撞查询用 ShapeContext.of(entity)，"
                    + "ABI 没有这个通道）");
            if (!stats.airIsZero) {
                String msg = "契约假设不成立：Block.getRawIdFromState(AIR)=" + airId + "（期望 0）。"
                        + "状态表本身仍然正确（id 是不透明整数），但『区域外=空气』这条内核约定要用 airId 填。";
                stats.notes.add(msg);
                LOG.error("[cava/mirror] {}", msg);
            }
            failure = null;
            LOG.info("[cava/mirror] 方块状态表构建完成：状态 {} 条 / 碰撞盒 {} 个，耗时 {} ms（air id={}）",
                    stats.stateCount, stats.boxTotal,
                    String.format("%.2f", stats.buildNanos / 1_000_000.0), airId);
            return true;
        } catch (Throwable t) {
            data = null;
            stats = null;
            failure = "构建状态表失败: " + t;
            LOG.error("[cava/mirror] 构建状态表失败（镜像侧不可用，调用方必须回退原逻辑）", t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    /** 构建 + **一次性**上传。原生不可用 / 上传失败 → false（调用方回退）。幂等。 */
    @Override
    public synchronized boolean uploadIfNeeded() {
        if (uploaded) {
            return true;
        }
        CavaNative nat = CavaNative.get();
        if (!nat.available()) {
            failure = "原生不可用: status=" + nat.status() + " (" + nat.detail() + ")";
            return false;
        }
        if (!build()) {
            return false;
        }
        long handle = nat.handle();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment recs = CavaNative.allocateArray(arena, CavaLayouts.STATE_RECORD, data.stateCount);
            data.writeRecords(recs);
            MemorySegment boxes = MemorySegment.NULL;
            if (data.boxTotal > 0) {
                boxes = CavaNative.allocateArray(arena, CavaLayouts.COLLISION_BOX, data.boxTotal);
                data.writeBoxes(boxes);
            }
            long begin = System.nanoTime();
            int rc = nat.stateTableUpload(handle, recs, data.stateCount, boxes, data.boxTotal);
            stats.uploadNanos = System.nanoTime() - begin;
            if (rc != CavaLayouts.CAVA_OK) {
                failure = "cava_state_table_upload → " + CavaLayouts.errorName(rc);
                LOG.error("[cava/mirror] {}（镜像侧不可用，调用方必须回退）", failure);
                return false;
            }
        } catch (Throwable t) {
            failure = "上传状态表抛出异常: " + t;
            LOG.error("[cava/mirror] " + failure, t);
            return false;
        }
        uploaded = true;
        epoch++;
        stats.tableEpoch = epoch;
        failure = null;
        LOG.info("[cava/mirror] 方块状态表已上传（一次性）：records={} boxes={} 上传耗时 {} ms",
                data.stateCount, data.boxTotal, String.format("%.3f", stats.uploadNanos / 1_000_000.0));
        return true;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public synchronized boolean ready() {
        return uploaded;
    }

    public synchronized StateTableData data() {
        return data;
    }

    public synchronized StateTableStats stats() {
        return stats;
    }

    @Override
    public synchronized String failure() {
        return failure;
    }

    /** 表版本（每次成功上传 +1）。 */
    public synchronized int epoch() {
        return epoch;
    }

    /** 表已经上传过时应使用的 air state id（区域外填充用）。 */
    @Override
    public synchronized int airStateId() {
        return stats == null ? 0 : Math.max(0, stats.airStateId);
    }

    public synchronized String report() {
        if (stats == null) {
            return "状态表：" + (failure == null ? "(未构建)" : failure);
        }
        return stats.report();
    }

    /**
     * **位置依赖碰撞形状普查**（文档/测试用，不在热路径上）：把"邻居=空气"与"邻居=自身（全连接）"
     * 两种视图各构建一次，统计盒集合不同的状态数。
     *
     * <p>这个数字就是"冻结 ABI 表达不了的那部分"的量化 —— 写进 docs/CAVA-mirror-notes.md §3。
     */
    public static String census() {
        StateTableBuilder.Result empty = StateTableBuilder.build(new McStateProbe(ProbeWorldView.MODE_EMPTY));
        StateTableBuilder.Result self = StateTableBuilder.build(new McStateProbe(ProbeWorldView.MODE_SELF));
        return StateTableBuilder.compareBoxes(empty.data(), self.data(),
                id -> String.valueOf(Block.getStateFromRawId(id).getBlock()), 12);
    }
}
