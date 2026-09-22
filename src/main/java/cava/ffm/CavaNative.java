package cava.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Logger LOG = LoggerFactory.getLogger("cava/native");
    private static final CavaNative INSTANCE = new CavaNative();

    public static CavaNative get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final Set<String> reportedCallFailures = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
    private long javaLayoutSumBytes = 0;
    private long nativeLayoutSum = -1;
    private boolean layoutByteWiseVariant = false;
    private String fieldTable = "";
    private Path libraryPath;
    private String libraryInfo = "(未加载)";
    private long handle = 0;
    private String openDetail = "(未调用 cava_open)";

    // 配置（由 cava.Cava 在 onInitialize 里注入；不注入也能独立跑）
    private volatile Boolean configEnabled;
    private volatile Path gameDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    private volatile String modVersion = "0.0.0";

    private CavaNative() {
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

    public long javaLayoutSumBytes() {
        return javaLayoutSumBytes;
    }

    public long nativeLayoutSum() {
        return nativeLayoutSum;
    }

    public boolean layoutByteWiseVariant() {
        return layoutByteWiseVariant;
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
        javaLayoutSum = LayoutCheck.javaSide().sumU32();
        javaLayoutSumBytes = LayoutCheck.javaSide().sumBytes();
        fieldTable = LayoutCheck.javaSide().fieldTable();

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
        LayoutCheck.Result lr = LayoutCheck.compare(LayoutCheck.javaSide(), report);
        layoutByteWiseVariant = lr.useByteWiseSum();
        if (!lr.ok()) {
            status = NativeStatus.LAYOUT_MISMATCH;
            detail = String.join("; ", lr.problems());
            LOG.error("[cava/native] 布局自检失败，整体回退纯 Java：\n{}\n{}", detail, fieldTable);
            return false;
        }
        for (String note : lr.notes()) {
            LOG.warn("[cava/native] [协商] {}", note);
        }
        LOG.info("[cava/native] 布局自检通过：native_entries={} java_sum=0x{} native_sum(per-entry u32 sum)=0x{}\n{}",
                nativeEntryCount, Long.toHexString(lr.sumToSendInOpenParams()),
                Long.toHexString(sumOfReportHashes(report)), fieldTable);

        // cava_open：先用协商出的变体；若原生用另一种变体算和，会返回 CAVA_ERR_LAYOUT，再试另一种
        long primarySum = lr.sumToSendInOpenParams();
        long alternateSum = layoutByteWiseVariant ? javaLayoutSum : javaLayoutSumBytes;
        int openRc = openOnce(b, arena, primarySum, report);
        if (openRc == CavaLayouts.CAVA_ERR_LAYOUT && primarySum != alternateSum) {
            LOG.warn("[cava/native] cava_open 返回 CAVA_ERR_LAYOUT（layout_hash_sum 变体不符），改用另一种变体重试一次");
            openRc = openOnce(b, arena, alternateSum, report);
            if (openRc == CavaLayouts.CAVA_OK) {
                layoutByteWiseVariant = !layoutByteWiseVariant;
            }
        }
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
        if (nativeLayoutSum != primarySum && nativeLayoutSum != alternateSum) {
            status = NativeStatus.LAYOUT_MISMATCH;
            detail = "cava_open 里原生 layout_hash_sum=" + Long.toHexString(nativeLayoutSum)
                    + " 与 Java 侧 0x" + Long.toHexString(primarySum) + " / 0x" + Long.toHexString(alternateSum) + " 都不等";
            LOG.error("[cava/native] {}", detail);
            return false;
        }
        status = NativeStatus.OPEN;
        detail = "OK";
        LOG.info("[cava/native] 原生库已打开：" + shortReport());
        return true;
    }

    private int openOnce(CavaBindings b, Arena arena, long layoutSum, LayoutCheck.NativeReport report) {
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

    /** 原生调用失败时的统一记录（Numeric 的热路径回退用）。 */
    public void onNativeCallFailure(String symbol, Throwable t) {
        if (reportedCallFailures.add(symbol)) {
            LOG.error("[cava/native] {} 调用失败，该调用起回退纯 Java（同类错误只报一次）", symbol, t);
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
                + (layoutByteWiseVariant ? " layout_hash=byte-wise(协商)" : "")
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
                .append("  java_sum(u32)=0x").append(Long.toHexString(javaLayoutSum))
                .append(" java_sum(bytes)=0x").append(Long.toHexString(javaLayoutSumBytes))
                .append(" native_sum=0x").append(Long.toHexString(nativeLayoutSum))
                .append(" entries=").append(nativeEntryCount)
                .append(layoutByteWiseVariant ? "  [逐字节变体]" : "").append(System.lineSeparator());
        sb.append("  cava_abi_touch  : ").append(touchCount).append("（=1 表示原生代码确实执行过）").append(System.lineSeparator());
        sb.append("  句柄            : ").append(handle).append(System.lineSeparator());
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
