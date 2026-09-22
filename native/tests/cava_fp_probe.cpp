/* cava_fp_probe.cpp —— 数值一致性探针（B4）。
 *
 * 干什么：对**固定写死在源码里**的输入向量，把本平台 + - * / sqrt 的结果按位打印成
 * 文本，交给 Java 侧（native/tests/java/FpProbeJava.java 或 src/test 里的单元层测试）
 * 逐位比对。P0 就在 native 上跑这个，是为了在写任何游戏逻辑之前先确认
 * 「原生算出来的 double 和 Java 逐位一致」这条地基。
 *
 * 输出（UTF-8 / LF / 逐行）：
 *     # 注释行（可跳过）
 *     add  <a_bits:16hex> <b_bits:16hex> <result_bits:16hex>
 *     sub / mul / div 同形
 *     sqrt <a_bits:16hex> 0000000000000000 <result_bits:16hex>   （单目，b 恒为 0）
 *
 * 输入向量（全部来自源码里的常量 / 固定种子 PRNG，完全可重复）：
 *   - 2000 组随机位模式对（splitmix64, seed 见 kSeed）
 *   - kSpecials（40 个）的两两笛卡尔积
 *   - sqrt：所有 a 中 !(a < 0) 的（覆盖 NaN / ±0 / +inf / 次正规 / 正规）
 *
 * 用法：cava_fp_probe [输出路径]   默认 native/tests/vectors/fp_probe.txt（相对当前目录）
 */
#include "../include/cava_abi.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

const uint64_t kSeed = 0x0F1E2D3C4B5A6978ull;
const int      kRandomPairs = 2000;

/* 特殊值：±0 / ±1 / ±0.5 / ±2 / 3 / ±inf / 各种 NaN 载荷 / 最大有限 / 最小正规 /
 * 最大最小次正规 / 整型与 2 的幂边界。*/
const uint64_t kSpecials[] = {
    0x0000000000000000ull, /* +0.0 */
    0x8000000000000000ull, /* -0.0 */
    0x3FF0000000000000ull, /* 1.0 */
    0xBFF0000000000000ull, /* -1.0 */
    0x3FE0000000000000ull, /* 0.5 */
    0xBFE0000000000000ull, /* -0.5 */
    0x4000000000000000ull, /* 2.0 */
    0xC000000000000000ull, /* -2.0 */
    0x4008000000000000ull, /* 3.0 */
    0xC008000000000000ull, /* -3.0 */
    0x7FF0000000000000ull, /* +inf */
    0xFFF0000000000000ull, /* -inf */
    0x7FF8000000000000ull, /* 规范 quiet NaN */
    0xFFF8000000000000ull, /* 负 quiet NaN */
    0x7FF0000000000001ull, /* signaling NaN（最小载荷）*/
    0x7FFFFFFFFFFFFFFFull, /* NaN 最大载荷 */
    0xFFF0000000000001ull, /* 负 signaling NaN */
    0x7FEFFFFFFFFFFFFFull, /* DBL_MAX */
    0xFFEFFFFFFFFFFFFFull, /* -DBL_MAX */
    0x0010000000000000ull, /* DBL_MIN（最小正规）*/
    0x8010000000000000ull, /* -DBL_MIN */
    0x0000000000000001ull, /* 最小次正规 */
    0x8000000000000001ull, /* -最小次正规 */
    0x000FFFFFFFFFFFFFull, /* 最大次正规 */
    0x0000000000000002ull, /* 2 * 最小次正规 */
    0x3E112E0BE826D695ull, /* 1e-9 */
    0x3FD5555555555555ull, /* 1/3 的 double 近似 */
    0x3FEFFFFFFFFFFFFFull, /* 0.9999999999999999 */
    0x41DFFFFFFFFFFFFFull, /* 2^31 - 1 = 2147483647.0 */
    0x41E0000000000000ull, /* 2^31 = 2147483648.0 */
    0xC1E0000000000000ull, /* -2^31 */
    0x41E0000000000001ull, /* 2^31 + 2 */
    0x4330000000000000ull, /* 2^52 */
    0x4330000000000001ull, /* 2^52 + 1 */
    0x4340000000000000ull, /* 2^53 */
    0x43DFFFFFFFFFFFFFull, /* 2^63 - 1024（最大可转 long 的 double）*/
    0x43E0000000000000ull, /* 2^63 */
    0xC3E0000000000000ull, /* -2^63 */
    0x7FE0000000000000ull, /* ~8.98846567431158e307 */
    0x43ABC16D674EC800ull, /* 1e18 */
};

uint64_t splitmix64(uint64_t& s) {
    uint64_t z = (s += 0x9E3779B97F4A7C15ull);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

inline double D(uint64_t bits) {
    return cava_double_of_bits(bits);
}

inline uint64_t B(double v) {
    return cava_bits_of_double(v);
}

void emit(std::FILE* f, const char* op, uint64_t a, uint64_t b, uint64_t r, long long& n) {
    std::fprintf(f, "%s %016llx %016llx %016llx\n", op,
                 (unsigned long long)a, (unsigned long long)b, (unsigned long long)r);
    ++n;
}

} /* namespace */

int main(int argc, char** argv) {
    /* CTest 会把本文件也当成一个用例无参调用（native/tests/CMakeLists.txt：每个 .cpp 一个 target），
     * 所以无参时**不写任何文件**、只提示怎么跑 —— 避免测试运行悄悄改写 git 里的黄金向量。
     * 生成向量：cava_fp_probe.exe native/tests/vectors/fp_probe.txt（build-mingw.ps1 就是这么调的）。*/
    if (argc < 2) {
        std::printf("SKIP: 需要显式给输出路径（用法: cava_fp_probe <输出文件>），"
                    "例如 native/tests/vectors/fp_probe.txt\n");
        return 0;
    }
    const char* path = argv[1];
    std::FILE* f = std::fopen(path, "wb");
    if (f == nullptr) {
        std::fprintf(stderr, "cava_fp_probe: 打不开输出文件 %s\n", path);
        return 2;
    }

    std::vector<uint64_t> ra(kRandomPairs), rb(kRandomPairs);
    uint64_t s = kSeed;
    for (int i = 0; i < kRandomPairs; ++i) {
        ra[i] = splitmix64(s);
        rb[i] = splitmix64(s);
    }

    long long pairs = 0, sqrts = 0;

    std::fprintf(f, "# cava_fp_probe v1\n");
    std::fprintf(f, "# build_id: %s\n", cava_build_id());
    std::fprintf(f, "# abi_version: %d\n", (int)cava_abi_version());
    std::fprintf(f, "# 格式: <op> <a_bits:16hex> <b_bits:16hex> <result_bits:16hex>; op in {add,sub,mul,div,sqrt}\n");
    std::fprintf(f, "# sqrt 是单目: b_bits 恒为 0000000000000000; 输入只取 !(a < 0) 的向量（含 NaN / ±0 / +inf）\n");
    std::fprintf(f, "# 向量: %d 组随机位模式对(splitmix64 seed=0x%016llx) + %d 个特殊值笛卡尔积\n",
                 kRandomPairs, (unsigned long long)kSeed,
                 (int)(sizeof(kSpecials) / sizeof(kSpecials[0])));
    std::fprintf(f, "# 生成器: native/tests/cava_fp_probe.cpp（固定输入，可重复）\n");

    /* ---- 随机对 ----
     * CAVA_FP_PROBE_SWAP_COMMUTATIVE：只在做「双 NaN 载荷差异来源」实验时用。
     * 定义后 add/mul 写成 b op a（加法/乘法可交换，数值结果不变，只有
     * 「两个操作数都是 NaN 时选谁的载荷」会变）。*/
    for (int i = 0; i < kRandomPairs; ++i) {
        const double a = D(ra[i]);
        const double b = D(rb[i]);
#ifdef CAVA_FP_PROBE_SWAP_COMMUTATIVE
        emit(f, "add", ra[i], rb[i], B(b + a), pairs);
        emit(f, "sub", ra[i], rb[i], B(b - a), pairs);
        emit(f, "mul", ra[i], rb[i], B(b * a), pairs);
        emit(f, "div", ra[i], rb[i], B(b / a), pairs);
#else
        emit(f, "add", ra[i], rb[i], B(a + b), pairs);
        emit(f, "sub", ra[i], rb[i], B(a - b), pairs);
        emit(f, "mul", ra[i], rb[i], B(a * b), pairs);
        emit(f, "div", ra[i], rb[i], B(a / b), pairs);
#endif
    }

    /* ---- 特殊值笛卡尔积 ---- */
    const int nspec = (int)(sizeof(kSpecials) / sizeof(kSpecials[0]));
    for (int i = 0; i < nspec; ++i) {
        for (int j = 0; j < nspec; ++j) {
            const double a = D(kSpecials[i]);
            const double b = D(kSpecials[j]);
#ifdef CAVA_FP_PROBE_SWAP_COMMUTATIVE
            emit(f, "add", kSpecials[i], kSpecials[j], B(b + a), pairs);
            emit(f, "sub", kSpecials[i], kSpecials[j], B(b - a), pairs);
            emit(f, "mul", kSpecials[i], kSpecials[j], B(b * a), pairs);
            emit(f, "div", kSpecials[i], kSpecials[j], B(b / a), pairs);
#else
            emit(f, "add", kSpecials[i], kSpecials[j], B(a + b), pairs);
            emit(f, "sub", kSpecials[i], kSpecials[j], B(a - b), pairs);
            emit(f, "mul", kSpecials[i], kSpecials[j], B(a * b), pairs);
            emit(f, "div", kSpecials[i], kSpecials[j], B(a / b), pairs);
#endif
        }
    }

    /* ---- sqrt（只对 !(a < 0)：NaN 与 ±0 自动落进来）---- */
    for (int i = 0; i < kRandomPairs; ++i) {
        if (!(D(ra[i]) < 0.0)) {
            emit(f, "sqrt", ra[i], 0ull, B(std::sqrt(D(ra[i]))), sqrts);
        }
    }
    for (int i = 0; i < nspec; ++i) {
        if (!(D(kSpecials[i]) < 0.0)) {
            emit(f, "sqrt", kSpecials[i], 0ull, B(std::sqrt(D(kSpecials[i]))), sqrts);
        }
    }

    std::fclose(f);
    std::printf("cava_fp_probe: %s\n", path);
    std::printf("  build_id  = %s\n", cava_build_id());
    std::printf("  pairs     = %lld (x4 ops = %lld 行)\n", pairs / 4, pairs);
    std::printf("  sqrt      = %lld 行\n", sqrts);
    std::printf("  total     = %lld 行\n", pairs + sqrts);
    return 0;
}
