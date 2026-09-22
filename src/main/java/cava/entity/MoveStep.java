package cava.entity;

/**
 * {@code Entity.move(MovementType, Vec3d)}（intermediary {@code method_5784}）里
 * <b>所有会产生可观测副作用的步骤</b>，按真实字节码顺序排列。
 *
 * <p><b>唯一事实来源</b>：本枚举的 {@code ordinal()} 就是原版执行顺序，
 * {@link #bytecodeOffset()} 是它在原版 {@code move} 里的字节码偏移。
 * 别处（{@link MoveEventKind}、{@link EventReplay}）<b>只引用它，不重复定义顺序常量</b>
 * —— P1 的硬教训：同一份常量定义两处，错位之后运行期无法发现。
 * {@code MoveBytecodeTruthTest} 断言偏移严格递增并且等于一批写死的字面量，
 * 所以"以后有人重排枚举"会立刻红。
 *
 * <p><b>证据（本流自己跑的，不是转述）</b>（PowerShell 5.1，命令一行写完，避免续行符）：
 * <pre>
 * javap -p -c -classpath &lt;minecraft-common-1.20.4-net.fabricmc.yarn.1_20_4.1.20.4+build.3-v2.jar&gt; net.minecraft.entity.Entity
 * </pre>
 * 完整转录见 {@code docs/CAVA-p2-java-notes.md} 第 2 节。
 *
 * <p><b>不覆盖的部分</b>（都是提前 return，没有需要回放的虚调用）：
 * {@code noClip} 分支（偏移 1–38）、{@code MovementType.PISTON} 且位移被清零（偏移 47–70）。
 * 这两个判定必须留在 Java 侧原位执行，原生接管时也要先走它们。
 */
public enum MoveStep {

    /**
     * 偏移 0（{@code move} 的 HEAD）。<b>不是原版代码，是 VMP 的注入点</b>：
     * {@code com.ishland.vmp.mixins.entity.move_zero_velocity.MixinEntity.onMove}
     * 的 {@code @Inject(at=HEAD, cancellable=true)}。
     * 复刻见 {@link VmpZeroVelocityGate}（含"包围盒变过一次后永久失效"的黏滞语义）。
     */
    VMP_ZERO_VELOCITY(0, "vmp:MixinEntity.onMove(@HEAD,cancellable)", false),

    /** 偏移 132：{@code invokevirtual Entity.adjustMovementForCollisions(Vec3d)} —— <b>P2 的原生目标</b>。 */
    COLLISION_SOLVE(132, "Entity.adjustMovementForCollisions(Vec3d)", false),

    /**
     * 偏移 197：{@code World.raycast(RaycastContext{FALLDAMAGE_RESETTING})}，
     * 命中（{@code Type != MISS}）则 {@code Entity.onLanding()}（偏移 214）。
     * 守卫（偏移 151–164）：{@code fallDistance != 0.0F && d >= 1.0}，且整段在 {@code d > 1e-7} 内。
     */
    LANDING_RAYCAST(197, "Entity.onLanding()", false),

    /** 偏移 218–245：{@code Entity.setPosition(getX()+dx, getY()+dy, getZ()+dz)}。 */
    SET_POSITION(218, "Entity.setPosition(DDD)", false),

    /**
     * 偏移 283–403：四个碰撞标志位的计算与赋值
     * （{@code horizontalCollision} 333、{@code verticalCollision} 354、
     * {@code groundCollision} 379、{@code collidedSoftly} 391/398/403）。
     * 其中只有 {@code collidedSoftly} 是虚调用：{@code Entity.hasCollidedSoftly(Vec3d)}，
     * 且<b>仅当 horizontalCollision 为真</b>才调用（否则直接置 false）。
     */
    SET_COLLISION_FLAGS(283, "Entity.hasCollidedSoftly(Vec3d)", false),

    /** 偏移 412：{@code Entity.setOnGround(Z, Vec3d)}。 */
    SET_ON_GROUND(412, "Entity.setOnGround(Z,Vec3d)", false),

    /**
     * 偏移 416–445：{@code BlockPos blockPos = getLandingPos();}
     * {@code BlockState blockState = world.getBlockState(blockPos);}
     * {@code fall(vec3d.y, isOnGround(), blockState, blockPos);}
     * <b>只在这里解析一次方块状态</b>，偏移 518/565 复用同一引用。
     */
    FALL(416, "Entity.fall(D,Z,BlockState,BlockPos)", true),

    /**
     * 偏移 448：{@code if (isRemoved()) { pop; return; }} —— <b>回放必须在这里提前结束</b>。
     * 它后面的步骤（清水平速度 / onEntityLand / onSteppedOn / 方块碰撞扫描 / 火焰）
     * 在原版里<b>都不会发生</b>。
     */
    REMOVED_EARLY_RETURN(448, "Entity.isRemoved()", false),

    /** 偏移 468–515：{@code setVelocity(bl ? 0.0 : v.x, v.y, bl2 ? 0.0 : v.z)}。 */
    HORIZONTAL_VELOCITY_ZERO(468, "Entity.setVelocity(DDD)", false),

    /** 偏移 525–544：守卫 {@code movement.y != vec3d.y} → {@code Block.onEntityLand(BlockView,Entity)}。 */
    ENTITY_LAND(525, "Block.onEntityLand(BlockView,Entity)", true),

    /** 偏移 547–565：守卫 {@code isOnGround()} → {@code Block.onSteppedOn(World,BlockPos,BlockState,Entity)}。 */
    STEPPED_ON(547, "Block.onSteppedOn(World,BlockPos,BlockState,Entity)", true),

    /** 偏移 737–749：{@code stepOnBlock(落点, 落点状态, playsSounds, 落点==踩踏点, movement)}（私有方法）。 */
    STEP_ON_BLOCK_MAIN(737, "Entity.stepOnBlock(BlockPos,BlockState,Z,Z,Vec3d)", true),

    /** 偏移 755–774：{@code stepOnBlock(踩踏点, 踩踏点状态, false, emitsGameEvents, movement)}，仅在两点不同时。 */
    STEP_ON_BLOCK_SECOND(755, "Entity.stepOnBlock(BlockPos,BlockState,Z,Z,Vec3d)", true),

    /** 偏移 796–835：{@code playSwimSound()}（819）与 {@code emitGameEvent(GameEvent.SWIM)}（831）。 */
    SWIM_EFFECTS(796, "Entity.playSwimSound()/emitGameEvent(SWIM)", false),

    /** 偏移 841–850：{@code Entity.addAirTravelEffects()}。 */
    AIR_TRAVEL_EFFECTS(841, "Entity.addAirTravelEffects()", false),

    /**
     * 偏移 853：{@code Entity.tryCheckBlockCollision()} → {@code checkBlockCollision()}（偏移 2576 起）。
     *
     * <p>内层逐方块顺序 = <b>x 外层 → y 中层 → z 内层</b>
     * （由 2576 处的 {@code iinc} 顺序实测确定：z 在 224、y 在 230、x 在 236）。
     * 每格<b>先</b>查 {@code isAlive()}（偏移 127，为假立刻 return），
     * <b>再</b>取方块状态，然后依次 {@code BlockState.onEntityCollision(World,BlockPos,Entity)}（167）
     * 与 {@code Entity.onBlockCollision(BlockState)}（173）。
     */
    BLOCK_COLLISION(853, "BlockState.onEntityCollision / Entity.onBlockCollision", true),

    /** 偏移 857–878：{@code setVelocity(getVelocity().multiply(f,1.0,f))}，{@code f = getVelocityMultiplier()}。 */
    VELOCITY_MULTIPLIER(857, "Entity.setVelocity(Vec3d)", false),

    /**
     * 偏移 881–982：火焰分支。
     * {@code world.getStatesInBoxIfLoaded(boundingBox.contract(1.0E-6)).noneMatch(s -> s.isOf(Blocks.FIRE))}
     * 为真 → {@code setFireTicks(-getBurningDuration())}（924）/ {@code playExtinguishSound()}（942）；
     * 为假 → {@code isOnFire() && (inPowderSnow || isWet())} 时 {@code setFireTicks(...)}（979）。
     */
    FIRE_BOX(881, "World.getStatesInBoxIfLoaded(...).noneMatch(isOf(FIRE))", false);

    private final int bytecodeOffset;
    private final String vanillaTarget;
    private final boolean touchesBlockState;

    MoveStep(int bytecodeOffset, String vanillaTarget, boolean touchesBlockState) {
        this.bytecodeOffset = bytecodeOffset;
        this.vanillaTarget = vanillaTarget;
        this.touchesBlockState = touchesBlockState;
    }

    /** {@code net.minecraft.entity.Entity.move} 里本步骤第一条相关指令的字节码偏移。 */
    public int bytecodeOffset() {
        return bytecodeOffset;
    }

    /** 原版里这一步调用的方法（签名逐字抄自 javap 输出的注释列）。 */
    public String vanillaTarget() {
        return vanillaTarget;
    }

    /**
     * 这一步是否需要<b>原始方块状态对象</b>。
     *
     * <p>为 true 的步骤，回放时必须经 {@link MoveCallbacks#stateAt} 拿世界里的真实对象，
     * <b>不允许</b>用原生算出来的扁平 AABB 重建一个替身 ——
     * 否则 mod 覆写的方块行为（{@code onEntityCollision} / {@code onSteppedOn} / {@code onEntityLand}）
     * 会整段丢失，而且不会报错。
     */
    public boolean touchesBlockState() {
        return touchesBlockState;
    }

    /** 原版执行顺序（= 声明顺序 = {@link #bytecodeOffset()} 升序）。 */
    public int order() {
        return ordinal();
    }
}
