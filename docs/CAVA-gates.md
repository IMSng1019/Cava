# Cava 门禁验证记录（captain）

> **每条门禁都必须有本机实测证据。** 这是"架构假设是否成立"的台账，与 `docs/CAVA-baseline.md`（性能基线）分开。
> 复现脚本：`tools/build-preview-gate.ps1` + `tools/setup-preview-gate-server.ps1`。

---

## 门禁 #5（`docs/CAVA-execution-plan.md`）：**预览版 class 能否被 Fabric Loader 加载并执行**

**结论：通过（PASS）。**

### 为什么这条是架构级门禁
整个项目的载体假设是「Fabric mod + JDK 21 **预览版 FFM**（必须 `--enable-preview`）」。
如果 Fabric Loader / Mixin 无法处理 class 文件 major 65 + minor 65535（预览版标志），或者运行时拿不到
`java.lang.foreign`，那么"用 FFM 调 C++"这条路根本不成立，整个方案要重审。**这条必须先验，不能等到 P1。**

### 做法（不依赖 Gradle / Loom，纯手工 jar）
1. 用 `javac --release 21 --enable-preview` 编一个实现 `net.fabricmc.api.ModInitializer` 的类，
   编译期依赖直接取本机 Gradle 模块缓存里的 `fabric-loader` 与 `sponge-mixin`（不联网、不走 Loom）。
2. 打成普通 mod jar（`fabric.mod.json` + 一个 entrypoint），丢进真实 **Fabric 0.19.5 / MC 1.20.4** 服务端的 `mods/`。
3. 用 `--enable-preview --enable-native-access=ALL-UNNAMED` 启动真实服务端。
4. 在 `onInitialize` 里用预览版 FFM 做一次真实 native 调用（`strlen`），并把结果打进日志。

### 证据（真实 stdout，未删改）

    正在加载 Minecraft 1.20.4 with Fabric Loader 0.19.5
    加载 5 个 mod:
        - cava-gate 0.0.1
        - fabricloader 0.19.5
        - java 21
        - minecraft 1.20.4
    [CAVA-GATE] ================= PREVIEW GATE =================
    [CAVA-GATE] mod class file major.minor = 65.65535   (65.65535 == preview)
    [CAVA-GATE] runtime               = 21.0.10+8-LTS-217
    [CAVA-GATE] fabricloader          = 0.19.5
    [CAVA-GATE] java.lang.foreign     = java.lang.foreign.Linker
    [CAVA-GATE] native strlen("cava")  = 4   (expect 4)
    [CAVA-GATE] VERDICT               = PREVIEW_OK
    [13:54:31] [Server thread/INFO]: Done (14.193s)! For help, type "help"

（`major.minor = 65.65535` 直接读的是 jar 里那个 class 文件自己的头，证明它确实是预览版 class，
而 Loader 把它加载起来并执行了 `onInitialize`。）

### 适用版本（实测）
| 项 | 值 |
| --- | --- |
| Minecraft | 1.20.4 |
| Fabric Loader（真实服务端解析到的） | **0.19.5**（与 `gradle.properties` 里 pin 的 `loader_version=0.19.5` 一致） |
| Fabric Installer | 1.1.2 |
| `sponge-mixin`（服务端实际加载的） | 0.17.4+mixin.0.8.7 |
| JDK | 21.0.10+8-LTS-217 |
| 启动参数关键项 | `--enable-preview --enable-native-access=ALL-UNNAMED` |

### 由这条门禁派生的**新的硬约束**

1. **`--enable-native-access=ALL-UNNAMED` 不能省。** 少了它 native downcall 会在运行期被拒。
   这条要进 P0-A 的 Gradle 运行参数、进 `docs/CAVA-build.md`、进测试服启动脚本（已写进主计划的门禁 2）。
2. **Fabric Loader 0.19.5 是**"能加载预览版 class 的**已知可用版本**"。以后任何 `loader_version` 变更都要重跑这条门禁。
3. 预览版 class 可以正常打成 jar、被 Loader 发现、被实例化 —— 所以 **Cava 的 `ModInitializer` 本身可以直接是预览版 class**，
   不需要为它单独做 classloader 隔离。（Mixin 处理预览版 class **池**的完整验证放在门禁 2 的端到端冒烟里，用真实 Loom 产物跑。）

### 未验证的部分（诚实标注）
- 本门禁**没有**验证「Mixin 注解处理器 + Loom remap 在预览版 class **池**上是否正常」（见 `docs/CAVA-execution-plan.md` 门禁 2）。
  本门禁故意只用普通 mod jar + entrypoint，把变量降到最少。
- 本门禁**没有**验证 49 mod 整合包环境下是否一致（只有纯 Fabric Loader + Minecraft）。

---

## 门禁 #1（构建）：**Loom 版本必须 pin 到 1.17.x**

**结论：通过（已定位根因并由 P0-A 修复）。** 这不是架构假设问题，但会直接让构建失败，所以记在这里。

| 事实 | 证据 |
| --- | --- |
| `fabric-loom` **1.18.2** 要求 **JVM 25** | `Could not resolve net.fabricmc:fabric-loom:1.18.2 ... Dependency requires at least JVM runtime version 25. This build uses a Java 21 JVM.`；maven.fabricmc.net 的 module metadata 属性 `org.gradle.jvm.version = 25` |
| `fabric-loom` **1.17.20 / 1.17.21** 要求 **JVM 21** ✅ | 同一属性 = 21，plugin-api 9.5.0 |
| `fabric-loom` 1.10.5 要求 JVM 17 | 同一属性 = 17 |

**决定**：`gradle.properties` 的 `loom_version` pin 到 **1.17.20**，Gradle 用 **9.7.1**（满足 plugin-api 9.5.0）。
**推论**：模板自带的 CI workflow 用 JDK 25 是**因为 Loom 1.18 需要 25**；本项目改用 Loom 1.17.x 之后，
CI 必须把 JDK 固定成 **21**（否则 `--release 21 --enable-preview` 不成立）。
