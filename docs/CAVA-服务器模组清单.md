# Cava：服务器模组清单与影响分析

**这份文档用来做什么**：以后加新功能、换注入点、排查性能异常时，先在这里查三件事——
1. 这个模组会不会影响我要动的那条代码路径？
2. 它是不是真的在优化性能？优化的是哪一块？
3. 我要挂的那个方法，有没有别人已经占了？占了之后我该让位还是接管？

配套文档：docs/CAVA-hook-points.md（注入点与策略）、docs/CAVA-platform-and-compat.md（兼容矩阵）、docs/CAVA-v1-plan.md（总体方案）。
清单位置：J:\mc\Cava\优化模组\服务端模组（49 个 jar，本文件所有版本号均从 jar 内 fabric.mod.json 实读）。

---

## 1. 统计

| 类别 | 数量 | 说明 |
| --- | --- | --- |
| 性能优化类 | 9 | lithium / servercore / vmp / krypton / ferritecore / memoryleakfix / c2me / noisium / starlight |
| 性能优化+调试 | 3 | carpet / carpet-tis-addition / （gca 为 Carpet 附加） |
| 预生成工具 | 1 | chunky |
| 剖析工具 | 1 | spark（**P0 基线就用它**） |
| 判定后归位 | — | icterine=性能优化（进度判定）；getittogetherdrops=优化（掉落物合并）；fuji=功能+2 个性能模块；fix-mc-stats=统计修复；necronomicon/jupiter=库；MCMOD=反向（定时 System.gc，污染基线） |
| 重负载非优化类 | 8 | bluemap / axiom / ledger / voicechat / geyser / floodgate / automodpack |
| 功能类 | 12 | luckperms / easyauth / nochatreports / minimotd / vanilla-permissions / customname / randomtp / syncmatica / servux / easybot 等 |
| 库/前置 | 6 | fabric-api / fabric-language-kotlin / architectury / cloth-config / YACL / malilib |
| **客户端误装** | 4 | sodium / continuity / customskinloader / malilib（env=client，服务端不会加载） |

---

## 2. 全量清单

| modid | 版本 | env | 类别 | 是否碰 Cava 三子系统 |
| --- | --- | --- | --- | --- |
| carpet | 1.4.128+v231205 | * | 性能优化+调试 | 3 条 optimization 规则**默认全关/等同原版**；寻路 hook 纯被动（只 redirect EntityNavigation.createPath）；**/tick 是原版命令，Carpet 自己没有 TickCommand** |
| gca | 2.7.0-1.20.4 | * | Carpet 附加 | 11 条规则全是假人/交互便利项，**无一条 optimization**，与三子系统零交集 |
| architectury | 11.1.17 | * | 库 | 待核 |
| automodpack | 4.0.6 | * | 重负载（自动分发） | 外层 jar 无 mixin；内嵌 jar 的 8 个全在登录/网络 |
| cloth-config | 13.0.121 | * | 库 | 待核 |
| fabric-api | 0.96.11+1.20.4 | * | 库 | 待核 |
| fabric-language-kotlin | 1.13.11+kotlin.2.3.21 | * | 库 | 待核 |
| nochatreports | 1.20.4-v2.6.1 | * | 功能 | 待核 |
| axiom | 4.7.1 | * | 重负载（建造工具） | **唯一真威胁**：绕过 World.setBlockState 直写 ChunkSection；光照走非主线程 |
| bluemap | 5.3 | * | 重负载（网页地图） | 零 mixin；读 .mca 文件，不碰世界对象 |
| c2me | 0.2.0+alpha.11.72 | * | 性能优化 | 镜像✓（线程与调度） 三子系统✗ |
| carpet-tis-addition | 1.82.3 | * | 性能优化+调试 | 5 条 optimization 规则**默认全关**；**提供红石/实体差分夹具的核心 logger**（见第 5 节） |
| chunky | 1.3.146 | * | 性能优化（预生成） | 镜像✓（区块抖动放大） |
| continuity | 3.0.0-beta.5+1.20.2 | client | 客户端误装 | 待核 |
| eclipsescustomname | 0.2.2-1.20.4 | * | 功能 | 待核 |
| customskinloader-bootstrap | 15.0.1 | client | 客户端误装 | 待核 |
| easyauth | 3.2.1 | server | 功能（登录） | 10 个 mixin 全在网络/玩家管理；**未登录玩家 playerTick 被整体取消** |
| easybot | 0.3.2 | * | 功能（QQ 群↔MC 聊天桥，**不是假人 mod**） | 三子系统全 ✗ |
| memoryclearermissnotoredict | 1.5.0 | * | **反向**：定时 System.gc() | 污染性能基线测量 |
| ferritecore | 6.0.3 | * | 性能优化 | 镜像✓（方块状态去重） |
| fix-mc-stats | 1.0.0 | * | 修复（统计 bug） | 碰 ServerPlayerEntity.travel（**与我们不同方法体，不冲突**） |
| floodgate | 2.2.0-SNAPSHOT | * | 重负载（基岩版） | 5 个 mixin（accessor/常量/网络），世界写入经 MinecraftServer.execute 回主线程 |
| fuji | 1.1.8 | * | 功能（40+ 生存模块）+ **含 2 个真性能模块** | 镜像边缘（tick_chunk_cache 改变 tick 遍历来源） |
| getittogetherdrops | 1.3.1 | * | 优化（掉落物合并） | 实体镜像间接受影响；硬依赖 cloth-config |
| geyser-fabric | 2.2.2-SNAPSHOT | * | 重负载（基岩版） | 仅 3 个 mixin；以 Java 客户端身份连服，世界数据全走协议 |
| icterine | 1.3.0 | * | **性能优化**（进度判定） | 三子系统全 ✗ |
| jupiter | 2.1 | * | 库（配置同步） | 零 mixin |
| krypton | 0.2.6 | * | 性能优化 | 全 ✗（仅网络栈） |
| ledger | 1.3.1 | server | 重负载（方块日志） | 97 个 mixin 全在 World.setBlockState 等**调用点**；三子系统不冲突。DB 写非主线程，回档的世界写入线程**未验证** |
| ledger-databases | 1.2.1 | * | 库（打包 JDBC 驱动） | 无 mixin |
| lithium | 0.12.1 | * | 性能优化 | 寻路✓ 实体✓ 红石✓ 镜像✓ |
| luckperms | 5.4.113 | server | 功能（权限） | 4 个 mixin（命令/accessor/玩家） |
| malilib | 0.18.1 | client | 库（客户端） | 待核 |
| memoryleakfix | 1.1.5 | * | 内存修复 | 1.20.4 服务端**真正生效的只有 1 条**（Biome 温度缓存改 static）；与三子系统及三类镜像均不命中；与 Lithium 的 world.temperature_cache 重叠 |
| minimotd-fabric | 2.1.0 | server | 功能 | 4 个 mixin，全在服务器列表响应路径 |
| necronomicon | 1.4.2 | * | 库（配置/文本/NBT/datagen） | 仅 ItemStack.getName + 客户端 |
| noisium | 2.2.2+mc1.20.2-1.20.4 | * | 性能优化 | 生成期写入（镜像边缘）。**2.2.2 与审计的 2.3.0 除 fabric.mod.json 外逐字节相同** |
| packetfixer | 1.4.1 | * | 修复（非优化） | 只有 9 条网络栈 mixin（@ModifyConstant 为主、priority=9999，2 处 @Overwrite 在 Varint21FrameDecoder）；**与审计时的 3.3.2 完全不同，旧清单已替换** |
| randomtp | 8.0.1+1.20 | * | 功能 | 零 mixin；一执行就同步加载数百区块 |
| servercore | 1.5.0+1.20.4 | * | 性能优化 | 寻路✓ 实体✓ 红石✗ 镜像✗ |
| servux | 0.1.0 | server | 功能（给 masa 客户端工具供结构数据） | MinecraftServer.tick 尾 + 区块发包路径；三子系统 ✗ |
| sodium | 0.5.8+mc1.20.4 | client | 客户端误装 | 待核 |
| spark | 1.10.58 | * | 工具（剖析） | 待核 |
| starlight | 1.1.3+fabric.f5dcd1a | * | 性能优化 | 镜像✓（setBlockState 调用点） |
| syncmatica | 1.20.4-0.3.11 | * | 功能（投影共享） | server 段 6 个；litematica 相关全 client 段 |
| vanilla-permissions | 0.2.3+1.20.4 | * | 功能 | 待核 |
| vmp | 0.2.0+beta.7.139 | * | 性能优化 | 寻路✗ 实体✓ 红石✗ 镜像✓（调色板锁） |
| voicechat | 1.20.4-2.5.22 | * | 重负载（语音） | common 段仅 PlayerManagerMixin；其余 9 个全 client 段 |
| yet_another_config_lib_v3 | 3.5.0+1.20.4-fabric | * | 库 | 待核 |

---

## 3. 跨切面扫描结论（与单个 mod 无关、但会影响开工的）

1. **无重复 mod id** → 没有启动级 id 冲突。
2. **没有一条声明的 breaks/conflicts 被触发**。全部指向未安装的 mod（optifabric / phosphor / hydrogen / cardboard / dynview / betterchunkloading / tic_tacs）。两处临界值都安全：carpet-tis-addition 声明 breaks lithium<=0.11.0（装的是 0.12.1）；voicechat 声明 breaks fabric-api<0.91.1+1.20.4（装的是 0.96.11）。
3. **4 个客户端 mod 误装在服务端文件夹**（sodium / continuity / CustomSkinLoader / malilib）。Fabric Loader 在专用服务端会跳过 env=client 的 mod，所以**无害但也无用**——建议移出去，避免以后排查时干扰判断。
4. **版本谓词曾被我怀疑是启动阻断，查证后确认不是**：fuji 声明 ~1.20.1、MCMOD 声明 ~1.20、getittogetherdrops 声明 ~1.20。我读了 Fabric Loader 的 VersionComparisonOperator 源码，`~`（SAME_TO_NEXT_MINOR）的判定是"**主版本与次版本相同、且不低于给定版本**"，因此 ~1.20.1 覆盖 1.20.1–1.20.x，**1.20.4 匹配**。（注意它不是 npm 的"<下一个次版本"语义。）
5. **spark 1.10.58 已装** → P0 要求的性能基线可以直接在你这台服务器上做，不需要额外装东西。
6. **重量级非优化 mod 会显著影响基线测量**（BlueMap / Axiom / Ledger / Geyser / Floodgate / voicechat / AutoModpack / RandomTP），第 5 节给了三档处理方案。
7. **分片 D 的边界扫描结论：这 13 个重负载 mod 里没有任何一个 mixin 以 Cava 的核心方法为注入目标。** 用 intermediary 方法号做边界匹配（避免 method_52 命中 method_520 的假阳性）结果：寻路 method_52/54/58/59/23476 → 0 命中；红石 method_27842/10485/10479/27844/9991 → 0 命中；镜像 method_12256/12010 → 0 个 mixin 目标；实体侧仅 2 处，其中 Axiom 的 MixinPlayerEntity(travel=method_6091, HEAD, cancellable) **登记在 client 段，专用服务器不加载**。
8. **唯一真正威胁镜像的是 Axiom**：AxiomServerboundSetBlock.handle 与 AxiomServerboundSetBuffer.applyBlockBufferServer 在服务端**绕过 World.setBlockState 直写** ServerWorld.getChunk → WorldChunk.getSection → **ChunkSection.setBlockState(method_12256)**，并直接增删方块实体、操作 WorldChunk.setLoadedToWorld；包里有 REASON_NOUPDATES 之类标志。→ **Cava 必须保留 ChunkSection.setBlockState 这一路的镜像钩子**，且不能假设每次方块变更都伴随 WorldChunk 级调用与邻居更新。另外 Axiom 的 MixinServerLevel / MixinThreadedLevelLightEngine 会在**非主线程推进光照**，这条要加断言与对照实验。

---

## 4. 与 Cava 三个子系统的冲突总表（你的这套组合）

| mod | 关系 | 默认处置 |
| --- | --- | --- |
| lithium 0.12.1 | 寻路：LandPathNodeMaker 缓存短路（priority 990）**不碰 PathNodeNavigator**；实体：**@Overwrite** Entity.adjustMovementForCollisions；红石：getReceivedRedstonePower **HEAD+cancel 整段替换** | 在 **Cava 自己的 fabric.mod.json** 里写 custom.lithium:options 关掉 mixin.entity.collisions.movement 与 mixin.block.redstone_wire（官方机制，用户无需改 lithium.properties）。寻路无需处理 |
| servercore 1.5.0 | 寻路：PathFinder 体内 4×@Redirect + 2×@ModifyVariable（**无配置可关**）；GroundPathNavigation.createPath HEAD；实体：Entity.push HEAD 同点、Entity.move 在 INVOKE 点 | 寻路直接接管（体内补丁自然不执行，不崩）；实体需读它的 Inactive 接口复刻短路，否则让位 |
| vmp 0.2.0-beta.7.139 | 实体：Entity.move HEAD+cancel（零位移短路，**行为改变且不可配置**） | priority 默认 1000 让我们先走（=原版语义）；若选策略 B 则让 VMP 走。**这是 A/B 语义基准的分水岭** |
| c2me 0.2.0-alpha.11.72 | 三子系统无交集；但把区块任务插进 tick 中段、worldgen 走 worker 线程 | 镜像事件驱动 + 主线程队列；不缓存"本 tick 区块集合" |
| chunky 1.3.146 | 仅 @Accessor/@Invoker | 无冲突；镜像**不要每 tick 全量重建**（预生成期会被放大） |
| starlight 1.1.3 | 在 LevelChunk/ProtoChunk.setBlockState 内有 @Redirect | 镜像钩子**只观察、不取消**（同点再来一个就是硬冲突） |
| ferritecore 6.0.3 | 方块状态去重 → 内容相同者共享同一 VoxelShape 实例 | 镜像建表**用 state id 做键，禁用对象身份** |
| noisium 2.2.2 | 生成期在 populateNoise 里 @Redirect **调用点** | 我们挂方法本身，不挂调用点 |
| krypton 0.2.6 | 只在 Netty 与编解码 | 无交集 |
| carpet / gca / tis-addition | 红石与各类可选优化规则（多数默认关） | 逐个规则查默认值；红石类规则一旦开启，P3 原生路径让位 |
| 其余（功能/库/重负载/待定） | 见第 5 节 | 待各分片调研结论 |

---

## 5. 性能基线测量建议（P0 直接用）

1. **分层测，不要一锅端**：∅ 纯原版 → +通用优化九件套 → +你的全套。每层都跑 native on/off 两组。
2. **测之前按三档处理**（分片 D 的实测结论，并记录关了什么）：
   - **必须关**：BlueMap（最大 CPU/IO 噪声源且零交互）、**Axiom**（一用起来就是单 tick 数十万次直写方块）、Ledger + ledger-databases（每次方块变更加派发入队 + 持续 DB 写）、Geyser（引入第二套协议栈与额外实体）、AutoModpack（启动期扫描 + 内嵌 Netty 分发）、RandomTP（一执行就加载数百区块）。
   - **建议关**：Floodgate、Syncmatica、voicechat、EasyAuth。EasyAuth 特别注意：未登录玩家的整个 playerTick 会被取消，会扭曲实体侧负载分布，测试账号必须已登录。
   - **可保留**：LuckPerms、MiniMOTD（开销≈0；LuckPerms 还是 EasyAuth/Geyser/Floodgate 的软依赖，关掉会改变它们的权限路径）。
3. **场景要可复现**：用原版 /tick freeze｜step｜sprint｜rate 控制节奏，配合 Carpet 的 /player 假玩家批量生成实体；同一个存档、同一个种子、同一个起始 tick。
4. **看 spark 的分层占比**：重点看"实体 tick / 寻路 / 方块 tick（含红石）/ 区块生成"四块各占多少。**如果寻路占比 < 5%，P1 的优先级要重新评估**。

---

## 6. 加新功能时的检查清单（本文件的核心用途）

顺序照做，能挡掉绝大多数坑：

1. **定位**：新功能要动哪个方法？写出 Yarn 名 + intermediary。
2. **查占位**：在本文件第 2、4 节和 docs/CAVA-hook-points.md 里查有没有别人动它。按对方注入类型决策：

| 别人的注入类型 | 我们能不能 HEAD-cancellable 接管 | 要注意什么 |
| --- | --- | --- |
| @Overwrite 同一方法 | 能，但**必须让 priority 大于对方**，否则我们的注入会随旧方法体被丢弃（静默失效） | 更稳的做法是换注入点或让位 |
| HEAD-cancellable（同点） | 能，但**谁先返回谁说了算**，取决于 priority | 这是语义归属问题，不是技术问题 → 走 A/B 决策 |
| 方法体内补丁（@Redirect/@ModifyVariable/@Inject 其他点） | 能，对方代码自然不执行，不会崩 | **必须把对方的收益在 native 里补回来**，否则比"只装它"更慢 |
| 调用点 @Redirect | 能（我们挂方法本身） | 绝不能在**同一调用点**再 redirect（同点双 redirect 是硬冲突） |
| @Accessor/@Invoker | 能 | 无影响 |

3. **数据契约检查**（详见 docs/CAVA-hook-points.md 第 4 节）：是否读到 BlockState 内部结构？是否用对象身份做键？是否缓存了 PalettedContainer.data？是否假设调色板有锁？是否用了静态线程池字段？
4. **注入纪律**：只用 @Inject(at=HEAD, cancellable=true)；require=0；显式写 priority；绝不 @Overwrite / @Redirect。
5. **可观测性**：为新钩子加金丝雀计数、失败回退、并在启动兼容性报告里加一行。
6. **验证**：parity 三层测试 + mod 组合矩阵（至少 ∅ 与 +你的全套两档）。

### 怎么自己查一个 mod（速查命令）

    # 1) 看元数据
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z=[System.IO.Compression.ZipFile]::OpenRead('J:\mc\Cava\优化模组\服务端模组\<jar>')
    $z.Entries | Where-Object { $_.FullName -like '*mixins*.json' -or $_.FullName -eq 'fabric.mod.json' } | ForEach-Object { $_.FullName }
    # 2) 看 refmap（里面直接写着每个 mixin 的目标方法，解析成 intermediary 名）
    # 3) 用本机映射把 intermediary 反查成 Yarn 名
    #    C:\Users\郁小悟520\.gradle\caches\fabric-loom\1.20.4\net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\mappings.tiny
    #    方法行格式：m <TAB> 混淆desc <TAB> 混淆名 <TAB> desc <TAB> method_xxx <TAB> Yarn名

---

## 7. 分片调研状态

**逐 mod 详解**（优化的是什么 / 实现方式 / 与三子系统的关系 / 加新功能的注意事项 / 证据 / 置信度）由四路并行调研补充，完成后作为新的「逐 mod 详解」一节插入本文件（届时后续章节顺延编号）：

| 分片 | 范围 | 状态 |
| --- | --- | --- |
| A | 核心优化九件套（含 Noisium 2.2.2 与 PacketFixer 1.4.1 的版本校准、memoryleakfix） | 调研中 |
| B | Carpet 三件套（优化规则与默认值、测试工具） | 调研中 |
| C | 待定的 7 个小众 mod（判断到底优不优化） | 调研中 |
| D | 重负载非优化类（对基线与镜像的影响） | 调研中 |

---

## 附录 A：逐 mod 详解

### A.1 分片 C：小众/功能型 mod（已完成，9 个 jar）

**核对方式**：本地 jar 的 fabric.mod.json / *.mixins.json / refmap + javap 注解常量池反解，再用 1.20.4 mappings 反查。6 个有 refmap 的 mod 共 106 个 intermediary 成员，**106/106 全部在 1.20.4 存在**（注入目标均可解析）。

| mod | 是不是优化 mod | 它到底做什么 | 与 Cava 的关系 | 加新功能时要注意 |
| --- | --- | --- | --- | --- |
| **icterine** 1.3.0 | ★是（但优化的是**进度判定**，不是 tick） | 缓存"获得物品"进度判定：@Inject HEAD on class_2066.trigger(method_8950)、cancellable on class_2066$class_2068.matches(method_8958)、@Redirect ItemPredicate.test(method_8970) 与 NonNullList.add | 三子系统全不命中 | 它用了 @Redirect（我们禁用）；会改变进度判定求值顺序 → 对照实验要固定其开关 |
| **necronomicon** 1.4.2 | 否 | 前置/工具库（Kotlin：配置/文本/NBT/datagen API） | 仅 2 个 mixin：ItemStack.getName(method_7964) HEAD cancellable + 客户端 DebugHud | **depends 里完全没有 minecraft 版本约束**，换版本 loader 不拦（升级时要自己盯） |
| **jupiter** 2.1 | 否 | 配置同步库 | **零 mixin、零 refmap** | 无 |
| **fix-mc-stats** 1.0.0 | 否（统计 bug 修复） | 25 个服务端 + 7 个客户端 mixin | **唯一命中 Cava 方法族的 mod**：@Inject ServerPlayerEntity.travel at INVOKE increaseTravelMotionStats(method_54720) cancellable + @ModifyArg。已用混淆 jar 验证：ServerPlayerEntity **自己声明了 travel 的 override**，体内 super 调 LivingEntity.travel → 与我们钩的 LivingEntity.travel(method_6091) **是不同方法体，不冲突**；且它不改写移动参数，不破坏逐位一致 | 注入点一律写「类+方法」，不要只写方法名（同名 override 是两个方法） |
| **getittogetherdrops** 1.3.1 | 间接（掉落物合并策略） | @Inject INVOKE_ASSIGN + cancellable on ItemEntity.mergeWithNeighbours(method_6973) + @Invoker isMergable(method_20397)/tryToMerge(method_6972) | **实体镜像间接受影响**（合并时机改变实体数量） | **硬依赖 cloth-config**（缺则加载失败）；对照实验固定其合并配置 |
| **MCMOD (Memory Clearer)** 1.5.0 | 否，**方向相反** | 定时/手动 **System.gc()**（TimerTask 体内只有 invokestatic System.gc） | 零 mixin；只引用 6 个原版成员，1.20.1→1.20.4 兼容风险低 | **真实危害是 STW 停顿污染 tick 基线与性能测量**（它自己的日志都建议加 -XX:+ExplicitGCInvokesConcurrent）；做基线时建议关掉 |
| **easybot** 0.3.2 | 否 | **不是假人 mod**：QQ 群↔MC 聊天桥（连 ws://127.0.0.1:26990/bridge，账号绑定/聊天转发/皮肤查询，内置 Jetty） | 仅 ServerPlayNetworkHandler @Inject HEAD + 两个 accessor；**不生成假人、不 tick 实体、不加载区块** | 我原先"假人 mod 可能影响实体 tick"的判断是错的，已更正 |
| **servux** 0.1.0 | 否 | 给 MiniHUD/Litematica 等 masa 客户端 mod 供结构数据（environment=server） | 2 个 mixin：MinecraftServer.tick(method_3748) at RETURN（每 tick 尾部固定小开销）、class_3898.track(method_52348) at HEAD（区块发包路径）；三子系统全不命中 | 无 |
| **fuji** 1.1.8 | 间接（40+ 生存模块，**含 2 个真性能模块**） | (a) biome_lookup_cache：@Redirect getRandomSpawnMobAt(method_8664)/mobsAt(method_29950) 里的 WorldView.getBiome(method_23753)；(b) tick_chunk_cache：维护"哪些区块要 tick"的缓存，@Redirect ChunkMap.getChunks(method_17264) in class_3215.tickChunks(method_14161)（与 ServerCore 的 ticking-chunk cache 同类） | **镜像边缘相关**：它改了"每 tick 遍历哪些区块"的迭代来源，失效点在 onChunkStatusChange(method_31414)；我们钩的 setBlockState 写入侧不受影响 | 版本声明 ~1.20.1 但字节码含 1.20.2+ 类（实际是 1.20.2+ 构建），48 个 mixin 目标 100% 存在于 1.20.4；模块开关在 config/fuji/（默认值未核实）。**若开了 tick_chunk_cache，要确认 Cava 没有"按 tick 顺序扫描加载区块"的隐含假设** |

**分片 C 的三条行动项**
1. 无需为这 9 个 mod 改动寻路/红石注入点：这两条路径在 9 个 mod 中**无人占用**（method_52/54、method_58/59/23476、method_6356、method_27842、method_10485、method_10479/27844、method_9991、class_7165/class_7159 全部空闲）。
2. 基准环境要处理两件事：MCMOD 的 System.gc()（否则 tick 基线失真）；fuji 若开了 tick_chunk_cache，确认镜像没有依赖 tick 顺序的隐含假设。
3. 对照实验固定项：fix-mc-stats 的配置开关、getittogetherdrops 的合并配置、icterine 的 mixin 开关。
4. **Biome 温度在这套整合里有三套实现**：原版 ThreadLocal、memoryleakfix 的 static ThreadLocal、Lithium 的 world.temperature_cache（@Overwrite Biome.getTemperature=method_21740）。同时装时 memoryleakfix 那条修复基本被 Lithium 架空。若将来原生侧用到群系温度，**逐位基线必须区分装/不装这三者**。
### A.2 分片 D：重量级非优化类（已完成，13 个 jar）

**核对方式**：解包读 mixins.json / refmap，再用 javap 解析注解常量池确认真实注入值，并用 intermediary 方法号做边界匹配。

| mod | mixin 规模与性质 | 线程归属 | 与 Cava 的关系 |
| --- | --- | --- | --- |
| **BlueMap 5.3** | 零 mixin，纯 Fabric 事件 + 自建线程池 | 从磁盘 .mca 读世界（MCAWorld + WatchService）；落盘用 supplyAsync 回主线程 | 不碰世界对象，安全 |
| **Axiom 4.7.1** | 88 个 mixin 类，common 段 17 个在服务端生效 | **光照走非主线程** | **唯一真威胁**：直写 ChunkSection.setBlockState(method_12256) + 直接增删方块实体；建造包带 REASON_NOUPDATES 标志 |
| **Ledger 1.3.1** | 97 个 mixin 类，全部插在 World.setBlockState/removeBlock/breakBlock 的**调用点** | DB 写非主线程（CoroutineScope + Dispatchers.IO + LinkedBlockingQueue）；**回档的世界写入线程未验证** | ComparatorBlock/RepeaterBlock 的 mixin 注入的是 onUse 而非 getPower/update → 三子系统不冲突 |
| **ledger-databases 1.2.1** | 无 mixin，只打包 JDBC 驱动 | — | 无 |
| **voicechat 2.5.22** | common 段仅 PlayerManagerMixin，其余 9 个全 client 段 | 独立 UDP；相关类为 client-only，服务端不加载 | 无 |
| **Geyser 2.2.2** | 仅 3 个 mixin | 以 Java 客户端身份连服，世界数据全走协议包 | 无。附注：DedicatedServerMixin 里 4 个方法经 javap flags 确认是 ACC_BRIDGE\|ACC_SYNTHETIC 编译器桥接，**不是**服务端执行器覆写（纠正了直觉判断） |
| **Floodgate 2.2.0** | 5 个 mixin（ChunkMap @Accessor entityMap、ClientIntentionPacket @ModifyConstant、Connection @Accessor、ServerConnectionListener @Inject） | 皮肤路径首行就是 MinecraftServer.execute | 无 |
| **EasyAuth 3.2.1** | 10 个 mixin，全在网络/玩家管理路径 | 主线程 | ServerPlayerEntityMixin.playerTick() 是 @Inject(HEAD, cancellable) → 未登录玩家整个 playerTick 被取消 |
| **LuckPerms 5.4.113** | 4 个 mixin（命令 execute HEAD cancellable + 2 accessor + 玩家 copyFrom/worldChanged TAIL） | 主线程 | 无 |
| **MiniMOTD 2.1.0** | 4 个 mixin，全在服务器列表响应路径 | 主线程 | 无 |
| **AutoModpack 4.0.6** | 外层 jar 无 mixin；内嵌 jar 的 8 个全在登录/网络 | 主线程 + 内嵌 Netty | 无 |
| **RandomTP 8.0.1** | 零 mixin，纯 Java + Architectury | 命令同步加载区块 | 无（但基线噪声大） |
| **Syncmatica 0.3.11** | server 段 6 个（自定义 payload + 生命周期）；litematica 相关全 client 段 | 主线程 | 无 |

**分片 D 的三条结论**
1. **Cava 的「世界变更只在主线程」假设在本分片成立**（BlueMap/Geyser/Floodgate/voicechat/AutoModpack/MiniMOTD/Syncmatica/EasyAuth/LuckPerms 全部安全），只剩两处需要断言或对照实验：**Axiom 的光照路径**、**Ledger 回档的世界写入线程**（静态字节码里没找到 MinecraftServer.execute 证据）。
2. **镜像钩子必须留在 ChunkSection.setBlockState（method_12256）这一层**——Axiom 的建造包正是从这一层直写进来的；只挂世界级 setBlockState 会漏掉它。
3. Axiom 的建造包把「一次写入」与「邻居更新」解耦（REASON_NOUPDATES），因此镜像刷新不能依赖邻居更新被触发，只能盯写入本身。
### A.3 分片 A：核心通用优化类（已完成，11 个 jar）

**做法加强**：11 个 jar 全部解包，对**每个 mixin 类跑 javap -v -p**，把 @Mixin 目标类、注入器类型、method=、at=、cancellable、priority 全量读出来，再与既有审计逐条对表（中间产物在 .cava-research/inv-batchA/）。

**版本校准结果**

| 结论 | mod |
| --- | --- |
| 与审计版本**逐字节相同**（SHA256 一致），结论原样沿用 | lithium 0.12.1、vmp 0.2.0+beta.7.139、c2me 0.2.0+alpha.11.72、chunky 1.3.146 |
| mixins.json 与上游分支逐条一致 + 类文件齐全 | servercore、krypton、ferritecore、starlight |
| **除 fabric.mod.json 与一个 architectury 注入类名外逐字节相同** → 旧结论适用 | **Noisium 2.2.2**（审计用 2.3.0） |
| **完全不同，旧清单已作废** | **Packet Fixer 1.4.1**（审计用 3.3.2） |
| 全新分析（此前未审计） | **memoryleakfix 1.1.5** |

**memoryleakfix 1.1.5 的关键事实**
- `memoryleakfix-16.mixins.json` **根本没被引用、不会加载**；插件按类级 @MinecraftRequirement 过滤。
- **1.20.4 服务端上真正生效的只有 1 条**：Biome 的 ThreadLocal 温度缓存改成 static（@WrapOperation 打在 Biome <init> 的 ThreadLocal.withInitial 上）。其余 entityMemoriesLeak（≤1.19.3）/tagKeyLeak（1.18.2）/drownedNavigationLeak（1.16.3–1.16.5 且 config 未加载）全被版本区间拦掉，2 条 client-only 服务端不加载。
- 与 Cava 的寻路/实体/红石/三类镜像**均不命中**。
- 新发现的重叠：与 Lithium 的 `world.temperature_cache.BiomeMixin`（@Overwrite Biome.getTemperature(BlockPos)=method_21740）功能重叠。

**jar 级注入点复核（12 个 Hook + 镜像契约，结论与 docs/CAVA-hook-points.md 一致，无新增冲突）**

| 我们的 Hook | 11 个 mod 里的占用情况 |
| --- | --- |
| Hook1 PathNodeNavigator.findPathToAny | 仅 ServerCore（方法体内 @Redirect×4 + @ModifyVariable×2，可被我们 HEAD 旁路）；Lithium 0 命中 |
| Hook5 MobNavigation.createPath | 仅 ServerCore @Inject(HEAD, cancellable) |
| Hook6 Entity.move(method_5784) | **VMP @Inject(HEAD, cancellable) 同点** + ServerCore @Inject(at=INVOKE limitPistonMovement) |
| Hook7 getBlockCollisions(method_20812) | **11 个 mod 全 0 命中** |
| Hook8 adjustMovementForCollisions | **Lithium 唯一一处 @Overwrite(method_20736)** + @Redirect×2 打在 method_17835 体内 |
| Hook9 Entity.push | 仅 ServerCore @Inject(HEAD, cancellable) 同点 |
| Hook10 tickCramming(method_6070) | 仅 Lithium @Redirect→World.getOtherEntities 调用点 |
| Hook13 getReceivedRedstonePower(method_27842) | **仅 Lithium @Inject(HEAD, cancellable) 同点**（priority 990）；其余 10 个 0 命中 |
| Hook13b/14 update / updateNeighbors | 仅 Lithium alloc.enum_values @Redirect×2（只换 Direction.values()，算法未改） |
| Hook15 getPower(method_9991) / Hook16 NeighborUpdater(7165) / ChainRestrictedNeighborUpdater(7159) | **11 个 mod 全 0 命中** |
| Hook17 ChunkSection.setBlockState(method_12256) | Lithium/Noisium 只在**调用点** @Redirect，方法本体无 @Overwrite → 我们 @Inject(HEAD) 安全 |
| Hook18 WorldChunk/ProtoChunk.setBlockState(method_12010) | Starlight 在方法体内 @Redirect → 我们只观察、不 @Redirect、不 cancel |
| Hook19 禁区 World/WorldChunk.getBlockState | Lithium world.inline_block_access（priority=500）+ world.chunk_access 都是 @Overwrite → 继续避开 |

**镜像契约也在 jar 级复现**：FerriteCore fastmap @Overwrite populateNeighbours(method_28496) + blockstatecache/threaddetec；VMP no_locking @Overwrite lock/unlock(method_12334/12335) 恒生效无开关；C2ME 根 mixinPriority=1100、159 个 mixin 类里 **0 个**碰三子系统、4 个碰镜像类。

**顺带纠正一条**：c2me 的 fabric.mod.json 里写的 `mixin.world.player_chunk_tick` 是**无效键**——Lithium 0.12.1 的 jar 里存在 `mixin.alloc.chunk_ticking`、`mixin.alloc.blockstate`，但**不存在** player_chunk_tick。
### A.4 分片 B：Carpet 三件套（已完成，3 个 jar / 801 个 mixin）

**核对方式**：三个 jar 与安装目录 SHA-1 逐一比对（Carpet 安装件 sha1 53ee9564…b4a7 与 Modrinth 1.4.128 元数据逐位一致，无版本漂移）；自写 class-file 解析器（**必须同时读 RuntimeInvisibleAnnotations，否则 @Mixin 全部读不到**）+ refmap + 本机 mappings，186/16/599 个 mixin 全部解析成功。

**1. 默认配置下三者都不改三子系统的可观测行为**
- Carpet 3 条 optimization：fastRedstoneDust(**false**)、lagFreeSpawning(**false**)、maxEntityCollisions(int **0 = 等同原版**)。
- TIS 5 条 optimization：optimizedFastEntityMovement/**false**、optimizedHardHitBoxEntityCollision/**false**、optimizedTNTHighPriority/**false**、chunkUpdatePacketThreshold/64（纯网络）、blockEventPacketRange/64.0（=原版）。
- 其余默认值（quasiConnectivity=1、fillUpdates=true、pushLimit=12、railPowerLimit=9、poiUpdates=true、entityMomentumLoss=true、lightUpdates=ON、tileTickLimit=65536、chunkTickSpeed=1）**全部等于原版**。
- **默认开且改变原版行为的规则：0 条。** 这是最好的消息——你的服务器当前就是逐位一致的基线环境。

> **【实际情况】**：用户**已经手动开启了** Carpet 的红石粉卡顿优化（fastRedstoneDust）与 TNT 相关优化、无卡顿刷怪口径。因此**服务器的红石基准不是原版，而是「Carpet fastRedstoneDust 的算法」**——P3 必须按这个基准做（复刻或让位），详见 docs/CAVA-platform-and-compat.md 第 2.3 节。

**2. 只有 3 处同点冲突，且都在默认关的规则下**

| 方法 | 占用者 | 影响 |
| --- | --- | --- |
| RedstoneWireBlock.update(method_10485) | Carpet @Inject(HEAD, cancellable)（fastRedstoneDust）+ TIS @ModifyVariable | 规则开启时红石只能二选一 |
| PoweredRailBlock(class_2442) | Carpet @ModifyConstant + TIS ×2 @Inject | 规则开启时需让位 |
| **WorldChunk.setBlockState(class_2818;method_12010)** | **Carpet 两个 mixin 都压在这里**（@Redirect onPlace/onRemove、@Redirect getBlockEntity） | **镜像候选点被占** → 结论见下 |

**3. 镜像钩子位置最终定案（两条独立证据收敛到同一点）**
- 分片 D：Axiom 绕过 World.setBlockState，从 **ChunkSection.setBlockState(method_12256)** 直写 → 必须挂这一层才不漏。
- 分片 B：**Carpet 与 TIS 都没碰 ChunkSection.setBlockState**，而 WorldChunk.setBlockState 被 Carpet 压了两个 mixin。
→ **镜像主钩子定死在 ChunkSection.setBlockState（method_12256）**；WorldChunk.setBlockState 降级为可选辅助点，且只能观察。

**4. Carpet 的寻路 hook 是纯被动观测**：PathNavigation_pathfindingMixin 只 @Redirect EntityNavigation.createPath(method_6348/6349)，源码首行就是 `if(!LoggerRegistry.__pathfinding) return createPath(...)`；对 findPathToAny(class_13;m52/54)、LandPathNodeMaker(class_14)、ChunkCache(class_1950)、recalculatePath(method_6356) **零注入**。

**5. 会毁掉逐位一致的规则清单（默认全关，但一旦有人开就会毁掉基准）**
- Carpet：fastRedstoneDust、optimizedTNT、lagFreeSpawning、movableBlockEntities、tntDoNotUpdate、fillUpdates=false、quasiConnectivity=0、pushLimit、railPowerLimit。
- TIS：**redstoneDustRandomUpdateOrder（随机化红石粉更新顺序，最毒）**、totallyNoBlockUpdate、updateSkippingSimulator、updateSuppressionSimulator、instantBlockUpdaterReintroduced、repeaterHalfDelay、dustTrapdoorReintroduced、optimizedFastEntityMovement、optimizedHardHitBoxEntityCollision。
- **建议**：Cava 启动时读 Carpet/TIS 的规则快照（Carpet 的 /testcarpet dump），检测到上述规则开启就在兼容性报告里明确警告，并对相应子系统让位。

**6. Lithium × TIS 的交互**：TIS 的 optimizedTNTHighPriority 明说要用更高优先级覆盖 Lithium 的爆炸优化；optimizedFastEntityMovement.compat.lithium 是 @Dynamic 条件注入。TIS fabric.mod.json 声明 breaks lithium<=0.11.0，装的是 0.12.1 不在范围内。

**未验证**：TIS「默认关规则零副作用」只做到常量池门控字符串级；wetExplosionReintroduced 默认值自动提取失败；fastRedstoneDust=true 与 Cava 同点注入的实际执行顺序需真实环境验证。

---

## 7. 测试服部署清单（P0-D 追加，2026-09-22）

> **编号说明**：本文件上方原有的「## 7. 分片调研状态」与附录 A 保持原样（任务书要求不动已有内容），
> 本节按任务书以「第 7 节：测试服部署清单」为题**追加在文件末尾**。
> 实际部署由 **P0-E** 落地（`tools/setup-testbed.ps1` 等）；本节把【确切步骤 + 版本核实 + 配置理由 + 卡点】写死，供复现与回填。

### 7.1 现状（全部本机实测）

- 工作区里**没有**现成的服务端根目录（见 `docs/CAVA-launch-notes.md` §3）；`J:\mc\mods\run\saves\新的世界`（17.7 MB）是**客户端存档，未验证**能否给服务端用。
- 49 个服务端 mod 全在 `J:\mc\Cava\优化模组\服务端模组\`（174.6 MB）。
  **该目录里没有服务端加载器 jar**：按 `loader|server-launch|installer|fabric-server` 过滤只命中
  `server-CustomSkinLoader_Universal-15.0.1.jar`（那是 env=client 的误装件，不是加载器）。
- **已实测落地**（P0-E）：`testbed/server/`（真 Fabric 服务端）、`testbed/dl/`（加载器 jar）、
  `testbed/mod-plan.tsv`（49 行三档计划）、`testbed/runs/wave0..wave4/`（分波冒烟日志）、
  `testbed/gate-preview/`（**架构级门**验证目录）。
- 全部在 `testbed/`（`.gitignore`）——**jar 一个都不进 git**。

### 7.2 装哪种加载器、从哪下（URL 全部 node fetch 实测 200）

| 组件 | 版本 | 来源 / URL | 实测 |
| --- | --- | --- | --- |
| **Fabric Server Launcher**（推荐） | loader **0.19.5** + installer **1.1.2** | `https://meta.fabricmc.net/v2/versions/loader/1.20.4/0.19.5/1.1.2/server/jar` | **200** `application/java-archive`；落盘名 `fabric-server-mc1.20.4-loader0.19.5-installer1.1.2.jar`（`testbed/dl/` 实测 181840 B） |
| 原版服务端 | 1.20.4 | piston-meta → `https://piston-data.mojang.com/v1/objects/8dd1a28015f51b1803213892b50b7b4fc76e594d/server.jar` | size **49150256**、sha1 `8dd1a280…594d`；`testbed/server/server.jar` 实测 **49150256 B** ✅ |
| Fabric API | **0.96.11+1.20.4**（整合包实际版本） | `https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.96.11+1.20.4/fabric-api-0.96.11+1.20.4.jar` | 200 ✅ |
| （对比）Fabric API | 0.97.3+1.20.4（**项目 `gradle.properties` 里的版本**） | 同 maven 路径 | 200 ✅ |

**版本判据**：meta `v2/versions/loader/1.20.4` 共 253 条，**0.19.5 = 最新 stable**；
`v2/versions/installer` 最新 stable = **1.1.2**；与 `gradle.properties` 的 `loader_version=0.19.5` 一致。

> ✅ **fabric-api 版本分叉 —— captain 已拍板（2026-09-22，决定 #2）：构建期与运行期统一用 `0.96.11+1.20.4`。**
> 理由：验收语言是「与同一套整合包、native 关闭时逐 tick 一致」，构建期与运行期版本不一致本身就是一类不可控变量。
> `gradle.properties` 的 `fabric_api_version` 由 **P0-A** 改成 `0.96.11+1.20.4`（模板自带的 0.97.3 弃用；
> 若 0.96.11 在 Loom 下解析不到，P0-A 要立刻报告，**不要自己悄悄换回去**）。

**Carpet / TIS / GCA 的确切版本（jar 文件名 + jar 内 `fabric.mod.json` 实读）**

| jar 文件名 | modid / version | 声明的依赖 |
| --- | --- | --- |
| `[地毯] fabric-carpet-1.20.3-1.4.128+v231205.jar` | `carpet 1.4.128+v231205` | minecraft >1.20.1、fabricloader >=0.14.18、java >=17 |
| `server-carpet-tis-addition-v1.82.3-mc1.20.4.jar` | `carpet-tis-addition 1.82.3` | **carpet >=1.4.128**、fabricloader >=0.14.25、mixinextras >=0.3.0 |
| `[Gugle的Carpet附加包] gugle-carpet-addition-2.7.0-1.20.4.jar` | GCA 2.7.0-1.20.4 | — |

### 7.3 一键部署（P0-E 的脚本，均已实测存在）

```powershell
# 全量 keep 档（默认 -Wave 4）；要最小冒烟用 -Wave 0
pwsh -File tools/setup-testbed.ps1
pwsh -File tools/setup-testbed.ps1 -Wave 0
pwsh -File tools/setup-testbed.ps1 -Force            # 重新下载 jar

# 只重铺 mod（三档策略 + 分波）
pwsh -File tools/copy-mods.ps1 -Wave 4
pwsh -File tools/copy-mods.ps1 -Profile full-minus-must-off
pwsh -File tools/copy-mods.ps1 -Probe easyauth,voicechat

# 起服（就绪判据 = 日志出现 "Done ("；超时用 RCON 优雅停）
pwsh -File tools/start-server.ps1 -Name smoke -MaxSeconds 180
pwsh -File tools/start-server.ps1 -Name vanilla-probe -Vanilla -MaxSeconds 60
```

脚本行为（读源码实测）：

| 脚本 | 做什么 |
| --- | --- |
| `tools/setup-testbed.ps1` | node 解析 meta 的 loader/installer → 下 launcher → 下 vanilla 并校验 sha1 → 写**确定性** `eula.txt` / `server.properties` / `fabric-server-launcher.properties`（`serverJar=server.jar`）→ 调 `copy-mods.ps1` |
| `tools/copy-mods.ps1` | 按三档 + wave 把 49 个 jar 分级拷进 `testbed/server/mods`，其余移到 `testbed/disabled/<tier>/` |
| `tools/start-server.ps1` | 固定 JVM 参数起服，等 `Done (` 就绪，超时走 RCON 停服；日志落 `testbed/runs/<name>/` |
| `tools/dl.cjs` / `meta.cjs` / `get-vanilla.cjs` | 下载与 meta 解析（**一律走 node**：本机 PowerShell 的 `Invoke-WebRequest` TLS 全失败） |

### 7.4 三档处理（`testbed/mod-plan.tsv` 实测分档）

| 档 | 数量 | 处置与内容 |
| --- | --- | --- |
| `keep` | **33** | 拷进 mods（优化九件套 + 库 + Carpet 三件套 + 功能类） |
| `noise` | **8** | **必须关**：BlueMap / Axiom / Ledger / ledger-databases / Geyser / AutoModpack / RandomTP / MCMOD |
| `suggested` | **4** | **建议关**：Floodgate / Syncmatica / voicechat / EasyAuth |
| `clientenv` | **4** | env=client 误装件（sodium / continuity / CustomSkinLoader / malilib），专用服务端不会加载 |

合计 33+8+4+4 = **49** ✅

> ⚠️ **实测踩到的依赖不变量（务必遵守）**：**TIS 硬依赖 Carpet**。13:55 的 `testbed/server/logs/latest.log` 里服务器**加载失败**：
>
> ```
> Mod resolution failed
> Immediate reason: [HARD_DEP_NO_CANDIDATE carpet-tis-addition 1.82.3 {depends carpet @ [>=1.4.128]}, ROOT_FORCELOAD_SINGLE …]
> Fix: add [add:carpet 1.4.128 ([[1.4.128,∞)])], remove [], replace []
> ```
>
> → `keep` 档里 **carpet / gca / tis-addition 必须同进同出**（分波时 wave3 三件一起上）。

### 7.5 server.properties 与启动参数的推荐配置（含理由）

```properties
# 确定性锚点（改任何一个都会让「逐 tick 一致」失去意义）
level-seed=20260922
level-type=minecraft:normal
generate-structures=true
randomTickSpeed=3
difficulty=normal
view-distance=10
simulation-distance=10
sync-chunk-writes=true

# 测量相关
max-tick-time=-1              # 关看门狗：测量期绝不能被 watchdog 杀进程
player-idle-timeout=0
rate-limit=0
white-list=false

# 布场 / 自动化
gamemode=creative
spawn-protection=0
allow-flight=true
enable-command-block=true
function-permission-level=2
op-permission-level=4
online-mode=false             # EasyAuth 需要离线模式
enable-rcon=true
rcon.port=25575
rcon.password=cava            # P0-E 用它优雅停服 / 远程执行
```

**启动参数（`tools/start-server.ps1` 默认值，实测）**：

```
-Xms2G -Xmx4G -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 --enable-preview --enable-native-access=ALL-UNNAMED
```

- `--enable-preview`：**硬约束**（预览版 class 版本 65.65535；见契约 §2）。
- `--enable-native-access=ALL-UNNAMED`：消除 FFM 受限方法警告（契约 §2.4）。
- 自动保存：采集期用 `/save-off`，结束再 `/save-all flush`（`server.properties` 里没有直接的开关；由 scenario 脚本负责）。

### 7.6 EasyAuth 测试账号「必须已登录」怎么落地

- **为什么**：EasyAuth 的 `ServerPlayerEntityMixin.playerTick()` 是 `@Inject(HEAD, cancellable)` →
  **未登录玩家的整个 playerTick 被取消**，会扭曲实体侧负载分布（清单附录 A.2）。
- **怎么做**（命令字面量从 EasyAuth 3.2.1 的 `data/easyauth/lang/en_us.json` **实读**）：

| 时机 | 命令 | lang 键 |
| --- | --- | --- |
| 首次注册 | `/register <password> <password>` | `registerRequired` |
| （配了全局密码时） | `/register <global password> <password> <password>` | `registerRequiredWithGlobalPassword` |
| 每次进服 | `/login <password>`（别名 `/l`） | `loginRequired`：「You are not authenticated! Use /login, /l to authenticate!」 |

- 前提：`online-mode=false`（正版登录时 EasyAuth 直接放行：lang `onlinePlayerLogin = "You are using an online account. No need to log in."`）。
- **采样前的检查项**：测试账号在 `/list` 里在线，且日志里**没有** `loginRequired` 提示，才可以开始计时采样。
- 更稳的做法（也是 `mod-plan.tsv` 的默认选择）：**基线测量期间不装 EasyAuth**（`suggested` 档），需要时用 `-Probe easyauth` 单独跑一轮。

### 7.7 已通过的门 / 仍未验证的项

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 服务端能真实跑起来（MC 1.20.4 + loader 0.19.5） | ✅ | `testbed/gate-preview/logs/latest.log:80` → `Done (14.193s)! For help, type "help"` |
| **预览版 class 能被 Fabric Loader 加载（架构级门）** | ✅ **通过** | 同文件 18–24 行：`[CAVA-GATE] mod class file major.minor = 65.65535`、`runtime = 21.0.10+8-LTS-217`、`java.lang.foreign = java.lang.foreign.Linker`、`native strlen("cava") = 4`、`VERDICT = PREVIEW_OK` |
| 49 个 jar 三档分档 | ✅ | `testbed/mod-plan.tsv`（49 行；33/8/4/4） |
| 客户端存档 `新的世界` 能否给服务端用 | ❓ 未验证 | 需要拷进 `testbed/server/world` 实跑一次 |
| wave4 全量 keep 档启动 | ⛔ 当前失败 | `testbed/runs/wave4/server.log`：TIS 缺 Carpet 的 `HARD_DEP_NO_CANDIDATE`（见 §7.4） |
| spark 四块占比基线 | ❓ 待回填 | 归 **P0-E**（`docs/CAVA-baseline.md`） |
| 确定性证明（同存档两次运行逐 tick 一致） | ❓ 待回填 | 归 **P0-E + P0-C**（黄金轨迹） |

