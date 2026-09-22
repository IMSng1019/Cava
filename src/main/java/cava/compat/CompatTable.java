package cava.compat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>重叠归属总表</b>（契约第 5 节 / 任务书第 1 条的"本轮归属决策"）。
 *
 * <p>这张表是<b>唯一权威</b>：启动报告的 owner 行、{@code config/cava.json} 的 perMod 默认值、
 * 单测的断言都从这里取。表里的每一条都有本机 jar 证据（见 {@code docs/CAVA-compat-notes.md} 的证据表）。
 *
 * <p>本轮的归属决策（captain 已定）：
 * <ul>
 *   <li>{@code mixin.ai.pathing} —— P1 要复刻原生 A*，<b>先关</b>利锂这个组。</li>
 *   <li>{@code mixin.entity.collisions.movement} —— P2 才决定，本轮不关（现状 = 让位）。</li>
 *   <li>{@code mixin.block.redstone_wire} —— P3 才决定，本轮不关。</li>
 *   <li>{@code mixin.shapes} —— 不在我们的注入点，不关。</li>
 * </ul>
 */
public final class CompatTable {

    /** 与 Cava 有重叠、需要在报告里逐个交代的 mod。 */
    public static final List<String> RELEVANT_MODS = List.of(
            "lithium", "servercore", "vmp", "carpet", "carpet-tis-addition");

    /** 覆盖"镜像"的伪子系统 id（不参与子系统禁用裁决）。 */
    public static final String MIRROR = "mirror";

    private CompatTable() {
    }

    /**
     * 全部重叠点。
     *
     * <p>证据（javap / jar 实读，逐条见 docs/CAVA-compat-notes.md）：
     * <ul>
     *   <li>lithium：「mixin.ai.pathing / mixin.entity.collisions.movement / mixin.block.redstone_wire /
     *       mixin.shapes」四个组名在 {@code assets/lithium/lithium-mixin-config-default.properties} 里实读存在。</li>
     *   <li>servercore：refmap 实读 {@code servercore-common-refmap.json}
     *       → {@code activation_range.EntityMixin.push(DDD)V = class_1297;method_5762(DDD)V}（Yarn 名
     *       {@code Entity.addVelocity}）、{@code move = method_5784}；
     *       接口 {@code .../interfaces/activation_range/Inactive} 只声明 {@code servercore$inactiveTick()}，
     *       <b>{@code servercore$isInactive()} 在 {@code ActivationEntity} 上</b>（任务书此处口径需更正）。</li>
     *   <li>vmp：refmap 实读 {@code vmp-fabric-mc1.20.4-refmap.json}
     *       → {@code entity.move_zero_velocity.MixinEntity.move = class_1297;method_5784}、
     *       {@code setBoundingBox = method_5857}；该 mixin 类的前缀不在 {@code VMPMixinPlugin} 的门控前缀里
     *       → <b>无条件生效、不可配置</b>。</li>
     *   <li>carpet / tis：规则名来自 {@code docs/CAVA-服务器模组清单.md} 附录 A.4。</li>
     * </ul>
     */
    public static final List<OverlapPoint> OVERLAPS = List.of(
            // ---------------- 寻路 ----------------
            new OverlapPoint("pathfind", "lithium", "mixin.ai.pathing", Owner.NATIVE, OverlapPoint.Stage.DECIDED,
                    "LandPathNodeMaker 缓存短路（priority 990）；不碰 PathNodeNavigator"),
            new OverlapPoint("pathfind", "servercore", "optimizations.misc.PathFinderMixin", Owner.NATIVE,
                    OverlapPoint.Stage.DECIDED,
                    "PathFinder 体内 4x@Redirect + 2x@ModifyVariable；不可配置，但我们 HEAD 接管后体内补丁自然不执行"),
            new OverlapPoint("pathfind", "servercore", "optimizations.sync_loads.GroundPathNavigationMixin", Owner.NATIVE,
                    OverlapPoint.Stage.DECIDED, "GroundPathNavigation.createPath HEAD cancellable"),
            // ---------------- 实体 ----------------
            new OverlapPoint("entity", "lithium", "mixin.entity.collisions.movement", Owner.MOD,
                    OverlapPoint.Stage.PENDING_P2, "@Overwrite Entity.adjustMovementForCollisions（method_20736）"),
            new OverlapPoint("entity", "servercore", "activation_range.EntityMixin#addVelocity", Owner.MOD,
                    OverlapPoint.Stage.PENDING_P2,
                    "Entity.addVelocity(DDD)V 即 method_5762 HEAD cancellable：isInactive && !world.isClient 时取消"),
            new OverlapPoint("entity", "vmp", "entity.move_zero_velocity.MixinEntity", Owner.MOD,
                    OverlapPoint.Stage.PENDING_P2,
                    "Entity.move 即 method_5784 HEAD cancellable：!boundingBoxChanged && movement.equals(Vec3d.ZERO) 时取消"),
            // ---------------- 红石 ----------------
            new OverlapPoint("redstone", "lithium", "mixin.block.redstone_wire", Owner.MOD, OverlapPoint.Stage.PENDING_P3,
                    "RedstoneWireBlock.getReceivedRedstonePower（method_27842）HEAD + cancel 整段替换（priority 990）"),
            new OverlapPoint("redstone", "carpet", "CarpetSettings.fastRedstoneDust", Owner.MOD,
                    OverlapPoint.Stage.PENDING_P3,
                    "RedstoneWireBlock.update（method_10485）HEAD cancellable；用户已开启 → 红石基准就是它"),
            new OverlapPoint("redstone", "carpet-tis-addition", "CarpetTISAdditionSettings.redstoneDustRandomUpdateOrder",
                    Owner.MOD, OverlapPoint.Stage.PENDING_P3, "随机化红石粉更新顺序（最毒，见 A.4 第 5 条）"),
            // ---------------- 镜像（登记但不裁决） ----------------
            new OverlapPoint(MIRROR, "lithium", "mixin.shapes", Owner.MOD, OverlapPoint.Stage.NOT_HOOKED,
                    "VoxelShape 系列；不在 Cava 注入点上，不关"));

    public static List<OverlapPoint> forSubsystem(String subsystem) {
        List<OverlapPoint> out = new ArrayList<>();
        for (OverlapPoint p : OVERLAPS) {
            if (p.subsystem().equals(subsystem)) {
                out.add(p);
            }
        }
        return out;
    }

    /** 需要裁决的子系统 id（去掉 mirror 这种伪子系统）。 */
    public static List<String> decidedSubsystems() {
        Set<String> s = new LinkedHashSet<>();
        for (OverlapPoint p : OVERLAPS) {
            if (p.affectsCavaSubsystem()) {
                s.add(p.subsystem());
            }
        }
        return List.copyOf(s);
    }

    /** 表里出现过的全部 mod id。 */
    public static List<String> mods() {
        Set<String> s = new LinkedHashSet<>();
        for (OverlapPoint p : OVERLAPS) {
            s.add(p.modId());
        }
        return List.copyOf(s);
    }
}
