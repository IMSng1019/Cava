package cava.mirror;

import cava.ffm.CavaLayouts;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 方块状态表的**纯数据形态**：每个 state id 一条 {@code CavaStateRecord} + 一张扁平 AABB 数组。
 *
 * <p>键是 **state id**（数组下标即 id），所以天然免疫对象身份问题。
 * 不引用任何 Minecraft 类型 → 可用假状态表单测（含逐字段 offset 断言）。
 */
public final class StateTableData {

    private static final long REC_FLAGS = CavaLayouts.STATE_RECORD.byteOffset(MemoryLayout.PathElement.groupElement("flags"));
    private static final long REC_BOX_OFFSET = CavaLayouts.STATE_RECORD.byteOffset(MemoryLayout.PathElement.groupElement("box_offset"));
    private static final long REC_BOX_COUNT = CavaLayouts.STATE_RECORD.byteOffset(MemoryLayout.PathElement.groupElement("box_count"));
    private static final long REC_PATH_TYPE = CavaLayouts.STATE_RECORD.byteOffset(MemoryLayout.PathElement.groupElement("path_type_idx"));
    private static final long REC_MALUS = CavaLayouts.STATE_RECORD.byteOffset(MemoryLayout.PathElement.groupElement("malus"));

    /** 记录数（= 状态数）。 */
    public final int stateCount;
    /** 碰撞盒总数。 */
    public final int boxTotal;

    private final int[] flags;
    private final int[] boxOffset;
    private final int[] boxCount;
    private final int[] pathTypeIdx;
    private final float[] malus;
    private final float[] boxCoords;

    StateTableData(int stateCount, int boxTotal, int[] flags, int[] boxOffset, int[] boxCount,
                   int[] pathTypeIdx, float[] malus, float[] boxCoords) {
        this.stateCount = stateCount;
        this.boxTotal = boxTotal;
        this.flags = flags;
        this.boxOffset = boxOffset;
        this.boxCount = boxCount;
        this.pathTypeIdx = pathTypeIdx;
        this.malus = malus;
        this.boxCoords = boxCoords;
    }

    /** state id 的 flags。 */
    public int flags(int stateId) {
        return flags[stateId];
    }

    /** state id 的盒下标（无盒 = {@code CAVA_BOX_NONE}）。 */
    public int boxOffset(int stateId) {
        return boxOffset[stateId];
    }

    /** state id 的盒个数。 */
    public int boxCount(int stateId) {
        return boxCount[stateId];
    }

    /** state id 的 {@code path_type_idx}。 */
    public int pathTypeIdx(int stateId) {
        return pathTypeIdx[stateId];
    }

    /** state id 的默认 malus。 */
    public float malus(int stateId) {
        return malus[stateId];
    }

    /** 第 i 个盒的第 c 个分量。 */
    public float box(int i, int c) {
        return boxCoords[i * 6 + c];
    }

    /** 把记录写进 {@code record_count == stateCount} 的数组（{@code allocateArray} 分配的段）。 */
    public void writeRecords(MemorySegment seg) {
        for (int i = 0; i < stateCount; i++) {
            long base = (long) i * CavaLayouts.STATE_RECORD_SIZE;
            seg.set(ValueLayout.JAVA_INT, base + REC_FLAGS, flags[i]);
            seg.set(ValueLayout.JAVA_INT, base + REC_BOX_OFFSET, boxOffset[i]);
            seg.set(ValueLayout.JAVA_INT, base + REC_BOX_COUNT, boxCount[i]);
            seg.set(ValueLayout.JAVA_INT, base + REC_PATH_TYPE, pathTypeIdx[i]);
            seg.set(ValueLayout.JAVA_FLOAT, base + REC_MALUS, malus[i]);
        }
    }

    /** 把碰撞盒写进 {@code box_count == boxTotal} 的数组。 */
    public void writeBoxes(MemorySegment seg) {
        for (int i = 0; i < boxTotal; i++) {
            long base = (long) i * CavaLayouts.COLLISION_BOX_SIZE;
            for (int c = 0; c < 6; c++) {
                seg.set(ValueLayout.JAVA_FLOAT, base + c * 4L, boxCoords[i * 6 + c]);
            }
        }
    }
}
