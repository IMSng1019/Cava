/* cava_entity_vanilla.h -- 【仅测试用】把 VoxelShape 的构造语义照抄一份，
 * 好让 native/tests/entity 能在**没有 Minecraft** 的情况下造出与原版逐位相同的形状。
 *
 * 为什么需要它：内核的输入是 (点表 + 体素位图)，而向量必须用**原版真会产生的**那些形状。
 * 如果测试自己随便造形状，就变成了'自己给自己出题'（P1 的教训）。
 * 所以这里逐条复刻 javap 读出来的：
 *   VoxelShapes.findRequiredBitResolution(double,double)   (字节码 220-292)
 *   VoxelShapes.cuboidUnchecked(double x6)                 (字节码 57-201)
 *   VoxelShapes.cuboid(Box)                                (字节码 203-218)
 *   VoxelShapes.method_1087()  = FULL_CUBE 的构造          (字节码 1048-1066)
 *   BitSetVoxelSet.create(...) / getIndex(x,y,z)           (字节码 45-100 / 206-219)
 *   VoxelShape.offset(double,double,double)                (VoxelShape 字节码 135-168)
 *   SimpleVoxelShape.getPointPositions = FractionalDoubleList
 * 以及一份**独立于内核**的定点断言（见 cava_entity_vectors.cpp 的 [builder] 段），
 * 断言值直接从上面的字节码手算，不是从本文件的实现反推。
 *
 * 本文件不参与生产 DLL（CMake 只 GLOB native/src 下的 .cpp）。
 */
#ifndef CAVA_ENTITY_VANILLA_H
#define CAVA_ENTITY_VANILLA_H

#include <stdint.h>
#include <string.h>
#include <vector>
#include <cmath>
#include <cstdlib>

#include "cava_entity.h"

namespace cava { namespace entity { namespace vanilla {

using cava::entity::Axis;
using cava::entity::AXIS_X;
using cava::entity::AXIS_Y;
using cava::entity::AXIS_Z;

/* Math.round(double)：JDK = (long)Math.floor(v + 0.5)。NaN -> 0；越界饱和。*/
inline int64_t java_round(double v) {
    if (v != v) return 0;
    if (v >= 9.223372036854775807E18) return 9223372036854775807LL;
    if (v <= -9.223372036854775808E18) return (-9223372036854775807LL - 1);
    return (int64_t)std::floor(v + 0.5);
}

/* VoxelShapes.findRequiredBitResolution（javap 220-292） */
inline int32_t find_required_bit_resolution(double d, double e) {
    if (d < -1.0E-7 || e > 1.0000001) return -1;
    for (int32_t i = 0; i <= 3; ++i) {
        int32_t j = 1 << i;
        double f = d * (double)j;
        double g = e * (double)j;
        bool bl  = std::fabs(f - (double)java_round(f)) < 1.0E-7 * (double)j;
        bool bl2 = std::fabs(g - (double)java_round(g)) < 1.0E-7 * (double)j;
        if (bl && bl2) return i;
    }
    return -1;
}

/* 测试用的形状：直接持有 (点表 + 体素位图)。不是内核类型，只是它的输入。*/
struct TestShape {
    int32_t points_kind;
    int32_t size[3];
    std::vector<double> pts[3];      /* 长度 size[a]+1（FRACTIONAL 时留空，内核自己算 i/size）*/
    std::vector<uint64_t> bits;      /* 长度 ceil(sizeX*sizeY*sizeZ/64) */
    bool is_empty_shape;             /* 显式记录（对齐原版语义）*/

    void reset(int32_t sx, int32_t sy, int32_t sz, int32_t kind) {
        points_kind = kind;
        size[0] = sx; size[1] = sy; size[2] = sz;
        int64_t n = (int64_t)sx * (int64_t)sy * (int64_t)sz;
        bits.assign((size_t)((n + 63) / 64), 0u);
        is_empty_shape = (n <= 0);
        for (int a = 0; a < 3; ++a) pts[a].clear();
    }
    void set_bit(int32_t x, int32_t y, int32_t z) {
        int64_t idx = ((int64_t)x * (int64_t)size[1] + (int64_t)y) * (int64_t)size[2] + (int64_t)z;
        bits[(size_t)(idx >> 6)] |= (uint64_t)1 << (idx & 63);
        is_empty_shape = false;
    }
    void set_rect(int32_t x0, int32_t y0, int32_t z0, int32_t x1, int32_t y1, int32_t z1) {
        for (int32_t x = x0; x < x1; ++x)
            for (int32_t y = y0; y < y1; ++y)
                for (int32_t z = z0; z < z1; ++z) set_bit(x, y, z);
    }
    /* 供内核使用的只读视图；调用方保证 TestShape 比 ShapeView 活得久。*/
    ShapeView view(int64_t id, int32_t src, int32_t bx, int32_t by, int32_t bz) const {
        ShapeView v;
        v.points_kind = points_kind;
        v.source = src;
        v.size[0] = size[0]; v.size[1] = size[1]; v.size[2] = size[2];
        for (int a = 0; a < 3; ++a)
            v.points[a] = pts[a].empty() ? nullptr : pts[a].data();
        v.bits = bits.empty() ? nullptr : bits.data();
        v.shape_token = id;
        v.block_x = bx; v.block_y = by; v.block_z = bz;
        return v;
    }
};

inline TestShape full_cube_shape() {
    TestShape s;
    s.points_kind = cava::entity::SHAPE_POINTS_FRACTIONAL;
    s.reset(1, 1, 1, cava::entity::SHAPE_POINTS_FRACTIONAL);
    s.set_bit(0, 0, 0);
    return s;
}

/* VoxelShapes.cuboidUnchecked(x1,y1,z1,x2,y2,z2) 的逐分支复刻。*/
inline TestShape cuboid_unchecked(double x1, double y1, double z1,
                                  double x2, double y2, double z2) {
    TestShape s;
    if (x2 - x1 < 1.0E-7 || y2 - y1 < 1.0E-7 || z2 - z1 < 1.0E-7) {
        /* EMPTY = ArrayVoxelShape(BitSetVoxelSet(0,0,0), [0],[0],[0]) */
        s.points_kind = cava::entity::SHAPE_POINTS_EXPLICIT;
        s.reset(0, 0, 0, cava::entity::SHAPE_POINTS_EXPLICIT);
        for (int a = 0; a < 3; ++a) s.pts[a].assign(1, 0.0);
        s.is_empty_shape = true;
        return s;
    }
    int32_t i = find_required_bit_resolution(x1, x2);
    int32_t j = find_required_bit_resolution(y1, y2);
    int32_t k = find_required_bit_resolution(z1, z2);
    if (i < 0 || j < 0 || k < 0) {
        /* ArrayVoxelShape(FULL_CUBE.voxels, [x1,x2], [y1,y2], [z1,z2]) */
        s.points_kind = cava::entity::SHAPE_POINTS_EXPLICIT;
        s.reset(1, 1, 1, cava::entity::SHAPE_POINTS_EXPLICIT);
        s.pts[0].assign({x1, x2});
        s.pts[1].assign({y1, y2});
        s.pts[2].assign({z1, z2});
        s.set_bit(0, 0, 0);
        return s;
    }
    if (i == 0 && j == 0 && k == 0) return full_cube_shape();
    int32_t l = 1 << i, m = 1 << j, n = 1 << k;
    s.points_kind = cava::entity::SHAPE_POINTS_FRACTIONAL;
    s.reset(l, m, n, cava::entity::SHAPE_POINTS_FRACTIONAL);
    int64_t rx0 = java_round(x1 * (double)l), ry0 = java_round(y1 * (double)m), rz0 = java_round(z1 * (double)n);
    int64_t rx1 = java_round(x2 * (double)l), ry1 = java_round(y2 * (double)m), rz1 = java_round(z2 * (double)n);
    s.set_rect((int32_t)rx0, (int32_t)ry0, (int32_t)rz0, (int32_t)rx1, (int32_t)ry1, (int32_t)rz1);
    return s;
}

/* VoxelShape.offset(dx,dy,dz)：非空时返回 ArrayVoxelShape（点表整体平移，体素集共享）。
 * ⚠️ 重要：**平移后的形状一律变成 EXPLICIT 点表**（基类 getCoordIndex = 二分查找），
 * 即使原形状是 SimpleVoxelShape。getBlockCollisions 发的正是这种平移后的形状。*/
inline TestShape shape_offset(const TestShape& src, double dx, double dy, double dz) {
    if (src.is_empty_shape) {
        TestShape e;
        e.points_kind = cava::entity::SHAPE_POINTS_EXPLICIT;
        e.reset(0, 0, 0, cava::entity::SHAPE_POINTS_EXPLICIT);
        for (int a = 0; a < 3; ++a) e.pts[a].assign(1, 0.0);
        return e;
    }
    TestShape r = src;
    /* 先把 FRACTIONAL 的点表显式化（i/size），再整体平移。*/
    if (src.points_kind == cava::entity::SHAPE_POINTS_FRACTIONAL) {
        for (int a = 0; a < 3; ++a) {
            r.pts[a].resize((size_t)src.size[a] + 1);
            for (int32_t idx = 0; idx <= src.size[a]; ++idx)
                r.pts[a][(size_t)idx] = (double)idx / (double)src.size[a];
        }
    }
    const double off[3] = { dx, dy, dz };
    for (int a = 0; a < 3; ++a)
        for (size_t idx = 0; idx < r.pts[a].size(); ++idx) r.pts[a][idx] += off[a];
    r.points_kind = cava::entity::SHAPE_POINTS_EXPLICIT;
    return r;
}

/* VoxelShapes.cuboid(x1,y1,z1,x2,y2,z2)：先做 IllegalArgument 检查（原版抛异常），
 * 但测试里只用合法输入。*/
inline TestShape cuboid(double x1, double y1, double z1, double x2, double y2, double z2) {
    return cuboid_unchecked(x1, y1, z1, x2, y2, z2);
}

}}} /* namespace */
#endif /* CAVA_ENTITY_VANILLA_H */
