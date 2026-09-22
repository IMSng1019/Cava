# Cava 门禁验证记录（captain）

> **每条门禁都必须有本机实测证据。** 这是"架构假设是否成立"的台账，与 `docs/CAVA-baseline.md`（性能基线）分开。
> 复现脚本：`tools/build-preview-gate.ps1`、`tools/setup-preview-gate-server.ps1`、`tools/LayoutGuardProbe.java`。

---

## 门禁 #4：**Gradle 构建与单元测试 → 通过（captain 独立复跑）**

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Dfile.encoding=UTF-8'
.\gradlew.bat test --console=plain --no-watch-fs --rerun-tasks
```

**结果**：`BUILD SUCCESSFUL in 6s`，exit 0；`compileJava` 打印 `Note: Some input files use preview features of Java SE 21`
（确证 `--enable-preview` 生效）。JUnit 报告（`build/test-results/test/*.xml`）逐类实测：

    cava.CavaConfigTest          tests=5  failures=0 errors=0
    cava.ffm.LayoutHashTest      tests=5  failures=0 errors=0
    cava.parity.Fnv1aTest        tests=5  failures=0 errors=0
    cava.parity.GoldenTraceTest  tests=3  failures=0 errors=0
    cava.parity.TraceDiffTest    tests=5  failures=0 errors=0
    cava.subsystem.CanaryTest    tests=5  failures=0 errors=0
    TOTAL                        tests=28 failures=0 errors=0

产物 `build/libs/cava-0.1.0.jar`（667165 B，33 个 class）实测包含 `natives/windows-x64/cava.dll`（2780964 B）、
`fabric.mod.json`、`cava.mixins.json`、`cava.client.mixins.json`。

**预览标志在 remap 之后仍然保留**（实测逐 class 读头）：`cava/ffm/CavaBindings.class` = `major 65, minor 65535`（预览版），
而 `cava/ffm/NativeLibrary.class` / `cava/parity/TickSampler.class` = `major 65, minor 0`（普通版，因为它们不直接用预览 API）。
**说明**：`--enable-preview` 是编译期要求（不写它 javac 直接拒绝 `java.lang.foreign`），编译出来的**只有真正用到预览 API 的 class** 带 65535。
所以"jar 里 preview class 数量"不是指标；`compileJava` 的那行 Note 才是。

### 本机两条无害噪声（不要误判为失败）
1. `Exception in thread "File watcher server" ... Couldn't open current thread, error = 5`：
   沙箱不允许 Gradle 起文件监视线程。**加 `--no-watch-fs` 即消失**，与构建结果无关。
2. `Directory 'C:\Program Files\Java\jdk-21.0.10' ... does not exist`（三个候选路径）：
   `gradle.properties` 里 `org.gradle.java.installations.paths` 的兜底候选，**只是提示**；
   期望的 `C:\Program Files\Java\jdk-21` 存在且被选中，构建与测试都正常。

---

## 门禁 #2 + #3：**端到端冒烟（原生库 ↔ Java FFM ↔ 一键回退 ↔ ABI 守卫）→ 全部通过**

**结论：PASS。** 这是 P0 的核心验收：Java 侧的 FFM facade **真的**加载了 C++ 产物、**真的**跑通了布局自检与所有回退路径。

### 证据链（全部实跑，产物为 CMake 构建的 `natives/windows-x64/cava.dll`）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 原生自测 | `native/tests/build-mingw.ps1` | `SUMMARY: 67 passed, 0 failed` / `RESULT: PASS`（c++17 / c++20 / CAVA_SAFE=1 **三份各 67/0**，exit 0） |
| CMake+CTest | `cmake --build build/native-captain` + `ctest --test-dir build/native-captain -C Release` | **3/3 passed**（`cava_dll_loadtest` / `cava_fp_probe` / `cava_selftest`），exit 0 |
| Java 端到端 | `java --enable-preview --enable-native-access=ALL-UNNAMED -cp <classes> cava.ffm.NativeSelfTest` | `status=OPEN`、`handle=4294967297`、**`java_layout_sum = native_layout_sum = 0x6149FD30`**、22 个 double 边界值 ×(d2i,d2l) + 8 个位模式往返**全 ok**、`SELF-TEST: PASS`、exit 0 |
| 一键回退 | `... cava.ffm.NativeSelfTest disabled` | `status=DISABLED_BY_FLAG`、**INFO 不是 ERROR**、exit 0 |
| 缺库优雅失败 | `-Dcava.native.path=<不存在>` | `status=RESOURCE_MISSING`、exit 0（不崩） |
| **ABI 守卫** | `tools/LayoutGuardProbe` | 见下表 |

### ABI 守卫逐项（`tools/LayoutGuardProbe.java`，直连原生 `cava_open`/`cava_close`）

    abi=1  sum=correct        -> status=0    handle=4294967297  native_sum=0x6149FD30   OK
       close=0  closeAgain=-3  closeForged=-4        （幂等 + 伪造句柄都安全返回错误码）
    abi=1  sum=wrong          -> status=-2   handle=0           （CAVA_ERR_LAYOUT）      OK
    abi=2  sum=correct        -> status=-1   handle=0           （CAVA_ERR_ABI_VERSION） OK
    abi=99 sum=correct        -> status=-1   handle=0           （CAVA_ERR_ABI_VERSION） OK
    abi=0  sum=correct        -> status=-1   handle=0           （CAVA_ERR_ABI_VERSION） OK
    abi=1  sum=bytewise-var   -> status=-2   handle=0           （变体被拒绝）           OK

**这一条直接保住"JVM 段错误"这个最大风险**：布局一旦漂移，`cava_open` 在**写入任何句柄之前**就以 `CAVA_ERR_LAYOUT` 失败，
Java 侧整体回退纯 Java，不会有任何一次原生调用落在错误的结构体上。

### 一个必须记住的实测约束：**`Linker.defaultLookup()` 看不到 `System.load()` 的 DLL**

本机实测：`System.load(绝对路径)` 成功后，`Linker.nativeLinker().defaultLookup()` **查不到** `cava_build_id`/`cava_open`。
`CavaBindings` 已实现回退 `SymbolLookup.libraryLookup(path, Arena.ofShared())` 并常驻该 arena（实测生效）。
**P1/P2/P3 任何新增原生调用都必须走这条已封装好的路径，不要自己写 `defaultLookup`。**

### 未验证
- **MC 侧代码未与真实 MC API 对编**（`cava.Cava` / `cava.parity.TickSampler` / `cava.client.CavaClient`）：
  P0-C 用手写桩做了语法/类型自检（exit 0），但那**不证明**与真实 API 匹配。需要 `gradlew build` 产出 yarn named jar 后补编。
- MSVC 构建、Linux x64、CAVA_SAFE 的真实 CMake 构建（P0-B 只手工模拟过）。

---

## 门禁 #6：**真实 Cava jar 在真实服务端里跑起来（启动横幅 + 原生库加载 + 布局自检）→ 通过**

这是"MC 侧代码从未在服务端里跑过"这个缺口的闭合验证（captain 亲自做）。
**做法**：把 `gradlew build` 产出的 `build/libs/cava-0.1.0.jar`（不是桩、不是探针）
连同 `fabric-api 0.96.11` 与 **Lithium / ServerCore / VMP / FerriteCore / Carpet / TIS** 一起
放进一个真实 Fabric 服务端的 `mods/`，用 `--enable-preview --enable-native-access=ALL-UNNAMED` 启动。

### 证据（服务器真实 stdout，节选）

    - cava 0.1.0
       \-- mixinextras 0.5.5
    [main/WARN]: Force-disabling mixin 'alloc.blockstate.StateMixin' as rule 'mixin.alloc.blockstate' (added by mods [ferritecore]) disables it and children
    [main/WARN]: Force-disabling mixin 'alloc.chunk_ticking.ServerChunkManagerMixin' as rule 'mixin.alloc.chunk_ticking' (added by mods [servercore]) disables it and children
    [native] 原子落盘 …\cava\natives\0.1.0\windows-x64\cava-34a578ed29e77709.dll (len=2780964, sha256=34a578ed29e77709…)
    [native] System.load(…\cava-34a578ed29e77709.dll) 成功
    [native] defaultLookup() 找不到 cava_build_id，回退 SymbolLookup.libraryLookup()
    [cava/native] 布局自检通过：native_entries=4 java_sum=0x6149fd30 native_sum(per-entry u32 sum)=0x6149fd30
    [cava/native] cava_open → sent_sum=0x6149fd30 rc=CAVA_OK result.status=CAVA_OK result.abi=1 result.native_layout_sum=0x6149fd30 handle=4294967297
    [cava/native] 原生库已打开：status=OPEN build_id="cava 0.1.0 windows-x64 GNU 15.2.0 … -O2 -fwrapv -ffp-contract=off -fno-fast-math safe=0 …" abi=1/1 entries=4
      ################ Cava 0.1.0 （Java 21 预览版 FFM + C++ 原生；P0 骨架，未注入任何游戏逻辑） ################
      ================================ Cava / native ================================
      native 状态     : OPEN  (OK)
      ABI 版本        : java=1 native=1 platform=windows-x64 build_flags=0x0 build_id_hash=0x398927d3
      布局自检        : 通过  java_sum(u32)=0x6149fd30 native_sum=0x6149fd30 entries=4
      cava_abi_touch  : 1（=1 表示原生代码确实执行过）
      config 文件     : …\config\cava.json（文件不存在，已写入默认值）
    [Server thread/INFO]: Done (7.102s)! For help, type "help"
    [Server thread/INFO]: [cava/parity] 未开启轨迹采集（-Dcava.parity.trace=<dir> 才开）
    [Server thread/INFO]: [cava] 金丝雀跳过 pathfind：enabled=false（P0 骨架或已被禁用，不参与判定）
    [Server thread/INFO]: [cava] 金丝雀自检结束：0 个通过 / 3 个注册

### 这条门禁证明了什么
1. **资源打包约定成立**：`natives/windows-x64/cava.dll` 从 jar 里被正确取出（`source=resource:/natives/windows-x64/cava.dll`）。
2. **哈希命名 + 原子落盘 + `System.load` 在生产路径上真的跑通了**，不是只有自检程序能跑。
3. **布局自检在真实服务端里通过**，且 `cava_abi_touch() == 1` 证明**原生代码确实被执行过**（不是只加载了库）。
4. **与真实优化 mod 共存启动成功**：Lithium / ServerCore / VMP / FerriteCore / Carpet / TIS 同时在装，无冲突、无崩溃。
5. **`config/cava.json` 默认值自动落盘**。

### 仍未闭合
- **金丝雀 0/3**：因为 P0 阶段三个子系统都是 `enabled=false`（按设计），所以"金丝雀主动触发目标方法看计数器有没有动"这条链路**尚未在真实环境里被验证过一次**。
  这一条要等 P1 真正注入 `PathNodeNavigator.findPathToAny` 之后才能验。**这是 P1 的必验项。**
- 黄金轨迹**尚未采到**（`-Dcava.parity.trace` 没开），确定性前置（同存档连续两次一致）也还没证明。

---

## 门禁 #5：**预览版 class 能否被 Fabric Loader 加载并执行 → 通过**

**结论：PASS。** 整个项目的载体假设是「Fabric mod + JDK 21 **预览版 FFM**（必须 `--enable-preview`）」。
如果 Fabric Loader / Mixin 无法处理 class 文件 major 65 + minor 65535（预览版标志），或者运行时拿不到
`java.lang.foreign`，那么"用 FFM 调 C++"这条路根本不成立，整个方案要重审。**这条必须先验，不能等到 P1。**

### 做法（不依赖 Gradle / Loom，纯手工 jar）
1. 用 `javac --release 21 --enable-preview` 编一个实现 `net.fabricmc.api.ModInitializer` 的类，
   编译期依赖直接取本机 Gradle 模块缓存里的 `fabric-loader` 与 `sponge-mixin`（不联网、不走 Loom）。
2. 打成普通 mod jar（`fabric.mod.json` + 一个 entrypoint），丢进真实 **Fabric 0.19.5 / MC 1.20.4** 服务端的 `mods/`。
3. 用 `--enable-preview --enable-native-access=ALL-UNNAMED` 启动真实服务端。
4. 在 `onInitialize` 里用预览版 FFM 做一次真实 native 调用（`strlen`），并把结果打进日志。

### 证据（真实 stdout，未删改）

    正在加载 Minecraft 1.20.4 with Fabric Loader 0.19.5
    加载 5 个 mod:
        - cava-gate 0.0.1
        - fabricloader 0.19.5
        - java 21
        - minecraft 1.20.4
    [CAVA-GATE] ================= PREVIEW GATE =================
    [CAVA-GATE] mod class file major.minor = 65.65535   (65.65535 == preview)
    [CAVA-GATE] runtime               = 21.0.10+8-LTS-217
    [CAVA-GATE] fabricloader          = 0.19.5
    [CAVA-GATE] java.lang.foreign     = java.lang.foreign.Linker
    [CAVA-GATE] native strlen("cava")  = 4   (expect 4)
    [CAVA-GATE] VERDICT               = PREVIEW_OK
    [13:54:31] [Server thread/INFO]: Done (14.193s)! For help, type "help"

（`major.minor = 65.65535` 直接读的是 jar 里那个 class 文件自己的头，证明它确实是预览版 class，
而 Loader 把它加载起来并执行了 `onInitialize`。）

### 适用版本（实测）
| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.4 |
| Fabric Loader（真实服务端解析到的） | **0.19.5**（与 `gradle.properties` 里 pin 的 `loader_version=0.19.5` 一致） |
| Fabric Installer | 1.1.2 |
| `sponge-mixin`（服务端实际加载的） | 0.17.4+mixin.0.8.7 |
| JDK | 21.0.10+8-LTS-217 |
| 启动参数关键项 | `--enable-preview --enable-native-access=ALL-UNNAMED` |

### 由这条门禁派生的**新的硬约束**

1. **`--enable-native-access=ALL-UNNAMED` 不能省。** 少了它 native downcall 会在运行期被拒。
2. **Fabric Loader 0.19.5 是**"能加载预览版 class 的**已知可用版本**"。以后任何 `loader_version` 变更都要重跑这条门禁。
3. 预览版 class 可以正常打成 jar、被 Loader 发现、被实例化 —— 所以 **Cava 的 `ModInitializer` 本身可以直接是预览版 class**。

---

## 门禁 #1：**Loom 版本必须 pin 到 1.17.x → 通过**

**结论：通过（已定位根因并由 P0-A 修复）。**

| 事实 | 证据 |
| --- | --- |
| `fabric-loom` **1.18.2** 要求 **JVM 25** | `Could not resolve net.fabricmc:fabric-loom:1.18.2 ... Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.`；maven.fabricmc.net 的 module metadata 属性 `org.gradle.jvm.version = 25` |
| `fabric-loom` **1.17.20 / 1.17.21** 要求 **JVM 21** ✅ | 同一属性 = 21，plugin-api 9.5.0 |
| `fabric-loom` 1.10.5 要求 JVM 17 | 同一属性 = 17 |

**决定**：`gradle.properties` 的 `loom_version` pin 到 **1.17.20**，Gradle 用 **9.7.1**（满足 plugin-api 9.5.0）。
**推论**：模板自带的 CI workflow 用 JDK 25 是**因为 Loom 1.18 需要 25**；本项目改用 Loom 1.17.x 之后，
CI 必须把 JDK 固定成 **21**（否则 `--release 21 --enable-preview` 不成立）。已核对：workflow 里现在是 `java-version: '21'`（temurin）。

---

## 附：本轮 captain 亲自跑出来的三条"环境坑"（都已固化）

1. **PowerShell 5.1 会破坏 `-Dkey=value` 参数**：`& java -Dfoo=bar ...` 实测被解析成 `ClassNotFoundException: /foo=bar`；
   `javac -cp "a;b"` 实测报 `invalid flag: :`。**解法**：JVM 属性走 `$env:JAVA_TOOL_OPTIONS`；javac 参数走 **`@argfile`（UTF-8 编码！）**。
   > ASCII argfile 会把含中文的用户目录名写成 `???`，导致 classpath 静默失效（实测报 "package net.fabricmc.api does not exist"）。
2. **`Linker.defaultLookup()` 看不到 `System.load()` 的库**（见门禁 #2）。
3. **`natives/<平台标签>/` 是共享输出目录**：实测被两个流先后覆盖（116933B → 114904B → 空 → 2780964B），
   每次覆盖都会让别人链接到不一致的映像。**同一时刻只有一个流能构建原生产物。**