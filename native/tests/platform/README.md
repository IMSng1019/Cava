# native/tests/platform —— 平台矩阵的数值一致性测试套件

> 交付物归属：P4-B（平台与 CI 轮）。这个目录是**新增**的，不动 `native/tests/CMakeLists.txt`。

## 这个套件回答什么问题

prompts/07 第 1 条：「每个平台都必须跑一遍**数值一致性测试套件**（逐位一致性 + 编译开关 +
ABI 布局自检）——这是「全平台」真正的成本所在。」

本目录把这句要求做成了**可执行、可复现、能红**的四节：

| 节 | 内容 | 判据 |
| --- | --- | --- |
| 1. 环境画像 | 编译器/位宽/端序/FLT_EVAL_METHOD/FMA 可用性 | 小端与 64 位硬断言；其余打印供 5 平台对照 |
| 2. 编译开关自检 | 宏层 + 行为层 | `__FAST_MATH__` / `__FINITE_MATH_ONLY__` 编译期拒绝；`-fwrapv` 用 `x+1>x` 行为证明；`a*a-1` 的 FMA 收缩探针；**sqrt 必须走正确舍入路径**（5 个定点） |
| 3. 逐位一致性 | 读签入的黄金向量 `native/tests/vectors/fp_probe.txt`（15456 行），在**本平台**重算 + - * / sqrt 并逐位比对 | 数值位不一致必须为 0；「两边都是 NaN、只有载荷不同」默认计数豁免（可用 `--strict-nan` 变硬） |
| 4. ABI 布局自检 | 运行期加载原生库，调 cava_abi_version / cava_layout_report / cava_open / cava_close | 14 条 entry、`layout_hash_sum = 0x1C12265E`、逐字段 (offset,size) 与 offsetof/sizeof 机械表一致、错和值必须被 CAVA_ERR_LAYOUT 拒 |

## 怎么跑

### 方式一：不依赖 CMake（就是一条 g++ 命令；Windows 上脚本已封装）

    # Windows
    pwsh -File native/tests/platform/run-platform-suite.ps1 -Lib natives/windows-x64/cava.dll
    # Linux / macOS
    sh native/tests/platform/run-platform-suite.sh natives/linux-x64/libcava.so

脚本内部等价于：

    g++ -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -fno-math-errno -Wall -Wextra -static \
        -I native/include -o build/platform-suite/cava_platform_suite.exe \
        native/tests/platform/cava_platform_suite.cpp
    # 必须从仓库根运行：黄金向量路径默认是仓库根相对的
    build/platform-suite/cava_platform_suite.exe --golden native/tests/vectors/fp_probe.txt \
        --expect-rows 15456 --lib natives/windows-x64/cava.dll

### 方式二：走 CMake（复用 native/cmake/CavaFlags.cmake 的硬性参数）

    cmake -S native/tests/platform -B build/platform-suite -DCMAKE_BUILD_TYPE=Release
    cmake --build build/platform-suite --config Release --parallel
    build/platform-suite/cava_platform_suite --golden native/tests/vectors/fp_probe.txt \
        --expect-rows 15456 --lib natives/<tag>/<cava.dll|libcava.so|libcava.dylib>

CI（.github/workflows/build.yml 的 native job）走的就是方式二，5 个平台同一条命令。

## 参数与退出码

| 参数 | 说明 |
| --- | --- |
| `--golden <file>` | 黄金 FP 向量，默认 native/tests/vectors/fp_probe.txt |
| `--lib <path>` | 原生库；缺省读环境变量 CAVA_SUITE_LIB，再缺省自动找 natives/<tag>/<库名> |
| `--expect-rows N` | 断言黄金行数（CI 传 15456） |
| `--strict-nan` | 把「双 NaN 载荷不同」也算失败（默认豁免并计数） |
| `--quiet` | 只打失败与摘要 |

退出码：0 通过 / 1 有检查失败 / 2 用法错误 / 3 黄金向量缺失。

## 三个必须知道的设计取舍（都是实测逼出来的）

1. **为什么运行期 dlopen 而不是链接**：这样测的是**真正要发出去的那个文件**，且 5 个平台用同一条
   命令；也顺带覆盖"符号名/导出表对不对"。

2. **为什么 NaN 载荷默认豁免**：IEEE-754 不规定 NaN 载荷的传播规则。同一台机器上
   native/tests/java/FpProbeJava.java 拿 Java 21 重算同一份黄金，add/mul 各有 20 行不符，
   **全部是双 NaN 载荷**，且"是不是 NaN"的类别不一致 = 0。要求跨平台 NaN 载荷逐位相同是在要求
   一件 IEEE 没保证、Java 自己都做不到的事。豁免是**计数 + 打印**的，不是静默跳过。

3. **为什么套件自己也要 `-fno-math-errno`**：本机实测，MinGW g++ 15.2 在缺这个开关时
   std::sqrt 会**有时**退化成 msvcrt 的 sqrt —— 而 msvcrt 的 sqrt **不是正确舍入**的，在 1056 行
   sqrt 里有 3 行差 1 ulp、2 行不静音 sNaN。详见 docs/CAVA-platform-notes.md §3。

## 一个必须说清的边界

第 3 节测的是**编译并运行这个套件的那个平台/工具链**的 + - * / sqrt 是否逐位复现黄金；
它**不经过原生库**（ABI 里没有算术自检入口）。所以：

* 「库自己的 FP 代码生成」由另外两条证据覆盖：
  tools/platform-flagcheck.ps1 从 flags.make/compile_commands.json 确认**库与套件用的是同一套开关**，
  并从反汇编确认**库里没有 AVX 浮点指令、sqrt 走硬件指令**；
* 「库的行为」由第 4 节（真调 cava_layout_report / cava_open）覆盖。

## 相关工具

| 工具 | 干什么 |
| --- | --- |
| tools/platform-flagcheck.ps1 | **从产物反查**编译开关：flags.make + compile_commands.json + 导入表 + 反汇编（浮点 AVX / sqrt 路径） |
| tools/platform-tagmatrix.ps1 | 平台标签推导：17 个组合（10 个纯函数 + 7 个真实 cmake configure），可选再跑真实交叉 configure |
| native/tests/platform/tag_matrix.cmake | 上面那个的 CMake 实现（只需要 cmake，5 个平台都能跑） |
| native/tests/platform/tag_matrix_case.cmake | 单个组合的 cmake -P 用例（由 tag_matrix.cmake 驱动） |
