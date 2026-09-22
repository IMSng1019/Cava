/* cava_pf.h —— P1 生物寻路的原生内核（内部头，不是 ABI）。
 *
 * 所有权：W2-P1 流（native/src/pathfind/）。
 *
 * 语义来源（**唯一权威**）：
 *   - docs/CAVA-pathfind-oracle-spec.md（javap -p -c 的逐条转写）
 *   - 本机 javap 复核：build/javap/LandPathNodeMaker.txt 等
 * 本文件的每一条分支都必须能在字节码里找到出处；任何"顺手优化"都是 parity 事故。
 *
 * 数值纪律：所有代价运算保持 float；只有 MathHelper.sqrt 内部、getFeetY / Box /
 * 实体包围盒用 double（原版就是 double）。float 比较一律"先算后比"，不合并条件。
 */
#ifndef CAVA_PF_H
#define CAVA_PF_H

#include <cstdint>
#include <vector>

#include "../../include/cava_abi.h"

namespace cava {
namespace pathfind {

/* ------------------------------------------------------------------ */
/* 0. PathNodeType 序号（必须与 Java 枚举 ordinal 完全一致，含 defaultPenalty）*/
/* ------------------------------------------------------------------ */
enum : int32_t {
    PT_BLOCKED = 0,          /* -1.0f */
    PT_OPEN = 1,             /*  0.0f */
    PT_WALKABLE = 2,         /*  0.0f */
    PT_WALKABLE_DOOR = 3,    /*  0.0f */
    PT_TRAPDOOR = 4,         /*  0.0f */
    PT_POWDER_SNOW = 5,      /* -1.0f */
    PT_DANGER_POWDER_SNOW = 6, /* 0.0f */
    PT_FENCE = 7,            /* -1.0f */
    PT_LAVA = 8,             /* -1.0f */
    PT_WATER = 9,            /*  8.0f */
    PT_WATER_BORDER = 10,    /*  8.0f */
    PT_RAIL = 11,            /*  0.0f */
    PT_UNPASSABLE_RAIL = 12, /* -1.0f */
    PT_DANGER_FIRE = 13,     /*  8.0f */
    PT_DAMAGE_FIRE = 14,     /* 16.0f */
    PT_DANGER_OTHER = 15,    /*  8.0f */
    PT_DAMAGE_OTHER = 16,    /* -1.0f */
    PT_DOOR_OPEN = 17,       /*  0.0f */
    PT_DOOR_WOOD_CLOSED = 18,/* -1.0f */
    PT_DOOR_IRON_CLOSED = 19,/* -1.0f */
    PT_BREACH = 20,          /*  4.0f */
    PT_LEAVES = 21,          /* -1.0f */
    PT_STICKY_HONEY = 22,    /*  8.0f */
    PT_COCOA = 23,           /*  0.0f */
    PT_DAMAGE_CAUTIOUS = 24, /*  0.0f */
    PT_DANGER_TRAPDOOR = 25, /*  0.0f */
    PT_COUNT = 26
};

float default_penalty(int32_t type);

/* ------------------------------------------------------------------ */
/* 1. 方块状态扩展位                                                    */
/* ------------------------------------------------------------------ */
/* 【勘误 + 改号，2026-09-22，P1-Java-A 实测并上报】
 * 本枚举的**位号**曾经是 P1 内核流自己提案的一套编号（PF_AIR=1<<8 …），
 * 与 captain 随后冻结进 cava_abi.h 的 CAVA_SF_x 与 CAVA_PF_x 两族宏 **19 个位全部错开**。
 * 实测对拍（两个文件程序化比对）：bit8 = 头文件 TRAPDOOR / 本文件曾是 AIR；
 * bit15 = 头文件 RAIL / 曾是 DOOR；bit20 = 头文件 FIRE_DAMAGE / 曾是 FENCE_TAG；
 * bit25 = 头文件 FIRE / 曾是 PATHFIND_LAND；bit26 = 头文件 WITHER_ROSE / 曾是 WATER_BLOCK。
 * 也就是说：Java 按冻结头文件填的 flags 会被内核**逐位读错**（空气当活板门、
 * 铁轨当门、火焰当可通行、凋灵玫瑰当水方块），而且**运行期完全无法发现**
 * （cava_pathfind 现在返回 UNIMPLEMENTED，没有可观测行为）。
 *
 * 处置（**纯改号，零逻辑改动**）：这里不再自定义位号，改为**冻结头文件宏的别名**。
 * 每个内核谓词在头文件里都有精确对应位：
 *   PF_AIR              <- CAVA_SF_AIR          （头文件 bit6：isAir）
 *   PF_DOOR             <- CAVA_SF_DOOR         （头文件 bit7：是门）
 *   PF_DOOR_OPEN        <- CAVA_SF_OPEN         （头文件 bit5：门/活板门/栅栏门的"开着"）
 *   PF_FENCE_GATE_OPEN  <- CAVA_SF_OPEN         （栅栏门分支内与上式等价：栅栏门状态上
 *                                                SF_OPEN <=> state.get(FenceGateBlock.OPEN)）
 *   其余 15 位 <- 同名 CAVA_PF_*
 * 头文件里比内核多出的位（FENCE_OR_WALL_CLOSED / DOOR_IRON / FIRE / WITHER_ROSE）
 * 是**派生位**，Java 侧照填，内核不读。
 * **头文件是唯一权威，本文件不许再自定编号。** */
enum : uint32_t {
    PF_AIR              = CAVA_SF_AIR,          /* state.isAir() */
    PF_TRAPDOOR         = CAVA_PF_TRAPDOOR,     /* BlockTags.TRAPDOORS || LILY_PAD || BIG_DRIPLEAF */
    PF_POWDER_SNOW      = CAVA_PF_POWDER_SNOW,  /* Blocks.POWDER_SNOW */
    PF_CACTUS_OR_BERRY  = CAVA_PF_CACTUS_OR_BERRY, /* CACTUS || SWEET_BERRY_BUSH */
    PF_HONEY            = CAVA_PF_HONEY,        /* HONEY_BLOCK */
    PF_COCOA            = CAVA_PF_COCOA,        /* COCOA */
    PF_CAUTIOUS         = CAVA_PF_CAUTIOUS,     /* WITHER_ROSE || POINTED_DRIPSTONE */
    PF_DOOR             = CAVA_SF_DOOR,         /* block instanceof DoorBlock */
    PF_DOOR_OPEN        = CAVA_SF_OPEN,         /* state.get(OPEN) */
    PF_DOOR_HAND        = CAVA_PF_DOOR_HAND,    /* getBlockSetType().canOpenByHand() */
    PF_RAIL             = CAVA_PF_RAIL,         /* block instanceof AbstractRailBlock */
    PF_LEAVES           = CAVA_PF_LEAVES,       /* block instanceof LeavesBlock */
    PF_FENCE_TAG        = CAVA_PF_FENCES,       /* BlockTags.FENCES */
    PF_WALL_TAG         = CAVA_PF_WALLS,        /* BlockTags.WALLS */
    PF_FENCE_GATE       = CAVA_PF_FENCE_GATE,   /* block instanceof FenceGateBlock */
    PF_FENCE_GATE_OPEN  = CAVA_SF_OPEN,         /* state.get(FenceGateBlock.OPEN) */
    PF_FIRE_DAMAGE      = CAVA_PF_FIRE_DAMAGE,  /* LandPathNodeMaker.inflictsFireDamage(state) */
    PF_PATHFIND_LAND    = CAVA_PF_PATH_THROUGH_LAND, /* state.canPathfindThrough(view,pos,LAND) */
    PF_WATER_BLOCK      = CAVA_PF_WATER_BLOCK   /* state.isOf(Blocks.WATER) */
};

/* 能力位直接用 ABI 头的 CAVA_NAV_*（captain 已把本流提案的全部 11 位加进去了）。
 * 这里不再自定义 caps 位。*/

enum : int32_t { FLUID_NONE = 0, FLUID_WATER = 1, FLUID_LAVA = 2 };

inline int32_t fluid_of(uint32_t flags) {
    if (flags & CAVA_SF_LAVA) return FLUID_LAVA;
    if (flags & CAVA_SF_WATER) return FLUID_WATER;
    return FLUID_NONE;
}

/* ------------------------------------------------------------------ */
/* 2. 世界视图（有界长方体区域 + 状态表）                                */
/* ------------------------------------------------------------------ */
/* 越界约定与参照实现 Terrain 一致：区域外 = OUT_OF_WORLD（AIR、LAND 可通行、
 * 无流体、无碰撞）。这与 cava_region_state_id_at 返回 -1 是两件事：后者是
 * "查询 API 的返回值"，前者是"算法的取值"。*/
struct WorldView {
    const CavaStateRecord*  recs = nullptr;
    int32_t                 rec_count = 0;
    const CavaCollisionBox* boxes = nullptr;
    int32_t                 box_count = 0;

    int32_t origin_x = 0, origin_y = 0, origin_z = 0;
    int32_t dim_x = 0, dim_y = 0, dim_z = 0;
    const int32_t* ids = nullptr;

    int32_t min_y = -64;      /* world.getBottomY() */
    int32_t sea_level = 63;

    bool valid() const {
        return recs != nullptr && rec_count > 0 && ids != nullptr
               && dim_x > 0 && dim_y > 0 && dim_z > 0;
    }

    /* 越界 / 未初始化一律返回 OUT_OF_WORLD 记录，绝不返回 nullptr。*/
    const CavaStateRecord& state_at(int32_t x, int32_t y, int32_t z) const;
};

inline uint32_t sf(const CavaStateRecord& r) { return r.flags; }
inline bool has(uint32_t flags, uint32_t bit) { return (flags & bit) != 0; }

float collision_max_y(const WorldView& w, const CavaStateRecord& r);
float collision_min_y(const WorldView& w, const CavaStateRecord& r);

/* (y-1) + collisionMaxY(x, y-1, z) —— Terrain.staticFeetY */
double static_feet_y(const WorldView& w, int32_t x, int32_t y, int32_t z);

/* 原版 Box.intersects 用严格不等号。*/
bool is_space_empty(const WorldView& w,
                    double min_x, double min_y, double min_z,
                    double max_x, double max_y, double max_z);

/* ------------------------------------------------------------------ */
/* 3. 生物档案                                                          */
/* ------------------------------------------------------------------ */
struct MobProfile {
    float   width = 0.6f;
    float   height = 1.8f;
    float   step_height = 0.0f;
    int32_t safe_fall_distance = 3;
    uint32_t caps = 0;                 /* CAVA_CAP_* | PF_CAP_* */
    double  x = 0.0, y = 0.0, z = 0.0;
    bool    on_ground = false;
    bool    touching_water = false;
    bool    can_walk_on_fluid = false;

    float   penalty[PT_COUNT];
    bool    penalty_set[PT_COUNT];

    MobProfile() {
        for (int32_t i = 0; i < PT_COUNT; ++i) { penalty[i] = 0.0f; penalty_set[i] = false; }
    }

    float get_penalty(int32_t type) const {
        if (type < 0 || type >= PT_COUNT) return 0.0f;
        return penalty_set[type] ? penalty[type] : default_penalty(type);
    }
    void set_penalty(int32_t type, float v) {
        if (type < 0 || type >= PT_COUNT) return;
        penalty[type] = v; penalty_set[type] = true;
    }

    bool can_open_doors() const { return (caps & CAVA_NAV_CAN_OPEN_DOORS) != 0; }
    bool can_enter_open_doors() const { return (caps & CAVA_NAV_CAN_ENTER_OPEN_DOORS) != 0; }
    bool can_swim() const { return (caps & CAVA_NAV_CAN_SWIM) != 0; }
    bool can_walk_over_fences() const { return (caps & CAVA_NAV_CAN_WALK_OVER_FENCES) != 0; }
    bool amphibious() const { return (caps & CAVA_NAV_AMPHIBIOUS) != 0; }
    bool penalize_deep_water() const { return (caps & CAVA_NAV_PENALIZE_DEEP_WATER) != 0; }

    /* EntityDimensions.getBoxAt(Vec3d)：f = width / 2.0f 是 **float** 除法。*/
    double box_min_x() const { float f = width / 2.0f; return x - (double) f; }
    double box_min_y() const { return y; }
    double box_min_z() const { float f = width / 2.0f; return z - (double) f; }
    double box_max_x() const { float f = width / 2.0f; return x + (double) f; }
    double box_max_z() const { float f = width / 2.0f; return z + (double) f; }
    double box_max_y() const { return y + (double) height; }

    int32_t block_x() const;
    int32_t block_y() const;
    int32_t block_z() const;
};

/* ------------------------------------------------------------------ */
/* 4. 求解请求 / 结果                                                   */
/* ------------------------------------------------------------------ */
struct SolveParams {
    int32_t start_x = 0, start_y = 0, start_z = 0;
    int32_t target_x = 0, target_y = 0, target_z = 0;
    int32_t node_budget = 0;   /* = (int)((float)navigator_range * follow_range) */
    float   max_range = 0.0f;  /* findPathToAny 的第 4 个形参 f */
    int32_t reach_radius = 0;  /* 第 5 个形参 i */
};

struct OutNode {
    int32_t x = 0, y = 0, z = 0;
    int32_t type = PT_BLOCKED;
    bool    visited = false;
    float   path_length = 0.0f;
    float   penalized_path_length = 0.0f;
    float   distance_to_nearest_target = 0.0f;
    float   heap_weight = 0.0f;
    float   penalty = 0.0f;
};

struct SolveResult {
    bool     found = false;
    bool     reaches_target = false;
    int32_t  target_x = 0, target_y = 0, target_z = 0;
    float    manhattan_distance_from_target = 0.0f;
    int32_t  expanded_count = 0;
    uint64_t trace_hash = 0;
    std::vector<OutNode> nodes;
};

/* isValidDiagonalSuccessor 的**单侧拒绝条件**。
 *
 * 字节码（LandPathNodeMaker.isValidDiagonalSuccessor，偏移 154 / 179）：
 *     154: iload 5 (flag5) ; ifeq 188
 *     179: iload 5         ; ifeq 188
 *     184: iconst_1 ; goto 189     // true 出口唯一
 *     188: iconst_0 ; ireturn      // false 出口唯一
 * => 析取项是 flag5，取反后 **&& !flag5**；任一侧满足即拒绝（不是"两侧都"）。
 *
 * 抽成内联函数是为了让"极性读反"这类错误在单元层留下断言，而不是只靠随机向量。
 * 内核里那一处必须调用本函数（单一事实来源）。*/
inline bool diagonal_side_rejected(bool side_y_ge_host, bool side_penalty_neg, bool flag5) {
    return side_y_ge_host && side_penalty_neg && !flag5;
}

/* 单目标求解（原版是 Set<BlockPos>；多目标时不走原生，见 notes 文档）。
 * 注意：起点由**生物位姿**推出（原版 findPathToAny 也是调 pathNodeMaker.getStart()），
 * SolveParams.start_* 只用于一致性自检，不参与求解。
 * 返回 false 表示参数非法（调用方回退原逻辑）。*/
bool solve(const WorldView& world, const MobProfile& mob,
           const SolveParams& params, SolveResult& out);

/* ------------------------------------------------------------------ */
/* 5. 内部工具（测试与 ABI 层共用）                                      */
/* ------------------------------------------------------------------ */
int32_t java_floor(double v);
int32_t java_floor_f(float v);
int32_t java_ceil(double v);

} /* namespace pathfind */
} /* namespace cava */

#endif /* CAVA_PF_H */
