package cava.mixin.entity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code Entity} 上「{@code move} 需要、但从 {@code cava.mixin.entity} 看不见」的成员读取口。
 *
 * <p>只有 4 个，全部是**只读或原版自己就会做的那次写**：
 * <ul>
 *   <li>{@code nextStepSoundDistance}（{@code private float}）：原版在 {@code move} 偏移 790/808 写它，
 *       偏移 713 读它。{@code @Accessor} 同时给出读/写；写成 get/set 一对，语义与原版一致。</li>
 *   <li>{@code canClimb(BlockState)}（{@code private}）：偏移 645 调用。</li>
 *   <li>{@code stepOnBlock(...)}（{@code private}）：偏移 750/774 调用。</li>
 *   <li>{@code adjustMovementForCollisions(Vec3d)}（{@code private}，实例重载）：偏移 132 的**原生目标**。
 *       影子模式用它拿原版值做逐位比对；原生失败时也用它做**与纯 Java 完全一致**的回退。</li>
 * </ul>
 *
 * <p>{@code protected} 的成员（{@code hasCollidedSoftly} / {@code adjustMovementForSneaking} /
 * {@code adjustMovementForPiston} / {@code tryCheckBlockCollision} / {@code getMoveEffect} …）
 * 由 mixin 类用 {@code @Shadow} 声明，不需要在这里开接口。
 */
@Mixin(Entity.class)
public interface McMoveAccess {

    /** 偏移 713 读、790/808 写。 */
    @Accessor("nextStepSoundDistance")
    float cava$nextStepSoundDistance();

    /** 偏移 790/808 写。 */
    @Accessor("nextStepSoundDistance")
    void cava$setNextStepSoundDistance(float value);

    /** 偏移 645：{@code this.canClimb(steppingState)}。 */
    @Invoker("canClimb")
    boolean cava$canClimb(BlockState state);

    /** 偏移 750 / 774：{@code this.stepOnBlock(...)}（私有）。 */
    @Invoker("stepOnBlock")
    boolean cava$stepOnBlock(BlockPos pos, BlockState state, boolean playSounds, boolean emitGameEvents,
                             Vec3d movement);

    /** 偏移 132：{@code this.adjustMovementForCollisions(movement)}（私有实例重载，含台阶分支）。 */
    @Invoker("adjustMovementForCollisions")
    Vec3d cava$adjustMovementForCollisions(Vec3d movement);
}
