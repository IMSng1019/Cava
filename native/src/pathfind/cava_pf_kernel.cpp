/* cava_pf_kernel.cpp —— P1 生物寻路原生内核（LandPathNodeMaker + PathNodeNavigator）。
 *
 * 逐分支复刻 net.minecraft.entity.ai.pathing（Yarn 1.20.4）。
 * 每处都可以在 docs/CAVA-pathfind-oracle-spec.md 与 build/javap 下的 javap 转写里找到出处。
 *
 * 【重要修正】isValidDiagonalSuccessor 的 flag5 极性：
 *   字节码 154/179: "iload 5 (flag5) ; ifeq 188"，188 = iconst_0; ireturn（返回 false）。
 *   机械验证：用 javac 21 编译两种候选源码形状并 javap 对比（build/probe/DiagProbe.java）：
 *       ... || flag()   -> "invokestatic flag; ifeq <false>"   <= 与字节码同形
 *       ... || !flag()  -> "invokestatic flag; ifne <false>"   <= 不同形
 *   结论：拒绝条件是 (side.y >= host.y && side.penalty < 0 && !flag5)。
 *   oracle 参照实现（LandMaker.java:464/467）与 oracle spec §2.4 的改写（第 266/267 行）
 *   都把这一位写反了；spec 第 256/259 行的字节码注释（"!flag5 -> return false"）才是对的。
 *   本文件按字节码实现（!flag5），并在 notes 文档里作为跨流请求上报。
 */
#include "cava_pf.h"

#include <cmath>
#include <cstring>
#include <limits>
#include <unordered_map>
#include <vector>

namespace cava {
namespace pathfind {

/* ------------------------------------------------------------------ */
/* 0. 常量与工具                                                       */
/* ------------------------------------------------------------------ */

static const float kDefaultPenalty[PT_COUNT] = {
    -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f, -1.0f, -1.0f, 8.0f,
    8.0f, 0.0f, -1.0f, 8.0f, 16.0f, 8.0f, -1.0f, 0.0f, -1.0f, -1.0f,
    4.0f, -1.0f, 8.0f, 0.0f, 0.0f, 0.0f
};

float default_penalty(int32_t type) {
    return (type >= 0 && type < PT_COUNT) ? kDefaultPenalty[type] : 0.0f;
}

int32_t java_floor(double v) {
    int32_t i = cava_d2i_sat(v);
    return v < (double) i ? i - 1 : i;
}
int32_t java_floor_f(float v) {
    int32_t i = cava_d2i_sat((double) v);
    return v < (float) i ? i - 1 : i;
}
int32_t java_ceil(double v) {
    int32_t i = cava_d2i_sat(v);
    return v > (double) i ? i + 1 : i;
}

/* MathHelper.sqrt(float) = (float)Math.sqrt((double)f)（规格 §5.1）。*/
static inline float mc_sqrt(float f) { return (float) std::sqrt((double) f); }

/* Java 的 Math.max(float,float) 是 (a >= b) ? a : b —— 与 std::max 在 NaN 上不同。
 * 本项目的 penalty 不可能是 NaN，但这里照样逐字对齐，免得将来有人"顺手优化"。*/
static inline float jmax(float a, float b) { return (a >= b) ? a : b; }
static inline int32_t jmax_i(int32_t a, int32_t b) { return (a >= b) ? a : b; }

/* Java 的 Math.abs(int)：INT_MIN 仍返回 INT_MIN（不 UB）。*/
static inline int32_t jabs_i(int32_t v) {
    return (v < 0) ? (int32_t) (0u - (uint32_t) v) : v;
}

/* Direction 枚举序：DOWN, UP, NORTH, SOUTH, WEST, EAST（规格 §2.7）。*/
enum : int32_t { DIR_DOWN = 0, DIR_UP = 1, DIR_NORTH = 2, DIR_SOUTH = 3, DIR_WEST = 4, DIR_EAST = 5 };
static const int32_t kDirX[6] = {0, 0, 0, 0, -1, 1};
static const int32_t kDirY[6] = {-1, 1, 0, 0, 0, 0};
static const int32_t kDirZ[6] = {0, 0, -1, 1, 0, 0};

/* PathNode.hash(int,int,int)（规格 §3.1）—— 不完美的 32 位打包，y 只有 8 位。*/
static inline int32_t node_hash(int32_t x, int32_t y, int32_t z) {
    return (y & 0xFF)
         | ((x & 0x7FFF) << 8)
         | ((z & 0x7FFF) << 24)
         | (x < 0 ? (int32_t) 0x80000000 : 0)
         | (z < 0 ? 0x8000 : 0);
}

/* BlockPos.asLong（规格 §3.5）。*/
static inline int64_t block_pos_long(int32_t x, int32_t y, int32_t z) {
    return ((int64_t) (x & 0x3FFFFFF) << 38) | ((int64_t) (y & 0xFFF)) | ((int64_t) (z & 0x3FFFFFF) << 12);
}

/* ------------------------------------------------------------------ */
/* 1. 世界视图                                                          */
/* ------------------------------------------------------------------ */

static const CavaStateRecord kOutOfWorld = {
    PF_AIR | PF_PATHFIND_LAND,   /* flags */
    CAVA_BOX_NONE,               /* box_offset */
    0u,                          /* box_count */
    (uint32_t) PT_OPEN,          /* path_type_idx */
    0.0f                         /* malus */
};

const CavaStateRecord& WorldView::state_at(int32_t x, int32_t y, int32_t z) const {
    if (recs == nullptr || ids == nullptr || dim_x <= 0 || dim_y <= 0 || dim_z <= 0) {
        return kOutOfWorld;
    }
    const int32_t lx = x - origin_x;
    const int32_t ly = y - origin_y;
    const int32_t lz = z - origin_z;
    if (lx < 0 || ly < 0 || lz < 0 || lx >= dim_x || ly >= dim_y || lz >= dim_z) {
        return kOutOfWorld;
    }
    /* 索引顺序：x 最快、y 最慢（cava_region_upload 约定）。*/
    const int32_t id = ids[((ly * dim_z) + lz) * dim_x + lx];
    if (id < 0 || id >= rec_count) {
        return kOutOfWorld;
    }
    return recs[id];
}

static inline bool box_range_ok(const WorldView& w, const CavaStateRecord& r) {
    return w.boxes != nullptr && r.box_count > 0 && r.box_offset != CAVA_BOX_NONE
           && r.box_offset <= (uint32_t) w.box_count
           && r.box_count <= (uint32_t) (w.box_count - (int32_t) r.box_offset);
}

float collision_max_y(const WorldView& w, const CavaStateRecord& r) {
    if (!box_range_ok(w, r)) return 0.0f;
    float m = w.boxes[r.box_offset].max_y;
    for (uint32_t i = 1; i < r.box_count; ++i) {
        const float v = w.boxes[r.box_offset + i].max_y;
        if (v > m) m = v;
    }
    return m;
}

float collision_min_y(const WorldView& w, const CavaStateRecord& r) {
    if (!box_range_ok(w, r)) return 0.0f;
    float m = w.boxes[r.box_offset].min_y;
    for (uint32_t i = 1; i < r.box_count; ++i) {
        const float v = w.boxes[r.box_offset + i].min_y;
        if (v < m) m = v;
    }
    return m;
}

double static_feet_y(const WorldView& w, int32_t x, int32_t y, int32_t z) {
    const CavaStateRecord& r = w.state_at(x, y - 1, z);
    return (double) (y - 1) + (double) collision_max_y(w, r);
}

bool is_space_empty(const WorldView& w,
                    double min_x, double min_y, double min_z,
                    double max_x, double max_y, double max_z) {
    int32_t x0 = java_floor(min_x), x1 = java_floor(max_x);
    int32_t y0 = java_floor(min_y), y1 = java_floor(max_y);
    int32_t z0 = java_floor(min_z), z1 = java_floor(max_z);

    /* 安全裁剪：区域外恒为 OUT_OF_WORLD（无碰撞），迭代它们不可能改变结果。
     * 这同时把最坏情况的迭代量限制在区域体积内（防止病态 box 造成 2^31 次循环）。*/
    if (w.dim_x > 0) {
        if (x0 < w.origin_x) x0 = w.origin_x;
        if (x1 > w.origin_x + w.dim_x - 1) x1 = w.origin_x + w.dim_x - 1;
        if (y0 < w.origin_y) y0 = w.origin_y;
        if (y1 > w.origin_y + w.dim_y - 1) y1 = w.origin_y + w.dim_y - 1;
        if (z0 < w.origin_z) z0 = w.origin_z;
        if (z1 > w.origin_z + w.dim_z - 1) z1 = w.origin_z + w.dim_z - 1;
    }
    for (int32_t y = y0; y <= y1; ++y) {
        for (int32_t z = z0; z <= z1; ++z) {
            for (int32_t x = x0; x <= x1; ++x) {
                const CavaStateRecord& r = w.state_at(x, y, z);
                if (!box_range_ok(w, r)) continue;
                for (uint32_t i = 0; i < r.box_count; ++i) {
                    const CavaCollisionBox& b = w.boxes[r.box_offset + i];
                    const double b_min_x = (double) x + (double) b.min_x;
                    const double b_min_y = (double) y + (double) b.min_y;
                    const double b_min_z = (double) z + (double) b.min_z;
                    const double b_max_x = (double) x + (double) b.max_x;
                    const double b_max_y = (double) y + (double) b.max_y;
                    const double b_max_z = (double) z + (double) b.max_z;
                    if (min_x < b_max_x && max_x > b_min_x
                        && min_y < b_max_y && max_y > b_min_y
                        && min_z < b_max_z && max_z > b_min_z) {
                        return false;
                    }
                }
            }
        }
    }
    return true;
}

int32_t MobProfile::block_x() const { return java_floor(x); }
int32_t MobProfile::block_y() const { return java_floor(y); }
int32_t MobProfile::block_z() const { return java_floor(z); }

/* ------------------------------------------------------------------ */
/* 2. 节点竞技场 + 最小堆                                               */
/* ------------------------------------------------------------------ */

namespace {

struct Node {
    int32_t x = 0, y = 0, z = 0;
    int32_t hash = 0;
    int32_t heap_index = -1;
    float   penalized_path_length = 0.0f;
    float   distance_to_nearest_target = 0.0f;
    float   heap_weight = 0.0f;
    float   path_length = 0.0f;
    float   penalty = 0.0f;
    int32_t previous = -1;
    bool    visited = false;
    int32_t type = PT_BLOCKED;
};

/* PathNodeMaker.pathNodeCache：**按 int 哈希去重**，所以坐标不同但哈希相同的两个位置
 * 会共享同一个 PathNode 实例（原版的坏哈希语义，规格 §3.3）。这里用 hash -> index。*/
struct NodeArena {
    std::vector<Node> nodes;
    std::unordered_map<int32_t, int32_t> cache;

    NodeArena() { nodes.reserve(4096); cache.reserve(4096); }

    int32_t get(int32_t x, int32_t y, int32_t z) {
        const int32_t h = node_hash(x, y, z);
        auto it = cache.find(h);
        if (it != cache.end()) return it->second;
        const int32_t idx = (int32_t) nodes.size();
        Node n;
        n.x = x; n.y = y; n.z = z; n.hash = h;
        nodes.push_back(n);
        cache.emplace(h, idx);
        return idx;
    }
};

/* PathMinHeap 的逐指令复刻（规格 §1）。存的是节点下标，-1 代表 Java 的 null。*/
struct MinHeap {
    std::vector<int32_t> a;
    int32_t count = 0;
    NodeArena* arena = nullptr;

    void init(NodeArena* ar) { arena = ar; a.clear(); a.reserve(1024); count = 0; }
    void clear() { count = 0; }
    bool is_empty() const { return count == 0; }

    void push(int32_t idx) {
        Node& node = arena->nodes[idx];
        /* 原版: if (node.heapIndex >= 0) throw new IllegalStateException("OW KNOWS!") */
        if (node.heap_index >= 0) return; /* 不可达；原版会抛异常 */
        if ((size_t) count == a.size()) a.resize(count == 0 ? 128 : (size_t) count * 2);
        a[(size_t) count] = idx;
        node.heap_index = count;
        shift_up(count);
        count += 1;
    }

    int32_t pop() {
        const int32_t idx = a[0];
        count -= 1;
        a[0] = a[(size_t) count];
        a[(size_t) count] = -1;
        if (count > 0) shift_down(0);
        arena->nodes[idx].heap_index = -1;
        return idx;
    }

    void set_node_weight(int32_t idx, float weight) {
        Node& node = arena->nodes[idx];
        const float old = node.heap_weight;
        node.heap_weight = weight;
        if (weight < old) shift_up(node.heap_index);
        else shift_down(node.heap_index);
    }

    void shift_up(int32_t index) {
        const int32_t idx = a[(size_t) index];
        const float weight = arena->nodes[idx].heap_weight;
        while (index > 0) {
            const int32_t parent = (index - 1) >> 1;
            const int32_t parent_idx = a[(size_t) parent];
            if (weight < arena->nodes[parent_idx].heap_weight) {
                a[(size_t) index] = parent_idx;
                arena->nodes[parent_idx].heap_index = index;
                index = parent;
            } else {
                break;
            }
        }
        a[(size_t) index] = idx;
        arena->nodes[idx].heap_index = index;
    }

    void shift_down(int32_t index) {
        const int32_t idx = a[(size_t) index];
        const float weight = arena->nodes[idx].heap_weight;
        for (;;) {
            const int32_t child = 1 + (index << 1);
            const int32_t sibling = child + 1;
            if (child >= count) break;
            const int32_t child_idx = a[(size_t) child];
            const float child_weight = arena->nodes[child_idx].heap_weight;
            int32_t sibling_idx;
            float sibling_weight;
            if (sibling >= count) {
                sibling_idx = -1;
                sibling_weight = INFINITY;   /* Float.POSITIVE_INFINITY */
            } else {
                sibling_idx = a[(size_t) sibling];
                sibling_weight = arena->nodes[sibling_idx].heap_weight;
            }
            if (child_weight < sibling_weight) {
                if (child_weight < weight) {
                    a[(size_t) index] = child_idx;
                    arena->nodes[child_idx].heap_index = index;
                    index = child;
                } else {
                    break;
                }
            } else {
                if (sibling_weight < weight) {
                    if (sibling_idx < 0) break;   /* 原版此处会 NPE；实测不可达，防御性 break */
                    a[(size_t) index] = sibling_idx;
                    arena->nodes[sibling_idx].heap_index = index;
                    index = sibling;
                } else {
                    break;
                }
            }
        }
        a[(size_t) index] = idx;
        arena->nodes[idx].heap_index = index;
    }
};

/* ------------------------------------------------------------------ */
/* 3. 求解器（LandPathNodeMaker + AmphibiousPathNodeMaker + Navigator）  */
/* ------------------------------------------------------------------ */

struct Solver {
    const WorldView* w = nullptr;
    MobProfile mob;                 /* 拷贝：amphibious init 会改写惩罚表（可观测副作用）*/
    SolveParams p;

    NodeArena arena;
    MinHeap heap;
    std::unordered_map<int64_t, int32_t> node_types;

    int32_t entity_block_x_size = 1;
    int32_t entity_block_y_size = 1;
    int32_t entity_block_z_size = 1;

    int32_t successors[16];
    int32_t successor_count = 0;

    /* ---- 基础查询 ---- */
    int32_t fluid_at(int32_t x, int32_t y, int32_t z) const {
        return fluid_of(w->state_at(x, y, z).flags);
    }
    bool fluid_is_water(int32_t x, int32_t y, int32_t z) const {
        return fluid_at(x, y, z) == FLUID_WATER;
    }

    /* LandPathNodeMaker.getCommonNodeType（规格 §5.4.6，分支顺序即优先级）。*/
    int32_t common_node_type(int32_t x, int32_t y, int32_t z) const {
        const uint32_t f = w->state_at(x, y, z).flags;
        if (has(f, PF_AIR)) return PT_OPEN;
        if (has(f, PF_TRAPDOOR)) return PT_TRAPDOOR;
        if (has(f, PF_POWDER_SNOW)) return PT_POWDER_SNOW;
        if (has(f, PF_CACTUS_OR_BERRY)) return PT_DAMAGE_OTHER;
        if (has(f, PF_HONEY)) return PT_STICKY_HONEY;
        if (has(f, PF_COCOA)) return PT_COCOA;
        if (has(f, PF_CAUTIOUS)) return PT_DAMAGE_CAUTIOUS;
        if (fluid_of(f) == FLUID_LAVA) return PT_LAVA;
        if (has(f, PF_FIRE_DAMAGE)) return PT_DAMAGE_FIRE;
        if (has(f, PF_DOOR)) {
            if (has(f, PF_DOOR_OPEN)) return PT_DOOR_OPEN;
            return has(f, PF_DOOR_HAND) ? PT_DOOR_WOOD_CLOSED : PT_DOOR_IRON_CLOSED;
        }
        if (has(f, PF_RAIL)) return PT_RAIL;
        if (has(f, PF_LEAVES)) return PT_LEAVES;
        if (has(f, PF_FENCE_TAG) || has(f, PF_WALL_TAG)
            || (has(f, PF_FENCE_GATE) && !has(f, PF_FENCE_GATE_OPEN))) {
            return PT_FENCE;
        }
        if (!has(f, PF_PATHFIND_LAND)) return PT_BLOCKED;
        if (fluid_of(f) == FLUID_WATER) return PT_WATER;
        return PT_OPEN;
    }

    int32_t node_type_from_neighbors(int32_t x, int32_t y, int32_t z, int32_t fallback) const {
        for (int32_t dx = -1; dx <= 1; ++dx) {
            for (int32_t dy = -1; dy <= 1; ++dy) {
                for (int32_t dz = -1; dz <= 1; ++dz) {
                    if (dx == 0 && dz == 0) continue;
                    const int32_t px = x + dx, py = y + dy, pz = z + dz;
                    const uint32_t f = w->state_at(px, py, pz).flags;
                    if (has(f, PF_CACTUS_OR_BERRY)) return PT_DANGER_OTHER;
                    if (has(f, PF_FIRE_DAMAGE)) return PT_DANGER_FIRE;
                    if (fluid_of(f) == FLUID_WATER) return PT_WATER_BORDER;
                    if (has(f, PF_CAUTIOUS)) return PT_DAMAGE_CAUTIOUS;
                }
            }
        }
        return fallback;
    }

    int32_t land_node_type(int32_t x, int32_t y, int32_t z) const {
        const int32_t common = common_node_type(x, y, z);
        if (common != PT_OPEN || y < w->min_y + 1) return common;
        const int32_t below = common_node_type(x, y - 1, z);
        switch (below) {
            case PT_OPEN: case PT_WATER: case PT_LAVA: case PT_WALKABLE:
                return PT_OPEN;
            case PT_DAMAGE_FIRE:     return PT_DAMAGE_FIRE;
            case PT_DAMAGE_OTHER:    return PT_DAMAGE_OTHER;
            case PT_STICKY_HONEY:    return PT_STICKY_HONEY;
            case PT_POWDER_SNOW:     return PT_DANGER_POWDER_SNOW;
            case PT_DAMAGE_CAUTIOUS: return PT_DAMAGE_CAUTIOUS;
            case PT_TRAPDOOR:        return PT_DANGER_TRAPDOOR;
            default:                 return node_type_from_neighbors(x, y, z, PT_WALKABLE);
        }
    }

    int32_t amphibious_default_node_type(int32_t x, int32_t y, int32_t z) const {
        const int32_t common = common_node_type(x, y, z);
        if (common == PT_WATER) {
            for (int32_t d = 0; d < 6; ++d) {
                if (common_node_type(x + kDirX[d], y + kDirY[d], z + kDirZ[d]) == PT_BLOCKED) {
                    return PT_WATER_BORDER;
                }
            }
            return PT_WATER;
        }
        return land_node_type(x, y, z);
    }

    /* LandPathNodeMaker.adjustNodeType —— **收到的 x/y/z 是实体的方块坐标**（规格 §5.4.2）。*/
    int32_t adjust_node_type(int32_t type, int32_t ex, int32_t ey, int32_t ez) const {
        const bool b = mob.can_enter_open_doors();
        int32_t r = type;
        if (r == PT_DOOR_WOOD_CLOSED && mob.can_open_doors() && b) r = PT_WALKABLE_DOOR;
        if (r == PT_DOOR_OPEN && !b) r = PT_BLOCKED;
        if (r == PT_RAIL
            && !has(w->state_at(ex, ey, ez).flags, PF_RAIL)
            && !has(w->state_at(ex, ey - 1, ez).flags, PF_RAIL)) {
            r = PT_UNPASSABLE_RAIL;
        }
        return r;
    }

    int32_t find_nearby_node_types(int32_t x, int32_t y, int32_t z, bool* set, int32_t def) const {
        int32_t first = def;
        const int32_t ex = mob.block_x(), ey = mob.block_y(), ez = mob.block_z();
        for (int32_t dx = 0; dx < entity_block_x_size; ++dx) {
            for (int32_t dy = 0; dy < entity_block_y_size; ++dy) {
                for (int32_t dz = 0; dz < entity_block_z_size; ++dz) {
                    const int32_t px = dx + x, py = dy + y, pz = dz + z;
                    const int32_t base = mob.amphibious() ? amphibious_default_node_type(px, py, pz)
                                                          : land_node_type(px, py, pz);
                    const int32_t type = adjust_node_type(base, ex, ey, ez);
                    if (dx == 0 && dy == 0 && dz == 0) first = type;
                    set[type] = true;
                }
            }
        }
        return first;
    }

    /* LandPathNodeMaker.getNodeType(BlockView,x,y,z,MobEntity)。*/
    int32_t node_type_raw(int32_t x, int32_t y, int32_t z) const {
        bool set[PT_COUNT];
        std::memset(set, 0, sizeof(set));
        int32_t best = PT_BLOCKED;
        best = find_nearby_node_types(x, y, z, set, best);
        if (set[PT_FENCE]) return PT_FENCE;
        if (set[PT_UNPASSABLE_RAIL]) return PT_UNPASSABLE_RAIL;
        int32_t chosen = PT_BLOCKED;
        for (int32_t t = 0; t < PT_COUNT; ++t) {
            if (!set[t]) continue;
            if (mob.get_penalty(t) < 0.0f) return t;
            if (mob.get_penalty(t) >= mob.get_penalty(chosen)) chosen = t;
        }
        if (best == PT_OPEN && mob.get_penalty(chosen) == 0.0f && entity_block_x_size <= 1) {
            return PT_OPEN;
        }
        return chosen;
    }

    /* getNodeType(MobEntity,x,y,z)：带 nodeTypes 缓存（键 = BlockPos.asLong）。*/
    int32_t node_type(int32_t x, int32_t y, int32_t z) {
        const int64_t key = block_pos_long(x, y, z);
        auto it = node_types.find(key);
        if (it != node_types.end()) return it->second;
        const int32_t v = node_type_raw(x, y, z);
        node_types.emplace(key, v);
        return v;
    }

    /* ---- getStart ---- */
    int32_t can_path_through(int32_t x, int32_t y, int32_t z) {
        const int32_t type = node_type(x, y, z);
        return type == PT_OPEN || mob.get_penalty(type) >= 0.0f;
    }

    int32_t get_start_at(int32_t x, int32_t y, int32_t z) {
        const int32_t idx = arena.get(x, y, z);
        Node& n = arena.nodes[idx];
        n.type = node_type(x, y, z);
        n.penalty = mob.get_penalty(n.type);
        return idx;
    }

    int32_t get_start() {
        int32_t y = mob.block_y();
        const int32_t bx = mob.block_x();
        const int32_t bz = mob.block_z();
        if (mob.can_walk_on_fluid && fluid_at(bx, y, bz) != FLUID_NONE) {
            while (mob.can_walk_on_fluid && fluid_at(bx, y, bz) != FLUID_NONE) y++;
            y--;
        } else if (mob.can_swim() && mob.touching_water) {
            while (has(w->state_at(bx, y, bz).flags, PF_WATER_BLOCK) || fluid_is_water(bx, y, bz)) y++;
            y--;
        } else if (mob.on_ground) {
            y = java_floor(mob.y + 0.5);
        } else {
            int32_t px = mob.block_x(), py = mob.block_y(), pz = mob.block_z();
            while (has(w->state_at(px, py, pz).flags, PF_AIR)
                   || has(w->state_at(px, py, pz).flags, PF_PATHFIND_LAND)) {
                if (py <= w->min_y) break;
                py--;
            }
            y = py + 1;
        }
        const int32_t ex = mob.block_x();
        const int32_t ez = mob.block_z();
        if (!can_path_through(ex, y, ez)) {
            const int32_t fx0 = java_floor(mob.box_min_x());
            const int32_t fz0 = java_floor(mob.box_min_z());
            const int32_t fx1 = java_floor(mob.box_max_x());
            const int32_t fz1 = java_floor(mob.box_max_z());
            if (can_path_through(fx0, y, fz0)) return get_start_at(fx0, y, fz0);
            if (can_path_through(fx0, y, fz1)) return get_start_at(fx0, y, fz1);
            if (can_path_through(fx1, y, fz0)) return get_start_at(fx1, y, fz0);
            if (can_path_through(fx1, y, fz1)) return get_start_at(fx1, y, fz1);
        }
        return get_start_at(ex, y, ez);
    }

    /* ---- getPathNode ---- */
    double step_height() const { return std::max(1.125, (double) mob.step_height); }
    /* Math.max(1.0f, stepHeight) 是 float（规格 §5.3）。*/

    double feet_y(int32_t x, int32_t y, int32_t z) const {
        if ((mob.can_swim() || mob.amphibious()) && fluid_is_water(x, y, z)) {
            return (double) y + 0.5;
        }
        return static_feet_y(*w, x, y, z);
    }

    bool check_box_collision(double min_x, double min_y, double min_z,
                             double max_x, double max_y, double max_z) const {
        return !is_space_empty(*w, min_x, min_y, min_z, max_x, max_y, max_z);
    }

    /* LandPathNodeMaker.isBlocked(PathNode)（规格 §7.1）。*/
    bool is_blocked_node(int32_t idx) const {
        const Node& node = arena.nodes[idx];
        const double len_x = mob.box_max_x() - mob.box_min_x();
        const double len_y = mob.box_max_y() - mob.box_min_y();
        const double len_z = mob.box_max_z() - mob.box_min_z();
        double vx = (double) node.x - mob.x + len_x / 2.0;
        double vy = (double) node.y - mob.y + len_y / 2.0;
        double vz = (double) node.z - mob.z + len_z / 2.0;
        const double length = std::sqrt(vx * vx + vy * vy + vz * vz);
        const double avg = (len_x + len_y + len_z) / 3.0;
        const int32_t steps = java_ceil(length / avg);
        const double scale = (double) (1.0f / (float) steps);   /* steps==0 时是 +Inf，原版循环不执行 */
        vx *= scale; vy *= scale; vz *= scale;
        /* 原版：box = box.offset(v) 反复作用 —— **必须逐次累加**，不能写成 min + v*i
         * （重复加法与乘法在最后几位上可能不同）。*/
        double b_min_x = mob.box_min_x();
        double b_min_y = mob.box_min_y();
        double b_min_z = mob.box_min_z();
        double b_max_x = mob.box_max_x();
        double b_max_y = mob.box_max_y();
        double b_max_z = mob.box_max_z();
        for (int32_t i = 1; i <= steps; ++i) {
            b_min_x += vx; b_min_y += vy; b_min_z += vz;
            b_max_x += vx; b_max_y += vy; b_max_z += vz;
            if (check_box_collision(b_min_x, b_min_y, b_min_z, b_max_x, b_max_y, b_max_z)) {
                return false;
            }
        }
        return true;
    }

    static bool is_blocked_type(int32_t type) {
        return type == PT_FENCE || type == PT_DOOR_WOOD_CLOSED || type == PT_DOOR_IRON_CLOSED;
    }

    int32_t get_node_with(int32_t x, int32_t y, int32_t z, int32_t type, float penalty) {
        const int32_t idx = arena.get(x, y, z);
        Node& n = arena.nodes[idx];
        n.type = type;
        n.penalty = jmax(n.penalty, penalty);   /* Math.max，单调不减 */
        return idx;
    }

    int32_t get_blocked_node(int32_t x, int32_t y, int32_t z) {
        const int32_t idx = arena.get(x, y, z);
        Node& n = arena.nodes[idx];
        n.type = PT_BLOCKED;
        n.penalty = -1.0f;
        return idx;
    }

    int32_t get_path_node(int32_t x, int32_t y, int32_t z, int32_t max_y_step,
                          double prev_feet_y, int32_t dir, int32_t node_type_arg) {
        int32_t result = -1;
        const double f_y = feet_y(x, y, z);
        if (f_y - prev_feet_y > step_height()) return -1;
        int32_t type = node_type(x, y, z);
        float penalty = mob.get_penalty(type);
        const double half_width = (double) mob.width / 2.0;
        if (penalty >= 0.0f) result = get_node_with(x, y, z, type, penalty);
        if (is_blocked_type(node_type_arg) && result != -1
            && arena.nodes[result].penalty >= 0.0f && !is_blocked_node(result)) {
            result = -1;
        }
        if (type == PT_WALKABLE || (mob.amphibious() && type == PT_WATER)) return result;
        if (result == -1 || arena.nodes[result].penalty < 0.0f) {
            if (max_y_step > 0
                && !(type == PT_FENCE && !mob.can_walk_over_fences())
                && type != PT_UNPASSABLE_RAIL && type != PT_TRAPDOOR && type != PT_POWDER_SNOW) {
                const int32_t up = get_path_node(x, y + 1, z, max_y_step - 1, prev_feet_y, dir, node_type_arg);
                if (up != -1 && (arena.nodes[up].type == PT_OPEN || arena.nodes[up].type == PT_WALKABLE)
                    && mob.width < 1.0f) {
                    const double dx = (double) (x - kDirX[dir]) + 0.5;
                    const double dz = (double) (z - kDirZ[dir]) + 0.5;
                    const double box_min_x = dx - half_width;
                    const double box_min_y = feet_y(java_floor(dx), y + 1, java_floor(dz)) + 0.001;
                    const double box_min_z = dz - half_width;
                    const double box_max_x = dx + half_width;
                    const double box_max_y = (double) mob.height
                                           + feet_y(arena.nodes[up].x, arena.nodes[up].y, arena.nodes[up].z)
                                           - 0.002;
                    const double box_max_z = dz + half_width;
                    if (check_box_collision(box_min_x, box_min_y, box_min_z,
                                            box_max_x, box_max_y, box_max_z)) {
                        result = -1;
                    }
                }
            }
        }
        if (!mob.amphibious() && type == PT_WATER && !mob.can_swim()) {
            if (node_type(x, y - 1, z) != PT_WATER) return result;
            while (y > w->min_y) {
                y--;
                type = node_type(x, y, z);
                if (type != PT_WATER) return result;
                result = get_node_with(x, y, z, type, mob.get_penalty(type));
            }
        }
        if (type == PT_OPEN) {
            int32_t fall = 0;
            const int32_t y0 = y;
            while (type == PT_OPEN) {
                y--;
                if (y < w->min_y) return get_blocked_node(x, y0, z);
                const int32_t previous_fall = fall++;
                if (previous_fall >= mob.safe_fall_distance) return get_blocked_node(x, y, z);
                type = node_type(x, y, z);
                penalty = mob.get_penalty(type);
                if (type != PT_OPEN && penalty >= 0.0f) {
                    result = get_node_with(x, y, z, type, penalty);
                    break;
                }
                if (penalty < 0.0f) return get_blocked_node(x, y, z);
            }
        }
        if (is_blocked_type(type) && result == -1) {
            result = arena.get(x, y, z);
            Node& n = arena.nodes[result];
            n.visited = true;
            n.type = type;
            n.penalty = default_penalty(type);
        }
        return result;
    }

    /* ---- 邻居 ---- */
    bool valid_adjacent(int32_t target, int32_t host) const {
        if (target == -1) return false;
        if (arena.nodes[target].visited) return false;
        return arena.nodes[target].penalty >= 0.0f || arena.nodes[host].penalty < 0.0f;
    }

    /* LandPathNodeMaker.isValidDiagonalSuccessor —— 见文件头的极性说明（!flag5）。*/
    bool valid_diagonal(int32_t host, int32_t side_a, int32_t side_b, int32_t diag) const {
        if (diag == -1 || side_b == -1 || side_a == -1) return false;
        if (arena.nodes[diag].visited) return false;
        if (arena.nodes[side_b].y > arena.nodes[host].y) return false;
        if (arena.nodes[side_a].y > arena.nodes[host].y) return false;
        if (arena.nodes[side_a].type == PT_WALKABLE_DOOR) return false;
        if (arena.nodes[side_b].type == PT_WALKABLE_DOOR) return false;
        if (arena.nodes[diag].type == PT_WALKABLE_DOOR) return false;
        const bool flag = arena.nodes[side_b].type == PT_FENCE && arena.nodes[side_a].type == PT_FENCE
                          && (double) mob.width < 0.5;
        if (arena.nodes[diag].penalty < 0.0f) return false;
        /* 单一事实来源：diagonal_side_rejected 见 cava_pf.h（字节码 154/179 的 ifeq 188）。*/
        if (diagonal_side_rejected(arena.nodes[side_b].y >= arena.nodes[host].y,
                                   arena.nodes[side_b].penalty < 0.0f, flag)) {
            return false;
        }
        if (diagonal_side_rejected(arena.nodes[side_a].y >= arena.nodes[host].y,
                                   arena.nodes[side_a].penalty < 0.0f, flag)) {
            return false;
        }
        return true;
    }

    bool valid_aquatic_adjacent(int32_t target, int32_t host) const {
        return valid_adjacent(target, host) && arena.nodes[target].type == PT_WATER;
    }

    int32_t get_successors(int32_t current) {
        int32_t count = 0;
        const int32_t x = arena.nodes[current].x;
        const int32_t y = arena.nodes[current].y;
        const int32_t z = arena.nodes[current].z;
        const int32_t above = node_type(x, y + 1, z);
        const int32_t here = node_type(x, y, z);
        int32_t max_y_step = 0;
        if (!(mob.get_penalty(above) < 0.0f) && here != PT_STICKY_HONEY) {
            max_y_step = java_floor_f(jmax(1.0f, mob.step_height));
        }
        const double f_y = feet_y(x, y, z);

        const int32_t south = get_path_node(x, y + 1, z + 1, max_y_step, f_y, DIR_SOUTH, here);
        if (valid_adjacent(south, current)) successors[count++] = south;
        const int32_t west = get_path_node(x - 1, y + 1, z, max_y_step, f_y, DIR_WEST, here);
        if (valid_adjacent(west, current)) successors[count++] = west;
        const int32_t east = get_path_node(x + 1, y + 1, z, max_y_step, f_y, DIR_EAST, here);
        if (valid_adjacent(east, current)) successors[count++] = east;
        const int32_t north = get_path_node(x, y + 1, z - 1, max_y_step, f_y, DIR_NORTH, here);
        if (valid_adjacent(north, current)) successors[count++] = north;

        const int32_t north_west = get_path_node(x - 1, y + 1, z - 1, max_y_step, f_y, DIR_NORTH, here);
        if (valid_diagonal(current, west, north, north_west)) successors[count++] = north_west;
        const int32_t north_east = get_path_node(x + 1, y + 1, z - 1, max_y_step, f_y, DIR_NORTH, here);
        if (valid_diagonal(current, east, north, north_east)) successors[count++] = north_east;
        const int32_t south_west = get_path_node(x - 1, y + 1, z + 1, max_y_step, f_y, DIR_SOUTH, here);
        if (valid_diagonal(current, west, south, south_west)) successors[count++] = south_west;
        const int32_t south_east = get_path_node(x + 1, y + 1, z + 1, max_y_step, f_y, DIR_SOUTH, here);
        if (valid_diagonal(current, east, south, south_east)) successors[count++] = south_east;

        if (mob.amphibious()) {
            const int32_t up = get_path_node(x, y + 1, z, jmax_i(0, max_y_step - 1), f_y, DIR_UP, here);
            const int32_t down = get_path_node(x, y - 1, z, max_y_step, f_y, DIR_DOWN, here);
            if (valid_aquatic_adjacent(up, current)) successors[count++] = up;
            if (valid_aquatic_adjacent(down, current) && here != PT_TRAPDOOR) successors[count++] = down;
            for (int32_t i = 0; i < count; ++i) {
                Node& n = arena.nodes[successors[i]];
                if (n.type == PT_WATER && mob.penalize_deep_water() && n.y < w->sea_level - 10) {
                    n.penalty += 1.0f;
                }
            }
        }
        return count;
    }

    /* ---- float 距离（全程 float，规格 §5.1）---- */
    static float node_distance(const Node& a, const Node& b) {
        const float dx = (float) (b.x - a.x);
        const float dy = (float) (b.y - a.y);
        const float dz = (float) (b.z - a.z);
        return mc_sqrt(dx * dx + dy * dy + dz * dz);
    }
    static float node_manhattan(const Node& a, const Node& b) {
        /* Math.abs(int) 先算再 i2f；加法顺序 (|dx|+|dy|)+|dz|。*/
        const float dx = (float) jabs_i(b.x - a.x);
        const float dy = (float) jabs_i(b.y - a.y);
        const float dz = (float) jabs_i(b.z - a.z);
        return dx + dy + dz;
    }
};

} /* anonymous namespace */

/* ------------------------------------------------------------------ */
/* 4. 主循环                                                            */
/* ------------------------------------------------------------------ */

bool solve(const WorldView& world, const MobProfile& mob_in,
           const SolveParams& params, SolveResult& out) {
    out = SolveResult();
    if (!world.valid()) return false;
    if (params.start_x < -30000000 || params.start_x > 30000000) return false;
    if (params.target_x < -30000000 || params.target_x > 30000000) return false;

    Solver s;
    s.w = &world;
    s.mob = mob_in;
    s.p = params;

    /* PathNodeMaker.init */
    s.entity_block_x_size = java_floor_f(s.mob.width + 1.0f);
    s.entity_block_y_size = java_floor_f(s.mob.height + 1.0f);
    s.entity_block_z_size = java_floor_f(s.mob.width + 1.0f);
    s.node_types.reserve(4096);
    s.heap.init(&s.arena);

    /* AmphibiousPathNodeMaker.init 会改写生物自己的惩罚表（可观测副作用，规格 §5.4.7）。*/
    if (s.mob.amphibious()) {
        s.mob.set_penalty(PT_WATER, 0.0f);
        s.mob.set_penalty(PT_WALKABLE, 6.0f);
        s.mob.set_penalty(PT_WATER_BORDER, 4.0f);
    }

    const int32_t start = s.get_start();
    const int32_t target_node = s.arena.get(params.target_x, params.target_y, params.target_z);

    out.target_x = params.target_x;
    out.target_y = params.target_y;
    out.target_z = params.target_z;

    /* 起点初始化：注意 start.distanceToNearestTarget **不乘 1.5**。
     * nearest_node / nearest_distance 复刻 TargetPathNode.updateNearestNode（严格 <）。*/
    float nearest_distance = std::numeric_limits<float>::max();
    int32_t nearest_node = start;
    {
        const float d = Solver::node_distance(s.arena.nodes[start], s.arena.nodes[target_node]);
        if (d < nearest_distance) { nearest_distance = d; nearest_node = start; }
        s.arena.nodes[start].penalized_path_length = 0.0f;
        s.arena.nodes[start].distance_to_nearest_target = d;
        s.arena.nodes[start].heap_weight = s.arena.nodes[start].distance_to_nearest_target;
    }
    s.heap.push(start);

    bool found = false;
    int32_t visited = 0;
    const int32_t node_budget = params.node_budget;
    uint64_t trace = 0xCBF29CE484222325ull;
    int32_t expanded = 0;

    while (!s.heap.is_empty()) {
        visited++;
        if (visited >= node_budget) break;
        const int32_t current = s.heap.pop();
        expanded++;
        {
            const Node& n = s.arena.nodes[current];
            trace ^= (uint64_t) (n.x & 0xFFFFFFFF); trace *= 0x100000001B3ull;
            trace ^= (uint64_t) (n.y & 0xFFFFFFFF); trace *= 0x100000001B3ull;
            trace ^= (uint64_t) (n.z & 0xFFFFFFFF); trace *= 0x100000001B3ull;
            uint32_t hw_bits, pl_bits;
            std::memcpy(&hw_bits, &n.heap_weight, 4);
            std::memcpy(&pl_bits, &n.penalized_path_length, 4);
            trace ^= (uint64_t) hw_bits; trace *= 0x100000001B3ull;
            trace ^= (uint64_t) pl_bits; trace *= 0x100000001B3ull;
        }
        s.arena.nodes[current].visited = true;

        if (Solver::node_manhattan(s.arena.nodes[current], s.arena.nodes[target_node])
            <= (float) params.reach_radius) {
            found = true;
        }
        if (found) break;

        if (Solver::node_distance(s.arena.nodes[current], s.arena.nodes[start]) >= params.max_range) {
            continue;
        }
        const int32_t count = s.get_successors(current);
        for (int32_t i = 0; i < count; ++i) {
            const int32_t succ = s.successors[i];
            Node& sc = s.arena.nodes[succ];
            const Node& cur = s.arena.nodes[current];
            const float distance = Solver::node_distance(cur, sc);
            sc.path_length = cur.path_length + distance;
            const float penalized = cur.penalized_path_length + distance + sc.penalty;
            if (sc.path_length < params.max_range) {
                if (sc.heap_index < 0 || penalized < sc.penalized_path_length) {
                    sc.previous = current;
                    sc.penalized_path_length = penalized;
                    const float d = Solver::node_distance(sc, s.arena.nodes[target_node]);
                    if (d < nearest_distance) { nearest_distance = d; nearest_node = succ; }
                    sc.distance_to_nearest_target = d * 1.5f;
                    if (sc.heap_index >= 0) {
                        s.heap.set_node_weight(succ, sc.penalized_path_length + sc.distance_to_nearest_target);
                    } else {
                        sc.heap_weight = sc.penalized_path_length + sc.distance_to_nearest_target;
                        s.heap.push(succ);
                    }
                }
            }
        }
    }

    /* ---- createPath ---- */
    if (found) {
        out.reaches_target = false;
        const int32_t end = nearest_node;
        std::vector<int32_t> chain;
        int32_t n = end;
        chain.push_back(n);
        while (s.arena.nodes[n].previous != -1) {
            n = s.arena.nodes[n].previous;
            chain.push_back(n);
        }
        for (size_t i = chain.size(); i-- > 0;) {
            const Node& nd = s.arena.nodes[chain[i]];
            OutNode o;
            o.x = nd.x; o.y = nd.y; o.z = nd.z;
            o.type = nd.type;
            o.heap_index = nd.heap_index;
            o.visited = nd.visited;
            o.path_length = nd.path_length;
            o.penalized_path_length = nd.penalized_path_length;
            o.distance_to_nearest_target = nd.distance_to_nearest_target;
            o.heap_weight = nd.heap_weight;
            o.penalty = nd.penalty;
            out.nodes.push_back(o);
        }
    } else {
        out.reaches_target = true;
        const int32_t end = nearest_node;
        std::vector<int32_t> chain;
        int32_t n = end;
        chain.push_back(n);
        while (s.arena.nodes[n].previous != -1) {
            n = s.arena.nodes[n].previous;
            chain.push_back(n);
        }
        for (size_t i = chain.size(); i-- > 0;) {
            const Node& nd = s.arena.nodes[chain[i]];
            OutNode o;
            o.x = nd.x; o.y = nd.y; o.z = nd.z;
            o.type = nd.type;
            o.heap_index = nd.heap_index;
            o.visited = nd.visited;
            o.path_length = nd.path_length;
            o.penalized_path_length = nd.penalized_path_length;
            o.distance_to_nearest_target = nd.distance_to_nearest_target;
            o.heap_weight = nd.heap_weight;
            o.penalty = nd.penalty;
            out.nodes.push_back(o);
        }
    }
    out.found = true;
    if (!out.nodes.empty()) {
        const OutNode& last = out.nodes.back();
        /* Math.abs(int)：INT_MIN 上 std::abs 是 UB，用与 Java 同语义的 jabs_i。*/
        const float dx = (float) jabs_i(params.target_x - last.x);
        const float dy = (float) jabs_i(params.target_y - last.y);
        const float dz = (float) jabs_i(params.target_z - last.z);
        out.manhattan_distance_from_target = dx + dy + dz;
    }
    out.expanded_count = expanded;
    out.trace_hash = trace;
    return true;
}

} /* namespace pathfind */
} /* namespace cava */
