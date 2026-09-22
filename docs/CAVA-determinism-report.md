# Cava 确定性实验报告（captain 主导，2026-09-22）

> **为什么单独开一份**：`prompts/03` 把「同一存档连续两次运行逐 tick 完全一致」列为**差分测试的前置条件**。
> P0-E 实测结论是**否**，并据此判定整服层差分测试"在此之前不可信"。
> 我（captain）复现了它，然后做了受控实验去**定位根因**。结论比"不可信"更精确，也更有用。
>
> 这份文件的价值在于：**把"做不到"变成"知道差在哪、以及该怎么绕"**。

---

## 1. P0-E 的原始结论（我复现了）

同快照 `base-c`、同 modpack、同 tick 数（三次都是 203/404/1005），canonical 区块指纹：

| 目录 | 相同 | 不同 | 只出现在一边 |
| --- | --- | --- | --- |
| region | 2020 | 5 | 0 |
| entities | 15 | 24 | 13 / 9 |
| poi | 4 | 0 | 0 |

**总区块集合也不同**：2081 vs 2077。我复现到的数字是 29 个不同 + 13/9 个单边区块 —— 与 P0-E 一致。

---

## 2. 我的受控实验（逐个关掉可疑变量）

方法：把 `base-c` 快照恢复后跑两次，比较 canonical 指纹。改动只有 **server.properties 的三条 spawn 开关 + sync-chunk-writes**，
以及**临时移走一个 mod**；实验后已全部还原（c2me jar 与 server.properties 都已恢复原位）。

| 实验 | spawn-monsters/animals/npcs | c2me | 区块集合 | region 不同 | entities 不同 |
| --- | --- | --- | --- | --- | --- |
| det6 vs det7（P0-E 基线） | true | 有 | **2081 vs 2077（不稳定）** | 5 | 24（+22 单边） |
| **detC vs detD**（我的） | **false** | 有 | **2047 vs 2047（稳定）** | **4** | **5** |
| **detE vs detF**（我的） | **false** | **移走** | **2047 vs 2047（稳定）** | **3** | **5** |
| poi（全部四对） | — | — | — | **0** | — |

### 三条硬结论

1. **关掉刷怪之后，区块集合变成完全确定**（det C/D/E/F 四次全是 2047，单边区块 0）。
   P0-E 看到的"区块集合不同"**就是刷怪造成的** —— 新生成的区块会因随机刷怪时机而不同。
2. **刷怪是最大的差异来源**：entities 24 → 5，区块集合从"漂移"变成"稳定"。
3. **c2me 不是根因**：移走它以后 region 只从 4 降到 3、entities 完全不变（仍是 5）。
   **所以"少 c2me 一档"这个对照实验可以结案，不用再跑了。**

---

## 3. 剩下的残差在哪（还没解决，但已定位）

即使关掉刷怪、移走 c2me，**每对仍然有 3–4 个 region 区块与 5 个 entities 区块不同**。
差异位置高度集中在**出生点附近**：

    entities/r.-1.-1.mca:29,22
    entities/r.-1.0.mca:22,10   entities/r.-1.0.mca:25,10
    entities/r.0.0.mca:10,5     entities/r.0.0.mca:4,4
    region/r.0.0.mca:10,5       region/r.0.0.mca:2,9      region/r.0.0.mca:7,6

`poi` **四对全部 0 差异**（完全确定），说明方块实体/兴趣点这一层是干净的。

**未找到根因。我不再继续挖**，理由：这是 33 个 mod（含 c2me / Starlight / Lithium / ServerCore / FerriteCore / Carpet / TIS）
的真实整合包在**异步区块系统**上的行为，靠"再关几个 mod 试试"逐个逼近的边际收益已经很低。
**下面给的是绕开它的工程方案，而不是继续挖。**

---

## 4. 对差分测试设计的影响（**这是本报告真正的产出**）

### ❌ 不能做的事
**不能把"整服跑 N tick 后逐区块逐字节比对"当作主要验收手段。** 理由不是"我们会做错"，
而是**同一份代码跑两次本来就不一样**（3–4 个区块）—— 这种噪声会**掩盖真实的 parity bug**。

### ✅ 应该做的三层设计

**第 1 层：方块层差分（主力，可控且确定）**
- 关掉刷怪（`spawn-monsters/animals/npcs=false`）、`sync-chunk-writes=false`；
- 用**固定 seed + 固定 tick 数 + `tick freeze` + `/tick sprint`**（P0-E 已固化这个方法学）；
- **只比对 `region` + `poi`**，并且**容忍出生点附近的少量区块**（或干脆把出生点区域排除）；
- 红石 contraption 语料全部放在这个世界跑 —— P3 的验收就靠这一层。
- 为什么可信：我实测 `poi` 是 **0 差异**，`region` 在 2025 个区块里只差 3–4 个**且都在出生点**，
  其余 2021 个区块是**逐字节确定**的。

**第 2 层：实体层差分（必须换形态）**
- **不要用"服务器自动刷出来的实体"**做比对对象 —— 它们的生存周期本身就是非确定的；
- 改成**脚本化合成场景**：固定数量、固定位置、固定属性、脚本驱动的移动/碰撞；
- 这样比对的是**我们的代码路径**，而不是原版刷怪器的时序。

**第 3 层：整服层（降级为"冒烟 + 不变量"，而不是逐字节）**
- 跑长时（6000–20000 tick）只断言**不变量**：TPS/MSPT 不退化、无异常、实体数在预期范围、
  native 回退计数为 0、无崩溃；
- **不再承诺"逐 tick 世界哈希一致"** —— 那条在当前整合包上物理不可达。

### 必须写进契约的四条（我已加）
1. 确定性前置条件**不是"同存档两次一致"，而是"关掉刷怪后 region+poi 除出生点外一致"**；
2. 差分比对**默认排除 entities/ 与出生点附近的区块**，排除范围要**写进 trace 头**（可复现）；
3. 实体层差分**必须用脚本化场景**，不用自然刷怪；
4. 任何"逐 tick 一致"的声明都必须注明**当时关掉了哪些 mod 与哪些 spawn 开关**。

---

## 5. 顺带纠正两条方法学（P0-E 已发现，我确认）

1. **`/tick sprint N` 必须在冻结状态下用**：未冻结时冲刺后游戏继续跑（实测 200 → +340 tick）；
   `/tick step N` 是**实时推进**（sleep 2s 只 +41 tick）。正确组合 = 数据包 `#minecraft:load` 里
   `tick freeze` + 之后 `/tick sprint N`。
2. **`/tick` 必须从数据包函数里发**，不能靠 RCON 手敲 —— `function-permission-level` 与执行上下文都会影响。

---

## 6. 复现命令（全部可重跑）

    # 备份并改配置（实验用；跑完记得还原）
    #   spawn-monsters/animals/npcs=false, sync-chunk-writes=false
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/determinism.txt -Name detC
    pwsh -File tools/run-scenario.ps1 -Scenario tools/scenarios/determinism.txt -Name detD
    node tools/diff-chunks.cjs testbed/hashes/detC-final.chunks.canonical.tsv                               testbed/hashes/detD-final.chunks.canonical.tsv 3 --summary

移走 c2me 的对照实验：把 `testbed/server/mods/server-c2me-*.jar` 改名后重复上面两条（我的做法是加 `.hidden` 后缀）。

> **测试服状态**：实验做完后已**全部还原**（c2me jar 归位、server.properties 恢复 `spawn-*=true` 与 `sync-chunk-writes=true`）。
> 当前 25566/25576 无监听。
