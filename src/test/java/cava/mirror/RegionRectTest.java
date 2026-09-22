package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 窗口规划测试（边距的推导写在 {@link RegionRect} 的类注释里）。 */
class RegionRectTest {

    @Test
    void cornersAndVolume() {
        RegionRect r = RegionRect.ofCorners(3, 60, -5, 1, 62, -1);
        assertEquals(1, r.minX());
        assertEquals(3, r.maxX());
        assertEquals(60, r.minY());
        assertEquals(62, r.maxY());
        assertEquals(-5, r.minZ());
        assertEquals(-1, r.maxZ());
        assertEquals(3 * 3 * 5, r.volume());
        assertTrue(r.describe().contains("3x3x5"));
    }

    @Test
    void rejectsEmptyDims() {
        assertThrows(IllegalArgumentException.class, () -> new RegionRect(0, 0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RegionRect(0, 0, 0, 1, -1, 1));
    }

    @Test
    void marginsFollowDocumentedFormulas() {
        // 水平：max(4, floor(w/2)+2)
        assertEquals(4, RegionRect.horizontalMargin(0.6f));
        assertEquals(4, RegionRect.horizontalMargin(1.0f));
        assertEquals(4, RegionRect.horizontalMargin(1.4f));
        assertEquals(4, RegionRect.horizontalMargin(1.5f));    // floor(0.75)+2 = 2 -> max(4,2) = 4
        assertEquals(6, RegionRect.horizontalMargin(8.0f));    // floor(4)+2 = 6
        // 向上：max(4, floor(h+1)+1)
        assertEquals(4, RegionRect.upMargin(1.8f));            // floor(2.8)+1 = 3 -> 4
        assertEquals(4, RegionRect.upMargin(2.0f));            // floor(3.0)+1 = 4
        assertEquals(5, RegionRect.upMargin(3.0f));            // floor(4)+1 = 5
        // 向下：safeFallDistance + 4
        assertEquals(4, RegionRect.downMargin(0));
        assertEquals(7, RegionRect.downMargin(3));
        assertEquals(24, RegionRect.downMargin(20));
    }

    @Test
    void solveWindowIsBoundingBoxPlusMarginsAndClampedToWorld() {
        RegionRect r = RegionRect.forSolve(0, 64, 0, 10, 66, -4, 0.6f, 1.8f, 3, -64, 319);
        assertEquals(-4, r.minX());
        assertEquals(14, r.maxX());
        assertEquals(-8, r.minZ());
        assertEquals(4, r.maxZ());
        assertEquals(64 - 7, r.minY());
        assertEquals(66 + 4, r.maxY());
        // Y 裁剪
        RegionRect low = RegionRect.forSolve(0, -60, 0, 0, -60, 0, 0.6f, 1.8f, 20, -64, 319);
        assertEquals(-64, low.minY());
        RegionRect high = RegionRect.forSolve(0, 318, 0, 0, 318, 0, 0.6f, 1.8f, 3, -64, 319);
        assertEquals(319, high.maxY());
    }

    @Test
    void solveWindowVolumeIsReasonable() {
        RegionRect r = RegionRect.forSolve(0, 64, 0, 40, 64, 0, 0.6f, 1.8f, 3, -64, 319);
        // x: -4..44 = 49；y: 57..68 = 12；z: -4..4 = 9
        assertEquals(49, r.dimX());
        assertEquals(12, r.dimY());
        assertEquals(9, r.dimZ());
        assertTrue(r.volume() < 1 << 21, "默认窗口必须远低于 2M 上限");
    }
}
