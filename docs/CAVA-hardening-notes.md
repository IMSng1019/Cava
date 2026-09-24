# Cava 运行时加固（P4-A）—— 熔断 / 看门狗 / fuzz / SAFE / 回滚

> 本轮（P4-A）**全部在本机实测**，只用到 windows-x64。每条结论后面都有真实命令与真实输出；
> 做不到的一律写「未验证」或「做不到 + 卡在哪」。**本文不含任何编造的数字。**
>
> 授权路径：`src/main/java/cava/harden/**`（新建）、`src/main/java/cava/ffm/**`（在既有状态机上加熔断/看门狗，
> 未动 `CavaLayouts.java`）、`src/test/java/cava/{harden,ffm}/**`、`native/tests/fuzz/**`（新建）、
> 本文档、`tools/harden-*.ps1`。

---

## 0. 交付物一览

| 文件 | 作用 |
| --- | --- |
| `src/main/java/cava/harden/CircuitBreaker.java` | 熔断器：同入口连续硬失败 N 次 ⇒ 全局关闭 native（计数可查） |
| `src/main/java/cava/harden/CallWatchdog.java` | 看门狗：单次调用超阈值 ⇒ 记录告警 + 计数（**只观测**） |
| `src/main/java/cava/harden/NativeCallGuard.java` | 唯一包装层：熔断 + 看门狗 + 异常兜底 + 原生回退计数 |
| `src/main/java/cava/harden/HardenProbe.java` | 独立命令行探针（不依赖 MC / 不引用 FFM） |
| `src/main/java/cava/ffm/CavaNative.java` | **9 个包装方法全部改走 guard**；新增 `DISABLED_BY_BREAKER` 状态、`hardeningReport()`、banner 两行 |
| `src/main/java/cava/ffm/NativeStatus.java` | 新增枚举值 `DISABLED_BY_BREAKER` |
| `native/tests/fuzz/cava_fuzz_abi.cpp` | 已冻结 ABI 的坏输入覆盖（LoadLibrary 直连 DLL，guard page + 哨兵） |
| `native/tests/fuzz/build-fuzz.ps1` | 编驱动 + 编 release/SAFE 两份库 + 对三份产物跑同一套 + SAFE 断言探针 |
| `tools/harden-fuzz.ps1` / `tools/harden-breaker.ps1` / `tools/harden-rollback.ps1` | 三条可复现的验收脚本 |
| `src/test/java/cava/harden/*.java`、`src/test/java/cava/ffm/CavaNativeHardeningTest.java` | 27 条单测（含真实 DLL 集成） |

---

## 1. 熔断（circuit breaker）

### 做了什么

- **建在既有状态机之上，没有另造状态机**：熔断时把 `CavaNative.status()` 翻到
  `NativeStatus.DISABLED_BY_BREAKER`（新增枚举值），于是 `available()==false`、
  `bindingsIfOpen()==null`，**9 个包装方法都在第一行就返回 `-100`** —— 与"原生没打开"完全同一套语义，
  钩子本来就完全不介入。
- 计数**分入口**（`cava_pathfind` / `cava_resolve_move` / …），只有**连续**失败才累加，任何成功或"契约内的拒绝"都会清零。
- 阈值：`-Dcava.native.breaker.threshold`，**默认 5**，`<=0` = 关闭熔断（运维逃生口）。
- 日志纪律：**一次熔断只打 1 条 ERROR**，之后再失败 500 次也一条都不打（只计数）。
- 可查询：`CavaNative.hardeningReport()`（启动横幅里也打两行）。

### ★ 默认阈值 5 的理由（不是随手取的）

1. **用"连续"而不是"累计"**：单次失败在设计上就是"这一次回退原逻辑"，P1/P2 的实测运行里逐次回退是常态路径；
   偶发一次不该关掉整条原生路径。
2. **不能是 1 或 2**：那会把正常的边界回退误判成故障。
3. **不能是 50/100**：实测最热的入口 `cava_resolve_move` 一次运行被调用 95 万次、live 接管 14.4 万次，
   而 **errors 全为 0**（见 `docs/CAVA-gates.md` 的 P2 live 一节）。在这个量级上"连续 5 次失败"
   已经说明是系统性故障（ABI 漂移 / 镜像损坏 / 原生内部状态坏了）。
   此时继续调用只是在给一条**已经确定坏掉**的路径继续付 FFM 边界成本（实测净亏 ≈ +1.86 µs/次），
   并且把日志刷满。5 次 ≈ 0.0035% 的调用量：既立刻停手，又不被瞬时抖动误伤。

### ★★ 本轮最重要的一次修正：**"任何负返回码都算失败"是错的**（实测打红）

第一版策略是"rc < 0 就算一次失败"。结果被**既有的** `PathfindAbiTest` 当场打红：

    PathfindAbiTest > invalidProfileIsRejected() FAILED
    org.opentest4j.AssertionFailedError: height = NaN 必须 CAVA_ERR_ARG ==> expected: <-4> but was: <-100>
    [Test worker/ERROR]: 熔断：cava_mob_profile_upload 连续 5 次失败（最后一次 rc=-4） ⇒ 自动全局关闭 native…

那 5 次 `-4` 是测试**故意**喂的非法 profile，而 ABI 头文件明文要求这种输入返回 `CAVA_ERR_ARG`：
**它是一次"合法的拒绝"，不是故障。** 更严重的是 `cava_pathfind` 在
"状态表 / 区域 / 生物档案还没推"时也返回 `CAVA_ERR_ARG` —— 那是**启动预热期**的正常回答。
按旧策略，预热期连续 5 次请求就会把整个进程的原生路径**永久**关掉（假熔断，且不可逆）。

**修正后的两级策略（现在的实现）**：

| 类别 | 返回码 | 是否熔断 | 理由 |
| --- | --- | --- | --- |
| 抛异常（downcall 失败） | — | **是** | 调用根本没发生，不是"参数不对" |
| `CAVA_ERR_ABI_VERSION` `-1` | 硬 | **是** | 两侧 ABI 版本不一致 |
| `CAVA_ERR_LAYOUT` `-2` | 硬 | **是** | 结构体布局漂移（最大风险） |
| `CAVA_ERR_OOM` `-5` | 硬 | **是** | 原生侧内存耗尽 |
| `CAVA_ERR_INTERNAL` `-6` | 硬 | **是** | 原生内部状态坏了 |
| `CAVA_ERR_NULL` `-3` | 软 | 否 | 句柄形状/已释放/空指针 = **调用方输入问题** |
| `CAVA_ERR_ARG` `-4` | 软 | 否 | ABI 明文规定的合法拒绝；也是**预热期**的正常回答 |
| `CAVA_ERR_UNIMPLEMENTED` `-7` | 软 | 否 | 稳定的"这版没实现"，不是损坏 |

软失败**照样计数**（`breaker.softFailures()`），只是不熔断、不打 ERROR。
四个硬码在 `NativeCallGuard` 里有本地常量，并由
`CavaNativeHardeningTest#hardeningErrorCodesMatchTheAbi` 与 `CavaLayouts.CAVA_ERR_*` **对拍**，写错会红。

### 实跑证据

命令（脚本里的断言 + 真实输出）：

    pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-breaker.ps1

    | cava.native.enabled     : (未设置 → 默认 true)
    | breaker.threshold       : 5   (-Dcava.native.breaker.threshold)
    | watchdog.threshold      : 50.000ms   (-Dcava.native.watchdog.micros)
    | status                  : OPEN
    | --- A) 软失败：契约内的拒绝不熔断（真实原生调用，伪造句柄） ---
    |   ok: CAVA_ERR_NULL 是调用方输入问题（ABI 明文）⇒ 软失败，不许熔断
    |   ok: 软失败照样是真实原生调用
    |   ok: 软失败不打 ERROR
    | --- B) 硬失败：注入 CAVA_ERR_INTERNAL(-6)（模拟「原生自己坏了」）---
    | ERROR cava/native - 熔断：cava_pathfind 连续 5 次失败（最后一次 rc=-6） ⇒ 自动全局关闭 native（本进程内不再尝试任何原生调用，所有子系统按既有语义整体回退纯 Java）。阈值 -Dcava.native.breaker.threshold=5；计数见 CavaNative.hardeningReport()。
    |   ok: 连续 5 次硬失败后状态必须是 DISABLED_BY_BREAKER，实际 DISABLED_BY_BREAKER
    |   ok: 熔断只允许打 1 条 ERROR，实际 1
    | --- C) 熔断之后：连真实入口都不再尝试 ---
    |   ok: 熔断后 100 次调用必须全部返回 -100 = ERR_NATIVE_UNAVAILABLE
    |   ok: 熔断后原生尝试次数必须一动不动
    |   ok: 原生回退计数必须增长（这是上线验收要看的那个数），实际 102
    | [cava/native] 熔断[enabled=true threshold=5 tripped=true symbol=cava_pathfind consecutive=5 lastRc=-6 failures=5 softFailures=7 trips=1 errorEmits=1 suppressedAfterTrip=0 perSymbol={cava_pathfind=5}] 看门狗[...] attempts=12 unavailableCalls=102
    | HARDEN-PROBE: PASS
    exit=0   ERROR lines=1
    BREAKER CHECK: PASS

**真实 DLL 上的"必然失败"用例**（`src/test/java/cava/ffm/CavaNativeHardeningTest.java`）用了两种：

1. **伪造句柄** `0xDEADBEEF` → `CAVA_ERR_NULL`：50 次连打，**不熔断**（软失败），状态仍 OPEN。
2. **真 downcall 失败**：把请求段放在一个**已关闭的 Arena** 里 —— FFM 的 downcall 对入参做 liveness 检查并抛
   `IllegalStateException`（这正是包装层必须处理的真故障）。连打 5 次 ⇒ 状态变 `DISABLED_BY_BREAKER`、
   `errorEmissions==1`、`callFailureEmissions==1`（同入口异常只报一次），随后 500 次调用全部返回 `-100`、
   **原生尝试次数一动不动**（`attempts` 停在熔断那一刻），`unavailableCalls` 涨 500。

> **熔断是进程内单向的**，所以单测**不用生产单例**，而是 `CavaNative.newIsolatedForTest()`
> 拿一个不共享状态的实例；否则同一 JVM 里后面的原生测试会全部静默走回退路径 —— 那正是本项目
> 反复强调的"绿 ≠ 测过"。生产单例的接线由一条独立用例断言（`hardeningIsWiredIntoTheProductionSingleton`）。

### 单测

    .\gradlew.bat test --tests "cava.harden.*" --tests "cava.ffm.*" --console=plain --no-watch-fs
    cava.ffm.CavaNativeHardeningTest   tests=6  failures=0 errors=0 skipped=0
    cava.ffm.PathfindAbiTest           tests=6  failures=0 errors=0 skipped=0   ← 重新变绿（策略修正的直接证据）
    cava.harden.CallWatchdogTest       tests=8  failures=0 errors=0 skipped=0
    cava.harden.CircuitBreakerTest     tests=13 failures=0 errors=0 skipped=0

---

## 2. 看门狗（只观测，绝不改行为）

- 阈值 `-Dcava.native.watchdog.micros`（单位微秒，默认 50000 = **50 ms**，`0` = 关闭）。
- 超阈值 ⇒ 计数 + 一行 WARN（每入口上限 8 条，之后只计数）。**不重试、不改返回码、不触发回退、不阻塞**。
- 观测层自己出错也只计数：`instrumentationFailures()`（取时钟抛异常 / 写日志抛异常都算），
  **返回值仍然逐位等于原生返回值**。

### `System.nanoTime` 与"确定性"的划界

契约 4.2 第 5 条要求"不依赖 wall-clock / 线程调度"，指的是**不能让时间影响任何被观测的数值**。
本类的边界是：

    允许：Java 侧 (t1 - t0) 与阈值比较 -> 只增加计数器、只打一行 WARN。
    禁止：把 (t1-t0) 写进任何 MemorySegment / 任何 ABI 结构体 / 任何回退判定 /
          任何参与游戏数值计算的表达式。原生侧永远看不到时间
          （即 CAVA_OPEN_FLAG_DETERMINISTIC 的另一半）。

一句话的可证伪形式：**删掉整个看门狗，同一份输入跑出来的世界哈希必须一模一样。**
断言这条的用例是 `CallWatchdogTest#slowCallDoesNotChangeTheResultEvenWithAnInsaneClock`：
把时钟换成"每次跳 10 秒"的假时钟（阈值 1 ns ⇒ 每一次调用都判超时），
10 个返回码与"永不超时"的对照组**逐个相同**；连"取时钟本身抛异常"的用例也照样返回原生返回值。

阈值用 long 纳秒相减（292 年内不溢出）；假时钟回拨时按"未超时"处理并计入 `clockAnomalies()`，绝不改行为。

真实 DLL 上的对照实验：同一个 `cava_region_upload` / `cava_pathfind` 分别在
阈值 1 ns（每次都超时、有 WARN）与 1 小时（从不超时）的两个隔离实例上执行，**返回码完全相同**
（`CavaNativeHardeningTest#watchdogOverThresholdDoesNotChangeTheReturnCode`）。

---

## 3. fuzz：已冻结 ABI 的系统性坏输入覆盖（本轮最有价值的一项）

### 形态

`native/tests/fuzz/cava_fuzz_abi.cpp` + `build-fuzz.ps1`：

- **不用 CMake、不依赖 MC、不依赖 Java**：驱动用 `LoadLibrary` + `GetProcAddress` 直连 DLL，
  所以同一份驱动可以对**任意一份产物**跑（交付物 / 本地 release 重建 / SAFE 构建）。
- **输出缓冲区一律"容量恰好顶到 guard page"**：`VirtualAlloc` 分配 `n` 页可读写 + 1 页 `PAGE_NOACCESS`，
  让 `buffer + declared_capacity` 正好落在 guard page 起点。任何越界写 = 立刻 access violation。
  未使用的尾部填 `0xA5` 哨兵，调用后校验（连"在容量内多写了一点"也能发现）。
- **确定性伪随机**：xorshift64*，固定种子 `0x5EEDC0DE5EEDC0DE`（头行打印）。
  同一份种子 + 同一份 DLL ⇒ 同一批用例，可逐例复现。
- 每条用例打印 `family | 输入摘要 | -> 返回码 (错误码名)`，并统计
  `cases / crashes / undefined_returns / state_mutations / overruns / unexpected_ok`。

### 覆盖的维度（任务清单逐条对应）

| 维度 | 具体用例 |
| --- | --- |
| 越界坐标（含 INT_MIN/INT_MAX） | `tx/ty/tz`、`ref.block_x/y/z`、`region` 原点、`region_state_id_at` 查询坐标（±INT_MIN、±INT_MAX、±(INT_MAX-1)、30000000、-1） |
| NaN / ±Infinity | `max_range`、AABB 六个分量、`move_x/y/z`、`step_height`、`malus`（随机记录） |
| 超大 AABB（min/max 颠倒） | inverted / zero-size / negative-size / ±1e308 / NaN / ±Inf / min=+Inf,max=-Inf / 1e6 宽 |
| 零 / 负维度 | `dim_x/y/z` = 0 / -1 / INT_MIN / INT_MAX / 256³(>16M 上限) / 1×1×(1<<24) |
| count 与指针不同源 | `req->shape_count != ref_count`、`id_count != dim 乘积`、`ref_count` 传 0 但给指针、`event_cap` 与指针不匹配、`bit_words` 与 size 不自洽 |
| cap 不足 | `cava_pathfind` 的 `cap` = 0/-1/INT_MIN、以及**"真实节点数 - 1"且缓冲区恰好那么大**（guard page 验收） |
| 伪造 / 陈旧句柄 | 0、-1、1、256、257、258、1024、`0x100000001`（形状合法但代际陈旧）、`0xDEADBEEF`、INT64_MIN/MAX、负数 |
| 非法 flags / reserved 非 0 | `CavaPathRequest.flags/reserved0/1/2`、`CavaMoveRequest.flags/reserved0/1`、`CavaShapeRecord.reserved0`、`CavaMoveShapeRef.reserved0/1/2` |
| 形状记录自洽性 | `points_kind>1`、`bit_words` 不等于 `ceil(x*y*z/64)`、`point_offset/bit_offset` 越界、负 size、`1<<20`³ 巨大 size |

### 断言（验收口径）

1. **绝不段错误** —— 进程必须活着走到 `SUMMARY` 行；崩了脚本会看到 exit≠0 且没有 SUMMARY。
2. **绝不越界写** —— guard page + 哨兵双重检测（`overruns` 计数）。
3. **必须返回明确错误码** —— 返回码分类为 {合法(≥0)、ABI 错误(-1..-7)、**未定义**}；`undefined_returns` 必须为 0。
4. **失败调用不改变已有状态** —— 先建立合法基线，再打 1400 次坏调用，然后重读：
   - **区域**：合法 8×4×8（底层为实心地板）上传 → 读 4 个点 → 打完全部非法 dims/count/NULL/伪造句柄 clear
     → **重读 4 个点必须逐字节相同**（这是任务点名的"非法 region_upload 不改变已缓存区域"）；
   - **位移**：合法形状表 + 一次求解（基线 `delta=(0.1,0,0)`、events=1）→ 打 1400 次坏调用（含坏形状表上传）
     → 再求解，**返回码与 delta 的 double 位模式必须完全相同**；
   - **寻路**：合法状态表 + 区域 + 档案 → 基线 `nodes=5 hash=0xD95CE1918EFF78D3` → 同一轮坏调用
     → **节点数与节点字节 FNV-1a 必须相同**。

### 实跑结果（三份产物 × 200,467 例）

    pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -Cases 100000

    === fuzz [shipped] ===
      dll    = J:\mc\Cava\natives\windows-x64\cava.dll      (2855079 B)
      sha256 = 43129D92C13A4D3BD274802EDC6DF7008662464C20205CE232B5B60464F850EE
      build_id: cava 0.1.0 windows-x64 GNU 15.2.0 (C:/mingw64/bin/g++.exe) … safe=0
      entries=14  layout_sum=0x1C12265E  cava_open rc=0 handle=4294967297
      SUMMARY … cases=200467 crashes=0 undefined_returns=0 state_mutations=0 overruns=0 unexpected_ok=0
      RESULT: PASS
    === fuzz [release-rebuild] ===  cava_release.dll  711521 B  sha256 E7D839CEBA78B8E072963169274C3600…
      build_id: cava 0.1.0 win-x64 mingw-gcc-15.2.0 O2/fwrapv/ffp-contract=off safe=0
      cases=200467 crashes=0 undefined_returns=0 state_mutations=0 overruns=0 unexpected_ok=0  PASS
    === fuzz [safe-build] ===       cava_safe.dll     711521 B  sha256 6D7DF29B551244DC58A80B01CEDE6A0A…
      build_id: … safe=1
      cases=200467 crashes=0 undefined_returns=0 state_mutations=0 overruns=0 unexpected_ok=0  PASS
    OVERALL: PASS   (SCRIPT EXIT=0)

三条腿覆盖了**两套构建系统**：CMake 产物（shipped）与 g++ 静态配方产物（release-rebuild / safe-build）。
（前一轮同样的 100k 用例也跑过重建前的 natives 产物 `sha256 F79052CD…`（711521 B，同为 g++ 配方）：
`cases=200467 crashes=0 undefined_returns=0 state_mutations=0 overruns=0`，结论相同。）

基线/不变性/上限三条关键行（同一份日志）：

    cap              probe target=(5,0,5) range=16 reach=1 -> 5 node(s)
    cap              cap = nodes-1 = 4 (guarded buffer)                         ->   -4 (ARG)
    invariance       pathfind baseline nodes=5 hash=0xD95CE1918EFF78D3
    invariance       resolve baseline rc=0 delta=(0.1,0,0) events=1 overflow=0
    invariance       pathfind baseline identical after 1400 bad calls (nodes=5 hash=0xD95CE1918EFF78D3)
    invariance       resolve baseline bit-identical after the bad-call storm

**总用例数 = 601,401（三份产物各 200,467），崩溃 0，未定义返回 0，状态被改 0，越界写 0。**

复现命令（种子固定）：

    pwsh -NoProfile -ExecutionPolicy Bypass -File native/tests/fuzz/build-fuzz.ps1 -Cases 100000 -Seed 0x5EEDC0DE5EEDC0DE
    pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-fuzz.ps1 -Cases 100000 -SkipBuild

日志：`build/native-fuzz/logs/fuzz-{shipped,release,safe}.log`。

### ★ fuzz 驱动自己踩的坑（写下来免得后人重踩）

第一版驱动**当场把进程打成 access violation（0xC0000005）**，根因**不在原生库，在驱动自己**：
它给 `cava_state_table_upload` 传了 `box_count = (1<<20)+1`（在库的上限 `kMaxBoxes=1<<22` 之内），
而实际缓冲区只有 1 个 `CavaCollisionBox` —— 按 ABI 契约 2.1.3，**"指针与长度同源"是调用方的义务**，
被调用方只保证"不越过声明的长度"，不可能去验证指针背后的真实容量。

> **教训**：**fuzz 驱动必须先自己遵守契约**，否则测出来的是驱动自己的 UB。
> 现在所有"个数在库的上限之内"的用例都配了**等大的缓冲区**；只有"超过库上限"的用例才允许
> 用小块缓冲区（那种情况库在解引用之前就返回了 `CAVA_ERR_ARG`）。
> 这条也顺带验证了一件事：**库对 count 的上限检查发生在任何解引用之前**（超限用例全部干净返回）。

---

## 4. SAFE 构建行为实测：记录 + 返回错误码，**不 abort**

SAFE 构建（`-DCAVA_SAFE=1`）编成独立 DLL（**故意不写进 `natives/`**，那是共享输出目录），
然后用同一条驱动 + `--safe-probe` 故意触发断言（`cava_layout_report(NULL)`）：

    === SAFE assertion probe [release-rebuild] : cava_layout_report(NULL) ===
    | [safe-probe] calling cava_layout_report(NULL) ...
    | [safe-probe] cava_layout_report(NULL) -> -3 (NULL)
    | [safe-probe] process is STILL ALIVE after the call (no abort)

    === SAFE assertion probe [safe-build] : cava_layout_report(NULL) ===
    | [safe-probe] calling cava_layout_report(NULL) ...
    | [cava][SAFE] assertion #1 failed: (out != nullptr) at J:\mc\Cava\native\src\cava_layout.cpp:590 -> code=-3
    | [safe-probe] cava_layout_report(NULL) -> -3 (NULL)
    | [safe-probe] process is STILL ALIVE after the call (no abort)

**结论：与预期一致。** release 构建**静默**返回 `CAVA_ERR_NULL(-3)`；
SAFE 构建**多打一行 stderr**（`[cava][SAFE] assertion #1 failed: … -> code=-3`），
**返回同一个错误码、进程继续活着、没有 abort、没有异常**。
两份产物的 `build_id` 分别显示 `safe=0` / `safe=1`，且 SAFE 构建的 fuzz 全绿（见 §3）。

未覆盖：SAFE 断言只触发了 `cava_layout.cpp:590` 这一处（另有 `cava_handle.cpp:97/98` 两处
`out_handle/params != nullptr` 断言，需要绕过 Java 侧直接调 `cava_open`；本轮**未验证**）。

---

## 5. 回滚：一个 JVM 参数回到纯 Java

    pwsh -NoProfile -ExecutionPolicy Bypass -File tools/harden-rollback.ps1

（脚本自己 `gradlew compileJava`，然后用 `build/classes/java/main` + gradle 缓存里的
`slf4j-api`/`log4j-core` 拼 classpath，跑两腿 `cava.harden.HardenProbe`；JVM 属性走
`JAVA_TOOL_OPTIONS` —— 本机 PowerShell 5.1 会解析坏 `& java -Dk=v`。）

**A 腿：native ON**

    | INFO  cava/native - [native] System.load(…cava-f79052cd99e797d4.dll) 成功
    | INFO  cava/native - [cava/native] 布局自检通过：native_entries=14 java_sum=0x1c12265e native_sum(per-entry sum)=0x1c12265e
    | INFO  cava/native - [cava/native] cava_open → sent_sum=0x1c12265e rc=CAVA_OK … handle=4294967297
    | status                  : OPEN
    | available()             : true
    | pathfind(handle=4294967297) -> -4
    counts: INFO=7 WARN=0 ERROR=0

（INFO 有输出 ⇒ 日志通道是活的，"零 ERROR"才有意义。）

**B 腿：`-Dcava.native.enabled=false`（回滚）**

    | INFO  cava/native - [cava/native] -Dcava.native.enabled=false（或 config native.enabled=false）：按设计不加载原生库 —— 这是正常路径，走纯 Java
    | status                  : DISABLED_BY_FLAG
    | available()             : false
    | detail                  : -Dcava.native.enabled=false（或 config native.enabled=false）：按设计不加载原生库
    | pathfind(handle=0) -> -100
    | [cava/native] 熔断[enabled=true threshold=5 tripped=false failures=0 …] 看门狗[…] attempts=0 unavailableCalls=1
    counts: INFO=1 WARN=0 ERROR=0

    8/8 断言通过：exit=0、status=DISABLED_BY_FLAG、available()=false、P1 入口返回 -100、ERROR=0、
    HARDEN-PROBE: PASS ⇒ **ROLLBACK CHECK: PASS**

注意 B 腿里**没有** `System.load` 那行 —— 库根本没被解压/加载；而且 `unavailableCalls=1`
正是"原生回退计数"（P4 上线验收要看的那个数）。

### 可执行的回滚步骤（含"怎么确认成功"）

1. 停服。
2. 启动命令加**一个**参数：`-Dcava.native.enabled=false`
   （等价做法：`config/cava.json` 里 `"native": {"enabled": false}`）。
3. 起服。
4. **成功判据（必须全部满足）**：
   - 日志出现 `[cava/native] -Dcava.native.enabled=false…这是正常路径，走纯 Java`；
   - 横幅里 `native 状态 : DISABLED_BY_FLAG`、`回退语义 : 整体回退纯 Java（所有钩子不介入）`；
   - 横幅里 `熔断 : … tripped=false`、`attempts=0`；
   - **没有** `[native] System.load(` 行（连库都不解压）；
   - **没有任何 `[cava/native]` ERROR 行**。
5. 机器校验：`pwsh -NoProfile -File tools/harden-rollback.ps1 -SkipBuild` ⇒ `ROLLBACK CHECK: PASS`。
6. 回到原生：删掉那个参数并重启（`tryOpen()` 是一次性的，**不支持运行期来回切**）。

---

## 6. 门禁现状：`gradlew build` 绿、`gradlew test` 绿、**ctest 本来就红（与我无关，根因已定位）**

    .\gradlew.bat build --console=plain --no-watch-fs
    BUILD SUCCESSFUL in 7s     GRADLE BUILD EXIT=0
    (test 任务：229 tests completed, 0 failed, 14 skipped —— 含本轮新增的 27 条)

    cava.ffm.CavaNativeHardeningTest tests=6 failures=0 errors=0 skipped=0
    cava.ffm.PathfindAbiTest         tests=6 failures=0 errors=0 skipped=0   ← 真的跑了（没 skip）

### ctest：**本轮开始前就是红的**（两处 P0 期硬编码，与加固改动无关）

第一次跑（**我还没碰过任何 native 文件、也没重新配置过原生构建**）：

    & ctest --test-dir build/native-captain -C Release
    25% tests passed, 3 tests failed out of 4
      1 - cava_dll_loadtest (Failed)
      3 - cava_pathfind_vectors (Exit code 0xc0000139)     ← 该 exe 是 20:37 的旧产物
      4 - cava_selftest (Failed)                            ← 120 passed / 10 failed

重建（`cmake --build build/native-captain --parallel` + 把 `natives/windows-x64` 放进 PATH，
CMakeLists 第 41 行的注释就是要求这个）之后：

    ctest --test-dir build/native-captain -C Release
    50% tests passed, 2 tests failed out of 4
      1 - cava_dll_loadtest (Failed)
      4 - cava_selftest (Failed)
    2 - cava_fp_probe, 3 - cava_pathfind_vectors : Passed

两项失败的**根因是同一件事**（`native/tests/` 里 P0 期的硬编码没跟上已冻结的 14 结构体 ABI）：

    native/tests/cava_dll_loadtest.cpp:113   check(n == 9, "cava_layout_report 返回 9（P0 的 4 个 + P1 的 5 个）");
    native/tests/cava_dll_loadtest.cpp:115   for (i < n && i < 9) { sum += … }        ← 只累加前 9 个
    native/tests/cava_selftest.cpp           "[FAIL] cava_layout_report 返回 14（期望 9）"
                                             "[FAIL] report.entry_count=14（期望 9）"
                                             "[FAIL] layout_hash_sum=0x6975cbf9 == Java 侧对齐值 0x1c12265e（9 条 entry 的和）"
                                             "[FAIL] 正确 layout_hash_sum -> status=-2 handle=0x0"
    （物理布局类断言全部通过：size/align/offsetof/逐字段/hash 都 ok）

⇒ 这两个测试把**旧的 9 结构体和值 0x6975CBF9** 发给 `cava_open`，库（正确地）以
`CAVA_ERR_LAYOUT` 拒绝。**这是"测试的期望值过期"，不是 ABI 或实现的问题。**

**为什么我没有修**：`native/tests/*.cpp`、`native/tests/CMakeLists.txt`、
`native/tests/vectors/layout_expected.txt` **都不在本轮授权路径**（我只被授权 `native/tests/fuzz/**`）。
建议由 owner（P0-B / captain）做最小修复：把 `9` 改成从 `cava_layout_report` 的返回值取，
并重新生成 `layout_expected.txt`（`cava_selftest --dump-layout`）。

### ★★ 一个必须上报的副作用：我重建原生测试时**覆盖了交付物 DLL**

`cmake --build build/native-captain` 会按 CMakeLists 的约定把 `cava` target 输出到
`<repo>/natives/windows-x64/cava.dll`（**共享输出目录** —— gates 文档警告过这一点）。实测：

    重建前: 711521 B   sha256 F79052CD99E797D45D79C724E3C5690A…  build_id "… windows-x64 GNU 15.2.0 …"
    重建后: 2855079 B  sha256 43129D92C13A4D3BD274802EDC6DF7008662464C20205CE232B5B60464F850EE

两份都是**全静态**（导入表只有 `KERNEL32.dll` + `msvcrt.dll`，实测 objdump），
都来自同一份源码、都报 `entries=14 / layout_sum=0x1C12265E`，fuzz 都全绿；
差别只是构建系统（CMake vs `build-mingw.ps1` 的 g++ 配方）与体积。
**`gradlew build` 会把这个文件打进 jar**，所以 captain 如果要求交付物是 g++ 配方那一份，
重跑 `native/tests/build-mingw.ps1`（或 `gradlew buildNative` 取 CMake 那一份）即可。

---

## 7. 未验证 / 做不到的部分（诚实清单）

1. **7 天连续运行**：做不到 —— 需要真实整合包服务端连续跑 7 天，本机没有这个时间窗。
   **能做的是**：`unavailableCalls`（原生回退计数）+ `hardeningReport()` 已经在横幅里，
   7 天跑的判据就是它恒为 0。
2. **崩馈取证（hs_err 解析）**：**本轮未做**（不在 P4-A 的 6 项清单里，任务书把它列在 P4 的
   "崩溃取证"条目下）。未验证。
3. **非 windows-x64 平台**：加固层是纯 Java（不含 FFM、不含平台相关代码），
   理论上平台无关，但 **arm64/macOS/linux 一行都没跑过** —— 未验证。
4. **fuzz 的"区段并发卸载"与"调色板损坏"**：任务书 P4 里提到这两项。
   本轮 fuzz 覆盖的是**镜像侧 ABI 的输入**（region/state/shape 上传的坏参数与状态不变性），
   **没有**覆盖"Java 侧镜像在并发下卸载区段"的真实竞态（那需要真实服务端 + 并发推送）。
   未验证。
5. **ASan/UBSan**：本机 MinGW 工具链没有可用的 ASan 运行库，**没跑**。
   README/CI 里要跑的话归 P4-B。
6. **看门狗的真实慢调用**：本机原生调用都是微秒级，**从未见过自然超阈值**；
   "超阈值 ⇒ 告警"这条只在假时钟与阈值 1 ns 下验证过（`slow=0` 是真实运行的读数）。
7. **熔断的"真故障"注入**：用"关闭的 Arena"制造 FFM downcall 异常（真实、可复现）；
   **没有**制造过一次真实的原生 `CAVA_ERR_INTERNAL`（那需要往库里注入故障）。
