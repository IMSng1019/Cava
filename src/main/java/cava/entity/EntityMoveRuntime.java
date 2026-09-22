package cava.entity;

import cava.ffm.CavaNative;
import cava.mixin.entity.McMoveAccess;
import cava.shape.MoveShapeBatch;
import cava.shape.ShapeTable;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 实体位移的**运行时驱动**：由 {@code EntityMoveMixin} 在 {@code Entity.move} 的 HEAD 调用。
 *
 * <h2>三种模式（系统属性 {@code cava.entity.move}，**默认 off**）</h2>
 * <ul>
 *   <li>{@code off}（默认）：什么都不做，连形状表都不建。<b>回退默认安全</b>。</li>
 *   <li>{@code shadow}：把原生链路**完整跑一遍**（建形状表 → 组 refs → 调 {@code cava_resolve_move}），
 *       把原生位移与原版私有的 {@code adjustMovementForCollisions(Vec3d)} **逐位**比对并计数，
 *       然后**照常让原版跑**（不 cancel）。**行为风险 = 0**，是真实服务端上最硬的端到端证据。</li>
 *   <li>{@code live}：HEAD 处调原生求解 → 用 {@link EventReplay} 按 <b>18+1 步顺序拉取式回放</b>
 *       执行完整段 {@code move} → {@code ci.cancel()}。原版 {@code move} 的方法体**一次都不执行**。</li>
 * </ul>
 *
 * <h2>live 的接管条件与 fail-closed 边界（要读清楚）</h2>
 * <ol>
 *   <li><b>零位移永不接管</b>：{@code movement.equals(Vec3d.ZERO)}（逐位 {@code Double.compare}，
 *       所以 {@code -0.0} 不算零）直接让原版跑。这样我们的 cancel 集合与 VMP 的
 *       {@code move_zero_velocity} cancel 集合<b>恒不相交</b> —— 与两个 HEAD 注入的<b>顺序无关</b>，
 *       不需要（也不可能）去猜 Mixin 的 priority 语义。VMP 的黏滞状态机仍然被复刻并逐次询问
 *       （{@link VmpZeroVelocityGate}，计数可见），但<b>它的返回值从不驱动 cancel</b>。</li>
 *   <li>{@code noClip}（偏移 4–38）与 {@code MovementType.PISTON}（47–70）两个提前返回分支
 *       <b>留给原版</b>（{@link MoveStep} 类注释里的同一条约定）。</li>
 *   <li>原生不可用 / 形状表不可用 / 任何错误码 / {@code event_overflow} / 形状 token 越界 /
 *       台阶分支守卫命中 ⇒ {@code solveNative} 返回 {@code null} ⇒ <b>不 cancel</b>，原版照跑，
 *       且**不刷 ERROR 级噪声**（每类只 INFO 一次）。</li>
 *   <li>回放开始<b>之后</b>若 {@code strictOk()} 为假：结构上已经不可能回滚（副作用已发生），
 *       所以记 {@link #report()} 的 {@code liveNonStrict} 并 INFO 一次。为避免它发生，
 *       三个会产生事件的分支（射线 / 逐格扫描 / 火焰盒）都由 Java 侧在**同一分支内**注入事件
 *       （{@link MoveInputSource#supplyEventsAt}），注入条数与消费条数当场对账
 *       （"回执校验"）—— 于是 {@code unconsumed}/{@code anomalies} 在 live 下按构造为空。</li>
 *   <li>可重入（回放途中又发生一次 {@code move}）⇒ 内层让原版跑（{@code liveReentrant}）。</li>
 * </ol>
 *
 * <h2>「原生结果 = 原版结果」是可证明的</h2>
 * 见 {@link NativeMoveSolver} 的类注释：恒传 {@code step_height = 0} 拿第 0 趟结果，
 * 再用原版自己的判据预测台阶分支是否会被进入；会进就**不用**原生结果，改调原版私有方法
 * （拿到的是与纯 Java **逐位相同**的值，不是近似回退）。
 *
 * <h2>性能采样规范</h2>
 * {@link #timingReport()} 报的是**真实调用的累计均值 + 样本量 + 分桶趋势**
 * （{@code vanillaMove}/{@code liveMove} 各自的首桶/末桶均值），
 * 不用"三次采样"，也**不把预热瞬态藏进均值**。本机实测值写在
 * {@code docs/CAVA-p2-live-notes.md}。
 */
public final class EntityMoveRuntime {

    /** 模式系统属性。 */
    public static final String PROP_MODE = "cava.entity.move";

    /**
     * <b>破坏性诊断金丝雀</b>：{@code -Dcava.entity.move.canary=skip-setposition} 时，
     * live 接管路径**不调用** {@code Entity.setPosition}（其余步骤照旧）。
     *
     * <p>用途：证明"实体的位置真的是这段回放写下去的"，而不是"计数器动了但原版还在跑"。
     * 可证伪的形态：开了金丝雀之后实体必须**停在出生点**（回放不再写位置）；
     * 若 {@code ci.cancel()} 没有生效，原版 {@code move} 会紧接着把实体搬走，dump 就不会等于出生点。
     * 与"只让计数器 +1"相比，这条证据直接作用在世界状态上。
     *
     * <p>默认 {@code off}；生产绝不允许打开 —— 它会真的改变世界。
     */
    public static final String PROP_CANARY = "cava.entity.move.canary";

    /** 金丝雀取值：跳过 {@code setPosition}。 */
    public static final String CANARY_SKIP_SETPOSITION = "skip-setposition";

    /**
     * <b>live 自证模式</b>：{@code -Dcava.entity.move.verify=true} 时，每次 live 接管都会**再调一次**
     * 原版私有的 {@code adjustMovementForCollisions(Vec3d)}（纯读、无副作用），把
     * "本次实际采用的位移"与"原版会算出的位移"<b>在同一次调用、同一份输入上逐位比对</b>。
     *
     * <p>这是"live 与 shadow 差异为零"能拿到的**最强形式**：shadow 是"算但不接管"，
     * 这里是"接管了的那一个值 == 原版值"，同 call 对照，不受跨进程/跨 tick 的非确定性影响
     * （本机实测：连纯原版的两次运行，实体位置 dump 都有 10–16 行不同 —— 跨 run 比 dump 不成立）。
     *
     * <p>代价：每次接管多一次原版求解（本机约 450ns）。默认关。
     */
    public static final String PROP_VERIFY = "cava.entity.move.verify";

    /** 分桶宽度（性能趋势：每 20000 次真实调用打一行，暴露预热瞬态）。 */
    public static final long BUCKET = 20000;

    /** 运行模式。 */
    public enum Mode {
        OFF, SHADOW, LIVE, UNKNOWN
    }

    private static final Logger LOG = LoggerFactory.getLogger("cava/entity");

    private static final AtomicLong NATIVE_CALLS = new AtomicLong();
    private static final AtomicLong NATIVE_OK = new AtomicLong();
    private static final AtomicLong FALLBACK = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong OVERFLOW = new AtomicLong();
    private static final AtomicLong STEP_BRANCH_SKIPPED = new AtomicLong();
    private static final AtomicLong SHADOW_COMPARED = new AtomicLong();
    private static final AtomicLong SHADOW_AGREE = new AtomicLong();
    private static final AtomicLong SHADOW_MISMATCH = new AtomicLong();
    private static final AtomicLong STATE_REFS = new AtomicLong();
    private static final AtomicLong INLINE_REFS = new AtomicLong();
    private static final AtomicLong TOKEN_OUT_OF_RANGE = new AtomicLong();

    // ---- live 专用计数 ----
    private static final AtomicLong LIVE_TAKEOVERS = new AtomicLong();
    private static final AtomicLong LIVE_NON_STRICT = new AtomicLong();
    private static final AtomicLong LIVE_PULLS = new AtomicLong();
    private static final AtomicLong LIVE_DECLINED_ZERO = new AtomicLong();
    private static final AtomicLong LIVE_DECLINED_NOCLIP = new AtomicLong();
    private static final AtomicLong LIVE_DECLINED_PISTON = new AtomicLong();
    private static final AtomicLong LIVE_DECLINED_REENTRANT = new AtomicLong();
    private static final AtomicLong LIVE_VERIFIED = new AtomicLong();
    private static final AtomicLong LIVE_VERIFY_MISMATCH = new AtomicLong();
    private static final AtomicLong VMP_GATE_QUERIES = new AtomicLong();
    private static final AtomicLong VMP_GATE_WOULD_CANCEL = new AtomicLong();

    /** 真实 A/B 计时：**同一次调用、同一份输入**上跑两条路径（影子模式天然给出这个对照）。 */
    private static final AtomicLong BATCH_NANOS = new AtomicLong();
    private static final AtomicLong NATIVE_NANOS = new AtomicLong();
    private static final AtomicLong VANILLA_NANOS = new AtomicLong();
    private static final AtomicLong TIMED_CALLS = new AtomicLong();

    /** 整段 {@code Entity.move} 的真实计时（HEAD→RETURN / HEAD→cancel），带分桶趋势。 */
    private static final Timing VANILLA_MOVE = new Timing("vanillaMove");
    private static final Timing LIVE_MOVE = new Timing("liveMove");

    /** 计时用的自愈式栈顶（**不**做嵌套栈：嵌套时内层覆盖外层，外层不记样本 —— 见类注释）。 */
    private static long timingStart;
    private static boolean timingArmed;

    private static volatile Mode mode;
    private static boolean loggedFallback;
    private static boolean loggedNonStrict;
    private static volatile boolean shutdownHookRegistered;
    private static volatile String canaryMode;
    private static String lastMismatch = "";
    private static final MoveShapeBatch BATCH = new MoveShapeBatch();
    private static final MoveEventLog LOG_SINK = new MoveEventLog(NativeMoveSolver.DEFAULT_EVENT_CAP);

    /** 回放会话的深度（>0 = 正在接管中；内层的 move 让原版跑）。 */
    private static int liveDepth;

    /** live 自证开关（启动时读一次，见 {@link #PROP_VERIFY}）。 */
    private static final boolean verifyEnabled =
            Boolean.parseBoolean(System.getProperty(PROP_VERIFY, "false").trim());

    private EntityMoveRuntime() {
    }

    /** 当前模式（系统属性 → 缓存）。 */
    public static Mode mode() {
        Mode m = mode;
        if (m == null) {
            m = parseMode(System.getProperty(PROP_MODE, "off"));
            mode = m;
        }
        return m;
    }

    static Mode parseMode(String raw) {
        if (raw == null) {
            return Mode.OFF;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "shadow", "dry", "compare" -> Mode.SHADOW;
            case "live", "takeover", "on" -> Mode.LIVE;
            case "off", "false", "0", "" -> Mode.OFF;
            default -> Mode.UNKNOWN;
        };
    }

    /** 供单测重置模式缓存。 */
    static void resetModeCache() {
        mode = null;
    }

    /**
     * {@code Entity.move} 的 HEAD。
     *
     * @param gate 该实体自己的 VMP 黏滞门（VMP 不在类路径上时为 {@code null}，此时**完全不碰**它）
     * @return true = 调用方必须 {@code ci.cancel()}（原版 {@code move} 整段不执行）
     */
    public static boolean onMoveHead(Entity self, MovementType type, Vec3d movement, VmpZeroVelocityGate gate) {
        Mode m = mode();
        if (m == Mode.OFF || m == Mode.UNKNOWN) {
            return false;
        }
        try {
            return m == Mode.SHADOW ? shadow(self, movement) : live(self, type, movement, gate);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            if (!loggedFallback) {
                loggedFallback = true;
                LOG.info("[cava/entity] 实体位移原生路径异常，已回退原版（同类只报一次）: {}", t.toString());
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // shadow：跑完整链路 + 逐位比对，然后让原版照跑
    // ------------------------------------------------------------------

    private static boolean shadow(Entity self, Vec3d movement) {
        if (!preconditions(self)) {
            return false;
        }
        Vec3d nativeDelta = solveNative(self, movement);
        if (nativeDelta == null) {
            return false;
        }
        long t3 = System.nanoTime();
        Vec3d vanilla = ((McMoveAccess) (Object) self).cava$adjustMovementForCollisions(movement);
        VANILLA_NANOS.addAndGet(System.nanoTime() - t3);
        TIMED_CALLS.incrementAndGet();
        SHADOW_COMPARED.incrementAndGet();
        if (sameBits(nativeDelta, vanilla)) {
            SHADOW_AGREE.incrementAndGet();
        } else {
            SHADOW_MISMATCH.incrementAndGet();
            if (lastMismatch.isEmpty()) {
                lastMismatch = "movement=" + movement + " native=" + nativeDelta + " vanilla=" + vanilla;
                LOG.info("[cava/entity] 影子比对不一致（首条）: {}", lastMismatch);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // live：原生位移真的被采用，整段 move 由拉取式回放执行
    // ------------------------------------------------------------------

    /**
     * {@code live} 模式：见类注释的"接管条件与 fail-closed 边界"。
     *
     * @return true = 本次 {@code move} 已由回放整段执行，调用方必须 cancel
     */
    private static boolean live(Entity self, MovementType type, Vec3d movement, VmpZeroVelocityGate gate) {
        // (1) 零位移：VMP 的领地，永不接管（逐位判定，-0.0 不算零）。
        if (movement.equals(Vec3d.ZERO)) {
            LIVE_DECLINED_ZERO.incrementAndGet();
            if (gate != null) {
                VMP_GATE_QUERIES.incrementAndGet();
                if (gate.onMoveHead(movement)) {
                    VMP_GATE_WOULD_CANCEL.incrementAndGet();
                }
            }
            return false;
        }
        // (2) 两个提前返回分支留给原版。
        if (self.noClip) {
            LIVE_DECLINED_NOCLIP.incrementAndGet();
            return false;
        }
        if (type == MovementType.PISTON) {
            LIVE_DECLINED_PISTON.incrementAndGet();
            return false;
        }
        // (3) 可重入：内层让原版跑（外层回放里的回调触发的 move）。
        if (liveDepth != 0) {
            LIVE_DECLINED_REENTRANT.incrementAndGet();
            return false;
        }
        // (4) 前置条件（原生可用 + 形状表已上传）；不满足时 preconditions 自己计数/记日志。
        if (!preconditions(self)) {
            return false;
        }

        McMoveAccess access = (McMoveAccess) (Object) self;
        // 偏移 39–44：wasOnFire 在 HEAD 捕获（局部 6 后面还要用），字段也照原版写一次。
        boolean wasOnFire = self.isOnFire();
        self.wasOnFire = wasOnFire;
        Profiler profiler = self.getWorld().getProfiler();
        profiler.push("move");                                    // 偏移 71–81

        // 偏移 86–122：movementMultiplier。**幂等**，所以在这里先做、之后回退也不会双重生效。
        Vec3d m = movement;
        Vec3d multiplier = access.cava$movementMultiplier();
        if (multiplier.lengthSquared() > 1.0E-7) {
            m = m.multiply(multiplier);
            access.cava$setMovementMultiplier(Vec3d.ZERO);
            self.setVelocity(Vec3d.ZERO);
        }
        // 偏移 123–129：adjustMovementForSneaking 是**虚方法**，必须调它本身（可能有子类覆写）。
        m = access.cava$adjustMovementForSneaking(m, type);

        Vec3d delta = solveNative(self, m);                        // 偏移 130–135
        if (delta == null) {
            profiler.pop();                                        // 回退：把刚 push 的 'move' 弹掉
            return false;
        }

        if (verifyEnabled) {
            // live 自证：同一个 movement / 同一只实体上，原版私有方法会算出什么？
            Vec3d vanilla = access.cava$adjustMovementForCollisions(m);
            LIVE_VERIFIED.incrementAndGet();
            if (!sameBits(delta, vanilla)) {
                LIVE_VERIFY_MISMATCH.incrementAndGet();
                if (lastMismatch.isEmpty()) {
                    lastMismatch = "[live-verify] movement=" + m + " live=" + delta + " vanilla=" + vanilla;
                    LOG.info("[cava/entity] live 自证不一致（首条）: {}", lastMismatch);
                }
            }
        }
        LiveMoveSession session = new LiveMoveSession(self, m, delta, wasOnFire, canarySkipSetPosition());
        // trace=false：生产热路径不建诊断流水（实测 15–18µs/次 -> 见 EventReplay 类注释）。
        // strictOk()/unconsumed/anomalies 判定一条不少，它们是纯计数、不分配。
        EventReplay<BlockState, VoxelShape> replay = new EventReplay<>(session, false);
        liveDepth++;
        EventReplay.Transcript transcript;
        try {
            transcript = replay.replay(session, LOG_SINK);
        } finally {
            liveDepth--;
        }
        LIVE_TAKEOVERS.incrementAndGet();
        LIVE_PULLS.addAndGet(transcript.pullCount());
        if (!transcript.strictOk()) {
            LIVE_NON_STRICT.incrementAndGet();
            if (!loggedNonStrict) {
                loggedNonStrict = true;
                LOG.info("[cava/entity] live 回放自检未通过（副作用已发生、无法回滚，已计数）: {}",
                        transcript.report());
            }
        }
        return true;
    }

    /** 金丝雀开关（缓存；**不**在热路径上反复读系统属性）。 */
    static boolean canarySkipSetPosition() {
        String c = canaryMode;
        if (c == null) {
            c = System.getProperty(PROP_CANARY, "off").trim().toLowerCase(java.util.Locale.ROOT);
            canaryMode = c;
            if (!"off".equals(c) && !c.isEmpty()) {
                LOG.warn("[cava/entity] 破坏性金丝雀已开启（{}={}）：live 接管不再写实体位置 —— 只允许诊断用！",
                        PROP_CANARY, c);
            }
        }
        return CANARY_SKIP_SETPOSITION.equals(c);
    }

    /**
     * VMP 黏滞门是否该被喂/被问：只有 {@code live} 模式且 {@code vmp.mixins.json}
     * 真的在类路径上时才启用（否则会引入本机服务器根本没有的行为）。
     */
    public static boolean vmpGateTracking() {
        return mode() == Mode.LIVE && VmpZeroVelocityGateHolder.available();
    }

    /**
     * 由 {@code EntityMoveMixin} 在 {@code Entity.setBoundingBox} 的 <b>HEAD、字段赋值之前</b>调用。
     * 时序约束见 {@link VmpZeroVelocityGate#onSetBoundingBoxHeadBeforeAssign}。
     */
    public static void onSetBoundingBoxHead(VmpZeroVelocityGate gate, Box boxBeforeAssign, Box incoming) {
        if (gate != null) {
            gate.onSetBoundingBoxHeadBeforeAssign(boxBeforeAssign, incoming);
        }
    }

    // ------------------------------------------------------------------
    // 整段 move 的真实计时（HEAD→RETURN / HEAD→cancel）
    // ------------------------------------------------------------------

    /** {@code Entity.move} HEAD 处调用（在所有其他工作之前）。 */
    public static void timingEnter() {
        timingStart = System.nanoTime();
        timingArmed = true;
    }

    /** {@code Entity.move} 走到任意一个 RETURN 时调用（未 cancel ⇒ 原版方法体真的执行了）。 */
    public static void timingExitVanilla() {
        if (timingArmed) {
            timingArmed = false;
            VANILLA_MOVE.add(System.nanoTime() - timingStart);
        }
    }

    /** HEAD 处决定 cancel 时调用（本次 move 的方法体一次都没执行）。 */
    public static void timingExitLive() {
        if (timingArmed) {
            timingArmed = false;
            LIVE_MOVE.add(System.nanoTime() - timingStart);
        }
    }

    /** 分桶计时器：累计均值 + 首桶/末桶均值（暴露预热瞬态，不把瞬态藏进均值）。 */
    static final class Timing {
        private final String name;
        private final AtomicLong nanos = new AtomicLong();
        private final AtomicLong count = new AtomicLong();
        private long bucketNanos;
        private long bucketCount;
        private long firstBucketMean = -1;
        private long lastBucketMean = -1;

        Timing(String name) {
            this.name = name;
        }

        void add(long delta) {
            nanos.addAndGet(delta);
            count.incrementAndGet();
            bucketNanos += delta;
            bucketCount++;
            if (bucketCount >= BUCKET) {
                long mean = bucketNanos / bucketCount;
                if (firstBucketMean < 0) {
                    firstBucketMean = mean;
                }
                lastBucketMean = mean;
                LOG.info("[cava/entity] 计时分桶 {}: 本桶均值={}ns 累计均值={}ns n={}",
                        name, mean, mean(), count.get());
                bucketNanos = 0;
                bucketCount = 0;
            }
        }

        long mean() {
            long n = count.get();
            return n == 0 ? 0 : nanos.get() / n;
        }

        long count() {
            return count.get();
        }

        String report() {
            if (count.get() == 0) {
                return name + "=n/a";
            }
            return name + "=" + mean() + "ns(n=" + count.get()
                    + ",首桶=" + (firstBucketMean < 0 ? "未满" : firstBucketMean + "ns")
                    + ",末桶=" + (lastBucketMean < 0 ? "未满" : lastBucketMean + "ns") + ")";
        }

        void reset() {
            nanos.set(0);
            count.set(0);
            bucketNanos = 0;
            bucketCount = 0;
            firstBucketMean = -1;
            lastBucketMean = -1;
        }
    }

    // ------------------------------------------------------------------
    // 共同部分
    // ------------------------------------------------------------------

    /**
     * 终局计数：进程退出时打一行完整的 {@link #report()}。
     *
     * <p>理由：热路径上的计数每 2000 次才打一行，最后一行与真实终值最多差 1999 ——
     * 报告里"接管了多少次"这种数字不能带这个尾巴。退出钩子只注册一次，代价为零。
     */
    private static void registerShutdownReport() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // **不用 logger**：logback 的 appender 可能已经随它自己的 shutdown 钩子关掉了
                    // （实测：用 LOG.info 打的那行在日志里根本没出现）。stdout 一定还在。
                    System.out.println("[cava/entity] 终局计数 " + report() + " | " + wholeMoveTimingReport());
                    System.out.flush();
                } catch (Throwable ignored) {
                    // 退出路径上不允许再抛
                }
            }, "cava-entity-final-report"));
        } catch (Throwable ignored) {
            // 已经有别的钩子/正在关闭：忽略
        }
    }

    private static boolean preconditions(Entity self) {
        registerShutdownReport();
        CavaNative nat = CavaNative.get();
        if (!nat.available()) {
            FALLBACK.incrementAndGet();
            if (!loggedFallback) {
                loggedFallback = true;
                LOG.info("[cava/entity] 原生不可用（status={}），实体位移走纯 Java", nat.status());
            }
            return false;
        }
        if (!ShapeTable.get().uploadIfNeeded()) {
            FALLBACK.incrementAndGet();
            if (!loggedFallback) {
                loggedFallback = true;
                LOG.info("[cava/entity] 形状表不可用（{}），实体位移走纯 Java", ShapeTable.get().failure());
            }
            return false;
        }
        return true;
    }

    /**
     * 组 refs + 调原生；返回 null = 不能采用原生结果（调用方回退原版）。
     *
     * <p>采用原生结果的**充要条件**（在 {@link NativeMoveSolver} 的用法下）：
     * 原版这次**不会**进台阶分支 —— 因为冻结 ABI 只有一份 refs[]，
     * 而台阶分支会用不同的 stretch 盒重跑方块查询。
     */
    static Vec3d solveNative(Entity self, Vec3d movement) {
        long t0 = System.nanoTime();
        buildBatch(self, movement);
        long t1 = System.nanoTime();
        BATCH_NANOS.addAndGet(t1 - t0);
        CavaNative nat = CavaNative.get();
        LOG_SINK.reset();
        NativeMoveSolver.Out out = NativeMoveSolver.solve(nat.handle(),
                self.getBoundingBox().minX, self.getBoundingBox().minY, self.getBoundingBox().minZ,
                self.getBoundingBox().maxX, self.getBoundingBox().maxY, self.getBoundingBox().maxZ,
                movement.x, movement.y, movement.z,
                0.0, self.isOnGround(), BATCH, NativeMoveSolver.DEFAULT_EVENT_CAP, LOG_SINK);
        long t2 = System.nanoTime();
        NATIVE_NANOS.addAndGet(t2 - t1);
        NATIVE_CALLS.incrementAndGet();
        STATE_REFS.addAndGet(BATCH.stateHits());
        INLINE_REFS.addAndGet(BATCH.inlineFallbacks());
        if (out.rc != cava.ffm.CavaLayouts.CAVA_OK) {
            ERRORS.incrementAndGet();
            if (!loggedFallback) {
                loggedFallback = true;
                LOG.info("[cava/entity] cava_resolve_move → {}，实体位移回退原版（同类只报一次）",
                        cava.ffm.CavaLayouts.errorName(out.rc));
            }
            return null;
        }
        if (out.eventOverflow != 0) {
            OVERFLOW.incrementAndGet();
            FALLBACK.incrementAndGet();
            return null;
        }
        if (!out.tokensInRange) {
            TOKEN_OUT_OF_RANGE.incrementAndGet();
            FALLBACK.incrementAndGet();
            return null;
        }
        NATIVE_OK.incrementAndGet();
        // 真实服务端的可观测证据：每 N 次原生求解打一行完整计数（不需要命令/端点）。
        long calls = NATIVE_CALLS.get();
        if (calls == 1 || calls % 2000 == 0) {
            LOG.info("[cava/entity] {} | {}", report(), BATCH.report());
        }

        Vec3d base = out.base();
        // 原版 method_17835 的三个 != 与 bl4（oracle spec §6.2 偏移 45/65/85/105/133）。
        boolean bl = movement.x != base.x;
        boolean bl2 = movement.y != base.y;
        boolean bl3 = movement.z != base.z;
        boolean bl4 = self.isOnGround() || (bl2 && movement.y < 0.0);
        if (self.getStepHeight() > 0.0F && bl4 && (bl || bl3)) {
            STEP_BRANCH_SKIPPED.incrementAndGet();
            FALLBACK.incrementAndGet();
            return null;   // 台阶分支：冻结 ABI 的单份 refs[] 表达不了，交给原版
        }
        return out.delta();
    }

    /**
     * 按**原版顺序**组形状列表：entityCollisions → worldBorder → blockCollisions
     * （{@code Entity.adjustMovementForCollisions(Entity,Vec3d,Box,World,List)} 字节码 14-94）。
     *
     * <p>注意传进去的盒子是 {@code box.stretch(movement)}（拉伸后的），不是实体当前碰撞箱。
     */
    static void buildBatch(Entity self, Vec3d movement) {
        BATCH.reset();
        World world = self.getWorld();
        Box box = self.getBoundingBox();
        Box stretched = box.stretch(movement);

        List<VoxelShape> entityCollisions = world.getEntityCollisions(self, stretched);
        if (!entityCollisions.isEmpty()) {
            for (int i = 0; i < entityCollisions.size(); i++) {
                BATCH.add(entityCollisions.get(i), MoveShapeBatch.SRC_ENTITY);
            }
        }
        WorldBorder border = world.getWorldBorder();
        if (border.canCollide(self, stretched)) {
            BATCH.add(border.asVoxelShape(), MoveShapeBatch.SRC_WORLD_BORDER);
        }
        for (VoxelShape s : world.getBlockCollisions(self, stretched)) {
            BATCH.add(s, MoveShapeBatch.SRC_BLOCK);
        }
    }

    /** 逐位相等（{@code Double.compare}，所以 {@code -0.0} 与 {@code 0.0} 不等 —— 与原版一致）。 */
    static boolean sameBits(Vec3d a, Vec3d b) {
        return Double.compare(a.x, b.x) == 0 && Double.compare(a.y, b.y) == 0 && Double.compare(a.z, b.z) == 0;
    }

    /** 上一次调用组好的形状批（live 回放要用它取 {@code shapes[token]}）。 */
    static MoveShapeBatch lastBatch() {
        return BATCH;
    }

    /** 上一次调用解出来的事件日志（live 回放要用）。 */
    static MoveEventLog lastEventLog() {
        return LOG_SINK;
    }

    /** VMP 存在性探测（live 才需要）。 */
    static final class VmpZeroVelocityGateHolder {
        private static final Boolean AVAILABLE = detect();

        private static Boolean detect() {
            try {
                ClassLoader cl = EntityMoveRuntime.class.getClassLoader();
                return cl != null && cl.getResource("vmp.mixins.json") != null;
            } catch (Throwable t) {
                return Boolean.FALSE;
            }
        }

        static boolean available() {
            return AVAILABLE;
        }
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    /** 一行的实测计数（**真实数字，报告/日志共用**）。 */
    public static String report() {
        return "mode=" + mode()
                + " nativeCalls=" + NATIVE_CALLS.get()
                + " nativeOk=" + NATIVE_OK.get()
                + " fallback=" + FALLBACK.get()
                + " errors=" + ERRORS.get()
                + " overflow=" + OVERFLOW.get()
                + " stepBranchSkipped=" + STEP_BRANCH_SKIPPED.get()
                + " tokenOutOfRange=" + TOKEN_OUT_OF_RANGE.get()
                + " stateRefs=" + STATE_REFS.get()
                + " inlineRefs=" + INLINE_REFS.get()
                + " shadowCompared=" + SHADOW_COMPARED.get()
                + " shadowAgree=" + SHADOW_AGREE.get()
                + " shadowMismatch=" + SHADOW_MISMATCH.get()
                + liveReport()
                + " | " + wholeMoveTimingReport()
                + timingReport()
                + (lastMismatch.isEmpty() ? "" : " firstMismatch={" + lastMismatch + "}");
    }

    /** live 接管计数（含"为什么没接管"的分类）。 */
    public static String liveReport() {
        return " liveTakeovers=" + LIVE_TAKEOVERS.get()
                + " liveNonStrict=" + LIVE_NON_STRICT.get()
                + " livePulls=" + LIVE_PULLS.get()
                + " liveVerified=" + LIVE_VERIFIED.get()
                + " liveVerifyMismatch=" + LIVE_VERIFY_MISMATCH.get()
                + " declinedZero=" + LIVE_DECLINED_ZERO.get()
                + " declinedNoClip=" + LIVE_DECLINED_NOCLIP.get()
                + " declinedPiston=" + LIVE_DECLINED_PISTON.get()
                + " declinedReentrant=" + LIVE_DECLINED_REENTRANT.get()
                + " vmpGateQueries=" + VMP_GATE_QUERIES.get()
                + " vmpGateWouldCancel=" + VMP_GATE_WOULD_CANCEL.get();
    }

    /**
     * 真实 A/B 计时（**同一次调用、同一份输入**）：影子模式在同一个 movement 上先跑原生、再跑原版私有方法。
     *
     * <p>报告的是**稳态均值**（累计纳秒 / 次数），不是"三次采样"；样本量写在括号里。
     * 注意这是**同线程同输入**的对照，不含 JIT 预热期的区分 —— 需要的读者请看 {@code timedCalls} 的量级。
     */
    public static String timingReport() {
        long n = TIMED_CALLS.get();
        if (n == 0) {
            return "";
        }
        return String.format(java.util.Locale.ROOT,
                " | timedCalls=%d 组refs=%.0fns 原生调用=%.0fns 原版调用=%.0fns",
                n, BATCH_NANOS.get() / (double) n, NATIVE_NANOS.get() / (double) n,
                VANILLA_NANOS.get() / (double) n);
    }

    /** 整段 move 的 A/B 计时（分桶趋势）。 */
    public static String wholeMoveTimingReport() {
        return VANILLA_MOVE.report() + " " + LIVE_MOVE.report();
    }

    /** 计数快照（单测/终局报告用）。 */
    public record Snapshot(long nativeCalls, long nativeOk, long fallback, long errors, long overflow,
                           long stepBranchSkipped, long stateRefs, long inlineRefs,
                           long shadowCompared, long shadowAgree, long shadowMismatch,
                           long liveTakeovers, long liveNonStrict) {
    }

    public static Snapshot snapshot() {
        return new Snapshot(NATIVE_CALLS.get(), NATIVE_OK.get(), FALLBACK.get(), ERRORS.get(), OVERFLOW.get(),
                STEP_BRANCH_SKIPPED.get(), STATE_REFS.get(), INLINE_REFS.get(),
                SHADOW_COMPARED.get(), SHADOW_AGREE.get(), SHADOW_MISMATCH.get(),
                LIVE_TAKEOVERS.get(), LIVE_NON_STRICT.get());
    }

    /** 供测试清零。 */
    static void resetCountersForTest() {
        NATIVE_CALLS.set(0);
        NATIVE_OK.set(0);
        FALLBACK.set(0);
        ERRORS.set(0);
        OVERFLOW.set(0);
        STEP_BRANCH_SKIPPED.set(0);
        SHADOW_COMPARED.set(0);
        SHADOW_AGREE.set(0);
        SHADOW_MISMATCH.set(0);
        STATE_REFS.set(0);
        INLINE_REFS.set(0);
        TOKEN_OUT_OF_RANGE.set(0);
        BATCH_NANOS.set(0);
        NATIVE_NANOS.set(0);
        VANILLA_NANOS.set(0);
        TIMED_CALLS.set(0);
        LIVE_TAKEOVERS.set(0);
        LIVE_NON_STRICT.set(0);
        LIVE_PULLS.set(0);
        LIVE_DECLINED_ZERO.set(0);
        LIVE_DECLINED_NOCLIP.set(0);
        LIVE_DECLINED_PISTON.set(0);
        LIVE_DECLINED_REENTRANT.set(0);
        VMP_GATE_QUERIES.set(0);
        VMP_GATE_WOULD_CANCEL.set(0);
        VANILLA_MOVE.reset();
        LIVE_MOVE.reset();
        timingArmed = false;
        liveDepth = 0;
        lastMismatch = "";
        loggedFallback = false;
        loggedNonStrict = false;
        canaryMode = null;
        resetModeCache();
    }
}
