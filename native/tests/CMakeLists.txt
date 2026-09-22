# =============================================================================
# Cava —— 原生测试（文件所有权：P0-A 构建基建流；测试源码由 native 侧负责）
#
# 约定：
#   * native/tests/*.c|*.cpp 每个文件 => 一个可执行 target cava_test_<文件名> + 一个 CTest 用例 <文件名>。
#     GLOB + CONFIGURE_DEPENDS：加测试文件**不需要**改这个文件。
#   * 参数：默认**不传任何 argv**（cava_fp_probe 把 argv[1] 当输出文件路径，不能被占用）。
#     确实需要 argv 的测试在下面 CAVA_TEST_ARGS_<名字> 里登记（告诉 P0-A，或自己加一行）。
#   * 环境变量（测试进程一定能拿到）：
#       CAVA_NATIVE_LIB = 原生库全路径（Windows 下 = <repo>/natives/<tag>/cava.dll）
#       PATH            = natives/<tag>/ + 编译器 bin（MinGW 运行时 DLL）+ 原 PATH
#   * 工作目录 = 仓库根（与 cava_fp_probe 注释里的默认输出路径 native/tests/vectors/fp_probe.txt 一致）；
#     native/tests/vectors/ 由本文件在 configure 期建好。
#   * 需要 gtest 之类框架时找 P0-A 加依赖，不要在这里自己 add_subdirectory 拉第三方源码。
# 跑法：ctest --test-dir build/native-mingw --output-on-failure
#
# 坑（本机实测）：不要用 file(STRINGS) 解析带中文注释的参数文件 —— CMake 的 file(STRINGS)
# 按「ASCII 串」解析，非 ASCII 字节会被当成行分隔符，中文注释会被切成好几段变成多个 argv。
# =============================================================================

file(GLOB CAVA_TEST_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/*.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/*.cc"
    "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/*.cxx"
)

if(NOT CAVA_TEST_SOURCES)
    message(STATUS "cava tests: native/tests 下没有测试源文件，跳过")
    return()
endif()

set(CAVA_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../..")

# cava_fp_probe 默认把向量写到 native/tests/vectors/fp_probe.txt（相对工作目录 = 仓库根）
file(MAKE_DIRECTORY "${CAVA_ROOT_DIR}/native/tests/vectors")

# 需要 argv 的测试在这里登记（默认谁都拿不到 argv）
set(CAVA_TEST_ARGS_cava_dll_loadtest "$<TARGET_FILE:cava>")

# Windows 上测试 exe 需要能找到 cava.dll（natives/<tag>/）与编译器运行时 DLL（MinGW: libstdc++-6.dll 等）。
# 注意：ENVIRONMENT 是 CMake **列表**变量，PATH 里的 ';' 必须转义成 '\;'，否则会被拆成多个列表项、
# 后半截 PATH 直接丢掉 —— 本机实测踩过（表现为 ctest 里 exe 起不来，0xc0000135 = DLL not found）。
set(_cava_test_path "")
if(WIN32)
    get_filename_component(_cava_cxx_bin "${CMAKE_CXX_COMPILER}" DIRECTORY)
    set(_cava_test_path "${CAVA_NATIVES_DIR};${_cava_cxx_bin};$ENV{PATH}")
    string(REPLACE ";" "\\;" _cava_test_path "${_cava_test_path}")
endif()

foreach(_src IN LISTS CAVA_TEST_SOURCES)
    get_filename_component(_name "${_src}" NAME_WE)
    set(_target "cava_test_${_name}")

    add_executable(${_target} "${_src}")
    cava_configure_target(${_target})
    cava_apply_build_definitions(${_target})

    target_include_directories(${_target} PRIVATE
        "${CAVA_ROOT_DIR}/native/include"
        "${CAVA_ROOT_DIR}/native/src"
    )

    set(_extra_args "")
    if(DEFINED "CAVA_TEST_ARGS_${_name}")
        set(_extra_args "${CAVA_TEST_ARGS_${_name}}")
    endif()

    if(TARGET cava)
        target_link_libraries(${_target} PRIVATE cava)
    endif()
    add_test(NAME "${_name}" COMMAND ${_target} ${_extra_args})

    set_tests_properties("${_name}" PROPERTIES WORKING_DIRECTORY "${CAVA_ROOT_DIR}")

    set(_test_env "")
    if(WIN32)
        list(APPEND _test_env "PATH=${_cava_test_path}")
    endif()
    if(TARGET cava)
        list(APPEND _test_env "CAVA_NATIVE_LIB=$<TARGET_FILE:cava>")
    endif()
    if(_test_env)
        set_tests_properties("${_name}" PROPERTIES ENVIRONMENT "${_test_env}")
    endif()
endforeach()

list(LENGTH CAVA_TEST_SOURCES _cava_test_count)
message(STATUS "cava tests: ${_cava_test_count} 个测试 target（CTest 用例名 = 文件名；CAVA_NATIVE_LIB 已注入）")
