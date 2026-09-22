package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaLayouts;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * 状态表装箱测试（**脱离服务器**：用假 StateProbe）。
 *
 * <p>同时逐字段验证"写进 {@code CavaStateRecord}/{@code CavaCollisionBox} 的字节"与
 * {@code cava.ffm.CavaLayouts} 的偏移一致 —— 这是 Java 侧唯一能单测的 ABI 对齐证据。
 */
class StateTableBuilderTest {

    /** 4 个假状态：空气 / 整方块 / 栅栏 / 水。 */
    private static final class FakeProbe implements StateProbe {
        @Override
        public int stateCount() {
            return 4;
        }

        @Override
        public void probe(int id, StateSample out) {
            switch (id) {
                case 0 -> { // 空气
                    out.set(MirrorFlags.Pred.SF_AIR, true);
                    out.set(MirrorFlags.Pred.PF_PATH_THROUGH_LAND, true);
                    out.commonType = PathTypes.OPEN;
                }
                case 1 -> { // 整方块（不可通行）
                    out.set(MirrorFlags.Pred.SF_SOLID, true);
                    out.set(MirrorFlags.Pred.SF_BLOCKS_MOTION, true);
                    out.addBox(0f, 0f, 0f, 1f, 1f, 1f);
                    out.commonType = PathTypes.BLOCKED;
                }
                case 2 -> { // 栅栏
                    out.set(MirrorFlags.Pred.PF_FENCES, true);
                    out.set(MirrorFlags.Pred.PF_FENCE_OR_WALL_CLOSED, true);
                    out.addBox(0.25f, 0f, 0.25f, 0.75f, 1.5f, 0.75f);
                    out.addBox(0.4375f, 0f, 0f, 0.5625f, 1.5f, 0.5625f);
                    out.commonType = PathTypes.FENCE;
                }
                default -> { // 水
                    out.set(MirrorFlags.Pred.SF_FLUID, true);
                    out.set(MirrorFlags.Pred.SF_WATER, true);
                    out.set(MirrorFlags.Pred.PF_PATH_THROUGH_LAND, true);
                    out.commonType = PathTypes.WATER;
                }
            }
        }
    }

    @Test
    void boxesAreKeyedByStateId() {
        StateTableBuilder.Result r = StateTableBuilder.build(new FakeProbe());
        StateTableData d = r.data();
        assertEquals(4, d.stateCount);
        assertEquals(3, d.boxTotal);
        // id0 空气：无盒
        assertEquals(-1, d.boxOffset(0));
        assertEquals(0, d.boxCount(0));
        // id1 整方块：1 个盒，从 0 开始
        assertEquals(0, d.boxOffset(1));
        assertEquals(1, d.boxCount(1));
        assertEquals(1.0f, d.box(0, 4), 0.0f);
        // id2 栅栏：2 个盒，接在 id1 之后
        assertEquals(1, d.boxOffset(2));
        assertEquals(2, d.boxCount(2));
        assertEquals(1.5f, d.box(1, 4), 0.0f);
        assertEquals(0.4375f, d.box(2, 0), 0.0f);
        // id3 水：无盒
        assertEquals(-1, d.boxOffset(3));
        assertEquals(0, d.boxCount(3));
        assertEquals(3, r.stats().boxTotal);
        assertEquals(2, r.stats().statesWithoutBoxes);
        assertEquals(2, r.stats().maxBoxesPerState);
    }

    @Test
    void flagsAndPathTypeAreCarriedThrough() {
        StateTableData d = StateTableBuilder.build(new FakeProbe()).data();
        assertEquals(MirrorFlags.SF_AIR | MirrorFlags.PF_PATH_THROUGH_LAND, d.flags(0));
        assertEquals(PathTypes.OPEN, d.pathTypeIdx(0));
        assertEquals(0.0f, d.malus(0), 0.0f);
        assertEquals(PathTypes.FENCE, d.pathTypeIdx(2));
        assertEquals(-1.0f, d.malus(2), 0.0f);
        assertEquals(PathTypes.WATER, d.pathTypeIdx(3));
        assertEquals(8.0f, d.malus(3), 0.0f);
    }

    @Test
    void writtenBytesMatchAbiOffsets() {
        StateTableBuilder.Result r = StateTableBuilder.build(new FakeProbe());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment recs = CavaNativeArray.records(arena, r.data().stateCount);
            r.data().writeRecords(recs);
            // id1：flags 在 0、box_offset 在 4、box_count 在 8、path_type_idx 在 12、malus 在 16
            long base = CavaLayouts.STATE_RECORD_SIZE;
            assertEquals(r.data().flags(1), recs.get(ValueLayout.JAVA_INT, base + 0));
            assertEquals(0, recs.get(ValueLayout.JAVA_INT, base + 4));
            assertEquals(1, recs.get(ValueLayout.JAVA_INT, base + 8));
            assertEquals(PathTypes.BLOCKED, recs.get(ValueLayout.JAVA_INT, base + 12));
            // malus = 该 commonType 的默认惩罚；BLOCKED 是 -1.0f
            assertEquals(-1.0f, recs.get(ValueLayout.JAVA_FLOAT, base + 16), 0.0f);
            // id0 的 box_offset 必须是 CAVA_BOX_NONE = 0xFFFFFFFF
            assertEquals(0xFFFFFFFF, recs.get(ValueLayout.JAVA_INT, 0L + 4), "id0 的 box_offset");
            // 盒数组：第 0 个盒 = 整方块
            MemorySegment boxes = CavaNativeArray.boxes(arena, r.data().boxTotal);
            r.data().writeBoxes(boxes);
            assertEquals(1.0f, boxes.get(ValueLayout.JAVA_FLOAT, 5 * 4L), 0.0f);
            assertEquals(0.25f, boxes.get(ValueLayout.JAVA_FLOAT, 6 * 4L), 0.0f);
        }
    }

    @Test
    void unknownBitIsRejected() {
        StateProbe bad = new StateProbe() {
            @Override
            public int stateCount() {
                return 1;
            }

            @Override
            public void probe(int id, StateSample out) {
                out.flags = 1 << 30;
            }
        };
        assertThrows(IllegalStateException.class, () -> StateTableBuilder.build(bad));
    }

    @Test
    void compareBoxesReportsDifferences() {
        StateTableData a = StateTableBuilder.build(new FakeProbe()).data();
        String same = StateTableBuilder.compareBoxes(a, a, id -> "s" + id, 3);
        assertTrue(same.contains("一致 4 / 不同 0"), same);

        StateProbe shifted = new StateProbe() {
            @Override
            public int stateCount() {
                return 4;
            }

            @Override
            public void probe(int id, StateSample out) {
                new FakeProbe().probe(id, out);
                if (id == 1) {
                    out.addBox(0f, 0f, 0f, 1f, 0.5f, 1f);
                }
            }
        };
        StateTableData b = StateTableBuilder.build(shifted).data();
        String diff = StateTableBuilder.compareBoxes(a, b, id -> "s" + id, 3);
        assertTrue(diff.contains("不同 1"), diff);
    }

    /** 小工具：按 ABI 布局分配数组（测试里也走 allocateArray 那条唯一正确路径）。 */
    private static final class CavaNativeArray {
        static MemorySegment records(Arena arena, int count) {
            return cava.ffm.CavaNative.allocateArray(arena, CavaLayouts.STATE_RECORD, count);
        }

        static MemorySegment boxes(Arena arena, int count) {
            return cava.ffm.CavaNative.allocateArray(arena, CavaLayouts.COLLISION_BOX, count);
        }
    }
}
