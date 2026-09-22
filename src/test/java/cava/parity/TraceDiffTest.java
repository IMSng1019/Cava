package cava.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 离线比对器：零差异要打印 ZERO DIFF，有差异要指到首个差异 tick 与字段。 */
class TraceDiffTest {

    @TempDir
    Path tmp;

    private Path write(String name, List<String> lines) throws IOException {
        Path p = tmp.resolve(name);
        Files.write(p, lines, StandardCharsets.UTF_8);
        return p;
    }

    private static String header(String label, boolean nativeOn) {
        return "{\"t\":\"h\",\"v\":1,\"label\":\"" + label + "\",\"native\":" + nativeOn
                + ",\"mods\":\"deadbeefdeadbeef\",\"mc\":\"1.20.4\",\"cava\":\"0.1.0\",\"seed\":1,\"startTick\":0}";
    }

    private static String tick(long k, String w) {
        return "{\"t\":\"k\",\"k\":" + k + ",\"w\":\"" + w + "\",\"e\":null,\"p\":null,\"bt\":null,\"nt\":null,\"x\":null}";
    }

    @Test
    void zeroDiff() throws IOException {
        List<String> a = List.of(header("java", false), tick(0, "0000000000000001"), tick(1, "0000000000000002"));
        List<String> b = List.of(header("native", true), tick(0, "0000000000000001"), tick(1, "0000000000000002"));
        TraceDiff.Result r = TraceDiff.diff(write("a.ndjson", a), write("b.ndjson", b));
        assertTrue(r.zeroDiff());
        assertEquals(2, r.ticksCompared());
        assertTrue(r.report().startsWith("ZERO DIFF over 2 ticks"));
    }

    @Test
    void findsFirstDiffTickAndField() throws IOException {
        List<String> a = List.of(header("java", false), tick(0, "0000000000000001"), tick(1, "0000000000000002"),
                tick(2, "0000000000000003"));
        List<String> b = List.of(header("native", true), tick(0, "0000000000000001"), tick(1, "00000000000000ff"),
                tick(2, "0000000000000003"));
        TraceDiff.Result r = TraceDiff.diff(write("a.ndjson", a), write("b.ndjson", b));
        assertFalse(r.zeroDiff());
        assertEquals(1, r.firstDiffTick());
        assertEquals(1L, r.diffCounts().get("w"));
        assertTrue(r.report().contains("首个差异 tick=1"));
        assertTrue(r.report().contains("w"));
    }

    @Test
    void detectsLineCountMismatch() throws IOException {
        List<String> a = List.of(header("java", false), tick(0, "0000000000000001"));
        List<String> b = List.of(header("native", true), tick(0, "0000000000000001"), tick(1, "0000000000000002"));
        TraceDiff.Result r = TraceDiff.diff(write("a.ndjson", a), write("b.ndjson", b));
        assertFalse(r.zeroDiff());
        assertTrue(r.report().contains("tick 行数不同（不含头行）: a=1 b=2"), r.report());
    }

    @Test
    void detectsHeaderMismatch() throws IOException {
        List<String> a = List.of("{\"t\":\"h\",\"v\":1,\"label\":\"x\",\"native\":false,\"mods\":\"1111\",\"mc\":\"1.20.4\",\"cava\":\"0.1.0\",\"seed\":1,\"startTick\":0}",
                tick(0, "0000000000000001"));
        List<String> b = List.of("{\"t\":\"h\",\"v\":1,\"label\":\"y\",\"native\":true,\"mods\":\"2222\",\"mc\":\"1.20.4\",\"cava\":\"0.1.0\",\"seed\":1,\"startTick\":0}",
                tick(0, "0000000000000001"));
        TraceDiff.Result r = TraceDiff.diff(write("a.ndjson", a), write("b.ndjson", b));
        assertFalse(r.zeroDiff());
        assertTrue(r.report().contains("mods 指纹不同"));
    }

    // ------------------------------------------------------------------
    // 头行驱动的排除规则（captain 的确定性结论要求）+ detail 差异定位
    // ------------------------------------------------------------------

    private static String headerMeta(String label, boolean nativeOn, String excl, String incl) {
        return "{\"t\":\"h\",\"v\":1,\"label\":\"" + label + "\",\"native\":" + nativeOn
                + ",\"mods\":\"deadbeefdeadbeef\",\"mc\":\"1.20.4\",\"cava\":\"0.1.0\",\"seed\":1,\"startTick\":0"
                + ",\"radius\":4,\"spawnExcl\":3,\"excl\":\"" + excl + "\",\"incl\":\"" + incl + "\""
                + ",\"hash\":\"dims=overworld;w=blocks;e=entity-pos-bits;p=navstate\"}";
    }

    private static String tickFull(long k, String w, String e) {
        return "{\"t\":\"k\",\"k\":" + k + ",\"w\":\"" + w + "\",\"e\":\"" + e
                + "\",\"p\":null,\"bt\":null,\"nt\":null,\"x\":null}";
    }

    @Test
    void headerMetaIsParsed() {
        TraceDiff.Header h = TraceDiff.parseHeader(headerMeta("x", true, "entities", ""));
        assertEquals("4", h.radius());
        assertEquals("3", h.spawnExcl());
        assertTrue(h.excludesEntities());
        assertTrue(h.excludes("entities"));
    }

    /** 契约 4.2 的默认：**e 不参与比对**（实体层本质不确定）；显式 --entities 才比。 */
    @Test
    void entitiesAreExcludedByDefault() throws IOException {
        List<String> a = List.of(headerMeta("a", false, "entities", ""), tickFull(0, "aaaa", "1111"));
        List<String> b = List.of(headerMeta("b", true, "entities", ""), tickFull(0, "aaaa", "2222"));
        TraceDiff.Result r = TraceDiff.diff(write("ea.ndjson", a), write("eb.ndjson", b));
        assertTrue(r.zeroDiff(), r.report());
        assertTrue(r.report().contains("ZERO DIFF over 1 ticks"), r.report());
        assertTrue(r.report().contains("e 未参与比对"), r.report());

        TraceDiff.Result forced = TraceDiff.diff(tmp.resolve("ea.ndjson"), tmp.resolve("eb.ndjson"), Boolean.TRUE);
        assertFalse(forced.zeroDiff(), "--entities 时必须报 e 的差异");
        assertEquals(1L, forced.diffCounts().get("e"));
    }

    /** 头行 incl=entities（脚本化合成场景采集时）⇒ e 参与比对。 */
    @Test
    void headerInclEnablesEntities() throws IOException {
        List<String> a = List.of(headerMeta("a", false, "", "entities"), tickFull(0, "aaaa", "1111"));
        List<String> b = List.of(headerMeta("b", true, "", "entities"), tickFull(0, "aaaa", "2222"));
        TraceDiff.Result r = TraceDiff.diff(write("ia.ndjson", a), write("ib.ndjson", b));
        assertFalse(r.zeroDiff());
        assertEquals(1L, r.diffCounts().get("e"));
    }

    /** 旧 trace（没有 excl/incl 键）也必须按契约默认排除 entities。 */
    @Test
    void legacyHeaderStillExcludesEntities() throws IOException {
        List<String> a = List.of(header("a", false), tickFull(0, "aaaa", "1111"));
        List<String> b = List.of(header("b", true), tickFull(0, "aaaa", "2222"));
        TraceDiff.Result r = TraceDiff.diff(write("la.ndjson", a), write("lb.ndjson", b));
        assertTrue(r.zeroDiff(), r.report());
    }

    /** detail-*.ndjson 必须把差异定位到**实体键 / 区块坐标**。 */
    @Test
    void detailLocalizesEntityAndChunk() throws IOException {
        write("da.ndjson", List.of(headerMeta("da", false, "entities", ""), tickFull(0, "aaaa", "1111"),
                tickFull(1, "bbbb", "1111")));
        write("db.ndjson", List.of(headerMeta("db", true, "entities", ""), tickFull(0, "aaaa", "1111"),
                tickFull(1, "cccc", "1111")));
        write("detail-da.ndjson", List.of("{\"t\":\"h-detail\"}",
                "{\"t\":\"d\",\"k\":1,\"ent\":[[\"minecraft:zombie#45\",\"1111\",\"(200.50,64.00,-3.50)\"]],"
                        + "\"paths\":[],\"dc\":[[\"minecraft:overworld:4,-1\",\"dead\"]]}"));
        write("detail-db.ndjson", List.of("{\"t\":\"h-detail\"}",
                "{\"t\":\"d\",\"k\":1,\"ent\":[[\"minecraft:zombie#45\",\"9999\",\"(200.60,64.00,-3.50)\"]],"
                        + "\"paths\":[],\"dc\":[[\"minecraft:overworld:4,-1\",\"beef\"]]}"));

        TraceDiff.Result r = TraceDiff.diff(tmp.resolve("da.ndjson"), tmp.resolve("db.ndjson"));
        assertFalse(r.zeroDiff());
        assertEquals(1, r.firstDiffTick());
        String rep = r.report();
        assertTrue(rep.contains("minecraft:zombie#45"), rep);
        assertTrue(rep.contains("minecraft:overworld:4,-1"), rep);
        assertTrue(rep.contains("(200.50,64.00,-3.50)"), rep);
    }

    /** 没有 detail 文件时必须**明说**不能定位，而不是假装定位到了。 */
    @Test
    void missingDetailIsReportedHonestly() throws IOException {
        List<String> a = List.of(headerMeta("na", false, "entities", ""), tickFull(0, "aaaa", "1111"));
        List<String> b = List.of(headerMeta("nb", true, "entities", ""), tickFull(0, "bbbb", "1111"));
        TraceDiff.Result r = TraceDiff.diff(write("na.ndjson", a), write("nb.ndjson", b));
        assertFalse(r.zeroDiff());
        assertTrue(r.report().contains("没有 detail 明细文件"), r.report());
    }

    @Test
    void parsesNullsAndNumbers() {
        String line = tick(7, "abc");
        TraceDiff.Tick t = TraceDiff.parseTick(line);
        assertEquals(7, t.k());
        assertEquals("abc", t.w());
        assertNull(t.e());
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }
}
