# Cava P1-FIX：寻路原生接管两个正确性缺陷的修复与实测（含"能不能默认打开"的裁决依据）

> 作者：P1-FIX 流（响应 `docs/CAVA-pathfind-perf.md` §6.2 / §6.3 实测抓到的两个行为差异）。
> 本文件**只记录实跑**。每条结论都带命令或回执原文；没跑到的写在 §9。
> 落盘：2026-09-24。私有测试服：`testbed\perf-fix`（端口 **25680/25681**，从 perf-pathfind 复制 + 每次从
> `testbed\parity\snapshots\parity-base` 恢复世界）。**绝不使用** `testbed\gate-preview`。

---

## 0. 结论速览

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| 缺陷 1：镜像复用没有失效源（mob 穿墙） | **已修**：失效源接上契约指定的主钩子 `ChunkSection.setBlockState(method_12256)`；复用同时收紧为"**同 tick** + 无失效事件"双条件（跨 tick 复用改为显式可选 + 金丝雀计数） | §2、§5.3、§7 |
| 缺陷 2：窗口（包围盒+4）截断最优路径 | **已修**：新增 `WindowTruncationGuard` 保守检测，触发即**整体回退 Java**，并按判据分别计数；**不扩窗口**（那是 captain 的策略） | §3、§5.2、§6、§7 |
| 顺带发现的第三个缺陷：`Path.reachesTarget` 被填反 | **已修**（Java 侧 1 处 + 比对脚本键名笔误 1 处）。P1-PERF 的"逐字段一致"在这条上**曾经是假的**：比对脚本把键名写成 `reachesTargetFlag`，而回执里是 `reachedTargetFlag`，于是这个字段**从来没被比过** | §4 |
| detour128 在 reuse/repush 两种 mode 下 native on/off 是否逐字段一致 | **一致**（12 个 preset/mode 组合全部一致，`COMPARE EXIT=0`），`collisionNodes=0` | §5.2 |
| 其余性能场景是否退化 | 长/大场景**没有退化**（long128 2.16x、maze41 2.16x、maze63 2.58x、slalom 1.68x，接管率 100%）；detour128 因为回退**变慢**（≈0.74x）；1 节点的 `long128hash` 退化（0.64x） | §6 |
| `cava.pathfind.native` 能不能默认打开 | **不能**（本轮判据不满足）。真实 AI 负载两次独立实测**回退率 47.8% / 51.8%**，打开等于"一半调用白付一次原生 + 再跑一遍 Java" | §8 |

---

## 1. 产物与哈希（一切结论都挂在这两行上）

~~~
DLL: J:\mc\Cava\natives\windows-x64\cava.dll
     257536 bytes  sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D
     （**未改动**：本轮一行 native 都没碰；主腿的 #dllStable 都是 True，收尾复核哈希相同、mtime 未变）
JAR: build\libs\cava-0.1.0.jar
     565610 bytes  sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B
     （§5 的全部腿都用这一个 jar；部署到 testbed\perf-fix\server\mods 后 #deployed == #jar）
~~~

~~~
$env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; .\gradlew.bat build --no-daemon --console=plain --no-watch-fs
BUILD SUCCESSFUL in 19s
JAR: 565610 bytes sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B
~~~

> **gradle 的一个环境坑（不是代码问题）**：不带 `--no-daemon` 时，本机那个**别的会话留下的常驻 daemon
> （pid 3060，Gradle 9.7.1）**跑测试会让 19 个 `@TempDir` 用例报
> `ExtensionConfigurationException: Failed to create default temp directory` /
> `AccessDeniedException: C:\Users\郁小悟~1\AppData\Local\Temp\dsh-6WX2Ar\junit-...`。
> 同一个 `test` 任务加 `--no-daemon`（从本会话起新 daemon）**全绿**。见 §9 第 1 条。

全量测试的实跑口径（**强制重跑**，不是 UP-TO-DATE）：

~~~
$env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; .\gradlew.bat test --rerun-tasks --no-daemon --console=plain --no-watch-fs
BUILD SUCCESSFUL in 16s / 7 actionable tasks: 7 executed
build\test-results\test\*.xml 汇总：suites=45 tests=257 failures=0 errors=0 skipped=9
本轮新增/改动的 5 个测试类都真的跑了：SectionOriginRegistryTest 6 / WindowTruncationGuardTest 9 /
RegionMirrorTest 20 / NativeNodeCodecTest 3 / RegionRectTest 6（failures 全 0）
~~~

---

## 2. 缺陷 1：镜像复用没有失效源 —— 修法与两个必须记住的事实

### 2.1 事实（复核过，不是推测）

- 复用判据原本是"同矩形 + `lastTick != Long.MIN_VALUE`（无失效事件哨兵）"，而失效事件只有
  `RegionMirror.onBlockChanged / onSectionUnloaded / onWorldChanged` 三个入口；
  这三个方法在 `src/main/java` 的**生产路径里一次都没被调用**（只有 bench 诊断在调）。
- 契约（`docs/CAVA-hook-points.md` 第 17 行）指定的主钩子 `ChunkSection.setBlockState`
  在 18 个 mixin 文件里**不存在** ⇒ 地形怎么变原生都不知道。

### 2.2 修法 1：把复用收紧成"同 tick + 无失效事件"（构造性安全）

`RegionMirror.pushReusingSameTick` 现在要求 **`lastTick == r.currentTick()`** *并且* 没有失效事件。
理由写进了代码注释：1.20.4 的世界变更只在服务端主线程、发生在 tick 内 ⇒ "同 tick 复用"**原理上**
拿不到陈旧地形，这一条不需要任何失效源就成立；而"没有失效事件"这一半要失效源**完备**才成立。

> **把旧注释里那句"不要再把 `lastTick == currentTick` 加回来"改掉了**（那段理由只对了一半：
> "同 tick 会漏 tick 内变化"是真的，"所以只能靠失效源"是错的 —— 当时失效源根本不存在）。
> 现在两件事都要：同 tick 挡跨 tick 陈旧，失效钩子挡 tick 内改动。
> 旧代码抱怨的"复用永远不可能命中"也不是"按 tick 判定"造成的，而是另一处接线缺失。

跨 tick 复用改为**显式可选**：`-Dcava.mirror.reuse.crosstick=true`（默认 **false**），
并带金丝雀计数 `RegionMirror.crossTickReuseHits()` 与 `crossTickReuseBlocked()`
（后者 = "如果打开这个开关，能多省多少次推送"的上界）。**没有金丝雀证据不许默认打开。**

### 2.3 修法 2：接上主钩子 `ChunkSection.setBlockState(method_12256)`

新增 `src/main/java/cava/mixin/mirror/ChunkSectionSetBlockStateMixin.java`：

- `@Inject(method = "setBlockState(IIILnet/minecraft/block/BlockState;Z)Lnet/minecraft/block/BlockState;", at = HEAD, cancellable = false, require = 0)`，
  类级 `@Mixin(value = ChunkSection.class, priority = 1000)`（**显式 priority**）。
  **不 cancel、不 redirect、不 @Overwrite、不返回值**（镜像钩子只观察）。
- 说明：Mixin 0.8.7 的 `@Inject` **没有 priority 元素**（本机 javap 实测成员表：
  `id/method/target/slice/at/cancellable/locals/remap/require/expect/allow/constraints/order`），
  同点顺序的旋钮是 `order`（显式写 1000 = 默认值，语义是"不打优先级战争"）。

**映射核实（本机实测，不是记忆）**：

~~~
# Yarn tiny（.gradle-home/caches/fabric-loom/1.20.4/net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny）
c  dlp      net/minecraft/class_2826       net/minecraft/world/chunk/ChunkSection
  m  (IIILdjh;)Ldjh;    a  method_16675    setBlockState     ← 4 参重载
  m  (IIILdjh;Z)Ldjh;   a  method_12256    setBlockState     ← 5 参重载（我们钩这个）
# javap -p -c：4 参重载体**直接 invokevirtual 调 5 参重载**（iconst_1 传 lock=true）
#   ⇒ 只钩 5 参这一个点就覆盖两条入口
# 构建产物里（remapJar 之后）注解串已经是 intermediary：
   #28 = Utf8  method_12256(IIILnet/minecraft/class_2680;Z)Lnet/minecraft/class_2680;
   #8  = Utf8  Lnet/minecraft/class_2826;            ← @Mixin 的目标类
~~~

**同点占用（用本机安装的 mod jar 复核，不是只抄审计文档）**：

- `server-lithium-…-0.12.1.jar`：`mixin/chunk/no_locking/ChunkSectionMixin` 里有
  `method_12256` 与 `@Redirect`（常量池含 `Lorg/spongepowered/asm/mixin/injection/Redirect;`），
  `mixin/util/block_tracking/ChunkSectionMixin` 里有 `@Inject` + `@Redirect`；
  **两个类里都没有 `@Overwrite`**。
- `server-noisium-…jar`：refmap 里有
  `Lnet/minecraft/world/chunk/ChunkSection;setBlockState(IIILnet/minecraft/block/BlockState;Z)… -> Lnet/minecraft/class_2826;method_12256…`
  —— 它是**调用点**的 `@Redirect`，与本注入点无关。
- ⇒ 结论：本点已有 `@Inject`/`@Redirect`，但**没有 `@Overwrite`**，按
  `docs/CAVA-hook-points.md` 第 2 节的规则（`@Inject(HEAD)` 与非 `@Overwrite` 的补丁共存、
  且我们不 cancel 所以别人的补丁照常执行）**可安全共存**；`require=0` 保证万一以后映射/目标漂移
  也只是"不生效"，不会崩服。

### 2.4 一个必须记住的事实：`ChunkSection.setBlockState` **只有区段局部坐标**

`javap -p` 实测 `ChunkSection` 的字段只有
`nonEmptyBlockCount / randomTickableBlockCount / nonEmptyFluidCount / blockStateContainer / biomeContainer`
—— **没有任何位置字段** ⇒ 光靠这个钩子拿不到世界坐标（`(lx,ly,lz)` ∈ 0..15）。

解法：镜像推送时（`ServerWorldRegionReader.fill` 逐区段读世界）把"**区段实例 → 区段原点**"登记进
`SectionOriginRegistry` 的**身份表**（IdentityHashMap，绝不用 equals）；钩子用 `this` 做身份查表
拿到原点，再算世界坐标，**只有落在当前缓存矩形内**才调 `RegionMirror.onBlockChanged`。

**热路径纪律**（挂在线上的东西不能做重活）：
1. 一条 `volatile` 读：没有缓存区域直接返回（世界生成写 proto 区段走的就是这条路）；
2. 一次"上一次命中的区段"身份比较（世界生成/站点重建都是聚簇写同一区段，几乎总命中）；
3. 未命中才做一次 IdentityHashMap.get；
4. **空区段也必须登记**（现在是空气，但随时可能被写入 —— 漏登记就是漏失效）。

计数器（金丝雀）：`sectionHits / inWindowHits / outsideWindow / invalidations / disabledSkips / hookErrors`，
回执里以 `blockHook=hits:N,inWindow:N,invalidations:N` 出现。

### 2.5 失效的两个已知缺口（如实登记）

1. **区段卸载没有事件源**（`onSectionUnloaded` 仍然没人调）。危险度被"同 tick 复用"压住：
   卸载/重载不会发生在同一次推送所在的 tick 内。跨 tick 复用默认关，所以这条不是当前路径上的洞。
2. **推送失败后的登记**：一次失败的推送不会改掉原生里的内容（ABI 约定不部分写入），
   所以失败时**保留上一次的快照**（`abortWindow()`）。本轮改过一次：
   最初写的是 `clearWindow()`，会在"推送失败 + 同 tick 后续写入"时留下失效洞，已修。

---

## 3. 缺陷 2：窗口截断 —— 保守检测 + 回退（不扩窗口）

### 3.1 先核实原版语义（任务要求"用 javap 核实"，不照抄以为的语义）

`javap -p -c net.minecraft.entity.ai.pathing.PathNodeNavigator`（1.20.4 Yarn 映射 jar）实测：

- 预算：`int maxVisited = (int)((float)this.range * followRange)`（off 85-94），
  循环里 `iinc visited,1; if (visited >= maxVisited) break`（off 106-133）—— 与原生内核逐句一致；
- 抵达判定：对弹出节点 `current.getManhattanDistance(t) <= (float)reachRange` ⇒ `markReached`（off 162-190）；
- 返回：`found` 集合非空 → 取**最短**的那条（`comparingInt`）；为空 → 按
  `getManhattanDistanceFromTarget` 再按长度取最小（`comparingDouble`+`thenComparingInt`），
  两条路都走 `TargetPathNode.getNearestNode()`（`method_21660/21661` → `createPath(nearestNode,…)`）。
  **⇒ 原版"最近点"= 最近被接受节点的回溯链**，与内核实现一致；`reachRange` 的语义 = "弹出的节点距目标
  曼哈顿 ≤ 它就算抵达"。
- ⇒ 由此得到一条可直接用的判据：**末节点与目标曼哈顿距离 > reachRange ⟺ 原版走的是"没抵达"分支**。

### 3.2 为什么检测只能是启发式（写给 captain 的硬事实）

要**判定**"这次原生结果与全视野原版一致"，需要知道原生搜索的**访问集合**有没有落到窗口外。
**冻结 ABI 不导出这个信息**：`CavaPathNode.flags` 原生侧恒为 0（`cava_pf_abi.cpp` 注释
"CAVA_PATH_NODE_* 当前没有定义任何位"），内核算出来的 `SolveResult.expanded_count` 也没有出口。
所以：

- **能 sound 判定的**：路径贴到窗口边界面；目标的"抵达球"没完整落在窗口内。
- **只能启发式的**："原生没抵达"。它既可能是窗口截断，也可能是预算耗尽（后者原版也没抵达，
  例如 maze63 两腿实测逐字段一致）⇒ 一律回退会把**已经证明一致**的场景也拖回 Java
  （实测 maze63 接管 553µs vs 回退后 ≈ 原生 576µs + Java 1430µs）。

### 3.3 三条判据（分别计数，触发即整体回退 Java）

| 判据 | 触发条件 | 处置 |
| --- | --- | --- |
| `window-shell` | 返回路径**任一节点**落在窗口边界面（x/z 面；y 面只在"该边界不是世界高度"时判 —— 窗口 Y 会被 `getBottomY/getTopY` 裁剪，贴世界边界是合法的） | 回退 |
| `goal-shell` | `target ± reachRange` 没有完整落在窗口内（x/y/z 任一轴） | 回退 |
| `not-reached` | 末节点与目标曼哈顿距离 > `reachRange`：默认策略 `early-stop` 只在**路径步数 `rc-1` < 起点→目标欧氏直线距离 `d0`**（每一步至少走 1 格 ⇒ 步数是走过路程的下界）时回退；否则只计数 `not-reached-structural` | 回退 / 只计数 |

开关：`-Dcava.pathfind.window.guard=false`（总开关，**只给可证伪对照**）、
`-Dcava.pathfind.window.notReached=early-stop|always|off`（默认 `early-stop`）。
`always` 是最保守档（结构性最近点也回退），留给 captain 拍板。

### 3.4 `early-stop` 为什么能把 detour128 与 maze63 分开（实测数字）

| 场景 | 原生末节点 | 曼哈顿 | 路径步数 `rc-1` | `d0`（欧氏） | 判定 |
| --- | --- | --- | --- | --- | --- |
| detour128（真截断） | (95,71,0) | 65 | 63 | 128 | `63 < 128` ⇒ **回退** ✓ |
| maze63（预算耗尽、两腿本来就一致） | (83,71,-111) | 22 | 502 | 84.9 | `502 >= 84.9` ⇒ 只计数、**保持接管** ✓ |

**残余风险（假阴性）**：如果窗口截断发生在"搜索已经走过超过直线距离、但仍在窗口里绕"的场景
（大迷宫被切掉一角），本判据会漏检。要把它变成 sound，必须让原生导出"是否触边/展开规模"
（内核算了 `expanded_count`）—— **那是 captain 的 ABI 决策，不是本流的**。

---

## 4. 顺带抓到的第三个缺陷：`Path.reachesTarget` 被填反（以及比对脚本的键名笔误）

原代码 `NativeNodeCodec.reachesTargetFlag` 返回 `!(manh <= reachRange)`，注释说
"原版语义是反的（oracle spec 4.3.1）"。**实测把这个说法证伪了** —— 同一个 jar、native 关闭
（纯原版）的回执逐条：

~~~
long128      end=(159,71,0)   manh=1.000   reachedTargetFlag=true    ← 抵达
slalom       end=(160,71,31)  manh=1.000   reachedTargetFlag=true    ← 抵达
maze41       end=(135,71,134) manh=1.000   reachedTargetFlag=true    ← 抵达
maze63       end=(83,71,-111) manh=22.000  reachedTargetFlag=false   ← 未抵达（预算耗尽）
long128hash  end=(32,71,-32)  manh=128.000 reachedTargetFlag=true    ← **决定性**
~~~

最后一条是决定性的：`long128hash` 里起点/终点在 `PathNode.hash` 下同键，搜索在**第 1 个节点**上就
命中目标测试（perf 文档 §3.4 的复刻回执 `FOUND@pop1`），而它给的是 `true`
⇒ **`reachesTarget() == found`（true = 抵达）**；若按旧注释的"反语义"，maze63（未抵达）该是 `true`，
实测是 `false`，自相矛盾。

于是：

- `NativeNodeCodec.reachesTargetFlag` → 改名 `reachesTarget`，返回 `manh <= reachRange`（附实测证据）；
- `NativePathBuilder` 的注释改成直接语义；
- **比对脚本** `tools/parity-perf-pathfind.ps1` 一直把键名写成 `reachesTargetFlag`，而回执里是
  `reachedTargetFlag` ⇒ `Field()` 两边都返回 `(缺)`、**这个字段从来没被真的比过**
  —— 也就是说 P1-PERF 报告里的"逐字段一致"在这一条上是假的。本轮把键名修正，
  这个字段现在**真的在比**（见 §5.2 的比对输出）。

> `Path.reachesTarget` 的可观测性：`javap` 实测它被 `Path.toBuf`（**网络序列化**）、
> `Path.copy()`、以及任何读它的 mod 使用；`EntityNavigation`/`MobNavigation` 不直接读它
> （`equalsPath` 也不读）⇒ 不影响 AI 决策，但**是网络可见字段**，按验收语言必须一致。
> 另：原生内核内部 `out.reaches_target` 复刻的是"反语义"（`cava_pf_kernel.cpp` 899/924 行），
> 但该字段**没有出口**（不在 ABI 里）⇒ 当前不可观测；将来若要导出必须先修它。**本轮没改 native。**

---

## 5. 实跑证据

### 5.1 命令（每条腿都自己恢复世界、自己算产物哈希）

~~~
# 两条主腿（全 preset × 两种 mode）
pwsh -File tools/parity-perf-pathfind.ps1 -Leg off -Native off -Root J:\mc\Cava\testbed\perf-fix -ServerPort 25680 -RconPort 25681
pwsh -File tools/parity-perf-pathfind.ps1 -Leg on  -Native on  -Root J:\mc\Cava\testbed\perf-fix -ServerPort 25680 -RconPort 25681 -Invalidate
pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off -OnTag on

# 对照 A（缺陷 2）：把窗口截断检测关掉
pwsh -File tools/parity-perf-pathfind.ps1 -Leg off-ctl -Native off -Root ... -Presets long128,detour128,maze63 -Modes both -ForceN 800
pwsh -File tools/parity-perf-pathfind.ps1 -Leg on-guardoff -Native on -Root ... -Presets long128,detour128,maze63 -Modes both -ForceN 800 -JavaProp -Dcava.pathfind.window.guard=false
pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off-ctl -OnTag on-guardoff     # 必须红

# 对照 B（缺陷 1）：把"同 tick 限制 + 失效源"两半都拆掉 = 修复前的形态
pwsh -File tools/parity-perf-pathfind.ps1 -Leg on-prefixsim -Native on -Root ... -Presets long128,detour128,maze63 -Modes both -ForceN 800 -Invalidate -JavaProps '-Dcava.mirror.reuse.crosstick=true,-Dcava.mirror.invalidation=false'
pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off-ctl -OnTag on-prefixsim # 必须红

# 真实 AI 负载下的回退率（40 僵尸 + 1 村民 × 1200 tick）
pwsh -File tools/parity-perf-pathfind.ps1 -Leg aion -Native on -Root ... -AiLoad -SkipPerf -Presets long128
~~~

### 5.2 主腿一致性（`-Compare`）

~~~
================ P1-PERF 一致性比对 ================
  off = J:\mc\Cava\testbed\perf-fix\results\off.txt
  on  = J:\mc\Cava\testbed\perf-fix\results\on.txt

--- 产物哈希 ---
  off.txt: #dll=257536 bytes sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D | #jar=565610 bytes sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B | #deployed=565610 bytes sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B | #dllStable=True
  on.txt: #dll=257536 bytes sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D | #jar=565610 bytes sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B | #deployed=565610 bytes sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B | #dllStable=True

--- 每次调用耗时（ns/次）与节点数 ---
  preset/mode                  off ns          on ns  off nodes     off/on 回退(早停/结构)
  detour128/repush          1998983.0      2927111.5     128.00     0.683x 4300/4300/0
  detour128/reuse           2085874.6      2856944.7     128.00     0.730x 8300/8300/0
  long128/repush             482724.0       330147.1     128.00     1.462x 0/0/0
  long128/reuse              486247.0       224905.4     128.00     2.162x 0/0/0
  long128hash/repush          29891.1       179082.5       1.00     0.167x 1300/1300/0
  long128hash/reuse           33193.0        53410.6       1.00     0.621x 2300/2300/0
  maze41/repush              492322.4       444714.5     200.00     1.107x 0/0/0
  maze41/reuse               516996.6       225206.8     200.00     2.296x 0/0/0
  maze63/repush             1560835.5      1002160.6     503.00     1.557x 0/0/1800
  maze63/reuse              1493291.4       541119.7     503.00     2.760x 0/0/3300
  slalom/repush             8187475.0      5912352.2     138.00     1.385x 0/0/0
  slalom/reuse              8261683.9      4891502.4     138.00     1.689x 0/0/0

判定：**CONSISTENT**（全部 preset/mode 的 ok/nodes/终点/坐标哈希/type 哈希/穿墙计数 逐字段相同）
COMPARE-MAIN-EXIT=0
~~~

### 5.3 失效钩子实测（`cava pathfind invalidate <preset>`，缺陷 1 的直接反面证据）

命令做的事（全程**同一个 tick** ⇒ 复用只可能由"同 tick + 无失效事件"命中）：
A 先解一次（这一步把窗口推进原生）→ 记指纹 → 把 A 路径上第一个非 BLOCKED 节点改成石头
（走 `ServerWorld.setBlockState → WorldChunk.setBlockState → ChunkSection.setBlockState` = **主钩子**）
→ B 再解一次 → 再把原生接管临时关掉解一次得到 **Java 参考 C** → 还原方块。
判定 `PASS` 需要四条同时成立：`inWindowHits +1`、`invalidations +1`、`B != A`、`B == C`。

~~~
[cava/pathfind] INVALIDATE preset=long128 verdict=PASS nativeOn=true put=(33,71,0) a=len=128,end=(159,71,0),sig=0x7acb85719d26c525 b=len=128,end=(159,71,0),sig=0x0d00a7efd9ba1d46 c=len=128,end=(159,71,0),sig=0x0d00a7efd9ba1d46 hookHits=0->1(+1) invalidations=0->1(+1) mirrorInvalidations=0->1(+1) oldState=block.minecraft.air
[cava/pathfind] INVALIDATE preset=detour128 verdict=PASS nativeOn=true put=(33,71,0) a=len=128,end=(159,71,0),sig=0x5404370d9d311ede b=len=128,end=(159,71,0),sig=0xef8f362842e9c801 c=len=128,end=(159,71,0),sig=0xef8f362842e9c801 hookHits=245->246(+1) invalidations=245->246(+1) mirrorInvalidations=8545->8546(+1) oldState=block.minecraft.air
[cava/pathfind] INVALIDATE preset=slalom verdict=PASS nativeOn=true put=(33,71,-31) a=len=138,end=(160,71,31),sig=0x412310c09f1f0dfb b=len=138,end=(160,71,31),sig=0x5ad88d2081ace2c2 c=len=138,end=(160,71,31),sig=0x5ad88d2081ace2c2 hookHits=868->869(+1) invalidations=868->869(+1) mirrorInvalidations=13468->13469(+1) oldState=block.minecraft.air
[cava/pathfind] INVALIDATE preset=maze41 verdict=PASS nativeOn=true put=(98,71,97) a=len=200,end=(135,71,134),sig=0xbadc59bd92b5487b b=len=200,end=(135,71,134),sig=0x99300327c04fbe00 c=len=200,end=(135,71,134),sig=0x99300327c04fbe00 hookHits=2142->2143(+1) invalidations=2142->2143(+1) mirrorInvalidations=19042->19043(+1) oldState=block.minecraft.air
[cava/pathfind] INVALIDATE preset=maze63 verdict=PASS nativeOn=true put=(34,71,-159) a=len=503,end=(83,71,-111),sig=0x1da4c53d4f75f941 b=len=503,end=(83,71,-111),sig=0x6ba724156ed25098 c=len=503,end=(83,71,-111),sig=0x6ba724156ed25098 hookHits=12728->12729(+1) invalidations=12728->12729(+1) mirrorInvalidations=32928->32929(+1) oldState=block.minecraft.air
[cava/pathfind] INVALIDATE preset=long128hash verdict=SKIP(path-too-short:1) nativeOn=true put=(none) a=len=1,end=(32,71,-32),sig=0xa608076531bd8ad8 b=(none) c=(none) hookHits=37306->37306(+0) invalidations=37306->37306(+0) mirrorInvalidations=59306->59306(+0)
~~~

### 5.4 两条可证伪对照

~~~
================ P1-PERF 一致性比对 ================
  off = J:\mc\Cava\testbed\perf-fix\results\off-ctl.txt
  on  = J:\mc\Cava\testbed\perf-fix\results\on-guardoff.txt

--- 产物哈希 ---
  off-ctl.txt: #dll=257536 bytes sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D | #jar=565610 bytes sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B | #dllStable=True
  on-guardoff.txt: #dll=257536 bytes sha256=0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D | #jar=565610 bytes sha256=D4344E25B78C5806DE1BFD161CB0082FD818CCB1E3B4C82B36B739ACCA0E560B | #dllStable=True

--- 每次调用耗时（ns/次）与节点数 ---
  preset/mode                  off ns          on ns  off nodes     off/on 回退(早停/结构)
  detour128/repush          1988001.5       555788.8     128.00     3.577x 0/0/0
  detour128/reuse           2071478.0       425310.0     128.00     4.871x 0/0/0
  long128/repush             495537.9       287176.1     128.00     1.726x 0/0/0
  long128/reuse              534783.9       216035.8     128.00     2.475x 0/0/0
  maze63/repush             1426369.4       868784.6     503.00     1.642x 0/0/0
  maze63/reuse              1474236.5       469890.9     503.00     3.137x 0/0/0

判定：**DIVERGENT**（2 处）
  !! detour128/repush : nodes_avg(off=128.00 on=64.00) | nodes_p50(off=128 on=64) | nodes_min(off=128 on=64) | nodes_max(off=128 on=64) | end(off=(159,71,0) on=(95,71,0)) | manh(off=1.000 on=65.000) | reachedTargetFlag(off=true on=false) | sig_coords(off=0x5404370d9d311ede on=0x792a9f3088f39025) | sig_types(off=0x8b587018814f4325 on=0x3b329a200570b325) | analyzeLen(off=128 on=64) | endNode(off=(159,71,0) on=(95,71,0))
  !! detour128/reuse : nodes_avg(off=128.00 on=64.00) | ... 同上 ...
COMPARE-A-EXIT=2
~~~

~~~
================ P1-PERF 一致性比对 ================
  off = J:\mc\Cava\testbed\perf-fix\results\off-ctl.txt
  on  = J:\mc\Cava\testbed\perf-fix\results\on-prefixsim.txt

--- 每次调用耗时（ns/次）与节点数 ---
  preset/mode                  off ns          on ns  off nodes     off/on 回退(早停/结构)
  detour128/repush          1988001.5      2313974.9     128.00     0.859x 1100/1100/0
  detour128/reuse           2071478.0       200194.0     128.00    10.347x 0/0/0
  long128/repush             495537.9       306300.0     128.00     1.618x 0/0/0
  long128/reuse              534783.9       204915.9     128.00     2.610x 0/0/0
  maze63/repush             1426369.4       921824.8     503.00     1.547x 0/0/1100
  maze63/reuse              1474236.5       481517.8     503.00     3.062x 0/0/1100

--- 缺陷 1 失效钩子实测（-Invalidate 才有）---
  on-prefixsim.txt: [cava/pathfind] INVALIDATE preset=long128 verdict=RED(no-invalidation;stale-result(B==A);B!=javaRef;) nativeOn=true put=(33,71,0) hookHits=0->1(+1) invalidations=0->0(+0) mirrorInvalidations=0->0(+0)
  on-prefixsim.txt: [cava/pathfind] INVALIDATE preset=detour128 verdict=RED(no-invalidation;stale-result(B==A);B!=javaRef;) nativeOn=true hookHits=245->246(+1) invalidations=0->0(+0) mirrorInvalidations=1100->1100(+0)
  on-prefixsim.txt: [cava/pathfind] INVALIDATE preset=maze63 verdict=RED(no-invalidation;stale-result(B==A);B!=javaRef;) nativeOn=true hookHits=355->356(+1) invalidations=0->0(+0) mirrorInvalidations=2200->2200(+0)

判定：**DIVERGENT**（1 处）
  !! detour128/reuse : sig_coords(off=0x5404370d9d311ede on=0x7acb85719d26c525) | collisionNodes(off=0 on=8) | firstBadNode(off=(无) on=(96,71,0))
COMPARE-B-EXIT=2
~~~

### 5.5 真实 AI 负载上的回退率（40 僵尸 + 1 村民 × 1200 tick）

~~~
[perf] ai-load: 40 只僵尸 + 1 村民；sprint 1200 tick
[perf] ai-load stats0: [cava/pathfind] benchRuns=0 benchSeq=0 | canary=126 takeovers=69 nativeCalls=126 errors=0 disabled=false
  nativeCalls=126,takeovers=69,fallbacks=57 (45.24%),judge={window-shell=9 goal-shell=0 not-reached=0
  not-reached-early-stop=48 not-reached-structural=18}
  reasons={window-not-reached-early-stop=48 window-window-shell=9} | hook=true native=true probe=false
  bypassProfileGate=false probeTicks=600 windowGuard=on,notReached=early-stop
[perf] ai-load stats1: [cava/pathfind] benchRuns=0 benchSeq=0 | canary=390 takeovers=188 nativeCalls=390 errors=0 disabled=false
  nativeCalls=390,takeovers=188,fallbacks=202 (**51.79%**),judge={window-shell=16 goal-shell=0 not-reached=0
  not-reached-early-stop=186 not-reached-structural=57}
  reasons={window-not-reached-early-stop=186 window-window-shell=16} | hook=true native=true probe=false
  bypassProfileGate=false probeTicks=600 windowGuard=on,notReached=early-stop
[perf] ai-load 寻路调用 = 264 次 / 1200 tick ⇒ 0.2200 次/tick（canary 126 -> 390）

# 读法：整条 AI 腿累计 202/390 = 51.8% 回退；**纯 1200 tick 冲刺期间**是
# (202-57)/(390-126) = 145/264 = **54.9%**。第一次跑同一条腿是 193/404 = 47.8%
# （window-shell 13、early-stop 180、structural 49）⇒ 两次独立实测都落在 48–55%。
~~~

---

## 6. 修复前后数字对比（同一脚本、同一 preset/mode、同一产物链）

`off` = native 关闭（原版 Java 寻路）；`on` = native 打开（本轮修复后）。
"修复前 on" 一列取自 `docs/CAVA-pathfind-perf.md` §5 的实跑（jar sha256 `B4D67BEF…`）——
本轮的机器负载与那时不同，所以**跨轮绝对值只做量级参考**；同 jar 的严格对照见 §7。

| preset/mode | 节点 | off µs | on µs（修复后） | off/on | on µs（修复前, doc §5） | 接管率 | 回退(早停/结构/贴边/球) | collisionNodes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| long128/reuse | 128 | 486.2 | 224.9 | 2.16x | 266.8 | 100.0% | 0/0/0/0 | 0 |
| long128/repush | 128 | 482.7 | 330.1 | 1.46x | 396.8 | 100.0% | 0/0/0/0 | 0 |
| detour128/reuse | 128 | 2085.9 | 2856.9 | 0.73x | 272.3 | 0.0% | **8300**/0/0/0 | 0 |
| detour128/repush | 128 | 1999.0 | 2927.1 | 0.68x | 783.6 | 0.0% | **4300**/0/0/0 | 0 |
| slalom/reuse | 138 | 8261.7 | 4891.5 | 1.69x | 5507.9 | 100.0% | 0/0/0/0 | 0 |
| slalom/repush | 138 | 8187.5 | 5912.4 | 1.38x | 6637.2 | 100.0% | 0/0/0/0 | 0 |
| maze41/reuse | 200 | 517.0 | 225.2 | 2.30x | 273.5 | 100.0% | 0/0/0/0 | 0 |
| maze41/repush | 200 | 492.3 | 444.7 | 1.11x | 513.9 | 100.0% | 0/0/0/0 | 0 |
| maze63/reuse | 503 | 1493.3 | 541.1 | 2.76x | 636.7 | 100.0% | 0/**3300(结构性,不回退)**/0/0 | 0 |
| maze63/repush | 503 | 1560.8 | 1002.2 | 1.56x | 1180.4 | 100.0% | 0/**1800(结构性,不回退)**/0/0 | 0 |
| long128hash/reuse | 1 | 33.2 | 53.4 | 0.62x | 61.0 | 0.0% | 2300/0/0/0 | 0 |
| long128hash/repush | 1 | 29.9 | 179.1 | 0.17x | 187.4 | 0.0% | 1300/0/0/0 | 0 |

> 表里"回退"列 = `fallbacks`（**真的回退了 Java 的次数**），括号里是判据分布
> `早停/结构性最近点(只计数不回退)/贴窗口边/目标球越界`。`off µs` 与 `on µs` 都是本机同一轮实测
> （`ns_avg/1000`）。本机同时有别的流的 Gradle daemon/java 常驻（§1 的 daemon 坑），
> 所以**同一条腿内的相对比较**与**同一轮内 off/on 的比较**才可靠；两条腿都是紧接着跑的。

**读数**：

- long128 / slalom / maze41 / maze63 四种模式**接管率 100%、回退 0**，倍数 1.33x–2.58x，与前一轮同量级；
- **detour128 两腿都是 0% 接管、100% `not-reached-early-stop` 回退**：这是修好缺陷 2 的代价
  （原生调用照样付、然后再跑一遍 Java ⇒ ≈0.74x，比 native 关闭还慢）；
- maze63 的 `fb_structural` 计数 = 全量调用数（原生交回的是**结构性最近点**，两腿本来就逐字段一致）
  —— 这条计数就是"我们看见了、但按证据不回退"的凭据；
- long128hash（故意构造的 `PathNode.hash` 同键非法场景，`ok=false`）100% 回退 ——
  一个 1 节点、`manh=128` 的"最接近点"必然落进 `early-stop`。**这类短程最近点回退在真实 AI 里很常见**，
  见 §8 的 ≈50%。

---

## 7. 两条可证伪对照（改坏必须变红）

| 对照 | 做了什么 | 结果 |
| --- | --- | --- |
| **A：缺陷 2** | 只把总开关关掉：`-Dcava.pathfind.window.guard=false`（其余与主腿完全一样） | `-Compare` **DIVERGENT，EXIT=2**：detour128 两种 mode 都变回 `nodes=64 / end=(95,71,0) / manh=65`（与 perf 文档 §6.2 的原始症状逐字一致） |
| **B：缺陷 1** | 把"同 tick 限制 + 失效源"两半都拆掉：`-Dcava.mirror.reuse.crosstick=true` + `-Dcava.mirror.invalidation=false`（= 修复前形态） | `-Compare` **DIVERGENT，EXIT=2**：`detour128/reuse: collisionNodes(off=0 on=8) | firstBadNode(off=(无) on=(96,71,0))`（与 §6.3 的原始症状逐字一致）；同一条腿的 `-Invalidate` 三条回执全是 `RED(no-invalidation;stale-result(B==A);B!=javaRef;)` |

> 对照 B 里 `detour128/reuse` 的 on 腿 `ns_avg` 反而**快 8.9 倍** —— 因为它是**穿墙抄近路**。
> 这正是"性能数字漂亮但答案是错的"那种事故，值得记住。

---

## 8. 裁决：`cava.pathfind.native` 修完之后**能不能默认打开**？

### 8.1 结论：**不能**（本轮判据不满足）

### 8.2 判据（每一条都有实跑数字，不是感觉）

| # | 判据 | 门槛 | 本轮实测 | 判定 |
| --- | --- | --- | --- | --- |
| 1 | 合成性能场景逐字段一致 | 12/12 | 12/12 `CONSISTENT`（EXIT=0），detour128 两种 mode 都含在内 | ✅ |
| 2 | **真实 AI 负载的回退率** | 回退 = 白付一次原生 + 再跑 Java ⇒ 回退率高就等于没收益（且每 tick 更贵） | 两次独立实测：**51.8%（202/390）** 与 **47.8%（193/404）**；纯 1200 tick 冲刺期间 54.9%（145/264）。判据分布（第二次）：`early-stop=186`、`window-shell=16`、`structural=57`、`goal-shell=0` | ❌ |
| 3 | 真实负载上窗口不足是否会出现 | 若出现必须知道占比 | **出现**：`window-shell=16/390 ≈ 4.1%`（另一次 13/404 ≈ 3.2%；自然 AI 调用，不是人造几何） | ❌（说明窗口策略仍未闭合） |
| 4 | 短程调用的收益 | 短搜索原生本来就慢（perf 文档：1 节点 0.09x） | 本轮 `long128hash` 0.64x；真实 AI 调用的**规模分布未记录** | ❌（收益上界不明） |
| 5 | 失效源完备性 | 跨 tick 复用默认关时至少要有"同 tick"这条构造性保证 | 同 tick 保证在（单测钉死）；但**区段卸载仍无事件源**，跨 tick 复用默认关 | ⚠️ 有条件满足 |
| 6 | 全量测试 / 原生测试 | 全绿 | `gradlew build --no-daemon` 全绿；`ctest` 4/4 Passed；DLL 哈希未变 | ✅ |

### 8.3 打开之后还剩哪些**未验证**风险（诚实清单）

1. **真实 AI 的 ≈50% 回退里，有多少是"必须回退"（Java 会找到原生找不到的路）与多少是"白回退"
   没有区分**（能确定的只有：其中 `window-shell` 那一档 3–4% 是真的被窗口截断） —— 需要给回退路径加一次性诊断（Java 结果与原生结果逐字段比一次并计数），本轮没做。
2. **`early-stop` 是启发式**：大迷宫里"被窗口切掉一角、但搜索已经走过超过直线距离"的截断会**漏检**
   （要 sound 必须原生导出"是否触边/展开规模"，属 ABI 决策）。
3. **真实 AI 每次调用的搜索规模/路径长度分布**仍未记录（perf 文档 §8 第 2 条的缺口没变），
   所以"分流（只对大搜索接管）能拿回多少收益"没数。
4. **失效源的区段卸载缺口**（§2.5）未补；跨 tick 复用因此在真实服务器上**默认不可用**。
5. **钩子的线上开销**没有单独基准：设计上是"1 条 volatile 读 + 1 次身份比较"，未命中才查表；
   本轮的量级只在站点重建（数千次写入）里间接出现，没有专门测 ns/次。
6. **多生物同 tick 并发寻路**（perf 文档 §8 第 3 条）与**整服层 2000 实体 × 6000 tick 逐 tick 差分**
   （prompts/04 的整服层验收）**仍未做**。

### 8.4 建议的下一步（供 captain 拍板，不是本流的默认动作）

1. **分流**：只对"搜索规模 ≥ N 节点 / 路径预计超过窗口"的调用接管（需要先量真实规模分布）；
2. **窗口策略**：要么给"窗口不足"检测一个 sound 信号（ABI 导出 `expanded_count` 或"是否触边"），
   要么换窗口形状（更长、更窄、按目标方向偏移）—— **两者都是 captain 的决策**；
3. **回退率仪表盘**：把 `fallbackReport()`（nativeCalls/takeovers/fallbacks/判据分布）接进
   `/cava` 的常规输出，上线后能一眼看出收益还剩多少。

---

## 9. 没跑 / 做不到（不要当成已完成）

| # | 项 | 状态与原因 |
| --- | --- | --- |
| 1 | `gradlew test`（用常驻 daemon）全绿 | **做不到**：本机常驻 daemon（别的会话留下，pid 3060）跑 `@TempDir` 用例会 `AccessDeniedException`（19 个）。同一任务 `--no-daemon` 全绿。**这是环境问题，与代码无关**（失败用例是 CavaConfig/GoldenTrace/TraceDiff，都不碰本轮改的文件） |
| 2 | native `ctest` 的**重新构建** | **不许做**：本机原生构建的两个入口（根 `CMakeLists.txt` 第 8/79/92 行固定产物到 `natives/<tag>/`；`native/tests/build-mingw.ps1` 第 88 行显式写 `natives\windows-x64\cava.dll`）都会**覆盖冻结交付物**，而任务明令不许写 `natives/`。折中做法：用**已构建好的** MSVC 测试 exe 直接 `ctest`（不触发编译、不写 natives/）—— 4/4 Passed，且交付 DLL 哈希在跑完后复核不变 |
| 3 | 回退路径的"该不该回退"归因 | 未做（§8.3 第 1 条）：需要在回退分支里跑一次 Java 并把两边结果比一次并计数 |
| 4 | 真实 AI 的路径长度分布 / 分流收益 | 未做（§8.3 第 3 条） |
| 5 | 区段卸载失效源 | 未做（§2.5） |
| 6 | 整服层 2000 实体 × 6000 tick 逐 tick 差分 | 未做（prompts/04 的整服层验收，超出本轮范围） |
| 7 | 跨 tick 复用在**活 tick 服务器**上的收益/风险实测 | 未做。测试服的 `tick freeze` 会让 `currentTick()` 不变，"同 tick"与旧的"跨 tick"语义在冻结点上**无法区分**；单测覆盖了语义，活服务器上的金丝雀计数（`crossTickReuseBlocked`/`crossTickReuseHits`）**还没有出口能打出来**（建议接进 `/cava pathfind stats`） |

---

## 10. 需要 captain 拍板的问题

1. **窗口策略**（最重要）：是继续"包围盒+4 + 保守回退"，还是给原生一个 sound 的"窗口是否够"信号
   （ABI 导出 `expanded_count` / 触边标志）？后者要改冻结 ABI（14 结构体 / `layout_hash_sum=0x1C12265E`）。
2. **是否默认打开 `cava.pathfind.native`**：本流裁决 **不能**（§8），主因是真实 AI 回退率 ≈50%。
   若 captain 要开，建议先做**分流**并把回退率接进常规输出。
3. **回退率门槛**：上线判据要不要写成硬门禁（例如"真实负载回退率 ≤ 5% 才允许默认打开"）？
4. **跨 tick 复用**：要不要在补上"区段卸载"事件源之后打开（`cava.mirror.reuse.crosstick`）？
   本轮只提供了开关与金丝雀计数，默认关。
5. **`RegionSource` 接口建议签名**（本流**没有**改 captain 的接口文件）：
   若要让注入流拿到"窗口是否可信"的一等公民信号，建议新增只读账目方法而不是扩 record，例如
   `long pathfindWindowFallbacks()` / `String pathfindWindowDiagnostics()`（因为 Java record 不能增量扩字段）。
   另外建议把镜像账目接进 `/cava pathfind stats`（`RegionMirror.report()` 目前没有调用点）。

---

## 11. 产物清单（本轮改动）

| 文件 | 作用 |
| --- | --- |
| `src/main/java/cava/mixin/mirror/ChunkSectionSetBlockStateMixin.java` | 新增：镜像主钩子（@Inject HEAD / require=0 / 类级 priority=1000，不 cancel 不 redirect） |
| `src/main/java/cava/mirror/SectionOriginRegistry.java` | 新增：失效源（区段实例→原点身份表、O(1) 窗口内判定、金丝雀计数、失效源开关） |
| `src/main/java/cava/hook/WindowTruncationGuard.java` | 新增：窗口截断保守检测（三判据 + early-stop 策略 + 计数） |
| `src/main/java/cava/mirror/RegionMirror.java` | 复用改成"同 tick + 无失效事件"；跨 tick 复用显式可选 + 金丝雀；发布/放弃登记表 |
| `src/main/java/cava/mirror/RegionRect.java` | 加 `contains`（失效钩子热路径的 O(1) 判据） |
| `src/main/java/cava/mirror/ServerWorldRegionReader.java` | 推送时登记覆盖到的区段（含空区段） |
| `src/main/java/cava/hook/PathfindHook.java` | 接窗口截断检测 + 回退计数 + `fallbackReport()` |
| `src/main/java/cava/hook/PathfindSwitches.java` | 两个新开关（`window.guard` / `window.notReached`） |
| `src/main/java/cava/hook/NativeNodeCodec.java` / `NativePathBuilder.java` | `reachesTarget` 语义修正（实测证据写进注释） |
| `src/main/java/cava/hook/PathfindPerfBench.java` | 回执新增回退/判据/镜像/钩子字段；新增 `/cava pathfind invalidate <preset>` |
| `src/main/resources/cava.mixins.json` | 登记新 mixin |
| `src/test/java/cava/mirror/SectionOriginRegistryTest.java` / `cava/hook/WindowTruncationGuardTest.java` | 新增单测；`RegionMirrorTest`/`RegionRectTest`/`NativeNodeCodecTest` 按新语义重写 |
| `tools/parity-perf-pathfind.ps1` | 接管不变量改成 `takeovers+fallbacks==expectDelta`；`reachedTargetFlag` 键名勘误；`-JavaProps`/`-Invalidate`/`-SkipPerf` |
| `docs/CAVA-p1-fix-notes.md` | 本文件 |

**commit**：`2790772`（第一轮：失效源 + 窗口截断检测 + 回执/脚本扩展）与 `6365a0c`（第二轮：`Path.reachesTarget` 语义勘误 + 比对脚本键名勘误 + 推送失败保留登记）。两轮都用**同一个最终 jar**（`D4344E25…`）重跑了全部六条腿 —— 这一点很重要：本文所有回执与那个哈希一一对应。

## 12. 复现（复制即可）

~~~
# 0) 私有测试服（从 perf-pathfind 复制；世界由脚本每次从 parity-base 恢复）
robocopy J:\mc\Cava\testbed\perf-pathfind J:\mc\Cava\testbed\perf-fix /E /XD world results runs
# 1) 构建（GRADLE_USER_HOME 必须显式；**加 --no-daemon**，见 §9 第 1 条）
$env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; .\gradlew.bat build --no-daemon --console=plain --no-watch-fs
# 2) 六条腿（§5.1 的命令；端口 25680/25681，跑前 netstat 确认空闲）
# 3) 比对（红 = exit 2）
pwsh -File tools/parity-perf-pathfind.ps1 -Compare -OffTag off -OnTag on
~~~

---

## 附录 N【P1-NET 追加 2026-09-24】§8.3 第 3 条缺口已闭合：真实 AI 负载的净收益 = **−14.7 µs/tick（净亏）**

`docs/CAVA-p1-net-notes.md`（P1-NET 流）把本文件 §8.3 的三条缺口补上/推进了：

- §8.3 第 3 条（真实 AI 的规模分布）：**已测**。距离 p50=7 / p90=10 / max≈11–15 格（3D 切比雪夫）、
  返回路径 p50=7–8 / p90=12–14 节点、原版预算恒 **560** 节点；每次调用耗时 p50=62–113 µs、p90=761–1626 µs（长尾极重）。
- §8.3 第 1 条（回退该不该回退）：**部分闭合**，`-Dcava.pathfind.fallback.compare=true` 实测
  `fbSame=45/105 = 42.9%` 的回退是"Java 结果与原生结果逐节点完全相同"的**纯浪费**；
  成对对照（`compareAll`）进一步给出：接管调用上原生 157.7 µs vs Java 129.6 µs（**1.22x 慢**）。
- 结论：**开关仍然是 NO-GO**（R2 判据①②③ 全不满足），而且**按规模分流不能翻正**（逐距离桶全亏、回退率与规模无关）。
  本文件 §8.4 建议的第 1 条（分流）到此**已被数据否决**，第 2 条（窗口策略/ABI）才是唯一有希望的方向。
