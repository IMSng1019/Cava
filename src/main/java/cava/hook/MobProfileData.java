package cava.hook;

import cava.ffm.CavaLayouts;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * {@code CavaMobProfile} 的 Java 侧镜像（**不含任何 Minecraft 类型**，可单测）。
 *
 * <p>字段顺序与 {@code native/include/cava_abi.h} 的 {@code CavaMobProfile} 一一对应，
 * 偏移直接取 {@link CavaLayouts#MOB_PROFILE_OFFSETS}（那份常量已由
 * {@code CavaLayouts.checkAgainstCAbi()} 与 C 编译器的 {@code offsetof/sizeof} 逐字段对齐）。
 *
 * <p><b>生命周期（captain 2026-09-22 裁决）</b>：档案里的 {@code start_x/y/z} 是**当前位姿**，
 * 而 {@code CavaPathRequest} 里没有起点 ⇒ 档案必须**每次求解前重推**，不得跨 tick 复用。
 */
public final class MobProfileData {

    /** 惩罚表项数（= {@code CAVA_PNT_COUNT}）。 */
    public static final int PENALTY_COUNT = CavaLayouts.PNT_COUNT;

    private final float[] penalty = new float[PENALTY_COUNT];

    /**
     * {@code reserved_max_fall_distance}：**占位字段，内核不读**（captain 2026-09-22 裁决，
     * 真正生效的是 {@link #safeFallDistance}），而且 1.20.4 里**没有来源**
     * （{@code Entity} 上只有 {@code getSafeFallDistance()}，没有 {@code getMaxFallDistance()}，javap 实测）。
     * 永远写 0，**不要把它当活字段**。
     */
    public float reservedMaxFallDistance;
    public double startX;
    public double startY;
    public double startZ;
    public int startBlockX;
    public int startBlockY;
    public int startBlockZ;
    public float width;
    public float height;
    public float stepHeight;
    public int safeFallDistance;
    public int minY;
    public int seaLevel;
    public int caps;
    public int penaltyMask;

    /** 写第 i 项惩罚值（索引语义见 {@link AbiPenaltyOrder}）。 */
    public void setPenalty(int index, float value) {
        penalty[index] = value;
    }

    public float penalty(int index) {
        return penalty[index];
    }

    public float[] penaltyCopy() {
        return penalty.clone();
    }

    /**
     * 把本对象写进一个 {@code sizeof(CavaMobProfile)} 的段。
     *
     * <p>写入前**整段清零**：尾部 {@code reserved0/reserved1} 与 {@code penalty[]} 的未填项必须是 0
     * （契约：flags/reserved 非 0 一律 {@code CAVA_ERR_ARG}，宁可在 Java 侧先清零）。
     *
     * @param base 段的起始偏移（正常情况下 0）
     */
    public void writeTo(MemorySegment segment, long base) {
        segment.fill((byte) 0);
        // 偏移一律用 CavaLayouts.MobProfileOffset.* —— **不要按字段名查**
        // （byteOffset(groupElement("名字")) 在字段改名时会运行期抛异常，captain 已实测撞过一次）。
        long penaltyBase = base + CavaLayouts.MobProfileOffset.PENALTY;
        for (int i = 0; i < PENALTY_COUNT; i++) {
            segment.set(ValueLayout.JAVA_FLOAT, penaltyBase + (long) i * Float.BYTES, penalty[i]);
        }
        segment.set(ValueLayout.JAVA_FLOAT, base + CavaLayouts.MobProfileOffset.RESERVED_MAX_FALL,
                reservedMaxFallDistance);
        segment.set(ValueLayout.JAVA_DOUBLE, base + CavaLayouts.MobProfileOffset.START_X, startX);
        segment.set(ValueLayout.JAVA_DOUBLE, base + CavaLayouts.MobProfileOffset.START_Y, startY);
        segment.set(ValueLayout.JAVA_DOUBLE, base + CavaLayouts.MobProfileOffset.START_Z, startZ);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.START_BLOCK_X, startBlockX);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.START_BLOCK_Y, startBlockY);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.START_BLOCK_Z, startBlockZ);
        segment.set(ValueLayout.JAVA_FLOAT, base + CavaLayouts.MobProfileOffset.WIDTH, width);
        segment.set(ValueLayout.JAVA_FLOAT, base + CavaLayouts.MobProfileOffset.HEIGHT, height);
        segment.set(ValueLayout.JAVA_FLOAT, base + CavaLayouts.MobProfileOffset.STEP_HEIGHT, stepHeight);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.SAFE_FALL_DISTANCE, safeFallDistance);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.MIN_Y, minY);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.SEA_LEVEL, seaLevel);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.CAPS, caps);
        segment.set(ValueLayout.JAVA_INT, base + CavaLayouts.MobProfileOffset.PENALTY_MASK, penaltyMask);
    }

    /** 可读摘要（日志/测试用；不含 26 项惩罚表逐项）。 */
    public String describe() {
        return "profile{start=(" + startX + "," + startY + "," + startZ + ") block=(" + startBlockX + ","
                + startBlockY + "," + startBlockZ + ") wh=(" + width + "," + height + ") step=" + stepHeight
                + " safeFall=" + safeFallDistance + " minY=" + minY + " sea=" + seaLevel
                + " caps=0x" + Integer.toHexString(caps) + " mask=0x" + Integer.toHexString(penaltyMask) + "}";
    }
}
