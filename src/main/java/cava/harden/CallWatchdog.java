package cava.harden;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 看门狗：单次原生调用耗时超过阈值 ⇒ <b>记录告警 + 计数</b>。
 *
 * <h2>铁的纪律：只观测、绝不改行为</h2>
 * <ul>
 *   <li>返回值 = 原生返回值，<b>逐位不变</b>；超时不会改写成错误码、不会触发回退、不会重试。</li>
 *   <li>不抛异常：连"取时钟"这个动作本身抛异常也照样吞掉（{@link #begin()} 返回哨兵值），
 *       只有 {@link #instrumentationFailures()} 计数变化。</li>
 *   <li>不做任何阻塞、不加锁、不分配大对象（热路径上只做 2 次 {@code nanoTime} + 1 次 map 查找）。</li>
 * </ul>
 *
 * <h2>{@code System.nanoTime} 与"确定性"的划界（契约 4.2 第 5 条）</h2>
 * 契约要求"不依赖 wall-clock / 线程调度"，指的是<b>不能让时间影响任何被观测的数值</b>。
 * 本类的时间量只出现在 Java 侧的诊断计数里，边界划在下面这条线上：
 * <pre>
 *   允许：Java 侧 (t1 - t0) 与阈值比较 -&gt; 只增加计数器、只打一行 WARN。
 *   禁止：把 t1-t0 写进任何 MemorySegment / 任何 ABI 结构体 / 任何回退判定 /
 *         任何参与游戏数值计算的表达式；原生侧永远看不到时间（CAVA_OPEN_FLAG_DETERMINISTIC 的另一半）。
 * </pre>
 * 换句话说：<b>删掉整个看门狗，同一份输入跑出来的世界哈希必须一模一样</b> ——
 * 单测 {@code CallWatchdogTest#watchdogDoesNotChangeResults} 就是对这条的断言
 * （把时钟换成"每次都超时 10 秒"的假时钟，返回码必须仍然相同）。
 *
 * <p>时钟时间差用 {@code long} 减法：{@code System.nanoTime} 的差值在
 * 292 年内不会溢出；万一出现负值（时钟回拨 / 假时钟），按"未超时"处理并计入
 * {@link #clockAnomalies()}，绝不因此改变行为。
 */
public final class CallWatchdog {

    /** 阈值系统属性（单位微秒，便于写 50ms = 50000；也接受小数）。 */
    public static final String PROP_THRESHOLD_MICROS = "cava.native.watchdog.micros";

    /** 默认阈值 50ms。 */
    public static final long DEFAULT_THRESHOLD_NANOS = 50_000_000L;

    /** 每个入口最多打多少条 WARN（之后只计数）——告警不是刷屏。 */
    public static final int MAX_WARN_PER_SYMBOL = 8;

    private static final Logger LOG = LoggerFactory.getLogger("cava/native");

    /** 未取到时钟时的哨兵：{@code finish()} 看到它就整段跳过。 */
    public static final long NO_CLOCK = Long.MIN_VALUE;

    private final long thresholdNanos;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, SymbolStats> stats = new ConcurrentHashMap<>();
    private final AtomicLong slowCalls = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();
    private final AtomicLong warnEmissions = new AtomicLong();
    private final AtomicLong instrumentationFailures = new AtomicLong();
    private final AtomicLong clockAnomalies = new AtomicLong();
    private final AtomicLong abortedCalls = new AtomicLong();
    private final AtomicLong lastNanos = new AtomicLong(-1);

    private volatile Consumer<String> warnSink = CallWatchdog::logWarn;

    private static final class SymbolStats {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong slow = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicInteger warned = new AtomicInteger();
    }

    public CallWatchdog() {
        this(readThresholdNanos(), System::nanoTime);
    }

    public CallWatchdog(long thresholdNanos, LongSupplier clock) {
        this.thresholdNanos = thresholdNanos;
        this.clock = clock;
    }

    /** 解析 {@code -Dcava.native.watchdog.micros}（<=0 = 关闭看门狗）。 */
    public static long readThresholdNanos() {
        String raw = System.getProperty(PROP_THRESHOLD_MICROS, "").trim();
        if (raw.isEmpty()) {
            return DEFAULT_THRESHOLD_NANOS;
        }
        try {
            double micros = Double.parseDouble(raw);
            if (!(micros > 0)) {
                return 0L;
            }
            double nanos = micros * 1000.0;
            return nanos >= 9.0e18 ? Long.MAX_VALUE : (long) nanos;
        } catch (NumberFormatException e) {
            LOG.warn("[cava/native] -D{}=\"{}\" 不是数字，看门狗阈值回落到 50ms", PROP_THRESHOLD_MICROS, raw);
            return DEFAULT_THRESHOLD_NANOS;
        }
    }

    private static void logWarn(String msg) {
        LOG.warn(msg);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public long thresholdNanos() {
        return thresholdNanos;
    }

    public boolean enabled() {
        return thresholdNanos > 0;
    }

    public long slowCalls() {
        return slowCalls.get();
    }

    public long maxNanos() {
        return maxNanos.get();
    }

    public long warnEmissions() {
        return warnEmissions.get();
    }

    /** 观测层自身出错的次数（取时钟失败 / 日志失败）。**与原生调用无关**。 */
    public long instrumentationFailures() {
        return instrumentationFailures.get();
    }

    public long clockAnomalies() {
        return clockAnomalies.get();
    }

    /**
     * 被 {@link #abort(long)} 中止计时的调用次数（原生调用抛异常）。
     *
     * <p><b>刻意与 {@link #instrumentationFailures()} 分开</b>：那条计数只表示"看门狗自己出错了"
     * （取时钟失败 / 记账抛异常）。原生调用抛异常是<b>被观测对象</b>出事，不是观测层出事 ——
     * 混在一起会让运维在真故障时误判成"看门狗坏了"，把注意力引到错误的组件上。
     */
    public long abortedCalls() {
        return abortedCalls.get();
    }

    /** 最近一次被测到的耗时（ns；从未测到为 -1）。<b>仅供展示</b>，不得用于任何行为判定。 */
    public long lastMeasured() {
        return lastNanos.get();
    }

    public long calls() {
        long n = 0;
        for (SymbolStats s : stats.values()) {
            n += s.calls.get();
        }
        return n;
    }

    public long slowCalls(String symbol) {
        SymbolStats s = stats.get(symbol);
        return s == null ? 0 : s.slow.get();
    }

    public void setWarnSink(Consumer<String> sink) {
        this.warnSink = (sink == null) ? CallWatchdog::logWarn : sink;
    }

    // ------------------------------------------------------------------
    // 采样
    // ------------------------------------------------------------------

    /** 开始计时；取时钟失败返回 {@link #NO_CLOCK}（调用方照常执行原生调用）。 */
    public long begin() {
        if (thresholdNanos <= 0) {
            return NO_CLOCK;
        }
        try {
            return clock.getAsLong();
        } catch (Throwable t) {
            instrumentationFailures.incrementAndGet();
            return NO_CLOCK;
        }
    }

    /**
     * 结束计时并记账。<b>绝不抛异常、绝不改变调用结果。</b>
     *
     * @param symbol 原生入口名（仅用于计数与日志）
     * @param t0     {@link #begin()} 的返回值
     * @return 本次耗时（ns）；未计时/时钟异常时为 -1。<b>调用方不得用它做任何行为判定。</b>
     */
    public long finish(String symbol, long t0) {
        if (thresholdNanos <= 0 || t0 == NO_CLOCK) {
            return -1;
        }
        try {
            final long t1 = clock.getAsLong();
            final long dt = t1 - t0;
            if (dt < 0) {
                clockAnomalies.incrementAndGet();
                return -1; // 时钟回拨/假时钟：按"未超时"处理，绝不改行为
            }
            lastNanos.set(dt);
            SymbolStats s = stats.computeIfAbsent(symbol, k -> new SymbolStats());
            s.calls.incrementAndGet();
            updateMax(s.maxNanos, dt);
            updateMax(maxNanos, dt);
            if (dt < thresholdNanos) {
                return dt;
            }
            slowCalls.incrementAndGet();
            s.slow.incrementAndGet();
            int n = s.warned.incrementAndGet();
            if (n > MAX_WARN_PER_SYMBOL) {
                return dt; // 只计数，不打日志
            }
            warnEmissions.incrementAndGet();
            warnSink.accept("[cava/native] 看门狗：" + symbol + " 单次调用 " + fmtMicros(dt)
                    + " 超过阈值 " + fmtMicros(thresholdNanos)
                    + "（第 " + n + " 次；只记录，不改变结果、不触发回退）");
            return dt;
        } catch (Throwable t) {
            instrumentationFailures.incrementAndGet();
            return -1;
        }
    }

    /**
     * 不计时的失败路径（原生调用抛异常时用）：只加 {@link #abortedCalls()}，
     * <b>不加</b> {@link #instrumentationFailures()}（那不是观测层出错），也不产生"慢调用"结论。
     */
    public void abort(long t0) {
        if (t0 != NO_CLOCK) {
            abortedCalls.incrementAndGet();
        }
    }

    private static void updateMax(AtomicLong holder, long v) {
        long cur = holder.get();
        while (v > cur && !holder.compareAndSet(cur, v)) {
            cur = holder.get();
        }
    }

    public static String fmtMicros(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.3fms", nanos / 1_000_000.0);
    }

    /** 单行报告。 */
    public String report() {
        return "看门狗[enabled=" + enabled() + " threshold=" + fmtMicros(thresholdNanos)
                + " calls=" + calls() + " slow=" + slowCalls.get() + " max=" + fmtMicros(maxNanos.get())
                + " warns=" + warnEmissions.get() + " clockAnomalies=" + clockAnomalies.get()
                + " abortedCalls=" + abortedCalls.get()
                + " instrumentationFailures=" + instrumentationFailures.get()
                + (slowCalls.get() == 0 ? "" : " perSymbol=" + perSymbolSummary()) + "]";
    }

    private String perSymbolSummary() {
        Map<String, String> m = new TreeMap<>();
        stats.forEach((k, v) -> {
            if (v.slow.get() > 0) {
                m.put(k, v.slow.get() + "/" + v.calls.get() + "@" + fmtMicros(v.maxNanos.get()));
            }
        });
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}
