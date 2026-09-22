# Cava 兼容性调研：ServerCore / VMP / Krypton / FerriteCore / ModernFix / Packet Fixer

范围：Minecraft **1.20.4 Fabric**。目标：判断这 6 个 mod 是否侵入 Cava 的三个原生子系统
（生物寻路 `PathNodeNavigator.findPath`、实体开销 `Entity.move/collide/push` 等、红石 `RedstoneWireBlock` 等）
以及三类世界镜像（方块状态表 / 16³ 区段镜像 / 实体扁平数组镜像）。

方法：**逐版本抓取真实 mixin 源码**（不是读文档）。六个 mod 的 mixin 源码共 552 个文件已落盘在
`.probe/src/<owner>__<repo>/<branch>/`，脚本见 `.probe/{tree,dl,sites,extract}.mjs`，注入点全表见 `.probe/sites.txt`、`.probe/mixin-map.txt`。
命名对照：Yarn `PathNodeNavigator`=`class_13`=Mojang `PathFinder`；`PathNodeMaker`=`class_8`=`PathNavigationRegion`；
`MobNavigation`=`class_1409`=`GroundPathNavigation`；`Entity`=`class_1297`；`CollisionView`=`class_1941`=`CollisionGetter`；
`EntityView`=`class_1924`=`EntityGetter`；`LivingEntity`=`class_1309`；`VoxelShape`=`class_265`。
（该 mod 若用 Mojang 映射编译，源码里出现的就是 Mojang 名，运行期两者都落到同一个 intermediary 方法。）

## 结论速览

| mod | 1.20.4 版本 | 维护 | 许可证 | 寻路(`findPath`) | 实体(`move/push/collide`) | 红石 | 方块状态表 | 区段镜像 | 实体镜像 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ServerCore | 1.5.0+1.20.4 | 该分支停更(2024-04-07)，项目活跃 | MIT(+GPL 来源片段) | **命中 findPath** | **命中 move/push** | 无 | 无 | 无 | **有影响(激活范围)** |
| VMP | 0.2.0+beta.7.139+1.20.4 | 该分支停更(2024-03-30)，项目活跃 | MIT | 无 | **命中 move(同点)** | 无 | 无 | 有(去锁) | 有(velocityDirty) |
| Krypton | 0.2.6（无 1.20.4 分支） | 1.20.x 停更(2024-01-02) | LGPL-3.0 | 无 | 无 | 无 | 无 | 无 | 无(仅追踪层) |
| FerriteCore | 6.0.3-fabric | 1.20.x 停更(2023-12-13) | MIT | 无 | 无 | 无 | **强相关(去重)** | 有(opt-in) | 无 |
| ModernFix | 5.17.0+mc1.20.4 | 分支 EOL(2024-04-24) | LGPL-3.0-only | 无 | 无 | 无 | **相关(缓存/构造)** | **相关(data 替换)** | 无 |
| Packet Fixer | 3.3.2（含 1.20.4） | **仍在维护**(2026-06) | MIT | 无 | 无 | 无 | 无 | 无 | 无 |

**一句话**：只有 ServerCore 真正侵入 Cava 的原生方法；红石子系统与这 6 个 mod **完全无交集**；
其余交集全部落在三类镜像的"对象标识/字段被替换"上，属于语义约束而非注入冲突。

---

## 1. ServerCore

1. **版本/维护/许可**：Modrinth `1.5.0+1.20.4`（2024-04-02）；分支 `ver/1.20.4` 最后提交 **2024-04-07** → 1.20.4 线已冻结，但项目本体活跃（Modrinth updated 2026-09-16，已支持到 26.3）。Modrinth 标 **MIT**；GitHub 未识别根许可证文件，且 activation-range 相关源码头部自述 *"Based on: Paper & Spigot (Entity-Activation-Range.patch) … License: GPL-3.0 (licenses/GPL.md)"* —— 再分发时这段的来源需注意。
2. **注入方式与命中**：抓取的 65 个 mixin **无 @Overwrite**；用 @Inject / @Redirect / @ModifyVariable / @ModifyConstant / @ModifyArg / @ModifyExpressionValue / @ModifyReturnValue / @WrapWithCondition(MixinExtras)。**命中我们的类**：
   - `optimizations.misc.PathFinderMixin` → `PathFinder`(=我们的 **PathNodeNavigator/findPath**)：`@Redirect` ×4（`Set.stream()`、`Collectors.toMap`、`Stream.collect`、`Sets.newHashSetWithExpectedSize`）+ `@ModifyVariable` ×2（index=8 换成 `Object2ObjectOpenHashMap` 并用 `nodeEvaluator.getGoal` 预填；index=10 换成 `ObjectArraySet`）。
   - `optimizations.sync_loads.GroundPathNavigationMixin` → `GroundPathNavigation`(=我们的 **MobNavigation**)：`@Inject(method="createPath(BlockPos,int)", at=HEAD, cancellable)`，区块未加载直接返回 null。
   - `features.activation_range.EntityMixin` → `Entity`：**`@Inject(method="push(DDD)V", at=HEAD, cancellable)`**（未激活实体不被推挤）；**`@Inject(method="move", at=INVOKE Entity.limitPistonMovement, shift=BEFORE)`**（活塞推动刷新激活计时）。另 `features.misc.EntityMixin` 对 `findDimensionEntryPoint` 有 1 个 @Redirect；`inactive_ticks.LivingEntityMixin` 只有 @Shadow。
   - 其余为 `ServerLevel`(`tick` / `tickNonPassenger` @WrapWithCondition 跳过 `Entity.tick()`)、`ChunkMap.playerIsCloseEnoughForSpawning`、`ServerChunkCache`×3、`LevelChunk`、`ChunkHolder`、`NaturalSpawner`、`MobCategory` 等。
   - **0 命中的我们的目标**：红石全部 8 个类、`CollisionView.getBlockCollisions`、`EntityView.getEntities`、`LivingEntity.travel`、`VoxelShape`、`BlockState`、`PathNodeMaker/LandPathNodeMaker/PathNode/PathNodeType/PathMinHeap/Path/TargetPathNode`。
3. **冲突形式**：
   - 寻路 = **重复优化 + 死代码**（非崩）。我们在 HEAD cancel 后，它的 4 个 @Redirect / 2 个 @ModifyVariable 永不执行，它"少建 stream/集合"的优化被顶掉。**它无法用配置让位**：`OptimizationConfig` 只有 `reduce-sync-loads` / `cache-ticking-chunks` / `fast-biome-lookups` / `cancel-duplicate-fluid-ticks` 四项，`ServerCoreMixinPlugin.shouldApplyMixin` 也只对 `optimizations.sync_loads` / `biome_lookups` / `ticking.chunk.cache` / `LiquidBlockMixin` 做前缀判断，**PathFinderMixin 永远 return true**。
   - 实体 = **同点冲突**（`push` 双方都在 HEAD+cancellable；Mixin 允许并存不会崩，但我们会覆盖它"未激活不被推挤"的语义）+ **跳点冲突**（`move`：HEAD cancel 会跳过它的活塞激活刷新）。
   - 红石 = 无交集。
4. **可行共存方案**：(a) 原生 `push` 入口先读它的公开接口 `me.wesley1808.servercore.common.interfaces.activation_range.Inactive`（`servercore$isInactive`），为真则直接 return，复现其语义；(b) 原生 `findPath` 内部按它的口径构造目标 Map（`Object2ObjectOpenHashMap` + `nodeEvaluator.getGoal`），或干脆**在检测到 servercore 时让我们的原生 findPath 让位**（它没有开关，只能由我们让）；(c) 原生 `move` 里补做"活塞推动 → 刷新激活计时"，或接受该 tick 实体保持未激活（需与它的 activation-range 配置对齐）。
5. **镜像相关**：activation range 会让一批实体**整 tick 不 tick**（`ServerLevel.tickNonPassenger` 用 @WrapWithCondition 直接跳过 `Entity.tick()`）→ 实体镜像每 tick 读到的是"不变的位置"，这是预期；但**不要主动为这些实体写回/唤醒**，也不要假设每 tick 必有新位置。方块状态表 / 区段镜像**无影响**（它不碰 BlockState、PalettedContainer、LevelChunkSection）。
6. **证据**：`raw.githubusercontent.com/Wesley1808/ServerCore/ver/1.20.4/...`（PathFinderMixin.java、GroundPathNavigationMixin.java、features/activation_range/EntityMixin.java、features/misc/EntityMixin.java、ServerCoreMixinPlugin.java、OptimizationConfig.java、common/resources/servercore.common.mixins.json）；`api.modrinth.com/v2/project/servercore`；`github.com/Wesley1808/ServerCore/commits/ver/1.20.4`。
7. **置信度**：源码证据（高）。未验证：1.20.4 线是否有未发布的后续修复；activation range 在 Cava 场景下的具体 tick 分布（需实测）。

---

## 2. Very Many Players (VMP)

1. **版本/维护/许可**：`0.2.0+beta.7.139+1.20.4`（2024-03-30）；分支 `ver/1.20.4` 最后提交 **2024-03-30** → 1.20.4 线冻结，项目活跃（updated 2026-09-15，已到 26.3）。**MIT**。
2. **注入方式与命中**：71 个 mixin 文件 / 62 个 mixin 类，有 `VMPMixinPlugin` + `config/vmp.properties`。@Inject / @Redirect / @ModifyVariable / @Overwrite / Accessor 混用。**命中我们的类**：
   - `entity.move_zero_velocity.MixinEntity` → `net.minecraft.entity.Entity`：**`@Inject(method="move", at=HEAD, cancellable=true)`**（`movement.equals(Vec3.ZERO) && !boundingBoxChanged` 时 cancel）+ `@Inject(method="setBoundingBox", at=HEAD)`。**与我们的注入点完全重合。**
   - `chunk.loading.portals.MixinEntity` → `Entity`：只碰 `tickPortal`(@Inject HEAD / @Redirect `getMaxNetherPortalTime`) 与 `getTeleportTarget`(@Inject HEAD, cancellable)，**不碰 move/collide/push**。
   - `general.no_locking.MixinPalettedContainer` → `PalettedContainer`：**`@Overwrite lock()` / `@Overwrite unlock()`**（Yarn 名，Mojang 对应 acquire/release）→ 去掉线程检测。
   - `playerwatching.optimize_nearby_entity_tracking_lookups.MixinEntityTrackerEntry` → `EntityTrackerEntry`：duck 接口 `vmp$tickAlways()` 会写 **`entity.velocityDirty`** 并强制追踪 tick；`entitytracker.MixinThreadedAnvilChunkStorageEntityTracker` → `@Redirect` `getMaxTrackDistance()` in `updateTrackedStatus`（动态追踪距离，按 tick 缓存）。
   - `general.collections.MixinTypeFilterableList` 优先级 **1005**，源码注释写明 *"priority compatibility hack for lithium"*（作者用优先级差回避同点冲突的既有做法）。
   - **0 命中**：`CollisionView.getBlockCollisions`、`EntityView.getEntities`、`LivingEntity.travel`、`VoxelShape`、`BlockState`、红石 8 类、`PathFinder/NodeEvaluator`。
3. **冲突形式**：`Entity.move` = **同点重复优化**（不崩）；`PalettedContainer` 去锁 = **语义冲突**（若镜像假设有线程保护会失效；只读镜像无影响），且 `general.no_locking` **不在 plugin 的任何开关分支里 → 恒生效、无法配置关闭**；实体镜像 = 需避开 `velocityDirty` 作为我们自己的状态位；红石/寻路/方块状态表 = 无交集。
4. **可行共存方案**：原生 `move` 保留等价短路（零位移且包围盒未变则直接返回），或由我们在检测到 `vmp` 时让位；镜像只读、不做加锁假设；实测其 `vmp$tickAlways` 对"未激活实体"的影响。VMP 本身对 krypton/carpet/raknetify/c2me 都有 `isModLoaded` 分支 → 说明社区惯例是"检测到对方就让位"，我们也可以让它检测到 Cava。
5. **可配置开关（`config/vmp.properties`，源码默认值）**：`use_optimized_entity_tracking=true`、`use_async_portals=true`、`use_multiple_netty_event_loops=true`、`use_async_chunks_on_login=true`、`exp_use_optimized_chunk_ticking_iteration=false`。
6. **证据**：`raw.githubusercontent.com/RelativityMC/VMP-fabric/ver/1.20.4/`（mixins/entity/move_zero_velocity/MixinEntity.java、mixins/general/no_locking/MixinPalettedContainer.java、mixins/chunk/loading/portals/MixinEntity.java、mixins/entitytracker/*、mixins/playerwatching/*、mixins/VMPMixinPlugin.java、common/config/Config.java、resources/fabric.mod.json 声明 `minecraft >=1.20.2-beta.2`）；`api.modrinth.com/v2/project/vmp-fabric`。
7. **置信度**：源码证据（高）。未验证：`vmp$tickAlways` 与 ServerCore activation range 同时存在时的实际交互（需实测）。

---

## 3. Krypton

1. **版本/维护/许可**：**1.20.4 没有专属分支**；仓库分支只有 dev/mc-1.17…dev/mc-1.20.5、master。1.20.4 可用版本是 **0.2.6**（Modrinth 2024-01-02，tag `v0.2.6` 最后提交 2024-01-02）。tag 的 `gradle.properties` 是 `minecraft_version=1.20.2`，`fabric.mod.json` 声明 `depends.minecraft ">=1.20.2"` → **靠版本区间在 1.20.4 上运行**。仓库仍活跃（master 已到 1.20.5+，pushed 2026-08），1.20.x 停更。**LGPL-3.0**。
2. **注入方式与命中**：11 个 mixin（`krypton.mixins.json`，`required=true`，plugin `KryptonMixinPlugin`）：`ClientConnection`×3（compression / encryption / no-flush，@Inject+@Shadow）、`ServerLoginNetworkHandler`(@Redirect×2)、`LegacyQueryHandler`(@Inject)、`SplitterHandler`(**@Overwrite**)、`VarInts`(**@Overwrite**×2)、`StringEncoding`(**@Overwrite**)、`EntityTrackerEntry`(@Redirect)、`ServerCommonNetworkHandler`(Accessor)、`CustomPayloadS2CPacket`(@ModifyVariable)、`SharedConstants`(@Redirect)。
3. **冲突形式**：**与 Cava 三个原生子系统全部无交集** —— 红石 8 类、`PathFinder/NodeEvaluator`、`Entity.move/push/collide`、`CollisionView`、`EntityView`、`LivingEntity.travel`、`VoxelShape`、`BlockState`、`PalettedContainer`（区段）**逐项 0 命中**。Krypton 改的是 **Netty pipeline 与编解码**（压缩/加密/VarInt/帧切分/flush），不触碰世界数据与实体位置/速度/碰撞箱。
4. **可行共存方案**：无需为 Cava 做任何处理。唯一需要知道的是**追踪层**：Krypton 的 `EntityTrackerEntry` @Redirect 与 VMP 的重叠，VMP 已专门写 `playerwatching.MixinTACSCancelSendingKrypton`（`@Dynamic("Compatibility hack for krypton")` + `isModLoaded("krypton")`）来让位 —— 若 Cava 将来也碰 `ChunkMap/EntityTracker`，需照抄这种"检测后让位"的模式。
5. **旁证（注入冲突的真实后果）**：krypton **issue #66** 记录 Krypton 与 Lithium 在 entity tracker 上同点 @Redirect 时服务端只打印 *"@Redirect conflict. Skipping lithium… already redirected by krypton…"* 并**跳过后者**，不崩溃。注意该 issue 是 **1.18.2 时代**、混入类名 `TacsTrackedEntityMixin`，与 0.2.6 的 `EntityTrackerEntryMixin` **不是同一个类**，只能作为"同点 @Redirect 的降级行为"参考，不能外推到 1.20.4。
6. **证据**：`raw.githubusercontent.com/astei/krypton/v0.2.6/`（gradle.properties、src/main/resources/fabric.mod.json、krypton.mixins.json、mixins/**.java）；`api.modrinth.com/v2/project/krypton/version`（1.20.4 仅 0.2.4/0.2.5/0.2.6）；`github.com/astei/krypton/commits/v0.2.6.atom`；`github.com/astei/krypton/issues/66`。
7. **置信度**：源码证据（高）。**未验证**：0.2.6 在 1.20.4 上的实机可用性（依据是 fabric.mod.json 的 `>=1.20.2` 与 Modrinth 版本标注，未启动过服务器）。

---

## 4. FerriteCore

1. **版本/维护/许可**：`6.0.3-fabric`（Modrinth 2023-12-13）；分支 `build-6.0.3` 的最后一次提交就是 **"Update to Minecraft 1.20.4"**（2023-12-13）→ 1.20.x 线停更，项目活跃至 26.x。**MIT**。
2. **结构**：mixin 按特性拆成 **8 个独立 config**（`fastmap` / `blockstatecache` / `threaddetec` / `predicates` / `mrl` / `modelsides` / `dedupbakedquad` / `dedupmultipart`），每个配独立 `Config` 插件；配置落盘 **`config/ferritecore.mixin.properties`**（源码 `ConfigFileHandler`：`Constants.MODID + ".mixin.properties"`）。`blockstatecache` 与 `fastmap` 两组**不区分 client/server → 专用服务器上生效**（只有 fabric 的 `MinecraftMixin` 是 client-only）。
3. **命中我们的类**：
   - **方块状态表**：`blockstatecache.BlockStateBaseMixin` `@Inject(method="initCache", at=HEAD/TAIL)` → `BlockStateCacheImpl.deduplicateCachePre/Post`，全局去重表 `CACHE_COLLIDE` / `CACHE_PROJECT` / `CACHE_FACE_STURDY`；`blockstatecache.BlockStateCacheMixin` `@Mixin(targets="…BlockBehaviour$BlockStateBase$Cache")`，`@Shadow @Mutable` `collisionShape` / `occlusionShapes` / `faceSturdy` 并暴露 get/set。
   - **VoxelShape**：同组 6 个 Accessor（`VoxelShape` / `ArrayVoxelShape` / `BitSetDiscreteVoxelShape` / `DiscreteVoxelShape` / `SliceShape` / `SubShape`）暴露内部字段供按内容比较。
   - **BlockState 属性表**：`fastmap.FastMapStateHolderMixin` → `StateHolder`：**`@Overwrite populateNeighbours(Map)`** + `@Redirect Table.get` in `setValue`/`trySetValue`（`neighbours` 这张 Guava Table 被换成 FastMap）。
   - **区段镜像**：`threaddetec.PalettedContainerMixin` → `PalettedContainer`：`@Inject(at=TAIL)` 3 个构造器把 `threadingDetector` 置 null + **`@Overwrite acquire()/release()`**。
   - **0 命中**：`Entity.move/push/collide`、`LivingEntity.travel`、`CollisionView`、`EntityView`、`findPath`/`NodeEvaluator`、红石 8 类。
4. **冲突形式（本次最需要注意的一个）**：
   - 方块状态表 = **语义冲突**：去重后"内容相同"的状态**共享同一个 `VoxelShape` / `boolean[]` 实例** → 若我们的方块状态表用**对象身份**（`==` / `IdentityHashMap`）做键或缓存，会误判/漏判。必须按 BlockState 的全局 id 或形状内容建表。`getCollisionShape()` 的**返回值语义仍与原版等价**（这点可放心）。
   - fastmap = **字段级语义冲突**：`neighbours` 表被替换、`populateNeighbours` 被 @Overwrite → **不要读 BlockState 的内部 `neighbours` 字段**，只用公开 API（`getValue`/`with`/`setValue`）。
   - 区段镜像：`threaddetec` 是 **opt-in（默认关闭）**，所以默认**不会**与 VMP 的 `@Overwrite lock/unlock` 撞车。
   - 红石 / 实体 / 寻路：无交集。
5. **可行共存方案（它给了现成杠杆）**：`replaceNeighborTable` 系列开关（默认 true）——`replaceNeighborLookup`、`replacePropertyMap`、`blockstateCacheDeduplication`（**若我们要稳定的 shape 身份，可让用户关这一项**）；而 **`populateNeighborTable`（默认 false）的官方注释直接写着"重新填充原版邻居表，用于少数直接访问该表的 mod"** —— 这正是给我们的反向开关。`useSmallThreadingDetector` 默认 false（注释自述"因极罕见且极难复现的崩溃默认关闭"）。
6. **证据**：`raw.githubusercontent.com/malte0811/FerriteCore/build-6.0.3/`（blockstatecache/{BlockStateBaseMixin,BlockStateCacheMixin,Config}.java、fastmap/{FastMapStateHolderMixin,Config}.java、threaddetec/{PalettedContainerMixin,Config}.java、mixin/config/FerriteConfig.java、Fabric/…/platform/ConfigFileHandler.java、Common/resources/ferritecore.{blockstatecache,fastmap,fabric}.mixin.json）；`api.modrinth.com/v2/project/ferrite-core/version`；`github.com/malte0811/FerriteCore/commits/build-6.0.3.atom`。
7. **置信度**：源码证据（高）。

---

## 5. ModernFix

1. **版本/维护/许可**：`5.17.0+mc1.20.4`（2024-04-24，1.20.4 线最后一版）；分支 **`eol/1.20.4`（eol = 已终止维护）**，最后提交 2024-04-24；项目本体活跃（1.21.4/26.1）。**LGPL-3.0-only**（Modrinth）。
2. **结构与注入方式**：约 **128 个 mixin 类**（common + fabric + neoforge + testmod 四个 source set），plugin `ModernFixMixinPlugin`，配置 **`config/modernfix-mixins.properties`**；`shouldApplyMixin` 用"mixin 类名去掉包前缀"（如 `perf.compact_bit_storage.PalettedContainerMixin`）查表决定是否应用 → **可逐项关闭**（属性文件里的精确键拼写本次未逐字验证）。注入方式 @Inject / @Redirect / @ModifyVariable / @ModifyArg / @ModifyConstant / `@Overwrite` / `@WrapOperation` 都有。
3. **命中我们的类（全在"镜像/缓存"面，不改行为逻辑）**：
   - **区段镜像**：`perf.compact_bit_storage.PalettedContainerMixin` `@Inject` 到 `PalettedContainer.read(FriendlyByteBuf)` 的 `data` PUTFIELD 之后（`LocalCapture`）→ 读网络包后**可能把 `this.data` 换成更小的存储**（源码注释：把超大 chunk 的调色板 id 挪回 0 并重建）。
   - **方块状态表**：`perf.cache_blockstate_cache_arrays.AbstractBlockStateCacheMixin`（`targets=BlockStateBase.Cache`，@Redirect×2）、`perf.reduce_blockstate_cache_rebuilds.BlockStateBaseMixin`（@Redirect×4）+ `BlocksMixin`、`bugfix.chunk_deadlock.BlockStateBaseMixin`（priority=100，@ModifyVariable）、`perf.mojang_registry_size.StateHolderMixin`、`perf.state_definition_construct.StateDefinitionMixin`。
   - **区块对象**：`perf.ticking_chunk_alloc.ChunkAccessMixin` **`@Overwrite getAllReferences()`**（源码注释自承 *"technically, this introduces an API change, as the return value may no longer be a live view"*）、`ChunkHolderMixin` **@Overwrite `getTickingChunk()` / `getFullChunk()`**。
   - **0 命中**：`Entity.move/push/collide`、`LivingEntity.travel`、`CollisionView`、`EntityView`、`findPath`/`NodeEvaluator`、红石 8 类（它没有 Entity 行为类 mixin，唯一 `LivingEntity*` 是客户端的 `LivingEntityRenderer`）。
4. **冲突形式**：**无注入点冲突、无语义冲突**（它是"同语义、更省内存/更少分配"）。真正的风险是**对象标识与字段被替换**：区段镜像若缓存 `PalettedContainer.data` 引用，会在 `read()` 后读到已被丢弃的对象 → **必须每次经 `getData()` 重取，或在 `read()` 之后重建镜像**；方块状态表同理不要持有内部数组/身份的长期引用。`getAllReferences()` 的语义变更（不再保证活视图）只影响我们用它的场合。
5. **可行共存方案**：优先在 `modernfix-mixins.properties` 逐项关闭 `perf.compact_bit_storage.PalettedContainerMixin` / `perf.ticking_chunk_alloc.*`（若我们的区段镜像依赖 `data` 稳定性）；其余（方块状态缓存复用）按第 4 条改为"内容/ID 建键"即可共存，无需互相禁用。
6. **证据**：`raw.githubusercontent.com/embeddedt/ModernFix/eol/1.20.4/`（common/mixin/perf/compact_bit_storage/PalettedContainerMixin.java、perf/cache_blockstate_cache_arrays/AbstractBlockStateCacheMixin.java、perf/reduce_blockstate_cache_rebuilds/*、perf/ticking_chunk_alloc/{ChunkAccessMixin,ChunkHolderMixin}.java、core/ModernFixMixinPlugin.java、core/config/ModernFixEarlyConfig.java）；`api.modrinth.com/v2/project/modernfix/version`；`github.com/embeddedt/ModernFix/commits/eol/1.20.4.atom`。
7. **置信度**：源码证据（高，针对 EOL 的 1.20.4 分支）。未验证：`modernfix-mixins.properties` 中键的精确书写形式。

---

## 6. Packet Fixer

1. **版本/维护/许可**：**1.20.4 存在**，且是本次 6 个 mod 里唯一仍在积极维护的：Modrinth 最新 `3.3.2`（2026-04-04），1.20.4 共有 20 个版本；backport 分支 `1.18.2-to-1.21.8` 最后提交 **2026-06-10**。**MIT**。
2. **结构**：按 MC 版本分 source set（`fabric/java17/v1_18`、`v1_19`、`v1_20`、`v1_20_2` …）；**1.20.4 用的是 `v1_20_2`** —— 已核对 `fabric/java17/v1_20_2/build.gradle.kts` 里 `minecraftVersion = "1.20.4"`。
3. **注入方式与命中**：几乎全部是 **`@ModifyConstant`**（放宽网络/NBT 的长度上限常量），个别 @Redirect。涉及 `FriendlyByteBuf`(@ModifyConstant×8)、`PacketEncoder`、`CompressionDecoder`、`Varint21FrameDecoder`(priority=1001)、`NbtAccounter`(@Redirect)、`Connection`/`ServerConnectionListener`（`targets=` 匿名内部类）、各类 custom payload / query 包、`ServerGamePacketListenerImpl`、`ServerLoginPacketListenerImpl` 等。**对我们的目标类 0 命中**：红石 8 类、`PathFinder/NodeEvaluator`、`Entity.move/push/collide`、`CollisionView`、`EntityView`、`LivingEntity.travel`、`VoxelShape`、`BlockState`、`PalettedContainer` 全无交集。
4. **冲突形式**：**无冲突**，也没有三个镜像的数据一致性影响 —— 它不是世界模拟优化，只改包/字符串/NBT 的尺寸上限与少量编解码。
5. **可行共存方案**：无需处理。唯一协同点：如果 Cava 的原生层自己读写包/序列化 NBT，要注意它把原版上限调大后的取值（读取它的配置保持一致），否则可能出现"原版能收、我们拒收"的不一致。
6. **证据**：`raw.githubusercontent.com/TonimatasDEV/PacketFixer/1.18.2-to-1.21.8/`（`fabric/java17/v1_20_2/build.gradle.kts`、`fabric/java17/v1_20_2/src/main/java/dev/tonimatas/packetfixer/mixins/v1_20_2_fabric/*.java`、同名 mixins.json）；`api.modrinth.com/v2/project/packet-fixer/version`；`github.com/TonimatasDEV/PacketFixer/commits/1.18.2-to-1.21.8.atom`。
7. **置信度**：源码证据（高）。

---

## 7. 对 Cava 设计的直接影响清单

| # | 结论 | 建议动作 |
| --- | --- | --- |
| 1 | **红石子系统与这 6 个 mod 零交集**（`RedstoneWireBlock`/`NeighborUpdater`/`ChainRestrictedNeighborUpdater`/`AbstractRedstoneGateBlock`/`ComparatorBlock`/`RepeaterBlock`/`ObserverBlock`/`PistonBlock` 在 552 个 mixin 文件里 0 命中） | 红石原生路径不必为这 6 个 mod 做任何兼容分支 |
| 2 | **`PathNodeNavigator.findPath` 只有 ServerCore 命中**，且它无配置开关 | 二选一：原生实现按它的口径构造目标 Map，或在检测到 `servercore` 时让位；并把这条写进"补丁所有权模型" |
| 3 | **`Entity.push(DDD)V` 与 ServerCore 同点（HEAD+cancellable）**；`Entity.move` 与 ServerCore（INVOKE 点）和 VMP（HEAD+cancellable）都有交集 | 原生 push 入口读 `Inactive`；原生 move 保留"零位移短路"并补做活塞激活刷新 |
| 4 | **方块状态表是本轮最大的真实风险**：FerriteCore 去重让"内容相同"的状态共享 `VoxelShape`/`boolean[]` 实例；ModernFix 改写缓存数组与 StateHolder 构造 | 建表键改用 **BlockState 全局 id** 或**形状内容**，禁止对象身份比较；不读 `neighbours` 内部字段；必要时用 FerriteCore 的 `populateNeighborTable`（反向开关）或关闭 `blockstateCacheDeduplication` |
| 5 | **区段镜像要处理 `PalettedContainer.data` 可被替换**（ModernFix `read()` 后重建） | 每次经 `getData()` 取，或在 `read()` 之后重建镜像；不要长期缓存 `data` 引用 |
| 6 | **`PalettedContainer` 加锁移除**：VMP 恒生效（@Overwrite lock/unlock），FerriteCore 的同类是 opt-in（默认关） | 镜像只读、不假设线程保护；不需要为此禁用 VMP |
| 7 | **实体镜像与 activation range 冲突**：ServerCore 会让一批实体整 tick 不 tick，VMP 会强制追踪 tick 并写 `velocityDirty` | 镜像接受"位置有时不变"；不要复用 `velocityDirty`；不要主动唤醒未激活实体 |
| 8 | 六个 mod 的**注入方式分布**：@Inject/@Redirect/@ModifyVariable 为主；`@Overwrite` 出现在 VMP(PalettedContainer.lock/unlock)、Krypton(SplitterHandler/VarInts/StringEncoding)、FerriteCore(populateNeighbours、acquire/release)、ModernFix(ChunkAccess.getAllReferences、ChunkHolder.getTickingChunk/getFullChunk 等)。**没有任何一个 @Overwrite 我们的三类目标方法** | 我们"不 @Overwrite 原版方法"的约束在这 6 个 mod 面前不产生对抗 |
