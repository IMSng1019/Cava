package cava.harden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 看门狗单测：<b>只观测、绝不改行为</b>。
 *
 * <p>核心断言是 {@link #slowCallDoesNotChangeTheResultEvenWithAnInsaneClock()}：
 * 把时钟换成"每次都超时 10 秒"的假时钟，返回码必须与"从不超时"的对照组逐位相同。
 * 这就是"看门狗不影响确定性"的可证伪证据。
 */
class CallWatchdogTest {

    private static final long MS = 1_000_000L;

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

    /** 假时钟：第 1 次调用返回 base，之后每次返回 base + 每次递增的步长。 */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private final AtomicLong now = new AtomicLong();
        private final long step;

        FakeClock(long start, long step) {
            this.now.set(start);
            this.step = step;
        }

        @Override
        public long getAsLong() {
            return now.getAndAdd(step);
        }
    }

    @Test
    void slowCallIsCountedAndWarned() {
        RecordingSink sink = new RecordingSink();
        // 阈值 50ms；假时钟每次跳 100ms ⇒ 必然超阈值（超时才叫看门狗）
        CallWatchdog w = new CallWatchdog(50 * MS, new FakeClock(1_000_000_000L, 100 * MS));
        w.setWarnSink(sink);
        long t0 = w.begin();
        assertEquals(100 * MS, w.finish("cava_pathfind", t0), "耗时应当被如实测出");
        assertEquals(1, w.slowCalls());
        assertEquals(1, sink.count(), "超阈值必须记录一条告警");
        assertTrue(sink.messages.get(0).contains("看门狗"), sink.messages.get(0));
        assertTrue(sink.messages.get(0).contains("不改变结果"), "告警必须写清「只记录不改行为」");
        assertEquals(100 * MS, w.maxNanos());
        assertEquals(100 * MS, w.lastMeasured());
    }

    @Test
    void fastCallIsNotCounted() {
        RecordingSink sink = new RecordingSink();
        CallWatchdog w = new CallWatchdog(50 * MS, new FakeClock(0L, 5 * MS));
        w.setWarnSink(sink);
        long t0 = w.begin();
        assertEquals(5 * MS, w.finish("cava_pathfind", t0));
        assertEquals(0, w.slowCalls());
        assertEquals(0, sink.count());
        assertEquals(1, w.calls());
    }

    @Test
    void slowCallDoesNotChangeTheResultEvenWithAnInsaneClock() {
        // 对照组：阈值 1 小时 —— 永远不会告警
        RecordingSink calmSink = new RecordingSink();
        NativeCallGuard calm = new NativeCallGuard(new CircuitBreaker(1000),
                new CallWatchdog(3600_000L * MS, System::nanoTime));
        calm.setErrorSink(calmSink);
        calm.watchdog().setWarnSink(calmSink);

        // 实验组：阈值 1ns，而且时钟每次跳 10 秒 —— 每一次调用都"超时"
        RecordingSink wildSink = new RecordingSink();
        NativeCallGuard wild = new NativeCallGuard(new CircuitBreaker(1000),
                new CallWatchdog(1L, new FakeClock(0L, 10_000 * MS)));
        wild.setErrorSink(wildSink);
        wild.watchdog().setWarnSink(wildSink);

        int[] codes = {0, 1, 7, -1, -2, -3, -4, -5, -6, -7};
        for (int rc : codes) {
            assertEquals(rc, calm.call("cava_pathfind", () -> rc), "对照组必须原样返回");
            assertEquals(rc, wild.call("cava_pathfind", () -> rc),
                    "看门狗超时绝不许改变返回码（rc=" + rc + "）");
        }
        assertTrue(wild.watchdog().slowCalls() >= codes.length, "实验组每一次都应当被记为慢调用");
        assertTrue(wild.watchdog().warnEmissions() > 0);
        assertEquals(0, calm.watchdog().slowCalls(), "对照组不该有慢调用");
        assertEquals(0, calmSink.count(), "对照组不该有告警");
        // 熔断语义不受看门狗影响：失败计数只由返回码决定，与耗时无关
        assertEquals(4, calm.breaker().failures(), "codes 里只有 -1/-2/-5/-6 算硬失败");
        assertEquals(3, calm.breaker().softFailures(), "-3/-4/-7 是契约内的软失败");
        assertEquals(calm.breaker().failures(), wild.breaker().failures(),
                "两组阈值/时钟完全不同，硬失败计数必须一样");
        assertEquals(calm.breaker().softFailures(), wild.breaker().softFailures());
        assertFalse(calm.breaker().tripped());
        assertFalse(wild.breaker().tripped());
    }

    @Test
    void aThrowingClockStillReturnsTheNativeResult() {
        CallWatchdog w = new CallWatchdog(50 * MS, () -> {
            throw new IllegalStateException("clock exploded");
        });
        NativeCallGuard guard = new NativeCallGuard(new CircuitBreaker(1000), w);
        guard.setErrorSink(s -> { });
        w.setWarnSink(s -> { });
        assertEquals(-4, guard.call("cava_pathfind", () -> -4), "取时钟失败也必须原样返回原生返回值");
        assertEquals(7, guard.call("cava_pathfind", () -> 7));
        assertTrue(w.instrumentationFailures() > 0, "观测层自身出错要计数");
        assertEquals(0, w.slowCalls());
    }

    @Test
    void clockGoingBackwardsIsTreatedAsNotSlow() {
        RecordingSink sink = new RecordingSink();
        CallWatchdog w = new CallWatchdog(50 * MS, new FakeClock(1_000 * MS, -10 * MS));
        w.setWarnSink(sink);
        long t0 = w.begin();
        assertEquals(-1, w.finish("cava_pathfind", t0), "时钟回拨 ⇒ 按未计时处理，返回 -1");
        assertEquals(0, w.slowCalls());
        assertEquals(0, sink.count());
        assertEquals(1, w.clockAnomalies());
    }

    @Test
    void warnIsRateLimitedButCountingIsNot() {
        RecordingSink sink = new RecordingSink();
        CallWatchdog w = new CallWatchdog(50 * MS, new FakeClock(0L, 100 * MS));
        w.setWarnSink(sink);
        for (int i = 0; i < 100; i++) {
            w.finish("cava_pathfind", w.begin());
        }
        assertEquals(100, w.slowCalls(), "慢调用必须全部计数");
        assertEquals(CallWatchdog.MAX_WARN_PER_SYMBOL, sink.count(),
                "日志上限 " + CallWatchdog.MAX_WARN_PER_SYMBOL + " 条 —— 告警不是刷屏");
    }

    @Test
    void disabledWatchdogCostsNothingAndReports() {
        CallWatchdog w = new CallWatchdog(0L, () -> {
            throw new AssertionError("关闭时绝不许取时钟");
        });
        assertEquals(CallWatchdog.NO_CLOCK, w.begin());
        w.finish("x", CallWatchdog.NO_CLOCK);
        assertFalse(w.enabled());
        assertEquals(0, w.slowCalls());
        assertTrue(w.report().contains("enabled=false"), w.report());
    }

    @Test
    void thresholdComesFromSystemProperty() {
        String saved = System.getProperty(CallWatchdog.PROP_THRESHOLD_MICROS);
        try {
            System.setProperty(CallWatchdog.PROP_THRESHOLD_MICROS, "50");
            assertEquals(50 * 1000L, CallWatchdog.readThresholdNanos());
            System.clearProperty(CallWatchdog.PROP_THRESHOLD_MICROS);
            assertEquals(CallWatchdog.DEFAULT_THRESHOLD_NANOS, CallWatchdog.readThresholdNanos(),
                    "默认阈值必须是 50ms");
            System.setProperty(CallWatchdog.PROP_THRESHOLD_MICROS, "0");
            assertEquals(0L, CallWatchdog.readThresholdNanos(), "0 = 关闭看门狗");
        } finally {
            if (saved == null) {
                System.clearProperty(CallWatchdog.PROP_THRESHOLD_MICROS);
            } else {
                System.setProperty(CallWatchdog.PROP_THRESHOLD_MICROS, saved);
            }
        }
    }
}
