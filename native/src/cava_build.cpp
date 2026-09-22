/* cava_build.cpp —— 构建标志、平台码、SAFE 断言基础设施（B2）。
 *
 * SAFE 语义（任务书硬要求）：
 *   触发断言 = 记录（stderr 一行 + 原子计数）+ 返回错误码，
 *   **绝不 abort()、绝不抛异常、绝不终止进程**。release 构建里 CAVA_ASSERT 展开为空。
 */
#include "cava_internal.h"

#include <atomic>
#include <cstdio>

namespace {

std::atomic<uint64_t> g_assert_failures{0};
std::atomic<uint32_t> g_assert_printed{0};

/* 打印上限：断言通常是「同一个 bug 打一万次」，这里只放前 16 条到 stderr，
 * 但计数不封顶（计数才是给自检/回归看的）。*/
constexpr uint32_t kMaxPrinted = 16;

} /* namespace */

namespace cava {
namespace detail {

bool safe_build(void) {
    return CAVA_SAFE_ENABLED != 0;
}

int32_t build_flags(void) {
    int32_t f = 0;
#if CAVA_SAFE_ENABLED
    f |= CAVA_BUILD_FLAG_SAFE_ASSERTS;
#endif
#ifndef NDEBUG
    f |= CAVA_BUILD_FLAG_DEBUG;
#endif
#if CAVA_ASAN_ENABLED
    f |= CAVA_BUILD_FLAG_ASAN;
#endif
#if CAVA_UBSAN_ENABLED
    f |= CAVA_BUILD_FLAG_UBSAN;
#endif
    return f;
}

int32_t platform_code(void) {
#if defined(_WIN32) || defined(__CYGWIN__)
#  if defined(_M_ARM64) || defined(__aarch64__)
    return CAVA_PLATFORM_WINDOWS_ARM64;
#  else
    return CAVA_PLATFORM_WINDOWS_X64;
#  endif
#elif defined(__linux__)
#  if defined(__aarch64__)
    return CAVA_PLATFORM_LINUX_ARM64;
#  else
    return CAVA_PLATFORM_LINUX_X64;
#  endif
#elif defined(__APPLE__)
#  if defined(__aarch64__)
    return CAVA_PLATFORM_MACOS_ARM64;
#  else
    return CAVA_PLATFORM_MACOS_X64;
#  endif
#else
    return 0; /* 未知平台：Java 侧看到 0 就应该整体回退 */
#endif
}

void assert_fail(const char* file, int line, const char* expr, int32_t code) {
    const uint64_t n = g_assert_failures.fetch_add(1, std::memory_order_relaxed) + 1;
    if (g_assert_printed.fetch_add(1, std::memory_order_relaxed) < kMaxPrinted) {
        std::fprintf(stderr, "[cava][SAFE] assertion #%llu failed: (%s) at %s:%d -> code=%d\n",
                     (unsigned long long)n,
                     (expr != nullptr) ? expr : "?",
                     (file != nullptr) ? file : "?",
                     line,
                     (int)code);
        std::fflush(stderr);
    }
}

uint64_t assert_fail_count(void) {
    return g_assert_failures.load(std::memory_order_relaxed);
}

} /* namespace detail */
} /* namespace cava */
