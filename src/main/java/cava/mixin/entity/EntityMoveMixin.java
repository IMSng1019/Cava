package cava.mixin.entity;

import cava.entity.EntityMoveRuntime;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Entity.move(MovementType, Vec3d)}（intermediary {@code method_5784}）的**唯一**注入点。
 *
 * <p><b>注入形态</b>：{@code @Inject(at = HEAD, cancellable = true, require = 0)}，显式
 * {@code priority = 900}（默认 1000）。不 cancellable 时不改任何原版指令，也不读写原版局部变量。
 *
 * <p><b>为什么不与 VMP 的 {@code @Inject(HEAD, cancellable)} 打架</b>：
 * 两者都在 HEAD、都可取消。VMP 的语义是「{@code !boundingBoxChanged && movement.equals(Vec3d.ZERO)}
 * ⇒ 取消整段、无任何副作用」。本类的处理保持**顺序无关**：
 * <ul>
 *   <li>本流的 {@code off}/{@code shadow} 模式**从不取消** ⇒ VMP 的行为完全不受影响；</li>
 *   <li>{@code live} 模式本轮未交付（见 {@link EntityMoveRuntime#LIVE_NOT_SHIPPED_REASON}），
 *       所以现在不存在"我们取消、VMP 没机会跑"的情况。</li>
 * </ul>
 * 将来交付 live 时，必须**同时**接上 {@code cava.entity.VmpZeroVelocityGate}
 * （它复刻了含黏滞语义的完整状态机），并且只在 {@code vmp.mixins.json} 真的在类路径上时才启用
 * ——否则会引入本机服务器根本没有的行为。
 *
 * <p><b>形状采集用的 {@code @Invoker}/{@code @Accessor} 是另一组 mixin</b>
 * （{@link VoxelShapeAccessor} / {@link McMoveAccess}），它们只加薄包装、不改任何原版行为。
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class EntityMoveMixin {

    /**
     * @param movementType 原版第一个实参
     * @param movement     原版第二个实参（**已经由 {@code travel} 算好**；三角函数留在 Java 侧）
     * @param ci           cancel 句柄；只有 {@link EntityMoveRuntime#onMoveHead} 明确要求时才取消
     */
    @Inject(method = "move", at = @At("HEAD"), cancellable = true, require = 0)
    private void cava$moveHead(MovementType movementType, Vec3d movement, CallbackInfo ci) {
        if (EntityMoveRuntime.onMoveHead((Entity) (Object) this, movementType, movement)) {
            ci.cancel();
        }
    }
}
