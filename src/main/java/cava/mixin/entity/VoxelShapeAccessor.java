package cava.mixin.entity;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelSet;
import net.minecraft.util.shape.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code VoxelShape} 的两个**只读**读取口 —— 让 Java 侧能拿到「点表 + 体素位图」。
 *
 * <h2>为什么这里破例使用 {@code @Invoker}（本项目的第一次）</h2>
 * 工程契约禁止的是 {@code @Overwrite} 与 {@code @Redirect}：它们**替换/改写目标方法体内的调用**，
 * 会和别的 mod 硬冲突，或者在别人也打了补丁时**静默丢弃**其中一方。
 *
 * <p>{@code @Invoker} 不是同一类东西：
 * <ol>
 *   <li>它**不修改目标方法的任何一条指令**，只是在目标类上加一个薄包装方法，
 *       方法体只有「调用原版自己的那个方法」一条 invoke 指令
 *       （实测 {@code Bytecode.invokeMethod} 对非 private 目标生成 {@code INVOKEVIRTUAL}）；</li>
 *   <li>所以**原版行为 = 0 变化**：没有它的时候 {@code getPointPositions} 照样是那样；
 *       有了它只是多了一个调用入口。别的 mod 即使也 mixin 了这个方法，也不会被覆盖或丢弃；</li>
 *   <li>反过来，**它是唯一可行的形态**：{@code VoxelShape.getPointPositions(Axis)} 是
 *       {@code protected abstract}（javap 实读），{@code voxels} 字段是 {@code protected final}；
 *       而 {@code getBoundingBoxes()} 反推点表会引入 &lt;1e-7 的量化误差
 *       —— 那正是原版做碰撞判定的量级（{@code 1.0E-7} 守卫），对 parity 不可接受。
 *       反射读私有成员在生产环境**必然失效**（MC 类被 remap 成 intermediary，
 *       见 {@code AmphibiousPathNodeMakerAccessor} 的实测记录）；</li>
 *   <li>{@code @Accessor}/{@code @Invoker} 的名字会被 Loom 在 remapJar 时改写成 intermediary，
 *       这是本项目已经验证过的形态（P1 的 {@code PathNodeNavigatorAccessor}）。</li>
 * </ol>
 *
 * <p><b>只用它做读取。</b>本接口没有任何写入口、没有任何 {@code @Redirect}/{@code @Overwrite}。
 *
 * <p>{@code getPointPositions} 返回的是 fastutil 的 {@link DoubleList}，可以逐点 {@code getDouble(i)}；
 * {@code VoxelSet} 的 {@code contains(int,int,int)} / {@code getXSize()} 等本来就是 {@code public}
 * （javap 实读），不需要再开接口。
 */
@Mixin(VoxelShape.class)
public interface VoxelShapeAccessor {

    /** {@code protected final VoxelSet voxels} —— 体素位图的宿主。 */
    @Accessor("voxels")
    VoxelSet cava$voxels();

    /**
     * {@code protected abstract DoubleList getPointPositions(Direction.Axis)}。
     *
     * <p>返回的列表长度 = {@code size(axis) + 1}；语义由子类决定：
     * {@code SimpleVoxelShape} 给 {@code i/size}，{@code ArrayVoxelShape} 给显式点表
     * （{@code offset()} 之后是 {@code OffsetDoubleList}，逐点就是「原值 + 平移量」）。
     */
    @Invoker("getPointPositions")
    DoubleList cava$getPointPositions(Direction.Axis axis);
}
