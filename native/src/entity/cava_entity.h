/* cava_entity.h -- P2 实体碰撞【纯几何内核】的接口。
 *
 * 设计纪律（与 docs/CAVA-工程接口契约.md 一致，P1 的四条教训当硬约束）：
 *   1. 【同一份常量/位布局只允许定义一处】。本文件里所有'从字节码抄下来的表'
 *      （轴帧表 AXIS_FRAME、坐标映射）都只有这一份定义，内核只许引用。
 *   2. 内核【不读世界、不做方块查询、不碰 Minecraft 类型】：形状由调用方以只读视图传入。
 *      这样它能脱离 MC 编译与单测（见 native/tests/entity/）。
 *   3. 数值一律 double 语义；只允许 + - * / 与 fabs；禁止任何超越函数
 *      （契约 2.1 第 4 条：MSVC/GCC 之间超越函数不保证逐位一致）。
 *   4. 内核【不产生副作用】：它把'碰到的每个形状'按【原版顺序】写进输出事件数组，
 *      Java 侧拿 shape_token 回到自己的 VoxelShape[] 上执行虚方法。
 *
 * 语义权威：docs/CAVA-entity-oracle-spec.md（javap 逐条转写）。
 * 作者：P2-K 子代理。
 */
#ifndef CAVA_ENTITY_H
#define CAVA_ENTITY_H

#include <stdint.h>

namespace cava {
namespace entity {

/* 原版字面量 1.0E-7（javap 里以 ldc2_w  // double 1.0E-7d 出现）。
 * 全内核只此一处定义。*/
constexpr double CAVA_EPS = 1.0E-7;

/* Java 的 (double)->int 是【饱和】转换（NaN->0），C++ 直接转是 UB。
 * ABI 侧已有 cava_d2i_sat；本内核为了能脱离 ABI 单测，在这里给出【唯一实现】，
 * 接线时 ABI 的 cava_d2i_sat 必须改为转调本函数（不得出现两份独立实现）。*/
inline int32_t java_d2i_sat(double v) {
    if (v != v) return 0;                       /* NaN -> 0，与 (int)Double.NaN 一致 */
    if (v >= 2147483647.0) return 2147483647;
    if (v <= -2147483648.0) return (-2147483647 - 1);
    return (int32_t)v;                          /* 截断（向 0），JVM d2i 语义 */
}

/* Math.abs(double)：只清符号位（fabs 不是超越函数，允许）。
 * java_abs_exact 额外把 -0.0 归成 +0.0（Math.abs(-0.0) == 0.0，本机实测）。*/
inline double java_abs(double v) { return v < 0.0 ? -v : v; }
inline double java_abs_exact(double v) { return v == 0.0 ? 0.0 : (v < 0.0 ? -v : v); }

/* Math.min / Math.max：【任一为 NaN 结果为 NaN】（本机实测，见 spec 第 3.4 节）。
 * C++ 的 std::min/std::max 与 fmin/fmax 语义都不同，必须自备。*/
inline double java_min(double a, double b) {
    if (a != a) return a;
    if (b != b) return b;
    if (a == 0.0 && b == 0.0) return (1.0 / a < 0.0) ? a : b;   /* min(0.0,-0.0) == -0.0 */
    return a <= b ? a : b;
}
inline double java_max(double a, double b) {
    if (a != a) return a;
    if (b != b) return b;
    if (a == 0.0 && b == 0.0) return (1.0 / a > 0.0) ? a : b;   /* max(0.0,-0.0) == 0.0 */
    return a >= b ? a : b;
}

/* ---------------------------------------------------------------- */
/* 轴与轴循环（AxisCycleDirection）                                  */
/* ---------------------------------------------------------------- */

enum Axis { AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 2 };
enum AxisCycle { CYCLE_NONE = 0, CYCLE_FORWARD = 1, CYCLE_BACKWARD = 2 };

/* AxisCycleDirection 的枚举序（javap AxisCycleDirection.<clinit>：NONE=0, FORWARD=1, BACKWARD=2），
 * 以及 values() 的顺序 [NONE, FORWARD, BACKWARD]（method_36930）。
 *
 * 陷阱（我已踩过一次，不要重踩）：between 是 static 方法，没有 this，
 * 所以 javap 里的 aload_0 = 第一个形参、aload_1 = 第二个形参。
 * 字节码 VALUES[aload_1.ordinal - aload_0.ordinal mod 3]
 *   => index = floorMod(second.ordinal - first.ordinal, 3)。
 * 若误当成实例方法（以为 aload_0 是 this），整张轴帧表会整体错位，
 * 而'位移照样算得出来、只是与原版不一致'——正是最难发现的那类 parity bug。*/
inline AxisCycle axis_cycle_between(Axis first, Axis second) {
    int d = ((int)second - (int)first) % 3;
    if (d < 0) d += 3;
    return (AxisCycle)d;                       /* 0=NONE, 1=FORWARD, 2=BACKWARD，与 VALUES 同序 */
}

/* AxisCycleDirection$1/$2/$3 的 cycle()：
 *   NONE.cycle(a)     = AXES[a]
 *   FORWARD.cycle(a)  = AXES[floorMod(a+1,3)]
 *   BACKWARD.cycle(a) = AXES[floorMod(a-1,3)] */
inline Axis axis_cycle_cycle(AxisCycle c, Axis a) {
    if (c == CYCLE_NONE) return a;
    int d = ((int)a + (c == CYCLE_FORWARD ? 1 : -1)) % 3;
    if (d < 0) d += 3;
    return (Axis)d;
}
inline AxisCycle axis_cycle_opposite(AxisCycle c) {
    return c == CYCLE_NONE ? CYCLE_NONE : (c == CYCLE_FORWARD ? CYCLE_BACKWARD : CYCLE_FORWARD);
}

/* 沿 axis 求解时，VoxelShape.calculateMaxDistance(Direction.Axis, Box, double) 内部构造的
 * '循环坐标系'：x_axis/y_axis/z_axis 是 p/q/r 三个下标各自对应的【真实轴】。
 *
 * 推导（唯一事实来源，测试有断言，见 truth-table TT-7）：
 *   axisCycle = between(axis, X) = VALUES[floorMod(X.ord - axis.ord, 3)]
 *   opposite  = axisCycle.opposite()
 *   xAxis = opposite.cycle(X), yAxis = opposite.cycle(Y), zAxis = opposite.cycle(Z)
 * 于是 VoxelSet.inBoundsAndContains(opposite, p, q, r) 里 choose(...) 的结果恰为
 *   coord[x_axis]=p, coord[y_axis]=q, coord[z_axis]=r。三种情况逐一验证：
 *     opposite=NONE     : NONE.choose     = Axis.choose(i1,i2,i3) => X=p,Y=q,Z=r => X,Y,Z
 *     opposite=FORWARD  : FORWARD.choose  = Axis.choose(i3,i1,i2) => X=r,Y=p,Z=q => Y,Z,X
 *     opposite=BACKWARD : BACKWARD.choose = Axis.choose(i2,i3,i1) => X=q,Y=r,Z=p => Z,X,Y
 *   与上面 cycle() 的结果一致 —— 这就是'两种独立算法必须互相印证'的那一处。*/
struct AxisFrame {
    AxisCycle cycle;      /* between(axis, X) -- 只为可读性保留，求解本身不用它 */
    AxisCycle opposite;
    Axis x_axis;          /* p 下标对应的真实轴 */
    Axis y_axis;          /* q 下标对应的真实轴 */
    Axis z_axis;          /* r 下标对应的真实轴 */
};

/* 表只有这一份。行下标 = 求解轴（AXIS_X/AXIS_Y/AXIS_Z）。*/
inline const AxisFrame& axis_frame(Axis axis) {
    static const AxisFrame TABLE[3] = {
        /* axis = X */ { CYCLE_NONE,     CYCLE_NONE,     AXIS_X, AXIS_Y, AXIS_Z },
        /* axis = Y */ { CYCLE_BACKWARD, CYCLE_FORWARD,  AXIS_Y, AXIS_Z, AXIS_X },
        /* axis = Z */ { CYCLE_FORWARD,  CYCLE_BACKWARD, AXIS_Z, AXIS_X, AXIS_Y },
    };
    return TABLE[(int)axis];
}

/* ---------------------------------------------------------------- */
/* 形状视图                                                          */
/* ---------------------------------------------------------------- */

/* getPointPositions / getCoordIndex 的两种实现（1.20.4 只有这两个 VoxelShape 子类参与碰撞求解；
 * javap 实测 VoxelShapes.combine 只返回 SimpleVoxelShape 或 ArrayVoxelShape）。*/
enum ShapePointsKind {
    /* SimpleVoxelShape：getPointPosition(axis,i) = (double)i / size(axis)，
     * 且【覆写了 getCoordIndex】(floor(clamp(coord*size,-1,size)))。points 字段被忽略。*/
    SHAPE_POINTS_FRACTIONAL = 0,
    /* ArrayVoxelShape：显式点表，getCoordIndex = binarySearch(0,size+1,i->coord<point(i))-1。*/
    SHAPE_POINTS_EXPLICIT   = 1,
};

/* 形状来源。顺序在【调用方的列表里】表达，内核只原样回写。
 * 原版顺序（Entity.adjustMovementForCollisions(Entity,Vec3d,Box,World,List) 字节码 14-94）：
 *   1) entityCollisions（World.getEntityCollisions 的返回值，可能为空 -> 不 addAll）
 *   2) worldBorder.asVoxelShape()（仅当 entity != null 且 canCollide(entity, box.stretch(movement))）
 *   3) world.getBlockCollisions(entity, box.stretch(movement))（恒追加，惰性迭代）
 * 这个顺序【有语义】：VoxelShapes.calculateMaxOffset 在任一形状把 maxDist 压到 |.|<1e-7
 * 之后，会让【后续形状】直接短路返回 0。换顺序 = 换结果。*/
enum ShapeSource {
    SHAPE_SRC_ENTITY       = 0,
    SHAPE_SRC_WORLD_BORDER = 1,
    SHAPE_SRC_BLOCK        = 2,
    SHAPE_SRC_OTHER        = 3,
};

/* 一个 VoxelShape 的【完整且精确】的只读描述。
 *
 * 为什么不直接传'扁平 AABB 列表'：原版求解走的是【体素网格】(VoxelSet)，不是 AABB 列表。
 * VoxelShape.calculateMaxDistance 只用三样东西：
 *   voxels.getSize(axis)、getPointPosition(axis,i)、voxels.inBoundsAndContains(...)。
 * 把 AABB 列表反推回网格会引入不可忽略的 <1e-7 级误差
 * （VoxelShapes.cuboid 的 SimpleVoxelShape 路径把坐标量化到 round(c*size)/size）。
 * 所以这里原样传 (点表 + 体素位图)，与 VoxelSet 一一对应，零近似。
 *
 * BitSetVoxelSet 的存储就是一张覆盖 [0,sizeX)x[0,sizeY)x[0,sizeZ) 的位图：
 *   getIndex(x,y,z) = (x*sizeY + y)*sizeZ + z   （javap BitSetVoxelSet.getIndex）
 * 所以 bits 需要 ceil(sizeX*sizeY*sizeZ/64) 个 uint64，位序 little-endian（BitSet 语义）。
 * isEmpty() 对 BitSetVoxelSet 被覆写为 storage.isEmpty() => '位图全 0'。*/
struct ShapeView {
    int32_t         points_kind;   /* ShapePointsKind */
    int32_t         source;        /* ShapeSource */
    int32_t         size[3];       /* voxels.getSize(AXIS_X/Y/Z)，均 >= 0 */
    const double*   points[3];     /* points_kind==EXPLICIT 时有效，长度 size[a]+1 */
    const uint64_t* bits;          /* 长度 >= ceil(sizeX*sizeY*sizeZ/64) */
    /* 【身份令牌】：内核从不解释它，只把它原样回写到事件里。
     * Java 侧放'该形状在它自己的 VoxelShape[] 数组里的下标'，
     * 于是事件回放时 shapes[event.shape_token] 就是那个对象本身（同一引用，不是重建）。*/
    int64_t         shape_token;
    /* 仅 source==SHAPE_SRC_BLOCK 时有意义；其余填 INT32_MIN。*/
    int32_t         block_x, block_y, block_z;
};

struct ShapeListView {
    const ShapeView* items;        /* 允许为 null（count==0 时） */
    int32_t          count;        /* <0 视为 0 */
};

/* ---------------------------------------------------------------- */
/* 几何 / 事件 / 请求                                                */
/* ---------------------------------------------------------------- */

struct Vec3 { double x, y, z; };
struct Box  { double min_x, min_y, min_z, max_x, max_y, max_z; };

inline double box_min(const Box& b, Axis a) {
    return a == AXIS_X ? b.min_x : (a == AXIS_Y ? b.min_y : b.min_z);
}
inline double box_max(const Box& b, Axis a) {
    return a == AXIS_X ? b.max_x : (a == AXIS_Y ? b.max_y : b.max_z);
}
inline Box box_offset(const Box& b, const Vec3& v) {
    Box r;
    r.min_x = b.min_x + v.x; r.min_y = b.min_y + v.y; r.min_z = b.min_z + v.z;
    r.max_x = b.max_x + v.x; r.max_y = b.max_y + v.y; r.max_z = b.max_z + v.z;
    return r;
}
/* Box.stretch(double,double,double) 逐条转写（javap Box 589-670）：
 * 负值加 min、正值加 max、0 不动；NaN 走 dcmpg->ifge 为真、再 dcmpl->ifle 为假，
 * 于是【加到 max 上】（原版未对 NaN 做任何特判）。*/
inline Box box_stretch_xyz(const Box& b, double x, double y, double z) {
    double min_x = b.min_x, min_y = b.min_y, min_z = b.min_z;
    double max_x = b.max_x, max_y = b.max_y, max_z = b.max_z;
    if (x < 0.0) min_x += x; else if (x > 0.0) max_x += x;
    if (y < 0.0) min_y += y; else if (y > 0.0) max_y += y;
    if (z < 0.0) min_z += z; else if (z > 0.0) max_z += z;
    Box r; r.min_x = min_x; r.min_y = min_y; r.min_z = min_z;
           r.max_x = max_x; r.max_y = max_y; r.max_z = max_z;
    return r;
}
inline Box box_stretch(const Box& b, const Vec3& v) { return box_stretch_xyz(b, v.x, v.y, v.z); }

/* 事件：内核'扫过并命中'的每一个形状，按原版顺序。
 *
 * 【字段顺序即未来的 ABI 冻结顺序】（提案里 CavaMoveEvent 用同一顺序）：
 * 全部 4 字节字段在前、8 字节字段在后，内部零填充。*/
struct MoveEvent {
    int32_t  source;           /* ShapeSource，原样回写 */
    int32_t  axis;             /* 产生这次 clamp 的求解轴（AXIS_X/Y/Z） */
    int32_t  block_x, block_y, block_z;   /* 仅 BLOCK 来源有意义 */
    int32_t  pass;             /* 内部第几趟（0..4，见 resolve_movement 注释） */
    int32_t  accepted;         /* 1 = 该 offset 真的并进了 maxDist；0 = 被 +-1e-7 守卫拒绝 */
    int32_t  cell_x, cell_y, cell_z;      /* 命中的体素单元（真实 xyz 下标，非 p/q/r） */
    int32_t  reserved0, reserved1;
    int64_t  shape_token;         /* Java: shapes[shape_token] 即原对象 */
    double   offset;           /* 该形状算出的 d（pointPos - boxMax 或 pointPos - boxMin） */
    double   max_dist_before;  /* 该形状进入前的 maxDist */
    double   max_dist_after;   /* 该形状之后的 maxDist（未被接受时与 before 相同） */
};

struct MoveRequest {
    ShapeListView shapes;
    Box           box;          /* 进入时的实体碰撞箱（世界坐标，未 stretch） */
    Vec3          movement;     /* 已由 Java 侧算好的位移（本内核不做任何坐标变换） */
    double        step_height;  /* Entity.getStepHeight()，f2d 后的值 */
    int32_t       on_ground;    /* Entity.isOnGround() */
};

struct MoveResult {
    Vec3    delta;              /* 最终位移（= Entity.adjustMovementForCollisions(Vec3d) 的返回值） */
    Vec3    base_delta;         /* 第 0 趟（纯碰撞）的结果，便于差分定位 */
    Vec3    step_candidate;     /* 台阶候选 vec3d（未采用时等于 base_delta） */
    int32_t event_count;        /* 写入的事件个数（<= event_cap） */
    int32_t event_overflow;     /* 1 = 事件数组不够，【已丢弃】部分事件（绝不越界） */
    int32_t step_used;          /* 1 = 最终位移来自台阶分支 */
};

/* 事件数组不足时：内核【停止记录】（不越界、不报错），把 event_overflow 置 1，
 * 位移结果仍然完整正确。Java 侧看到 overflow 必须回退纯 Java（否则事件回放不全）。*/
#define CAVA_ENTITY_EVENT_OK        0
#define CAVA_ENTITY_EVENT_OVERFLOW  1

/* ---------------------------------------------------------------- */
/* 入口                                                              */
/* ---------------------------------------------------------------- */

/* 命中的体素单元（真实 xyz 下标）与该形状算出的 d。hit 可为 null。*/
struct HitInfo {
    int32_t hit;          /* 1 = 在扫描里找到了一个含实心体素的单元 */
    int32_t accepted;     /* 1 = d 通过了 +-1e-7 守卫并并进了 max_dist */
    int32_t cell_x, cell_y, cell_z;
    double  d;
};

struct EventSink {
    MoveEvent* events;    /* 允许为 null（只算位移、不要事件） */
    int32_t    cap;
    int32_t    count;
    int32_t    overflow;
    int32_t    pass;
};

/* VoxelShape.calculateMaxDistance(Direction.Axis, Box, double) 的逐分支复刻。
 * 只算几何；命中信息写进 hit（可为 null）。*/
double calculate_max_distance(const ShapeView& shape, Axis axis, const Box& box, double max_dist,
                              HitInfo* hit);

/* VoxelShapes.calculateMaxOffset(Direction.Axis, Box, Iterable<VoxelShape>, double) 的复刻，
 * 并在每个'命中'的形状上追加一条事件。*/
double calculate_max_offset(Axis axis, const Box& box, const ShapeListView& shapes, double max_dist,
                            EventSink* sink);

/* 私有静态重载 Entity.adjustMovementForCollisions(Vec3d, Box, List<VoxelShape>) 的复刻。*/
Vec3 adjust_movement_for_collisions(const Vec3& movement, const Box& box,
                                    const ShapeListView& shapes, EventSink* sink);

#define CAVA_ENTITY_ERR_NULL (-3)
#define CAVA_ENTITY_ERR_ARG  (-4)

/* 入口：等价于实例方法 Entity.adjustMovementForCollisions(Vec3d)（含台阶分支）。
 * 返回值：CAVA_ENTITY_EVENT_OK(0) / CAVA_ENTITY_ERR_NULL(-3) / CAVA_ENTITY_ERR_ARG(-4)。*/
int32_t resolve_movement(const MoveRequest& req, MoveEvent* events, int32_t event_cap, MoveResult* out);

/* 内核版本指纹（唯一事实来源；测试与 ABI 都读它）。*/
#define CAVA_ENTITY_KERNEL_ABI 1

} /* namespace entity */
} /* namespace cava */

#endif /* CAVA_ENTITY_H */
