package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** custom.lithium:options：机制语义 + "只关 P1 需要的那一组"的强断言。 */
class LithiumOptionsTest {

    @Test
    void keyNormalizationMirrorsLithiumApplyModOverride() throws IOException {
        // LithiumConfig.applyModOverride: 键不以 "mixin." 开头 -> 前缀自动补上
        assertEquals("mixin.ai.pathing", LithiumOptions.normalizeKey("ai.pathing"));
        assertEquals("mixin.ai.pathing", LithiumOptions.normalizeKey("mixin.ai.pathing"));
        assertEquals("mixin.ai.pathing", LithiumOptions.normalizeKey(" ai.pathing "));
        assertEquals("mixin.", LithiumOptions.normalizeKey(""));
    }

    @Test
    void parsesCustomObjectAndRejectsNonObject() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("lithium:options", Map.of("ai.pathing", false, "mixin.block.redstone_wire", true));
        Map<String, Boolean> parsed = LithiumOptions.parseCustom(ok);
        assertEquals(Boolean.FALSE, parsed.get("mixin.ai.pathing"));
        assertEquals(Boolean.TRUE, parsed.get("mixin.block.redstone_wire"));
        assertTrue(LithiumOptions.isDisabled("mixin.ai.pathing", parsed));
        assertFalse(LithiumOptions.isDisabled("mixin.block.redstone_wire", parsed));
        assertFalse(LithiumOptions.isDisabled("mixin.shapes", parsed), "没写的组一律视为开着");

        // 非对象值：Lithium 会 WARN 并忽略 -> 这里读成空表
        assertTrue(LithiumOptions.parseCustom(Map.of("lithium:options", "nope")).isEmpty());
        assertFalse(LithiumOptions.isValidCustom(Map.of("lithium:options", "nope")));
        assertTrue(LithiumOptions.parseCustom(null).isEmpty());
        assertTrue(LithiumOptions.parseCustom(Map.of()).isEmpty());
    }

    @Test
    void shippedMetadataMatchesSource() throws IOException {
        Path f = TestPaths.fabricModJson().orElse(null);
        Assumptions.assumeTrue(f != null, "找不到 src/main/resources/fabric.mod.json");
        Map<String, Boolean> fromFile = LithiumOptions.readShippedFromFile(f);
        assertEquals(LithiumOptions.SHIPPED, fromFile,
                "代码里的 SHIPPED 常量必须与 fabric.mod.json 实际发布值逐项一致");
        // 任务书验收：custom 段只关 P1 需要的那一组
        assertEquals(Map.of("mixin.ai.pathing", false), fromFile,
                "custom.lithium:options 只能有 mixin.ai.pathing=false 一项");
        for (String g : LithiumOptions.CANDIDATE_GROUPS) {
            if (g.equals("mixin.ai.pathing")) {
                assertTrue(LithiumOptions.isDisabled(g, fromFile));
            } else {
                assertFalse(LithiumOptions.isDisabled(g, fromFile), g + " 本轮不许关（见 LithiumOptions.reason）");
            }
        }
        assertTrue(LithiumOptions.reason("mixin.shapes").contains("不关"));
        assertEquals("mixin.ai.pathing=false mixin.entity.collisions.movement=true "
                + "mixin.block.redstone_wire=true mixin.shapes=true", LithiumOptions.describe(fromFile),
                "describe 直接反映发布值");
    }

    @Test
    void wantedTableFollowsPathfindOwner() {
        Map<String, Boolean> wantedNative = LithiumOptions.wantedFrom(Map.of("pathfind", Owner.NATIVE));
        assertEquals(Boolean.FALSE, wantedNative.get("mixin.ai.pathing"), "原生接管寻路 -> 关掉 Lithium 的寻路组");
        Map<String, Boolean> wantedDefer = LithiumOptions.wantedFrom(Map.of("pathfind", Owner.MOD));
        assertEquals(Boolean.TRUE, wantedDefer.get("mixin.ai.pathing"), "让位 -> 让 Lithium 继续优化");
    }

    @Test
    void inconsistencyDetection() {
        assertTrue(LithiumOptions.inconsistencies(LithiumOptions.SHIPPED, Map.of("mixin.ai.pathing", false)).isEmpty());
        var bad = LithiumOptions.inconsistencies(LithiumOptions.SHIPPED, Map.of("mixin.ai.pathing", true));
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).contains("mixin.ai.pathing"));
        assertTrue(LithiumOptions.staticRemedyHint("mixin.ai.pathing").contains("fabric.mod.json"));
    }

    @Test
    void realLithiumJarDeclaresAllFourGroups() {
        Path jar = TestPaths.modpackJar("lithium").orElse(null);
        Assumptions.assumeTrue(jar != null, "本机没有 lithium jar，跳过");
        for (String g : LithiumOptions.CANDIDATE_GROUPS) {
            var v = LithiumOptions.defaultGroupValue(jar, g);
            assertTrue(v.isPresent(), g + " 必须在 lithium-mixin-config-default.properties 里");
            assertEquals("true", v.get(), g + " 的默认值实测是 true");
        }
        assertEquals("false", LithiumOptions.defaultGroupValue(jar, "mixin.ai.nearby_entity_tracking").orElseThrow());
        assertEquals("", LithiumOptions.defaultGroupValue(jar, "no.such.group").orElse(""));
    }
}
