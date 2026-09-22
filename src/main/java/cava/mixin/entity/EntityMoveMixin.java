package cava.mixin.entity;

import cava.entity.EntityMoveRuntime;
import cava.entity.VmpZeroVelocityGate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Entity.move(MovementType, Vec3d)}（intermediary {@code method_5784}）的**唯一行为注入点**，
 * 外加两个**只计数、不改指令**的观测注入。
 *
 * <h2>1. {@code move} HEAD（可取消）</h2>
 * {@code @Inject(at = HEAD, cancellable = true, require = 0)}，显式 {@code priority = 900}（默认 1000）。
 * {@code off}/{@code shadow} 模式**从不 cancel**；{@code live} 模式在
 * {@link EntityMoveRuntime#onMoveHead} 明确要求时才 {@code ci.cancel()}。
 *
 * <h3>与 VMP 的 {@code @Inject(HEAD, cancellable)} 不打架（**顺序无关**的论证）</h3>
 * VMP 的语义是「{@code !boundingBoxChanged && movement.equals(Vec3d.ZERO)} ⇒ 取消整段、无任何副作用」。
 * 本流的 {@code live} 接管规则里**零位移被显式排除**（{@link EntityMoveRuntime} 类注释第 1 条），
 * 所以两个 cancel 集合恒不相交：无论谁先被 Mixin 调用、无论 cancel 之后对方的 handler 还会不会被执行，
 * 结果都一样。VMP 的黏滞状态机仍被复刻（{@link VmpZeroVelocityGate}）并在 live 下逐次询问，
 * 但**它的返回值从不驱动我们的 cancel** —— 这是"不需要猜 Mixin priority 语义"的关键。
 *
 * <h2>2. {@code move} RETURN（只计数）</h2>
 * 统计"原版 {@code move} 的方法体真的跑到了返回"的次数。{@code live} 接管时
 * {@code ci.cancel()} 会让方法从注入点直接返回，<b>RETURN 注入不会执行</b> ——
 * 于是 {@code liveTakeovers=N && vanillaMoveReturns=0} 就是"接管真的发生了"的可观测证据
 * （不是只调了一次计数器）。
 *
 * <h2>3. {@code setBoundingBox} HEAD（只喂黏滞标志）</h2>
 * VMP 的零位移短路有一个**黏滞语义**：包围盒真变过一次之后，该实体永久失去短路。
 * 复刻它的唯一时序约束是"必须在字段赋值<b>之前</b>比较旧盒与新盒"
 * （见 {@link VmpZeroVelocityGate#onSetBoundingBoxHeadBeforeAssign}）。
 * 本注入只在 {@code live} 模式且 VMP 在类路径上时才动（{@link EntityMoveRuntime#vmpGateTracking()}）。
 *
 * <p>三个注入都<b>不改写任何原版指令</b>（没有 {@code @Redirect}/{@code @Overwrite}），
 * 形状采集用的 {@code @Accessor}/{@code @Invoker} 是另一组 mixin
 * （{@link VoxelShapeAccessor} / {@link McMoveAccess}）。
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class EntityMoveMixin {

    /** 本实体自己的 VMP 黏滞门（惰性创建；只有 live 模式 + VMP 在类路径上时才被喂/被问）。 */
    @Unique
    private VmpZeroVelocityGate cava$vmpGate;

    /**
     * @param movementType 原版第一个实参
     * @param movement     原版第二个实参（**已经由 {@code travel} 算好**；三角函数留在 Java 侧）
     * @param ci           cancel 句柄；只有 {@link EntityMoveRuntime#onMoveHead} 明确要求时才取消
     */
    @Inject(method = "move", at = @At("HEAD"), cancellable = true, require = 0)
    private void cava$moveHead(MovementType movementType, Vec3d movement, CallbackInfo ci) {
        EntityMoveRuntime.timingEnter();
        VmpZeroVelocityGate gate = EntityMoveRuntime.vmpGateTracking() ? cava$vmpGate() : null;
        if (EntityMoveRuntime.onMoveHead((Entity) (Object) this, movementType, movement, gate)) {
            EntityMoveRuntime.timingExitLive();
            ci.cancel();
        }
    }

    /** 原版方法体真的执行到返回（接管成功时这里**不该**被触发）。 */
    @Inject(method = "move", at = @At("RETURN"), require = 0)
    private void cava$moveReturn(MovementType movementType, Vec3d movement, CallbackInfo ci) {
        EntityMoveRuntime.timingExitVanilla();
    }

    /** {@code setBoundingBox} 的 HEAD：字段赋值之前比较旧盒/新盒（VMP 黏滞语义的时序约束）。 */
    @Inject(method = "setBoundingBox", at = @At("HEAD"), require = 0)
    private void cava$setBoundingBoxHead(Box box, CallbackInfo ci) {
        if (!EntityMoveRuntime.vmpGateTracking()) {
            return;
        }
        EntityMoveRuntime.onSetBoundingBoxHead(cava$vmpGate(), ((Entity) (Object) this).getBoundingBox(), box);
    }

    /** 惰性取本实体的黏滞门。 */
    @Unique
    private VmpZeroVelocityGate cava$vmpGate() {
        VmpZeroVelocityGate gate = cava$vmpGate;
        if (gate == null) {
            gate = new VmpZeroVelocityGate();
            cava$vmpGate = gate;
        }
        return gate;
    }
}
