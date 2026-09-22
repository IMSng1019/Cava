package cava.entity;

import cava.mixin.entity.McMoveAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

/**
 * {@code live} 接管用的**生产会话**：一个对象同时是
 * {@link MoveCallbacks}（原版虚方法的注入点）与 {@link MoveInputSource}（拉取式输入源）。
 *
 * <h2>为什么是一个对象</h2>
 * 两者的状态是同一份：{@code landingPos} / {@code steppingPos} / {@code MoveEffect} 这些量
 * 在原版里是**局部变量**，被后面多处复用（{@code landingPos} 在 416 解析、518/548/565/740 复用；
 * {@code steppingState} 在 636 解析、722/744/770/841 复用；{@code effect} 在 569 解析、
 * 576/744/770/813/825 复用）。会话把"拉取到的那个对象"记住，正是为了复刻这种复用 ——
 * 重查一次世界不但更慢，还可能拿到不同的对象。
 *
 * <h2>生命周期</h2>
 * 每次 {@code Entity.move} 建一个新会话（不可重入、不跨线程）。构造参数里的
 * {@code wasOnFire} 是 HEAD 处捕获的 {@code isOnFire()}（原版偏移 39–44 的局部 6），
 * 不是后面现读的字段。
 *
 * <h2>契约边界</h2>
 * 三角函数（{@code travel} 里的）**全部留在 Java 侧**：本会话只吃算好的 {@code Vec3d}。
 * 原生内核只被问"这一份形状列表 + 这个位移 → 夹到多少"。
 */
final class LiveMoveSession implements MoveCallbacks<BlockState, VoxelShape>, MoveInputSource {

    private final Entity self;
    private final World world;
    private final McMoveAccess access;

    /** 过完 {@code adjustMovementForSneaking} 的位移（原版局部 2）。 */
    private final Vec3d movement;
    /** 原生求解结果 = 原版局部 3（{@code adjustMovementForCollisions} 的返回值）。 */
    private final Vec3d adjusted;
    /** 原版偏移 39–44 捕获的 {@code isOnFire()}（局部 6），火焰分支要用。 */
    private final boolean wasOnFire;
    /** 诊断金丝雀：true = **不**调用 {@code Entity.setPosition}（见 EntityMoveRuntime#PROP_CANARY）。 */
    private final boolean canarySkipSetPosition;

    /** 偏移 416 拉到的落点（原版局部 8）。 */
    private BlockPos landingPos;
    /** 偏移 626 拉到的踩踏点（原版局部 18）。 */
    private BlockPos steppingPos;
    /** 偏移 569 拉到的 {@code MoveEffect}（原版局部 11）。 */
    private Entity.MoveEffect effect;

    LiveMoveSession(Entity self, Vec3d movement, Vec3d adjusted, boolean wasOnFire,
            boolean canarySkipSetPosition) {
        this.self = self;
        this.world = self.getWorld();
        this.access = (McMoveAccess) (Object) self;
        this.movement = movement;
        this.adjusted = adjusted;
        this.wasOnFire = wasOnFire;
        this.canarySkipSetPosition = canarySkipSetPosition;
    }

    // ==================================================================
    // MoveInputSource：每个方法都是"当场向实体/世界取值"
    // ==================================================================

    @Override
    public Vec3d movement() {
        return movement;
    }

    @Override
    public Vec3d adjusted() {
        return adjusted;
    }

    @Override
    public double posX() {
        return self.getX();
    }

    @Override
    public double posY() {
        return self.getY();
    }

    @Override
    public double posZ() {
        return self.getZ();
    }

    @Override
    public float fallDistance() {
        return self.fallDistance;
    }

    @Override
    public BlockPos landingPos() {
        // 偏移 416：此刻 supportingBlockPos 已经被 412 的 setOnGround 改写过。
        return landingPos = self.getLandingPos();
    }

    @Override
    public BlockPos steppingPos() {
        return steppingPos = self.getSteppingPos();   // 偏移 626
    }

    @Override
    public boolean moveEffectHasAny() {
        effect = access.cava$getMoveEffect();         // 偏移 569（局部 11）
        return effect.hasAny();                       // 偏移 576
    }

    @Override
    public boolean hasVehicle() {
        return self.hasVehicle();                     // 偏移 583
    }

    @Override
    public boolean stepSoundDistanceExceeded() {
        // 偏移 708–717：两个字段在 589–705 的记账里刚被写过。
        return self.distanceTraveled > access.cava$nextStepSoundDistance();
    }

    @Override
    public boolean steppingEqualsLanding() {
        // 偏移 728–735：比较的是 626 解析出来的那个对象与 416 解析出来的那个对象。
        return steppingPos.equals(landingPos);
    }

    @Override
    public boolean moveEffectPlaysSounds() {
        return effect.playsSounds();                  // 偏移 744（复用 569 的局部 11）
    }

    @Override
    public boolean moveEffectEmitsGameEvents() {
        return effect.emitsGameEvents();              // 偏移 770
    }

    @Override
    public boolean touchingWater() {
        return self.isTouchingWater();                // 偏移 796
    }

    @Override
    public boolean regionLoaded() {
        // checkBlockCollision 偏移 5–67：盒子取的是 **setPosition 之后**的当前碰撞箱。
        Box box = self.getBoundingBox();
        BlockPos from = BlockPos.ofFloored(box.minX + 1.0E-7, box.minY + 1.0E-7, box.minZ + 1.0E-7);
        BlockPos to = BlockPos.ofFloored(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
        return world.isRegionLoaded(from, to);
    }

    @Override
    public float velocityMultiplier() {
        return access.cava$getVelocityMultiplier();   // 偏移 858
    }

    @Override
    public int supplyEventsAt(MoveStep step, MoveEventLog log) {
        switch (step) {
            case LANDING_RAYCAST -> {
                // 偏移 167–214：World.raycast(RaycastContext{FALLDAMAGE_RESETTING, WATER, this})
                Vec3d start = self.getPos();
                BlockHitResult hit = world.raycast(new RaycastContext(start, start.add(adjusted),
                        RaycastContext.ShapeType.FALLDAMAGE_RESETTING, RaycastContext.FluidHandling.WATER, self));
                if (hit.getType() != HitResult.Type.MISS) {
                    BlockPos pos = hit.getBlockPos();
                    log.add(MoveEventKind.LANDING_RAYCAST_HIT, pos.getX(), pos.getY(), pos.getZ(),
                            hit.getType().ordinal());
                    return 1;
                }
                return 0;
            }
            case BLOCK_COLLISION -> {
                // checkBlockCollision 偏移 82–239：**x 外层 → y 中层 → z 内层**（与 getBlockCollisions 的
                // CuboidBlockIterator 恰好相反，见 oracle spec §6.3）。这里只枚举坐标，
                // 方块状态由回放在同一循环里逐格现取（对象身份/时序与原版一致）。
                Box box = self.getBoundingBox();
                BlockPos from = BlockPos.ofFloored(box.minX + 1.0E-7, box.minY + 1.0E-7, box.minZ + 1.0E-7);
                BlockPos to = BlockPos.ofFloored(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
                int n = 0;
                for (int x = from.getX(); x <= to.getX(); x++) {
                    for (int y = from.getY(); y <= to.getY(); y++) {
                        for (int z = from.getZ(); z <= to.getZ(); z++) {
                            log.add(MoveEventKind.COLLIDING_BLOCK, x, y, z, 0);
                            n++;
                        }
                    }
                }
                return n;
            }
            case FIRE_BOX -> {
                // 偏移 881–903：world.getStatesInBoxIfLoaded(boundingBox.contract(1.0E-6)).noneMatch(isOf(FIRE))
                boolean noFire = world.getStatesInBoxIfLoaded(self.getBoundingBox().contract(1.0E-6))
                        .noneMatch(state -> state.isOf(Blocks.FIRE));
                if (!noFire) {
                    log.add(MoveEventKind.FIRE_IN_BOX, 0, 0, 0, 0);
                    return 1;
                }
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }

    // ==================================================================
    // MoveCallbacks：原版虚方法的注入点（顺序由 EventReplay 决定）
    // ==================================================================

    @Override
    public BlockState stateAt(BlockPos pos) {
        return world.getBlockState(pos);
    }

    @Override
    public VoxelShape collisionShapeAt(BlockPos pos) {
        // 只用于 AXIS_CLIP 的诊断行；取的就是 getBlockCollisions 会用的那个形状来源。
        return world.getBlockState(pos).getCollisionShape(world, pos, ShapeContext.of(self));
    }

    @Override
    public void onLanding() {
        self.onLanding();
    }

    @Override
    public void setPosition(double x, double y, double z) {
        if (canarySkipSetPosition) {
            return;   // 破坏性金丝雀：位置不再被回放写下去（可证伪，见 PROP_CANARY）
        }
        self.setPosition(x, y, z);
    }

    @Override
    public void setCollisionFlags(MoveFlags flags) {
        self.horizontalCollision = flags.horizontalCollision();
        self.verticalCollision = flags.verticalCollision();
        self.groundCollision = flags.groundCollision();
        self.collidedSoftly = flags.collidedSoftly();
    }

    @Override
    public boolean hasCollidedSoftly(Vec3d adjustedIn) {
        return access.cava$hasCollidedSoftly(adjustedIn);
    }

    @Override
    public void setOnGround(boolean onGround, Vec3d adjustedIn) {
        self.setOnGround(onGround, adjustedIn);
    }

    @Override
    public boolean isOnGround() {
        return self.isOnGround();
    }

    @Override
    public void fall(double heightDifference, boolean onGround, BlockState state, BlockPos pos) {
        access.cava$fall(heightDifference, onGround, state, pos);
    }

    @Override
    public boolean isRemoved() {
        return self.isRemoved();
    }

    @Override
    public boolean isAlive() {
        return self.isAlive();
    }

    @Override
    public Vec3d getVelocity() {
        return self.getVelocity();
    }

    @Override
    public void setVelocity(double x, double y, double z) {
        self.setVelocity(x, y, z);
    }

    @Override
    public void onEntityLand(BlockState state) {
        state.getBlock().onEntityLand(world, self);
    }

    @Override
    public void onSteppedOn(BlockState state, BlockPos pos) {
        state.getBlock().onSteppedOn(world, pos, state, self);
    }

    @Override
    public void moveEffectBookkeeping(BlockPos steppingPosIn, BlockState steppingState) {
        // 607–622：speed += (float)(vec3d.length() * 0.6)   ← 0.6 是 **double**，先乘后 d2f
        self.speed += (float) (adjusted.length() * 0.6);
        // 642–656：canClimb 把局部 14（vec3d.y 的副本）覆写成 0.0
        boolean canClimb = access.cava$canClimb(steppingState);
        double e = canClimb ? 0.0 : adjusted.y;
        // 658–672：horizontalSpeed += (float) vec3d.horizontalLength() * 0.6F   ← 这里是 **float** 乘
        self.horizontalSpeed += (float) adjusted.horizontalLength() * 0.6F;
        // 675–705：distanceTraveled += (float) sqrt(dx*dx + e*e + dz*dz) * 0.6F
        self.distanceTraveled += (float) Math.sqrt(adjusted.x * adjusted.x + e * e + adjusted.z * adjusted.z) * 0.6F;
    }

    @Override
    public boolean stepOnBlock(BlockPos pos, BlockState state, boolean playSounds, boolean emitGameEvents,
            Vec3d movementIn) {
        return access.cava$stepOnBlock(pos, state, playSounds, emitGameEvents, movementIn);
    }

    @Override
    public boolean stateIsAir(BlockState state) {
        return state.isAir();
    }

    @Override
    public void refreshNextStepSoundDistance() {
        access.cava$setNextStepSoundDistance(access.cava$calculateNextStepSoundDistance());
    }

    @Override
    public void onSwimEffects() {
        // 811–835：这两个开关读的是 569 解析出来的同一个 effect 对象（局部 11）。
        if (effect.playsSounds()) {
            access.cava$playSwimSound();
        }
        if (effect.emitsGameEvents()) {
            self.emitGameEvent(GameEvent.SWIM);
        }
    }

    @Override
    public void onAirTravelEffects() {
        access.cava$addAirTravelEffects();
    }

    @Override
    public void onEntityCollision(BlockState state, BlockPos pos) {
        state.onEntityCollision(world, pos, self);
    }

    @Override
    public void onBlockCollision(BlockState state) {
        access.cava$onBlockCollision(state);
    }

    @Override
    public void multiplyVelocity(float factor) {
        self.setVelocity(self.getVelocity().multiply((double) factor, 1.0, (double) factor));
    }

    @Override
    public void onFireStep(boolean firePresent) {
        if (!firePresent) {
            // 911–924：fireTicks > 0 则跳过（字节码 ifgt 927）
            if (self.getFireTicks() <= 0) {
                self.setFireTicks(-access.cava$getBurningDuration());
            }
            // 927–949：wasOnFire && (inPowderSnow || isWet())
            if (wasOnFire && (self.inPowderSnow || self.isWet())) {
                access.cava$playExtinguishSound();
            }
        } else {
            // 952–981：isOnFire() && (inPowderSnow || isWet())
            if (self.isOnFire() && (self.inPowderSnow || self.isWet())) {
                self.setFireTicks(-access.cava$getBurningDuration());
            }
        }
    }

    @Override
    public void profilerAfterMovement() {
        world.getProfiler().pop();
        world.getProfiler().push("rest");
    }

    @Override
    public void profilerEnd() {
        world.getProfiler().pop();
    }
}
