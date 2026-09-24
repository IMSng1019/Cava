package cava.hook;

/**
 * P1 寻路钩子的全部开关（系统属性）。**本类不引用任何 Minecraft 类型**，因此可单测。
 *
 * <table>
 *   <caption>开关</caption>
 *   <tr><th>属性</th><th>默认</th><th>含义</th></tr>
 *   <tr><td>{@code cava.pathfind.hook}</td><td>true</td>
 *       <td>注入体总开关。false = handler 第一句就 return，**完全不介入**（契约 §3 的回退语义）。</td></tr>
 *   <tr><td>{@code cava.pathfind.native}</td><td><b>false</b></td>
 *       <td>是否允许原生接管。默认关是 captain 的 P1 门禁：{@code cava_pathfind} 目前保守返回
 *           {@code CAVA_ERR_UNIMPLEMENTED}，且 {@code CAVA_PF_*} 位号对齐尚未由镜像流回执。
 *           位号错位时打开原生路径会产出"看起来正常但语义错"的路径，**运行期无法发现**。</td></tr>
 *   <tr><td>{@code cava.pathfind.probe}</td><td>true</td>
 *       <td>SERVER_STARTED（或首个 tick）后主动触发一次目标方法，断言金丝雀计数 +1。</td></tr>
 *   <tr><td>{@code cava.pathfind.probe.ticks}</td><td>600</td>
 *       <td>探测最长等待多少个服务端 tick（等世界/生物就绪）。超时 = 计数为 0 的失败路径。</td></tr>
 * </table>
 *
 * <p>为什么不把开关放进 {@code config/cava.json}：那需要改 {@code cava/CavaConfig.java}（不是本流的文件）。
 * 系统属性可以在**同一个 jar、同一个服务器目录**上做 A/B，这正是 P1 性能测量需要的形态。
 */
public final class PathfindSwitches {

    /** 注入体总开关。 */
    public static final String PROP_HOOK = "cava.pathfind.hook";
    /** 原生接管开关。 */
    public static final String PROP_NATIVE = "cava.pathfind.native";
    /** 启动金丝雀探测开关。 */
    public static final String PROP_PROBE = "cava.pathfind.probe";
    /** 金丝雀探测的最长等待 tick 数。 */
    public static final String PROP_PROBE_TICKS = "cava.pathfind.probe.ticks";
    // 说明：原来还有一个 -Dcava.pathfind.maxRegionBlocks（注入流自己算区域窗口时的体积上限）。
    // 契约加了 pushForSolve 之后**窗口策略归镜像侧**，注入流不再算窗口 ⇒ 该开关已删除。
    // 说明：原来这里有一个 -Dcava.mirror.class=<实现类名>（注入流靠反射按名字找实现）。
    // captain 2026-09-22 加了契约入口 cava.mirror.MirrorFactory 之后，**它已删除** ——
    // "靠反射猜实现类"被实测证明会伪装成"子系统缺失"（reasons={mirror-missing=20201}）。
    /**
     * **诊断开关（默认 false，不要在生产开）**：跳过镜像流的 {@code isProfileReadyForSolve} 门禁。
     *
     * <p>为什么需要它：镜像流的 {@code isFlagsReadyFor(caps)} 尚未就绪时会拒绝每一次求解
     * （实测 {@code reasons={profile-not-ready=20201}}），于是"区域推送 → 档案上传 →
     * {@code cava_pathfind} → 错误码回退"这条编排链路在真实服务端上**永远走不到**，无法验证。
     *
     * <p>打开它的**唯一**合法用途：在 {@code cava_pathfind} 仍保守返回
     * {@code CAVA_ERR_UNIMPLEMENTED}（因此不可能产出错误路径）时，拿到"编排真的调到了原生、
     * 并且按错误码正确回退"的实测证据。**位号对齐之后必须立刻关掉它** ——
     * 那时跳过门禁就会真的产出"看起来正常但语义错"的路径。开启时打 ERROR 日志。
     */
    public static final String PROP_BYPASS_PROFILE_GATE = "cava.pathfind.diagnostic.bypassProfileGate";

    /**
     * **窗口截断检测总开关**（默认 true，2026-09-24 P1-FIX 加）。
     *
     * <p>false = 完全不做窗口截断检测 ⇒ 原生会把"到最接近点"的截断路径当答案交回去
     * （实测 {@code detour128} 两腿不一致）。**只给可证伪对照用**，生产不要关。
     */
    public static final String PROP_WINDOW_GUARD = "cava.pathfind.window.guard";
    /**
     * "原生没抵达目标"时的处置策略（默认 {@code early-stop}，见 {@link WindowTruncationGuard}）：
     * {@code early-stop} = 只在"搜索停得太早"（路径步数 < 起点目标直线距离）时回退；
     * {@code always} = 一律回退（最保守，会把 maze63 那种**已实测逐字段一致**的预算耗尽场景也拖回 Java）；
     * {@code off} = 不回退（只计数）。
     */
    public static final String PROP_WINDOW_NOT_REACHED = "cava.pathfind.window.notReached";

    /**
     * **按规模分流的最小"起步距离"**（默认见 {@link #DEFAULT_GATE_MIN_BLOCKS}，P1-NET 流加）。
     *
     * <p>单位是**方块**，口径是起点→目标的 **3D 切比雪夫距离** {@code max(|dx|,|dy|,|dz|)}
     * （对能斜着走的陆地生物，这是"路径步数"的下界，O(1) 可算）。小于该值时**根本不调原生**：
     * 直接返回 null 让 Java 逻辑跑（不付任何原生的区域推送/档案上传/跨界成本）。
     *
     * <p>为什么需要它（实测出处见 {@code docs/CAVA-p1-net-notes.md}）：短程搜索原生本来就慢
     * （1 节点场景 0.09x–0.62x），而真实 AI 负载里短程调用占比很高 ⇒ 不分流时"打开开关"是净亏。
     *
     * <p>值为 {@code 0} = **关闭分流**（每个调用都尝试原生，这是修复后的原行为，保留给对照实验）。
     */
    public static final String PROP_GATE_MIN = "cava.pathfind.gate.minBlocks";

    /**
     * **回退归因诊断**（默认 false；P1-NET 加）：原生结果被窗口截断检测判回退时，
     * 等 Java 跑完之后把**两条路径逐字段比一次**并计数（{@code fallbackSame/fallbackDiff}）。
     *
     * <p>回答的是 P1-FIX §8.3 第 1 条留下的问题："≈30–50% 的回退里，有多少是必须回退、
     * 有多少是白回退"。开销只落在回退那一次调用上（两条路径各算一遍 FNV），默认关。
     */
    public static final String PROP_FALLBACK_COMPARE = "cava.pathfind.fallback.compare";

    /**
     * **成对对照诊断**（默认 false；P1-NET 加）：接管成功的那次调用**再跑一遍 Java**（把注入体当作不存在），
     * 于是"同一次调用、同一份地形、同一个 JIT 状态"下两条路的耗时与结果都能直接相减。
     *
     * <p>为什么需要它：跨腿比较（off 腿 vs on 腿）在这个负载上**噪声压过信号** ——
     * 实测纯 Java 的两条腿每 tick 寻路耗时 48.3 / 64.5 / 95.2 µs（**两倍**的离散度），
     * 而开关的效应只有几十 µs/tick ⇒ 用跨腿差值判符号是不可靠的。成对对照把噪声消掉。
     *
     * <p>代价：每个接管调用多跑一次 Java（腿的墙钟明显变长，但**配对差**不受影响）；
     * 金丝雀计数会因为内层重放调用翻倍（回执里如实可见）。
     */
    public static final String PROP_COMPARE_ALL = "cava.pathfind.diagnostic.compareAll";

    /** {@link #PROP_PROBE_TICKS} 的默认值。 */
    public static final int DEFAULT_PROBE_TICKS = 600;

    /**
     * {@link #PROP_GATE_MIN} 的默认值 = **0（不分流）**。
     *
     * <p>**数据出处：docs/CAVA-p1-net-notes.md §5**。结论是"按规模分流"在本负载上**不成立**：
     * 真实 AI 调用的距离 p50=7 / p90=10 / max=11 格，而净亏**不集中在短程**——
     * 逐距离桶的回退率是 23%/33%/33%/32%（≤2 / 3-4 / 5-8 / 9-16 格），几乎不随规模变化；
     * 阈值只能"砍掉一段前缀"，砍掉哪一段都不能把总账翻正（实测：任何 T 的净收益都在 −3 ~ −6 µs/tick）。
     * 所以默认值取 0（= 与已测行为一致），把"分流"留给**将来在别的负载上重新量**时用
     * （`-Dcava.pathfind.gate.minBlocks=<N>` 即可打开，不需要改代码）。
     */
    public static final long DEFAULT_GATE_MIN_BLOCKS = 0L;

    private PathfindSwitches() {
    }

    /** true = 注入体做事；false = 打完招呼立刻 return。 */
    public static boolean hookEnabled() {
        return readBoolean(PROP_HOOK, true);
    }

    /** true = 允许原生接管（默认 false，见类注释的门禁说明）。 */
    public static boolean nativeTakeoverEnabled() {
        return readBoolean(PROP_NATIVE, false);
    }

    /** true = 启动后主动跑一次金丝雀探测。 */
    public static boolean probeEnabled() {
        return readBoolean(PROP_PROBE, true);
    }

    public static int probeTicks() {
        return (int) readLong(PROP_PROBE_TICKS, DEFAULT_PROBE_TICKS);
    }

    /** 见 {@link #PROP_BYPASS_PROFILE_GATE}：**仅供诊断**。 */
    public static boolean bypassProfileGate() {
        return readBoolean(PROP_BYPASS_PROFILE_GATE, false);
    }

    private static boolean readBoolean(String key, boolean def) {
        return parseBoolean(System.getProperty(key), def);
    }

    /** true = 做窗口截断检测（默认 true）。 */
    public static boolean windowGuardEnabled() {
        return readBoolean(PROP_WINDOW_GUARD, true);
    }

    /** 见 {@link #PROP_COMPARE_ALL}（默认 false）。 */
    public static boolean compareAll() {
        return readBoolean(PROP_COMPARE_ALL, false);
    }

    /** 见 {@link #PROP_FALLBACK_COMPARE}（默认 false）。 */
    public static boolean fallbackCompare() {
        return readBoolean(PROP_FALLBACK_COMPARE, false);
    }

    /** {@link #PROP_GATE_MIN} 的当前值（方块；0 = 关闭分流）。 */
    public static long gateMinBlocks() {
        return parseLong(System.getProperty(PROP_GATE_MIN), DEFAULT_GATE_MIN_BLOCKS);
    }

    /** {@link #PROP_WINDOW_NOT_REACHED} 的策略串（非法值一律回默认 {@code early-stop}）。 */
    public static String windowNotReachedPolicy() {
        String v = System.getProperty(PROP_WINDOW_NOT_REACHED);
        if (v == null) {
            return "early-stop";
        }
        String t = v.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (t) {
            case "always", "off", "early-stop" -> t;
            default -> "early-stop";
        };
    }

    private static long readLong(String key, long def) {
        return parseLong(System.getProperty(key), def);
    }

    /** 与 {@code CavaNative.enabledByFlag()} 同款语义（false/0/no/off 一律假；空 = 默认值）。 */
    public static boolean parseBoolean(String raw, boolean def) {
        if (raw == null) {
            return def;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return def;
        }
        return !(v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no") || v.equalsIgnoreCase("off"));
    }

    /** 非法或空一律回默认值（**不抛异常**：开关解析绝不能让服务端起不来）。 */
    public static long parseLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 一行摘要，供启动横幅/日志。 */
    public static String describe() {
        return "hook=" + hookEnabled()
                + " native=" + nativeTakeoverEnabled()
                + " probe=" + probeEnabled()
                + " bypassProfileGate=" + bypassProfileGate()
                + " probeTicks=" + probeTicks()
                + " gateMinBlocks=" + gateMinBlocks()
                + " " + WindowTruncationGuard.describe();
    }
}
