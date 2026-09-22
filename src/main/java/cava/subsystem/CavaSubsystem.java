package cava.subsystem;

/**
 * 子系统注册的唯一入口（契约第 3 节，**接口已冻结**）。
 *
 * <p>回退语义：{@link #enabled()} 为 false 时钩子必须**完全不介入**
 * （注入方法第一句就是 {@code if (!enabled) return;}）。
 */
public interface CavaSubsystem {

    /** "pathfind" / "entity" / "redstone"。 */
    String id();

    /** 原生句柄有效且布局自检通过。 */
    boolean nativeReady();

    /** 主动触发一次目标方法，看计数器有没有动。 */
    void canaryProbe();

    /** 钩子命中次数。 */
    long canaryCount();

    /** 失败回退：置为禁用并记录。 */
    void disable(String reason);

    boolean enabled();

    /** 禁用原因（未禁用时为空串）。 */
    default String disabledReason() {
        return "";
    }

    /** 该子系统是否真的装了钩子（P0 骨架 = false，不参与金丝雀判定，避免误报 ERROR）。 */
    default boolean hooksInstalled() {
        return false;
    }

    /** 一行状态，供启动报告打印。 */
    default String describe() {
        return String.format("%-9s enabled=%-5s hooks=%-5s canary=%-6d nativeReady=%-5s %s",
                id(), enabled(), hooksInstalled(), canaryCount(), nativeReady(), disabledReason());
    }
}
