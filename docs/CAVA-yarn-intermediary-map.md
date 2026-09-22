# Cava Yarn <-> intermediary 映射速查（1.20.4 / yarn 1.20.4+build.3）

本表**由本机官方映射文件直接解析生成，不是凭记忆写的**。来源：
`C:/Users/郁小悟520/.gradle/caches/fabric-loom/1.20.4/net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2/mappings.tiny`
（tiny v2：class 行 = c / official / intermediary / named；member 行 = m / officialDesc / intermediaryDesc / intermediaryName / namedName）。
生成脚本：`.cava-research/gen-yarn-map.cjs`（gitignore，可随时重跑）。

**用法**：写 mixin 用 named（第 1 列）；需要写死混淆名（mixin JSON 配置、跨映射引用）用 intermediary 列；official 描述符用于读别的 mod 源码——很多 mod 的 mixin 写的是 Mojang 名，这正是「看起来不冲突、其实撞在同一个方法上」的常见原因。

描述符里的 `a` / `djh` 是 official 混淆类名简写（L 开头），对照同一张表的 class 行换算。

## net/minecraft/entity/ai/pathing/PathNodeNavigator

`official=efi`  `net/minecraft/class_13`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `findPathToAny` | (Lbgs;Lefe;Ljava/util/Map;FIF)Lefg; | `method_54` |
| `findPathToAny` | (Lcuc;Lbmn;Ljava/util/Set;FIF)Lefg; | `method_52` |

## net/minecraft/entity/ai/pathing/PathNodeMaker

`official=eff`  `net/minecraft/class_8`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getStart` | ()Lefe; | `method_21` |
| `getNode` | (DDD)Lefk; | `method_16` |
| `getNode` | (III)Lefe; | `method_13` |
| `getNode` | (Lhx;)Lefe; | `method_27137` |
| **未找到** | getNeighbors, getNodeTypeFromNeighbors, getCommonNodeType, getLandNodeType, getBlockedNodeType, getPathNodeType | — |

## net/minecraft/entity/ai/pathing/LandPathNodeMaker

`official=efl`  `net/minecraft/class_14`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getNodeType` | (Lbmn;III)Lefc; | `method_29303` |
| `getNodeType` | (Lbmn;Lhx;)Lefc; | `method_63` |
| `getLandNodeType` | (Lcsv;Lhx$a;)Lefc; | `method_23476` |
| `getNodeTypeFromNeighbors` | (Lcsv;Lhx$a;Lefc;)Lefc; | `method_59` |
| `getFeetY` | (Lcsv;Lhx;)D | `method_60` |
| `getCommonNodeType` | (Lcsv;Lhx;)Lefc; | `method_58` |
| `isAmphibious` | ()Z | `method_37004` |
| `getStart` | (Lhx;)Lefe; | `method_43415` |
| `getFeetY` | (Lhx;)D | `method_37003` |
| **未找到** | getNode, getNeighbors, getPathNodeTypeRaw, isDoorOpen, canEnterDoor, getBlockedNodeType, getPathNodeType | — |

## net/minecraft/entity/ai/pathing/BirdPathNodeMaker

`official=efd`  `net/minecraft/class_6`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getNodeType` | (IIIJ)Lefc; | `method_9` |
| `isPassable` | (Lefe;)Z | `method_22877` |
| `getNodeType` | (III)Lefc; | `method_31932` |
| **未找到** | getStart, getNode, getNeighbors, canPathThrough | — |

## net/minecraft/entity/ai/pathing/WaterPathNodeMaker

`official=efj`  `net/minecraft/class_12`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `canPathThrough` | (Lefe;Lefe;Lefe;)Z | `method_38488` |
| **未找到** | getStart, getNode, getNeighbors, getNodeType, isPassable | — |

## net/minecraft/entity/ai/pathing/AmphibiousPathNodeMaker

`official=efa`  `net/minecraft/class_15`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| **未找到** | getStart, getNode, getNeighbors, getNodeType, canPathThrough | — |

## net/minecraft/entity/ai/pathing/PathNode

`official=efe`  `net/minecraft/class_9`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getBlockPos` | ()Lhx; | `method_22879` |
| `getDistance` | (Lefe;)F | `method_31` |
| `getDistance` | (Lhx;)F | `method_35494` |
| `getPos` | ()Lelt; | `method_35496` |
| `getSquaredDistance` | (Lhx;)F | `method_35497` |
| `getSquaredDistance` | (Lefe;)F | `method_32` |
| `getManhattanDistance` | (Lhx;)F | `method_21654` |
| `getManhattanDistance` | (Lefe;)F | `method_21653` |
| `equals` | (Ljava/lang/Object;)Z | `equals` |
| **未找到** | getX, getY, getZ, getHeapWeight, getPenalty, getPathNodeType, isVisited, hashCode, isClosed, markClosed | — |

## net/minecraft/entity/ai/pathing/PathNodeType

`official=efc`  `net/minecraft/class_7`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getDefaultPenalty` | ()F | `method_11` |
| `method_36788` | ()[Lefc; | `method_36788` |
| `<init>` | (Ljava/lang/String;IF)V | `<init>` |

## net/minecraft/entity/ai/pathing/PathMinHeap

`official=efb`  `net/minecraft/class_5`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `clear` | ()V | `method_5` |
| `push` | (Lefe;)Lefe; | `method_2` |
| `setNodeWeight` | (Lefe;F)V | `method_3` |
| `pop` | ()Lefe; | `method_6` |
| `isEmpty` | ()Z | `method_8` |
| `getNodes` | ()[Lefe; | `method_35493` |
| **未找到** | getStartNode | — |

## net/minecraft/entity/ai/pathing/Path

`official=efg`  `net/minecraft/class_11`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `next` | ()V | `method_44` |
| `getNode` | (I)Lefe; | `method_40` |
| `setLength` | (I)V | `method_36` |
| `isFinished` | ()Z | `method_46` |
| `getEnd` | ()Lefe; | `method_45` |
| `getLength` | ()I | `method_38` |
| `getTarget` | ()Lhx; | `method_48` |
| `getManhattanDistanceFromTarget` | ()F | `method_21656` |
| **未找到** | getNodes | — |

## net/minecraft/entity/ai/pathing/TargetPathNode

`official=efk`  `net/minecraft/class_4459`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| **未找到** | of, newTarget, getNode | — |

## net/minecraft/entity/ai/pathing/EntityNavigation

`official=bvv`  `net/minecraft/class_1408`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `findPathTo` | (DDDI)Lefg; | `method_6352` |
| `findPathTo` | (Lblv;I)Lefg; | `method_6349` |
| `startMovingAlong` | (Lefg;D)Z | `method_6334` |
| `findPathTo` | (Lhx;I)Lefg; | `method_6348` |
| `findPathTo` | (Lhx;II)Lefg; | `method_35141` |
| `findPathTo` | (Ljava/util/Set;I)Lefg; | `method_29934` |
| `findPathTo` | (Ljava/util/Set;IZI)Lefg; | `method_35142` |
| `shouldRecalculatePath` | (Lhx;)Z | `method_18053` |
| `tick` | ()V | `method_6360` |
| `recalculatePath` | ()V | `method_6356` |
| `getCurrentPath` | ()Lefg; | `method_6345` |
| `isIdle` | ()Z | `method_6357` |
| `stop` | ()V | `method_6340` |
| **未找到** | setCurrentPath | — |

## net/minecraft/entity/ai/pathing/MobNavigation

`official=bvu`  `net/minecraft/class_1409`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| **未找到** | createPath, getPathfindingPenalty, canPathThroughDoors, canEnterDoors | — |

## net/minecraft/entity/Entity

`official=blv`  `net/minecraft/class_1297`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `raycast` | (DFZ)Lelr; | `method_5745` |
| `updateMovementInFluid` | (Lasw;D)Z | `method_5692` |
| `adjustMovementForCollisions` | (Lblv;Lelt;Lelo;Lctp;Ljava/util/List;)Lelt; | `method_20736` |
| `move` | (Lbmr;Lelt;)V | `method_5784` |
| `setBoundingBox` | (Lelo;)V | `method_5857` |
| `adjustMovementForCollisions` | (Lelt;)Lelt; | `method_17835` |
| `adjustMovementForCollisions` | (Lelt;Lelo;Ljava/util/List;)Lelt; | `method_20737` |
| `isOnGround` | ()Z | `method_24828` |
| `getLandingPos` | ()Lhx; | `method_43260` |
| `setPosition` | (DDD)V | `method_5814` |
| `setPosition` | (Lelt;)V | `method_33574` |
| `getEyePos` | ()Lelt; | `method_33571` |
| `isPushable` | ()Z | `method_5810` |
| `getEntityWorld` | ()Lctp; | `method_5770` |
| `getWorld` | ()Lctp; | `method_37908` |
| `getPos` | ()Lelt; | `method_19538` |
| `getVelocity` | ()Lelt; | `method_18798` |
| `squaredDistanceTo` | (Lblv;)D | `method_5858` |
| `squaredDistanceTo` | (Lelt;)D | `method_5707` |
| `pushAwayFrom` | (Lblv;)V | `method_5697` |
| `setVelocity` | (Lelt;)V | `method_18799` |
| `squaredDistanceTo` | (DDD)D | `method_5649` |
| `setVelocity` | (DDD)V | `method_18800` |
| **未找到** | getBoundingBox, getBlockPos | — |

## net/minecraft/entity/LivingEntity

`official=bml`  `net/minecraft/class_1309`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `tickCramming` | ()V | `method_6070` |
| `travel` | (Lelt;)V | `method_6091` |
| `getJumpVelocity` | ()F | `method_6106` |
| **未找到** | getMoveEffect, isPushable, calcGlidingVelocity | — |

## net/minecraft/util/math/Box

`official=elo`  `net/minecraft/class_238`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `intersects` | (DDDDDD)Z | `method_1003` |
| `intersection` | (Lelo;)Lelo; | `method_999` |
| `intersects` | (Lelt;Lelt;)Z | `method_993` |
| `offset` | (Lhx;)Lelo; | `method_996` |
| `getLengthX` | ()D | `method_17939` |
| `stretch` | (DDD)Lelo; | `method_1012` |
| `union` | (Lelo;)Lelo; | `method_991` |
| `stretch` | (Lelt;)Lelo; | `method_18804` |
| `getLengthY` | ()D | `method_17940` |
| `expand` | (DDD)Lelo; | `method_1009` |
| `intersects` | (Lelo;)Z | `method_994` |
| `offset` | (Lelt;)Lelo; | `method_997` |
| `getLengthZ` | ()D | `method_17941` |
| `offset` | (DDD)Lelo; | `method_989` |
| `contains` | (Lelt;)Z | `method_1006` |
| `contains` | (DDD)Z | `method_1008` |
| `squaredMagnitude` | (Lelt;)D | `method_49271` |
| `getCenter` | ()Lelt; | `method_1005` |
| `contract` | (DDD)Lelo; | `method_35580` |
| `expand` | (D)Lelo; | `method_1014` |
| `contract` | (D)Lelo; | `method_1011` |

## net/minecraft/util/shape/VoxelShape

`official=emm`  `net/minecraft/class_265`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `offset` | (DDD)Lemm; | `method_1096` |
| `forEachEdge` | (Lemj$a;)V | `method_1104` |
| `calculateMaxDistance` | (Lhv;Lelo;D)D | `method_1103` |
| `getStartingCoord` | (Lic$a;DD)D | `method_35593` |
| `calculateMaxDistance` | (Lic$a;Lelo;D)D | `method_1108` |
| `getFace` | (Lic;)Lemm; | `method_20538` |
| `isEmpty` | ()Z | `method_1110` |
| `getBoundingBoxes` | ()Ljava/util/List; | `method_1090` |
| **未找到** | getFaces | — |

## net/minecraft/world/CollisionView

`official=csz`  `net/minecraft/class_1941`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `isSpaceEmpty` | (Lblv;Lelo;)Z | `method_8587` |
| `isSpaceEmpty` | (Lelo;)Z | `method_18026` |
| `getBlockCollisions` | (Lblv;Lelo;)Ljava/lang/Iterable; | `method_20812` |
| `isSpaceEmpty` | (Lblv;)Z | `method_17892` |

## net/minecraft/world/BlockView

`official=csv`  `net/minecraft/class_1922`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getBlockEntity` | (Lhx;Ldgx;)Ljava/util/Optional; | `method_35230` |
| `getBlockState` | (Lhx;)Ldjh; | `method_8320` |
| `getFluidState` | (Lhx;)Leer; | `method_8316` |
| `getBlockEntity` | (Lhx;)Ldgv; | `method_8321` |
| **未找到** | clip, getBlockEntityIfLoaded | — |

## net/minecraft/world/EntityView

`official=ctg`  `net/minecraft/class_1924`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getOtherEntities` | (Lblv;Lelo;Ljava/util/function/Predicate;)Ljava/util/List; | `method_8333` |
| `getPlayers` | (Lbwz;Lbml;Lelo;)Ljava/util/List; | `method_18464` |
| `getEntitiesByClass` | (Ljava/lang/Class;Lelo;Ljava/util/function/Predicate;)Ljava/util/List; | `method_8390` |
| `getOtherEntities` | (Lblv;Lelo;)Ljava/util/List; | `method_8335` |
| `getEntityCollisions` | (Lblv;Lelo;)Ljava/util/List; | `method_20743` |
| `getPlayers` | ()Ljava/util/List; | `method_18456` |
| **未找到** | getEntities | — |

## net/minecraft/world/block/NeighborUpdater

`official=eft`  `net/minecraft/class_7165`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `replaceWithStateForNeighborUpdate` | (Lctq;Lic;Ldjh;Lhx;Lhx;II)V | `method_42393` |
| `updateNeighbors` | (Lhx;Lcwq;Lic;)V | `method_41705` |
| `replaceWithStateForNeighborUpdate` | (Lic;Ldjh;Lhx;Lhx;II)V | `method_42392` |
| **未找到** | update, updateNeighborsAlways, updateNeighborsExcept | — |

## net/minecraft/world/block/ChainRestrictedNeighborUpdater

`official=efr`  `net/minecraft/class_7159`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `enqueue` | (Lhx;Lefr$c;)V | `method_41706` |
| **未找到** | update, replaceWithStateForNeighborUpdate | — |

## net/minecraft/block/RedstoneWireBlock

`official=dcr`  `net/minecraft/class_2457`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getPlacementState` | (Lcsv;Ldjh;Lhx;)Ldjh; | `method_27840` |
| `getRenderConnectionType` | (Lcsv;Lhx;Lic;)Ldkm; | `method_10477` |
| `getRenderConnectionType` | (Lcsv;Lhx;Lic;Z)Ldkm; | `method_27841` |
| `getReceivedRedstonePower` | (Lctp;Lhx;)I | `method_27842` |
| `update` | (Lctp;Lhx;Ldjh;)V | `method_10485` |
| `updateNeighbors` | (Lctp;Lhx;)V | `method_10479` |
| `updateOffsetNeighbors` | (Lctp;Lhx;)V | `method_27844` |
| `isNotConnected` | (Ldjh;)Z | `method_28483` |
| `increasePower` | (Ldjh;)I | `method_10486` |
| **未找到** | getStrongRedstonePower, emitsRedstonePower, getWeakRedstonePower, getStateForNeighborUpdate | — |

## net/minecraft/block/AbstractRedstoneGateBlock

`official=cys`  `net/minecraft/class_2312`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getOutputLevel` | (Lcsv;Lhx;Ldjh;)I | `method_9993` |
| `getPower` | (Lctp;Lhx;Ldjh;)I | `method_9991` |
| `canPlaceAbove` | (Lcts;Lhx;Ldjh;)Z | `method_53789` |
| `updatePowered` | (Lctp;Lhx;Ldjh;)V | `method_9998` |
| `isLocked` | (Lcts;Lhx;Ldjh;)Z | `method_9996` |
| `getUpdateDelayInternal` | (Ldjh;)I | `method_9992` |
| **未找到** | getInputLevel | — |

## net/minecraft/block/ComparatorBlock

`official=cya`  `net/minecraft/class_2286`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `calculateOutputSignal` | (Lctp;Lhx;Ldjh;)I | `method_9773` |
| **未找到** | getPower, updatePowered, getUpdateDelayInternal, getComparatorOutput | — |

## net/minecraft/block/RepeaterBlock

`official=dcw`  `net/minecraft/class_2462`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| **未找到** | getUpdateDelayInternal, canPlaceAbove, getPlacementState, getStateForNeighborUpdate | — |

## net/minecraft/block/ObserverBlock

`official=dca`  `net/minecraft/class_2426`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| **未找到** | updatePowered, getPlacementState, scheduledTick | — |

## net/minecraft/block/PistonBlock

`official=dja`  `net/minecraft/class_2665`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `tryMove` | (Lctp;Lhx;Ldjh;)V | `method_11483` |
| `shouldExtend` | (Lcuf;Lhx;Lic;)Z | `method_11482` |
| `isMovable` | (Ldjh;Lctp;Lhx;Lic;ZLic;)Z | `method_11484` |
| **未找到** | onSyncedBlockEvent | — |

## net/minecraft/block/BlockState

`official=djh`  `net/minecraft/class_2680`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| **未找到** | getBlock, isAir, isSolidBlock, blocksMovement, getCollisionShape, isOpaque, getFluidState, getBlockEntityType, onStateReplaced, isReplaceable, getHardness, getBlockEntityType | — |

## net/minecraft/world/chunk/ChunkSection

`official=dlp`  `net/minecraft/class_2826`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getBlockState` | (III)Ldjh; | `method_12254` |
| `setBlockState` | (IIILdjh;)Ldjh; | `method_16675` |
| `setBlockState` | (IIILdjh;Z)Ldjh; | `method_12256` |
| `hasAny` | (Ljava/util/function/Predicate;)Z | `method_19523` |
| `isEmpty` | ()Z | `method_38292` |
| `getBlockStateContainer` | ()Ldlw; | `method_12265` |
| **未找到** | getLightLevel | — |

## net/minecraft/world/chunk/WorldChunk

`official=dlo`  `net/minecraft/class_2818`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| **未找到** | setBlockState, getSection, getSectionArray, getSectionIndex, getBlockState | — |

## net/minecraft/world/World

`official=ctp`  `net/minecraft/class_1937`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `setBlockState` | (Lhx;Ldjh;)Z | `method_8501` |
| **未找到** | getBlockState, getFluidState | — |

## net/minecraft/util/math/BlockPos

`official=hx`  `net/minecraft/class_2338`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `asLong` | ()J | `method_10063` |
| `asLong` | (III)J | `method_10064` |
| `add` | (JIII)J | `method_10096` |
| `offset` | (JLic;)J | `method_10060` |
| `iterate` | (Lhx;Lhx;)Ljava/lang/Iterable; | `method_10097` |
| `offset` | (Lic$a;I)Lhx; | `method_30513` |
| `offset` | (Lic;)Lhx; | `method_10093` |
| `offset` | (Lic;I)Lhx; | `method_10079` |
| `add` | (Ljb;)Lhx; | `method_10081` |
| `add` | (III)Lhx; | `method_10069` |
| `iterate` | (IIIIII)Ljava/lang/Iterable; | `method_10094` |
| `fromLong` | (J)Lhx; | `method_10092` |

## net/minecraft/world/chunk/Chunk

`official=dld`  `net/minecraft/class_2791`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `getSection` | (I)Ldlp; | `method_38259` |
| `getSectionArray` | ()[Ldlp; | `method_12006` |
| `getPos` | ()Lcsw; | `method_12004` |
| **未找到** | getSectionIndex | — |

## net/minecraft/block/Blocks

`official=cws`  `net/minecraft/class_2246`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `method_26143` | (Ldjh;)I | `method_26143` |
| `method_33357` | (Ldjh;)I | `method_33357` |
| `method_26104` | (Ldjh;)I | `method_26104` |
| `method_26145` | (Ldjh;)I | `method_26145` |
| `method_26146` | (Ldjh;)I | `method_26146` |
| `method_26147` | (Ldjh;)I | `method_26147` |
| `method_26148` | (Ldjh;)I | `method_26148` |
| `method_26149` | (Ldjh;)I | `method_26149` |
| `method_26150` | (Ldjh;)I | `method_26150` |
| `method_26151` | (Ldjh;)I | `method_26151` |
| `method_26152` | (Ldjh;)I | `method_26152` |
| `method_26136` | (Ldjh;)I | `method_26136` |
| `method_26105` | (Ldjh;)I | `method_26105` |
| `method_26144` | (Ldjh;)I | `method_26144` |
| `refreshShapeCache` | ()V | `method_26979` |
| `createLightLevelFromLitBlockState` | (I)Ljava/util/function/ToIntFunction; | `method_26107` |
| `method_26108` | (ILdjh;)I | `method_26108` |
| `register` | (Lahf;Lcwq;)Lcwq; | `method_52571` |
| `createBedBlock` | (Lclm;)Lcwq; | `method_26109` |
| `method_26111` | (Lclm;Ldjh;)Leev; | `method_26111` |
| `createShulkerBoxBlock` | (Lclm;Leev;)Lcwq; | `method_26110` |
| `createFlowerPotBlock` | (Lcwq;)Lcwq; | `method_50000` |
| `createLeavesBlock` | (Ldec;)Lcwq; | `method_26106` |
| `method_55132` | (Ldjh;)I | `method_55132` |
| `always` | (Ldjh;Lcsv;Lhx;)Z | `method_26113` |
| `never` | (Ldjh;Lcsv;Lhx;Lblz;)Ljava/lang/Boolean; | `method_26114` |
| `createWoodenButtonBlock` | (Ldjw;)Lcwq; | `method_45451` |
| `createNetherStemBlock` | (Leev;)Lcwq; | `method_26115` |
| `method_26116` | (Leev;Ldjh;)Leev; | `method_26116` |
| `createLogBlock` | (Leev;Leev;)Lcwq; | `method_26117` |
| `createLogBlock` | (Leev;Leev;Ldec;)Lcwq; | `method_47375` |
| `method_47376` | (Leev;Leev;Ldjh;)Leev; | `method_47376` |
| `register` | (Ljava/lang/String;Lcwq;)Lcwq; | `method_9492` |
| `createPistonBlock` | (Z)Lcwq; | `method_26119` |
| `createStoneButtonBlock` | ()Lcwq; | `method_45453` |
| `createStainedGlassBlock` | (Lclm;)Lcwq; | `method_26120` |
| `createOldStairsBlock` | (Lcwq;)Lcwq; | `method_55133` |
| `method_41421` | (Ldjh;)I | `method_41421` |
| `never` | (Ldjh;Lcsv;Lhx;)Z | `method_26122` |
| `always` | (Ldjh;Lcsv;Lhx;Lblz;)Ljava/lang/Boolean; | `method_26123` |
| `createCandleBlock` | (Leev;)Lcwq; | `method_50001` |
| `method_26118` | (Leev;Leev;Ldjh;)Leev; | `method_26118` |
| `method_24165` | ()Ldgx; | `method_24165` |
| `createStairsBlock` | (Lcwq;)Lcwq; | `method_53980` |
| `method_41422` | (Ldjh;)I | `method_41422` |
| `method_32895` | (Ldjh;Lcsv;Lhx;)Z | `method_32895` |
| `canSpawnOnLeaves` | (Ldjh;Lcsv;Lhx;Lblz;)Ljava/lang/Boolean; | `method_26126` |
| `method_41423` | (Ldjh;)I | `method_41423` |
| `method_39537` | (Ldjh;Lcsv;Lhx;)Z | `method_39537` |
| `method_26130` | (Ldjh;Lcsv;Lhx;Lblz;)Z | `method_26130` |
| `method_41424` | (Ldjh;)I | `method_41424` |
| `method_26125` | (Ldjh;Lcsv;Lhx;)Z | `method_26125` |
| `method_26128` | (Ldjh;Lcsv;Lhx;Lblz;)Z | `method_26128` |
| `method_38230` | (Ldjh;)I | `method_38230` |
| `method_53981` | (Ldjh;Lcsv;Lhx;)Z | `method_53981` |
| `method_26132` | (Ldjh;Lcsv;Lhx;Lblz;)Z | `method_26132` |
| `method_36460` | (Ldjh;)I | `method_36460` |
| `method_36461` | (Ldjh;)I | `method_36461` |
| `method_32894` | (Ldjh;)I | `method_32894` |
| `method_26112` | (Ldjh;)I | `method_26112` |
| `method_26121` | (Ldjh;)I | `method_26121` |
| `method_26124` | (Ldjh;)I | `method_26124` |
| `method_26127` | (Ldjh;)I | `method_26127` |
| `method_24419` | (Ldjh;)I | `method_24419` |
| `method_26131` | (Ldjh;)I | `method_26131` |
| `method_26129` | (Ldjh;)I | `method_26129` |
| `method_26134` | (Ldjh;)I | `method_26134` |
| `method_26135` | (Ldjh;)I | `method_26135` |
| `method_36458` | (Ldjh;)I | `method_36458` |
| `method_26137` | (Ldjh;)I | `method_26137` |
| `method_26138` | (Ldjh;)I | `method_26138` |
| `method_26139` | (Ldjh;)I | `method_26139` |
| `method_26140` | (Ldjh;)I | `method_26140` |
| `method_26141` | (Ldjh;)I | `method_26141` |
| `method_26142` | (Ldjh;)I | `method_26142` |
| `method_31625` | (Ldjh;)I | `method_31625` |

## net/minecraft/registry/Registry

`official=it`  `net/minecraft/class_2378`

| named | official 描述符 | intermediary |
| --- | --- | --- |
| `get` | (Lahf;)Ljava/lang/Object; | `method_29107` |
| `get` | (Lahg;)Ljava/lang/Object; | `method_10223` |
| `getId` | (Ljava/lang/Object;)Lahg; | `method_10221` |

## 生成时未解析到的条目（多为继承/接口方法，需到父类小节或人工核对，不要猜）

- net/minecraft/entity/ai/pathing/PathNodeMaker: getNeighbors, getNodeTypeFromNeighbors, getCommonNodeType, getLandNodeType, getBlockedNodeType, getPathNodeType
- net/minecraft/entity/ai/pathing/LandPathNodeMaker: getNode, getNeighbors, getPathNodeTypeRaw, isDoorOpen, canEnterDoor, getBlockedNodeType, getPathNodeType
- net/minecraft/entity/ai/pathing/BirdPathNodeMaker: getStart, getNode, getNeighbors, canPathThrough
- net/minecraft/entity/ai/pathing/WaterPathNodeMaker: getStart, getNode, getNeighbors, getNodeType, isPassable
- net/minecraft/entity/ai/pathing/AmphibiousPathNodeMaker: getStart, getNode, getNeighbors, getNodeType, canPathThrough
- net/minecraft/entity/ai/pathing/PathNode: getX, getY, getZ, getHeapWeight, getPenalty, getPathNodeType, isVisited, hashCode, isClosed, markClosed
- net/minecraft/entity/ai/pathing/PathMinHeap: getStartNode
- net/minecraft/entity/ai/pathing/Path: getNodes
- net/minecraft/entity/ai/pathing/TargetPathNode: of, newTarget, getNode
- net/minecraft/entity/ai/pathing/EntityNavigation: setCurrentPath
- net/minecraft/entity/ai/pathing/MobNavigation: createPath, getPathfindingPenalty, canPathThroughDoors, canEnterDoors
- net/minecraft/entity/Entity: getBoundingBox, getBlockPos
- net/minecraft/entity/LivingEntity: getMoveEffect, isPushable, calcGlidingVelocity
- net/minecraft/util/shape/VoxelShape: getFaces
- net/minecraft/world/BlockView: clip, getBlockEntityIfLoaded
- net/minecraft/world/EntityView: getEntities
- net/minecraft/world/block/NeighborUpdater: update, updateNeighborsAlways, updateNeighborsExcept
- net/minecraft/world/block/ChainRestrictedNeighborUpdater: update, replaceWithStateForNeighborUpdate
- net/minecraft/block/RedstoneWireBlock: getStrongRedstonePower, emitsRedstonePower, getWeakRedstonePower, getStateForNeighborUpdate
- net/minecraft/block/AbstractRedstoneGateBlock: getInputLevel
- net/minecraft/block/ComparatorBlock: getPower, updatePowered, getUpdateDelayInternal, getComparatorOutput
- net/minecraft/block/RepeaterBlock: getUpdateDelayInternal, canPlaceAbove, getPlacementState, getStateForNeighborUpdate
- net/minecraft/block/ObserverBlock: updatePowered, getPlacementState, scheduledTick
- net/minecraft/block/PistonBlock: onSyncedBlockEvent
- net/minecraft/block/BlockState: getBlock, isAir, isSolidBlock, blocksMovement, getCollisionShape, isOpaque, getFluidState, getBlockEntityType, onStateReplaced, isReplaceable, getHardness, getBlockEntityType
- net/minecraft/world/chunk/ChunkSection: getLightLevel
- net/minecraft/world/chunk/WorldChunk: setBlockState, getSection, getSectionArray, getSectionIndex, getBlockState
- net/minecraft/world/World: getBlockState, getFluidState
- net/minecraft/world/chunk/Chunk: getSectionIndex
