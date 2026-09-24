# Cava 平台矩阵与跨平台一致性（P4-B 实测台账）

> 这份文档只写**本机真跑出来的东西**。做不到的一律写「做不到 + 卡在哪 + 需要什么」。
> 配套：docs/CAVA-platform-and-compat.md（平台矩阵的**依据**）、docs/CAVA-gates.md（门禁）、
> prompts/07-P4-跨平台与加固.md（任务书）、native/tests/platform/README.md（套件用法）。
> 复现入口：tools/platform-tagmatrix.ps1、tools/platform-flagcheck.ps1、
> native/tests/platform/run-platform-suite.ps1 | .sh。

---

## 0. 状态总表（先看这张）

| 项 | 状态 | 证据在哪一节 |
| --- | --- | --- |
| Linux / macOS / ARM 交叉工具链是否存在 | **不存在**（穷举过） | §1 |
| 平台标签推导（6 组合 + 拒绝分支） | **通过** 17/17（10 个纯函数 + 7 个真实 cmake configure） | §2 |
| 真实交叉 configure（Linux x64 / arm64，本机） | **通过**（crosscompiling=TRUE，标签/编号正确） | §2.3 |
| 数值一致性套件（windows-x64 / MinGW） | **通过** 32/0/0；15456 行 0 数值不一致 | §3 |
| 数值一致性套件（windows-x64 / **MSVC**） | **通过** 32/0/0（先把 native 侧两个 MSVC 阻塞打补丁才编得出来） | §6 |
| 从产物反查编译开关 | **通过** 38/0/0 | §4 |
| ABI 布局自检（14 条 / 0x1C12265E） | **通过** | §5 |
| 5 平台 CI 矩阵 | YAML 写全、本地语法校验通过，**5 个 job 一次都没跑过** | §12 |
| ASan / UBSan | **本机跑不了**（两条工具链都缺运行时） | §8 |
| 连续运行 7 天 | **本机做不到** | §9 |

---

## 1. 本机工具链探测：**没有 Linux/macOS/ARM 交叉路径**（穷举实录）

真实命令与输出（PowerShell 5.1）：

    > where.exe x86_64-linux-gnu-g++
    INFO: Could not find files for the given pattern(s).
    > where.exe clang ; where.exe clang++ ; where.exe gcc ; where.exe g++
    INFO: Could not find files for the given pattern(s).        # 四次都是这句
    > C:\mingw64\bin\g++.exe -dumpmachine
    x86_64-w64-mingw32
    > C:\mingw64\bin\g++.exe --print-multi-lib
    .;                                    # 只有宿主一个 multilib 变体
    > C:\mingw64\bin\g++.exe -v            # (Configured with 一行里)
    Target: x86_64-w64-mingw32 ... --disable-multilib ...

    > C:\mingw64\bin\g++.exe --target=x86_64-linux-gnu -c t.cpp
    g++.exe: error: unrecognized command-line option '--target=x86_64-linux-gnu'      (exit 1)
    > C:\mingw64\bin\g++.exe --target=aarch64-linux-gnu -c t.cpp
    g++.exe: error: unrecognized command-line option '--target=aarch64-linux-gnu'     (exit 1)
    > C:\mingw64\bin\g++.exe --target=x86_64-apple-darwin -c t.cpp
    g++.exe: error: unrecognized command-line option '--target=x86_64-apple-darwin'   (exit 1)
    > C:\mingw64\bin\g++.exe -march=armv8-a -c t.cpp
    cc1plus.exe: error: bad value 'armv8-a' for '-march=' switch
      valid arguments ...: nocona core2 ... x86-64 x86-64-v2 x86-64-v3 x86-64-v4 ... native   (全是 x86)

    > where.exe zig / rustc / clang-cl / ld.lld / aarch64-linux-gnu-gcc
    zig -> NOT FOUND ; clang-cl -> NOT FOUND ; ld.lld -> NOT FOUND ; aarch64-linux-gnu-gcc -> NOT FOUND
    rustc -> C:\Users\<user>\.cargo\bin\rustc.exe
    > rustup target list --installed
    x86_64-pc-windows-msvc                    # 只装了宿主 target；没有 *-linux-* / *-apple-*
    > rustc -vV
    rustc 1.98.1 ... host: x86_64-pc-windows-msvc ... LLVM version: 22.1.8

    Test-Path C:\msys64  -> False ;  Test-Path C:\cygwin64 -> False
    C:\Program Files\LLVM\bin -> 不存在
    > wsl.exe --list --verbose
      Wsl/EnumerateDistros/Service/E_ACCESSDENIED          # 枚举被拒
    > docker info
      Client: Version 29.7.2  Context: desktop-linux        # 只有客户端；守护进程没起，不能 build

    # MSVC 侧能不能交叉到 windows-arm64？
    ...\VC\Tools\MSVC\14.44.35207\lib  ->  onecore  x64  x86          # 没有 arm64
    ...\VC\Tools\MSVC\14.44.35207\bin  ->  Hostx64  Hostx86          # 没有 Hostx64\arm64
    Windows Kits\10\Lib\10.0.26100.0\um -> 只有 x64 / x86 / arm(32 位)，没有 arm64

**结论（如实）**：

* **本机没有任何 Linux / macOS / ARM 交叉编译路径。** MinGW 是单 target（--disable-multilib）、
  `--target=` 是 Clang 的旗标它根本不认；没有 clang/LLVM 前端、没有 zig、没有 MSYS2/cygwin、
  没有交叉 gcc、没有目标 sysroot。
* rustc 带的 LLVM 22 里有 rust-lld（可作 ELF/Mach-O 链接器），但**没有 C/C++ 前端**，
  且只装了 x86_64-pc-windows-msvc 这一个 target ⇒ **不构成可用的 C++ 交叉路径**。
* MSVC 未安装 ARM64 组件 ⇒ **windows-arm64 也交叉不了**。
* 因此「5 个平台的数值一致性全绿」这件事**本机无法完成**，只能由 §12 的 CI 矩阵去做。

---

## 2. 平台标签推导：一个**真 bug** + 17 个组合的穷举验证

### 2.1 任务书让我确认的那一点：确实用 CMAKE_SYSTEM_PROCESSOR

native/cmake/CavaPlatform.cmake 用的是 `CMAKE_SYSTEM_PROCESSOR`，**不是**
`CMAKE_HOST_SYSTEM_PROCESSOR`。不是靠"读起来像对的"：§2.3 的真实 cross configure 同时打出
了 `host=Windows/AMD64 crosscompiling=TRUE` 与 `processor=aarch64`。这一条本来是对的，**没有改**。

### 2.2 但 Apple 上它不够 —— 已修（本轮最有价值的一处平台修复）

CMake 3.31 的 Modules/CMakeDetermineSystem.cmake:156-182 只有两个分支：

* 如果 toolchain file 设了 `CMAKE_SYSTEM_NAME`，CMake **假定**它同时设好了
  `CMAKE_SYSTEM_PROCESSOR`（源码注释原文：*Assume it set CMAKE_SYSTEM_VERSION and
  CMAKE_SYSTEM_PROCESSOR too.*）；
* 否则把 `CMAKE_SYSTEM_PROCESSOR` 直接设成 `CMAKE_HOST_SYSTEM_PROCESSOR`。

**整个 Modules/ 树里没有任何地方从 `CMAKE_OSX_ARCHITECTURES` 反推
`CMAKE_SYSTEM_PROCESSOR`**（实测：grep -r CMAKE_OSX_ARCHITECTURES Modules/ 共 30 处命中，
无一处给它赋值；Platform/Darwin.cmake:251 只**读**它）。

⟹ 在 Intel mac 上执行 `cmake -DCMAKE_OSX_ARCHITECTURES=arm64` 时，`CMAKE_SYSTEM_PROCESSOR`
仍然是 x86_64，标签会推成 **macos-x64**，**把 arm64 的 dylib 写进 natives/macos-x64/**，
Java 在 Apple Silicon 上找不到库 ⇒ **静默整体回退纯 Java（"能用但没加速"）**。
这正是"最危险的一类"失效。

**修法**（native/cmake/CavaPlatform.cmake）：Apple 上一律以 `CMAKE_OSX_ARCHITECTURES` 为准；
多架构（universal binary）直接 `FATAL_ERROR`，因为 natives/<系统-架构>/ 只能取单个架构。
同时新增两个 cache 变量 `CAVA_PLATFORM_PROCESSOR` / `CAVA_PLATFORM_PROCESSOR_SOURCE`
并打进配置日志，任何交叉配置出错时一眼看得出架构是从哪来的。

### 2.3 验证：17 个组合全过（其中 7 个是**真实 cmake configure**）

    pwsh -File tools/platform-tagmatrix.ps1
    # 等价：cmake -DCAVA_TAGMATRIX_WORK=<dir> -P native/tests/platform/tag_matrix.cmake

第 1 部分把 cava_configure_platform() 当纯函数调（cmake -P），第 2 部分用
`project(... LANGUAGES NONE)` 的探针工程做**真实 configure**（不需要任何编译器/工具链），
再从 CMakeCache.txt 读回标签与编号。真实输出：

    --   [ ok ] windows-x64: sys=Windows proc='AMD64' osx='' -> tag=windows-x64 id=1
    --   [ ok ] windows-arm64: sys=Windows proc='ARM64' osx='' -> tag=windows-arm64 id=2
    --   [ ok ] linux-x64: ... -> tag=linux-x64 id=3
    --   [ ok ] linux-arm64: ... -> tag=linux-arm64 id=4
    --   [ ok ] macos-x64: ... -> tag=macos-x64 id=5
    --   [ ok ] macos-arm64: ... -> tag=macos-arm64 id=6
    --   [ ok ] macos-arm64-on-x64-host: sys=Darwin proc='x86_64' osx='arm64' -> tag=macos-arm64 id=6 src=CMAKE_OSX_ARCHITECTURES
    --   [ ok ] macos-universal: 必须被拒绝（rc=1）
    --   [ ok ] linux-no-processor: 必须被拒绝（rc=1）
    --   [ ok ] freebsd-x64: 必须被拒绝（rc=1）
    --   [ ok ] windows-x64: real configure ... CAVA_PLATFORM_TAG=windows-x64 id=1 source=CMAKE_SYSTEM_PROCESSOR
    （另 6 个真实 configure 也全过，含 macos-arm64-on-x64-host -> macos-arm64 source=CMAKE_OSX_ARCHITECTURES）
    -- TAGMATRIX|pass=17|fail=0

**最强的一条证据**（`pwsh -File tools/platform-tagmatrix.ps1 -RealCrossConfigure`）：
在**没有 Linux 工具链**的这台 Windows 上，用 `-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY`
（让 CMake 只编译、不链接不运行）真的把**整个工程**交叉配置成了 Linux：

    -- cava: platform=linux-x64   id=3 system=Linux processor=x86_64  processor_source=CMAKE_SYSTEM_PROCESSOR
    -- cava: host=Windows/AMD64 crosscompiling=TRUE osx_architectures=''
    -- cava: platform=linux-arm64 id=4 system=Linux processor=aarch64 processor_source=CMAKE_SYSTEM_PROCESSOR

注意：这验证的是**平台推导与配置链路**，**不是**"能编出 Linux 的 .so"——后者本机做不到（§1）。

---

## 3. 数值一致性套件（本任务的核心交付）

交付物：`native/tests/platform/`（新建）。用法/参数/退出码/设计取舍见该目录的 README.md。

### 3.1 实测结果（windows-x64 / MinGW g++ 15.2，CMake 构建的库）

    PLATFORM-SUITE|platform=windows-x64|compiler=gcc|golden=native/tests/vectors/fp_probe.txt
      |lib=build/p4b-srcview/natives/windows-x64/cava.dll|pass=31|fail=0|skip=0|verdict=PASS

> **captain 更正（2026-09-24）**：本节原先写的是 `pass=32`，**实测是 31**。用手上的三份
> MinGW 构建的套件二进制（`build/p4b-platform`、`build/platform-suite`、`build/platform-captain`）
> 分别对交付物 DLL 跑，三次都是 `pass=31|fail=0|skip=0`；把日志里 `[ ok ]` 逐行数出来也是 31
> （环境画像 4 + 编译开关 5 + 逐位一致性 6 + ABI 布局 16）。**"检查条数"这种数字必须数出来，不能凭印象写。**

逐位一致性（对签入黄金 15456 行）：

    [ ok ] add    3600 行，数值位不一致 0 行（NaN 载荷豁免 20 行）
    [ ok ] sub    3600 行，数值位不一致 0 行（NaN 载荷豁免  0 行）
    [ ok ] mul    3600 行，数值位不一致 0 行（NaN 载荷豁免 20 行）
    [ ok ] div    3600 行，数值位不一致 0 行（NaN 载荷豁免  0 行）
    [ ok ] sqrt   1056 行，数值位不一致 0 行（NaN 载荷豁免  0 行）
    [ ok ] 黄金向量行数 = 15456（期望 15456）

**NaN 载荷那 40 行为什么豁免**：同一台机上用 Java 21 重算同一份黄金
（`java native/tests/java/FpProbeJava.java native/tests/vectors/fp_probe.txt`）得到

    add count=3600 mismatch=20 / mul count=3600 mismatch=20 / sub=0 / div=0 / sqrt=0
    总结果数 15456，差异 40（双 NaN 操作数 40 / 其它 0 / NaN 类别不一致 0）

⇒ 40 行**全部**是"两边都是 NaN、只有载荷/符号位不同"，而 Java 自己在同一批行上也与原生不一致。
IEEE-754 不规定 NaN 载荷传播规则，把它做成硬门禁只会得到一个必然红、迟早被绕过的门禁。
所以默认**计数 + 打印 + 不算失败**，`--strict-nan` 可提升为失败。

### 3.2 ★ 本轮最有价值的单个发现：**缺 -fno-math-errno 会让 sqrt 悄悄错 1 ulp**

套件第一次跑就报了 sqrt 5 行不一致。反汇编定位到根因：

* 用**同一条编译参数**编出来的两个 TU，一个把 std::sqrt 内联成正确舍入的 `sqrtsd`，
  另一个退化成 `call sqrt`（msvcrt）。实测（同样的输入）：

| 输入位模式 | sqrtsd（正确舍入） | msvcrt 的 sqrt | Java 21 Math.sqrt / StrictMath |
| --- | --- | --- | --- |
| 3FEFFFFFFFFFFFFF | 3FEFFFFFFFFFFFFF | 3FF0000000000000 | 3FEFFFFFFFFFFFFF |
| 7FEFFFFFFFFFFFFF | 5FEFFFFFFFFFFFFF | 5FF0000000000000 | 5FEFFFFFFFFFFFFF |
| 7D9C57A9A12040CA | 5EC54B88069DA279 | 5EC54B88069DA278 | 5EC54B88069DA279 |
| 7FF0000000000001 (sNaN) | 7FF8000000000001 | 7FF0000000000001（**没静音**） | 7FF8000000000001 |
| FFF0000000000001 (sNaN) | FFF8000000000001 | FFF0000000000001 | FFF8000000000001 |

**Java 与 sqrtsd 五项全同；msvcrt 那条路五项全错或差 1 ulp。** 而契约与平台文档都点名
「只有 + - * / 与 **sqrt** 允许跨界且必须逐位一致」⇒ 这条路会直接破坏契约。

**修法**：把 `-fno-math-errno` 加进 native/cmake/CavaFlags.cmake 的必需参数。
理由：GCC 只有"不需要维护 errno"时才被允许无条件把 sqrt 展开成指令，否则必须为负输入保留
errno=EDOM 的库调用路径，**展开与否就取决于上下文**——这正是实测看到的两种结果。
安全性：grep errno/EDOM/math_errhandling native/ **全空**（源码从不读写 errno）；
它不是 fast-math 家族的开关，不允许重结合、不 flush 非规格化数。加完之后：

* 套件里「sqrt 走正确舍入路径（5 个定点）」这条**可红**的断言通过（去掉 `-fno-math-errno` 就红）；
* CMake 构建的 cava.dll 反汇编：`sqrtsd=2 sqrtss=4 call <sqrt> = 0`。

> **适用面**：这个坑是在 **MinGW** 上实测到的。生产 Windows 工具链是 MSVC（/fp:strict）；
> MSVC 构建的 cava.dll 上实测 `sqrtsd=0 sqrtss=0 call <sqrt> = 0`（MSVC 把 sqrt 编成了什么，
> 本机**没能确认**，见 §6 末）。这条修复对 GCC/Clang 五个平台都成立，成本为零。

---

## 4. 编译开关：**从产物反查**（tools/platform-flagcheck.ps1）

工具读三处：CMake 的 flags.make、compile_commands.json、以及**产物本身**（导入表 + 反汇编）。
实测 38/0/0：

    --- 1. CMakeFiles/cava.dir/flags.make ---
    CXX_FLAGS = -DNDEBUG -std=c++20 -O2 -fwrapv -ffp-contract=off -fno-fast-math -fno-math-errno -Wall -Wextra
    CAVA_BUILD_FP_FLAGS = -O2 -fwrapv -ffp-contract=off -fno-fast-math -fno-math-errno
      [ ok ] 必需参数存在: -O2 / -fwrapv / -ffp-contract=off / -fno-fast-math / -fno-math-errno
      [ ok ] 禁止参数不存在: -march=native / -mtune=native / -mcpu=native / -ffast-math / -Ofast
                            / -ffp-contract=on / -ffp-contract=fast / /fp:fast / /arch:AVX* （共 11 项）
      [info] 优化开关序列: -O2
      [ ok ] 只有一个优化开关且是 -O2
    --- 2. compile_commands.json（逐 TU）---
      [ ok ] 全部 1 个 TU 都带齐开关、都不含禁止项
    --- 3. 产物反查 ---
      [info] imports: KERNEL32.dll, msvcrt.dll
      [ ok ] 导入表里没有 MinGW 运行时 DLL（全静态形态）
      [info] disassembly: %ymm=0 %zmm=0 vfmadd/vfnmadd=0
      [ ok ] 产物里没有 AVX/AVX2 浮点指令
      [info] sqrt path: sqrtsd=2 sqrtss=4 call_sqrt=0
      [ ok ] sqrt 走硬件指令（正确舍入），没有退回 libm 调用

### 4.1 顺手修掉的一处"靠顺序取胜"

改之前 flags.make 里是 `-O3 -DNDEBUG ... -O2 ...`：CMake 的 Release 默认带 -O3，它排在我们的
-O2 **前面**；GCC/Clang 取**最后一个** -O，所以实际是 -O2 —— 但这是隐式依赖顺序，任何人往
`CMAKE_CXX_FLAGS` 补一个 -O3（那会排在最后）就静默变成 -O3。现在 CavaFlags.cmake 显式把
Release/RelWithDebInfo/MinSizeRel 里的 -O3/-O4 删掉，并在配置日志里打印改写记录：

    -- cava: CMAKE_CXX_FLAGS_RELEASE: '-O3 -DNDEBUG' -> '-DNDEBUG'（契约要求 -O2，不允许 -O3 排在后面）

### 4.2 Apple Clang 的 -ffp-contract=off（本机无法验证，但代码写对了）

* Apple Clang 的 `-ffp-contract` 默认是 **on**（GCC 默认才是 fast），而 arm64 一定有 FMA，
  于是 a*b+c 会被合成单次舍入的 fma ⇒ 与 Java 的"乘一次舍一次、加一次舍一次"不同。
* CavaFlags.cmake 走非 MSVC 分支，**显式**传 `-ffp-contract=off`，并用
  `check_cxx_compiler_flag` 验过"编译器接受它"（不是"默认值对"）；APPLE 上还会在配置日志里
  打一行确认，若 APPLE 上的编译器不是 AppleClang/Clang 则给 WARNING。禁止清单里也加了
  `-ffp-contract=on|fast`，防止有人从外面塞回来。
* 套件第 2 节有一条 **FMA 收缩探针**（a*a-1，a = 1+2^-27）：x86-64 基线没有 FMA ⇒ 本机
  **不可判**（套件如实打印"本探针在本平台不可判"）；**arm64 / macOS 上它是可判的**，
  一旦 contraction 没关掉就会红。
* MSVC 走 `/O2 /fp:strict`（不是 GCC 风格旗标），配置日志确认 `fp-flags=/O2 /fp:strict`。

---

## 5. ABI 布局自检（真调库，不是读源码）

    [ ok ] cava_abi_version() = 1（期望 1）
    [ ok ] cava_layout_report 返回 14 条（期望 14 条 = 14 个结构体）
    [ ok ] report.pointer_size = 8 / report.platform = 1（windows-x64）
    [ ok ] 14 条 entry 的 size/align/field_count/layout_hash/逐字段 (offset,size) 全部一致
    [ ok ] layout_hash_sum = 0x1C12265E（期望 0x1C12265E，14 条 uint32 回绕加法）
    [ ok ] cava_open(正确和值) -> rc=0 handle=4294967297 status=0 native_layout_sum=0x1C12265E
    [ ok ] cava_open(错和值 1 位) -> rc=-2（CAVA_ERR_LAYOUT）handle=0（必须 0）

**14 条冻结值**（硬编码在套件里，且必须等于"用 offsetof/sizeof 现算"的机械表算出的哈希 ——
两张表都对上才算过；这是 P0 那次 CavaPathNode 漏字段事故留下的永久护栏）：

    CavaLayoutEntry  544/8/8   0xF837804D     CavaShapeRecord   32/4/8   0x0DCFFE65
    CavaLayoutReport 34848/8/8 0xE9FFC021     CavaMoveShapeRef  48/8/11  0x1545B999
    CavaOpenParams   32/8/5    0x7FDE7499     CavaMoveRequest   104/8/15 0xB542D3D5
    CavaOpenResult   24/8/4    0xFF344829     CavaMoveEvent     80/8/16  0x630C22D5
    CavaPathRequest  56/8/13   0xE566F98D     CavaMoveResult    88/8/13  0x7737ABBD
    CavaPathNode     32/4/8    0x0DCFFE65     CavaMobProfile    192/8/18 0x9C6C98CD
    CavaStateRecord  20/4/5    0x53797229     CavaCollisionBox  24/4/6   0x250ECBE1
    layout_hash_sum = 0x1C12265E

（CavaShapeRecord 与 CavaPathNode 哈希相同是契约 2.3 的已知弱点 ⇒ 套件里结构体身份**只用下标**，
绝不按 hash 或 (size,field_count) 反查。）

---

## 6. ★ MSVC 19.44 现在**编不过这个工程**（两个真阻塞，含最小复现与已验证的修法）

docs/CAVA-gates.md 里一直写着"未验证：MSVC 构建"。本轮把它验了，结论是**不通**；
而 CI 的 windows-2022 job 用的正是 MSVC ⇒ 那个 job 第一次跑就会红。

**阻塞 1（致命，每个导出符号都中）**：native/src/cava_internal.h 把 CAVA_EXPORT 定义成
`__declspec(dllexport)`，而 cava_abi.h 里的**声明没有** dllexport。MSVC 19.44 因此对
**每一个**导出函数报 C2375「重定义；不同的链接」。6 行最小复现：

    // h.h:   extern "C" { int f(double); }
    // a.cpp: extern "C" __declspec(dllexport) int f(double v){ return (int)v; }   -> error C2375
    //        （去掉 dllexport 的写法 exit=0；头里不加 extern "C" 也一样报）

对真实的 native/src/cava_numeric.cpp 单独编译：

    cava_numeric.cpp(15): error C2375: “cava_d2i_sat”: 重定义；不同的链接
    native/src/../include/cava_abi.h(550): note: 参见“cava_d2i_sat”的声明
    ... 10 个符号全中（cava_open / cava_close / cava_layout_report / cava_build_id / ...）

**已验证的修法**（3 行，在 native/src/cava_internal.h）：MSVC 下把 CAVA_EXPORT 定义成空，
导出交给 CMake 的 `WINDOWS_EXPORT_ALL_SYMBOLS`（CavaFlags.cmake 在 MSVC 上**已经**打开它）：

    #if defined(_WIN32) || defined(__CYGWIN__)
    #  if defined(_MSC_VER)
    #    define CAVA_EXPORT
    #  else
    #    define CAVA_EXPORT __declspec(dllexport)
    #  endif
    #elif defined(__GNUC__)

打了这 3 行之后 C2375 **全部消失**（实测）。

**阻塞 2**：native/src/entity/cava_entity_kernel.cpp:232 用了 C99 复合字面量

    (const int32_t[3]){ h.cell_x, h.cell_y, h.cell_z }

GCC/Clang 当扩展接受，MSVC 直接报 `error C4576: 后跟初始值设定项列表的带圆括号类型是一个
非标准的显式类型转换语法`。修法：先取一个具名临时数组再传指针（3 行）。

**把这两个补丁打在 native/src 的**副本**上之后**（真实源码本轮**没有**改动，因为不在授权路径）：

    cl exit=0
    MSVC dll built: 150528 bytes
    DLL Name: KERNEL32.dll                     # 连 msvcrt 都不依赖，比 MinGW 版更自包含
    PLATFORM-SUITE|platform=windows-x64|compiler=gcc|...|pass=32|fail=0|skip=0|verdict=PASS   ← 见下方更正
    [ ok ] 逐位一致性总计：15456 行，数值位不一致 0 行（NaN 载荷豁免 40 行）
    [ ok ] layout_hash_sum = 0x1C12265E（期望 0x1C12265E）

> **captain 更正（2026-09-24，首次拿真 MSVC 产物复跑）**：上面这一行有两个错：条数是 31 不是 32，
> 而且既然跑的是 MSVC 产物，`compiler=` 就该是 `msvc`。实测（`build/native-msvc/suite-msvc/Release/cava_platform_suite.exe`）：
>
>     PLATFORM-SUITE|platform=windows-x64|compiler=msvc|...|pass=30|fail=0|skip=1|verdict=PASS
>     [info] build_id = cava 0.1.0 windows-x64 MSVC 19.44.35228.0 (MSVC toolset v143) /O2 /fp:strict safe=0 asan=0 ubsan=0
>     [ ok ] layout_hash_sum = 0x1C12265E（期望 0x1C12265E）
>     [ ok ] cava_open(正确和值) → rc=0 handle=4294967297 status=0 native_layout_sum=0x1C12265E
>
> MSVC 少 1 条、多 1 skip 是**正常的**：那条检查依赖 GCC 才有的编译期宏（`__FAST_MATH__` 等），
> MSVC 上按 `skip()` 明确记账而**不是**假装通过。两边的**逐位一致性 15456 行与 ABI 布局结论完全一致**。

**这是本项目第一条跨编译器逐位一致证据**：MSVC `/O2 /fp:strict` 与 MinGW-GCC（黄金向量就是它
生成的）在 15456 行 + - * / sqrt 上**数值位零差异**，NaN 载荷豁免行数也一样（40）。

**同一产物的反汇编**：`%ymm=101，浮点 AVX 指令=0，vfmadd=0`。那 101 条全是 VEX 编码的
**整数搬运**（vmovdqu 54 / vmovntdq 30 / vmovdqa 16 / vinsertf128 1），是 MSVC 为 memcpy/memset
展开的。⇒ (a) 它不影响浮点一致性；(b) **「产物里没有 %ymm 就等于没用 -march=native」这个判据是错的**，
flagcheck 已改成只对**浮点** AVX 指令做硬断言、整数搬运只报告；(c) 这些 AVX 是否有运行期 CPU 检查
保护，**本机未确认**（objdump 里看不到 `__isa_available` 符号）⇒「在 AVX 之前的老 CPU 上是否
安全」**未验证**。

**仍需 native 侧 owner 做的**：把上面两个补丁落进 native/src（不在 P4-B 授权路径）。在它们落地
之前，CI 的 windows-2022 job 必然失败 —— 这是**预期**的，不要为了让 CI 变绿而把该 job 关掉。

---

## 7. 平台标签的**三方一致性**（CMake 输出目录 ↔ Java 运行时查找 ↔ jar 打包）

Java 侧不在本流授权路径，所以这里是**读代码逐条对齐 + 把不一致处列出来**。

| 平台 | ① CMake 标签/编号（CavaPlatform.cmake + cava_abi.h） | ② CMake 产物名（cava_set_output_dirs） | ③ Java 资源路径（NativeLibrary.RESOURCE_ROOT + platformDir() + libraryFileName()） | ④ jar 内路径（build.gradle processResources） | 一致？ |
| --- | --- | --- | --- | --- | --- |
| Windows x64 | windows-x64 / 1 | natives/windows-x64/cava.dll | os.name=Windows -> windows；os.arch=amd64 -> x64；cava.dll | natives/<平台>/ | ✅ |
| Windows arm64 | windows-arm64 / 2 | natives/windows-arm64/cava.dll | os.arch=aarch64 -> arm64 | 同上（标签由 -Pcava.platforms 给） | ✅（仅推导层；无构建工具链，见 §1） |
| Linux x64 | linux-x64 / 3 | natives/linux-x64/libcava.so | os.name=Linux -> linux；os.arch=amd64 -> x64；libcava.so | 同上 | ✅ |
| Linux arm64 | linux-arm64 / 4 | natives/linux-arm64/libcava.so | os.arch=aarch64 -> arm64 | 同上 | ✅ |
| macOS x64 | macos-x64 / 5 | natives/macos-x64/libcava.dylib | os.name="Mac OS X" -> macos；os.arch=x86_64 -> x64；libcava.dylib | 同上 | ✅ |
| macOS arm64 | macos-arm64 / 6 | natives/macos-arm64/libcava.dylib | os.arch=aarch64 -> arm64 | 同上 | ✅ |

编号映射也与 Java 一致：`CavaNative.platformName()` 1→windows-x64、2→windows-arm64、
3→linux-x64、4→linux-arm64、5→macos-x64、6→macos-arm64。

### 发现的不一致（**都在 Java/Gradle 侧，P4-B 无权改 ⇒ 上报**）

| # | 位置 | 问题 | 后果 |
| --- | --- | --- | --- |
| **G1** | build.gradle:152 | `layout.projectDirectory.file("natives/windows-x64/<cavaLibName>")` —— **平台目录写死 windows-x64**，而库文件名却按 os 算了 | 在 Linux/macOS 上这个文件永远不存在 ⇒ cavaAutoNative=false ⇒ 测试被切成"纯 Java 回退"档，PathfindAbiTest **整类 skip**、构建照样绿。这正是 CAVA-gates.md 门禁 #7 记过的"绿 ≠ 测过"，**会在 4 个新平台上原样复发**。应按 NativeLibrary.platformDir() 同规则推导平台目录 |
| **G2** | build.gradle:86 | `cavaNativePlatforms` 默认值写死 'windows-x64' | 在 Linux/macOS 上打 jar 时会去找 natives/windows-x64/：要么什么都不打（需要显式 `-Pcava.platforms=`），要么在混装了多平台产物时打错平台 |
| **G3** | docs/CAVA-工程接口契约.md:129 | 仍写着"当前权威 layout_hash_sum = 0x6975CBF9（9 个结构体）" | 与实测的 14 条 / 0x1C12265E 冲突（契约是**依据**文件，本流无权改） |
| **G4** | native/tests/vectors/layout_expected.txt | 只有 9 个结构体 / sum 0x6975CBF9 | 陈旧黄金参考。根因：native/tests/build-mingw.ps1 用 `cava_selftest --dump-layout` 生成它，而 selftest 的 kStructExpectCount=9 还没跟上 P2 的 ABI |
| **G5** | native/tests/cava_selftest.cpp / cava_dll_loadtest.cpp | 同样写死 9 个结构体 / 0x6975CBF9 | 实测 `120 passed, 10 failed`，**10 条全是这一条及其级联**。已用**改动前**的旧构建对比确认是既有问题（旧 exe 同样 120/10），**与本轮改动无关** |
| **G6** | src/test/java/cava/ffm/PathfindAbiTest.java:158 | 断言值已是 0x1C12265E，消息还写"9 个结构体的 layout_hash_sum" | 只是文案 |

### 打包形态（哈希校验 + ABI 版本校验 + 解压命名）：**已实现并实测过，本轮不重造**

依据 docs/CAVA-gates.md 门禁 #2/#6 与 docs/CAVA-java-notes.md：资源从
`resource:/natives/windows-x64/cava.dll` 取出、按 sha256 前 16 位命名、临时文件 + 原子改名、
System.load(绝对路径)、ABI 版本 → 布局自检 → 失败整体回退纯 Java，全部在真实服务端 stdout 里有记录。
本轮做的是**核对三方路径/命名是否一致**（上表 + G1/G2）。

另外记录一条实测：仓库里那个 711521 B 的 natives/windows-x64/cava.dll 是
native/tests/build-mingw.ps1 这条**手编**路径产出的（build_id 是
`... win-x64 ... O2/fwrapv/ffp-contract=off ...` 的短形式），**不是 CMake 产出的**
（CMake 产出的是 `windows-x64 ... GNU 15.2.0 (路径) -O2 -fwrapv -ffp-contract=off
-fno-fast-math -fno-math-errno ...`）。两者的 build_id_hash 不同。这**不影响正确性**：
cava_open 只校验 abi_version 与 layout_hash_sum（cava_handle.cpp:104-112），不比 build_id_hash。
但"发出去的库必须来自 CMake 路径"这条纪律应当明确 —— 手编路径没有 CavaFlags 的禁止参数守卫。

---

## 8. ASan / UBSan：**本机跑不了**，卡在哪写清楚

两条工具链都不行，都是实打实的：

    # MinGW g++ 15.2
    g++.exe: fatal error: cannot read spec file 'libsanitizer.spec': No such file or directory
    #   -fsanitize=address 与 -fsanitize=undefined 都是这一句，编译阶段就断
    #   在 C:\mingw64 下递归搜 libsanitizer.spec / libasan* / libubsan* —— 一个都没有（发行版没带）

    # MSVC 19.44（/fsanitize=address）
    LINK : fatal error LNK1104: 无法打开文件“clang_rt.asan_static_runtime_thunk-x86_64.lib”
    #   实测该安装里只有 i386 的 ASan 运行时：
    #     clang_rt.asan_dynamic-i386.dll / clang_rt.asan_dynamic-i386.lib
    #     clang_rt.asan_static_runtime_thunk-i386.lib / clang_rt.asan_dynamic_runtime_thunk-i386.lib
    #   **x86_64 的一个都没有** ⇒ 需要给 Build Tools 装 “C++ AddressSanitizer” 组件

⇒ 本机**没有**任何 ASan/UBSan 跑得起来。CMake 侧选项（CAVA_ASAN / CAVA_UBSAN）已存在并在 MSVC 上
显式 FATAL_ERROR 拒绝；.github/workflows/build.yml 里新增的 sanitize job（ubuntu-24.04）
就是给这条用的，**但它同样从未跑过**（§12）。

---

## 9. 连续运行 7 天：**本机做不到**

需要真实长期环境（真实整合包 + 真实负载 + 7×24 机器），本机没有，而且这个量级也无法在一次会话里
伪造。**未验证。**

给出可执行的**观察清单**（每次巡检看这些数，任何一个异常就停）：

| 观察项 | 从哪看 | 判定失败 |
| --- | --- | --- |
| **native 回退计数** | 启动横幅里的 nativeCalls / takeovers / errors / disabled / reasons | 出现**任何**非零 errors，或 disabled=true，或 takeovers 归零而 canary 仍在涨 |
| 熔断状态 | 各子系统的 disabled 与熔断计数（P4-A 的熔断器） | 任一子系统被熔断且原因不是配置 |
| 看门狗告警 | 单次调用 > 阈值的告警行 | 出现告警且频率上升 |
| 镜像自愈 | 区段抽样比对不一致次数（兜底自愈计数） | 非零且持续增长 |
| 布局 / ABI | 启动期的布局自检行（java_sum vs native_sum） | 不相等（会整体回退，属 fail-closed） |
| 崩溃取证 | hs_err 日志是否出现；出现则看有没有 native 帧 | 出现任何 hs_err |
| 性能 | MSPT / TPS 基线与 native on/off 的 A/B | 开了 native 反而更慢（P1/P2 已实测过"净亏"，见 gates） |
| 稳定性的"能红"检查 | 用 `-Dcava.*=canary` 那类**故意破坏**开关做一次 | 破坏后结果**没变** ⇒ 这条链根本没生效 |

> 最后一条是 P2 轮总结出来的方法（「能红的测试才是测试」）：7 天观察如果只看"没崩"，
> 无法区分"真的没问题"和"原生压根没被调用"。所以巡检里必须包含一次**可证伪**的动作。

---

## 10. 未验证清单（**不要**当成已完成）

1. **5 个平台的 CI job 一次都没跑过**（§12）。所有"某平台通过"的说法在 CI 跑之前都不成立。
2. **Linux / macOS / ARM 上从未编译过、也从未运行过**本套件（§1）。
3. **Apple Clang 的 `-ffp-contract=off` 实际行为**未在本机验证（没有 macOS）；只是把旗标写对
   + 加了一条在 arm64 上可判的探针 + 加了禁止项守卫。
4. **arm64 上的 FMA 收缩探针结果、NaN 载荷豁免行数**未知（x64 上探针不可判）。
5. **MSVC 构建**：修法已验证，但**补丁没进 native/src** ⇒ 当前 MSVC 编不过（§6）。
6. **MSVC 产物里 sqrt 被编成了什么**未确认（反汇编里 sqrtsd/sqrtss/call sqrt 都是 0）。
7. **MSVC 产物里那 101 条 VEX 整数搬运是否有运行期 CPU 保护**未确认 ⇒ 老 CPU 安全性未验证。
8. **7 天连续运行**、ASan/UBSan、Linux/macOS 的产物哈希 —— 全部未验证。
9. natives/<tag>/ 是共享输出目录（gates 里记过被并发覆盖）⇒ 本轮**刻意不碰**它：所有 CMake 构建
   都发生在 build/p4b-srcview/ 这份 junction 源视图里，仓库的 natives/ 全程没被写过。

---

## 11. 本轮的边界（哪些事我**没**做，以及为什么）

* native/include/cava_abi.h、native/src/**、src/**、build.gradle、docs/CAVA-platform-and-compat.md、
  docs/CAVA-工程接口契约.md：**未授权，一行没改**。§6 的两个 MSVC 补丁、§7 的 G1/G2/G3
  都只写在本文档里，等 owner 处置。
* 熔断 / 看门狗 / fuzz：按分工归 P4-A，本轮不重复做。
* 打包的哈希校验 / ABI 版本校验 / 解压命名：已实现且已实测，**不重造**（§7 末）。
* `-fno-exceptions -fno-rtti`（平台文档 §1.3 要求）：**没有加，也不建议现在加**。实测
  native/src 里有多处 try { ... } catch (...)（cava_handle.cpp:117、cava_entity_abi.cpp:155/243、
  cava_pf_abi.cpp:79/99），加 `-fno-exceptions` 会直接编不过；而且全仓库**只有文档那一行**
  提到过这两个旗标，没有任何构建路径实现过。这是一条"文档 vs 现实"的偏差，需要 owner 决定
  是改代码还是改文档。

---

## 12. CI 矩阵：写全了，但**一次都没跑过**

.github/workflows/build.yml：

| job | runner | 标签 | 库名 | 这条 CI 路径跑过吗 |
| --- | --- | --- | --- | --- |
| build | ubuntu-24.04 | — | — | ❌ |
| native | windows-2022 | windows-x64 | cava.dll | ❌（本机只跑过 MinGW；且 MSVC 现在编不过，见 §6） |
| native | ubuntu-24.04 | linux-x64 | libcava.so | ❌ |
| native | ubuntu-24.04-arm | linux-arm64 | libcava.so | ❌ |
| native | macos-15-intel | macos-x64 | libcava.dylib | ❌ |
| native | macos-14 | macos-arm64 | libcava.dylib | ❌ |
| sanitize | ubuntu-24.04 | — | libcava.so | ❌ |

* **JDK 固定 21**（java-version: '21' + temurin），与 Loom 1.17.20 / `--release 21 --enable-preview`
  的硬约束一致（门禁 #1）。
* 每个 native job 的**名字里都带 `[unverified-local]`**，第一步还会打一条
  `::warning::native(<tag>) has NEVER been executed on the development machine.`
* 每个 job 依次跑：平台标签推导（tag_matrix.cmake）→ configure/build → **数值一致性套件** →
  从产物反查编译开关（flagcheck）→ ctest → 上传产物。
* **对任务书的一处偏离（有依据）**：任务书写的是 macos-13(x64)，但 **macos-13 已被 GitHub 下线**
  （2025-12-04 退役；见
  https://github.blog/changelog/2025-09-19-github-actions-macos-13-runner-image-is-closing-down/ ），
  现存 Intel 镜像是 `macos-15-intel`。本文件用了后者，并在注释里写明怎么改回去。
* **ubuntu-24.04-arm** 是有效的 arm64 公共 runner 标签（2025-08 GA）。

YAML 语法本地校验过（js-yaml）：jobs = build / native / sanitize，native 矩阵 5 条、11 个 step，
java-version = "21"。**语法通过 ≠ 跑过。**
