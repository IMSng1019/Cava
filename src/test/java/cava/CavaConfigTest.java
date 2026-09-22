package cava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** config/cava.json 的读写、默认值与容错（任务 C6：P0 只要「能读写 + 能打印」）。 */
class CavaConfigTest {

    @TempDir
    Path tmp;

    @Test
    void writesDefaultsWhenMissing() throws IOException {
        Path file = tmp.resolve("config").resolve("cava.json");
        CavaConfig c = CavaConfig.load(file);
        assertTrue(Files.isRegularFile(file), "缺失时应写默认值");
        assertTrue(c.nativeEnabled());
        assertEquals(CavaConfig.Ownership.AUTO, c.ownership("pathfind"));
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"native\""));
        assertTrue(json.contains("\"ownership\""));
        assertTrue(json.contains("\"pathfind\": \"auto\""));
    }

    @Test
    void roundTrip() throws IOException {
        Path file = tmp.resolve("cava.json");
        Files.writeString(file, """
                {
                  "native": { "enabled": false },
                  "ownership": { "pathfind": "native-first", "entity": "defer", "redstone": "auto" },
                  "parity": { "trace": "out/traces", "ticks": 6000, "label": "native-on", "worldRadius": 4 }
                }
                """, StandardCharsets.UTF_8);
        CavaConfig c = CavaConfig.load(file);
        assertFalse(c.nativeEnabled());
        assertEquals(CavaConfig.Ownership.NATIVE_FIRST, c.ownership("pathfind"));
        assertEquals(CavaConfig.Ownership.DEFER, c.ownership("entity"));
        assertEquals(CavaConfig.Ownership.AUTO, c.ownership("redstone"));
        assertEquals(6000, c.parityTicks());
        assertEquals("out/traces", c.parityTrace());
        assertEquals("native-on", c.parityLabel());
        assertEquals(4, c.parityWorldRadius());

        // 写回去再读一遍必须一致
        c.save();
        CavaConfig again = CavaConfig.load(file);
        assertFalse(again.nativeEnabled());
        assertEquals(CavaConfig.Ownership.NATIVE_FIRST, again.ownership("pathfind"));
        assertEquals(6000, again.parityTicks());
    }

    @Test
    void malformedJsonFallsBackToDefaults() throws IOException {
        Path file = tmp.resolve("broken.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        CavaConfig c = CavaConfig.load(file);
        assertTrue(c.nativeEnabled());
        assertTrue(c.loadNote().contains("失败"));
    }

    @Test
    void parsesNestedObjectsAndScalars() {
        Map<String, Object> m = CavaConfig.parseObject("{\"a\":{\"b\":true},\"c\":-12,\"d\":\"x\\u0041\",\"e\":null}");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) m.get("a")).get("b"));
        assertEquals(-12L, m.get("c"));
        assertEquals("xA", m.get("d"));
        assertTrue(m.containsKey("e"));
        assertThrows(IllegalArgumentException.class, () -> CavaConfig.parseObject("{\"a\":1} trailing"));
    }

    @Test
    void ownershipParsing() {
        assertEquals(CavaConfig.Ownership.NATIVE_FIRST, CavaConfig.Ownership.parse("native-first"));
        assertEquals(CavaConfig.Ownership.DEFER, CavaConfig.Ownership.parse("DEFER"));
        assertEquals(CavaConfig.Ownership.AUTO, CavaConfig.Ownership.parse("随便"));
        assertEquals("native-first", CavaConfig.Ownership.NATIVE_FIRST.jsonName());
    }
}
