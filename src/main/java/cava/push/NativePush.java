package cava.push;

import cava.ffm.CavaNative;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 第 2 核的 FFM 绑定。
 *
 * <p><b>为什么自己 libraryLookup</b>：{@code cava/ffm/CavaBindings.java} 的 {@code REQUIRED_SYMBOLS}
 * 与逐符号 downcall 句柄是**冻结的、且不在本流授权路径**，所以本轮新增的三个符号不能登记进去。
 * 这里复用 {@link CavaNative#libraryPath()}（已封装好的"不要用 defaultLookup"那条纪律），
 * 在 {@code cava/push} 自己的 Arena 上再取一次符号。**这是临时形态**，
 * 正式形态要求把符号并进 CavaBindings（见 docs/CAVA-push-notes.md 提案第 6 条）。
 *
 * <p>失败语义：任何一步失败 => {@link #available()} 恒 false，调用方走原版，**只报一次日志**。
 */
public final class NativePush {

    private static final Logger LOG = LoggerFactory.getLogger("cava/push");

    public static final int ERR_UNAVAILABLE = CavaNative.ERR_NATIVE_UNAVAILABLE;

    private static final FunctionDescriptor FD_PUSH_AWAY = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    private static final FunctionDescriptor FD_SECTION_PLAN = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    private static final FunctionDescriptor FD_BOX_FILTER = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

    private static volatile boolean tried;
    private static volatile boolean ok;
    private static volatile String detail = "未尝试";

    private static MethodHandle hPushAway;
    private static MethodHandle hSectionPlan;
    private static MethodHandle hBoxFilter;

    /** 长生命周期（库句柄必须活到进程结束）。 */
    private static Arena lookupArena;

    /** 每线程的 out 暂存（dx/dz 各 8 字节 + hit 4 字节）。**避免热路径分配**。 */
    private static final ThreadLocal<MemorySegment> SCRATCH =
            ThreadLocal.withInitial(() -> Arena.ofShared().allocate(24));

    private NativePush() {
    }

    public static boolean available() {
        ensure();
        return ok;
    }

    public static String detail() {
        ensure();
        return detail;
    }

    private static synchronized void ensure() {
        if (tried) {
            return;
        }
        tried = true;
        try {
            CavaNative nat = CavaNative.get();
            if (!nat.tryOpen()) {
                detail = "原生库未打开：" + nat.status();
                return;
            }
            Path lib = nat.libraryPath();
            if (lib == null) {
                detail = "libraryPath 为 null";
                return;
            }
            lookupArena = Arena.ofShared();
            SymbolLookup lookup = SymbolLookup.libraryLookup(lib, lookupArena);
            Linker linker = Linker.nativeLinker();
            hPushAway = linker.downcallHandle(require(lookup, "cava_push_away_from"), FD_PUSH_AWAY);
            hSectionPlan = linker.downcallHandle(require(lookup, "cava_push_section_plan"), FD_SECTION_PLAN);
            hBoxFilter = linker.downcallHandle(require(lookup, "cava_push_box_filter"), FD_BOX_FILTER);
            ok = true;
            detail = "OK (" + lib.getFileName() + ")";
        } catch (Throwable t) {
            ok = false;
            detail = t.getClass().getSimpleName() + ": " + t.getMessage();
            LOG.info("[cava/push] 原生 push 入口不可用（{}）—— 整段回退原版", detail);
        }
    }

    private static MemorySegment require(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException("缺少符号 " + name));
    }

    /**
     * {@code cava_push_away_from}：成功返回 hit（0/1），失败返回负错误码。
     * 成功后 {@link #lastDx()} / {@link #lastDz()} 是本线程最后一次的结果。
     */
    public static int pushAwayFrom(double thisX, double thisZ, double otherX, double otherZ) {
        if (!available()) {
            return ERR_UNAVAILABLE;
        }
        try {
            MemorySegment s = SCRATCH.get();
            MemorySegment pdx = s.asSlice(0, 8);
            MemorySegment pdz = s.asSlice(8, 8);
            MemorySegment phit = s.asSlice(16, 4);
            int rc = (int) hPushAway.invokeExact(thisX, thisZ, otherX, otherZ, pdx, pdz, phit);
            return rc == 0 ? phit.get(ValueLayout.JAVA_INT, 0) : rc;
        } catch (Throwable t) {
            CavaNative.get().onNativeCallFailure("cava_push_away_from", t);
            return CavaNative.ERR_CALL_FAILED;
        }
    }

    public static double lastDx() {
        return SCRATCH.get().get(ValueLayout.JAVA_DOUBLE, 0);
    }

    public static double lastDz() {
        return SCRATCH.get().get(ValueLayout.JAVA_DOUBLE, 8);
    }

    /** {@code cava_push_section_plan}：成功把计划写进 {@code out} 并返回条数；失败返回负错误码。 */
    public static int sectionPlan(MemorySegment box6, MemorySegment positions, int count,
                                  MemorySegment out, int outCap, MemorySegment outCount) {
        if (!available()) {
            return ERR_UNAVAILABLE;
        }
        try {
            outCount.set(ValueLayout.JAVA_INT, 0, -1);
            int rc = (int) hSectionPlan.invokeExact(box6, positions, count, out, outCap, outCount);
            if (rc != 0) {
                return rc;
            }
            return outCount.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            CavaNative.get().onNativeCallFailure("cava_push_section_plan", t);
            return CavaNative.ERR_CALL_FAILED;
        }
    }

    /** {@code cava_push_box_filter}：顺序保持的候选下标。生产路径**未接线**（见 notes 第 8 节）。 */
    public static int boxFilter(MemorySegment query6, MemorySegment boxes6, int count,
                                MemorySegment outIdx, int outCap, MemorySegment outCount) {
        if (!available()) {
            return ERR_UNAVAILABLE;
        }
        try {
            outCount.set(ValueLayout.JAVA_INT, 0, -1);
            int rc = (int) hBoxFilter.invokeExact(query6, boxes6, count, outIdx, outCap, outCount);
            if (rc != 0) {
                return rc;
            }
            return outCount.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            CavaNative.get().onNativeCallFailure("cava_push_box_filter", t);
            return CavaNative.ERR_CALL_FAILED;
        }
    }
}
