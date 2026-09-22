/* cava_dll_loadtest.cpp —— 动态加载测试：证明 natives/windows-x64/cava.dll 真的
 * 能被 LoadLibrary + GetProcAddress 找到全部 ABI 符号，并且通过函数指针调用行为正确。
 *
 * 为什么需要它：MinGW 默认**不导出**任何符号，一旦 CMake 那边漏了导出宏 / .def，
 * Java FFM 的 Linker 会在运行期才报 UnsatisfiedLinkError。这个测试把问题提前到构建期。
 *
 * 用法：cava_dll_loadtest.exe <cava.dll 路径>
 * 退出码 0 = 全部符号可解析、调用正确；非 0 = 失败（逐条打印）。
 */
#include "../include/cava_abi.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <dlfcn.h>
#endif

typedef const char* (*fn_build_id)(void);
typedef int64_t (*fn_touch)(void);
typedef int32_t (*fn_version)(void);
typedef int32_t (*fn_layout_report)(CavaLayoutReport*);
typedef int32_t (*fn_open)(const CavaOpenParams*, int64_t*, CavaOpenResult*);
typedef int32_t (*fn_close)(int64_t);
typedef int32_t (*fn_d2i)(double);
typedef int64_t (*fn_d2l)(double);
typedef uint64_t (*fn_bits_of_double)(double);
typedef double (*fn_double_of_bits)(uint64_t);

static int g_fail = 0;

static void check(bool ok, const char* what) {
    std::printf("  [%s] %s\n", ok ? " ok " : "FAIL", what);
    if (!ok) {
        ++g_fail;
    }
}

static void* resolve(void* lib, const char* name) {
#if defined(_WIN32)
    return (void*)GetProcAddress((HMODULE)lib, name);
#else
    return dlsym(lib, name);
#endif
}

int main(int argc, char** argv) {
    /* CTest 会无参调用本用例（native/tests/CMakeLists.txt 里每个 .cpp 一个用例），
     * 所以无参时不算失败：打印 SKIP 并返回 0。要真跑就传路径或设 CAVA_DLL_PATH。*/
    const char* path = (argc >= 2) ? argv[1] : std::getenv("CAVA_DLL_PATH");
    if (path == nullptr || path[0] == '\0') {
        std::printf("SKIP: 未提供 cava.dll 路径（用法: cava_dll_loadtest <cava.dll> 或设 CAVA_DLL_PATH）\n");
        return 0;
    }

#if defined(_WIN32)
    HMODULE lib = LoadLibraryA(path);
    if (lib == nullptr) {
        std::fprintf(stderr, "LoadLibrary 失败 (%lu): %s\n", (unsigned long)GetLastError(), path);
        return 2;
    }
#else
    void* lib = dlopen(path, RTLD_NOW);
    if (lib == nullptr) {
        std::fprintf(stderr, "dlopen 失败: %s\n", dlerror());
        return 2;
    }
#endif

    std::printf("=== cava_dll_loadtest ===\n");
    std::printf("library: %s\n", path);

    const char* names[] = {
        "cava_build_id", "cava_abi_touch", "cava_abi_version", "cava_layout_report",
        "cava_open", "cava_close", "cava_d2i_sat", "cava_d2l_sat",
        "cava_bits_of_double", "cava_double_of_bits",
    };
    void* syms[10];
    bool all_resolved = true;
    for (int i = 0; i < 10; ++i) {
        syms[i] = resolve(lib, names[i]);
        if (syms[i] == nullptr) {
            all_resolved = false;
            std::printf("  [FAIL] 找不到导出符号 %s\n", names[i]);
        }
    }
    check(all_resolved, "10 个 ABI 符号全部可解析（和 Java FFM Linker 查的名字一致）");
    if (!all_resolved) {
        return 1;
    }

    fn_build_id build_id = (fn_build_id)syms[0];
    fn_touch touch = (fn_touch)syms[1];
    fn_version version = (fn_version)syms[2];
    fn_layout_report layout_report = (fn_layout_report)syms[3];
    fn_open open_ = (fn_open)syms[4];
    fn_close close_ = (fn_close)syms[5];
    fn_d2i d2i = (fn_d2i)syms[6];
    fn_d2l d2l = (fn_d2l)syms[7];
    fn_bits_of_double bits_of_double = (fn_bits_of_double)syms[8];
    fn_double_of_bits double_of_bits = (fn_double_of_bits)syms[9];

    std::printf("  build_id = %s\n", build_id());
    check(version() == CAVA_ABI_VERSION, "cava_abi_version() == CAVA_ABI_VERSION");

    static CavaLayoutReport rep; /* 34 KB，放静态区 */
    std::memset(&rep, 0, sizeof(rep));
    const int32_t n = layout_report(&rep);
    check(n == 4, "cava_layout_report 返回 4");
    uint32_t sum = 0;
    for (int32_t i = 0; i < n && i < 4; ++i) {
        sum += rep.entries[i].layout_hash;
        std::printf("    %d: hash=0x%08x size=%llu fields=%u\n", (int)i,
                    (unsigned)rep.entries[i].layout_hash,
                    (unsigned long long)rep.entries[i].struct_size,
                    (unsigned)rep.entries[i].field_count);
    }
    std::printf("  layout_hash_sum = 0x%08x\n", (unsigned)sum);

    CavaOpenParams p;
    std::memset(&p, 0, sizeof(p));
    p.abi_version = CAVA_ABI_VERSION;
    p.layout_hash_sum = (uint64_t)sum;
    int64_t h = 0;
    CavaOpenResult r;
    std::memset(&r, 0, sizeof(r));
    int32_t st = open_(&p, &h, &r);
    check(st == CAVA_OK && h != 0, "cava_open 成功并返回非 0 句柄");
    check(r.native_layout_sum == (uint64_t)sum, "native_layout_sum 与 Java 侧算的和一致");
    check(close_(h) == CAVA_OK, "cava_close 成功");
    check(close_(h) == CAVA_ERR_NULL, "重复 cava_close 返回 CAVA_ERR_NULL（不崩）");
    check(d2i(2147483648.0) == 2147483647, "d2i_sat(2^31) 饱和到 INT_MAX");
    check(d2l(-1e300) == (-9223372036854775807LL - 1), "d2l_sat(-1e300) 饱和到 Long.MIN");
    check(bits_of_double(1.0) == 0x3FF0000000000000ull, "bits_of_double(1.0)");
    check(double_of_bits(0x3FF0000000000000ull) == 1.0, "double_of_bits(0x3FF0000000000000)");
    check(touch() > 0, "cava_abi_touch() 可调用");

#if defined(_WIN32)
    FreeLibrary((HMODULE)lib);
#else
    dlclose(lib);
#endif

    std::printf("RESULT: %s\n", (g_fail == 0) ? "PASS" : "FAIL");
    return (g_fail == 0) ? 0 : 1;
}
