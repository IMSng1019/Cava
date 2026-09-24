# tag_matrix_case.cmake -- 单次调用 cava_configure_platform()，把推导出的标签/编号打出来。
#
# 为什么要用 cmake -P（脚本模式）而不是真的 configure：
#   真的 configure 需要一台目标平台机器/交叉工具链；而**平台标签推导本身**是一个纯函数，
#   输入只有 CMAKE_SYSTEM_NAME / CMAKE_SYSTEM_PROCESSOR / CMAKE_OSX_ARCHITECTURES / APPLE，
#   输出只有 CAVA_PLATFORM_TAG / CAVA_PLATFORM_ID —— 所以可以在这台 Windows 机器上穷举验证。
#   真正"跑一遍完整 configure"的版本在 tools/platform-tagmatrix.ps1 的第 2 部分。
#
# 用法（由 tools/platform-tagmatrix.ps1 驱动）：
#   cmake -DCAVA_TAGTEST_WORK=<dir> -DCAVA_TAGTEST_SYS=Linux -DCAVA_TAGTEST_PROC=aarch64 -P tag_matrix_case.cmake
# 输出：TAG=<tag> ID=<id> PROC=<实际用于推导的架构> SRC=<取值来源>
# 退出码：0 = 推导成功；1 = FATAL_ERROR（不支持的组合，期望如此时算通过）

if(NOT DEFINED CAVA_TAGTEST_WORK)
    message(FATAL_ERROR "需要 -DCAVA_TAGTEST_WORK=<可写目录>（cava_configure_platform 会建 natives/<tag>/）")
endif()

# 脚本模式没有 project()，这些是 project()/CMakeDetermineSystem 平时会设好的变量，手动补上。
set(CMAKE_SOURCE_DIR "${CAVA_TAGTEST_WORK}")
set(CMAKE_BINARY_DIR "${CAVA_TAGTEST_WORK}/build")
set(CMAKE_SYSTEM_NAME "${CAVA_TAGTEST_SYS}")
set(CMAKE_SYSTEM_PROCESSOR "${CAVA_TAGTEST_PROC}")
if(DEFINED CAVA_TAGTEST_APPLE)
    set(APPLE "${CAVA_TAGTEST_APPLE}")
endif()
if(DEFINED CAVA_TAGTEST_OSXARCH)
    set(CMAKE_OSX_ARCHITECTURES "${CAVA_TAGTEST_OSXARCH}")
endif()
set(CAVA_EXPORT_ALL_SYMBOLS OFF)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/../../cmake")
include(CavaPlatform)
cava_configure_platform()

message(STATUS "TAG=${CAVA_PLATFORM_TAG} ID=${CAVA_PLATFORM_ID} PROC=${CAVA_PLATFORM_PROCESSOR} SRC=${CAVA_PLATFORM_PROCESSOR_SOURCE}")
