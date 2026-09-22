# Cava 差分夹具与黄金轨迹语料库（操作手册）

> **这份文档解决什么**：P0 的验收要求「native 关 / 开两次运行的黄金轨迹完全一致」。
> 本文给出**可直接照做**的采集步骤、每条通道的**确切开关与字段**、以及**确定性前置条件**。
> **黄金轨迹的格式以 `docs/CAVA-工程接口契约.md` §4 为准，本文不另立格式。**
>
> **证据规矩**：本文所有「字段/默认值/注入点」都标了来源（jar 解包目录 / refmap / javap / 本机 mappings）。
> 凡是我没核实的，一律写 **「未验证」**，不要当成结论。
>
> **来源（本机可复现）**：
> - Carpet 1.4.128 解包目录：`.cava-research/batchB/carpet/`（`fabric.mod.json` → `carpet 1.4.128+v231205`）
> - TIS 1.82.3 解包目录：`.cava-research/batchB/tis/`（`fabric.mod.json` → `carpet-tis-addition 1.82.3`）
> - TIS 规则全量 dump：`.cava-research/batchB/rules-tis-full.json`（从 `CarpetTISAdditionSettings` 常量池解析）
> - 本机 Yarn 映射：`C:\Users\郁小悟520\.gradle\caches\fabric-loom\1.20.4\net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\mappings.tiny`
> - 反汇编命令见 `docs/CAVA-dev-toolbox.md` §2（`javap -p -c`，**不要加 --enable-preview**）

---

## 1. 一句话总览：两条腿走路

| 用途 | 用什么 | 为什么 |
| --- | --- | --- |
| **逐 tick 自动比对（CI / 验收）** | **我们自己的黄金轨迹**（契约 §4 的 NDJSON） | 只有它给出逐 tick 的世界哈希 / 实体位模式 / 路径节点序列，且能自动化 |
| **人工定位与第一手观测** | TIS `/log movement`、TIS `/log microTiming`、Carpet `/log pathfinding` | 现成工具，能告诉你「第几 tick、哪个坐标、哪次事件」，但**都是采样/过滤式的，不是全量位模式** |

> ⚠️ **最容易踩的认知坑**：TIS / Carpet 的 logger 是**给玩家看的输出**（订阅制、有阈值过滤、可能只打印异常），
> **不能直接当黄金轨迹用**。它们的正确位置是「黄金轨迹报出差异之后，用它去定位原因」。

---

## 2. 三层测试：最小可执行形态与通过标准

| 层级 | 最小可执行形态 | 通过标准 | 现状 |
| --- | --- | --- | --- |
| **单元层** | `.\.gradlew.bat test`（JUnit；`build.gradle` 已配 `useJUnitPlatform()` + `maxParallelForks = 1`，测试 JVM 带 `--enable-preview --enable-native-access=ALL-UNNAMED`） | 原生纯函数 vs Java 纯函数，**百万级随机 + 边界输入位模式 100% 一致** | ❓ 未验证（Gradle 构建当前受阻，见 `docs/CAVA-build.md` §3） |
| **场景层** | 固定存档 + 脚本化操作（`/tick freeze|step` + Carpet `/player` 假人 + `/setblock`/`/fill` 或 scarpet）→ 采黄金轨迹 | 逐 tick 完全一致 | ❓ 未验证（需要真测试服，P0-E） |
| **整服层** | 同一存档跑 6000–20000 tick，native off/on 两次 | 输出必须明确打印 `ZERO DIFF over N ticks` | ❓ 未验证（比对器归 P0-C） |

约定形态（契约 §4.3，任务归 P0-C，P0-A 注册 Gradle 任务）：

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'
& '.\gradlew.bat' parityDiff -Pcava.natives=off,on -Pcava.ticks=6000
```

运行期开关（契约 §4.1，已冻结）：`-Dcava.native.enabled`、`-Dcava.parity.trace`、`-Dcava.parity.ticks`、`-Dcava.parity.label`。

---

## 3. 黄金轨迹 = 唯一权威格式（引用契约，不重抄）

- 逐 tick 一行 NDJSON：`<dir>/trace-<label>.ndjson`；第一行是头（`"t":"h"`），之后每 tick 一行键固定。
- 哈希一律 **FNV-1a 64**；`w` 世界哈希、`e` 实体位模式、`p` 寻路节点序列、`bt`/nt 事件计数。
- **判定顺序**：① 先证明「同一存档连续两次运行逐 tick 一致」（确定性）→ ② 再比对 native off/on。
  ①不过，②没有意义。

---

## 4. 三条采集通道的逐条操作步骤

> 前置：**需要一个真 Fabric 服务端**（`testbed/server/`，部署步骤见 `docs/CAVA-服务器模组清单.md` §7）。
> 所有 `/log` 命令都是**玩家维度订阅**：执行者必须是 OP 玩家（或假人），且 logger 本身要处于可用状态。

### 4.1 通道 A：TIS `/log movement`（实体差分核心）

**① 先确认开关的默认值**（这是任务书要求的第一件事）：

| 规则 | 默认值 | 语义 | 证据 |
| --- | --- | --- | --- |
| `loggerMovement` | **`ops`** | 「移动记录器的开关 / 权限等级需求」；`ops` = 只对 OP 开放订阅，**未订阅时不产生任何日志** | `rules-tis-full.json` 第 1424–1439 行（`descEn: The switch / permission requirement of movement logger`） |

所以默认状态下：**logger 常驻但不输出**，需要 OP 玩家订阅。

**② 打开 / 订阅**

```mc
/carpet loggerMovement true          # 若要以非 OP 身份订阅，或想强制打开（默认 ops 时可省略）
/log movement                        # 订阅，使用默认 option
/log movement non_zero               # 只记录「实际位移与原始位移不一致」的实体
/log movement non_zero:@e[type=creeper,distance=..5]   # 带实体过滤
```

> 建议 option 的**字节码常量池实测值**（`MovementLogger.getSuggestedLoggingOption`）：
> `non_zero`、`non_zero:@a[distance=..10]`、`@s`、`non_zero:@e[type=creeper,distance=..5]`、`Steve`。

**③ 记录点：HEAD/RETURN + 三处修改（refmap + javap 实测）**

| 记录点 | TIS 源码里的方法名（mojmap 风格） | intermediary | **Yarn 1.20.4（写 mixin 时用这个）** | 记录内容 |
| --- | --- | --- | --- | --- |
| HEAD | `Entity.move` | `class_1297.method_5784(Lclass_1313;Lclass_243;)V` | **`Entity.move(MoverType, Vec3)`** | 创建 Tracker：实体、**起始 pos**、world、MoverType、**originalMovement** |
| RETURN | 同上 | 同上 | 同上 | `finalize()` → 打印「实际移动」与全部 modification |
| 修改点 1 | `Entity.limitPistonMovement` | `method_18794` | **`Entity.adjustMovementForPiston(Vec3)`** | `MovementModification.PISTON`（lang：`Piston Limit`） |
| 修改点 2 | `Entity.maybeBackOffFromEdge` | `method_18796` | **`Entity.adjustMovementForSneaking(Vec3, MoverType)`** | `MovementModification.SNEAKING`（lang：`Sneaking`） |
| 修改点 3 | `Entity.collide`（`@ModifyVariable`） | `method_17835` | **`Entity.adjustMovementForCollisions(Vec3)`** | `MovementModification.COLLISION`（lang：`Collision`） |

> ⚠️ **命名坑（重要）**：TIS 的 mixin 注解字符串用的是 **Mojang 官方名**（`collide` / `limitPistonMovement` / `maybeBackOffFromEdge`），
> 而 **Yarn 1.20.4 的名字完全不同**（见上表右列，`mappings.tiny` 实测）。
> 写我们自己的 mixin 时**必须用 Yarn 名**；查名字一律走 `docs/CAVA-dev-toolbox.md` §1 的 `tools/mapquery.cjs`。

**④ 上报字段（lang `en_us.json` 实测）**

    header         : "%1$s tried to move for %2$s"
    header_details : "Movement type: %1$s, Game time: %2$s"
    delta          : "delta"
    due_to         : " due to %1$s"
    footer         : "%1$s actually moved for %2$s"
    movement_type.*: Self logic / Player action / Piston / Shulker box / Shulker

Tracker 内部字段（`MovementLogger$Tracker` javap 实测）：
`entity`、`originalPos`、`world(class_3218 = ServerWorld)`、`movementType(class_1313 = MoverType)`、
`originalMovement`、`currentMovement`、`modifications: List<ModificationRecord>`。

**⑤ 必须知道的过滤阈值（决定它能不能当证据）**

`MovementLogger$Tracker.MIN_DIFFERENCE = 1.0E-12`（字节码 `ldc2_w 1.0E-12d` 实测）。
→ 位移差异小于 1e-12 的移动**不会上报**。**所以 `/log movement` 是「异常移动探测器」，不是全量位模式轨迹。**
逐位一致（bit-exact）的证据必须来自我们自己的黄金轨迹 `e` 字段。

---

### 4.2 通道 B：TIS `/log microTiming`（红石差分核心）

**① 默认值（必须先开，否则什么都看不到）**

| 规则 | 默认值 | 说明 | 证据 |
| --- | --- | --- | --- |
| `microTiming` | **`false`** | 「启用微时序记录器的功能」，默认**关** | `rules-tis-full.json` 第 1468–1490 行 |
| `microTimingTarget` | **`MARKER_ONLY`** | **默认只记录被染料标记的方块！** | 第 1518–1537 行 |
| `microTimingDyeMarker` | `true` | 允许手持染料右击标记目标 | 第 1493–1515 行 |
| `microTimingTickDivision` | `TickDivision.WORLD_TIMER` | 微时序的「tick 归属」划分方式 | 第 1540+ 行 |

枚举实测（javap）：
- `MicroTimingTarget` = `LABELLED` / `IN_RANGE` / `ALL` / `MARKER_ONLY`（另有 `IN_RANGE_RADIUS` 常量）
- `MicroTimingLogger$LoggingOption` = `MERGED` / `ALL` / `UNIQUE` / `DEFAULT`
- `EventType` = `ACTION_START` / `ACTION_END` / `ACTION` / `EVENT`

**② 打开 / 订阅（红石装置全量观测的推荐组合）**

```mc
/carpet microTiming true             # 先开功能（默认 false）
/carpet microTimingTarget all        # 默认 MARKER_ONLY 只记染料标记的方块；全量必须改 all
/log microTiming all                 # 订阅（MERGED / ALL / UNIQUE）
```

想只盯某个装置时（更接近默认用法、噪声小得多）：

```mc
/carpet microTimingTarget marker_only
# 手持染料右击目标方块（microTimingDyeMarker=true 时可用）
```

**③ 事件类型（`logging/loggers/microtiming/events/` 目录实测）**

    BlockStateChangeEvent      方块状态变化（含 PropertyChange 明细）
    BlockReplaceEvent          方块被替换
    DetectBlockUpdateEvent     侦测到方块更新（emit / detected / started / ended）
    EmitBlockUpdateEvent       发出方块更新
    EmitBlockUpdateRedstoneDustEvent  红石粉发出的更新（**红石专用**）
    ScheduleBlockUpdateEvent   方块更新被排期
    ScheduleTileTickEvent      方块 tick 被排期
    ScheduleBlockEventEvent    方块事件被排期
    ExecuteTileTickEvent       方块 tick 执行
    ExecuteBlockEventEvent     方块事件执行（带 FailInfo / FailReason）
    PistonComputePushStructureEvent  活塞推动结构计算（带 Result）

**④ `setBlockState` 的 flags 位语义（lang 实测，红石/镜像差分极有用）**

```
bit 0  emits block updates          bit 5  drops items in block entity
bit 1  updates listeners            bit 6  caused by piston
bit 2  updates client listeners     bit 7  emits light updates
bit 3  immediately redraws on client bit 8  triggers side effects
bit 4  emits state updates
```

**⑤ 字段与阶段**

lang 实测的**公共字段键**：`gametime`、`dimension`、`position`、`depth`、`event_source`、`order`、`priority`、
`block`、`fluid`、`entity`、`id`、`type`、`return_value`、`successful`/`failed`。
事件来源类：`EventSource$BlockEventSource` / `EventSource$FluidEventSource`（javap 实测）。
阶段（`TickStage` + `tickphase/*` 子阶段类）覆盖 `tile_tick` / `block_event` / `entity` / `chunk_tick` /
`tile_entity` / `randomtick` / `player_action` / `network` / `scarpet` … 等（lang `stage.*` 实测）。

> ⚠️ **未验证**：`pos` / `depth` / `event_source` 这三个键我核实到的是 **lang 键**与 **EventSource 类**；
> 「每条消息具体打印哪几个字段」由各 message 子类决定，**没有逐条 javap 验证**。
> 真机上第一次采集时用 `/log microTiming all` 打一条出来对照即可确认。

---

### 4.3 通道 C：Carpet `/log pathfinding`（寻路第一手观测）

**① 订阅与参数（字节码实测：参数是「毫秒阈值」）**

```mc
/log pathfinding        # 默认阈值 20 ms
/log pathfinding 5      # 只报耗时 > 5 ms 的寻路
/log pathfinding off    # 取消
```

证据：`carpet.logging.LoggerRegistry` 的静态初始化里注册 `pathfinding`，**默认 option 字符串 `"20"`**，
可选 `"2" / "5" / "10" / "20"`；`PathfindingVisualizer.slowPath(Entity, Vec3, float, boolean)` 用 `Integer.parseInt(option)` 解析（javap 实测）。

**② 它到底包了哪个方法（与清单里的旧描述不同，以 jar 为准）**

- 门控字段：`LoggerRegistry.__pathfinding`（`boolean`）——关闭时**完全不介入**（mixin 第一句就 if 短路）。
- 计时：`System.nanoTime()`（**真实时间**，不是游戏刻）。
- 被包的方法：`EntityNavigation.findPathTo`，描述符 `(Ljava/util/Set;IZI)Lnet/minecraft/class_11;` = **intermediary `method_35142`**
  （`mappings.tiny` 实测：`m (Ljava/util/Set;IZI)Lefg; a method_35142 findPathTo`）。
  mixin 类 `carpet/mixins/PathNavigation_pathfindingMixin` 里同时存在 `pathToBlock` / `pathToEntity` 两个包装方法。
- ⚠️ **纠正 `docs/CAVA-服务器模组清单.md` 第 5 节的旧描述**：那里写的是「只 `@Redirect` `EntityNavigation.createPath`(`method_6348`/`method_6349`)」。
  1.4.128 的实际字节码包的是 **`findPathTo`(`method_35142`)**（`createPath` 的 `method_6348/6349` 是同类的另外两个重载）。
  **写兼容层/冲突表时按 `method_35142` 处理。**

**③ 能做与不能做**

| 能做 | 不能做 |
| --- | --- |
| 定位「哪个实体在哪个坐标寻路超时」 | 给出逐节点路径（它只打印耗时与目标点） |
| 快速发现性能退化 / 卡死的路径查询 | 当位模式证据（`nanoTime` 本身不确定，每次都不一样） |

→ **寻路逐节点比对只能靠黄金轨迹的 `p` 字段**（契约 §4.2）。

---

### 4.4 通道 D：原版 `/tick`（1.20.4 自带，Carpet 没有）

**证据（本机 `mappings.tiny` 第 29892 行起）**：

    c	alk	net/minecraft/class_8916	net/minecraft/server/command/TickCommand
    f	...	field_46925	MAX_TICK_RATE
    f	...	field_46926	DEFAULT_TICK_RATE_STRING
    m	(...)I	method_54690	executeQuery
    m	(F)I	method_54691	executeRate
    m	(I)I	method_54692	executeSprint
    m	(Z)I	method_54693	executeFreeze
    m	(J)Ljava/lang/String;	method_54686	format
    m	(...)V	method_54687	register

→ `/tick` 是**原版命令**：`/tick freeze`、`/tick unfreeze`、`/tick step [n]`、`/tick sprint [n]`、`/tick rate <x>`、`/tick query`。
**Carpet 1.4.128 自己没有 TickCommand → 不存在 `/tick warp`**（与清单一致）。

**推荐用法（场景层的确定性步进）**：

```mc
/tick freeze                                  # 冻结世界，安全布场
/setblock … /fill … /summon …                 # 布置装置（freeze 期间不推进）
/tick step 200                                # 精确推进 200 刻
/tick unfreeze                                # 恢复正常
/tick rate 20                                 # 固定 20 TPS（默认就是 20；显式写死避免歧义）
```

> ⚠️ **未验证**：`/tick sprint` 与 `/tick rate` 在大数值下对 1-tick 脉冲类装置的语义影响没实测；
> 采黄金轨迹时建议**全程 `rate 20` + `step`**，不要 `sprint`（它追求的是「跑得快」，不是「跑得准」）。

---

## 5. 确定性前置条件清单（**不满足就白测**）

| # | 条件 | 怎么做 | 证据/说明 |
| --- | --- | --- | --- |
| 1 | **固定种子** | `server.properties` 里 `level-seed=<固定值>`；**且用已经生成好的存档**（避免生成阶段差异） | 契约 §4.2 的 `seed` 要写进 trace 头 |
| 2 | **单线程** | 不要开 c2me 的线程化/异步区块；确认 `c2me` 配置与基线一致 | c2me 会把区块任务插进 tick 中段（清单 §4） |
| 3 | **不依赖 wall-clock** | 任何参与比对的量都必须是**游戏刻 / 位模式**，不能用 `System.nanoTime`/`currentTimeMillis` | ⚠️ Carpet pathfinding logger 用的就是 `nanoTime` → **它的输出天然不确定**，只能观测 |
| 4 | **固定 randomTickSpeed** | `/gamerule randomTickSpeed 3`（写死） | 随机刻会改变世界哈希 |
| 5 | **关自动保存** | `/save-off`（采集结束再 `/save-all flush`）；`server.properties` 的自动保存相关项保持一致 | 契约 §4.2「关自动保存」 |
| 6 | **固定 tick 速率** | `/tick rate 20`，用 `/tick step N` 推进 | §4.4 |
| 7 | **重量级 mod 按三档关掉** | 必须关：BlueMap / Axiom / Ledger(+databases) / Geyser / AutoModpack / RandomTP / MCMOD；建议关：Floodgate / Syncmatica / voicechat / EasyAuth（**测试账号必须已登录**） | `docs/CAVA-服务器模组清单.md` §5 |
| 8 | **记录 mod 指纹** | trace 头 `mods` 字段写 modset 指纹（modid+version 排序哈希） | 契约 §4.2 |
| 9 | **记录会改变行为的规则开关** | 特别是 Carpet `fastRedstoneDust`（**用户已开**）、`optimizedTNT`、`lagFreeSpawning`；TIS `redstoneDustRandomUpdateOrder`（最毒）、`totallyNoBlockUpdate`、`updateSuppressionSimulator`、`updateSkippingSimulator`、`instantBlockUpdaterReintroduced`、`repeaterHalfDelay`、`dustTrapdoorReintroduced`、`optimizedFastEntityMovement`、`optimizedHardHitBoxEntityCollision` | 清单附录 A.4 第 5 节（**默认全关，一旦有人开就会毁掉基准**） |
| 10 | **先证明「连续两次运行一致」** | 同一存档、同一 mod、native 关闭，跑两次，逐 tick 比对 | 契约 §4.2 末段；**这是整条链的前提** |

> **语义基准提醒**：本项目基准 = **「同一整合包、native 关闭时」**，不是纯原版。
> 用户已开 `fastRedstoneDust` → **P3 的红石基准是 Carpet 的算法**（详见 `docs/CAVA-platform-and-compat.md` §2.3）。

---

## 6. contraption 语料库（P3 红石用）

**通用判定口径**：每个装置 = 「装置描述 + 预期行为 + 怎么判定一致」。
判定一律是**两条**：
1. **黄金轨迹**：逐 tick 世界哈希 `w`（＋实体 `e`）完全一致（同 tick 内的中间态它看不到）；
2. **microTiming 事件序列**：同一 tick 内的**事件顺序**一致（`pos` + `depth` + `event_source` + 事件类型）。
   —— 只比 `w` 会漏掉「同一刻多次翻转后回到原状」「更新顺序不同但最终状态相同」这类差异，**红石机器恰恰死在这里**。

| # | 装置 | 描述（建议布法） | 预期行为 | 一致性判定 |
| --- | --- | --- | --- | --- |
| 1 | **中继器时钟** | 两个中继器互指成环（如 (0,0,0) 朝 +X → (2,0,0) 朝 -X），各 delay=1 | 稳定周期振荡，周期 = 2×(delay+1) 刻 | 逐 tick `w` + 中继器 `POWERED`/`DELAY` 状态；周期偏移 1 刻即失败 |
| 2 | **活塞门** | 4 活塞 + 中继器延时链，`/setblock` 输入一次脉冲 | 活塞按固定顺序伸出/收回，方块位移序列固定 | microTiming 的 `ExecuteBlockEventEvent`（活塞事件）+ `PistonComputePushStructureEvent`（推动结构结果）+ 逐 tick `w` |
| 3 | **比较器逻辑** | 比较器减法模式 + 红石线衰减链 + 容器信号 | 输出强度按固定规则变化 | microTiming 的比较器更新事件 + `w`（红石线 power 是方块状态的一部分） |
| 4 | **instant wire（瞬时线）** | 利用更新顺序的 0 刻传输（活塞/红石线组合，具体布法随版本） | 同一游戏刻内传播完成 | **只看 `w` 会漏**：必须比 microTiming 事件序列（`depth` 与顺序） |
| 5 | **1-tick 脉冲** | 短脉冲发生器（如观察者 + 中继器）打在中继器/活塞上 | 脉冲被捕获或被吞掉的**边界行为**固定 | 输出端方块状态的逐 tick 序列；边界（2 刻 vs 1 刻）必须一致 |
| 6 | **更新抑制边界** | 依赖「更新链深度超限被吞」的装置 | 抑制发生/不发生的**临界位置**固定 | ⚠️ 1.20.4 原版仍有链深上限（`ChainRestrictedNeighborUpdater`）；要复现老版本抑制还得开 TIS 的 `updateSuppressionSimulator`（**默认 false**）。判定 = 抑制点坐标 + microTiming 事件断点 |
| 7 | **侦测器链** | N 个侦测器首尾相接 | 每 2 刻（视版本）传播一格，末端输出时序固定 | 逐 tick `w` + microTiming 的 `DetectBlockUpdateEvent`/`EmitBlockUpdateEvent` 顺序 |

### 6.1 用什么描述装置（可行性排序）

| 方案 | 可行性 | 说明 |
| --- | --- | --- |
| **scarpet 脚本**（`/script run …`） | ✅ **推荐** | Carpet 自带 scarpet（`carpet/script/**` 在 jar 里实测存在，含 `value`/`api`/`command` 等 10+ 子包）；可在脚本里 `setblock`、读方块、按 tick 采样、写文件——**最适合「装置 + 采集」一体化** |
| **命令脚本**（`/setblock` / `/fill` + `/tick step`） | ✅ 可用 | 零依赖，配合 `/tick freeze` 精确布场；缺点是读状态要靠 logger |
| **结构文件 `.nbt`** | ⚠️ 未验证 | 需要在客户端/litematica 里做好再导出，还要 `/place template` + 数据包；**本仓库没有现成结构文件**，短期内不划算 |

> **建议的落地形态**：`tools/` 下放每个装置的 scarpet 脚本（`.sc`）+ 一段「布场命令 + 期望事件序列」的说明；
> 采集时用 `/tick freeze` → 跑脚本布场 → `/tick step N` → 收黄金轨迹 + microTiming 输出。
> **⚠️ 未验证**：以上流程没有在真服务器上跑过（当前没有可用测试服，见 §7）。

---

## 7. 未验证 / 卡点清单（诚实版）

| 项 | 状态 | 卡在哪 / 需要什么 |
| --- | --- | --- |
| 三条 logger 通道的实际输出 | ❓ 未验证 | **需要一个真 Fabric 服务端**（`testbed/server/`）。截至本文写作，仓库里**没有已部署的服务端**（`docs/CAVA-launch-notes.md` §3：全盘没有这套整合包的服务端根目录） |
| TIS 规则的设置入口是 `/carpet <rule>` 还是 `/tis <rule>` | ❓ 未验证 | `CarpetTISAdditionSettings` 里有 `public static final String TIS` 常量与 `commands/CommandRegister|CommandExtender`，说明存在 TIS 自己的命令树；**确切字面量要在真服务器上用 tab 补全确认**（不要照抄本文） |
| microTiming 每条消息具体打印哪些字段 | ❓ 未验证 | 已核实 lang 键与 EventSource 类；逐条 message 子类未 javap |
| Carpet 假人在无客户端条件下能否稳定收到 `/log` 输出并落盘 | ❓ 未验证 | 建议优先用「我们自己的黄金轨迹」做自动采集，logger 只做人工定位 |
| scarpet 装置脚本 | ❓ 未验证 | 需要真服务器；脚本本身也要在 P3 之前写好 |
| `/tick sprint` / `rate` 对 1-tick 脉冲的影响 | ❓ 未验证 | 采集时不要用 sprint（§4.4） |
| `parityDiff` 一条命令 | ❓ 未验证 | Gradle 任务归 P0-A、比对器归 P0-C；当前 Gradle 构建受阻（`docs/CAVA-build.md` §3） |

---

## 8. 相关文档

- `docs/CAVA-工程接口契约.md` §4 —— **黄金轨迹格式（唯一权威）**、§4.1 开关、§4.3 一条命令。
- `docs/CAVA-dev-toolbox.md` —— 查映射 / javap 读原版 / 联网走 node（**工具与坑的权威落盘处**）。
- `docs/CAVA-服务器模组清单.md` §5（性能基线三档处理）+ §7（测试服部署清单）。
- `docs/CAVA-build.md` —— 构建手册（含 Gradle/CMake 当前卡点）。
- `docs/CAVA-p0-acceptance.md` —— P0 验收台账（每条的证据或卡点）。
