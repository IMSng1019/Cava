package cava.shape;

/**
 * P2 那 5 个结构体的**字段偏移**（px 级常量），来自已冻结的 {@code native/include/cava_abi.h}
 * 与 {@code native/src/cava_layout.cpp} 的实测登记值（和值 {@code 0x1C12265E}）。
 *
 * <h2>为什么不用 {@code layout.byteOffset(groupElement("字段名"))}</h2>
 * 那是**按字符串查字段名**：字段一改名就在运行期抛异常（P1 在 {@code max_fall_distance}
 * 加 {@code reserved_} 前缀时撞过一次）。所以热路径一律走这里的常量；
 * {@code AbiOffsetsTest} 会把每个常量与 {@code CavaLayouts} 的布局**逐个对拍**，
 * 任何一侧漂移都会在单测里红，而不是在服务端上。
 *
 * <p>{@code CavaLayouts.java} 是冻结文件（不由本流修改），所以这些常量放在这里。
 */
public final class AbiOffsets {

    private AbiOffsets() {
    }

    // CavaShapeRecord：32 字节 / 8 字段 / align 4
    public static final long SHAPE_RECORD_SIZE = 32;
    public static final long SHAPE_POINTS_KIND = 0;
    public static final long SHAPE_POINT_OFFSET = 4;
    public static final long SHAPE_BIT_OFFSET = 8;
    public static final long SHAPE_BIT_WORDS = 12;
    public static final long SHAPE_SIZE_X = 16;
    public static final long SHAPE_SIZE_Y = 20;
    public static final long SHAPE_SIZE_Z = 24;
    public static final long SHAPE_RESERVED0 = 28;

    // CavaMoveShapeRef：48 字节 / 11 字段 / align 8
    public static final long REF_SIZE = 48;
    public static final long REF_SHAPE_TOKEN = 0;
    public static final long REF_KIND = 8;
    public static final long REF_STATE_ID = 12;
    public static final long REF_BLOCK_X = 16;
    public static final long REF_BLOCK_Y = 20;
    public static final long REF_BLOCK_Z = 24;
    public static final long REF_SOURCE = 28;
    public static final long REF_INLINE_SLOT = 32;
    public static final long REF_RESERVED0 = 36;
    public static final long REF_RESERVED1 = 40;
    public static final long REF_RESERVED2 = 44;

    // CavaMoveRequest：104 字节 / 14 字段 / align 8
    public static final long REQ_SIZE = 104;
    public static final long REQ_RESERVED0 = 0;
    public static final long REQ_MIN_X = 8;
    public static final long REQ_MIN_Y = 16;
    public static final long REQ_MIN_Z = 24;
    public static final long REQ_MAX_X = 32;
    public static final long REQ_MAX_Y = 40;
    public static final long REQ_MAX_Z = 48;
    public static final long REQ_MOVE_X = 56;
    public static final long REQ_MOVE_Y = 64;
    public static final long REQ_MOVE_Z = 72;
    public static final long REQ_STEP_HEIGHT = 80;
    public static final long REQ_FLAGS = 88;
    public static final long REQ_ON_GROUND = 92;
    public static final long REQ_SHAPE_COUNT = 96;
    public static final long REQ_RESERVED1 = 100;

    // CavaMoveEvent：80 字节 / 16 字段 / align 8
    public static final long EVENT_SIZE = 80;
    public static final long EVENT_SOURCE = 0;
    public static final long EVENT_AXIS = 4;
    public static final long EVENT_BLOCK_X = 8;
    public static final long EVENT_BLOCK_Y = 12;
    public static final long EVENT_BLOCK_Z = 16;
    public static final long EVENT_PASS = 20;
    public static final long EVENT_ACCEPTED = 24;
    public static final long EVENT_CELL_X = 28;
    public static final long EVENT_CELL_Y = 32;
    public static final long EVENT_CELL_Z = 36;
    public static final long EVENT_SHAPE_TOKEN = 48;
    public static final long EVENT_OFFSET = 56;
    public static final long EVENT_MAX_DIST_BEFORE = 64;
    public static final long EVENT_MAX_DIST_AFTER = 72;

    // CavaMoveResult：88 字节 / 12 字段 / align 8
    public static final long RESULT_SIZE = 88;
    public static final long RESULT_STATUS = 0;
    public static final long RESULT_STEP_USED = 4;
    public static final long RESULT_EVENT_COUNT = 8;
    public static final long RESULT_EVENT_OVERFLOW = 12;
    public static final long RESULT_DELTA_X = 16;
    public static final long RESULT_DELTA_Y = 24;
    public static final long RESULT_DELTA_Z = 32;
    public static final long RESULT_BASE_X = 40;
    public static final long RESULT_BASE_Y = 48;
    public static final long RESULT_BASE_Z = 56;
    public static final long RESULT_STEP_X = 64;
    public static final long RESULT_STEP_Y = 72;
    public static final long RESULT_STEP_Z = 80;
}
