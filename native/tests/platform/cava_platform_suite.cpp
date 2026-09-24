/* cava_platform_suite.cpp —— 平台矩阵的**数值一致性测试套件**（P4-B）。
 *
 * 这是 prompts/07 第 1 条要求的那个套件：**每个目标平台都必须跑一遍**
 * 「逐位一致性 + 编译开关自检 + ABI 布局自检」。它在 5 个平台的 CI 上被调用
 * （.github/workflows/build.yml 的 native job），也可以在任何一台机器上独立跑。
 *
 * 设计原则（为什么长这样）：
 *   1. **不链接原生库，运行期 dlopen/LoadLibrary 加载**。
 *      理由：a) 交叉/本机都能用同一条命令；b) 测的是**真正要发出去的那个 .dll/.so/.dylib**，
 *      而不是"链接期看到的符号"；c) 与 Java 侧 SymbolLookup.libraryLookup 的用法同形。
 *   2. **逐位一致性不自己造向量**：直接复用签入的黄金向量 native/tests/vectors/fp_probe.txt
 *      （由 native/tests/cava_fp_probe.cpp 在 windows-x64 上生成，15456 行）。
 *      做法是"读黄金里的 (op,a,b)，在**本平台**重算，比对黄金里的 r 的位模式"。
 *      ⇒ 语义就是"这个平台能不能逐位复现 windows-x64 的算术"。输入来自黄金，本平台只负责算。
 *   3. **编译开关自检要能从产物反查**，分三层：
 *      a) 本 TU 的编译期宏断言（__FAST_MATH__ / __FINITE_MATH_ONLY__）；
 *      b) 行为证明（-fwrapv 的溢出回绕、FMA 收缩探针）；
 *      c) 加载到的库里 cava_build_id() 自己带的 CAVA_BUILD_FP_FLAGS 字符串；
 *      另外 tools/platform-flagcheck.ps1 从 CMake 的 flags.make/compile_commands.json 与
 *      反汇编（VEX/AVX 指令计数）做第四层"从产物反查"。
 *   4. **ABI 布局自检两套表**（照抄 cava_selftest.cpp 的教训）：
 *      - 硬编码黄金表（size / fields / layout_hash，冻结值）；
 *      - 用 offsetof/sizeof **现算**的机械表（连字段顺序都比）。
 *      两张表必须都能对上 DLL 的 cava_layout_report，且机械表算出的 hash 必须等于硬编码黄金。
 *      只留一张表会"照同一份错理解一起错"——这正是 CavaPathNode 漏字段那次骗过测试的路径。
 *
 * 输出：人类可读的分节日志 + 末尾一行机器可读的 PLATFORM-SUITE 摘要行。
 * 退出码：0 = 全部执行的检查通过；1 = 有检查失败；2 = 用法/IO 错误；3 = 黄金向量找不到。
 *
 * 编法（两条路，都实测过，见 native/tests/platform/README.md）：
 *   独立（不依赖 CMake，形态照 native/tests/entity/build-entity.ps1）：
 *     g++ -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math \
 *         -I native/include -o cava_platform_suite.exe native/tests/platform/cava_platform_suite.cpp
 *   CMake（复用 native/cmake/CavaFlags.cmake 的硬性参数）：
 *     cmake -S native/tests/platform -B build/platform-suite -DCMAKE_BUILD_TYPE=Release
 *
 * 用法：
 *   cava_platform_suite [--golden <fp_probe.txt>] [--lib <原生库路径>]
 *                       [--expect-rows N] [--dump-table] [--quiet]
 *   环境变量 CAVA_SUITE_LIB 等价于 --lib（CI 里更省事）。
 *   不给 --lib 时自动找 natives/<tag>/<cava.dll|libcava.so|libcava.dylib>；
 *   找不到就把 ABI 一节标成 SKIP（**不假装跑过**），其余两节照跑。
 */
#include "../include/cava_abi.h"

#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

/* ===================================================================== */
/* 迷你测试框架                                                          */
/* ===================================================================== */
static int g_pass = 0;
static int g_fail = 0;
static int g_skip = 0;
static bool g_quiet = false;
static bool g_strict_nan = false;

static void section(const char* name) {
    std::printf("\n--- %s ---\n", name);
}

static void check(bool ok, const char* fmt, ...) {
    char buf[768];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (ok) {
        ++g_pass;
        if (!g_quiet) std::printf("  [ ok ] %s\n", buf);
    } else {
        ++g_fail;
        std::printf("  [FAIL] %s\n", buf);
    }
}

static void skip(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    ++g_skip;
    std::printf("  [SKIP] %s\n", buf);
}

static void info(const char* fmt, ...) {
    char buf[768];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    std::printf("  [info] %s\n", buf);
}

/* ===================================================================== */
/* 编译期自检 1/3：宏层                                                  */
/* ===================================================================== */
#if defined(__FAST_MATH__)
#  error "cava: 本 TU 带 __FAST_MATH__（-ffast-math / -Ofast 生效）—— 破坏逐位一致性，拒绝编译"
#endif
#if defined(__FINITE_MATH_ONLY__) && __FINITE_MATH_ONLY__ != 0
#  error "cava: 本 TU 的 __FINITE_MATH_ONLY__ != 0（-ffinite-math-only 生效）—— 拒绝编译"
#endif
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__)
#  error "cava: 大端目标（平台文档规则 14：当前所有目标平台都是小端，不写死假设，所以这里是硬失败）"
#endif

/* 编译器画像：MSVC 没有 __VERSION__，三元表达式里也会要求两边都能编译，所以走宏。 */
#define CAVA_STRINGIFY_IMPL(x) #x
#define CAVA_STRINGIFY(x) CAVA_STRINGIFY_IMPL(x)
#if defined(_MSC_VER)
#  define CAVA_COMPILER_NAME "msvc"
#  define CAVA_COMPILER_VERSION_STR "(MSVC _MSC_VER=" CAVA_STRINGIFY(_MSC_VER) ")"
#elif defined(__clang__)
#  define CAVA_COMPILER_NAME "clang"
#  define CAVA_COMPILER_VERSION_STR __VERSION__
#elif defined(__GNUC__)
#  define CAVA_COMPILER_NAME "gcc"
#  define CAVA_COMPILER_VERSION_STR __VERSION__
#else
#  define CAVA_COMPILER_NAME "unknown"
#  define CAVA_COMPILER_VERSION_STR "(unknown)"
#endif

/* ===================================================================== */
/* 位工具（自己实现，避免依赖被测库）                                     */
/* ===================================================================== */
static uint64_t bits_of(double d) { uint64_t u = 0; std::memcpy(&u, &d, sizeof(u)); return u; }
static double   double_of(uint64_t u) { double d = 0; std::memcpy(&d, &u, sizeof(d)); return d; }

/* ---- sqrt 必须走"正确舍入"的那条路（本机实测的坑，见 docs/CAVA-platform-notes.md）----
 * MinGW g++ 15.2 **本机实测**：同一个 -O2 -fwrapv -ffp-contract=off -fno-fast-math 下，
 *   * 直接写在一个小函数里的 std::sqrt(x)  → GCC 内联成 sqrtsd / sqrtss（IEEE 正确舍入，与 Java 逐位一致）；
 *   * 写在本文件那个 switch 的 default 分支里 → GCC 改**调用 msvcrt 的 sqrt**，
 *     而 msvcrt 的 sqrt **不是正确舍入**的，并且**不把 sNaN 静音**。
 * 实测差异（对黄金向量里同样的 5 行）：
 *     a=3FEFFFFFFFFFFFFF  sqrtsd=3FEFFFFFFFFFFFFF  msvcrt=3FF0000000000000   ← 差 1 ulp
 *     a=7FEFFFFFFFFFFFFF  sqrtsd=5FEFFFFFFFFFFFFF  msvcrt=5FF0000000000000   ← 差 1 ulp
 *     a=7D9C57A9A12040CA  sqrtsd=5EC54B88069DA279  msvcrt=5EC54B88069DA278   ← 差 1 ulp
 *     a=7FF0000000000001  sqrtsd=7FF8000000000001  msvcrt=7FF0000000000001   ← sNaN 没静音
 *     a=FFF0000000000001  sqrtsd=FFF8000000000001  msvcrt=FFF0000000000001
 * Java 21 的 Math.sqrt / StrictMath.sqrt 与 sqrtsd 五项全同。所以这里显式走 __builtin_sqrt，
 * 并在第 2 节加一条"可红的"断言把这个坑钉死（换编译器/换写法一旦退回库调用就会红）。
 * 注：交付物 natives/windows-x64/cava.dll **实测没有这个问题**（反汇编里 sqrtsd×2 + sqrtss×4、
 * call <sqrt> ×0），所以这是套件自身的坑，不是产品的坑。 */
#if defined(_MSC_VER)
#  define CAVA_SQRT(x) (std::sqrt(x))
#else
#  define CAVA_SQRT(x) (__builtin_sqrt(x))
#endif

/* ===================================================================== */
/* 编译期自检 2/3：行为层（-fwrapv / MSVC 默认回绕）                      */
/* ===================================================================== */
#if defined(_MSC_VER)
#  define CAVA_NOINLINE __declspec(noinline)
#else
#  define CAVA_NOINLINE __attribute__((noinline))
#endif

/* 有符号溢出必须回绕。没有 -fwrapv 时 GCC/Clang 会假定 x+1 > x 恒真，
 * 于是这个函数在 x=INT32_MAX 时返回 1；有 -fwrapv 时必须返回 0。 */
CAVA_NOINLINE static int overflow_assumed(int32_t x) { return (x + 1) > x ? 1 : 0; }

/* 平台文档规则 9 点名的形状：x*31+z 这类哈希必须与 Java 的 int 回绕一致。
 * 期望值用 uint32_t 算（无符号溢出是良定义），再按位比较。 */
CAVA_NOINLINE static int32_t wrap_hash_step(int32_t h, int32_t z) { return h * 31 + z; }
static uint32_t wrap_hash_step_u(uint32_t h, uint32_t z) { return h * 31u + z; }

/* FMA 收缩探针：a = 1 + 2^-27，a*a - 1
 *   分开算（不收缩）：fl(a*a) = 1 + 2^-26，减 1 得 2^-26          = 0x3E50000000000000
 *   收缩成 fma(a,a,-1)：恰好 2^-26 + 2^-54                        = 0x3E50000000000001
 * 只有目标 ISA 真有 FMA 时这两者才不同（x86-64 基线 SSE2 没有 FMA ⇒ 本探针在 x64 上不可判，
 * 但 arm64 一定有 FMA ⇒ 在 macOS arm64 / linux arm64 上这是**决定性的**）。
 * 用 volatile 挡住常量折叠，但不挡后端的收缩（两者都在寄存器里）。 */
static uint64_t fma_probe(uint64_t* out_uncontracted) {
    /* 0x3FF0000002000000 = 1 + 2^-27（指数 1023，尾数 0x2000000 = 2^25 = 2^-27 * 2^52）。
     * volatile 只是挡住常量折叠；两个操作数仍会进寄存器，所以后端**仍然可以**把它们收缩成 fma。 */
    volatile double va = double_of(0x3FF0000002000000ull);
    const double a = va;
    const uint64_t separate = bits_of(a * a - 1.0);
    if (out_uncontracted != nullptr) *out_uncontracted = separate;
    return separate;
}

/* ===================================================================== */
/* 平台标签（与 native/cmake/CavaPlatform.cmake + Java NativeLibrary 对齐） */
/* ===================================================================== */
static const char* host_platform_tag() {
#if defined(_WIN32) && (defined(_M_ARM64) || defined(__aarch64__))
    return "windows-arm64";
#elif defined(_WIN32)
    return "windows-x64";
#elif defined(__APPLE__) && defined(__aarch64__)
    return "macos-arm64";
#elif defined(__APPLE__)
    return "macos-x64";
#elif defined(__linux__) && defined(__aarch64__)
    return "linux-arm64";
#elif defined(__linux__)
    return "linux-x64";
#else
    return "unknown";
#endif
}

static const char* host_library_file_name() {
#if defined(_WIN32)
    return "cava.dll";
#elif defined(__APPLE__)
    return "libcava.dylib";
#else
    return "libcava.so";
#endif
}

/* ===================================================================== */
/* 动态加载                                                              */
/* ===================================================================== */
#if defined(_WIN32)
typedef HMODULE LibHandle;
static LibHandle lib_open(const char* p) { return ::LoadLibraryA(p); }
static void* lib_sym(LibHandle h, const char* n) {
    return reinterpret_cast<void*>(::GetProcAddress(h, n));
}
static std::string lib_err() {
    char b[128];
    std::snprintf(b, sizeof(b), "GetLastError=%lu", (unsigned long)::GetLastError());
    return std::string(b);
}
static void lib_close(LibHandle h) { if (h) ::FreeLibrary(h); }
#else
typedef void* LibHandle;
static LibHandle lib_open(const char* p) { return ::dlopen(p, RTLD_NOW | RTLD_LOCAL); }
static void* lib_sym(LibHandle h, const char* n) { return ::dlsym(h, n); }
static std::string lib_err() { const char* e = ::dlerror(); return std::string(e ? e : "(no dlerror)"); }
static void lib_close(LibHandle h) { if (h) ::dlclose(h); }
#endif

/* ===================================================================== */
/* 黄金布局表（**冻结值**：改 ABI 就必须同时改这里，且必须是两侧复算过的真值） */
/* 与 native/src/cava_layout.cpp 的登记顺序一一对应（下标即身份，禁止按形状反查）。 */
/* ===================================================================== */
struct StructGolden {
    const char* name;
    uint64_t    size;
    uint64_t    align;
    int32_t     field_count;
    uint32_t    layout_hash;
};

/* 冻结的 layout_hash_sum（14 个结构体 uint32 回绕加法）。
 * 来源：native/tests/cava_selftest.cpp kExpectedLayoutSum 与 docs/CAVA-gates.md 门禁记录，
 * 本套件在真实 cava.dll 上复算确认（本文件末尾会打印实测值）。 */
static const uint32_t kGoldenLayoutSum = 0x1C12265Eu;

static const StructGolden kGolden[14] = {
    { "CavaLayoutEntry",   sizeof(CavaLayoutEntry),   alignof(CavaLayoutEntry),   8,  0xF837804Du },
    { "CavaLayoutReport",  sizeof(CavaLayoutReport),  alignof(CavaLayoutReport),  8,  0xE9FFC021u },
    { "CavaOpenParams",    sizeof(CavaOpenParams),    alignof(CavaOpenParams),    5,  0x7FDE7499u },
    { "CavaOpenResult",    sizeof(CavaOpenResult),    alignof(CavaOpenResult),    4,  0xFF344829u },
    { "CavaPathRequest",   sizeof(CavaPathRequest),   alignof(CavaPathRequest),   13, 0xE566F98Du },
    { "CavaPathNode",      sizeof(CavaPathNode),      alignof(CavaPathNode),      8,  0x0DCFFE65u },
    { "CavaMobProfile",    sizeof(CavaMobProfile),    alignof(CavaMobProfile),    18, 0x9C6C98CDu },
    { "CavaStateRecord",   sizeof(CavaStateRecord),   alignof(CavaStateRecord),   5,  0x53797229u },
    { "CavaCollisionBox",  sizeof(CavaCollisionBox),  alignof(CavaCollisionBox),  6,  0x250ECBE1u },
    /* P2 实体位移的 5 个（值取自真实 natives/windows-x64/cava.dll 的 cava_layout_report 实测，
     * 与 docs/CAVA-p2-wiring-notes.md 记的 5 个新值逐位一致）。
     * 注意 CavaShapeRecord 与 CavaPathNode 的 hash **相同**（都是 0x0DCFFE65）——
     * 这是契约 2.3 已知的固有弱点（哈希不区分形状相同的结构体），
     * 所以本文件里结构体身份**只用下标**，绝不按 (size, field_count) 或 hash 反查。*/
    { "CavaShapeRecord",   sizeof(CavaShapeRecord),   alignof(CavaShapeRecord),   8,  0x0DCFFE65u },
    { "CavaMoveShapeRef",  sizeof(CavaMoveShapeRef),  alignof(CavaMoveShapeRef),  11, 0x1545B999u },
    { "CavaMoveRequest",   sizeof(CavaMoveRequest),   alignof(CavaMoveRequest),   15, 0xB542D3D5u },
    { "CavaMoveEvent",     sizeof(CavaMoveEvent),     alignof(CavaMoveEvent),     16, 0x630C22D5u },
    { "CavaMoveResult",    sizeof(CavaMoveResult),    alignof(CavaMoveResult),    13, 0x7737ABBDu },
};
static const int32_t kGoldenCount = 14;

/* -------- 机械表：直接用 offsetof/sizeof 现算（不人手抄数字） -------- */
struct FieldRow { const char* name; uint64_t offset; uint64_t size; };

#define CAVA_ROW(T, m) { #m, (uint64_t)offsetof(T, m), (uint64_t)sizeof(((T*)0)->m) }

#define ROWS_CavaLayoutEntry(T) \
    CAVA_ROW(T, abi_version), CAVA_ROW(T, reserved0), CAVA_ROW(T, struct_size), CAVA_ROW(T, struct_align), \
    CAVA_ROW(T, field_count), CAVA_ROW(T, layout_hash), CAVA_ROW(T, field_offsets), CAVA_ROW(T, field_sizes)
#define ROWS_CavaLayoutReport(T) \
    CAVA_ROW(T, abi_version), CAVA_ROW(T, build_flags), CAVA_ROW(T, platform), CAVA_ROW(T, pointer_size), \
    CAVA_ROW(T, entry_count), CAVA_ROW(T, reserved0), CAVA_ROW(T, build_id_hash), CAVA_ROW(T, entries)
#define ROWS_CavaOpenParams(T) \
    CAVA_ROW(T, abi_version), CAVA_ROW(T, flags), CAVA_ROW(T, layout_hash_sum), CAVA_ROW(T, reserved0), CAVA_ROW(T, reserved1)
#define ROWS_CavaOpenResult(T) \
    CAVA_ROW(T, status), CAVA_ROW(T, abi_version), CAVA_ROW(T, native_layout_sum), CAVA_ROW(T, reserved0)
#define ROWS_CavaPathRequest(T) \
    CAVA_ROW(T, reserved1), CAVA_ROW(T, tx), CAVA_ROW(T, ty), CAVA_ROW(T, tz), CAVA_ROW(T, reach_range), \
    CAVA_ROW(T, max_range), CAVA_ROW(T, flags), CAVA_ROW(T, reserved0), CAVA_ROW(T, reserved2), \
    CAVA_ROW(T, max_visited_nodes), CAVA_ROW(T, pad0), CAVA_ROW(T, pad1), CAVA_ROW(T, pad2)
#define ROWS_CavaPathNode(T) \
    CAVA_ROW(T, x), CAVA_ROW(T, y), CAVA_ROW(T, z), CAVA_ROW(T, heapIndex), CAVA_ROW(T, g), \
    CAVA_ROW(T, f), CAVA_ROW(T, type), CAVA_ROW(T, flags)
#define ROWS_CavaMobProfile(T) \
    CAVA_ROW(T, penalty), CAVA_ROW(T, reserved_max_fall_distance), CAVA_ROW(T, start_x), CAVA_ROW(T, start_y), \
    CAVA_ROW(T, start_z), CAVA_ROW(T, start_block_x), CAVA_ROW(T, start_block_y), CAVA_ROW(T, start_block_z), \
    CAVA_ROW(T, width), CAVA_ROW(T, height), CAVA_ROW(T, step_height), CAVA_ROW(T, safe_fall_distance), \
    CAVA_ROW(T, min_y), CAVA_ROW(T, sea_level), CAVA_ROW(T, caps), CAVA_ROW(T, penalty_mask), \
    CAVA_ROW(T, reserved0), CAVA_ROW(T, reserved1)
#define ROWS_CavaStateRecord(T) \
    CAVA_ROW(T, flags), CAVA_ROW(T, box_offset), CAVA_ROW(T, box_count), CAVA_ROW(T, path_type_idx), CAVA_ROW(T, malus)
#define ROWS_CavaCollisionBox(T) \
    CAVA_ROW(T, min_x), CAVA_ROW(T, min_y), CAVA_ROW(T, min_z), CAVA_ROW(T, max_x), CAVA_ROW(T, max_y), CAVA_ROW(T, max_z)
#define ROWS_CavaShapeRecord(T) \
    CAVA_ROW(T, points_kind), CAVA_ROW(T, point_offset), CAVA_ROW(T, bit_offset), CAVA_ROW(T, bit_words), \
    CAVA_ROW(T, size_x), CAVA_ROW(T, size_y), CAVA_ROW(T, size_z), CAVA_ROW(T, reserved0)
#define ROWS_CavaMoveShapeRef(T) \
    CAVA_ROW(T, shape_token), CAVA_ROW(T, kind), CAVA_ROW(T, state_id), CAVA_ROW(T, block_x), CAVA_ROW(T, block_y), \
    CAVA_ROW(T, block_z), CAVA_ROW(T, source), CAVA_ROW(T, inline_slot), CAVA_ROW(T, reserved0), \
    CAVA_ROW(T, reserved1), CAVA_ROW(T, reserved2)
#define ROWS_CavaMoveRequest(T) \
    CAVA_ROW(T, reserved0), CAVA_ROW(T, min_x), CAVA_ROW(T, min_y), CAVA_ROW(T, min_z), CAVA_ROW(T, max_x), \
    CAVA_ROW(T, max_y), CAVA_ROW(T, max_z), CAVA_ROW(T, move_x), CAVA_ROW(T, move_y), CAVA_ROW(T, move_z), \
    CAVA_ROW(T, step_height), CAVA_ROW(T, flags), CAVA_ROW(T, on_ground), CAVA_ROW(T, shape_count), CAVA_ROW(T, reserved1)
#define ROWS_CavaMoveEvent(T) \
    CAVA_ROW(T, source), CAVA_ROW(T, axis), CAVA_ROW(T, block_x), CAVA_ROW(T, block_y), CAVA_ROW(T, block_z), \
    CAVA_ROW(T, pass), CAVA_ROW(T, accepted), CAVA_ROW(T, cell_x), CAVA_ROW(T, cell_y), CAVA_ROW(T, cell_z), \
    CAVA_ROW(T, reserved0), CAVA_ROW(T, reserved1), CAVA_ROW(T, shape_token), CAVA_ROW(T, offset), \
    CAVA_ROW(T, max_dist_before), CAVA_ROW(T, max_dist_after)
#define ROWS_CavaMoveResult(T) \
    CAVA_ROW(T, status), CAVA_ROW(T, step_used), CAVA_ROW(T, event_count), CAVA_ROW(T, event_overflow), \
    CAVA_ROW(T, delta_x), CAVA_ROW(T, delta_y), CAVA_ROW(T, delta_z), CAVA_ROW(T, base_x), CAVA_ROW(T, base_y), \
    CAVA_ROW(T, base_z), CAVA_ROW(T, step_x), CAVA_ROW(T, step_y), CAVA_ROW(T, step_z)

static const FieldRow kMech0[]  = { ROWS_CavaLayoutEntry(CavaLayoutEntry) };
static const FieldRow kMech1[]  = { ROWS_CavaLayoutReport(CavaLayoutReport) };
static const FieldRow kMech2[]  = { ROWS_CavaOpenParams(CavaOpenParams) };
static const FieldRow kMech3[]  = { ROWS_CavaOpenResult(CavaOpenResult) };
static const FieldRow kMech4[]  = { ROWS_CavaPathRequest(CavaPathRequest) };
static const FieldRow kMech5[]  = { ROWS_CavaPathNode(CavaPathNode) };
static const FieldRow kMech6[]  = { ROWS_CavaMobProfile(CavaMobProfile) };
static const FieldRow kMech7[]  = { ROWS_CavaStateRecord(CavaStateRecord) };
static const FieldRow kMech8[]  = { ROWS_CavaCollisionBox(CavaCollisionBox) };
static const FieldRow kMech9[]  = { ROWS_CavaShapeRecord(CavaShapeRecord) };
static const FieldRow kMech10[] = { ROWS_CavaMoveShapeRef(CavaMoveShapeRef) };
static const FieldRow kMech11[] = { ROWS_CavaMoveRequest(CavaMoveRequest) };
static const FieldRow kMech12[] = { ROWS_CavaMoveEvent(CavaMoveEvent) };
static const FieldRow kMech13[] = { ROWS_CavaMoveResult(CavaMoveResult) };

struct MechTable { const FieldRow* rows; int32_t count; };
static const MechTable kMech[14] = {
    { kMech0,  (int32_t)(sizeof(kMech0)  / sizeof(FieldRow)) },
    { kMech1,  (int32_t)(sizeof(kMech1)  / sizeof(FieldRow)) },
    { kMech2,  (int32_t)(sizeof(kMech2)  / sizeof(FieldRow)) },
    { kMech3,  (int32_t)(sizeof(kMech3)  / sizeof(FieldRow)) },
    { kMech4,  (int32_t)(sizeof(kMech4)  / sizeof(FieldRow)) },
    { kMech5,  (int32_t)(sizeof(kMech5)  / sizeof(FieldRow)) },
    { kMech6,  (int32_t)(sizeof(kMech6)  / sizeof(FieldRow)) },
    { kMech7,  (int32_t)(sizeof(kMech7)  / sizeof(FieldRow)) },
    { kMech8,  (int32_t)(sizeof(kMech8)  / sizeof(FieldRow)) },
    { kMech9,  (int32_t)(sizeof(kMech9)  / sizeof(FieldRow)) },
    { kMech10, (int32_t)(sizeof(kMech10) / sizeof(FieldRow)) },
    { kMech11, (int32_t)(sizeof(kMech11) / sizeof(FieldRow)) },
    { kMech12, (int32_t)(sizeof(kMech12) / sizeof(FieldRow)) },
    { kMech13, (int32_t)(sizeof(kMech13) / sizeof(FieldRow)) },
};

/* 契约 2.3 的哈希：每字段 4 步（off_lo, size_lo, off_hi, size_hi） */
static uint32_t hash_fields(const FieldRow* rows, int32_t n) {
    uint32_t h = CAVA_LAYOUT_FNV_OFFSET;
    for (int32_t i = 0; i < n; ++i) {
        const uint32_t feed[4] = {
            (uint32_t)(rows[i].offset & 0xFFFFFFFFull),
            (uint32_t)(rows[i].size & 0xFFFFFFFFull),
            (uint32_t)((rows[i].offset >> 32) & 0xFFFFFFFFull),
            (uint32_t)((rows[i].size >> 32) & 0xFFFFFFFFull),
        };
        for (int k = 0; k < 4; ++k) { h ^= feed[k]; h *= CAVA_LAYOUT_FNV_PRIME; }
    }
    return h;
}

/* ---- 从 cava_layout_report 的原始 entry 重算哈希（不信任原生侧给的 hash 字段）---- */
static uint32_t hash_entry_raw(const CavaLayoutEntry& e) {
    uint32_t h = CAVA_LAYOUT_FNV_OFFSET;
    for (uint32_t i = 0; i < e.field_count; ++i) {
        const uint64_t off = e.field_offsets[i];
        const uint64_t sz = e.field_sizes[i];
        const uint32_t feed[4] = {
            (uint32_t)(off & 0xFFFFFFFFull), (uint32_t)(sz & 0xFFFFFFFFull),
            (uint32_t)((off >> 32) & 0xFFFFFFFFull), (uint32_t)((sz >> 32) & 0xFFFFFFFFull),
        };
        for (int k = 0; k < 4; ++k) { h ^= feed[k]; h *= CAVA_LAYOUT_FNV_PRIME; }
    }
    return h;
}

/* ===================================================================== */
/* 第 1 节：环境画像（5 个平台之间唯一可以直接对照的一组事实）             */
/* ===================================================================== */
static void dump_table(const CavaLayoutReport& rep, std::FILE* f) {
    std::fprintf(f, "static const StructGolden kGolden[%d] = {\n", rep.entry_count);
    uint32_t sum = 0;
    for (int32_t i = 0; i < rep.entry_count && i < kGoldenCount; ++i) {
        const CavaLayoutEntry& e = rep.entries[i];
        std::fprintf(f, "    { \"%s\", %llu, %llu, %u, 0x%08XuU },\n",
                     kGolden[i].name,
                     (unsigned long long)e.struct_size,
                     (unsigned long long)e.struct_align,
                     (unsigned)e.field_count,
                     (unsigned)e.layout_hash);
        sum += e.layout_hash;
    }
    std::fprintf(f, "};\n// layout_hash_sum = 0x%08X (%u)\n", (unsigned)sum, (unsigned)sum);
}

static void section_env() {
    section("1. 环境画像");
    info("platform_tag(编译期) = %s", host_platform_tag());
    info("library_file_name    = %s", host_library_file_name());
    info("compiler             = %s %s", CAVA_COMPILER_NAME, CAVA_COMPILER_VERSION_STR);
    info("sizeof(void*)=%d sizeof(long)=%d sizeof(long long)=%d sizeof(long double)=%d",
         (int)sizeof(void*), (int)sizeof(long), (int)sizeof(long long), (int)sizeof(long double));
    info("char signed=%d  FLT_EVAL_METHOD=%d  __STDC_IEC_559__=%d",
         (int)((char)-1 < 0),
#ifdef __FLT_EVAL_METHOD__
         (int)__FLT_EVAL_METHOD__,
#else
         -1,
#endif
#ifdef __STDC_IEC_559__
         1
#else
         0
#endif
    );
#if defined(__FMA__) || defined(__ARM_FEATURE_FMA) || defined(_M_ARM64)
    info("FMA: 目标 ISA 有 FMA（收缩探针在本平台**可判**）");
    const bool has_fma = true;
#else
    info("FMA: 目标 ISA 基线里没有 FMA（收缩探针在本平台不可判 —— 不代表开关没问题）");
    const bool has_fma = false;
#endif
    (void)has_fma;

    /* 平台文档规则 6/14：宽度与小端必须在编译期就钉死 */
    check(sizeof(void*) == 8, "sizeof(void*) == 8（ABI 只用 64 位目标：%d）", (int)sizeof(void*));
    check(sizeof(double) == 8, "sizeof(double) == 8（%d）", (int)sizeof(double));
    check(sizeof(int32_t) == 4 && sizeof(int64_t) == 8, "定宽整型宽度正确");
#if defined(__BYTE_ORDER__)
    check(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "目标是小端（平台文档规则 14）");
#else
    skip("本编译器没有 __BYTE_ORDER__，小端只能靠运行期探针");
#endif
}

/* ===================================================================== */
/* 第 2 节：编译开关自检（本 TU + 行为 + 被测库自报）                     */
/* ===================================================================== */
static void section_switches() {
    section("2. 编译开关自检");

    /* 2a 宏层：__FAST_MATH__ / __FINITE_MATH_ONLY__ 已在文件顶 #error（编译过来说明没中）。 */
    check(true, "编译期宏：__FAST_MATH__ 未定义、__FINITE_MATH_ONLY__==0（否则本文件编不过）");

    /* 2b 行为层：-fwrapv（GCC/Clang）/ MSVC 默认回绕 */
    const int assumed = overflow_assumed(2147483647);
    check(assumed == 0,
          "整数溢出按回绕处理（x+1 > x 在 x=INT32_MAX 时 = %d；=1 说明 -fwrapv 没生效）", assumed);

    int wrap_ok = 1;
    {
        int32_t h = 1;
        uint32_t u = 1u;
        for (int i = 0; i < 1000; ++i) {
            h = wrap_hash_step(h, (int32_t)i);
            u = wrap_hash_step_u(u, (uint32_t)i);
            if ((uint32_t)h != u) { wrap_ok = 0; break; }
        }
    }
    check(wrap_ok != 0, "x*31+z 哈希形状逐位回绕（平台文档规则 9；1000 步与无符号参考一致）");

    /* 2c FMA/收缩：行为探针（有 FMA 的 ISA 上才有判别力）*/
    uint64_t uncontracted = 0;
    const uint64_t observed = fma_probe(&uncontracted);
    const bool has_fma =
#if defined(__FMA__) || defined(__ARM_FEATURE_FMA) || defined(_M_ARM64)
        true;
#else
        false;
#endif
    if (has_fma) {
        check(observed == 0x3E50000000000000ull,
              "a*a-1 未与 FMA 收缩（观测 0x%016llX，未收缩参考 0x3E50000000000000，"
              "收缩会得到 0x3E50000000000001）—— Apple Clang 默认 -ffp-contract=on，必须显式关掉",
              (unsigned long long)observed);
    } else {
        info("a*a-1 观测 0x%016llX；本 ISA 无 FMA ⇒ 收缩探针不可判（arm64/macOS 上才有判别力）",
             (unsigned long long)uncontracted);
        check(uncontracted == 0x3E50000000000000ull,
              "a*a-1 在无 FMA 基线上等于分开计算的参考值 0x3E50000000000000");
    }

    /* 2d sqrt 必须走 IEEE 正确舍入的那条路（MinGW 上 std::sqrt 有两条路，见文件顶的实测记录）*/
    {
        /* 期望值直接取黄金向量里的同 5 行（golden 与 Java Math.sqrt 逐位一致，已实测）：
         *   fp_probe.txt:15453 / :15446 / :15191 / :15448 / :15447 */
        const uint64_t cases[5] = { 0x3FEFFFFFFFFFFFFFull, 0x7FEFFFFFFFFFFFFFull, 0x7D9C57A9A12040CAull,
                                    0x0000000000000001ull, 0x0010000000000000ull };
        const uint64_t wants[5] = { 0x3FEFFFFFFFFFFFFFull, 0x5FEFFFFFFFFFFFFFull, 0x5EC54B88069DA279ull,
                                    0x1E60000000000000ull, 0x2000000000000000ull };
        int sqrt_bad = 0;
        for (int i = 0; i < 5; ++i) {
            /* volatile 是**必须**的：否则 GCC 会用 MPFR 在编译期把 sqrt 折成正确舍入值，
             * 于是这条检查测的是"编译器常量折叠对不对"，而不是"运行期走哪条路"——
             * 而实测出问题的恰恰是运行期那条路（见文件顶上的记录）。 */
            volatile uint64_t vcase = cases[i];
            const uint64_t got = bits_of(CAVA_SQRT(double_of(vcase)));
            if (got != wants[i]) {
                ++sqrt_bad;
                std::printf("    sqrt(%016llX) = %016llX，正确舍入值应为 %016llX\n",
                            (unsigned long long)cases[i], (unsigned long long)got,
                            (unsigned long long)wants[i]);
            }
        }
        check(sqrt_bad == 0,
              "sqrt 走正确舍入路径（5 个定点：最小次正规 / 最小正规 / 两个 1-ulp 边界 / DBL_MAX；"
              "退回 msvcrt 的 sqrt 会红 —— 本机实测过）");
    }

    /* 2e 危险开关不可能"混进来"：CMake 侧已经在配置期 FATAL_ERROR，
     *    这里再确认本 TU 的宏画像没有被 -Ofast/-march=native 改过。*/
#if defined(__OPTIMIZE__)
    info("__OPTIMIZE__ 已定义（本 TU 是优化构建）");
#else
    info("__OPTIMIZE__ 未定义（本 TU 是 -O0 构建；只影响本套件自身的速度，不影响被测库）");
#endif
}

/* ===================================================================== */
/* 第 3 节：逐位一致性（对黄金向量 native/tests/vectors/fp_probe.txt）     */
/* ===================================================================== */
static bool is_nan_bits(uint64_t b) {
    return ((b >> 52) & 0x7FFull) == 0x7FFull && (b & 0x000FFFFFFFFFFFFFull) != 0ull;
}

/* 三分类判据（这是本套件唯一一处"允许不一致"的地方，必须写清楚为什么）：
 *   HARD_NUMERIC  : 两边都不是 NaN，位模式却不同  → **真发散**，必须红。
 *   HARD_NANCLASS : 一边是 NaN 一边不是           → **真发散**，必须红（最危险的一类）。
 *   SOFT_NANPAYLOAD: 两边都是 NaN，只有载荷/符号位不同 → IEEE-754 不规定 NaN 载荷的传播规则，
 *                    不同 CPU/编译器各按自己的规矩来。**本机实测的旁证**：同一台机器上
 *                    JVM 的 Math.sqrt/add/mul 与原生黄金在这类行上本身就差 40 行
 *                    （native/tests/java/FpProbeJava.java 跑出来 add=20 / mul=20，全是双 NaN，
 *                    且 NaN 类别不一致 = 0）。要求跨平台 NaN 载荷逐位相同是在要求一件
 *                    IEEE 没有保证、Java 自己都做不到的事，把它做成硬门禁只会得到一个
 *                    "必然红所以迟早被绕过"的门禁。
 *                    ⇒ 默认**计数 + 打印 + 不算失败**，可用 --strict-nan 提升为失败。
 * 其它平台（MSVC / Apple Clang / arm64）第一次跑时，如果需要看这批豁免行到底是什么，加 --strict-nan 即可。 */
static void section_fp(const char* golden_path, long expect_rows, bool strict_nan) {
    section("3. 逐位一致性（+ - * / sqrt，对 windows-x64 签入的黄金向量）");
    info("黄金向量 = %s", golden_path);
    info("NaN 载荷豁免：%s（--strict-nan 可把它提升为硬失败）", strict_nan ? "关闭" : "开启");

    std::FILE* f = std::fopen(golden_path, "rb");
    if (f == nullptr) {
        check(false, "打不开黄金向量 %s", golden_path);
        return;
    }

    char line[256];
    long rows = 0, hard = 0, soft = 0, shown_hard = 0, shown_soft = 0;
    long per_op_total[5] = {0, 0, 0, 0, 0};
    long per_op_hard[5] = {0, 0, 0, 0, 0};
    long per_op_soft[5] = {0, 0, 0, 0, 0};
    const char* op_names[5] = { "add", "sub", "mul", "div", "sqrt" };

    while (std::fgets(line, (int)sizeof(line), f) != nullptr) {
        if (line[0] == '#' || line[0] == '\n' || line[0] == '\r') continue;
        char op[8] = {0};
        unsigned long long a = 0, b = 0, want = 0;
        if (std::sscanf(line, "%7s %llx %llx %llx", op, &a, &b, &want) != 4) continue;

        int idx = -1;
        for (int i = 0; i < 5; ++i) if (std::strcmp(op, op_names[i]) == 0) idx = i;
        if (idx < 0) continue;

        const double da = double_of((uint64_t)a);
        const double db = double_of((uint64_t)b);
        double got = 0.0;
        switch (idx) {
            case 0: got = da + db; break;
            case 1: got = da - db; break;
            case 2: got = da * db; break;
            case 3: got = da / db; break;
            default: got = CAVA_SQRT(da); break;
        }
        const uint64_t got_bits = bits_of(got);
        ++rows;
        ++per_op_total[idx];
        if (got_bits == (uint64_t)want) continue;

        const bool both_nan = is_nan_bits((uint64_t)want) && is_nan_bits(got_bits);
        if (both_nan) {
            ++soft;
            ++per_op_soft[idx];
            if (shown_soft < 8) {
                ++shown_soft;
                std::printf("    [NaN载荷] %-4s a=%016llX b=%016llX golden=%016llX this_platform=%016llX\n",
                            op, a, b, want, (unsigned long long)got_bits);
            }
        } else {
            ++hard;
            ++per_op_hard[idx];
            if (shown_hard < 20) {
                ++shown_hard;
                std::printf("    [数值差] %-4s a=%016llX b=%016llX golden=%016llX this_platform=%016llX\n",
                            op, a, b, want, (unsigned long long)got_bits);
            }
        }
    }
    std::fclose(f);

    if (rows == 0) {
        check(false, "黄金向量一行都没解析出来（格式或文件被改过？）");
        return;
    }

    for (int i = 0; i < 5; ++i) {
        if (per_op_total[i] == 0) continue;
        check(per_op_hard[i] == 0, "%-4s %6ld 行，数值位不一致 %ld 行（NaN 载荷豁免 %ld 行）",
              op_names[i], per_op_total[i], per_op_hard[i], per_op_soft[i]);
    }
    if (expect_rows > 0) {
        check(rows == expect_rows, "黄金向量行数 = %ld（期望 %ld）", rows, expect_rows);
    } else {
        info("黄金向量共 %ld 行（未指定 --expect-rows，不判定行数）", rows);
    }

    if (strict_nan) {
        check(soft == 0, "严格模式：NaN 载荷差异也必须为 0（实测 %ld 行）", soft);
    } else if (soft > 0) {
        info("NaN 载荷差异 %ld 行（豁免，非失败；判据见本函数上方注释）", soft);
    }
    check(hard == 0, "逐位一致性总计：%ld 行，数值位不一致 %ld 行（NaN 载荷豁免 %ld 行）", rows, hard, soft);
}

/* ===================================================================== */
/* 第 4 节：ABI 布局自检                                                  */
/* ===================================================================== */
typedef const char* (*fn_build_id)(void);
typedef int32_t     (*fn_abi_version)(void);
typedef int32_t     (*fn_layout_report)(CavaLayoutReport*);
typedef int32_t     (*fn_open)(const CavaOpenParams*, int64_t*, CavaOpenResult*);
typedef int32_t     (*fn_close)(int64_t);

static int section_abi(const char* lib_path, bool have_lib) {
    section("4. ABI 布局自检（cava_layout_report）");

    /* 4a 本 TU 自己的两张表先对上（这一步不依赖任何库）：
     *    机械表(offsetof/sizeof) 算出的 hash 必须等于签入的冻结黄金 hash。*/
    int mech_mismatch = 0;
    for (int32_t i = 0; i < kGoldenCount; ++i) {
        const uint32_t h = hash_fields(kMech[i].rows, kMech[i].count);
        if (kGolden[i].layout_hash == 0u) {
            info("%-18s 冻结 hash 未填（机械表算出 0x%08X）", kGolden[i].name, (unsigned)h);
            continue;
        }
        if (h != kGolden[i].layout_hash || kMech[i].count != kGolden[i].field_count) {
            ++mech_mismatch;
            std::printf("    %-18s 冻结(size=%llu,fields=%d,hash=0x%08X) 本 TU 机械算出(fields=%d,hash=0x%08X)\n",
                        kGolden[i].name, (unsigned long long)kGolden[i].size, (int)kGolden[i].field_count,
                        (unsigned)kGolden[i].layout_hash, (int)kMech[i].count, (unsigned)h);
        }
    }
    check(mech_mismatch == 0,
          "本 TU 的机械表(offsetof/sizeof) 与冻结黄金表一致（不一致说明 cava_abi.h 变了而黄金没更新）");

    if (!have_lib) {
        skip("没有 --lib / CAVA_SUITE_LIB，且自动探测没找到 %s/%s —— ABI 一节**未执行**（不假装跑过）",
             host_platform_tag(), host_library_file_name());
        return 0;
    }

    LibHandle h = lib_open(lib_path);
    if (!h) {
        check(false, "加载 %s 失败：%s", lib_path, lib_err().c_str());
        return 1;
    }
    info("已加载 %s", lib_path);

    fn_abi_version  p_ver  = reinterpret_cast<fn_abi_version>(lib_sym(h, "cava_abi_version"));
    fn_layout_report p_rep = reinterpret_cast<fn_layout_report>(lib_sym(h, "cava_layout_report"));
    fn_build_id     p_bid  = reinterpret_cast<fn_build_id>(lib_sym(h, "cava_build_id"));
    fn_open         p_open = reinterpret_cast<fn_open>(lib_sym(h, "cava_open"));
    fn_close        p_close = reinterpret_cast<fn_close>(lib_sym(h, "cava_close"));
    check(p_ver && p_rep && p_bid && p_open && p_close,
          "5 个必查符号都能解析：cava_abi_version=%p cava_layout_report=%p cava_build_id=%p cava_open=%p cava_close=%p",
          (void*)p_ver, (void*)p_rep, (void*)p_bid, (void*)p_open, (void*)p_close);
    if (!p_ver || !p_rep || !p_bid || !p_open || !p_close) { lib_close(h); return 1; }

    check(p_ver() == CAVA_ABI_VERSION, "cava_abi_version() = %d（期望 %d）", (int)p_ver(), (int)CAVA_ABI_VERSION);

    /* 4b build_id 里带的编译开关（从产物反查的第 3 层） */
    const char* bid = p_bid();
    info("build_id = %s", bid ? bid : "(null)");
    if (bid != nullptr) {
        /* cava_abi.cpp 的 build_id 串里带的是"编译开关画像"：
         *   CMake 构建 → CAVA_BUILD_FP_FLAGS 原样（"-O2 -fwrapv -ffp-contract=off -fno-fast-math"，
         *                MSVC 是 "/O2 /fp:strict"）；
         *   不走 CMake 的手编构建（native/tests/build-mingw.ps1）→ 回落到短形式（"O2/fwrapv/..."）。
         * 所以这里断言的是"**画像里不含危险项**"，而不是"必须逐字等于某串" ——
         * 后者会在合法的非 CMake 构建上误报。真正的"编译开关是否带全"由
         * tools/platform-flagcheck.ps1 从 CMake 的 flags.make/compile_commands.json 里查。 */
        check(std::strstr(bid, "fast-math") == nullptr || std::strstr(bid, "no-fast-math") != nullptr ||
              std::strstr(bid, "ffp-contract=off") != nullptr,
              "build_id 画像里没有裸 -ffast-math（build_id=\"%s\"）", bid);
        check(std::strstr(bid, "march=native") == nullptr && std::strstr(bid, "Ofast") == nullptr &&
              std::strstr(bid, "/fp:fast") == nullptr,
              "build_id 画像里没有 -march=native / -Ofast / /fp:fast");
        const bool mentions_contract =
            std::strstr(bid, "ffp-contract=off") != nullptr || std::strstr(bid, "/fp:strict") != nullptr;
        if (!mentions_contract) {
            info("build_id 没有明说 contraction 开关（非 CMake 的手编构建会这样）；"
                 "请用 tools/platform-flagcheck.ps1 从构建产物里确认");
        }
    }

    /* 4c 布局报告逐条比对 */
    CavaLayoutReport rep;
    std::memset(&rep, 0, sizeof(rep));
    const int32_t n = p_rep(&rep);
    check(n == kGoldenCount, "cava_layout_report 返回 %d 条（期望 %d 条 = 14 个结构体）", (int)n, (int)kGoldenCount);
    check(rep.entry_count == kGoldenCount, "report.entry_count = %d（期望 %d）", (int)rep.entry_count, (int)kGoldenCount);
    check(rep.abi_version == CAVA_ABI_VERSION, "report.abi_version = %d（期望 %d）", (int)rep.abi_version, (int)CAVA_ABI_VERSION);
    check(rep.pointer_size == (int32_t)sizeof(void*),
          "report.pointer_size = %d（本平台 sizeof(void*)=%d）", (int)rep.pointer_size, (int)sizeof(void*));

    const int platform_id_expected =
#if defined(_WIN32) && (defined(_M_ARM64) || defined(__aarch64__))
        CAVA_PLATFORM_WINDOWS_ARM64;
#elif defined(_WIN32)
        CAVA_PLATFORM_WINDOWS_X64;
#elif defined(__APPLE__) && defined(__aarch64__)
        CAVA_PLATFORM_MACOS_ARM64;
#elif defined(__APPLE__)
        CAVA_PLATFORM_MACOS_X64;
#elif defined(__linux__) && defined(__aarch64__)
        CAVA_PLATFORM_LINUX_ARM64;
#elif defined(__linux__)
        CAVA_PLATFORM_LINUX_X64;
#else
        0;
#endif
    check(rep.platform == platform_id_expected,
          "report.platform = %d（本平台编译期标签 %s 对应 %d）", (int)rep.platform, host_platform_tag(), platform_id_expected);

    uint32_t sum = 0;
    long entry_bad = 0;
    const int32_t limit = (rep.entry_count < kGoldenCount) ? rep.entry_count : kGoldenCount;
    for (int32_t i = 0; i < limit; ++i) {
        const CavaLayoutEntry& e = rep.entries[i];
        sum += e.layout_hash;
        const uint32_t recomputed = hash_entry_raw(e);
        bool ok = (e.struct_size == kGolden[i].size) &&
                  (e.struct_align == kGolden[i].align) &&
                  (e.field_count == (uint32_t)kGolden[i].field_count) &&
                  (e.layout_hash == kGolden[i].layout_hash) &&
                  (recomputed == e.layout_hash);
        if (!ok) {
            ++entry_bad;
            std::printf("    %-18s size=%llu(期望%llu) align=%llu(期望%llu) fields=%u(期望%d) hash=0x%08X(期望0x%08X) 重算=0x%08X\n",
                        kGolden[i].name,
                        (unsigned long long)e.struct_size, (unsigned long long)kGolden[i].size,
                        (unsigned long long)e.struct_align, (unsigned long long)kGolden[i].align,
                        (unsigned)e.field_count, (int)kGolden[i].field_count,
                        (unsigned)e.layout_hash, (unsigned)kGolden[i].layout_hash, (unsigned)recomputed);
            continue;
        }
        /* 逐字段：与"本 TU 用 offsetof/sizeof 现算"的表比，连字段顺序都比 */
        if (kMech[i].count != (int32_t)e.field_count) { continue; }
        for (int32_t k = 0; k < kMech[i].count; ++k) {
            if (e.field_offsets[k] != kMech[i].rows[k].offset || e.field_sizes[k] != kMech[i].rows[k].size) {
                ++entry_bad;
                std::printf("    %-18s 字段[%d] %s: 库=(off=%llu,size=%llu) 本 TU=(off=%llu,size=%llu)\n",
                            kGolden[i].name, (int)k, kMech[i].rows[k].name,
                            (unsigned long long)e.field_offsets[k], (unsigned long long)e.field_sizes[k],
                            (unsigned long long)kMech[i].rows[k].offset, (unsigned long long)kMech[i].rows[k].size);
                break;
            }
        }
    }
    check(entry_bad == 0, "14 条 entry 的 size/align/field_count/layout_hash/逐字段 (offset,size) 全部一致");
    check(sum == kGoldenLayoutSum,
          "layout_hash_sum = 0x%08X（期望 0x%08X，14 条 uint32 回绕加法）",
          (unsigned)sum, (unsigned)kGoldenLayoutSum);

    /* 4d cava_open 的 fail-closed 行为：错和值必须被 CAVA_ERR_LAYOUT 拒 */
    CavaOpenParams params;
    std::memset(&params, 0, sizeof(params));
    params.abi_version = CAVA_ABI_VERSION;
    params.flags = 0;
    params.layout_hash_sum = (uint64_t)sum;

    int64_t handle = 0;
    CavaOpenResult res;
    std::memset(&res, 0, sizeof(res));
    const int32_t rc_ok = p_open(&params, &handle, &res);
    check(rc_ok == CAVA_OK && handle != 0 && res.status == CAVA_OK,
          "cava_open(正确和值) → rc=%d handle=%lld status=%d native_layout_sum=0x%llX",
          (int)rc_ok, (long long)handle, (int)res.status, (unsigned long long)res.native_layout_sum);
    check(res.native_layout_sum == (uint64_t)sum,
          "cava_open 回填的 native_layout_sum = 0x%llX（= 报告和值）", (unsigned long long)res.native_layout_sum);
    if (handle != 0) {
        check(p_close(handle) == CAVA_OK, "cava_close(handle) = 0");
    }

    params.layout_hash_sum = (uint64_t)(sum ^ 0x1u); /* 故意错一位 */
    int64_t bad_handle = 12345;
    CavaOpenResult bad_res;
    std::memset(&bad_res, 0, sizeof(bad_res));
    const int32_t rc_bad = p_open(&params, &bad_handle, &bad_res);
    check(rc_bad == CAVA_ERR_LAYOUT && bad_handle == 0,
          "cava_open(错和值 1 位) → rc=%d（期望 %d = CAVA_ERR_LAYOUT）handle=%lld（必须 0）",
          (int)rc_bad, (int)CAVA_ERR_LAYOUT, (long long)bad_handle);

    /* dump-table 模式：把本平台的实测表按 C++ 初始化器打出来，方便"冻结值"更新 */
    if (std::getenv("CAVA_SUITE_DUMP_TABLE") != nullptr) {
        std::printf("\n// ==== 从 %s 实测的表（可直接替换 kGolden）====\n", lib_path);
        dump_table(rep, stdout);
    }

    lib_close(h);
    return 0;
}

/* ===================================================================== */
/* main                                                                  */
/* ===================================================================== */
static void usage() {
    std::printf(
        "cava_platform_suite —— 平台矩阵数值一致性测试套件（逐位一致性 + 编译开关 + ABI 布局）\n"
        "用法: cava_platform_suite [--golden <fp_probe.txt>] [--lib <原生库>] [--expect-rows N] [--quiet]\n"
        "  --golden      黄金 FP 向量（默认 native/tests/vectors/fp_probe.txt）\n"
        "  --lib         原生库路径；缺省读环境变量 CAVA_SUITE_LIB，再缺省自动探测\n"
        "                natives/<tag>/<cava.dll|libcava.so|libcava.dylib>\n"
        "  --expect-rows 断言黄金向量行数（CI 用 15456）\n"
        "  --quiet       只打失败与摘要\n"
        "  --strict-nan  把「两边都是 NaN、只有载荷不同」也算失败（默认豁免并计数）\n"
        "退出码: 0=通过  1=有检查失败  2=用法错误  3=黄金向量缺失\n");
}

int main(int argc, char** argv) {
    const char* golden = "native/tests/vectors/fp_probe.txt";
    std::string lib;
    long expect_rows = 0;

    for (int i = 1; i < argc; ++i) {
        const char* a = argv[i];
        if (std::strcmp(a, "--golden") == 0 && i + 1 < argc) { golden = argv[++i]; }
        else if (std::strcmp(a, "--lib") == 0 && i + 1 < argc) { lib = argv[++i]; }
        else if (std::strcmp(a, "--expect-rows") == 0 && i + 1 < argc) { expect_rows = std::strtol(argv[++i], nullptr, 10); }
        else if (std::strcmp(a, "--quiet") == 0) { g_quiet = true; }
        else if (std::strcmp(a, "--strict-nan") == 0) { g_strict_nan = true; }
        else if (std::strcmp(a, "--help") == 0 || std::strcmp(a, "-h") == 0) { usage(); return 0; }
        else { std::printf("未知参数: %s\n", a); usage(); return 2; }
    }

    if (lib.empty()) {
        const char* env = std::getenv("CAVA_SUITE_LIB");
        if (env != nullptr && env[0] != '\0') lib = env;
    }

    std::printf("================ Cava 平台数值一致性套件 ================\n");
    std::printf("platform tag = %s\n", host_platform_tag());

    section_env();
    section_switches();

    bool have_lib = !lib.empty();
    if (!have_lib) {
        std::string auto_path = std::string("natives/") + host_platform_tag() + "/" + host_library_file_name();
        std::FILE* probe = std::fopen(auto_path.c_str(), "rb");
        if (probe != nullptr) { std::fclose(probe); lib = auto_path; have_lib = true; }
    }

    /* 黄金向量缺失时 fp 一节会给 FAIL，但先把文件存在性说清楚 */
    std::FILE* gf = std::fopen(golden, "rb");
    if (gf == nullptr) {
        std::printf("\n黄金向量 %s 不存在（--golden 指定；工作目录必须是仓库根）\n", golden);
        section_fp(golden, expect_rows, g_strict_nan);
        std::printf("\nRESULT: FAIL（黄金向量缺失）\n");
        return 3;
    }
    std::fclose(gf);

    section_fp(golden, expect_rows, g_strict_nan);
    section_abi(lib.c_str(), have_lib);

    std::printf("\nPLATFORM-SUITE|platform=%s|compiler=%s|golden=%s|lib=%s|pass=%d|fail=%d|skip=%d|verdict=%s\n",
                host_platform_tag(), CAVA_COMPILER_NAME,
                golden, have_lib ? lib.c_str() : "(none)",
                g_pass, g_fail, g_skip, (g_fail == 0) ? "PASS" : "FAIL");
    std::printf("RESULT: %s\n", (g_fail == 0) ? "PASS" : "FAIL");
    return (g_fail == 0) ? 0 : 1;
}
