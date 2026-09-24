package cava.harden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 线程归属检查单测（P4-C）。
 *
 * <p>它断言的是<b>可证伪</b>的三件事：
 * <ol>
 *   <li><b>主线程连续调用 ⇒ offThreadCalls 一动不动</b>（检查不会把正常路径误报）；</li>
 *   <li><b>第二条线程调用一次 ⇒ offThreadCalls == 1</b>（检查真的能红）；
 *       这就是"能红的测试才是测试"：如果把 {@code noteThreadOwnership} 里的比较改坏，
 *       这一条会立刻失败；</li>
 *   <li><b>返回值完全不受影响</b>：越线调用的返回值与同参数的合法调用逐位相同，
 *       且看门狗/熔断计数不受线程归属影响（只观测，绝不改行为）。</li>
 * </ol>
 *
 * <p>为什么用 {@code -Dcava.native.threadcheck=false} 也要有一条：检查必须能整体关掉
 * （现场如果出现误报，运维要有一个不重新打包就能关的开关）。
 */
class NativeThreadOwnershipTest {

    private static int rcAlways(int rc) {
        return rc;
    }

    @Test
    void sameThreadCallsNeverCountAsOffThread() {
        NativeCallGuard guard = new NativeCallGuard();
        for (int i = 0; i < 1000; i++) {
            assertEquals(7, guard.call("cava_pathfind", () -> rcAlways(7)));
        }
        assertEquals(0, guard.offThreadCalls(), "同一个线程调用 1000 次不能出现越线计数");
        assertNotNull(guard.ownerThreadName(), "owner 必须被认领");
        assertEquals(1000, guard.attempts());
    }

    @Test
    void aSecondThreadIsCountedAndReportedOnce() throws Exception {
        NativeCallGuard guard = new NativeCallGuard();
        assertEquals(0, guard.call("cava_pathfind", () -> rcAlways(0)));
        final String owner = guard.ownerThreadName();

        final int perThread = 50;
        final int threads = 3;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger rcs = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (guard.call("cava_pathfind", () -> rcAlways(5)) != 5) {
                            rcs.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "offthread-" + t);
            th.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "越线线程必须在 30 s 内跑完");

        assertEquals(0, rcs.get(), "越线调用的返回值必须原样是 5（检查不改变行为）");
        assertEquals(threads * perThread, guard.offThreadCalls(),
                "3 条线程各 " + perThread + " 次越线调用必须被逐次计数");
        assertEquals(1 + threads * perThread, guard.attempts(), "越线调用照样是真实调用");
        assertTrue(guard.firstOffThread().contains("owner="), "必须记录首次越线现场：" + guard.firstOffThread());
        assertTrue(guard.firstOffThread().contains("cava_pathfind"), "现场里要有入口名");
        assertEquals(owner, guard.ownerThreadName(), "owner 认领之后不许再变");
    }

    /** 反向对照：关掉检查后，同样的越线调用必须<b>不再</b>计数（证明计数来自本检查）。 */
    @Test
    void disablingTheCheckStopsTheCounting() throws Exception {
        String prev = System.getProperty(NativeCallGuard.PROP_THREAD_CHECK);
        System.setProperty(NativeCallGuard.PROP_THREAD_CHECK, "false");
        try {
            NativeCallGuard guard = new NativeCallGuard();
            guard.call("cava_pathfind", () -> rcAlways(0));
            Thread th = new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    guard.call("cava_pathfind", () -> rcAlways(0));
                }
            }, "offthread-disabled");
            th.start();
            th.join(10_000);
            assertFalse(th.isAlive(), "线程必须结束");
            assertEquals(0, guard.offThreadCalls(), "-Dcava.native.threadcheck=false 时必须完全不计数");
        } finally {
            if (prev == null) {
                System.clearProperty(NativeCallGuard.PROP_THREAD_CHECK);
            } else {
                System.setProperty(NativeCallGuard.PROP_THREAD_CHECK, prev);
            }
        }
    }

    /** 报告行必须带上两个上线计数（ownerThread / offThreadCalls）。 */
    @Test
    void reportExposesTheThreadCounters() {
        NativeCallGuard guard = new NativeCallGuard();
        guard.call("cava_pathfind", () -> rcAlways(0));
        String report = guard.report() + " " + guard.consecutiveReport();
        assertTrue(report.contains("offThreadCalls=0"), "报告里必须有 offThreadCalls：" + report);
        assertTrue(report.contains("ownerThread="), "报告里必须有 ownerThread：" + report);
    }
}
