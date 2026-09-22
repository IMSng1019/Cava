package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.CavaConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** config/cava.json 的「子系统 × mod」覆盖：能读、能写、能被覆盖。 */
class CompatConfigTest {

    private Path tmp;

    @BeforeEach
    void setUpTemp() throws IOException {
        tmp = TestPaths.tempDir("compat-config");
    }

    @Test
    void perModDefaultsCoverCompatTable() {
        CavaConfig c = CavaConfig.load(tmp.resolve("cava.json"));
        for (String mod : CompatTable.mods()) {
            assertTrue(c.perMod().containsKey(mod), "perMod 默认值缺 " + mod);
            for (OverlapPoint p : CompatTable.OVERLAPS) {
                if (p.modId().equals(mod) && p.affectsCavaSubsystem()) {
                    assertTrue(c.perMod().get(mod).containsKey(p.subsystem()),
                            "perMod." + mod + " 缺子系统 " + p.subsystem());
                }
            }
        }
        // mirror 是伪子系统，不该出现在 CavaConfig.SUBSYSTEMS 里
        assertFalse(CavaConfig.SUBSYSTEMS.contains(CompatTable.MIRROR));
        assertTrue(CompatTable.decidedSubsystems().containsAll(CavaConfig.SUBSYSTEMS));
    }

    @Test
    void jsonRoundTripKeepsPerModAndCompat() throws IOException {
        Path f = tmp.resolve("cava.json");
        Files.writeString(f, """
                {
                  "ownership": { "pathfind": "native-first", "entity": "auto", "redstone": "auto" },
                  "perMod": { "vmp": { "entity": "defer" }, "servercore": { "entity": "native-first" } },
                  "compat": { "autoDeferOnRule": false, "ruleFiles": [ "D:/snap/carpet.conf" ] }
                }
                """, StandardCharsets.UTF_8);
        CavaConfig c = CavaConfig.load(f);
        assertEquals(CavaConfig.Ownership.DEFER, c.ownership("entity", "vmp"));
        assertEquals(CavaConfig.Ownership.NATIVE_FIRST, c.ownership("entity", "servercore"));
        assertFalse(c.compatAutoDeferOnRule());
        assertEquals(1, c.compatRuleFiles().size());
        c.save();
        CavaConfig again = CavaConfig.load(f);
        assertEquals(CavaConfig.Ownership.DEFER, again.ownership("entity", "vmp"));
        assertEquals(CavaConfig.Ownership.NATIVE_FIRST, again.ownership("entity", "servercore"));
        assertFalse(again.compatAutoDeferOnRule());
        assertEquals("D:/snap/carpet.conf", again.compatRuleFiles().get(0));
    }

    @Test
    void perModFallsBackToSubsystemLevel() {
        CavaConfig c = CavaConfig.load(tmp.resolve("cava.json"));
        assertTrue(c.perMod().containsKey("vmp"));
        c.setOwnership("entity", CavaConfig.Ownership.DEFER);   // 粗粒度
        assertEquals(CavaConfig.Ownership.DEFER, c.ownership("entity", "vmp"));
        assertEquals(CavaConfig.Ownership.DEFER, c.ownership("entity", "servercore"));
        c.setOwnership("entity", "vmp", CavaConfig.Ownership.NATIVE_FIRST);  // 细粒度覆盖粗粒度
        assertEquals(CavaConfig.Ownership.NATIVE_FIRST, c.ownership("entity", "vmp"));
        assertEquals(CavaConfig.Ownership.DEFER, c.ownership("entity", "servercore"));
        c.setOwnership("entity", "vmp", CavaConfig.Ownership.AUTO);  // AUTO = 继承粗粒度
        assertEquals(CavaConfig.Ownership.DEFER, c.ownership("entity", "vmp"));
        // 未知 mod / 未知子系统不炸
        assertEquals(CavaConfig.Ownership.AUTO, c.ownership("pathfind", "no-such-mod"));
    }

    @Test
    void defaultJsonIsSelfDocumenting() {
        CavaConfig c = CavaConfig.load(tmp.resolve("cava.json"));
        String json = c.toJson();
        assertTrue(json.contains("\"perMod\""));
        assertTrue(json.contains("\"servercore\""));
        assertTrue(json.contains("\"vmp\""));
        assertTrue(json.contains("\"autoDeferOnRule\": true"));
        assertTrue(json.contains("\"ruleFiles\": []"));
        // 老字段不能丢（P0 的 cava.parity / cava.Cava 在用）
        assertTrue(json.contains("\"pathfind\": \"auto\""));
        assertTrue(json.contains("\"parity\""));
    }
}
