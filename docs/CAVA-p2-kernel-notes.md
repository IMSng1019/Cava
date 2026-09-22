# Cava P2 实体碰撞（快照 K）：纯几何内核交付说明 + ABI 提案

> 作者：P2-K 子代理。**结论全部有本机实测证据**，未验证的一律标注。
> 语义权威：`docs/CAVA-entity-oracle-spec.md`（javap 逐条转写）。
> **本文件包含 ABI 提案，但【没有改 `native/include/cava_abi.h`】** —— 该头文件由 captain 持有，
> 按任务书要求「ABI 由我提案、captain 冻结」。

---

## 0. 一句话状态

**纯几何内核完成，487 项断言全通过（含 13 组定点真值表），400 组跨语言向量逐位自校验且幂等。**
内核**不依赖 Minecraft**（`#include` 只有 `<stdint.h>` 与自己的头；`net.minecraft` 命中数 0），
可用契约规定的编译参数单独编译（实测 exit=0）。
**ABI 提案已写成可直接冻结的形态，并附编译器实测的逐字段 offset/size 与 layout_hash。**
**未做（也不在本任务范围内）**：Java 侧注入、真实服务器端到端、性能对比、与 Lithium 的归属裁决。

---

## 1. 交付物

| 路径 | 内容 | 进生产 DLL？ |
| --- | --- | --- |
| `native/src/entity/cava_entity.h` | 内核接口：轴帧表、形状视图、事件、请求/结果、`java_min/java_max/java_d2i_sat` | **是**（CMake GLOB `native/src` 下的 .cpp） |
| `native/src/entity/cava_entity_kernel.cpp` | `calculate_max_distance` / `calculate_max_offset` / `adjust_movement_for_collisions` / `resolve_movement` 的逐分支复刻 | **是** |
| `native/tests/entity/cava_entity_vanilla.h` | **仅测试用**：把 `VoxelShapes.cuboid` / `VoxelShape.offset` / `BitSetVoxelSet` 的构造语义照抄一份，让测试能造出原版真会产生的形状 | 否 |
| `native/tests/entity/cava_entity_vectors.cpp` | 定点真值表 + 轴帧互证 + 随机向量生成与自校验 | 否 |
| `native/tests/entity/cava_abi_proposal_probe.cpp` | ABI 提案的**布局实测探针**（校验 layout_hash 实现 + 打印新结构体的 offset/size/hash） | 否 |
| `native/tests/build-entity.ps1` | 独立构建脚本（**不动 P0-A 的 `native/tests/CMakeLists.txt`**；纯 ASCII） | 否 |
| `native/tests/entity/vectors/entity-00.bin` + `manifest.txt` | 跨语言向量（400 组，含位移位模式） | 否 |

改动/新增**未触碰**：`native/include/cava_abi.h`、`native/src/cava_*.cpp`、`native/CMakeLists.txt`、
`native/tests/CMakeLists.txt`、`src/main/**`、`build.gradle`。

---

## 2. 实测证据（真实命令 + 真实输出）

### 2.1 构建 + 全量测试

    PS J:\mc\Cava> powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-entity.ps1
    === Cava P2 ABI proposal layout probe ===
    -- step 1: layout_hash 实现对拍（期望值来自 cava_abi.h 注释里的实测值）--
      CavaLayoutEntry      got=0xF837804D want=0xF837804D  OK
      CavaLayoutReport     got=0xE9FFC021 want=0xE9FFC021  OK
      CavaOpenParams       got=0x7FDE7499 want=0x7FDE7499  OK
      CavaOpenResult       got=0xFF344829 want=0xFF344829  OK
      CavaPathRequest      got=0xE566F98D want=0xE566F98D  OK
      CavaPathNode         got=0x0DCFFE65 want=0x0DCFFE65  OK
      CavaMobProfile       got=0x9C6C98CD want=0x9C6C98CD  OK
      CavaStateRecord      got=0x53797229 want=0x53797229  OK
      CavaCollisionBox     got=0x250ECBE1 want=0x250ECBE1  OK
      9 结构体和 = 0x6975CBF9（权威 layout_hash_sum = 0x6975CBF9）
    ...（step 2/3 见 5.9）
    SUMMARY: hash-impl OK
    srcs=2
    BUILT: J:\mc\Cava\build\native-entity\cava_entity_vectors.exe (683511 bytes)
    === Cava P2 entity collision kernel test ===
    [builder] 形状构造的定点断言
    [frame] 轴帧一致性（两种独立推导）
      axis=X cycle=0 opposite=0 xa=X ya=Y za=Z
      axis=Y cycle=2 opposite=1 xa=Y ya=Z za=X
      axis=Z cycle=1 opposite=2 xa=Z ya=X za=Y
    [truth-table] 定点真值表（真值全部由字节码手推）
      ok  [TT-1] 空形状表 -> 位移位模式原样返回、零事件
      ok  [TT-2] 相切面 -> 位移恰好 0.0（不是 1e-7，也不是穿透）
      ok  [TT-2e] 相切命中产生 1 条 X 轴事件、offset = 0.0
      ok  [TT-3] 距相切 1e-7 -> 位移恰好 1e-7
      ok  [TT-4] 距相切 2e-6 -> 位移恰好 2e-6
      ok  [TT-5a] |dx|<|dz| -> 先 Z 后 X，结果是 (2,0,-1)
      ok  [TT-5b] |dx|>=|dz| -> 先 X 后 Z，结果是 (0,0,-1)
      ok  [TT-6a] [S1(2^-24), S2] -> 0.0（第二个形状被 1e-7 短路）
      ok  [TT-6b] [S2, S1] -> 2^-24（最后一个形状没有后置短路）
      ok  [TT-7] 负坐标：贴到墙面，位移 -0.5
      ok  [TT-8] 3e7 量级坐标：位移恰好 0.5
      ok  [TT-9a] 台阶用例的第 0 趟 = (0.4,-0.1,0)
      ok  [TT-9b] 台阶用例的最终位移 = (1,0.6,0)
      ok  [TT-9c] step_used == 1
      ok  [TT-9d] step_candidate.y == stepHeight
      ok  [TT-9e] 事件的 pass 单调不减（原版调用顺序）
      ok  [TT-10] stepHeight=0 -> 位移 == base、step_used=0
      ok  [TT-11] cap 不足 -> overflow=1 且位移不受影响
      ok  [TT-12] 零位移 -> 直接返回、零事件、不进台阶分支
      ok  [TT-13] 两个都挡时取 min(d)（0.25），与列表顺序无关（都不触发 1e-7 短路）
    [vectors] 生成跨语言向量
      cases=400  有事件=188  改了位移=209  台阶命中=4  全零位移=40
      bytes=196788  fnv1a64=12e1f79186f28ca4
    SUMMARY: 487 checks, 0 failed
    RESULT: PASS
    exit=0

### 2.2 幂等（连跑两次）

    run1 entity-00.bin sha256=EFFD4D31C1E7D420378C53FCB32EE3B155DB873801B09240879E9ABB93AB0AA2
    run2 entity-00.bin sha256=EFFD4D31C1E7D420378C53FCB32EE3B155DB873801B09240879E9ABB93AB0AA2
    idempotent=True
    manifest sha256=07B135566716BEADAFC685E84B84AAFDA96AF46571B0E0F592E43140AC7F9F8A
    manifest idempotent=True

### 2.3 脱离 MC + 契约编译参数

    PS> Select-String -Path native\src\entity\*.h,native\src\entity\*.cpp -Pattern '^\s*#include'
    #include <stdint.h>
    #include "cava_entity.h"
    PS> ... -Pattern 'net\.minecraft|net/minecraft|java\.lang' | Measure-Object
    minecraft/java refs: 0

    PS> & 'C:\mingw64\bin\g++.exe' -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math \
             -Wall -Wextra -Wno-unused-parameter -c native\src\entity\cava_entity_kernel.cpp
    kernel compile exit=0        （零 warning）

---

## 3. 实现要点（parity 风险点逐条）

| # | 风险点 | 处置 | 证据 |
| --- | --- | --- | --- |
| 1 | **轴序**：Y 一定先；`|dx|<|dz|` 时 Z 先（且只有 Z 平移 box），否则 X 先 | 逐字节码转写，含「`139: iload 9; ifne 158` 使 X 的结果不平移 box」 | TT-5a / TT-5b（顺序敏感的判定性用例） |
| 2 | **轴帧表**（`AxisCycleDirection.between` 是 static，`aload_0` 不是 this） | `axis_frame()` 只此一份定义；用**两种独立推导**互证 | `[frame]` 12 项断言 + 3 行打印 |
| 3 | **1e-7 的五个不同含义**（收敛短路 / 扫描收缩 / 结果守卫 / 遍历盒 / 循环上限） | 逐个写在 oracle spec §3.3；内核里只有 `CAVA_EPS` 一处常量 | TT-2/3/4（守卫）、TT-6a/6b（收敛短路） |
| 4 | **形状列表顺序有语义**（`calculateMaxOffset` 的短路在**迭代开头**） | 顺序完全由调用方给定，内核只按序处理；事件也按同一顺序发 | TT-6a（0.0）vs TT-6b（2^-24） |
| 5 | **`getCoordIndex` 两个子类实现不同** | `ShapeView.points_kind` 两档，分别走「分母」与「二分」两条路 | `[builder]` 三档 fbr 断言 + TT-6b 用 EXPLICIT 命中 |
| 6 | **`VoxelShape.offset` 把形状变成 `ArrayVoxelShape`** | 内核对 `source==BLOCK` 的形状**强制按 EXPLICIT 处理**（见 5.2 结论 3） | oracle spec §3.8 / §5.4；向量里 100% 的方块形状都是 EXPLICIT |
| 7 | **`Math.min/max` 的 NaN 与 `-0.0`** | 自带 `java_min/java_max`，不用 `std::min/max`、不用 `fmin/fmax` | 本机 `MathProbe` 实测 6 行 |
| 8 | **±Infinity**（世界边界形状的点表） | 内核全程 double，不做有限性假设；`d >= -1e-7` 对 NaN/±Inf 的行为与原版一致 | spec §4.2；**未端到端覆盖（留白 6）** |
| 9 | **命中即返回**（不继续扫同一形状的其它格） | 逐字节码转写（309/420 的 `dreturn` 在 `if (contains)` 块内） | TT-13（取 min 而非「第一个」） |
| 10 | 台阶分支的 5 趟 + 停用条件 | `resolve_movement` 逐条转写 133/227/279/297；事件带 `pass` | TT-9a..e、TT-10 |

### 3.1 与原版的**有意差异**（都在代码里有注释）

1. **不构造、不缓存 `VoxelShape`**：内核只消费 (点表 + 位图) 视图。
2. **不做 `BlockCollisionSpliterator` 的遍历**：形状表由 Java 侧按原版顺序组装好传进来。
   内核**不读世界**，这是「能脱离 MC 单测」的前提。
3. **事件是「命中」而不是「扫过」**：内核只在某个形状真的产生了 `d`（无论是否被守卫接受）时发事件。
   完整「扫过的每个方块」= 调用方传进来的那个形状表本身，Java 侧本来就有它。
4. `resolve_movement` 把**第 0 趟 / 台阶候选 / 最终位移**三个都返回（原版只有最终值）。
   这是**额外的诊断信息**，不改变任何数值。

---

## 4. 跨语言向量格式 **CVEM v1**（`native/tests/entity/vectors/entity-00.bin`）

全部小端（x86-64 实测；**跨大端平台需显式转换 —— 未验证**）。

    header (32 字节)
      char[4] magic = 'CVEM'
      u16 version = 1 ; u16 reserved = 0
      u32 caseCount ; u64 masterSeed ; u32 flags = 0 ; u16/u16 reserved

    per case
      u32 caseId ; u32 shapeCount
      f64 box[6]         = minX,minY,minZ,maxX,maxY,maxZ
      f64 movement[3]
      f64 stepHeight ; u32 onGround ; u32 pad
      per shape:
        u32 pointsKind ; u32 source ; i32 size[3]
        i32 blockX, blockY, blockZ ; i64 shapeToken
        u32 pointCount ; f64 points[pointCount]      // X 段、Y 段、Z 段依次拼接；FRACTIONAL 时为 0
        u32 bitWords   ; u64 bits[bitWords]
      // 输出
      u64 deltaBits[3] ; u64 baseDeltaBits[3] ; u64 stepCandidateBits[3]
      u32 eventCount ; u32 stepUsed ; u32 eventOverflow
      per event:
        i64 shapeToken
        i32 source, axis, blockX, blockY, blockZ, pass, accepted, cellX, cellY, cellZ
        u32 reserved0, reserved1
        u64 offsetBits, maxDistBeforeBits, maxDistAfterBits

**位移一律以 `u64` 位模式给出**（不是文本、不是十进制），保证跨语言逐位可比。

规模：`cases=400`、`bytes=196788`、`fnv1a64=0x12e1f79186f28ca4`（FNV-1a 64，offset basis `0xCBF29CE484222325`，prime `0x100000001B3`）。

生成器（`cava_entity_vectors.cpp`）：xorshift64* + splitmix 混合，`caseSeed = mix(0x1F2E3D4C5B6A7988, caseId)`，
**消费顺序严格固定**（先后顺序写在 `gen_case()` 里）。目录默认 `native/tests/entity/vectors/`（可用 argv[1] 覆盖）。

**⚠️ 这批向量的诚实定位（P1 教训 3）**：它是**参照实现自产**的，只能证明
「内核与未来 Java 侧参照实现彼此一致」，**不能证明内核等于原版**。
「等于原版」这个结论由 **13 组定点真值表**承担 —— 那些真值是**手推自字节码**的（见 oracle spec 各节）。

---

## 5. **ABI 提案**（可直接冻结的形态）

### 5.0 最难的一处：**形状列表怎么过边界**

**约束**：

1. 内核不读世界 => 形状必须由 Java 侧给；
2. 形状是 **Java 堆对象**（`VoxelShape`），FFM 的 `MemorySegment` **不能持有 Java 引用**，也没有「Java 对象句柄」这种标量；
3. 求解用的是 `VoxelSet` 的**点表 + 体素位图**，**不是扁平 AABB**（oracle spec §3.7：用 AABB 反推会引入 <1e-7 量化误差）；
4. **事件回放必须能拿回「那个对象本身」** —— 重建一个 `VoxelShape` 会丢掉 mod 覆写的派发行为。

**提案（两级 + 一个不透明令牌）**：

| 过边界的东西 | 形式 | 频次 |
| --- | --- | --- |
| 形状**几何** | `CavaShapeRecord` + `points[]` + `bits[]`，**按 state id 索引的常驻形状表**（`cava_shape_table_upload`） | **一次/状态**（启动或状态表变更时） |
| 无 state id 的形状（实体箱 / 世界边界） | 同一个 `CavaShapeRecord`，但放在**每次调用的 inline 数组**里 | 约 1-2 个/调用 |
| 形状**身份** | `CavaMoveShapeRef.shape_token`（`int64`），**不透明、只原样回写** | 每形状 8 字节 |
| 形状**位置** | `CavaMoveShapeRef.kind=STATE` + `block_x/y/z`：原生侧按 `VoxelShape.offset` 的语义**逐点加常数**合成平移点表 | 每形状 12 字节 |

**为什么这样就满足了「必须能原样取回对象本身」**：

- Java 侧在发起调用时，把本次调用的形状按顺序放进一个局部数组 `VoxelShape[] shapesThisCall`，
  并把**该数组的下标**填进 `shape_token`。
- 原生侧**从不解释** `shape_token`，只在事件里原样回写。
- Java 侧回放时执行 `shapesThisCall[event.shape_token]` —— 这是**同一个引用**（`==`），不是 `equals`，不是重建。
- 于是「对象身份」**根本没有跨过边界**；跨过去的只是一个整数。**这是本设计的核心。**

**为什么 `kind=STATE` 的平移必须在原生侧做（而不是让 Java 上传平移后的形状）**：

- `getBlockCollisions` 发的是 `shape.offset(x,y,z)`（oracle spec §5.4），**每 tick 每个方块都是一个新对象**；
  若让 Java 逐个上传平移后的几何，形状表就退化成「每 tick 全量重传」，缓存完全失效。
- 而 `VoxelShape.offset` 的语义就是 `OffsetDoubleList`（**逐点加同一个常数**，见 §3.8），
  **逐点加法是位级精确的**，所以原生侧合成与 Java 侧 `offset()` 的结果**逐位相同**。
- 同时原生侧**必须把该形状的 `points_kind` 视为 EXPLICIT**（`offset()` 返回的永远是 `ArrayVoxelShape`）。
  这条写进契约，测试里 100% 的方块形状都是 EXPLICIT。

### 5.1 `CavaShapeRecord`（32 字节 / 8 字段 / align 4）

    typedef struct CavaShapeRecord {
        uint32_t points_kind;   /* in: CAVA_SHAPE_POINTS_FRACTIONAL(0) / EXPLICIT(1) */
        uint32_t point_offset;  /* in: 进 points[] 的 double 下标（EXPLICIT 时有效） */
        uint32_t bit_offset;    /* in: 进 bits[] 的 uint64 下标 */
        uint32_t bit_words;     /* in: 位图 uint64 个数；0 = 空形状 */
        int32_t  size_x, size_y, size_z;   /* in: VoxelSet.getSize(axis) */
        uint32_t reserved0;
    } CavaShapeRecord;

**每个字段为什么必需**：

| 字段 | 为什么必需 |
| --- | --- |
| `points_kind` | `getCoordIndex` / `getPointPositions` **按子类分派**（oracle spec §3.5），**必须知道是 `SimpleVoxelShape` 还是 `ArrayVoxelShape`** |
| `point_offset` | EXPLICIT 时点表在扁平数组里的起点；长度 = `size_x+size_y+size_z+3`（每轴 `size+1` 个点） |
| `bit_offset` / `bit_words` | 体素位图；`bits[bit_offset .. +bit_words)`。`bit_words==0` 即 `isEmpty()`（对齐 `BitSetVoxelSet.isEmpty()`） |
| `size_x/y/z` | `VoxelSet.getSize(axis)`；决定扫描上限 `o`、边界 `l/n` 的夹取、以及 `getIndex` 的步长 |
| `reserved0` | 把结构体补齐到 32 字节、**零内部填充**（4 字节字段 ×8） |

**编码约定（写进契约）**：

- `bits` 的位序 = `BitSetVoxelSet.getIndex(x,y,z) = (x*size_y + y)*size_z + z`，
  位 `i` 落在 `bits[bit_offset + i/64]` 的第 `i%64` 位（little-endian 语义）。
- `points` 的排布 = **X 段（size_x+1 个）、Y 段、Z 段**依次拼接。
- `points_kind==FRACTIONAL` 时 `point_offset` 被忽略（原生侧算 `i/size`），但 `size_*` 与 `bits` 仍必须给。

### 5.2 `CavaMoveShapeRef`（48 字节 / 11 字段 / align 8）

    typedef struct CavaMoveShapeRef {
        int64_t  shape_token;   /* in/out: 不透明身份；原生侧原样回写到事件 */
        uint32_t kind;          /* in: CAVA_MSHAPE_STATE(0) / CAVA_MSHAPE_INLINE(1) */
        uint32_t state_id;      /* in: kind==STATE 时有效（= P1 的 state id） */
        int32_t  block_x, block_y, block_z;  /* in: kind==STATE 时的方块坐标（平移量） */
        uint32_t source;        /* in: CAVA_ESHAPE_SRC_ENTITY/WORLD_BORDER/BLOCK/OTHER */
        uint32_t inline_slot;   /* in: kind==INLINE 时有效，进 inline_shapes[] 的下标 */
        int32_t  reserved0, reserved1, reserved2;
    } CavaMoveShapeRef;

| 字段 | 为什么必需 |
| --- | --- |
| `shape_token` | **身份**（5.0）。必须 64 位：Java 侧的下标可能超过 2^31（一次调用不会，但保留余量且对齐 P1 的 `handle` 习惯） |
| `kind` | 两种几何来源（常驻表 / 本次内联）**必须能区分** |
| `state_id` | 方块来源的形状**按 state id 复用**，这是唯一的缓存键（契约禁止用 `BlockState` 对象身份做键） |
| `block_x/y/z` | `VoxelShape.offset(x,y,z)` 的平移量；**同时是事件里 `block_x/y/z` 的来源**（Java 侧要按原版顺序对这些方块跑 `onEntityCollision` / `onSteppedOn`） |
| `source` | 事件回放要知道这个形状来自哪一批（实体 / 世界边界 / 方块），因为原版**三批的处理方式不同** |
| `inline_slot` | 实体碰撞箱与世界边界形状**没有 state id**，必须另走内联数组 |
| `reserved0..2` | 补齐到 48 字节、零内部填充、零尾部填充 |

**顺序语义（写进契约）**：`refs[]` 的**下标顺序就是原版形状表顺序**（entity → worldborder → blocks，见 oracle spec §4.1），
`calculateMaxOffset` 的 1e-7 短路对它敏感。**原生侧绝不重排。**

### 5.3 `CavaMoveRequest`（104 字节 / 14 字段 / align 8）

    typedef struct CavaMoveRequest {
        int64_t  reserved0;                 /* in: 必须为 0（也把结构体顶到 8 字节对齐） */
        double   min_x, min_y, min_z;       /* in: 实体碰撞箱（世界坐标，未 stretch） */
        double   max_x, max_y, max_z;
        double   move_x, move_y, move_z;    /* in: **已经算好**的位移 */
        double   step_height;               /* in: Entity.getStepHeight()，f2d 后的值 */
        uint32_t flags;                     /* in: 保留，必须为 0；非 0 返回 CAVA_ERR_ARG */
        uint32_t on_ground;                 /* in: Entity.isOnGround()，0/1 */
        int32_t  shape_count;               /* in: refs[] 的长度 */
        int32_t  reserved1;
    } CavaMoveRequest;

| 字段 | 为什么必需 |
| --- | --- |
| `min_*/max_*` | `Entity.getBoundingBox()`。**世界坐标**，不是相对方块的 |
| `move_*` | **已经算好的位移**。契约明确：`travel` 的三角函数部分留在 Java，原生只吃算好的 `Vec3d` |
| `step_height` | 台阶分支的阈值（`133: getStepHeight() > 0.0F` 与 `227: vec3d2.y < stepHeight`） |
| `on_ground` | `bl4 = isOnGround() || (bl2 && movement.y < 0.0)` 的第一项 |
| `shape_count` | `refs[]` 长度；与指针同源（契约 2.1 第 3 条） |
| `flags` | 与 `CavaPathRequest.flags` 一致的前向兼容位（当前必须为 0） |
| `reserved0/1` | 对齐与尾填充；**具名字段**，不用 C 的匿名填充（JDK 21 的 `paddingLayout` 无法命名，P1 踩过） |

**这个结构体刻意极小**：所有「每个生物恒定」的量（`getEntityCollisions` 的结果、世界边界形状、方块形状）
都不在这里，而走形状表 / 形状引用。理由与 P1 的 `CavaMobProfile` 相同：FFM 边界有成本。

### 5.4 `CavaMoveEvent`（80 字节 / 16 字段 / align 8）

    typedef struct CavaMoveEvent {
        int32_t  source;            /* out: 原样回写 CavaMoveShapeRef.source */
        int32_t  axis;              /* out: 产生这次 clamp 的求解轴 0/1/2 */
        int32_t  block_x, block_y, block_z;  /* out: 仅 source==BLOCK 有意义 */
        int32_t  pass;              /* out: 内部第几趟（0..4，见 oracle spec §6.2） */
        int32_t  accepted;          /* out: 1 = 该 offset 通过了 ±1e-7 守卫并并进了 maxDist */
        int32_t  cell_x, cell_y, cell_z;     /* out: 命中的体素单元（真实 xyz 下标） */
        int32_t  reserved0, reserved1;
        int64_t  shape_token;       /* out: 原样回写 */
        double   offset;            /* out: 该形状算出的 d */
        double   max_dist_before;   /* out: 该形状进入前的 maxDist */
        double   max_dist_after;    /* out: 该形状之后的 maxDist */
    } CavaMoveEvent;

| 字段 | 为什么必需 |
| --- | --- |
| `shape_token` | **回放时取回原对象**（5.0） |
| `source` / `block_*` | Java 侧要对**方块来源**的事件按原版顺序跑虚方法（`BlockState.onEntityCollision` / `Block.onSteppedOn` / `Block.onEntityLand`）；实体与边界来源不能跑 |
| `axis` | 「命中类型」：这个形状挡住的是哪个轴。Java 侧据此决定 `horizontalCollision` 相关分支 |
| `pass` | 台阶分支会**多次**调用求解（0=纯碰撞, 1=抬升, 2=竖直探针, 3=位移探针, 4=落回）。回放顺序必须与调用顺序一致 |
| `accepted` | 区分「撞到了但被 1e-7 守卫拒绝」与「撞到并被采纳」—— 两者的 `maxDist` 变化不同 |
| `cell_*` / `offset` / `max_dist_before/after` | **差分定位用**：一旦整服差分出现不一致，这三个量能把范围缩到「哪个形状、哪一格、哪个分支」 |
| `reserved0/1` | 对齐（把 `shape_token` 顶到 48 字节偏移） |

### 5.5 `CavaMoveResult`（88 字节 / 12 字段 / align 8）

    typedef struct CavaMoveResult {
        int32_t  status;           /* out: CAVA_OK / CAVA_ERR_* */
        int32_t  step_used;        /* out: 1 = 最终位移来自台阶分支 */
        int32_t  event_count;      /* out: 实际写入的事件个数 */
        int32_t  event_overflow;   /* out: 1 = 事件数组不足，**已丢弃**（Java 必须回退纯 Java） */
        double   delta_x, delta_y, delta_z;   /* out: 最终位移（= method_17835 的返回值） */
        double   base_x, base_y, base_z;      /* out: 第 0 趟（纯碰撞）结果 */
        double   step_x, step_y, step_z;      /* out: 台阶候选 vec3d */
    } CavaMoveResult;

`base_*` 与 `step_*` 是**额外诊断信息**（原版不返回）。它们不改变 `delta_*`，
但让「不一致发生在哪一趟」可判定 —— 这是 P1 反复强调的「差分要能定位到分支」。

`event_overflow==1` 时 Java **必须回退纯 Java**（否则事件回放不全，等于行为改变）。

### 5.6 入口签名（全部结构体只传指针）

    /* 形状表：按 state id 索引。id_count 必须与 P1 的 CavaStateRecord 表长度一致。
     * 与 P1 的 cava_state_table_upload **分开**：P1 存扁平 AABB（寻路用），
     * P2 必须存 (点表 + 体素位图)（碰撞求解用，见 oracle spec §3.7）。
     * cap 不足返回 CAVA_ERR_ARG 且**不改变已有表**。 */
    int32_t cava_shape_table_upload(int64_t handle,
                                    const CavaShapeRecord* records, int32_t record_count,
                                    const double* points, int32_t point_count,
                                    const uint64_t* bits, int32_t bit_word_count);

    /* 一次实体位移求解。
     * 返回 CAVA_OK / CAVA_ERR_*；具体数值在 out 里。
     * 入口必须校验：句柄有效、req/out 非空、refs 与 ref_count 同源、
     *              每个 ref 的 state_id < record_count 或 inline_slot < inline_shape_count。
     * 任一非法 => 对应错误码，**不写 out、不产生任何副作用**。 */
    int32_t cava_resolve_move(int64_t handle,
                              const CavaMoveRequest* req,
                              const CavaMoveShapeRef* refs, int32_t ref_count,
                              const CavaShapeRecord* inline_shapes, int32_t inline_shape_count,
                              const double* inline_points, int32_t inline_point_count,
                              const uint64_t* inline_bits, int32_t inline_bit_word_count,
                              CavaMoveEvent* events, int32_t event_cap,
                              CavaMoveResult* out);

`inline_*` 三个数组只在有 `kind==INLINE` 的 ref 时才需要；否则可以传 NULL/0。

### 5.7 契约三条如何满足

| 契约条款 | 本提案的满足方式 |
| --- | --- |
| 2.1.1 **结构体只通过指针** | 5 个结构体全部以 `const T*` / `T*` 出现；**没有按值传递、没有返回结构体** |
| 2.1.2 **禁用 `long` / 裸 `char`** | 字段只有 `int32_t/uint32_t/int64_t/double` |
| 2.1.3 **(指针, 长度) 同源** | `refs/ref_count`、`events/event_cap`、`points/point_count`、`bits/bit_word_count`、`records/record_count` 成对出现；越界一律 `CAVA_ERR_ARG` |
| 2.1.8 **非法输入不段错误** | 入口逐项校验（句柄、空指针、count 符号、state_id/inline_slot 越界）；`events` 不足时**只丢事件不越界** |
| 2.3 **数组算一个字段** | 结构体里**没有任何数组字段**；`refs[]`/`events[]`/`points[]`/`bits[]` 都是函数参数，**不进 layout_hash** |
| 2.3 **无内部填充** | 5 个结构体全部由编译器实测确认零内部填充、零尾部填充（见 5.9 的逐字段 offset） |
| 字段顺序（P1 的做法） | 一律 **4 字节字段在前、8 字节字段在后**；需要 8 对齐时用**开头的 `int64_t`** 顶住（与 `CavaPathRequest` 同款） |

### 5.8 语义增量（**必须写进 `cava_abi.h` 注释**的三条）

1. **`kind==STATE` 的形状一律按 EXPLICIT 点表处理**，点表 = 记录点表 + `(block_x, block_y, block_z)`（逐点加，位级精确）；
   即使 `points_kind==FRACTIONAL`。理由：`VoxelShape.offset` 返回的永远是 `ArrayVoxelShape`（oracle spec §3.8）。
2. **`refs[]` 的顺序就是形状表顺序，原生侧绝不重排**（`calculateMaxOffset` 的 1e-7 短路对顺序敏感）。
3. **形状表按 state id 索引，禁止用 `BlockState` 对象身份做键**（与 P1 同一条纪律，FerriteCore 会共享实例）。

### 5.9 布局实测（`cava_abi_proposal_probe.cpp` 的真实输出）

**第一步：layout_hash 实现对拍（9/9 OK，和 = 0x6975CBF9 = 权威值）** —— 见 §2.1。

    -- step 2/3: 提案结构体 --
    CavaShapeRecord    size= 32 align=4
      points_kind off=0/4  point_offset off=4/4  bit_offset off=8/4  bit_words off=12/4
      size_x off=16/4  size_y off=20/4  size_z off=24/4  reserved0 off=28/4
    CavaMoveShapeRef   size= 48 align=8
      shape_token off=0/8  kind off=8/4  state_id off=12/4
      block_x off=16/4  block_y off=20/4  block_z off=24/4
      source off=28/4  inline_slot off=32/4  reserved0 off=36/4  reserved1 off=40/4  reserved2 off=44/4
    CavaMoveRequest    size=104 align=8
      reserved0 off=0/8  min_x off=8/8 ... step_height off=80/8
      flags off=88/4  on_ground off=92/4  shape_count off=96/4  reserved1 off=100/4
    CavaMoveEvent      size= 80 align=8
      source off=0/4 ... cell_z off=36/4  reserved0 off=40/4  reserved1 off=44/4
      shape_token off=48/8  offset off=56/8  max_dist_before off=64/8  max_dist_after off=72/8
    CavaMoveResult     size= 88 align=8
      status off=0/4  step_used off=4/4  event_count off=8/4  event_overflow off=12/4
      delta_x off=16/8 ... step_z off=80/8

    -- step 3: 5 个新结构体的 layout_hash 与投影和 --
      CavaShapeRecord    hash=0x0DCFFE65
      CavaMoveShapeRef   hash=0x1545B999
      CavaMoveRequest    hash=0xB542D3D5
      CavaMoveEvent      hash=0x630C22D5
      CavaMoveResult     hash=0x7737ABBD
      投影 layout_hash_sum = 0x6975CBF9 + 0xB29C5A65 = 0x1C12265E

**⚠️ 一个必须上报的哈希碰撞（契约 §2.3 已预告的弱点，这里给出了第二个实例）**：

    CavaShapeRecord 的 layout_hash = 0x0DCFFE65 == CavaPathNode 的 layout_hash

原因相同：两者都是**连续的 8 个 4 字节字段**（`CavaPathNode` 是 3×i32+1×i32+2×f32+2×u32 = 8 个 4 字节字段）。
**这不构成安全漏洞**（真正的护栏是逐字段 (offset,size) 全表比对），但**必须记录**：
任何以 `layout_hash` 相等来推断「结构体相同」的代码都是错的。

**落地时的必做项**：

1. 把 5 个结构体加进 `native/src/cava_layout.cpp` 的注册表**并**在 Java 的 `cava/ffm/CavaLayouts.java` 同步加；
2. 两侧用**编译器实测的** `offsetof/sizeof` 逐字段比对（不许手抄本文件的表）；
3. 两侧独立复算 `layout_hash_sum`，**以实测为准**（本文件给的是提案投影值 `0x1C12265E`，
   一旦结构体有任何字段调整就作废）。

---

## 6. 未验证 / 留白（**不要当成已完成**）

1. **Java 侧完全没做**：`cava/ffm/**` 的新绑定、`cava/mirror/**` 的形状上传、`cava/mixin/entity/**` 的注入与金丝雀，
   本轮**一行都没写**（不在授权路径内）。**P2 的接管链条目前是断的。**
2. **ABI 未冻结、未编译进 DLL**：`cava_abi.h` 由 captain 持有，本流的 `cava_entity_kernel.cpp` **只被 CMake GLOB 收进 `cava.dll` 的编译单元**，
   但**没有任何 `extern "C"` 入口**、**没有登记布局**。所以它现在**只是被编译**，不会被调用。
   **我也没在 `natives/<平台标签>/` 里构建过**（那是共享输出目录，契约禁止同时刻两个流构建）。
3. **没有真实服务器验证**：没有整服差分、没有 `-Dcava.parity.trace`、没有 TIS/log movement 比对。
   「与同一整合包 native 关闭时逐 tick 一致」**完全未验证**。
4. **没有性能数据**。本流一次都没测过吞吐。**任何性能结论都不许从这里引用。**
5. **形状表的实际数据来源未验证**：`getPointPositions` 是 `protected`，Java 侧拿不到；
   要么加 `@Invoker`/`@Accessor` mixin，要么用 `getBoundingBoxes()` 反推（**有 <1e-7 误差，不推荐**）。
   **这一条是 ABI 落地的第一个阻塞点**，需要 captain 拍板（见第 7 节）。
6. **世界边界形状（点表含 ±Infinity）没有端到端用例**：内核在数学上支持 ±Inf/NaN，但没有向量覆盖它。
7. **`CavaShapeRecord` 的 `layout_hash` 与 `CavaPathNode` 碰撞**（见 5.9）—— 已记录，未消除（契约说哈希只做粗筛）。
8. **mod 自定义 `VoxelShape` 子类**：`points_kind` 只有两档，覆盖不了第三种。
   提案里 `cava_resolve_move` 必须能拒绝：**建议用 `CAVA_ERR_UNIMPLEMENTED` 回退**（见第 7 节请求 3）。
9. **Lithium 的 `@Overwrite` 归属未决**（oracle spec §9.8）：在 captain 拍板「让位/复刻」之前，
   P2 的接管范围是不确定的。

---

## 7. 跨流请求清单

| # | 给谁 | 请求 |
| --- | --- | --- |
| 1 | **captain** | 审第 5 节的 ABI 提案并冻结（5 个结构体 + 2 个入口 + 5.8 的三条语义增量）。**未冻结前本流不再动 ABI** |
| 2 | **captain** | 裁决 **Lithium 对 `method_20736` 的 `@Overwrite`**：让位（P2 零收益）还是复刻（要与 Lithium 的行为对齐，而它自认改了来源顺序） |
| 3 | **captain** | 裁决「Java 侧怎么拿到 `VoxelShape` 的点表」：加 `@Invoker` 调 `getPointPositions`（精确）还是别的路。**这是 P2 接管的第一阻塞点** |
| 4 | **captain** | 提案里 mod 自定义 `VoxelShape` 子类怎么办：建议 `cava_resolve_move` 返回 `CAVA_ERR_UNIMPLEMENTED`，Java 侧回退 |
| 5 | P0-B | 冻结后把 5 个结构体加进 `native/src/cava_layout.cpp` **并**在 Java `CavaLayouts` 同步加；两侧逐字段比对（**不许抄本文件的表**） |
| 6 | P0-C（ffm 流） | 冻结后在 `CavaBindings`/`CavaNative` 里加 `cava_shape_table_upload` / `cava_resolve_move` 的 `MemoryLayout` 与绑定 |
| 7 | P2 注入流 | 事件回放必须**严格按 `events[]` 顺序**执行虚方法；`event_overflow==1` 时**必须整体回退纯 Java** |
| 8 | P2-Oracle / captain | 若要 Java 侧参照实现：读 `native/tests/entity/vectors/entity-00.bin`（CVEM v1，格式见第 4 节）逐位比对 |

---

## 8. 复现

    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-entity.ps1

（脚本会依次：构建并跑 ABI 布局探针 -> 构建并跑内核差分测试 -> 写向量文件。全部 exit=0 才算通过。）

**本机踩到的坑（已固化进脚本）**：

1. g++ 的临时 `.o` 默认落在 `%TEMP%`（含中文），汇编写不进去 -> 脚本把 `TMP`/`TEMP` 指到 `build/native-entity/tmp`。
2. MinGW GCC 15（posix 线程模型）默认动态链 `libwinpthread-1.dll` -> 脚本用 `-static`。
3. PowerShell 5.1 读**无 BOM 的 UTF-8 脚本**（注释含中文）会解析失败 -> `build-entity.ps1` **保持纯 ASCII**。
4. C++ 里没有 `_Alignof`（那是 C11）；用 `alignof`。
5. 定点真值表的期望值**必须挑二进制精确的数**（2^-24 而不是 5e-8；-0.5 而不是 -0.4），
   否则测的是浮点舍入而不是语义。
