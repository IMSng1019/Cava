/* cava_numeric.cpp —— 跨语言逐位一致的数值工具（B1）。
 *
 * 铁律（契约 2.1 / 2.4）：只有 + - * / 与 sqrt 允许跨到原生侧；
 * double->int 必须走这里的饱和转换（C++ 直接转是 UB，x86 上会给 INT_MIN）。
 * Java 语义（JLS 5.1.3 narrowing primitive conversion）：
 *     NaN            -> 0
 *     d <= INT_MIN   -> INT_MIN
 *     d >= INT_MAX   -> INT_MAX
 *     其余            -> 截断向零
 */
#include "cava_internal.h"

#include <cstring>

extern "C" CAVA_EXPORT int32_t cava_d2i_sat(double v) {
    if (v != v) {
        return 0; /* NaN -> 0（与 Java 的 (int)Double.NaN 一致）*/
    }
    if (v <= -2147483648.0) {
        return (-2147483647 - 1);
    }
    if (v >= 2147483647.0) {
        return 2147483647;
    }
    return (int32_t)v; /* 此处 |v| < 2^31，截断向零是精确定义的 */
}

extern "C" CAVA_EXPORT int64_t cava_d2l_sat(double v) {
    if (v != v) {
        return 0;
    }
    /* 9223372036854775807.0 这个字面量按 IEEE 舍入正好等于 2^63，
     * 与 Java 把 Long.MAX_VALUE 提升成 double 的结果一致。*/
    if (v <= -9223372036854775808.0) {
        return (-9223372036854775807LL - 1);
    }
    if (v >= 9223372036854775807.0) {
        return 9223372036854775807LL;
    }
    return (int64_t)v;
}

extern "C" CAVA_EXPORT uint64_t cava_bits_of_double(double v) {
    uint64_t bits = 0;
    /* 用 memcpy 而非 union 别名：后者是严格别名 UB（-O2 下会真的咬人）。*/
    static_assert(sizeof(bits) == sizeof(v), "double 必须是 8 字节");
    std::memcpy(&bits, &v, sizeof(bits));
    return bits;
}

extern "C" CAVA_EXPORT double cava_double_of_bits(uint64_t bits) {
    double v = 0.0;
    static_assert(sizeof(bits) == sizeof(v), "double 必须是 8 字节");
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}
