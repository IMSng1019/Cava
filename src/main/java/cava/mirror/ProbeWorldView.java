package cava.mirror;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * 采样碰撞形状 / {@code canPathfindThrough} 用的**合成世界视图**。
 *
 * <p>为什么需要它：原版 API 是 {@code state.getCollisionShape(world, pos)} —— **形状依赖位置**
 * （栅栏/墙/铁栏杆/玻璃板的形状由邻居决定；脚手架还依赖 {@code ShapeContext}）。
 * 而冻结的 ABI 里每个 state id **只能有一条记录、一组盒**，表达不了"按位置不同的形状"。
 *
 * <p>本视图就是文档里说的"足够通用的世界视图"，两种模式：
 * <ul>
 *   <li>{@link #MODE_EMPTY}（**默认，本轮选用**）：所有被查询的邻居位置返回**空气**。
 *       理由（写进 docs/CAVA-mirror-notes.md §3）：对"孤立方块"（最常见的栅栏柱、单根栏杆、
 *       独立墙柱）这是**精确值**；对"连成一排"的情况它**低估**了 2/16 厚的连接条，
 *       而那一条永远指向**相邻的同类方块**（本身就是 FENCE 这类不可通行节点）。</li>
 *   <li>{@link #MODE_SELF}：所有邻居位置返回**被求值的状态自身**（= 全连接）。它是"最大"形状，
 *       用于 {@code McStateTableProbeTest} 的**位置依赖普查**：两种模式结果不同的状态数，
 *       就是"ABI 表达不了"的状态数（这个数字写进文档，作为已知风险量化）。</li>
 * </ul>
 *
 * <p>{@code getBlockEntity} 一律返回 {@code null}：碰撞形状的默认实现不使用方块实体，
 * 用到它的方块（如箱子）也只在 {@code getOutlineShape} 里用，与寻路无关。
 */
final class ProbeWorldView implements BlockView {

    /** 邻居查询返回空气。 */
    static final int MODE_EMPTY = 0;
    /** 邻居查询返回被求值状态自身（= 全连接）。 */
    static final int MODE_SELF = 1;
    /** 邻居查询返回**实心整方块**（石头）——用于形状普查里"邻居都是固体"这一档。 */
    static final int MODE_SOLID = 2;

    private final int mode;
    private BlockState self = Blocks.AIR.getDefaultState();

    ProbeWorldView(int mode) {
        this.mode = mode;
    }

    void set(BlockState state) {
        this.self = state;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    /**
     * {@code HeightLimitView.getBottomY()} 是 {@code BlockView} 的抽象成员之一。
     *
     * <p>返回 {@code 0}：本视图**只**用于采样形状/通行性，被采样方块本身由 {@code set()} 给定；
     * 需要"世界下界"的原版实现（如 {@code getLandNodeType}）根本不在这条路径上。
     * 这个值只影响"方块实现自己去查世界高度"的极端情况，而碰撞形状里没有这种用法。
     */
    @Override
    public int getBottomY() {
        return 0;
    }

    /** {@code HeightLimitView.getHeight()}：同上，只影响"方块自己去查世界高度"的极端情况。 */
    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return switch (mode) {
            case MODE_SELF -> self;
            case MODE_SOLID -> Blocks.STONE.getDefaultState();
            default -> Blocks.AIR.getDefaultState();
        };
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return switch (mode) {
            case MODE_SELF -> self.getFluidState();
            case MODE_SOLID -> Blocks.STONE.getDefaultState().getFluidState();
            default -> Blocks.AIR.getDefaultState().getFluidState();
        };
    }
}
