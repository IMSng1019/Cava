package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cava.ffm.CavaLayouts;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * {@code CavaMobProfile} 的 Java 侧写入（需要 FFM 预览 API，但**不需要原生库、不需要 Minecraft**）。
 *
 * <p>为什么值得测：偏移写错 = 原生侧读到垃圾（宽高变成坐标、惩罚表错位）。
 * 这里用**独立算出的**偏移常量做交叉验证（不是照抄 {@code MOB_PROFILE_OFFSETS}），
 * 这样 {@code cava_abi.h} 一改、{@code CavaLayouts} 没跟上时测试会红。
 */
class MobProfileDataTest {

    /** 独立从 cava_abi.h 的字段顺序手算的期望偏移（与 CavaLayouts 的实现无关）。 */
    private static final long[] EXPECTED = {
            0,      // penalty[26]  = 26*4 = 104 字节
            104,    // max_fall_distance
            112,    // start_x (double)
            120,    // start_y
            128,    // start_z
            136,    // start_block_x
            140,    // start_block_y
            144,    // start_block_z
            148,    // width
            152,    // height
            156,    // step_height
            160,    // safe_fall_distance
            164,    // min_y
            168,    // sea_level
            172,    // caps
            176,    // penalty_mask
            180,    // reserved0
            184,    // reserved1
    };

    private static MobProfileData sample() {
        MobProfileData d = new MobProfileData();
        for (int i = 0; i < MobProfileData.PENALTY_COUNT; i++) {
            d.setPenalty(i, i + 0.5f);
        }
        d.reservedMaxFallDistance = 0.0f;   // 占位字段：内核不读，永远 0
        d.startX = 1.5;
        d.startY = 64.0625;
        d.startZ = -2.5;
        d.startBlockX = 1;
        d.startBlockY = 64;
        d.startBlockZ = -3;
        d.width = 0.6f;
        d.height = 1.95f;
        d.stepHeight = 0.6f;
        d.safeFallDistance = 3;
        d.minY = -64;
        d.seaLevel = 63;
        d.caps = MobInputs.NAV_CAN_SWIM | MobInputs.NAV_ON_GROUND;
        d.penaltyMask = MobInputs.PENALTY_ALL_SET;
        return d;
    }

    @Test
    void expectedOffsetsMatchTheFrozenLayout() {
        assertEquals(EXPECTED.length, CavaLayouts.MOB_PROFILE_OFFSETS.length, "字段数");
        for (int i = 0; i < EXPECTED.length; i++) {
            assertEquals(EXPECTED[i], CavaLayouts.MOB_PROFILE_OFFSETS[i], "字段 " + i);
        }
        assertEquals(192L, CavaLayouts.MOB_PROFILE_SIZE);
        assertEquals(104L, (long) MobProfileData.PENALTY_COUNT * Float.BYTES);
    }

    @Test
    void writeToProducesTheExactFieldPattern() {
        MobProfileData d = sample();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(CavaLayouts.MOB_PROFILE);
            d.writeTo(seg, 0);

            for (int i = 0; i < MobProfileData.PENALTY_COUNT; i++) {
                assertEquals(i + 0.5f, seg.get(ValueLayout.JAVA_FLOAT, EXPECTED[0] + (long) i * 4), "penalty[" + i + "]");
            }
            assertEquals(0.0f, seg.get(ValueLayout.JAVA_FLOAT, EXPECTED[1]), "reserved_max_fall 必须是 0");
            assertEquals(1.5, seg.get(ValueLayout.JAVA_DOUBLE, EXPECTED[2]));
            assertEquals(64.0625, seg.get(ValueLayout.JAVA_DOUBLE, EXPECTED[3]));
            assertEquals(-2.5, seg.get(ValueLayout.JAVA_DOUBLE, EXPECTED[4]));
            assertEquals(1, seg.get(ValueLayout.JAVA_INT, EXPECTED[5]));
            assertEquals(64, seg.get(ValueLayout.JAVA_INT, EXPECTED[6]));
            assertEquals(-3, seg.get(ValueLayout.JAVA_INT, EXPECTED[7]));
            assertEquals(0.6f, seg.get(ValueLayout.JAVA_FLOAT, EXPECTED[8]));
            assertEquals(1.95f, seg.get(ValueLayout.JAVA_FLOAT, EXPECTED[9]));
            assertEquals(0.6f, seg.get(ValueLayout.JAVA_FLOAT, EXPECTED[10]));
            assertEquals(3, seg.get(ValueLayout.JAVA_INT, EXPECTED[11]));
            assertEquals(-64, seg.get(ValueLayout.JAVA_INT, EXPECTED[12]));
            assertEquals(63, seg.get(ValueLayout.JAVA_INT, EXPECTED[13]));
            assertEquals(MobInputs.NAV_CAN_SWIM | MobInputs.NAV_ON_GROUND, seg.get(ValueLayout.JAVA_INT, EXPECTED[14]));
            assertEquals(MobInputs.PENALTY_ALL_SET, seg.get(ValueLayout.JAVA_INT, EXPECTED[15]));
            assertEquals(0, seg.get(ValueLayout.JAVA_INT, EXPECTED[16]), "reserved0 必须是 0（ABI 会拒非 0）");
            assertEquals(0, seg.get(ValueLayout.JAVA_INT, EXPECTED[17]), "reserved1 必须是 0");
        }
    }

    @Test
    void capsBitValuesMatchTheFrozenAbi() {
        assertEquals(1, MobInputs.NAV_CAN_OPEN_DOORS);
        assertEquals(1 << 1, MobInputs.NAV_CAN_ENTER_OPEN_DOORS);
        assertEquals(1 << 2, MobInputs.NAV_CAN_FLOAT);
        assertEquals(1 << 3, MobInputs.NAV_AMPHIBIOUS);
        assertEquals(1 << 4, MobInputs.NAV_PENALIZE_DEEP_WATER);
        assertEquals(1 << 5, MobInputs.NAV_CAN_WALK_OVER_FENCES);
        assertEquals(1 << 6, MobInputs.NAV_CAN_SWIM);
        assertEquals(1 << 7, MobInputs.NAV_CAN_PATHFIND_THROUGH);
        assertEquals(1 << 8, MobInputs.NAV_ON_GROUND);
        assertEquals(1 << 9, MobInputs.NAV_TOUCHING_WATER);
        assertEquals(1 << 10, MobInputs.NAV_CAN_WALK_ON_FLUID);
        assertEquals(0x03FFFFFF, MobInputs.PENALTY_ALL_SET);
    }

    @Test
    void profileKeyIsStableAndSensitiveToContext() {
        MobProfileData a = sample();
        MobProfileData b = sample();
        assertNotNull(a);
        // 同一个"档案"必须得到同一个 key（否则 isProfileReadyForSolve 永远 false ⇒ 永远静默回退）
        long k1 = MobInputs.profileKey(a, MobInputs.MakerKind.LAND);
        long k2 = MobInputs.profileKey(b, MobInputs.MakerKind.LAND);
        assertEquals(k1, k2);
        // 上下文变了必须换 key（否则镜像流会把两个不同 profile 的 flags 混在一起）
        b.width = 1.4f;
        org.junit.jupiter.api.Assertions.assertNotEquals(k1, MobInputs.profileKey(b, MobInputs.MakerKind.LAND));
        org.junit.jupiter.api.Assertions.assertNotEquals(k1, MobInputs.profileKey(a, MobInputs.MakerKind.FLYING));
        b.width = a.width;
        b.caps = a.caps | MobInputs.NAV_CAN_OPEN_DOORS;
        org.junit.jupiter.api.Assertions.assertNotEquals(k1, MobInputs.profileKey(b, MobInputs.MakerKind.LAND));
    }
}
