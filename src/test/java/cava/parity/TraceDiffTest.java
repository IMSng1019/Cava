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
