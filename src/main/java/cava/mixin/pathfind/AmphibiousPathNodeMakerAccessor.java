package cava.mixin.pathfind;

import net.minecraft.entity.ai.pathing.AmphibiousPathNodeMaker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code AmphibiousPathNodeMaker.penalizeDeepWater}（{@code private final boolean}，无 getter）的读取口。
 *
 * <p><b>为什么必须是 accessor mixin 而不是反射</b>（本机实测的真实教训）：
 * 第一版用 {@code MethodHandles.privateLookupIn(...).findVarHandle(AmphibiousPathNodeMaker.class,
 * "penalizeDeepWater", boolean.class)}，在开发环境（named）能跑，但**生产环境里 MC 类是 intermediary**
 * （字段被 remap 成 {@code field_XXXXX}），真实服务端日志实测：
 * <pre>
 * [cava/pathfind] 读不到 AmphibiousPathNodeMaker.penalizeDeepWater
 *   （java.lang.NoSuchFieldException: no such field: net.minecraft.class_15.penalizeDeepWater/boolean/getField）
 * </pre>
 * {@code @Accessor} 的字段名会被 Loom 在 remapJar 时改写成 intermediary
 * （实测产物里 {@code PathNodeNavigatorAccessor} 的 {@code pathNodeMaker} 已变成 {@code field_61}），
 * 所以它是这里唯一可靠的形态。
 */
@Mixin(AmphibiousPathNodeMaker.class)
public interface AmphibiousPathNodeMakerAccessor {

    /** {@code private final boolean penalizeDeepWater}。 */
    @Accessor("penalizeDeepWater")
    boolean cava$penalizeDeepWater();
}
