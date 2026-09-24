package cava.harden;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 熔断器：<b>同一个原生入口连续失败 N 次</b> ⇒ 自动全局关闭 native（进程内不再尝试任何原生调用）。
 *
 * <p><b>它建在 {@code cava.ffm.CavaNative} 既有状态机之上，不另造状态机</b>：
 * 本类只管"数失败、判熔断、打一次日志"，真正的开关仍然是
 * {@code CavaNative.status()}（熔断时被置为
 * {@link cava.ffm.NativeStatus#DISABLED_BY_BREAKER}）——
 * {@code CavaNative.available()} 立刻变 false，所有钩子按既有语义完全不介入。
 *
 * <h2>阈值与默认值（{@value #DEFAULT_THRESHOLD}）的理由</h2>
 * <ul>
 *   <li><b>用"连续"而不是"累计"</b>：偶发的一次失败（例如一次边界输入触发的
 *       {@code CAVA_ERR_ARG}）不该关掉整条原生路径；一次成功即把计数清零。</li>
 *   <li><b>为什么不是 1 或 2</b>：单次失败在设计上就是"这一次调用回退原逻辑"，
 *       不是错误（P1/P2 的实测运行里逐次回退是常态路径）。阈值太低会把正常回退误判成故障。</li>
 *   <li><b>为什么不是 50 或 100</b>：实测最热的入口 {@code cava_resolve_move} 一次运行
 *       被调用 95 万次、{@code live} 接管 14.4 万次，而 <b>errors 全为 0</b>。
 *       在这个量级上"连续 5 次失败"已经出现概率极低 ⇒ 必然是系统性故障（ABI 漂移、镜像损坏、
 *       原生内部状态坏了），此时继续调用只是在为"已经确定坏掉的路径"继续付 FFM 边界成本
 *       （实测净亏 ≈ +1.86 µs/次），还让日志被刷屏。5 次 = 约 0.0035% 的调用量，
 *       既能立刻停手，又不会被瞬时抖动误伤。</li>
 *   <li><b>可配</b>：{@code -D}{@value #PROP_THRESHOLD}{@code ==<N>}；
 *       {@code <=0} = 关闭熔断（运维逃生口，仅用于"必须连续跑满、连熔断也不许发生"的场合；
 *       默认不推荐）。</li>
 * </ul>
 *
 * <h2>日志纪律</h2>
 * <b>熔断是既定策略，不是每次都要喊</b>：一次熔断只打 <b>1 条 ERROR</b>
 * （"明确日志"），此后再失败的调用既不再尝试原生、也不再打日志，只增加
 * {@link #suppressedAfterTrip()} 计数。ERROR 噪声是运维事故，不是功能。
 *
 * <h2>与确定性的关系</h2>
 * 本类不参与任何数值计算、不写回 ABI、不改变任何返回值（
 * {@link NativeCallGuard#call} 的返回值只可能是"原生返回的 rc"或
 * "不再尝试原生"这个既定语义码）。因此它不破坏"同一份输入 ⇒ 同一份输出"。
 * 唯一的进程状态变化是"熔断后不再尝试原生" —— 这是<b>运维安全阀</b>，
 * 由"连续 N 次失败"这个确定性条件触发，与 wall-clock / 线程调度无关。
 */
public final class CircuitBreaker {

    /** 阈值系统属性；{@code <=0} = 关闭熔断。 */
    public static final String PROP_THRESHOLD = "cava.native.breaker.threshold";

    /** 默认阈值：见类注释"为什么是 5"。 */
    public static final int DEFAULT_THRESHOLD = 5;

    /** 熔断发生时的回调（由 {@code CavaNative} 用来翻转状态机）。 */
    @FunctionalInterface
    public interface TripListener {
        void onTrip(String symbol, int consecutiveFailures, int lastCode);
    }

    private static final Logger LOG = LoggerFactory.getLogger("cava/native");

    private final int threshold;
    private final ConcurrentHashMap<String, AtomicInteger> consecutive = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> failuresPerSymbol = new ConcurrentHashMap<>();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong softFailures = new AtomicLong();
    private final AtomicLong trips = new AtomicLong();
    private final AtomicLong errorEmissions = new AtomicLong();
    private final AtomicLong suppressedAfterTrip = new AtomicLong();

    private volatile Consumer<String> errorSink = CircuitBreaker::logError;
    private volatile TripListener listener = (symbol, n, rc) -> { };

    private volatile boolean tripped;
    private volatile String tripSymbol = "";
    private volatile int tripConsecutive;
    private volatile int tripCode;

    public CircuitBreaker() {
        this(readThreshold());
    }

    public CircuitBreaker(int threshold) {
        this.threshold = threshold;
    }

    /** {@code cava.native.breaker.threshold} 的解析（非法值回落到默认值并说明）。 */
    public static int readThreshold() {
        String raw = System.getProperty(PROP_THRESHOLD, "").trim();
        if (raw.isEmpty()) {
            return DEFAULT_THRESHOLD;
        }
        try {
            int v = Integer.parseInt(raw);
            if (v > 1_000_000) {
                return 1_000_000; // 上限：防止把 int 撑爆后语义诡异
            }
            return v;
        } catch (NumberFormatException e) {
            LOG.warn("[cava/native] -D{}=\"{}\" 不是整数，熔断阈值回落到 {}",
                    PROP_THRESHOLD, raw, DEFAULT_THRESHOLD);
            return DEFAULT_THRESHOLD;
        }
    }

    private static void logError(String msg) {
        LOG.error(msg);
    }

    // ------------------------------------------------------------------
    // 状态查询（"可查询计数"）
    // ------------------------------------------------------------------

    public int threshold() {
        return threshold;
    }

    /** 熔断是否已开启（false 表示"根本不允许熔断"）。 */
    public boolean enabled() {
        return threshold > 0;
    }

    public boolean tripped() {
        return tripped;
    }

    public String tripSymbol() {
        return tripSymbol;
    }

    public int tripConsecutive() {
        return tripConsecutive;
    }

    public int tripCode() {
        return tripCode;
    }

    /** 该入口当前连续失败次数（成功即清零）。 */
    public int consecutiveFailures(String symbol) {
        AtomicInteger c = consecutive.get(symbol);
        return c == null ? 0 : c.get();
    }

    /** 该入口累计失败次数（不清零）。 */
    public long totalFailures(String symbol) {
        AtomicLong c = failuresPerSymbol.get(symbol);
        return c == null ? 0 : c.get();
    }

    /** 全部入口的累计"硬失败"次数（只有这些才会熔断）。 */
    public long failures() {
        return failures.get();
    }

    /**
     * 累计"软失败"次数：原生<b>按 ABI 明文规定</b>拒绝了一次输入
     * （{@code CAVA_ERR_ARG} / {@code CAVA_ERR_NULL} / {@code CAVA_ERR_UNIMPLEMENTED}）。
     *
     * <p><b>这些不熔断</b>，理由见 {@link NativeCallGuard#isTripWorthy(int)} ——
     * 它们是"合法回退"，不是"原生坏了"。但计数保留，运维能看到。
     */
    public long softFailures() {
        return softFailures.get();
    }

    public long trips() {
        return trips.get();
    }

    /** 本实例一共输出了多少条 ERROR（熔断日志）。正常应为 0 或 1。 */
    public long errorEmissions() {
        return errorEmissions.get();
    }

    /** 熔断之后仍然进来的失败次数（它们<b>没有</b>产生日志，只计数）。 */
    public long suppressedAfterTrip() {
        return suppressedAfterTrip.get();
    }

    // ------------------------------------------------------------------
    // 注入点（测试 / CavaNative）
    // ------------------------------------------------------------------

    public void setErrorSink(Consumer<String> sink) {
        this.errorSink = (sink == null) ? CircuitBreaker::logError : sink;
    }

    public void setTripListener(TripListener l) {
        this.listener = (l == null) ? (symbol, n, rc) -> { } : l;
    }

    /** 只给单测/诊断用：把熔断状态与计数清空（生产路径没有"重新打开"这一说）。 */
    public void reset() {
        consecutive.clear();
        failuresPerSymbol.clear();
        failures.set(0);
        trips.set(0);
        errorEmissions.set(0);
        suppressedAfterTrip.set(0);
        softFailures.set(0);
        tripped = false;
        tripSymbol = "";
        tripConsecutive = 0;
        tripCode = 0;
    }

    // ------------------------------------------------------------------
    // 记账
    // ------------------------------------------------------------------

    /**
     * 记一次失败。
     *
     * @return true = <b>本次调用触发了熔断</b>（调用方据此翻转状态机、打那唯一一条 ERROR）
     */
    public boolean recordFailure(String symbol, int rc) {
        failures.incrementAndGet();
        failuresPerSymbol.computeIfAbsent(symbol, k -> new AtomicLong()).incrementAndGet();
        if (tripped) {
            suppressedAfterTrip.incrementAndGet();
            return false;
        }
        int n = consecutive.computeIfAbsent(symbol, k -> new AtomicInteger()).incrementAndGet();
        if (!enabled() || n < threshold) {
            return false;
        }
        return trip(symbol, n, rc);
    }

    /**
     * 记一次<b>软失败</b>（原生按契约拒绝输入）：只计数，<b>不增加硬失败链</b>，
     * 并且把该入口的硬失败链清零 —— 能按契约回错误码说明这个入口是活的、参数校验在工作。
     */
    public void recordSoftFailure(String symbol, int rc) {
        softFailures.incrementAndGet();
        recordSuccess(symbol);
    }

    /** 记一次成功：该入口的连续计数清零（累计计数不动）。 */
    public void recordSuccess(String symbol) {
        AtomicInteger c = consecutive.get(symbol);
        if (c != null && c.get() != 0) {
            c.set(0);
        }
    }

    private synchronized boolean trip(String symbol, int n, int rc) {
        if (tripped) {
            return false;
        }
        tripped = true;
        tripSymbol = symbol;
        tripConsecutive = n;
        tripCode = rc;
        trips.incrementAndGet();
        emit("熔断：" + symbol + " 连续 " + n + " 次失败（最后一次 rc=" + rc + "）"
                + " ⇒ 自动全局关闭 native（本进程内不再尝试任何原生调用，"
                + "所有子系统按既有语义整体回退纯 Java）。"
                + "阈值 -D" + PROP_THRESHOLD + "=" + threshold
                + "；计数见 CavaNative.hardeningReport()。");
        try {
            listener.onTrip(symbol, n, rc);
        } catch (Throwable t) {
            // 观测层绝不允许反过来影响调用结果
            emit("熔断回调抛出异常（已忽略，不影响回退语义）: " + t);
        }
        return true;
    }

    private void emit(String msg) {
        errorEmissions.incrementAndGet();
        try {
            errorSink.accept(msg);
        } catch (Throwable ignored) {
            // 日志本身失败也不能影响调用结果
        }
    }

    /** 单行报告（可 grep；banner / 运维命令共用）。 */
    public String report() {
        return "熔断[enabled=" + enabled() + " threshold=" + threshold + " tripped=" + tripped
                + (tripped ? " symbol=" + tripSymbol + " consecutive=" + tripConsecutive + " lastRc=" + tripCode : "")
                + " failures=" + failures.get() + " softFailures=" + softFailures.get() + " trips=" + trips.get()
                + " errorEmits=" + errorEmissions.get() + " suppressedAfterTrip=" + suppressedAfterTrip.get()
                + (failures.get() == 0 ? "" : " perSymbol=" + perSymbolSummary()) + "]";
    }

    private String perSymbolSummary() {
        Map<String, Long> m = new TreeMap<>();
        failuresPerSymbol.forEach((k, v) -> m.put(k, v.get()));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}
