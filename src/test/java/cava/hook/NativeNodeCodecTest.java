package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaLayouts;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code CavaPathNode[]} 解码 + {@code Path.reachesTarget} 的还原（纯函数 + FFM，不需要 MC/原生库）。
 *
 * <p>两个都是"错了也不崩、只是与原版不一致"的地方，必须钉死：
 * <ul>
 *   <li>字段偏移写错 ⇒ 节点坐标串味；</li>
 *   <li>{@code reachesTarget} 填反 ⇒ 路径对象与原版不一致（而这个字段**被
 *       {@code Path.toBuf} 序列化**、被 {@code copy()} 复制，是网络可见字段）。</li>
 * </ul>
 *
 * <p><b>2026-09-24 勘误</b>：旧注释（与 oracle spec 4.3.1）说语义是反的，实测证伪 ——
 * 纯原版回执里 {@code long128hash}（起点终点 {@code PathNode.hash} 同键、搜索第 1 个节点就
 * {@code FOUND@pop1}）给的是 {@code reachedTargetFlag=true}，而 {@code maze63}（预算耗尽、未抵达）
 * 给的是 {@code false} ⇒ {@code reachesTarget() == found}（true = 抵达）。
 * 详见 {@link NativeNodeCodec#reachesTarget}。
 */
class NativeNodeCodecTest {

    private static MemorySegment nodes(Arena arena, int count, int[][] xyz, int[] types) {
        // 数组必须走 CavaNative.allocateArray（JDK 21 的 arena.allocate(layout, n) 只分配一个元素）
        MemorySegment seg = cava.ffm.CavaNative.allocateArray(arena, CavaLayouts.PATH_NODE, count);
        long[] off = CavaLayouts.PATH_NODE_OFFSETS;
        for (int i = 0; i < count; i++) {
            long b = (long) i * CavaLayouts.PATH_NODE_SIZE;
            seg.set(ValueLayout.JAVA_INT, b + off[0], xyz[i][0]);
            seg.set(ValueLayout.JAVA_INT, b + off[1], xyz[i][1]);
            seg.set(ValueLayout.JAVA_INT, b + off[2], xyz[i][2]);
            seg.set(ValueLayout.JAVA_INT, b + off[3], -1);          // 出堆后 heapIndex = -1
            seg.set(ValueLayout.JAVA_FLOAT, b + off[4], i + 0.25f); // g
            seg.set(ValueLayout.JAVA_FLOAT, b + off[5], i + 7.5f);  // f
            seg.set(ValueLayout.JAVA_INT, b + off[6], types[i]);
            seg.set(ValueLayout.JAVA_INT, b + off[7], 0);
        }
        return seg;
    }

    @Test
    void decodesEveryFieldAtTheFrozenOffsets() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = nodes(arena, 3,
                    new int[][]{{1, 64, 2}, {2, 64, 2}, {3, 64, 2}},
                    new int[]{2, 2, 2});
            List<NativeNodeCodec.Node> list = NativeNodeCodec.decode(seg, 3);
            assertNotNull(list);
            assertEquals(3, list.size());
            assertEquals(1, list.get(0).x());
            assertEquals(64, list.get(0).y());
            assertEquals(2, list.get(0).z());
            assertEquals(-1, list.get(0).heapIndex());
            assertEquals(0.25f, list.get(0).g());
            assertEquals(7.5f, list.get(0).f());
            assertEquals(2, list.get(0).typeOrdinal());
            assertEquals(3, list.get(2).x());
        }
    }

    @Test
    void rejectsContractViolations() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = nodes(arena, 2, new int[][]{{0, 0, 0}, {0, 0, 0}}, new int[]{2, 2});
            assertNull(NativeNodeCodec.decode(seg, 0), "0 个节点不是可接管的结果");
            assertNull(NativeNodeCodec.decode(seg, -1));
            assertNull(NativeNodeCodec.decode(seg, 3), "count 超过段容量必须拒绝（否则读到越界内存）");
            assertNull(NativeNodeCodec.decode(null, 1));
        }
        try (Arena arena = Arena.ofConfined()) {
            // type 序号越界 = 契约违反（CAVA_PNT_* 只有 26 项）
            MemorySegment bad = nodes(arena, 1, new int[][]{{0, 0, 0}}, new int[]{26});
            assertNull(NativeNodeCodec.decode(bad, 1));
            MemorySegment neg = nodes(arena, 1, new int[][]{{0, 0, 0}}, new int[]{-1});
            assertNull(NativeNodeCodec.decode(neg, 1));
        }
    }

    /**
     * {@code reachesTarget} 的语义 = **原版 {@code found}**（true = 抵达）。
     *
     * <p>每一条都对着实测回执写（{@code testbed/perf-fix/results/off.txt}）：
     * <ul>
     *   <li>long128/slalom/maze41 末节点 {@code manh=1}、{@code reachRange=1} ⇒ 原版回执 {@code true}；</li>
     *   <li>maze63 末节点 {@code manh=22} ⇒ 原版回执 {@code false}；</li>
     *   <li>边界：恰好等于半径算"抵达"（原版是 {@code <=}，bytecode 是 {@code fcmpg/ifgt}）；</li>
     *   <li>负数半径：任何距离都不在半径内 ⇒ 只能给最接近点 ⇒ {@code false}。</li>
     * </ul>
     */
    @Test
    void reachesTargetMatchesVanillaFoundSemantics() {
        assertTrue(NativeNodeCodec.reachesTarget(10, 64, 10, 11, 64, 10, 1), "manh=1 <= 1 ⇒ 抵达");
        assertTrue(NativeNodeCodec.reachesTarget(10, 64, 10, 10, 64, 10, 0), "manh=0 <= 0 ⇒ 抵达");
        assertFalse(NativeNodeCodec.reachesTarget(10, 64, 10, 20, 64, 10, 1), "manh=10 > 1 ⇒ 未抵达");
        assertFalse(NativeNodeCodec.reachesTarget(83, 71, -111, 93, 71, -99, 1), "maze63 实测：manh=22 ⇒ false");
        // 边界：恰好等于半径算"抵达"
        assertTrue(NativeNodeCodec.reachesTarget(0, 0, 0, 1, 1, 0, 2));
        assertFalse(NativeNodeCodec.reachesTarget(0, 0, 0, 1, 1, 0, 1));
        // 负数半径：任何距离都不在半径内 ⇒ 只能给最接近点 ⇒ false
        assertFalse(NativeNodeCodec.reachesTarget(0, 0, 0, 0, 0, 0, -1));
    }
}
