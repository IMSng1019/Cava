package cava.mirror;

/**
 * 区域推送前的"状态表闸门"（抽出来是为了单测能绕开真实注册表）。
 *
 * <p>实现者：{@link BlockStateTable}（真实）与单测里的合成表。
 * 语义：{@code uploadIfNeeded()} 返回 false 表示**镜像侧不可用，调用方必须回退**。
 */
public interface StateTableGate {

    /** 确保状态表已构建并**一次性**上传给原生。失败 → false。 */
    boolean uploadIfNeeded();

    /** 表是否已上传成功。 */
    boolean ready();

    /** 区域外填充 / 越界填充用的 air state id。 */
    int airStateId();

    /** 不可用原因（供日志）。 */
    String failure();

    /**
     * 状态表自检：{@code commonNodeType(flags) == path_type_idx} 对**全部**状态成立。
     *
     * <p>这条是"位填错了"的报警器：19 个谓词位与 path_type_idx 是同一套原版语义的两种表达，
     * 任何一位填反/填漏都会让两者对不上。
     *
     * <p>默认 {@code true}（单测的假实现不必关心）；{@link BlockStateTable} 在构建时会真的算一遍。
     */
    default boolean selfConsistent() {
        return true;
    }

    /**
     * 该 state 的碰撞盒是否"位置/上下文相关"（冻结 ABI 的一组盒表达不了）。
     *
     * <p>captain 裁决 1 的保守守卫：区域里出现这种状态就**回退原逻辑**（不加速、也不出错）。
     * 判定在建表时做完（静态类清单 + 18 个合成上下文的经验测试），这里只做 O(1) 查表。
     *
     * <p>默认 {@code false}（单测的假实现不必关心）。
     */
    default boolean isShapeGuarded(int stateId) {
        return false;
    }
}
