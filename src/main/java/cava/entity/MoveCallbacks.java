package cava.entity;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 回放的<b>可测注入点</b>：原版 {@code Entity.move} 里全部"会被 mod 覆写"的调用。
 *
 * <p>单测用假的"方块行为"实现本接口，就能在没有服务器、没有 MC 注册表的情况下
 * 逐条断言"调了哪些方法、按什么顺序、参数是什么"。
 *
 * <h2>两条硬契约</h2>
 * <ol>
 *   <li>{@link #stateAt(BlockPos)} 必须返回世界里的<b>原始方块状态对象</b>，
 *       {@link #collisionShapeAt(BlockPos)} 必须返回<b>原始碰撞形状对象</b>（{@code VoxelShape}）。
 *       <b>不允许</b>用原生算出来的扁平 AABB 重建替身：</li>
 *   <li>同一个 {@code BlockPos} 在一次回放里只会被解析 <b>一次</b>（原版偏移 422 取一次、
 *       后面 518/565 复用同一引用）。回放把这个引用原样传给所有需要的回调 ——
 *       对象身份必须保持，否则 FerriteCore 那类"内容相同的状态共享同一个
 *       {@code VoxelShape} 实例"的 mod，其行为会在我们这里被悄悄改写。</li>
 * </ol>
 *
 * @param <S> 方块状态类型（生产：{@code net.minecraft.block.BlockState}；单测：任意假类型）
 * @param <H> 碰撞形状类型（生产：{@code net.minecraft.util.shape.VoxelShape}；单测：任意假类型）
 */
public interface MoveCallbacks<S, H> {

    /** 世界里的原始方块状态对象（含 mod 覆写的行为）。 */
    S stateAt(BlockPos pos);

    /**
     * 世界里的<b>原始碰撞形状对象</b>。
     *
     * <p>存在的唯一理由：把"只传扁平 AABB"这条捷径堵死。
     * 原生解算需要的是形状本身（{@code VoxelShape}），一旦在某处把它降级成 6 个 double，
     * 位置依赖的形状（{@code BambooBlock} / {@code PointedDripstoneBlock} / {@code ScaffoldingBlock}
     * 这类覆写了带 pos/ShapeContext 形状方法的方块）就会静默发散。
     */
    H collisionShapeAt(BlockPos pos);

    /** 偏移 214：{@code Entity.onLanding()}。 */
    void onLanding();

    /** 偏移 245：{@code Entity.setPosition(DDD)}。 */
    void setPosition(double x, double y, double z);

    /** 偏移 382–403：把四个碰撞标志位写回实体。 */
    void setCollisionFlags(MoveFlags flags);

    /** 偏移 391：{@code Entity.hasCollidedSoftly(Vec3d)}（仅 horizontalCollision 为真时调用）。 */
    boolean hasCollidedSoftly(Vec3d adjusted);

    /** 偏移 412：{@code Entity.setOnGround(Z,Vec3d)}。 */
    void setOnGround(boolean onGround, Vec3d adjusted);

    /** 偏移 438/548：{@code Entity.isOnGround()}。 */
    boolean isOnGround();

    /** 偏移 445：{@code Entity.fall(D,Z,BlockState,BlockPos)}。 */
    void fall(double heightDifference, boolean onGround, S state, BlockPos pos);

    /** 偏移 448：{@code Entity.isRemoved()}（为真则回放立即结束）。 */
    boolean isRemoved();

    /** checkBlockCollision 每格开头：{@code Entity.isAlive()}（为假则整段 return）。 */
    boolean isAlive();

    /** 偏移 476：{@code Entity.getVelocity()}。 */
    Vec3d getVelocity();

    /** 偏移 515：{@code Entity.setVelocity(DDD)}。 */
    void setVelocity(double x, double y, double z);

    /** 偏移 544：{@code Block.onEntityLand(BlockView,Entity)}。 */
    void onEntityLand(S state);

    /** 偏移 565：{@code Block.onSteppedOn(World,BlockPos,BlockState,Entity)}。 */
    void onSteppedOn(S state, BlockPos pos);

    /** 偏移 749/774：{@code Entity.stepOnBlock(...)} 的返回值（决定是否刷新 nextStepSoundDistance）。 */
    boolean stepOnBlock(BlockPos pos, S state, boolean playSounds, boolean emitGameEvents, Vec3d movement);

    /** 偏移 811–835：{@code playSwimSound()} + {@code emitGameEvent(GameEvent.SWIM)}。 */
    void onSwimEffects();

    /** 偏移 850：{@code Entity.addAirTravelEffects()}。 */
    void onAirTravelEffects();

    /** 偏移 167：{@code BlockState.onEntityCollision(World,BlockPos,Entity)}。 */
    void onEntityCollision(S state, BlockPos pos);

    /** 偏移 173：{@code Entity.onBlockCollision(BlockState)}。 */
    void onBlockCollision(S state);

    /** 偏移 875：{@code setVelocity(getVelocity().multiply(f,1.0,f))}。 */
    void multiplyVelocity(float factor);

    /**
     * 偏移 895–982：火焰分支整段。
     * {@code firePresent == false} → 原版第一个分支（{@code setFireTicks} / {@code playExtinguishSound}）；
     * {@code true} → 第二个分支（{@code isOnFire() && (inPowderSnow || isWet())}）。
     */
    void onFireStep(boolean firePresent);
}
