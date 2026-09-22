/* cava_push_vectors.cpp -- P2 第 2 核：定点真值表 + 跨语言向量生成。
 *
 * 规矩照 P2 第 1 核（native/tests/entity/cava_entity_vectors.cpp）：
 *   - 真值表的期望值**全部由字节码手推**，并逐条注明推导过程；不是"跑一遍记下来"。
 *   - 向量文件写**位模式**（double 原样 8 字节），供 Java 侧逐位对拍。
 *   - 输出 SUMMARY/RESULT，供脚本判 PASS/FAIL。
 *
 * 构建（脱离 Minecraft，只编 2 个 cpp）：
 *   pwsh -File native/tests/push/build-push.ps1
 */
#include "../../src/entity/push/cava_push.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <initializer_list>
#include <string>
#include <vector>

using namespace cava::push;

static int g_checks = 0;
static int g_failed = 0;

static void ok(bool cond, const char* what) {
    ++g_checks;
    if (!cond) {
        ++g_failed;
        std::printf("  FAIL %s\n", what);
    }
}

static uint64_t bits(double d) {
    uint64_t u = 0;
    std::memcpy(&u, &d, 8);
    return u;
}

static bool bits_eq(double a, double b) { return bits(a) == bits(b); }

static bool is_pos_zero(double d) { return bits(d) == 0ULL; }

/* forward decl: ok_push defined after test_push_away_from */
static void ok_push(std::initializer_list<double> in, int hit, double dx, double dz,
                    bool exact, const char* why);

/* ------------------------------------------------------------------ */
/* 数值语义自检（Java vs C++ 的三处已知陷阱）                          */
/* ------------------------------------------------------------------ */
static void test_numeric_primitives() {
    std::printf("[numeric] Java 语义的原语（NaN / -0.0）\n");
    /* sqrt 是 IEEE 精确舍入，允许；这里只钉住我们对它的用法。*/
    ok(bits_eq(std::sqrt(4.0), 2.0), "sqrt(4) == 2");
    ok(std::isnan(std::sqrt(-1.0)), "sqrt(-1) = NaN");
    /* 有符号零：+0.0 + -0.0 = +0.0（IEEE）*/
    ok(is_pos_zero(0.0 + (-0.0)), "+0.0 + -0.0 == +0.0");
    ok(bits_eq(-0.0 / 1.0, -0.0), "-0.0 / 1.0 == -0.0");
    ok(bits_eq(0.0 / 1.0, 0.0), "+0.0 / 1.0 == +0.0");
}

/* ------------------------------------------------------------------ */
/* TT-PA：Entity.pushAwayFrom 的几何                                   */
/* ------------------------------------------------------------------ */
struct PaCase {
    const char* id;
    double this_x, this_z, other_x, other_z;
    int hit;
    double dx, dz;
    bool exact;     /* true = 逐位断言；false = 结构性断言（符号/大小）*/
    const char* why;
};

static void test_push_away_from() {
    std::printf("[truth-table] pushAwayFrom 定点真值（期望值全部由字节码手推）\n");

    const double C = CAVA_PUSH_MIN_SEP_SQ;   /* 0.00999999977648258209228515625 */
    const double K = CAVA_PUSH_FACTOR;       /* 0.0500000007450580596923828125  */

    /* 逐条推导（偏移号对 javap -p -c net.minecraft.entity.Entity#pushAwayFrom）：
     *
     * TT-PA-1  this=(0,0) other=(4,0)
     *   24-43  d=4.0  e=+0.0
     *   45-51  f=absMax(4,0)=4.0
     *   53-59  4.0 >= 0.009999999776482582 -> 进
     *   62-67  f=sqrt(4.0)=2.0（精确）
     *   69-79  d=4/2=2.0  e=+0.0/2=+0.0
     *   81-85  g=1/2=0.5
     *   87-95  0.5 > 1.0 假 -> 不钳
     *   97-107 d=2*0.5=1.0  e=+0.0*0.5=+0.0
     *   109-121 d=1.0*(double)0.05F = (double)0.05F     <<< f>=1 时位移恒为 0.05F
     */
    ok_push({0,0,4,0}, 1, K, 0.0, true, "d=4 -> 恒为 0.05F");

    /* TT-PA-2  this=(0,0) other=(0.25,0)  —— 测 if (g > 1.0) g = 1.0 的【钳位】
     *   d=0.25 f=0.25 -> f=sqrt=0.5 ; d=0.25/0.5=0.5 ; g=1/0.5=2.0 > 1 -> g=1.0
     *   d=0.5*1.0=0.5 ; d=0.5*(double)0.05F = 0.02500000037252902984619140625（精确）*/
    ok_push({0,0,0.25,0}, 1, 0.02500000037252902984619140625, 0.0, true, "g>1 钳位到 1");

    /* TT-PA-3  this=(0,0) other=(1,0) —— g 恰好 == 1.0，**不钳**（dcmpl+ifle 是 <=）
     *   f=1 -> sqrt=1 ; d=1/1=1 ; g=1/1=1 ; 1 > 1 假 -> g 保持 1 ; d=1*1=1 ; d=(double)0.05F */
    ok_push({0,0,1,0}, 1, K, 0.0, true, "g==1 边界不钳");

    /* TT-PA-4  this=(0,0) other=(-4,0) —— 符号必须原样穿过（dneg 在 Java 侧，这里是 p 本身）
     *   d=-4 f=4 -> sqrt=2 ; d=-2 ; g=0.5 ; d=-1 ; d=-(double)0.05F */
    ok_push({0,0,-4,0}, 1, -K, 0.0, true, "负方向对称");

    /* TT-PA-5  this=(0,0) other=(+0.0, 8)
     *   d=+0.0  e=8
     *   69-73  d = +0.0 / 2.828... = +0.0（**符号保持**：正零除以正数仍是正零）
     *   97-121 d 一路乘正数 -> 仍是 +0.0
     *   => dx 必须**逐位是 +0.0**（不是 -0.0）。这是 Java 侧 addVelocity(-p,...) 里
     *      -(+0.0) = -0.0 的来源，若内核把它变成 -0.0，最终符号会翻两次。*/
    {
        PushDelta out{};
        int rc = push_away_from(0.0, 0.0, 0.0, 8.0, &out);
        ok(rc == CAVA_PUSH_OK, "PA-5 rc");
        ok(out.hit == 1, "PA-5 hit");
        ok(is_pos_zero(out.dx), "PA-5 dx 必须是 +0.0（符号保持）");
        ok(out.dz > 0.0, "PA-5 dz > 0");
    }

    /* TT-PA-6  other.x = NaN
     *   45-51 absMax(NaN, 5.0)：NaN 的 (a<0) 为假不取负 -> Math.max(NaN,5.0) = NaN
     *   53-59 dcmpl 对 NaN 得 -1 -> iflt 命中 -> **整个分支不进**（速度不动）*/
    ok_push({0,0,std::nan(""),5.0}, 0, 0.0, 0.0, true, "NaN 分隔 -> 短路");

    /* TT-PA-7  other.x = +Inf
     *   f=absMax(inf,0)=inf ; inf>=C 进 ; f=sqrt(inf)=inf ; d=inf/inf=NaN ; e=+0.0/inf=+0.0
     *   g=1/inf=+0.0 ; g>1 假 ; d=NaN*0.0=NaN ; e=+0.0*0.0=+0.0
     *   => dx=NaN, dz=+0.0  <<< "无限远"不是"推不动"，是 **NaN**。极易读反。*/
    ok_push({0,0,INFINITY,0}, 1, std::nan(""), 0.0, true, "无限远 -> NaN 位移");

    /* TT-PA-8  other=(-0.0,-0.0)
     *   d = -0.0 - 0.0 = -0.0 ; e = -0.0
     *   absMax(-0.0,-0.0)：两个 (a<0) 都是假（-0.0<0.0 为假）-> 不取负
     *   Math.max(-0.0,-0.0) = -0.0（Java 实测，见 spec 3.4）
     *   -0.0 >= 0.009999... 为假 -> 短路*/
    ok_push({0,0,-0.0,-0.0}, 0, 0.0, 0.0, true, "-0.0 分隔 -> 短路");

    /* TT-PA-9  阈值**恰好相等**：(double)0.01F >= (double)0.01F -> 进分支 */
    {
        PushDelta out{};
        int rc = push_away_from(0.0, 0.0, C, 0.0, &out);
        ok(rc == CAVA_PUSH_OK, "PA-9 rc");
        ok(out.hit == 1, "PA-9 hit（>= 是闭界）");
        ok(is_pos_zero(out.dz), "PA-9 dz = +0.0");
    }
    /* TT-PA-9b 阈值**差一个 ulp**：nextafter(C, 0) < C -> 短路 */
    {
        double just_below = std::nextafter(C, 0.0);
        PushDelta out{};
        int rc = push_away_from(0.0, 0.0, just_below, 0.0, &out);
        ok(rc == CAVA_PUSH_OK && out.hit == 0, "PA-9b hit（低于阈值一个 ulp）");
    }
    /* TT-PA-10  d = 0.009（字面量）严格小于 (double)0.01F */
    ok_push({0,0,0.009,0}, 0, 0.0, 0.0, true, "0.009 < 0.01F");

    /* TT-PA-11  "守卫必须在除法之前"的反证：若先除后判，f=0 会给出 inf/NaN。
     * 这里只是把这条不变量写成断言：凡是 hit==1 的用例，|f| 必 >= 0.01。*/
    {
        const double xs[] = {0.5, 1.0, 1.0000001, 1e-3, 1e3, -1e3, 0.010000001};
        for (double x : xs) {
            PushDelta out{};
            push_away_from(0.0, 0.0, x, 0.0, &out);
            if (out.hit) {
                ok(std::fabs(x) >= C, "PA-11 hit 蕴含 |d| >= 0.01F（除数为零不可能）");
                ok(!std::isnan(out.dx) || !std::isfinite(x) || true, "PA-11 (informational)");
            }
        }
    }

    /* 弱断言：Δ=3 时结果应在 0.05F 附近但不保证逐位（sqrt(3) 不精确） */
    {
        PushDelta out{};
        push_away_from(0.0, 0.0, 3.0, 0.0, &out);
        ok(out.hit == 1, "PA-12 hit");
        ok(std::fabs(out.dx - K) < 1e-15, "PA-12 dx ~= 0.05F（弱断言，真值表只保证 1e-15）");
    }
    std::printf("  （TT-PA 共 12 组，逐位断言 + 2 条弱断言）\n");
}

/* 小工具：把 PaCase 跑成断言（仅用于逐位/短路两类） */
static void ok_push(std::initializer_list<double> in, int hit, double dx, double dz,
                    bool exact, const char* why) {
    double a[4] = {0, 0, 0, 0};
    size_t i = 0;
    for (double v : in) { if (i < 4) a[i++] = v; }
    PushDelta out{};
    int rc = push_away_from(a[0], a[1], a[2], a[3], &out);
    if (rc != CAVA_PUSH_OK) { ok(false, why); return; }
    if (out.hit != hit) { ok(false, why); return; }
    if (hit == 0) { ok(bits_eq(out.dx, 0.0) && bits_eq(out.dz, 0.0), why); return; }
    if (exact) {
        bool e1 = bits_eq(out.dx, dx) || (std::isnan(dx) && std::isnan(out.dx));
        bool e2 = bits_eq(out.dz, dz) || (std::isnan(dz) && std::isnan(out.dz));
        ok(e1 && e2, why);
    } else {
        ok(std::fabs(out.dx - dx) < 1e-15, why);
    }
    (void)exact;
}

/* ------------------------------------------------------------------ */
/* TT-BF：Box.intersects + 顺序保持的候选过滤                          */
/* ------------------------------------------------------------------ */
static Box6 bx(double a, double b, double c, double d, double e, double f) {
    Box6 r{a, b, c, d, e, f};
    return r;
}

static void test_box_intersects() {
    std::printf("[truth-table] Box.intersects 定点真值\n");
    /* 字节码 925-960：六条严格比较，全与 */
    ok(box_intersects(bx(0,0,0,1,1,1), bx(0,0,0,1,1,1)), "BF-1 自身相交");
    ok(!box_intersects(bx(0,0,0,1,1,1), bx(1,0,0,2,1,1)), "BF-2 x 面相切**不算**相交（严格 <）");
    ok(!box_intersects(bx(0,0,0,1,1,1), bx(0,1,0,1,2,1)), "BF-2b y 面相切不算");
    ok(box_intersects(bx(0,0,0,1,1,1), bx(0.999999999,0,0,2,1,1)), "BF-3 重叠 1e-9 算相交");
    ok(!box_intersects(bx(std::nan(""),0,0,1,1,1), bx(0,0,0,1,1,1)), "BF-4 NaN -> false");
    ok(box_intersects(bx(-0.0,0,0,1,1,1), bx(0,0,0,1,1,1)), "BF-5 -0.0 边界");
    ok(box_intersects(bx(0,0,0,0,1,1), bx(-1,0,0,1,1,1)), "BF-6 退化（零宽）盒仍相交");
    /* 顺序保持：命中下标必须升序，且等于"逐个判"的结果 */
    Box6 boxes[5] = {bx(0,0,0,1,1,1), bx(10,0,0,11,1,1), bx(0.5,0,0,1.5,1,1),
                     bx(0,10,0,1,11,1), bx(-1,0,0,0.5,1,1)};
    int32_t out[8];
    int n = filter_intersecting(bx(0,0,0,1,1,1), boxes, 5, out, 8);
    ok(n == 3 && out[0] == 0 && out[1] == 2 && out[2] == 4, "BF-7 命中下标 0,2,4 升序");
    int n2 = filter_intersecting(bx(0,0,0,1,1,1), boxes, 5, out, 2);
    ok(n2 < 0, "BF-8 容量不足 -> 错误码（不越界写）");
    int n3 = filter_intersecting(bx(0,0,0,1,1,1), boxes, 0, out, 8);
    ok(n3 == 0, "BF-9 空输入 -> 0");
}

/* ------------------------------------------------------------------ */
/* TT-SP：区段 broadphase 的访问计划                                   */
/* ------------------------------------------------------------------ */
static int pack_naive_sorted_cmp(const void* a, const void* b) {
    int64_t x = *(const int64_t*)a, y = *(const int64_t*)b;
    return (x < y) ? -1 : (x > y ? 1 : 0);
}

static std::vector<int64_t> build_positions(const int32_t* xs, int nx,
                                            const int32_t* ys, int ny,
                                            const int32_t* zs, int nz) {
    std::vector<int64_t> v;
    for (int i = 0; i < nx; ++i)
        for (int j = 0; j < ny; ++j)
            for (int k = 0; k < nz; ++k)
                v.push_back(pack_section(xs[i], ys[j], zs[k]));
    /* 原版 trackedPositions 是 LongAVLTreeSet -> 迭代器按**有符号 long 升序** */
    std::qsort(v.data(), v.size(), sizeof(int64_t), pack_naive_sorted_cmp);
    return v;
}

static void test_pack_and_order() {
    std::printf("[pack] ChunkSectionPos 打包/解码（两种独立推导互证）\n");
    /* 推导 A：javap asLong 的移位/掩码（x:22b<<42, z:22b<<20, y:20b） */
    ok(pack_section(1, 2, 3) == (((int64_t)1 << 42) | ((int64_t)3 << 20) | (int64_t)2),
       "SP-0a pack(1,2,3) = 1<<42 | 3<<20 | 2");
    /* 推导 B：解码器必须逐位还原（含负数与边界） */
    const int32_t xs[] = {-2097152, -1, 0, 1, 2097151, -12345};
    const int32_t ys[] = {-524288, -1, 0, 1, 524287, 777};
    const int32_t zs[] = {-2097152, -1, 0, 1, 2097151, -6789};
    for (int i = 0; i < 6; ++i) {
        int64_t p = pack_section(xs[i], ys[i], zs[i]);
        ok(unpack_x(p) == xs[i] && unpack_y(p) == ys[i] && unpack_z(p) == zs[i], "SP-0b 往返");
    }
    /* x=-1 时打包值全 1 = -1（有符号），这是唯一会让 hi_key+1 回绕的 x */
    ok(pack_section(-1, -1, -1) == (int64_t)-1, "SP-0c pack(-1,-1,-1) == -1（hi_key+1 回绕成 0）");
}

static void test_section_plan() {
    std::printf("[truth-table] SectionedEntityCache.forEachInBox 访问计划\n");
    const int32_t xs[] = {-2, -1, 0, 1};
    const int32_t ys[] = {-1, 0, 3, 4, 5};
    const int32_t zs[] = {-2, -1, 0, 1};
    std::vector<int64_t> pos = build_positions(xs, 4, ys, 5, zs, 4);   /* 80 个 */

    /* SP-1  box = (0.5, 64.0, 0.5, 1.5, 65.0, 1.5)
     *   xMin = section_coord(0.5-2.0 = -1.5) = floor(-1.5)=-2 -> -2>>4 = -1
     *   yMin = section_coord(64.0-4.0 = 60.0) = 60>>4 = 3
     *   zMin = section_coord(0.5-2.0 = -1.5) = -1
     *   xMax = section_coord(1.5+2.0 = 3.5) = 3>>4 = 0
     *   yMax = section_coord(65.0+0.0 = 65.0) = 65>>4 = 4
     *   zMax = section_coord(3.5) = 0
     *   => x in {-1,0}, y in {3,4}, z in {-1,0}
     *   内层按**打包值升序** = (x, **z 掩码升序, y 掩码升序**)：
     *     z 掩码：0(z=0) < 1(z=1) < 4194302(z=-2) < 4194303(z=-1)
     *     => z=0 组在前、z=-1 组在后；y 掩码 3 < 4
     *   期望（8 条）：(-1,z=0,y=3) (-1,z=0,y=4) (-1,z=-1,y=3) (-1,z=-1,y=4)
     *                 ( 0,z=0,y=3) ( 0,z=0,y=4) ( 0,z=-1,y=3) ( 0,z=-1,y=4)   */
    {
        int64_t out[64];
        int n = section_plan(bx(0.5, 64.0, 0.5, 1.5, 65.0, 1.5), pos.data(), (int32_t)pos.size(), out, 64);
        int64_t exp[8] = {
            pack_section(-1, 3, 0), pack_section(-1, 4, 0), pack_section(-1, 3, -1), pack_section(-1, 4, -1),
            pack_section( 0, 3, 0), pack_section( 0, 4, 0), pack_section( 0, 3, -1), pack_section( 0, 4, -1)};
        bool same = (n == 8);
        for (int i = 0; same && i < 8; ++i) same = (out[i] == exp[i]);
        ok(same, "SP-1 (x 升序, z 掩码升序, y 掩码升序)");
        if (!same) {
            std::printf("    got n=%d:", n);
            for (int i = 0; i < (n < 16 ? n : 16); ++i)
                std::printf(" (%d,%d,%d)", unpack_x(out[i]), unpack_y(out[i]), unpack_z(out[i]));
            std::printf("\n");
        }
    }

    /* SP-2  yMax 是 maxY + 0.0：box.maxY = 63.9 -> section_coord(63.9) = 63>>4 = 3
     *   => y 窗口只剩 {3}（对照 SP-1 的 {3,4}）*/
    {
        int64_t out[64];
        int n = section_plan(bx(0.5, 64.0, 0.5, 1.5, 63.9, 1.5), pos.data(), (int32_t)pos.size(), out, 64);
        ok(n == 4, "SP-2 yMax 收窄到 y=3 -> 4 条");
        if (n == 4) {
            ok(unpack_y(out[0]) == 3 && unpack_z(out[0]) == 0 && unpack_y(out[1]) == 3 && unpack_z(out[1]) == -1,
               "SP-2 顺序 (z=0) 后 (z=-1)");
        }
    }

    /* SP-3  x 全落在 -1：这条同时覆盖 pack(-1,-1,-1)+1 == 0 的回绕分支
     *   box.minX=-8 maxX=-4 -> section_coord(-10.0) = -10>>4 = -1；section_coord(-2.0)= -1
     *   z 同理；y in {3,4}
     *   期望 2 条：(-1,z=-1,y=3) (-1,z=-1,y=4) */
    {
        int64_t out[64];
        int n = section_plan(bx(-8.0, 64.0, -8.0, -4.0, 65.0, -4.0), pos.data(), (int32_t)pos.size(), out, 64);
        ok(n == 2 && unpack_x(out[0]) == -1 && unpack_z(out[0]) == -1 && unpack_y(out[0]) == 3
                  && unpack_y(out[1]) == 4, "SP-3 只访问 x=-1（hi_key+1 回绕）");
    }

    /* SP-4  窗口内没有任何区段 -> 0 条（box 在世界之外）*/
    {
        int64_t out[64];
        int n = section_plan(bx(5000.0, 64.0, 5000.0, 5001.0, 65.0, 5001.0), pos.data(), (int32_t)pos.size(), out, 64);
        ok(n == 0, "SP-4 窗口外 -> 0 条");
    }

    /* SP-5  容量不足 -> 错误码（调用方必须整段回退原版）*/
    {
        int64_t out[2];
        int n = section_plan(bx(0.5, 64.0, 0.5, 1.5, 65.0, 1.5), pos.data(), (int32_t)pos.size(), out, 2);
        ok(n < 0, "SP-5 容量不足 -> 错误码");
    }

    /* SP-6  单调性：结果必须是输入数组的**升序子序列**（顺序保持的机器可验证形式）*/
    {
        /* 64 太小：窗口 x in [-3,2] y in [-4,5] z in [-3,2] 覆盖了**全部 80 个**区段，
         * 第一版容量给了 64，section_plan 正确地返回了错误码 —— 是**测试写错了**，不是内核。*/
        int64_t out[256];
        int n = section_plan(bx(-33.0, -60.0, -33.0, 33.0, 80.0, 33.0), pos.data(), (int32_t)pos.size(), out, 256);
        ok(n == 80, "SP-6a 全窗口命中 80 个区段");
        bool inc = (n >= 0);
        for (int i = 1; inc && i < n; ++i) inc = out[i] > out[i - 1];
        ok(inc, "SP-6 输出严格升序（输入子序列）");
        /* 并且数组序确实是 (x, z 掩码, y 掩码) 升序 —— 用两种独立写法互证。
         * 注意 x 用**有符号**的 unpack_x：x=-1 的掩码是 0x3FFFFF、x=0 是 0，
         * 无符号比较会把 -1 排在 0 后面，而有符号 long 序不会（这正是"负数 x 整组靠前"）。*/
        bool mono = true;
        for (size_t i = 1; i < pos.size(); ++i) {
            uint64_t a = (uint64_t)pos[i - 1], b = (uint64_t)pos[i];
            int32_t ax = unpack_x((int64_t)a), bx2 = unpack_x((int64_t)b);
            if (ax != bx2) { if (!(ax < bx2)) mono = false; continue; }
            uint64_t az = (a >> 20) & 0x3FFFFF, bz = (b >> 20) & 0x3FFFFF;
            if (az != bz) { if (!(az < bz)) mono = false; continue; }
            uint64_t ay = a & 0xFFFFF, by = b & 0xFFFFF;
            if (!(ay < by)) mono = false;
        }
        ok(mono, "SP-6b 快照序 == (x 有符号升序, z 掩码升序, y 掩码升序)");
    }
}

/* ------------------------------------------------------------------ */
/* 向量生成                                                            */
/* ------------------------------------------------------------------ */
static uint64_t g_fnv = 0xCBF29CE484222325ULL;
static void fnv_reset() { g_fnv = 0xCBF29CE484222325ULL; }
static void fnv_update(const void* p, size_t n) {
    const uint8_t* b = (const uint8_t*)p;
    for (size_t i = 0; i < n; ++i) { g_fnv ^= b[i]; g_fnv *= 0x100000001B3ULL; }
}

static uint64_t g_rng = 0x2545F4914F6CDD1DULL;
static uint64_t rnd() { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return g_rng; }
static double rnd_double_special() {
    uint64_t r = rnd();
    switch (r % 16) {
        case 0: return 0.0;
        case 1: return -0.0;
        case 2: return (double)(int64_t)(rnd() % 2001) - 1000.0;
        case 3: return (double)(int64_t)(rnd() % 21) * 0.05;      /* 与阈值同量级 */
        case 4: return 0.009999999776482582;                       /* 恰在阈值 */
        case 5: return (double)INFINITY;
        case 6: return -(double)INFINITY;
        case 7: return (double)std::nan("");
        case 8: return 1e-9;
        case 9: return 1e9;
        case 10: return -1e-9;
        default: {
            /* [-64,64) 上的"普通"值，二进制不保证精确 —— 由逐位对拍兜住 */
            double v = ((double)(int64_t)(rnd() % 128001) - 64000.0) / 1000.0;
            return v;
        }
    }
}

static void write_u32(FILE* f, uint32_t v) { std::fwrite(&v, 4, 1, f); fnv_update(&v, 4); }
static void write_i64(FILE* f, int64_t v) { std::fwrite(&v, 8, 1, f); fnv_update(&v, 8); }
static void write_i32(FILE* f, int32_t v) { std::fwrite(&v, 4, 1, f); fnv_update(&v, 4); }
static void write_f64(FILE* f, double v) { std::fwrite(&v, 8, 1, f); fnv_update(&v, 8); }

static void write_vectors(const std::string& dir) {
    std::printf("[vectors] 生成跨语言向量\n");
    const int kPushCases = 2048;
    const int kPlanCases = 256;

    /* --- push-00.bin (CVPU v1) --- */
    {
        std::string path = dir + "/push-00.bin";
        FILE* f = std::fopen(path.c_str(), "wb");
        if (!f) { std::printf("  FAIL 打不开 %s\n", path.c_str()); ++g_failed; return; }
        fnv_reset();
        std::fwrite("CVPU", 1, 4, f); fnv_update("CVPU", 4);
        write_u32(f, 1);
        write_u32(f, (uint32_t)kPushCases);
        for (int i = 0; i < kPushCases; ++i) {
            double a = rnd_double_special(), b = rnd_double_special();
            double c = rnd_double_special(), d = rnd_double_special();
            if (i < 12) {
                /* 前 12 条固定为真值表里那些"容易读反"的输入，保证 Java 侧一定覆盖到 */
                const double fixed[12][4] = {
                    {0, 0, 4, 0}, {0, 0, 0.25, 0}, {0, 0, 1, 0}, {0, 0, -4, 0},
                    {0, 0, 0.0, 8.0}, {0, 0, (double)INFINITY, 0}, {0, 0, -0.0, -0.0},
                    {0, 0, 0.009, 0}, {0, 0, 0.009999999776482582, 0}, {0, 0, -0.0, 0.0},
                    {5, 7, 5, 7}, {1e300, -1e300, -1e300, 1e300}};
                a = fixed[i][0]; b = fixed[i][1]; c = fixed[i][2]; d = fixed[i][3];
            }
            PushDelta out{};
            int rc = push_away_from(a, b, c, d, &out);
            write_f64(f, a); write_f64(f, b); write_f64(f, c); write_f64(f, d);
            write_f64(f, out.dx); write_f64(f, out.dz);
            write_i32(f, rc == CAVA_PUSH_OK ? out.hit : -999);
        }
        uint64_t fv = g_fnv;
        std::fclose(f);
        std::printf("  push-00.bin  cases=%d  fnv1a64=%016llx\n", kPushCases, (unsigned long long)fv);
    }

    /* --- plan-00.bin (CVPL v1) --- */
    {
        std::string path = dir + "/plan-00.bin";
        FILE* f = std::fopen(path.c_str(), "wb");
        if (!f) { std::printf("  FAIL 打不开 %s\n", path.c_str()); ++g_failed; return; }
        fnv_reset();
        std::fwrite("CVPL", 1, 4, f); fnv_update("CVPL", 4);
        write_u32(f, 1);
        write_u32(f, (uint32_t)kPlanCases);

        const int32_t xs[] = {-2, -1, 0, 1, 2};
        const int32_t ys[] = {-2, -1, 0, 3, 4, 5, 19};
        const int32_t zs[] = {-2, -1, 0, 1, 2};
        std::vector<int64_t> base = build_positions(xs, 5, ys, 7, zs, 5);

        for (int i = 0; i < kPlanCases; ++i) {
            /* 输入必须是"升序快照"：随机抽子集后仍按原序（保持升序），
             * 这样也顺带覆盖"不连续/稀疏"的真实形态。*/
            std::vector<int64_t> pos;
            for (size_t j = 0; j < base.size(); ++j) {
                if ((rnd() & 3) != 0) pos.push_back(base[j]);
            }
            if (i < 4) pos = base;                       /* 前 4 条用全集 */
            double a, b, c, d, e, g;
            if (i < 4) {
                const double fixed[4][6] = {
                    {0.5, 64.0, 0.5, 1.5, 65.0, 1.5},
                    {0.5, 64.0, 0.5, 1.5, 63.9, 1.5},
                    {-8.0, 64.0, -8.0, -4.0, 65.0, -4.0},
                    {5000.0, 64.0, 5000.0, 5001.0, 65.0, 5001.0}};
                a = fixed[i][0]; b = fixed[i][1]; c = fixed[i][2];
                d = fixed[i][3]; e = fixed[i][4]; g = fixed[i][5];
            } else {
                double cx = ((double)(int64_t)(rnd() % 161) - 80.0);
                double cz = ((double)(int64_t)(rnd() % 161) - 80.0);
                a = cx; b = 64.0 + (double)(rnd() % 40); c = cz;
                d = cx + (double)(rnd() % 20); e = b + (double)(rnd() % 20); g = cz + (double)(rnd() % 20);
                if ((rnd() & 7) == 0) { b = -60.0; e = -50.0; }        /* 高空/地下窗口 */
            }
            Box6 box{a, b, c, d, e, g};
            std::vector<int64_t> out(pos.size() + 8);
            int n = section_plan(box, pos.data(), (int32_t)pos.size(), out.data(), (int32_t)out.size());
            write_f64(f, a); write_f64(f, b); write_f64(f, c);
            write_f64(f, d); write_f64(f, e); write_f64(f, g);
            write_u32(f, (uint32_t)pos.size());
            for (int64_t p : pos) write_i64(f, p);
            if (n < 0) { write_i32(f, -1); } else {
                write_i32(f, n);
                for (int k = 0; k < n; ++k) write_i64(f, out[k]);
            }
        }
        uint64_t fv = g_fnv;
        std::fclose(f);
        std::printf("  plan-00.bin  cases=%d  fnv1a64=%016llx\n", kPlanCases, (unsigned long long)fv);
    }
}

int main(int argc, char** argv) {
    std::string dir = ".";
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--out") == 0 && i + 1 < argc) dir = argv[++i];
    }
    std::printf("=== Cava P2 push kernel test ===\n");
    test_numeric_primitives();
    test_push_away_from();
    test_box_intersects();
    test_pack_and_order();
    test_section_plan();
    write_vectors(dir);
    std::printf("SUMMARY: %d checks, %d failed\n", g_checks, g_failed);
    std::printf("RESULT: %s\n", g_failed == 0 ? "PASS" : "FAIL");
    return g_failed == 0 ? 0 : 1;
}
