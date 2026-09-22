# Cava 起步侦察记录（captain，2026-09-22）

> 本文件只记录**本机实测事实**与**已核实映射**，不含推测。凡是推测一律标注「未验证」。
> 目的：让 build-infra / native-core / ffm-java / parity-harness / compat-layer / pathfind-eng / entity-eng / redstone-eng
> 开工前不必重复踩坑。

## 1. 沙箱与 GRADLE_USER_HOME（**必读，会直接让构建失败**）

| 事实 | 实测证据 |
| --- | --- |
| 会话文件沙箱模式 = workspace-write，工作区 = `J:\mc\Cava` | 运行期上下文 |
| `J:\mc\mods\.gradle-home`（当前 GRADLE_USER_HOME）**可读不可写** | `New-Item Junction` 到该目录后写文件报 `Access to the path ... is denied`；wrapper 直接 `java.io.FileNotFoundException: ...gradle-9.7.1-bin.zip.lck (拒绝访问)` |
| 用 junction 绕过**行不通** | 同上：junction 本身能建，但通过 junction 写仍被拒绝 |
| 结论 | `GRADLE_USER_HOME` 必须指向工作区内，例如 `J:\mc\Cava\.gradle-home`，并已加入 .gitignore |

可复制命令：

```powershell
$env:GRADLE_USER_HOME = 'J:\mc\Cava\.gradle-home'
.\gradlew.bat build --console=plain
```

缓存体量参照（决定首次构建要下多少）：`J:\mc\mods\.gradle-home` = 2735.6 MB / 11849 文件；
`C:\Users\郁小悟520\.gradle\caches\fabric-loom\1.20.4` = 174.5 MB。

## 2. 本机工具链（均已实测存在）

| 工具 | 路径 | 备注 |
| --- | --- | --- |
| JDK 21 | `C:\Program Files\Java\jdk-21`（JAVA_HOME），`21.0.10+8-LTS-217` | 唯一可用运行时（预览版 class 版本 65.65535） |
| CMake | `J:\mc\Cava\tools\cmake\cmake-3.31.2-windows-x86_64\bin\cmake.exe` | 不在 PATH，写全路径 |
| MinGW GCC 15.2 | `C:\mingw64\bin\g++.exe`，构建器 `C:\mingw64\bin\mingw32-make.exe` | 配 `-G "MinGW Makefiles"` |
| MSVC | `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe` | 配 `-G "Visual Studio 17 2022"` |
| node | `C:\Program Files\nodejs\node.exe` | 联网走 node fetch（web_fetch 对 GitHub/Modrinth 不可用） |
| git | `C:\Program Files\Git\cmd\git.exe` | 身份已配：`yxw1019 <147071791+yxw1019@users.noreply.github.com>` |

网络可达性实测：`maven.fabricmc.net` 200、`piston-meta.mojang.com` 200、`repo1.maven.org` 200。

## 3. 测试服务器现状（影响 P0-E / P0-F / 差分测试 / 兼容层）

- **没有现成的服务端根目录**。`J:\mc\mods\run` 是**客户端** run 目录（`run\mods` 里 0 个 jar，只有 xaero/modmenu 配置）。
  全盘 `server.properties` 命中：`J:\mc\mods\run`、`J:\mc\p2p\run`、`J:\mc\titular\run`，都不是这套整合包的服务端。
- 49 个服务端 mod 全在 `J:\mc\Cava\优化模组\服务端模组\`，合计 **174.6 MB**，与 `docs/CAVA-服务器模组清单.md` 一致。
- 现成存档 `J:\mc\mods\run\saves\新的世界` = 17.7 MB（**客户端存档格式，未验证能否直接给服务端用**）。

→ 因此 P0-F 需要在工作区内新建真实 Fabric 服务端（建议 `J:\mc\Cava\testbed\server\`，已 gitignore）。

## 4. 已核实的映射（来自本机 mappings.tiny，可直接用于 mixin）

完整表见 `docs/CAVA-yarn-intermediary-map.md`（脚本生成，可重跑：`.cava-research/gen-yarn-map.cjs`）。这里只列 P0/P1 会用到的：

| 用途 | Yarn 类.方法 | official 描述符 | intermediary |
| --- | --- | --- | --- |
| 寻路主体（Map 版） | `PathNodeNavigator.findPathToAny` | `(Lbgs;Lefe;Ljava/util/Map;FIF)Lefg;` | `method_54` |
| 寻路主体（Set 版） | `PathNodeNavigator.findPathToAny` | `(Lcuc;Lbmn;Ljava/util/Set;FIF)Lefg;` | `method_52` |
| 镜像主钩子 | `ChunkSection.setBlockState` | `(IIILdjh;Z)Ldjh;` | `method_12256` |
| 红石线网更新 | `RedstoneWireBlock.update` | `(Lctp;Lhx;Ldjh;)V` | `method_10485` |
| 红石取电（Lithium 抢占点） | `RedstoneWireBlock.getReceivedRedstonePower` | `(Lctp;Lhx;)I` | `method_27842` |
| 二极管取电 | `AbstractRedstoneGateBlock.getPower` | `(Lctp;Lhx;Ldjh;)I` | `method_9991` |
| 实体移动 | `Entity.move` | `(Lbmr;Lelt;)V` | `method_5784` |
| 实体推挤 | `Entity.pushAwayFrom` | `(Lblv;)V` | `method_5697` |
| 碰撞合并（Lithium @Overwrite 点） | `Entity.adjustMovementForCollisions` | `(Lblv;Lelt;Lelo;Lctp;Ljava/util/List;)Lelt;` | `method_20736` |
| 方块碰撞求解 | `CollisionView.getBlockCollisions` | `(Lblv;Lelo;)Ljava/lang/Iterable;` | `method_20812` |
| 实体枚举 | `EntityView.getOtherEntities` | `(Lblv;Lelo;Ljava/util/function/Predicate;)Ljava/util/List;` | `method_8333` / 无谓词版 `method_8335` |

## 5. 本次踩过的坑（给后续写解析脚本的人）

1. **tiny v2 的 javadoc 行也以 `c` 开头**，形如 `c\t<长注释文本>` 且**带缩进前缀**。
   把它当 class 行会污染状态、让后续成员挂到错误的类上——本机实测曾一次性毁掉 2035 个类（连 `Entity` 都变成 0 个方法）。
2. class 行的判别式是：`p.length === 4 && p[2].indexOf('class_') > 0`。
   注意 **`p[2]` 是带命名空间的 `net/minecraft/class_XXXX`**，用 `indexOf(...) === 0` 会全部判错（踩过）。
3. member 行以 **TAB 开头**，列序为 `['', 'm'|'f', officialDesc, intermediaryDesc, intermediaryName, namedName]`。

## 6. 仍未确认 / 未验证

- 工作区内 `GRADLE_USER_HOME` 首次构建**实际需要下载多少、耗时多久**：未验证（任务在 P0-A 里）。
- `优化模组/服务端模组/`（174.6 MB）**是否要纳入 git**：未验证，属仓库体积决定，等用户定。
- 存档 `新的世界` 能否用于服务端整服层差分：未验证（P0-F/P0-D 会碰到）。