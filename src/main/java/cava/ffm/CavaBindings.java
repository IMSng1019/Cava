package cava.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code cava_abi.h} 的手写 FFM 绑定（**不用 jextract**）。
 *
 * <p><b>JDK 21 预览版 API 的已实测坑（契约 2.4/2.5）——本类的写法严格遵守</b>：
 * <ul>
 *   <li>没有 {@code Linker.Option.critical}（JDK 22 才有）：本类不使用任何 Linker.Option。</li>
 *   <li>数组分配必须 {@code arena.allocateArray(layout, count)}：
 *       {@code arena.allocate(JAVA_INT, 10)} 是「一个 int、值为 10」，只有 4 字节——
 *       用它当数组会让原生写越界并直接把 JVM 打成段错误（本项目已复现）。</li>
 *   <li>{@code Arena} 没有 {@code byteSize()}。</li>
 *   <li>没有 {@code Arena.allocateFrom(String)}；也没有 {@code MemorySegment.setString/getString}，
 *       JDK 21 用 {@code setUtf8String}/{@code getUtf8String}。</li>
 * </ul>
 *
 * <p>所有函数都走 {@code linker.downcallHandle(symbol, FunctionDescriptor)}，符号来自
 * {@code linker.defaultLookup()}（已 System.load 的库对其可见）；万一不可见，回退
 * {@code SymbolLookup.libraryLookup(path, arena)} 并保留 arena 生命周期到进程结束。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class CavaBindings {

    /** 由 {@link #bind(Path, java.util.function.Consumer)} 返回；绑定失败抛 {@link NativeLibrary.Failure}。 */
    public static final String SYM_BUILD_ID = "cava_build_id";
    public static final String SYM_ABI_TOUCH = "cava_abi_touch";
    public static final String SYM_ABI_VERSION = "cava_abi_version";
    public static final String SYM_LAYOUT_REPORT = "cava_layout_report";
    public static final String SYM_OPEN = "cava_open";
    public static final String SYM_CLOSE = "cava_close";
    public static final String SYM_D2I_SAT = "cava_d2i_sat";
    public static final String SYM_D2L_SAT = "cava_d2l_sat";
    public static final String SYM_BITS_OF_DOUBLE = "cava_bits_of_double";
    public static final String SYM_DOUBLE_OF_BITS = "cava_double_of_bits";

    /** 全部必须存在（缺一个就回退纯 Java）。 */
    public static final List<String> REQUIRED_SYMBOLS = List.of(
            SYM_BUILD_ID, SYM_ABI_TOUCH, SYM_ABI_VERSION, SYM_LAYOUT_REPORT,
            SYM_OPEN, SYM_CLOSE, SYM_D2I_SAT, SYM_D2L_SAT, SYM_BITS_OF_DOUBLE, SYM_DOUBLE_OF_BITS);

    /** 原生调用抛出任何 Throwable 时的包装（Numeric/CavaNative 会捕获它并回退）。 */
    public static final class CallFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public final String symbol;

        public CallFailure(String symbol, Throwable cause) {
            super(symbol + " 调用失败: " + cause, cause);
            this.symbol = symbol;
        }
    }

    private final Path libraryPath;
    private final String lookupKind;
    private final Arena lookupArena; // libraryLookup 用；null = 走 defaultLookup

    private final MethodHandle hBuildId;
    private final MethodHandle hAbiTouch;
    private final MethodHandle hAbiVersion;
    private final MethodHandle hLayoutReport;
    private final MethodHandle hOpen;
    private final MethodHandle hClose;
    private final MethodHandle hD2iSat;
    private final MethodHandle hD2lSat;
    private final MethodHandle hBitsOfDouble;
    private final MethodHandle hDoubleOfBits;

    private CavaBindings(Path libraryPath, String lookupKind, Arena lookupArena, Linker linker, SymbolLookup lookup)
            throws NativeLibrary.Failure {
        this.libraryPath = libraryPath;
        this.lookupKind = lookupKind;
        this.lookupArena = lookupArena;

        List<String> missing = new ArrayList<>();
        for (String sym : REQUIRED_SYMBOLS) {
            if (lookup.find(sym).isEmpty()) {
                missing.add(sym);
            }
        }
        if (!missing.isEmpty()) {
            throw new NativeLibrary.Failure(NativeStatus.LOAD_FAILED,
                    "库 " + libraryPath.getFileName() + " 缺少符号: " + String.join(", ", missing)
                            + "（lookup=" + lookupKind + "）");
        }

        // 注意：这里全部是标量 / 指针参数，没有 (指针,长度) 对，因此不涉及分配语义的坑。
        this.hBuildId = downcall(linker, lookup, SYM_BUILD_ID, FunctionDescriptor.of(ValueLayout.ADDRESS));
        this.hAbiTouch = downcall(linker, lookup, SYM_ABI_TOUCH, FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        this.hAbiVersion = downcall(linker, lookup, SYM_ABI_VERSION, FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.hLayoutReport = downcall(linker, lookup, SYM_LAYOUT_REPORT,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.hOpen = downcall(linker, lookup, SYM_OPEN,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.hClose = downcall(linker, lookup, SYM_CLOSE, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        this.hD2iSat = downcall(linker, lookup, SYM_D2I_SAT, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_DOUBLE));
        this.hD2lSat = downcall(linker, lookup, SYM_D2L_SAT, FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));
        this.hBitsOfDouble = downcall(linker, lookup, SYM_BITS_OF_DOUBLE,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));
        this.hDoubleOfBits = downcall(linker, lookup, SYM_DOUBLE_OF_BITS,
                FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_LONG));
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor desc) {
        // JDK 21 没有 Linker.Option.critical；这里也不加任何 option（默认语义 = 普通 downcall）
        return linker.downcallHandle(lookup.find(symbol).orElseThrow(), desc);
    }

    /**
     * 绑定符号。库必须已经被 {@link NativeLibrary#load} 加载过。
     *
     * @param log 绑定过程的诊断输出（lookup 用了哪条路）
     */
    public static CavaBindings bind(Path libraryPath, java.util.function.Consumer<String> log) throws NativeLibrary.Failure {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();
        Arena arena = null;
        String kind = "Linker.defaultLookup()";
        Optional<MemorySegment> probe = lookup.find(SYM_BUILD_ID);
        if (probe.isEmpty()) {
            // 兜底：某些平台/加载方式下 defaultLookup 看不到 System.load 进来的库
            log.accept("[native] defaultLookup() 找不到 " + SYM_BUILD_ID + "，回退 SymbolLookup.libraryLookup()");
            arena = Arena.ofShared();
            lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            kind = "SymbolLookup.libraryLookup(" + libraryPath.getFileName() + ")";
        }
        log.accept("[native] 符号表来源: " + kind);
        return new CavaBindings(libraryPath, kind, arena, linker, lookup);
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public String lookupKind() {
        return lookupKind;
    }

    /**
     * 一次性分配一个共享 Arena 供 open/布局自检的入参出参使用。
     * 调用方负责生命周期（本类不持有）。
     */
    public static Arena newSharedArena() {
        return Arena.ofShared();
    }

    // ------------------------------------------------------------------
    // 调用包装：一律把 Throwable 转成 CallFailure，绝不逃逸
    // （MethodHandle.invokeExact 声明 throws Throwable，必须包）
    // ------------------------------------------------------------------

    /** {@code const char* cava_build_id(void)} —— 静态字符串，生命周期 = 进程。 */
    public String buildId() {
        try {
            MemorySegment p = (MemorySegment) hBuildId.invokeExact();
            if (p == null || p.equals(MemorySegment.NULL)) {
                return "";
            }
            // 返回的是 0 长度段：reinterpret 到足够大的界内再读 NUL 结尾 UTF-8
            return p.reinterpret(4096).getUtf8String(0);
        } catch (Throwable t) {
            throw new CallFailure(SYM_BUILD_ID, t);
        }
    }

    public long abiTouch() {
        try {
            return (long) hAbiTouch.invokeExact();
        } catch (Throwable t) {
            throw new CallFailure(SYM_ABI_TOUCH, t);
        }
    }

    public int abiVersion() {
        try {
            return (int) hAbiVersion.invokeExact();
        } catch (Throwable t) {
            throw new CallFailure(SYM_ABI_VERSION, t);
        }
    }

    /** {@code int32_t cava_layout_report(CavaLayoutReport* out)} —— out 容量必须 ≥ sizeof(CavaLayoutReport)。 */
    public int layoutReport(MemorySegment out) {
        try {
            return (int) hLayoutReport.invokeExact(out);
        } catch (Throwable t) {
            throw new CallFailure(SYM_LAYOUT_REPORT, t);
        }
    }

    /** {@code int32_t cava_open(const CavaOpenParams*, int64_t* out_handle, CavaOpenResult*)}。 */
    public int open(MemorySegment params, MemorySegment outHandle, MemorySegment outResult) {
        try {
            return (int) hOpen.invokeExact(params, outHandle, outResult);
        } catch (Throwable t) {
            throw new CallFailure(SYM_OPEN, t);
        }
    }

    /** {@code int32_t cava_close(int64_t handle)} —— 幂等。 */
    public int close(long handle) {
        try {
            return (int) hClose.invokeExact(handle);
        } catch (Throwable t) {
            throw new CallFailure(SYM_CLOSE, t);
        }
    }

    public int d2iSat(double v) {
        try {
            return (int) hD2iSat.invokeExact(v);
        } catch (Throwable t) {
            throw new CallFailure(SYM_D2I_SAT, t);
        }
    }

    public long d2lSat(double v) {
        try {
            return (long) hD2lSat.invokeExact(v);
        } catch (Throwable t) {
            throw new CallFailure(SYM_D2L_SAT, t);
        }
    }

    public long bitsOfDouble(double v) {
        try {
            return (long) hBitsOfDouble.invokeExact(v);
        } catch (Throwable t) {
            throw new CallFailure(SYM_BITS_OF_DOUBLE, t);
        }
    }

    public double doubleOfBits(long bits) {
        try {
            return (double) hDoubleOfBits.invokeExact(bits);
        } catch (Throwable t) {
            throw new CallFailure(SYM_DOUBLE_OF_BITS, t);
        }
    }

    /** 只用于诊断：确认某个 layout 的 byteSize（Arena 没有 byteSize，MemoryLayout 有）。 */
    public static long sizeOf(MemoryLayout layout) {
        return layout.byteSize();
    }
}
