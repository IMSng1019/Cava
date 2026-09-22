package cava.oracle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原版 net.minecraft.entity.ai.pathing.PathNodeNavigator（class_13 / efi）findPathToAny 的逐分支复刻。
 *
 * <p>证据见 docs/CAVA-pathfind-oracle-spec.md 第 3.4 / 4 / 6 节，全部来自 javap -p -c 与 javap -v
 * 的 BootstrapMethods。
 */
public final class Navigator {
    private static final float TARGET_DISTANCE_MULTIPLIER = 1.5f;

    private final NodeMaker pathNodeMaker;
    private final int range;
    private final PNode[] successors = new PNode[32];
    private final MinHeap minHeap = new MinHeap();

    public Navigator(NodeMaker pathNodeMaker, int range) {
        this.pathNodeMaker = pathNodeMaker;
        this.range = range;
    }

    /**
     * findPathToAny(ChunkCache, MobEntity, Set&lt;BlockPos&gt;, float, int, float)。
     *
     * @param targets     目标方块坐标，按调用方给出的顺序（原版是 Set，单目标时顺序无影响）
     * @param maxRange    形参 f
     * @param reachRadius 形参 i（抵达判定半径，同时也是 SystemCore 里的 maxVisitedNodes 实参）
     * @param followRange 形参 g（与 this.range 相乘得到节点预算）
     */
    public PathResult findPathToAny(Terrain world, MobProfile entity, List<int[]> targets,
                                    float maxRange, int reachRadius, float followRange) {
        this.minHeap.clear();
        this.pathNodeMaker.init(world, entity);
        PNode start = this.pathNodeMaker.getStart();
        if (start == null) {
            return PathResult.notFound();
        }
        Map<TargetNode, int[]> targetMap = new HashMap<>();
        for (int[] t : targets) {
            targetMap.put(this.pathNodeMaker.getNode((double) t[0], (double) t[1], (double) t[2]), t);
        }
        PathResult result = findPathToAny(start, targetMap, maxRange, reachRadius, followRange);
        this.pathNodeMaker.clear();
        return result;
    }

    private PathResult findPathToAny(PNode start, Map<TargetNode, int[]> targetMap,
                                     float maxRange, int reachRadius, float followRange) {
        Set<TargetNode> targets = targetMap.keySet();

        start.penalizedPathLength = 0.0f;
        start.distanceToNearestTarget = calculateDistances(start, targets);
        start.heapWeight = start.distanceToNearestTarget;
        this.minHeap.clear();
        this.minHeap.push(start);

        Set<TargetNode> found = new HashSet<>(guavaCapacity(targets.size()));
        int visited = 0;
        int nodeBudget = (int) ((float) this.range * followRange);
        long traceHash = 0xCBF29CE484222325L;
        int expanded = 0;

        while (!this.minHeap.isEmpty()) {
            visited++;
            if (visited >= nodeBudget) {
                break;
            }
            PNode current = this.minHeap.pop();
            expanded++;
            traceHash = trace(traceHash, current);
            current.visited = true;
            for (TargetNode target : targets) {
                if (current.getManhattanDistance(target) <= (float) reachRadius) {
                    target.markReached();
                    found.add(target);
                }
            }
            if (!found.isEmpty()) {
                break;
            }
            if (current.getDistance(start) >= maxRange) {
                continue;
            }
            int count = this.pathNodeMaker.getSuccessors(this.successors, current);
            for (int i = 0; i < count; i++) {
                PNode successor = this.successors[i];
                float distance = current.getDistance(successor);
                successor.pathLength = current.pathLength + distance;
                float penalized = current.penalizedPathLength + distance + successor.penalty;
                if (successor.pathLength < maxRange) {
                    if (!successor.isInHeap() || penalized < successor.penalizedPathLength) {
                        successor.previous = current;
                        successor.penalizedPathLength = penalized;
                        successor.distanceToNearestTarget =
                                calculateDistances(successor, targets) * TARGET_DISTANCE_MULTIPLIER;
                        if (successor.isInHeap()) {
                            this.minHeap.setNodeWeight(successor,
                                    successor.penalizedPathLength + successor.distanceToNearestTarget);
                        } else {
                            successor.heapWeight =
                                    successor.penalizedPathLength + successor.distanceToNearestTarget;
                            this.minHeap.push(successor);
                        }
                    }
                }
            }
        }

        PathResult selected;
        if (!found.isEmpty()) {
            // found.stream().map(t -> createPath(..., false)).min(comparingInt(Path::getLength))
            PathResult best = null;
            for (TargetNode target : found) {
                PathResult candidate = createPath(target.getNearestNode(), targetMap.get(target), false);
                if (best == null || Integer.compare(candidate.nodes.length, best.nodes.length) < 0) {
                    best = candidate;
                }
            }
            selected = best;
        } else {
            // targets.stream().map(t -> createPath(..., true))
            //     .min(comparingDouble(Path::getManhattanDistanceFromTarget).thenComparingInt(Path::getLength))
            PathResult best = null;
            for (TargetNode target : targets) {
                PathResult candidate = createPath(target.getNearestNode(), targetMap.get(target), true);
                if (best == null) {
                    best = candidate;
                    continue;
                }
                int c = Double.compare(candidate.manhattanDistanceFromTarget, best.manhattanDistanceFromTarget);
                if (c == 0) {
                    c = Integer.compare(candidate.nodes.length, best.nodes.length);
                }
                if (c < 0) {
                    best = candidate;
                }
            }
            selected = best;
        }
        if (selected == null) {
            return PathResult.notFound();
        }
        selected.expandedCount = expanded;
        selected.traceHash = traceHash;
        return selected;
    }

    /** PathNodeNavigator.createPath。 */
    private PathResult createPath(PNode endNode, int[] target, boolean reachesTarget) {
        List<PNode> list = new ArrayList<>();
        PNode node = endNode;
        list.add(0, node);
        while (node.previous != null) {
            node = node.previous;
            list.add(0, node);
        }
        return PathResult.of(list, target, reachesTarget);
    }

    /** PathNodeNavigator.calculateDistances。 */
    private float calculateDistances(PNode node, Set<TargetNode> targets) {
        float best = Float.MAX_VALUE;
        for (TargetNode target : targets) {
            float d = node.getDistance(target);
            target.updateNearestNode(d, node);
            best = Math.min(d, best);
        }
        return best;
    }

    /** 展开轨迹哈希：必须在 pop 之后、任何后续松弛之前采样（规格第 10.4 节）。 */
    private static long trace(long h, PNode node) {
        h ^= ((long) node.x) & 0xFFFFFFFFL;
        h *= 0x100000001B3L;
        h ^= ((long) node.y) & 0xFFFFFFFFL;
        h *= 0x100000001B3L;
        h ^= ((long) node.z) & 0xFFFFFFFFL;
        h *= 0x100000001B3L;
        h ^= ((long) Float.floatToRawIntBits(node.heapWeight)) & 0xFFFFFFFFL;
        h *= 0x100000001B3L;
        h ^= ((long) Float.floatToRawIntBits(node.penalizedPathLength)) & 0xFFFFFFFFL;
        h *= 0x100000001B3L;
        return h;
    }

    /** Guava Maps.capacity / Sets.newHashSetWithExpectedSize 的容量公式（逐字复刻）。 */
    private static int guavaCapacity(int expectedSize) {
        if (expectedSize < 3) {
            return expectedSize + 1;
        }
        if (expectedSize < (1 << 30)) {
            return (int) ((float) expectedSize / 0.75F + 1.0F);
        }
        return Integer.MAX_VALUE;
    }
}
