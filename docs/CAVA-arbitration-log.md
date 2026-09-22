# Cava 仲裁与复核记录（captain 亲历）

> 这份文件记录**多方意见不一致时 captain 是怎么裁的、依据是什么**。
> 目的有两个：① 让后来的会话知道"哪些结论被双向锁死过"；② 留下"读字节码读反了会长什么样"的活教材。
> 与 `docs/CAVA-gates.md`（门禁）分开：那份记"东西能不能跑"，这份记"结论对不对"。

---

## 仲裁 #1：`LandPathNodeMaker.isValidDiagonalSuccessor` 第三个合取项的极性

**结论：`!flag5`（正确）** ｜ 状态：**已双向锁死** ｜ 日期：2026-09-22

### 三方立场
| 方 | 主张 |
| --- | --- |
| W2-P1 实现流 | 第三个合取项是 `!flag5`，并给出 `build/probe/DiagProbe.java` 的判定性实验（编译两种候选源码形状看 javac 生成 `ifeq`/`ifne`） |
| P1-Oracle 参照实现 | 写成 `flag5`（`LandMaker.java:464,467`）；但其**自己的规格第 256/259 行注释写的是 `// !flag5 -> return false`** —— 文档内部自相矛盾 |
| 广播 #5（captain，转述 oracle） | 写成"两侧都 y>=host.y 且 penalty<0 且 **flag5** 才拒绝" —— **转述时把 oracle 的错抄了进去** |

### captain 的独立复核（唯一依据：字节码）
`javap -p -c -classpath <minecraft-common-1.20.4-…jar> net.minecraft.entity.ai.pathing.LandPathNodeMaker`
局部变量映射：**1=host, 2=sideA, 3=sideB, 4=diag, 5=flag5**

    124: aload 4 ; getfield penalty ; fconst_0 ; fcmpl ; iflt 188
    134: sideB.y ; host.y ; if_icmplt 159
    145: sideB.penalty ; fconst_0 ; fcmpl ; ifge 159
    154: iload 5 ; ifeq 188        <-- 关键
    159: sideA.y ; host.y ; if_icmplt 184
    170: sideA.penalty ; fconst_0 ; fcmpl ; ifge 184
    179: iload 5 ; ifeq 188        <-- 关键
    184: iconst_1 ; goto 189       <-- 唯一的 true 出口
    188: iconst_0 ; ireturn        <-- 唯一的 false 出口

**推理链（三步，每步都可独立验证）**：
1. `ifeq 188` 的语义 = "**操作数为 0 时跳转**"（`ifeq` 跳的是"等于零"，不是"非零"）。⚠️ 这一步就是两次读反的地方。
2. 偏移 **188 是唯一的 `iconst_0; ireturn`**（false 出口），`184` 是唯一的 `iconst_1`（true 出口）。可由 `131: iflt 188`（`diag.penalty < 0` → false）与 `167/176: if_icmplt/ifge 184` 交叉验证。
3. 因此"落到 154/179 之后走到 188"的条件是 **`flag5 == 0`** ⇒ 该析取项是 `|| flag5` ⇒ 取反后合取项是 **`&& !flag5`**。

**语义侧自洽性检查（不是依据，但是有力旁证）**：`flag5 = sideB.type==FENCE && sideA.type==FENCE && (double)width < 0.5`，
即"**窄体型生物夹在两面栅栏之间**"。若按 `flag5` 读，含义会变成"窄体型生物被拒绝、宽体型放行"——这与原版特意为小鸡/蝙蝠这类小生物开口子的意图正好相反。

### 影响面
`entity.width < 0.5` 的生物（鸡、鹦鹉、蝙蝠等）贴着**两面栅栏**做**对角移动**时的选路。
不是"罕见角落"：这是原版为小生物专门写的一条豁免。

### 为什么 10000 组向量抓不住它（**本轮最重要的一条工程教训**）
向量是**参照实现自己产**的。参照实现错，向量就跟着错，比对必然全绿。
⇒ **自洽 ≠ 对齐原版**。随机向量只能证明"两个实现彼此一致"，不能证明"它们都等于原版"。
**对策（已落地）**：对每个"容易读反"的分支补**定点真值表断言**，真值**从字节码推**，而不是从实现反推。

### 双向锁死的证据
- **oracle 侧**：`OracleSelfTest.checkDiagonalTruthTable()` 14 行真值表（宽度 0.4 / 0.49999 / 0.5 / 0.9 × 两侧/单侧栅栏 × y/penalty/visited/WALKABLE_DOOR/null），自检 32 → **46 项、失败 0**。
  **反向验证**：把条件改回 `&& flag` 后**恰好 5 行 FAIL、其余 9 行通过** —— 证明这条测试确实抓得住这个分支。
- **P1 实现侧**：按 `!flag5` 实现，并被要求补等价定点用例。
- **captain 侧**：javac 直编 18 个 oracle 类跑 `OracleSelfTest` → exit 0 / 46 项失败 0；并独立复算向量哈希。

### 向量重置（修 bug 导致，不是漂移）
| 文件 | 旧 (8606e85) | 新 (389fa43) |
| --- | --- | --- |
| `golden-00.bin` | `7D29C7F1…5CA7` | `0AB5DD015CC7DE87085274C888B75EEF16B488A47561E15E2FB62C9491EAB212` |
| `vectors-00.bin` | `E48FE936…7CDB` | `A030904FC43CB362F6EEB43FE7DA7C010EDDB0C390B0A3DDB063C186FF811999` |
| `vectors-01.bin` | 未变 | `5C485B9C114644F850AC0005300526F182202BC8DF3867AA305B8F12FBA43370` |
| `manifest.txt` | 未变 | `34D5396CFB7CCBA5DBDE922EB94260A00B74B97ED78BB6196EB5AF636331B74E` |

> ⚠️ **`vectors-01.bin` 未变是正常的**（该分片没命中这条分支）。比对时必须**两个分片一起跑**，
> 不能因为"有一个文件对上了"就宣布一致。

---

## 教训清单（写给下一个会话）

1. **`ifeq`/`ifne`/`iflt`/`ifge` 的极性必须逐条回读，不能靠"感觉"**。本项目已经在这个方法上读反两次。
   可靠做法：先确认**出口标签**（哪个偏移是 `iconst_0/ireturn`、哪个是 `iconst_1`），再回推跳转条件。
2. **参照实现自产的向量不能证明参照实现是对的**。容易读反的分支一定要有**从字节码推出真值**的定点用例。
3. **写"反向验证"**：故意把实现改回错误形状，确认测试**确实变红**。只报"测试全绿"没有说服力。
4. **转述也会传播错误**：captain 在广播 #5 里把 oracle 的错抄给了所有人。转述技术结论时必须回读原始证据，不能只当二传手。
5. **文档内部矛盾是强信号**：oracle 的注释对、改写错 —— 同一份文档里两种说法不一致时，**说明有人在这里犹豫过**，必须回字节码。
