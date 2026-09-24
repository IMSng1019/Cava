package cava.hook;

/**
 * **每 tick 服务端耗时的分布**（P1-NET 流加；纯 Java ⇒ 可单测）。
 *
 * <p>口径：把 {@code ServerTickEvents.END_SERVER_TICK} 相邻两次回调的 {@code System.nanoTime()} 差
 * 当作这一 tick 的耗时。为什么这个口径成立：
 * <ul>
 *   <li>回调在**服务端线程**上、每个 tick 恰好一次（fabric-lifecycle-events-v1，仓库里已在用）；</li>
 *   <li>差值 = 这一 tick 的 tick 体 + 一 tick 与下一 tick 之间的循环开销。测试用 {@code tick sprint}
 *       让服务端**不睡**地连续 tick（{@code tick freeze} 下根本不 tick），所以差值 ≈ tick 体本身；</li>
 *   <li>不冻结 tick（真实活 tick）时这个口径会把 50 ms 的等待算进去 —— 所以**必须**配合
 *       {@code tick sprint} 或在报告里说明。本流用 sprint + sprint 前 {@code reset}。</li>
 * </ul>
 *
 * <p>不合理的差值（{@code <=0} 或 {@code >}{@link #MAX_PLAUSIBLE_NS}）计入 {@code skipped} 而不是样本：
 * 冻结/解冻、区块加载、停服、GC 长暂停都会产生这种值，混进去会把 p99 完全带偏。
 */
public final class TickTimeRecorder {

    /** 超过这个值的间隔不算 tick 耗时（60 s；冻结/加载/停服窗口）。 */
    public static final long MAX_PLAUSIBLE_NS = 60_000_000_000L;

    /**
     * 超过这个值的间隔算"没在 sprint 的 tick"（45 ms；20 TPS 的 tick 体含 50 ms 睡眠）。
     *
     * <p>为什么需要它（实测）：{@code tick sprint} 跑完到脚本读到 TICKSTAT 之间隔着几秒的
     * 轮询/RCON 往返，那段时间服务端仍然以 20 TPS 在跑（冻结 tick 仍在 tick，只是世界不推进）
     * ⇒ 第一次实测里 2628 个样本中约 228 个是 50 ms 的"睡眠 tick"，把 p95 直接顶到 49.9 ms。
     * sprint 中的 tick 是 1–3 ms 量级，45 ms 这条线把它们干净分开；被滤掉的数量如实报在
     * {@code slowTicks} 里（**两条腿同一口径**）。
     */
    public static final long SLOW_TICK_NS = 45_000_000L;

    private static final TickTimeRecorder INSTANCE = new TickTimeRecorder();

    private final CallStats stats = new CallStats();
    private volatile Thread serverThread;
    private long lastEnd;
    private long skipped;
    private long slowTicks;

    private TickTimeRecorder() {
    }

    public static TickTimeRecorder get() {
        return INSTANCE;
    }

    /** 由 {@code END_SERVER_TICK} 调用（服务端线程）。 */
    public void onEndTick() {
        Thread t = Thread.currentThread();
        if (serverThread == null) {
            serverThread = t;
        }
        long now = System.nanoTime();
        long prev = lastEnd;
        lastEnd = now;
        if (prev == 0L) {
            return;   // 第一次只做锚点
        }
        long d = now - prev;
        if (d <= 0L || d > MAX_PLAUSIBLE_NS) {
            skipped++;
            return;
        }
        if (d > SLOW_TICK_NS) {
            slowTicks++;
            return;
        }
        stats.add(d);
    }

    /** 服务端线程（由本记录器判定；寻路的"是否在主线程上跑"用它做身份比较，不用线程名）。 */
    public Thread serverThread() {
        return serverThread;
    }

    public synchronized void reset() {
        stats.reset();
        skipped = 0;
        slowTicks = 0;
        lastEnd = 0L;
    }

    /** 被判为"没在 sprint"（间隔 {@code >}{@link #SLOW_TICK_NS}）的 tick 数。 */
    public long slowTicks() {
        return slowTicks;
    }

    public long ticks() {
        return stats.count();
    }

    public long skipped() {
        return skipped;
    }

    public CallStats stats() {
        return stats;
    }

    /** 一行回执（**无空格**，便于逐 token 解析）：毫秒为单位，3 位小数。 */
    public String report() {
        CallStats s = stats;
        double scale = 1.0e-6;
        return "TICKSTAT ticks=" + s.count()
                + " skipped=" + skipped
                + " slowTicks=" + slowTicks
                + " mspt_avg=" + f(s.avg() * scale)
                + " mspt_p50=" + f(s.percentile(0.50) * scale)
                + " mspt_p90=" + f(s.percentile(0.90) * scale)
                + " mspt_p95=" + f(s.percentile(0.95) * scale)
                + " mspt_p99=" + f(s.percentile(0.99) * scale)
                + " mspt_max=" + f(s.max() * scale)
                + " mspt_min=" + f(s.min() * scale)
                // 桶边界 = 1/2/4/8/16/32/64/128 ms（**单位是 ns**：CallStats 存的就是纳秒）
                + " histNs=" + s.histogram(new long[] {
                        1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
                        16_000_000L, 32_000_000L, 64_000_000L, 128_000_000L});
    }

    private static String f(double v) {
        return v < 0 ? "-1" : String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
