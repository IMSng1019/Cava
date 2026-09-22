package cava.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 布局与布局哈希的回归测试。
 *
 * <p>期望值有两个来源，都不是手推的：
 * <ol>
 *   <li>C 编译器实测：MinGW g++ 15.2 用 {@code cava_abi.h} 打 offsetof/sizeof/alignof；</li>
 *   <li>真实 {@code cava.dll} 实测：{@code cava_layout_report()} + {@code cava_open()}
 *       在本机算出同一个 {@code layout_hash_sum}（见 docs/CAVA-java-notes.md）。</li>
 * </ol>
 * 任何人改动 cava_abi.h / {@link CavaLayouts} / native 侧 kLayouts 都会在这里立刻炸出来。
 */
class LayoutHashTest {

    @Test
    void cLayoutMatchesJavaLayout() {
        assertEquals(List.of(), CavaLayouts.checkAgainstCAbi());
    }

    @Test
    void offsetsMatchCompiler() {
        assertStruct("CavaLayoutEntry", 544, 8, new long[]{0, 4, 8, 16, 24, 28, 32, 288});
        assertStruct("CavaLayoutReport", 34848, 8, new long[]{0, 4, 8, 12, 16, 20, 24, 32});
        assertStruct("CavaOpenParams", 32, 8, new long[]{0, 4, 8, 16, 24});
        assertStruct("CavaOpenResult", 24, 8, new long[]{0, 4, 8, 16});
        // P1 追加的 5 个（2026-09-22 随 ABI 登记）
        assertStruct("CavaPathRequest", 56, 8, new long[]{0, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52});
        assertStruct("CavaPathNode", 32, 4, new long[]{0, 4, 8, 12, 16, 20, 24, 28});
        assertStruct("CavaMobProfile", 192, 8,
                new long[]{0, 104, 112, 120, 128, 136, 140, 144, 148, 152, 156, 160, 164, 168, 172, 176, 180, 184});
        assertStruct("CavaStateRecord", 20, 4, new long[]{0, 4, 8, 12, 16});
        assertStruct("CavaCollisionBox", 24, 4, new long[]{0, 4, 8, 12, 16, 20});
    }

    private static void assertStruct(String name, long size, long align, long[] offsets) {
        CavaLayouts.Struct s = CavaLayouts.STRUCTS.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        assertEquals(size, s.size(), name + " sizeof");
        assertEquals(align, s.align(), name + " alignof");
        assertEquals(offsets.length, s.fieldCount(), name + " 字段数");
        List<Long> actual = new ArrayList<>();
        for (CavaLayouts.Field f : s.fields()) {
            actual.add(f.offset());
        }
        for (int i = 0; i < offsets.length; i++) {
            assertEquals(offsets[i], actual.get(i), name + " 第 " + i + " 个字段 offset");
        }
    }

    @Test
    void layoutHashesAreStable() {
        LayoutCheck.JavaSide java = LayoutCheck.javaSide();
        long[] expected = {
                0xF837804DL, // CavaLayoutEntry
                0xE9FFC021L, // CavaLayoutReport
                0x7FDE7499L, // CavaOpenParams
                0xFF344829L, // CavaOpenResult
                0xE566F98DL, // CavaPathRequest
                0x0DCFFE65L, // CavaPathNode
                0x9C6C98CDL, // CavaMobProfile
                0x53797229L, // CavaStateRecord
                0x250ECBE1L, // CavaCollisionBox
                // --- P2 实体位移（2026-09-22 冻结）---
                0x0DCFFE65L, // CavaShapeRecord  ← 与 CavaPathNode 同值：都是连续 8 个 4 字节字段
                0x1545B999L, // CavaMoveShapeRef
                0xB542D3D5L, // CavaMoveRequest
                0x630C22D5L, // CavaMoveEvent
                0x7737ABBDL, // CavaMoveResult
        };
        assertEquals(expected.length, java.structs().size(), "导出结构体个数");
        for (int i = 0; i < expected.length; i++) {
            // int -> 无符号 long 再比，避免 0x8.. 开头的哈希被符号扩展
            assertEquals(expected[i], Integer.toUnsignedLong(java.structs().get(i).hashU32()),
                    java.structs().get(i).name() + " layout_hash");
        }
        assertEquals(0x1C12265EL, java.sumU32(), "layout_hash_sum（14 个结构体；真实 cava.dll 算出同值）");
    }

    @Test
    void arraysCountAsOneField() {
        // CavaLayoutReport.entries[64] 是一个字段（offset=32, size=34816），否则字段数会超过 32 上限
        CavaLayouts.Struct report = CavaLayouts.STRUCTS.get(1);
        CavaLayouts.Field entries = report.fields().get(7);
        assertEquals("entries", entries.name());
        assertEquals(32, entries.offset());
        assertEquals(64L * 544L, entries.size());
        assertTrue(report.fieldCount() <= CavaLayouts.LAYOUT_MAX_FIELDS);
        // CavaMobProfile.penalty[26] 也是一个字段
        CavaLayouts.Struct profile = CavaLayouts.STRUCTS.get(6);
        assertEquals("penalty", profile.fields().get(0).name());
        assertEquals(0, profile.fields().get(0).offset());
        assertEquals(26L * 4L, profile.fields().get(0).size());
    }
}
