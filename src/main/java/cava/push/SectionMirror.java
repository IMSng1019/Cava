package cava.push;

import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * {@code SectionedEntityCache.trackedPositions} 的原生镜像。
 *
 * <p>原版 {@code forEachInBox} 对每个 x 列做一次 {@code LongAVLTreeSet.subSet(...)} 再逐元素过滤 y/z；
 * 在 forceload 大面积区块的服务器上，这个扫描量是"该 x 列的全部区段"，而真正命中的常常只有几个。
 * 镜像把这张有序表放进原生内存，由 {@code cava_push_section_plan} 一次算出**访问计划**
 * （输入数组的一个升序子序列 ⇒ 与原版访问顺序**逐一相同**）。
 *
 * <p><b>同步纪律</b>：镜像只在 {@code addSection/removeSection} 两处被改动（原版也只用这两处改
 * {@code trackedPositions}）。调用方每次用之前必须用 {@link #inSync(int)} 对账（O(1) 的 size 比对），
 * 不齐就 {@link #syncFrom} 重建并计数 —— 任何不确定都回退原版。
 *
 * <p><b>内存</b>：全部段都在同一个 {@link Arena} 里；扩容时旧段在同一 arena 内滞留到关闭为止
 * （每个世界一个镜像、扩容次数是 log 级，可接受；正式 ABI 需要 handle 化，见 notes 提案）。
 */
public final class SectionMirror {

    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;

    private final Arena arena = Arena.ofShared();
    private MemorySegment positions;
    private MemorySegment out;
    private MemorySegment box6;
    private MemorySegment outCount;
    private int count;
    private int cap;
    private int outCap;

    public SectionMirror(LongSortedSet src, int initialCap) {
        this.cap = Math.max(16, initialCap);
        this.outCap = 1024;
        this.positions = arena.allocateArray(LONG, cap);
        this.out = arena.allocateArray(LONG, outCap);
        this.box6 = arena.allocate(6 * 8L);
        this.outCount = arena.allocate(4);
        syncFrom(src);
    }

    public int size() {
        return count;
    }

    /** O(1) 对账：镜像条数必须等于原集合条数，否则一定有漂移。 */
    public boolean inSync(int expectedSize) {
        return count == expectedSize;
    }

    public void syncFrom(LongSortedSet src) {
        int n = src.size();
        ensureCap(n);
        int i = 0;
        for (long p : src) {                    // LongSortedSet 的迭代器 = **升序**
            positions.setAtIndex(LONG, i++, p);
        }
        count = i;
    }

    public void add(long pos) {
        int idx = lowerBound(pos);
        if (idx < count && positions.getAtIndex(LONG, idx) == pos) {
            return;                                 // 幂等：原版 add 到 LongSortedSet 也是幂等
        }
        ensureCap(count + 1);
        if (idx < count) {
            MemorySegment.copy(positions, (long) idx * 8, positions, (long) (idx + 1) * 8, (long) (count - idx) * 8);
        }
        positions.setAtIndex(LONG, idx, pos);
        count++;
    }

    public void remove(long pos) {
        int idx = lowerBound(pos);
        if (idx >= count || positions.getAtIndex(LONG, idx) != pos) {
            return;
        }
        if (idx < count - 1) {
            MemorySegment.copy(positions, (long) (idx + 1) * 8, positions, (long) idx * 8, (long) (count - idx - 1) * 8);
        }
        count--;
    }

    /** 第一个 >= pos 的下标（有符号比较，与 LongAVLTreeSet 的自然序一致）。 */
    private int lowerBound(long pos) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (positions.getAtIndex(LONG, mid) < pos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void ensureCap(int need) {
        if (need <= cap) {
            return;
        }
        int ncap = cap;
        while (ncap < need) {
            ncap *= 2;
        }
        MemorySegment np = arena.allocateArray(LONG, ncap);
        MemorySegment.copy(positions, 0, np, 0, (long) count * 8);
        positions = np;
        cap = ncap;
    }

    /** 调用原生算访问计划。返回条数（≥0），负数 = 失败（调用方必须回退原版）。 */
    public int plan(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        box6.set(ValueLayout.JAVA_DOUBLE, 0, minX);
        box6.set(ValueLayout.JAVA_DOUBLE, 8, minY);
        box6.set(ValueLayout.JAVA_DOUBLE, 16, minZ);
        box6.set(ValueLayout.JAVA_DOUBLE, 24, maxX);
        box6.set(ValueLayout.JAVA_DOUBLE, 32, maxY);
        box6.set(ValueLayout.JAVA_DOUBLE, 40, maxZ);
        return NativePush.sectionPlan(box6, positions.asSlice(0, (long) count * 8), count, out, outCap, outCount);
    }

    public long outAt(int i) {
        return out.getAtIndex(LONG, i);
    }
}
