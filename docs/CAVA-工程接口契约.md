# Cava 工程接口契约（**多代理并行开发的唯一共享契约**）

> 本文件由主代理（captain）维护。**任何子代理在改下列内容之前，必须先改本文件并 commit。**
> 契约之外的实现细节各自自由发挥；契约之内的一律不许自作主张。
> 数据来源一律以本机证据为准（映射表 / jar / 实测），推测必须标注「未验证」。

---

## 0. 并行开发拓扑（git worktree + 分支）

| 分支 | worktree | 负责流 | 主要写什么 |
| --- | --- | --- | --- |
| `main` | `J:\mc\Cava`（主工作区） | captain | 契约、合并、文档、最终验收 |
| `p0-build` | `.worktrees/p0-build` | 构建基建 | `build.gradle` / `gradle.properties` / `settings.gradle` / `.github` / `native/CMakeLists.txt` |
| `p0-native` | `.worktrees/p0-native` | 原生核心 | `native/src/**` / `native/tests/**` |
| `p0-java` | `.worktrees/p0-java` | Java 侧 | `src/main/java/cava/**` / `src/test/java/cava/**` / `src/main/resources/**` |

**文件所有权铁律**：一个文件在同一时刻只能由一个分支改。跨流需要的改动，写成「请求」交给 captain 或对应流的代理，不要自己越界改。

**提交纪律**（并行期强制）：
- 一律 **pathspec 形式**提交，不依赖暂存区：`git commit -m "..." -- <我的路径...>`。
- **不要用 `git add`**（除非紧接着立刻 commit 同一批路径，中间不跑别的命令）；**绝不** `git add -A` / `git add .` / `git commit -a`。
- 发现暂存区里有别人的文件：**不要提交、不要 reset 别人的东西**，只在报告里说明。
- **不要 push**、不要 rebase、不要 `git checkout` / `git stash` 别人的改动。
- 提交前先 `git status --short` 确认。

> **真实事故记录（2026-09-22）**：P0-B `git add` 了 15 个文件后，P0-D 用不带 pathspec 的 `git commit` 把它们一起提交了。
> 已用 `git reset --soft` 修正、零丢失 —— 这条纪律就是为此加的。

---

## 1. 命名与包结构（已冻结）

    cava/                        ← 模组根包（**不是 cava.modid**）
      Cava.java                  ← ModInitializer（唯一入口）
      CavaConfig.java            ← config/cava.json 的读取与默认值
      ffm/                       ← **所有 java.lang.foreign 调用只允许出现在这里**，禁止引用任何 MC 类型
        NativeLibrary.java       ← 解压 + 哈希校验 + System.load
        CavaBindings.java        ← MethodHandle 绑定（按 cava_abi.h 手写，不用 jextract）
        NativeStatus.java        ← OPEN / DISABLED_BY_FLAG / ABI_MISMATCH / LAYOUT_MISMATCH / LOAD_FAILED ...
        CavaNative.java          ← facade 单例：tryOpen() / available() / status() / close()
        Numeric.java             ← 饱和转换与位模式工具（唯一数值工具入口）
      mirror/                    ← 方块状态表 / 区段镜像 / 实体镜像
      mixin/                     ← Mixin 注入点（每个都带回退分支）
      fallback/                  ← 纯 Java 回退
      canary/                    ← 钩子金丝雀框架
      compat/                    ← 兼容层：让位/复刻决策、启动报告
      subsystem/                 ← 子系统注册表（pathfind / entity / redstone）
      parity/                    ← **差分测试的运行时部分**（黄金轨迹采集），必须能进生产 jar
      client/                    ← 客户端入口（保留，服务端不加载）
    src/test/java/cava/parity/   ← 单元层测试与离线比对器（不进 jar）

**Mixin 配置**：`src/main/resources/cava.mixins.json`（服务端，package `cava.mixin`）与
`cava.client.mixins.json`（客户端，package `cava.client.mixin`）；P0 阶段两个列表都为空。

**勘误（2026-09-22，由 P0-C 实测发现）**：契约最初写的是 `cava.native.ffm`，**这是非法的** ——
`native` 是 Java 保留字（JLS 3.9），不能做包名，javac 报 `错误: 需要<标识符>`。
**已定为 `cava.ffm`**（唯一合法且最短）。"所有 FFM 调用集中一处"这条纪律的验法改为：
`grep -rl "java.lang.foreign" src/main/java` 必须只命中 `cava/ffm/`。

---

## 2. 原生库 ABI（权威文件：`native/include/cava_abi.h`）

**唯一权威定义在头文件里**，本节只说纪律与 Java 侧的对应形状。

### 2.1 硬纪律

1. 结构体**只通过指针**跨边界；绝不按值传参、绝不返回结构体。
2. 允许的标量：`int8_t/int16_t/int32_t/int64_t/uint32_t/uint64_t/float/double/void*/int64_t 句柄`。
   **禁用 `long`**（Windows 上是 32 位）、**禁用裸 `char` 做数值**、**禁用 `long double`**。
3. `(指针, 长度)` 必须同源；被调用方保证不越界，越界请求返回 `CAVA_ERR_ARG`。
4. 数值一致性：**只有 `+ - * /` 与 `sqrt` 允许在原生侧参与可观测数值计算**。
   `sin/cos/tan/atan2/exp/log/pow` 一律留在 Java。MSVC 与 GCC 之间、甚至同编译器不同优化级别之间，超越函数都不保证逐位一致。
5. 编译参数固定：`-O2 -fwrapv -ffp-contract=off -fno-fast-math`（MSVC：`/O2 /fp:strict`）；
   整数溢出必须 wrap；**禁 `-march=native` / `-ffast-math` / `-Ofast`**。
6. Java 的 `(double)->int` 越界**饱和**，C++ 是 UB → 一律走 `cava_d2i_sat` / `cava_d2l_sat`。
7. 整数除法前必须保证除数非零。
8. 所有入口对「未初始化 / 句柄已释放 / 空指针」必须安全返回错误码，**绝不段错误**。

### 2.2 句柄生命周期

- `cava_open(params, &handle, &result)` → `CAVA_OK` 且 `handle != 0`；否则 `handle == 0`。
- `cava_close(handle)` 幂等：重复关闭或关闭 0 返回 `CAVA_ERR_NULL`，不崩溃。
- 所有后续子系统入口都必须先校验句柄有效（内部句柄表 + magic）。

### 2.3 布局自检（**这是防「JVM 段错误」的核心机制**）

每个结构体必须有 `cava_layout_<name>(uint32_t* hash, uint64_t* size, uint64_t* align)`，返回字段数。

`layout_hash` 算法（Java 侧必须一模一样地实现）：

    h = 0x811C9DC5
    for each field in declaration order:
        h ^= (uint32_t)(offset & 0xFFFFFFFF); h *= 0x01000193
        h ^= (uint32_t)(size   & 0xFFFFFFFF); h *= 0x01000193
        h ^= (uint32_t)((offset >> 32) & 0xFFFFFFFF); h *= 0x01000193   // 64 位字段也要覆盖
        h ^= (uint32_t)((size   >> 32) & 0xFFFFFFFF); h *= 0x01000193

`layout_hash_sum` = 所有导出结构体 `layout_hash` 的 **uint32 无符号加法**（回绕）。

> ⚠️ **实测到的固有弱点（2026-09-22，captain 记录，不要误用）**：上面这个公式**不区分字段数与"形状相同"的结构体**。
> 实测 `CavaPathRequest` / `CavaPathNode` / `CavaCollisionBox` 三者都只由连续的单 u32 字段构成，
> 于是 **layout_hash 完全相同（`0x250ECBE1`）**。即使把字段数喂进去也仍然碰撞（三者字段数分别为 6/6/6——是 6/6/6 中两个 6、一个是 6，三者**字段数与步长都一致**，故仍相同）。
>
> **这不构成安全漏洞**，因为真正拦住布局漂移的不是哈希，而是：
> 1. **原生侧逐字段 `(offset, size)` 全表比对**（`cava_layout_report` 返回的 `field_offsets/field_sizes`）；
> 2. **Java 侧对 C 编译器实测 offsetof 的断言**（`CavaLayouts.checkAgainstCAbi()`）；
> 3. `cava_open` 的 `layout_hash_sum` 只是**快速失败**的粗筛。
>
> **仍然要保留哈希**（它能抓住"少了一个结构体/多加了一个结构体"这类整体漂移），但**任何 parity 结论都不许只依赖哈希**。
Java 侧算出期望值填进 `CavaOpenParams.layout_hash_sum`；原生侧比较自己算出的值，
不等则 `cava_open` 返回 `CAVA_ERR_LAYOUT` 且 `handle = 0` → **整体回退纯 Java**。

**必须导出布局的源生结构体（P0 只有这 4 个，后续每加一个都要登记）**：

| 结构体 | 说明 |
| --- | --- |
| `CavaLayoutEntry` | 自检条目（本身也要自检） |
| `CavaLayoutReport` | 自检报告 |
| `CavaOpenParams` | open 入参 |
| `CavaOpenResult` | open 出参 |
| `CavaPathRequest` | P1 寻路入参（已冻结，**56 字节 / 13 字段**） |
| `CavaPathNode` | P1 寻路出参节点（已冻结，**32 字节 / 8 字段**） |
| `CavaMobProfile` | P1 生物档案（已冻结，**192 字节 / 18 字段**） |
| `CavaStateRecord` | 方块状态表条目（**20 字节 / 5 字段**） |
| `CavaCollisionBox` | 扁平 AABB（**24 字节 / 6 字段**） |

> **当前权威 `layout_hash_sum` = `0x6975CBF9`**（9 个结构体，MinGW g++ 15.2 / x86-64 实测；
> Java 侧独立重算得到**完全相同**的值）。任何结构体改动都必须两侧同时改并重跑两边的自检。

### 2.4 P1 寻路 ABI（**已冻结**，`CavaPathRequest` / `CavaPathNode`）

接口形状与常量在 `native/include/cava_abi.h` 里。要点：

- 坐标一律**世界坐标**；`g`/`f` 是 **float**，跨边界原样传位模式，**禁止中途提升为 double**。
- `cava_pathfind` 返回 `>0` 节点数 / `0` 无路径 / `<0` 错误码；**`cap` 不足返回 `CAVA_ERR_ARG` 且绝不部分写入**。
- **镜像侧 ABI（区段/方块状态推送）故意留到 P1 真正开工时冻结** —— 它必须由区段镜像与方块状态表的实际实现推导。
  在它冻结之前，P1 只允许先做「纯算法内核 + 逐位一致性验证」，不得自行发明镜像 ABI。

### 2.5 Java 侧 FFM 的坑（JDK 21 预览 API，**本机已逐条实测**）

证据来源：`spike/ffm/FfmProbe.java`（已实跑，见下表"实测输出"列）。
复现命令：
```powershell
$javac = 'C:\Program Files\Java\jdk-21\bin\javac.exe'
$java  = 'C:\Program Files\Java\jdk-21\bin\java.exe'
& $javac --release 21 --enable-preview -d out spike/ffm/FfmProbe.java
& $java --enable-preview --enable-native-access=ALL-UNNAMED -cp out FfmProbe
```

| 坑 | JDK 21 的实际形状 | 实测输出 |
| --- | --- | --- |
| 没有 `Linker.Option.critical` | 只有 `firstVariadicArg` / `captureCallState` / `captureStateLayout` / **`isTrivial`**（22 才改名 critical） | `Linker.Option has critical()? false` |
| `allocate` 的数值重载**不是**数组分配 | `arena.allocate(JAVA_INT, 10)` = 一个 int、值 10 | `byteSize() = 4` |
| 数组分配要显式 | `arena.allocateArray(JAVA_INT, 10)` | `byteSize() = 40` |
| **`Arena` 没有 `byteSize()`** | `byteSize()` 只在 `MemorySegment` 上 | `Arena has byteSize()? false` |
| **没有 `allocateFrom(String)`**（22 才有） | 用 `arena.allocate(len, 1)` + `setUtf8String` | `Arena has allocateFrom? false` |
| **没有 `setString` / `getString`**（22 才有） | JDK 21 是 **`setUtf8String(long, String)`** / `getUtf8String(long)`，**且没有 Charset 重载** | 编译期 `cannot find symbol: method setString(int,String)` |
| 端到端可用性 | `Linker.nativeLinker().defaultLookup()` + `downcallHandle` 正常 | `strlen("hello") = 5` |

**铁律**：任何 (指针, 长度) 必须同源（同一个 arena、同一个 layout、同一个 count）。

---

## 3. 子系统注册与金丝雀框架

`cava.subsystem.CavaSubsystem` 是唯一注册入口：

    public interface CavaSubsystem {
        String id();                     // "pathfind" / "entity" / "redstone"
        boolean nativeReady();           // 原生句柄有效且布局自检通过
        void   canaryProbe();            // 主动触发一次目标方法，看计数器有没有动
        long   canaryCount();            // 钩子命中次数
        void   disable(String reason);   // 失败回退：置为禁用并记录
        boolean enabled();
    }

- 每个钩子持有一个 `cava.canary.HookCanary`（`AtomicLong` 计数 + 一次性的 probe 目标）。
- **启动流程**：`Cava.onInitialize`（或 SERVER_STARTED）→ `CavaNative.tryOpen()` →
  失败则全部子系统 `disable`；成功则逐子系统 `canaryProbe()`，
  计数没动 → 该子系统 `disable` 并打 ERROR 日志（**不是崩溃**）。
- **回退语义**：`enabled() == false` 时钩子必须**完全不介入**（`@Inject` 里第一句就 `if (!enabled) return;`）。

---

## 4. 差分测试与黄金轨迹格式

### 4.1 开关

| 系统属性 | 默认 | 含义 |
| --- | --- | --- |
| `-Dcava.native.enabled` | `true` | `false` = 完全不加载原生库，纯 Java 路径 |
| `-Dcava.parity.trace` | 空 | 非空 = 黄金轨迹输出目录 |
| `-Dcava.parity.ticks` | `0` | >0 = 采满这么多 tick 后自动停服并落盘 |
| `-Dcava.parity.label` | 空 | 运行标签，写进 trace 头 |
| `-Dcava.parity.world.radius` | `8` | 世界哈希**固定扫描盒**的半径（区块数，覆盖 3 个维度）。两侧必须一致 |

### 4.2 黄金轨迹：**逐 tick 一行 NDJSON**（`<dir>/trace-<label>.ndjson`）

选 NDJSON 而不是二进制：可 diff、可 grep、CI 里可读，体积在 6000–20000 tick 量级可接受（gzip 后更小）。
**哈希一律 FNV-1a 64**（Java 与 C++ 都能逐位重现，不用 CRC/xxhash 之类依赖）。

第一行必须是头，字段固定：

    {"t":"h","v":1,"label":"...","native":false,"mods":"<modset 指纹>","mc":"1.20.4","cava":"0.1.0","seed":12345,"startTick":0}

之后每 tick 一行，**字段缺省即 null，但键必须都在**：

    {"t":"k","k":<tick序号>,"w":"<世界哈希 hex16>","e":"<实体状态哈希 hex16>",
     "p":"<寻路结果哈希 hex16>","bt":"<方块 tick 事件数>","nt":"<邻居更新事件数>",
     "x":"<可选扩展：子系统自检抽样哈希>"}

- `w`：对**固定扫描盒内的所有区块**的方块状态按 **(dim, x, y, z, stateId)** 排序后逐项 FNV-1a；**禁用对象身份**。
  **扫描盒必须固定、可复现**（默认：以原点为中心、半径 `-Dcava.parity.world.radius` 个区块，默认 8，覆盖 3 个维度；空段跳过）。
  **不要用"当前已加载区块集合"**：1.20.4 Yarn 没有公开的枚举 API（`threadedAnvilChunkStorage`/`loadedChunks` 都是私有字段），
  而且已加载集合本身会抖动 → 会产生假差异。两侧必须用同一个扫描盒（由系统属性固定）。
- `e`：每个实体按 (id, 类型注册名, x/y/z 的 **double 原始位模式**, motion 位模式, yaw/pitch 位模式, onGround)。
- `p`：本次 tick 内所有寻路调用的 (实体 id, 起点, 终点, 节点数, 逐节点坐标与 f 值位模式) 拼接哈希。
- **确定性前提（2026-09-22 由实测修正，见 `docs/CAVA-determinism-report.md`）**：
  固定种子、固定 `randomTickSpeed`、关自动保存、不依赖 wall-clock **都还不够**。
  实测结论：**关掉刷怪（`spawn-monsters/animals/npcs=false`）+ `sync-chunk-writes=false`** 之后，
  - 区块集合**完全确定**（四次运行都是 2047，单边区块 0）；
  - `poi` **0 差异**；
  - `region` 在 2025 个区块里**只差 3–4 个，且全部集中在出生点附近**，其余 2021 个逐字节确定；
  - `entities` 仍差 5 个 —— **实体层本质上不确定**，不能作为整服级逐字节比对对象。

  **因此比对规则**：
  1. 默认**排除 `entities/`**；
  2. 默认**排除出生点附近区块**（范围写进 trace 头，保证可复现）；
  3. **实体层的 parity 必须用脚本化合成场景**（固定数量/位置/属性 + 脚本驱动），不要用服务器自然刷怪；
  4. 任何"逐 tick 一致"的声明都必须注明**当时关掉了哪些 mod 与哪些 spawn 开关**。
  5. 整服层降级为**不变量断言**（TPS/MSPT/无异常/native 回退计数=0/不崩），**不再承诺逐 tick 世界哈希一致**。

### 4.3 一条命令跑出差异报告

    .\gradlew.bat parityDiff -Pcava.natives=off,on -Pcava.ticks=6000

（具体 Gradle 任务形态由 `p0-java` 流实现；离线比对器在 `src/test/java/cava/parity/TraceDiff.java`，
输出：差异为 0 时明确打印 `ZERO DIFF over N ticks`；有差异时打印首个差异 tick、子系统、坐标/实体 id。）

---

## 5. 兼容层（让位 / 复刻）

- **每一项重叠都必须有显式归属**：`native` / `mod` / `vanilla`。
- 选择**复刻**时才去关对方的 mixin 组（`custom.lithium:options`）；选择**让位**时**不要关**。
- 启动必须打印一张表：`检测到的 mod → 重叠点 → 归属 → 被禁用的子系统及原因`。
- `config/cava.json` 允许逐项覆盖为 `auto | native-first | defer`。

---

## 6. 文档 = 唯一的跨会话记忆

- 每完成一项，**把结论写回 `docs/` 下对应文档**（`CAVA-服务器模组清单.md` / `CAVA-hook-points.md` /
  `CAVA-platform-and-compat.md` / `CAVA-v1-plan.md`），**或**新建 `docs/CAVA-<主题>.md`。
- 未验证的一律写「未验证」。**禁止把推测写成结论。**
- 不要凭记忆写类名/方法名：一律查 `docs/CAVA-yarn-intermediary-map.md` 或本机映射缓存。
