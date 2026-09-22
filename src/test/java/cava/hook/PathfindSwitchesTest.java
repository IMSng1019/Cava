package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 开关解析（纯 Java，不需要 Minecraft，也不需要原生库）。
 *
 * <p>为什么值得测：这些开关决定"钩子介不介入"。解析写错（例如把 {@code "false"} 当假在某个分支漏掉）
 * 会让 A/B 性能对比的两条腿跑到同一个配置上，从而得出**看起来合理但完全错误**的结论。
 */
class PathfindSwitchesTest {

    @Test
    void booleanParsingMatchesCavaNativeConvention() {
        assertTrue(PathfindSwitches.parseBoolean(null, true));
        assertFalse(PathfindSwitches.parseBoolean(null, false));
        assertTrue(PathfindSwitches.parseBoolean("", true));
        assertFalse(PathfindSwitches.parseBoolean("", false));
        // 与 CavaNative.enabledByFlag() 完全同款：false / 0 / no / off 一律假
        for (String falsy : new String[]{"false", "FALSE", "False", "0", "no", "NO", "off", "OFF", " false "}) {
            assertFalse(PathfindSwitches.parseBoolean(falsy, true), falsy);
        }
        for (String truthy : new String[]{"true", "TRUE", "1", "yes", "on", "anything"}) {
            assertTrue(PathfindSwitches.parseBoolean(truthy, false), truthy);
        }
    }

    @Test
    void longParsingFallsBackInsteadOfThrowing() {
        assertEquals(7L, PathfindSwitches.parseLong("7", 3L));
        assertEquals(3L, PathfindSwitches.parseLong("not-a-number", 3L));
        assertEquals(3L, PathfindSwitches.parseLong("", 3L));
        assertEquals(3L, PathfindSwitches.parseLong(null, 3L));
        assertEquals(-1L, PathfindSwitches.parseLong("-1", 3L));
    }

    @Test
    void nativeTakeoverIsOffByDefault() {
        String old = System.getProperty(PathfindSwitches.PROP_NATIVE);
        System.clearProperty(PathfindSwitches.PROP_NATIVE);
        try {
            // captain 的 P1 门禁：cava_pathfind 保守返回 CAVA_ERR_UNIMPLEMENTED，
            // 且 CAVA_PF_* 位号对齐未回执 ⇒ 默认**不打开**原生路径。
            assertFalse(PathfindSwitches.nativeTakeoverEnabled(),
                    "默认必须是关：位号错位时打开原生路径会产出看起来正常但语义错的路径");
            assertTrue(PathfindSwitches.hookEnabled(), "注入体本身默认开（要能数金丝雀）");
            assertTrue(PathfindSwitches.probeEnabled(), "金丝雀探测默认开");
        } finally {
            if (old != null) {
                System.setProperty(PathfindSwitches.PROP_NATIVE, old);
            }
        }
    }

    @Test
    void mirrorClassDefaultsToTheFrozenImplementationName() {
        String old = System.getProperty(PathfindSwitches.PROP_MIRROR_CLASS);
        System.clearProperty(PathfindSwitches.PROP_MIRROR_CLASS);
        try {
            assertEquals("cava.mirror.RegionMirror", PathfindSwitches.mirrorClassName());
        } finally {
            if (old != null) {
                System.setProperty(PathfindSwitches.PROP_MIRROR_CLASS, old);
            }
        }
    }
}
