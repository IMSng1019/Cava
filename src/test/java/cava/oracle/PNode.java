package cava.oracle;

/**
 * 原版 net.minecraft.entity.ai.pathing.PathNode（class_9 / efe）的镜像。
 *
 * <p>字段名与语义 1:1；{@link #hash(int, int, int)} 逐位复刻原版（**注意它是一个不完美的
 * 打包，y 只有 8 位**，见规格第 3.1 节）。
 */
public class PNode {
    public final int x;
    public final int y;
    public final int z;
    private final int hashCode;
    public int heapIndex = -1;
    public float penalizedPathLength;
    public float distanceToNearestTarget;
    public float heapWeight;
    public PNode previous;
    public boolean visited;
    public float pathLength;
    public float penalty;
    public Pnt type = Pnt.BLOCKED;

    public PNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.hashCode = hash(x, y, z);
    }

    /** PathNode.hash(int,int,int) 的逐位复刻。 */
    public static int hash(int x, int y, int z) {
        return (y & 0xFF)
                | ((x & 0x7FFF) << 8)
                | ((z & 0x7FFF) << 24)
                | (x < 0 ? 0x80000000 : 0)
                | (z < 0 ? 0x8000 : 0);
    }

    public boolean isInHeap() {
        return this.heapIndex >= 0;
    }

    /** PathNode.getDistance(PathNode)：全程 float。 */
    public float getDistance(PNode other) {
        float dx = (float) (other.x - this.x);
        float dy = (float) (other.y - this.y);
        float dz = (float) (other.z - this.z);
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** PathNode.getManhattanDistance(PathNode)：先 Math.abs(int) 再 i2f，加法顺序 (|dx|+|dy|)+|dz|。 */
    public float getManhattanDistance(PNode other) {
        float dx = (float) Math.abs(other.x - this.x);
        float dy = (float) Math.abs(other.y - this.y);
        float dz = (float) Math.abs(other.z - this.z);
        return dx + dy + dz;
    }

    /** PathNode.getManhattanDistance(BlockPos)。 */
    public float getManhattanDistance(int bx, int by, int bz) {
        float dx = (float) Math.abs(bx - this.x);
        float dy = (float) Math.abs(by - this.y);
        float dz = (float) Math.abs(bz - this.z);
        return dx + dy + dz;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PNode other)) {
            return false;
        }
        return this.hashCode == other.hashCode
                && this.x == other.x
                && this.y == other.y
                && this.z == other.z;
    }

    @Override
    public int hashCode() {
        return this.hashCode;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
