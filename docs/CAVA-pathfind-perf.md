# Cava P1-PERF：大搜索空间下的 native on/off 性能与逐 tick 一致性

> 作者：P1-PERF 流（补 prompts/04 里被推迟的那项验收）。
> 本文件**只记录实跑**。每条结论都带命令或日志出处；没跑到的、做不到的写在第 8 节。
> 落盘时间：2026-09-24。私有测试服：`testbed\perf-pathfind`（端口 25660/25661，绝不复用别人的目录/端口）。

---

## 0. 结论速览

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| 大搜索空间下 native 是快是慢 | **快**：每次调用 **1.26x–3.04x**（按"去掉两腿共有的 ChunkCache 构建"后的求解耗时算），节点数越多倍数越大 | 第 5 节表 |
| 每 tick 值多少钱 | 本整合包实测寻路频率 **0.22 次/tick**（40 僵尸+1 村民、1200 tick）；按此换算 maze63 规模每次省 **≈269 µs/tick** | 第 4.2 节 |
| 两条腿在大场景下是否逐调用一致 | **不完全一致**：4/5 个性能场景一致；**detour128 场景两腿都不一致**，且是两个独立缺陷 | 第 6 节 |
| 缺陷 1（窗口不足） | 镜像窗口 = 起点终点包围盒 + 4，**最优路径一旦离开这个盒子，native 就找不到路**：实测 native 返回 64 节点、停在墙前（vanilla 128 节点、绕过墙） | 第 6.2 |
| 缺陷 2（区域陈旧） | 镜像的"同矩形复用"**没有任何失效来源**：`ChunkSection.setBlockState` 主钩子在仓库里根本不存在（grep 0 处调用）⇒ 地形改了 native 不知道，实测**路径穿过 8 格实心石头** | 第 6.3 |
| 起终点隔 6 格的旧 bench 能不能代表 | **不能**：本流实测同一台机器同一个 jar 上它的 ns/op 在两次运行里差 22 倍（23.4 µs → 514.2 µs）；它量的是跨界固定成本 | 第 3.5 |
| 这个整合包里生物会被静默移除吗 | **本私有测试服上没有复现**：NoAI 与带 AI 的猪都在 30 秒（661 tick）后仍存活；40 僵尸在 1200 tick 冲刺里真实发起了 259–278 次寻路 | 第 4.1 |

**一句话**：大场景下 native 的收益随搜索规模增长（因为跨界的区域推送被"同矩形复用"吃掉了），
但这次测量同时暴露出**两个必须修的正确性缺陷**（窗口不足、区域复用无失效源）——
按 prompts/04 的验收语言，P1 **当前还不能算"与同整合包 native 关闭逐 tick 一致"**。

---

## 1. 为什么重做这件事（原来那版为什么不算）

现成的 `/cava pathfind bench` 用 `cava.hook.PathfindScenario`：起点终点只隔 **6 格**、
`NAV_RANGE=8`、`followRange=16`、平坦地面无遮挡 ⇒ 搜索空间极小（回执里 `avgNodes=6.00`，
原生侧展开的节点更少）。在那种尺度上：

- 每次调用的耗时几乎全是**跨界固定成本**（区域推送判定 + 生物档案上传 + FFM 调用 + 缓冲区分配）；
- 量出来的"native 更快/更慢"没有代表性，也无法回答"搜索变大以后收益怎么变"。

所以本流另起了一套**参数化的大场景**（`cava.hook.PathfindPerfScenario`）与分布口径的 bench
（`cava.hook.PathfindPerfBench`，命令 `/cava pathfind perf|site|diag|explore`），
并把"这个场景到底测没测到东西"做成**硬门禁**（场景不成立就是 `ok=false`，不给好看的数字）。

---

## 2. 测量条件（每个数字都对应这一套）

| 项 | 值 |
| --- | --- |
| 测试服 | `testbed\perf-pathfind\server`（从共享 `testbed\server` 复制，33 个 mod，与基线同一套整合包） |
| 世界 | 从 `testbed\parity\snapshots\parity-base` 恢复（固定种子 20260922、已 chunky 预生成 ±170、已跑热落定）**每条腿都恢复一次** |
| 端口 | 游戏 25660 / RCON 25661（先 `netstat` 确认空闲） |
| JVM | `C:\Program Files\Java\jdk-21\bin\java.exe`，`-Xms2G -Xmx4G --enable-preview --enable-native-access=ALL-UNNAMED` |
| 开关 | off 腿：`-Dcava.native.enabled=false`；on 腿：`-Dcava.native.enabled=true -Dcava.pathfind.native=true`；两腿都 `-Dcava.pathfind.probe=false` |
| 世界状态 | 开局 `tick freeze` + `save-off` + `doMobSpawning/doFireTick/doWeatherCycle/doDaylightCycle=false`；站点相关区块 **forceload**（单条命令上限 256 区块，实测 484 会被整条拒绝 ⇒ 分 4 个象限） |
| 计时口径 | `System.nanoTime()` 包住"造 ChunkCache + `findPathToAny`"；两腿都含 ChunkCache 构建，另单独报 `setup_avg` 以便扣除 |
| 迭代 | 每个 (preset,mode) 预热 300 次后再计时；n 见第 5 节（1500–20000） |
| 机器状态 | 本机同时有别的流的 Gradle daemon/java 常驻，所以**同一条腿内的相对比较**才可靠；两腿是紧接着跑的 |

### 2.1 产物哈希（每条腿开始时重新计算，结束时再算一次）

    DLL: J:\mc\Cava\natives\windows-x64\cava.dll
         257536 bytes  sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D
         （MSVC 产物；四条腿的 #dllStable 都是 True —— 测量期间没有被换掉）
    JAR: build\libs\cava-0.1.0.jar
         550714 bytes  sha256=B4D67BEF91736A656A12A0AE91B1863D69D74010FF5410EEB06EAA65E8843A31
         （jar 内嵌的就是上面那个 DLL；部署到 testbed\perf-pathfind\server\mods\cava-0.1.0.jar，
           哈希与构建产物一致：#deployed == #jar）

结果文件（脚本自动落盘，含回执原文）：`testbed\perf-pathfind\results\{off,on,off-ctl,on-ctl}.txt`。
本文件里的命令与回执都是从这些文件/日志里逐字抄的。

### 2.2 实跑命令

    # 一条腿（off/on 各一次；每条腿自己恢复世界、自己算哈希）
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg off -Native off -AiLoad -OldBench 2000
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg on  -Native on
    # 一致性比对（红 = 有差异，exit 2）
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off -OnTag on
    # 可证伪对照：只把"一条腿"的终点 Z 挪一格，diff 必须变红
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg off-ctl -Native off -Presets long128,maze41 -Modes reuse
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg on-ctl  -Native on  -Presets long128,maze41 -Modes reuse -TargetOffset 1
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off-ctl -OnTag on-ctl

---

## 3. 场景（为什么这些参数才算"大"）

全部是**合成负载**：脚本直接构造 `ChunkCache` + `PathNodeNavigator` 调 `findPathToAny`，
与生物 AI 发起的那一次调用**同一条入口**，但**不代表本整合包的真实 AI 负载分布**（见第 4 节）。
地形是运行时**幂等铺设**的（石地板 + 3 层石墙；迷宫布局是源码里的字面量 `'#'/'.'` 行，**零随机**）。

| preset | 几何 | 导航参数（navRange / followRange / maxRange） | 预算 range×followRange | 实测返回路径节点数 |
| --- | --- | --- | --- | --- |
| `long128` | 平坦 145×97 竞技场，起点 (32,71,0) → 终点 (160,71,0)，**无遮挡** | 8 / 192 / 384 | 1536 | **128** |
| `detour128` | 同竞技场；x=96 处 3 层石墙 z=-48..24，**唯一缺口在 z=25..48** | 32 / 192 / 512 | 6144 | **128**（vanilla 绕过墙） |
| `slalom` | 同竞技场；两道墙 (x=80,z=-48..16)、(x=112,z=-16..48)，强制蛇形绕行 | 32 / 192 / 512 | 6144 | **138** |
| `maze41` | 41×41 完美迷宫（对角穿越，唯一通路） | 42 / 192 / 512 | 8064 | **200** |
| `maze63` | 63×63 完美迷宫（对角穿越，唯一通路） | 42 / 192 / 512 | 8064 | **503**（预算耗尽、被截断：末节点距目标 22 格） |
| `long128hash` | **故意的几何陷阱**：起点 (32,71,-32)、终点 (160,71,-32) | 8 / 192 / 384 | 1536 | **1**（场景无效，见 3.4） |

### 3.1 为什么 followRange=192 而不是 16

原版 `findPathToAny` **只通过 ChunkCache 看世界**，而 ChunkCache 的半径是 `followRange + 8`，
未加载/范围外的区块在它眼里是 `EmptyChunk` = 空气。第一版把 followRange 设成 64（半径 72）
去测 128 格的终点 ⇒ **终点根本不在缓存里**，vanilla 自己也只能返回"1 个节点的最接近路径"。
现在全部 preset 用 192（半径 200），覆盖整条路径与整个迷宫。

> 这是个**会静默毁掉测量的坑**：两腿都会返回同一条 1 节点路径，比对还是"一致"。
> `env` 探针（`world=[stone/stone/stone/] cache=[stone/stone/stone/] cacheChunks=576/676`）
> 与 `cache-blind` 门禁就是为它加的。

### 3.2 "搜索真的展开了"的证据（不是只看路径长度）

- **maze63 的预算被耗尽**：预算 8064，回执 `nodes_avg=503.00 end=(83,71,-111) manh=22.000`，
  末节点离终点还有 22 格 ⇒ 搜索**至少弹出了 8064 个节点**才被预算截断（这是"展开规模"的硬下界）。
- **预算扫描**（`cava pathfind explore <preset> <ranges...>`）本来就是为了直接量这个：
  它把"目标最早在第几个预算下被找到"打出来。本流把它实现并编译通过了，
  但**完整表格没有跑完**（见第 8 节）。
- **时间量级**：同一个 jar、同一台机器上，起终点隔 6 格的旧场景 ≈ 23–514 µs/次，
  而 maze63 的 vanilla 是 **1861 µs/次**、slalom 是 **10607 µs/次**。

### 3.3 场景有效性门禁（`ok=false` 的五种原因，都是本流实跑踩出来的）

| 门禁 | 触发条件 | 为什么必须报红 |
| --- | --- | --- |
| `search-too-small` | 平均节点数 < preset 的 `minNodes` | 没展开就等于没测 |
| `cache-blind` | 站点的地板在 ChunkCache 里看不见（world=solid/cache=air） | 终点在缓存半径外 ⇒ 量的是"没有路" |
| `target-node-collision` | 起点与终点在原版 `PathNode.hash` 下**同键** | 目标查缓存会拿到起点节点，搜索第 1 个节点就判"已抵达" |
| `site-invalid` | 全站点逐格核对不过（地板/通道/墙三层） | 测的不是预设地形 |
| `canary-mismatch` | 金丝雀计数 ≠ warmup+n | 有调用没穿过注入点 ⇒ 计时无效 |

### 3.4 一个真实的原版几何陷阱（`target-node-collision` 的实证）

原版 `PathNode.hash(x,y,z)` 是 `(y & 0xFF) | ((x & 0x7FFF) << 8) | ((z & 0x7FFF) << 24) | (x<0?MIN:0) | (z<0?0x8000:0)`——
**z 只有低 8 位进高位**，而 **x 的第 15 位与"z<0"标志位重叠**。于是：

    PathNode.hash(32,71,-32)  = PathNode.hash(160,71,-32) = 0xE000A047     ← 本机实测（门禁打印原文）
    PathNode.hash(32,71,  0)  = 0x00002047
    PathNode.hash(160,71, 0)  = 0x0000A047                                  ← 不同键，正常工作

后果：`long128hash` 场景里 vanilla 在**弹出的第 1 个节点**上就判定"已抵达"（目标查缓存拿到的是起点节点），
返回 1 个节点的路径。在**最终产物**（jar sha256 `B4D67BEF…`）上用字节码复刻搜索时实测到（逐字）：

    [cava/pathfind] DIAG preset=long128hash replica{start.pathLength=0.0 penalized=0.0 maxRange=384.0 budget=1536
      target=(160,71,-32) FOUND@pop1 at(32,71,-32) pops=1 pushed=0}
      mobPos=(32.5,71.0,-31.5) start=(32,71,-32:WALKABLE)
      successors=8 [(32,71,-31:WALKABLE:g=0.0) (31,71,-32:WALKABLE:g=0.0) (33,71,-32:WALKABLE:g=0.0) …]
      world=[stone/stone/stone/] cache=[stone/stone/stone/] cacheChunks=576/676

（起点有 8 个合法后继、缓存里地板看得见，但搜索在第 1 个节点就判"已抵达" ⇒ 唯一解释就是目标与起点同键。）
**这不是 Cava 的 bug，是原版哈希的碰撞**；两条腿（原生内核也复刻了这个哈希缓存）都如实复现了它。
它的价值是：证明场景门禁能在"两腿一致但什么都没测到"时报红。

### 3.5 旧 bench（6 格场景）为什么不能用

同一个 jar、同一台机器、同一个测试服上，`/cava pathfind bench 2000` 两次跑出：

    BENCH id=1 ok=true n=2000 ns/op=23356.2   totalMs=46.7   avgNodes=6.00     (第一次 off 腿)
    BENCH id=1 ok=true n=2000 ns/op=514190.6  totalMs=1028.4 avgNodes=6.00     (第二次 off 腿，加了 -AiLoad)

**同一个场景、同一个节点数，差 22 倍。** 差别只在于前一次是服务器刚起来、后一次刚跑完 1200 tick 的
AI 负载冲刺（JIT/内存状态不同）。这正好说明小场景的绝对值不可用于跨腿比较 —— 本流只把它当"小场景参考量级"。

---

## 4. 生物存活实测 与 真实 AI 负载的寻路频率

### 4.1 任务里点名的"地雷"：本私有测试服上**没有复现**

    [probe] summoned 2 pigs (NoAI + AI) at t0        （t0 = gametime 3384）
    [14:02:31] [Rcon] [probe] PROBE_NOAI_ALIVE
    [14:02:34] [Rcon] [probe] PROBE_AI_ALIVE        （t1 = gametime 4045 ⇒ 661 tick ≈ 30 s）

两只猪（`NoAI:1b` 与带 AI）在**世界解冻**、真实 tick 30 秒后都还活着。
另外 40 只僵尸 + 1 村民在 1200 tick 的 `tick sprint` 里**真实发起了 259 次（off 腿）/ 278 次（on 腿）寻路**
（`cava pathfind stats` 的金丝雀前后差值），说明 AI 与寻路是真的在跑。

**结论：在 `testbed\perf-pathfind` 这套配置上，"召唤后约 1 秒被静默移除"没有复现。**
本文件仍然用**合成场景**做性能对比，原因是：真实 AI 的寻路**时机与规模都不受控**
（何时重算由 AI 随机数决定），不适合做逐调用计时；而合成场景可以把参数钉死、可复现。

### 4.2 真实 AI 负载的寻路频率（per-tick 换算的唯一实测来源）

    [perf] ai-load: 40 只僵尸 + 1 村民；sprint 1200 tick
    [perf] ai-load 寻路调用 = 259 次 / 1200 tick ⇒ 0.2158 次/tick（canary 117 -> 376）   ← off 腿
    [perf] ai-load 寻路调用 = 278 次 / 1200 tick ⇒ 0.2317 次/tick（canary 136 -> 414）   ← on 腿（控制腿）

⇒ **本整合包上，40 只僵尸 + 1 村民的负载 ≈ 0.22 次寻路/tick。**

"每 tick"换算就用这个数：**每 tick 的寻路耗时 = 每次调用 × 0.22**。
注意：真实负载里这些调用的规模取决于当时地形与目标距离，本流**没有**记录它们的路径长度
（见第 8 节），所以第 5.4 节的"每 tick"列是**把大场景的每次调用乘上实测频率**的投影，不是直接测到的。

---

## 5. 两条腿的测量结果

回执原文（节选，逐字；完整行在 `testbed\perf-pathfind\results\*.txt`）：

    off: PERF id=1 preset=long128 mode=reuse run=1 n=20000 warmup=300 ok=true ns_avg=592621.9 ns_p50=646000 ns_p95=759800 ns_p99=900900 ns_max=13703100 ns_min=376100 setup_avg=47287.8 totalMs=8413.8 nodes_avg=128.00 ...
    off: PERF id=9 preset=maze63 mode=reuse run=1 n=3000 warmup=300 ok=true ns_avg=1861465.9 ns_p50=2009300 ns_p95=2297300 ns_p99=2525400 ns_max=6110200 setup_avg=47885.0 totalMs=4203.1 nodes_avg=503.00 ... end=(83,71,-111) manh=22.000
    on : PERF id=1 preset=long128 mode=reuse run=1 n=20000 warmup=300 ok=true ns_avg=266812.9 ns_p50=279700 ns_p95=340900 ns_p99=467100 ns_max=12866900 ns_min=82600 setup_avg=51236.2 totalMs=3936.3 nodes_avg=128.00 ...
    on : PERFDETAIL id=1 ... takeovers=20300/20300 upload_avg_ns=7963.1 solve_avg_ns=205891.9 mirror=pushes:1,reuse:20307,fail:0,cells:14796
    on : PERF id=9 preset=maze63 mode=reuse run=1 n=3000 warmup=300 ok=true ns_avg=636692.5 ns_p50=642200 ns_p95=803700 ns_p99=921600 ns_max=1815400 setup_avg=39835.7 totalMs=1910.1 nodes_avg=503.00 ...
    on : PERFDETAIL id=9 ... takeovers=3300/3300 upload_avg_ns=10805.4 solve_avg_ns=575876.6 mirror=pushes:1,reuse:3307,fail:0,cells:57132

### 5.1 每次调用：avg / p50 / p95 / p99 / max（微秒）

`reuse` = 生产默认路径（同矩形复用镜像区域）；`repush` = 每次调用前强制作废复用
（`RegionMirror.onBlockChanged`，诊断用），代表"每次都真的重推窗口"的上界。

| preset（节点数） | 腿 | avg | p50 | p95 | p99 | max | 一致性 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| long128 (128) | off reuse | 592.6 | 646.0 | 759.8 | 900.9 | 13703.1 | 一致 |
| | **on reuse** | **266.8** | **279.7** | **340.9** | **467.1** | 12866.9 | |
| | off repush | 592.2 | 647.5 | 762.5 | 893.6 | 1521.5 | 一致 |
| | **on repush** | **396.8** | **423.0** | **509.9** | **673.5** | 8816.6 | |
| slalom (138) | off reuse | 10606.9 | 10818.0 | 12610.6 | 13000.9 | 42711.9 | 一致 |
| | **on reuse** | **5507.9** | **5791.6** | **6436.0** | **6959.8** | 8723.8 | |
| | off repush | 10465.9 | 10557.8 | 12569.0 | 12969.9 | 14335.8 | 一致 |
| | **on repush** | **6637.2** | **6751.0** | **7979.5** | **8754.7** | 11530.4 | |
| maze41 (200) | off reuse | 658.2 | 719.3 | 849.3 | 974.8 | 2634.6 | 一致 |
| | **on reuse** | **273.5** | **288.8** | **345.7** | **438.7** | 9582.8 | |
| | off repush | 645.3 | 664.1 | 842.3 | 979.8 | 1282.6 | 一致 |
| | **on repush** | **513.9** | **545.7** | **648.3** | **782.9** | 983.4 | |
| maze63 (503) | off reuse | 1861.5 | 2009.3 | 2297.3 | 2525.4 | 6110.2 | 一致 |
| | **on reuse** | **636.7** | **642.2** | **803.7** | **921.6** | 1815.4 | |
| | off repush | 1814.8 | 1875.7 | 2276.6 | 2493.0 | 4848.2 | 一致 |
| | **on repush** | **1180.4** | **1260.7** | **1446.1** | **1587.7** | 1957.9 | |
| detour128 (128/64) | off reuse | 2657.3 | 2881.3 | 3245.2 | 3482.4 | 23089.4 | **不一致 6.3** |
| | on reuse | 272.3 | 279.2 | 354.2 | 493.0 | 1502.6 | |
| | off repush | 2633.2 | 2872.2 | 3220.6 | 3377.5 | 12810.2 | **不一致 6.2** |
| | on repush | 783.6 | 823.8 | 963.6 | 1196.1 | 26737.8 | |
| long128hash (1) | off reuse | 33.7 | 31.6 | 44.5 | 86.7 | 260.7 | 场景无效 |
| | on reuse | 61.0 | 58.8 | 86.0 | 145.1 | 663.5 | |
| | off repush | 29.8 | 28.3 | 41.7 | 57.7 | 221.3 | 场景无效 |
| | on repush | 187.4 | 191.4 | 241.3 | 387.6 | 621.9 | |

### 5.2 native 相对 off 的倍数（先把两腿共有的 ChunkCache 构建扣掉）

| preset | off 求解 µs (avg−setup) | on 求解 µs | off/on | 每次调用（含 setup）的绝对差 |
| --- | --- | --- | --- | --- |
| long128 reuse | 545.3 | 215.6 | **2.53x** | −325.8 µs |
| long128 repush | 544.9 | 345.1 | **1.58x** | −195.4 µs |
| slalom reuse | 10351.1 | 5313.8 | **1.95x** | −5099.0 µs |
| slalom repush | 10216.8 | 6384.4 | **1.60x** | −3828.7 µs |
| maze41 reuse | 621.9 | 237.2 | **2.62x** | −384.7 µs |
| maze41 repush | 608.4 | 474.5 | **1.28x** | −131.4 µs |
| maze63 reuse | 1813.6 | 596.9 | **3.04x** | −1224.8 µs |
| maze63 repush | 1766.9 | 1135.1 | **1.56x** | −634.4 µs |
| long128hash reuse（1 节点） | 2.0 | 23.3 | **0.09x（native 慢约 11 倍）** | +27.2 µs |

含 setup 的"毛"倍数（脚本打印的原值）：long128 reuse 2.22x / repush 1.49x；
slalom 1.93x / 1.58x；maze41 2.41x / 1.26x；maze63 2.92x / 1.54x。

### 5.3 每次调用里 native 那段花在哪（on 腿，回执的 upload/solve 分解）

| preset | mode | 区域推送+档案上传 avg | 原生 A* avg | 镜像台账 |
| --- | --- | --- | --- | --- |
| long128 | reuse | **7.96 µs** | 205.9 µs | `pushes:1, reuse:20307, cells:14796` |
| long128 | repush | **145.98 µs** | 201.5 µs | `pushes:8300, reuse:8, cells:122806800` |
| slalom | reuse | 27.10 µs | 5277.3 µs | `pushes:1, reuse:8307, cells:120012` |
| slalom | repush | 1169.98 µs | 5216.5 µs | `pushes:4300, cells:516051600` |
| maze41 | reuse | 10.44 µs | 215.3 µs | `pushes:1, reuse:6307, cells:26508` |
| maze41 | repush | 248.70 µs | 221.4 µs | `pushes:3300, cells:87476400` |
| maze63 | reuse | 10.81 µs | 575.9 µs | `pushes:1, reuse:3307, cells:57132` |
| maze63 | repush | **511.25 µs** | 621.5 µs | `pushes:1800, cells:102837600` |

**读法**：`reuse` 里"推送"只发生 1 次（热身首调），所以每次真正付的是**档案上传 ≈ 8–27 µs**；
`repush` 才是"每次真推窗口"的代价：**maze63 的窗口 63×12×63 ≈ 4.5 万格 ⇒ ~511 µs/次**，
slalom 的窗口 132×12×9 ⇒ ~1170 µs/次。窗口越大推得越贵，而**这个成本与搜索规模无关**
（它就是一次拷贝 + FFM 上传 + 形状守卫扫描）。

### 5.4 每 tick 的口径（用第 4.2 节的实测频率 0.22 次/tick）

| 场景规模 | off µs/tick | on µs/tick（reuse） | 每 tick 省 |
| --- | --- | --- | --- |
| maze63（503 节点、预算耗尽） | 409.5 | 140.1 | **≈ −269 µs/tick** |
| slalom（138 节点、强制绕行） | 2333.5 | 1211.7 | ≈ −1122 µs/tick |
| maze41（200 节点） | 144.8 | 60.2 | ≈ −85 µs/tick |
| long128（128 节点、平坦） | 130.4 | 58.7 | ≈ −72 µs/tick |

**这是投影不是直测**：0.22 次/tick 是实测的，但"每次调用都是这个大场景"是假设。
本整合包的真实 AI 寻路多数是短路径，所以真实收益**小于**上表；
上表回答的是"如果寻路规模真的这么大，每 tick 值多少"。

---

## 6. 一致性判定（可证伪）

### 6.1 判定命令与结果

    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off -OnTag on
    ......
    判定：**DIVERGENT**（2 处）
      !! detour128/repush : nodes_avg(off=128.00 on=64.00) | nodes_p50(off=128 on=64) | nodes_min(off=128 on=64) |
         nodes_max(off=128 on=64) | end(off=(159,71,0) on=(95,71,0)) | manh(off=1.000 on=65.000) |
         sig_coords(off=0x5404370d9d311ede on=0x792a9f3088f39025) | sig_types(off=0x8b587018814f4325 on=0x3b329a200570b325) |
         analyzeLen(off=128 on=64) | endNode(off=(159,71,0) on=(95,71,0))
      !! detour128/reuse : sig_coords(off=0x5404370d9d311ede on=0x7acb85719d26c525) |
         collisionNodes(off=0 on=8) | firstBadNode(off=(无) on=(96,71,0))
    COMPARE EXIT=2

比对字段（全部来自回执，逐字比较）：`ok / n / nodes_avg / nodes_p50 / nodes_min / nodes_max / nullPaths /
endDistinct / end / manh / reachesTargetFlag / sig_coords / sig_types / sigDistinct / analyzeLen / startNode /
endNode / collisionNodes / firstBadNode` + "on 腿是否每次调用都接管了"（`takeovers == warmup+n`）。
`sig_coords` / `sig_types` 是**整条节点序列**的 FNV-1a（坐标 / PathNodeType 序号），不是抽样。

**其余 4 个性能场景（long128 / slalom / maze41 / maze63）在两种 mode 下全部逐字段一致**：
节点数（128/138/200/503）、末节点、曼哈顿距离、整条序列哈希、穿墙计数（0）都相同，
且 on 腿每次都接管（`takeovers = n + warmup`，例如 `takeovers=20300/20300`）。

### 6.2 缺陷 1：镜像窗口不够 —— native 找不到绕过障碍的路（repush 暴露）

`detour128` 的唯一缺口在 z=25..48，而镜像窗口是"起点终点包围盒 + 4"⇒ **z=-4..4**。
native 在窗口里看到的 x=96 是一整面墙，绕行的路不在窗口内，于是它返回"到最接近点的路径"：

    end=(95,71,0)  manh=65.000  nodes_avg=64.00        ← on（repush）
    end=(159,71,0) manh=1.000   nodes_avg=128.00       ← off（vanilla 绕过墙）

**后果**：这是 prompts/04 验收语言里的直接违反 —— 同一只生物、同一个目标，装 Cava 之后
走的是另一条路（走不通就停在墙前）。它是**窗口策略的结构性问题**（`RegionRect.forSolve` 只按
"节点扫描需要的边距"推导，没有覆盖"最优路径可能离开包围盒"这件事），不是边界拷贝的浮点问题。

### 6.3 缺陷 2：区域复用的失效源不存在 —— native 走过 8 格实心石头（reuse 暴露）

`reuse` 腿里 native 的路径**穿过了 x=96、y=71..73 的石墙**：

    on : PERFDETAIL id=3 … collisionNodes=8 firstBadNode=(96,71,0) mirror=pushes:0,reuse:8308,fail:0,cells:0
    off: PERFDETAIL id=3 … collisionNodes=0 firstBadNode=(无)

机制（已定位到源码）：镜像的复用条件是"**同矩形 + 自上次上传以来没有失效事件**"，
而失效事件只有 `RegionMirror.onBlockChanged / onSectionUnloaded / onWorldChanged` 三个入口。

    > grep -rn "onBlockChanged|onSectionUnloaded" src/main/java
    src/main/java/cava/mirror/RegionMirror.java:482:  public void onSectionUnloaded(...)
    src/main/java/cava/mirror/RegionMirror.java:487:  public void onBlockChanged(...)
    src/main/java/cava/hook/PathfindPerfBench.java:225: mirror.onBlockChanged(0,0,0);   ← 本流诊断用
    src/main/java/cava/hook/PathfindPerfBench.java:241: mirror.onBlockChanged(0,0,0);   ← 本流诊断用

**仓库里没有任何 mixin 调它们**：契约说的主钩子 `ChunkSection.setBlockState(method_12256)`
在 `src/main/java/cava/mixin/**` 里不存在（18 个 mixin 文件里没有方块变更钩子）。
⇒ `pushReusingSameTick` 的"有变更就不复用"目前**没有事件来源**：
只要两次求解的窗口矩形相同（同一只生物连续寻路、或多只生物在同一片地形上），
**地形怎么变 native 都不会知道** —— 实测后果就是 mob 穿墙。

> 对照：off 腿同一次调用的路径 `collisionNodes=0`；把复用强制作废（repush）后
> native 的路径不再穿墙（但换成 6.2 的截断问题）⇒ 两个缺陷是**独立**的。

### 6.4 证伪对照：把一条腿的场景参数改一格，diff 必须变红

    # off-ctl 与 on-ctl 只差一个系统属性：-Dcava.pathfind.perf.targetOffset=1（终点 Z +1）
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off-ctl -OnTag on-ctl
    ......
    判定：**DIVERGENT**（2 处）
      !! long128/reuse : end(off=(159,71,0) on=(159,71,1)) | sig_coords(off=0x7acb85719d26c525 on=0x7acb84719d26c372) | endNode(...)
      !! maze41/reuse : nodes_avg(off=200.00 on=201.00) | end(off=(135,71,134) on=(135,71,135)) | sig_coords(...) | sig_types(...)
    CTL COMPARE EXIT=2

注意 long128 这一条：**路径长度完全相同（都是 128）**，只有末节点与整条序列哈希不同 ——
比对仍然报红。这证明第 6.1 节的"一致"不是因为比对太粗（例如只比节点数）才通过的。

---

## 7. 结论（诚实的版本）

1. **大搜索空间下 native 是快的，而且尺度越大越划算**：去掉两腿共有的 ChunkCache 构建后，
   128 节点 2.53x、200 节点 2.62x、503 节点 3.04x；强制每次重推窗口时仍有 1.28x–1.60x。
   根因是**跨界固定成本**：查表/上传/分配与搜索规模无关，而 Java 侧的 A* 线性增长。
2. **收益的上界被"窗口推送"卡住**：窗口一旦真的要重推（地形变了、矩形变了），
   maze63 规模 **+511 µs/次**、slalom 规模 **+1170 µs/次**，会把 1.95x–3.04x 压到 1.28x–1.60x。
   本整合包实测的寻路频率只有 0.22 次/tick ⇒ 这个成本目前**吃得下**（每 tick 省 85–1122 µs 的投影）。
3. **但 P1 现在还不能算"与同整合包 native 关闭逐 tick 一致"**：第 6.2 / 6.3 两条是**行为差异**，
   不是性能问题。两条都有最小复现（`detour128` 的 reuse/repush 两种 mode）与机制定位。
4. **1 节点级别的搜索上 native 是亏的**（0.09x，慢约 11 倍）：`long128hash` 场景
   每次调用 23.3 µs vs Java 2.0 µs。这类短距离调用在真实负载里占比不低，
   所以"接管一切寻路"这个策略需要按规模分流 —— **本流没有实现分流，也没测分流后的收益**（见第 8 节）。

---

## 8. 未验证 / 做不到（不要当成已完成）

| # | 项 | 状态 |
| --- | --- | --- |
| 1 | `explore` 预算扫描表（"目标最早在多小预算下被找到" = A* 展开数的硬下界） | **命令已实现并编译通过，但没有跑出完整表格**；本报告的"展开规模"证据来自 maze63 的预算耗尽与时间量级 |
| 2 | 真实 AI 每次调用的**路径长度分布** | 未记录。第 5.4 节的"每 tick"是按"每次都是大场景"的投影 |
| 3 | 多生物**并发**寻路（同一 tick 多只生物） | 未测。原生侧区域/档案是每句柄一份可变状态，注入侧用锁串行化 ⇒ 并行度=1 的影响没有量过 |
| 4 | 自然地形（不是石地板竞技场）上的窗口不足问题 | **未测**。6.2 是在"唯一通路远离包围盒"的人造几何上做的；真实地形里多常见，需要另做统计 |
| 5 | 水生 / 飞行寻路 | 原生内核不覆盖（已知范围外） |
| 6 | 树木/藤蔓/脚手架等"位置相关形状"的方块 | 未测；形状守卫（`cava.mirror.shape.guard`）在本流场景里没有触发（站点只有石/空气） |
| 7 | 整服层逐 tick 差分（2000 实体 × 6000 tick） | **未做**（prompts/04 的整服层验收）；本流只做了逐调用一致性 + 真实 AI 频率 |
| 8 | ServerCore / Lithium 被跳过是否可观测 | 沿用前面门禁的结论（单目标下不可观测），本流没有重新验证多目标/大场景 |
| 9 | 本测试服上"生物被静默移除"没有复现 | 不代表共享 `testbed\gate-preview` 上也没有；两个目录的 mod/config 不完全一样 |

---

## 9. 本流踩到的坑（写给后来者，每条都花过时间）

1. **`/forceload add` 单条命令上限 256 区块**：一次要 484 个区块时**整条命令被拒**（一个区块都没加载），
   必须拆成 ≤256 的矩形。日志原文：`Too many chunks in the specified area (maximum 256, specified 484)`。
2. **原版 `findPathToAny` 只看得到 `followRange+8`**：终点必须在这个半径内，否则 vanilla 自己就返回
   1 个节点的"最接近路径"，两腿还"一致"。加 `cache-blind` 门禁 + `env` 探针以后一眼可见。
3. **`PathNode.hash` 会让某些(起点,终点)同键**（见 3.4）：合成场景必须查哈希，否则量到的是
   "第 1 个节点就判定抵达"。
4. **RCON 回执在 `sendCommandFeedback=false` 下不可信**：命令还在跑，RCON 已经返回空包 ⇒
   脚本会抢跑下一条命令（后续全被 `busy` 拒掉）。必须**轮询日志里的回执行**。
5. **站点写入会被盖回去**（本整合包 c2me 的异步 chunk io 嫌疑最大）：
   写完立刻回读可能读到旧地形。现在的协议是 `site → save-all flush → site`，
   并要求第二次回执的 `verifyAfterRebuild=PASS`；`build()` 的 `changed` 计数**不能**当稳定性判据
   （第二遍的 clear+重放墙本来就会再改一遍，容易误判）。
6. **PowerShell param 块的逗号**：`-Diag`/`-AiLoad` 这类 switch 插进 param 块时忘了逗号 ⇒
   PowerShell 解析报错（`函数参数列表中缺少")"`）。
7. **生成 PowerShell 脚本时别在 JS 模板里写 PowerShell 的美元花括号变量**（会被当插值），
   本次是脚本生成阶段踩的。

---

## 10. 复现（复制即可）

    # 0) 前置：私有测试服（从共享 testbed 复制 + 从 parity 快照恢复世界）
    #    robocopy testbed\server -> testbed\perf-pathfind\server ; robocopy testbed\dl -> testbed\perf-pathfind\dl
    #    世界：robocopy testbed\parity\snapshots\parity-base -> testbed\perf-pathfind\server\world /MIR
    #    （脚本每次运行都会自己恢复世界）

    # 1) 构建（GRADLE_USER_HOME 必须显式指向工作区内，否则撞别人的 zip.lck）
    $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; .\gradlew.bat build --console=plain --no-watch-fs

    # 2) 两条腿（各自恢复世界、各自算 DLL/jar 哈希并写进结果头）
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg off -Native off -AiLoad -OldBench 2000
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg on  -Native on

    # 3) 一致性（红 = exit 2）
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off -OnTag on

    # 4) 证伪对照（一条腿的终点挪一格 ⇒ 必须红）
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg off-ctl -Native off -Presets long128,maze41 -Modes reuse
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg on-ctl  -Native on  -Presets long128,maze41 -Modes reuse -TargetOffset 1
    pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off-ctl -OnTag on-ctl

    # 5) 单场景诊断（邻居/起点/缓存/哈希同键/搜索复刻）
    #    RCON: cava pathfind diag <preset> | cava pathfind site <preset> | cava pathfind explore <preset> 1,2,4,8,16,32
    pwsh -File tools/parity-perf-pathfind.ps1 -Leg diag-final -Native off -Presets long128hash -Diag

---

## 11. 产物清单（本次改动）

| 文件 | 作用 |
| --- | --- |
| `src/main/java/cava/hook/PathfindPerfScenario.java` | 大场景预设（长路径/绕行/迷宫/几何陷阱）、字面量迷宫、幂等铺场、全站点逐格核对、环境探针 |
| `src/main/java/cava/hook/PathfindPerfBench.java` | `/cava pathfind {perf|site|diag|explore}`：分布计时、可观测指纹、五类场景门禁 |
| `src/main/java/cava/hook/PathfindBench.java` | 只加一行：同一注册时机里挂上 perf 命令 |
| `tools/parity-perf-pathfind.ps1` | 一条腿的完整驱动 + 哈希钉住 + 站点协议 + 一致性比对（可判红）+ 证伪对照 + AI 负载频率 |
| `docs/CAVA-pathfind-perf.md` | 本文件 |
| `docs/CAVA-baseline.md` | 追加"P1-PERF"一节指向本文件 |
