# CavaHash.cmake —— 构建后打印产物指纹（Size + SHA256）。
#
# 为什么存在：`natives/<tag>/` 是**共享输出目录**，而且有**多个生产者**（根 CMakeLists 的 cava target 与
# native/tests/build-mingw.ps1）。本项目已经**两次**出现"某条流的验证被另一次重建静默作废"
# （见 docs/CAVA-gates.md 门禁 #8 §8.4）。把指纹打进构建日志，"当前交付物是哪一份"就不再依赖谁的记忆。
#
# 用法：
#   cmake -DCAVA_HASH_FILE=<文件路径> -P native/cmake/CavaHash.cmake
#   （根 CMakeLists 已把它挂在 cava target 的 POST_BUILD 上）

if(NOT DEFINED CAVA_HASH_FILE)
    message(FATAL_ERROR "CavaHash.cmake: 必须给 -DCAVA_HASH_FILE=<文件路径>")
endif()
if(NOT EXISTS "${CAVA_HASH_FILE}")
    message(FATAL_ERROR "CavaHash.cmake: 文件不存在: ${CAVA_HASH_FILE}")
endif()

file(SIZE "${CAVA_HASH_FILE}" _cava_artifact_size)
file(SHA256 "${CAVA_HASH_FILE}" _cava_artifact_sha)
message(STATUS "cava: ARTIFACT ${CAVA_HASH_FILE} size=${_cava_artifact_size} sha256=${_cava_artifact_sha}")
message(STATUS "cava: 注意：natives/<tag>/ 是共享输出目录，任何『交付物是 X』的说法都必须连同这一行记录；重建即作废。")
