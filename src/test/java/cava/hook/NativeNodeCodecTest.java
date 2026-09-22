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
 * {@code CavaPathNode[]} 解码 + {@code reachesTarget} 反语义的还原（纯函数 + FFM，不需要 MC/原生库）。
 *
 * <p>两个都是"错了也不崩、只是与原版不一致"的地方，必须钉死：
 * <ul>
 *   <li>字段偏移写错 ⇒ 节点坐标串味；</li>
 *   <li>{@code reachesTarget} 用正语义 ⇒ 上层的导航行为与原版相反（oracle spec 4.3.1）。</li>
 * </ul>
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

    @Test
    void reachesTargetFlagIsInvertedLikeVanilla() {
        // 末节点落在 reachRange 内 ⇒ 原版走 found 分支 ⇒ createPath(..., false) ⇒ reachesTarget = false
        assertFalse(NativeNodeCodec.reachesTargetFlag(10, 64, 10, 11, 64, 10, 1));
        assertFalse(NativeNodeCodec.reachesTargetFlag(10, 64, 10, 10, 64, 10, 0));
        // 末节点在半径外 ⇒ found 为空 ⇒ createPath(..., true) ⇒ reachesTarget = true
        assertTrue(NativeNodeCodec.reachesTargetFlag(10, 64, 10, 20, 64, 10, 1));
        // 边界：恰好等于半径算"抵达"（原版是 <= ，bytecode 是 fcmpg/ifgt）
        assertFalse(NativeNodeCodec.reachesTargetFlag(0, 0, 0, 1, 1, 0, 2));
        assertTrue(NativeNodeCodec.reachesTargetFlag(0, 0, 0, 1, 1, 0, 1));
        // 负数半径：任何距离都不在半径内 ⇒ 只能给最接近点 ⇒ reachesTarget = true
        assertTrue(NativeNodeCodec.reachesTargetFlag(0, 0, 0, 0, 0, 0, -1));
    }
}
