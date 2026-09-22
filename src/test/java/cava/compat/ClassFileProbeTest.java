package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** class 文件探测器的自检：先用"本测试类自己的 class 文件"当样本，再对真实 jar 抽查。 */
class ClassFileProbeTest {

    @Test
    void readsOwnClassFile() throws IOException {
        byte[] bytes;
        try (InputStream in = ClassFileProbeTest.class.getResourceAsStream("ClassFileProbeTest.class")) {
            assertNotNull(in, "测试类自己的 class 必须在本进程的 classpath 上");
            bytes = in.readAllBytes();
        }
        ClassFileProbe.Info info = ClassFileProbe.read(bytes);
        assertEquals("cava/compat/ClassFileProbeTest", info.internalName());
        assertFalse(info.isInterface());
        assertTrue(info.declaresMethod("readsOwnClassFile"));
        assertFalse(info.declaresMethod("noSuchMethod"));
        assertTrue(info.major() >= 61, "JDK 17+ 的 class 版本至少是 61，实测 " + info.major());
    }

    @Test
    void readsClassFileFromJar() throws IOException {
        // 用 JDK 自己的 jar 当样本是可行的：本测试的 classpath 里必然有 jar 形式的依赖。
        // 这里改用"整合包里的真实 jar"做更强的断言（缺失时跳过，CI 无整合包也能过）。
        Path lithium = TestPaths.modpackJar("lithium").orElse(null);
        Assumptions.assumeTrue(lithium != null, "本机没有整合包 jar，跳过");
        List<String> classes = ClassFileProbe.listClasses(lithium);
        assertTrue(classes.contains("me/jellysquid/mods/lithium/common/config/LithiumConfig"));
        var info = ClassFileProbe.readFromJar(lithium, "me/jellysquid/mods/lithium/common/config/LithiumConfig");
        assertTrue(info.isPresent());
        assertTrue(info.get().declaresMethod("applyModOverrides"));
        assertTrue(info.get().hasConstantContaining("lithium:options"),
                "LithiumConfig 的常量池里必须有 lithium:options（官方覆盖机制的键）");
        assertTrue(ClassFileProbe.readFromJar(lithium, "no/such/Class").isEmpty());
        assertFalse(ClassFileProbe.hasClass(lithium, "no/such/Class"));
    }

    @Test
    void rejectsNonClassBytes() {
        try {
            ClassFileProbe.read(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
            throw new AssertionError("非 class 字节应当抛 IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }
    }
}
