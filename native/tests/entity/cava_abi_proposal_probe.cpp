/* cava_abi_proposal_probe.cpp -- P2 实体碰撞 ABI 提案的【布局实测探针】。
 *
 * 目的（P1 的教训：手算 offset 错过 3 次，必须让编译器说话）：
 *   1. 用【权威头文件 cava_abi.h 里已登记的 9 个结构体】校验本文件的 layout_hash 实现
 *      （期望值 = cava_abi.h 注释里的实测值；对不上说明公式实现错了，新结构体的哈希也不可信）。
 *   2. 打印 5 个【提案结构体】的逐字段 offsetof/sizeof/align 与 layout_hash。
 *   3. 打印 '如果这 5 个结构体按提案登记进去' 的 layout_hash_sum 投影值。
 *
 * 本文件【只读】cava_abi.h（GLOB 只收 native/src 下的 .cpp，所以本文件不会进生产 DLL）。
 */
#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include "cava_abi.h"

/* ---- 冻结公式（cava_abi.h 第 54-64 行）的唯一实现 ---- */
static uint32_t g_hash = 0;
static void h_feed(uint64_t off, uint64_t size) {
    g_hash ^= (uint32_t)(off & 0xFFFFFFFFu);  g_hash *= CAVA_LAYOUT_FNV_PRIME;
    g_hash ^= (uint32_t)(size & 0xFFFFFFFFu); g_hash *= CAVA_LAYOUT_FNV_PRIME;
    g_hash ^= (uint32_t)((off >> 32) & 0xFFFFFFFFu);  g_hash *= CAVA_LAYOUT_FNV_PRIME;
    g_hash ^= (uint32_t)((size >> 32) & 0xFFFFFFFFu); g_hash *= CAVA_LAYOUT_FNV_PRIME;
}
#define FIELD(T, name)          h_feed((uint64_t)offsetof(T, name), (uint64_t)sizeof(((T*)0)->name))
#define FIELD_ARRAY(T, name, n) h_feed((uint64_t)offsetof(T, name), (uint64_t)sizeof(((T*)0)->name[0]) * (uint64_t)(n))
#define HASH_BEGIN() do { g_hash = CAVA_LAYOUT_FNV_OFFSET; } while (0)
#define HASH_END()   (g_hash)

static int g_fails = 0;
static void expect_hash(const char* name, uint32_t got, uint32_t want) {
    printf("  %-20s got=0x%08X want=0x%08X  %s\n", name, got, want, got == want ? "OK" : "MISMATCH");
    if (got != want) ++g_fails;
}

/* ================================================================ */
/* 第一步：用真实头文件里的 9 个结构体验证哈希实现                     */
/* ================================================================ */
static uint32_t hash_CavaLayoutEntry(void) {
    HASH_BEGIN();
    FIELD(CavaLayoutEntry, abi_version);
    FIELD(CavaLayoutEntry, reserved0);
    FIELD(CavaLayoutEntry, struct_size);
    FIELD(CavaLayoutEntry, struct_align);
    FIELD(CavaLayoutEntry, field_count);
    FIELD(CavaLayoutEntry, layout_hash);
    FIELD_ARRAY(CavaLayoutEntry, field_offsets, CAVA_LAYOUT_MAX_FIELDS);
    FIELD_ARRAY(CavaLayoutEntry, field_sizes, CAVA_LAYOUT_MAX_FIELDS);
    return HASH_END();
}
static uint32_t hash_CavaLayoutReport(void) {
    HASH_BEGIN();
    FIELD(CavaLayoutReport, abi_version);
    FIELD(CavaLayoutReport, build_flags);
    FIELD(CavaLayoutReport, platform);
    FIELD(CavaLayoutReport, pointer_size);
    FIELD(CavaLayoutReport, entry_count);
    FIELD(CavaLayoutReport, reserved0);
    FIELD(CavaLayoutReport, build_id_hash);
    FIELD_ARRAY(CavaLayoutReport, entries, CAVA_LAYOUT_REPORT_CAP);
    return HASH_END();
}
static uint32_t hash_CavaOpenParams(void) {
    HASH_BEGIN();
    FIELD(CavaOpenParams, abi_version);
    FIELD(CavaOpenParams, flags);
    FIELD(CavaOpenParams, layout_hash_sum);
    FIELD(CavaOpenParams, reserved0);
    FIELD(CavaOpenParams, reserved1);
    return HASH_END();
}
static uint32_t hash_CavaOpenResult(void) {
    HASH_BEGIN();
    FIELD(CavaOpenResult, status);
    FIELD(CavaOpenResult, abi_version);
    FIELD(CavaOpenResult, native_layout_sum);
    FIELD(CavaOpenResult, reserved0);
    return HASH_END();
}
static uint32_t hash_CavaPathRequest(void) {
    HASH_BEGIN();
    FIELD(CavaPathRequest, reserved1);
    FIELD(CavaPathRequest, tx); FIELD(CavaPathRequest, ty); FIELD(CavaPathRequest, tz);
    FIELD(CavaPathRequest, reach_range);
    FIELD(CavaPathRequest, max_range);
    FIELD(CavaPathRequest, flags);
    FIELD(CavaPathRequest, reserved0);
    FIELD(CavaPathRequest, reserved2);
    FIELD(CavaPathRequest, max_visited_nodes);
    FIELD(CavaPathRequest, pad0); FIELD(CavaPathRequest, pad1); FIELD(CavaPathRequest, pad2);
    return HASH_END();
}
static uint32_t hash_CavaPathNode(void) {
    HASH_BEGIN();
    FIELD(CavaPathNode, x); FIELD(CavaPathNode, y); FIELD(CavaPathNode, z);
    FIELD(CavaPathNode, heapIndex);
    FIELD(CavaPathNode, g); FIELD(CavaPathNode, f);
    FIELD(CavaPathNode, type); FIELD(CavaPathNode, flags);
    return HASH_END();
}
static uint32_t hash_CavaMobProfile(void) {
    HASH_BEGIN();
    FIELD_ARRAY(CavaMobProfile, penalty, CAVA_PNT_COUNT);
    FIELD(CavaMobProfile, reserved_max_fall_distance);
    FIELD(CavaMobProfile, start_x); FIELD(CavaMobProfile, start_y); FIELD(CavaMobProfile, start_z);
    FIELD(CavaMobProfile, start_block_x); FIELD(CavaMobProfile, start_block_y); FIELD(CavaMobProfile, start_block_z);
    FIELD(CavaMobProfile, width); FIELD(CavaMobProfile, height); FIELD(CavaMobProfile, step_height);
    FIELD(CavaMobProfile, safe_fall_distance); FIELD(CavaMobProfile, min_y); FIELD(CavaMobProfile, sea_level);
    FIELD(CavaMobProfile, caps); FIELD(CavaMobProfile, penalty_mask);
    FIELD(CavaMobProfile, reserved0); FIELD(CavaMobProfile, reserved1);
    return HASH_END();
}
static uint32_t hash_CavaStateRecord(void) {
    HASH_BEGIN();
    FIELD(CavaStateRecord, flags); FIELD(CavaStateRecord, box_offset);
    FIELD(CavaStateRecord, box_count); FIELD(CavaStateRecord, path_type_idx);
    FIELD(CavaStateRecord, malus);
    return HASH_END();
}
static uint32_t hash_CavaCollisionBox(void) {
    HASH_BEGIN();
    FIELD(CavaCollisionBox, min_x); FIELD(CavaCollisionBox, min_y); FIELD(CavaCollisionBox, min_z);
    FIELD(CavaCollisionBox, max_x); FIELD(CavaCollisionBox, max_y); FIELD(CavaCollisionBox, max_z);
    return HASH_END();
}

/* ================================================================ */
/* 第二步：5 个提案结构体（【只在本文件里定义，绝不改 cava_abi.h】）    */
/* ================================================================ */

#define CAVA_SHAPE_POINTS_FRACTIONAL 0u
#define CAVA_SHAPE_POINTS_EXPLICIT   1u

#define CAVA_ESHAPE_SRC_ENTITY       0
#define CAVA_ESHAPE_SRC_WORLD_BORDER 1
#define CAVA_ESHAPE_SRC_BLOCK        2
#define CAVA_ESHAPE_SRC_OTHER        3

#define CAVA_MSHAPE_STATE  0u
#define CAVA_MSHAPE_INLINE 1u

typedef struct CavaShapeRecord {
    uint32_t points_kind;
    uint32_t point_offset;
    uint32_t bit_offset;
    uint32_t bit_words;
    int32_t  size_x, size_y, size_z;
    uint32_t reserved0;
} CavaShapeRecord;

typedef struct CavaMoveShapeRef {
    int64_t  shape_token;
    uint32_t kind;
    uint32_t state_id;
    int32_t  block_x, block_y, block_z;
    uint32_t source;
    uint32_t inline_slot;
    int32_t  reserved0;
    int32_t  reserved1;
    int32_t  reserved2;
} CavaMoveShapeRef;

typedef struct CavaMoveRequest {
    int64_t  reserved0;
    double   min_x, min_y, min_z;
    double   max_x, max_y, max_z;
    double   move_x, move_y, move_z;
    double   step_height;
    uint32_t flags;
    uint32_t on_ground;
    int32_t  shape_count;
    int32_t  reserved1;
} CavaMoveRequest;

typedef struct CavaMoveEvent {
    int32_t  source;
    int32_t  axis;
    int32_t  block_x, block_y, block_z;
    int32_t  pass;
    int32_t  accepted;
    int32_t  cell_x, cell_y, cell_z;
    int32_t  reserved0, reserved1;
    int64_t  shape_token;
    double   offset;
    double   max_dist_before;
    double   max_dist_after;
} CavaMoveEvent;

typedef struct CavaMoveResult {
    int32_t  status;
    int32_t  step_used;
    int32_t  event_count;
    int32_t  event_overflow;
    double   delta_x, delta_y, delta_z;
    double   base_x, base_y, base_z;
    double   step_x, step_y, step_z;
} CavaMoveResult;

static uint32_t hash_CavaShapeRecord(void) {
    HASH_BEGIN();
    FIELD(CavaShapeRecord, points_kind); FIELD(CavaShapeRecord, point_offset);
    FIELD(CavaShapeRecord, bit_offset);   FIELD(CavaShapeRecord, bit_words);
    FIELD(CavaShapeRecord, size_x); FIELD(CavaShapeRecord, size_y); FIELD(CavaShapeRecord, size_z);
    FIELD(CavaShapeRecord, reserved0);
    return HASH_END();
}
static uint32_t hash_CavaMoveShapeRef(void) {
    HASH_BEGIN();
    FIELD(CavaMoveShapeRef, shape_token);
    FIELD(CavaMoveShapeRef, kind); FIELD(CavaMoveShapeRef, state_id);
    FIELD(CavaMoveShapeRef, block_x); FIELD(CavaMoveShapeRef, block_y); FIELD(CavaMoveShapeRef, block_z);
    FIELD(CavaMoveShapeRef, source); FIELD(CavaMoveShapeRef, inline_slot);
    FIELD(CavaMoveShapeRef, reserved0); FIELD(CavaMoveShapeRef, reserved1); FIELD(CavaMoveShapeRef, reserved2);
    return HASH_END();
}
static uint32_t hash_CavaMoveRequest(void) {
    HASH_BEGIN();
    FIELD(CavaMoveRequest, reserved0);
    FIELD(CavaMoveRequest, min_x); FIELD(CavaMoveRequest, min_y); FIELD(CavaMoveRequest, min_z);
    FIELD(CavaMoveRequest, max_x); FIELD(CavaMoveRequest, max_y); FIELD(CavaMoveRequest, max_z);
    FIELD(CavaMoveRequest, move_x); FIELD(CavaMoveRequest, move_y); FIELD(CavaMoveRequest, move_z);
    FIELD(CavaMoveRequest, step_height);
    FIELD(CavaMoveRequest, flags); FIELD(CavaMoveRequest, on_ground);
    FIELD(CavaMoveRequest, shape_count); FIELD(CavaMoveRequest, reserved1);
    return HASH_END();
}
static uint32_t hash_CavaMoveEvent(void) {
    HASH_BEGIN();
    FIELD(CavaMoveEvent, source); FIELD(CavaMoveEvent, axis);
    FIELD(CavaMoveEvent, block_x); FIELD(CavaMoveEvent, block_y); FIELD(CavaMoveEvent, block_z);
    FIELD(CavaMoveEvent, pass); FIELD(CavaMoveEvent, accepted);
    FIELD(CavaMoveEvent, cell_x); FIELD(CavaMoveEvent, cell_y); FIELD(CavaMoveEvent, cell_z);
    FIELD(CavaMoveEvent, reserved0); FIELD(CavaMoveEvent, reserved1);
    FIELD(CavaMoveEvent, shape_token);
    FIELD(CavaMoveEvent, offset); FIELD(CavaMoveEvent, max_dist_before); FIELD(CavaMoveEvent, max_dist_after);
    return HASH_END();
}
static uint32_t hash_CavaMoveResult(void) {
    HASH_BEGIN();
    FIELD(CavaMoveResult, status); FIELD(CavaMoveResult, step_used);
    FIELD(CavaMoveResult, event_count); FIELD(CavaMoveResult, event_overflow);
    FIELD(CavaMoveResult, delta_x); FIELD(CavaMoveResult, delta_y); FIELD(CavaMoveResult, delta_z);
    FIELD(CavaMoveResult, base_x); FIELD(CavaMoveResult, base_y); FIELD(CavaMoveResult, base_z);
    FIELD(CavaMoveResult, step_x); FIELD(CavaMoveResult, step_y); FIELD(CavaMoveResult, step_z);
    return HASH_END();
}

#define SHOW(T) printf("  %-18s size=%3llu align=%llu\n", #T, (unsigned long long)sizeof(T), (unsigned long long)alignof(T))
#define SHOWF(T, f) printf("      %-22s off=%3llu size=%llu\n", #f, (unsigned long long)offsetof(T,f), (unsigned long long)sizeof(((T*)0)->f))

int main(void) {
    printf("=== Cava P2 ABI proposal layout probe ===\n");
    printf("-- step 1: layout_hash 实现对拍（期望值来自 cava_abi.h 注释里的实测值）--\n");
    uint32_t h[9];
    h[0] = hash_CavaLayoutEntry();    expect_hash("CavaLayoutEntry",    h[0], 0xF837804Du);
    h[1] = hash_CavaLayoutReport();   expect_hash("CavaLayoutReport",   h[1], 0xE9FFC021u);
    h[2] = hash_CavaOpenParams();     expect_hash("CavaOpenParams",     h[2], 0x7FDE7499u);
    h[3] = hash_CavaOpenResult();     expect_hash("CavaOpenResult",     h[3], 0xFF344829u);
    h[4] = hash_CavaPathRequest();    expect_hash("CavaPathRequest",    h[4], 0xE566F98Du);
    h[5] = hash_CavaPathNode();       expect_hash("CavaPathNode",       h[5], 0x0DCFFE65u);
    h[6] = hash_CavaMobProfile();     expect_hash("CavaMobProfile",     h[6], 0x9C6C98CDu);
    h[7] = hash_CavaStateRecord();    expect_hash("CavaStateRecord",    h[7], 0x53797229u);
    h[8] = hash_CavaCollisionBox();   expect_hash("CavaCollisionBox",   h[8], 0x250ECBE1u);
    uint32_t base_sum = 0;
    for (int i = 0; i < 9; ++i) base_sum += h[i];
    printf("  9 结构体和 = 0x%08X（权威 layout_hash_sum = 0x6975CBF9）\n", base_sum);
    if (base_sum != 0x6975CBF9u) ++g_fails;

    printf("-- step 2: 提案结构体（尚未登记，只在本探针里定义）--\n");
    SHOW(CavaShapeRecord);
    SHOWF(CavaShapeRecord, points_kind); SHOWF(CavaShapeRecord, point_offset);
    SHOWF(CavaShapeRecord, bit_offset);  SHOWF(CavaShapeRecord, bit_words);
    SHOWF(CavaShapeRecord, size_x); SHOWF(CavaShapeRecord, size_y); SHOWF(CavaShapeRecord, size_z);
    SHOWF(CavaShapeRecord, reserved0);
    SHOW(CavaMoveShapeRef);
    SHOWF(CavaMoveShapeRef, shape_token); SHOWF(CavaMoveShapeRef, kind); SHOWF(CavaMoveShapeRef, state_id);
    SHOWF(CavaMoveShapeRef, block_x); SHOWF(CavaMoveShapeRef, block_y); SHOWF(CavaMoveShapeRef, block_z);
    SHOWF(CavaMoveShapeRef, source); SHOWF(CavaMoveShapeRef, inline_slot);
    SHOWF(CavaMoveShapeRef, reserved0); SHOWF(CavaMoveShapeRef, reserved1); SHOWF(CavaMoveShapeRef, reserved2);
    SHOW(CavaMoveRequest);
    SHOWF(CavaMoveRequest, reserved0);
    SHOWF(CavaMoveRequest, min_x); SHOWF(CavaMoveRequest, min_y); SHOWF(CavaMoveRequest, min_z);
    SHOWF(CavaMoveRequest, max_x); SHOWF(CavaMoveRequest, max_y); SHOWF(CavaMoveRequest, max_z);
    SHOWF(CavaMoveRequest, move_x); SHOWF(CavaMoveRequest, move_y); SHOWF(CavaMoveRequest, move_z);
    SHOWF(CavaMoveRequest, step_height);
    SHOWF(CavaMoveRequest, flags); SHOWF(CavaMoveRequest, on_ground);
    SHOWF(CavaMoveRequest, shape_count); SHOWF(CavaMoveRequest, reserved1);
    SHOW(CavaMoveEvent);
    SHOWF(CavaMoveEvent, source); SHOWF(CavaMoveEvent, axis);
    SHOWF(CavaMoveEvent, block_x); SHOWF(CavaMoveEvent, block_y); SHOWF(CavaMoveEvent, block_z);
    SHOWF(CavaMoveEvent, pass); SHOWF(CavaMoveEvent, accepted);
    SHOWF(CavaMoveEvent, cell_x); SHOWF(CavaMoveEvent, cell_y); SHOWF(CavaMoveEvent, cell_z);
    SHOWF(CavaMoveEvent, reserved0); SHOWF(CavaMoveEvent, reserved1);
    SHOWF(CavaMoveEvent, shape_token);
    SHOWF(CavaMoveEvent, offset); SHOWF(CavaMoveEvent, max_dist_before); SHOWF(CavaMoveEvent, max_dist_after);
    SHOW(CavaMoveResult);
    SHOWF(CavaMoveResult, status); SHOWF(CavaMoveResult, step_used);
    SHOWF(CavaMoveResult, event_count); SHOWF(CavaMoveResult, event_overflow);
    SHOWF(CavaMoveResult, delta_x); SHOWF(CavaMoveResult, delta_y); SHOWF(CavaMoveResult, delta_z);
    SHOWF(CavaMoveResult, base_x); SHOWF(CavaMoveResult, base_y); SHOWF(CavaMoveResult, base_z);
    SHOWF(CavaMoveResult, step_x); SHOWF(CavaMoveResult, step_y); SHOWF(CavaMoveResult, step_z);

    printf("-- step 3: 5 个新结构体的 layout_hash 与投影和 --\n");
    uint32_t n[5];
    n[0] = hash_CavaShapeRecord();
    n[1] = hash_CavaMoveShapeRef();
    n[2] = hash_CavaMoveRequest();
    n[3] = hash_CavaMoveEvent();
    n[4] = hash_CavaMoveResult();
    const char* nn[5] = { "CavaShapeRecord", "CavaMoveShapeRef", "CavaMoveRequest",
                          "CavaMoveEvent", "CavaMoveResult" };
    uint32_t add = 0;
    for (int i = 0; i < 5; ++i) { printf("  %-18s hash=0x%08X\n", nn[i], n[i]); add += n[i]; }
    printf("  投影 layout_hash_sum = 0x%08X + 0x%08X = 0x%08X\n", base_sum, add, base_sum + add);
    printf("  （提案值：落地到 cava_abi.h 后必须由原生 cava_layout_report 与 Java CavaLayouts 两侧独立复算确认）\n");

    printf("SUMMARY: %s\n", g_fails == 0 ? "hash-impl OK" : "hash-impl MISMATCH");
    return g_fails == 0 ? 0 : 1;
}
