package cava.compat;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 「会毁掉一致性」的 Carpet / TIS 规则清单（{@code docs/CAVA-服务器模组清单.md} 附录 A.4 第 5 条）。
 *
 * <p><b>默认值来源</b>：{@code .cava-research/batchB/rules-carpet.json} / {@code rules-tis.json}
 * （batchB 用自写 class-file 解析器从真实 jar 提取的 86 / 143 条规则快照），
 * 并与同文档 A.4 第 1 条手工核过的默认值逐条一致（fastRedstoneDust=false、lagFreeSpawning=false、
 * maxEntityCollisions=0、quasiConnectivity=1、fillUpdates=true、pushLimit=12、railPowerLimit=9）。
 *
 * <p>两个等级：
 * <ul>
 *   <li>{@link Severity#BASELINE} —— <b>它就是当前整合包的基准</b>（用户已开启）。Cava 要么复刻它，
 *       要么让位；不是"错误"，报告里按 INFO 打，但对应子系统必须让位。</li>
 *   <li>{@link Severity#TOXIC} —— 默认关，一旦有人开会毁掉"与同一套整合包逐 tick 一致"的可复现性
 *       （随机化更新顺序、更新抑制模拟器、跳过更新等）。报告里按 WARN 打并让位。</li>
 * </ul>
 *
 * <p>本类不引用任何 Minecraft 类型。
 *
 * @param source       规则来源 mod id（{@code carpet} / {@code carpet-tis-addition}）
 * @param name         规则名（与 {@code carpet.conf} 里的键完全一致）
 * @param severity     等级
 * @param subsystem    受影响的 Cava 子系统
 * @param defaultValue 该规则的默认值（证据见类注释）；{@code "?"} 表示未提取到 → <b>不告警</b>
 * @param note         一句话说明
 */
public record ConsistencyRule(String source, String name, Severity severity, String subsystem, String defaultValue,
        String note) {

    /** 等级。 */
    public enum Severity {
        BASELINE("baseline"),
        TOXIC("toxic");

        private final String json;

        Severity(String json) {
            this.json = json;
        }

        public String jsonName() {
            return json;
        }
    }

    /** 默认值未提取到时的占位（不告警，避免拿推测当结论）。 */
    public static final String UNKNOWN_DEFAULT = "?";

    /**
     * 生效值是否已经偏离默认值 = 需要告警。
     *
     * <p>默认值未知时一律返回 false（诚实：不做无根据的告警）。
     */
    public boolean isAlarming(String value) {
        if (value == null || UNKNOWN_DEFAULT.equals(defaultValue)) {
            return false;
        }
        return !sameValue(value, defaultValue);
    }

    /** 报告里的一行（字段固定）。 */
    public String describe(String value) {
        return source + " " + name + "=" + value + " (默认 " + defaultValue + ") " + severity.jsonName()
                + " -> " + subsystem;
    }

    /** 数值/布尔等价比较：{@code 64} == {@code 64.0} == {@code 64.0d}；其余按去空白忽略大小写。 */
    public static boolean sameValue(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.trim();
        String y = b.trim();
        if (x.equalsIgnoreCase(y)) {
            return true;
        }
        Double dx = asNumber(x);
        Double dy = asNumber(y);
        return dx != null && dy != null && dx.doubleValue() == dy.doubleValue();
    }

    /** 把 {@code 64.0d} / {@code 12f} 这类 Java 字面量后缀也算成数字。 */
    public static Double asNumber(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() > 1) {
            char c = t.charAt(t.length() - 1);
            if (c == 'd' || c == 'D' || c == 'f' || c == 'F') {
                t = t.substring(0, t.length() - 1);
            }
        }
        if (t.isEmpty() || t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
            return null;
        }
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A.4 第 5 条的完整清单 + 等级判定。 */
    public static final List<ConsistencyRule> ALL = List.of(
            // ---- Carpet：用户已开启的两条 + 无卡顿刷怪，构成当前基准 ----
            new ConsistencyRule("carpet", "fastRedstoneDust", Severity.BASELINE, "redstone", "false",
                    "红石粉卡顿优化：RedstoneWireBlock.update HEAD cancellable —— 红石子系统的基准就是它，不是原版"),
            new ConsistencyRule("carpet", "optimizedTNT", Severity.BASELINE, "redstone", "false",
                    "同一位置/液体中的 TNT 爆炸优化（用户已开启）"),
            new ConsistencyRule("carpet", "lagFreeSpawning", Severity.BASELINE, "entity", "false",
                    "无卡顿刷怪（用户已开启）"),
            // ---- Carpet：其余"一旦开就毁基准"的 ----
            new ConsistencyRule("carpet", "movableBlockEntities", Severity.TOXIC, "redstone", "false",
                    "活塞可推动方块实体"),
            new ConsistencyRule("carpet", "tntDoNotUpdate", Severity.TOXIC, "redstone", "false",
                    "TNT 贴电源放置时不更新"),
            new ConsistencyRule("carpet", "fillUpdates", Severity.TOXIC, "mirror", "true",
                    "fill/clone/setblock 不触发方块更新 -> 镜像不能假设「每次写入都伴随邻居更新」"),
            new ConsistencyRule("carpet", "quasiConnectivity", Severity.TOXIC, "redstone", "1",
                    "准连通性范围改变 -> 活塞/发射器行为改变"),
            new ConsistencyRule("carpet", "pushLimit", Severity.TOXIC, "redstone", "12",
                    "活塞推动上限改变 -> 移动顺序与更新次数改变"),
            new ConsistencyRule("carpet", "railPowerLimit", Severity.TOXIC, "redstone", "9",
                    "动力铁轨供电范围改变"),
            // ---- TIS：默认全关，开启即毁基准 ----
            new ConsistencyRule("carpet-tis-addition", "redstoneDustRandomUpdateOrder", Severity.TOXIC, "redstone",
                    "false", "随机化红石粉更新顺序（最毒）"),
            new ConsistencyRule("carpet-tis-addition", "totallyNoBlockUpdate", Severity.TOXIC, "redstone", "false",
                    "完全不触发方块更新"),
            new ConsistencyRule("carpet-tis-addition", "updateSkippingSimulator", Severity.TOXIC, "redstone", "false",
                    "跳过更新模拟器"),
            new ConsistencyRule("carpet-tis-addition", "updateSuppressionSimulator", Severity.TOXIC, "redstone", "false",
                    "更新抑制模拟器（String 规则）"),
            new ConsistencyRule("carpet-tis-addition", "instantBlockUpdaterReintroduced", Severity.TOXIC, "redstone",
                    "false", "瞬时方块更新器"),
            new ConsistencyRule("carpet-tis-addition", "repeaterHalfDelay", Severity.TOXIC, "redstone", "false",
                    "中继器半延迟"),
            new ConsistencyRule("carpet-tis-addition", "dustTrapdoorReintroduced", Severity.TOXIC, "redstone", "false",
                    "红石粉-活板门旧行为回归"),
            new ConsistencyRule("carpet-tis-addition", "optimizedFastEntityMovement", Severity.TOXIC, "entity", "false",
                    "实体移动快速路径（行为改变，且与 Lithium 有 @Dynamic 条件注入交互）"),
            new ConsistencyRule("carpet-tis-addition", "optimizedHardHitBoxEntityCollision", Severity.TOXIC, "entity",
                    "false", "硬碰撞箱实体碰撞优化"),
            new ConsistencyRule("carpet-tis-addition", "optimizedTNTHighPriority", Severity.TOXIC, "redstone", "false",
                    "以更高优先级覆盖 Lithium 的爆炸优化"));

    public static Optional<ConsistencyRule> find(String source, String name) {
        for (ConsistencyRule r : ALL) {
            if (r.source().equals(source) && r.name().equals(name)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** 某来源的全部规则名（用于"报告里有没有漏掉"的自检）。 */
    public static List<String> namesOf(String source) {
        return ALL.stream().filter(r -> r.source().equals(source))
                .map(r -> r.name().toLowerCase(Locale.ROOT)).toList();
    }
}
