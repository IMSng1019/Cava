package cava.hook;

import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 注入流的自举：注册金丝雀探测与 bench 命令所需的监听器。
 *
 * <p><b>为什么从注入点里自举</b>：{@code cava/Cava.java} 是 captain 的文件、{@code fabric.mod.json}
 * 是构建流的文件，注入流都不能改。而"启动后主动触发一次目标方法"又必须有人在生命周期上挂钩子。
 * 所以这里用**首个注入点命中**作为自举时机：一旦 {@code findPathToAny} 被调用过，
 * 说明 mixin 已经生效、Fabric API 也已就绪，此时注册 tick 监听器就能在下一个 tick 完成探测。
 *
 * <p>这带来一个**已知的顺序约束**：如果服务端从头到尾**没有任何一次寻路**，探测永远不会启动 ——
 * 但那种情况下"注入是否生效"本身也无从验证。真实服务器（哪怕是空跑）在生成区块/生物时就会寻路。
 */
public final class PathfindBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private PathfindBootstrap() {
    }

    /** 幂等；**任何异常都不逃逸**（注入点绝不能因为引导失败而把服务端带崩）。 */
    public static void ensureInstalled() {
        if (INSTALLED.get()) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            ServerLifecycleEvents.SERVER_STARTED.register(PathfindProbe::onServerStarted);
            ServerTickEvents.END_SERVER_TICK.register(PathfindProbe::onEndTick);
            LOG.info("[cava/pathfind] 注入体已自举（{}）；{}；{}", PathfindSwitches.describe(),
                    PathfindMirrorBridge.describe(), AmphibiousPathNodeMakerAccess.describe());
        } catch (Throwable t) {
            LOG.error("[cava/pathfind] 自举失败（金丝雀与 bench 不可用，但注入点本身仍会回退原逻辑）", t);
        }
        try {
            PathfindBench.register();
        } catch (Throwable t) {
            LOG.error("[cava/pathfind] /cava 命令注册失败（不影响寻路）", t);
        }
    }

    /** 测试/诊断用。 */
    public static boolean installed() {
        return INSTALLED.get();
    }
}
