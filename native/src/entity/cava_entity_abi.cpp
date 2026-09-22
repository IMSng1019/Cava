/* cava_entity_abi.cpp -- P2 实体位移的 ABI 入口（cava_abi.h 里已冻结的那两个符号）。
 *
 * 接线范围（诚实版）：
 *   - cava_shape_table_upload ：**完整可用**。按 state id 常驻 (点表 + 体素位图)。
 *   - cava_resolve_move       ：**已接线**，真的求解并写出位移与事件。
 *
 * 与 P1 的关系：**状态分开**。native/src/pathfind/cava_pf_abi.cpp 的 HandleState 存的是
 * 扁平 AABB（寻路用），本文件存的是 (点表 + 体素位图)（碰撞求解用），是两张不同的表。
 * 两者只共用句柄的**形状校验**，不共用任何容器。
 *
 * 入口纪律（cava_abi.h 第 7 条 + 契约 2.1.8）：
 *   任一非法输入 => 对应错误码，**不写 out、不写 events、不产生任何副作用**。
 *   所以校验全部发生在任何写入之前；内核只在最后写 out。
 *
 * event_overflow 的语义（**本文件定，并写进 docs/CAVA-p2-wiring-notes.md**）：
 *   事件数组不足时内核停止记录（绝不越界），把 event_overflow 置 1，位移结果**仍然完整正确**。
 *   本入口此时**返回 CAVA_OK**，并在 out 里同时给出 event_overflow=1 与完整 delta_*。
 *   理由：位移是可信的（诊断/影子比对要用它），而"事件不全"这件事由 overflow 位表达，
 *   不需要与"参数非法"共用一个错误码。**Java 侧看到 event_overflow==1 必须回退纯 Java**
 *   （事件回放不全 = 少调虚方法 = 行为改变）。
 *
 * 本轮实测踩到的坑：形状记录必须**逐条自洽**（bit_words == ceil(sizeX*sizeY*sizeZ/64)、
 *   EXPLICIT 的点表长度 == sizeX+sizeY+sizeZ+3），否则内核会越界读 points[]/bits[]。
 *   上传时逐条校验，不合法整表拒绝（与 P1 的"cap 不足不改变已有表"同款）。
 */
#include "cava_entity.h"
#include "../cava_internal.h"   /* CAVA_EXPORT（P0-B 所有，只读不改）*/

#include <mutex>
#include <unordered_map>
#include <vector>

namespace {

using cava::entity::MoveEvent;
using cava::entity::MoveRequest;
using cava::entity::MoveResult;
using cava::entity::ShapeListView;
using cava::entity::ShapeView;

constexpr int32_t kMaxHandleSlots   = 256;
constexpr int32_t kMaxShapeRecords  = 1 << 20;   /* 状态数上界（真实 ~27k）*/
constexpr int32_t kMaxPointDoubles  = 1 << 25;   /* 32M double */
constexpr int32_t kMaxBitWords      = 1 << 25;   /* 32M uint64 */
constexpr int32_t kMaxRefs          = 1 << 20;
constexpr int32_t kMaxInlineShapes  = 1 << 16;
constexpr int32_t kMaxEvents        = 1 << 22;
constexpr int32_t kMaxAxisSize      = 1 << 16;   /* 单轴体素数上界；保证乘积不溢出 int64 */

/* ------------------------------------------------------------------ */
/* CavaMoveEvent（ABI，冻结）与 cava::entity::MoveEvent（内核）必须**逐字段同布局** —— */
/* 内核直接往 ABI 的缓冲区里写事件，不复制。任何一侧改字段顺序都会在这里编译失败。 */
/* ------------------------------------------------------------------ */
static_assert(sizeof(CavaMoveEvent) == sizeof(MoveEvent), "CavaMoveEvent / MoveEvent 大小不一致");
static_assert(offsetof(CavaMoveEvent, source)          == offsetof(MoveEvent, source),          "source");
static_assert(offsetof(CavaMoveEvent, axis)            == offsetof(MoveEvent, axis),            "axis");
static_assert(offsetof(CavaMoveEvent, block_x)         == offsetof(MoveEvent, block_x),         "block_x");
static_assert(offsetof(CavaMoveEvent, block_y)         == offsetof(MoveEvent, block_y),         "block_y");
static_assert(offsetof(CavaMoveEvent, block_z)         == offsetof(MoveEvent, block_z),         "block_z");
static_assert(offsetof(CavaMoveEvent, pass)            == offsetof(MoveEvent, pass),            "pass");
static_assert(offsetof(CavaMoveEvent, accepted)        == offsetof(MoveEvent, accepted),        "accepted");
static_assert(offsetof(CavaMoveEvent, cell_x)          == offsetof(MoveEvent, cell_x),          "cell_x");
static_assert(offsetof(CavaMoveEvent, cell_y)          == offsetof(MoveEvent, cell_y),          "cell_y");
static_assert(offsetof(CavaMoveEvent, cell_z)          == offsetof(MoveEvent, cell_z),          "cell_z");
static_assert(offsetof(CavaMoveEvent, shape_token)     == offsetof(MoveEvent, shape_token),     "shape_token");
static_assert(offsetof(CavaMoveEvent, offset)          == offsetof(MoveEvent, offset),          "offset");
static_assert(offsetof(CavaMoveEvent, max_dist_before) == offsetof(MoveEvent, max_dist_before), "max_dist_before");
static_assert(offsetof(CavaMoveEvent, max_dist_after)  == offsetof(MoveEvent, max_dist_after),  "max_dist_after");
/* 事件结构体内部零填充：48 字节的 4 字节字段 + int64 + 3 double = 80。*/
static_assert(offsetof(CavaMoveEvent, shape_token) == 48, "shape_token 必须在 48 字节偏移");
static_assert(sizeof(CavaMoveEvent) == 80, "CavaMoveEvent 必须是 80 字节");

/* 与 P1 完全同款的句柄形状校验（句柄编码 = (generation << 32) | (slot + 1)）。*/
bool handle_shape_ok(int64_t handle) {
    const uint64_t u = (uint64_t) handle;
    const uint32_t lo = (uint32_t) (u & 0xFFFFFFFFull);
    return lo != 0 && lo <= (uint32_t) kMaxHandleSlots;
}

/* P2 自己的状态：**与 P1 的 HandleState 分开**（见文件头）。*/
struct ShapeTableState {
    std::vector<CavaShapeRecord> recs;
    std::vector<double>          points;
    std::vector<uint64_t>        bits;
};

std::mutex g_mutex;
std::unordered_map<int64_t, ShapeTableState> g_tables;

/* 记录必须自洽，否则内核会越界读。返回 true 表示可以安全交给内核。*/
bool record_ok(const CavaShapeRecord& r, int32_t point_count, int32_t bit_word_count,
               int32_t* out_point_span) {
    if (r.points_kind > CAVA_SHAPE_POINTS_EXPLICIT) return false;
    if (r.reserved0 != 0) return false;
    if (r.size_x < 0 || r.size_y < 0 || r.size_z < 0) return false;
    if (r.size_x > kMaxAxisSize || r.size_y > kMaxAxisSize || r.size_z > kMaxAxisSize) return false;

    const int64_t n = (int64_t) r.size_x * (int64_t) r.size_y * (int64_t) r.size_z;
    const int64_t want_words = (n + 63) / 64;
    if ((int64_t) r.bit_words != want_words) return false;              /* isEmpty() 语义 */
    if ((int64_t) r.bit_offset + want_words > (int64_t) bit_word_count) return false;

    int64_t span = 0;
    if (r.points_kind == CAVA_SHAPE_POINTS_EXPLICIT) {
        /* 每轴 size+1 个点，X 段、Y 段、Z 段依次拼接。*/
        span = (int64_t) r.size_x + 1 + (int64_t) r.size_y + 1 + (int64_t) r.size_z + 1;
        if ((int64_t) r.point_offset + span > (int64_t) point_count) return false;
    }
    if (out_point_span != nullptr) *out_point_span = (int32_t) span;
    return true;
}

/* 由记录 + 平移量合成 EXPLICIT 点表：offset() 的 OffsetDoubleList 语义
 *   getDouble(i) = (double)block + base(i)
 * FRACTIONAL 的 base(i) = (double)i / (double)size（SimpleVoxelShape 的分母路径）。
 * 必须保持"先除法再加"这一次运算顺序，不许换成乘法。*/
void fill_translated(const CavaShapeRecord& r, const double* base_points,
                     int32_t block_x, int32_t block_y, int32_t block_z, double* out) {
    const int32_t size[3] = { r.size_x, r.size_y, r.size_z };
    const int32_t blk[3] = { block_x, block_y, block_z };
    int32_t at = 0;
    for (int32_t a = 0; a < 3; ++a) {
        for (int32_t i = 0; i <= size[a]; ++i) {
            const double base = (r.points_kind == CAVA_SHAPE_POINTS_FRACTIONAL)
                                    ? ((double) i / (double) size[a])
                                    : base_points[at];
            out[at] = (double) blk[a] + base;
            ++at;
        }
    }
}

} /* namespace */

extern "C" CAVA_EXPORT int32_t cava_shape_table_upload(int64_t handle,
                                                       const CavaShapeRecord* records, int32_t record_count,
                                                       const double* points, int32_t point_count,
                                                       const uint64_t* bits, int32_t bit_word_count) {
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    if (record_count < 0 || point_count < 0 || bit_word_count < 0) return CAVA_ERR_ARG;
    if (record_count > kMaxShapeRecords || point_count > kMaxPointDoubles || bit_word_count > kMaxBitWords) {
        return CAVA_ERR_ARG;
    }
    if (record_count > 0 && records == nullptr) return CAVA_ERR_ARG;
    if (point_count > 0 && points == nullptr) return CAVA_ERR_ARG;
    if (bit_word_count > 0 && bits == nullptr) return CAVA_ERR_ARG;

    /* 逐条自洽校验：任何一条不合法 => 整表拒绝，**不改变已有表**。*/
    for (int32_t i = 0; i < record_count; ++i) {
        int32_t span = 0;
        if (!record_ok(records[i], point_count, bit_word_count, &span)) return CAVA_ERR_ARG;
    }

    ShapeTableState next;
    try {
        next.recs.assign(records, records + record_count);
        if (point_count > 0) next.points.assign(points, points + point_count);
        if (bit_word_count > 0) next.bits.assign(bits, bits + bit_word_count);
    } catch (...) {
        return CAVA_ERR_OOM;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    g_tables[handle] = std::move(next);
    return CAVA_OK;
}

extern "C" CAVA_EXPORT int32_t cava_resolve_move(int64_t handle,
                                                 const CavaMoveRequest* req,
                                                 const CavaMoveShapeRef* refs, int32_t ref_count,
                                                 const CavaShapeRecord* inline_shapes, int32_t inline_shape_count,
                                                 const double* inline_points, int32_t inline_point_count,
                                                 const uint64_t* inline_bits, int32_t inline_bit_word_count,
                                                 CavaMoveEvent* events, int32_t event_cap,
                                                 CavaMoveResult* out) {
    /* ---------- 1. 全部校验（在任何写入之前） ---------- */
    if (req == nullptr || out == nullptr) return CAVA_ERR_NULL;
    if (!handle_shape_ok(handle)) return CAVA_ERR_NULL;
    if (ref_count < 0 || inline_shape_count < 0 || inline_point_count < 0
        || inline_bit_word_count < 0 || event_cap < 0) {
        return CAVA_ERR_ARG;
    }
    if (ref_count > kMaxRefs || inline_shape_count > kMaxInlineShapes || event_cap > kMaxEvents) {
        return CAVA_ERR_ARG;
    }
    if (ref_count > 0 && refs == nullptr) return CAVA_ERR_ARG;
    if (inline_shape_count > 0 && inline_shapes == nullptr) return CAVA_ERR_ARG;
    if (inline_point_count > 0 && inline_points == nullptr) return CAVA_ERR_ARG;
    if (inline_bit_word_count > 0 && inline_bits == nullptr) return CAVA_ERR_ARG;
    if (event_cap > 0 && events == nullptr) return CAVA_ERR_NULL;

    if (req->flags != 0) return CAVA_ERR_ARG;          /* 契约：保留位必须为 0 */
    if (req->reserved0 != 0 || req->reserved1 != 0) return CAVA_ERR_ARG;
    if (req->on_ground != 0 && req->on_ground != 1) return CAVA_ERR_ARG;
    /* refs 与 ref_count 同源：请求里声明的 shape_count 必须与实参一致。*/
    if (req->shape_count != ref_count) return CAVA_ERR_ARG;

    /* inline 记录逐条自洽校验。*/
    for (int32_t i = 0; i < inline_shape_count; ++i) {
        if (!record_ok(inline_shapes[i], inline_point_count, inline_bit_word_count, nullptr)) {
            return CAVA_ERR_ARG;
        }
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    ShapeTableState* st = nullptr;
    {
        auto it = g_tables.find(handle);
        if (it != g_tables.end()) st = &it->second;
    }
    const int32_t record_count = (st == nullptr) ? 0 : (int32_t) st->recs.size();

    /* 每个 ref 的引用必须落在有效范围内（契约点名的两条）。*/
    for (int32_t i = 0; i < ref_count; ++i) {
        const CavaMoveShapeRef& r = refs[i];
        if (r.kind > CAVA_MSHAPE_INLINE) return CAVA_ERR_ARG;
        if (r.source > CAVA_ESHAPE_SRC_OTHER) return CAVA_ERR_ARG;
        if (r.reserved0 != 0 || r.reserved1 != 0 || r.reserved2 != 0) return CAVA_ERR_ARG;
        if (r.kind == CAVA_MSHAPE_STATE) {
            if (r.state_id >= (uint32_t) record_count) return CAVA_ERR_ARG;
        } else {
            if (r.inline_slot >= (uint32_t) inline_shape_count) return CAVA_ERR_ARG;
        }
    }

    /* ---------- 2. 组装内核视图 ---------- */
    const CavaShapeRecord* srecs = (st == nullptr) ? nullptr : st->recs.data();
    const double*          spts  = (st == nullptr || st->points.empty()) ? nullptr : st->points.data();
    const uint64_t*        sbits = (st == nullptr || st->bits.empty()) ? nullptr : st->bits.data();

    int64_t scratch_needed = 0;
    for (int32_t i = 0; i < ref_count; ++i) {
        const CavaMoveShapeRef& r = refs[i];
        if (r.kind != CAVA_MSHAPE_STATE) continue;
        const CavaShapeRecord& sr = srecs[r.state_id];
        /* 平移后一律按 EXPLICIT 合成点表（offset() 返回的永远是 ArrayVoxelShape）。*/
        scratch_needed += (int64_t) sr.size_x + 1 + (int64_t) sr.size_y + 1 + (int64_t) sr.size_z + 1;
    }

    /* 本次调用的视图与合成点表。只有存在 STATE ref 时才有实际分配。*/
    std::vector<ShapeView> local_views;
    std::vector<double>    local_scratch;
    try {
        local_views.resize((size_t) (ref_count > 0 ? ref_count : 0));
        if (scratch_needed > 0) local_scratch.resize((size_t) scratch_needed);
    } catch (...) {
        return CAVA_ERR_OOM;
    }

    int64_t scratch_at = 0;
    for (int32_t i = 0; i < ref_count; ++i) {
        const CavaMoveShapeRef& r = refs[i];
        ShapeView& v = local_views[(size_t) i];
        v.points_kind = CAVA_SHAPE_POINTS_FRACTIONAL;
        v.source = (int32_t) r.source;
        v.size[0] = v.size[1] = v.size[2] = 0;
        v.points[0] = v.points[1] = v.points[2] = nullptr;
        v.bits = nullptr;
        v.shape_token = r.shape_token;
        v.block_x = r.block_x; v.block_y = r.block_y; v.block_z = r.block_z;

        if (r.kind == CAVA_MSHAPE_INLINE) {
            const CavaShapeRecord& sr = inline_shapes[r.inline_slot];
            v.points_kind = (int32_t) sr.points_kind;
            v.size[0] = sr.size_x; v.size[1] = sr.size_y; v.size[2] = sr.size_z;
            if (sr.points_kind == CAVA_SHAPE_POINTS_EXPLICIT) {
                for (int32_t a = 0; a < 3; ++a) {
                    v.points[a] = inline_points + sr.point_offset
                                  + ((a == 0) ? 0 : (a == 1 ? (sr.size_x + 1) : (sr.size_x + 1 + sr.size_y + 1)));
                }
            }
            v.bits = (sr.bit_words == 0) ? nullptr : (inline_bits + sr.bit_offset);
            continue;
        }

        const CavaShapeRecord& sr = srecs[r.state_id];
        v.size[0] = sr.size_x; v.size[1] = sr.size_y; v.size[2] = sr.size_z;
        v.bits = (sr.bit_words == 0) ? nullptr : (sbits + sr.bit_offset);
        /* STATE：按 offset() 语义逐点加方块坐标，结果视为 EXPLICIT。
         * 原版 getBlockCollisions 发出的形状 100% 是 ArrayVoxelShape（oracle spec 3.8），
         * 所以这里永远是 EXPLICIT —— 与 Java 侧看到的一致。*/
        double* dst = local_scratch.data() + scratch_at;
        const double* base = (sr.points_kind == CAVA_SHAPE_POINTS_EXPLICIT) ? (spts + sr.point_offset) : nullptr;
        fill_translated(sr, base, r.block_x, r.block_y, r.block_z, dst);
        v.points_kind = CAVA_SHAPE_POINTS_EXPLICIT;
        v.points[0] = dst;
        v.points[1] = dst + (sr.size_x + 1);
        v.points[2] = dst + (sr.size_x + 1) + (sr.size_y + 1);
        scratch_at += (int64_t) sr.size_x + 1 + (int64_t) sr.size_y + 1 + (int64_t) sr.size_z + 1;
    }

    MoveRequest kreq;
    kreq.shapes.items = local_views.empty() ? nullptr : local_views.data();
    kreq.shapes.count = ref_count;
    kreq.box.min_x = req->min_x; kreq.box.min_y = req->min_y; kreq.box.min_z = req->min_z;
    kreq.box.max_x = req->max_x; kreq.box.max_y = req->max_y; kreq.box.max_z = req->max_z;
    kreq.movement.x = req->move_x; kreq.movement.y = req->move_y; kreq.movement.z = req->move_z;
    kreq.step_height = req->step_height;
    kreq.on_ground = (int32_t) req->on_ground;

    MoveResult kout;
    kout.delta.x = kout.delta.y = kout.delta.z = 0.0;
    kout.base_delta.x = kout.base_delta.y = kout.base_delta.z = 0.0;
    kout.step_candidate.x = kout.step_candidate.y = kout.step_candidate.z = 0.0;
    kout.event_count = 0; kout.event_overflow = 0; kout.step_used = 0;

    /* ---------- 3. 求解（内核是唯一的写入者） ---------- */
    /* 布局已由上面的 static_assert 钉死，这里只做类型桥接（不复制、不转换）。*/
    const int32_t rc = cava::entity::resolve_movement(
            kreq, reinterpret_cast<MoveEvent*>(events), event_cap, &kout);
    if (rc < 0) return rc;

    out->status         = CAVA_OK;
    out->step_used      = kout.step_used;
    out->event_count    = kout.event_count;
    out->event_overflow = kout.event_overflow;
    out->delta_x = kout.delta.x;        out->delta_y = kout.delta.y;        out->delta_z = kout.delta.z;
    out->base_x  = kout.base_delta.x;   out->base_y  = kout.base_delta.y;   out->base_z  = kout.base_delta.z;
    out->step_x  = kout.step_candidate.x; out->step_y = kout.step_candidate.y; out->step_z = kout.step_candidate.z;

    /* event_overflow==1 时**仍返回 CAVA_OK**（见文件头）：位移可信、事件不全由 overflow 位表达。*/
    return CAVA_OK;
}
