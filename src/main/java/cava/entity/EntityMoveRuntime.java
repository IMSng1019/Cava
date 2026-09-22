package cava.entity;

import cava.ffm.CavaNative;
import cava.mixin.entity.McMoveAccess;
import cava.shape.MoveShapeBatch;
import cava.shape.ShapeTable;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
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
 *   <li>{@code live}：原生位移**真的被采用**（cancel 掉原版 {@code move}，由
 *       {@link MoveReplayDriver} 按 18 步顺序回放）。默认不启用。</li>
 * </ul>
 *
 * <h2>「原生结果 = 原版结果」是可证明的</h2>
 * 见 {@link NativeMoveSolver} 的类注释：恒传 {@code step_height = 0} 拿第 0 趟结果，
 * 再用原版自己的判据预测台阶分支是否会被进入；会进就**不用**原生结果，改调原版私有方法
 * （拿到的是与纯 Java **逐位相同**的值，不是近似回退）。
 *
 * <h2>回退</h2>
 * 原生关闭 / 未 OPEN / 任何错误码 / {@code event_overflow} / 形状采集失败 ⇒ 一律回退，
 * 并且**不报 ERROR 级噪声**（每类只 INFO 一次）。真实失败计数看 {@link #report()}。
 */
public final class EntityMoveRuntime {

    /** 模式系统属性。 */
    public static final String PROP_MODE = "cava.entity.move";

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
    /** 真实 A/B 计时：**同一次调用、同一份输入**上跑两条路径（影子模式天然给出这个对照）。 */
    private static final AtomicLong BATCH_NANOS = new AtomicLong();
    private static final AtomicLong NATIVE_NANOS = new AtomicLong();
    private static final AtomicLong VANILLA_NANOS = new AtomicLong();
    private static final AtomicLong TIMED_CALLS = new AtomicLong();

    private static volatile Mode mode;
    private static boolean loggedFallback;
    private static boolean loggedLiveUnavailable;
    private static String lastMismatch = "";
    private static final MoveShapeBatch BATCH = new MoveShapeBatch();
    private static final MoveEventLog LOG_SINK = new MoveEventLog(NativeMoveSolver.DEFAULT_EVENT_CAP);

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
     * @return true = 调用方必须 {@code ci.cancel()}（原版 {@code move} 整段不执行）
     */
    public static boolean onMoveHead(Entity self, MovementType type, Vec3d movement) {
        Mode m = mode();
        if (m == Mode.OFF || m == Mode.UNKNOWN) {
            return false;
        }
        try {
            return m == Mode.SHADOW ? shadow(self, movement) : live(self, type, movement);
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
    // live：原生位移真的被采用
    // ------------------------------------------------------------------

    /**
     * {@code live} 模式：**本轮未交付**（原因见 {@link #LIVE_NOT_SHIPPED_REASON}）。
     *
     * <p>这里**故意不 cancel**：一个"半对"的 {@code move} 替代实现比不实现更危险 ——
     * 它会静默改变整服行为，而且 {@code EntityMoveRuntime} 的影子模式抓不到它。
     * 所以 live 在本轮是**显式拒绝 + 一行 INFO**，而不是"看起来能跑的近似实现"。
     */
    private static boolean live(Entity self, MovementType type, Vec3d movement) {
        if (!loggedLiveUnavailable) {
            loggedLiveUnavailable = true;
            LOG.info("[cava/entity] {}（继续走原版 move；影子模式仍可用：-D{}=shadow）",
                    LIVE_NOT_SHIPPED_REASON, PROP_MODE);
        }
        return false;
    }

    /**
     * 本轮**不交付** live 接管的原因（实测得出的结论，不是"没时间"）。
     *
     * <p>{@code Entity.move} 里有三处 Java 侧输入**只有在回放过程中、前面某一步改过实体状态之后**才存在，
     * 而 {@link MoveInputs} 是一个**一次性建好**的 record：
     * <ol>
     *   <li>{@code getLandingPos()}（偏移 416）在 {@code setOnGround}（412）**之后**读；
     *       {@code setOnGround} → {@code updateSupportingBlockPos} → {@code findSupportingBlockPos}
     *       会改 {@code supportingBlockPos}，而 {@code getLandingPos()} 依赖它；</li>
     *   <li>{@code getSteppingPos()}（偏移 626）依赖同一批状态，且它在 {@code moveEffect} 分支**内部**；</li>
     *   <li>{@code world.isRegionLoaded(...)}（{@code checkBlockCollision}）扫的是
     *       {@code setPosition}（218）**之后**的碰撞箱；{@code stepSoundBranch} 依赖
     *       {@code distanceTraveled}（675–705）——那是回放中途才被写的。</li>
     * </ol>
     * 这三条合起来意味着：{@code EventReplay} 的「先把 {@link MoveInputs} 建好、再一次性回放」模型
     * **不可能**驱动一次真实的 {@code move}。真实接管需要把回放改成**拉取式**
     * （回调在正确的时刻回实体取值，而不是预先打包），那是 {@code EventReplay} 的一次重新设计，
     * 会动到它现有的 34 个用例。
     *
     * <p>本轮的准确结论：**ABI → Java 的链路（形状采集 / 上传 / 原生求解 / 字节级对拍）已经全部接通
     * 并在真实服务端验证**；缺的是 {@code move} 尾段（18 步里 Java 侧的 4 段内部记账 + 3 类
     * 非几何事件）的替代实现。见 {@code docs/CAVA-p2-wiring-notes.md}。
     */
    public static final String LIVE_NOT_SHIPPED_REASON =
            "live 接管本轮未交付：EventReplay 的 MoveInputs 是预打包模型，无法表达 move 里"
                    + "「setOnGround 之后才有的 landingPos」「moveEffect 分支内才有的 steppingPos」"
                    + "「bookkeeping 之后才有的 stepSoundBranch」三处时序依赖";

    // ------------------------------------------------------------------
    // 共同部分
    // ------------------------------------------------------------------

    private static boolean preconditions(Entity self) {
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
                + timingReport()
                + (lastMismatch.isEmpty() ? "" : " firstMismatch={" + lastMismatch + "}");
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

    /** 计数快照（单测/终局报告用）。 */
    public record Snapshot(long nativeCalls, long nativeOk, long fallback, long errors, long overflow,
                           long stepBranchSkipped, long stateRefs, long inlineRefs,
                           long shadowCompared, long shadowAgree, long shadowMismatch) {
    }

    public static Snapshot snapshot() {
        return new Snapshot(NATIVE_CALLS.get(), NATIVE_OK.get(), FALLBACK.get(), ERRORS.get(), OVERFLOW.get(),
                STEP_BRANCH_SKIPPED.get(), STATE_REFS.get(), INLINE_REFS.get(),
                SHADOW_COMPARED.get(), SHADOW_AGREE.get(), SHADOW_MISMATCH.get());
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
        lastMismatch = "";
        loggedFallback = false;
        resetModeCache();
    }
}
