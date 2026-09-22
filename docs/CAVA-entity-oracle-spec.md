# Cava P2 实体碰撞语义规格（Oracle Spec）

> **本文件是 P2 实体碰撞/位移子系统的唯一语义基准。** 全部内容来自本机 **Yarn 命名 Minecraft 1.20.4 字节码的 javap 输出**，
> 不是记忆、不是反编译猜测、不是从实现反推。凡是字节码看不出来的，一律写「未验证」。
>
> 作者：P2-K 子代理。工具链见 `docs/CAVA-dev-toolbox.md`；样式照抄 `docs/CAVA-pathfind-oracle-spec.md`。
> 配套内核：`native/src/entity/**`；交付说明与 ABI 提案：`docs/CAVA-p2-kernel-notes.md`。

---

## 0. 证据来源与复现命令

### 0.1 jar（**必须用 `minecraft-common`**，toolbox 第 2 节的勘误已在 P1 记录）

    C:\Users\<user>\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-common\
        1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\
        minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar

复现（javap **不认** `--enable-preview`，直接 `-p -c`）：

    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c -classpath <上面的 jar> net.minecraft.entity.Entity
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.shape.VoxelShapes
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.shape.VoxelShape
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.world.CollisionView
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.world.BlockCollisionSpliterator
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.CuboidBlockIterator
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.shape.VoxelSet
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.shape.BitSetVoxelSet
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.shape.SimpleVoxelShape
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.shape.ArrayVoxelShape
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.math.AxisCycleDirection
    & '...\javap.exe' -p -c -classpath <jar> 'net.minecraft.util.math.AxisCycleDirection$1'
    & '...\javap.exe' -p -c -classpath <jar> 'net.minecraft.util.math.AxisCycleDirection$2'
    & '...\javap.exe' -p -c -classpath <jar> 'net.minecraft.util.math.AxisCycleDirection$3'
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.math.Box
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.util.math.MathHelper
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.world.border.WorldBorder
    & '...\javap.exe' -p -c -classpath <jar> 'net.minecraft.world.border.WorldBorder$StaticArea'
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.world.BlockView
    & '...\javap.exe' -p -c -classpath <jar> net.minecraft.block.ShapeContext

本轮 javap 转储落在 `build/p2-javap/*.txt`（构建产物，不进仓库）。

### 0.2 映射表（`node tools/mapquery.cjs ...` 的**实测输出**）

| Yarn 成员 | intermediary | 说明 |
| --- | --- | --- |
| `Entity.move` | **`method_5784`** | `(MovementType, Vec3d)V` |
| `Entity.adjustMovementForCollisions(Entity,Vec3d,Box,World,List)` | **`method_20736`** | 静态 public，**组装形状表**的那一层 |
| `Entity.adjustMovementForCollisions(Vec3d,Box,List)` | **`method_20737`** | 静态 private，**真正的求解核心** |
| `Entity.adjustMovementForCollisions(Vec3d)` | **`method_17835`** | 实例 private，**含台阶分支**，`move` 直接调它 |
| `Entity.pushAwayFrom` | `method_5697` | P2 第 2 个核，本规格不覆盖 |
| `CollisionView.getBlockCollisions` | **`method_20812`** | default 方法，返回 `Iterable<VoxelShape>` |
| `EntityView.getOtherEntities` | `method_8333` / `method_8335` | 谓词版 / 无谓词版 |

类映射：`CollisionView`=`class_1941`/`csz`，`ShapeContext`=`class_3726`/`ely`，
`EntityShapeContext`=`class_3727`/`emd`，`BlockCollisionSpliterator`=`class_5329`/`cst`，
`VoxelShapes`=`class_259`/`emj`，`WorldBorder`=`class_2784`/`dky`。

### 0.3 任务书里两个**名字不存在**，本规格已按实测更正

- **`BlockView.clip` 在 1.20.4 不存在**（`mapquery method BlockView clip` -> 0 match）。
  射线入口是 **`BlockView.raycast(RaycastContext)`**，底层静态实现是
  **`BlockView.raycast(Vec3d, Vec3d, C, BiFunction, Function)`**（见第 8 节）。
  历史上 `clip` 是 1.17 之前的名字，本版已改名 —— **不要照旧名写 mixin 目标**。
- **`Entity.entityInside` / `limitPistonMovement` / `maybeBackOffFromEdge` / `updateEntityMovementAfterFallOn`
  在 1.20.4 都不存在**（`javap -p -c net.minecraft.entity.Entity` 全文扫描各 0 命中，见第 6.3 节）。
  它们是 1.21+ 的名字。1.20.4 对应的点是 `BlockState.onEntityCollision`。

---

## 1. 调用链总览（谁调谁、按什么顺序）

    Entity.move(MovementType, Vec3d)                       [method_5784]
      -> adjustMovementForSneaking(movement, type)          [虚方法，默认返回原值]
      -> Entity.adjustMovementForCollisions(Vec3d)          [method_17835, 实例 private]
           -> world.getEntityCollisions(this, box.stretch(movement))
           -> Entity.adjustMovementForCollisions(this, movement, box, world, entityCollisions)
                                                             [method_20736, 静态 public]
                -> 组装有序形状表：entityCollisions / worldBorder / blockCollisions
                -> Entity.adjustMovementForCollisions(movement, box, shapes)
                                                             [method_20737, 静态 private]  <<< 核心
                     -> VoxelShapes.calculateMaxOffset(axis, box, shapes, maxDist)
                          -> VoxelShape.calculateMaxDistance(axis, box, maxDist)
                               -> VoxelSet.getSize / getPointPosition / inBoundsAndContains

本文按「由内向外」写：先核心（第 2、3 节），再形状来源与顺序（第 4、5 节），再 `move` 的事件顺序（第 6 节）。

---

## 2. 核心：`method_20737` = `adjustMovementForCollisions(Vec3d, Box, List<VoxelShape>)`

### 2.1 空表短路（字节码 0-10）

    private static net.minecraft.util.math.Vec3d adjustMovementForCollisions(Vec3d, Box, List);
       0: aload_2 ; invokeinterface List.isEmpty:()Z
       6: ifeq 11
       9: aload_0 ; areturn              // return movement;  <-- 返回的是**同一个对象**

**语义**：形状表为空时**原样返回入参对象**（Java 里 `==` 成立）。逐位语义 = movement 的三个 double 位模式不变。
注意：`move` 之外的调用点会传 `entityCollisions`（可能为空）进来，所以这条短路在真实游戏里**经常命中**。

### 2.2 轴序（字节码 11-194）—— 这是本方法最容易被读反的地方

    private static Vec3d adjustMovementForCollisions(Vec3d movement, Box box, List<VoxelShape> shapes);
       0:  if (shapes.isEmpty()) return movement;                                  // 0-10
      11:  double dx = movement.x;                                                // 11-15
      16:  double dy = movement.y;                                                // 16-20
      22:  double dz = movement.z;                                                // 22-26
      28:  if (dy != 0.0) {                                                       // 28-32  dcmpl; ifeq 63
      35:      dy = VoxelShapes.calculateMaxOffset(Axis.Y, box, shapes, dy);      // 35-45
      47:      if (dy != 0.0) box = box.offset(0.0, dy, 0.0);                     // 47-62
           }
      63:  boolean bl = Math.abs(dx) < Math.abs(dz);                              // 63-81  dcmpg; ifge 80
      83:  if (bl) {                                                             // 83-85  ifeq 123
      88:      if (dz != 0.0) {                                                   // 88-92
      95:          dz = VoxelShapes.calculateMaxOffset(Axis.Z, box, shapes, dz);   // 95-105
     107:          if (dz != 0.0) box = box.offset(0.0, 0.0, dz);                 // 107-122
               }
           }
     123:  if (dx != 0.0) {                                                      // 123-126
     129:      dx = VoxelShapes.calculateMaxOffset(Axis.X, box, shapes, dx);      // 129-138
     139:      if (!bl) {                                                        // 139-141  ifne 158
     144:          if (dx != 0.0) box = box.offset(dx, 0.0, 0.0);                 // 144-157
               }
           }
     158:  if (!bl) {                                                            // 158-160  ifne 182
     163:      if (dz != 0.0) {
     170:          dz = VoxelShapes.calculateMaxOffset(Axis.Z, box, shapes, dz);   // 170-180
               }
           }
     182:  return new Vec3d(dx, dy, dz);                                         // 182-194

**逐条结论（必须逐位复刻）**：

1. **Y 永远是第一个算的轴**（且是唯一无条件先算的）。
2. `bl = |dx| < |dz|`。**`<` 是严格小于**；`dx`/`dz` 为 NaN 时 `Math.abs` 也是 NaN，
   `dcmpg` 对 NaN 返回 1 -> `ifge` 成立 -> **`bl = false`（NaN 走「X 先」的分支）**。
3. **`bl == true`（X 位移比 Z 小）时**：先算 Z 并把 `box` 沿 Z 平移；
   再算 X，**但 X 的结果不再平移到 box 上**（`139: iload 9; ifne 158` 跳过）。
   -> 这一支里 box 只被 Z 平移过。
4. **`bl == false` 时**：先算 X 并把 `box` 沿 X 平移；再算 Z，**Z 的结果不平移 box**。
5. 所以「后算的那个轴看到的 box」永远是**只被前一个轴平移过**的 box —— 这就是轴序会改变结果的原因
   （定点用例 TT-5a/TT-5b 把它钉死了）。
6. **某轴没撞到时 delta 怎么取**：`calculateMaxOffset` 返回它收到的 `maxDist` 原值（见 3.1/3.2），
   所以 `dx/dy/dz` 保持 `movement` 的原值。**没有任何「回退成 0」的写法**。
7. 三个轴的 `if (d != 0.0)` 守卫是**位级 != 0**：`-0.0 != 0.0` 为 **false**（JVM `dcmpl` 语义），
   所以 `movement.y == -0.0` **不会**进入 Y 分支。

### 2.3 `1.0E-7` 在本方法里**不出现**

核心方法本身没有 1e-7 常量；它全部来自 `calculateMaxOffset` / `calculateMaxDistance`（第 3 节）与 `Box.stretch`（第 7 节）。
把 1e-7 记在核心这一层是常见的误解，特此声明。

### 2.4 轴帧：`AxisCycleDirection` —— **`between` 是 static，`aload_0` 不是 this**

**这是本规格最重要的一条实测坑**（我在推导时踩过一次，整张轴表整体错位）。

    public static AxisCycleDirection between(Direction$Axis, Direction$Axis);   // 注意 static
       0: getstatic VALUES
       3: aload_1 ; invokevirtual Direction$Axis.ordinal:()I
       7: aload_0 ; invokevirtual Direction$Axis.ordinal:()I
      11: isub
      12: iconst_3 ; invokestatic Math.floorMod:(II)I
      16: aaload ; areturn

`between` **没有 `this`**，所以 `aload_0` 是**第一个形参**、`aload_1` 是第二个形参：

    index = floorMod(second.ordinal - first.ordinal, 3)
    VALUES = [NONE, FORWARD, BACKWARD]     （method_36930 实测顺序）

`VoxelShape.calculateMaxDistance(Direction.Axis axis, ...)` 传的是 `between(axis, X)`：

| 求解轴 | index = floorMod(X - axis, 3) | axisCycle | opposite | xAxis | yAxis | zAxis |
| --- | --- | --- | --- | --- | --- | --- |
| X | 0 | NONE | NONE | **X** | **Y** | **Z** |
| Y | 2 | BACKWARD | FORWARD | **Y** | **Z** | **X** |
| Z | 1 | FORWARD | BACKWARD | **Z** | **X** | **Y** |

（xAxis = opposite.cycle(X)，其余同理。）

`cycle` 的实测（`AxisCycleDirection$2` = FORWARD / `$3` = BACKWARD）：

    FORWARD.cycle(a)  = AXES[floorMod(a.ordinal + 1, 3)]
    BACKWARD.cycle(a) = AXES[floorMod(a.ordinal - 1, 3)]
    NONE.cycle(a)     = a;   NONE.opposite() = NONE;
    FORWARD.opposite() = BACKWARD;  BACKWARD.opposite() = FORWARD;

**第二重印证**：`VoxelSet.inBoundsAndContains(AxisCycleDirection c, int i1,int i2,int i3)` 会把三个下标
喂给 `c.choose(..., AXIS_X/Y/Z)`，而 `choose` 的实参顺序是：

| 实现 | choose 的实参顺序 | 于是 (p,q,r) 映射到 |
| --- | --- | --- |
| `$1` NONE | `Axis.choose(i1, i2, i3)` | `X=p, Y=q, Z=r` |
| `$2` FORWARD | `Axis.choose(i3, i1, i2)` | `X=r, Y=p, Z=q` |
| `$3` BACKWARD | `Axis.choose(i2, i3, i1)` | `X=q, Y=r, Z=p` |

三行与上表的 `xAxis/yAxis/zAxis` **逐一吻合**（`Axis.choose(a,b,c)` = X->a, Y->b, Z->c）。
内核据此只用**一份** `AxisFrame` 表，并在测试里用**两种独立推导**互证（`[frame]` 段 12 项断言）。

---

## 3. `VoxelShapes.calculateMaxOffset` 与 `VoxelShape.calculateMaxDistance`

### 3.1 `calculateMaxOffset`：`1e-7` 短路在**每次迭代的开头**

    public static double calculateMaxOffset(Axis axis, Box box, Iterable<VoxelShape> shapes, double maxDist);
       0: shapes.iterator() -> local 5
       8: if (!it.hasNext()) goto 55
      18: shape = (VoxelShape) it.next()
      30: if (Math.abs(maxDist) < 1.0E-7) { dconst_0 ; dreturn }      // 34: ldc2_w 1.0E-7; dcmpg; ifge 43
      43: maxDist = shape.calculateMaxDistance(axis, box, maxDist)
      52: goto 8
      55: return maxDist

**这条有三处必须记牢**：

1. 检查在**循环体开头**（`shapes` 非空时必有至少一次）。所以**空 Iterable 不会触发它**，直接返回入参。
2. 某个形状把 `|maxDist|` 压到 `< 1e-7` 之后，**下一个形状会让整个调用立刻返回 `0.0`**
   —— 不是返回那个很小的值，是**返回精确的 `0.0`**。
3. 如果那个形状是**最后一个**，循环就结束了，**没有后置检查**，返回的是那个很小的值。
   -> `[A(很小的 d), B]` 与 `[B, A(很小的 d)]` 结果**不同**（定点用例 TT-6a/TT-6b）。

`Math.abs(maxDist) < 1.0E-7` 对 NaN 为 false（`dcmpg` 得 1 -> `ifge` 成立 -> 不短路）。

### 3.2 `VoxelShape.calculateMaxDistance(Axis, Box, double)` 全分支

    public double calculateMaxDistance(Direction$Axis axis, Box box, double maxDist);
       0: return calculateMaxDistance(AxisCycleDirection.between(axis, Axis.X), box, maxDist);

    protected double calculateMaxDistance(AxisCycleDirection cycle, Box box, double maxDist);
       0:  if (isEmpty()) return maxDist;                            // 0-8
       9:  if (Math.abs(maxDist) < 1.0E-7) return 0.0;               // 9-21
      22:  opp = cycle.opposite();
      28:  xa = opp.cycle(X); ya = opp.cycle(Y); za = opp.cycle(Z);  // 28-56
      58:  maxOnX = box.getMax(xa);  minOnX = box.getMin(xa);        // 58-72
      74:  i = getCoordIndex(xa, minOnX + 1.0E-7);                   // 74-86
      88:  j = getCoordIndex(xa, maxOnX - 1.0E-7);                   // 88-100
     102:  k = max(0, getCoordIndex(ya, box.getMin(ya) + 1.0E-7));  // 102-122
     124:  l = min(voxels.getSize(ya), getCoordIndex(ya, box.getMax(ya) - 1.0E-7) + 1);  // 124-154
     156:  m = max(0, getCoordIndex(za, box.getMin(za) + 1.0E-7));  // 156-176
     178:  n = min(voxels.getSize(za), getCoordIndex(za, box.getMax(za) - 1.0E-7) + 1);  // 178-208
     210:  o = voxels.getSize(xa);                                  // 210-219
     221:  if (maxDist > 0.0) {                                     // 221-224  dcmpl; ifle 332
     227:    for (p = j + 1; p < o; p++)
     240:      for (q = k; q < l; q++)
     251:        for (r = m; r < n; r++)
     262:          if (voxels.inBoundsAndContains(opp, p, q, r)) {
     280:            d = getPointPosition(xa, p) - maxOnX;
     293:            if (d >= -1.0E-7) maxDist = Math.min(maxDist, d);
     309:            return maxDist;                            // <-- 无论是否接受都立即返回
                      }
           } else if (maxDist < 0.0) {                              // 332-335  dcmpg; ifge 440
     338:    for (p = i - 1; p >= 0; p--)
     349:      for (q = k; q < l; q++)
     360:        for (r = m; r < n; r++)
     371:          if (voxels.inBoundsAndContains(opp, p, q, r)) {
     389:            d = getPointPosition(xa, p + 1) - minOnX;
     404:            if (d <= 1.0E-7) maxDist = Math.max(maxDist, d);
     420:            return maxDist;
                      }
           }
     440:  return maxDist;

**逐条结论**：

- `maxDist == 0.0`（含 `+0.0` 与 `-0.0`）时**两个分支都不进**，直接返回 0。
- 正方向扫 `p` **升序**、从 `j+1` 到 `o-1`；负方向扫 `p` **降序**、从 `i-1` 到 `0`。
- q/r 的窗口 `[k,l) x [m,n)` 由 box 在另外两个轴上的 `min+1e-7` / `max-1e-7` 的**体素下标**决定，
  并且**被 0 与 `voxels.getSize` 夹住**。窗口为空 => 这一轴永远不撞（哪怕几何上重叠）。
- **命中即返回**：找到第一个含实心体素的 `(p,q,r)` 就 `return`，不再继续扫。
  因此对同一个形状，结果是「沿 p 方向最近的那一格」决定的，**不是**所有格的 min/max。
- 正方向的守卫是 `d >= -1.0E-7`；负方向是 `d <= 1.0E-7`。**符号相反、方向不对称**，不要写成同一个。
- 守卫失败时 `maxDist` 不变，但**照样 return**。

### 3.3 「1e-7」在本子系统里的**三个不同含义**

| 位置 | 表达式 | 含义 |
| --- | --- | --- |
| `calculateMaxOffset` 循环开头 | `Math.abs(maxDist) < 1.0E-7` | **收敛短路**：太小就整体返回 0.0 |
| `calculateMaxDistance` 入口 | `Math.abs(maxDist) < 1.0E-7` | 同上，单形状版本 |
| 扫描边界 | `getCoordIndex(.., boxMin + 1e-7)` / `(.., boxMax - 1e-7)` | **收缩**窗口，避免把「恰好相切」算成重叠 |
| 结果守卫 | 正方向 `d >= -1e-7` / 负方向 `d <= 1e-7` | **容差**：允许把实体拉回相切位置 |
| `Box.stretch` / 遍历盒 | `floor(minX - 1e-7) - 1` 等 | 见 5.2 |

它们**不能互换**。定点用例 TT-2/3/4 与 TT-6a/6b 分别锁死「结果守卫」与「收敛短路」。

### 3.4 `Math.min` / `Math.max` 的 NaN 语义（本机实测，必须自备实现）

    Math.min(NaN,1) = NaN  bits=7ff8000000000000
    Math.min(1,NaN) = NaN  bits=7ff8000000000000
    Math.max(NaN,1) = NaN  bits=7ff8000000000000
    Math.max(1,NaN) = NaN  bits=7ff8000000000000
    Math.min(0.0,-0.0) = -0.0  bits=8000000000000000
    Math.max(0.0,-0.0) = 0.0   bits=0

（复现：`javac -d build/p2-probe build/p2-probe/MathProbe.java` + `java -cp build/p2-probe MathProbe`。）

C++ 的 `std::min/std::max` 在 `min(NaN, x)` 上返回 `x`；`fmin/fmax` 返回另一个操作数。**两者都不对**。
内核因此自带 `java_min` / `java_max`（`native/src/entity/cava_entity.h`），并额外处理 `-0.0`。

### 3.5 `getCoordIndex` **两个子类实现不同**（不要只实现一个）

    // VoxelShape（基类）
    protected int getCoordIndex(Direction$Axis axis, double coord) {
        return MathHelper.binarySearch(0, voxels.getSize(axis) + 1,
                                       i -> coord < getPointPosition(axis, i)) - 1;
    }

    // SimpleVoxelShape（覆写！）
    protected int getCoordIndex(Direction$Axis axis, double coord) {
        int n = voxels.getSize(axis);
        return MathHelper.floor(MathHelper.clamp(coord * (double)n, -1.0, (double)n));
    }

    // MathHelper.binarySearch(start,length,pred)：
    //   i = length - start; while (i > 0) { j = i/2; k = start + j;
    //       if (pred(k)) i = j; else { start = k+1; i = i-j-1; } } return start;
    //   -> 返回 [start,length) 里第一个让 pred 为真的下标；没有则返回 length。

二者在「`coord * n` 恰好进位到整数」时结果会差 1，**是可观测差异**，不能合并。

`getPointPositions` 也有两套：`SimpleVoxelShape` 用 `FractionalDoubleList(n)`，`getDouble(i) = (double)i / (double)n`；
`ArrayVoxelShape` 用显式 `DoubleList`。

**1.20.4 里参与碰撞求解的 `VoxelShape` 子类只有这两个**：
`VoxelShapes.combine` 的两条返回路径实测只有 `SimpleVoxelShape`（三个 PairList 都是 `FractionalPairList` 时）
与 `ArrayVoxelShape`（否则），`DisjointVoxelShape` **不存在**（javap 报找不到类）。

### 3.6 `VoxelSet` / `BitSetVoxelSet` 的存储布局

    BitSetVoxelSet.getIndex(x,y,z) = (x * sizeY + y) * sizeZ + z
    BitSetVoxelSet.contains(x,y,z) = storage.get(getIndex(x,y,z))
    VoxelSet.inBoundsAndContains(x,y,z) = 0<=x<sizeX && 0<=y<sizeY && 0<=z<sizeZ && contains(x,y,z)
    BitSetVoxelSet.isEmpty() = storage.isEmpty()          // 覆写：位图全 0
    VoxelSet.getSize(axis)   = Axis.choose(sizeX, sizeY, sizeZ)

-> 一张覆盖 `[0,sizeX) x [0,sizeY) x [0,sizeZ)` 的位图**足以精确描述**一个 `BitSetVoxelSet`，
不需要 `minX..maxZ` 缓存字段（那只是 `getMin/getMax` 用的，`calculateMaxDistance` 不读）。

### 3.7 `VoxelShapes.cuboid` 的**两条互斥构造路径**（决定形状是 FRACTIONAL 还是 EXPLICIT）

    static int findRequiredBitResolution(double d, double e) {
        if (d < -1.0E-7 || e > 1.0000001) return -1;       // <-- 早退，极易漏读
        for (int i = 0; i <= 3; i++) {
            int j = 1 << i;
            boolean bl  = Math.abs(d*j - Math.round(d*j)) < 1.0E-7 * j;
            boolean bl2 = Math.abs(e*j - Math.round(e*j)) < 1.0E-7 * j;
            if (bl && bl2) return i;
        }
        return -1;
    }

    static VoxelShape cuboidUnchecked(double x1,double y1,double z1,double x2,double y2,double z2) {
        if (x2-x1 < 1e-7 || y2-y1 < 1e-7 || z2-z1 < 1e-7) return EMPTY;
        int i = fbr(x1,x2), j = fbr(y1,y2), k = fbr(z1,z2);
        if (i < 0 || j < 0 || k < 0) return new ArrayVoxelShape(FULL_CUBE.voxels, [x1,x2],[y1,y2],[z1,z2]);
        if (i == 0 && j == 0 && k == 0) return FULL_CUBE;
        int l = 1<<i, m = 1<<j, n = 1<<k;
        return new SimpleVoxelShape(BitSetVoxelSet.create(l,m,n,
            round(x1*l), round(y1*m), round(z1*n), round(x2*l), round(y2*m), round(z2*n)));
    }

**实测推论（我第一版读错了两处，记录下来）**：

- **`(0.375, 0.625) -> 3 (size 8)`**，但 **`(0, 1.5) -> -1`**（`e = 1.5 > 1.0000001` 早退）。
  所以真正的栅栏碰撞盒 `0.375..0.625 x 0..1.5 x 0.375..0.625` **不是** 8 分格的 `SimpleVoxelShape`，
  而是 **`ArrayVoxelShape` + 显式点表 `{0.375,0.625}/{0,1.5}/{0.375,0.625}`**（走基类 `getCoordIndex` 的二分）。
- 任何**负坐标**的 cuboid 也一律落到显式点表分支（`d < -1e-7` 早退），点表就是**精确**的 `x1/x2`。

### 3.8 `VoxelShape.offset` 会把形状**变成 `ArrayVoxelShape`**

    public VoxelShape offset(double dx, double dy, double dz) {
        if (isEmpty()) return VoxelShapes.empty();
        return new ArrayVoxelShape(this.voxels,
            new OffsetDoubleList(getPointPositions(X), dx),   // getDouble(i) = base.getDouble(i) + dx
            new OffsetDoubleList(getPointPositions(Y), dy),
            new OffsetDoubleList(getPointPositions(Z), dz));
    }

**关键**：体素集**共享不变**，但点表变成「显式」、类变成 `ArrayVoxelShape`。
而 `getBlockCollisions` 发出的正是 `shape.offset(x,y,z)`（见 5.4），
**所以内层求解看到的形状 100% 是 EXPLICIT 点表** —— 即使该方块的原始碰撞盒是 `SimpleVoxelShape`。
（唯一的例外是 `VoxelShapes.fullCube()` 那条快路径，见 5.4。）

---

## 4. 形状来源与顺序：`method_20736`

### 4.1 三批来源，顺序**有语义**

    public static Vec3d adjustMovementForCollisions(Entity entity, Vec3d movement, Box box,
                                                    World world, List<VoxelShape> entityCollisions);
       0:  builder = ImmutableList.builderWithExpectedSize(entityCollisions.size() + 1);   // 0-12
      14:  if (!entityCollisions.isEmpty()) builder.addAll(entityCollisions);              // 14-31
      32:  WorldBorder wb = world.getWorldBorder();                                       // 32-36
      38:  boolean bl = entity != null                                                      // 38-61
                       && wb.canCollide(entity, box.stretch(movement));
      63:  if (bl) builder.add(wb.asVoxelShape());                                        // 63-78
      79:  builder.addAll(world.getBlockCollisions(entity, box.stretch(movement)));        // 79-94
      95:  return adjustMovementForCollisions(movement, box, builder.build());              // 95-105

**三个必须记住的点**：

1. **entityCollisions 在最前**。它们是 `World.getEntityCollisions` 的返回值（实体碰撞箱）。
   注意 `if (!isEmpty()) addAll(...)` —— 空表**不调用 addAll**（对惰性 Iterable 有副作用差别，对结果无差别）。
2. **`worldBorder` 是第三个输入源**，不能漏。它只在 `entity != null && canCollide(...)` 时追加**一个**形状。
3. **方块形状最后**，且是**惰性** `Iterable`（`addAll` 会**立即**把它迭代完，因为 `ImmutableList.Builder.addAll` 会遍历）。

**为什么顺序有语义**：`calculateMaxOffset` 的收敛短路（3.1 第 2 条）使「先出现的形状」可能让后面的形状完全不被考虑；
而 `min`/`max` 的折叠又让最终值只取决于**被接受的那些 d**。所以**换顺序 = 换结果**（定点用例 TT-6 是直接证据）。

### 4.2 `worldBorder.canCollide` 与 `asVoxelShape`

    public boolean canCollide(Entity entity, Box box) {                       // WorldBorder 295-324
        double d = Math.max(1.0, MathHelper.absMax(box.getLengthX(), box.getLengthZ()));
        return this.getDistanceInsideBorder(entity) < d * 2.0
            && this.contains(entity.getX(), entity.getZ(), d);
    }

    public double getDistanceInsideBorder(double x, double z) {
        double d = z - getBoundNorth(), e = getBoundSouth() - z;
        double f = x - getBoundWest(),  g = getBoundEast() - x;
        double h = Math.min(f, g); h = Math.min(h, d); return Math.min(h, e);   // 顺序 (min(f,g), d, e)
    }

    public boolean contains(double x, double z, double margin) {              // 159-192
        return x > getBoundWest() - margin && x < getBoundEast() + margin
            && z > getBoundNorth() - margin && z < getBoundSouth() + margin;
    }

    public VoxelShape asVoxelShape() { return this.area.asVoxelShape(); }

`WorldBorder$StaticArea.recalculateBounds()`（字节码 82-184）实测：

    boundWest  = clamp(centerX - size/2, -maxRadius, +maxRadius)
    boundNorth = clamp(centerZ - size/2, -maxRadius, +maxRadius)
    boundEast  = clamp(centerX + size/2, -maxRadius, +maxRadius)
    boundSouth = clamp(centerZ + size/2, -maxRadius, +maxRadius)
    shape = VoxelShapes.combineAndSimplify(
                VoxelShapes.UNBOUNDED,
                VoxelShapes.cuboid(floor(boundWest), NEGATIVE_INFINITY, floor(boundNorth),
                                   ceil(boundEast),  POSITIVE_INFINITY, ceil(boundSouth)),
                BooleanBiFunction.ONLY_FIRST);      // = UNBOUNDED 减去内部盒 = 边界之外

`VoxelShapes.UNBOUNDED = cuboid(-inf,-inf,-inf, +inf,+inf,+inf)`，落显式点表分支，点表 = `{-inf, +inf}`。

**给原生侧的两条硬结论**：

1. 世界边界形状的**点表含 ±Infinity**。`calculateMaxDistance` 里会出现 `-inf - (+inf) = -inf`、
   `+inf - (+inf) = NaN` 这类值，NaN 会经 `d >= -1e-7`（false）被拒绝。**内核必须原样支持 ±Inf/NaN**，不能假设有限。
2. 它是在 `recalculateBounds()` 里**构造一次并缓存**的（`asVoxelShape()` 只返回字段），
   **不是每 tick 重建**。原生侧要复刻的话必须同样缓存，否则形状表长度/身份会漂移。

### 4.3 `World` 上的重载

`javap -p -c net.minecraft.world.World` 里**没有** `getEntityCollisions` / `getBlockCollisions` 的声明，
两者都来自接口默认实现：`EntityView.getEntityCollisions`（抽象，`World` 实现）与
`CollisionView.getBlockCollisions`（default，见第 5 节）。
**给 Java 侧注入流的提醒**：`getEntityCollisions` 的返回值必须是 `List`（不是惰性视图）且顺序稳定 ——
它直接决定形状表顺序。

---

## 5. `CollisionView.getBlockCollisions`（`method_20812`）的遍历顺序

### 5.1 默认实现

    public default Iterable<VoxelShape> getBlockCollisions(Entity entity, Box box) {
        return () -> new BlockCollisionSpliterator(this, entity, box, false,
                       (pos, state) -> state.getCollisionShape(this, pos, ShapeContext.of(entity)));
    }

字节码 169-175：`invokedynamic #163 iterator:(CollisionView;Entity;Box)Iterable`，
lambda 体即 `new BlockCollisionSpliterator(world, entity, box, false, biFunction)`。

**第 4 个布尔实参是 `forEntity = false`**：所以 `getBlockCollisions` 这一路**不做** `shouldSuffocate` 过滤。
（`CollisionView.canCollide` 与 `findSupportingBlockPos` 传的是 `true`，见字节码 193-216 / 217-267。）

### 5.2 `BlockCollisionSpliterator` 的遍历盒

构造函数字节码 63-173：

    xMin = floor(box.minX - 1e-7) - 1 ;  xMax = floor(box.maxX + 1e-7) + 1
    yMin = floor(box.minY - 1e-7) - 1 ;  yMax = floor(box.maxY + 1e-7) + 1
    zMin = floor(box.minZ - 1e-7) - 1 ;  zMax = floor(box.maxZ + 1e-7) + 1
    blockIterator = new CuboidBlockIterator(xMin, yMin, zMin, xMax, yMax, zMax)

（`MathHelper.floor` 是 `(int)d` + 修正，**不是** `Math.floor`。）

### 5.3 `CuboidBlockIterator.step()` —— **x 最快、z 最慢**（**极易读反**）

    public boolean step() {
        if (blocksIterated == totalSize) return false;
        this.x = blocksIterated % sizeX;
        int i   = blocksIterated / sizeX;
        this.y = i % sizeY;
        this.z = i / sizeY;
        blocksIterated++;
        return true;
    }

所以真实坐标的推进顺序是 **z 外层、y 中层、x 内层**：

    for z in [zMin, zMax]: for y in [yMin, yMax]: for x in [xMin, xMax]: yield (x,y,z)

**这是最容易被直觉写反的一处**：MC 里大量手写循环都是 `for x { for y { for z } }`（z 最快），
而这里恰好相反。**形状列表的顺序**（进而求解结果）由它决定。

### 5.4 `computeNext()` 的过滤与发射（字节码 155-303）

    循环 step()：
      (x,y,z) = it.getX/getY/getZ;  edge = it.getEdgeCoordinatesCount()
      if (edge == 3) continue;                       // 角点直接跳过（45: iconst_3; if_icmpne 52）
      chunk = getChunk(x, z); if (chunk == null) continue;
      state = chunk.getBlockState(pos.set(x,y,z))
      if (forEntity && !state.shouldSuffocate(chunk, pos)) continue;   // 本路径 forEntity=false，不走
      if (edge == 1 && !state.exceedsCube()) continue;
      if (edge == 2 && !state.isOf(Blocks.MOVING_PISTON)) continue;
      shape = state.getCollisionShape(world, pos, context)
      if (shape == VoxelShapes.fullCube()) {                              // 172-177  **引用比较**
          if (!box.intersects(x, y, z, x+1, y+1, z+1)) continue;          // 180-205
          return resultFunction.apply(pos, shape.offset(x, y, z));        // 208-232
      }
      offset = shape.offset(x, y, z);
      if (offset.isEmpty()) continue;
      if (!VoxelShapes.matchesAnywhere(offset, boxShape, AND)) continue;
      return resultFunction.apply(pos, offset);                           // 269-284

其中 `boxShape = VoxelShapes.cuboid(box)` 在构造函数里算好（字节码 33-43）。

**四条硬结论**：

1. **发射的形状永远是 `shape.offset(x,y,z)`**（新对象），不是方块状态里缓存的那个 `VoxelShape`。
   因此内层求解看到的是 **`ArrayVoxelShape` + 显式点表**（见 3.8）。
2. `shape == VoxelShapes.fullCube()` 是**引用相等**（`if_acmpne`），不是 `equals`。
   实测 `cuboidUnchecked` 在 `i==j==k==0` 时会**返回 `fullCube()` 单例**，
   所以值等于整方块的那些状态确实会命中快路径；但 mod 自己 `new` 出来的等价形状**不会**。
   快路径下**不再做 `matchesAnywhere`**，只做一次 `Box.intersects(x,y,z,x+1,y+1,z+1)`。
3. `edge == 1` 要求 `state.exceedsCube()`；`edge == 2` 要求 `state.isOf(Blocks.MOVING_PISTON)`；`edge == 3` 全跳过。
   **这三条是「贴边的方块要不要参与碰撞」的判据，漏掉会多算或漏算整批形状。**
4. 惰性：`Iterable` 只有在被迭代时才推进。`method_20736` 的 `builder.addAll(...)` 会**一次性迭代完**。

### 5.5 传给 `getBlockCollisions` 的盒子 = `box.stretch(movement)`

`method_20736` 传的是**拉伸后的盒子**，而不是实体当前碰撞箱。`stretch` 的语义见第 7 节。

---

## 6. `Entity.move`（`method_5784`）的完整顺序

### 6.1 逐段（字节码 0-994，行号对 `javap -p -c net.minecraft.entity.Entity` 第 1272 行起）

| 偏移 | 动作 | 备注 |
| --- | --- | --- |
| 0-38 | `if (noClip) { setPosition(pos + movement); return; }` | 无碰撞直通 |
| 39-44 | `wasOnFire = isOnFire()` | |
| 47-70 | `if (type == MovementType.PISTON) { movement = adjustMovementForPiston(movement); if (movement.equals(Vec3d.ZERO)) return; }` | `Vec3d.ZERO` 比较走 `Vec3d.equals`（逐位 `Double.compare`） |
| 71-81 | `profiler.push('move')` | |
| 86-122 | `if (movementMultiplier.lengthSquared() > 1.0E-7) { movement = movement.multiply(movementMultiplier); movementMultiplier = ZERO; setVelocity(ZERO); }` | |
| 123-129 | `movement = adjustMovementForSneaking(movement, type)` | 虚方法；`Entity` 默认实现直接返回入参 |
| **130-135** | **`Vec3d vec3d = adjustMovementForCollisions(movement)`** | **`method_17835`，几何求解在这一句** |
| 136-140 | `double d = vec3d.lengthSquared()` | |
| 142-247 | `if (d > 1.0E-7) { ... }` 见下 | 位移很小就**不 setPosition**、不触发落地 |
| 142-216 | `if (fallDistance != 0.0F && d >= 1.0) { world.raycast(RaycastContext(getPos(), getPos().add(vec3d), FALLDAMAGE_RESETTING, WATER, this)); if (type != MISS) onLanding(); }` | **`onLanding` 只在这一支里** |
| 217-247 | `setPosition(getX()+vec3d.x, getY()+vec3d.y, getZ()+vec3d.z)` | **位置更新在 onLanding 之后** |
| 248-259 | `profiler.pop()` | |
| 260-274 | `profiler.push('rest')` | |
| 275-294 | `bl  = !MathHelper.approximatelyEquals(movement.x, vec3d.x)` | 容差 **9.999999747378752E-6** |
| 296-315 | `bl2 = !MathHelper.approximatelyEquals(movement.z, vec3d.z)` | |
| 317-334 | `horizontalCollision = bl || bl2` | **用 approximatelyEquals，不是 `!=`** |
| 336-345 | `verticalCollision = movement.y != vec3d.y` | **这一条用的是精确 `!=`** |
| 347-379 | `groundCollision = verticalCollision && movement.y < 0.0` | |
| 382-403 | `collidedSoftly = horizontalCollision ? hasCollidedSoftly(vec3d) : false` | `Entity` 默认返回 false |
| 406-412 | `setOnGround(groundCollision, vec3d)` | -> `updateSupportingBlockPos` -> `world.findSupportingBlockPos` |
| 415-419 | `BlockPos landing = getLandingPos()` | `getPosWithYOffset(0.2F)` |
| 421-427 | `BlockState state = world.getBlockState(landing)` | |
| 432-445 | `fall(vec3d.y, isOnGround(), state, landing)` | |
| 448-467 | `if (isRemoved()) { profiler.pop(); return; }` | **`fall` 里可能移除实体（虚空/岩浆）** |
| 468-517 | `if (horizontalCollision) { v = getVelocity(); setVelocity(bl?0:v.x, v.y, bl2?0:v.z); }` | |
| 518-524 | `Block block = state.getBlock()` | |
| 525-546 | `if (movement.y != vec3d.y) block.onEntityLand(world, this)` | **Y 位移被改过**才调 |
| 547-567 | `if (isOnGround()) block.onSteppedOn(world, landing, state, this)` | |
| 568-580 | `MoveEffect effect = getMoveEffect()` | |
| 581-853 | `if (effect.hasAny() && !hasVehicle()) { ... }` 见下 | 脚步声/游泳/统计 |
| 589-605 | 局部 12/14/16 = `vec3d.x / vec3d.y / vec3d.z` | |
| 607-622 | `speed += (float) vec3d.length() * 0.6F` | |
| 625-640 | `steppingPos = getSteppingPos(); steppingState = world.getBlockState(steppingPos)` | |
| 642-656 | `canClimb = this.canClimb(steppingState); double e = canClimb ? 0.0 : vec3d.y;` | 局部 14 被覆写 |
| 658-672 | `horizontalSpeed += (float) vec3d.horizontalLength() * 0.6F` | |
| 675-705 | `distanceTraveled += (float) Math.sqrt(l12*l12 + e*e + l16*l16) * 0.6F` | |
| 708-838 | 步声/游泳音效分支；`stepOnBlock(landing, state, playsSounds, landing.equals(steppingPos), movement)` | |
| **853-854** | **`tryCheckBlockCollision()`** | **-> `checkBlockCollision()` -> `BlockState.onEntityCollision`（1.20.4 的 entityInside）** |
| 857-878 | `setVelocity(getVelocity().multiply(getVelocityMultiplier()))` | |
| 881-951 | 火/细雪熄灭判定（`getStatesInBoxIfLoaded(boundingBox.contract(1.0E-6))`） | |
| 952-981 | `if (isOnFire() && (inPowderSnow || isWet())) setFireTicks(-getBurningDuration())` | |
| 982-994 | `profiler.pop()`；返回 | |

### 6.2 `method_17835`（实例 `adjustMovementForCollisions(Vec3d)`，含台阶分支）

这是 `move` 真正调用的那一个；内核 `resolve_movement` 与它逐条对应。

    0:   box = getBoundingBox()
    5:   entityCollisions = world.getEntityCollisions(this, box.stretch(movement))
    19:  result = (movement.lengthSquared() == 0.0)
                     ? movement
                     : adjustMovementForCollisions(this, movement, box, world, entityCollisions)
    45:  bl  = movement.x != result.x            // 局部 5（精确 !=，不是 approximatelyEquals）
    65:  bl2 = movement.y != result.y            // 局部 6
    85:  bl3 = movement.z != result.z            // 局部 7
    105: bl4 = isOnGround() || (bl2 && movement.y < 0.0)     // 局部 8
    133: if (getStepHeight() > 0.0F && bl4 && (bl || bl3)) {
    157:     vec3d  = adjustMovementForCollisions(this, new Vec3d(movement.x, stepHeight, movement.z),
                                              box, world, entityCollisions)
    189:     vec3d2 = adjustMovementForCollisions(this, new Vec3d(0.0, stepHeight, 0.0),
                                              box.stretch(movement.x, 0.0, movement.z), world, entityCollisions)
    227:     if (vec3d2.y < (double) getStepHeight()) {
    241:         vec3d3 = adjustMovementForCollisions(this, new Vec3d(movement.x, 0.0, movement.z),
                                                  box.offset(vec3d2), world, entityCollisions)
                                   .add(vec3d2)
    279:         if (vec3d3.horizontalLengthSquared() > vec3d.horizontalLengthSquared()) vec3d = vec3d3
             }
    297:     if (vec3d.horizontalLengthSquared() > result.horizontalLengthSquared()) {
    311:         return vec3d.add(adjustMovementForCollisions(this,
                     new Vec3d(0.0, -vec3d.y + movement.y, 0.0), box.offset(vec3d), world, entityCollisions));
             }
         }
    352: return result

**注意**：台阶分支里 `adjustMovementForCollisions(this, ...)` 是**同一个五参静态方法**，
因此**形状表被复用（同一个 `entityCollisions` 列表对象）**，但 `world.getBlockCollisions` 会因为
`box.stretch(...)` 不同而**重新遍历一遍**。-> 原生侧每次进入这一支都是**新的方块查询**。

### 6.3 1.20.4 里**不存在**的名字（任务书 / 1.21 记忆里的）

    PS> javap -p -c -classpath <jar> net.minecraft.entity.Entity | Select-String 'entityInside' -SimpleMatch
    Entity 中 entityInside 命中数 = 0
    Entity 中 updateEntityMovementAfterFallOn 命中数 = 0
    Entity 中 limitPistonMovement 命中数 = 0
    Entity 中 maybeBackOffFromEdge 命中数 = 0

1.20.4 对应的点是：

| 1.21 名字 | 1.20.4 的等价物 |
| --- | --- |
| `Entity.entityInside(BlockState, BlockPos)` | **不存在**；对应 `BlockState.onEntityCollision(World, BlockPos, Entity)`，由 `checkBlockCollision()` 派发 |
| `Entity.limitPistonMovement` | 不存在；活塞位移走 `MovementType.PISTON` + `adjustMovementForPiston` |
| `Entity.maybeBackOffFromEdge` | 不存在；边缘回退走 `adjustMovementForSneaking`（默认实现直通） |
| `updateEntityMovementAfterFallOn` | 不存在；落地回调是 `Block.onEntityLand` / `Block.onSteppedOn` / `Entity.fall` |

`checkBlockCollision()`（字节码 2576-2694）实测：

    Box box = getBoundingBox();
    BlockPos a = BlockPos.ofFloored(box.minX + 1e-7, box.minY + 1e-7, box.minZ + 1e-7);
    BlockPos b = BlockPos.ofFloored(box.maxX - 1e-7, box.maxY - 1e-7, box.maxZ - 1e-7);
    if (world.isRegionLoaded(a, b)) {
        Mutable pos = new Mutable();
        for (int x = a.getX(); x <= b.getX(); x++)          // !!! x 外层
          for (int y = a.getY(); y <= b.getY(); y++)
            for (int z = a.getZ(); z <= b.getZ(); z++) {    // !!! z 内层（与 5.3 相反！）
                pos.set(x,y,z);
                BlockState st = world.getBlockState(pos);
                st.onEntityCollision(world, pos, this);      // 2660
                this.onBlockCollision(st);                   // 2663, Entity 默认空实现
            }
    }

**注意这里与 5.3 的顺序相反**：`checkBlockCollision` 是 `for x { for y { for z } }`（z 最快），
而 `CuboidBlockIterator` 那一路是 z 最慢。**两处不能共用一个遍历器。**

同时注意它扫的是**移动之后**的碰撞箱（`getBoundingBox()` 已被 `setPosition` 更新），
并且**用 ±1e-7 收缩**，与 `getBlockCollisions` 的 `floor(±1e-7)±1` 又是**两套不同的边界**。

### 6.4 两个不同的近似阈值

    MathHelper.approximatelyEquals(a,b) = Math.abs(b - a) < 9.999999747378752E-6

（`9.999999747378752E-6` 是 `1.0E-5f` 提升为 double 的位模式。）
`move` 里 `horizontalCollision` 用它，而 `verticalCollision` 用**精确 `!=`**，`bl/bl2/bl3` 也用精确 `!=`。
**这四个判定的阈值不同，不能统一。**

---

## 7. `Box` 的相关方法 / `VoxelShape.getBoundingBoxes` / `ShapeContext`

### 7.1 `Box.stretch(double dx, double dy, double dz)`（字节码 589-670）

    double minX=this.minX, minY=..., minZ=..., maxX=this.maxX, maxY=..., maxZ=...;
    if (dx < 0) minX += dx; else if (dx > 0) maxX += dx;
    if (dy < 0) minY += dy; else if (dy > 0) maxY += dy;
    if (dz < 0) minZ += dz; else if (dz > 0) maxZ += dz;
    return new Box(minX, minY, minZ, maxX, maxY, maxZ);

**NaN 的落点（我第一版读反过一次）**：`dcmpg` 对 NaN 得 1 -> `ifge` 成立 -> **跳过第一个分支**；
再 `dcmpl` 对 NaN 得 **-1** -> `ifle` 成立 -> **也跳过第二个分支**。
**所以 `stretch(NaN, ...)` 在该轴上什么都不做。**
（C++ 里 `NaN < 0` 与 `NaN > 0` 都是 false，行为天然一致 —— 内核无需特判。）

### 7.2 `Box.offset` / `Box.contract` / `Box.intersects`

- `offset(dx,dy,dz)` = 六个数各自 `+`（逐分量加法，无重排）。
- `intersects(x1,y1,z1,x2,y2,z2)`：六个**严格**比较（不加 1e-7）：
  `minX < x2 && maxX > x1 && minY < y2 && maxY > y1 && minZ < z2 && maxZ > z1`。
  **恰好相切 -> false**（`<`/`>` 严格）。

### 7.3 `VoxelShape.getBoundingBoxes()`

    public List<Box> getBoundingBoxes() {
        List<Box> list = Lists.newArrayList();
        forEachBox((x1,y1,z1,x2,y2,z2) -> list.add(new Box(x1,y1,z1,x2,y2,z2)));
        return list;
    }

`forEachBox` 走 `VoxelSet.forEachBox(PositionBiConsumer, true)` -> `BitSetVoxelSet.forEachBox`，
把**相邻的实心格合并**成大盒后回调（字节码 425-562：按 y 外层 / x 中层 / z 内层扫描列，再纵向与横向合并）。
**遍历顺序是 `for y { for x { for z } }`**（与 5.3 的 z 最慢又不同）。

**注意**：`getBoundingBoxes()` 返回的是**体素合并后的大盒列表**，
它**不是** `calculateMaxDistance` 用的那套点表/位图。
把它当成求解输入会引入 <1e-7 的量化误差（见 3.7）。
**本内核因此不用 `getBoundingBoxes`；它只在射线路径（8.5）里出现。**

`getBoundingBox()`（单数）在 `isEmpty()` 时**抛 `UnsupportedOperationException('No bounds for empty shape.')`**
（经 `Util.throwOrPause`）。**任何调用点都必须先 `isEmpty()`。**

### 7.4 `ShapeContext` / `EntityShapeContext`

    public static ShapeContext of(Entity entity) { return new EntityShapeContext(entity); }
    public static ShapeContext absent() { return EntityShapeContext.ABSENT; }

`BlockCollisionSpliterator` 构造函数里：`entity == null ? ShapeContext.absent() : ShapeContext.of(entity)`（字节码 27-34）。
它被传给 `BlockState.getCollisionShape(view, pos, context)`，
**对少数方块（`BambooBlock` / `PointedDripstoneBlock` / `ScaffoldingBlock`）会改变碰撞盒**
（`docs/CAVA-gates.md` 门禁 #7 记录：114 个类声明了带上下文的形状方法，实测只有 3 个类 / 64 个状态真的会变）。

-> **形状的来源必须在 Java 侧解决**，原生侧只接受已算好的形状（这也是本内核的边界）。

---

## 8. 射线（`Entity.raycast` / `BlockView.raycast`）

### 8.1 `Entity.raycast(double maxDistance, float tickDelta, boolean includeFluids)`（字节码 4554-4594）

    Vec3d start = getCameraPosVec(tickDelta);
    Vec3d dir   = getRotationVec(tickDelta);
    Vec3d end   = start.add(dir.x * maxDistance, dir.y * maxDistance, dir.z * maxDistance);
    return getWorld().raycast(new RaycastContext(start, end, ShapeType.OUTLINE,
                                                includeFluids ? FluidHandling.ANY : FluidHandling.NONE, this));

**结论：1.20.4 的 `Entity.raycast` 只看方块/流体，完全不看实体。**
「方块与实体的优先级」在 1.20.4 里**不在这个方法里** —— 实体射线在
`ProjectileUtil` / `ServerPlayNetworkHandler` 等调用点各自处理。
任务书里写的「Entity.raycast 里方块与实体的优先级」**在 1.20.4 不成立**，已在第 9 节记为更正。

### 8.2 `BlockView.raycast`（= 老的 `clip`）

    public static <T,C> T raycast(Vec3d start, Vec3d end, C context,
                                  BiFunction<C, BlockPos, T> blockHitFactory, Function<C, T> missFactory);
       0: if (start.equals(end)) return missFactory.apply(context);
      17: d = lerp(-1.0E-7, end.x, start.x)   // MathHelper.lerp(delta,start,end) = start + delta*(end-start)
      33: e = lerp(-1.0E-7, end.y, start.y)
      49: f = lerp(-1.0E-7, end.z, start.z)
      65: g = lerp(-1.0E-7, start.x, end.x)
      81: h = lerp(-1.0E-7, start.y, end.y)
      97: i = lerp(-1.0E-7, start.z, end.z)
     113: j = floor(g); k = floor(h); l = floor(i)
     134: pos = new BlockPos.Mutable(j,k,l)
     149: t = blockHitFactory.apply(context, pos);  if (t != null) return t;   // 起点方块先查一次
     168: m = g - d; n = h - e; o = i - f;
     189: p = MathHelper.sign(m); q = sign(n); r = sign(o);
     210: s = (p == 0) ? Double.MAX_VALUE : (double)p / m;    // 其余两轴同理
     267: w = s * (p > 0 ? 1.0 - fractionalPart(g) : fractionalPart(g));
     292: x = u * (q > 0 ? 1.0 - fractionalPart(h) : fractionalPart(h));
     317: y = v * (r > 0 ? 1.0 - fractionalPart(i) : fractionalPart(i));
     342: while (w <= 1.0 || x <= 1.0 || y <= 1.0) {           // <<< 见 8.3
     363:     if (w < x) { if (w < y) { j += p; w += s; } else { l += r; y += v; } }
     413:     else       { if (x < y) { k += q; x += u; } else { l += r; y += v; } }
     452:     t2 = blockHitFactory.apply(context, pos.set(j,k,l));
     472:     if (t2 != null) return t2;
           }
     483: return missFactory.apply(context);

**tie-breaking（只影响步进方向，不影响「哪个方块先被访问」）**：

| 比较 | 分支 |
| --- | --- |
| `w < x` | 取 X 步进；**相等时落到 else** |
| `w < y`（在 `w<x` 内） | 取 X；**相等时取 Z** |
| `x < y`（在 `w>=x` 内） | 取 Y；**相等时取 Z** |

即 **X 与 Z 同时最小时优先 Z**（因为 `w < y` 的 else）。这与「先 X 后 Z」的直觉相反，
是**真实可观测**的方向差异（例如 `movement = (1,0,1)` 的对角射线）。

### 8.3 循环条件是 **OR** 不是 AND（**javac 判定实验钉死**）

原版字节码（342-363）：

    342: dload 37 (w) ; dconst_1 ; dcmpg ; ifle 363     // w <= 1 -> 进循环体
    349: dload 39 (x) ; dconst_1 ; dcmpg ; ifle 363     // x <= 1 -> 进循环体
    356: dload 41 (y) ; dconst_1 ; dcmpg ; ifgt 483     // 三轴都 > 1 -> 退出（miss）
    363: <循环体>

我把两种候选源码形状编出来对比（`build/p2-probe/LoopShapeProbe.java`，javac 21 + javap）：

    while (w <= 1 && x <= 1 && y <= 1)   ->  dcmpg; ifgt END   x3          （candidateAllLe）
    while (w <= 1 || x <= 1 || y <= 1)   ->  dcmpg; ifle BODY  x2，dcmpg; ifgt END  （candidateAnyLe）

原版是**第二种形状**。-> **循环条件是 `w <= 1.0 || x <= 1.0 || y <= 1.0`**。

（我第一版按记忆写成 `&&`；这正是「凭记忆写语义」的典型。**这条已经用判定性实验而不是记忆定死。**）

`dcmpg` 的 NaN 语义：NaN 得 1 -> `ifle` 不成立、`ifgt` 成立 -> 只要有一轴是 NaN 就**立刻退出**。

### 8.4 `BlockView.raycastBlock` 的 tie-breaking（字节码 77-116）

    BlockHitResult a = state.getCollisionShape(view,pos).raycast(start,end,pos);   // 碰撞形状
    if (a == null) return null;
    BlockHitResult b = state.getRaycastShape(view,pos).raycast(start,end,pos);     // 视觉轮廓形状
    if (b != null && b.getPos().subtract(start).lengthSquared()
                  < a.getPos().subtract(start).lengthSquared()) {
        return a.withSide(b.getSide());          // 位置取碰撞形状的，朝向取轮廓形状的
    }
    return a;

**注意返回的是 `a`（碰撞形状的结果）**，只把 `side` 换成 `b` 的 —— 这是原版一个刻意的怪癖。

### 8.5 `VoxelShape.raycast(start, end, pos)`（字节码 331-407）

    if (isEmpty()) return null;
    Vec3d dir = end.subtract(start);
    if (dir.lengthSquared() < 1.0E-7) return null;
    Vec3d p = start.add(dir.multiply(0.001));
    if (voxels.inBoundsAndContains(
            getCoordIndex(X, p.x - pos.getX()),
            getCoordIndex(Y, p.y - pos.getY()),
            getCoordIndex(Z, p.z - pos.getZ()))) {
        return new BlockHitResult(p, Direction.getFacing(dir.x,dir.y,dir.z).getOpposite(), pos, true);
    }
    return Box.raycast(this.getBoundingBoxes(), start, end, pos);

**这里 `getCoordIndex` 和 `calculateMaxDistance` 用的是同一个实现**（同一份子类覆写），
所以 3.5 那条「两个子类实现不同」对射线同样生效。
**若把射线也搬到原生，必须复用同一份 `shape_coord_index`。**

---

## 9. 本规格明确**没有**证实的东西（留白 / 更正）

1. **§7.1 `Box.stretch` 的 NaN 分支是我在写文档时更正过的**：第一版写「NaN 加到 max」，
   回读字节码 `dcmpl; ifle 63` 后确认是「NaN **两个分支都不进**」。
   这一条**没有定点用例覆盖**。**未验证**。
2. **`World.getEntityCollisions` 的具体实现**（`EntityView` 的抽象方法在 `World` / `ServerWorld` 里的重载）
   **没有读**。它对形状表顺序有直接影响。**未验证。**
3. **`VoxelShapes.combine` 的完整 `PairList` 语义**只读了返回类型分支（3.5），
   `createListPair` / `BitSetVoxelSet.combine` 的内部**没有逐条转写**。
   本内核**不构造形状**，只接受 Java 侧算好的点表+位图，所以不受影响；
   但**Java 侧若要自己造世界边界形状，必须用原版 `VoxelShapes.combineAndSimplify`，不能自己拼点表**。
4. **`moving` 状态的 `WorldBorder$MovingArea`**（`interpolateSize` 期间）**没有读**。
   它在 `getAreaInstance()` 切换时构造形状的时机可能与 `StaticArea` 不同。**未验证。**
5. **mod 自定义的 `VoxelShape` 子类**：1.20.4 原版只有 `SimpleVoxelShape` / `ArrayVoxelShape` 参与求解，
   但 mod 可以继承 `VoxelShape` 并覆写 `getCoordIndex` / `getPointPositions`。
   本内核的 `points_kind` 只有两档，**覆盖不了第三种**。-> ABI 侧必须**显式拒绝**（见 notes 第 5 节）。
6. **`ShapeContext` 对那 3 个方块类的影响**已在门禁 #7 里实测，但**没有在本规格里读它们自己的字节码**。**未验证。**
7. **射线部分的 tie-breaking 没有定点用例**（本内核不覆盖射线）。
   §8.2/8.3/8.4 的结论都只来自字节码 + javac 判定实验，**没有端到端实跑**。
8. **跟 Lithium / ServerCore / VMP 的交互完全没测**（P2 的兼容层归属是另一个流的事）。
   特别是 **Lithium 对 `method_20736` 有 `@Overwrite`**（`prompts/05-P2-实体开销.md` 记录）：
   若选「复刻」，本内核必须与 Lithium 的行为对齐，而 Lithium 的源码注释自认**改了三个来源的顺序**、
   且**没有研究 1e-7 margin 是否影响结果**。-> **本条是本规格最大的未闭合风险，必须由 captain 拍板归属。**

### 9.x 对任务书的三处更正

| 任务书原文 | 1.20.4 实测 |
| --- | --- |
| 「`BlockView.clip` 的遍历与 tie-breaking」 | 类/方法名是 **`BlockView.raycast`**；`clip` 在 1.20.4 **不存在** |
| 「`Entity.raycast` 里方块与实体的优先级」 | `Entity.raycast` **只查方块/流体**，不看实体 |
| 「`entityInside` / `limitPistonMovement` / `maybeBackOffFromEdge` / `updateEntityMovementAfterFallOn`」 | 1.20.4 **都不存在**；对应 `BlockState.onEntityCollision` / `adjustMovementForPiston` / `adjustMovementForSneaking` / `Block.onEntityLand`+`onSteppedOn` |

---

## 10. 本机实跑证据（可直接复制）

### 10.1 内核 + 差分测试

    PS J:\mc\Cava> powershell -NoProfile -ExecutionPolicy Bypass -File native/tests/build-entity.ps1
    srcs=2
    BUILT: J:\mc\Cava\build\native-entity\cava_entity_vectors.exe (683511 bytes)
    === Cava P2 entity collision kernel test ===
    [builder] 形状构造的定点断言
    [frame] 轴帧一致性（两种独立推导）
      axis=X cycle=0 opposite=0 xa=X ya=Y za=Z
      axis=Y cycle=2 opposite=1 xa=Y ya=Z za=X
      axis=Z cycle=1 opposite=2 xa=Z ya=X za=Y
    [truth-table] 定点真值表（真值全部由字节码手推）
      ok [TT-1] .. ok [TT-13]      （13 组 / 19 条断言）
    [vectors] 生成跨语言向量
      cases=400  有事件=188  改了位移=209  台阶命中=4  全零位移=40
      bytes=196788  fnv1a64=12e1f79186f28ca4
    SUMMARY: 487 checks, 0 failed
    RESULT: PASS
    exit=0

### 10.2 幂等

    run1 entity-00.bin sha256=EFFD4D31C1E7D420378C53FCB32EE3B155DB873801B09240879E9ABB93AB0AA2
    run2 entity-00.bin sha256=EFFD4D31C1E7D420378C53FCB32EE3B155DB873801B09240879E9ABB93AB0AA2
    idempotent=True

### 10.3 隔离证明（内核不依赖 Minecraft）

    PS> Select-String -Path native\src\entity\*.h,native\src\entity\*.cpp -Pattern '^\s*#include'
    #include <stdint.h>
    #include "cava_entity.h"
    PS> ... -Pattern 'net\.minecraft|net/minecraft|java\.lang' | Measure-Object
    minecraft/java refs: 0

    PS> g++ -std=c++17 -O2 -fwrapv -ffp-contract=off -fno-fast-math -Wall -Wextra \
             -c native\src\entity\cava_entity_kernel.cpp -o build\native-entity\kernel-only.o
    kernel compile exit=0

### 10.4 修正记录

- **本文件首次成稿时**：`BlockView.raycast` 的循环条件最初按记忆写成 `&&`。
  用 `build/p2-probe/LoopShapeProbe.java` 的 javac 判定实验改正为 **`||`**（§8.3）。
- **本文件首次成稿时**：`AxisCycleDirection.between` 最初按**实例方法**读（以为 `aload_0` 是 `this`），
  导致整张轴帧表错位。回读字节码（static 方法没有 `this`）后改正（§2.4）。
- **本文件首次成稿时**：`Box.stretch` 的 NaN 分支写成「加到 max」，回读后改为「两个分支都不进」（§7.1）。
- **定点真值表第一次跑**：两条期望值是我手算错的（`fbr(0.5,1.5)` 漏了 `e > 1.0000001` 早退；
  负坐标用例选了二进制不精确的 4.6）。**已按字节码重新手算**，并把「为什么错」写进用例注释。
