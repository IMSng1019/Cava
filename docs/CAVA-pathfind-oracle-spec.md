# Cava P1 寻路语义规格（Oracle Spec）

> **本文件是 P1 的唯一语义基准。** 内容全部来自本机 **Yarn 命名 Minecraft 1.20.4 字节码的 javap 输出**，
> 不是记忆、不是反编译猜测。凡是字节码看不出来的，一律写「未验证」。
>
> 作者：P1-Oracle 子代理。工具链见 `docs/CAVA-dev-toolbox.md`。

---

## 0. 证据来源与复现命令

### 0.1 **【重要纠正】toolbox 第 2 节的 jar 路径是错的**

`docs/CAVA-dev-toolbox.md` 第 2 节给的 `minecraft-clientonly-...jar` **不含任何服务端/公共类**：
实测该 jar 内 `net/minecraft/entity/**` 条目数为 **0**，也没有 `net/minecraft/entity/ai/pathing/` 目录。
（10662 个条目里只有 client + assets。）

**真正含寻路类的是同缓存下的 `minecraft-common` jar**（实测 Entries.Count = 12746，含
`net/minecraft/entity/ai/pathing/` 全部 21 个 class）。本规格所有 javap 均取自它：

    C:Users<user>.gradlecachesabric-loomminecraftMaven
etminecraftminecraft-common      1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2      minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar

复现（javap 不认 --enable-preview，直接 -p -c）：

    & 'C:Program FilesJavajdk-21injavap.exe' -p -c -classpath <上面的 jar> net.minecraft.entity.ai.pathing.PathMinHeap

### 0.2 映射表（node tools/mapquery.cjs class <Yarn名> 的实测输出）

| Yarn | intermediary | official |
| --- | --- | --- |
| PathNodeNavigator | net/minecraft/class_13 | efi |
| PathMinHeap | net/minecraft/class_5 | efb |
| PathNode | net/minecraft/class_9 | efe |
| Path | net/minecraft/class_11 | efg |
| PathNodeType | net/minecraft/class_7 | efc |
| PathNodeMaker | net/minecraft/class_8 | eff |
| LandPathNodeMaker | net/minecraft/class_14 | efl |
| BirdPathNodeMaker | net/minecraft/class_6 | efd |
| WaterPathNodeMaker | net/minecraft/class_12 | efj |
| AmphibiousPathNodeMaker | net/minecraft/class_15 | efa |
| TargetPathNode | net/minecraft/class_4459 | efk |

**PathNodeTypeCache 不存在**（mapquery class PathNodeTypeCache -> not found）。
1.20.4 里没有这个类；缓存职责由 PathNodeMaker.pathNodeCache（Int2ObjectMap<PathNode>）、
LandPathNodeMaker.nodeTypes（Long2ObjectMap<PathNodeType>）、WaterPathNodeMaker.nodePosToType、
BirdPathNodeMaker.pathNodes 各自承担。

可执行参照实现：src/test/java/cava/oracle/**（纯 Java，不引用任何 net.minecraft 类型）。
测试向量：src/test/resources/cava/oracle/**。

---

## 1. PathMinHeap（class_5 / efb）的 sift-up / sift-down 精确分支

**证据**：javap -p -c net.minecraft.entity.ai.pathing.PathMinHeap。

### 1.1 字段与容量

    private PathNode[] pathNodes;      // 构造时 = new PathNode[128]
    private int count;

    public PathMinHeap();
       5: sipush 128
       8: anewarray  class PathNode

push 时若 count == pathNodes.length 则扩容为 count << 1（**两倍，不是 1.5 倍**），System.arraycopy：

    push(PathNode):
       0: aload_1 ; getfield PathNode.heapIndex:I
       4: iflt 17                       // if (node.heapIndex >= 0) throw new IllegalStateException("OW KNOWS!")
       7: new java/lang/IllegalStateException ; ldc "OW KNOWS!" ; athrow
      17: getfield count ; getfield pathNodes ; arraylength ; if_icmpne 58
      29: getfield count ; iconst_1 ; ishl ; anewarray PathNode    // new PathNode[count << 1]
      50: invokestatic System.arraycopy
      58: pathNodes[count] = node
      68: node.heapIndex = count
      76: this.count = count + 1        // dup_x1 模式 -> 自增后
      88: shiftUp(<自增前的 count>)      // 关键：shiftUp 收到的下标 = 放入位置
      91: areturn                        // 返回 node 本身

### 1.2 pop() —— **取的是下标 0，且用「最后一个元素」填补根部**

    pop():
       0: node = pathNodes[0]
       7: pathNodes[0] = pathNodes[--count]     // 先自减再取
      29: pathNodes[count] = null
      39: if (count > 0) shiftDown(0)           // 只在 count > 0 时
      51: node.heapIndex = -1
      56: return node

### 1.3 clear() —— **只把 count 置 0**

    clear():  0: aload_0 ; 1: iconst_0 ; 2: putfield count ; 5: return

**关键**：clear() **不重置数组内容，也不重置任何 PathNode.heapIndex**。
所以 clear() 之后，之前入过堆的节点仍然 isInHeap() == true（heapIndex >= 0）。
PathMinHeap.push 对这类节点会抛 IllegalStateException("OW KNOWS!")。
-> **导航器每次 findPathToAny 都复用同一批 PathNode 的前提是 PathNodeMaker.init() 清了 pathNodeCache。**（见 §3。）

### 1.4 getStart() = pathNodes[0]；isEmpty() = count == 0；getNodes() = Arrays.copyOf(pathNodes, count)

### 1.5 shiftUp(int i) —— 比较用 **严格 <**

    private void shiftUp(int i);
       0: node = pathNodes[i] ; 7: weight = node.heapWeight
      12: if (i <= 0) goto 62
      16: parent = (i - 1) >> 1                 // 18: iconst_1 / 19: isub / 20: ishr（算术右移）
      23: parentNode = pathNodes[parent]
      32: fload weight ; parentNode.heapWeight ; fcmpg ; ifge 62   // if (weight < parentWeight) 才上移
      42: pathNodes[i] = parentNode ; parentNode.heapIndex = i ; i = parent ; goto 12
      62: pathNodes[i] = node ; node.heapIndex = i

**严格 < -> 权重相等时不上移 -> 相等元素相对顺序被保留（对父子关系而言）。**
fcmpg 语义：任一为 NaN -> 返回 1 -> ifge 成立 -> **不上移**。

### 1.6 shiftDown(int i) —— 相等时走 **右孩子**（sibling）

    private void shiftDown(int i);
       0: node = pathNodes[i] ; 7: weight = node.heapWeight
      12: child = 1 + (i << 1)          // 12: iconst_1 / 13: iload i / 14: iconst_1 / 15: ishl / 16: iadd
      19: sibling = child + 1
      25: if (child >= count) goto 150  // break
      37: childNode = pathNodes[child] ; childWeight = childNode.heapWeight
      53: if (sibling >= count) { siblingNode = null; siblingWeight = Float.POSITIVE_INFINITY; }
          else { siblingNode = pathNodes[sibling]; siblingWeight = siblingNode.heapWeight; }
      88: fload childWeight ; fload siblingWeight ; fcmpg ; ifge 123
            96: fload childWeight ; fload weight ; fcmpg ; ifge 150
           103: pathNodes[i] = childNode ; childNode.heapIndex = i ; i = child ; goto 147
     123: fload siblingWeight ; fload weight ; fcmpg ; ifge 150
           130: pathNodes[i] = siblingNode ; siblingNode.heapIndex = i ; i = sibling ; goto 147
     150: pathNodes[i] = node ; node.heapIndex = i

**判定规则（逐字）**：

- 右孩子不存在时，它的权重按 +Infinity 参与比较（**不是跳过右孩子**）。
- childWeight < siblingWeight -> 候选 = 左孩子；**否则（含相等与 NaN）候选 = 右孩子**。
- 候选权重 < weight 才下移，否则 break。
- 因此 **childWeight == siblingWeight 时优先把右孩子提上来** —— 这是「同代价路径选哪一条」的决定性分支。

**相等元素的相对顺序**（必须逐位复刻的结论）：

1. push 把新元素放到末尾后 shiftUp，**只在严格小于父节点时**才交换 -> 相同权重的新元素**留在原位（更靠后）**。
2. pop 把**最后一个元素**搬到根部再 shiftDown -> 原末尾元素会被尽量往前推。
3. shiftDown 在两个子权重相等时选**右**孩子。
4. popNode / setNodeWeight 的分支见 1.7 / 1.8。

-> 结论：**这个堆不是稳定堆**，也不能用「PriorityQueue + 序号打破平局」来复刻。
原生侧（C++）必须逐指令复刻 §1.5–1.8。

### 1.7 popNode(PathNode node)

       0: pathNodes[node.heapIndex] = pathNodes[--count]
      25: pathNodes[count] = null
      35: if (count > node.heapIndex) {
      46:    if (pathNodes[node.heapIndex].heapWeight < node.heapWeight) shiftUp(node.heapIndex)   // fcmpg/ifge
             else shiftDown(node.heapIndex)
          }
      85: node.heapIndex = -1

注意：if (count > node.heapIndex) 用的是 if_icmple 85（**count <= heapIndex 就整个跳过**）。

### 1.8 setNodeWeight(PathNode node, float w)

       0: old = node.heapWeight
       5: node.heapWeight = w
      10: fload w ; fload old ; fcmpg ; ifge 27
      16: shiftUp(node.heapIndex)
      27: shiftDown(node.heapIndex)

**没有 heapIndex >= 0 的保护**：对不在堆里的节点调用会走到
shiftUp(-1) -> ifle 62 成立 -> pathNodes[-1] = node -> **ArrayIndexOutOfBoundsException**。
导航器只在 node.isInHeap() 为真时调用它（§3.4），所以正常路径不会触发。

---

## 2. 节点展开顺序

### 2.1 PathNodeNavigator.findPathToAny 主循环里邻居数组的遍历顺序

**证据**：javap -p -c net.minecraft.entity.ai.pathing.PathNodeNavigator（私有重载）。

     222: pathNodeMaker.getSuccessors(successors, current) -> n
     237: i = 0
     240: if (i >= n) goto 418
     247: s = successors[i]
     ... 处理 s ...
     412: iinc i, 1
     415: goto 240

**严格按下标 0..n-1 顺序**，没有重排、没有排序。每个 PathNodeMaker 子类决定下标含义（§2.2–2.6）。

### 2.2 LandPathNodeMaker.getSuccessors（class_14）

**证据**：字节码 327–604。8 个候选，顺序**固定**如下（host = 当前节点 (x,y,z)）：

| 下标 | 目标坐标 | 方向常量 | 收录条件 |
| --- | --- | --- | --- |
| 0 | (x, y+1, z+1) | Direction.SOUTH | isValidAdjacentSuccessor(node, host) |
| 1 | (x-1, y+1, z) | Direction.WEST | isValidAdjacentSuccessor |
| 2 | (x+1, y+1, z) | Direction.EAST | isValidAdjacentSuccessor |
| 3 | (x, y+1, z-1) | Direction.NORTH | isValidAdjacentSuccessor |
| 4 | (x-1, y+1, z-1) | Direction.NORTH | isValidDiagonalSuccessor(diag, west, north, diag) |
| 5 | (x+1, y+1, z-1) | Direction.NORTH | isValidDiagonalSuccessor(diag, east, north, diag) |
| 6 | (x-1, y+1, z+1) | Direction.SOUTH | isValidDiagonalSuccessor(diag, west, south, diag) |
| 7 | (x+1, y+1, z+1) | Direction.SOUTH | isValidDiagonalSuccessor(diag, east, south, diag) |

**注意 y 偏移恒为 +1**：字节码里 8 次调用全部是 iload y ; iconst_1 ; iadd。

每次调用前统一计算两个量（只算一次，8 次调用共用）：

       5: aboveType  = getNodeType(entity, x, y+1, z)
      29: hereType   = getNodeType(entity, x, y,   z)
      51: i4 = 0
          if (!(entity.getPathfindingPenalty(aboveType) < 0.0f) && hereType != STICKY_HONEY)
              i4 = MathHelper.floor(Math.max(1.0f, entity.getStepHeight()))
      89: feetY = getFeetY(new BlockPos(x, y, z))     // 实例方法，见 §6.6

fcmpl + iflt：**penalty 为 NaN 时视为 < 0 -> i4 保持 0。**

8 次 getPathNode 的实参恒为 (nx, y+1, nz, i4, feetY, dir, hereType)，
其中 **hereType 是「当前节点位置」的类型**（不是目标位置的类型）。递归里继续往下传（§7.1 第 8 步）。

### 2.3 isValidAdjacentSuccessor(target, host)

       0: if (target == null) return false
       4: if (target.visited) return false
      11: if (target.penalty < 0.0f)         // fcmpl / ifge
      20:    if (host.penalty >= 0.0f) return false    // fcmpg / ifge
      29: return true

即：

    target != null && !target.visited && (target.penalty >= 0.0f || host.penalty < 0.0f)

### 2.4 isValidDiagonalSuccessor(diag, sideA, sideB, ...)

**证据**：字节码 627–719。实参顺序是
(结果槽, sideA, sideB, diag)——aload_1 是 host 当前节点、aload_2 = sideA、aload_3 = sideB、aload 4 = diag。

       0: if (diag == null || sideB == null || sideA == null) return false
      15: if (diag.visited) return false
      25: if (sideB.y > host.y) return false
      36: if (sideA.y > host.y) return false
      49: if (sideA.type == WALKABLE_DOOR) return false
      59: if (sideB.type == WALKABLE_DOOR) return false
      69: if (diag.type  == WALKABLE_DOOR) return false
      82: flag5 = (sideB.type == FENCE && sideA.type == FENCE && (double)entity.getWidth() < 0.5)
     124: 判定见下

**逐分支重写（字节码 124–189）** —— 注意跳转方向，容易读反：

    124: aload 4 (diag).penalty ; fconst_0 ; fcmpl ; iflt 188      // diag.penalty < 0  -> return false
    134: sideB.y ; host.y ; if_icmplt 159                          // sideB.y <  host.y -> 去 159
    145: sideB.penalty ; fconst_0 ; fcmpl ; ifge 159               // sideB.penalty >= 0 -> 去 159
    154: iload 5 (flag5) ; ifeq 188                                // !flag5 -> return false
    159: sideA.y ; host.y ; if_icmplt 184                          // sideA.y <  host.y -> 去 184
    170: sideA.penalty ; fconst_0 ; fcmpl ; ifge 184               // sideA.penalty >= 0 -> 去 184
    179: iload 5 ; ifeq 188                                        // !flag5 -> return false
    184: iconst_1                                                   // return true
    188: iconst_0                                                   // return false

即（**这是正确读法**；把 188 与 184 看反会得到完全相反的结论）：

    if (diag.penalty < 0.0f) return false;
    if (sideB.y >= host.y && sideB.penalty < 0.0f && flag5) return false;
    if (sideA.y >= host.y && sideA.penalty < 0.0f && flag5) return false;
    return true;

等价写法：

    return diag.penalty >= 0.0f
        && !(sideB.y >= host.y && sideB.penalty < 0.0f && flag5)
        && !(sideA.y >= host.y && sideA.penalty < 0.0f && flag5);

（flag5 为真时才可能拒绝；flag5 为假时对角一律放行。）

### 2.5 BirdPathNodeMaker.getSuccessors（class_6）—— **26 个邻居**

**证据**：字节码 180–1050。用本地变量下标 4+k 与 aastore 分块机械提取。
P_k 表示第 k 个候选节点 isPassable（penalty >= 0），U 表示 unvisited。

| k | 偏移 | 收录条件 |
| --- | --- | --- |
| 0 | (0,0,+1) | U |
| 1 | (-1,0,0) | U |
| 2 | (+1,0,0) | U |
| 3 | (0,0,-1) | U |
| 4 | (0,+1,0) | U |
| 5 | (0,-1,0) | U |
| 6 | (+1,+1,+1) | U && P0 && P4 |
| 7 | (-1,+1,0) | U && P1 && P4 |
| 8 | (+1,+1,0) | U && P2 && P4 |
| 9 | (0,+1,-1) | U && P3 && P4 |
| 10 | (0,-1,+1) | U && P0 && P5 |
| 11 | (-1,-1,0) | U && P1 && P5 |
| 12 | (+1,-1,0) | U && P2 && P5 |
| 13 | (0,-1,-1) | U && P3 && P5 |
| 14 | (+1,0,-1) | U && P3 && P2 |
| 15 | (+1,0,+1) | U && P0 && P2 |
| 16 | (-1,0,-1) | U && P3 && P1 |
| 17 | (-1,0,+1) | U && P0 && P1 |
| 18 | (+1,+1,-1) | U && P14 && P3 && P2 && P4 && P9 && P8 |
| 19 | (+1,+1,+1) | U && P15 && P0 && P2 && P4 && P6 && P8 |
| 20 | (-1,+1,-1) | U && P16 && P3 && P1 && P4 && P9 && P7 |
| 21 | (-1,+1,+1) | U && P17 && P0 && P1 && P4 && P6 && P7 |
| 22 | (+1,-1,-1) | U && P14 && P3 && P2 && P5 && P13 && P12 |
| 23 | (+1,-1,+1) | U && P15 && P0 && P2 && P5 && P10 && P12 |
| 24 | (-1,-1,-1) | U && P16 && P3 && P1 && P5 && P13 && P11 |
| 25 | (-1,-1,+1) | U && P17 && P0 && P1 && P5 && P10 && P11 |

（k=0..5 的 6 个轴邻居按 (0,0,+1),(-1,0,0),(+1,0,0),(0,0,-1),(0,+1,0),(0,-1,0) 的顺序。）

**规律**：每个「角」（三个偏移都非 0）要求它的 **3 个轴邻居 + 3 个棱邻居**（共 6 个）全部 isPassable；
每个「棱」（两个偏移非 0）要求它的 **2 个轴邻居** 全部 isPassable。

BirdPathNodeMaker.isPassable(n) = n != null && n.penalty >= 0.0f；
unvisited(n) = n != null && !n.visited；getPassableNode(x,y,z) 见 §8.4。

### 2.6 WaterPathNodeMaker.getSuccessors（class_12）

**证据**：字节码 78–187。两阶段：

**阶段 1** —— 遍历 Direction.values()（顺序 = 枚举序 = **DOWN, UP, NORTH, SOUTH, WEST, EAST**，见 §2.7）：

    for (Direction d : Direction.values()) {
        PathNode n = getPassableNode(x + d.dx, y + d.dy, z + d.dz);
        map.put(d, n);                       // 即使 n == null 也 put
        if (hasNotVisited(n)) array[count++] = n;
    }

**阶段 2** —— 遍历 Direction.Type.HORIZONTAL.iterator()（顺序 = **[NORTH, EAST, SOUTH, WEST]**，§2.8）：

    for (Direction d : Direction.Type.HORIZONTAL) {
        Direction d2 = d.rotateYClockwise();
        PathNode n = getPassableNode(x + d.dx + d2.dx, y, z + d.dz + d2.dz);
        if (canPathThrough(n, map.get(d), map.get(d2))) array[count++] = n;   // 没有 hasNotVisited！
    }

- hasNotVisited(n) = n != null && !n.visited（字节码 188–198）
- canPathThrough(n, a, b) = hasNotVisited(n) && a != null && a.penalty >= 0.0f && b != null && b.penalty >= 0.0f（200–223）
- rotateYClockwise 实测：NORTH->EAST，SOUTH->WEST，WEST->NORTH，EAST->SOUTH（Direction$1.field_11054 表 + tableswitch）。
  因此阶段 2 的对角顺序是 **NE, SE, SW, NW**。
- **y 不参与阶段 2**。

### 2.7 Direction 枚举序与偏移

**证据**：Direction$1.<clinit> 的 field_11054 映射表 与 Direction.getOffsetX/Y/Z。

field_11054：DOWN=1, UP=2, NORTH=3, SOUTH=4, WEST=5, EAST=6 -> **枚举声明序就是 DOWN, UP, NORTH, SOUTH, WEST, EAST**。
getOffsetX/Y/Z 直接读 vector（Vec3i）：DOWN=(0,-1,0)，UP=(0,1,0)，NORTH=(0,0,-1)，SOUTH=(0,0,1)，WEST=(-1,0,0)，EAST=(1,0,0)。

### 2.8 Direction.Type.HORIZONTAL 顺序

**证据**：javap -p -c net.minecraft.util.math.Direction$Type 的 static {}：
new Direction[]{ NORTH, EAST, SOUTH, WEST }，new Axis[]{ X, Z }。
Type.iterator() 就是数组顺序 -> **[NORTH, EAST, SOUTH, WEST]**。

---

## 3. 节点去重语义 / 坐标打包

### 3.1 PathNode.hash(int x, int y, int z)（class_9）

**证据**：javap -p -c net.minecraft.entity.ai.pathing.PathNode，方法 public static int hash(int,int,int)。

       0: iload_1 (y) ; sipush 255  ; iand                       // y & 0xFF
       5: iload_0 (x) ; sipush 32767; iand ; bipush 8 ; ishl      // (x & 0x7FFF) << 8
      13: ior
      14: iload_2 (z) ; sipush 32767; iand ; bipush 24; ishl      // (z & 0x7FFF) << 24
      22: ior
      23: iload_0 ; ifge 32 ; ldc -2147483648 ; ior               // x < 0 ? 0x80000000 : 0
      34: iload_2 ; ifge 43 ; ldc 32768 ; ior                     // z < 0 ? 0x8000 : 0
      45: ireturn

即：

    hash(x,y,z) = (y & 0xFF) | ((x & 0x7FFF) << 8) | ((z & 0x7FFF) << 24)
                | (x < 0 ? 0x80000000 : 0) | (z < 0 ? 0x8000 : 0)

**位宽**：y 只有 8 位（**y 相差 256 的倍数会撞哈希**），x/z 各 15 位（相差 32768 的倍数会撞），
符号位另占 1 位（x 一位、z 一位）。-> **这是一个不完美的打包，不是唯一键。**

### 3.2 PathNode.equals / hashCode

    equals(Object o):
      if (!(o instanceof PathNode)) return false;
      return this.hashCode == o.hashCode && this.x == o.x && this.y == o.y && this.z == o.z;
    hashCode(): return this.hashCode;      // 构造时算好的字段

### 3.3 PathNodeMaker.getNode(int x,int y,int z)（class_8）—— **按 int 哈希做去重缓存**

    getNode(int,int,int):
       0: pathNodeCache.computeIfAbsent(PathNode.hash(x,y,z), k -> new PathNode(x,y,z))

pathNodeCache 是 it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<PathNode>（构造时 new）。
**Int2ObjectMap.computeIfAbsent(int key, ...) 只按这个 int 键索引**，不校验坐标。

因此：

- **同一 int 哈希、但坐标不同的两个位置，会返回同一个 PathNode 实例**（坐标是第一次创建时的坐标）。
  这是货真价实的原版行为（y 相差 256、x/z 相差 32768、或符号位碰撞时触发）。
  **原生侧必须复刻这个「坏哈希共享对象」语义**，否则在 y 跨度大 / 坐标差的场景会不一致。
- 缓存**只在 PathNodeMaker.init(ChunkCache, MobEntity) 里 clear()**（init 字节码 10–19）。
  PathNodeMaker.clear() 只把 cachedWorld/entity 置 null，**不清 pathNodeCache**。
- LandPathNodeMaker.clear() 还会清 nodeTypes（Long2ObjectMap<PathNodeType>，键 = BlockPos.asLong）与
  collidedBoxes（Object2BooleanMap<Box>），然后调 super.clear()；并调 entity.onFinishPathfinding()。

### 3.4 「重复访问时 g 值与 heapIndex 如何合并」

由 PathNodeNavigator 主循环逐字决定（**字节码 256–411**）：

    s.pathLength = current.pathLength + d;                       // 266–276 无条件写！
    float penalized = current.penalizedPathLength + d + s.penalty; // 279–293
    if (s.pathLength < maxRange) {                                 // 295–303 fcmpg/ifge
        if (!s.isInHeap() || penalized < s.penalizedPathLength) {   // 306–322
            s.previous = current;                // 325  <- 只在接受松弛时覆盖
            s.penalizedPathLength = penalized;   // 332
            s.distanceToNearestTarget = calculateDistances(s, targets) * 1.5f;  // 339–352
            if (s.isInHeap()) minHeap.setNodeWeight(s, s.penalizedPathLength + s.distanceToNearestTarget);
            else { s.heapWeight = s.penalizedPathLength + s.distanceToNearestTarget; minHeap.push(s); }
        }
    }

**结论**：

1. s.pathLength（= 未加惩罚的 g）**每次遇到都无条件重写**，即使随后被丢弃。
2. 「更优」的判定用的是 **penalizedPathLength（带惩罚的 g）**，不是 pathLength。
3. **已在堆中**且更优 -> setNodeWeight（就地调整堆位置），**不 push**。
4. **不在堆中**（含已被 pop、或从未入堆）且满足条件 -> 重设 heapWeight 后 push。
   **注意：已经被 pop 过（visited == true）的节点会被重新 push**——导航器**没有**检查 visited！
   （这个「重新入堆」是原版 A* 的真实行为，不是待修正的 bug。）
5. s.previous 只在「接受这次松弛」时被覆盖。

### 3.5 maker 内部还各自有类型缓存

- LandPathNodeMaker.nodeTypes : Long2ObjectMap<PathNodeType>，键 BlockPos.asLong(x,y,z)
  （getNodeType(MobEntity,x,y,z)，字节码 1495–1511）。clear() 会清空。
- WaterPathNodeMaker.nodePosToType : Long2ObjectMap<PathNodeType>（addPathNodePos）。
- BirdPathNodeMaker.pathNodes : Long2ObjectMap<PathNodeType>（getNodeType(int,int,int)）。

BlockPos.asLong（javap -p -c net.minecraft.util.math.BlockPos）：

    SIZE_BITS_X = SIZE_BITS_Z = 1 + floorLog2(smallestEncompassingPowerOfTwo(30000000)) = 1 + 25 = 26
    SIZE_BITS_Y = 64 - 26 - 26 = 12
    BITS_X = (1<<26)-1 ; BITS_Y = (1<<12)-1 ; BITS_Z = (1<<26)-1
    BIT_SHIFT_Z = SIZE_BITS_Y = 12 ; BIT_SHIFT_X = SIZE_BITS_Y + SIZE_BITS_Z = 38
    asLong(x,y,z) = ((x & BITS_X) << 38) | ((y & BITS_Y) << 0) | ((z & BITS_Z) << 12)

（证据：BlockPos.static {} 字节码 45–124。）

### 3.6 PathNode 构造与字段初值

    PathNode(int x,int y,int z):
        heapIndex = -1
        type = PathNodeType.BLOCKED
        this.x/y/z = 参数
        this.hashCode = hash(x,y,z)

其余字段（penalizedPathLength / distanceToNearestTarget / heapWeight / pathLength / penalty =
0.0f，previous = null，visited = false）由 JVM 默认值给出。

TargetPathNode(PathNode n)：super(n.x, n.y, n.z)，nearestNodeDistance = Float.MAX_VALUE（3.4028235E38f），
nearestNode = null，reached = false。

---

## 4. maxVisitedNodes 预算与提前退出

**证据**：PathNodeNavigator.findPathToAny 私有重载。

### 4.1 预算的计算

      85: aload_0 ; getfield range:I ; i2f        // (float)this.range     <- 构造器传入的 int
      90: fload 6 (followRange) ; fmul            // (float)range * followRange
      93: f2i ; istore 11                          // 截断（f2i = 向零取整，NaN->0，饱和）

    int nodeBudget = (int)((float)this.range * followRange);

### 4.2 计数器自增与判定

      96: if (minHeap.isEmpty()) goto 421
     106: iinc 9, 1                 // visited++   <- 唯一一处自增
     109: iload 9 ; iload 11 ; if_icmpge 421     // if (visited >= nodeBudget) 退出
     116: current = minHeap.pop()

- **只有一处自增**（每轮循环开头，在 isEmpty 检查之后、pop 之前）。
- 判定是 **>=**，不是 >：visited >= nodeBudget 时立刻退出，**本轮不 pop**。
- 顺序是先 isEmpty 再 visited++。所以 nodeBudget <= 0 时，堆非空也会在第 1 轮（visited 变成 1）时退出。

### 4.3 退出后返回什么

三段式：

     421: if (!found.isEmpty()) {                       // found = 本次调用中「被判定抵达」的目标集合
             optional = found.stream()
                            .map(t -> createPath(t.getNearestNode(), targetMap.get(t), false))
                            .min(Comparator.comparingInt(Path::getLength));
          } else {
             optional = targets.stream()
                            .map(t -> createPath(t.getNearestNode(), targetMap.get(t), true))
                            .min(Comparator.comparingDouble(Path::getManhattanDistanceFromTarget)
                                           .thenComparingInt(Path::getLength));
          }
     516: if (optional.isEmpty()) return null; else return optional.get();

- **createPath(..., reachesTarget) 的布尔值：found 非空分支传 false（iconst_0，方法 method_21660），
  否则分支传 true（iconst_1，method_21661）。** 这是反直觉但字节码明确。
  ---
  ### 4.3.1 **Path.reachesTarget() 的语义是反的（本机实测双重确认）**
  ---
  - 字节码：found 非空（**真的抵达了目标**）-> `reachesTarget = false`；
    found 为空（**没抵达，只能给最接近的路径**）-> `reachesTarget = true`。
  - 实跑确认（10000 组向量）：`reachesTarget == false` 的路径末节点 `distanceToNearestTarget` 都
    `<= reachRadius`；`== true` 的都 `> reachRadius`。
  - -> **原生侧与 Java 钩子绝不能把 `reachesTarget` 当作「是否找到目标」来用。**
  - -> 另外：**只要目标集合非空，LandPathNodeMaker 路径下的 `findPathToAny` 几乎不会返回 null**
    （10000 组里 null 出现 0 次）。「没找到路」的表达是**一条到最接近点的短路径**，不是 null。
    这对上层的 `EntityNavigation` 行为影响很大，做原生接管时必须复刻。
- Stream.min = reduce((a,b) -> cmp.compare(a,b) <= 0 ? a : b) -> **并列时保留先遇到的元素**。
- invokedynamic 解析（javap -v 的 BootstrapMethods，实测）：
  - #0 = this::method_21659（BlockPos -> TargetPathNode）
  - #1 = t -> method_21661(targetMap, t)（found 空分支用）
  - #2 = Path::getLength（ToIntFunction）
  - #3 = t -> method_21660(targetMap, t)（found 非空分支用）
  - #4 = Path::getManhattanDistanceFromTarget（ToDoubleFunction）

method_21660 / method_21661：

    method_21660(map, t) = createPath(t.getNearestNode(), map.get(t), false)
    method_21661(map, t) = createPath(t.getNearestNode(), map.get(t), true)

**不确定性提示（原生侧必须知道）**：
found 是 Sets.newHashSetWithExpectedSize(targets.size()) 出来的 HashSet<TargetPathNode>，
而 TargetPathNode 继承 PathNode.hashCode()/equals()（= 打包坐标哈希）。
targetMap 是 Collectors.toMap 出来的 HashMap<TargetPathNode, BlockPos>，targets = targetMap.keySet()。
-> **多目标时的遍历顺序取决于 HashMap/HashSet 的桶顺序**（由 PathNode.hash 与容量决定），
不是插入顺序。单目标时无影响。**Cava 参照实现按 Java 的 HashMap/HashSet 语义复刻**（见 §9），
这样它逐位等于原版；原生侧若无法复刻，应在 Java 钩子里**只允许单目标走原生路径**（多目标回退）。
注：Sets.newHashSetWithExpectedSize(n) 的容量 = (n<3) ? n+1 : (int)(n/0.75f)+1（Guava Maps.capacity）。

### 4.4 抵达判定

     131: for (TargetPathNode t : targets) {
     162:    if (current.getManhattanDistance(t) <= (float)reachRadius) {   // 169: iload 5 ; i2f ; 172: fcmpg ; 173: ifgt
     176:        t.markReached();
     181:        found.add(t);
             }
          }
     194: if (!found.isEmpty()) goto 421        // 跳出主循环
     207: if (current.getDistance(start) >= maxRange) goto 96   // continue（fcmpl/iflt 的反向）

**参数 5（int）同时是「抵达半径」**，不是节点预算。节点预算来自 §4.1。
found **在本轮循环内不清空**（声明在循环外），一旦非空立刻 break。

current.getDistance(start) >= maxRange -> continue（不展开邻居）。

### 4.5 邻居松弛的另一个 maxRange 门槛

见 §3.4：if (s.pathLength < maxRange) 才考虑松弛（**严格 <**）。

---

## 5. 代价 / 距离 / malus 的 float 运算链

### 5.1 PathNode.getDistance(PathNode)

       0: dx = (float)(other.x - this.x)
      11: dy = (float)(other.y - this.y)
      22: dz = (float)(other.z - this.z)
      34: MathHelper.sqrt(dx*dx + dy*dy + dz*dz)      // 全部 float 运算

MathHelper.sqrt(float) 实测（javap -p -c net.minecraft.util.math.MathHelper）：

    public static float sqrt(float f);
       0: fload_0 ; f2d ; invokestatic java/lang/Math.sqrt:(D)D ; d2f ; freturn

即 **(float)Math.sqrt((double)f)**。原生侧可直接用 sqrtf（正确舍入的 float sqrt 与
「double sqrt 再舍入到 float」一致：53 >= 2*24+2，双重舍入无害）。

getHorizontalDistance = sqrt(dx*dx + dz*dz)（float）。
getSquaredDistance = dx*dx + dy*dy + dz*dz（float，不开方）。
getManhattanDistance = (float)(|dx| + |dy| + |dz|)（先 Math.abs(int) 再 i2f 再 float 加法，顺序 (|dx|+|dy|)+|dz|）。

### 5.2 导航器里的 g / f 定义

| 名称 | 字段 | 含义 |
| --- | --- | --- |
| g（未惩罚） | PathNode.pathLength | current.pathLength + getDistance(current, s) |
| g（惩罚后） | PathNode.penalizedPathLength | current.penalizedPathLength + d + s.penalty（**先加 d 再加 penalty**） |
| h | PathNode.distanceToNearestTarget | calculateDistances(s, targets) * 1.5f |
| f（堆权重） | PathNode.heapWeight | penalizedPathLength + distanceToNearestTarget |

起点：start.penalizedPathLength = 0.0f（字节码 25–27），
start.distanceToNearestTarget = calculateDistances(start, targets)（**起点不乘 1.5**），
start.heapWeight = start.distanceToNearestTarget。

**h 用的是欧氏距离**（getDistance），不是曼哈顿；曼哈顿只用于 §4.4 的抵达判定。

calculateDistances（字节码 0–61）：

    float best = Float.MAX_VALUE;                  // 3.4028235E38f
    for (TargetPathNode t : targets) {
        float d = pathNode.getDistance(t);
        t.updateNearestNode(d, pathNode);
        best = Math.min(d, best);
    }
    return best;

TargetPathNode.updateNearestNode(float d, PathNode n)（TargetPathNode 字节码 0–19）：

       0: fload d ; getfield nearestNodeDistance ; fcmpg ; ifge 19
       9: nearestNodeDistance = d ; nearestNode = n

即 **严格 d < nearestNodeDistance** 才更新（相等不更新 -> 先到的目标保持）。

### 5.3 数值纪律

**凡是字节码里出现 fadd / fmul / fcmpg / fcmpl 的地方一律是 float**，全程没有 f2d/d2f 提升
（除 MathHelper.sqrt 内部；以及 Math.max(1.0f, stepHeight) 也是 float）。
**原生侧必须用 float，且不许改变结合顺序。**

### 5.4 malus（惩罚）链：getPathNodeType -> getLandNodeType -> 累加

#### 5.4.1 PathNodeMaker.getNodeType(BlockView, x, y, z, MobEntity) 是抽象方法

由 LandPathNodeMaker（javap 1294–1371）实现：

    EnumSet<PathNodeType> set = EnumSet.noneOf(PathNodeType.class);
    PathNodeType best = PathNodeType.BLOCKED;
    best = findNearbyNodeTypes(view, x, y, z, set, best, entity.getBlockPos());
    if (set.contains(FENCE)) return FENCE;
    if (set.contains(UNPASSABLE_RAIL)) return UNPASSABLE_RAIL;
    PathNodeType chosen = BLOCKED;
    for (PathNodeType t : set) {                       // EnumSet 迭代序 = 枚举 ordinal 升序
        if (entity.getPathfindingPenalty(t) < 0.0f) return t;         // fcmpg / ifge
        if (entity.getPathfindingPenalty(t) >= entity.getPathfindingPenalty(chosen)) chosen = t;  // fcmpl / iflt
    }
    if (best == OPEN && entity.getPathfindingPenalty(chosen) == 0.0f && this.entityBlockXSize <= 1) return OPEN;
    return chosen;

**注意最后一条**：fcmpl 与 ifne 组合（字节码 145–168）-> penalty(chosen) == 0.0f 才可能返回 OPEN
（!= 0 就跳到 return chosen）；且要求 entityBlockXSize <= 1（iconst_1 ; if_icmpgt 169）。

Bird 版本少一条 UNPASSABLE_RAIL 检查（BirdPathNodeMaker 字节码 1140–1209）。

#### 5.4.2 findNearbyNodeTypes（Land 1373–1437）

**⚠️ 易错点**：传给 adjustNodeType 的 BlockPos 是**实体的方块坐标**（字节码 `70: aload 7`，
即形参 BlockPos = `entity.getBlockPos()`），**不是当前体素的坐标**。这是原版的真实行为，
照抄时必须一致，否则 WALKABLE_DOOR / BLOCKED / UNPASSABLE_RAIL 三处调整会作用在错误的格子上。

    for (int dx = 0; dx < entityBlockXSize; dx++)
      for (int dy = 0; dy < entityBlockYSize; dy++)
        for (int dz = 0; dz < entityBlockZSize; dz++) {
            int px = dx + x, py = dy + y, pz = dz + z;
            PathNodeType t = adjustNodeType(view, entityBlockPos, getDefaultNodeType(view, px, py, pz));
            if (dx == 0 && dy == 0 && dz == 0) firstType = t;    // 返回给调用者作为「局部 best」
            set.add(t);
        }
    return firstType;

entityBlockXSize = entityBlockZSize = MathHelper.floor(entity.getWidth() + 1.0f)，
entityBlockYSize = MathHelper.floor(entity.getHeight() + 1.0f)（PathNodeMaker.init 字节码 19–55）。

#### 5.4.3 adjustNodeType（Land 1439–1480）

    boolean b = canEnterOpenDoors();
    if (type == DOOR_WOOD_CLOSED && canOpenDoors() && b) type = WALKABLE_DOOR;
    if (type == DOOR_OPEN && !b) type = BLOCKED;
    if (type == RAIL && !(view.getBlockState(pos).getBlock() instanceof AbstractRailBlock)
                    && !(view.getBlockState(pos.down()).getBlock() instanceof AbstractRailBlock)) type = UNPASSABLE_RAIL;
    return type;

#### 5.4.4 getDefaultNodeType -> getLandNodeType（Land 1525–1608）

    static PathNodeType getLandNodeType(BlockView view, BlockPos.Mutable pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        PathNodeType common = getCommonNodeType(view, pos);
        if (common != OPEN || y < view.getBottomY() + 1) return common;
        switch (<getCommonNodeType(view, pos.set(x, y-1, z)) 的 ordinal>) {   // LandPathNodeMaker$1.field_47414
            case OPEN: case WATER: case LAVA: case WALKABLE:  return OPEN;
            case DAMAGE_FIRE:      return DAMAGE_FIRE;
            case DAMAGE_OTHER:     return DAMAGE_OTHER;
            case STICKY_HONEY:     return STICKY_HONEY;
            case POWDER_SNOW:      return DANGER_POWDER_SNOW;
            case DAMAGE_CAUTIOUS:  return DAMAGE_CAUTIOUS;
            case TRAPDOOR:         return DANGER_TRAPDOOR;
            default:               return getNodeTypeFromNeighbors(view, pos.set(x, y, z), WALKABLE);
        }
    }

LandPathNodeMaker$1.field_47414（实测 static {}）：OPEN->1, WATER->2, LAVA->3, WALKABLE->4,
DAMAGE_FIRE->5, DAMAGE_OTHER->6, STICKY_HONEY->7, POWDER_SNOW->8, DAMAGE_CAUTIOUS->9, TRAPDOOR->10。

#### 5.4.5 getNodeTypeFromNeighbors（Land 1610–1696）

三重循环 dx ∈ [-1,1], dy ∈ [-1,1], dz ∈ [-1,1]（**外层 dx，中层 dy，内层 dz**），
**跳过 dx == 0 && dz == 0 的位置**（字节码 44–51；注意 dy 不参与跳过判定）：

    for dx in -1..1: for dy in -1..1: for dz in -1..1:
       if (dx == 0 && dz == 0) continue;
       BlockState st = view.getBlockState(pos.set(x+dx, y+dy, z+dz));
       if (st.isOf(Blocks.CACTUS) || st.isOf(Blocks.SWEET_BERRY_BUSH)) return DANGER_OTHER;
       if (inflictsFireDamage(st)) return DANGER_FIRE;
       if (view.getFluidState(pos).isIn(FluidTags.WATER)) return WATER_BORDER;
       if (st.isOf(Blocks.WITHER_ROSE) || st.isOf(Blocks.POINTED_DRIPSTONE)) return DAMAGE_CAUTIOUS;
    return fallback;   // 实参 WALKABLE

#### 5.4.6 getCommonNodeType（Land 1698–1845）—— **分支顺序即优先级**

    1. state.isAir()                                            -> OPEN
    2. state.isIn(BlockTags.TRAPDOORS) || isOf(LILY_PAD) || isOf(BIG_DRIPLEAF) -> TRAPDOOR
    3. isOf(POWDER_SNOW)                                        -> POWDER_SNOW
    4. isOf(CACTUS) || isOf(SWEET_BERRY_BUSH)                   -> DAMAGE_OTHER
    5. isOf(HONEY_BLOCK)                                        -> STICKY_HONEY
    6. isOf(COCOA)                                              -> COCOA
    7. isOf(WITHER_ROSE) || isOf(POINTED_DRIPSTONE)             -> DAMAGE_CAUTIOUS
    8. fluid = view.getFluidState(pos); if (fluid.isIn(LAVA))   -> LAVA
    9. inflictsFireDamage(state)                                -> DAMAGE_FIRE
    10. block instanceof DoorBlock:
           state.get(DoorBlock.OPEN) -> DOOR_OPEN
           block.getBlockSetType().canOpenByHand() -> DOOR_WOOD_CLOSED  否则 DOOR_IRON_CLOSED
    11. block instanceof AbstractRailBlock                      -> RAIL
    12. block instanceof LeavesBlock                            -> LEAVES
    13. state.isIn(FENCES) || state.isIn(WALLS)
        || (block instanceof FenceGateBlock && !state.get(FenceGateBlock.OPEN)) -> FENCE
    14. !state.canPathfindThrough(view, pos, NavigationType.LAND) -> BLOCKED
    15. fluid.isIn(WATER)                                       -> WATER
    16.                                                         -> OPEN

inflictsFireDamage(state) = state.isIn(BlockTags.FIRE) || isOf(LAVA) || isOf(MAGMA_BLOCK)
|| CampfireBlock.isLitCampfire(state) || isOf(LAVA_CAULDRON)。

AbstractBlock.canPathfindThrough 默认实现（javap -p -c net.minecraft.block.AbstractBlock）：

    switch (NavigationType 序：LAND=0, WATER=1, AIR=2) {
      case 1 (LAND):  return !state.isFullCube(view, pos);
      case 2 (WATER): return view.getFluidState(pos).isIn(FluidTags.WATER);
      case 3 (AIR):   return !state.isFullCube(view, pos);
      default:        return false;
    }

-> **脚手架（ScaffoldingBlock）不覆写 canPathfindThrough**（实测：对该类 javap 结果里没有该方法），
因此 LAND 通行性 = !isFullCube = **true**。
（ScaffoldingBlock.getCollisionShape 返回 COLLISION_SHAPE；该 shape 的确切数值**未验证**，
属于方块状态表的数据问题，不属于寻路算法语义。）

#### 5.4.7 malus 的累加点（**只有这三处**）

1. LandPathNodeMaker.getNodeWith(x,y,z,type,penalty)（字节码 1246–1264）：

       PathNode n = getNode(x,y,z);          // 缓存里的那只
       n.type = type;
       n.penalty = Math.max(n.penalty, penalty);    // 单调不减

2. LandPathNodeMaker.getBlockedNode(x,y,z)：

       PathNode n = getNode(x,y,z); n.type = BLOCKED; n.penalty = -1.0f;   // 直接覆盖，不是 max

3. WaterPathNodeMaker.getPassableNode（字节码 225–286）：

       type = addPathNodePos(x,y,z);
       if ((canJumpOutOfWater && type == BREACH) || type == WATER) {
           float pen = entity.getPathfindingPenalty(type);
           if (pen >= 0.0f) {
               n = getNode(x,y,z); n.type = type; n.penalty = Math.max(n.penalty, pen);
               if (cachedWorld.getFluidState(new BlockPos(x,y,z)).isEmpty()) n.penalty += 8.0f;
           }
       }
       return n;   // 不满足时为 null

MobEntity.getPathfindingPenalty(PathNodeType)（javap -p -c net.minecraft.entity.mob.MobEntity）：

    MobEntity owner = this.getControllingVehicle() instanceof MobEntity m && m.movesIndependently() ? m : this;
    Float f = owner.pathfindingPenalties.get(type);
    return f == null ? type.getDefaultPenalty() : f;

setPathfindingPenalty(type, v) = pathfindingPenalties.put(type, v)（**直接覆盖**）。

**jar 全量字节扫描结论**（getPathfindingPenalty 作为常量池字符串出现于 10 个 class，
其中只有 MobEntity 是声明者，其余 9 个是调用者）：
**1.20.4 里没有任何实体子类覆写 getPathfindingPenalty** —— 每类生物的惩罚表完全由
pathfindingPenalties（构造器里 setPathfindingPenalty）与 PathNodeType.getDefaultPenalty() 决定。

调用 setPathfindingPenalty 的类共 28 个（实测）：
FollowMobGoal, FollowOwnerGoal, AmphibiousPathNodeMaker, MobEntity,
AbstractPiglinEntity, BlazeEntity, BreezeEntity, DrownedEntity, EndermanEntity, GuardianEntity,
RavagerEntity, WardenEntity, WaterCreatureEntity, WitherSkeletonEntity, ZombifiedPiglinEntity,
AnimalEntity, AxolotlEntity, BeeEntity, ChickenEntity, FoxEntity, FrogEntity, GoatEntity,
MerchantEntity, ParrotEntity, SnifferEntity, StriderEntity, TurtleEntity, WolfEntity。

**AmphibiousPathNodeMaker.init 会改写生物自己的惩罚表**（字节码 18–46）：

    entity.setPathfindingPenalty(WATER, 0.0f);
    oldWalkablePenalty = entity.getPathfindingPenalty(WALKABLE);
    entity.setPathfindingPenalty(WALKABLE, 6.0f);
    oldWaterBorderPenalty = entity.getPathfindingPenalty(WATER_BORDER);
    entity.setPathfindingPenalty(WATER_BORDER, 4.0f);

clear() 只还原 WALKABLE 与 WATER_BORDER（**不还原 WATER**）。这是可观测的副作用，原生侧必须复刻。

---

## 6. 终点判定 / Path 的截断与后处理

### 6.1 TargetPathNode

- 由 PathNodeMaker.asTargetPathNode(PathNode) = new TargetPathNode(node) 构造，坐标 = 该 PathNode 的坐标。
- PathNodeNavigator.method_21659(BlockPos bp) = pathNodeMaker.getNode(bp.getX(), bp.getY(), bp.getZ())
  （getNode(double,double,double) 各自 MathHelper.floor）-> asTargetPathNode。
  **注意 PathNodeMaker.getNode(DDD) 的声明返回类型就是 TargetPathNode**（javap 签名实测）。
- updateNearestNode(d, node)：严格 d < nearestNodeDistance 才写（§5.2）。
- markReached() 只置 reached = true；isReached() 读取。
  **导航器并不读 isReached()** —— 它用局部 found 集合代替。

### 6.2 createPath(PathNode endNode, BlockPos target, boolean reachesTarget)

**证据**：字节码 0–57。

    List<PathNode> list = Lists.newArrayList();
    PathNode n = endNode;
    list.add(0, n);                       // 先放终点
    while (n.previous != null) { n = n.previous; list.add(0, n); }   // 不断往「头」插
    return new Path(list, target, reachesTarget);

**这就是全部的后处理：没有去尾、没有平滑、没有 setLength。**
列表顺序 = **从起点到终点**（因为是 add(0, ...)）。

### 6.3 Path 构造器

    Path(List<PathNode> nodes, BlockPos target, boolean reachesTarget):
       this.nodes = nodes ; this.target = target ;
       this.manhattanDistanceFromTarget = nodes.isEmpty()
            ? Float.MAX_VALUE
            : nodes.get(nodes.size() - 1).getManhattanDistance(target);    // 末节点 -> target 的曼哈顿距离
       this.reachesTarget = reachesTarget;
       currentNodeIndex = 0

### 6.4 Path 的其它读取语义

- getLength() = nodes.size()
- next() = currentNodeIndex++；isStart() = currentNodeIndex <= 0；isFinished() = currentNodeIndex >= nodes.size()
- getEnd() = 空表返回 null，否则最后一个
- getLastNode() = currentNodeIndex > 0 ? nodes.get(currentNodeIndex-1) : null
- setLength(int i)：**只截断，不补齐** —— if (nodes.size() > i) nodes.subList(i, nodes.size()).clear()
- setNode(int, PathNode)、setCurrentNodeIndex(int) 是纯写入
- equalsPath(Path)：长度不同 -> false；逐节点比 x/y/z

**Path 的构造与截断都不参与 findPathToAny 的返回值选择**（选择只按 §4.3 的比较器）。

### 6.5 终点判定用曼哈顿距离

current.getManhattanDistance(t) <= (float)reachRadius，其中 t 是 TargetPathNode（PathNode），
所以走的是 getManhattanDistance(PathNode) = (float)(|dx|+|dy|+|dz|)。

### 6.6 getFeetY（Land 818–869）

    double getFeetY(BlockPos pos) {                     // 实例方法
        if ((canSwim() || isAmphibious()) && cachedWorld.getFluidState(pos).isIn(FluidTags.WATER))
            return (double)pos.getY() + 0.5;
        return getFeetY(cachedWorld, pos);              // 静态方法
    }
    static double getFeetY(BlockView view, BlockPos pos) {
        BlockPos below = pos.down();
        VoxelShape shape = view.getBlockState(below).getCollisionShape(view, below);
        return (double)below.getY() + (shape.isEmpty() ? 0.0 : shape.getMax(Direction.Axis.Y));
    }

---

## 7. 特判分支清单（必须逐条复刻）

### 7.1 LandPathNodeMaker.getPathNode(x, y, z, maxYStep, prevFeetY, direction, nodeType)

**证据**：字节码 876–1234。逐步：

     1) feetY = getFeetY(mutable.set(x, y, z))                    // 实例 getFeetY
     2) if (feetY - prevFeetY > getStepHeight()) return null;     // getStepHeight() = Math.max(1.125, entity.getStepHeight())
     3) type = getNodeType(entity, x, y, z); penalty = entity.getPathfindingPenalty(type);
     4) halfWidth = (double)entity.getWidth() / 2.0
     5) PathNode result = null;
        if (penalty >= 0.0f) result = getNodeWith(x, y, z, type, penalty);
     6) if (isBlocked(nodeType /* 形参 */) && result != null && result.penalty >= 0.0f && !isBlocked(result))
            result = null;
     7) if (type == WALKABLE || (isAmphibious() && type == WATER)) return result;
     8) if (result == null || result.penalty < 0.0f) {
            if (maxYStep > 0
                && !(type == FENCE && !canWalkOverFences())
                && type != UNPASSABLE_RAIL && type != TRAPDOOR && type != POWDER_SNOW) {
                PathNode up = getPathNode(x, y+1, z, maxYStep - 1, prevFeetY, direction, nodeType);   // 递归
                if (up != null && (up.type == OPEN || up.type == WALKABLE) && entity.getWidth() < 1.0f) {
                    double dx = (x - direction.getOffsetX()) + 0.5;
                    double dz = (z - direction.getOffsetZ()) + 0.5;
                    Box box = new Box(
                        dx - halfWidth,
                        getFeetY(mutable.set(dx, y + 1, dz)) + 0.001,
                        dz - halfWidth,
                        dx + halfWidth,
                        (double)entity.getHeight() + getFeetY(mutable.set(up.x, up.y, up.z)) - 0.002,
                        dz + halfWidth);
                    if (checkBoxCollision(box)) result = null;
                }
            }
        }
     9) if (!isAmphibious() && type == WATER && !canSwim()) {
            if (getNodeType(entity, x, y - 1, z) != WATER) return result;
            while (y > world.getBottomY()) {
                y--;
                type = getNodeType(entity, x, y, z);
                if (type != WATER) return result;
                result = getNodeWith(x, y, z, type, entity.getPathfindingPenalty(type));
            }
        }
    10) if (type == OPEN) {
            int fall = 0, y0 = y;
            while (type == OPEN) {
                if (--y < world.getBottomY()) return getBlockedNode(x, y0, z);      // 用原始 y
                if (fall++ >= entity.getSafeFallDistance()) return getBlockedNode(x, y, z);
                type = getNodeType(entity, x, y, z);
                penalty = entity.getPathfindingPenalty(type);
                if (type != OPEN && penalty >= 0.0f) { result = getNodeWith(x, y, z, type, penalty); break; }
                if (penalty < 0.0f) return getBlockedNode(x, y, z);
            }
        }
    11) if (isBlocked(type) && result == null) {
            result = getNode(x, y, z);
            result.visited = true;
            result.type = type;
            result.penalty = type.getDefaultPenalty();
        }
    12) return result;

第 8 步的 Box 实参顺序（字节码 307–392）：minX = dx - halfWidth；
minY = getFeetY(set(dx, y+1, dz)) + 0.001；minZ = dz - halfWidth；maxX = dx + halfWidth；
maxY = (double)entity.getHeight() + getFeetY(set(up.x, up.y, up.z)) - 0.002；maxZ = dz + halfWidth。
（注意 maxY 是「身高 + feetY(up) - 0.002」，不是 feetY 单独。）

isBlocked(PathNodeType) = type == FENCE || type == DOOR_WOOD_CLOSED || type == DOOR_IRON_CLOSED
（静态，字节码 721–735）。

isBlocked(PathNode)（字节码 737–816）：

    Box box = entity.getBoundingBox();
    Vec3d v = new Vec3d(node.x - entity.getX() + box.getLengthX()/2.0,
                        node.y - entity.getY() + box.getLengthY()/2.0,
                        node.z - entity.getZ() + box.getLengthZ()/2.0);
    int steps = MathHelper.ceil(v.length() / box.getAverageSideLength());
    v = v.multiply((double)(1.0f / (float)steps));    // 注意是 float 除法再转 double（字节码 fconst_1/fdiv/f2d）
    for (int i = 1; i <= steps; i++) { box = box.offset(v); if (checkBoxCollision(box)) return false; }
    return true;

checkBoxCollision(Box) = collidedBoxes.computeIfAbsent(box, b -> !cachedWorld.isSpaceEmpty(entity, b))
（按 Box.equals/hashCode 去重的结果缓存；clear() 时清空）。

### 7.2 门

- 类型：getCommonNodeType 第 10 步（DOOR_OPEN / DOOR_WOOD_CLOSED / DOOR_IRON_CLOSED）。
  canOpenByHand 来自 DoorBlock.getBlockSetType().canOpenByHand()。
- adjustNodeType：DOOR_WOOD_CLOSED && canOpenDoors() && canEnterOpenDoors() -> WALKABLE_DOOR；
  DOOR_OPEN && !canEnterOpenDoors() -> BLOCKED。
- isValidDiagonalSuccessor：三节点任一为 WALKABLE_DOOR 直接返回 false。
- isBlocked(PathNodeType) 包含两种闭合门 -> §7.1 第 6 / 11 步要生效。
- canOpenDoors / canEnterOpenDoors 是 PathNodeMaker 的 protected boolean 字段
  （由 setCanOpenDoors/setCanEnterOpenDoors 设置；adjustNodeType 会读）。

### 7.3 栅栏

- FENCE 来自 getCommonNodeType 第 13 步（FENCES 标签 / WALLS 标签 / 关闭的栅栏门）。
- getNodeType 里 set.contains(FENCE) 直接返回 FENCE（优先级高于逐项挑惩罚）。
- §7.1 第 8 步：type == FENCE && !canWalkOverFences() 时不尝试「上台阶」。
- §2.4：两侧都是 FENCE 且 entity.getWidth() < 0.5 时 flag5 = true，会短路掉对角拒绝逻辑。

### 7.4 脚手架

见 §5.4.6：ScaffoldingBlock 不覆写 canPathfindThrough -> LAND 通行 = !isFullCube = true；
也不会命中 getCommonNodeType 第 2 步的 TRAPDOORS 标签（脚手架不是 trapdoor）。
它的 getCollisionShape 是顶部薄板 -> 影响 getFeetY（shape.getMax(Y)）。
**shape 的确切数值未验证**（属方块状态表数据）。

### 7.5 台阶（slab / stairs）

原版**没有**针对 slab/stairs 的专门分支 —— 它们只通过
getCommonNodeType（isFullCube 决定 canPathfindThrough）与 getFeetY（碰撞盒 maxY = 0.5 / 1.0）
间接参与。**「台阶特判」= getStepHeight()/maxYStep 机制 + getFeetY 的高度差**，没有额外 switch。

### 7.6 水 / 岩浆边缘

- WATER / WATER_BORDER / LAVA 来自 getCommonNodeType 第 8/15 步 与
  getNodeTypeFromNeighbors 的 WATER_BORDER。
- §7.1 第 9 步是「不会游泳的生物在水里往下沉」的特判。
- WaterPathNodeMaker 的 BREACH（跳出水面）见 §8.2。
- AmphibiousPathNodeMaker.getSuccessors 会额外补 UP/DOWN 两个邻居（见 §8.3）。

### 7.7 maxFallDistance

原版 **没有名为 maxFallDistance 的字段**；等价机制是
MobEntity.getSafeFallDistance()（§7.1 第 10 步的循环上限）。
getSafeFallDistance() 的默认实现**未验证**（属生物档案输入）。

---

## 8. PathNodeType 枚举序与 malus 表

**证据**：javap -p -c net.minecraft.entity.ai.pathing.PathNodeType 的 static {} 与 method_36788()。

| ordinal | 常量 | defaultPenalty |
| --- | --- | --- |
| 0 | BLOCKED | -1.0f |
| 1 | OPEN | 0.0f |
| 2 | WALKABLE | 0.0f |
| 3 | WALKABLE_DOOR | 0.0f |
| 4 | TRAPDOOR | 0.0f |
| 5 | POWDER_SNOW | -1.0f |
| 6 | DANGER_POWDER_SNOW | 0.0f |
| 7 | FENCE | -1.0f |
| 8 | LAVA | -1.0f |
| 9 | WATER | 8.0f |
| 10 | WATER_BORDER | 8.0f |
| 11 | RAIL | 0.0f |
| 12 | UNPASSABLE_RAIL | -1.0f |
| 13 | DANGER_FIRE | 8.0f |
| 14 | DAMAGE_FIRE | 16.0f |
| 15 | DANGER_OTHER | 8.0f |
| 16 | DAMAGE_OTHER | -1.0f |
| 17 | DOOR_OPEN | 0.0f |
| 18 | DOOR_WOOD_CLOSED | -1.0f |
| 19 | DOOR_IRON_CLOSED | -1.0f |
| 20 | BREACH | 4.0f |
| 21 | LEAVES | -1.0f |
| 22 | STICKY_HONEY | 8.0f |
| 23 | COCOA | 0.0f |
| 24 | DAMAGE_CAUTIOUS | 0.0f |
| 25 | DANGER_TRAPDOOR | 0.0f |

共 26 个常量；method_36788() 建的数组顺序**就是 ordinal 顺序 0..25**。
getDefaultPenalty() 只返回构造时传入的 defaultPenalty 字段。

### 8.2 WaterPathNodeMaker.addPathNodePos / getNodeType（class_12）

addPathNodePos(x,y,z) = nodePosToType.computeIfAbsent(BlockPos.asLong(x,y,z), k -> getDefaultNodeType(cachedWorld, x,y,z))
（getDefaultNodeType 就是 getNodeType(BlockView,x,y,z,entity)）。

getNodeType（字节码 317–403）—— **对 entityBlockXSize × entityBlockYSize × entityBlockZSize 的体素盒**：

    for (int px = x; px < x + entityBlockXSize; px++)
     for (int py = y; py < y + entityBlockYSize; py++)
      for (int pz = z; pz < z + entityBlockZSize; pz++) {
         FluidState fluid = view.getFluidState(mutable.set(px,py,pz));
         BlockState st = view.getBlockState(mutable.set(px,py,pz));
         if (fluid.isEmpty() && st.canPathfindThrough(view, mutable.down(), NavigationType.WATER) && st.isAir())
             return BREACH;
         if (!fluid.isIn(FluidTags.WATER)) return BLOCKED;
      }
    return view.getBlockState(mutable /* 最后一次 set 的值 */)
              .canPathfindThrough(view, mutable, NavigationType.WATER) ? WATER : BLOCKED;

### 8.3 AmphibiousPathNodeMaker.getSuccessors（class_15，继承 LandPathNodeMaker）

    int n = super.getSuccessors(array, node);       // 先放陆地 8 个
    PathNodeType above = getNodeType(entity, x, y+1, z);
    PathNodeType here  = getNodeType(entity, x, y,   z);
    int step = (entity.getPathfindingPenalty(above) >= 0.0f && here != STICKY_HONEY)
               ? MathHelper.floor(Math.max(1.0f, entity.getStepHeight())) : 0;   // else 显式置 0
    double feetY = getFeetY(new BlockPos(x, y, z));
    PathNode up   = getPathNode(x, y+1, z, Math.max(0, step - 1), feetY, Direction.UP,   here);
    PathNode down = getPathNode(x, y-1, z, step,                 feetY, Direction.DOWN, here);
    if (isValidAquaticAdjacentSuccessor(up, node)) array[n++] = up;
    if (isValidAquaticAdjacentSuccessor(down, node) && here != TRAPDOOR) array[n++] = down;
    for (int i = 0; i < n; i++)
        if (array[i].type == WATER && this.penalizeDeepWater && array[i].y < world.getSeaLevel() - 10)
            array[i].penalty += 1.0f;
    return n;

isValidAquaticAdjacentSuccessor(n, host) = isValidAdjacentSuccessor(n, host) && n.type == WATER。

getDefaultNodeType（Amphibious 覆写）：先 getCommonNodeType；
若为 WATER 则遍历 Direction.values() 找任一邻居的 getCommonNodeType == BLOCKED -> WATER_BORDER，否则 WATER；
否则 getLandNodeType。
getStart()：不在水里 -> super.getStart()；在水里 -> getStart(new BlockPos(floor(box.minX), floor(box.minY + 0.5), floor(box.minZ)))。
getNode(double,double,double)：floor(x), floor(y + 0.5), floor(z)（**注意 y 加了 0.5**）。

### 8.4 BirdPathNodeMaker（class_6）关键点

- getNodeType(int,int,int) 有自己的 Long2ObjectMap<PathNodeType> pathNodes 缓存。
- getDefaultNodeType（字节码 1211–1306）：先 getCommonNodeType；
  若为 OPEN 且 y >= view.getBottomY()+1，看下方方块类型：
  DAMAGE_FIRE|LAVA -> DAMAGE_FIRE；DAMAGE_OTHER -> DAMAGE_OTHER；COCOA -> COCOA；
  FENCE 且 mutable != entity.getBlockPos() -> FENCE；
  WALKABLE|OPEN|WATER -> OPEN；否则 WALKABLE；
  最后若结果是 WALKABLE 或 OPEN -> getNodeTypeFromNeighbors(view, mutable.set(x,y,z), result)。
- getPassableNode(x,y,z)：type = getNodeType(x,y,z)（私有缓存版）；
  pen = entity.getPathfindingPenalty(type)；
  若 pen >= 0.0f：n = getNode(x,y,z); n.type = type; n.penalty = Math.max(n.penalty, pen);
  再 **if (type == WALKABLE) n.penalty += 1.0f;**；返回 n（否则 null）。
- getStart()：游泳且触水时，从 entity.getBlockY() 起向上跳过连通水；否则 floor(entity.getY()+0.5)；
  再 BlockPos.ofFloored(x, y, z)；若 !canPathThrough(bp) 则遍历 getPotentialEscapePositions(entity)
  找第一个 canPathThrough 的返回 getStart(pos)；否则 getStart(bp)。
- canPathThrough(BlockPos) = entity.getPathfindingPenalty(getNodeType(entity, bp)) >= 0.0f
  （**没有 OPEN 特例**，与 Land 版不同）。

---

## 9. 参照实现（cava.oracle）—— 结构、输入抽象与纪律

### 9.1 位置与隔离

    src/test/java/cava/oracle/

**不 import 任何 net.minecraft 类型**。本机实测证明（命令与输出见 §12）：

    Select-String -Path src/test/java/cava/oracle/*.java -Pattern '^\s*import\s'                                  # 全部是 java.*
    Select-String -Path src/test/java/cava/oracle/*.java -Pattern '^\s*import\s+(net\.minecraft|net/minecraft)'   # (空)
    # 再把编译产物常量池里的字符串 'net/minecraft' 扫一遍：命中 0 / 23 个 class

### 9.2 类清单

| 文件 | 对应原版 | 说明 |
| --- | --- | --- |
| Pnt.java | PathNodeType | 26 个常量 + defaultPenalty（§8 表） |
| Mth.java | MathHelper | sqrt/floor/ceil 的逐位复刻 |
| Dir.java | Direction | 枚举序、偏移、Type.HORIZONTAL 顺序、rotateYClockwise |
| BlockKind.java | 方块状态的派生属性 | 位标志 + 流体 + 碰撞 minY/maxY |
| Box.java | util.math.Box | 只保留寻路用到的部分 |
| Terrain.java | ChunkCache / BlockView | 调色板 + 3D 数组；全部方块查询的唯一入口 |
| MobProfile.java | MobEntity | 尺寸/步高/安全坠落/门能力/游泳/惩罚表/实体位姿 |
| PNode.java | PathNode | 字段 1:1，含 hash(int,int,int) |
| TargetNode.java | TargetPathNode | |
| NodeMaker.java | PathNodeMaker | 含「坏哈希去重缓存」 |
| LandMaker.java | LandPathNodeMaker + AmphibiousPathNodeMaker | amphibious / penalizeDeepWater 开关 |
| MinHeap.java | PathMinHeap | §1 逐分支复刻 |
| Navigator.java | PathNodeNavigator | §4 主循环 + §4.3 结果选择 |
| PathResult.java | Path | 节点序列（坐标 + 各 float 原样位模式）+ 展开轨迹哈希 |
| Xorshift.java | — | 可复现伪随机 |
| TerrainGen.java | — | 场景生成 |
| VectorGen.java | — | 生成 src/test/resources/cava/oracle/*.bin |
| OracleSelfTest.java | — | public static void main，跑一遍并打印统计 |

**本迭代覆盖的范围**：LandPathNodeMaker（含 Amphibious 变体）。
WaterPathNodeMaker / BirdPathNodeMaker 的**语义已被本规格固化**（§2.5 / §2.6 / §8.2 / §8.4），
但**尚未落成 Java 参照实现** —— 它们各自还有 getStart / getPotentialEscapePositions /
getNodeType 体素盒等未复刻部分，见 §11 的待办清单。P1 的验收场景（陆地生物）不依赖它们。

### 9.3 输入抽象（形状由本规格冻结）

Terrain 是唯一的方块查询入口：

    public final class Terrain {
        int minY(); int seaLevel();
        int paletteIndexAt(int x, int y, int z);   // 越界 -> 0
        BlockKind kindAt(int x, int y, int z);
        int fluidAt(int x, int y, int z);          // NONE / WATER / LAVA
        boolean fluidIsWater / fluidIsLava / fluidIsEmpty(int x,int y,int z);
        double staticFeetY(int x, int y, int z);   // (y-1) + collisionMaxY(x, y-1, z)
        boolean isSpaceEmpty(minX,minY,minZ,maxX,maxY,maxZ);
        long worldHash();
    }

BlockKind 的位（与 §5.4.6 的分支一一对应）：

    AIR, TRAPDOOR, POWDER_SNOW, CACTUS_OR_BERRY, HONEY, COCOA, CAUTIOUS,
    DOOR, DOOR_OPEN, DOOR_HAND, RAIL, LEAVES, FENCE_TAG, WALL_TAG,
    FENCE_GATE, FENCE_GATE_OPEN, FIRE_DAMAGE, PATHFIND_LAND, WATER_BLOCK
    + fluid: FLUID_NONE | FLUID_WATER | FLUID_LAVA
    + collisionMinY / collisionMaxY（float）

**越界约定**：调色板下标 0 固定为 OUT_OF_WORLD —— AIR、PATHFIND_LAND、无流体、碰撞高度 0。
所有越界查询都返回下标 0。

MobProfile 是唯一的生物档案入口；getPathfindingPenalty / setPathfindingPenalty 精确复刻
MobEntity（§5.4.7）。

### 9.4 与原版的有意偏离（只有这几处，其余逐指令照抄）

1. **checkBoxCollision 不做结果缓存**。原版用 Object2BooleanMap&lt;Box&gt; 记忆化，
   但 !world.isSpaceEmpty(entity, box) 在一次寻路内是纯函数 —— 缓存只影响性能，不影响结果。
2. **碰撞模型是扁平 AABB**（每方块 minY..maxY），不是体素形状集合。这是契约里规定的镜像形态
   （docs/CAVA-工程接口契约.md 与 prompts/04 都写明「扁平 AABB 数组，不要用体素近似」）。
   Box 之间的相交判定用原版的严格不等号。
3. **Amphibious 用开关表示**（LandMaker.amphibious），不是子类。语义与继承版一致
   （super.getSuccessors 先跑、再补 UP/DOWN、最后统一加深水惩罚）。
4. **found / targets 的集合语义**用 java.util.HashSet / HashMap 直接表达（同一个 JDK 的桶顺序），
   并按 Guava 的 Maps.capacity 公式算容量 —— 这样多目标时的选择顺序与原版一致（§4.3）。
5. PathNodeMaker.pathNodeCache 用 HashMap&lt;Integer,PNode&gt; 代替
   Int2ObjectOpenHashMap&lt;PathNode&gt; —— 「同 int 键 -> 同实例」的语义完全一致。

### 9.5 数值纪律（硬性）

1. **所有代价运算保持 float**；只有 MathHelper.sqrt 内部、getFeetY / Box / 实体包围盒用 double
   （原版就是 double）。
2. fcmpg / fcmpl 的方向逐条照抄；float 比较一律「先算后比」，不合并条件。
3. **不重排分支顺序**，不做「顺手优化」。
4. 浮点位模式用 Float.floatToRawIntBits 原样导出（-0.0f 与 NaN 不做规范化）。
5. isBlocked(PathNode) 里的 1.0f / (float) steps 在 steps == 0 时是 +Inf/NaN，
   原版循环不执行 —— 照抄，**不加保护分支**。

---

## 10. 测试向量

### 10.1 位置与分片（本机实跑结果）

    src/test/resources/cava/oracle/
      manifest.txt          452 bytes    人读说明 + 种子 + 复现命令
      vectors-00.bin        3,868,453 bytes
      vectors-01.bin           65,510 bytes
      golden-00.bin           276,856 bytes
      合计                  4,211,271 bytes (4.02 MB)

单文件严格 < 5 MB。默认 10000 组（= 要求的下限 10^4）；组数可用
java -cp out cava.oracle.OracleSelfTest &lt;outDir&gt; &lt;cases&gt; 放大。

### 10.2 复现命令（实测可用）

    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
    & 'C:\Program Files\Java\jdk-21\bin\javac.exe' --release 21 -encoding UTF-8 -d out (Get-ChildItem -Recurse src/test/java/cava/oracle/*.java | ForEach-Object { $_.FullName })
    & 'C:\Program Files\Java\jdk-21\bin\java.exe' -cp out cava.oracle.OracleSelfTest

也可以一条命令：node tools/gen-pathfind-vectors.cjs （它只是上面两条的包装）。

**幂等性实测**：连跑两次，四个文件的 SHA-256 全部相同（见 §12.3）。

### 10.3 二进制格式（**全部大端**，Java DataOutputStream 默认字节序）

**vectors-NN.bin**

    header (28 字节):
      char[4]  "CVOV"
      u16      version = 1
      u16      reserved = 0
      u32      caseCount        // 本分片的组数
      u64      masterSeed       = 0x1F2E3D4C5B6A7988
      u32      shardIndex
      u32      shardCount

    per case —— input:
      u32 caseId
      u64 caseSeed
      u8  scenario          // 0 平地 1 随机障碍 2 台阶 3 水 4 岩浆 5 门 6 栅栏 7 脚手架 8 迷宫 9 混合
      u8  makerKind         // 0 = LAND, 1 = AMPHIBIOUS
      u16 reserved
      i32 originX, originY, originZ
      u16 sizeX, sizeY, sizeZ
      i32 minY              // = originY
      i32 seaLevel
      i32 groundY
      f32 profileWidth, profileHeight, profileStepHeight
      i32 safeFallDistance
      u8  profileFlags      // bit0 canOpenDoors, 1 canEnterOpenDoors, 2 canSwim,
                            // 3 canWalkOverFences, 4 amphibious, 5 penalizeDeepWater,
                            // 6 onGround, 7 touchingWater
      u8  reserved[3]
      f64 entityX, entityY, entityZ
      u8  canWalkOnFluid
      u8  reserved[7]
      u32 penaltyMask       // bit i = 第 i 个 PathNodeType 被显式覆盖
      f32 penalty[26]       // 覆盖后的**有效值**（getPathfindingPenalty 的结果）
      i32 targetX, targetY, targetZ
      u16 range             // PathNodeNavigator 构造参数 range
      f32 maxRange
      i32 reachRadius
      f32 followRange
      u32 worldHashLow      // Terrain.worldHash() 的低 32 位

    per case —— output:
      u8  found             // false 时后面只有这 4 个字段 + 无节点
      u16 nodeCount         // 0xFFFF 表示无路径
      u8  reachesTarget     // 注意：原版语义是反的，见 §4.3.1
      u32 expandedCount     // 主循环里成功 pop 的次数
      u64 traceHash         // 展开轨迹 FNV-1a 64（§10.4）
      f32 manhattanDistanceFromTarget
      per node (nodeCount 个):
        第 0 个: i32 x, i32 y, i32 z
        其余  : i8 dx, i8 dy, i8 dz   // 相对上一节点（路径每步位移 <= 1，一定放得下）
        u8  typeOrdinal
        u8  flags           // bit0 visited
        f32 pathLength
        f32 penalizedPathLength
        f32 distanceToNearestTarget
        f32 heapWeight
        f32 penalty

（即每节点 21 字节，首个节点多 9 字节。）

**golden-00.bin**（60 组，覆盖全部 10 个场景；caseId = 0..59）

    header (18 字节): char[4] "CVOG", u16 version = 1, u16 reserved = 0,
                      u32 caseCount, u64 masterSeed
    per case:
      与 vectors 完全相同的 input 段
      u16 paletteCount = 16
      palette[16] { u16 flags ; u8 fluid ; u8 reserved ; f32 collisionMinY ; f32 collisionMaxY }
      u8[sizeX * sizeY * sizeZ] blocks        // 索引顺序 (y * sizeZ + z) * sizeX + x
      与 vectors 完全相同的 output 段

### 10.4 traceHash（展开轨迹）

    h = 0xCBF29CE484222325
    for each popped node n (在 pop 之后、任何后续松弛之前采样):
        h ^= (u64)(n.x & 0xFFFFFFFF); h *= 0x100000001B3
        h ^= (u64)(n.y & 0xFFFFFFFF); h *= 0x100000001B3
        h ^= (u64)(n.z & 0xFFFFFFFF); h *= 0x100000001B3
        h ^= (u64)floatToRawIntBits(n.heapWeight); h *= 0x100000001B3
        h ^= (u64)floatToRawIntBits(n.penalizedPathLength); h *= 0x100000001B3

FNV-1a 64，offset basis = 0xCBF29CE484222325，prime = 0x100000001B3
（与 docs/CAVA-工程接口契约.md §4.2 的哈希约定一致）。
**这是「堆语义 + 访问顺序」的强指纹**：任何 sift 分支读错、任何邻居顺序读错，它都会变。

### 10.5 worldHash

生成完 blocks[]（调色板下标序列，**不含调色板**，遍历顺序 y 外层 / z 中层 / x 内层）之后：

    h = 0xCBF29CE484222325
    for each byte b: h ^= (u64)b; h *= 0x100000001B3

低 32 位写进向量的 worldHashLow。移植方只要重现同样的 blocks[] 就能对上。

### 10.6 生成算法（必须逐位可复现）

**伪随机**：xorshift64*（自研，不用 java.util.Random）

    state: u64 x != 0（seed == 0 时用 0x9E3779B97F4A7C15）
    next():
        x ^= x >>> 12; x ^= x << 25; x ^= x >>> 27;      // 无符号右移
        state = x;
        return x * 0x2545F4914F6CDD1D                    // u64 回绕
    nextInt(bound) = (int)((next() >>> 1) % bound)
    nextFloat()    = (float)((next() >>> 40) * (1.0 / 16777216.0))
    nextBoolean()  = (next() >>> 63) != 0

**caseSeed**

    caseSeed = mix(MASTER_SEED, caseId)
    mix(a,b): z = a ^ (b * 0x9E3779B97F4A7C15); z ^= z>>>29; z *= 0xBF58476D1CE4E5B9;
              z ^= z>>>32; z *= 0x94D049BB133111EB; return z ^ (z>>>31)

**参数抽取顺序**（用 Xorshift(caseSeed) 这一个流，严格按此顺序消费）：

    1.  scenario   = nextInt(10)
    2.  makerKind  = nextInt(2)
    3.  sizeX = 14 + nextInt(5); sizeZ = 14 + nextInt(5); sizeY = 14 + nextInt(4)
    4.  originX = -400 + nextInt(801); originZ = -400 + nextInt(801); originY = 40 + nextInt(60)
    5.  seaLevel = originY + 6 + nextInt(6);  groundY = originY + 5 + nextInt(3)
    6.  width      = pick{0.4,0.5,0.6,0.7,0.9,1.2,1.4}
        height     = pick{0.7,1.0,1.3,1.8,2.1,2.9}
        stepHeight = pick{0.0,0.5,0.6,1.0,1.0625}
        safeFallDistance = pick{0,1,2,3,4}
    7.  canOpenDoors / canEnterOpenDoors / canSwim / canWalkOverFences = 各一次 nextBoolean
        amphibious = (makerKind == 1); penalizeDeepWater = amphibious && nextBoolean
        onGround = true
    8.  overrides = nextInt(6); 重复 overrides 次:
            setPathfindingPenalty(pick(Pnt.values()), pick{0,0,1,2,4,8,16,-1})
    9.  地形 = TerrainGen.generate(caseSeed ^ 0xA5A5A5A5A5A5A5A5, scenario, ...)   // 独立一条流
   10.  起点：最多 24 次 { sx = nextInt(sizeX); sz = nextInt(sizeZ) } 直到
            kindAt(originX+sx, groundY+1, originZ+sz) 有 AIR 或 PATHFIND_LAND
        实体位姿 = (originX+sx+0.5, groundY+1, originZ+sz+0.5)
        touchingWater = fluidIsWater(originX+sx, groundY+1, originZ+sz)
                     || fluidIsWater(originX+sx, groundY,   originZ+sz)
   11.  目标：最多 32 次 { tx = nextInt(sizeX); tz = nextInt(sizeZ) } 直到
            |tx-sx| + |tz-sz| >= 5；ty = groundY + nextInt(3)
   12.  range = pick{8,16,24,32}
        followRange = pick{2,4,8,12,16,24,32}
        maxRange = pick{3,4,5,6,8,12,16,24,32}
        reachRadius = pick{0,0,1,1,2}

**地形生成**（TerrainGen.generate，用自己的 Xorshift(terrainSeed)）：

    基础层：worldY < groundY -> DIRT；worldY == groundY -> STONE；其余 AIR
    然后按 scenario 追加（每步的随机消费顺序见 TerrainGen.java，与本表一一对应）：
      0 FLAT        : 无
      1 OBSTACLES   : n = 12+nextInt(24) 根柱子，每根 {x=nextInt(sizeX), z=nextInt(sizeZ),
                      h=1+nextInt(3), kind=nextInt(4)->STONE/FENCE/LEAVES/BUSH}
      2 STAIRS      : dir=nextInt(2), len=4+nextInt(4), x0=2+nextInt(max(1,sizeX-6)),
                      z0=2+nextInt(max(1,sizeZ-6))；每级 3x3 的 SLAB，高度 groundY+1+k
      3 WATER       : 中心与两个半径随机，池底与池面两层都是 WATER
      4 LAVA        : 3x3 的 LAVA，落在 groundY 那一层
      5 DOORS       : 一堵 STONE 墙（两格高）+ 一个门洞（DOOR_CLOSED 或 DOOR_OPEN，随机）
      6 FENCE       : 一行 FENCE + 一个随机缺口
      7 SCAFFOLDING : 1..3 层 3x3 的 SCAFFOLDING
      8 MAZE        : 网格墙 (x%3==0 && z%3!=1)||(z%3==0 && x%3!=1)，且 nextInt(10)<8，两格高
      9 MIXED       : 40 根随机柱子，10 种方块随机

**调色板（固定 16 项，下标即 golden 文件里的索引）**

| idx | 名字 | flags | fluid | collision |
| --- | --- | --- | --- | --- |
| 0 | OUT_OF_WORLD | AIR+PATHFIND_LAND | NONE | 0..0 |
| 1 | AIR | AIR+PATHFIND_LAND | NONE | 0..0 |
| 2 | STONE | — | NONE | 0..1 |
| 3 | DIRT | — | NONE | 0..1 |
| 4 | SLAB | PATHFIND_LAND | NONE | 0..0.5 |
| 5 | WATER | WATER_BLOCK+PATHFIND_LAND | WATER | 0..0 |
| 6 | LAVA | — | LAVA | 0..0 |
| 7 | DOOR_CLOSED | DOOR+DOOR_HAND | NONE | 0..0 |
| 8 | DOOR_OPEN | DOOR+DOOR_OPEN+PATHFIND_LAND | NONE | 0..0 |
| 9 | FENCE | FENCE_TAG | NONE | 0..1.5 |
| 10 | SCAFFOLDING | PATHFIND_LAND | NONE | 0.875..1 |
| 11 | LEAVES | LEAVES | NONE | 0..1 |
| 12 | HONEY | HONEY | NONE | 0..1 |
| 13 | RAIL | RAIL+PATHFIND_LAND | NONE | 0..0.0625 |
| 14 | CACTUS | CACTUS_OR_BERRY | NONE | 0..1 |
| 15 | BUSH | PATHFIND_LAND | NONE | 0..0 |

### 10.7 本机实跑的统计（10000 组）

    组数              : 10000
    有路径(Path!=null): 10000 (100.00%)
    真的抵达目标      : 1785 (17.85%)   <- Path.reachesTarget() == false
    未抵达(回退路径)  : 8215 (82.15%)   <- Path.reachesTarget() == true
    平均路径节点数    : 4.874    最大 24
    平均展开节点数    : 34.121   最大 1023
    f 值 min/max/avg  : 0.0000 / 171.5000 / 20.9751（样本 48737）
    生成耗时          : 1.68 s（单线程）
    逐场景（组数）：FLAT 1001 / OBSTACLES 1028 / STAIRS 984 / WATER 936 / LAVA 993 /
                    DOORS 1024 / FENCE 945 / SCAFFOLDING 1015 / MAZE 1038 / MIXED 1036

---

## 11. 还没验证 / 明确留白的部分

以下条目**没有被本规格证实**，实现方必须先自行取字节码证据再动手：

1. MobEntity.getSafeFallDistance() 的默认返回值、getStepHeight() 的默认值 —— 未查
   （属生物档案输入；参照实现把它们当参数）。
2. ScaffoldingBlock.COLLISION_SHAPE 的确切数值 —— 未查（属方块状态表数据）。
   LAND 可通行性 = !isFullCube **已证实**（AbstractBlock.canPathfindThrough 的默认实现）。
3. PathNodeMaker.pathNodeCache 构造时的预期容量会不会影响 computeIfAbsent 的结果 ——
   **不影响**（同 int 键只可能映射到一只实例）；参照实现按「同键 -> 同实例」实现。
4. Collectors.toMap / HashSet 的桶遍历顺序在跨 JDK 版本上是否稳定 ——
   参照实现按 JDK 21 的 java.util.HashMap 行为对齐。若要跨 JDK 稳定，
   **建议在 Java 钩子里限制「多目标不走原生加速」**（见 §4.3 的不确定性提示）。
5. AmphibiousPathNodeMaker.getStart 在水里用的是 entity.getBoundingBox() 的 minX/minY/minZ，
   而 getBoundingBox() 在实体有 pose（游泳/爬行）时会变 —— **未验证 pose 对它的影响**。
6. **WaterPathNodeMaker / BirdPathNodeMaker 尚未落成参照实现**。它们的邻居顺序与守卫条件
   已经逐条固化（§2.5 / §2.6），但还缺：
   - WaterPathNodeMaker.getStart（字节码 41–64，未读）
   - BirdPathNodeMaker.getPotentialEscapePositions（字节码 1308–1429，只读到一半）
   - BirdPathNodeMaker.getStart 的完整语义（字节码 45–145，已读，但依赖上一条）
   - WaterPathNodeMaker.clear / nodePosToType 的生命周期细节
7. **PNode.hash 的位碰撞在 14–18 格宽、14–17 格高的测试世界里不可能被触发**
   （需要 y 相差 256、或 x/z 相差 32768）。参照实现用不变式自检直接验证了
   「同哈希 -> 同实例」（OracleSelfTest 第 2 项），但没有端到端向量覆盖它。
   **建议 P1 在整服层专门造一个 y 跨度 > 256 或坐标差 32768 的场景来补这一条。**
8. Carpet 的 fastRedstoneDust 与本子系统无关；但**服务器装了 Lithium / ServerCore**：
   Lithium 完全不碰 class_13；ServerCore 在方法体内做 @Redirect / @ModifyVariable
   （只换容器实现，prompts/04 已记）。**「换容器实现不改变结果」这一条未经验证** ——
   这是差分测试的职责，不是本规格的结论。
9. **toolbox 第 2 节的 jar 路径要改**（见 §0.1）。这条需要 captain 或 toolbox 的所有者执行，
   本文档只报告事实：clientonly jar 里没有服务端类，必须用 minecraft-common jar。

---

## 12. 本机实跑证据（可直接复制）

### 12.1 编译 + 运行

    PS J:\mc\Cava> [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    PS J:\mc\Cava> $env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
    PS J:\mc\Cava> & 'C:\Program Files\Java\jdk-21\bin\javac.exe' --release 21 -encoding UTF-8 -d out (Get-ChildItem -Recurse src/test/java/cava/oracle/*.java | ForEach-Object { $_.FullName })
    PS J:\mc\Cava> & 'C:\Program Files\Java\jdk-21\bin\java.exe' -cp out cava.oracle.OracleSelfTest
    === Cava P1 pathfind oracle self-test ===
    out        = J:\mc\Cava\src\test\resources\cava\oracle
    cases      = 10000
    masterSeed = 0x1f2e3d4c5b6a7988

    --- 语义不变式自检 ---
      自检：32 项，失败 0 项
    ...（完整输出见 §10.7 与提交说明）

### 12.2 隔离证明（不依赖 Minecraft）

    PS J:\mc\Cava> Select-String -Path src/test/java/cava/oracle/*.java -Pattern '^\s*import\s'
    LandMaker.java:3: import java.util.HashMap;
    ... （全部是 java.*，共 23 行）

    PS J:\mc\Cava> Select-String -Path src/test/java/cava/oracle/*.java -Pattern '^\s*import\s+(net\.minecraft|net/minecraft)'
    (空)

    PS J:\mc\Cava> # 扫编译产物常量池里的字符串 'net/minecraft'
    命中 class 数：0 / 总 class 数 23

### 12.3 幂等性

    PS J:\mc\Cava> # 连跑两次比对 SHA-256
    文件数 4，哈希不同的文件数：0
    7D29C7F12BCCFD7A  golden-00.bin
    E48FE936375C9A8B  vectors-00.bin
    5C485B9C114644F8  vectors-01.bin
    34D5396CFB7CCBA5  manifest.txt

### 12.4 修正记录

- **本文件首次成稿时**：§2.4 的 isValidDiagonalSuccessor 结论最初写反了
  （把 iflt 188 / ifge 184 的跳转目标看反）。现已按字节码改正，并用参照实现逐用例复核。
- **本文件首次成稿时**：§5.4.2 补充「adjustNodeType 收到的是实体的方块坐标而不是体素坐标」。
- **本文件首次成稿时**：新增 §4.3.1「Path.reachesTarget() 语义是反的」，由字节码 + 10000 组实跑双重确认。
