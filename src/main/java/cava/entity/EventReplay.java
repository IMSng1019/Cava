package cava.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * 事件回放骨架：把「原生返回的事件 + Java 侧现算的事实」翻译成「按原版顺序调用哪些虚方法」。
 *
 * <h2>顺序的唯一事实来源</h2>
 * 顺序<b>不由原生决定</b>，也不由本类重新写一遍：本类逐条走 {@link MoveStep#values()}
 * （声明顺序 = {@code Entity.move} 的字节码顺序，见 {@link MoveStep} 的偏移注释），
 * 在每一步里按<b>日志出现顺序</b>消费属于它的 {@link MoveEventKind} 事件。
 * 所以原生把事件写反了也不会造成行为差异 —— 它只会让"消费不掉的事件"变多，
 * 而那被显式计进 {@link Transcript#unconsumed()}。
 *
 * <h2>拉取式输入（本类的核心形态）</h2>
 * 输入<b>不是预打包的</b>：每一步在<b>它自己的那一刻</b>经 {@link MoveInputSource} 向 Java 侧取值，
 * 因为 {@code Entity.move} 里有四处输入只有回放中途才成立
 * （{@code getLandingPos()} 在 {@code setOnGround} 之后、{@code getSteppingPos()} 在
 * {@code moveEffect} 分支内部、{@code isRegionLoaded} 在 {@code setPosition} 之后、
 * {@code distanceTraveled} 记账之后）。每次拉取都记进 {@link Transcript#pulls()}，
 * 于是"拉取时刻"本身是<b>可断言</b>的（见 {@code MoveInputPullOrderTest}）。
 *
 * <h2>transcript 是**可关的诊断**</h2>
 * {@code trace == false} 时：不建 {@link Line} / {@link Pull} / 诊断字符串、不为 {@code AXIS_CLIP}
 * 去世界取原始形状（那是纯粹为流水服务的世界查询）。<b>判定逻辑一条不少</b>：
 * {@code error}/{@code unconsumed}/{@code anomalies} 照旧算，{@code strictOk()} 照旧可用。
 *
 * <p>理由是本机实测：生产路径带上 transcript 后每次 {@code move} 慢到 <b>15–18 微秒</b>
 * （逐条 {@code "…" + fmt(...)} 的字符串拼接 + 每次拉取一个 record），
 * 而原版整段 {@code move} 只有 1–3 微秒。诊断不该出现在热路径上。
 *
 * <h2>fail-closed 的地方</h2>
 * <ul>
 *   <li>{@link MoveEventLog#overflowed()} ⇒ {@link Transcript#error()} 非空
 *       （事件被截断 = 少调虚方法）；</li>
 *   <li>有事件没被任何步骤消费 ⇒ 记进 {@link Transcript#unconsumed()}；</li>
 *   <li>{@link MoveInputSource#supplyEventsAt} 报告注入了、日志里却消费不到
 *       ⇒ 记进 {@link Transcript#anomalies()}（回执校验）。</li>
 * </ul>
 * 本类<b>不抛异常</b>：调用方（mixin）看到 {@code strictOk()==false} 应当整段回退纯 Java，
 * 而不是让服务端起不来。
 *
 * <p><b>实例不可重入</b>：{@code cur}/{@code used} 是实例状态（每次 {@link #replay} 开头重置）。
 * 生产路径每次 {@code move} 用一个新会话对象，天然满足。
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

    /**
     * 一次<b>拉取</b>：在第 {@code step} 步向 Java 侧要了哪个输入。
     * {@code pulls} 的 {@link MoveStep#ordinal()} 必须单调不减 —— 那是"按原版顺序拉取"的可断言形式。
     */
    public record Pull(MoveStep step, String name) {
        public String text() {
            return step.name() + ":" + name;
        }
    }

    /** 回放结果。{@code pullCount} 在 {@code trace=false} 时仍然有效（纯计数、不分配）。 */
    public record Transcript(List<Line> lines, List<String> axisClips, List<Pull> pulls, int pullCount,
            List<String> unconsumed, List<String> anomalies, String error) {

        public Transcript {
            lines = List.copyOf(lines);
            axisClips = List.copyOf(axisClips);
            pulls = List.copyOf(pulls);
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

        /** 拉取序列（诊断/断言用）。 */
        public List<String> pullTexts() {
            return pulls.stream().map(Pull::text).toList();
        }

        public String report() {
            return "steps=" + lines.size() + " pulls=" + pullCount + " axisClips=" + axisClips.size()
                    + " unconsumed=" + unconsumed.size() + " anomalies=" + anomalies.size()
                    + " error=" + (error == null ? "-" : error);
        }
    }

    private final MoveCallbacks<S, H> cb;
    private final boolean trace;

    private final List<Line> lines = new ArrayList<>();
    private final List<String> axisClips = new ArrayList<>();
    private final List<Pull> pulls = new ArrayList<>();
    private final List<String> anomalies = new ArrayList<>();

    private MoveStep cur = MoveStep.VMP_ZERO_VELOCITY;
    /** 拉取次数（**总是**计数：诊断关闭时也要能报"每次接管拉了几个输入"）。 */
    private int pullCount;

    /**
     * 事件消费位图。
     *
     * <p><b>必须在日志增长时跟着长</b>：三类 Java 侧事实是在回放<b>中途</b>由
     * {@link MoveInputSource#supplyEventsAt} 追加进日志的，若在回放开头按当时的
     * {@code log.size()} 定长分配，追加进来的事件会**一条都消费不到**
     * （{@code MoveInputPullOrderTest} / {@code EventReplayTest} 抓到过这个 bug）。
     */
    private boolean[] used = new boolean[0];

    /** 带完整诊断流水（单测 / 差分用）。 */
    public EventReplay(MoveCallbacks<S, H> callbacks) {
        this(callbacks, true);
    }

    /**
     * @param trace false = <b>生产热路径</b>：不建诊断字符串/记录，也不为 {@code AXIS_CLIP}
     *              去世界取原始形状；判定逻辑不变（见类注释）
     */
    public EventReplay(MoveCallbacks<S, H> callbacks, boolean trace) {
        this.cb = callbacks;
        this.trace = trace;
    }

    // ------------------------------------------------------------------
    // 拉取（trace 时记进 pulls，于是"拉取时刻"可断言）
    // ------------------------------------------------------------------

    private Vec3d pullVec(String name, Vec3d v) {
        pullCount++;
        if (trace) {
            pulls.add(new Pull(cur, name));
        }
        return v;
    }

    private double pullD(String name, double v) {
        pullCount++;
        if (trace) {
            pulls.add(new Pull(cur, name));
        }
        return v;
    }

    private float pullF(String name, float v) {
        pullCount++;
        if (trace) {
            pulls.add(new Pull(cur, name));
        }
        return v;
    }

    private boolean pullB(String name, boolean v) {
        pullCount++;
        if (trace) {
            pulls.add(new Pull(cur, name));
        }
        return v;
    }

    private BlockPos pullP(String name, BlockPos v) {
        pullCount++;
        if (trace) {
            pulls.add(new Pull(cur, name));
        }
        return v;
    }

    public Transcript replay(MoveInputSource in, MoveEventLog log) {
        lines.clear();
        axisClips.clear();
        pulls.clear();
        anomalies.clear();
        pullCount = 0;
        used = new boolean[log.size()];
        String error = log.overflowed()
                ? "原生事件数组溢出（cap=" + log.capacity() + "）：事件被截断，回放不完整"
                : null;

        // ---- 0. VMP 零位移短路（在原方法 HEAD，不在这里执行；流水里留位置） ----
        cur = MoveStep.VMP_ZERO_VELOCITY;
        if (trace) {
            lines.add(new Line(MoveStep.VMP_ZERO_VELOCITY, "guard@HEAD", "见 VmpZeroVelocityGate"));
        }

        // ---- 1. 原生解算（本类的输入就是它的输出）----
        cur = MoveStep.COLLISION_SOLVE;
        Vec3d movement = pullVec("movement", in.movement());   // 偏移 129
        Vec3d adjusted = pullVec("adjusted", in.adjusted());   // 偏移 135
        double d = adjusted.lengthSquared();                   // 偏移 136
        boolean movedAtAll = d > 1.0E-7;                       // 偏移 142–148
        if (trace) {
            lines.add(new Line(MoveStep.COLLISION_SOLVE, "native",
                    "adjusted=" + fmt(adjusted) + " moved=" + movedAtAll));
        }
        for (int i : take(log, MoveEventKind.AXIS_CLIP)) {
            BlockPos pos = pos(log, i);
            // 硬要求：这里必须拿**原始形状对象**，不能用扁平 AABB 重建。
            // 这一步同时是将来"把形状喂给原生"的接入点：原生报告"哪一格夹住了哪个轴"，
            // Java 侧回世界取那一格的原始 VoxelShape —— 形状对象从始至终不经过 AABB 降级。
            // （trace=false 时不取：那是纯粹为流水服务的世界查询，热路径上白花。）
            if (trace) {
                H shape = cb.collisionShapeAt(pos);
                axisClips.add("axis=" + log.payload(i) + "@" + fmt(pos) + " shapeId=0x"
                        + Integer.toHexString(System.identityHashCode(shape)));
            }
        }

        // ---- 2. 落点射线 + 3. setPosition（同一个 d > 1e-7 分支内，偏移 142–247）----
        if (movedAtAll) {
            cur = MoveStep.LANDING_RAYCAST;
            float fallDistance = pullF("fallDistance", in.fallDistance());   // 偏移 152
            if (fallDistance != 0.0F && d >= 1.0) {                          // 偏移 151–164
                // 射线是**世界查询**：内核没有世界，由 Java 侧在这一刻现算并注入事件。
                int supplied = in.supplyEventsAt(MoveStep.LANDING_RAYCAST, log);
                int[] rayHits = take(log, MoveEventKind.LANDING_RAYCAST_HIT);
                if (rayHits.length > 0) {
                    if (trace) {
                        lines.add(new Line(MoveStep.LANDING_RAYCAST, "Entity.onLanding()",
                                "hit=" + fmt(pos(log, rayHits[0])) + " payload=" + log.payload(rayHits[0])));
                    }
                    cb.onLanding();                                      // 偏移 214
                } else if (trace) {
                    lines.add(new Line(MoveStep.LANDING_RAYCAST, "Entity.onLanding()", "MISS"));
                }
                // 回执校验：**注入的事件必须被消费掉**（少了 = 该调的回调没调）。
                // 反向不等（消费多于注入）是正常的：定值夹具的事件是预先放在日志里的。
                if (supplied > rayHits.length) {
                    anomalies.add("MISSING:LANDING_RAYCAST_HIT（supply 报告注入 " + supplied
                            + " 条但日志里只取到 " + rayHits.length + " 条）");
                }
            }
            // 注意：take 只能在"这一步真的执行"的分支里调用 ——
            // 提前 take 会把没被回放的事件静默标成已消费（本测试就是这么抓到这个 bug 的）。
            cur = MoveStep.SET_POSITION;
            double nx = pullD("posX", in.posX()) + adjusted.x;   // 偏移 219
            double ny = pullD("posY", in.posY()) + adjusted.y;   // 偏移 228
            double nz = pullD("posZ", in.posZ()) + adjusted.z;   // 偏移 237
            if (trace) {
                lines.add(new Line(MoveStep.SET_POSITION, "Entity.setPosition(DDD)", fmt(nx, ny, nz)));
            }
            cb.setPosition(nx, ny, nz);                          // 偏移 245
        }

        // 偏移 248–274：pop('move') / push('rest') —— 在标志位计算（275）**之前**，
        // 两条路径（movedAtAll 真/假）都会走到这里。
        cb.profilerAfterMovement();

        // ---- 4. 碰撞标志位（偏移 275–403）----
        cur = MoveStep.SET_COLLISION_FLAGS;
        boolean xBlocked = !net.minecraft.util.math.MathHelper.approximatelyEquals(movement.x, adjusted.x);
        boolean zBlocked = !net.minecraft.util.math.MathHelper.approximatelyEquals(movement.z, adjusted.z);
        boolean collidedSoftly = false;
        if (xBlocked || zBlocked) {                              // 偏移 382–386
            collidedSoftly = cb.hasCollidedSoftly(adjusted);      // 偏移 391
            if (trace) {
                lines.add(new Line(MoveStep.SET_COLLISION_FLAGS, "Entity.hasCollidedSoftly(Vec3d)",
                        "->" + collidedSoftly));
            }
        }
        MoveFlags flags = MoveFlags.compute(movement, adjusted, collidedSoftly);
        cb.setCollisionFlags(flags);
        if (trace) {
            lines.add(new Line(MoveStep.SET_COLLISION_FLAGS, "flagFields",
                    "h=" + flags.horizontalCollision() + " v=" + flags.verticalCollision()
                            + " g=" + flags.groundCollision() + " soft=" + flags.collidedSoftly()));
        }

        // ---- 5. setOnGround（偏移 412）----
        cur = MoveStep.SET_ON_GROUND;
        cb.setOnGround(flags.groundCollision(), adjusted);
        if (trace) {
            lines.add(new Line(MoveStep.SET_ON_GROUND, "Entity.setOnGround(Z,Vec3d)",
                    String.valueOf(flags.groundCollision())));
        }

        // ---- 6. 落点方块：**只解析一次**，后续 onEntityLand / onSteppedOn / stepOnBlock 复用同一引用 ----
        cur = MoveStep.FALL;
        // 关键时序：landingPos 必须在 setOnGround（刚执行完，改写了 supportingBlockPos）**之后**拉。
        BlockPos landingPos = pullP("landingPos", in.landingPos());     // 偏移 416
        S landingState = cb.stateAt(landingPos);                       // 偏移 422
        // isOnGround() 在这一次回放里只能被问这一次（原版偏移 438）；下一次是偏移 548 的守卫。
        // 记日志时**不能**再问一遍 —— 那会多出一次虚调用（单测把调用序列写死了，多一次就红）。
        boolean onGroundForFall = cb.isOnGround();
        cb.fall(adjusted.y, onGroundForFall, landingState, landingPos); // 偏移 445
        if (trace) {
            lines.add(new Line(MoveStep.FALL, "Entity.fall(D,Z,BlockState,BlockPos)",
                    "dy=" + adjusted.y + " onGround=" + onGroundForFall + " @" + fmt(landingPos)
                            + " stateId=0x" + Integer.toHexString(System.identityHashCode(landingState))));
        }

        // ---- 7. 移除即提前返回（偏移 448）----
        cur = MoveStep.REMOVED_EARLY_RETURN;
        if (cb.isRemoved()) {
            if (trace) {
                lines.add(new Line(MoveStep.REMOVED_EARLY_RETURN, "Entity.isRemoved()", "true → 回放结束"));
            }
            cb.profilerEnd();                                          // 偏移 455–467：pop('rest')
            return finish(log, error);
        }
        if (trace) {
            lines.add(new Line(MoveStep.REMOVED_EARLY_RETURN, "Entity.isRemoved()", "false"));
        }

        // ---- 8. 水平速度清零（偏移 468–515）----
        cur = MoveStep.HORIZONTAL_VELOCITY_ZERO;
        if (flags.horizontalCollision()) {
            Vec3d v = cb.getVelocity();
            double vx = xBlocked ? 0.0 : v.x;
            double vz = zBlocked ? 0.0 : v.z;
            cb.setVelocity(vx, v.y, vz);
            if (trace) {
                lines.add(new Line(MoveStep.HORIZONTAL_VELOCITY_ZERO, "Entity.setVelocity(DDD)",
                        "(" + vx + "," + v.y + "," + vz + ")"));
            }
        }

        // ---- 9. onEntityLand（偏移 525–544）----
        cur = MoveStep.ENTITY_LAND;
        if (movement.y != adjusted.y) {
            cb.onEntityLand(landingState);
            if (trace) {
                lines.add(new Line(MoveStep.ENTITY_LAND, "Block.onEntityLand", "@" + fmt(landingPos)));
            }
        }

        // ---- 10. onSteppedOn（偏移 547–565）----
        cur = MoveStep.STEPPED_ON;
        if (cb.isOnGround()) {
            cb.onSteppedOn(landingState, landingPos);
            if (trace) {
                lines.add(new Line(MoveStep.STEPPED_ON, "Block.onSteppedOn", "@" + fmt(landingPos)));
            }
        }

        // ---- 11–14. 踩踏音效 / 游泳 / 空中效果（偏移 568–853）----
        cur = MoveStep.MOVE_EFFECT_BOOKKEEPING;
        if (pullB("moveEffectHasAny", in.moveEffectHasAny())        // 偏移 576
                && !pullB("hasVehicle", in.hasVehicle())) {         // 偏移 583
            // 偏移 626/636：steppingPos 与 steppingState 在这一支的**开头**解析一次，
            // 后面的 722（isAir）、744、770 全部复用同一引用。
            BlockPos steppingPos = pullP("steppingPos", in.steppingPos());   // 偏移 626
            S steppingState = cb.stateAt(steppingPos);                       // 偏移 636
            // 偏移 589–705：Java 内部记账（speed / horizontalSpeed / distanceTraveled / canClimb）。
            // 它在 708 的步声判定**之前**，所以必须在这里回调，而不是在分支外。
            cb.moveEffectBookkeeping(steppingPos, steppingState);
            // 偏移 708–717（字段比较）→ 720–725（isAir）；&& 的短路顺序就是原版的顺序。
            boolean stepSoundBranch = pullB("stepSoundDistanceExceeded", in.stepSoundDistanceExceeded())
                    && !cb.stateIsAir(steppingState);
            if (stepSoundBranch) {
                cur = MoveStep.STEP_ON_BLOCK_MAIN;
                boolean steppingEqualsLanding = pullB("steppingEqualsLanding",
                        in.steppingEqualsLanding());                     // 偏移 728–735
                boolean playsSounds = pullB("moveEffectPlaysSounds",
                        in.moveEffectPlaysSounds());                     // 偏移 742
                boolean played = cb.stepOnBlock(landingPos, landingState, playsSounds,
                        steppingEqualsLanding, movement);                // 偏移 749
                if (trace) {
                    lines.add(new Line(MoveStep.STEP_ON_BLOCK_MAIN, "Entity.stepOnBlock",
                            "@" + fmt(landingPos) + " playSounds=" + playsSounds
                                    + " emit=" + steppingEqualsLanding + " ->" + played));
                }
                if (!steppingEqualsLanding) {
                    cur = MoveStep.STEP_ON_BLOCK_SECOND;
                    boolean emitsGameEvents = pullB("moveEffectEmitsGameEvents",
                            in.moveEffectEmitsGameEvents());             // 偏移 768
                    played |= cb.stepOnBlock(steppingPos, steppingState, false, emitsGameEvents,
                            movement);                                       // 偏移 774
                    if (trace) {
                        lines.add(new Line(MoveStep.STEP_ON_BLOCK_SECOND, "Entity.stepOnBlock",
                                "@" + fmt(steppingPos) + " playSounds=false emit=" + emitsGameEvents
                                        + " ->" + played));
                    }
                }
                if (played) {
                    cb.refreshNextStepSoundDistance();                   // 偏移 785–790
                } else if (pullB("touchingWater", in.touchingWater())) {  // 偏移 796
                    cur = MoveStep.SWIM_EFFECTS;
                    cb.refreshNextStepSoundDistance();                    // 偏移 803–808
                    cb.onSwimEffects();                                   // 偏移 811–835
                    if (trace) {
                        lines.add(new Line(MoveStep.SWIM_EFFECTS, "playSwimSound/emitGameEvent(SWIM)", ""));
                    }
                }
            } else if (cb.stateIsAir(steppingState)) {                    // 偏移 841–845
                cur = MoveStep.AIR_TRAVEL_EFFECTS;
                cb.onAirTravelEffects();                                  // 偏移 850
                if (trace) {
                    lines.add(new Line(MoveStep.AIR_TRAVEL_EFFECTS, "Entity.addAirTravelEffects()", ""));
                }
            }
        }

        // ---- 15. 方块碰撞扫描（偏移 853 → checkBlockCollision）----
        cur = MoveStep.BLOCK_COLLISION;
        if (pullB("regionLoaded", in.regionLoaded())) {   // checkBlockCollision 偏移 67：setPosition 之后的盒子
            // 逐格枚举同样是**世界查询**：Java 侧按原版顺序（x 外层 → y 中层 → z 内层）枚举坐标，
            // 方块状态仍在下面的循环里**逐格**现取（对象身份 + 时序都与原版一致）。
            int supplied = in.supplyEventsAt(MoveStep.BLOCK_COLLISION, log);
            int consumedBlocks = 0;
            boolean stoppedEarly = false;
            for (int i : take(log, MoveEventKind.COLLIDING_BLOCK)) {
                consumedBlocks++;
                if (!cb.isAlive()) {                       // checkBlockCollision 偏移 127
                    if (trace) {
                        lines.add(new Line(MoveStep.BLOCK_COLLISION, "Entity.isAlive()", "false → return"));
                    }
                    stoppedEarly = true;
                    break;
                }
                BlockPos pos = pos(log, i);
                S state = cb.stateAt(pos);
                cb.onEntityCollision(state, pos);          // BlockState.onEntityCollision（偏移 167）
                cb.onBlockCollision(state);                // Entity.onBlockCollision（偏移 173）
                if (trace) {
                    lines.add(new Line(MoveStep.BLOCK_COLLISION,
                            "BlockState.onEntityCollision/Entity.onBlockCollision", "@" + fmt(pos)));
                }
            }
            if (stoppedEarly) {
                // isAlive() 为假就整段 return —— 剩下的格子原版也不会访问。把注入的剩余事件标成已消费，
                // 否则它们会以"未消费"的形式报出来（那是假警报：行为与原版一致）。
                take(log, MoveEventKind.COLLIDING_BLOCK);
            }
            if (supplied > consumedBlocks) {
                anomalies.add("BLOCK_COLLISION:supply 注入 " + supplied + " 条但只消费 " + consumedBlocks + " 条");
            }
        }

        // ---- 16. 速度乘子（偏移 857–878）----
        cur = MoveStep.VELOCITY_MULTIPLIER;
        float multiplier = pullF("velocityMultiplier", in.velocityMultiplier());   // 偏移 858
        cb.multiplyVelocity(multiplier);                                           // 偏移 875
        if (trace) {
            lines.add(new Line(MoveStep.VELOCITY_MULTIPLIER, "setVelocity(multiply(f,1,f))",
                    "f=" + multiplier));
        }

        // ---- 17. 火焰分支（偏移 881–982）----
        cur = MoveStep.FIRE_BOX;
        int suppliedFire = in.supplyEventsAt(MoveStep.FIRE_BOX, log);
        int[] fires = take(log, MoveEventKind.FIRE_IN_BOX);
        boolean firePresent = fires.length > 0;
        cb.onFireStep(firePresent);
        if (trace) {
            lines.add(new Line(MoveStep.FIRE_BOX, "onFireStep", "firePresent=" + firePresent));
        }
        if (suppliedFire > fires.length) {
            anomalies.add("FIRE_BOX:supply 注入 " + suppliedFire + " 条但只消费 " + fires.length + " 条");
        }
        cb.profilerEnd();                                                          // 偏移 982–994：pop('rest')

        return finish(log, error);
    }

    private Transcript finish(MoveEventLog log, String error) {
        List<String> unconsumed = new ArrayList<>();
        for (int i = 0; i < log.size(); i++) {
            if (i >= used.length || !used[i]) {
                unconsumed.add(log.kind(i) + "@" + log.x(i) + "," + log.y(i) + "," + log.z(i));
            }
        }
        return new Transcript(lines, axisClips, pulls, pullCount, unconsumed, anomalies, error);
    }

    /** 按日志顺序取出该种类的全部事件下标，并标记已消费（日志中途增长时自动扩容）。 */
    private int[] take(MoveEventLog log, MoveEventKind kind) {
        if (used.length < log.size()) {
            used = java.util.Arrays.copyOf(used, log.size());
        }
        int n = 0;
        for (int i = 0; i < log.size(); i++) {
            if (!used[i] && log.kind(i) == kind) {
                n++;
            }
        }
        int[] out = new int[n];
        int at = 0;
        for (int i = 0; i < log.size(); i++) {
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

    private static String fmt(double x, double y, double z) {
        return "(" + x + "," + y + "," + z + ")";
    }

    private static String fmt(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
