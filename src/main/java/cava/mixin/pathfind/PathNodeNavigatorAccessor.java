package cava.mixin.pathfind;

import net.minecraft.entity.ai.pathing.PathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code PathNodeNavigator} 的两个私有字段的读取口（**只读，不改语义**）。
 *
 * <p>需要它们是因为编排要复刻两个原版量：
 * <ul>
 *   <li>{@code pathNodeMaker}：从它读 {@code canOpenDoors/canEnterOpenDoors/canSwim/canWalkOverFences}
 *       —— 这些是 {@code CAVA_NAV_*} 能力位的**唯一权威来源**（不从生物类型猜）；</li>
 *   <li>{@code range}：原版节点预算 {@code (int)((float)range * followRange)}（oracle spec 4.1）。</li>
 * </ul>
 *
 * <p>用 {@code @Accessor} 而不是 {@code @Redirect}/{@code @Overwrite}：它不修改目标方法的任何表达式，
 * 只生成一个读字段的方法（契约允许的形态）。
 */
@Mixin(PathNodeNavigator.class)
public interface PathNodeNavigatorAccessor {

    /** {@code private final PathNodeMaker pathNodeMaker}。 */
    @Accessor("pathNodeMaker")
    PathNodeMaker cava$pathNodeMaker();

    /** {@code private final int range}。 */
    @Accessor("range")
    int cava$range();
}
