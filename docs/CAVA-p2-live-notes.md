# Cava P2 live 接管轮：`Entity.move` 整段接管

> 作者：P2 live 子代理（工作目录 `J:\mc\Cava`）。**所有数字都是本机实测**，原样贴在 §5/§6；
> 未验证的一律写「未验证」。语义权威 = `docs/CAVA-entity-oracle-spec.md`（本轮**未修改**），
> ABI 权威 = `native/include/cava_abi.h`（本轮**未修改**，见 §7）。
> 上一轮的接线与实测 = `docs/CAVA-p2-wiring-notes.md`。

---

## 0. 一句话状态

**`-Dcava.entity.move=live` 真的接管了**：HEAD 处调原生求解 → 用**拉取式**回放执行完整段
`Entity.move` → `ci.cancel()`。真实服务端实测 **144158 次接管、0 回退、0 错误、
0 事件溢出、0 自检未通过**，并且**原版 move 方法体一次都没有执行到返回**
（`vanillaMove=n/a`，见 §4.2）。**live 与 shadow 的差异为零**：`-Dcava.entity.move.verify=true`
下 **96220 次"接管实际采用的位移 vs 原版同 call 私求解"逐位比对，0 不一致**（§4.3）。

**性能是净亏，如实说**：整段 `move` 稳态 原版 **1.34µs** vs live **3.20µs/次**（§6）。

---

## 1. 核心工作：`EventReplay` 改成拉取式

上一轮拒绝 live 的理由是「`MoveInputs` 是预打包 record，表达不了 move 里四处**中途才成立**的输入」。
本轮把这个前置条件做掉了：

| 文件 | 变化 |
| --- | --- |
| `cava/entity/MoveInputSource.java` | **新建**：拉取契约。每个方法 = 一个拉取点，javadoc 里写死它的字节码偏移 |
| `cava/entity/EventReplay.java` | **重写**：每一步在**它自己的那一刻**向源拉取；同时加 `trace` 开关（诊断流水可关，判定不变） |
| `cava/entity/MoveInputs.java` | 从「生产输入」降级为**定值实现**（单测夹具），implement `MoveInputSource` |
| `cava/entity/MoveStep.java` | 补一个常量 `MOVE_EFFECT_BOOKKEEPING(568)`（原先 547 与 737 之间没有常量，拉取时刻会被记错一格） |
| `cava/entity/MoveCallbacks.java` | 补 `stateIsAir`（抽象，722/841）、`refreshNextStepSoundDistance`（785/803）、`profilerAfterMovement`（248）、`profilerEnd`（455/982） |

**四处时序依赖现在都在正确时刻拉取**（`MoveInputPullOrderTest` 逐条钉死）：

| 拉取点 | 偏移 | 为什么必须"那一刻" |
| --- | --- | --- |
| `landingPos()` | 416 | 在 `setOnGround`（412）**之后**；那一步会改写 `supportingBlockPos` |
| `steppingPos()` | 626 | 只在 `moveEffect` 分支**内部**求值 |
| `stepSoundDistanceExceeded()` | 708–717 | 依赖 589–705 记账写下的 `distanceTraveled` |
| `regionLoaded()` | checkBlockCollision 67 | 扫的是 `setPosition`（218）**之后**的盒子 |

**顺序唯一事实来源仍是 `MoveStep.ordinal()`**：回放把每次拉取连同它所属的 `MoveStep` 记进
`Transcript.pulls`，用例断言 ordinal 单调不减 + 整条序列写死。**没有另立一套顺序表。**

**保留下来的能力**（一条没削）：
- `shadow` 逐位比对：本轮重构后真实服务端 **144147 次比对 0 不一致**（§5）；
- `strictOk()` / transcript / 未消费事件 / anomaly（回执校验）四件套；
- `AXIS_CLIP` 走**原始 VoxelShape 对象**、落点方块状态**只解析一次**、扁平 AABB 近路仍然被断言为 0 次。

---

## 2. live 的接管条件与 fail-closed 边界

```
零位移（movement.equals(Vec3d.ZERO)，逐位） → 永不接管（VMP 的领地，见下）
noClip（4–38） / MovementType.PISTON（47–70）→ 留给原版
可重入（回放里的回调又触发 move）→ 内层留给原版
原生不可用 / 形状表不可用 / 任何错误码 / event_overflow / token 越界 / 台阶分支守卫 → 不 cancel
```

1. **零位移永不接管 ⇒ 与 VMP 的 cancel 集合恒不相交 ⇒ 与注入顺序无关。**
   VMP 的黏滞状态机仍然被复刻并逐次询问（`VmpZeroVelocityGate` + `EntityMoveMixin` 的
   `setBoundingBox` HEAD 注入，只在 live + VMP 在类路径上时启用），
   计数见 `vmpGateQueries/vmpGateWouldCancel`；但**它的返回值从不驱动我们的 cancel** ——
   不需要（也不可能）去猜 Mixin 的 priority 语义。
2. **回放开始之后的失败无法回滚**（副作用已发生）。所以三类会产生事件的分支
   （射线 / 逐格扫描 / 火焰盒）都由 Java 侧在**同一分支内**注入事件
   （`MoveInputSource.supplyEventsAt`），注入条数与消费条数当场对账（回执校验）——
   live 下 `unconsumed/anomalies` 按构造为空，实测 `liveNonStrict=0`。
3. 异常仍然按任务书要求**整段回退不 cancel**（`onMoveHead` 的 catch）。若异常发生在回放中途，
   原版会在**已被部分改写**的实体上再跑一遍 —— 这条残留风险写在 §8「未验证」里。

---

## 3. 内核能力边界（**没有扩 ABI**）

冻结的 `cava_resolve_move` **只产出一种事件**（几何裁剪 `AXIS_CLIP`）。另外三类事实在原版里都是
世界查询，内核没有世界：

| 事实 | 谁算 | 机制 |
| --- | --- | --- |
| 射线命中（197） | Java | `supplyEventsAt(LANDING_RAYCAST)` 现算 `world.raycast(FALLDAMAGE_RESETTING, WATER)` |
| 逐格碰撞扫描（853） | Java | `supplyEventsAt(BLOCK_COLLISION)` 按原版 **x→y→z** 枚举坐标；方块状态仍逐格现取 |
| 火焰盒（881） | Java | `supplyEventsAt(FIRE_BOX)` 现算 `getStatesInBoxIfLoaded(contract(1e-6))` |

于是"内核事件 + Java 现算事实"合成同一份日志，`EventReplay` 的事件消费/未消费检查对三者**原样适用**。

**台阶分支仍然回退**（恒传 `step_height=0`，Java 侧原版判据守卫）。本机三组场景里
`stepBranchSkipped=0`（冰面上滑行的盔甲架/物品没撞到东西）——**这一支在本轮的负载里未被触发**。
是否必须先解决台阶分支才能上线 live：**不是**（守卫生效即回退原版，行为与纯 Java 逐位相同）；
但"原生吃下台阶分支"需要 ABI 支持"每趟一份形状列表"，见 §7。

---

## 4. 验收证据（真实服务端，私有实例 `testbed/p2-live`，端口 25621/25622/25623）

场景：超平坦私有世界（seed 20240922）+ 蓝冰竞技场（`forceload` ±48），Carpet `/tick freeze` +
`/tick step N` 精确控 tick，实体位置用 `/data get` 逐实体导出。
二进制：`build/libs/cava-0.1.0.jar`（2026-09-22 22:36，内含 `natives/windows-x64/cava.dll`
sha256 `1e6ee368600c5536b2a048b9019681a031d8943b81de7927ec0c067a4c0ba454`，
与 `remapJar` 前的工作区 DLL **逐位相同** —— 旧产物造成过三次假结论，这次先验 hash）。

### 4.1 单元层

    ./gradlew.bat test --tests "cava.entity.*" --tests "cava.shape.*"   ->  48 tests, 0 failed
    ./gradlew.bat test                                                  ->  BUILD SUCCESSFUL

新增 `MoveInputPullOrderTest`（5 个用例）：拉取序列写死、三处时序证据、
`steppingPos` 只在 moveEffect 分支内拉、零位移时不动位置块。

### 4.2 真的接管了（不是"计数器动了一下"）

    [cava/entity] 终局计数 mode=LIVE nativeCalls=144158 nativeOk=144158 fallback=0 errors=0
      overflow=0 stepBranchSkipped=0 tokenOutOfRange=0 liveTakeovers=144158 liveNonStrict=0
      livePulls=1298536 ... | vanillaMove=n/a liveMove=4220ns(n=144158,首桶=10538ns,末桶=3200ns)

- `vanillaMove=n/a` = **原版 `move` 的方法体一次都没走到 RETURN**（mixin 在 RETURN 处只计数）。
  这是"cancel 真的生效"的直接观测，不是 `hit()`。
- `livePulls/liveTakeovers = 1298536/144158 = 9.01`：每次接管平均拉取 9.01 个输入。
- `liveNonStrict=0`：96220/144158 次回放的 `strictOk()` 全为真。

**破坏性金丝雀**（`-Dcava.entity.move.canary=skip-setposition`，默认 off，只允许诊断用）：
接管路径**不写** `setPosition`。结果（`canary-live.dump.txt`，120 实体 × 4 字段）：

    金丝雀 Pos == 出生点（含 /summon 的块中心规则）的行数: 116 / 116（不存在的 4 个实体在
    纯原版运行里同样不存在，是 summon 失败，与本轮无关）
    金丝雀 Y 全为 -59.0 的行数: 116 / 116
    对照 beh-live：同样实体已落到 -60.0 并滑行 1.7–20 格

⇒ 实体的位置**确实**是这段回放写下去的；`ci.cancel()` 若失效，原版会紧接着把实体搬走，
金丝雀 dump 就不会等于出生点。**可证伪**。

### 4.3 live 与 shadow 差异为零（本任务最强证据）

**跨 run 比实体 dump 不成立**（实测踩到）：同一场景跑两次**纯原版**，

    off vs off2  不同行: 10   （旧布局，120 行）
    off-a vs off-b 不同行: 16 （稀疏网格，120 行）

世界本身有 run-to-run 非确定性（实体 `random` 的种子与 tick 内的一些随机量），
所以"live dump ≠ off dump"**不能**归因给 live。改用**同 call** 对照：

    -Dcava.entity.move.verify=true（每次接管多调一次原版私有求解，纯读、无副作用）
    [cava/entity] 终局计数 mode=LIVE nativeCalls=96220 ... liveTakeovers=96220
      liveVerified=96220 liveVerifyMismatch=0 ... liveNonStrict=0 | vanillaMove=n/a

**96220 次"接管实际采用的位移 == 原版 `adjustMovementForCollisions(Vec3d)` 在同一次调用、
同一份输入上的返回值"，逐位（`Double.compare` 三分量）比对，0 不一致。**
同一 jar 的 shadow 侧另有 144147 次同 call 比对 0 不一致（§5）。这就是"live 与 shadow 差异为零"
能拿到的最强形式：shadow 是"算但不接管"，verify 是"接管了的那一个值 == 原版值"。

---

## 5. shadow（重构后没有退化）

    perf-shadow: nativeCalls=144147 shadowCompared=144147 shadowAgree=144147 shadowMismatch=0
      stateRefs=223184 inlineRefs=0 | timedCalls=144147 组refs=1451ns 原生调用=768ns 原版调用=446ns
    beh-shadow : nativeCalls=17815  shadowCompared=17815  shadowAgree=17815  shadowMismatch=0

⇒ 上一轮"94 万次逐位比对 0 不一致"的能力在拉取式重构后**原样保留**（本轮 16 万次）。

---

## 6. 性能：**诚实结论是净亏**

测量规范：样本 = 真实调用，**分桶（每 20000 次一行）**，报"首桶/末桶/累计均值"+ 样本量。
没有出现"三次完全相同的 ns/op"。

| 运行（同一确定性场景：600 物品实体、60 批 × 20 tick + 4000 tick） | 末桶均值 | 累计均值 | n |
| --- | --- | --- | --- |
| `off`（整段原版 move） | **1339 ns** | 1765 ns | 280000 |
| `shadow`（整段：组refs+原生+原版再跑一遍） | 3693 ns | 4694 ns | 144147 |
| `live`（整段接管：原生 + 拉取式回放） | **3200 ns** | 4220 ns | 144158 |
| `live+verify`（每次再多一次原版求解） | 3794 ns | 6246 ns | 96220 |

- **净亏 ≈ 3200 − 1339 = +1.86 µs/次**（预热瞬态很大：首桶 10538ns → 末桶 3200ns，
  所以只报末桶 + 趋势，不报一个数字了事）。
- live **比 shadow 便宜**（3200 vs 3693）：shadow 把世界查询做了两遍（组 refs 一遍、原版内部一遍），
  live 只做一遍。⇒ **瓶颈在 Java 侧的组 refs（1451ns）与回放本身，不在原生内核**（原生调用 768ns）。
- 上一轮的净亏估计是 ~1.98µs/次；本轮实测 ~1.86µs/次 —— 量级一致，**没变快**。
  变快的一处是诊断流水：带 transcript 时每次接管 **15–18µs**，`trace=false` 后降到 **~3.2µs**。
- **未验证**：refs 密集（实体卡在方块里 / 几十个碰撞形状）的负载下是否反超。本场景平均 refs=1.55。

---

## 7. 需要别人配合 / 请裁决

1. **台阶分支（≥2 次重跑 `getBlockCollisions`）**：本任务明令不扩 ABI。当前做法是
   Java 侧用原版判据守卫 + 命中就回退（`stepBranchSkipped`）。**本机负载没触发过这一支**，
   所以我**不能**说"guard 永远正确"——只能说它逐条对齐 oracle spec §6.2 的偏移 45/65/85/105/133。
   **请裁决**：live 上线前是否需要 ABI 支持"每趟一份形状列表"。
2. **`cava/shape`**（P2 接线领地）：本轮没碰。若要让 live 更快，组 refs 的 1451ns 是首要目标
   （例如把 `getEntityCollisions` 常见为空、`getBlockCollisions` 常见为 1 个形状这两条快路径做进 Java 侧）。
3. **`src/main/resources/cava.mixins.json` 不在本流授权路径**：所有新注入都塞进了**已登记**的
   `EntityMoveMixin`（新增 RETURN 计数注入与 setBoundingBox HEAD 注入），**没有新增 mixin 类**，
   所以本轮不需要动那个文件。

---

## 8. 未验证 / 留白（**不要当成已完成**）

1. **台阶分支守卫在真实服务端未被触发**（§7.1）：三组场景 `stepBranchSkipped=0`。
2. **回放中途抛异常**：会按任务书回退（不 cancel），但此时副作用已部分发生 —— 本轮
   `errors=0`，从未真实发生过；这条路径**未验证**。
3. **跨 run 的整服轨迹差分没有做**：本轮证明了"同 call 值逐位相同"，**没有**证明"整服逐 tick 一致"
   （世界本身 run-to-run 非确定，见 §4.3 的两次纯原版对照）。
4. **零位移 / VMP 路径没有被真实触发**：三组场景 `declinedZero=0 vmpGateQueries=0`
   （盔甲架与物品只在有速度时才调 `move`）。零位移永不接管这条规则**只有单测级证据**。
5. **实体类型覆盖窄**：只有盔甲架 + 物品。玩家（`ServerPlayerEntity` 覆写面最大）、
   载具、活塞位移（`MovementType.PISTON` 直接回退）、`noClip` 实体**都没测**。
6. `liveMove` 与 `vanillaMove` 来自**不同 run**（同场景同 tick 数），不是同 call A/B。
7. 性能数字只覆盖"平均 refs=1.55"的负载（§6）。

---

## 9. 复现

    # 单元
    $env:GRADLE_USER_HOME='J:/mc/Cava/.gradle-home'
    $env:JAVA_TOOL_OPTIONS='-Duser.language=en -Dfile.encoding=UTF-8'
    ./gradlew.bat test --tests "cava.entity.*" --tests "cava.shape.*" --console=plain --no-watch-fs --no-configuration-cache

    # jar（注意：必须是 remapJar 的产物）
    ./gradlew.bat remapJar --console=plain --no-watch-fs --no-configuration-cache
    Copy-Item -LiteralPath build/libs/cava-0.1.0.jar -Destination testbed/p2-live/mods/cava-0.1.0.jar -Force

    # 真实服务端（私有端口；离屏无窗口）
    pwsh -NoProfile -File tools/p2-live.ps1 -Action reset
    pwsh -NoProfile -File tools/p2-live.ps1 -Action start -Mode live            # 或 shadow / off
    pwsh -NoProfile -File tools/p2-live.ps1 -Action start -Mode live -Verify    # live 自证
    pwsh -NoProfile -File tools/p2-live.ps1 -Action start -Mode live -Canary skip-setposition
    pwsh -NoProfile -File tools/p2-live.ps1 -Action scenario -Kind item -Batches 60 -PerBatch 10 -BatchSteps 20 -Steps 4000 -NoDump
    pwsh -NoProfile -File tools/p2-live.ps1 -Action stop

    # 本机坑（本轮踩到的）
    # 1) 上一次没停干净的服务器会让新实例 BindException 崩溃，而 RCON 会打到**老实例**上 ->
    #    结果全是假的。-Action start 现在会先探端口。
    # 2) logback 的 appender 在 JVM shutdown 钩子里可能已经关掉 -> 终局计数改用 System.out。
    # 3) 用 pwsh 变量捕获子进程 stdout / *> $null 重定向会挂住（本机实测）：编排脚本一律显式顺序。
