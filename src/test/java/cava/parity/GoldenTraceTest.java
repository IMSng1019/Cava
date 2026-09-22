package cava.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NDJSON 往返：写出去的行必须能被 TraceDiff 原样读回来（契约 4.2 的键必须都在）。 */
class GoldenTraceTest {

    @TempDir
    Path tmp;

    @Test
    void headerAndTicksRoundTrip() throws IOException {
        try (GoldenTrace t = GoldenTrace.open(tmp, "unit", "aabbccdd00112233", "1.20.4", "0.1.0", 12345L, 0L, true)) {
            t.tick(0, "0123456789abcdef", null, null, null, null, null);
            t.tick(1, "fedcba9876543210", null, null, 3L, 7L, null);
            t.flush();
            assertEquals(3, t.lineCount());
        }
        Path file = tmp.resolve("trace-unit.ndjson");
        assertTrue(Files.isRegularFile(file));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());

        TraceDiff.Header h = TraceDiff.parseHeader(lines.get(0));
        assertEquals("unit", h.label());
        assertEquals("aabbccdd00112233", h.mods());
        assertEquals("1.20.4", h.mc());
        assertEquals("0.1.0", h.cava());
        assertEquals("true", h.nativeOn());
        assertEquals("12345", h.seed());
        assertEquals("0", h.startTick());

        TraceDiff.Tick t0 = TraceDiff.parseTick(lines.get(1));
        assertEquals(0, t0.k());
        assertEquals("0123456789abcdef", t0.w());
        assertNull(t0.e());
        assertNull(t0.p());
        assertNull(t0.bt());
        assertNull(t0.nt());
        assertNull(t0.x());

        TraceDiff.Tick t1 = TraceDiff.parseTick(lines.get(2));
        assertEquals(1, t1.k());
        assertEquals("fedcba9876543210", t1.w());
        assertEquals("3", t1.bt());
        assertEquals("7", t1.nt());
        // 键必须都在（缺省即 null，但键不能少）
        for (String key : new String[]{"t", "k", "w", "e", "p", "bt", "nt", "x"}) {
            assertTrue(lines.get(2).contains("\"" + key + "\":"), "缺少键 " + key + ": " + lines.get(2));
        }
    }

    @Test
    void labelIsSanitizedForFileName() throws IOException {
        try (GoldenTrace t = GoldenTrace.open(tmp, "native on/off", "", "1.20.4", "0.1.0", 0L, 0L, false)) {
            t.tick(0, null, null, null, null, null, null);
        }
        assertTrue(Files.isRegularFile(tmp.resolve("trace-native_on_off.ndjson")));
    }

    @Test
    void jsonEscaping() {
        assertEquals("\"a\\\"b\\nc\"", GoldenTrace.json("a\"b\nc"));
    }
}
