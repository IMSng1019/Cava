package cava.compat;

import cava.CavaConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Carpet / TIS 规则快照探测。
 *
 * <p><b>规则文件在哪</b>（本机 javap 证据）：{@code carpet.api.settings.SettingsManager.getFile()} 的字节码是
 * <pre>
 *   server.getSavePath(WorldSavePath.&lt;field_24188&gt;).resolve(identifier + ".conf")
 * </pre>
 * 即<b>存档目录</b>下的 {@code &lt;identifier&gt;.conf}。{@code WorldSavePath} 的映射已用
 * {@code tools/mapquery.cjs} 核过：{@code class_5218 = net/minecraft/util/WorldSavePath}。
 * TIS 通过 {@code carpettisaddition/mixins/carpet/hooks/*&#47;SettingsManagerMixin} 挂在 Carpet 的
 * SettingsManager 上，所以两者的规则都在同一批 {@code .conf} 里也可能各自一份 —— 探测时两处都读。
 *
 * <p><b>解析规则完全照抄字节码</b>（{@code SettingsManager.readSettingsFromConf}）：
 * <pre>
 *   line = line.replaceAll("[\r\n]", "")
 *   if (line.equalsIgnoreCase("locked")) locked = true
 *   parts = line.split("\\s+", 2)
 *   if (parts.length &lt;= 1) 跳过
 *   if (values.isEmpty() &amp;&amp; parts[0].startsWith("#")) 跳过
 *   if (parts[1].startsWith("#")) 跳过
 *   values.put(parts[0], parts[1])
 * </pre>
 * 用 {@code split(..., 2)} 意味着值里的空格会被保留 —— 这里必须逐条对齐，否则规则值会读错。
 *
 * <p><b>关于 {@code /testcarpet dump}</b>：它在本机<b>未验证</b>（没有找到该命令的确切输出格式证据）。
 * 所以离线快照只接受两种<b>Cava 自己定义</b>的简单格式：{@code config/cava/carpet-rules.json}
 * 与 {@code config/cava/carpet-rules.properties}（一行一个 {@code 规则名=值}）。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class CarpetRuleProbe {

    /** Carpet 自己的规则文件（存档目录下）。 */
    public static final String CARPET_CONF = "carpet.conf";

    /** TIS 可能用的规则文件（存在就读，不存在无害）。 */
    public static final String TIS_CONF = "carpet-tis-addition.conf";

    /** Cava 自定义的离线规则快照（JSON 对象：规则名 -> 值；也接受 {@code {"rules": {...}}}）。 */
    public static final String SNAPSHOT_JSON = "config/cava/carpet-rules.json";

    /** Cava 自定义的离线规则快照（properties：一行一个 {@code 规则名=值}）。 */
    public static final String SNAPSHOT_PROPERTIES = "config/cava/carpet-rules.properties";

    private CarpetRuleProbe() {
    }

    /** 一份规则快照。 */
    public record Snapshot(String label, Path file, Map<String, String> values, boolean locked, String note) {

        public Snapshot {
            values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public boolean absent() {
            return values.isEmpty();
        }
    }

    /** 一条规则的判定结果。 */
    public record Finding(ConsistencyRule rule, String value, String origin, boolean alarming) {

        public String describe() {
            return rule.name() + "=" + value + " origin=" + origin + " alarming=" + alarming;
        }
    }

    /**
     * 候选文件，按优先级排列（越靠前越优先）。
     *
     * @param gameDir   游戏根目录（可为 null）
     * @param extraFiles 配置里显式指定的规则文件（{@code compat.ruleFiles}）
     */
    public static List<Path> candidates(Path gameDir, List<String> extraFiles) {
        List<Path> out = new ArrayList<>();
        if (extraFiles != null) {
            for (String s : extraFiles) {
                if (s == null || s.isBlank()) {
                    continue;
                }
                Path p = Path.of(s.trim());
                out.add(p.isAbsolute() || gameDir == null ? p : gameDir.resolve(p));
            }
        }
        if (gameDir == null) {
            return out;
        }
        out.add(gameDir.resolve("config").resolve(CARPET_CONF));
        out.add(gameDir.resolve(CARPET_CONF));
        out.add(gameDir.resolve("config").resolve(TIS_CONF));
        out.add(gameDir.resolve(SNAPSHOT_JSON));
        out.add(gameDir.resolve(SNAPSHOT_PROPERTIES));
        List<Path> worldDirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(gameDir)) {
            for (Path d : ds) {
                if (Files.isDirectory(d)) {
                    worldDirs.add(d);
                }
            }
        } catch (IOException ignored) {
            // 读不到目录就只留上面那几个确定位置
        }
        worldDirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path d : worldDirs) {
            out.add(d.resolve(CARPET_CONF));
            out.add(d.resolve(TIS_CONF));
        }
        return out;
    }

    /** 读全部存在的候选文件（不存在的直接跳过）。 */
    public static List<Snapshot> readAll(Path gameDir, List<String> extraFiles) {
        List<Snapshot> out = new ArrayList<>();
        for (Path p : candidates(gameDir, extraFiles)) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".json")) {
                    out.add(parseJson(p, text));
                } else if (name.endsWith(".properties")) {
                    out.add(parseProperties(p, text));
                } else {
                    out.add(parseConf(p, text));
                }
            } catch (IOException | RuntimeException ignored) {
                // 单个文件读不动不影响其它来源
            }
        }
        return out;
    }

    /** 严格复刻 {@code SettingsManager.readSettingsFromConf} 的解析语义。 */
    public static Snapshot parseConf(Path file, String text) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean locked = false;
        for (String raw : text.split("\n", -1)) {
            String line = raw.replaceAll("[\r\n]", "");
            if (line.equalsIgnoreCase("locked")) {
                locked = true;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length <= 1) {
                continue;
            }
            if (values.isEmpty() && parts[0].startsWith("#")) {
                continue;
            }
            if (parts[1].startsWith("#")) {
                continue;
            }
            values.put(parts[0], parts[1]);
        }
        String label = file == null ? "carpet.conf" : file.getFileName().toString();
        return new Snapshot(label, file, values, locked, values.isEmpty() ? "空文件" : "已解析 " + values.size() + " 条");
    }

    /** Cava 自定义 JSON 快照：{@code {"规则名": 值}} 或 {@code {"rules": {...}}}。 */
    public static Snapshot parseJson(Path file, String text) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Object> root = CavaConfig.parseObject(text);
        Object inner = root.get("rules");
        Map<?, ?> src = inner instanceof Map<?, ?> m ? m : root;
        for (Map.Entry<?, ?> e : src.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Map || v instanceof java.util.List) {
                continue;
            }
            values.put(String.valueOf(e.getKey()), stringify(v));
        }
        return new Snapshot("carpet-rules.json", file, values, false, "Cava 自定义离线快照（/testcarpet dump 格式未验证）");
    }

    /** properties 快照：一行一个 {@code 规则名=值}，{@code #} 开头是注释。 */
    public static Snapshot parseProperties(Path file, String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return new Snapshot("carpet-rules.properties", file, values, false, "Cava 自定义离线快照");
    }

    private static String stringify(Object v) {
        if (v instanceof Boolean b) {
            return b.toString();
        }
        if (v instanceof Long l) {
            return l.toString();
        }
        return String.valueOf(v);
    }

    /**
     * 把若干快照合并成"规则名 -> 值"（越靠前的来源优先），再对 {@link ConsistencyRule#ALL} 逐条判定。
     *
     * @return 与 {@code ALL} 同序的判定列表；规则缺失时 {@code value == null} 且不告警
     */
    public static List<Finding> evaluate(List<Snapshot> snapshots) {
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> origin = new LinkedHashMap<>();
        for (Snapshot s : snapshots) {
            for (Map.Entry<String, String> e : s.values().entrySet()) {
                if (!merged.containsKey(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                    origin.put(e.getKey(), s.label());
                }
            }
        }
        List<Finding> out = new ArrayList<>();
        for (ConsistencyRule r : ConsistencyRule.ALL) {
            String v = merged.get(r.name());
            out.add(new Finding(r, v, origin.getOrDefault(r.name(), "-"), r.isAlarming(v)));
        }
        return out;
    }

    /** 合并后的规则表（给报告/单测直接用）。 */
    public static Map<String, String> merge(List<Snapshot> snapshots) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Snapshot s : snapshots) {
            for (Map.Entry<String, String> e : s.values().entrySet()) {
                merged.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return merged;
    }
}
