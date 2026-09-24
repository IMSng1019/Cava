# Cava 崩溃取证（hs_err 解析 + 归属判定 + 回滚）

> **状态：已实跑。** 本文件里每一个数字都来自本机某一次真实运行的输出，命令与关键行附在 §6。
> 相关文件：`tools/crash-probe.ps1`（跑出真崩溃）、`tools/hs-err-report.ps1`（解析与判定）、
> `tools/CrashProbe.java` / `tools/CavaArtifactProbe.java`、`native/tests/crashprobe/`。
> `docs/CAVA-hardening-notes.md` §7 第 2 条（“崩溃取证本轮未做”）由此闭合。

---

## 1. 为什么要有这一节：hs_err 里最容易判错的一件事

**问题帧（Problematic frame）在哪个模块里，决定这是不是 Cava 的 bug。**
不是“日志里出现了 `cava_` 字样”——本项目的崩溃探针**故意**导出了一个名字里带 cava 的符号
（`cava_crash_probe_null_deref`），文本匹配的解析器会把它误判成 Cava 的问题。
判定必须先把帧里的模块名解析到 `Dynamic libraries:` 表里的**真实文件路径**，再比路径/名字。

本项目的判据（`tools/hs-err-report.ps1` 里就是这三条）：

| 问题帧所在模块 | 判定 | 退出码 | 含义 |
| --- | --- | --- | --- |
| `cava.dll`（即 `natives/windows-x64/cava.dll`） | `CAVA_NATIVE_FAULT` | 0 | **是 Cava 的问题**：收集产物 + 日志，按 §5 回滚 |
| `crashprobe*.dll` | `NOT_CAVA_PROBE` | 1 | 是**故意写坏的测试探针**，不是 Cava |
| 其它任何模块（`othermod.dll` / `jvm.dll` / 显卡驱动 …） | `NOT_CAVA` | 1 | 不是 Cava；把日志转给对应模块的维护者 |
| 日志本身不可解析 | `PARSE_FAILED` | **2** | **不给出任何判定**（见 §4） |

> **`PARSE_FAILED` 必须是 2 而不是静默的“没发现问题”。** 一份截断的日志如果被当成
> “帧不在 cava.dll 里 ⇒ 不是 Cava”，那是最危险的假阴性：真实的 Cava 崩溃日志只要传输被截断，
> 就会被判成“不是我们”。脚本因此在缺少 `Dynamic libraries:` 区块时直接拒绝出结论。

---

## 2. 一条真实可复现的取证链

```
native/tests/crashprobe/crashprobe.c        导出 cava_crash_probe_null_deref()：往地址 0 写
        │                                   （**故意写坏，绝不编进 cava.dll**）
        ├─ build-crashprobe.ps1 用 g++ -shared 编成 build/p4c-crash/crashprobe.dll
        │  再复制成 crashprobe_null2.dll / othermod.dll / cava.dll（仅文件名不同，字节相同）
        ▼
tools/CrashProbe.java   用 java.lang.foreign downcall 调它（JVM 参数带
                        -XX:ErrorFile=build/p4c-hs-err/hs_err_pid%p.log -XX:-CreateCoredumpOnCrash）
        ▼
JVM 硬崩（EXCEPTION_ACCESS_VIOLATION），HotSpot 写出 build/p4c-hs-err/hs_err_pid<PID>_<label>.log
        ▼
tools/hs-err-report.ps1 解析 → 模块解析 → 指纹核对 → 判定 + 退出码
```

三种输入**必然给出不同判定**，这是“能红的测试才是测试”在这条链上的形式（`tools/crash-probe.ps1` 自动断言）：

| 输入 | 问题帧（实测原文） | 判定 | 退出码 |
| --- | --- | --- | --- |
| A `crashprobe.dll` | `# C  [crashprobe.dll+0x1554]` | `NOT_CAVA_PROBE` | 1 |
| B `othermod.dll` | `# C  [othermod.dll+0x1554]` | `NOT_CAVA` | 1 |
| C `crashprobe_cava_proxy.dll`（转发进一个名叫 `cava.dll` 的模块） | `# C  [cava.dll+0x1554]` | `CAVA_NATIVE_FAULT` | **0** |
| D `native/tests/vectors/hs_err_truncated_fixture.log`（**手工造**） | 帧可读，但缺模块表 | `PARSE_FAILED` | **2** |

C 这一路是**唯一**能验证“正判定分支”的办法：故意写坏的代码不能编进 `cava.dll`（会污染交付物），
所以让一个代理 DLL 先把**同名副本**（`build/p4c-crash/cava.dll`，永远不进 `natives/`）
加载进来、再调用它的导出函数。于是崩溃日志长得与真实的 Cava 原生崩溃**完全同形**，
解析器的正向分支是被真日志测出来的，不是被 fixture 测出来的。

---

## 3. 报告里读什么（逐项含义）

`pwsh -NoProfile -File tools/hs-err-report.ps1 -Log <hs_err>` 的输出分段：

| 段 | 关键行 | 怎么读 |
| --- | --- | --- |
| input | `log` / `size/bytes` | 先确认拿到的是**完整**文件（截断的日志大小会明显偏小） |
| header | `signal` | `EXCEPTION_ACCESS_VIOLATION (0xc0000005)` = 野指针/越界；`SIGABRT` 是断言或 std::terminate |
| header | `jre` / `vm` | 必须与部署一致：本项目锁 **JDK 21.0.x + `--enable-preview`**，别的版本直接换掉再复现 |
| header | `java-cmd` / `jvm-args` / `classpath` | 复现命令的**全部**信息（尤其 `-Dcava.native.*` 开关与 mod 版本） |
| problem-frame | `raw` / `module` / `offset` / `module-path` | **判定只看这一段**；`module-path` 是从 `Dynamic libraries:` 表反查出来的真实文件 |
| cava | `in-stack` | 栈里有没有 cava.dll 的帧（**不是**判定依据，但是重要线索） |
| cava | `symbols-matching-cava_` | 日志里提到 `cava_` 的行数。**文本匹配会骗人**，脚本只用它来提醒，不用它来判定 |
| cava | `module-in-dump` / `address-in-cava` | cava.dll 的装载区间，以及在 dump 的寄存器/栈里找到的第一个落在该区间的地址 |
| cava | `on-disk-sha256` | 对日志里那个路径**当前**的文件算的哈希（见下面的警告） |
| cava | `build-id` / `layout` | 直接 `System.load` 该文件、调 `cava_build_id` 与 `cava_layout_report` 得到；实测 `14 entries, sum=0x1C12265E` |
| verdict | `verdict` / `reason` / `exit` | 判定与退出码 |

> **两个必须写在明面上的不确定性**
> 1. `on-disk-sha256` 是“现在的文件”的哈希，**不是崩溃那一刻那个文件的哈希**。
>    共享产物目录 `natives/windows-x64/` 会被别的构建流覆盖 —— 本文件写作期间它就变过两次
>    （`D751A3D1…` 2855079 B g++ → `0EBE3B04…` 257536 B MSVC；见 §6）。
>    所以**取证第一步是立刻把崩溃机器上那份 `cava.dll` 连同日志一起拷走**，别在原地算哈希。
> 2. 崩溃行里的 `pc=0x…` 是**运行期地址**，与 dump 时打印的模块区间不在同一坐标系，
>    不能拿它去和 `cava.dll` 的区间比大小。判定只用 `模块名+偏移`，偏移才是有意义的锚点
>    （配合 build_id 才能定位到具体指令）。

---

## 4. 可证伪性：这份解析器凭什么算“测过”

1. **三种输入判定互不相同**（A/B/C 三条 + D），由 `tools/crash-probe.ps1` 写成断言，
   实测 `CRASH-PROBE: PASS`（13/13 条 ok）。
2. **A 与 B 是两次独立的真崩溃**（不同 pid、不同日志），不是同一份文件改了名字：
   `hs_err_pid46892_probe.log` / `hs_err_pid40616_othermod.log`。
3. **D 在损坏输入上非零退出**（exit 2）并打印**拒绝理由**（缺 `Dynamic libraries:` 区块）。
4. **反例的构造方式写在 fixture 文件头上**（“HAND-MADE，由哪一次真实日志截断得到”），
   并且 `native/tests/vectors/README.md` 单独说明——**手工造的东西必须自称手工造**。

---

## 5. 上线后真崩了怎么取证（照做即可）

**第 1 步 · 冻结现场（先拷贝，后分析）**
收集这四样，**在任何人重启服务器之前**：
1. `hs_err_pid<PID>.log`（JVM 参数里的 `-XX:ErrorFile` 指到哪就在哪；默认是服务端工作目录）；
2. 崩溃那一刻的 `cava.dll`（从 mod jar 解出来的那份，**不要重新构建**）+ 它的 sha256；
3. `logs/latest.log`（含 `[cava/native]` 的熔断/看门狗/回退计数与 mod 列表横幅）；
4. 启动命令（`java -XX:…` 全量，尤其 `-Dcava.native.*`）与 `config/cava.json`。

**第 2 步 · 判定归属（一条命令）**
`pwsh -NoProfile -File tools/hs-err-report.ps1 -Log <hs_err> -CavaDll <冻结的那份 dll>`
- 退出码 0 / `CAVA_NATIVE_FAULT` ⇒ 进第 3 步；
- 退出码 1 / `NOT_CAVA*` ⇒ **不是 Cava**，把日志转给 `module-path` 那个模块的维护者，本流程结束；
- 退出码 2 / `PARSE_FAILED` ⇒ 日志不可用：**回到第 1 步重新收集**，不要凭一半的日志下结论。

**第 3 步 · 核对版本指纹（防止“拿错 dll 复现”）**
报告里的 `build-id` 与 `layout` 必须与线上一致：`layout` 应当是
`14 entries, sum=0x1C12265E`（ABI 已冻结）；不一致说明二进制被换过，先对齐产物再分析。
`address-in-cava` 给出 dump 里落在 cava.dll 区间的地址（有 ⇒ 栈上确实有 Cava 的帧）。

**第 4 步 · 先恢复服务，再定位根因（可用一个 JVM 参数）**
停服 → 启动命令加 `-Dcava.native.enabled=false`（或 `config/cava.json` 里
`native.enabled=false`）→ 起服。成功判据（全部满足）：
- 日志有 `[cava/native] -Dcava.native.enabled=false…这是正常路径，走纯 Java`；
- **没有** `[native] System.load(` 行（库根本不解压，所以崩溃源被物理移除）；
- **没有任何** `[cava/native]` ERROR 行；
- 机器校验：`pwsh -NoProfile -File tools/harden-rollback.ps1 -SkipBuild` ⇒ `ROLLBACK CHECK: PASS`。

**第 5 步 · 复现与定位**
用第 1 步冻结的 `cava.dll` + 同样的 JDK + 同样的 `-Dcava.native.*` 开关复现；
把 `cava.dll+0x<offset>` 交给 captain（**只有 captain 能动 `native/include/cava_abi.h` 与
`native/src/cava_layout.cpp`**）。若是 SAFE 构建，`stderr` 里会多一行
`[cava][SAFE] assertion #N failed: … -> code=`——那是**已记录的断言**，比裸崩溃好查一个数量级。

**第 6 步 · 结论归档**
把日志、`cava.dll` 的 sha256、判定结论、复现命令追加到本文件 §6；
如果判定是 `NOT_CAVA`，也**照样归档**——“排除了 Cava” 本身就是结论。

---

## 6. 实跑证据（原文关键行）

### 6.1 一条命令跑完三种输入

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File tools/crash-probe.ps1
```

    真实 crashprobe 崩溃（JVM 硬崩 + hs_err）：
    #  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffbc20b1554, pid=46892, tid=41748
    #  JRE version: Java(TM) SE Runtime Environment (21.0.10+8) (build 21.0.10+8-LTS-217)
    # Problematic frame:
    # C  [crashprobe.dll+0x1554]
    # CreateCoredumpOnCrash turned off, no core file dumped
    # An error report file with more information is saved as:
    # J:\mc\Cava\build\p4c-hs-err\hs_err_pid46892_probe.log

    三次崩溃各自留下的日志（真实，非 fixture）：
    RUN probe      log=hs_err_pid46892_probe.log    (60035 B)  frame: # C  [crashprobe.dll+0x1554]
    RUN othermod   log=hs_err_pid40616_othermod.log (59620 B)  frame: # C  [othermod.dll+0x1554]
    RUN cavaframe  log=hs_err_pid45644_cavaframe.log(57796 B)  frame: # C  [cava.dll+0x1554]

    ================= assertions =================
      ok   : run A exited non-zero (the JVM really died): 1
      ok   : run A did not hit the 60 s timeout
      ok   : run A produced an hs_err log
      ok   : run B exited non-zero: 1
      ok   : run B produced an hs_err log
      ok   : run C exited non-zero: 1
      ok   : run C produced an hs_err log
      ok   : A verdict NOT_CAVA_PROBE (actual NOT_CAVA_PROBE), exit=1
      ok   : B verdict NOT_CAVA (actual NOT_CAVA), exit=1
      ok   : C verdict CAVA_NATIVE_FAULT (actual CAVA_NATIVE_FAULT), exit=0
      ok   : D verdict PARSE_FAILED (actual PARSE_FAILED), exit=2
      ok   : D exited with code 2 (non-zero for unusable input), actual 2
      ok   : the four verdicts are all DIFFERENT: NOT_CAVA_PROBE, NOT_CAVA, CAVA_NATIVE_FAULT, PARSE_FAILED

    CRASH-PROBE: PASS

### 6.2 正判定分支的 JSON（C 腿）

```json
{"log":"…\\hs_err_pid45644_cavaframe.log","signal":"EXCEPTION_ACCESS_VIOLATION",
 "frame":"C  [cava.dll+0x1554]","module":"cava.dll","cavaInStack":true,
 "cavaBuildId":"cava 0.1.0 …","cavaLayoutSum":"0x1C12265E","cavaSha256":"0EBE3B04…",
 "verdict":"CAVA_NATIVE_FAULT",
 "reason":"problem frame resolves to cava.dll -- Cava native fault confirmed by the module table"}
```

### 6.3 WER / 挂起：观察到了什么（任务书点名要如实记录）

- **没有出现 WER 弹窗，也没有挂起。** 三次崩溃的墙钟分别是 **214 ms / 202 ms / 200 ms**，
  `timedOut=False`（脚本对每次运行 `WaitForExit(60 s)`，超时就 kill 并记 `TIMED OUT`）。
- 依据：加了 `-XX:-CreateCoredumpOnCrash`（日志里可见
  `# CreateCoredumpOnCrash turned off, no core file dumped`），
  并且**从子进程分离的 `Start-Process` 换成了 `System.Diagnostics.Process`**（原因见下）。
- 一处**真实踩到的坑**：`Start-Process -PassThru` 在原生硬崩后拿到 `ExitCode` 不稳定
  （第一次实测打印出 `(unavailable -- killed?)`）。换成 `System.Diagnostics.Process` +
  `ReadToEndAsync` 后稳定拿到非零退出码。**这不是崩溃本身的问题，是取证脚本的问题**，
  但如果没换，报告里“进程真的死了吗”这一条就成了无法验证的断言。
- `exit code` 实测是 **1** 而不是 `0xC0000005`：JVM 捕获访问违例、走自己的错误处理后再退出，
  Windows 层面留下的是 1。**所以“是否硬崩”要看 hs_err 日志与 stdout 的
  `A fatal error has been detected` 横幅，不能只看退出码。**

### 6.4 一个必须上报的产物变动（共享目录又被覆盖了）

本文件写作期间 `natives/windows-x64/cava.dll` **变了两次**（不是本流改的，本流只读它）：

    2026-09-24 13:41  D751A3D100110D6ABA9F518E64876C900A764D7C94C57380F44317E908695DF3  2855079 B
                      build_id "cava 0.1.0 windows-x64 GNU 15.2.0 (C:/mingw64/bin/g++.exe) …"
    2026-09-24 14:0x  5C4E777857D50DF8A98C366304E6175B6267D25979A7E56500A38FEF8810402A  (g++ 配方)
    2026-09-24 14:2x  0EBE3B04A869819D7A59C8ADC11B0D1277B120B80F956A7C05D2EB62D007904D  257536 B
                      build_id "cava 0.1.0 windows-x64 MSVC 19.44.35228.0 (MSVC toolset v143) /O2 /fp:strict …"

三份**都报 `14 entries / layout_sum=0x1C12265E`**，所以这不是 ABI 漂移；
但这说明 `natives/windows-x64/` 仍然在被多个流写入（`docs/CAVA-gates.md` §6 警告过）。
**结论：任何“当前交付物是 X”的说法都必须带 sha256 + 时间戳**，否则下一小时就不成立了。

---

## 7. 未验证 / 做不到

1. **真机整合包下的崩溃**：本文件的崩溃全部由故意写坏的探针产生。**没有**在真实服务端上让 Cava 自己崩过
   （那需要先有一处真实缺陷）。所以“解析器对真实 Cava 崩溃的处理”是靠 §2 的**同形替换**验证的，
   不是靠一次真实的 Cava 缺陷验证的。
2. **其它平台的 hs_err 形状**：只在 **windows-x64** 上跑过。Linux 的头行是 `SIGSEGV (0xb)`、
   模块段是 `/path/libcava.so`，脚本的解析规则是按通用形状写的，但**未实测**。
3. **`libcava.so` 的模块段解析**：`Resolve-Module` 依赖 `[name+0xoffset]` 这个形状；
   Linux 上的帧可能带 `(libcava.so+0x1234)` 括号写法 —— **未验证**。
4. **`cava.dll+0x<offset>` 到源码行的映射**：需要 `addr2line` 或 PDB，
   MinGW 产物带 DWARF、CMake/MSVC 产物带 PDB，脚本**没有**做这一步（归 captain，因为要碰构建系统）。
