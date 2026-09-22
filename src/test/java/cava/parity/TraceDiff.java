package cava.parity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 黄金轨迹离线比对器（契约 4.3，任务 C8）。**不进 jar**（放在 src/test/java）。
 *
 * <p>命令行：
 * <pre>
 * java -cp &lt;test-classes&gt; cava.parity.TraceDiff &lt;a.ndjson&gt; &lt;b.ndjson&gt;
 * </pre>
 * 零差异打印 {@code ZERO DIFF over N ticks}（exit 0）；有差异打印首个差异 tick 与字段（exit 1）。
 *
 * <p>只依赖 JDK：现场手写的行解析器，不引 JSON 库（P0 不加依赖）。
 */
public final class TraceDiff {

    private TraceDiff() {
    }

    /** 轨迹头。 */
    public record Header(String label, String mods, String mc, String cava, String nativeOn, String seed, String startTick) {
    }

    /** 一条 tick 行。 */
    public record Tick(long k, String w, String e, String p, String bt, String nt, String x) {
    }

    /** 比对结果。 */
    public static final class Result {
        private final Path a;
        private final Path b;
        private final Header ha;
        private final Header hb;
        private final long ticksCompared;
        private final long aLines;
        private final long bLines;
        private final long firstDiffTick;
        private final Map<String, Long> diffCounts;
        private final String firstDiffDetail;
        private final List<String> headerNotes;

        Result(Path a, Path b, Header ha, Header hb, long ticksCompared, long aLines, long bLines,
               long firstDiffTick, Map<String, Long> diffCounts, String firstDiffDetail, List<String> headerNotes) {
            this.a = a;
            this.b = b;
            this.ha = ha;
            this.hb = hb;
            this.ticksCompared = ticksCompared;
            this.aLines = aLines;
            this.bLines = bLines;
            this.firstDiffTick = firstDiffTick;
            this.diffCounts = diffCounts;
            this.firstDiffDetail = firstDiffDetail;
            this.headerNotes = headerNotes;
        }

        public boolean zeroDiff() {
            return firstDiffTick < 0 && aLines == bLines && headerNotes.isEmpty();
        }

        public long ticksCompared() {
            return ticksCompared;
        }

        public long firstDiffTick() {
            return firstDiffTick;
        }

        public Map<String, Long> diffCounts() {
            return diffCounts;
        }

        /** 人读报告。 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            if (zeroDiff()) {
                sb.append("ZERO DIFF over ").append(ticksCompared).append(" ticks");
                sb.append("  [a=").append(ha.label()).append(" native=").append(ha.nativeOn())
                        .append(" | b=").append(hb.label()).append(" native=").append(hb.nativeOn()).append(']');
                return sb.toString();
            }
            sb.append("DIFF FOUND over ").append(ticksCompared).append(" ticks");
            sb.append("  [a=").append(a.getFileName()).append(" label=").append(ha.label()).append(" native=").append(ha.nativeOn());
            sb.append(" | b=").append(b.getFileName()).append(" label=").append(hb.label()).append(" native=").append(hb.nativeOn()).append(']');
            sb.append(System.lineSeparator());
            if (firstDiffTick >= 0) {
                sb.append("  首个差异 tick=").append(firstDiffTick);
                sb.append(System.lineSeparator()).append("    ").append(firstDiffDetail);
            }
            if (aLines != bLines) {
                sb.append(System.lineSeparator()).append("  tick 行数不同（不含头行）: a=").append(aLines).append(" b=").append(bLines);
            }
            for (String note : headerNotes) {
                sb.append(System.lineSeparator()).append("  头行差异: ").append(note);
            }
            if (!diffCounts.isEmpty()) {
                sb.append(System.lineSeparator()).append("  逐字段差异 tick 数:");
                for (Map.Entry<String, Long> e : diffCounts.entrySet()) {
                    sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
                }
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return report();
        }
    }

    /** 比对两个 NDJSON 轨迹文件。 */
    public static Result diff(Path a, Path b) throws IOException {
        List<String> la = Files.readAllLines(a, StandardCharsets.UTF_8);
        List<String> lb = Files.readAllLines(b, StandardCharsets.UTF_8);
        Header ha = la.isEmpty() ? null : parseHeader(la.get(0));
        Header hb = lb.isEmpty() ? null : parseHeader(lb.get(0));
        List<String> headerNotes = compareHeaders(ha, hb);

        List<Tick> ta = new ArrayList<>();
        for (int i = 1; i < la.size(); i++) {
            if (!la.get(i).isBlank()) {
                ta.add(parseTick(la.get(i)));
            }
        }
        List<Tick> tb = new ArrayList<>();
        for (int i = 1; i < lb.size(); i++) {
            if (!lb.get(i).isBlank()) {
                tb.add(parseTick(lb.get(i)));
            }
        }

        long firstDiff = -1;
        String firstDetail = "";
        Map<String, Long> counts = new LinkedHashMap<>();
        int n = Math.min(ta.size(), tb.size());
        for (int i = 0; i < n; i++) {
            Tick x = ta.get(i);
            Tick y = tb.get(i);
            StringBuilder detail = new StringBuilder();
            List<String> diffFields = new ArrayList<>();
            if (x.k() != y.k()) {
                diffFields.add("k");
                detail.append("k: ").append(x.k()).append(" != ").append(y.k()).append("; ");
            }
            diffFields.addAll(compareField("w", x.w(), y.w(), detail));
            diffFields.addAll(compareField("e", x.e(), y.e(), detail));
            diffFields.addAll(compareField("p", x.p(), y.p(), detail));
            diffFields.addAll(compareField("bt", x.bt(), y.bt(), detail));
            diffFields.addAll(compareField("nt", x.nt(), y.nt(), detail));
            diffFields.addAll(compareField("x", x.x(), y.x(), detail));
            if (!diffFields.isEmpty()) {
                if (firstDiff < 0) {
                    firstDiff = x.k();
                    firstDetail = "tick " + x.k() + " 字段[" + String.join(",", diffFields) + "]  " + detail;
                }
                for (String f : diffFields) {
                    counts.merge(f, 1L, Long::sum);
                }
            }
        }
        return new Result(a, b, ha, hb, n, ta.size(), tb.size(), firstDiff, counts, firstDetail, headerNotes);
    }

    private static List<String> compareField(String name, String x, String y, StringBuilder detail) {
        if (java.util.Objects.equals(x, y)) {
            return List.of();
        }
        detail.append(name).append(": '").append(x).append("' != '").append(y).append("'; ");
        return List.of(name);
    }

    private static List<String> compareHeaders(Header a, Header b) {
        List<String> notes = new ArrayList<>();
        if (a == null || b == null) {
            notes.add("有一侧没有头行");
            return notes;
        }
        // label 与 native 本来就该不同：只提示，不算差异
        if (!java.util.Objects.equals(a.mods(), b.mods())) {
            notes.add("mods 指纹不同 a=" + a.mods() + " b=" + b.mods());
        }
        if (!java.util.Objects.equals(a.mc(), b.mc())) {
            notes.add("mc 版本不同 a=" + a.mc() + " b=" + b.mc());
        }
        if (!java.util.Objects.equals(a.cava(), b.cava())) {
            notes.add("cava 版本不同 a=" + a.cava() + " b=" + b.cava());
        }
        if (!java.util.Objects.equals(a.seed(), b.seed())) {
            notes.add("seed 不同 a=" + a.seed() + " b=" + b.seed());
        }
        if (!java.util.Objects.equals(a.startTick(), b.startTick())) {
            notes.add("startTick 不同 a=" + a.startTick() + " b=" + b.startTick());
        }
        return notes;
    }

    /** 解析头行（{@code t=h}）。 */
    public static Header parseHeader(String line) {
        return new Header(raw(line, "label"), raw(line, "mods"), raw(line, "mc"), raw(line, "cava"),
                raw(line, "native"), raw(line, "seed"), raw(line, "startTick"));
    }

    /** 解析 tick 行（{@code t=k}）。 */
    public static Tick parseTick(String line) {
        return new Tick(num(raw(line, "k")), raw(line, "w"), raw(line, "e"), raw(line, "p"),
                raw(line, "bt"), raw(line, "nt"), raw(line, "x"));
    }

    private static long num(String s) {
        return s == null ? -1 : Long.parseLong(s);
    }

    /**
     * 取 {@code "key":} 之后的值：字符串去引号，{@code null} 返回 null，数字/布尔原样返回。
     * 手写就够——schema 是固定的（契约 4.2）。
     */
    static String raw(String line, String key) {
        String needle = "\"" + key + "\":";
        int i = line.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int p = i + needle.length();
        while (p < line.length() && line.charAt(p) == ' ') {
            p++;
        }
        if (p >= line.length()) {
            return null;
        }
        if (line.startsWith("null", p)) {
            return null;
        }
        char c = line.charAt(p);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            for (int q = p + 1; q < line.length(); q++) {
                char ch = line.charAt(q);
                if (ch == '\\') {
                    char e = line.charAt(++q);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(e);
                    }
                } else if (ch == '"') {
                    return sb.toString();
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }
        int q = p;
        while (q < line.length() && ",} ".indexOf(line.charAt(q)) < 0) {
            q++;
        }
        return line.substring(p, q);
    }

    /** CLI 入口。 */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("用法: TraceDiff <a.ndjson> <b.ndjson>");
            System.exit(2);
            return;
        }
        Result r = diff(Path.of(args[0]), Path.of(args[1]));
        System.out.println(r.report());
        System.exit(r.zeroDiff() ? 0 : 1);
    }
}
