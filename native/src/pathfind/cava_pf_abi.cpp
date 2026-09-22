/* cava_pf_abi.cpp —— P1 寻路的 ABI 入口（cava_abi.h 里已冻结的那几个符号）。
 *
 * 实现范围（诚实版，见 docs/CAVA-p1-pathfind-notes.md 的「ABI 缺口」一节）：
 *   - cava_state_table_upload / cava_region_upload / cava_region_clear /
 *     cava_region_state_id_at：**完整可用**，是 Java 侧镜像的落地接口。
 *   - cava_pathfind：校验完整，但因为**冻结的 CavaPathRequest 里没有生物档案通道**
 *     （width/height/stepHeight/惩罚表/实体 double 位姿/world bottomY/seaLevel/maxRange），
 *     无法在不破坏 parity 的前提下求解 —— 因此返回 CAVA_ERR_UNIMPLEMENTED，
 *     Java 侧据此回退原逻辑（契约要求：错误码 = 回退，绝不是崩）。
 *     需要 captain 决定是否扩 ABI（提案见 notes）。
 *
 * 句柄校验：句柄编码 = (generation << 32) | (slot_index + 1)（见 cava_handle.cpp）。
 * 本文件只能校验**形状**并查自己的状态表 —— 真正的代际校验要 cava::detail::lookup()，
 * 它在 cava_handle.cpp 的匿名命名空间里（P0-B 所有）。这一条已写进 notes 的请求清单。
 */
#include "cava_pf.h"
#include "../cava_internal.h"   /* CAVA_EXPORT 与 CAVA_ASSERT（P0-B 所有，只读不改）*/

#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {

using cava::pathfind::MobProfile;
using cava::pathfind::WorldView;

constexpr int32_t kMaxHandleSlots = 256;
constexpr int32_t kMaxStateRecords = 1 << 20;
constexpr int32_t kMaxBoxes = 1 << 22;
constexpr int64_t kMaxRegionBlocks = 1 << 24;   /* 16M 方块 = 一次寻路窗口的合理上界 */

struct HandleState {
    std::vector<CavaStateRecord>  recs;
    std::vector<CavaCollisionBox> boxes;

    std::vector<int32_t> ids;
    int32_t dim_x = 0, dim_y = 0, dim_z = 0;
    int32_t origin_x = 0, origin_y = 0, origin_z = 0;
    bool region_valid = false;

    int32_t min_y = -64;
    int32_t sea_level = 63;

    bool has_mob = false;
    MobProfile mob;
};

std::mutex g_mutex;
std::unordered_map<int64_t, HandleState> g_states;

bool handle_shape_ok(int64_t handle) {
    const uint64_t u = (uint64_t) handle;
    const uint32_t lo = (uint32_t) (u & 0xFFFFFFFFull);
    return lo != 0 && lo <= (uint32_t) kMaxHandleSlots;
}

/* 返回 nullptr = 句柄形状非法或本子系统还没为它建立任何状态。*/
HandleState* find_state(int64_t handle) {
    if (!handle_shape_ok(handle)) return nullptr;
    auto it = g_states.find(handle);
    return it == g_states.end() ? nullptr : &it->second;
}

HandleState& ensure_state(int64_t handle) { return g_states[handle]; }

} /* namespace */

extern "C" CAVA_EXPORT int32_t cava_state_table_upload(int64_t handle,
                                                       const CavaStateRecord* records, int32_t record_count,
                                                       const CavaCollisionBox* boxes, int32_t box_count) {
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    if (record_count < 0 || box_count < 0) return CAVA_ERR_ARG;
    if (record_count > kMaxStateRecords || box_count > kMaxBoxes) return CAVA_ERR_ARG;
    if (record_count > 0 && records == nullptr) return CAVA_ERR_ARG;
    if (box_count > 0 && boxes == nullptr) return CAVA_ERR_ARG;
    /* 一次性上传：cap 不足（这里是上限）返回 CAVA_ERR_ARG 且不写任何内容。*/
    std::lock_guard<std::mutex> lock(g_mutex);
    HandleState& st = ensure_state(handle);
    try {
        st.recs.assign(records, records + record_count);
        st.boxes.assign(boxes, boxes + box_count);
    } catch (...) {
        return CAVA_ERR_OOM;
    }
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_region_upload(int64_t handle,
                                                  int32_t dim_x, int32_t dim_y, int32_t dim_z,
                                                  int32_t origin_x, int32_t origin_y, int32_t origin_z,
                                                  const int32_t* ids, int32_t id_count) {
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    if (dim_x <= 0 || dim_y <= 0 || dim_z <= 0) return CAVA_ERR_ARG;
    const int64_t vol = (int64_t) dim_x * (int64_t) dim_y * (int64_t) dim_z;
    if (vol <= 0 || vol > kMaxRegionBlocks) return CAVA_ERR_ARG;
    if (ids == nullptr || id_count != (int32_t) vol) return CAVA_ERR_ARG;

    std::vector<int32_t> copy;
    try {
        copy.assign(ids, ids + id_count);
    } catch (...) {
        return CAVA_ERR_OOM;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    HandleState& st = ensure_state(handle);
    st.ids.swap(copy);
    st.dim_x = dim_x; st.dim_y = dim_y; st.dim_z = dim_z;
    st.origin_x = origin_x; st.origin_y = origin_y; st.origin_z = origin_z;
    st.region_valid = true;
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_region_clear(int64_t handle) {
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    std::lock_guard<std::mutex> lock(g_mutex);
    HandleState* st = find_state(handle);
    if (st == nullptr) return CAVA_OK;   /* 幂等：没有区域也算清干净了 */
    st->ids.clear();
    st->region_valid = false;
    st->dim_x = st->dim_y = st->dim_z = 0;
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_region_state_id_at(int64_t handle, int32_t x, int32_t y, int32_t z,
                                                       int32_t* out_state_id) {
    if (out_state_id == nullptr) return CAVA_ERR_NULL;
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    *out_state_id = -1;
    std::lock_guard<std::mutex> lock(g_mutex);
    const HandleState* st = find_state(handle);
    if (st == nullptr || !st->region_valid) return CAVA_OK;   /* 区域外 = -1 */
    const int32_t lx = x - st->origin_x;
    const int32_t ly = y - st->origin_y;
    const int32_t lz = z - st->origin_z;
    if (lx < 0 || ly < 0 || lz < 0 || lx >= st->dim_x || ly >= st->dim_y || lz >= st->dim_z) {
        return CAVA_OK;
    }
    *out_state_id = st->ids[((size_t) ly * st->dim_z + lz) * st->dim_x + lx];
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_mob_profile_upload(int64_t handle, const CavaMobProfile* profile) {
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    if (profile == nullptr) return CAVA_ERR_ARG;
    if (!(profile->width > 0.0f) || !(profile->height > 0.0f)) return CAVA_ERR_ARG;   /* NaN 也拒 */
    if (!(profile->step_height >= 0.0f)) return CAVA_ERR_ARG;
    if (profile->penalty_mask & ~CAVA_PENALTY_ALL_SET) return CAVA_ERR_ARG;

    std::lock_guard<std::mutex> lock(g_mutex);
    HandleState& st = ensure_state(handle);
    MobProfile m;
    m.width = profile->width;
    m.height = profile->height;
    m.step_height = profile->step_height;
    m.safe_fall_distance = profile->safe_fall_distance;
    m.caps = profile->caps;
    m.x = profile->start_x; m.y = profile->start_y; m.z = profile->start_z;
    m.on_ground = (profile->caps & CAVA_NAV_ON_GROUND) != 0;
    m.touching_water = (profile->caps & CAVA_NAV_TOUCHING_WATER) != 0;
    m.can_walk_on_fluid = (profile->caps & CAVA_NAV_CAN_WALK_ON_FLUID) != 0;
    /* 惩罚表按 CAVA_PNT_* 索引写进**同一索引**的内部表；索引语义是否一致见 cava_pathfind 的注释 (1)。*/
    for (int32_t i = 0; i < CAVA_PNT_COUNT && i < cava::pathfind::PT_COUNT; ++i) {
        if (profile->penalty_mask & (1u << i)) m.set_penalty(i, profile->penalty[i]);
    }
    st.mob = m;
    st.min_y = profile->min_y;
    st.sea_level = profile->sea_level;
    st.has_mob = true;
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_mob_profile_clear(int64_t handle) {
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    std::lock_guard<std::mutex> lock(g_mutex);
    HandleState* st = find_state(handle);
    if (st == nullptr) return CAVA_OK;   /* 幂等 */
    st->has_mob = false;
    st->mob = MobProfile();
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_pathfind(int64_t handle, const CavaPathRequest* req,
                                             CavaPathNode* out, int32_t cap) {
    if (req == nullptr || out == nullptr) return CAVA_ERR_NULL;
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    if (cap <= 0) return CAVA_ERR_ARG;
    if (req->flags != 0) return CAVA_ERR_ARG;        /* 契约：flags 保留，必须为 0 */
    if (req->reserved0 != 0 || req->reserved1 != 0 || req->reserved2 != 0) return CAVA_ERR_ARG;

    std::lock_guard<std::mutex> lock(g_mutex);
    const HandleState* st = find_state(handle);
    if (st == nullptr || st->recs.empty()) return CAVA_ERR_ARG;      /* 状态表还没推 */
    if (!st->region_valid || st->ids.empty()) return CAVA_ERR_ARG;   /* 区域还没推 */
    if (!st->has_mob) return CAVA_ERR_ARG;                           /* 生物档案还没推 */

    /* ⚠️ 输入齐了，但**还不能算** —— 有两处必须先由 captain 裁决的语义冲突：
     *
     * (1) CAVA_PNT_* 的序号表与 javap 实证的 PathNodeType 枚举 ordinal **不一致**。
     *     头文件写"必须与 Java 枚举 ordinal 完全一致"，但实测（oracle spec §8，
     *     javap -p -c net.minecraft.entity.ai.pathing.PathNodeType 的 static{}）真实 ordinal 是：
     *        0 BLOCKED 1 OPEN 2 WALKABLE 3 WALKABLE_DOOR 4 TRAPDOOR 5 POWDER_SNOW
     *        6 DANGER_POWDER_SNOW 7 FENCE 8 LAVA 9 WATER 10 WATER_BORDER 11 RAIL
     *        12 UNPASSABLE_RAIL 13 DANGER_FIRE 14 DAMAGE_FIRE 15 DANGER_OTHER 16 DAMAGE_OTHER
     *        17 DOOR_OPEN 18 DOOR_WOOD_CLOSED 19 DOOR_IRON_CLOSED 20 BREACH 21 LEAVES
     *        22 STICKY_HONEY 23 COCOA 24 DAMAGE_CAUTIOUS 25 DANGER_TRAPDOOR
     *     而头文件给的是 5=FENCE 6=LAVA 7=WATER 8=RAIL 9=UNPASSABLE ...，且含 1.20.4 里
     *     **根本不存在**的 DAMAGE_CACTUS / DOOR_OPEN_IRON / DAMAGE_WITHER_ROSE / DANGER_WATER，
     *     缺 POWDER_SNOW / WATER_BORDER / DANGER_TRAPDOOR / DAMAGE_CAUTIOUS。
     *     => Java 若按 pnt.ordinal() 填惩罚表、原生按头文件常量索引，**惩罚表整体错位**。
     *     本内核内部坚持用 javap 实证的 ordinal（PT_*），所以这里不做"猜着映射"。
     *
     * (2) CAVA_PF_* 状态位是一组**派生/合成**标志（FENCE_OR_WALL_CLOSED / DANGER / DOOR_IRON），
     *     与 getCommonNodeType 的 19 个 1:1 谓词不是一一对应（例如 WALLS 与 FENCES 分开、
     *     FENCE_GATE 的"开着"没有单独位），映射规则要先定义。
     *
     * 在这两条定下来之前算出来的路径会"看起来正常但与原版不一致" —— 契约明令禁止这种猜测。
     * 所以只回退，并给出精确错误码。内核本身已被 10060 组向量逐位验证（见 notes 第 2 节），
     * 缺的只是 ABI 这一层的语义对齐。*/
    (void) out;
    (void) cap;
    return CAVA_ERR_UNIMPLEMENTED;
}
