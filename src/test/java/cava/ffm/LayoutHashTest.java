package cava.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 布局与布局哈希的回归测试。
 *
 * <p>期望值来自 **C 编译器实测**（MinGW g++ 15.2，用 cava_abi.h 打 offsetof/sizeof/alignof，
 * 见 docs/CAVA-java-notes.md 的实测输出），不是手推的。任何人改动 cava_abi.h 或
 * {@link CavaLayouts} 都会在这里立刻炸出来。
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
        // 与 C 桩（native/src 之外的测试桩，逐字段 offsetof/sizeof 喂同一个 FNV-1a）实测一致
        LayoutCheck.JavaSide java = LayoutCheck.javaSide();
        // int -> 无符号 long 再比，避免 0x8.. 开头的哈希被符号扩展
        assertEquals(0xF837804DL, Integer.toUnsignedLong(java.structs().get(0).hashU32()), "CavaLayoutEntry hash(u32)");
        assertEquals(0xE9FFC021L, Integer.toUnsignedLong(java.structs().get(1).hashU32()), "CavaLayoutReport hash(u32)");
        assertEquals(0x7FDE7499L, Integer.toUnsignedLong(java.structs().get(2).hashU32()), "CavaOpenParams hash(u32)");
        assertEquals(0xFF344829L, Integer.toUnsignedLong(java.structs().get(3).hashU32()), "CavaOpenResult hash(u32)");
        assertEquals(0x6149FD30L, java.sumU32(), "layout_hash_sum(u32, 契约 2.3)");
        assertEquals(0xDB2A07EDL, java.sumBytes(), "layout_hash_sum(bytes, 头文件注释变体)");
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
    }

    @Test
    void byteWiseVariantDiffers() {
        // 两个变体必须真的不同，否则「自动协商」没有意义
        LayoutCheck.JavaSide java = LayoutCheck.javaSide();
        assertTrue(java.sumU32() != java.sumBytes());
    }
}
