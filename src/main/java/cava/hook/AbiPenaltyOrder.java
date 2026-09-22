package cava.hook;

import java.util.List;

/**
 * {@code CavaMobProfile.penalty[26]} 的**索引语义**：索引 = {@code cava_abi.h} 的 {@code CAVA_PNT_*}。
 *
 * <p>为什么单独一个类：这张表错一位 = 整张惩罚表错位，**路径照样算得出来、只是与原版不一致**——
 * 属于最难发现的 parity bug（见 {@code docs/CAVA-gates.md} 门禁 #7 的坑 1）。
 * 所以顺序只允许在这一个地方写死，并且启动时用 {@link #checkAgainstVanillaOrder(List)} 自检。
 *
 * <p>当前 {@code cava_abi.h} 的表（2026-09-22 由 PathNodeType 的 {@code static{}} 字节码重建）
 * 与 Yarn 1.20.4 {@code PathNodeType} 的枚举 ordinal **逐条一致**，因此这里就是恒等映射：
 * {@code CAVA_PNT_x == PathNodeType.x.ordinal()}。
 *
 * <p><b>本类不引用任何 Minecraft 类型</b>，枚举名只以字符串形式出现，便于纯 Java 单测。
 */
public final class AbiPenaltyOrder {

    /** 索引 → {@code PathNodeType} 常量名（顺序 = {@code cava_abi.h} 的 {@code CAVA_PNT_*} 值顺序）。 */
    public static final List<String> ABI_ORDER = List.of(
            "BLOCKED",              // CAVA_PNT_BLOCKED            0
            "OPEN",                 // CAVA_PNT_OPEN               1
            "WALKABLE",             // CAVA_PNT_WALKABLE           2
            "WALKABLE_DOOR",        // CAVA_PNT_WALKABLE_DOOR      3
            "TRAPDOOR",             // CAVA_PNT_TRAPDOOR           4
            "POWDER_SNOW",          // CAVA_PNT_POWDER_SNOW        5
            "DANGER_POWDER_SNOW",   // CAVA_PNT_DANGER_POWDER_SNOW 6
            "FENCE",                // CAVA_PNT_FENCE              7
            "LAVA",                 // CAVA_PNT_LAVA               8
            "WATER",                // CAVA_PNT_WATER              9
            "WATER_BORDER",         // CAVA_PNT_WATER_BORDER      10
            "RAIL",                 // CAVA_PNT_RAIL              11
            "UNPASSABLE_RAIL",      // CAVA_PNT_UNPASSABLE_RAIL   12
            "DANGER_FIRE",          // CAVA_PNT_DANGER_FIRE       13
            "DAMAGE_FIRE",          // CAVA_PNT_DAMAGE_FIRE       14
            "DANGER_OTHER",         // CAVA_PNT_DANGER_OTHER      15
            "DAMAGE_OTHER",         // CAVA_PNT_DAMAGE_OTHER      16
            "DOOR_OPEN",            // CAVA_PNT_DOOR_OPEN         17
            "DOOR_WOOD_CLOSED",     // CAVA_PNT_DOOR_WOOD_CLOSED  18
            "DOOR_IRON_CLOSED",     // CAVA_PNT_DOOR_IRON_CLOSED  19
            "BREACH",               // CAVA_PNT_BREACH            20
            "LEAVES",               // CAVA_PNT_LEAVES            21
            "STICKY_HONEY",         // CAVA_PNT_STICKY_HONEY      22
            "COCOA",                // CAVA_PNT_COCOA             23
            "DAMAGE_CAUTIOUS",      // CAVA_PNT_DAMAGE_CAUTIOUS   24
            "DANGER_TRAPDOOR");     // CAVA_PNT_DANGER_TRAPDOOR   25

    /** 表长（= {@code CAVA_PNT_COUNT} = {@code CavaLayouts.PNT_COUNT}）。 */
    public static final int COUNT = 26;

    private AbiPenaltyOrder() {
    }

    /**
     * 自检：{@code vanillaOrder} 必须是**真实 {@code PathNodeType.values()} 的名字顺序**。
     *
     * @return 不一致的条目描述（空 = 一致）。不一致时**必须拒绝原生接管**（惩罚表会整体错位）。
     */
    public static List<String> checkAgainstVanillaOrder(List<String> vanillaOrder) {
        java.util.ArrayList<String> problems = new java.util.ArrayList<>();
        if (vanillaOrder.size() != ABI_ORDER.size()) {
            problems.add("条目数不等：abi=" + ABI_ORDER.size() + " vanilla=" + vanillaOrder.size());
            return problems;
        }
        for (int i = 0; i < ABI_ORDER.size(); i++) {
            if (!ABI_ORDER.get(i).equals(vanillaOrder.get(i))) {
                problems.add("index " + i + ": abi=" + ABI_ORDER.get(i) + " vanilla=" + vanillaOrder.get(i));
            }
        }
        return problems;
    }
}
