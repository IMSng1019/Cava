# Cava P2 第 2 核：实体间 broadphase 与 `Entity.pushAwayFrom` 接线说明 / ABI 提案

> 作者：P2-push 子代理（工作目录 `J:\mc\Cava`）。**所有数字都是本机实测**，原始输出贴在 §5/§6；
> 未验证的一律写「未验证」。语义权威 = `docs/CAVA-push-oracle-spec.md`（本轮新建），
> ABI 权威 = `native/include/cava_abi.h`（**本轮未修改**，提案在 §3）。
>
> **一句话状态**：区段 broadphase（`SectionedEntityCache.forEachInBox`）在真实服务端**真的接管过**
> （`bpTakeovers=15595`、`bpFallback=0`、`bpErrors=0`、`bpDesync=0`），并且与**原版 API 逐条转写**
> 的同 call 比对 **4025 次 0 不一致**；可证伪金丝雀能红。**但性能是净亏（+6.8µs/次）**，见 §6。
> `Entity.pushAwayFrom` 的内核与向量全部做完并通过，**但真实服务端的接管计数是 0** ——
> 在本机这套负载下这个原版方法**从未被调用**，原因见 §5.2，**这条明确标为未验证**。

---

## 1. 改了什么（只动被授权路径）

| 路径 | 内容 |
| --- | --- |
| `native/src/entity/push/cava_push.h` | 内核接口（**不是**冻结 ABI；提案在 §3）|
| `native/src/entity/push/cava_push_kernel.cpp` | 纯几何内核：`push_away_from` / `box_intersects` / `section_plan` |
| `native/src/entity/push/cava_push_exports.cpp` | `extern "C"` 的三个 C ABI 入口（提案的参考实现）|
| `native/tests/push/cava_push_vectors.cpp` | 定点真值表 + 跨语言向量生成（**脱离 MC**）|
| `native/tests/push/build-push.ps1` | 只编 2 个 cpp 的构建脚本（不引 CMake / ABI 头）|
| `native/tests/push/vectors/push-00.bin` `plan-00.bin` | 跨语言向量（2048 + 256 例）|
| `src/main/java/cava/push/PushMath.java` | Java 参照实现（第二份独立转写，只用于对拍）|
| `src/main/java/cava/push/NativePush.java` | FFM 绑定（**自己 libraryLookup**，见 §4.2）|
| `src/main/java/cava/push/PushRuntime.java` | 模式 / 计数 / 金丝雀 / A-B 计时 / 终局摘要 |
| `src/main/java/cava/push/SectionMirror.java` | `trackedPositions` 的原生镜像（升序 long 数组）|
| `src/main/java/cava/mixin/push/EntityPushAwayFromMixin.java` | `Entity.pushAwayFrom` HEAD/RETURN 注入 |
| `src/main/java/cava/mixin/push/SectionedEntityCacheMixin.java` | `forEachInBox` HEAD 注入 + `addSection`/`removeSection` 镜像维护 |
| `src/main/resources/cava.mixins.json` | 只**追加**上面两个类 |
| `src/test/java/cava/push/PushMathTest.java` | Java 参照的定点真值表（不依赖原生）|
| `src/test/java/cava/push/PushVectorParityTest.java` | 向量 → **真实 DLL** 逐位对拍（3 例全过）|
| `testbed/p2-push/**` | 私有实例（端口 25651/25652/25653）+ 崩场脚本 |

**没碰**：`cava_abi.h` / `cava_layout.cpp` / `cava/ffm/**` / `cava/entity/**` / `cava/shape/**` /
`cava/mirror/**` / `cava/hook/**` / `cava/compat/**` / `cava/parity/**` / `cava/mixin/entity/**` /
`cava/Cava.java` / `build.gradle` / `tools/parity-*.ps1`。

## 2. 内核与入口

### 2.1 内核（`cava_push_kernel.cpp`，**不 include 任何 MC / ABI 头**）

| 函数 | 语义 |
| --- | --- |
| `push_away_from(thisX,thisZ,otherX,otherZ,out)` | 逐位复刻 `Entity.pushAwayFrom` 的几何（oracle spec §2）|
| `box_intersects(a,b)` | 逐位复刻 `Box.intersects(DDDDDD)`（**严格不等**，NaN ⇒ false）|
| `filter_intersecting(query, boxes[], n, out[], cap)` | 顺序保持的候选下标过滤 |
| `pack_section / unpack_x/y/z / section_coord` | `ChunkSectionPos` 的位布局（**y 在低 20 位、z 在中间 22 位**）|
| `section_plan(box, sorted_positions[], n, out[], cap)` | 逐位复刻 `SectionedEntityCache.forEachInBox` 的**访问顺序** |

数值纪律照第 1 核：只允许 `+ - * / sqrt`，`java_max/java_min` **复用** `native/src/entity/cava_entity.h`
（同一份语义只定义一处）。可移植性上刻意避开了两处 UB：有符号左移溢出用 `uint64` 做，
算术右移写成显式的符号扩展（`sign_extend` / `sar4`）。

### 2.2 三个 C 入口（`cava_push_exports.cpp`，`CAVA_EXPORT`）

    int cava_push_away_from(double this_x, double this_z, double other_x, double other_z,
                            double* out_dx, double* out_dz, int32_t* out_hit);
    int cava_push_box_filter(const double* query6, const double* boxes6, int32_t count,
                             int32_t* out_idx, int32_t out_cap, int32_t* out_count);
    int cava_push_section_plan(const double* box6, const int64_t* positions, int32_t count,
                               int64_t* out, int32_t out_cap, int32_t* out_count);

`CAVA_OK / CAVA_ERR_NULL(-3) / CAVA_ERR_ARG(-4)` 的**数值**与 `cava_abi.h` 一致，但内核刻意不
include 那份冻结头（要能脱离 ABI 单测）。**任一非法输入 ⇒ 错误码、不写 out。**

导出实测（`objdump -p natives/windows-x64/cava.dll`）：

    [  13] +base[  14]  000d cava_push_away_from
    [  14] +base[  15]  000e cava_push_box_filter
    [  15] +base[  16]  000f cava_push_section_plan

---

## 3. ABI 提案（**给 captain**，本轮 `cava_abi.h` 一字未改）

### 3.1 冻结形态建议（三个结构体 + 三个入口）

    /* 一个实体 AABB。6 个 double，**零内部填充**（连续 48 字节）。*/
    typedef struct CavaEntityBox {
        double min_x, min_y, min_z, max_x, max_y, max_z;
    } CavaEntityBox;                              /* size=48  fields=6 */

    /* 一次推挤请求。**全部标量**，零填充。*/
    typedef struct CavaPushRequest {
        double self_x, self_z, other_x, other_z;  /* 由 Java 侧现取（getX/getZ 是虚调用）*/
        int32_t reserved0;                        /* =0，否则 CAVA_ERR_ARG */
    } CavaPushRequest;                            /* size=40  fields=5 */

    /* 一次推挤结果。**把 (p,q,hit) 放在同一个结构体里**，避免三次 out 指针。*/
    typedef struct CavaPushResult {
        double dx, dz;
        int32_t hit;
        int32_t reserved0;                        /* 显式具名尾部填充，见 3.2 第 3 条 */
    } CavaPushResult;                             /* size=24  fields=4 */

    int cava_push_away_from(const CavaPushRequest*, CavaPushResult*);
    int cava_push_box_filter(const CavaEntityBox* query, const CavaEntityBox* boxes,
                             int32_t count, int32_t* out_idx, int32_t out_cap, int32_t* out_count);
    int cava_push_section_plan(const CavaEntityBox* query, const int64_t* positions,
                               int32_t count, int64_t* out, int32_t out_cap, int32_t* out_count);

### 3.2 它如何满足契约的三条

| 契约条款 | 本提案怎么满足 |
| --- | --- |
| **结构体只传指针** | 三个入口的入参与出参**全部是 `T*`**；没有一处按值传结构体。|
| **数组算一个字段** | `boxes` / `positions` / `out` / `out_idx` 一律是「裸指针 + 独立的 `int32_t` 长度」；结构体里**没有任何数组**，也不需要 `CAVA_LAYOUT_MAX_FIELDS` 里的嵌套登记。|
| **零内部填充** | `CavaEntityBox` 是 6 个连续 double（48 字节）；`CavaPushRequest` 是 4 double + 1 int32 = 36 → **有 4 字节尾部填充**，所以显式加 `reserved0` 变成 40 字节零内部填充。`CavaPushResult` 同理（16 + 4 → 显式 `reserved0` → 24）。**这是 P1 那条「JDK 21 的 paddingLayout 无法命名」教训的直接应用：所有填充都必须具名。** |

**当前参考实现为什么不用结构体**：这轮 `cava_abi.h` 不在授权路径内，而新增结构体必须**同时**登记到
`native/src/cava_layout.cpp` 与 `src/main/java/cava/ffm/CavaLayouts.java`（两个都不在授权路径）。
所以本轮落的是「标量 + 裸数组」形态，**代价是参数多、且没有 `cava_open` 的 fail-closed 保护** ——
这正是 §3.4 第 1 条要求补齐的部分。

### 3.3 提案必须回答的两个问题

**(1) 实体身份怎么过边界？**

**答案：身份根本不过边界。** 契约要求「对象集合与谓词留在 Java」，所以：

- 原生侧**只有数值**：坐标、AABB、区段打包键；
- `pushAwayFrom` 的 `(self, other)` 只在 Java 侧存在，原生拿到的只有 4 个 double；
- 区段计划返回的是 **`trackedPositions` 的打包 long**（`ChunkSectionPos.asLong`），
  它是原版自己的键，Java 侧拿它去 `trackingSections.get(pos)` 取**原版对象**。
  **原生从不持有实体引用，也不返回实体引用。**

如果将来要把 `getOtherEntities` 的候选集也搬到原生（§8 判定为「本轮不做」），
**必须**同样只返回**下标或 `int` 实体 id**，由 Java 侧用一张当 tick 的快照表还原对象；
**绝不能**让原生持有指针。这是本提案对后续扩展的硬约束。

**(2) 被推挤的实体集合怎么回到 Java？原版顺序是什么？**

原版顺序（oracle spec §1.1/§2.1）：`tickCramming` 先物化 **`List<Entity>`**（`getOtherEntities` 的返回值，
**不是惰性视图**），再 `for (e : list) this.pushAway(e)`。所以顺序 = **`getOtherEntities` 的枚举顺序**，
而枚举顺序由 `SectionedEntityCache.forEachInBox`（oracle spec §4）决定：

    x 数值升序 → 固定 x 时「z 掩码升序、再 y 掩码升序」→ 区段内按 TypeFilterableList 的列表序

本提案**不搬运实体集合**，只搬运**区段访问计划**（`int64` 打包键的升序子序列）。
理由是这条性质可以**证明**：

> **原生返回的是输入有序数组的一个升序子序列 ⇒ 访问顺序与原版逐一相同。**

Java 侧只需对每个键做**原版自己的三个过滤**（`trackingSections.get(pos) != null`、
`!isEmpty()`、`getStatus().shouldTrack()`）并调用 `consumer.accept(section)`，
因此「顺序」这件事**没有第二份实现**，也就没有读反的空间。

### 3.4 提案里的其它三条要求

1. **符号必须并进 `CavaBindings.REQUIRED_SYMBOLS`**（本轮做不到，见 §4.2）：只有那样才有
   `cava_open` 的布局 fail-closed 保护；本轮的独立 `libraryLookup` 是**临时形态**。
2. **句柄化**：本轮的原生侧是**无状态纯函数**（好），但 Java 侧的 `SectionMirror` 是**每实例一份**，
   正式形态要么给 C 入口加一个 `handle`，要么在文档里写死「一个 world 一个镜像」。
3. **`positions[]` 必须是有序快照**：内核**不排序也不校验有序**。这是个**前置条件**，
   必须在 ABI 文档里写明；否则结果静默错误。

---

## 4. Java 接线

### 4.1 两个模式 / 一个金丝雀 / 一个自证开关

    -Dcava.push.mode=off|shadow|live               # 推挤内核，默认 off
    -Dcava.push.broadphase=off|shadow|live         # 区段 broadphase，默认跟随 mode
    -Dcava.push.verify=true                        # 不接管，只做「原生预测 vs 原版实际」对拍
    -Dcava.push.canary=zero-push|drop-last-section # 只允许诊断用
    -Dcava.push.bench=true                         # 打开 A/B 计时

**回退默认安全**：原生不可用 / 任何错误码 / 镜像条数对不上 / 计划装不下 / 重入 ⇒
**一律不 cancel**，原版照跑，且不改变任何原版状态。异常在 mixin 里不吞（原版行为），
但原生调用本身的所有异常都被 `NativePush` 转成错误码并**只报一次**日志。

### 4.2 一个结构性约束：符号表是冻结的

`cava/ffm/CavaBindings.java` 的 `REQUIRED_SYMBOLS` 与逐符号 downcall 句柄**不在本流授权路径**，
所以本轮新增的三个符号**不能登记进去**。`NativePush` 因此用 `CavaNative.libraryPath()`
（已封装好的「不要用 defaultLookup」那条纪律）在 **`cava/push` 自己的 Arena** 上再取一次符号。
实测可用（`[cava/push] 模式=... 原生入口=true（OK (cava-f79052cd99e797d4.dll)）`）。
**这是临时形态，§3.4 第 1 条要求补齐。**

### 4.3 区段镜像的同步与对账

- 镜像 = `trackedPositions` 的升序 `long` 数组，放在共享 Arena 的原生内存里；
- 只在原版**唯二**的改动点 `addSection`（TAIL）/ `removeSection`（HEAD）上同步；
- **每次使用前做 O(1) 对账**：`mirror.count == trackedPositions.size()`，不等就整表重建 + 计数；
- 重入保护：`consumer.accept` 可能触发嵌套的 `forEachInBox`（同一个缓存实例会把 out 缓冲冲掉），
  深度 > 0 时直接让原版跑。

---

## 5. 真实服务端证据（私有实例 `testbed/p2-push`，端口 25651/25652/25653）

场景：超平坦世界 + 蓝冰台面 + forceload ±48 + Carpet 假人 + `/tick freeze` 精确控 tick；
**200 个矿车**以 0.35 格间距密集摆放（每个矿车每 tick 对邻居调 `pushAwayFrom`，字节码 489-692），
`/tick step 100`，再用 RCON 逐实体导出位置算位移。

### 5.1 区段 broadphase：**真的接管了**

    # live3（boats）/ live1（minecarts）/ canary2（minecarts + 金丝雀）
    [cava/push] 终局计数 mode=live broadphase=live canary=off
      | bpCalls=16308 bpTakeovers=16308 bpFallback=0 bpErrors=0 bpDesync=0 bpVisited=33112
    [cava/push] 终局计数 mode=live broadphase=live canary=drop-last-section
      | bpCalls=15595 bpTakeovers=15595 bpFallback=0 bpErrors=0 bpDesync=0 bpVisited=33450 bpCanary=15594

- `bpTakeovers == bpCalls`：**每一次 `forEachInBox` 都被原生计划接管**（`ci.cancel()` 生效）；
- `bpFallback=0` / `bpErrors=0` / `bpDesync=0`：没有一次因为原生失败或镜像漂移而回退；
- 日志里 `[cava/push]` **没有任何 ERROR/WARN**。

### 5.2 ★ 诚实结论：`Entity.pushAwayFrom` 的**真实接管计数是 0**

    所有运行的同一行（live / shadow / 金丝雀都一样）：
    pushCalls=0 pushTakeovers=0 pushDeclined=0 pushErrors=0 pushCanary=0
    shadowCompared=0 shadowMismatch=0 verified=0 verifyMismatch=0

**这意味着**：本机这套负载下，原版 `Entity.pushAwayFrom` **一次都没有被调用**，
所以这个核在真实服务端上**没有接管证据**。原因已定位到两条（都实测过）：

1. **`MobEntity` 在本整合包里会在约 1 秒内被静默移走**（`PersistenceRequired:1b` +
   `Invulnerable:1b` + `NoAI:1b` + `NoGravity:1b` 全挡不住；死亡消息、crash 报告都没有）。
   **关掉 `-Dcava.push.mode=off -Dcava.push.broadphase=off` 后现象完全相同** ⇒ **与本轮改动无关**。
   而 `LivingEntity.tickCramming`（唯一的主路径）只能作用在生物上。
2. **非生物的两条替代路都不通**：矿车走的是 `AbstractMinecartEntity.pushAwayFrom`（**自己的覆写**，
   不是 `Entity.pushAwayFrom`）；船虽然会走 `super.pushAwayFrom`，但 200 条船**根本没进入实体 lookup**
   （RCON 的 `@e[tag=...]` 一个都找不到），所以 `BoatEntity.tick` 的候选集为空。

**未验证**：`Entity.pushAwayFrom` 在真实服务端上的接管行为。**本轮能证明的最强命题只是**
「内核与原版几何在 2048 个跨语言向量上逐位一致 + 62 条定点断言全过」。
**没有**构造出能证明「接管后行为不变且更快」的真实负载。

### 5.3 ★ 可证伪金丝雀（**能红的测试才是测试**）

`-Dcava.push.canary=drop-last-section`：在 live 的区段循环里**故意丢掉计划中的最后一个区段**。
同一场景、同一 jar、只差一个 `-D`：

| 运行 | canary | `moved`（位移 > 0.05 的矿车数）| `avgDist` | `maxDist` |
| --- | --- | --- | --- | --- |
| live1 | off | **200 / 200** | **7.689** | **8.7001** |
| canary2 | `drop-last-section` | **183 / 200** | **3.9915** | **6.9678** |

**金丝雀一开，平均位移掉 48%、最大位移掉 20%、17 个矿车完全没被推动。**
⇒ 原生 broadphase 的结果**真的进入了物理**，不是「计数器动了一下」。
（`bpCanary=15594` 说明这次运行里 15594 次调用都执行了破坏动作。）

### 5.4 与原版逐条转写的同 call 对拍（shadow 模式）

    [cava/push] 终局计数 mode=shadow broadphase=shadow canary=off
      | bpCalls=4025 bpTakeovers=0 bpFallback=0 bpErrors=0 bpDesync=0 bpVisited=4024
        bpCompared=4025 bpMismatch=0 | bpTimed=4025 nativePlan=8933ns vanillaPlan=2113ns

`bpMismatch=0` 的含金量：对拍的另一侧**一个常数都没有自己写** —— 它直接调用
**原版自己的 `ChunkSectionPos.getSectionCoord / asLong / unpackY / unpackZ`** 与
**`LongAVLTreeSet.subSet`**，把 `forEachInBox` 的字节码逐条转写一遍（`SectionedEntityCacheMixin.cava$vanillaPlan`）。
所以这条同时验证了「位布局有没有读反」（§4.2 的 y/z 交换）。

### 5.5 已知偏差（写明）

如果 `consumer.accept` 在遍历途中改动了 `trackedPositions`（实体增删 → `addSection/removeSection`），
原版是边遍历边看，本路径用的是**计划快照**。原版在那种情况下的行为依赖 fastutil 迭代器的
未定义细节。**本轮没有构造出用例，也没有观察到**；下一次调用会用 O(1) 对账发现漂移并重建。

---

## 6. 性能：**诚实结论是净亏**

测量：shadow 模式下**同一次调用**先算原生计划、再算原版转写（`-Dcava.push.bench=true`），
样本 = 4025 次真实调用。

| 项 | 均值 |
| --- | --- |
| `cava_push_section_plan`（含 FFM 边界 + `box6` 写入 + `asSlice`）| **8933 ns** |
| 原版 `forEachInBox` 的 `subSet` + y/z 过滤（用原版自己的 API）| **2113 ns** |

⇒ **本形态净亏约 +6.8 µs/次**。瓶颈**不在算法**，在 **FFM 边界**：本场景平均只访问
**约 1 个区段**（`bpVisited / bpCalls ≈ 1.0`），原版那条 `subSet` 扫描本来就只要 2.1µs，
而原生一次调用要付 8.9µs 的过界成本。

**读法纪律**（照 P2 上一轮）：样本是 4025 次真实调用、**不是三次采样**；没有出现
「三次完全相同的 ns/op」；报的是累计均值 + 样本量；**本机没有做 JIT 预热分段**，
所以这 4025 次里包含预热瞬态，**均值可能高估**。

**结论与建议**：
1. 区段 broadphase 的**原生 live 不建议上线**（除非先把 FFM 边界的固定开销摊掉，例如
   把「一次 tick 内多次查询」合并成一次批量调用，或者把镜像与计划都做成句柄化以减少参数）；
2. **更有希望的方向**是 `refs`/`AABB` 密度高的负载（`bpVisited` 几十上百），本机**没有构造出来** ——
   **未验证**。

推挤内核这一侧没有拿到真实调用的计时（`pushCalls=0`，§5.2），**未验证**。

---

## 7. 第 3 核（射线）：**判定为「本轮不做」**

**现状先读清楚**：`World.raycast`（`FALLDAMAGE_RESETTING`）在第 1 核的 live 接管里**留在 Java 侧**，
由 `MoveInputSource.supplyEventsAt(LANDING_RAYCAST)` 现算（见 `docs/CAVA-p2-live-notes.md` §3）。
也就是说它**既没有被搬原生，也没有被绕过** —— 语义仍然逐位等于原版。

**为什么不做**（三条，都是结构性的，不是时间问题）：

1. **射线是方块世界查询，不是几何求解**。`BlockView.raycast` 的循环体每一步都要
   `state.getCollisionShape / getRaycastShape`（oracle spec §8.4），也就是要方块状态；
   本项目的原生侧**没有世界**，只有形状表。要搬就得把「沿射线经过的每个方块的形状」
   全部喂进去 —— 而「经过哪些方块」本身就是要算的东西。
2. **收益面比第 1/2 核小一个量级**：`Entity.move` 与 `tickCramming` 是每实体每 tick，
   射线是 AI 视线/攻击判定时才发生。
3. **`VoxelShape.raycast` 依赖 `getCoordIndex` 的**两个**子类实现**（oracle spec §8.5 + §3.5），
   复用第 1 核的 `shape_coord_index` 是必须的；这要求 ABI 上把「射线」和「碰撞求解」共用
   同一份形状表句柄 —— 那是 ABI 层面的耦合，不该在没有收益证据时先做。

⇒ **判据是「只有在能证明搬原生后行为不变且更快时才做」；本轮连第一步（搬）的净收益都无法预估，**
**所以不做，并写明理由。** 这一条**没有做任何性能测量**，属于**未验证的判断**。

---

## 8. 第 4 核（`getOtherEntities` 的候选集枚举）：**本轮不做**

- **谓词留 Java** 是契约，已满足；
- 本轮的 `cava_push_box_filter`（`Box.intersects` 的顺序保持过滤）**内核已就绪并有真值表**，
  但**没有接线**：它要求把一批实体的 AABB 拷进原生内存，而 `EntityTrackingSection.forEach`
  的候选数通常只有个位数 —— 拷贝成本必然大于过滤成本；
- 真正有前途的形态是**每 tick 一次的实体 AABB 空间索引 + 批量包**，那需要
  「一个 tick 一次上传」的实体镜像协议（任务书 §设计要求里提到的那条），
  以及 §3.3(1) 的「身份不过边界」硬约束。**那是一件独立的、比本核大的工作。**

⇒ **明确写「不做」，并给出上面两条理由。**

---

## 9. 需要别人配合 / 请裁决

| # | 给谁 | 请求 |
| --- | --- | --- |
| 1 | captain | §3 的 ABI 提案：三个结构体 + 三个入口，以及「符号必须并进 `CavaBindings.REQUIRED_SYMBOLS`」（本轮做不到，见 §4.2）。|
| 2 | captain | **区段 broadphase 的 live 归属**：本机实测净亏（§6）。是否保留 `-Dcava.push.broadphase=live` 这个开关（默认 off）？|
| 3 | captain | **`Entity.pushAwayFrom` 的真实接管证据缺失**（§5.2）。若要求必须取得，需要先解决「本整合包会移走 MobEntity」——**这不是本核能修的**。|
| 4 | 下一轮 | 若要继续第 2 核：**先做「每 tick 一次的实体 AABB 镜像 + 批量区段/候选查询」**，把 FFM 边界的固定开销摊到几十次查询上；单次查询形态已被证明是净亏。|

## 10. 复现

    # 内核（脱离 MC；只编 2 个 cpp）
    pwsh -NoProfile -File native/tests/push/build-push.ps1
    #   kernel-only compile: exit=0 (no Minecraft headers)
    #   SUMMARY: 62 checks, 0 failed / RESULT: PASS

    # 真实 DLL（**必须重新编，否则服务端会加载旧 dll**）
    $srcs = (Get-ChildItem native\src -Recurse -Filter *.cpp | % FullName)
    & C:\mingw64\bin\g++.exe -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -Wall -Wextra `
        -static -static-libgcc -static-libstdc++ -DNDEBUG -shared -o natives/windows-x64/cava.dll $srcs
    #   旧 sha256=1E6EE368...（P2 live 轮）  新 sha256=F79052CD99E797D45D79C724E3C5690A1976D0EAE74EFFD939764F7DAB1BA6AF

    # 单元（含真实 DLL 的向量对拍）
    $env:GRADLE_USER_HOME='J:/mc/Cava/.gradle-home'
    $env:JAVA_TOOL_OPTIONS='-Duser.language=en -Dfile.encoding=UTF-8'
    .\gradlew.bat test --tests 'cava.push.*' --console=plain --no-watch-fs --no-configuration-cache

    # jar（**必须是 remapJar 的产物**）+ 部署
    .\gradlew.bat remapJar --console=plain --no-watch-fs --no-configuration-cache
    Copy-Item -LiteralPath build/libs/cava-0.1.0.jar -Destination testbed/p2-push/mods/cava-0.1.0.jar -Force
    #   jar 内 natives/windows-x64/cava.dll 的 sha256 必须 == 工作区那份（本轮实测相同）

    # 真实服务端（私有端口；离屏无窗口）
    pwsh -File testbed/p2-push/run-push.ps1 -Action reset
    pwsh -File testbed/p2-push/run-push.ps1 -Action start -Mode live -Broadphase live -Tag live1
    pwsh -File testbed/p2-push/run-push.ps1 -Action rcon -Command 'player Bot spawn at 0.5 -60.0 -8.5'
    pwsh -File testbed/p2-push/run-push.ps1 -Action scenario -Pigs 200 -Steps 100 -Tag live1
    pwsh -File testbed/p2-push/run-push.ps1 -Action stop      # 终局计数在 shutdown 钩子里打 System.out

    # 本机坑（本轮踩到的，都已固化进脚本）
    # 1) RCON 的 ReceiveTimeout 若设成 3000ms，每条命令都要等满 3 秒 -> 200 次召唤要 10 分钟。改 150ms。
    # 2) 端口被占会打到老实例上；-Action start 先探端口。
    # 3) gradlew jar 不更新 build/libs/cava-0.1.0.jar（那是 remapJar 的产物）。
    # 4) 【新】本整合包里 MobEntity 召唤后约 1 秒被静默移走；改用矿车做推挤负载。
    # 5) 【新】船（oak_boat）召唤成功但不进入实体 lookup（RCON 的 @e 找不到）—— 场景设计前先用 @e 验证。
