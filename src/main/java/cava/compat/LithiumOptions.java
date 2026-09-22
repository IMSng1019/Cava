package cava.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code custom.lithium:options} —— Lithium 的官方 mod 覆盖机制。
 *
 * <p><b>机制（本机 javap 实证，不是记忆）</b>：{@code me.jellysquid.mods.lithium.common.config.LithiumConfig}
 * <ul>
 *   <li>{@code applyModOverrides()} 遍历 {@code FabricLoader.getInstance().getAllMods()}，
 *       对每个 mod 取 {@code ModMetadata.getCustomValue("lithium:options")}（常量池 #28 = {@code lithium:options}）。</li>
 *   <li>值必须是 {@code CvType.OBJECT}，否则打 WARN
 *       {@code "Mod '{}' contains invalid Lithium option overrides, ignoring"} 并跳过。</li>
 *   <li>{@code applyModOverride} 里：<b>键不以 {@code mixin.} 开头时会自动补上前缀</b>
 *       （常量池 #380 = {@code mixin.}），然后去 {@code options} 表里查；查不到打
 *       {@code "attempted to override option '{}', which doesn't exist, ignoring"}。</li>
 *   <li><b>全类没有 {@code System.getProperty} / 环境变量开关</b> —— 也就是说这条覆盖是
 *       <b>静态元数据、运行期无法撤销</b>。这是本文件最重要的一个限制。</li>
 * </ul>
 *
 * <p>四个候选组名都在 {@code assets/lithium/lithium-mixin-config-default.properties} 里实读存在：
 * {@code mixin.ai.pathing=true}、{@code mixin.entity.collisions.movement=true}、
 * {@code mixin.block.redstone_wire=true}、{@code mixin.shapes=true}。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class LithiumOptions {

    /** custom 键。 */
    public static final String KEY = "lithium:options";

    /** Lithium 给规则补的前缀。 */
    public static final String MIXIN_PREFIX = "mixin.";

    /** 任务书给的四个候选组（顺序即报告顺序）。 */
    public static final List<String> CANDIDATE_GROUPS = List.of(
            "mixin.ai.pathing",
            "mixin.entity.collisions.movement",
            "mixin.block.redstone_wire",
            "mixin.shapes");

    /**
     * Cava <b>实际发布</b>在 {@code fabric.mod.json} 里的覆盖表。
     *
     * <p>本轮只关一组：{@code mixin.ai.pathing}（P1 要复刻原生 A*）。
     * 另外三组<b>不关</b>，理由见 {@link #reason(String)}。
     * 这个常量与 {@code src/main/resources/fabric.mod.json} 的一致性由
     * {@code LithiumOptionsTest#shippedMetadataMatchesSource()} 强制。
     */
    public static final Map<String, Boolean> SHIPPED = Map.of("mixin.ai.pathing", false);

    private LithiumOptions() {
    }

    /** 每个候选组为什么被关 / 没被关（写进文档与启动报告）。 */
    public static String reason(String group) {
        return switch (group) {
            case "mixin.ai.pathing" ->
                    "【关】P1 要复刻原生 A*（PathNodeNavigator.findPathToAny）；Lithium 这个组是 LandPathNodeMaker 缓存短路，"
                            + "关掉它的收益损失要在 P1 上线后补回来（见 docs/CAVA-compat-notes.md 的风险条）";
            case "mixin.entity.collisions.movement" ->
                    "【不关】P2 才决定归属；本轮保持现状 = 让位。提前关会让服务器比现在更慢且没有任何收益";
            case "mixin.block.redstone_wire" ->
                    "【不关】P3 才决定归属；本轮保持现状 = 让位（而且红石基准还叠着 Carpet fastRedstoneDust）";
            case "mixin.shapes" ->
                    "【不关】VoxelShape/形状缓存系列，不在 Cava 的注入点上，与三个子系统都不重叠";
            default -> "【未知组】不在任务书的四个候选键里";
        };
    }

    /** 未在前缀表里出现的组一律视为"没关"。 */
    public static boolean isDisabled(String group, Map<String, Boolean> options) {
        if (options == null) {
            return false;
        }
        Boolean v = options.get(normalizeKey(group));
        return v != null && !v;
    }

    /** 复刻 {@code LithiumConfig.applyModOverride}：没带 {@code mixin.} 前缀就补上。 */
    public static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        String k = key.trim();
        return k.startsWith(MIXIN_PREFIX) ? k : MIXIN_PREFIX + k;
    }

    /**
     * 从 mod 元数据的 {@code custom} 表里取 {@code lithium:options}。
     *
     * <p>非对象值（Lithium 会 WARN 并忽略）在这里返回空表，并可通过 {@link #invalidReason} 区分。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Boolean> parseCustom(Map<String, Object> custom) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (custom == null || !(custom.get(KEY) instanceof Map<?, ?> m)) {
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getValue() instanceof Boolean b) {
                out.put(normalizeKey(String.valueOf(e.getKey())), b);
            }
        }
        return out;
    }

    /** {@code custom.lithium:options} 是不是一个合法对象（Lithium 会校验）。 */
    public static boolean isValidCustom(Map<String, Object> custom) {
        return custom != null && custom.get(KEY) instanceof Map;
    }

    /** 读一份 {@code fabric.mod.json} 文本里的覆盖表（单测直接读仓库源文件用）。 */
    public static Map<String, Boolean> readShippedFromJson(String fabricModJson) {
        return parseCustom(ModProbe.fromJson(fabricModJson, "(memory)").custom());
    }

    /** 读文件形式的 {@code fabric.mod.json}。 */
    public static Map<String, Boolean> readShippedFromFile(Path fabricModJson) throws IOException {
        return readShippedFromJson(Files.readString(fabricModJson, StandardCharsets.UTF_8));
    }

    /**
     * 期望的覆盖表：由"子系统级归属"推导。
     *
     * <p>规则：{@code pathfind} 的归属是 {@link Owner#NATIVE} → 想要 {@code mixin.ai.pathing} 关闭；
     * 否则想要它开着（因为我们要让位，得让 Lithium 继续优化）。
     */
    public static Map<String, Boolean> wantedFrom(Map<String, Owner> subsystemOwners) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        Owner pf = subsystemOwners == null ? null : subsystemOwners.get("pathfind");
        out.put("mixin.ai.pathing", pf != Owner.NATIVE);
        return out;
    }

    /** 发布值与期望值不一致的组（报告里要 WARN）。 */
    public static List<String> inconsistencies(Map<String, Boolean> shipped, Map<String, Boolean> wanted) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : wanted.entrySet()) {
            boolean actual = !isDisabled(e.getKey(), shipped);
            if (actual != e.getValue()) {
                out.add(e.getKey() + "（fabric.mod.json 里是 " + (actual ? "开" : "关") + "，按当前配置应当是 "
                        + (e.getValue() ? "开" : "关") + "）");
            }
        }
        return out;
    }

    /** 静态元数据无法在运行期撤销 —— 报告里给用户的可执行提示。 */
    public static String staticRemedyHint(String group) {
        return "custom.lithium:options 是 Fabric 静态元数据，Lithium 全类无 System.getProperty 开关，"
                + "运行期无法撤销；要改必须编辑 cava 的 fabric.mod.json 并把 " + normalizeKey(group)
                + " 从 false 改成 true（或删掉该项）后重启";
    }

    /** 报告行用的简短描述。 */
    public static String describe(Map<String, Boolean> options) {
        StringBuilder sb = new StringBuilder();
        for (String g : CANDIDATE_GROUPS) {
            sb.append(g).append('=').append(isDisabled(g, options) ? "false" : "true").append(' ');
        }
        return sb.toString().trim();
    }

    /** 只保留 Cava 真正关心的四个候选组的视图。 */
    public static Map<String, Boolean> candidatesOnly(Map<String, Boolean> options) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String g : CANDIDATE_GROUPS) {
            out.put(g, !isDisabled(g, options));
        }
        return out;
    }

    /** 从 jar 元数据里读 Lithium 自己的默认值（{@code assets/lithium/lithium-mixin-config-default.properties}）。 */
    public static Optional<String> defaultGroupValue(Path lithiumJar, String group) {
        if (lithiumJar == null || !Files.isRegularFile(lithiumJar)) {
            return Optional.empty();
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(lithiumJar.toFile())) {
            var e = zip.getEntry("assets/lithium/lithium-mixin-config-default.properties");
            if (e == null) {
                return Optional.empty();
            }
            try (var in = zip.getInputStream(e)) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                String key = normalizeKey(group);
                for (String line : text.split("\n", -1)) {
                    String t = line.trim();
                    int eq = t.indexOf('=');
                    if (eq > 0 && t.substring(0, eq).trim().toLowerCase(Locale.ROOT).equals(key)) {
                        return Optional.of(t.substring(eq + 1).trim());
                    }
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** 解析 {@code lime} 风格的布尔文本（Lithium 的 properties 用 true/false）。 */
    public static boolean parseBool(String s) {
        return s != null && s.trim().equalsIgnoreCase("true");
    }

    /** 便利：从 {@link ModInfo} 里读覆盖表。 */
    public static Map<String, Boolean> of(ModInfo mod) {
        return mod == null ? Map.of() : parseCustom(mod.custom());
    }

    /** 便利：从 CavaConfig 的原始 custom 表读（未使用，保留给测试）。 */
    public static Map<String, Boolean> fromRawCustom(Map<String, Object> raw) {
        return parseCustom(raw);
    }
}
