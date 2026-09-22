# Cava P2 第 2 核语义规格：实体间 broadphase 与 `Entity.pushAwayFrom`

> **本文件是 P2 第 2 核（实体推挤 / 区段 broadphase）的唯一语义基准。** 全部结论来自本机
> **Yarn 命名 Minecraft 1.20.4 字节码的 `javap -p -c` 输出**，不是记忆、不是反编译猜测、
> 不是从实现反推。凡字节码看不出来的，一律写「未验证」。
>
> 作者：P2-push 子代理。样式照抄 `docs/CAVA-entity-oracle-spec.md`（P2 第 1 核）。
> 内核：`native/src/entity/push/**`；接线与 ABI 提案：`docs/CAVA-push-notes.md`。

---

## 0. 证据来源与复现命令

### 0.1 jar（**必须用 `minecraft-common`**）

    J:\mc\Cava\.gradle-home\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-common\
        1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\
        minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar

    $jar = '<上面的 jar>'
    $jp  = 'C:\Program Files\Java\jdk-21\bin\javap.exe'
    & $jp -p -c -classpath $jar net.minecraft.entity.Entity            # pushAwayFrom / addVelocity
    & $jp -p -c -classpath $jar net.minecraft.entity.LivingEntity      # tickCramming / pushAway / isPushable
    & $jp -p -c -classpath $jar net.minecraft.entity.decoration.ArmorStandEntity
    & $jp -p -c -classpath $jar net.minecraft.entity.vehicle.BoatEntity
    & $jp -p -c -classpath $jar net.minecraft.entity.vehicle.AbstractMinecartEntity
    & $jp -p -c -classpath $jar net.minecraft.world.World              # getOtherEntities
    & $jp -p -c -classpath $jar net.minecraft.world.entity.SectionedEntityCache
    & $jp -p -c -classpath $jar net.minecraft.world.entity.EntityTrackingSection
    & $jp -p -c -classpath $jar net.minecraft.world.entity.EntityTrackingStatus
    & $jp -p -c -classpath $jar net.minecraft.util.math.ChunkSectionPos
    & $jp -p -c -classpath $jar net.minecraft.util.math.Box
    & $jp -p -c -classpath $jar net.minecraft.util.math.MathHelper
    & $jp -p -c -classpath $jar net.minecraft.predicate.entity.EntityPredicates
    & $jp -p -c -classpath $jar net.minecraft.entity.mob.MobEntity

本轮转储落在 `build/push-javap/*.txt`（构建产物，不进仓库）。

### 0.2 映射（`node tools/mapquery.cjs` 与本轮 refmap 实读）

| Yarn | intermediary | 说明 |
| --- | --- | --- |
| `Entity.pushAwayFrom(Entity)` | **`method_5697`** | 本核主角；`public void`，**不是** private |
| `Entity.addVelocity(double,double,double)` | **`method_5762`** | ServerCore 的 HEAD 短路打在它上面 |
| `EntityView.getOtherEntities(Entity,Box,Predicate)` | `method_8333` | `World` 实现，谓词版 |
| `EntityView.getOtherEntities(Entity,Box)` | `method_8335` | default，填 `EXCEPT_SPECTATOR` |
| `CollisionView.getBlockCollisions` | `method_20812` | 第 1 核 |

`EntityPushAwayFromMixin` **remap 后**的注解实读（`build/libs/cava-0.1.0.jar` 里的 class）：

    #24 = Utf8  Lorg/spongepowered/asm/mixin/injection/Inject;
    #26 = Utf8  method_5697                     <-- 目标方法号，不是猜的
    #21 = Utf8  (Lnet/minecraft/class_1297;Lorg/.../callback/CallbackInfo;)V

### 0.3 任务书里两处需要更正的地方

1. 任务书写「ServerCore 在 `Entity.push` 上有 `@Inject(HEAD, cancellable)`」。**1.20.4 没有 `Entity.push`**；
   ServerCore 的 refmap 实读是 `push(DDD)V -> class_1297;method_5762(DDD)V`，而 `method_5762` 的 Yarn 名是
   **`addVelocity`**（`mappings.tiny` 实读，见 `docs/CAVA-compat-notes.md` §3.2）。
   本规格一律用 `addVelocity`。
2. 任务书把「实体间 broadphase」与 `Entity.pushAwayFrom` 并列成一个核。实测这两件事在 1.20.4 是
   **同一段调用链的两层**：`LivingEntity.tickCramming` → `World.getOtherEntities`（broadphase）→
   `LivingEntity.pushAway` → `Entity.pushAwayFrom`（几何）。见 §1。

---

## 1. 谁调用 `Entity.pushAwayFrom`（调用点逐条实读）

对整包 5684 个 class 做字节码串搜索，**只有 8 个类**提到 `pushAwayFrom`：
`Entity` / `LivingEntity` / `ArmorStandEntity` / `RavagerEntity` / `ShulkerEntity` / `SlimeEntity` /
`AbstractMinecartEntity` / `BoatEntity`。**`VehicleEntity` 不在其中** —— 这一点在 §1.3 有用。

### 1.1 `LivingEntity.tickCramming()`（字节码 0-216）— 服务器侧的主路径

    public void tickCramming();
       0-42:  if (getWorld().isClient) {
                  list = getWorld().getEntitiesByType(TypeFilter.instanceOf(PlayerEntity.class),
                                getBoundingBox(), EntityPredicates.canBePushedBy(this));
                  list.forEach(accept:(LivingEntity;)Consumer)      // invokedynamic #11
                  return;
              }
      43-58:  list = getWorld().getOtherEntities(this, getBoundingBox(),
                                                  EntityPredicates.canBePushedBy(this));
      60-66:  if (list.isEmpty()) return;
      69-96:  i = getGameRules().getInt(GameRules.MAX_ENTITY_CRAMMING);
              if (i > 0 && list.size() > i-1 && this.random.nextInt(4) == 0) { ... 挤伤 ... }
     180-216: for (Entity e : list) this.pushAway(e);

**读法要点**：
- **客户端那一支走的是 `getEntitiesByType(TypeFilter.instanceOf(PlayerEntity.class), ...)`**，
  玩家走 `getEntitiesByType` 而**不是** `getOtherEntities`。服务端才走 `getOtherEntities`。
  所以「接管 `getOtherEntities`」对客户端无效 —— 本条本核**只考虑服务端**。
- `MAX_ENTITY_CRAMMING` 的默认值是 24；**置 0 时挤伤段整段跳过，但推挤循环照样跑**
  （`i > 0` 是短路条件）。本轮的场景正是靠这一点既避免伤害又保留推挤负载。
- `tickCramming` 的调用点在 `LivingEntity.baseTick()` 内（字节码偏移 **863**，紧跟在
  `tickRiptide(...)`（859）之后）—— **与 AI 无关**，`NoAI` 生物同样会跑。

### 1.2 `LivingEntity.pushAway(Entity)`（字节码 7552-7557）

    protected void pushAway(Entity entity);
       0: aload_1                                     // 形参 entity
       1: aload_0                                     // this
       2: invokevirtual Entity.pushAwayFrom:(LEntity;)V
       5: return

⇒ **接收者是邻居，参数是 tick 中的那个实体**：`entity.pushAwayFrom(this)`。
所以 `pushAwayFrom` 里的 `this` 是邻居、形参是「挤人的那个」。

### 1.3 其余调用点（都**不是** `Entity.pushAwayFrom` 本身）

| 调用点 | 接收者 | 实际解析到 |
| --- | --- | --- |
| `ArmorStandEntity.tickCramming`（741-771）：`getOtherEntities(this, bbox, RIDEABLE_MINECART_PREDICATE)`，若 `squaredDistanceTo(e) <= 0.2` 则 `e.pushAwayFrom(this)` | 矿车 | **`AbstractMinecartEntity.pushAwayFrom`（覆写）** |
| `AbstractMinecartEntity.tick` 489-594 / 613-692 | 玩家/铁傀儡/矿车 | **矿车覆写**（`EntityPredicates.canBePushedBy` + `bbox.expand(0.2,0,0.2)`）|
| `BoatEntity.tick` 403-590 → `this.pushAwayFrom(e)`（581） | 船 | `BoatEntity.pushAwayFrom` → **`super.pushAwayFrom` = `VehicleEntity.pushAwayFrom` = `Entity.pushAwayFrom`** |
| `SlimeEntity.pushAwayFrom`（569-573） | 史莱姆 | `invokespecial MobEntity.pushAwayFrom` → `LivingEntity` → `Entity` |
| `RavagerEntity.knockback`（585） | 劫掠兽 | `invokevirtual LivingEntity.pushAwayFrom` |

**`BoatEntity.pushAwayFrom`（字节码 319-347）是唯一一条从「非 MobEntity」走到 `Entity.pushAwayFrom` 的路**：

    public void pushAwayFrom(Entity entity);
       0: aload_1; instanceof BoatEntity; ifeq 33
       7: entity.getBoundingBox().minY      // dcmpg
      14: this.getBoundingBox().maxY
      21: ifge 56                          // 若 entity.minY >= this.maxY 什么都不做
      25: invokespecial VehicleEntity.pushAwayFrom:(LEntity;)V     // <<< 落到 Entity.pushAwayFrom
      33: entity.getBoundingBox().minY  vs  this.getBoundingBox().minY   （非船分支）
      51: invokespecial VehicleEntity.pushAwayFrom

**本轮实测的后果**：真实服务端上 `Entity.pushAwayFrom` 的调用次数是 **0**（§notes §5）。
原因是本机整合包里 `MobEntity` 会在召唤后约 1 秒内被移走（与本轮改动无关，详见 notes §5.2），
而矿车走的是自己的覆写、船又没能进入实体 lookup。这条**未验证**，写在这里是为了让后来者
不要以为「跑一次就有计数」。

---

## 2. `Entity.pushAwayFrom(Entity)` 的完整语义（字节码 4223-4318）

### 2.1 逐条转写

    public void pushAwayFrom(Entity entity);
        0-8:   if (this.isConnectedThroughVehicle(entity)) return;        // getRootVehicle()==getRootVehicle()
        9-23:  if (entity.noClip || this.noClip) return;
       24-33:  double d = entity.getX() - this.getX();      // local 2
       34-43:  double e = entity.getZ() - this.getZ();      // local 4
       45-51:  double f = MathHelper.absMax(d, e);          // local 6
       53-59:  if (f >= 0.009999999776482582d) {            // dcmpl + iflt
       62-67:      f = Math.sqrt(f);
       69-73:      d = d / f;
       74-79:      e = e / f;
       81-85:      double g = 1.0 / f;                      // local 8
       87-95:      if (g > 1.0) g = 1.0;                    // dcmpl + ifle
       97-101:     d = d * g;
      102-107:     e = e * g;
      109-114:     d = d * 0.05000000074505806d;            // = (double)0.05F
      115-121:     e = e * 0.05000000074505806d;
      123-146:     if (!this.hasPassengers() && this.isPushable())
                       this.addVelocity(-d, 0.0d, -e);      // dneg / dconst_0 / dneg
      147-168:     if (!entity.hasPassengers() && entity.isPushable())
                       entity.addVelocity(d, 0.0d, e);
           }
     169:  return;

### 2.2 四处**容易读反**的地方（每处都有定点用例）

1. **`f` 的赋值不是 `Math.abs(Math.max(d,e))`，而是 `MathHelper.absMax(d,e)`**（字节码 272-291）：

       if (a < 0.0) a = -a;      // 编译成 dcmpg + ifge：**NaN 时 dcmpg 得 1 -> 不取负**
       if (b < 0.0) b = -b;
       return Math.max(a, b);    // Java 的 Math.max：任一 NaN 结果为 NaN

   ⇒ **NaN 原样穿过两个 `if`**（TT-PA-6）。

2. **除数不可能为 0，而这个性质**完全**来自守卫的位置**：
   53 行的守卫先把 `f` 压到 `>= (double)0.01F`，62/69/74/81 行的三次除法全在它之后。
   把守卫挪到除法之后就会立刻产生 `inf` / `NaN`。**顺序即正确性。**
   （唯一例外是 `f = +Inf`：`sqrt(+Inf)=+Inf`、`d/f = Inf/Inf = NaN`、`1/f = +0.0` —— TT-PA-7。
   也就是说「无限远」在原版里给出的位移是 **NaN**，不是 0。）

3. **`g = 1.0/f` 里的 `f` 已经是 `sqrt` 之后的值**（62 行把 `f` 重新赋值了）。
   ⇒ 对 `f_原始 >= 1` 的情形，`d/f*sqrt * 1/f_sqrt = 1`，位移恒为 `(double)0.05F`（TT-PA-1 / PA-3）；
   对 `f_原始 < 1` 的情形，`g > 1` 被**钳到 1.0**，位移变成 `sqrt(f)*0.05F`（TT-PA-2）。
   两类结果**差一个数量级**，是最容易读错的一处。

4. **两个 `addVelocity` 的符号相反、且都是 `+0.0` 的中轴分量**：
   `this.addVelocity(-d, +0.0, -e)` / `entity.addVelocity(d, +0.0, e)`。
   `dconst_0` 是 **+0.0**（不是 -0.0）；`dneg` 在 Java 侧做，所以内核必须原样传出 `+0.0` 的符号
   （TT-PA-5：`d=+0.0` 时 `p` 必须逐位是 `+0.0`）。

### 2.3 谓词与对象集合**一律留在 Java 侧**（契约）

`isConnectedThroughVehicle` / `noClip` / `hasPassengers` / `isPushable` / `addVelocity` 全是 Java 虚调用，
内核只算 `(d, e) -> (p, q)`。这样做的**直接好处**：ServerCore 打在 `addVelocity` 上的短路
（§5）天然继续生效，不需要复刻、也不会因为我们在原生里自己加速度而绕过它。

`hasPassengers()`（字节码 6238-6247）= `!passengerList.isEmpty()`；
`isConnectedThroughVehicle(Entity)`（8381-8391）= `this.getRootVehicle() == entity.getRootVehicle()`（**引用比较**）；
`Entity.isPushable()`（4614-4617）基类返回 **false**，`LivingEntity.isPushable()`（7878）是虚方法，
`ArmorStandEntity.isPushable()`（732-735）返回 **false**，`BoatEntity` / `AbstractMinecartEntity` 返回 **true**。

---

## 3. broadphase：`World.getOtherEntities` 的真实调用链

### 3.1 三层

    World.getOtherEntities(except, box, predicate)                    // 字节码 1449-1466
        profiler.visit("getEntities")
        list = Lists.newArrayList()
        getEntityLookup().forEachIntersects(box, lambda(entity, except, predicate, list))
        return list                                                  // **不是惰性视图**

    EntityLookup.forEachIntersects(Box, Consumer)                     // 接口
    SimpleEntityLookup.forEachIntersects(Box, Consumer)               // 字节码 51-59
        cache.forEachIntersects(box, LazyIterationConsumer.forConsumer(consumer))

    SectionedEntityCache.forEachIntersects(Box, LazyIterationConsumer) // 字节码 280-288
        forEachInBox(box, lambda(box, consumer, section) -> section.forEach(box, consumer))

`getOtherEntities` 的 lambda 体（`World` 的 invokedynamic #1）依次做 `entity != except` 与
`predicate.test(entity)`，命中才 `list.add`。**顺序 = 枚举顺序**（§4）。

### 3.2 谓词：`EntityPredicates.canBePushedBy(Entity)`（字节码 36-59）

    Team team = entity.getScoreboardTeam();
    CollisionRule rule = (team == null) ? CollisionRule.ALWAYS : team.getCollisionRule();
    if (rule == CollisionRule.NEVER) return Predicates.alwaysFalse();
    return EXCEPT_SPECTATOR.and(e -> e != entity && e.isPushable() && <按 rule 的额外判定>);

`EXCEPT_SPECTATOR` = `e -> !e.isSpectator()`。
**服务端 tickCramming 的候选集 = `getOtherEntities(this, bbox, canBePushedBy(this))`**，
即「不是旁观者、不是自己、`isPushable()`、且评分板碰撞规则允许」。

### 3.3 叶子过滤：`EntityTrackingSection.forEach(Box, LazyIterationConsumer)`（字节码 41-68）

    合成器 = TypeFilterableList.iterator()
    for (e : collection) if (e.getBoundingBox().intersects(box)) { if (consumer.accept(e).shouldAbort()) return ABORT; }
    return CONTINUE

`Box.intersects(Box)` 委托到 `intersects(DDDDDD)`（字节码 925-960）：

    minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ

**六条全部是严格不等**（`dcmpg+ifge` / `dcmpl+ifle`）：面**相切不算相交**（TT-BF-2），
且 NaN 参与时整体为 false（TT-BF-4）。

---

## 4. `SectionedEntityCache.forEachInBox` 的访问顺序（**本核最容易被读反的一段**）

### 4.1 逐条转写（字节码 33-146）

    int i = 2;                                                    // 35-36：载入后**从未使用**（死代码）
    int xMin = ChunkSectionPos.getSectionCoord(box.minX - 2.0);
    int yMin = ChunkSectionPos.getSectionCoord(box.minY - 4.0);
    int zMin = ChunkSectionPos.getSectionCoord(box.minZ - 2.0);
    int xMax = ChunkSectionPos.getSectionCoord(box.maxX + 2.0);
    int yMax = ChunkSectionPos.getSectionCoord(box.maxY + 0.0);   // dconst_0; dadd
    int zMax = ChunkSectionPos.getSectionCoord(box.maxZ + 2.0);
    for (int x = xMin; x <= xMax; x++) {
        long minKey = ChunkSectionPos.asLong(x, 0, 0);
        long maxKey = ChunkSectionPos.asLong(x, -1, -1);
        for (long pos : trackedPositions.subSet(minKey, maxKey + 1)) {   // LongAVLTreeSet
            int y = ChunkSectionPos.unpackY(pos);
            int z = ChunkSectionPos.unpackZ(pos);
            if (y < yMin) continue;  if (y > yMax) continue;
            if (z < zMin) continue;  if (z > zMax) continue;
            EntityTrackingSection<T> s = trackingSections.get(pos);
            if (s == null || s.isEmpty() || !s.getStatus().shouldTrack()) continue;
            if (consumer.accept(s).shouldAbort()) return;
        }
    }

### 4.2 `ChunkSectionPos.asLong` 的位布局（字节码 476-508）—— **y 和 z 是反的**

    0-13:  r = ((long)x & 4194303L) << 42
    14-24: r |= ((long)y & 1048575L) << 0        <-- **y 在最低 20 位**
    25-36: r |= ((long)z & 4194303L) << 20       <-- **z 在中间 22 位**

    unpackX(v) = (int)(v << 0  >> 42)   // 高 22 位 = x
    unpackY(v) = (int)(v << 44 >> 44)   // 低 20 位 = y
    unpackZ(v) = (int)(v << 22 >> 42)   // 第 20..41 位 = z

**两个独立印证**：(a) 解码侧的移位宽度与编码侧的掩码宽度逐一吻合（x/z 是 22 位 `0x3FFFFF`，
y 是 20 位 `0xFFFFF`）；(b) `unpackX/Y/Z` 三个方法各自只做一次左移+算术右移，语义无歧义。
内核据此只用**一份** pack/unpack 实现，并在单测里用两种独立推导互证（TT-SP-0a/0b）。

### 4.3 由 4.1 + 4.2 推出的**三条硬结论**

1. 外层是 **x 的数值升序**（`xMin..xMax` 的 for 循环），**不是**打包值升序。
2. 内层是 **打包值升序**；因为打包值是 `x<<42 | z<<20 | y`，所以固定 x 时的顺序是
   **「z 的掩码升序，再 y 的掩码升序」** —— **先 z 后 y**，与直觉相反。
3. 掩码是 22/20 位的**无符号截取**，所以**负 z 排在正 z 之后**、**负 y 排在正 y 之后**
   （`z=-1` 的掩码是 `0x3FFFFF`，比 `z=0` 的 `0` 大）。

⇒ 一个查询盒 `z ∈ {-1, 0}` 的访问顺序是 **先 `z=0` 的那一列区段，再 `z=-1` 的那一列**。
TT-SP-1 就是这条的定点用例（8 条期望值全部手推）。

### 4.4 窗口是**不对称**的

    x: [minX - 2.0, maxX + 2.0]
    z: [minZ - 2.0, maxZ + 2.0]
    y: [minY - 4.0, maxY + 0.0]      <-- 下扩 4、上扩 0

TT-SP-2 把 `yMax` 从 4 收到 3 来钉住这条。`maxY + 0.0` 里的 `dconst_0; dadd` 只对 `-0.0` 有含义
（`(-0.0) + 0.0 = +0.0`），在 `MathHelper.floor` 下两者结果相同，因此它在本函数里**是等价变换**。

### 4.5 `maxKey + 1` 的**回绕**

`x = -1` 时 `asLong(-1,-1,-1) = 0xFFFFFFFFFFFFFFFF = -1`，于是 `maxKey + 1 = 0`。
此时 `subSet(minKey, 0)` 是一个**合法且非空**的区间（所有负 x 的区段都 `< 0`）。
内核用**无符号加法**复刻这个回绕（`C++` 里有符号溢出是 UB），TT-SP-3 专门覆盖它。

---

## 5. 占用者的确切位置

### 5.1 ServerCore：`Entity.addVelocity(DDD)` 的 HEAD 短路

`servercore-common-refmap.json` 实读：`push(DDD)V -> Lnet/minecraft/class_1297;method_5762(DDD)V`。
`EntityMixin.servercore$ignorePushingWhileInactive(DDD, CallbackInfo)` 的字节码逐条：

    0: getfield servercore$isInactive:Z ; ifeq 22
    7: getfield field_6002 (world) ; getfield class_1937.field_9236 (isClient) ; ifne 22
   17: invokevirtual CallbackInfo.cancel()

⇒ **`isInactive && !world.isClient` 时取消 `addVelocity`**。
**与我们的关系**：我们**不复刻**它 —— `pushAwayFrom` 的两次 `addVelocity` 在 Java 侧照常虚分派，
ServerCore 的短路继续生效。核心里没有速度模型，因此不存在「绕开它」的风险。

### 5.2 VMP / Lithium

- **VMP**：唯一的两处注入是 `Entity.move` 的 HEAD（零位移短路）与 `setBoundingBox` 的 HEAD。
  本核碰的是 `pushAwayFrom` 与 `SectionedEntityCache`，**与它不相交**。
- **Lithium**：唯一的 `@Overwrite` 在 `Entity.adjustMovementForCollisions`（第 1 核的领地），
  另有 `@Redirect(require=5)` 打在实例版 `method_17835` 体内。**均不在本核路径上。**
  本核**没有**改动 `docs/CAVA-compat-notes.md` 里那条「entity 子系统的归属让位给 Lithium」的判定。

---

## 6. 定点真值表（期望值全部由字节码手推）

原生侧：`native/tests/push/cava_push_vectors.cpp`（`SUMMARY: 62 checks, 0 failed`）；
Java 侧：`src/test/java/cava/push/PushMathTest.java`。

### 6.1 TT-PA：`pushAwayFrom` 的几何

| 用例 | `this` | `other` | 期望 | 为什么（手推）|
| --- | --- | --- | --- | --- |
| PA-0 | (0,0) | (0.009,0) | hit=0, (+,0,0) | 0.009 < (double)0.01F |
| PA-1 | (0,0) | (4,0) | hit=1, dx=`(double)0.05F` | f=4→sqrt=2→d=2→g=1/2→2*0.5=1 |
| PA-2 | (0,0) | (0.25,0) | hit=1, dx=0.02500000037252902984619140625 | f=0.25→sqrt=0.5→d=0.5→g=2>1 **钳到 1** |
| PA-3 | (0,0) | (1,0) | hit=1, dx=`(double)0.05F` | f=1→g=1，`1 > 1` 为假 ⇒ **不钳** |
| PA-4 | (0,0) | (-4,0) | hit=1, dx=`-(double)0.05F` | 符号原样穿过 |
| PA-5 | (0,0) | (+0.0, 8) | hit=1, **dx 逐位 = +0.0** | +0.0 除以正数仍 +0.0 |
| PA-6 | (0,0) | (NaN,5) | hit=0 | absMax 传播 NaN ⇒ `f >= C` 假 |
| PA-7 | (0,0) | (+Inf,0) | hit=1, dx=**NaN**, dz=+0.0 | Inf/Inf=NaN（不是「推不动」）|
| PA-8 | (0,0) | (-0.0,-0.0) | hit=0 | `Math.max(-0.0,-0.0) = -0.0` ⇒ 守卫假 |
| PA-9 | (0,0) | ((double)0.01F,0) | hit=1 | `>=` 是闭界 |
| PA-9b | (0,0) | (nextDown((double)0.01F),0) | hit=0 | 低一个 ulp 就出界 |
| PA-11 | 多条 | — | hit ⇒ \|d\| >= 0.01F | 「除数为零不可能」的不变量 |

其中 PA-1/PA-2/PA-3/PA-4 是**逐位**断言（值在二进制里精确可表示），其余是结构性断言，
原因写在用例注释里。`MathHelper.absMax` 另有单独的 NaN / ±0.0 断言。

### 6.2 TT-BF：`Box.intersects`

| 用例 | 期望 |
| --- | --- |
| BF-1 自身 | true |
| BF-2 x 面相切 (0..1 vs 1..2) | **false**（严格 `<`）|
| BF-2b y 面相切 | false |
| BF-3 重叠 1e-9 | true |
| BF-4 查询盒含 NaN | false |
| BF-5 `-0.0` 边界 | true |
| BF-6 零宽退化盒 | true |
| BF-7 候选下标 0,2,4 升序 | 顺序保持 |
| BF-8 容量不足 | 错误码（不越界写）|

### 6.3 TT-SP：区段访问计划

用例盒 `(0.5, 64.0, 0.5, 1.5, 65.0, 1.5)`：
xMin=-1 yMin=3 zMin=-1 xMax=0 yMax=4 zMax=0，x∈{-1,0}、y∈{3,4}、z∈{-1,0}。
**期望 8 条，顺序为**
`(-1,z=0,y=3) (-1,z=0,y=4) (-1,z=-1,y=3) (-1,z=-1,y=4)` 然后 x=0 同样 4 条。
（`z=0` 在 `z=-1` **之前** —— 这就是 §4.3 第 2/3 条。）

| 用例 | 覆盖 |
| --- | --- |
| SP-0a | `pack(1,2,3) == 1<<42 \| 3<<20 \| 2`（y 在低位）|
| SP-0b | pack/unpack 往返（含 ±边界与负数）|
| SP-0c | `pack(-1,-1,-1) == -1` ⇒ `hi+1` 回绕 |
| SP-1 | 上面的 8 条顺序 |
| SP-2 | `yMax` 收窄 ⇒ 只剩 4 条，且 z 顺序不变 |
| SP-3 | 只访问 x=-1（回绕分支）|
| SP-4 | 窗口外 ⇒ 0 条 |
| SP-5 | 容量不足 ⇒ 错误码 |
| SP-6/6b | 输出是输入的**严格升序子序列** + 快照序 == (x 有符号升序, z 掩码升序, y 掩码升序) |

### 6.4 本轮真值表**抓到的两个真错**（记下来，因为它们正是「能红的测试」的价值）

1. `section_coord` 第一版**只写了 `MathHelper.floor`，漏了 `>> 4`** —— SP-1..SP-5 立刻全红。
2. SP-6 第一版给的输出容量是 64，而该窗口覆盖全部 80 个区段，`section_plan` 正确地返回了
   错误码 —— **是测试写错了**，不是内核。两处都留在用例注释里。

---

## 7. 本规格**没有**证实的东西（留白 / 未验证）

1. **`Entity.pushAwayFrom` 在真实服务端上从未被调用**（本机整合包里 MobEntity 会被移走，见 notes §5.2）。
   所以 §1.3 那张「谁调用」表在**真实运行**里只验证了一半：矿车那条路验证过（走的是覆写），
   船那条路**没有**验证过。
2. **`World.getOtherEntities` 的 lambda 体**只按字节码顺序读，没有单独的反编译对照。
3. **`trackedPositions` 的迭代器语义**（`LongAVLTreeSet` 是**有符号** long 升序）来自 fastutil 的行为，
   本轮只通过「内核排序 == 原版 API 转写」间接验证（notes §5.4：4025 次同 call 比对 0 不一致）。
4. **`EntityTrackingStatus` 的三态**（HIDDEN / TRACKED / TICKING）与 chunk level 的映射**没有**读字节码；
   本核只调用 `shouldTrack()`，语义由原版对象负责。
5. **客户端那一支**（`tickCramming` 的 `getEntitiesByType`）本核**完全没碰**。
6. **`SectionedEntityCache.forEachInBox` 与 `consumer.accept` 中途改动 `trackedPositions`** 的交互：
   原版是边遍历边看，我们在 live 用的是**计划快照**。原版在那种情况下的行为依赖 fastutil 迭代器的
   未定义细节；本轮**没有构造出用例**，也**没有**观察到。见 notes §5.5。
