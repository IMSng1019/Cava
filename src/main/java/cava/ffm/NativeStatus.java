package cava.ffm;

/**
 * 原生库可用性状态（{@link CavaNative#status()} 的取值）。
 *
 * <p>P0 契约要求至少包含 OPEN / DISABLED_BY_FLAG / RESOURCE_MISSING / EXTRACT_FAILED /
 * LOAD_FAILED / ABI_MISMATCH / LAYOUT_MISMATCH / OPEN_FAILED。{@link #NOT_TRIED} 是
 * 初始状态（{@code tryOpen()} 尚未调用）。
 *
 * <p>任何非 {@link #OPEN} 状态都等价于「整体回退纯 Java」：所有钩子必须完全不介入。
 */
public enum NativeStatus {
    /** 尚未尝试加载（初始状态）。 */
    NOT_TRIED,
    /** 句柄有效、ABI 与布局自检全部通过。 */
    OPEN,
    /** {@code -Dcava.native.enabled=false} 或 config 里 native.enabled=false：正常路径，不加载、不报 ERROR。 */
    DISABLED_BY_FLAG,
    /** jar 内 / classpath 上找不到 natives/系统-架构/库文件。 */
    RESOURCE_MISSING,
    /** 解压到磁盘失败（写临时文件 / 原子改名失败）。 */
    EXTRACT_FAILED,
    /** System.load 或符号绑定失败（含 UnsatisfiedLinkError）。 */
    LOAD_FAILED,
    /** cava_abi_version() 与 Java 侧 CAVA_ABI_VERSION 不一致。 */
    ABI_MISMATCH,
    /** 结构体布局自检不一致（字段偏移/大小/字段数/布局哈希）——防 JVM 段错误的核心闸门。 */
    LAYOUT_MISMATCH,
    /** cava_open 返回非 CAVA_OK 或 handle == 0。 */
    OPEN_FAILED,
    /**
     * 运行时熔断：同一个原生入口连续失败 N 次（默认 5，见
     * {@link cava.harden.CircuitBreaker}）⇒ 自动全局关闭 native。
     *
     * <p>它是 <b>OPEN 之后</b>才可能出现的状态，语义与其它非 OPEN 状态完全一致：
     * 所有钩子完全不介入、整体回退纯 Java。区别只在"怎么坏的"——
     * 加载期失败是 fail-closed（布局/ABI 自检），本状态是运行期 fail-fast。
     */
    DISABLED_BY_BREAKER;

    /** 是否处于「原生可用」状态。 */
    public boolean isOpen() {
        return this == OPEN;
    }
}
