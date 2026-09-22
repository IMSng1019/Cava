package cava.shape;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelSet;
import net.minecraft.util.shape.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 的**常驻形状表**：每个 state id → (点表 + 体素位图)，一次性上传给原生。
 *
 * <h2>与 P1 的状态表是两张不同的表</h2>
 * P1（{@code cava.mirror.BlockStateTable}）存的是**扁平 AABB**（寻路用）；
 * 本表存的是**体素网格**（碰撞求解用）。两者都按 state id 索引，但内容、用途、原生侧的容器全都不同
 * （{@code cava_shape_table_upload} vs {@code cava_state_table_upload}）。
 *
 * <h2>三条纪律</h2>
 * <ol>
 *   <li>键是 <b>state id</b>（{@code Block.getStateFromRawId}），**禁止对象身份做键** ——
 *       FerriteCore 那类 mod 会让内容相同的状态共享实例。</li>
 *   <li>形状的**身份**仍然保留：本类额外维护一个
 *       {@code IdentityHashMap<VoxelSet, Integer>}（{@code ==} 比较），
 *       它是运行期「这个形状是不是就是常驻表里那一个」的 O(1) 候选查找。
 *       它**只用来找候选**，命中之后还要逐点精确校验（见 {@link MoveShapeBatch}）。</li>
 *   <li>一次性上传；cap 不足/校验不过由原生返回 {@code CAVA_ERR_ARG} 且**不改变已有表**。</li>
 * </ol>
 *
 * <h2>候选几何是怎么采的</h2>
 * {@code state.getCollisionShape(ShapeProbeView(空气), PROBE_POS)} —— 与 P1 镜像同一档
 * （邻居=空气、{@code ShapeContext.absent()}）。**它只是候选**：真实求解时每个形状都会与它
 * 做逐点逐位比对，不一致就走 INLINE。所以这一档的选择**不构成 parity 风险**。
 */
public final class ShapeTable {

    private static final Logger LOG = LoggerFactory.getLogger("cava/entity");
    private static final ShapeTable INSTANCE = new ShapeTable();

    /** 采样位置（与 P1 的 {@code McStateProbe.PROBE_POS} 同值，便于对照）。 */
    public static final BlockPos PROBE_POS = new BlockPos(8, 64, 8);

    public static ShapeTable get() {
        return INSTANCE;
    }

    private final ShapeProbeView view = new ShapeProbeView();

    /** state id → 该状态的几何（**记录基准**；运行期命中判定用）。 */
    private ShapeGeometry[] byState;
    /** 常驻表里每条记录在扁平点表/位图里的位置（建表期算好，上传时直接用）。 */
    private int[] recordPointOffset;
    private int[] recordBitOffset;
    private int[] recordPointSpan;
    /** {@code VoxelSet} 身份 → 一个拥有该体素集的 state id（候选；不是证明）。 */
    private IdentityHashMap<VoxelSet, Integer> voxelSetOwner;
    /** state id → 该状态的 {@code VoxelSet}（建表期抓的**同一个对象**，运行期用 {@code ==} 比）。 */
    private VoxelSet[] stateVoxels;

    private ShapeTableStats stats;
    private String failure = "(尚未构建)";
    private boolean uploaded;
    private boolean usable;

    private ShapeTable() {
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /** 构建（纯 Java + 注册表，不需要原生库）。幂等。 */
    public synchronized boolean build() {
        if (byState != null) {
            return usable;
        }
        long begin = System.nanoTime();
        try {
            int n = Block.STATE_IDS.size();
            ShapeTableStats s = new ShapeTableStats();
            s.stateCount = n;
            s.recordCount = n;

            ShapeGeometry[] geoms = new ShapeGeometry[n];
            VoxelSet[] voxels = new VoxelSet[n];
            IdentityHashMap<VoxelSet, Integer> owners = new IdentityHashMap<>();
            int pointTotal = 0;
            int bitTotal = 0;
            long cellTotal = 0;
            int withShape = 0;
            int explicit = 0;
            int fractional = 0;

            int[] pOff = new int[n];
            int[] bOff = new int[n];
            int[] pSpan = new int[n];

            for (int id = 0; id < n; id++) {
                BlockState st = Block.getStateFromRawId(id);
                view.set(st);
                VoxelShape shape = st.getCollisionShape(view, PROBE_POS);
                ShapeGeometry g = ShapeGeometry.of(shape);
                geoms[id] = g;
                voxels[id] = ((cava.mixin.entity.VoxelShapeAccessor) shape).cava$voxels();
                owners.putIfAbsent(voxels[id], id);

                pOff[id] = pointTotal;
                bOff[id] = bitTotal;
                int span = (g.pointsKind == ShapeGeometry.POINTS_EXPLICIT) ? g.points.length : 0;
                pSpan[id] = span;
                pointTotal += span;
                bitTotal += g.bits.length;
                cellTotal += (long) g.sizeX * g.sizeY * g.sizeZ;
                if (g.isEmpty()) {
                    continue;
                }
                withShape++;
                if (g.pointsKind == ShapeGeometry.POINTS_EXPLICIT) {
                    explicit++;
                } else {
                    fractional++;
                }
            }

            s.statesWithShape = withShape;
            s.distinctVoxelSets = owners.size();
            s.pointTotal = pointTotal;
            s.bitWordTotal = bitTotal;
            s.cellTotal = cellTotal;
            s.explicitStates = explicit;
            s.fractionalStates = fractional;

            byState = geoms;
            stateVoxels = voxels;
            voxelSetOwner = owners;
            recordPointOffset = pOff;
            recordBitOffset = bOff;
            recordPointSpan = pSpan;
            stats = s;
            s.buildNanos = System.nanoTime() - begin;
            usable = true;
            failure = null;
            LOG.info("[cava/entity] {}", s.report());
            return true;
        } catch (Throwable t) {
            byState = null;
            stats = null;
            usable = false;
            failure = "构建形状表失败: " + t;
            LOG.error("[cava/entity] {}（实体位移原生路径不可用，调用方回退纯 Java）", failure, t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 上传
    // ------------------------------------------------------------------

    /** 构建 + **一次性**上传。原生不可用 / 上传失败 → false（调用方回退）。幂等。 */
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
            int n = stats.recordCount;
            MemorySegment recs = CavaNative.allocateArray(arena, CavaLayouts.SHAPE_RECORD, n);
            for (int id = 0; id < n; id++) {
                ShapeGeometry g = byState[id];
                long o = (long) id * AbiOffsets.SHAPE_RECORD_SIZE;
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_POINTS_KIND, g.pointsKind);
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_POINT_OFFSET, recordPointOffset[id]);
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_BIT_OFFSET, recordBitOffset[id]);
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_BIT_WORDS, g.bits.length);
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_SIZE_X, g.sizeX);
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_SIZE_Y, g.sizeY);
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_SIZE_Z, g.sizeZ);
                recs.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_RESERVED0, 0);
            }
            MemorySegment pts = MemorySegment.NULL;
            if (stats.pointTotal > 0) {
                pts = CavaNative.allocateArray(arena, ValueLayout.JAVA_DOUBLE, stats.pointTotal);
                int at = 0;
                for (int id = 0; id < n; id++) {
                    double[] p = byState[id].points;
                    if (p == null) {
                        continue;
                    }
                    MemorySegment.copy(p, 0, pts, ValueLayout.JAVA_DOUBLE, (long) at * 8, p.length);
                    at += p.length;
                }
            }
            MemorySegment bits = MemorySegment.NULL;
            if (stats.bitWordTotal > 0) {
                bits = CavaNative.allocateArray(arena, ValueLayout.JAVA_LONG, stats.bitWordTotal);
                int at = 0;
                for (int id = 0; id < n; id++) {
                    long[] b = byState[id].bits;
                    if (b.length == 0) {
                        continue;
                    }
                    MemorySegment.copy(b, 0, bits, ValueLayout.JAVA_LONG, (long) at * 8, b.length);
                    at += b.length;
                }
            }
            long t0 = System.nanoTime();
            int rc = nat.shapeTableUpload(handle, recs, n, pts, stats.pointTotal, bits, stats.bitWordTotal);
            stats.uploadNanos = System.nanoTime() - t0;
            if (rc != CavaLayouts.CAVA_OK) {
                failure = "cava_shape_table_upload → " + CavaLayouts.errorName(rc);
                LOG.error("[cava/entity] {}（实体位移原生路径不可用，调用方回退）", failure);
                usable = false;
                return false;
            }
            stats.uploadedRecords = n;
        } catch (Throwable t) {
            failure = "上传形状表抛出异常: " + t;
            LOG.error("[cava/entity] " + failure, t);
            usable = false;
            return false;
        }
        uploaded = true;
        failure = null;
        LOG.info("[cava/entity] 形状表已上传（一次性）：records={} points={} bitWords={} 上传耗时 {} ms",
                stats.recordCount, stats.pointTotal, stats.bitWordTotal,
                String.format("%.3f", stats.uploadNanos / 1_000_000.0));
        return true;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public synchronized boolean ready() {
        return uploaded && usable;
    }

    /** 记录数（= 状态数）。 */
    public synchronized int recordCount() {
        return stats == null ? 0 : stats.recordCount;
    }

    public synchronized ShapeTableStats stats() {
        return stats;
    }

    public synchronized String failure() {
        return failure;
    }

    /** 该 state id 的候选记录下标（就是它自己）。 */
    public synchronized int recordOf(int stateId) {
        return stateId;
    }

    /** state id 的候选几何（可能为 {@code null} = 表没建）。 */
    public synchronized ShapeGeometry geometryOf(int stateId) {
        return byState == null || stateId < 0 || stateId >= byState.length ? null : byState[stateId];
    }

    /** 建表期抓到的该状态的 {@code VoxelSet} 对象（运行期用 {@code ==} 比身份）。 */
    public synchronized VoxelSet voxelsOf(int stateId) {
        return stateVoxels == null || stateId < 0 || stateId >= stateVoxels.length ? null : stateVoxels[stateId];
    }

    /** 拥有该 {@code VoxelSet} 对象的候选 state id；没有则 -1。 */
    public synchronized int ownerOf(VoxelSet voxels) {
        if (voxelSetOwner == null || voxels == null) {
            return -1;
        }
        Integer v = voxelSetOwner.get(voxels);
        return v == null ? -1 : v;
    }

    public synchronized int pointOffsetOf(int stateId) {
        return recordPointOffset == null ? 0 : recordPointOffset[stateId];
    }

    public synchronized int bitOffsetOf(int stateId) {
        return recordBitOffset == null ? 0 : recordBitOffset[stateId];
    }

    public synchronized String report() {
        return stats == null ? "形状表：" + failure : stats.report();
    }

    /** 供测试注入（把表重置）。 */
    synchronized void resetForTest() {
        byState = null;
        stateVoxels = null;
        voxelSetOwner = null;
        recordPointOffset = null;
        recordBitOffset = null;
        recordPointSpan = null;
        stats = null;
        uploaded = false;
        usable = false;
        failure = "(尚未构建)";
    }

    /** 诊断：{@code VoxelSet} 身份表规模（单测断言用）。 */
    synchronized Map<VoxelSet, Integer> identityMapView() {
        return voxelSetOwner;
    }
}
