package cava.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import cava.harden.CircuitBreaker;
import cava.harden.CallWatchdog;
import cava.harden.NativeCallGuard;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原生库 facade 单例（任务 C4）。**不引用任何 Minecraft 类型**——这个包必须能脱离 MC 单独跑
 * （见 {@link NativeSelfTest}）。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code tryOpen()} 幂等：只在第一次调用时真正做事，之后返回缓存结果。</li>
 *   <li>任何一步异常都不许逃逸（含 {@code UnsatisfiedLinkError} / {@code ExceptionInInitializerError}
 *       / {@code LinkageError}），全部转成 {@link NativeStatus} + 明确日志。</li>
 *   <li>{@code -Dcava.native.enabled=false} → {@link NativeStatus#DISABLED_BY_FLAG}：
 *       不加载、不打 ERROR（这是正常路径）。</li>
 *   <li>非 OPEN 一律 = 整体回退纯 Java。</li>
 * </ul>
 */
public final class CavaNative {

    /** 系统属性：false = 完全不加载原生库。 */
    public static final String PROP_ENABLED = "cava.native.enabled";

    /**
     * 原生不可用（{@code status != OPEN}）时，所有 P1 入口返回的统一回退信号。
     *
     * <p>取值在 ABI 的错误码区间（-1..-7）之外，Java 侧自有码从 -100 起，
     * 调用方只需判断 {@code rc < 0} 即回退原逻辑；想区分"原生没开"与"原生报错"时比这个常量。
     *
     * <p>取值定义搬到 {@link NativeCallGuard}（唯一事实来源），这里只是别名，
     * **数值一个都没变**（-100 / -101），已有调用方零改动。
     */
    public static final int ERR_NATIVE_UNAVAILABLE = NativeCallGuard.ERR_NATIVE_UNAVAILABLE;

    /** 原生调用抛异常（已记录一次日志）时的回退信号。 */
    public static final int ERR_CALL_FAILED = NativeCallGuard.ERR_CALL_FAILED;

    private static final Logger LOG = LoggerFactory.getLogger("cava/native");
    private static final CavaNative INSTANCE = new CavaNative(new NativeCallGuard());

    public static CavaNative get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    /** 加固层（熔断 + 看门狗）：包在原生调用之外，**不替代**本类自己的状态机。 */
    private final NativeCallGuard guard;

    private volatile NativeStatus status = NativeStatus.NOT_TRIED;
    private volatile String detail = "tryOpen() 尚未调用";
    private volatile boolean closed = false;
    private volatile CavaBindings active;

    // 诊断信息（启动横幅 / 自检用）
    private String buildId = "";
    private int javaAbiVersion = CavaLayouts.ABI_VERSION;
    private int nativeAbiVersion = -1;
    private int platform = 0;
    private int buildFlags = 0;
    private long buildIdHash = 0;
    private int nativeEntryCount = 0;
    private long touchCount = -1;
    private long javaLayoutSum = 0;
    private long nativeLayoutSum = -1;
    private String fieldTable = "";
    private Path libraryPath;
    private String libraryInfo = "(未加载)";
    private long handle = 0;
    private String openDetail = "(未调用 cava_open)";

    // 配置（由 cava.Cava 在 onInitialize 里注入；不注入也能独立跑）
    private volatile Boolean configEnabled;
    private volatile Path gameDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    private volatile String modVersion = "0.0.0";

    private CavaNative(NativeCallGuard guard) {
        this.guard = guard;
        this.guard.setErrorSink(LOG::error);
        // 熔断 = 把既有状态机翻到 DISABLED_BY_BREAKER：available() 立刻为 false，
        // bindingsIfOpen() 返回 null ⇒ 所有包装方法直接返回 -100，一次原生都不再尝试。
        // 那唯一一条 ERROR 由 CircuitBreaker 自己打（它才是"熔断是既定策略"的记录者）。
        this.guard.breaker().setTripListener(this::onBreakerTrip);
    }

    /**
     * 单测专用：新建一个**不与生产单例共享任何状态**的实例。
     *
     * <p>为什么必须能隔离：熔断是<b>进程内单向</b>的（这是它的设计目的），
     * 如果单测直接把生产单例熔断了，同一 JVM 里后面跑的所有原生测试都会静默走回退路径
     * —— 那正是本项目反复强调的"绿 ≠ 测过"。
     */
    static CavaNative newIsolatedForTest() {
        return new CavaNative(new NativeCallGuard());
    }

    /** 单测专用：指定加固层（用来做"阈值不同、结果必须相同"的对照实验）。 */
    static CavaNative newIsolatedForTest(NativeCallGuard guard) {
        return new CavaNative(guard);
    }

    private void onBreakerTrip(String symbol, int consecutiveFailures, int lastCode) {
        synchronized (lock) {
            if (status == NativeStatus.OPEN) {
                status = NativeStatus.DISABLED_BY_BREAKER;
                detail = "熔断：" + symbol + " 连续 " + consecutiveFailures + " 次失败（最后一次 rc="
                        + CavaLayouts.errorName(lastCode) + "）⇒ 自动全局关闭 native，整体回退纯 Java";
            }
        }
    }

    /** 加固层（熔断 + 看门狗）—— 可查询计数从这里取。 */
    public NativeCallGuard hardening() {
        return guard;
    }

    /** 加固层计数的一行快照（banner / 运维命令）。 */
    public String hardeningReport() {
        return "[cava/native] " + guard.report() + " " + guard.consecutiveReport();
    }

    /** 熔断阈值（{@code -Dcava.native.breaker.threshold}，<=0 = 关闭熔断）。 */
    public int breakerThreshold() {
        return guard.breaker().threshold();
    }

    /** 看门狗阈值（ns；{@code -Dcava.native.watchdog.micros}）。 */
    public long watchdogThresholdNanos() {
        return guard.watchdog().thresholdNanos();
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    /** 注入游戏目录（= 服务端 run 目录）与 mod 版本（决定解压目录）。 */
    public void configure(Path gameDir, String modVersion) {
        if (gameDir != null) {
            this.gameDir = gameDir.toAbsolutePath().normalize();
        }
        if (modVersion != null && !modVersion.isBlank()) {
            this.modVersion = modVersion;
        }
    }

    /** 注入 config/cava.json 里的 native.enabled（系统属性优先）。 */
    public void setConfiguredEnabled(Boolean enabled) {
        this.configEnabled = enabled;
    }

    /** 是否启用原生（系统属性 > config > 默认 true）。 */
    public boolean enabledByFlag() {
        String v = System.getProperty(PROP_ENABLED, "").trim();
        if (!v.isEmpty()) {
            return !(v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off"));
        }
        Boolean c = configEnabled;
        return c == null || c;
    }

    // ------------------------------------------------------------------
    // 状态查询
    // ------------------------------------------------------------------

    public NativeStatus status() {
        return status;
    }

    /** 原生句柄有效且未关闭。 */
    public boolean available() {
        return status == NativeStatus.OPEN && handle != 0 && !closed;
    }

    /** 供热路径用的绑定（未 OPEN 返回 null，调用方直接走纯 Java）。 */
    public CavaBindings bindingsIfOpen() {
        return available() ? active : null;
    }

    /** 任何时候都可调；未 OPEN 抛 IllegalStateException。 */
    public CavaBindings bindings() {
        CavaBindings b = active;
        if (b == null) {
            throw new IllegalStateException("原生库未打开: " + status);
        }
        return b;
    }

    public long handle() {
        return handle;
    }

    public String buildId() {
        return buildId;
    }

    public int abiVersion() {
        return nativeAbiVersion;
    }

    public int javaAbiVersion() {
        return javaAbiVersion;
    }

    public long javaLayoutSum() {
        return javaLayoutSum;
    }

    public long nativeLayoutSum() {
        return nativeLayoutSum;
    }

    public String fieldTable() {
        return fieldTable;
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public String libraryInfo() {
        return libraryInfo;
    }

    public long touchCount() {
        return touchCount;
    }

    public String detail() {
        return detail;
    }

    // ------------------------------------------------------------------
    // 打开
    // ------------------------------------------------------------------

    /** 幂等打开。返回 {@link #available()}。**任何异常都不会逃逸。** */
    public boolean tryOpen() {
        synchronized (lock) {
            if (status != NativeStatus.NOT_TRIED) {
                return available();
            }
            try {
                return doOpen();
            } catch (NativeLibrary.Failure f) {
                status = f.status;
                detail = String.valueOf(f.getMessage());
                LOG.error("[cava/native] 原生库不可用（{}）：{} —— 整体回退纯 Java", status, detail);
            } catch (Throwable t) {
                // 兜底：UnsatisfiedLinkError / ExceptionInInitializerError / OutOfMemoryError 之外的 LinkageError 等
                status = NativeStatus.LOAD_FAILED;
                detail = t.getClass().getName() + ": " + t.getMessage();
                LOG.error("[cava/native] 加载原生库时抛出未预期异常，整体回退纯 Java: " + detail, t);
            }
            return available();
        }
    }

    private boolean doOpen() throws NativeLibrary.Failure {
        if (!enabledByFlag()) {
            status = NativeStatus.DISABLED_BY_FLAG;
            detail = "-D" + PROP_ENABLED + "=false（或 config native.enabled=false）：按设计不加载原生库";
            LOG.info("[cava/native] {} —— 这是正常路径，走纯 Java", detail);
            return false;
        }
        LayoutCheck.JavaSide javaSide = LayoutCheck.javaSide();
        javaLayoutSum = javaSide.sumU32();
        fieldTable = javaSide.fieldTable();

        // Java 侧自身的一致性（与 C 编译器期望的 offsetof 比对）——不依赖原生库
        List<String> selfProblems = CavaLayouts.checkAgainstCAbi();
        if (!selfProblems.isEmpty()) {
            status = NativeStatus.LAYOUT_MISMATCH;
            detail = "Java 侧结构体布局与 cava_abi.h 期望不符: " + String.join("; ", selfProblems);
            LOG.error("[cava/native] {}\n{}", detail, fieldTable);
            return false;
        }

        Path nativesRoot = NativeLibrary.defaultNativesRoot();
        NativeLibrary.Prepared prepared = NativeLibrary.prepare(nativesRoot, modVersion, LOG::info);
        libraryPath = prepared.path();
        libraryInfo = prepared.path() + " (source=" + prepared.source() + ", reused=" + prepared.reused()
                + ", bytes=" + prepared.size() + ", sha256=" + prepared.sha256() + ")";
        NativeLibrary.load(prepared.path(), LOG::info);

        CavaBindings b = CavaBindings.bind(prepared.path(), LOG::info);
        active = b;

        buildId = b.buildId();
        nativeAbiVersion = b.abiVersion();
        touchCount = b.abiTouch();
        if (nativeAbiVersion != CavaLayouts.ABI_VERSION) {
            status = NativeStatus.ABI_MISMATCH;
            detail = "cava_abi_version()=" + nativeAbiVersion + " != Java 侧 " + CavaLayouts.ABI_VERSION;
            LOG.error("[cava/native] {} —— 整体回退纯 Java", detail);
            return false;
        }

        // 布局自检
        Arena arena = Arena.ofShared();
        MemorySegment reportSeg = arena.allocate(CavaLayouts.LAYOUT_REPORT);
        int rc = b.layoutReport(reportSeg);
        if (rc < 0) {
            status = NativeStatus.LAYOUT_MISMATCH;
            detail = "cava_layout_report 返回 " + CavaLayouts.errorName(rc);
            LOG.error("[cava/native] {} —— 整体回退纯 Java", detail);
            return false;
        }
        LayoutCheck.NativeReport report = LayoutCheck.parseReport(reportSeg);
        platform = report.platform();
        buildFlags = report.buildFlags();
        buildIdHash = report.buildIdHash();
        nativeEntryCount = rc;
        LayoutCheck.Result lr = LayoutCheck.compare(javaSide, report);
        if (!lr.ok()) {
            status = NativeStatus.LAYOUT_MISMATCH;
            detail = String.join("; ", lr.problems());
            LOG.error("[cava/native] 布局自检失败，整体回退纯 Java：\n{}\n{}", detail, fieldTable);
            return false;
        }
        for (String note : lr.notes()) {
            LOG.warn("[cava/native] {}", note);
        }
        LOG.info("[cava/native] 布局自检通过：native_entries={} java_sum=0x{} native_sum(per-entry sum)=0x{}\n{}",
                nativeEntryCount, Long.toHexString(lr.sumToSendInOpenParams()),
                Long.toHexString(sumOfReportHashes(report)), fieldTable);

        // 唯一权威公式（cava_abi.h 2026-09-22 裁定）已经定死，不再做变体协商
        long layoutSum = lr.sumToSendInOpenParams();
        int openRc = openOnce(b, arena, layoutSum);
        if (openRc != CavaLayouts.CAVA_OK) {
            status = openRc == CavaLayouts.CAVA_ERR_LAYOUT ? NativeStatus.LAYOUT_MISMATCH
                    : openRc == CavaLayouts.CAVA_ERR_ABI_VERSION ? NativeStatus.ABI_MISMATCH : NativeStatus.OPEN_FAILED;
            detail = "cava_open 返回 " + CavaLayouts.errorName(openRc) + "；" + openDetail;
            LOG.error("[cava/native] {} —— 整体回退纯 Java", detail);
            return false;
        }
        if (handle == 0) {
            status = NativeStatus.OPEN_FAILED;
            detail = "cava_open 返回 CAVA_OK 但 handle == 0（违反 ABI 约定）";
            LOG.error("[cava/native] {}", detail);
            return false;
        }
        if (nativeLayoutSum != javaLayoutSum) {
            status = NativeStatus.LAYOUT_MISMATCH;
            detail = "cava_open 里原生 layout_hash_sum=" + Long.toHexString(nativeLayoutSum)
                    + " 与 Java 侧 0x" + Long.toHexString(javaLayoutSum) + " 不等";
            LOG.error("[cava/native] {}", detail);
            return false;
        }
        status = NativeStatus.OPEN;
        detail = "OK";
        LOG.info("[cava/native] 原生库已打开：" + shortReport());
        return true;
    }

    private int openOnce(CavaBindings b, Arena arena, long layoutSum) {
        // 注意 JDK 21 的分配语义：arena.allocate(layout) = 一个该 layout 的实例；
        // 数组必须 arena.allocateArray(layout, count)。这里全是单实例，且 (指针,长度) 同源（同一 arena）。
        MemorySegment params = arena.allocate(CavaLayouts.OPEN_PARAMS);
        params.set(ValueLayout.JAVA_INT, 0, CavaLayouts.ABI_VERSION);
        params.set(ValueLayout.JAVA_INT, 4, CavaLayouts.OPEN_FLAG_SAFE_ASSERTS | CavaLayouts.OPEN_FLAG_DETERMINISTIC);
        params.set(ValueLayout.JAVA_LONG, 8, layoutSum);
        params.set(ValueLayout.JAVA_LONG, 16, 0L);
        params.set(ValueLayout.JAVA_LONG, 24, 0L);
        MemorySegment outHandle = arena.allocate(ValueLayout.JAVA_LONG); // 一个 int64_t
        MemorySegment outResult = arena.allocate(CavaLayouts.OPEN_RESULT);

        int rc = b.open(params, outHandle, outResult);
        int rStatus = outResult.get(ValueLayout.JAVA_INT, 0);
        int rAbi = outResult.get(ValueLayout.JAVA_INT, 4);
        long rSum = outResult.get(ValueLayout.JAVA_LONG, 8);
        long h = outHandle.get(ValueLayout.JAVA_LONG, 0);
        openDetail = "sent_sum=0x" + Long.toHexString(layoutSum) + " rc=" + CavaLayouts.errorName(rc)
                + " result.status=" + CavaLayouts.errorName(rStatus) + " result.abi=" + rAbi
                + " result.native_layout_sum=0x" + Long.toHexString(rSum) + " handle=" + h;
        LOG.info("[cava/native] cava_open → {}", openDetail);
        if (rc == CavaLayouts.CAVA_OK && h != 0) {
            handle = h;
            nativeLayoutSum = rSum;
            nativeAbiVersion = rAbi;
            return CavaLayouts.CAVA_OK;
        }
        handle = 0;
        nativeLayoutSum = rSum;
        return rc != CavaLayouts.CAVA_OK ? rc : CavaLayouts.CAVA_ERR_INTERNAL;
    }

    private static long sumOfReportHashes(LayoutCheck.NativeReport report) {
        long sum = 0;
        for (LayoutCheck.NativeEntry e : report.entries()) {
            sum = (sum + (e.layoutHash() & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        }
        return sum;
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    /** 幂等关闭；不抛异常。不释放 FFM 的 lookup arena（释放会让已加载库的句柄悬空）。 */
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            long h = handle;
            if (h != 0 && active != null) {
                try {
                    int rc = active.close(h);
                    LOG.info("[cava/native] cava_close({}) → {}", h, CavaLayouts.errorName(rc));
                } catch (Throwable t) {
                    LOG.error("[cava/native] cava_close 抛出异常（已忽略）", t);
                }
            }
            handle = 0;
            active = null;
        }
    }

    /**
     * 原生调用失败时的统一记录（Numeric / NativePush 的热路径回退用）。
     *
     * <p>这些入口（数值工具、push）不在 {@link #guard} 的包装内，所以这里<b>只</b>做两件事：
     * 记一次失败（连续计数）与"每个入口最多一条 ERROR"的既有行为。
     *
     * <p>处置口径（已写进 docs/CAVA-hardening-notes.md）：
     * <b>抛异常算失败</b>（一个 downcall 抛 Throwable 不是"合法回退"，是原生调用真的坏了）；
     * 而<b>错误码只在被包装的入口上计</b>——push 是 opt-in 且默认关的子系统，
     * 它返回的 {@code CAVA_ERR_ARG} 不该把正在跑的两个核一起关掉。
     */
    public void onNativeCallFailure(String symbol, Throwable t) {
        guard.noteThrowable(symbol, t);
    }

    // ------------------------------------------------------------------
    // P1 薄封装：**status != OPEN 时一律返回回退信号**，调用方不必自己判断状态
    // ------------------------------------------------------------------

    /**
     * 分配数组的唯一正确写法（JDK 21 实测陷阱）：
     * {@code arena.allocate(JAVA_INT, 10)} 是「一个 int、值 10」，**只有 4 字节**；
     * 把它当数组交给原生写越界会把 JVM 打成段错误。数组一律走这里（= {@code allocateArray}）。
     */
    public static MemorySegment allocateArray(Arena arena, MemoryLayout element, long count) {
        return arena.allocateArray(element, count);
    }

    /**
     * {@code cava_pathfind}：&gt;0 = 节点数；0 = 无路径；&lt;0 = 错误码；
     * {@link #ERR_NATIVE_UNAVAILABLE} = 原生不可用（必须回退原逻辑）。
     *
     * @param req 容量 ≥ {@code sizeof(CavaPathRequest)} 的段（用 {@link CavaLayouts#PATH_REQUEST} 分配）
     * @param out 容量 = {@code cap} 个 {@link CavaLayouts#PATH_NODE} 的数组（{@link #allocateArray}）
     */
    public int pathfind(long handle, MemorySegment req, MemorySegment out, int cap) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_PATHFIND, () -> b.pathfind(handle, req, out, cap));
    }

    /** {@code cava_mob_profile_upload}：非法字段（width&lt;=0 / NaN 等）→ {@code CAVA_ERR_ARG}，且不改动已有档案。 */
    public int mobProfileUpload(long handle, MemorySegment profile) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_MOB_PROFILE_UPLOAD, () -> b.mobProfileUpload(handle, profile));
    }

    /** {@code cava_mob_profile_clear}：幂等；未上传过也算成功。 */
    public int mobProfileClear(long handle) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_MOB_PROFILE_CLEAR, () -> b.mobProfileClear(handle));
    }

    /**
     * {@code cava_state_table_upload}：一次性上传方块状态表。
     *
     * @param records {@link #allocateArray}(arena, {@link CavaLayouts#STATE_RECORD}, recordCount)
     * @param boxes   {@link #allocateArray}(arena, {@link CavaLayouts#COLLISION_BOX}, boxCount)；boxCount==0 时传 {@code MemorySegment.NULL}
     */
    public int stateTableUpload(long handle, MemorySegment records, int recordCount,
                                MemorySegment boxes, int boxCount) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_STATE_TABLE_UPLOAD,
                () -> b.stateTableUpload(handle, records, recordCount, boxes, boxCount));
    }

    /**
     * {@code cava_region_upload}：把 {@code dimX*dimY*dimZ} 个 state id 推进原生区域缓存
     * （索引顺序 {@code ((y*dimZ)+z)*dimX+x}，x 最快、y 最慢）。
     *
     * @param ids {@link #allocateArray}(arena, ValueLayout.JAVA_INT, idCount) —— 必须与 idCount 同源
     */
    public int regionUpload(long handle, int dimX, int dimY, int dimZ,
                            int originX, int originY, int originZ, MemorySegment ids, int idCount) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_REGION_UPLOAD,
                () -> b.regionUpload(handle, dimX, dimY, dimZ, originX, originY, originZ, ids, idCount));
    }

    /** {@code cava_region_clear}：幂等。 */
    public int regionClear(long handle) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_REGION_CLEAR, () -> b.regionClear(handle));
    }

    /** {@code cava_region_state_id_at}：{@code outStateId} = {@code arena.allocate(ValueLayout.JAVA_INT)}（一个 int）。 */
    public int regionStateIdAt(long handle, int x, int y, int z, MemorySegment outStateId) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_REGION_STATE_ID_AT,
                () -> b.regionStateIdAt(handle, x, y, z, outStateId));
    }

    // ------------------------------------------------------------------
    // P2 薄封装：实体位移（形状表 + 一次求解）
    // ------------------------------------------------------------------

    /**
     * {@code cava_shape_table_upload}：一次性上传「按 state id 索引」的常驻形状表
     * （点表 + 体素位图，碰撞求解用；与 P1 的扁平 AABB 状态表是**两张不同的表**）。
     *
     * <p>数组一律用 {@link #allocateArray}(arena, …)：
     * {@code records} = {@code allocateArray(arena, CavaLayouts.SHAPE_RECORD, n)}，
     * {@code points} = {@code allocateArray(arena, ValueLayout.JAVA_DOUBLE, m)}，
     * {@code bits} = {@code allocateArray(arena, ValueLayout.JAVA_LONG, w)}。
     * 容量为 0 的数组传 {@code MemorySegment.NULL}（**不要**传 {@code arena.allocate(layout, 0)}）。
     */
    public int shapeTableUpload(long handle, MemorySegment records, int recordCount,
                                MemorySegment points, int pointCount,
                                MemorySegment bits, int bitWordCount) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_SHAPE_TABLE_UPLOAD,
                () -> b.shapeTableUpload(handle, records, recordCount, points, pointCount, bits, bitWordCount));
    }

    /**
     * {@code cava_resolve_move}：一次实体位移求解。
     *
     * <p>返回 {@code CAVA_OK} 时还要看 {@code out} 的 {@code event_overflow}；
     * {@code ==1} ⇒ 调用方必须回退纯 Java（见 {@code cava.entity.MoveEventLog#overflowed()}）。
     */
    public int resolveMove(long handle, MemorySegment req,
                           MemorySegment refs, int refCount,
                           MemorySegment inlineShapes, int inlineShapeCount,
                           MemorySegment inlinePoints, int inlinePointCount,
                           MemorySegment inlineBits, int inlineBitWordCount,
                           MemorySegment events, int eventCap,
                           MemorySegment out) {
        CavaBindings b = bindingsIfOpen();
        if (b == null) {
            return guard.unavailable();   // 原生回退计数（P4 验收要看的那个数）
        }
        return guard.call(CavaBindings.SYM_RESOLVE_MOVE,
                () -> b.resolveMove(handle, req, refs, refCount, inlineShapes, inlineShapeCount,
                        inlinePoints, inlinePointCount, inlineBits, inlineBitWordCount, events, eventCap, out));
    }

    // ------------------------------------------------------------------
    // 诊断入口（给不引用 FFM 的探针/脚本用；生产热路径不要调）
    // ------------------------------------------------------------------

    /**
     * 用<b>调用方指定的句柄</b>发一次 {@code cava_pathfind}（全 0 请求 + {@code cap} 张节点）。
     *
     * <p>存在的唯一理由：让 {@code cava.harden}（按契约不许出现 {@code java.lang.foreign}）里的
     * 命令行探针与单测也能观测到"原生入口的真实返回码"，而不必在探针里复制一份 ABI 布局。
     * 句柄可以被伪造成任意值 —— 这正是它作为"必然失败"输入源的用法。
     */
    public int probePathfind(long handleOverride, int cap) {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment req = arena.allocate(CavaLayouts.PATH_REQUEST);
            MemorySegment out = allocateArray(arena, CavaLayouts.PATH_NODE, cap);
            return pathfind(handleOverride, req, out, cap);
        }
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    /** 单行摘要。 */
    public String shortReport() {
        return "status=" + status
                + " build_id=\"" + buildId + "\""
                + " abi=" + nativeAbiVersion + "/" + javaAbiVersion
                + " java_sum=0x" + Long.toHexString(javaLayoutSum)
                + " native_sum=0x" + Long.toHexString(nativeLayoutSum)
                + " entries=" + nativeEntryCount
                + " handle=" + handle;
    }

    /** 启动横幅（多行）。 */
    public String banner() {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("  ================================ Cava / native ================================").append(System.lineSeparator());
        sb.append("  native 状态     : ").append(status).append("  (").append(detail).append(')').append(System.lineSeparator());
        sb.append("  enabled 判定    : ").append(enabledByFlag()
                ? "true（系统属性 " + PROP_ENABLED + " / config native.enabled）"
                : "false（-D" + PROP_ENABLED + "=false 或 config native.enabled=false）").append(System.lineSeparator());
        sb.append("  库文件          : ").append(libraryInfo).append(System.lineSeparator());
        sb.append("  符号表          : ").append(active == null ? "(未绑定)" : active.lookupKind()).append(System.lineSeparator());
        sb.append("  build_id        : ").append(buildId.isEmpty() ? "(未取到)" : buildId).append(System.lineSeparator());
        sb.append("  ABI 版本        : java=").append(javaAbiVersion).append(" native=").append(nativeAbiVersion)
                .append(" platform=").append(platformName(platform)).append(" build_flags=0x").append(Integer.toHexString(buildFlags))
                .append(" build_id_hash=0x").append(Long.toHexString(buildIdHash)).append(System.lineSeparator());
        sb.append("  布局自检        : ").append(status == NativeStatus.LAYOUT_MISMATCH ? "失败" : "通过")
                .append("  java_sum=0x").append(Long.toHexString(javaLayoutSum))
                .append(" native_sum=0x").append(Long.toHexString(nativeLayoutSum))
                .append(" entries=").append(nativeEntryCount).append(System.lineSeparator());
        sb.append("  cava_abi_touch  : ").append(touchCount).append("（=1 表示原生代码确实执行过）").append(System.lineSeparator());
        sb.append("  句柄            : ").append(handle).append(System.lineSeparator());
        sb.append("  熔断            : ").append(guard.breaker().report()).append(System.lineSeparator());
        sb.append("  看门狗          : ").append(guard.watchdog().report()).append(System.lineSeparator());
        sb.append("  回退语义        : ").append(available() ? "不适用（原生可用）" : "整体回退纯 Java（所有钩子不介入）").append(System.lineSeparator());
        sb.append("  ==============================================================================");
        return sb.toString();
    }

    /** 结构体逐字段 (offset,size) 表（任务 C3 要求打日志）。 */
    public String layoutDetail() {
        return fieldTable.isEmpty() ? LayoutCheck.javaSide().fieldTable() : fieldTable;
    }

    public static String platformName(int p) {
        return switch (p) {
            case CavaLayouts.PLATFORM_WINDOWS_X64 -> "windows-x64";
            case CavaLayouts.PLATFORM_LINUX_X64 -> "linux-x64";
            case 2 -> "windows-arm64";
            case 4 -> "linux-arm64";
            case 5 -> "macos-x64";
            case 6 -> "macos-arm64";
            default -> "unknown(" + p + ")";
        };
    }

    /** 允许自检/测试注入日志消费者（P0 只需要能拿到字符串，故用不到；保留给单测）。 */
    public void forEachLogLine(Consumer<String> sink) {
        sink.accept(banner());
        sink.accept(layoutDetail());
    }
}
