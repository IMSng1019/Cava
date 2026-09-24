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

# --- 0. 先把 Release/RelWithDebInfo/MinSizeRel 里的 -O3 去掉 ------------------
# CMake 内置的 CMAKE_CXX_FLAGS_RELEASE = "-O3 -DNDEBUG"。编译命令行的顺序是
#   CMAKE_CXX_FLAGS -> CMAKE_CXX_FLAGS_<CONFIG> -> target_compile_options
# 我们的 -O2 在最后，GCC/Clang 取**最后一个** -O ⇒ 实际上确实是 -O2。
# 但"靠顺序取胜"太脆：任何人往 CMAKE_CXX_FLAGS 里补一个 -O3（那会排在最后）就静默变成 -O3。
# 契约 §2.1.5 写死 -O2，所以这里把它**显式删掉**，让命令行里只剩一个 -O（工具可以据此断言）。
# 只动本工程自己的配置变量，且只删 -O3/-O4（-O0/-O1/-O2/-Os 都是调用者的明确意图，保留）。
if(NOT MSVC)
    set(CAVA_STRIP_RELEASE_O3 ON CACHE BOOL "从 Release 系配置里删掉 CMake 默认的 -O3（契约要求 -O2）")
    if(CAVA_STRIP_RELEASE_O3)
        foreach(_cfg CMAKE_CXX_FLAGS_RELEASE CMAKE_C_FLAGS_RELEASE
                     CMAKE_CXX_FLAGS_RELWITHDEBINFO CMAKE_C_FLAGS_RELWITHDEBINFO
                     CMAKE_CXX_FLAGS_MINSIZEREL CMAKE_C_FLAGS_MINSIZEREL)
            if(DEFINED ${_cfg} AND NOT "${${_cfg}}" STREQUAL "")
                set(_before "${${_cfg}}")
                string(REGEX REPLACE "(^| )-[Oo]3( |$)" " " _after "${_before}")
                string(REGEX REPLACE "(^| )-[Oo]4( |$)" " " _after "${_after}")
                string(STRIP "${_after}" _after)
                if(NOT _after STREQUAL _before)
                    set(${_cfg} "${_after}")
                    message(STATUS "cava: ${_cfg}: '${_before}' -> '${_after}'（契约要求 -O2，不允许 -O3 排在后面）")
                endif()
            endif()
        endforeach()
    endif()
endif()

# --- 1. 组装并校验 CAVA_HARDENED_FP_FLAGS -------------------------------------
set(_cava_fp_flags "")
if(MSVC)
    set(_cava_fp_candidates "/O2" "/fp:strict")
else()
    # -ffp-contract=off 在 **Apple Clang 上是必须显式给的**：
    # Apple Clang 的 -ffp-contract 默认是 on（GCC 默认才是 fast），而 arm64 一定有 FMA 指令，
    # 于是 a*b+c 会被合成为 fma(a,b,c)（只舍入一次），与 Java 的"乘一次舍一次、加一次舍一次"不同。
    # 契约 §2.1.5 与平台文档规则 2 都要求关掉；check_cxx_compiler_flag 会验证 Apple Clang 真的接受它
    # （clang 支持的取值是 on|off|fast|fast-honor-pragmas，off 一直有效）。
    # -fno-fast-math 在 Apple Clang 上同样有效，用来兜住 -ffast-math/-funsafe-math-optimizations。
    #
    # -fno-math-errno：**这是本机实测逼出来的第 5 个必需参数，不是抄来的**。
    # 起因：平台套件（native/tests/platform/cava_platform_suite.cpp）第一次跑就报 sqrt 有 3 行
    # 数值位不一致（1 ulp）+ 2 行 sNaN 没静音。反汇编定位到根因：
    #     MinGW g++ 15.2 在 -O2 -fwrapv -ffp-contract=off -fno-fast-math 下，
    #     std::sqrt **有时**内联成正确舍入的 sqrtsd，**有时**退化成 call sqrt（msvcrt）。
    #     实测对比（同一台机、同一批输入）：
    #       a=3FEFFFFFFFFFFFFF  sqrtsd->3FEFFFFFFFFFFFFF   msvcrt->3FF0000000000000  (差 1 ulp)
    #       a=7FEFFFFFFFFFFFFF  sqrtsd->5FEFFFFFFFFFFFFF   msvcrt->5FF0000000000000  (差 1 ulp)
    #       a=7FF0000000000001  sqrtsd->7FF8000000000001   msvcrt->7FF0000000000001  (sNaN 未静音)
    #     Java 21 的 Math.sqrt / StrictMath.sqrt 与 sqrtsd 五项全同 ⇒ 库调用那条路会破坏
    #     「sqrt 允许跨界且逐位一致」这条契约（平台文档 §1.2 规则 1 点名 sqrt 是允许的）。
    # 为什么是 -fno-math-errno 而不是别的：GCC 只有在**不需要维护 errno** 时才被允许无条件把
    # sqrt 展开成指令（否则必须为负输入保留 errno=EDOM 的库调用路径，展开与否就取决于上下文，
    # 于是同一个宏在不同 TU / 不同语句里得到两种结果 —— 这正是实测看到的）。
    # 安全性：本仓库 native/** 里**没有任何一处读写 errno**（grep errno/EDOM/math_errhandling 全空），
    # 所以丢掉 errno 副作用不影响任何行为；它**不是** fast-math 家族的开关，
    # 不允许重结合、不 flush 非规格化数、不放松舍入 —— 逐位一致性只会更稳。
    # 实测（CMake 重编后的 cava.dll，objdump）：sqrtsd/sqrtss 有、call <sqrt> = 0。
    set(_cava_fp_candidates "-O2" "-fwrapv" "-ffp-contract=off" "-fno-fast-math" "-fno-math-errno")
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
set(CAVA_HARDENED_FP_FLAGS_LIST "${_cava_fp_flags}" CACHE STRING "cava 硬性编译参数（列表形式，给测试 target 用）" FORCE)

# Apple Clang：把"我们确实显式关掉了 contraction"写进配置日志（取证用）。
if(APPLE AND NOT MSVC)
    if(CMAKE_CXX_COMPILER_ID MATCHES "AppleClang|Clang")
        message(STATUS "cava: Apple Clang ${CMAKE_CXX_COMPILER_VERSION}：已显式传 -ffp-contract=off "
                       "（Apple Clang 默认 on；arm64 有 FMA，不关就会把 a*b+c 合成为单次舍入的 fma）")
    else()
        message(WARNING "cava: APPLE 上的编译器是 ${CMAKE_CXX_COMPILER_ID}，不是 Apple Clang —— "
                        "请确认它同样接受 -ffp-contract=off 且默认不是 on"
                        "（下面 check_cxx_compiler_flag 只验「接受」，不验「默认值」）")
    endif()
endif()

# 明确禁止的危险参数（写在这里当文档，同时防止有人从外面塞进来）
#   -march=native / -mtune=native : 让产物绑定构建机 CPU，跨机分发 ⇒ SIGILL；且改变 FP 收缩与向量化
#   -ffast-math / -Ofast          : flush 非规格化数 + 重结合，逐位一致性直接消失
#   -ffp-contract=on|fast         : 平台文档规则 2，a*b+c 实测 25.5% 位不一致
#   /fp:fast                      : MSVC 的 -ffast-math
#   /arch:AVX*                    : MSVC 的 -march=native（本项目**没有**运行期分派代码，
#                                   一旦打开就是整库绑定 AVX，老 CPU 直接 SIGILL）
# 注意：-march=x86-64-v2 之类**基线**提升没有被禁（平台文档规则 12 允许"x86-64-v2 + 运行期分派"），
# 但当前代码里没有任何运行期分派，真要开之前先读 docs/CAVA-platform-notes.md 的"必须一起做的事"。
set(CAVA_FORBIDDEN_FP_FLAGS
    "-march=native;-mtune=native;-mcpu=native;-ffast-math;-Ofast"
    "-ffp-contract=on;-ffp-contract=fast;/fp:fast;/arch:AVX;/arch:AVX2;/arch:AVX512")
foreach(_flag IN LISTS CAVA_FORBIDDEN_FP_FLAGS)
    foreach(_where CMAKE_CXX_FLAGS CMAKE_CXX_FLAGS_RELEASE CMAKE_CXX_FLAGS_RELWITHDEBINFO
                  CMAKE_C_FLAGS CMAKE_C_FLAGS_RELEASE CMAKE_C_FLAGS_RELWITHDEBINFO)
        if(DEFINED ${_where} AND "${${_where}}" MATCHES "${_flag}")
            message(FATAL_ERROR
                "cava: 检测到被禁止的编译参数 '${_flag}'（在 ${_where} 里；破坏跨平台数值一致性）。"
                "见 native/cmake/CavaFlags.cmake 顶部的理由表。")
        endif()
    endforeach()
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
