# Cava P1 寻路：原生内核交付说明与实测台账

> 作者：W2-P1 流（多代理并行开发第二轮）。**结论全部有本机实测证据**，未验证的一律标注。
> 语义权威：\`docs/CAVA-pathfind-oracle-spec.md\`（javap 转写）+ 本机 javap 复核 + 本地 javac 判定实验。

---

## 0. 一句话状态

**原生内核完成，与 10000 组参照向量 + 60 组 golden 向量逐位一致（0 差异）。
ABI 缺口已由 captain 采纳并扩展（广播 #6：新增 \`CavaMobProfile\` / \`cava_mob_profile_upload\` /
11 个 \`CAVA_NAV_*\` 能力位 / \`max_range\` / 9 个结构体登记，新 \`layout_hash_sum=0xC04A5791\`），
C++ 侧已按新 ABI 改完并编译通过。
**仍保守回退**：\`cava_pathfind\` 在输入齐全时返回 \`CAVA_ERR_UNIMPLEMENTED\` —— 因为
\`CAVA_PNT_*\` 的序号表与 javap 实证的 \`PathNodeType\` 枚举 ordinal **不一致**（见第 8 节），
猜着映射会产出"看起来正常但与原版不一致"的路径。** 详见第 4 / 8 节。

---

## 1. 交付物

| 路径 | 内容 |
| --- | --- |
| \`native/src/pathfind/cava_pf.h\` | 内核接口：PathNodeType 表、状态标志位、WorldView、MobProfile、SolveParams/Result、\`diagonal_side_rejected\` |
| \`native/src/pathfind/cava_pf_kernel.cpp\` | PathMinHeap + LandPathNodeMaker(+Amphibious) + PathNodeNavigator 的逐分支复刻 |
| \`native/src/pathfind/cava_pf_abi.cpp\` | ABI 入口：\`cava_state_table_upload\` / \`cava_region_upload\` / \`cava_region_clear\` / \`cava_region_state_id_at\` / \`cava_pathfind\` |
| \`native/tests/cava_pathfind_vectors.cpp\` | 跨语言差分测试：读冻结向量 + 重建地形 + 逐位比对 + 定点真值表 + ABI 冒烟 |
| \`native/tests/build-pathfind.ps1\` | 独立构建脚本（**不动 P0-A 的 \`native/tests/CMakeLists.txt\`**） |
| \`build/probe/DiagProbe.java\` | isValidDiagonalSuccessor 极性的判定性实验（构建产物，见第 3 节） |

未做（卡点见第 4/6 节）：Java 侧 \`cava/mirror/**\` 与注入 \`cava/mixin/pathfind/**\`。

---

## 2. 实测证据（真实命令 + 真实输出）

### 2.1 构建 + 差分测试

    PS J:\mc\Cava> powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-pathfind.ps1
    srcs=8
    BUILT: J:\mc\Cava\build\native-pathfind\cava_pathfind_vectors.exe (717649 bytes)

    PS J:\mc\Cava> .\build\native-pathfind\cava_pathfind_vectors.exe src/test/resources/cava/oracle
    === Cava P1 pathfind native differential test ===
    [truth-table] diagonal_side_rejected: 8/8 rows OK  (PASS)
    [abi] cava_open=0 handle=4294967297 native_layout_sum=6149fd30
    [abi] state_table_upload=0 (expect 0)
    [abi] region_upload=0 (expect 0)
    [abi] state_id_at(1,0,0)=1 (expect 1)
    [abi] state_id_at(5,0,0)=-1 (expect -1, 区域外)
    [abi] state_id_at(0,1,0)=0 (expect 0)
    [abi] region_upload(id_count=7)=-4 (expect -4)
    [abi] cava_pathfind(ok args)=-7 (expect -7 = CAVA_ERR_UNIMPLEMENTED: ABI 缺生物档案)
    [abi] cava_pathfind(flags!=0)=-4 (expect -4)
    [abi] cava_pathfind(cap=0)=-4 (expect -4)
    [abi] cava_pathfind(伪造句柄)=-3 (expect -3)
    [abi] cava_close=0 (expect 0); closeAgain=-3
    [abi] smoke: PASS
    [shard] src/test/resources/cava/oracle/vectors-00.bin  version=1 cases=10000 ... shard=0/1
      cases=10000 mismatches=0 worldHashFail=0 goldenBlockMismatch=0
    [shard] src/test/resources/cava/oracle/golden-00.bin  version=1 cases=60 ...
      cases=60 mismatches=0 worldHashFail=0 goldenBlockMismatch=0
    RESULT: PASS (truthTableFails=0, abiSmokeFails=0, shards=1)

耗时 **0.43 s / 10060 组**（含地形重建）。

**比对粒度（不是抽样）**：每组逐节点比 x/y/z、typeOrdinal、visited，
以及 5 个 float 的**原始位模式**（pathLength / penalizedPathLength /
distanceToNearestTarget / heapWeight / penalty），另加 expandedCount、traceHash、
manhattanDistanceFromTarget、reachesTarget。

**地形重建也被验证**：10000 组的 \`worldHashLow\` 全部命中；golden 的 60 组
\`blocks[]\` 与原生重建结果逐字节相同。

### 2.2 与向量哈希核对（captain 发的权威值）

| 文件 | 大小 | SHA-256（实测） | 与 captain 的值 |
| --- | --- | --- | --- |
| golden-00.bin | 276856 | \`0AB5DD01…EAB212\` | ✅ 一致 |
| vectors-00.bin | 3868553 | \`A030904F…FF811999\` | ✅ 一致 |
| manifest.txt | 452 | \`34D5396C…31B74E\` | ✅ 一致 |
| vectors-01.bin | 65510 | \`5C485B9C…A43370\` | 哈希一致，**但该文件已不是有效分片，见下** |

### 2.3 ⚠️ \`vectors-01.bin\` 是**上一轮的残留**，不是"没命中分支的分片"

实测它**前 4 个字节不是 \`CVOV\`**，头里也不是任何合法头：

    vectors-00.bin magic='CVOV' head=43 56 4f 56 00 01 00 00 00 00 27 10 ... 00 00 00 00 | 00 00 00 01
                                                                 ^caseCount=0x2710=10000  ^shard=0/1
    vectors-01.bin magic='  &q'  head=00 00 26 71 ff 1f 08 87 00 00 00 9f ...

**新集合只有一个分片**：\`vectors-00.bin\` 的头写着 \`caseCount=10000, shardIndex=0, shardCount=1\`，
10000 组全在里面（本轮实测就是从它一个文件里读完 10000 组的）。
\`vectors-01.bin\`（14:03:19，比新集合早 9 分钟）是**旧的两分片版本留下的孤儿文件**，
内容已被覆盖成非 CVOV 数据。**它不该被当成第二个分片比对，也不该进仓库。**
（建议 oracle 流删掉它；本流不越界删别人的文件。）

---

## 3. 实现要点（parity 风险点逐条）

### 3.1 isValidDiagonalSuccessor 的 flag5 极性 —— **已用 javac 判定实验钉死**

这是本轮最重要的发现。三方产物一度都写成相反的极性；**字节码 + javac 判定实验**给出的答案：

    if (sideB.y >= host.y && sideB.penalty < 0.0f && !flag5) return false;
    if (sideA.y >= host.y && sideA.penalty < 0.0f && !flag5) return false;
    return true;            // 任一侧各自独立拒绝，不是"两侧都"

判定实验（\`build/probe/DiagProbe.java\`，javac 21 + javap）：

    // 第三析取项 = flag()      ->  28: ifeq  58   (flag==0 跳 false 结果)
    // 第三析取项 = !flag()     ->  28: ifne  58
    原版字节码 154/179 是 "iload 5 (flag5) ; ifeq 188"，188 = iconst_0; ireturn
    => 析取项是 flag5 => 合取项为假 <=> y>=host.y && penalty<0 && flag5==0 => 拒绝条件是 !flag5

交叉验证：同一段里 \`131: iflt 188\`（diag.penalty<0）也指向 188，独立确认 188 是 false 出口。
captain 已独立复核并裁决 **\`!flag5\`**；oracle 已据此修参照实现 + 重生成向量。

**回归防线**：\`diagonal_side_rejected(y_ge_host, penalty_neg, flag5)\` 抽成 \`cava_pf.h\` 里的
单一事实来源，内核那一处必须调用它；测试跑 8 行真值表断言（\`[truth-table] 8/8 rows OK\`）。

### 3.2 逐条清单

| # | 风险点 | 处置 | 证据 |
| --- | --- | --- | --- |
| 1 | PathMinHeap sift-up/down 与相等元素顺序 | 严格 \`<\` 上浮；下沉左右相等时**选右孩子**；右孩子不存在按 +\`INFINITY\` 参与比较；pop 取下标 0 并用**最后一个元素**填补 | 与 10000 组 traceHash 逐位一致（traceHash 就是"堆语义指纹"） |
| 2 | getNeighbors 展开顺序 | 陆地 8 个：SOUTH,WEST,EAST,NORTH,NW,NE,SW,SE；两栖再补 UP,DOWN | 同上 |
| 3 | 节点去重（坏哈希共享实例） | \`hash(x,y,z)\` 原样复刻，缓存 **hash -> 同一节点** | 同上；注意 14–18 格小世界触发不到位碰撞（spec §11.7） |
| 4 | maxVisitedNodes 预算 | \`visited++\` 后 \`if (visited >= budget) break\`，先 isEmpty 再自增 | 10000 组 expandedCount 一致 |
| 5 | malus 的 float 运算 | 全程 float，\`(g)+d+penalty\` 左结合；\`MathHelper.sqrt\` = \`(float)sqrt((double)f)\`；Java 的 \`Math.max\` 语义（NaN 分支）也照抄 | 5 个 float 逐位一致 |
| 6 | 门/栅栏/脚手架/台阶/水/岩浆边缘 | getCommonNodeType 16 步优先级、adjustNodeType、getNodeTypeFromNeighbors、getPathNode 第 8/9/10/11 步照抄 | 场景向量（DOORS/FENCE/SCAFFOLDING/STAIRS/WATER/LAVA）全绿 |
| 7 | 终点判定与 Path 后处理 | \`reachesTarget\` 反语义（找到=false）；createPath 只做 previous 回溯 + add(0,…) | 10000 组 reachesTarget 一致 |

### 3.3 与原版的**有意差异**（都在内核里有注释）

1. \`checkBoxCollision\` 不做 Box 结果缓存（纯记忆化，不影响结果）。
2. \`is_space_empty\` 迭代前把范围**裁剪到区域边界**：区域外恒为 OUT_OF_WORLD（无碰撞），
   裁剪不改变结果，但把病态 box 的最坏迭代量限制在区域体积内。
3. 碰撞盒用**真实 AABB 的 x/z 范围**（契约要求"扁平 AABB，不要体素近似"）；
   参照实现的 Terrain 把 x/z 展平成整格——向量回放时用整格盒喂进来，两边等价。
   注意参照实现的"空形状"判据是 \`maxY<=minY && maxY<=0\`，本内核是 \`box_count==0\`；
   对固定的 16 项调色板两者等价（已实测），但对任意方块状态**不保证等价**（见第 5 节）。
4. \`MobProfile\` 是**拷贝**进求解器的：原版 \`AmphibiousPathNodeMaker.init\` 会永久改写
   生物自己的惩罚表（\`WATER=0\` 且 \`clear()\` 不还原），本内核不污染调用方。
   单次调用内行为一致；跨调用（同一生物先两栖、后非两栖寻路）会有差异 —— **未在真实服务器验证**。

---

## 4. ⚠️ 冻结 ABI 的硬缺口（**P1 接管的唯一阻塞**）

\`CavaPathRequest\` 里**没有**原版陆地寻路必需的输入：

| 缺什么 | 用在哪（字节码出处） |
| --- | --- |
| \`maxRange\`（float，findPathToAny 第 4 个形参） | \`current.getDistance(start) >= maxRange\` 跳过展开；\`successor.pathLength < maxRange\` 才松弛 |
| \`entity.width / height\` | findNearbyNodeTypes 的体素盒尺寸；getPathNode 第 8 步的半宽与 checkBoxCollision；isBlocked(PathNode) 的包围盒 |
| \`entity.stepHeight\` | \`Math.max(1.125, stepHeight)\` 与 \`Math.max(1.0f, stepHeight)\` |
| 实体 \`double x/y/z\` | getStart 的四个盒角候选；isBlocked(PathNode) 的射线起点 |
| 惩罚表（26 float + set 标志） | \`MobEntity.getPathfindingPenalty\` —— 决定 getNodeType 的 chooser 与所有 malus |
| \`world.getBottomY()\` / \`getSeaLevel()\` | getPathNode 第 9/10 步的下界；两栖 penalizeDeepWater |
| \`canSwim\` / \`canWalkOverFences\` / \`amphibious\` / \`penalizeDeepWater\` | getStart、getPathNode 第 8/9 步、两栖追加邻居 |
| 起点方块坐标 | 原版根本不从参数取起点，是 \`pathNodeMaker.getStart()\`（由实体位姿推）—— 所以 \`sx/sy/sz\` 本身也是冗余的 |

另外 \`CavaStateRecord.flags\` 只有 6 个 \`CAVA_SF_*\` 位，而 \`getCommonNodeType\` 需要 19 个谓词
（AIR / TRAPDOORS / POWDER_SNOW / CACTUS_OR_BERRY / HONEY / COCOA / CAUTIOUS / DOOR(+OPEN+HAND) /
RAIL / LEAVES / FENCES / WALLS / FENCE_GATE(+OPEN) / FIRE_DAMAGE / canPathfindThrough(LAND) / WATER_BLOCK）。

### 提案（**需要 captain 裁决，本流不自行加导出符号**）

1. 新增 \`cava_mob_profile_upload(int64_t handle, const CavaMobProfile* p)\`：
   \`struct CavaMobProfile { float width, height, stepHeight; int32_t safeFallDistance; int32_t minY; int32_t seaLevel;
    uint32_t caps; double x,y,z; uint8_t onGround, touchingWater, canWalkOnFluid; uint8_t pad[5];
    float penalty[26]; uint32_t penaltyMask; }\`
   （惩罚表按 \`penaltyMask\` 位选，未选中的用 \`PathNodeType.getDefaultPenalty()\`。）
2. \`CavaStateRecord.flags\` 追加扩展位：\`1<<8 .. 1<<26\`，含义见 \`cava_pf.h\` 的 \`PF_*\` 枚举
   （本流已按"末尾追加、不复用旧位"的方式定义好，逐位对应 getCommonNodeType 的一个分支）。
3. \`CavaPathRequest\` 追加 \`float maxRange\`（或由 \`followRange\`×\`range\` 之外的第三个字段给）。
4. \`CavaPathRequest.sx/sy/sz\` 的语义写清楚：**它不参与求解**（原版从实体位姿取起点），
   只做一致性校验，或者干脆删掉。
5. \`CavaPathRequest\` / \`CavaPathNode\` **要登记进 \`cava_layout_report\`**（契约 2.3 的表里写了，
   但 \`native/src/cava_layout.cpp\` 与 Java 的 \`cava/ffm/CavaLayouts.java\` 都还没有它们）。
   **必须两侧一起加**，否则 \`native_layout_sum\` 与 Java 期望值不等 → \`cava_open\` 直接 CAVA_ERR_LAYOUT、整体回退。
6. 需要 \`cava::detail::lookup()\`（句柄代际校验）能被子系统入口复用 —— 它在 \`cava_handle.cpp\`
   的匿名命名空间里。本流的 ABI 层只做了句柄**形状**校验 + 自有状态表查找（不会段错误，但不校验代际）。

---

## 5. 未验证 / 留白（**不要当成已完成**）

1. **Java 侧完全没做**：\`cava/mirror/**\`（方块状态表 + 区域推送）、\`cava/mixin/pathfind/**\`（注入 + 金丝雀）
   本轮未落盘 —— 因为 \`cava/ffm/**\` 里没有 \`cava_pathfind\`/\`cava_state_table_upload\` 的绑定
   （不归本流），且 ABI 缺口未决。**这是本流最大的未完成项。**
2. **没有真机验证**：没有 \`Block.getRawIdFromState(Blocks.AIR.getDefaultState()) == 0\` 的实测
   （契约里点名的待验假设）—— 需要真实 MC 环境，本流未跑。
3. **没有整服层差分**：\`gradlew build\` 之外的服务器比对未做（且 ABI 未通，做了也没有原生路径可测）。
4. **没有性能对比**：prompts/04 要求"与装了 Lithium + ServerCore 的 Java 寻路对比"。
   本流的原生内核在所有向量上耗时 0.43 s / 10060 组（≈43 µs/组，含地形重建），
   **但这不是可比的性能证据**（没测 Java 侧、没测真实生物分布、没有 JIT 预热对比）。**未验证。**
5. WaterPathNodeMaker / BirdPathNodeMaker **未实现**（参照实现也没有，见 spec §11.6）。
   本内核只覆盖 LAND 与 AMPHIBIOUS，与 prompts/04 的陆地验收范围一致。
6. \`is_space_empty\` 的"空形状"判据与参照实现不同（见 3.3 第 3 条）——对 16 项调色板等价，
   对任意方块状态**未验证**。真实状态表推送上来的盒数组必须是"该状态碰撞形状的全部 AABB"。
7. 两栖对生物惩罚表的**跨调用副作用**未复刻（见 3.3 第 4 条）。
8. \`getSafeFallDistance() / getStepHeight()\` 的**默认值**未从字节码确认（spec §11.1 也标了未验证），
   本内核把它们当输入参数，不做假设。

---

## 6. 跨流请求清单

| # | 给谁 | 请求 |
| --- | --- | --- |
| 1 | captain | 裁决第 4 节的 ABI 扩展提案（不裁决则 P1 无法接管） |
| 2 | captain / P0-B | \`cava_pathfind\` 等 5 个符号的**布局登记**：\`CavaPathRequest\`/\`CavaPathNode\` 加进 \`cava_layout.cpp\` **并且** Java \`CavaLayouts\` 同步加 |
| 3 | P0-C（ffm 流） | 在 \`CavaBindings\` / \`CavaNative\` 里加 \`cava_pathfind\` / \`cava_state_table_upload\` / \`cava_region_upload\` / \`cava_region_clear\` / \`cava_region_state_id_at\` 的绑定与 MemoryLayout |
| 4 | P0-B | 暴露 \`cava::detail::lookup(int64_t)\`（或等价的句柄校验）给子系统入口 |
| 5 | P0-A | 若要 CTest 覆盖寻路：给 \`native/tests/cava_pathfind_vectors.cpp\` 加一个 target（本流不越界改 CMakeLists） |
| 6 | P1-Oracle | 删除残留的 \`src/test/resources/cava/oracle/vectors-01.bin\`（已不是合法 CVFR 分片，见 2.3） |

---

## 7. 复现

    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-pathfind.ps1
    .\build\native-pathfind\cava_pathfind_vectors.exe src/test/resources/cava/oracle

只跑前 N 组：在末尾加一个数字参数（例：\`... src/test/resources/cava/oracle 200\`）。

**本机踩到的两个坑（已固化进脚本）**：
1. g++ 的临时 \`.o\` 默认落在 \`%TEMP%\`，本机该路径含中文/沙箱限制 → 汇编器报
   \`can't create ...ccXXXXXX.o\`。脚本里把 \`TMP\`/\`TEMP\` 指到 \`build/native-pathfind/tmp\`。
2. MinGW GCC 15（posix 线程模型）默认动态链 \`libwinpthread-1.dll\` → 直接跑 exe 报
   \`exit=-1073741515\`（STATUS_DLL_NOT_FOUND）。脚本用 \`-static\` 全静态链接，产物只依赖 KERNEL32+msvcrt。
3. PowerShell 5.1 读 **无 BOM 的 UTF-8 脚本**（注释含中文）会解析失败（实测 \`Unexpected token ')'\`）。
   \`build-pathfind.ps1\` 因此**保持纯 ASCII**。


---

## 8. 【更新】captain 扩展 ABI 之后的状态（广播 #6）

### 8.1 已落地（本流已适配，编译 + 测试通过）

- \`CAVA_CAP_*\` → **\`CAVA_NAV_*\`**（11 位，含本流提案的 CAN_SWIM / CAN_WALK_OVER_FENCES /
  AMPHIBIOUS / PENALIZE_DEEP_WATER / ON_GROUND / TOUCHING_WATER / CAN_WALK_ON_FLUID）。
- 新增 \`CavaMobProfile\`（184 B）+ \`cava_mob_profile_upload\` / \`cava_mob_profile_clear\`。
  本流已实现，含 \`width<=0 / height<=0 / step_height<0 / penalty_mask 越位\` 的拒绝，
  **失败不改变已有档案**（实测：\`mob_profile_upload(width<0)=-4\`）。
- \`CavaPathRequest\` 删掉 \`sx/sy/sz\` 与 \`profile\`，改为 \`tx/ty/tz + reach_range + max_range +
  max_visited_nodes + reserved0/1/2\`；本流 ABI 层已按新字段校验。
- \`layout_hash_sum\` 在**同一个会话里就变了 4 次**（实测观测序列：
  \`0x6149FD30\`(4 结构体) → \`0xC04A5791\`(广播 #6) → \`0xcd595745\` → \`0xc750ed61\`(最终实测)）。
  所以本流测试**不再硬编码**这个值：改用 \`cava_layout_report\` 按契约 2.3 的公式自算
  （\`sum = Σ layout_hash\` 的 uint32 回绕和），\`cava_open\` 实测随头文件自动跟上。
  **提醒**：Java 侧 \`CavaLayouts\` 必须用同一公式；只要头文件还在动，两侧就必须一起动。

### 8.2 ⚠️ 仍然阻塞：**\`CAVA_PNT_*\` 不是 Yarn \`PathNodeType\` 的枚举 ordinal**

头文件写"必须与 Java 枚举 ordinal 完全一致"，但实测（javap，spec §8）真实 ordinal 与头文件从 5 起就错位：

| ordinal | 头文件 \`CAVA_PNT_*\` | javap 实证 |
| --- | --- | --- |
| 5 | FENCE | POWDER_SNOW |
| 6 | LAVA | DANGER_POWDER_SNOW |
| 7 | WATER | FENCE |
| 9 | UNPASSABLE | WATER |
| 10 | DOOR_OPEN | WATER_BORDER |
| 19 | DAMAGE_CACTUS | DOOR_IRON_CLOSED |
| 20 | DOOR_OPEN_IRON | BREACH |
| 24 | DANGER_WATER | DAMAGE_CAUTIOUS |
| 25 | DANGER_POWDER_SNOW | DANGER_TRAPDOOR |

且头文件含 1.20.4 里不存在的 \`DAMAGE_CACTUS / DOOR_OPEN_IRON / DAMAGE_WITHER_ROSE / DANGER_WATER\`，
缺 \`POWDER_SNOW / WATER_BORDER / DAMAGE_CAUTIOUS / DANGER_TRAPDOOR\`。

**为什么危险**：惩罚表是 \`float penalty[26]\`。若 Java 按 \`pnt.ordinal()\` 填、原生按头文件常量索引，
则 \`penalty[5]\` 在 Java 是 POWDER_SNOW、在原生被当成 FENCE —— **路径照样算得出来、看起来正常，
但与原版不一致**。这是最难发现的一类 parity bug。

**处置**：内核内部坚持用 javap 实证的 ordinal（\`cava_pf.h\` 的 \`PT_*\`，已被 10060 组向量逐位验证），
**不做猜着映射**，\`cava_pathfind\` 继续返回 \`CAVA_ERR_UNIMPLEMENTED\`。
建议 (a) 把头文件数值改成实证 ordinal（推荐，零映射），或 (b) 契约里写死"必须用头文件常量索引，不是 ordinal"。

### 8.3 次要：\`CAVA_PF_*\` 与 getCommonNodeType 不是一一对应

\`FENCE_OR_WALL_CLOSED\` / \`DANGER\` / \`DOOR_IRON\` / \`FIRE\` / \`WITHER_ROSE\` 是**派生量**；
且缺 \`FENCE_GATE_OPEN\`（栅栏门开着）、独立的 \`FENCE_TAG\` 与 \`WALL_TAG\`
（原版正文里 FENCES 与 WALLS 分开判）。派生量可行，但**每个派生量的精确定义必须写进契约**，
否则原生侧无法判断"这个位等不等于原版那一步"。

### 8.4 本流这次新增的实测（CMake / CTest）

    configure exit=0                          （需要把 C:\mingw64\bin 放进 PATH，否则找不到编译器）
    cmake --build build/native-p1  exit=0     （native/src/pathfind/** 被 GLOB 收进 cava.dll）
    ctest -R cava_pathfind_vectors  -> 100% tests passed, 0 tests failed out of 1  (0.70 s)

**CMake 侧一个坑**：\`ctest\` 调 \`cava_pathfind_vectors\` 时**不带目录参数**（P0-A 加的 target）。
本流已把测试改成**自动定位**（从 cwd 与 argv[0] 逐级向上找 \`src/test/resources/cava/oracle\`，
找不到才报 usage），所以不需要改别人的 \`native/tests/CMakeLists.txt\`。

**（已解决）** 当时 \`ctest\` 里 \`cava_selftest\` / \`cava_dll_loadtest\` 因扩 ABI 的既有断言过期而失败 ——
P0-B 已更新，本轮最终实测 **4/4 全绿**。
---

## 9. 【最终】cava_pathfind 已真实接线（不再是保守回退）

两条历史阻塞**均已解除**（captain 裁决）：
- `CAVA_PNT_*` 已换成 javap 实证的 ordinal ⇒ **`CAVA_PNT_N` 就是内核的 `PT_N`**，惩罚表按真实 ordinal 索引；
- 内核 `PF_*` 已改为头文件 `CAVA_SF_* / CAVA_PF_*` 的**别名**（唯一事实来源）；
  Java 侧 `MirrorFlags.commonNodeType(flags)` 是内核 `common_node_type()` 的逐分支镜像，
  并已对全部真实状态对拍（26644 条，0 不一致）。

接线内容：`HandleState`（区域 + 状态表 + 生物档案）→ `WorldView` + `MobProfile`；
`CavaPathRequest` 的 tx/ty/tz、reach_range、max_range、max_visited_nodes → `SolveParams`；
`SolveResult.nodes` → `CavaPathNode`（g = penalizedPathLength、f = heapWeight、heapIndex、type，
与 `NativePathBuilder` 的读取一一对应）。

**ABI 层实测（[abi] 段）**：

    [abi] cava_pathfind=3 nodes; first=(1,1,1) last=(3,1,3) last.g=2.82843 last.f=2.82843 type(first)=2 heapIndex(first)=-1
    [abi] cava_pathfind(cap=1, 路径更长)=-4 (expect -4), buffer 首字段=-777 (expect -777)

- 3 个节点、首=起点、末=终点（reach_range=0）；g = 2.82843 = 2√2（两步对角）—— 数值自洽。
- **cap 不足返回 `CAVA_ERR_ARG` 且验证了「绝不部分写入」**（哨兵值 -777 未被覆盖）。
- `<=0` 的 max_visited_nodes 直接 `CAVA_ERR_ARG`：Java 侧 `PathfindHook` 本来就保证 budget>0
  （budget<=0 自己就先回退了），所以这一支没有"猜一个默认值"的必要。

**回归**（接线后重跑，证明内核没被改坏）：

    10000/10000 vectors + 60/60 golden：mismatches=0，worldHashFail=0
    ctest：100% tests passed, 0 tests failed out of 4
           （cava_dll_loadtest / cava_fp_probe / cava_pathfind_vectors / cava_selftest）
    natives/windows-x64/cava.dll 重新构建：2,828,332 B @ 15:44:38

**未验证**：**整服层的 takeovers > 0 尚待注入流复测**（本流没有跑真实服务器的路径）。
ABI 层已实测能出路径、cap 与错误码语义都符合契约，但「接管后与同一整合包 native 关闭逐 tick 一致」
仍然是**未验证**的 —— 需要注入流复测 + 整服差分。


