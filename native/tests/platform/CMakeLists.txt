# =============================================================================
# native/tests/platform/CMakeLists.txt —— 平台数值一致性套件的 CMake 入口
#
# 为什么是**独立的一棵 CMake 树**（不挂进 native/tests/CMakeLists.txt）：
#   * native/tests/CMakeLists.txt 是 P0-A 的路径，P4-B 没被授权改；
#   * 更重要的是：这个套件要能在**交叉配置**下也被构建，而主树的 cava 目标在交叉配置下
#     本来就不该被构建（没有目标 toolchain）。
#   * 它刻意**不复用 CAVA_PLATFORM_TAG**：套件的平台标签来自编译期宏（host_platform_tag()），
#     这样才能发现"CMake 推的标签"和"编译器实际编出来的平台"不一致。
#
# 用法（CI 与本地同一套命令）：
#   cmake -S native/tests/platform -B build/platform-suite -DCMAKE_BUILD_TYPE=Release
#   cmake --build build/platform-suite --config Release
#   build/platform-suite/cava_platform_suite --golden native/tests/vectors/fp_probe.txt --lib natives/<tag>/<lib> --expect-rows 15456
#
# 不依赖 CMake 的等价命令见 native/tests/platform/README.md 与 run-platform-suite.ps1 / .sh。
# =============================================================================
cmake_minimum_required(VERSION 3.24)

project(cava_platform_suite LANGUAGES CXX)

# 复用主树的硬性数值参数（-O2 -fwrapv -ffp-contract=off -fno-fast-math -fno-math-errno；
# MSVC: /O2 /fp:strict）与禁止参数守卫。**套件自己必须用同一套开关**，
# 否则它测的就不是"产物用的那套规则"。
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/../../cmake")
set(CAVA_IS_TOP_LEVEL OFF)
set(CAVA_EXPORT_ALL_SYMBOLS OFF)
include(CavaFlags)

add_executable(cava_platform_suite cava_platform_suite.cpp)
cava_configure_target(cava_platform_suite)

set_target_properties(cava_platform_suite PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

# 头文件只用于结构体定义与常量（cava_layout_report 等函数是**运行期 dlsym/LoadLibrary**
# 解析的，所以这里刻意不链接 cava 库）。
target_include_directories(cava_platform_suite PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../include"
)

message(STATUS "cava platform-suite: fp-flags=${CAVA_HARDENED_FP_FLAGS")
message(STATUS "cava platform-suite: system=${CMAKE_SYSTEM_NAME/${CMAKE_SYSTEM_PROCESSOR host=${CMAKE_HOST_SYSTEM_NAME/${CMAKE_HOST_SYSTEM_PROCESSOR crosscompiling=${CMAKE_CROSSCOMPILING")
