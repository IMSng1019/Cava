package cava.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 事件回放骨架：把「原生返回的事件」翻译成「按原版顺序调用哪些虚方法」。
 *
 * <h2>顺序的唯一事实来源</h2>
 * 顺序<b>不由原生决定</b>，也不由本类重新写一遍：本类逐条走 {@link MoveStep#values()}
 * （声明顺序 = {@code Entity.move} 的字节码顺序，见 {@link MoveStep} 的偏移注释），
 * 在每一步里按<b>日志出现顺序</b>消费属于它的 {@link MoveEventKind} 事件。
 * 所以原生把事件写反了也不会造成行为差异 —— 它只会让"消费不掉的事件"变多，
 * 而那被显式计进 {@link Transcript#unconsumed()}。
 *
 * <h2>为什么必须是"Java 侧按坐标回世界取对象"</h2>
 * 事件只带 {@code BlockPos}，方块状态与碰撞形状一律经
 * {@link MoveCallbacks#stateAt} / {@link MoveCallbacks#collisionShapeAt} 现取。
 * 这样 mod 覆写的方块行为（{@code onEntityCollision} / {@code onSteppedOn} /
 * {@code onEntityLand} / 位置依赖的碰撞形状）才会照旧生效；
 * 若用原生算好的扁平 AABB 重建一个替身，行为会在<b>不报任何错</b>的情况下丢失。
 *
 * <h2>fail-closed 的地方</h2>
 * <ul>
 *   <li>{@link MoveEventLog#overflowed()} ⇒ {@link Transcript#error()} 非空
 *       （事件被截断 = 少调虚方法）；</li>
 *   <li>有事件没被任何步骤消费 ⇒ 记进 {@link Transcript#unconsumed()}；</li>
 *   <li>该发事件却没发（守卫成立但没有对应事件）⇒ 记进 {@link Transcript#anomalies()}。</li>
 * </ul>
 * 本类<b>不抛异常</b>：调用方（mixin）看到 {@code strictOk()==false} 应当整段回退纯 Java，
 * 而不是让服务端起不来。
 *
 * @param <S> 方块状态类型
 * @param <H> 碰撞形状类型
 */
public final class EventReplay<S, H> {

    /** 回放流水的一行：步骤 + 调用的方法 + 参数摘要。 */
    public record Line(MoveStep step, String call, String detail) {
        public String text() {
            return step.name() + " | " + call + (detail.isEmpty() ? "" : " | " + detail);
        }
    }

    /** 回放结果。 */
    public record Transcript(List<Line> lines, List<String> axisClips, List<String> unconsumed,
            List<String> anomalies, String error) {

        public Transcript {
            lines = List.copyOf(lines);
            axisClips = List.copyOf(axisClips);
            unconsumed = List.copyOf(unconsumed);
            anomalies = List.copyOf(anomalies);
        }

        public int steps() {
            return lines.size();
        }

        /** 严格通过：没有截断、没有剩余事件、没有异常。 */
        public boolean strictOk() {
            return error == null && unconsumed.isEmpty() && anomalies.isEmpty();
        }

        public List<String> texts() {
            return lines.stream().map(Line::text).toList();
        }

        public String report() {
            return "steps=" + lines.size() + " axisClips=" + axisClips.size()
                    + " unconsumed=" + unconsumed.size() + " anomalies=" + anomalies.size()
                    + " error=" + (error == null ? "-" : error);
        }
    }

    private final MoveCallbacks<S, H> cb;

    public EventReplay(MoveCallbacks<S, H> callbacks) {
        this.cb = callbacks;
    }

    public Transcript replay(MoveInputs in, MoveEventLog log) {
        List<Line> lines = new ArrayList<>();
        List<String> axisClips = new ArrayList<>();
        List<String> anomalies = new ArrayList<>();
        boolean[] used = new boolean[log.size()];
        String error = log.overflowed()
                ? "原生事件数组溢出（cap=" + log.capacity() + "）：事件被截断，回放不完整"
                : null;

        // ---- 0. VMP 零位移短路（在原方法 HEAD，不在这里执行；流水里留位置） ----
        lines.add(new Line(MoveStep.VMP_ZERO_VELOCITY, "guard@HEAD", "见 VmpZeroVelocityGate"));

        // ---- 1. 原生解算（本类的输入就是它的输出）----
        lines.add(new Line(MoveStep.COLLISION_SOLVE, "native",
                "adjusted=" + fmt(in.adjusted()) + " moved=" + in.movedAtAll()));
        for (int i : take(log, used, MoveEventKind.AXIS_CLIP)) {
            BlockPos pos = pos(log, i);
            // 硬要求：这里必须拿**原始形状对象**，不能用扁平 AABB 重建。
            // 这一步同时是将来"把形状喂给原生"的接入点：原生报告"哪一格夹住了哪个轴"，
            // Java 侧回世界取那一格的原始 VoxelShape —— 形状对象从始至终不经过 AABB 降级。
            H shape = cb.collisionShapeAt(pos);
            axisClips.add("axis=" + log.payload(i) + "@" + fmt(pos) + " shapeId=0x"
                    + Integer.toHexString(System.identityHashCode(shape)));
        }

        double d = in.adjustedLengthSquared();

        // ---- 2. 落点射线 + 3. setPosition（同一个 d > 1e-7 分支内）----
        if (in.movedAtAll()) {
            if (in.fallDistance() != 0.0F && d >= 1.0) {
                // 注意：take 只能在"这一步真的执行"的分支里调用 ——
                // 提前 take 会把没被回放的事件静默标成已消费（本测试就是这么抓到这个 bug 的）。
                int[] rayHits = take(log, used, MoveEventKind.LANDING_RAYCAST_HIT);
                if (rayHits.length > 0) {
                    lines.add(new Line(MoveStep.LANDING_RAYCAST, "Entity.onLanding()",
                            "hit=" + fmt(pos(log, rayHits[0])) + " payload=" + log.payload(rayHits[0])));
                    cb.onLanding();
                } else {
                    anomalies.add("MISSING:LANDING_RAYCAST_HIT（守卫成立但原生没发事件）");
                }
            }
            lines.add(new Line(MoveStep.SET_POSITION, "Entity.setPosition(DDD)", fmt(in.positionAfterMove())));
            cb.setPosition(in.posX() + in.adjusted().x, in.posY() + in.adjusted().y, in.posZ() + in.adjusted().z);
        }

        // ---- 4. 碰撞标志位（偏移 283–403）----
        boolean[] mask = MoveFlags.horizontalAxisMask(in.movement(), in.adjusted());
        boolean collidedSoftly = false;
        if (mask[0] || mask[1]) {
            collidedSoftly = cb.hasCollidedSoftly(in.adjusted());
            lines.add(new Line(MoveStep.SET_COLLISION_FLAGS, "Entity.hasCollidedSoftly(Vec3d)",
                    "->" + collidedSoftly));
        }
        MoveFlags flags = MoveFlags.compute(in.movement(), in.adjusted(), collidedSoftly);
        cb.setCollisionFlags(flags);
        lines.add(new Line(MoveStep.SET_COLLISION_FLAGS, "flagFields",
                "h=" + flags.horizontalCollision() + " v=" + flags.verticalCollision()
                        + " g=" + flags.groundCollision() + " soft=" + flags.collidedSoftly()));

        // ---- 5. setOnGround ----
        cb.setOnGround(flags.groundCollision(), in.adjusted());
        lines.add(new Line(MoveStep.SET_ON_GROUND, "Entity.setOnGround(Z,Vec3d)",
                String.valueOf(flags.groundCollision())));

        // ---- 6. 落点方块：**只解析一次**，后续 onEntityLand / onSteppedOn / stepOnBlock 复用同一引用 ----
        BlockPos landingPos = in.landingPos();
        S landingState = cb.stateAt(landingPos);
        // isOnGround() 在这一次回放里只能被问这一次（原版偏移 438）；下一次是偏移 548 的守卫。
        // 记日志时**不能**再问一遍 —— 那会多出一次虚调用（单测把调用序列写死了，多一次就红）。
        boolean onGroundForFall = cb.isOnGround();
        cb.fall(in.adjusted().y, onGroundForFall, landingState, landingPos);
        lines.add(new Line(MoveStep.FALL, "Entity.fall(D,Z,BlockState,BlockPos)",
                "dy=" + in.adjusted().y + " onGround=" + onGroundForFall + " @" + fmt(landingPos)
                        + " stateId=0x" + Integer.toHexString(System.identityHashCode(landingState))));

        // ---- 7. 移除即提前返回（偏移 448）----
        if (cb.isRemoved()) {
            lines.add(new Line(MoveStep.REMOVED_EARLY_RETURN, "Entity.isRemoved()", "true → 回放结束"));
            return finish(lines, axisClips, used, log, anomalies, error);
        }
        lines.add(new Line(MoveStep.REMOVED_EARLY_RETURN, "Entity.isRemoved()", "false"));

        // ---- 8. 水平速度清零（偏移 515）----
        if (flags.horizontalCollision()) {
            Vec3d v = cb.getVelocity();
            double nx = mask[0] ? 0.0 : v.x;
            double nz = mask[1] ? 0.0 : v.z;
            cb.setVelocity(nx, v.y, nz);
            lines.add(new Line(MoveStep.HORIZONTAL_VELOCITY_ZERO, "Entity.setVelocity(DDD)",
                    "(" + nx + "," + v.y + "," + nz + ")"));
        }

        // ---- 9. onEntityLand（偏移 544）----
        if (in.movement().y != in.adjusted().y) {
            cb.onEntityLand(landingState);
            lines.add(new Line(MoveStep.ENTITY_LAND, "Block.onEntityLand", "@" + fmt(landingPos)));
        }

        // ---- 10. onSteppedOn（偏移 565）----
        if (cb.isOnGround()) {
            cb.onSteppedOn(landingState, landingPos);
            lines.add(new Line(MoveStep.STEPPED_ON, "Block.onSteppedOn", "@" + fmt(landingPos)));
        }

        // ---- 11–14. 踩踏音效 / 游泳 / 空中效果（偏移 574–853）----
        if (in.moveEffectHasAny() && !in.hasVehicle()) {
            // 偏移 626/636：steppingPos 与 steppingState 在这一支的**开头**解析一次，
            // 后面的 722（isAir）、744、770 全部复用同一引用。
            BlockPos steppingPos = in.steppingPos();
            S steppingState = cb.stateAt(steppingPos);
            // 偏移 589–705：Java 内部记账（speed / horizontalSpeed / distanceTraveled / canClimb）。
            // 它在 708 的步声判定**之前**，所以必须在这里回调，而不是在分支外。
            cb.moveEffectBookkeeping(steppingPos, steppingState);
            if (in.stepSoundBranch()) {
                boolean played = cb.stepOnBlock(landingPos, landingState, in.moveEffectPlaysSounds(),
                        in.steppingEqualsLanding(), in.movement());
                lines.add(new Line(MoveStep.STEP_ON_BLOCK_MAIN, "Entity.stepOnBlock",
                        "@" + fmt(landingPos) + " playSounds=" + in.moveEffectPlaysSounds()
                                + " emit=" + in.steppingEqualsLanding() + " ->" + played));
                if (!in.steppingEqualsLanding()) {
                    played |= cb.stepOnBlock(steppingPos, steppingState, false, in.moveEffectEmitsGameEvents(),
                            in.movement());
                    lines.add(new Line(MoveStep.STEP_ON_BLOCK_SECOND, "Entity.stepOnBlock",
                            "@" + fmt(steppingPos) + " playSounds=false emit=" + in.moveEffectEmitsGameEvents()
                                    + " ->" + played));
                }
                if (!played && in.touchingWater()) {
                    cb.onSwimEffects();
                    lines.add(new Line(MoveStep.SWIM_EFFECTS, "playSwimSound/emitGameEvent(SWIM)", ""));
                }
            } else if (in.steppingStateIsAir()) {
                cb.onAirTravelEffects();
                lines.add(new Line(MoveStep.AIR_TRAVEL_EFFECTS, "Entity.addAirTravelEffects()", ""));
            }
        }

        // ---- 15. 方块碰撞扫描（偏移 854 → checkBlockCollision）----
        if (in.regionLoaded()) {
            for (int i : take(log, used, MoveEventKind.COLLIDING_BLOCK)) {
                if (!cb.isAlive()) {
                    lines.add(new Line(MoveStep.BLOCK_COLLISION, "Entity.isAlive()", "false → return"));
                    break;
                }
                BlockPos pos = pos(log, i);
                S state = cb.stateAt(pos);
                cb.onEntityCollision(state, pos);
                cb.onBlockCollision(state);
                lines.add(new Line(MoveStep.BLOCK_COLLISION, "BlockState.onEntityCollision/Entity.onBlockCollision",
                        "@" + fmt(pos)));
            }
        }

        // ---- 16. 速度乘子（偏移 875）----
        cb.multiplyVelocity(in.velocityMultiplier());
        lines.add(new Line(MoveStep.VELOCITY_MULTIPLIER, "setVelocity(multiply(f,1,f))",
                "f=" + in.velocityMultiplier()));

        // ---- 17. 火焰分支（偏移 895–982）----
        int[] fires = take(log, used, MoveEventKind.FIRE_IN_BOX);
        boolean firePresent = fires.length > 0;
        cb.onFireStep(firePresent);
        lines.add(new Line(MoveStep.FIRE_BOX, "onFireStep", "firePresent=" + firePresent));

        return finish(lines, axisClips, used, log, anomalies, error);
    }

    private Transcript finish(List<Line> lines, List<String> axisClips, boolean[] used, MoveEventLog log,
            List<String> anomalies, String error) {
        List<String> unconsumed = new ArrayList<>();
        for (int i = 0; i < used.length; i++) {
            if (!used[i]) {
                unconsumed.add(log.kind(i) + "@" + log.x(i) + "," + log.y(i) + "," + log.z(i));
            }
        }
        return new Transcript(lines, axisClips, unconsumed, anomalies, error);
    }

    /** 按日志顺序取出该种类的全部事件下标，并标记已消费。 */
    private static int[] take(MoveEventLog log, boolean[] used, MoveEventKind kind) {
        int n = 0;
        for (int i = 0; i < used.length; i++) {
            if (!used[i] && log.kind(i) == kind) {
                n++;
            }
        }
        int[] out = new int[n];
        int at = 0;
        for (int i = 0; i < used.length; i++) {
            if (!used[i] && log.kind(i) == kind) {
                used[i] = true;
                out[at++] = i;
            }
        }
        return out;
    }

    private static BlockPos pos(MoveEventLog log, int index) {
        return new BlockPos(log.x(index), log.y(index), log.z(index));
    }

    private static String fmt(Vec3d v) {
        return "(" + v.x + "," + v.y + "," + v.z + ")";
    }

    private static String fmt(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
