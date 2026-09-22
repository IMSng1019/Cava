package cava.mirror;

/**
 * {@code cava_abi.h} 的 {@code CAVA_NAV_*} 能力位（{@code CavaMobProfile.caps}）+ 惩罚表掩码。
 *
 * <p>这些位的语义是"该生物能不能做某事"，与方块状态无关 —— 原版里它们来自
 * {@code PathNodeMaker.canOpenDoors() / canEnterOpenDoors() / canSwim() / canWalkOverFences()}
 * 以及两栖子类，所以镜像侧必须**从活的 PathNodeMaker 上读**，不要凭实体类型猜。
 */
public final class NavCaps {

    private NavCaps() {
    }

    /** 能用手开门（原版 {@code PathNodeMaker.canOpenDoors()}）。 */
    public static final int CAN_OPEN_DOORS = 1 << 0;
    /** 能穿过开着的门（原版 {@code canEnterOpenDoors()}）。 */
    public static final int CAN_ENTER_OPEN_DOORS = 1 << 1;
    /** 会浮在水面（原版 {@code EntityNavigation}/{@code PathNodeMaker} 的浮动能力）。 */
    public static final int CAN_FLOAT = 1 << 2;
    /** 两栖寻路器（{@code AmphibiousPathNodeMaker}）。 */
    public static final int AMPHIBIOUS = 1 << 3;
    /** 深水加惩罚（两栖寻路器的构造实参）。 */
    public static final int PENALIZE_DEEP_WATER = 1 << 4;
    /** 能越过栅栏（{@code canWalkOverFences()}）。 */
    public static final int CAN_WALK_OVER_FENCES = 1 << 5;
    /** 会游泳（{@code canSwim()}）。 */
    public static final int CAN_SWIM = 1 << 6;
    /** 可穿过（仅飞行类有意义）。 */
    public static final int CAN_PATHFIND_THROUGH = 1 << 7;
    /** 当前在地面（{@code entity.isOnGround()}）。 */
    public static final int ON_GROUND = 1 << 8;
    /** 当前触水（{@code entity.isTouchingWater()}）。 */
    public static final int TOUCHING_WATER = 1 << 9;
    /** 能在流体上行走（{@code entity.canWalkOnFluid(...)}）。 */
    public static final int CAN_WALK_ON_FLUID = 1 << 10;

    /** {@code CAVA_PENALTY_ALL_SET}：惩罚表 26 项全有效。 */
    public static final int PENALTY_ALL_SET = 0x03FFFFFF;

    /**
     * 本版本认识的**全部** caps 位。
     *
     * <p>用途：{@code RegionSource.isFlagsReadyFor(caps)} 里判断"调用方是不是用了更新的 ABI"。
     * 出现不认识的位**不阻塞**（那些位与方块状态的 19 个谓词无关），只打一次 WARN。
     */
    public static final int KNOWN_MASK = CAN_OPEN_DOORS | CAN_ENTER_OPEN_DOORS | CAN_FLOAT | AMPHIBIOUS
            | PENALIZE_DEEP_WATER | CAN_WALK_OVER_FENCES | CAN_SWIM | CAN_PATHFIND_THROUGH | ON_GROUND
            | TOUCHING_WATER | CAN_WALK_ON_FLUID;
}
