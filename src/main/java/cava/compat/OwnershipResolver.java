package cava.compat;

import cava.CavaConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「配置 + 探测到的 mod 集合」解算成每个重叠点的<b>最终归属</b>。
 *
 * <p>解算规则（任务书第 6 条：每个决定都必须能被配置覆盖）：
 * <pre>
 *   配置 ownership(子系统, mod) = defer        -> Owner.MOD      （让位，不管默认值是什么）
 *                              = native-first -> Owner.NATIVE   （复刻，不管默认值是什么）
 *                              = auto         -> OverlapPoint.defaultOwner()（本轮既定决策）
 * </pre>
 * 子系统级裁决：<b>只要有一个重叠点被判给 MOD，整个子系统就让位</b>
 * —— 因为对方的补丁还在跑，我们在原生侧做"等价替代"的前提就不成立了。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class OwnershipResolver {

    private OwnershipResolver() {
    }

    /** 一个重叠点的解算结果。 */
    public record Decision(OverlapPoint point, Owner owner, CavaConfig.Ownership configured, String reason) {

        public String subsystem() {
            return point.subsystem();
        }

        public String modId() {
            return point.modId();
        }

        public String key() {
            return point.key();
        }

        public boolean affectsCavaSubsystem() {
            return point.affectsCavaSubsystem();
        }
    }

    /** 只对<b>实际存在</b>的 mod 出裁决；不存在的 mod 不进报告（避免编数字）。 */
    public static List<Decision> resolve(CavaConfig cfg, Collection<String> presentModIds) {
        Set<String> present = new HashSet<>(presentModIds);
        List<Decision> out = new ArrayList<>();
        for (OverlapPoint p : CompatTable.OVERLAPS) {
            if (!present.contains(p.modId())) {
                continue;
            }
            CavaConfig.Ownership configured = cfg == null ? CavaConfig.Ownership.AUTO
                    : cfg.ownership(p.subsystem(), p.modId());
            Owner owner = switch (configured) {
                case DEFER -> Owner.MOD;
                case NATIVE_FIRST -> Owner.NATIVE;
                case AUTO -> p.defaultOwner();
            };
            out.add(new Decision(p, owner, configured, reasonFor(p, configured, owner)));
        }
        return out;
    }

    private static String reasonFor(OverlapPoint p, CavaConfig.Ownership configured, Owner owner) {
        String base = switch (configured) {
            case DEFER -> "config perMod." + p.modId() + "." + p.subsystem() + "=defer 覆盖（强制让位）";
            case NATIVE_FIRST -> "config perMod." + p.modId() + "." + p.subsystem() + "=native-first 覆盖（强制复刻）";
            case AUTO -> "auto -> 本轮既定决策";
        };
        String tail = switch (p.stage()) {
            case DECIDED -> "（本轮已拍板）";
            case PENDING_P2 -> "（待 P2 决策，本轮保持现状 = 让位）";
            case PENDING_P3 -> "（待 P3 决策，本轮保持现状 = 让位）";
            case NOT_HOOKED -> "（不在 Cava 注入点上，登记不裁决）";
        };
        return base + tail + "；默认归属=" + p.defaultOwner().jsonName();
    }

    /** 子系统级归属：任一重叠点让位 → 整个子系统让位。没有重叠点 → VANILLA。 */
    public static Map<String, Owner> subsystemOwners(List<Decision> decisions, Collection<String> subsystems) {
        Map<String, Owner> out = new LinkedHashMap<>();
        for (String s : subsystems) {
            Owner acc = null;
            for (Decision d : decisions) {
                if (!d.affectsCavaSubsystem() || !d.subsystem().equals(s)) {
                    continue;
                }
                acc = acc == null ? d.owner() : Owner.merge(acc, d.owner());
            }
            out.put(s, acc == null ? Owner.VANILLA : acc);
        }
        return out;
    }

    /** 让位原因（给子系统 disable 用的一句话）。 */
    public static String deferReason(List<Decision> decisions, String subsystem) {
        StringBuilder sb = new StringBuilder("兼容层：让位给 ");
        List<String> parts = new ArrayList<>();
        for (Decision d : decisions) {
            if (d.affectsCavaSubsystem() && d.subsystem().equals(subsystem) && d.owner() == Owner.MOD) {
                parts.add(d.modId() + "(" + d.key() + ")");
            }
        }
        sb.append(String.join(" + ", parts));
        sb.append("；原生路径若上线会与它的补丁重叠");
        return sb.toString();
    }
}
