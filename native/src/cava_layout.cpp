/* cava_layout.cpp —— 布局自检（B1 / 契约 2.3）。
 *
 * 这是防「JVM 段错误」的核心机制：Java 侧用**完全一样的公式**算出 4 个结构体的
 * layout_hash 与 layout_hash_sum，填进 CavaOpenParams.layout_hash_sum；
 * 原生侧自算一遍比对，不等就 CAVA_ERR_LAYOUT + handle=0 => 整体回退纯 Java。
 *
 * 哈希公式（契约 2.3 的伪代码，逐字实现，Java 侧必须一模一样）：
 *     h = 0x811C9DC5
 *     for each field in declaration order:
 *         h ^= (uint32_t)(offset & 0xFFFFFFFF); h *= 0x01000193
 *         h ^= (uint32_t)(size   & 0xFFFFFFFF); h *= 0x01000193
 *         h ^= (uint32_t)((offset >> 32) & 0xFFFFFFFF); h *= 0x01000193
 *         h ^= (uint32_t)((size   >> 32) & 0xFFFFFFFF); h *= 0x01000193
 *
 * 两条必须写进文档的裁定（Java 侧必须照做）：
 *   1) 数组字段算成一个字段：offset = 数组起始，size = 整个数组的字节数。
 *      理由：CAVA_LAYOUT_MAX_FIELDS == 32，而 CavaLayoutEntry 自身就有 2 个 32 元素
 *      数组（6 + 32 + 32 = 70 > 32），只有把数组当一个字段才装得下。
 *   2) 32 位字段也要走满 4 步（高位补 0）。伪代码就是这么写的，别自作聪明省两步。
 */
#include "cava_internal.h"

#include <cstring>

namespace {

struct StructLayout {
    const char* name;
    uint64_t    size;
    uint64_t    align;
    int32_t     field_count;
    uint64_t    offsets[CAVA_LAYOUT_MAX_FIELDS];
    uint64_t    sizes[CAVA_LAYOUT_MAX_FIELDS];
};

/* entry 顺序 = cava_abi.h 里的声明顺序。Java 侧求和时顺序无所谓（加法可交换），
 * 但逐条比对的测试要按这个顺序。
 *
 * P1 追加的 5 个结构体（2026-09-22，captain 随 ABI 扩展一起登记）：
 *   CavaPathRequest / CavaPathNode / CavaMobProfile / CavaStateRecord / CavaCollisionBox
 * **必须与 Java 侧 cava/ffm/CavaLayouts.java 同时登记**，否则两边 layout_hash_sum 不等
 * → cava_open 返回 CAVA_ERR_LAYOUT → 整体回退纯 Java。加完后由 captain 复算和值。*/
const StructLayout kLayouts[] = {
    {
        "CavaLayoutEntry",
        (uint64_t)sizeof(CavaLayoutEntry),
        (uint64_t)alignof(CavaLayoutEntry),
        8,
        {
            (uint64_t)offsetof(CavaLayoutEntry, abi_version),
            (uint64_t)offsetof(CavaLayoutEntry, reserved0),
            (uint64_t)offsetof(CavaLayoutEntry, struct_size),
            (uint64_t)offsetof(CavaLayoutEntry, struct_align),
            (uint64_t)offsetof(CavaLayoutEntry, field_count),
            (uint64_t)offsetof(CavaLayoutEntry, layout_hash),
            (uint64_t)offsetof(CavaLayoutEntry, field_offsets),
            (uint64_t)offsetof(CavaLayoutEntry, field_sizes),
        },
        {
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->abi_version),
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->reserved0),
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->struct_size),
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->struct_align),
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->field_count),
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->layout_hash),
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->field_offsets),
            (uint64_t)sizeof(((CavaLayoutEntry*)0)->field_sizes),
        },
    },
    {
        "CavaLayoutReport",
        (uint64_t)sizeof(CavaLayoutReport),
        (uint64_t)alignof(CavaLayoutReport),
        8,
        {
            (uint64_t)offsetof(CavaLayoutReport, abi_version),
            (uint64_t)offsetof(CavaLayoutReport, build_flags),
            (uint64_t)offsetof(CavaLayoutReport, platform),
            (uint64_t)offsetof(CavaLayoutReport, pointer_size),
            (uint64_t)offsetof(CavaLayoutReport, entry_count),
            (uint64_t)offsetof(CavaLayoutReport, reserved0),
            (uint64_t)offsetof(CavaLayoutReport, build_id_hash),
            (uint64_t)offsetof(CavaLayoutReport, entries),
        },
        {
            (uint64_t)sizeof(((CavaLayoutReport*)0)->abi_version),
            (uint64_t)sizeof(((CavaLayoutReport*)0)->build_flags),
            (uint64_t)sizeof(((CavaLayoutReport*)0)->platform),
            (uint64_t)sizeof(((CavaLayoutReport*)0)->pointer_size),
            (uint64_t)sizeof(((CavaLayoutReport*)0)->entry_count),
            (uint64_t)sizeof(((CavaLayoutReport*)0)->reserved0),
            (uint64_t)sizeof(((CavaLayoutReport*)0)->build_id_hash),
            (uint64_t)sizeof(((CavaLayoutReport*)0)->entries),
        },
    },
    {
        "CavaOpenParams",
        (uint64_t)sizeof(CavaOpenParams),
        (uint64_t)alignof(CavaOpenParams),
        5,
        {
            (uint64_t)offsetof(CavaOpenParams, abi_version),
            (uint64_t)offsetof(CavaOpenParams, flags),
            (uint64_t)offsetof(CavaOpenParams, layout_hash_sum),
            (uint64_t)offsetof(CavaOpenParams, reserved0),
            (uint64_t)offsetof(CavaOpenParams, reserved1),
        },
        {
            (uint64_t)sizeof(((CavaOpenParams*)0)->abi_version),
            (uint64_t)sizeof(((CavaOpenParams*)0)->flags),
            (uint64_t)sizeof(((CavaOpenParams*)0)->layout_hash_sum),
            (uint64_t)sizeof(((CavaOpenParams*)0)->reserved0),
            (uint64_t)sizeof(((CavaOpenParams*)0)->reserved1),
        },
    },
    {
        "CavaOpenResult",
        (uint64_t)sizeof(CavaOpenResult),
        (uint64_t)alignof(CavaOpenResult),
        4,
        {
            (uint64_t)offsetof(CavaOpenResult, status),
            (uint64_t)offsetof(CavaOpenResult, abi_version),
            (uint64_t)offsetof(CavaOpenResult, native_layout_sum),
            (uint64_t)offsetof(CavaOpenResult, reserved0),
        },
        {
            (uint64_t)sizeof(((CavaOpenResult*)0)->status),
            (uint64_t)sizeof(((CavaOpenResult*)0)->abi_version),
            (uint64_t)sizeof(((CavaOpenResult*)0)->native_layout_sum),
            (uint64_t)sizeof(((CavaOpenResult*)0)->reserved0),
        },
    },
    {
        "CavaPathRequest",
        (uint64_t)sizeof(CavaPathRequest),
        (uint64_t)alignof(CavaPathRequest),
        13,
        {
            (uint64_t)offsetof(CavaPathRequest, reserved1),
            (uint64_t)offsetof(CavaPathRequest, tx),
            (uint64_t)offsetof(CavaPathRequest, ty),
            (uint64_t)offsetof(CavaPathRequest, tz),
            (uint64_t)offsetof(CavaPathRequest, reach_range),
            (uint64_t)offsetof(CavaPathRequest, max_range),
            (uint64_t)offsetof(CavaPathRequest, flags),
            (uint64_t)offsetof(CavaPathRequest, reserved0),
            (uint64_t)offsetof(CavaPathRequest, reserved2),
            (uint64_t)offsetof(CavaPathRequest, max_visited_nodes),
            (uint64_t)offsetof(CavaPathRequest, pad0),
            (uint64_t)offsetof(CavaPathRequest, pad1),
            (uint64_t)offsetof(CavaPathRequest, pad2),
        },
        {
            (uint64_t)sizeof(((CavaPathRequest*)0)->reserved1),
            (uint64_t)sizeof(((CavaPathRequest*)0)->tx),
            (uint64_t)sizeof(((CavaPathRequest*)0)->ty),
            (uint64_t)sizeof(((CavaPathRequest*)0)->tz),
            (uint64_t)sizeof(((CavaPathRequest*)0)->reach_range),
            (uint64_t)sizeof(((CavaPathRequest*)0)->max_range),
            (uint64_t)sizeof(((CavaPathRequest*)0)->flags),
            (uint64_t)sizeof(((CavaPathRequest*)0)->reserved0),
            (uint64_t)sizeof(((CavaPathRequest*)0)->reserved2),
            (uint64_t)sizeof(((CavaPathRequest*)0)->max_visited_nodes),
            (uint64_t)sizeof(((CavaPathRequest*)0)->pad0),
            (uint64_t)sizeof(((CavaPathRequest*)0)->pad1),
            (uint64_t)sizeof(((CavaPathRequest*)0)->pad2),
        },
    },
    {
        "CavaPathNode",
        (uint64_t)sizeof(CavaPathNode),
        (uint64_t)alignof(CavaPathNode),
        8,
        {
            (uint64_t)offsetof(CavaPathNode, x),
            (uint64_t)offsetof(CavaPathNode, y),
            (uint64_t)offsetof(CavaPathNode, z),
            (uint64_t)offsetof(CavaPathNode, heapIndex),
            (uint64_t)offsetof(CavaPathNode, g),
            (uint64_t)offsetof(CavaPathNode, f),
            (uint64_t)offsetof(CavaPathNode, type),
            (uint64_t)offsetof(CavaPathNode, flags),
        },
        {
            (uint64_t)sizeof(((CavaPathNode*)0)->x),
            (uint64_t)sizeof(((CavaPathNode*)0)->y),
            (uint64_t)sizeof(((CavaPathNode*)0)->z),
            (uint64_t)sizeof(((CavaPathNode*)0)->heapIndex),
            (uint64_t)sizeof(((CavaPathNode*)0)->g),
            (uint64_t)sizeof(((CavaPathNode*)0)->f),
            (uint64_t)sizeof(((CavaPathNode*)0)->type),
            (uint64_t)sizeof(((CavaPathNode*)0)->flags),
        },
    },
    {
        "CavaMobProfile",
        (uint64_t)sizeof(CavaMobProfile),
        (uint64_t)alignof(CavaMobProfile),
        18,
        {
            (uint64_t)offsetof(CavaMobProfile, penalty),
            (uint64_t)offsetof(CavaMobProfile, reserved_max_fall_distance),
            (uint64_t)offsetof(CavaMobProfile, start_x),
            (uint64_t)offsetof(CavaMobProfile, start_y),
            (uint64_t)offsetof(CavaMobProfile, start_z),
            (uint64_t)offsetof(CavaMobProfile, start_block_x),
            (uint64_t)offsetof(CavaMobProfile, start_block_y),
            (uint64_t)offsetof(CavaMobProfile, start_block_z),
            (uint64_t)offsetof(CavaMobProfile, width),
            (uint64_t)offsetof(CavaMobProfile, height),
            (uint64_t)offsetof(CavaMobProfile, step_height),
            (uint64_t)offsetof(CavaMobProfile, safe_fall_distance),
            (uint64_t)offsetof(CavaMobProfile, min_y),
            (uint64_t)offsetof(CavaMobProfile, sea_level),
            (uint64_t)offsetof(CavaMobProfile, caps),
            (uint64_t)offsetof(CavaMobProfile, penalty_mask),
            (uint64_t)offsetof(CavaMobProfile, reserved0),
            (uint64_t)offsetof(CavaMobProfile, reserved1),
        },
        {
            (uint64_t)sizeof(((CavaMobProfile*)0)->penalty),
            (uint64_t)sizeof(((CavaMobProfile*)0)->reserved_max_fall_distance),
            (uint64_t)sizeof(((CavaMobProfile*)0)->start_x),
            (uint64_t)sizeof(((CavaMobProfile*)0)->start_y),
            (uint64_t)sizeof(((CavaMobProfile*)0)->start_z),
            (uint64_t)sizeof(((CavaMobProfile*)0)->start_block_x),
            (uint64_t)sizeof(((CavaMobProfile*)0)->start_block_y),
            (uint64_t)sizeof(((CavaMobProfile*)0)->start_block_z),
            (uint64_t)sizeof(((CavaMobProfile*)0)->width),
            (uint64_t)sizeof(((CavaMobProfile*)0)->height),
            (uint64_t)sizeof(((CavaMobProfile*)0)->step_height),
            (uint64_t)sizeof(((CavaMobProfile*)0)->safe_fall_distance),
            (uint64_t)sizeof(((CavaMobProfile*)0)->min_y),
            (uint64_t)sizeof(((CavaMobProfile*)0)->sea_level),
            (uint64_t)sizeof(((CavaMobProfile*)0)->caps),
            (uint64_t)sizeof(((CavaMobProfile*)0)->penalty_mask),
            (uint64_t)sizeof(((CavaMobProfile*)0)->reserved0),
            (uint64_t)sizeof(((CavaMobProfile*)0)->reserved1),
        },
    },
    {
        "CavaStateRecord",
        (uint64_t)sizeof(CavaStateRecord),
        (uint64_t)alignof(CavaStateRecord),
        5,
        {
            (uint64_t)offsetof(CavaStateRecord, flags),
            (uint64_t)offsetof(CavaStateRecord, box_offset),
            (uint64_t)offsetof(CavaStateRecord, box_count),
            (uint64_t)offsetof(CavaStateRecord, path_type_idx),
            (uint64_t)offsetof(CavaStateRecord, malus),
        },
        {
            (uint64_t)sizeof(((CavaStateRecord*)0)->flags),
            (uint64_t)sizeof(((CavaStateRecord*)0)->box_offset),
            (uint64_t)sizeof(((CavaStateRecord*)0)->box_count),
            (uint64_t)sizeof(((CavaStateRecord*)0)->path_type_idx),
            (uint64_t)sizeof(((CavaStateRecord*)0)->malus),
        },
    },
    {
        "CavaCollisionBox",
        (uint64_t)sizeof(CavaCollisionBox),
        (uint64_t)alignof(CavaCollisionBox),
        6,
        {
            (uint64_t)offsetof(CavaCollisionBox, min_x),
            (uint64_t)offsetof(CavaCollisionBox, min_y),
            (uint64_t)offsetof(CavaCollisionBox, min_z),
            (uint64_t)offsetof(CavaCollisionBox, max_x),
            (uint64_t)offsetof(CavaCollisionBox, max_y),
            (uint64_t)offsetof(CavaCollisionBox, max_z),
        },
        {
            (uint64_t)sizeof(((CavaCollisionBox*)0)->min_x),
            (uint64_t)sizeof(((CavaCollisionBox*)0)->min_y),
            (uint64_t)sizeof(((CavaCollisionBox*)0)->min_z),
            (uint64_t)sizeof(((CavaCollisionBox*)0)->max_x),
            (uint64_t)sizeof(((CavaCollisionBox*)0)->max_y),
            (uint64_t)sizeof(((CavaCollisionBox*)0)->max_z),
        },
    },
    {
        "CavaShapeRecord",
        (uint64_t)sizeof(CavaShapeRecord),
        (uint64_t)alignof(CavaShapeRecord),
        8,
        {
            (uint64_t)offsetof(CavaShapeRecord, points_kind),
            (uint64_t)offsetof(CavaShapeRecord, point_offset),
            (uint64_t)offsetof(CavaShapeRecord, bit_offset),
            (uint64_t)offsetof(CavaShapeRecord, bit_words),
            (uint64_t)offsetof(CavaShapeRecord, size_x),
            (uint64_t)offsetof(CavaShapeRecord, size_y),
            (uint64_t)offsetof(CavaShapeRecord, size_z),
            (uint64_t)offsetof(CavaShapeRecord, reserved0),
        },
        {
            (uint64_t)sizeof(((CavaShapeRecord*)0)->points_kind),
            (uint64_t)sizeof(((CavaShapeRecord*)0)->point_offset),
            (uint64_t)sizeof(((CavaShapeRecord*)0)->bit_offset),
            (uint64_t)sizeof(((CavaShapeRecord*)0)->bit_words),
            (uint64_t)sizeof(((CavaShapeRecord*)0)->size_x),
            (uint64_t)sizeof(((CavaShapeRecord*)0)->size_y),
            (uint64_t)sizeof(((CavaShapeRecord*)0)->size_z),
            (uint64_t)sizeof(((CavaShapeRecord*)0)->reserved0),
        },
    },
    {
        "CavaMoveShapeRef",
        (uint64_t)sizeof(CavaMoveShapeRef),
        (uint64_t)alignof(CavaMoveShapeRef),
        11,
        {
            (uint64_t)offsetof(CavaMoveShapeRef, shape_token),
            (uint64_t)offsetof(CavaMoveShapeRef, kind),
            (uint64_t)offsetof(CavaMoveShapeRef, state_id),
            (uint64_t)offsetof(CavaMoveShapeRef, block_x),
            (uint64_t)offsetof(CavaMoveShapeRef, block_y),
            (uint64_t)offsetof(CavaMoveShapeRef, block_z),
            (uint64_t)offsetof(CavaMoveShapeRef, source),
            (uint64_t)offsetof(CavaMoveShapeRef, inline_slot),
            (uint64_t)offsetof(CavaMoveShapeRef, reserved0),
            (uint64_t)offsetof(CavaMoveShapeRef, reserved1),
            (uint64_t)offsetof(CavaMoveShapeRef, reserved2),
        },
        {
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->shape_token),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->kind),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->state_id),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->block_x),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->block_y),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->block_z),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->source),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->inline_slot),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->reserved0),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->reserved1),
            (uint64_t)sizeof(((CavaMoveShapeRef*)0)->reserved2),
        },
    },
    {
        "CavaMoveRequest",
        (uint64_t)sizeof(CavaMoveRequest),
        (uint64_t)alignof(CavaMoveRequest),
        15,
        {
            (uint64_t)offsetof(CavaMoveRequest, reserved0),
            (uint64_t)offsetof(CavaMoveRequest, min_x),
            (uint64_t)offsetof(CavaMoveRequest, min_y),
            (uint64_t)offsetof(CavaMoveRequest, min_z),
            (uint64_t)offsetof(CavaMoveRequest, max_x),
            (uint64_t)offsetof(CavaMoveRequest, max_y),
            (uint64_t)offsetof(CavaMoveRequest, max_z),
            (uint64_t)offsetof(CavaMoveRequest, move_x),
            (uint64_t)offsetof(CavaMoveRequest, move_y),
            (uint64_t)offsetof(CavaMoveRequest, move_z),
            (uint64_t)offsetof(CavaMoveRequest, step_height),
            (uint64_t)offsetof(CavaMoveRequest, flags),
            (uint64_t)offsetof(CavaMoveRequest, on_ground),
            (uint64_t)offsetof(CavaMoveRequest, shape_count),
            (uint64_t)offsetof(CavaMoveRequest, reserved1),
        },
        {
            (uint64_t)sizeof(((CavaMoveRequest*)0)->reserved0),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->min_x),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->min_y),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->min_z),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->max_x),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->max_y),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->max_z),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->move_x),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->move_y),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->move_z),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->step_height),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->flags),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->on_ground),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->shape_count),
            (uint64_t)sizeof(((CavaMoveRequest*)0)->reserved1),
        },
    },
    {
        "CavaMoveEvent",
        (uint64_t)sizeof(CavaMoveEvent),
        (uint64_t)alignof(CavaMoveEvent),
        16,
        {
            (uint64_t)offsetof(CavaMoveEvent, source),
            (uint64_t)offsetof(CavaMoveEvent, axis),
            (uint64_t)offsetof(CavaMoveEvent, block_x),
            (uint64_t)offsetof(CavaMoveEvent, block_y),
            (uint64_t)offsetof(CavaMoveEvent, block_z),
            (uint64_t)offsetof(CavaMoveEvent, pass),
            (uint64_t)offsetof(CavaMoveEvent, accepted),
            (uint64_t)offsetof(CavaMoveEvent, cell_x),
            (uint64_t)offsetof(CavaMoveEvent, cell_y),
            (uint64_t)offsetof(CavaMoveEvent, cell_z),
            (uint64_t)offsetof(CavaMoveEvent, reserved0),
            (uint64_t)offsetof(CavaMoveEvent, reserved1),
            (uint64_t)offsetof(CavaMoveEvent, shape_token),
            (uint64_t)offsetof(CavaMoveEvent, offset),
            (uint64_t)offsetof(CavaMoveEvent, max_dist_before),
            (uint64_t)offsetof(CavaMoveEvent, max_dist_after),
        },
        {
            (uint64_t)sizeof(((CavaMoveEvent*)0)->source),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->axis),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->block_x),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->block_y),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->block_z),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->pass),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->accepted),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->cell_x),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->cell_y),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->cell_z),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->reserved0),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->reserved1),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->shape_token),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->offset),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->max_dist_before),
            (uint64_t)sizeof(((CavaMoveEvent*)0)->max_dist_after),
        },
    },
    {
        "CavaMoveResult",
        (uint64_t)sizeof(CavaMoveResult),
        (uint64_t)alignof(CavaMoveResult),
        13,
        {
            (uint64_t)offsetof(CavaMoveResult, status),
            (uint64_t)offsetof(CavaMoveResult, step_used),
            (uint64_t)offsetof(CavaMoveResult, event_count),
            (uint64_t)offsetof(CavaMoveResult, event_overflow),
            (uint64_t)offsetof(CavaMoveResult, delta_x),
            (uint64_t)offsetof(CavaMoveResult, delta_y),
            (uint64_t)offsetof(CavaMoveResult, delta_z),
            (uint64_t)offsetof(CavaMoveResult, base_x),
            (uint64_t)offsetof(CavaMoveResult, base_y),
            (uint64_t)offsetof(CavaMoveResult, base_z),
            (uint64_t)offsetof(CavaMoveResult, step_x),
            (uint64_t)offsetof(CavaMoveResult, step_y),
            (uint64_t)offsetof(CavaMoveResult, step_z),
        },
        {
            (uint64_t)sizeof(((CavaMoveResult*)0)->status),
            (uint64_t)sizeof(((CavaMoveResult*)0)->step_used),
            (uint64_t)sizeof(((CavaMoveResult*)0)->event_count),
            (uint64_t)sizeof(((CavaMoveResult*)0)->event_overflow),
            (uint64_t)sizeof(((CavaMoveResult*)0)->delta_x),
            (uint64_t)sizeof(((CavaMoveResult*)0)->delta_y),
            (uint64_t)sizeof(((CavaMoveResult*)0)->delta_z),
            (uint64_t)sizeof(((CavaMoveResult*)0)->base_x),
            (uint64_t)sizeof(((CavaMoveResult*)0)->base_y),
            (uint64_t)sizeof(((CavaMoveResult*)0)->base_z),
            (uint64_t)sizeof(((CavaMoveResult*)0)->step_x),
            (uint64_t)sizeof(((CavaMoveResult*)0)->step_y),
            (uint64_t)sizeof(((CavaMoveResult*)0)->step_z),
        },
    },
};

constexpr int32_t kStructCount = (int32_t)(sizeof(kLayouts) / sizeof(kLayouts[0]));
static_assert(kStructCount == 14, "登记的结构体数量与 cava_abi.h 不一致；加结构体要同时改 Java 侧");
static_assert(kStructCount <= CAVA_LAYOUT_REPORT_CAP, "entry 数超过 CavaLayoutReport 容量");

uint32_t hash_field(uint32_t h, uint64_t off, uint64_t size) {
    h ^= (uint32_t)(off & 0xFFFFFFFFull);
    h *= CAVA_LAYOUT_FNV_PRIME;
    h ^= (uint32_t)(size & 0xFFFFFFFFull);
    h *= CAVA_LAYOUT_FNV_PRIME;
    h ^= (uint32_t)((off >> 32) & 0xFFFFFFFFull);
    h *= CAVA_LAYOUT_FNV_PRIME;
    h ^= (uint32_t)((size >> 32) & 0xFFFFFFFFull);
    h *= CAVA_LAYOUT_FNV_PRIME;
    return h;
}

uint32_t struct_hash(const StructLayout& s) {
    uint32_t h = CAVA_LAYOUT_FNV_OFFSET;
    for (int32_t i = 0; i < s.field_count; ++i) {
        h = hash_field(h, s.offsets[i], s.sizes[i]);
    }
    return h;
}

void fill_entry(CavaLayoutEntry* e, const StructLayout& s) {
    std::memset(e, 0, sizeof(*e));
    e->abi_version  = CAVA_ABI_VERSION;
    e->reserved0    = 0;
    e->struct_size  = s.size;
    e->struct_align = s.align;
    e->field_count  = (uint32_t)s.field_count;
    e->layout_hash  = struct_hash(s);
    for (int32_t i = 0; i < s.field_count; ++i) {
        e->field_offsets[i] = s.offsets[i];
        e->field_sizes[i]   = s.sizes[i];
    }
}

const StructLayout* find_layout(const char* name) {
    if (name == nullptr) {
        return nullptr;
    }
    for (int32_t i = 0; i < kStructCount; ++i) {
        if (std::strcmp(kLayouts[i].name, name) == 0) {
            return &kLayouts[i];
        }
    }
    return nullptr;
}

/* 契约 2.3 要求的 cava_layout_<name>(hash*, size*, align*) 形状的内部实现。*/
int32_t layout_query(const char* name, uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align) {
    const StructLayout* s = find_layout(name);
    if (s == nullptr) {
        return CAVA_ERR_ARG;
    }
    if (out_hash != nullptr) {
        *out_hash = struct_hash(*s);
    }
    if (out_size != nullptr) {
        *out_size = s->size;
    }
    if (out_align != nullptr) {
        *out_align = s->align;
    }
    return s->field_count;
}

} /* namespace */

namespace cava {
namespace detail {

uint32_t fnv1a32_bytes(const void* data, std::size_t len) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    uint32_t h = CAVA_LAYOUT_FNV_OFFSET;
    for (std::size_t i = 0; i < len; ++i) {
        h ^= (uint32_t)p[i];
        h *= CAVA_LAYOUT_FNV_PRIME;
    }
    return h;
}

uint32_t layout_hash_of(const char* struct_name) {
    const StructLayout* s = find_layout(struct_name);
    return (s == nullptr) ? 0u : struct_hash(*s);
}

uint64_t native_layout_sum() {
    uint32_t sum = 0;
    for (int32_t i = 0; i < kStructCount; ++i) {
        sum += struct_hash(kLayouts[i]); /* uint32 回绕（契约 2.3） */
    }
    return (uint64_t)sum;
}

int32_t layout_CavaLayoutEntry(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaLayoutEntry", h, s, a);
}
int32_t layout_CavaLayoutReport(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaLayoutReport", h, s, a);
}
int32_t layout_CavaOpenParams(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaOpenParams", h, s, a);
}
int32_t layout_CavaOpenResult(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaOpenResult", h, s, a);
}
int32_t layout_CavaPathRequest(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaPathRequest", h, s, a);
}
int32_t layout_CavaPathNode(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaPathNode", h, s, a);
}
int32_t layout_CavaMobProfile(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaMobProfile", h, s, a);
}
int32_t layout_CavaStateRecord(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaStateRecord", h, s, a);
}
int32_t layout_CavaCollisionBox(uint32_t* h, uint64_t* s, uint64_t* a) {
    return layout_query("CavaCollisionBox", h, s, a);
}

} /* namespace detail */
} /* namespace cava */

extern "C" CAVA_EXPORT int32_t cava_layout_report(CavaLayoutReport* out) {
    CAVA_ASSERT(out != nullptr, CAVA_ERR_NULL);
    if (out == nullptr) {
        return CAVA_ERR_NULL;
    }

    std::memset(out, 0, sizeof(*out));
    out->abi_version   = CAVA_ABI_VERSION;
    out->build_flags   = cava::detail::build_flags();
    out->platform      = cava::detail::platform_code();
    out->pointer_size  = (int32_t)sizeof(void*);
    out->entry_count   = kStructCount;
    out->reserved0     = 0;
    out->build_id_hash = (uint64_t)cava::detail::fnv1a32_bytes(cava_build_id(),
                                                               std::strlen(cava_build_id()));
    for (int32_t i = 0; i < kStructCount; ++i) {
        fill_entry(&out->entries[i], kLayouts[i]);
    }
    return kStructCount;
}
