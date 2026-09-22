package cava.hook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原生节点序列 -> 原版 {@link Path}（任务清单第 4 步）。
 *
 * <p><b>为什么只填 x/y/z/type</b>：javap 实测（{@code -p -c}）表明 {@link Path} 的**全部消费者**
 * （{@code Path} 自身、{@code EntityNavigation}、{@code MobNavigation}）只读
 * {@code PathNode.x / y / z / type} 四个字段；{@code pathLength / penalizedPathLength /
 * heapWeight / distanceToNearestTarget / penalty / visited} **没有任何读取点**。
 * 它们是求解器内部量，而求解器已经在原生侧了。所以 ABI 只导出 x/y/z/heapIndex/g/f/type 是**够用的**
 * （heapIndex 只做 parity 证据，不参与行为）。
 *
 * <p><b>后处理规则</b>（oracle spec 6.2）：原版的 {@code createPath} **只做** previous 回溯 + {@code add(0,...)}，
 * 没有去尾、没有平滑、没有 {@code setLength}。原生返回的顺序已经是 Path 顺序，所以这里**不做任何后处理**。
 *
 * <p><b>reachesTarget 的语义是反的</b>（spec 4.3.1）：见 {@link NativeNodeCodec#reachesTargetFlag}。
 */
public final class NativePathBuilder {

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private NativePathBuilder() {
    }

    /**
     * @param nodes      已解码的原生节点（Path 顺序：起点 -> 终点）
     * @param target     终点方块坐标（原版 {@code Path.target}）
     * @param reachRange 原版 {@code findPathToAny} 的第 5 个形参（int 抵达半径）
     * @return 原版 {@code Path}；节点为空或 type 越界时返回 {@code null}（调用方回退）
     */
    public static Path toPath(List<NativeNodeCodec.Node> nodes, BlockPos target, int reachRange) {
        if (nodes == null || nodes.isEmpty() || target == null) {
            return null;
        }
        PathNodeType[] types = PathNodeType.values();
        List<PathNode> list = new ArrayList<>(nodes.size());
        for (NativeNodeCodec.Node n : nodes) {
            if (n.typeOrdinal() < 0 || n.typeOrdinal() >= types.length) {
                LOG.warn("[cava/pathfind] 原生返回的 PathNodeType 序号越界：{} —— 回退原逻辑", n.typeOrdinal());
                return null;
            }
            PathNode node = new PathNode(n.x(), n.y(), n.z());
            node.heapIndex = n.heapIndex();
            node.penalizedPathLength = n.g();
            node.heapWeight = n.f();
            node.type = types[n.typeOrdinal()];
            list.add(node);
        }
        NativeNodeCodec.Node last = nodes.get(nodes.size() - 1);
        boolean reachesTarget = NativeNodeCodec.reachesTargetFlag(
                last.x(), last.y(), last.z(), target.getX(), target.getY(), target.getZ(), reachRange);
        return new Path(list, target, reachesTarget);
    }
}
