package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code CAVA_PNT_*} 的索引语义 vs 原版 {@code PathNodeType} 的枚举 ordinal。
 *
 * <p><b>证据</b>：{@code docs/CAVA-pathfind-oracle-spec.md} 第 8 节 + 本机
 * {@code javap -p -c net.minecraft.entity.ai.pathing.PathNodeType} 的 {@code static{}} 逐条重建，
 * 与 {@code docs/CAVA-gates.md} 门禁 #7 记录的值一致。这里把它写成一个**独立的常量表**，
 * 与 {@code cava_abi.h} 的 {@code CAVA_PNT_*} 表互为交叉验证 ——
 * 任一侧错位，{@link AbiPenaltyOrder#checkAgainstVanillaOrder} 就会报出来，
 * 从而让 {@code PathfindHook} 拒绝原生接管（错位的惩罚表"路径照样算得出来，只是不与原版一致"）。
 */
class AbiPenaltyOrderTest {

    /** Yarn 1.20.4 {@code PathNodeType} 的 ordinal 顺序（javap {@code static{}} 实证）。 */
    private static final List<String> VANILLA_ORDINALS = List.of(
            "BLOCKED", "OPEN", "WALKABLE", "WALKABLE_DOOR", "TRAPDOOR", "POWDER_SNOW",
            "DANGER_POWDER_SNOW", "FENCE", "LAVA", "WATER", "WATER_BORDER", "RAIL",
            "UNPASSABLE_RAIL", "DANGER_FIRE", "DAMAGE_FIRE", "DANGER_OTHER", "DAMAGE_OTHER",
            "DOOR_OPEN", "DOOR_WOOD_CLOSED", "DOOR_IRON_CLOSED", "BREACH", "LEAVES",
            "STICKY_HONEY", "COCOA", "DAMAGE_CAUTIOUS", "DANGER_TRAPDOOR");

    @Test
    void abiOrderMatchesTheVanillaEnumOrdinals() {
        assertEquals(26, AbiPenaltyOrder.COUNT);
        assertEquals(26, AbiPenaltyOrder.ABI_ORDER.size());
        assertEquals(26, VANILLA_ORDINALS.size());
        List<String> problems = AbiPenaltyOrder.checkAgainstVanillaOrder(VANILLA_ORDINALS);
        assertEquals(List.of(), problems,
                "CAVA_PNT_* 的索引必须等于 PathNodeType 的 ordinal；不等就是惩罚表整体错位");
    }

    @Test
    void tableHasNoDuplicates() {
        assertEquals(26, AbiPenaltyOrder.ABI_ORDER.stream().distinct().count());
        assertEquals(26, VANILLA_ORDINALS.stream().distinct().count());
    }

    @Test
    void detectorActuallyDetectsAShift() {
        // 阴性对照：把第 5 项换成 FENCE（这正是 cava_abi.h 曾经犯过的错），检测器必须报出来
        java.util.ArrayList<String> shifted = new java.util.ArrayList<>(VANILLA_ORDINALS);
        shifted.set(5, "FENCE");
        List<String> problems = AbiPenaltyOrder.checkAgainstVanillaOrder(shifted);
        assertTrue(problems.size() >= 1, "错位必须被检出：" + problems);
        assertTrue(problems.get(0).contains("index 5"), problems.toString());
        // 条目数不等也要报
        assertEquals(1, AbiPenaltyOrder.checkAgainstVanillaOrder(VANILLA_ORDINALS.subList(0, 25)).size());
    }
}
