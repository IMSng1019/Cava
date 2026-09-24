# Cava P0-E：真实测试服 + 第一条性能基线

> 本文件**只记录实测**。每条结论都带命令或日志出处；没测到的、做不到的，全部写进
> 第 5 节「未验证 / 卡点」，不含推测数字。
> 落盘时间：2026-09-22。测试服根目录：`J:\mc\Cava\testbed\`（`.gitignore` 已忽略，**不入库**）。

---

## 0. 结论速览

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| 测试服能不能真起 | **能**（Fabric 1.20.4 + 33 个 mod，带 `--enable-preview --enable-native-access=ALL-UNNAMED`） | `[14:03:13] [Server thread/INFO]: Done (3.157s)! For help, type "help"`（wave4 全量包） |
| 命令行能不能下命令 | **能**（RCON 25576，自研最小客户端 `tools/rcon.ps1`） | `tick query` / `spark tps` / `function` 均有回应 |
| 客户端存档能否给服务端用 | **能**，2932 个区块原样加载，逐 region 文件的区块数一致 | `testbed/hashes/client-save-source.json` vs `client-save-after-boot.json` |
| 同一存档连续两次运行是否逐 tick 一致 | **否**（本整合包当前状态）。tick 数三次运行完全一致，但区块内容有 23/2025 不同、实体区块 24/39 不同 | 第 3.3 节 |
| 四块占比 | 见第 4.3 节 | JFR 采样 + spark |
| 三条 logger 的落盘 | **Carpet/TIS 的 HUD logger 在无客户端的专用服务端上不落盘**（只写订阅文件）；vanilla `/debug` 在专用服务端**不写文件** | 第 4.4 节 |

---

## 1. 测试服的确切构建方式

### 1.1 版本与确切 URL（全部实读，不是记忆）

| 项 | 值 | 来源 |
| --- | --- | --- |
| Minecraft | 1.20.4 | 服务器日志 `Loading Minecraft 1.20.4 with Fabric Loader 0.19.5` |
| Fabric Loader | **0.19.5**（stable=true, build=5） | `https://meta.fabricmc.net/v2/versions/loader/1.20.4` 的第一条 stable（253 条里只有 1 条 stable） |
| Fabric Installer | **1.1.2**（stable=true） | `https://meta.fabricmc.net/v2/versions/installer` |
| 服务端 launcher jar | `https://meta.fabricmc.net/v2/versions/loader/1.20.4/0.19.5/1.1.2/server/jar` | 下载 **181,840 字节** → `testbed/dl/fabric-server-mc1.20.4-loader0.19.5-installer1.1.2.jar` |
| 原版服务端 jar | `https://piston-data.mojang.com/v1/objects/8dd1a28015f51b1803213892b50b7b4fc76e594d/server.jar` | 下载 **49,150,256 字节**，sha1 `8dd1a280…e594d`，脚本内校验 `sha1_match=true` |
| JDK | `C:\Program Files\Java\jdk-21` = 21.0.10+8-LTS-217 | `java -version` |

用到的 meta 接口（都走 `node fetch`；本机 PowerShell 的 `Invoke-WebRequest` TLS 全失败）：

- `https://meta.fabricmc.net/v2/versions/loader/1.20.4`
- `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` → 1.20.4 的 version json → downloads.server

### 1.2 一键脚本（可重跑，幂等）

| 脚本 | 作用 |
| --- | --- |
| `tools/setup-testbed.ps1` | 解析版本 → 下载 launcher + 原版 server.jar（sha1 校验）→ 写死确定性配置 → 调 copy-mods 铺 mod |
| `tools/copy-mods.ps1` | 按「三档处理」把 49 个 jar 铺进 `testbed/server/mods/`，其余留在 `testbed/disabled/<档>/`；带**逐个 jar 体积复核**，任何静默拷贝失败直接 throw |
| `tools/make-det-datapack.ps1` | 往世界里装一个 `#minecraft:load` 数据包：开局就 `tick freeze` + `save-off` |
| `tools/make-load-datapack.ps1` | 生成 `/function cava:spawn_mobs`（固定网格刷 N 只怪） |
| `tools/run-scenario.ps1` | 场景驱动器（起服 → 等 Done → RCON 定时下发 → 存盘 → 世界哈希 → 停服 → 写 `manifest.json`） |
| `tools/start-server.ps1` / `tools/lib-server.ps1` | 只起服/停服；**开服前先探测 server-port 是否已被占用**，占用就立刻报错 |
| `tools/rcon.ps1` | 最小 Source-RCON 客户端（避免 stdin 管道；沙箱下 Node 的管道 stdio 会 EPERM） |
| `tools/worldhash.cjs` | 世界指纹：逐区块提取**解压后的 chunk payload** 再哈希（region 头的时间戳不可比），另出剔除 `LastUpdate`/`InhabitedTime` 的 canonical 指纹 |
| `tools/diff-chunks.cjs` | 两次运行的逐区块差异（按 region/entities/poi 分类） |
| `tools/jfr-buckets.cjs` | JFR 采样 → 四块占比 |
| `tools/sparkprofile-probe.cjs` | spark `.sparkprofile`（裸 protobuf）结构探针 |
| `tools/sparkprofile-buckets.cjs` | 把 `.sparkprofile` 解成调用树并按四块汇总（协议结构用 `javap` 从 spark jar 里读出来） |
| `tools/dl.cjs` / `tools/meta.cjs` / `tools/get-vanilla.cjs` / `tools/yarnmap.cjs` | node 联网小工具 / 版本解析 / intermediary↔Yarn 反查 |

### 1.3 启动命令行原文（每次运行都记在 `testbed/runs/*/manifest.json`）

    java = C:\Program Files\Java\jdk-21\bin\java.exe
    args = -Xms2G -Xmx4G -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
           --enable-preview --enable-native-access=ALL-UNNAMED
           -jar J:\mc\Cava\testbed\dl\fabric-server-mc1.20.4-loader0.19.5-installer1.1.2.jar nogui
    cwd  = J:\mc\Cava\testbed\server

**实测结论：原版 + Fabric 完全接受 `--enable-preview --enable-native-access=ALL-UNNAMED`**，
日志里没有关于这两个参数的 WARN/ERROR（JDK 只在首次调用受限 FFM 方法时警告，本阶段没有原生库所以不会出现）。

stdin 被重定向到一个空文件（`stdin.empty`）：不用管道、不当交互终端，所有交互走 RCON。

### 1.4 server.properties 的确定性选择（脚本生成，不允许手改）

冻结项：`level-seed=20260922`、`level-type=minecraft:normal`、`randomTickSpeed=3`、`difficulty=normal`、
`view-distance=10`、`simulation-distance=10`、`gamemode=creative`、`online-mode=false`、
`enforce-secure-profile=false`、`spawn-protection=0`。

有意偏离默认的地方（都写在这里，免得以后被当成莫名其妙）：

| 键 | 值 | 为什么 |
| --- | --- | --- |
| `max-tick-time` | `-1` | 关掉看门狗：`/tick sprint 600`、profiler 采样时的长 tick 会被它当成卡死杀掉 |
| `sync-chunk-writes` | `true`（默认） | **故意不改**。改成 false 会改变落盘时序，属于会动语义的开关 |
| `enable-rcon` | `true`（密码 `cava`） | 沙箱下唯一可靠的命令通道 |
| `function-permission-level` | `4` | 默认 2 时数据包函数**看不到** `save-off`（权限 4）：实测报 `Unknown or incomplete command … at line 2`，整个函数加载失败 |
| `server-port` / `rcon.port` | `25566` / `25576` | 本机同时有别的 agent 在 25565 起探针服，实测撞端口 `java.net.BindException: Address already in use` |
| 自动保存 | 运行期用 `/save-off` 下发 | 原版根本没有 autosave 配置项 |

### 1.5 一个坑（已修）：文件名里的方括号

`优化模组/服务端模组/[地毯] fabric-carpet-….jar` 与 `[Gugle的Carpet附加包] ….jar` 文件名带方括号，
**PowerShell 的 `Copy-Item -Path` 对含 `[` `]` 的路径会静默什么都不做**（实测：命令返回成功、目标文件不存在）。
后果：第一次跑 wave3 时 carpet 没被拷进去，服务器直接 `HARD_DEP_NO_CANDIDATE carpet-tis-addition` 起不来。
现在 `copy-mods.ps1` 全部用 `-LiteralPath`，并加了逐个 jar 的体积复核。

---

## 2. 49 个 mod 的三档处理与实际结果

### 2.1 分波次冒烟实测（每加一批就跑一次）

| 波次 | 加了什么 | mod 数（日志 `Loading N mods`） | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 0 | fabric-api 0.96.11 + spark 1.10.58 | **44**（fabric-api 自身展开成几十个模块） | ✅ 起、收命令、干净停服 | `testbed/runs/wave0-smoke/server.log` |
| 1 | + 6 个库（FLK / architectury / cloth-config / YACL / Necronomicon / Jupiter） | **72** | ✅ | `runs/wave1/server.log` |
| 2 | + 13 个通用优化/修复（lithium、servercore、vmp、krypton、ferritecore、memoryleakfix、c2me、noisium、starlight、packetfixer、icterine、getittogetherdrops、Chunky） | **114** | ✅（有 `Error loading class: carpet/patches/EntityPlayerMPFake`，此时 Carpet 还没装，属预期） | `runs/wave2/server.log` |
| 3 | + Carpet 三件套 | **118** | ❌→✅（第一次因 1.5 的方括号静默拷贝失败而起不来；修好后 20.3s 到 Done） | `runs/wave3/server.log` |
| 4 | + 9 个功能类（LuckPerms、MiniMOTD、NoChatReports、customname、vanilla-permissions、easybot、servux、fix-mc-stats、fuji） | **147** | ✅ `Done (3.157s)` | `runs/wave4/server.log` |

Done 行原文（wave4）：

    [14:02:37] [main/INFO]: Loading Minecraft 1.20.4 with Fabric Loader 0.19.5
    [14:02:38] [main/INFO]: Loading 147 mods:
    [14:03:13] [Server thread/INFO]: Done (3.157s)! For help, type "help"

### 2.2 三档处理的实际结果（33 开 / 16 关 / 共 49）

机器可读清单：`testbed/mod-plan.tsv`（脚本输出，不进 git）。

| 档 | jar | 关掉的理由（照 `docs/CAVA-服务器模组清单.md` 第 5 节） |
| --- | --- | --- |
| 必须关 | server-BlueMap-5.3 | 网页地图：最大 CPU/IO 噪声源，且零交互 |
| 必须关 | server-Axiom-4.7.1 | 直写 ChunkSection + 非主线程光照，镜像钩子的唯一真威胁 |
| 必须关 | server-ledger-1.3.1 | 每次方块变更加派发入队 + 持续 DB 写 |
| 必须关 | server-ledger-databases-1.2.1 | Ledger 的 JDBC 打包依赖，随 Ledger 关 |
| 必须关 | server-geyser-fabric-2.2.2 | 第二套协议栈与额外实体 |
| 必须关 | automodpack-4.0.6 | 启动期扫描 + 内嵌 Netty 分发 |
| 必须关 | server-randomtp-8.0.1 | 一执行就同步加载数百区块 |
| 必须关 | server-fabric-MCMOD-1.5.0 | 定时 `System.gc()`，污染 tick 基线 |
| 建议关 | server-easyauth-3.2.1 | 未登录玩家 playerTick 被整体取消，会扭曲实体负载分布 |
| 建议关 | server-floodgate-2.2.0 | 基岩版支持，无基岩客户端时零收益 |
| 建议关 | voicechat-2.5.22 | 语音需要真实客户端与 UDP |
| 建议关 | server-syncmatica-0.3.11 | 投影共享，测试期无 litematica 客户端 |
| 客户端误装 | sodium / continuity / CustomSkinLoader / malilib | `env=client`，专用服务端根本不加载（清单第 3.3 节） |

**保留 = 33 个**（清单第 5 节说可保留的 LuckPerms / MiniMOTD 都在里面）。

### 2.3 启动日志里真实出现的告警（逐条摘，不是猜的）

| 日志原文（节选） | 含义 / 要不要管 |
| --- | --- |
| `Mod 'c2me' attempted to override option 'mixin.world.player_chunk_tick', which doesn't exist, ignoring` | **实锤**清单 A.3 的结论：c2me 的 fabric.mod.json 里这个键是无效键 |
| `Force-disabling mixin 'alloc.chunk_ticking.ServerChunkManagerMixin' as rule 'mixin.alloc.chunk_ticking' (added by mods [servercore])` | servercore 关掉了 lithium 的区块 tick 缓存 |
| `Force-disabling mixin 'alloc.blockstate.StateMixin' as rule 'mixin.alloc.blockstate' (added by mods [ferritecore])` | ferritecore 关掉 lithium 的 blockstate 分配优化，与镜像契约里的状态去重同一条链 |
| `Method overwrite conflict for getClimateSettings in architectury.mixins.json:BiomeAccessor from mod architectury, previously written by carpet.mixins.Biome_scarpetMixin. Skipping method.` | architectury 与 Carpet 在同一点抢 `@Overwrite`，architectury 让位。同点冲突真实存在 |
| `Reference map 'packetfixer-fabric-fabric-refmap.json' … could not be read`（memoryleakfix 同类） | 生产环境没有 refmap 是正常的，不是错误 |
| `BridgeClient-Worker/WARN: 正在尝试重连服务器` / `连接遇到错误: Connection refused: getsockopt` | **easybot** 死循环重连 `ws://127.0.0.1:26990`。不 tick 实体，但持续打日志刷网卡，**做基线建议关** |
| `未找到统计数据目录 .\world\.\stats 无法使用玩家统计变量!` | fix-mc-stats 提示，世界刚建时没有 stats 目录 |
| `The async-profiler engine is not supported for your os/arch (windows11/amd64), so the built-in Java engine will be used instead` | spark 在本机只能用内置 Java 采样引擎（影响精度，见第 4 节） |

**结论：33 个 mod 的组合没有任何启动级冲突**（无 mod id 冲突、无未满足依赖、无 breaks 触发）。

### 2.4 EasyAuth 的测试账号

- 本测试服 **EasyAuth 属于建议关 → 实际没装**，所以「未登录玩家 playerTick 被取消」这条在本环境不存在。
- 因此第 5 节把它列为「未验证」：**没有实测过 EasyAuth 开着 + 未登录 + 假人**的行为。
  以后若要在开着 EasyAuth 的环境做验收，必须显式验证：在 `config/easyauth/` 注册测试账号 → 用真客户端登录一次 → 再跑 `/player` 假人，
  并用 `/log` 或 spark 确认假人的 playerTick 真的在跑。**这一步没有自动化。**

---

## 3. 世界与确定性

### 3.1 现成的客户端存档能不能给服务端用 —— **能**（实测）

`tools/import-client-save.ps1` 把 `J:\mc\mods\run\saves\新的世界`（27 文件 / 17.7 MB）整份拷进
`testbed/server/world/`（只跳过 `session.lock`），然后正常起服：

    [step] READY after 23.4s: [14:05:22] [Server thread/INFO]: Done (2.423s)! For help, type "help"
    [step] > list            -> There are 0 of a max of 20 players online:
    [step] > save-all flush  -> Saving the game (this may take a moment!)Saved the game

**逐 region 文件的区块数完全一致**（服务端读的是原有区块，不是重新生成）：

| region 文件 | 源存档区块数 | 服务端读取后 |
| --- | --- | --- |
| region/r.-1.-1.mca | 1024 | 1024 |
| region/r.-1.0.mca | 640 | 640 |
| region/r.0.-1.mca | 512 | 512 |
| region/r.0.0.mca | 319 | 319 |
| region/r.-2.-1.mca | 160 | 160 |
| region/r.-1.-2.mca | 32 | 32 |
| entities/r.-1.0.mca | 13 | 12（实体在 ~250 tick 里被 tick 过，数量会变，属预期） |

指纹：源 `testbed/hashes/client-save-source.json`（digestPayload `1c1323e4…`，2932 区块）
vs 起服后 `client-save-after-boot.json`（`e49afcd3…`，2933 区块，`Time` 1105→1355）。
两次指纹不同是**预期**：服务端跑了 ~250 tick，`LastUpdate`/`InhabitedTime` 与实体列表必然变。

附带信息：该存档 `seed=2463666350834590950`、`Version.Name=1.20.4`、SpawnX/Z = -160/-112。

### 3.2 固定种子世界

`tools/scenarios/create-base-world.txt`：删世界 →（可选装 freeze 数据包）→ 起服 → 生成出生点区域 → `save-all flush` → 停服 → 快照。

- 不带 freeze 数据包：`base-a`（2082 区块，`Time=236`）、`base-b`（2079 区块，`Time=190`）
- 带 freeze 数据包（开局就冻结，`Time` 停在 1）：`base-c`（2061 区块）、`base-d`（2062 区块）

规模只有 ~2000 个区块（出生点准备区域），不是整个世界预生成 —— 满足「不要预生成整个世界」。

### 3.3 确定性验证（P0 验收项）—— **答案：否，当前整合包不是逐 tick 一致**

做法（可复现，脚本 `tools/scenarios/determinism.txt`）：

1. 从**同一快照 `base-c`** 恢复世界；
2. 装 `#minecraft:load` 数据包：开局 `tick freeze` + `save-off`，把游戏时间钉在快照值（实测 `ticks=1`）；
3. `tick sprint 200` → `save-all flush` → 世界指纹；再来 `200`、再来 `600`；
4. 三次独立运行 det5 / det6 / det7，比较 tick 数与逐区块哈希。

**tick 数完全一致**（三次运行，逐检查点一模一样）：

| 检查点 | det5 | det6 | det7 |
| --- | --- | --- | --- |
| 起点（快照） | 1 | 1 | 1 |
| t200 | 203 | 203 | 203 |
| t400 | 404 | 404 | 404 |
| t1000 | 1005 | 1005 | 1005 |

**但区块内容不一致**。取 det7-t1000 与 det6 的终局世界（两者 `ticks=1005`）逐区块比对：

| 目录 | 相同 | 不同 | 只在一侧 |
| --- | --- | --- | --- |
| `region/`（canonical，已剔除 LastUpdate/InhabitedTime） | 2002 | **23** | 0 / 0 |
| `region/`（原始 payload，含时间戳字段） | 1924 | 101 | 0 / 0 |
| `entities/` | 15 | **24** | 9 / 13 |
| `poi/` | 4 | 0 | 0 / 0 |

解读（把话说清楚）：

- **tick 调度是确定的**：同样起点 + 同样 tick 数 → 同样 game time。
- **世界内容不是逐位确定的**：
  - `entities/` 差异最大（39 个区块里 24 个不同，另有 22 个区块只在一侧）——实体有 UUID、有随机数、有异步区块加载时机；
  - `region/` 有 **23/2025 ≈ 1.1%** 的区块连「剔除时间戳后」的内容都不同 —— 原始比对 101 个不同、canonical 后剩 23 个，
    说明 78 个是 `LastUpdate`/`InhabitedTime`，**剩下 23 个是真的内容差异**；
  - 生成的区块**个数**也不同（det5/det6 = 2080/2081/2081，det7 = 2076/2077/2077）。
- 两次**全新生成**同一种子（`base-c` vs `base-d`，都在 `Time=1`）同样不一致：
  `region` 1907 同 / **118 不同**、`entities` 0 同 / 32 不同、`poi` 1 同 / 3 不同 + 1 只在一侧。

→ **「同一存档连续两次运行逐 tick 一致」这条 P0 验收，在当前整合包上不成立**；
  差分测试（prompts/03）与整服层验收必须先解决它。
  可疑方向（**尚未定位，属未验证**）：c2me 的并行 worldgen/chunkio、异步区块加载的墙钟依赖、实体 UUID/随机数、save 时的并发写。
  下一步建议：用 `copy-mods.ps1` 做「少 c2me 一档」的对照实验，看那 23 个 region 差异是否消失。

**方法论警告（会直接毁掉测量）**：`/tick sprint` 与 `/tick step` 的语义差别：

| 命令 | 实测行为 |
| --- | --- |
| `/tick sprint N`（游戏**未**冻结时） | 冲刺 N tick 后**继续正常跑** —— 实测「200 tick 的 sprint」最终推进了 **340 tick** |
| `/tick step N`（游戏已冻结） | 只在**实时**下推进（20 tick/s），`sleep 2` 只推进了 ~41 tick |
| **`/tick sprint N`（游戏已冻结）** | ✅ 冲刺 N tick 后**回到冻结**，且飞快；既精确又快的唯一组合（本次验收用它） |

---

## 4. 第一条性能基线

### 4.1 测量条件（每格数字都对应这一套条件）

| 项 | 空载基线 | 有负载基线 |
| --- | --- | --- |
| 场景脚本 | `tools/scenarios/baseline-empty.txt` / `measure-jfr-empty.txt` | `tools/scenarios/baseline-loaded.txt` / `measure-jfr-loaded.txt` |
| 世界 | `base-a`（固定种子 20260922，~2080 区块） | 同左 |
| 实体 | 无玩家、无额外实体（只有世界自带的少量生物） | **8 个 Carpet 假人（survival + 抗性提升 IV + 生命恢复 IV）+ 200 只僵尸**（`/function cava:spawn_mobs`，固定网格，`PersistenceRequired`） |
| 时间 | `doDaylightCycle=false`、`time=18000` | 同左 |
| 刷怪 | 默认 | `doMobSpawning=false`（只有我们刷的 200 只） |
| 自动保存 | `save-off` | `save-off` |
| mod 组合 | 33 个 keep 档（见 2.2） | 同左 |
| 机器状态 | 已 `netstat` 确认没有别人的 java 服务端占端口；但本机有别的 agent 的 Gradle daemon 常驻，CPU 不为零 | 同左 |

`/spark tps` 的 CPU 一栏是**整机**口径，别把别的进程算进来；下面的占比以 **tick 时长**为准，不看 CPU%。

### 4.2 spark 原始输出（逐字抄自 `testbed/runs/*/server.log`）

空载（`baseline-empty`）：

    [⚡] TPS from last 5s, 10s, 1m, 5m, 15m:
    [⚡]  19.97, *20.0, *20.0, *20.0, *20.0
    [⚡] Tick durations (min/med/95%ile/max ms) from last 10s, 1m:
    [⚡]  0.1/1.2/2.9/4.7;  0.1/2.0/7.4/178.8
    [⚡] CPU usage from last 10s, 1m, 15m:
    [⚡]  8%, 12%, 12%  (system)
    [⚡]  0%, 4%, 4%  (process)
    [Rcon: Stopped tick profiling after 20.00 seconds and 401 ticks (20.05 ticks per second)]

有负载（`measure-loaded`，8 假人 + 200 僵尸稳态）：

    [⚡] TPS from last 5s, 10s, 1m, 5m, 15m:
    [⚡]  *20.0, *20.0, *20.0, *20.0, *20.0
    [⚡] Tick durations (min/med/95%ile/max ms) from last 10s, 1m:
    [⚡]  2.7/5.3/8.7/12.0;  0.2/3.3/7.7/165.6
    [⚡] CPU usage from last 10s, 1m, 15m:
    [⚡]  24%, 18%, 20%  (system)
    [⚡]  1%, 3%, 3%  (process)
    [⚡] Memory usage: 725.9 MB / 4.0 GB   (17%)

有负载（`baseline-loaded`，同一场景的另一次运行，交叉验证）：

    [⚡]  19.98, 19.98, 19.27, 19.85, 19.95
    [⚡]  2.3/3.7/5.5/8.6;  0.5/4.0/11.6/130.9
    [Rcon: Stopped tick profiling after 19.98 seconds and 400 ticks (20.02 ticks per second)]


**交叉验证：Carpet 自己的 `/profile <ticks>`**（Carpet 内置的 tick 计时器，与 spark 独立）——
空载窗口实测返回 `[Rcon: Average tick time: 0.791ms]`，与 spark 的 MSPT 中位数 1.2 ms 同量级。
注意它的参数形式很挑：同一个 `profile 600` 在另一次运行里被拒（`Incorrect argument for command`），
且它**只回给命令源、不打印调用树**，所以四块占比还是得靠 `tools/sparkprofile-buckets.cjs`。

**MSPT 口径**：空载中位数 **1.2 ms**、95%ile 2.9 ms；有负载中位数 **4.4–5.3 ms**、95%ile 5.8–8.7 ms。
两档都跑满 20 TPS（有负载 5 分钟均值 19.85–19.95）。

### 4.3 四块占比

测量源：**spark 自己保存的 `.sparkprofile`**（`config/spark/profile-2026-09-22_14.23.38.sparkprofile`，有负载窗口）与
`profile-2026-09-22_14.20.35.sparkprofile`（空载窗口），用 `tools/sparkprofile-buckets.cjs` 解出协议树后统计。
协议结构是用 `javap` 从服务器上那个 spark jar 里读出来的（`SparkSamplerProtos$SamplerData/ThreadNode/StackTraceNode`），不是猜的。

**分母的口径**：把 `MinecraftServer.waitForNextTick`（空载时占整线程采样 77.9%，里面 70% 是 `LockSupport.parkNanos` 睡眠）
排除掉，只看 **world tick 子树** —— 因为四块占比要回答的是「这一 tick 的时间花在哪」，不是「服务器一天到晚在干嘛」。

| 四块 | 锚点（怎么算的） | 有负载采样 | 占 world tick | 空载采样 | 占 world tick | 是否实测 |
| --- | --- | --- | --- | --- | --- | --- |
| **方块 tick（含红石）** | `net/minecraft/world/tick/WorldTickScheduler` 子树（调度的方块/流体 tick 都走它；红石粉、中继器都在这里） | 5804 | **36.3%** | 2108 | **21.6%** | 实测 |
| **实体 tick** | 实体类族（`net/minecraft/entity/**`，含 `ai/pathing` 以外的 AI/移动）自身采样 | 36 | **0.2%** | 92 | 0.9% | **不可信，见下** |
| **寻路** | `net.minecraft.class_13`（`PathNodeNavigator.findPathToAny`，intermediary method_52/54）子树 | **0** | **0.0%** | 0 | 0.0% | **不可信，见下** |
| **区块生成 / 区块管理** | ① 类族 `world/gen`、`world/chunk`、`world/biome`、`structure`；② 父节点 `ServerChunkManager` 子树 | ① 1404 / ② 9656 | ① 8.8% / ② **60.5%** | ① 1012 / ② 6504 | ① 10.4% / ② 66.6% | 实测（看清口径） |

**同一棵树里的其他可读事实（有负载窗口，占 world tick）** —— 这些比四块本身更有信息量：

| 节点 | 占 world tick | 说明 |
| --- | --- | --- |
| `ServerChunkManager` → `SpawnHelper` | **21.2%** | **自然刷怪**是这份负载里第二大的单项，比除区块管理外的一切都大 |
| `ServerChunkManager` → `ServerChunkManager`（tickChunks 主体） | 37.4% | 区块 tick 主体（随机刻 / 方块实体 / 实体 tick 都从这里进） |
| `ChunkTicketManager` | 22.7% | ticket/距离传播（其中 c2me `NoTickSystem` 5.8%、`Delayed8WayDistancePropagator2D` 5.5%） |
| `WorldTickScheduler` → `ServerWorld` → `AbstractBlock$AbstractBlockState` | 16.0% | **方块 tick 里最大的单项**（方块状态回调，红石类就在这里） |
| `WorldTickScheduler` → `ServerWorld` → `FluidState` | 5.3–6.8% | 流体 tick |
| `WrapOperation$…$implOnTickWorlds`（Carpet 包裹的 tickWorlds） | 100% | 整个世界 tick 的根；Carpet 在这里插了 `yeetUpdateSuppressionCrash` |

**为什么「实体 tick」和「寻路」两格不可信（卡点，必须说清）**

- 这份 profile 里 **200 只僵尸确实存在**：profile 元数据按维度的实体统计写着
  `minecraft:zombie = 200`、`minecraft:player = 8`、`minecraft:cow = 14`、`minecraft:chicken = 32`、`minecraft:chest_minecart = 18`、
  `overworld 合计 = 345`（`tools/sparkprofile-probe.cjs --field=1` 解出来的）。
- 但采样树里**一帧实体类都没有**（整个树 2537 个节点，`entity` 字样只出现在 bucket 汇总行），`class_13`（寻路主体）同样一帧都没有。
- 原因：本机 **async-profiler 引擎不可用**，日志原文 `The async-profiler engine is not supported for your os/arch (windows11/amd64), so the built-in Java engine will be used instead`。
  spark 的内置 Java 采样器（ThreadMXBean 路线）在这台机器上只稳定采到调用栈的**外层**（`ServerWorld` / `ServerChunkManager` / `$$Lambda` 这些），
  采不到深处的实体与寻路帧。→ **这两格记 0.2% / 0.0% 是采样器分辨率的结果，不是「实体和寻路不花时间」的结论。**
- 影响：清单第 5 节要求的关键判据「**如果寻路占比 < 5%，P1 的优先级要重新评估**」**在本机无法用 spark 判定**。
  要判定必须换采样器：把 JFR 跑通（`-XX:StartFlightRecording` 已经能「排定」录制，但实测文件 0 字节，未解决 —— 见第 5 节卡点 3），
  或在有 async-profiler 支持的平台上复测。

**可以直接用的两条结论**：

1. **方块 tick（含红石）在两种负载下都是实打实的大头**：空载 21.6%、有负载 36.3%（有负载时还叠了僵尸踩压力板/流体等）。
   → P3（红石）的收益上限不低，值得做。
2. **刷怪（`SpawnHelper` 21.2%）与区块 tick/ticket 管理（合计 ~60%）是更大的两块**，而且它们都不在 Cava 的三个子系统里。
   做收益预估时不能只看四块。


### 4.4 三条 logger 的落盘位置与格式（**坏消息，必须看**）

| logger | 谁提供 | 落盘位置（实测） | 结论 |
| --- | --- | --- | --- |
| `/log microTiming`（TIS） | carpet-tis-addition 1.82.3 | **不落盘**。只有订阅状态写到 `testbed/server/config/carpettisaddition/logger_subscriptions.json`（实测内容：`{"473de042-…-ff30d01fb299": {"microTiming": null, "pathfinding": null}}`），输出走 HUD | 专用服务端 + 假人 = 没有 HUD，**采不到数值** |
| `/log movement`、`/log pathfinding`（Carpet/TIS） | carpet / TIS | 同上；日志里只留一行订阅确认 `[bot01: bot01 subscribed to microTiming.]` | 同上 |
| vanilla `/debug start` / `/debug stop` | 原版 | **专用服务端不写任何文件**；只有控制台一行 `Stopped tick profiling after 20.00 seconds and 401 ticks (20.05 ticks per second)` | 拿不到调用树 |

下命令的正确姿势（实测）：RCON 直接 `/log microTiming` 会返回 `[Rcon: No player specified]`，
必须 `/execute as <假人名> run log microTiming` 才订阅成功（日志出现 `[bot01: bot01 subscribed to microTiming.]`）。

真正**能落盘**的是这两个：

1. **spark** → `testbed/server/config/spark/profile-YYYY-MM-DD_HH.MM.SS.sparkprofile`
   - **不是 gzip、是裸 protobuf**（实测首字节 `0a d5 80 01`）；同目录还有 `activity.json`。
   - 元数据可解（`tools/sparkprofile-probe.cjs`）：平台、JVM 参数、**server.properties 全文**、147 个 mod 的 id+版本、
     以及**按维度的实体统计**（如 `minecraft:chicken 32`、`minecraft:falling_block 65`）。
   - 采样数据在 top-level 字段 2：`ThreadNode{name, children[], times[], children_refs[]}`，每个 `StackTraceNode` 自带
     `class_name / method_name / line_number / method_desc / times / children_refs`；**`times` 是每个时间窗口的采样数（含子树，即 inclusive）**，
     `children_refs` 是指向同一扁平数组的下标 —— 所以不需要 spark 的网页查看器也能还原整棵调用树。
     已实现：`tools/sparkprofile-buckets.cjs`（`--tree <最小百分比>` 打调用树、`--root <子串>` 从某个节点往下算占比）。
2. **JFR 录制** → `testbed/runs/<name>/cava.jfr`，再用 `jfr print` 转文本。
   - **`jcmd` 在本机挂不上服务器**（实测 `java.io.IOException: 拒绝访问`，沙箱禁止 attach 用的命名管道），
     必须在**启动命令行**上加 `-XX:StartFlightRecording=name=cava,settings=profile,delay=60s,duration=60s,filename=…`；
     `run-scenario.ps1` 已加 `-JfrFile` 参数把这个坑封装掉。

---

## 5. 未验证 / 卡点

| # | 卡点 | 具体卡在哪 | 需要什么 |
| --- | --- | --- | --- |
| 1 | **逐 tick 一致的确定性不成立** | 三次运行 tick 数完全一致，但 `region/` 有 23/2025 区块内容不同、`entities/` 24/39 不同、区块集合也不同 | 「少 c2me 一档」的对照实验定位根因；这是 prompts/03 的前置条件 |
| 2 | **Carpet/TIS logger 不落盘** | HUD logger 只在有客户端渲染时输出；专用服务端 + 假人没有 HUD | 接真客户端，或改用别的证据源；**P2/P3 的差分夹具不能依赖这两个 logger 的文件** |
| 3 | **vanilla `/debug` 在专用服务端不写文件；JFR 也写不出文件** | `/debug` 只有控制台一行统计。JFR：`-XX:StartFlightRecording` 能被 JVM 接受（日志 `Recording 1 scheduled to start in 1 m. The result will be written to …`），但运行结束后 `cava.jfr` **始终 0 字节**（delay=60s/duration=60s 与 delay=15s/duration=20s 两次都一样，服务端本身干净退出） | 四块占比目前只能靠 spark 的 `.sparkprofile`（已解出，见 4.3）；JFR 需要进一步排查（怀疑 repository 目录在工作区外被沙箱拒绝，试 `-XX:FlightRecorderOptions=repository=<工作区内路径>`） |
| 4 | EasyAuth 未登录玩家行为 | 本环境没装 EasyAuth | 手工步骤，见 2.4 |
| 5 | 整机 CPU 噪声 | 别的 agent 的 Gradle daemon 常驻，spark 的 CPU% 是整机口径 | 基线期间独占机器，或用 tick 时长（本文件的占比都用 tick 时长） |
| 6 | Fabric Loader 版本 | 用的是 0.19.5（当前最新 stable），整合包原始环境未必是它 | 若验收要求与整合包原样一致，需确认原 Loader 版本 |
| 7 | `/tick sprint` 的 "+2 tick" | 起点 1 → `sprint 200` 后是 203（+202），到 t400 是 +201、到 t1000 是 +601。三次运行**完全一致**，不影响可比性，但语义要写清楚 | — |

---

## 6. 复现命令（复制即可）

    # 0) 前置：联网只能走 node；本机 PowerShell 的 Invoke-WebRequest TLS 全失败
    $node = 'C:\Program Files\nodejs\node.exe'

    # 1) 建服（幂等；下载 launcher + 原版 server.jar + 写配置 + 铺 mod）
    pwsh -File tools/setup-testbed.ps1 -Wave 4

    # 2) 冒烟：起服 → RCON 下命令 → 停服
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/wave-smoke.txt -Name wave4

    # 3) 客户端存档能否复用（会先算源存档指纹）
    pwsh -File tools/import-client-save.ps1
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/import-client-save.txt -Name client-save

    # 4) 造确定性世界（带 freeze 数据包，Time 停在 1）
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/create-base-world.txt -Name base-c

    # 5) 确定性验收：跑两次，再逐区块比对
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/determinism.txt -Name det5
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/determinism.txt -Name det6
    & $node tools/diff-chunks.cjs testbed/hashes/det5-t1000.chunks.canonical.tsv testbed/hashes/det6-t1000.chunks.canonical.tsv 20 --summary

    # 6) 两档基线（spark TPS/MSPT + /debug tick 统计）
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/baseline-empty.txt  -Name baseline-empty
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/baseline-loaded.txt -Name baseline-loaded

    # 7) 四块占比：JFR 必须在启动参数里武装（jcmd 挂不上）
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/measure-jfr-loaded.txt -Name measure-jfr-loaded -JfrFile J:\mc\Cava\testbed\runs\measure-jfr-loaded\cava.jfr
    & 'C:\Program Files\Java\jdk-21\bin\jfr.exe' print --events jdk.ExecutionSample --stack-depth 40 testbed\runs\measure-jfr-loaded\cava.jfr > testbed\runs\measure-jfr-loaded\jfr-samples.txt
    & $node tools/jfr-buckets.cjs testbed\runs\measure-jfr-loaded\jfr-samples.txt --report

---

## 7. 【追加】P1-PERF：大搜索空间下的 native on/off 性能与一致性（2026-09-24）

P0-E 这份基线里的"寻路"两格（4.3 节：`class_13` 采样 0.0%，判定为**采样器分辨率不可信**）
在本轮被**另一条路**补上了：不靠采样器，直接构造大搜索空间的合成场景，逐调用计时 + 逐字段比对。

- **全文（含命令、回执原文、产物哈希、两处缺陷的定位）**：`docs/CAVA-pathfind-perf.md`
- 驱动脚本：`tools/parity-perf-pathfind.ps1`（私有测试服 `testbed\perf-pathfind`，端口 25660/25661）
- 场景与 bench：`src/main/java/cava/hook/PathfindPerf{Scenario,Bench}.java`（`/cava pathfind perf|site|diag|explore`）

三条直接与这份基线有关的实测结论：

1. **原 4.3 节"寻路占比 0.0%"确实只是采样器问题**：本整合包上 40 僵尸 + 1 村民的负载实测
   **0.22 次寻路/tick**（1200 tick 冲刺、前后金丝雀差值），单次调用从 33 µs（1 节点）
   到 10.6 ms（138 节点强制绕行）/ 1.86 ms（503 节点迷宫，vanilla 侧）不等。
2. **大场景下 native 更快，且随规模增长**：去掉两腿共有的 ChunkCache 构建后
   128 节点 2.53x、200 节点 2.62x、503 节点 3.04x；强制每次重推镜像窗口时仍有 1.28x–1.60x。
   把实测频率 0.22 次/tick 乘上去，maze63 规模每 tick 约省 **269 µs**（投影，不是直测）。
3. **但一致性不通过**：`detour128` 场景两腿在 reuse/repush 两种模式下都出现差异 ——
   ①镜像窗口（起点终点包围盒 + 4）装不下绕行路径 ⇒ native 停在墙前（64 vs 128 节点）；
   ②镜像的"同矩形复用"**没有任何失效来源**（`ChunkSection.setBlockState` 主钩子在仓库里不存在）
   ⇒ 地形改了 native 不知道，实测**路径穿过 8 格实心石头**。
   **在这两条修掉之前，P1 不能算"与同整合包 native 关闭逐 tick 一致"。**

另外两条与基线环境有关的实测更正：

- **"生物被静默移除"在 `testbed\perf-pathfind` 上没有复现**：NoAI 与带 AI 的猪在解冻世界里
  30 秒（661 tick）后都还活着；40 只僵尸在 1200 tick 里真实发起了 259/278 次寻路。
  （共享 `testbed\gate-preview` 上当年的观测没有在本目录复现，两者 mod/config 不完全一样。）
- **`/forceload add` 有 256 区块/次的硬上限**，超了整条命令被拒（`Too many chunks in the specified
  area (maximum 256, specified 484)`）⇒ 需要分片；而"原版 `findPathToAny` 只看得见
  `followRange+8` 半径内的方块"这条约束会让**终点在半径外时两腿都返回 1 个节点的路径**，
  看起来还"一致" —— 做寻路场景时这是第一个要查的坑。

