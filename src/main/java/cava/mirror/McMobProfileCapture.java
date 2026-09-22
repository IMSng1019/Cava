package cava.mirror;

import java.lang.reflect.Field;
import net.minecraft.entity.ai.pathing.AmphibiousPathNodeMaker;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从**活的** {@link MobEntity} + 它的 {@link PathNodeMaker} 采集 {@link MobProfileSpec}。
 *
 * <p><b>关键设计：能力位从活的 PathNodeMaker 上读</b>（{@code canOpenDoors()} /
 * {@code canEnterOpenDoors()} / {@code canSwim()} / {@code canWalkOverFences()}）——
 * 这正是原版寻路当时用的值，**比按实体类型猜稳**（门能不能开是 {@code MobEntity} 初始化时
 * 写进 maker 的）。
 *
 * <p>任何"找不到出处"的字段都让 {@code exact=false}（→ {@code isProfileReadyForSolve} 返回 false
 * → 注入流回退原逻辑）。**宁可慢，不可不一致。**
 */
public final class McMobProfileCapture {

    private static final Logger LOG = LoggerFactory.getLogger("cava/mirror");

    private McMobProfileCapture() {
    }

    /**
     * 给注入流用的**填值回调**，直接喂给
     * {@code RegionSource.uploadProfileForSolve(long handle, Consumer<MemorySegment> uploader)}：
     *
     * <pre>{@code
     * mirror.uploadProfileForSolve(handle, McMobProfileCapture.filler(mob, world));
     * }</pre>
     *
     * <p>注意：档案里含**当前位姿**，所以这个回调**每次求解前都要重新构造**（别缓存）。
     * 生产者仍然是注入流（它有 {@code MobEntity}）；本类只是把"26 项惩罚表 + caps + 布局偏移"
     * 这段容易写错的代码收敛到一处，避免两边各写一份。
     */
    public static java.util.function.Consumer<java.lang.foreign.MemorySegment> filler(MobEntity mob,
                                                                                     ServerWorld world) {
        MobProfileSpec spec = of(mob, world);
        return spec::writeTo;
    }

    /** 采集。{@code world} 必须与该生物所在维度一致（取 bottomY / seaLevel）。 */
    public static MobProfileSpec of(MobEntity mob, ServerWorld world) {
        boolean exact = true;
        if (mob == null || world == null) {
            throw new IllegalArgumentException("mob/world 不能为 null");
        }
        PathNodeType[] types = PathNodeType.values();
        if (types.length != PathTypes.COUNT) {
            LOG.error("[cava/mirror] PathNodeType.values().length={} 与 ABI 的 26 不一致 —— 惩罚表会错位，拒绝采集",
                    types.length);
            exact = false;
        }
        float[] penalty = new float[PathTypes.COUNT];
        for (int i = 0; i < types.length && i < penalty.length; i++) {
            penalty[i] = mob.getPathfindingPenalty(types[i]);
        }

        EntityNavigation nav = mob.getNavigation();
        PathNodeMaker maker = nav == null ? null : nav.getNodeMaker();
        int caps = 0;
        int navKind = MobProfileSpec.KIND_UNSUPPORTED;
        if (maker == null) {
            LOG.warn("[cava/mirror] {} 没有 navigation/PathNodeMaker —— 能力位无出处，exact=false",
                    mob.getClass().getName());
            exact = false;
        } else {
            if (maker.canOpenDoors()) {
                caps |= NavCaps.CAN_OPEN_DOORS;
            }
            if (maker.canEnterOpenDoors()) {
                caps |= NavCaps.CAN_ENTER_OPEN_DOORS;
            }
            if (maker.canSwim()) {
                caps |= NavCaps.CAN_SWIM;
            }
            if (maker.canWalkOverFences()) {
                caps |= NavCaps.CAN_WALK_OVER_FENCES;
            }
            if (maker instanceof AmphibiousPathNodeMaker) {
                caps |= NavCaps.AMPHIBIOUS;
                navKind = MobProfileSpec.KIND_AMPHIBIOUS;
                Boolean penalizeDeepWater = readPenalizeDeepWater(maker);
                if (penalizeDeepWater == null) {
                    exact = false;
                } else if (penalizeDeepWater) {
                    caps |= NavCaps.PENALIZE_DEEP_WATER;
                }
            } else if (maker instanceof LandPathNodeMaker) {
                navKind = MobProfileSpec.KIND_LAND;
            } else {
                navKind = MobProfileSpec.KIND_UNSUPPORTED;
                exact = false; // Bird/Water maker：内核未实现，必须回退
            }
        }
        if (mob.isOnGround()) {
            caps |= NavCaps.ON_GROUND;
        }
        if (mob.isTouchingWater()) {
            caps |= NavCaps.TOUCHING_WATER;
        }
        try {
            if (mob.canWalkOnFluid(world.getFluidState(mob.getBlockPos()))) {
                caps |= NavCaps.CAN_WALK_ON_FLUID;
            }
        } catch (Throwable t) {
            exact = false;
            LOG.warn("[cava/mirror] canWalkOnFluid 求值失败，exact=false: {}", t.toString());
        }

        return new MobProfileSpec(
                penalty,
                0.0f,                       /* max_fall_distance：1.20.4 找不到 getMaxFallDistance 来源（见 notes §4） */
                mob.getX(), mob.getY(), mob.getZ(),
                mob.getBlockPos().getX(), mob.getBlockPos().getY(), mob.getBlockPos().getZ(),
                mob.getWidth(), mob.getHeight(), mob.getStepHeight(),
                mob.getSafeFallDistance(), world.getBottomY(), world.getSeaLevel(),
                caps, NavCaps.PENALTY_ALL_SET, navKind, exact);
    }

    /** 反射读 {@code AmphibiousPathNodeMaker.penalizeDeepWater}（私有 final boolean，无 getter）。找不到返回 null。 */
    private static Boolean readPenalizeDeepWater(PathNodeMaker maker) {
        for (Field f : maker.getClass().getDeclaredFields()) {
            if (f.getType() == boolean.class && f.getName().toLowerCase(java.util.Locale.ROOT).contains("penalize")) {
                try {
                    f.setAccessible(true);
                    return f.getBoolean(maker);
                } catch (Throwable t) {
                    LOG.warn("[cava/mirror] 反射读 {} 失败: {}", f.getName(), t.toString());
                    return null;
                }
            }
        }
        return null;
    }
}
