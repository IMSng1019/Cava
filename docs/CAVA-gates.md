# Cava 门禁验证记录（captain）

> **每条门禁都必须有本机实测证据。** 这是"架构假设是否成立"的台账，与 `docs/CAVA-baseline.md`（性能基线）分开。
> 复现脚本：`tools/build-preview-gate.ps1`、`tools/setup-preview-gate-server.ps1`、`tools/LayoutGuardProbe.java`。

---

## 门禁 #4：**Gradle 构建与单元测试 → 通过（captain 独立复跑）**

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Dfile.encoding=UTF-8'
.\gradlew.bat test --console=plain --no-watch-fs --rerun-tasks
```

**结果**：`BUILD SUCCESSFUL in 6s`，exit 0；`compileJava` 打印 `Note: Some input files use preview features of Java SE 21`
（确证 `--enable-preview` 生效）。JUnit 报告（`build/test-results/test/*.xml`）逐类实测：

    cava.CavaConfigTest          tests=5  failures=0 errors=0
    cava.ffm.LayoutHashTest      tests=5  failures=0 errors=0
    cava.parity.Fnv1aTest        tests=5  failures=0 errors=0
    cava.parity.GoldenTraceTest  tests=3  failures=0 errors=0
    cava.parity.TraceDiffTest    tests=5  failures=0 errors=0
    cava.subsystem.CanaryTest    tests=5  failures=0 errors=0
    TOTAL                        tests=28 failures=0 errors=0

产物 `build/libs/cava-0.1.0.jar`（667165 B，33 个 class）实测包含 `natives/windows-x64/cava.dll`（2780964 B）、
`fabric.mod.json`、`cava.mixins.json`、`cava.client.mixins.json`。

**预览标志在 remap 之后仍然保留**（实测逐 class 读头）：`cava/ffm/CavaBindings.class` = `major 65, minor 65535`（预览版），
而 `cava/ffm/NativeLibrary.class` / `cava/parity/TickSampler.class` = `major 65, minor 0`（普通版，因为它们不直接用预览 API）。
**说明**：`--enable-preview` 是编译期要求（不写它 javac 直接拒绝 `java.lang.foreign`），编译出来的**只有真正用到预览 API 的 class** 带 65535。
所以"jar 里 preview class 数量"不是指标；`compileJava` 的那行 Note 才是。

### 本机两条无害噪声（不要误判为失败）
1. `Exception in thread "File watcher server" ... Couldn't open current thread, error = 5`：
   沙箱不允许 Gradle 起文件监视线程。**加 `--no-watch-fs` 即消失**，与构建结果无关。
2. `Directory 'C:\Program Files\Java\jdk-21.0.10' ... does not exist`（三个候选路径）：
   `gradle.properties` 里 `org.gradle.java.installations.paths` 的兜底候选，**只是提示**；
   期望的 `C:\Program Files\Java\jdk-21` 存在且被选中，构建与测试都正常。

---

## 门禁 #2 + #3：**端到端冒烟（原生库 ↔ Java FFM ↔ 一键回退 ↔ ABI 守卫）→ 全部通过**

**结论：PASS。** 这是 P0 的核心验收：Java 侧的 FFM facade **真的**加载了 C++ 产物、**真的**跑通了布局自检与所有回退路径。

### 证据链（全部实跑，产物为 CMake 构建的 `natives/windows-x64/cava.dll`）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 原生自测 | `native/tests/build-mingw.ps1` | `SUMMARY: 67 passed, 0 failed` / `RESULT: PASS`（c++17 / c++20 / CAVA_SAFE=1 **三份各 67/0**，exit 0） |
| CMake+CTest | `cmake --build build/native-captain` + `ctest --test-dir build/native-captain -C Release` | **3/3 passed**（`cava_dll_loadtest` / `cava_fp_probe` / `cava_selftest`），exit 0 |
| Java 端到端 | `java --enable-preview --enable-native-access=ALL-UNNAMED -cp <classes> cava.ffm.NativeSelfTest` | `status=OPEN`、`handle=4294967297`、**`java_layout_sum = native_layout_sum = 0x6149FD30`**、22 个 double 边界值 ×(d2i,d2l) + 8 个位模式往返**全 ok**、`SELF-TEST: PASS`、exit 0 |
| 一键回退 | `... cava.ffm.NativeSelfTest disabled` | `status=DISABLED_BY_FLAG`、**INFO 不是 ERROR**、exit 0 |
| 缺库优雅失败 | `-Dcava.native.path=<不存在>` | `status=RESOURCE_MISSING`、exit 0（不崩） |
| **ABI 守卫** | `tools/LayoutGuardProbe` | 见下表 |

### ABI 守卫逐项（`tools/LayoutGuardProbe.java`，直连原生 `cava_open`/`cava_close`）

    abi=1  sum=correct        -> status=0    handle=4294967297  native_sum=0x6149FD30   OK
       close=0  closeAgain=-3  closeForged=-4        （幂等 + 伪造句柄都安全返回错误码）
    abi=1  sum=wrong          -> status=-2   handle=0           （CAVA_ERR_LAYOUT）      OK
    abi=2  sum=correct        -> status=-1   handle=0           （CAVA_ERR_ABI_VERSION） OK
    abi=99 sum=correct        -> status=-1   handle=0           （CAVA_ERR_ABI_VERSION） OK
    abi=0  sum=correct        -> status=-1   handle=0           （CAVA_ERR_ABI_VERSION） OK
    abi=1  sum=bytewise-var   -> status=-2   handle=0           （变体被拒绝）           OK

**这一条直接保住"JVM 段错误"这个最大风险**：布局一旦漂移，`cava_open` 在**写入任何句柄之前**就以 `CAVA_ERR_LAYOUT` 失败，
Java 侧整体回退纯 Java，不会有任何一次原生调用落在错误的结构体上。

### 一个必须记住的实测约束：**`Linker.defaultLookup()` 看不到 `System.load()` 的 DLL**

本机实测：`System.load(绝对路径)` 成功后，`Linker.nativeLinker().defaultLookup()` **查不到** `cava_build_id`/`cava_open`。
`CavaBindings` 已实现回退 `SymbolLookup.libraryLookup(path, Arena.ofShared())` 并常驻该 arena（实测生效）。
**P1/P2/P3 任何新增原生调用都必须走这条已封装好的路径，不要自己写 `defaultLookup`。**

### 未验证
- **MC 侧代码未与真实 MC API 对编**（`cava.Cava` / `cava.parity.TickSampler` / `cava.client.CavaClient`）：
  P0-C 用手写桩做了语法/类型自检（exit 0），但那**不证明**与真实 API 匹配。需要 `gradlew build` 产出 yarn named jar 后补编。
- MSVC 构建、Linux x64、CAVA_SAFE 的真实 CMake 构建（P0-B 只手工模拟过）。

---

## 门禁 #7：**P1 的 ABI 扩展在两侧同时落地并端到端复验 → 通过**

**为什么单列一条**：扩 ABI 是本轮风险最高的一次改动 —— 它同时动 C 结构体、原生布局注册表、
Java 的 `MemoryLayout` 镜像、以及**两侧算出的 `layout_hash_sum`**。任何一侧漏改，
`cava_open` 就会 fail-closed，整个 mod 静默回退纯 Java（**看起来"能用"，其实原生一次都没被调用**）。

### 最终状态（captain 亲自复跑）

| 检查 | 结果 |
| --- | --- |
| 原生自测（三种构建：c++17 / c++20 / `CAVA_SAFE=1`） | 各 **130 passed, 0 failed** / exit 0 |
| `ctest`（4 个用例：dll_loadtest / fp_probe / pathfind_vectors / selftest） | **4/4 passed** |
| Java 侧 `CavaLayouts.checkAgainstCAbi()` | **problems = 0**（逐字段 (offset,size) 与 C 编译器一致） |
| 两侧 `layout_hash_sum` | Java `0x6975CBF9` == native `0x6975CBF9` |
| `cava.ffm.NativeSelfTest`（真实 dll） | `status=OPEN`、`handle=4294967297`、`java_layout_sum == native_layout_sum`、**`SELF-TEST: PASS`** |

9 个结构体（实测 size/fields）：
`CavaLayoutEntry 544/8`、`CavaLayoutReport 34848/8`、`CavaOpenParams 32/5`、`CavaOpenResult 24/4`、
`CavaPathRequest 56/13`、`CavaPathNode 32/8`、`CavaMobProfile 192/18`、`CavaStateRecord 20/5`、`CavaCollisionBox 24/6`。

### 这条门禁真正抓到的四个坑（每一个都会静默错）
1. **`CAVA_PNT_*` 序号表是从记忆里编的**：从索引 5 起全错位，且含 4 个 1.20.4 **不存在**的常量、缺 4 个真实常量。
   惩罚表 `float penalty[26]` 按它索引 ⇒ **路径照样算得出来，只是不与原版一致**。已用 `PathNodeType` 的 `static{}` 字节码逐条重建。
2. **`CavaPathNode` 在原生注册表里漏登记了 2 个字段**（`type` / `flags`，只登记了 6/8）。
   靠**和值不等**定位：原生 `0x80b49975` vs Java `0x6975CBF9`；
   在 Java 侧只哈希前 6 个字段**恰好**复现 `0x80b49975`，从而钉死根因。
   > **教训：布局自检的"和值不等"是真实信号，绝不能当噪声跳过。**
3. **FFM 的 `structLayout` 不会自动插填充，而是直接拒绝错位成员**；同时 JDK 21 的 `paddingLayout`
   **无法命名**，导致两侧字段数/大小不一致。最终把字段顺序选成两侧都零内部填充，尾部填充改成显式具名字段。
4. **我手算的 offset 错了 3 处** —— 全部由编译器的 `offsetof/sizeof` 实测值纠正。

### 由这次事故派生的**永久护栏**（P0-B 主动加的，值得推广）
每个结构体现在做**两套**比对：
- **硬编码期望表**（变更探测器：结构体被改动时它会红）；
- **用 `offsetof/sizeof` 现算的机械表**（连字段名顺序都比）。

> 为什么两套都要：如果只把"人给的字段表"抄进硬编码表，**两张表会照同一份错理解一起错**，测试反而"通过"。
> 只有机械表能抓住 `CavaPathNode` 漏字段那一类。**"测试通过"不等于"理解正确"。**

### 本轮附带修掉的一个"假绿"（值得单独记）

`cava.ffm.PathfindAbiTest` 的 6 个用例在**每一次** `gradlew test` 里都是 **skipped**：
测试任务强制 `cava.native.enabled=false`，于是 `tryOpen()` 返回 `DISABLED_BY_FLAG`，整类命中 assumption。
**构建一直是绿的，但整个原生 ABI 一次都没被测到 —— "绿"不等于"测过"。**

修法：测试任务把两个开关**成对**决定（工作区里有 `natives/windows-x64/cava.dll` 就打开原生并指过去；
没有就关掉原生、跑纯 Java 回退）。两条路都实测过：

    有 dll  -> PathfindAbiTest ran=6 skipped=0 ；NativeFallbackTest skipped
    无 dll  -> NativeFallbackTest ran=2 skipped=0 ；PathfindAbiTest skipped

### 附带确认：兼容层报告在真实服务端里跑通了（端到端）

同一轮实机启动（含 Lithium / ServerCore / VMP / FerriteCore / Carpet / TIS）里，服务端主线程打出了完整归属表：

    CAVA-COMPAT|v1|header|mc=1.20.4|loader=0.19.5|native=OPEN|mods=56|relevant=5|overlaps=10|defer=entity,redstone|rules=0/19
    CAVA-COMPAT|v1|owner|pathfind|lithium|mixin.ai.pathing|native|auto|decided|LandPathNodeMaker 缓存短路（priority 990）；不碰 PathNodeNavigator
    CAVA-COMPAT|v1|owner|pathfind|servercore|optimizations.misc.PathFinderMixin|native|...
    CAVA-COMPAT|v1|owner|entity|vmp|entity.move_zero_velocity.MixinEntity|mod|auto|pending-p2|...
    CAVA-COMPAT|v1|owner|redstone|carpet|CarpetSettings.fastRedstoneDust|mod|auto|pending-p3|...
    [Server thread/INFO]: Done (6.051s)!
    [cava] 金丝雀跳过 entity：兼容层：让位给 lithium(...) + servercore(...) + vmp(...)
    [cava] 金丝雀跳过 redstone：兼容层：让位给 lithium(...) + carpet(...) + carpet-tis-addition(...)

两点值得注意：
1. **Mixin 层面确实生效了**：日志里有
   `Force-disabling mixin 'ai.pathing.LandPathNodeMakerMixin' as rule 'mixin.ai.pathing' (added by mods [cava])`，
   证明 `custom.lithium:options` 那条配置真的被 Lithium 读到了（不是"写了但没人读"）。
2. **金丝雀的 0/3 现在有解释了**，而且解释是**逐点、带 mod 名的** —— 不再是笼统的"P0 骨架未启用"。
   这正是兼容层该有的样子：**每个让位决定都能在日志里被追责。**

> ⚠️ **一个必须说清的性能事实**：`mixin.ai.pathing` 现在**已经关掉**，而 P1 的原生路径**尚未接管**
> （`cava_pathfind` 保守返回 `CAVA_ERR_UNIMPLEMENTED`）。也就是说**在 P1 接管之前，寻路比装 Cava 之前更慢**
> —— 关掉的是 Lithium 的 `LandPathNodeMaker` 缓存短路，纯损失、没有任何补偿。
> 回退办法是删掉 `fabric.mod.json` 里的 `custom` 段（单测会提醒）。**这条已上报 captain 待拍板。**

### 验证这类事情时的一个陷阱（我自己先踩了一次）

我第一次验"无 dll"是直接把 dll 改名后跑 `gradlew test` —— **结论是错的**，
因为 **Gradle configuration cache 复用了 dll 还在时算出的那个决定**。
**凡是依赖"配置期文件是否存在"的验证，必须加 `--no-configuration-cache` 重跑**，
否则你测的是缓存，不是代码。（我是用一个临时测试打印测试 JVM 真正看到的系统属性才确认这一点的。）

### 门禁 #6 的未闭合项：**金丝雀已在真实服务端里真的 +1 → 已闭合**

`docs/CAVA-execution-plan.md` 之前记着「金丝雀 0/3，从未在真实环境里被验证过一次」，
而"**静默失效**"是三种失效模式里最危险的一种（服务器正常启动、TPS 正常，但原生从未被调用）。
注入流把它验成了真的 —— 真实服务端（Fabric 1.20.4 + Lithium + ServerCore + VMP + FerriteCore + Carpet + TIS）：

    [cava/pathfind] 金丝雀 PASS：主动触发 findPathToAny 一次，计数 0 -> 1（原版返回 Path(4 节点)）；
      canary=1 takeovers=0 nativeCalls=0 errors=0 disabled=false reasons={skipped=1}
    [cava/pathfind] BENCH n=20000 ns/op=102731.8 avgNodes=4.00 canaryDelta=20000(expect 20000)

**"+1"不是调 `hook.hit()` 伪造的**：探测在真实世界 + 真实生物上真调了一次 `PathNodeNavigator.findPathToAny`；
bench 里 20000 次真实调用 `canaryDelta=20000/20000`（一次不多不少）。
旁证：`remapJar` 后注解已是 `method_52`/`method_54`、accessor 是 `field_61`/`field_18708`，**jar 里不需要 refmap**。

> **仍未闭合的**：`takeovers=0` —— **原生从未成功接管过一次**。
> 原因已定位（captain 的接口设计错误：档案需要实体位姿与惩罚表，而只有注入点拿得到，
> 所以"镜像流产出档案"注定恒返回未就绪）。接口已改（`6a925ef`），等镜像侧跟进后复测。

### 从"金丝雀从未被验证"这件事里学到的三条（都来自注入流的真实踩坑）

1. **金丝雀会"鸡生蛋"**：只在注入体里自举 ⇒ 服务端跑 75 秒日志里**一条 `[cava/pathfind]` 都没有**，
   于是**无法区分「mixin 没生效」和「这段窗口里根本没有寻路」**。
   修法：加一个**只做自举**的 `MinecraftServer.<init>` HEAD 注入。
   > ⚠️ **附带硬约束**：**构造器 `@At("HEAD")` 的 handler 必须是 `static`**，
   > 否则 Mixin 抛 `InvalidInjectionException` 且**整个服务端起不来**（实测崩溃）。这是"启动即崩"级。
2. **反射读原版 private 字段在生产环境必然失效**：MC 在运行时是 **intermediary** 命名，
   实测 `NoSuchFieldException: net.minecraft.class_15.penalizeDeepWater`。
   **必须用 `@Accessor`**，不能用反射。
3. **性能测量不该依赖 profiler**：本机 async-profiler 不可用、spark 采不到内层帧，
   注入流改用 `/cava pathfind bench <n>`（同一 jar/存档/mod 集，只改一个 `-D`）。
   它的**诚实读法**值得推广：
   > A/B/C 三腿的差（106–131 µs）**小于本机噪声**（B 比 A/C 还慢，物理上不可能）⇒ **钩子边际开销本机测不出**；
   > 而 D 腿（诊断开关跳过门禁）**396.7 µs ≈ 3.0× 原版**，远超噪声 ⇒
   > **只要 `cava_pathfind` 还保守回退，开原生就是净亏约 3 倍。**
   > **在原生真能出路径之前，不宣布任何性能结论。**

### 一条被"实测推翻一半"的担忧：位置依赖的碰撞形状

我在裁决块里要求"位置依赖的碰撞形状**不许静默发散**"，前提是它会影响碰撞。
镜像流按裁决实现后，顺手做了一个**比裁决前提更强**的实测，结果把风险缩小了：

| 项 | 静态面 | 实际需要守卫的 |
| --- | --- | --- |
| 覆写了带 `world/pos/ShapeContext` 形状方法的方块类 | **114 个** | **3 个类 / 64 个状态**（`BambooBlock` / `PointedDripstoneBlock` / `ScaffoldingBlock`） |

它没有按"声明了参数就算"来守卫（那会把大量"声明了但根本不看"的方块误挡），
而是对这些类**用 18 个合成上下文**（邻居=空气/石头/自身 × 两个位置 × `ShapeContext`=absent/上方有实体/下降）**重算碰撞盒，只守卫真的会变的那 64 个状态**。

**更重要的实测**（1.20.4 的碰撞形状基本与邻居无关 —— 连接条只出现在 outline 里）：

    shapes oak_fence:        air=[0.375,0,0.375 -> 0.625,1.5,0.625]，solid/self/ctxAbove **完全相同**
    shapes cobblestone_wall: air=[0.25,0,0.25 -> 0.75,1.5,0.75]      **完全相同**
    shapes iron_bars / oak_fence_gate / oak_stairs / stone / water:  **完全相同**

⇒ 原以为"2/16 连接条被低估"的风险**对碰撞不成立**；MODE_EMPTY 视图对绝大多数方块是**精确**的。

**运行期形态**：push 在 fill 后、上传前扫区域 id，命中被守卫的状态即**回退**（默认开，
`-Dcava.mirror.shape.guard=false` 可关做 A/B）；成本 = O(区域方块数) 次数组查表。

> **残留风险（已写明，未消除）**：那 18 个变体是**启发式**，不是穷举；真实服务器上仍需复核。

### 三条"容易读反 / 容易混淆"的原版真值（P2 实体轮，captain 已独立复核）

这三条都是**运行期不会报错、只会让结果与服务器不一致**的类型，所以每条都配了定点用例，而不是靠随机向量。

#### 1. `MathHelper.approximatelyEquals` 的阈值是 `9.999999747378752E-6`，**不是 1e-7**

```
0: dload_2 ; 1: dload_0 ; 2: dsub ; 3: invokestatic Math.abs:(D)D
6: ldc2_w  #166   // double 9.999999747378752E-6d
9: dcmpg ; 10: ifge 17 ; 13: iconst_1 ; 18: ireturn
```

**最容易犯的错**：把 `1e-7` 当成这个函数的阈值。**`1e-7` 确实存在于 `Entity.move` 里，但它是
`lengthSquared` 守卫用的另一条常量**，与近似比较无关。两个常量在两个地方、都有用，混起来不会报错。

#### 2. `verticalCollision` 用裸 `!=`，只有 x/z 走近似比较

`Entity.move` 里 `verticalCollision` 的判定是 `dcmpl/ifeq`（裸不等），而 x/z 用的是
`approximatelyEquals`。**后果**：当 `movement` 与 `adjusted` 相差 `1e-9` 时
—— **`horizontalCollision = false`、`verticalCollision = true`**。按"三个轴都用近似比较"实现就会错。

#### 3. `Vec3d.equals` / `Box.equals` 用的是 `Double.compare` ⇒ **`-0.0` 不等于 `0.0`**

```
21: getfield x:D ; 29: invokestatic Double.compare:(DD)I ; 32: ifeq 37 ; 35: iconst_0 ; 36: ireturn
```

**这条直接决定 VMP 的零位移短路**：VMP 的条件是 `movement.equals(Vec3d.ZERO)`，而 `Vec3d.ZERO` 的分量是 `+0.0`。
所以**一个分量为 `-0.0` 的 movement 不会命中短路**。用 `== 0.0` 或"数学上等于零"来实现，
会比现服务器**多跳过**一批移动 —— 逐 tick 差分必爆，而且极难定位。

> **共同教训**：这三条都不是"读错跳转方向"，而是**"两个看起来一样的常量/判定，实际不是一回事"**。
> 与 P1 那次 `!flag5` 是同一类风险的不同形态。**防御手段也一样：写死调用序列的定点用例**，
> 因为随机向量是参照实现自己产的，参照实现错了它跟着错。

### 一条被证伪的担忧（记录下来免得后人重复投入）

注入流担心「档案/区域是每句柄一份可变状态，而寻路跑在工作线程上 ⇒ 只能加锁，并行度=1」，
并建议做"每线程一句柄"或"档案改成入参"的 ABI 改造。

**captain 用字节码核过：这个前提不成立。**
`EntityNavigation.findPathToAny` 是**直接 `invokevirtual`** 调 `PathNodeNavigator.findPathToAny`，
整条调用链上**没有任何 executor 交接** ⇒ **原版寻路是同步跑在调用（主）线程上的**。
所以那把锁是**防御性**的，不是吞吐瓶颈；**不做 ABI 改造**（拿高风险变更去换一个不存在的问题）。
新增显式约束：**"原生路径假定主线程调用"** —— 将来若有 mod 把寻路挪到工作线程，这条门禁要重评。

### 已知且可接受的弱点
`layout_hash` 公式**不区分字段数/形状相同的结构体**：`CavaPathNode` 与 `CavaCollisionBox` 的
hash 都是 `0x250ECBE1`。所以**哈希只做"整体漂移"的粗筛**，真正的护栏是**逐字段全表比对**。
这条已写进契约 2.3。

---

## 门禁 #6：**真实 Cava jar 在真实服务端里跑起来（启动横幅 + 原生库加载 + 布局自检）→ 通过**

这是"MC 侧代码从未在服务端里跑过"这个缺口的闭合验证（captain 亲自做）。
**做法**：把 `gradlew build` 产出的 `build/libs/cava-0.1.0.jar`（不是桩、不是探针）
连同 `fabric-api 0.96.11` 与 **Lithium / ServerCore / VMP / FerriteCore / Carpet / TIS** 一起
放进一个真实 Fabric 服务端的 `mods/`，用 `--enable-preview --enable-native-access=ALL-UNNAMED` 启动。

### 证据（服务器真实 stdout，节选）

    - cava 0.1.0
       \-- mixinextras 0.5.5
    [main/WARN]: Force-disabling mixin 'alloc.blockstate.StateMixin' as rule 'mixin.alloc.blockstate' (added by mods [ferritecore]) disables it and children
    [main/WARN]: Force-disabling mixin 'alloc.chunk_ticking.ServerChunkManagerMixin' as rule 'mixin.alloc.chunk_ticking' (added by mods [servercore]) disables it and children
    [native] 原子落盘 …\cava\natives\0.1.0\windows-x64\cava-34a578ed29e77709.dll (len=2780964, sha256=34a578ed29e77709…)
    [native] System.load(…\cava-34a578ed29e77709.dll) 成功
    [native] defaultLookup() 找不到 cava_build_id，回退 SymbolLookup.libraryLookup()
    [cava/native] 布局自检通过：native_entries=4 java_sum=0x6149fd30 native_sum(per-entry u32 sum)=0x6149fd30
    [cava/native] cava_open → sent_sum=0x6149fd30 rc=CAVA_OK result.status=CAVA_OK result.abi=1 result.native_layout_sum=0x6149fd30 handle=4294967297
    [cava/native] 原生库已打开：status=OPEN build_id="cava 0.1.0 windows-x64 GNU 15.2.0 … -O2 -fwrapv -ffp-contract=off -fno-fast-math safe=0 …" abi=1/1 entries=4
      ################ Cava 0.1.0 （Java 21 预览版 FFM + C++ 原生；P0 骨架，未注入任何游戏逻辑） ################
      ================================ Cava / native ================================
      native 状态     : OPEN  (OK)
      ABI 版本        : java=1 native=1 platform=windows-x64 build_flags=0x0 build_id_hash=0x398927d3
      布局自检        : 通过  java_sum(u32)=0x6149fd30 native_sum=0x6149fd30 entries=4
      cava_abi_touch  : 1（=1 表示原生代码确实执行过）
      config 文件     : …\config\cava.json（文件不存在，已写入默认值）
    [Server thread/INFO]: Done (7.102s)! For help, type "help"
    [Server thread/INFO]: [cava/parity] 未开启轨迹采集（-Dcava.parity.trace=<dir> 才开）
    [Server thread/INFO]: [cava] 金丝雀跳过 pathfind：enabled=false（P0 骨架或已被禁用，不参与判定）
    [Server thread/INFO]: [cava] 金丝雀自检结束：0 个通过 / 3 个注册

### 这条门禁证明了什么
1. **资源打包约定成立**：`natives/windows-x64/cava.dll` 从 jar 里被正确取出（`source=resource:/natives/windows-x64/cava.dll`）。
2. **哈希命名 + 原子落盘 + `System.load` 在生产路径上真的跑通了**，不是只有自检程序能跑。
3. **布局自检在真实服务端里通过**，且 `cava_abi_touch() == 1` 证明**原生代码确实被执行过**（不是只加载了库）。
4. **与真实优化 mod 共存启动成功**：Lithium / ServerCore / VMP / FerriteCore / Carpet / TIS 同时在装，无冲突、无崩溃。
5. **`config/cava.json` 默认值自动落盘**。

### 仍未闭合
- **金丝雀 0/3**：因为 P0 阶段三个子系统都是 `enabled=false`（按设计），所以"金丝雀主动触发目标方法看计数器有没有动"这条链路**尚未在真实环境里被验证过一次**。
  这一条要等 P1 真正注入 `PathNodeNavigator.findPathToAny` 之后才能验。**这是 P1 的必验项。**
- 黄金轨迹**尚未采到**（`-Dcava.parity.trace` 没开），确定性前置（同存档连续两次一致）也还没证明。

---

## 门禁 #5：**预览版 class 能否被 Fabric Loader 加载并执行 → 通过**

**结论：PASS。** 整个项目的载体假设是「Fabric mod + JDK 21 **预览版 FFM**（必须 `--enable-preview`）」。
如果 Fabric Loader / Mixin 无法处理 class 文件 major 65 + minor 65535（预览版标志），或者运行时拿不到
`java.lang.foreign`，那么"用 FFM 调 C++"这条路根本不成立，整个方案要重审。**这条必须先验，不能等到 P1。**

### 做法（不依赖 Gradle / Loom，纯手工 jar）
1. 用 `javac --release 21 --enable-preview` 编一个实现 `net.fabricmc.api.ModInitializer` 的类，
   编译期依赖直接取本机 Gradle 模块缓存里的 `fabric-loader` 与 `sponge-mixin`（不联网、不走 Loom）。
2. 打成普通 mod jar（`fabric.mod.json` + 一个 entrypoint），丢进真实 **Fabric 0.19.5 / MC 1.20.4** 服务端的 `mods/`。
3. 用 `--enable-preview --enable-native-access=ALL-UNNAMED` 启动真实服务端。
4. 在 `onInitialize` 里用预览版 FFM 做一次真实 native 调用（`strlen`），并把结果打进日志。

### 证据（真实 stdout，未删改）

    正在加载 Minecraft 1.20.4 with Fabric Loader 0.19.5
    加载 5 个 mod:
        - cava-gate 0.0.1
        - fabricloader 0.19.5
        - java 21
        - minecraft 1.20.4
    [CAVA-GATE] ================= PREVIEW GATE =================
    [CAVA-GATE] mod class file major.minor = 65.65535   (65.65535 == preview)
    [CAVA-GATE] runtime               = 21.0.10+8-LTS-217
    [CAVA-GATE] fabricloader          = 0.19.5
    [CAVA-GATE] java.lang.foreign     = java.lang.foreign.Linker
    [CAVA-GATE] native strlen("cava")  = 4   (expect 4)
    [CAVA-GATE] VERDICT               = PREVIEW_OK
    [13:54:31] [Server thread/INFO]: Done (14.193s)! For help, type "help"

（`major.minor = 65.65535` 直接读的是 jar 里那个 class 文件自己的头，证明它确实是预览版 class，
而 Loader 把它加载起来并执行了 `onInitialize`。）

### 适用版本（实测）
| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.4 |
| Fabric Loader（真实服务端解析到的） | **0.19.5**（与 `gradle.properties` 里 pin 的 `loader_version=0.19.5` 一致） |
| Fabric Installer | 1.1.2 |
| `sponge-mixin`（服务端实际加载的） | 0.17.4+mixin.0.8.7 |
| JDK | 21.0.10+8-LTS-217 |
| 启动参数关键项 | `--enable-preview --enable-native-access=ALL-UNNAMED` |

### 由这条门禁派生的**新的硬约束**

1. **`--enable-native-access=ALL-UNNAMED` 不能省。** 少了它 native downcall 会在运行期被拒。
2. **Fabric Loader 0.19.5 是**"能加载预览版 class 的**已知可用版本**"。以后任何 `loader_version` 变更都要重跑这条门禁。
3. 预览版 class 可以正常打成 jar、被 Loader 发现、被实例化 —— 所以 **Cava 的 `ModInitializer` 本身可以直接是预览版 class**。

---

## 门禁 #1：**Loom 版本必须 pin 到 1.17.x → 通过**

**结论：通过（已定位根因并由 P0-A 修复）。**

| 事实 | 证据 |
| --- | --- |
| `fabric-loom` **1.18.2** 要求 **JVM 25** | `Could not resolve net.fabricmc:fabric-loom:1.18.2 ... Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.`；maven.fabricmc.net 的 module metadata 属性 `org.gradle.jvm.version = 25` |
| `fabric-loom` **1.17.20 / 1.17.21** 要求 **JVM 21** ✅ | 同一属性 = 21，plugin-api 9.5.0 |
| `fabric-loom` 1.10.5 要求 JVM 17 | 同一属性 = 17 |

**决定**：`gradle.properties` 的 `loom_version` pin 到 **1.17.20**，Gradle 用 **9.7.1**（满足 plugin-api 9.5.0）。
**推论**：模板自带的 CI workflow 用 JDK 25 是**因为 Loom 1.18 需要 25**；本项目改用 Loom 1.17.x 之后，
CI 必须把 JDK 固定成 **21**（否则 `--release 21 --enable-preview` 不成立）。已核对：workflow 里现在是 `java-version: '21'`（temurin）。

---

## 附：本轮 captain 亲自跑出来的三条"环境坑"（都已固化）

1. **PowerShell 5.1 会破坏 `-Dkey=value` 参数**：`& java -Dfoo=bar ...` 实测被解析成 `ClassNotFoundException: /foo=bar`；
   `javac -cp "a;b"` 实测报 `invalid flag: :`。**解法**：JVM 属性走 `$env:JAVA_TOOL_OPTIONS`；javac 参数走 **`@argfile`（UTF-8 编码！）**。
   > ASCII argfile 会把含中文的用户目录名写成 `???`，导致 classpath 静默失效（实测报 "package net.fabricmc.api does not exist"）。
2. **`Linker.defaultLookup()` 看不到 `System.load()` 的库**（见门禁 #2）。
3. **`natives/<平台标签>/` 是共享输出目录**：实测被两个流先后覆盖（116933B → 114904B → 空 → 2780964B），
   每次覆盖都会让别人链接到不一致的映像。**同一时刻只有一个流能构建原生产物。**