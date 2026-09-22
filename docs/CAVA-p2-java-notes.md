# Cava P2-Java：实体侧接入（ABI 未冻结前的可做部分）

> 作者：P2-Java 流（第三轮并行开发的实体分支）。工作目录 = 仓库根 `J:\mc\Cava`。
> 所有结论都有本机实测证据；未验证的一律标注「未验证」或「等 ABI」。
> 语义权威 = 本流自己跑的 `javap`（`minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar`）
> + VMP 1.20.4 的 mixin 源码原文（`.research/`）。

---

## 0. 一句话状态

**Java 侧实体接入的「不依赖 ABI」部分完成**：SoA 镜像（打包 + 读取两端）、
事件回放骨架（顺序 = 我从字节码逐条读出来的 `Entity.move`）、
VMP 零位移短路的黏滞复刻、ServerCore inactive 的跳过统计。
四类单测 **34 个用例全绿**（`gradlew build` / `gradlew test` 实跑，见第 6 节）。
**上传端与注入端故意没做**（ABI 未冻结 / 不是本流的路径），清单见第 7 节。

---

## 1. 交付物（只动了被授权的路径）

| 路径 | 内容 |
| --- | --- |
| `src/main/java/cava/entity/EntityMirror.java` | SoA 镜像：`pack()` 打包 + 各 `x(i)/id(i)` 读取端 + id→行号索引 + 冻结核对 |
| `src/main/java/cava/entity/EntitySample.java` | 可复用的单行暂存（字段顺序 = 将来 ABI 的字段顺序） |
| `src/main/java/cava/entity/EntitySource.java` | **窄输入接口（2 个方法）**：注入流实现它即可接上 MC 世界 |
| `src/main/java/cava/entity/EntityFlags.java` | 位布局的**唯一定义处** + 名字映射 |
| `src/main/java/cava/entity/SkipReason.java` / `TickSkipLedger.java` | 每 tick「哪些实体被跳过、为什么」的台账 + 不可变快照 |
| `src/main/java/cava/entity/InactivityProbe.java` | inactive 探测的注入点；生产实现 = `ServerCoreAdapter::isInactive` |
| `src/main/java/cava/entity/EntityTickObserver.java` | 每 tick 观测入口（把「先取快照再 pack」的时序固定下来） |
| `src/main/java/cava/entity/MoveStep.java` | **`Entity.move` 的步骤表**：顺序 + 字节码偏移 + 原版调用签名 |
| `src/main/java/cava/entity/MoveEventKind.java` | 原生事件的种类（只标种类，顺序由 `MoveStep` 定） |
| `src/main/java/cava/entity/MoveEventLog.java` | 事件小数组的 Java 侧收窄表示（只固定语义，不固定字节布局） |
| `src/main/java/cava/entity/MoveInputs.java` | 回放需要的 Java 侧输入（每个字段标了字节码出处） |
| `src/main/java/cava/entity/MoveCallbacks.java` | 回放的**可测注入点**（原版里全部会被 mod 覆写的调用） |
| `src/main/java/cava/entity/MoveFlags.java` | 四个碰撞标志位的逐分支复刻 |
| `src/main/java/cava/entity/EventReplay.java` | 回放骨架（按 `MoveStep` 顺序消费事件 + 回放流水） |
| `src/main/java/cava/entity/VmpZeroVelocityGate.java` | VMP 零位移短路复刻（含黏滞语义 + 时序约束进方法名） |
| `src/test/java/cava/entity/MoveBytecodeTruthTest.java` | **从字节码推出的真值的定点用例** |
| `src/test/java/cava/entity/EventReplayTest.java` | 事件顺序 / 参数 / 原始对象身份（假方块行为） |
| `src/test/java/cava/entity/VmpZeroVelocityGateTest.java` | VMP 黏滞语义 + 负零 |
| `src/test/java/cava/entity/EntityMirrorTest.java` | 打包 / 读取 / inactive 跳过 / 不回写 / 冻结核对 |

未做（见第 7 节）：`mirror` 上传、mixin 注入、原生侧任何东西。

---

## 2. `Entity.move` 的字节码转录（本流自己读的）

**命令（PowerShell 5.1，一行写完；用续行符时那个字符会被当内容传进去）**：

    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c -classpath 'C:\Users\郁小悟520\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-common\1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2\minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar' net.minecraft.entity.Entity > build\p2-java-scratch\Entity.txt

输出 9894 行（447923 字节），`move` 在 **1272–1748** 行。下面的偏移全部直接抄自那次输出。

| 偏移 | 原版语句（按字节码还原） | 落到哪个 `MoveStep` |
| --- | --- | --- |
| 0–38 | `if (noClip) { setPosition(pos + movement); return; }` | （提前返回，留在 Java 原位） |
| 39–70 | `wasOnFire = isOnFire();` 然后 `if (type == PISTON) { movement = adjustMovementForPiston(...); if (movement.equals(ZERO)) return; }` | （提前返回） |
| 86–120 | `if (movementMultiplier.lengthSquared() > 1.0E-7) { movement = movement.multiply(...); movementMultiplier = ZERO; setVelocity(ZERO); }` | （Java 内部） |
| 123–129 | `movement = adjustMovementForSneaking(movement, type)` | COLLISION_SOLVE 之前 |
| **132** | `vec3d = adjustMovementForCollisions(movement)` | **COLLISION_SOLVE**（P2 的原生目标） |
| 136–140 | `d = vec3d.lengthSquared()` | |
| 142–148 | `if (d > 1.0E-7) {` | |
| 151–164 | `if (fallDistance != 0.0F && d >= 1.0) {` | |
| **197** | `world.raycast(new RaycastContext(getPos(), getPos().add(vec3d), FALLDAMAGE_RESETTING, WATER, this))` | **LANDING_RAYCAST** |
| 202–214 | `if (hit.getType() != MISS) onLanding();` | 同上 |
| **218–245** | `setPosition(getX()+vec3d.x, getY()+vec3d.y, getZ()+vec3d.z)` | **SET_POSITION** |
| **283** | `bl = !MathHelper.approximatelyEquals(movement.x, vec3d.x)` | **SET_COLLISION_FLAGS** |
| **304** | `bl2 = !MathHelper.approximatelyEquals(movement.z, vec3d.z)` | 同上 |
| **333** | `horizontalCollision = bl \|\| bl2` | 同上 |
| **345** | `verticalCollision = (movement.y != vec3d.y)` ← `dcmpl; ifeq`，**不是近似比较** | 同上 |
| **357–379** | `groundCollision = verticalCollision && movement.y < 0.0` | 同上 |
| **386–403** | `collidedSoftly = horizontalCollision ? hasCollidedSoftly(vec3d) : false` | 同上 |
| **412** | `setOnGround(groundCollision, vec3d)` | **SET_ON_GROUND** |
| **416–419** | `blockPos = getLandingPos()` | **FALL** |
| **422–430** | `blockState = world.getBlockState(blockPos)` ← **只取这一次** | 同上 |
| **432–445** | `fall(vec3d.y, isOnGround(), blockState, blockPos)` | 同上 |
| **448–467** | `if (isRemoved()) { profiler.pop(); return; }` | **REMOVED_EARLY_RETURN** |
| **468–515** | `if (horizontalCollision) { Vec3d v = getVelocity(); setVelocity(bl?0.0:v.x, v.y, bl2?0.0:v.z); }` | **HORIZONTAL_VELOCITY_ZERO** |
| 518–523 | `Block block = blockState.getBlock()` | **ENTITY_LAND** |
| **525–544** | `if (movement.y != vec3d.y) block.onEntityLand(world, this)` | 同上 |
| **547–565** | `if (isOnGround()) block.onSteppedOn(world, blockPos, blockState, this)` | **STEPPED_ON** |
| 568–579 | `moveEffect = getMoveEffect(); if (moveEffect.hasAny() && !hasVehicle()) {` | |
| 589–705 | `speed / horizontalSpeed / distanceTraveled` 与 `canClimb` 的内部记账 | （Java 内部） |
| 708–725 | `if (distanceTraveled > nextStepSoundDistance && !blockState2.isAir()) {` | |
| 730–735 | `bl4 = blockPos2.equals(blockPos)` | |
| **737–749** | `bl5 = stepOnBlock(blockPos, blockState, moveEffect.playsSounds(), bl4, movement)` | **STEP_ON_BLOCK_MAIN** |
| **755–774** | `if (!bl4) bl5 \|= stepOnBlock(blockPos2, blockState2, false, moveEffect.emitsGameEvents(), movement)` | **STEP_ON_BLOCK_SECOND** |
| 780–793 | `if (bl5) nextStepSoundDistance = calculateNextStepSoundDistance()` | （Java 内部） |
| **796–835** | `else if (isTouchingWater()) { nextStepSoundDistance = ...; if (playsSounds) playSwimSound(); if (emitsGameEvents) emitGameEvent(SWIM); }` | **SWIM_EFFECTS** |
| **841–850** | `else if (blockState2.isAir()) addAirTravelEffects()` | **AIR_TRAVEL_EFFECTS** |
| **853–854** | `tryCheckBlockCollision()` | **BLOCK_COLLISION** |
| **857–878** | `f = getVelocityMultiplier(); setVelocity(getVelocity().multiply(f, 1.0, f))` | **VELOCITY_MULTIPLIER** |
| **881–982** | `if (world.getStatesInBoxIfLoaded(boundingBox.contract(1.0E-6)).noneMatch(s -> s.isOf(FIRE))) {...} else if (isOnFire() && (inPowderSnow \|\| isWet())) {...}` | **FIRE_BOX** |
| 982–994 | `profiler.pop()` | |

### `checkBlockCollision()`（偏移 2576–2691）—— 逐方块顺序

    from = BlockPos.ofFloored(box.minX+1e-7, box.minY+1e-7, box.minZ+1e-7)
    to   = BlockPos.ofFloored(box.maxX-1e-7, box.maxY-1e-7, box.maxZ-1e-7)
    if (world.isRegionLoaded(from, to))
        for (x = from.x .. to.x)            // iinc 在 236
          for (y = from.y .. to.y)          // iinc 在 230
            for (z = from.z .. to.z) {      // iinc 在 224
              if (!isAlive()) return;       // 偏移 127：在取方块状态**之前**
              state = world.getBlockState(pos)              // 147–156
              state.onEntityCollision(world, pos, this)     // 167
              this.onBlockCollision(state)                  // 173
            }

⇒ 顺序是 **x 外层 → y 中层 → z 内层**；`isAlive()` 在**每格开头**、取状态之前。

### 一处必须记住的「方法名即约束」

`Entity.setBoundingBox(Box)` 是 **final 且函数体只有一条 `putfield`**（偏移 8060–8075）。
所以 VMP 在 HEAD 注入时读到的 `this.boundingBox` 仍然是**旧值** ——
「新旧比较」这件事**只有在赋值之前做才成立**。这条约束写进了
`VmpZeroVelocityGate.onSetBoundingBoxHeadBeforeAssign(...)` 的方法名里（见第 4 节）。

---

## 3. 三个「容易读反」的分支 + 它们的定点用例

P1 的教训：随机向量是参照实现自己产的，参照实现错了就一起错。
所以下面每一条都有**只能由真值满足**的写死断言（`MoveBytecodeTruthTest`）。

### 3.1 `MathHelper.approximatelyEquals` 的阈值是 `9.999999747378752E-6`，**不是 `1.0E-7`**

`javap -p -c net.minecraft.util.math.MathHelper`（第 368 行起）：

    0: dload_2 ; 1: dload_0 ; 2: dsub ; 3: Math.abs:(D)D
    6: ldc2_w  #166   // double 9.999999747378752E-6d      ← = (double)1.0E-5f
    9: dcmpg ; 10: ifge 17 ; 13: iconst_1 ; 17: iconst_0

`Entity.move` 里 `1.0E-7` 只出现在 `lengthSquared() > 1.0E-7` 那两个守卫上，
**不是**近似相等的阈值。照记忆写 `1e-7` 会让 `horizontalCollision` 在 `1e-7..1e-5`
这段缝隙里与现服务器不同 —— 而这段缝隙恰好是「贴着墙滑动」时最常见的位置。

**定点用例**：`approximatelyEquals(0.0, 5.0E-6)` 必须 **true**（1e-7 的实现会返回 false）；
`approximatelyEquals(0.0, 9.999999747378752E-6)` 必须 **false**（严格小于），
`Math.nextDown(阈值)` 必须 **true**。

### 3.2 三个碰撞标志里只有 x/z 用近似比较

`verticalCollision` 是裸 `!=`（偏移 345 的 `dcmpl; ifeq`）。

**定点用例**：`movement=(1,1,1)`、`adjusted=(1+1e-9, 1+1e-9, 1+1e-9)` ⇒
`horizontalCollision == false`（近似相等）而 `verticalCollision == true`（不等于）。
只差一个 ulp 量级的量就能把两者区分开。

### 3.3 `-0.0` 不是 `0.0`

`Vec3d.equals`（`Vec3d.txt:627`）与 `Box.equals`（`Box.txt:338`）都是 `Double.compare` 逐分量：

    22: getfield x:D ; 25: getfield x:D ; 29: Double.compare:(DD)I ; 32: ifeq 37 ...

VMP 的短路判定正是 `movement.equals(Vec3d.ZERO)`，所以 **`-0.0` 分量不会触发短路**。
复刻时写 `x == 0.0` 会在负零上多取消一次 `move`（少跑一整段）。

---

## 4. VMP 零位移短路：黏滞语义

**源码证据**（`.research/cache/raw.githubusercontent.com_RelativityMC_VMP-fabric_ver_1.20.4_src_main_java_com_ishland_vmp_mixins_entity_move_zero_velocity_MixinEntity.java`，34 行原文）：

    @Unique private boolean boundingBoxChanged = false;

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void onMove(MovementType movementType, Vec3d movement, CallbackInfo ci) {
        if (!boundingBoxChanged && movement.equals(Vec3d.ZERO)) {
            ci.cancel();
            boundingBoxChanged = false;                 // ← 复位只在这里
        }
    }

    @Inject(method = "setBoundingBox", at = @At("HEAD"))
    private void onBoundingBoxChanged(Box boundingBox, CallbackInfo ci) {
        if (!this.boundingBox.equals(boundingBox)) boundingBoxChanged = true;
    }

**结论（本任务书点名的那条）**：置位路径只有 `setBoundingBox`；复位路径在 cancel 分支内，
而 cancel 的前置条件是它**已经是 false**。
⇒ **包围盒一旦真变过一次，这个实体的零位移短路就永久失效**。

**为什么这条不做会炸**：只实现「零位移就跳过」会比现服务器更快 —— 被跳过的
`Entity.move` 里有 `wasOnFire` 的刷新、`movementMultiplier` 的消费、
`horizontalCollision` 的清零/保持、`checkBlockCollision` 的整段扫描、火焰分支。
而且差异**只在实体第一次换碰撞盒之后**才显形（生物长大、玩家换姿势、上下载具），
属于「跑一万 tick 才炸一次」的那一类。

**状态机只有一份**：`VmpZeroVelocityGate` 是 `cava.compat.VmpAdapter.State` 的薄包装，
不重新实现 `shouldCancelMove`。相等性走 `Box.equals` / `Vec3d.equals` 那一套（经 `State`）。

**单测**（`VmpZeroVelocityGateTest`，6 个用例）：
`boundingBoxChangeDisablesShortcutForever` 在置位后**连问 1000 次**零位移，
每一次都必须返回 false —— 这就是「永久」的可执行版本；
`comparingAfterTheAssignmentWouldSilentlyNeverFire` 是**反例演示**：
把比较放到赋值之后，标志恒为 false、短路永远生效，从而证明那条时序约束必须进方法名。

---

## 5. ServerCore inactive 的跳过统计（本轮只观测）

**做成什么样**：
- `EntityMirror.pack()` 逐行调用注入的 `InactivityProbe`；生产实现是
  `ServerCoreAdapter::isInactive`（已存在，反射 + 软依赖，ServerCore 没装时恒 false）。
- **inactive 实体照样打包**，只置观测位 `EntityFlags.SERVERCORE_INACTIVE` + 记台账。
  理由：ServerCore 跳过的是 `Entity.tick()`，**不是实体本身** ——
  它仍然存在、仍然参与碰撞与推挤判定。把它从镜像里剔掉会改变碰撞结果，
  那是行为差异，不是优化。
- 台账 `TickSkipLedger` 逐条记 `(实体 id, 原因)`，原因分
  `SERVERCORE_INACTIVE` / `REMOVED` / `UNSAMPLABLE`（最后一类是需要调查的）。
- **冻结核对**：连续两 tick 都 inactive 的实体，12 个 double 必须**逐位**不变
  （`Double.doubleToRawLongBits` 比较）。这是「位置不变是预期行为」的可执行版本；
  只上报不抛异常（真机上可能有载具等例外路径）。
  刚进入 inactive 的那一 tick 不计入（它上一 tick 还在 tick）。
- **不回写是结构性保证**：`EntitySource` 只有 `sample()` 一个读方法，
  镜像**没有**任何写回实体的路径。单测用一个带写计数器的假实体断言打包 5 tick 后
  写次数仍为 0。

**单测**（`EntityMirrorTest`，7 个用例）覆盖：逐位搬运（含 `-0.0`）、
inactive 照样打包 + 置位 + 台账、REMOVED/UNSAMPLABLE 只记账不入镜像、
5 tick 零回写、id→行号索引、冻结核对抓出「被移动过的 inactive 实体」、
快照跨 tick 不丢。

---

## 6. 实测证据（真实命令 + 真实输出）

### 6.1 `gradlew build --rerun-tasks --no-configuration-cache` → 通过

    > Task :compileJava
    > Task :processResources
    > Task :classes
    > Task :compileClientJava
    > Task :processClientResources
    > Task :jar
    > Task :processIncludeJars
    > Task :sourcesJar
    > Task :clientClasses
    > Task :compileTestJava
    > Task :processTestResources
    > Task :testClasses
    > Task :remapJar
    > Task :test
    > Task :remapSourcesJar
    > Task :assemble
    > Task :validateAccessWidener NO-SOURCE
    > Task :check
    > Task :build

    BUILD SUCCESSFUL in 10s
    12 actionable tasks: 12 executed
    BUILD_EXIT=0

（`--rerun-tasks` 保证不是 `UP-TO-DATE` 的假绿；`--no-configuration-cache` 按门禁 #7 的教训加，
避免"配置期文件是否存在"这类决定被缓存复用。）

### 6.2 `gradlew test --rerun-tasks --no-configuration-cache` → 通过

    > Task :test

    BUILD SUCCESSFUL in 8s
    7 actionable tasks: 7 executed
    TEST_EXIT=0

逐类（`build/test-results/test/TEST-*.xml` 实测，只列新增的四类 + 与本次相关的两条）：

    cava.entity.MoveBytecodeTruthTest              tests=8    failures=0   errors=0   skipped=0
    cava.entity.EventReplayTest                    tests=13   failures=0   errors=0   skipped=0
    cava.entity.VmpZeroVelocityGateTest            tests=6    failures=0   errors=0   skipped=0
    cava.entity.EntityMirrorTest                   tests=7    failures=0   errors=0   skipped=0
    ---------------------------------------------------------------- 新增合计 tests=34
    cava.ffm.PathfindAbiTest                       tests=6    failures=0   errors=0   skipped=0
    cava.ffm.NativeFallbackTest                    tests=2    failures=0   errors=0   skipped=2
    cava.mirror.McStateTableProbeTest              tests=7    failures=0   errors=0   skipped=7

全仓 34 个测试类 **tests=… failures=0 errors=0**，只有两处 skip 且都是既有设计：
`NativeFallbackTest`（dll 在盘上 → 它按设计 skip，让 `PathfindAbiTest` 跑）与
`McStateTableProbeTest`（本机 `Bootstrap.initialize()` 失败 → 整类 assume-skip）。
**本流的测试不需要 MC bootstrap**：只用 `net.minecraft.util.math` 下的纯数学类，
所以在这台机器上是真跑了而不是 skip。

### 6.3 单测确实"咬人"的证据（不是只会在绿的时候绿）

首轮跑出 **4 个失败**，其中两个是我自己写的真 bug：

    EventReplayTest > replaysInExactVanillaOrder() FAILED
      expected: <[…, isOnGround, fall(0.0,true,landing,@3,64,5), isRemoved, getVelocity, …]>
      but was: <[…, isOnGround, fall(0.0,true,landing,@3,64,5), isOnGround, isRemoved, getVelocity, …]>
      → 回放为了拼日志又调了一次 Entity.isOnGround()，比原版多一次虚调用

    EventReplayTest > unconsumedEventsAreReported() FAILED
      org.opentest4j.AssertionFailedError: 未消费的事件必须显式报出来 ==> expected: <false> but was: <true>
      → LANDING_RAYCAST_HIT 在进入 d > 1e-7 块时就被 take() 标成已消费，
        "守卫不成立、这步根本没跑"时被静默吞掉

两条都修了（`EventReplay.java`）：`isOnGround()` 改成问一次存局部变量；
`take()` 移进"这一步真的执行"的分支内。

---

### 6.4 收尾时遇到的并行干扰（已解除）与本流的隔离复验

跑收尾那一轮全仓验证时，`compileJava` 一度失败，**失败点全部在 `cava/parity/**`，本流一个字没碰**：

    J:\mc\Cava\src\main\java\cava\parity\TickSampler.java:17: error: a type with the same simple name is already defined by the single-type-import of Path
    J:\mc\Cava\src\main\java\cava\parity\TickSampler.java:89: error: reference to Path is ambiguous
    J:\mc\Cava\src\main\java\cava\parity\TickSampler.java:351: error: reference to Path is ambiguous
    J:\mc\Cava\src\main\java\cava\parity\TickSampler.java:147: error: cannot find symbol  class Meta  location: class GoldenTrace
    J:\mc\Cava\src\main\java\cava\parity\TickSampler.java:228: error: cannot find symbol  method detailLine(long,StringBuilder,StringBuilder,String)
    8 errors

`git status --short` 确认那是**另一个流的未提交改动**：

    M src/main/java/cava/parity/GoldenTrace.java
    M src/main/java/cava/parity/TickSampler.java

按并行纪律本流**不去修别人的文件**，于是做了一次**隔离复验**
（只编 `cava/entity/**`，用 javac + JUnit Platform Launcher 直跑）：

    resolved extra jars: 16 / 16
    MAIN_JAVAC_EXIT=0
    TEST_JAVAC_EXIT=0

    Test run finished after 137 ms
    [        34 tests found           ]
    [         0 tests skipped         ]
    [        34 tests successful      ]
    [         0 tests failed          ]

    RESULT: PASS found=34 succeeded=34 failed=0 skipped=0
    RUN_EXIT=0

**这条顺带证明了一件更有价值的事**：本流的测试**不需要 MC bootstrap、不需要 Fabric Loader**，
只需要 `net.minecraft.util.math` 下的纯数学类 + MC 的几个库 jar
（`datafixerupper / guava / joml / brigadier / logging / fastutil …`）。
所以它**不会**像 `McStateTableProbeTest` 那样在本机被 assume-skip 掉。

**干扰已由对方解除**：随后重跑 `gradlew build --rerun-tasks --no-configuration-cache`
得到 `BUILD SUCCESSFUL in 12s`（exit 0），第 6.1/6.2 节的全仓结论成立。

## 7. 「等 ABI」清单 vs 已可独立验证

### 7.1 等 ABI（P2-K 冻结后才能做的）

| # | 项 | 卡在哪 |
| --- | --- | --- |
| 1 | 把 `EntityMirror` 的 SoA 数组**上传**到原生 | 需要 `CavaEntitySoA` 的结构体定义 + `cava_entity_upload` 入口 + 两侧 `layout_hash` 登记。Java 侧这里只有 `pack()`，上传端是空的 |
| 2 | `EntityFlags` 的位与 `CAVA_EF_*` 对齐 | 同上；位含义现在只在 Java 一处定义，冻结时**必须同时改头文件** |
| 3 | `MoveEventLog` 的字节布局 | 本类只固定语义（kind + 3×int32 + payload）。原生小数组的 packing / 容量 / 溢出错误码由 ABI 定 |
| 4 | `EventReplay` 真正被原生驱动 | 现在 `MoveEventLog` 由测试手写；生产要由 `CavaBindings` 解码原生缓冲区填进来 |
| 5 | 原生解算的**碰撞形状输入** | 原生需要每个方块的原始 `VoxelShape`。契约 2.3 的 `CavaCollisionBox` 是扁平 AABB，**形状必须由区段/状态表侧提供**（与 P1 镜像同一套），不在本流路径上 |

### 7.2 已经可以独立验证（不依赖 ABI，已有单测）

| 项 | 证据 |
| --- | --- |
| `Entity.move` 的步骤顺序与字节码偏移 | `MoveBytecodeTruthTest.moveStepOrderMatchesBytecodeOffsets`（偏移严格递增 + 18 个字面量） |
| 回放的调用序列（含参数与顺序） | `EventReplayTest.replaysInExactVanillaOrder`（整条序列写死） |
| 落点方块状态只解析一次、四处复用 | `EventReplayTest.resolvesLandingStateOnceAndReusesTheObject` |
| 碰撞几何必须用原始形状对象 | `EventReplayTest.axisClipBlocksResolveTheRawShapeAndNeverAFlatAabb`（并断言扁平 AABB 近路调用次数 = 0） |
| 事件写反顺序不影响回放顺序 | `EventReplayTest.logOrderDoesNotAffectReplayOrder` |
| 事件「没被消费 / 少发 / 溢出」都被报出来 | 三个 fail-closed 用例 |
| 移除即提前返回 / 区块未加载不扫方块 | `removedEntityStopsReplayMidway` / `regionNotLoadedSkips...` |
| VMP 黏滞语义、负零、时序反例 | `VmpZeroVelocityGateTest`（6 个用例） |
| inactive 照样打包 + 台账 + 冻结核对 + 零回写 | `EntityMirrorTest`（7 个用例） |
| `approximatelyEquals` 阈值、`!=` vs 近似、`Double.compare` | `MoveBytecodeTruthTest`（8 个用例） |

### 7.3 未验证 / 留白（**不要当成已完成**）

1. **没有任何真机验证**：本流没起服务器、没跑 mixin。`EntitySource` 的 MC 适配
   （`ServerWorld.getEntities()` / `Entity` 字段读取）**一行都没写** —— 那是注入流的路径。
2. **`InactivityProbe.serverCore()` 没在真机上命中过**：`ServerCoreAdapter.isInactive`
   本身有它自己的单测（`cava.compat` 流），但「实体路径上真的读到 inactive=true」
   需要真实服务端。**未验证**。
3. **冻结核对的例外路径未量化**：载具/乘客、活塞推动的实体是否真的「位置逐位不变」，
   真机上需要用台账样本确认（本流只做了机制 + 单测）。
4. **性能未测**：本流不声称任何性能结论。镜像每 tick 打包 15 个 double × N 实体的成本
   没有测量；台账每 tick 的分配（快照排序）也没有测量。
5. **`MoveCallbacks` 的生产实现不存在**：注入流要把 19 个方法接到 `Entity` 的对应调用上。
   本流只验证了「骨架按这个顺序调」，**没有**验证「实现接对了」。
6. **`stepOnBlock` 的 `bl4` 语义照抄但未在真机核对**：原版把
   `blockPos2.equals(blockPos)` 当作 `emitsGameEvents` 传给主调用
   （偏移 747 的 `iload 21`），看起来像原版自身的一个怪癖，但字节码就是这样。
   **单测按字节码断言，真机行为未验证。**
7. **`EntitySource.empty()` 之外没有别的实现**：接口本身已在单测里被假实现覆盖，
   但真实世界的迭代顺序/实体集合来源（`ServerWorld.getEntities()` 的过滤器）
   完全没验证。

---

## 8. 跨流请求

| # | 给谁 | 请求 |
| --- | --- | --- |
| 1 | P2-K（ABI） | `EntityFlags` 的 11 个位请直接采用 `cava/entity/EntityFlags.java` 的位号（本流是唯一定义处）；结构体字段顺序建议与 `EntitySample` 一致（id/type/pos/vel/bbox/flags），便于两侧逐字段对拍 |
| 2 | 注入流 | 实现 `EntitySource`（2 个方法）即可接上 MC 世界；实现 `MoveCallbacks`（19 个方法）即可接上回放。**两者的契约都在接口 javadoc 里** |
| 3 | 注入流 | `Entity.move` 的 HEAD 若是 VMP 同点，需要 `VmpZeroVelocityGate`；`setBoundingBox` 的 HEAD 需要 `onSetBoundingBoxHeadBeforeAssign`。**调用顺序**：`onMoveHead` 必须在 `move` 的一切副作用之前 |
| 4 | captain | 本流**没有**碰 `cava/compat/**`（也没碰 native / mirror / hook / mixin / ffm / build.gradle）。`VmpAdapter.State` 已被本流复用（未修改）；若以后要改它的语义，请同时通知本流（`VmpZeroVelocityGate` 是薄包装） |
| 5 | captain | `docs/CAVA-gates.md` 的门禁 #7 里「位置依赖的碰撞形状」结论对本流同样适用：**回放路径不许把形状降级成扁平 AABB**，本流已用接口 + 单测把这条钉住 |

---

## 9. 复现

    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $env:GRADLE_USER_HOME='J:\mc\Cava\.gradle-home'
    $env:JAVA_TOOL_OPTIONS='-Duser.language=en -Dfile.encoding=UTF-8'
    $env:TMP='J:\mc\Cava\build\tmp'; $env:TEMP=$env:TMP
    .\gradlew.bat build --rerun-tasks --no-configuration-cache --console=plain --no-watch-fs
    .\gradlew.bat test --rerun-tasks --no-configuration-cache --console=plain --no-watch-fs
    .\gradlew.bat test --tests "cava.entity.*" --console=plain --no-watch-fs

读字节码：

    & 'C:\Program Files\Java\jdk-21\bin\javap.exe' -p -c -classpath '<minecraft-common-1.20.4-…jar>' net.minecraft.entity.Entity > build\p2-java-scratch\Entity.txt

**本流踩到的两个坑**：
1. `javap` 的输出重定向命令在 PowerShell 5.1 里要**整条一行写完**；
   用反引号续行时反引号本身会被当成内容传进去。
2. **单测真的抓到了两个我自己写的 bug**（都是「看起来对」的那种）：
   ① 回放里为了拼日志又调了一次 `isOnGround()` —— 比原版多一次虚调用；
   ② `LANDING_RAYCAST_HIT` 事件在一进入 `d > 1e-7` 块就被 `take()` 标成已消费，
   于是「守卫不成立、这一步根本没跑」时它被**静默吞掉**。
   两个都是「把调用序列写死」的定点用例抓出来的，随机向量永远抓不到。
