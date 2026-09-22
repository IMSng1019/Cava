package cava.ffm;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 独立自检入口（任务 C7）——**不依赖 Minecraft**，可直接从命令行跑：
 *
 * <pre>
 * &amp; 'C:\Program Files\Java\jdk-21\bin\java.exe' --enable-preview --enable-native-access=ALL-UNNAMED \
 *     -cp &lt;classes&gt; cava.ffm.NativeSelfTest
 * </pre>
 *
 * <p>步骤：
 * <ol>
 *   <li>原生符号：{@code cava_build_id()} / {@code cava_abi_version()} / {@code cava_abi_touch()}；</li>
 *   <li>Java 侧 4 个结构体的逐字段 (offset,size) 表与两种 layout_hash_sum（**不依赖原生库**，总是打印）；</li>
 *   <li>{@code cava_open} → status / native_layout_sum / handle；</li>
 *   <li>{@code cava_d2i_sat} 与 Java 饱和语义的逐点差分（含 NaN / ±∞ / 越界）；</li>
 *   <li>原生库不存在时优雅失败：打印状态与 {@code LOAD_FAILED} 路径说明，**exit 0 不算崩溃**。</li>
 * </ol>
 *
 * <p>退出码：0 = 原生可用且差分全过，或原生不可用（优雅失败）；3 = 原生打开了但差分不一致；2 = 自检自身抛异常。
 * 命令行可选参数：{@code disabled}（强制 {@code -Dcava.native.enabled=false} 走 DISABLED_BY_FLAG 路径）。
 */
public final class NativeSelfTest {

    private NativeSelfTest() {
    }

    private static int failures = 0;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("[self-test] 自检自身抛出异常（不是原生库的问题）: " + t);
            t.printStackTrace(System.out);
            System.exit(2);
        }
    }

    private static void run(String[] args) {
        boolean forceDisabled = false;
        for (String a : args) {
            if ("disabled".equalsIgnoreCase(a)) {
                forceDisabled = true;
            }
        }
        if (forceDisabled) {
            System.setProperty(CavaNative.PROP_ENABLED, "false");
        }

        line("=== Cava NativeSelfTest ===");
        line("java.version      : " + System.getProperty("java.version") + "  (" + System.getProperty("java.vendor") + ")");
        line("os / arch         : " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
        line("platformDir       : " + NativeLibrary.PLATFORM_DIR);
        line("resourcePath      : " + NativeLibrary.RESOURCE_PATH);
        line("cava.native.path  : " + orNone(System.getProperty(NativeLibrary.PROP_LIBRARY_PATH)));
        line("cava.native.dir   : " + orNone(System.getProperty(NativeLibrary.PROP_NATIVES_DIR)));
        line("nativesRoot       : " + NativeLibrary.defaultNativesRoot());
        line("cava.native.enabled: " + (forceDisabled ? "false (命令行强制)" : System.getProperty(CavaNative.PROP_ENABLED, "(未设置 → 默认 true)")));

        // ---- 步骤 2：Java 侧布局（永远可跑，不需要原生库） ----
        line("");
        line("--- [2] Java 侧结构体布局（手写 MemoryLayout，偏移由 byteOffset 算出） ---");
        for (String p : CavaLayouts.checkAgainstCAbi()) {
            line("  !! Java 侧布局与 cava_abi.h 期望不符: " + p);
            failures++;
        }
        LayoutCheck.JavaSide java = LayoutCheck.javaSide();
        System.out.print(java.fieldTable());
        line("layout_hash_sum(u32, 契约 2.3)      = 0x" + Long.toHexString(java.sumU32()) + "  (" + java.sumU32() + ")");
        line("layout_hash_sum(bytes, 头文件注释)  = 0x" + Long.toHexString(java.sumBytes()) + "  (" + java.sumBytes() + ")");

        // ---- 打开 ----
        CavaNative native_ = CavaNative.get();
        native_.configure(Path.of(System.getProperty("user.dir", ".")), "selftest");
        boolean open = native_.tryOpen();

        if (!open) {
            // ---- 步骤 5：优雅失败 ----
            line("");
            line("--- [5] 原生库不可用 → 优雅失败路径 ---");
            line("status            : " + native_.status());
            line("detail            : " + native_.detail());
            line("libraryInfo       : " + native_.libraryInfo());
            line("LOAD_FAILED path  : 已走到（原生未打开，未执行 cava_open / cava_d2i_sat）");
            line("回退语义          : Numeric 走纯 Java 饱和转换，钩子完全不介入");
            line("Numeric.d2iSat(1e300)  = " + Numeric.d2iSat(1e300));
            line("Numeric.d2iSat(-1e300) = " + Numeric.d2iSat(-1e300));
            line("Numeric.d2iSat(NaN)    = " + Numeric.d2iSat(Double.NaN));
            line("Numeric.d2lSat(1e300)  = " + Numeric.d2lSat(1e300));
            line("Numeric.bitsOfDouble(-0.0) = 0x" + Long.toHexString(Numeric.bitsOfDouble(-0.0)));
            line("");
            line("SELF-TEST: SOFT-FAIL (" + native_.status() + ") —— 不是崩溃，exit 0");
            System.exit(0);
            return;
        }

        // ---- 步骤 1：原生符号 ----
        CavaBindings b = native_.bindings();
        line("");
        line("--- [1] 原生符号 ---");
        line("cava_build_id()   : " + b.buildId());
        line("cava_abi_version(): " + b.abiVersion());
        line("cava_abi_touch()  : " + b.abiTouch() + "  (再次调用: " + b.abiTouch() + ")");

        // ---- 步骤 3：cava_open ----
        line("");
        line("--- [3] cava_open ---");
        line("status            : " + native_.status());
        line("handle            : " + native_.handle());
        line("java_layout_sum   : 0x" + Long.toHexString(native_.javaLayoutSum())
                + "  (bytes 变体 0x" + Long.toHexString(native_.javaLayoutSumBytes()) + ")");
        line("native_layout_sum : 0x" + Long.toHexString(native_.nativeLayoutSum()));
        line("layout 变体       : " + (native_.layoutByteWiseVariant() ? "byte-wise（协商）" : "u32（契约 2.3）"));
        line("库文件            : " + native_.libraryInfo());

        // ---- 步骤 4：数值差分 ----
        line("");
        line("--- [4] cava_d2i_sat / cava_d2l_sat / 位模式 与 Java 语义逐点差分 ---");
        double[] probes = {
                0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 1.9, -1.9,
                2147483647.0, 2147483648.0, -2147483648.0, -2147483649.0,
                1e300, -1e300, Double.MAX_VALUE, -Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
                9.223372036854776E18, -9.223372036854776E18, 1.0E19, -1.0E19
        };
        for (double v : probes) {
            int ni = b.d2iSat(v);
            int ji = (int) v;
            long nl = b.d2lSat(v);
            long jl = (long) v;
            boolean ok = ni == ji && nl == jl;
            if (!ok) {
                failures++;
            }
            line(String.format(Locale.ROOT, "  %-24s d2i native=%-12d java=%-12d %s | d2l native=%-21d java=%-21d %s",
                    fmt(v), ni, ji, ni == ji ? "ok" : "MISMATCH", nl, jl, nl == jl ? "ok" : "MISMATCH"));
        }
        double[] bitsProbes = {0.0, -0.0, 1.0, -1.5, Math.PI, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE};
        for (double v : bitsProbes) {
            long nbits = b.bitsOfDouble(v);
            long jbits = Double.doubleToRawLongBits(v);
            double round = b.doubleOfBits(nbits);
            boolean ok = nbits == jbits && Double.doubleToRawLongBits(round) == jbits;
            if (!ok) {
                failures++;
            }
            line(String.format(Locale.ROOT, "  bits(%-22s) native=0x%016X java=0x%016X roundtrip=%s %s",
                    fmt(v), nbits, jbits, fmt(round), ok ? "ok" : "MISMATCH"));
        }

        line("");
        line("layout 明细（原生报告逐字段核对通过后打印 Java 侧表）:");
        System.out.println(native_.layoutDetail());
        line("SELF-TEST: " + (failures == 0 ? "PASS" : "FAIL (" + failures + " 项不一致)"));
        System.exit(failures == 0 ? 0 : 3);
    }

    private static String fmt(double v) {
        return Double.toString(v);
    }

    private static String orNone(String v) {
        return v == null || v.isEmpty() ? "(未设置)" : v;
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
