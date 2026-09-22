# Cava 全平台与 mod 兼容设计

本文回答两件事：**"全平台"意味着什么工程代价**，以及**怎么和主流服务端优化 mod 共存**。
配套文档：Cava v1 技术方案（docs/CAVA-v1-plan.md）。

---

## 0. 本轮确认的决策

| 决策项 | 结论 | 直接影响 |
| --- | --- | --- |
| 载体 | **Fabric mod**，不做服务端 fork | 用 Mixin 注入；原版实现保留为回退路径；不分发 Mojang 代码 |
| 平台 | **全平台**：Windows / Linux / macOS × x64 / arm64 | 5 个原生产物 + 一整套跨平台数值一致性规则 + 5 平台 CI |
| 语义 | **严格照原版实现** | 任何偏差都算 bug；需要"语义基准"策略来处理其它 mod |
| 兼容 | 尽量与主流服务端优化 mod 共存 | 补丁所有权模型 + MixinExtras + 运行时兼容性报告 |

---

## 1. 全平台带来的硬性约束

### 1.1 目标产物矩阵

| 平台 | 架构 | 编译器 | 产物 | 关键要求 |
| --- | --- | --- | --- | --- |
| Windows | x64 | MSVC 14.4x | cava.dll | /MT 静态 CRT（不要求用户装 VC++ 运行库） |
| Linux | x64 | GCC 12+ | libcava.so | -static-libgcc -static-libstdc++（或不使用 STL） |
| Linux | arm64 | GCC / Clang | libcava.so | 面向 Ampere / 树莓派 / Asahi 等 |
| macOS | arm64 | Apple Clang | libcava.dylib | 必须显式 -ffp-contract=off（Apple Clang 默认 on，且 arm64 有 FMA） |
| macOS | x64 | Apple Clang | libcava.dylib | 可与 arm64 合并为 universal binary |

可选（按需再加）：linux-x64-musl（Alpine 容器）。

### 1.2 数值一致性：跨平台规则（比单平台严格得多）

这是"全平台"四个字真正的代价。以下每条都是逐位一致的硬要求，违反任何一条都会让不同平台的服务器行为发散：

| # | 规则 | 原因 / 实测依据 |
| --- | --- | --- |
| 1 | 只有 + - * / 和 sqrt 允许跨界 | 实测所有 libm 的 sin/cos/tan/atan2/exp/log/pow 都与 Java 不一致 |
| 2 | 必须显式 -ffp-contract=off | GCC 默认 fast、Clang 默认 on；开启时 a*b+c 实测 25.5% 位不一致 |
| 3 | 禁用 long double | x86 是 80 位、arm64 是 128 位、MSVC 是 64 位 |
| 4 | 禁用裸 char 参与数值 | x86 默认 signed，ARM 默认 unsigned |
| 5 | 禁用裸 long / unsigned long | Windows 是 LLP64（long 32 位），Unix 是 LP64（long 64 位） |
| 6 | 一律 int8_t/uint8_t/int32_t/int64_t/double/float + static_assert | 消除 3/4/5 的实现差异 |
| 7 | **double→int 必须自研饱和转换** | Java 规范：NaN→0、越界饱和到 MIN/MAX；C++ 是 UB，x86 上越界会得到 INT_MIN。vanilla 的 Mth.floor(double) 正是先做 (int)value 再比较，直接照搬会在超大坐标上发散（1e18：Java 得 INT_MAX，C++ 得 INT_MIN） |
| 8 | 整数除法/取模前保证除数非零 | Java 抛 ArithmeticException，C++ 是 UB（SIGFPE） |
| 9 | -fwrapv（GCC/Clang）；MSVC 默认回绕 | 保证 x*31+z 这类哈希与 Java 一致 |
| 10 | 禁用位域（bit-field） | 布局由实现定义，跨 ABI 不可移植 |
| 11 | 结构体不按值传递，只传指针 | Windows x64 / SysV AMD64 / AAPCS64 的结构体分类规则各不相同，FFM 的 GroupLayout 必须逐平台对齐，风险高收益低 |
| 12 | 禁 -march=native / -mcpu=native | x86 用基线 SSE2（或 x86-64-v2 + 运行期分派），arm64 用 armv8-a 基线 |
| 13 | 禁 -ffast-math | 会 flush 非规格化数、重结合，破坏逐位一致 |
| 14 | 编译期断言小端 | 当前所有目标平台都是小端，但不写死假设 |

**执行办法**：把上面这套数值测试（FFM 边界、逐位一致性、编译开关）做成一个独立测试套件，在 5 个平台的 CI 上全部跑一遍。否则"全平台"只是口号。

### 1.3 ABI 设计（跨平台）

- 只用 extern "C" + POD + 显式宽度类型；结构体一律指针传递。
- **导出一个布局自检结构**：C++ 侧提供 cava_abi_layout（含各结构体的 sizeof 与关键字段 offsetof），Java 侧用 MemoryLayout 构造同样的布局并逐项比对。两端结构体一旦漂移立即发现，而不是等到段错误。
- 导出 cava_abi_version()，版本不匹配直接拒绝加载。
- 编译期 -fno-exceptions -fno-rtti，不使用 STL 类型跨边界。
- Windows /MT，Linux/macOS 静态链接 libgcc/libstdc++（或干脆不用）。

### 1.4 打包、加载与回退

- jar 内布局：natives/<os>-<arch>/libcava.{dll,so,dylib}
- 运行期解压到 <gameDir>/cava/natives/<modVersion>/<os>-<arch>/：用内容哈希命名 + 原子改名，避免多实例并发写坏文件
- 用 SymbolLookup.libraryLookup(绝对路径, arena) 加载（Java 21 预览 API 支持 Path 重载，已实测）
- 校验链：ABI 版本 → 布局自检 → 可选文件哈希；任一失败则**整体回退纯 Java**并打明确日志
- 运行期单个子系统出错（错误码 / 超时 / 自检失败）→ 只熔断该子系统，其余继续，并计数上报

### 1.5 CI 与本机现状

| 项目 | 现状 | 要做的事 |
| --- | --- | --- |
| 模板自带 workflow | 用 **JDK 25** + ubuntu-24.04 | **必须改**：javac 25 不接受 --release 21 --enable-preview，构建与测试统一 JDK 21 |
| CI 矩阵 | 只有 ubuntu 一条 | 扩成 ubuntu-24.04 / ubuntu-24.04-arm / windows-2022 / macos-13 / macos-14 五条 |
| MSVC | 14.44 已装 | 直接用 |
| MinGW GCC | 15.2 已装 | 仅作辅助（其 libm 质量差，且不是生产工具链） |
| CMake | **未安装** | P0 必装 |
| Docker | CLI 有，**守护进程未运行** | 启动后可在本地构建 Linux 产物（省去等 CI） |
| WSL | 枚举被拒（E_ACCESSDENIED） | 不作为依赖 |

---

## 2. mod 兼容架构

### 2.1 核心原则：一条代码路径只有一个执行者

我们和优化 mod 争夺的是同一批热点方法。启动时对每个热点方法决定**归属**：native / 其它 mod / 原版。每个决定都写进日志与兼容性报告，绝不静默。

### 2.2 注入方式纪律（**这决定能不能共存**）

| 方式 | 用不用 | 理由 |
| --- | --- | --- |
| @Inject(at = HEAD, cancellable = true) | **主力方案** | 两个 mod 同时 inject 不会崩；我们提前 return 就完成"接管"，对方代码自然不执行 |
| MixinExtras @WrapOperation / @ModifyExpressionValue | 需要改表达式时用 | Fabric Loader 自带，可叠加；比 @Redirect 安全一个数量级 |
| @Redirect | **禁用** | redirect 之间是硬冲突：两个 mod redirect 同一处会直接崩在类加载阶段 |
| @Overwrite | **禁用** | 与其它 mod 的 overwrite 冲突，且会静默丢掉别人的修改 |

结论：**只要双方都遵守"用 @Inject 不做 @Overwrite"的惯例，Mixin 层面的硬冲突基本可以消除**；剩下的都是语义层面的问题，可以用 2.3 的策略处理。

### 2.3 语义基准问题（这一步必须先想清楚）

> **【已决定】采用策略 B（模组优先）。** 理由：该整合包已在生产环境长期运行、大型机器上未出现红石问题，所以基准是**当前整合包的行为**，不是纯原版。由此产生三条硬性后果：
> 1. **「行为不变」的目标对象 = 你现在的服务器**。差分测试的参考实现就是「装 Cava 之前的那套整合包」，黄金轨迹直接从那台服务器采。
> 2. **每个重叠点必须显式二选一**：**让位**（对方继续跑，我们零加速）或**复刻**（我们在 C++ 里实现对方的语义）。复刻的实现依据是对方 mod 的开源代码 + 实测黄金轨迹。
> 3. **不能再拿「原版逐位一致」当验收语言**。验收标准改成：**与「同一套整合包、native 关闭」逐 tick 一致**。
> 另需注意许可证：Lithium 是 **LGPL-3.0-only**，逐行移植其算法会产生衍生作品义务；Carpet / VMP / ServerCore 是 MIT，移植宽松。对 Lithium 建议优先考虑「让位」或按行为重写而非照抄。

装了 Lithium 或 ServerCore 之后，"不装 Cava 时的行为"已经不是纯原版了。而你的要求是"照原版实现"。于是：

- 我们的原生路径一旦接管，行为回到**原版语义** → 与"同一套 mod 但不装 Cava"可能有细微差异；
- 我们如果让位，就没有加速。

三种策略：

| 策略 | 行为 | 适用场景 |
| --- | --- | --- |
| **A. 原版优先（建议默认）** | 原生路径始终实现原版语义；检测到"会改变行为"的 mod 时，对应子系统让位 | 与你的要求一致 |
| B. mod 优先 | 只要有重叠补丁就让位，追求"零行为变化" | 不想承担任何行为差异风险的服务器 |
| C. 语义复刻 | 对文档完备的 mod 显式复刻其语义 | 只有明确需要时才做（成本高、维护负担重） |

配置粒度：按子系统（寻路/实体/红石）× 按 mod 覆盖。

### 2.4 镜像同步的稳健性（对其它 mod 的防御）

其它 mod 会改区块与方块写入路径（C2ME 让区块在线程间迁移、Lithium 优化 ChunkSection、FerriteCore 改方块状态缓存）。三层防御：

1. **主同步点**：区块加载/卸载事件（进入 / 退出镜像）；
2. **增量点**：LevelChunk.setBlockState 这个漏斗（主线程方块写入的必经之路）；
3. **兜底自愈**：每 N tick 抽样若干区段，比对"镜像"与"真实世界"的哈希；不一致就整段重建 + 告警 + 计数。
   这条兜底是防"别的 mod 悄悄绕过我们 hook"的唯一可靠手段，必须有。

另外：世界生成阶段（ProtoChunk）不 hook，等区块转正时整段重建镜像。

### 2.5 运行时兼容性报告（对服务器管理员价值极高）

启动时打印一张表：检测到的相关 mod 与版本 → 与我们的重叠点 → 每个重叠点的最终归属 → 被禁用的子系统及原因。出问题时这一张表能省掉几小时排查。

### 2.6 配置草案

    config/cava.json
    {
      "native": { "enabled": true, "safeMode": false },
      "subsystems": {
        "pathfinding": { "enabled": true, "ownership": "auto" },
        "entity":      { "enabled": true, "ownership": "auto" },
        "redstone":    { "enabled": true, "ownership": "auto" }
      },
      "modPolicy": {
        "lithium":    "native-first",
        "servercore": "native-first",
        "alternate_current": "defer",
        "c2me":       "native-first"
      },
      "safety": { "watchdogMs": 50, "autoCircuitBreaker": 5, "mirrorAuditInterval": 600 }
    }

### 2.7 验证矩阵

parity 测试必须按 mod 组合跑，而不是只跑原版：

| 组合 | 目的 |
| --- | --- |
| ∅（纯原版） | 基准语义 |
| + Lithium | 最可能重叠的通用优化 |
| + ServerCore | 刷怪/区块 tick 重叠 |
| + C2ME | 线程模型与镜像同步 |
| + 全套 | 真实服务器形态 |

每套组合都跑 native on/off 对比。CI 上单元层跑全组合，场景层至少跑 ∅ 与 +Lithium 两档。

---

## 3. 逐 mod 兼容矩阵（源码级核实，3/4 路已完成）

核实方式：逐 mod 抓取源码与官方 jar 的 refmap，再用 Yarn 1.20.4 映射核对 intermediary 名——结论是源码证据，不是文档推测。
- 逐 mod 详细审计：docs/CAVA-mod-audit-servercore-vmp-krypton-ferritecore-modernfix-packetfixer.md（552 个 mixin 源文件为证据）
- 注入点汇总与策略：docs/CAVA-hook-points.md
- Lithium 详细报告在本地研究缓存 .research/lithium-1.20.4-report.md（已 gitignore）

### 3.1 总览矩阵

| mod | 1.20.4 版本 | 寻路 | 实体 | 红石 | 区段/方块状态镜像 | 实体镜像 | 默认策略 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Lithium | 0.12.1（已冻结） | 重叠：LandPathNodeMaker 缓存短路（**不碰 PathNodeNavigator**） | **重叠：adjustMovementForCollisions @Overwrite** | 重叠：getReceivedRedstonePower cancellable HEAD | 相关：ChunkSection 计数、World/WorldChunk.getBlockState @Overwrite、调色板替换 | 无 | 寻路无需处理；实体与红石用 lithium:options 关掉对方重叠组 |
| ServerCore | 1.5.0+1.20.4 | **重叠且不可配置**：PathNodeNavigator @Redirect×4 + @ModifyVariable×2 | 重叠：Entity.push 同点 HEAD cancellable；move 在 INVOKE 点 | 无 | 无 | 有：激活范围会整 tick 跳过 Entity.tick() | 寻路直接接管（对方补丁自然跳过）；实体需复刻其短路或让位 |
| VMP | 0.2.0+beta.7.139 | 无 | 重叠：Entity.move HEAD cancellable（无开关） | 无 | 有：PalettedContainer 去锁（恒生效） | 有：会写 velocityDirty | 原生 move 保留等价的零位移短路 |
| Krypton | 0.2.6 | 无 | 无 | 无 | 无 | 无 | 无冲突 |
| FerriteCore | 6.0.3 | 无 | 无 | 无 | **强相关：方块状态去重（共享实例）** | 无 | 建表改用 state id 做键，禁用对象身份 |
| ModernFix | 5.17.0 | 无 | 无 | 无 | 相关：PalettedContainer.data 被替换、getAllReferences @Overwrite | 无 | 不长期缓存 data 引用 |

> 注：**你的服务器未安装 ModernFix**（安装目录下无此 jar）。保留在本矩阵是为了将来有人装上时直接可查。
| Packet Fixer | **1.4.1**（注意：与审计时的 3.3.2 完全不同） | 无 | 无 | 无 | 无 | 无 | 只有 9 条网络栈 mixin（以 @ModifyConstant 为主、priority=9999，另 2 处 @Overwrite 在 Varint21FrameDecoder），**没有** NbtAccounter/Connection/ServerConnectionListener 那些 |
| **Alternate Current** | mc1.20-1.9.0 | 无 | 无 | **真冲突：在 updatePowerStrength（=Yarn update）上 HEAD+cancel 事实接管** | 自建 InstantNeighborUpdater，绕开 ChainRestrictedNeighborUpdater | 无 | 红石二选一；它没有关闭机制 → 我们让位 |
| **Carpet** | 1.4.128 | 只 @Redirect 调用方 createPath，不碰 class_13 | LivingEntity pushEntities HEAD+cancel（默认关） | fastRedstoneDust 与 AC 同点（**默认 false**） | 仅 accessor | 无 | 默认配置下低风险；建议作为差分测试夹具 |
| **Carpet Extra** | 1.4.128 | 无 | 无 | 只碰红石外围（比较器/中继器/活塞），**默认全关** | 无 | 无 | 低风险 |
| C2ME | 0.2.0+alpha.11.72 | 无 | 无 | 无 | **强相关：线程与调度被重写** | 无 | 事件驱动镜像 + 主线程队列 |
| Chunky | 1.3.146 | 无 | 无 | 无 | 相关：区块抖动放大器 | 无 | 不做每 tick 全量重建 |
| Radium / Canary | 1.20.4 不存在（仅 Forge/NeoForge） | — | — | — | — | — | 不适用 |

### 3.2 三条最重要的结论

1. **真正需要处理的冲突只有三处**：Lithium 的 Entity.adjustMovementForCollisions（@Overwrite）、Lithium 的 RedstoneWireBlock.getReceivedRedstonePower（cancellable HEAD）、ServerCore 的 Entity.push（与我们同点的 cancellable HEAD）。前两处可以用 Lithium 官方的 lithium:options 机制关掉它对应的 mixin 组；第三处需要读它的公开接口复刻短路，否则让位。
2. **红石要分成两类看（我上一轮的说法需要修正）**：通用优化 mod（ServerCore / VMP / Krypton / FerriteCore / ModernFix / Packet Fixer / C2ME / Noisium）对 NeighborUpdater / ChainRestrictedNeighborUpdater / AbstractRedstoneGateBlock / RepeaterBlock / ObserverBlock / PistonBlock 的命中数是 **0**；**但红石专用 mod 三家全部抢同一个方法**——Alternate Current 与 Carpet 的 fastRedstoneDust 在 updatePowerStrength（Yarn: update）上 @Inject(HEAD, cancellable)，Lithium 在 calculateTargetStrength（Yarn: getReceivedRedstonePower）上同点接管。

> 版本校准（分片 A，jar 级逐 class SHA256）：**安装的 Noisium 2.2.2 与审计用的 2.3.0 除 fabric.mod.json 与一个 architectury 注入类名外逐字节相同**，旧结论直接适用，不必重查。
   所以红石**只能二选一**：检测到任何一家启用对应功能，P3 原生路径必须显式让位（Lithium 可用 lithium:options 关掉，AC 与 Carpet 没有这种机制）。另外 AC 会自建 InstantNeighborUpdater、绕开 ChainRestrictedNeighborUpdater，挂在邻居更新队列上的钩子在它面前会漏更新。
3. **没有任何 mod @Overwrite 我们的三类目标方法**（除上述 Lithium 那一处）。因此「我们绝不 @Overwrite」这条自我约束不产生对抗，反而让我们免疫了绝大多数潜在冲突。
4. **镜像钩子只能观察，不能取消。** Starlight 已在 LevelChunk / ProtoChunk.setBlockState 内有 @Redirect；我们的增量同步点必须用不 cancellable 的 @Inject 观察，绝不能在同点 @Redirect 或取消。

### 3.3 由兼容性反推的设计修订（已写进 docs/CAVA-hook-points.md）

- 镜像主钩子改挂 **ChunkSection.setBlockState（method_12256）/ WorldChunk.setBlockState（method_12010）**；**禁止**挂 World.getBlockState / WorldChunk.getBlockState（被 Lithium @Overwrite，我们的注入会静默失效）。
- 方块状态表用 **state id** 做键，禁用对象身份（FerriteCore 去重会让不同状态的形状共享同一实例）。
- 不长期缓存 PalettedContainer.data（ModernFix）、**不依赖 PalettedContainer 的 lock/unlock**（Lithium 的 chunk.no_locking 与 VMP 都把它们 @Overwrite 成 no-op）、不缓存"本 tick 区块集合"（C2ME）、不做每 tick 全量重建（Chunky）。
- 线程池参数改为**运行期取值**：Util.getMainWorkerExecutor() / getIoWorkerExecutor()（ThreadTweak 会把这两个池整体换掉）。
- **不在别人已经用掉的调用点做 @Redirect**：Noisium 在 NoiseChunkGenerator.populateNoise 里 redirect 了 ChunkSection.setBlockState 的调用点；同点双 @Redirect 是硬冲突。
- **不自建光照镜像**：Starlight 把多个光照引擎方法 @Overwrite 成空实现，并额外持 region ticket 保活区块。
- 新增 **钩子金丝雀自检**：启动完成后主动触发每个目标方法一次，确认我们的计数器 +1；没触发就禁用该子系统并大声报错。这是防"注入静默失效"的唯一可靠手段。
- 所有注入 **require=0**；priority 默认 1000 并逐点决策，需要独占时直接关对方那个 mixin 组，不打优先级战争。

### 3.4 一个具体例子：VMP 的零位移短路（A/B 策略的分水岭）

VMP 的 entity.move_zero_velocity.MixinEntity 对 Entity.move（method_5784）做了 @Inject(HEAD, cancellable=true)：位移为零且包围盒未变时直接 cancel。**这不是等价优化**——原版对零位移仍会走碰撞解算与 onGround 更新。而且它**不属于任何配置项，无法用 VMP 的 custom.vmp:incompatibleConfig 机制关闭**。

于是我们的 Entity.move 接管会碰上"谁先返回谁说了算"：Mixin 是低优先级先应用、先应用者先执行回调，而 VMP 的 priority 是 1050。所以：

- 我们 priority > 1050 → VMP 先短路 → 装 VMP 时行为跟 VMP 走（策略 B）；
- 我们 priority < 1050 → 我们先进原生路径 → 行为回到原版（策略 A）。

这就是"语义基准 A/B"最真实的分水岭，**必须逐点决策，不能全局一刀切**。建议默认 A（priority 1000，早于 VMP 1050 与 C2ME 1100），并在兼容性报告里明确写出"我们覆盖了 VMP 的哪条短路"。

### 3.5 不构成冲突、但会影响设计的 mod

| mod | 1.20.4 情况 | 对我们的影响 |
| --- | --- | --- |
| Noisium 2.3.0（仓库已归档） | 只优化世界生成写入；@Redirect 打在 NoiseChunkGenerator.populateNoise 内对 ChunkSection.setBlockState 的**调用点** | 我们挂方法本身、不挂调用点，因此不冲突；worldgen 写入不经活体镜像，与"只在区块转正时同步"的设计一致 |
| Starlight 1.1.3（已归档） | 光照重写，**没有**把光照搬离主线程；但在 LevelChunk.setBlockState 内有调用点 @Redirect（ChunkSkyLightSources.update 恒返 false） | 我们的 setBlockState 注入不受影响；不要自建光照镜像；它持有 region ticket 保活区块，卸载时机与直觉不同。附注：其自身文档承认 1.20 原版光照已基本照抄 Starlight，1.20.4 上光照收益很小 |
| ThreadTweak 1.20.4-0.1.2 | 整体替换 Util.MAIN_WORKER_EXECUTOR / IO_WORKER_EXECUTOR | 我们对线程池的任何假设都要改成运行期取值 |
| ModernFix 5.17.0（paper_chunk_patches 默认开） | 用 legacy 调度重写 chunk status 调度；检测到 C2ME 会自动禁用，**但它不认识 Cava** | 兼容性报告里要提示可关 mixin.bugfix.paper_chunk_patches，或原生路径让位 |
| Phosphor / ScalableLux / Moonrise / Smoothchunk / DimensionalThreading | **1.20.4 全部不可用**（最高 1.19.x / 无发布 / 仅 1.21+ / 闭源无对应版本） | 当前无冲突，记录备将来升级 |

两条记录在案的事实：
- DimensionalThreading（仅到 1.19）把 RedstoneWireBlock.wiresGivePower（field_11438）从 static 全局 flag 换成 ThreadLocal——说明这个字段是全局可变、线程不安全的。1.20.4 上它仍是 static；只要我们不做多线程红石就不成问题（C2ME 已声明与它不兼容）。
- **mixin 优先级地形**：C2ME 1100、VMP 1050、Lithium 局部 990/1005/1100。我们的钩子必须显式设 priority 并避开这些热点方法。

### 3.6 仍未完成 / 未验证

- **四路调研全部完成**（Lithium；ServerCore/VMP/Krypton/FerriteCore/ModernFix/Packet Fixer；C2ME/Chunky/光照/线程模型；红石与 AI 类）。矩阵与结论已覆盖 1.20.4 Fabric 服务端生态中所有已知相关的 mod。
- 明确**不适用于 1.20.4 Fabric** 的：Radium（无 1.20.4 版本）、Canary（仅 Forge）、AI-Improvements（仅 NeoForge，且源码 0 个 @Mixin）、MoreAI（未找到）、Phosphor（≤1.19.4）、ScalableLux（无发布）、Moonrise（1.21+）、Smoothchunk（无 1.20.4）、DimensionalThreading（仅 1.19），以及一批 client-only 的（EntityCulling / MoreCulling / Exordium / BadOptimizations）。
- 未验证清单：Krypton 0.2.6 在 1.20.4 的实机可用性；ModernFix 配置键的精确拼写；VMP 与 ServerCore 激活范围同时存在时的实测交互；C2ME 的 convertToFullChunk 之后下游回调是否仍保证主线程；VMP 零位移短路的逐位差异（需实测）；通配 method="*" 的 @Overwrite 是否漏计。
- 环境备注：本机 GitHub/Modrinth 被 DNS 解析到 198.18.x.x 假 IP，web_fetch 不可用；本轮调研改走 Node.js 直连抓取，证据来自真实响应。GitHub API 限流 60 次/小时。

### 3.7 关键结论的独立复核（我本人逐行读过源码，不是转述）

四份承重结论已在本地缓存的 Lithium 1.20.4 源码里亲自复核：

| 结论 | 证据（文件:行） | 复核结果 |
| --- | --- | --- |
| 别的 mod 可用 lithium:options 关掉 Lithium 的 mixin 组 | common/config/LithiumConfig.java:186-208 | **成立**。applyModOverrides() 遍历 FabricLoader.getAllMods() 的全部 mod 元数据，读 custom 值 "lithium:options"；非 OBJECT 只 warn 忽略；键不以 "mixin." 开头时由 getMixinRuleName 自动补前缀（两种写法都能用） |
| Lithium 寻路 mixin 的 priority 是 990 | mixin/ai/pathing/LandPathNodeMakerMixin.java:22-25 | **成立**，且作者原注释写明：priority 必须 < 1000，因为 Fabric API 用 1000，要抢在它之前注入 |
| Lithium 用 @Overwrite 整体替换 Entity.adjustMovementForCollisions | mixin/entity/collisions/movement/EntityMixin.java:49-52（另加 require=5 的 @Redirect 在 21-43 行） | **成立** |
| Lithium 用 @Inject(HEAD, cancellable) 整体替换红石线取电 | mixin/block/redstone_wire/RedstoneWireBlockMixin.java:67-76（cir.setReturnValue 直接返回自家实现） | **成立** |

**复核过程中捞到两条比我原判断更有价值的作者自述：**

1. **Lithium 自己承认它的碰撞重写没验证过与原版等价。** EntityMixin.java 第 55 行原文注释：
   "//vanilla order: entities, worldborder, blocks. It is unknown whether changing this order changes the result regarding the confusing 1e-7 VoxelShape margin behavior. Not yet investigated"
   → 连最成熟的优化 mod 都不敢断言这里逐位等价。这正好支持我们的策略选择：**开启原生碰撞时用 lithium:options 关掉它的 mixin.entity.collisions.movement**，而不是试图在它之上再叠一层。
2. **红石的"可观测性"有权威背书。** RedstoneWireBlockMixin.java 第 18-52 行的类注释写明：移除冗余的方块更新"用一个活板门上的红石线就能检测到"，移除递归更新"用依赖特定方块更新顺序的定位装置就能检测到"，所以 Lithium **只优化电力计算部分**。这与我们 P3 的方案 A（只搬线网重算，不动更新队列）完全一致，同时说明方案 B 确实是高风险区。
   同处注释还给了一个期望值：仅电力计算这一项在红石密集场景可带来**最多 30% 的 MSPT 下降**（Lithium 自己的数字）——收益是真的，但仅限红石密集服。
## 4. 需要你确认的点

1. **语义基准策略**默认取 A（原版优先，改变行为的 mod 让位）还是 B（有重叠就让位，追求零行为变化）？
2. **arm64 是否都要原生支持**？（Linux arm64 + macOS arm64 会多 2–3 个构建与测试目标；如果只在 x64 上跑，能省下不少工作量）
3. 是否接受把 **MixinExtras** 作为依赖？（Fabric Loader 已自带，基本零成本）
4. **你实际在用的 mod 列表**是什么？按你的真实清单做兼容矩阵，远比泛泛覆盖所有主流 mod 有效——尤其是红石类 mod，它们直接改变语义，必须逐个确认。
