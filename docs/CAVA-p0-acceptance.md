# Cava P0 验收台账（诚实版）

> **规矩（本文件存在的理由）**：
> 1. 逐条抄 `prompts/01-P0-环境与骨架.md` 的验收标准，一条不漏。
> 2. **每一项「已完成」后面必须有可复现的证据**（文件路径 + 命令 + 真实输出摘要）。**没有证据就不许写已完成。**
> 3. 其它流正在同时实现这些项 → 允许写「由 <哪个流> 负责，待整合时回填」，但**必须把「需要什么证据」写死**，方便 captain 回填。
> 4. 本文件由 **P0-D** 维护；回填时请保留原有证据行、只改「状态/证据」两列。
>
> 维护者：P0-D ｜ 最后更新：2026-09-22（Wave 1 并行期）

---

## 1. 验收标准（prompts/01 原文四条）

| # | 验收标准（原文） | 状态 | 一句话证据 |
| --- | --- | --- | --- |
| A1 | 服务端带 `--enable-preview` 能启动，并打印 ABI 版本与布局自检结果 | **部分完成** | 「带 flags 启动」✅ 已实测（`gate-preview/logs/latest.log:80` `Done (14.193s)!`）；「打印 ABI 版本与布局自检」＝**待回填**（归 P0-C/P0-E） |
| A2 | native 关闭 / 开启两次运行，黄金轨迹**完全一致**（此时原生什么都不做） | **进行中（未采到）** | 尚无黄金轨迹文件；比对器与运行器归 P0-C，测试服归 P0-E |
| A3 | 一键回退可用（`-Dcava.native.enabled=false`）且不报错 | **进行中（未验证）** | 契约 §4.1 已冻结开关；实现归 P0-C，未跑过 |
| A4 | 拿到 spark 基线四块占比数字 | **进行中（未采到）** | spark 1.10.58 已装且服务端可跑；采样归 P0-E |

---

## 2. 逐条展开（状态 / 证据 / 卡点 / 回填需要什么）

### A1 服务端带 `--enable-preview` 能启动，并打印 ABI 版本与布局自检结果

**状态**：拆成两半，前半**已完成**，后半**待回填**。

**① 服务端带 `--enable-preview` 能启动 —— ✅ 已完成（证据充分）**

- 证据文件：`testbed/gate-preview/logs/latest.log`（80 行）
- 关键行（原文，行号即文件行号）：

      :18  [CAVA-GATE] ================= PREVIEW GATE =================
      :19  [CAVA-GATE] mod class file major.minor = 65.65535   (65.65535 == preview)
      :20  [CAVA-GATE] runtime               = 21.0.10+8-LTS-217
      :21  [CAVA-GATE] fabricloader          = 0.19.5
      :22  [CAVA-GATE] java.lang.foreign     = java.lang.foreign.Linker
      :23  [CAVA-GATE] native strlen("cava")  = 4   (expect 4)
      :24  [CAVA-GATE] VERDICT               = PREVIEW_OK
      :80  [Server thread/INFO]: Done (14.193s)! For help, type "help"

- 复现命令：`pwsh -File tools/start-server.ps1 -Name <name> -MaxSeconds 180`
  （JVM 参数由脚本固定为 `-Xms2G -Xmx4G … --enable-preview --enable-native-access=ALL-UNNAMED`）
- **这条同时把 `docs/CAVA-execution-plan.md` §5 第 5 条的「架构级门」（预览版 class 能否被 Fabric Loader 加载）判为通过**：
  class minor=65535 的 mod 被 loader 0.19.5 正常加载，且 FFM 的 `java.lang.foreign.Linker` 在该 JVM 上可用，
  原生调用（`strlen`）成功返回 4。

**② 打印 ABI 版本与布局自检结果 —— ⏳ 待回填（归 P0-C，运行在 P0-E 的测试服）**

- 现状：原生侧已经有 `cava_abi_version` / `cava_layout_report`（MinGW 产物导出符号实测齐全，见 §3.1），
  但 **Java 侧还没有把 Cava 打进测试服跑一次**（`testbed/server/mods` 里没有 Cava 的 jar）。
- **回填需要什么证据（写死）**：
  1. `testbed/server/mods/` 里出现 Cava 的构建产物（`build/libs/cava-0.1.0.jar`）；
  2. `testbed/server/logs/latest.log` 里出现 **ABI 版本行**（形如 `cava_abi_version=1`）与
     **布局自检结果**（4 个结构体全过 / `layout_hash_sum` 相等）；
  3. 启动日志无 `CAVA_ERR_LAYOUT` / `LOAD_FAILED` / 段错误；
  4. 命令：`pwsh -File tools/start-server.ps1 -Name p0-accept -MaxSeconds 180` 的完整日志路径。
- 前置卡点：`gradlew build` 当前受阻于 Loom/JDK25（见 A5 与 `docs/CAVA-build.md` §3）→ 归 **P0-A**。

### A2 native 关闭 / 开启两次运行，黄金轨迹完全一致

**状态**：⏳ **进行中（未采到任何轨迹）**。

- 已有：契约 §4 冻结的格式与开关；`docs/CAVA-parity-fixtures.md` 给出的采集步骤与确定性前置条件。
- **回填需要什么证据（写死）**：
  1. 两个文件：`trace-native-off.ndjson`、`trace-native-on.ndjson`（含 trace 头，`native` 字段分别为 false/true）；
  2. **先有确定性证明**：同一配置**连续两次** native=off 的轨迹逐 tick 相同（这是前提，不能跳过）；
  3. 比对器输出原文：零差异时必须打印 `ZERO DIFF over N ticks`（N = 实际 tick 数）；
  4. 命令与耗时：`.\.gradlew.bat parityDiff -Pcava.natives=off,on -Pcava.ticks=6000`（或实际使用的手工命令）。
- 负责流：**P0-C**（运行时采集 + 比对器 + Gradle 任务）；宿主：**P0-E**（`testbed/server`）。
- 卡点：① Gradle 构建受阻（P0-A）；② 原生库尚未被 Java 侧加载（P0-C）；③ 存档与场景脚本未定（P0-E）。

### A3 一键回退可用（`-Dcava.native.enabled=false`）且不报错

**状态**：⏳ **进行中（未验证）**。

- 已有：契约 §4.1 表格冻结了 `-Dcava.native.enabled`（默认 `true`，`false` = 完全不加载原生库）；
  `build.gradle` 里测试默认注入 `cava.native.enabled=false`（P0-A 已写）。
- **回填需要什么证据（写死）**：
  1. 同一 jar 在 `-Dcava.native.enabled=false` 下启动的日志片段：原生状态 = `DISABLED_BY_FLAG`，
     且**没有 ERROR**（允许 INFO/DEBUG）；
  2. `-Dcava.native.enabled=true` 时状态为 `OPEN`（或明确的降级状态 + 原因），两者对照；
  3. 失败的失败路径证据（P0 冒烟第 3 条）：故意改坏 `layout_hash_sum` → `CAVA_ERR_LAYOUT` 且 handle=0，且**服务端不崩**。
- 负责流：**P0-C**（facade 状态机）+ **P0-B**（原生返回错误码）。

### A4 拿到 spark 基线四块占比数字

**状态**：⏳ **进行中（未采到）**。

- 已有：spark 1.10.58 在 `mod-plan.tsv` 里是 wave0（`keep`）成员；测试服可跑（A1 证据）。
- **回填需要什么证据（写死）**：
  1. spark 报告文件或原始链接（`/spark profiler start|stop` 产出）；
  2. **四块占比数字**：实体 tick / 寻路 / 方块 tick（含红石）/ 区块生成，各占 %，以及总采样时长与 MSPT；
  3. mod 组合与环境标注：wave 号、`testbed/mod-plan.tsv` 的三档结果、`server.properties` 的关键值、
     是否有假人/实体规模（`/spark health` 或 `/counter` 输出）。
- 负责流：**P0-E**（`docs/CAVA-baseline.md` 是它的落盘处）。
- 注意：**MCMOD 的定时 `System.gc()` 会污染基线**（已在 `noise` 档关闭）；EasyAuth 未登录会扭曲实体负载（见清单 §7.6）。

---

## 3. 我（P0-D）本轮独立实测到的证据（供 captain 回填时直接引用）

### 3.1 原生库真的能编出来（MinGW）—— ✅

- 命令（完整可复制）：见 `docs/CAVA-build.md` §4.1。
- 输出摘要：

      -- cava: 源文件 7 个 -> J:/mc/Cava/natives/windows-x64
      -- cava: fp-flags=-O2 -fwrapv -ffp-contract=off -fno-fast-math
      [ 57%] Linking CXX shared library …\natives\windows-x64\cava.dll
      == mingw build exit: 0

- 导出符号（`objdump -p` 实测）：`cava_abi_touch / cava_abi_version / cava_bits_of_double / cava_build_id /
  cava_close / cava_d2i_sat / cava_d2l_sat / cava_double_of_bits / cava_layout_report / cava_open`。
- **状态**：编译链 ✅；但 **Java 侧尚未加载过它**（A1② / A2 仍待回填）。

### 3.2 原生自测程序当前不是全绿 —— ⛔（P0-B 的进度）

- 命令：`$env:PATH='C:\mingw64\bin;J:\mc\Cava\natives\windows-x64;'+$env:PATH` 后直接跑
  `build\native-mingw\native\tests\cava_test_cava_selftest.exe`
- 输出摘要（前 12 行）：

      === cava_selftest ===
      build_id     : cava 0.1.0 win-x64 mingw-gcc-15.2.0 O2/fwrapv/ffp-contract=off safe=0 asan=0 ubsan=0
      platform     : 1   pointer_size=8
      --- 1. abi version / build id ---
        [ ok ] cava_abi_version()=1 == CAVA_ABI_VERSION=1
        [ ok ] report.abi_version=1
        …

- **退出码 = 1**（ABI 段全 ok，后续段失败）。**因此「布局自检 4 条全过」目前不能写成已完成。**
- 另两个测试可执行文件本轮 exit=0：`cava_test_cava_fp_probe`（生成 15456 行向量）、`cava_test_tmp_p0a_smoke`。
- **回填需要什么证据**：`cava_test_cava_selftest.exe` 退出码 0 的完整输出（含 4 个结构体的 `layout_hash` 比对）。
- 负责流：**P0-B**。

### 3.3 架构级门（预览版 class）—— ✅ 见 A1①

### 3.4 测试服存在且能跑 —— ✅ 见 `docs/CAVA-服务器模组清单.md` §7.7

### 3.5 构建链的当前卡点 —— ⛔（P0-A 的进度，不是验收项但会卡住 A1②/A2）

- `gradlew build`：`fabric-loom 1.18.2 要求 JVM 25`（与 JDK 21 冲突）→ captain 已决定 pin `loom 1.17.20`。
- 证据与复现：`docs/CAVA-build.md` §3.2（含 sha256、原始错误文本）。
- **回填需要什么证据**：pin 之后 `.\.gradlew.bat build --console=plain` 成功 + `build/libs/cava-<ver>.jar` 存在 + 首次下载量/耗时。

---

## 4. 状态用词定义（避免歧义）

| 状态 | 含义 |
| --- | --- |
| **已完成** | 有可复现证据（文件/命令/输出），任何人照做都能得到同样结果 |
| **部分完成** | 一条验收标准里只有一部分有证据（本文件必须写清「哪一半」） |
| **进行中** | 负责流正在做，尚无证据；已写死「回填需要什么证据」 |
| **受阻** | 有明确外部卡点（例：Gradle 构建失败、缺测试服）；已写清卡在哪 |
| **无法在本机验证** | 本机缺必要前提（例：需要真实服务端根目录、需要客户端） |

---

## 5. 附：相关落盘位置

| 内容 | 文件 |
| --- | --- |
| 构建手册与构建卡点 | `docs/CAVA-build.md` |
| 差分夹具 / 采集通道 / 确定性前置 | `docs/CAVA-parity-fixtures.md` |
| 测试服部署（含预览门证据） | `docs/CAVA-服务器模组清单.md` §7 |
| 工具与坑（映射/javap/联网/FFM） | `docs/CAVA-dev-toolbox.md` |
| 文件所有权与整合门 | `docs/CAVA-execution-plan.md` §3/§5 |
| 基线数据（P0-E） | `docs/CAVA-baseline.md`（P0-E 维护） |

> **给 captain 的回填提醒**：本文件任何一行改成「已完成」之前，请把**证据行**（文件路径 + 命令 + 输出摘要）
> 一起写进去。没有证据的「已完成」在本项目里视为无效交付。
