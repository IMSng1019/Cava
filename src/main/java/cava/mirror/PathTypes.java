package cava.mirror;

/**
 * Yarn 1.20.4 {@code PathNodeType} 的**真实 ordinal 表**与默认惩罚（malus）。
 *
 * <p><b>为什么要有这个类</b>：{@code CavaMobProfile.penalty[26]} 与
 * {@code CavaStateRecord.path_type_idx} 都按"PathNodeType 的序号"索引。序号错一位 =
 * 整张惩罚表错位，**而且路径照样算得出来**（最难发现的一类 parity bug）。
 * 本表的值来自 {@code docs/CAVA-pathfind-oracle-spec.md} §8 的 javap 实证
 * （{@code javap -p -c net.minecraft.entity.ai.pathing.PathNodeType} 的 {@code static{}}），
 * 并由 {@code PathTypesTest} 硬编码锁死；MC 环境可用时再由
 * {@code McStateTableProbeTest} 与真实枚举对拍。
 *
 * <p><b>⚠️ {@code cava_abi.h} 的 {@code CAVA_PNT_*} 数值是错的</b>（见 {@link #HEADER_CAVA_PNT}）：
 * 从索引 5 起全部错位，且含 4 个 1.20.4 里不存在的常量。**唯一正确的序号是本表的 ordinal**
 * （= {@code PathNodeType.ordinal()}）—— 原生内核内部用的也是这一套。
 * 所以 Java 侧一律 {@code type.ordinal()}，**不要用 {@code CAVA_PNT_*} 常量去索引**。
 */
public final class PathTypes {

    private PathTypes() {
    }

    /** 常量个数（= {@code CAVA_PNT_COUNT} = 26）。 */
    public static final int COUNT = 26;

    // 名称（顺序 = ordinal）
    private static final String[] NAMES = {
            "BLOCKED", "OPEN", "WALKABLE", "WALKABLE_DOOR", "TRAPDOOR", "POWDER_SNOW",
            "DANGER_POWDER_SNOW", "FENCE", "LAVA", "WATER", "WATER_BORDER", "RAIL",
            "UNPASSABLE_RAIL", "DANGER_FIRE", "DAMAGE_FIRE", "DANGER_OTHER", "DAMAGE_OTHER",
            "DOOR_OPEN", "DOOR_WOOD_CLOSED", "DOOR_IRON_CLOSED", "BREACH", "LEAVES",
            "STICKY_HONEY", "COCOA", "DAMAGE_CAUTIOUS", "DANGER_TRAPDOOR"
    };

    // Entity 默认惩罚（PathNodeType 构造器第 3 个实参，spec §8 表）
    private static final float[] DEFAULT_PENALTY = {
            -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f,
            0.0f, -1.0f, -1.0f, 8.0f, 8.0f, 0.0f,
            -1.0f, 8.0f, 16.0f, 8.0f, -1.0f,
            0.0f, -1.0f, -1.0f, 4.0f, -1.0f,
            8.0f, 0.0f, 0.0f, 0.0f
    };

    /** 常用序号（= ordinal）。 */
    public static final int BLOCKED = 0;
    /** OPEN。 */
    public static final int OPEN = 1;
    /** WALKABLE。 */
    public static final int WALKABLE = 2;
    /** WALKABLE_DOOR。 */
    public static final int WALKABLE_DOOR = 3;
    /** TRAPDOOR。 */
    public static final int TRAPDOOR = 4;
    /** POWDER_SNOW。 */
    public static final int POWDER_SNOW = 5;
    /** DANGER_POWDER_SNOW。 */
    public static final int DANGER_POWDER_SNOW = 6;
    /** FENCE。 */
    public static final int FENCE = 7;
    /** LAVA。 */
    public static final int LAVA = 8;
    /** WATER。 */
    public static final int WATER = 9;
    /** WATER_BORDER。 */
    public static final int WATER_BORDER = 10;
    /** RAIL。 */
    public static final int RAIL = 11;
    /** UNPASSABLE_RAIL。 */
    public static final int UNPASSABLE_RAIL = 12;
    /** DANGER_FIRE。 */
    public static final int DANGER_FIRE = 13;
    /** DAMAGE_FIRE。 */
    public static final int DAMAGE_FIRE = 14;
    /** DANGER_OTHER。 */
    public static final int DANGER_OTHER = 15;
    /** DAMAGE_OTHER。 */
    public static final int DAMAGE_OTHER = 16;
    /** DOOR_OPEN。 */
    public static final int DOOR_OPEN = 17;
    /** DOOR_WOOD_CLOSED。 */
    public static final int DOOR_WOOD_CLOSED = 18;
    /** DOOR_IRON_CLOSED。 */
    public static final int DOOR_IRON_CLOSED = 19;
    /** BREACH。 */
    public static final int BREACH = 20;
    /** LEAVES。 */
    public static final int LEAVES = 21;
    /** STICKY_HONEY。 */
    public static final int STICKY_HONEY = 22;
    /** COCOA。 */
    public static final int COCOA = 23;
    /** DAMAGE_CAUTIOUS。 */
    public static final int DAMAGE_CAUTIOUS = 24;
    /** DANGER_TRAPDOOR。 */
    public static final int DANGER_TRAPDOOR = 25;

    /**
     * {@code cava_abi.h} 里那套**错误**的 {@code CAVA_PNT_*} 数值（下标 = 头文件常量值，值 = 它声称的常量名）。
     *
     * <p>仅用于文档/单测把"错在哪"钉死；**生产代码不许用它索引任何数组**。
     */
    public static final String[] HEADER_CAVA_PNT = {
            "BLOCKED", "OPEN", "WALKABLE", "WALKABLE_DOOR", "TRAPDOOR", "FENCE",
            "LAVA", "WATER", "RAIL", "UNPASSABLE", "DOOR_OPEN", "DANGER_FIRE",
            "DAMAGE_FIRE", "DANGER_OTHER", "DAMAGE_OTHER", "FENCE_GATE?/BREACH?", "BREACH?",
            "DOOR_OPEN_IRON?", "DOOR_IRON_CLOSED?", "DAMAGE_CACTUS", "DOOR_OPEN_IRON", "LEAVES",
            "STICKY_HONEY", "COCOA", "DANGER_WATER", "DANGER_POWDER_SNOW"
    };

    /** 名称（越界返回 {@code "?"}）。 */
    public static String name(int ordinal) {
        return ordinal >= 0 && ordinal < COUNT ? NAMES[ordinal] : "?";
    }

    /** 默认惩罚（越界返回 0）。 */
    public static float defaultPenalty(int ordinal) {
        return ordinal >= 0 && ordinal < COUNT ? DEFAULT_PENALTY[ordinal] : 0.0f;
    }

    /** 全部默认惩罚的副本（写 CavaMobProfile 时用）。 */
    public static float[] defaultPenalties() {
        return DEFAULT_PENALTY.clone();
    }

    /** 校验表自身一致性（长度 / 负值分布），返回问题列表。 */
    public static java.util.List<String> selfCheck() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (NAMES.length != COUNT) {
            problems.add("NAMES.length=" + NAMES.length + " 期望 " + COUNT);
        }
        if (DEFAULT_PENALTY.length != COUNT) {
            problems.add("DEFAULT_PENALTY.length=" + DEFAULT_PENALTY.length + " 期望 " + COUNT);
        }
        for (int i = 0; i < COUNT && i < NAMES.length; i++) {
            boolean negative = DEFAULT_PENALTY[i] < 0.0f;
            boolean mustBeNegative = NAMES[i].equals("BLOCKED") || NAMES[i].equals("POWDER_SNOW")
                    || NAMES[i].equals("FENCE") || NAMES[i].equals("LAVA")
                    || NAMES[i].equals("UNPASSABLE_RAIL") || NAMES[i].equals("DAMAGE_OTHER")
                    || NAMES[i].equals("DOOR_WOOD_CLOSED") || NAMES[i].equals("DOOR_IRON_CLOSED")
                    || NAMES[i].equals("LEAVES");
            if (negative != mustBeNegative) {
                problems.add(NAMES[i] + ": defaultPenalty=" + DEFAULT_PENALTY[i] + " 与「不可通行」不符");
            }
        }
        return problems;
    }
}
