# =============================================================================
# tag_matrix.cmake —— 平台标签推导的穷举测试（P4-B）
#
# 一口气跑完，任何装了 CMake 的机器都能跑（5 个 CI 平台 + 本机同一套命令）：
#   cmake -DCAVA_TAGMATRIX_WORK=<可写目录> -P native/tests/platform/tag_matrix.cmake
# 由 tools/platform-tagmatrix.ps1 包一层（Windows 上更顺手）。
#
# 为什么这不是"假装在交叉编译"：
#   平台标签推导的输入只有 4 个变量（CMAKE_SYSTEM_NAME / CMAKE_SYSTEM_PROCESSOR /
#   CMAKE_OSX_ARCHITECTURES / APPLE），输出只有 2 个（CAVA_PLATFORM_TAG / CAVA_PLATFORM_ID）。
#   本脚本把这两个层面都真跑一遍：
#     第 1 部分：用 'cmake -P' 直接调 cava_configure_platform()，穷举 10 个组合（含必须被拒绝的）。
#     第 2 部分：用**真实 cmake configure**（project(LANGUAGES NONE)，所以不需要任何编译器/工具链）
#                对 7 个组合各配置一个独立 build 目录，再从 CMakeCache.txt 里读回标签与编号。
#                ⇒ 这一步真的走了 project() → CMakeDetermineSystem → CavaPlatform.cmake 全链路。
#   本机没有 Linux/macOS 工具链，所以"真的编译出 .so/.dylib"这件事**没有**被验证（如实标注，
#   见 docs/CAVA-platform-notes.md）。
# =============================================================================
cmake_minimum_required(VERSION 3.24)

if(NOT DEFINED CAVA_TAGMATRIX_WORK)
    message(FATAL_ERROR "需要 -DCAVA_TAGMATRIX_WORK=<可写目录>")
endif()
set(_work "${CAVA_TAGMATRIX_WORK}")
file(MAKE_DIRECTORY "${_work}")
set(_case_script "${CMAKE_CURRENT_LIST_DIR}/tag_matrix_case.cmake")
if(NOT EXISTS "${_case_script}")
    message(FATAL_ERROR "找不到 ${_case_script}")
endif()

set(_pass 0)
set(_fail 0)
set(_log "")

function(_record ok msg)
    if(${ok})
        math(EXPR _p "${_pass} + 1")
        set(_pass ${_p} PARENT_SCOPE)
        message(STATUS "  [ ok ] ${msg}")
    else()
        math(EXPR _f "${_fail} + 1")
        set(_fail ${_f} PARENT_SCOPE)
        message(STATUS "  [FAIL] ${msg}")
    endif()
endfunction()

# name | SYSTEM_NAME | PROCESSOR | OSX_ARCHITECTURES | APPLE | expected tag ("" = 必须被拒绝) | expected id
# 字段用 '-' 当"空"的哨兵：CMake 的 list 会丢掉尾部空元素，直接用空串会让 list(GET) 越界
# （本机实测踩过：linux-no-processor / macos-universal 两行直接 "list index out of range"）。
# 多架构用 ',' 分隔，读出来后换成 ';'。
set(_cases
    "windows-x64|Windows|AMD64|-|-|windows-x64|1"
    "windows-arm64|Windows|ARM64|-|-|windows-arm64|2"
    "linux-x64|Linux|x86_64|-|-|linux-x64|3"
    "linux-arm64|Linux|aarch64|-|-|linux-arm64|4"
    "macos-x64|Darwin|x86_64|-|TRUE|macos-x64|5"
    "macos-arm64|Darwin|arm64|-|TRUE|macos-arm64|6"
    "macos-arm64-on-x64-host|Darwin|x86_64|arm64|TRUE|macos-arm64|6"
    "macos-universal|Darwin|x86_64|x86_64,arm64|TRUE|-|-"
    "linux-no-processor|Linux|-|-|-|-|-"
    "freebsd-x64|FreeBSD|x86_64|-|-|-|-"
)

message(STATUS "=== tag_matrix part 1: cava_configure_platform() as a pure function (cmake -P) ===")
foreach(_c IN LISTS _cases)
    string(REPLACE "|" ";" _f "${_c}")
    list(GET _f 0 _name)
    list(GET _f 1 _sys)
    list(GET _f 2 _proc)
    list(GET _f 3 _osx)
    list(GET _f 4 _apple)
    list(GET _f 5 _want_tag)
    list(GET _f 6 _want_id)
    if(_proc STREQUAL "-")
        set(_proc "")
    endif()
    if(_osx STREQUAL "-")
        set(_osx "")
    else()
        string(REPLACE "," ";" _osx "${_osx}")
    endif()
    if(_apple STREQUAL "-")
        set(_apple "")
    endif()
    if(_want_tag STREQUAL "-")
        set(_want_tag "")
    endif()

    set(_cw "${_work}/case-${_name}")
    file(REMOVE_RECURSE "${_cw}")
    file(MAKE_DIRECTORY "${_cw}")
    set(_args "-DCAVA_TAGTEST_WORK=${_cw}" "-DCAVA_TAGTEST_SYS=${_sys}" "-DCAVA_TAGTEST_PROC=${_proc}")
    if(NOT _osx STREQUAL "")
        # list(APPEND) 会把值里的 ';' 当列表分隔符再拆一次（本机实测：universal 那一行因此
        # 只把 x86_64 传了进去，"必须被拒绝"的用例假通过）。这里显式转义。
        string(REPLACE ";" "\\;" _osx_esc "${_osx}")
        list(APPEND _args "-DCAVA_TAGTEST_OSXARCH=${_osx_esc}")
    endif()
    if(NOT _apple STREQUAL "")
        list(APPEND _args "-DCAVA_TAGTEST_APPLE=${_apple}")
    endif()
    list(APPEND _args -P "${_case_script}")
    execute_process(
        COMMAND "${CMAKE_COMMAND}" ${_args}
        RESULT_VARIABLE _rc
        OUTPUT_VARIABLE _out
        ERROR_VARIABLE _err
    )
    set(_all "${_out}${_err}")
    if(_all MATCHES "TAG=([^ \r\n]*) ID=([^ \r\n]*) PROC=([^ \r\n]*) SRC=([^ \r\n]*)")
        set(_got_tag "${CMAKE_MATCH_1}")
        set(_got_id "${CMAKE_MATCH_2}")
        set(_got_src "${CMAKE_MATCH_4}")
    else()
        set(_got_tag "")
        set(_got_id "")
        set(_got_src "")
    endif()

    if(_want_tag STREQUAL "")
        _record(${_rc} "${_name}: 必须被拒绝（rc=${_rc}, tag='${_got_tag}'）")
    else()
        set(_ok FALSE)
        if(_rc EQUAL 0 AND _got_tag STREQUAL _want_tag AND "${_got_id}" STREQUAL "${_want_id}")
            set(_ok TRUE)
        endif()
        _record(${_ok} "${_name}: sys=${_sys} proc='${_proc}' osx='${_osx}' -> tag=${_got_tag} id=${_got_id} src=${_got_src}（期望 ${_want_tag}/${_want_id}）")
    endif()
endforeach()

# ---------------------------------------------------------------------------
# 第 2 部分：真实 configure（project LANGUAGES NONE ⇒ 不需要任何编译器/工具链）
# 这才能真正走 project() -> CMakeDetermineSystem -> CavaPlatform.cmake 全链路。
# probe 工程写在 work 目录里，所以 file(MAKE_DIRECTORY natives/<tag>) 落在 work 里，
# **不会**碰仓库的共享 natives/。
# ---------------------------------------------------------------------------
message(STATUS "=== tag_matrix part 2: real configure (project LANGUAGES NONE, no toolchain needed) ===")
set(_probe "${_work}/probe")
file(MAKE_DIRECTORY "${_probe}")
file(WRITE "${_probe}/CMakeLists.txt"
"# 由 native/tests/platform/tag_matrix.cmake 生成的一次性探针工程。\n"
"# LANGUAGES NONE：不需要编译器，只验证平台标签推导。\n"
"cmake_minimum_required(VERSION 3.24)\n"
"project(cava_tag_probe LANGUAGES NONE)\n"
"list(APPEND CMAKE_MODULE_PATH \"${CMAKE_CURRENT_LIST_DIR}/../../cmake\")\n"
"set(CAVA_EXPORT_ALL_SYMBOLS OFF)\n"
"include(CavaPlatform)\n"
"cava_configure_platform()\n"
"message(STATUS \"PROBE platform=${CAVA_PLATFORM_TAG} id=${CAVA_PLATFORM_ID} processor=${CAVA_PLATFORM_PROCESSOR} source=${CAVA_PLATFORM_PROCESSOR_SOURCE} natives=${CAVA_NATIVES_DIR}\")\n")

set(_real_cases
    "windows-x64|Windows|AMD64|-|1|windows-x64"
    "windows-arm64|Windows|ARM64|-|2|windows-arm64"
    "linux-x64|Linux|x86_64|-|3|linux-x64"
    "linux-arm64|Linux|aarch64|-|4|linux-arm64"
    "macos-x64|Darwin|x86_64|-|5|macos-x64"
    "macos-arm64|Darwin|arm64|-|6|macos-arm64"
    "macos-arm64-on-x64-host|Darwin|x86_64|arm64|6|macos-arm64"
)
foreach(_c IN LISTS _real_cases)
    string(REPLACE "|" ";" _f "${_c}")
    list(GET _f 0 _name)
    list(GET _f 1 _sys)
    list(GET _f 2 _proc)
    list(GET _f 3 _osx)
    list(GET _f 4 _want_id)
    list(GET _f 5 _want_tag)
    if(_osx STREQUAL "-")
        set(_osx "")
    endif()

    set(_bd "${_work}/configure-${_name}")
    file(REMOVE_RECURSE "${_bd}")
    set(_args "-DCMAKE_SYSTEM_NAME=${_sys}" "-DCMAKE_SYSTEM_PROCESSOR=${_proc}")
    if(NOT _osx STREQUAL "")
        list(APPEND _args "-DCMAKE_OSX_ARCHITECTURES=${_osx}")
    endif()
    execute_process(
        COMMAND "${CMAKE_COMMAND}" -S "${_probe}" -B "${_bd}" ${_args}
        RESULT_VARIABLE _rc
        OUTPUT_VARIABLE _out
        ERROR_VARIABLE _err
    )
    set(_tag "")
    set(_id "")
    set(_proc "")
    set(_srcv "")
    set(_cache "${_bd}/CMakeCache.txt")
    if(EXISTS "${_cache}")
        file(STRINGS "${_cache}" _lines REGEX "^CAVA_PLATFORM_")
        foreach(_l IN LISTS _lines)
            if(_l MATCHES "^CAVA_PLATFORM_TAG:STRING=(.*)$")
                set(_tag "${CMAKE_MATCH_1}")
            elseif(_l MATCHES "^CAVA_PLATFORM_ID:STRING=(.*)$")
                set(_id "${CMAKE_MATCH_1}")
            elseif(_l MATCHES "^CAVA_PLATFORM_PROCESSOR:STRING=(.*)$")
                set(_proc "${CMAKE_MATCH_1}")
            elseif(_l MATCHES "^CAVA_PLATFORM_PROCESSOR_SOURCE:STRING=(.*)$")
                set(_srcv "${CMAKE_MATCH_1}")
            endif()
        endforeach()
    endif()
    set(_cross "?")
    if("${_out}${_err}" MATCHES "crosscompiling")
        set(_cross "见配置日志")
    endif()
    set(_ok FALSE)
    if(_rc EQUAL 0 AND _tag STREQUAL _want_tag AND "${_id}" STREQUAL "${_want_id}")
        set(_ok TRUE)
    endif()
    _record(${_ok} "${_name}: real configure sys=${_sys} proc='${_proc}' osx='${_osx}' -> CAVA_PLATFORM_TAG=${_tag} id=${_id} processor=${_proc} source=${_srcv}（期望 ${_want_tag}/${_want_id}）")
    if(NOT _ok)
        message(STATUS "         configure rc=${_rc}")
        message(STATUS "         ${_err}")
    endif()
endforeach()

message(STATUS "")
message(STATUS "TAGMATRIX|pass=${_pass}|fail=${_fail}|work=${_work}")
if(_fail GREATER 0)
    message(FATAL_ERROR "tag_matrix: ${_fail} 个组合不符合预期")
endif()
message(STATUS "TAGMATRIX: PASS（全部组合符合预期）")