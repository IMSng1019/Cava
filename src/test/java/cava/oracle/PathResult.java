package cava.oracle;

import java.util.List;

/** 原版 net.minecraft.entity.ai.pathing.Path（class_11 / efg）在差分测试里需要的全部信息。 */
public final class PathResult {
    /** 路径上的一个节点；所有 float 都以**原样位模式**保留（禁止规范化 -0.0f / NaN）。 */
    public static final class OutNode {
        public int x;
        public int y;
        public int z;
        public int type;
        public boolean visited;
        public float pathLength;
        public float penalizedPathLength;
        public float distanceToNearestTarget;
        public float heapWeight;
        public float penalty;
    }

    public boolean found;
    public OutNode[] nodes = new OutNode[0];
    public boolean reachesTarget;
    public int targetX;
    public int targetY;
    public int targetZ;
    public float manhattanDistanceFromTarget;
    public int expandedCount;
    public long traceHash;

    public static PathResult notFound() {
        PathResult r = new PathResult();
        r.found = false;
        return r;
    }

    /** createPath(PathNode, BlockPos, boolean) 的结果（顺序 = 起点 -> 终点）。 */
    public static PathResult of(List<PNode> list, int[] target, boolean reachesTarget) {
        PathResult r = new PathResult();
        r.found = true;
        r.nodes = new OutNode[list.size()];
        for (int i = 0; i < list.size(); i++) {
            PNode n = list.get(i);
            OutNode o = new OutNode();
            o.x = n.x;
            o.y = n.y;
            o.z = n.z;
            o.type = n.type.ordinal();
            o.visited = n.visited;
            o.pathLength = n.pathLength;
            o.penalizedPathLength = n.penalizedPathLength;
            o.distanceToNearestTarget = n.distanceToNearestTarget;
            o.heapWeight = n.heapWeight;
            o.penalty = n.penalty;
            r.nodes[i] = o;
        }
        r.reachesTarget = reachesTarget;
        r.targetX = target[0];
        r.targetY = target[1];
        r.targetZ = target[2];
        PNode last = list.get(list.size() - 1);
        r.manhattanDistanceFromTarget = last.getManhattanDistance(target[0], target[1], target[2]);
        return r;
    }
}
