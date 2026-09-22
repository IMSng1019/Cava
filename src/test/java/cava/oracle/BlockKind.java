package cava.oracle;

/**
 * 一个「方块状态」在寻路语义上的全部派生属性。
 *
 * <p>字段与 {@code LandPathNodeMaker.getCommonNodeType / adjustNodeType / getNodeTypeFromNeighbors}
 * 的每一个分支一一对应（见规格第 5.4.6 节），所以它是「方块状态表 → pathType」的唯一输入。
 * **用调色板下标做键，禁止对象身份。**
 */
public final class BlockKind {
    public static final int AIR = 1;                 // state.isAir()
    public static final int TRAPDOOR = 1 << 1;       // BlockTags.TRAPDOORS || LILY_PAD || BIG_DRIPLEAF
    public static final int POWDER_SNOW = 1 << 2;    // Blocks.POWDER_SNOW
    public static final int CACTUS_OR_BERRY = 1 << 3;// CACTUS || SWEET_BERRY_BUSH
    public static final int HONEY = 1 << 4;          // HONEY_BLOCK
    public static final int COCOA = 1 << 5;          // COCOA
    public static final int CAUTIOUS = 1 << 6;       // WITHER_ROSE || POINTED_DRIPSTONE
    public static final int DOOR = 1 << 7;           // block instanceof DoorBlock
    public static final int DOOR_OPEN = 1 << 8;      // state.get(DoorBlock.OPEN)
    public static final int DOOR_HAND = 1 << 9;      // getBlockSetType().canOpenByHand()
    public static final int RAIL = 1 << 10;          // block instanceof AbstractRailBlock
    public static final int LEAVES = 1 << 11;        // block instanceof LeavesBlock
    public static final int FENCE_TAG = 1 << 12;     // BlockTags.FENCES
    public static final int WALL_TAG = 1 << 13;      // BlockTags.WALLS
    public static final int FENCE_GATE = 1 << 14;    // block instanceof FenceGateBlock
    public static final int FENCE_GATE_OPEN = 1 << 15; // state.get(FenceGateBlock.OPEN)
    public static final int FIRE_DAMAGE = 1 << 16;   // LandPathNodeMaker.inflictsFireDamage(state)
    public static final int PATHFIND_LAND = 1 << 17; // state.canPathfindThrough(view,pos,NavigationType.LAND)
    public static final int WATER_BLOCK = 1 << 18;   // state.isOf(Blocks.WATER)

    public static final int FLUID_NONE = 0;
    public static final int FLUID_WATER = 1;
    public static final int FLUID_LAVA = 2;

    public final int flags;
    public final int fluid;
    /** getCollisionShape(...).getMax(Axis.Y)，空形状为 0。 */
    public final float collisionMaxY;
    /** getCollisionShape(...).getMin(Axis.Y)，空形状为 0。 */
    public final float collisionMinY;

    public BlockKind(int flags, int fluid, float collisionMinY, float collisionMaxY) {
        this.flags = flags;
        this.fluid = fluid;
        this.collisionMinY = collisionMinY;
        this.collisionMaxY = collisionMaxY;
    }

    public boolean has(int flag) {
        return (this.flags & flag) != 0;
    }
}
