package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * **R6 机械对拍**：{@code tools/parity-perf-pathfind.ps1} 里"要比的字段名"与回执里"实际出现的键名"
 * 必须**逐名对齐**，少一个/多一个都红。
 *
 * <p>为什么必须机械对拍（这是本轮第三个缺陷的根因）：比对脚本曾经把键名写成
 * {@code reachesTargetFlag}，而 {@code PathfindPerfBench} 打出来的键是 {@code reachedTargetFlag}。
 * {@code Field()} 对不存在的键返回 {@code (缺)}，于是**两边都是 {@code (缺)}、两边相等** ⇒
 * 这个字段从来没被比过，而整体判定还是 CONSISTENT/绿。人读脚本发现不了"少比了一个字段"，
 * 只有把三张表与格式串做集合相等才能发现。
 *
 * <p>三条断言：
 * <ol>
 *   <li><b>脚本要比的每个字段都必须真的在回执里</b>（否则就是下一个 {@code reachesTargetFlag}）；</li>
 *   <li><b>回执里每个键都必须被分类</b>——要么在要比的表里，要么在"明确不比"的
 *       {@link #EXEMPT} 里且有理由。新增一个回执键却忘了分类 ⇒ 红（"多一个也要红"）；</li>
 *   <li>{@code src/test/resources/cava-receipt-sample.txt} 是**真实运行**落盘的回执原文
 *       （P1-NET 那一轮跑出来的），要比的字段在里面必须都能取到值 ⇒ 直接复现"这个字段
 *       到底比没比过"这件事，而不是只对着源码推。</li>
 * </ol>
 */
class ReceiptFieldParityTest {

    /** 要比的字段（脚本）：PERF 行。 */
    private static final String PERF_ARRAY = "perfFields";
    /** 要比的字段（脚本）：PERFDETAIL 行。 */
    private static final String DET_ARRAY = "detFields";
    /** 要比的字段（脚本）：PERFDETAIL 里的 {@code site:} 复合记号。 */
    private static final String SITE_ARRAY = "siteFields";

    /**
     * 回执里**允许不比**的键，每个都要有理由。{@code id/preset/mode/warmup} 是恒等常量或身份，
     * {@code ns_* / totalMs / setup_avg / upload_avg_ns / solve_avg_ns} 是**两腿预期不同**的性能量
     * （它们正是被测对象），{@code takeovers/fallbacks/gated/nativeCallsDelta/fb_shell…/mirror/mirrorReuse/blockHook}
     * 是 on 腿独有的过程计数（off 腿必然为 0 / -1），{@code changed} 是铺场景的副产物。
     */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<>();

    static {
        for (String k : new String[] {"id", "preset", "mode", "run", "warmup"}) {
            EXEMPT.put(k, "身份/恒等常量");
        }
        for (String k : new String[] {"ns_avg", "ns_p50", "ns_p95", "ns_p99", "ns_max", "ns_min",
                "setup_avg", "totalMs", "upload_avg_ns", "solve_avg_ns"}) {
            EXEMPT.put(k, "两腿预期不同的性能量（被测对象本身）");
        }
        for (String k : new String[] {"targetOffset", "takeovers", "fallbacks", "gated", "nativeCallsDelta",
                "fb_shell", "fb_goalShell", "fb_notReached", "fb_earlyStop", "fb_structural",
                "mirror", "mirrorReuse", "blockHook"}) {
            EXEMPT.put(k, "on 腿独有的过程计数（off 腿必然为 0/-1）；记账由 takeovers+fallbacks+gated==expectDelta 把关");
        }
        EXEMPT.put("changed", "铺场景的副产物（取决于这次铺设改了多少方块）");
        EXEMPT.put("reason", "只在 ok=false 的回执行里出现（失败路径用另一个 format 串）；正常腿看不到");
    }

    @Test
    void scriptFieldListsMatchReceiptKeys() throws IOException {
        String script = read(repoFile("tools/parity-perf-pathfind.ps1"));
        String java = read(repoFile("src/main/java/cava/hook/PathfindPerfBench.java"));

        Set<String> perfFields = psArray(script, PERF_ARRAY);
        Set<String> detFields = psArray(script, DET_ARRAY);
        Set<String> siteFields = psArray(script, SITE_ARRAY);
        assertFalse(perfFields.isEmpty(), "解析不出 " + PERF_ARRAY);
        assertFalse(detFields.isEmpty(), "解析不出 " + DET_ARRAY);
        assertFalse(siteFields.isEmpty(), "解析不出 " + SITE_ARRAY);

        Set<String> perfKeys = keysOf(extractFormat(java, "[cava/pathfind] PERF id=%d"));
        Set<String> detKeys = keysOf(extractFormat(java, "[cava/pathfind] PERFDETAIL id=%d"));
        Set<String> siteKeys = siteKeys(java);

        // (1) 脚本要比的字段必须真的存在（这一条就是 R6 那个 bug 的直接反例）
        assertEquals(new TreeSet<>(), minus(perfFields, perfKeys), "PERF 表里有回执里不存在的键名");
        assertEquals(new TreeSet<>(), minus(detFields, detKeys), "PERFDETAIL 表里有回执里不存在的键名");
        assertEquals(new TreeSet<>(), minus(siteFields, siteKeys), "site: 表里有回执里不存在的子键");

        // (2) 回执里每个键都必须被分类（多一个也要红）
        Set<String> compared = new LinkedHashSet<>();
        compared.addAll(perfFields);
        compared.addAll(detFields);
        compared.addAll(siteFields);
        Set<String> all = new LinkedHashSet<>();
        all.addAll(perfKeys);
        all.addAll(detKeys);
        all.addAll(siteKeys);
        Set<String> unclassified = minus(all, compared);
        unclassified.removeAll(EXEMPT.keySet());
        assertEquals(new TreeSet<>(), unclassified,
                "回执里出现了既没被比对、也没在 EXEMPT 里声明理由的键（新增字段必须补分类）");
        // 反向：EXEMPT 里不许留"回执里根本没有"的键（否则这张表会慢慢烂掉）
        assertEquals(new TreeSet<>(), minus(EXEMPT.keySet(), all), "EXEMPT 里有回执里不存在的键");
        // 同一个键不能既比又不比
        Set<String> both = new TreeSet<>(compared);
        both.retainAll(EXEMPT.keySet());
        assertEquals(new TreeSet<>(), both, "同一个键同时出现在「要比」与「不比」两张表里");
    }

    @Test
    void realReceiptSampleHasEveryComparedField() throws IOException {
        String sample = read(repoFile("src/test/resources/cava-receipt-sample.txt"));
        String script = read(repoFile("tools/parity-perf-pathfind.ps1"));
        Set<String> perfFields = psArray(script, PERF_ARRAY);
        Set<String> detFields = psArray(script, DET_ARRAY);
        Set<String> siteFields = psArray(script, SITE_ARRAY);

        String perf = firstLine(sample, "PERF id=");
        String detail = firstLine(sample, "PERFDETAIL id=");
        assertFalse(perf.isEmpty(), "样本里没有 PERF 行");
        assertFalse(detail.isEmpty(), "样本里没有 PERFDETAIL 行");

        List<String> missing = new ArrayList<>();
        for (String f : perfFields) {
            if (field(perf, f) == null) {
                missing.add("PERF." + f);
            }
        }
        for (String f : detFields) {
            if (field(detail, f) == null) {
                missing.add("PERFDETAIL." + f);
            }
        }
        for (String f : siteFields) {
            if (siteField(detail, f) == null) {
                missing.add("site:" + f);
            }
        }
        assertEquals(List.of(), missing, "真实回执样本里取不到这些字段（= 脚本比了个寂寞）");

        // 样本里不该出现"回执根本没有的键"（样本必须是这一版代码跑出来的）
        String java = read(repoFile("src/main/java/cava/hook/PathfindPerfBench.java"));
        Set<String> perfKeys = keysOf(extractFormat(java, "[cava/pathfind] PERF id=%d"));
        Set<String> detKeys = keysOf(extractFormat(java, "[cava/pathfind] PERFDETAIL id=%d"));
        // 反向只做**有界**检查：回执行的某些字段是 %s 填进去的"复合值"，它们自己又带 key=value
        // （如 windowGuard=..、env 里的 cacheChunks=..）——那些不是格式串的键，明确列出来而不是放宽成"随便"。
        Set<String> fromSubstitution = Set.of("windowGuard", "notReached", "cacheChunks", "cacheSpan", "world", "cache");
        Set<String> extraPerf = minus(keysOf(perf), perfKeys);
        extraPerf.removeAll(fromSubstitution);
        assertEquals(new TreeSet<>(), extraPerf, "样本 PERF 行里有源码格式串没有、也不是 %s 复合值的键");
        Set<String> extraDet = minus(keysOf(detail), detKeys);
        extraDet.removeAll(fromSubstitution);
        assertEquals(new TreeSet<>(), extraDet, "样本 PERFDETAIL 行里有源码格式串没有、也不是 %s 复合值的键");
    }

    // ------------------------------------------------------------------
    // 解析工具
    // ------------------------------------------------------------------

    private static Path repoFile(String rel) throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path p = dir.resolve(rel);
            if (Files.isRegularFile(p)) {
                return p;
            }
            dir = dir.getParent();
        }
        throw new IOException("从 user.dir=" + System.getProperty("user.dir") + " 往上 6 层都找不到 " + rel);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 把 PowerShell 里的 {@code $name = @('a','b',...)} 解析成集合。 */
    private static Set<String> psArray(String script, String name) {
        Matcher m = Pattern.compile("\\$" + name + "\\s*=\\s*@\\(([^)]*)\\)", Pattern.DOTALL).matcher(script);
        if (!m.find()) {
            return Set.of();
        }
        Set<String> out = new TreeSet<>();
        Matcher item = Pattern.compile("'([^']+)'").matcher(m.group(1));
        while (item.find()) {
            out.add(item.group(1));
        }
        return out;
    }

    /**
     * 从 Java 源码里把 {@code String.format} 的**格式串**拼出来。
     *
     * <p>做法：从 marker 所在的字面量开头往里走，只在双引号内取字符、跳过 {@code +} 与空白，
     * 遇到第一个"字面量之外的分号"（= 语句结束）就停。这样跨行拼接的相邻字面量会被正确合并，
     * 又不会把后面的实参列表（里面有 {@code ==} 之类的比较）吞进来。
     */
    private static String extractFormat(String java, String marker) {
        int at = java.indexOf(marker);
        assertTrue(at > 0, "源码里找不到格式串 " + marker);
        int start = java.lastIndexOf('"', at);
        StringBuilder sb = new StringBuilder();
        boolean inLit = false;
        for (int p = start; p < java.length(); p++) {
            char c = java.charAt(p);
            if (c == '"') {
                inLit = !inLit;
                continue;
            }
            if (!inLit) {
                if (c == ';') {
                    break;
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 与脚本里的 {@code Field()} 同口径：{@code (^|空白) key=值}。 */
    private static Set<String> keysOf(String formatString) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("(?:^|\\s)([A-Za-z_][A-Za-z0-9_]*)=").matcher(formatString);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** {@code site:} 复合记号里的子键（Field() 取不到：子键前面是冒号/逗号）。 */
    private static Set<String> siteKeys(String java) {
        String fmt = extractFormat(java, "[cava/pathfind] PERFDETAIL id=%d");
        Matcher m = Pattern.compile("site:(\\S+)").matcher(fmt);
        assertTrue(m.find(), "格式串里没有 site: 记号");
        Set<String> out = new TreeSet<>();
        Matcher sub = Pattern.compile("(?:^|,)([A-Za-z_][A-Za-z0-9_]*)=").matcher(m.group(1));
        while (sub.find()) {
            out.add(sub.group(1));
        }
        return out;
    }

    private static String field(String line, String key) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(key) + "=(\\S+)").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static String siteField(String line, String key) {
        Matcher m = Pattern.compile("site:(\\S+)").matcher(line);
        if (!m.find()) {
            return null;
        }
        Matcher sub = Pattern.compile("(?:^|,)" + Pattern.quote(key) + "=([^,]+)").matcher(m.group(1));
        return sub.find() ? sub.group(1) : null;
    }

    /** 文件里第一行含 marker 的行（回执行原文）。 */
    private static String firstLine(String text, String marker) {
        for (String line : text.split("\\R")) {
            if (line.contains(marker)) {
                return line.trim();
            }
        }
        return "";
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }
}
