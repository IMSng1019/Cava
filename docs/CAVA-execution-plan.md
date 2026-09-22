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
7. **更新 `docs/CAVA-后续对话提示词.md` 的"附二：本轮成果"**，让下一个会话不必重做。

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
- `mixin.ai.pathing`：P1 复刻 → **关**（Lithium 的寻路优化让位给原生 A*）。
- `mixin.entity.collisions.movement`：**本轮不关**（P2 才决策）。
- `mixin.block.redstone_wire`：**本轮不关**（P3 才决策）。
- `mixin.shapes`：不关（不在我们的注入点）。

**Wave 2 之后、Wave 3 之前必须先冻结**：实体镜像 ABI（P2 的 SoA 布局）—— 由 P2 流按真实 `Entity` 推导，captain 审查后写进 `cava_abi.h`。

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
