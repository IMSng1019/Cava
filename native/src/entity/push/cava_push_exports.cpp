/* cava_push_exports.cpp -- P2 第 2 核的 C ABI 入口（**尚未进入 cava_abi.h**）。
 *
 * 为什么单独一个文件：内核本身用 C++ 命名空间 + 重载，导出必须 extern "C" + CAVA_EXPORT。
 * 这里只做三件事：参数校验（任一非法 => 错误码、**不写 out**）、调用内核、写 out。
 *
 * ABI 状态（诚实版）：cava_abi.h 由 captain 持有，本轮**未修改**。
 * 正式形态（结构体 + 布局哈希登记）写在 docs/CAVA-push-notes.md 的提案里；
 * 本文件是**提案的参考实现**：只用标量与裸数组，因此不需要 layout 登记，
 * 但代价是参数多、且没有 cava_open 的 fail-closed 保护 —— 这正是提案要求补齐的部分。
 *
 * Java 侧绑定见 src/main/java/cava/push/NativePush.java（**自己 libraryLookup**，
 * 因为 cava/ffm/CavaBindings.java 的 REQUIRED_SYMBOLS 是冻结的、不在本流授权路径）。
 */
#include "cava_push.h"
#include "../../cava_internal.h"   /* CAVA_EXPORT（P0-B 所有，只读不改）*/

using cava::push::Box6;
using cava::push::PushDelta;

extern "C" {

/* Entity.pushAwayFrom 的几何。out 三个分开的标量指针：
 * 结构体 {double,double,int32} 有尾部填充，契约要求"零内部填充"，所以不打包成结构体。*/
CAVA_EXPORT int cava_push_away_from(double this_x, double this_z, double other_x, double other_z,
                                    double* out_dx, double* out_dz, int32_t* out_hit) {
    if (out_dx == nullptr || out_dz == nullptr || out_hit == nullptr) {
        return CAVA_ENTITY_ERR_NULL;   /* -3，与 cava_abi.h 的 CAVA_ERR_NULL 同值 */
    }
    PushDelta d{};
    const int rc = cava::push::push_away_from(this_x, this_z, other_x, other_z, &d);
    if (rc != CAVA_PUSH_OK) {
        return rc;
    }
    *out_dx = d.dx;
    *out_dz = d.dz;
    *out_hit = d.hit;
    return CAVA_PUSH_OK;
}

/* 顺序保持的 AABB 候选过滤。boxes6 是 count*6 个 double（minX,minY,minZ,maxX,maxY,maxZ）。
 * 数组算一个字段：(指针, 元素个数) 成对传入，长度单位在文档里写死。*/
CAVA_EXPORT int cava_push_box_filter(const double* query6, const double* boxes6, int32_t count,
                                     int32_t* out_idx, int32_t out_cap, int32_t* out_count) {
    if (query6 == nullptr || out_count == nullptr) {
        return CAVA_ENTITY_ERR_NULL;
    }
    if (count < 0 || out_cap < 0) {
        return CAVA_ENTITY_ERR_ARG;
    }
    if ((count > 0 && boxes6 == nullptr) || (out_cap > 0 && out_idx == nullptr)) {
        return CAVA_ENTITY_ERR_NULL;
    }
    Box6 q{query6[0], query6[1], query6[2], query6[3], query6[4], query6[5]};
    int written = 0;
    for (int32_t i = 0; i < count; ++i) {
        Box6 b{boxes6[i * 6 + 0], boxes6[i * 6 + 1], boxes6[i * 6 + 2],
               boxes6[i * 6 + 3], boxes6[i * 6 + 4], boxes6[i * 6 + 5]};
        if (!cava::push::box_intersects(q, b)) {
            continue;
        }
        if (written >= out_cap) {
            return CAVA_ENTITY_ERR_ARG;   /* 容量不足 -> 调用方整段回退 */
        }
        out_idx[written++] = i;
    }
    *out_count = written;
    return CAVA_PUSH_OK;
}

/* 区段 broadphase 的访问计划。positions 必须是升序快照（前置条件，见 cava_push.h）。*/
CAVA_EXPORT int cava_push_section_plan(const double* box6, const int64_t* positions, int32_t count,
                                       int64_t* out, int32_t out_cap, int32_t* out_count) {
    if (box6 == nullptr || out_count == nullptr) {
        return CAVA_ENTITY_ERR_NULL;
    }
    if (count < 0 || out_cap < 0) {
        return CAVA_ENTITY_ERR_ARG;
    }
    if ((count > 0 && positions == nullptr) || (out_cap > 0 && out == nullptr)) {
        return CAVA_ENTITY_ERR_NULL;
    }
    Box6 b{box6[0], box6[1], box6[2], box6[3], box6[4], box6[5]};
    const int n = cava::push::section_plan(b, positions, count, out, out_cap);
    if (n < 0) {
        return n;
    }
    *out_count = n;
    return CAVA_PUSH_OK;
}

} /* extern "C" */
