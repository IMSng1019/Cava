package cava.subsystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.canary.HookCanary;
import org.junit.jupiter.api.Test;

/** 钩子金丝雀与注册表：命中/未命中两条路都必须可判定。 */
class CanaryTest {

    @Test
    void hitCounts() {
        HookCanary c = new HookCanary("test");
        assertEquals(0, c.count());
        c.hit();
        c.hit();
        assertEquals(2, c.count());
        assertFalse(c.probeNow(), "没有探测目标时不该报告命中");
        assertTrue(c.probe(c::hit), "有目标且目标命中计数 -> true");
        assertEquals(3, c.count());
    }

    @Test
    void probeWithoutHitIsFalse() {
        HookCanary c = new HookCanary("test");
        assertFalse(c.probe(() -> {
        }));
        assertEquals(0, c.count());
        assertTrue(c.lastProbeResult().contains("计数未变"));
    }

    @Test
    void globalRegistryIsEmptyWithoutServerStart() {
        // 子系统只由 cava.Cava.onInitialize 注册；单测不启动 MC，所以全局表应为空且探测不抛异常
        assertTrue(SubsystemRegistry.get().all().isEmpty(), "单测里不应有全局子系统");
        assertEquals(0, SubsystemRegistry.get().canaryProbeAll());
    }

    @Test
    void canarySelfTestCoversBothPaths() {
        // 合成子系统：good 的探测命中 -> 保持启用；bad 的探测不动 -> disable 并记原因
        assertTrue(SubsystemRegistry.get().canarySelfTest());
    }

    @Test
    void disabledSubsystemIsNeverProbed() {
        AbstractSubsystem s = new AbstractSubsystem("unit-disabled", false);
        assertFalse(s.enabled(), "P0 骨架：没有钩子就必须是 disabled");
        s.disable("测试禁用");
        assertEquals("测试禁用", s.disabledReason());
        s.canaryProbe();
        assertEquals(0, s.canaryCount());
    }
}
