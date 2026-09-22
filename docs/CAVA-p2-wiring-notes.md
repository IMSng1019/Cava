# Cava P2 接线轮：实体位移 ABI 接通说明

> 作者：P2 接线子代理（工作目录 `J:\mc\Cava`）。所有数字都是**本机实测**；
> 未验证的一律标注「未验证」。语义权威 = `docs/CAVA-entity-oracle-spec.md`，
> ABI 权威 = `native/include/cava_abi.h`（本轮**未修改**）。

---

## 0. 一句话状态

**冻结的 P2 ABI 已经从两端接通并跑通真实服务端**：形状采集 → `cava_shape_table_upload` →
`cava_resolve_move` → 事件解码 → `VoxelShape[] shapes[token]`，在真实服务端跑了 **95 万次**
原生求解，与原版私有 `adjustMovementForCollisions(Vec3d)` **逐位比对 941734 次、0 不一致**。

**没有交付的**：把 `Entity.move` 整段换成原生驱动（`live`）。原因见 §6 —— 是**结构性的**
（`EventReplay` 的 `MoveInputs` 是预打包模型，表达不了 `move` 里三处「中途才有的输入」），
不是时间问题。`live` 在本轮是**显式拒绝 + 一行 INFO**，绝不静默走近似实现。

---

## 1. 改了什么（只动了被授权的路径）

| 路径 | 内容 |
| --- | --- |
| `native/src/entity/cava_entity_abi.cpp` | **新建**：`cava_shape_table_upload` + `cava_resolve_move`（句柄状态与 P1 分开） |
| `src/main/java/cava/ffm/CavaBindings.java` | 两个符号的 FFM 绑定（`REQUIRED_SYMBOLS` 里也加了） |
| `src/main/java/cava/ffm/CavaNative.java` | 两个薄封装（`shapeTableUpload` / `resolveMove`） |
| `src/main/java/cava/ffm/LayoutCheck.java` | **修了一个真 bug**（见 §4） |
| `src/main/java/cava/mixin/entity/VoxelShapeAccessor.java` | **新建**：`@Accessor("voxels")` + `@Invoker("getPointPositions")` |
| `src/main/java/cava/mixin/entity/McMoveAccess.java` | **新建**：`Entity` 的 4 个私有成员读取口 |
| `src/main/java/cava/mixin/entity/EntityMoveMixin.java` | **新建**：`Entity.move` HEAD 注入（唯一的**行为**注入点） |
| `src/main/resources/cava.mixins.json` | 登记上面 3 个 mixin |
| `src/main/java/cava/shape/` | **新建**：ShapeGeometry / ShapeProbeView / ShapeTable / ShapeTableStats / MoveShapeBatch / AbiOffsets |
| `src/main/java/cava/entity/EntityMoveRuntime.java` | **新建**：模式（off/shadow/live）+ 计数 + 组 refs + 台阶守卫 + A/B 计时 |
| `src/main/java/cava/entity/NativeMoveSolver.java` | **新建**：FFM 编组/调用/解码的唯一调用点 |
| `src/main/java/cava/entity/MoveCallbacks.java` | 加了一个 **default 空方法** `moveEffectBookkeeping`（老实现不受影响） |
| `src/main/java/cava/entity/EventReplay.java` | 在 moveEffect 分支**开头**解析 steppingPos/steppingState 并回调该钩子（对齐原版偏移 626/636） |
| `src/test/java/cava/entity/MoveVectorParityTest.java` | **新建**：CVEM 向量 → 真实 DLL 的逐位对拍 |
| `src/test/java/cava/shape/AbiOffsetsTest.java` | **新建**：偏移常量 vs 冻结布局逐字段对拍 |

**没碰**：`cava_abi.h` / `cava_layout.cpp` / `CavaLayouts.java` / `mirror` / `hook` /
`compat` / `Cava.java` / `build.gradle` / 根 `CMakeLists.txt`。

---

## 2. 原生入口（`native/src/entity/cava_entity_abi.cpp`）

- **句柄状态与 P1 分开**：P1 的 `HandleState` 存扁平 AABB（寻路），本文件的 `ShapeTableState`
  存 (点表 + 体素位图)（碰撞）。两者只共用句柄形状校验，容器完全独立。
- **入口逐项校验，任一非法 ⇒ 错误码、不写 out、不写 events、零副作用**：
  句柄形状 / req / out 非空 / 各 count 非负且不超上限 / (指针,长度) 同源 /
  `flags==0` / `reserved*` 为 0 / `on_ground` 只能 0 或 1 /
  **`req->shape_count == ref_count`** / 每个 ref 的 `source` 在 0..3、`reserved*` 为 0、
  `kind==STATE ⇒ state_id < record_count`、`kind==INLINE ⇒ inline_slot < inline_shape_count`。
- **形状记录逐条自洽校验**（本轮实测踩出来的，不加会越界读）：
  `bit_words == ceil(sizeX*sizeY*sizeZ/64)`、`bit_offset+bit_words` 不越界、
  EXPLICIT 时点表长度必须够 `sizeX+sizeY+sizeZ+3`、单轴 size 不超过 65536、
  `points_kind` 不超过 1、`reserved0 == 0`。
  **任何一条不合法整表拒绝，不改变已有表**（与 P1 的「cap 不足不改变已有表」同款）。
- **`kind==STATE` 的平移在原生侧合成**：按 `VoxelShape.offset` 的语义逐点加方块坐标，
  并把该形状**当作 EXPLICIT**（`offset()` 返回的永远是 `ArrayVoxelShape`）。
  FRACTIONAL 记录的基准点是 `(double)i / (double)size`（先除法再加，不许换成乘法）。
- **`CavaMoveEvent` 与内核的 `MoveEvent` 用 `static_assert` 钉死逐字段同布局**
  （14 条 offsetof + sizeof），内核直接写 ABI 缓冲区，不复制。

### `event_overflow` 的语义（**本轮给定，写在这里作为权威**）

事件数组不足时内核停止记录（**绝不越界**）、置 `event_overflow = 1`，
**位移结果仍然完整正确**。本入口此时**返回 `CAVA_OK`**，`out` 里同时给出
`event_overflow=1` 与完整的 `delta_*`。

---

## 3. 形状采集与上传（`cava/shape/`）

### 3.1 为什么 `@Invoker` 不算破例

契约禁止的是 `@Overwrite` 与 `@Redirect`（替换/改写目标方法体内的指令，会与别的 mod 硬冲突
或静默丢弃别人补丁）。`@Invoker` 与它们不是一类：

1. 它**不改目标方法的任何一条指令**，只在目标类上加一个薄包装，方法体只有「调用原版自己那个方法」
   一条 invoke 指令（实测 `Bytecode.invokeMethod` 对非 private 目标生成 `INVOKEVIRTUAL`）；
2. `VoxelShape.getPointPositions(Axis)` 是 `protected abstract`、`voxels` 是
   `protected final`（javap 实读）——**不存在公开来源**；用 `getBoundingBoxes()` 反推
   会引入小于 1e-7 的量化误差，而那正是原版做碰撞判定的量级，对 parity 不可接受；
3. 反射读私有成员在生产环境**必然失效**（MC 类被 remap 成 intermediary，
   见 `AmphibiousPathNodeMakerAccessor` 的实测记录）；
4. `@Invoker` / `@Accessor` 的名字会被 Loom 在 remapJar 时改写成 intermediary ——
   本项目已验证过的形态。

**另外两个成员根本不需要 mixin**：`VoxelSet.contains(int,int,int)` 与
`getXSize()/getYSize()/getZSize()` 本来就是 `public`（javap 实读）。
所以只需要 `voxels`（@Accessor）+ `getPointPositions`（@Invoker）。

### 3.2 STATE vs INLINE 是一个**可证明**的判定

原版 `getBlockCollisions` 发出的永远是 `shape.offset(x,y,z)`，而 `offset()` 的语义是
「体素集**按引用共享**、点表逐点加同一个常数」。所以对每个形状做这套**精确**校验：

1. 该形状的 `VoxelSet` 与常驻表里某个 state 建表期抓到的对象**是同一个引用**（`==`）
   ⇒ 体素位图逐位相同；
2. 三个轴求 `k` = 形状点0 减 记录点0，必须是**整数值的 double**且落在 int32 内；
3. 三个轴的**每一个**点 i 验证 `形状点(i) == (double)(int)k + 记录点(i)`（精确 `==`，无 epsilon）。

三条同时成立 ⇒ 几何完全一致。**任何一条不成立就退回 INLINE**，把真实几何内联过去（慢，但一定对）。
实测：95 万次调用里 **stateRefs=1306056 / inlineRefs=623**（INLINE 占 0.05%）。

### 3.3 实测规模（真实服务端，`testbed/p2-wire` 私有实例）

    [cava/entity] 形状表: 状态=26644 有形状=21633 不同VoxelSet=208 记录=26644
                  点表double=163018 位图uint64=22518 体素=220774
                  (EXPLICIT=17123 FRACTIONAL=4510) 构建=13.710ms 上传=0.000ms
    [cava/entity] 形状表已上传（一次性）：records=26644 points=163018 bitWords=22518 上传耗时 1.026 ms

（`不同VoxelSet=208` 是**按对象身份去重**后的形状数：26644 个状态实际只有 208 种不同的体素集。）

---

## 4. 修掉的一个真 bug：布局自检把整个原生库判死了

`LayoutCheck.compare` 原来按 `(struct_size, field_count)` 找**第一个**匹配的 Java 结构体。
P2 冻结后 `CavaShapeRecord`（32 字节 / 8 字段）与 `CavaPathNode`（32 字节 / 8 字段）
**完全同形**，而且 `layout_hash` 也相同（`0x0DCFFE65` —— 哈希只看 (offset,size) 序列）。

后果（实测，在修之前）：`CavaPathNode` 被匹配两次、`CavaShapeRecord` 永远「没有对应 entry」
⇒ `cava_open` 返回 `CAVA_ERR_LAYOUT` ⇒ **整条原生路径静默不可用**，
`PathfindAbiTest` 整类 skip，构建仍然「绿」。

修法：**优先按下标一一对应**（两侧顺序都约定为 `cava_abi.h` 的声明顺序），
只有下标对不上时才退回按内容找未匹配项；顺序不一致时**记 note** 而不是静默通过。

---

## 5. 逐位对拍（单元层 + 真实服务端）

### 5.1 单元层：CVEM 向量 → 真实 cava.dll

`MoveVectorParityTest`（`gradlew test --tests "cava.entity.MoveVectorParityTest"`）：

- 读 `native/tests/entity/vectors/entity-00.bin`（CVEM v1，400 组），
  **先核对 fnv1a64 == 0x12E1F79186F28CA4**（与 manifest.txt 一致，防止读错文件）；
- 每组编成 `CavaMoveRequest` + `CavaMoveShapeRef[]`（INLINE）+ inline 点表/位图，
  用**真实 DLL** 调 `cava_resolve_move`；
- 比对 `delta/base/step` 的**位模式**、`event_count/step_used/event_overflow`、
  以及**每一条事件的 14 个字段**（含 `offset/max_dist_before/after` 的位模式）。
- 结果：**对拍 258 例（82 例带事件）、0 不一致**。
  - **142 例跳过**并计数上报：CVEM v1 没有存每轴点表长度，而生成器里有一种手工构造的
    3x1x3 非凸形状只给了 3 个点而 `size[0]=3`（文件里 `npc=8` 而规范布局要 10）——
    无法在 Java 侧逐位还原，**不猜**。
    （附注：那条形状在 C++ 参照实现里也会读越界，属生成器的**已知瑕疵**，不是内核问题。）
- 另有一条 `stateRefTranslationMatchesPreShiftedInline`：同一份几何，一次走 INLINE（点表已平移）、
  一次走 STATE + `block=(3,-2,7)`，要求位移逐位相等 —— 验证平移合成的编组链路。

### 5.2 真实服务端（私有实例 `testbed/p2-wire`，端口 25601 / RCON 25602）

---

## 6. `live`（把 `move` 整段换掉）本轮**未交付**，以及为什么

**不是时间问题，是结构性的。** `Entity.move` 里有三处 Java 侧输入**只有在回放过程中、
前面某一步改过实体状态之后**才存在，而 `MoveInputs` 是一个**一次性建好**的 record：

1. `getLandingPos()`（偏移 416）在 `setOnGround`（412）**之后**读；
   `setOnGround` 会走 `updateSupportingBlockPos` → `findSupportingBlockPos`，
   改掉 `supportingBlockPos`，而 `getLandingPos()` 依赖它；
2. `getSteppingPos()`（偏移 626）依赖同一批状态，且它在 `moveEffect` 分支**内部**；
3. `world.isRegionLoaded(...)`（`checkBlockCollision`）扫的是 `setPosition`（218）
   **之后**的碰撞箱；`stepSoundBranch` 依赖 `distanceTraveled`（675-705）——
   那是回放**中途**才被写的。

⇒ `EventReplay` 的「先建 `MoveInputs`、再一次性回放」模型**不可能**驱动一次真实的
`move`。真实接管需要把回放改成**拉取式**（回调在正确时刻回实体取值），
那是 `EventReplay` 的重新设计，会动到它现有的用例。

**本轮的处理**：`-Dcava.entity.move=live` 会**显式拒绝**并打一行 INFO，继续走原版；
绝不静默走一个「看起来能跑」的近似实现（那会静默改变整服行为，而影子模式抓不到它）。
原因原文写在 `EntityMoveRuntime.LIVE_NOT_SHIPPED_REASON`。

**顺带得到一个安全性质**：因为影子路径已经把「原生结果 == 原版私有方法的结果」证明到
94 万次 0 不一致，将来接管时**原生失败的回退**就是调同一个原版私有方法 ——
**与纯 Java 逐位相同**，不是近似回退。

---

## 7. 冻结 ABI 的一处**能力边界**（发现，不是 bug）

`cava_resolve_move` 只接受**一份** `refs[]` 形状列表，但原版 `method_17835` 的
**台阶分支**会用**不同的 `box.stretch(...)`** 重新跑 `world.getBlockCollisions`
（最多 4 次，见 `CAVA-entity-oracle-spec.md` §6.2 的注）。
也就是说「一份形状列表算完整台阶分支」在原理上不成立。

**本轮的接线用法（可证明正确）**：

1. **恒传 `step_height = 0.0`** ⇒ 内核不进台阶分支，返回的就是第 0 趟（纯碰撞）的结果；
2. Java 侧用**原版自己的判据**预测台阶分支是否会被进入（`bl/bl2/bl3` 的精确 !=、
   `bl4 = onGround || (bl2 && movement.y < 0)`、`getStepHeight() > 0.0F`，
   逐条对齐 oracle spec §6.2 的偏移 45/65/85/105/133）；
3. 不会进 ⇒ 原生结果**就是**原版结果，采用；会进 ⇒ 丢掉原生结果、改调原版私有方法。

实测该守卫命中率 8265 / 950000 ≈ **0.87%**。

> **给 ABI 的观察项（不是本轮要改的）**：若要让原生真正吃下台阶分支，
> `cava_resolve_move` 需要能表达「每一趟各有一份形状列表」。当前形态下 `step_used` 恒为 0。

---

## 8. 性能：**本机实测，当前接线是净亏（诚实结论）**

影子模式天然给出**同一次调用、同一份输入**的 A/B 对照（先跑原生、再跑原版私有方法）：

| 项 | 稳态均值（941734 次调用） |
| --- | --- |
| Java 侧组 refs（`buildBatch`：世界查询 + 逐形状校验） | **1753 ns** |
| `cava_resolve_move`（含 FFM 边界 + 内核） | **761 ns** |
| 原版 `adjustMovementForCollisions(Vec3d)` | **532 ns** |

**结论（不粉饰）**：

1. **本次接线目前比原版慢约 1.98 微秒/次**（1753 + 761 - 532）。
   也就是说：`live` 现在上线会是**净亏**——这是它不该在本轮上线的第二个理由；
2. 瓶颈**不在原生内核**，而在 **Java 侧**：
   - 组 refs 的 1753 ns 里包含 `world.getBlockCollisions` 的**整个世界查询**
     （原版也要做，但影子模式下它做了两遍：我们先做一遍、原版内部再做一遍）——
     所以这个数**高估**了接管后的成本；
   - 原生调用 761 ns 里主要是 **FFM 边界**；本次观测的 `refs` 平均只有 **1.37 个形状**
     （`stateRefs / nativeCalls`），也就是说**绝大多数调用是「空/近空形状表」**——
     原版在这种情况下几乎免费（`shapes.isEmpty()` 直接返回），原生却要付整条边界成本。
     这两个数**不能外推到碰撞密集的负载**。
3. 测量规范：样本 = 95 万次真实调用，**不是三次采样**；没有出现「三次完全相同的 ns/op」；
   报的是**累计均值**并给出样本量。**没有做 JIT 预热期的分段**（未验证项，见 §9）。

> **未验证**：`refs` 较大的负载（实体被卡在方块里、或 `getBlockCollisions`
> 返回几十个形状）下原生是否反超。本轮没有构造出这种负载（走廊 + 猪群的平均 refs 仍只有 1.37）。

---

## 9. 未验证 / 留白（**不要当成已完成**）

1. **`live` 未交付**（§6）。真实接管**没有发生过**。
   本轮能证明的最强命题是：「native 结果与原版结果在 94 万次真实调用上逐位相同」。
2. **性能数字的适用面**（§8）：平均 refs=1.37 的负载，不能外推到碰撞密集场景。
   没有做预热分段（首次调用与稳态的差异未量化）。
3. **`native/tests/` 的 P0-B 自测已经因 P2 冻结而变红**（**不是本轮引入的**，见 §10）。
4. **`ShapeProbeView`（邻居=空气、`ShapeContext.absent()`）只是候选**：
   位置/上下文相关的形状靠运行期逐点精确校验挡掉（会走 INLINE）；
   **没有**证明「INLINE 路径在生产里从不出错」——只在真实服务端观察到 623 / 131 万。
5. **世界边界形状的 INLINE 路径**在真实服务端**没有命中过**（走廊在世界中心，`canCollide` 为假）。未验证。
6. **没有做逐 tick 差分**：影子模式逐位比对的是**返回值**，不是整服轨迹。
7. `EntityMoveRuntime` 的计数器只在日志里（每 2000 次一行），**没有命令端点**
   （命令注册在 `cava/hook/`，不在本流授权路径）。

---

## 10. 需要别人配合（跨流请求）

| # | 给谁 | 请求 |
| --- | --- | --- |
| 1 | **P0-B / native 自测** | `native/tests/cava_selftest.cpp` 写死「9 个结构体 / 0x6975CBF9」。P2 冻结后是 **14 个 / 0x1C12265E**。实测 `121 passed, 9 failed`，**全部**是这一条（`cava_layout_report 返回 14（期望 9）`、`cava_open` 用旧和值被 `CAVA_ERR_LAYOUT` 拒）。本流没碰该路径 |
| 2 | **P0-C（`src/test/java/cava/ffm/`）** | 两处同类陈旧断言：`LayoutHashTest.layoutHashesAreStable`（第 60-77 行）与 `PathfindAbiTest.openStateIsConsistent`（第 158 行）。把 9 个期望值补成 14 个（新增 0x0DCFFE65 / 0x1545B999 / 0xB542D3D5 / 0x630C22D5 / 0x7737ABBD），`layout_hash_sum` 改成 `0x1C12265E`。**这两条在本轮之前就已经因 P2 冻结而错**，只是当时 `LayoutCheck` 的 bug 让它变成 skip |
| 3 | captain | §7 的 ABI 观察项：台阶分支需要「每趟一份形状列表」才能被原生吃下 |
| 4 | 下一轮（P2 接管） | 把 `EventReplay` 改成**拉取式**（回调在正确时刻回实体取值），这是 `live` 的前置 |

---

## 11. 复现

原生库（会覆盖 `natives/windows-x64/cava.dll`；**注意时间戳，别用旧 dll**）：

    $env:TMP='J:/mc/Cava/native/build/tmp'; $env:TEMP=$env:TMP
    & C:/mingw64/bin/g++.exe -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -Wall -Wextra -static -static-libgcc -static-libstdc++ -DNDEBUG -shared -o natives/windows-x64/cava.dll (Get-ChildItem native/src -Recurse -Filter *.cpp | % FullName)

Java：构建 + 单测

    $env:GRADLE_USER_HOME='J:/mc/Cava/.gradle-home'
    $env:JAVA_TOOL_OPTIONS='-Duser.language=en -Dfile.encoding=UTF-8'
    ./gradlew.bat build --console=plain --no-watch-fs --no-configuration-cache
    ./gradlew.bat test --tests "cava.entity.MoveVectorParityTest" --console=plain --no-watch-fs --no-configuration-cache
    ./gradlew.bat test --tests "cava.shape.AbiOffsetsTest" --console=plain --no-watch-fs --no-configuration-cache

真实服务端（私有目录 + 私有端口）

    cd testbed/p2-wire
    $env:JAVA_TOOL_OPTIONS='-Dcava.entity.move=shadow --enable-preview --enable-native-access=ALL-UNNAMED'
    & 'C:/Program Files/Java/jdk-21/bin/java.exe' -Xmx2G -jar fabric-server-launch.jar nogui
    # 另一个窗口用 tools/rcon.ps1 -Port 25602 建走廊 / spawn 假人与生物

**本机坑（已固化）**：`gradlew jar` **不更新** `build/libs/cava-0.1.0.jar`
（那是 `remapJar` 的产物）。要重新部署必须跑 `remapJar`，
否则服务端会加载旧 mod，而**所有计数都是旧的**（本轮踩过一次）。

JVM 参数：`-Dcava.entity.move=shadow --enable-preview --enable-native-access=ALL-UNNAMED`；
用 RCON 建了一个 28x28 石墙走廊、spawn 了 Carpet 假人 + 50 只猪/牛/羊。

最终一行（`testbed/p2-wire/p2-run4.log`）：

    [cava/entity] mode=SHADOW nativeCalls=950000 nativeOk=950000 fallback=8265 errors=0 overflow=0
    stepBranchSkipped=8265 tokenOutOfRange=0 stateRefs=1306056 inlineRefs=623
    shadowCompared=941734 shadowAgree=941734 shadowMismatch=0
    | timedCalls=941734 组refs=1753ns 原生调用=761ns 原版调用=532ns

读法：
- **原生真的被调用 95 万次**，且**返回值被逐位比对过 941734 次，0 次不一致**；
- `fallback=8265` **全部**来自 `stepBranchSkipped`（台阶守卫主动放弃，见 §7）；
  `errors=0`、`overflow=0`、`tokenOutOfRange=0`；
  `[cava/entity]` 没有任何 ERROR/WARN 噪声；
- `nativeCalls` 与 `shadowCompared` 的差 = 「原生结果拿到但没比对」的次数。

> ⚠️ **「接管」的准确措辞**：影子模式**没有 cancel** `Entity.move` —— 原生位移被算出来、
> 被逐位比对，但**没有被采用**。所以本轮的证据是
> 「**native 结果 == 原版结果（94 万次 0 不一致）+ 原生入口零错误**」，
> **不是**「原生接管了移动」。`live` 未交付（§6）。

理由：位移是可信的（影子比对要用它），「事件不全」由 overflow 位表达，
不该和「参数非法」共用一个错误码。
**Java 侧看到 `event_overflow == 1` 必须回退纯 Java**（事件回放不全 = 少调虚方法 = 行为改变）。
