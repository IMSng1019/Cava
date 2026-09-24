package cava.hook;

import cava.canary.HookCanary;
import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import cava.mirror.RegionSource;
import cava.mirror.SectionOriginRegistry;
import cava.mixin.pathfind.PathNodeNavigatorAccessor;
import cava.subsystem.SubsystemRegistry;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1 寻路的**接管编排**（任务清单第 3 步 / prompts/04）。
 *
 * <p>一次调用的完整链路：
 * <ol>
 *   <li>从原版上下文取输入（{@link MobInputs}）；</li>
 *   <li>让镜像流推区域 + 确认 profile 语义就绪（{@link RegionSource}）；</li>
 *   <li>上传 {@code CavaMobProfile}（**每次求解前重推**，captain 2026-09-22 裁决）；</li>
 *   <li>调 {@code CavaNative.pathfind}；</li>
 *   <li>返回值 &gt; 0 才把节点序列转回 {@code Path}（{@link NativePathBuilder}）；</li>
 *   <li>**任何错误码 / 异常 / 缺条件一律返回 {@code null} = 回退原逻辑**（{@code ci.cancel()} 根本不调用）。</li>
 * </ol>
 *
 * <p><b>明确不做的事</b>（oracle spec 4.3.1 + 任务清单第 4 条）：**多目标时直接不走原生**。
 * 原版 {@code found}/{@code targetMap} 的遍历顺序取决于 {@code HashMap/HashSet} 的桶序，
 * 无法复刻；单目标时无影响。
 *
 * <p><b>并发</b>：{@code cava_mob_profile_upload} / {@code cava_region_upload} 在原生侧是
 * **每个句柄一份可变状态**（不是每线程），而原版寻路跑在 {@code Util.getMainWorkerExecutor()} 的
 * **工作线程**上。因此在同一句柄上并发调用会互相踩踏 ⇒ 这里用 {@link #nativeLock} 串行化
 * 【镜像推送 + 档案上传 + cava_pathfind】。**这是冻结 ABI 的固有约束**，已上报 captain（见 notes）。
 */
public final class PathfindHook {

    /** 单例（注入体只认这一个实例）。 */
    public static final PathfindHook INSTANCE = new PathfindHook();

    /** hookId 与 {@code AbstractSubsystem} 的命名一致（{@code <id>:main}）。 */
    public static final String HOOK_ID = "pathfind:main";

    /** 一次调用最多写回的节点数（cap）。原版预算最大也就几千；给个上界防手滑。 */
    public static final int MAX_CAP = 8192;

    /** 连续失败多少次后彻底禁用（避免刷屏；契约要求"禁用而不是崩溃"）。 */
    private static final int MAX_CONSECUTIVE_ERRORS = 8;

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private final HookCanary canary = new HookCanary(HOOK_ID);
    private final ReentrantLock nativeLock = new ReentrantLock();
    private final Map<String, AtomicLong> reasons = new ConcurrentHashMap<>();
    private final AtomicLong takeovers = new AtomicLong();
    private final AtomicLong nativeCalls = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong consecutiveErrors = new AtomicLong();
    private final AtomicLong uploadNanos = new AtomicLong();
    private final AtomicLong solveNanos = new AtomicLong();
    /** 原生调用之后**被窗口截断检测判回退**的次数（回退率的分母之一）。 */
    private final AtomicLong windowFallbacks = new AtomicLong();
    /** 各判据的命中次数（含"计数但不回退"的 not-reached-structural）。 */
    private final Map<String, AtomicLong> guardHits = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // P1-NET：真实负载下的"每次调用"账本（"打开开关净赚还是净亏"的唯一直接证据）
    // ------------------------------------------------------------------

    /** 被**按规模分流**挡下（根本没调原生）的次数。 */
    private final AtomicLong gatedShort = new AtomicLong();
    /** 走 Java 原逻辑完成的调用数（含原生回退、被分流、开关关闭、非陆地档案…）。 */
    private final AtomicLong javaCalls = new AtomicLong();
    /** Java 原逻辑的累计挂钟（ns；只算 findPathToAny 方法体，不含调用方建 ChunkCache）。 */
    private final AtomicLong javaWallNanos = new AtomicLong();
    /** 原生一侧的累计挂钟（ns；镜像推送 + 档案上传 + cava_pathfind）。 */
    private final AtomicLong nativeWallNanos = new AtomicLong();
    /** 既没被原生记账、也没被 RETURN 记账的调用（正常为 0；>0 = 注入体有洞，如实报）。 */
    private final AtomicLong unaccounted = new AtomicLong();
    /** 不在服务端线程上跑的调用数（"寻路是不是主线程"的直接证据）。 */
    private final AtomicLong offThreadCalls = new AtomicLong();

    // ------------------------------------------------------------------
    // P1-CROSS：镜像账本窗口（"跨 tick 复用"这一注的**核心观测量**）
    // ------------------------------------------------------------------
    // 为什么放在这里：RegionMirror.report() 在此之前**没有调用点**（R5 记的可观测性待办），
    // 而跨 tick 复用的判决线就是 pushes / reuseSkips / crossTickReuseBlocked 三个数的比例。
    // 口径与其它账本一致：reset 时打一次快照，报告时给**窗口内增量**（sprint 前后相减）。

    /** reset 时的镜像计数快照（-1 = 当时拿不到镜像实例）。 */
    private final AtomicLong mPush0 = new AtomicLong(-1);
    private final AtomicLong mReuse0 = new AtomicLong(-1);
    private final AtomicLong mSame0 = new AtomicLong(-1);
    private final AtomicLong mCross0 = new AtomicLong(-1);
    private final AtomicLong mBlocked0 = new AtomicLong(-1);
    private final AtomicLong mInval0 = new AtomicLong(-1);
    private final AtomicLong mFail0 = new AtomicLong(-1);
    private final AtomicLong mCells0 = new AtomicLong(-1);
    private final AtomicLong mPushNanos0 = new AtomicLong(-1);
    private final AtomicLong hSection0 = new AtomicLong(-1);
    private final AtomicLong hInWindow0 = new AtomicLong(-1);
    private final AtomicLong hInval0 = new AtomicLong(-1);

    /** 当前进程内的镜像实现（拿不到 = null）。 */
    public static cava.mirror.RegionMirror mirrorInstance() {
        return PathfindMirrorBridge.getGlobal() instanceof cava.mirror.RegionMirror m ? m : null;
    }

    /** 快照镜像侧计数（{@link #resetLedger()} 里调用）：此后 {@link #aiDistReport()} 报窗口内增量。 */
    public void snapshotMirrorCounters() {
        cava.mirror.RegionMirror m = mirrorInstance();
        mPush0.set(m == null ? -1 : m.pushes());
        mReuse0.set(m == null ? -1 : m.reuseSkips());
        mSame0.set(m == null ? -1 : m.reuseSameTickHits());
        mCross0.set(m == null ? -1 : m.crossTickReuseHits());
        mBlocked0.set(m == null ? -1 : m.crossTickReuseBlocked());
        mInval0.set(m == null ? -1 : m.invalidationCount());
        mFail0.set(m == null ? -1 : m.failures());
        mCells0.set(m == null ? -1 : m.cellsCopied());
        mPushNanos0.set(m == null ? -1 : m.pushNanos());
        hSection0.set(SectionOriginRegistry.sectionHits());
        hInWindow0.set(SectionOriginRegistry.inWindowHits());
        hInval0.set(SectionOriginRegistry.invalidations());
    }

    /** 增量（快照缺失时按"从 0 开始"处理，绝不返回负数）。 */
    private static long win(long now, AtomicLong base) {
        long b = base.get();
        return b < 0 ? now : Math.max(0L, now - b);
    }

    /**
     * 镜像账本一行（**窗口内增量**）：跨 tick 复用能不能省下推送，就看
     * {@code mirrorCrossBlocked / (mirrorCrossBlocked + mirrorPushes)} 这个比例。
     */
    public String mirrorReport() {
        cava.mirror.RegionMirror m = mirrorInstance();
        if (m == null) {
            return "mirror=(none)";
        }
        long pushes = win(m.pushes(), mPush0);
        long cross = win(m.crossTickReuseHits(), mCross0);
        long blocked = win(m.crossTickReuseBlocked(), mBlocked0);
        long pushNanos = win(m.pushNanos(), mPushNanos0);
        StringBuilder sb = new StringBuilder();
        sb.append("mirrorPushes=").append(pushes)
                .append(" mirrorReuse=").append(win(m.reuseSkips(), mReuse0))
                .append(" mirrorSameTick=").append(win(m.reuseSameTickHits(), mSame0))
                .append(" mirrorCrossTick=").append(cross)
                .append(" mirrorCrossBlocked=").append(blocked)
                .append(" mirrorInval=").append(win(m.invalidationCount(), mInval0))
                .append(" mirrorFail=").append(win(m.failures(), mFail0))
                .append(" mirrorCells=").append(win(m.cellsCopied(), mCells0))
                .append(" mirrorPushMs=").append(ms(pushNanos))
                .append(" mirrorPushUs=").append(pushes == 0 ? "0.0"
                        : String.format(java.util.Locale.ROOT, "%.2f", pushNanos / 1000.0 / pushes))
                .append(" mirrorCrossTickEnabled=").append(cava.mirror.RegionMirror.crossTickReuseEnabled())
                .append(" mirrorInvalidationEnabled=").append(SectionOriginRegistry.invalidationEnabled())
                .append(" hookSectionHits=").append(win(SectionOriginRegistry.sectionHits(), hSection0))
                .append(" hookInWindow=").append(win(SectionOriginRegistry.inWindowHits(), hInWindow0))
                .append(" hookInvalidations=").append(win(SectionOriginRegistry.invalidations(), hInval0));
        return sb.toString();
    }

    /** 原生一侧的耗时拆分（窗口内增量）：{@code prepMs} = 镜像推送 + 档案上传，{@code solveMs} = cava_pathfind。 */
    public String nativeSplitReport() {
        return "prepMs=" + ms(uploadNanos.get()) + " solveMs=" + ms(solveNanos.get());
    }

    /** 起点→目标 3D 切比雪夫距离（方块）——分流的判据量，也是"真实 AI 规模分布"的一半。 */
    private final CallStats distStats = new CallStats();
    /** Java 结果的路径节点数。 */
    private final CallStats javaLenStats = new CallStats();
    /** 原生结果的节点数（接管 + 回退都记）。 */
    private final CallStats nativeLenStats = new CallStats();
    /** 被**接管**的原生结果节点数。 */
    private final CallStats takeoverLenStats = new CallStats();
    /** 被**回退**的原生结果节点数。 */
    private final CallStats fallbackLenStats = new CallStats();
    /** 走 Java 的调用耗时（ns）—— 不含回退时"先付掉"的那段原生。 */
    private final CallStats javaNsStats = new CallStats();
    /** 调了原生的调用耗时（ns）—— 推送 + 上传 + 求解，即"付掉的钱"。 */
    private final CallStats nativeNsStats = new CallStats();

    /**
     * 距离桶数。**必须是编译期常量**：实例字段的初始化（{@link #INSTANCE} 的构造）在静态字段
     * 文本顺序里**早于** {@code DIST_EDGES} 的赋值 —— 用 {@code DIST_EDGES.length} 会拿到 null
     * （实测：服务端在 {@code SERVER_STARTED} 上 {@code ExceptionInInitializerError} 直接崩掉启动，
     * 而单测与 {@code compileJava} 都发现不了）。下面的静态块在类初始化末尾复核两者一致。
     */
    private static final int BUCKET_COUNT = 12;

    /** 规模分布的桶边界（方块 / 节点）。 */
    private static final long[] DIST_EDGES = {2, 4, 8, 16, 24, 32, 48, 64, 96, 128, 192};
    private static final long[] LEN_EDGES = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512};

    static {
        if (BUCKET_COUNT != DIST_EDGES.length + 1) {
            throw new IllegalStateException("BUCKET_COUNT(" + BUCKET_COUNT + ") != DIST_EDGES.length+1("
                    + (DIST_EDGES.length + 1) + ")：桶边界与数组长度必须同步改");
        }
    }

    /**
     * **按"起点→目标距离"分桶的账本**（P1-NET 核心件）：下标 = 桶号（见 {@link #bucketOf}）。
     *
     * <p>为什么必须有它：分流阈值要回答的是"距离小于 T 的调用接管了是赚还是亏"，
     * 而那是一个**逐桶的**问题（java 耗时与 native 耗时都随规模变化）。
     * 有了逐桶的 {@code javaNs/nativeNs/take/fb}，任意阈值 T 的净收益都能从两条腿的原始数据算出来，
     * 不必为每个候选阈值各跑一次 A/B（那既慢又不可比）。
     */
    private final long[] bucketCalls = new long[BUCKET_COUNT];
    private final long[] bucketJavaNs = new long[BUCKET_COUNT];
    private final long[] bucketNativeNs = new long[BUCKET_COUNT];
    private final long[] bucketTake = new long[BUCKET_COUNT];
    private final long[] bucketFallback = new long[BUCKET_COUNT];
    private final long[] bucketGated = new long[BUCKET_COUNT];
    private final long[] bucketLenSum = new long[BUCKET_COUNT];

    /** 原版节点预算 {@code (int)(navRange * followRange)} 的分布 = "搜索规模的**上界**"。 */
    private final CallStats budgetStats = new CallStats();

    private volatile boolean disabled;
    private volatile String disabledReason = "";
    private final java.util.concurrent.atomic.AtomicBoolean profileGateBypassWarned =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean bitOrderChecked;
    private volatile String bitOrderProblem = "";

    /**
     * "一次逻辑调用"的线程局部标记。
     *
     * <p>为什么要它：{@code findPathToAny} 有两个重载，public（Set）版在方法体里调用 private（Map）版。
     * 两个都注入 ⇒ 一次生物寻路会命中注入体两次。金丝雀必须数**逻辑调用**，否则计数是 2 倍，
     * "计数 == 迭代次数"这条硬证据就不成立了。
     *
     * <p>放在这里而不是 mixin 里：mixin 的静态字段初始化依赖 Mixin 的 {@code <clinit>} 合并行为，
     * 一旦不生效就是 {@code null} 引用（注入点抛异常 = 崩服）。普通类的静态/实例字段没有这个风险。
     */
    private final ThreadLocal<Boolean> logicalCall = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * "一次逻辑调用"的账本探针（P1-NET 加）。
     *
     * <p>它把"从 HEAD 到 RETURN 的挂钟"拆成三条互不重叠的账：原生付掉的那段（{@code nativeNs}）、
     * Java 逻辑那段、以及"回退 = 两段都付"的合账。没有它就只能拿合成场景的每次调用耗时去**推测**
     * 真实负载的净收益（P1-PERF 文档里那一列"每 tick 投影"就是这么来的，本流把它换成直测）。
     */
    private static final class Probe {
        /** HEAD 时的 {@code System.nanoTime()}；0 = 本次没有起点时间。 */
        long t0;
        /** 这一次调用是否已经在原生分支里记过账（记过 = RETURN 不再整段记 Java）。 */
        boolean accounted;
        /** 原生分支返回了 null ⇒ Java 逻辑还要再跑一遍（白付一次原生）。 */
        boolean fellBack;
        /** 原生那一侧的挂钟（ns）。 */
        long nativeNs;
        /** 规模是否已经记过（一次逻辑调用只记一次）。 */
        boolean distRecorded;
        /** 起点→目标切比雪夫距离（方块）；-1 = 未知（多目标/空参数）。 */
        int dist = -1;
        /** 距离所在的桶号；-1 = 不计桶。 */
        int bucket = -1;
        /** 原生那段挂钟（等 RETURN 时和 Java 那段一起成行入桶）。 */
        long bucketNativePending;
        /** 这一次原生返回了 null（回退）⇒ 桶里那一行要标 fallback。 */
        boolean bucketFb;
        /** 原生返回的节点数（回退那一行也要记规模）。 */
        int bucketNativeLen;
        /** 被分流挡下（桶里那一行要标 gated）。 */
        boolean wasGated;
        /** 回退归因诊断：被回退掉的那条原生路径的指纹（0 = 没采）。 */
        long fallbackSig;
        /** 回退归因诊断：这一次回退要不要在 RETURN 里和 Java 结果比一次。 */
        boolean comparePending;
    }

    /**
     * 一次调用在桶账本里**恰好占一行**（所以只在两个地方调用：原生分支与 RETURN 的 Java 分支）。
     * {@code synchronized}：寻路可能在多条工作线程上跑（实测本整合包 offThread=0，但不假设它恒为 0）。
     */
    private synchronized void bucketAdd(int b, long javaNs, long nativeNs,
                                        int take, int fb, int gated, long len) {
        if (b < 0 || b >= bucketCalls.length) {
            return;
        }
        bucketCalls[b]++;
        bucketJavaNs[b] += javaNs;
        bucketNativeNs[b] += nativeNs;
        bucketTake[b] += take;
        bucketFallback[b] += fb;
        bucketGated[b] += gated;
        bucketLenSum[b] += len;
    }

    private final ThreadLocal<Probe> probe = ThreadLocal.withInitial(Probe::new);

    /** 成对对照诊断：正在跑"把注入体当不存在"的那次 Java 重放（防重入）。 */
    private final ThreadLocal<Boolean> replay = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 这一次调用是否发生在 Java 重放里（重放不走任何记账）。 */
    private boolean inReplay() {
        return Boolean.TRUE.equals(replay.get());
    }

    // ---- 成对对照（-Dcava.pathfind.diagnostic.compareAll=true）的账 ----
    private final AtomicLong pairCalls = new AtomicLong();
    private final AtomicLong pairSame = new AtomicLong();
    private final AtomicLong pairDiff = new AtomicLong();
    private final AtomicLong pairRefJavaNs = new AtomicLong();
    private final AtomicLong pairNativeNs = new AtomicLong();
    private final AtomicLong pairFbJavaNs = new AtomicLong();
    private final AtomicLong pairJavaLen = new AtomicLong();
    private final AtomicLong pairNativeLen = new AtomicLong();

    /**
     * **成对净收益**（一行；只有 compareAll 打开时才有值）：
     * {@code pairNetUs = (Σ 参考 Java − Σ 原生 − Σ 回退那次的 Java) / 1000}。
     * 这三项都来自**同一次调用**，所以没有跨腿噪声；除以 tick 数就是每 tick 值。
     */
    public String pairReport() {
        long net = pairRefJavaNs.get() - pairNativeNs.get() - pairFbJavaNs.get();
        long calls = pairCalls.get();
        return "pairCalls=" + calls
                + " pairSame=" + pairSame.get()
                + " pairDiff=" + pairDiff.get()
                + " pairRefJavaMs=" + ms(pairRefJavaNs.get())
                + " pairNativeMs=" + ms(pairNativeNs.get())
                + " pairFbJavaMs=" + ms(pairFbJavaNs.get())
                + " pairNetMs=" + ms(net)
                + " pairNetUsPerCall=" + (calls == 0 ? "0.000"
                        : String.format(java.util.Locale.ROOT, "%.3f", net / 1000.0 / calls))
                + " pairJavaLenAvg=" + (calls == 0 ? "0.0"
                        : String.format(java.util.Locale.ROOT, "%.2f", pairJavaLen.get() / (double) calls))
                + " pairNativeLenAvg=" + (calls == 0 ? "0.0"
                        : String.format(java.util.Locale.ROOT, "%.2f", pairNativeLen.get() / (double) calls));
    }

    private PathfindHook() {
    }

    // ------------------------------------------------------------------
    // 注入点入口
    // ------------------------------------------------------------------

    /**
     * 注入体第一句调用：**金丝雀计数 + 一次性引导**。
     *
     * <p>它刻意**不受开关影响**：金丝雀要回答的是"注入点到底有没有生效"，
     * 与"要不要接管"是两个问题。关掉接管时计数仍然增长，正是"开关只影响接管"的证据。
     */
    public void onHookEntry() {
        PathfindBootstrap.ensureInstalled();
        canary.hit();
    }

    /** public（Set）重载进入时调用：标记"一次逻辑调用开始"，并起表（P1-NET 的账本）。 */
    public void enterOuterCall() {
        if (inReplay()) {
            return;   // 重放调用不走账本（它的耗时由外层单独计）
        }
        logicalCall.set(Boolean.TRUE);
        Probe p = probe.get();
        p.t0 = System.nanoTime();
        p.accounted = false;
        p.fellBack = false;
        p.nativeNs = 0L;
        p.distRecorded = false;
        p.dist = -1;
        p.bucket = -1;
    }

    /**
     * 注入体 HEAD 的**参数快照**（P1-NET 加）：把这一次调用的"搜索规模口径"记下来。
     *
     * <p>为什么不放在 {@code tryTakeover} 里：那是"要不要接管"的路径，开关关闭时根本不走；
     * 而"真实 AI 的规模分布"必须在 off 腿上也能测到（off 腿才是分布的真值）。
     *
     * @param navRange 原生/原版导航器的 {@code range}（{@code PathNodeNavigatorAccessor}）；
     *                 与 {@code followRange} 相乘就是原版节点预算 = 搜索展开规模的上界
     */
    public void onCallArgs(MobEntity mob, Set<BlockPos> targets, float maxRange, int reachRange,
                           float followRange, int navRange) {
        if (inReplay()) {
            return;
        }
        if (mob == null || targets == null || targets.size() != 1) {
            return;
        }
        BlockPos t = targets.iterator().next();
        if (t == null) {
            return;
        }
        Probe p = probe.get();
        p.dist = chebyshev(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                t.getX(), t.getY(), t.getZ());
        p.bucket = bucketOf(p.dist);
        if (navRange > 0 && followRange > 0f) {
            budgetStats.add((long) ((float) navRange * followRange));
        }
    }

    /** 距离 → 桶号（{@link #DIST_EDGES} 是上界；最后一桶是"大于最大边界"）。 */
    public static int bucketOf(int dist) {
        int b = 0;
        while (b < DIST_EDGES.length && dist > DIST_EDGES[b]) {
            b++;
        }
        return b;
    }

    /** 桶数（回执/测试用）。 */
    public static int bucketCount() {
        return DIST_EDGES.length + 1;
    }

    /** 桶上界（-1 = 无上界）；回执表头用。 */
    public static long bucketEdge(int b) {
        return b >= DIST_EDGES.length ? -1L : DIST_EDGES[b];
    }

    /**
     * private（Map）重载进入时调用。
     *
     * @return true = 这是外层调用引发的嵌套进入（**不要再记一次数**）
     */
    public boolean enterInnerCall() {
        boolean nested = Boolean.TRUE.equals(logicalCall.get());
        logicalCall.set(Boolean.FALSE);
        return nested;
    }

    public HookCanary canary() {
        return canary;
    }

    public long canaryCount() {
        return canary.count();
    }

    /** 注入体是否要继续往下走（false = 打完招呼立刻 return）。 */
    public boolean hookEnabled() {
        return PathfindSwitches.hookEnabled();
    }

    /**
     * 现在是否允许原生接管（**全部条件必须同时成立**）。
     *
     * <p>缺任何一条都只是"回退"，不是错误。逐条原因见 {@link #takeoverBlockers()}。
     */
    public boolean enabled() {
        if (disabled || !PathfindSwitches.hookEnabled() || !PathfindSwitches.nativeTakeoverEnabled()) {
            return false;
        }
        return CavaNative.get().available() && bitOrderOk();
    }

    /** 逐条列出"为什么现在不能接管"（启动日志用；空列表 = 可以接管）。 */
    public List<String> takeoverBlockers() {
        List<String> out = new ArrayList<>();
        if (disabled) {
            out.add("hook 已被禁用：" + disabledReason);
        }
        if (!PathfindSwitches.hookEnabled()) {
            out.add("-D" + PathfindSwitches.PROP_HOOK + "=false（注入体总开关关闭）");
        }
        if (!PathfindSwitches.nativeTakeoverEnabled()) {
            out.add("-D" + PathfindSwitches.PROP_NATIVE + "=false（默认关：captain 的 P1 门禁，"
                    + "cava_pathfind 保守返回 CAVA_ERR_UNIMPLEMENTED 且 CAVA_PF_* 位号对齐未回执）");
        }
        CavaNative nat = CavaNative.get();
        if (!nat.available()) {
            out.add("原生库不可用：status=" + nat.status());
        }
        if (!bitOrderOk()) {
            out.add("惩罚表索引语义自检失败：" + bitOrderProblem);
        }
        if (PathfindMirrorBridge.getGlobal() == null) {
            out.add("镜像实现未发现（" + PathfindMirrorBridge.describe() + "）");
        }
        // 档案的权威生产者是注入流（captain 2026-09-22 裁决），不存在"镜像流是否提供档案"这个问题了
        return out;
    }

    /** 启动时打一行状态（Cava 的横幅之外，本包自报）。 */
    public void logStartupState() {
        List<String> blockers = takeoverBlockers();
        if (blockers.isEmpty()) {
            LOG.info("[cava/pathfind] 接管条件全部满足（{}）", PathfindSwitches.describe());
        } else {
            LOG.info("[cava/pathfind] 原生接管当前**关闭**（回退原逻辑），原因 {} 条：{}",
                    blockers.size(), String.join(" | ", blockers));
        }
    }

    // ------------------------------------------------------------------
    // 惩罚表索引语义自检（门禁 #7 的坑 1：错一位 = 整张表错位且看不出来）
    // ------------------------------------------------------------------

    private boolean bitOrderOk() {
        if (!bitOrderChecked) {
            synchronized (this) {
                if (!bitOrderChecked) {
                    List<String> problems = AbiPenaltyOrder.checkAgainstVanillaOrder(vanillaTypeNames());
                    bitOrderProblem = problems.isEmpty() ? "" : String.join("; ", problems);
                    if (!problems.isEmpty()) {
                        LOG.error("[cava/pathfind] CAVA_PNT_* 的索引语义与 PathNodeType 的 ordinal 不一致，"
                                + "惩罚表会整体错位（路径看起来正常但不与原版一致）⇒ 拒绝原生接管：{}", bitOrderProblem);
                    }
                    bitOrderChecked = true;
                }
            }
        }
        return bitOrderProblem.isEmpty();
    }

    private static List<String> vanillaTypeNames() {
        List<String> names = new ArrayList<>();
        for (net.minecraft.entity.ai.pathing.PathNodeType t : net.minecraft.entity.ai.pathing.PathNodeType.values()) {
            names.add(t.name());
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 接管
    // ------------------------------------------------------------------

    /**
     * 尝试用原生结果替换本次 {@code findPathToAny}。
     *
     * @return 原版 {@code Path}（调用方 {@code cir.setReturnValue}）；{@code null} = **回退原逻辑**
     */
    public Path tryTakeover(PathNodeNavigator navigator, ChunkCache chunkCache, MobEntity mob,
                            Set<BlockPos> targets, float maxRange, int reachRange, float followRange) {
        if (inReplay()) {
            return null;   // 成对对照的 Java 重放：让原逻辑照跑
        }
        if (!enabled()) {
            count("skipped");
            return null;
        }
        try {
            return doTakeover(navigator, chunkCache, mob, targets, maxRange, reachRange, followRange);
        } catch (Throwable t) {
            onError("编排抛出异常", t);
            return null;
        }
    }

    private Path doTakeover(PathNodeNavigator navigator, ChunkCache chunkCache, MobEntity mob,
                            Set<BlockPos> targets, float maxRange, int reachRange, float followRange) {
        // (1) 多目标 → 直接不走原生（oracle spec 4.3.1：found/targetMap 的桶序无法复刻）
        if (targets == null || targets.size() != 1) {
            count(targets == null ? "targets-null" : "multi-target");
            return null;
        }
        BlockPos target = targets.iterator().next();
        if (target == null || mob == null) {
            count("bad-args");
            return null;
        }
        // (1.5) **按规模分流**（P1-NET）：短程搜索原生本来就慢，直接不进入原生管线。
        // 判据 = 起点→目标的 3D 切比雪夫距离 < 阈值。成本 O(1)（两次减法 + max + 一次比较），
        // 位置在**镜像推送 / 档案上传 / 跨界调用之前** ⇒ 被挡下的调用一分钱原生成本都不付。
        // 阈值的数据出处见 docs/CAVA-p1-net-notes.md（真实规模分布 + 逐距离换手点扫描）。
        long gateMin = PathfindSwitches.gateMinBlocks();
        if (gateMin > 0L) {
            int cheb = chebyshev(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                    target.getX(), target.getY(), target.getZ());
            if ((long) cheb < gateMin) {
                gatedShort.incrementAndGet();
                probe.get().wasGated = true;   // 由 RETURN 那一笔连"Java 花了多少"一起入桶
                count("gated-short");
                return null;
            }
        }
        Object self = navigator;
        if (!(self instanceof PathNodeNavigatorAccessor acc)) {
            count("accessor-missing");
            return null;
        }
        PathNodeMaker maker = acc.cava$pathNodeMaker();
        int range = acc.cava$range();
        if (maker == null) {
            count("maker-null");
            return null;
        }
        // (2) 只有陆地/两栖档案能做（原生内核未实现飞行/水生）
        MobInputs.MakerKind kind = MobInputs.kindOf(maker);
        if (kind != MobInputs.MakerKind.LAND) {
            count("maker-" + kind.name().toLowerCase(java.util.Locale.ROOT));
            return null;
        }
        if (maker instanceof net.minecraft.entity.ai.pathing.AmphibiousPathNodeMaker amp
                && !AmphibiousPathNodeMakerAccess.readable(amp)) {
            // 两栖档案少一个能力位 ⇒ 马吕斯会与原版不同 ⇒ 宁可不加速
            count("amphibious-accessor-missing");
            return null;
        }
        // (3) 镜像侧必须显式就绪
        CavaNative nat = CavaNative.get();
        long handle = nat.handle();
        World world = mob.getWorld();
        RegionSource mirror = PathfindMirrorBridge.get(world);
        if (mirror == null) {
            count("mirror-missing");
            return null;
        }
        // 档案的**权威生产者 = 注入流**（captain 2026-09-22 裁决）：档案里含实体位姿与 26 项惩罚表，
        // 只有注入点拿得到；镜像流只负责"写进原生"（它持有 handle 与 arena 生命周期）。
        // ⇒ 没有"两个生产者"，也没有互斥桥。
        MobProfileData profile = MobInputs.build(mob, maker);
        if (!mirror.isFlagsReadyFor(profile.caps)) {
            if (!PathfindSwitches.bypassProfileGate()) {
                count("flags-not-ready");
                return null;
            }
            if (profileGateBypassWarned.compareAndSet(false, true)) {
                LOG.error("[cava/pathfind] ⚠ 诊断开关 -D{}=true 生效：**跳过了镜像流的 flags 就绪门禁**。"
                        + "这只有在 cava_pathfind 保守回退（CAVA_ERR_UNIMPLEMENTED）时才安全；"
                        + "位号对齐后必须关掉。", PathfindSwitches.PROP_BYPASS_PROFILE_GATE);
            }
            count("flags-gate-bypassed");
        }
        // (4) 原版节点预算（oracle spec 4.1）
        int budget = (int) ((float) range * followRange);
        if (budget <= 0) {
            count("budget-nonpositive");
            return null;
        }
        int cap = Math.min(budget, MAX_CAP);

        nativeLock.lock();
        long tNative0 = System.nanoTime();
        try {
            long t0 = System.nanoTime();
            // 窗口策略归**镜像侧**（captain 2026-09-22 裁决，契约 pushForSolve）：
            // 实测"注入流每次自己算并重推 35³=42875 格"≈320 µs/次，而 vanilla 整个求解只要 ≈33 µs
            // ⇒ 打开原生反而慢 13.9 倍。现在只给起点/终点/体型，推什么、要不要复用由镜像侧决定。
            RegionSource.Pushed window;
            try {
                window = mirror.pushForSolve(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                        target.getX(), target.getY(), target.getZ(),
                        profile.width, profile.height, profile.safeFallDistance);
            } catch (RegionSource.MirrorUnavailableException e) {
                count("mirror-unavailable");
                return null;
            }
            // 注入流负责**填值**（每次求解前重推，captain 裁决不得跨 tick 复用），
            // 镜像流负责把这段内容写进原生（它持有 handle 与 arena 的生命周期）。
            if (!mirror.uploadProfileForSolve(handle, seg -> profile.writeTo(seg, 0))) {
                count("profile-upload-refused");
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment req = arena.allocate(CavaLayouts.PATH_REQUEST);
                fillRequest(req, target, reachRange, maxRange, budget);
                MemorySegment out = CavaNative.allocateArray(arena, CavaLayouts.PATH_NODE, cap);
                long t1 = System.nanoTime();
                uploadNanos.addAndGet(t1 - t0);
                int rc = nat.pathfind(handle, req, out, cap);
                solveNanos.addAndGet(System.nanoTime() - t1);
                nativeCalls.incrementAndGet();
                // **原生这一侧到底付了多少钱**（推送 + 上传 + 求解；不含等锁）：接管与回退都要记，
                // 因为"回退"的代价正是"这笔钱白付 + 再跑一遍 Java"。修好 47.8%/51.8% 回退率的账
                // 只能靠这个数，不能靠每次调用的平均值去推。
                Probe pr = probe.get();
                long nativePart = System.nanoTime() - tNative0;
                pr.accounted = true;
                pr.fellBack = true;      // 从这里往下每一条 return null 都意味着 Java 还要再跑一遍
                pr.nativeNs = nativePart;
                pr.bucketNativePending = nativePart;
                nativeNsStats.add(nativePart);
                nativeWallNanos.addAndGet(nativePart);
                recordDistOnce(pr, mob, targets);
                if (PathfindOutcome.classify(rc, cap) != PathfindOutcome.Kind.TAKE_OVER) {
                    count(rc == CavaLayouts.CAVA_ERR_UNIMPLEMENTED ? "native-unimplemented"
                            : "native-" + CavaLayouts.errorName(rc));
                    return null;
                }
                List<NativeNodeCodec.Node> nodes = NativeNodeCodec.decode(out, rc);
                if (nodes == null) {
                    count("decode-rejected");
                    return null;
                }
                // **窗口截断检测**（缺陷 2 的修复件）：原生只看得到 pushForSolve 推的那个矩形，
                // 而原版最优路径可以离开它（实测 detour128/repush：原生 64 节点停在墙前 vs 原版 128 节点绕过墙）。
                // 判定为"可能被窗口截断" ⇒ 这次调用**整体回退 Java**（返回 null，原逻辑照跑）。
                WindowTruncationGuard.Verdict verdict = WindowTruncationGuard.check(
                        window.originX(), window.originY(), window.originZ(),
                        window.dimX(), window.dimY(), window.dimZ(),
                        world.getBottomY(), world.getTopY() - 1,
                        nodes, target.getX(), target.getY(), target.getZ(), reachRange);
                if (!verdict.reason().isEmpty()) {
                    guardHits.computeIfAbsent(verdict.reason(), k -> new AtomicLong()).incrementAndGet();
                }
                nativeLenStats.add(nodes.size());
                if (verdict.fallback()) {
                    windowFallbacks.incrementAndGet();
                    fallbackLenStats.add(nodes.size());
                    pr.bucketFb = true;
                    pr.bucketNativeLen = nodes.size();
                    if (PathfindSwitches.fallbackCompare() || PathfindSwitches.compareAll()) {
                        pr.fallbackSig = sigOfNodes(nodes);
                        pr.comparePending = true;
                    }
                    count("window-" + verdict.reason());
                    return null;
                }
                Path path = NativePathBuilder.toPath(nodes, target, reachRange);
                if (path == null) {
                    count("decode-rejected");
                    return null;
                }
                takeovers.incrementAndGet();
                pr.fellBack = false;
                takeoverLenStats.add(nodes.size());
                if (PathfindSwitches.compareAll()) {
                    // **成对对照**：把注入体当不存在，用同样的入参再解一次（内层会看到 replay 标志 ⇒
                    // 直接走原逻辑）。放在这里（而不是开头）是为了让原生的那段计时不受这次 Java 影响。
                    long tr0 = System.nanoTime();
                    replay.set(Boolean.TRUE);
                    Path ref = null;
                    try {
                        ref = navigator.findPathToAny(chunkCache, mob, targets, maxRange, reachRange, followRange);
                    } catch (Throwable t) {
                        LOG.warn("[cava/pathfind] 成对对照的 Java 重放抛异常（忽略这一次的配对）", t);
                    } finally {
                        replay.set(Boolean.FALSE);
                    }
                    long refNs = System.nanoTime() - tr0;
                    if (ref != null) {
                        pairCalls.incrementAndGet();
                        pairRefJavaNs.addAndGet(refNs);
                        pairNativeNs.addAndGet(pr.bucketNativePending);
                        pairJavaLen.addAndGet(ref.getLength());
                        pairNativeLen.addAndGet(nodes.size());
                        if (sigOfPath(ref) == sigOfNodes(nodes)) {
                            pairSame.incrementAndGet();
                        } else {
                            pairDiff.incrementAndGet();
                        }
                    }
                }
                // 接管成功的这次调用在桶账本里就地成行（RETURN 是否还会走一遍不确定 ⇒ 不能依赖它）
                bucketAdd(pr.bucket, 0L, pr.bucketNativePending, 1, 0, 0, nodes.size());
                pr.bucketNativePending = 0L;
                consecutiveErrors.set(0);
                return path;
            }
        } finally {
            nativeLock.unlock();
        }
    }

    private static void fillRequest(MemorySegment req, BlockPos target, int reachRange, float maxRange, int budget) {
        long[] o = CavaLayouts.PATH_REQUEST_OFFSETS;
        req.fill((byte) 0);
        req.set(ValueLayout.JAVA_LONG, o[0], 0L);              // reserved1：契约要求 0
        req.set(ValueLayout.JAVA_INT, o[1], target.getX());
        req.set(ValueLayout.JAVA_INT, o[2], target.getY());
        req.set(ValueLayout.JAVA_INT, o[3], target.getZ());
        req.set(ValueLayout.JAVA_INT, o[4], reachRange);
        req.set(ValueLayout.JAVA_FLOAT, o[5], maxRange);
        req.set(ValueLayout.JAVA_INT, o[6], 0);                // flags：契约要求 0
        req.set(ValueLayout.JAVA_INT, o[7], 0);                // reserved0
        req.set(ValueLayout.JAVA_INT, o[8], 0);                // reserved2
        req.set(ValueLayout.JAVA_INT, o[9], budget);           // max_visited_nodes
    }

    // ------------------------------------------------------------------
    // P1-NET：返回点记账（注入体的 RETURN 钩子）
    // ------------------------------------------------------------------

    /**
     * 注入体 RETURN 调用（只挂在 public/Set 重载上）：把"这一次调用最终走了 Java"记进账本。
     *
     * <p>三种情形：
     * <ul>
     *   <li>原生接管成功（{@code accounted && !fellBack}）：原生分支已经记过，这里什么都不做；</li>
     *   <li>原生调过但返回 null（{@code accounted && fellBack}）：**只记 Java 那一段的增量**
     *       （总挂钟 − 原生那段），于是"白付一次原生 + 再跑一次 Java"在账本上就是两笔，</li>
     *   <li>根本没调原生（开关关闭 / 被分流 / 档案不匹配…）：整段都是 Java。</li>
     * </ul>
     */
    public void onCallReturn(Path result, MobEntity mob, Set<BlockPos> targets) {
        if (inReplay()) {
            return;   // 同上：重放不记账
        }
        Probe p = probe.get();
        long now = System.nanoTime();
        long wall = p.t0 == 0L ? -1L : now - p.t0;
        p.t0 = 0L;
        if (p.accounted) {
            boolean fellBack = p.fellBack;
            long nativePart = p.nativeNs;
            p.accounted = false;
            p.fellBack = false;
            p.nativeNs = 0L;
            if (!fellBack) {
                return;   // 接管：账已经在原生分支里记完
            }
            if (PathfindSwitches.compareAll() && result != null) {
                // 回退那一次的 Java 就是它自己的参考解（同一次调用、同一份地形）⇒ 也进成对账
                pairCalls.incrementAndGet();
                pairRefJavaNs.addAndGet(wall < 0L ? 0L : wall - nativePart);
                pairNativeNs.addAndGet(nativePart);
                pairFbJavaNs.addAndGet(wall < 0L ? 0L : wall - nativePart);
                pairJavaLen.addAndGet(result.getLength());
                pairNativeLen.addAndGet(p.bucketNativeLen);
                if (sigOfPath(result) == p.fallbackSig) {
                    pairSame.incrementAndGet();
                } else {
                    pairDiff.incrementAndGet();
                }
            }
            if (p.comparePending) {
                p.comparePending = false;
                fbCompare.incrementAndGet();
                if (result == null) {
                    fbNull.incrementAndGet();
                } else {
                    if (sigOfPath(result) == p.fallbackSig) {
                        fbSame.incrementAndGet();
                    } else {
                        fbDiff.incrementAndGet();
                    }
                    if (p.bucketNativeLen == result.getLength()) {
                        fbSameLen.incrementAndGet();
                    }
                }
                p.fallbackSig = 0L;
            }
            long javaPart = wall < 0L ? 0L : Math.max(0L, wall - nativePart);
            javaCalls.incrementAndGet();
            javaWallNanos.addAndGet(javaPart);
            javaNsStats.add(javaPart);
            long len = result == null ? 0L : result.getLength();
            javaLenStats.add(len);
            // 回退那一行 = 白付的原生（nativeNs）+ 再跑的 Java（javaNs），两边都在桶里 ⇒ 净收益可直接算
            bucketAdd(p.bucket, javaPart, p.bucketNativePending,
                    p.bucketFb ? 0 : 0, p.bucketFb ? 1 : 0, 0, p.bucketNativeLen);
            p.bucketNativePending = 0L;
            p.bucketNativeLen = 0;
            p.bucketFb = false;
            return;
        }
        if (wall < 0L) {
            unaccounted.incrementAndGet();
            return;
        }
        javaCalls.incrementAndGet();
        javaWallNanos.addAndGet(wall);
        javaNsStats.add(wall);
        long len = result == null ? 0L : result.getLength();
        javaLenStats.add(len);
        recordDistOnce(p, mob, targets);
        bucketAdd(p.bucket, wall, 0L, 0, 0, p.wasGated ? 1 : 0, len);
        p.wasGated = false;
        Thread st = TickTimeRecorder.get().serverThread();
        if (st != null && Thread.currentThread() != st) {
            offThreadCalls.incrementAndGet();
        }
    }

    /** 一次逻辑调用只记一次"规模"（原生分支与 RETURN 都可能走到这里）。 */
    private void recordDistOnce(Probe p, MobEntity mob, Set<BlockPos> targets) {
        if (p.distRecorded || mob == null || targets == null || targets.size() != 1) {
            return;
        }
        BlockPos t = targets.iterator().next();
        if (t == null) {
            return;
        }
        p.distRecorded = true;
        distStats.add(chebyshev(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                t.getX(), t.getY(), t.getZ()));
    }

    /** 3D 切比雪夫距离（方块）= 能斜着走的陆地生物"路径步数"的下界；分流闸门用它。 */
    public static int chebyshev(int ax, int ay, int az, int bx, int by, int bz) {
        int dx = Math.abs(ax - bx);
        int dy = Math.abs(ay - by);
        int dz = Math.abs(az - bz);
        return Math.max(dx, Math.max(dy, dz));
    }

    /**
     * **真实负载账本**（一行、无空格，便于逐 token 解析）：
     * 调用数 / 去向分布 / 规模分布 / 两边各自的耗时分布 / 两边的总时间。
     *
     * <p>净收益就是 {@code javaTotalMs} 与 {@code nativeTotalMs} 两列在两条腿上的对照：
     * off 腿只有 {@code javaTotalMs}（就是"寻路段自身耗时"），on 腿是
     * {@code nativeTotalMs}（接管 + 白付的回退）加 {@code javaTotalMs}（回退那一段 + 被分流的）。
     */
    public String aiDistReport() {
        long calls = javaCalls.get() + takeovers.get();
        StringBuilder sb = new StringBuilder();
        sb.append("AIDIST calls=").append(calls)
                .append(" java=").append(javaCalls.get())
                .append(" native=").append(nativeCalls.get())
                .append(" takeover=").append(takeovers.get())
                .append(" fallback=").append(windowFallbacks.get())
                .append(" gated=").append(gatedShort.get())
                .append(" unaccounted=").append(unaccounted.get())
                .append(" offThread=").append(offThreadCalls.get());
        sb.append(' ').append(distStats.fields("dist", 1.0, ""));
        sb.append(" distHist=").append(distStats.histogram(DIST_EDGES));
        sb.append(' ').append(javaLenStats.fields("javaLen", 1.0, ""));
        sb.append(' ').append(nativeLenStats.fields("nativeLen", 1.0, ""));
        sb.append(" takeoverLenHist=").append(takeoverLenStats.histogram(LEN_EDGES));
        sb.append(" fallbackLenHist=").append(fallbackLenStats.histogram(LEN_EDGES));
        sb.append(' ').append(javaNsStats.fields("javaUs", 1.0e-3, ""));
        sb.append(' ').append(nativeNsStats.fields("nativeUs", 1.0e-3, ""));
        sb.append(" javaTotalMs=").append(ms(javaWallNanos.get()));
        sb.append(" nativeTotalMs=").append(ms(nativeWallNanos.get()));
        sb.append(" gateMinBlocks=").append(PathfindSwitches.gateMinBlocks());
        if (PathfindSwitches.fallbackCompare()) {
            sb.append(' ').append(fallbackCompareReport());
        }
        if (PathfindSwitches.compareAll()) {
            sb.append(' ').append(pairReport());
        }
        sb.append(' ').append(budgetStats.fields("budget", 1.0, ""));
        sb.append(" budgetHist=").append(budgetStats.histogram(new long[] {
                256, 512, 1024, 2048, 4096, 8192, 16384, 32768}));
        // P1-CROSS：镜像账本（推送/复用/跨tick/失效）+ 原生耗时拆分（推送+档案 vs 求解）
        sb.append(' ').append(mirrorReport());
        sb.append(' ').append(nativeSplitReport());
        return sb.toString();
    }

    private static String ms(long ns) {
        return String.format(java.util.Locale.ROOT, "%.3f", ns / 1.0e6);
    }

    /**
     * 只把**账本**清零（不动 canary / reasons / 禁用状态）—— 给"真实负载 sprint"用：
     * {@code aidist reset} → sprint → {@code aidist}，两次之间的窗口就是采样窗口。
     */
    public void resetLedger() {
        gatedShort.set(0);
        javaCalls.set(0);
        javaWallNanos.set(0);
        nativeWallNanos.set(0);
        unaccounted.set(0);
        offThreadCalls.set(0);
        takeovers.set(0);
        nativeCalls.set(0);
        windowFallbacks.set(0);
        uploadNanos.set(0);
        solveNanos.set(0);
        guardHits.clear();
        distStats.reset();
        javaLenStats.reset();
        nativeLenStats.reset();
        takeoverLenStats.reset();
        fallbackLenStats.reset();
        javaNsStats.reset();
        nativeNsStats.reset();
        budgetStats.reset();
        fbCompare.set(0);
        fbSame.set(0);
        fbDiff.set(0);
        fbNull.set(0);
        fbSameLen.set(0);
        pairCalls.set(0);
        pairSame.set(0);
        pairDiff.set(0);
        pairRefJavaNs.set(0);
        pairNativeNs.set(0);
        pairFbJavaNs.set(0);
        pairJavaLen.set(0);
        pairNativeLen.set(0);
        java.util.Arrays.fill(bucketCalls, 0L);
        java.util.Arrays.fill(bucketJavaNs, 0L);
        java.util.Arrays.fill(bucketNativeNs, 0L);
        java.util.Arrays.fill(bucketTake, 0L);
        java.util.Arrays.fill(bucketFallback, 0L);
        java.util.Arrays.fill(bucketGated, 0L);
        java.util.Arrays.fill(bucketLenSum, 0L);
        // P1-CROSS：镜像账本的窗口起点（pushes / reuse / crossTick / invalidations）
        snapshotMirrorCounters();
    }

    /**
     * **逐桶账本**（每个距离桶一行；分流阈值就是从这里选的）。
     *
     * <p>行形如 {@code AIDISTBUCKET b=2 le=4 calls=.. javaNs=.. nativeNs=.. take=.. fb=.. gate=.. lenSum=..}
     * （{@code le=-1} = 最后一桶 = "大于最大边界"）。两腿各跑一次后，任意阈值 T 的净收益都能算：
     * {@code Σ_{b≥T桶} (javaNs_off − nativeNs_on − javaNs_on)}。
     */
    public List<String> aiBucketReport() {
        List<String> out = new ArrayList<>();
        for (int b = 0; b < bucketCalls.length; b++) {
            if (bucketCalls[b] == 0L) {
                continue;
            }
            out.add("AIDISTBUCKET b=" + b
                    + " le=" + bucketEdge(b)
                    + " calls=" + bucketCalls[b]
                    + " javaNs=" + bucketJavaNs[b]
                    + " nativeNs=" + bucketNativeNs[b]
                    + " take=" + bucketTake[b]
                    + " fb=" + bucketFallback[b]
                    + " gate=" + bucketGated[b]
                    + " lenSum=" + bucketLenSum[b]);
        }
        return out;
    }

    /** 被分流挡下的调用数。 */
    public long gatedCalls() {
        return gatedShort.get();
    }

    /** 走 Java 的调用数（含回退）。 */
    public long javaCalls() {
        return javaCalls.get();
    }

    /** 原生一侧累计挂钟（ns）。 */
    public long nativeWallNanos() {
        return nativeWallNanos.get();
    }

    /** Java 逻辑累计挂钟（ns）。 */
    public long javaWallNanos() {
        return javaWallNanos.get();
    }

    // ------------------------------------------------------------------
    // 回退归因诊断（-Dcava.pathfind.fallback.compare=true）
    // ------------------------------------------------------------------

    private final AtomicLong fbCompare = new AtomicLong();
    private final AtomicLong fbSame = new AtomicLong();
    private final AtomicLong fbDiff = new AtomicLong();
    private final AtomicLong fbNull = new AtomicLong();
    private final AtomicLong fbSameLen = new AtomicLong();

    /** 原生节点序列的指纹（坐标 + type 序号），与 Java {@link Path} 的算法**完全一样**才对得上。 */
    private static long sigOfNodes(List<NativeNodeCodec.Node> nodes) {
        long h = cava.parity.Fnv1a.begin();
        for (NativeNodeCodec.Node n : nodes) {
            h = cava.parity.Fnv1a.updateInt(h, n.x());
            h = cava.parity.Fnv1a.updateInt(h, n.y());
            h = cava.parity.Fnv1a.updateInt(h, n.z());
            h = cava.parity.Fnv1a.updateInt(h, n.typeOrdinal());
        }
        return h;
    }

    /** Java 路径的指纹（同一算法）。 */
    private static long sigOfPath(Path path) {
        long h = cava.parity.Fnv1a.begin();
        int len = path.getLength();
        for (int i = 0; i < len; i++) {
            net.minecraft.entity.ai.pathing.PathNode n = path.getNode(i);
            h = cava.parity.Fnv1a.updateInt(h, n.x);
            h = cava.parity.Fnv1a.updateInt(h, n.y);
            h = cava.parity.Fnv1a.updateInt(h, n.z);
            h = cava.parity.Fnv1a.updateInt(h, n.type == null ? -1 : n.type.ordinal());
        }
        return h;
    }

    /**
     * 回退归因一行（只看被回退掉的那些调用）：**原生结果与 Java 结果是不是同一条路径**。
     * "同一条" ⇒ 这次回退是纯浪费（原生本来就能给对答案）；"不同" ⇒ 回退买到了不同的结果
     * （可能是正确性必需，也可能不是——那要另做逐节点 diff）。
     */
    public String fallbackCompareReport() {
        return "fbCmp=" + fbCompare.get()
                + " fbSame=" + fbSame.get()
                + " fbSameLen=" + fbSameLen.get()
                + " fbDiff=" + fbDiff.get()
                + " fbNull=" + fbNull.get();
    }

    // ------------------------------------------------------------------
    // 失败与统计
    // ------------------------------------------------------------------

    private void onError(String what, Throwable t) {
        long n = errors.incrementAndGet();
        long c = consecutiveErrors.incrementAndGet();
        LOG.error("[cava/pathfind] {}（第 {} 次，已回退原逻辑）", what, n, t);
        if (c >= MAX_CONSECUTIVE_ERRORS) {
            disable(what + " 连续 " + c + " 次，禁用该子系统");
            LOG.error("[cava/pathfind] 连续失败达到上限，**禁用 pathfind 子系统**并回退纯 Java（不是崩溃）");
        }
    }

    /** 记录一次回退原因（线程安全，惰性建桶）。 */
    public void count(String reason) {
        reasons.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    /** 禁用这个子系统（契约 §3：失败回退 = 置为禁用并记录，**不是崩溃**）。 */
    public void disable(String reason) {
        disabled = true;
        disabledReason = reason == null ? "(未给原因)" : reason;
        // 同步给注册在 SubsystemRegistry 里的 pathfind 子系统，让启动报告/契约 §3 的状态一致
        try {
            SubsystemRegistry.get().byId("pathfind").ifPresent(s -> s.disable(disabledReason));
        } catch (Throwable t) {
            LOG.warn("[cava/pathfind] 同步禁用状态到 SubsystemRegistry 失败（忽略）", t);
        }
    }

    public boolean disabled() {
        return disabled;
    }

    public String disabledReason() {
        return disabledReason;
    }

    public long takeovers() {
        return takeovers.get();
    }

    public long nativeCalls() {
        return nativeCalls.get();
    }

    public long errors() {
        return errors.get();
    }

    /** 一行统计（金丝雀报告 / /cava 命令）。 */
    public String stats() {
        return "canary=" + canaryCount()
                + " takeovers=" + takeovers.get()
                + " nativeCalls=" + nativeCalls.get()
                + " gated=" + gatedShort.get()
                + " errors=" + errors.get()
                + " disabled=" + disabled
                + " " + fallbackReport()
                // R5 的可观测性待办（P1-CROSS 落地）：RegionMirror.report() 在此之前没有调用点，
                // /cava pathfind stats 里看不到镜像账本 —— 打开跨 tick 复用必须看得见这三个数。
                + " " + mirrorReport()
                + " reasons=" + reasonSummary();
    }

    public String reasonSummary() {
        if (reasons.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        reasons.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue().get()).append(' '));
        sb.setCharAt(sb.length() - 1, '}');
        return sb.toString();
    }

    public long uploadNanos() {
        return uploadNanos.get();
    }

    public long solveNanos() {
        return solveNanos.get();
    }

    /** 被窗口截断检测判回退的次数。 */
    public long windowFallbacks() {
        return windowFallbacks.get();
    }

    /** 某条判据的命中次数（含未回退的 {@code not-reached-structural}）。 */
    public long guardHits(String reason) {
        AtomicLong v = guardHits.get(reason);
        return v == null ? 0L : v.get();
    }

    /**
     * 回退率一行（分母 = 原生真的被调用过的次数）：
     * {@code nativeCalls=..., takeovers=..., fallbacks=... (x%), 判据分布={...}}。
     * 没有它就看不出"收益还剩多少"。
     */
    public String fallbackReport() {
        long calls = nativeCalls.get();
        long fb = windowFallbacks.get();
        double pct = calls == 0 ? 0.0 : 100.0 * fb / calls;
        StringBuilder sb = new StringBuilder();
        sb.append("nativeCalls=").append(calls)
                .append(",takeovers=").append(takeovers.get())
                .append(",fallbacks=").append(fb)
                .append(String.format(java.util.Locale.ROOT, " (%.2f%%)", pct))
                .append(",judge={");
        boolean first = true;
        for (String k : new String[] {WindowTruncationGuard.REASON_SHELL,
                WindowTruncationGuard.REASON_GOAL_SHELL,
                WindowTruncationGuard.REASON_NOT_REACHED,
                WindowTruncationGuard.REASON_NOT_REACHED_EARLY,
                WindowTruncationGuard.REASON_NOT_REACHED_STRUCTURAL}) {
            long v = guardHits(k);
            if (!first) {
                sb.append(' ');
            }
            sb.append(k).append('=').append(v);
            first = false;
        }
        sb.append('}');
        return sb.toString();
    }

    /** 测试用：清掉统计与禁用状态。 */
    public void resetForTest() {
        reasons.clear();
        takeovers.set(0);
        nativeCalls.set(0);
        errors.set(0);
        consecutiveErrors.set(0);
        uploadNanos.set(0);
        solveNanos.set(0);
        windowFallbacks.set(0);
        guardHits.clear();
        gatedShort.set(0);
        javaCalls.set(0);
        javaWallNanos.set(0);
        nativeWallNanos.set(0);
        unaccounted.set(0);
        offThreadCalls.set(0);
        distStats.reset();
        javaLenStats.reset();
        nativeLenStats.reset();
        takeoverLenStats.reset();
        fallbackLenStats.reset();
        javaNsStats.reset();
        nativeNsStats.reset();
        canary.reset();
        disabled = false;
        disabledReason = "";
    }
}
