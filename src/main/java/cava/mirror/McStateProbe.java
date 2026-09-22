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
