# Cava 原生核心笔记（P0-B · 实测记录）

> 所有权：P0-B（原生核心流）。本文只写**本机真跑出来的**东西；没跑过的一律标「未验证」。
> 环境：Windows x64，MinGW-W64 GCC 15.2.0（`C:\mingw64`，x86_64-msvcrt-posix-seh），
> JDK 21.0.10（`C:\Program Files\Java\jdk-21`），CMake 3.31.2（便携版）。
> 全套重跑命令：`pwsh -File native/tests/build-mingw.ps1`

---

## 0. Java 侧直接要抄的数字（x64 / ABI v1）

| 结构体 | struct_size | align | field_count | layout_hash |
| --- | --- | --- | --- | --- |
| `CavaLayoutEntry`  | 544   | 8 | 8 | **0xf837804d** |
| `CavaLayoutReport` | 34848 | 8 | 8 | **0xe9ffc021** |
| `CavaOpenParams`   | 32    | 8 | 5 | **0x7fde7499** |
| `CavaOpenResult`   | 24    | 8 | 4 | **0xff344829** |

### layout_hash_sum = **0x6149FD30** = **1632238896**  ← Java 侧填进 `CavaOpenParams.layout_hash_sum`

（uint32 回绕加法：0xf837804d + 0xe9ffc021 + 0x7fde7499 + 0xff344829 = 0x16149fd30 → 取低 32 位）

**Java 侧不要硬编码这几个数字就完事**：应当自己按下面的公式算出期望值再填进去 ——
硬编码会让「Java 结构体写错但恰好没被测到」这种错误无法被布局自检发现。
硬编码只适合当单元测试的期望值（黄金参考见 `native/tests/vectors/layout_expected.txt`）。

逐字段 offset/size 全表（Java 侧可当断言用，也从 `--dump-layout` 直接生成）：

~~~
CavaLayoutEntry  size=544 align=8 fields=8
  abi_version 0/4    reserved0 4/4      struct_size 8/8     struct_align 16/8
  field_count 24/4   layout_hash 28/4  field_offsets 32/256  field_sizes 288/256
CavaLayoutReport size=34848 align=8 fields=8
  abi_version 0/4    build_flags 4/4   platform 8/4        pointer_size 12/4
  entry_count 16/4   reserved0 20/4    build_id_hash 24/8  entries 32/34816
CavaOpenParams   size=32 align=8 fields=5
  abi_version 0/4    flags 4/4         layout_hash_sum 8/8 reserved0 16/8  reserved1 24/8
CavaOpenResult   size=24 align=8 fields=4
  status 0/4         abi_version 4/4   native_layout_sum 8/8  reserved0 16/8
~~~

> MSVC 下这几个数字是否一致：**未验证**（结构体全是 8 字节对齐的标量 + 数组，
> 按 MSVC x64 的布局规则应当一致，但没实测过）。

---

## 1. 布局哈希的两条裁定（**Java 侧必须一模一样**）

契约 §2.3 的伪代码有两个地方会让人写出不同的实现，这里钉死：

1. **数组字段算一个字段**：`field_offsets[32]` 只喂一次，offset = 数组起点，size = 整个数组字节数（256）。
   理由：`CAVA_LAYOUT_MAX_FIELDS` 是 32，而 `CavaLayoutEntry` 自己有 6 个标量 + 2 个 32 元素数组
   （展开成 70 个字段就装不下了）。
2. **每个字段走满 4 步**，32 位字段高位补 0 也要走（别自作聪明省两步）：

~~~
h = 0x811C9DC5
for each field in declaration order:
    h ^= (uint32)(offset & 0xFFFFFFFF); h *= 0x01000193
    h ^= (uint32)(size   & 0xFFFFFFFF); h *= 0x01000193
    h ^= (uint32)((offset >> 32) & 0xFFFFFFFF); h *= 0x01000193
    h ^= (uint32)((size   >> 32) & 0xFFFFFFFF); h *= 0x01000193
~~~

**entry 顺序** = 头文件里的声明顺序：`CavaLayoutEntry` → `CavaLayoutReport` → `CavaOpenParams` → `CavaOpenResult`
（求和可交换，但逐条比对要按这个顺序）。

Java 参考实现（可直接抄）：

~~~java
static int fnv(int h, int v) { h ^= v; return h * 0x01000193; }
static int structHash(long[] off, long[] size) {
    int h = 0x811C9DC5;
    for (int i = 0; i < off.length; i++) {
        h = fnv(h, (int) off[i]);            h = fnv(h, (int) size[i]);
        h = fnv(h, (int) (off[i] >>> 32));   h = fnv(h, (int) (size[i] >>> 32));
    }
    return h;   // Java 的 int 乘法天然是 32 位回绕
}
~~~

`build_id_hash` 是**另一个**哈希：对 `cava_build_id()` 的字符串按逐字节 32 位 FNV-1a 算；
本机 Release 构建实测 `0x982192f1`（build_id 串见 `vectors/layout_expected.txt`，换构建参数就会变，只用于诊断）。

---

## 2. 实测的编译命令

### 2.1 自测路径（P0-B，不依赖 CMake）

~~~
C:\mingw64\bin\g++.exe -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -Wall -Wextra ^
    -static -static-libgcc -static-libstdc++ -DNDEBUG ^
    -o native\build\mingw\cava_selftest.exe native\tests\cava_selftest.cpp native\src\*.cpp
~~~

DLL：

~~~
C:\mingw64\bin\g++.exe <同上旗标> -shared -o natives\windows-x64\cava.dll native\src\*.cpp
~~~

**两个必须记住的坑（都实测踩过）**：

- `-static -static-libgcc -static-libstdc++` **不能省**。省掉时 cava.dll 的导入表里有
  `libstdc++-6.dll` / `libgcc_s_seh-1.dll` / `libwinpthread-1.dll`，本机实测在干净 PATH 下
  `LoadLibrary` 直接失败（error 126）；Minecraft 服务端的 java 进程 PATH 里不会有 MinGW。
  现在 `build-mingw.ps1` 会解析导入表，出现 MinGW 运行时 DLL 就 **throw**（构建期拦住，而不是让 Java 侧踩）。
- 本机沙箱下 g++ 的中间文件目录不可写（实测 `as.exe` 报
  `can't create C:\Users\...\Temp\dsh-xxxx\ccXXXX.o: No such file or directory`）→
  构建脚本把 `$env:TMP` / `$env:TEMP` 指到工作区内的 `native\build\tmp`。

### 2.2 CMake 路径（P0-A）

见 `native/README.md` 与根 `CMakeLists.txt` 注释（`-G "MinGW Makefiles"` + 便携版 cmake 全路径）。
P0-A 的 CMake 用 `GLOB_RECURSE` 收 `native/src` 下的 `.c/.cpp`，所以**加文件不用改 CMake**（已用他们的构建产物验证过，见 §5.3）。

---

## 3. 错误码与句柄语义（Java 侧按这个写 NativeStatus 映射）

### 3.1 `cava_open` 失败矩阵（全部实测，无一崩溃）

| 输入 | 返回 | `out_handle` | `out_result.status` |
| --- | --- | --- | --- |
| 正常（正确 sum + 正确 ABI） | `CAVA_OK` | 非 0（本机首次 `0x100000001`） | `CAVA_OK` |
| `layout_hash_sum` 错 | `CAVA_ERR_LAYOUT` (-2) | 0 | -2 |
| `abi_version` 不等于 `CAVA_ABI_VERSION` | `CAVA_ERR_ABI_VERSION` (-1) | 0 | -1 |
| `params == NULL` | `CAVA_ERR_NULL` (-3) | 0 | -3 |
| `out_handle == NULL` | `CAVA_ERR_NULL` (-3) | — | — |
| 句柄表满（256 个未关） | `CAVA_ERR_OOM` (-5) | 0 | -5 |
| 分配抛异常 | `CAVA_ERR_OOM`（异常绝不穿过 ABI 边界） | 0 | -5 |

**`out_result.native_layout_sum` 永远回填**（成功、失败的每一条路径都写），
这就是「让 Java 侧验证原生算出的和」的入口 —— **不需要改头文件**：
Java 侧只要传一个自己的 `CavaOpenResult`，即使 `cava_open` 返回 `CAVA_ERR_LAYOUT`，
也能把 `native_layout_sum` 打出来和 Java 自己算的对比（排障时非常有用）。

### 3.2 `cava_close` 语义（实测）

| 输入 | 返回 | 说明 |
| --- | --- | --- |
| 有效句柄 | `CAVA_OK` | 释放；槽位 generation+1 |
| 同一个句柄再关一次 | `CAVA_ERR_NULL` (-3) | 契约 §2.2 要求的「重复关闭返回 NULL」 |
| `0` | `CAVA_ERR_NULL` | |
| `-1` / `INT64_MIN` / `0x7FFF...FFFF` / 越界槽位 / 未来 generation | `CAVA_ERR_ARG` (-4) | **伪造**句柄（从没发出去过） |
| 槽位 0 + generation 0 | `CAVA_ERR_NULL` | 陈旧 |

判定规则：`gen < slot.next_generation` → 陈旧（`CAVA_ERR_NULL`）；`gen >= ` 当前 → 伪造（`CAVA_ERR_ARG`）。

### 3.3 句柄形状

句柄是 `((uint64)generation << 32) | (slot_index + 1)`，**不是裸指针**：
- `slot_index + 1 >= 1` ⇒ 句柄恒非 0；
- 重复关闭后 generation 递增，老句柄不会复活到新对象上（实测 64 轮 open/open/close/close 无重复、无失败）；
- 内部用 `std::mutex` + `shared_ptr` 查表，并发 close 不会让正在使用的人踩空（P0 无并发，先把地基打对）。

---

## 4. SAFE 开关（B2）实测

- 打开方式（**三个宏名都认**，因为构建侧实际发出的是后两个）：
  `-DCAVA_SAFE=1`（原生脚本） / `-DCAVA_SAFE_BUILD=1`（`native/cmake/CavaFlags.cmake` 的 `CAVA_SAFE=ON`） /
  `-DCAVA_BUILD_SAFE=1`（同文件的 `cava_apply_build_definitions()`）。
- `cava_layout_report` 的 `build_flags`：SAFE 位 = `CAVA_BUILD_FLAG_SAFE_ASSERTS`。
  实测 Release `0x00000000`；SAFE 构建 `0x00000003`（safe + debug，因为 SAFE 构建会 `-UNDEBUG`）。
- 触发时**记录 + 返回错误码，不 abort**：SAFE 构建跑自测，故意喂非法参数，退出码仍是 0，stderr 只有记录行：

~~~
[cava][SAFE] assertion #1 failed: (params != nullptr) at J:\mc\Cava\native\src\cava_handle.cpp:90 -> code=-3
[cava][SAFE] assertion #2 failed: (out_handle != nullptr) at J:\mc\Cava\native\src\cava_handle.cpp:89 -> code=-3
[cava][SAFE] assertion #3 failed: (out != nullptr) at J:\mc\Cava\native\src\cava_layout.cpp:245 -> code=-3
...
=== SUMMARY: 67 passed, 0 failed ===
RESULT: PASS      (exit code = 0)
~~~

- 计数不封顶，但只往 stderr 打前 16 条（避免刷屏）；`CAVA_ASSERT` 在 Release 下展开为空表达式
  （`cond` 与 `code` 都不求值）。
- 注意：**`cava_open` 的行为在 SAFE / Release 下完全一致**（SAFE 只是多记一行日志），
  所以「Java 传错 sum」在两种构建里都得到 `CAVA_ERR_LAYOUT`。

---

## 5. 自测与验收证据

### 5.1 三种构建各 67 项全过（实测输出）

~~~
=== run cava_selftest.exe (release) ===          SUMMARY: 67 passed, 0 failed   RESULT: PASS   exit 0
=== run cava_selftest_safe.exe (CAVA_SAFE=1) === SUMMARY: 67 passed, 0 failed   RESULT: PASS   exit 0
=== run cava_selftest_cxx20.exe (c++20) ===      SUMMARY: 67 passed, 0 failed   RESULT: PASS   exit 0
~~~

覆盖：ABI 版本 / build_id；布局 4 条（struct_size/align/field_count/每个字段 offset+size 与**硬编码 x64 期望**比对、
layout_hash 与测试侧**独立重算**比对、build_id_hash 与独立 FNV 比对）；`cava_open` 6 条失败路径；
`cava_close` 幂等 + 5 类非法句柄 + 64 轮 churn；`d2i_sat` 35 例 / `d2l_sat` 29 例
（NaN 5 种载荷、±0、±inf、INT_MIN-1、INT_MAX+1、刚好边界、超大 double、次正规）；
`bits_of_double` / `double_of_bits` 随机 20000 例（含 NaN 载荷/次正规/±0）；
`cava_abi_touch` 递增；`cava_layout_report(NULL)` 不崩。

### 5.2 交付物 `natives/windows-x64/cava.dll`（实测）

~~~
>>> import table check: J:\mc\Cava\natives\windows-x64\cava.dll
    DLL Name: KERNEL32.dll
    DLL Name: msvcrt.dll
    OK: 只有系统 DLL
=== run cava_dll_loadtest.exe (clean PATH) ... cava.dll ===
  [ ok ] 10 个 ABI 符号全部可解析（和 Java FFM Linker 查的名字一致）
  layout_hash_sum = 0x6149fd30
RESULT: PASS   exit 0
~~~

`cava_dll_loadtest` 用 `LoadLibraryA + GetProcAddress` 按名字查 `cava_build_id` / `cava_abi_touch` /
`cava_abi_version` / `cava_layout_report` / `cava_open` / `cava_close` / `cava_d2i_sat` / `cava_d2l_sat` /
`cava_bits_of_double` / `cava_double_of_bits` 十个符号，并通过函数指针真调一遍 ——
等价于 Java FFM `Linker` 的查找方式，能把「MinGW 没导出符号」这类问题提前到构建期。

### 5.3 与 P0-A 的 CMake 产物交叉验证（实测）

在 P0-A 的 CMake 于 13:52 产出的 DLL（导出表里还有他们的 `cava_p0a_stub_*`）上跑 `cava_dll_loadtest`：
10 个符号全部可解析，`cava_layout_report` 返回 4 条，哈希与我们算的**逐个相同**，`layout_hash_sum = 0x6149fd30`，
`cava_open` 成功 —— 说明两套构建编出的 ABI 完全一致。
（该 DLL 需要 MinGW 运行时 DLL，见 §2.1 与 §7.2。）

另外：P0-A 的 CMake/CTest 也编了同一个探针（`native/tests/*.cpp` 每个文件一个 test target），
它跑出来的 `fp_probe.txt` 数据行（15456 行）与本脚本产出的**逐行完全相同**，
只有头部 `# build_id:` 注释行不同（两套构建给的宏不同）—— 这又是一次编译器参数等价的交叉验证。
因此：**数据行是稳定的，build_id 注释行会随构建方式变**；提交时不要因为这一行而困惑。
无参运行 `cava_fp_probe.exe`（CTest 就是这么调的）**不写任何文件**，只打印 SKIP 提示，
避免测试运行悄悄改写 git 里的黄金向量。

---

## 6. fp_probe 结论（B4，本节是 P0 最重要的证据）

- 向量规模：**15456 条**（随机位模式对 2000 组 + 40 个特殊值的 40×40 笛卡尔积 1600 组，
  合计 3600 组 × 4 个二元运算 = 14400 行，加 `sqrt` 1056 行）。
  输入全部写死在 `native/tests/cava_fp_probe.cpp`（splitmix64，固定种子 `0x0F1E2D3C4B5A6978`），可重复。
- 文件：`native/tests/vectors/fp_probe.txt`（851 KB，**进 git**）。
  格式：`<op> <a_bits:16hex> <b_bits:16hex> <result_bits:16hex>`，`#` 开头是注释；
  `op` 属于 `{add,sub,mul,div,sqrt}`；`sqrt` 是单目，`b_bits` 恒为 `0000000000000000`，
  输入只取 `!(a < 0)` 的向量（这样 NaN / ±0 / +inf / 次正规全都被覆盖；负数 sqrt 不测 —— 负数开方的 NaN 符号两语言都不保证）。
- 比对器：`native/tests/java/FpProbeJava.java`（JDK 21；用 `doubleToRawLongBits`，不能用 `doubleToLongBits`，后者会把 NaN 规范化、掩盖载荷差异）。

### 6.1 实测结果（MinGW GCC 15.2.0 → JVM 21.0.10）

~~~
  add  count=3600    mismatch=20
  div  count=3600    mismatch=0
  mul  count=3600    mismatch=20
  sqrt count=1056    mismatch=0
  sub  count=3600    mismatch=0
总结果数 15456，差异 40（双 NaN 操作数 40 / 其它 0 / NaN 类别不一致 0）
~~~

### 6.2 结论（精确表述）

1. **`sub` / `div` / `sqrt`：15456 条里 0 差异，逐位一致**（含 ±0、±inf、次正规、极大极小、舍入边界）。
2. **`add` / `mul`：只有 40 条不同，且这 40 条全部是「两个操作数都是 NaN 且位模式不同」**：
   NaN 载荷一共 5 种，自组合 25 对，其中 5 对两边载荷相同（一致），剩下 20 对不一致 × add/mul 两种 = 40 条。
   只要有一个操作数不是 NaN（含 ±0、±inf、普通数），结果逐位一致；`0.0/0.0` 两边都得到同一个 `0xFFF8000000000000`。
3. **「是不是 NaN」从不出现分歧**（nanClassAgree 全为 true），差异只在 NaN 的**载荷/符号位**上。
4. 差异来自**编译器代码生成**，不是源码写法：GCC 对可交换的 `+ *` 会自行决定哪个操作数当 SSE 目标寄存器，
   而 HotSpot C2 的选择不同。x86 `ADDSD` / `MULSD` 的规则是「第一个源操作数若为 QNaN 就返回它」，
   于是「双 NaN」时选中的载荷取决于指令编码顺序。
   **实测验证（阴性对照）**：把源码里的 `a+b` 改写成 `b+a`（`-DCAVA_FP_PROBE_SWAP_COMMUTATIVE`）后，
   `add` / `mul` 的差异**仍是同样 20 条**（换源码顺序没用）；而同一次实验里 `sub` / `div` 因为不可交换被换坏，
   差异暴涨到 3188 / 3210 条 —— 这同时证明比对器确实能抓到差异，不是「懒得报错」。

### 6.3 对差分测试（P0-C / W2-差分）的直接影响

契约 §4.2 的黄金轨迹用「x/y/z 的 double **原始位模式**」。上面第 2 条意味着：
**如果某个 NaN 以非规范载荷的形式流进了被哈希的状态，native 开/关两次运行的哈希可能不同**。三条建议：

1. 哈希时把 NaN **规范化**（Java 用 `Double.doubleToLongBits`，原生侧用等价的规范化函数），
   或在写入轨迹前把 NaN 统一成 `0x7FF8000000000000`。这是最省事、最稳的做法。
2. 真出现「双 NaN 参与 + 或 *」的场景极少（Minecraft 的坐标/速度算术正常不会产生非规范 NaN 载荷）。
   一旦出现，说明那一处的数值已经不正常了，值得单独记一条日志。
3. P1+ 的原生入口**不要**自己造 NaN 载荷；返回值里出现 NaN 一律规范化后再交给 Java。

> 本条建议涉及契约文本（§4.2 的「原始位模式」措辞），归 captain 决定；P0-B 不擅自改契约。

---

## 7. 与构建侧（P0-A）的接口

### 7.1 宏名（已双向对齐，实测）

`native/src/cava_abi.cpp` 生成 build_id 时，**每一段都优先用 CMake 给的宏**，没给才用自己推导的：
`CAVA_BUILD_PLATFORM_TAG` / `CAVA_BUILD_COMPILER` / `CAVA_BUILD_FP_FLAGS`（整串覆盖用 `CAVA_BUILD_ID`；
版本号用 `CAVA_VERSION_STRING`，默认 0.1.0）。
用 P0-A 的宏名手工编译实测：

~~~
build_id    : cava 0.1.0 windows-x64 GNU 15.2.0 (C:/mingw64/bin/g++.exe) -O2 -fwrapv -ffp-contract=off -fno-fast-math safe=1 asan=0 ubsan=0
build_flags : 0x00000003 (safe_asserts=1 debug=1 asan=0 ubsan=0)
layout_hash_sum = 0x6149fd30
=== SUMMARY: 67 passed, 0 failed ===
~~~

即：P0-A 的 `CAVA_SAFE=ON` 现在**真的**会点亮原生侧的断言（此前宏名不一致，会静默不生效）。

### 7.2 需要 P0-A 跟的一件事（**未做，属阻塞性请求**）

`native/cmake/CavaFlags.cmake` 的 `cava_configure_target()` 目前**没有**给 target 加静态运行库链接，
所以 CMake 编出的 cava.dll 依赖三个 MinGW 运行时 DLL，在没装 MinGW 的机器上
（= 真实 Minecraft 服务端）`LoadLibrary` 会失败（本机干净 PATH 实测 error 126）。
建议在 `cava_configure_target()` 末尾加：

~~~cmake
if(WIN32 AND NOT MSVC)
    target_link_options(${target} PRIVATE -static-libgcc -static-libstdc++ -static)
endif()
~~~

### 7.3 输出路径冲突（提请裁定）

`natives/windows-x64/cava.dll` 目前**两个流都会写**：P0-A 的 CMake，以及 `native/tests/build-mingw.ps1`。
两边编出来的 ABI 内容一致（§5.3 实测），但**能不能加载**不同（§7.2）。
建议：以 CMake 为唯一发布者，P0-B 的脚本只在 `native/build/mingw/` 下产 DLL 供自测；
在 P0-A 加上 §7.2 的链接选项之前，最后写入者决定该文件能不能被 Java 加载。

---

## 8. 未验证清单（不要当成结论）

1. **MSVC 构建**：一次都没跑（本文所有数字都是 MinGW GCC 15.2.0 的）。MSVC 下 `sizeof` / `offsetof` 与
   `layout_hash` 是否一致未验证。
2. **Linux x64 构建**：未跑。
3. **P0-A 的 CMake 跑 `CAVA_SAFE=ON`**：宏名兼容性是手工模拟验证的，没有真跑一次他们的 SAFE 构建。
4. **`fp_probe.txt` 在其它编译器/平台上的逐位一致性**：只有 MinGW + JVM21 这一组实测结论。
   换编译器（MSVC）后 `add` / `mul` 的双 NaN 条数可能不同（但「非双 NaN 全一致」这一条预计成立）。
5. **`sqrt` 的机制**：本机 `-O2 -fno-fast-math` 下 `sqrt` 1056 条全一致（实测），
   但没有单独反汇编确认编译器发的是 `sqrtsd` 还是调 libm —— 结论是「实测一致」，不是「机制上保证」。
6. **`std::sqrt` 之外的超越函数**：按契约根本不允许跨到原生侧，未测。
7. 黄金轨迹里 NaN 规范化的最终决定：归 captain（§6.3）。

---

## 9. 请求（给 captain / 各流）

1. **P0-A**：按 §7.2 给 cava target 加静态运行库链接（阻塞 Java 侧在真实服务端加载）。
2. **captain**：裁定 `natives/windows-x64/cava.dll` 的唯一发布者（§7.3）。
3. **captain**：契约 §2.3 写了「每个结构体必须有 `cava_layout_<name>(hash*, size*, align*)`」，
   但**冻结的 `cava_abi.h` 里并没有声明这 4 个函数**。P0-B 的处理：把它们实现成
   `native/src/cava_layout.cpp` 里的内部 C++ 函数（`cava::detail::layout_CavaLayoutEntry` 等，**不导出**），
   对外仍只用 `cava_layout_report` + `CavaOpenResult.native_layout_sum`。
   要真正导出的话需要 captain 往头文件加 4 条声明（P0-B 没有改头文件）。
4. **captain**：`native/src/common/cava_p0a_stub.{c,cpp}` 与 `native/tests/tmp_p0a_smoke.cpp` 在 P0-B 的
   所有权目录里（整合门第 1 条已写明要删）。前两者现在会被编进 cava.dll（导出 `cava_p0a_stub_*`）。
5. **P0-C**：本侧的 `native/tests/java/FpProbeJava.java` 是原生侧的自检工具（不在 `src/test` 里）。
   真正的单元层测试建议把「读 `fp_probe.txt` 逐位比对」那段搬进 `src/test/java/cava/parity/` 并 CI 化；
   需要 §6.3 条的 NaN 规范化决定。