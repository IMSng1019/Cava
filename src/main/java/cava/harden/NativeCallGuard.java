package cava.harden;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原生调用的唯一包装层：把 <b>熔断</b>（{@link CircuitBreaker}）与 <b>看门狗</b>
 * （{@link CallWatchdog}）套在既有 FFM 调用之外。
 *
 * <p>语义（顺序即纪律）：
 * <ol>
 *   <li><b>已熔断</b> ⇒ 直接返回 {@link #ERR_NATIVE_UNAVAILABLE}，<b>一次原生都不尝试</b>
 *       （{@link #attempts()} 不增长）——这是"后续调用不再尝试原生"的可证伪证据。</li>
 *   <li>否则执行原生调用，<b>原样返回它的返回值</b>（看门狗只观测）。</li>
 *   <li>返回值分两级记账（<b>不是"负数就算失败"</b>，见 {@link #isTripWorthy(int)}）：
 *       <b>硬失败</b>（{-1,-2,-5,-6}）⇒ 连续计数 +1，可能触发熔断；
 *       <b>软失败</b>（{-3,-4,-7}，ABI 明文规定的合法拒绝）⇒ 只加 {@code softFailures} 并把连续计数清零；
 *       返回值 &ge; 0 ⇒ 同样清零该入口的连续计数。
 *       {@code 0} 是合法结果（例如 {@code cava_pathfind} 的"无路径"），<b>不是失败</b>。</li>
 *   <li>调用抛 {@code Throwable} ⇒ 返回 {@link #ERR_CALL_FAILED}，记一次失败，
 *       并且<b>每个入口只打一条 ERROR</b>（与 {@code CavaNative} 既有行为一致）。</li>
 * </ol>
 *
 * <p><b>不改变行为</b>：除了"熔断后不再尝试原生"这一个既定策略（连带把状态机翻到
 * {@code DISABLED_BY_BREAKER}），返回值恒等于原生返回值。记账/计时/日志各自自带 try-catch，
 * 任何观测层异常都不会逃逸到调用方，也不会改变返回的 rc。
 *
 * <p>本类<b>不引用 {@code java.lang.foreign}</b>（契约要求 FFM 只出现在 {@code cava.ffm}），
 * 因此可以脱离 MC 与原生库单独做单测。
 */
public final class NativeCallGuard {

    /** 原生不可用（未打开 / 已熔断 / 已关闭）时的统一回退信号。 */
    public static final int ERR_NATIVE_UNAVAILABLE = -100;

    /** 原生调用抛异常（已记录一次日志）时的回退信号。 */
    public static final int ERR_CALL_FAILED = -101;

    /**
     * <b>会熔断</b>的原生错误码（数值来自 {@code native/include/cava_abi.h}，冻结）：
     * {@code CAVA_ERR_ABI_VERSION(-1) / CAVA_ERR_LAYOUT(-2) / CAVA_ERR_OOM(-5) / CAVA_ERR_INTERNAL(-6)}。
     *
     * <p>它们的共同点：<b>不是"你参数不对"，是"原生自己坏了"</b> —— ABI 漂移、布局漂移、内存耗尽、
     * 内部状态损坏。连续出现即系统性故障，继续调用只是继续付 FFM 边界成本。
     *
     * <p>{@code cava.ffm.CavaNativeHardeningTest} 会拿这四个值与 {@code CavaLayouts.CAVA_ERR_*} 对拍，
     * 所以这里就算写错也会红，不会静默漂移。
     */
    public static final int CODE_ABI_VERSION = -1;
    public static final int CODE_LAYOUT = -2;
    public static final int CODE_OOM = -5;
    public static final int CODE_INTERNAL = -6;

    /**
     * 这个返回码算不算"失败到要熔断"？
     *
     * <h2>为什么 {@code CAVA_ERR_ARG} / {@code CAVA_ERR_NULL} / {@code CAVA_ERR_UNIMPLEMENTED} 不算</h2>
     * <b>这是实测逼出来的修正，不是拍脑袋</b>（2026-09-23，P4-A）：
     * <ol>
     *   <li>{@code CAVA_ERR_ARG} 是 ABI <b>明文规定</b>的"合法拒绝"：头文件里每个入口都写着
     *       "任一非法 ⇒ 对应错误码"。P1 的 {@code cava_pathfind} 还用它表示
     *       <b>"状态表/区域/档案还没推"</b> —— 也就是<b>启动预热期</b>的正常回答。
     *       把它当故障 ⇒ 预热期连续 5 次就把整个进程的原生路径永久关掉（假熔断）。</li>
     *   <li>{@code CAVA_ERR_NULL} 是"句柄形状不对 / 已释放 / 空指针"：交付物里
     *       {@code probePathfind(伪造句柄)} 就是这一类，属于<b>调用方</b>的输入问题。</li>
     *   <li>{@code CAVA_ERR_UNIMPLEMENTED} 是"这一版还没实现"的稳定回答，不是损坏。</li>
     * </ol>
     * 第一次实现把"任何负返回码"都当失败，结果<b>被既有的 {@code PathfindAbiTest} 当场打红</b>：
     * 它故意连喂 5 个非法 profile（ABI 契约要求返回 {@code CAVA_ERR_ARG}），第 5 次就被熔断成
     * {@code -100}，测试报 {@code expected: <-4> but was: <-100>}。
     * 既有的 ABI 测试是对的，错的是熔断策略。
     */
    public static boolean isTripWorthy(int rc) {
        return rc == CODE_ABI_VERSION || rc == CODE_LAYOUT || rc == CODE_OOM || rc == CODE_INTERNAL;
    }

    private static final Logger LOG = LoggerFactory.getLogger("cava/native");

    private final CircuitBreaker breaker;
    private final CallWatchdog watchdog;
    private final Set<String> reportedCallFailures = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** 见过的入口名（自维护登记表：报告用，避免在别处再抄一份符号名）。 */
    private final Set<String> symbolsSeen = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong unavailableCalls = new AtomicLong();
    private final AtomicLong lastNanos = new AtomicLong(-1);
    private final AtomicLong callFailureEmissions = new AtomicLong();

    private volatile Consumer<String> errorSink = NativeCallGuard::logError;

    public NativeCallGuard() {
        this(new CircuitBreaker(), new CallWatchdog());
    }

    public NativeCallGuard(CircuitBreaker breaker, CallWatchdog watchdog) {
        this.breaker = (breaker == null) ? new CircuitBreaker() : breaker;
        this.watchdog = (watchdog == null) ? new CallWatchdog() : watchdog;
    }

    private static void logError(String msg) {
        LOG.error(msg);
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    public CallWatchdog watchdog() {
        return watchdog;
    }

    /** 诊断类 ERROR（"某入口调用抛异常"）的输出口；与熔断那条 ERROR 共用一个 sink。 */
    public void setErrorSink(Consumer<String> sink) {
        this.errorSink = (sink == null) ? NativeCallGuard::logError : sink;
        breaker.setErrorSink(this.errorSink);
    }

    /** 真正落到原生入口上的调用次数（熔断之后不再增长）。 */
    public long attempts() {
        return attempts.get();
    }

    /**
     * "原生回退计数"：因为原生不可用（未打开 / 已熔断 / 已关闭）而
     * <b>一次原生都没尝试</b>的调用次数。只计数，不打日志。
     *
     * <p>这是 P4 上线验收要看的那个数（"连续运行 7 天 native 回退计数为 0"）。
     * 正常运行时它应当恒为 0 —— 一旦非 0，说明请求被送到了原生不可用的进程上。
     */
    public long unavailableCalls() {
        return unavailableCalls.get();
    }

    /** 供 {@code CavaNative} 的包装方法在"原生不可用"分支调用（同一计数、同一语义）。 */
    public int unavailable() {
        unavailableCalls.incrementAndGet();
        return ERR_NATIVE_UNAVAILABLE;
    }

    /** 上一次原生调用的耗时（ns；从未测到为 -1）。 */
    public long lastNanos() {
        return lastNanos.get();
    }

    /** "调用抛异常"类 ERROR 的输出条数（每个入口最多 1）。 */
    public long callFailureEmissions() {
        return callFailureEmissions.get();
    }

    /**
     * 执行一次原生调用。
     *
     * @param symbol  原生入口名（{@code cava_pathfind} 等），用于分入口计数
     * @param attempt 真正的原生调用（返回值即原生返回值）
     * @return 原生的返回值；或 {@link #ERR_NATIVE_UNAVAILABLE} / {@link #ERR_CALL_FAILED}
     */
    public int call(String symbol, IntSupplier attempt) {
        symbolsSeen.add(symbol);
        if (breaker.tripped()) {
            return unavailable();
        }
        final long t0 = watchdog.begin();
        final int rc;
        try {
            attempts.incrementAndGet();
            rc = attempt.getAsInt();
        } catch (Throwable t) {
            watchdog.abort(t0);
            noteThrowable(symbol, t);
            return ERR_CALL_FAILED;
        }
        long dt = watchdog.finish(symbol, t0);
        if (dt >= 0) {
            lastNanos.set(dt);
        }
        noteResult(symbol, rc);
        return rc;
    }

    private void noteResult(String symbol, int rc) {
        try {
            if (rc >= 0) {
                breaker.recordSuccess(symbol);
            } else if (isTripWorthy(rc)) {
                breaker.recordFailure(symbol, rc);
            } else {
                breaker.recordSoftFailure(symbol, rc);   // 契约内的合法拒绝：计数但不熔断
            }
        } catch (Throwable ignored) {
            // 记账绝不改变返回给调用方的 rc
        }
    }

    /** 原生调用抛异常：每个入口只报一次（与既有行为一致），但失败计数照记。 */
    public void noteThrowable(String symbol, Throwable t) {
        symbolsSeen.add(symbol);
        try {
            breaker.recordFailure(symbol, ERR_CALL_FAILED);
        } catch (Throwable ignored) {
            // 同上
        }
        if (reportedCallFailures.add(symbol)) {
            callFailureEmissions.incrementAndGet();
            try {
                errorSink.accept("[cava/native] " + symbol
                        + " 调用失败，该调用起回退纯 Java（同类错误只报一次）: " + t);
            } catch (Throwable ignored) {
                // 日志失败不影响结果
            }
        }
    }

    /** 单行报告（banner / 运维命令共用）。 */
    public String report() {
        return breaker.report() + " " + watchdog.report() + " attempts=" + attempts.get()
                + " unavailableCalls=" + unavailableCalls.get();
    }

    /** 分入口的连续失败计数快照（"可查询计数"）。 */
    public String consecutiveReport() {
        Map<String, Integer> m = new TreeMap<>();
        for (String s : symbolsSeen) {
            int c = breaker.consecutiveFailures(s);
            if (c > 0) {
                m.put(s, c);
            }
        }
        if (m.isEmpty()) {
            return "consecutiveFailures={}";
        }
        StringBuilder sb = new StringBuilder("consecutiveFailures={");
        boolean first = true;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}
