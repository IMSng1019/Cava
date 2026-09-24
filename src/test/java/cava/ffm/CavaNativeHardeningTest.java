package cava.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.harden.CallWatchdog;
import cava.harden.CircuitBreaker;
import cava.harden.NativeCallGuard;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 熔断 / 看门狗在<b>真实原生库</b>上的集成测试（喂伪造句柄 ⇒ 必然失败）。
 *
 * <p>为什么必须用真实库：假 supplier 只能证明记账逻辑对，证明不了"包装层真的接在 FFM 调用外面"。
 * 这里每一条都穿过 Java → FFM downcall → cava.dll。
 *
 * <p><b>每条用例一个隔离实例</b>（{@link CavaNative#newIsolatedForTest()}）：
 * 熔断是进程内单向的，把生产单例（或跨用例共用的实例）熔掉会让同一 JVM 里后面的原生测试
 * 全部静默走回退路径 —— 那正是本项目反复强调的"绿 ≠ 测过"。
 *
 * <p>没有原生库时整类 skip（与 {@code PathfindAbiTest} 同款）。纯 Java 的熔断/看门狗语义
 * 由 {@code cava.harden} 下的两个单测覆盖，它们永远会跑。
 */
class CavaNativeHardeningTest {

    private static final long FORGED_HANDLE = 0xDEAD_BEEFL;

    private CavaNative nat;

    @AfterEach
    void closeNative() {
        if (nat != null) {
            nat.close();
            nat = null;
        }
    }

    /** 打开一个隔离实例；没有原生库时整条用例 skip。 */
    private CavaNative open(NativeCallGuard guard) {
        CavaNative n = (guard == null) ? CavaNative.newIsolatedForTest() : CavaNative.newIsolatedForTest(guard);
        n.configure(Path.of(System.getProperty("user.dir", ".")), "test-harden");
        Assumptions.assumeTrue(n.tryOpen(),
                "需要原生库：-Dcava.native.path=<cava.dll>（当前 status=" + n.status() + "：" + n.detail() + "）");
        nat = n;
        return n;
    }

    /** 阈值调大的隔离实例：只想观察看门狗，不希望熔断干扰。 */
    private CavaNative openWatchdog(long watchdogNanos) {
        NativeCallGuard guard = new NativeCallGuard(new CircuitBreaker(1000),
                new CallWatchdog(watchdogNanos, System::nanoTime));
        guard.setErrorSink(s -> { });
        guard.watchdog().setWarnSink(s -> { });
        return open(guard);
    }

    /**
     * 伪造句柄 = {@code CAVA_ERR_NULL} = ABI 明文规定的"调用方输入问题" ⇒ <b>软失败，绝不熔断</b>。
     *
     * <p>这条是策略修正的回归：旧策略（任何负返回码都算失败）会让"故意喂坏输入"的既有
     * {@code PathfindAbiTest} 在 5 次之后被熔断（实测 {@code expected: <-4> but was: <-100>}）。
     */
    @Test
    void forgedHandleIsASoftFailureAndNeverTrips() {
        CavaNative n = open(null);
        for (int i = 0; i < 50; i++) {
            int rc = n.probePathfind(FORGED_HANDLE, 4);
            assertEquals(CavaLayouts.CAVA_ERR_NULL, rc, "伪造句柄必须安全返回 CAVA_ERR_NULL（第 " + i + " 次）");
        }
        assertFalse(n.hardening().breaker().tripped(), "50 次契约内的拒绝绝不许熔断");
        assertEquals(50, n.hardening().breaker().softFailures());
        assertEquals(0, n.hardening().breaker().failures(), "软失败不计入硬失败");
        assertEquals(0, n.hardening().breaker().errorEmissions(), "软失败不打 ERROR —— 它就是正常回退路径");
        assertEquals(NativeStatus.OPEN, n.status(), "软失败不改状态");
        assertTrue(n.available());
    }

    /**
     * 真·原生调用失败（FFM downcall 抛异常）⇒ 连续 N 次后熔断，之后<b>一次原生都不再尝试</b>。
     *
     * <p>怎么用真实库造出"必然失败"：把请求段放在一个<b>已经关闭</b>的 Arena 里 ——
     * FFM 的 downcall 会对入参做 liveness 检查并抛 {@code IllegalStateException}，
     * 这正是 {@code CavaNative} 包装层必须处理的那类真故障（不是"参数不对"，是"调用没发生"）。
     */
    @Test
    void realDowncallFailureTripsBreakerAndStopsAttemptingNative() {
        CavaNative n = open(null);
        int threshold = n.breakerThreshold();
        assertTrue(threshold > 0, "默认必须开启熔断（threshold=" + threshold + "）");

        try (Arena keep = Arena.ofShared()) {
            MemorySegment out = CavaNative.allocateArray(keep, CavaLayouts.PATH_NODE, 4);
            MemorySegment deadReq;
            try (Arena dead = Arena.ofShared()) {
                deadReq = dead.allocate(CavaLayouts.PATH_REQUEST);
                // 活着的时候能真的走到原生（全 0 请求 + 没有档案 => CAVA_ERR_ARG）
                assertEquals(CavaLayouts.CAVA_ERR_ARG, n.pathfind(n.handle(), deadReq, out, 4));
            }
            long attemptsBefore = n.hardening().attempts();
            for (int i = 1; i <= threshold; i++) {
                int rc = n.pathfind(n.handle(), deadReq, out, 4);
                assertEquals(CavaNative.ERR_CALL_FAILED, rc,
                        "关闭的 Arena 段必须让 downcall 抛异常并回退（第 " + i + " 次实际 " + rc + "）");
            }
            assertEquals(NativeStatus.DISABLED_BY_BREAKER, n.status(),
                    "连续 " + threshold + " 次真失败后必须自动全局关闭 native");
            assertEquals(threshold, n.hardening().attempts() - attemptsBefore, "熔断前每次都真的落到原生入口");
            assertEquals(1, n.hardening().breaker().errorEmissions(), "熔断只打 1 条 ERROR");
            assertEquals(1, n.hardening().callFailureEmissions(), "同一入口的调用异常只报一次");
            assertFalse(n.available());

            long attemptsAtTrip = n.hardening().attempts();
            for (int i = 0; i < 500; i++) {
                assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE, n.pathfind(n.handle(), deadReq, out, 4),
                        "熔断后必须返回 ERR_NATIVE_UNAVAILABLE");
            }
            assertEquals(attemptsAtTrip, n.hardening().attempts(),
                    "熔断后 500 次调用，原生尝试次数必须一动不动");
            assertEquals(500, n.hardening().unavailableCalls(), "原生回退计数（上线验收要看的那个数）");
            assertEquals(1, n.hardening().breaker().errorEmissions(), "熔断后再失败 500 次，ERROR 条数不许增长");
        }
    }

    /** 加固层里的原生错误码常量必须与 cava_abi.h / CavaLayouts 一致（防静默漂移）。 */
    @Test
    void hardeningErrorCodesMatchTheAbi() {
        assertEquals(CavaLayouts.CAVA_ERR_ABI_VERSION, NativeCallGuard.CODE_ABI_VERSION);
        assertEquals(CavaLayouts.CAVA_ERR_LAYOUT, NativeCallGuard.CODE_LAYOUT);
        assertEquals(CavaLayouts.CAVA_ERR_OOM, NativeCallGuard.CODE_OOM);
        assertEquals(CavaLayouts.CAVA_ERR_INTERNAL, NativeCallGuard.CODE_INTERNAL);
    }

    @Test
    void legalCallSucceedsAndIsNotCountedAsFailure() {
        CavaNative n = open(null);
        try (Arena arena = Arena.ofShared()) {
            MemorySegment ids = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, 4);
            for (int i = 0; i < 4; i++) {
                ids.set(ValueLayout.JAVA_INT, (long) i * 4, 100 + i);
            }
            assertEquals(CavaLayouts.CAVA_OK,
                    n.regionUpload(n.handle(), 2, 1, 2, 0, 0, 0, ids, 4), "合法区域上传必须成功");
            assertEquals(0, n.hardening().breaker().failures(), "成功调用不该被记成失败");
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            assertEquals(CavaLayouts.CAVA_OK, n.regionStateIdAt(n.handle(), 1, 0, 1, out));
            assertEquals(103, out.get(ValueLayout.JAVA_INT, 0));
            assertEquals(0, n.hardening().breaker().failures());
            assertTrue(n.hardening().watchdog().calls() >= 2, "看门狗应当数到了这几次调用");
        }
    }

    @Test
    void watchdogOverThresholdDoesNotChangeTheReturnCode() {
        CavaNative slow = openWatchdog(1L);             // 1ns：每一次调用都必然"超时"
        CavaNative calm = openWatchdog(3_600_000_000_000L); // 1 小时：永远不告警
        try (Arena arena = Arena.ofShared()) {
            MemorySegment ids = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, 4);
            for (int i = 0; i < 4; i++) {
                ids.set(ValueLayout.JAVA_INT, (long) i * 4, 1);
            }
            int rcSlowOk = slow.regionUpload(slow.handle(), 2, 1, 2, 0, 0, 0, ids, 4);
            int rcSlowBad = slow.probePathfind(slow.handle(), 64);
            int rcCalmOk = calm.regionUpload(calm.handle(), 2, 1, 2, 0, 0, 0, ids, 4);
            int rcCalmBad = calm.probePathfind(calm.handle(), 64);

            assertEquals(rcCalmOk, rcSlowOk, "看门狗超时绝不许改变返回码");
            assertEquals(rcCalmBad, rcSlowBad, "看门狗超时绝不许改变返回码（非法请求也一样）");
            assertTrue(slow.hardening().watchdog().slowCalls() >= 2, "A 组每次都应记为慢调用");
            assertTrue(slow.hardening().watchdog().warnEmissions() >= 1, "A 组必须留下告警记录");
            assertEquals(0, calm.hardening().watchdog().slowCalls(), "B 组不该有慢调用");
            assertEquals(0, calm.hardening().watchdog().warnEmissions(), "B 组不该有告警");
            assertTrue(slow.hardening().watchdog().lastMeasured() >= 0);
        } finally {
            calm.close();
        }
    }

    @Test
    void hardeningIsWiredIntoTheProductionSingleton() {
        CavaNative prod = CavaNative.get();
        assertNotNull(prod.hardening(), "生产单例必须持有加固层");
        assertEquals(NativeCallGuard.ERR_NATIVE_UNAVAILABLE, CavaNative.ERR_NATIVE_UNAVAILABLE,
                "别名必须与 NativeCallGuard 一致（数值 -100 不许漂移）");
        assertEquals(NativeCallGuard.ERR_CALL_FAILED, CavaNative.ERR_CALL_FAILED);
        assertTrue(prod.hardeningReport().contains("熔断["), prod.hardeningReport());
        assertTrue(prod.hardeningReport().contains("看门狗["), prod.hardeningReport());
        assertTrue(prod.banner().contains("熔断"), "启动横幅必须带上熔断计数");
        assertTrue(prod.banner().contains("看门狗"), "启动横幅必须带上看门狗计数");
    }
}
