package cava.compat;

import cava.CavaConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;

/**
 * mod 元数据探测：<b>一律从对方的 {@code fabric.mod.json} 读</b>，不硬编码任何版本/存在性假设。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #fromLoader()} —— 真实游戏里，从 Fabric Loader 的<b>生效元数据</b>读（含嵌套 jar）。</li>
 *   <li>{@link #fromDirectory(Path)} —— 离线：扫一个目录里的所有 jar，逐个读 zip 里的
 *       {@code fabric.mod.json}。单测与"没启动游戏也能出报告"用这条。</li>
 * </ul>
 *
 * <p>本类不引用任何 Minecraft 类型；{@link #fromLoader()} 是唯一接触 Fabric Loader 的方法，
 * 所以纯离线单测可以只调用 {@link #fromDirectory}。
 */
public final class ModProbe {

    /** mod 元数据文件名（Fabric 规定）。 */
    public static final String METADATA_FILE = "fabric.mod.json";

    private ModProbe() {
    }

    /** 离线探测：目录里每个 jar 的 {@code fabric.mod.json}。返回按 id 排序的列表。 */
    public static List<ModInfo> fromDirectory(Path dir) {
        List<ModInfo> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                jars.add(p);
            }
        } catch (IOException e) {
            return out;
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path jar : jars) {
            fromJar(jar).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(ModInfo::id));
        return out;
    }

    /** 离线探测单个 jar；没有 {@code fabric.mod.json} 或解析失败 → empty（不抛）。 */
    public static Optional<ModInfo> fromJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(METADATA_FILE);
            if (e == null) {
                return Optional.empty();
            }
            String json;
            try (InputStream in = zip.getInputStream(e)) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return Optional.of(fromJson(json, jar.toString()));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** 解析一份 {@code fabric.mod.json} 文本。 */
    public static ModInfo fromJson(String json, String source) {
        Map<String, Object> root = CavaConfig.parseObject(json);
        String id = asString(root.get("id"), "(无 id)");
        String version = asString(root.get("version"), "(无 version)");
        String env = asString(root.get("environment"), "*");
        List<String> mixins = new ArrayList<>();
        Object mix = root.get("mixins");
        if (mix instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    mixins.add(s);
                } else if (o instanceof Map<?, ?> m && m.get("config") instanceof String s) {
                    mixins.add(s);
                }
            }
        }
        Map<String, Object> custom = new LinkedHashMap<>();
        if (root.get("custom") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> en : m.entrySet()) {
                custom.put(String.valueOf(en.getKey()), en.getValue());
            }
        }
        return new ModInfo(id, version, env, mixins, custom, source);
    }

    /** 真实游戏：Fabric Loader 的全部已加载 mod（含嵌套 jar 的元数据）。 */
    public static List<ModInfo> fromLoader() {
        List<ModInfo> out = new ArrayList<>();
        try {
            Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
            for (ModContainer c : mods) {
                out.add(fromContainer(c));
            }
        } catch (RuntimeException | LinkageError e) {
            return out;
        }
        out.sort(Comparator.comparing(ModInfo::id));
        return out;
    }

    private static ModInfo fromContainer(ModContainer c) {
        ModMetadata md = c.getMetadata();
        List<String> mixins = new ArrayList<>();
        Map<String, Object> custom = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, CustomValue> e : md.getCustomValues().entrySet()) {
                custom.put(e.getKey(), toJava(e.getValue()));
            }
        } catch (RuntimeException ignored) {
            // 元数据不可读时留空表：报告会走"未探测到"分支
        }
        // mixins 列表只有原始 JSON 里才有；尝试通过 findPath 读一次，失败就留空
        try {
            Optional<Path> p = c.findPath(METADATA_FILE);
            if (p.isPresent() && Files.isRegularFile(p.get())) {
                Map<String, Object> root = CavaConfig.parseObject(Files.readString(p.get(), StandardCharsets.UTF_8));
                Object mix = root.get("mixins");
                if (mix instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof String s) {
                            mixins.add(s);
                        } else if (o instanceof Map<?, ?> m && m.get("config") instanceof String s) {
                            mixins.add(s);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // 读不到就算了：mixins 列表不是裁决必需项
        }
        String env = md.getEnvironment() == net.fabricmc.loader.api.metadata.ModEnvironment.UNIVERSAL
                ? "*"
                : md.getEnvironment().name().toLowerCase(java.util.Locale.ROOT);
        // source 记真实路径（jar 或目录），兼容层的适配器要靠它离线读 class 文件
        String source = "loader";
        try {
            List<Path> roots = c.getRootPaths();
            if (roots != null && !roots.isEmpty() && roots.get(0) != null) {
                source = roots.get(0).toString();
            }
        } catch (RuntimeException ignored) {
            // 嵌套 jar / 不可解路径：退化成 "loader"
        }
        return new ModInfo(md.getId(), md.getVersion().getFriendlyString(), env, mixins, custom, source);
    }

    /** Cava 自己的元数据（用来核对 {@code custom.lithium:options} 的<b>实际发布值</b>）。 */
    public static Optional<ModInfo> ownMetadata() {
        try {
            return FabricLoader.getInstance().getModContainer("cava").map(ModProbe::fromContainer);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static Object toJava(CustomValue v) {
        if (v == null) {
            return null;
        }
        return switch (v.getType()) {
            case STRING -> v.getAsString();
            case NUMBER -> v.getAsNumber();
            case BOOLEAN -> v.getAsBoolean();
            case OBJECT -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<String, CustomValue> e : v.getAsObject()) {
                    m.put(e.getKey(), toJava(e.getValue()));
                }
                yield m;
            }
            case ARRAY -> {
                List<Object> l = new ArrayList<>();
                for (CustomValue c : v.getAsArray()) {
                    l.add(toJava(c));
                }
                yield l;
            }
            case NULL -> null;
        };
    }

    private static String asString(Object o, String fallback) {
        return o instanceof String s ? s : fallback;
    }

    /** 从 mod 列表里找某个 id。 */
    public static Optional<ModInfo> find(Collection<ModInfo> mods, String id) {
        for (ModInfo m : mods) {
            if (m.id().equals(id)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** 从 mod 列表里取全部 id。 */
    public static List<String> ids(Collection<ModInfo> mods) {
        List<String> out = new ArrayList<>(mods.size());
        for (ModInfo m : mods) {
            out.add(m.id());
        }
        return out;
    }
}
