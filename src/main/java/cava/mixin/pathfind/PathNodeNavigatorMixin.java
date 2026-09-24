package cava.mixin.pathfind;

import cava.hook.PathfindHook;
import java.util.Map;
import java.util.Set;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.ai.pathing.TargetPathNode;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.chunk.ChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * P1 的注入点：{@code PathNodeNavigator.findPathToAny} 的**两个重载**（任务清单第 1 条）。
 *
 * <p>Yarn 名 {@code findPathToAny} = Mojang 名 {@code findPath}；intermediary：
 * <ul>
 *   <li>Set 版（public）= {@code method_52}
 *       {@code (ChunkCache, MobEntity, Set, float maxRange, int reachRange, float followRange) -> Path}</li>
 *   <li>Map 版（private）= {@code method_54}
 *       {@code (Profiler, PathNode start, Map<TargetPathNode,BlockPos>, float, int, float) -> Path}</li>
 * </ul>
 * 映射由 {@code node tools/mapquery.cjs method PathNodeNavigator findPathToAny} 实测；
 * 字节码顺序见 oracle spec 与 {@code javap -p -c}（public 版在 75 行处 {@code invokevirtual} 调 private 版）。
 *
 * <p><b>注入纪律</b>：{@code @Inject(at = HEAD, cancellable = true)}、{@code require = 0}、
 * 类级 {@code priority = 1000}。**绝不 {@code @Overwrite} / {@code @Redirect}**。
 *
 * <p><b>两个重载为什么行为不对称</b>：Map 版是 private 内层，唯一调用者是 Set 版，
 * 且它的形参里**没有实体**（宽高/惩罚表/位姿都取不到）⇒ 它只参与金丝雀计数，不做接管。
 * 计数用一次性"逻辑调用"标记去重，避免同一次寻路被算两次。
 *
 * <p><b>取消的副作用（已记录）</b>：ServerCore 在 Set 版方法体内有 4 个 {@code @Redirect}
 * + 2 个 {@code @ModifyVariable}（把 Map/Set 换成 fastutil 实现并预填）。一旦我们在 HEAD 取消，
 * 它们自然不执行 —— prompts/04 判断"它们只换容器实现"，不影响可观测结果；**该判断尚未做差分验证**。
 * 而在当前配置下（{@code cava.pathfind.native} 默认 false）我们**从不取消**，所以这条风险目前是 0。
 */
@Mixin(value = PathNodeNavigator.class, priority = 1000)
public abstract class PathNodeNavigatorMixin {

    /**
     * Set 版（public，{@code method_52}）：金丝雀计数 + 尝试原生接管。
     */
    @Inject(
            method = "findPathToAny(Lnet/minecraft/world/chunk/ChunkCache;Lnet/minecraft/entity/mob/MobEntity;"
                    + "Ljava/util/Set;FIF)Lnet/minecraft/entity/ai/pathing/Path;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void cava$headPublic(ChunkCache chunkCache, MobEntity mob, Set<BlockPos> targets,
                                 float maxRange, int reachRange, float followRange,
                                 CallbackInfoReturnable<Path> cir) {
        PathfindHook hook = PathfindHook.INSTANCE;
        hook.enterOuterCall();
        hook.onHookEntry();
        if (!hook.hookEnabled()) {
            return;   // 契约 §3：enabled()==false 时钩子**完全不介入**
        }
        Object self = this;
        if (!(self instanceof PathNodeNavigator navigator)) {
            return;
        }
        // P1-NET：把这一次调用的"规模口径"记下来（**两条腿都要**：off 腿的分布才是真值）。
        // navRange 从访问器拿（原版节点预算 = navRange × followRange，即搜索展开规模的上界）。
        int navRange = (self instanceof PathNodeNavigatorAccessor acc) ? acc.cava$range() : -1;
        hook.onCallArgs(mob, targets, maxRange, reachRange, followRange, navRange);
        Path replaced = hook.tryTakeover(navigator, chunkCache, mob, targets, maxRange, reachRange, followRange);
        if (replaced != null) {
            cir.setReturnValue(replaced);   // cancellable=true ⇒ setReturnValue 即取消原方法体
        }
    }

    /**
     * Set 版的 {@code RETURN}（P1-NET 加）：**给"这一次调用最终走了 Java"记账**。
     *
     * <p>为什么必须是独立的 RETURN 注入：HEAD 注入只能看到"要不要接管"，看不到"没接管时这条路径花了多久"。
     * 而"打开开关净赚还是净亏"要的是**两边的总时间**：off 腿全是这一边，on 腿是"接管的原生那段 +
     * 回退/被分流时的 Java 那段"。没有 RETURN 就只能拿合成场景的每次调用耗时去推（P1-PERF 文档里
     * 那列"每 tick 投影"就是推的），本流要的是直测。
     *
     * <p>{@code require = 0}、不改返回值、不取消：{@code cir.getReturnValue()} 只读。
     * 接管成功的那次调用由 {@code PathfindHook} 自己判重（探针 {@code accounted}），不会重复记账；
     * 即使 Mixin 在 {@code setReturnValue} 之后不再经过 RETURN，也只是少记一次"零成本"的分支。
     */
    @Inject(
            method = "findPathToAny(Lnet/minecraft/world/chunk/ChunkCache;Lnet/minecraft/entity/mob/MobEntity;"
                    + "Ljava/util/Set;FIF)Lnet/minecraft/entity/ai/pathing/Path;",
            at = @At("RETURN"), require = 0)
    private void cava$returnPublic(ChunkCache chunkCache, MobEntity mob, Set<BlockPos> targets,
                                   float maxRange, int reachRange, float followRange,
                                   CallbackInfoReturnable<Path> cir) {
        PathfindHook.INSTANCE.onCallReturn(cir.getReturnValue(), mob, targets);
    }

    /**
     * Map 版（private 内层，{@code method_54}）：只做金丝雀计数（内层没有实体上下文，不接管）。
     */
    @Inject(
            method = "findPathToAny(Lnet/minecraft/util/profiler/Profiler;"
                    + "Lnet/minecraft/entity/ai/pathing/PathNode;Ljava/util/Map;FIF)"
                    + "Lnet/minecraft/entity/ai/pathing/Path;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void cava$headPrivate(Profiler profiler, PathNode start, Map<TargetPathNode, BlockPos> targetMap,
                                  float maxRange, int reachRange, float followRange,
                                  CallbackInfoReturnable<Path> cir) {
        if (PathfindHook.INSTANCE.enterInnerCall()) {
            return;   // 外层已经为这次逻辑调用计过数
        }
        PathfindHook.INSTANCE.onHookEntry();
    }
}
