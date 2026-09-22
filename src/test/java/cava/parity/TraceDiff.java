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

    /**
     * 轨迹头。前 7 个键来自契约 4.2；后 5 个是 {@code GoldenTrace.Meta} 追加的**排除范围**
     * （captain 要求：排除范围必须写进 trace 头，否则"零差异"不可复现）。
     * 老 trace 没有后 5 个键 → 取到 null → 按契约默认（排除 entities / 排除出生点 3 区块）处理。
     */
    public record Header(String label, String mods, String mc, String cava, String nativeOn, String seed,
                         String startTick, String radius, String spawnExcl, String excl, String incl, String hash) {

        /** 默认不参与比对的字段（契约 4.2：默认排除 entities）。 */
        public boolean excludesEntities() {
            return excludes("entities");
        }

        /** {@code excl} 里是否点名了该字段。 */
        public boolean excludes(String field) {
            return contains(excl, field);
        }

        /** {@code incl} 里是否点名了该字段（点名即覆盖 excl）。 */
        public boolean includes(String field) {
            return contains(incl, field);
        }

        private static boolean contains(String list, String field) {
            if (list == null || list.isBlank() || "null".equals(list)) {
                return false;
            }
            for (String p : list.split(",")) {
                if (p.trim().equalsIgnoreCase(field)) {
                    return true;
                }
            }
            return false;
        }
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
        /** 本 tick 字段选择（哪些字段被比、哪些被头行的 excl 排除）。 */
        private final List<String> fieldSelection;
        /** 差异定位（来自 detail-*.ndjson）：实体键 / 区块坐标。 */
        private final List<String> localization;

        Result(Path a, Path b, Header ha, Header hb, long ticksCompared, long aLines, long bLines,
               long firstDiffTick, Map<String, Long> diffCounts, String firstDiffDetail, List<String> headerNotes,
               List<String> fieldSelection, List<String> localization) {
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
            this.fieldSelection = fieldSelection;
            this.localization = localization;
        }

        /** 字段选择说明（报告里必须打印：不然"零差异"可能只是因为比了个空集）。 */
        public List<String> fieldSelection() {
            return fieldSelection;
        }

        /** 差异定位（实体键 / 方块坐标）。 */
        public List<String> localization() {
            return localization;
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
                // **零差异时更要把"比了什么"打出来**：不然"零差异"可能只是因为比了个空集
                for (String line : fieldSelection) {
                    sb.append(System.lineSeparator()).append("  ").append(line);
                }
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
            for (String line : localization) {
                sb.append(System.lineSeparator()).append("  ").append(line);
            }
            for (String line : fieldSelection) {
                sb.append(System.lineSeparator()).append("  ").append(line);
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return report();
        }
    }

    /** 比对两个 NDJSON 轨迹文件（按头行的排除范围，默认排除 entities）。 */
    public static Result diff(Path a, Path b) throws IOException {
        return diff(a, b, null);
    }

    /**
     * 比对两个 NDJSON 轨迹文件。
     *
     * @param entitiesOverride {@code null} = 按头行的 excl/incl；{@code TRUE}/{@code FALSE} = 强制
     *                         （{@code --entities} / {@code --no-entities}，用于"我就是要看 e"的排查）
     */
    public static Result diff(Path a, Path b, Boolean entitiesOverride) throws IOException {
        List<String> la = Files.readAllLines(a, StandardCharsets.UTF_8);
        List<String> lb = Files.readAllLines(b, StandardCharsets.UTF_8);
        Header ha = la.isEmpty() ? null : parseHeader(la.get(0));
        Header hb = lb.isEmpty() ? null : parseHeader(lb.get(0));
        List<String> headerNotes = compareHeaders(ha, hb);

        // ---- 字段选择：契约 4.2 的"默认排除 entities"，且**必须打印出来** ----
        boolean compareEntities;
        String why;
        if (entitiesOverride != null) {
            compareEntities = entitiesOverride;
            why = "命令行强制（--entities/--no-entities）";
        } else if (ha != null && ha.includes("entities")) {
            compareEntities = true;
            why = "头行 incl 点名";
        } else if (ha != null && ha.excludesEntities()) {
            compareEntities = false;
            why = "头行 excl=entities（契约 4.2 默认：实体层本质不确定，必须用脚本化合成场景）";
        } else {
            compareEntities = false;
            why = "头行没有 excl/incl（旧 trace）→ 按契约默认排除 entities";
        }
        List<String> fieldSelection = new ArrayList<>();
        fieldSelection.add("比对字段: k,w,p,bt,nt,x" + (compareEntities ? ",e" : "（**e 未参与比对**：" + why + "）"));
        if (ha != null) {
            fieldSelection.add("头行范围: radius=" + ha.radius() + " spawnExcl=" + ha.spawnExcl()
                    + " excl=" + ha.excl() + " incl=" + ha.incl());
            fieldSelection.add("哈希定义: " + ha.hash());
        }

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
            if (compareEntities) {
                diffFields.addAll(compareField("e", x.e(), y.e(), detail));
            }
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
        List<String> localization = firstDiff < 0 ? List.of() : localize(a, b, firstDiff);
        return new Result(a, b, ha, hb, n, ta.size(), tb.size(), firstDiff, counts, firstDetail, headerNotes,
                fieldSelection, localization);
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
                raw(line, "native"), raw(line, "seed"), raw(line, "startTick"),
                raw(line, "radius"), raw(line, "spawnExcl"), raw(line, "excl"), raw(line, "incl"), raw(line, "hash"));
    }

    // ------------------------------------------------------------------
    // 差异定位：读同名 detail-<label>.ndjson，给出"哪个实体键 / 哪个区块坐标"
    // ------------------------------------------------------------------

    /** {@code trace-<label>.ndjson} → {@code detail-<label>.ndjson}（同一个目录）。 */
    public static Path detailSibling(Path trace) {
        String name = trace.getFileName().toString();
        String detail = name.startsWith("trace-") ? "detail-" + name.substring("trace-".length()) : ("detail-" + name);
        return trace.resolveSibling(detail);
    }

    /** 头 3 行 + 首个差异 tick 的实体/区块定位（detail 不存在时如实说明）。 */
    private static List<String> localize(Path a, Path b, long tick) {
        List<String> out = new ArrayList<>();
        Path da = detailSibling(a);
        Path db = detailSibling(b);
        if (!Files.isRegularFile(da) || !Files.isRegularFile(db)) {
            out.add("定位：没有 detail 明细文件（" + da.getFileName() + " / " + db.getFileName()
                    + "）⇒ 只能给到 tick 与子系统；重采时加 -Dcava.parity.detail=true 可定位到实体键/区块坐标");
            return out;
        }
        try {
            List<String> la = Files.readAllLines(da, StandardCharsets.UTF_8);
            List<String> lb = Files.readAllLines(db, StandardCharsets.UTF_8);
            for (long t = tick; t >= Math.max(0, tick - 1); t--) {
                String lineA = findTick(la, t);
                String lineB = findTick(lb, t);
                if (lineA == null || lineB == null) {
                    continue;
                }
                diffGroup(out, t, "实体", parseGroups(lineA, "ent", 3), parseGroups(lineB, "ent", 3));
                diffGroup(out, t, "路径", parseGroups(lineA, "paths", 3), parseGroups(lineB, "paths", 3));
                // dc 是 [区块键, 哈希] 两元组
                diffGroup(out, t, "区块", parseGroups(lineA, "dc", 2), parseGroups(lineB, "dc", 2));
            }
        } catch (IOException e) {
            out.add("定位：读 detail 失败 " + e);
        }
        if (out.isEmpty()) {
            out.add("定位：tick " + tick + "（及其前一 tick）的 detail 明细两侧一致 ⇒ 差异来自更早的累积或明细未覆盖的量");
        }
        return out;
    }

    private static String findTick(List<String> lines, long tick) {
        String needle = "\"k\":" + tick + ",";
        for (String l : lines) {
            if (l.contains("\"t\":\"d\"") && l.contains(needle)) {
                return l;
            }
        }
        return null;
    }

    private static void diffGroup(List<String> out, long tick, String what,
                                  List<String[]> a, List<String[]> b) {
        java.util.Map<String, String[]> ma = new java.util.LinkedHashMap<>();
        for (String[] r : a) {
            ma.put(r[0], r);
        }
        java.util.Map<String, String[]> mb = new java.util.LinkedHashMap<>();
        for (String[] r : b) {
            mb.put(r[0], r);
        }
        int shown = 0;
        for (java.util.Map.Entry<String, String[]> e : ma.entrySet()) {
            String[] rb = mb.get(e.getKey());
            if (rb == null) {
                out.add("定位：tick " + tick + " " + what + " 只在 a 出现: " + e.getKey() + " " + note(e.getValue()));
                shown++;
            } else if (!e.getValue()[1].equals(rb[1])) {
                out.add("定位：tick " + tick + " " + what + " " + e.getKey() + " 哈希不同  a=" + e.getValue()[1] + " "
                        + note(e.getValue()) + "  b=" + rb[1] + " " + note(rb));
                shown++;
            }
            if (shown >= 8) {
                return;
            }
        }
        for (java.util.Map.Entry<String, String[]> e : mb.entrySet()) {
            if (!ma.containsKey(e.getKey())) {
                out.add("定位：tick " + tick + " " + what + " 只在 b 出现: " + e.getKey() + " " + note(e.getValue()));
                shown++;
                if (shown >= 8) {
                    return;
                }
            }
        }
    }

    private static String note(String[] group) {
        return group.length > 2 ? group[2] : "";
    }

    /** 解析 {@code "key":[[a,b,c],[a,b,c]]}（值都是 JSON 字符串）。 */
    static List<String[]> parseGroups(String line, String key, int width) {
        List<String[]> out = new ArrayList<>();
        String needle = "\"" + key + "\":[";
        int i = line.indexOf(needle);
        if (i < 0) {
            return out;
        }
        int p = i + needle.length();
        while (p < line.length()) {
            while (p < line.length() && (line.charAt(p) == ',' || line.charAt(p) == ' ')) {
                p++;
            }
            if (p >= line.length() || line.charAt(p) == ']') {
                break;
            }
            if (line.charAt(p) != '[') {
                break;
            }
            p++;
            String[] triple = new String[width];
            for (int k = 0; k < width; k++) {
                while (p < line.length() && line.charAt(p) == ' ') {
                    p++;
                }
                if (p >= line.length() || line.charAt(p) != '"') {
                    return out;
                }
                StringBuilder sb = new StringBuilder();
                p++;
                while (p < line.length() && line.charAt(p) != '"') {
                    if (line.charAt(p) == '\\' && p + 1 < line.length()) {
                        p++;
                    }
                    sb.append(line.charAt(p++));
                }
                p++;
                triple[k] = sb.toString();
                while (p < line.length() && (line.charAt(p) == ',' || line.charAt(p) == ' ')) {
                    p++;
                }
            }
            out.add(triple);
            if (p < line.length() && line.charAt(p) == ']') {
                p++;
            }
        }
        return out;
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

    /**
     * CLI 入口。
     *
     * <pre>
     * java -cp &lt;test-classes&gt; cava.parity.TraceDiff &lt;a.ndjson&gt; &lt;b.ndjson&gt; [--entities|--no-entities]
     * </pre>
     * 零差异打印 {@code ZERO DIFF over N ticks}（exit 0）；有差异打印首个差异 tick / 子系统 / 实体键 / 区块坐标（exit 1）。
     */
    public static void main(String[] args) throws IOException {
        List<String> pos = new ArrayList<>();
        Boolean entities = null;
        for (String a : args) {
            switch (a) {
                case "--entities" -> entities = Boolean.TRUE;
                case "--no-entities" -> entities = Boolean.FALSE;
                default -> pos.add(a);
            }
        }
        if (pos.size() < 2) {
            System.out.println("用法: TraceDiff <a.ndjson> <b.ndjson> [--entities|--no-entities]");
            System.exit(2);
            return;
        }
        Result r = diff(Path.of(pos.get(0)), Path.of(pos.get(1)), entities);
        System.out.println(r.report());
        System.exit(r.zeroDiff() ? 0 : 1);
    }
}
