# Cava v1 技术方案

**目标**：在不改变原版 1.20.4 行为的前提下，用 Java 21 预览版 FFM（java.lang.foreign）调用 C++，把三个热点子系统（生物寻路 / 实体开销 / 红石）的内层循环搬到原生侧。
**载体**：Fabric mod（已确认，不做服务端 fork），基于模板 cava-template-1.20.4.zip。
**平台**：全平台（Windows / Linux / macOS × x64 / arm64）。
**状态**：方案草案。本机已完成可行性验证，所有关键数字均为实测，不是估计值。

---

## 0. 结论先行

1. **技术路线可行**。我已经在本机跑通 "Java 21 --enable-preview + FFM + 本地编译的 C++ 动态库" 的完整链路，MinGW G++ 15.2 与 MSVC 14.44 两条工具链都验证通过，并拿到了边界开销的真实数字。
2. **三条硬约束必须先接受**：
   - **JDK 被锁死在 21.0.x**。预览版 class 文件版本是 65.65535；不加 --enable-preview 直接 UnsupportedClassVersionError，而 JDK 22 即使加了 --enable-preview 也拒绝加载（实测报错：only recognizes preview features for class file version 66.65535）。
   - **超越函数不能跨边界**。实测 sin / cos / tan / atan2 / exp / log / pow 没有任何一套 libm 能和 Java 逐位一致（含 1 ulp 级差异，且 Math.* 与 StrictMath.* 本身就不相等）。逐位安全的只有 + - * / 和 sqrt。方案按"三角函数留在 Java"设计，三个子系统都能满足。
   - **收益来自数据布局，不是语言本身**。真正的加速来自把"对象图 + 每次查询 new 对象"换成"扁平数组镜像 + 批量跨边界调用"；C++ 只是这个镜像的承载者。如果只把 Java 逻辑逐行翻译成 C++，大概率更慢。
3. **决定成败的是等价性（parity）工程，不是 C++ 代码量**。本方案把接近一半的工程量放在差分测试、黄金轨迹和数值纪律上。红石和实体语义一旦有微小偏差，机器就会失灵、生物就会抽搐，而这种偏差靠人眼几乎发现不了。

---

## 1. 需求解读与范围界定

| 你的要求 | 我的解读 | 落实方式 |
| --- | --- | --- |
| 1.20.4 的 Java 时期 | Java 21（1.20.4 时代的运行时基线，也是 FFM 预览版的最后一个版本） | 编译与运行统一加 --enable-preview，JDK 固定 21.0.x |
| 使用预览版的功能 | java.lang.foreign 在 Java 21 是第三预览版（JEP 442） | javac --release 21 --enable-preview；java --enable-preview --enable-native-access=ALL-UNNAMED |
| 把原版代码重写为 Java 版 | 不需要从零重写游戏。Loom 已经能把 1.20.4 反编译成带 Yarn 名字的可读 Java 源码，它就是**规格说明书 + 回退实现** | 只重写三个子系统的内层循环，其余原版代码一行不动 |
| 逻辑与原版相同 | 逐位一致（bit-exact）+ 逐 tick 差分验证 | 见第 5 节等价性工程 |
| 第一版范围 | 生物寻路 / 实体开销 / 红石 | 见第 4 节，按 P1 → P2 → P3 串行推进 |

**非目标（v1 明确不做）**：区块生成、光照引擎、网络同步、AI 决策层（GoalSelector / Sensor / Brain）、属性与存档、客户端。这些东西的收益往往比寻路更大，但风险面也更大，留到 v2 再评估。

**载体已确认：Fabric mod**（不做服务端 fork）。理由：① 可以运行时开关 native 做 A/B 差分测试（这是本项目的命脉）；② 不需要分发 Mojang 反编译代码；③ 直接在你现有的 1.20.4 服务端上迭代。全平台构建与 mod 兼容的完整设计见配套文档 **docs/CAVA-platform-and-compat.md**。

---

## 2. 已完成的可行性验证（全部为本机实测）

### 2.1 环境现状

| 项目 | 状态 |
| --- | --- |
| 工作区 | J:\mc\Cava，Fabric 模板（Loom 1.18-SNAPSHOT / MC 1.20.4 / Yarn 1.20.4+build.3 / Gradle 9.7.1） |
| 现有代码 | 只有 4 个示例类，无任何 C++ 代码，Java 目标版本仍是 17 |
| JDK | 21.0.10（JAVA_HOME）、17、22、8 均已安装 |
| C++ 编译器 | MSVC 14.44（VS 2022 BuildTools）+ MinGW-w64 GCC 15.2（C:\mingw64） |
| 缺失 | **CMake 与 Ninja 未安装**（P0 必须先补上） |
| 构建阻断 | GRADLE_USER_HOME 指向 J:\mc\mods\.gradle-home（工作区之外），当前被文件沙箱拒绝写入，gradlew build 直接失败。需要放行该目录或把 GRADLE_USER_HOME 改到工作区内 |

### 2.2 FFM 边界开销（决定架构的关键数字）

JDK 21.0.10 / Windows x64，每项 2000 万次循环，取预热后稳定值：

| 操作 | 实测 | 对架构的含义 |
| --- | --- | --- |
| 普通 downcall（2 个 int） | 14.4 – 15.1 ns | 一次跨界 ≈ 15 ns |
| downcall（指针 + 3 个 double） | 15.6 – 16.3 ns | 参数变多几乎不增加成本 |
| upcall（C++ 回调 Java 静态方法） | 25.8 – 29.2 ns | 比我预期的便宜，事件回放式回调完全可行 |
| Arena.ofConfined + 4 KiB 分配 | ~140 ns | 每次查询建 arena 可以接受，每个节点建就不行 |
| MemorySegment.copy（64 KiB） | 105 – 110 GB/s | 镜像批量上传几乎免费 |
| 原生读取 400 KB | 24.6 µs / 次（16.3 GB/s） | 受内存带宽限制，不是边界限制 |

**推论（写进代码规范）**：每次跨边界调用必须携带 ≥ 1 µs 的工作量（约 60 倍边界成本），目标 ≥ 10 µs。
- 禁止：按方块、按路径节点、按 AABB、按红石元件跨边界。
- 允许：每实体每 tick 1–3 次；每次寻路 1 次；每次红石级联 1 次；每 tick 1 次镜像上传。
- 量化参照：1 万实体 / tick，即使每实体 3 次调用也只有 3 万次 × 16 ns ≈ 0.5 ms，安全。

### 2.3 逐位一致性实测（本方案最重要的发现）

每项 20 万随机样本，统计与 Java 结果**位模式不一致**的比例：

| 函数 | MSVC(ucrtbase) vs Java Math | MinGW(msvcrt) vs Java Math | Math vs StrictMath |
| --- | --- | --- | --- |
| sqrt | 0 | 0 | 0 |
| sin（[-π,π]） | 3.10%（最大 1 ulp） | 0.14%（最大 4 ulp） | 3.38% |
| cos（[-π,π]） | 3.34% | 0.13% | 3.35% |
| sin（\|x\|≈1e7） | 3.13%（1 ulp） | **98.2%**（彻底崩坏） | 3.40% |
| tan | 4.60% | 4.56% | 3.67% |
| atan2 | 17.72% | 17.72% | 0% |
| exp | 0.53% | 17.5% | 9.5% |
| log | 0.004% | 0.024% | 0.93% |
| pow | 0.036% | **49.0%** | 4.87% |

**结论**：
- **加、减、乘、除、sqrt 是逐位安全的**（sqrt 由 IEEE 强制正确舍入，实测 0 差异）。几何类内核（碰撞盒、距离、AABB 求交）可以放心搬到 C++。
- **所有超越函数都不安全**。而且注意 Math.sin ≠ StrictMath.sin，说明 HotSpot 对 Math.* 做了 intrinsic（走平台 libm），因此"把 fdlibm 移植到 C++"也不能解决问题——除非连 Java 调用点一起改。唯一稳妥的做法是：**超越函数不出边界**。
- 好消息是三个目标子系统都能满足这条边界：寻路只用到 sqrt 和整数；实体碰撞/位移只用 AABB 四则运算；红石是纯整数。真正需要三角函数的地方（生物朝向、移动向量合成、部分 AI）仍然留在 Java。
- 附带发现：MinGW(msvcrt) 的 libm 质量明显差于 MSVC(ucrtbase)（pow 49% 不一致、大参数 sin 98% 崩坏）。**即使不搬超越函数，也不要用 MinGW 的老 CRT 做数值相关模块**；生产环境统一 Linux GCC 构建。

### 2.4 编译开关实测

| 现象 | 实测结果 |
| --- | --- |
| -march=haswell -ffp-contract=fast 下计算 a*b+c | **25.5% 的样本与 Java 位模式不一致**（FMA 融合导致） |
| 基线 x86-64 或 -ffp-contract=off | 0% 不一致 |
| 不加 -fwrapv，计算 (x+1 > x) 在 INT_MAX | GCC 常量折叠为 true，**Java 是 false** |
| 加 -fwrapv | 与 Java 一致 |

**规则**：C++ 侧固定使用 -O2 -fwrapv -ffp-contract=off -fno-fast-math（**禁止 -march=native**）；MSVC 用 /O2 /fp:strict。这些不是"最佳实践建议"，而是实测出来的位一致必要条件。

**跨平台补充规则（因为要支持全平台）**：禁 long double（x86 80 位 / arm64 128 位 / MSVC 64 位）；禁裸 char 参与数值（x86 signed、ARM unsigned）；禁裸 long（Windows 32 位 / Unix 64 位）；结构体一律传指针、不按值传递；**double→int 必须自研饱和转换**（Java 规范是 NaN→0、越界饱和，C++ 是 UB——vanilla 的 Mth.floor(double) 直接照搬会在大坐标上发散）；整数除法前保证除数非零。完整 14 条清单见 docs/CAVA-platform-and-compat.md 第 1.2 节。

### 2.5 预览版锁定（部署硬约束）

| 场景 | 结果 |
| --- | --- |
| 编译产物 | class 文件 minor=65535, major=65 |
| 运行时不加 --enable-preview | UnsupportedClassVersionError |
| JDK 22 + --enable-preview | 拒绝加载（预览特性版本不匹配） |

**含义**：这个服务端必须永远跑在 JDK 21.0.x 上。启动脚本、Docker 镜像、CI 全部要固化 JDK 版本。其它 mod 不受影响，但整个 JVM 必须带 --enable-preview。FFM 调用代码要集中在一个 facade 包里，将来若迁移到 JDK 22+（FFM 转正、无需预览标志），只需改这一处。

### 2.6 已经踩到并复现的坑（写进规范）

1. **JDK 21 的 FFM API 和 JDK 22 不一样，照抄 Java 22 的写法会出人命**：
   - Java 21 没有 Linker.Option.critical（Java 22 才有），所以每次 downcall 都有线程状态切换成本；
   - Java 21 的数组分配是 arena.allocateArray(layout, count)；而 allocate(ValueLayout.JAVA_INT, 10) 是"分配**一个** int，值为 10"（4 字节），不是 10 个元素；
   - Arena 没有 byteSize()。
2. **上面那条错误的真实后果我已经复现**：Java 侧以为分配了 400 KB，实际只有 4 字节，原生代码照读 400 KB → 越界 → **JVM 进程直接段错误崩溃**。Java 的边界检查只保护 Java 侧访问，对原生侧毫无保护。
   由此定下第一条铁律：**任何 (指针, 长度) 必须同源**——长度要么由同一侧计算，要么在入口显式校验；SAFE 构建里所有数组访问都带边界检查。
3. **方法名勘误**：本方案早期版本里的 findPath / RedstoneWireBlock.getPower / Entity.collide / EntityView.getEntities 都是 1.20.5+ 或 Mojang 名字，在 1.20.4 Yarn 上会导致 mixin remap 失败。正确名字已用本机官方映射逐条核对，见 docs/CAVA-hook-points.md。

---

## 3. 总体架构

### 3.1 三层结构

    ┌─────────────────────────────────────────────────────────┐
    │ 第 1 层：Java（原版逻辑，基本不动）                       │
    │  世界模型 / AI 决策 / 方块回调 / 存档 / 网络 / 渲染无关    │
    │  · 保留原版实现作为回退路径（native 关闭时行为完全等于原版）│
    └───────────────▲──────────────────────┬──────────────────┘
                    │ 批量结果 / 事件回放   │ 批量指令（每 tick 数次）
    ┌───────────────┴──────────────────────▼──────────────────┐
    │ 第 2 层：镜像层（Mirror，性能的真正来源）                  │
    │  方块状态表（全局不可变） / 区段镜像（16³ palette 压缩）    │
    │  实体镜像（SoA 扁平数组） / 红石线网图                     │
    └───────────────▲──────────────────────┬──────────────────┘
                    │                      │
    ┌───────────────┴──────────────────────▼──────────────────┐
    │ 第 3 层：C++ 内核（无 Java 对象引用，自有 arena）          │
    │  A* 寻路 / 碰撞与位移 / 实体 broadphase / 红石线网重算     │
    └─────────────────────────────────────────────────────────┘

### 3.2 跨边界批处理规则

由 2.2 的实测数字推出，写进代码评审清单：
- 原生侧不得回调 Java 取方块数据（upcall 27 ns × 每节点数次 = 直接吃掉全部收益）；所有世界数据必须来自镜像。
- 回调只用于"事件回放"：原生算出"碰到了哪些方块/实体"，把位置列表返回，由 Java 按原版顺序执行虚方法回调（保持 mod 与方块语义）。
- 每个原生入口都必须能返回错误码，Java 侧据此回退原版逻辑并计数；连续失败自动熔断。

### 3.3 三类镜像（核心设计）

**A. 方块状态表（全局、不可变、约 2.7 万个状态）**
对每个 BlockState 预计算并常驻原生内存：
- pathType[profile]：按生物通行档案（陆地/水生/飞行/两栖 × 开门/穿门/浮水能力）预计算的字节；
- 碰撞盒：扁平 float 数组 + 每状态的 (offset, count)，直接复刻 VoxelShape 的 AABB 集合（注意：原版碰撞用 AABB 集合而不是体素栅格，用体素近似会立刻产生行为差异）；
- 红石属性：kind 字节 + 是否信号源 + 六向强弱信号 + 比较器输出；
- isSolid / blocksMotion / 流体 / 硬度等常用标志。
这张表只随数据包/注册表重载而重建，可以预生成为二进制资源随 jar 发布，避免每次启动重建。

**B. 区段镜像（每 16³ 区段）**
- 保留原版调色板压缩形式：palette[256] + 4/8 bit 索引（多数区段约 2 KB）；
- 按需派生并缓存：pathType[4096]、碰撞体素位图 uint64[64]（512 B）、红石 kind/power 字节数组；
- 更新策略：hook 方块写入路径，**立即**同步到镜像（红石要求同 tick 可见），同时把派生数组标脏、惰性重算；
- 生命周期：区段加载/卸载与镜像同步（卸载必须显式通知原生侧，否则悬空索引就是下一次崩溃）。

**C. 实体镜像（SoA）**
id / 类型档案 / 位置(3×double) / 速度(3×double) / 碰撞箱(w,h) / 标志位，约 80 字节/实体。每 tick 一次性打包上传：1 万实体 ≈ 800 KB ≈ 50 µs（占 50 ms tick 的 0.1%），完全可接受。也可以做写穿更新，但没必要过早优化。

### 3.4 C ABI 纪律

    #define CAVA_ABI_VERSION 1
    int32_t cava_abi_version(void);                       // 加载后必须校验
    Cava*   cava_create(const CavaConfig*);
    void    cava_destroy(Cava*);
    // 所有入口：返回 >=0 成功，<0 错误码（Java 侧回退原版实现）

- 只用 extern "C"、POD 结构体、不透明句柄；禁止 std::string/vector/异常跨边界。
- 内存归 Java 侧 arena 所有；原生侧不得持有 Java 堆引用。
- 服务器线程单线程调用（MC 世界逻辑本身就是单线程），因此不需要锁；但区段卸载可能来自其它线程，必须走队列。
- SAFE 构建（CAVA_SAFE=1）打开全部边界检查与断言；release 构建依赖不变量换取速度。

### 3.5 建议的目录结构

    src/main/java/cava/
      native/ffm/        ← 所有 java.lang.foreign 调用集中在这里（JDK 21 预览 API 专用）
      mirror/            ← 方块状态表 / 区段镜像 / 实体镜像
      hook/              ← Mixin 注入点（每个都带回退分支）
      fallback/          ← 纯 Java 回退（直接调原版方法）
    src/test/java/cava/parity/   ← 差分测试与黄金轨迹
    native/
      include/cava.h     ← C ABI 唯一定义处
      src/pathfind/  src/entity/  src/redstone/  src/common/
      tests/             ← C++ 单元测试 + fuzz 目标
    native/CMakeLists.txt
    tools/gen-blocktable/  ← 生成方块状态表二进制

---

## 4. 子系统方案（按实施顺序）

> 每个注入点的准确类名/方法名/中介名，以及其它 mod 在同一方法上的重叠情况，见 **docs/CAVA-hook-points.md**。下面提到的类名方法名均已用本机 Yarn 1.20.4 映射核实。

### 4.1 P1 生物寻路（第一个做，性价比最高）

**原版链路（Yarn 1.20.4 名称，已核实）**
EntityNavigation.tick() → 定期重算 → PathNodeNavigator.findPathToAny(...)（method_52 的 Set 版 / method_54 的 Map 版）→ PathNodeMaker 的子类（LandPathNodeMaker / BirdPathNodeMaker / WaterPathNodeMaker / AmphibiousPathNodeMaker）提供 getStart / getNode / getNeighbors，开放集用 PathMinHeap 排序，产出 Path（PathNode 列表）；PathNodeType 决定每格通行代价（malus）。

**为什么它最适合打头**
- 逻辑自洽：整条链路不含超越函数，只用 sqrt 与整数/float 代价运算，天然满足逐位一致；
- 收益集中：原版每个节点都要经 Int2ObjectMap 装箱存取、每次查询 new 一堆 PathNode，GC 压力大；扁平节点池 + 局部坐标索引的常数因子改善非常明显；
- 可测：给定"区域 + 起点终点 + 生物档案"就能逐节点比对，不需要跑完整服务器；
- 基础设施需求最小：只需要"方块状态表 + 区段镜像"，完全不需要实体镜像。

**原生侧必须复刻的清单（parity 风险点）**
1. PathMinHeap 的 sift-up/sift-down 行为与**相等元素的相对顺序**（决定同代价路径选哪一条）；
2. getNeighbors 的邻居展开顺序（固定顺序，不能按自己的喜好重排）；
3. 节点去重语义（原版用打包坐标做 key；重复访问时 g 值如何合并）；
4. maxVisitedNodes 预算与提前退出的判定条件；
5. malus 的**float** 运算：禁止中途提升为 double，禁止改变结合顺序；
6. 门 / 栅栏 / 脚手架 / 台阶 / 水 / 岩浆边缘的特判分支；
7. 终点 canReach 判定与 Path 的截断/后处理。

**接口形状（示意）**

    struct CavaPathRequest {
        int32_t sx, sy, sz, tx, ty, tz;
        int32_t maxVisitedNodes;
        uint8_t profile;          // 陆地/水生/飞行/两栖 + 能力位
        float   maxFallDistance;
        // ...终点的 reachRange 等
    };
    int32_t cava_pathfind(Cava*, const CavaPathRequest*, CavaPathNode* out, int32_t cap);

**注入点**：@Inject(at = HEAD, cancellable = true) 注入 PathNodeNavigator.findPathToAny（method_52 与 method_54 两个重载都要覆盖）；native 关闭或返回错误码时走原逻辑。完整注入点表见 docs/CAVA-hook-points.md。

**出口条件（全部满足才算 P1 完成）**
- 单元层：10^5 组随机（区域、起终点、生物档案）逐节点、逐 f 值完全一致；
- 场景层：迷宫 / 门前 / 水下 / 栅栏 / 脚手架 / 台阶 / 岩浆边缘 各 100 组逐节点一致；
- 整服层：2000 实体 × 6000 tick，世界哈希与实体位模式一致；
- 性能：寻路内核 ≥ 3×（目标 5–10×），端到端 MSPT 改善 ≥ 5%，否则止损复盘。

### 4.2 P2 实体开销

**拆成四个核，按性价比排序**
1. Entity.move（method_5784）→ CollisionView.getBlockCollisions（method_20812）的 AABB × 方块碰撞盒求解 —— 最高频、纯几何、逐位安全，收益最稳（注意：Entity 上没有 collide 方法，那是 1.20.5+ 的名字）；
2. 实体间 broadphase + push —— 原版在密集区域是 O(n^2)，实体农场/刷怪塔收益明显；
3. 射线（BlockView.clip；Entity 侧方法名待核实）—— AI 视线判定与攻击判定；
4. EntityView.getOtherEntities（method_8333 / method_8335）的候选集枚举 —— 注意：谓词是任意 Java lambda，**只能在 Java 侧执行**，原生侧只负责快速枚举候选 id，收益有限，优先级最低。

**事件回放机制（保持方块与 mod 语义的关键）**
原生只算几何与位移，把过程中"接触到的方块位置 + 命中类型"写进一个小数组返回；Java 侧按原版顺序执行 entityInside / stepOn / updateEntityMovementAfterFallOn 等虚方法。这样即使有 mod 覆写了方块行为，语义也不变。

**明确不动**：GoalSelector / Sensor / Brain 决策、属性系统、网络同步、travel 里的三角函数部分（把已经算好的 Vec3 位移作为输入传进去——这条边界同时保证了逐位一致）。

**出口条件**：位置/速度的 double 位模式逐 tick 一致；onGround / horizontalCollision / verticalCollision 一致；推挤的对象集合与最终结果一致；TPS 在 2000 实体场景下提升 ≥ 10%。

### 4.3 P3 红石

**原版结构**：RedstoneWireBlock 的 getReceivedRedstonePower（method_27842）+ update（method_10485）/ updateOffsetNeighbors（method_27844）/ updateNeighbors（method_10479）在线网上做 BFS 重算（注意：getPower / updatePowerStrength 是 1.20.5+ 的名字，getPower 在 1.20.4 属于 AbstractRedstoneGateBlock = method_9991）；更新风暴由 NeighborUpdater 与 ChainRestrictedNeighborUpdater 承载（有链深上限，超限会吞掉更新）。红石全是整数运算，逐位一致压力最小，**但"更新顺序"才是语义核心**。

**分两步**
- **方案 A（先做）**：只把 RedstoneWireBlock 的线网重算与 power 查询搬原生。边界清晰（一次级联一次调用）、纯整数、收益集中在大规模线网。
- **方案 B（后续，可选）**：把整个邻居更新队列搬原生（含 flags 顺序、shape update、链深上限、更新抑制语义）。难度高得多，只在机器密集型服务器才有回报。

**必须复刻**：六向更新的遍历顺序、flags 位语义、链深上限、比较器/中继器等方块实体的 tick 顺序。

**为什么方案 A 与方案 B 要分开（有权威背书）**：Lithium 的红石 mixin 类注释（本机源码复核，RedstoneWireBlockMixin.java:18-52）写明——移除冗余的方块更新"用一个活板门上的红石线就能检测到"，移除递归更新"用依赖特定方块更新顺序的定位装置就能检测到"，因此它**只优化电力计算，不碰更新扇出与顺序**。这正是我们的方案 A/B 分界线：A 的安全边际高，B（搬邻居更新队列）是真正的雷区。
同处注释还给出期望值：仅"电力计算"这一项在红石密集场景可带来**最多 30% 的 MSPT 下降**（Lithium 自己的数字）。

**预期管理（诚实版）**：普通生存服红石通常占不到 tick 的 2%，做它的收益主要体现在红石机器/技术服。如果 P1/P2 已经解决主要瓶颈，P3 可以降级或推迟。

**出口条件**：contraption 语料库（中继器时钟 / 活塞门 / 比较器逻辑 / instant wire / 1-tick 脉冲 / 更新抑制边界 / 侦测器链）逐 tick 世界哈希一致。

---

## 5. 等价性工程（项目的脊梁）

**三层测试**
| 层级 | 内容 | 通过标准 |
| --- | --- | --- |
| 单元层 | 原生纯函数 vs Java 纯函数，百万级随机/边界输入 | 位模式 100% 一致 |
| 场景层 | 固定存档 + 脚本化操作，记录黄金轨迹（每 tick 世界哈希、实体位模式、路径节点序列） | 逐 tick 完全一致 |
| 整服层 | 同一存档跑 6000–20000 tick，native 开/关两次运行对比 | 完全一致 |

**差分运行器**：-Dcava.native.enabled=false|true 两次运行，输出二进制 trace，逐 tick 比对，CI 自动执行。这是整个项目最重要的一个工具，应该在 P0 就搭起来。

**夹具工具（用你服务器上已装的 mod 就够，不需要额外东西）**
- **原版 /tick freeze｜unfreeze｜step｜sprint｜rate**：1.20.3 起 /tick 已经是**原版命令**（TIS 的 TickCommandMixin 目标就是原版 TickCommand = class_8916）。注意 **Carpet 1.4.128 自己没有 TickCommand**（1.20.2 分支还有），所以不存在 /tick warp，用原版命令。
- **Carpet**：/player 假玩家（spawn/use/jump/attack/drop/sneak/sprint/look/move，支持 once|continuous|interval，可批量铺实体）；**/log pathfinding（寻路耗时 + 路径可视化 PathfindingVisualizer）——寻路差分的第一手观测**；/track(MobAI)、/profile health、/counter；Scarpet(/script，预置 ai_tracker.sc / stats_test.sc / event_test.sc，适合逐 tick 采样落盘）。
- **Carpet TIS Addition**：**/log movement —— Entity.move 的 HEAD/RETURN 加上 limitPistonMovement / maybeBackOffFromEdge / collide 四处，逐 tick 全量记录，是实体差分的核心**；**/log microTiming —— tick 阶段级耗时 + 方块更新/状态更新/比较器更新/活塞事件逐条（带 pos/depth/event_source），是红石差分的核心**；另有 /lifetime、/info(server|world|block|entity)、/manipulate、/raycast、/counter、/spawn。
- **GCA**：/bot。
- 注意 logger 的默认开关：TIS 的 loggerMovement 默认 ops、loggerMicroTiming 默认 false——搭夹具前先确认。

**这条发现的实际价值**：差分测试原本需要我们自己写 trace 采集；现在可以**直接用 TIS 的 /log movement 与 /log microTiming 采数据**，把精力放在比对逻辑上。

**当前基线状态（好消息）**：分片 B 逐条核对了 Carpet/TIS/GCA 的规则默认值——**默认开启且改变原版行为的规则是 0 条**（quasiConnectivity=1、fillUpdates=true、pushLimit=12、railPowerLimit=9、poiUpdates=true、entityMomentumLoss=true、lightUpdates=ON、tileTickLimit=65536、chunkTickSpeed=1 全部等于原版）。也就是说你的服务器**现在就是逐位一致的基线环境**，可以直接开始采黄金轨迹。

**确定性前置条件**（否则一切比对都没有意义）：固定种子、单线程、不依赖 wall-clock、固定 randomTickSpeed、关闭有差异的自动保存。并且**必须先证明"原版连续两次运行结果完全一致"**。

**数值纪律**（每条都有 2.3 / 2.4 的实测支撑）：见 2.3、2.4 两节的规则，落到 CI 的编译参数检查里。

**崩溃纪律**（已经复现过一次 JVM 段错误）
- (指针, 长度) 必须同源；
- SAFE 构建全量边界检查，CI 跑 ASan/UBSan；
- 每个原生入口做 fuzz（越界坐标、NaN、超大 AABB、区段并发卸载、调色板损坏）；
- 所有入口返回错误码，Java 侧回退原版并计数；
- 熔断：同一入口连续失败 N 次自动全局关闭 native；
- 看门狗：单次调用超过阈值（例如 50 ms）记录告警，便于定位退化。

---

## 6. 构建、集成与分发

**Gradle / Loom 改造**
- options.release = 21 且同时加 --enable-preview（javac 要求 release 与预览版本一致）；
- 运行参数固定为 --enable-preview --enable-native-access=ALL-UNNAMED（后者消除受限方法警告）；
- FFM 代码集中在 cava.native.ffm 包，**不引用任何 MC 类型**，这样即使 Loom/tiny-remapper 对预览版 class 处理有问题，也能把它拆成独立 Gradle 子工程独立编译（P0 必须实测验证这一点，这是唯一的构建期未知数）；
- gradle.properties 里显式锁定 JDK 21 工具链。

**原生构建（全平台 5 个产物）**
- CMake（本机缺失，P0 第一件事是补上）；
- windows-x64：MSVC 14.4x，/MT 静态 CRT；linux-x64 / linux-arm64：GCC，静态链接 libgcc/libstdc++；macos-x64 / macos-arm64：Apple Clang，显式 -ffp-contract=off；
- jar 内按 natives/<os>-<arch>/ 存放，运行期解压到 <gameDir>/cava/natives/<version>/<os>-<arch>/（内容哈希命名 + 原子改名），用 SymbolLookup.libraryLookup 加载；
- 加载时校验：ABI 版本 → 结构体布局自检 → 文件哈希；任一失败整体回退纯 Java；
- CI：模板自带 workflow 用的是 **JDK 25**，与 --release 21 --enable-preview 冲突，**必须改成 JDK 21**，并把矩阵扩成 ubuntu-24.04 / ubuntu-24.04-arm / windows-2022 / macos-13 / macos-14 五条。

**部署约束**
- 服务端必须使用 JDK 21.0.x 且带 --enable-preview，写进启动脚本与运维文档；
- 其它 mod 不受影响，但同时运行的所有东西都共享这个 JVM 标志；
- 本机注意：GRADLE_USER_HOME=J:\mc\mods\.gradle-home 在工作区之外，当前沙箱拒绝写入导致构建失败，需要放行或改到工作区内。

---

## 7. 风险登记表

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 预览版锁死 JDK 21 | 无法升级 JDK；与要求 JDK 22+ 的组件冲突 | 固化启动脚本；FFM 隔离成 facade，将来迁移只改一处 |
| 原生崩溃（段错误） | 整个服务器进程直接死 | SAFE 构建 + fuzz + ASan + (ptr,len) 同源 + 错误码回退 + 自动熔断 |
| parity 漂移 | 机器失灵、生物行为异常，且极难发现 | 三层差分测试 + 逐位比对 + 黄金轨迹语料库 |
| 收益不足 | 投入大量工期没有回报 | P0 先做基线剖析（spark），若寻路占比 < 5% 则重新评估范围 |
| 红石更新顺序语义 | 技术服机器失效 | 方案 A→B 分步实施 + contraption 语料回归 |
| Loom 处理预览版 class | 构建失败 | FFM 层零 MC 依赖，可独立编译为子工程 |
| 镜像与真实世界不同步 | 静默的行为差异（最危险） | 每 tick 校验抽样哈希；区段卸载显式通知；SAFE 构建开启一致性断言 |

---

## 8. 里程碑与验收标准

| 阶段 | 内容 | 工期（粗估） | 出口条件 |
| --- | --- | --- | --- |
| P0 打通 | CMake + FFM facade + 最小 C++ 模块 + --enable-preview 构建链 + 差分运行器骨架 + 基线剖析 | 1–3 天 | 服务端能带预览标志启动并调用 C++；原版两次运行结果一致（确定性验证通过）；拿到 spark 基线 |
| P1 寻路 | 方块状态表 + 区段镜像 + A* + 注入点 + parity 套件 | 2–4 周 | 4.1 的四条出口条件全部满足 |
| P2 实体 | 碰撞/位移内核 + broadphase/push + 射线 + 事件回放 | 3–6 周 | 4.2 出口条件满足 |
| P3 红石 | 方案 A（线网重算），视情况追加方案 B | 3–6 周 | 4.3 出口条件满足 |
| P4 加固上线 | 可观测性、熔断、灰度开关、崩溃取证、文档 | 1–2 周 | 连续运行 7 天无崩溃、无回退触发、偏差为零 |

工期是单人开发的粗估，且高度依赖 parity 测试暴露问题的速度——等价性工作往往是实际耗时的大头。

---

## 9. 决策点

**已确认（本轮）**
1. ~~载体~~ → **Fabric mod**，不做服务端 fork。
2. ~~目标平台~~ → **全平台**（Windows / Linux / macOS × x64 / arm64），5 个原生产物。
3. ~~实现方式~~ → **严格照原版实现**（逐位一致）。
4. ~~兼容要求~~ → **尽量与主流服务端优化 mod 共存**。

**已确认（用户决策，2026 本轮）**
1. **语义基准 = 模组优先（策略 B）**。理由：这套整合包已在生产环境长期运行、大型机器上未出现红石问题，因此**基准是「当前整合包的行为」而不是纯原版**。→ 原生实现要复刻的是这套组合的语义；绝不允许装了 Cava 之后行为发生变化。每个重叠点必须显式决定「让位」还是「复刻」，并记录在案。
2. **平台范围**：先做 windows-x64 与 linux-x64；arm64 / macOS 推迟到 P4。
3. **三个子系统都要做**，顺序不敏感，按依赖推进（寻路 → 实体 → 红石）。
4. **MixinExtras**：接受（Fabric Loader 自带）。
5. **JDK 21 锁定**：接受。
6. **服务器已启用的 Carpet 规则**（会改变红石行为，是 P3 的语义基准）：fastRedstoneDust（红石粉卡顿优化）+ 无卡顿刷怪口径 + TNT 优化。**P3 不能按原版红石做。**

**仍未确认（需要数据而非选择题）**
- spark 基线里四块占比（实体 tick / 寻路 / 方块 tick 含红石 / 区块生成）→ 用于排优先级。
1. **语义基准策略**：默认 A（原版优先，遇到改变行为的 mod 让对应子系统让位）还是 B（只要有重叠补丁就让位，追求零行为变化）？见 docs/CAVA-platform-and-compat.md 第 2.3 节。
2. **arm64 是否都要原生支持**？（多 2–3 个构建与测试目标）
3. **推进方式**：三个子系统按 P1→P2→P3 串行（推荐），还是并行开工？
4. **性能基线**：有没有现成的 spark 报告或压力存档？
5. **你实际在用的 mod 列表**？按真实清单做兼容矩阵最有效。
6. 是否接受把 MixinExtras 作为依赖（Loader 自带，基本零成本）？

---

## 附录 A：实测原始数据

    FFM 边界（JDK 21.0.10, Win x64, 2000 万次）
      downcall(2×int)              14.4–15.1 ns
      downcall(ptr + 3×double)     15.6–16.3 ns
      upcall C++→Java              25.8–29.2 ns
      Arena.ofConfined + 4 KiB     ~140 ns
      MemorySegment.copy 64 KiB    105–110 GB/s
      原生读 400 KB                24.6 µs/次 (16.3 GB/s)

    逐位一致性（每项 20 万样本，与 Java 位模式不一致率）
      函数        MSVC      MinGW     Math vs StrictMath
      sqrt        0         0         0
      sin[-π,π]   3.10%     0.14%     3.38%
      cos[-π,π]   3.34%     0.13%     3.35%
      sin[1e7]    3.13%     98.2%     3.40%
      tan         4.60%     4.56%     3.67%
      atan2       17.72%    17.72%    0%
      exp         0.53%     17.5%     9.5%
      log         0.004%    0.024%    0.93%
      pow         0.036%    49.0%     4.87%

    编译开关
      -march=haswell -ffp-contract=fast : a*b+c 25.5% 位不一致
      -ffp-contract=off                : 0%
      缺 -fwrapv                       : (x+1 > x) 在 INT_MAX 上 Java=false / C++=true

    class 文件：minor=65535 major=65；JDK 22 + --enable-preview 拒绝加载

## 附录 B：Yarn ↔ Mojang 类名对照（1.20.4，已从本地 Yarn 映射核实）

| Yarn（本项目使用） | 混淆名 | Mojang 官方名 |
| --- | --- | --- |
| net/minecraft/entity/ai/pathing/PathNodeNavigator | efi | net/minecraft/world/level/pathfinder/PathFinder |
| net/minecraft/entity/ai/pathing/PathNodeMaker | eff | NodeEvaluator（抽象） |
| net/minecraft/entity/ai/pathing/LandPathNodeMaker | efl | WalkNodeEvaluator |
| net/minecraft/entity/ai/pathing/BirdPathNodeMaker | efd | FlyNodeEvaluator |
| net/minecraft/entity/ai/pathing/WaterPathNodeMaker | efj | SwimNodeEvaluator |
| net/minecraft/entity/ai/pathing/AmphibiousPathNodeMaker | efa | AmphibiousNodeEvaluator |
| net/minecraft/entity/ai/pathing/PathNode | efe | Node |
| net/minecraft/entity/ai/pathing/PathNodeType | efc | BlockPathTypes / PathType |
| net/minecraft/entity/ai/pathing/PathMinHeap | efb | BinaryHeap（堆比较器语义必须复刻） |
| net/minecraft/entity/ai/pathing/Path | efg | Path |
| net/minecraft/entity/ai/pathing/TargetPathNode | efk | Target |
| net/minecraft/entity/ai/pathing/EntityNavigation | bvv | PathNavigation |
| net/minecraft/entity/ai/pathing/MobNavigation | bvu | GroundPathNavigation |
| net/minecraft/entity/Entity | blv | Entity |
| net/minecraft/entity/LivingEntity | bml | LivingEntity |
| net/minecraft/util/math/Box | elo | AABB |
| net/minecraft/util/shape/VoxelShape | emm | VoxelShape |
| net/minecraft/world/CollisionView | csz | CollisionGetter |
| net/minecraft/world/BlockView | csv | BlockGetter |
| net/minecraft/world/EntityView | ctg | EntityGetter |
| net/minecraft/world/block/NeighborUpdater | eft | NeighborUpdater |
| net/minecraft/world/block/ChainRestrictedNeighborUpdater | efr | CollectingNeighborUpdater |
| net/minecraft/block/RedstoneWireBlock | dcr | RedstoneWireBlock |
| net/minecraft/block/AbstractRedstoneGateBlock | cys | DiodeBlock |
| net/minecraft/block/ComparatorBlock | cya | ComparatorBlock |
| net/minecraft/block/RepeaterBlock | dcw | RepeaterBlock |
| net/minecraft/block/ObserverBlock | dca | ObserverBlock |
| net/minecraft/block/PistonBlock | dja | PistonBaseBlock |
| net/minecraft/block/BlockState | djh | BlockState |

## 附录 C：参考项目（建议按顺序阅读）

1. **Lithium**（Java，开源）：先读它，搞清楚原版到底哪里慢、纯 Java 能压榨出多少。它的每个补丁都带性能数据，能帮你判断哪些子系统值得做原生。
2. **Alternate Current**（Java）：红石算法的重写，理解"线网图"建模方式与它为什么**不能**直接照搬到本项目（它改变了原版行为）。
3. **MCHPRS**（Rust）：把红石加速到极致的一个完整案例，可以看到更新队列、线网、方块状态表在原生侧是怎么组织的。
4. **C2ME / Folia / VMP / Pufferfish**：看区块生成与线程化的天花板在哪里——这是你 v2 真正想要的东西。
5. **JEP 442**（Java 21 FFM 预览版）与 **JEP 454**（Java 22 转正）：两份文档差异就是本项目 JDK 迁移时要改的 API 清单。

## 附录 D：本次可行性验证的产物（可随时删除）

    J:\mc\Cava\spike\
      cava_spike.cpp / Spike.java          FFM 边界开销基准（已验证可运行）
      cava_math.cpp / MathProbe.java       逐位一致性测试（sin/cos/tan/atan2/exp/log/pow/sqrt）
      cava_fp.cpp / BuildFlagProbe.java    编译开关测试（FMA 融合 / 有符号溢出）
      AllocProbe.java / SumOnly.java       JDK 21 分配 API 差异与段错误复现
      *.dll                                 MinGW 与 MSVC 两套产物
      build_msvc.bat                       MSVC 构建脚本
