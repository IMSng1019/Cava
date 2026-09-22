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
}
