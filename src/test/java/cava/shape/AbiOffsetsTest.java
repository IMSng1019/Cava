package cava.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cava.ffm.CavaLayouts;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import org.junit.jupiter.api.Test;

/**
 * {@link AbiOffsets} 的每个常量都必须与冻结的 {@link CavaLayouts} 布局**逐个**相等。
 *
 * <p>热路径用常量而不是 {@code byteOffset(groupElement("名字"))}（按字符串查字段名，改名即抛），
 * 所以必须有这条对拍来保证常量不会与布局漂移。漂移在这里红，而不是在服务端上写坏内存。
 */
class AbiOffsetsTest {

    private static long off(StructLayout layout, String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    @Test
    void shapeRecordOffsetsMatchFrozenLayout() {
        StructLayout l = CavaLayouts.SHAPE_RECORD;
        assertEquals(l.byteSize(), AbiOffsets.SHAPE_RECORD_SIZE, "CavaShapeRecord.sizeof");
        assertEquals(off(l, "points_kind"), AbiOffsets.SHAPE_POINTS_KIND);
        assertEquals(off(l, "point_offset"), AbiOffsets.SHAPE_POINT_OFFSET);
        assertEquals(off(l, "bit_offset"), AbiOffsets.SHAPE_BIT_OFFSET);
        assertEquals(off(l, "bit_words"), AbiOffsets.SHAPE_BIT_WORDS);
        assertEquals(off(l, "size_x"), AbiOffsets.SHAPE_SIZE_X);
        assertEquals(off(l, "size_y"), AbiOffsets.SHAPE_SIZE_Y);
        assertEquals(off(l, "size_z"), AbiOffsets.SHAPE_SIZE_Z);
        assertEquals(off(l, "reserved0"), AbiOffsets.SHAPE_RESERVED0);
    }

    @Test
    void moveShapeRefOffsetsMatchFrozenLayout() {
        StructLayout l = CavaLayouts.MOVE_SHAPE_REF;
        assertEquals(l.byteSize(), AbiOffsets.REF_SIZE, "CavaMoveShapeRef.sizeof");
        assertEquals(off(l, "shape_token"), AbiOffsets.REF_SHAPE_TOKEN);
        assertEquals(off(l, "kind"), AbiOffsets.REF_KIND);
        assertEquals(off(l, "state_id"), AbiOffsets.REF_STATE_ID);
        assertEquals(off(l, "block_x"), AbiOffsets.REF_BLOCK_X);
        assertEquals(off(l, "block_y"), AbiOffsets.REF_BLOCK_Y);
        assertEquals(off(l, "block_z"), AbiOffsets.REF_BLOCK_Z);
        assertEquals(off(l, "source"), AbiOffsets.REF_SOURCE);
        assertEquals(off(l, "inline_slot"), AbiOffsets.REF_INLINE_SLOT);
        assertEquals(off(l, "reserved0"), AbiOffsets.REF_RESERVED0);
        assertEquals(off(l, "reserved1"), AbiOffsets.REF_RESERVED1);
        assertEquals(off(l, "reserved2"), AbiOffsets.REF_RESERVED2);
    }

    @Test
    void moveRequestOffsetsMatchFrozenLayout() {
        StructLayout l = CavaLayouts.MOVE_REQUEST;
        assertEquals(l.byteSize(), AbiOffsets.REQ_SIZE, "CavaMoveRequest.sizeof");
        assertEquals(off(l, "reserved0"), AbiOffsets.REQ_RESERVED0);
        assertEquals(off(l, "min_x"), AbiOffsets.REQ_MIN_X);
        assertEquals(off(l, "min_y"), AbiOffsets.REQ_MIN_Y);
        assertEquals(off(l, "min_z"), AbiOffsets.REQ_MIN_Z);
        assertEquals(off(l, "max_x"), AbiOffsets.REQ_MAX_X);
        assertEquals(off(l, "max_y"), AbiOffsets.REQ_MAX_Y);
        assertEquals(off(l, "max_z"), AbiOffsets.REQ_MAX_Z);
        assertEquals(off(l, "move_x"), AbiOffsets.REQ_MOVE_X);
        assertEquals(off(l, "move_y"), AbiOffsets.REQ_MOVE_Y);
        assertEquals(off(l, "move_z"), AbiOffsets.REQ_MOVE_Z);
        assertEquals(off(l, "step_height"), AbiOffsets.REQ_STEP_HEIGHT);
        assertEquals(off(l, "flags"), AbiOffsets.REQ_FLAGS);
        assertEquals(off(l, "on_ground"), AbiOffsets.REQ_ON_GROUND);
        assertEquals(off(l, "shape_count"), AbiOffsets.REQ_SHAPE_COUNT);
        assertEquals(off(l, "reserved1"), AbiOffsets.REQ_RESERVED1);
    }

    @Test
    void moveEventOffsetsMatchFrozenLayout() {
        StructLayout l = CavaLayouts.MOVE_EVENT;
        assertEquals(l.byteSize(), AbiOffsets.EVENT_SIZE, "CavaMoveEvent.sizeof");
        assertEquals(off(l, "source"), AbiOffsets.EVENT_SOURCE);
        assertEquals(off(l, "axis"), AbiOffsets.EVENT_AXIS);
        assertEquals(off(l, "block_x"), AbiOffsets.EVENT_BLOCK_X);
        assertEquals(off(l, "block_y"), AbiOffsets.EVENT_BLOCK_Y);
        assertEquals(off(l, "block_z"), AbiOffsets.EVENT_BLOCK_Z);
        assertEquals(off(l, "pass"), AbiOffsets.EVENT_PASS);
        assertEquals(off(l, "accepted"), AbiOffsets.EVENT_ACCEPTED);
        assertEquals(off(l, "cell_x"), AbiOffsets.EVENT_CELL_X);
        assertEquals(off(l, "cell_y"), AbiOffsets.EVENT_CELL_Y);
        assertEquals(off(l, "cell_z"), AbiOffsets.EVENT_CELL_Z);
        assertEquals(off(l, "shape_token"), AbiOffsets.EVENT_SHAPE_TOKEN);
        assertEquals(off(l, "offset"), AbiOffsets.EVENT_OFFSET);
        assertEquals(off(l, "max_dist_before"), AbiOffsets.EVENT_MAX_DIST_BEFORE);
        assertEquals(off(l, "max_dist_after"), AbiOffsets.EVENT_MAX_DIST_AFTER);
    }

    @Test
    void moveResultOffsetsMatchFrozenLayout() {
        StructLayout l = CavaLayouts.MOVE_RESULT;
        assertEquals(l.byteSize(), AbiOffsets.RESULT_SIZE, "CavaMoveResult.sizeof");
        assertEquals(off(l, "status"), AbiOffsets.RESULT_STATUS);
        assertEquals(off(l, "step_used"), AbiOffsets.RESULT_STEP_USED);
        assertEquals(off(l, "event_count"), AbiOffsets.RESULT_EVENT_COUNT);
        assertEquals(off(l, "event_overflow"), AbiOffsets.RESULT_EVENT_OVERFLOW);
        assertEquals(off(l, "delta_x"), AbiOffsets.RESULT_DELTA_X);
        assertEquals(off(l, "delta_y"), AbiOffsets.RESULT_DELTA_Y);
        assertEquals(off(l, "delta_z"), AbiOffsets.RESULT_DELTA_Z);
        assertEquals(off(l, "base_x"), AbiOffsets.RESULT_BASE_X);
        assertEquals(off(l, "base_y"), AbiOffsets.RESULT_BASE_Y);
        assertEquals(off(l, "base_z"), AbiOffsets.RESULT_BASE_Z);
        assertEquals(off(l, "step_x"), AbiOffsets.RESULT_STEP_X);
        assertEquals(off(l, "step_y"), AbiOffsets.RESULT_STEP_Y);
        assertEquals(off(l, "step_z"), AbiOffsets.RESULT_STEP_Z);
    }
}
