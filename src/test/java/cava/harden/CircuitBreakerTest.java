package cava.harden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 熔断语义单测（<b>不依赖原生库、不依赖 MC</b>）。
 *
 * <p>验收对应关系（任务 P4-A 第 1 项）：
 * <ul>
 *   <li>喂 N 次必然失败 ⇒ 真的熔断（{@link #tripsAfterNConsecutiveFailures()}）；</li>
 *   <li>熔断后<b>不再尝试原生</b> ⇒ 用"调用计数器"证明 attempt 根本没被执行
 *       （{@link #afterTripNativeIsNeverAttemptedAgain()}）；</li>
 *   <li>不刷 ERROR 噪声 ⇒ 记录 sink 的条数恒为 1
 *       （{@link #tripEmitsExactlyOneErrorAndNeverSpams()}）；</li>
 *   <li>计数可查询（{@link #countersAreQueryable()}）。</li>
 * </ul>
 */
class CircuitBreakerTest {

    /** 记录"日志条数"的 sink —— 比 grep 日志更强：它数的是**产生**了几条。 */
    private static final class RecordingSink implements java.util.function.Consumer<String> {
        final List<String> messages = new ArrayList<>();

        @Override
        public synchronized void accept(String s) {
            messages.add(s);
        }

        synchronized int count() {
            return messages.size();
        }
    }

    @Test
    void tripsAfterNConsecutiveFailures() {
        RecordingSink sink = new RecordingSink();
        CircuitBreaker b = new CircuitBreaker(3);
        b.setErrorSink(sink);

        assertFalse(b.recordFailure("cava_pathfind", NativeCallGuard.CODE_INTERNAL), "第 1 次失败不该熔断");
        assertFalse(b.recordFailure("cava_pathfind", NativeCallGuard.CODE_INTERNAL), "第 2 次失败不该熔断");
        assertTrue(b.recordFailure("cava_pathfind", NativeCallGuard.CODE_INTERNAL), "第 3 次连续失败必须熔断");
        assertTrue(b.tripped());
        assertEquals("cava_pathfind", b.tripSymbol());
        assertEquals(3, b.tripConsecutive());
        assertEquals(NativeCallGuard.CODE_INTERNAL, b.tripCode());
        assertEquals(1, b.trips());
        assertEquals(3, b.consecutiveFailures("cava_pathfind"), "熔断时计数停在阈值上");
        // 熔断之后的失败走 suppressed 分支：不再增长、不再打日志
        for (int i = 0; i < 100; i++) {
            b.recordFailure("cava_pathfind", NativeCallGuard.CODE_INTERNAL);
        }
        assertEquals(3, b.consecutiveFailures("cava_pathfind"), "熔断后连续计数不再增长");
        assertEquals(100, b.suppressedAfterTrip());
        assertEquals(1, sink.count(), "熔断只打 1 条 ERROR");
    }

    @Test
    void successResetsTheConsecutiveCounter() {
        CircuitBreaker b = new CircuitBreaker(3);
        b.setErrorSink(s -> { });
        assertFalse(b.recordFailure("s", NativeCallGuard.CODE_OOM));
        assertFalse(b.recordFailure("s", NativeCallGuard.CODE_OOM));
        b.recordSuccess("s");
        assertEquals(0, b.consecutiveFailures("s"));
        assertFalse(b.recordFailure("s", NativeCallGuard.CODE_OOM), "清零后要重新数满 3 次");
        assertFalse(b.recordFailure("s", NativeCallGuard.CODE_OOM));
        assertTrue(b.recordFailure("s", NativeCallGuard.CODE_OOM));
        assertEquals(5, b.failures(), "累计失败次数不受清零影响");
    }

    @Test
    void countersArePerEntry() {
        CircuitBreaker b = new CircuitBreaker(3);
        b.setErrorSink(s -> { });
        b.recordFailure("cava_pathfind", NativeCallGuard.CODE_INTERNAL);
        b.recordFailure("cava_pathfind", NativeCallGuard.CODE_INTERNAL);
        b.recordFailure("cava_resolve_move", NativeCallGuard.CODE_INTERNAL);
        assertFalse(b.tripped(), "两个入口各自 2/1 次，都不该熔断");
        b.recordFailure("cava_pathfind", NativeCallGuard.CODE_INTERNAL);
        assertTrue(b.tripped(), "cava_pathfind 满 3 次 ⇒ 熔断");
        assertEquals("cava_pathfind", b.tripSymbol());
    }

    @Test
    void afterTripNativeIsNeverAttemptedAgain() {
        CircuitBreaker b = new CircuitBreaker(2);
        RecordingSink sink = new RecordingSink();
        b.setErrorSink(sink);
        b.setTripListener((sym, n, rc) -> { });
        NativeCallGuard guard = new NativeCallGuard(b, new CallWatchdog());
        guard.setErrorSink(sink);

        AtomicInteger invoked = new AtomicInteger();
        java.util.function.IntSupplier failing = () -> {
            invoked.incrementAndGet();
            return NativeCallGuard.CODE_LAYOUT;
        };
        assertEquals(NativeCallGuard.CODE_LAYOUT, guard.call("cava_pathfind", failing));
        assertEquals(NativeCallGuard.CODE_LAYOUT, guard.call("cava_pathfind", failing));
        assertEquals(2, invoked.get(), "熔断前每次都真的执行了");
        assertTrue(b.tripped());

        for (int i = 0; i < 1000; i++) {
            assertEquals(NativeCallGuard.ERR_NATIVE_UNAVAILABLE, guard.call("cava_pathfind", failing),
                    "熔断后必须返回 ERR_NATIVE_UNAVAILABLE");
        }
        assertEquals(2, invoked.get(), "熔断后一次原生都不许再尝试");
        assertEquals(2, guard.attempts(), "attempts 计数证明没有落到原生");
        assertEquals(1000, guard.unavailableCalls(), "熔断后的调用只计数（原生回退计数），不打日志");
        assertEquals(1, sink.count(), "整条用例只允许 1 条 ERROR（就是熔断那一条）");
        assertEquals(1, b.errorEmissions());
    }

    @Test
    void tripEmitsExactlyOneErrorAndNeverSpams() {
        RecordingSink sink = new RecordingSink();
        CircuitBreaker b = new CircuitBreaker(2);
        b.setErrorSink(sink);
        NativeCallGuard guard = new NativeCallGuard(b, new CallWatchdog());
        guard.setErrorSink(sink);

        for (int i = 0; i < 10_000; i++) {
            guard.call("cava_pathfind", () -> NativeCallGuard.CODE_INTERNAL);
        }
        assertEquals(1, sink.count(), "一万次失败只允许 1 条 ERROR（熔断是既定策略，不是每次都要喊）");
        assertTrue(sink.messages.get(0).contains("熔断"), "那条 ERROR 必须说清发生了什么: " + sink.messages.get(0));
        assertEquals(1, b.errorEmissions());
    }

    @Test
    void zeroThresholdDisablesTheBreaker() {
        CircuitBreaker b = new CircuitBreaker(0);
        b.setErrorSink(s -> { });
        for (int i = 0; i < 1000; i++) {
            b.recordFailure("s", NativeCallGuard.CODE_INTERNAL);
        }
        assertFalse(b.enabled());
        assertFalse(b.tripped(), "阈值 <=0 = 关闭熔断（运维逃生口）");
    }

    @Test
    void thresholdComesFromSystemProperty() {
        String saved = System.getProperty(CircuitBreaker.PROP_THRESHOLD);
        try {
            System.setProperty(CircuitBreaker.PROP_THRESHOLD, "7");
            assertEquals(7, new CircuitBreaker().threshold());
            System.clearProperty(CircuitBreaker.PROP_THRESHOLD);
            assertEquals(CircuitBreaker.DEFAULT_THRESHOLD, new CircuitBreaker().threshold(),
                    "默认阈值必须是 5（理由见 CircuitBreaker 类注释）");
            System.setProperty(CircuitBreaker.PROP_THRESHOLD, "not-a-number");
            assertEquals(CircuitBreaker.DEFAULT_THRESHOLD, new CircuitBreaker().threshold(),
                    "非法值回落到默认值，绝不抛异常");
        } finally {
            if (saved == null) {
                System.clearProperty(CircuitBreaker.PROP_THRESHOLD);
            } else {
                System.setProperty(CircuitBreaker.PROP_THRESHOLD, saved);
            }
        }
    }

    @Test
    void zeroReturnCodeIsSuccessNotFailure() {
        CircuitBreaker b = new CircuitBreaker(2);
        b.setErrorSink(s -> { });
        NativeCallGuard guard = new NativeCallGuard(b, new CallWatchdog());
        guard.setErrorSink(s -> { });
        // cava_pathfind 的 0 = 无路径（合法结果），绝不能被当成失败
        assertEquals(0, guard.call("cava_pathfind", () -> 0));
        assertEquals(0, guard.call("cava_pathfind", () -> 0));
        assertFalse(b.tripped());
        assertEquals(0, b.failures());
        assertTrue(guard.call("cava_pathfind", () -> 3) > 0);
        assertFalse(b.tripped());
    }

    @Test
    void throwingCallIsReportedOncePerEntry() {
        RecordingSink sink = new RecordingSink();
        CircuitBreaker b = new CircuitBreaker(1000);
        b.setErrorSink(sink);
        NativeCallGuard guard = new NativeCallGuard(b, new CallWatchdog());
        guard.setErrorSink(sink);
        for (int i = 0; i < 50; i++) {
            assertEquals(NativeCallGuard.ERR_CALL_FAILED,
                    guard.call("cava_pathfind", () -> {
                        throw new IllegalStateException("boom");
                    }));
        }
        assertEquals(1, sink.count(), "同一入口的调用异常只报一次（既有行为，不许回归）");
        assertEquals(1, guard.callFailureEmissions());
        assertEquals(50, b.totalFailures("cava_pathfind"), "但失败计数照记");
    }

    /**
     * <b>这条是本轮最重要的一条回归</b>：ABI 明文规定的"参数拒绝"不许熔断。
     *
     * <p>第一版策略把任何负返回码都当失败，结果被既有的 {@code PathfindAbiTest} 当场打红 ——
     * 它故意连喂 5 个非法 profile（契约要求返回 {@code CAVA_ERR_ARG}），第 5 次就被熔断成 -100。
     * 而且 {@code cava_pathfind} 在"状态表/区域/档案还没推"时也返回 {@code CAVA_ERR_ARG}，
     * 那是<b>启动预热期</b>的正常回答 —— 按旧策略会在预热期把整个进程的原生路径永久关掉。
     */
    @Test
    void contractRejectionsAreSoftAndNeverTrip() {
        RecordingSink sink = new RecordingSink();
        CircuitBreaker b = new CircuitBreaker(5);
        b.setErrorSink(sink);
        NativeCallGuard guard = new NativeCallGuard(b, new CallWatchdog(0, System::nanoTime));
        guard.setErrorSink(sink);

        final int[] soft = { CavaNativeErr.ARG, CavaNativeErr.NULL, CavaNativeErr.UNIMPLEMENTED };
        for (int i = 0; i < 200; i++) {
            for (int rc : soft) {
                assertEquals(rc, guard.call("cava_pathfind", () -> rc), "软失败必须原样返回");
            }
        }
        assertFalse(b.tripped(), "600 次契约内的拒绝绝不许熔断");
        assertEquals(600, b.softFailures());
        assertEquals(0, b.failures());
        assertEquals(0, sink.count(), "软失败不打 ERROR（它就是正常回退路径）");
        assertTrue(guard.report().contains("softFailures=600"), guard.report());
    }

    @Test
    void softFailureBreaksTheHardChain() {
        RecordingSink sink = new RecordingSink();
        CircuitBreaker b = new CircuitBreaker(3);
        b.setErrorSink(sink);
        NativeCallGuard guard = new NativeCallGuard(b, new CallWatchdog(0, System::nanoTime));
        guard.setErrorSink(sink);
        // 硬失败 2 次 + 一次契约拒绝 + 硬失败 2 次 ⇒ 中间那次证明"入口是活的"，链断掉，不该熔断
        guard.call("s", () -> NativeCallGuard.CODE_INTERNAL);
        guard.call("s", () -> NativeCallGuard.CODE_INTERNAL);
        guard.call("s", () -> CavaNativeErr.ARG);
        guard.call("s", () -> NativeCallGuard.CODE_INTERNAL);
        guard.call("s", () -> NativeCallGuard.CODE_INTERNAL);
        assertFalse(b.tripped(), "硬失败链被契约拒绝打断 ⇒ 重新计数");
        guard.call("s", () -> NativeCallGuard.CODE_INTERNAL);
        assertTrue(b.tripped(), "连续第 3 次硬失败 ⇒ 熔断");
    }

    @Test
    void onlyTheFourBrokenCodesAreTripWorthy() {
        assertEquals(true, NativeCallGuard.isTripWorthy(-1));
        assertEquals(true, NativeCallGuard.isTripWorthy(-2));
        assertEquals(false, NativeCallGuard.isTripWorthy(-3), "CAVA_ERR_NULL 是调用方输入问题");
        assertEquals(false, NativeCallGuard.isTripWorthy(-4), "CAVA_ERR_ARG 是 ABI 明文规定的合法拒绝");
        assertEquals(true, NativeCallGuard.isTripWorthy(-5));
        assertEquals(true, NativeCallGuard.isTripWorthy(-6));
        assertEquals(false, NativeCallGuard.isTripWorthy(-7), "UNIMPLEMENTED 是稳定回答，不是损坏");
        assertEquals(false, NativeCallGuard.isTripWorthy(-100), "Java 侧自有码不是原生错误");
        assertEquals(false, NativeCallGuard.isTripWorthy(0));
        assertEquals(false, NativeCallGuard.isTripWorthy(7));
    }

    /** 原生错误码的本地副本（值必须与 cava_abi.h 一致；漂移由 cava.ffm 的集成测试对拍抓）。 */
    private static final class CavaNativeErr {
        static final int ABI_VERSION = -1;
        static final int LAYOUT = -2;
        static final int NULL = -3;
        static final int ARG = -4;
        static final int OOM = -5;
        static final int INTERNAL = -6;
        static final int UNIMPLEMENTED = -7;
    }

    @Test
    void reportIsQueryable() {
        CircuitBreaker b = new CircuitBreaker(2);
        b.setErrorSink(s -> { });
        NativeCallGuard guard = new NativeCallGuard(b, new CallWatchdog(0, System::nanoTime));
        guard.setErrorSink(s -> { });
        assertEquals(NativeCallGuard.CODE_INTERNAL, guard.call("cava_pathfind", () -> NativeCallGuard.CODE_INTERNAL));
        String report = guard.report();
        assertTrue(report.contains("熔断["), report);
        assertTrue(report.contains("threshold=2"), report);
        assertTrue(report.contains("failures=1"), report);
        assertTrue(report.contains("softFailures=0"), report);
        assertTrue(guard.consecutiveReport().contains("cava_pathfind=1"), guard.consecutiveReport());
    }
}
