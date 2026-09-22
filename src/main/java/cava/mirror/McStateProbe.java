package cava.mirror;

import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;

/**
 * 真实注册表上的 {@link StateProbe}：**唯一的原版语义入口**。
 *
 * <p>键 = state id（{@code Block.getStateFromRawId(id)}），**绝不用对象身份**。
 * 每个谓词的判据逐条来自 {@code docs/CAVA-pathfind-oracle-spec.md} §5.4.6（javap 转写）。
 *
 * <p>{@code commonType} 用**原版方块直接算**（不经过 flags），
 * 这样 {@code McStateTableProbeTest} 就能断言 {@code MirrorFlags.commonNodeType(flags) == commonType}
 * —— 一条"我采的位"与"内核据位推出的类型"之间的独立对拍。
 */
public final class McStateProbe implements StateProbe {

    /** 采样位置：形状只通过 {@link ProbeWorldView} 看邻居，所以这个坐标本身不参与语义。 */
    public static final BlockPos PROBE_POS = new BlockPos(8, 64, 8);

    private final ProbeWorldView view;
    private final ProbeWorldView shapeView;

    /** 默认：邻居=空气（ABI 能表达的那一档）。 */
    public McStateProbe() {
        this(ProbeWorldView.MODE_EMPTY);
    }

    McStateProbe(int mode) {
        this.view = new ProbeWorldView(mode);
        this.shapeView = new ProbeWorldView(mode);
    }

    @Override
    public int stateCount() {
        return Block.STATE_IDS.size();
    }

    /** 采样位置的坐标（文档/单测引用）。 */
    public static BlockPos probePos() {
        return PROBE_POS;
    }

    /** 普查用的第二个位置（形状若与 pos 有关，两个位置会给出不同的盒）。 */
    private static final BlockPos PROBE_POS_B = new BlockPos(0, -60, 0);

    /**
     * **静态信号**：声明了"带 world/pos（/context）"的形状方法的方块类。
     *
     * <p>判据：类自己声明了 {@code getCollisionShape} / {@code getShape} / {@code getOutlineShape}
     * 且形参数为 3 或 4（= 带 {@code BlockView} / {@code BlockPos} / {@code ShapeContext}）。
     * 基类实现（{@code AbstractBlock}）不看这些参数，所以只有覆写者才可能位置/上下文相关。
     */
    public static java.util.Set<String> shapeSensitiveBlockClasses() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (net.minecraft.block.Block block : net.minecraft.registry.Registries.BLOCK) {
            Class<?> c = block.getClass();
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (!n.equals("getCollisionShape") && !n.equals("getShape") && !n.equals("getOutlineShape")) {
                    continue;
                }
                int p = m.getParameterCount();
                if (p == 3 || p == 4) {
                    out.add(c.getName());
                    break;
                }
            }
        }
        return out;
    }

    /**
     * **经验信号**：该状态的碰撞盒在"多个合成上下文"下是否会变。
     *
     * <p>变体 = {邻居: 空气/石头/自身} × {位置: (8,64,8) / (0,-60,0)} ×
     * {ShapeContext: absent / 实体在上方 / 正在下降}（共 18 个，含基准）。
     * 只要有一个变体与基准不同，就说明**冻结 ABI 的一组盒表达不了它** ⇒ 必须走保守守卫
     * （captain 裁决 1：宁可不加速，也不出错）。
     *
     * <p>这是**启发式**，不是证明：它只能覆盖这 18 个变体。剩下的风险写进 docs §3.1。
     */
    public static boolean shapeVariesAcrossContexts(net.minecraft.block.BlockState state) {
        java.util.List<net.minecraft.util.math.Box> base = boxesOf(state, ProbeWorldView.MODE_EMPTY, PROBE_POS, null);
        int[] modes = {ProbeWorldView.MODE_EMPTY, ProbeWorldView.MODE_SOLID, ProbeWorldView.MODE_SELF};
        BlockPos[] positions = {PROBE_POS, PROBE_POS_B};
        net.minecraft.block.ShapeContext[] contexts = {null, ProbeShapeContext.ABOVE, ProbeShapeContext.DESCENDING};
        for (int mode : modes) {
            for (BlockPos pos : positions) {
                for (net.minecraft.block.ShapeContext ctx : contexts) {
                    if (!boxesOf(state, mode, pos, ctx).equals(base)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 诊断：把一个状态在几个合成上下文下的碰撞盒打出来（探针/排查用，不在生产路径上）。
     *
     * <p>用途：判断"某个方块的**碰撞**盒到底会不会随邻居/上下文变" —— 这决定了
     * 冻结 ABI 的"一个状态一组盒"到底够不够用（captain 裁决 1 的守卫范围）。
     */
    public static String debugShapes(net.minecraft.block.BlockState state) {
        return "air=" + boxesOf(state, ProbeWorldView.MODE_EMPTY, PROBE_POS, null)
                + " solid=" + boxesOf(state, ProbeWorldView.MODE_SOLID, PROBE_POS, null)
                + " self=" + boxesOf(state, ProbeWorldView.MODE_SELF, PROBE_POS, null)
                + " ctxAbove=" + boxesOf(state, ProbeWorldView.MODE_EMPTY, PROBE_POS, ProbeShapeContext.ABOVE);
    }

    private static java.util.List<net.minecraft.util.math.Box> boxesOf(net.minecraft.block.BlockState state,
                                                                     int mode, BlockPos pos,
                                                                     net.minecraft.block.ShapeContext ctx) {
        ProbeWorldView view = new ProbeWorldView(mode);
        view.set(state);
        VoxelShape shape = ctx == null ? state.getCollisionShape(view, pos)
                : state.getCollisionShape(view, pos, ctx);
        return shape.getBoundingBoxes();
    }

    @Override
    public void probe(int stateId, StateSample out) {
        BlockState st = Block.getStateFromRawId(stateId);
        view.set(st);
        shapeView.set(st);
        Block blk = st.getBlock();

        boolean air = st.isAir();
        boolean door = blk instanceof DoorBlock;
        boolean gate = blk instanceof FenceGateBlock;
        boolean open = st.contains(Properties.OPEN) && st.get(Properties.OPEN);
        boolean fenceTag = st.isIn(BlockTags.FENCES);
        boolean wallTag = st.isIn(BlockTags.WALLS);
        FluidState fluid = st.getFluidState();
        boolean fire = st.isIn(BlockTags.FIRE);
        boolean fireDamage = fire || st.isOf(Blocks.LAVA) || st.isOf(Blocks.MAGMA_BLOCK)
                || CampfireBlock.isLitCampfire(st) || st.isOf(Blocks.LAVA_CAULDRON);
        boolean doorHand = door && ((DoorBlock) blk).getBlockSetType().canOpenByHand();

        out.set(MirrorFlags.Pred.SF_SOLID, st.isSolid());
        out.set(MirrorFlags.Pred.SF_BLOCKS_MOTION, st.blocksMovement());
        out.set(MirrorFlags.Pred.SF_FLUID, !fluid.isEmpty());
        out.set(MirrorFlags.Pred.SF_WATER, fluid.isIn(FluidTags.WATER));
        out.set(MirrorFlags.Pred.SF_LAVA, fluid.isIn(FluidTags.LAVA));
        out.set(MirrorFlags.Pred.SF_OPEN, open);
        out.set(MirrorFlags.Pred.SF_AIR, air);
        out.set(MirrorFlags.Pred.SF_DOOR, door);

        out.set(MirrorFlags.Pred.PF_TRAPDOOR,
                st.isIn(BlockTags.TRAPDOORS) || st.isOf(Blocks.LILY_PAD) || st.isOf(Blocks.BIG_DRIPLEAF));
        out.set(MirrorFlags.Pred.PF_POWDER_SNOW, st.isOf(Blocks.POWDER_SNOW));
        out.set(MirrorFlags.Pred.PF_CACTUS_OR_BERRY,
                st.isOf(Blocks.CACTUS) || st.isOf(Blocks.SWEET_BERRY_BUSH));
        out.set(MirrorFlags.Pred.PF_HONEY, st.isOf(Blocks.HONEY_BLOCK));
        out.set(MirrorFlags.Pred.PF_COCOA, st.isOf(Blocks.COCOA));
        out.set(MirrorFlags.Pred.PF_CAUTIOUS,
                st.isOf(Blocks.WITHER_ROSE) || st.isOf(Blocks.POINTED_DRIPSTONE));
        out.set(MirrorFlags.Pred.PF_DOOR_HAND, doorHand);
        out.set(MirrorFlags.Pred.PF_RAIL, blk instanceof AbstractRailBlock);
        out.set(MirrorFlags.Pred.PF_LEAVES, blk instanceof LeavesBlock);
        out.set(MirrorFlags.Pred.PF_FENCES, fenceTag);
        out.set(MirrorFlags.Pred.PF_WALLS, wallTag);
        out.set(MirrorFlags.Pred.PF_FENCE_GATE, gate);
        out.set(MirrorFlags.Pred.PF_FIRE_DAMAGE, fireDamage);
        out.set(MirrorFlags.Pred.PF_PATH_THROUGH_LAND,
                st.canPathfindThrough(view, PROBE_POS, NavigationType.LAND));
        out.set(MirrorFlags.Pred.PF_WATER_BLOCK, st.isOf(Blocks.WATER));

        // 派生位（头文件有、内核不读；填对是为了文档完整与将来的子系统复用）
        out.set(MirrorFlags.Pred.PF_FENCE_OR_WALL_CLOSED,
                fenceTag || wallTag || (gate && !open));
        out.set(MirrorFlags.Pred.PF_DOOR_IRON, door && !doorHand);
        out.set(MirrorFlags.Pred.PF_FIRE, fire);
        out.set(MirrorFlags.Pred.PF_WITHER_ROSE, st.isOf(Blocks.WITHER_ROSE));

        out.commonType = commonNodeType(st, blk, fluid, air, door, gate, open, fenceTag, wallTag, fireDamage, doorHand);

        // 碰撞盒：**扁平 AABB 集合**（契约要求，不是体素近似）
        VoxelShape shape = st.getCollisionShape(shapeView, PROBE_POS);
        if (!shape.isEmpty()) {
            for (Box b : shape.getBoundingBoxes()) {
                out.addBox((float) b.minX, (float) b.minY, (float) b.minZ,
                        (float) b.maxX, (float) b.maxY, (float) b.maxZ);
            }
        }
    }

    /** spec §5.4.6 的 16 步（**直读原版方块，不经过 flags**）。 */
    private int commonNodeType(BlockState st, Block blk, FluidState fluid, boolean air, boolean door,
                                      boolean gate, boolean open, boolean fenceTag, boolean wallTag,
                                      boolean fireDamage, boolean doorHand) {
        if (air) {
            return PathTypes.OPEN;
        }
        if (st.isIn(BlockTags.TRAPDOORS) || st.isOf(Blocks.LILY_PAD) || st.isOf(Blocks.BIG_DRIPLEAF)) {
            return PathTypes.TRAPDOOR;
        }
        if (st.isOf(Blocks.POWDER_SNOW)) {
            return PathTypes.POWDER_SNOW;
        }
        if (st.isOf(Blocks.CACTUS) || st.isOf(Blocks.SWEET_BERRY_BUSH)) {
            return PathTypes.DAMAGE_OTHER;
        }
        if (st.isOf(Blocks.HONEY_BLOCK)) {
            return PathTypes.STICKY_HONEY;
        }
        if (st.isOf(Blocks.COCOA)) {
            return PathTypes.COCOA;
        }
        if (st.isOf(Blocks.WITHER_ROSE) || st.isOf(Blocks.POINTED_DRIPSTONE)) {
            return PathTypes.DAMAGE_CAUTIOUS;
        }
        if (fluid.isIn(FluidTags.LAVA)) {
            return PathTypes.LAVA;
        }
        if (fireDamage) {
            return PathTypes.DAMAGE_FIRE;
        }
        if (door) {
            if (open) {
                return PathTypes.DOOR_OPEN;
            }
            return doorHand ? PathTypes.DOOR_WOOD_CLOSED : PathTypes.DOOR_IRON_CLOSED;
        }
        if (blk instanceof AbstractRailBlock) {
            return PathTypes.RAIL;
        }
        if (blk instanceof LeavesBlock) {
            return PathTypes.LEAVES;
        }
        if (fenceTag || wallTag || (gate && !open)) {
            return PathTypes.FENCE;
        }
        if (!st.canPathfindThrough(view, PROBE_POS, NavigationType.LAND)) {
            return PathTypes.BLOCKED;
        }
        if (fluid.isIn(FluidTags.WATER)) {
            return PathTypes.WATER;
        }
        return PathTypes.OPEN;
    }
}
