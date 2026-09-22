package cava.shape;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * 建**常驻形状表**时用的合成世界视图：所有被查询的邻居位置返回空气。
 *
 * <p>为什么需要它：原版 {@code BlockState.getCollisionShape(world, pos)} 的签名就说明形状**可以**依赖
 * 位置/邻居。而冻结的 ABI 里每个 state id 只有**一条**记录。
 *
 * <p><b>这不是"猜"</b>：本视图只用来生产**常驻表的候选几何**。
 * 运行期每一个真正参与求解的形状都会与它逐点、逐位做**精确相等校验**
 * （见 {@link MoveShapeBatch}）；校验不过就改用 {@code CAVA_MSHAPE_INLINE} 把该形状的真实几何内联过去。
 * 所以「候选表错了」只会让快路径少命中，**不会**造成行为差异。
 *
 * <p>本类与 {@code cava.mirror.ProbeWorldView} 语义一致（那边是包私有的，且属于 mirror 流的路径，
 * 本流不碰）。{@code getBlockEntity} 一律 {@code null}：碰撞形状的默认实现不使用方块实体。
 */
public final class ShapeProbeView implements BlockView {

    /** 当前被采样的状态（只为诊断；视图本身对所有位置都返回空气）。 */
    private BlockState sampled = Blocks.AIR.getDefaultState();

    public void set(BlockState state) {
        this.sampled = state;
    }

    /** 当前被采样的状态。 */
    public BlockState sampled() {
        return sampled;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getBottomY() {
        return 0;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Blocks.AIR.getDefaultState().getFluidState();
    }
}
