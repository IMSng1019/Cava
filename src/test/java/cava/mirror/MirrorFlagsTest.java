package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * flags 位定义与"内核据位推类型"的锁定测试（**故意硬编码**，见 docs/CAVA-gates.md 门禁 #7 的教训：
 * 只把实现抄一遍的测试会跟着实现一起错，必须有一张独立写下的期望表）。
 *
 * <p>期望值来源：{@code native/include/cava_abi.h} 的宏定义（bit 号）与
 * {@code docs/CAVA-pathfind-oracle-spec.md} §5.4.6（判据/优先级）。
 */
class MirrorFlagsTest {

    /** 位号硬编码表（= cava_abi.h 逐行抄写，**与 MirrorFlags 相互独立**）。 */
    private static final Map<String, Integer> EXPECTED_BITS = new HashMap<>();

    static {
        EXPECTED_BITS.put("SF_SOLID", 0);
        EXPECTED_BITS.put("SF_BLOCKS_MOTION", 1);
        EXPECTED_BITS.put("SF_FLUID", 2);
        EXPECTED_BITS.put("SF_WATER", 3);
        EXPECTED_BITS.put("SF_LAVA", 4);
        EXPECTED_BITS.put("SF_OPEN", 5);
        EXPECTED_BITS.put("SF_AIR", 6);
        EXPECTED_BITS.put("SF_DOOR", 7);
        EXPECTED_BITS.put("PF_TRAPDOOR", 8);
        EXPECTED_BITS.put("PF_POWDER_SNOW", 9);
        EXPECTED_BITS.put("PF_CACTUS_OR_BERRY", 10);
        EXPECTED_BITS.put("PF_HONEY", 11);
        EXPECTED_BITS.put("PF_COCOA", 12);
        EXPECTED_BITS.put("PF_CAUTIOUS", 13);
        EXPECTED_BITS.put("PF_DOOR_HAND", 14);
        EXPECTED_BITS.put("PF_RAIL", 15);
        EXPECTED_BITS.put("PF_LEAVES", 16);
        EXPECTED_BITS.put("PF_FENCES", 17);
        EXPECTED_BITS.put("PF_WALLS", 18);
        EXPECTED_BITS.put("PF_FENCE_GATE", 19);
        EXPECTED_BITS.put("PF_FIRE_DAMAGE", 20);
        EXPECTED_BITS.put("PF_PATH_THROUGH_LAND", 21);
        EXPECTED_BITS.put("PF_WATER_BLOCK", 22);
        EXPECTED_BITS.put("PF_FENCE_OR_WALL_CLOSED", 23);
        EXPECTED_BITS.put("PF_DOOR_IRON", 24);
        EXPECTED_BITS.put("PF_FIRE", 25);
        EXPECTED_BITS.put("PF_WITHER_ROSE", 26);
    }

    @Test
    void everyBitMatchesFrozenHeader() {
        assertEquals(EXPECTED_BITS.size(), MirrorFlags.PRED_COUNT, "谓词个数");
        for (MirrorFlags.Pred p : MirrorFlags.Pred.values()) {
            Integer expected = EXPECTED_BITS.get(p.name());
            assertTrue(expected != null, "少登记了谓词 " + p.name());
            assertEquals(1 << expected, p.bit(), p.name() + " 的位号与 cava_abi.h 不一致");
        }
    }

    /**
     * 内核别名契约（{@code native/src/pathfind/cava_pf.h} 的纯改号，2026-09-22 captain 裁决）。
     *
     * <p>这张表就是"内核的 PF_* 名字 → Java 的哪一位"的**唯一书面映射**；
     * 改号之前两者 19 个位全部错开（空气当活板门 / 铁轨当门 / 火焰当可通行 / 凋灵玫瑰当水方块）。
     */
    @Test
    void kernelAliasContract() {
        assertEquals(MirrorFlags.SF_AIR, kernel("PF_AIR"));
        assertEquals(MirrorFlags.SF_DOOR, kernel("PF_DOOR"));
        assertEquals(MirrorFlags.SF_OPEN, kernel("PF_DOOR_OPEN"));
        assertEquals(MirrorFlags.SF_OPEN, kernel("PF_FENCE_GATE_OPEN"));
        assertEquals(MirrorFlags.PF_TRAPDOOR, kernel("PF_TRAPDOOR"));
        assertEquals(MirrorFlags.PF_POWDER_SNOW, kernel("PF_POWDER_SNOW"));
        assertEquals(MirrorFlags.PF_CACTUS_OR_BERRY, kernel("PF_CACTUS_OR_BERRY"));
        assertEquals(MirrorFlags.PF_HONEY, kernel("PF_HONEY"));
        assertEquals(MirrorFlags.PF_COCOA, kernel("PF_COCOA"));
        assertEquals(MirrorFlags.PF_CAUTIOUS, kernel("PF_CAUTIOUS"));
        assertEquals(MirrorFlags.PF_DOOR_HAND, kernel("PF_DOOR_HAND"));
        assertEquals(MirrorFlags.PF_RAIL, kernel("PF_RAIL"));
        assertEquals(MirrorFlags.PF_LEAVES, kernel("PF_LEAVES"));
        assertEquals(MirrorFlags.PF_FENCES, kernel("PF_FENCE_TAG"));
        assertEquals(MirrorFlags.PF_WALLS, kernel("PF_WALL_TAG"));
        assertEquals(MirrorFlags.PF_FENCE_GATE, kernel("PF_FENCE_GATE"));
        assertEquals(MirrorFlags.PF_FIRE_DAMAGE, kernel("PF_FIRE_DAMAGE"));
        assertEquals(MirrorFlags.PF_PATH_THROUGH_LAND, kernel("PF_PATHFIND_LAND"));
        assertEquals(MirrorFlags.PF_WATER_BLOCK, kernel("PF_WATER_BLOCK"));
    }

    private static int kernel(String kernelName) {
        // 内核的 PF_* 名字与 Java 的 Pred 名字的对应（不是位号对应！）
        String javaName = switch (kernelName) {
            case "PF_AIR" -> "SF_AIR";
            case "PF_DOOR" -> "SF_DOOR";
            case "PF_DOOR_OPEN", "PF_FENCE_GATE_OPEN" -> "SF_OPEN";
            case "PF_FENCE_TAG" -> "PF_FENCES";
            case "PF_WALL_TAG" -> "PF_WALLS";
            case "PF_PATHFIND_LAND" -> "PF_PATH_THROUGH_LAND";
            default -> kernelName;
        };
        return MirrorFlags.Pred.valueOf(javaName).bit();
    }

    @Test
    void packAndUnknownBits() {
        boolean[] preds = new boolean[MirrorFlags.PRED_COUNT];
        preds[MirrorFlags.Pred.SF_AIR.ordinal()] = true;
        preds[MirrorFlags.Pred.PF_FENCES.ordinal()] = true;
        int flags = MirrorFlags.pack(preds);
        assertEquals(MirrorFlags.SF_AIR | MirrorFlags.PF_FENCES, flags);
        assertEquals(0, MirrorFlags.unknownBits(flags));
        assertEquals(1 << 30, MirrorFlags.unknownBits(1 << 30));
        assertThrows(IllegalArgumentException.class, () -> MirrorFlags.pack(new boolean[3]));
        assertFalse(MirrorFlags.names(flags).isEmpty());
    }

    // ------------------------------------------------------------------
    // commonNodeType：spec §5.4.6 的分支优先级（期望值独立写在测试里）
    // ------------------------------------------------------------------

    private static int type(int... bits) {
        int f = 0;
        for (int b : bits) {
            f |= b;
        }
        return MirrorFlags.commonNodeType(f);
    }

    @Test
    void commonNodeTypeBranchesAndPriority() {
        // 1. isAir 最高优先级：即使同时是活板门/门，也是 OPEN
        assertEquals(PathTypes.OPEN, type(MirrorFlags.SF_AIR));
        assertEquals(PathTypes.OPEN, type(MirrorFlags.SF_AIR, MirrorFlags.PF_TRAPDOOR, MirrorFlags.SF_DOOR));
        // 2..7 各自的分支
        assertEquals(PathTypes.TRAPDOOR, type(MirrorFlags.PF_TRAPDOOR));
        assertEquals(PathTypes.POWDER_SNOW, type(MirrorFlags.PF_POWDER_SNOW));
        assertEquals(PathTypes.DAMAGE_OTHER, type(MirrorFlags.PF_CACTUS_OR_BERRY));
        assertEquals(PathTypes.STICKY_HONEY, type(MirrorFlags.PF_HONEY));
        assertEquals(PathTypes.COCOA, type(MirrorFlags.PF_COCOA));
        assertEquals(PathTypes.DAMAGE_CAUTIOUS, type(MirrorFlags.PF_CAUTIOUS));
        // 8. 岩浆流体优先于"火焰伤害"
        assertEquals(PathTypes.LAVA, type(MirrorFlags.SF_LAVA, MirrorFlags.PF_FIRE_DAMAGE));
        // 9. 火焰伤害优先于门/铁轨/树叶/栅栏
        assertEquals(PathTypes.DAMAGE_FIRE,
                type(MirrorFlags.PF_FIRE_DAMAGE, MirrorFlags.SF_DOOR, MirrorFlags.PF_RAIL));
        // 10. 门三态
        assertEquals(PathTypes.DOOR_OPEN, type(MirrorFlags.SF_DOOR, MirrorFlags.SF_OPEN, MirrorFlags.PF_DOOR_HAND));
        assertEquals(PathTypes.DOOR_WOOD_CLOSED, type(MirrorFlags.SF_DOOR, MirrorFlags.PF_DOOR_HAND));
        assertEquals(PathTypes.DOOR_IRON_CLOSED, type(MirrorFlags.SF_DOOR));
        // 11/12
        assertEquals(PathTypes.RAIL, type(MirrorFlags.PF_RAIL));
        assertEquals(PathTypes.LEAVES, type(MirrorFlags.PF_LEAVES));
        // 13. 栅栏：FENCES / WALLS / （栅栏门且没开）
        assertEquals(PathTypes.FENCE, type(MirrorFlags.PF_FENCES));
        assertEquals(PathTypes.FENCE, type(MirrorFlags.PF_WALLS));
        assertEquals(PathTypes.FENCE, type(MirrorFlags.PF_FENCE_GATE));
        assertEquals(PathTypes.FENCE, type(MirrorFlags.PF_FENCES, MirrorFlags.SF_OPEN));
        // 开了的栅栏门不是栅栏：落到第 14/16 步
        assertEquals(PathTypes.OPEN, type(MirrorFlags.PF_FENCE_GATE, MirrorFlags.SF_OPEN, MirrorFlags.PF_PATH_THROUGH_LAND));
        // 14. 不可通行 → BLOCKED（优先于水）
        assertEquals(PathTypes.BLOCKED, type(MirrorFlags.SF_WATER));
        // 15. 水
        assertEquals(PathTypes.WATER, type(MirrorFlags.SF_WATER, MirrorFlags.PF_PATH_THROUGH_LAND));
        // 16. 兜底 OPEN
        assertEquals(PathTypes.OPEN, type(MirrorFlags.PF_PATH_THROUGH_LAND));
        // 流体的 lava 位优先于 water 位（fluidOf 的顺序）
        assertEquals(PathTypes.LAVA, type(MirrorFlags.SF_LAVA, MirrorFlags.SF_WATER, MirrorFlags.PF_PATH_THROUGH_LAND));
    }
}
