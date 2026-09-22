package cava.ffm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 原生库的定位 / 解压 / 哈希命名 / 加载。
 *
 * <p>资源路径 = {@code natives/<系统-架构>/<库文件名>}，例如 {@code natives/windows-x64/cava.dll}。
 * 解压到 {@code <游戏目录>/cava/natives/<mod版本>/<系统-架构>/}，文件名带内容哈希：
 * {@code cava-<sha256前16位>.dll}。写入用「临时文件 + 原子改名」，已存在同哈希文件直接复用。
 *
 * <p>加载只用 {@link System#load(String)}（绝对路径）——**不用 System.loadLibrary**，
 * 避免污染 {@code java.library.path} 语义（契约 C1）。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class NativeLibrary {

    private NativeLibrary() {
    }

    /** 开发用：直接指定一个库文件（跳过从 jar/classpath 读取），仍会走哈希命名 + 原子解压。 */
    public static final String PROP_LIBRARY_PATH = "cava.native.path";

    /** 解压根目录覆盖；默认 {@code <user.dir>/cava/natives}（见 {@link #defaultNativesRoot()}）。 */
    public static final String PROP_NATIVES_DIR = "cava.native.dir";

    /** 资源根目录（= CMake 输出目录名，契约 C1）。 */
    public static final String RESOURCE_ROOT = "natives/";

    /** 平台目录名 —— 必须与 CMake/native 侧的输出目录名一致。 */
    public static final String PLATFORM_DIR = platformDir();

    /** 库文件名 —— 必须与 CMake 的输出文件名一致。 */
    public static final String LIBRARY_FILE_NAME = libraryFileName();

    /** 完整资源路径。 */
    public static final String RESOURCE_PATH = RESOURCE_ROOT + PLATFORM_DIR + "/" + LIBRARY_FILE_NAME;

    /** 加载失败的载体：状态 + 人话原因。 */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public final NativeStatus status;

        public Failure(NativeStatus status, String message) {
            super(message);
            this.status = status;
        }

        public Failure(NativeStatus status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }
    }

    /** 解压（或复用）结果。 */
    public record Prepared(Path path, String sha256, long size, boolean reused, String source) {
        public String fileName() {
            return path.getFileName().toString();
        }

        public String shortHash() {
            return sha256.substring(0, 16);
        }
    }

    /** {@code windows-x64} / {@code linux-x64} / …，无法识别时返回 {@code unknown-<os>-<arch>}。 */
    public static String platformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osPart;
        if (os.contains("win")) {
            osPart = "windows";
        } else if (os.contains("linux")) {
            osPart = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osPart = "macos";
        } else {
            osPart = "unknown-" + os.replaceAll("[^a-z0-9]+", "");
        }
        String archPart;
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            archPart = "x64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archPart = "arm64";
        } else {
            archPart = arch.replaceAll("[^a-z0-9]+", "");
        }
        return osPart + "-" + archPart;
    }

    /** Windows {@code cava.dll} / Linux {@code libcava.so} / macOS {@code libcava.dylib}。 */
    public static String libraryFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "cava.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "libcava.dylib";
        }
        return "libcava.so";
    }

    /** 默认解压根：{@code <user.dir>/cava/natives}（服务端 run 目录 = 进程工作目录，故不依赖 MC 类型）。 */
    public static Path defaultNativesRoot() {
        String override = System.getProperty(PROP_NATIVES_DIR, "").trim();
        if (!override.isEmpty()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", "."), "cava", "natives").toAbsolutePath().normalize();
    }

    /**
     * 取库字节 → 算 sha256 → 解压到 {@code <nativesRoot>/<modVersion>/<platform>/cava-<sha16>.<ext>}。
     * 已存在且「长度 + 哈希」都对 → 直接复用。
     */
    public static Prepared prepare(Path nativesRoot, String modVersion, Consumer<String> log) throws Failure {
        String sourceOverride = System.getProperty(PROP_LIBRARY_PATH, "").trim();
        byte[] data;
        String source;
        if (!sourceOverride.isEmpty()) {
            Path from = Path.of(sourceOverride).toAbsolutePath().normalize();
            if (!Files.isRegularFile(from)) {
                throw new Failure(NativeStatus.RESOURCE_MISSING,
                        "-D" + PROP_LIBRARY_PATH + "=" + from + " 不是文件");
            }
            try {
                data = Files.readAllBytes(from);
            } catch (IOException e) {
                throw new Failure(NativeStatus.EXTRACT_FAILED, "读取 " + from + " 失败: " + e, e);
            }
            source = "file:" + from;
        } else {
            InputStream in = NativeLibrary.class.getResourceAsStream("/" + RESOURCE_PATH);
            if (in == null) {
                throw new Failure(NativeStatus.RESOURCE_MISSING,
                        "jar/classpath 里没有资源 /" + RESOURCE_PATH + "（用 -D" + PROP_LIBRARY_PATH + "=<文件> 可绕过）");
            }
            try (InputStream stream = in) {
                data = stream.readAllBytes();
            } catch (IOException e) {
                throw new Failure(NativeStatus.EXTRACT_FAILED, "读取资源 /" + RESOURCE_PATH + " 失败: " + e, e);
            }
            source = "resource:/" + RESOURCE_PATH;
        }
        if (data.length == 0) {
            throw new Failure(NativeStatus.RESOURCE_MISSING, source + " 是空文件");
        }

        String sha = sha256Hex(data);
        String hashedName = hashedFileName(LIBRARY_FILE_NAME, sha);
        Path dir = nativesRoot.resolve(modVersion == null || modVersion.isBlank() ? "0.0.0" : modVersion)
                .resolve(PLATFORM_DIR);
        Path target = dir.resolve(hashedName);

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new Failure(NativeStatus.EXTRACT_FAILED, "创建目录 " + dir + " 失败: " + e, e);
        }

        boolean reused = false;
        if (Files.isRegularFile(target)) {
            try {
                byte[] existing = Files.readAllBytes(target);
                if (existing.length == data.length && sha256Hex(existing).equals(sha)) {
                    reused = true;
                    log.accept("[native] 复用已解压库 " + target + " (len=" + data.length + ", sha256=" + sha.substring(0, 16) + "…)");
                } else {
                    log.accept("[native] 已存在同名文件但内容不符（len=" + existing.length + "/" + data.length + "），重新解压 " + target);
                }
            } catch (IOException e) {
                log.accept("[native] 校验已存在的 " + target + " 失败（" + e + "），重新解压");
            }
        }

        if (!reused) {
            // 临时文件名带 pid，避免同一台机器上两个 JVM 同时解压时互相踩
            Path tmp = dir.resolve(hashedName + "." + ProcessHandle.current().pid() + ".tmp");
            try {
                Files.write(tmp, data);
            } catch (IOException e) {
                throw new Failure(NativeStatus.EXTRACT_FAILED, "写临时文件 " + tmp + " 失败: " + e, e);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                log.accept("[native] 原子落盘 " + target + " (len=" + data.length + ", sha256=" + sha.substring(0, 16) + "…)");
            } catch (AtomicMoveNotSupportedException e) {
                log.accept("[native] ATOMIC_MOVE 不支持（" + e.getMessage() + "），回退 REPLACE_EXISTING");
                moveFallback(tmp, target);
            } catch (IOException e) {
                // Windows 上目标被占用 / 杀软扫描也会让 ATOMIC_MOVE 抛 IOException
                log.accept("[native] ATOMIC_MOVE 失败（" + e + "），回退 REPLACE_EXISTING");
                moveFallback(tmp, target);
            }
        }
        return new Prepared(target, sha, data.length, reused, source);
    }

    private static void moveFallback(Path tmp, Path target) throws Failure {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e2) {
            throw new Failure(NativeStatus.EXTRACT_FAILED, "改名 " + tmp + " -> " + target + " 失败: " + e2, e2);
        }
    }

    /** {@code cava.dll} + {@code <sha16>} → {@code cava-<sha16>.dll}。 */
    public static String hashedFileName(String base, String sha256Hex) {
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        return stem + "-" + sha256Hex.substring(0, 16) + ext;
    }

    /** {@link System#load(String)}，绝不抛出去。 */
    public static void load(Path absolutePath, Consumer<String> log) throws Failure {
        String abs = absolutePath.toAbsolutePath().normalize().toString();
        try {
            System.load(abs);
            log.accept("[native] System.load(" + abs + ") 成功");
        } catch (UnsatisfiedLinkError | SecurityException e) {
            throw new Failure(NativeStatus.LOAD_FAILED, "System.load(" + abs + ") 失败: " + e, e);
        }
    }

    /** SHA-256 十六进制小写。 */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
