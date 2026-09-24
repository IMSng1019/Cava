package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 方块变更失效源（缺陷 1 的修复件）单测：**脱离 Minecraft 与原生库**。
 *
 * <p>三件事必须成立：
 * <ol>
 *   <li>窗口内的写入 ⇒ 命中 + 失效（这正是"改了方块下一次求解必须看到新方块"的机制）；</li>
 *   <li>窗口外的写入 ⇒ **不失效**（O(1) 判据，不能每次方块写入都做重活/都失效）；</li>
 *   <li>未登记区段（世界生成写的 proto chunk 区段）⇒ 直接返回，不碰任何东西。</li>
 * </ol>
 */
class SectionOriginRegistryTest {

    private static final Object SECTION_A = new Object();
    private static final Object SECTION_B = new Object();
    private static final Object SECTION_UNKNOWN = new Object();

    @BeforeEach
    void setUp() {
        SectionOriginRegistry.resetForTest();
        System.clearProperty(SectionOriginRegistry.PROP_INVALIDATION);
    }

    @AfterEach
    void tearDown() {
        SectionOriginRegistry.resetForTest();
        System.clearProperty(SectionOriginRegistry.PROP_INVALIDATION);
    }

    @Test
    void writeInsideWindowCountsAndInvalidates() {
        RegionRect rect = new RegionRect(0, 60, 0, 16, 16, 16);
        SectionOriginRegistry.installForTest(rect, SECTION_A, 0, 64, 0);
        SectionOriginRegistry.onSectionBlockWrite(SECTION_A, 3, 7, 5);   // 世界 (3,71,5) ∈ rect
        assertEquals(1, SectionOriginRegistry.sectionHits());
        assertEquals(1, SectionOriginRegistry.inWindowHits(), "窗口内命中必须计数（金丝雀）");
        assertEquals(0, SectionOriginRegistry.outsideWindow());
    }

    @Test
    void writeOutsideWindowDoesNotCount() {
        RegionRect rect = new RegionRect(0, 60, 0, 16, 16, 16);
        SectionOriginRegistry.installForTest(rect, SECTION_A, 0, 64, 0);
        SectionOriginRegistry.onSectionBlockWrite(SECTION_A, 15, 15, 15); // 世界 (15,79,15)：y 在窗口外
        assertEquals(1, SectionOriginRegistry.sectionHits());
        assertEquals(0, SectionOriginRegistry.inWindowHits());
        assertEquals(1, SectionOriginRegistry.outsideWindow());
    }

    @Test
    void unknownSectionIsIgnored() {
        RegionRect rect = new RegionRect(0, 60, 0, 16, 16, 16);
        SectionOriginRegistry.installForTest(rect, SECTION_A, 0, 64, 0);
        SectionOriginRegistry.onSectionBlockWrite(SECTION_UNKNOWN, 1, 1, 1);
        assertEquals(0, SectionOriginRegistry.sectionHits());
        assertEquals(0, SectionOriginRegistry.inWindowHits());
    }

    /** 两个区段交替写（打破"上一次命中"快速路径）也要正确。 */
    @Test
    void secondSectionIsAlsoTracked() {
        RegionRect rect = new RegionRect(0, 60, 0, 32, 16, 16);
        SectionOriginRegistry.installForTest(rect, SECTION_A, 0, 64, 0);
        SectionOriginRegistry.addSectionForTest(SECTION_B, 16, 64, 0);
        SectionOriginRegistry.onSectionBlockWrite(SECTION_A, 1, 1, 1);
        SectionOriginRegistry.onSectionBlockWrite(SECTION_B, 1, 1, 1);   // 世界 (17,65,1)
        SectionOriginRegistry.onSectionBlockWrite(SECTION_A, 1, 1, 1);
        assertEquals(3, SectionOriginRegistry.sectionHits());
        assertEquals(3, SectionOriginRegistry.inWindowHits());
    }

    /** 关掉失效源（可证伪对照用）后：仍然计数"窗口内命中"，但**不失效**。 */
    @Test
    void disabledFeedCountsButDoesNotInvalidate() {
        System.setProperty(SectionOriginRegistry.PROP_INVALIDATION, "false");
        RegionRect rect = new RegionRect(0, 60, 0, 16, 16, 16);
        SectionOriginRegistry.installForTest(rect, SECTION_A, 0, 64, 0);
        SectionOriginRegistry.onSectionBlockWrite(SECTION_A, 1, 1, 1);
        assertEquals(1, SectionOriginRegistry.inWindowHits());
        assertEquals(1, SectionOriginRegistry.disabledSkips());
    }

    @Test
    void clearWindowStopsTracking() {
        RegionRect rect = new RegionRect(0, 60, 0, 16, 16, 16);
        SectionOriginRegistry.installForTest(rect, SECTION_A, 0, 64, 0);
        SectionOriginRegistry.clearWindow();
        assertNull(SectionOriginRegistry.cachedRect());
        SectionOriginRegistry.onSectionBlockWrite(SECTION_A, 1, 1, 1);
        assertEquals(0, SectionOriginRegistry.sectionHits());
    }
}
