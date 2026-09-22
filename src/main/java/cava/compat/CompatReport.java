package cava.compat;

import cava.CavaConfig;
import cava.subsystem.CavaSubsystem;
import cava.subsystem.SubsystemRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 启动兼容性报告：<b>「检测到的相关 mod → 与我们的重叠点 → 最终归属 → 被禁用的子系统及原因」</b>。
 *
 * <p>报告是<b>纯文本、逐行、字段固定</b>的，既能被人读，也能被 grep / 单测断言。
 * 每行都以 {@value #PREFIX} 开头，第 2 字段是格式版本，第 3 字段是记录类型（段数写死在单测里）：
 *
 * <pre>
 * CAVA-COMPAT|v1|header |mc=&lt;v&gt;|loader=&lt;v&gt;|native=&lt;status&gt;|mods=&lt;n&gt;|relevant=&lt;n&gt;|overlaps=&lt;n&gt;|defer=&lt;a,b&gt;|rules=&lt;alarm&gt;/&lt;total&gt;   (11 段)
 * CAVA-COMPAT|v1|mod    |&lt;id&gt;|&lt;version&gt;|&lt;env&gt;|&lt;source&gt;                                                  (7 段)
 * CAVA-COMPAT|v1|owner  |&lt;subsystem&gt;|&lt;modId&gt;|&lt;key&gt;|&lt;owner&gt;|&lt;configured&gt;|&lt;stage&gt;|&lt;overlap&gt;               (10 段)
 * CAVA-COMPAT|v1|rule   |&lt;source&gt;|&lt;name&gt;|&lt;value 或 absent&gt;|&lt;default&gt;|&lt;severity&gt;|&lt;subsystem&gt;|&lt;alarm&gt;|&lt;origin&gt; (11 段)
 * CAVA-COMPAT|v1|lithium|&lt;group&gt;|published=&lt;on 或 off&gt;|wanted=&lt;on 或 off&gt;|consistent=&lt;bool&gt;|&lt;reason&gt;     (8 段)
 * CAVA-COMPAT|v1|adapter|&lt;modId&gt;|&lt;detail&gt;                                                           (5 段)
 * CAVA-COMPAT|v1|note   |&lt;key&gt;|&lt;text&gt;                                                               (5 段)
 * CAVA-COMPAT|v1|warn   |&lt;code&gt;|&lt;text&gt;                                                              (5 段)
 * </pre>
 *
 * <p>grep 例子（PowerShell，用 -SimpleMatch 避开正则里的竖线转义）：
 * <pre>
 *   Get-Content logs/latest.log | Select-String -SimpleMatch 'CAVA-COMPAT|v1|owner|'
 *   Get-Content logs/latest.log | Select-String -SimpleMatch 'CAVA-COMPAT|v1|warn|'
 * </pre>
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class CompatReport {

    public static final String PREFIX = "CAVA-COMPAT";
    public static final String FORMAT_VERSION = "v1";

    private final List<String> lines = new ArrayList<>();
    private final List<OwnershipResolver.Decision> decisions;
    private final Map<String, Owner> subsystemOwners;
    private final List<CarpetRuleProbe.Finding> findings;
    private final Map<String, Boolean> shippedLithium;
    private final Map<String, Boolean> wantedLithium;
    private final List<String> warnings;
    private final List<ModInfo> mods;
    private final Set<String> ruleForcedDefer = new LinkedHashSet<>();
    private final Set<String> ruleDeferOverridden = new LinkedHashSet<>();

    private CompatReport(List<ModInfo> mods, List<OwnershipResolver.Decision> decisions,
            Map<String, Owner> subsystemOwners, List<CarpetRuleProbe.Finding> findings,
            Map<String, Boolean> shippedLithium, Map<String, Boolean> wantedLithium, List<String> warnings) {
        this.mods = List.copyOf(mods);
        this.decisions = List.copyOf(decisions);
        this.subsystemOwners = Map.copyOf(subsystemOwners);
        this.findings = List.copyOf(findings);
        this.shippedLithium = Map.copyOf(shippedLithium);
        this.wantedLithium = Map.copyOf(wantedLithium);
        this.warnings = List.copyOf(warnings);
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /**
     * 纯函数入口（单测直接调；不碰 Fabric / Minecraft）。
     *
     * @param cfg        config/cava.json（可为 null 表示全 auto）
     * @param mods       探测到的 mod 列表
     * @param snapshots  Carpet/TIS 规则快照（可为空 = 没找到规则文件）
     * @param shipped    fabric.mod.json 里<b>实际发布</b>的 lithium 覆盖表
     */
    public static CompatReport evaluate(CavaConfig cfg, List<ModInfo> mods,
            List<CarpetRuleProbe.Snapshot> snapshots, Map<String, Boolean> shipped,
            String mcVersion, String loaderVersion, String nativeStatus, int ruleFilesChecked) {
        List<ModInfo> modList = mods == null ? List.of() : mods;
        List<String> presentIds = ModProbe.ids(modList);
        List<OwnershipResolver.Decision> decisions = OwnershipResolver.resolve(cfg, presentIds);
        Map<String, Owner> owners = new LinkedHashMap<>(
                OwnershipResolver.subsystemOwners(decisions, CavaConfig.SUBSYSTEMS));

        List<CarpetRuleProbe.Finding> findings = CarpetRuleProbe.evaluate(
                snapshots == null ? List.of() : snapshots);
        Set<String> forced = new LinkedHashSet<>();
        Set<String> overridden = new LinkedHashSet<>();
        boolean autoDefer = cfg == null || cfg.compatAutoDeferOnRule();
        for (CarpetRuleProbe.Finding f : findings) {
            if (!f.alarming() || !autoDefer) {
                continue;
            }
            String sub = f.rule().subsystem();
            if (!CavaConfig.SUBSYSTEMS.contains(sub)) {
                continue;
            }
            if (allExplicitNativeFirst(decisions, sub)) {
                overridden.add(sub);
                continue;
            }
            forced.add(sub);
            owners.put(sub, Owner.MOD);
        }

        Map<String, Boolean> shippedMap = shipped == null ? Map.of() : shipped;
        Map<String, Boolean> wanted = LithiumOptions.wantedFrom(owners);

        List<String> warnings = new ArrayList<>();
        for (String inc : LithiumOptions.inconsistencies(shippedMap, wanted)) {
            warnings.add("LITHIUM_METADATA_MISMATCH: " + inc + "。"
                    + LithiumOptions.staticRemedyHint("mixin.ai.pathing"));
        }
        if (snapshots == null || snapshots.isEmpty()) {
            warnings.add("CARPET_RULES_ABSENT: 没找到任何 Carpet/TIS 规则文件（检查过 " + ruleFilesChecked
                    + " 个候选路径），规则相关判定全部为「未知」，本条不影响归属默认值");
        }
        for (CarpetRuleProbe.Finding f : findings) {
            if (f.alarming() && f.rule().severity() == ConsistencyRule.Severity.TOXIC) {
                warnings.add("TOXIC_RULE_ON: " + f.describe() + "；" + f.rule().note());
            }
        }
        for (String sub : overridden) {
            warnings.add("RULE_DEFER_OVERRIDDEN: 子系统 " + sub
                    + " 有告警规则生效，但它的全部重叠点都被显式配置成 native-first，按配置走原生优先（风险自担）");
        }

        CompatReport r = new CompatReport(modList, decisions, owners, findings, shippedMap, wanted, warnings);
        r.ruleForcedDefer.addAll(forced);
        r.ruleDeferOverridden.addAll(overridden);
        r.build(mcVersion, loaderVersion, nativeStatus, ruleFilesChecked);
        return r;
    }

    private static boolean allExplicitNativeFirst(List<OwnershipResolver.Decision> decisions, String subsystem) {
        boolean any = false;
        for (OwnershipResolver.Decision d : decisions) {
            if (!d.affectsCavaSubsystem() || !d.subsystem().equals(subsystem)) {
                continue;
            }
            any = true;
            if (d.configured() != CavaConfig.Ownership.NATIVE_FIRST) {
                return false;
            }
        }
        return any;
    }

    /** 游戏内入口：从 Fabric Loader 探测 mod，从游戏目录探测规则快照。 */
    public static CompatReport collectInGame(CavaConfig cfg, Path gameDir, String nativeStatus,
            String mcVersion, String loaderVersion) {
        List<ModInfo> mods = ModProbe.fromLoader();
        List<String> extraFiles = cfg == null ? List.of() : cfg.compatRuleFiles();
        int candidates = CarpetRuleProbe.candidates(gameDir, extraFiles).size();
        List<CarpetRuleProbe.Snapshot> snapshots = CarpetRuleProbe.readAll(gameDir, extraFiles);
        Map<String, Boolean> shipped = ModProbe.ownMetadata().map(LithiumOptions::of)
                .filter(m -> !m.isEmpty())
                .orElse(LithiumOptions.SHIPPED);
        return evaluate(cfg, mods, snapshots, shipped, mcVersion, loaderVersion, nativeStatus, candidates);
    }

    private void build(String mcVersion, String loaderVersion, String nativeStatus, int ruleFilesChecked) {
        List<String> relevant = new ArrayList<>();
        for (String id : CompatTable.RELEVANT_MODS) {
            if (ModProbe.find(mods, id).isPresent()) {
                relevant.add(id);
            }
        }
        long alarm = findings.stream().filter(CarpetRuleProbe.Finding::alarming).count();

        lines.add(join("header", "mc=" + s(mcVersion), "loader=" + s(loaderVersion), "native=" + s(nativeStatus),
                "mods=" + mods.size(), "relevant=" + relevant.size(), "overlaps=" + decisions.size(),
                "defer=" + String.join(",", deferSubsystems()), "rules=" + alarm + "/" + findings.size()));

        for (ModInfo m : mods) {
            if (relevant.contains(m.id()) || m.id().equals("cava")) {
                lines.add(join("mod", s(m.id()), s(m.version()), s(m.environmentOrDefault()), s(m.source())));
            }
        }

        for (OwnershipResolver.Decision d : decisions) {
            lines.add(join("owner", s(d.subsystem()), s(d.modId()), s(d.key()), d.owner().jsonName(),
                    d.configured().jsonName(), d.point().stage().jsonName(), s(d.point().overlap())));
        }

        for (CarpetRuleProbe.Finding f : findings) {
            lines.add(join("rule", s(f.rule().source()), s(f.rule().name()),
                    f.value() == null ? "absent" : s(f.value()), s(f.rule().defaultValue()),
                    f.rule().severity().jsonName(), s(f.rule().subsystem()),
                    f.alarming() ? "ALARM" : "ok", s(f.origin())));
        }

        for (String g : LithiumOptions.CANDIDATE_GROUPS) {
            boolean published = !LithiumOptions.isDisabled(g, shippedLithium);
            boolean wanted = wantedLithium.getOrDefault(g, true);
            lines.add(join("lithium", s(g), "published=" + (published ? "on" : "off"),
                    "wanted=" + (wanted ? "on" : "off"), "consistent=" + (published == wanted),
                    s(LithiumOptions.reason(g))));
        }

        lines.add(join("adapter", "servercore",
                s(ServerCoreAdapter.reportLine(ServerCoreAdapter.probeJar(jarOf("servercore").orElse(null)),
                        ModProbe.find(mods, "servercore").isPresent()))));
        lines.add(join("adapter", "vmp",
                s(VmpAdapter.reportLine(VmpAdapter.probeJar(jarOf("vmp").orElse(null)),
                        ModProbe.find(mods, "vmp").isPresent()))));

        lines.add(join("note", "redstone-baseline",
                s("用户已开启 Carpet fastRedstoneDust / optimizedTNT（+ 无卡顿刷怪）→ 红石子系统的基准是它们，不是原版；"
                        + "本机未定位到真实服务器的 carpet.conf，所以这些是 captain 声明 + 规则默认值证据，未在本机实跑验证")));
        lines.add(join("note", "subsystem-owners",
                s(subsystemOwners.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().jsonName())
                        .reduce((a, b) -> a + " " + b).orElse("(无)"))));
        lines.add(join("note", "rule-files-checked", s(String.valueOf(ruleFilesChecked))));
        lines.add(join("note", "lithium-static",
                s("custom.lithium:options 是静态元数据，运行期无法撤销；只有编辑 cava 的 fabric.mod.json 才能改")));

        for (String w : warnings) {
            String code = w.contains(":") ? w.substring(0, w.indexOf(':')) : "WARN";
            lines.add(join("warn", s(code), s(w)));
        }
    }

    private Optional<Path> jarOf(String modId) {
        return ModProbe.find(mods, modId).flatMap(m -> {
            try {
                Path p = Path.of(m.source());
                return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    private static String join(String type, String... fields) {
        StringBuilder sb = new StringBuilder(PREFIX).append('|').append(FORMAT_VERSION).append('|').append(type);
        for (String f : fields) {
            sb.append('|').append(s(f));
        }
        return sb.toString();
    }

    /** 报告字段里绝不能出现竖线或换行（会破坏字段数）。 */
    private static String s(String v) {
        if (v == null) {
            return "";
        }
        return v.replace('|', '/').replace('\r', ' ').replace('\n', ' ');
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<String> lines() {
        return List.copyOf(lines);
    }

    public String text() {
        return String.join(System.lineSeparator(), lines);
    }

    public List<ModInfo> mods() {
        return mods;
    }

    public List<OwnershipResolver.Decision> decisions() {
        return decisions;
    }

    public Map<String, Owner> subsystemOwners() {
        return subsystemOwners;
    }

    public Owner owner(String subsystem) {
        return subsystemOwners.getOrDefault(subsystem, Owner.VANILLA);
    }

    public List<CarpetRuleProbe.Finding> findings() {
        return findings;
    }

    public List<String> warnings() {
        return warnings;
    }

    public Map<String, Boolean> shippedLithium() {
        return shippedLithium;
    }

    public Map<String, Boolean> wantedLithium() {
        return wantedLithium;
    }

    public Set<String> lithiumInconsistencies() {
        return new LinkedHashSet<>(LithiumOptions.inconsistencies(shippedLithium, wantedLithium));
    }

    /** 被规则强制让位的子系统（compat.autoDeferOnRule=true 时）。 */
    public Set<String> ruleForcedDefer() {
        return Set.copyOf(ruleForcedDefer);
    }

    /** 规则让位被配置顶掉的子系统。 */
    public Set<String> ruleDeferOverridden() {
        return Set.copyOf(ruleDeferOverridden);
    }

    /** 需要让位给别人的子系统 id。 */
    public List<String> deferSubsystems() {
        List<String> out = new ArrayList<>();
        for (String id : CavaConfig.SUBSYSTEMS) {
            if (subsystemOwners.getOrDefault(id, Owner.VANILLA) == Owner.MOD) {
                out.add(id);
            }
        }
        return out;
    }

    /** 人类可读的一小张表（给启动横幅用）。 */
    public List<String> summaryLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format("    %-9s %-9s %s", "subsystem", "owner", "重叠来源"));
        for (String id : CavaConfig.SUBSYSTEMS) {
            StringBuilder who = new StringBuilder();
            for (OwnershipResolver.Decision d : decisions) {
                if (d.affectsCavaSubsystem() && d.subsystem().equals(id)) {
                    who.append(d.modId()).append('/').append(d.key()).append(' ');
                }
            }
            out.add(String.format("    %-9s %-9s %s", id, owner(id).jsonName(),
                    who.length() == 0 ? "(无重叠 mod)" : who.toString().trim()));
        }
        return out;
    }

    /**
     * 把让位结果落到子系统上：所有者是 mod 的子系统一律禁用并写明原因。
     *
     * @return 被禁用的子系统数量
     */
    public int applyDeferrals(SubsystemRegistry registry) {
        if (registry == null) {
            return 0;
        }
        int n = 0;
        for (String id : deferSubsystems()) {
            Optional<CavaSubsystem> s = registry.byId(id);
            if (s.isPresent()) {
                s.get().disable(OwnershipResolver.deferReason(decisions, id));
                n++;
            }
        }
        return n;
    }
}
