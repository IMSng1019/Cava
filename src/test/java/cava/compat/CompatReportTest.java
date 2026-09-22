package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.CavaConfig;
import cava.subsystem.AbstractSubsystem;
import cava.subsystem.SubsystemRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 启动兼容性报告：行格式、默认归属、以及"每个决定都能被配置覆盖"。 */
class CompatReportTest {

    private Path tmp;

    @BeforeEach
    void setUpTemp() throws IOException {
        tmp = TestPaths.tempDir("compat-report");
    }

    private static final Map<String, Integer> FIELD_COUNT = Map.of(
            "header", 11, "mod", 7, "owner", 10, "rule", 11, "lithium", 8, "adapter", 5, "note", 5, "warn", 5);

    private static ModInfo mod(String id, String version) {
        return new ModInfo(id, version, "*", List.of(), Map.of(), "test/" + id);
    }

    private static List<ModInfo> fullModset() {
        List<ModInfo> mods = new ArrayList<>();
        mods.add(mod("carpet", "1.4.128+v231205"));
        mods.add(mod("carpet-tis-addition", "1.82.3"));
        mods.add(mod("lithium", "0.12.1"));
        mods.add(mod("servercore", "1.5.0+1.20.4"));
        mods.add(mod("vmp", "0.2.0+beta.7.139"));
        return mods;
    }

    private CompatReport report(String json) throws Exception {
        Path f = tmp.resolve("cava-" + Math.abs(json.hashCode()) + ".json");
        Files.writeString(f, json);
        CavaConfig cfg = CavaConfig.load(f);
        return CompatReport.evaluate(cfg, fullModset(), List.of(), LithiumOptions.SHIPPED,
                "1.20.4", "0.19.5", "OPEN", 6);
    }

    private CompatReport plainReport() throws Exception {
        CavaConfig cfg = CavaConfig.load(tmp.resolve("cava-plain.json"));
        return CompatReport.evaluate(cfg, fullModset(), List.of(), LithiumOptions.SHIPPED,
                "1.20.4", "0.19.5", "OPEN", 6);
    }

    @Test
    void everyLineHasFixedFieldCount() throws Exception {
        CompatReport r = plainReport();
        assertFalse(r.lines().isEmpty());
        for (String line : r.lines()) {
            String[] p = line.split("\\|", -1);
            assertTrue(line.startsWith(CompatReport.PREFIX + "|" + CompatReport.FORMAT_VERSION + "|"), line);
            assertTrue(FIELD_COUNT.containsKey(p[2]), "未知记录类型: " + p[2]);
            assertEquals(FIELD_COUNT.get(p[2]), p.length, "字段数不对: " + line);
            assertTrue(line.indexOf('\n') < 0 && line.indexOf('\r') < 0);
        }
        long owners = r.lines().stream().filter(l -> l.startsWith("CAVA-COMPAT|v1|owner|")).count();
        assertEquals(CompatTable.OVERLAPS.size(), owners, "每个重叠点都要有一行 owner");
    }

    @Test
    void defaultDecisionsMatchCaptainRulings() throws Exception {
        CompatReport r = plainReport();
        assertEquals(Owner.NATIVE, r.owner("pathfind"), "P1 复刻原生 A* -> 寻路归 native");
        assertEquals(Owner.MOD, r.owner("entity"), "P2 未决策 -> 本轮让位");
        assertEquals(Owner.MOD, r.owner("redstone"), "P3 未决策 -> 本轮让位");
        assertEquals(List.of("entity", "redstone"), r.deferSubsystems());
        // mirror 是伪子系统：只登记不裁决，不进子系统归属表
        assertEquals(Owner.VANILLA, r.owner(CompatTable.MIRROR));
        assertTrue(r.lines().stream().anyMatch(
                l -> l.startsWith("CAVA-COMPAT|v1|owner|mirror|lithium|mixin.shapes|mod|auto|not-hooked|")));

        // 只有 mixin.ai.pathing 关了，且与归属一致
        assertTrue(r.lithiumInconsistencies().isEmpty(),
                "默认配置下发布值与期望值必须一致，实际: " + r.lithiumInconsistencies());
        String aiPathing = r.lines().stream()
                .filter(l -> l.startsWith("CAVA-COMPAT|v1|lithium|mixin.ai.pathing|")).findFirst().orElseThrow();
        assertTrue(aiPathing.contains("published=off"));
        assertTrue(aiPathing.contains("wanted=off"));
        assertTrue(aiPathing.contains("consistent=true"));
        for (String g : List.of("mixin.entity.collisions.movement", "mixin.block.redstone_wire", "mixin.shapes")) {
            String line = r.lines().stream().filter(l -> l.startsWith("CAVA-COMPAT|v1|lithium|" + g + "|"))
                    .findFirst().orElseThrow();
            assertTrue(line.contains("published=on"), g + " 本轮不许关: " + line);
        }
    }

    @Test
    void ownerLineCarriesEvidenceFields() throws Exception {
        CompatReport r = plainReport();
        String vmp = r.lines().stream()
                .filter(l -> l.startsWith("CAVA-COMPAT|v1|owner|entity|vmp|entity.move_zero_velocity.MixinEntity|"))
                .findFirst().orElseThrow();
        String[] p = vmp.split("\\|", -1);
        // 段序：0=前缀 1=v1 2=owner 3=subsystem 4=modId 5=key 6=owner 7=configured 8=stage 9=overlap
        assertEquals("entity.move_zero_velocity.MixinEntity", p[5]);
        assertEquals("mod", p[6]);
        assertEquals("auto", p[7]);
        assertEquals("pending-p2", p[8]);
        assertTrue(p[9].contains("method_5784"), "重叠说明里要有方法号证据: " + p[9]);
    }

    @Test
    void noRelevantModsMeansVanilla() throws Exception {
        CavaConfig cfg = CavaConfig.load(tmp.resolve("cava-none.json"));
        CompatReport r = CompatReport.evaluate(cfg, List.of(mod("sodium", "0.5.8")), List.of(),
                LithiumOptions.SHIPPED, "1.20.4", "0.19.5", "OPEN", 6);
        assertEquals(Owner.VANILLA, r.owner("pathfind"));
        assertEquals(Owner.VANILLA, r.owner("entity"));
        assertEquals(Owner.VANILLA, r.owner("redstone"));
        assertTrue(r.deferSubsystems().isEmpty());
        assertTrue(r.decisions().isEmpty());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("CARPET_RULES_ABSENT")));
    }

    @Test
    void configCanForceDeferAndWarnsAboutStaticMetadata() throws Exception {
        CompatReport r = report("{\"ownership\":{\"pathfind\":\"defer\"}}");
        assertEquals(Owner.MOD, r.owner("pathfind"));
        assertTrue(r.deferSubsystems().contains("pathfind"));
        // 让位给 Lithium，但 fabric.mod.json 已经把它的寻路组关了 -> 必须报不一致
        assertEquals(1, r.lithiumInconsistencies().size());
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("LITHIUM_METADATA_MISMATCH")));
        String aiPathing = r.lines().stream()
                .filter(l -> l.startsWith("CAVA-COMPAT|v1|lithium|mixin.ai.pathing|")).findFirst().orElseThrow();
        assertTrue(aiPathing.contains("published=off"));
        assertTrue(aiPathing.contains("wanted=on"));
        assertTrue(aiPathing.contains("consistent=false"));
        assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("CAVA-COMPAT|v1|warn|LITHIUM_METADATA_MISMATCH|")));
    }

    @Test
    void configCanForceNativeFirstPerMod() throws Exception {
        CompatReport r = report("""
                {"perMod":{"lithium":{"entity":"native-first"},
                           "servercore":{"entity":"native-first"},
                           "vmp":{"entity":"native-first"}}}
                """);
        assertEquals(Owner.NATIVE, r.owner("entity"));
        assertFalse(r.deferSubsystems().contains("entity"));
        assertTrue(r.deferSubsystems().contains("redstone"));
        String sc = r.lines().stream()
                .filter(l -> l.startsWith("CAVA-COMPAT|v1|owner|entity|servercore|")).findFirst().orElseThrow();
        assertTrue(sc.contains("|native|native-first|"), sc);
    }

    @Test
    void configCanOverrideRuleDrivenDeferral() throws Exception {
        // 把红石全部重叠点显式设成 native-first，再看规则告警怎么处理
        String base = """
                {"perMod":{"lithium":{"redstone":"native-first"},
                           "carpet":{"redstone":"native-first"},
                           "carpet-tis-addition":{"redstone":"native-first"}}%s}
                """;
        Path a = tmp.resolve("a.json");
        Files.writeString(a, base.formatted(""));
        CavaConfig ca = CavaConfig.load(a);
        CarpetRuleProbe.Snapshot toxic = CarpetRuleProbe.parseConf(Path.of("carpet.conf"),
                "redstoneDustRandomUpdateOrder true\n");
        CompatReport ra = CompatReport.evaluate(ca, fullModset(), List.of(toxic), LithiumOptions.SHIPPED,
                "1.20.4", "0.19.5", "OPEN", 6);
        assertEquals(Owner.NATIVE, ra.owner("redstone"), "显式 native-first 顶掉规则的自动让位");
        assertTrue(ra.ruleDeferOverridden().contains("redstone"));
        assertTrue(ra.ruleForcedDefer().isEmpty());
        assertTrue(ra.warnings().stream().anyMatch(w -> w.startsWith("RULE_DEFER_OVERRIDDEN")));
        assertTrue(ra.warnings().stream().anyMatch(w -> w.startsWith("TOXIC_RULE_ON")), "告警本身不能消失");

        // 关掉 autoDeferOnRule：规则只告警，不参与归属
        Path b = tmp.resolve("b.json");
        Files.writeString(b, base.formatted(",\"compat\":{\"autoDeferOnRule\":false}"));
        CavaConfig cb = CavaConfig.load(b);
        CompatReport rb = CompatReport.evaluate(cb, fullModset(), List.of(toxic), LithiumOptions.SHIPPED,
                "1.20.4", "0.19.5", "OPEN", 6);
        assertEquals(Owner.NATIVE, rb.owner("redstone"));
        assertTrue(rb.ruleForcedDefer().isEmpty());
        assertTrue(rb.ruleDeferOverridden().isEmpty());
        assertTrue(rb.warnings().stream().anyMatch(w -> w.startsWith("TOXIC_RULE_ON")));

        // 不显式覆盖时，毒规则会把红石从"默认让位"钉死在让位（默认本来就是 mod，这里验证 forced 记录）
        Path c = tmp.resolve("c.json");
        Files.writeString(c, "{}");
        CavaConfig cc = CavaConfig.load(c);
        CompatReport rc = CompatReport.evaluate(cc, fullModset(), List.of(toxic), LithiumOptions.SHIPPED,
                "1.20.4", "0.19.5", "OPEN", 6);
        assertEquals(Owner.MOD, rc.owner("redstone"));
    }

    @Test
    void baselineRulesAreReportedAsAlarmWithBaselineSeverity() throws Exception {
        CavaConfig cfg = CavaConfig.load(tmp.resolve("cava-rules.json"));
        CarpetRuleProbe.Snapshot live = CarpetRuleProbe.parseConf(Path.of("carpet.conf"),
                "fastRedstoneDust true\noptimizedTNT true\nlagFreeSpawning true\n");
        CompatReport r = CompatReport.evaluate(cfg, fullModset(), List.of(live), LithiumOptions.SHIPPED,
                "1.20.4", "0.19.5", "OPEN", 6);
        String frd = r.lines().stream().filter(l -> l.startsWith("CAVA-COMPAT|v1|rule|carpet|fastRedstoneDust|"))
                .findFirst().orElseThrow();
        assertTrue(frd.contains("|true|false|baseline|redstone|ALARM|"), frd);
        assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("CAVA-COMPAT|v1|note|redstone-baseline|")),
                "必须在报告里写明红石基准是 Carpet 而不是原版");
        assertFalse(r.warnings().stream().anyMatch(w -> w.startsWith("TOXIC_RULE_ON")),
                "baseline 级规则不该产生 TOXIC 告警");
    }

    @Test
    void applyDeferralsDisablesOnlyModOwnedSubsystems() throws Exception {
        var ctor = SubsystemRegistry.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        SubsystemRegistry reg = ctor.newInstance();   // 刻意不用全局单例，避免污染 CanaryTest
        reg.register(new AbstractSubsystem("pathfind", true));
        reg.register(new AbstractSubsystem("entity", true));
        reg.register(new AbstractSubsystem("redstone", true));
        CompatReport r = plainReport();
        assertEquals(2, r.applyDeferrals(reg));
        assertTrue(reg.byId("pathfind").orElseThrow().enabled(), "pathfind 归 native，不该被禁用");
        assertTrue(reg.byId("entity").orElseThrow().disabledReason().contains("让位"));
        assertTrue(reg.byId("redstone").orElseThrow().disabledReason().contains("让位"));
        assertFalse(reg.byId("entity").orElseThrow().enabled());
    }

    @Test
    void summaryLinesCoverAllSubsystems() throws Exception {
        CompatReport r = plainReport();
        List<String> s = r.summaryLines();
        assertEquals(CavaConfig.SUBSYSTEMS.size() + 1, s.size());
        for (String id : CavaConfig.SUBSYSTEMS) {
            assertTrue(s.stream().anyMatch(l -> l.trim().startsWith(id)), "缺子系统 " + id);
        }
    }

    @Test
    void unknownSubsystemDecisionsDoNotBreakSubsystemMap() throws Exception {
        CompatReport r = plainReport();
        Map<String, Owner> m = new LinkedHashMap<>(r.subsystemOwners());
        assertEquals(Set.copyOf(CavaConfig.SUBSYSTEMS), m.keySet());
        assertEquals(Owner.VANILLA, r.owner("no-such-subsystem"));
        assertTrue(new HashMap<>(m).size() == CavaConfig.SUBSYSTEMS.size());
    }
}
