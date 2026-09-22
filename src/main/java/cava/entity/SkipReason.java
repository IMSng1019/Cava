package cava.entity;

/**
 * 本 tick「被跳过」的原因。**这是差分证据的一部分**，不是日志装饰：
 * 服务器上位置不变的实体有两种完全不同的解释 ——
 * 「被 ServerCore 激活范围整 tick 跳过」（预期）与「我们自己的打包漏了它」（bug），
 * 只有把它们分门别类记下来才能区分。
 */
public enum SkipReason {

    /**
     * ServerCore 激活范围判定为未激活：{@code ServerLevel.tickNonPassenger}
     * 用 {@code @WrapWithCondition} 整个跳过 {@code Entity.tick()}。
     *
     * <p><b>预期行为</b>：该实体本 tick 位置/速度不变。镜像仍然打包它
     * （它照样参与碰撞与推挤判定），但原生侧不得推进它。
     */
    SERVERCORE_INACTIVE("servercore-inactive"),

    /** {@code Entity.isRemoved()}：已移除，不参与本 tick。 */
    REMOVED("removed"),

    /** 源方 {@code sample()} 返回 false：这一行读不出来（区块未加载等），<b>需要调查</b>。 */
    UNSAMPLABLE("unsamplable");

    private final String tag;

    SkipReason(String tag) {
        this.tag = tag;
    }

    public String tag() {
        return tag;
    }
}
