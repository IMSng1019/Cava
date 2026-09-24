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
     * 复刻原版的"是否抵达"判定，给出 {@code new Path(nodes, target, X)} 需要的 X。
     *
     * <p><b>语义勘误（2026-09-24 P1-FIX 用实测推翻旧注释）</b>：旧注释（以及 oracle spec 4.3.1 与
     * {@code VectorGen/OracleSelfTest} 的注释）声称 {@code Path.reachesTarget()} 的语义是**反的**
     * （"true = 没抵达"）。**实测把这个说法证伪了** —— 同一个 jar、同一台服务器上，native 关闭
     * （纯原版）时的回执逐条如下：
     * <pre>
     *   long128      end=(159,71,0)  manh=1.000   reachedTargetFlag=true     ← 抵达
     *   slalom       end=(160,71,31) manh=1.000   reachedTargetFlag=true     ← 抵达
     *   maze41       end=(135,71,134) manh=1.000  reachedTargetFlag=true     ← 抵达
     *   maze63       end=(83,71,-111) manh=22.000 reachedTargetFlag=false    ← 未抵达（预算耗尽）
     *   long128hash  end=(32,71,-32) manh=128.000 reachedTargetFlag=true     ← **决定性**
     * </pre>
     * 最后一条是决定性的：{@code long128hash} 里起点与终点在 {@code PathNode.hash} 下同键，
     * 搜索在第 1 个节点上就命中目标测试（{@code FOUND@pop1}，见 perf 文档 §3.4），
     * 而它的回执是 {@code true} ⇒ **{@code reachesTarget() == found}（true = 抵达）**。
     * 若按旧注释的"反语义"，maze63（未抵达）应当是 {@code true}，实测是 {@code false} —— 自相矛盾。
     *
     * <p>于是 {@code NativePathBuilder} 之前构造出的 {@code Path} 把这个字段**填反了**，
     * 而且 P1-PERF 的比对脚本因为键名写错（{@code reachesTargetFlag} vs 回执里的
     * {@code reachedTargetFlag}）**从来没有真的比过这个字段** ⇒ 8 个接管场景全都带着这个差异
     * 被判成"逐字段一致"。本轮两处都修了。
     *
     * <p>推论：found ⟺ 存在被弹出的节点满足 {@code getManhattanDistance(t) <= (float)reachRange}；
     * 返回路径的末节点是 {@code TargetPathNode.getNearestNode()}（所有被接受节点的最小曼哈顿距离），
     * 因此"末节点在半径内 ⇒ found"总是成立；反向（found 但末节点在半径外）只在
     * {@code PathNode.hash} 同键这种病态场景出现，而那种场景现在会被窗口截断检测判回退。
     *
     * @return {@code Path.reachesTarget} 的值：true = 真的抵达了目标
     */
    public static boolean reachesTarget(int lastX, int lastY, int lastZ,
                                        int targetX, int targetY, int targetZ, int reachRange) {
        int d = Math.abs(lastX - targetX) + Math.abs(lastY - targetY) + Math.abs(lastZ - targetZ);
        return (float) d <= (float) reachRange;
    }
}
