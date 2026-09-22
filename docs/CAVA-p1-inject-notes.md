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

**编排链路端到端全通、且不再需要任何诊断开关**（§2.6）：真实服务端上
~~接管条件全部满足~~、~~mirrorFactory=cava.mirror.MirrorFactory instance=true impl=cava.mirror.RegionMirror~~、
~~nativeCalls=5201 reasons={native-unimplemented=5201} errors=0~~。

**内核也已真实接线**：~~takeovers=2000/2000~~、~~nativeCallsDelta=2000~~、~~errors=0~~（§2.6）——
**第一次真实的原生接管已经拿到**。

**但同世界的 on/off 对比显示：原生当前比 vanilla 慢约 13.9 倍**（452.5 vs 32.6 µs/op）。
瓶颈**不是原生 A\***，而是**每次求解重推 35³=42875 格的区域**（稳态 ≈300 µs/次，
而 vanilla 整个求解 ≈33 µs）⇒ 见 §4.6 的 ABI 请求。**这条不解决，打开原生就是净亏。**

---

## 1. 交付物

| 路径 | 内容 |
| --- | --- |
| ~~src/main/java/cava/mixin/pathfind/PathNodeNavigatorMixin.java~~ | **注入点**：~~findPathToAny~~ 两个重载（method_52 / method_54） |
| ~~src/main/java/cava/mixin/pathfind/PathNodeNavigatorAccessor.java~~ | ~~@Accessor~~：~~pathNodeMaker~~ / ~~range~~（只读，不改表达式） |
| ~~src/main/java/cava/mixin/pathfind/AmphibiousPathNodeMakerAccessor.java~~ | ~~@Accessor~~：~~penalizeDeepWater~~（**必须是 accessor，不能用反射**，见 §3.2） |
| ~~src/main/java/cava/mixin/pathfind/MinecraftServerBootstrapMixin.java~~ | **只做自举**：~~MinecraftServer.<init>~~ HEAD + ~~runServer~~ HEAD（见 §3.1） |
| ~~src/main/java/cava/hook/PathfindHook.java~~ | 接管编排（取输入 → 镜像 → 原生 → 转换 → 回退） |
| ~~src/main/java/cava/hook/PathfindMirrorBridge.java~~ | 到 ~~cava.mirror.RegionSource~~ 的窄桥：取实例后用**契约方法** ~~bind(ServerWorld)~~ 绑世界（见 §4.3） |
| ~~src/main/java/cava/hook/MobInputs.java~~ / ~~MobProfileData.java~~ | 从实体抽 ~~CavaMobProfile~~（26 项惩罚表 + ~~CAVA_NAV_*~~） |
| ~~src/main/java/cava/hook/NativeNodeCodec.java~~ / ~~NativePathBuilder.java~~ | 原生节点 → 原版 ~~Path~~（含 ~~reachesTarget~~ 反语义还原） |
| ~~src/main/java/cava/hook/PathfindSwitches.java~~ | 全部系统属性开关（含诊断开关） |
| ~~src/main/java/cava/hook/PathfindProbe.java~~ | **金丝雀主动探测**（真实世界 + 真实生物 + 真实入口） |
| ~~src/main/java/cava/hook/PathfindScenario.java~~ | 可复现的合成寻路场景（探测与 bench 共用） |
| ~~src/main/java/cava/hook/PathfindBench.java~~ | ~~/cava pathfind {stats|probe|bench <n>}~~：**不依赖 profiler 的端到端测量** |
| ~~src/main/java/cava/hook/PathfindBootstrap.java~~ | 幂等自举（生命周期监听 + 命令注册） |
| ~~src/main/resources/cava.mixins.json~~ | 只加了本流的 4 个 mixin 类 |
| ~~src/test/java/cava/hook/*Test.java~~ | 6 个纯单测类（23 个 ~~@Test~~ 用例，**不需要 MC、不需要原生库**） |

---

## 2. 实测证据（真实命令 + 真实输出）

### 2.1 构建与单测

    PS J:\mc\Cava> $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'
    PS J:\mc\Cava> .\gradlew.bat build --console=plain --no-watch-fs
    > Task :build
    BUILD SUCCESSFUL in 3s          (EXIT=0)

本流 6 个单测类（读 ~~build/test-results/test/TEST-cava.hook.*.xml~~ 实测）：

    cava.hook.AbiPenaltyOrderTest       tests=3 failures=0 errors=0 skipped=0
    cava.hook.MobProfileDataTest        tests=4 failures=0 errors=0 skipped=0
    cava.hook.NativeNodeCodecTest       tests=3 failures=0 errors=0 skipped=0
    cava.hook.PathfindOutcomeTest       tests=4 failures=0 errors=0 skipped=0
    cava.hook.PathfindSwitchesTest      tests=4 failures=0 errors=0 skipped=0
    cava.hook.RegionWindowTest          tests=5 failures=0 errors=0 skipped=0
    HOOK TOTAL                          tests=23 failures=0 errors=0
    全部（含其它流）                     tests=144 failures+errors=0

（并行期实测过两次**别人路径**导致的红：~~cava/mirror/**~~ 一次 ~~compileJava~~、
~~src/test/java/cava/mirror/probe/McProbeMain.java~~ 一次 ~~compileTestJava~~；
都等对方跟上后复跑通过，本流未越界修改。另有一次本流 ~~RegionWindowTest~~ 的算术笔误，已修。）

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

**最终配置的复验**（新契约 ~~MirrorFactory~~ + ~~bind~~ + ~~isFlagsReadyFor~~ + ~~uploadProfileForSolve(handle, uploader)~~，
**诊断开关关闭**，n=5000）：

    [cava/pathfind] BENCH n=5000 ns/op=430803.9 totalMs=2154.0 avgNodes=4.00 nullPaths=0
        canaryDelta=5000(expect 5000) takeovers=0
        | canary=5201 takeovers=0 nativeCalls=5201 errors=0 reasons={native-unimplemented=5201}

⇒ 完整编排（绑世界 + flags 门禁 + 区域推送 + 档案上传 + ~~cava_pathfind~~ 返回 -7 + 回退）
**430.8 µs/op ≈ 3.3–4.2× 纯原版**（102.7–131.1 µs）。这条比 D 腿更硬：**没有任何诊断开关参与**。

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

### 2.6 【里程碑】**真实原生接管 + 窗口归镜像侧之后的 on/off 对比**

环境：**私有服务端目录** ~~testbed/p1b-bench~~（私有端口 25598/25574，世界按同一 seed 生成；
为什么必须私有见 §2.8）。驱动：~~build/cava-p1b/bench-run.ps1~~（**带回执校验**，见 §2.7）。

**第一阶段**（窗口由注入流自己算，35³）：native ON 均值 **452.5 µs/op ≈ vanilla 的 13.9 倍慢**。
定位到瓶颈是「每次求解重推 42875 格」。captain 据此把窗口策略移进契约（~~pushForSolve~~）。

**第二阶段**（本流改用契约 ~~pushForSolve~~）：

| 腿 | 系统属性 | n | ns/op 三次 | 均值 | **稳态（第 3 次）** | spread | takeovers |
| --- | --- | --- | --- | --- | --- | --- | --- |
| native OFF | ~~-Dcava.pathfind.native=false~~ | 20000 | 48720.8 / 28473.1 / 28039.0 | 35077.6 | **28039.0** | 59.0% | 0 |
| native ON | ~~-Dcava.pathfind.native=true~~ | 2000 | 69861.2 / 35050.2 / 27677.7 | 44196.4 | **27677.7** | 95.4% | **2000/2000** |

**怎么读这组数（重要）**：
1. **两条腿都有很强的预热瞬态**（69.9→35.1→27.7 与 48.7→28.5→28.0），所以**均值不是正确的统计量**；
   稳态取第 3 次采样：~~27.68 µs（native ON）vs 28.04 µs（native OFF）~~ ⇒ **在当前场景下已经分不出差别**
   （差 1.3%，远小于样本内噪声）。相对第一阶段的 452.5 µs，**这是 16 倍改善**。
2. **这不是「原生更快」的结论**：这是**合成场景**（一只生物、一个位置、7 节点短路径），
   原生 A\* 在这种路径上本来就没有发挥空间。要证明加速必须换**搜索量大的场景**（长路径 / 迷宫 / 多生物）。
3. ~~avgNodes=7.00~~（第一阶段是 4.00）—— 换了世界，所以**不要跨阶段比绝对值**，只比同一阶段内的 on/off。

**窗口与推送成本的变化（日志原文）**：

    第一阶段（注入流自己算）：区域推送 #4000 35x35x35=42875 填=280us 分配拷贝=34us 上传=2us 总=320us
    第二阶段（镜像侧算）：    区域推送 #4000 15x12x9=1620   填=19us  分配拷贝=1us  上传=0us 总=21us
                              区域推送 #6000 15x12x9=1620   填=7us   分配拷贝=1us  上传=0us 总=8us

窗口从 42875 格降到 **1620 格（26 倍小）**，单次推送从 ~320 µs 降到 **8–21 µs**（≈20–40 倍）。
**本流不再需要「区域窗口」这套自己的策略**：~~RegionWindow~~ 与其单测已删除，
开关 ~~-Dcava.pathfind.maxRegionBlocks~~ 一并删除。

**⚠️ 但「复用」其实还没有真正生效**（如实记录，见 §4.6）：
计数显示 ~~区域推送 #6000~~ ≈ 6000 次求解 —— **一次复用例都没有**。
读 A 的实现：~~RegionMirror.pushForSolve~~ 直接调 ~~push(...)~~（**没有走** ~~pushReusingSameTick~~），
而 ~~pushReusingSameTick~~ 的复用条件里**仍然有** ~~lastTick == r.currentTick()~~。
所以这一轮的性能改善来自**窗口变小**（+ 上传器跳过内容未变的上传，日志里 ~~上传=0us~~），**不是来自复用**。

### 2.6.1 第一阶段（历史证据，保留）

| 腿 | n | ns/op 三次 | 均值 | takeovers |
| --- | --- | --- | --- | --- |
| native OFF | 20000 | 43628.5 / 25738.4 / 28495.9 | 32620.9 | 0 |
| native ON（35³ 窗口） | 2000 | 505478.7 / 421252.7 / 430869.6 | 452533.7 | 2000/2000 |

里程碑（当时拿到）：~~takeovers=2000/2000~~、~~errors=0~~、**bypassProfileGate=false**~~。

环境换成**私有服务端目录** ~~testbed/p1b-bench~~（从 gate-preview 复制、私有端口 25598/25574、
**世界按同一 seed 重新生成**）。换目录的原因见 §2.8：gate-preview 是**多个 agent 共用**的。

驱动：~~build/cava-p1b/bench-run.ps1~~（**带回执校验**，见 §2.7）。

| 腿 | 系统属性 | n | ns/op 三次 | 均值 | spread | takeovers | nativeCalls |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **native OFF** | ~~-Dcava.pathfind.native=false~~ | 20000 | 43628.5 / 25738.4 / 28495.9 | **32620.9** | 54.8% | 0 | 0 |
| **native ON** | ~~-Dcava.pathfind.native=true~~ | 2000 | 505478.7 / 421252.7 / 430869.6 | **452533.7** | 18.6% | **2000/2000** | 2000 |

**里程碑**：~~takeovers=2000~~、~~nativeCallsDelta=2000~~、~~canaryDelta=2000/2000~~、~~errors=0~~ ——
**每一次调用都被原生接管**，而且 ~~bypassProfileGate=false~~（没有任何诊断开关参与）。
探针那次也是 ~~金丝雀 PASS ... canary=1 takeovers=1 nativeCalls=1 errors=0 reasons={}~~。

**⚠️ 但原生现在比 vanilla 慢约 13.9 倍**（452.5 / 32.6）。原因在日志里直接可见 ——
每次求解都要重推一个 35³ 的窗口，**稳态每推一次 ≈ 300 µs**，而 vanilla **整个求解**只要 ~33 µs：

    [cava/mirror] 区域推送 #1    35x35x35=42875 cells 填=2435us 分配拷贝=1369us 上传=2216us 总=6104us
    [cava/mirror] 区域推送 #4000 35x35x35=42875 cells 填=280us  分配拷贝=34us    上传=2us    总=320us
    [cava/mirror] 区域推送 #6000 35x35x35=42875 cells 填=268us  分配拷贝=30us    上传=2us    总=303us

⇒ **瓶颈不是原生 A*，而是「每次求解重推整个长方体」这条 ABI 模型**（见 §4.6 的请求）。
按 captain 的要求：**这不是最终性能结论**，只是「当前窗口策略 + 当前区域 ABI」下的事实。

### 2.7 测量驱动的可靠性（**我自己踩的两个坑，已修**）

captain 要求：**宁可少采几次，也不要让无效采样混进平均值**。为此做了两层：

**（1）模组侧：可校验回执**（~~PathfindBench~~，已进仓库）
- 每次 bench 有单调递增的 ~~id~~；
- **无论成功失败都打印恰好一行** ~~[cava/pathfind] BENCH id=<n> ok=<true|false> ...~~
  （~~ok~~ 的判据是 ~~canaryDelta == n~~，即「每一次调用都真的穿过了注入点」）；
- 失败路径（~~no-mob~~ / ~~busy~~ / 异常）**也留行** ⇒ **缺行 = 命令真的没跑**，不再有歧义；
- ~~/cava pathfind stats~~ 报 ~~benchRuns= / benchSeq=~~；并发 bench 会被拒绝（不打无效样本）。

**（2）驱动侧：只认日志回执**（~~build/cava-p1b/bench-run.ps1~~，工作产物）
发完 RCON 命令后**轮询日志**等 ~~BENCH id=<下一个>~~，校验 ~~ok=true~~ 与 id 单调；
拿不到回执就报 ~~SAMPLE INVALID~~ 并重试/剔除，绝不进平均值。

**两个真实事故（都是这份驱动自己犯的）**：
1. **固定 sleep 后杀服务端**：20000 次 × 430 µs ≈ 8.6 s 的采样在 5 s 时被杀 ⇒
   日志没有 BENCH 行、~~nativeCalls~~ 也没涨，**看起来像「命令没执行」**。
   （captain 一开始也怀疑是 ~~tools/rcon.ps1~~；实测根因在我这边。）
2. **回执匹配到上一轮的陈旧行**：第一版用 ~~Select-String | Select-Object -Last 1~~ 匹配 ~~BENCH id=1~~，
   于是 3 次采样全部匹配到**同一行**，报出 3 个「VALID」且 ~~ns/op~~ **完全相同** —— 
   这个「三次一模一样」就是无效采样的指纹。修法：先数日志里已有的 BENCH 行数，只接受**新增**的行，
   并把 id 校验改成**单调下限**（用等式曾误杀过两个好样本）。

### 2.8 环境坑：~~testbed/gate-preview~~ 是**多 agent 共用**的

实测 15:46:36 另一个 agent 在同一个目录、同一份 ~~server.properties~~ 上起了自己的服务端
（留下 ~~takeover-run.log~~，并把 ~~server.properties~~ 的修改时间刷成 15:46:44），
我的采样进程随即被挤掉（RCON ~~connection refused~~、BENCH 行始终不出现）。
**这不是模组崩溃**：目录里**没有任何 ~~hs_err_pid*.log~~**（JVM 硬崩溃一定会留）。
⇒ 需要可信测量时，**必须用私有目录 + 私有端口**（~~testbed/p1b-bench~~，25598/25574）。

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

### 3.7 并发：**原生路径假定主线程调用**（我原先的判断被 captain 用 javap 纠正）

~~cava_mob_profile_upload~~ / ~~cava_region_upload~~ 在原生侧是**每个句柄一份**可变状态。
我最初以为"寻路跑在 ~~Util.getMainWorkerExecutor()~~ 的工作线程上"，
captain 复核字节码后否定了这个前提，**他是对的**，本机复核一致：

    javap -p -c net.minecraft.entity.ai.pathing.EntityNavigation
      181: invokevirtual  // Method PathNodeNavigator.findPathToAny:(Lnet/minecraft/world/chunk/ChunkCache;
                         //   Lnet/minecraft/entity/mob/MobEntity;Ljava/util/Set;FIF)Lnet/minecraft/entity/ai/pathing/Path;
    javap -p -c net.minecraft.entity.ai.pathing.MobNavigation
      ...: invokespecial  // Method EntityNavigation.findPathTo:(Lnet/minecraft/util/math/BlockPos;I)...
    （1.20.4 Yarn 里**没有** net.minecraft.entity.ai.pathing.PathFinder 这个类）

⇒ ~~EntityNavigation.findPathToAny~~ **直接 invokevirtual** 调导航器，整条链上没有 executor 交接，
寻路是**同步跑在调用线程（主线程）**上的。

**结论（显式约束，写进契约）**：**原生路径假定主线程调用。**
- ~~PathfindHook~~ 里那把 ~~ReentrantLock~~ **保留**，但它是**防御性**的（防止将来有 mod 把寻路挪到别的线程），
  **不是吞吐瓶颈**；
- **不做**"每线程一个句柄 / 把档案与区域改成 ~~cava_pathfind~~ 入参"这类 ABI 改造 ——
  那是拿一次高风险变更去换一个不存在的吞吐问题（captain 裁决，我原先的请求 §4.3 第 3 条**撤回**）；
- 门禁重评条件：**将来若有 mod 把寻路挪到工作线程**，必须重新评估。

### 3.8 关掉注入体时金丝雀仍然计数

~~onHookEntry()~~（计数）刻意放在 ~~hookEnabled()~~ 判断**之前**。
金丝雀要回答的是「注入点有没有生效」，与「要不要接管」是两个问题；
B 腿实测 ~~-Dcava.pathfind.hook=false~~ 时 ~~canaryDelta~~ 仍等于 n，正是「开关只影响接管」的证据。

---

## 4. 跨流接口缺口 / 请求清单

### 4.1 原阻塞：**已解决并经真实服务端复测**（契约 ~~MirrorFactory~~ 是最后一块拼图）

现象（C 腿实测）：~~reasons={profile-not-ready=20201}~~（20000 次 bench + 1 次探测），
**即使打开 ~~-Dcava.pathfind.native~~，原生也一次都不会被调用**。

captain 复核后认定**根因是他的契约设计错了**（不是 A 的实现问题、也不是本流的调用问题）：
~~CavaMobProfile~~ 需要实体位姿 + 26 项惩罚表，**只有注入点拿得到**，
所以"镜像流产出档案"的设计**必然恒返回未就绪**。已改 ~~RegionSource~~（captain 提交 ~~6a925ef~~）：

- 删 ~~isProfileReadyForSolve(profileKey)~~ / ~~uploadProfileForSolve(handle, profileKey)~~；
- 新增 ~~uploadProfileForSolve(long handle, Consumer<MemorySegment> uploader)~~ —— **注入流填值，镜像流只负责写进原生**；
- 新增 ~~isFlagsReadyFor(int caps)~~（取代 profileKey 门禁）；
- 新增 ~~bind(ServerWorld)~~（见 §4.3）。

本流已按新接口改完（~~PathfindHook~~ / ~~PathfindMirrorBridge~~），**并已在真实服务端复测通过**：

    [cava/pathfind] 注入体已自举（...）；mirrorFactory=cava.mirror.MirrorFactory instance=true impl=cava.mirror.RegionMirror；
        penalizeDeepWater=readable
    [cava/pathfind] 接管条件全部满足（hook=true native=true probe=true bypassProfileGate=false ...）
    [cava/pathfind] 金丝雀 PASS：主动触发 findPathToAny 一次，计数 0 -> 1（原版返回 Path(4 节点)）；
        canary=1 takeovers=0 nativeCalls=1 errors=0 disabled=false reasons={native-unimplemented=1}

注意 ~~bypassProfileGate=false~~、~~reasons~~ 里**没有** ~~flags-not-ready~~ ⇒ 镜像流的 flags 就绪门禁**真的返回了 true**。

### 4.2 实例入口：~~MirrorFactory~~（captain 提交 ~~d014f11~~）

我按 (A) 方案把**「按类名试工厂」的反射彻底删掉**了：~~PathfindMirrorBridge~~ 现在直接
~~import cava.mirror.MirrorFactory~~。~~-Dcava.mirror.class~~ 也随之删除。
**现在是编译期强耦合**：~~MirrorFactory~~ 一旦改名/消失，**编译就红**，不会再伪装成运行期的静默回退。
（captain 的总结值得记：**删掉一个反射 hack 只做了一半的活** —— 契约必须同时提供"正当地做这件事"的入口，
否则 fail-closed 会伪装成"子系统缺失"，排查成本极高。这次的实测表现就是 ~~reasons={mirror-missing=20201}~~。）

### 4.3 原「档案互斥桥」：已删除

### 4.4 档案的权威生产者 = **注入流**（captain 2026-09-22 裁决）

理由同 §4.1：**只有注入点有位姿与惩罚表**。
本流据此**删掉了中间层 ~~PathfindProfileBridge~~**（含它"优先走镜像流"的分支）——
现在只有一条路：~~MobInputs.build(mob, maker)~~ 产出 ~~MobProfileData~~ →
~~mirror.uploadProfileForSolve(handle, seg -> profile.writeTo(seg, 0))~~。
**不存在"两个生产者"，也不需要互斥桥。**

### 4.6 ⚠ **新的头号请求：区域 ABI 的「每次重推整个长方体」是当前瓶颈**

~~cava_region_upload~~ 的模型是「一次推一个有界长方体」，而注入流每次求解前都必须重推
（区域是每次求解的输入）。在 ~~maxRange=16~~ 的真实场景下，正确性要求窗口至少 ±ceil(maxRange)+1
（见 ~~RegionWindow~~ 的推导），于是**每次求解 memcpy 42875 个 state id**：

    [cava/mirror] 区域推送 #4000 35x35x35=42875 cells=42875 填=280us 分配拷贝=34us 上传=2us 总=320us
    （对照：vanilla 整个 findPathToAny ≈ 33 µs）

**请求（给 captain / 镜像流 / 原生流，任选其一，都不在本流路径上）**：
1. **增量/脏标记模型**：~~cava_region_upload~~ 改成「只推变化的方块」或用「区域句柄 + 失效范围」，
   让镜像侧可以跨求解复用（A 的 ~~pushReusingSameTick~~ / ~~onBlockChanged~~ 已经是这个方向，
   但它不在冻结接口 ~~RegionSource.push(minX..dimZ)~~ 里，注入流够不到）；
2. **或者把「窗口」交给镜像侧决定**：注入流只给 (start, target, maxRange, budget)，
   由镜像侧用它自己的区段缓存决定推什么；
3. **或者缩小窗口语义**：如果内核能接受「区域外 = 不可通行」而不是「区域外 = 无碰撞空气」，
   窗口就能按节点预算而非 maxRange 来定，体积小一个数量级。

**在 1/2/3 任一落地之前，打开原生路径都是净亏**（第一阶段实测 13.9×）。

**（2026-09-22 复测后的补充：契约已加 pushForSolve，但「复用」还没接上）**

captain 加了 ~~RegionSource.pushForSolve(start, target, width, height, safeFallDistance)~~（窗口策略归镜像侧，
本流已改用它并**删掉了自己的 ~~RegionWindow~~**），实测窗口 42875→1620 格、推送 320→8–21 µs，
稳态 on/off 已经**分不出差别**（27.68 vs 28.04 µs，§2.6）。

**但 read 代码后发现「复用」并没有生效**，本轮收益全部来自「窗口变小」：
1. ~~RegionMirror.pushForSolve~~ 里直接 ~~return push(RegionRect.forSolve(...))~~
   —— **没有走** ~~pushReusingSameTick~~（captain 说它走复用路径，实测没有）；
2. ~~pushReusingSameTick~~ 的复用条件里**仍然有** ~~lastTick == r.currentTick()~~，
   所以即使调它，也只能在同一 tick 内命中；captain 描述的「按是否发生过失效事件判定」只改了 ~~invalidate()~~ 一侧。
3. 证据：日志计数 ~~区域推送 #6000~~ ≈ 6000 次求解 ⇒ **复用命中 0 次**。

**请求（给镜像流）**：把 ~~pushForSolve~~ 接到 ~~pushReusingSameTick~~，并去掉条件里的
NaNlastTick == r.currentTick()~~（保留 ~~lastTick != Long.MIN_VALUE~~ 作为失效哨兵即可，
NaNinvalidate()~~ 已经承担「有变更就不复用」的结构性保证）。
预期收益：同一片地形上的连续求解 ~~elapsedNanos == 0~~，推送成本再降一个数量级。**未验证。**

### 4.5 其他接口请求

| # | 给谁 | 请求 |
| --- | --- | --- |
| 1 | captain | ✅ **已闭合**：~~MirrorFactory~~（~~d014f11~~）成为实例入口，反射全删，真实服务端复测通过（§4.1/§4.2） |
| 2 | P1-Java-A | **已作废**：~~profileKey~~ 随旧接口一起删掉了，现在门禁是 ~~isFlagsReadyFor(caps)~~，档案由注入流填值 |
| 3 | captain / P0-B | **已撤回**（captain 用 javap 纠正了我的前提）：寻路是**同步跑在主线程**的，不存在吞吐问题，不做 ABI 改造。见 §3.7 |
| 4 | P1 内核流 | **当前唯一的阻塞**：~~cava_pathfind~~ 仍保守返回 ~~CAVA_ERR_UNIMPLEMENTED~~ ⇒ ~~takeovers=0~~。前端的输入（状态表 flags 就绪 / 区域 / 档案）**已经全部备齐并实测通过**，只等内核肯算 |

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
