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

    /**
     * bench 的**可校验回执**机制（captain 2026-09-22 要求）。
     *
     * <p>动机（本机实测的事故）：我用固定 sleep 后杀服务端的脚本驱动 bench，
     * 20000 次 × 430 µs ≈ 8.6 s 的采样在 5 s 时就被杀了 ⇒ 日志里没有 BENCH 行、
     * {@code nativeCalls} 也没涨，看起来像"命令没执行"。**如果这种无效采样混进平均值，
     * 任何性能结论都是噪声。** 所以：
     * <ul>
     *   <li>每次 bench 有单调递增的 {@code id}；</li>
     *   <li>**无论成功失败都打印一行** {@code BENCH id=... ok=...}（失败也有行 ⇒ 缺行 = 命令真的没跑）；</li>
     *   <li>{@code /cava pathfind stats} 报 {@code benchRuns=}，驱动方可据此判断命令有没有被派发。</li>
     * </ul>
     */
    private static final java.util.concurrent.atomic.AtomicLong BENCH_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong BENCH_DONE =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean BENCH_RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private PathfindBench() {
    }

    /** 已完成的 bench 次数（回执校验用）。 */
    public static long benchRuns() {
        return BENCH_DONE.get();
    }

    /** 已派发的 bench 序号（诊断用）。 */
    public static long benchSeq() {
        return BENCH_SEQ.get();
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
            // P1-PERF：大搜索空间的 perf bench 与 site 命令（同一个注册时机，避免时序问题）
            PathfindPerfBench.ensureRegistered(server);
            LOG.info("[cava/pathfind] /cava pathfind {{stats|probe|bench <n>|perf <preset> <n> [reuse|repush]|site <preset>}} 已注册");
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
        String line = "[cava/pathfind] benchRuns=" + BENCH_DONE.get() + " benchSeq=" + BENCH_SEQ.get()
                + " | " + PathfindHook.INSTANCE.stats() + " | " + PathfindSwitches.describe()
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
        long id = BENCH_SEQ.incrementAndGet();
        if (!BENCH_RUNNING.compareAndSet(false, true)) {
            // 并发 bench 会互相污染（同一只生物、同一份统计）⇒ 明确拒绝，不打无效样本
            String busy = "[cava/pathfind] BENCH id=" + id + " ok=false reason=busy";
            LOG.warn(busy);
            source.sendError(Text.literal(busy));
            return 0;
        }
        MinecraftServer server = source.getServer();
        ServerWorld world = server.getOverworld();
        Object[] found = PathfindScenario.findOrCreateMob(world);
        MobEntity mob = (MobEntity) found[0];
        boolean created = Boolean.TRUE.equals(found[1]);
        if (mob == null) {
            String bad = "[cava/pathfind] BENCH id=" + id + " ok=false reason=no-mob n=" + count;
            LOG.warn(bad);
            source.sendError(Text.literal(bad));
            BENCH_DONE.incrementAndGet();
            BENCH_RUNNING.set(false);
            return 0;
        }
        try {
            for (int i = 0; i < WARMUP; i++) {
                PathfindScenario.invoke(world, mob);
            }
            long canaryBefore = PathfindHook.INSTANCE.canaryCount();
            long takeoversBefore = PathfindHook.INSTANCE.takeovers();
            long nativeBefore = PathfindHook.INSTANCE.nativeCalls();
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
            long nativeDelta = PathfindHook.INSTANCE.nativeCalls() - nativeBefore;
            double nsPerOp = (double) dt / (double) count;
            // ok 的判据：**每一次调用都真的穿过了注入点**（canaryDelta == n）。
            // 这是回执校验的核心：驱动方只要读到 ok=true 且 id 对得上，这次采样就是有效的。
            boolean ok = canaryDelta == count;
            String line = String.format(java.util.Locale.ROOT,
                    "[cava/pathfind] BENCH id=%d ok=%s n=%d ns/op=%.1f totalMs=%.1f avgNodes=%.2f nullPaths=%d "
                            + "canaryDelta=%d expect=%d takeovers=%d nativeCallsDelta=%d | %s",
                    id, ok, count, nsPerOp, dt / 1.0e6, (double) nodes / (double) count, nulls,
                    canaryDelta, count, takeovers, nativeDelta, PathfindSwitches.describe());
            LOG.info(line);
            source.sendFeedback(() -> Text.literal(line), false);
            BENCH_DONE.incrementAndGet();
            return ok ? 1 : 0;
        } catch (Throwable t) {
            // **失败也必须留下回执行**，否则"缺行"无法区分"命令没跑"和"跑挂了"
            LOG.error("[cava/pathfind] BENCH id=" + id + " ok=false reason=exception n=" + count, t);
            BENCH_DONE.incrementAndGet();
            return 0;
        } finally {
            BENCH_RUNNING.set(false);
            if (created) {
                mob.discard();
            }
        }
    }
}
