package cava.hook;

import net.minecraft.entity.ai.pathing.AmphibiousPathNodeMaker;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;

/**
 * 从原版上下文抽出 {@code CavaMobProfile} 需要的全部输入（{@code docs/CAVA-p1-pathfind-notes.md} 第 4 节的缺字段清单）。
 *
 * <p>清单对照：
 * <ul>
 *   <li>{@code width/height/stepHeight} → {@code Entity.getWidth/getHeight/getStepHeight}</li>
 *   <li>实体 {@code double} 位姿 → {@code getX/getY/getZ}（起点由位姿推，**不是**从参数取）</li>
 *   <li>26 项惩罚表 → {@code MobEntity.getPathfindingPenalty(PathNodeType)}，索引语义见 {@link AbiPenaltyOrder}</li>
 *   <li>{@code world.getBottomY() / getSeaLevel()}</li>
 *   <li>{@code canSwim / canWalkOverFences / amphibious / penalizeDeepWater} → 从 {@code PathNodeMaker} 与
 *       {@code AmphibiousPathNodeMaker} 读（**不是**从生物猜）</li>
 * </ul>
 *
 * <p><b>两个 ABI 缺口（如实记录，见 docs/CAVA-p1-inject-notes.md）</b>：
 * <ol>
 *   <li>{@code max_fall_distance} 在 Yarn 1.20.4 里**没有对应来源**：{@code Entity} 上只有
 *       {@code getSafeFallDistance()}，没有 {@code getMaxFallDistance()}（javap 实测）。
 *       这里填 {@code getSafeFallDistance()}，**未验证**这是 ABI 作者的本意。</li>
 *   <li>{@code CAVA_NAV_CAN_WALK_ON_FLUID} 同样没有来源：Yarn 1.20.4 的 {@code Entity} 没有
 *       {@code canWalkOnFluid}（javap 实测）。**固定填 0**，未验证。</li>
 * </ol>
 */
public final class MobInputs {

    /** {@code CAVA_NAV_*} 位（与 cava_abi.h 逐位一致）。 */
    public static final int NAV_CAN_OPEN_DOORS = 1;
    public static final int NAV_CAN_ENTER_OPEN_DOORS = 1 << 1;
    public static final int NAV_CAN_FLOAT = 1 << 2;
    public static final int NAV_AMPHIBIOUS = 1 << 3;
    public static final int NAV_PENALIZE_DEEP_WATER = 1 << 4;
    public static final int NAV_CAN_WALK_OVER_FENCES = 1 << 5;
    public static final int NAV_CAN_SWIM = 1 << 6;
    public static final int NAV_CAN_PATHFIND_THROUGH = 1 << 7;
    public static final int NAV_ON_GROUND = 1 << 8;
    public static final int NAV_TOUCHING_WATER = 1 << 9;
    public static final int NAV_CAN_WALK_ON_FLUID = 1 << 10;

    /** {@code CAVA_PENALTY_ALL_SET}：26 项惩罚表全部有效。 */
    public static final int PENALTY_ALL_SET = 0x03FFFFFF;

    private MobInputs() {
    }

    /** 通行档案种类（用于 profileKey 与"能不能接管"的判断）。 */
    public enum MakerKind {
        /** {@code LandPathNodeMaker}（含两栖的子类）。 */
        LAND,
        /** {@code BirdPathNodeMaker} —— 原生内核**未实现**，必须回退。 */
        FLYING,
        /** {@code WaterPathNodeMaker} —— 原生内核**未实现**，必须回退。 */
        WATER,
        /** 未知实现 —— 回退。 */
        UNKNOWN
    }

    /** 只能接管 {@link MakerKind#LAND}（两栖是它的子类，内核已覆盖）。 */
    public static MakerKind kindOf(PathNodeMaker maker) {
        if (maker instanceof LandPathNodeMaker) {
            return MakerKind.LAND;
        }
        String n = maker.getClass().getName();
        if (n.endsWith("BirdPathNodeMaker")) {
            return MakerKind.FLYING;
        }
        if (n.endsWith("WaterPathNodeMaker")) {
            return MakerKind.WATER;
        }
        return MakerKind.UNKNOWN;
    }

    /** 组装档案（**每次求解前重推**，captain 2026-09-22 裁决）。 */
    public static MobProfileData build(MobEntity mob, PathNodeMaker maker) {
        MobProfileData d = new MobProfileData();
        PathNodeType[] types = PathNodeType.values();
        for (int i = 0; i < MobProfileData.PENALTY_COUNT; i++) {
            d.setPenalty(i, i < types.length ? mob.getPathfindingPenalty(types[i]) : 0.0f);
        }
        d.penaltyMask = PENALTY_ALL_SET;

        // reserved_max_fall_distance：占位字段，内核不读，且 1.20.4 没有来源 ⇒ 一律 0
        d.reservedMaxFallDistance = 0.0f;
        d.startX = mob.getX();
        d.startY = mob.getY();
        d.startZ = mob.getZ();
        BlockPos bp = mob.getBlockPos();
        d.startBlockX = bp.getX();
        d.startBlockY = bp.getY();
        d.startBlockZ = bp.getZ();
        d.width = mob.getWidth();
        d.height = mob.getHeight();
        d.stepHeight = mob.getStepHeight();
        d.safeFallDistance = mob.getSafeFallDistance();
        d.minY = mob.getWorld().getBottomY();
        d.seaLevel = mob.getWorld().getSeaLevel();
        d.caps = caps(mob, maker);
        return d;
    }

    /** 能力位。**来源全部是原版对象**，不做"按生物类型猜"的推断。 */
    public static int caps(MobEntity mob, PathNodeMaker maker) {
        int caps = 0;
        if (maker.canOpenDoors()) {
            caps |= NAV_CAN_OPEN_DOORS;
        }
        if (maker.canEnterOpenDoors()) {
            caps |= NAV_CAN_ENTER_OPEN_DOORS;
        }
        if (maker.canSwim()) {
            caps |= NAV_CAN_SWIM;
        }
        if (maker.canWalkOverFences()) {
            caps |= NAV_CAN_WALK_OVER_FENCES;
        }
        if (maker instanceof AmphibiousPathNodeMaker) {
            caps |= NAV_AMPHIBIOUS;
            if (AmphibiousPathNodeMakerAccess.penalizeDeepWater((AmphibiousPathNodeMaker) maker)) {
                caps |= NAV_PENALIZE_DEEP_WATER;
            }
        }
        if (mob.isOnGround()) {
            caps |= NAV_ON_GROUND;
        }
        if (mob.isTouchingWater()) {
            caps |= NAV_TOUCHING_WATER;
        }
        // NAV_CAN_WALK_ON_FLUID / NAV_CAN_FLOAT / NAV_CAN_PATHFIND_THROUGH：陆地档案无来源，恒 0
        return caps;
    }

    /**
     * 档案的**稳定标识**（给 {@code RegionSource.isProfileReadyForSolve}）。
     *
     * <p>构成（FNV-1a 64）：{@code [makerKind, caps, width, height, stepHeight]} 的位模式。
     * 这三条正是原版 {@code getCommonNodeType} 依赖的实体上下文（开门/越栅栏/体型）。
     *
     * <p>⚠️ **接口边界请求**：{@code RegionSource} 的 javadoc 说"profileKey 的构成由镜像流决定"，
     * 但调用方（本流）必须先生成一个值。所以这里把构成写死并**上报 captain/镜像流**：
     * 两边必须用同一个函数，否则 {@code isProfileReadyForSolve} 永远返回 false（表现为静默回退，不报错）。
     */
    public static long profileKey(MobProfileData d, MakerKind kind) {
        long h = 0xCBF29CE484222325L;   // FNV-1a 64 offset basis
        h = mix(h, kind.ordinal());
        h = mix(h, d.caps);
        h = mix(h, Float.floatToIntBits(d.width));
        h = mix(h, Float.floatToIntBits(d.height));
        h = mix(h, Float.floatToIntBits(d.stepHeight));
        return h;
    }

    private static long mix(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (i * 8)) & 0xFFL;
            h *= 0x100000001B3L;
        }
        return h;
    }
}
