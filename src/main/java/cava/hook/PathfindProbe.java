package cava.hook;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 金丝雀的**主动探测**（docs/CAVA-gates.md 门禁 #6 留下的未闭合项："金丝雀 0/3，从未在真实环境里被验证过一次"）。
 *
 * <p>做法：服务端跑起来之后，在**真实世界 + 真实生物**上发起一次真实的
 * {@code PathNodeNavigator.findPathToAny}，比较调用前后的 {@link PathfindHook#canaryCount()}：
 * <ul>
 *   <li>计数 +1 ⇒ 注入真的生效了（打 INFO）；</li>
 *   <li>计数没动 ⇒ **禁用 pathfind 子系统 + ERROR 日志**（契约 §3：失败回退不是崩溃）。</li>
 * </ul>
 *
 * <p>为什么用真实触发而不是 {@code hook.hit()}：后者只能证明计数器会加，
 * **不能证明 mixin 注入生效**。静默失效（服务器正常启动、TPS 正常、原生一次没被调用）
 * 正是这条门禁要抓的失效模式。
 */
public final class PathfindProbe {

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private static final AtomicBoolean DONE = new AtomicBoolean();
    private static final AtomicInteger TICKS = new AtomicInteger();

    private PathfindProbe() {
    }

    /** SERVER_STARTED：先报状态，再立刻探测。 */
    public static void onServerStarted(MinecraftServer server) {
        PathfindBench.ensureRegistered(server);
        PathfindHook.INSTANCE.logStartupState();
        run(server);
    }

    /** 每个 tick 兜底：万一 SERVER_STARTED 时世界/生物还没就绪，就等下一 tick。 */
    public static void onEndTick(MinecraftServer server) {
        PathfindBench.ensureRegistered(server);
        if (DONE.get()) {
            return;
        }
        int t = TICKS.incrementAndGet();
        int limit = Math.max(1, PathfindSwitches.probeTicks());
        if (t > limit) {
            if (DONE.compareAndSet(false, true)) {
                fail("等待 " + limit + " 个 tick 仍未拿到可探测的生物/世界");
            }
            return;
        }
        run(server);
    }

    /** 供命令手动再触发一次（不影响一次性语义）。 */
    public static boolean runOnce(MinecraftServer server) {
        DONE.set(false);
        return run(server);
    }

    public static boolean done() {
        return DONE.get();
    }

    /** @return true = 计数确实动了。 */
    static boolean run(MinecraftServer server) {
        if (!PathfindSwitches.probeEnabled()) {
            if (DONE.compareAndSet(false, true)) {
                LOG.info("[cava/pathfind] 金丝雀探测关闭（-D{}=false）", PathfindSwitches.PROP_PROBE);
            }
            return false;
        }
        if (server == null) {
            return false;
        }
        ServerWorld world = server.getOverworld();
        if (world == null) {
            return false;
        }
        Object[] found = PathfindScenario.findOrCreateMob(world);
        MobEntity mob = (MobEntity) found[0];
        boolean created = Boolean.TRUE.equals(found[1]);
        if (mob == null) {
            return false;   // 世界还没就绪：让下一个 tick 再试
        }
        if (!DONE.compareAndSet(false, true)) {
            if (created) {
                mob.discard();
            }
            return false;
        }
        try {
            long before = PathfindHook.INSTANCE.canaryCount();
            Path path = PathfindScenario.invoke(world, mob);
            long after = PathfindHook.INSTANCE.canaryCount();
            if (after > before) {
                LOG.info("[cava/pathfind] 金丝雀 PASS：主动触发 findPathToAny 一次，计数 {} -> {}（原版返回 {}）；{}",
                        before, after, path == null ? "null" : ("Path(" + path.getLength() + " 节点)"),
                        PathfindHook.INSTANCE.stats());
                return true;
            }
            fail("主动触发 findPathToAny 一次后计数未变（" + before + " -> " + after + "）");
            return false;
        } catch (Throwable t) {
            fail("金丝雀探测抛出异常: " + t);
            LOG.error("[cava/pathfind] 金丝雀探测异常", t);
            return false;
        } finally {
            if (created) {
                mob.discard();
            }
        }
    }

    private static void fail(String reason) {
        PathfindHook.INSTANCE.disable("金丝雀探测失败：" + reason);
        LOG.error("[cava/pathfind] 金丝雀 FAIL：{} ⇒ **禁用 pathfind 子系统**并回退纯 Java（不是崩溃）", reason);
    }
}
