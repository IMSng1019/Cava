# Cava P1 镜像侧（方块状态表 + 区域推送）：交付说明与实测台账

# Cava 镜像侧笔记（P1-Java-A）

> **captain 裁决块（2026-09-22，回答本文末尾第 4 条的两个问题）**
>
> **裁决 1：位置依赖的碰撞形状 —— 本轮不扩 ABI，但必须加"防静默发散"的护栏。**
> 现状：一个 state 只有一组盒，表达不了带 `ShapeContext` 的 `getCollisionShape`（20 个方块类覆写，脚手架最典型）。
> 你选了"邻居=空气"的通用视图 —— 这个选择**在脚手架/墙/雪/仙人掌附近会与原版不同**，属于已知 parity 缺口。
>
> **决定**：
> 1. **不扩 ABI**（收益不明确，而扩 ABI 的代价已知：两侧重登记 + `layout_hash_sum` 变化 + 并行流返工）；
> 2. **但绝不能让它静默发散**：接线时加一个**保守守卫** —— 如果本次求解的候选区域里可能出现
>    位置相关形状的方块（用你实测的那 20 个类做判定，或更保守地按方块类型白名单），
>    **就直接回退原逻辑**（不加速，也不出错）；
> 3. 把这条**登记为后续独立议题**（"按区域/按位置推送形状"的 ABI 扩展），需要时再立项。
>
> **裁决 2：`FENCE_GATE_OPEN` 不追加新位。** 你用 `CAVA_SF_OPEN` 兼任、并已在 §2 表里写明
> "栅栏门分支内二者等价" —— 这个处理是对的。**将来要区分时，从 bit27 往上追加，绝不复用已用位。**
>
> **裁决 3：`reserved_max_fall_distance`。** 你在 §4 提到它没有来源；captain 已把它改名为
> `reserved_max_fall_distance`（内核不读，真正生效的是 `safe_fall_distance`），填 0 即可。
> 详见 `native/include/cava_abi.h` 里的注释。
>
> **裁决 4：区域推送开销是"可接受但要计入"的。** 你实测 20.5 µs/次（3458 方块）——
> 这个量级**相对一次寻路是可接受的**，但**必须计入收益估算**（否则会出现"内核快了、端到端没快"）。
> 接线流已被要求把推送耗时纳入 native on/off 的端到端对比。
>
> **裁决 5：标签位"未验证"这条要当成硬约束执行。** 你的探针不加载数据包 ⇒ `BlockTags/FluidTags` 全空
> （`PF_TRAPDOOR=33=1+32` 是很硬的证据）。**真实服务器下这些位的计数会变大**，
> 所以**单测全绿不能当作"已与原版对齐"**；接线后必须在真实服务端里再对拍一次。



> 作者：P1-Java-A 流（镜像侧）。**所有结论都有本机实测证据**，未验证的一律写「未验证」。
> 语义权威：`docs/CAVA-pathfind-oracle-spec.md`（javap 转写）与 `native/include/cava_abi.h`（冻结 ABI）。
> 本轮交付：`src/main/java/cava/mirror/**`（21 个类）+ `src/test/java/cava/mirror/**`（6 个测试类）。

---

## 0. 一句话状态

`RegionSource` 已实现（`RegionMirror`）：**state id 键、扁平 AABB 集合、x 最快 y 最慢的区域推送、
一次性状态表上传**全部落地并用真实注册表实测。
一个**跨流阻断级发现**已修掉：冻结头文件的 `CAVA_PF_*` 与内核实际读的 `PF_*` **19 个位全部错位**
（详见 §1），现改为"内核 PF_* = 冻结宏的别名"，10060 组原生向量复跑**逐位一致**。

---

## 1. 【重要】flags 位：一次真实的静默不一致，以及修法

### 1.1 事实（实测，不是推测）

把 `native/include/cava_abi.h` 与 `native/src/pathfind/cava_pf.h` 的位号**程序化对拍**：

| bit | `cava_abi.h`（Java 写） | `cava_pf.h`（内核读，改号前） | |
| --- | --- | --- | --- |
| 8 | `CAVA_PF_TRAPDOOR` | `PF_AIR` | ❌ |
| 15 | `CAVA_PF_RAIL` | `PF_DOOR` | ❌ |
| 17 | `CAVA_PF_FENCES` | `PF_DOOR_HAND` | ❌ |
| 20 | `CAVA_PF_FIRE_DAMAGE` | `PF_FENCE_TAG` | ❌ |
| 25 | `CAVA_PF_FIRE` | `PF_PATHFIND_LAND` | ❌ |
| 26 | `CAVA_PF_WITHER_ROSE` | `PF_WATER_BLOCK` | ❌ |

**19/19 个位置全部不一致**。后果：空气读成活板门、铁轨读成门、火焰读成可通行、凋灵玫瑰读成水方块 ——
而且 `cava_pathfind` 当时返回 `CAVA_ERR_UNIMPLEMENTED`，**运行期没有任何可观测行为能发现它**。
这是"看起来正常但与原版不一致"的教科书形态。

### 1.2 修法（captain 2026-09-22 裁决：同意）

**不需要改 `cava_abi.h`**：内核需要的 19 个谓词，冻结头文件全都能精确表达 ——
用 `CAVA_SF_AIR` / `CAVA_SF_DOOR` / `CAVA_SF_OPEN` 承担 `PF_AIR` / `PF_DOOR` /
`PF_DOOR_OPEN`+`PF_FENCE_GATE_OPEN`。于是把 `cava_pf.h` 的 `PF_*` **改成冻结宏的别名**
（纯改号、零逻辑改动），**位布局只剩一处事实来源**。

**回归证据**（`native/tests/build-pathfind.ps1` + 向量回放，命令见 §5）：

    [shard] src/test/resources/cava/oracle/vectors-00.bin  version=1 cases=10000 master=1f2e3d4c5b6a7988 shard=0/1
      cases=10000 mismatches=0 worldHashFail=0 goldenBlockMismatch=0
    [shard] src/test/resources/cava/oracle/golden-00.bin  version=1 cases=60 ...
      cases=60 mismatches=0 worldHashFail=0 goldenBlockMismatch=0
    RESULT: PASS (truthTableFails=0, abiSmokeFails=0, shards=1)

### 1.3 ⚠️ 这条修法**还没进 cava.dll**

我改的是源码；`natives/windows-x64/cava.dll` 还是旧的（构建原生产物是别的流的事，
且 `natives/` 是共享输出目录）。**下一次 `cava.dll` 构建之前，DLL 里仍是旧位号**。
现在无影响（`cava_pathfind` 保守返回 UNIMPLEMENTED），但**接线时必须先重编 DLL**。

---

## 2. `CAVA_PF_*` **逐位定义表**（本轮核心交付）

键一律是 **state id**（`Block.getRawIdFromState` / `getStateFromRawId`），**禁止对象身份**
（FerriteCore 的 blockstateCacheDedup 让内容相同的状态共享实例）。
"命中状态数"= 真实注册表上 26644 个状态里置位的个数（**探针环境的标签为空，见 §2.3 警告**）。

| bit | `cava_abi.h` 宏 | 内核别名（改号后） | 原版判据（spec §5.4.6 的分支） | 命中状态数 | 内核读 |
| --- | --- | --- | --- | --- | --- |
| 0 | `CAVA_SF_SOLID` | — | `state.isSolid()`（**该 API 在 1.20.4 已 @Deprecated**） | 22723 | ✗ |
| 1 | `CAVA_SF_BLOCKS_MOTION` | — | `state.blocksMovement()`（同上，已 @Deprecated） | 22721 | ✗ |
| 2 | `CAVA_SF_FLUID` | — | `!state.getFluidState().isEmpty()` | 9245 | ✗ |
| 3 | `CAVA_SF_WATER` | — | `state.getFluidState().isIn(FluidTags.WATER)`（含流动水/含水方块） | **0**※ | ✓（`fluid_of`） |
| 4 | `CAVA_SF_LAVA` | — | `state.getFluidState().isIn(FluidTags.LAVA)` | **0**※ | ✓（`fluid_of`） |
| 5 | `CAVA_SF_OPEN` | `PF_DOOR_OPEN` + `PF_FENCE_GATE_OPEN` | `state.contains(Properties.OPEN) && state.get(Properties.OPEN)` | 1462 | ✓ |
| 6 | `CAVA_SF_AIR` | `PF_AIR` | `state.isAir()`（分支 1，最高优先级） | 3 | ✓ |
| 7 | `CAVA_SF_DOOR` | `PF_DOOR` | `state.getBlock() instanceof DoorBlock` | 1280 | ✓ |
| 8 | `CAVA_PF_TRAPDOOR` | `PF_TRAPDOOR` | `isIn(BlockTags.TRAPDOORS) \|\| isOf(LILY_PAD) \|\| isOf(BIG_DRIPLEAF)` | 33※ | ✓ |
| 9 | `CAVA_PF_POWDER_SNOW` | `PF_POWDER_SNOW` | `isOf(POWDER_SNOW)` | 1 | ✓ |
| 10 | `CAVA_PF_CACTUS_OR_BERRY` | `PF_CACTUS_OR_BERRY` | `isOf(CACTUS) \|\| isOf(SWEET_BERRY_BUSH)` | 20 | ✓ |
| 11 | `CAVA_PF_HONEY` | `PF_HONEY` | `isOf(HONEY_BLOCK)` | 1 | ✓ |
| 12 | `CAVA_PF_COCOA` | `PF_COCOA` | `isOf(COCOA)` | 12 | ✓ |
| 13 | `CAVA_PF_CAUTIOUS` | `PF_CAUTIOUS` | `isOf(WITHER_ROSE) \|\| isOf(POINTED_DRIPSTONE)` | 21 | ✓ |
| 14 | `CAVA_PF_DOOR_HAND` | `PF_DOOR_HAND` | `((DoorBlock) block).getBlockSetType().canOpenByHand()` | 1216 | ✓ |
| 15 | `CAVA_PF_RAIL` | `PF_RAIL` | `block instanceof AbstractRailBlock` | 92 | ✓ |
| 16 | `CAVA_PF_LEAVES` | `PF_LEAVES` | `block instanceof LeavesBlock` | 280 | ✓ |
| 17 | `CAVA_PF_FENCES` | `PF_FENCE_TAG` | `state.isIn(BlockTags.FENCES)` | **0**※ | ✓ |
| 18 | `CAVA_PF_WALLS` | `PF_WALL_TAG` | `state.isIn(BlockTags.WALLS)` | **0**※ | ✓ |
| 19 | `CAVA_PF_FENCE_GATE` | `PF_FENCE_GATE` | `block instanceof FenceGateBlock` | 352 | ✓ |
| 20 | `CAVA_PF_FIRE_DAMAGE` | `PF_FIRE_DAMAGE` | `isIn(FIRE) \|\| isOf(LAVA) \|\| isOf(MAGMA_BLOCK) \|\| CampfireBlock.isLitCampfire(state) \|\| isOf(LAVA_CAULDRON)` | 18※ | ✓ |
| 21 | `CAVA_PF_PATH_THROUGH_LAND` | `PF_PATHFIND_LAND` | `state.canPathfindThrough(view, pos, NavigationType.LAND)` | 6775 | ✓ |
| 22 | `CAVA_PF_WATER_BLOCK` | `PF_WATER_BLOCK` | `isOf(Blocks.WATER)`（区别于"含流体"） | 16 | ✓ |
| 23 | `CAVA_PF_FENCE_OR_WALL_CLOSED` | —（派生） | `(FENCES \|\| WALLS \|\| (FENCE_GATE && !OPEN))` = 分支 13 整个析取式 | 176※ | ✗ |
| 24 | `CAVA_PF_DOOR_IRON` | —（派生） | `DOOR && !DOOR_HAND` | 64 | ✗ |
| 25 | `CAVA_PF_FIRE` | —（派生） | `isIn(BlockTags.FIRE)` | **0**※ | ✗ |
| 26 | `CAVA_PF_WITHER_ROSE` | —（派生） | `isOf(WITHER_ROSE)` | 1 | ✗ |

※ = **受"标签为空"影响的计数**，见 §2.3；这些位**不是**填错，是探针环境没有加载数据包标签。

### 2.1 内核消费方式（**重要**）

`native/src/pathfind/cava_pf_kernel.cpp` 的 `common_node_type()` 是
`getCommonNodeType` 的 16 步逐分支镜像，**只读 flags**：`SF_AIR` → `TRAPDOOR` → `POWDER_SNOW` →
`CACTUS_OR_BERRY` → `HONEY` → `COCOA` → `CAUTIOUS` → `LAVA(fluid)` → `FIRE_DAMAGE` →
`DOOR(OPEN/HAND)` → `RAIL` → `LEAVES` → `FENCES|WALLS|(GATE&&!OPEN)` → `!PATH_THROUGH_LAND` →
`WATER(fluid)` → `OPEN`。分支顺序 = 优先级，**这一条不许重排**。

### 2.2 `path_type_idx` / `malus` 的正确语义（**结论：内核当前不读，别再往这里投资**）

| 环节 | 依赖实体？ | 依赖位置？ | 能放进"每状态一个常量"吗 |
| --- | --- | --- | --- |
| `getCommonNodeType`（16 步） | **否** | **否**（只用 state + 该 pos 的流体） | ✅ 可以，且这就是 `path_type_idx` 的定义 |
| `getNodeTypeFromNeighbors` | 否 | **是**（3×3×3 邻居） | ❌ 由原生按区域镜像算 |
| `getNodeType`（Land 1294） | **是**（`getPathfindingPenalty`、`entityBlockXSize`） | 是 | ❌ 由原生按 flags + 生物档案算 |
| `adjustNodeType` | **是**（`canOpenDoors`/`canEnterOpenDoors`，且 RAIL 分支用**实体的方块坐标**） | 是 | ❌ 同上 |

**实测事实**（`grep` 全文件）：`cava_pf_kernel.cpp` 里 `path_type_idx` 只在 `kOutOfWorld`
初始化器里出现过一次，`malus` 一次都没有 —— **内核不用它们**，类型完全由 flags 推。
所以：`path_type_idx` = `getCommonNodeType(state)` 的真实 ordinal（**不是**头文件那套错的
`CAVA_PNT_*`，见 §4.4），`malus` = 该类型的默认惩罚；两者**仅作文档/未来用**，
但既然填了就必须填对（`MirrorFlags.commonNodeType(flags)` 与"按原版方块直接算"在
**26644 个真实状态上 0 不一致**，见 §4.2）。

### 2.3 ⚠️ 探针环境的已知偏差（**不要误读上面的计数**）

本轮的"真实注册表"测量是在**进程内 `Bootstrap.initialize()`** 的环境里做的，
它**不加载原版数据包** ⇒ **所有 `BlockTags.*` 与 `FluidTags.*` 都是空的**。
铁证：`PF_TRAPDOOR` 只剩 33 = 1（LILY_PAD）+ 32（BIG_DRIPLEAF），
`SF_WATER`/`SF_LAVA`/`PF_FENCES`/`PF_WALLS`/`PF_FIRE` 全 0。

⇒ 受影响的只有**标签驱动**的位；`isOf(...)` / `instanceof` / 流体之外的那些位是准的。
**上表的"命中状态数"在真实服务器里会变大**（例如 TRAPDOOR 会到几百）。
**未验证**：真实服务器（带数据包）下的逐位计数。补齐办法见 §6.3。

---

## 3. 碰撞盒与区域推送

### 3.1 碰撞盒取法与"位置依赖"的处理（**我的选择与理由**）

- 取法：`state.getCollisionShape(view, pos)` → `VoxelShape.getBoundingBoxes()` →
  **扁平 AABB 集合**（契约要求：不是体素近似）。
- **形状依赖位置**（栅栏/墙/铁栏杆/玻璃板的连接、脚手架吃 `ShapeContext`），
  而冻结 ABI 里"一个 state id = 一条记录 = 一组盒"，**表达不了按位置不同的形状**。
- **本轮选择**：`ProbeWorldView.MODE_EMPTY` —— 所有被查询的邻居位置返回**空气**。理由：
  1. 对**孤立方块**（最常见的单根栅栏/独立墙柱/单块玻璃板）这是**精确值**；
  2. 连成一排时它**低估**了 2/16 厚的连接条，而连接条指向的**邻居本身就是同类不可通行方块**
     （FENCE/铁栏杆都是 `PF_FENCES`/`PT_FENCE`，别人占用的节点本来就不可通行）；
  3. 另一个候选 `MODE_SELF`（邻居=自身=全连接）是**最大**形状，会把"紧贴孤立方块的格子"
     判成不可站 —— 对 parity 的破坏面更大。
- 两种模式都在代码里（`ProbeWorldView`），`BlockStateTable.census()` 可随时对拍。
- **静态风险清单（实测）**：真实注册表里有 **20 个方块类**覆写了
  `getCollisionShape(BlockState, BlockView, BlockPos, ShapeContext)`：
  `BambooBlock, BellBlock, BigDripleafBlock, CactusBlock, ComposterBlock, FenceGateBlock, FluidBlock,
  GrindstoneBlock, HoneyBlock, LecternBlock, MudBlock, PistonExtensionBlock, PitcherCropBlock,
  PowderSnowBlock, ScaffoldingBlock, SculkShriekerBlock, SnowBlock, SoulSandBlock, WallBlock,
  WallHangingSignBlock`；覆写 `getCollisionShape(BlockState,BlockView,BlockPos)`（不带 context）的 **0 个**。
  这些是"ABI 表达不了"的**精确清单**（`ScaffoldingBlock` 尤其明显：它用 `context.isAbove(...)`，
  而真实碰撞查询走的是 `ShapeContext.of(entity)`，我们只能给 `absent()`）。
- **未验证**：`MODE_EMPTY` 与真实服务器（带标签、带实体上下文的 `getBlockCollisions`）之间的**逐状态差异**。
  由于 §2.3 的原因（标签为空），`census()` 在探针里返回"一致 26644 / 不同 0"，
  **这个 0 不能当作"形状与位置无关"的证据**。
- **留给后续流的口子**：真正要精确，需要**按位置推送形状**（新增原生入口 / ABI 扩展，
  例如"区域内的 per-position 形状变体表"）。**这需要 captain 裁决，我没有私自动 ABI。**

### 3.2 区域推送

- 布局：`RegionRect`（有界长方体）→ `RegionReader.fill`（x 最快、y 最慢）→
  `Arena.ofConfined()` + `CavaNative.allocateArray` + `MemorySegment.copy` → `cava_region_upload`。
- 数据来源：`ServerWorld → WorldChunk.getSectionArray() → ChunkSection.getBlockState(x,y,z)`，
  **按 (区块, 区段) 取一次 section 再扫局部坐标**；`section.isEmpty()` 整段跳过（全空气）；
  区块未加载 → **抛 `MirrorUnavailableException`**（显式失败，不猜）。
- 窗口策略（唯一推导处 `RegionRect.forSolve`，每条边距都有字节码出处）：
  - 水平 `max(4, floor(width/2)+2)`：`getNodeTypeFromNeighbors` 的 3×3×3（±1）、
    `findNearbyNodeTypes` 的 `floor(width+1)` 格、第 8 步以节点角点为中心再外扩 halfWidth；
  - 向上 `max(4, floor(height+1)+1)`：第 8 步递归 `y+1`、身高格数、目标节点 ±1；
  - 向下 `safeFallDistance + 4`：第 10 步"一直往下掉"最多 safeFallDistance 层；
  - Y 裁剪到世界高度；X/Z 不裁剪（世界无界），但要求区块已加载。
- 失效（**本轮只要最简方案**）：`push()` **默认每次重推**（数据必然新鲜）；
  `pushReusingSameTick(rect)` 在同 tick/同维度/同矩形时可跳过（默认不用，因为会漏 tick 内方块变化）；
  `onSectionUnloaded / onBlockChanged / onWorldChanged` 是**留给后续脏跟踪流的口子**
  （目前只记数 + 让同 tick 复用失效）。

---

## 4. 实测台账（真实命令 + 真实输出）

### 4.1 构建 / 单测 / 原生向量

    PS J:\mc\Cava> $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; $env:TMP='J:\mc\Cava\build\tmp'; $env:TEMP=$env:TMP
    PS J:\mc\Cava> .\gradlew.bat build --console=plain --no-watch-fs --no-configuration-cache --rerun-tasks
    BUILD SUCCESSFUL in 8s
    12 actionable tasks: 12 executed

    （JUnit，逐类实测）TOTAL tests=139 failures+errors=0 skipped=9
      cava.mirror.MirrorFlagsTest        tests=4  failures=0
      cava.mirror.PathTypesTest          tests=4  failures=0
      cava.mirror.StateTableBuilderTest  tests=5  failures=0
      cava.mirror.RegionRectTest         tests=5  failures=0
      cava.mirror.RegionMirrorTest       tests=10 failures=0
      cava.mirror.RegionMirrorPerfTest   tests=1  failures=0
      cava.mirror.McStateTableProbeTest  tests=7  skipped=7   ← 见 §4.3 的原因与绕法
      cava.ffm.PathfindAbiTest           tests=6  failures=0   （真 DLL，native 在测试里是开着的）

    原生向量复跑（改号之后）：
    PS J:\mc\Cava> powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-pathfind.ps1
    BUILT: J:\mc\Cava\build\native-pathfind\cava_pathfind_vectors.exe (731243 bytes)
    PS J:\mc\Cava> .\build\native-pathfind\cava_pathfind_vectors.exe src/test/resources/cava/oracle
    RESULT: PASS (truthTableFails=0, abiSmokeFails=0, shards=1)
      10000 + 60 组，mismatches=0 worldHashFail=0 goldenBlockMismatch=0

### 4.2 真实状态表（**26644 个状态**，独立探针跑出来的）

    [probe] MC 注册表 bootstrap 成功
    [probe] 真实状态数 = 26644
    [probe] getRawIdFromState(AIR) = 0 (CAVE_AIR=12959, VOID_AIR=12958)   ← 契约点名的待验假设：**成立**
    [probe] build=true wall=111 ms      （内部计时 buildNanos = 86.28 ms）
    [probe] records=26644 boxes=47224 noBoxStates=5011 maxBoxPerState=15
    [probe] 内存估算 records=520 KiB boxes=1106 KiB                       （≈1.6 MiB）
    [probe] native opened=true status=OPEN handle=4294967297
    [probe] upload=true wall=22 ms ffm=1.4915 ms                          （FFM 段 1.49–1.71 ms）
    [probe] flags→commonNodeType 逐状态对拍：共 26644 条，不一致 0          ← 位填错的报警器
    [probe] PathNodeType 对拍：values=26 问题=0
    [probe] 位置依赖碰撞形状普查：一致 26644 / 不同 0 / 共 26644           ← 见 §3.1 的 caveat（标签为空）
    [probe] 方块数=1058
    [probe] 覆写 canPathfindThrough(BlockView,BlockPos,NavigationType)=0 []
    [probe] 覆写 getCollisionShape(BlockState,BlockView,BlockPos,ShapeContext)=20 [...]
    [probe] 覆写 getCollisionShape(BlockState,BlockView,BlockPos)=0 []

**`canPathfindThrough` 覆写数 = 0 是一条好消息**：`PF_PATH_THROUGH_LAND` 这个位
在 1.20.4 里**没有任何方块覆写**（全部走 `AbstractBlock` 的默认实现 ⇒ `!isFullCube(view,pos)`），
所以"每个状态一个布尔"对它是成立的（位置依赖只可能通过 `isFullCube` 的形状进来）。

### 4.3 区域推送：耗时 vs 窗口尺寸

```
[perf] === 区域推送耗时 vs 窗口尺寸（假世界 fill + 真实 FFM 上传，reps=200 x rounds=4，取最快一轮）===
[perf] 窗口(dimX x dimY x dimZ)  方块数   总us/次   fill_us   分配拷贝_us   FFM上传_us   ns/方块
[perf] 16x16x16                     4096      5.40      1.79         1.59        1.43      1.318
[perf] 24x16x24                     9216      4.49      2.27         1.19        0.75      0.487
[perf] 32x24x32                    24576      7.61      4.74         1.51        1.15      0.310
[perf] 48x24x48                    55296     15.95      8.99         3.38        3.29      0.288
[perf] 64x40x64                   163840     61.86     23.35        13.74       24.19      0.378
[perf] 96x48x96                   442368    600.19     59.24       248.99      246.59      1.357
```
（假世界的 fill 只是数组写，是**下界**；真实世界每方块还要读 `ChunkSection` 并做一次
`Block.getRawIdFromState`。）

**真实口径**（探针里让 fill 真的做 `getStateFromRawId` + `getRawIdFromState`）：

    [probe] getRawIdFromState: 17900 ns / 1024 = 17.48 ns/次      ← 随机扫 1024 个状态的**最坏**口径
    [probe] IdentityHashMap 缓存命中: 32500 ns / 1024 = 31.74 ns/次 ← **本地缓存反而更慢，不要缓存**
    [probe] 真实口径推送 19x14x13  cells=3458   =  20.48 us/次（5.922 ns/方块）  ← 典型窗口（RegionRect.forSolve 默认边距）
    [probe] 真实口径推送 49x12x9   cells=5292   =  27.95 us/次（5.282 ns/方块）
    [probe] 真实口径推送 64x40x64  cells=163840 = 925.46 us/次（5.649 ns/方块）

**结论（给接线流的推荐）**：
- **推荐窗口 = `RegionRect.forSolve` 的默认边距**（本例 19×14×13 ≈ 3.5k 方块）⇒ **≈20–30 µs/次**，
  与"短距离寻路"同量级，**不能忽略**；
- 因此**不建议对每个 tick 的每次寻路都无脑重推**：优先用
  (a) `pushReusingSameTick`（同 tick 同矩形跳过）、
  (b) 只在该生物真的要寻路时才推（原版 `MobEntity` 不是每 tick 都寻路）；
- 64³ 以上（≥160k 方块）单次 **0.9 ms**，**不要**用这么大窗口；
- **未验证**：真实 `ChunkSection` 的调色板读取增量（没有世界构造不出 `ChunkSection`：
  1.20.4 的 biome 是动态注册表，单测里拿不到 `Registry<Biome>`）。

### 4.4 顺带钉死的一条：`CAVA_PNT_*` 数值是错的（内核不受影响）

头文件 `CAVA_PNT_*` 从索引 5 起与真实 ordinal 全部错位。**Java 侧一律用
`PathNodeType.ordinal()`**（`PathTypes`），原生内核内部也用实证 ordinal（`PT_*`），
所以**这条不需要改任何代码**，只需要**不要有人去用头文件常量索引数组**。
`PathTypesTest.headerPinTableIsKnownWrong` 把"错在哪"钉死在测试里。

---

## 5. 复现命令

    # 1) 构建 + 全部单测
    $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; $env:TMP='J:\mc\Cava\build\tmp'; $env:TEMP=$env:TMP
    .\gradlew.bat build --console=plain --no-watch-fs --no-configuration-cache --rerun-tasks

    # 2) 原生向量（改号回归）
    powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-pathfind.ps1
    .\build\native-pathfind\cava_pathfind_vectors.exe src/test/resources/cava/oracle

    # 3) 真实注册表探针（26644 状态 / air id / 耗时 / 普查）
    #    源码在仓库里：src/test/java/cava/mirror/probe/{McProbeMain,WideningLauncher}.java
    #    启动脚本在 build/mirror-probe/run.ps1（临时件，被 gitignore；重建方法见下）
    powershell -NoProfile -ExecutionPolicy Bypass -File build/mirror-probe/run.ps1

### 5.1 探针脚本怎么重建（`build/` 被清掉之后）

1. 导出测试运行期 classpath（**不改 build.gradle**，用 init 脚本）：

       # build/mirror-probe/probe.init.gradle
       allprojects { tasks.register('cavaPrintTestCp') { doLast {
         def ts = project.extensions.findByName('sourceSets')?.findByName('test')
         if (ts != null) { def f = new File(project.rootDir, 'build/mirror-probe/testcp.txt')
           f.parentFile.mkdirs(); f.text = ts.runtimeClasspath.asPath; println 'WROTE ' + f.absolutePath } } } }

       .\gradlew.bat --init-script build/mirror-probe/probe.init.gradle cavaPrintTestCp --console=plain --no-watch-fs

2. 编译（**必须分成两个输出目录**：Launcher 归父加载器，探针主体归子加载器）：

       javac --release 21 --enable-preview -cp <testcp> -d build/mirror-probe/classes-boot \
             src/test/java/cava/mirror/probe/McProbeMain.java
       javac --release 21                  -cp <testcp> -d build/mirror-probe/classes-launcher \
             src/test/java/cava/mirror/probe/WideningLauncher.java

3. 跑（**父 cp 里不能有 minecraft、不能有 main/test classes**；参数走 `@argfile`，
   **PS 5.1 下 argfile 必须 UTF-8 无 BOM**，否则 javac 报「无效的标记: ?--release」）：

       java --enable-preview --enable-native-access=ALL-UNNAMED -cp <deps;classes-launcher> \
            cava.mirror.probe.WideningLauncher \
            "<classes-boot>;<build/classes/java/main>;<minecraft-common.jar>" \
            cava.mirror.probe.McProbeMain "<natives/windows-x64/cava.dll>"

---

## 6. 未验证 / 已知风险 / 请求

1. **未验证**：真实服务器（带数据包标签）下的逐位命中数（§2.3）；真实 `ChunkSection` 调色板读取增量（§4.3）；
   `MODE_EMPTY` 形状与真实 `getBlockCollisions(entity, box)` 的逐状态差异（§3.1）。
2. **未验证**：`CavaMobProfile.max_fall_distance` **在 1.20.4 里找不到来源**
   （`MobEntity`/`EntityNavigation` 都没有 `getMaxFallDistance`，已 javap 逐个确认）——
   我填 0 并在代码里注明；内核当前不读它。**建议 captain 裁决删字段或写清来源**。
3. **给 B（注入流）的两条硬事实**：
   - 内核**只读 flags**，别指望 `path_type_idx`；```RegionSource.isProfileReadyForSolve```
     的语义是"表已上传 + 该 profile 的 caps/惩罚表有出处"，不是"parity 已验证"。
   - **档案必须每次求解前重推**（位姿在 `CavaMobProfile` 里，而 `CavaPathRequest` 没有起点字段）；
     调用方要先用 `MobProfiles.define(spec)`（或 `McMobProfileCapture.of(mob, world)`）再 upload。
4. **需要 captain 裁决的新入口**（我没动 ABI）：
   - 若要精确表达**位置依赖的碰撞形状**，需要"按区域/按位置推送形状"的原生入口（ABI 扩展）；
   - `FENCE_GATE_OPEN` 目前在头文件里**没有独立位**，我用 `CAVA_SF_OPEN` 兼任
     （在栅栏门分支内二者等价，已写进 §2 表）；若将来需要区分，要在头文件末尾追加新位。
5. **★ 未验证（本机环境限制）**：`McStateTableProbeTest`（7 个用例）在 JUnit 里**必然 skip** ——
   普通测试 JVM 没有 Fabric 的 access widener，`Bootstrap.initialize()` 会因
   `IllegalAccessError: SimpleRegistry → RegistryEntry$Reference.setRegistryKey`（包私有）而失败。
   我用一个**独立探针**绕过了它：源码已进仓库（`src/test/java/cava/mirror/probe/McProbeMain.java` +
   `WideningLauncher.java`，后者用 ASM 把 `net.minecraft.**` 的成员全部加宽成 public）。
   §4.2 的数字全部来自它，重建步骤见 §5.1。
   **未验证**：这条绕法只在探针里成立；**产品路径不受影响**（真服务器由 Fabric 自己应用 access widener）。