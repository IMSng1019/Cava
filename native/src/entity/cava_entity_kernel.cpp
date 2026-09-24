/* cava_entity_kernel.cpp -- P2 实体碰撞纯几何内核（逐分支复刻 1.20.4 字节码）。
 *
 * 权威来源（全部由 javap -p -c 读出，详见 docs/CAVA-entity-oracle-spec.md）：
 *   Entity.adjustMovementForCollisions(Entity,Vec3d,Box,World,List)  [静态 public]
 *   Entity.adjustMovementForCollisions(Vec3d,Box,List)                [静态 private，核心]
 *   Entity.adjustMovementForCollisions(Vec3d)                         [实例 private，含台阶]
 *   VoxelShapes.calculateMaxOffset(Axis, Box, Iterable, double)
 *   VoxelShape.calculateMaxDistance(Axis, Box, double)
 *   VoxelSet.inBoundsAndContains(AxisCycleDirection,int,int,int) / BitSetVoxelSet.contains
 *
 * 本文件不做任何世界查询、不产生副作用、不用超越函数。
 */
#include "cava_entity.h"

namespace cava {
namespace entity {

/* ------------------------------------------------------------------ */
/* MathHelper 的两个小工具（Java 语义，自己实现，不依赖 libm 的边界行为） */
/* ------------------------------------------------------------------ */

/* MathHelper.clamp(double value, double min, double max)
 * javap: dcmpg; ifge -> (v >= min 时跳过) ; 否则 return min ; 然后 Math.min(v,max)。
 * NaN 时 dcmpg 得 1 -> ifge 成立 -> 跳过 -> java_min(NaN,max) = NaN（原版不特判 NaN）。*/
inline double mh_clamp(double v, double lo, double hi) {
    if (v < lo) return lo;
    return java_min(v, hi);
}

/* MathHelper.floor(double)：先 (int) 饱和转换，再按 d < (double)i 修正一位。
 * 与 Math.floor(double) 不同（后者返回 double 且不饱和）。*/
inline int32_t mh_floor(double d) {
    int32_t i = java_d2i_sat(d);
    if (d < (double)i) i -= 1;
    return i;
}

/* ------------------------------------------------------------------ */
/* ShapeView 的三原语                                                    */
/* ------------------------------------------------------------------ */

/* VoxelShape.isEmpty() -> voxels.isEmpty()；BitSetVoxelSet 覆写为 storage.isEmpty()。
 * 位图全 0 <=> 空。size 为 0 时单词数也是 0，同样为空。*/
inline bool shape_is_empty(const ShapeView& s) {
    int64_t n = (int64_t)s.size[0] * (int64_t)s.size[1] * (int64_t)s.size[2];
    if (n <= 0) return true;
    if (s.bits == nullptr) return true;
    int64_t words = (n + 63) / 64;
    for (int64_t i = 0; i < words; ++i) {
        if (s.bits[i] != 0u) return false;
    }
    return true;
}

/* VoxelSet.getSize(axis) */
inline int32_t shape_size(const ShapeView& s, Axis a) { return s.size[(int)a]; }

/* VoxelShape.getPointPosition(axis, i) */
inline double shape_point_position(const ShapeView& s, Axis a, int32_t i) {
    if (s.points_kind == SHAPE_POINTS_FRACTIONAL) {
        /* SimpleVoxelShape.getPointPositions -> FractionalDoubleList(size)：
         * getDouble(i) = (double)i / (double)size  （javap FractionalDoubleList）。
         * 注意：这里必须逐位等于 i/size 这一次除法，不能换成乘法。*/
        return (double)i / (double)s.size[(int)a];
    }
    return s.points[(int)a][i];
}

/* MathHelper.binarySearch(start, length, predicate)：返回 [start,length) 里第一个让
 * predicate 为真的下标；没有则返回 length。predicate(i) = coord < getPointPosition(axis,i)。
 * javap MathHelper.binarySearch: i = length-start; while(i>0){ j=i/2; k=start+j;
 *   if(pred(k)) i=j; else { start=k+1; i=i-j-1; } } return start;  （整数除法向 0 截断，这里恒非负） */
inline int32_t shape_binary_search(const ShapeView& s, Axis a, double coord,
                                   int32_t start, int32_t length) {
    int32_t lo = start;
    int32_t i = length - start;
    while (i > 0) {
        int32_t j = i / 2;
        int32_t k = lo + j;
        if (coord < shape_point_position(s, a, k)) {
            i = j;
        } else {
            lo = k + 1;
            i = i - j - 1;
        }
    }
    return lo;
}

/* VoxelShape.getCoordIndex(axis, coord)。【两个子类实现不同，必须区分】：
 *   SimpleVoxelShape 覆写：floor(clamp(coord*size, -1, size))
 *   ArrayVoxelShape（及基类）：binarySearch(0, size+1, i->coord<point(i)) - 1
 * 二者在'coord*size 恰好进位到整数'时结果会差 1，属于真实可观测差异。*/
inline int32_t shape_coord_index(const ShapeView& s, Axis a, double coord) {
    int32_t n = s.size[(int)a];
    if (s.points_kind == SHAPE_POINTS_FRACTIONAL) {
        double v = coord * (double)n;
        return mh_floor(mh_clamp(v, -1.0, (double)n));
    }
    return shape_binary_search(s, a, coord, 0, n + 1) - 1;
}

/* VoxelSet.inBoundsAndContains(cycle, p, q, r)。
 * 由 axis_frame() 的单一事实来源把 (p,q,r) 映射成真实 (x,y,z)：
 *   coord[x_axis]=p, coord[y_axis]=q, coord[z_axis]=r   （见 cava_entity.h 的推导）。
 * 然后 bounds 检查 + bits[getIndex]。
 * 顺带把真实下标回填到 cell（事件用）。*/
inline bool shape_in_bounds_and_contains(const ShapeView& s, const AxisFrame& f,
                                         int32_t p, int32_t q, int32_t r, int32_t cell[3]) {
    int32_t c[3];
    c[(int)f.x_axis] = p;
    c[(int)f.y_axis] = q;
    c[(int)f.z_axis] = r;
    cell[0] = c[0]; cell[1] = c[1]; cell[2] = c[2];
    if (c[0] < 0 || c[1] < 0 || c[2] < 0) return false;
    if (c[0] >= s.size[0] || c[1] >= s.size[1] || c[2] >= s.size[2]) return false;
    int64_t idx = ((int64_t)c[0] * (int64_t)s.size[1] + (int64_t)c[1]) * (int64_t)s.size[2]
                + (int64_t)c[2];
    if (s.bits == nullptr) return false;
    return (s.bits[idx >> 6] >> (idx & 63)) & 1u;
}

/* ------------------------------------------------------------------ */
/* VoxelShape.calculateMaxDistance(Axis, Box, double)                   */
/* ------------------------------------------------------------------ */
double calculate_max_distance(const ShapeView& shape, Axis axis, const Box& box, double max_dist,
                              HitInfo* hit) {
    if (hit) { hit->hit = 0; hit->accepted = 0; hit->cell_x = hit->cell_y = hit->cell_z = 0; hit->d = 0.0; }
    /* 1) isEmpty() -> 原样返回 */
    if (shape_is_empty(shape)) return max_dist;
    /* 2) |maxDist| < 1e-7 -> 0.0 （NaN 时 '<' 为假，不在此短路） */
    if (java_abs(max_dist) < CAVA_EPS) return 0.0;

    const AxisFrame& f = axis_frame(axis);
    const Axis xa = f.x_axis;
    const Axis ya = f.y_axis;
    const Axis za = f.z_axis;

    const double max_on_x = box_max(box, xa);
    const double min_on_x = box_min(box, xa);
    const int32_t i = shape_coord_index(shape, xa, min_on_x + CAVA_EPS);
    const int32_t j = shape_coord_index(shape, xa, max_on_x - CAVA_EPS);
    const int32_t k = (int32_t)java_max(0.0, (double)shape_coord_index(shape, ya, box_min(box, ya) + CAVA_EPS));
    const int32_t l = (int32_t)java_min((double)shape_size(shape, ya),
                                        (double)(shape_coord_index(shape, ya, box_max(box, ya) - CAVA_EPS) + 1));
    const int32_t m = (int32_t)java_max(0.0, (double)shape_coord_index(shape, za, box_min(box, za) + CAVA_EPS));
    const int32_t n = (int32_t)java_min((double)shape_size(shape, za),
                                        (double)(shape_coord_index(shape, za, box_max(box, za) - CAVA_EPS) + 1));
    const int32_t o = shape_size(shape, xa);

    if (max_dist > 0.0) {
        for (int32_t p = j + 1; p < o; ++p) {
            for (int32_t q = k; q < l; ++q) {
                for (int32_t r = m; r < n; ++r) {
                    int32_t cell[3];
                    if (!shape_in_bounds_and_contains(shape, f, p, q, r, cell)) continue;
                    const double d = shape_point_position(shape, xa, p) - max_on_x;
                    if (hit) {
                        hit->hit = 1; hit->d = d;
                        hit->cell_x = cell[0]; hit->cell_y = cell[1]; hit->cell_z = cell[2];
                    }
                    if (d >= -CAVA_EPS) {
                        max_dist = java_min(max_dist, d);
                        if (hit) hit->accepted = 1;
                    }
                    return max_dist;
                }
            }
        }
    } else if (max_dist < 0.0) {
        for (int32_t p = i - 1; p >= 0; --p) {
            for (int32_t q = k; q < l; ++q) {
                for (int32_t r = m; r < n; ++r) {
                    int32_t cell[3];
                    if (!shape_in_bounds_and_contains(shape, f, p, q, r, cell)) continue;
                    const double d = shape_point_position(shape, xa, p + 1) - min_on_x;
                    if (hit) {
                        hit->hit = 1; hit->d = d;
                        hit->cell_x = cell[0]; hit->cell_y = cell[1]; hit->cell_z = cell[2];
                    }
                    if (d <= CAVA_EPS) {
                        max_dist = java_max(max_dist, d);
                        if (hit) hit->accepted = 1;
                    }
                    return max_dist;
                }
            }
        }
    }
    return max_dist;
}

/* ------------------------------------------------------------------ */
/* 事件 sink                                                            */
/* ------------------------------------------------------------------ */
static void sink_emit(EventSink* s, const ShapeView& sh, Axis axis, int32_t accepted,
                      const int32_t cell[3], double d, double before, double after) {
    if (s == nullptr) return;
    if (s->count >= s->cap || s->events == nullptr) { s->overflow = 1; return; }
    MoveEvent& e = s->events[s->count++];
    e.source = sh.source;
    e.axis = (int32_t)axis;
    e.block_x = sh.block_x; e.block_y = sh.block_y; e.block_z = sh.block_z;
    e.pass = s->pass;
    e.accepted = accepted;
    e.cell_x = cell[0]; e.cell_y = cell[1]; e.cell_z = cell[2];
    e.reserved0 = 0; e.reserved1 = 0;
    e.shape_token = sh.shape_token;
    e.offset = d;
    e.max_dist_before = before;
    e.max_dist_after = after;
}

/* ------------------------------------------------------------------ */
/* VoxelShapes.calculateMaxOffset(Axis, Box, Iterable, double)           */
/* ------------------------------------------------------------------ */
double calculate_max_offset(Axis axis, const Box& box, const ShapeListView& shapes, double max_dist,
                            EventSink* sink) {
    const ShapeView* items = shapes.items;
    int32_t count = shapes.count < 0 ? 0 : shapes.count;
    for (int32_t s = 0; s < count; ++s) {
        /* 关键：这个短路检查在**每次迭代的开头**，不是循环外、也不是末尾。
         * 后果：某个形状把 maxDist 压到 |.|<1e-7 后，**后续形状直接返回 0.0**；
         * 但如果它是**最后一个**形状，就没有这个后置检查，返回那个很小的值。*/
        if (java_abs(max_dist) < CAVA_EPS) return 0.0;
        if (items == nullptr) break;
        const ShapeView& sh = items[s];
        const double before = max_dist;
        HitInfo h;
        max_dist = calculate_max_distance(sh, axis, box, max_dist, &h);
        if (h.hit) {
            /* C99 复合字面量 (const int32_t[3]){...} 在 MSVC 上直接报 error C4576
             * （GCC/Clang 只当扩展接受）。换具名临时数组，语义完全一样：
             * 都是"本次调用内有效的 3 个 int32_t"，sink_emit 只读不存。*/
            const int32_t cell[3] = { h.cell_x, h.cell_y, h.cell_z };
            sink_emit(sink, sh, axis, h.accepted, cell, h.d, before, max_dist);
        }
    }
    return max_dist;
}

/* ------------------------------------------------------------------ */
/* 私有静态重载：Entity.adjustMovementForCollisions(Vec3d, Box, List)    */
/* ------------------------------------------------------------------ */
Vec3 adjust_movement_for_collisions(const Vec3& movement, const Box& box_in,
                                    const ShapeListView& shapes, EventSink* sink) {
    /* 0-10: if (shapes.isEmpty()) return movement;  （返回**同一个对象**，位模式原样） */
    if (shapes.count <= 0) return movement;

    Box box = box_in;
    double dx = movement.x;   /* 11-15 */
    double dy = movement.y;   /* 16-20 */
    double dz = movement.z;   /* 22-26 */

    /* 28-62: Y 轴永远第一，且是唯一会先 offset box 的轴（除分支内的 Z/X） */
    if (dy != 0.0) {
        dy = calculate_max_offset(AXIS_Y, box, shapes, dy, sink);
        if (dy != 0.0) box = box_offset(box, Vec3{0.0, dy, 0.0});
    }
    /* 63-81: boolean bl = Math.abs(dx) < Math.abs(dz);
     * dcmpg / ifge -> 反命题是 '>= ' 为真时不进入；NaN 时 Math.abs 为 NaN、dcmpg 得 1 ->
     * ifge 成立 -> bl = false（NaN 走 **X 先** 的分支）。*/
    const bool bl = java_abs(dx) < java_abs(dz);

    if (bl) {
        /* 83-122: Z 在前，且会把 box 沿 Z 位移 */
        if (dz != 0.0) {
            dz = calculate_max_offset(AXIS_Z, box, shapes, dz, sink);
            if (dz != 0.0) box = box_offset(box, Vec3{0.0, 0.0, dz});
        }
    }
    /* 123-157: X 一定算；只有当 !bl 时才把 dx 位移进 box */
    if (dx != 0.0) {
        dx = calculate_max_offset(AXIS_X, box, shapes, dx, sink);
        if (!bl) {
            if (dx != 0.0) box = box_offset(box, Vec3{dx, 0.0, 0.0});
        }
    }
    /* 158-181: !bl 时 Z 后算，**不再**位移 box（位移结果只用于返回） */
    if (!bl) {
        if (dz != 0.0) {
            dz = calculate_max_offset(AXIS_Z, box, shapes, dz, sink);
        }
    }
    Vec3 out; out.x = dx; out.y = dy; out.z = dz;   /* 182-194 */
    return out;
}

/* ------------------------------------------------------------------ */
/* 实例方法：Entity.adjustMovementForCollisions(Vec3d)（含台阶分支）      */
/* ------------------------------------------------------------------ */
inline double horizontal_length_squared(const Vec3& v) { return v.x * v.x + v.z * v.z; }

int32_t resolve_movement(const MoveRequest& req, MoveEvent* events, int32_t event_cap, MoveResult* out) {
    if (out == nullptr) return CAVA_ENTITY_ERR_NULL;
    if (event_cap < 0) return CAVA_ENTITY_ERR_ARG;
    if (events == nullptr && event_cap != 0) return CAVA_ENTITY_ERR_NULL;

    EventSink sink;
    sink.events = events; sink.cap = event_cap; sink.count = 0; sink.overflow = 0; sink.pass = 0;

    const ShapeListView shapes = req.shapes;
    const Box box = req.box;
    const Vec3 movement = req.movement;

    /* 19-43：movement.lengthSquared() == 0 时**直接返回 movement**（不做碰撞求解）。
     * dcmpl 对 NaN 得 -1 -> ifne 成立 -> 走真正求解（NaN 不被短路）。*/
    const double len_sq = movement.x * movement.x + movement.y * movement.y + movement.z * movement.z;
    Vec3 base;
    if (len_sq == 0.0) {
        base = movement;
    } else {
        base = adjust_movement_for_collisions(movement, box, shapes, &sink);
    }

    /* 45-103：三个 '是否被改了' 的判定用的是**原始 !=**（不是 approximatelyEquals） */
    const bool bl  = movement.x != base.x;   /* 局部 5 */
    const bool bl2 = movement.y != base.y;   /* 局部 6 */
    const bool bl3 = movement.z != base.z;   /* 局部 7 */
    /* 105-131: bl4 = isOnGround() || (bl2 && movement.y < 0.0) */
    const bool bl4 = (req.on_ground != 0) || (bl2 && movement.y < 0.0);

    Vec3 result = base;
    Vec3 step_candidate = base;
    int32_t step_used = 0;

    /* 133-139: stepHeight > 0 && bl4 && (bl || bl3) */
    if (req.step_height > 0.0 && bl4 && (bl || bl3)) {
        sink.pass = 1;
        Vec3 vec3d = adjust_movement_for_collisions(Vec3{movement.x, req.step_height, movement.z},
                                                    box, shapes, &sink);
        sink.pass = 2;
        Vec3 vec3d2 = adjust_movement_for_collisions(Vec3{0.0, req.step_height, 0.0},
                                                     box_stretch_xyz(box, movement.x, 0.0, movement.z),
                                                     shapes, &sink);
        if (vec3d2.y < req.step_height) {
            sink.pass = 3;
            Vec3 vec3d3 = adjust_movement_for_collisions(Vec3{movement.x, 0.0, movement.z},
                                                         box_offset(box, vec3d2), shapes, &sink);
            vec3d3.x += vec3d2.x; vec3d3.y += vec3d2.y; vec3d3.z += vec3d2.z;   /* Vec3d.add */
            if (horizontal_length_squared(vec3d3) > horizontal_length_squared(vec3d)) vec3d = vec3d3;
        }
        step_candidate = vec3d;
        if (horizontal_length_squared(vec3d) > horizontal_length_squared(base)) {
            sink.pass = 4;
            Vec3 down = adjust_movement_for_collisions(Vec3{0.0, -vec3d.y + movement.y, 0.0},
                                                       box_offset(box, vec3d), shapes, &sink);
            result.x = vec3d.x + down.x;
            result.y = vec3d.y + down.y;
            result.z = vec3d.z + down.z;
            step_used = 1;
        }
    }

    out->delta = result;
    out->base_delta = base;
    out->step_candidate = step_candidate;
    out->event_count = sink.count;
    out->event_overflow = sink.overflow;
    out->step_used = step_used;
    return CAVA_ENTITY_EVENT_OK;
}

} /* namespace entity */
} /* namespace cava */
