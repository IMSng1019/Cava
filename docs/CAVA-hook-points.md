# Cava 注入点清单（1.20.4 / Yarn 映射，已逐条核实）

配套：docs/CAVA-v1-plan.md（总体方案）、docs/CAVA-platform-and-compat.md（兼容矩阵）、docs/CAVA-mod-audit-*.md（逐 mod 源码审计）。

## 0. 核实方式与一条勘误

- **方法名**：本机 Yarn 1.20.4+build.3 官方映射逐行核对（net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny）。
- **其它 mod 的重叠**：源码级审计（552 个 mixin 源文件 + 官方 jar 的 refmap），不是文档推测。
- **勘误（已精确到「哪套映射」）**：v1 方案里写的 findPath / RedstoneWireBlock.getPower / Entity.collide / EntityView.getEntities 在 1.20.4 的 **Yarn** 里都不存在，照那些名字写 mixin 会 remap 失败。但要区分「Yarn 名不存在」与「方法不存在」——下面这些是同一批方法的两套名字，已用本机 Yarn tiny 与本机 Mojang 官方 server.txt 双向核对：

| Yarn（本项目使用） | Mojang（很多 mod 源码使用） |
| --- | --- |
| PathNodeNavigator.findPathToAny | PathFinder.findPath（两个重载 = method_52 / method_54） |
| RedstoneWireBlock.getReceivedRedstonePower | RedStoneWireBlock.calculateTargetStrength |
| RedstoneWireBlock.update | RedStoneWireBlock.updatePowerStrength（**1.20.4 确实存在**，只是 Yarn 名不叫它） |
| Entity.adjustMovementForCollisions | Entity.collideBoundingBox |
| EntityView.getOtherEntities | EntityGetter.getEntities |

**读别的 mod 源码时必须先确认它用哪套映射**：Alternate Current 与 Carpet 的 mixin 写的是 Mojang 名，这正是「看起来不冲突、其实撞在同一个方法上」的常见原因。

## 1. 我们真正要注入的点

| # | 用途 | Yarn 类.方法 | intermediary | 签名要点 | 其它 mod 是否也碰 | 冲突性质与策略 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 寻路主体（接管） | PathNodeNavigator.findPathToAny | method_52 / method_54（Set 版 / Map 版两个重载） | 返回 Path | ServerCore @Redirect×4 + @ModifyVariable×2（改 Map/Set 实现，**无 @Overwrite**，且不可配置） | 无硬冲突：我们 HEAD-cancellable 提前返回后对方补丁自然不执行；语义仍是原版 |
| 2 | 寻路类型判定 | LandPathNodeMaker.getCommonNodeType / getNodeTypeFromNeighbors / getLandNodeType | method_58 / method_59 / method_23476 | — | Lithium（priority=990，cancellable HEAD）+ @Redirect | **我们不注入这里**（native 自算类型），只需语义对齐 |
| 3 | 寻路区域视图 | ChunkCache.getBlockState / getFluidState | — | — | Lithium @Overwrite 且新增字段 | **禁止读它的字段/假设布局**；我们用自建区段镜像 |
| 4 | 导航触发 | EntityNavigation.recalculatePath / startMovingAlong / stop | method_6356 / method_6334 / method_6340 | — | Lithium @Inject（inactive_navigations） | 不需要注入；注意"不活跃生物可能长期不重算路径" |
| 5 | 建路入口 | MobNavigation.createPath | 待补 | — | ServerCore @Inject HEAD cancellable（受 reduce-sync-loads 控制） | 需适配或让位 |
| 6 | 实体移动 | Entity.move | method_5784 | — | **VMP @Inject HEAD cancellable（同点）**；ServerCore @Inject（INVOKE limitPistonMovement 之前）；Lithium experimental（默认关） | 同点竞争：原生 move 需复刻 VMP 的"零位移+包围盒未变则取消"短路、复刻 ServerCore 的激活计时刷新 |
| 7 | 方块碰撞求解 | CollisionView.getBlockCollisions | method_20812 | 返回 Iterable | 无人碰（Lithium 只实现了 isSpaceEmpty=method_8587） | 安全 |
| 8 | 合并碰撞求解 | Entity.adjustMovementForCollisions | method_20736（静态）/ method_17835（实例） | 返回 Vec3d | **Lithium @Overwrite（静态版）+ @Redirect(require=5)（实例版）** | **真冲突**：要么用 lithium:options 关掉 mixin.entity.collisions.movement，要么本子系统让位 |
| 9 | 实体推挤 | Entity.pushAwayFrom | method_5697 | — | **ServerCore Entity.push @Inject HEAD cancellable（同点）** | 同点：读它的公开接口 Inactive（servercore$isInactive）复刻短路，否则让位 |
| 10 | 挤压伤害 | LivingEntity.tickCramming | method_6070 | — | Lithium @Redirect（unpushable_cramming） | 可选，后置 |
| 11 | 射线 | BlockView.clip；（Entity 侧方法名待核实） | 待补 | — | 未发现 | 安全 |
| 12 | 实体枚举 | EntityView.getOtherEntities / getEntityCollisions | method_8333 / method_8335 / method_20743 | 带 Predicate | Lithium 改 EntityTrackingSection（unpushable_cramming） | 谓词必须留在 Java 侧执行 |
| 13 | 红石线网取电 | RedstoneWireBlock.getReceivedRedstonePower（Mojang: calculateTargetStrength） | method_27842 | 返回 int | **Lithium @Inject HEAD cancellable（priority=990，整段替换算法）** | **真冲突**：优先用 lithium:options 关掉 mixin.block.redstone_wire；若要共存必须 priority < 990 |
| 13b | 红石线网更新（方案 B 才动） | RedstoneWireBlock.update（Mojang: updatePowerStrength） | method_10485 | — | **Alternate Current @Inject HEAD cancellable（事实接管）**；**Carpet fastRedstoneDust @Inject HEAD cancellable（默认关）**；**TIS @ModifyVariable** | **真冲突**：红石只能二选一，检测到它们启用就显式让位。AC 没有关闭机制；AC 还自建 InstantNeighborUpdater，**绕开 ChainRestrictedNeighborUpdater**，挂队列的钩子会漏更新 |
| 14 | 红石线网更新 | RedstoneWireBlock.update / updateNeighbors / updateOffsetNeighbors | method_10485 / method_10479 / method_27844 | — | Lithium 只 @Redirect(Direction.values())，**算法本体未改** | 可安全镜像 |
| 15 | 二极管取电 | AbstractRedstoneGateBlock.getPower | method_9991 | 返回 int | 9 个 mod 全部 0 命中 | 安全 |
| 16 | 邻居更新队列 | NeighborUpdater / ChainRestrictedNeighborUpdater | class_7165 / class_7159 | — | **9 个 mod 全部 0 命中** | 安全（只有方案 B 才需要动它） |
| 17 | 镜像同步（**已定案为主钩子**） | ChunkSection.setBlockState | method_12256 | — | Lithium @Inject（计数）；Noisium 只在调用点 @Redirect | **两条独立证据收敛**：① Axiom 从这一层直写方块，只挂世界级会漏；② Carpet/TIS 都没碰这里（而 WorldChunk.setBlockState 被 Carpet 压了两个 mixin）。无 @Overwrite → @Inject(HEAD) 安全，但只观察不取消 |
| 18 | 镜像同步（区块写，**已降级为可选辅助点**） | WorldChunk.setBlockState | method_12010 | — | **Carpet 两个 mixin 都压在这里**（@Redirect onPlace/onRemove、@Redirect getBlockEntity）；Lithium @Redirect + @Inject | 仍无 @Overwrite，但同点已有两个 @Redirect → **只观察、不 redirect、不 cancel**；主钩子用第 17 行 |
| 19 | **禁止注入** | World.getBlockState / WorldChunk.getBlockState | — | — | **Lithium @Overwrite（priority 1000 / 500）** | 我们的注入可能落在被丢弃的旧方法体上而**静默失效**——绝对不要挂这两个 |

> **补充：同名方法要连类一起写。** LivingEntity.travel（method_6091）与 ServerPlayerEntity.travel 是同名的**两个方法体**（后者是 override，体内 super 调前者）。fix-mc-stats 注入的是 ServerPlayerEntity.travel，我们若钩 LivingEntity.travel 互不影响。所有注入点一律写「类.方法」，只写方法名会在这种地方踩坑。

## 2. 注入策略（由证据推出的硬规则）

1. **只用 @Inject(at=HEAD, cancellable=true) 接管整个方法体，绝不 @Overwrite。**
   审计结论：9 个 mod 里没有任何一个 @Overwrite 我们的三类目标方法（除 Lithium 的 adjustMovementForCollisions），所以这条自我约束不产生对抗，反而免疫了绝大多数冲突。
2. **HEAD-cancellable 天然免疫别人在"方法体内"的补丁。**
   ServerCore 对 PathNodeNavigator 的 @Redirect×4 / @ModifyVariable×2、Lithium 对 LandPathNodeMaker 的缓存短路，都发生在方法体内部；我们提前 return 后它们自然不执行——不崩、不双重优化、语义仍是原版。
3. **唯一危险的是别人 @Overwrite 同一个方法。** 此时晚应用者覆盖早应用者，早先注入的钩子会**静默失效**（中等置信度，未实机验证）。三条对策：
   - 优先挂在没人 @Overwrite 的方法上（第 17/18 行而不是第 19 行）；
   - 所有注入 **require=0**：找不到注入点不崩，只是不生效；
   - **钩子金丝雀自检**：启动完成后主动触发一次目标方法，确认我们的计数器真的 +1；没触发就禁用该子系统并大声报错。这是防"静默失效"的唯一可靠手段。
4. **priority 逐点决策，不在同一点竞争。** Mixin 语义是低优先级先应用、**先应用者先执行回调**：想活过别人的 @Overwrite 需要更大；想抢在别人 cancellable HEAD 之前需要更小（Lithium 用 990 抢 Fabric API 的 1000）。两者不可兼得，所以默认做法是**需要独占时直接关掉对方那个 mixin 组**（见第 3 节），而不是打优先级战争。

5. **priority 默认 1000。** 现有优先级地形：Lithium 局部 990 / 1005 / 1100、VMP 1050、C2ME 1100。取 1000 让我们排在 VMP 与 C2ME 之前（行为按原版走），排在 Lithium 的 990 之后（那两处我们本来就选择关掉对方组）。凡是可能被别人 @Overwrite 的方法，要么避开、要么把 priority 提到 >1100——但首选是避开。

6. **逐点决策的实例（VMP 零位移短路）**：VMP 的 Entity.move HEAD-cancellable（priority 1050）会在零位移时直接取消原版逻辑，**这是行为改变且不可配置**。我们 priority 1000 → 我们先进原生路径、行为回原版；priority >1050 → 跟 VMP 走。默认按原版（1000），并在兼容性报告里写明我们覆盖了它。

## 3. 关掉别人补丁的官方机制（已验证）

| mod | 机制 | 备注 |
| --- | --- | --- |
| Lithium | 任何 mod 可在**自己的** fabric.mod.json 写 custom.lithium:options，把组设为 false | false 优先于 true，Lithium 只打 warn 日志；**用户不需要改 config/lithium.properties** |
| FerriteCore | config/ferritecore.mixin.properties：populateNeighborTable / replaceNeighborLookup / blockstateCacheDeduplication 等反向开关 | populateNeighborTable 默认 false，官方注释明写"给直接访问该表的 mod 用" |
| ModernFix | config/modernfix-mixins.properties 按 mixin 类名关闭 | 键的精确拼写待核实 |
| C2ME | 自带 incompatibleMod(...) 与 lithium:options 先例 | 可照抄这套做法 |
| Alternate Current / Carpet | **没有**任何「被其它 mod 关闭」的机制 | 只能靠运行时探测 + 我们让位（Carpet 的 fastRedstoneDust 默认 false，实际风险低） |

Cava 计划在自己的 fabric.mod.json 里声明的示例（**待实测确认键名生效**）：

    "custom": {
      "lithium:options": {
        "mixin.entity.collisions.movement": false,
        "mixin.entity.collisions.intersection": false,
        "mixin.block.redstone_wire": false
      }
    }

## 4. 镜像侧的数据契约（被其它 mod 修订后的版本）

| 契约 | 原因 |
| --- | --- |
| 方块状态表**禁止用对象身份**（== / IdentityHashMap）做键，改用全局 state id 或形状内容 | FerriteCore blockstateCacheDeduplication 让内容相同的 BlockState 共享同一个 VoxelShape / boolean[] 实例 |
| 不读 BlockState 内部 neighbours 表 | Lithium fastmap 与 FerriteCore 都会替换它 |
| 不长期缓存 PalettedContainer 的 data 引用，每次经 getData() 取 | ModernFix 在读包后会替换 this.data |
| 不缓存"本 tick 的区块集合"快照跨调用使用 | C2ME 会在实体 tick 与方块/流体 tick 之间插入区块任务 |
| 镜像刷新必须**事件驱动 + 主线程队列** | C2ME 中段插队 + Chunky 预生成抖动 |
| 原生线程绝不触发 vanilla 区块加载 | C2ME 会让非主线程 getChunk 阻塞式加载，语义与无 C2ME 时不同 |
| 不把 entity.velocityDirty 当作自己的状态位 | VMP 会写这个字段 |
| 不主动唤醒激活范围外的实体，也不回写它们 | ServerCore activation range 会整 tick 跳过 Entity.tick()，位置不变是预期行为 |
| 不要假设 PalettedContainer 有锁保护 | VMP general.no_locking 恒生效、不可配置 |
| 区段镜像不要每 tick 全量重建 | Chunky 预生成时区块抖动会被放大，全量重建会变成主要开销 |
| 不依赖 PalettedContainer 的 lock/unlock | Lithium（chunk.no_locking）与 VMP 都把它们 @Overwrite 成 no-op，同步要自己做 |
| 线程池参数运行期取 Util.getMainWorkerExecutor() / getIoWorkerExecutor() | ThreadTweak 会整体替换这两个池 |
| 不在别人已用的调用点做 @Redirect | Noisium 在 NoiseChunkGenerator.populateNoise 里 redirect 了 ChunkSection.setBlockState 的调用点，同点双 @Redirect 是硬冲突 |
| 不自建光照镜像 | Starlight 把多个光照引擎方法 @Overwrite 成空实现，并额外持 region ticket 保活区块 |
| 镜像钩子**只观察、绝不 cancel 或 @Redirect** | Starlight 已经在 LevelChunk/ProtoChunk.setBlockState 内有 @Redirect；同点再来一个就是硬冲突 |
| 镜像主钩子必须留在 **ChunkSection.setBlockState（method_12256）** 这一层 | Axiom 的建造包绕过 World.setBlockState，从 ServerWorld.getChunk → WorldChunk.getSection → ChunkSection.setBlockState **直写**；只挂世界级 setBlockState 会漏掉它 |
| 不能假设每次方块写入都伴随 WorldChunk 级调用与邻居更新 | Axiom 的建造包带 REASON_NOUPDATES 之类标志，写入与邻居更新被解耦 |
| 光照相关假设要加断言 | Axiom 的 MixinServerLevel / MixinThreadedLevelLightEngine 会在非主线程推进光照 |

## 5. 适配器（软依赖）清单

| mod | 需要复刻/读取的行为 | 方式 |
| --- | --- | --- |
| ServerCore 1.5.0 | Entity.push 的激活范围短路：读公开接口 Inactive 的 servercore$isInactive；Entity.move 里"活塞推动→刷新激活计时" | 反射或编译期软依赖；复刻不了就让位 |
| VMP 0.2.0-beta.7 | Entity.move 的零位移短路（且包围盒未变） | 原生 move 保留等价短路即可，属于行为保持 |
| Lithium 0.12.1 | 若用户坚持保留 mixin.ai.pathing / block.redstone_wire，则对应子系统让位 | 启动时检测 lithium 配置，自动降级并在兼容性报告里说明 |

## 6. 调研完成度

四路源码级调研已全部完成（Lithium；ServerCore/VMP/Krypton/FerriteCore/ModernFix/Packet Fixer；C2ME/Chunky/光照/线程模型；红石与 AI 类）。本文与 docs/CAVA-platform-and-compat.md 的矩阵覆盖 1.20.4 Fabric 服务端生态中所有已知相关的 mod。

## 7. 我们的钩子会不会被别的 mod 覆盖？（三种失效模式与防线）

先澄清层级：**Mixin 只能改写 Java 类，动不了 native 库。** C++ 是通过 FFM 加载进 JVM 进程的普通动态库（.dll/.so/.dylib），别的 mod 既看不见它也覆盖不了它。真正可能被影响的是 **Java 侧那一个调用点**——它一旦被绕过，表现是「native 一次都没被调用」，而不是崩溃。

| # | 失效模式 | 机制 | 表现 | 防线 |
| --- | --- | --- | --- | --- |
| 1 | **崩溃级**（类加载失败） | 两个 mod 在同一目标点使用互斥注入器（双 @Redirect、双 @Overwrite） | 启动即崩，日志明确 | 我们的纪律：只用 @Inject(HEAD)，绝不用 @Redirect/@Overwrite，所以**我们不会制造这类冲突**。审计结论：没有任何 mod @Overwrite 我们的三类目标方法（唯一例外是 Lithium 的 Entity 碰撞，已用 lithium:options 关掉） |
| 2 | **静默失效级**（最危险） | 别人 @Overwrite 了我们注入的方法，且其 priority 让我们的注入先被应用、随后连同旧方法体一起被丢弃 | 服务器正常启动、TPS 正常，但 native 从未被调用 | ① 避开被别人 @Overwrite 的方法（镜像点挂 ChunkSection.setBlockState / WorldChunk.setBlockState，**不挂** World.getBlockState）；② 所有注入 require=0（找不到注入点不崩）；③ **钩子金丝雀**：启动后主动触发每个目标方法一次，断言计数器 +1，否则禁用该子系统并大声报错 → 把「静默失效」变成「响亮失效」 |
| 3 | **语义级** | 双方都生效但语义不同 | 行为与原版、或与「同 mod 但不装 Cava」不同 | 逐 mod 策略表 + A/B 语义基准 + 差分测试按 mod 组合跑 |

**反向情况（我们绕过别人）也要讲清楚**：我们 HEAD-cancellable 接管后，别人在**方法体内**的优化自然不执行——例如 ServerCore 对 PathFinder 的 @Redirect×4 + @ModifyVariable×2、Lithium 在 LandPathNodeMaker 上的路径类型缓存。这既不是冲突、也不会变慢（他们的代码根本不跑）；代价只是「我们接管的那条路上，他们的优化收益归零」，那份收益得由 native 自己做出来（例如寻路类型判定由 native 自算）。

**结论一句话**：装上这些优化 mod 之后，我们的 C++ **不会**被覆盖；可能发生的是「某个注入点被绕过而不生效」。而这类问题在当前清单里是**可枚举、可检测、可回退**的——因为每一种情况都有明确的方法级判定（见第 1 节的表）、有启动自检、还有一键回退的纯 Java 路径。
