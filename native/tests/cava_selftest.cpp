/* cava_selftest.cpp —— P0 原生核心自测（B3）。
 *
 * 覆盖：
 *   1) cava_abi_version() == CAVA_ABI_VERSION
 *   2) cava_layout_report(): 4 条 entry、struct_size/align/field 全对、layout_hash 与
 *      **测试侧独立重算**的值一致、build_id_hash 与独立 FNV-1a 一致
 *   3) cava_open(): 正确 layout_hash_sum 成功；错值 -> CAVA_ERR_LAYOUT；错 ABI -> CAVA_ERR_ABI_VERSION；
 *      NULL 参数 -> CAVA_ERR_NULL（且 native_layout_sum 永远回填）
 *   4) cava_close(): 幂等 / 关闭 0 / 陈旧句柄 / 伪造句柄 / 负数 —— 全部返回错误码且不崩
 *   5) cava_d2i_sat / cava_d2l_sat 全边界表（Java 语义）
 *   6) cava_bits_of_double(cava_double_of_bits(x)) == x 随机位模式（含 NaN 载荷、次正规）
 *   7) cava_abi_touch() 金丝雀递增
 *
 * 用法：
 *   cava_selftest                 跑全部检查；全过 exit 0，有失败 exit 1
 *   cava_selftest --dump-layout [输出文件]   打印布局参考表（给 Java 侧当黄金参考）
 *
 * 编译（不依赖 CMake，见 native/tests/build-mingw.ps1）：
 *   pwsh -File native/tests/build-mingw.ps1
 */
#include "../include/cava_abi.h"

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>

/* ------------------------------------------------------------------ */
/* 迷你测试框架                                                         */
/* ------------------------------------------------------------------ */
static int g_pass = 0;
static int g_fail = 0;
static const char* g_section = "";

static void section(const char* name) {
    g_section = name;
    std::printf("\n--- %s ---\n", name);
}

static void check(bool ok, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (ok) {
        ++g_pass;
        std::printf("  [ ok ] %s\n", buf);
    } else {
        ++g_fail;
        std::printf("  [FAIL] %s\n", buf);
    }
}

/* ------------------------------------------------------------------ */
/* 测试侧独立实现的 FNV-1a（故意和库里的写法不同，用来交叉验证）        */
/* ------------------------------------------------------------------ */
static uint32_t fnv_from_bytes(const void* data, size_t len) {
    const unsigned char* p = (const unsigned char*)data;
    uint32_t h = 0x811C9DC5u;
    for (size_t i = 0; i < len; ++i) {
        h ^= (uint32_t)p[i];
        h = h * 0x01000193u;
    }
    return h;
}

/* 契约 2.3：每字段 4 步（off_lo, size_lo, off_hi, size_hi）*/
static uint32_t fnv_fields(const uint64_t* offs, const uint64_t* sizes, int32_t n) {
    uint32_t h = 0x811C9DC5u;
    for (int32_t i = 0; i < n; ++i) {
        uint32_t feed[4];
        feed[0] = (uint32_t)(offs[i] & 0xFFFFFFFFull);
        feed[1] = (uint32_t)(sizes[i] & 0xFFFFFFFFull);
        feed[2] = (uint32_t)((offs[i] >> 32) & 0xFFFFFFFFull);
        feed[3] = (uint32_t)((sizes[i] >> 32) & 0xFFFFFFFFull);
        for (int k = 0; k < 4; ++k) {
            h ^= feed[k];
            h = h * 0x01000193u;
        }
    }
    return h;
}

/* ------------------------------------------------------------------ */
/* 黄金布局表（x64 / ABI v1 的硬编码期望值，改头文件就必须改这里）      */
/* ------------------------------------------------------------------ */
struct FieldExpect {
    const char* name;
    uint64_t    offset;
    uint64_t    size;
};

struct StructExpect {
    const char*      name;
    uint64_t         size;
    uint64_t         align;
    int32_t          field_count;
    const FieldExpect* fields;
};

static const FieldExpect kEntryFields[] = {
    { "abi_version",   0,   4 },
    { "reserved0",     4,   4 },
    { "struct_size",   8,   8 },
    { "struct_align",  16,  8 },
    { "field_count",   24,  4 },
    { "layout_hash",   28,  4 },
    { "field_offsets", 32,  256 },
    { "field_sizes",   288, 256 },
};
static const FieldExpect kReportFields[] = {
    { "abi_version",   0,  4 },
    { "build_flags",   4,  4 },
    { "platform",      8,  4 },
    { "pointer_size",  12, 4 },
    { "entry_count",   16, 4 },
    { "reserved0",     20, 4 },
    { "build_id_hash", 24, 8 },
    { "entries",       32, 34816 },
};
static const FieldExpect kParamsFields[] = {
    { "abi_version",     0,  4 },
    { "flags",           4,  4 },
    { "layout_hash_sum", 8,  8 },
    { "reserved0",       16, 8 },
    { "reserved1",       24, 8 },
};
static const FieldExpect kResultFields[] = {
    { "status",            0,  4 },
    { "abi_version",       4,  4 },
    { "native_layout_sum", 8,  8 },
    { "reserved0",         16, 8 },
};

/* P1 追加的 5 个结构体（captain 随 ABI 扩展登记，值与 Java 侧 CavaLayouts.java 逐项对齐过）。*/
static const FieldExpect kPathRequestFields[] = {
    { "reserved1",         0,  8 },
    { "tx",                8,  4 },
    { "ty",                12, 4 },
    { "tz",                16, 4 },
    { "reach_range",       20, 4 },
    { "max_range",         24, 4 },
    { "flags",             28, 4 },
    { "reserved0",         32, 4 },
    { "reserved2",         36, 4 },
    { "max_visited_nodes", 40, 4 },
    { "pad0",              44, 4 },
    { "pad1",              48, 4 },
    { "pad2",              52, 4 },
};
static const FieldExpect kPathNodeFields[] = {
    { "x",         0,  4 },
    { "y",         4,  4 },
    { "z",         8,  4 },
    { "heapIndex", 12, 4 },
    { "g",         16, 4 },
    { "f",         20, 4 },
    { "type",      24, 4 },
    { "flags",     28, 4 },
};
static const FieldExpect kMobProfileFields[] = {
    { "penalty",            0,   104 },
    { "max_fall_distance",  104, 4 },
    { "start_x",            112, 8 },
    { "start_y",            120, 8 },
    { "start_z",            128, 8 },
    { "start_block_x",      136, 4 },
    { "start_block_y",      140, 4 },
    { "start_block_z",      144, 4 },
    { "width",              148, 4 },
    { "height",             152, 4 },
    { "step_height",        156, 4 },
    { "safe_fall_distance", 160, 4 },
    { "min_y",              164, 4 },
    { "sea_level",          168, 4 },
    { "caps",               172, 4 },
    { "penalty_mask",       176, 4 },
    { "reserved0",          180, 4 },
    { "reserved1",          184, 4 },
};
static const FieldExpect kStateRecordFields[] = {
    { "flags",         0,  4 },
    { "box_offset",    4,  4 },
    { "box_count",     8,  4 },
    { "path_type_idx", 12, 4 },
    { "malus",         16, 4 },
};
static const FieldExpect kCollisionBoxFields[] = {
    { "min_x", 0,  4 },
    { "min_y", 4,  4 },
    { "min_z", 8,  4 },
    { "max_x", 12, 4 },
    { "max_y", 16, 4 },
    { "max_z", 20, 4 },
};

static const StructExpect kExpect[9] = {
    { "CavaLayoutEntry",   544,   8, 8,  kEntryFields        },
    { "CavaLayoutReport",  34848, 8, 8,  kReportFields       },
    { "CavaOpenParams",    32,    8, 5,  kParamsFields       },
    { "CavaOpenResult",    24,    8, 4,  kResultFields       },
    { "CavaPathRequest",   56,    8, 13, kPathRequestFields  },
    { "CavaPathNode",      32,    4, 8,  kPathNodeFields     },
    { "CavaMobProfile",    192,   8, 18, kMobProfileFields   },
    { "CavaStateRecord",   20,    4, 5,  kStateRecordFields  },
    { "CavaCollisionBox",  24,    4, 6,  kCollisionBoxFields },
};

/* ------------------------------------------------------------------ */
/* 机械期望：直接用 offsetof/sizeof 算出来（**不人手抄数字**）。        */
/* 为什么两套都要有：                                                  */
/*   - 硬编码表是「改头文件就会红」的变更探测器；                        */
/*   - 机械表能抓住「硬编码表和实现表犯同一个错」的情况 —— 这正是      */
/*     CavaPathNode 曾经漏登记 type/flags 两个字段时差点骗过测试的路径。*/
/* ------------------------------------------------------------------ */
#define CAVA_ROW(T, m) { #m, (uint64_t)offsetof(T, m), (uint64_t)sizeof(((T*)0)->m) }

#define CAVA_ROWS_CavaLayoutEntry(T)                                                                   \
    CAVA_ROW(T, abi_version), CAVA_ROW(T, reserved0), CAVA_ROW(T, struct_size), CAVA_ROW(T, struct_align), \
    CAVA_ROW(T, field_count), CAVA_ROW(T, layout_hash), CAVA_ROW(T, field_offsets), CAVA_ROW(T, field_sizes)

#define CAVA_ROWS_CavaLayoutReport(T)                                                                  \
    CAVA_ROW(T, abi_version), CAVA_ROW(T, build_flags), CAVA_ROW(T, platform), CAVA_ROW(T, pointer_size), \
    CAVA_ROW(T, entry_count), CAVA_ROW(T, reserved0), CAVA_ROW(T, build_id_hash), CAVA_ROW(T, entries)

#define CAVA_ROWS_CavaOpenParams(T) \
    CAVA_ROW(T, abi_version), CAVA_ROW(T, flags), CAVA_ROW(T, layout_hash_sum), CAVA_ROW(T, reserved0), CAVA_ROW(T, reserved1)

#define CAVA_ROWS_CavaOpenResult(T) \
    CAVA_ROW(T, status), CAVA_ROW(T, abi_version), CAVA_ROW(T, native_layout_sum), CAVA_ROW(T, reserved0)

#define CAVA_ROWS_CavaPathRequest(T)                                                                   \
    CAVA_ROW(T, reserved1), CAVA_ROW(T, tx), CAVA_ROW(T, ty), CAVA_ROW(T, tz), CAVA_ROW(T, reach_range), \
    CAVA_ROW(T, max_range), CAVA_ROW(T, flags), CAVA_ROW(T, reserved0), CAVA_ROW(T, reserved2),          \
    CAVA_ROW(T, max_visited_nodes), CAVA_ROW(T, pad0), CAVA_ROW(T, pad1), CAVA_ROW(T, pad2)

#define CAVA_ROWS_CavaPathNode(T)                                                                      \
    CAVA_ROW(T, x), CAVA_ROW(T, y), CAVA_ROW(T, z), CAVA_ROW(T, heapIndex), CAVA_ROW(T, g),             \
    CAVA_ROW(T, f), CAVA_ROW(T, type), CAVA_ROW(T, flags)

#define CAVA_ROWS_CavaMobProfile(T)                                                                    \
    CAVA_ROW(T, penalty), CAVA_ROW(T, max_fall_distance), CAVA_ROW(T, start_x), CAVA_ROW(T, start_y),   \
    CAVA_ROW(T, start_z), CAVA_ROW(T, start_block_x), CAVA_ROW(T, start_block_y), CAVA_ROW(T, start_block_z), \
    CAVA_ROW(T, width), CAVA_ROW(T, height), CAVA_ROW(T, step_height), CAVA_ROW(T, safe_fall_distance), \
    CAVA_ROW(T, min_y), CAVA_ROW(T, sea_level), CAVA_ROW(T, caps), CAVA_ROW(T, penalty_mask),          \
    CAVA_ROW(T, reserved0), CAVA_ROW(T, reserved1)

#define CAVA_ROWS_CavaStateRecord(T) \
    CAVA_ROW(T, flags), CAVA_ROW(T, box_offset), CAVA_ROW(T, box_count), CAVA_ROW(T, path_type_idx), CAVA_ROW(T, malus)

#define CAVA_ROWS_CavaCollisionBox(T) \
    CAVA_ROW(T, min_x), CAVA_ROW(T, min_y), CAVA_ROW(T, min_z), CAVA_ROW(T, max_x), CAVA_ROW(T, max_y), CAVA_ROW(T, max_z)

static const FieldExpect kMechEntry[]        = { CAVA_ROWS_CavaLayoutEntry(CavaLayoutEntry) };
static const FieldExpect kMechReport[]       = { CAVA_ROWS_CavaLayoutReport(CavaLayoutReport) };
static const FieldExpect kMechParams[]       = { CAVA_ROWS_CavaOpenParams(CavaOpenParams) };
static const FieldExpect kMechResult[]       = { CAVA_ROWS_CavaOpenResult(CavaOpenResult) };
static const FieldExpect kMechPathRequest[]  = { CAVA_ROWS_CavaPathRequest(CavaPathRequest) };
static const FieldExpect kMechPathNode[]     = { CAVA_ROWS_CavaPathNode(CavaPathNode) };
static const FieldExpect kMechMobProfile[]   = { CAVA_ROWS_CavaMobProfile(CavaMobProfile) };
static const FieldExpect kMechStateRecord[]  = { CAVA_ROWS_CavaStateRecord(CavaStateRecord) };
static const FieldExpect kMechCollisionBox[] = { CAVA_ROWS_CavaCollisionBox(CavaCollisionBox) };

struct MechExpect {
    const FieldExpect* fields;
    int32_t            count;   /* 字段数：直接数出来，不是抄的 */
    uint64_t           size;
    uint64_t           align;
};

static const MechExpect kMech[9] = {
    { kMechEntry,        (int32_t)(sizeof(kMechEntry) / sizeof(FieldExpect)),             sizeof(CavaLayoutEntry),   alignof(CavaLayoutEntry)   },
    { kMechReport,       (int32_t)(sizeof(kMechReport) / sizeof(FieldExpect)),            sizeof(CavaLayoutReport),  alignof(CavaLayoutReport)  },
    { kMechParams,       (int32_t)(sizeof(kMechParams) / sizeof(FieldExpect)),            sizeof(CavaOpenParams),    alignof(CavaOpenParams)    },
    { kMechResult,       (int32_t)(sizeof(kMechResult) / sizeof(FieldExpect)),            sizeof(CavaOpenResult),    alignof(CavaOpenResult)    },
    { kMechPathRequest,  (int32_t)(sizeof(kMechPathRequest) / sizeof(FieldExpect)),       sizeof(CavaPathRequest),   alignof(CavaPathRequest)   },
    { kMechPathNode,     (int32_t)(sizeof(kMechPathNode) / sizeof(FieldExpect)),          sizeof(CavaPathNode),      alignof(CavaPathNode)      },
    { kMechMobProfile,   (int32_t)(sizeof(kMechMobProfile) / sizeof(FieldExpect)),        sizeof(CavaMobProfile),    alignof(CavaMobProfile)    },
    { kMechStateRecord,  (int32_t)(sizeof(kMechStateRecord) / sizeof(FieldExpect)),       sizeof(CavaStateRecord),   alignof(CavaStateRecord)   },
    { kMechCollisionBox, (int32_t)(sizeof(kMechCollisionBox) / sizeof(FieldExpect)),      sizeof(CavaCollisionBox),  alignof(CavaCollisionBox)  },
};

static const int32_t kStructExpectCount = 9;

/* Java 侧对齐的和值（captain 复算并让 Java/C 两边逐字段一致后给出）。*/
static const uint32_t kExpectedLayoutSum = 0x6975CBF9u;

/* ------------------------------------------------------------------ */
/* --dump-layout：给 Java 侧的黄金参考文本                              */
/* ------------------------------------------------------------------ */
static void dump_layout(std::FILE* f, const CavaLayoutReport& rep) {
    std::fprintf(f, "# cava layout reference (ABI v%d)\n", rep.abi_version);
    std::fprintf(f, "# build_id: %s\n", cava_build_id());
    std::fprintf(f, "# struct_count=%d pointer_size=%d platform=%d build_flags=0x%08x\n",
                 rep.entry_count, rep.pointer_size, rep.platform, (unsigned)rep.build_flags);
    std::fprintf(f, "# build_id_hash=0x%08x\n", (unsigned)rep.build_id_hash);
    std::fprintf(f, "# 哈希公式（契约 2.3）：h=0x811C9DC5；每字段按声明顺序 4 步：\n");
    std::fprintf(f, "#   h^=(u32)(off&0xFFFFFFFF); h*=0x01000193; h^=(u32)(size&0xFFFFFFFF); h*=0x01000193;\n");
    std::fprintf(f, "#   h^=(u32)((off>>32)&0xFFFFFFFF); h*=0x01000193; h^=(u32)((size>>32)&0xFFFFFFFF); h*=0x01000193;\n");
    std::fprintf(f, "# 数组字段算一个字段：offset=数组起点, size=整个数组字节数\n");
    uint32_t sum = 0;
    const int32_t max_i = (rep.entry_count < kStructExpectCount) ? rep.entry_count : kStructExpectCount;
    for (int32_t i = 0; i < max_i; ++i) {
        const CavaLayoutEntry& e = rep.entries[i];
        std::fprintf(f, "struct %s size=%llu align=%llu fields=%u hash=0x%08x\n",
                     kExpect[i].name,
                     (unsigned long long)e.struct_size,
                     (unsigned long long)e.struct_align,
                     (unsigned)e.field_count,
                     (unsigned)e.layout_hash);
        for (uint32_t k = 0; k < e.field_count && k < (uint32_t)CAVA_LAYOUT_MAX_FIELDS; ++k) {
            std::fprintf(f, "  field %-20s offset=%-6llu size=%-6llu\n",
                         kExpect[i].fields[k].name,
                         (unsigned long long)e.field_offsets[k],
                         (unsigned long long)e.field_sizes[k]);
        }
        sum += e.layout_hash;
    }
    std::fprintf(f, "layout_hash_sum 0x%08x (%u)\n", (unsigned)sum, (unsigned)sum);
}

/* ------------------------------------------------------------------ */
/* 数值表                                                               */
/* ------------------------------------------------------------------ */
struct I32Case { double v; int32_t expect; const char* label; };

static const I32Case kI32[] = {
    {  0.0,                     0,            "0.0" },
    { -0.0,                     0,            "-0.0" },
    {  0.5,                     0,            "0.5" },
    { -0.5,                     0,            "-0.5" },
    {  0.9999999,               0,            "0.9999999" },
    { -0.9999999,               0,            "-0.9999999" },
    {  5e-324,                  0,            "min subnormal" },
    { -5e-324,                  0,            "-min subnormal" },
    {  1.0,                     1,            "1.0" },
    { -1.0,                    -1,            "-1.0" },
    {  1.9999999,               1,            "1.9999999" },
    { -1.9999999,              -1,            "-1.9999999" },
    {  12345.678,           12345,            "12345.678" },
    { -12345.678,          -12345,            "-12345.678" },
    {  2147483646.0,     2147483646,          "INT_MAX-1" },
    {  2147483646.5,     2147483646,          "INT_MAX-1 + .5（截断）" },
    {  2147483647.0,     2147483647,          "刚好 INT_MAX" },
    {  2147483647.5,     2147483647,          "INT_MAX + .5" },
    {  2147483648.0,     2147483647,          "INT_MAX+1（2^31）" },
    {  3.0e9,            2147483647,          "3e9" },
    {  1e300,            2147483647,          "1e300" },
    { -2147483647.0,    -2147483647,          "INT_MIN+1" },
    { -2147483647.5,    -2147483647,          "-INT_MAX-.5（截断向零）" },
    { -2147483648.0,    -2147483647 - 1,      "刚好 INT_MIN" },
    { -2147483648.5,    -2147483647 - 1,      "INT_MIN-.5" },
    { -2147483649.0,    -2147483647 - 1,      "INT_MIN-1" },
    { -3.0e9,           -2147483647 - 1,      "-3e9" },
    { -1e300,           -2147483647 - 1,      "-1e300" },
};

struct I64Case { double v; int64_t expect; const char* label; };

static const I64Case kI64[] = {
    {  0.0,                                0, "0.0" },
    { -0.0,                                0, "-0.0" },
    {  0.5,                                0, "0.5" },
    { -0.5,                                0, "-0.5" },
    {  5e-324,                             0, "min subnormal" },
    { -5e-324,                             0, "-min subnormal" },
    {  1.0,                                1, "1.0" },
    { -1.0,                               -1, "-1.0" },
    {  123456789012345.0,     123456789012345LL, "123456789012345" },
    { -123456789012345.5,    -123456789012345LL, "-123456789012345.5（截断）" },
    {  1.0e18,             1000000000000000000LL, "1e18" },
    {  4.611686018427387904e18, 4611686018427387904LL, "2^62" },
    {  9223372036854774784.0,  9223372036854774784LL, "2^63-1024（最大可转换）" },
    {  9223372036854775807.0,  9223372036854775807LL, "Long.MAX_VALUE（字面量=2^63）" },
    {  9223372036854775808.0,  9223372036854775807LL, "2^63 -> 饱和" },
    {  1.0e19,                 9223372036854775807LL, "1e19" },
    {  1e300,                  9223372036854775807LL, "1e300" },
    { -9223372036854774784.0, -9223372036854774784LL, "-(2^63-1024)" },
    { -9223372036854775808.0, (-9223372036854775807LL - 1), "-2^63 刚好" },
    { -9223372036854777856.0, (-9223372036854775807LL - 1), "-2^63-2048 -> 饱和" },
    { -1.0e19,                (-9223372036854775807LL - 1), "-1e19" },
    { -1e300,                 (-9223372036854775807LL - 1), "-1e300" },
};

/* NaN：位模式直给（载荷必须原样过境）*/
static const uint64_t kNanBits[] = {
    0x7FF8000000000000ull, /* 规范 quiet NaN */
    0xFFF8000000000000ull, /* 负 quiet NaN */
    0x7FF0000000000001ull, /* signaling NaN，最小载荷 */
    0x7FFFFFFFFFFFFFFFull, /* NaN 最大载荷 */
    0xFFFFFFFFFFFFFFFFull, /* 负 NaN 最大载荷 */
};

static const uint64_t kInfBits[] = {
    0x7FF0000000000000ull, /* +inf */
    0xFFF0000000000000ull, /* -inf */
};

/* ------------------------------------------------------------------ */
/* 随机位模式（固定种子 splitmix64，可重复）                            */
/* ------------------------------------------------------------------ */
static uint64_t splitmix64(uint64_t& s) {
    uint64_t z = (s += 0x9E3779B97F4A7C15ull);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return z ^ (z >> 31);
}

/* ------------------------------------------------------------------ */
int main(int argc, char** argv) {
    CavaLayoutReport rep;
    std::memset(&rep, 0, sizeof(rep));

    const int32_t entries = cava_layout_report(&rep);
    const bool dump = (argc > 1 && std::strcmp(argv[1], "--dump-layout") == 0);
    if (dump) {
        if (entries != kStructExpectCount) {
            std::fprintf(stderr, "layout_report failed: %d\n", (int)entries);
            return 1;
        }
        /* 可选的第二个参数 = 输出文件；不给就写 stdout。用 fopen("wb") 保证 LF、无 BOM。*/
        if (argc > 2) {
            std::FILE* out = std::fopen(argv[2], "wb");
            if (out == nullptr) {
                std::fprintf(stderr, "打不开输出文件 %s\n", argv[2]);
                return 1;
            }
            dump_layout(out, rep);
            std::fclose(out);
            std::printf("layout reference -> %s\n", argv[2]);
        } else {
            dump_layout(stdout, rep);
        }
        return 0;
    }

    std::printf("=== cava_selftest ===\n");
    std::printf("build_id     : %s\n", cava_build_id());
    std::printf("build_flags  : 0x%08x (safe_asserts=%d debug=%d asan=%d ubsan=%d)\n",
                (unsigned)rep.build_flags,
                (rep.build_flags & CAVA_BUILD_FLAG_SAFE_ASSERTS) ? 1 : 0,
                (rep.build_flags & CAVA_BUILD_FLAG_DEBUG) ? 1 : 0,
                (rep.build_flags & CAVA_BUILD_FLAG_ASAN) ? 1 : 0,
                (rep.build_flags & CAVA_BUILD_FLAG_UBSAN) ? 1 : 0);
    std::printf("platform     : %d   pointer_size=%d\n", (int)rep.platform, (int)rep.pointer_size);

    /* ---------------- 1. ABI 版本 ---------------- */
    section("1. abi version / build id");
    check(cava_abi_version() == CAVA_ABI_VERSION, "cava_abi_version()=%d == CAVA_ABI_VERSION=%d",
          (int)cava_abi_version(), (int)CAVA_ABI_VERSION);
    check(rep.abi_version == CAVA_ABI_VERSION, "report.abi_version=%d", (int)rep.abi_version);
    check(std::strlen(cava_build_id()) > 8, "cava_build_id() 非空: \"%s\"", cava_build_id());
    check(std::strstr(cava_build_id(), "cava ") == cava_build_id(), "build_id 以 \"cava \" 开头");

    /* ---------------- 2. 布局自检 ---------------- */
    section("2. layout report");
    check(entries == kStructExpectCount, "cava_layout_report 返回 %d（期望 %d）",
          (int)entries, (int)kStructExpectCount);
    check(rep.entry_count == kStructExpectCount, "report.entry_count=%d（期望 %d）",
          (int)rep.entry_count, (int)kStructExpectCount);
    check(rep.pointer_size == 8, "pointer_size=%d", (int)rep.pointer_size);
    check(rep.platform == CAVA_PLATFORM_WINDOWS_X64 || rep.platform == CAVA_PLATFORM_LINUX_X64 ||
          rep.platform > 0, "platform=%d（>0 即已识别）", (int)rep.platform);
    check(rep.build_id_hash == (uint64_t)fnv_from_bytes(cava_build_id(), std::strlen(cava_build_id())),
          "build_id_hash=0x%08x == 独立 FNV-1a 重算 0x%08x",
          (unsigned)rep.build_id_hash,
          (unsigned)fnv_from_bytes(cava_build_id(), std::strlen(cava_build_id())));

    uint32_t sum = 0;
    for (int32_t i = 0; i < entries && i < kStructExpectCount; ++i) {
        const CavaLayoutEntry& e = rep.entries[i];
        const StructExpect& x = kExpect[i];
        const MechExpect& m = kMech[i];
        char tag[64];
        std::snprintf(tag, sizeof(tag), "%s", x.name);
        check(e.abi_version == CAVA_ABI_VERSION, "%s.entry.abi_version=%d", tag, (int)e.abi_version);
        check(e.struct_size == x.size, "%s.struct_size=%llu（硬编码期望 %llu）",
              tag, (unsigned long long)e.struct_size, (unsigned long long)x.size);
        check(e.struct_align == x.align, "%s.struct_align=%llu", tag, (unsigned long long)e.struct_align);
        check(e.field_count == (uint32_t)x.field_count, "%s.field_count=%u（期望 %d）",
              tag, (unsigned)e.field_count, (int)x.field_count);
        check(e.layout_hash != 0, "%s.layout_hash=0x%08x（非 0）", tag, (unsigned)e.layout_hash);
        uint32_t expect_hash = fnv_fields(e.field_offsets, e.field_sizes, (int32_t)x.field_count);
        check(e.layout_hash == expect_hash,
              "%s.layout_hash=0x%08x == 独立重算 0x%08x", tag, (unsigned)e.layout_hash, (unsigned)expect_hash);
        bool fields_ok = true;
        for (int32_t f = 0; f < x.field_count; ++f) {
            if (e.field_offsets[f] != x.fields[f].offset || e.field_sizes[f] != x.fields[f].size) {
                fields_ok = false;
                std::printf("         ^ 字段第 %d 个不符：%s off=%llu size=%llu（期望 %llu/%llu）\n",
                            (int)f, x.fields[f].name,
                            (unsigned long long)e.field_offsets[f], (unsigned long long)e.field_sizes[f],
                            (unsigned long long)x.fields[f].offset, (unsigned long long)x.fields[f].size);
            }
        }
        check(fields_ok, "%s 全部 %d 个字段 offset/size 与硬编码 x64 期望一致", tag, (int)x.field_count);

        /* 机械比对：直接拿 offsetof/sizeof 的表来对。手抄表和实现表"一起错"的时候只有它能发现
         * （CavaPathNode 漏登记 type/flags 那次就是这个坑）。*/
        check(m.count == x.field_count, "%s 机械表字段数=%d == 硬编码表字段数=%d",
              tag, (int)m.count, (int)x.field_count);
        check(e.struct_size == m.size && e.struct_align == m.align,
              "%s.struct_size/align=%llu/%llu == offsetof 机械值 %llu/%llu",
              tag, (unsigned long long)e.struct_size, (unsigned long long)e.struct_align,
              (unsigned long long)m.size, (unsigned long long)m.align);
        bool mech_ok = true;
        const int32_t mf = (m.count < x.field_count) ? m.count : x.field_count;
        for (int32_t f = 0; f < mf; ++f) {
            if (e.field_offsets[f] != m.fields[f].offset || e.field_sizes[f] != m.fields[f].size ||
                std::strcmp(x.fields[f].name, m.fields[f].name) != 0) {
                mech_ok = false;
                std::printf("         ^ 机械比对第 %d 个字段不符：报告=%llu/%llu 机械=%llu/%llu（名 %s vs %s）\n",
                            (int)f,
                            (unsigned long long)e.field_offsets[f], (unsigned long long)e.field_sizes[f],
                            (unsigned long long)m.fields[f].offset, (unsigned long long)m.fields[f].size,
                            x.fields[f].name, m.fields[f].name);
            }
        }
        check(mech_ok, "%s 全部字段与 offsetof/sizeof 机械值一致（含字段名顺序）", tag);
        sum += e.layout_hash;
    }
    std::printf("  layout_hash_sum = 0x%08x (%u)\n", (unsigned)sum, (unsigned)sum);
    check(sum == kExpectedLayoutSum,
          "layout_hash_sum=0x%08x == Java 侧对齐值 0x%08x（9 条 entry 的和）",
          (unsigned)sum, (unsigned)kExpectedLayoutSum);
    for (int32_t i = 0; i < entries && i < kStructExpectCount; ++i) {
        std::printf("    %-18s 0x%08x  size=%llu\n", kExpect[i].name,
                    (unsigned)rep.entries[i].layout_hash,
                    (unsigned long long)rep.entries[i].struct_size);
    }

    /* ---------------- 3. open ---------------- */
    section("3. cava_open");
    CavaOpenParams p;
    std::memset(&p, 0, sizeof(p));
    p.abi_version = CAVA_ABI_VERSION;
    p.flags = CAVA_OPEN_FLAG_DETERMINISTIC;
    p.layout_hash_sum = (uint64_t)sum;

    int64_t h = 12345; /* 故意先塞垃圾，看失败路径会不会清成 0 */
    CavaOpenResult r;
    std::memset(&r, 0, sizeof(r));
    int32_t st = cava_open(&p, &h, &r);
    check(st == CAVA_OK && h != 0, "正确 layout_hash_sum -> status=%d handle=0x%llx",
          (int)st, (unsigned long long)h);
    check(r.status == CAVA_OK && r.native_layout_sum == (uint64_t)sum,
          "out_result.native_layout_sum=0x%08x 与 Java 侧算的和一致", (unsigned)r.native_layout_sum);
    check(r.abi_version == CAVA_ABI_VERSION, "out_result.abi_version=%d", (int)r.abi_version);

    const int64_t good_handle = h;

    CavaOpenParams bad = p;
    bad.layout_hash_sum = (uint64_t)sum ^ 1u;
    int64_t h2 = 999;
    CavaOpenResult r2;
    std::memset(&r2, 0, sizeof(r2));
    st = cava_open(&bad, &h2, &r2);
    check(st == CAVA_ERR_LAYOUT && h2 == 0, "错 layout_hash_sum -> status=%d handle=%lld（期望 -2 / 0）",
          (int)st, (long long)h2);
    check(r2.native_layout_sum == (uint64_t)sum, "失败时仍回填 native_layout_sum=0x%08x（诊断用）",
          (unsigned)r2.native_layout_sum);
    check(r2.status == CAVA_ERR_LAYOUT, "out_result.status=%d", (int)r2.status);

    CavaOpenParams badabi = p;
    badabi.abi_version = CAVA_ABI_VERSION + 1;
    int64_t h3 = 777;
    st = cava_open(&badabi, &h3, nullptr);
    check(st == CAVA_ERR_ABI_VERSION && h3 == 0, "错 abi_version -> status=%d handle=%lld（期望 -1 / 0）",
          (int)st, (long long)h3);

    int64_t h4 = 555;
    st = cava_open(nullptr, &h4, nullptr);
    check(st == CAVA_ERR_NULL && h4 == 0, "params=NULL -> status=%d handle=%lld（期望 -3 / 0）",
          (int)st, (long long)h4);

    st = cava_open(&p, nullptr, nullptr);
    check(st == CAVA_ERR_NULL, "out_handle=NULL -> status=%d（期望 -3，且不崩）", (int)st);

    int64_t h5 = 0;
    st = cava_open(&p, &h5, nullptr);
    check(st == CAVA_OK && h5 != 0 && h5 != good_handle, "第二个句柄 0x%llx 与第一个 0x%llx 不同",
          (unsigned long long)h5, (unsigned long long)good_handle);

    /* ---------------- 4. close ---------------- */
    section("4. cava_close（幂等 / 非法句柄）");
    check(cava_close(good_handle) == CAVA_OK, "close(合法句柄) == CAVA_OK");
    check(cava_close(good_handle) == CAVA_ERR_NULL, "重复 close 同一个句柄 == CAVA_ERR_NULL（不崩）");
    check(cava_close(0) == CAVA_ERR_NULL, "close(0) == CAVA_ERR_NULL");
    check(cava_close(h5) == CAVA_OK, "close(第二个句柄) == CAVA_OK");
    check(cava_close(h5) == CAVA_ERR_NULL, "重复 close(第二个) == CAVA_ERR_NULL");
    check(cava_close(-1) == CAVA_ERR_ARG, "close(-1) == CAVA_ERR_ARG");
    check(cava_close(INT64_MIN) == CAVA_ERR_ARG, "close(INT64_MIN) == CAVA_ERR_ARG");
    check(cava_close(0x7FFFFFFFFFFFFFFFLL) == CAVA_ERR_ARG, "close(0x7FFF...FFFF) == CAVA_ERR_ARG");
    check(cava_close(0x0000000100001000LL) == CAVA_ERR_ARG, "close(槽位越界 0x1000) == CAVA_ERR_ARG");
    check(cava_close(0x0000000200000001LL) == CAVA_ERR_ARG, "close(未来 generation) == CAVA_ERR_ARG");
    check(cava_close(0x0000000000000001LL) == CAVA_ERR_NULL, "close(槽位0 陈旧 gen0) == CAVA_ERR_NULL");
    /* 反复 open/close 之后仍然正常（generation 单调）*/
    bool churn_ok = true;
    int64_t prev = 0;
    for (int i = 0; i < 64; ++i) {
        int64_t a = 0;
        int64_t b = 0;
        if (cava_open(&p, &a, nullptr) != CAVA_OK || a == 0 || a == prev) {
            churn_ok = false;
            break;
        }
        if (cava_open(&p, &b, nullptr) != CAVA_OK || b == 0 || b == a) {
            churn_ok = false;
            break;
        }
        if (cava_close(a) != CAVA_OK || cava_close(b) != CAVA_OK) {
            churn_ok = false;
            break;
        }
        if (cava_close(a) != CAVA_ERR_NULL) { /* 陈旧句柄必须被拒 */
            churn_ok = false;
            break;
        }
        prev = a; /* 下一轮复用同一个槽位，句柄必须因 generation+1 而不同 */
    }
    check(churn_ok, "连续 64 轮 open/open/close/close：句柄不重复、陈旧句柄被拒");

    /* ---------------- 5. 饱和转换 ---------------- */
    section("5. cava_d2i_sat / cava_d2l_sat");
    int i32_bad = 0, i32_n = 0;
    for (size_t i = 0; i < sizeof(kI32) / sizeof(kI32[0]); ++i) {
        ++i32_n;
        int32_t got = cava_d2i_sat(kI32[i].v);
        if (got != kI32[i].expect) {
            ++i32_bad;
            std::printf("         ^ d2i(%s) = %d，期望 %d\n", kI32[i].label, (int)got, (int)kI32[i].expect);
        }
    }
    for (size_t i = 0; i < sizeof(kNanBits) / sizeof(kNanBits[0]); ++i) {
        ++i32_n;
        double v = cava_double_of_bits(kNanBits[i]);
        int32_t got = cava_d2i_sat(v);
        if (got != 0) {
            ++i32_bad;
            std::printf("         ^ d2i(NaN 0x%016llx) = %d，期望 0\n",
                        (unsigned long long)kNanBits[i], (int)got);
        }
    }
    ++i32_n;
    if (cava_d2i_sat(cava_double_of_bits(kInfBits[0])) != 2147483647) { ++i32_bad; std::printf("         ^ d2i(+inf) 错\n"); }
    ++i32_n;
    if (cava_d2i_sat(cava_double_of_bits(kInfBits[1])) != (-2147483647 - 1)) { ++i32_bad; std::printf("         ^ d2i(-inf) 错\n"); }
    check(i32_bad == 0, "d2i_sat 全部 %d 例通过（含 NaN/-0/inf/INT_MIN-1/INT_MAX+1/超大）", i32_n);

    int i64_bad = 0, i64_n = 0;
    for (size_t i = 0; i < sizeof(kI64) / sizeof(kI64[0]); ++i) {
        ++i64_n;
        int64_t got = cava_d2l_sat(kI64[i].v);
        if (got != kI64[i].expect) {
            ++i64_bad;
            std::printf("         ^ d2l(%s) = %lld，期望 %lld\n", kI64[i].label,
                        (long long)got, (long long)kI64[i].expect);
        }
    }
    for (size_t i = 0; i < sizeof(kNanBits) / sizeof(kNanBits[0]); ++i) {
        ++i64_n;
        if (cava_d2l_sat(cava_double_of_bits(kNanBits[i])) != 0) {
            ++i64_bad;
            std::printf("         ^ d2l(NaN 0x%016llx) != 0\n", (unsigned long long)kNanBits[i]);
        }
    }
    ++i64_n;
    if (cava_d2l_sat(cava_double_of_bits(kInfBits[0])) != 9223372036854775807LL) { ++i64_bad; std::printf("         ^ d2l(+inf) 错\n"); }
    ++i64_n;
    if (cava_d2l_sat(cava_double_of_bits(kInfBits[1])) != (-9223372036854775807LL - 1)) { ++i64_bad; std::printf("         ^ d2l(-inf) 错\n"); }
    check(i64_bad == 0, "d2l_sat 全部 %d 例通过（含 Long.MIN/MAX 边界）", i64_n);

    /* ---------------- 6. 位模式往返 ---------------- */
    section("6. bits_of_double / double_of_bits");
    check(cava_bits_of_double(1.0) == 0x3FF0000000000000ull, "bits_of_double(1.0)=0x%016llx",
          (unsigned long long)cava_bits_of_double(1.0));
    check(cava_double_of_bits(0x3FF0000000000000ull) == 1.0, "double_of_bits(0x3FF0000000000000)==1.0");
    check(cava_bits_of_double(cava_double_of_bits(0x7FF8000000000000ull)) == 0x7FF8000000000000ull,
          "NaN 载荷过境：0x7FF8000000000000 往返一致（后续 NaN 用例才有意义）");

    uint64_t rs = 0x0F1E2D3C4B5A6978ull;
    int64_t rt_n = 0, rt_bad = 0;
    uint64_t first_bad = 0, first_bad_got = 0;
    for (int i = 0; i < 20000; ++i) {
        uint64_t bits = splitmix64(rs);
        if (i < (int)(sizeof(kNanBits) / sizeof(kNanBits[0]))) {
            bits = kNanBits[i];
        }
        if (i == 10) { bits = 0x0000000000000001ull; }    /* 最小次正规 */
        if (i == 11) { bits = 0x000FFFFFFFFFFFFFull; }    /* 最大次正规 */
        if (i == 12) { bits = 0x8000000000000000ull; }    /* -0.0 */
        ++rt_n;
        uint64_t back = cava_bits_of_double(cava_double_of_bits(bits));
        if (back != bits) {
            ++rt_bad;
            if (rt_bad == 1) { first_bad = bits; first_bad_got = back; }
        }
    }
    if (rt_bad != 0) {
        std::printf("         ^ 首个不符：in=0x%016llx out=0x%016llx\n",
                    (unsigned long long)first_bad, (unsigned long long)first_bad_got);
    }
    check(rt_bad == 0, "随机位模式往返 %lld 例全部一致（含 NaN 载荷 / 次正规 / ±0）", (long long)rt_n);

    /* ---------------- 7. 金丝雀 ---------------- */
    section("7. cava_abi_touch（金丝雀）");
    int64_t t1 = cava_abi_touch();
    int64_t t2 = cava_abi_touch();
    check(t2 == t1 + 1, "touch 递增：%lld -> %lld", (long long)t1, (long long)t2);

    /* ---------------- 8. 非法参数不崩 ---------------- */
    section("8. 非法参数（负路径）");
    check(cava_layout_report(nullptr) == CAVA_ERR_NULL,
          "cava_layout_report(NULL) == CAVA_ERR_NULL（SAFE 构建下 stderr 会打一行断言）");

    /* ---------------- 汇总 ---------------- */
    std::printf("\n=== SUMMARY: %d passed, %d failed ===\n", g_pass, g_fail);
    if (g_fail != 0) {
        std::printf("RESULT: FAIL\n");
        return 1;
    }
    std::printf("RESULT: PASS\n");
    return 0;
}
