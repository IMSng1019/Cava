package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** mod 探测：从真实 jar 的 fabric.mod.json 读，不硬编码。 */
class ModProbeTest {

    private Path tmp;

    @BeforeEach
    void setUpTemp() throws IOException {
        tmp = TestPaths.tempDir("modprobe");
    }

    private Path writeJar(String name, String fabricModJson) throws IOException {
        Path jar = tmp.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            if (fabricModJson != null) {
                out.putNextEntry(new ZipEntry("fabric.mod.json"));
                out.write(fabricModJson.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("some/Other.class"));
            out.write(new byte[]{0});
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void readsMetadataFromRealJarLayout() throws IOException {
        Path jar = writeJar("fake-lithium.jar", """
                {"schemaVersion":1,"id":"lithium","version":"0.12.1","environment":"*",
                 "mixins":["lithium.mixins.json"],
                 "custom":{"lithium:options":{"mixin.alloc.chunk_ticking":false}}}
                """);
        Optional<ModInfo> m = ModProbe.fromJar(jar);
        assertTrue(m.isPresent());
        assertEquals("lithium", m.get().id());
        assertEquals("0.12.1", m.get().version());
        assertEquals(List.of("lithium.mixins.json"), m.get().mixins());
        assertTrue(m.get().hasCustom("lithium:options"));
        assertTrue(m.get().customValue("lithium:options") instanceof Map);
    }

    @Test
    void skipsJarsWithoutMetadata() throws IOException {
        writeJar("no-metadata.jar", null);
        writeJar("broken.jar", "{ not json");
        assertTrue(ModProbe.fromJar(tmp.resolve("no-metadata.jar")).isEmpty());
        assertTrue(ModProbe.fromJar(tmp.resolve("broken.jar")).isEmpty());
        assertTrue(ModProbe.fromDirectory(tmp).isEmpty());
    }

    @Test
    void directoryScanIsSortedAndSkipsGarbage() throws IOException {
        writeJar("b.jar", "{\"id\":\"zeta\",\"version\":\"2\"}");
        writeJar("a.jar", "{\"id\":\"alpha\",\"version\":\"1\"}");
        List<ModInfo> mods = ModProbe.fromDirectory(tmp);
        assertEquals(List.of("alpha", "zeta"), ModProbe.ids(mods));
        assertEquals("alpha", ModProbe.find(mods, "alpha").orElseThrow().id());
        assertTrue(ModProbe.find(mods, "nope").isEmpty());
        assertTrue(ModProbe.fromDirectory(tmp.resolve("missing")).isEmpty());
    }

    @Test
    void realModpackIsProbedNotHardcoded() {
        Path dir = TestPaths.modpackDir().orElse(null);
        Assumptions.assumeTrue(dir != null, "本机没有整合包目录，跳过");
        List<ModInfo> mods = ModProbe.fromDirectory(dir);
        assertTrue(mods.size() >= 40, "49 个 jar 里至少有 40 个带 fabric.mod.json，实测 " + mods.size());
        // 逐个断言"真实存在的 id + 版本"，任何一条不符就是整合包变了
        assertEquals("0.12.1", ModProbe.find(mods, "lithium").orElseThrow().version());
        assertEquals("1.5.0+1.20.4", ModProbe.find(mods, "servercore").orElseThrow().version());
        assertEquals("0.2.0+beta.7.139", ModProbe.find(mods, "vmp").orElseThrow().version());
        assertEquals("1.4.128+v231205", ModProbe.find(mods, "carpet").orElseThrow().version());
        assertEquals("1.82.3", ModProbe.find(mods, "carpet-tis-addition").orElseThrow().version());
        // ServerCore 自己也用 lithium:options 关掉了一组 —— 这就是机制真实可用的旁证
        ModInfo sc = ModProbe.find(mods, "servercore").orElseThrow();
        assertTrue(sc.hasCustom("lithium:options"), "ServerCore 的元数据里应当有 custom.lithium:options");
        assertTrue(LithiumOptions.isDisabled("mixin.alloc.chunk_ticking", LithiumOptions.of(sc)),
                "ServerCore 把 mixin.alloc.chunk_ticking 置为 false，应当被读成 disabled=true");
        ModInfo vmp = ModProbe.find(mods, "vmp").orElseThrow();
        assertTrue(vmp.hasCustom("lithium:options"), "VMP 元数据里也有 custom.lithium:options（空对象）");
    }

    @Test
    void parseObjectRejectsTrailingGarbage() {
        assertThrows(IllegalArgumentException.class, () -> ModProbe.fromJson("{} junk", "x"));
    }
}
