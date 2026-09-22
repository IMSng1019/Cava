package cava.hook;

import cava.mixin.pathfind.AmphibiousPathNodeMakerAccessor;
import net.minecraft.entity.ai.pathing.AmphibiousPathNodeMaker;

/**
 * 读 {@code AmphibiousPathNodeMaker.penalizeDeepWater} 的唯一入口。
 *
 * <p>实现是 {@link AmphibiousPathNodeMakerAccessor}（{@code @Accessor} mixin）。
 * **不要退回反射**：生产环境里 MC 的字段名是 intermediary（实测
 * {@code NoSuchFieldException: no such field: net.minecraft.class_15.penalizeDeepWater}），
 * 反射在开发环境能过、在真实服务端会静默失败。
 *
 * <p>读不到 = 少一个能力位 ⇒ 原生侧的马吕斯与原版不同 ⇒ {@link PathfindHook} 拒绝对该生物原生接管。
 */
public final class AmphibiousPathNodeMakerAccess {

    private AmphibiousPathNodeMakerAccess() {
    }

    /** 该实例是否可读（= accessor mixin 是否真的应用到了这个类上）。 */
    public static boolean readable(AmphibiousPathNodeMaker maker) {
        return maker instanceof AmphibiousPathNodeMakerAccessor;
    }

    /** 解析状态一行摘要。 */
    public static String describe() {
        Object probe = null;
        try {
            probe = new AmphibiousPathNodeMaker(true);
        } catch (Throwable ignored) {
            // 构造失败不影响判定：类型关系在编译期与 mixin 应用期已确定
        }
        boolean ok = probe instanceof AmphibiousPathNodeMakerAccessor;
        return "penalizeDeepWater=" + (ok ? "readable" : "UNREADABLE(accessor mixin 未应用)");
    }

    /** 读取；读不到时返回 false（调用方必须先查 {@link #readable}）。 */
    public static boolean penalizeDeepWater(AmphibiousPathNodeMaker maker) {
        if (maker instanceof AmphibiousPathNodeMakerAccessor acc) {
            try {
                return acc.cava$penalizeDeepWater();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }
}
