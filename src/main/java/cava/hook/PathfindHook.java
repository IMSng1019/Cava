package cava.hook;

import cava.canary.HookCanary;
import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import cava.mirror.RegionSource;
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

    /** public（Set）重载进入时调用：标记"一次逻辑调用开始"。 */
    public void enterOuterCall() {
        logicalCall.set(Boolean.TRUE);
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
        if (!PathfindProfileBridge.mirrorProvidesProfile()) {
            out.add("镜像流未提供生物档案服务，将使用注入流自带的档案（" + PathfindProfileBridge.describe() + "）");
        }
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
        // 档案归属：**优先镜像流**（captain 冻结接口的语义），只有它不可用时才由注入流自己造。
        // 两条路互斥 ⇒ 不会出现"两边各推一份档案"。
        Long mirrorKey = world instanceof net.minecraft.server.world.ServerWorld sw
                ? PathfindProfileBridge.defineViaMirror(mob, sw) : null;
        MobProfileData profile = null;
        long profileKey;
        if (mirrorKey != null) {
            profileKey = mirrorKey;
        } else {
            profile = MobInputs.build(mob, maker);
            profileKey = MobInputs.profileKey(profile, kind);
        }
        if (!mirror.isProfileReadyForSolve(profileKey)) {
            if (!PathfindSwitches.bypassProfileGate()) {
                count("profile-not-ready");
                return null;
            }
            if (profileGateBypassWarned.compareAndSet(false, true)) {
                LOG.error("[cava/pathfind] ⚠ 诊断开关 -D{}=true 生效：**跳过了镜像流的 profile 就绪门禁**。"
                        + "这只有在 cava_pathfind 保守回退（CAVA_ERR_UNIMPLEMENTED）时才安全；"
                        + "位号对齐后必须关掉。", PathfindSwitches.PROP_BYPASS_PROFILE_GATE);
            }
            count("profile-gate-bypassed");
        }
        // (4) 区域窗口（保守策略，见 RegionWindow）
        RegionWindow.Window window = RegionWindow.compute(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ(),
                maxRange, world.getBottomY(), world.getTopY(), PathfindSwitches.maxRegionBlocks());
        if (window == null) {
            count("window-rejected");
            return null;
        }
        // (5) 原版节点预算（oracle spec 4.1）
        int budget = (int) ((float) range * followRange);
        if (budget <= 0) {
            count("budget-nonpositive");
            return null;
        }
        int cap = Math.min(budget, MAX_CAP);

        nativeLock.lock();
        try {
            long t0 = System.nanoTime();
            try {
                mirror.push(window.minX(), window.minY(), window.minZ(),
                        window.dimX(), window.dimY(), window.dimZ());
            } catch (RegionSource.MirrorUnavailableException e) {
                count("mirror-unavailable");
                return null;
            }
            if (!mirror.uploadProfileForSolve(handle, profileKey)) {
                count("profile-upload-refused");
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                if (profile != null) {
                    // 退化路径：镜像流没有档案服务，由注入流上传（**每次求解前重推**，captain 裁决）
                    MemorySegment profSeg = arena.allocate(CavaLayouts.MOB_PROFILE);
                    profile.writeTo(profSeg, 0);
                    int prc = nat.mobProfileUpload(handle, profSeg);
                    if (prc != CavaLayouts.CAVA_OK) {
                        count("profile-upload-" + CavaLayouts.errorName(prc));
                        return null;
                    }
                }
                MemorySegment req = arena.allocate(CavaLayouts.PATH_REQUEST);
                fillRequest(req, target, reachRange, maxRange, budget);
                MemorySegment out = CavaNative.allocateArray(arena, CavaLayouts.PATH_NODE, cap);
                long t1 = System.nanoTime();
                uploadNanos.addAndGet(t1 - t0);
                int rc = nat.pathfind(handle, req, out, cap);
                solveNanos.addAndGet(System.nanoTime() - t1);
                nativeCalls.incrementAndGet();
                if (PathfindOutcome.classify(rc, cap) != PathfindOutcome.Kind.TAKE_OVER) {
                    count(rc == CavaLayouts.CAVA_ERR_UNIMPLEMENTED ? "native-unimplemented"
                            : "native-" + CavaLayouts.errorName(rc));
                    return null;
                }
                Path path = NativePathBuilder.toPath(NativeNodeCodec.decode(out, rc), target, reachRange);
                if (path == null) {
                    count("decode-rejected");
                    return null;
                }
                takeovers.incrementAndGet();
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
                + " errors=" + errors.get()
                + " disabled=" + disabled
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

    /** 测试用：清掉统计与禁用状态。 */
    public void resetForTest() {
        reasons.clear();
        takeovers.set(0);
        nativeCalls.set(0);
        errors.set(0);
        consecutiveErrors.set(0);
        uploadNanos.set(0);
        solveNanos.set(0);
        canary.reset();
        disabled = false;
        disabledReason = "";
    }
}
