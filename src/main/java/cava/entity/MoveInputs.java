package cava.entity;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 回放所需、但<b>不由原生提供</b>的 Java 侧输入。
 *
 * <p>为什么不是"原生全算完"：原版 {@code move} 里有相当一部分判定依赖
 * 只存在于 Java 侧的实体内部状态（{@code supportingBlockPos} 驱动的
 * {@code getLandingPos()}、{@code distanceTraveled}/{@code nextStepSoundDistance}、
 * {@code MoveEffect} 标志、载具关系……）。这些不是几何，搬过去只会多一份会漂移的副本。
 * 所以本记录只承载"分支判定所需的最小事实"，其余原样留在 Java 侧。
 *
 * <p>每个字段后面标的是它在 {@code Entity.move} 字节码里的出处。
 * <b>凡是标了「适配器算」的字段，都必须由注入流用原版方法/字段取值，
 * 不允许在原生侧重算</b>（P1 的教训：同一份语义两处实现 ⇒ 必然漂移）。
 *
 * <p><b>故意不放这里的两个量</b>（避免出现第二份事实来源 —— P1 的硬教训）：
 * <ul>
 *   <li>「包围盒里有火」：那是 {@link MoveEventKind#FIRE_IN_BOX} 事件，
 *       由原生发现；回放只看事件，不看 Java 侧的重复判定。</li>
 *   <li>「碰撞扫描命中的方块集合」：同理，是 {@link MoveEventKind#COLLIDING_BLOCK} 事件流。</li>
 * </ul>
 */
public record MoveInputs(
        /** 过完 {@code adjustMovementForSneaking} 的位移；偏移 129（{@code aload_2}）。 */
        Vec3d movement,
        /** 碰撞求解后的位移（原生的输出）；偏移 135（{@code aload_3}）。 */
        Vec3d adjusted,
        /** 本 tick 移动<b>之前</b>的 {@code getX()}；偏移 218。 */
        double posX,
        /** 偏移 228 的 {@code getY()}。 */
        double posY,
        /** 偏移 237 的 {@code getZ()}。 */
        double posZ,
        /** {@code getLandingPos()}；偏移 416。适配器算（依赖 {@code supportingBlockPos}，只存在于 Java 侧）。 */
        BlockPos landingPos,
        /** {@code getSteppingPos()}；偏移 626。适配器算。 */
        BlockPos steppingPos,
        /** {@code fallDistance}；偏移 152。 */
        float fallDistance,
        /** {@code getVelocityMultiplier()}；偏移 858。适配器算。 */
        float velocityMultiplier,
        /** {@code getMoveEffect().hasAny()}；偏移 576。适配器算。 */
        boolean moveEffectHasAny,
        /** {@code getMoveEffect().playsSounds()}；偏移 744。适配器算。 */
        boolean moveEffectPlaysSounds,
        /** {@code getMoveEffect().emitsGameEvents()}；偏移 770。适配器算。 */
        boolean moveEffectEmitsGameEvents,
        /** {@code hasVehicle()}；偏移 583。 */
        boolean hasVehicle,
        /** {@code isTouchingWater()}；偏移 797。 */
        boolean touchingWater,
        /**
         * 偏移 708–725 的合成判定：
         * {@code distanceTraveled > nextStepSoundDistance && !steppingState.isAir()}。适配器算。
         */
        boolean stepSoundBranch,
        /** {@code steppingState.isAir()}；偏移 722/841。 */
        boolean steppingStateIsAir,
        /** {@code steppingPos.equals(landingPos)}；偏移 730。 */
        boolean steppingEqualsLanding,
        /** {@code world.isRegionLoaded(from, to)}；{@code checkBlockCollision} 偏移 67。适配器算。 */
        boolean regionLoaded) {

    public MoveInputs {
        if (movement == null || adjusted == null || landingPos == null || steppingPos == null) {
            throw new IllegalArgumentException("MoveInputs 的向量/坐标不允许为 null");
        }
    }

    /** {@code Vec3d.lengthSquared()}；偏移 137。 */
    public double adjustedLengthSquared() {
        return adjusted.lengthSquared();
    }

    /** 偏移 148 的守卫：{@code d > 1.0E-7}（不满足则整段 setPosition 被跳过）。 */
    public boolean movedAtAll() {
        return adjustedLengthSquared() > 1.0E-7;
    }

    /** {@code getX()+adjusted.x} 等（偏移 218–245 的实参）。 */
    public Vec3d positionAfterMove() {
        return new Vec3d(posX + adjusted.x, posY + adjusted.y, posZ + adjusted.z);
    }
}
