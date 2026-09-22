# Cava 差分测试与黄金轨迹（P0 差分测试流 / prompts 03）

> **本文件是本流的结论落盘处**。所有数字都是本机实跑出来的；没跑的一律写「未验证」。
> 复现命令见 §8。**不要照抄过时结论**：本文件同时记录了两条"文档与实测不符"的更正。

---

## 0. 一句话结论

| 层 | 形态 | 结果 |
| --- | --- | --- |
| **单元层** | Java → FFM → **C ABI** → 原生内核 vs oracle 参照实现，10000 组向量 | **ZERO DIFF over 10000 cases**（本机 ~4 s） |
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
输出 `负控制结果: PASS（检出 126 处差异，首个 nodeCount）`。

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

（占位：本轮三腿跑完后回填真实输出）

---

## 5. 整服层

    pwsh -File tools/parity-invariants.ps1 -Ticks 2000 -Native on  -Leg inv-on
    pwsh -File tools/parity-invariants.ps1 -Ticks 2000 -Native off -Leg inv-off

**为什么不是逐 tick 世界哈希**：见 §1。**必须是实时 tick（`/tick unfreeze`）**：`/tick sprint` 下 TPS/MSPT 没有意义。
断言项：跑满 N tick、MSPT 均值、TPS 最低值、无 Exception/ERROR（**显式排除 easybot 桥接噪声**，见 §7.3）、
无「整体回退纯 Java」、无 `CAVA_ERR_*`、`errors=0`、`takeovers==nativeCalls`、实体数在范围内、退出码 0、无 hs_err。

（占位：本轮实跑结果）

---

## 6. mod 组合矩阵

    pwsh -File tools/parity-matrix.ps1 -Profile vanilla -Ticks 300 -Prefix van-
    pwsh -File tools/parity-matrix.ps1 -Profile modpack -Ticks 300 -Prefix mp-

三档 = ①纯原版（`copy-mods.ps1 -Wave 0`：fabric-api + spark）+ cava；②+整合包（`-Profile full-minus-must-off`，33 jar）；
③= ② 的 native on/off。三档共用同一个世界快照（区块已预生成，换 mod 不会改变世界内容）。

（占位：本轮实跑结果）

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

### 7.3 实测差分（单目标）

（占位：待 §4.1 与 bench 的 `avgNodes` 回填）

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
