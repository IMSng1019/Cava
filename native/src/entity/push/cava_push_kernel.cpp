/* cava_push_kernel.cpp -- P2 第 2 核内核实现（脱离 Minecraft 可编译可单测）。
 *
 * 语义权威：docs/CAVA-push-oracle-spec.md。全部常量与分支都带 javap 偏移号。
 * 本文件**不含任何 Minecraft 类型**，也不 include 任何 ABI 头。
 */
#include "cava_push.h"

#include <cmath>

namespace cava {
namespace push {

namespace {

/* JVM 语义的"取低 bits 位并符号扩展"。**不用有符号左移**（C++ 里溢出是 UB），
 * 也不用依赖实现定义的算术右移 —— 结果与 javap 里 (v << k) >> m 的 JVM 语义逐位相同。*/
inline int32_t sign_extend(uint64_t v, int bits) {
    const uint64_t mask = (bits >= 64) ? ~0ULL : ((1ULL << bits) - 1ULL);
    uint64_t x = v & mask;
    const uint64_t sign = 1ULL << (bits - 1);
    if (x & sign) {
        x |= ~mask;                 /* 把高位全置 1 */
    }
    return (int32_t)(int64_t)x;
}

/* MathHelper.absMax(double a, double b)（javap 字节码 272-291）：
 *     if (a < 0.0) a = -a;      // 编译成 dcmpg + ifge：NaN 时 dcmpg 得 1 -> 不取负
 *     if (b < 0.0) b = -b;
 *     return Math.max(a, b);    // Java 的 Math.max：任一 NaN 结果为 NaN
 * 注意 absMax **不是** Math.abs(Math.max(...))：NaN 原样穿过两个 if。*/
inline double abs_max(double a, double b) {
    if (!(a >= 0.0)) a = -a;        /* Java 的 (a < 0.0) 编译形状：NaN -> false */
    if (!(b >= 0.0)) b = -b;
    return cava::entity::java_max(a, b);
}

} /* namespace */

/* ================================================================ */
/* A. pushAwayFrom                                                    */
/* ================================================================ */

int push_away_from(double this_x, double this_z, double other_x, double other_z, PushDelta* out) {
    if (out == nullptr) {
        return CAVA_ENTITY_ERR_NULL;
    }
    /* 先无条件写"未进入分支"的结果：原版短路时**不碰速度**，语义等价于位移 (0,0)。*/
    out->dx = 0.0;
    out->dz = 0.0;
    out->hit = 0;

    /* 24..43：d = other.getX() - this.getX()   （顺序：先 other 后 this，减法不可交换）*/
    double d = other_x - this_x;
    /* 34..43：e = other.getZ() - this.getZ()   （字节码里 e 在 d 之前算出，但两者无依赖）*/
    double e = other_z - this_z;
    /* 45..51 */
    double f = abs_max(d, e);

    /* 53..59：dcmpl + iflt —— "f < 0.01F 或 f 是 NaN" 都跳过整个分支。
     * 写成 !(f >= C) 才能同时覆盖 NaN（C 的 >= 对 NaN 为 false）。*/
    if (!(f >= CAVA_PUSH_MIN_SEP_SQ)) {
        return CAVA_PUSH_OK;
    }

    /* 62..67：sqrt 在守卫**之后** —— 这是顺序无关正确性的唯一来源 */
    f = std::sqrt(f);
    /* 69..73 / 74..79 */
    d = d / f;
    e = e / f;
    /* 81..85 */
    double g = 1.0 / f;
    /* 87..95：dcmpl + ifle —— g <= 1.0（含 NaN）时不钳 */
    if (g > 1.0) {
        g = 1.0;
    }
    /* 97..107 */
    d = d * g;
    e = e * g;
    /* 109..121 */
    d = d * CAVA_PUSH_FACTOR;
    e = e * CAVA_PUSH_FACTOR;

    out->dx = d;
    out->dz = e;
    out->hit = 1;
    return CAVA_PUSH_OK;
}

/* ================================================================ */
/* B. Box.intersects + 顺序保持的候选过滤                              */
/* ================================================================ */

bool box_intersects(const Box6& a, const Box6& b) {
    /* 字节码 925-960：六条**严格**比较，任意一条不成立即 false。
     * 全部用正向写法（a.min < b.max 等）；NaN 时比较为 false -> 整体 false，与 JVM 的
     * dcmpg+ifge / dcmpl+ifle 组合结果一致（NaN 参与时两边都走"不成立"）。*/
    return a.min_x < b.max_x && a.max_x > b.min_x
        && a.min_y < b.max_y && a.max_y > b.min_y
        && a.min_z < b.max_z && a.max_z > b.min_z;
}

int filter_intersecting(const Box6& query, const Box6* boxes, int32_t count,
                        int32_t* out_idx, int32_t out_cap) {
    if (count < 0 || out_cap < 0) {
        return CAVA_ENTITY_ERR_ARG;
    }
    if ((count > 0 && boxes == nullptr) || (out_cap > 0 && out_idx == nullptr)) {
        return CAVA_ENTITY_ERR_NULL;
    }
    int32_t k = 0;
    for (int32_t i = 0; i < count; ++i) {
        if (!box_intersects(query, boxes[i])) {
            continue;
        }
        if (k >= out_cap) {
            /* 容量不足：**不越界写**，返回错误码，调用方整段回退原版。
             * 已写出的前缀不作数（调用方看到 rc < 0 必须丢弃）。*/
            return CAVA_ENTITY_ERR_ARG;
        }
        out_idx[k++] = i;
    }
    return k;
}

/* ================================================================ */
/* C. 区段 broadphase                                                 */
/* ================================================================ */

int64_t pack_section(int32_t x, int32_t y, int32_t z) {
    uint64_t r = 0;
    r |= ((uint64_t)(uint32_t)x & 4194303ULL) << 42;   /* x: 高 22 位 */
    r |= ((uint64_t)(uint32_t)z & 4194303ULL) << 20;   /* z: 中 22 位 */
    r |= ((uint64_t)(uint32_t)y & 1048575ULL);         /* y: 低 20 位 */
    return (int64_t)r;
}

int32_t unpack_x(int64_t packed) { return sign_extend((uint64_t)packed >> 42, 22); }
int32_t unpack_y(int64_t packed) { return sign_extend((uint64_t)packed, 20); }
int32_t unpack_z(int64_t packed) { return sign_extend((uint64_t)packed >> 20, 22); }

/* 可移植的算术右移 4 位（= floor(v / 16)，负数是向下取整）。不依赖实现定义的 signed >> 。*/
inline int32_t sar4(int32_t v) {
    if (v >= 0) {
        return (int32_t)((uint32_t)v >> 4);
    }
    return (int32_t)(-((int64_t)(((uint32_t)(-(int64_t)v) + 15u) >> 4)));
}

int32_t section_coord(double d) {
    /* ChunkSectionPos.getSectionCoord(double) 字节码 158-163：
     *     return getSectionCoord(MathHelper.floor(d));      // -> floor(d) >> 4
     * MathHelper.floor(double) 字节码 108-123: int i = (int)d; return d < (double)i ? i-1 : i;
     * (int)d 是 JVM d2i（饱和），由第 1 核的 java_d2i_sat 提供唯一实现。
     * **本轮实测踩到：第一版只写了 floor、漏了 >> 4，真值表 SP-1..SP-5 立刻变红。** */
    int32_t i = cava::entity::java_d2i_sat(d);
    if ((double)i > d) {                       /* 等价于 d < (double)i（NaN 时 false -> 返回 i）*/
        i = (i == INT32_MIN) ? INT32_MAX : (i - 1);      /* 复刻 Java 的 int 回绕，不用 UB 减法 */
    }
    return sar4(i);
}

int section_plan(const Box6& box, const int64_t* sorted_positions, int32_t count,
                 int64_t* out, int32_t out_cap) {
    if (count < 0 || out_cap < 0) {
        return CAVA_ENTITY_ERR_ARG;
    }
    if ((count > 0 && sorted_positions == nullptr) || (out_cap > 0 && out == nullptr)) {
        return CAVA_ENTITY_ERR_NULL;
    }
    if (count == 0) {
        return 0;
    }

    /* 字节码 33-76：注意 maxY 那一项是 dconst_0; dadd（加 +0.0），minY 是减 4.0 —— 窗口不对称 */
    const int32_t x_min = section_coord(box.min_x - 2.0);
    const int32_t y_min = section_coord(box.min_y - 4.0);
    const int32_t z_min = section_coord(box.min_z - 2.0);
    const int32_t x_max = section_coord(box.max_x + 2.0);
    const int32_t y_max = section_coord(box.max_y + 0.0);
    const int32_t z_max = section_coord(box.max_z + 2.0);

    /* 早退上界：数组按打包值升序，而打包值 = x<<42 | z<<20 | y ⇒ unpack_x 沿数组**单调不减**
     * （掩码 x 在 [0,2^22) 上与 x 同序；负 x 的打包值在最高位为 1，整体仍排在正 x 之前，
     *   而负 x 之间按掩码升序 = 按 x 升序）。所以 x 超过最后一个元素的 x 之后不可能再命中。*/
    const int32_t max_present_x = unpack_x(sorted_positions[count - 1]);

    int32_t k = 0;
    int32_t x = x_min;
    for (;;) {
        if (x > max_present_x) {
            break;
        }
        const int64_t lo_key = pack_section(x, 0, 0);
        const int64_t hi_key = pack_section(x, -1, -1);
        /* Java: trackedPositions.subSet(lo_key, hi_key + 1)；hi_key+1 在 x = 0x3FFFFF 时
         * 回绕成 0（signed），此时区间为空 —— 用无符号加法复刻，不做 UB 算术。*/
        const int64_t hi_excl = (int64_t)((uint64_t)hi_key + 1ULL);

        /* lower_bound：第一个 positions[i] >= lo_key（**有符号**比较，与 LongAVLTreeSet 一致）*/
        int32_t lo = 0, hi = count;
        while (lo < hi) {
            const int32_t mid = lo + (hi - lo) / 2;
            if (sorted_positions[mid] < lo_key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        for (int32_t i = lo; i < count; ++i) {
            const int64_t pos = sorted_positions[i];
            if (pos >= hi_excl) {
                break;
            }
            const int32_t y = unpack_y(pos);
            const int32_t z = unpack_z(pos);
            /* 字节码 162-187：四条 if_icmp 全部是 continue（不是 break）*/
            if (y < y_min || y > y_max || z < z_min || z > z_max) {
                continue;
            }
            if (k >= out_cap) {
                return CAVA_ENTITY_ERR_ARG;   /* 容量不足 -> 调用方整段回退原版 */
            }
            out[k++] = pos;
        }
        if (x == x_max) {
            break;                            /* 避免 x_max == INT32_MAX 时 ++x 溢出 */
        }
        ++x;
    }
    return k;
}

} /* namespace push */
} /* namespace cava */
