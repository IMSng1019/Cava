package cava.mirror;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块状态 flags 的**唯一 Java 侧定义**：{@code cava_abi.h} 的 {@code CAVA_SF_*}（bit0..7）+
 * {@code CAVA_PF_*}（bit8..26），以及**每一位的精确判据**（来自
 * {@code docs/CAVA-pathfind-oracle-spec.md} §5.4.6 的 javap 转写，不是凭记忆）。
 *
 * <p><b>纪律</b>：本类不引用任何 Minecraft 类型（可脱离服务器单测）。
 * 位号必须与 {@code native/include/cava_abi.h} 逐字一致；{@code CavaMirrorBitsTest} 用硬编码表锁死。
 *
 * <p><b>与原生内核的关系（2026-09-22 实测 + captain 裁决）</b>：内核曾经在
 * {@code native/src/pathfind/cava_pf.h} 里**自带一套位号**，与冻结头文件 19 个位全部错开
 * （air 当活板门 / 铁轨当门 / 火焰当可通行 / 凋灵玫瑰当水方块），而且运行期无法发现。
 * 现在内核已改为**本头文件宏的别名**（纯改号），所以：**Java 侧写的就是内核读的**。
 * 每个谓词的 {@link Pred#kernelReads()} 标出内核当前是否真的读这一位。
 */
public final class MirrorFlags {

    private MirrorFlags() {
    }

    // ------------------------------------------------------------------
    // 位常量（= cava_abi.h，唯一权威）
    // ------------------------------------------------------------------

    /** {@code CAVA_SF_SOLID}：{@code state.isSolid()}。 */
    public static final int SF_SOLID = 1 << 0;
    /** {@code CAVA_SF_BLOCKS_MOTION}：{@code state.blocksMovement()}。 */
    public static final int SF_BLOCKS_MOTION = 1 << 1;
    /** {@code CAVA_SF_FLUID}：{@code !state.getFluidState().isEmpty()}。 */
    public static final int SF_FLUID = 1 << 2;
    /** {@code CAVA_SF_WATER}：{@code state.getFluidState().isIn(FluidTags.WATER)}（含流动水）。 */
    public static final int SF_WATER = 1 << 3;
    /** {@code CAVA_SF_LAVA}：{@code state.getFluidState().isIn(FluidTags.LAVA)}。 */
    public static final int SF_LAVA = 1 << 4;
    /** {@code CAVA_SF_OPEN}：状态含 {@code Properties.OPEN} 且为 true（门/活板门/栅栏门）。 */
    public static final int SF_OPEN = 1 << 5;
    /** {@code CAVA_SF_AIR}：{@code state.isAir()}。内核的 {@code PF_AIR} 别名到这一位。 */
    public static final int SF_AIR = 1 << 6;
    /** {@code CAVA_SF_DOOR}：{@code block instanceof DoorBlock}（任意开关状态）。内核 {@code PF_DOOR}。 */
    public static final int SF_DOOR = 1 << 7;

    /** {@code CAVA_PF_TRAPDOOR}。 */
    public static final int PF_TRAPDOOR = 1 << 8;
    /** {@code CAVA_PF_POWDER_SNOW}。 */
    public static final int PF_POWDER_SNOW = 1 << 9;
    /** {@code CAVA_PF_CACTUS_OR_BERRY}。 */
    public static final int PF_CACTUS_OR_BERRY = 1 << 10;
    /** {@code CAVA_PF_HONEY}。 */
    public static final int PF_HONEY = 1 << 11;
    /** {@code CAVA_PF_COCOA}。 */
    public static final int PF_COCOA = 1 << 12;
    /** {@code CAVA_PF_CAUTIOUS}。 */
    public static final int PF_CAUTIOUS = 1 << 13;
    /** {@code CAVA_PF_DOOR_HAND}：门可否用手开（{@code DoorBlock.canOpenByHand(state)}）。 */
    public static final int PF_DOOR_HAND = 1 << 14;
    /** {@code CAVA_PF_RAIL}。 */
    public static final int PF_RAIL = 1 << 15;
    /** {@code CAVA_PF_LEAVES}。 */
    public static final int PF_LEAVES = 1 << 16;
    /** {@code CAVA_PF_FENCES}：{@code state.isIn(BlockTags.FENCES)}。 */
    public static final int PF_FENCES = 1 << 17;
    /** {@code CAVA_PF_WALLS}：{@code state.isIn(BlockTags.WALLS)}。 */
    public static final int PF_WALLS = 1 << 18;
    /** {@code CAVA_PF_FENCE_GATE}。 */
    public static final int PF_FENCE_GATE = 1 << 19;
    /** {@code CAVA_PF_FIRE_DAMAGE}：{@code inflictsFireDamage(state)}。 */
    public static final int PF_FIRE_DAMAGE = 1 << 20;
    /** {@code CAVA_PF_PATH_THROUGH_LAND}：{@code state.canPathfindThrough(view,pos,LAND)}。 */
    public static final int PF_PATH_THROUGH_LAND = 1 << 21;
    /** {@code CAVA_PF_WATER_BLOCK}：{@code state.isOf(Blocks.WATER)}（区别于"含流体"）。 */
    public static final int PF_WATER_BLOCK = 1 << 22;
    /** {@code CAVA_PF_FENCE_OR_WALL_CLOSED}：派生位 = §5.4.6 第 13 步的整个析取式。 */
    public static final int PF_FENCE_OR_WALL_CLOSED = 1 << 23;
    /** {@code CAVA_PF_DOOR_IRON}：派生位 = 是门且不能用手开。 */
    public static final int PF_DOOR_IRON = 1 << 24;
    /** {@code CAVA_PF_FIRE}：{@code state.isIn(BlockTags.FIRE)}。 */
    public static final int PF_FIRE = 1 << 25;
    /** {@code CAVA_PF_WITHER_ROSE}：{@code state.isOf(Blocks.WITHER_ROSE)}。 */
    public static final int PF_WITHER_ROSE = 1 << 26;

    /**
     * 一个谓词：位号 + 原版判据 + 内核是否读它。
     *
     * <p>枚举序 = 位号升序 = {@code boolean[]} 的下标序，也是文档里逐位定义表的顺序。
     */
    public enum Pred {
        SF_SOLID(MirrorFlags.SF_SOLID, "state.isSolid()", false),
        SF_BLOCKS_MOTION(MirrorFlags.SF_BLOCKS_MOTION, "state.blocksMovement()", false),
        SF_FLUID(MirrorFlags.SF_FLUID, "!state.getFluidState().isEmpty()", false),
        SF_WATER(MirrorFlags.SF_WATER, "state.getFluidState().isIn(FluidTags.WATER)", true),
        SF_LAVA(MirrorFlags.SF_LAVA, "state.getFluidState().isIn(FluidTags.LAVA)", true),
        SF_OPEN(MirrorFlags.SF_OPEN, "state.contains(Properties.OPEN) && state.get(Properties.OPEN)", true),
        SF_AIR(MirrorFlags.SF_AIR, "state.isAir()", true),
        SF_DOOR(MirrorFlags.SF_DOOR, "state.getBlock() instanceof DoorBlock", true),
        PF_TRAPDOOR(MirrorFlags.PF_TRAPDOOR,
                "state.isIn(BlockTags.TRAPDOORS) || isOf(LILY_PAD) || isOf(BIG_DRIPLEAF)", true),
        PF_POWDER_SNOW(MirrorFlags.PF_POWDER_SNOW, "state.isOf(Blocks.POWDER_SNOW)", true),
        PF_CACTUS_OR_BERRY(MirrorFlags.PF_CACTUS_OR_BERRY,
                "state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SWEET_BERRY_BUSH)", true),
        PF_HONEY(MirrorFlags.PF_HONEY, "state.isOf(Blocks.HONEY_BLOCK)", true),
        PF_COCOA(MirrorFlags.PF_COCOA, "state.isOf(Blocks.COCOA)", true),
        PF_CAUTIOUS(MirrorFlags.PF_CAUTIOUS,
                "state.isOf(Blocks.WITHER_ROSE) || state.isOf(Blocks.POINTED_DRIPSTONE)", true),
        PF_DOOR_HAND(MirrorFlags.PF_DOOR_HAND, "DoorBlock.canOpenByHand(state)", true),
        PF_RAIL(MirrorFlags.PF_RAIL, "state.getBlock() instanceof AbstractRailBlock", true),
        PF_LEAVES(MirrorFlags.PF_LEAVES, "state.getBlock() instanceof LeavesBlock", true),
        PF_FENCES(MirrorFlags.PF_FENCES, "state.isIn(BlockTags.FENCES)", true),
        PF_WALLS(MirrorFlags.PF_WALLS, "state.isIn(BlockTags.WALLS)", true),
        PF_FENCE_GATE(MirrorFlags.PF_FENCE_GATE, "state.getBlock() instanceof FenceGateBlock", true),
        PF_FIRE_DAMAGE(MirrorFlags.PF_FIRE_DAMAGE,
                "isIn(BlockTags.FIRE) || isOf(LAVA) || isOf(MAGMA_BLOCK) || CampfireBlock.isLitCampfire(state)"
                        + " || isOf(LAVA_CAULDRON)", true),
        PF_PATH_THROUGH_LAND(MirrorFlags.PF_PATH_THROUGH_LAND,
                "state.canPathfindThrough(view, pos, NavigationType.LAND)", true),
        PF_WATER_BLOCK(MirrorFlags.PF_WATER_BLOCK, "state.isOf(Blocks.WATER)", true),
        PF_FENCE_OR_WALL_CLOSED(MirrorFlags.PF_FENCE_OR_WALL_CLOSED,
                "(FENCES || WALLS || (FENCE_GATE && !OPEN))  —— 派生位，内核不读", false),
        PF_DOOR_IRON(MirrorFlags.PF_DOOR_IRON, "DOOR && !DOOR_HAND —— 派生位，内核不读", false),
        PF_FIRE(MirrorFlags.PF_FIRE, "state.isIn(BlockTags.FIRE) —— 派生位，内核不读", false),
        PF_WITHER_ROSE(MirrorFlags.PF_WITHER_ROSE, "state.isOf(Blocks.WITHER_ROSE) —— 派生位，内核不读", false);

        private final int bit;
        private final String vanilla;
        private final boolean kernelReads;

        Pred(int bit, String vanilla, boolean kernelReads) {
            this.bit = bit;
            this.vanilla = vanilla;
            this.kernelReads = kernelReads;
        }

        /** 位掩码。 */
        public int bit() {
            return bit;
        }

        /** 原版判据（javap 出处见 oracle spec §5.4.6）。 */
        public String vanilla() {
            return vanilla;
        }

        /** 原生内核当前是否读这一位（改号后的别名关系见类注释）。 */
        public boolean kernelReads() {
            return kernelReads;
        }
    }

    /** 谓词个数（= 27：8 个 SF + 19 个 PF）。 */
    public static final int PRED_COUNT = Pred.values().length;

    /** 全部位（含保留位之外的 27 位）。 */
    public static final int ALL_BITS;

    static {
        int all = 0;
        for (Pred p : Pred.values()) {
            all |= p.bit();
        }
        ALL_BITS = all;
    }

    /** 把 {@code boolean[PRED_COUNT]}（下标 = {@link Pred#ordinal()}）打包成 flags。 */
    public static int pack(boolean[] preds) {
        if (preds.length != PRED_COUNT) {
            throw new IllegalArgumentException("preds.length=" + preds.length + " 期望 " + PRED_COUNT);
        }
        int flags = 0;
        for (Pred p : Pred.values()) {
            if (preds[p.ordinal()]) {
                flags |= p.bit();
            }
        }
        return flags;
    }

    /** 置位/清位（MC 侧的采样器用，避免每次分配 boolean[]）。 */
    public static int with(int flags, Pred p, boolean value) {
        return value ? (flags | p.bit()) : (flags & ~p.bit());
    }

    /** 已置位的位名列表（日志/单测用）。 */
    public static List<String> names(int flags) {
        List<String> out = new ArrayList<>();
        for (Pred p : Pred.values()) {
            if ((flags & p.bit()) != 0) {
                out.add(p.name());
            }
        }
        return out;
    }

    /** 是否有未定义的位被置起（= 与头文件漂移的信号）。 */
    public static int unknownBits(int flags) {
        return flags & ~ALL_BITS;
    }

    /** 流体分类（与内核 {@code fluid_of} 同义）：0=无 1=水 2=岩浆。 */
    public static int fluidOf(int flags) {
        if ((flags & SF_LAVA) != 0) {
            return 2;
        }
        if ((flags & SF_WATER) != 0) {
            return 1;
        }
        return 0;
    }

    /**
     * {@code LandPathNodeMaker.getCommonNodeType} 的**位驱动重算**（spec §5.4.6 的 16 步，分支顺序即优先级）。
     *
     * <p>这是**原生内核 {@code common_node_type()} 的逐分支镜像**（含位号别名后的同一套语义），
     * 用途是让"Java 采样的谓词"与"内核据此推出的类型"在**脱离 DLL** 的情况下也能对拍：
     * {@code McStateTableProbeTest} 对**全部真实状态**断言
     * {@code commonNodeType(flags) == 直接按原版方块算出来的类型}。任何一位填错都会在这里红。
     *
     * <p>注意：这一步**不含实体上下文**（那是 adjustNodeType / 惩罚表的事），
     * 所以它给出的正是 {@code path_type_idx} 应有的语义。
     */
    public static int commonNodeType(int flags) {
        if (has(flags, SF_AIR)) {
            return PathTypes.OPEN;
        }
        if (has(flags, PF_TRAPDOOR)) {
            return PathTypes.TRAPDOOR;
        }
        if (has(flags, PF_POWDER_SNOW)) {
            return PathTypes.POWDER_SNOW;
        }
        if (has(flags, PF_CACTUS_OR_BERRY)) {
            return PathTypes.DAMAGE_OTHER;
        }
        if (has(flags, PF_HONEY)) {
            return PathTypes.STICKY_HONEY;
        }
        if (has(flags, PF_COCOA)) {
            return PathTypes.COCOA;
        }
        if (has(flags, PF_CAUTIOUS)) {
            return PathTypes.DAMAGE_CAUTIOUS;
        }
        if (fluidOf(flags) == 2) {
            return PathTypes.LAVA;
        }
        if (has(flags, PF_FIRE_DAMAGE)) {
            return PathTypes.DAMAGE_FIRE;
        }
        if (has(flags, SF_DOOR)) {
            if (has(flags, SF_OPEN)) {
                return PathTypes.DOOR_OPEN;
            }
            return has(flags, PF_DOOR_HAND) ? PathTypes.DOOR_WOOD_CLOSED : PathTypes.DOOR_IRON_CLOSED;
        }
        if (has(flags, PF_RAIL)) {
            return PathTypes.RAIL;
        }
        if (has(flags, PF_LEAVES)) {
            return PathTypes.LEAVES;
        }
        if (has(flags, PF_FENCES) || has(flags, PF_WALLS)
                || (has(flags, PF_FENCE_GATE) && !has(flags, SF_OPEN))) {
            return PathTypes.FENCE;
        }
        if (!has(flags, PF_PATH_THROUGH_LAND)) {
            return PathTypes.BLOCKED;
        }
        if (fluidOf(flags) == 1) {
            return PathTypes.WATER;
        }
        return PathTypes.OPEN;
    }

    private static boolean has(int flags, int bit) {
        return (flags & bit) != 0;
    }
}
