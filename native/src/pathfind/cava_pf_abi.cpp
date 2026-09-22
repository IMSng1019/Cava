/* cava_pf_abi.cpp —— P1 寻路的 ABI 入口（cava_abi.h 里已冻结的那几个符号）。
 *
 * 实现范围（诚实版，见 docs/CAVA-p1-pathfind-notes.md 的「ABI 缺口」一节）：
 *   - cava_state_table_upload / cava_region_upload / cava_region_clear /
 *     cava_region_state_id_at：**完整可用**，是 Java 侧镜像的落地接口。
 *   - cava_mob_profile_upload / cava_mob_profile_clear：**已实现**（含非法字段拒绝且不改变已有档案）。
 *   - cava_pathfind：**已接线**，真的求解并写出节点（见函数内注释；两条历史阻塞已解除）。
 *     返回值：>0 节点数 / 0 无路径 / <0 错误码（Java 侧按契约回退原逻辑，绝不崩）。
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

    /* 【历史记录，两条阻塞均已解除 —— 不要按旧结论再拒算】
     * (1) CAVA_PNT_* 与 PathNodeType 枚举 ordinal 曾经不一致：**已修**。
     *     captain 重读 static{} 后把头文件换成实测 ordinal，现在 CAVA_PNT_N 就是内核的 PT_N
     *     （同一套数）。惩罚表按 CAVA_PNT_* 索引 = 按真实 ordinal 索引。
     * (2) 内核的 PF_* 与头文件 CAVA_PF_* 曾经 19/19 全错位：**已修**。
     *     cava_pf.h 的 PF_* 现在是头文件宏的别名（唯一事实来源），
     *     Java 侧 MirrorFlags 与内核 common_node_type 已逐状态对拍（26644 条 0 不一致）。
     * 若将来再出现"输入对不上"，**停下来上报**，不要在这里加回退。*/

    /* 预算：Java 侧（PathfindHook）保证 budget = (int)((float)range * followRange) > 0，
     * 否则它自己就回退了。<=0 在这里没有可靠推导方式 —— 不猜，直接报参数错。*/
    if (req->max_visited_nodes <= 0) return CAVA_ERR_ARG;

    /* 全程持锁求解：区域/状态表的 vector 必须保持存活，且 MC 的世界访问本来就在主线程。
     * （若将来出现真实的并发推送需求，改成 shared_ptr 快照即可。）*/
    WorldView w;
    w.recs = st->recs.data();
    w.rec_count = (int32_t) st->recs.size();
    w.boxes = st->boxes.empty() ? nullptr : st->boxes.data();
    w.box_count = (int32_t) st->boxes.size();
    w.origin_x = st->origin_x;
    w.origin_y = st->origin_y;
    w.origin_z = st->origin_z;
    w.dim_x = st->dim_x;
    w.dim_y = st->dim_y;
    w.dim_z = st->dim_z;
    w.ids = st->ids.data();
    w.min_y = st->min_y;
    w.sea_level = st->sea_level;

    cava::pathfind::SolveParams p;
    p.start_x = st->mob.block_x();          /* 内核从实体位姿推起点，这三个只做记录 */
    p.start_y = st->mob.block_y();
    p.start_z = st->mob.block_z();
    p.target_x = req->tx;
    p.target_y = req->ty;
    p.target_z = req->tz;
    p.node_budget = req->max_visited_nodes;
    p.max_range = req->max_range;
    p.reach_radius = req->reach_range;

    cava::pathfind::SolveResult res;
    if (!cava::pathfind::solve(w, st->mob, p, res)) return CAVA_ERR_ARG;
    if (!res.found || res.nodes.empty()) return 0;      /* 0 = 无路径（合法结果）*/

    const int32_t n = (int32_t) res.nodes.size();
    if (n > cap) return CAVA_ERR_ARG;                   /* 契约：cap 不足**绝不部分写入** */

    for (int32_t i = 0; i < n; ++i) {
        const cava::pathfind::OutNode& s = res.nodes[(size_t) i];
        CavaPathNode& d = out[i];
        d.x = s.x; d.y = s.y; d.z = s.z;
        d.heapIndex = s.heap_index;
        d.g = s.penalized_path_length;   /* Java: node.penalizedPathLength = g */
        d.f = s.heap_weight;             /* Java: node.heapWeight = f */
        d.type = (uint32_t) s.type;
        d.flags = 0;                     /* CAVA_PATH_NODE_* 当前没有定义任何位 */
    }
    return n;
}
