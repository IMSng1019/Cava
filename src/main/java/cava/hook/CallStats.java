package cava.hook;

import java.util.Arrays;

/**
 * 一组 {@code long} 样本的分布统计（P1-NET 流加；**纯 Java、无 MC 类型 ⇒ 可单测**）。
 *
 * <p>为什么不用平均值：本流要回答的是"真实 AI 负载下打开开关净赚还是净亏"，
 * 而每次调用的耗时是**长尾分布**（短的几微秒、长的几毫秒）。只报 avg 会把"绝大多数调用很短"
 * 这件事藏起来，而分流阈值恰恰要按分布来选 ⇒ 必须同时有 p50/p90/p99 与桶直方图。
 *
 * <p>样本上限 {@link #MAX_SAMPLES}（32k）；超出部分只进 {@code sum/min/max} 与 {@code overflow} 计数，
 * 不进百分位 —— 本流的用量（真实 AI ≈0.22 次/tick）远达不到上限，超了也如实报 {@code overflow}。
 */
public final class CallStats {

    /** 保留的原始样本数上限（百分位只能从原始样本算）。 */
    public static final int MAX_SAMPLES = 32768;

    private final long[] samples = new long[MAX_SAMPLES];
    private int n;
    private long overflow;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;

    public synchronized void add(long v) {
        sum += v;
        if (v < min) {
            min = v;
        }
        if (v > max) {
            max = v;
        }
        if (n < MAX_SAMPLES) {
            samples[n++] = v;
        } else {
            overflow++;
        }
    }

    public synchronized void reset() {
        n = 0;
        overflow = 0;
        sum = 0;
        min = Long.MAX_VALUE;
        max = Long.MIN_VALUE;
    }

    /** 样本总数（含未保留原始值的溢出样本）。 */
    public synchronized long count() {
        return (long) n + overflow;
    }

    /** 未被保留原始值的样本数（{@code >0} ⇒ 百分位是"前 32k 个样本"的口径）。 */
    public synchronized long overflowCount() {
        return overflow;
    }

    /** 分位数（{@code q=0.5} = 中位数）；没有样本时返回 {@code -1}。 */
    public synchronized long percentile(double q) {
        if (n == 0) {
            return -1L;
        }
        long[] s = Arrays.copyOf(samples, n);
        Arrays.sort(s);
        int idx = (int) Math.round(q * (n - 1));
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= n) {
            idx = n - 1;
        }
        return s[idx];
    }

    public synchronized long min() {
        return count() == 0 ? -1L : min;
    }

    public synchronized long max() {
        return count() == 0 ? -1L : max;
    }

    public synchronized double avg() {
        long c = count();
        return c == 0 ? 0.0 : sum / (double) c;
    }

    public synchronized long sum() {
        return sum;
    }

    /**
     * 桶直方图：{@code edges} 升序，输出 {@code <e:c;...;+:c}（**无空格**，便于回执逐 token 解析）。
     * 桶的口径是 **{@code v < edge}**（标签就是"小于该边界"），最后一个桶是"大于等于最大边界"。
     */
    public synchronized String histogram(long[] edges) {
        long[] counts = new long[edges.length + 1];
        for (int i = 0; i < n; i++) {
            long v = samples[i];
            int b = 0;
            while (b < edges.length && v >= edges[b]) {
                b++;
            }
            counts[b]++;
        }
        counts[edges.length] += overflow;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < edges.length; i++) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append('<').append(edges[i]).append(':').append(counts[i]);
        }
        sb.append(";+:").append(counts[edges.length]);
        return sb.toString();
    }

    /**
     * 一行 {@code key=value}（**无空格、无逗号分隔的空格**，可直接被回执解析器逐 token 取）。
     *
     * @param prefix 前缀（如 {@code javaNs}）
     * @param scale  数值缩放（1 = 原值；1e-3 用于 ns→µs）
     * @param unit   单位后缀（可为空串）
     */
    public synchronized String fields(String prefix, double scale, String unit) {
        return prefix + "_n=" + count()
                + " " + prefix + "_p50=" + fmt(percentile(0.50) * scale) + unit
                + " " + prefix + "_p90=" + fmt(percentile(0.90) * scale) + unit
                + " " + prefix + "_p99=" + fmt(percentile(0.99) * scale) + unit
                + " " + prefix + "_max=" + fmt(max() * scale) + unit
                + " " + prefix + "_avg=" + fmt(avg() * scale) + unit;
    }

    private static String fmt(double v) {
        if (v < 0) {
            return "-1";
        }
        if (v >= 1000.0) {
            return Long.toString(Math.round(v));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
