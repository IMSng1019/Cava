# native/ —— Cava 原生核心

Fabric 服务端 mod "Cava" 的原生侧。契约权威文件是 `include/cava_abi.h`，
共享纪律见 `../docs/CAVA-工程接口契约.md`，本侧的实测结论见 `../docs/CAVA-native-notes.md`。

## 目录

    native/
      include/cava_abi.h     ← ABI 唯一权威定义（captain 持有，**已冻结**）
      src/                   ← 实现（所有权：P0-B 原生核心流）
        cava_internal.h        内部实现头（不是 ABI；含 CAVA_SAFE 探测与 CAVA_ASSERT）
        cava_abi.cpp           cava_build_id / cava_abi_touch / cava_abi_version
        cava_build.cpp         build_flags / platform_code / SAFE 断言基础设施
        cava_layout.cpp        布局自检（cava_layout_report + 内部 layout 表）
        cava_handle.cpp        cava_open / cava_close（槽位表 + generation + magic）
        cava_numeric.cpp       cava_d2i_sat / cava_d2l_sat / bits_of_double / double_of_bits
      tests/                 ← 自测与证据（所有权：P0-B）
        build-mingw.ps1        一键：编译 + 自测 + 生成向量 + 动态加载测试 + Java 比对
        cava_selftest.cpp      67 项自测（ABI/布局/open/close/饱和转换/位模式往返/金丝雀）
        cava_fp_probe.cpp      生成 vectors/fp_probe.txt（+ - * / sqrt 的逐位结果）
        cava_dll_loadtest.cpp  LoadLibrary + GetProcAddress 调一遍全部 ABI 符号
        java/FpProbeJava.java  JDK 21 侧对照物：重算 + - * / sqrt 并逐位比对
        vectors/fp_probe.txt   15456 条黄金向量（**进 git**，Java 单元层测试用）
        vectors/layout_expected.txt  布局黄金参考（offset/size/hash/总和）
      CMakeLists.txt         ← 构建（所有权：P0-A；GLOB_RECURSE 收 src 下的 .c/.cpp）

## 两条构建路径

**A. 不依赖 CMake（P0-B 自测用，随时可用）**

    pwsh -File native/tests/build-mingw.ps1

**B. 走仓库 CMake（P0-A 维护，产物落 `natives/<平台标签>/cava.dll`）**

    $cmake = 'J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe'
    & $cmake -S J:\mc\Cava -B J:\mc\Cava\build\native-mingw -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=C:/mingw64/bin/g++.exe
    & $cmake --build J:\mc\Cava\build\native-mingw --parallel

## 硬纪律（契约 `2.1，违反即返工）

1. 只有 `+ - * /` 与 `sqrt` 允许在原生侧参与可观测数值计算；超越函数一律留 Java。
2. 编译固定 `-O2 -fwrapv -ffp-contract=off -fno-fast-math`（MSVC `/O2 /fp:strict`），禁 `-march=native`。
3. `double` → 整数一律走 `cava_d2i_sat` / `cava_d2l_sat`（Java 越界饱和，C++ 直接转是 UB）。
4. 结构体只通过指针跨边界；禁用 `long`、裸 `char` 做数值、`long double`。
5. **Windows 上 cava.dll 必须静态链接 MinGW 运行库**（`-static -static-libgcc -static-libstdc++`）：
   否则依赖 `libstdc++-6.dll` / `libgcc_s_seh-1.dll` / `libwinpthread-1.dll`，
   在没装 MinGW 的机器上 Java 侧加载会失败（本机实测：干净 PATH 下 `LoadLibrary` 报 126）。
   `build-mingw.ps1` 会检查导入表并在违反时直接失败。
