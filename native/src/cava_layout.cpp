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

/* entry 顺序 = cava_abi.h 里的声明顺序：CavaLayoutEntry / CavaLayoutReport /
 * CavaOpenParams / CavaOpenResult。Java 侧求和时顺序无所谓（加法可交换），
 * 但逐条比对的测试要按这个顺序。 */
const StructLayout kLayouts[4] = {
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
};

constexpr int32_t kStructCount = (int32_t)(sizeof(kLayouts) / sizeof(kLayouts[0]));
static_assert(kStructCount == 4, "P0 只导出这 4 个结构体；加结构体要同时改契约文档与 Java 侧");
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
