package cava.hook;

import cava.ffm.CavaLayouts;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * CavaPathNode[] 到 Java 记录的**纯解码器**（不引用任何 Minecraft 类型，可单测）。
 *
 * <p>字段布局取 CavaLayouts.PATH_NODE_OFFSETS（与 cava_abi.h 的 CavaPathNode 逐字段对齐，
 * 已由 checkAgainstCAbi() 验证）：x,y,z,heapIndex,g,f,type,flags。
 *
 * <p>只做**边界与契约校验**，不做任何数值转换（g/f 原样传位模式）。
 */
public final class NativeNodeCodec {

    /** 一个原生节点的原始字段。 */
    public record Node(int x, int y, int z, int heapIndex, float g, float f, int typeOrdinal, int flags) {
    }

    private NativeNodeCodec() {
    }

    /**
     * @param out   原生写入的数组段（容量 >= count 个 CavaPathNode）
     * @param count cava_pathfind 返回的节点数
     * @return 节点列表；任何契约违反（count 越界 / type 序号越界 / 段太小）返回 null
     */
    public static List<Node> decode(MemorySegment out, int count) {
        if (count <= 0) {
            return null;
        }
        long need = (long) count * CavaLayouts.PATH_NODE_SIZE;
        if (out == null || out.byteSize() < need) {
            return null;
        }
        long[] off = CavaLayouts.PATH_NODE_OFFSETS;
        List<Node> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long b = (long) i * CavaLayouts.PATH_NODE_SIZE;
            int x = out.get(ValueLayout.JAVA_INT, b + off[0]);
            int y = out.get(ValueLayout.JAVA_INT, b + off[1]);
            int z = out.get(ValueLayout.JAVA_INT, b + off[2]);
            int heapIndex = out.get(ValueLayout.JAVA_INT, b + off[3]);
            float g = out.get(ValueLayout.JAVA_FLOAT, b + off[4]);
            float f = out.get(ValueLayout.JAVA_FLOAT, b + off[5]);
            int type = out.get(ValueLayout.JAVA_INT, b + off[6]);
            int flags = out.get(ValueLayout.JAVA_INT, b + off[7]);
            if (type < 0 || type >= AbiPenaltyOrder.COUNT) {
                return null;   // 契约违反：type 必须是 CAVA_PNT_* 序号
            }
            nodes.add(new Node(x, y, z, heapIndex, g, f, type, flags));
        }
        return nodes;
    }

    /**
     * 复刻原版的“是否抵达”判定，用于还原 Path.reachesTarget 的**反语义**。
     *
     * <p>证据（oracle spec 4.3.1 / 4.4）：原版 found 非空 => createPath(..., false)；
     * found 为空 => createPath(..., true)。而 found 非空 <=> 存在被弹出的节点满足
     * current.getManhattanDistance(t) <= (float) reachRadius；返回路径的末节点就是
     * t.getNearestNode()，其曼哈顿距离 <= 触发时的距离 => **末节点在半径内 <=> 原版走 found 分支**。
     *
     * @return Path 构造器要的 reachesTarget 值（**语义是反的**：true = 没抵达）
     */
    public static boolean reachesTargetFlag(int lastX, int lastY, int lastZ,
                                            int targetX, int targetY, int targetZ, int reachRange) {
        int d = Math.abs(lastX - targetX) + Math.abs(lastY - targetY) + Math.abs(lastZ - targetZ);
        boolean found = (float) d <= (float) reachRange;
        return !found;
    }
}
