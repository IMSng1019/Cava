package cava.hook;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * **不依赖 profiler 的性能测量**（任务清单第 5 条）。
 *
 * <p>背景：本机 async-profiler 不可用、spark 采不到内层帧，"寻路占比 0.0%" 这个数字不可信。
 * 所以这里给出一个**端到端 wall-clock** 的测法：在真实世界 + 真实生物上跑 N 次真实的
 * {@code PathNodeNavigator.findPathToAny}，直接量总耗时与每次调用的纳秒数。
 *
 * <p>用法（同一条命令，两份日志，两份命令行的差别**只有**一个系统属性）：
 * <pre>
 *   /cava pathfind bench 20000
 * </pre>
 *
 * <p>对比方式（**必须在同一个服务器目录、同一个存档、同一个 mod 集合上跑**）：
 * <pre>
 *   -Dcava.pathfind.hook=false    纯原版 + 一次 mixin 分发
 *   -Dcava.pathfind.native=false  注入体 + 金丝雀 + 总是回退（当前默认）
 *   -Dcava.pathfind.native=true   尝试原生接管（当前会被 CAVA_ERR_UNIMPLEMENTED 挡住）
 * </pre>
 *
 * <p>输出同时打印**金丝雀计数增量**：它必须等于迭代次数，否则说明 mixin 有调用没被命中
 * （这本身就是一条比"耗时"更硬的正确性证据）。
 */
public final class PathfindBench {

    /** 默认迭代次数。 */
    public static final int DEFAULT_ITERATIONS = 2000;
    /** 上限（防止一条命令把服务端卡住太久）。 */
    public static final int MAX_ITERATIONS = 200_000;
    /** 预热次数（让 JIT 把原版与钩子的热路径编出来）。 */
    public static final int WARMUP = 200;

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private PathfindBench() {
    }

    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 注册 {@code /cava pathfind ...}（幂等）。
     *
     * <p><b>为什么直接往 dispatcher 里注册，而不是只用 {@code CommandRegistrationCallback}</b>（实测教训）：
     * 第一版只挂 {@code CommandRegistrationCallback}，在 gate-preview 真实服务端上
     * {@code /cava pathfind bench} 报 {@code Unknown or incomplete command} —— 事件的触发时机
     * （fabric 在 {@code CommandManager} 构造里 fire）与实际注册时机对不上。
     * 直接拿 {@code server.getCommandManager().getDispatcher()} 注册是**确定性的**：
     * 只要服务端起来了，dispatcher 就存在。
     */
    public static void ensureRegistered(MinecraftServer server) {
        if (server == null || REGISTERED.get()) {
            return;
        }
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            var dispatcher = server.getCommandManager().getDispatcher();
            dispatcher.register(CommandManager.literal("cava")
                    .then(CommandManager.literal("pathfind")
                            .then(CommandManager.literal("stats")
                                    .executes(ctx -> stats(ctx.getSource())))
                            .then(CommandManager.literal("probe")
                                    .executes(ctx -> probe(ctx.getSource())))
                            .then(CommandManager.literal("bench")
                                    .executes(ctx -> bench(ctx.getSource(), DEFAULT_ITERATIONS))
                                    .then(CommandManager.argument("count",
                                                    IntegerArgumentType.integer(1, MAX_ITERATIONS))
                                            .executes(ctx -> bench(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "count")))))));
            LOG.info("[cava/pathfind] /cava pathfind {{stats|probe|bench <n>}} 已注册");
        } catch (Throwable t) {
            REGISTERED.set(false);
            LOG.error("[cava/pathfind] 命令注册失败（不影响寻路）", t);
        }
    }

    /** 兼容入口：早期版本从 {@code CommandRegistrationCallback} 调用。 */
    public static void register() {
        // 空实现：注册改由 ensureRegistered(server) 在服务端就绪后确定性完成
    }

    private static int stats(ServerCommandSource source) {
        String line = "[cava/pathfind] " + PathfindHook.INSTANCE.stats() + " | " + PathfindSwitches.describe()
                + " | " + PathfindMirrorBridge.describe();
        LOG.info(line);
        source.sendFeedback(() -> Text.literal(line), false);
        return 1;
    }

    private static int probe(ServerCommandSource source) {
        boolean ok = PathfindProbe.runOnce(source.getServer());
        String line = "[cava/pathfind] 手动金丝雀探测： " + (ok ? "PASS" : "FAIL") + " | "
                + PathfindHook.INSTANCE.stats();
        LOG.info(line);
        source.sendFeedback(() -> Text.literal(line), false);
        return ok ? 1 : 0;
    }

    private static int bench(ServerCommandSource source, int count) {
        MinecraftServer server = source.getServer();
        ServerWorld world = server.getOverworld();
        Object[] found = PathfindScenario.findOrCreateMob(world);
        MobEntity mob = (MobEntity) found[0];
        boolean created = Boolean.TRUE.equals(found[1]);
        if (mob == null) {
            source.sendError(Text.literal("[cava/pathfind] bench：找不到也无法创建可用的生物"));
            return 0;
        }
        try {
            for (int i = 0; i < WARMUP; i++) {
                PathfindScenario.invoke(world, mob);
            }
            long canaryBefore = PathfindHook.INSTANCE.canaryCount();
            long takeoversBefore = PathfindHook.INSTANCE.takeovers();
            long t0 = System.nanoTime();
            long nodes = 0;
            int nulls = 0;
            for (int i = 0; i < count; i++) {
                Path p = PathfindScenario.invoke(world, mob);
                if (p == null) {
                    nulls++;
                } else {
                    nodes += p.getLength();
                }
            }
            long dt = System.nanoTime() - t0;
            long canaryDelta = PathfindHook.INSTANCE.canaryCount() - canaryBefore;
            long takeovers = PathfindHook.INSTANCE.takeovers() - takeoversBefore;
            double nsPerOp = (double) dt / (double) count;
            String line = String.format(java.util.Locale.ROOT,
                    "[cava/pathfind] BENCH n=%d ns/op=%.1f totalMs=%.1f avgNodes=%.2f nullPaths=%d "
                            + "canaryDelta=%d(expect %d) takeovers=%d | %s | %s",
                    count, nsPerOp, dt / 1.0e6, (double) nodes / (double) count, nulls,
                    canaryDelta, count, takeovers, PathfindSwitches.describe(), PathfindHook.INSTANCE.stats());
            LOG.info(line);
            source.sendFeedback(() -> Text.literal(line), false);
            return canaryDelta == count ? 1 : 0;
        } finally {
            if (created) {
                mob.discard();
            }
        }
    }
}
