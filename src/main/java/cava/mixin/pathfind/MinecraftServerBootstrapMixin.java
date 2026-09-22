package cava.mixin.pathfind;

import cava.hook.PathfindBootstrap;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * **只做自举**的注入点：服务端主循环入口 {@code MinecraftServer.runServer()} 的 HEAD。
 *
 * <p><b>为什么需要它</b>（真实的踩坑记录）：最初的实现只在 {@code findPathToAny} 的注入体里
 * 调 {@link PathfindBootstrap#ensureInstalled()} —— 于是产生**鸡生蛋问题**：
 * 金丝雀探测本身要等第一次寻路才会被注册，而"有没有寻路"正是待验证的事情。
 * 实测：gate-preview 真实服务端跑 75 秒，日志里**一条 {@code [cava/pathfind]} 都没有**，
 * 无法区分"注入没生效"和"这段窗口内没有寻路发生"。这正是门禁 #6 要抓的**静默失效**。
 *
 * <p>所以改成在服务端主循环入口无条件自举一次，探测就能在 SERVER_STARTED（或首个 tick）
 * 主动发起一次真实寻路。注入体里的 {@code ensureInstalled()} 保留作为幂等兜底。
 *
 * <p><b>行为影响 = 0</b>：{@code @Inject(at = HEAD)}、不 cancellable、不改任何表达式、
 * {@code require = 0}，只在方法入口调用一次幂等的注册函数。
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerBootstrapMixin {

    /**
     * 构造器 HEAD：**必须在这里**，因为 bench 命令走
     * {@code CommandRegistrationCallback}，而命令分发器是在 {@code MinecraftServer.<init>} 里建好的。
     * 实测：只在 {@code runServer} HEAD 注册时，真实服务端上 {@code /cava pathfind bench} 报
     * {@code Unknown or incomplete command}（注册晚了一步）。
     */
    @Inject(method = "<init>", at = @At("HEAD"), require = 0)
    private static void cava$bootstrapEarly(CallbackInfo ci) {
        // **必须是 static**：实测 Mixin 拒绝"在 super() 之前的构造器注入用非 static handler"
        // （InvalidInjectionException: @At("HEAD") selector @Inject handler before super()
        //  invocation must be static），而且那次失败的代价是整个服务端起不来。
        PathfindBootstrap.ensureInstalled();
    }

    /** {@code protected void runServer()}：兜底（幂等）。 */
    @Inject(method = "runServer", at = @At("HEAD"), require = 0)
    private void cava$bootstrap(CallbackInfo ci) {
        PathfindBootstrap.ensureInstalled();
    }
}
