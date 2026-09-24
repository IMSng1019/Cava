# Cava P1-CROSS：跨 tick 复用这一注的判决（命中率 / 跨 tick 失效 / 净收益）

> 作者：P1-CROSS 流（响应 captain 裁决 **R7**：用户选择先赌"跨 tick 复用"这一注，而不是立刻收口）。
> 本文件**只记录实跑**：每条结论都带实跑命令 + 回执原文 + 产物哈希；没跑到的写在 §8。
> 私有测试服：`testbed\perf-cross`（**25700/25701**；从 `perf-net` 复制，世界每条腿从
> `testbed\parity\snapshots\parity-base` 恢复）。**绝不使用** `testbed\gate-preview`。
> 与 P1-NET 的关系：**同一套配对方法**（`-Dcava.pathfind.diagnostic.compareAll=true`：同一次调用把
> Java 与原生各跑一遍），所以本文件的每 tick 净收益**与 `−14.75 µs/tick` 直接可比**。

---

## 0. 结论速览

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| **① 跨 tick 复用命中率**（任务指定的第一判决线） | **≈0**。跨 tick 复用的前提是"**同一个矩形**再次被求解"：两条 `crosstick=false` 对照腿实测 **1 次 / 1002 次调用 = 0.0998%**；四条腿（1972 次调用）合计 **1 次**（0.05%）；**同 tick 复用 0 次** | §4 |
| 真实 AI 负载下镜像复用整体命中率 | **0 / 1972 = 0%**：每一次原生调用都**冷推一个新窗口**（`mirrorPushes == nativeCalls`：499/503/497/473，四条腿逐一相等） | §4 |
| **② 跨 tick 失效正确性** | **成立**：改窗口内方块 → 推进 **2 / 6 / 21 / 101** 个**真实 tick** → 再求解**看到新地形**（`b == c != a`），四条 `write` 臂全 **PASS** | §5.3 |
| 可证伪对照（关掉失效钩子） | **四条全部变红**：`-Dcava.mirror.invalidation=false` ⇒ `no-invalidation` 是四轮的共同项，其中两轮**连旧路径都原样交回**（`stale-result(B==A)`），另两轮的 `b` 与 Java 参考不同；同腿的 no-edit 臂也全 RED（跨 tick 复用**真的命中**、`mirrorPushes +0`，交回 **129** 节点的陈旧路径 vs Java 参考 **128**） | §5.4 |
| 缺失的失效源 | 区段卸载/重载（身份表按**区段实例**建 ⇒ 重载后是新实例，**静默漏失效**）、绕过 `ChunkSection.setBlockState` 的直写、区块加载路径；复用路径**不复查** `isReady` | §5.5 |
| **③ 净收益**（配对口径） | **四条腿全为负**：**−25.64 / −15.38 / −17.41 / −11.52 µs/tick**（crosstick 关 vs 开，2400 tick 窗口） | §6.1 |
| 推送成本的**上界**实验（把推送整块抹零） | **四条腿仍然 ≤ 0**：−14.63 / −7.02 / −7.09 / −2.51 µs/tick ⇒ **即使 100% 命中也翻不正** | §6.3 |
| 原生每次调用的拆分 | 原生 135.4–186.3 µs = **镜像推送 39.89–52.97 µs**（28–30%）+ 档案上传/请求填充 29–41 µs + `cava_pathfind` 36.6–91.7 µs | §6.2 |
| **判决** | **这一注输了**（命中率 0.1% ≪ 50%），而且是**结构性输**（推送抹零也翻不正）。默认值保持 **false**，改默认由 captain 拍板 | §9 |

**一句话**：跨 tick 复用的前提在真实 AI 负载上**不成立** —— 1972 次调用里"同一个矩形再次被求解"只出现
**1 次**（0.05%），复用命中 **0 次**；**并且**即使把镜像推送成本整块抹掉（不可达上界），四条腿的净收益
仍然 **≤ 0**，真正压着符号的是**回退白付**（20–40 ms / 2400 tick）。⇒ **R7 这一注判负**；
剩下的路是 **R1（窗口策略 / ABI 信号）**，不是推送。

---

## 1. 这一轮要回答什么（以及判决线写在实验之前）

R7 的实验设计（`docs/CAVA-gates.md` §8.6.1，commit `69b6ad3`）与本轮的判决线：

1. **先量命中率**：跨 tick 复用要求"同一个矩形再次被求解"，而真实负载里每只怪/每个目标算出来的矩形
   都不同 ⇒ **命中率低（<50%）则这一注当场就输**，不必再测性能；
2. **再证跨 tick 失效正确性**：改窗口内方块 → **推进真实 tick** → 再求解必须看到新地形；
   **关掉失效钩子必须变红**；
3. **再按 P1-NET 同一套配对方法测净收益**，与 `−14.75 µs/tick` 直接可比。

本文件按这个顺序给数。**判决线在跑之前就写好了**，不是事后解释。

---

## 2. 测量条件与实跑命令

### 2.1 条件（四条 AI 腿完全相同，只有 crosstick 一个属性不同）

| 项 | 值 |
| --- | --- |
| 测试服 | `testbed\perf-cross`（从 `perf-net` 复制；34 个 mod，同一套整合包） |
| 世界 | 每条腿从 `testbed\parity\snapshots\parity-base` 恢复（固定种子），铺 `long128` 场地 |
| 端口 | 25700（游戏）/ 25701（RCON）；跑前 `netstat` 确认空闲；收尾按 PID 杀 java 再确认 |
| JDK | `C:\Program Files\Java\jdk-21\bin\java.exe`，`-Xms2G -Xmx4G --enable-preview --enable-native-access=ALL-UNNAMED` |
| 负载 | **40 僵尸 + 1 村民**（`-AiLoad`，与 P1-NET 完全同一条腿）；村民 `resistance 9` 免疫伤害 |
| tick | `tick freeze` + `tick sprint`：**预热 400 tick**（不计入）→ `aidist reset` → **测量 2400 tick** |
| 开关 | 两腿都 `-Dcava.native.enabled=true -Dcava.pathfind.native=true -Dcava.pathfind.probe=false` |
| 配对 | 两腿都 `-Dcava.pathfind.diagnostic.compareAll=true`（P1-NET 的决定性方法） |
| 差异 | 实验腿多一个 `-Dcava.mirror.reuse.crosstick=true`（**默认 false，本轮没有改默认值**） |

### 2.2 实跑命令（逐字）

    # 公共参数
    $base = @('-File','J:\mc\Cava\tools\parity-perf-pathfind.ps1','-Root','J:\mc\Cava\testbed\perf-cross',
              '-ServerPort','25700','-RconPort','25701','-Presets','long128','-Modes','reuse')

    # 第 1 批（对照 + 实验）
    & pwsh @base -Leg cross-off-1 -Native on -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -JavaProps '-Dcava.pathfind.diagnostic.compareAll=true'
    & pwsh @base -Leg cross-on-1  -Native on -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -JavaProps '-Dcava.pathfind.diagnostic.compareAll=true,-Dcava.mirror.reuse.crosstick=true'

    # 第 2 批（同一 jar 的独立重复，用于给命中率第二个样本 + 量化腿间噪声）
    & pwsh @base -Leg cross-off-2 -Native on -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -JavaProps '-Dcava.pathfind.diagnostic.compareAll=true'
    & pwsh @base -Leg cross-on-2  -Native on -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -JavaProps '-Dcava.pathfind.diagnostic.compareAll=true,-Dcava.mirror.reuse.crosstick=true'

    # 第 3 批（跨 tick 失效实测：arm → **tick sprint N** → check）
    & pwsh @base -Leg cross-xtick-on     -Native on -SkipPerf -XTick -XTickArms 'write,nowrite' -XTickGaps '1,5,20,100' -JavaProps '-Dcava.mirror.reuse.crosstick=true'
    & pwsh @base -Leg cross-xtick-nohook -Native on -SkipPerf -XTick -XTickArms 'write,nowrite' -XTickGaps '1,5,20,100' -JavaProps '-Dcava.mirror.reuse.crosstick=true,-Dcava.mirror.invalidation=false'

    # 构建（唯一一次；六条腿全部用同一个 jar）
    $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; .\gradlew.bat build --no-daemon --console=plain --no-watch-fs
    → BUILD SUCCESSFUL in 17s；build\test-results 汇总 suites=47 tests=265 failures=0 errors=0 skipped=9

### 2.3 产物哈希（每条腿开始/结束各算一次；脚本的 `#dllStable` 机制复用）

    DLL: J:\mc\Cava\natives\windows-x64\cava.dll
         257536 bytes  sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D（**全程未变**）
    JAR: build\libs\cava-0.1.0.jar
         587168 bytes  sha256=682ED206923A091155DA85E5F035E62BD4BE51ECE8AFEA877409CBB172B7130A
         （**六条腿全部用这一个 jar**；每条腿 `#deployed == #jar`、`#dllStable=True`，逐条见
          `testbed/perf-cross/results/*.txt`）

---

## 3. 这一轮加的仪表（回答"命中率"这个前提）

| 加的东西 | 在哪里 | 回答什么 |
| --- | --- | --- |
| **镜像账本窗口** | `PathfindHook.mirrorReport()`：`mirrorPushes / mirrorReuse / mirrorSameTick / mirrorCrossTick / mirrorCrossBlocked / mirrorInval / mirrorFail / mirrorCells / mirrorPushMs / mirrorPushUs` | **本轮的核心观测量**。reset 时打快照、报告时给**窗口内增量**（与其它账本同口径）。这同时落地 R5 记的待办（`RegionMirror.report()` 在此之前**没有调用点**） |
| **原生耗时拆分** | `PathfindHook.nativeSplitReport()`：`prepMs`（镜像推送 + 档案上传 + arena/请求填充）+ `solveMs`（`cava_pathfind` 本身） | "推送 vs 求解"能不能拆开：**能拆**（§6.2） |
| **推送成本出口** | `RegionMirror.pushNanos() / pushFillNanos() / pushAllocNanos() / pushUploadNanos()` | 被省掉的那些推送"每次本来要花多少钱"（不是平均值，是窗口增量） |
| **`/cava pathfind stats` 接上镜像账本** | `PathfindHook.stats()` += `mirrorReport()` | R5 的可观测性待办（打开跨 tick 复用必须看得见这三个数） |
| **跨 tick 失效实测命令** | `cava pathfind xtick arm <preset> <write\|nowrite>` / `cava pathfind xtick check <preset>` | 把 P1-FIX 的 `invalidate` 从"同 tick"扩成"**推进真实 tick**"（§5.1） |
| **脚本驱动** | `tools/parity-perf-pathfind.ps1` 的 `-XTick -XTickArms -XTickGaps` | arm → `tick sprint N` → check 的自动往返 + 回执落盘 |

---

## 4. ① 命中率：跨 tick 复用的前提在真实 AI 负载上不成立

### 4.1 判据怎么读

`RegionMirror.pushReusingSameTick` 的复用条件是"**同一个矩形** + 同维度 + 没有失效事件 + （同 tick 或
显式打开跨 tick）"。于是三个计数的含义是：

- `mirrorSameTick`：同 tick 复用命中（矩形相同且同一 tick）；
- `mirrorCrossTick`：**跨 tick 复用命中**（矩形相同、不同 tick、开关开着）；
- `mirrorCrossBlocked`：**矩形相同、不同 tick、但开关关着**（= 这个开关打开后"能多省多少次推送"的**上界**）——
  这个数在 `crosstick=false` 的对照腿上是**直接可测的**，不需要打开开关就能知道命中率。

⇒ **命中率 = mirrorCrossBlocked / 调用数**（对照腿），或 **mirrorCrossTick / 调用数**（实验腿）。

### 4.2 四条腿的原文（镜像账本段，逐字抄自 `results/*.txt`）

    cross-off-1（crosstick=false）：mirrorPushes=499 mirrorReuse=0 mirrorSameTick=0 mirrorCrossTick=0 mirrorCrossBlocked=0 mirrorInval=0 mirrorFail=0 mirrorCells=1362027 mirrorPushMs=26.430 mirrorPushUs=52.97 mirrorCrossTickEnabled=false mirrorInvalidationEnabled=true hookSectionHits=21 hookInWindow=0 hookInvalidations=0 prepMs=47.035 solveMs=45.754
    cross-off-2（crosstick=false）：mirrorPushes=503 mirrorReuse=0 mirrorSameTick=0 mirrorCrossTick=0 mirrorCrossBlocked=1 mirrorInval=0 mirrorFail=0 mirrorCells=1311369 mirrorPushMs=20.063 mirrorPushUs=39.89 mirrorCrossTickEnabled=false mirrorInvalidationEnabled=true hookSectionHits=29 hookInWindow=0 hookInvalidations=0 prepMs=31.337 solveMs=36.631
    cross-on-1 （crosstick=true ）：mirrorPushes=497 mirrorReuse=0 mirrorSameTick=0 mirrorCrossTick=0 mirrorCrossBlocked=0 mirrorInval=12 mirrorFail=0 mirrorCells=1262798 mirrorPushMs=24.771 mirrorPushUs=49.84 mirrorCrossTickEnabled=true mirrorInvalidationEnabled=true hookSectionHits=49 hookInWindow=12 hookInvalidations=12 prepMs=43.988 solveMs=39.569
    cross-on-2 （crosstick=true ）：mirrorPushes=473 mirrorReuse=0 mirrorSameTick=0 mirrorCrossTick=0 mirrorCrossBlocked=0 mirrorInval=0 mirrorFail=0 mirrorCells=1248359 mirrorPushMs=21.626 mirrorPushUs=45.72 mirrorCrossTickEnabled=true mirrorInvalidationEnabled=true hookSectionHits=0 hookInWindow=0 hookInvalidations=0 prepMs=36.204 solveMs=38.713

四条腿的"每次调用"部分（原文，用于确认 `native == calls`）：

    cross-off-1: [cava/pathfind] AIDIST calls=499 java=215 native=499 takeover=284 fallback=215 gated=0 unaccounted=0 offThread=0
    cross-off-2: [cava/pathfind] AIDIST calls=503 java=151 native=503 takeover=352 fallback=151 gated=0 unaccounted=0 offThread=0
    cross-on-1 : [cava/pathfind] AIDIST calls=497 java=151 native=497 takeover=346 fallback=151 gated=0 unaccounted=0 offThread=0
    cross-on-2 : [cava/pathfind] AIDIST calls=473 java=132 native=473 takeover=341 fallback=132 gated=0 unaccounted=0 offThread=0

### 4.3 ★★ 判决数字

| 腿 | crosstick | 调用 | **推送** | 复用(同tick) | **复用(跨tick)** | **跨tick被拒** | 失效 | **命中率** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cross-off-1 | false | 499 | **499** | 0 | 0 | **0** | 0 | **0 / 499 = 0%** |
| cross-off-2 | false | 503 | **503** | 0 | 0 | **1** | 0 | **1 / 503 = 0.199%** |
| cross-on-1 | true | 497 | **497** | 0 | **0** | 0 | 12 | **0 / 497 = 0%** |
| cross-on-2 | true | 473 | **473** | 0 | **0** | 0 | 0 | **0 / 473 = 0%** |
| **合计** | — | **1972** | **1972** | **0** | **0** | **1** | 12 | **1 / 1972 = 0.051%** |

**三条读法（都不需要额外假设）**：

1. **两条对照腿（crosstick=false）是这条判决的干净样本**：它们那个窗口里镜像失效次数是 **0**，
   所以 `mirrorCrossBlocked` 不会被"失效事件"掩盖 —— **1972 次里"同一个矩形再次被求解"只发生了 1 次**
   （`cross-off-2`），**同 tick 一次都没有**。命中率 **0.0998%**（1/1002），判决线是 **<50% 即输**。
2. **实验腿独立复核**：把开关打开之后 `mirrorCrossTick` 仍然是 **0**（497、473 次调用），
   `mirrorPushes` 仍然等于调用数（497、473）⇒ 开关打开**没有省下任何一次推送**。
3. **这不是"冷推送"能救的形态**：真实 AI 的矩形由"当次起点 + 当次目标 + 体型"决定，而生物在两次调用之间
   会移动/换目标 ⇒ 每次调用的矩形都不一样。唯一那次命中来自"某只怪在同一格上对同一目标又解了一次"。

**⇒ 第一判决：这一注当场就输了。** 而且它输在**前提**上：不是"跨 tick 复用有没有被安全修复挡住"，
而是"**根本没有第二个相同的矩形**"。P1-NET 那句"安全修复把复用收紧成同 tick 之后真实负载下几乎每次都重推"
在这一轮被量化成"**每一次**都重推"（1972/1972）。

> 补一条反向核对：本轮的 `sameTick` 也是 0 —— 也就是说**镜像复用（同 tick + 跨 tick）在这份负载上
> 一次都没命中过**。P1-NET 那个"合成场景 2.16–2.76x"的复用命中，靠的是同一个 preset 起点/终点被反复求解，
> 真实 AI 里不存在这种形态。

---

## 5. ② 跨 tick 失效：PASS 与"关掉钩子必须变红"

### 5.1 命令做的事（`cava pathfind xtick`）

```
cava pathfind xtick arm   <preset> <write|nowrite>   # A：解一次 + 记指纹/tick/计数；（write 时）把 A 路径上
                                                     #    第一个非 BLOCKED 节点改成石头（走主钩子那条写路径）
（脚本在两条命令之间跑 tick freeze + tick sprint N ⇒ **推进 N 个真实 tick**）
cava pathfind xtick check <preset>                   # B：再解一次 → C：临时关掉 native 再解一次（Java 参考）
                                                     #    → 还原方块 → 判定
```

为什么**必须**拆成两条命令：`ticksElapsed` 只能由"世界真的 tick 过"产生，而 `tick freeze` 下
"同 tick"与"跨 tick"分不开（P1-FIX §9 第 7 条记的就是这个缺口）。**`write` 臂**测"改了方块必须看到"；
**`nowrite` 臂**测"什么都没改时复用真的会命中"（否则 write 臂即使全程重推也会 PASS = 测试是空的）。

判定：`write` 臂 PASS 需要 ①钩子窗口内命中 +1 ②失效 +1 ③`b != a` ④`b == c`；
`nowrite` 臂 PASS 需要 ①跨 tick 复用命中 +1 ②`b == a` ③`b == c`。

### 5.2 实跑命令

    & pwsh @base -Leg cross-xtick-on     -Native on -SkipPerf -XTick -XTickArms 'write,nowrite' -XTickGaps '1,5,20,100' -JavaProps '-Dcava.mirror.reuse.crosstick=true'
    & pwsh @base -Leg cross-xtick-nohook -Native on -SkipPerf -XTick -XTickArms 'write,nowrite' -XTickGaps '1,5,20,100' -JavaProps '-Dcava.mirror.reuse.crosstick=true,-Dcava.mirror.invalidation=false'

### 5.3 腿 3（`cross-xtick-on`：crosstick=true + 失效钩子开）—— `write` 臂全 PASS

    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=PASS tickA=3293 tickB=3295 ticksElapsed=2 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b c=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b hookIn=0->43(+43) invalidations=0->43(+43) mirrorInval=0->43(+43) mirrorPushes=0->2(+2) mirrorReuse=0->0(+0) mirrorSameTick=0->0(+0) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(33,71,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=block.minecraft.air
    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=PASS tickA=3295 tickB=3301 ticksElapsed=6 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b c=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b hookIn=45->52(+7) invalidations=45->52(+7) mirrorInval=45->52(+7) mirrorPushes=2->4(+2) mirrorReuse=0->0(+0) mirrorSameTick=0->0(+0) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(33,71,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=block.minecraft.air
    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=PASS tickA=3301 tickB=3322 ticksElapsed=21 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xfaf910c0bbb5cf35 c=len=128,end=(159,71,0),sig=0xfaf910c0bbb5cf35 hookIn=54->126(+72) invalidations=54->126(+72) mirrorInval=54->126(+72) mirrorPushes=4->6(+2) mirrorReuse=0->0(+0) mirrorSameTick=0->0(+0) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(33,71,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=block.minecraft.air
    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=PASS tickA=3322 tickB=3423 ticksElapsed=101 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b c=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b hookIn=177->193(+16) invalidations=177->193(+16) mirrorInval=177->193(+16) mirrorPushes=6->14(+8) mirrorReuse=0->0(+0) mirrorSameTick=0->0(+0) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(33,71,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=block.minecraft.air

**读法**：四个 gap（1/5/20/100）分别在 `ticksElapsed=2/6/21/101` 之后判定
（1 个 tick sprint 实际推进 2，因为 RCON 的 sprint 命令与下一条命令之间世界又走了一拍 —— 回执里的
`ticksElapsed` 是**实测**值，不是请求值）：
`b`（新地形下的原生结果）与 `c`（Java 参考）**逐字段相同**，与 `a`（改之前的路径）**不同**；
`invalidations +43/+7/+72/+16`、`mirrorPushes +2/+2/+2/+8`（前三轮 = A 与 B 各一次真推送；
第四轮 101 tick 里多出来的 6 次推送说明这个窗口里还有**别的寻路调用**——世界里原有的生物，与本文件 §8 第 1 条
同一个"未逐格归因"的缺口）、`mirrorCrossTick +0`（复用被失效事件合法拒绝）。
**"跨 tick + 改了窗口内方块 ⇒ 看得到新地形"成立。**

### 5.4 ★★ 可证伪对照（腿 4：`-Dcava.mirror.invalidation=false`）—— 必须变红，确实全红

    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=RED(no-invalidation;stale-result(B==A);B!=javaRef;) tickA=3293 tickB=3295 ticksElapsed=2 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0x7acb85719d26c525 c=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b hookIn=0->43(+43) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=0->1(+1) mirrorReuse=0->1(+1) mirrorSameTick=0->0(+0) mirrorCrossTick=0->1(+1) mirrorCrossBlocked=0->0(+0) put=(33,71,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=block.minecraft.air
    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=RED(no-invalidation;stale-result(B==A);B!=javaRef;) tickA=3295 tickB=3301 ticksElapsed=6 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0x7acb85719d26c525 c=len=128,end=(159,71,0),sig=0xd302b1889a7fa31b hookIn=45->52(+7) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=1->1(+0) mirrorReuse=1->3(+2) mirrorSameTick=0->0(+0) mirrorCrossTick=1->3(+2) mirrorCrossBlocked=0->0(+0) put=(33,71,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=block.minecraft.air
    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=RED(no-invalidation;) tickA=3301 tickB=3322 ticksElapsed=21 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xfaf910c0bbb5cf35 c=len=128,end=(159,71,0),sig=0xfaf910c0bbb5cf35 hookIn=54->64(+10) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=1->3(+2) mirrorReuse=3->4(+1) mirrorSameTick=0->0(+0) mirrorCrossTick=3->4(+1) mirrorCrossBlocked=0->0(+0) put=(33,71,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=block.minecraft.air
    [cava/pathfind] XTICK phase=check preset=long128 arm=write verdict=RED(no-invalidation;B!=javaRef;) tickA=3322 tickB=3423 ticksElapsed=101 a=len=128,end=(159,71,0),sig=0xfaf910c0bbb5cf35 b=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab c=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c hookIn=115->173(+58) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=3->6(+3) mirrorReuse=4->5(+1) mirrorSameTick=0->1(+1) mirrorCrossTick=4->4(+0) mirrorCrossBlocked=0->0(+0) put=(33,72,0) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=block.minecraft.air

**`write` 臂：四条全部 RED**，而且**红的原因逐轮不同、共同项是 `no-invalidation`**（逐字读上面四行）：

- `ticksElapsed=2`（gap=1）：`RED(no-invalidation;stale-result(B==A);B!=javaRef;)` —— **原样交回了改方块之前的路径**
  （`b == a`），与 Java 参考（`c`）不同；
- `ticksElapsed=6`（gap=5）：同上（`stale-result(B==A)`）；
- `ticksElapsed=21`（gap=20）：`RED(no-invalidation;)` —— 这一轮 `b == c`（**碰巧**又来了一次真推送，
  见该行 `mirrorPushes +2`），但 `b != a` 说明"改方块没被镜像看见"这件事仍然成立；
- `ticksElapsed=101`（gap=100）：`RED(no-invalidation;B!=javaRef;)` —— 复用交回 **129** 节点的陈旧路径，
  Java 参考是 **128**。

⇒ 关键不是"每一轮都陈旧"（那取决于这一轮有没有别的理由触发真推送），而是**`no-invalidation` 四条全中、
且只要复用真的命中就一定与 Java 参考不一致**：这就是"关掉钩子必须变红"的原文。

同一条腿的 `nowrite` 臂还给出了一条**额外的反面证据**：什么都没改、只推进 tick，跨 tick 复用**真的命中了**
（四轮都是 `mirrorPushes +0`（一次推送都没发生）、`mirrorReuse +2`，其中跨 tick +1 ~ +2 / 同 tick 0 ~ +1，
逐轮见原文），但交回的是**陈旧窗口** ⇒ 与 Java 参考不一样：

    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(B!=javaRef;) tickA=3423 tickB=3425 ticksElapsed=2 a=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab b=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab c=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c hookIn=175->177(+2) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=6->6(+0) mirrorReuse=5->7(+2) mirrorSameTick=1->2(+1) mirrorCrossTick=4->5(+1) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=(none)
    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(B!=javaRef;) tickA=3425 tickB=3431 ticksElapsed=6 a=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab b=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab c=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c hookIn=178->184(+6) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=6->6(+0) mirrorReuse=7->9(+2) mirrorSameTick=2->2(+0) mirrorCrossTick=5->7(+2) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=(none)
    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(B!=javaRef;) tickA=3431 tickB=3452 ticksElapsed=21 a=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab b=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab c=len=128,end=(159,71,0),sig=0x7acb85719d26c525 hookIn=185->206(+21) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=6->6(+0) mirrorReuse=9->11(+2) mirrorSameTick=2->2(+0) mirrorCrossTick=7->9(+2) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=(none)
    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(B!=javaRef;) tickA=3452 tickB=3553 ticksElapsed=101 a=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab b=len=129,end=(159,71,0),sig=0xc7bc7f48f831d5ab c=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c hookIn=206->307(+101) invalidations=0->0(+0) mirrorInval=0->0(+0) mirrorPushes=6->6(+0) mirrorReuse=11->13(+2) mirrorSameTick=2->2(+0) mirrorCrossTick=9->11(+2) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=false restored=(none)

**读法**：`b`（复用旧窗口的原生结果）节点数 **129**，`c`（直接读世界的 Java 参考）**128** ——
差 1 个节点就是"镜像里的世界"与"真实世界"已经不一样了。而且**这不是测试自己改的方块**：
`hookIn` 在 101 个 tick 里涨了 **+101**（= 世界自身每 tick 有约 1 次落在窗口内的方块写入）。
⇒ 在**活 tick** 的世界上，跨 tick 复用的正确性**完全依赖失效源**，没有"反正没人动地形"这种便宜可占。

同一条腿在腿 3（失效钩子**开**）下的同一臂是另一种结果（复用被合法拒绝、真推送、结果与 Java 一致）：

    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(no-cross-tick-reuse(blocked=+0);A!=B;) tickA=3423 tickB=3425 ticksElapsed=2 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c c=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c hookIn=195->197(+2) invalidations=195->197(+2) mirrorInval=195->197(+2) mirrorPushes=14->16(+2) mirrorReuse=0->0(+0) mirrorSameTick=0->0(+0) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=(none)
    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(no-cross-tick-reuse(blocked=+0);A!=B;) tickA=3425 tickB=3431 ticksElapsed=6 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c c=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c hookIn=198->204(+6) invalidations=198->204(+6) mirrorInval=198->204(+6) mirrorPushes=16->18(+2) mirrorReuse=0->0(+0) mirrorSameTick=0->0(+0) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=(none)
    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(no-cross-tick-reuse(blocked=+0);) tickA=3431 tickB=3452 ticksElapsed=21 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0x7acb85719d26c525 c=len=128,end=(159,71,0),sig=0x7acb85719d26c525 hookIn=205->226(+21) invalidations=205->226(+21) mirrorInval=205->226(+21) mirrorPushes=18->21(+3) mirrorReuse=0->0(+0) mirrorSameTick=0->0(+0) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=(none)
    [cava/pathfind] XTICK phase=check preset=long128 arm=nowrite verdict=RED(no-cross-tick-reuse(blocked=+0);A!=B;) tickA=3452 tickB=3553 ticksElapsed=101 a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c c=len=128,end=(159,71,0),sig=0xddf074d1846e0f4c hookIn=226->289(+63) invalidations=226->289(+63) mirrorInval=226->289(+63) mirrorPushes=21->26(+5) mirrorReuse=0->1(+1) mirrorSameTick=0->1(+1) mirrorCrossTick=0->0(+0) mirrorCrossBlocked=0->0(+0) put=(none) mobAtStart=true crossTickEnabled=true invalidationEnabled=true restored=(none)

**读法**：`mirrorReuse +0`、`mirrorCrossTick +0`、`mirrorPushes +2`、`b == c`（新推送 ⇒ 正确），
但 `a != b`（世界自己在两次求解之间变了）⇒ 我的 `nowrite` 判定规则判它 RED(A!=B)，**这是判定规则偏严，
不是缺陷**：`nowrite` 臂想证明的"复用命中且结果正确"这件事，在活 tick 的世界上会被世界自身的写入破坏，
真正干净的证据来自腿 4（`mirrorCrossTick +2` 且 `mirrorPushes +0`）。

### 5.5 还缺哪些失效源（如实登记）与最坏后果

| # | 缺的源 | 机制 | 跨 tick 复用下的后果 |
| --- | --- | --- | --- |
| 1 | **区段卸载/重载** | `onSectionUnloaded` **没有事件源**；而且 `SectionOriginRegistry` 的身份表是 `IdentityHashMap<ChunkSection, 原点>` —— 区块卸载再加载后是**新的 `ChunkSection` 实例**，表里没有它 ⇒ 新实例上的写入既不算"已登记区段命中"，也不算失效（`origins.get(section) == null` 直接 return） | **静默漏失效**：重载后的地形改动镜像永远看不到 |
| 2 | 复用路径不复查"区块还在不在" | `pushReusingSameTick` 复用分支**不调** `reader.isReady(...)`（只有 `push` 调） | 窗口覆盖的区块被卸载后，复用照旧命中 ⇒ 用一份**已不在世界里的**地形求解 |
| 3 | 绕过 `ChunkSection.setBlockState` 的直写 | 别的 mod 直接写 `PalettedContainer`（`sec.getBlockStateContainer().set(...)`）不经过主钩子 | 镜像看不到 ⇒ 陈旧 |
| 4 | 区块加载/生成 | 新加载区块的地形不会进入已发布的窗口（只有下一次 push 才会登记它的区段） | 与 #1 同类：登记表过期 ⇒ 漏失效 |
| 5 | 世界级替换 | 已覆盖：`bind` 换世界/换维度会 `clearLocked() + invalidate()` | 无 |

**最坏情况错成什么样**（不是"性能差一点"，是"行为与原版不一致"）：
原生拿着**旧地形**跑 A\*，把"现在有方块的位置"当空气 ⇒ **路径穿墙**（P1-FIX 实测过同症状：
`collisionNodes=8 firstBadNode=(96,71,0)`，见 `docs/CAVA-p1-fix-notes.md` §5.4 对照 B）；
反向也可能把"现在空的位置"当实心 ⇒ 绕远/找不到路。**现有的 `WindowTruncationGuard` 抓不到这一类**：
它只看几何是否越界 / 是否抵达，**不看地形新旧**。服务端不会崩，但"与 native 关闭时逐 tick 一致"这条
验收语言被破坏 —— 这正是 R4 要求"补上区段卸载失效源**并且**拿到金丝雀证据才允许打开"的原因。

> 本轮**没有**修这些洞（不在本流范围：改"按区块键的脏跟踪"要动镜像的登记结构，
> 而 R4 要求的那条"活 tick 服务器上的金丝雀证据"本轮只做到了"跨 tick 复用会命中并会陈旧"这一面）。

---

## 6. ③ 净收益（与 P1-NET 同一套配对方法）

### 6.1 配对表（`compareAll`：同一次调用把 Java 与原生各跑一遍）

| 腿 | crosstick | 调用 | 接管 | 回退(率) | Σ 参考 Java | Σ 原生 | Σ 回退那次 Java | **pairNet** | **µs/tick** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| cross-off-1 | false | 499 | 284 | 215 (43.1%) | 150.314 ms | 92.951 ms | 118.898 ms | **−61.536 ms** | **−25.64** |
| cross-off-2 | false | 503 | 352 | 151 (30.0%) | 119.201 ms | 68.095 ms | 88.006 ms | **−36.900 ms** | **−15.38** |
| cross-on-1 | true | 497 | 346 | 151 (30.4%) | 124.239 ms | 83.716 ms | 82.299 ms | **−41.776 ms** | **−17.41** |
| cross-on-2 | true | 473 | 341 | 132 (27.9%) | 132.050 ms | 75.055 ms | 84.635 ms | **−27.640 ms** | **−11.52** |
| （P1-NET 决定性两腿，同一方法） | — | 522/498 | 362/334 | 160/164 | — | — | — | −35.405 / −35.309 ms | **−14.75 / −14.71** |

**六条配对腿（本轮 4 + P1-NET 2）全部为负**，范围 **−11.52 ~ −25.64 µs/tick**。

**两条腿的差值不能读成"开关的效应"**：`crosstick` 开/关在这份负载上**行为等价**（§4：复用命中 0 次、
推送次数与调用数一一相等），所以两腿 `pairNet` 的差异全部来自**负载差异**（回退率 27.9%–43.1% 是僵尸 AI
随机性决定的，回退那次 Java 平均 553–641 µs）。这正是 P1-NET §2 那句"跨腿噪声 40–100%，只有配对口径可信"
的又一次实测：**开关的净收益 = 0（结构上），剩下的全是噪声。**

### 6.2 原生每次调用的拆分（"推送 vs 求解"——**拆开了**）

| 腿 | 原生每次 | 镜像推送 | 档案上传+arena/请求填充 | `cava_pathfind` | 推送占比 |
| --- | --- | --- | --- | --- | --- |
| cross-off-1 | 186.3 µs | **52.97 µs** | 41.29 µs | 91.69 µs | 28.4% |
| cross-off-2 | 135.4 µs | **39.89 µs** | 28.84 µs | 87.05 µs | 29.5% |
| cross-on-1 | 168.4 µs | **49.84 µs** | 38.66 µs | 79.62 µs | 29.6% |
| cross-on-2 | 158.7 µs | **45.72 µs** | 29.56 µs | 81.85 µs | 28.8% |

（口径：`mirrorPushMs/mirrorPushes` = 镜像推送本身；`prepMs` = 推送 + 档案上传 + arena/请求填充；
`solveMs` = `cava_pathfind` 那一次跨界调用。三项相加 = `nativeUs_avg`，逐腿对得上。）

⇒ **captain 的假设"冷推送占很大一部分"在成本侧成立**（推送 = 原生的 28–30%，每次调用都真推），
**但"跨 tick 复用能把这笔钱省掉"在负载侧不成立**（§4：命中 0 次）。

每 tick：所有 **499/503/497/473 次调用**（`nativeCalls == calls`）都各付一次推送。

### 6.3 ★★ 上界实验：把"推送成本"整块抹掉，符号会翻正吗？—— **不会**

这是回答"整块世界常驻镜像 + 增量更新值不值得做"的那个数：把 `mirrorPushMs` **整块**从 `pairNet` 里减掉
（= 假设推送成本降到 0，这是任何"常驻镜像/增量更新"方案的**不可达上界**）：

| 腿 | pairNet | − 推送总耗时 | 上界 pairNet | 上界 µs/tick | 符号 |
| --- | --- | --- | --- | --- | --- |
| cross-off-1 | −61.536 ms | 26.430 ms | −35.106 ms | **−14.63** | 仍为负 |
| cross-off-2 | −36.900 ms | 20.063 ms | −16.837 ms | **−7.02** | 仍为负 |
| cross-on-1 | −41.776 ms | 24.771 ms | −17.005 ms | **−7.09** | 仍为负 |
| cross-on-2 | −27.640 ms | 21.626 ms | −6.014 ms | **−2.51** | 仍为负 |
| （P1-NET net5-pair-1，用本轮实测的 ~50 µs/推送折算） | −35.405 ms | ≈ +24.9 ms | ≈ −10.5 ms | ≈ **−4.4** | 仍为负 |

**⇒ 即使跨 tick 复用命中 100%（本轮实测 0.05%），也翻不正。** 亏损的分解（同腿内可分解，逐腿加得上）：

| 腿 | Σ 白付的原生（回退） | Σ 接管调用上的"原生 − Java 参考" | 合计 |
| --- | --- | --- | --- |
| cross-off-1 | 215 × 186.3 = **40.05 ms** | 284 × (186.3 − 110.6) = 21.49 ms | 61.54 ≈ −61.536 ✓ |
| cross-off-2 | 151 × 135.4 = **20.45 ms** | 352 × (135.4 − 88.6) = 16.47 ms | 36.92 ≈ −36.900 ✓ |
| cross-on-1 | 151 × 168.4 = **25.43 ms** | 346 × (168.4 − 121.2) = 16.33 ms | 41.76 ≈ −41.776 ✓ |
| cross-on-2 | 132 × 158.7 = **20.95 ms** | 341 × (158.7 − 139.1) = 6.70 ms | 27.65 ≈ −27.640 ✓ |

⇒ **回退白付 = 8.5–16.7 µs/tick 是主项，接管调用上的原生超额 = 2.8–9.0 µs/tick 是次项**；
镜像推送 = 8.4–11.0 µs/tick。把推送抹零只够把最"轻"的那条腿从 −11.52 推到 −2.51。

---

## 7. 还剩哪条路 + 量级估计

1. **R1（窗口策略 / ABI 信号）—— 唯一能把符号翻正的方向。**
   要消掉的是**回退白付**（8.5–16.7 µs/tick）。把回退率从 28–43% 压到 R2 判据②要求的 **≤5%**，
   等于消掉这笔钱的 75–90% ⇒ **+7 ~ +15 µs/tick**，正好是能把 −11.5 ~ −25.6 翻正的量级。
   代价与前提：需要一个"**窗口够不够**"的 **sound** 信号（内核算过 `expanded_count`，但没有出口），
   那是 captain 的 ABI 决策（14 结构体 / `layout_hash_sum=0x1C12265E`）。
   本轮的旁证：两条路逐节点一致率 `pairSame` = **314/499、361/503、314/497、333/473 = 62.9–71.8%**，
   与 P1-NET 的 66% 一致 ⇒ "原生算对了、只是我们不敢用"的比例很高。
2. **整块世界常驻镜像 + 增量更新（R7 提到的备选）—— 上界收益 8.4–11.0 µs/tick，单独做不改变符号。**
   它要解决的是本文件 §5.5 的两个洞（脏跟踪改按**区块键**而不是区段实例身份；复用路径复查 `isReady`），
   并维护一份常驻副本：本测试服 forceload 484 区块 × 16×16×384 = **4760 万格**，int32 常驻 **190 MB**
   （4-bit 调色板 ≈ 24 MB + 状态表）。**⇒ 只有和路线 1 一起做才有意义**：先有 sound 的窗口信号，
   再有常驻镜像把推送压到接近 0，两项相加才可能 > 0。
3. **把档案上传也省掉** —— 29–41 µs/call ≈ 8 µs/tick 量级，每次必付（`CavaMobProfile` 含实体位姿与
   26 项惩罚表，实体会动，不能缓存）⇒ 同样是 ABI 语义改动，冻结期内不可做。

---

## 8. 没跑 / 做不到（不要当成已完成）

| # | 项 | 状态与原因 |
| --- | --- | --- |
| 1 | **"窗口内世界自身写入"的逐格归因** | 只量到速率（活 tick 下该窗口 0.6–1.0 次/tick，见 §5.4），**没有**把 `RegionMirror.lastInvalidation()` 的坐标接进回执 ⇒ 不知道具体是哪些方块（random tick / 流体 / 重力）。这是一条**如实缺口**，不影响任何判决 |
| 2 | 区段卸载失效源 | **没做**（§5.5 第 1 条）。身份表按 `ChunkSection` 实例建，重载即漏失效 —— 本轮只把它写成"缺什么、最坏错成什么样" |
| 3 | 复用路径的 `isReady` 复查 | **没做**（§5.5 第 2 条）。这是打开跨 tick 复用的**前置条件**之一 |
| 4 | 别的负载形状（多玩家、自然刷怪、区块生成、长途寻路） | **没做**。本轮固定 40 僵尸 + 1 村民 / `long128` / `tick sprint`，与 P1-NET 同一条腿，为的是可比较 |
| 5 | 真实 AI 的"矩形重复"在别的负载上会不会变多 | **没做**。本轮 4 条腿给的是这一类负载的数字（0.05%）；多玩家/长途/卡住的怪可能不同，但那不是本负载 |
| 6 | `fallback.compare`（回退归因）本轮的重复 | **没做**。沿用 P1-NET 的 `fbSame=45/105=42.9%`；本轮只报 `pairSame` |
| 7 | MSPT 判据 | **没作为判据**。与 P1-NET 同因：腿间噪声 ±52%，开关效应在本机噪声以下（本轮 off/on 的 `mspt_avg` 0.475/0.454/… 也在这个量级） |
| 8 | 改默认值 | **没做**（也不该由本流做）。`cava.mirror.reuse.crosstick` 默认仍 **false**，`cava.pathfind.native` 默认仍 **false** |

---

## 9. 裁决

| # | 判据（R7 的实验设计） | 门槛 | 本轮实测 | 判定 |
| --- | --- | --- | --- | --- |
| ① | **跨 tick 复用命中率** | 命中率高才有意义（判决线：**<50% 即输**） | **1 / 1002 = 0.0998%**（对照腿，两次独立样本）；4 条腿合计 **1 / 1972 = 0.051%**；**同 tick 复用 0 次** | ❌ **输** |
| ② | 跨 tick 失效正确性（真实 tick 推进） | 改窗口内方块 → 推进 tick → 必须看到新地形；关钩子必须变红 | `ticksElapsed=2/6/21/101` 四条 `write` 臂全 **PASS**；关失效钩子 ⇒ 全 **RED(no-invalidation;stale-result(B==A);B!=javaRef;)** | ✅ **成立**（但**缺**区段卸载等失效源，见 §5.5） |
| ③ | 配对口径净收益（与 −14.75 µs/tick 同法） | **> 0 且超过噪声** | **−25.64 / −15.38 / −17.41 / −11.52 µs/tick**（4 条腿全负）；把推送抹零的上界仍 ≤ 0（−14.63 ~ −2.51） | ❌ **输** |

**明确的判决：这一注输了。** 具体是**两重输**：

1. **前提输**：跨 tick 复用要求"同一个矩形再次被求解"，真实 AI 负载上这件事 **1972 次里发生 1 次**
   （0.05%），复用命中 **0 次**。captain 的假设"冷推送占很大一部分"在**成本**上成立（推送 = 原生每次的
   28–30%，且每次都真推），但"跨 tick 复用能把它省掉"在**负载**上不成立。
2. **结构输**：即使假设 100% 命中（把推送成本整块抹零），四条腿的净收益仍然 **≤ 0**
   （−14.63 / −7.02 / −7.09 / −2.51 µs/tick）—— 因为真正压着符号的是**回退白付**（8.5–16.7 µs/tick）。

**给 captain 的动作建议**：

- `cava.mirror.reuse.crosstick` **保持默认 false**（本轮不改默认值）；R4 的打开条件（补区段卸载 + 活 tick
  金丝雀证据）本轮**只完成了一半**：金丝雀计数已经有了（`/cava pathfind stats` 能看到），跨 tick 复用
  也证明"会命中、会陈旧"，但**区段卸载失效源仍未补** ⇒ 打开它仍然不安全。
- P1 的下一步**不再是推送**，而是 R1：让"窗口够不够"有 sound 信号（ABI 决策）。这是唯一能把
  −11.5 ~ −25.6 µs/tick 翻正的方向；量级估计 **+7 ~ +15 µs/tick**。
- "整块世界常驻镜像 + 增量更新"**单独做不改变符号**（上界 8.4–11.0 µs/tick），要在路线 1 之后作为
  "把推送压到 0"的第二步才有叠加价值。

---

## 10. 本轮改动清单

| 文件 | 作用 |
| --- | --- |
| `src/main/java/cava/mirror/RegionMirror.java` | 新增推送成本出口 `pushNanos/pushFillNanos/pushAllocNanos/pushUploadNanos`（**只加只读访问器，没有改复用/失效的任何行为**） |
| `src/main/java/cava/hook/PathfindHook.java` | 镜像账本窗口（reset 快照 + 增量报告）+ `mirrorReport()` / `nativeSplitReport()`，接进 `AIDIST` 与 `/cava pathfind stats`（R5 待办） |
| `src/main/java/cava/hook/PathfindPerfBench.java` | 新增 `cava pathfind xtick arm/check`（跨 tick 失效实测：真实 tick 推进 + Java 参考 + 还原 + 判定） |
| `tools/parity-perf-pathfind.ps1` | 新增 `-XTick / -XTickArms / -XTickGaps` 驱动（arm → tick sprint → check）+ `XTICK` 回执采集 |
| `docs/CAVA-p1-cross-notes.md` | 本文件 |

**产物**：DLL **未改动**（257536 B / `0EBE3B04…904D`，六条腿 `#dllStable=True`）；
JAR 587168 B / `682ED206923A091155DA85E5F035E62BD4BE51ECE8AFEA877409CBB172B7130A`（六条腿同一个 jar）。
**全量测试**：`suites=47 tests=265 failures=0 errors=0 skipped=9`。

**commit**：见本文件所在提交（`git log -1 --format=%h`）。
