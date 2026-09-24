package cava.hook;

import cava.mirror.RegionMirror;
import cava.mirror.RegionSource;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1-PERF：**大搜索空间**下 native on / off 的逐次调用性能与可观测一致性（prompts/04 被推迟的那一项）。
 *
 * <p>与 {@link PathfindBench} 的区别（为什么不是改它）：
 * <ul>
 *   <li>场景是 {@link PathfindPerfScenario} 的**大搜索空间预设**（长路径 / 绕行 / 41x41 / 63x63 迷宫），
 *       不再是那个只展开 5 个节点的小场景；</li>
 *   <li>报的是**分布**（avg/p50/p95/p99/max）而不是一个平均值；</li>
 *   <li>同时采**可观测结果指纹**（节点数 / 终点 / 坐标序列哈希 / type 序列哈希 / 路径是否穿墙），
 *       让 "两条腿一致" 变成可判定、可证伪的结论；</li>
 *   <li>把 "搜索真的展开了" 做成硬门禁（{@link PathfindPerfScenario.Preset#minNodes()}）——
 *       没展开就是 ok=false，不给"看起来测过了"的假绿。</li>
 * </ul>
 *
 * <p>用法（RCON）
 * <pre>
 *   cava pathfind perf &lt;preset&gt; &lt;count&gt; [reuse|repush]
 *   cava pathfind site &lt;preset&gt;        # 只铺场景 + 自检并打指纹
 * </pre>
 *
 * <p>{@code repush} = 每次调用前把镜像区域的"同矩形复用"判定作废
 * （{@code RegionMirror.onBlockChanged}），于是每次都是**冷窗口真推送**。真实服务器上
 * 相邻两次寻路的窗口通常不同 ⇒ 复用不命中，所以 {@code repush} 才是"每只生物一次"的现实上界，
 * 而 {@code reuse} 是"同一片地形连续求解"的下界。两个都报，不挑好看的。
 */
public final class PathfindPerfBench {

    /** 单次命令的迭代上限（防止一条命令把服务端卡死）。 */
    public static final int MAX_ITERATIONS = 200_000;
    /** 预热次数。 */
    public static final int WARMUP = 300;
    /** 计时循环之后再做多少次"全节点指纹"分析（不计时）。 */
    public static final int ANALYZE = 8;

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");
    private static final AtomicLong SEQ = new AtomicLong();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private PathfindPerfBench() {
    }

    // ------------------------------------------------------------------
    // 命令注册
    // ------------------------------------------------------------------

    /** 由 {@link PathfindBench#ensureRegistered(MinecraftServer)} 调用（同一次注册时机，幂等）。 */
    public static void ensureRegistered(MinecraftServer server) {
        if (server == null || !REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            var dispatcher = server.getCommandManager().getDispatcher();
            LiteralArgumentBuilder<ServerCommandSource> perf = CommandManager.literal("perf")
                    .then(CommandManager.argument("preset", StringArgumentType.word())
                            .suggests((ctx, b) -> {
                                for (PathfindPerfScenario.Preset p : PathfindPerfScenario.presets()) {
                                    b.suggest(p.name());
                                }
                                return b.buildFuture();
                            })
                            .then(CommandManager.argument("count", IntegerArgumentType.integer(1, MAX_ITERATIONS))
                                    .executes(ctx -> run(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "preset"),
                                            IntegerArgumentType.getInteger(ctx, "count"), "reuse"))
                                    .then(CommandManager.argument("mode", StringArgumentType.word())
                                            .executes(ctx -> run(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "preset"),
                                                    IntegerArgumentType.getInteger(ctx, "count"),
                                                    StringArgumentType.getString(ctx, "mode"))))));
            LiteralArgumentBuilder<ServerCommandSource> site = CommandManager.literal("site")
                    .then(CommandManager.argument("preset", StringArgumentType.word())
                            .executes(ctx -> siteCommand(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "preset"))));
            LiteralArgumentBuilder<ServerCommandSource> diag = CommandManager.literal("diag")
                    .then(CommandManager.argument("preset", StringArgumentType.word())
                            .executes(ctx -> diag(ctx.getSource(), StringArgumentType.getString(ctx, "preset"))));
            // 预算扫描：直接量"目标最早在第几个节点预算下被找到" = A* 的展开规模（比 avgNodes 可靠）
            LiteralArgumentBuilder<ServerCommandSource> explore = CommandManager.literal("explore")
                    .then(CommandManager.argument("preset", StringArgumentType.word())
                            .then(CommandManager.argument("ranges", StringArgumentType.word())
                                    .executes(ctx -> explore(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "preset"),
                                            StringArgumentType.getString(ctx, "ranges")))));
            dispatcher.register(CommandManager.literal("cava")
                    .then(CommandManager.literal("pathfind").then(perf).then(site).then(explore).then(diag)));
            LOG.info("[cava/pathfind] /cava pathfind {{perf <preset> <count> [reuse|repush]|site <preset>}} 已注册（presets={}）",
                    PathfindPerfScenario.names());
        } catch (Throwable t) {
            REGISTERED.set(false);
            LOG.error("[cava/pathfind] perf 命令注册失败（不影响寻路）", t);
        }
    }

    private static int siteCommand(ServerCommandSource source, String name) {
        PathfindPerfScenario.Preset p = PathfindPerfScenario.byName(name);
        if (p == null) {
            source.sendError(Text.literal("未知 preset：" + name + "（可选 " + PathfindPerfScenario.names() + "）"));
            return 0;
        }
        ServerWorld world = source.getServer().getOverworld();
        PathfindPerfScenario.Built built = PathfindPerfScenario.build(world, p);
        String bad = PathfindPerfScenario.verifySiteFull(world, p);
        PathfindPerfScenario.Built again = PathfindPerfScenario.build(world, p);
        String bad2 = PathfindPerfScenario.verifySiteFull(world, p);
        String line = "[cava/pathfind] SITE " + p.describe() + " cells=" + built.cells()
                + " changed=" + built.changed() + " changedAgain=" + again.changed()
                + " siteHash=0x" + Long.toHexString(again.siteHash())
                + " verifyFull=" + (bad == null ? "PASS" : "FAIL(" + bad + ")")
                + " verifyAfterRebuild=" + (bad2 == null ? "PASS" : "FAIL(" + bad2 + ")");
        LOG.info(line);
        source.sendFeedback(() -> Text.literal(line), false);
        return bad == null ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // 测量
    // ------------------------------------------------------------------

    private static int run(ServerCommandSource source, String name, int count, String mode) {
        long id = SEQ.incrementAndGet();
        if (!RUNNING.compareAndSet(false, true)) {
            LOG.warn("[cava/pathfind] PERF id={} ok=false reason=busy", id);
            source.sendError(Text.literal("另一个 perf bench 正在跑"));
            return 0;
        }
        try {
            return measure(source, id, name, count, mode);
        } catch (Throwable t) {
            LOG.error("[cava/pathfind] PERF id=" + id + " ok=false reason=exception preset=" + name, t);
            return 0;
        } finally {
            RUNNING.set(false);
        }
    }

    private static int measure(ServerCommandSource source, long id, String name, int count, String mode) {
        PathfindPerfScenario.Preset p = PathfindPerfScenario.byName(name);
        if (p == null) {
            fail(source, id, name, mode, count, "unknown-preset");
            return 0;
        }
        boolean repush = "repush".equalsIgnoreCase(mode);
        if (!repush && !"reuse".equalsIgnoreCase(mode)) {
            fail(source, id, name, mode, count, "unknown-mode(expect reuse|repush)");
            return 0;
        }
        MinecraftServer server = source.getServer();
        ServerWorld world = server.getOverworld();
        PathfindPerfScenario.Built built = PathfindPerfScenario.build(world, p);
        BlockPos target = PathfindPerfScenario.target(p);
        String siteBad = PathfindPerfScenario.verifySiteFull(world, p);
        MobEntity mob = PathfindPerfScenario.realizeMob(world, p);
        if (mob == null) {
            fail(source, id, name, mode, count, "no-mob");
            return 0;
        }
        boolean mobAtStart = mob.getBlockPos().equals(p.start());
        PathNodeNavigator navigator = PathfindPerfScenario.newNavigator(p);
        RegionSource src = PathfindMirrorBridge.get(world);
        RegionMirror mirror = (src instanceof RegionMirror rm) ? rm : null;
        long mirrorPushes0 = mirror == null ? -1 : mirror.pushes();
        long mirrorReuse0 = mirror == null ? -1 : mirror.reuseSkips();
        long mirrorFail0 = mirror == null ? -1 : mirror.failures();
        long mirrorCells0 = mirror == null ? -1 : mirror.cellsCopied();

        float followRange = p.followRange();
        float maxRange = p.maxRange();
        int reachRange = p.reachRange();
        int navRange = p.navRange();
        BlockPos startPos = mob.getBlockPos();

        // 场景有效性 + 环境指纹（诊断；见 PathfindPerfScenario.cacheBlind 的说明）
        ChunkCache probeCache = PathfindPerfScenario.newCache(world, startPos, followRange);
        String env = PathfindPerfScenario.envProbe(world, probeCache, p, startPos, target);
        String startProbe = PathfindPerfScenario.startNodeProbe(world, mob, p);
        String blind = PathfindPerfScenario.cacheBlind(world, probeCache, p, startPos, target);
        // **几何陷阱门禁**（本流实测过）：原版 PathNode.hash 只把 z 的低 8 位放进高位，且 x 的第 15 位与
        // "z<0" 标志位重叠 ⇒ 某些 (起点,终点) 会**同键**：目标查缓存时拿到的是起点节点，
        // 搜索在第 1 个节点就判定"已抵达"、返回 1 个节点的路径。这种场景两条腿一致但什么都没测到。
        String collide = null;
        int hashStart = net.minecraft.entity.ai.pathing.PathNode.hash(startPos.getX(), startPos.getY(), startPos.getZ());
        int hashTarget = net.minecraft.entity.ai.pathing.PathNode.hash(target.getX(), target.getY(), target.getZ());
        if (hashStart == hashTarget) {
            collide = "PathNode.hash 同键(0x" + Integer.toHexString(hashStart) + ")：start=(" + startPos.toShortString()
                    + ") target=(" + target.toShortString() + ") ⇒ 目标查缓存会拿到起点节点";
        }

        long canary0 = PathfindHook.INSTANCE.canaryCount();
        long takeovers0 = PathfindHook.INSTANCE.takeovers();
        long nativeCalls0 = PathfindHook.INSTANCE.nativeCalls();
        long uploadNanos0 = PathfindHook.INSTANCE.uploadNanos();
        long solveNanos0 = PathfindHook.INSTANCE.solveNanos();

        // 预热（不计时）
        for (int i = 0; i < WARMUP; i++) {
            if (repush && mirror != null) {
                mirror.onBlockChanged(0, 0, 0);
            }
            ChunkCache cache = PathfindPerfScenario.newCache(world, startPos, followRange);
            navigator.findPathToAny(cache, mob, Set.of(target), maxRange, reachRange, followRange);
        }

        long[] nanos = new long[count];
        int[] lens = new int[count];
        int[] ends = new int[count * 3];
        float[] manh = new float[count];
        boolean[] reached = new boolean[count];
        long[] setupNanos = new long[count];
        boolean[] isNull = new boolean[count];
        int nulls = 0;
        for (int i = 0; i < count; i++) {
            if (repush && mirror != null) {
                mirror.onBlockChanged(0, 0, 0);
            }
            long t0 = System.nanoTime();
            ChunkCache cache = PathfindPerfScenario.newCache(world, startPos, followRange);
            long t1 = System.nanoTime();
            Path path = navigator.findPathToAny(cache, mob, Set.of(target), maxRange, reachRange, followRange);
            long t2 = System.nanoTime();
            setupNanos[i] = t1 - t0;
            nanos[i] = t2 - t0;
            if (path == null) {
                isNull[i] = true;
                nulls++;
                lens[i] = -1;
                continue;
            }
            lens[i] = path.getLength();
            PathNode end = path.getEnd();
            if (end != null) {
                ends[i * 3] = end.x;
                ends[i * 3 + 1] = end.y;
                ends[i * 3 + 2] = end.z;
            }
            manh[i] = path.getManhattanDistanceFromTarget();
            reached[i] = path.reachesTarget();
        }
        long canaryDelta = PathfindHook.INSTANCE.canaryCount() - canary0;
        long takeovers = PathfindHook.INSTANCE.takeovers() - takeovers0;
        long nativeDelta = PathfindHook.INSTANCE.nativeCalls() - nativeCalls0;
        long uploadNanos = PathfindHook.INSTANCE.uploadNanos() - uploadNanos0;
        long solveNanos = PathfindHook.INSTANCE.solveNanos() - solveNanos0;

        // ---- 全节点指纹（不计时，单独几次调用）----
        Set<Long> sigs = new HashSet<>();
        long sigCoords = 0;
        long sigTypes = 0;
        int sigDistinct = 0;
        int solidNodes = 0;
        int collisionNodes = 0;
        String firstBadNode = "(无)";
        int analyzeNulls = 0;
        int analyzeLen = -1;
        String startNode = "(?)";
        String endNode = "(?)";
        for (int k = 0; k < ANALYZE; k++) {
            Path path = PathfindPerfScenario.invoke(world, mob, p, target);
            if (path == null) {
                analyzeNulls++;
                continue;
            }
            int len = path.getLength();
            long hc = 0xcbf29ce484222325L;
            long ht = 0xcbf29ce484222325L;
            for (int i = 0; i < len; i++) {
                PathNode n = path.getNode(i);
                hc = fnv(hc, n.x);
                hc = fnv(hc, n.y);
                hc = fnv(hc, n.z);
                ht = fnv(ht, n.type == null ? -1 : n.type.ordinal());
                BlockPos np = n.getBlockPos();
                boolean collides = !world.getBlockState(np).getCollisionShape(world, np).isEmpty();
                if (collides) {
                    collisionNodes++;
                    if ("(无)".equals(firstBadNode)) {
                        firstBadNode = "(" + n.x + "," + n.y + "," + n.z + ")";
                    }
                }
            }
            sigs.add(hc);
            if (k == 0) {
                sigCoords = hc;
                sigTypes = ht;
                analyzeLen = len;
                PathNode first = path.getNode(0);
                PathNode last = path.getEnd();
                startNode = first == null ? "(空)" : "(" + first.x + "," + first.y + "," + first.z + ")";
                endNode = last == null ? "(空)" : "(" + last.x + "," + last.y + "," + last.z + ")";
            }
        }
        sigDistinct = sigs.size();

        // ---- 统计 ----
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        long[] lensSorted = new long[count - nulls];
        int li = 0;
        for (int i = 0; i < count; i++) {
            if (!isNull[i]) {
                lensSorted[li++] = lens[i];
            }
        }
        Arrays.sort(lensSorted);
        long[] setupSorted = setupNanos.clone();
        Arrays.sort(setupSorted);
        long nsTotal = 0;
        for (long v : nanos) {
            nsTotal += v;
        }
        double nodesAvg = lensSorted.length == 0 ? 0.0 : Arrays.stream(lensSorted).average().orElse(0.0);
        Set<Long> endSet = new HashSet<>();
        for (int i = 0; i < count; i++) {
            if (!isNull[i]) {
                endSet.add((((long) (ends[i * 3] & 0x1FFFFF)) << 42)
                        | (((long) (ends[i * 3 + 1] & 0x1FFFFF)) << 21)
                        | (ends[i * 3 + 2] & 0x1FFFFF));
            }
        }
        int endDistinct = endSet.size();
        String lastEnd = "(none)";
        float lastManh = Float.NaN;
        boolean lastReached = false;
        for (int i = count - 1; i >= 0; i--) {
            if (!isNull[i]) {
                lastEnd = "(" + ends[i * 3] + "," + ends[i * 3 + 1] + "," + ends[i * 3 + 2] + ")";
                lastManh = manh[i];
                lastReached = reached[i];
                break;
            }
        }

        // ---- 回执 ----
        String reason = null;
        if (canaryDelta != count + WARMUP) {
            reason = "canary-mismatch(" + canaryDelta + "!=" + (count + WARMUP) + ")";
        } else if (!mobAtStart) {
            reason = "mob-not-at-start(now=" + mob.getBlockPos().toShortString() + " want=" + p.start().toShortString() + ")";
        } else if (siteBad != null) {
            reason = "site-invalid(" + siteBad + ")";
        } else if (collide != null) {
            reason = "target-node-collision(" + collide + ")";
        } else if (blind != null) {
            reason = "cache-blind(" + blind + ")";
        } else if (nodesAvg < p.minNodes()) {
            reason = "search-too-small(avg=" + String.format(Locale.ROOT, "%.1f", nodesAvg)
                    + " < minNodes=" + p.minNodes() + ")";
        } else if (analyzeNulls > 0) {
            reason = "analyze-null-paths(" + analyzeNulls + ")";
        }
        boolean ok = reason == null;
        String head = String.format(Locale.ROOT,
                "[cava/pathfind] PERF id=%d preset=%s mode=%s run=1 n=%d warmup=%d ok=%s%s "
                        + "ns_avg=%.1f ns_p50=%d ns_p95=%d ns_p99=%d ns_max=%d ns_min=%d setup_avg=%.1f totalMs=%.1f "
                        + "nodes_avg=%.2f nodes_p50=%d nodes_min=%d nodes_max=%d nullPaths=%d endDistinct=%d "
                        + "end=%s manh=%s reachedTargetFlag=%s",
                id, p.name(), mode, count, WARMUP, ok, reason == null ? "" : " reason=" + reason,
                nsTotal / (double) count, pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99),
                sorted.length == 0 ? 0 : sorted[sorted.length - 1], sorted.length == 0 ? 0 : sorted[0],
                Arrays.stream(setupNanos).average().orElse(0.0), nsTotal / 1.0e6,
                nodesAvg, pct(lensSorted, 0.50),
                lensSorted.length == 0 ? 0 : lensSorted[0],
                lensSorted.length == 0 ? 0 : lensSorted[lensSorted.length - 1],
                nulls, endDistinct, lastEnd,
                Float.isNaN(lastManh) ? "nan" : String.format(Locale.ROOT, "%.3f", lastManh),
                lastReached);
        String detail = String.format(Locale.ROOT,
                "[cava/pathfind] PERFDETAIL id=%d preset=%s mode=%s targetOffset=%d "
                        + "sig_coords=0x%016x sig_types=0x%016x sigDistinct=%d analyzeNulls=%d analyzeLen=%d "
                        + "startNode=%s endNode=%s solidNodes=0 collisionNodes=%d firstBadNode=%s "
                        + "canaryDelta=%d expectDelta=%d takeovers=%d nativeCallsDelta=%d upload_avg_ns=%.1f solve_avg_ns=%.1f "
                        + "mirror=pushes:%d,reuse:%d,fail:%d,cells:%d "
                        + "site:cells=%d,changed=%d,hash=0x%016x,verify=%s preset:%s "
                        + "params=navRange:%d,followRange:%.1f,maxRange:%.1f,reachRange:%d,budget:%d,minNodes:%d,horizDist:%.1f "
                        + "env=%s startProbe=%s",
                id, p.name(), mode, PathfindPerfScenario.targetOffset(),
                sigCoords, sigTypes, sigDistinct, analyzeNulls, analyzeLen, startNode, endNode,
                collisionNodes, firstBadNode, canaryDelta, count + WARMUP, takeovers, nativeDelta,
                nativeDelta == 0 ? 0.0 : uploadNanos / (double) nativeDelta,
                nativeDelta == 0 ? 0.0 : solveNanos / (double) nativeDelta,
                mirror == null ? -1 : mirror.pushes() - mirrorPushes0,
                mirror == null ? -1 : mirror.reuseSkips() - mirrorReuse0,
                mirror == null ? -1 : mirror.failures() - mirrorFail0,
                mirror == null ? -1 : mirror.cellsCopied() - mirrorCells0,
                built.cells(), built.changed(), built.siteHash(), siteBad == null ? "PASS" : "FAIL",
                p.name(), navRange, followRange, maxRange, reachRange, p.budget(), p.minNodes(),
                p.horizontalDistance(), env, startProbe);
        LOG.info(head);
        LOG.info(detail);
        source.sendFeedback(() -> Text.literal(head), false);
        source.sendFeedback(() -> Text.literal(detail), false);
        mob.discard();
        return ok ? 1 : 0;
    }

    /**
     * **预算扫描**：{@code cava pathfind explore <preset> <range1,range2,...>}。
     *
     * <p>为什么要它：{@code avgNodes} 是**返回路径的长度**，不是 A* 的展开规模。
     * 原版把展开计数关在方法体里，外部读不到。但节点预算 {@code (int)(range * followRange)}
     * 是可外部设置的：**"目标最早在多大的预算下被找到"就是 A* 展开规模的一个硬下界**
     * （预算耗尽前必须把目标弹出来）。这比"路径多长"更接近任务要的 nodes explored。
     */
    private static int explore(ServerCommandSource source, String name, String ranges) {
        PathfindPerfScenario.Preset p = PathfindPerfScenario.byName(name);
        if (p == null) {
            source.sendError(Text.literal("未知 preset：" + name));
            return 0;
        }
        ServerWorld world = source.getServer().getOverworld();
        PathfindPerfScenario.build(world, p);
        BlockPos target = PathfindPerfScenario.target(p);
        MobEntity mob = PathfindPerfScenario.realizeMob(world, p);
        if (mob == null) {
            source.sendError(Text.literal("explore: 造不出生物"));
            return 0;
        }
        BlockPos startPos = mob.getBlockPos();
        int reps = 5;
        try {
            for (String raw : ranges.split(",")) {
                int range;
                try {
                    range = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (range <= 0) {
                    continue;
                }
                PathNodeNavigator navigator = new PathNodeNavigator(new net.minecraft.entity.ai.pathing.LandPathNodeMaker(), range);
                int budget = (int) ((float) range * p.followRange());
                int len = -1;
                String end = "(none)";
                float manh = Float.NaN;
                boolean reached = false;
                long t0 = System.nanoTime();
                for (int i = 0; i < reps; i++) {
                    ChunkCache cache = PathfindPerfScenario.newCache(world, startPos, p.followRange());
                    Path path = navigator.findPathToAny(cache, mob, Set.of(target), p.maxRange(), p.reachRange(), p.followRange());
                    if (path == null) {
                        len = 0;
                        continue;
                    }
                    len = path.getLength();
                    manh = path.getManhattanDistanceFromTarget();
                    reached = !(manh > (float) p.reachRange());
                    PathNode last = path.getEnd();
                    end = last == null ? "(空)" : "(" + last.x + "," + last.y + "," + last.z + ")";
                }
                long ns = (System.nanoTime() - t0) / reps;
                String line = String.format(Locale.ROOT,
                        "[cava/pathfind] EXPLORE preset=%s range=%d budget=%d len=%d end=%s manh=%.3f targetReached=%s ns_avg=%d",
                        p.name(), range, budget, len, end, manh, reached, ns);
                LOG.info(line);
                source.sendFeedback(() -> Text.literal(line), false);
            }
        } finally {
            mob.discard();
        }
        return 1;
    }

    /**
     * **邻域诊断**：{@code cava pathfind diag <preset>}。
     *
     * <p>打印原版 {@code LandPathNodeMaker} 的 start 节点、8 个邻居的原始类型与惩罚、
     * 以及 {@code getSuccessors} 实际返回的邻居数 —— 用来把"搜索一个节点都没展开"钉到具体哪一步拒绝的。
     */
    private static int diag(ServerCommandSource source, String name) {
        PathfindPerfScenario.Preset p = PathfindPerfScenario.byName(name);
        if (p == null) {
            source.sendError(Text.literal("未知 preset：" + name));
            return 0;
        }
        ServerWorld world = source.getServer().getOverworld();
        PathfindPerfScenario.build(world, p);
        BlockPos target = PathfindPerfScenario.target(p);
        MobEntity mob = PathfindPerfScenario.realizeMob(world, p);
        if (mob == null) {
            source.sendError(Text.literal("diag: 造不出生物"));
            return 0;
        }
        try {
            BlockPos startPos = mob.getBlockPos();
            ChunkCache cache = PathfindPerfScenario.newCache(world, startPos, p.followRange());
            net.minecraft.entity.ai.pathing.LandPathNodeMaker maker =
                    new net.minecraft.entity.ai.pathing.LandPathNodeMaker();
            maker.init(cache, mob);
            PathNode start = maker.getStart();
            PathNode[] buf = new PathNode[64];
            int n = maker.getSuccessors(buf, start);
            StringBuilder succ = new StringBuilder();
            for (int i = 0; i < n && i < 12; i++) {
                succ.append('(').append(buf[i].x).append(',').append(buf[i].y).append(',').append(buf[i].z)
                        .append(':').append(buf[i].type).append(":g=").append(buf[i].penalizedPathLength).append(") ");
            }
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
            StringBuilder nt = new StringBuilder();
            for (int[] d : dirs) {
                net.minecraft.entity.ai.pathing.PathNodeType t = maker.getNodeType(cache,
                        start.x + d[0], start.y, start.z + d[1], mob);
                nt.append('[').append(d[0]).append(',').append(d[1]).append("]=").append(t)
                        .append(":pen=").append(mob.getPathfindingPenalty(t)).append(' ');
            }
            // **搜索复刻（诊断）**：按私有 findPathToAny 的字节码逐句复刻内层循环，把"为什么一个节点都不展开"钉死。
            // 注意：这是**诊断用的复刻**，不参与任何测量，也不改变被测路径。
            StringBuilder tr = new StringBuilder();
            PathNode[] succ2 = new PathNode[32];
            net.minecraft.entity.ai.pathing.PathMinHeap heap = new net.minecraft.entity.ai.pathing.PathMinHeap();
            net.minecraft.entity.ai.pathing.TargetPathNode tgt = maker.getNode(
                    target.getX(), target.getY(), target.getZ());
            start.penalizedPathLength = 0.0f;
            start.distanceToNearestTarget = start.getManhattanDistance(tgt);
            start.heapWeight = start.distanceToNearestTarget;
            heap.clear();
            heap.push(start);
            tr.append("replica{start.pathLength=").append(start.pathLength)
                    .append(" penalized=").append(start.penalizedPathLength)
                    .append(" maxRange=").append(p.maxRange())
                    .append(" budget=").append((int) ((float) p.navRange() * p.followRange()))
                    .append(" target=(").append(target.getX()).append(',').append(target.getY()).append(',').append(target.getZ()).append(')');
            int visited = 0;
            int budget = (int) ((float) p.navRange() * p.followRange());
            int pops = 0;
            int pushed = 0;
            while (!heap.isEmpty()) {
                if (visited >= budget) {
                    tr.append(" BUDGET-STOP@").append(visited);
                    break;
                }
                visited++;
                PathNode cur = heap.pop();
                pops++;
                cur.visited = true;
                if (cur.getManhattanDistance(tgt) <= (float) p.reachRange()) {
                    tr.append(" FOUND@pop").append(pops).append(" at(").append(cur.x).append(',').append(cur.y).append(',').append(cur.z).append(')');
                    break;
                }
                if (cur.getDistance(start) >= p.maxRange()) {
                    if (pops <= 3) {
                        tr.append(" MAXRANGE-SKIP@pop").append(pops);
                    }
                    continue;
                }
                int n2 = maker.getSuccessors(succ2, cur);
                if (pops <= 3) {
                    tr.append(" | pop").append(pops).append("=(").append(cur.x).append(',').append(cur.y).append(',').append(cur.z)
                            .append(") curPathLen=").append(cur.pathLength).append(" succ=").append(n2);
                }
                for (int i = 0; i < n2; i++) {
                    PathNode s = succ2[i];
                    float d = cur.getDistance(s);
                    s.pathLength = cur.pathLength + d;
                    float g = cur.penalizedPathLength + d + s.penalty;
                    boolean accept = s.pathLength < p.maxRange() && (!s.isInHeap() || g < s.penalizedPathLength);
                    if (pops <= 2) {
                        tr.append(" [").append(i).append(":(").append(s.x).append(',').append(s.z).append(") type=").append(s.type)
                                .append(" pathLen=").append(s.pathLength).append(" pen=").append(s.penalty)
                                .append(" inHeap=").append(s.isInHeap()).append(" visited=").append(s.visited)
                                .append(" acc=").append(accept).append(']');
                    }
                    if (accept) {
                        s.previous = cur;
                        s.penalizedPathLength = g;
                        s.distanceToNearestTarget = s.getManhattanDistance(tgt) * 1.5f;
                        if (s.isInHeap()) {
                            heap.setNodeWeight(s, s.penalizedPathLength + s.distanceToNearestTarget);
                        } else {
                            s.heapWeight = s.penalizedPathLength + s.distanceToNearestTarget;
                            heap.push(s);
                            pushed++;
                        }
                    }
                }
            }
            tr.append(" pops=").append(pops).append(" pushed=").append(pushed).append('}');
            String line = "[cava/pathfind] DIAG preset=" + p.name() + " " + tr
                    + " mobPos=(" + mob.getX() + "," + mob.getY() + "," + mob.getZ() + ")"
                    + " start=" + (start == null ? "(null)" : "(" + start.x + "," + start.y + "," + start.z + ":" + start.type + ")")
                    + " successors=" + n + " [" + succ.toString().trim() + "]"
                    + " neighbourTypes=" + nt.toString().trim()
                    + " | " + PathfindPerfScenario.envProbe(world, cache, p, startPos, target);
            LOG.info(line);
            source.sendFeedback(() -> Text.literal(line), false);
        } finally {
            mob.discard();
        }
        return 1;
    }

    private static void fail(ServerCommandSource source, long id, String preset, String mode, int count, String reason) {
        String line = "[cava/pathfind] PERF id=" + id + " preset=" + preset + " mode=" + mode + " n=" + count
                + " ok=false reason=" + reason;
        LOG.warn(line);
        source.sendError(Text.literal(line));
    }

    private static long fnv(long h, int v) {
        h ^= (v & 0xffffffffL);
        h *= 0x100000001b3L;
        return h;
    }

    /** 最近秩百分位（nearest-rank）。 */
    private static long pct(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx];
    }
}
