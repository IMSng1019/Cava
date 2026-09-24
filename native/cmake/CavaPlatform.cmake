# =============================================================================
# CavaPlatform.cmake —— 平台推导与输出目录（文件所有权：P0-A 构建基建流）
#
# CAVA_PLATFORM_TAG 形如 windows-x64 / linux-x64 / linux-arm64 / macos-x64 / macos-arm64，
# 必须用 **CMAKE_SYSTEM_PROCESSOR**（不是 CMAKE_HOST_SYSTEM_PROCESSOR），交叉编译才正确。
# **唯一例外是 Apple**：CMake 不会从 CMAKE_OSX_ARCHITECTURES 反推 CMAKE_SYSTEM_PROCESSOR，
# 所以 Apple 上以 CMAKE_OSX_ARCHITECTURES 为准（详见 cava_configure_platform() 里的长注释）。
# CAVA_PLATFORM_ID 的数值必须与 native/include/cava_abi.h 的 CAVA_PLATFORM_* 完全一致。
#
# 本机实测（2026-09，见 docs/CAVA-platform-notes.md）：
#   * 6 个 (SYSTEM_NAME, PROCESSOR) 组合 → 6 个正确标签/编号：由 tools/platform-tagmatrix.ps1
#     用**真实 CMake 配置**穷举（-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY 让交叉配置
#     在没有目标工具链的机器上也能走到本函数）。
#   * Apple 分支：-DCMAKE_SYSTEM_NAME=Darwin -DCMAKE_SYSTEM_PROCESSOR=x86_64
#     -DCMAKE_OSX_ARCHITECTURES=arm64 必须得到 macos-arm64（不修的话是 macos-x64）。
#
# 用法：project() 之后 include，然后调用 cava_configure_platform()。
# =============================================================================

set(CAVA_PLATFORM_WINDOWS_X64   1)
set(CAVA_PLATFORM_WINDOWS_ARM64 2)
set(CAVA_PLATFORM_LINUX_X64     3)
set(CAVA_PLATFORM_LINUX_ARM64   4)
set(CAVA_PLATFORM_MACOS_X64     5)
set(CAVA_PLATFORM_MACOS_ARM64   6)

function(cava_configure_platform)
    string(TOLOWER "${CMAKE_SYSTEM_NAME}" _cava_sys)

    # -------------------------------------------------------------------------
    # 目标架构的**权威来源**（这一段决定 natives/<tag>/ 落在哪个目录，错标不会报错）
    #
    # 1) 交叉编译（toolchain file / -DCMAKE_SYSTEM_NAME=...）时 CMAKE_SYSTEM_PROCESSOR 就是目标架构
    #    —— 但**前提是 toolchain file 自己设了它**。证据（CMake 3.31 源码）：
    #    Modules/CMakeDetermineSystem.cmake:156-161
    #      if(CMAKE_SYSTEM_NAME)  # toolchain file 设过
    #        # Assume it set CMAKE_SYSTEM_VERSION and CMAKE_SYSTEM_PROCESSOR too.
    #    没设就会是空串，下面会 FATAL_ERROR（fail-closed，不静默猜）。
    # 2) 本机构建时 CMake 把它设成 CMAKE_HOST_SYSTEM_PROCESSOR（同一文件 :173）。
    # 3) **例外：Apple（本函数必须显式处理，否则会静默错标）**。
    #    同一文件的 else 分支（:167-182）**根本不看 CMAKE_OSX_ARCHITECTURES**，而整个
    #    Modules/ 树里也没有任何地方从它反推 CMAKE_SYSTEM_PROCESSOR
    #    （实测：grep -r CMAKE_OSX_ARCHITECTURES Modules/ 共 30 处命中，无一处给
    #     CMAKE_SYSTEM_PROCESSOR 赋值；Platform/Darwin*.cmake 里 SYSTEM_PROCESSOR 只被"读"，
    #     见 Platform/Darwin.cmake:251）。
    #    ⇒ 在 Intel mac 上 `-DCMAKE_OSX_ARCHITECTURES=arm64` 时 CMAKE_SYSTEM_PROCESSOR 仍是
    #      x86_64，标签会推成 macos-x64，**把 arm64 的 dylib 写进 natives/macos-x64/**，
    #      Java 侧在 Apple Silicon 上找不到库 → 静默整体回退纯 Java（"能用但没加速"）。
    #    所以 Apple 上一律以 CMAKE_OSX_ARCHITECTURES 为准。
    # -------------------------------------------------------------------------
    set(_cava_proc "${CMAKE_SYSTEM_PROCESSOR}")
    set(_cava_proc_source "CMAKE_SYSTEM_PROCESSOR")
    if(APPLE AND DEFINED CMAKE_OSX_ARCHITECTURES AND NOT "${CMAKE_OSX_ARCHITECTURES}" STREQUAL "")
        list(LENGTH CMAKE_OSX_ARCHITECTURES _cava_osx_arch_count)
        if(_cava_osx_arch_count GREATER 1)
            message(FATAL_ERROR
                "cava: CMAKE_OSX_ARCHITECTURES='${CMAKE_OSX_ARCHITECTURES}' 是 universal binary（多架构）。\n"
                "平台标签 natives/<系统-架构>/ 只能取**单个**架构，通用二进制放进任何一个都会让另一种机器找不到库。\n"
                "请每个架构单独配置一次（-DCMAKE_OSX_ARCHITECTURES=arm64 / x86_64），"
                "要合并成 universal 就用 lipo 在 CMake 之外做，并把结果分别放进 natives/macos-arm64/ 与 natives/macos-x64/。")
        endif()
        set(_cava_proc "${CMAKE_OSX_ARCHITECTURES}")
        set(_cava_proc_source "CMAKE_OSX_ARCHITECTURES")
    endif()
    string(TOLOWER "${_cava_proc}" _cava_proc)
    set(CAVA_PLATFORM_PROCESSOR "${_cava_proc}" CACHE STRING "cava 目标架构（实际用于推导平台标签的值）" FORCE)
    set(CAVA_PLATFORM_PROCESSOR_SOURCE "${_cava_proc_source}" CACHE STRING "cava 目标架构取值来源" FORCE)

    set(_cava_tag "")
    set(_cava_id 0)

    if(_cava_sys STREQUAL "windows")
        if(_cava_proc MATCHES "^(amd64|x86_64|x64)$")
            set(_cava_tag "windows-x64")
            set(_cava_id ${CAVA_PLATFORM_WINDOWS_X64})
        elseif(_cava_proc MATCHES "^(arm64|aarch64)$")
            # ABI 里有编号，但 P0 只在 x64 上验证过（arm64 属 P4）
            set(_cava_tag "windows-arm64")
            set(_cava_id ${CAVA_PLATFORM_WINDOWS_ARM64})
        endif()
    elseif(_cava_sys STREQUAL "linux")
        if(_cava_proc MATCHES "^(amd64|x86_64|x64)$")
            set(_cava_tag "linux-x64")
            set(_cava_id ${CAVA_PLATFORM_LINUX_X64})
        elseif(_cava_proc MATCHES "^(arm64|aarch64)$")
            set(_cava_tag "linux-arm64")
            set(_cava_id ${CAVA_PLATFORM_LINUX_ARM64})
        endif()
    elseif(_cava_sys STREQUAL "darwin")
        if(_cava_proc MATCHES "^(amd64|x86_64|x64)$")
            set(_cava_tag "macos-x64")
            set(_cava_id ${CAVA_PLATFORM_MACOS_X64})
        elseif(_cava_proc MATCHES "^(arm64|aarch64)$")
            set(_cava_tag "macos-arm64")
            set(_cava_id ${CAVA_PLATFORM_MACOS_ARM64})
        endif()
    endif()

    if(_cava_tag STREQUAL "")
        message(FATAL_ERROR
            "cava: 不支持的平台组合 CMAKE_SYSTEM_NAME='${CMAKE_SYSTEM_NAME}' "
            "目标架构='${_cava_proc}'（来源 ${_cava_proc_source}，原始 CMAKE_SYSTEM_PROCESSOR='${CMAKE_SYSTEM_PROCESSOR}'）。\n"
            "支持的组合只有：windows-x64 / windows-arm64 / linux-x64 / linux-arm64 / macos-x64 / macos-arm64。\n"
            "交叉编译请在 toolchain file 里**同时**设置 CMAKE_SYSTEM_NAME 与 CMAKE_SYSTEM_PROCESSOR —— "
            "CMake 只在 CMAKE_SYSTEM_NAME 被设过时假定 toolchain file 已经把架构也设好了"
            "（Modules/CMakeDetermineSystem.cmake:156-158），漏设就是空串，这里直接拒绝而不是猜一个。\n"
            "Apple 上请改用 -DCMAKE_OSX_ARCHITECTURES=<arm64|x86_64>（本函数以它为准）。")
    endif()

    set(CAVA_PLATFORM_TAG "${_cava_tag}" CACHE STRING "cava 平台标签（决定 natives/ 子目录）" FORCE)
    set(CAVA_PLATFORM_ID  "${_cava_id}"  CACHE STRING "cava 平台编号（与 cava_abi.h 的 CAVA_PLATFORM_* 一致）" FORCE)

    # 产物目录：库一律落到 <repo>/natives/<tag>/
    set(CAVA_NATIVES_DIR "${CMAKE_SOURCE_DIR}/natives/${CAVA_PLATFORM_TAG}" CACHE PATH "cava 原生库输出目录" FORCE)
    # import lib / pdb / 其它中间产物落 build/，不污染 natives/
    set(CAVA_IMPORT_LIB_DIR "${CMAKE_BINARY_DIR}/import-lib" CACHE PATH "cava Windows import lib 输出目录" FORCE)

    file(MAKE_DIRECTORY "${CAVA_NATIVES_DIR}")
    file(MAKE_DIRECTORY "${CAVA_IMPORT_LIB_DIR}")

    # 平台相关的导出补充：cava_abi.h 是冻结的、没有 dllexport 宏，
    # GNU/Clang 在 Windows 上必须显式要求导出全部符号（MSVC 走 WINDOWS_EXPORT_ALL_SYMBOLS）。
    set(CAVA_PLATFORM_EXPORT_LINK_OPTIONS "" CACHE STRING "cava 平台导出相关链接选项" FORCE)
    if(WIN32 AND NOT MSVC AND CAVA_EXPORT_ALL_SYMBOLS)
        set(CAVA_PLATFORM_EXPORT_LINK_OPTIONS "-Wl,--export-all-symbols" CACHE STRING "cava 平台导出相关链接选项" FORCE)
    endif()
endfunction()
