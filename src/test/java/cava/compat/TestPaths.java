package cava.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 单测里找仓库内真实文件的小工具（不依赖运行时 cwd 恰好是项目根）。 */
final class TestPaths {

    private TestPaths() {
    }

    /**
     * 建一个临时目录，<b>放在工作区的 build/tmp 下</b>。
     *
     * <p>不用 JUnit 的 {@code @TempDir}：本机文件沙箱对 {@code java.io.tmpdir}
     * （实测 {@code C:\Users\郁小悟~1\AppData\Local\Temp\dsh-*} 这种 8.3 短名路径）
     * 会间歇性拒绝建目录，报 {@code AccessDeniedException: Failed to create default temp directory}。
     * 工作区内的目录是确定可写的。
     */
    static Path tempDir(String tag) throws IOException {
        Path base = find("build").map(p -> p.resolve("tmp")).orElseGet(() -> Path.of("build", "tmp"));
        Path dir = base.resolve("cava-compat-tests").resolve(tag + "-" + Long.toHexString(System.nanoTime()));
        Files.createDirectories(dir);
        return dir;
    }

    /** 从 cwd 往上找，直到某个祖先目录下存在给定相对路径。 */
    static Optional<Path> find(String relative) {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++) {
            Path p = cur.resolve(relative);
            if (Files.exists(p)) {
                return Optional.of(p);
            }
            cur = cur.getParent();
        }
        return Optional.empty();
    }

    /** 整合包服务端 mod 目录（不存在时返回空）。 */
    static Optional<Path> modpackDir() {
        return find("优化模组/服务端模组").filter(Files::isDirectory);
    }

    /** 源码里的 fabric.mod.json。 */
    static Optional<Path> fabricModJson() {
        return find("src/main/resources/fabric.mod.json").filter(Files::isRegularFile);
    }

    /** modpack 里的某个 jar（按文件名包含匹配）。 */
    static Optional<Path> modpackJar(String contains) {
        Optional<Path> dir = modpackDir();
        if (dir.isEmpty()) {
            return Optional.empty();
        }
        try (var ds = Files.newDirectoryStream(dir.get(), "*.jar")) {
            for (Path p : ds) {
                if (p.getFileName().toString().contains(contains)) {
                    return Optional.of(p);
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
