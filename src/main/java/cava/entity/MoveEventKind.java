package cava.entity;

/**
 * 「原生返回的事件」的种类 —— 原生在解算 {@code Entity.move} 的几何过程中
 * <b>发现的世界事实</b>（不是几何本身）。
 *
 * <p><b>设计要点</b>：
 * <ol>
 *   <li>事件只携带 <b>方块坐标</b>（+ 一个 {@code payload} 整数），
 *       <b>绝不携带几何</b>。Java 侧回放时按坐标回世界取<b>原始对象</b>
 *       （{@link MoveCallbacks#stateAt}）—— 这是"mod 覆写的方块行为不丢"的前提。</li>
 *   <li>事件<b>只标种类，不带顺序号</b>：顺序的唯一事实来源是
 *       {@link #step()}（→ {@link MoveStep#ordinal()}），原生写错顺序也不会造成行为差异。</li>
 *   <li>回放时<b>未消费的事件视为错误</b>（fail-closed），不静默丢弃 ——
 *       静默丢弃 = 少调一次 mod 的虚方法 = 逐 tick 差分里再也查不出来的偏差。</li>
 * </ol>
 */
public enum MoveEventKind {

    /**
     * {@link MoveStep#LANDING_RAYCAST}：FALLDAMAGE_RESETTING 射线命中了方块。
     * {@code payload} = {@code HitResult.Type} 的 ordinal；{@code pos} = 命中方块坐标。
     * 只有"命中"才发事件（MISS 不产生任何回调）。
     */
    LANDING_RAYCAST_HIT(MoveStep.LANDING_RAYCAST),

    /**
     * {@link MoveStep#BLOCK_COLLISION}：{@code checkBlockCollision} 会遍历到的方块之一。
     * 原生按<b>原版遍历顺序</b>（x→y→z）逐格追加；{@code payload} 未使用。
     */
    COLLIDING_BLOCK(MoveStep.BLOCK_COLLISION),

    /**
     * {@link MoveStep#FIRE_BOX}：收缩 {@code 1.0E-6} 的包围盒里存在 FIRE 状态
     * （即原版 {@code noneMatch(isOf(FIRE))} 为 false）。{@code pos}/{@code payload} 未使用。
     */
    FIRE_IN_BOX(MoveStep.FIRE_BOX),

    /**
     * <b>只诊断、不驱动回调</b>：{@link MoveStep#COLLISION_SOLVE} 内部某个轴被裁剪。
     * {@code payload} = 轴（0=X, 1=Y, 2=Z，对应 {@code Vec3d} 的分量序），
     * {@code pos} = 造成裁剪的方块。
     *
     * <p>它不调用任何虚方法，但会被写进 {@link EventReplay} 的回放流水里 ——
     * 逐 tick 差分时这是"哪一格、哪个轴先被夹住"的第一手证据。
     */
    AXIS_CLIP(MoveStep.COLLISION_SOLVE);

    private final MoveStep step;

    MoveEventKind(MoveStep step) {
        this.step = step;
    }

    /** 本类事件归属的原版步骤（顺序的唯一事实来源）。 */
    public MoveStep step() {
        return step;
    }

    /** 轴序（{@link #payload} 的语义），只对 {@link #AXIS_CLIP} 有意义。 */
    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_Z = 2;
}
