package cava.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 每 tick 的「哪些实体被跳过、为什么」台账。
 *
 * <p>这是后续<b>逐 tick 差分的证据</b>：服务器上"某实体位置没变"既可能是
 * ServerCore 激活范围的预期行为，也可能是我们自己漏打包的 bug。
 * 台账把两者分开记录，并在 {@link EntityMirror#checkInactiveStability} 里
 * 与上一 tick 的镜像交叉核对。
 *
 * <p><b>只是台账，不驱动任何决策</b>：本轮（P2-Java，ABI 未冻结）实体路径只观测。
 *
 * <p><b>为什么要 {@link #snapshot()}</b>：本对象每 tick 被 {@link #begin(int)} 清空，
 * 所以"拿上一 tick 的台账"必须显式取快照。这样"先取快照再 pack"这种时序约束
 * 就不需要调用方记住 —— 拿不到快照就会编译不过。
 */
public final class TickSkipLedger {

    private static final int INITIAL = 64;

    private int tick = Integer.MIN_VALUE;
    private int[] ids = new int[INITIAL];
    private byte[] reasons = new byte[INITIAL];
    private int size;

    /** 按 {@link SkipReason#ordinal()} 计数的本 tick 统计。 */
    private final int[] counts = new int[SkipReason.values().length];

    /** 跨 tick 累计（只增不减，报告用）。 */
    private final long[] totalCounts = new long[SkipReason.values().length];

    private long totalRecords;

    /** 不可变快照：本 tick 被某个原因跳过的 id 集合（用于跨 tick 交叉核对）。 */
    public record Snapshot(int tick, SkipReason reason, int[] idsSorted, int[] allCounts) {

        public Snapshot {
            idsSorted = idsSorted.clone();
            allCounts = allCounts.clone();
        }

        public int size() {
            return idsSorted.length;
        }

        /** 二分查找（{@code idsSorted} 已排序）。 */
        public boolean contains(int entityId) {
            return Arrays.binarySearch(idsSorted, entityId) >= 0;
        }

        public int count(SkipReason r) {
            return allCounts[r.ordinal()];
        }
    }

    /** 开始一个新 tick：清空本 tick 的内容。 */
    public void begin(int newTick) {
        this.tick = newTick;
        this.size = 0;
        Arrays.fill(counts, 0);
    }

    /** 记一条跳过。 */
    public void record(int entityId, SkipReason reason) {
        if (size == ids.length) {
            int cap = ids.length * 2;
            ids = Arrays.copyOf(ids, cap);
            reasons = Arrays.copyOf(reasons, cap);
        }
        ids[size] = entityId;
        reasons[size] = (byte) reason.ordinal();
        size++;
        counts[reason.ordinal()]++;
        totalCounts[reason.ordinal()]++;
        totalRecords++;
    }

    public int tick() {
        return tick;
    }

    public int size() {
        return size;
    }

    public int id(int index) {
        return ids[index];
    }

    public SkipReason reason(int index) {
        return SkipReason.values()[reasons[index]];
    }

    public int count(SkipReason reason) {
        return counts[reason.ordinal()];
    }

    public long totalCount(SkipReason reason) {
        return totalCounts[reason.ordinal()];
    }

    public long totalRecords() {
        return totalRecords;
    }

    /** 本 tick 被该原因跳过的全部 id（未排序）。 */
    public List<Integer> idsWith(SkipReason reason) {
        List<Integer> out = new ArrayList<>(counts[reason.ordinal()]);
        for (int i = 0; i < size; i++) {
            if (reasons[i] == (byte) reason.ordinal()) {
                out.add(ids[i]);
            }
        }
        return out;
    }

    /** 取不可变快照（本 tick 内容；之后 {@link #begin(int)} 不会影响它）。 */
    public Snapshot snapshot(SkipReason reason) {
        int n = counts[reason.ordinal()];
        int[] picked = new int[n];
        int at = 0;
        for (int i = 0; i < size; i++) {
            if (reasons[i] == (byte) reason.ordinal()) {
                picked[at++] = ids[i];
            }
        }
        Arrays.sort(picked);
        return new Snapshot(tick, reason, picked, counts);
    }

    /** 一行报告（日志/轨迹用）。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("tick=").append(tick).append(" skipped=").append(size);
        for (SkipReason r : SkipReason.values()) {
            sb.append(' ').append(r.tag()).append('=').append(counts[r.ordinal()]);
        }
        return sb.toString();
    }
}
