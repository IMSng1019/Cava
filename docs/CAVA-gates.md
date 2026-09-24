# Cava 门禁验证记录（captain）

> **每条门禁都必须有本机实测证据。** 这是"架构假设是否成立"的台账，与 `docs/CAVA-baseline.md`（性能基线）分开。
> 复现脚本：`tools/build-preview-gate.ps1`、`tools/setup-preview-gate-server.ps1`、`tools/LayoutGuardProbe.java`。

---

## 门禁 #8：**P4 上线加固 + Windows 生产工具链切换 → 通过（captain 独立复跑，2026-09-24）**

> 本节**只记我自己跑出来的**东西。逐项标注：**复跑** = 我重跑了原命令；**复核** = 我读了源码/产物并做了独立实验；
> **未闭合** = 本机做不到（原因写清）。三条流的原始台账：`docs/CAVA-hardening-notes.md`（P4-A 加固）、
> `docs/CAVA-platform-notes.md`（P4-B 平台/CI）、P4 MSVC 轮的提交 `77480f9`。

### 8.1 ★ 交付物换成了 MSVC `/MT`（本轮唯一一处"改交付"的决定）

**起因**：`docs/CAVA-platform-and-compat.md` §1.1 的目标产物矩阵白纸黑字写着
`Windows x64 | MSVC 14.4x | cava.dll | /MT 静态 CRT`，§1.5 更写着 MinGW GCC「**仅作辅助**（其 libm 质量差，
且不是生产工具链）」。而在此之前，`natives/windows-x64/cava.dll` 一直是 **MinGW** 的 CMake 产物
（2855079 B，导入表 KERNEL32+msvcrt）。**交付物与本项目自己的契约不符**，而且 MSVC 那条路
在 P4-B 那轮只在**源码副本**上验证过、从未从真实树构建过。

**做法**（captain 亲自构建，产物按设计落到 `natives/windows-x64/`）：

    cmd /c build\msvc-captain.bat        # call vcvars64 -> cmake -S J:\mc\Cava -G "Visual Studio 17 2022" -A x64
    cmd /c build\msvc-captain-build.bat  # cmake --build build/native-captain-msvc --config Release --parallel 1

**最终交付物指纹（此后所有依赖产物的结论都以它为准）**：

    natives/windows-x64/cava.dll   257536 B   sha256 0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D
    build_id = cava 0.1.0 windows-x64 MSVC 19.44.35228.0 (MSVC toolset v143) /O2 /fp:strict safe=0 asan=0 ubsan=0
    导入表   = KERNEL32.dll（**只有它** —— /MT 生效；默认 /MD 会拖 MSVCP140/VCRUNTIME140/api-ms-win-crt-*）

| 验证项 | 命令 | 结果 |
| --- | --- | --- |
| 配置/build | 见上 | configure exit=0；build **0 个错误 / 9 个警告**，BUILD_EXIT=0 |
| 原生测试（MSVC 编） | `ctest --test-dir build/native-captain-msvc -C Release` | **4/4 Passed** |
| 自测计数 | `cava_test_cava_selftest.exe` | **180 passed, 0 failed** |
| 寻路向量 | `cava_test_cava_pathfind_vectors.exe` | cases=10000 + 60，**mismatches=0** |
| 平台数值套件（MSVC 编） | `build/native-msvc/suite-msvc/Release/cava_platform_suite.exe --golden … --expect-rows 15456` | **pass=31 fail=0 skip=1** verdict=PASS |
| 平台数值套件（GCC 编，跨编译器） | `build/platform-captain/cava_platform_suite.exe --golden … --expect-rows 15456` | **pass=32 fail=0 skip=0** verdict=PASS（15456 行逐位 0 差异） |
| ABI 布局 | 上述两条 | `cava_layout_report`=14 条、`layout_hash_sum=0x1C12265E`、`cava_open` rc=0 |
| 导出面 | `objdump -p` | `cava_abi.h` 的 **19 个符号全在**，另有 3 个未在头文件声明的 `cava_push_*` |
| ABI fuzz | `build-fuzz.ps1 -Cases 20000 -SkipBuild` | cases=40467，**crashes=0 undefined_returns=0 state_mutations=0 overruns=0** |
| Java 全量 | `gradlew test --rerun-tasks` | **tests=235 failures=0 errors=0 skipped=9**（FFM 绑定的就是这份 MSVC DLL） |

> **MSVC 多一个 skip 是正常的**：那条依赖 `__BYTE_ORDER__`（GCC 才有），MSVC 上走 `skip()` **明确记账**，
> 不是假装通过。
>
> **★ 关于条数（我在这里先判错了别人，见 8.3 第 3 条）**：同一个二进制、同一个库，
> **条数取决于命令行** —— 不带 `--expect-rows` 是 31 项，带 `--expect-rows 15456` 是 32 项
> （多的一条是 `黄金向量行数 = 15456`，源码 `cava_platform_suite.cpp:645`）；
> **CI 用的就是带 `--expect-rows` 的那种**。所以 P4-B 与 MSVC 轮报的 32 都是对的。
> ⇒ 今后这类"计数"结论一律写成 **"命令 + 条数"**，不要只写一个数。

> **本机环境坑（已固化）**：`cmake --build ... --parallel`（多节点 MSBuild）在本沙箱里**静默失败** ——
> exit=1，却只打两行 `Checking File Globs / 1>Checking Build System`，**一条 error 都没有**。
> 加 `--parallel 1` + `set MSBUILDDISABLENODEREUSE=1` 后 0 错误通过。**这不是代码问题**，
> 看到"MSVC 编不过"先看是不是多节点 MSBuild。

### 8.2 加固：P4-A 的六项，我逐项复跑

| 项 | 我的复跑命令 | 我的结果 |
| --- | --- | --- |
| 熔断 | `tools/harden-breaker.ps1` | `BREAKER CHECK: PASS`；软失败 7 次不熔断、硬失败第 5 次熔断、**ERROR 恰好 1 条**、熔断后 100 次全 `-100`、`attempts=12` 冻结、`unavailableCalls=102` |
| 看门狗 | 同上（报告行） | `看门狗[enabled=true threshold=50.000ms calls=12 slow=0 max=1.049ms warns=0 …]` —— 只观测 |
| 一键回滚 | `tools/harden-rollback.ps1 -SkipBuild` | `ROLLBACK CHECK: PASS`；B 腿 `DISABLED_BY_FLAG`/`available()=false`/`pathfind→-100`/`ERROR=0`/`attempts=0` |
| fuzz | `build-fuzz.ps1 -Cases 20000`（三份产物） | 3×40467=**121401 例，crashes/undefined/state_mutations/overruns 全 0** |
| SAFE 构建 | 同上 safe 腿 | SAFE DLL 跑满 40467 例无崩溃（断言行本身的对照见 P4-A 台账） |
| Java 门禁 | `gradlew test --rerun-tasks` / `gradlew build` | 235/0/0/9、BUILD SUCCESSFUL |

### 8.3 我在复验里改掉的四处（都是"文档/计数与事实不符"）

1. **熔断口径的类注释是陈旧的**：`NativeCallGuard` 类注释还写着"返回值 < 0 ⇒ 记一次失败"，
   与实测逼出来的两级口径（硬 {-1,-2,-5,-6} 才熔断，软 {-3,-4,-7} 只计数）矛盾 ⇒ 已改。
2. **`abort()` 把"原生调用抛异常"记进了 `instrumentationFailures`** —— 那会让运维在**真故障**时
   误判成"看门狗坏了"，把注意力引到错误组件上。已拆成独立计数 `abortedCalls`，
   并加单测 `CallWatchdogTest#anAbortedCallIsNotAnInstrumentationFailure` 钉住（提交 `66145db`）。
3. **★ 平台套件条数：我判错了别人，最后是"两个数都对"**。P4-B 与 MSVC 轮都报 32，我先测到 31，
   就把文档里的 32 改成了 31（提交 `4731a79`）。MSVC 流不服、给了可复核证据（S3 有 7 条），我再查源码：
   `cava_platform_suite.cpp:645` 那条 `黄金向量行数 = 15456` 的断言**只在传了 `--expect-rows` 时才执行**，
   而 **CI 恰好传了**。实测四种组合后确认：**不带 = 31，带 = 32；MSVC 编的套件在带 `--expect-rows` 时是 31+1skip**。
   ⇒ 已在 `docs/CAVA-platform-notes.md` **二次更正（更正我自己的更正）**，并把教训写死：
   **"计数"类结论必须写成"命令 + 条数"**，否则一个依赖参数的数字会被当成绝对值，还会把对的判成错的。
   > 这条本身也验证了本项目的分工价值：**子代理有实测证据时可以据理力争，captain 也要能被证据推翻。**
4. **导出面比头文件多 3 个符号**：`cava_push_away_from` / `cava_push_box_filter` / `cava_push_section_plan`
   两份产物都导出，但 `cava_abi.h` 里没有它们。**我第一版结论写错了**（写成"只给 native 测试用、
   Java 侧从不解析"），随后查 `cava/push/NativePush.java` 发现**Java 真的会解析这三个符号**
   （它自己做 `libraryLookup` + `downcallHandle`，因为不在冻结的 `CavaBindings.REQUIRED_SYMBOLS` 里）。
   ⇒ 真正的结论是：**这是一个未被 `cava_open` 布局自检覆盖、也没有任何自动守卫的第二导出面**，
   签名漂移只会变成一次静默的 FFM UB；默认配置下不会触发（`cava.push.mode` 默认 `off`）。
   已按这个口径改写契约 §2.1 第 9 条。
   > 教训：**"多导出的符号没人用"是个假设，不是事实** —— 假设要 grep 过 `src/main/java` 才能写进文档。
   > （这条正是"复核必须独立做"的价值：P4-A/B 都没查这一层，我一查发现两个流都没覆盖的风险面。）

### 8.4 新增护栏：**产物指纹纪律**（这是本轮真实付过代价的一条）

`natives/<tag>/` 是**共享输出目录**，而且**有两个生产者**（根 CMakeLists 的 `cava` target 与
`native/tests/build-mingw.ps1`）会写同一个 `cava.dll`。本轮实测到的事故形态：

- P4-B 用自己的产物业已跑完的验证（`43129D92…`），被 P4-A 一次 `cmake --build` **静默换掉**（`D751A3D1…`）；
- P4-A 报告里的一条 fuzz 结论随后也被另一次重建作废。

**规则（今后照做）**：
1. 任何"依赖产物"的结论**必须记录 Size + SHA256**；哈希一变，那条结论即作废，必须重跑。
2. 交付物的**权威生产者 = 根 CMakeLists**（`gradlew buildNative`）；`build-mingw.ps1` 只是辅助路径。
3. 落交付物之前先确认没有别人正在构建；MSVC 产物**故意**只写 `natives/<tag>/`（这是设计），
   所以任何人在它之后再跑一次别的构建，都必须**重新确认交付物哈希**。

**已落地（不再靠人记）**：构建侧现在**每次链接完都打一行指纹** ——
`cava: ARTIFACT <路径> size=<n> sha256=<hex>`（根 CMakeLists 的 `POST_BUILD` 挂 `native/cmake/CavaHash.cmake`），
`native/tests/build-mingw.ps1` 在写完共享目录之后也打同样一行。实测输出：

    -- cava: ARTIFACT J:/mc/Cava/natives/windows-x64/cava.dll size=257536 sha256=0ebe3b04a869819d7a59c8adc11b0d1277b120b80f956a7c05d2eb62d007904d

（提议人是 P4-C 流：它的取证脚本在 13:41→14:0x 之间看到**三个不同哈希**轮番出现，说明"交付物是 X"
这种说法在当时一小时后就不成立了。构建日志里留痕是最便宜的修法。）

### 8.5 崩溃取证 + 并发（P4-C）：我逐项复跑

| 项 | 我复跑的命令 | 我的结果 |
| --- | --- | --- |
| 崩溃取证链 | `pwsh -File tools/crash-probe.ps1` | **CRASH-PROBE: PASS**，13/13 断言；四种输入判定互不相同：`NOT_CAVA_PROBE` / `NOT_CAVA` / `CAVA_NATIVE_FAULT` / `PARSE_FAILED`（后者 **exit=2**，拒绝给结论） |
| "真崩"是不是真的 | 同上（三次真实 JVM 硬崩） | 三次 `EXCEPTION_ACCESS_VIOLATION`、退出码 1、各自产出 hs_err；**没有 WER 弹窗、没有挂起**（214/202/200 ms） |
| 并发 fuzz | `build-fuzz.ps1 -Cases 20000 -SkipBuild` | 三份产物各 40467 例 + **8 线程 × 4000 轮（36112 次调用）**：`crashes=0 undefined_returns=0 torn=0 contract_violations=0 overruns=0` |

**这一项真正的价值是"判定方法本身可证伪"**：探针 DLL 导出的符号就叫 `cava_crash_probe_null_deref`，
所以**任何按文本 grep 的解析器都会把探针崩溃误判成"Cava 的 bug"**；而这个解析器**先从 hs_err 的
Dynamic libraries 段解析出问题帧属于哪个模块**、再下判定 —— 探针腿因此正确地得到 `NOT_CAVA_PROBE`。
手工造的 fixture 是**截断过的真实日志**（`.gitignore` 挡住 `hs_err_*.log`，故用 `git add -f`，理由写在
`native/tests/vectors/README.md`）。

**并发实测发现（如实记录，不是"能过"）**：并发替换形状表期间 `cava_resolve_move` 有 **0.381%** 返回
`CAVA_ERR_ARG`（角色掩码归因：只求解 0/11736、求解+换表 0/35736、四角色全开 367/96439）。
**不是内存损坏**（无撕裂读、无越界写、无未定义返回码），是"**调用序列层面没有原子性**"。
⇒ 我把它**写成契约条款**（§2.1 第 10 条）而不是给原生加锁：今天所有入口都在 Server thread
（注入点逐条读过），加锁的代价全落在热路径上、收益为零。上线判据同时扩成三项：
`unavailableCalls == 0` **且** `offThreadCalls == 0` **且** `tripped == false`。
`offThreadCalls` 这个守卫**只计数、不加锁、不改行为**，并且有一条"故意改坏就变红"的单测钉着。

### 8.6 ★★ 性能对比（prompts/04 推迟项）—— 以及它**反手抓到的两个正确性缺陷**

命令（两条腿，产物哈希每条腿开始/结束各算一次，四条腿 `#dllStable` 全 True）：

    pwsh -File tools/parity-perf-pathfind.ps1 -Leg off -Native off -AiLoad -OldBench 2000
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg on  -Native on

**性能（µs/次；off = 同一套整合包、native 关闭）**：

| 场景（平均节点数） | off | on | 倍数 | 每 tick（×0.22 次/tick） |
| --- | --- | --- | --- | --- |
| long128（128） | 592.6 | **266.8** | 2.53x | −72 µs |
| slalom（138） | 10606.9 | **5507.9** | 1.95x | −1122 µs |
| maze41（200） | 658.2 | **273.5** | 2.62x | −85 µs |
| maze63（503） | 1861.5 | **636.7** | 3.04x | −269 µs |
| 强制每次真推窗口（repush） | — | — | 1.28–1.60x | — |
| 单节点级搜索（旧 6 格场景） | 2.0 | 23.3 | **0.09x（慢 11 倍）** | — |

"每 tick 0.22 次"是**实测**（40 僵尸 + 1 村民、1200 tick、两腿各一次：0.2158 / 0.2317），不是估的；
但"每次调用的路径分布"来自合成场景 ⇒ **每 tick 那一列是投影，不是直测**（这条已在 P1 台账里标明）。

**★★ 一致性判定：`DIVERGENT(2)`，exit=2 —— 两个缺陷都会改变游戏行为**：

| 缺陷 | 现象（原文） | 机制（已定位到源码级） |
| --- | --- | --- |
| **1. 窗口截断** | `detour128/repush`：`nodes_avg` off=128.00 / on=64.00，`end` off=(159,71,0) / on=(95,71,0)，`manh` off=1.000 / on=65.000（最优路径绕出包围盒，窗口外那段走廊不存在） | `RegionRect.forSolve` 只按"节点扫描边距"开窗（包围盒 +4），**没有覆盖"最优路径可能离开包围盒"** |
| **2. 镜像无失效源（会穿墙）** | `detour128/reuse`：`collisionNodes` off=0 / **on=8**，`firstBadNode`=(96,71,0)，`mirror=pushes:0,reuse:8308` | 复用判据是"同矩形 + `lastTick != Long.MIN_VALUE`（无失效事件哨兵）"，而 `RegionMirror.onBlockChanged/onSectionUnloaded/onWorldChanged` **在 `src/main/java` 的生产路径里一次都没被调用**（**我独立 grep 复核过**：唯二的调用点是性能 bench 自己故意调的）；契约说的主钩子 `ChunkSection.setBlockState` 在 18 个 mixin 文件里**不存在** |

其余 4 个场景（long128/slalom/maze41/maze63）在两种 mode 下**逐字段一致**（节点数/末节点/曼哈顿/
整条节点序列 FNV/穿墙计数）。**可证伪对照**：只把一条腿的终点挪一格（`-Dcava.pathfind.perf.targetOffset=1`）
⇒ `CTL COMPARE EXIT=2`，且 long128 连"路径长度完全相同"也照样报红 ⇒ 证明"一致"不是比对太粗才通过的。

> **★ 为什么这件事没有炸到真实服务器**：`cava.pathfind.native` **默认是 false**（P1 的门禁，
> 见 `PathfindSwitches`）。所以这两个缺陷在**默认配置下根本不会被触发** —— 它们是"**打开开关之前必须先修**"，
> 不是线上事故。这恰好说明那条门禁是有价值的，也说明**"性能轮"实际上起到了 P1 的验收测试作用**：
> 它把"求解器自证一致（live 96220/0）"与"端到端行为一致（本轮的逐字段比对）"这两件事区分开了 ——
> 前者只保证"同一份推上去的数据算得对"，**不保证"推上去的数据是对的/够用的"**。

**修复轮已启动**（P1-FIX 流）：①把复用限制在"同一次推送所在的 tick 内"（同 tick 复用原理上拿不到陈旧地形）
+ 接上 `ChunkSection.setBlockState` 失效钩子（O(1)，默认仍走安全路径，跨 tick 复用要金丝雀证据才允许开）；
②对"结果可能被窗口截断"做保守检测并**回退 Java**（触边界 / 预算耗尽 / 末节点距目标 > reachRange），
按原因分别计数（上线要看回退率）。**窗口策略本身（开多大/要不要可增长）是 captain 的裁决项，不在修复轮里自行决定。**


**修复轮结果（P1-FIX，提交 `2790772` / `6365a0c` / `ddb919c`）—— 我复核过的部分**：

| 项 | 结果 |
| --- | --- |
| 一致性 | `-Compare off/on` → **CONSISTENT，EXIT=0**，12/12 preset×mode 逐字段一致；`detour128` 两种 mode 都是 `nodes=128 end=(159,71,0) manh=1 collisionNodes=0` |
| 缺陷 1 的反面证据 | `/cava pathfind invalidate <preset>`：5 个 preset PASS（钩子 +1、失效 +1、B≠A、B==Java 参考） |
| 两条可证伪对照 | **都变红**：guard off ⇒ `detour128` 回到 `nodes 64 / manh 65`；`crosstick+invalidation` off ⇒ `collisionNodes 8 firstBadNode=(96,71,0)`（穿墙复现） |
| 性能（4 个场景） | 接管率 100%、回退 0、**2.16x–2.76x**；`detour128` 因回退变慢（0.68–0.73x，这是修正确性的代价）；1 节点场景退化（0.17–0.62x） |
| 门禁 | `gradlew test --rerun-tasks --no-daemon` → **tests=257 failures=0 errors=0 skipped=9**（我复跑）；`ctest` 4/4；交付物 DLL 未改动（`0ebe3b04…`） |

**★ 第三个缺陷：比对脚本的"逐字段一致"曾经是假的。** `Path.reachesTarget` 在 Java 侧被填反，
而比对脚本把键名写成了 `reachesTargetFlag`（回执里的键是 `reachedTargetFlag`）⇒ `Field()` 两边都返回 `(缺)`，
**这个字段从来没有被比过**（我读了脚本，勘误注释与修正都在）。
⇒ 这是本轮最值得记住的教训之一：**"逐字段比对"本身也会静默漏字段**，必须与回执字段名做**机械对拍**，不能靠人读。

### 8.6.1 captain 裁决（P1 能不能上线）

- **R1 窗口策略**：**保持"包围盒 + 4 + 保守回退"**，现在**不**扩 ABI 去加"触边/窗口不足"信号。
  理由：默认开关本来就是关的，当前最缺的是"真实负载下净收益是不是正的"这个数字；
  ABI 一旦动就要两侧同时改 + 重算 `layout_hash_sum` + 重新验证三份产物，等真要默认打开时再做。
  **已知假阴性**（已写进 P1-FIX 文档）：大迷宫里"被切掉一角但已走过直线距离"的截断会漏检。
- **R2 默认打开 `cava.pathfind.native`：否。** 三条判据缺一不可：
  ①**真实 AI 负载**下净收益为正（合成场景的 2.16–2.76x 不算数）；②回退率可观测且 **≤5%**；
  ③短程调用按规模分流之后再测。当前实测真实 AI 回退率 **47.8% / 51.8%**（回退 = 白付一次原生 + 再跑一次 Java）。
- **R3 回退率**：**写成上线硬门禁**（≤5%，且必须在真实 AI 负载上测，不能用合成场景）。
- **R4 跨 tick 复用**（`-Dcava.mirror.reuse.crosstick`）：**保持默认关**，直到补上"区段卸载"失效源
  **并且**在活 tick 服务器上拿到金丝雀证据（当前测试服 tick freeze 让"同 tick"与"跨 tick"无法区分）。
- **R5 接口**：本轮**不**给 `RegionSource` 加方法（不为未启用的功能扩大冻结接口）。
  记一个上线的可观测性待办：`RegionMirror.report()` 目前**没有调用点**，`/cava pathfind stats` 里看不到
  镜像的推送/复用/失效计数 —— 要打开跨 tick 复用时必须一并接上。
- **R6 机械对拍**：比对脚本的字段名与回执字段名要有一条**测试**去对拍（本轮已经因为笔误漏掉一个字段），
  交给下一轮做。

### 8.7 仍未闭合（诚实清单）

- **7 天连续运行 / native 回退计数为 0**（prompts/07 验收第 2 条）：本机做不到，没有 7×24 环境。
- **非 Windows 平台**：本机无 Linux/macOS/ARM 工具链、Docker 守护进程未运行、WSL 枚举被拒；
   5 平台 CI 矩阵**写全了但一次都没跑过**（job 名里带 `[unverified-local]`）。
- **ASan / UBSan / TSan**：同上，本机跑不起来（只在 CI 里排了 ASan/UBSan；TSan 连排都没排）。
- **没有在真实服务端上让 Cava 自己崩过**：正向取证靠"同名模块替换"构造，不是真实缺陷。
- **`cava_open`/`cava_close` 的并发未覆盖**（生产里只在启动/关闭各一次）。
- **`offThreadCalls` 的真实越线一次都没观测到**（单测里是故意造的第二条线程）。
- **MSVC 产物的 fuzz**：MSVC 流自己声明"没跑过"，**我补跑了** —— `build-fuzz.ps1 -Cases 20000` 直连
   交付物（`sha256 0ebe3b04…`，build_id 里是 `MSVC 19.44`）：`cases=40467 crashes=0 undefined_returns=0
   state_mutations=0 overruns=0`，**PASS**；并发阶段（8 线程）同样 `torn=0 contract_violations=0`。
   ⇒ **MSVC 产物现在与 MinGW 产物享受同一套 fuzz 证据**。
- **MSVC 产物的 `sqrt` 反汇编 / 反查编译开关**：`platform-flagcheck.ps1` 只对 MinGW 构建目录跑过，
   MSVC 构建目录没跑（`sqrt` 行为已由套件的 5 个定点断言覆盖，但"从产物反查"这一层缺）。
- ~~性能对比（prompts/04 验收）~~：**已完成**（见 §8.6），但它抓出的两个缺陷**修完之前 P1 不能上线**。
- **prompts/04 的整服层验收**（2000 实体 × 6000 tick 逐 tick 差分）：**未做**。
- **自然地形上的"窗口不足"发生率**：未测（6.2 是人造几何）。
- **多生物同 tick 并发寻路**的并行度影响：未测（注入侧有锁 ⇒ 并行度 = 1）。

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

## 门禁 #7：**P1 的 ABI 扩展在两侧同时落地并端到端复验 → 通过**

**为什么单列一条**：扩 ABI 是本轮风险最高的一次改动 —— 它同时动 C 结构体、原生布局注册表、
Java 的 `MemoryLayout` 镜像、以及**两侧算出的 `layout_hash_sum`**。任何一侧漏改，
`cava_open` 就会 fail-closed，整个 mod 静默回退纯 Java（**看起来"能用"，其实原生一次都没被调用**）。

### 最终状态（captain 亲自复跑）

| 检查 | 结果 |
| --- | --- |
| 原生自测（三种构建：c++17 / c++20 / `CAVA_SAFE=1`） | 各 **130 passed, 0 failed** / exit 0 |
| `ctest`（4 个用例：dll_loadtest / fp_probe / pathfind_vectors / selftest） | **4/4 passed** |
| Java 侧 `CavaLayouts.checkAgainstCAbi()` | **problems = 0**（逐字段 (offset,size) 与 C 编译器一致） |
| 两侧 `layout_hash_sum` | Java `0x6975CBF9` == native `0x6975CBF9` |
| `cava.ffm.NativeSelfTest`（真实 dll） | `status=OPEN`、`handle=4294967297`、`java_layout_sum == native_layout_sum`、**`SELF-TEST: PASS`** |

9 个结构体（实测 size/fields）：
`CavaLayoutEntry 544/8`、`CavaLayoutReport 34848/8`、`CavaOpenParams 32/5`、`CavaOpenResult 24/4`、
`CavaPathRequest 56/13`、`CavaPathNode 32/8`、`CavaMobProfile 192/18`、`CavaStateRecord 20/5`、`CavaCollisionBox 24/6`。

### 这条门禁真正抓到的四个坑（每一个都会静默错）
1. **`CAVA_PNT_*` 序号表是从记忆里编的**：从索引 5 起全错位，且含 4 个 1.20.4 **不存在**的常量、缺 4 个真实常量。
   惩罚表 `float penalty[26]` 按它索引 ⇒ **路径照样算得出来，只是不与原版一致**。已用 `PathNodeType` 的 `static{}` 字节码逐条重建。
2. **`CavaPathNode` 在原生注册表里漏登记了 2 个字段**（`type` / `flags`，只登记了 6/8）。
   靠**和值不等**定位：原生 `0x80b49975` vs Java `0x6975CBF9`；
   在 Java 侧只哈希前 6 个字段**恰好**复现 `0x80b49975`，从而钉死根因。
   > **教训：布局自检的"和值不等"是真实信号，绝不能当噪声跳过。**
3. **FFM 的 `structLayout` 不会自动插填充，而是直接拒绝错位成员**；同时 JDK 21 的 `paddingLayout`
   **无法命名**，导致两侧字段数/大小不一致。最终把字段顺序选成两侧都零内部填充，尾部填充改成显式具名字段。
4. **我手算的 offset 错了 3 处** —— 全部由编译器的 `offsetof/sizeof` 实测值纠正。

### 由这次事故派生的**永久护栏**（P0-B 主动加的，值得推广）
每个结构体现在做**两套**比对：
- **硬编码期望表**（变更探测器：结构体被改动时它会红）；
- **用 `offsetof/sizeof` 现算的机械表**（连字段名顺序都比）。

> 为什么两套都要：如果只把"人给的字段表"抄进硬编码表，**两张表会照同一份错理解一起错**，测试反而"通过"。
> 只有机械表能抓住 `CavaPathNode` 漏字段那一类。**"测试通过"不等于"理解正确"。**

### 本轮附带修掉的一个"假绿"（值得单独记）

`cava.ffm.PathfindAbiTest` 的 6 个用例在**每一次** `gradlew test` 里都是 **skipped**：
测试任务强制 `cava.native.enabled=false`，于是 `tryOpen()` 返回 `DISABLED_BY_FLAG`，整类命中 assumption。
**构建一直是绿的，但整个原生 ABI 一次都没被测到 —— "绿"不等于"测过"。**

修法：测试任务把两个开关**成对**决定（工作区里有 `natives/windows-x64/cava.dll` 就打开原生并指过去；
没有就关掉原生、跑纯 Java 回退）。两条路都实测过：

    有 dll  -> PathfindAbiTest ran=6 skipped=0 ；NativeFallbackTest skipped
    无 dll  -> NativeFallbackTest ran=2 skipped=0 ；PathfindAbiTest skipped

### 附带确认：兼容层报告在真实服务端里跑通了（端到端）

同一轮实机启动（含 Lithium / ServerCore / VMP / FerriteCore / Carpet / TIS）里，服务端主线程打出了完整归属表：

    CAVA-COMPAT|v1|header|mc=1.20.4|loader=0.19.5|native=OPEN|mods=56|relevant=5|overlaps=10|defer=entity,redstone|rules=0/19
    CAVA-COMPAT|v1|owner|pathfind|lithium|mixin.ai.pathing|native|auto|decided|LandPathNodeMaker 缓存短路（priority 990）；不碰 PathNodeNavigator
    CAVA-COMPAT|v1|owner|pathfind|servercore|optimizations.misc.PathFinderMixin|native|...
    CAVA-COMPAT|v1|owner|entity|vmp|entity.move_zero_velocity.MixinEntity|mod|auto|pending-p2|...
    CAVA-COMPAT|v1|owner|redstone|carpet|CarpetSettings.fastRedstoneDust|mod|auto|pending-p3|...
    [Server thread/INFO]: Done (6.051s)!
    [cava] 金丝雀跳过 entity：兼容层：让位给 lithium(...) + servercore(...) + vmp(...)
    [cava] 金丝雀跳过 redstone：兼容层：让位给 lithium(...) + carpet(...) + carpet-tis-addition(...)

两点值得注意：
1. **Mixin 层面确实生效了**：日志里有
   `Force-disabling mixin 'ai.pathing.LandPathNodeMakerMixin' as rule 'mixin.ai.pathing' (added by mods [cava])`，
   证明 `custom.lithium:options` 那条配置真的被 Lithium 读到了（不是"写了但没人读"）。
2. **金丝雀的 0/3 现在有解释了**，而且解释是**逐点、带 mod 名的** —— 不再是笼统的"P0 骨架未启用"。
   这正是兼容层该有的样子：**每个让位决定都能在日志里被追责。**

> ⚠️ **一个必须说清的性能事实**：`mixin.ai.pathing` 现在**已经关掉**，而 P1 的原生路径**尚未接管**
> （`cava_pathfind` 保守返回 `CAVA_ERR_UNIMPLEMENTED`）。也就是说**在 P1 接管之前，寻路比装 Cava 之前更慢**
> —— 关掉的是 Lithium 的 `LandPathNodeMaker` 缓存短路，纯损失、没有任何补偿。
> 回退办法是删掉 `fabric.mod.json` 里的 `custom` 段（单测会提醒）。**这条已上报 captain 待拍板。**

### 验证这类事情时的一个陷阱（我自己先踩了一次）

我第一次验"无 dll"是直接把 dll 改名后跑 `gradlew test` —— **结论是错的**，
因为 **Gradle configuration cache 复用了 dll 还在时算出的那个决定**。
**凡是依赖"配置期文件是否存在"的验证，必须加 `--no-configuration-cache` 重跑**，
否则你测的是缓存，不是代码。（我是用一个临时测试打印测试 JVM 真正看到的系统属性才确认这一点的。）

### 门禁 #6 的未闭合项：**金丝雀已在真实服务端里真的 +1 → 已闭合**

`docs/CAVA-execution-plan.md` 之前记着「金丝雀 0/3，从未在真实环境里被验证过一次」，
而"**静默失效**"是三种失效模式里最危险的一种（服务器正常启动、TPS 正常，但原生从未被调用）。
注入流把它验成了真的 —— 真实服务端（Fabric 1.20.4 + Lithium + ServerCore + VMP + FerriteCore + Carpet + TIS）：

    [cava/pathfind] 金丝雀 PASS：主动触发 findPathToAny 一次，计数 0 -> 1（原版返回 Path(4 节点)）；
      canary=1 takeovers=0 nativeCalls=0 errors=0 disabled=false reasons={skipped=1}
    [cava/pathfind] BENCH n=20000 ns/op=102731.8 avgNodes=4.00 canaryDelta=20000(expect 20000)

**"+1"不是调 `hook.hit()` 伪造的**：探测在真实世界 + 真实生物上真调了一次 `PathNodeNavigator.findPathToAny`；
bench 里 20000 次真实调用 `canaryDelta=20000/20000`（一次不多不少）。
旁证：`remapJar` 后注解已是 `method_52`/`method_54`、accessor 是 `field_61`/`field_18708`，**jar 里不需要 refmap**。

> **仍未闭合的**：`takeovers=0` —— **原生从未成功接管过一次**。
> 原因已定位（captain 的接口设计错误：档案需要实体位姿与惩罚表，而只有注入点拿得到，
> 所以"镜像流产出档案"注定恒返回未就绪）。接口已改（`6a925ef`），等镜像侧跟进后复测。

### 从"金丝雀从未被验证"这件事里学到的三条（都来自注入流的真实踩坑）

1. **金丝雀会"鸡生蛋"**：只在注入体里自举 ⇒ 服务端跑 75 秒日志里**一条 `[cava/pathfind]` 都没有**，
   于是**无法区分「mixin 没生效」和「这段窗口里根本没有寻路」**。
   修法：加一个**只做自举**的 `MinecraftServer.<init>` HEAD 注入。
   > ⚠️ **附带硬约束**：**构造器 `@At("HEAD")` 的 handler 必须是 `static`**，
   > 否则 Mixin 抛 `InvalidInjectionException` 且**整个服务端起不来**（实测崩溃）。这是"启动即崩"级。
2. **反射读原版 private 字段在生产环境必然失效**：MC 在运行时是 **intermediary** 命名，
   实测 `NoSuchFieldException: net.minecraft.class_15.penalizeDeepWater`。
   **必须用 `@Accessor`**，不能用反射。
3. **性能测量不该依赖 profiler**：本机 async-profiler 不可用、spark 采不到内层帧，
   注入流改用 `/cava pathfind bench <n>`（同一 jar/存档/mod 集，只改一个 `-D`）。
   它的**诚实读法**值得推广：
   > A/B/C 三腿的差（106–131 µs）**小于本机噪声**（B 比 A/C 还慢，物理上不可能）⇒ **钩子边际开销本机测不出**；
   > 而 D 腿（诊断开关跳过门禁）**396.7 µs ≈ 3.0× 原版**，远超噪声 ⇒
   > **只要 `cava_pathfind` 还保守回退，开原生就是净亏约 3 倍。**
   > **在原生真能出路径之前，不宣布任何性能结论。**

### 一条被"实测推翻一半"的担忧：位置依赖的碰撞形状

我在裁决块里要求"位置依赖的碰撞形状**不许静默发散**"，前提是它会影响碰撞。
镜像流按裁决实现后，顺手做了一个**比裁决前提更强**的实测，结果把风险缩小了：

| 项 | 静态面 | 实际需要守卫的 |
| --- | --- | --- |
| 覆写了带 `world/pos/ShapeContext` 形状方法的方块类 | **114 个** | **3 个类 / 64 个状态**（`BambooBlock` / `PointedDripstoneBlock` / `ScaffoldingBlock`） |

它没有按"声明了参数就算"来守卫（那会把大量"声明了但根本不看"的方块误挡），
而是对这些类**用 18 个合成上下文**（邻居=空气/石头/自身 × 两个位置 × `ShapeContext`=absent/上方有实体/下降）**重算碰撞盒，只守卫真的会变的那 64 个状态**。

**更重要的实测**（1.20.4 的碰撞形状基本与邻居无关 —— 连接条只出现在 outline 里）：

    shapes oak_fence:        air=[0.375,0,0.375 -> 0.625,1.5,0.625]，solid/self/ctxAbove **完全相同**
    shapes cobblestone_wall: air=[0.25,0,0.25 -> 0.75,1.5,0.75]      **完全相同**
    shapes iron_bars / oak_fence_gate / oak_stairs / stone / water:  **完全相同**

⇒ 原以为"2/16 连接条被低估"的风险**对碰撞不成立**；MODE_EMPTY 视图对绝大多数方块是**精确**的。

**运行期形态**：push 在 fill 后、上传前扫区域 id，命中被守卫的状态即**回退**（默认开，
`-Dcava.mirror.shape.guard=false` 可关做 A/B）；成本 = O(区域方块数) 次数组查表。

> **残留风险（已写明，未消除）**：那 18 个变体是**启发式**，不是穷举；真实服务器上仍需复核。

### P2 第 2 核（broadphase + `pushAwayFrom`）：**技术成功，但不该上线**（2026-09-22）

**这一节的结论是"不做"，而它和"做成了"一样有价值 —— 因为它是实测出来的。**

**技术上是成功的**：区段 broadphase 在真实服务端**真的接管过**（`bpTakeovers=16308 bpFallback=0 bpErrors=0 bpDesync=0`），
shadow 同 call 对拍 `bpCompared=4025 bpMismatch=0`（对拍另一侧**用原版自己的 `ChunkSectionPos` + `LongAVLTreeSet` 转写**，
顺带证明位布局没读反）。内核脱离 MC 编译通过、62 checks 全过、跨语言向量 0 不一致。

**但性能净亏**：同 call A/B（4025 样本）**`nativePlan=8933ns` vs `vanillaPlan=2113ns` ⇒ 净亏 ≈ +6.8µs/次**，
**瓶颈在 FFM 边界不在算法**（本负载平均只访问 ~1 个区段 —— 边界成本摊不开）。

> **所以决定：不上线。** `-Dcava.push.*` 保持 opt-in、默认关。
> 这与第 1 核（live 净亏 +1.86µs、默认关）是同一个处置：**技术可行 ≠ 值得开**，
> 而"测出来是亏的就不开"比"为了交付而硬上"正确得多。

**一条必须记住的负面结果**：`pushCalls=0` —— 本整合包里 `MobEntity` 召唤后约 1 秒**被静默移走**
（`PersistenceRequired`/`Invulnerable`/`NoAI`/`NoGravity` 都挡不住，无死亡消息无 crash）；
`-Dcava.push.mode=off -Dcava.push.broadphase=off` 下现象**完全相同** ⇒ **与本轮改动无关**。
矿车走自己的覆写，船没进实体 lookup。
⇒ **没能构造出"能证明 push 接管不变且更快"的真实负载。**

> **这条比性能数字更重要**：它说明**"实体推挤"这个核在当前整合包上可能根本没有负载**。
> 任务书把它排在性价比第 2 位是基于"原版在密集区域是 O(n²)"的一般判断，
> 而**实测负载不支持这个前提**。任何后续要在这上面投入的人，先看这条。

**可证伪金丝雀（又一次能红的测试）**：`-Dcava.push.canary=drop-last-section` 只丢计划里最后一个区段 ⇒
同场景 `avgDist 7.689 → 3.9915`、`maxDist 8.7001 → 6.9678`、`moved 200 → 183`。
**原生结果真的进了物理** —— 这比"计数器 +1"强。

**定点真值表又抓到两个真错**：`section_coord` 漏了 `>>4`（SP-1..5 全红）；
以及一处**测试自己写错**（容量给 64，而内核正确返回了错误码）。

**核 3 射线与核 4 `getOtherEntities`：判定不做，理由已写进文档** ——
射线是"原生没有世界、`VoxelShape.raycast` 依赖 `getCoordIndex` 的两个子类实现、收益面小一个量级"；
`getOtherEntities` 是"谓词留 Java 已满足、叶子候选数通常个位数、拷贝成本 > 过滤成本"。
**"写清为什么不做"是可以接受的交付**，比做一个不可靠的版本好。

**它自己指出的一个结构性缺口（我给裁决）**：`cava/ffm/**` 不在它的授权路径，
所以三个新符号**无法登记进 `CavaBindings.REQUIRED_SYMBOLS`**，本轮只能自己 `libraryLookup`。
**这确实是个缺口** —— 缺了 `cava_open` 的 fail-closed 保护。但既然本核**决定不上线**，
**这个缺口现在不需要补**；等哪天要开这个核，再连同 ABI 提案一起并进 CavaBindings。

### ✅ P2 `live` 接管达成（2026-09-22）—— 以及它带来的一个**方法论收获**

**结果**：`-Dcava.entity.move=live` 真接管，**144158 次接管、fallback=0、errors=0、overflow=0**。

**三条证据的形式值得单独记，因为它们比"通过"更强：**

1. **`vanillaMove=n/a`** —— 原版 `move` 方法体**一次都没走到 RETURN**（RETURN 注入只计数）。
   ⇒ **接管不是"计数器动了一下"，而是原版那段代码真的没执行。** 这是比"计数 +1"强得多的证据形态。
2. **live vs shadow 差异为零**：`-Dcava.entity.move.verify=true` 下每次接管**再调一次原版私有求解并逐位比对**
   ⇒ **`liveVerified=96220, liveVerifyMismatch=0`**。
   它同时说明：**跨 run 比实体 dump 不成立** —— 实测两次**纯原版 off vs off** 就有 10~16/120 行不同。
3. **可证伪的金丝雀**：`-Dcava.entity.move.canary=skip-setposition` ⇒ **116/116 实体停在出生点、Y 恒 -59.0**
   （对照 beh-live 已落到 -60 并滑行）。⇒ **这个测试有能力失败**，所以它的"通过"有意义。

> **第 3 条是本次最值得推广的做法**：金丝雀不能只证明"我跑了"，要**故意破坏一步、证明结果真的变了**。
> 这与 P1 那次"把条件改回 `&& flag` 后恰好 5 行变红"是同一个思路 —— **能红的测试才是测试。**

**重构没有削弱已有能力**：shadow 144147 次比对 0 不一致；单元 **198 tests / 0 failures**。

**性能仍然净亏（诚实）**：末桶 原版 **1339 ns** vs live **3200 ns** ⇒ **净亏 ≈ +1.86 µs/次**。
瓶颈在 **Java 侧组 refs（1451 ns）**，不在内核（768 ns）。唯一变快的地方：诊断流水关掉后 15-18µs → 3.2µs。
**平均 refs 只有 1.55，不可外推。**

### ★ "预打包输入"这种方法在**有中途状态**的方法上必然错（2026-09-22，P2 live 轮）

事件回放（event replay）本来是这套设计里最漂亮的一环：**原生只算几何、不产生副作用，
把"接触到的方块与命中类型"写成事件流；Java 侧按原版顺序执行虚方法。**
这样即使别的 mod 覆写了方块行为，语义也不变。

**但 P2 的接线暴露了它的一个结构性缺陷**，值得单独记下来因为它是**方法层面的、不是实现层面的**：

`EventReplay` 的输入是**一次性预打包**的 `MoveInputs` record。而 `Entity.move` 里有**三处输入只有回放中途才成立**：

| 输入 | 字节码偏移 | 为什么预打包一定错 |
| --- | --- | --- |
| `getLandingPos()` | 416 | 它在 `setOnGround`（**412**）**之后**才被调用，而 `setOnGround` 会改写 `supportingBlockPos` ⇒ 提前取到的是**旧值** |
| `getSteppingPos()` | 626 | 在 moveEffect 分支**内部**才求值 |
| 碰撞盒 / `stepSoundBranch` | 218 / 708–725 | 扫的是 `setPosition`（**218**）**之后**的盒子；且 `stepSoundBranch` 依赖**回放中途才写入**的 `distanceTraveled` |

**结论（可推广的规则）**：
> **只要被回放的方法内部会改写它自己的输入所依赖的状态，"提前打包"就必然拿到错的值。**
> 正确形态是**拉取式**：回放走到某一步时**按原版顺序向 Java 侧要**那个输入 —— **只有按原版顺序拉取，
> 才能读到与原版同一时刻的值。**

**这条不是"实现没写好"，而是把"预打包"这个范式用错了地方。** 同理适用于任何后续要接管的方法：
**先问"这个方法内部会不会改写我打算提前打包的那些量"。**

**接线流的处置值得记一笔**：它**没有**为了交差而让 `live` 走一个"近似实现"，
而是让 `-Dcava.entity.move=live` **显式拒绝 + 一行 INFO**，并在文档里写清前置条件。
**"能拒绝"比"能跑一个错的"有价值。**（正交的正面例子：`shadow` 模式用**同一份求解器**算了 941734 次、
与原版逐位 0 不一致 —— 那证明的是**求解正确**，不是**已经替换**。这两件事必须分开说。）

### ★ 契约 2.3 预言的弱点**真的咬人了**（2026-09-22，P2 接线轮发现）

我在契约 2.3 里写过一条"已知且可接受的弱点"：`layout_hash` 公式**不区分形状相同的结构体**，
所以 `CavaPathNode` 与 `CavaCollisionBox` 的 hash 相同（都是 `0x250ECBE1`），
并断言"这不构成安全漏洞，因为真正的护栏是逐字段全表比对"。

**那个断言是对的，但我低估了它的影响面。** P2 冻结后新增的 `CavaShapeRecord`
（连续 8 个 4 字节字段）与 `CavaPathNode`（同样连续 8 个 4 字节字段）**hash 完全相同 = `0x0DCFFE65`**。
而 `cava.ffm.LayoutCheck` 当时是用 **(size, field_count) 找第一个匹配的结构体** —— 于是它把
`CavaShapeRecord` 匹配成了 `CavaPathNode`，**逐字段比对必然失败** ⇒ `cava_open` 返回 `CAVA_ERR_LAYOUT`
⇒ **整条原生路径不可用**。

**最要命的是它的表现**：`PathfindAbiTest` 作为"需要原生库"的测试**整类 skip**，
于是 **构建仍然是绿的** —— 又一次"绿 ≠ 测过"。

**修复**：`LayoutCheck` 改成**按下标一一对应**（两侧登记顺序本来就相同），不再靠 (size, field_count) 查找。

> **教训（已写进契约 2.3 的同一处）**：**"哈希碰撞只是粗筛失效"这句话不够** ——
> 真正的问题是**任何"用哈希或形状去反查结构体身份"的代码都会因此错配**。
> 所以：**结构体身份只能用下标/名字，绝不能用 (size, field_count) 或 hash 反查。**
> 同时这也解释了为什么 P0 的 `CavaPathNode`/`CavaCollisionBox` 同值从未出事 —— 当时没有反查代码。

### P2 接线轮的实际成果（prompts/05 第 1 核）

| 项 | 实测 |
| --- | --- |
| 原生入口 | `native/src/entity/cava_entity_abi.cpp`；`cava_layout_report` entries=**14** sum=**0x1C12265E** |
| 单元（真实 DLL） | CVEM 向量灌进 `cava_resolve_move`：**258 例（82 例带事件）0 不一致** |
| **真实服务端** | `nativeCalls=950000 nativeOk=950000 errors=0 overflow=0`；**与原版私有 `adjustMovementForCollisions(Vec3d)` 逐位比对 941734 次，0 不一致** |
| 形状表 | 26644 状态 / 21633 有形状 / 208 种不同 `VoxelSet` / 163018 double / 22518 uint64；构建 13.7ms、上传 1.03ms |
| 日志噪声 | **0 条 `cava/entity` ERROR/WARN** |

**但 `live`（整段替换 `Entity.move`）本轮未交付，且原因是结构性的**：
`EventReplay` 的输入是**一次性预打包**的 record，而 `move` 里有三处输入**只有回放中途才成立** ——
`getLandingPos()`(416) 在 `setOnGround`(412) **之后**、`getSteppingPos()`(626) 在 moveEffect 分支内、
`checkBlockCollision` 扫的是 `setPosition`(218) **之后**的盒子且 `stepSoundBranch` 依赖回放中途写入的 `distanceTraveled`。
⇒ **真接管要求把回放改成"拉取式"**。`-Dcava.entity.move=live` 现在**显式拒绝 + 一行 INFO**，**不静默走近似实现**。

> **所以"原生接管了实体移动"这句话本轮不成立。** 成立的是"**链路可运行 + 结果与原版逐位相同**"。
> 这个区分很重要：943 万次比对证明的是**求解正确**，不是**已经替换**。

**性能（诚实结论，当前接线是净亏）**：同一次调用同一份输入的 A/B（941734 次样本）——
组 refs 1753 ns / 原生调用 761 ns / 原版 532 ns ⇒ **慢约 1.98 µs/次**。
瓶颈**在 Java 侧不在内核**；且本次观测平均 `refs` 只有 **1.37**（多数是空形状表，原版几乎免费），
**不能外推到碰撞密集负载**。未做预热分段。

**冻结 ABI 的一条能力边界**：`cava_resolve_move` 只有**一份** `refs[]`，
而原版台阶分支会用**不同的 `box.stretch` 重跑 `getBlockCollisions` 最多 4 次**。
本轮因此恒传 `step_height=0` 并在 Java 侧用原版判据预测台阶分支（命中率 0.87%），会进就丢掉原生结果改调原版。
⇒ **要让原生吃下台阶分支，ABI 需要"每趟一份形状列表"。** 这是已知边界，不是 bug。

### 差分测试的三层结果（prompts/03，2026-09-22）

| 层 | 命令 | 结果 |
| --- | --- | --- |
| 单元层 | `tools/parity-unit.ps1` | **ZERO DIFF over 10000 cases**（**走完整 Java→FFM→C ABI**）+ C++ 全字段 10000/0 + 负控制能红 |
| 场景层 | `tools/parity-diff.ps1 -Ticks 600` | **方块层 `w` 逐 tick 零差异**（同配置两次、native on/off **都零**，含红石线功率/活塞/比较器状态） |
| 整服层 | `tools/parity-invariants.ps1 -Ticks 1200 -Native on` | **PASS**（1855 tick 实时、MSPT 0.4ms、实时 TPS 20.56、实体 59、无 ERROR 级日志、优雅停服） |

**实体/路径层仍未通过，但已定位且与 native 无关**：实体 id 计数器在不同运行间**整体平移**（同一只僵尸 a=#15 / b=#29），
且 mob 的寻路时机由 AI 随机数决定 ⇒ `p` 在 515/600 tick 不同。
**门禁按设计 `exit=3` 拒绝给 on/off 下结论** —— 这个处置是对的：**门禁能拒绝给结论，比给一个错结论有价值。**

#### ★ 单元层立刻抓到一个"只有走 ABI 才看得见"的真 bug（**本轮最有价值的一处**）

第一次走完整 ABI 就出现 **126 处差异**，根因**不在内核而在喂入**：
Java 把 `CAN_SWIM` 填到了 caps 的 `1 << 2`（= `CAVA_NAV_CAN_FLOAT`）而不是 `1 << 6`。

**为什么这件事重要**：C++ 那条腿**永远看不到它** —— 它直接构造 `WorldView`/`MobProfile`，绕过了 Java 的填值。
这正是 **ABI 作为"契约边界"的价值**：两个独立实现必须就同一个位布局达成一致，而**只有跨过边界才能验证这件事**。

> 这与 `CAVA_PF_*` 那次是**同一类 bug 的第四次实例**（产物与它的记账/填值不一致），
> 但这次**在合入前就被差分抓住了** —— 说明"两层独立实现 + 强制比对"这套机制是有效的。

#### 两条对既有文档的更正（都是实测）

1. **`native/build/mingw/cava_pathfind_vectors.exe` 是陈旧产物**（14:25，当时 `cava_pathfind` 还返回 `CAVA_ERR_UNIMPLEMENTED`）。
   拿它的"10000 组已通过"**是错的**。现在每次都**现场重编译**，不信任仓库里任何旧 exe。
   > 这是"旧二进制造成假结论"的**第三次**实例，已固化成纪律。
2. 冷启动噪声之外，场景层第一轮的非确定性**全部是"跑法"问题**：采样器早于 RCON 布场就开始落盘；
   世界里有**没落定的落沙**。修法：场景与 `tick sprint` 都移进 `#minecraft:load`；
   base 快照先 `chunky` 预生成 + 跑热 1200 tick 再保存。修完后 `w` 逐 tick 零差异。

#### captain 特别要的那一问：**ServerCore 被跳过是否可观测 → 单目标下不可观测**

`javap` 实测 `PathFinderMixin` = **3 个 @Redirect**（`Set.stream` / `Collectors.toMap` / `Stream.collect`）
+ **2 个 @ModifyVariable**（换 `Object2ObjectOpenHashMap`、换 `HashSet` 容量）—— **全是容器/管线替换**。
脚本驱动 **2000 次真实单目标 `findPathToAny`**：

    off  avgNodes=[6.00, 6.00, 6.00]  takeovers=0     errors=0
    on   avgNodes=[6.00, 6.00, 6.00]  takeovers=2000/2000  errors=0

⇒ **单目标下跳过它不可观测**。多目标我们不接管（`targets.size() != 1` 直接返回 null），所以也不存在"跳过"。
**这是 P1 留下的那个"从未差分过"的问题的正式答案。**

### 三条"容易读反 / 容易混淆"的原版真值（P2 实体轮，captain 已独立复核）

这三条都是**运行期不会报错、只会让结果与服务器不一致**的类型，所以每条都配了定点用例，而不是靠随机向量。

#### 1. `MathHelper.approximatelyEquals` 的阈值是 `9.999999747378752E-6`，**不是 1e-7**

```
0: dload_2 ; 1: dload_0 ; 2: dsub ; 3: invokestatic Math.abs:(D)D
6: ldc2_w  #166   // double 9.999999747378752E-6d
9: dcmpg ; 10: ifge 17 ; 13: iconst_1 ; 18: ireturn
```

**最容易犯的错**：把 `1e-7` 当成这个函数的阈值。**`1e-7` 确实存在于 `Entity.move` 里，但它是
`lengthSquared` 守卫用的另一条常量**，与近似比较无关。两个常量在两个地方、都有用，混起来不会报错。

#### 2. `verticalCollision` 用裸 `!=`，只有 x/z 走近似比较

`Entity.move` 里 `verticalCollision` 的判定是 `dcmpl/ifeq`（裸不等），而 x/z 用的是
`approximatelyEquals`。**后果**：当 `movement` 与 `adjusted` 相差 `1e-9` 时
—— **`horizontalCollision = false`、`verticalCollision = true`**。按"三个轴都用近似比较"实现就会错。

#### 3. `Vec3d.equals` / `Box.equals` 用的是 `Double.compare` ⇒ **`-0.0` 不等于 `0.0`**

```
21: getfield x:D ; 29: invokestatic Double.compare:(DD)I ; 32: ifeq 37 ; 35: iconst_0 ; 36: ireturn
```

**这条直接决定 VMP 的零位移短路**：VMP 的条件是 `movement.equals(Vec3d.ZERO)`，而 `Vec3d.ZERO` 的分量是 `+0.0`。
所以**一个分量为 `-0.0` 的 movement 不会命中短路**。用 `== 0.0` 或"数学上等于零"来实现，
会比现服务器**多跳过**一批移动 —— 逐 tick 差分必爆，而且极难定位。

> **共同教训**：这三条都不是"读错跳转方向"，而是**"两个看起来一样的常量/判定，实际不是一回事"**。
> 与 P1 那次 `!flag5` 是同一类风险的不同形态。**防御手段也一样：写死调用序列的定点用例**，
> 因为随机向量是参照实现自己产的，参照实现错了它跟着错。

### 一条被证伪的担忧（记录下来免得后人重复投入）

注入流担心「档案/区域是每句柄一份可变状态，而寻路跑在工作线程上 ⇒ 只能加锁，并行度=1」，
并建议做"每线程一句柄"或"档案改成入参"的 ABI 改造。

**captain 用字节码核过：这个前提不成立。**
`EntityNavigation.findPathToAny` 是**直接 `invokevirtual`** 调 `PathNodeNavigator.findPathToAny`，
整条调用链上**没有任何 executor 交接** ⇒ **原版寻路是同步跑在调用（主）线程上的**。
所以那把锁是**防御性**的，不是吞吐瓶颈；**不做 ABI 改造**（拿高风险变更去换一个不存在的问题）。
新增显式约束：**"原生路径假定主线程调用"** —— 将来若有 mod 把寻路挪到工作线程，这条门禁要重评。

### 已知且可接受的弱点
`layout_hash` 公式**不区分字段数/形状相同的结构体**：`CavaPathNode` 与 `CavaCollisionBox` 的
hash 都是 `0x250ECBE1`。所以**哈希只做"整体漂移"的粗筛**，真正的护栏是**逐字段全表比对**。
这条已写进契约 2.3。

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