package cava.entity;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * <b>拉取式</b>回放输入源：{@link EventReplay} 走到某一步时，<b>按原版顺序</b>向 Java 侧
 * 「要」那个输入，而不是提前全部打包。
 *
 * <h2>为什么必须拉取（这是本接口存在的唯一理由）</h2>
 * {@code Entity.move} 里有四处输入<b>只有回放中途才成立</b>，预打包模型拿不到同一时刻的值：
 * <ol>
 *   <li>{@link #landingPos()}（偏移 416）在 {@code setOnGround}（412）<b>之后</b>读；
 *       {@code setOnGround} → {@code updateSupportingBlockPos} → {@code world.findSupportingBlockPos}
 *       会改写 {@code supportingBlockPos}，而 {@code getLandingPos()} 依赖它；</li>
 *   <li>{@link #steppingPos()}（偏移 626）在 {@code moveEffect} 分支<b>内部</b>才求值；</li>
 *   <li>{@link #regionLoaded()}（{@code checkBlockCollision} 偏移 67）扫的是 {@code setPosition}（218）
 *       <b>之后</b>的碰撞箱；</li>
 *   <li>{@link #stepSoundDistanceExceeded()}（708–717）依赖 {@code distanceTraveled}/{@code nextStepSoundDistance}
 *       —— 那是 589–705 的记账<b>中途</b>才被写的。</li>
 * </ol>
 *
 * <h2>契约</h2>
 * <ul>
 *   <li>每个方法 = 一个<b>拉取点</b>，括号里的偏移就是原版读它的位置；方法体必须<b>当场</b>
 *       向实体/世界取值，<b>不允许</b>缓存成"预先算好的副本"（那就退回了预打包模型）。</li>
 *   <li>调用顺序由 {@link EventReplay} 按 {@link MoveStep#ordinal()} 决定，
 *       <b>不在这里、也不在原生侧重复定义</b>。</li>
 *   <li>本接口<b>不抛异常</b>给调用方做控制流：拉取失败由生产实现自己回退（见
 *       {@code EntityMoveRuntime} 的 pre-flight）。</li>
 * </ul>
 *
 * <p>实现有两份：{@link MoveInputs}（定值记录，单测/差分夹具用）与
 * {@code LiveMoveSession}（生产：每次拉取都回实体现取）。
 */
public interface MoveInputSource {

    // ------------------------------------------------------------------
    // 偏移 129 / 135：两个位移（这两个不是"中途才成立"的，但同样经接口取）
    // ------------------------------------------------------------------

    /** 过完 {@code adjustMovementForSneaking} 的位移；偏移 129（{@code aload_2}）。 */
    Vec3d movement();

    /** 碰撞求解后的位移（原生的输出）；偏移 135（{@code aload_3}）。 */
    Vec3d adjusted();

    // ------------------------------------------------------------------
    // 逐步拉取点
    // ------------------------------------------------------------------

    /** 偏移 219：{@code getX()}（{@code onLanding} 之后才读，所以必须在这里拉）。 */
    double posX();

    /** 偏移 228：{@code getY()}。 */
    double posY();

    /** 偏移 237：{@code getZ()}。 */
    double posZ();

    /** 偏移 152：{@code fallDistance}（射线守卫；{@code fall()} 之前）。 */
    float fallDistance();

    /**
     * 偏移 416：{@code getLandingPos()}。
     * <b>必须在 {@link MoveCallbacks#setOnGround}(Z,Vec3d)（偏移 412）之后拉取</b> ——
     * 那一步会改写 {@code supportingBlockPos}。
     */
    BlockPos landingPos();

    /**
     * 偏移 626：{@code getSteppingPos()}。只在 {@code getMoveEffect().hasAny() && !hasVehicle()}
     * 分支内部拉取。
     */
    BlockPos steppingPos();

    /** 偏移 576：{@code getMoveEffect().hasAny()}。 */
    boolean moveEffectHasAny();

    /** 偏移 583：{@code hasVehicle()}。 */
    boolean hasVehicle();

    /**
     * 偏移 708–717：{@code distanceTraveled > nextStepSoundDistance}。
     *
     * <p>必须在 {@link MoveCallbacks#moveEffectBookkeeping}(BlockPos,Object)（589–705，
     * 写 {@code distanceTraveled}）<b>之后</b>拉取，否则读到的是上一 tick 的值。
     * 后半条 {@code && !steppingState.isAir()} 由回放在这里用
     * {@link MoveCallbacks#stateIsAir}(Object) 现问（{@code &&} 的短路顺序就是原版的顺序）。
     */
    boolean stepSoundDistanceExceeded();

    /** 偏移 728–735：{@code steppingPos.equals(landingPos)}。 */
    boolean steppingEqualsLanding();

    /** 偏移 742：{@code getMoveEffect().playsSounds()}。 */
    boolean moveEffectPlaysSounds();

    /** 偏移 768：{@code getMoveEffect().emitsGameEvents()}。 */
    boolean moveEffectEmitsGameEvents();

    /** 偏移 796：{@code isTouchingWater()}（只在 {@code !played} 分支里问）。 */
    boolean touchingWater();

    /**
     * {@code checkBlockCollision} 偏移 67：{@code world.isRegionLoaded(from,to)}
     * （{@code from/to} 由 {@code setPosition}（218）之后的碰撞箱决定）。
     */
    boolean regionLoaded();

    /** 偏移 858：{@code getVelocityMultiplier()}（最后一步，读的是当前位置下的世界状态）。 */
    float velocityMultiplier();

    // ------------------------------------------------------------------
    // 原生内核目前**不产生**的三类事实：由 Java 侧在该步现算并注入事件
    // ------------------------------------------------------------------

    /**
     * 在某个步骤的<b>那一刻</b>现算该步的 Java 侧事实，并把它们作为事件注入 {@code log}。
     *
     * <h2>为什么需要这个钩子</h2>
     * 冻结的 {@code cava_resolve_move} 只产出一种事件（几何裁剪 {@link MoveEventKind#AXIS_CLIP}）。
     * 另外三类事实（射线命中 / 逐格碰撞扫描 / 火焰盒）在原版里都是<b>世界查询</b>，
     * 内核没有世界，只能由 Java 侧在<b>同一时刻</b>现算 —— 拉到 {@link EventReplay} 里执行，
     * 而不是提前算好。这样事件日志、未消费检查、anomaly 检查这套机制对三类事实<b>原样适用</b>。
     *
     * <p><b>回执校验</b>：返回值 = 本次注入的事件条数；回放在该步消费完之后必须消费到同样多条，
     * 否则记 {@code anomalies}（防止"注入与消费不是同一批"这种静默错位）。
     *
     * @param step 当前正在回放的步骤（顺序唯一事实来源仍是 {@link MoveStep#ordinal()}）
     * @param log  事件日志（内核事件已经写在里面；本方法<b>追加</b>）
     * @return 本次注入的事件条数
     */
    default int supplyEventsAt(MoveStep step, MoveEventLog log) {
        return 0;
    }
}
