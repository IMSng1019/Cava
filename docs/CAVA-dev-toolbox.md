# Cava 开发工具箱（本机已验证）

> 这张表是"少走弯路"的入口。每一项都**在本机实际跑过**，命令可直接复制。
> 未验证的一律标注。文件所有权见 `docs/CAVA-工程接口契约.md`。

## 1. 查映射：Yarn ↔ intermediary ↔ official

**永远不要凭记忆写类名/方法名。** 用 `tools/mapquery.cjs`：

```powershell
$node = 'C:\Program Files\nodejs\node.exe'
& $node tools/mapquery.cjs class PathNodeNavigator
& $node tools/mapquery.cjs method PathNodeNavigator findPathToAny
& $node tools/mapquery.cjs intermediary class_13
```

已实测输出（2026-09）：

    PathNodeNavigator -> intermediary net/minecraft/class_13, official efi
    findPathToAny     -> method_54  official (Lbgs;Lefe;Ljava/util/Map;FIF)Lefg;   (Map 版)
                         method_52  official (Lcuc;Lbmn;Ljava/util/Set;FIF)Lefg;   (Set 版)

映射文件位置（可用环境变量 `CAVA_MAPPINGS` 覆盖）：

    C:\Users\<user>\.gradle\caches\fabric-loom\1.20.4\net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\mappings.tiny

**tiny v2 解析的坑**（已在 `docs/CAVA-launch-notes.md` 第 5 节记录，本工具已规避）：
javadoc 行也以 `c` 开头，判别式必须是 `p.length === 4 && p[2].indexOf('class_') > 0`。

## 2. 读原版行为：命名 jar + javap

**不需要反编译器就能拿到"权威行为"**：Loom 缓存里已经有一份 **Yarn 命名**的 Minecraft jar，
里面是重命名后的字节码，`javap -c -p` 出来的控制流顺序就是 parity 的唯一依据。

    C:\Users\<user>\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-common\
        1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\
        minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar

> **勘误（2026-09-22，由 P1-Oracle 实测发现，captain 已复核）**：本文早期写的是
> **`minecraft-clientonly`** jar —— **那是错的**。实测条目数：
>
> | jar | `net/minecraft/*` | `net/minecraft/entity/*` | `entity/ai/pathing/*` |
> | --- | --- | --- | --- |
> | `minecraft-clientonly-1.20.4-…jar` | 2152 | **0** | **0** |
> | `minecraft-common-1.20.4-…jar` | 5907 | **911** | **21** |
>
> `javap -classpath <clientonly> net.minecraft.entity.ai.pathing.PathNodeNavigator` → **找不到类**；
> 换成 `minecraft-common` 立刻能读。
> **服务端相关逻辑（含寻路）在 `minecraft-common` 里**，读原版行为一律用这个 jar。

```powershell
& 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c -classpath <上面的 jar> net.minecraft.entity.ai.pathing.PathNodeNavigator
```

> **注意**：`javap` **不认 `--enable-preview`**（实测报 `未知选项`）。直接 `-p -c` 即可。

## 3. 反编译器（可选，已装但尚未验证在本 jar 上可用）

- 已下载：`tools/decompiler/vineflower-1.11.1.jar`（来自 `repo1.maven.org`，网络走 node fetch）。
- **状态：未验证**。实测到的现象记在 `spike/ffm/decompile-test.cjs`：它必须先接**输入 jar**、
  再接 `-m <mappings>`，再用 `--only=<类>` 选类；把 mappings 当第一个位置参数会被当成输入并静默产出空结果。
- 因此**当前默认工作方式是用第 2 节的 javap**，不要因为反编译没配好而卡住。

## 4. 联网

- **PowerShell 的 `Invoke-WebRequest` 在本机 TLS 全失败**（实测三个 Maven 源全 FAIL）。不要用它测网络。
- 走 node：

```powershell
& 'C:\Program Files\nodejs\node.exe' -e "fetch('https://maven.fabricmc.net/').then(r=>console.log(r.status)).catch(e=>console.log('FAIL',e.message))"
```

- 已实测可达（200）：`maven.fabricmc.net`、`repo1.maven.org`、`services.gradle.org`、`piston-meta.mojang.com`。
- **GitHub / Modrinth 被 DNS 解析到 198.18.x.x 假 IP** → `web_fetch` 不可用；需要 GitHub 上的东西时优先用本机缓存（`.research` / `.probe` / `.cava-research`）。

## 5. JDK 21 预览版 FFM（**实测**）

见 `docs/CAVA-工程接口契约.md` 第 2.5 节的实测表格。最小可运行样例：`spike/ffm/FfmProbe.java`。
一句话版：**没有 `critical`、没有 `Arena.byteSize()`、没有 `allocateFrom`/`setString`（是 `setUtf8String`），
数组必须 `allocateArray`。**

## 6. PowerShell 5.1 的两个实测坑

1. `& $java -Duser.language=en ...` 会被解析坏（实测 `无效的编码: .language=en`）。
   用 `$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Dfile.encoding=UTF-8'`。
2. 中文/英文诊断可能是乱码：脚本开头加 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`。

## 7. 上线加固 / 平台 / 崩溃取证 工具（P4 轮的产出，**每条都实跑过**）

| 工具 | 用途 | 命令 |
| --- | --- | --- |
| `tools/harden-breaker.ps1` | 熔断行为端到端验证（软失败不熔断 / 硬失败第 5 次熔断 / 熔断后不再尝试原生） | `pwsh -File tools/harden-breaker.ps1` |
| `tools/harden-rollback.ps1` | 一键回滚验证（`-Dcava.native.enabled=false` 两腿对比，成功判据机器校验） | `pwsh -File tools/harden-rollback.ps1 [-SkipBuild]` |
| `tools/harden-fuzz.ps1` / `native/tests/fuzz/build-fuzz.ps1` | **已冻结 ABI 的坏输入 fuzz**（越界/NaN/颠倒 AABB/cap 不足/伪造句柄；输出缓冲区顶到 guard page + 0xA5 哨兵） | `pwsh -File native/tests/fuzz/build-fuzz.ps1 -Cases 20000`（`-Dll <路径>` 可换任意产物） |
| `tools/platform-flagcheck.ps1` | **从产物反查编译开关**（flags.make / compile_commands.json / 导入表 / 反汇编里有没有 AVX） | `pwsh -File tools/platform-flagcheck.ps1 -BuildDir build/native-captain -Lib natives/windows-x64/cava.dll` |
| `tools/platform-tagmatrix.ps1` | 5 平台标签推导矩阵（`-RealCrossConfigure` 会真的交叉 configure 成 Linux） | `pwsh -File tools/platform-tagmatrix.ps1` |
| `native/tests/platform/` | **平台数值一致性套件**（逐位 + 编译开关 + ABI 布局；31 项） | `cava_platform_suite.exe --golden native/tests/vectors/fp_probe.txt`（`CAVA_SUITE_LIB` 指定被测库） |
| `tools/hs-err-report.ps1` + `tools/crash-probe.ps1` | **崩溃取证**：真崩出 hs_err → 判定"问题帧在哪个模块"（模块级判定，不是 grep 文本） | `pwsh -File tools/crash-probe.ps1`；单跑 `pwsh -File tools/hs-err-report.ps1 -Log <hs_err>` |
| `tools/CavaArtifactProbe.java` | 交付物指纹（Size/SHA256/build_id/entries/layout_sum） | 见脚本头 |

**本机两个构建坑（P4 实测）**：
1. **多节点 MSBuild 会静默失败**：`cmake --build ... --parallel` 在本沙箱里 exit=1 却**一行 error 都没有**
   （只打 `Checking File Globs / 1>Checking Build System`）。用 `--parallel 1` + `set MSBUILDDISABLENODEREUSE=1`。
   现成脚本：`build/msvc-captain.bat`（configure）+ `build/msvc-captain-build.bat`（build）。
2. **Ninja 生成器卡死过一次**（cmake+ninja CPU 0% 十六分钟，未定位根因）。本机稳妥的两个生成器是
   **MinGW Makefiles** 与 **Visual Studio 17 2022**。

**交付物（windows-x64）**：`natives/windows-x64/cava.dll` = **MSVC /MT** 产物
（257536 B，sha256 `0EBE3B04…`，导入表只有 KERNEL32.dll）。契约 §1.1/§1.5 规定 MSVC 是生产工具链，
MinGW「仅作辅助」。

## 8. 每完成一项就更新这份表

新踩到的坑、新验证可用的命令，**追加到这里**（或对应主题的 `docs/CAVA-*.md`）。
