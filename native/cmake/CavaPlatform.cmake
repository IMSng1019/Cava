# =============================================================================
# CavaPlatform.cmake —— 平台推导与输出目录（文件所有权：P0-A 构建基建流）
#
# CAVA_PLATFORM_TAG 形如 windows-x64 / linux-x64 / linux-arm64 / macos-x64 / macos-arm64，
# 必须用 **CMAKE_SYSTEM_PROCESSOR**（不是 CMAKE_HOST_SYSTEM_PROCESSOR），交叉编译才正确。
# CAVA_PLATFORM_ID 的数值必须与 native/include/cava_abi.h 的 CAVA_PLATFORM_* 完全一致。
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
    string(TOLOWER "${CMAKE_SYSTEM_PROCESSOR}" _cava_proc)
    string(TOLOWER "${CMAKE_SYSTEM_NAME}" _cava_sys)

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
            "CMAKE_SYSTEM_PROCESSOR='${CMAKE_SYSTEM_PROCESSOR}'。\n"
            "支持的组合只有：windows-x64 / windows-arm64 / linux-x64 / linux-arm64 / macos-x64 / macos-arm64。\n"
            "交叉编译请在 toolchain file 里正确设置 CMAKE_SYSTEM_NAME 与 CMAKE_SYSTEM_PROCESSOR。")
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
