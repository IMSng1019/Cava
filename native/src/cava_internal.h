/* cava_internal.h —— Cava 原生库的**内部**实现头。
 *
 * 这不是 ABI 的一部分：Java(FFM) 侧只允许依赖 native/include/cava_abi.h。
 * 所有权：p0-native 流（native/src 目录）。别人不要改这个文件。
 *
 * 约定：
 *   - 头文件用相对路径包含（"../include/cava_abi.h"），这样不依赖 CMake 的 -I 设置，
 *     无论构建侧怎么配置都能编过。
 *   - 全部实现是 C++17，零第三方依赖。
 */
#ifndef CAVA_INTERNAL_H
#define CAVA_INTERNAL_H

#include <cstddef>
#include <cstdint>
#include <memory>

#include "../include/cava_abi.h"

/* ------------------------------------------------------------------ */
/* 0. 导出属性                                                          */
/* ------------------------------------------------------------------ */
/* 头文件里刻意没有放导出宏（ABI 已冻结）。这里在 .cpp 的定义上加：
 *  - Windows(MinGW/MSVC): __declspec(dllexport)  -> cava.dll 的导出表里出现符号
 *  - 其它平台: visibility default
 * 静态库 / 自测单文件编译时加了这个也没害处。*/
#if defined(_WIN32) || defined(__CYGWIN__)
#  if defined(_MSC_VER)
/* MSVC 下**故意**定义成空：cava_abi.h（冻结的公开头）里的声明没有 __declspec(dllexport)，
 * 在定义上再写一次就是 C2375「重定义；不同的链接」——**每个导出符号都中**（MSVC 19.44 实测）。
 * 导出改由 CMake 的 WINDOWS_EXPORT_ALL_SYMBOLS 负责（native/cmake/CavaFlags.cmake 在 MSVC 上已开）。*/
#    define CAVA_EXPORT
#  else
#    define CAVA_EXPORT __declspec(dllexport)
#  endif
#elif defined(__GNUC__)
#  define CAVA_EXPORT __attribute__((visibility("default")))
#else
#  define CAVA_EXPORT
#endif

/* ------------------------------------------------------------------ */
/* 1. CAVA_SAFE 开关（B2）                                              */
/* ------------------------------------------------------------------ */
/* 用法（构建侧只需记这一条）：
 *     -DCAVA_SAFE=1   打开全部边界检查与断言（测试/DEBUG 构建）
 *     不定义 或 =0    关闭（release 默认；CAVA_ASSERT 展开为空）
 * 为了容忍 CMake 传 ON/TRUE 的写法，用 ## 拼接探测：
 *     CAVA_SAFE_IS_ON_1 有定义；CAVA_SAFE_IS_ON_0 / _OFF 没有定义。
 * 注意：探测是**大小写敏感**的，只认 1 / ON / TRUE / YES。*/
#define CAVA_SAFE_IS_ON_1    1
#define CAVA_SAFE_IS_ON_ON   1
#define CAVA_SAFE_IS_ON_TRUE 1
#define CAVA_SAFE_IS_ON_YES  1
#define CAVA_SAFE_PROBE_IMPL(x) CAVA_SAFE_IS_ON_##x
#define CAVA_SAFE_PROBE(x) CAVA_SAFE_PROBE_IMPL(x)

/* 三个宏名都认（构建侧实际发出的是哪个就用哪个）：
 *   -DCAVA_SAFE=1        原生侧脚本 / 手工构建
 *   -DCAVA_SAFE_BUILD=1  根 CMakeLists 的 CAVA_SAFE=ON（native/cmake/CavaFlags.cmake）
 *   -DCAVA_BUILD_SAFE=1  CavaFlags.cmake 的 cava_apply_build_definitions() */
#if (defined(CAVA_SAFE) && (CAVA_SAFE_PROBE(CAVA_SAFE) + 0)) || \
    (defined(CAVA_SAFE_BUILD) && (CAVA_SAFE_PROBE(CAVA_SAFE_BUILD) + 0)) || \
    (defined(CAVA_BUILD_SAFE) && (CAVA_SAFE_PROBE(CAVA_BUILD_SAFE) + 0))
#  define CAVA_SAFE_ENABLED 1
#else
#  define CAVA_SAFE_ENABLED 0
#endif

/* ------------------------------------------------------------------ */
/* 2. sanitizer / debug 探测                                            */
/* ------------------------------------------------------------------ */
#if defined(__SANITIZE_ADDRESS__)
#  define CAVA_ASAN_ENABLED 1
#elif defined(__has_feature)
#  if __has_feature(address_sanitizer)
#    define CAVA_ASAN_ENABLED 1
#  endif
#endif
#ifndef CAVA_ASAN_ENABLED
#  define CAVA_ASAN_ENABLED 0
#endif

#if defined(__SANITIZE_UNDEFINED__)
#  define CAVA_UBSAN_ENABLED 1
#elif defined(__has_feature)
#  if __has_feature(undefined_behavior_sanitizer)
#    define CAVA_UBSAN_ENABLED 1
#  endif
#endif
#ifndef CAVA_UBSAN_ENABLED
#  define CAVA_UBSAN_ENABLED 0
#endif

/* ------------------------------------------------------------------ */
/* 3. 内部实现函数（跨 .cpp 用；不是 ABI）                              */
/* ------------------------------------------------------------------ */
namespace cava {
namespace detail {

/* 32 位 FNV-1a，逐字节（= 契约 2.3 的公式；offset/size 的 4 个 u32 依次喂入）。*/
uint32_t fnv1a32_bytes(const void* data, std::size_t len);

/* 原生侧算出的**全部导出结构体**（P0 的 4 个 + P1 追加的 5 个 = 9 个）layout_hash
 * 的 uint32 回绕和。这是 cava_open 用来和 Java 侧 layout_hash_sum 比对的权威值，
 * 也通过 CavaOpenResult::native_layout_sum 回给 Java。*/
uint64_t native_layout_sum();

/* 单个结构体的 layout_hash（契约 2.3 的公式）。*/
uint32_t layout_hash_of(const char* struct_name);

/* 契约 2.3 里要求的 cava_layout_<name> 形状的内部实现。
 * 头文件（已冻结）没有声明它们，所以**不导出**，只在原生内部用；
 * 需要暴露时由 captain 往 cava_abi.h 加声明即可。返回字段数，<0 = 名字不认识。*/
int32_t layout_CavaLayoutEntry(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaLayoutReport(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaOpenParams(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaOpenResult(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaPathRequest(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaPathNode(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaMobProfile(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaStateRecord(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);
int32_t layout_CavaCollisionBox(uint32_t* out_hash, uint64_t* out_size, uint64_t* out_align);

/* ------------------------------------------------------------------ */
/* 3b. 句柄表（给子系统入口做**代际校验**；不新增任何导出符号）         */
/* ------------------------------------------------------------------ */
/* 子系统入口（native/src/pathfind 等）只校验句柄"形状"是不够的：槽位会被复用，
 * 一个陈旧句柄的形状完全合法，但指向的是**别的对象**。用法：
 *     auto inst = cava::detail::lookup(handle);   // 有效 -> 非空
 *     if (!inst) return CAVA_ERR_NULL;
 * 或只判布尔：cava::detail::handle_valid(handle)。
 * 返回 shared_ptr：即使另一线程同时在 cava_close，对象也不会在使用中被析构。*/
constexpr uint32_t CAVA_HANDLE_MAGIC_LIVE = 0x41564143u; /* 'C','A','V','A'（小端读作 CAVA）*/
constexpr uint32_t CAVA_HANDLE_MAGIC_DEAD = 0x44414544u; /* 'D','E','A','D'：只用于诊断 */
constexpr int32_t  CAVA_MAX_HANDLES = 256;

struct CavaInstance {
    uint32_t magic = CAVA_HANDLE_MAGIC_LIVE;
    uint32_t generation = 1;
    int32_t  flags = 0;
    int32_t  abi_version = CAVA_ABI_VERSION;
    uint64_t native_layout_sum = 0;
    int64_t  serial = 0;
};

std::shared_ptr<CavaInstance> lookup(int64_t handle);
bool handle_valid(int64_t handle);

/* 当前构建是否 SAFE（供 build_flags 与文档用）。*/
bool safe_build();
int32_t build_flags();
int32_t platform_code();

/* SAFE 断言失败的记录点：计数 + 一行 stderr（**绝不 abort、绝不抛异常**）。*/
void     assert_fail(const char* file, int line, const char* expr, int32_t code);
uint64_t assert_fail_count();

} /* namespace detail */
} /* namespace cava */

/* ------------------------------------------------------------------ */
/* 4. CAVA_ASSERT（B2）                                                 */
/* ------------------------------------------------------------------ */
/* SAFE 构建：条件不成立 -> 记录（stderr + 计数）并 return 错误码；
 * release  ：整条展开为空表达式（cond/code 都不求值）。 */
#if CAVA_SAFE_ENABLED
#  define CAVA_ASSERT(cond, code)                                                   \
      do {                                                                          \
          if (!(cond)) {                                                            \
              ::cava::detail::assert_fail(__FILE__, __LINE__, #cond, (code));        \
              return (code);                                                        \
          }                                                                         \
      } while (0)
#  define CAVA_ASSERT_VOID(cond, code)                                              \
      do {                                                                          \
          if (!(cond)) {                                                            \
              ::cava::detail::assert_fail(__FILE__, __LINE__, #cond, (code));        \
              return;                                                               \
          }                                                                         \
      } while (0)
#else
#  define CAVA_ASSERT(cond, code) ((void)0)
#  define CAVA_ASSERT_VOID(cond, code) ((void)0)
#endif

#endif /* CAVA_INTERNAL_H */
