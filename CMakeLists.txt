# =============================================================================
# Cava —— 原生库构建（根 CMakeLists.txt；文件所有权：P0-A 构建基建流）
#
# 契约（docs/CAVA-工程接口契约.md §2）：
#   * C++20；编译参数固定 -O2 -fwrapv -ffp-contract=off -fno-fast-math
#     （MSVC: /O2 /fp:strict）；**禁 -march=native / -ffast-math / -Ofast**（见 native/cmake/CavaFlags.cmake）。
#   * ABI 权威定义在 native/include/cava_abi.h（本文件不碰它）。
#   * 产物固定落到 <repo>/natives/${CAVA_PLATFORM_TAG}/；Windows 上那里只有 cava.dll，
#     import lib / pdb 落到 build/ 下的 import-lib/，不污染 natives。
#
# 源文件收集：**GLOB_RECURSE + CONFIGURE_DEPENDS**。native/src 下任何目录（common / pathfind /
# entity / redstone / ...）新增 .c/.cpp 都会被自动收录 —— native 侧加文件**不需要**改这个文件。
# 只有在要加第三方源码树或新 target 时才需要动这里（找 P0-A）。
#
# 本机命令（CMake 不在 PATH，用便携版全路径；mingw32-make 也不在 PATH，必须显式给；
# build 目录带流标识，别复用别人的 build 目录 —— CMakeCache 里存着过期结论）：
#   $cmake = 'J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe'
#   & $cmake -S J:\mc\Cava -B J:\mc\Cava\build\native-mingw-<流名> -G "MinGW Makefiles" `
#       -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=C:/mingw64/bin/gcc.exe `
#       -DCMAKE_CXX_COMPILER=C:/mingw64/bin/g++.exe `
#       -DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe
#   & $cmake --build J:\mc\Cava\build\native-mingw-<流名> --parallel
# 或者走 Gradle：.\gradlew.bat buildNative（它会自动把 C:/mingw64/bin 加进子进程 PATH）
#
# 平台矩阵（5 平台）的数值一致性套件是**独立**的一棵 CMake 树，刻意不挂在这里：
#   & $cmake -S J:\mc\Cava\native\tests\platform -B J:\mc\Cava\build\platform-suite -DCMAKE_BUILD_TYPE=Release
#   & $cmake --build J:\mc\Cava\build\platform-suite
#   $env:CAVA_SUITE_LIB = 'J:\mc\Cava\natives\windows-x64\cava.dll'
#   & J:\mc\Cava\build\platform-suite\cava_platform_suite.exe --golden J:\mc\Cava\native\tests\vectors\fp_probe.txt
# 详见 native/tests/platform/README.md 与 docs/CAVA-platform-notes.md。
# =============================================================================

cmake_minimum_required(VERSION 3.24)

project(cava
    VERSION 0.1.0
    DESCRIPTION "Cava native core (Minecraft 1.20.4 Fabric server mod)"
    LANGUAGES C CXX
)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/native/cmake")
include(CavaPlatform)   # CAVA_PLATFORM_TAG / CAVA_PLATFORM_ID / 输出目录 / 平台校验
include(CavaFlags)      # CAVA_HARDENED_FP_FLAGS / SAFE / ASAN / UBSAN / 通用 target 配置

# -----------------------------------------------------------------------------
# 选项
# -----------------------------------------------------------------------------
if(CMAKE_SOURCE_DIR STREQUAL CMAKE_CURRENT_SOURCE_DIR)
    set(CAVA_IS_TOP_LEVEL ON)
else()
    set(CAVA_IS_TOP_LEVEL OFF)
endif()

option(CAVA_SAFE "打开全部断言与边界检查（慢；默认 OFF，调试/差分排查时才开）" OFF)
option(CAVA_ASAN "AddressSanitizer（默认 OFF；与 MSVC 不兼容）" OFF)
option(CAVA_UBSAN "UndefinedBehaviorSanitizer（默认 OFF；与 MSVC 不兼容）" OFF)
option(CAVA_BUILD_TESTS "构建原生测试（仅顶层构建默认 ON）" ${CAVA_IS_TOP_LEVEL})
option(CAVA_EXPORT_ALL_SYMBOLS "Windows 上导出全部符号（cava_abi.h 没有 dllexport 宏）" ON)

cava_configure_platform()   # 见 native/cmake/CavaPlatform.cmake

# -----------------------------------------------------------------------------
# 源文件：GLOB_RECURSE + CONFIGURE_DEPENDS（native 侧加文件不用改这里）
# -----------------------------------------------------------------------------
file(GLOB_RECURSE CAVA_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/native/src/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/native/src/*.cc"
    "${CMAKE_CURRENT_SOURCE_DIR}/native/src/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/native/src/*.cxx"
)
# 测试有独立的 target 树（native/tests/CMakeLists.txt），src 下的草稿目录也不收
list(FILTER CAVA_SOURCES EXCLUDE REGEX "/native/src/(tests|scratch|_scratch|drafts)/")

# -----------------------------------------------------------------------------
# 库 target：cava（SHARED）
# -----------------------------------------------------------------------------
if(CAVA_SOURCES)
    list(LENGTH CAVA_SOURCES _cava_src_count)
    add_library(cava SHARED ${CAVA_SOURCES})
    add_library(cava::cava ALIAS cava)

    target_include_directories(cava
        PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/native/include"
        PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/native/src"
    )

    cava_configure_target(cava)
    cava_apply_build_definitions(cava)
    cava_set_output_dirs(cava)

    message(STATUS "cava: 源文件 ${_cava_src_count} 个 -> ${CAVA_NATIVES_DIR}")
else()
    # 允许「契约先行、源码未到位」的状态：配置必须成功，但绝不产出假库。
    message(WARNING "cava: native/src 下没有 .c/.cpp 源文件，跳过 cava 目标（源码到位后重跑 CMake）")
endif()

# -----------------------------------------------------------------------------
# 测试（仅顶层构建；见 native/tests/CMakeLists.txt）
# -----------------------------------------------------------------------------
if(CAVA_BUILD_TESTS)
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/native/tests/CMakeLists.txt")
        enable_testing()
        add_subdirectory(native/tests)
    else()
        message(STATUS "cava: native/tests/CMakeLists.txt 不存在，跳过测试")
    endif()
endif()

# -----------------------------------------------------------------------------
# 配置摘要（写进构建日志，便于报告/排障取证）
# -----------------------------------------------------------------------------
# 平台推导取证：把"目标架构是哪来的"打出来 —— 交叉编译出错时这一行就能定位
# （host 与 target 不同 = 真的在交叉编译；processor_source=CMAKE_OSX_ARCHITECTURES 表示 Apple 分支生效）。
message(STATUS "cava: platform=${CAVA_PLATFORM_TAG} id=${CAVA_PLATFORM_ID} system=${CMAKE_SYSTEM_NAME} processor=${CAVA_PLATFORM_PROCESSOR} processor_source=${CAVA_PLATFORM_PROCESSOR_SOURCE}")
message(STATUS "cava: host=${CMAKE_HOST_SYSTEM_NAME}/${CMAKE_HOST_SYSTEM_PROCESSOR} crosscompiling=${CMAKE_CROSSCOMPILING} osx_architectures='${CMAKE_OSX_ARCHITECTURES}'")
message(STATUS "cava: compiler=${CAVA_BUILD_COMPILER} (${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION})")
message(STATUS "cava: fp-flags=${CAVA_HARDENED_FP_FLAGS}")
message(STATUS "cava: safe=${CAVA_SAFE} asan=${CAVA_ASAN} ubsan=${CAVA_UBSAN} build_type=${CMAKE_BUILD_TYPE}")
message(STATUS "cava: natives-dir=${CAVA_NATIVES_DIR}")
message(STATUS "cava: import-lib-dir=${CAVA_IMPORT_LIB_DIR}")
