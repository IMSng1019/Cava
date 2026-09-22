# Cava 后续对话提示词包

## 怎么用

1. **每个新会话都没有本次对话的记忆**。所以每个提示词里都塞进了「必读文档 + 已拍板决策 + 硬约束」，不需要你复述背景。
2. 用法：开新会话 → **先粘「通用上下文」** → 再粘某一个任务的提示词 → 开工。
3. 建议顺序：**P0** → **兼容层** → **差分测试** → **P1 寻路** → **P2 实体** → **P3 红石** → **P4 跨平台加固**。（兼容层与差分测试是所有子系统的前置，越早做越省事。）
4. 每次收工，**要求那个会话把结论写回 docs 下的对应文档**——这是唯一的跨会话记忆。做完一项就更新一次，否则下一个会话要从头猜。
5. 项目里已有三份调研缓存（.research、.probe、.cava-research，均已 gitignore），里面有 800+ 个 mixin 的解析结果与逐 mod 报告，可用来自查，不必重新下载。

---

【通用上下文 · 从这里复制】

# Cava 项目上下文（新会话必读）

## 项目是什么
Cava 是 Minecraft 1.20.4 的 **Fabric 服务端 mod**。做法：用 **Java 21 的预览版 FFM**（java.lang.foreign，必须 --enable-preview）调用 C++ 动态库，把三个子系统的内层循环搬到原生侧（**生物寻路 / 实体开销 / 红石**），让服务器更快——**但服务器行为必须与现在完全一致**。

## 工作目录
J:/mc/Cava　（Fabric mod 项目；原生代码放 native/；文档在 docs/）

## 开工前必须读完的文档（缺一不可）
- docs/CAVA-v1-plan.md —— 总体方案：架构、里程碑、数值规则、差分夹具
- docs/CAVA-hook-points.md —— 19 个注入点、注入纪律、镜像数据契约、三种失效模式
- docs/CAVA-platform-and-compat.md —— 平台矩阵、逐 mod 兼容结论、语义基准
- docs/CAVA-服务器模组清单.md —— 服务器实际 49 个模组、逐 mod 详解（附录 A.1–A.4）、加新功能检查清单

## 已拍板的决策（不要再问，直接执行）
1. **载体**：Fabric mod，不做服务端 fork。
2. **语义基准 = 模组优先**：基准是「服务器当前这套整合包的行为」，**不是纯原版**。绝不允许装了 Cava 之后行为发生变化。每个与别的 mod 重叠的点，必须显式决定 **让位** 还是 **复刻**，并写进文档。验收语言是「**与同一套整合包、native 关闭时逐 tick 一致**」。
3. **平台**：先 windows-x64 + linux-x64；arm64 / macOS 推迟到 P4。
4. **JDK 锁 21.0.x 且必须带 --enable-preview**（预览版 class 版本 65.65535，JDK 22 即使加标志也拒绝加载）。
5. **服务器已启用的 Carpet 规则**：fastRedstoneDust（红石粉卡顿优化）+ 无卡顿刷怪 + TNT 优化 → **红石的语义基准是 Carpet 的算法，不是原版**。
6. 三个子系统都要做，顺序按依赖推进（寻路 → 实体 → 红石）。

## 硬约束（违反会返工）

**数值 / 跨平台**
- 只有 + - * / 和 sqrt 允许跨到 native；sin、cos、tan、atan2、exp、log、pow **一律留在 Java**（实测没有任何 libm 能与 Java 逐位一致）
- 编译固定 -O2 -fwrapv -ffp-contract=off -fno-fast-math（MSVC 用 /O2 /fp:strict）；**禁 -march=native**
- 禁 long double、禁裸 char 参与数值、禁裸 long；结构体跨边界只传指针
- double 转 int 必须自研饱和转换（Java 越界饱和，C++ 是 UB，x86 上会给 INT_MIN）
- 整数除法前保证除数非零

**注入纪律**
- 只用 @Inject(at=HEAD, cancellable=true)；**绝不 @Overwrite / @Redirect**（要改表达式就用 MixinExtras 的 @WrapOperation）
- 所有注入 require=0；**显式设 priority**（默认 1000）；每个钩子要有金丝雀计数与失败回退
- 镜像钩子**只观察**，不 cancel、不 redirect

**镜像数据契约**
- 方块状态表用 **state id** 做键，禁用对象身份（FerriteCore 的状态去重会让内容相同的状态共享同一个 VoxelShape 实例）
- 不缓存 PalettedContainer.data；**不依赖调色板 lock/unlock**（VMP 与 Lithium 都把它移除了）
- **镜像主钩子 = ChunkSection.setBlockState(method_12256)**：Axiom 从这一层直写方块，且 Carpet/TIS 都没碰它
- 不能假设方块写入都伴随邻居更新（Axiom 的 REASON_NOUPDATES）
- 线程假设：世界变更只在主线程（Ledger 回档与 Axiom 光照两处未验证，需加断言）

## 本机环境注意事项
- **web_fetch 不可用**：GitHub / Modrinth 被 DNS 解析到 198.18.x.x 假 IP。需要联网时用 **node 直接 fetch**。
- Gradle 的 GRADLE_USER_HOME 指向工作区外的 J:/mc/mods/.gradle-home，文件沙箱可能拒绝写入 → 构建前先确认，必要时改用工作区内的 GRADLE_USER_HOME。
- 本机工具：JDK 21.0.10 / 17 / 22；MSVC 14.44（VS 2022 BuildTools）；MinGW GCC 15.2（C:/mingw64）；Docker CLI 有但守护进程未运行。
- **CMake 已经装好（P0 已完成这一项）**：便携版 3.31.2，路径 J:/mc/Cava/tools/cmake/cmake-3.31.2-windows-x86_64/bin/cmake.exe（**不在 PATH 里，需要写全路径或自行加入 PATH**）。tools/ 已加进 .gitignore。
- **构建器不需要 Ninja**：C:/mingw64/bin/mingw32-make.exe 可用（配 MinGW Makefiles 生成器），或用 J:/mc/Cava 之外的 MSBuild（Visual Studio 17 2022 生成器，路径 C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/MSBuild/Current/Bin/MSBuild.exe）。
- 现成证据（优先用本地证据，不要靠猜）：
  - Yarn 映射：C:/Users/郁小悟520/.gradle/caches/fabric-loom/1.20.4/net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny
  - Mojang 映射：同缓存上层 layered/working_dir/layered_hash_2198/mojang/server.txt
  - 服务器模组 jar：J:/mc/Cava/优化模组/服务端模组/
  - 调研缓存：.research、.probe、.cava-research

## 工作方式要求
- **不要凭记忆写类名或方法名**。Yarn 名与 Mojang 名是两套，例如 findPathToAny 对 findPath、getReceivedRedstonePower 对 calculateTargetStrength、update 对 updatePowerStrength、adjustMovementForCollisions 对 collideBoundingBox。一律用本机映射核对。
- 有本地证据就用本地证据（jar 的 refmap + javap 注解 + 本机映射）。
- 每完成一项，**把结论写回 docs 下对应文档**——这是唯一的跨会话记忆。
- 不确定就明确标注「未验证」，不要把推测写成结论。

【通用上下文 · 复制到这里结束】

---

【提示词 P0 · 环境与骨架 · 从这里复制】

## 本任务目标
把开发环境与骨架搭起来，**不碰任何游戏逻辑**。做完之后：服务端能用 Java 21 + --enable-preview 启动、能加载一个什么都不做的原生库、能跑差分测试骨架、并采到第一条性能基线。

## 具体任务
1. ~~补齐工具链~~ **已完成**：便携版 CMake 3.31.2 已解压到 J:/mc/Cava/tools/cmake/cmake-3.31.2-windows-x86_64/bin/cmake.exe（不在 PATH，用全路径）。构建器用 mingw32-make 或 MSBuild，都不需要 Ninja。本任务直接从这里开始。
2. **Gradle / Loom 改造**
   - options.release = 21 且同时加 --enable-preview（javac 要求二者一致）
   - 运行参数加 --enable-preview --enable-native-access=ALL-UNNAMED
   - **模板自带的 .github/workflows/build.yml 用的是 JDK 25，与 --release 21 --enable-preview 冲突，必须改成 JDK 21**
   - gradle.properties 显式锁 JDK 21 工具链
   - 先解决 GRADLE_USER_HOME 在工作区外导致构建失败的问题
3. **native 目录与最小 C ABI 模块**：CMakeLists + cava_abi_version() / cava_create() / cava_destroy()，外加一个导出的结构体布局自检表（各结构体 sizeof 与关键字段 offsetof）。
4. **Java 侧 FFM facade**（cava.native.ffm 包，**不引用任何 MC 类型**）
   - 库路径：natives/系统-架构/ 从 jar 解压到 游戏目录/cava/natives/版本/系统-架构/（内容哈希命名 + 原子改名）
   - 校验链：ABI 版本 → 布局自检 → 任一失败则**整体回退纯 Java**并打明确日志
   - **JDK 21 预览 API 的三个坑（已实测，务必注意）**
     - 没有 Linker.Option.critical（那是 JDK 22 才有的）
     - 数组分配是 arena.allocateArray(layout, count)；**allocate(ValueLayout.JAVA_INT, 10) 是「分配一个 int、值为 10」，只有 4 字节**——用错会让原生代码越界，直接把 JVM 打成段错误（本项目已复现过一次）
     - Arena 没有 byteSize()
   - 铁律：任何 (指针, 长度) 必须同源
5. **差分测试骨架**：开关 -Dcava.native.enabled=false|true；黄金轨迹格式（逐 tick 世界哈希 + 实体位模式 + 路径节点序列）与比对器；先证明「同一存档连续两次运行结果完全一致」（确定性前置条件）。
6. **钩子金丝雀框架**（先搭框架，P1 才用）：每个钩子一个计数器；启动后主动触发一次目标方法，计数器没动就禁用该子系统并报错。
7. **采第一条基线**
   - spark（已装在服务器上）做剖析，重点拿到四块占比：**实体 tick / 寻路 / 方块 tick（含红石）/ 区块生成**
   - 用原版 /tick freeze|step|sprint|rate 控制节奏；用 Carpet /log pathfinding、TIS /log movement、/log microTiming 采一段黄金轨迹
   - 测量时按 docs/CAVA-服务器模组清单.md 第 5 节的三档处理重量级 mod：**BlueMap / Axiom / Ledger / Geyser / AutoModpack / RandomTP 必须关；MCMOD 会定时 System.gc() 也要关**；EasyAuth 的测试账号必须已登录

## 验收标准
- 服务端带 --enable-preview 能启动，并打印 ABI 版本与布局自检结果
- native 关闭 / 开启两次运行，黄金轨迹**完全一致**（此时原生什么都不做）
- 一键回退可用（-Dcava.native.enabled=false）且不报错
- 拿到 spark 基线四块占比数字

## 禁止
- 不要加任何游戏内钩子、不要改任何原版行为
- 不要用 @Overwrite / @Redirect

【提示词 P0 · 复制到这里结束】

---

【提示词 兼容层 · 从这里复制】

## 本任务目标
把「让位 / 复刻」从结论变成**代码可执行的配置 + 启动时可见的报告**。这是所有子系统的前置。

## 背景要点
语义基准是「当前整合包的行为」。所以每个与别的 mod 重叠的点，要么**让位**（对方继续跑，我们零加速），要么**复刻**（我们在 C++ 里实现对方的语义，性能归我们）。两者都必须显式记录。

## 具体任务
1. **Cava 自己的 fabric.mod.json 写 custom.lithium:options**（Lithium 官方机制；已在源码里核实 LithiumConfig.applyModOverrides 会遍历所有 mod 的元数据，false 优先，键可带或不带 mixin. 前缀）
   - **注意**：只有在选择「复刻」时才关掉对方的组；选择「让位」时**不要关**
   - 候选键：mixin.entity.collisions.movement、mixin.block.redstone_wire、mixin.ai.pathing、mixin.shapes
2. **ServerCore 适配器**：反射读它的公开接口 me.wesley1808.servercore.common.interfaces.activation_range.Inactive（方法 servercore$isInactive），复刻它的 Entity.push 短路与激活范围语义。它的补丁不可配置，只能复刻或让位。
3. **VMP 适配器**：复刻 Entity.move 的零位移短路（源码条件：包围盒未变且 movement 等于 Vec3d.ZERO 时取消）。它同样不可配置，且这是**行为改变**，不复刻就会与当前服务器不一致。
4. **Carpet / TIS 规则检测**：启动时读取规则快照（Carpet 的 /testcarpet dump，或直接读 config 文件），列出「会毁掉一致性」的规则（清单见 docs/CAVA-服务器模组清单.md 附录 A.4 第 5 条），检测到开启时：报告里警告 + 对相应子系统让位
   - **已知现状**：用户已开启 fastRedstoneDust 与 TNT 优化，红石子系统的基准就是它们，不是原版
5. **启动兼容性报告**：一张表，写清「检测到的相关 mod → 与我们的重叠点 → 最终归属（native / mod / vanilla）→ 被禁用的子系统及原因」
6. **config/cava.json**：按子系统 × 按 mod 的 ownership 配置（auto / native-first / defer）

## 验收标准
- 在装有全套整合包的环境里启动，报告正确反映每个重叠点的归属
- 把 native 全关时，服务器行为与「完全没装 Cava」一致
- 每个决定都能被配置覆盖

【提示词 兼容层 · 复制到这里结束】

---

【提示词 差分测试 · 从这里复制】

## 本任务目标
建三层差分测试与 mod 组合矩阵。这是整个项目的脊梁：**没有它，任何「行为一致」的声明都不成立**。

## 具体任务
1. **确定性前置**：先证明「同一存档、同一套 mod、连续两次运行，逐 tick 结果完全一致」（固定种子、单线程、不依赖 wall-clock、固定 randomTickSpeed、关掉会造成差异的自动保存）。做不到就没有差分测试可言。
2. **三层测试**
   - 单元层：原生纯函数 vs Java 纯函数，百万级随机与边界输入，位模式 100% 一致
   - 场景层：固定存档 + 脚本化操作，记录黄金轨迹（每 tick 世界哈希、实体位模式、路径节点序列）
   - 整服层：同一存档跑 6000–20000 tick，native 开 / 关两次运行比对
3. **采集工具用现成的，不要自己造**
   - TIS /log movement —— Entity.move 的 HEAD/RETURN 加 limitPistonMovement / maybeBackOffFromEdge / collide 四处逐 tick 全量（**实体差分核心**）
   - TIS /log microTiming —— tick 阶段耗时 + 方块更新 / 状态更新 / 比较器更新 / 活塞事件逐条，带 pos/depth/event_source（**红石差分核心**）
   - Carpet /log pathfinding —— 寻路耗时与路径可视化（**寻路差分第一手观测**）
   - 原版 /tick freeze|unfreeze|step|sprint|rate（1.20.3 起是原版命令；Carpet 1.4.128 自己没有 TickCommand）
   - 注意 logger 默认开关：TIS loggerMovement 默认 ops、loggerMicroTiming 默认 false
4. **mod 组合矩阵**：至少跑纯原版、+整合包、+整合包且 native on/off 三档；重量级 mod 按 docs/CAVA-服务器模组清单.md 第 5 节关掉后再跑一遍
5. **CI 化**：单元层跑全组合；场景层至少跑纯原版与 +整合包两档

## 验收标准
- 能一条命令跑出「native on 与 off 的逐 tick 差异报告」
- 差异为零时报告明确说零；有差异时能定位到第几 tick、哪个子系统、哪个实体或方块坐标

【提示词 差分测试 · 复制到这里结束】

---

【提示词 P1 · 生物寻路 · 从这里复制】

## 本任务目标
用原生 A* 替换 PathNodeNavigator.findPathToAny，行为与**当前整合包**一致。这是第一个子系统，也是基础设施需求最小的一个（只需要方块状态表 + 区段镜像，不需要实体镜像）。

## 关键事实
- **注入点**：PathNodeNavigator.findPathToAny，两个重载都要覆盖（Yarn 名；intermediary method_52 带 Set 参数、method_54 带 Map 参数）。注意 findPath 是 Mojang 名，Yarn 名的 mixin 要写 findPathToAny。
- **占用者**：ServerCore 在方法体内做 4 个 @Redirect + 2 个 @ModifyVariable（把 Map/Set 换成 fastutil 实现并预填），**不可配置**；Lithium **完全不碰 class_13**。我们的 @Inject(HEAD, cancellable) 会让 ServerCore 的补丁自然不执行——需要差分验证这不会改变可观测结果（它理论上只换容器实现）。
- 相关类：PathNodeMaker(class_8)、LandPathNodeMaker(class_14)、BirdPathNodeMaker(class_6)、WaterPathNodeMaker(class_12)、PathNode(class_9)、PathNodeType(class_7)、PathMinHeap(class_5)、Path(class_11)、TargetPathNode(class_4459)、EntityNavigation(class_1408)、MobNavigation(class_1409)。

## 必须复刻的清单（parity 风险点）
1. PathMinHeap 的 sift-up/sift-down 行为与**相等元素的相对顺序**（决定同代价路径选哪一条）
2. getNeighbors 的邻居展开顺序
3. 节点去重语义（打包坐标做 key；重复访问时 g 值如何合并）
4. maxVisitedNodes 预算与提前退出的判定
5. malus 的 **float** 运算：禁止中途提升为 double、禁止改变结合顺序
6. 门 / 栅栏 / 脚手架 / 台阶 / 水 / 岩浆边缘的特判分支
7. 终点判定与 Path 的截断 / 后处理

## 基础设施（本任务要顺带做出来）
- **方块状态表**：每个 state 映射到 pathType（按通行档案：陆地/水生/飞行/两栖 × 开门/穿门/浮水能力）、碰撞盒（扁平 AABB 数组，**不要用体素近似**，原版碰撞用的是 AABB 集合）、isSolid / blocksMotion / 流体。**用 state id 做键，禁止对象身份。**
- **区段镜像**：16³ 区段，保留调色板压缩形式 + 派生 pathType 数组；主钩子挂 **ChunkSection.setBlockState(method_12256)**，只观察不取消；区段加载/卸载必须显式通知原生侧。

## 接口与纪律
- 一次调用返回整条路径：cava_pathfind(Cava*, const CavaPathRequest*, CavaPathNode* out, int32_t cap)，返回大于 0 = 节点数、0 = 无路径、小于 0 = 出错（Java 侧回退原逻辑）
- @Inject(HEAD, cancellable=true)，require=0，priority 1000；加金丝雀计数与失败回退

## 验收标准
- 单元层：10^5 组随机（区域、起终点、生物档案）逐节点、逐 f 值完全一致
- 场景层：迷宫 / 门前 / 水下 / 栅栏 / 脚手架 / 台阶 / 岩浆边缘 各 100 组逐节点一致
- 整服层：2000 实体 × 6000 tick，与「同整合包 native 关闭」逐 tick 一致
- **性能：必须与「装了 Lithium + ServerCore 的 Java 寻路」对比，而不是与纯原版对比。** 如果打不过它们，接管就是净亏，要如实报告。

【提示词 P1 · 复制到这里结束】

---

【提示词 P2 · 实体开销 · 从这里复制】

## 本任务目标
把实体移动 / 碰撞 / 推挤的内核搬到原生侧，行为与当前整合包一致。

## 四个核（按性价比排序）
1. **Entity.move(method_5784) 到 CollisionView.getBlockCollisions(method_20812)** 的 AABB × 方块碰撞盒求解（最高频、纯几何、只用四则运算与 sqrt）
2. **实体间 broadphase + Entity.pushAwayFrom(method_5697)**（原版在密集区域是 O(n²)）
3. **射线**（BlockView.clip / Entity.raycast）—— AI 视线与攻击判定
4. EntityView.getOtherEntities(method_8333/8335) 的候选集枚举（**谓词必须留在 Java 侧执行**，收益有限、优先级最低）

## 占用者（必须处理）
- **Lithium**：唯一一处 @Overwrite —— Entity.adjustMovementForCollisions(method_20736)，另有 @Redirect(require=5) 打在实例版 method_17835 体内。**它的源码注释自认「改了 entities/worldborder/blocks 的顺序，1e-7 VoxelShape margin 是否影响结果尚未研究」**。二选一：**让位**（保留 Lithium，零加速）或**复刻**（读它的 LGPL 源码重写；注意 LGPL-3.0-only 的衍生作品义务，建议按行为重写而非照抄代码）。
- **VMP**：Entity.move 上 @Inject(HEAD, cancellable)，零位移短路，**不可配置且是行为改变** → 必须复刻，否则与你现在的服务器不一致。
- **ServerCore**：Entity.push 上 @Inject(HEAD, cancellable)（与我们同点）+ Entity.move 在 INVOKE limitPistonMovement 处注入 + 激活范围（inactive 实体整 tick 不 tick）。要读它的公开接口复刻短路语义。

## 设计要求
- **事件回放**：原生只算几何与位移，把过程中接触到的方块位置与命中类型写进一个小数组返回；Java 侧按原版顺序执行 entityInside / stepOn / updateEntityMovementAfterFallOn 等虚方法。这样即使有 mod 覆写了方块行为，语义也不变。
- **明确不动**：GoalSelector / Sensor / Brain 决策、属性系统、网络同步、**travel 里的三角函数部分**（生物朝向与移动向量合成留在 Java，把已算好的 Vec3 位移作为输入传进去）。这条边界同时保证了逐位一致，但也意味着 AI 移动全链路**不是**全加速——预期要提前说清。
- **实体镜像**：SoA 数组（id / 类型 / 位置 / 速度 / 碰撞箱 / 标志），每 tick 一次性打包上传。注意 ServerCore 激活范围内的实体**整 tick 不动**，这是预期行为，不要主动唤醒或回写。

## 验收标准
- 位置 / 速度的 double 位模式逐 tick 一致（在装了整套整合包的环境下）
- onGround / horizontalCollision / verticalCollision 一致
- 推挤的对象集合与最终结果一致
- 用 TIS /log movement 做逐 tick 比对

【提示词 P2 · 复制到这里结束】

---

【提示词 P3 · 红石 · 从这里复制】

## 本任务目标
把红石线网的计算搬到原生侧。**注意：语义基准不是原版，而是「Carpet fastRedstoneDust + TIS」的当前行为。**

## 关键事实
- **用户已开启** Carpet 的 fastRedstoneDust（红石粉卡顿优化）与 TNT 优化、无卡顿刷怪口径 → **服务器现在跑的红石算法已经不是原版**。
- 占用者：
  - **Carpet**：RedstoneWireBlock.update（Yarn 名；等于 Mojang updatePowerStrength，method_10485）上 @Inject(HEAD, cancellable)，源码形如 if(fastRedstoneDust){...; cir.cancel();} —— **已开启，正在生效**
  - **TIS**：同一方法上还有 @ModifyVariable
  - **Lithium**：RedstoneWireBlock.getReceivedRedstonePower（Yarn 名；等于 Mojang calculateTargetStrength，method_27842）上 @Inject(HEAD, cancellable)，priority 990，整段替换电力计算
  - 注意：1.20.4 的 RedstoneWireBlock **没有** getPower（那是 1.20.5+ 的名字）；1.20.4 里 getPower 属于 AbstractRedstoneGateBlock(method_9991)
- 安全区：AbstractRedstoneGateBlock、RepeaterBlock、ObserverBlock、PistonBlock、NeighborUpdater(class_7165)、ChainRestrictedNeighborUpdater(class_7159) 在已审计的全部 mod 里**零命中**

## 三个选项（每个点都要显式决定并记录）
1. **让位**：不碰 update(method_10485)，红石零加速
2. **复刻 Carpet**：读 Carpet 的 MIT 源码（fastRedstoneDust 实现），在 C++ 里实现同一算法 → 性能归我们
3. **复刻 Lithium**：读它的 LGPL 源码重写电力计算（注意许可证）
推荐顺序：先做源码比对，能复刻 Carpet 就复刻——它是当前生效的那一层。

## 必须复刻
六向更新的遍历顺序、flags 位语义、链深上限、比较器 / 中继器等方块实体的 tick 顺序。
**权威依据**：Lithium 的红石 mixin 类注释明确写了「移除冗余方块更新用一个活板门上的红石线就能检测到，移除递归更新用依赖特定更新顺序的定位装置就能检测到，所以我们只优化电力计算」——这条边界就是我们的安全边际。

## 夹具
**TIS /log microTiming 是红石差分的核心**：它逐条记录方块更新 / 状态更新 / 比较器更新 / 活塞事件，带 pos / depth / event_source。

## 验收标准
- contraption 语料逐 tick 世界哈希一致（**在开启 fastRedstoneDust 的前提下**）：中继器时钟、活塞门、比较器逻辑、instant wire、1-tick 脉冲、更新抑制边界、侦测器链
- 用你自己的机器复现一批现成装置，与「同整合包 native 关闭」比对

【提示词 P3 · 复制到这里结束】

---

【提示词 P4 · 跨平台与加固 · 从这里复制】

## 本任务目标
补齐 arm64 / macOS 支持，并做完上线加固。

## 具体任务
1. **补齐构建目标**：linux-arm64、macos-arm64、macos-x64（windows-x64 与 linux-x64 在 P0 已做）
   - 每个平台都必须跑一遍**数值一致性测试套件**（逐位一致性 + 编译开关 + ABI 布局自检）——这是「全平台」真正的成本所在
   - 注意 Apple Clang 默认 -ffp-contract=on 且 arm64 有 FMA，必须显式关掉
   - 打包进 jar 的 natives/系统-架构/，运行期解压 + 哈希校验 + ABI 版本校验
2. **CI 矩阵**：ubuntu-24.04(x64) / ubuntu-24.04-arm / windows-2022 / macos-13(x64) / macos-14(arm64)。**注意模板自带 workflow 用的是 JDK 25，与 --release 21 --enable-preview 冲突，必须改成 JDK 21。**
3. **加固**
   - 熔断：同一原生入口连续失败 N 次自动全局关闭 native
   - 看门狗：单次调用超阈值（如 50 ms）记录告警
   - 崩溃取证：保留 hs_err 日志解析、native 侧断言开关（SAFE 构建）
   - fuzz：越界坐标、NaN、超大 AABB、区段并发卸载、调色板损坏
   - ASan / UBSan 在 CI 上跑一遍
4. **上线**：灰度开关、回滚步骤文档、连续运行 7 天无崩溃且 native 回退计数为 0

## 验收标准
- 5 个平台的数值一致性测试全绿
- 装载全套整合包连续运行 7 天，native 回退计数为 0
- 任意时刻可用一个 JVM 参数回到纯 Java

【提示词 P4 · 复制到这里结束】

---

## 附：一个可以反复用的小提示词

【加新功能前的检查 · 从这里复制】

我要给 Cava 加一个新功能，需要注入到原版方法。请按 docs/CAVA-服务器模组清单.md 第 6 节的检查清单执行：
1. 先定位要动哪个方法（写出 **Yarn 名 + intermediary**，用本机映射核对，不要凭记忆）
2. 查占位：在 docs/CAVA-hook-points.md 与 docs/CAVA-服务器模组清单.md 里查有没有别的 mod 动它；按对方注入类型决策（@Overwrite / HEAD-cancellable / 方法体内补丁 / 调用点 @Redirect / @Accessor）
3. 检查镜像数据契约（对象身份做键、PalettedContainer.data、调色板锁、静态线程池字段等）
4. 注入纪律：@Inject(HEAD, cancellable)、require=0、显式 priority、绝不 @Overwrite 与 @Redirect
5. 加金丝雀计数、失败回退、兼容性报告条目
6. 说明验收方式（parity 三层 + mod 组合矩阵）
如果要动的点在表里没有记录，请**实际读那个 mod 的 jar**（refmap + javap 注解）再下结论，不要猜。

【加新功能前的检查 · 复制到这里结束】

---

## 附二·A：**P0 已完成**（2026-09-22 多代理并行轮，全部有实测证据）

> **新会话先读这三份**：`docs/CAVA-工程接口契约.md`（共享契约）、`docs/CAVA-gates.md`（已通过的门禁与实测坑）、`docs/CAVA-execution-plan.md`（多代理主计划与文件所有权）。

- **`gradlew build` 已可过**（`+ test` 28 单测全绿）。要守住 JDK 21 + `--release 21 --enable-preview`，**Loom 必须 pin `1.17.20`** —— Loom 1.18.x 的 module metadata 要求 **JVM 25**（模板自带 workflow 写 JDK 25 就是迁就它）。CI 已改回 JDK 21。
- **原生库能编能测**：`CMakeLists.txt` + `native/cmake/{CavaPlatform,CavaFlags}.cmake`；`cmake -S . -B build/native-<流名> -G "MinGW Makefiles" -DCMAKE_MAKE_PROGRAM=C:/mingw64/bin/mingw32-make.exe`；`ctest` **3/3 通过**；产物 `natives/windows-x64/cava.dll`；Windows 全静态运行时（依赖只剩 KERNEL32+msvcrt，代价 2.78MB）。
- **Java ↔ 原生端到端已通**：`cava.ffm.NativeSelfTest` → `status=OPEN`、`java_layout_sum == native_layout_sum == 0x6149FD30`、`SELF-TEST: PASS`；一键回退 `-Dcava.native.enabled=false` → `DISABLED_BY_FLAG`（INFO 非 ERROR）；缺库 → `RESOURCE_MISSING` 优雅退出。
- **ABI 守卫逐项实测**（`tools/LayoutGuardProbe.java`）：错误 layout_hash → `CAVA_ERR_LAYOUT`、错误/缺失 ABI 版本 → `CAVA_ERR_ABI_VERSION`，**都在写入句柄之前失败**；`cava_close` 幂等 + 伪造句柄安全。
- **架构级门已通过**：`javac --release 21 --enable-preview` 编出的 mod（class mini r=65535）能被真实 **Fabric Loader 0.19.5 / MC 1.20.4** 服务端加载并执行，`java.lang.foreign` 可用。**派生硬约束：启动参数必须含 `--enable-native-access=ALL-UNNAMED`。**
- **ABI 已冻结**（`native/include/cava_abi.h`）：P0 的 `cava_open/close/layout_report/abi_version/build_id/touch/d2i_sat/d2l_sat/bits_*`，P1 的 `CavaPathRequest/CavaPathNode/cava_pathfind`，**镜像侧**的 `cava_state_table_upload/cava_region_upload/cava_region_state_id_at`（**有界长方体区域推送**，不是整块世界镜像）。
- **Java 侧骨架已就位**：`cava.ffm`（唯一允许出现 `java.lang.foreign` 的包；`cava.native.ffm` **非法**，`native` 是保留字）、`cava.canary`、`cava.subsystem`、`cava.parity`（黄金轨迹 NDJSON + 固定扫描盒世界哈希）、`cava.CavaConfig`。
- **真测试服能起**：`tools/start-server.ps1` / `tools/copy-mods.ps1`；端口 **25566**（25565 留给别人，踩过冲突）；`testbed/` 已 gitignore。

### 本机实测过的坑（照做可省几小时，细节见 `docs/CAVA-gates.md` 附录与 `docs/CAVA-dev-toolbox.md`）
1. **PowerShell 是 5.1**：`& java -Dkey=value` 会被解析坏 → JVM 属性走 `$env:JAVA_TOOL_OPTIONS`；`javac -cp "a;b"` 也会坏 → 走 **UTF-8 的 `@argfile`**（ASCII argfile 会把含中文的用户目录名写成 `???`，classpath 静默失效）。
2. **`Linker.defaultLookup()` 看不到 `System.load()` 进来的 DLL** → 必须回退 `SymbolLookup.libraryLookup(path, arena)`（`cava.ffm.CavaBindings` 已封装，别自己写 defaultLookup）。
3. **`natives/<平台标签>/` 是共享输出目录**，并发构建会互相覆盖（实测把一次测试打假失败）→ 同时只有一个流能构建原生产物。
4. **CMake 的 `check_cxx_compiler_flag` 默认用 Debug 探针**，MSVC 下 `/O2` 与 `/RTC1` 冲突 → 误判"不支持 /O2"。已加 `CMAKE_TRY_COMPILE_CONFIGURATION=Release`。
5. **`javap` 不认 `--enable-preview`**；原版行为的权威来源是 Loom 缓存里那份 **命名** jar（`javap -p -c -classpath <它> <类>`）。
6. **共享 git index 会竞态**：并行期一律 `git commit -m "..." -- <显式路径>`，不要 `git add`。

---

## 附二·B：上一轮的成果（仍然有效）

- **环境**：便携版 CMake 3.31.2 已装在工作区内（见通用上下文）。

- **注入点清单**：19 个点，含 Yarn 名、intermediary、占用者、策略 —— docs/CAVA-hook-points.md
- **逐 mod 详解**：49 个 jar，分四个附录（A.1 小众/功能型、A.2 重负载类、A.3 核心优化类、A.4 Carpet 三件套）—— docs/CAVA-服务器模组清单.md
- **兼容矩阵与语义基准**：docs/CAVA-platform-and-compat.md
- **调研原始产物**：.cava-research/（四份分片报告 + javap 注解解析 + refmap 提取）、.probe/（552 个 mixin 源文件）、.research/（Lithium/C2ME 源码树）
- **关键数字**：FFM 边界 14–16 ns/次、upcall 26–29 ns/次、Arena 分配 140 ns；实测只有 + - * / 与 sqrt 能与 Java 逐位一致