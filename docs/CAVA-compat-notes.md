# Cava 兼容层（让位 / 复刻）落盘结论 —— W2-兼容层

> 本文是 W2-兼容层流的结论落盘处。**所有类名/方法名/规则名都有本机实测证据**，
> 未验证的一律写「未验证」。复现命令逐条给出。
>
> 代码入口：`src/main/java/cava/compat/**`（新增）、`src/main/java/cava/CavaConfig.java`（扩展）、
> `src/main/java/cava/Cava.java`（只加了「启动报告」一段调用）、`src/main/resources/fabric.mod.json`（只加 `custom` 段）。

---

## 0. 一句话结论

「让位 / 复刻」现在是**可执行配置 + 启动时可见报告 + 可被单测断言**的东西：

- `fabric.mod.json` 的 `custom.lithium:options` **只关了一组**：`mixin.ai.pathing`。
- 启动时打印固定字段的 `CAVA-COMPAT|v1|*` 报告（可 grep、可单测）。
- `config/cava.json` 提供「子系统 × mod」的 `auto | native-first | defer` 覆盖，**每个决定都能被配置顶掉**（含规则驱动的自动让位）。
- ServerCore / VMP 的适配器**用反射 + jar/class 文件探测**，不假设存在性，不凭记忆写方法名。

---

## 1. 本轮归属决策（captain 已定，代码与文档一致）

| 重叠点 | 来源 | 子系统 | 本轮归属 | 依据 |
| --- | --- | --- | --- | --- |
| `mixin.ai.pathing` | lithium 0.12.1 | pathfind | **native**（**关掉该组**） | P1 要复刻原生 A* |
| `optimizations.misc.PathFinderMixin` | servercore 1.5.0 | pathfind | native | 我们 HEAD 接管 `PathNodeNavigator.findPathToAny` 后，它打在方法体内的补丁自然不执行 |
| `optimizations.sync_loads.GroundPathNavigationMixin` | servercore 1.5.0 | pathfind | native | 同上 |
| `mixin.entity.collisions.movement` | lithium 0.12.1 | entity | **mod（让位）** | **待 P2 决策**，本轮保持现状 |
| `activation_range.EntityMixin#addVelocity` | servercore 1.5.0 | entity | **mod（让位）** | 待 P2 决策 |
| `entity.move_zero_velocity.MixinEntity` | vmp 0.2.0+beta.7.139 | entity | **mod（让位）** | 待 P2 决策 |
| `mixin.block.redstone_wire` | lithium 0.12.1 | redstone | **mod（让位）** | **待 P3 决策**，本轮不关 |
| `CarpetSettings.fastRedstoneDust` | carpet 1.4.128 | redstone | **mod（让位）** | 用户已开启 → 它**就是**红石基准 |
| `CarpetTISAdditionSettings.redstoneDustRandomUpdateOrder` | carpet-tis-addition 1.82.3 | redstone | mod（让位） | 待 P3 决策 |
| `mixin.shapes` | lithium 0.12.1 | mirror | mod（**不关**） | 不在 Cava 注入点上，登记不裁决 |

**由此得出的子系统级归属（本轮）**：`pathfind = native`、`entity = mod`、`redstone = mod`。
解算规则：**只要一个子系统有一个重叠点判给 mod，整个子系统就让位**（对方的补丁还在跑，"等价替代"的前提不成立）。

## 2. `custom.lithium:options`：只关一组，为什么

### 2.1 机制（javap 实证，不是记忆）

```powershell
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c 
  -classpath '优化模组\服务端模组\server-lithium-fabric-mc1.20.4-0.12.1.jar' 
  me.jellysquid.mods.lithium.common.config.LithiumConfig
```

| 事实 | 证据 |
| --- | --- |
| 键就是 `lithium:options` | 常量池 `#28 = Utf8 lithium:options` |
| 会遍历**所有** mod 的元数据 | `applyModOverrides()` 首句 `FabricLoader.getInstance().getAllMods()` |
| 值必须是对象 | 不是 `CvType.OBJECT` 就打 WARN `Mod '{}' contains invalid Lithium option overrides, ignoring` |
| 键可带/不带 `mixin.` 前缀 | `applyModOverride`：不以 `mixin.` 开头就调 `getMixinRuleName(...)` 补前缀（常量池 `#380 = Utf8 mixin.`） |
| 键必须已存在 | 否则 WARN `attempted to override option '{}', which doesn't exist, ignoring` |
| **没有运行期开关** | 全类常量池**没有** `System.getProperty` / 环境变量分支 |

四个候选键都在 `assets/lithium/lithium-mixin-config-default.properties` 里实读存在且默认 `true`：
`mixin.ai.pathing` / `mixin.entity.collisions.movement` / `mixin.block.redstone_wire` / `mixin.shapes`。

真实世界的旁证：**ServerCore 自己**的 `fabric.mod.json` 就写着
`"custom": {"lithium:options": {"mixin.alloc.chunk_ticking": false}}`，
VMP 也写了（空对象）。所以这是被实际使用的官方机制。

### 2.2 本轮发布值（`src/main/resources/fabric.mod.json`）

```json
"custom": {
  "lithium:options": {
    "mixin.ai.pathing": false
  }
}
```

| 组 | 关？ | 为什么 |
| --- | --- | --- |
| `mixin.ai.pathing` | **关** | P1 要复刻原生 A*（`PathNodeNavigator.findPathToAny`），必须先让 Lithium 的寻路组让路 |
| `mixin.entity.collisions.movement` | 不关 | P2 才决策；**提前关只会让服务器更慢且零收益** |
| `mixin.block.redstone_wire` | 不关 | P3 才决策；而且红石基准还叠着 Carpet `fastRedstoneDust` |
| `mixin.shapes` | 不关 | 不在 Cava 的注入点上（VoxelShape/形状缓存），与三个子系统零重叠 |

单测 `LithiumOptionsTest#shippedMetadataMatchesSource` 直接读 `src/main/resources/fabric.mod.json`，
断言它**恰好等于** `{"mixin.ai.pathing": false}` —— 以后任何人偷偷加一行都会红。

### 2.3 ⚠️ 重要限制：静态元数据**运行期无法撤销**

`config/cava.json` 可以把 `pathfind` 改成 `defer`（让位给 Lithium），
但 `custom.lithium:options` 已经写死在 mod 元数据里、Lithium 也没有运行期开关 —— **那一组回不来了**。
所以启动报告在这种情况下会打一条**硬告警**：

```
CAVA-COMPAT|v1|lithium|mixin.ai.pathing|published=off|wanted=on|consistent=false|...
CAVA-COMPAT|v1|warn|LITHIUM_METADATA_MISMATCH|...要改必须编辑 cava 的 fabric.mod.json 并把 mixin.ai.pathing 从 false 改成 true（或删掉该项）后重启
```

这是一条**已知的、有意的**取舍：可配置的东西（子系统归属、让位、告警）全部可配置；
不可配置的那一项**必须被看得见**，而不是悄悄不一致。

## 3. ServerCore 适配器（反射 + 软依赖）

```powershell
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -classpath '优化模组\服务端模组\server-servercore-fabric-1.5.0+1.20.4.jar' 
  me.wesley1808.servercore.common.interfaces.activation_range.Inactive 
  me.wesley1808.servercore.common.interfaces.activation_range.ActivationEntity
```

### 3.1 勘误（**任务书此处口径是错的**）

任务书说「反射读 `Inactive`（方法 `servercore$isInactive`）」。**javap 实测**：

```text
public interface me.wesley1808.servercore.common.interfaces.activation_range.Inactive {
  public default void servercore$inactiveTick();      <-- 只有这一个方法
}
public interface me.wesley1808.servercore.common.interfaces.activation_range.ActivationEntity {
  public abstract me.wesley...ActivationType servercore$getActivationType();
  public abstract boolean servercore$isExcluded();
  public abstract int servercore$getActivatedTick();
  public abstract void servercore$setActivatedTick(int);
  public abstract int servercore$getActivatedImmunityTick();
  public abstract void servercore$setActivatedImmunityTick(int);
  public abstract boolean servercore$isInactive();     <-- isInactive 在这里
  public abstract void servercore$setInactive(boolean);
  public abstract void servercore$incFullTickCount();
  public default int servercore$getFullTickCount();
}
```

`me.wesley1808.servercore.mixin.features.activation_range.EntityMixin` **同时实现这两个接口**。
所以 `ServerCoreAdapter.isInactive(Object)` 反射的是 **`ActivationEntity`**。
`ServerCoreAdapterTest#probeJarReadsRealInterfaceShape` 把这条钉死：
`inactiveInterfaceMethods() == [servercore$inactiveTick]`、`isInactiveOnInactiveInterface() == false`。

### 3.2 复刻的短路语义

refmap 实读（`servercore-common-refmap.json`）：

```text
"push(DDD)V" -> Lnet/minecraft/class_1297;method_5762(DDD)V
"move"       -> Lnet/minecraft/class_1297;method_5784(Lnet/minecraft/class_1313;Lnet/minecraft/class_243;)V
"Lnet/minecraft/world/entity/Entity;limitPistonMovement(...)" -> class_1297;method_18794(...)
```

**`method_5762(DDD)V` 的 Yarn 名不是 `push` 而是 `addVelocity`** —— 本机 `mappings.tiny` 实读：

```text
m	(DDD)V	j	method_5762	addVelocity
```

`EntityMixin.servercore$ignorePushingWhileInactive(DDD, CallbackInfo)` 的字节码（`javap -p -c`）逐条就是：

```text
0: getfield  servercore$isInactive:Z ; ifeq 22
7: getfield  field_6002 (Entity.world) ; getfield class_1937.field_9236 (World.isClient) ; ifne 22
17: invokevirtual CallbackInfo.cancel()
```

即 **`isInactive && !world.isClient` 时取消 `Entity.addVelocity`**，
已落成纯函数 `ServerCoreAdapter.shouldCancelAddVelocity(boolean, boolean)`（单测对着字节码断言 4 种组合）。

**为什么是软依赖**：ServerCore 不在编译期依赖里；探测失败一律当「没装」→ 让位给它。

## 4. VMP 适配器：零位移短路

```powershell
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c 
  -classpath '优化模组\服务端模组\server-vmp-fabric-mc1.20.4-0.2.0+beta.7.139-all.jar' 
  com.ishland.vmp.mixins.entity.move_zero_velocity.MixinEntity
```

### 4.1 注入点与条件（javap -v 的注解 + refmap 实读）

| 项 | 实测值 |
| --- | --- |
| `@Mixin` 目标 | `Lnet/minecraft/class_1297;`（= `Entity`） |
| 注入 1 | `@Inject(method=["move"], at=HEAD, cancellable=true)` → refmap `Lnet/minecraft/class_1297;method_5784(Lnet/minecraft/class_1313;Lnet/minecraft/class_243;)V` |
| 注入 2 | `@Inject(method=["setBoundingBox"], at=HEAD)`（非 cancellable）→ refmap `method_5857(Lnet/minecraft/class_238;)V` |
| 状态字段 | `@Shadow Box field_6005` + `@Unique boolean boundingBoxChanged`（构造器里初始化为 `false`） |

`field_6005` 是什么，用本机映射核过（**不是猜**）：

```text
f	Lelo;	aI	field_6005	boundingBox
```

即 `net.minecraft.entity.Entity.boundingBox`（`Lelo;` = `Box`）。注意同文件里 `field_6004` 是 `prevPitch`，别搞混。

取消判定的字节码（`onMove`）：

```text
0: getfield  boundingBoxChanged:Z ; ifne 26
7: aload_2 (movement) ; getstatic class_243.field_1353 (Vec3d.ZERO) ; invokevirtual Vec3d.equals
14: ifeq 26
17: invokevirtual CallbackInfo.cancel()
21: putfield  boundingBoxChanged:Z = false      <-- 取消后复位
```

`Vec3d.equals` 用 **`Double.compare`** 逐分量比较（named jar 的 `javap -p -c net.minecraft.util.math.Vec3d` 实读），
所以 **`-0.0` 不等于 `0.0`** —— 复刻时必须用 `Double.compare`，不能写成 `== 0`。
已落成 `VmpAdapter.shouldCancelMove(boolean, double, double, double)` 与可整段回放的 `VmpAdapter.State`。

### 4.1.1 ⚠️ 一条容易被忽略的**黏滞**语义（P2 必须照抄）

把字节码连起来看：`boundingBoxChanged` **只在 cancel 分支里被复位**，而 cancel 又要求它**已经是 false**；
`onBoundingBoxChanged` 只会把它置 `true`、从不复位。于是：

> **一个实体的包围盒只要真正变过一次，VMP 的零位移短路对它就永久失效。**

这不是我读错——两条 `@Inject` 的完整字节码就是这么多。P2 复刻 `Entity.move` 时如果只实现"包围盒没变且位移为零就跳过"，
就会**比现在的服务器多做（=更快但行为不同）**，逐 tick 差分必然爆。`VmpAdapterTest#stateMachineReplaysMixinFields`
把这条黏滞语义钉死在单测里。

### 4.2 「不可配置」是**探测**出来的，不是假设

`VMPMixinPlugin.shouldApplyMixin` 的字节码是一串 `startsWith(前缀)` 判断：
`com.ishland.vmp.mixins.carpet.`（依赖 carpet）、`...playerwatching.optimize_nearby_entity_tracking_lookups`、
`...networking.eventloops.`、`...chunk.loading.portals.`、`...general.cache_ops.biome.`、
`...chunk.iteration.`、`...chunk.loading.async_chunk_on_player_login`、`...chunk.loading.command`、
`...playerwatching.MixinTACSCancelSendingKrypton`（`equals`）、`...networking.avoid_deadlocks`；
**没有任何一条前缀能命中 `entity.move_zero_velocity.MixinEntity`**，末尾直接 `iconst_1; ireturn`（默认放行）。
`VmpAdapter.probeJar` 把插件类的常量池前缀全读出来再判 `startsWith`，得到 `unconditional()==true`。
旁证：服务端 `config/vmp.properties` 里没有对应开关项。

## 5. Carpet / TIS 规则检测

### 5.1 规则文件在哪（javap 实证）

`carpet.api.settings.SettingsManager.getFile()` 的字节码：

```text
0: getfield server:MinecraftServer
4: getstatic class_5218.field_24188
7: invokevirtual MinecraftServer.method_27050(class_5218)   // getSavePath(WorldSavePath)
10: getfield identifier:String
14: invokedynamic makeConcatWithConstants   // 常量池 #473 = "\u0001.conf"
19: invokeinterface Path.resolve(String)
```

`class_5218` 已用 `tools/mapquery.cjs intermediary class_5218` 核过 = **`net.minecraft.util.WorldSavePath`**。
所以规则文件是**存档目录**下的 `<identifier>.conf`（Carpet 的 identifier 就是 `carpet`）。
TIS 通过 `carpettisaddition/mixins/carpet/hooks/*&#47;SettingsManagerMixin` 挂在 Carpet 的 SettingsManager 上。

### 5.2 解析语义（逐条照抄字节码）

`SettingsManager.readSettingsFromConf`：

```text
line = line.replaceAll("[\r\n]", "")
if (line.equalsIgnoreCase("locked")) locked = true
parts = line.split("\\s+", 2)
if (parts.length <= 1) continue
if (values.isEmpty() && parts[0].startsWith("#")) continue
if (parts[1].startsWith("#")) continue
values.put(parts[0], parts[1])
```

注意是 `split(..., 2)` —— **值里的空格与注释会整段保留**（`pushLimit 12 # x` 的值是 `12 # x`，不是 `12`）。
`CarpetRuleProbeTest#parseConfMirrorsBytecode` 把这条语义钉死。

探测的候选路径（按优先级）：
1. `config/cava.json` 的 `compat.ruleFiles` 里显式指定的文件；
2. `<gameDir>/config/carpet.conf`、`<gameDir>/carpet.conf`；
3. `<gameDir>/config/carpet-tis-addition.conf`；
4. `<gameDir>/config/cava/carpet-rules.json` 或 `.properties`（**Cava 自定义**的离线快照格式）；
5. `<gameDir>/*/carpet.conf` 与 `<gameDir>/*/carpet-tis-addition.conf`（存档目录，一层深）。

> **`/testcarpet dump` 的确切输出格式：未验证。** 所以离线快照只接受上面第 4 条那种简单 `规则名 -> 值` 的
> JSON / properties。谁要接真 dump，先补证据再改 `CarpetRuleProbe.parseJson`。

### 5.3 「会毁掉一致性」的规则清单

原始清单 = `docs/CAVA-服务器模组清单.md` 附录 A.4 第 5 条。本层把它拆成两级（**拆分是本流自己的判定，理由写在下面**）：

| 等级 | 含义 | 规则 |
| --- | --- | --- |
| `BASELINE` | **它现在就是整合包的基准**（用户已开启）。不是错误；对应子系统让位 | carpet: `fastRedstoneDust`、`optimizedTNT`、`lagFreeSpawning` |
| `TOXIC` | 默认关；一旦开就会毁掉「与同一套整合包逐 tick 一致」的可复现性 | carpet: `movableBlockEntities`、`tntDoNotUpdate`、`fillUpdates`、`quasiConnectivity`、`pushLimit`、`railPowerLimit`；tis: `redstoneDustRandomUpdateOrder`、`totallyNoBlockUpdate`、`updateSkippingSimulator`、`updateSuppressionSimulator`、`instantBlockUpdaterReintroduced`、`repeaterHalfDelay`、`dustTrapdoorReintroduced`、`optimizedFastEntityMovement`、`optimizedHardHitBoxEntityCollision`、`optimizedTNTHighPriority` |

共 **19 条**。默认值来源：`.cava-research/batchB/rules-carpet.json`（86 条）/ `rules-tis.json`（143 条），
由分片 B 用自写 class-file 解析器从真实 jar 提取；与 A.4 第 1 条手工核过的
`fastRedstoneDust=false`、`lagFreeSpawning=false`、`maxEntityCollisions=0`、`quasiConnectivity=1`、
`fillUpdates=true`、`pushLimit=12`、`railPowerLimit=9` **逐条一致**。

判定规则：**生效值偏离默认值就告警**（数值等价比较：`64` == `64.0` == `64.0d`）。
默认值未提取到的规则写 `?` 且**一律不告警**（不拿推测当结论）。

### 5.4 已知现状（必须写进报告）

> **用户已开启 Carpet `fastRedstoneDust` 与 TNT 优化（+ 无卡顿刷怪 `lagFreeSpawning`）
> ⇒ 红石子系统的基准是它们，不是原版。**

本流**未在本机定位到真实服务器的 `carpet.conf`**（全盘扫 `J:mc` 的 `*.conf` 只命中 LuckPerms / MiniMOTD / 网站配置），
所以在报告里这条的来源写死成「captain 声明 + 规则默认值证据，未在本机实跑验证」，并额外打一条
`CAVA-COMPAT|v1|note|redstone-baseline|...`。**不编数字。**

### 5.5 规则告警如何影响归属（可被配置顶掉）

`compat.autoDeferOnRule`（默认 `true`）：

1. 规则告警 → 对应子系统**强制让位**（owner 置 `mod`）；
2. **但**如果该子系统的**全部**重叠点都被显式配置成 `native-first`，则按配置走原生优先，
   并打 `RULE_DEFER_OVERRIDDEN` 告警（**规则告警本身不会消失**）；
3. `autoDeferOnRule=false` → 规则**只告警**，完全不参与归属。

三条路径都有单测（`CompatReportTest#configCanOverrideRuleDrivenDeferral`）。

## 6. 启动兼容性报告：格式规范

每行以 `CAVA-COMPAT|v1|` 开头，**字段数固定**（单测逐行断言），任何字段里的 `|` 与换行都会被替换成 `/` 与空格。

| 记录类型 | 段数 | 形状 |
| --- | --- | --- |
| `header` | 11 | `CAVA-COMPAT|v1|header|mc=<v>|loader=<v>|native=<status>|mods=<n>|relevant=<n>|overlaps=<n>|defer=<a,b>|rules=<alarm>/<total>` |
| `mod` | 7 | `...|mod|<id>|<version>|<env>|<source>` |
| `owner` | 10 | `...|owner|<subsystem>|<modId>|<key>|<owner>|<configured>|<stage>|<overlap>` |
| `rule` | 11 | `...|rule|<source>|<name>|<value 或 absent>|<default>|<severity>|<subsystem>|<ALARM 或 ok>|<origin>` |
| `lithium` | 8 | `...|lithium|<group>|published=<on 或 off>|wanted=<on 或 off>|consistent=<bool>|<reason>` |
| `adapter` | 5 | `...|adapter|<servercore 或 vmp>|<探测结果>` |
| `note` | 5 | `...|note|<key>|<text>` |
| `warn` | 5 | `...|warn|<code>|<text>` |

告警码：`LITHIUM_METADATA_MISMATCH`、`CARPET_RULES_ABSENT`、`TOXIC_RULE_ON`、`RULE_DEFER_OVERRIDDEN`。

grep 例子（PowerShell，用 `-SimpleMatch` 避开竖线转义）：

```powershell
Get-Content logs/latest.log | Select-String -SimpleMatch 'CAVA-COMPAT|v1|owner|'
Get-Content logs/latest.log | Select-String -SimpleMatch 'CAVA-COMPAT|v1|warn|'
```

归属的取值：`native`（我们在原生侧复刻，对方的补丁不该再跑）/ `mod`（让位，我们完全不介入）/ `vanilla`（没人占，走原版）。
`configured` 是配置里那一格的值（`auto` / `native-first` / `defer`），
`stage` 是决策阶段（`decided` / `pending-p2` / `pending-p3` / `not-hooked`）。

## 7. `config/cava.json`：按子系统 × 按 mod 的覆盖

```json
{
  "native": { "enabled": true },
  "ownership": { "pathfind": "auto", "entity": "auto", "redstone": "auto" },
  "perMod": {
    "lithium": { "pathfind": "auto", "entity": "auto", "redstone": "auto" },
    "servercore": { "pathfind": "auto", "entity": "auto" },
    "vmp": { "entity": "auto" },
    "carpet": { "redstone": "auto" },
    "carpet-tis-addition": { "redstone": "auto", "entity": "auto" }
  },
  "compat": { "autoDeferOnRule": true, "ruleFiles": [] },
  "parity": { "trace": "", "ticks": 0, "label": "", "worldRadius": 8 }
}
```

优先级：**`perMod[mod][subsystem]`（非 `auto`） > `ownership[subsystem]` > 本轮既定默认值**。
`perMod` 写 `auto` 表示「继承粗粒度那一层」，所以旧的 `ownership` 语义**完全保留**（P0 的 `cava.parity` / `cava.Cava` 不受影响）。

## 8. 单测清单（`gradlew test`）

| 文件 | 覆盖 |
| --- | --- |
| `ClassFileProbeTest` | 自写 class 文件解析器（读自己的 class / 读真实 jar / 拒绝非 class 字节） |
| `ModProbeTest` | 从真实 jar 读 `fabric.mod.json`；缺元数据/坏 JSON 跳过；**真实整合包逐个版本断言** |
| `ServerCoreAdapterTest` | 短路语义 4 组合；**接口形状勘误**钉死；缺 jar 不炸 |
| `VmpAdapterTest` | 零位移短路含 `-0.0`；状态机回放；**无条件生效 = 不可配置** |
| `CarpetRuleProbeTest` | `readSettingsFromConf` 语义逐条；JSON/properties 快照；告警判定；候选路径 |
| `LithiumOptionsTest` | 键前缀归一；**发布值恰好只有一组 false**；期望表随归属变化；不一致检测 |
| `CompatConfigTest` | `perMod` 默认值覆盖 CompatTable；读写往返；**细粒度覆盖粗粒度** |
| `CompatReportTest` | 行格式段数；默认归属；**四条配置覆盖路径**；`applyDeferrals` 只禁该禁的 |
| `RealModpackProbeTest` | 对真实 49 个 jar 出整张报告并打印；适配器探测结论断言 |

## 8.1 本轮顺手修的两个真坑（都被单测挡住过）

| 坑 | 现象（实测） | 处理 |
| --- | --- | --- |
| **`CavaConfig` 的最小 JSON 解析器不支持数组** | 真实 `fabric.mod.json` 必有 `authors`/`mixins` 数组 → `期望数字 @211`；结果：**整合包 49 个 jar 探测到 0 个 mod**，`cava.json` 写回再读也失败（P0 的 `CavaConfigTest#roundTrip` 被带红） | 给 `CavaConfig.Parser` **补上数组支持**（纯增量，对象/字符串/数字/布尔/null 语义不变）。`cava.parity` 不受影响 |
| **refmap 的键是内部名（斜杠），不是点号名** | `CavaConfig.refmapEntry` 一开始传 `a.b.C` 去查 `a/b/C` 的表 → 永远查不到，ServerCore/VMP 的"方法号证据"全灭 | 查表时**两种分隔符都试**；单测断言 `push(DDD)V -> method_5762`、`move -> method_5784` 必须命中 |

另外：本机文件沙箱对 `java.io.tmpdir`（实测是 `C:\Users\郁小悟~1\AppData\Local\Temp\dsh-*` 这种 **8.3 短名**路径）
会**间歇性**拒绝建目录（`AccessDeniedException: Failed to create default temp directory`），
所以本流的单测**不用 JUnit 的 `@TempDir`**，改用 `TestPaths.tempDir()` 在工作区 `build/tmp/cava-compat-tests/` 下建目录。
后来者如果也踩到"@TempDir 随机失败"，照这个办法绕。

## 9. 未验证 / 风险 / 请求

| 项 | 状态 |
| --- | --- |
| 真实服务器上 `carpet.conf` 的实际内容 | ❓ **未验证**（本机没找到该文件）。报告在找不到时打 `CARPET_RULES_ABSENT`，**不假装规则都是默认值** |
| `/testcarpet dump` 的输出格式 | ❓ 未验证（只支持 Cava 自定义的简单快照格式） |
| 启动报告在**真实服务端**里的实际输出 | ❓ 未验证（本轮只跑了 `gradlew test` 与离线 jar 探测；没有起服） |
| `fabric.mod.json` 的 `custom` 段被 Fabric Loader 接受 | ✅ 语法与 Lithium 的解析路径都对着字节码核过；**但没在真实服务端启动里看过 Lithium 的日志**（可加 `-Dmixin.debug=true` 复核） |
| ServerCore 的 `isInactive` 反射在真实实体上的行为 | ❓ 未验证（P2 才用） |

### 请求（不越界改别人的东西）

1. **给 captain**：本轮把 `mixin.ai.pathing` 关掉之后，**在 P1 上线之前服务器的寻路会比现在更慢**（Lithium 那个组是
   `LandPathNodeMaker` 缓存短路，删掉它等于纯损失）。这是任务书明确要求的「先关」，我照做了，
   但请确认这是有意的取舍；若想改成「P1 落地时再关」，只需删掉 `fabric.mod.json` 里那一行（单测会同步提醒）。
2. **给 P3 流**：`ConsistencyRule` 的 19 条清单已经带默认值与等级；如果 P3 决定要复刻
   `fastRedstoneDust`，请把 `CompatTable` 里那条 `carpet/CarpetSettings.fastRedstoneDust` 的 `defaultOwner` 从 `MOD` 改成 `NATIVE`
   （改一处即可，报告与单测会自动跟随）。
3. **给 P2 流**：`ServerCoreAdapter.isInactive(Object)` 与 `VmpAdapter.shouldCancelMove(...)`
   已经是可以直接调用的纯/反射入口，P2 复刻 `Entity.move` 时**必须同时复刻 VMP 的零位移短路**，否则行为会变。
4. **给差分测试流**：报告行格式已冻结成表（第 6 节），如果要加字段请**追加在末尾**并同时更新
   `CompatReportTest.FIELD_COUNT`，不要插在中间。

---

## 10. 实测证据（真实输出，未删改）

### 10.1 单测

    pwsh -NoProfile -File scripts/gradlew-cava.ps1 test --console=plain
    > Task :compileTestJava
    > Task :test
    BUILD SUCCESSFUL in 7s

从 `build/test-results/test/*.xml` 汇总（不是手写数字）：

    TOTAL tests=73 failures=0 skipped=0
    cava.compat.CarpetRuleProbeTest     : tests=6  failures=0 skipped=0
    cava.compat.ClassFileProbeTest      : tests=3  failures=0 skipped=0
    cava.compat.CompatConfigTest        : tests=4  failures=0 skipped=0
    cava.compat.CompatReportTest        : tests=11 failures=0 skipped=0
    cava.compat.LithiumOptionsTest      : tests=6  failures=0 skipped=0
    cava.compat.ModProbeTest            : tests=5  failures=0 skipped=0
    cava.compat.RealModpackProbeTest    : tests=2  failures=0 skipped=0
    cava.compat.ServerCoreAdapterTest   : tests=4  failures=0 skipped=0
    cava.compat.VmpAdapterTest          : tests=4  failures=0 skipped=0

（本流新增 **45** 个用例；其余 28 个是 P0 已有的，全部仍然绿。）

### 10.2 整包构建 + remap

    pwsh -NoProfile -File scripts/gradlew-cava.ps1 build --console=plain
    > Task :jar
    > Task :remapJar
    BUILD SUCCESSFUL in 6s

**remap 后的 `build/libs/cava-0.1.0.jar` 里 `custom` 段原样保留**（直接开 zip 读的 `fabric.mod.json`）：

```json
"custom": {
    "lithium:options": {
        "mixin.ai.pathing": false
    }
}
```

### 10.3 真实整合包探测报告（`RealModpackProbeTest` 捕获的 stdout，49 个真实 jar）

```text
CAVA-COMPAT|v1|header|mc=1.20.4|loader=0.19.5|native=OPEN|mods=49|relevant=5|overlaps=10|defer=entity,redstone|rules=0/19
CAVA-COMPAT|v1|mod|carpet|1.4.128+v231205|*|<jar>
CAVA-COMPAT|v1|mod|carpet-tis-addition|1.82.3|*|<jar>
CAVA-COMPAT|v1|mod|lithium|0.12.1|*|<jar>
CAVA-COMPAT|v1|mod|servercore|1.5.0+1.20.4|*|<jar>
CAVA-COMPAT|v1|mod|vmp|0.2.0+beta.7.139|*|<jar>
CAVA-COMPAT|v1|owner|pathfind|lithium|mixin.ai.pathing|native|auto|decided|...
CAVA-COMPAT|v1|owner|pathfind|servercore|optimizations.misc.PathFinderMixin|native|auto|decided|...
CAVA-COMPAT|v1|owner|pathfind|servercore|optimizations.sync_loads.GroundPathNavigationMixin|native|auto|decided|...
CAVA-COMPAT|v1|owner|entity|lithium|mixin.entity.collisions.movement|mod|auto|pending-p2|...
CAVA-COMPAT|v1|owner|entity|servercore|activation_range.EntityMixin#addVelocity|mod|auto|pending-p2|...
CAVA-COMPAT|v1|owner|entity|vmp|entity.move_zero_velocity.MixinEntity|mod|auto|pending-p2|...
CAVA-COMPAT|v1|owner|redstone|lithium|mixin.block.redstone_wire|mod|auto|pending-p3|...
CAVA-COMPAT|v1|owner|redstone|carpet|CarpetSettings.fastRedstoneDust|mod|auto|pending-p3|...
CAVA-COMPAT|v1|owner|redstone|carpet-tis-addition|CarpetTISAdditionSettings.redstoneDustRandomUpdateOrder|mod|auto|pending-p3|...
CAVA-COMPAT|v1|owner|mirror|lithium|mixin.shapes|mod|auto|not-hooked|...
CAVA-COMPAT|v1|rule|carpet|fastRedstoneDust|absent|false|baseline|redstone|ok|-
CAVA-COMPAT|v1|rule|carpet|optimizedTNT|absent|false|baseline|redstone|ok|-
CAVA-COMPAT|v1|rule|carpet|lagFreeSpawning|absent|false|baseline|entity|ok|-
...（另有 16 条 toxic 规则行，全部 value=absent）
CAVA-COMPAT|v1|lithium|mixin.ai.pathing|published=off|wanted=off|consistent=true|...
CAVA-COMPAT|v1|lithium|mixin.entity.collisions.movement|published=on|wanted=on|consistent=true|...
CAVA-COMPAT|v1|lithium|mixin.block.redstone_wire|published=on|wanted=on|consistent=true|...
CAVA-COMPAT|v1|lithium|mixin.shapes|published=on|wanted=on|consistent=true|...
CAVA-COMPAT|v1|adapter|servercore|可用：Inactive=true[servercore$inactiveTick] ActivationEntity=true[servercore$getActivationType, servercore$isExcluded, servercore$getActivatedTick, servercore$setActivatedTick, servercore$getActivatedImmunityTick, servercore$setActivatedImmunityTick, servercore$isInactive, servercore$setInactive, servercore$incFullTickCount, servercore$getFullTickCount] isInactive@ActivationEntity=true isInactive@Inactive=false addVelocity@refmap=true(servercore-common-refmap.json)
CAVA-COMPAT|v1|adapter|vmp|无条件生效（不可配置）：declared=true gated=false prefixes=10 refmap=vmp-fabric-mc1.20.4-refmap.json(move=true,setBoundingBox=true)
CAVA-COMPAT|v1|note|redstone-baseline|用户已开启 Carpet fastRedstoneDust / optimizedTNT（+ 无卡顿刷怪）→ 红石子系统的基准是它们，不是原版；本机未定位到真实服务器的 carpet.conf，所以这些是 captain 声明 + 规则默认值证据，未在本机实跑验证
CAVA-COMPAT|v1|note|subsystem-owners|redstone=mod entity=mod pathfind=native
CAVA-COMPAT|v1|note|rule-files-checked|6
CAVA-COMPAT|v1|note|lithium-static|custom.lithium:options 是静态元数据，运行期无法撤销；只有编辑 cava 的 fabric.mod.json 才能改
CAVA-COMPAT|v1|warn|CARPET_RULES_ABSENT|CARPET_RULES_ABSENT: 没找到任何 Carpet/TIS 规则文件（检查过 6 个候选路径），规则相关判定全部为「未知」，本条不影响归属默认值
```

**两条最关键的实测结论**（就在上面这两行 `adapter` 里）：

1. `isInactive@ActivationEntity=true` 且 `isInactive@Inactive=false` —— 任务书那句「Inactive 有 servercore$isInactive」**被真实 jar 否定**；
   `Inactive` 里只有一个 `servercore$inactiveTick()`。
2. `gated=false` + `prefixes=10` —— VMP 的零位移短路**确实无条件生效**（插件里 10 个门控前缀没有一个命中它），
   所以它「不可配置」是**探测出来的**，不是我假设的。
