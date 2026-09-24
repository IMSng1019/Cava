# Cava

> **Minecraft 1.20.4 Fabric 服务端 mod**：用 JDK 21 预览版 FFM（java.lang.foreign）调用一个 C++ 原生库，
> 把**生物寻路 / 实体开销 / 红石**三个子系统的内层循环搬到原生侧。硬约束是「**与同一套整合包、native 关闭时逐 tick 一致**」——
> 语义基准是整合包，不是原版。

**状态：已定论（2026-09-24）。**

> ## ★ 结论
>
> **在这套整合包上，把这三个子系统搬到 C++ 不会让服务器更快。**
> 三个子系统全部**不接管**（两个实测净亏、一个让位），**默认配置与不装 Cava 行为一致** ——
> 原生库只负责加载、布局自检和一次启动金丝雀探测，**没有任何游戏逻辑被原生接管**。
>
> | 子系统 | 实测结论 | 关键数字 |
> | --- | --- | --- |
> | 生物寻路 | **净亏 ⇒ 不接管** | 真实 AI 负载 **−14.75 / −14.71 µs/tick**（成对实测，两次差 0.2%；7 条腿符号全为负） |
> | 实体开销（碰撞/推挤） | **净亏 ⇒ 不接管** | core1 **+1.86 µs/次**、core2 broadphase **+6.8 µs/次**；且这套包的场景里推挤调用数为 **0** |
> | 红石 | **让位（0% 加速）** | 接管点是**死代码**：fastRedstoneDust 开启时 RedstoneWireBlock.update 的三个（全部）调用点都被 Carpet @Redirect 走了 |
>
> **三条根因**（换任何项目都适用，见下文「可复用判据」）：
>
> 1. **活儿太小**：这套整合包里怪物每次寻路只走 **7 格**（p50；p90=10），寻路总耗时 **34–45 µs/tick ≈ 一个 tick 的 7–9%** ——
>    即使把寻路整块变成免费，收益上限也就这么多。合成的大场景（128–503 节点）确实快 2～3 倍，**但真实负载里没有那种场景**。
> 2. **数据要翻译**：寻路的输入是**世界的方块状态**（Java 对象/调色板容器），C++ 读不了，每次都得翻译成扁平表递过去。
>    实测推送占原生每次耗时的 **28–30%**；而即使把这笔**整块抹零**（常驻镜像的不可达上界），四条腿仍全部 ≤ 0 ——
>    因为真正的账是「**回退 = 白付一次原生再跑一次 Java**」。
> 3. **包里已经有人做过**：Lithium / ServerCore / Carpet / VMP / FerriteCore 全在包里，**好摘的果子早被 Java 侧摘走了**。
>    最好的反例是隔壁 EntityCollisionOptimizer（同一套 FFM+C++ 路线）：它在密集实体场景是**数倍级**收益 ——
>    差别不在语言，在**那块活儿占 tick 多少、数据好不好交、能不能一次调用干一整批**。

---

## 这个项目的价值

速度收益是 0，但产出不是 0。按重要性排序：

1. **一套真的能抓出 bug 的验收方法**（本项目最有价值的产出）。
   它不是「跑绿了」，而是：**native 开/关两条腿逐字段比对** + **故意改坏必须变红** + **比对字段与回执字段机械对拍**。
   证据：这套方法抓到了 **3 个单腿测试永远看不到的真缺陷** ——
   ①**怪物穿墙**（镜像没有失效源，native 路径笔直穿过 8 格实心石头）；
   ②**路径被截断**（窗口 = 起点终点包围盒 + 4，最优路径绕出去就停在墙前：64 节点 vs 128 节点）；
   ③**一个「从来没被比过」的字段**（Path.reachesTarget 填反 + 比对脚本键名笔误 ⇒ 之前的「逐字段一致」是假的）。
   详见 docs/CAVA-gates.md 门禁 #8。
2. **一个被证明逐位一致的原生内核 + 冻结 ABI 的完整机制**。
   14 个结构体、layout_hash_sum = 0x1C12265E、任何一侧漂移都 **fail-closed**（cava_open 直接拒绝）；
   跨编译器逐位一致：**MSVC /O2 /fp:strict 与 GCC 在 15456 行 + - * / sqrt 上零数值位差异**；
   另有 5 平台构建矩阵、数值一致性套件、编译开关反查。
3. **这套整合包真实负载的硬数据**（以前全是「以为」）：
   寻路距离 p50=7 / 节点预算恒 560 / 每 tick 0.22 次寻路 / 寻路占 tick 7–9%；
   区块生成与区块管理 **~60%**、方块 tick **36.3%**（红石类只是其中 AbstractBlockState 回调查 16% 的一部分）、自然刷怪 **21.2%**。
4. **一张「别往这里投钱」的地图**，以及可复用的负面结论 ——
   和「P2 broadphase 技术成功但决定不上线」一样，**知道不该做什么，和知道该做什么一样值钱**。
5. **工程基建**（都能独立复用）：ABI 坏输入 fuzz 驱动（guard page + 哨兵）、真崩 hs_err 崩溃取证链（按**模块**判定归属）、
   熔断 / 看门狗 / 一键回滚、5 平台标签推导矩阵、**构建期产物指纹**（cava: ARTIFACT … size/sha256）。
6. **一份诚实的未验证清单**（见文末）—— 本项目坚持把「没跑过」和「跑过了」分开写。

### 可复用判据：什么情况下才值得把一段 Minecraft 逻辑搬到 C++

四条**全部**成立才值得动手（本项目的三个目标每一条都踩在反面）：

| 判据 | 说明 |
| --- | --- |
| 1. 那块活儿**占 tick 足够大** | 先量再改。占 5% 的活儿，就算搬到原生快 3 倍，也只省 3%。 |
| 2. 内层是**纯计算**，不是「边遍历边改世界」 | 寻路能搬是因为它在镜像区域上算节点；红石搬不动是因为它每一步都在读写世界、调用可被任意 mod 覆写的方块行为。 |
| 3. 数据**能便宜地交过去**（最好两边读同一份） | 实体状态（几个 double）可以常驻共享、零拷贝；世界方块必须每次翻译。跨界价格是实测的：调用 **14–16 ns**、回调 **26–29 ns** —— 便宜，但乘上「每次方块读取」就是毫秒级。 |
| 4. 能**一次调用干一整批** | ECO 的边界承载「一次完整查询 / 一整轮推挤 / 一次位移」；本项目是「每只怪一次调用」，固定开销全额付、回退时还付两遍。 |

> 反过来说：**C++ 不是加速手段，是「把大块、规整、可批量、数据便宜的计算搬出 JVM」的手段。**
> 目标选错时，语言再快也救不回来。

---

## 现在的默认行为（可以用在生产服上）

| 开关 | 默认值 | 含义 |
| --- | --- | --- |
| cava.native.enabled | true | 允许**加载**原生库（解压 → SHA256 命名 → 布局自检 → cava_open）。加载失败就整体回退纯 Java，不报错、不刷屏。 |
| cava.pathfind.native | false | 寻路原生接管（P1）。**净亏，保持关闭**。 |
| cava.entity.move | off | 实体位移整段接管（P2）。**默认关**。 |
| cava.push.mode | off | 实体推挤 / broadphase（P2 第 2 核）。**默认关**。 |
| 红石 | — | 未实现（让位给 Carpet + Lithium）。 |
| 跨 tick 镜像复用 | false | 需要完整失效源；实测命中率 0.05%，保持关闭。 |

**一键回滚**：-Dcava.native.enabled=false（等价于 config/cava.json 里 "native": {"enabled": false}）。
成功判据与机器校验见 docs/CAVA-hardening-notes.md §5 与 tools/harden-rollback.ps1。

---

## 怎么验证（每条都在本机实跑过）

    $env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'   # 默认那个共享 home 会被别的会话锁住

    .\gradlew.bat test --rerun-tasks --no-daemon          # Java 全量：265 tests / 0 failed / 9 skipped
    .\gradlew.bat build --no-configuration-cache           # 产 jar（内含 natives/windows-x64/cava.dll）

    # 原生：ctest 4/4（selftest 180 passed / 0 failed、pathfind vectors 10000+60 cases 0 不一致）
    $env:PATH = "$PWD\natives\windows-x64;C:\mingw64\bin;$env:PATH"
    & tools\cmake\cmake-3.31.2-windows-x86_64\bin\ctest.exe --test-dir build\native-captain-msvc -C Release

    # ABI 坏输入 fuzz（三份产物，输出缓冲区顶到 guard page）
    pwsh -File native/tests/fuzz/build-fuzz.ps1 -Cases 20000

    # 平台数值一致性套件（逐位 + 编译开关 + ABI 布局；CI 口径带 --expect-rows 是 32 项）
    pwsh -File tools\platform-tagmatrix.ps1
    $env:CAVA_SUITE_LIB = "$PWD\natives\windows-x64\cava.dll"
    & build\platform-captain\cava_platform_suite.exe --golden native\tests\vectors\fp_probe.txt --expect-rows 15456

    # 加固：熔断 / 回滚 / 崩溃取证
    pwsh -File tools/harden-breaker.ps1
    pwsh -File tools/harden-rollback.ps1 -SkipBuild
    pwsh -File tools/crash-probe.ps1                      # 真的把 JVM 打崩三次，产出真 hs_err

    # 性能对比（需要测试服；native on/off 两条腿 + 逐字段比对）
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg off -Native off -AiLoad
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg on  -Native on
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off -OnTag on

**交付物指纹**（Windows x64）：natives/windows-x64/cava.dll = **257536 B**，
sha256 0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D，
导入表**只有 KERNEL32.dll**（MSVC /MT 静态 CRT）；构建期会打印 cava: ARTIFACT … size/sha256。

---

## 文档地图

| 文档 | 内容 |
| --- | --- |
| docs/CAVA-项目笔记.md | **给读者的项目笔记**：整个过程、三次转折、工程教训、如果重来一次 |
| docs/CAVA-gates.md | **门禁台账**（最重要的证据库）：8 条门禁 + P1/P2/P3/P4 的实测结论与事故 |
| docs/CAVA-后续对话提示词.md | 跨会话记忆：状态、决策、未闭合清单 |
| docs/CAVA-工程接口契约.md | ABI 硬纪律、布局自检、线程模型、导出面 |
| docs/CAVA-execution-plan.md | 多代理主计划、文件所有权、风险台账 |
| docs/CAVA-pathfind-perf.md / CAVA-p1-net-notes.md / CAVA-p1-fix-notes.md / CAVA-p1-cross-notes.md | P1 性能与正确性的完整证据链 |
| docs/CAVA-redstone-notes.md | P3 让位决策的字节码级依据 |
| docs/CAVA-hardening-notes.md / CAVA-crash-forensics.md / CAVA-concurrency-notes.md | P4 加固、崩溃取证、并发模型 |
| docs/CAVA-platform-notes.md / CAVA-dev-toolbox.md | 平台矩阵 / 开发工具箱 |
| native/include/cava_abi.h | **ABI 唯一权威定义**（14 个结构体；改它必须两侧同时改并重算 layout_hash_sum） |

---

## 未验证 / 做不到（诚实清单）

- **非 Windows 平台**：本机无 Linux/macOS/ARM 工具链、Docker 守护进程未运行、WSL 枚举被拒。
  5 平台 CI 矩阵**写全了但一次都没跑过**（job 名里都带 [unverified-local]）。
- **ASan / UBSan / TSan**：本机跑不起来（只在 CI 里排了 ASan/UBSan）。
- **7 天连续运行**：没有 7×24 环境。判据已写好：unavailableCalls == 0 且 offThreadCalls == 0 且 tripped == false。
- **真实生产服的 carpet.conf**：从未见到该文件，「已开启 fastRedstoneDust」一直按用户说明记录。
- **没有在真实服务端上让 Cava 自己崩过**：崩溃取证的正向判定靠「同名模块替换」构造。
- **MSVC 产物**：跑过真实服务端冒烟与全套数值/布局/fuzz 验证，但未长期运行。

## 许可

Cava 以 **CC0 1.0** 发布（见 LICENSE）。本仓库引用的第三方算法/结论均标注来源；
Carpet 为 MIT、Lithium 为 LGPLv3（**未采纳**其算法移植，见 docs/CAVA-redstone-notes.md §4）。
