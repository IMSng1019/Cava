package cava.mirror;

/**
 * 方块状态的**采样来源**（MC 绑定层与单测假世界之间的唯一缝）。
 *
 * <p>实现者：{@code McStateProbe}（真实注册表）与单测里的 {@code FakeStateProbe}。
 * 键一律是 **state id**（{@code Block.getRawIdFromState}），**禁止对象身份**
 * （FerriteCore 的 blockstateCacheDeduplication 会让内容相同的状态共享实例）。
 */
public interface StateProbe {

    /** 状态总数（真实环境 = {@code Block.STATE_IDS.size()}）。 */
    int stateCount();

    /**
     * 采样 {@code stateId}（0 &lt;= stateId &lt; {@link #stateCount()}）。
     *
     * <p>实现必须**先 {@code out.reset()}** 再填。
     */
    void probe(int stateId, StateSample out);
}
