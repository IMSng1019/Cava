package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * PathNodeType 序号表与默认惩罚的锁定测试。
 *
 * <p>期望值是 {@code docs/CAVA-pathfind-oracle-spec.md} §8 的 javap 实证表 —— **手抄进测试**，
 * 不从实现反推（这张表错一位 = 整张惩罚表错位，而且路径照样算得出来）。
 * MC 环境可用时 {@code McStateTableProbeTest} 还会与真实枚举对拍。
 */
class PathTypesTest {

    private static final String[] NAMES = {
            "BLOCKED", "OPEN", "WALKABLE", "WALKABLE_DOOR", "TRAPDOOR", "POWDER_SNOW",
            "DANGER_POWDER_SNOW", "FENCE", "LAVA", "WATER", "WATER_BORDER", "RAIL",
            "UNPASSABLE_RAIL", "DANGER_FIRE", "DAMAGE_FIRE", "DANGER_OTHER", "DAMAGE_OTHER",
            "DOOR_OPEN", "DOOR_WOOD_CLOSED", "DOOR_IRON_CLOSED", "BREACH", "LEAVES",
            "STICKY_HONEY", "COCOA", "DAMAGE_CAUTIOUS", "DANGER_TRAPDOOR"
    };

    private static final float[] PENALTY = {
            -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f,
            0.0f, -1.0f, -1.0f, 8.0f, 8.0f, 0.0f,
            -1.0f, 8.0f, 16.0f, 8.0f, -1.0f,
            0.0f, -1.0f, -1.0f, 4.0f, -1.0f,
            8.0f, 0.0f, 0.0f, 0.0f
    };

    @Test
    void ordinalsAndPenaltiesMatchSpec() {
        assertEquals(26, PathTypes.COUNT);
        for (int i = 0; i < NAMES.length; i++) {
            assertEquals(NAMES[i], PathTypes.name(i), "ordinal " + i + " 的名字");
            assertEquals(PENALTY[i], PathTypes.defaultPenalty(i), 0.0f, "ordinal " + i + " 的默认惩罚");
        }
        assertEquals("?", PathTypes.name(-1));
        assertEquals("?", PathTypes.name(26));
        assertEquals(0.0f, PathTypes.defaultPenalty(99), 0.0f);
    }

    @Test
    void namedConstantsMatchOrdinals() {
        assertEquals(0, PathTypes.BLOCKED);
        assertEquals(1, PathTypes.OPEN);
        assertEquals(2, PathTypes.WALKABLE);
        assertEquals(5, PathTypes.POWDER_SNOW);
        assertEquals(7, PathTypes.FENCE);
        assertEquals(9, PathTypes.WATER);
        assertEquals(10, PathTypes.WATER_BORDER);
        assertEquals(14, PathTypes.DAMAGE_FIRE);
        assertEquals(17, PathTypes.DOOR_OPEN);
        assertEquals(19, PathTypes.DOOR_IRON_CLOSED);
        assertEquals(20, PathTypes.BREACH);
        assertEquals(24, PathTypes.DAMAGE_CAUTIOUS);
        assertEquals(25, PathTypes.DANGER_TRAPDOOR);
    }

    @Test
    void selfCheckIsClean() {
        assertTrue(PathTypes.selfCheck().isEmpty(), () -> String.join("; ", PathTypes.selfCheck()));
    }

    /** 头文件那套 CAVA_PNT_* 数值与真实 ordinal 的**已知错位**（钉死，避免有人"顺手"改回去）。 */
    @Test
    void headerPinTableIsKnownWrong() {
        assertEquals("FENCE", PathTypes.HEADER_CAVA_PNT[5], "头文件 bit5 声称是 FENCE");
        assertEquals("POWDER_SNOW", PathTypes.name(5), "真实 ordinal 5 是 POWDER_SNOW");
        assertEquals("LAVA", PathTypes.HEADER_CAVA_PNT[6]);
        assertEquals("DANGER_POWDER_SNOW", PathTypes.name(6));
    }
}
