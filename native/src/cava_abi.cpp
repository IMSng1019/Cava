/* cava_abi.cpp —— 版本 / 金丝雀 / build id。*/
#include "cava_internal.h"

#include <atomic>
#include <string>

/* ------------------------------------------------------------------ */
/* build id 拼装（B1）                                                  */
/* ------------------------------------------------------------------ */
/* 构建侧可以给的宏（都可选；不给就用下面的默认值）：
 *   CAVA_BUILD_ID       整串覆盖（最高优先级，给了就用它，别的宏都不看）
 *   CAVA_VERSION_STRING 默认 "0.1.0"
 *   CAVA_OPT_TAG        默认 "O2/fwrapv/ffp-contract=off"
 * 禁止 __DATE__ / __TIME__：会破坏可重复构建（契约要求）。 */
#define CAVA_STR2(x) #x
#define CAVA_STR(x) CAVA_STR2(x)

#ifndef CAVA_VERSION_STRING
#  define CAVA_VERSION_STRING "0.1.0"
#endif
#ifndef CAVA_OPT_TAG
#  define CAVA_OPT_TAG "O2/fwrapv/ffp-contract=off"
#endif

namespace {

std::atomic<int64_t> g_touch{0};

/* 构建系统没给 CAVA_BUILD_PLATFORM_TAG 时才用得上（见 build_id_string）。*/
[[maybe_unused]] const char* platform_tag() {
    switch (cava::detail::platform_code()) {
        case CAVA_PLATFORM_WINDOWS_X64:   return "win-x64";
        case CAVA_PLATFORM_WINDOWS_ARM64: return "win-arm64";
        case CAVA_PLATFORM_LINUX_X64:     return "linux-x64";
        case CAVA_PLATFORM_LINUX_ARM64:   return "linux-arm64";
        case CAVA_PLATFORM_MACOS_X64:     return "macos-x64";
        case CAVA_PLATFORM_MACOS_ARM64:   return "macos-arm64";
        default:                          return "unknown-platform";
    }
}

/* 编译器标签：<abi>-<family>-<version>，例如 mingw-gcc-15.2.0 / msvc-19.44 / linux-clang-18.1.8。
 * 只用编译期常量，不含日期时间。 */
/* 构建系统没给 CAVA_BUILD_COMPILER 时才用得上。*/
[[maybe_unused]] std::string compiler_tag() {
    std::string s;
#if defined(_WIN32) && defined(__GNUC__) && !defined(__clang__)
    s = "mingw-";
#elif defined(_WIN32) && defined(__clang__)
    s = "mingw-";
#elif defined(_WIN32) && defined(_MSC_VER)
    s = "msvc-";
#elif defined(__linux__)
    s = "linux-";
#elif defined(__APPLE__)
    s = "darwin-";
#endif
#if defined(__clang__)
    s += "clang-" CAVA_STR(__clang_major__) "." CAVA_STR(__clang_minor__) "." CAVA_STR(__clang_patchlevel__);
#elif defined(_MSC_VER)
    /* 预处理器不做算术，所以直接打 _MSC_VER 原值（例如 msvc-1944）。*/
    s += CAVA_STR(_MSC_VER);
#elif defined(__GNUC__)
    s += "gcc-" CAVA_STR(__GNUC__) "." CAVA_STR(__GNUC_MINOR__) "." CAVA_STR(__GNUC_PATCHLEVEL__);
#else
    s += "unknown-cc";
#endif
    return s;
}

std::string build_id_string() {
#ifdef CAVA_BUILD_ID
    return std::string(CAVA_BUILD_ID);
#else
    /* 每一段都优先用构建系统给的宏（native/cmake/CavaFlags.cmake 会发
     * CAVA_BUILD_PLATFORM_TAG / CAVA_BUILD_COMPILER / CAVA_BUILD_FP_FLAGS），
     * 没给就用本文件推导出来的值。整串只影响 build_id_hash（诊断用），
     * 不影响 layout_hash。*/
    std::string s = "cava ";
    s += CAVA_VERSION_STRING;
    s += ' ';
#ifdef CAVA_BUILD_PLATFORM_TAG
    s += CAVA_BUILD_PLATFORM_TAG;
#else
    s += platform_tag();
#endif
    s += ' ';
#ifdef CAVA_BUILD_COMPILER
    s += CAVA_BUILD_COMPILER;
#else
    s += compiler_tag();
#endif
    s += ' ';
#ifdef CAVA_BUILD_FP_FLAGS
    s += CAVA_BUILD_FP_FLAGS;
#else
    s += CAVA_OPT_TAG;
#endif
    s += " safe=";
    s += (CAVA_SAFE_ENABLED ? "1" : "0");
    s += " asan=";
    s += (CAVA_ASAN_ENABLED ? "1" : "0");
    s += " ubsan=";
    s += (CAVA_UBSAN_ENABLED ? "1" : "0");
    return s;
#endif
}

} /* namespace */

extern "C" CAVA_EXPORT const char* cava_build_id(void) {
    /* 进程生命期稳定的静态字符串（C++11 magic static，线程安全）。*/
    static const std::string id = build_id_string();
    return id.c_str();
}

extern "C" CAVA_EXPORT int64_t cava_abi_touch(void) {
    /* 原子 +1 并返回新值。int64 溢出由 C++20 的原子整型语义保证是回绕，不是 UB。*/
    return g_touch.fetch_add(1, std::memory_order_relaxed) + 1;
}

extern "C" CAVA_EXPORT int32_t cava_abi_version(void) {
    return CAVA_ABI_VERSION;
}
