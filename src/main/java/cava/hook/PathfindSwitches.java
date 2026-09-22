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

    /** {@link #PROP_PROBE_TICKS} 的默认值。 */
    public static final int DEFAULT_PROBE_TICKS = 600;

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
                + " probeTicks=" + probeTicks();
    }
}
