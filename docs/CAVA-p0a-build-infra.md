# Cava 构建基建（P0-A）—— 实测事实与用法

> 文件所有权：P0-A 构建基建流。本文件只写**本机实测**结论，未验证的一律标注「未验证」。
> 关联：`docs/CAVA-launch-notes.md`（环境侦察）、`prompts/01-P0-环境与骨架.md`（P0 任务书）、
> `docs/CAVA-工程接口契约.md`（§2 ABI 纪律、§4.3 parityDiff）。
> 最近一次实测时间：2026-09-22。

---

## 0. 一句话复用命令（本机）

```powershell
# Java 侧（推荐用包装脚本，它会把 GRADLE_USER_HOME 指到工作区内）
.\scripts\gradlew-cava.ps1 build --console=plain

# 没脚本时等价于：
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'
.\gradlew.bat build --console=plain

# 原生侧（CMake 不在 PATH，必须全路径；mingw32-make 也必须显式给；build 目录带流标识）
$cmake = 'J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe'
& $cmake -S J:\mc\Cava -B J:\mc\Cava\build\native-mingw-<流名> -G "MinGW Makefiles" `
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=C:/mingw64/bin/gcc.exe `
    -DCMAKE_CXX_COMPILER=C:/mingw64/bin/g++.exe `
    -DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe
& $cmake --build J:\mc\Cava\build\native-mingw-<流名> --parallel
& 'C:\mingw64\bin\ctest.exe' --test-dir J:\mc\Cava\build\native-mingw-<流名> --output-on-failure
```

**GRADLE_USER_HOME 必须在工作区内**（`J:\mc\mods\.gradle-home` 可读不可写，wrapper 连 `.lck` 都建不了）。
**绝对路径没有写进 `build.gradle` / `gradle.properties` / `settings.gradle`**（那样别的机器与 CI 会挂），
只写在包装脚本 `scripts/gradlew-cava.ps1` 和本文档里。

---

## 1. Gradle / Loom（实测通过）

### 1.1 版本钉死（**最重要的一条**）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Gradle | 9.7.1（wrapper 自带） | 未改 |
| Loom | **1.17.20**（插件 id `net.fabricmc.fabric-loom-remap`） | **不能升到 1.18.x** |
| JDK（跑 Gradle + 工具链） | 21.0.10 | `C:\Program Files\Java\jdk-21` |
| MC / Yarn / Loader | 1.20.4 / 1.20.4+build.3 / 0.19.5 | 未改 |
| fabric-api | **0.96.11+1.20.4** | captain 广播 #3 决定 2：构建期必须与服务器实际 jar 一致（模板的 0.97.3 已弃用）。实测可解析、构建绿 |

**为什么不能升 Loom**：Loom 1.18.x 的 Gradle module metadata 里 `org.gradle.jvm.version=25`，
在 JDK 21 上直接失败（实测原文）：

    Could not resolve net.fabricmc:fabric-loom:1.18.2.
      > Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.

这也解释了模板自带 `.github/workflows/build.yml` 为什么写 JDK 25 —— 它是迁就 Loom 1.18 的，
与本项目「JDK 21 + --enable-preview」硬约束冲突，**已改回 JDK 21**。
换 Loom 版本前先查这一条：

    https://maven.fabricmc.net/net/fabricmc/fabric-loom/<版本>/fabric-loom-<版本>.module
      -> "org.gradle.jvm.version"（21 才可用）与 "org.gradle.plugin.api-version"（要 ≤ 本机 Gradle）

已实测的取值：1.17.20 / 1.17.21 → jvm 21 + plugin-api 9.5.0 ✅；1.18.2 → jvm 25 + plugin-api 9.7.0 ❌；
1.10.5 → jvm 17 + plugin-api 8.12（配 Gradle 9.7.1 未验证）。

### 1.2 编译与运行参数

- 全部 `JavaCompile`：`options.encoding='UTF-8'`（源码有中文）、`options.release=21`、`--enable-preview`。
  二者必须同时给，只给一个 javac 直接报错。
- 全部 Loom run（`client`/`server`/以后新增的）：`vmArgs '--enable-preview', '--enable-native-access=ALL-UNNAMED'`
  （用 `runs { configureEach { ... } }`，不点名，避免 Loom 改默认 run 名字后静默失效）。
- 全部 `Test`：`useJUnitPlatform()` + 同样的 jvmArgs + `maxParallelForks=1`（差分/确定性考虑）；
  单测默认注入 `-Dcava.native.enabled=false`（纯 Java 路径），要测原生用 `-Pcava.native.enabled=true`。
- 工具链：`java { toolchain { languageVersion = 21 } }`；`gradle.properties` 里
  `org.gradle.java.installations.paths` 列了本机常见 JDK 21 位置做兜底。
  **实测：列表里不存在的目录只会打印一行提示，不影响构建**（这是有意留下的容错，CI 上也安全）。

### 1.3 版本号 / group 的改动（**请 captain 确认**）

- `version` 从模板的 `1.0.0` 改成 **`0.1.0`**，`group` 从 `cava.modid` 改成 **`cava`**。
  理由：契约 §4.2 的黄金轨迹头写的是 `"cava":"0.1.0"`，`cava_abi.h` 里 `cava_build_id` 的示例也是 `cava 0.1.0 ...`；
  包结构契约也写明模组根包是 `cava`（不是 `cava.modid`）。要改回去只需动 `gradle.properties`。

### 1.4 原生库打包进 jar（实测通过）

`processResources` 会把 `natives/<platform-tag>/` 下的动态库打进 jar 的 **`natives/<platform-tag>/`**，
只收 `.dll/.so/.dylib`（import lib / pdb / obj 不进 jar）。默认平台 `windows-x64`，
多平台用 `-Pcava.platforms=windows-x64,linux-x64`。
jar 内路径 = `natives/windows-x64/cava.dll`（**Java 侧 `NativeLibrary.java` 请按这个路径取资源；P0-A 提出的约定，待 Java 流确认**）。

### 1.5 可选的原生构建任务（默认不挂到 `build` 上）

    .\gradlew.bat buildNative            # = cmakeConfigure + cmake --build，15s（本机实测）
    .\gradlew.bat cmakeConfigure
    可用 -Pcava.cmake= / -Pcava.native.generator= / -Pcava.native.buildType= / -Pcava.native.cc|cxx= / -Pcava.native.bin= 覆盖

`buildNative` **故意不参与 `build`**：没有 CMake/MinGW 的机器不该因此失败。
本机默认工具链 = `C:/mingw64/bin`（存在才加进子进程 PATH），默认 CMake = 工作区里的便携版。

---

## 2. 原生构建（CMake）

### 2.1 文件与职责

| 文件 | 职责 |
| --- | --- |
| `CMakeLists.txt`（根） | 选项、源文件 GLOB、`cava` 库 target、测试子目录、配置摘要打印 |
| `native/cmake/CavaPlatform.cmake` | 平台标签/编号推导、输出目录、平台白名单校验 |
| `native/cmake/CavaFlags.cmake` | FP 参数校验与施加、CAVA_SAFE/ASAN/UBSAN、构建宏、输出目录属性 |
| `native/tests/CMakeLists.txt` | 每个测试源文件 → 一个 target + 一个 CTest 用例 |

**源文件收集是 GLOB**（`file(GLOB_RECURSE ... CONFIGURE_DEPENDS native/src/*.c|cpp|...)`）：
native 侧在 `native/src` 下**任何目录**新增 `.c/.cc/.cpp/.cxx` 都会被自动收录，**不用改 CMakeLists**。
排除 `native/src/{tests,scratch,_scratch,drafts}/`。`native/src` 下一个源文件都没有时：
**配置成功但跳过 `cava` target 并打 WARNING**（绝不产出假库）。

### 2.2 编译参数（硬约束，带守卫）

- GNU/Clang：`-O2 -fwrapv -ffp-contract=off -fno-fast-math`；MSVC：`/O2 /fp:strict`。
- 每个参数都过 `check_cxx_compiler_flag`；**缺任何一个直接 FATAL_ERROR**，绝不静默降级。
- 另外会检查 `CMAKE_CXX_FLAGS/CMAKE_CXX_FLAGS_RELEASE` 里有没有混进
  `-march=native / -mtune=native / -ffast-math / -Ofast / /fp:fast`，有就 FATAL_ERROR。
- C++20（`CXX_STANDARD 20`，`CXX_EXTENSIONS OFF`），C 侧用 C11。
- **MSVC 专用修正**：`check_cxx_compiler_flag` 默认用 **Debug** 配置做 try_compile，Debug 带 `/RTC1`，
  与 `/O2` 冲突（cl 报 D8016），会误判「不支持 /O2」并 FATAL_ERROR。
  已设 `set(CMAKE_TRY_COMPILE_CONFIGURATION Release)` —— 实测 MSVC 19.44 下 `/O2` 与 `/fp:strict` 都是 Success。
- **Windows + GNU 的运行时**：库 target 用 `-static-libgcc -static-libstdc++ -static`
  + `-Wl,--exclude-libs,libgcc.a:libgcc_eh.a:libstdc++.a:libsupc++.a:libwinpthread.a`。
  只写 `-static-libgcc -static-libstdc++` 实测**仍留 libwinpthread-1.dll 依赖**（等于没解决）；
  加 `-static` 后如果不排除符号，DLL 会把 `_Unwind_Resume` 等 libgcc 符号一起导出
  （因为 `--export-all-symbols`），任何链我们 import lib 的 C++ 程序都会 multiple definition（实测 cava_fp_probe 挂在这）。
  实测结果：DLL 依赖只剩 `KERNEL32.dll + msvcrt.dll`，10 个 ABI 符号仍全部导出，ctest 3/3 通过。
  代价：`cava.dll` 从约 0.11 MB 涨到约 2.78 MB（静态 libstdc++ 被拉进来）。

### 2.3 选项

| 选项 | 默认 | 作用 |
| --- | --- | --- |
| `CAVA_SAFE` | OFF | 全部断言与边界检查（`CAVA_SAFE_BUILD=1` + `-UNDEBUG`） |
| `CAVA_ASAN` | OFF | AddressSanitizer（MSVC 上直接报错，用 MinGW/Clang） |
| `CAVA_UBSAN` | OFF | UBSan（同上） |
| `CAVA_BUILD_TESTS` | ON（仅顶层） | 构建 `native/tests` |
| `CAVA_EXPORT_ALL_SYMBOLS` | ON | Windows 下导出全部符号（`cava_abi.h` 冻结、没有 dllexport 宏） |

### 2.4 产物

- 库：`<repo>/natives/<CAVA_PLATFORM_TAG>/`。**Windows 上只有 `cava.dll`**
  （MinGW 默认会叫 `libcava.dll`，已在 CMake 里 `PREFIX ""` 强制对齐 MSVC）。
  非 Windows 保持平台惯例：`libcava.so` / `libcava.dylib`。
- import lib / pdb：`<build>/import-lib/`（实测：`build/native-mingw/import-lib/libcava.dll.a`），不污染 `natives/`。
- 平台标签：`windows-x64` / `windows-arm64` / `linux-x64` / `linux-arm64` / `macos-x64` / `macos-arm64`，
  由 **`CMAKE_SYSTEM_PROCESSOR`**（不是 HOST）推导；不认识的组合直接 FATAL_ERROR 并打印实际值。
  实测（MinGW Makefiles）：`CMAKE_SYSTEM_NAME=Windows`、`CMAKE_SYSTEM_PROCESSOR=AMD64` → `windows-x64`（id=1）。

### 2.5 注入给 native 侧的编译期宏

`target_compile_definitions`（字符串宏都带引号，实测 MinGW 下转义正常）：

    CAVA_BUILD_PLATFORM_TAG   "windows-x64"                          （字符串）
    CAVA_BUILD_PLATFORM_ID    1                                      （与 cava_abi.h 的 CAVA_PLATFORM_* 一致）
    CAVA_BUILD_COMPILER       "GNU 15.2.0 (C:/mingw64/bin/g++.exe)"  （字符串）
    CAVA_BUILD_FP_FLAGS       "-O2 -fwrapv -ffp-contract=off -fno-fast-math"
    CAVA_BUILD_TYPE           "Release"
    CAVA_BUILD_SAFE / _ASAN / _UBSAN / _DEBUG   0 或 1

（任务书要求的 6 个都在；`PLATFORM_ID`/`TYPE`/`DEBUG` 是方便 native 侧填 `CavaLayoutReport` 补的。）

### 2.6 测试接线（实测 3/3 通过）

- 约定：`native/tests/<名>.cpp` → target `cava_test_<名>` + CTest 用例 `<名>`。
- **默认不传 argv**（`cava_fp_probe` 用 argv[1] 当输出文件路径）。确实要 argv 的在
  `native/tests/CMakeLists.txt` 的 `CAVA_TEST_ARGS_<名字>` 里登记（现在只有 `cava_dll_loadtest` 需要库路径）。
- 注入环境变量：`CAVA_NATIVE_LIB`=原生库全路径、`PATH`=natives 目录 + 编译器 bin + 原 PATH；
  工作目录 = 仓库根（与 `cava_fp_probe` 注释里 `native/tests/vectors/fp_probe.txt` 的默认路径一致）。

### 2.7 踩过的坑（写下来免得重踩）

1. `-G "MinGW Makefiles"` 时 **必须显式 `-DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe`**
   （或者把 `C:\mingw64\bin` 加进 PATH），否则 CMake 报
   `unable to find a build program corresponding to "MinGW Makefiles"`。
   实测：显式给 MAKE_PROGRAM 后，即使 PATH 里没有 mingw64，配置与构建都成功（exit 0）。
2. `from(dir) { include ... }` 是拷贝**目录内容**，想让 jar 里出现 `natives/windows-x64/cava.dll`
   必须写 `into 'natives'`，否则会变成根目录下的 `windows-x64/cava.dll`（本机踩过）。
3. CTest 的 `ENVIRONMENT` 是 **CMake 列表**：`"PATH=a;b;c"` 里的 `;` 必须转义成 `\;`，
   否则后半截 PATH 被当成另一个列表项丢掉，测试进程直接 `0xc0000135`（DLL not found）。
4. **不要用 `file(STRINGS)` 解析带中文注释的文件**：它按「ASCII 串」解析，
   非 ASCII 字节会被当成行分隔符，一行中文注释会被切成好几个参数（本机实测把一个 args 文件切成了 4 个 argv）。
5. Gradle 的 `problems-report.html` 一旦被**别的沙箱工具**创建过，DSH 沙箱会拒绝覆盖/删除它
   （同目录新建文件正常，只有那一个文件被拒），构建最后会以 `AccessDeniedException` 收尾。
   本机处置：把目录改名绕开（见 §4 待办）。

---

## 3. 实测验收记录（2026-09-22）

### 3.1 Java 侧

    $env:GRADLE_USER_HOME='J:mcCava.gradle-home'
    $env:GRADLE_RO_DEP_CACHE='J:mcmods.gradle-homecaches'   # 可选：只读复用已有 modules-2
    .gradlew.bat build --console=plain

    > Configure project :
    Fabric Loom: 1.17.20
    > Task :compileJava
    注: 某些输入文件使用 Java SE 21 的预览功能。
    > Task :jar / :remapJar / :assemble / :build
    [Incubating] Problems report is available at: .../build/reports/problems/problems-report.html
    BUILD SUCCESSFUL in 10s
    9 actionable tasks: 6 executed, 3 up-to-date
    === gradlew build exit=0 ===

- 冷启动（首次）耗时：**1 分 50 秒**，其中发行包与依赖大多来自只读复用缓存。
  换 `fabric-api` 到 0.96.11+1.20.4 后的一次构建 = **2 分 24 秒**（要重下旧版 fabric-api 并重新 remap）。
- 单元测试：`:test` 实测 6 个测试类 **28 个用例全绿**
  （CavaConfigTest/LayoutHashTest/Fnv1aTest/GoldenTraceTest/TraceDiffTest/CanaryTest）。
- **`--enable-preview` 真的进了 javac 命令行**（`--debug` 抓到的原文，截取尾部）：
  `... --release 21 -d ... -encoding UTF-8 ... <classpath> --enable-preview J:\mc\Cava\src\main\java\cava\Cava.java ...`
- 预览 class 实证：`build/classes` 下 `cava/ffm/*` 共 **12 个 class 的 minor=65535**（major=65），
  其余 21 个（模板类/未用预览 API 的类）是 65.0 —— 与契约 §0 决策 4 的描述一致。
  完全冷启动要下的东西（**未验证**）：Gradle 发行包约 130 MB + Loom/MC 依赖数百 MB。
- 产物：`build/libs/cava-0.1.0.jar`（230 KB）、`cava-0.1.0-sources.jar`。
- 编译结果里的 class 版本实测 `major=65`；`minor=0` 是因为当前源码**还没用到**预览 API
  （实测：`javac --release 21 --enable-preview` 编译一个不用预览特性的类，minor 也是 0；
  用到 `java.lang.foreign` 之类预览 API 时才会是 65535）。
- 已知会被 `--enable-preview` 影响的：JDK 22+ 加载这些 class 会直接拒绝（契约 §0 决策 4）。

### 3.2 原生侧

    cava: 源文件 5 个 -> J:/mc/Cava/natives/windows-x64
    cava: platform=windows-x64 id=1 system=Windows processor=AMD64
    cava: compiler=GNU 15.2.0 (C:/mingw64/bin/g++.exe)
    cava: fp-flags=-O2 -fwrapv -ffp-contract=off -fno-fast-math
    Linking CXX shared library J:mcCava
ativeswindows-x64cava.dll
    === build exit=0 ===

    ctest --test-dir build/native-mingw --output-on-failure
    1/3 cava_dll_loadtest  Passed
    2/3 cava_fp_probe      Passed
    3/3 cava_selftest      Passed
    100% tests passed, 0 tests failed out of 3

- 导出符号实测（`objdump -p natives/windows-x64/cava.dll`）：`cava_open/cava_close/cava_build_id/
  cava_abi_version/cava_abi_touch/cava_layout_report/cava_d2i_sat/cava_d2l_sat/cava_bits_of_double/
  cava_double_of_bits` 共 10 个全部在导出表里（`--export-all-symbols` 生效）。
- `natives/windows-x64/` 实测只有 `cava.dll` 一个文件；import lib 在 `build/native-mingw/import-lib/`。
- `.gradlew.bat buildNative` 实测 BUILD SUCCESSFUL in 15s（Gradle→CMake 通路可用）。
- 修完静态运行时后（`build/native-mingw-p0a`，**PATH 里没有 mingw64**，靠显式 MAKE_PROGRAM）：
  configure exit=0、build exit=0、ctest **3/3 通过**，
  `natives/windows-x64/` 里只有 `cava.dll`（约 2.78 MB），
  `objdump -p` 依赖只有 `KERNEL32.dll` + `msvcrt.dll`，ABI 符号 10 个全在导出表。
- MSVC：`-G "Visual Studio 17 2022" -A x64` **配置成功**（`compiler=MSVC 19.44.35228.0 (toolset v143)`、
  `fp-flags=/O2 /fp:strict`、`/O2` 与 `/fp:strict` 的 check 都是 Success）。**只验证了配置，没跑完整构建**。

---

## 4. 未验证 / 待办 / 请求 captain

**请求 captain（不在 P0-A 文件所有权内）**

1. **`.gitignore` 加 `natives/`**（原生产物目录，绝不能入库）。顺带确认 `build/`、`.gradle-home/` 已在里面（已确认在）。
2. **删掉 `build/reports/problems-locked-2026-09-22/`**（我为了绕开沙箱拒写把原目录改名了；
   里面那个 `problems-report.html` 是别的沙箱工具创建的，DSH 沙箱覆盖/删除/改名单个文件都被拒）。
   沙箱外一条命令：`Remove-Item -Recurse -Force J:\mc\Cava\build\reports\problems-locked-2026-09-22`。
3. **`native/tests/vectors/fp_probe.txt`**（`cava_fp_probe` 生成的向量，ctest 会写）要不要入库当黄金数据？P0-A 不定。
4. **`docs/CAVA-build.md`** 由别的流负责，本文档不碰它；如果两边内容重叠，请以本文档的「实测」部分为准。

**未验证（不要当结论用）**

- MSVC **完整构建**（`-G "Visual Studio 17 2022"` 编译 + 链接 + `WINDOWS_EXPORT_ALL_SYMBOLS` 是否真导出）：
  **未验证**（配置阶段已验证通过，见 §3.2）。
- linux-x64 / linux-arm64 / macos-x64 / macos-arm64：**未验证**（P4；平台推导与校验已写好）。
- GitHub Actions 上的 `build` / `native` 两个 job：**未验证**（本地无法跑 Actions；
  `native` job 只有 windows-x64 是 `enabled: true`，其余 4 个是占位 `false`）。
- `sourcesJar`/`remapSourcesJar` 内容正确性、`publishing` 块：**未验证**（不影响 `build`）。
- 完全冷缓存（无只读复用）的构建时长与网络依赖：**未验证**。
- Loom 1.17.21（比 pin 的 1.17.20 新一个 patch）：metadata 已查（jvm 21 / plugin-api 9.5.0），**未实测**。

**并行纪律提醒**

- `natives/<platform-tag>/cava.dll` 是**共享输出**：两个流同时编原生库会互相覆盖。
  整合验收时请串行跑，跑完以最后一次构建为准。
- `build/` 下的目录按流派分（P0-A 用 `build/native-mingw-p0a`、`build/native-msvc-p0a`），不要跨流复用。

**P0-A 挂起项（等别人给接口）**

- 契约 §4.3 的 `parityDiff` Gradle 任务：等 `p0-java` 给出运行器类名/入口后由 P0-A 在 `build.gradle` 注册。
- 若 native 侧要新增 target/第三方源码树，或某测试需要 argv，直接找 P0-A 改 `CMakeLists.txt` /
  `native/tests/CMakeLists.txt`（这两个文件归 P0-A）。
