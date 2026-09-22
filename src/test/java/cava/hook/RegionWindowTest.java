package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 区域窗口（纯计算）。
 *
 * <p>为什么值得测：窗口给小了，区域外的方块在原生侧等价于"无碰撞空格" ⇒ **路径会穿墙**，
 * 而且运行期完全看不出来。所以这里把"可达球必在外接盒内"这条性质钉死。
 */
class RegionWindowTest {

    @Test
    void halfExtentCoversTheReachableBall() {
        // maxRange = 16 ⇒ 半长必须是 ceil(16)+1 = 17（球外的方块一律不参与，少一个都可能漏）
        RegionWindow.Window w = RegionWindow.compute(0, 64, 0, 16.0f, -64, 319, 100_000_000L);
        assertNotNull(w);
        assertEquals(17, w.dimX() / 2);
        assertEquals(35, w.dimX());
        assertEquals(35, w.dimZ());
        assertEquals(-17, w.minX());
        assertEquals(-17, w.minZ());
        assertEquals(35, w.dimY(), "Y 也要覆盖 ±half：64-17 .. 64+17");
        assertEquals((long) w.dimX() * w.dimY() * w.dimZ(), w.volume());
        assertEquals(35L * 35L * 35L, w.volume());
    }

    @Test
    void fractionalMaxRangeRoundsUp() {
        RegionWindow.Window w = RegionWindow.compute(0, 64, 0, 16.5f, -64, 319, 100_000_000L);
        assertNotNull(w);
        // ceil(16.5) = 17, +1 = 18
        assertEquals(-18, w.minX());
        assertEquals(37, w.dimX());
    }

    @Test
    void yIsClampedToWorldBounds() {
        // startY = -60、half = ceil(4)+1 = 5 ⇒ 原始区间 [-65, -54]；下界被夹到 bottomY = -64
        RegionWindow.Window w = RegionWindow.compute(0, -60, 0, 4.0f, -64, 319, 100_000_000L);
        assertNotNull(w);
        assertEquals(-64, w.minY(), "下界必须夹到 bottomY");
        assertEquals(-55, w.minY() + w.dimY() - 1, "上界 = startY + half（-60 + 5）");
        assertEquals(10, w.dimY());
        // 上界同样要夹（否则会传出世界高度，原生侧读到区域外 = 无碰撞空气）
        RegionWindow.Window top = RegionWindow.compute(0, 318, 0, 4.0f, -64, 319, 100_000_000L);
        assertNotNull(top);
        assertEquals(319, top.minY() + top.dimY() - 1, "上界必须夹到 topY");
    }

    @Test
    void unusableInputsReturnNull() {
        assertNull(RegionWindow.compute(0, 64, 0, Float.NaN, -64, 319, 1L << 40), "NaN 不可用");
        assertNull(RegionWindow.compute(0, 64, 0, 0.0f, -64, 319, 1L << 40), "0 不可用");
        assertNull(RegionWindow.compute(0, 64, 0, -1.0f, -64, 319, 1L << 40), "负数不可用");
        assertNull(RegionWindow.compute(0, 64, 0, Float.POSITIVE_INFINITY, -64, 319, 1L << 40), "+Inf 不可用");
    }

    @Test
    void volumeOverCapIsRejectedNotTruncated() {
        // 35^3 = 42875；cap 给 42874 ⇒ 必须整体拒绝（**绝不部分推送**）
        RegionWindow.Window w = RegionWindow.compute(0, 64, 0, 16.0f, -64, 319, 42_874L);
        assertNull(w, "超过体积上限必须回退，不能推一个缺角的长方体");
        RegionWindow.Window ok = RegionWindow.compute(0, 64, 0, 16.0f, -64, 319, 42_875L);
        assertNotNull(ok);
        assertTrue(ok.fits(42_875L));
    }
}
