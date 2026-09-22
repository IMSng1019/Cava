package cava.oracle;

import java.util.HashMap;
import java.util.Map;

/**
 * net.minecraft.entity.ai.pathing.PathNodeMaker（class_8 / eff）的镜像。
 *
 * <p>去重缓存复刻：原版是 it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap&lt;PathNode&gt;，
 * 键是 {@link PNode#hash(int, int, int)} —— **一个不完美的 32 位打包**，所以坐标不同但哈希相同的
 * 两个位置会共享同一个 PathNode 实例（规格第 3.3 节）。这里用 HashMap&lt;Integer, PNode&gt; 表达
 * 同样的「同键 -> 同实例」语义（遍历顺序无关，因为原版只做 get/put）。
 */
public abstract class NodeMaker {
    protected Terrain world;
    protected MobProfile entity;
    protected final Map<Integer, PNode> pathNodeCache = new HashMap<>();

    protected int entityBlockXSize;
    protected int entityBlockYSize;
    protected int entityBlockZSize;

    protected boolean canEnterOpenDoors;
    protected boolean canOpenDoors;
    protected boolean canSwim;
    protected boolean canWalkOverFences;

    public void init(Terrain world, MobProfile entity) {
        this.world = world;
        this.entity = entity;
        this.pathNodeCache.clear();
        this.entityBlockXSize = Mth.floor(entity.width + 1.0f);
        this.entityBlockYSize = Mth.floor(entity.height + 1.0f);
        this.entityBlockZSize = Mth.floor(entity.width + 1.0f);
        this.canEnterOpenDoors = entity.canEnterOpenDoors;
        this.canOpenDoors = entity.canOpenDoors;
        this.canSwim = entity.canSwim;
        this.canWalkOverFences = entity.canWalkOverFences;
    }

    public void clear() {
        this.world = null;
        this.entity = null;
    }

    public boolean canEnterOpenDoors() {
        return this.canEnterOpenDoors;
    }

    public boolean canOpenDoors() {
        return this.canOpenDoors;
    }

    public boolean canSwim() {
        return this.canSwim;
    }

    public boolean canWalkOverFences() {
        return this.canWalkOverFences;
    }

    /** PathNodeMaker.getNode(int,int,int)：按 PathNode.hash 去重。 */
    protected PNode getNode(int x, int y, int z) {
        Integer key = PNode.hash(x, y, z);
        PNode node = this.pathNodeCache.get(key);
        if (node == null) {
            node = new PNode(x, y, z);
            this.pathNodeCache.put(key, node);
        }
        return node;
    }

    protected TargetNode asTargetNode(PNode node) {
        return new TargetNode(node);
    }

    public abstract PNode getStart();

    /** PathNodeMaker.getNode(double,double,double) —— 原版声明返回 TargetPathNode。 */
    public abstract TargetNode getNode(double x, double y, double z);

    public abstract int getSuccessors(PNode[] successors, PNode node);
}
