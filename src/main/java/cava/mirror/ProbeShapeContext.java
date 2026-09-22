package cava.mirror;

import net.minecraft.block.ShapeContext;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

/**
 * 合成 {@link ShapeContext}：用来把"带实体的碰撞查询"（原版走 {@code ShapeContext.of(entity)}）
 * 也纳入形状普查。
 *
 * <p>为什么需要它：冻结 ABI 里没有 {@code ShapeContext} 通道，而真实碰撞查询用的是
 * {@code world.getBlockCollisions(entity, box)} ⇒ {@code ShapeContext.of(entity)}。
 * 我们只能给 {@code absent()}，所以必须**知道哪些方块的形状会因此不同**
 * （脚手架是最典型的：{@code isAbove(...)} 为真才有碰撞形状）。
 *
 * <p>本类只在**建表时的普查**与探针里使用，不参与生产路径。
 */
final class ProbeShapeContext implements ShapeContext {

    /** 模拟"实体站在方块上方"（脚手架/雪层等会看这个）。 */
    static final ProbeShapeContext ABOVE = new ProbeShapeContext(true, false);
    /** 模拟"实体正在下降"。 */
    static final ProbeShapeContext DESCENDING = new ProbeShapeContext(false, true);

    private final boolean above;
    private final boolean descending;

    private ProbeShapeContext(boolean above, boolean descending) {
        this.above = above;
        this.descending = descending;
    }

    @Override
    public boolean isDescending() {
        return descending;
    }

    @Override
    public boolean isAbove(VoxelShape shape, BlockPos pos, boolean defaultValue) {
        return above;
    }

    @Override
    public boolean isHolding(Item item) {
        return false;
    }

    @Override
    public boolean canWalkOnFluid(FluidState state, FluidState fluidState) {
        return false;
    }
}
