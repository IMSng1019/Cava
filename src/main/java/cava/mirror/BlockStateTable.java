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
    /** 构建时做的自检结果：commonNodeType(flags) == path_type_idx 对全部状态成立。 */
    private volatile boolean tableSelfConsistent;
    private int selfCheckMismatches = -1;
    /** 位置/上下文相关形状的守卫表（captain 裁决 1）：true = 该状态必须让调用方回退。 */
    private volatile boolean[] shapeGuarded;
    private int guardedStateCount = -1;
    private final java.util.List<String> guardedClasses = new java.util.ArrayList<>();

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
            runSelfCheck();
            runShapeGuardCensus();
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

    /**
     * 构建时自检：对**全部**状态验证 {@code MirrorFlags.commonNodeType(flags) == path_type_idx}。
     *
     * <p>为什么值得花这一遍（26644 次整数运算，实测 &lt; 1 ms）：flags 与 path_type_idx 是同一套
     * 原版语义的两种表达，任何一位填反/填漏都会让两者对不上 —— 这是**唯一**能在真实注册表上
     * 抓住"位填错"的廉价报警器（内核只读 flags，不会替我们发现）。
     */
    private void runSelfCheck() {
        int mismatch = 0;
        String first = null;
        for (int id = 0; id < data.stateCount; id++) {
            int derived = MirrorFlags.commonNodeType(data.flags(id));
            if (derived != data.pathTypeIdx(id)) {
                mismatch++;
                if (first == null) {
                    first = "id=" + id + " 位推=" + PathTypes.name(derived) + " 表=" + PathTypes.name(data.pathTypeIdx(id));
                }
            }
        }
        selfCheckMismatches = mismatch;
        tableSelfConsistent = mismatch == 0;
        stats.notes.add("状态表自检（commonNodeType(flags) vs path_type_idx）：不一致 " + mismatch + " 条"
                + (first == null ? "" : "，首条 " + first));
        if (mismatch != 0) {
            LOG.error("[cava/mirror] 状态表自检失败：{} 条不一致（首条 {}）—— 镜像侧不可用，调用方必须回退",
                    mismatch, first);
        }
    }

    /**
     * **形状守卫普查**（captain 裁决 1，"绝不静默发散"）。
     *
     * <p>两步：
     * <ol>
     *   <li><b>静态</b>：{@link McStateProbe#shapeSensitiveBlockClasses()} —— 覆写了带
     *       world/pos/ShapeContext 的形状方法的方块类（基类不看这些参数，所以只有覆写者才可能相关）；</li>
     *   <li><b>经验</b>：对这些类的每个状态，用 18 个合成上下文（邻居=空气/石头/自身 × 两个位置 ×
     *       ShapeContext=absent/实体在上方/下降）重算碰撞盒；与基准不同 ⇒ 记为守卫。</li>
     * </ol>
     * 只有"静态命中 **且** 经验上真的会变"的状态才守卫 —— 这样既保守（会变的都挡掉），
     * 又不会把"声明了参数但其实不看"的方块（例如流体：形状恒为空）误挡。
     *
     * <p>成本实测写进 docs §3.1；不在热路径上（一次构建一次）。
     */
    private void runShapeGuardCensus() {
        long begin = System.nanoTime();
        java.util.Set<String> sensitive = McStateProbe.shapeSensitiveBlockClasses();
        boolean[] guarded = new boolean[data.stateCount];
        int count = 0;
        java.util.Set<String> classes = new java.util.TreeSet<>();
        for (int id = 0; id < data.stateCount; id++) {
            net.minecraft.block.BlockState st = Block.getStateFromRawId(id);
            if (!sensitive.contains(st.getBlock().getClass().getName())) {
                continue;
            }
            if (McStateProbe.shapeVariesAcrossContexts(st)) {
                guarded[id] = true;
                count++;
                classes.add(st.getBlock().getClass().getSimpleName());
            }
        }
        shapeGuarded = guarded;
        guardedStateCount = count;
        guardedClasses.clear();
        guardedClasses.addAll(classes);
        long ms = (System.nanoTime() - begin) / 1_000_000;
        stats.notes.add("形状守卫：静态敏感类 " + sensitive.size() + " 个，其中经验上真的会变的 "
                + classes.size() + " 个类 / " + count + " 个状态被守卫（普查耗时 " + ms + " ms）");
        stats.notes.add("守卫类清单: " + classes);
        LOG.info("[cava/mirror] 形状守卫普查：静态敏感类 {}，经验命中 {} 类 / {} 状态，耗时 {} ms；类={}",
                sensitive.size(), classes.size(), count, ms, classes);
    }

    /** 该状态是否必须让调用方回退（位置/上下文相关形状）。 */
    @Override
    public boolean isShapeGuarded(int stateId) {
        boolean[] g = shapeGuarded;
        return g != null && stateId >= 0 && stateId < g.length && g[stateId];
    }

    /** 被守卫的状态数（-1 = 还没构建）。 */
    public int guardedStateCount() {
        return guardedStateCount;
    }

    /** 被守卫的方块类名（诊断用）。 */
    public java.util.List<String> guardedClasses() {
        return java.util.List.copyOf(guardedClasses);
    }

    /** 自检是否通过（{@link StateTableGate#selfConsistent()}）。 */
    @Override
    public boolean selfConsistent() {
        return tableSelfConsistent;
    }

    /** 自检不一致的条数（-1 = 还没构建）。 */
    public int selfCheckMismatches() {
        return selfCheckMismatches;
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
