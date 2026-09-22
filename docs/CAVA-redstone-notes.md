# Cava 红石子系统归属（P3）—— 源码比对、实测与显式决策

> 本文是 **P3 红石轮阶段 1** 的结论落盘处。**每一个类名/方法名/注入器/常量都来自本机 jar 与字节码，
> 复现命令逐条给出。凡未实测的一律写「未验证」。**
>
> 相关：`prompts/06-P3-红石.md`、`docs/CAVA-hook-points.md` §1 第 13/13b/14/15/16 行、
> `docs/CAVA-compat-notes.md` §1、`docs/CAVA-parity-fixtures.md` §6、`docs/CAVA-server-*`（规则清单 A.4 第 5 条）。

---

## 0. 一句话结论

**决策：红石子系统继续「让位」（owner = mod），Cava 不接管任何红石方法。**

理由不是"难"，而是本机证据显示**接管点本身是空的**：

> 在 `fastRedstoneDust = true`（= 用户当前的配置）时，`RedstoneWireBlock.update`(`method_10485`)
> **一次都不会被调用** —— Carpet 在它的**三个（也是全部）调用点**上做了 `@Redirect`，
> 把控制流整体引到 `RedstoneWireTurbo`。任何打在 `method_10485` 上的原生钩子
> 都会是**永久静默失效**，而不是"慢一点"。

外加三条独立理由（§3）：算法内层是 Java 世界写入 + 任意方块虚调用、算法**故意使用随机数**决定更新顺序、
以及唯一可用的验收通道（逐 tick 世界哈希 `w`）**在结构上看不到同 tick 内的顺序差异**——
而本次实测还发现现成夹具**根本区分不出规则开/关**（§5）。

---

## 1. 占用者逐条核实（jar + refmap + 字节码；不是照抄任务书）

任务书点名的三个占用者，逐条核对结果如下（`method_10485` = Yarn `update` = Mojang `updatePowerStrength`；
`class_2457` = `RedstoneWireBlock`，与本项目 mappings 一致）：

| # | 占用者 | mixin 类（jar 内路径） | 注入器（字节码实读） | 运行期门控 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 1 | **Carpet 1.4.128+v231205** | `carpet/mixins/RedstoneWireBlock_fastMixin` | `@Inject(method="updatePowerStrength", at=HEAD, cancellable=true)` + **3 个 `@Redirect`**（`onPlace`/`onRemove`/`neighborChanged` 的 `updatePowerStrength` 调用点）+ 2 个 `@Accessor("shouldSignal")` | `CarpetSettings.fastRedstoneDust`（`public static boolean`，默认 false） | **与任务书一致**（且比任务书多 3 个 @Redirect，见 §2.3） |
| 2 | **TIS 1.82.3** | `carpettisaddition/mixins/rule/redstoneDustRandomUpdateOrder/RedstoneWireBlockMixin` | **`@ModifyVariable`**`(method="updatePowerStrength", at=@At(value="INVOKE", target="Ljava/util/Set;iterator()Ljava/util/Iterator;"))` | `CarpetTISAdditionSettings.redstoneDustRandomUpdateOrder`（默认 **false**） | 任务书说"TIS @ModifyVariable" **成立**，但它属于**最毒的那条规则**；见 §4.4 |
| 2b | TIS（logger） | `carpettisaddition/mixins/logger/microtiming/events/EmitBlockUpdateMixins$RedstoneWireBlockMixin` | `@Redirect`(argsOnly) 同一方法 | `microTiming`（默认 false） | 纯观测，不影响语义 |
| 3 | **Lithium 0.12.1** | `me.jellysquid.mods.lithium.mixin.block.redstone_wire.RedstoneWireBlockMixin` | `@Inject(method="getReceivedRedstonePower", cancellable=true, at=HEAD)` | `mixin.block.redstone_wire`（默认 **on**） | **与任务书一致，但 priority 那条是错的**，见下 |

### 1.1 一条勘误：Lithium 的红石 mixin **没有 priority=990**

`docs/CAVA-hook-points.md` 第 13 行与 `prompts/06` 都写「priority 990」。本机实测：

* `javap -p -v` 该 mixin 类的常量池里**没有 `priority`**（只有 `value`/`method`/`at`/`HEAD`/`cancellable`）；
* `lithium.mixins.json` 里**没有 `priority` 字段**（`package`/`required`/`compatibilityLevel`/`plugin`/... 无 priority）；
* 上游源码 `.cava-research/repos/lithium204`（`mod_version=0.12.1`、`minecraft_version=1.20.4`，即安装件同版本源码）
  的 `@Mixin(RedstoneWireBlock.class)` 也**没写 priority**。

⇒ **它用的是默认 1000**。这条勘误影响"抢优先级"这类方案的具体数字（1000 而不是 990），
不影响"Lithium 整段替换电力计算"这个定性结论。

### 1.2 Carpet 的 refmap（`fabric-carpet-refmap.json`，实读）

```json
"carpet/mixins/RedstoneWireBlock_fastMixin": {
  "<init>": "Lnet/minecraft/class_2457;<init>(Lnet/minecraft/class_4970$class_2251;)V",
  "Lnet/minecraft/world/level/block/RedStoneWireBlock;updatePowerStrength(...)": "Lnet/minecraft/class_2457;method_10485(...)V",
  "neighborChanged": "Lnet/minecraft/class_2457;method_9612(...)",
  "onPlace":  "Lnet/minecraft/class_2457;method_9615(...)",
  "onRemove": "Lnet/minecraft/class_2457;method_9536(...)",
  "shouldSignal": "field_11438:Z",
  "updatePowerStrength": "Lnet/minecraft/class_2457;method_10485(Lnet/minecraft/class_1937;Lnet/minecraft/class_2338;Lnet/minecraft/class_2680;)V"
}
```

⇒ 三个 `@Redirect` 的目标（Mojang 名 `RedStoneWireBlock.updatePowerStrength`）**确实解析到 `method_10485`**，
不是"写了但没生效"。`carpet.mixins.json` 的 `required=true`，且该 mixin 类名列在其中（实读）。

---

## 2. Carpet 到底改了什么（原版字节码对读，Yarn 名）

原版权威字节码取自 loom 的 **Yarn 命名 jar**（不是记忆）：

    .gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-common-5f62589a64\1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\*.jar

### 2.1 原版 `RedstoneWireBlock.update`（= `method_10485`）的全部内容

反编译级摘要（`javap -p -c`，逐条读出来的）：

    i = getReceivedRedstonePower(world, pos)                    // method_27842
    if (state.get(POWER) != i):
        if (world.getBlockState(pos) == state)                   // 引用相等
            world.setBlockState(pos, state.with(POWER, i), 2)    // flags = iconst_2
        set = {pos} ∪ {pos.offset(d) : d ∈ Direction.values()}   // 7 个位置
        for p in set: world.updateNeighborsAlways(p, this)       // 7 次
    return

**原版调用的全部入口只有 3 个**（同一次 `javap` 扫描，方法本身是 `private`，类外不可能有调用者）：

    onBlockAdded    → update      (offset 24)     [Mojang: onPlace]
    onStateReplaced → update      (offset 85)     [Mojang: onRemove]
    neighborUpdate  → update      (offset 21)     [Mojang: neighborChanged]

⇒ **Carpet 的 3 个 `@Redirect` 恰好覆盖了 100% 的调用点**，一个不漏。
（旁证：`carpet.mixins.json` 是 `required=true` 且该 mixin 在列表里；Mixin 的 `@Redirect` 注入失败默认抛错、
服务端起不来 —— 而本机测试服能正常启动，所以这三个 `@Redirect` **确实应用成功**了。）

### 2.2 Carpet 替换后的路径（安装 jar 的 `updateLogicPublic` 字节码）

    i = this.getReceivedRedstonePower(world, pos)                // method_27842（仍走 Lithium 的替换实现！）
    if (state.get(POWER) != i):
        newState = state.with(POWER, i)
        if (world.getBlockState(pos) == state)                   // 引用相等
            if (world.setBlock(pos, newState, 18))               // bipush 18 = 2 | 16
                wireTurbo.updateNeighborShapes(world, pos, newState)
        // 规则开启时下面这段是死代码（getstatic CarpetSettings.fastRedstoneDust; ifne 跳过）
        for p in {pos} ∪ 6 邻居: world.updateNeighborsAt(p, newState.getBlock())

**与原版的差异（逐条）**：

| # | 项 | 原版 | Carpet fastRedstoneDust |
| --- | --- | --- | --- |
| 1 | 更新顺序 | 每条线各自递归；邻居更新走 1.19+ 的**邻居更新栈**（`ChainRestrictedNeighborUpdater`，受 `max-chained-neighbor-updates` 限制） | 以触发点为中心的**按曼哈顿距离分层 BFS**；层内按"信息流朝向"用 24 邻居重排表排序 |
| 2 | 邻居"更新"的投递 | `World.updateNeighborsAlways`（进栈、延后处理） | **直接 `BlockState.neighborChanged(...)`**（`class_2680.method_26181`），**绕过更新栈**，因此也绕过了链深上限 |
| 3 | 电力计算 | 每次调用 `getReceivedRedstonePower` 重新读世界 | BFS 内用**缓存的邻居状态** + `getMaxCurrentStrength`；只对起始节点调一次 `getReceivedRedstonePower` |
| 4 | 状态写入 flags | `iconst_2`（`NOTIFY_LISTENERS`） | `bipush 18`（`NOTIFY_LISTENERS | FORCE_STATE`），随后**手工补发形状更新**（`updateIndirectNeighbourShapes` + 6×(`updateShape`+`updateOrDestroy`)） |
| 5 | 更新量 | 每条线变化 ~7 次 `updateNeighborsAlways`（×6 邻居 = 42 次投递，去重后仍很多） | 只向**非红石方块**发更新并去重（源码注释自述"最坏情况减少 95%"） |
| 6 | 更新顺序的决定方式 | 完全由调用顺序（递归）决定，确定 | **朝向歧义时用 `ThreadLocalRandom` 随机决定**（见 §2.4） |

### 2.3 ★ 关键发现：规则开启时 `method_10485` 是**死方法**

把 §2.1 与 §1.2 合起来看：

* `update` 是 `private`，类外无法调用；
* 类内 3 个调用点**全部**被 Carpet `@Redirect` 成 `fastUpdate(...)`；
* `fastUpdate` 在 `fastRedstoneDust == true` 时**直接走 turbo 并 return**，永不调 `updatePowerStrength`。

⇒ **规则开启时，`method_10485` 的方法体一次也不会执行。**
任何"我们在 `method_10485` 上 `@Inject(HEAD)` 接管红石"的方案，
在用户的真实配置下**恒等于没有生效**——正是 `docs/CAVA-hook-points.md` §7 第 2 类
「静默失效级（最危险）：服务器正常启动、TPS 正常，但原生从未被调用」。

要真正接管，就得去 **Carpet 自己的类**（`carpet.helpers.RedstoneWireTurbo.updateSurroundingRedstone`）上注入 ——
那是把 Cava 硬绑到另一个 mod 的内部实现上（跨 mod 私有 API + 版本强耦合），
`docs/CAVA-hook-points.md` §2 的纪律（只碰原版方法、只 HEAD-cancellable）不支持这么做。

### 2.4 Carpet 的算法**故意**用随机数（安装 jar 字节码）

`RedstoneWireTurbo.computeHeading(int rx, int rz)` 的 `tableswitch`（0..8）：

    case 0/2/6/8: ThreadLocalRandom.current().nextInt(0, 1)   // 恒返回 0，但消费一次随机数
    case 1/3/5/7: 常量
    case 4:       ThreadLocalRandom.current().nextInt(0, 4)   // ★ 真随机，4 选 1
    default:      nextInt(0, 4)

字节码实读（`computeHeading`）：`102: invokestatic ThreadLocalRandom.current` / `105: iconst_0` / `106: iconst_4` /
`107: invokevirtual nextInt:(II)I` / `110: ireturn`。**case 4 = `rx==0 && rz==0`**。

它什么时候是 `(0,0)`？`findNeighbors` 里 `cx/cz` 由"已访问邻居"决定，都没有时退回 `upd1.xbias/zbias`，
而初始节点的 bias 默认 0。**而 `onPlace`/`onRemove` 路径传进来的 `source` 是 `null`**
（安装 jar 的 `redirectOnBlockAddedUpdate` 字节码：`5: aconst_null`）⇒ 放/拆红石粉时走进随机分支。

Carpet 自己也在类注释里承认这一点（源码 `.cava-research/repos/carpet204/.../RedstoneWireTurbo.java` 第 108–111 行）：

> *Within each layer, updates are ordered left-to-right relative to the direction of information flow.
> … Only when this direction is ambiguous is randomness applied (intentionally).*

以及 Carpet 在游戏内对该规则的描述（**本次 RCON 实测输出**）：

> `Lag optimizations for redstone dust by Theosib. ... also fixes some locational behaviours of vanilla redstone MC-11193
> so behaviour of locational vanilla contraptions is not guaranteed`

⇒ **基准不是"原版"，也不完全是"确定性的"**：在朝向歧义的装置上，现状服务器**逐次运行的世界演化不保证相同**。

---

## 3. 复刻可行性判定：要复刻哪几件事，每一件的代价

要"复刻 Carpet"，必须**同时**做到下面 6 件事（任何一件做不到，就不是逐位一致）：

| # | 要复刻的东西 | 本机证据 | 判定 |
| --- | --- | --- | --- |
| 1 | **整套 BFS + 24 邻居重排 + 3 条队列 + nodeCache + `currentWalkLayer` 重入调度** | `RedstoneWireTurbo` 成员表（`updateQueue0/1/2`、`nodeCache`、`currentWalkLayer`、`scheduleReentrantNeighborChanged`） | ⚠️ 可写，但见 #3 |
| 2 | **`ThreadLocalRandom` 抽取序列** | §2.4 字节码 | ❌ 原生无法独立复刻：Java 的 `ThreadLocalRandom` 由 JVM 全局种子（nanoTime 混合）决定，**跨运行就不同**。要一致只能每次歧义都回调 Java 取随机数（把不确定性搬进原生），且必须保证**抽取次数与顺序完全一致**（任何提前 return 都会错位） |
| 3 | **内层循环的 Java 世界语义** | 安装 jar `RedstoneWireTurbo` 常量池里 **23 个 distinct MC 成员**，其中至少 8 个是**可被任意 mod 覆写的虚调用**：`Level.getBestNeighborSignal`(`method_49804`)、`getBlockState`(`method_8320`)、`removeBlock`(`method_8650`)、`setBlock(pos,state,flags)`(`method_8652`)、`BlockState.neighborChanged`(`method_26181`)、`BlockState.canSurvive`(`method_26184`)、`BlockState.updateShape`(`method_26191`)、`BlockState.isRedstoneConductor`(`method_26212`)、`BlockState.updateIndirectNeighbourShapes`(`method_30102`)、`Block.updateOrDestroy`(`method_30094`)、`Block.dropResources`(`method_9497`) | ❌ **这不是"数值内层循环"**：它是"边遍历边改世界"的调度器。搬进原生只有两条路：① 把方块行为面整个镜像（无界：整合包任何 mod 都能加方块/覆写这些方法）；② 每次操作 FFM 下行调用——**下行调用只会比它替换掉的 Java 调用更贵**，注定是净亏 |
| 4 | **静态全局 `shouldSignal`（`field_11438`）在算法中途被改写** | `calculateCurrentChanges`：`setWiresGivePower(false) → getBestNeighborSignal → setWiresGivePower(true)` | ⚠️ 原生要写 Java 静态字段（可用 Java 侧 setter 包一层），但这是"算法中途改全局"的语义，绕不开 |
| 5 | **旁路邻居更新栈**（直接 `neighborChanged`）与手工形状更新 | §2.2 第 2/4 条 | ❌ 原生化等价于"在原生里再实现一遍 1.19+ 邻居更新语义的绕过"，且这正是 Lithium 注释点名的**不可检测边界** |
| 6 | **`dropResources` 掉落物**（会把 ItemEntity 生成进世界 ⇒ 影响实体层与后续 tick） | 常量池 `class_2248.method_9497` | ❌ 与实体层耦合 |

**结论**：能复刻的是"形状"（BFS 骨架），**不能复刻的是"逐位一致"**；
而性能收益方向也是反的（#3）。这不是"风险高"，而是"投入产出为负"。

> 与 P1 寻路对照：寻路之所以能搬，是因为它的内层循环是**在镜像区段上做纯计算**
> （节点类型/惩罚表/堆操作）。红石粉的内层循环**恰恰相反**：它的每一步都是对世界的读写与对任意方块行为的虚调用。

---

## 4. 许可证（三个选项各自的后果）

| 选项 | 许可证（本机实读） | 影响 |
| --- | --- | --- |
| ① 让位 | — | 无 |
| ② 复刻 Carpet | `.cava-research/repos/carpet204/LICENSE` = **MIT License, Copyright (c) 2020 gnembon** | 合法：可复制/修改/再分发，**保留版权与许可声明**即可。Cava 自身是 **CC0 1.0**（仓库根 `LICENSE`），MIT 代码并入 CC0 项目需**在源码/文档里保留 MIT 声明**（建议放 `docs/CAVA-redstone-notes.md` 附录或 `THIRD-PARTY-NOTICES`） |
| ③ 复刻 Lithium | `.cava-research/repos/lithium204/LICENSE.txt` = **GNU LGPL v3** | 把它的算法移植成 C++ 属于**衍生作品**，需要以 LGPL 分发对应部分（对 CC0 项目是许可污染）。**不采纳** |

（以上是"许可证文本实读"级别的结论，不是法律意见。）

---

## 5. 本次实测（本机私有测试服，真实输出）

**做法**（不共用任何人的目录/端口）：把 `testbed/parity` 整体复制成 **`testbed/p3-redstone`**（私有，
端口 25611/25612），在**快照里**放一份 `carpet.conf`：

    testbed\p3-redstone\snapshots\parity-base\carpet.conf   →   fastRedstoneDust true

然后跑差分流的场景层（native 关闭，即"现状基线"）：

    pwsh -NoProfile -File tools/parity-scenario.ps1 -Leg p3-on-a -Ticks 600 -Native off -Root testbed/p3-redstone -ServerPort 25611 -RconPort 25612
    pwsh -NoProfile -File tools/parity-scenario.ps1 -Leg p3-on-b -Ticks 600 -Native off -Root testbed/p3-redstone -ServerPort 25611 -RconPort 25612

### 5.1 规则确实生效（服务器**运行中**的 RCON 查询，逐字原文）

    [rcon] > carpet fastRedstoneDust
    fastRedstoneDustLag optimizations for redstone dustby Theosib.. also fixes some locational behaviours of vanilla
    redstone MC-11193so behaviour of locational vanilla contraptions is not guaranteedTags: [experimental], [optimization]
    current value: true (modified value)Options: [ true false ]

同一份日志里，**兼容层也读到了同一份 conf**（这顺带闭合了 P2 留下的"本机找不到 carpet.conf"缺口，
至少在私有测试服上闭合了）：

    CAVA-COMPAT|v1|rule|carpet|fastRedstoneDust|true|false|baseline|redstone|ALARM|carpet.conf

### 5.2 逐 tick 差分（`tools/parity-diff.ps1 -SkipRuns`）

    确定性前置：同一配置（native off）跑两次 ================
      DIFF FOUND over 600 ticks
        首个差异 tick=1
          tick 1 字段[x]  x: 'ent=0;paths=0' != 'ent=4;paths=0'
        逐字段差异 tick 数: x=520 p=489
        定位：tick 1 实体 只在 b 出现: minecraft:item#2 (70.77,72.56,2.24) / minecraft:zombie#11..#13
    ================ native on vs off（这里其实是"规则 on vs 规则 off"）================
      DIFF FOUND over 600 ticks
        首个差异 tick=20
          tick 20 字段[p,x]  p: '...' != '...'
        逐字段差异 tick 数: p=183 x=559

**怎么读这两段（重要）**：

1. **两次比对里 `w`（方块层世界哈希）都是 0 个差异 tick** —— 也就是说
   「在开启 fastRedstoneDust 的前提下，逐 tick 世界哈希一致」**在这套夹具上是成立的**（on/on 一致，on/off 也一致）。
2. 但两处判红**都不是红石造成的**：差异字段只有 `p`（`navstate`，mob 导航状态）与 `x`（辅助计数），
   且定位信息就是 P2 已经落盘过的那个已知原因——**实体 id 计数器整体平移 + AI 随机数决定寻路时机**
   （`docs/CAVA-gates.md`「实体/路径层仍未通过，但已定位且与 native 无关」）。
   `tools/parity-diff.ps1` 因此按设计 `exit=1` 并打印"确定性前置不通过 ⇒ on/off 的结论不可用"——**这个处置是对的**。
3. **最有信息量的一条**：`w` 在 **规则开 vs 规则关** 下也完全一致（600 tick）。
   ⇒ **现成夹具根本区分不出 Carpet 算法与原版算法**（它只有一个每 2 tick 翻转的红石块 + 2 格红石线 + 活塞 + 比较器，
   且所有更新都带 `source`）。
   ⇒ 于是「`w` 逐 tick 一致」**不能**当作"原生红石与现状逐位一致"的证据：
   它证明的是**夹具不敏感**，不是算法等价。

---

## 6. 显式归属决策

| 点 | 归属 | 理由 |
| --- | --- | --- |
| `RedstoneWireBlock.update` (`method_10485`) | **mod（让位）** | 规则开启时该方法体是死代码（§2.3）；算法不可逐位复刻（§2.4/§3） |
| `RedstoneWireBlock.getReceivedRedstonePower` (`method_27842`) | **mod（让位）** | Lithium `mixin.block.redstone_wire` 已默认接管（§1）；且它是 turbo 的**被调用方**，我们动它等于改 turbo 的输入 |
| `updateNeighbors`/`updateOffsetNeighbors` (`method_10479`/`method_27844`) | **不动** | 规则开启时同样不可达（Carpet 旁路掉整条路径） |
| `AbstractRedstoneGateBlock` / `RepeaterBlock` / `ObserverBlock` / `PistonBlock` / `NeighborUpdater` / `ChainRestrictedNeighborUpdater` | **不动** | 已审计 9 个 mod **0 命中**（安全区），但**没有加速对象**：它们不是瓶颈，且一旦动它们就越过了 Lithium 注释点名的"不可检测"边界 |

**与 `docs/CAVA-compat-notes.md` 的关系：本轮不改任何东西。**
兼容层请求 #2（把 `CompatTable` 里 `carpet/CarpetSettings.fastRedstoneDust` 的 `defaultOwner` 从 `MOD` 改成 `NATIVE`）
**明确不采纳** —— 那个请求的前提"P3 决定复刻 fastRedstoneDust"不成立。
`fabric.mod.json` 的 `lithium:options` 继续保持只关 `mixin.ai.pathing`（**不要**关 `mixin.block.redstone_wire`：
关掉它只会让服务器更慢，而 Cava 没有任何东西顶上）。

### 代价（必须写清楚）

* **红石零加速。** Cava 在这个子系统上的收益是 **0%**，不是"小"。
* 依据：任务书自身口径「普通生存服红石占不到 tick 的 2%」；
  Lithium 自报"仅电力计算"在**红石密集**场景最多 **30% MSPT 下降**（Lithium 自己的数字，见
  `docs/CAVA-platform-and-compat.md`）——那是 dense-redstone 场景，不是生存服常态；
  且**那份收益现在已经在生效**（Lithium 的 mixin 是开着的），Cava 顶上去也只能是"替换"而不是"叠加"。
* **风险端**：如果硬上，最可能的结局不是变慢，而是 §2.3 的**静默失效**（服务器一切正常、原生一次没跑），
  或者"原生跑了一部分、Carpet 跑了一部分"的混合语义——那是逐 tick 差分最难定位的一类失败。

### 如果将来要动，前置条件（按顺序，一条都不能跳）

1. 先有**顺序敏感夹具**（现成夹具已被本次实测证明不敏感，§5.2 第 3 条），
   并且它必须能在 `w` 上区分"规则开 vs 规则关" —— 否则验收是空的；
2. 先解决 `ThreadLocalRandom` 的抽取序列对齐（否则基准自己就不可复现）；
3. 先确认能在**不改 Carpet 内部类**的前提下拿到接管点（§2.3 表明 `method_10485` 不行）；
4. 先有一份"逐节点 Java 调用次数"的实测（本次只做了**静态**的常量池计数：23 个 MC 成员），
   用来说明原生侧到底省下了什么。

---

## 7. 未验证 / 风险（诚实清单）

| 项 | 状态 |
| --- | --- |
| 真实生产服务器上 `carpet.conf` 的实际内容 | ❓ **未验证**（本机仍未见该文件；本次是我在**私有测试服**里自己写了一份把它打开）。用户"已开启"这条仍是 captain 声明 |
| Carpet 的随机朝向分支在真实装置上**是否真的被走到** | ❓ **未验证**。只证明了代码路径**可达**（`onPlace`/`onRemove` 传 `source=null`，`computeHeading(0,0)` → `nextInt(0,4)`），没有插桩计数 |
| 顺序敏感夹具 | ❓ **未建**。`docs/CAVA-parity-fixtures.md` §6 的 7 个装置本轮**一个都没重做**（让位决策下没有验收对象，且第 1 条前置条件未满足） |
| TIS `redstoneDustRandomUpdateOrder` 与 Carpet `fastRedstoneDust` 同时开启的语义 | ⚠️ **静态推断**：TIS 的 `@ModifyVariable` 挂在 `update` 里的 `set.iterator()` 上，而规则开启时 `update` 是死代码 ⇒ 该规则**失效**。未实机验证 |
| 性能数字 | ❌ **本机不测**：让位决策下没有可测对象（无原生红石路径）。任何"红石加速 X%"的数字都必须是编的，本文一个都不给 |

---

## 8. 复现命令汇总（可直接粘贴）

    # 0) 私有测试服（不要用共享的 testbed/gate-preview；端口也别撞）
    Copy-Item -Path 'J:\mc\Cava\testbed\parity' -Destination 'J:\mc\Cava\testbed\p3-redstone' -Recurse -Force
    Set-Content -LiteralPath 'J:\mc\Cava\testbed\p3-redstone\snapshots\parity-base\carpet.conf' -Value 'fastRedstoneDust true' -Encoding ASCII

    # 1) 两条腿（native 关闭 = 现状基线）
    pwsh -NoProfile -File tools/parity-scenario.ps1 -Leg p3-on-a -Ticks 600 -Native off -Root testbed/p3-redstone -ServerPort 25611 -RconPort 25612
    pwsh -NoProfile -File tools/parity-scenario.ps1 -Leg p3-on-b -Ticks 600 -Native off -Root testbed/p3-redstone -ServerPort 25611 -RconPort 25612

    # 2) 腿跑着的时候（服务器 READY 之后）取规则现状 —— 这是"规则真的生效"的现场证据
    pwsh -NoProfile -File tools/rcon.ps1 -Command 'carpet fastRedstoneDust' -Port 25612

    # 3) 比对（w 是否逐 tick 一致）
    pwsh -NoProfile -File tools/parity-diff.ps1 -SkipRuns -LegA p3-on-a -LegB p3-on-b -LegC ref-off-a -Root testbed/p3-redstone

    # 4) 占用者核实（三份 jar 的字节码）
    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -v -classpath '.cava-research\batchB\carpet' carpet.mixins.RedstoneWireBlock_fastMixin
    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c -classpath '.cava-research\batchB\carpet' carpet.helpers.RedstoneWireTurbo
    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -v -classpath '.cava-research\batchB\tis' 'carpettisaddition.mixins.rule.redstoneDustRandomUpdateOrder.RedstoneWireBlockMixin'
    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -v -classpath '优化模组\服务端模组\server-lithium-fabric-mc1.20.4-0.12.1.jar' me.jellysquid.mods.lithium.mixin.block.redstone_wire.RedstoneWireBlockMixin

    # 5) 原版权威字节码（Yarn 命名 jar；**注意不是** minecraft-common.jar，那个是 obf 的）
    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c -classpath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-common-5f62589a64\1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\minecraft-common-5f62589a64-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar' net.minecraft.block.RedstoneWireBlock

---

## 附录：本轮**没有**改动的代码

按"先决策，后实现"的纪律，本轮**没有写一行 C++、没有加任何 mixin、没有改兼容层**。
`src/main/java/cava/redstone/**`、`native/src/redstone/**`、`src/main/java/cava/mixin/redstone/**` 均为空（未创建）。
如果 captain 要"让位也要有检测与报告"，建议的落地形态（下一轮，需别人配合）：

* 在 `cava.compat` 的既有规则探测里，把 `fastRedstoneDust` 从 `BASELINE` 告警升级为"**红石让位 + 原因**"的一行说明
  （现有输出已经有 `owner|redstone|carpet|CarpetSettings.fastRedstoneDust|mod|...|pending-p3` 与
  `rule|carpet|fastRedstoneDust|true|false|baseline|redstone|ALARM|carpet.conf` 两行，改 `stage` 即可）；
* **不要**为了"检测"在 `method_10485` 上加观察用 mixin：规则开启时它不执行，观察不到任何东西（§2.3），
  却会让兼容层报告出现"我们注入过"的假象。
