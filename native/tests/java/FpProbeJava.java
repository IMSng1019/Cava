import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FpProbeJava —— 拿 native/tests/vectors/fp_probe.txt 里的输入向量，在 JVM 上重算
 * + - * / 与 Math.sqrt，和原生侧的结果**逐位**比对。
 *
 * 这是 P0-B「数值一致性探针」的 Java 侧对照物：
 *   - 原生侧：native/tests/cava_fp_probe.cpp（固定输入，MinGW 编译）
 *   - Java 侧：本文件（JDK 21，非预览，单文件源码启动即可）
 *
 * 用法：
 *   "C:\Program Files\Java\jdk-21\bin\java.exe" native/tests/java/FpProbeJava.java native/tests/vectors/fp_probe.txt
 *
 * 注意：必须用 doubleToRawLongBits（doubleToLongBits 会把 NaN 规范化，掩盖载荷差异）。
 * 退出码 0 = 无差异；1 = 有差异（会把前 20 条不符打出来）。
 */
public final class FpProbeJava {

    /** IEEE-754：指数全 1 且尾数非 0 => NaN。*/
    static boolean isNaNbits(long bits) {
        return ((bits >>> 52) & 0x7FFL) == 0x7FFL && (bits & 0x000FFFFFFFFFFFFFL) != 0L;
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args.length > 0 ? args[0] : "native/tests/vectors/fp_probe.txt");
        Map<String, long[]> stat = new TreeMap<>(); // op -> {count, mismatch}
        List<String> firstBad = new ArrayList<>();
        long total = 0;
        long bothNaNCount = 0;  // 不符且两个操作数都是 NaN
        long otherCount = 0;    // 不符但并非「双 NaN」（这类才是真正的危险信号）
        long nanClassDiff = 0;  // 连「是不是 NaN」都不一致的（最危险）

        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            String[] t = line.split("\\s+");
            if (t.length != 4) {
                throw new IllegalStateException("畸形行: " + raw);
            }
            String op = t[0];
            long aBits = Long.parseUnsignedLong(t[1], 16);
            long bBits = Long.parseUnsignedLong(t[2], 16);
            long expBits = Long.parseUnsignedLong(t[3], 16);

            double a = Double.longBitsToDouble(aBits);
            double b = Double.longBitsToDouble(bBits);
            double r;
            switch (op) {
                case "add":  r = a + b; break;
                case "sub":  r = a - b; break;
                case "mul":  r = a * b; break;
                case "div":  r = a / b; break;
                case "sqrt": r = Math.sqrt(a); break;
                default: throw new IllegalStateException("未知 op: " + op);
            }
            long gotBits = Double.doubleToRawLongBits(r);

            long[] s = stat.computeIfAbsent(op, k -> new long[2]);
            s[0]++;
            total++;
            if (gotBits != expBits) {
                s[1]++;
                boolean bothNaN = Double.isNaN(a) && Double.isNaN(b);
                boolean nanClassAgree = isNaNbits(gotBits) == isNaNbits(expBits);
                if (bothNaN) bothNaNCount++; else otherCount++;
                if (!nanClassAgree) nanClassDiff++;
                if (firstBad.size() < 20) {
                    firstBad.add(String.format("%s a=%016x b=%016x native=%016x java=%016x bothNaN=%s nanClassAgree=%s",
                            op, aBits, bBits, expBits, gotBits, bothNaN, nanClassAgree));
                }
            }
        }

        System.out.println("FpProbeJava: " + path);
        long mismatch = 0;
        for (Map.Entry<String, long[]> e : stat.entrySet()) {
            long[] s = e.getValue();
            mismatch += s[1];
            System.out.printf("  %-4s count=%-7d mismatch=%d%n", e.getKey(), s[0], s[1]);
        }
        if (!firstBad.isEmpty()) {
            System.out.println("  前若干条不符：");
            for (String b : firstBad) {
                System.out.println("    " + b);
            }
        }
        System.out.printf("总结果数 %d，差异 %d（双 NaN 操作数 %d / 其它 %d / NaN 类别不一致 %d）%n",
                total, mismatch, bothNaNCount, otherCount, nanClassDiff);
        if (mismatch == 0) {
            System.out.printf("ZERO BIT DIFF over %d results (+ - * / sqrt)%n", total);
            return;
        }
        System.out.println("RESULT: MISMATCH");
        System.exit(1);
    }
}
