package cava.parity;

/**
 * FNV-1a 64（契约 4.2：黄金轨迹的全部哈希都用它）。
 *
 * <p>**自己实现**，不依赖 MC 的哈希、不用 CRC/xxhash：Java 与 C++ 都能逐位重现。
 * 逐字节处理（不是逐 long），因为跨语言只有「字节流」是明确一致的。
 */
public final class Fnv1a {

    private Fnv1a() {
    }

    /** FNV-1a 64 offset basis。 */
    public static final long OFFSET_BASIS = 0xCBF29CE484222325L;

    /** FNV-1a 64 prime。 */
    public static final long PRIME = 0x00000100000001B3L;

    /** 新建一个哈希状态。 */
    public static long begin() {
        return OFFSET_BASIS;
    }

    /** 喂 1 字节。 */
    public static long updateByte(long h, int b) {
        h ^= (b & 0xFF);
        h *= PRIME;
        return h;
    }

    /** 喂一个 long 的 8 字节小端表示。 */
    public static long updateLong(long h, long value) {
        for (int i = 0; i < 8; i++) {
            h = updateByte(h, (int) ((value >>> (8 * i)) & 0xFF));
        }
        return h;
    }

    /** 喂一个 int 的 4 字节小端表示（用于 state id / 坐标等）。 */
    public static long updateInt(long h, int value) {
        for (int i = 0; i < 4; i++) {
            h = updateByte(h, (value >>> (8 * i)) & 0xFF);
        }
        return h;
    }

    /** 喂一个 double 的**原始位模式**（禁止用数值本身，否则跨语言不保真）。 */
    public static long updateDouble(long h, double value) {
        return updateLong(h, Double.doubleToRawLongBits(value));
    }

    /** 喂字符串的 UTF-8 字节。 */
    public static long updateString(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                h = updateByte(h, c);
            } else if (c < 0x800) {
                h = updateByte(h, 0xC0 | (c >> 6));
                h = updateByte(h, 0x80 | (c & 0x3F));
            } else if (Character.isSurrogate(c)) {
                // 与 UTF-8 编码一致：把代理对合成码点再编 4 字节
                int cp = s.codePointAt(i);
                if (Character.charCount(cp) == 2) {
                    i++;
                }
                h = updateByte(h, 0xF0 | (cp >> 18));
                h = updateByte(h, 0x80 | ((cp >> 12) & 0x3F));
                h = updateByte(h, 0x80 | ((cp >> 6) & 0x3F));
                h = updateByte(h, 0x80 | (cp & 0x3F));
            } else {
                h = updateByte(h, 0xE0 | (c >> 12));
                h = updateByte(h, 0x80 | ((c >> 6) & 0x3F));
                h = updateByte(h, 0x80 | (c & 0x3F));
            }
        }
        return h;
    }

    /** 喂 UTF-8 字节数组（例如从文件读回来的原字节）。 */
    public static long updateBytes(long h, byte[] bytes) {
        for (byte b : bytes) {
            h = updateByte(h, b);
        }
        return h;
    }

    /** 便捷：对若干 long 求 FNV-1a 64。 */
    public static long hashLongs(long... values) {
        long h = begin();
        for (long v : values) {
            h = updateLong(h, v);
        }
        return h;
    }

    /** 便捷：对字符串求 FNV-1a 64。 */
    public static long hashString(String s) {
        return updateString(begin(), s);
    }

    /** 十六进制 16 位小写（轨迹里的哈希字段格式）。 */
    public static String hex16(long value) {
        StringBuilder sb = new StringBuilder(16);
        String hex = Long.toUnsignedString(value, 16);
        for (int i = hex.length(); i < 16; i++) {
            sb.append('0');
        }
        sb.append(hex);
        return sb.toString();
    }
}
