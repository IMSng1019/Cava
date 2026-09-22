package cava.mirror;

import cava.ffm.CavaLayouts;
import cava.parity.Fnv1a;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * {@code CavaMobProfile} 的 Java 侧值对象（纯数据，可单测）。
 *
 * <p><b>位姿也在里面（{@code startX/Y/Z}）</b>，而 {@code CavaPathRequest} 里没有起点 ——
 * 所以 captain 已裁决：**每次求解前重新上传**（见 {@code RegionSource.uploadProfileForSolve}）。
 *
 * <p>{@link #independentKey()} 是"与该生物这一瞬间的位姿无关"的语义指纹，
 * 用来充当 {@code profileKey}：它只随**能力/体型/惩罚表/世界上下界**变化。
 * 位姿不进 key（每 tick 都在变），但**每次上传前必须用当前位姿重新构造 spec**。
 */
public record MobProfileSpec(
        float[] penalty,          /* penalty[26]，下标 = PathTypes 的真实 ordinal */
        float maxFallDistance,    /* ABI 有、1.20.4 里找不到来源（见 mirror-notes §4），内核不读 */
        double startX, double startY, double startZ,
        int startBlockX, int startBlockY, int startBlockZ,
        float width, float height, float stepHeight,
        int safeFallDistance, int minY, int seaLevel,
        int caps, int penaltyMask, int navKind, boolean exact) {

    /** 陆地寻路器（{@code LandPathNodeMaker}）。 */
    public static final int KIND_LAND = 1;
    /** 两栖寻路器（{@code AmphibiousPathNodeMaker}）。 */
    public static final int KIND_AMPHIBIOUS = 2;
    /** 内核未实现的寻路器（飞行 / 水生）。 */
    public static final int KIND_UNSUPPORTED = 0;

    private static final long O_PENALTY = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("penalty"));
    private static final long O_MAX_FALL = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("max_fall_distance"));
    private static final long O_START_X = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("start_x"));
    private static final long O_START_Y = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("start_y"));
    private static final long O_START_Z = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("start_z"));
    private static final long O_SBX = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("start_block_x"));
    private static final long O_SBY = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("start_block_y"));
    private static final long O_SBZ = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("start_block_z"));
    private static final long O_WIDTH = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("width"));
    private static final long O_HEIGHT = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("height"));
    private static final long O_STEP = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("step_height"));
    private static final long O_SAFE_FALL = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("safe_fall_distance"));
    private static final long O_MIN_Y = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("min_y"));
    private static final long O_SEA = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("sea_level"));
    private static final long O_CAPS = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("caps"));
    private static final long O_MASK = CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("penalty_mask"));

    public MobProfileSpec {
        if (penalty.length != PathTypes.COUNT) {
            throw new IllegalArgumentException("penalty.length=" + penalty.length + " 期望 " + PathTypes.COUNT);
        }
        penalty = penalty.clone();
    }

    /** 该档案是否落在原生内核已实现的范围内（陆地 / 两栖；飞行与水生未实现）。 */
    public boolean supported() {
        return navKind == KIND_LAND || navKind == KIND_AMPHIBIOUS;
    }

    /** 是否由镜像侧"逐字段有出处"地采集（false = 有字段是猜的，必须回退）。 */
    public boolean ready() {
        return exact && supported() && width > 0.0f && height > 0.0f && stepHeight >= 0.0f
                && !Float.isNaN(width) && !Float.isNaN(height)
                && (penaltyMask & ~NavCaps.PENALTY_ALL_SET) == 0;
    }

    /** 与位姿无关的语义指纹（= profileKey）。 */
    public long independentKey() {
        long h = Fnv1a.begin();
        h = Fnv1a.updateInt(h, Float.floatToRawIntBits(width));
        h = Fnv1a.updateInt(h, Float.floatToRawIntBits(height));
        h = Fnv1a.updateInt(h, Float.floatToRawIntBits(stepHeight));
        h = Fnv1a.updateInt(h, safeFallDistance);
        h = Fnv1a.updateInt(h, minY);
        h = Fnv1a.updateInt(h, seaLevel);
        h = Fnv1a.updateInt(h, caps);
        h = Fnv1a.updateInt(h, penaltyMask);
        h = Fnv1a.updateInt(h, navKind);
        for (int i = 0; i < penalty.length; i++) {
            h = Fnv1a.updateInt(h, Float.floatToRawIntBits(penalty[i]));
        }
        return h;
    }

    /** 按 {@code CavaMobProfile} 的布局写进段（192 字节）。 */
    public void writeTo(MemorySegment seg) {
        for (int i = 0; i < penalty.length; i++) {
            seg.set(ValueLayout.JAVA_FLOAT, O_PENALTY + i * 4L, penalty[i]);
        }
        seg.set(ValueLayout.JAVA_FLOAT, O_MAX_FALL, maxFallDistance);
        seg.set(ValueLayout.JAVA_DOUBLE, O_START_X, startX);
        seg.set(ValueLayout.JAVA_DOUBLE, O_START_Y, startY);
        seg.set(ValueLayout.JAVA_DOUBLE, O_START_Z, startZ);
        seg.set(ValueLayout.JAVA_INT, O_SBX, startBlockX);
        seg.set(ValueLayout.JAVA_INT, O_SBY, startBlockY);
        seg.set(ValueLayout.JAVA_INT, O_SBZ, startBlockZ);
        seg.set(ValueLayout.JAVA_FLOAT, O_WIDTH, width);
        seg.set(ValueLayout.JAVA_FLOAT, O_HEIGHT, height);
        seg.set(ValueLayout.JAVA_FLOAT, O_STEP, stepHeight);
        seg.set(ValueLayout.JAVA_INT, O_SAFE_FALL, safeFallDistance);
        seg.set(ValueLayout.JAVA_INT, O_MIN_Y, minY);
        seg.set(ValueLayout.JAVA_INT, O_SEA, seaLevel);
        seg.set(ValueLayout.JAVA_INT, O_CAPS, caps);
        seg.set(ValueLayout.JAVA_INT, O_MASK, penaltyMask);
    }
}
