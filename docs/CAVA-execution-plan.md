# Cava 多代理执行主计划（captain 维护）

> 本文是"这一轮谁在做什么、下一轮开什么"的唯一台账。执行细节看各流自己的 docs。
> 开工前必读：`docs/CAVA-工程接口契约.md`（契约）、`docs/CAVA-dev-toolbox.md`（工具与坑）。

## 1. 执行原则

1. **契约先行**：`native/include/cava_abi.h` + `docs/CAVA-工程接口契约.md` 是共享边界。任何跨界改动先改契约再改实现。
2. **文件所有权唯一**：同一时刻一个文件只能由一个流改（见第 3 节表）。跨流需求写成「请求」，由 captain 或归属流执行。
3. **提交纪律**：每个流自己 commit，**显式列出自己的文件**；绝不 `git add -A`；不 push、不 rebase、不动别人的文件。
4. **证据优先**：结论必须有本机实测证据（javap / 真跑 / 真日志）。未验证的一律写「未验证」，**禁止把推测写成结论、禁止编数字**。
5. **不阻塞**：拿不到的东西先标注卡点继续推进别的部分，不要为了等一个东西停摆。

## 2. 依赖图（谁卡谁）

    [契约 cava_abi.h / CavaPathRequest 已冻结]  ← 已完成
              │
              ├─→ P0-A 构建基建 ──┐
              ├─→ P0-B native 核心 ─┤
              │                     ├─→ **P0 端到端冒烟**（native 库加载 + 布局自检 + 一键回退）
              ├─→ P0-C Java FFM facade ┘        │
              │                                  ▼
              │                        Wave 2：兼容层 / 差分测试 / P1 寻路 / P2 实体 / P3 红石
              ├─→ P0-D 文档与语料 ─────────────────┘
              ├─→ P0-E 真实测试服 + 基线 ──────────┘（整服层差分的宿主）
              └─→ P1-Oracle 原版寻路语义 ──────────┘（P1 的左侧参照物）

**关键路径**：P0-A/B/C → 端到端冒烟 → Wave 2。P0-D/E 与 P1-Oracle 可全程并行。

## 3. 文件所有权表（并行期有效）

| 路径 | 归属流 | 备注 |
| --- | --- | --- |
| `docs/CAVA-工程接口契约.md`、`native/include/cava_abi.h`、`docs/CAVA-dev-toolbox.md`、`docs/CAVA-execution-plan.md` | **captain** | 契约与台账 |
| `build.gradle`、`gradle.properties`、`settings.gradle`、`gradle/wrapper/**`、`.github/**`、`CMakeLists.txt`、`native/cmake/**`、`native/tests/CMakeLists.txt` | P0-A | 构建基建 |
| `native/src/**`、`native/tests/**`（除 CMakeLists）、`docs/CAVA-native-notes.md` | P0-B | 原生核心 |
| `src/main/**`、`src/client/**`、`src/test/java/cava/parity/**`、`docs/CAVA-java-notes.md` | P0-C | Java 侧 |
| `docs/CAVA-build.md`、`docs/CAVA-parity-fixtures.md`、`docs/CAVA-p0-acceptance.md`、`tools/*.ps1` | P0-D | 文档与语料 |
| `testbed/**`（gitignore）、`tools/setup-testbed.ps1` 等、`docs/CAVA-baseline.md` | P0-E | 测试服与基线 |
| `docs/CAVA-pathfind-oracle-spec.md`、`src/test/java/cava/oracle/**`、`src/test/resources/cava/oracle/**` | P1-Oracle | 参照实现 |

> **Wave 1 的冲突点已全部清理**：P0-A 的临时占位 `native/src/common/cava_p0a_stub.{c,cpp}` 与
> `native/tests/tmp_p0a_smoke.cpp` 已不在源码树中（P0-B 构建时源码已是 5 个 `.cpp`，DLL 导出只剩 10 个 `cava_*`）。
> 另外确认：`natives/` 在 `.gitignore` 内，原生产物不入库。

> **Wave 1 的实际结论：六条流全部收工，门禁 #1/#2/#3/#5 全部通过**（证据 `docs/CAVA-gates.md`）。
> `gradlew build` 绿 + 28 单测绿；CMake/`ctest` 3/3 绿；Java ↔ 原生端到端 `SELF-TEST: PASS`；
> 一键回退与 ABI 守卫逐项实测。**唯一大缺口**：MC 侧三个文件（`cava.Cava` / `TickSampler` / `CavaClient`）
> 只做过"手写桩"的类型自检，**尚未与真实 MC API 对编** —— 这条由 Wave 2 的 `gradlew build` 覆盖。

## 4. 当前这一轮（Wave 1）的分流

| 流 | 任务书 | 交付 |
| --- | --- | --- |
| P0-A | `prompts/01` 第 2–3 条 + 第 8 节 CMake | Gradle/Loom JDK21 改造、CI 改 JDK21、CMake 产出 `natives/<平台标签>/` |
| P0-B | `prompts/01` 第 3–4 条 | `cava_abi.h` 全符号实现 + 布局自检 + 饱和转换 + 自测 + `fp_probe.txt` 向量 |
| P0-C | `prompts/01` 第 4–6 条 | FFM facade + 哈希校验加载链 + 金丝雀框架 + 子系统注册 + 黄金轨迹运行时骨架 |
| P0-D | `prompts/01` 第 7 条与验收 | 构建手册、差分夹具手册、P0 验收台账（诚实版）、测试服部署清单 |
| P0-E | `prompts/01` 第 7 条 | **真跑起来**的 Fabric 测试服 + 确定性验证 + spark 四块占比基线 |
| P1-Oracle | `prompts/04` 前置 | 原版寻路语义规格（javap 证据）+ 纯 Java 参照实现 + 测试向量 |

## 5. 整合门（Wave 1 → Wave 2 之前 captain 必做）

1. **删占位文件**：`native/src/common/cava_p0a_stub.{c,cpp}`（P0-A 的临时件）必须删除并确认 CMake 仍能配置。
2. **端到端冒烟**（P0 的核心验收）：`NativeSelfTest` 在**真实构建出的库**上跑通：
   `cava_build_id` / `cava_abi_version` / 布局自检 4 条全过 / `cava_open` 成功 / `cava_d2i_sat` 正确。
3. **失败路径冒烟**：故意改坏 `CavaOpenParams.layout_hash_sum` → 必须 `CAVA_ERR_LAYOUT` 且句柄为 0；`-Dcava.native.enabled=false` → 状态 `DISABLED_BY_FLAG` 且无 ERROR。
4. **Gradle 真的能过**：`$env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'; .\gradlew.bat build --console=plain` 至少到"编译通过"；记录耗时与下载量。
5. ~~**预览版 class 能被 Fabric Loader 加载**（**架构级门**）~~ → ✅ **已通过（captain 亲自验证，证据见 `docs/CAVA-gates.md`）**：
   `javac --release 21 --enable-preview` 编出的 mod（class major.minor = **65.65535**）在真实 **Fabric Loader 0.19.5 / MC 1.20.4** 服务端上被加载并执行，
   `java.lang.foreign` 可用，native downcall `strlen("cava") = 4`，服务器 `Done (14.193s)`。
   **派生硬约束**：启动参数必须含 **`--enable-native-access=ALL-UNNAMED`**（少了它 native 调用被拒）；
   **Loader 0.19.5 是已验证可用版本**，任何 loader 版本变更都要重跑这条门禁。
   **仍未验证**：Mixin + Loom remap 在**含预览版 class 的池**上是否正常（留给下面第 2 条端到端冒烟，用真实 Loom 产物跑）。
6. **回填 P0 验收台账**：`docs/CAVA-p0-acceptance.md` 每条都要有证据或明确的「受阻 + 卡点」。
7. ~~**更新 `docs/CAVA-后续对话提示词.md` 的"附二：本轮成果"**~~ → ✅ 已写入 **附二·A**（P0 结果 + 六条本机实测坑）。

### 整合门的实际结论（captain 亲自复跑，证据 `docs/CAVA-gates.md`）

| 门 | 状态 | 证据摘要 |
| --- | --- | --- |
| 1 删占位文件 | ✅ | `native/src/` 只剩 5 个 `.cpp`，DLL 只导出 10 个 `cava_*` |
| 2 端到端冒烟 | ✅ | `NativeSelfTest` → `status=OPEN`、`java_layout_sum == native_layout_sum == 0x6149FD30`、`SELF-TEST: PASS` |
| 3 失败路径冒烟 | ✅ | `tools/LayoutGuardProbe`：错误 layout_hash → `CAVA_ERR_LAYOUT`；ABI≠1 → `CAVA_ERR_ABI_VERSION`；都在写句柄之前失败；`close` 幂等 + 伪造句柄安全 |
| 4 Gradle 真能过 | ✅ | `BUILD SUCCESSFUL`；JUnit XML 逐类核对 **28 tests / 0 failures / 0 errors**；jar 内含 `natives/windows-x64/cava.dll` |
| 5 预览版 class 能被 Loader 加载 | ✅ | `docs/CAVA-gates.md` 门禁 #5（真实 Fabric 0.19.5 服务端） |
| 6 回填验收台账 | ⏳ | 由 P0-E（基线）与 W2 流补齐后回填 |
| 7 更新跨会话记忆 | ✅ | `docs/CAVA-后续对话提示词.md` 附二·A |

**~~仍未闭合的最大缺口~~ → 已闭合（门禁 #6）**：真实的 `cava-0.1.0.jar` 已在一个真实 Fabric 服务端里
**与 Lithium / ServerCore / VMP / FerriteCore / Carpet / TIS 同时装载并成功启动**，启动横幅、原生库加载、
哈希命名落盘、布局自检（`cava_abi_touch()==1` 证明原生真的执行过）、`config/cava.json` 默认值落盘全部实测通过。

**仍未闭合的**：金丝雀 0/3（P0 三个子系统都是 `enabled=false`，按设计），
以及**黄金轨迹尚未采到**（`-Dcava.parity.trace` 没开）、确定性前置（同存档连续两次一致）未证明。

### P1 的当前交接状态（Wave 2 结束时）
- **原生内核已完成**：LAND + AMPHIBIOUS，与纯 Java 参照实现 **10060 组逐位一致（0 差异）**，
  含 `worldHashLow` 与 golden 的 `blocks[]` 逐字节校验（地形重建也正确）。`ctest` 里 `cava_pathfind_vectors` 会跑它。
- **`cava_pathfind` 目前保守返回 `CAVA_ERR_UNIMPLEMENTED`**，Java 侧按契约回退原逻辑。
  **这是正确的处置**：它拒绝猜一条"看起来正常但不与原版一致"的路径。
- **接手前还差三步**（都在 `docs/CAVA-p1-pathfind-notes.md` 第 4/6 节）：
  1. `cava/ffm/**` 里 `cava_pathfind` / `cava_mob_profile_upload` / `cava_mob_profile_clear` /
     `cava_state_table_upload` / `cava_region_*` 的 FFM 绑定（归 P0-C，本轮已指派）；
  2. `cava/mirror/**`（方块状态表 + 区域推送）与 `cava/mixin/pathfind/**`（注入 + 金丝雀）—— **从未落盘**；
  3. `CAVA_PF_*` 各位的**精确定义**要写进契约（P1 指出它含派生量、且缺 `FENCE_GATE_OPEN` 等）。
- **待验假设**：`Block.getRawIdFromState(Blocks.AIR.getDefaultState()) == 0`（真实 MC 环境里实测）。
- **P0-B 已备好**：`cava::detail::lookup/handle_valid`（句柄代际校验，不新增导出符号，槽位复用后旧句柄仍被拒），
  `cava_pf_abi.cpp` 可直接改用。

## 6. Wave 2 分流（P0 冒烟通过后立即开）

| 流 | 任务书 | 前置 | 状态 | 交付 |
| --- | --- | --- | --- | --- |
| W2-兼容层 | `prompts/02` | P0-C ✅ | **运行中** | `custom.lithium:options`（本轮只关 `mixin.ai.pathing`）、ServerCore/VMP 适配、Carpet/TIS 规则检测、启动报告表、`config/cava.json` 覆盖 |
| W2-P1 寻路 | `prompts/04` | 镜像 ABI ✅（已冻结） | **运行中** | `cava_pathfind` 原生实现 + 注入 `PathNodeNavigator.findPathToAny`（method_52/54）+ 金丝雀 + 三层差分 |
| P0-E 基线 | `prompts/01` 第 7 条 | — | **运行中** | 测试服 + spark 四块占比 |
| P1-Oracle | `prompts/04` 前置 | — | **运行中** | 原版语义规格（javap 证据）+ 纯 Java 参照实现 + 向量 |
| W2-差分测试 | `prompts/03` | P0-C ✅ + P0-E（测试服） | pending | 确定性前置证明、三层测试、`parityDiff` 一条命令、CI 化 |
| W2-P2 实体 | `prompts/05` | 兼容层归属决策 + 差分测试 | pending | 碰撞求解内核 + 事件回放 + 实体 SoA 镜像 |
| W2-P3 红石 | `prompts/06` | 兼容层归属决策（Carpet 复刻 vs 让位） | pending | `RedstoneWireBlock.update`(method_10485) 的归属实现 + contraption 语料 |

**镜像侧 ABI 已由 captain 冻结**（`native/include/cava_abi.h`）：采用**有界长方体区域推送**，不是整块世界镜像 ——
P1 的每次寻路本来就有天然边界（起点→终点 + maxVisitedNodes），这样绕开了调色板压缩、脏标记与区段卸载通知。
**Wave 2 的归属决策（captain 已定，供兼容层与后续流遵守）**：
- `mixin.ai.pathing`：P1 复刻 → 关（Lithium 的寻路优化让位给原生 A*）。
  > ⚠️ **这条决策的代价被用户点出来了，记录在此（2026-09-22）**：关掉 `mixin.ai.pathing` 关掉的是
  > **Lithium 的纯 Java 优化**，而在原生路径真正接管之前，寻路跑的是"**没有 Lithium 的纯 Java**" ——
  > 比装 Cava 之前**更慢**，纯损失、零补偿。
  >
  > **原则（据此修正）**：**默认不允许性能回退**。因此顺序必须是
  > **先让原生路径真正接管（金丝雀 +1、真实场景跑通）→ 再关闭对方的组**；
  > 在原生接管被实测确认之前，这一组**保持开启**，代价是"暂时没有加速"，但**不倒退**。
  > 本轮 P1 接管完成后，由兼容流把这条从"静态关闭"改成"**原生可用时才关闭**"（运行期判定）。
- `mixin.entity.collisions.movement`：**本轮不关**（P2 才决策）。
- `mixin.block.redstone_wire`：**本轮不关**（P3 才决策）。
- `mixin.shapes`：不关（不在我们的注入点）。

**Wave 2 之后、Wave 3 之前必须先冻结**：实体镜像 ABI（P2 的 SoA 布局）—— 由 P2 流按真实 `Entity` 推导，captain 审查后写进 `cava_abi.h`。

## 6.5 Wave 3 分流（提示词顺序推进；**性能对比留到最后**）

**用户裁定（2026-09-22）**：
1. **区块生成不纳入范围**（P0-E 基线显示它占 60.5%，但 Cava 的三个子系统都不碰它）——记录为"明确不在范围内"，不再讨论；
2. **先把提示词推完，再回来做性能对比**。所以 `prompts/04` 里"与 Lithium + ServerCore 的 Java 寻路对比"这条**有意推迟**；
3. **`mixin.ai.pathing` 暂时保持关闭**（用户明确说"不用"回退）——
   即**接受"P1 已接管但只到与 vanilla 打平"这个中间态**，代价是 Lithium 的 Java 寻路优化暂时不生效。**这是用户的知情取舍，不是遗漏。**

| 流 | 任务书 | 状态 | 交付 |
| --- | --- | --- | --- |
| **P2-K** | `prompts/05` 第 1 个核 | ✅ 完成 | 原版碰撞语义规格（javap 证据）+ 纯几何内核 + 定点真值表 + 向量；**结论：core1 净 +1.86 µs/次、core2 净 +6.8 µs/次 ⇒ 决定不上线**（负面结论同样入库，见 `docs/CAVA-p2-*-notes.md`） |
| **P2-Java** | `prompts/05` 设计要求 | ✅ 完成（含 live 接管） | 实体 SoA 镜像 + 事件回放 + VMP 黏滞语义复刻；live 接管 144158 次、自证 96220、mismatch **0** |
| **P03-差分** | `prompts/03` | ✅ 完成 | 单元层 harness + 场景层黄金轨迹（自采）+ 整服层不变量 + mod 组合矩阵 + 一条命令出报告；三层结果见 `docs/CAVA-gates.md` |
| P1-性能对比 | `prompts/04` 验收 | ✅ 完成（**结果见门禁 #8 §8.6**） | 大搜索空间下 native 快 **1.95–3.04x**（每 tick −72…−1122 µs）；**但一致性判定 DIVERGENT(2)**：窗口截断 + 镜像无失效源（穿墙）。`cava.pathfind.native` 默认 **false** ⇒ 默认配置不受影响 |
| **P1-FIX** | 本轮实测抓出的两个缺陷 | ✅ 完成（`2790772`/`6365a0c`/`ddb919c`） | 同 tick 复用 + `ChunkSection.setBlockState` 失效钩子（`SectionOriginRegistry` 还原世界坐标）+ 窗口截断三判据保守回退；`-Compare` **CONSISTENT**、两条可证伪对照都变红、4 场景 2.16–2.76x 不退化。**顺带抓到第三个缺陷**：`reachesTarget` 填反 + 比对脚本键名笔误 ⇒ 该字段从来没被比过 |
| **P1-NET** | captain 裁决 R2 的判据数字 | ✅ 完成（`09ea1dc`）→ **NO-GO** | **真实 AI 负载净收益 = −14.75/−14.71 µs/tick（净亏，成对口径两次差 0.2%）**；R2 三条判据全不满足；按规模分流已实现并实测，**只能止亏不能转正**（最优阈值 = 不接管）；亏损可分解为"接管调用原生也慢 1.22x"+"31–33% 回退白付原生"；R6 机械对拍落地（顺带查出 `site:` 复合记号以前一个都没比、`(缺)==(缺)` 静默通过）。**`cava.pathfind.native` 保持默认 false** |
| P3 红石 | `prompts/06` | ✅ 完成（**决策=让位**） | Carpet/TIS 源码比对已完成：`RedstoneWireBlock.update` 在 `fastRedstoneDust` 开启时是**死方法**（3 个调用点全被 redirect）+ 算法用 `ThreadLocalRandom` + 需镜像 23 个 MC 成员 ⇒ **零红石加速**；并记录了"现夹具对红石算法不敏感"这个盲点 |
| P4 跨平台加固 | `prompts/07` | ✅ 完成（有明确未闭合项） | 熔断/看门狗/ABI fuzz/SAFE 断言/一键回滚**全部有实跑证据**；5 平台构建矩阵 + 数值一致性套件 + CI 矩阵**写全但非 Windows 未跑过**；**交付物换成契约要求的 MSVC `/MT` 产物**。详见 `docs/CAVA-gates.md` 门禁 #8 |

**P2 的关键设计约束（captain 已读字节码后写的，供后续流遵守）**：
`Entity.adjustMovementForCollisions` 的**形状来源顺序有语义**：
`entityCollisions`（前）→ `worldBorder.asVoxelShape()`（仅当 `canCollide(entity, box.stretch(movement))`）→ `world.getBlockCollisions(...)`（后）。
三批形状交给**私有静态重载**求解；**worldBorder 是第三个输入源，不能漏**。
以及：**回放事件必须能取回原始 VoxelShape 对象**，否则 mod 覆写过的方块行为会丢。

## 7. 风险台账

| 风险 | 触发条件 | 现状 | 对策 |
| --- | --- | --- | --- |
| ~~预览版 class 无法被 Fabric Loader 加载~~ | — | ✅ **已排除**（`docs/CAVA-gates.md` 门禁 #5，实测通过） | 保留门禁复现脚本；loader 版本变更时重跑 |
| **Loom 1.18.x 要求 JVM 25，与 JDK 21 硬约束冲突** | 构建期 | ✅ 已定位并修复：`loom_version` pin **1.17.20**（`docs/CAVA-gates.md` 门禁 #1） | CI 的 JDK 必须固定 21（模板自带的 JDK 25 是因为 Loom 1.18） |
| Mixin + Loom remap 在含预览版 class 的池上是否正常 | 端到端冒烟 | 未验证 | 用真实 Loom 产物做门禁 2 |
| Gradle 首次构建下不动 | 网络/缓存 | 已确认网络可达（node fetch 200），`GRADLE_USER_HOME` 已改为工作区内 | P0-A 报告实际耗时与下载量 |
| 没有服务端根目录 | — | 已由 P0-E 在 `testbed/` 内新建 | P0-E |
| `PathMinHeap` 相等元素顺序搞错 | — | P1-Oracle 正在用 javap 固化 | 单元层 10^5 组逐节点比对 |
| Lithium `@Overwrite` 碰撞点 | P2 | 未决 | 兼容层给出售让/复刻的显式决策 |
| 反编译器在本 jar 上未验证 | — | 已标注「未验证」，默认走 javap | 不阻塞 |
| **参照实现可能"自洽但不对齐原版"** | 已实际发生一次 | `LandMaker.isValidDiagonalSuccessor` 的第三个合取项被写成 `flag5`（应为 `!flag5`）；**10000 组向量是参照实现自己产的，抓不住这个错** | ① 每个易读反的分支都要有**定点真值表断言**（不靠随机向量）；② W2-P1 用 javac 判定性实验独立复核，captain 用 javap 仲裁 |
| **同一份常量被定义在两处 → 静默漂移** | **已实际发生一次（P1 位号 19/19 全错位）** | `CAVA_PF_*` 在 `cava_abi.h`、内核读的 `PF_*` 在 `native/src/pathfind/cava_pf.h`，**19 个位号全部不一致**。后果本会是：Java 填 `AIR` → 内核读成"活板门"、`RAIL` → "门"、`FIRE` → "可通行"、`WITHER_ROSE` → "水方块"。**而且运行期完全发现不了**（`cava_pathfind` 返回 UNIMPLEMENTED，没有可观测行为） | 改成**别名**：`PF_*` 定义为 `CAVA_PF_*`（**只剩一处事实来源**），并重跑 10060 组向量自证零逻辑改动。<br>**通用规则**：**任何跨边界的常量/位布局只允许定义一次**；需要别名就写成别名，不要复制数值。 |
| **接口语义太"软"导致调用方误用** | **已实际发生一次** | 我把位姿放进 `CavaMobProfile`，却在注释里写"每生物一份、变化时重推" → 会得到**陈旧起点** | 把约束**写进方法名**：`uploadProfileForSolve` / `isProfileReadyForSolve`，并在 javadoc 里写死"每次求解前重推、不得跨 tick 复用"。<br>**通用规则**：**会致命的调用时序约束要进方法名，不要只写在注释里。** |
| **按字段名查结构体偏移** | 已实际发生一次 | `MOB_PROFILE.byteOffset(groupElement("max_fall_distance"))` 在字段改名后**运行期抛异常**（不是编译期），我在加 `reserved_` 前缀时撞到 | 偏移改为 `CavaLayouts.MobProfileOffset.*` 实测常量；**通用规则：关键偏移不用字符串查字段名** |
| **编译失败会让同一轮里留下过期可执行文件，产生"看起来很真"的假失败** | 已实际发生一次 | `ctest` 报 `cava_pathfind_vectors` **9664/10000 不一致**，看着像 parity 崩了；实际是上一次 build 已经失败、**旧 exe 与旧 dll 还在盘上**。干净重建后 **4/4 通过** | **通用规则：看到"大规模不一致"先确认被测二进制是刚构建的**（看时间戳或强制重建），再怀疑代码 |
| **共享产物目录被别的流重建 ⇒ 已验证的结论被静默作废** | **已实际发生两次** | `natives/<tag>/cava.dll` 有**两个生产者**（根 CMakeLists 与 `native/tests/build-mingw.ps1`）。P4-B 的验证哈希 `43129D92…` 被 P4-A 的一次 `cmake --build` 换成 `D751A3D1…`；P4-A 的一条 fuzz 结论随后也被作废 | **产物指纹纪律**：任何依赖产物的结论必须记 **Size+SHA256**，哈希一变即作废重跑；交付物权威生产者 = 根 CMakeLists（`gradlew buildNative`）。见门禁 #8 §8.4 |
| **存在"未被 `cava_open` 覆盖、也没有自动守卫"的第二导出面** | 已实际存在 | `cava_push_away_from` / `cava_push_box_filter` / `cava_push_section_plan` 不在 `cava_abi.h` 里，却在两份产物里都导出，**而且 `cava.push.NativePush` 真的会解析它们**（它自己做 `libraryLookup`+`downcallHandle`）。签名漂移 ⇒ 静默 FFM UB，没有任何自动报警 | 契约 §2.1 第 9 条已写明；默认配置不触发（`cava.push.mode` 默认 `off`）；改这三个入口必须**同时**改 `NativePush` 的 `FD_*` 与 `native/tests/push/` 向量并重跑 |
| **"求解器一致"被误当成"端到端一致"** | **已实际发生**（P1 性能轮抓到） | P1 的 live 自证是"native 与 Java 参照实现**在同一份推送数据上**逐位一致"（96220/0），它**看不到**"推上去的数据本身是否陈旧/是否够用"。性能轮用逐字段比对（节点数/末节点/曼哈顿/节点序列 FNV/穿墙计数）在 `detour128` 上抓到 **DIVERGENT(2)**：路径穿过 8 格实心石头（`collisionNodes` 0→8） | **通用规则：自证式 parity 只能证明"算得对"，不能证明"输入对/够"**。任何"接管上线"的判据必须包含**与不接管腿的逐字段比对**（本项目的 native on/off 双腿比对照抄 `tools/parity-perf-pathfind.ps1 -Compare`）。修复轮 P1-FIX 进行中；`cava.pathfind.native` 默认 false 挡住了线上影响 |
| **镜像复用没有失效源** | **已实际发生**（与上一条同源） | `RegionMirror` 的复用判据 = "同矩形 + 无失效事件"，但 `onBlockChanged/onSectionUnloaded/onWorldChanged` **在生产路径里零调用**（契约主钩子 `ChunkSection.setBlockState` 从没接上），于是"无失效事件"永远成立 ⇒ 跨 tick 拿到陈旧地形 | 修法：①**复用默认限制在同 tick 内**（同 tick 复用原理上不可能陈旧）；②接上主钩子并给它金丝雀计数；③跨 tick 复用要**证据**才允许默认打开。**通用规则：哨兵式判据（"没有失效事件"）只有在失效源完备时才是安全的；没有失效源时，"没有事件"等于"永远不失效"。** |
| **读原版行为的 jar 路径被写错** | 已实际发生一次 | `docs/CAVA-dev-toolbox.md` 早期写 `minecraft-clientonly`，而 `entity/*` 与 `ai/pathing/*` **只在 `minecraft-common` 里**（clientonly 的 entity 条目数 = 0） | 已勘误并写进 toolbox；所有"读原版"的流都被这条卡过，固化进门禁 #5 的教训 |