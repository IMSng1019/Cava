package cava.shape;

import cava.mixin.entity.VoxelShapeAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelSet;
import net.minecraft.util.shape.VoxelShape;

/**
 * 一次 {@code cava_resolve_move} 调用的**形状列表**：refs[] + inline 几何 + {@code VoxelShape[] shapes}。
 *
 * <h2>{@code shape_token} = {@code shapes[]} 的下标（§5.0 的核心）</h2>
 * 对象身份**从不跨边界**：跨过去的只是一个 {@code int64} 下标。原生把它原样回写到事件里，
 * Java 侧回放时执行 {@code shapes[event.shape_token]} —— 那是**同一个引用**（{@code ==}），
 * 不是重建、不是 {@code equals}。这是「mod 覆写过的方块行为不丢」的落地方式。
 *
 * <h2>STATE vs INLINE：一个**可证明**的判定，不是一个启发式</h2>
 * 原版 {@code getBlockCollisions} 发出的永远是 {@code shape.offset(x,y,z)}（oracle spec §5.4/§3.8），
 * 而 {@code offset()} 的语义就是「体素集**按引用共享**、点表逐点加同一个常数」。
 * 所以对每个形状做下面这套**精确**校验，通过就说明「用常驻表的记录 + 平移量合成的几何」
 * 与真实对象**逐点逐位相同**：
 *
 * <pre>
 *   1. 该形状的 VoxelSet 与常驻表里某个 state 建表期抓到的对象**是同一个引用**（==）
 *      ⇒ 体素位图逐位相同（offset() 不会换掉 VoxelSet）；
 *   2. 对三个轴求 k_a = 形状点0 − 记录点0；k_a 必须是**整数值的 double**且落在 int32 内；
 *   3. 对三个轴的**每一个**点 i 验证：形状点(i) == (double)(int)k_a + 记录点(i)（精确 ==，无 epsilon）。
 * </pre>
 *
 * 三条同时成立 ⇒ 几何完全一致 ⇒ 原生按 STATE 合成出来的点表就是真实点表。
 * **任何一条不成立就退回 INLINE，把真实几何内联过去** —— 慢，但一定对。
 *
 * <p>注意第 3 步的加法顺序：{@code ArrayVoxelShape} 的点表是
 * {@code OffsetDoubleList}（{@code offset + base}），原生合成的是 {@code (double)block + base}；
 * IEEE-754 加法可交换，两个操作数相同 ⇒ 结果逐位相同。
 */
public final class MoveShapeBatch {

    /** {@code CAVA_ESHAPE_SRC_*}。 */
    public static final int SRC_ENTITY = 0;
    public static final int SRC_WORLD_BORDER = 1;
    public static final int SRC_BLOCK = 2;
    public static final int SRC_OTHER = 3;

    /** {@code CAVA_MSHAPE_*}。 */
    public static final int MSHAPE_STATE = 0;
    public static final int MSHAPE_INLINE = 1;

    private static final int INITIAL = 32;

    private VoxelShape[] shapes = new VoxelShape[INITIAL];
    private long[] token = new long[INITIAL];
    private int[] kind = new int[INITIAL];
    private int[] stateId = new int[INITIAL];
    private int[] blockX = new int[INITIAL];
    private int[] blockY = new int[INITIAL];
    private int[] blockZ = new int[INITIAL];
    private int[] source = new int[INITIAL];
    private int[] inlineSlot = new int[INITIAL];
    private int count;

    /** INLINE 形状的几何（每条一个）。 */
    private ShapeGeometry[] inlineGeom = new ShapeGeometry[INITIAL];
    private int inlineCount;
    private double[] inlinePoints = new double[512];
    private int inlinePointCount;
    private long[] inlineBits = new long[512];
    private int inlineBitCount;
    /** 第 i 个 INLINE 形状在扁平点表/位图里的起点。 */
    private int[] inlinePointStart = new int[INITIAL];
    private int[] inlineBitStart = new int[INITIAL];

    /** 判定 STAT 通过 / 退回 INLINE 的计数（实测证据用）。 */
    private int stateHits;
    private int inlineFallbacks;

    public void reset() {
        for (int i = 0; i < count; i++) {
            shapes[i] = null;
        }
        count = 0;
        inlineCount = 0;
        inlinePointCount = 0;
        inlineBitCount = 0;
        stateHits = 0;
        inlineFallbacks = 0;
    }

    public int count() {
        return count;
    }

    public int stateHits() {
        return stateHits;
    }

    public int inlineFallbacks() {
        return inlineFallbacks;
    }

    public VoxelShape shape(int index) {
        return shapes[index];
    }

    public int source(int index) {
        return source[index];
    }

    public int blockX(int index) {
        return blockX[index];
    }

    public int blockY(int index) {
        return blockY[index];
    }

    public int blockZ(int index) {
        return blockZ[index];
    }

    /**
     * 追加一个形状（**保持原版顺序**：entity → worldborder → blocks）。
     *
     * <p>空形状直接跳过：它在 {@code calculateMaxDistance} 里恒等于「原样返回 maxDist」，
     * 加不加都不改变结果（而空形状表 ≠ 空形状：表本身空才会触发 {@code shapes.isEmpty()} 短路，
     * 那一条由 {@link #count()} 是否为 0 表达，不受这里影响）。
     */
    public void add(VoxelShape shape, int src) {
        if (shape == null || shape.isEmpty()) {
            return;
        }
        int slot = count;
        ensure(slot + 1);
        shapes[slot] = shape;
        token[slot] = slot;
        source[slot] = src;
        if (src == SRC_BLOCK && tryStateRef(shape, slot)) {
            stateHits++;
            count = slot + 1;
            return;
        }
        inlineFallbacks++;
        kind[slot] = MSHAPE_INLINE;
        stateId[slot] = 0;
        blockX[slot] = 0;
        blockY[slot] = 0;
        blockZ[slot] = 0;
        addInline(shape, slot);
        count = slot + 1;
    }

    /** 三个轴都要过的精确校验（见类注释）。 */
    private boolean tryStateRef(VoxelShape shape, int slot) {
        ShapeTable table = ShapeTable.get();
        if (!table.ready()) {
            return false;
        }
        VoxelShapeAccessor acc = (VoxelShapeAccessor) shape;
        VoxelSet voxels = acc.cava$voxels();
        int owner = table.ownerOf(voxels);
        if (owner < 0) {
            return false;
        }
        ShapeGeometry canon = table.geometryOf(owner);
        if (canon == null || canon.isEmpty()) {
            return false;
        }
        if (voxels.getXSize() != canon.sizeX || voxels.getYSize() != canon.sizeY
                || voxels.getZSize() != canon.sizeZ) {
            return false;
        }
        int[] k = new int[3];
        int axisIndex = 0;
        for (Direction.Axis axis : Direction.Axis.values()) {
            int size = axis == Direction.Axis.X ? canon.sizeX : (axis == Direction.Axis.Y ? canon.sizeY : canon.sizeZ);
            DoubleList dl = acc.cava$getPointPositions(axis);
            double kd = dl.getDouble(0) - canon.point(axis, 0);
            double floored = Math.floor(kd);
            if (kd != floored || kd < Integer.MIN_VALUE || kd > Integer.MAX_VALUE) {
                return false;
            }
            int ki = (int) kd;
            for (int i = 0; i <= size; i++) {
                if (dl.getDouble(i) != (double) ki + canon.point(axis, i)) {
                    return false;
                }
            }
            k[axisIndex++] = ki;
        }
        kind[slot] = MSHAPE_STATE;
        stateId[slot] = owner;
        blockX[slot] = k[0];
        blockY[slot] = k[1];
        blockZ[slot] = k[2];
        inlineSlot[slot] = 0;
        return true;
    }

    private void addInline(VoxelShape shape, int slot) {
        ensureInline(inlineCount + 1);
        ShapeGeometry g = ShapeGeometry.of(shape);
        inlineGeom[inlineCount] = g;
        inlineSlot[slot] = inlineCount;
        inlineCount++;

        int needPoints = g.points == null ? 0 : g.points.length;
        int needBits = g.bits.length;
        if (inlinePointCount + needPoints > inlinePoints.length) {
            inlinePoints = Arrays.copyOf(inlinePoints, Math.max(inlinePoints.length * 2, inlinePointCount + needPoints));
        }
        if (inlineBitCount + needBits > inlineBits.length) {
            inlineBits = Arrays.copyOf(inlineBits, Math.max(inlineBits.length * 2, inlineBitCount + needBits));
        }
        // 记录该形状在扁平数组里的起点（写 segment 时用）
        inlinePointStart[inlineCount - 1] = inlinePointCount;
        inlineBitStart[inlineCount - 1] = inlineBitCount;
        if (needPoints > 0) {
            System.arraycopy(g.points, 0, inlinePoints, inlinePointCount, needPoints);
            inlinePointCount += needPoints;
        }
        if (needBits > 0) {
            System.arraycopy(g.bits, 0, inlineBits, inlineBitCount, needBits);
            inlineBitCount += needBits;
        }
    }

    public int inlineCount() {
        return inlineCount;
    }

    public int inlinePointCount() {
        return inlinePointCount;
    }

    public int inlineBitCount() {
        return inlineBitCount;
    }

    public ShapeGeometry inlineGeom(int i) {
        return inlineGeom[i];
    }

    // ------------------------------------------------------------------
    // 写 MemorySegment（FFM）
    // ------------------------------------------------------------------

    /** 写 {@code CavaMoveShapeRef[count]}。 */
    public void writeRefs(MemorySegment seg) {
        for (int i = 0; i < count; i++) {
            long o = (long) i * AbiOffsets.REF_SIZE;
            seg.set(ValueLayout.JAVA_LONG, o + AbiOffsets.REF_SHAPE_TOKEN, token[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_KIND, kind[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_STATE_ID, stateId[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_BLOCK_X, blockX[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_BLOCK_Y, blockY[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_BLOCK_Z, blockZ[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_SOURCE, source[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_INLINE_SLOT, inlineSlot[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_RESERVED0, 0);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_RESERVED1, 0);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.REF_RESERVED2, 0);
        }
    }

    /** 写 {@code CavaShapeRecord[inlineCount]}（点表/位图下标用**本批的**扁平数组）。 */
    public void writeInlineShapes(MemorySegment seg) {
        for (int i = 0; i < inlineCount; i++) {
            ShapeGeometry g = inlineGeom[i];
            long o = (long) i * AbiOffsets.SHAPE_RECORD_SIZE;
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_POINTS_KIND, g.pointsKind);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_POINT_OFFSET, inlinePointStart[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_BIT_OFFSET, inlineBitStart[i]);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_BIT_WORDS, g.bits.length);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_SIZE_X, g.sizeX);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_SIZE_Y, g.sizeY);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_SIZE_Z, g.sizeZ);
            seg.set(ValueLayout.JAVA_INT, o + AbiOffsets.SHAPE_RESERVED0, 0);
        }
    }

    public void writeInlinePoints(MemorySegment seg) {
        if (inlinePointCount > 0) {
            MemorySegment.copy(inlinePoints, 0, seg, ValueLayout.JAVA_DOUBLE, 0, inlinePointCount);
        }
    }

    public void writeInlineBits(MemorySegment seg) {
        if (inlineBitCount > 0) {
            MemorySegment.copy(inlineBits, 0, seg, ValueLayout.JAVA_LONG, 0, inlineBitCount);
        }
    }

    /** 诊断一行。 */
    public String report() {
        return "refs=" + count + " state=" + stateHits + " inline=" + inlineFallbacks
                + " inlinePoints=" + inlinePointCount + " inlineBits=" + inlineBitCount;
    }

    // ------------------------------------------------------------------
    private void ensure(int n) {
        if (n <= shapes.length) {
            return;
        }
        int cap = Math.max(INITIAL, Integer.highestOneBit(n - 1) << 1);
        shapes = Arrays.copyOf(shapes, cap);
        token = Arrays.copyOf(token, cap);
        kind = Arrays.copyOf(kind, cap);
        stateId = Arrays.copyOf(stateId, cap);
        blockX = Arrays.copyOf(blockX, cap);
        blockY = Arrays.copyOf(blockY, cap);
        blockZ = Arrays.copyOf(blockZ, cap);
        source = Arrays.copyOf(source, cap);
        inlineSlot = Arrays.copyOf(inlineSlot, cap);
    }

    private void ensureInline(int n) {
        if (n <= inlineGeom.length) {
            return;
        }
        int cap = Math.max(INITIAL, Integer.highestOneBit(n - 1) << 1);
        inlineGeom = Arrays.copyOf(inlineGeom, cap);
        inlinePointStart = Arrays.copyOf(inlinePointStart, cap);
        inlineBitStart = Arrays.copyOf(inlineBitStart, cap);
    }
}
