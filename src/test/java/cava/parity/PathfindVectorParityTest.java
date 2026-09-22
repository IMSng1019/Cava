package cava.parity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaNative;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 单元层差分的 **CI 入口**（{@code gradlew test} 自动收集，不需要改 build.gradle）：
 * 「oracle 参照实现 vs 原生内核（经 Java FFM → C ABI）」必须逐位一致。
 *
 * <p>没有原生库时整类 skip（与 {@code cava.ffm.PathfindAbiTest} 同策略）：
 * 构建脚本在 {@code natives/windows-x64/cava.dll} 存在时会自动打开原生并指过去，
 * 所以"绿"意味着真的测过；没有 dll 时 skip 是显式的，不会伪装成通过。
 *
 * <p>组数可用 {@code -Dcava.parity.vectors.max=N} 调小（快速回归）。
 * 完整 10000 组在本机实测约 4 秒（含 JVM 启动）。
 */
class PathfindVectorParityTest {

    private static Path shard;

    @BeforeAll
    static void locateVectors() throws URISyntaxException {
        var url = PathfindVectorParityTest.class.getResource("/cava/oracle/vectors-00.bin");
        Assumptions.assumeTrue(url != null, "测试资源里没有 cava/oracle/vectors-00.bin");
        shard = Paths.get(url.toURI());
        Assumptions.assumeTrue(CavaNative.get().tryOpen() || true, "native 状态不影响 assumption 判定");
    }

    private static int maxCases() {
        return Integer.getInteger("cava.parity.vectors.max", 10000);
    }

    @Test
    void zeroDiffOverVectors() throws IOException {
        CavaNative nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "test");
        nat.setConfiguredEnabled(true);
        Assumptions.assumeTrue(nat.tryOpen() && nat.available(),
                "需要原生库（-Dcava.native.path=<cava.dll>）：当前 status=" + nat.status() + " " + nat.detail());

        PathfindVectorDiff.Result r = PathfindVectorDiff.run(shard, maxCases(), true, false);
        assertTrue(r.zeroDiff(), () -> "差分非零：\n" + r.report());
        assertTrue(r.report().startsWith("=== Cava 单元层差分")
                        && r.report().contains("ZERO DIFF over " + r.casesRun + " cases"),
                () -> "报告必须明确说零：\n" + r.report());
        assertTrue(r.casesRun > 0, "至少要跑一组");
    }

    /**
     * 负控制：把 CAN_SWIM 填到错误的 caps 位（= 本项目真实发生过并已修的喂入 bug）时，
     * 比对器**必须**报差异。没有这条，"ZERO DIFF" 可能只是比对器瞎了。
     */
    @Test
    void negativeControlDetectsWrongCapBit() throws IOException {
        CavaNative nat = CavaNative.get();
        Assumptions.assumeTrue(nat.tryOpen() && nat.available(), "需要原生库");
        String key = "cava.parity.selftest.wrongCaps";
        int cases = Math.min(maxCases(), 2000);
        try {
            System.setProperty(key, "true");
            PathfindVectorDiff.Result r = PathfindVectorDiff.run(shard, cases, false, false);
            assertFalse(r.zeroDiff(), "错误喂入竟然零差异 ⇒ 比对器不可信");
            assertNotNull(r.nativeMismatches.get(0).field());
            assertTrue(r.nativeMismatches.size() > 0, "必须报出差异条目");
        } finally {
            System.clearProperty(key);
        }
    }
}
