package cava.mixin.entity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code Entity} 上「{@code move} 需要、但从 {@code cava.mixin.entity} 看不见」的成员读取口。
 *
 * <p><b>只加薄包装，不改目标方法的任何一条指令</b>（与 {@code @Overwrite}/{@code @Redirect} 不是一类）。
 * {@code public} 的成员一律<b>直接调用</b>，不进本接口 —— 这是本接口为什么只有这些条目的原因：
 * <ul>
 *   <li>{@code nextStepSoundDistance}（{@code private float}）：原版在 {@code move} 偏移 790/808 写它，
 *       偏移 713 读它。</li>
 *   <li>{@code canClimb(BlockState)}（{@code private}）：偏移 645。<b>拉取式重构后不再由回放直接调</b>
 *       （它折进 {@link cava.entity.MoveCallbacks#moveEffectBookkeeping}），保留给老用例。</li>
 *   <li>{@code stepOnBlock(...)}（{@code private}）：偏移 749/774。</li>
 *   <li>{@code adjustMovementForCollisions(Vec3d)}（{@code private}，实例重载）：偏移 132 的**原生目标**。
 *       影子模式用它拿原版值做逐位比对；原生失败时也用它做**与纯 Java 完全一致**的回退。</li>
 *   <li>{@code adjustMovementForSneaking(Vec3d,MovementType)}：偏移 126，{@code move} 在求解**之前**调它。
 *       {@code live} 接管时必须调用**虚方法本身**（子类可能覆写），不能假定它返回入参。</li>
 *   <li>{@code hasCollidedSoftly(Vec3d)}（偏移 391）、{@code fall(D,Z,BlockState,BlockPos)}（445）、
 *       {@code addAirTravelEffects()}（850）、{@code onBlockCollision(BlockState)}（173）、
 *       {@code playExtinguishSound()}（949）、{@code playSwimSound()}（819）、
 *       {@code getBurningDuration()}（920/975）、{@code calculateNextStepSoundDistance()}（788/806）、
 *       {@code getMoveEffect()}（569）、{@code getVelocityMultiplier()}（858）：全是
 *       {@code protected}/{@code private}，且都是 {@code live} 回放必须**原样调用**的点。</li>
 *   <li>{@code movementMultiplier}（{@code protected Vec3d}）：偏移 86–122 的乘子块。</li>
 * </ul>
 *
 * <p>{@code public} 的成员（{@code setOnGround(Z,Vec3d)} / {@code getLandingPos()} /
 * {@code getSteppingPos()} / {@code isTouchingWater()} / {@code hasVehicle()} /
 * {@code horizontalCollision} 等四个标志位 / {@code speed} / {@code horizontalSpeed} /
 * {@code distanceTraveled} / {@code fallDistance} / {@code wasOnFire} / {@code inPowderSnow} /
 * {@code noClip} / {@code getFireTicks()} / {@code setFireTicks()} / {@code onLanding()} /
 * {@code setPosition(DDD)} / {@code setVelocity(DDD)}）由 mixin 类用 {@code @Shadow} 声明或直接访问，
 * 不需要在这里开接口（javap 实读的字段修饰符）。
 */
@Mixin(Entity.class)
public interface McMoveAccess {

    /** 偏移 713 读、790/808 写。 */
    @Accessor("nextStepSoundDistance")
    float cava$nextStepSoundDistance();

    /** 偏移 790/808 写。 */
    @Accessor("nextStepSoundDistance")
    void cava$setNextStepSoundDistance(float value);

    /** 偏移 645：{@code this.canClimb(steppingState)}（私有）。 */
    @Invoker("canClimb")
    boolean cava$canClimb(BlockState state);

    /** 偏移 750 / 774：{@code this.stepOnBlock(...)}（私有）。 */
    @Invoker("stepOnBlock")
    boolean cava$stepOnBlock(BlockPos pos, BlockState state, boolean playSounds, boolean emitGameEvents,
                             Vec3d movement);

    /** 偏移 132：{@code this.adjustMovementForCollisions(movement)}（私有实例重载，含台阶分支）。 */
    @Invoker("adjustMovementForCollisions")
    Vec3d cava$adjustMovementForCollisions(Vec3d movement);

    /** 偏移 126：{@code this.adjustMovementForSneaking(movement, type)}（虚方法，可能在子类被覆写）。 */
    @Invoker("adjustMovementForSneaking")
    Vec3d cava$adjustMovementForSneaking(Vec3d movement, MovementType type);

    /** 偏移 391：{@code this.hasCollidedSoftly(vec3d)}。 */
    @Invoker("hasCollidedSoftly")
    boolean cava$hasCollidedSoftly(Vec3d adjusted);

    /** 偏移 445：{@code this.fall(vec3d.y, isOnGround(), blockState, blockPos)}。 */
    @Invoker("fall")
    void cava$fall(double heightDifference, boolean onGround, BlockState state, BlockPos pos);

    /** 偏移 850：{@code this.addAirTravelEffects()}。 */
    @Invoker("addAirTravelEffects")
    void cava$addAirTravelEffects();

    /** 偏移 173：{@code this.onBlockCollision(state)}。 */
    @Invoker("onBlockCollision")
    void cava$onBlockCollision(BlockState state);

    /** 偏移 949：{@code this.playExtinguishSound()}。 */
    @Invoker("playExtinguishSound")
    void cava$playExtinguishSound();

    /** 偏移 819：{@code this.playSwimSound()}。 */
    @Invoker("playSwimSound")
    void cava$playSwimSound();

    /** 偏移 920 / 975：{@code this.getBurningDuration()}。 */
    @Invoker("getBurningDuration")
    int cava$getBurningDuration();

    /** 偏移 788 / 806：{@code this.calculateNextStepSoundDistance()}。 */
    @Invoker("calculateNextStepSoundDistance")
    float cava$calculateNextStepSoundDistance();

    /** 偏移 569：{@code this.getMoveEffect()}（局部 11 被 576/744/770/813/825 复用）。 */
    @Invoker("getMoveEffect")
    Entity.MoveEffect cava$getMoveEffect();

    /** 偏移 858：{@code this.getVelocityMultiplier()}（位置相关，必须在这一刻取）。 */
    @Invoker("getVelocityMultiplier")
    float cava$getVelocityMultiplier();

    /** 偏移 86：{@code this.movementMultiplier}（{@code protected}）。 */
    @Accessor("movementMultiplier")
    Vec3d cava$movementMultiplier();

    /** 偏移 112：把乘子复位成 {@code Vec3d.ZERO}。 */
    @Accessor("movementMultiplier")
    void cava$setMovementMultiplier(Vec3d value);
}
