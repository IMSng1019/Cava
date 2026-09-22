# =============================================================================
# CavaFlags.cmake —— 数值一致性的编译参数（文件所有权：P0-A 构建基建流）
#
# 契约 §2.1.5：-O2 -fwrapv -ffp-contract=off -fno-fast-math（MSVC: /O2 /fp:strict）；
# 整数溢出必须 wrap；**禁 -march=native / -ffast-math / -Ofast**。
# 这些参数是「逐 tick 一致」的前提，所以每个都要用 check_cxx_compiler_flag 验过；
# 缺任何一个都直接 FATAL_ERROR，绝不静默降级。
#
# 用法：include 后对每个原生 target 调用 cava_configure_target(<target>)。
# =============================================================================

include(CheckCXXCompilerFlag)
include(CheckCCompilerFlag)

# MSVC 上 check_cxx_compiler_flag 默认用 **Debug** 配置做 try_compile，而 Debug 带 /RTC1，
# 与 /O2 不兼容 -> cl 报 D8016 "/O2 和 /RTC1 不兼容" -> 误判「编译器不支持 /O2」并 FATAL_ERROR。
# 强制 try_compile 用 Release 配置（P0-D 实测发现，P0-A 修）。
set(CMAKE_TRY_COMPILE_CONFIGURATION Release)

# --- 1. 组装并校验 CAVA_HARDENED_FP_FLAGS -------------------------------------
set(_cava_fp_flags "")
if(MSVC)
    set(_cava_fp_candidates "/O2" "/fp:strict")
else()
    set(_cava_fp_candidates "-O2" "-fwrapv" "-ffp-contract=off" "-fno-fast-math")
endif()

foreach(_flag IN LISTS _cava_fp_candidates)
    string(MAKE_C_IDENTIFIER "${_flag}" _flag_var)
    check_cxx_compiler_flag("${_flag}" "CAVA_HAVE_FLAG_${_flag_var}")
    if(CAVA_HAVE_FLAG_${_flag_var})
        list(APPEND _cava_fp_flags "${_flag}")
    else()
        message(FATAL_ERROR
            "cava: 编译器 ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION} 不支持必需参数 '${_flag}'。\n"
            "这些参数是数值逐位一致（逐 tick 对齐）的前提，不允许静默降级 —— 请换工具链。")
    endif()
endforeach()

string(REPLACE ";" " " CAVA_HARDENED_FP_FLAGS "${_cava_fp_flags}")
set(CAVA_HARDENED_FP_FLAGS "${CAVA_HARDENED_FP_FLAGS}" CACHE STRING "cava 硬性编译参数" FORCE)

# 明确禁止的危险参数（写在这里当文档，同时防止有人从外面塞进来）
set(CAVA_FORBIDDEN_FP_FLAGS "-march=native;-mtune=native;-ffast-math;-Ofast;/fp:fast")
foreach(_flag IN LISTS CAVA_FORBIDDEN_FP_FLAGS)
    if("${CMAKE_CXX_FLAGS} ${CMAKE_CXX_FLAGS_RELEASE} ${CMAKE_C_FLAGS} ${CMAKE_C_FLAGS_RELEASE}" MATCHES "${_flag}")
        message(FATAL_ERROR "cava: 检测到被禁止的编译参数 '${_flag}'（破坏跨平台数值一致性）")
    endif()
endforeach()

# --- 2. 编译标识（生成 cava_build_id / CavaLayoutReport 用） -------------------
set(CAVA_BUILD_COMPILER "${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
if(MSVC)
    string(APPEND CAVA_BUILD_COMPILER " (MSVC toolset ${CMAKE_VS_PLATFORM_TOOLSET})")
else()
    string(APPEND CAVA_BUILD_COMPILER " (${CMAKE_CXX_COMPILER})")
endif()

# --- 3. target 配置函数 -------------------------------------------------------
# 编译参数 + 警告 + CAVA_SAFE / ASAN / UBSAN。链接选项一并加，测试 target 也走同一个函数。
function(cava_configure_target target)
    target_compile_options(${target} PRIVATE ${_cava_fp_flags})

    if(CAVA_SAFE)
        target_compile_definitions(${target} PRIVATE CAVA_SAFE_BUILD=1)
        if(MSVC)
            target_compile_options(${target} PRIVATE /UNDEBUG)   # 打开 assert
        else()
            target_compile_options(${target} PRIVATE -UNDEBUG -fno-omit-frame-pointer)
        endif()
    endif()

    if(MSVC)
        target_compile_options(${target} PRIVATE /W4 /permissive- /Zc:preprocessor /utf-8)
    else()
        target_compile_options(${target} PRIVATE -Wall -Wextra)
    endif()

    if(CAVA_ASAN)
        if(MSVC)
            message(FATAL_ERROR "cava: CAVA_ASAN 在 MSVC 上不支持，请用 MinGW/Clang 或关掉该选项")
        endif()
        target_compile_options(${target} PRIVATE -fsanitize=address -fno-omit-frame-pointer)
        target_link_options(${target} PRIVATE -fsanitize=address)
    endif()

    if(CAVA_UBSAN)
        if(MSVC)
            message(FATAL_ERROR "cava: CAVA_UBSAN 在 MSVC 上不支持，请用 MinGW/Clang 或关掉该选项")
        endif()
        target_compile_options(${target} PRIVATE -fsanitize=undefined -fno-sanitize-recover=undefined)
        target_link_options(${target} PRIVATE -fsanitize=undefined)
    endif()

    if(WIN32 AND NOT MSVC AND CAVA_EXPORT_ALL_SYMBOLS AND CAVA_PLATFORM_EXPORT_LINK_OPTIONS)
        target_link_options(${target} PRIVATE ${CAVA_PLATFORM_EXPORT_LINK_OPTIONS})
    endif()
endfunction()

# 编译期可见的构建标识（cava_build_id / 布局报告用；字符串宏一律加引号）
function(cava_apply_build_definitions target)
    target_compile_definitions(${target} PRIVATE
        CAVA_BUILD_PLATFORM_TAG="${CAVA_PLATFORM_TAG}"
        CAVA_BUILD_PLATFORM_ID=${CAVA_PLATFORM_ID}
        CAVA_BUILD_COMPILER="${CAVA_BUILD_COMPILER}"
        CAVA_BUILD_FP_FLAGS="${CAVA_HARDENED_FP_FLAGS}"
        CAVA_BUILD_TYPE="${CMAKE_BUILD_TYPE}"
        CAVA_BUILD_SAFE=$<BOOL:${CAVA_SAFE}>
        CAVA_BUILD_ASAN=$<BOOL:${CAVA_ASAN}>
        CAVA_BUILD_UBSAN=$<BOOL:${CAVA_UBSAN}>
        CAVA_BUILD_DEBUG=$<CONFIG:Debug>
    )
endfunction()

# 输出目录：库 -> natives/<tag>/（Windows 上只留 cava.dll），import lib / pdb -> build/import-lib/
# 多配置生成器（VS）默认会在输出目录后面再拼一个配置名，这里逐个配置显式覆盖掉。
function(cava_set_output_dirs target)
    set_target_properties(${target} PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED ON
        CXX_EXTENSIONS OFF
        C_STANDARD 11
        C_STANDARD_REQUIRED ON
        C_STANDARD 11
        OUTPUT_NAME "cava"
        POSITION_INDEPENDENT_CODE ON
        LIBRARY_OUTPUT_DIRECTORY "${CAVA_NATIVES_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${CAVA_NATIVES_DIR}"
        ARCHIVE_OUTPUT_DIRECTORY "${CAVA_IMPORT_LIB_DIR}"
        PDB_OUTPUT_DIRECTORY "${CAVA_IMPORT_LIB_DIR}"
    )
    foreach(_cfg "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZE)
        set_target_properties(${target} PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY${_cfg} "${CAVA_NATIVES_DIR}"
            RUNTIME_OUTPUT_DIRECTORY${_cfg} "${CAVA_NATIVES_DIR}"
            ARCHIVE_OUTPUT_DIRECTORY${_cfg} "${CAVA_IMPORT_LIB_DIR}"
            PDB_OUTPUT_DIRECTORY${_cfg} "${CAVA_IMPORT_LIB_DIR}"
        )
    endforeach()

    if(WIN32)
        # Windows 上产物文件名固定为 cava.dll：MinGW 默认会加 lib 前缀（libcava.dll），
        # 与 MSVC 的 cava.dll 不一致，也会逼 Java 侧按工具链猜文件名。
        set_target_properties(${target} PROPERTIES PREFIX "")
    endif()

    if(WIN32 AND NOT MSVC)
        # 只有**要发出去的库**做全静态运行时（免装 MinGW 运行时 DLL）。
        # 这里（cava_set_output_dirs）只被库 target 调用，测试 target 不调用：
        # 全静态的 libgcc_eh + --export-all-symbols 会让测试 exe 同时从 import lib 拿到
        # _Unwind_Resume，链接期 multiple definition（本机实测踩过）。
        # -static 必须带上：只写 -static-libgcc -static-libstdc++ 时实测仍会留下
        # libwinpthread-1.dll 依赖（objdump -p 验证过），等于没解决问题。
        # --exclude-libs：把静态运行时的符号标成不导出。否则 DLL 会把 libgcc 的
        # _Unwind_Resume 之类一起导出（--export-all-symbols），任何链我们 import lib 的 C++
        # 程序都会 "multiple definition of _Unwind_Resume"（本机实测，cava_fp_probe 挂在这）。
        # 只列运行时归档名，不写 ALL —— 我们自己的 objects.a 也在归档里，ALL 会把 ABI 符号一起藏掉。
        target_link_options(${target} PRIVATE
            -static-libgcc -static-libstdc++ -static
            "-Wl,--exclude-libs,libgcc.a:libgcc_eh.a:libstdc++.a:libsupc++.a:libwinpthread.a")
    endif()

    if(WIN32 AND MSVC AND CAVA_EXPORT_ALL_SYMBOLS)
        # cava_abi.h 是冻结的、没有 __declspec(dllexport)：MSVC 下必须整库导出
        set_target_properties(${target} PROPERTIES WINDOWS_EXPORT_ALL_SYMBOLS ON)
    endif()

    # 多配置生成器（VS）把 .dll 写进 natives，但 import lib 不能跟着去 natives
    if(WIN32 AND CMAKE_CONFIGURATION_TYPES)
        set_target_properties(${target} PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY "${CAVA_NATIVES_DIR}"
            ARCHIVE_OUTPUT_DIRECTORY "${CAVA_IMPORT_LIB_DIR}"
        )
    endif()
endfunction()
