# Cava 并发笔记：原生库的线程模型（实测）+ Java 侧的调用线程归属

> **状态：原生侧已实跑（8 线程 × 4000 轮 × 3 份产物），Java 侧已读代码 + 已加运行期守卫。**
> 任务书点名的两件事都在这里：①fuzz 覆盖“区段并发卸载”；②回答“Java 侧到底会不会从非服务器主线程调用原生”。
> 相关文件：`native/tests/fuzz/cava_fuzz_abi.cpp`（多线程阶段）、`native/tests/fuzz/build-fuzz.ps1`、
> `src/main/java/cava/harden/NativeCallGuard.java`（归属检查）、
> `src/test/java/cava/harden/NativeThreadOwnershipTest.java`（单测）。
> 冻结的 ABI 头 `native/include/cava_abi.h` **一个字都没改**（本流无权改，线程模型条款留给 captain，见 §5）。

---

## 0. 结论（先看这五行）

1. **原生库在并发下不损坏内存**：8 线程 × 4000 轮 × 36112 次调用，三份产物（shipped / g++ release / CAVA_SAFE）
   全部 `crashes=0 undefined_returns=0 torn=0 contract_violations=0 overruns=0`。
2. **但“合法”不等于“一致”**：并发替换形状表期间，`cava_resolve_move` 有 **0.38%** 的调用返回
   `CAVA_ERR_ARG(-4)`。这是 ABI 明文允许的拒绝码，不是未定义行为，但它说明**库没有事务性快照**。
3. **根因已用对照实验定位**：只跑“寻路/求解”角色时 `rc=-4` 为 **0/11736**；
   加上“反复替换状态/形状表”的角色后变成 **367/96439 = 0.381%**。
4. **Java 侧今天只从一个线程调用原生**（服务端主线程 / Server thread）；
   `cava/hook/PathfindHook.java` 与 `cava/mirror/RegionMirror.java` 各自已有锁把原生调用串行化。
   风险不在服务器自己，在**第三方 mod 把寻路挪到工作线程**（AsyncPathFinding / Petal 这一类，本整合包未安装）。
5. **已加的守卫是“只计数不改行为”**：`NativeCallGuard.offThreadCalls()`（上线判据：恒为 0），
   首次越线打一条 WARN。**没有**给原生热路径加锁（性能是核心指标，见 §4 的成本估计）。

---

## 1. 契约与头文件里原本一个字都没写（这就是要有 §1 的原因）

- `docs/CAVA-工程接口契约.md` §2「原生库 ABI」：只讲指针/标量/布局/错误码，**没有线程模型**。
- `native/include/cava_abi.h`：**没有线程模型**。
- 代码里已有两处**隐含**假设（写在注释里，没有进契约）：
  - `cava/hook/PathfindHook.java` 第 46–49 行：认为原生状态是**每句柄一份**、
    用 `nativeLock`（ReentrantLock）把【镜像推送 + 档案上传 + cava_pathfind】串行化；
  - `cava/mirror/RegionMirror.java` 第 26–29 行：原版 `EntityNavigation.findPathToAny` →
    `PathNodeNavigator.findPathToAny` 是直接 invokevirtual、**链上无 executor 交接**，
    所以假定“原生路径跑在主线程（调用线程）”。

> 两处注释互相补全：**库本身不是线程安全的（语义上），但 Java 侧用锁把它变成单线程访问。**
> 这不是设计，这是两处独立加固凑出来的巧合 —— 所以本轮把它变成**可测量**的东西（§3）。

---

## 2. 多线程 fuzz 阶段：做法与断言

### 2.1 形态

`native/tests/fuzz/cava_fuzz_abi.cpp` 里新增 `multithread_phase(threads_per_role, seed, role_mask)`：
**8 线程 = 4 个角色 × 2**，每个线程固定 **4000 轮**，固定种子派生（每个线程一条独立输入流，
不共享驱动自己的 PRNG —— 否则驱动自己就是数据竞争，任何发现都无法归因）。

| 角色 | 线程在做什么 | 对应任务书的哪一句 |
| --- | --- | --- |
| A 卸载 | 反复 `cava_region_upload`（不同原点）+ `cava_region_clear` | “区段并发卸载” |
| B 读 | 反复 `cava_region_state_id_at` 读同一批坐标 | 撕裂读检测 |
| C 求解 | 反复 `cava_pathfind` / `cava_resolve_move` | 热路径与控制结构竞争 |
| D 换表 | 反复 `cava_state_table_upload` / `cava_shape_table_upload` / profile | 共享表被替换 |

开关：默认开；`--threads N` 指定每角色线程数（2 ⇒ 8 线程），`--roles <掩码>` 只跑指定角色
（**归因实验就靠它**：掩码 4 = 只跑角色 C，12 = C+D，15 = 全部），`-NoThreads` 整体跳过。

### 2.2 阶段开始前**必须**先建立基线（这是本轮自己踩的第一个坑）

第一版直接开 8 线程，结果角色 C 的 `cava_resolve_move` **4088/4088 全部返回 -4**。
看起来像“并发下库坏了”，其实是**驱动自己的问题**：
`cava_shape_table_upload` 从来没被调用过，而一个 `kind == CAVA_MSHAPE_STATE` 的 ref
要求 `state_id < record_count`（`native/src/entity/cava_entity_abi.cpp:220`）。
**这是契约定义的合法拒绝，不是竞态。** 修法：阶段开头显式上传 state/region/profile/shape 四张表并打印
`mt-baseline : state=0 region=0 profile=0 shape=0`，任何一个非 0 就把整个 fuzz 判为 FAIL。

> 教训与 `docs/CAVA-hardening-notes.md` §3 那条一样：**fuzz 驱动必须先自己满足契约**，
> 否则测出来的是驱动的 UB（或驱动的错误配置）。

### 2.3 断言（验收口径）

1. 进程活着走到 `MT SUMMARY`（崩了就没有这一行，脚本看 exit≠0）；
2. 返回码分类只有三种：{合法(≥0)、ABI 错误(-1..-7)、**未定义**}，`undefined_returns` 必须为 0；
3. guard page + `0xA5` 哨兵：任何越过声明容量的写算 `overruns`；
4. **返回值与自己的输出结构必须自洽**：`cava_resolve_move` 返回 `rc` 时 `out->status` 必须等于 `rc`，
   `cava_pathfind` 返回的节点数不能超过 capacity —— 不自洽算 `torn`；
5. **ABI 明文规定“error 不写 out”**：调用前把 `CavaMoveResult` 填 `0xA5`，返回 `<0` 时
   只要有一个字节被改过就算 `contract_violations`。

结果**允许**每次不同（`cava_region_clear` 真的会清掉区域，路径“有/无”都合法）。
验收口径是“**定义良好、有界、不破坏内存**”，**不是**“每次答案一样”。

---

## 3. 实跑数据

命令（`build-fuzz.ps1` 默认就带多线程阶段；`-NoThreads` 可关）：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -Cases 100000
```

### 3.1 三份产物（8 线程 × 4000 轮）

    === fuzz [shipped] ===        natives/windows-x64/cava.dll
      sha256 = 0EBE3B04A869819D7A59C8ADC11B0D12...   build_id: cava 0.1.0 windows-x64 MSVC 19.44.35228.0 /O2 /fp:strict safe=0
      MT SUMMARY threads=8 calls=36112 undefined_returns=0 torn=0 contract_violations=0 overruns=0
      mt-rc-hist     : ok=32024 positive=3945 ABI(-1..-7)=0/0/0/143/0/0/0 undefined=0
      mt-region      : uploads_ok=8000 clears_ok=4112
      MT RESULT: PASS        SUMMARY … cases=200467 crashes=0 undefined_returns=0 state_mutations=0 overruns=0 unexpected_ok=0

    === fuzz [release-rebuild] ===  build/native-fuzz/cava_release.dll (g++ 15.2, -O2 -fwrapv -ffp-contract=off)
      MT SUMMARY threads=8 calls=36112 undefined_returns=0 torn=0 contract_violations=0 overruns=0
      mt-rc-hist     : ok=32024 positive=3564 ABI(-1..-7)=0/0/0/524/0/0/0 undefined=0
      MT RESULT: PASS

    === fuzz [safe-build] ===       build/native-fuzz/cava_safe.dll (-DCAVA_SAFE=1)
      MT SUMMARY threads=8 calls=36112 undefined_returns=0 torn=0 contract_violations=0 overruns=0
      mt-rc-hist     : ok=32024 positive=3701 ABI(-1..-7)=0/0/0/387/0/0/0 undefined=0
      MT RESULT: PASS

    OVERALL: PASS   (SCRIPT EXIT=0)

⇒ **没有崩溃、没有未定义返回、没有撕裂读、没有越界写、没有契约违背。**
⇒ `crashes=0` 是**弱**证据（它只说明“这次没崩”），所以每一条都配了能红的对照（§3.3）。

### 3.2 0.38%：并发替换形状表时 `cava_resolve_move` 返回 `CAVA_ERR_ARG`

**这是本轮最重要的发现。** 用 `--roles` 掩码做的对照（每格 3 次运行、取合计）：

| 跑的角色 | resolve 调用数 | `rc=-4` | 比例 |
| --- | --- | --- | --- |
| 只有 C（求解） | 11736 | **0** | 0% |
| C + D（求解 + 换表） | 35736 | **0** | 0% |
| A + B + C + D（8 线程） | 96439 | **367** | **0.381%** |

（掩码 12 的 3 次运行恰好都是 0；掩码 15 的 3 次是 80 / 99 / 99 —— 说明它**依赖调度**，不是每次都触发。）

机制（读代码得到，与观测一致）：`cava_resolve_move` 在 `g_mutex` 里查一次 `record_count`，
再校验 `state_id < record_count`（`cava_entity_abi.cpp:205-224`）。
另一条线程用一份**更短的表**替换它，于是这次校验合理地失败。
**这不是数据损坏**（没有撕裂结构体、没有越界），是**调用序列层面没有原子性**。

对本项目的含义：**只要保证“同一时刻只有一条线程在碰原生”，这个 0.38% 根本不会出现** ——
Java 侧已经有锁（§4），所以它今天不是线上风险，而是**契约缺口**（§5）。

### 3.3 能红的对照（这一节比上面任何数字都重要）

| 对照 | 改动 | 结果 |
| --- | --- | --- |
| D 检测器的**第一版**对 error 路径也比 `out->status` | 驱动自身的 bug | 报 7 次“TORN”，全部是**假阳性**（error 路径本来就要求不写 out） |
| 修好检测器后 | 区分 `torn`（rc≥0 不自洽）与 `contract_violations`（rc<0 却写了 out） | 两者都归 0 |
| Java 归属检查的**故意改坏** | 把 `offThreadCalls.incrementAndGet()` 改成 `if (false) { … }` | `NativeThreadOwnershipTest` 立刻红：`expected: <150> but was: <0>`（1 failed / 4） |
| 恢复后 | —— | 4/4 绿 |

> **第一行值得单独记**：“可证伪”不只针对被测对象，也针对**测量工具**。
> 我的检测器第一版报出了 7 个“撕裂读”，读代码后发现是它把“error 不写 out”这条 ABI 规定当成了故障。
> 如果我没去核这条，报告里就会多出一条耸人听闻但完全错误的“并发撕裂读”。

---

## 4. Java 侧：到底会不会从非服务器主线程调用原生？

### 4.1 注入点逐个看（`require=0`、`@At("HEAD")`、显式 priority，纪律未破）

| 注入点 | 目标方法（Yarn） | 跑在哪条线程 | 依据 |
| --- | --- | --- | --- |
| `MinecraftServerBootstrapMixin` | `MinecraftServer.<init>` / `runServer` HEAD | 主线程（起服那条，之后变成 Server thread） | `runServer` 本身就是服务端主循环 |
| `PathNodeNavigatorMixin` | `PathNodeNavigator.findPathToAny`（Set 版 public / Map 版 private） | **调用线程** | 1.20.4 调用链 `ServerWorld.tick → … → MobEntity.tickNewAi → PathAwareEntity.tickMovement → EntityNavigation.findPathToAny → PathNodeNavigator.findPathToAny` **直接 invokevirtual、没有 executor 交接**（`RegionMirror` 注释里记着 captain 用字节码核过） |
| `EntityMoveMixin` ×3（`move` HEAD/RETURN、`setBoundingBox` HEAD） | `Entity.move` / `setBoundingBox` | Server thread | 实体 tick 与碰撞全在 `ServerWorld.tick` 里；`setBoundingBox` 是 `move` 与位置写入的伴随调用 |
| `EntityPushAwayFromMixin` | `Entity.pushAwayFrom` HEAD/RETURN | Server thread | 唯一调用链 `LivingEntity.tickCramming → World.getOtherEntities → pushAway → Entity.pushAwayFrom`（`docs/CAVA-push-oracle-spec.md` §1 “对整包 5684 个 class 做字节码串搜索，只有 8 个类提到它”） |
| `SectionedEntityCacheMixin` ×3 | `addSection` / `removeSection` / `forEachInBox` | Server thread | 区段头由实体追踪在 tick 里增删；`forEachInBox` 从 `getOtherEntities` 进来 |
| `VoxelShapeAccessor` / `McMoveAccess` / 两个 Accessor | 只读字段/调方法，不是原生入口 | 继承调用方线程 | 不改行为 |

**答案：今天 Java 侧的原生调用只可能来自服务端主线程（Server thread）。**
依据是三件事叠起来的：(a) 注入点全是原版在 tick 里同步调用的方法；
(b) 没有一处 executor / CompletableFuture 交接（`grep` 全仓 `src/main/java/cava`：除了 `ThreadLocal`、
原子类与 `addShutdownHook`，没有工作线程池）；
(c) `PathfindHook.nativeLock` 与 `RegionMirror` 的对象锁把“即使有第二条线程”也串行化了。

### 4.2 但风险不是零 —— 两类真实的越线来源（都未验证）

1. **第三方寻路 mod**：AsyncPathFinding / Petal 这类会把`findPathToAny`挪到工作线程或线程池。
   本整合包（`docs/CAVA-服务器模组清单.md`，49 个 mod）**没有**它们 ⇒ 今天不触发；
   **加 mod 检查清单里要加一条**：`cava.native.threadcheck` 的 `offThreadCalls` 必须仍为 0。
2. **其它 mod 从网络/IO 线程直接读写世界**（Ledger 回档、Axiom 光照 —— `prompts/00` 里点名的两处未验证）。
   它们会走到方块状态镜像的入口；入口本身有锁，但“线程假设”仍未验证。

### 4.3 已落地的守卫（**只计数，绝不改行为**）

`cava/harden/NativeCallGuard.java` 新增：`noteThreadOwnership(symbol)`，在 `call(...)` 开头调用。

- **第一条原生调用所在的线程 = owner**（之后不再变）；
- 任何别的线程调用 ⇒ `offThreadCalls++`，**首次**打一条 WARN（含 owner 名、调用者名、入口名）；
- **返回值、熔断、看门狗、日志之外的任何行为都不受影响**：不抛异常、不强制回退、不加锁；
- 成本：一次 `Thread.currentThread()` + 一次 `==`（owner 命中路径），不分配、不加锁；
- 开关：`-Dcava.native.threadcheck=false`（现场误报时不用重新打包就能关）；
- 计数出现在 `hardeningReport()` 里：`ownerThread=<name>#<id> offThreadCalls=<n>`。

**上线判据（与“原生回退计数=0”并列）**：`offThreadCalls == 0`。

单测（`src/test/java/cava/harden/NativeThreadOwnershipTest.java`，实测 4/4 绿）：

    sameThreadCallsNeverCountAsOffThread          同线程 1000 次 ⇒ offThreadCalls 恒 0
    aSecondThreadIsCountedAndReportedOnce         3 线程 × 50 次越线 ⇒ offThreadCalls == 150，返回值全部原样
    disablingTheCheckStopsTheCounting             -D…threadcheck=false ⇒ 完全不计数（反向对照）
    reportExposesTheThreadCounters                报告行里必须有 offThreadCalls / ownerThread

跑法（本机**不能用 gradlew**：共享 `GRADLE_USER_HOME` 的 wrapper 锁被别的流占着；
改用手搓 javac + gradle 缓存里的 junit-platform-launcher，**不依赖网络、不碰共享锁**）：

```powershell
$gh = 'J:\mc\mods\.gradle-home'   # 找 junit-jupiter-{api,engine}-5.10.2 / junit-platform-{commons,engine,launcher}-1.10.2 / opentest4j / apiguardian / slf4j-api
$javac = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
# 编 NativeCallGuard + CircuitBreaker + CallWatchdog + NativeThreadOwnershipTest（classpath 用上面那些 jar）
& $javac -encoding UTF-8 --release 21 -cp $cp -d build\p4c-junit\classes src\main\java\cava\harden\NativeCallGuard.java src\main\java\cava\harden\CircuitBreaker.java src\main\java\cava\harden\CallWatchdog.java src\test\java\cava\harden\NativeThreadOwnershipTest.java
# 跑（本流用的是一个 30 行的 LauncherFactory 小程序；结果见下）
TESTS=4 FAILED=0 SUCCEEDED=4 SKIPPED=0
```

---

## 5. 给 captain 拍板：三选一（我的建议是 A）

### A. 把“单线程调用”写成契约条款（**建议**）

候选文本（可直接粘进 `docs/CAVA-工程接口契约.md` §2.1，或 `cava_abi.h` 的顶部注释）：

> **2.1.9 线程模型（P4-C 实测补充）**
> 原生库**不是线程安全的**：每个句柄的可变状态（区段、状态表、形状表、档案）受一把全局互斥量保护，
> 因此**不会**出现数据损坏（P4-C 实测：8 线程 × 4000 轮，无崩溃 / 无未定义返回 / 无越界写），
> 但**跨调用的语义没有原子性** —— 实测并发替换形状表期间 `cava_resolve_move` 有 0.381% 的调用
> 返回 `CAVA_ERR_ARG(-4)`（A 类合法拒绝，调用方按既有规则回退纯 Java 即可）。
> **调用方义务**：同一句柄上的所有原生调用必须由**同一条线程**发起（建议：服务端主线程），
> 或由调用方自己用一把锁串行化。`cava_open`/`cava_close` 同此约束。
> **违反的后果**：不会立刻崩，但可能出现上述 `CAVA_ERR_ARG` 抖动，且**任何并发正确性都不在
> 本 ABI 的保证范围内**。

- 成本估计：**约 0.5 人日**（一句契约 + 一条运行期计数已经在 `hardeningReport()` 里了 + 一次 7 天跑读数）。
- 优点：不碰热路径、零性能代价；把“锁在 Java 侧”这个既成事实合法化。
- 风险：**把责任推给调用方** —— 所以必须同时保留 §4.3 的运行期计数作为证据。

### B. 在原生侧加细粒度锁 / 快照（**不建议本轮做**）

- 做法：把 `g_mutex` 从“局部加锁”扩展到整个入口，或让表替换走 RCU/双缓冲。
- 成本估计：**3–8 人日**（要动 `native/src/entity/cava_entity_abi.cpp` 与 `pathfind/cava_pf_abi.cpp` 的
  共享状态，还要重跑三份产物的 200k 用例 + 数值一致性套件），**且每次调用多一次全局锁开销**。
- 结论：**性能是本项目核心指标，而 0.38% 的 ABI 拒绝在单线程调用下根本不会出现** ⇒ 不值得。

### C. 什么都不做，只留运行期计数（**最省，但不合规**）

- 成本 **0.1 人日**；缺点是契约里仍然没有线程模型，下一个读代码的人会重新踩一遍。

---

## 6. 未验证 / 做不到

1. **真实服务端上的并发**：本轮的 8 线程压力全部在**独立驱动进程**里造的（`cava_fuzz_abi.exe` 直连 DLL）。
   **没有**在真实服务端里造出第二条调用线程（那需要一个会异步寻路的 mod，本整合包没有）。
2. **TSan（ThreadSanitizer）**：本机 MinGW 工具链没有可用的 TSan 运行库（与 ASan/UBSan 同一个限制，
   见 `docs/CAVA-hardening-notes.md` §7 第 5 条）⇒ **没跑**。
3. **`cava_open`/`cava_close` 的并发**：本轮的并发阶段只覆盖 upload/read/path/resolve/clear 六个入口，
   `open`/`close` **没有**并发打（生产上它们只在启动/关闭时各一次）。
4. **`offThreadCalls` 的真实越线证据**：单测里是**故意**用第二条线程造的；
   真实服务器上**一次都没观测到**（因为今天确实只有主线程调用）。这条只有等装了异步寻路 mod 才可能亮。
5. **0.381% 这个数字的稳定性**：3 次运行是 80/99/99，**依赖调度**。它不是精确率，量级对（百次量级）。
6. **服务端 7 天跑的 `offThreadCalls`**：与“原生回退计数为 0”一样，本机没有 7 天时间窗（P4-A 已记）。

---

## 7. 复现命令（照抄即可）

```powershell
# 1) 三份产物 × 10 万用例 × 8 线程（默认就带多线程阶段）
pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -Cases 100000

# 2) 只跑“不并发”的那一半（对照）
pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -Cases 100000 -SkipBuild -NoThreads

# 3) 归因：只跑求解角色 / 求解+换表 / 全部（0% / 0% / 0.38%）
build\native-fuzz\cava_fuzz_abi.exe natives\windows-x64\cava.dll --cases 10 --roles 4  --log build\native-fuzz\logs\mt-c.log
build\native-fuzz\cava_fuzz_abi.exe natives\windows-x64\cava.dll --cases 10 --roles 12 --log build\native-fuzz\logs\mt-cd.log
build\native-fuzz\cava_fuzz_abi.exe natives\windows-x64\cava.dll --cases 10 --roles 15 --log build\native-fuzz\logs\mt-all.log
```

日志：`build/native-fuzz/logs/fuzz-{shipped,release,safe}.log`（末尾是 `mt-*` 与 `MT SUMMARY` 行）。
