package cava.oracle;

/** 原版 net.minecraft.entity.ai.pathing.TargetPathNode（class_4459 / efk）的镜像。 */
public final class TargetNode extends PNode {
    private float nearestNodeDistance = Float.MAX_VALUE;
    private PNode nearestNode;
    private boolean reached;

    public TargetNode(PNode node) {
        super(node.x, node.y, node.z);
    }

    public TargetNode(int x, int y, int z) {
        super(x, y, z);
    }

    /** TargetPathNode.updateNearestNode：严格 d < nearestNodeDistance 才写。 */
    public void updateNearestNode(float distance, PNode node) {
        if (distance < this.nearestNodeDistance) {
            this.nearestNodeDistance = distance;
            this.nearestNode = node;
        }
    }

    public PNode getNearestNode() {
        return this.nearestNode;
    }

    public void markReached() {
        this.reached = true;
    }

    public boolean isReached() {
        return this.reached;
    }
}
