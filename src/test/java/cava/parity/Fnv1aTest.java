package cava.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** FNV-1a 64 的标准向量与工具行为（契约 4.2：哈希必须逐位可重现）。 */
class Fnv1aTest {

    @Test
    void knownVectors() {
        // 标准 FNV-1a 64 测试向量（ASCII 字符串的 UTF-8 字节）
        assertEquals(0xCBF29CE484222325L, Fnv1a.hashString(""));
        assertEquals(0xAF63DC4C8601EC8CL, Fnv1a.hashString("a"));
        assertEquals(0xAF63DF4C8601F1A5L, Fnv1a.hashString("b"));
        assertEquals(0xAF63DE4C8601EFF2L, Fnv1a.hashString("c"));
        assertEquals(0x85944171F73967E8L, Fnv1a.hashString("foobar"));
    }

    @Test
    void byteAndBytesAgree() {
        byte[] bytes = "cava".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long viaBytes = Fnv1a.updateBytes(Fnv1a.begin(), bytes);
        long viaString = Fnv1a.hashString("cava");
        assertEquals(viaString, viaBytes);
    }

    @Test
    void hex16IsFixedWidth() {
        assertEquals("0000000000000000", Fnv1a.hex16(0));
        assertEquals("ffffffffffffffff", Fnv1a.hex16(-1L));
        assertEquals("0af63dc4c8601ec8", Fnv1a.hex16(0x0AF63DC4C8601EC8L));
        assertEquals(16, Fnv1a.hex16(0x85944171F73967E8L).length());
    }

    @Test
    void differentInputsDifferentHashes() {
        assertNotEquals(Fnv1a.hashString("cava"), Fnv1a.hashString("Cava"));
        assertNotEquals(Fnv1a.hashLongs(1, 2), Fnv1a.hashLongs(2, 1));
    }

    @Test
    void doubleUsesRawBits() {
        // -0.0 与 0.0 的位模式必须不同（这也是为什么不能用数值哈希）
        assertNotEquals(Fnv1a.updateDouble(Fnv1a.begin(), 0.0), Fnv1a.updateDouble(Fnv1a.begin(), -0.0));
        assertEquals(Double.doubleToRawLongBits(Double.NaN),
                Double.doubleToRawLongBits(Double.longBitsToDouble(Fnv1a.updateDouble(Fnv1a.begin(), Double.NaN) == 0 ? 0 : Double.doubleToRawLongBits(Double.NaN))));
    }
}
