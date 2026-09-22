# Cava Java 侧实测记录（P0-C）

> 只写**本机实测**与已验证事实；未验证的一律标注「未验证」。
> 采集人：P0-C（FFM facade / 加载校验链 / 数值工具 / 回退语义 / 差分测试运行时骨架）
> 采集时间：2026-09-22 前后。命令全部照抄可复现。

---

## 0. 结论速览

| 项 | 结论 | 证据 |
| --- | --- | --- |
| FFM 包 | **`cava.ffm`**（契约原文 `cava.native.ffm` **非法**：`native` 是 Java 保留字 JLS 3.9） | 见 §1 |
| 加载链路 | 解压→哈希命名→System.load→绑定→ABI→布局自检→open **全链路实测通过** | §3、§5 |
| 布局自检 | Java 侧 (offset,size) 表与 **MinGW g++ 15.2 的 offsetof 完全一致**；`layout_hash_sum(u32)=0x6149fd30` | §4 |
| 数值工具 | 22 个 double 边界值 × (d2i,d2l) + 8 个位模式往返，**原生与 Java 全一致** | §5 step4 |
| 回退语义 | `-Dcava.native.enabled=false` → `DISABLED_BY_FLAG`（INFO，不报 ERROR）；无库 → `RESOURCE_MISSING` 且 exit 0 | §5 |
| 单测 | JUnit 5：**28/28 通过** | §7 |
| MC 侧代码 | **没有编译过**（缺 yarn named MC jar，未跑 Loom）；只做了名字级核对 | §8 |

---

## 1. 包结构：`cava.native.ffm` 为什么改成 `cava.ffm`

`native` 是 Java **保留字**，package 名只能由标识符组成，所以 `package cava.native.ffm;` 无法编译。实测：

```
$ javac -d out src/main/java/cava/native/ffm/NativeStatus.java
src\main\java\cava\native\ffm\NativeStatus.java:1: 错误: 需要<标识符>
package cava.native.ffm;
             ^
（8 个文件同一处报错）
```

最小复现：`echo "package cava.native.ffm; public class P {}" > P.java; javac P.java` → 同上。

**captain 已拍板（广播 #3）：FFM 包 = `cava.ffm`**，最终包结构：
`cava` / `cava.ffm` / `cava.mixin` / `cava.canary` / `cava.subsystem` / `cava.compat` / `cava.mirror` / `cava.fallback` / `cava.parity`。

纪律仍然可 grep 验证：`grep -rl "java.lang.foreign" src/main/java` **只命中 `cava/ffm/`**，且该包不引用任何 MC 类型。

---

## 2. JDK 21 预览版 FFM 的实测坑（本项目实测，全部已在代码里规避）

| # | 事实 | 处置 |
| --- | --- | --- |
| 1 | **没有** `Linker.Option.critical`（JDK 22 才有） | 不使用任何 Linker.Option |
| 2 | 数组必须 `arena.allocateArray(layout, count)`；`arena.allocate(JAVA_INT, 10)` 是「一个 int、值 10」**只有 4 字节** | 代码里只有单实例 `arena.allocate(layout)`；注释写明 |
| 3 | `Arena` **没有** `byteSize()` | 用 `MemoryLayout.byteSize()` |
| 4 | **没有** `Arena.allocateFrom(String)`；**没有** `MemorySegment.setString/getString` | 用 `setUtf8String` / `getUtf8String`（本机实测 `cava_build_id()` 读回正常） |
| 5 | `MemoryLayout` **没有** `memberLayouts()`（在 `GroupLayout`/`StructLayout` 上） | 布局字段声明为 `StructLayout` |
| 6 | ⚠ **`Linker.defaultLookup()` 看不到 `System.load()` 进来的 DLL** | 回退 `SymbolLookup.libraryLookup(path, arena)`，实测有效；见 §3 |

第 6 条是本轮**最重要的新发现**（与任务书里「SymbolLookup 用 linker.defaultLookup()」的假设相反）：

```
[main] INFO cava/native - [native] System.load(...\cava-62e3aa1ae980e144.dll) 成功
[main] INFO cava/native - [native] defaultLookup() 找不到 cava_build_id，回退 SymbolLookup.libraryLookup()
[main] INFO cava/native - [native] 符号表来源: SymbolLookup.libraryLookup(cava-62e3aa1ae980e144.dll)
```

实现（`cava.ffm.CavaBindings.bind`）：先 `defaultLookup()`，找不到主符号就换 `libraryLookup(path, Arena.ofShared())`，
并把这个 arena 的**生命周期保留到进程结束**（提前 close 会让句柄悬空）。
**未验证**：`SymbolLookup.loaderLookup()` 是否也能看到（P1 可测，能省一个 arena）。

---

## 3. 加载校验链（`cava.ffm.NativeLibrary` / `CavaBindings` / `CavaNative`）

顺序：`-Dcava.native.enabled` → 定位资源 → sha256 内容哈希命名 → 临时文件 + 原子改名 → `System.load(绝对路径)` →
符号绑定 → `cava_abi_version()` → `cava_layout_report()` 逐字段比对 → `cava_open`（带 `layout_hash_sum`）。

- 资源路径：`natives/<系统-架构>/cava.dll`（本机 = `natives/windows-x64/cava.dll`）；
  解压到 `<游戏目录>/cava/natives/<mod版本>/<系统-架构>/cava-<sha256前16位>.dll`。
- 已存在同哈希文件（长度 + 哈希都核对）→ 复用；否则写 `<name>.<pid>.tmp` 后 `Files.move(..., ATOMIC_MOVE)`，
  失败（`AtomicMoveNotSupportedException` 或其它 `IOException`，Windows 上被占用也会走到）→ 回退 `REPLACE_EXISTING` 并打日志。
- **只用 `System.load`（绝对路径），不用 `System.loadLibrary`**（不污染 `java.library.path`）。
- 开发用旁路：`-Dcava.native.path=<文件>`（仍走哈希命名 + 原子解压，只是不从 jar 读）；`-Dcava.native.dir=<解压根>`。

实测日志（`-Dcava.native.path=<stub>`）：

```
[native] 原子落盘 ...\cava-6f47835b5db86e72.dll (len=48524, sha256=6f47835b5db86e72...)
[native] System.load(...) 成功
```

状态机（`cava.ffm.NativeStatus`）：
`NOT_TRIED / OPEN / DISABLED_BY_FLAG / RESOURCE_MISSING / EXTRACT_FAILED / LOAD_FAILED / ABI_MISMATCH / LAYOUT_MISMATCH / OPEN_FAILED`。
`tryOpen()` 幂等；**任何 Throwable 都不逃逸**（含 `UnsatisfiedLinkError` / `ExceptionInInitializerError` / 其它 `LinkageError`），
全部转成状态 + 日志。非 OPEN 一律 = 整体回退纯 Java。

---

## 4. 结构体布局与 layout_hash（本机实测数字）

### 4.1 C 编译器（MinGW g++ 15.2.0，x86_64-w64-mingw32）实测 offsetof/sizeof/alignof

命令（一次性测试工程，产物在 gitignored 的 `build/selftest/`）：

```powershell
$env:TMP='J:\mc\Cava\build\selftest\tmp'; $env:TEMP=$env:TMP   # 默认 %TEMP% 不可写，gcc 会报 can't create ...o
& 'C:\mingw64\bin\g++.exe' -O2 -IJ:\mc\Cava\native\include -o offsets_probe.exe offsets_probe.c
.\offsets_probe.exe
```

```
CavaLayoutEntry size=544 align=8
  abi_version      offset=0      size=4
  reserved0        offset=4      size=4
  struct_size      offset=8      size=8
  struct_align     offset=16     size=8
  field_count      offset=24     size=4
  layout_hash      offset=28     size=4
  field_offsets    offset=32     size=256
  field_sizes      offset=288    size=256
CavaLayoutReport size=34848 align=8
  abi_version 0/4, build_flags 4/4, platform 8/4, pointer_size 12/4, entry_count 16/4, reserved0 20/4,
  build_id_hash 24/8, entries 32/34816
CavaOpenParams size=32 align=8     : abi_version 0/4, flags 4/4, layout_hash_sum 8/8, reserved0 16/8, reserved1 24/8
CavaOpenResult size=24 align=8     : status 0/4, abi_version 4/4, native_layout_sum 8/8, reserved0 16/8
```

Java 侧（`CavaLayouts` 用手写 `MemoryLayout` + `byteOffset(PathElement.groupElement(...))` 算出来的偏移）
与上表**逐字段一致**，并由 `CavaLayouts.checkAgainstCAbi()` 在 `tryOpen()` 里每次启动断言一遍。

### 4.2 布局哈希（契约 2.3 的逐 u32 FNV-1a，32 位回绕）

| 结构体 | hash (u32 变体) | hash (逐字节变体) |
| --- | --- | --- |
| `CavaLayoutEntry` | `0xF837804D` | `0x79E51922` |
| `CavaLayoutReport` | `0xE9FFC021` | `0x3232B389` |
| `CavaOpenParams` | `0x7FDE7499` | `0x50534569` |
| `CavaOpenResult` | `0xFF344829` | `0xDEBEF5D9` |
| **`layout_hash_sum`** | **`0x6149FD30`** (1632238896) | `0xDB2A07ED` (3676964845) |

**⚠ 契约内部有歧义（需要 native 侧对齐）**：
- 契约 §2.3 写的是「每个字段喂 4 个 u32：offset 低 32、size 低 32、offset 高 32、size 高 32」→ `0x6149FD30`；
- `cava_abi.h` 的注释写的是「对每个字段依次喂入 (offset:u32, size:u32) 的**小端字节**」→ 逐字节 FNV → `0xDB2A07ED`。

两者结果不同。**Java 侧两个都算**：
1. 先按契约 §2.3 的 u32 变体算，与原生 `cava_layout_report()` 里每个 entry 的 `layout_hash` 比对；
2. 若每个 entry 都等于**逐字节变体**，则判定原生用的是变体 2，自动改用 `0xDB2A07ED` 去 `cava_open`（并在日志打 WARN「协商」）；
3. 若 `cava_open` 仍返回 `CAVA_ERR_LAYOUT`，再用另一个变体重试一次。

字段 (offset,size) 是**逐个比对**的，与哈希变体无关，所以这个协商不削弱安全性。
**建议 native 侧 / captain 二选一并把另一处文档改掉**（我这边两种都已实现，切换零成本）。
另外：**布局报告里数组字段算「一个字段」**（`field_offsets[32]`/`field_sizes[32]` 各 1 个；`entries[64]` 1 个），
否则 `CavaLayoutReport` 的字段数会超过 `CAVA_LAYOUT_MAX_FIELDS=32`。

---

## 5. NativeSelfTest 实测输出（任务 C7）

一次性测试桩（**不是**项目的原生实现；真正的库归 p0-native 流）建在 gitignored 的 `build/selftest/native/`：

```powershell
$env:TMP='J:\mc\Cava\build\selftest\tmp'
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dcava.native.path=J:\mc\Cava\build\selftest\native\cava.dll -Dcava.native.dir=J:\mc\Cava\build\selftest\run2\cava\natives'
& 'C:\Program Files\Java\jdk-21\bin\java.exe' --enable-preview --enable-native-access=ALL-UNNAMED `
    -cp 'J:\mc\Cava\build\selftest\classes;<slf4j-api>;<slf4j-simple>' cava.ffm.NativeSelfTest
```

四条路径**全部实跑**：

| 场景 | 命令差异 | 结果 |
| --- | --- | --- |
| ① 原生可用（u32 变体） | 上面的命令 | `status=OPEN`，`handle=4097`，`java_sum=native_sum=0x6149fd30`，**SELF-TEST: PASS**，exit 0 |
| ② 原生用逐字节变体 | `-Dcava.native.path=...\cava-bytes.dll` | 协商生效：`sent_sum=0xdb2a07ed rc=CAVA_OK`，`layout 变体=byte-wise（协商）`，**PASS**，exit 0 |
| ③ 库不存在 | 不设 `cava.native.path` | `status=RESOURCE_MISSING`，打印 `LOAD_FAILED path: 已走到`，Numeric 走纯 Java，**exit 0（不是崩溃）** |
| ④ 显式关闭 | 参数 `disabled` | `status=DISABLED_BY_FLAG`，**INFO 级日志（不是 ERROR）**，exit 0 |

关键行（场景 ①）：

```
--- [1] 原生符号 ---
cava_build_id()   : cava 0.1.0 win-x64 stub-p0c O2/fwrapv/ffp-contract=off
cava_abi_version(): 1
cava_abi_touch()  : 2  (再次调用: 3)
--- [3] cava_open ---
status            : OPEN
handle            : 4097
java_layout_sum   : 0x6149fd30  (bytes 变体 0xdb2a07ed)
native_layout_sum : 0x6149fd30
layout 变体       : u32（契约 2.3）
--- [4] ... 逐点差分 ---
  2.147483648E9   d2i native=2147483647   java=2147483647   ok | d2l native=2147483648  java=2147483648  ok
  -1.0E300        d2i native=-2147483648  java=-2147483648  ok | d2l native=-9223372036854775808 java=... ok
  NaN             d2i native=0            java=0            ok | d2l native=0           java=0           ok
  bits(-0.0)      native=0x8000000000000000 java=0x8000000000000000 roundtrip=-0.0 ok
SELF-TEST: PASS
```

覆盖的 double 边界：`0, -0.0, ±1, ±0.5, ±1.9, 2147483647/48, -2147483648/49, ±1e300, ±MAX_VALUE, ±∞, NaN,
±9.223372036854776E18, ±1e19`（共 22 个）+ 位模式探针 `0, -0.0, 1, -1.5, π, NaN, ∞, MIN_VALUE`。
**这张表就是原生 `cava_d2i_sat/cava_d2l_sat` 必须复刻的语义**（NaN→0、越界饱和、向零取整）。

编译自检类不需要 Gradle：
```powershell
& 'C:\Program Files\Java\jdk-21\bin\javac.exe' --release 21 --enable-preview -nowarn -cp <slf4j-api> -d <out> <cava/ffm/*.java>
```

---

## 6. 差分测试骨架（`cava.parity`，运行时侧）

- `GoldenTrace`：NDJSON 写入器，头行 + 逐 tick 行，字段严格按契约 4.2（缺省写 `null`，**键不省**）；
  行尾 `\n`、UTF-8、每 100 行 flush。文件名 `<dir>/trace-<label>.ndjson`（label 做了文件名 sanitize）。
- `TickSampler`：注册 `ServerTickEvents.END_SERVER_TICK`；`-Dcava.parity.trace=<dir>` 才开；
  `-Dcava.parity.ticks=N` 采满自动 `server.stop(false)`；`-Dcava.parity.label` 写进头行。
- **P0 只实现 `w`**；`e / p / bt / nt / x` 一律 `null`（键都在）。

### 6.1 世界哈希的两个已定决策（都要评审）

1. **用 state id 做键，禁止对象身份**：`Block.getRawIdFromState(state)`（intermediary `method_9507`，本机映射表核实）。
   理由：FerriteCore 会把内容相同的 BlockState 去重成同一实例，identity 做键会在装了 FerriteCore 的整合包里产生假差异。
2. **扫描盒，而不是「所有已加载区块」**：
   本机映射表实测 —— 1.20.4 yarn **没有公开的「枚举已加载区块」API**：
   `ServerChunkManager.threadedAnvilChunkStorage`（`field_17254`）与 `ThreadedAnvilChunkStorage.loadedChunks`（`field_18307`）
   都是私有字段，P0 禁止 mixin。且「已加载集合」本身会随加载时机抖动 → 两次运行会产生**假差异**。
   因此 P0 用固定扫描盒：以 (0,0) 为中心、半径 `-Dcava.parity.world.radius`（默认 8）个区块的方阵 × 三个维度，
   逐区块 `world.getChunkManager().getWorldChunk(cx, cz)`（`method_21730`，未加载返回 null，**不会加载区块**）。
   迭代顺序 = (dim, chunkX, chunkZ, sectionIndex, x, y, z) = 契约要求的 (dim,x,y,z) 字典序。
3. **空段（`ChunkSection.isEmpty()`）不贡献字节**：两次运行的空段集合一致，跳过不改变可比性，但让成本从
   「区块数 × 24 段 × 4096」降到「实际非空方块数」。**这是与契约字面定义的一处偏差，已在此备案。**
   性能：未在真实世界测过（**未验证**）。采集每 200 tick 打一次「方块数 / ms per tick」实测日志。

---

## 7. 单元测试（真实运行结果）

JUnit 5（`junit-platform-console-standalone 1.10.2`，直接 `java -jar` 跑，不依赖 Gradle）：

```powershell
& 'C:\Program Files\Java\jdk-21\bin\java.exe' --enable-preview -jar build\selftest\lib\junit-console.jar execute `
    --class-path "<classes>;<test-classes>;<slf4j>" --select-package cava --details=summary
```

```
[         9 containers successful ]
[        28 tests found           ]
[        28 tests successful      ]
[         0 tests failed          ]
```

覆盖：FNV-1a 64 标准向量（`""/a/b/c/foobar`）、hex16 定宽、`-0.0` 位模式、
NDJSON 往返（头 + tick + null 键齐全 + label sanitize + JSON 转义）、
TraceDiff 零差异 / 首个差异 tick 与字段 / 行数不同 / 头行不同、
布局哈希常量与 C 编译器 offsetof 回归、config 读写与容错、金丝雀两条路径。
金丝雀自检实测日志（`SubsystemRegistry.canarySelfTest()`）：

```
[cava] 子系统 selftest-good 金丝雀通过（0 -> 1）
[cava] 子系统 selftest-bad 的钩子金丝雀**没有动**（0 -> 0），已禁用并回退纯 Java   ← ERROR 级
[cava] 金丝雀框架自检结果: PASS（good.enabled=true good.count=1 bad.enabled=false bad.reason="计数未变（钩子没生效）"）
```

---

## 8. 未验证清单（**不要当成结论用**）

1. **MC 侧代码没有编译过**：`cava.Cava` / `cava.parity.TickSampler` / `cava.client.CavaClient` 需要 yarn named 的 MC jar，
   本机没有（Loom 未成功跑过，缓存里只有 official/intermediary jar）。已做的替代核对：
   - 全部 import 用脚本核对过 —— `net.minecraft.*` 12 个类在 yarn 1.20.4+build.3 映射表里都存在；
     `net.fabricmc.*` 7 个类在 fabric-loader/ fabric-api jar 里都存在（`imports ok=14 missing=0`）。
   - 用到的方法名逐个查过本机 `mappings.tiny`（附 intermediary）：`MinecraftServer.getWorlds → method_3738`、
     `getTicks → method_3780`、`stop → method_3747`、`getSaveProperties → method_27728`、
     `SaveProperties.getGeneratorOptions → method_28057`、`GeneratorOptions.getSeed → method_28028`、
     `ServerWorld.getChunkManager → method_14178`、`World.getRegistryKey → method_27983`、
     `RegistryKey.getValue → method_29177`、`ChunkManager.getWorldChunk(II) → method_21730`、
     `Chunk.getSectionArray → method_12006`、`ChunkSection.getBlockState → method_12254`、
     `ChunkSection.isEmpty → method_38292`、`Block.getRawIdFromState → method_9507`、
     `HeightLimitView.getBottomSectionCoord → method_32891`。
   - Fabric 事件名用 javap 核实：`ServerTickEvents.END_SERVER_TICK` + `EndTick.onEndTick(MinecraftServer)`、
     `ServerLifecycleEvents.SERVER_STARTED/SERVER_STOPPING`。
   - **补充（语法/类型自检）**：按上表手写了 24 个 MC/Fabric 类型的**一次性桩**（放 gitignored 的
     `build/selftest/stubs/`），把 `Cava.java` / `TickSampler.java` / `CavaClient.java` 对着桩编译：
     ```
     stub javac exit=0
     mc-side javac exit=0   （产出 3 个 class）
     ```
     这只证明**代码自身的语法与类型使用自洽**（含 `Iterable<ServerWorld>` 迭代、`ChunkSection[]`、
     泛型 `RegistryKey<World>`、lambda 与事件接口签名），**不证明与真实 MC API 匹配** —— 桩的签名是我按
     映射表转录的，真实 API 的对编仍然要做（等 P0-A 的 Gradle/named jar）。
2. `ChunkManager.getWorldChunk(int,int)` **不会加载区块**：按 yarn 名与 2/3 参重载推断，**未在运行期验证**。
3. `server.stop(false)` 的 `false`：vanilla 里这个参数是 `waitForServer`，**不是「是否保存」**。
   确定性还需要 `/save-off` 或停服前不落盘（P0-F 的测试服脚本要覆盖）。P0 任务书写的是「false = 不保存」，**存疑**。
4. 世界哈希的性能/正确性：从未在真实服务端跑过（只跑过单测）。
5. 黄金轨迹端到端（同存档两次运行 → `ZERO DIFF`）：**未做**，需要 P0-F 的真实服务端。
6. `Arena`/`libraryLookup` 的 arena 泄漏：按设计保留到进程结束，**未做压力验证**（每进程只 open 一次，风险低）。
7. 布局哈希变体二选一：**待 captain/native 侧拍板**（§4.2）。

---

## 9. 给 captain 的请求（需要别人文件的改动）

1. `build.gradle`（P0-A）加测试依赖与平台：
   ```gradle
   dependencies { testImplementation "org.junit.jupiter:junit-jupiter:5.10.2" }
   test { useJUnitPlatform() }
   ```
2. （可选，契约 §4.3 提到）`parityDiff` 任务形态由 P0-C 实现，但入口在 `build.gradle`。
   建议：``gradle
   tasks.register('parityDiff', JavaExec) {
       dependsOn testClasses
       classpath = sourceSets.test.runtimeClasspath
       mainClass = 'cava.parity.TraceDiff'
       args = [project.findProperty('cava.traceA'), project.findProperty('cava.traceB')]
   }
   ```
   （P0-C 只保证 `TraceDiff.main` 可用；任务怎么定义听 P0-A/captain 的。）
3. 确认 §4.2 的布局哈希变体（u32 vs 逐字节），并让 native 侧按同一个实现，然后我删掉协商代码。
4. 确认 §6.1 的「固定扫描盒 + 空段跳过」是否可作为 P0/P1 的世界哈希定义写进契约 §4.2。
