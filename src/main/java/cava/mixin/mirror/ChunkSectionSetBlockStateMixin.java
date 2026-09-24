package cava.mixin.mirror;

import cava.mirror.SectionOriginRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.ChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 镜像主钩子：{@code ChunkSection.setBlockState(method_12256)} —— **只观察，不 cancel、不 redirect、不返回值**。
 *
 * <p><b>为什么是这一层</b>（{@code docs/CAVA-hook-points.md} 第 17 行，两条独立证据收敛）：
 * ① Axiom 的建造包绕过 {@code World.setBlockState}，从 {@code ServerWorld.getChunk →
 * WorldChunk.getSection → ChunkSection.setBlockState} **直写**，只挂世界级 setBlockState 会漏；
 * ② Carpet/TIS 都没碰这里（而 {@code WorldChunk.setBlockState} 被 Carpet 压了两个 mixin）。
 *
 * <p><b>映射核实（本机实测，2026-09-24）</b>：Yarn tiny
 * （{@code .gradle-home/caches/fabric-loom/1.20.4/net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny}）
 * 里 {@code net/minecraft/world/chunk/ChunkSection} = {@code net/minecraft/class_2826}，
 * 两个重载：
 * <ul>
 *   <li>{@code setBlockState(IIILdjh;)Ldjh;} = {@code method_16675}</li>
 *   <li>{@code setBlockState(IIILdjh;Z)Ldjh;} = <b>{@code method_12256}</b>（djh = BlockState）</li>
 * </ul>
 * 本机 {@code javap -p -c} 实测：**4 参重载体内直接 {@code invokevirtual} 调 5 参重载**，
 * 所以只钩 5 参这一个点就覆盖两条入口。
 *
 * <p><b>坐标是区段局部坐标</b>：{@code ChunkSection} 里没有任何位置字段（javap 实测只有
 * {@code nonEmptyBlockCount/randomTickableBlockCount/nonEmptyFluidCount/blockStateContainer/biomeContainer}），
 * 所以世界坐标由 {@link SectionOriginRegistry} 的"区段实例 → 原点"身份表还原
 * （表在镜像推送时逐区段登记）。**这是一个必须记住的事实**：只写 {@code x/y/z} 三个 int
 * 是拿不到世界坐标的。
 *
 * <p><b>注入纪律</b>：{@code @Inject(at = HEAD, cancellable = false, require = 0)} + 类级显式
 * {@code priority = 1000}；**绝不 {@code @Overwrite} / {@code @Redirect}**。
 * {@code require = 0}：目标点找不到时不崩、只是不生效（配套的门禁是
 * {@link SectionOriginRegistry#inWindowHits()} 金丝雀计数 + {@code /cava pathfind invalidate} 实测）。
 * 说明：Mixin 0.8.7 的 {@code @Inject} **没有 priority 元素**（javap 实测成员表：
 * id/method/target/slice/at/cancellable/locals/remap/require/expect/allow/constraints/order），
 * 同一注入点上的顺序旋钮是 {@code order}（这里显式写 1000 = 默认值，语义是"不做优先级战争"）。
 *
 * <p><b>同点占用情况</b>：审计（{@code docs/CAVA-hook-points.md}）记录 Lithium 在此点有
 * {@code @Inject}（计数）、Noisium 只在**调用点** {@code @Redirect}。{@code @Inject} + {@code @Inject}
 * 不冲突；{@code @Redirect} 在调用点、与本点无关。本轮没有改别人任何东西。
 */
@Mixin(value = ChunkSection.class, priority = 1000)
public abstract class ChunkSectionSetBlockStateMixin {

    /**
     * 观察入口：{@code (lx, ly, lz)} 是**区段局部坐标**（0..15），世界坐标由镜像侧的登记表还原。
     */
    @Inject(
            method = "setBlockState(IIILnet/minecraft/block/BlockState;Z)Lnet/minecraft/block/BlockState;",
            at = @At("HEAD"), cancellable = false, require = 0, order = 1000)
    private void cava$observeSetBlockState(int lx, int ly, int lz, BlockState state, boolean lock,
                                           CallbackInfoReturnable<BlockState> cir) {
        // O(1) 且**绝不抛异常**（实现内部吞异常）：挂在方块写入路径上，抛出去就是世界写入被中断。
        SectionOriginRegistry.onSectionBlockWrite(this, lx, ly, lz);
    }
}
