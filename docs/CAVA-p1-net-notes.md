# Cava P1-NET：真实 AI 负载下 `cava.pathfind.native` 打开到底净赚还是净亏

> 作者：P1-NET 流（补 captain 裁决 **R2 判据①** 唯一缺的那个数字：真实 AI 负载的净收益）。
> 本文件**只记录实跑**：每条结论都带命令/回执原文/产物哈希；没跑到的写在 §8。
> 私有测试服：`testbed\perf-net`（端口 25690/25691），世界每条腿从 `parity-base` 快照恢复一次。

---

## 0. 结论速览

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| **真实 AI 负载下打开开关净赚还是净亏** | **净亏**。**成对实测（同一次调用、同一份地形、同一个 JIT 状态）−35.405 ms / 2400 tick = −14.75 µs/tick**；另一条独立腿 −35.309 ms = **−14.71 µs/tick** | §4.3 |
| 跨腿口径的净收益 | 也全是负的：−3.3 / −53.4（J2' 两对）、−27.4 / −30.5（J3）µs/tick；但**跨腿噪声就有 ±40–100%**（三条纯 Java 腿 48.3 / 64.5 / 95.2 µs/tick）⇒ 跨腿只能给符号，给不了幅度 | §4.1 §4.4 |
| 为什么会亏（机制） | 两条路都在亏：① 接管的那些调用上**原生自己就比 Java 慢**（157.7 µs vs 129.6 µs/次，**1.22x**）；② 31–33% 的调用白付一次原生（157.7 µs/次）再跑 Java（464 µs/次） | §4.3 |
| 每 tick 服务端耗时能不能看出差别 | **看不出**：三条纯 Java 腿的 MSPT p50 就差 52%（0.285 → 0.434 ms），而开关的效应是 −14.7 µs/tick ≈ tick 的 3.8% ⇒ 在本机噪声以下 | §4.4 |
| 真实 AI 每次调用的规模分布 | 距离（3D 切比雪夫）p50=**7** / p90=**10** / max=**11–15** 格；返回路径节点数 p50=**7–8** / p90=**12–14** / max=**26–27**；原版节点预算恒为 **560** | §4.2 |
| 按规模分流能不能救 | **不能**。逐距离桶**每一桶都是净亏**（−47 ~ −317 µs/次），回退率也不随规模变化（24.7% / 33.3% / 41.6% / 38.6%）⇒ 阈值只能砍前缀，砍哪一段都翻不正；数据支持的"最优阈值"是 **≥12 格 = 一个都不接管** | §5 |
| 分流之后净收益 | 阈值 12 格：接管 0 次、净收益 **0**（结构上等价于把开关关掉）；阈值 99999（改坏）同样 0 —— **分流只是把亏损关掉，不产生收益** | §5.3 §6 |
| R6 机械对拍 | 已加测试（源码格式串 vs 脚本字段表 vs **真实回执样本** 三方对拍），**通过**；并修掉脚本里"字段缺失 = 相等"的那个坑 | §7 |

**一句话**：真实 AI 负载下打开 `cava.pathfind.native` **是净亏**（−14.7 µs/tick，机制见 §4.3），
**按规模分流救不回来**（§5），因此 R2 判据①**不满足**，建议**继续默认关闭**（§9）。

---

## 1. 这一轮要回答什么（以及为什么以前没有这个数）

P1 的正确性已经闭合（12/12 preset×mode 逐字段一致、`detour128` 两种 mode 都回到 128 节点、
`/cava pathfind invalidate` 五个 preset 全 PASS、两条可证伪对照都变红）。
但 `cava.pathfind.native` **默认仍然是 false**，因为 R2 判据①要的是**真实 AI 负载**（不是合成场景）
下的净收益，而 P1-FIX 只测到了**回退率 47.8% / 51.8%**。

- 回退率 ≈50% 是不是等于"净亏"？**不一定** —— 要看接管省下来的钱够不够付回去。
- "短程搜索原生本来就慢（1 节点 0.09x）"是不是意味着真实负载一定亏？**也不一定** ——
  真实 AI 的调用规模分布**从来没被测过**（P1-FIX §8.3 第 3 条明说这是缺口）。

本流把这两件事都变成数字，并且把"分流阈值"从拍脑袋变成有出处。

## 2. 测量条件（每个数字都对应这一套）

| 项 | 值 |
| --- | --- |
| 测试服 | `testbed\perf-net`（从 `testbed\perf-pathfind` 复制；35 个 mod，与基线同一套整合包） |
| 世界 | 每条腿从 `testbed\parity\snapshots\parity-base` 恢复（固定种子 20260922），并铺 `long128` 场景（与 P1-FIX 的 AI 腿同一块地） |
| 端口 | 25690（游戏）/ 25691（RCON）；跑前 netstat 确认空闲；收尾按 PID 杀 java 再 netstat 确认 |
| JDK | `C:\Program Files\Java\jdk-21\bin\java.exe`，`-Xms2G -Xmx4G --enable-preview --enable-native-access=ALL-UNNAMED` |
| 负载 | **40 僵尸 + 1 村民**（`-AiLoad`，与 P1-FIX 同一条腿）；`time set midnight`；村民 `resistance 9`（伤害免疫） |
| tick | `tick freeze` + `tick sprint`：**预热 400 tick**（不计入窗口）→ 清账本 → **测量 2400 tick** |
| 开关 | off 腿：`-Dcava.native.enabled=false`；on 腿：`=true -Dcava.pathfind.native=true`；两腿都 `-Dcava.pathfind.probe=false` |
| 分流 | `-GateMin <N>` → `-Dcava.pathfind.gate.minBlocks=N`；`0` = **关掉分流**（对照），`99999` = 把阈值改坏（证伪对照） |
| 重复 | off ×2、on ×2（J2'，同一 jar）；最终 jar 上再跑 off ×1 / on ×1 / 分流 ×2 / 回退归因 ×1 / 配对对照 ×2 |

**为什么村民要免疫伤害**：本流要跑 400+2400 tick（P1-FIX 那条腿只有 1200），村民中途被打死会让"负载"
在窗口内塌掉，而且死在哪个 tick 由 AI 随机数决定 ⇒ 两次不可比。`resistance 9` 两条腿完全一样；
这是对 P1-FIX 场景的一处**显式改动**（写在这里而不是藏起来）。

**为什么预热**：P1-PERF 实测同一个 6 格 bench 在冷/热服务端上差 22 倍（23.4 µs → 514.2 µs）。

**读数字前必须知道的噪声下限**：本机**跨腿噪声极大** —— 同一个合成场景（long128/reuse、n=300、
确定性调用）在两条**纯 Java** 腿上是 **402.6 µs** 与 **562.6 µs**（差 40%）；
AI 负载的纯 Java 腿每 tick 寻路耗时是 **48.3 / 64.5 / 95.2 µs**（差 2 倍）。
⇒ 本流因此加了**成对对照**（§4.3）：在同一次调用里把两条路都跑一遍，噪声消掉后才敢报数。

### 2.1 产物哈希（每条腿开始/结束各算一次；脚本的 `#dllStable` 机制复用）

    DLL: J:\mc\Cava\natives\windows-x64\cava.dll
         257536 bytes  sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D（全程未变）
    J2'  第 1 批四条腿：578425 bytes
         sha256=929395EEC38BA29B96FA983F5C636C95981E98A12F934C37077565DACD269BDA
    J3   第 2 批（= 默认阈值 0 + 回退归因）：579539 bytes
         sha256=FFFFB79D146824088C9A2CC264015CF63C3865B74BD7E13F97212413B4A282CA
    J4   第 3 批（J3 + 成对对照）：580722 bytes
         sha256=5AA2AFE67FA67A15B323CD10972CC15A5E98A2F5C0B9D7681A509606F89523E3

每条腿的 `#dllStable=True`、`#deployed == #jar`（脚本自动核对，逐条见 `testbed/perf-net/results/*.txt`）。
J2'→J3→J4 的差异只有"加的仪表"（闸门默认值、回退归因、成对对照），**都被下面各自的腿覆盖**；
决定性两腿（§4.3 的配对腿）用的是同一个 jar（J4）。

### 2.2 实跑命令（逐字；`$base` 见下）

    # 公共参数
    $base = @('-File','J:\mc\Cava\tools\parity-perf-pathfind.ps1','-Root','J:\mc\Cava\testbed\perf-net',
              '-ServerPort','25690','-RconPort','25691','-Presets','long128','-Modes','reuse','-ForceN','300')
    $dists = '2_4_6_8_12_16_24_32_48_64_96_128'

    # 第 1 批（J2'）：off/on 各两条 + 换手点扫描
    & pwsh @base -Leg net3-off-1 -Native off -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -SweepN 1500 -Sweep $dists
    & pwsh @base -Leg net3-on-1  -Native on -GateMin 0 -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -SweepN 1500 -Sweep $dists
    & pwsh @base -Leg net3-off-2 -Native off -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf
    & pwsh @base -Leg net3-on-2  -Native on -GateMin 0 -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf

    # 第 2 批（J3 = 最终行为）：决定性两腿 + 数据支持的阈值腿 + 改坏阈值 + 回退归因
    & pwsh @base -Leg net4-off-F   -Native off -AiLoad -AiTicks 2400 -AiWarmup 400
    & pwsh @base -Leg net4-on-F    -Native on  -AiLoad -AiTicks 2400 -AiWarmup 400
    & pwsh @base -Leg net4-gate12  -Native on -GateMin 12 -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf
    & pwsh @base -Leg net4-gatebad -Native on -GateMin 99999 -AiLoad -AiTicks 2400 -AiWarmup 400
    & pwsh @base -Leg net4-oncmp   -Native on -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -JavaProps '-Dcava.pathfind.fallback.compare=true'

    # 第 3 批（J4）：**成对对照**（接管成功时再跑一遍 Java）
    & pwsh @base -Leg net5-pair-1 -Native on -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -JavaProps '-Dcava.pathfind.diagnostic.compareAll=true'
    & pwsh @base -Leg net5-pair-2 -Native on -AiLoad -AiTicks 2400 -AiWarmup 400 -SkipPerf -JavaProps '-Dcava.pathfind.diagnostic.compareAll=true'

    # 一致性比对（红 = exit 2）
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag net4-off-F -OnTag net4-on-F    -Root J:\mc\Cava\testbed\perf-net
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag net4-off-F -OnTag net4-gatebad -Root J:\mc\Cava\testbed\perf-net

    # 换手点扫描（单条命令，逐距离；分隔符只能用 '_'，逗号会被 Brigadier 的 word() 拒掉）
    cava pathfind sweep long128 2_4_6_8_12_16_24_32_48_64_96_128 1500 reuse

---

## 3. 这一轮加的仪表（它回答什么问题）

| 加的东西 | 在哪里 | 回答什么 |
| --- | --- | --- |
| **每次调用的账本** | `PathfindHook` 探针 + `PathNodeNavigatorMixin.cava$returnPublic`（RETURN 注入） | 这一次调用到底花了多少：接管的原生那段、回退时白付的原生 + 再跑的 Java、"根本没调原生"的 Java。`/cava pathfind aidist` |
| **逐距离桶账本** | `PathfindHook.bucketAdd` | 任意阈值 T 的净收益（不必为每个候选阈值各跑一次 A/B） |
| **每 tick 服务端耗时分布** | `TickTimeRecorder`（`END_SERVER_TICK`） | MSPT 的 p50/p90/p95/p99 + 噪声下限（§4.4） |
| **按规模分流的闸门** | `PathfindSwitches.PROP_GATE_MIN`（`cava.pathfind.gate.minBlocks`） | 短程不接管；O(1)（两次减法 + max + 比较），发生在镜像推送/档案上传**之前** |
| **回退归因** | `cava.pathfind.fallback.compare` | 被回退掉的那些调用里，Java 结果与原生结果是不是**同一条路径** |
| **成对对照** | `cava.pathfind.diagnostic.compareAll` | 同一次调用把两条路都跑一遍 ⇒ 消掉跨腿噪声（§4.3） |

口径（**决定了数字怎么读**）：

1. **不含 ChunkCache 构建**：计时只包 `findPathToAny` 方法体；两腿都要建 ChunkCache（调用方建的），
   排除它才是"接管 vs 不接管"的净差（合成 bench 里它单列 `setup_avg`）。
2. **原生那一侧 = 镜像推送 + 档案上传 + `cava_pathfind`**（不含等锁）。
3. **每 tick 耗时只统计 sprint 中的 tick**：`tick sprint` 结束后脚本还要做几秒轮询/RCON，
   那段时间服务端仍以 20 TPS 跑"冻结 tick"（间隔 ~50 ms），会把 p95 顶到 49.9 ms
   （第一次实测就是这样：2628 个样本里 228 个是睡眠 tick）。`TickTimeRecorder` 因此把
   间隔 > 45 ms 的样本计入 `slowTicks`（如实报数），不进百分位。
   另一条独立口径是 **vanilla 自己的 sprint 报告**（`Sprint completed with N ticks per second, or X ms per tick`）：
   实测与 `mspt_avg` 一致（off-1：日志 0.53 ms/tick vs `mspt_avg=0.532`）。
4. 寻路**全部在服务端线程**上：`offThread=0`（各腿 500–1500 次调用，`unaccounted=0`）。

---

## 4. 结果

### 4.1 决定性两腿与两次独立重复

| 腿 | jar | 分流阈值 | 调用数 | 接管 | 回退(率) | Java 总耗时 | 原生总耗时 | **每 tick 寻路** | 相对 off |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| net3-off-1 | J2' | —（off） | 497 | 0 | 0 | 166.29 ms | 0 | **69.3 µs** | — |
| net3-on-1 | J2' | 0 | 488 | 333 | 155 (31.8%) | 92.57 ms | 81.65 ms | **72.6 µs** | **−3.31** |
| net3-off-2 | J2' | —（off） | 488 | 0 | 0 | 122.26 ms | 0 | **50.9 µs** | — |
| net3-on-2 | J2' | 0 | 489 | 320 | 169 (34.6%) | 142.30 ms | 108.18 ms | **104.4 µs** | **−53.43** |
| net4-off-F | J3 | —（off） | 517 | 0 | 0 | 115.97 ms | 0 | **48.3 µs** | — |
| net4-on-F | J3 | 0（= 默认） | 506 | 316 | 190 (37.5%) | 107.72 ms | 73.91 ms | **75.7 µs** | **−27.36** |
| net4-oncmp | J3 | 0 | 497 | 392 | 105 (21.1%) | 94.17 ms | 95.05 ms | **78.8 µs** | **−30.52** |

**符号一致（7 条腿全是负的），幅度不可比**（−3.3 ~ −53.4 µs/tick）—— 因为跨腿噪声本身就有 2 倍（§2）。
跨腿口径的**最好估计**是用逐距离桶把两条腿合起来重建：**−27.0 µs/tick**（off-F vs on-F，见 §5.2）。

### 4.2 真实 AI 的规模分布（这是 P1-FIX 明说"没记录"的那个数）

来源：`cava pathfind aidist`（一次逻辑调用记一次；`offThread=0`；`unaccounted=0`；`budget_p50=560` 恒定）。
`net4-off-F` 那一条腿 517 次调用的回执原文：

    dist    = 3D 切比雪夫（起点→目标，方块）= "路径步数"的下界
              p50=7  p90=10  p99=10  max=10          （三条腿 10 / 10~11 / 13~15）
              distHist=<2:14;<4:70;<8:214;<16:219;<24:0;<32:0;<48:0;<64:0;<96:0;<128:0;<192:0;+:0
    javaLen = 原版返回路径的节点数
              p50=7  p90=12  p99=21  max=26          （三条腿一致）
    budget  = 原版节点预算 (int)(navRange × followRange) = **560（每一次调用都一样）**
              budgetHist=<256:0;<512:0;<1024:517;...>   ⇒ 真实 AI 的搜索规模上界是 560 个节点，
              而不是合成场景的 1536 / 6144 / 8064
    javaUs  = 每次调用原版寻路挂钟
              p50=61.7  p90=761.4  p99=1556  max=2432  avg=224.3

**两条读法（都很重要）**：

1. **这个负载是"短距离、长尾耗时"**：距离中位数只有 7 格，但 p90 耗时是 p50 的 12 倍 ——
   因为很多目标是**走不到的**（僵尸的随机游走目标、被场地边缘挡住的目标），A\* 会把 560 个节点的预算跑满。
2. 因此**"短程 ⇒ 便宜"在这个负载上不成立**：距离 5–8 格那一桶的每次调用耗时（312–394 µs）
   比距离 ≤2 格那一桶（170–232 µs）**更贵**。按"距离"切的阈值，切掉的是便宜的那一头。

### 4.3 ★★ 成对对照：同一次调用两条路都跑（消掉跨腿噪声）

`-Dcava.pathfind.diagnostic.compareAll=true`：接管成功时，把注入体当不存在、用**同样的入参**再解一次
（内层靠 ThreadLocal 重入标志直接走原逻辑），于是同一次调用的（Java 耗时、原生耗时、两条路径是否相同）
全都能配对比较。回执原文（两条独立腿）：

    [17:16:32] [cava/pathfind] AIDIST calls=498 java=164 native=498 takeover=334 fallback=164 gated=0 unaccounted=0 offThread=0
      ... nativeUs_avg=145.3 javaTotalMs=81.976 nativeTotalMs=72.343 gateMinBlocks=0
      pairCalls=498 pairSame=332 pairDiff=166 pairRefJavaMs=119.010 pairNativeMs=72.343 pairFbJavaMs=81.976
      pairNetMs=-35.309 pairNetUsPerCall=-70.901 pairJavaLenAvg=7.48 pairNativeLenAvg=7.12
    [17:12:xx] [cava/pathfind] AIDIST calls=522 java=160 native=522 takeover=362 fallback=160 gated=0
      pairCalls=522 pairSame=346 pairDiff=176 pairRefJavaMs=121.171 pairNativeMs=82.303 pairFbJavaMs=74.273
      pairNetMs=-35.405 pairNetUsPerCall=-67.826

| 配对腿 | 调用 | 接管 | 回退 | 两条路**逐节点完全相同** | Σ 参考 Java | Σ 原生（接管+回退都算） | Σ 回退那次 Java | **成对净收益** | **每 tick** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| net5-pair-1 | 522 | 362 | 160 | **346 / 522 = 66.3%** | 121.171 ms | 82.303 ms | 74.273 ms | **−35.405 ms** | **−14.75 µs** |
| net5-pair-2 | 498 | 334 | 164 | **332 / 498 = 66.7%** | 119.010 ms | 72.343 ms | 81.976 ms | **−35.309 ms** | **−14.71 µs** |

**两条腿差 0.2%**（−35.405 vs −35.309 ms）—— 这才是可信的那个数：
**净收益 = −14.7 µs/tick（2400 tick 窗口，含回退的浪费）**。

**机制（同一条腿内可分解，不需要跨腿）**：

| 分项 | 数 | 说明 |
| --- | --- | --- |
| 接管那 362 次的 Java 参考解 | 46.898 ms ÷ 362 = **129.6 µs/次** | 就是它自己被换掉的那次 Java |
| 接管那 362 次的原生实付 | (82.303 − 160×157.7) ≈ 57.1 ms ÷ 362 ≈ **157.7 µs/次** | 含镜像推送 + 档案上传 + 跨界调用 |
| ⇒ **原生在自己"赢"的那些调用上就慢 1.22x** | 每次亏 ≈ 28 µs | 真实负载版的"跨界固定成本"（合成场景的 1 节点 0.09x 是它的极端形态） |
| 回退那 160 次白付的原生 | 160 × 157.7 ≈ **25.2 ms** | 纯浪费（Java 照样要跑，464.2 µs/次） |
| 合计 | 10.2 + 25.2 = **35.4 ms** ✓ | 与 `pairNetMs=-35.405` 对得上 |

**回退归因（另一条腿的独立证据）**：`net4-oncmp`（105 次回退，`fallback.compare=true`）：

    fbCmp=105 fbSame=45 fbSameLen=51 fbDiff=60 fbNull=0

`fbSame=45/105 = 42.9%` —— **这些回退是纯浪费**（原生交回的路径与 Java **逐节点完全相同**，本来可以直接用）。
另外 51 次"节点数相同、内容不同"（同长度、不同 tie-break），60 次内容不同。
把 45 次纯浪费的回退"改好"能省回 ≈45 × 900 µs ≈ 40 ms ≈ 16.9 µs/tick ——
**但这条腿的净亏是 −30.5 µs/tick，仍然翻不正**。
（顺带：`pairSame=66%` 也再次确认两条实现在这个负载上**大面积逐节点一致** = 正确性没有退化。）

### 4.4 每 tick 服务端耗时（MSPT）：**看不出差别，而且这就是结论**

| 腿 | 阈值 | mspt p50 | p90 | p95 | p99 | max | avg | slowTicks（滤掉） |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| net4-off-F | off | 0.285 | 0.594 | 0.892 | 1.587 | 15.925 | 0.382 | 162 |
| net4-on-F | 0（默认） | 0.315 | 0.694 | 1.064 | 1.757 | 3.656 | 0.419 | 161 |
| net4-oncmp | 0 | 0.442 | — | 1.265 | 2.443 | — | 0.571 | — |
| net4-gate12 | 12 | 0.434 | — | 1.188 | 1.952 | — | 0.547 | — |
| net4-gatebad | 99999（**纯 Java**） | 0.434 | — | 1.375 | 2.649 | 28.283 | 0.586 | 153 |
| net5-pair-1 | 0 + 配对 | 0.305 | 0.759 | 1.062 | 1.909 | 3.574 | — | — |
| net5-pair-2 | 0 + 配对 | 0.324 | 0.769 | 1.079 | 1.926 | 3.280 | — | — |

- **噪声下限**：`net4-gate12` 与 `net4-gatebad` 都是**纯 Java**（`native=0`、全部 `gated`），
  它们与 `net4-off-F`（也是纯 Java）的 p50 差到 **0.285 vs 0.434 ms（+52%）**，
  `mspt_avg` 0.382 / 0.547 / 0.586 之间同样差 50%。
- 开关的效应是 **−14.7 µs/tick ≈ 0.38 ms 的 3.8%** ⇒ **在本机腿间噪声以下**。
- 结论写法（**不用"应该/大概/可能"**）：**MSPT 上测不出可归因的差别；判决必须用 §4.3 的成对计数。**
- **CPU / 分配：没拿到**（JFR 在本机写不出文件是 P0-E §5 卡点 3 的已知问题；spark 的 Java 采样器
  采不到 `class_13` 深帧，P0-E §4.3 已实测；`/spark tps` 经 RCON 返回空，脚本如实记录
  `ai-load spark tps: `）。见 §8。

---

## 5. 按规模分流：阈值怎么选、分流之后净收益多少

### 5.1 先量换手点曲线（合成场景，确定性，每距离 1500 次 + 100 预热）

    cava pathfind sweep long128 2_4_6_8_12_16_24_32_48_64_96_128 1500 reuse

    d      2      4      6      8     12     16     24     32     48     64     96    128
    off  63.0   58.8   65.5   86.7   87.8  103.8  126.4  138.7  198.4  269.9  353.8  467.0  µs
    on   74.9   61.3   62.5   71.6   72.5   57.7   64.1   73.3   91.1  103.1  136.7  175.7  µs
    x    0.84   0.96   1.05   1.21   1.21   1.80   1.97   1.89   2.18   2.62   2.59   2.66

⇒ **合成场景里原生在 d≥6 就赢**（d=2 时 0.84x）。**但这条曲线不能直接拿来定阈值**：
同一距离上，真实 AI 的每次调用耗时是合成场景的 **3–5 倍**（真实 d≤8 桶 = 312–394 µs vs 合成 d=8 = 86.7 µs），
因为真实目标经常**走不到**（预算跑满 560 节点），而合成场景是平坦开阔地。
⇒ 阈值只能用**真实负载自己的逐距离桶**来定。

### 5.2 真实负载的逐距离桶（off-F vs on-F，同一 jar J3）

| 桶 | 距离 | off 调用 | on 调用 | on 回退率 | off 每次 Java | on 每次原生 | on 回退那次 Java | on 每次合计 | **每次净** | 贡献 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| b0 | ≤2 | 46 | 21 | **33.3%** | 99.9 µs | 134.5 µs | 282.8 µs | 417.3 µs | **−317.4 µs** | −2.78 µs/tick |
| b1 | 3–4 | 77 | 81 | **24.7%** | 230.6 µs | 117.1 µs | 161.1 µs | 278.2 µs | **−47.5 µs** | −1.60 µs/tick |
| b2 | 5–8 | 235 | 238 | **41.6%** | 239.9 µs | 144.3 µs | 226.2 µs | 370.5 µs | **−130.6 µs** | −12.95 µs/tick |
| b3 | 9–16 | 159 | 166 | **38.6%** | 234.2 µs | 164.2 µs | 210.1 µs | 374.3 µs | **−140.2 µs** | −9.70 µs/tick |
| 合计 | — | 517 | 506 | 37.5% | — | — | — | — | — | **−27.03 µs/tick** |

**三件事一眼可见**：

1. **每一桶都是净亏**（−47 ~ −317 µs/次）—— 没有"接管就赚"的那一头；
2. **回退率不随规模变化**（24.7% / 33.3% / 41.6% / 38.6%）⇒ **距离不是回退的预测因子**，
   按距离切分不开"好调用"与"坏调用"；
3. 阈值只能"砍掉一段前缀"，而**砍掉哪一段都不能把总账翻正**：

        T=0（不分流）  −27.03 µs/tick
        T=3            −24.25
        T=5            −22.65
        T=9             −9.70
        T=17            +0.00   ← 数据支持的"最优阈值"：**什么都不接管**（等价于把开关关掉）

### 5.3 数据支持的阈值腿（J3）

| 腿 | 阈值 | 接管 | 回退 | gated | 每 tick 寻路 | 说明 |
| --- | --- | --- | --- | --- | --- | --- |
| net4-gate12 | 12 格 | **0** | 0 | **490/490** | 64.5 µs | 真实距离 max≤15 ⇒ 阈值 12 把**全部**调用挡在原生之外（`gated=490`、`native=0`），结构上等于纯 Java |
| net4-gatebad | 99999 | 0 | 0 | **494/494** | 95.2 µs | **把阈值改坏**的对照：同样一次原生都不调；它 95.2 vs gate12 的 64.5 全部来自**腿间噪声**（两条都是纯 Java，§4.4） |

**分流本身的成本**：闸门是 `doTakeover` 的第一件事，O(1)（两次减法 + max + 比较 + 一次系统属性读），
发生在镜像推送/档案上传之前；被挡下的调用在账本里是 `gated`，
`takeovers + fallbacks + gated == expectDelta` 这条不变量由比对脚本核对（§7）。

**结论（不留含糊）**：按规模分流在本负载上**不能**把净收益翻正 —— 不是阈值选得不好，
而是亏损分布在**每一个**规模桶里、且与规模无关。数据支持的动作是 **T ≥ 12（= 不接管）**，
也就是**把开关关掉**。分流开关因此**默认 0（关闭）**，代码留着给"别的负载"用：
`-Dcava.pathfind.gate.minBlocks=<N>` 即可打开，不需要改代码、不需要重建。

---

## 6. 可证伪对照（改坏阈值 / 关掉分流 ⇒ 净收益变差）

| 对照 | 做了什么 | 结果 |
| --- | --- | --- |
| **A：关掉分流（= 把阈值改坏成 0）** | 数据支持的阈值是 ≥12 格（`net4-gate12`：接管 0、**净收益 0**）；对照腿把阈值设成 **0** | **净收益变差**：0 → **−27.4 µs/tick**（J3 决定性两腿）；J2' 上是 0 → −3.3 / −53.4。回执直接可读：`gated=0`、`takeover=316`、`fallback=190` |
| **B：阈值高到荒谬** | `-GateMin 99999` | `gated=494/494`、`takeover=0`、`native=0`：闸门把**全部**调用挡在原生之外（这正是"改坏"也能得到 0 的原因：它等价于关掉开关） |
| **C：分流不许改变结果** | `-Compare -OffTag net4-off-F -OnTag net4-gatebad`（`gated=600`、`takeovers=0`） | **CONSISTENT，EXIT=0**：要比的字段 + `site:` 子键全等，且"每次调用要么接管要么被明确计数"的不变量成立 |
| **D：默认配置不许改变结果** | `-Compare -OffTag net4-off-F -OnTag net4-on-F` | **CONSISTENT，EXIT=0**；同一条腿的合成场景 `long128/reuse` 是 **1.974x**（402.6 → 203.9 µs）—— 合成场景仍然赢、真实负载仍然亏，这正是本报告要区分的东西 |

---

## 7. R6 机械对拍：比对脚本的字段名 vs 回执的键名

**加的测试**：`src/test/java/cava/hook/ReceiptFieldParityTest.java`（`gradlew test` 里跑）。三条断言：

1. **脚本要比的每个字段都必须在回执里真的存在** —— 解析 `tools/parity-perf-pathfind.ps1` 的
   `$perfFields / $detFields / $siteFields` 三张表，与 `PathfindPerfBench` 里 **PERF / PERFDETAIL 格式串**
   抽出来的键名集合做**集合相等**（少一个红）。这一条就是 R6 那个 bug 的直接反例
   （脚本写 `reachesTargetFlag`、回执里是 `reachedTargetFlag` ⇒ 两边都 `(缺)`、两边相等 ⇒ 从来没比过）。
2. **回执里每个键都必须被分类**（多一个也红）：要么在"要比"的表里，要么在 `EXEMPT` 里**带理由**
   （身份/恒等常量、两腿预期不同的性能量、on 腿独有的过程计数、铺设副产物、只在失败回执里出现的 `reason`）。
   新增回执字段却忘了分类 ⇒ 测试红。反向也查：`EXEMPT` 里不许留"回执里根本没有"的键。
3. **真实回执样本**：`src/test/resources/cava-receipt-sample.txt`（逐字抄自 `net4-off-F` 那一腿的
   PERF/PERFDETAIL 行）里，**每一个"要比的字段"都必须取得到值** —— 直接复现"这个字段到底比没比过"，
   而不是只对着源码推。

**顺手的同口径自查（本轮结果）**：

| 项 | 之前 | 现在 |
| --- | --- | --- |
| PERF 行要比的字段 | 11 个 | 11 个（不变，但加了"字段缺失即红"） |
| PERFDETAIL 行要比的字段 | 8 个 | **15 个**：新增 `canaryDelta expectDelta analyzeNulls solidNodes params env startProbe` |
| `site:` 复合记号 | **一个都没比**（`Field()` 取不到子键） | **3 个子键**（`cells hash verify`，专门的抽取器） |
| 字段缺失的处理 | `(缺) == (缺)` ⇒ **静默通过**（R6 的根因） | **直接红**：`"$f(字段缺失! off=$a on=$b)"` |
| 接管不变量 | `takeovers+fallbacks == expectDelta` | `takeovers+fallbacks+**gated** == expectDelta` |
| 新回执行 | — | `AIDIST / AIDISTBUCKET / TICKSTAT / SWEEP` 一并进比对输出（AIDIST 逐桶、SWEEP 逐距离并排打） |

**测试结果**：`gradlew test --no-daemon` → **265 tests completed, 0 failed, 9 skipped**（含上面三条断言）。

---

## 8. 没跑 / 做不到（不要当成已完成）

| # | 项 | 状态与原因 |
| --- | --- | --- |
| 1 | **JFR / spark 的 CPU 与分配** | **没拿到**（原因见 §4.4 末）。本报告**没有**任何 CPU/分配数字。 |
| 2 | 别的负载形状（多玩家、自然刷怪、区块生成、长途寻路） | 未做。本轮按任务固定场景（40 僵尸 + 1 村民、`long128` 场地、`tick sprint`）测；它只代表"短程僵尸 AI"这一类。别的分布会不同 ⇒ 分流阈值要在**那个**负载上重量。 |
| 3 | 跨 tick 复用（R4） | 未做（`cava.mirror.reuse.crosstick` 仍默认关，原因见 P1-FIX §2.5/§8）。 |
| 4 | 回退的**逐节点**归因 | 部分做了：`fbSame / fbSameLen / fbDiff` 三个计数已有（§4.3）；但 `fbDiff=60` 里哪些是"Java 找到了更长的路（必须回退）"、哪些只是 tie-break 不同，**没有**做逐节点 diff。 |
| 5 | 把"42.9% 的回退是纯浪费"变成修复 | **未做**（也不在本轮范围）：需要一个**不跑 Java 就能判定"窗口够不够"**的信号 —— 按 R1 那是 ABI 决策。本流只提供数据。 |
| 6 | 阈值腿的重复次数 | `gate12` / `gatebad` 各 1 条腿。它们的结论是**结构性**的（`native=0` ⇒ 与 off 等价），不依赖统计；真正需要重复的两个配置（off / on）各有 2 条腿 + 2 条配对腿。 |
| 7 | 别的机器 / 别的 tick 数 | 未做。本机跨腿噪声 ±40–100%（§2），换机器结论可能变（尤其"原生固定成本 157.7 µs/次"这条随 CPU 与 JIT 变化）。 |
| 8 | 一条命令的健壮性坑（如实记录） | Brigadier 的 `word()` 参数**不接受逗号**，而 `sendCommandFeedback=false` 连错误都不回 ⇒ 实测白等 20 分钟、什么都没测到。现在 `sweep/explore` 的三种分隔符（`_ . ,`）都吃，脚本用 `_`。同类坑：`-Presets` 只有一个元素时 PowerShell 会把结果解包成字符串，`$want[0]` 变成首字符 ⇒ `site l` 被拒、场地根本没铺（已修：`@()` 强制数组）。 |

---

## 9. 裁决：R2 三条判据逐条给"满足/不满足"

| # | 判据（R2 原文） | 本轮实测 | 判定 |
| --- | --- | --- | --- |
| ① | **真实 AI 负载**下净收益为正（合成场景的 2.16–2.76x 不算数） | **成对实测 −14.75 / −14.71 µs/tick**（两次差 0.2%）；跨腿口径 −3.3 ~ −53.4；逐距离桶**每一桶都是净亏**；机制 = 原生在接管的调用上就慢 1.22x + 31–33% 回退白付 | ❌ **不满足** |
| ② | 回退率可观测且 **≤5%** | 本轮 21.1% / 31.8% / 34.6% / 37.5%（P1-FIX 47.8% / 51.8%）；判据分布里 `not-reached-early-stop` 占约九成，`window-shell` 2–4% | ❌ **不满足** |
| ③ | **短程调用按规模分流之后再测** | 已实现（`cava.pathfind.gate.minBlocks`，O(1)，默认 **0 = 关**）+ 已实测：逐桶净值全为负、回退率与规模无关 ⇒ 任何阈值都翻不正；数据支持的最优阈值是 ≥12 格 = **不接管** | ❌ **不满足**（分流做了也测了，但它是"止损"不是"变正"） |

**明确的 go/no-go**：**NO-GO**。`cava.pathfind.native` **继续默认 false**（R2 维持）。
本轮的代码改动**不影响默认行为**：默认 `gateMinBlocks=0`（= 与已测的"分流关闭"行为一致），
native 本来就默认关；两个诊断开关（`fallback.compare` / `diagnostic.compareAll`）默认 false；
`TickTimeRecorder` 只读 `System.nanoTime()`（每 tick 两次；各腿 `ticks=2400`）。

**最便宜的改法（用数据说话）**：**不是**按规模分流（§5 已证伪），而是**把回退成本降下来** ——
亏损的 71%（25.2 / 35.4 ms）来自"白付一次原生"：

1. 其中 **42.9% 的回退是纯浪费**（`fbSame=45/105`：Java 交回的路径与原生逐节点完全相同）。
   要吃掉它需要一个**不跑 Java 就能判定"窗口够不够"**的信号（R1 明确说那是 ABI 决策）；
   本条只是把"值多少钱"量出来：把 45 次纯浪费的回退改好 ≈ 16.9 µs/tick，**仍然翻不正**这条腿的 −30.5。
2. 剩下的亏在"原生在易调用上也慢"（157.7 vs 129.6 µs/次）：那是**跨界固定成本**
   （镜像推送 + 档案上传 + FFM 调用），要动就得动窗口/推送策略（同样是 R1）。
⇒ 在 R1 不变、ABI 冻结的前提下，**这个开关在真实 AI 负载上没有正收益的路径**；
本报告的用途是把"什么时候值得再打开"变成可复查的数字。

---

## 10. 本轮改动清单

| 文件 | 作用 |
| --- | --- |
| `src/main/java/cava/hook/CallStats.java` | 新增：分布统计（p50/p90/p99 + 桶直方图），纯 Java 可单测 |
| `src/main/java/cava/hook/TickTimeRecorder.java` | 新增：每 tick 服务端耗时分布（`END_SERVER_TICK` 相邻差；`slowTicks` 滤掉非 sprint 的睡眠 tick） |
| `src/main/java/cava/hook/PathfindHook.java` | 每次调用账本（Java/原生/回退三段）+ 逐距离桶 + `AIDIST`/`AIDISTBUCKET` 回执 + 分流闸门 + 回退归因 + 成对对照 |
| `src/main/java/cava/mixin/pathfind/PathNodeNavigatorMixin.java` | 新增 RETURN 注入（给"最终走 Java"的那次调用记账）；HEAD 里补参数快照（navRange/followRange…） |
| `src/main/java/cava/hook/PathfindSwitches.java` | 新增 `cava.pathfind.gate.minBlocks`（默认 0）、`cava.pathfind.fallback.compare`（默认 false）、`cava.pathfind.diagnostic.compareAll`（默认 false） |
| `src/main/java/cava/hook/PathfindPerfBench.java` | 新增 `cava pathfind sweep <preset> <d1_d2_...> <n> [reuse\|repush]`；PERFDETAIL 加 `gated` |
| `src/main/java/cava/hook/PathfindBench.java` | 新增 `cava pathfind aidist [reset]` / `tickstat [reset]` |
| `src/main/java/cava/hook/PathfindBootstrap.java` | 注册 tick 耗时记录器（与寻路无关、两条腿一样） |
| `tools/parity-perf-pathfind.ps1` | 字段表 + `site:` 抽取器 + **字段缺失即红**；`-GateMin` / `-Sweep` / `-AiWarmup`；SWEEP/AIDIST/TICKSTAT 比对段；两处命令/参数坑修复 |
| `src/test/java/cava/hook/ReceiptFieldParityTest.java` | 新增：R6 机械对拍（源码格式串 ↔ 脚本字段表 ↔ 真实回执样本） |
| `src/test/resources/cava-receipt-sample.txt` | 新增：真实回执样本（R6 的对拍基准） |
| `src/test/java/cava/hook/NetLedgerTest.java` | 新增：`CallStats` / `TickTimeRecorder` / 分流判据 / 默认值单测 |
| `docs/CAVA-p1-net-notes.md` | 本文件 |

---

## 附录 C【P1-CROSS 追加 2026-09-24】§8 第 3 条缺口已闭合：跨 tick 复用命中率 **≈0**，"冷推送"这一注判负

`docs/CAVA-p1-cross-notes.md`（P1-CROSS 流，响应 captain 裁决 **R7**）用与本文**同一套配对方法**
（`-Dcava.pathfind.diagnostic.compareAll=true`；40 僵尸 + 1 村民、预热 400 + 测量 2400 tick、私有服
`testbed\perf-cross` 25700/25701）把 §8 第 3 条（跨 tick 复用未做）与 §9 末"最便宜的改法"里的那个假设
（"接管调用也慢很可能与安全修复引入的冷推送有关"）测掉了：

- **命中率（第一判决线）**：跨 tick 复用的前提是"同一个矩形再次被求解"。两条 `crosstick=false` 对照腿
  （那两个窗口里镜像失效次数为 **0**，所以计数不会被失效事件掩盖）实测
  **1 次 / 1002 次调用 = 0.0998%**；四条腿（1972 次调用）合计 **1 次**；**同 tick 复用 0 次**。
  ⇒ **`mirrorPushes == nativeCalls`**（499/503/497/473）—— 不是"几乎每次都重推"，是**每次都重推**。
- **成本拆分（本文当时缺的那一半）**：原生每次 135.4–186.3 µs = 镜像推送 **39.89–52.97 µs**（28–30%）
  + 档案上传/请求填充 29–41 µs + `cava_pathfind` 36.6–91.7 µs（`prepMs`/`solveMs` 新增出口）。
- **净收益（配对口径，与本文 −14.75 直接可比）**：**−25.64 / −15.38 / −17.41 / −11.52 µs/tick**（四条腿全负）；
  把镜像推送成本**整块抹掉**（不可达上界）之后仍然 **≤ 0**（−14.63 / −7.02 / −7.09 / −2.51）
  ⇒ **即便 100% 命中也不能翻正**；亏损主项仍是本文 §4.3 那条"回退白付"（20–40 ms / 2400 tick）。
- ⇒ 本文 §9 末"唯一有机会把符号翻正的实验"到此**已被数据否决**；剩下的唯一方向是 **R1（窗口策略 / ABI 信号）**。
  `cava.mirror.reuse.crosstick` 保持默认 **false**（改默认由 captain 拍板）。


