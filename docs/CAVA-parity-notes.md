# Cava 差分测试与黄金轨迹（P0 差分测试流 / prompts 03）

> **本文件是本流的结论落盘处**。所有数字都是本机实跑出来的；没跑的一律写「未验证」。
> 复现命令见 §8。**不要照抄过时结论**：本文件同时记录了两条"文档与实测不符"的更正。

---

## 0. 一句话结论

| 层 | 形态 | 结果 |
| --- | --- | --- |
| **单元层** | Java → FFM → **C ABI** → 原生内核 vs oracle 参照实现，10000 组向量 | **ZERO DIFF over 10000 cases**（本机 ~4 s；**在实体 ABI 冻结后的 DLL 上复验过**：`java_sum=0x1c12265e`、14 个结构体） |
| 单元层（C++ 侧对照） | `cava_pathfind_vectors.exe`（现场重编译）直接调 `solve()`，10000 组全字段逐位 | **mismatches=0** |
| 单元层（负控制） | 故意把 `CAN_SWIM` 填到错误的 caps 位 | **检出 126 处差异 / 11 组** ⇒ 比对器不是瞎的 |
| **场景层** | 私有测试服 + 脚本化合成场景，native off×2 与 on×1，逐 tick NDJSON 比对 | 见 §4.1（真实数字） |
| **整服层** | 不变量断言（不是逐 tick 世界哈希） | 见 §5 |
| **mod 矩阵** | 纯原版 / +整合包（native on/off） | 见 §6 |
| **ServerCore 跳过** | 字节码证据 + on/off 差分 | 见 §7 |

**两条必须记住的实测更正**（都是本流亲手测出来的）：

1. **仓库里 `native/build/mingw/cava_pathfind_vectors.exe` 是陈旧产物**（14:25，那时 `cava_pathfind` 还返回
   `CAVA_ERR_UNIMPLEMENTED`）。拿它当"10000 组已通过"是**错的**。本流改成**每次现场重编译**
   （`tools/parity-unit.ps1` 腿 1），并以时间戳核对。
2. **"10000 组已与原生内核逐位一致"只有在走 C ABI 之后才重新成立**：本流第一次跑 Java→FFM→C ABI 就抓到
   **126 处差异**，根因不是内核算法而是**喂入** —— 把 `CAN_SWIM` 填到了 caps 的 `1<<2`
   （= `CAVA_NAV_CAN_FLOAT`）而不是 `1<<6`。C++ 那个测试看不到这一层（它直接构造 `WorldView/MobProfile`）。

---

## 1. 三层设计为什么这么切（承接 captain 的确定性报告）

`docs/CAVA-determinism-report.md` 的受控实验结论：同一份代码跑两次，`region` 差 3–4 个区块（全在出生点附近）、
`entities` 差 5 个、`poi` 0 个 ⇒ **逐 tick 世界哈希一致物理不可达**。本流据此落地：

- **方块层**：关刷怪 + 固定种子 + `tick freeze` + `/tick sprint N`；世界哈希**排除出生点附近区块**，
  排除范围写进 trace 头（`radius / spawnExcl / excl / incl / hash` 五个新键，见 §2）。
- **实体层**：**不用自然刷怪**，用数据包脚本化合成场景（固定数量/位置/属性的实体 + 脚本驱动的红石激励源）。
- **整服层**：降级为不变量断言（TPS/MSPT、无异常、native 回退计数=0、不崩、实体数在范围内）。

### 1.1 一条必须写明的偏离：世界的"3 个维度"改成主世界

契约 §4.2 说扫描盒"覆盖 3 个维度"。实测本存档 **DIM-1 / DIM1 一个区块都没生成**
（`testbed/parity/server/world/DIM-1/region` 不存在），于是第一 tick 会在两个维度里各强制生成 ~240 个区块：
分钟级耗时 + 生成时序噪声（c2me）。场景层的脚本化场景全在主世界，所以场景层固定 `-Dcava.parity.dims=overworld`，
并把这个选择写进 trace 头的 `hash` 字段（`dims=overworld;...`）。
**这条算对契约的偏离，请 captain 裁决**：要么接受"场景层只看主世界"，要么先在 nether/end 里预生成再打开。

### 1.2 世界哈希的开销（实测）与半径取值

半径 8 + 3 维 ≈ **9.8M 方块/tick**（推算 ~200 ms/tick，6000 tick 要 20 分钟）。
场景层用 **`radius=4` + `spawnExcl=3`**（= 32 个区块，约 1.3M 方块/tick）：
实测 **23.5–24.7 ms/tick**（日志原文 `已采集 200 tick（世界哈希 240844800 方块 ... 均耗时 23.83 ms/tick）`）。
场景平台在 chunk `(4,-1)..(5,0)`，radius=4 覆盖 |cx|,|cz| ≤ 4 —— **平台东端 x=80..84 在盒外**（已记录，装置与实体都在盒内）。

---

## 2. 黄金轨迹格式（契约 4.2 的实现 + 头行扩展）

- 逐 tick 一行 NDJSON，`trace-<label>.ndjson`；FNV-1a 64；写盘侧 `cava.parity.GoldenTrace`（进生产 jar）。
- **头行在契约的 8 个键之后追加 5 个键**（captain 要求"排除范围写进 trace 头"，否则"零差异"不可复现）：

~~~json
{"t":"h","v":1,"label":"off-a","native":false,"mods":"1240b4efa0af31ae","mc":"1.20.4","cava":"0.1.0",
 "seed":20260922,"startTick":0,"radius":4,"spawnExcl":3,"excl":"entities","incl":"",
 "hash":"dims=overworld;w=blocks(dim,x,y,z,stateId);e=entity-pos-bits;p=navstate(x,y,z,type);bt=null;nt=null"}
~~~

- `w`：固定扫描盒内所有区块的方块状态，按 (dim,x,y,z,stateId) 逐项 FNV-1a（state id 做键，禁对象身份），
  再减去出生点 `(2*spawnExcl+1)²` 的方形（默认 3 → 排除 ±48 方块）。
- `e`：扫描盒内实体的 (类型, id, x/y/z/motion/yaw/pitch 位模式, onGround)，**按 (类型,x,y,z,id) 排序**后逐项哈希
  （去掉"已加载集合迭代顺序"这个不稳定因素）。**默认不参与比对**（契约 4.2），头行 `excl=entities`。
- `p`：**与契约措辞不同，必须知道** —— 契约写的是"本次 tick 内所有寻路调用的…"，那需要注入点，
  而 `cava.hook` / `cava.mixin` **不归本流所有**（并行纪律）。本流采的是等价可观测量：
  每 tick 末所有 mob **当前导航路径**的 (实体键, 节点数, 逐节点 x/y/z/type)。
  头行 `hash` 明确写 `p=navstate`，报告必须按这个定义读；**"调用级"节点序列由单元层 10000 组逐位覆盖**。
- `bt` / `nt` **一律 null**：方块 tick / 邻居更新计数同样需要注入点（且 TIS `/log microTiming` 在专用服务端不落盘）。
  红石层目前靠 `w` 覆盖（红石功率是方块状态的一部分）。
- `detail-<label>.ndjson`（可选，`-Dcava.parity.detail=true`）：逐 tick 一行，含逐实体 `[键,哈希,坐标]`、
  逐路径 `[键,哈希,nodes=N]`、以及**相对上一 tick 变化的区块** `[维度:cx,cz,哈希]`。
  它是"有差异时定位到哪个实体 / 哪个区块坐标"的数据来源；不存在时比对器会**明说不能定位**（不假装）。

---

## 3. 单元层：一条命令 + 两条腿 + 负控制

    pwsh -File tools/parity-unit.ps1                 # 全量 10000 组
    pwsh -File tools/parity-unit.ps1 -MaxCases 500   # 快速回归

**腿 1（C++，全字段）**：现场重编译 `native/tests/build-pathfind.ps1` → `build/native-pathfind/cava_pathfind_vectors.exe`，
输出 `cases=10000 mismatches=0 worldHashFail=0 goldenBlockMismatch=0` / `RESULT: PASS`。
它覆盖 ABI **导不出**的字段：`pathLength / distanceToNearestTarget / penalty / visited / expandedCount / traceHash / manhattan / reachesTarget`。

**腿 2（Java → FFM → C ABI，生产路径）**：`cava.parity.PathfindVectorDiff`。它做三条**互相独立**的断言：

1. 语料完整性：解析出的输入能重建同一 terrain（`worldHash` 低 32 位相等）且与 `VectorGen.buildCase` 逐字段一致；
2. 参照实现重跑 vs 冻结语料（逐位）—— 语料过期/参照漂移在这里红，**不会伪装成"原生不一致"**；
3. 原生 vs 期望：`state_table_upload / region_upload / mob_profile_upload / cava_pathfind`，
   比 `nodeCount + 每节点 (x,y,z,type,g,f)`。

实测输出：

    === Cava 单元层差分：oracle 参照实现 vs 原生内核（经 Java FFM → C ABI）===
    本次比对的组数: 10000（有路径 10000 / 无路径 0）
    参照实现重跑  : 是
    原生         : status=OPEN build_id="cava 0.1.0 windows-x64 GNU 15.2.0 ... -O2 -fwrapv -ffp-contract=off -fno-fast-math ..." abi=1/1 java_sum=0x6975cbf9 native_sum=0x6975cbf9 entries=9
    ZERO DIFF over 10000 cases

**腿 3（负控制）**：`-Dcava.parity.selftest.wrongCaps=true` 把 `CAN_SWIM` 填到 `1<<2`，
输出 `负控制结果: PASS（检出 N 处差异，首个 nodeCount）`。
**N 随内核版本变化**：在 15:44 那个 DLL 上是 **126 处**；在实体 ABI 冻结之后重编的 DLL
（`java_sum=0x1c12265e`、14 个结构体）上是 **20 处**。判据只有一个：**必须 > 0**。
⚠️ 负控制腿**必须跑够组数**（首个敏感组是 #1281）：只跑前 50 组会得到"零差异"，
从而把"比对器是瞎的"这个错误结论报出来 —— 本机实测踩过一次，现在固定跑 `max(maxCases, 2500)`。

**CI 化**：`src/test/java/cava/parity/PathfindVectorParityTest.java` 被现有的 `gradlew test` 自动收集
（**没有改 build.gradle**）。实测 `build/test-results/test/TEST-cava.parity.PathfindVectorParityTest.xml`：
`tests=2 failures=0 errors=0 skipped=0`。

### 3.1 抓到的那次真实喂入 bug（排查过程值得记住）

首跑 10000 组报 **126 处差异、11 组失败**，全部集中在 `scenario=MIXED maker=LAND`：

1. `--dump 1281` 打印两侧节点 + 该坐标的 palette 下标 / 原版 common 类型；
2. 原生在**空气格**上给出 `type=9 (WATER)`，而 oracle 同格是 `type=10 (WATER_BORDER)`；
3. `cava_region_state_id_at` 逐格读回 **3136 格全部一致** ⇒ region 喂入没问题；
4. 于是查 profile 的 caps 映射 —— 对照 `cava_abi.h`：`CAVA_NAV_CAN_SWIM == 1u << 6`，
   而 harness 写成了 `1 << 2`（= `CAVA_NAV_CAN_FLOAT`）。修掉即 **ZERO DIFF**。

**教训**：caps 这类位标志必须**逐位对表**，不能凭语义相近猜位号 —— 猜错的后果是
"路径照样算得出来、只是水格类型不同"，只有随机向量才会偶然暴露。

---

## 4. 场景层：一条命令出报告

    pwsh -File tools/parity-diff.ps1 -Ticks 600          # off-a / off-b / on-a + 两次比对

腿脚本 `tools/parity-scenario.ps1`：私有测试服 `testbed/parity`（**私有端口 25591/25592**，不与共享的
`testbed/gate-preview`、`testbed/server` 抢）。每腿：还原**同一个世界快照** → 装脚本化场景数据包 →
起服务端（改 `-Dcava.native.enabled` / `-Dcava.pathfind.native` 两个开关）→ RCON `function cava:scenario` 布场 →
`/tick sprint 600` → 采样侧采满 600 tick 自动停服 → 回执校验（trace 行数 + 「轨迹落盘」日志 + 无 ERROR）。

场景内容（`tools/make-parity-datapack.ps1`，主世界 chunk(4,-1)..(5,0)，y=70）：
25×17 石头平台 + 清空 6 层；红石装置（`cava:tick_loop` 每 2 tick 翻转红石块 → 红石线 → 推活塞 + 灯 + 比较器）；
A 组 6 只 `NoAI` 猪牛（确定性实体层基线）；B 组 2 村民 + 3 僵尸（真正的寻路驱动）。

### 4.1 实测结果

#### 4.1.1 第一轮（**未通过**，但两次失败都定位到了根因 —— 这部分比"零差异"更有价值）

第一轮 off-a / off-b 都是 600 tick 有效采集（`行=601 tick行=600`，日志有「轨迹落盘」）：

    ================ 确定性前置：同一配置（native off）跑两次 ================
    DIFF FOUND over 600 ticks
      首个差异 tick=2
        tick 2 字段[x]  x: 'ent=0;paths=0' != 'ent=3;paths=0';
      逐字段差异 tick 数: x=492 w=533 p=516
      定位：tick 2 实体 只在 b 出现: minecraft:zombie#12 (64.50,71.00,3.50)
      定位：tick 2 实体 只在 b 出现: minecraft:zombie#10 (66.50,71.00,3.50)

    ================ native on vs off ================
    DIFF FOUND over 600 ticks
      首个差异 tick=68   字段[w]  w: '2065b81fc569589b' != '8a8fc7e9b35c3108'
      定位：tick 68 实体 只在 a 出现: minecraft:falling_block#27 (72.50,-33.04,64.50)
      定位：tick 67 实体 只在 b 出现: minecraft:falling_block#13 (72.50,-33.00,64.50)

**两个根因都在"跑法"这一侧，不在被测量的代码侧**：

1. **采样起点早于场景布场**：采样器在 `SERVER_STARTED` 就开始逐 tick 落盘，而场景是 RCON 之后才发的 ⇒
   "第几个采样 tick 时场景出现"取决于 RCON 到达时刻（进程调度）⇒ 实体在第 2 tick 就差了 3 只僵尸。
   **修法**：把 `function cava:scenario` 与 `tick sprint N` **都放进 `#minecraft:load` 链**
   （`tools/make-parity-datapack.ps1 -SprintTicks`）。第 0 个采样 tick 时场景已在位。
2. **自然动力学没落定**：`minecraft:falling_block#27 @(72.50,-33.00,64.50)` —— 世界里有没落定的沙/砾
   （z=64 → chunk (4,4)，**不是平台**），"落在哪一 tick"本身带随机 ⇒ `w` 差 533 个 tick。
   **修法**：base 快照必须是"跑热并保存过"的世界（`-PrepareWorld -SettleTicks 1200`：
   chunky 预生成 → `tick unfreeze` + `tick sprint 1200` → `save-all flush` → 快照），
   并把场景的 `randomTickSpeed` 钉成 0。

#### 4.1.2 第二轮（修好跑法之后）

三腿全部 `LEG OK（600 tick）`、`行=601 tick行=600`、无 ERROR、日志有「轨迹落盘」。报告：

    ================ 确定性前置：同一配置（native off）跑两次 ================
    DIFF FOUND over 600 ticks
      首个差异 tick=17
        tick 17 字段[p,x]  p: 'cbf29ce484222325' != 'e1c9dd9d7fa306cd'; x: 'ent=5;paths=0' != 'ent=5;paths=1';
      逐字段差异 tick 数: p=515 x=583        ← **w 一次都没差**
      定位：tick 17 实体 minecraft:item#2 哈希不同  a=d9c16ab479fc5fc3 (71.16,71.54,2.73)  b=42c294af88bc41cd (70.71,72.13,2.45)
      定位：tick 17 实体 只在 a 出现: minecraft:zombie#15 (64.50,71.00,3.50)
      定位：tick 17 实体 只在 b 出现: minecraft:zombie#29 (64.50,71.00,3.50)
      定位：tick 17 路径 只在 b 出现: minecraft:zombie#11 nodes=11

    ================ native on vs off ================
    DIFF FOUND over 600 ticks
      首个差异 tick=7
        tick 7 字段[p,x]  p: 'cbf29ce484222325' != '1fa85046457c5b01'; x: 'ent=5;paths=0' != 'ent=5;paths=1';
      逐字段差异 tick 数: p=242 x=594         ← **w 一次都没差**
      定位：tick 7 实体 minecraft:item#2 哈希不同  a=3f212a3df320c29c (70.98,72.46,2.54)  b=0118375860918392 (70.77,72.36,3.10)
      定位：tick 7 实体 只在 a 出现: minecraft:zombie#15 (64.50,71.00,3.50)
      定位：tick 7 实体 只在 b 出现: minecraft:zombie#16 (64.50,71.00,3.50)

##### 结论（**按子系统分开说，不要混成一句"有差异"**）

- **方块层：通过。** 两次比对里 `w` **都是零差异**（逐字段差异表里根本没有 `w`）——
  600 tick 内**红石装置**（脚本驱动的 `cava:tick_loop` 翻转 → 红石线 → 推活塞 + 灯 + 比较器）
  与整个扫描盒的方块状态逐 tick 一致：既在"同一配置两次"下一致，也在 native on/off 下一致。
- **实体/路径层：不通过，根因已定位且与 native 无关**：
  1. **实体 id 不是稳定可观测量**：同一实体在 a 里是 `zombie#15`、b 里是 `zombie#29`（坐标相同）——
     `minecraft:item#2` 的创建时机不同 ⇒ **id 计数器整体平移**。本流的 `e` 哈希与明细键都带 id，
     所以 id 漂移被记成"实体差异"。**修法（下一轮）**：把 id 从哈希与键里去掉，键改用 `类型@坐标`。
  2. **mob 的寻路时机由 mob 随机数决定**（`zombie#11 nodes=11 只在 b 出现`）—— AI 驱动的寻路
     "什么时候想起来要寻路"本身不确定，与 captain 的结论一致。这一层要**脚本驱动**才有确定性：见 §7.3。
- **门禁按设计生效**：确定性前置不通过时 `parity-diff.ps1` 以 exit=3 结束并打印
  「确定性前置不通过：同一配置两次都不同 ⇒ on/off 的结论不可用」，**没有**把 on/off 的差异记到 native 头上。

---

## 5. 整服层

    pwsh -File tools/parity-invariants.ps1 -Ticks 2000 -Native on  -Leg inv-on
    pwsh -File tools/parity-invariants.ps1 -Ticks 2000 -Native off -Leg inv-off

**为什么不是逐 tick 世界哈希**：见 §1。**必须是实时 tick（`/tick unfreeze`）**：`/tick sprint` 下 TPS/MSPT 没有意义。
**为什么不是逐 tick 世界哈希**：见 §1。**必须是实时 tick（`/tick unfreeze`）**：`/tick sprint` 下 TPS/MSPT 没有意义。
断言项：跑满 N tick、MSPT 均值、实时 TPS（= gametime 增量 / 墙钟秒，**不依赖 spark**）、
无 ERROR 级日志与真正的 Java 异常（`/WARN]` 一律不算）、native=on 时无「整体回退纯 Java」、无 `CAVA_ERR_*`、
`errors=0`、`takeovers==nativeCalls`、实体数在范围内、退出码 0、无 hs_err。

实测（两腿，各请求 1200 tick；实时 tick，非 sprint）：

| 腿 | 推进 tick | 墙钟 | MSPT 均值 | 实时 TPS | 实体数 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| inv-off（native off） | 1854 | 90.2 s | 0.4 ms | 20.55 | 59 | 除"退出码"外全部成立（见下） |
| inv-on（native on） | 1855 | 90.2 s | 0.4 ms | 20.56 | 59 | **PASS（所有不变量成立）** |

两腿都：无 ERROR 级日志、无真正的 Java 异常、native=on 腿无「整体回退纯 Java」、无 `CAVA_ERR_*`、
日志有优雅停服证据（`Stopping the server` / `Goodbye!`）、无 hs_err_pid、实体数一致（59）。

**第一次跑是 FAIL 的，四条都是我自己断言器的 bug**（如实记录，免得后人重踩）：
1. 把 `/WARN]` 里的 "Error loading class … ClassNotFoundException"（5 条）与
   "COM exception querying Win32_*"（4 条）当成了 ERROR —— 这两族是本整合包启动期的**已知无害噪声**；
2. native=off 那一腿**按设计**会打印「整体回退纯 Java」（横幅的"回退语义"行），
   而我把这条断言写成了无条件的 ⇒ off 腿必然误报；
3. `spark tps` 的 RCON 返回格式解析不到（TPS 样本 0 个）⇒ 改成用 `gametime/墙钟` 自算；
4. `$srv.Process.ExitCode` 在进程退出后可能取到 `$null`（Start-Process 的 Process 对象）；
   现在**以日志证据为准**（优雅停服 + 无 hs_err），退出码只在能取到时校验 —— inv-off 那一次就是被这条误判的，
   它的日志里同样有 `Stopping the server` / `Goodbye!`。

> 说明：这里的 TPS/MSPT 是**空载小世界**（无玩家、实体 59、扫描盒外无区块加载）的数字，
> 只能当"不退化"的不变量，**不能**当性能结论。

---

## 6. mod 组合矩阵

    pwsh -File tools/parity-matrix.ps1 -Profile vanilla -Ticks 300 -Prefix van-
    pwsh -File tools/parity-matrix.ps1 -Profile modpack -Ticks 300 -Prefix mp-

三档 = ①纯原版（`copy-mods.ps1 -Wave 0`：fabric-api + spark）+ cava；②+整合包（`-Profile full-minus-must-off`，33 jar）；
③= ② 的 native on/off。三档共用同一个世界快照（区块已预生成，换 mod 不会改变世界内容）。

**本轮实跑的是第 ②/③ 档**：§4.1.2 的三条腿就是整合包（148 个 mod 被 loader 加载、modset 指纹
`1240b4efa0af31ae`，见 trace 头）下的 native off×2 + on×1。
**第 ① 档（纯原版）只有脚本、没有实跑**（`testbed/parity` 里铺的就是整合包）—— 记在 §9 的未验证清单里。

---

## 7. ServerCore 被跳过之后：可观测差异（prompts/03 第 5 项）

### 7.1 结论

**单目标寻路下，跳过 ServerCore 的 `PathFinderMixin` 不可观测**；但它**不是**无条件等价 ——
它换的是**容器实现**，多目标时容器决定遍历顺序。

### 7.2 字节码证据（本机实跑）

    jar  = testbed/parity/server/mods/server-servercore-fabric-1.5.0+1.20.4.jar
    javap -p -v -c me/wesley1808/servercore/mixin/optimizations/misc/PathFinderMixin.class

`Mixin` 目标 = `findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)`
（= Yarn 的 `PathNodeNavigator.findPathToAny`）。实测注入点：

| # | 注解 | 目标 | 干了什么 |
| --- | --- | --- | --- |
| 1 | @Redirect | `Ljava/util/Set;stream()Ljava/util/stream/Stream;` | 换成 fastutil 流（`servercore$reduceStreams`） |
| 2 | @Redirect | `Ljava/util/stream/Collectors;toMap(...)` | 换成自拼 collector |
| 3 | @Redirect | `Ljava/util/stream/Stream;collect(...)` | 同上链式替换 |
| 4 | @ModifyVariable | `At(INVOKE, PathNavigationRegion.getProfiler(), BEFORE)` | `servercore$replaceMap`：把目标 Map 换成 `it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap`（字节码里 `new Object2ObjectOpenHashMap` + 遍历拷入） |
| 5 | @ModifyVariable | — | `servercore$noHashSet(int)`：把 `Sets.newHashSetWithExpectedSize(n)` 换成长度优化过的 HashSet 构造 |

兼容层自报的条目与之一致（同一份日志原文）：
`CAVA-COMPAT|v1|owner|pathfind|servercore|optimizations.misc.PathFinderMixin|native|auto|decided|PathFinder 体内 4x@Redirect + 2x@ModifyVariable；不可配置，但我们 HEAD 接管后体内补丁自然不执行`

**为什么单目标不可观测**：这些注入点改的是 `targetMap` / `found` 的**容器类型**与
`targets.stream()...collect(toMap(...))` 这条**构造管线**；映射语义不变，只有**多目标**时遍历顺序才会影响
"选哪条路径"（`found.stream().min(comparingInt(Path::getLength))` 在长度并列时取迭代序第一个）。
而 Cava 对多目标**直接不接管**（`PathfindHook.doTakeover` 第一句 `targets.size() != 1` 返回 null）⇒
多目标时我们不 HEAD 取消，ServerCore 照常执行，与 native off 完全一致；单目标时取消掉的那些补丁，
在单元素集合上是恒等的。

### 7.3 实测差分（单目标，脚本驱动）

用 P1 已有的 `/cava pathfind bench 2000`（固定场景 PathfindScenario，2000 次**真实** `findPathToAny`），
两腿各跑 3 次采样（带 id 回执校验；本流实测三次全部 `回执=OK`）：

    --- native off ---
    [cava/pathfind] 金丝雀 PASS：…（原版返回 Path(6 节点)）；canary=1 takeovers=0 nativeCalls=0 errors=0
    [cava/pathfind] BENCH id=1 ok=true n=2000 ns/op=99814.5 … avgNodes=6.00 nullPaths=0 canaryDelta=2000 takeovers=0
    [cava/pathfind] BENCH id=2 ok=true n=2000 ns/op=34359.6 … avgNodes=6.00 nullPaths=0 canaryDelta=2000 takeovers=0
    [cava/pathfind] BENCH id=3 ok=true n=2000 ns/op=30224.0 … avgNodes=6.00 nullPaths=0 canaryDelta=2000 takeovers=0
    --- native on ---
    [cava/pathfind] 接管条件全部满足（hook=true native=true probe=true bypassProfileGate=false probeTicks=600）
    [cava/pathfind] 金丝雀 PASS：…（原版返回 Path(6 节点)）；canary=1 takeovers=1 nativeCalls=1 errors=0 reasons={}
    [cava/pathfind] BENCH id=1 ok=true n=2000 ns/op=39963.8 … avgNodes=6.00 nullPaths=0 canaryDelta=2000 takeovers=2000 nativeCallsDelta=2000
    [cava/pathfind] BENCH id=2 ok=true n=2000 ns/op=13390.4 … avgNodes=6.00 nullPaths=0 canaryDelta=2000 takeovers=2000 nativeCallsDelta=2000
    [cava/pathfind] BENCH id=3 ok=true n=2000 ns/op=10743.1 … avgNodes=6.00 nullPaths=0 canaryDelta=2000 takeovers=2000 nativeCallsDelta=2000

**可观测差异：没有。** 两腿 `avgNodes=[6.00,6.00,6.00]` 完全一致、`nullPaths=0`、`errors=0`；
native on 腿 `takeovers=2000/2000`（每一次调用都被原生接管，hook 的 `canaryDelta=2000` 证明调用确实穿过注入点），
也就是说：**这一轮 2000 次单目标寻路里，ServerCore 的体内补丁一次都没执行，而结果与原版逐项一致**。
再叠加单元层"10000 组逐节点逐 float 位模式一致"，可以下这个结论：
**单目标路径下跳过 ServerCore 的 4x@Redirect + 2x@ModifyVariable 不改变可观测结果**；
多目标那一侧我们本来就不接管（§7.2），所以也不存在"跳过"。

**顺带一条性能观察（不是本流的验收项，按 P1 的方法学读）**：稳态（第 3 次采样）
`off 30224.0 ns/op vs on 10743.1 ns/op` ≈ **2.8×**；但三次采样是 99.8k→34.4k→30.2k（off）与 40.0k→13.4k→10.7k（on），
**强预热瞬态**，用均值会得出错误结论（P1 的教训）。样本量只有 3×2000、场景是单生物短路径，
**不要**把这条当成通用加速比。

**顺带确认的一件事实**（省得后人重复踩）：`easybot-fabric` 在专用服务端上每 ~5 秒打一条
`[EasyBotBridge-Jetty-n/ERROR]: 连接遇到错误: Connection refused`。它**无害**，但会让"日志里不许有 ERROR"
这类断言误报 —— 所有断言器都必须显式排除 `EasyBotBridge|BridgeClient|连接遇到错误|正在尝试重连`
以及 `/WARN]` 里的 `Error loading class|COM exception`。

---

## 8. 复现命令（全部实跑过）

    # 0) 构建（cava jar 要进测试服 mods/）
    $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; $env:TMP='J:\mc\Cava\build\tmp'; $env:TEMP=$env:TMP
    .\gradlew.bat build --console=plain --no-watch-fs --no-configuration-cache

    # 1) 单元层（一条命令，三条腿）
    pwsh -File tools/parity-unit.ps1

    # 2) 场景层（私有测试服）
    #    一次性：把 testbed\server 复制成 testbed\parity\server（server.jar/libraries/mods）
    pwsh -File tools/parity-scenario.ps1 -Root testbed\parity -PrepareWorld -ChunkyRadius 170
    pwsh -File tools/parity-scenario.ps1 -Root testbed\parity -Prepare -NoRestore -SnapshotOnly
    pwsh -File tools/parity-diff.ps1 -Ticks 600

    # 3) 整服层
    pwsh -File tools/parity-invariants.ps1 -Ticks 2000 -Native on -Leg inv-on

    # 4) mod 矩阵
    pwsh -File tools/parity-matrix.ps1 -Profile vanilla -Ticks 300 -Prefix van-

---

## 9. 未验证 / 卡点（诚实清单）

1. **`bt` / `nt` 没有采集**：方块 tick / 邻居更新计数需要注入点，而 `cava.hook` / `cava.mixin` 不归本流所有。
   红石层目前只有 `w`（方块状态）覆盖 —— 同一 tick 内多次翻转后回到原状这类差异**看不到**。
2. **场景层只覆盖主世界**（§1.1），且平台东端 x=80..84 在 radius=4 的盒外。
3. **`p` 是导航状态而不是"调用日志"**（§2），调用级节点序列只有单元层覆盖。
4. **多目标寻路的差分未做**：`PathfindHook` 对多目标不接管，本流场景也全是单目标。
5. **ServerCore 的结论 = 字节码层面 + 单目标实测**，没有做"多目标下容器顺序差异"的正面对照实验（我们本来就不接管多目标）。
6. **`gradlew test` 里的 10000 组**没有在 CI runner 上跑过（本仓库当前没有 CI）。
7. **实体 id 不稳定**（§4.1.2 第 1 条）：`e` 的哈希与明细键都带 `Entity.getId()`，同一实体的 id 会因
   "之前创建过多少实体"而整体平移。**未修**（改法已写明：id 从哈希/键里去掉，键用 `类型@坐标`）。
8. **场景层的实体/路径比对在第二轮仍未通过**（原因见 §4.1.2）；方块层已逐 tick 零差异。
   "实体层零差异"这句话本轮**不能说**。
9. **mod 矩阵的纯原版档**只有脚本、没有实跑（`testbed/parity` 里铺的是整合包 33 jar）；
   整合包档 = §4.1 的三条腿。
10. **本轮单元层结论是在两代 ABI 上各测一次**：P1 的 9 结构体 DLL（`java_sum=0x6975cbf9`）与
   实体 ABI 冻结后的 14 结构体 DLL（`java_sum=0x1c12265e`），两次都是 10000 组 ZERO DIFF。
   但**路径内核若在实体流里被动过，本流的数字就不再代表当前 HEAD** —— 复跑一条命令即可（`tools/parity-unit.ps1`）。
