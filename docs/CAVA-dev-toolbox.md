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

    C:\Users\<user>\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-clientonly\
        1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\
        minecraft-clientonly-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar

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

## 7. 每完成一项就更新这份表

新踩到的坑、新验证可用的命令，**追加到这里**（或对应主题的 `docs/CAVA-*.md`）。
