package cava.harden;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // ------------------------------------------------------------------
    // 线程归属检查（P4-C）：**只计数 + 首次打一条日志**，绝不改变行为
    // ------------------------------------------------------------------
    /** 第一次调用原生的那个线程 = owner。之后只读，不再变。 */
    private volatile Thread ownerThread = null;
    /** 从非 owner 线程打到原生入口的次数（P4 上线要看的第二个计数，第一个是 unavailableCalls）。 */
    private final AtomicLong offThreadCalls = new AtomicLong();
    /** 首次违规是否已经报告过（每个进程最多一条 WARN）。 */
    private final AtomicBoolean offThreadReported = new AtomicBoolean();
    /** 第一次违规的现场，写进日志用。 */
    private volatile String firstOffThread = "";
    /** 线程归属检查是否启用（默认开；{@code -Dcava.native.threadcheck=false} 关）。 */
    private final boolean threadCheck;

    private volatile Consumer<String> errorSink = NativeCallGuard::logError;

    public NativeCallGuard() {
        this(new CircuitBreaker(), new CallWatchdog());
    }

    public NativeCallGuard(CircuitBreaker breaker, CallWatchdog watchdog) {
        this.breaker = (breaker == null) ? new CircuitBreaker() : breaker;
        this.watchdog = (watchdog == null) ? new CallWatchdog() : watchdog;
        this.threadCheck = !"false".equalsIgnoreCase(System.getProperty(PROP_THREAD_CHECK, "true"));
    }

    /** 系统属性：{@code false} = 关闭线程归属检查（只关检查，不关计数以外的东西）。 */
    public static final String PROP_THREAD_CHECK = "cava.native.threadcheck";

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
        noteThreadOwnership(symbol);
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

    /**
     * 线程归属检查：**第一条原生调用所在的线程就是 owner**，之后任何别的线程调用只计数。
     *
     * <p><b>为什么是"只计数 + 首次一条日志"而不是断言或抛异常</b>：本项目的铁律是
     * 语义必须与"native 关闭"时逐 tick 一致，而这个检查是在热路径上。抛异常或强制回退都会
     * <b>改变行为</b>（把一次可用的原生调用变成一次纯 Java 回退），那比"多线程调用"本身更危险。
     * 所以这里只做两件零语义成本的事：{@code offThreadCalls++} 与首次 WARN。
     *
     * <p><b>为什么必须有这个检查</b>：{@code docs/CAVA-工程接口契约.md} 与
     * {@code native/include/cava_abi.h} 都<b>没有写过线程模型</b>。实测（P4-C，8 线程 × 4000 轮）
     * 说明原生库<b>不是数据损坏</b>的（无段错误 / 无未定义返回 / 无越界写），但确实有
     * 0.38% 的 {@code cava_resolve_move} 在并发替换形状表期间返回 {@code CAVA_ERR_ARG}。
     * 结论写在 {@code docs/CAVA-concurrency-notes.md}：invariant 是"单线程调用"，本计数就是它的
     * 运行期证据（{@code offThreadCalls} 必须恒为 0）。
     *
     * <p>成本：一次 {@code Thread.currentThread()} 与一次 {@code ==} 比较（owner 命中路径），
     * 不分配、不加锁、不写日志。{@code -Dcava.native.threadcheck=false} 可整体关闭。
     */
    private void noteThreadOwnership(String symbol) {
        if (!threadCheck) {
            return;
        }
        try {
            final Thread cur = Thread.currentThread();
            final Thread owner = ownerThread;
            if (owner == null) {
                // 首个调用者认领 owner；两者同时竞争时只有先 set 的那次生效
                if (ownerThread == null) {
                    ownerThread = cur;
                    return;
                }
                if (ownerThread == cur) {
                    return;
                }
            } else if (owner == cur) {
                return;
            }
            offThreadCalls.incrementAndGet();
            if (offThreadReported.compareAndSet(false, true)) {
                firstOffThread = "owner=" + describe(ownerThread) + " caller=" + describe(cur)
                        + " symbol=" + symbol;
                try {
                    LOG.warn("[cava/native] 原生调用来自非 owner 线程（只计数，不改变行为）："
                            + firstOffThread
                            + " —— 契约要求原生入口只从 owner 线程调用；实测原生库不是内存不安全的，"
                            + "但并发替换形状/状态表期间会有少量 cava_resolve_move 返回 CAVA_ERR_ARG。"
                            + "详见 docs/CAVA-concurrency-notes.md。");
                } catch (Throwable ignored) {
                    // 日志失败绝不影响返回值
                }
            }
        } catch (Throwable ignored) {
            // 归属检查自己出错也只当"没检查"：绝不改变调用结果
        }
    }

    private static String describe(Thread t) {
        if (t == null) {
            return "(none)";
        }
        return t.getName() + "#" + t.threadId();
    }

    /** owner 线程名（还没有任何原生调用时为 null）。 */
    public String ownerThreadName() {
        Thread t = ownerThread;
        return (t == null) ? null : describe(t);
    }

    /** 从非 owner 线程打到原生入口的次数 —— 上线判据是它恒为 0。 */
    public long offThreadCalls() {
        return offThreadCalls.get();
    }

    /** 首次越线现场（一行；没有则空串）。 */
    public String firstOffThread() {
        return firstOffThread;
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
                + " unavailableCalls=" + unavailableCalls.get()
                + " ownerThread=" + (ownerThreadName() == null ? "(none)" : ownerThreadName())
                + " offThreadCalls=" + offThreadCalls.get();
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
