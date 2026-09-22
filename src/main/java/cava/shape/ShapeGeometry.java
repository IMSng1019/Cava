package cava.shape;

import cava.mixin.entity.VoxelShapeAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.Arrays;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.SimpleVoxelShape;
import net.minecraft.util.shape.VoxelSet;
import net.minecraft.util.shape.VoxelShape;

/**
 * 一个 {@code VoxelShape} 的**零近似**几何快照：点表 + 体素位图 + 两个尺寸。
 *
 * <p>这就是 {@code native/include/cava_abi.h} 的 {@code CavaShapeRecord} 在 Java 侧的对应物。
 * 之所以不传扁平 AABB：内核走的是**体素网格**（{@code VoxelSet}），
 * 把 AABB 反推回网格会引入 &lt;1e-7 的量化误差 —— 那正是原版做碰撞判定的量级。
 *
 * <h2>点表的两种编码（与 ABI 的 {@code CAVA_SHAPE_POINTS_*} 一一对应）</h2>
 * <ul>
 *   <li>{@link #POINTS_FRACTIONAL}（{@code SimpleVoxelShape}）：第 i 个点 = {@code (double)i / (double)size}。
 *       原生侧照这个公式**现算**，所以点表不占数组空间。这一档还额外决定了
 *       {@code getCoordIndex} 走的是「floor(clamp(coord*size,-1,size))」而不是二分 ——
 *       两个子类的实现**不同**，必须分开编码。</li>
 *   <li>{@link #POINTS_EXPLICIT}（其余，含所有 {@code offset()} 之后的形状）：点表逐点给出，
 *       X 段（{@code sizeX+1} 个）、Y 段、Z 段依次拼接。</li>
 * </ul>
 *
 * <p><b>体素位图的位序</b>与 {@code BitSetVoxelSet.getIndex} 一致：
 * {@code idx = (x*sizeY + y)*sizeZ + z}，位 {@code idx} 落在 {@code bits[idx >>> 6]} 的第
 * {@code idx & 63} 位（little-endian 的 BitSet 语义）。
 */
public final class ShapeGeometry {

    /** {@code CAVA_SHAPE_POINTS_FRACTIONAL}。 */
    public static final int POINTS_FRACTIONAL = 0;
    /** {@code CAVA_SHAPE_POINTS_EXPLICIT}。 */
    public static final int POINTS_EXPLICIT = 1;

    /** 单轴体素数上界（与原生入口的 kMaxAxisSize 一致，超了必须放弃原生）。 */
    public static final int MAX_AXIS_SIZE = 1 << 16;

    /** 点表编码。 */
    public int pointsKind;
    /** {@code VoxelSet.getXSize()/getYSize()/getZSize()}。 */
    public int sizeX;
    public int sizeY;
    public int sizeZ;
    /** EXPLICIT 时的点表（长度 = sizeX+sizeY+sizeZ+3）；FRACTIONAL 时为 {@code null}。 */
    public double[] points;
    /** 体素位图（长度 = ceil(sizeX*sizeY*sizeZ/64)）；空形状为长度 0 的数组。 */
    public long[] bits;

    /** 体素总数。 */
    public int cellCount() {
        return sizeX * sizeY * sizeZ;
    }

    /** 空形状（{@code isEmpty()}）：体素数为 0 或位图全 0。 */
    public boolean isEmpty() {
        for (long w : bits) {
            if (w != 0L) {
                return false;
            }
        }
        return true;
    }

    /**
     * 第 {@code axis} 轴第 {@code i} 个点的值（**与原生内核的 {@code shape_point_position} 同一公式**）。
     *
     * <p>只用于校验，不进热路径。
     */
    public double point(Direction.Axis axis, int i) {
        int a = axis.ordinal();
        int size = a == 0 ? sizeX : (a == 1 ? sizeY : sizeZ);
        if (pointsKind == POINTS_FRACTIONAL) {
            return (double) i / (double) size;
        }
        int base = a == 0 ? 0 : (a == 1 ? sizeX + 1 : sizeX + 1 + sizeY + 1);
        return points[base + i];
    }

    /** 提取一个形状的几何。**不修改任何原版状态。** */
    public static ShapeGeometry of(VoxelShape shape) {
        VoxelShapeAccessor acc = (VoxelShapeAccessor) shape;
        VoxelSet voxels = acc.cava$voxels();
        ShapeGeometry g = new ShapeGeometry();
        g.sizeX = voxels.getXSize();
        g.sizeY = voxels.getYSize();
        g.sizeZ = voxels.getZSize();
        if (g.sizeX < 0 || g.sizeY < 0 || g.sizeZ < 0
                || g.sizeX > MAX_AXIS_SIZE || g.sizeY > MAX_AXIS_SIZE || g.sizeZ > MAX_AXIS_SIZE) {
            throw new IllegalStateException("VoxelSet 尺寸越界: " + g.sizeX + "x" + g.sizeY + "x" + g.sizeZ);
        }
        boolean fractional = shape instanceof SimpleVoxelShape;
        g.pointsKind = fractional ? POINTS_FRACTIONAL : POINTS_EXPLICIT;
        if (!fractional) {
            int n = (g.sizeX + 1) + (g.sizeY + 1) + (g.sizeZ + 1);
            double[] pts = new double[n];
            int at = 0;
            for (Direction.Axis axis : Direction.Axis.values()) {
                DoubleList dl = acc.cava$getPointPositions(axis);
                int size = axis == Direction.Axis.X ? g.sizeX : (axis == Direction.Axis.Y ? g.sizeY : g.sizeZ);
                for (int i = 0; i <= size; i++) {
                    pts[at++] = dl.getDouble(i);
                }
            }
            g.points = pts;
        }
        g.bits = extractBits(voxels, g.sizeX, g.sizeY, g.sizeZ);
        return g;
    }

    /** 逐体素读位图（{@code VoxelSet.contains} 是 {@code public}，javap 实读）。 */
    public static long[] extractBits(VoxelSet voxels, int sx, int sy, int sz) {
        long cells = (long) sx * (long) sy * (long) sz;
        if (cells <= 0) {
            return new long[0];
        }
        long[] out = new long[(int) ((cells + 63) / 64)];
        int idx = 0;
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    if (voxels.contains(x, y, z)) {
                        out[idx >>> 6] |= 1L << (idx & 63);
                    }
                    idx++;
                }
            }
        }
        return out;
    }

    /** 深拷贝（暂存复用时要小心，所以这里给一个显式入口）。 */
    public ShapeGeometry copy() {
        ShapeGeometry g = new ShapeGeometry();
        g.pointsKind = pointsKind;
        g.sizeX = sizeX;
        g.sizeY = sizeY;
        g.sizeZ = sizeZ;
        g.points = points == null ? null : Arrays.copyOf(points, points.length);
        g.bits = Arrays.copyOf(bits, bits.length);
        return g;
    }

    /** 诊断用一行。 */
    public String describe() {
        return (pointsKind == POINTS_FRACTIONAL ? "FRAC" : "EXPL") + "/" + sizeX + "x" + sizeY + "x" + sizeZ
                + " pts=" + (points == null ? 0 : points.length) + " words=" + bits.length;
    }
}
