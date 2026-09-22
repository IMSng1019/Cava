package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.CavaConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.IOException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验收项：对 {@code 优化模组/服务端模组/} 里的<b>真实 jar</b> 做探测（反射 + jar 元数据 + class 文件），
 * 并把<b>实际探测结果</b>打进报告。
 *
 * <p>没有整合包时整类跳过（CI 也能过），不会变成假绿。
 */
class RealModpackProbeTest {

    private Path tmp;

    @BeforeEach
    void setUpTemp() throws IOException {
        tmp = TestPaths.tempDir("real-modpack");
    }

    @Test
    void reportFromRealModpackJars() throws Exception {
        Path dir = TestPaths.modpackDir().orElse(null);
        Assumptions.assumeTrue(dir != null, "本机没有 优化模组/服务端模组，跳过真实 jar 探测");
        List<ModInfo> mods = ModProbe.fromDirectory(dir);
        assertTrue(mods.size() >= 40, "探测到的 mod 太少: " + mods.size());

        CavaConfig cfg = CavaConfig.load(tmp.resolve("cava.json"));
        CompatReport r = CompatReport.evaluate(cfg, mods, List.of(), LithiumOptions.SHIPPED,
                "1.20.4", "0.19.5", "OPEN", 6);

        System.out.println("===== CAVA 真实整合包探测报告（测试输出，非游戏日志）=====");
        for (String line : r.lines()) {
            System.out.println(line);
        }
        System.out.println("===== 报告结束 =====");

        String header = r.lines().get(0);
        assertTrue(header.contains("|mods=" + mods.size() + "|"), header);
        assertTrue(header.contains("|relevant=5|"), "5 个相关 mod 都应被探到: " + header);
        assertTrue(header.contains("|overlaps=" + CompatTable.OVERLAPS.size() + "|"), header);
        assertTrue(header.contains("|defer=entity,redstone|"), header);
        // rules 是 header 的最后一个字段，没有结尾竖线
        assertTrue(header.endsWith("|rules=0/" + ConsistencyRule.ALL.size()), header);

        // 适配器必须真的读到了 jar，而不是"未安装/无法探测"
        String scLine = r.lines().stream().filter(l -> l.startsWith("CAVA-COMPAT|v1|adapter|servercore|"))
                .findFirst().orElseThrow();
        assertTrue(scLine.contains("可用"), scLine);
        assertTrue(scLine.contains("isInactive@ActivationEntity=true"), scLine);
        assertTrue(scLine.contains("isInactive@Inactive=false"), scLine);
        assertTrue(scLine.contains("addVelocity@refmap=true"), scLine);
        String vmpLine = r.lines().stream().filter(l -> l.startsWith("CAVA-COMPAT|v1|adapter|vmp|"))
                .findFirst().orElseThrow();
        assertTrue(vmpLine.contains("无条件生效"), vmpLine);
        assertTrue(vmpLine.contains("move=true,setBoundingBox=true"), vmpLine);

        // 五个相关 mod 都有 mod 行 + lithium 四组都有 lithium 行
        for (String id : CompatTable.RELEVANT_MODS) {
            assertTrue(r.lines().stream().anyMatch(l -> l.startsWith("CAVA-COMPAT|v1|mod|" + id + "|")),
                    "缺 mod 行: " + id);
        }
        assertEquals(LithiumOptions.CANDIDATE_GROUPS.size(),
                r.lines().stream().filter(l -> l.startsWith("CAVA-COMPAT|v1|lithium|")).count());
        assertTrue(r.lithiumInconsistencies().isEmpty());
        assertFalse(r.warnings().stream().anyMatch(w -> w.startsWith("LITHIUM_METADATA_MISMATCH")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("CARPET_RULES_ABSENT")),
                "本机没有规则快照时必须诚实报「未找到」，不能默认成「规则都是默认值」");

        // 探测结论落盘一份（真实证据，供 docs 引用）
        Path out = tmp.resolve("real-modpack-report.txt");
        Files.writeString(out, r.text());
        assertTrue(Files.size(out) > 0);
    }

    @Test
    void serverCoreAndVmpSchemaAgainstRealJars() {
        Path sc = TestPaths.modpackJar("servercore").orElse(null);
        Path vmp = TestPaths.modpackJar("vmp").orElse(null);
        Assumptions.assumeTrue(sc != null && vmp != null, "本机没有 servercore / vmp jar，跳过");

        ServerCoreAdapter.Schema s = ServerCoreAdapter.probeJar(sc).orElseThrow();
        System.out.println("[probe] servercore schema: " + s.describe());
        assertEquals(List.of("servercore$inactiveTick"), s.inactiveInterfaceMethods());
        assertTrue(s.isInactiveOnActivationEntity());

        VmpAdapter.Schema v = VmpAdapter.probeJar(vmp).orElseThrow();
        System.out.println("[probe] vmp schema: " + v.describe());
        assertTrue(v.unconditional());
        assertEquals("com.ishland.vmp.mixins.entity.move_zero_velocity.MixinEntity",
                VmpAdapter.MIXIN_ENTITY_CLASS);
    }
}
