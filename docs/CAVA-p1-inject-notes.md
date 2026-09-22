# Cava P1 寻路 · 注入流（P1-Java-B）交付说明与实测台账

> 作者：P1-Java-B 流。**结论全部有本机实测证据**，未跑过的一律标「未验证」。
> 语义权威：~~docs/CAVA-pathfind-oracle-spec.md~~；ABI 权威：~~native/include/cava_abi.h~~；
> 交接：~~docs/CAVA-p1-pathfind-notes.md~~；门禁：~~docs/CAVA-gates.md~~。

---

## 0. 一句话状态

**注入点、金丝雀、接管编排、返回值转换、开关与 bench 全部落盘并在真实服务端跑通。**
**本轮最重要的验收项已闭合：金丝雀在真实服务端里真的动了** ——
~~金丝雀 PASS：主动触发 findPathToAny 一次，计数 0 -> 1~~（日志见 §2.2）；
bench 里 20000 次真实调用 **canaryDelta=20000/20000**（一次不多、一次不少）。

**原生接管当前整条关闭**（by design，见 §4.1）：~~cava_pathfind~~ 保守返回
~~CAVA_ERR_UNIMPLEMENTED~~，且镜像流的 ~~isProfileReadyForSolve~~ 目前恒 false
（实测 ~~reasons={profile-not-ready=20201}~~）。
编排链路本身已被证实是通的（§2.5：~~nativeCalls=5201~~ 全部按 ~~native-unimplemented~~ 正确回退）。

---

## 1. 交付物

| 路径 | 内容 |
| --- | --- |
| ~~src/main/java/cava/mixin/pathfind/PathNodeNavigatorMixin.java~~ | **注入点**：~~findPathToAny~~ 两个重载（method_52 / method_54） |
| ~~src/main/java/cava/mixin/pathfind/PathNodeNavigatorAccessor.java~~ | ~~@Accessor~~：~~pathNodeMaker~~ / ~~range~~（只读，不改表达式） |
| ~~src/main/java/cava/mixin/pathfind/AmphibiousPathNodeMakerAccessor.java~~ | ~~@Accessor~~：~~penalizeDeepWater~~（**必须是 accessor，不能用反射**，见 §3.2） |
| ~~src/main/java/cava/mixin/pathfind/MinecraftServerBootstrapMixin.java~~ | **只做自举**：~~MinecraftServer.<init>~~ HEAD + ~~runServer~~ HEAD（见 §3.1） |
| ~~src/main/java/cava/hook/PathfindHook.java~~ | 接管编排（取输入 → 镜像 → 原生 → 转换 → 回退） |
| ~~src/main/java/cava/hook/PathfindMirrorBridge.java~~ | 到 ~~cava.mirror.RegionSource~~ 的窄桥（含按世界构造的 ~~forWorld~~） |
| ~~src/main/java/cava/hook/PathfindProfileBridge.java~~ | **档案归属桥**：优先镜像流，互斥退化（见 §4.2） |
| ~~src/main/java/cava/hook/MobInputs.java~~ / ~~MobProfileData.java~~ | 从实体抽 ~~CavaMobProfile~~（26 项惩罚表 + ~~CAVA_NAV_*~~） |
| ~~src/main/java/cava/hook/NativeNodeCodec.java~~ / ~~NativePathBuilder.java~~ | 原生节点 → 原版 ~~Path~~（含 ~~reachesTarget~~ 反语义还原） |
| ~~src/main/java/cava/hook/PathfindSwitches.java~~ | 全部系统属性开关（含诊断开关） |
| ~~src/main/java/cava/hook/PathfindProbe.java~~ | **金丝雀主动探测**（真实世界 + 真实生物 + 真实入口） |
| ~~src/main/java/cava/hook/PathfindScenario.java~~ | 可复现的合成寻路场景（探测与 bench 共用） |
| ~~src/main/java/cava/hook/PathfindBench.java~~ | ~~/cava pathfind {stats|probe|bench <n>}~~：**不依赖 profiler 的端到端测量** |
| ~~src/main/java/cava/hook/PathfindBootstrap.java~~ | 幂等自举（生命周期监听 + 命令注册） |
| ~~src/main/resources/cava.mixins.json~~ | 只加了本流的 4 个 mixin 类 |
| ~~src/test/java/cava/hook/*Test.java~~ | 6 个纯单测类（24 个用例，**不需要 MC、不需要原生库**） |

---

## 2. 实测证据（真实命令 + 真实输出）

### 2.1 构建与单测

    PS J:\mc\Cava> $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'
    PS J:\mc\Cava> .\gradlew.bat build --console=plain --no-watch-fs
    > Task :build
    BUILD SUCCESSFUL in 5s          (EXIT=0)

（期间实测过一次 ~~139 tests completed, 1 failed, 9 skipped~~，那 1 个失败是本流 ~~RegionWindowTest~~
的算术笔误，已修。）

### 2.2 金丝雀：真实服务端里真的 +1（**本轮最重要的验收项**）

环境：~~testbed/gate-preview~~（真实 Fabric 1.20.4 服务端 + fabric-api 0.96.11 +
**Lithium 0.12.1 / ServerCore 1.5.0 / VMP / FerriteCore / Carpet / TIS**），
把 ~~build/libs/cava-0.1.0.jar~~ 放进 ~~mods/~~，用
~~--enable-preview --enable-native-access=ALL-UNNAMED~~ 启动。

    [15:27:31] [main/INFO]: [cava/pathfind] 注入体已自举（hook=true native=false probe=true ...）；
        ...；mirrorProfile=available；penalizeDeepWater=readable
    [15:27:37] [Server thread/INFO]: [cava/pathfind] /cava pathfind {{stats|probe|bench <n>}} 已注册
    [15:27:37] [Server thread/INFO]: [cava/pathfind] 原生接管当前**关闭**（回退原逻辑），原因 2 条：
        -Dcava.pathfind.native=false（默认关：captain 的 P1 门禁 ...） | 镜像实现未发现（...）
    [15:27:37] [Server thread/INFO]: [cava/pathfind] 金丝雀 PASS：主动触发 findPathToAny 一次，
        计数 0 -> 1（原版返回 Path(4 节点)）；canary=1 takeovers=0 nativeCalls=0 errors=0 disabled=false
        reasons={skipped=1}

**这条证据链为什么算数**：~~PathfindProbe~~ 不是直接调 ~~hook.hit()~~，而是在**真实世界 + 真实生物**上
调用真实的 ~~PathNodeNavigator.findPathToAny~~（~~PathfindScenario.invoke~~），
所以「计数 +1」证明的是 **mixin 真的应用到了 ~~class_13~~ 上并被调用**，而不是「计数器会加」。
~~reasons={skipped=1}~~ 进一步证明注入体确实走进了 ~~tryTakeover~~ 才回退。

规模版证据（bench 内 20000 次真实调用）：

    [cava/pathfind] BENCH n=20000 ns/op=112980.4 totalMs=2259.6 avgNodes=4.00 nullPaths=0
        canaryDelta=20000(expect 20000) takeovers=0

**canaryDelta == n**：一次不多、一次不少。这比「+1」更硬。

**产物层面的旁证**（说明注入不是靠 refmap 侥幸生效）：~~remapJar~~ 已把注解值直接改写成 intermediary ——
~~javap -v~~ 实测产物里是
~~method=["method_52(Lnet/minecraft/class_1950;Lnet/minecraft/class_1308;Ljava/util/Set;FIF)Lnet/minecraft/class_11;"]~~、
~~method=["method_54(...)"]~~、accessor 的 ~~value="field_61"~~ / ~~value="field_18708"~~，
jar 里**没有也不需要 refmap**。

### 2.3 接管没打开时也是安全的（默认安全路径）

~~-Dcava.pathfind.native~~ **默认 false**（captain 的 P1 门禁：~~cava_pathfind~~ 保守回退 +
~~CAVA_PF_*~~ 位号对齐未回执）。默认配置下：

- 注入体只做 ~~canary.hit()~~ + 一次 ~~enabled()~~ 判断，**从不取消原方法**；
- ServerCore 在方法体内的 4 个 ~~@Redirect~~ / 2 个 ~~@ModifyVariable~~ 照常执行；
- 启动日志逐条列出「为什么现在不能接管」，可追责。

### 2.4 性能：**不依赖 profiler 的端到端测量**

做法：~~/cava pathfind bench <n>~~ 在真实世界 + 真实生物上跑 n 次**同一条**合成寻路
（同一 ~~maxRange~~/~~reachRange~~/~~followRange~~/~~range~~，每次新建
~~ChunkCache~~+~~LandPathNodeMaker~~+~~PathNodeNavigator~~，与 ~~PathFinder~~ 的实际形状一致），
直接量 wall-clock，并同时打印**金丝雀增量**作为正确性对照。

同一条命令、同一份 jar、同一个存档、同一 mod 集合，**只改一个系统属性**：

| 腿 | 系统属性 | n | ns/op | totalMs | canaryDelta | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| B（纯原版 + 一次 mixin 分发） | ~~-Dcava.pathfind.hook=false~~ | 20000 | **131143.6** | 2622.9 | 20000/20000 | ~~reasons={}~~ |
| A（注入体 + 金丝雀 + 总是回退） | ~~-Dcava.pathfind.hook=true -Dcava.pathfind.native=false~~ | 20000 | **112980.4** | 2259.6 | 20000/20000 | ~~reasons={skipped=20201}~~ |
| C（尝试原生，被镜像门禁挡住） | ~~... -Dcava.pathfind.native=true~~ | 20000 | **106374.5** | 2127.5 | 20000/20000 | ~~reasons={profile-not-ready=20201}~~ |
| D（**诊断开关**跳过门禁 → 真的走完整链路） | ~~... -Dcava.pathfind.diagnostic.bypassProfileGate=true~~ | 5000 | **396737.4** | 1983.7 | 5000/5000 | ~~nativeCalls=5201 reasons={native-unimplemented=5201}~~ |

**诚实的读法**：

1. **A/B/C 三条腿的差异（106–131 µs）小于本机噪声**。B（钩子完全不介入）反而比 A/C 慢，
   物理上不可能 ⇒ 差异被机器噪声吞掉了（本机同时有别的 agent 在跑服务端）。
   ⇒ **「钩子本身的边际开销」在当前测量精度下无法分辨**，不能声称「钩子开销 < 5%」之类。
2. **D 腿的 396.7 µs/op（≈3.0× 纯原版）是超出噪声的真实效应**，它测的是
   「**完整编排跑一遍但原生拒绝**」的成本：每次调用都要推一次区域（35×35×35 窗口）
   + 上传档案 + 一次 ~~cava_pathfind~~（返回 -7）。
   ⇒ **只要 ~~cava_pathfind~~ 还保守回退，打开原生路径就是净亏约 3 倍**。这条量化了 captain 那条门禁的必要性。
3. 三条腿的 ~~canaryDelta~~ 全部等于 n ⇒ 这个 bench 同时是一条**注入覆盖率**断言。
4. ~~avgNodes=4.00 nullPaths=0~~：合成场景稳定产出 4 节点路径，两腿可比。

**最终 jar 的复验**（把 ABI 偏移常量改成 ~~CavaLayouts.MobProfileOffset.*~~ 之后重跑 A 腿）：
~~n=20000 ns/op=102731.8 totalMs=2054.6 avgNodes=4.00 nullPaths=0 canaryDelta=20000(expect 20000)~~，
金丝雀同样 PASS。**同一腿三次实测 102.7 / 113.0 µs**，再次说明噪声大于效应。

**基线是否合规**：prompts/04 要求「与装了 Lithium + ServerCore 的 Java 寻路对比」。
B/A/C 腿**就是**在装了 Lithium 0.12.1 + ServerCore 1.5.0 的服务端里跑的（§2.2 环境），所以基线是对的；
但**原生加速比无法测**，因为原生根本没接管（§4.1）。

### 2.5 编排链路在真实服务端上确实调到了原生并按错误码回退

D 腿实测（诊断开关见 §3.4）：

    [cava/pathfind] BENCH n=5000 ns/op=396737.4 totalMs=1983.7 avgNodes=4.00 nullPaths=0
        canaryDelta=5000(expect 5000) takeovers=0
    [cava/pathfind] canary=5201 takeovers=0 nativeCalls=5201 errors=0 disabled=false
        reasons={native-unimplemented=5201 profile-gate-bypassed=1}
    [cava/pathfind] 金丝雀 PASS：主动触发 findPathToAny 一次，计数 0 -> 1（原版返回 Path(4 节点)）

⇒ **区域推送 → 档案上传 → ~~cava_pathfind~~ → ~~CAVA_ERR_UNIMPLEMENTED~~ → 回退原逻辑 → 原版返回 Path(4 节点)**
这条链路每一步都真实执行过，且**没有任何异常逃逸**（~~errors=0~~）。

---

## 3. 设计要点与**踩到的坑**（都可复现）

### 3.1 坑 1：金丝雀的**鸡生蛋**问题 —— 必须有一个独立的启动自举点

第一版只在 ~~findPathToAny~~ 的注入体里调 ~~PathfindBootstrap.ensureInstalled()~~。
实测（gate-preview 跑 75 秒）：**日志里一条 ~~[cava/pathfind]~~ 都没有**。
于是**无法区分**「mixin 没生效」和「这段窗口内没有寻路发生」——这正是门禁 #6 要抓的静默失效。

修法：新增 ~~MinecraftServerBootstrapMixin~~，在 ~~MinecraftServer.<init>~~ HEAD（static handler）
与 ~~runServer~~ HEAD 各调一次幂等的 ~~ensureInstalled()~~。

> **附带踩到的第二个坑**：构造器 ~~@At("HEAD")~~ 的注入 handler **必须是 static**。
> 非 static 时 Mixin 直接抛
> ~~InvalidInjectionException: @At("HEAD") selector @Inject handler before super() invocation must be static~~，
> 而且因为 ~~MinecraftServer~~ 在 ~~Cava.onInitialize~~ 期间就被类加载，**整个服务端起不来**（实测崩溃日志）。

### 3.2 坑 2：反射读原版 private 字段在**生产环境**必然失效

第一版用 ~~MethodHandles.privateLookupIn(...).findVarHandle(AmphibiousPathNodeMaker.class, "penalizeDeepWater", boolean.class)~~
读两栖档案的 ~~penalizeDeepWater~~。开发环境（named）能跑；**真实服务端实测**：

    [cava/pathfind] 读不到 AmphibiousPathNodeMaker.penalizeDeepWater
      （java.lang.NoSuchFieldException: no such field: net.minecraft.class_15.penalizeDeepWater/boolean/getField）

原因：生产环境里 MC 类是 **intermediary**（字段是 ~~field_XXXXX~~）。
修法：改用 ~~@Accessor~~ mixin（Loom 的 ~~remapJar~~ 会把注解里的字段名改写成 intermediary）。
修完实测 ~~penalizeDeepWater=readable~~（§2.2 第一行）。

### 3.3 坑 3：命令必须用**确定性**方式注册

只挂 ~~CommandRegistrationCallback~~ 时，真实服务端上 ~~/cava pathfind bench~~ 报
~~Unknown or incomplete command~~（事件触发时机与注册时机对不上）。
修法：~~PathfindBench.ensureRegistered(server)~~ 直接拿 ~~server.getCommandManager().getDispatcher()~~
注册，在 SERVER_STARTED 与每个 END_SERVER_TICK 各兜一次（~~AtomicBoolean~~ 幂等）。

### 3.4 诊断开关（**不要在生产打开**）

~~-Dcava.pathfind.diagnostic.bypassProfileGate=true~~：跳过镜像流的 ~~isProfileReadyForSolve~~ 门禁。
存在的唯一理由见 §2.5；打开时打 **ERROR** 日志。位号对齐之后必须关掉 ——
那时跳过门禁就会真的产出「看起来正常但语义错」的路径。

### 3.5 多目标**直接不走原生**（写进代码注释 + 本文档）

原版 ~~found~~ 是 ~~Sets.newHashSetWithExpectedSize~~ 出来的 ~~HashSet<TargetPathNode>~~、
~~targetMap~~ 是 ~~Collectors.toMap~~ 出来的 ~~HashMap~~，**多目标时的遍历顺序取决于桶序**
（oracle spec §4.3.1），无法复刻。
~~PathfindHook.doTakeover~~ 第一句就是 ~~if (targets.size() != 1) { count("multi-target"); return null; }~~。
单目标时无影响。

### 3.6 ~~reachesTarget~~ 的反语义与「只填 4 个字段」

- ~~Path.reachesTarget~~ 的语义是**反的**（found 非空 ⇒ ~~createPath(..., false)~~）。
  还原方式：末节点与终点的曼哈顿距离 ≤ ~~(float) reachRange~~ ⟺ 走 found 分支 ⟺ ~~reachesTarget=false~~。
  证据：oracle spec §4.3.1 / §4.4（~~iload 5 ; i2f ; fcmpg ; ifgt~~）。
- 转换只填 ~~x/y/z/type~~（外加 ~~heapIndex~~ 作 parity 证据）。依据是本机 javap 实测：
  ~~Path~~ / ~~EntityNavigation~~ / ~~MobNavigation~~ 读 ~~PathNode~~ 的字段**只有 x/y/z/type**，
  ~~pathLength / penalizedPathLength / heapWeight / distanceToNearestTarget / penalty / visited~~
  **没有任何读取点**（它们是求解器内部量，而求解器已经在原生侧）。
  ⇒ ABI 只导出 x/y/z/heapIndex/g/f/type 对**可观测行为**是够的。

### 3.7 并发：单句柄 = 单份可变状态 ⇒ 必须串行化

~~cava_mob_profile_upload~~ / ~~cava_region_upload~~ 在原生侧是**每个句柄一份**状态，
而原版寻路跑在 ~~Util.getMainWorkerExecutor()~~ 的**工作线程**上。
同一句柄并发调用会互相踩踏 ⇒ ~~PathfindHook~~ 用一把 ~~ReentrantLock~~ 串行化
【镜像推送 + 档案上传 + ~~cava_pathfind~~】。**这是冻结 ABI 的固有约束**，已上报（§4.3）。

### 3.8 关掉注入体时金丝雀仍然计数

~~onHookEntry()~~（计数）刻意放在 ~~hookEnabled()~~ 判断**之前**。
金丝雀要回答的是「注入点有没有生效」，与「要不要接管」是两个问题；
B 腿实测 ~~-Dcava.pathfind.hook=false~~ 时 ~~canaryDelta~~ 仍等于 n，正是「开关只影响接管」的证据。

---

## 4. 跨流接口缺口 / 请求清单

### 4.1 ⚠ **当前唯一的真实阻塞：镜像流的 ~~isProfileReadyForSolve~~ 恒 false**

C 腿实测 ~~reasons={profile-not-ready=20201}~~（20000 次 bench + 1 次探测）。
后果：**即使把 ~~-Dcava.pathfind.native~~ 打开，原生也一次都不会被调用**。
这不是 bug，是镜像流 flags 谓词位还没就绪时的**正确表现**（宁可回退，也不要跑出语义错的路径）。
**但这也意味着：在 A 确认「位号已对齐 + 向量重跑通过」之前，原生加速比无从测量。**

### 4.2 重复劳动的处置：档案到底谁推？（**已按互斥方案落地，请 captain 裁决**）

- 冻结接口 ~~RegionSource.uploadProfileForSolve(handle, profileKey)~~ 只说「上传档案以供本次求解」，
  但**调用方只能给它一个 ~~long profileKey~~，给不了位姿/惩罚表** —— 而那些只有注入点拿得到。
- 镜像流实际提供了 ~~McMobProfileCapture.of(mob, world)~~ + ~~MobProfiles.define(spec)~~ 注册表。
- 本流的处置（~~PathfindProfileBridge~~）：**优先走镜像流**（~~of~~ → ~~define~~ → ~~uploadProfileForSolve~~），
  镜像流不可用时才退化到注入流自带的 ~~MobInputs~~ + ~~CavaNative.mobProfileUpload~~。**两条路互斥**，
  不会出现「两边各推一份」。
- **请求 captain 裁决**：档案的权威生产者是谁？若定为镜像流，请把「如何把实体位姿交给镜像流」
  写进冻结契约（现在的 ~~uploadProfileForSolve(handle, key)~~ 签名表达不了这件事）。

### 4.3 其他接口请求

| # | 给谁 | 请求 |
| --- | --- | --- |
| 1 | captain | ~~RegionSource~~ 冻结接口里没有「按世界取镜像」（A 的 ~~RegionMirror.forWorld(ServerWorld)~~）与 ~~pushForSolve(...)~~；本流只能靠反射撞。建议把它们提升为契约的一部分，否则「接口对不上」只能靠运行期回退掩盖 |
| 2 | P1-Java-A | ~~profileKey~~ 的构成必须两边一致。本流实现的是 FNV-1a 64 over ~~[makerKind, caps, width, height, stepHeight]~~（位模式）。**不一致的表现是「永远静默回退」，不报错** |
| 3 | captain / P0-B | 单句柄可变状态（档案/区域）与「寻路跑在工作线程」互斥 ⇒ 需要**每线程一个句柄**，或把档案+区域变成 ~~cava_pathfind~~ 的入参，否则原生侧永远只能串行跑，多核优势归零 |

### 4.4 已确认的 ABI 缺口（不阻塞本轮）

- ~~CavaPathNode.flags~~ 的注释写着 ~~CAVA_PATH_NODE_*~~，但 **~~cava_abi.h~~ 里没有定义任何 ~~CAVA_PATH_NODE_*~~**。本流目前**忽略该字段**。
- ~~CavaMobProfile.reserved_max_fall_distance~~（原 ~~max_fall_distance~~）：captain 已裁决为占位字段，本流**固定填 0**。
- ~~CAVA_NAV_CAN_WALK_ON_FLUID~~：Yarn 1.20.4 的 ~~Entity~~ 没有 ~~canWalkOnFluid~~，本流**固定填 0**，**未验证**。

---

## 5. 未验证 / 留白（**不要当成已完成**）

1. **原生接管从未在真实服务端上成功过一次**（~~takeovers=0~~）。§2.5 只证明了「调用与回退」，
   **没有**证明「原生返回的节点序列能变成一条与原版一致的路」。
   ~~NativePathBuilder~~ 的正确性目前只有单测（~~NativeNodeCodecTest~~）覆盖。
2. **ServerCore 的 4 个 ~~@Redirect~~ / 2 个 ~~@ModifyVariable~~ 被跳过后的可观测差异未做差分验证**。
   prompts/04 判断「它们只换容器实现」；本轮**没有**验证 —— 因为本轮从不取消（~~native=false~~）。
3. **区域窗口策略（~~RegionWindow~~，±ceil(maxRange)+1）未在真实运行中被检验**：
   窗口给小了原生侧会把区域外当「无碰撞空气」⇒ 穿墙。本轮原生从不求解，所以这条风险尚未暴露。
4. **~~profileKey~~ 的两侧一致性未验证**（§4.3 第 2 条）。
5. **多目标的回退分支没有专门的实测**（bench 场景是单目标）。代码路径存在且逻辑简单，但**未验证**。
6. **钩子本身的边际开销无法从本轮数据分辨**（§2.4 第 1 条）。
7. **整服层差分没做**：契约 §4.2 说整服逐字节比对不可靠（~~docs/CAVA-determinism-report.md~~），
   本轮只做了「真实服务器上的 native on/off 对比」（§2.4），**没有**跑 region+poi 哈希比对。
   那需要固定种子 + 关刷怪 + tick freeze/sprint，属于整合轮。
8. 两栖档案在真实服务端上**没有被走到过**（探测用的是陆生生物）。
9. captain 提醒的 **A 的探针环境不加载数据包 ⇒ BlockTags/FluidTags 为空**，本流**没有**在真实服务端上
   复核「标签驱动的位」的计数 —— 那是 A 的交付面，本流只如实转述。

---

## 6. 复现

    # 1) 构建
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'
    $env:TMP='J:\mc\Cava\build\tmp'; $env:TEMP=$env:TMP
    .\gradlew.bat build --console=plain --no-watch-fs

    # 2) 真实服务端 + 金丝雀（驱动脚本是本流的工作产物，不进仓库）
    powershell -NoProfile -ExecutionPolicy Bypass -File build\cava-p1b\run-gate2.ps1 `
        -Label p1b-legA -ExtraProps "-Dcava.pathfind.hook=true -Dcava.pathfind.native=false" -Bench 20000

    # 3) 性能四腿：只改 ExtraProps
    #    hook=false / native=false / native=true / native=true + diagnostic.bypassProfileGate=true

**依赖**：~~testbed/gate-preview~~ 需要 ~~enable-rcon=true~~ + ~~rcon.password=cava~~（端口 25575）
才能用 ~~/cava pathfind bench~~ 驱动（本流已就地改好，该目录不在仓库里）。
