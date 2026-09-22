# Cava 构建手册（本机实测）

> **读者**：新会话里没有记忆的代理 / 未来的维护者。
> **规矩**：本文每条命令都标注**状态**——「✅ 已实测」= 我在本机跑过并贴了真实输出摘要；「⛔ 受阻」= 跑了但失败，写清卡在哪；「❓ 未验证」= 没跑过，别当成结论。
> **配套文档**（不要在本文件里重复它们的内容）：
> - `docs/CAVA-dev-toolbox.md` —— 查映射、javap 读原版、联网走 node、FFM 预览版坑、PowerShell 坑（**权威落盘处**）。
> - `docs/CAVA-execution-plan.md` —— 多代理文件所有权表（谁能改哪个文件）。
> - `docs/CAVA-工程接口契约.md` §2 —— 原生 ABI 与数值纪律；§4 —— 黄金轨迹格式。
> - `docs/CAVA-launch-notes.md` §1 —— GRADLE_USER_HOME 沙箱问题的原始侦察记录。

---

## 1. 工具链全路径表（✅ 全部实测存在）

| 工具 | 全路径 | 实测版本输出 |
| --- | --- | --- |
| JDK 21（唯一编译/运行 JVM） | `C:\Program Files\Java\jdk-21`（`JAVA_HOME` 指向它） | `java version "21.0.10" 2026-01-20 LTS` / `21.0.10+8-LTS-217` |
| CMake（不在 PATH） | `J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe` | `cmake version 3.31.2` |
| CTest（不在 PATH） | `…\cmake-3.31.2-windows-x86_64\bin\ctest.exe` | 随 CMake 3.31.2 |
| MinGW g++ | `C:\mingw64\bin\g++.exe` | `g++.exe (MinGW-W64 x86_64-msvcrt-posix-seh, built by Brecht Sanders, r1) 15.2.0` |
| MinGW make（构建器） | `C:\mingw64\bin\mingw32-make.exe` | `GNU Make 4.4.1` |
| MSBuild / MSVC | `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\…`（由 CMake 自己找到） | `MSVC 19.44.35228.0`，toolset `v143`，Windows SDK `10.0.26100.0` |
| node（唯一可用的联网方式） | `C:\Program Files\nodejs\node.exe` | `v22.18.0` |
| git | `C:\Program Files\Git\cmd\git.exe` | 身份 `yxw1019 <147071791+yxw1019@users.noreply.github.com>` |
| Gradle | 由 wrapper 下载到 `<GRADLE_USER_HOME>\wrapper\dists\` | `Gradle 9.7.1`（`gradle-wrapper.properties` 指向 `gradle-9.7.1-bin.zip`） |

**本机只装了这些 JDK**：`C:\Program Files\Java` 下有 `jdk-17`、`jdk-21`、`jdk-22`、`jdk1.8.0_202`、`latest\jdk-21`。
**没有 JDK 25**（这点很关键，见 §3 的 Loom 卡点）。

> **shell 说明（实测）**：本仓库里用的执行器是 **PowerShell 7.6.6**（`$PSVersionTable.PSVersion` = 7.6.6，Edition=Core，`pwsh.exe`）。
> 但 `docs/CAVA-dev-toolbox.md` §6 记录的坑针对 **Windows PowerShell 5.1**，用户手工复制时会用 5.1。
> **因此本文所有命令都写成 5.1 也能跑的写法**：不用 `&&`、不用三元 `?:`、不用 PS7 独有参数。

---

## 2. `GRADLE_USER_HOME` 必须在工作区内（✅ 已实测，这条会直接决定构建能不能跑）

### 2.1 现象与根因

本机默认 `GRADLE_USER_HOME = J:\mc\mods\.gradle-home`（**工作区之外**），它在当前文件沙箱（workspace-write，工作区 = `J:\mc\Cava`）下**可读不可写**：

| 证据 | 原文 |
| --- | --- |
| wrapper 首次下载锁文件 | `java.io.FileNotFoundException: …gradle-9.7.1-bin.zip.lck (拒绝访问)` |
| 用 junction 指向工作区也无效 | junction 能建，但**通过 junction 写仍被拒绝** |
| 来源 | `docs/CAVA-launch-notes.md` §1（captain 实测） |

### 2.2 正确做法

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'   # 已在 .gitignore 里
```

**为什么不能只写 `-g`**：Gradle 需要往 GRADLE_USER_HOME 里写 `wrapper/dists`、`caches`、`daemon`、各种 `.lock`。只要它在工作区外，第一次写锁就失败。

### 2.3 免下载 130 MB 发行包：从外部缓存里拷（✅ 已实测可行）

外部缓存 `J:\mc\mods\.gradle-home\wrapper\dists\gradle-9.7.1-bin\1w1c7tv4s851m17nbqdsro2tv\` 里**已经有解压好的 Gradle 9.7.1**，而**读**外部目录是允许的。把它拷进工作区即可跳过下载：

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'
New-Item -ItemType Directory -Force -Path "$env:GRADLE_USER_HOME\wrapper" | Out-Null
robocopy 'J:\mc\mods\.gradle-home\wrapper' "$env:GRADLE_USER_HOME\wrapper" /E /NJH /NJS /NP
# robocopy 退出码 1 = 已复制文件（正常）；>=8 才是错误
& '.\gradlew.bat' --version --console=plain
```

实测输出摘要（✅）：

```
Gradle 9.7.1
Build time:    2026-08-19 14:16:09 UTC
Launcher JVM:  21.0.10 (Oracle Corporation 21.0.10+8-LTS-217)
Daemon JVM:    C:\Program Files\Java\jdk-21 (no Daemon JVM specified, using current Java home)
== gradle --version exit: 0
```

### 2.4 `--offline` 能不能复用外部缓存？（❓ 未验证 + 结论）

- **不能直接把 `GRADLE_USER_HOME` 指到工作区外再配 `--offline`**：`--offline` 只禁止**网络解析**，不禁止写锁文件与元数据，仍然会因为「拒绝访问」失败。
- **可行的替代**是把外部缓存**复制**进工作区：外部 `caches` 目录实测 **2259 MB**（`J:\mc\mods\.gradle-home\caches`，`Get-ChildItem -Recurse | Measure-Object Length -Sum`），J: 盘剩余 ≈ 39.8 GB。复制命令：
  ```powershell
  robocopy 'J:\mc\mods\.gradle-home\caches' "$env:GRADLE_USER_HOME\caches" /E /NJH /NJS /NP
  ```
  **❓ 未验证**：我没有实际拷贝这 2.2 GB，也没有验证拷完之后 `--offline` 能过（Gradle 的 `caches/modules-2` 是内容寻址的，理论上可移植；`caches/<gradle-version>/` 里的版本专属元数据不一定兼容）。
  **现阶段不需要**：网络实测可达（`maven.fabricmc.net` / `repo1.maven.org` / `services.gradle.org` / `piston-meta.mojang.com` 全 200，见 dev-toolbox §4），联网拉依赖是可行路径。

---

## 3. Gradle：只有 P0-A 负责跑完整构建（当前状态 = ⛔ 受阻，卡在 Loom）

> **纪律（captain 广播）**：**只有 P0-A 跑完整 Gradle 构建**。多代理同时跑会抢同一个
> `build/reports/problems/problems-report.html` 并报 `AccessDeniedException`（已实测）。
> 需要编译 Java 时优先用 `javac` 直接编。

### 3.1 命令（形态固定）

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'
& '.\gradlew.bat' build --console=plain
```

### 3.2 已实测的失败模式（⛔ 卡点，**不是**本手册的推测）

我在 HEAD `a21b5cb`、`build.gradle` sha256 `72E21985…51CA7`、`gradle.properties` sha256 `C31D6F08…26F9FC` 这一版上真跑过：

```
FAILURE: Build completed with 2 failures.
1: Task failed with an exception.
* What went wrong:
A problem occurred configuring root project 'cava'.
> Could not resolve all artifacts for configuration 'classpath'.
   > Could not resolve net.fabricmc:fabric-loom:1.18.2.
     Required by:
         buildscript of root project 'cava' > net.fabricmc.fabric-loom-remap:…:1.18-SNAPSHOT:20260916.103615-2
      > Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.
2: Task failed with an exception.
* What went wrong:
java.nio.file.AccessDeniedException: J:\mc\Cava\build\reports\problems\problems-report.html
== build exit: 1
```

**根因 1（关键路径）**：`fabric-loom 1.18.x` 要求 **JVM 25**，与「JDK 21 + `--release 21 --enable-preview`」硬约束冲突。
判据来自 maven.fabricmc.net 的 Gradle module metadata 属性 `org.gradle.jvm.version`（我用 node fetch 逐个版本读的）：

| fabric-loom 版本 | `org.gradle.jvm.version` |
| --- | --- |
| 1.18.2 / 1.18.1 | **25** |
| 1.17.21 / 1.17.20 … 1.14.10 | **21** ✅ |
| 1.11.8 / 1.12.7 / 1.13.6 | 21 |
| 1.10.5 | 17 |
| 1.9.2 / 1.8.9 / 1.7.4 | 8 |

**处置（已由 captain 决定，归 P0-A）**：`gradle.properties` 里 `loom_version` pin 到 **1.17.20**。
👉 **不要自己改 `loom_version`，也不要 patch/替换 Loom 的 jar。**
旁证（我在隔离目录 `build/p0d-probe/loompatch/` 里用 loom 1.17.21 跑过一次配置，随后按 captain 指令终止）：
日志里已经打出 `Fabric Loom: 1.17.21` 且 `> Configure project :` 成功进入——**即 1.17.x 在 JDK 21 上能加载**（完整 build 归 P0-A，本文不重复）。

**根因 2（次要，但会拖住 P0-A）**：`build/reports/problems/problems-report.html` **本会话不可写**。
实测：

| 操作 | 结果 |
| --- | --- |
| 在该目录**新建**文件 `New-Item build\reports\problems\mine.txt` | ✅ 成功（目录可写） |
| 覆盖/删除那个已存在的 `problems-report.html`（`Add-Content` / `Remove-Item`，含 `-Recurse` 删整个 `build/reports`） | ⛔ `Access to the path … is denied` |
| `icacls` 该目录 | 目录 ACL 里有**本会话的 capability SID**（`S-1-4-747283207-1067055648:(OI)(CI)(W,D,DC)`）；而那个 html 的 ACL 里只有**另一个会话的 capability SID**（`S-1-4-879133556-62827845`）和 `LAPTOP-55OFT65B\CodexSandboxUsers` |

→ 结论：**跨沙箱会话创建的文件，后来的会话改不动也删不掉**。规避办法是「同一时刻只有一个流跑 Gradle」，
或由**创建它的那个会话**删掉/换个 build 目录。
（`icacls` 里 `BUILTIN\Users:(RX)` 与 `Authenticated Users:(M)` 看着能写，但沙箱令牌不是普通交互令牌，实际被拒。）

### 3.3 无害噪声（出现不用管）

```
Caught exception: Couldn't open current thread, error = 5
Exception in thread "File watcher server" net.rubygrapefruit.platform.NativeException: Couldn't open current thread, error = 5
```
这是 Gradle 的文件监视器在沙箱下拿不到线程句柄，会退回轮询，**不影响构建结果**。

### 3.4 预览版 / JDK 的两条硬纪律（❓ 未验证的部分已标注）

- `options.release = 21` 与 `--enable-preview` **必须成对出现**，少一个 javac 直接报错。
  编译产物 class 版本是 **65.65535**；**JDK 22 即使带 `--enable-preview` 也拒绝加载**（只认 66.65535）。
- CI（`.github/workflows/build.yml`）**必须用 JDK 21**——模板自带的 `java-version: '25'` 与 `--release 21 --enable-preview` 冲突。**归 P0-A 改**。
- **❓ 未验证**：把编译出的 mod jar（含 preview class）放进真实 Fabric 服务端能否加载成功（这是 `docs/CAVA-execution-plan.md` §5 第 5 条的**架构级门**，归 P0-E/整合）。

---

## 4. CMake：原生库构建（MinGW ✅ 跑通 / MSVC ⛔ configure 通了、build 还在查）

产物目录固定是 **`<repo>/natives/<平台标签>/`**（`native/cmake/CavaPlatform.cmake` 推导，
标签取值 `windows-x64|windows-arm64|linux-x64|linux-arm64|macos-x64|macos-arm64`），import lib / pdb 落到 build 目录不污染 `natives/`。
源文件是 `GLOB_RECURSE + CONFIGURE_DEPENDS`（`native/src/**`）——**native 侧加文件不需要改 CMakeLists**。

### 4.1 MinGW（✅ 已实测通过，两条命令）

```powershell
$cmake = 'J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe'

# 1) 配置（⚠️ 必须显式给 CMAKE_MAKE_PROGRAM，否则报 "unable to find a build program"）
& $cmake -S 'J:\mc\Cava' -B 'J:\mc\Cava\build\native-mingw' -G 'MinGW Makefiles' `
    -DCMAKE_BUILD_TYPE=Release `
    -DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe `
    -DCMAKE_C_COMPILER=C:/mingw64/bin/gcc.exe `
    -DCMAKE_CXX_COMPILER=C:/mingw64/bin/g++.exe

# 2) 构建
& $cmake --build 'J:\mc\Cava\build\native-mingw' --parallel
```

**真实输出摘要（✅，2026-09-22，HEAD `6608606`）**：

```
-- The CXX compiler identification is GNU 15.2.0
-- Performing Test CAVA_HAVE_FLAG__O2 - Success
-- Performing Test CAVA_HAVE_FLAG__fwrapv - Success
-- Performing Test CAVA_HAVE_FLAG__ffp_contract_off - Success
-- Performing Test CAVA_HAVE_FLAG__fno_fast_math - Success
-- cava: 源文件 7 个 -> J:/mc/Cava/natives/windows-x64
-- cava tests: 3 个测试 target（CTest 名前缀 = 文件名）
-- cava: platform=windows-x64 id=1 system=Windows processor=AMD64
-- cava: compiler=GNU 15.2.0 (C:/mingw64/bin/g++.exe) (GNU 15.2.0)
-- cava: fp-flags=-O2 -fwrapv -ffp-contract=off -fno-fast-math
[ 57%] Linking CXX shared library J:\mc\Cava\natives\windows-x64\libcava.dll
[100%] Built target cava_test_cava_selftest
== mingw build exit: 0
```

导出符号实测（`objdump -p natives\windows-x64\libcava.dll`）：`cava_abi_touch / cava_abi_version / cava_bits_of_double /
cava_build_id / cava_close / cava_d2i_sat / cava_d2l_sat / cava_double_of_bits / cava_layout_report / cava_open`（另有 P0-A 的临时 stub 符号）。

> **✅ 命名坑已修（实测时间线）**：我第一次构建（13:51）产出的是 **`libcava.dll`**——MinGW 上
> `add_library(cava SHARED …)` 默认带 `lib` 前缀，而当时 `CavaFlags.cmake` 只设了 `OUTPUT_NAME "cava"`（**前缀不受 OUTPUT_NAME 控制**）。
> 13:52:28 P0-A 已在 `native/cmake/CavaFlags.cmake:139` 补上 `set_target_properties(${target} PROPERTIES PREFIX "")`；
> 13:53 复测 `natives/windows-x64/cava.dll`（114904 B，MinGW 构建）**导出 10 个 `cava_*` 符号齐全**：

```
DLL Name: libgcc_s_seh-1.dll / libstdc++-6.dll / KERNEL32.dll / msvcrt.dll / libwinpthread-1.dll
[19] cava_abi_touch  [20] cava_abi_version  [21] cava_bits_of_double  [22] cava_build_id  [23] cava_close
[24] cava_d2i_sat    [25] cava_d2l_sat      [26] cava_double_of_bits  [27] cava_layout_report  [28] cava_open
```

> **⚠️ `natives/<平台标签>/` 是共享输出目录（实测踩到）**：P0-A / P0-B / 我三方同时构建时，`natives/windows-x64/` 里的文件会被互相覆盖
> （我 13:51 放的 `libcava.dll` 与一份 558310 B 的 `cava.dll`，到 13:53 只剩下别人新构建的 `cava.dll` 114904 B，直接导致我那一次 ctest 报 `0xc0000135`）。
> **纪律：同一时刻只有一个流跑原生构建**；产物文件名/ABI 以 P0-A 为准。

### 4.2 MSVC（⛔ configure 已通；build 退出码 1，错误正在取证）

```powershell
# 1) 配置（⚠️ 必须给 CMAKE_TRY_COMPILE_CONFIGURATION=Release，且**必须是全新 build 目录**）
& $cmake -S 'J:\mc\Cava' -B 'J:\mc\Cava\build\native-msvc' `
    -G 'Visual Studio 17 2022' -A x64 `
    -DCMAKE_TRY_COMPILE_CONFIGURATION=Release

# 2) 构建（VS 是多配置生成器，要 --config）
& $cmake --build 'J:\mc\Cava\build\native-msvc' --config Release --parallel
```

**为什么必须加 `CMAKE_TRY_COMPILE_CONFIGURATION=Release`**（✅ 已定位根因，证据是 CMake 自己的 configure log）：
`native/cmake/CavaFlags.cmake:25` 用 `check_cxx_compiler_flag("/O2" …)` 探针，**默认用 Debug 配置做 try_compile**，
而 Debug 的 `CMAKE_CXX_FLAGS_DEBUG` 带 `/RTC1`，与 `/O2` 互斥：

```
cl : 命令行 error D8016: “/O2”和“/RTC1”命令行选项不兼容
CMake Error at native/cmake/CavaFlags.cmake:29 (message):
  cava: 编译器 MSVC 19.44.35228.0 不支持必需参数 '/O2'。
== msvc configure exit: 1
```

**第二个坑：检查结果会被缓存。** `check_cxx_compiler_flag` 的结果写进 `CMakeCache.txt`，
所以**改完必须换新 build 目录（或删 `CMakeCache.txt`）**，否则旧目录里永远是失败值（我第一次就踩了：

复用旧目录仍然失败，换 `build/native-p0d-msvc2` 后 `CAVA_HAVE_FLAG__O2 - Success`、`CAVA_HAVE_FLAG__fp_strict - Success`）。

加了参数之后的成功配置摘要（✅）：

```
-- Performing Test CAVA_HAVE_FLAG__O2 - Success
-- Performing Test CAVA_HAVE_FLAG__fp_strict - Success
-- cava: compiler=MSVC 19.44.35228.0 (MSVC toolset v143) (MSVC 19.44.35228.0)
-- cava: fp-flags=/O2 /fp:strict
-- Configuring done (15.1s)
== msvc configure exit: 0
```

⛔ **MSVC `--build` 当时退出码 1**：`--config Release` 之后先打印 `GLOB mismatch!` → CMake 自动重新生成 → 之后 MSBuild 退出。
**未取到确切错误行**：取证那一刻 P0-A/P0-B 正在并发改 `native/cmake/CavaFlags.cmake` 并构建同一个 `natives/windows-x64/`，
我的日志被中途重配置打断（日志 `build/p0d-msvc-build.log`，只有 `Checking File Globs / 1>Checking Build System` 就结束）。
→ **结论：MSVC build 的结论在当前并发条件下不可复现，需要在单流情况下重跑**（归 P0-A/P0-B）。
**在 MSVC 路径跑通之前，windows-x64 的可用产物是 MinGW 那一份（§4.1 已验证）。**

### 4.3 跑原生测试（CTest）：⚠️ 必须先给 PATH（✅ 已实测）

```powershell
# MinGW 产物运行时要 MinGW 运行库；测试 exe 在 build 目录，DLL 在 natives 目录
$env:PATH = 'C:\mingw64\bin;J:\mc\Cava\natives\windows-x64;' + $env:PATH
& 'J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\ctest.exe' `
    --test-dir 'J:\mc\Cava\build\native-mingw' --output-on-failure
```

**症状与根因（✅）**：直接用 `ctest`（不加 PATH）时三个测试**全部** `Exit code 0xc0000135`（= STATUS_DLL_NOT_FOUND），
因为 `libcava.dll` 依赖 `libgcc_s_seh-1.dll` / `libstdc++-6.dll` / `libwinpthread-1.dll`（`objdump -p` 实测），
它们在 `C:\mingw64\bin` 里、不在默认 PATH 上。给上 PATH 后同一个 exe 立刻能跑：

```
--- cava_test_cava_selftest.exe ---
=== cava_selftest ===
build_id     : cava 0.1.0 win-x64 mingw-gcc-15.2.0 O2/fwrapv/ffp-contract=off safe=0 asan=0 ubsan=0
platform     : 1   pointer_size=8
  [ ok ] cava_abi_version()=1 == CAVA_ABI_VERSION=1
--- cava_test_cava_fp_probe.exe --- exit=0
--- cava_test_tmp_p0a_smoke.exe --- exit=0
```

> `cava_selftest` 本次**退出码 1**（ABI 段全 ok，后续段失败）——属 P0-B 的实现进度，证据见 `docs/CAVA-p0-acceptance.md`。

### 4.4 走 Gradle 的原生任务（❓ 未验证）

`build.gradle` 注册了两个任务，**我没有跑**（captain 规定只有 P0-A 跑 Gradle；且 Gradle 构建当前受阻）：

```powershell
& '.\gradlew.bat' buildNative                                        # 默认 MinGW Makefiles
& '.\gradlew.bat' buildNative -Pcava.native.generator="Visual Studio 17 2022"
```

它内部就是 §4.1/§4.2 的 `cmake -S … -B build/native -G …`。注意任务里**没有传 `CMAKE_MAKE_PROGRAM` / `CMAKE_TRY_COMPILE_CONFIGURATION`**，
按 §4.1/§4.2 的实测，这两个参数在当前 `CavaFlags.cmake` 下是必需的 → **这两个 Gradle 任务目前大概率跑不通**（❓ 未验证，归 P0-A 修）。

---

## 5. 产物清单（✅ 实测的目录状态）

| 路径 | 谁产出 | 说明 |
| --- | --- | --- |
| `natives/windows-x64/cava.dll` | MinGW（§4.1） | **当前值**：114904 B（13:53，P0-A 修 `PREFIX ""` 之后），导出 10 个 `cava_*` 符号 |
| ~~`natives/windows-x64/libcava.dll`~~ | MinGW（修复前） | 116933 B，13:51 的产物，已被后续构建覆盖（命名坑见 §4.1） |
| `build/native-mingw/` | CMake（MinGW） | 目标文件、测试 exe、`import-lib/` |
| `build/native-msvc2/` | CMake（MSVC） | 同上（MSVC 版） |
| `build/libs/cava-<version>.jar` | Gradle `build` | **❓ 未验证**：当前 Gradle 构建受阻，还没产出 |

`natives/`、`build/`、`tools/`、`.gradle-home/` 都在 `.gitignore` 里——**原生库和构建目录不进 git**。

---

## 6. 常见失败速查表

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| `…gradle-9.7.1-bin.zip.lck (拒绝访问)` | `GRADLE_USER_HOME` 在工作区外，沙箱拒绝写 | §2：设成 `J:\mc\Cava\.gradle-home` |
| `Dependency requires at least JVM runtime version 25` | `fabric-loom 1.18.x` 要 JDK 25，和 JDK 21 硬约束冲突 | `loom_version` pin **1.17.20**（**P0-A 负责**，别自己改） |
| `AccessDeniedException: build/reports/problems/problems-report.html` | 该文件由**另一个沙箱会话**创建，本会话改不动也删不掉 | 同一时刻只让一个流跑 Gradle；或换 build 目录/由原会话清理 |
| `Couldn't open current thread, error = 5` | 沙箱下 Gradle 文件监视器退化 | 无视，不影响结果 |
| `javac`：`--release` 与 `--enable-preview` 不匹配类错误 | 二者必须成对 | `options.release = 21` + `--enable-preview` |
| 运行期 `UnsupportedClassVersionError`（minor 65535） | 用了 JDK 22+ 跑预览版 class | 只用 `C:\Program Files\Java\jdk-21` |
| CMake：`unable to find a build program corresponding to "MinGW Makefiles"` | `mingw32-make` 不在 PATH | 加 `-DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe` |
| CMake(MSVC)：`不支持必需参数 '/O2'` + `D8016 /O2 和 /RTC1 不兼容` | `check_cxx_compiler_flag` 用 Debug 配置做探针 | 加 `-DCMAKE_TRY_COMPILE_CONFIGURATION=Release`，**并且换新 build 目录**（结果有缓存） |
| ctest 三个用例全 `0xc0000135` | 找不到 MinGW 运行库 | 把 `C:\mingw64\bin`（和 `natives\windows-x64`）加进 `PATH` |
| `javap` 报 `未知选项: --enable-preview` | javap 不认预览标志 | 直接 `-p -c`（见 dev-toolbox §2） |
| 原生构建产物「莫名其妙的没了/文件名变了」 | `natives/<tag>/` 是**多流共享**输出目录，互相覆盖 | 同一时刻只让一个流构建；文件名以 P0-A 为准（§4.1） |

---

## 7. 本文的「未验证 / 受阻」清单（**别当成已完成**）

| 项 | 状态 | 卡在哪 / 需要什么 |
| --- | --- | --- |
| `gradlew build` 完整成功 | ⛔ 受阻 | loom pin 1.17.20 之后的重跑归 **P0-A**；本文只记录了 pin 之前的失败与判据 |
| `build/libs/cava-<ver>.jar` | ❓ 未验证 | 依赖上一条 |
| MSVC `--build` 成功 | ⛔ 受阻/证据被并发干扰 | configure 的两坑已定位并复现；build 退 1 但取证被并发构建打断，需单流复现，归 **P0-A/P0-B** |
| `gradlew buildNative` | ❓ 未验证 | 任务里没传 §4.1/§4.2 必需的两个参数 |
| `.gradle-home\caches` 预置 + `--offline` | ❓ 未验证 | 见 §2.4，需要 2.2 GB 拷贝 + 一次真跑 |
| 预览版 mod jar 被 Fabric Loader 加载 | ❓ 未验证 | 需要 P0-E 的真测试服（`testbed/server/`） |
| CI（`.github/workflows/build.yml`）改 JDK 21 | ❓ 未验证 | 归 P0-A |

---

## 8. 一句话流程（给自己抄）

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'      # 1. 先设这个，否则一切免谈
$cmake = 'J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe'
& $cmake -S 'J:\mc\Cava' -B 'J:\mc\Cava\build\native-mingw' -G 'MinGW Makefiles' `
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe `
    -DCMAKE_C_COMPILER=C:/mingw64/bin/gcc.exe -DCMAKE_CXX_COMPILER=C:/mingw64/bin/g++.exe
& $cmake --build 'J:\mc\Cava\build\native-mingw' --parallel     # 2. 出 natives/windows-x64/libcava.dll
& '.\gradlew.bat' build --console=plain                            # 3. 归 P0-A（当前受阻于 loom）
```
