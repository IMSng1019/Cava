/* cava_entity_vectors.cpp -- P2 实体碰撞内核的差分/定点测试 + 跨语言向量生成器。
 *
 * 三段：
 *   [builder]     用**手算自字节码**的定点值校验 cava_entity_vanilla.h 的形状构造，
 *                 防止'测试自己给自己出题'（P1 教训 3）。
 *   [truth-table] 定点真值表：每条的真值都是**从字节码推出来的**，不是从内核反推的。
 *   [vectors]     生成可跨语言比对的 vectors 文件（含位移的**位模式**），并逐位自校验。
 *
 * 运行：cava_entity_vectors.exe [输出目录]
 * 本文件是测试程序，不参与生产 DLL。
 */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <vector>
#include <string>

#include "cava_entity.h"
#include "cava_entity_vanilla.h"

using namespace cava::entity;
using cava::entity::vanilla::TestShape;

/* ------------------------------------------------------------------ */
/* 迷你测试框架                                                          */
/* ------------------------------------------------------------------ */
static int g_checks = 0;
static int g_fails  = 0;
static void check(bool ok, const char* what) {
    ++g_checks;
    if (!ok) { ++g_fails; printf("  FAIL: %s\n", what); }
}
static uint64_t bits_of(double v) { uint64_t b; memcpy(&b, &v, 8); return b; }
static bool same_bits(double a, double b) { return bits_of(a) == bits_of(b); }

/* FNV-1a 64（契约 4.2 的统一哈希）。*/
static uint64_t fnv1a(uint64_t h, const void* p, size_t n) {
    const unsigned char* b = (const unsigned char*)p;
    for (size_t i = 0; i < n; ++i) { h ^= (uint64_t)b[i]; h *= 0x100000001B3ull; }
    return h;
}

/* ------------------------------------------------------------------ */
/* xorshift64*（与 P1 同款：自研，不用 rand()，跨语言可复现）              */
/* ------------------------------------------------------------------ */
struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) { s = seed ? seed : 0x9E3779B97F4A7C15ull; }
    uint64_t next() {
        uint64_t x = s;
        x ^= x >> 12; x ^= x << 25; x ^= x >> 27;
        s = x;
        return x * 0x2545F4914F6CDD1Dull;
    }
    uint32_t next_int(uint32_t bound) { return (uint32_t)((next() >> 1) % (uint64_t)bound); }
    double next_double() { return (double)(next() >> 11) * (1.0 / 9007199254740992.0); }
    double next_range(double lo, double hi) { return lo + (hi - lo) * next_double(); }
};
static uint64_t mix64(uint64_t a, uint64_t b) {
    uint64_t z = a ^ (b * 0x9E3779B97F4A7C15ull);
    z ^= z >> 29; z *= 0xBF58476D1CE4E5B9ull;
    z ^= z >> 32; z *= 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

/* ------------------------------------------------------------------ */
/* [builder] 用**手算自字节码**的定点值校验形状构造                       */
/* ------------------------------------------------------------------ */
static void test_builder() {
    printf("[builder] 形状构造的定点断言\n");
    /* findRequiredBitResolution：i=0 时 j=1，需要 d*1 与 e*1 都落在整数 1e-7 内。
     * (0,1)   -> i=0（两者都整） -> size = 1
     * (0.375,0.625) -> i=0: 0.375/0.625 不整; i=1(2): 0.75/1.25 不整;
     *                  i=2(4): 1.5/2.5 不整; i=3(8): 3.0/5.0 整 -> 3 -> size 8
     * (0.1,0.9) -> 4 档都不整 -> -1（走 ArrayVoxelShape 显式点表）
     * (-2,-1)  -> d < -1e-7 -> -1
     * (1,2)    -> e > 1.0000001 -> -1
     * 注：这些期望值是照 javap VoxelShapes.findRequiredBitResolution(220-292) 手算的。*/
    check(vanilla::find_required_bit_resolution(0.0, 1.0) == 0, "fbr(0,1) == 0");
    check(vanilla::find_required_bit_resolution(0.375, 0.625) == 3, "fbr(0.375,0.625) == 3");
    check(vanilla::find_required_bit_resolution(0.1, 0.9) == -1, "fbr(0.1,0.9) == -1");
    check(vanilla::find_required_bit_resolution(-2.0, -1.0) == -1, "fbr(-2,-1) == -1");
    check(vanilla::find_required_bit_resolution(1.0, 2.0) == -1, "fbr(1,2) == -1");
    /* (0.5,1.5)：e = 1.5 > 1.0000001 -> 第 8-13 字节码的早退分支直接 -1。
     * 这一条**推翻了我第一版的手算**（我漏了 e > 1.0000001 的早退），
     * 也顺带说明：Y 跨度 1.5 的栅栏/墙碰撞盒**不会**走 SimpleVoxelShape 的分母路径。*/
    check(vanilla::find_required_bit_resolution(0.5, 1.5) == -1, "fbr(0.5,1.5) == -1（e>1.0000001 早退）");
    check(vanilla::find_required_bit_resolution(0.0, 0.5) == 1, "fbr(0,0.5) == 1（size 2）");
    check(vanilla::find_required_bit_resolution(0.375, 0.625) == 3, "fbr(0.375,0.625) == 3（size 8）");

    TestShape full = vanilla::cuboid(0, 0, 0, 1, 1, 1);
    check(full.points_kind == SHAPE_POINTS_FRACTIONAL, "cuboid(0..1) -> FRACTIONAL");
    check(full.size[0] == 1 && full.size[1] == 1 && full.size[2] == 1, "cuboid(0..1) size 1x1x1");
    check(full.bits.size() == 1 && full.bits[0] == 1u, "cuboid(0..1) 只有 cell(0,0,0)");

    TestShape slab = vanilla::cuboid(0, 0, 0, 1, 0.5, 1);
    check(slab.points_kind == SHAPE_POINTS_FRACTIONAL, "cuboid(0..0.5) -> FRACTIONAL");
    check(slab.size[1] == 2, "cuboid(0..0.5) Y size == 2（fbr(0,0.5)==1）");
    check(slab.bits[0] == 1u, "cuboid(0..0.5) 只有 y 下标 0 的 cell");

    /* 真正的栅栏碰撞盒（0.375..0.625 x 0..1.5 x 0.375..0.625）：
     * Y 跨度 1.5 > 1.0000001 -> fbr(Y) = -1 -> 整个 cuboid 落到 ArrayVoxelShape（显式点表）分支。
     * **这条我第一版也猜错了**（以为它是 8x8x8 的 SimpleVoxelShape）。教训与 P1 一致：
     * 形状走哪条实现路径必须逐条回读 findRequiredBitResolution，不能凭直觉。*/
    TestShape fence = vanilla::cuboid(0.375, 0.0, 0.375, 0.625, 1.5, 0.625);
    check(fence.points_kind == SHAPE_POINTS_EXPLICIT, "真栅栏盒 -> EXPLICIT（Y 跨度 1.5 触发早退）");
    check(fence.size[0] == 1 && fence.size[1] == 1 && fence.size[2] == 1, "真栅栏盒 size 1x1x1");
    check(same_bits(fence.pts[0][0], 0.375) && same_bits(fence.pts[0][1], 0.625), "真栅栏盒 X 点表精确");
    check(same_bits(fence.pts[1][1], 1.5), "真栅栏盒 Y 点表精确到 1.5");

    /* 走 SimpleVoxelShape 分母路径的 8 分格例子：0.375..0.625 x 0..1 x 0.375..0.625。
     * X/Z -> fbr = 3（size 8），Y -> fbr = 0（size 1）。*/
    TestShape sub8 = vanilla::cuboid(0.375, 0.0, 0.375, 0.625, 1.0, 0.625);
    check(sub8.points_kind == SHAPE_POINTS_FRACTIONAL, "0.375..0.625 方柱 -> FRACTIONAL");
    check(sub8.size[0] == 8 && sub8.size[1] == 1 && sub8.size[2] == 8, "方柱 8x1x8");
    check(sub8.bits.size() == 1, "8*1*8/64 == 1 个 uint64");
    {
        bool ok = true;
        for (int32_t x = 0; x < 8; ++x) for (int32_t z = 0; z < 8; ++z) {
            int64_t idx = ((int64_t)x * 1 + 0) * 8 + z;
            bool got = (sub8.bits[(size_t)(idx >> 6)] >> (idx & 63)) & 1u;
            bool want = (x >= 3 && x < 5 && z >= 3 && z < 5);
            if (got != want) ok = false;
        }
        check(ok, "方柱体素 = X 3..5 / Z 3..5");
    }

    TestShape neg = vanilla::cuboid(-6, 0, 0, -5, 1, 1);
    check(neg.points_kind == SHAPE_POINTS_EXPLICIT, "负坐标 cuboid -> EXPLICIT");
    check(neg.pts[0].size() == 2 && neg.pts[0][0] == -6.0 && neg.pts[0][1] == -5.0, "负坐标点表精确");
    check(neg.size[0] == 1 && neg.size[1] == 1 && neg.size[2] == 1, "负坐标 cuboid size 1x1x1");

    TestShape off = vanilla::shape_offset(vanilla::cuboid(0, 0, 0, 1, 1, 1), 5.0, 64.0, -3.0);
    check(off.points_kind == SHAPE_POINTS_EXPLICIT, "offset 之后一律 EXPLICIT");
    check(same_bits(off.pts[0][0], 5.0) && same_bits(off.pts[0][1], 6.0), "offset 点表整体平移");
    check(same_bits(off.pts[1][0], 64.0) && same_bits(off.pts[1][1], 65.0), "offset Y 点表平移");
    check(same_bits(off.pts[2][0], -3.0) && same_bits(off.pts[2][1], -2.0), "offset Z 点表平移");
    check(off.bits.size() == 1 && off.bits[0] == 1u, "offset 不动体素集");

    TestShape empt = vanilla::cuboid(0, 0, 0, 0, 1, 1);
    check(empt.is_empty_shape && empt.size[0] == 0, "退化 cuboid -> 空形状");
}

/* ------------------------------------------------------------------ */
/* [frame] 轴帧表的两种独立算法必须一致（防'读反 aload_0/aload_1'）        */
/* ------------------------------------------------------------------ */
static void test_frame() {
    printf("[frame] 轴帧一致性（两种独立推导）\n");
    /* 推导 A：内核的 axis_frame() 表。
     * 推导 B：完全按 javap 的三步走（between -> opposite -> cycle）。*/
    const char* names[3] = { "X", "Y", "Z" };
    for (int a = 0; a < 3; ++a) {
        Axis axis = (Axis)a;
        AxisCycle cyc = axis_cycle_between(axis, AXIS_X);   /* index = floorMod(X - axis, 3) */
        AxisCycle opp = axis_cycle_opposite(cyc);
        Axis xa = axis_cycle_cycle(opp, AXIS_X);
        Axis ya = axis_cycle_cycle(opp, AXIS_Y);
        Axis za = axis_cycle_cycle(opp, AXIS_Z);
        const AxisFrame& f = axis_frame(axis);
        check(f.cycle == cyc,  "axis_frame.cycle 与 between() 一致");
        check(f.opposite == opp, "axis_frame.opposite 一致");
        check(f.x_axis == xa && f.y_axis == ya && f.z_axis == za, "axis_frame 三轴一致（cycle 推导）");
        /* 推导 C：完全不看 cycle，只按 AxisCycleDirection$N.choose 的实参顺序手推。
         *   NONE.choose     = Axis.choose(i1,i2,i3)   => X=i1,Y=i2,Z=i3 => xa=X,ya=Y,za=Z
         *   FORWARD.choose  = Axis.choose(i3,i1,i2)   => X=i3,Y=i1,Z=i2 => xa=Y,ya=Z,za=X
         *   BACKWARD.choose = Axis.choose(i2,i3,i1)   => X=i2,Y=i3,Z=i1 => xa=Z,ya=X,za=Y
         * 再由 choose 的结果反查 p/q/r 各自落到哪个轴。*/
        Axis c_xa = AXIS_X, c_ya = AXIS_Y, c_za = AXIS_Z;
        if (opp == CYCLE_FORWARD)  { c_xa = AXIS_Y; c_ya = AXIS_Z; c_za = AXIS_X; }
        if (opp == CYCLE_BACKWARD) { c_xa = AXIS_Z; c_ya = AXIS_X; c_za = AXIS_Y; }
        check(f.x_axis == c_xa && f.y_axis == c_ya && f.z_axis == c_za,
              "axis_frame 三轴一致（choose 实参顺序推导）");
        printf("  axis=%s cycle=%d opposite=%d xa=%s ya=%s za=%s\n",
               names[a], (int)f.cycle, (int)f.opposite,
               names[(int)f.x_axis], names[(int)f.y_axis], names[(int)f.z_axis]);
    }
    /* 顺带把三条 between() 的结果钉死（javap 手算）。*/
    check(axis_cycle_between(AXIS_X, AXIS_X) == CYCLE_NONE, "between(X,X)=NONE");
    check(axis_cycle_between(AXIS_Y, AXIS_X) == CYCLE_BACKWARD, "between(Y,X)=BACKWARD");
    check(axis_cycle_between(AXIS_Z, AXIS_X) == CYCLE_FORWARD, "between(Z,X)=FORWARD");
}

/* ------------------------------------------------------------------ */
/* 用例执行                                                            */
/* ------------------------------------------------------------------ */
struct RunOut {
    MoveResult r;
    std::vector<MoveEvent> events;
};

static void run_case(const std::vector<TestShape>& shapes,
                     const std::vector<int32_t>& srcs,
                     const std::vector<int32_t>& bxyz,
                     const Box& box, const Vec3& mv, double step_h, int32_t on_ground,
                     RunOut* out, int32_t event_cap = 256) {
    std::vector<ShapeView> views(shapes.size());
    for (size_t i = 0; i < shapes.size(); ++i)
        views[i] = shapes[i].view((int64_t)i, srcs[i], bxyz[i*3+0], bxyz[i*3+1], bxyz[i*3+2]);
    MoveRequest req;
    req.shapes.items = views.empty() ? nullptr : views.data();
    req.shapes.count = (int32_t)views.size();
    req.box = box; req.movement = mv; req.step_height = step_h; req.on_ground = on_ground;
    out->events.assign((size_t)event_cap, MoveEvent());
    int32_t rc = resolve_movement(req, out->events.data(), event_cap, &out->r);
    check(rc == CAVA_ENTITY_EVENT_OK, "resolve_movement 返回 OK");
    out->events.resize((size_t)out->r.event_count);
}

/* 只跑纯碰撞重载（不含台阶分支）。*/
static Vec3 run_core(const std::vector<TestShape>& shapes, const Box& box, const Vec3& mv,
                     std::vector<MoveEvent>* events_out = nullptr) {
    std::vector<ShapeView> views(shapes.size());
    for (size_t i = 0; i < shapes.size(); ++i)
        views[i] = shapes[i].view((int64_t)i, SHAPE_SRC_BLOCK, (int32_t)i, 0, 0);
    ShapeListView l; l.items = views.empty() ? nullptr : views.data(); l.count = (int32_t)views.size();
    EventSink sink; sink.events = nullptr; sink.cap = 0; sink.count = 0; sink.overflow = 0; sink.pass = 0;
    std::vector<MoveEvent> ev(64);
    sink.events = ev.data(); sink.cap = 64;
    Vec3 r = adjust_movement_for_collisions(mv, box, l, &sink);
    if (events_out) { ev.resize((size_t)sink.count); *events_out = ev; }
    return r;
}

static Box mk_box(double x0, double y0, double z0, double x1, double y1, double z1) {
    Box b; b.min_x = x0; b.min_y = y0; b.min_z = z0; b.max_x = x1; b.max_y = y1; b.max_z = z1; return b;
}
static Vec3 mk_v(double x, double y, double z) { Vec3 v; v.x = x; v.y = y; v.z = z; return v; }

/* ------------------------------------------------------------------ */
/* [truth-table] 定点真值表                                              */
/* ------------------------------------------------------------------ */
static void tt(const char* name, bool ok, const char* detail) {
    ++g_checks;
    if (!ok) { ++g_fails; printf("  FAIL[%s] %s\n", name, detail); }
    else printf("  ok  [%s] %s\n", name, detail);
}

static void test_truth_table() {
    printf("[truth-table] 定点真值表（真值全部由字节码手推）\n");
    std::vector<MoveEvent> ev;

    /* TT-1 空 list 短路：私有静态重载第 0-10 字节码 `if (list.isEmpty()) return movement;`
     * 返回的是**同一个 Vec3d 对象**，所以三个分量必须位模式相同。*/
    {
        Vec3 mv = mk_v(0.1234567890123456, -0.30000000000000004, -1.0e-8);
        Vec3 r = run_core({}, mk_box(0,0,0, 0.6,1.8,0.6), mv, &ev);
        tt("TT-1", same_bits(r.x,mv.x) && same_bits(r.y,mv.y) && same_bits(r.z,mv.z) && ev.empty(),
           "空形状表 -> 位移位模式原样返回、零事件");
    }
    /* TT-2 恰好相切（正 X 方向）：box.maxX == cube.minX == 0.0，move +0.5。
     * j = coordIndex(X, 0 - 1e-7) = -1 -> p=0 -> d = pointPos(0) - maxOnX = 0.0，
     * d >= -1e-7 通过 -> maxDist = min(0.5, 0.0) = 0.0。*/
    {
        Vec3 r = run_core({vanilla::cuboid(0,0,0, 1,1,1)}, mk_box(-1,0,0, 0,1,1), mk_v(0.5,0,0), &ev);
        tt("TT-2", same_bits(r.x,0.0) && same_bits(r.y,0.0) && same_bits(r.z,0.0),
           "相切面 -> 位移恰好 0.0（不是 1e-7，也不是穿透）");
        tt("TT-2e", ev.size()==1 && ev[0].accepted==1 && same_bits(ev[0].offset,0.0) && ev[0].axis==AXIS_X,
           "相切命中产生 1 条 X 轴事件、offset = 0.0");
    }
    /* TT-3 距离 1e-7：box.maxX = -1e-7，move +0.5 -> d = 0 - (-1e-7) = 1e-7 -> 位移 1e-7。*/
    {
        Vec3 r = run_core({vanilla::cuboid(0,0,0, 1,1,1)}, mk_box(-1-1.0e-7,0,0, -1.0e-7,1,1), mk_v(0.5,0,0));
        tt("TT-3", same_bits(r.x,1.0e-7), "距相切 1e-7 -> 位移恰好 1e-7");
    }
    /* TT-4 距离 2e-6 -> 位移 2e-6。*/
    {
        Vec3 r = run_core({vanilla::cuboid(0,0,0, 1,1,1)}, mk_box(-1-2.0e-6,0,0, -2.0e-6,1,1), mk_v(0.5,0,0));
        tt("TT-4", same_bits(r.x,2.0e-6), "距相切 2e-6 -> 位移恰好 2e-6");
    }
    /* TT-5 轴序：|dx| < |dz| 时 Z 先算并把 box 沿 Z 位移，X 后算且看到位移后的 box。
     * A = 世界盒 x[0,1] z[-2,-1]（挡 -Z）；B = 世界盒 x[1,2] z[0,1]（挡 +X）。
     * movement = (2,0,-3)：|2|<|-3| -> bl=true。
     *   Z 趟：A 命中，d = pointPos_Z(1) - box.minZ = -1 - 0 = -1 -> dz = -1，box 移到 z[-1,0]。
     *   X 趟（box 已在 z[-1,0]）：B 的 r 扫描窗 = [0,0) 为空 -> B 不挡 -> dx = 2。
     * 若顺序反过来（X 先），B 会挡住 -> dx = 0。这是**顺序敏感**的判定性用例。*/
    {
        std::vector<TestShape> sh = { vanilla::cuboid(0,0,-2, 1,1,-1), vanilla::cuboid(1,0,0, 2,1,1) };
        Vec3 r = run_core(sh, mk_box(0,0,0, 1,1,1), mk_v(2,0,-3));
        tt("TT-5a", same_bits(r.x,2.0) && same_bits(r.y,0.0) && same_bits(r.z,-1.0),
           "|dx|<|dz| -> 先 Z 后 X，结果是 (2,0,-1)");
        Vec3 r2 = run_core(sh, mk_box(0,0,0, 1,1,1), mk_v(3,0,-2));
        tt("TT-5b", same_bits(r2.x,0.0) && same_bits(r2.y,0.0) && same_bits(r2.z,-1.0),
           "|dx|>=|dz| -> 先 X 后 Z，结果是 (0,0,-1)");
    }
    /* TT-6 形状顺序敏感：calculateMaxOffset 的 |maxDist|<1e-7 短路在**每次迭代开头**。
     * S1 在 x = 1 + 2^-24 处挡 +X（d = 2^-24 < 1e-7），S2 在 x = 1.25 处挡 +X（d = 0.25）。
     *   [S1,S2]：算完 S1 后 maxDist = 2^-24，进 S2 迭代**开头**即返回 0.0。
     *   [S2,S1]：算完 S2 后 maxDist = 0.25，再算 S1 -> min(0.25, 2^-24) = 2^-24，循环结束（无后置检查）。
     * 用 2^-24 = 5.9604644775390625e-8 而不是 5e-8：1.0 + 2^-24 精确可表示，
     * 于是 (1.0+TINY) - 1.0 与 TINY **位级相等**，期望值可以照字面写。*/
    {
        const double TINY = 5.9604644775390625e-8;   /* 2^-24 */
        TestShape s1 = vanilla::cuboid(1.0 + TINY, 0, 0, 2.0 + TINY, 1, 1);
        TestShape s2 = vanilla::cuboid(1.25, 0, 0, 2.25, 1, 1);
        Vec3 a = run_core({s1, s2}, mk_box(0,0,0, 1,1,1), mk_v(0.5,0,0));
        Vec3 b = run_core({s2, s1}, mk_box(0,0,0, 1,1,1), mk_v(0.5,0,0));
        tt("TT-6a", same_bits(a.x, 0.0), "[S1(2^-24), S2] -> 0.0（第二个形状被 1e-7 短路）");
        tt("TT-6b", same_bits(b.x, TINY), "[S2, S1] -> 2^-24（最后一个形状没有后置短路）");
    }
    /* TT-7 负坐标：墙在世界盒 x[-5,-4]，box x[-3.5,-2.5]，move -1.0。
     * 墙落 EXPLICIT（fbr(-5,-4) 因 d<-1e-7 早退），点表 = {-5,-4}。
     * i = coordIndex(X, -3.5 + 1e-7) = 1 -> p = 0 -> d = pointPos(1) - minOnX = -4 - (-3.5) = -0.5。
     * d <= 1e-7 -> maxDist = max(-1.0, -0.5) = -0.5。
     * 【第一版用了 -4.6，结果 d = -5 - (-4.6) 不是精确的 -0.4（二进制无法表示 4.6）——
     *   定点真值表必须挑**二进制精确**的数，否则测的是浮点舍入而不是语义。】*/
    {
        Vec3 r = run_core({vanilla::cuboid(-5,0,0, -4,1,1)}, mk_box(-3.5,0,0, -2.5,1,1), mk_v(-1.0,0,0));
        tt("TT-7", same_bits(r.x,-0.5) && same_bits(r.y,0.0) && same_bits(r.z,0.0),
           "负坐标：贴到墙面，位移 -0.5");
    }
    /* TT-8 大坐标（世界边界量级 3e7）：墙 x[29999999,30000000]，box maxX = 29999998.5。
     * j = coordIndex(X, 29999998.5 - 1e-7) = -1 -> p = 0 -> d = 29999999 - 29999998.5 = 0.5。*/
    {
        Vec3 r = run_core({vanilla::cuboid(29999999,0,0, 30000000,1,1)},
                          mk_box(29999997.5,0,0, 29999998.5,1,1), mk_v(2.0,0,0));
        tt("TT-8", same_bits(r.x,0.5) && same_bits(r.y,0.0) && same_bits(r.z,0.0),
           "3e7 量级坐标：位移恰好 0.5");
    }
    /* TT-9 台阶分支。逐步手推见 docs/CAVA-entity-oracle-spec.md 第 6 节。
     * box(0,0,0 .. 0.6,1.8,0.6)、movement(1,-0.1,0)、stepHeight 0.6、onGround true、
     * 形状 = 台阶 x[1,2] y[0,0.6] z[-1,2]。期望 base=(0.4,-0.1,0)、最终 =(1,0.6,0)、step_used=1。*/
    {
        std::vector<TestShape> sh = { vanilla::cuboid(1,0,-1, 2,0.6,2) };
        std::vector<int32_t> srcs = { SHAPE_SRC_BLOCK };
        std::vector<int32_t> bxyz = { 1,0,-1 };
        RunOut o;
        run_case(sh, srcs, bxyz, mk_box(0,0,0, 0.6,1.8,0.6), mk_v(1.0,-0.1,0.0), 0.6, 1, &o);
        tt("TT-9a", same_bits(o.r.base_delta.x,0.4) && same_bits(o.r.base_delta.y,-0.1) && same_bits(o.r.base_delta.z,0.0),
           "台阶用例的第 0 趟 = (0.4,-0.1,0)");
        tt("TT-9b", same_bits(o.r.delta.x,1.0) && same_bits(o.r.delta.y,0.6) && same_bits(o.r.delta.z,0.0),
           "台阶用例的最终位移 = (1,0.6,0)");
        tt("TT-9c", o.r.step_used == 1, "step_used == 1");
        tt("TT-9d", same_bits(o.r.step_candidate.y, 0.6), "step_candidate.y == stepHeight");
        /* 事件必须覆盖 0..4 里的若干趟，且**顺序**是 0 -> 1 -> 4。*/
        {
            bool order_ok = true; int32_t last = -1;
            for (size_t i = 0; i < o.events.size(); ++i) {
                if (o.events[i].pass < last) order_ok = false;
                last = o.events[i].pass;
            }
            tt("TT-9e", order_ok && !o.events.empty(), "事件的 pass 单调不减（原版调用顺序）");
        }
    }
    /* TT-10 stepHeight == 0 -> 完全不进台阶分支。*/
    {
        std::vector<TestShape> sh = { vanilla::cuboid(1,0,-1, 2,0.6,2) };
        std::vector<int32_t> srcs = { SHAPE_SRC_BLOCK };
        std::vector<int32_t> bxyz = { 1,0,-1 };
        RunOut o;
        run_case(sh, srcs, bxyz, mk_box(0,0,0, 0.6,1.8,0.6), mk_v(1.0,-0.1,0.0), 0.0, 1, &o);
        tt("TT-10", o.r.step_used == 0 && same_bits(o.r.delta.x,0.4) && same_bits(o.r.delta.y,-0.1),
           "stepHeight=0 -> 位移 == base、step_used=0");
    }
    /* TT-11 事件数组不足 -> overflow 置位、不越界、位移仍正确。*/
    {
        /* 需要 **两趟各命中一次** 才能让 cap=1 溢出：
         *   S0 世界盒 x[2.2,3.2] y[0,2] z[0,1] -> 第 0 趟的 X 分支命中（X 位移前 box 已在 y[1,2]，
         *      与 S0 的 Y 范围仍有交叠，所以 q 扫描窗非空）；
         *   S1 世界盒 y[3,4] -> 第 0 趟的 Y 分支命中（Y 永远最先算）。
         * 实测：两个形状各自只在自己的那一趟命中一次 => 共 2 条事件。
         * （第一版用 S0=x[2,3] y[0,1]：Y 位移后 box 抬到 y[1,2]，与 S0 的 Y 窗变成空集，
         *   X 趟不再命中，于是只有 1 条事件、cap=1 不溢出。）*/
        std::vector<TestShape> sh = { vanilla::cuboid(2.2,0,0, 3.2,2,1), vanilla::cuboid(0,3,0, 1,4,1) };
        std::vector<int32_t> srcs = { SHAPE_SRC_BLOCK, SHAPE_SRC_BLOCK };
        std::vector<int32_t> bxyz = { 2,0,0, 0,3,0 };
        /* 未使用但保留可读性：S0 的世界原点 */
        RunOut o;
        run_case(sh, srcs, bxyz, mk_box(0,0,0, 1,1,1), mk_v(1.0,1.0,0.0), 0.0, 1, &o, 1);
        MoveResult full;
        { RunOut o2; run_case(sh, srcs, bxyz, mk_box(0,0,0, 1,1,1), mk_v(1.0,1.0,0.0), 0.0, 1, &o2, 64); full = o2.r; }
        tt("TT-11", o.r.event_overflow == 1 && same_bits(o.r.delta.x, full.delta.x)
                  && same_bits(o.r.delta.y, full.delta.y) && same_bits(o.r.delta.z, full.delta.z),
           "cap 不足 -> overflow=1 且位移不受影响");
    }
    /* TT-12 movement.lengthSquared()==0 -> 直接返回 movement（不做求解、零事件）。*/
    {
        std::vector<TestShape> sh = { vanilla::cuboid(0,0,0, 1,1,1) };
        std::vector<int32_t> srcs = { SHAPE_SRC_BLOCK };
        std::vector<int32_t> bxyz = { 0,0,0 };
        RunOut o;
        run_case(sh, srcs, bxyz, mk_box(0.2,0.2,0.2, 0.8,1.2,0.8), mk_v(0.0,0.0,0.0), 1.0, 1, &o);
        tt("TT-12", same_bits(o.r.delta.x,0.0) && same_bits(o.r.delta.y,0.0) && same_bits(o.r.delta.z,0.0)
                   && o.r.event_count == 0 && o.r.step_used == 0,
           "零位移 -> 直接返回、零事件、不进台阶分支");
    }
    /* TT-13 排序语义：maxDist 取的是 min（正方向）/ max（负方向），不是'第一个命中'。*/
    {
        /* 用二进制精确的 1.25 / 1.5，d 分别是 0.25 / 0.5（1.2 那种值会引入舍入噪声）。*/
        TestShape near1 = vanilla::cuboid(1.25, 0, 0, 2.25, 1, 1);
        TestShape far1  = vanilla::cuboid(1.5, 0, 0, 2.5, 1, 1);
        Vec3 a = run_core({near1, far1}, mk_box(0,0,0, 1,1,1), mk_v(0.9,0,0));
        Vec3 b = run_core({far1, near1}, mk_box(0,0,0, 1,1,1), mk_v(0.9,0,0));
        tt("TT-13", same_bits(a.x,0.25) && same_bits(b.x,0.25),
           "两个都挡时取 min(d)（0.25），与列表顺序无关（都不触发 1e-7 短路）");
    }
}

/* ------------------------------------------------------------------ */
/* 随机用例 + 向量文件                                                   */
/* ------------------------------------------------------------------ */
struct CaseData {
    uint32_t id;
    Box box;
    Vec3 mv;
    double step_h;
    uint32_t on_ground;
    std::vector<TestShape> shapes;
    std::vector<int32_t> srcs;
    std::vector<int32_t> bxyz;
    MoveResult out;
    std::vector<MoveEvent> events;
};

static const double DYADIC[8] = { 0.0625, 0.125, 0.25, 0.375, 0.5, 0.625, 0.8125, 1.5 };

static const double MOV[] = { 0.0, 1.0e-8, 5.9604644775390625e-8, 1.0e-7, 2.1e-7, 0.1, 0.25, 0.5, 1.0, -0.5, -1.0, 2.0, -2.0, 0.30000000000000004 };
static const double GAPS[] = { 0.0, 1.0e-7, 5.9604644775390625e-8, 2.0e-6, 0.5, 1.25, 0.25 };
static const double APPROACH[] = { 0.5, 1.0, 2.0, 0.25 };

static void gen_case(uint32_t id, uint64_t seed, CaseData* c) {
    Rng r(seed);
    c->id = id;
    int32_t scenario = (int32_t)r.next_int(6);
    double base = 0.0;
    if (scenario == 4)      base = r.next_range(-30.0, 30.0);
    else if (scenario == 5) base = r.next_range(2.9999e7, 3.0e7);
    double w = 0.6, h = 1.8;
    if (r.next_int(2) == 0) w = 0.5;
    if (r.next_int(3) == 0) h = 1.0;
    c->step_h = (r.next_int(2) == 0) ? 0.0 : DYADIC[4];
    c->on_ground = (uint32_t)r.next_int(2);

    double origin[8][3];
    int32_t n = 1 + (int32_t)r.next_int(4);
    if (n > 8) n = 8;
    for (int32_t i = 0; i < n; ++i) {
        int32_t cell_x = (int32_t)r.next_int(9) - 2;
        int32_t cell_y = (int32_t)r.next_int(3) - 1;
        int32_t cell_z = (int32_t)r.next_int(9) - 2;
        double ox = base + (double)cell_x, oy = (double)cell_y, oz = base + (double)cell_z;
        TestShape s;
        int32_t kind = (int32_t)r.next_int(4);
        if (kind == 0) {
            s = vanilla::cuboid(ox, oy, oz, ox + 1.0, oy + 1.0, oz + 1.0);
        } else if (kind == 1) {
            double top = DYADIC[r.next_int(8)];
            s = vanilla::cuboid(ox, oy, oz, ox + 1.0, oy + top, oz + 1.0);
        } else if (kind == 2) {
            double a = DYADIC[r.next_int(4)];
            double b = DYADIC[5 + (int32_t)r.next_int(3)];
            s = vanilla::cuboid(ox + a, oy, oz + a, ox + b, oy + 1.5, oz + b);
        } else {
            /* 人造的'世界边界样'形状：3x1x3 网格、只填四条边（非凸、非单盒）。*/
            s.reset(3, 1, 3, SHAPE_POINTS_EXPLICIT);
            s.pts[0].assign({ ox, ox + 1.0, ox + 2.0 });
            s.pts[1].assign({ oy, oy + 1.0 });
            s.pts[2].assign({ oz, oz + 1.0, oz + 2.0 });
            for (int32_t x = 0; x < 3; ++x) for (int32_t z = 0; z < 3; ++z) {
                if (x == 1 && z == 1) continue;
                s.set_bit(x, 0, z);
            }
        }
        c->shapes.push_back(s);
        c->srcs.push_back(i == 0 ? SHAPE_SRC_ENTITY : (i == 1 ? SHAPE_SRC_WORLD_BORDER : SHAPE_SRC_BLOCK));
        c->bxyz.push_back(cell_x); c->bxyz.push_back(cell_y); c->bxyz.push_back(cell_z);
        origin[i][0] = ox; origin[i][1] = oy; origin[i][2] = oz;
    }

    /* 一半用例把实体**锚定到第一个形状上**，否则随机撒点几乎永远不碰撞
     * （第一版实测：400 组里只有 34 组产生了事件 —— 覆盖率根本不够）。*/
    if (r.next_int(2) == 0) {
        int32_t ax = (int32_t)r.next_int(3);
        double gap = GAPS[r.next_int(7)];
        double mv = APPROACH[r.next_int(4)];
        double ox = origin[0][0], oy = origin[0][1], oz = origin[0][2];
        if (ax == 0) {
            c->box = mk_box(ox - gap - w, oy, oz, ox - gap, oy + h, oz + w);
            c->mv = mk_v(mv, 0.0, 0.0);
        } else if (ax == 1) {
            c->box = mk_box(ox, oy - gap - h, oz, ox + w, oy - gap, oz + w);
            c->mv = mk_v(0.0, mv, 0.0);
        } else {
            c->box = mk_box(ox, oy, oz - gap - w, ox + w, oy + h, oz - gap);
            c->mv = mk_v(0.0, 0.0, mv);
        }
        /* 偶尔再加一个轴，让多轴扫描窗也参与。*/
        if (r.next_int(3) == 0) {
            c->mv.x = MOV[r.next_int(14)];
            c->mv.z = MOV[r.next_int(14)];
        }
    } else {
        /* 随机撒点（部分贴整数格，命中相切/1e-7 边界）。*/
        bool snap = (r.next_int(2) == 0);
        double bx = base + (snap ? (double)(int32_t)r.next_int(9) : r.next_range(0.0, 8.0));
        double by = snap ? (double)(int32_t)r.next_int(3) : r.next_range(0.0, 3.0);
        double bz = base + (snap ? (double)(int32_t)r.next_int(9) : r.next_range(0.0, 8.0));
        c->box = mk_box(bx, by, bz, bx + w, by + h, bz + w);
        c->mv = mk_v(MOV[r.next_int(14)], MOV[r.next_int(14)], MOV[r.next_int(14)]);
        if (scenario == 3) { c->mv.y = 0.0; }
    }
    RunOut o;
    run_case(c->shapes, c->srcs, c->bxyz, c->box, c->mv, c->step_h, (int32_t)c->on_ground, &o);
    c->out = o.r;
    c->events = o.events;
}

/* ---- 小端写盘（x86-64 实测；跨大端平台需显式转换——未验证） ---- */
static void put(void* dst, size_t* off, const void* src, size_t n) {
    memcpy((unsigned char*)dst + *off, src, n); *off += n;
}
static std::vector<unsigned char> serialize(const std::vector<CaseData>& cases, uint64_t master_seed) {
    size_t cap = 64;
    for (size_t i = 0; i < cases.size(); ++i) {
        const CaseData& c = cases[i];
        cap += 8 + 6*8 + 3*8 + 8 + 4 + 4;
        for (size_t s = 0; s < c.shapes.size(); ++s) {
            const TestShape& t = c.shapes[s];
            cap += 4*4 + 3*4 + 4 + (t.pts[0].size()+t.pts[1].size()+t.pts[2].size())*8
                 + 4 + t.bits.size()*8 + 3*4 + 8;
        }
        cap += 3*8 + 3*8 + 3*8 + 4 + 4 + 4;
        cap += c.events.size() * (8 + 4*12 + 3*8);
        cap += 64;
    }
    std::vector<unsigned char> buf(cap);
    size_t off = 0;
    char magic[4] = { 'C','V','E','M' };
    uint16_t ver = 1, rsv = 0; uint32_t cnt = (uint32_t)cases.size(); uint32_t flags = 0;
    put(buf.data(), &off, magic, 4);
    put(buf.data(), &off, &ver, 2);
    put(buf.data(), &off, &rsv, 2);
    put(buf.data(), &off, &cnt, 4);
    put(buf.data(), &off, &master_seed, 8);
    put(buf.data(), &off, &flags, 4);
    put(buf.data(), &off, &rsv, 2);
    put(buf.data(), &off, &rsv, 2);
    for (size_t i = 0; i < cases.size(); ++i) {
        const CaseData& c = cases[i];
        put(buf.data(), &off, &c.id, 4);
        uint32_t nsh = (uint32_t)c.shapes.size();
        put(buf.data(), &off, &nsh, 4);
        put(buf.data(), &off, &c.box, 6*8);
        put(buf.data(), &off, &c.mv, 3*8);
        put(buf.data(), &off, &c.step_h, 8);
        put(buf.data(), &off, &c.on_ground, 4);
        uint32_t pad = 0; put(buf.data(), &off, &pad, 4);
        for (size_t s = 0; s < c.shapes.size(); ++s) {
            const TestShape& t = c.shapes[s];
            put(buf.data(), &off, &t.points_kind, 4);
            uint32_t src = (uint32_t)c.srcs[s]; put(buf.data(), &off, &src, 4);
            put(buf.data(), &off, &t.size, 3*4);
            int32_t bx = c.bxyz[s*3+0], by = c.bxyz[s*3+1], bz = c.bxyz[s*3+2];
            put(buf.data(), &off, &bx, 4); put(buf.data(), &off, &by, 4); put(buf.data(), &off, &bz, 4);
            int64_t sid = (int64_t)s; put(buf.data(), &off, &sid, 8);
            uint32_t npc = (uint32_t)(t.pts[0].size() + t.pts[1].size() + t.pts[2].size());
            put(buf.data(), &off, &npc, 4);
            for (int a = 0; a < 3; ++a)
                if (!t.pts[a].empty()) put(buf.data(), &off, t.pts[a].data(), t.pts[a].size()*8);
            uint32_t nw = (uint32_t)t.bits.size();
            put(buf.data(), &off, &nw, 4);
            if (nw) put(buf.data(), &off, t.bits.data(), (size_t)nw*8);
        }
        uint64_t db[3] = { bits_of(c.out.delta.x), bits_of(c.out.delta.y), bits_of(c.out.delta.z) };
        uint64_t bb[3] = { bits_of(c.out.base_delta.x), bits_of(c.out.base_delta.y), bits_of(c.out.base_delta.z) };
        uint64_t sb[3] = { bits_of(c.out.step_candidate.x), bits_of(c.out.step_candidate.y), bits_of(c.out.step_candidate.z) };
        put(buf.data(), &off, db, 24);
        put(buf.data(), &off, bb, 24);
        put(buf.data(), &off, sb, 24);
        uint32_t ne = (uint32_t)c.events.size();
        put(buf.data(), &off, &ne, 4);
        uint32_t su = (uint32_t)c.out.step_used; put(buf.data(), &off, &su, 4);
        uint32_t ov = (uint32_t)c.out.event_overflow; put(buf.data(), &off, &ov, 4);
        for (size_t e = 0; e < c.events.size(); ++e) {
            const MoveEvent& ev = c.events[e];
            put(buf.data(), &off, &ev.shape_token, 8);
            put(buf.data(), &off, &ev.source, 4);
            put(buf.data(), &off, &ev.axis, 4);
            put(buf.data(), &off, &ev.block_x, 4);
            put(buf.data(), &off, &ev.block_y, 4);
            put(buf.data(), &off, &ev.block_z, 4);
            put(buf.data(), &off, &ev.pass, 4);
            put(buf.data(), &off, &ev.accepted, 4);
            put(buf.data(), &off, &ev.cell_x, 4);
            put(buf.data(), &off, &ev.cell_y, 4);
            put(buf.data(), &off, &ev.cell_z, 4);
            uint32_t r0 = 0, r1 = 0;
            put(buf.data(), &off, &r0, 4); put(buf.data(), &off, &r1, 4);
            uint64_t ob = bits_of(ev.offset), mb = bits_of(ev.max_dist_before), ma = bits_of(ev.max_dist_after);
            put(buf.data(), &off, &ob, 8); put(buf.data(), &off, &mb, 8); put(buf.data(), &off, &ma, 8);
        }
    }
    buf.resize(off);
    return buf;
}

static bool write_file(const std::string& path, const std::vector<unsigned char>& b) {
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return false;
    size_t w = fwrite(b.data(), 1, b.size(), f);
    fclose(f);
    return w == b.size();
}

static std::string find_out_dir(int argc, char** argv) {
    if (argc > 1) return std::string(argv[1]);
    const char* cand[] = { "native/tests/entity/vectors", "../native/tests/entity/vectors",
                           "../../native/tests/entity/vectors", "../../../native/tests/entity/vectors" };
    for (int i = 0; i < 4; ++i) {
        FILE* f = fopen((std::string(cand[i]) + "/.keep").c_str(), "rb");
        if (f) { fclose(f); return cand[i]; }
        /* 目录存在但 .keep 不存在也接受：直接试写。*/
    }
    return cand[0];
}

static void test_vectors() {
    printf("[vectors] 生成跨语言向量\n");
    const uint64_t MASTER_SEED = 0x1F2E3D4C5B6A7988ull;
    const uint32_t N = 400;
    std::vector<CaseData> cases(N);
    for (uint32_t i = 0; i < N; ++i) gen_case(i, mix64(MASTER_SEED, i), &cases[i]);

    /* 统计：确保随机用例真的覆盖到了有分支的分支。*/
    uint32_t nonempty_events = 0, step_used = 0, moved = 0, zero_delta = 0;
    for (uint32_t i = 0; i < N; ++i) {
        if (!cases[i].events.empty()) ++nonempty_events;
        if (cases[i].out.step_used) ++step_used;
        if (!(same_bits(cases[i].out.delta.x, cases[i].mv.x) &&
              same_bits(cases[i].out.delta.y, cases[i].mv.y) &&
              same_bits(cases[i].out.delta.z, cases[i].mv.z))) ++moved;
        if (cases[i].out.delta.x == 0.0 && cases[i].out.delta.y == 0.0 && cases[i].out.delta.z == 0.0) ++zero_delta;
    }
    printf("  cases=%u  有事件=%u  改了位移=%u  台阶命中=%u  全零位移=%u\n",
           N, nonempty_events, moved, step_used, zero_delta);
    check(nonempty_events * 5 > N * 2, "至少 40% 的用例产生了碰撞事件");
    check(moved * 4 > N, "至少 25% 的用例位移被改变");
    check(step_used > 0, "随机集合覆盖到台阶分支");

    /* 幂等：同一个 case 跑两次结果必须逐位相同。*/
    {
        bool idem = true;
        for (uint32_t i = 0; i < N; i += 37) {
            CaseData again;
            gen_case(i, mix64(MASTER_SEED, i), &again);
            if (!same_bits(again.out.delta.x, cases[i].out.delta.x) ||
                !same_bits(again.out.delta.y, cases[i].out.delta.y) ||
                !same_bits(again.out.delta.z, cases[i].out.delta.z)) idem = false;
            if (again.events.size() != cases[i].events.size()) idem = false;
        }
        check(idem, "生成器幂等：同 seed 两次结果逐位相同");
    }

    std::vector<unsigned char> bin = serialize(cases, MASTER_SEED);
    uint64_t h = fnv1a(0xCBF29CE484222325ull, bin.data(), bin.size());
    printf("  bytes=%zu  fnv1a64=%016llx\n", bin.size(), (unsigned long long)h);

    std::string dir = find_out_dir(0, nullptr);
    std::string path = dir + "/entity-00.bin";
    bool wrote = write_file(path, bin);
    if (!wrote) {
        /* 目录可能还不存在（首次运行）：尝试建目录。*/
        std::string cmd = "mkdir \"" + dir + "\"";
        int rc = system(cmd.c_str()); (void)rc;
        wrote = write_file(path, bin);
    }
    check(wrote, "向量文件写盘成功");
    if (wrote) printf("  写出 %s\n", path.c_str());

    /* manifest：用文本记录哈希与规模，便于跨语言核对。*/
    {
        std::string mp = dir + "/manifest.txt";
        FILE* f = fopen(mp.c_str(), "wb");
        if (f) {
            fprintf(f, "format=CVEM\nversion=1\ncases=%u\nmasterSeed=%016llx\n",
                    N, (unsigned long long)MASTER_SEED);
            fprintf(f, "bytes=%zu\nfnv1a64=%016llx\n", bin.size(), (unsigned long long)h);
            fprintf(f, "kernelAbi=%d\n", CAVA_ENTITY_KERNEL_ABI);
            fclose(f);
            printf("  写出 %s\n", mp.c_str());
        }
    }
}

int main(int argc, char** argv) {
    (void)argc; (void)argv;
    printf("=== Cava P2 entity collision kernel test ===\n");
    test_builder();
    test_frame();
    test_truth_table();
    test_vectors();
    printf("SUMMARY: %d checks, %d failed\n", g_checks, g_fails);
    printf("RESULT: %s\n", g_fails == 0 ? "PASS" : "FAIL");
    return g_fails == 0 ? 0 : 1;
}
