package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Carpet/TIS 规则快照：解析语义必须与 SettingsManager.readSettingsFromConf 字节码逐条对齐。 */
class CarpetRuleProbeTest {

    private Path tmp;

    @BeforeEach
    void setUpTemp() throws IOException {
        tmp = TestPaths.tempDir("carpet-rules");
    }

    @Test
    void parseConfMirrorsBytecode() {
        String text = String.join("\n",
                "# Carpet configuration file",
                "",
                "fastRedstoneDust true",
                "pushLimit 12",
                "quasiConnectivity 1",
                "someStringRule hello world",   // split(...,2) -> 值里保留空格
                "valueWithHash 5 # 注释",        // 值不以 # 开头 -> 整段保留（含注释）
                "commentedOut # true",           // 值以 '#' 开头 -> 跳过
                "onlyOneToken",                  // 只有一段 -> 跳过
                "locked");
        CarpetRuleProbe.Snapshot s = CarpetRuleProbe.parseConf(Path.of("carpet.conf"), text);
        assertTrue(s.locked(), "单独的 locked 行要置 locked");
        assertEquals("true", s.values().get("fastRedstoneDust"));
        assertEquals("12", s.values().get("pushLimit"));
        assertEquals("hello world", s.values().get("someStringRule"));
        assertEquals("12", s.values().get("pushLimit"));
        assertEquals("1", s.values().get("quasiConnectivity"));
        assertEquals("5 # 注释", s.values().get("valueWithHash"),
                "split(\"\\\\s+\", 2) 的语义：值里带空格/注释一律原样保留");
        assertFalse(s.values().containsKey("commentedOut"));
        assertFalse(s.values().containsKey("onlyOneToken"));
        assertFalse(s.values().containsKey("#"));
    }

    @Test
    void parseJsonAndPropertiesSnapshots() {
        CarpetRuleProbe.Snapshot j = CarpetRuleProbe.parseJson(Path.of("x.json"),
                "{\"fastRedstoneDust\": true, \"pushLimit\": 12, \"ruleWithObject\": {\"a\":1}}");
        assertEquals("true", j.values().get("fastRedstoneDust"));
        assertEquals("12", j.values().get("pushLimit"));
        assertFalse(j.values().containsKey("ruleWithObject"), "对象值不是标量规则，跳过");
        CarpetRuleProbe.Snapshot wrapped = CarpetRuleProbe.parseJson(Path.of("y.json"),
                "{\"rules\":{\"redstoneDustRandomUpdateOrder\":\"true\"}}");
        assertEquals("true", wrapped.values().get("redstoneDustRandomUpdateOrder"));
        CarpetRuleProbe.Snapshot p = CarpetRuleProbe.parseProperties(Path.of("x.properties"),
                "# 注释\nfastRedstoneDust=true\n# 又一行\n");
        assertEquals("true", p.values().get("fastRedstoneDust"));
    }

    @Test
    void readAllFindsWorldCarpetConf() throws IOException {
        Path world = tmp.resolve("world");
        Files.createDirectories(world);
        Files.writeString(world.resolve("carpet.conf"), "fastRedstoneDust true\nlagFreeSpawning true\n",
                StandardCharsets.UTF_8);
        List<CarpetRuleProbe.Snapshot> snaps = CarpetRuleProbe.readAll(tmp, List.of());
        assertEquals(1, snaps.size());
        assertEquals("carpet.conf", snaps.get(0).label());
        assertEquals("true", CarpetRuleProbe.merge(snaps).get("fastRedstoneDust"));
        // 显式指定的文件优先级最高
        Path explicit = tmp.resolve("explicit.conf");
        Files.writeString(explicit, "fastRedstoneDust false\n", StandardCharsets.UTF_8);
        List<CarpetRuleProbe.Snapshot> snaps2 = CarpetRuleProbe.readAll(tmp, List.of(explicit.toString()));
        assertEquals("false", CarpetRuleProbe.merge(snaps2).get("fastRedstoneDust"));
    }

    @Test
    void evaluateFlagsDeviationsFromDefaults() {
        CarpetRuleProbe.Snapshot live = CarpetRuleProbe.parseConf(Path.of("carpet.conf"), String.join("\n",
                "fastRedstoneDust true",
                "optimizedTNT true",
                "lagFreeSpawning true",
                "pushLimit 12",
                "fillUpdates true",
                "redstoneDustRandomUpdateOrder true",
                "optimizedFastEntityMovement true",
                "pushLimit 12"));
        List<CarpetRuleProbe.Finding> fs = CarpetRuleProbe.evaluate(List.of(live));
        Map<String, CarpetRuleProbe.Finding> byName = new java.util.LinkedHashMap<>();
        for (CarpetRuleProbe.Finding f : fs) {
            byName.put(f.rule().name(), f);
        }
        assertTrue(byName.get("fastRedstoneDust").alarming(), "默认 false，实测 true -> 告警");
        assertEquals(ConsistencyRule.Severity.BASELINE, byName.get("fastRedstoneDust").rule().severity());
        assertFalse(byName.get("pushLimit").alarming(), "12 == 默认 12 -> 不告警");
        assertFalse(byName.get("fillUpdates").alarming(), "true == 默认 true -> 不告警");
        assertTrue(byName.get("redstoneDustRandomUpdateOrder").alarming());
        assertEquals(ConsistencyRule.Severity.TOXIC, byName.get("redstoneDustRandomUpdateOrder").rule().severity());
        assertFalse(byName.get("quasiConnectivity").alarming(), "1 == 默认 1 -> 不告警");
        // 规则缺失 -> value=null 且不告警（不能拿"没读到"当"没开启"之外的结论）
        assertNull(byName.get("repeaterHalfDelay").value());
        assertFalse(byName.get("repeaterHalfDelay").alarming());
    }

    @Test
    void numericEquivalenceHandlesJavaLiteralSuffixes() {
        assertTrue(ConsistencyRule.sameValue("64", "64.0d"));
        assertTrue(ConsistencyRule.sameValue("12", "12"));
        assertFalse(ConsistencyRule.sameValue("13", "12"));
        assertFalse(ConsistencyRule.sameValue("true", "false"));
        assertTrue(ConsistencyRule.sameValue("TRUE", "true"));
        assertNull(ConsistencyRule.asNumber("true"));
        assertEquals(12.0d, ConsistencyRule.asNumber("12f").doubleValue(), 0.0d);
    }

    @Test
    void candidateListIncludesDocumentedLocations() {
        List<Path> c = CarpetRuleProbe.candidates(Path.of("game"), List.of("custom.conf"));
        assertTrue(c.get(0).toString().endsWith("custom.conf"));
        assertTrue(c.stream().anyMatch(p -> p.toString().endsWith("config/carpet.conf".replace('/', java.io.File.separatorChar))));
        assertTrue(c.stream().anyMatch(p -> p.toString().contains("carpet-rules.json")));
    }
}
