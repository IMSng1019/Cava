package cava.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * 事件回放骨架验证（三条关注点之一：<b>事件顺序</b>）。
 *
 * <p>全部用假"方块行为"实现 {@link MoveCallbacks}：不需要服务器、不需要 bootstrap MC，
 * 而且能逐条断言"调了哪些方法、按什么顺序、参数是什么、传进去的是不是同一个对象"。
 */
class EventReplayTest {

    /** 假方块状态对象：只需要"身份"，不需要注册表。 */
    record FakeState(String name) {
    }

    /** 假世界 + 记录型回调。 */
    static final class Fake implements MoveCallbacks<FakeState, String> {
        final List<String> calls = new ArrayList<>();
        final Map<BlockPos, FakeState> states = new HashMap<>();
        final Map<BlockPos, String> shapes = new HashMap<>();
        final Map<BlockPos, Integer> stateAtCount = new HashMap<>();

        /** 万一有人想用扁平 AABB 抄近路，就会走到这里 —— 本测试断言它永远是 0。 */
        int flatAabbCalls;

        boolean removed;
        boolean alive = true;
        boolean onGround;
        boolean stepOnBlockResult = true;
        Vec3d velocity = new Vec3d(1.0, -0.5, 2.0);
        MoveFlags lastFlags;
        double lastVelocityX = Double.NaN;
        double lastVelocityY = Double.NaN;
        double lastVelocityZ = Double.NaN;
        float lastMultiplier = Float.NaN;
        Boolean lastFirePresent;

        Fake state(BlockPos pos, String name) {
            states.put(pos, new FakeState(name));
            return this;
        }

        Fake shape(BlockPos pos, String name) {
            shapes.put(pos, name);
            return this;
        }

        private static String key(BlockPos pos) {
            return pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }

        @Override
        public FakeState stateAt(BlockPos pos) {
            calls.add("stateAt(" + key(pos) + ")");
            stateAtCount.merge(pos, 1, Integer::sum);
            // 假世界对没登记过的坐标也返回一个稳定对象（真实世界永远有方块状态）
            return states.computeIfAbsent(pos, p -> new FakeState("unknown@" + key(p)));
        }

        @Override
        public String collisionShapeAt(BlockPos pos) {
            calls.add("shapeAt(" + key(pos) + ")");
            return shapes.get(pos);
        }

        /** 扁平 AABB 近路的替身；回放路径不该碰它。 */
        String flatAabbAt(BlockPos pos) {
            flatAabbCalls++;
            return "aabb";
        }

        @Override
        public void onLanding() {
            calls.add("onLanding");
        }

        @Override
        public void setPosition(double x, double y, double z) {
            calls.add("setPosition(" + x + "," + y + "," + z + ")");
        }

        @Override
        public void setCollisionFlags(MoveFlags flags) {
            calls.add("setCollisionFlags");
            lastFlags = flags;
        }

        @Override
        public boolean hasCollidedSoftly(Vec3d adjusted) {
            calls.add("hasCollidedSoftly");
            return false;
        }

        @Override
        public void setOnGround(boolean ground, Vec3d adjusted) {
            calls.add("setOnGround(" + ground + ")");
            this.onGround = ground;
        }

        @Override
        public boolean isOnGround() {
            calls.add("isOnGround");
            return onGround;
        }

        @Override
        public void fall(double dy, boolean ground, FakeState state, BlockPos pos) {
            calls.add("fall(" + dy + "," + ground + "," + state.name() + ",@" + key(pos) + ")");
        }

        @Override
        public boolean isRemoved() {
            calls.add("isRemoved");
            return removed;
        }

        @Override
        public boolean isAlive() {
            calls.add("isAlive");
            return alive;
        }

        @Override
        public Vec3d getVelocity() {
            calls.add("getVelocity");
            return velocity;
        }

        @Override
        public void setVelocity(double x, double y, double z) {
            calls.add("setVelocity(" + x + "," + y + "," + z + ")");
            lastVelocityX = x;
            lastVelocityY = y;
            lastVelocityZ = z;
        }

        @Override
        public void onEntityLand(FakeState state) {
            calls.add("onEntityLand(" + state.name() + ")");
        }

        @Override
        public void onSteppedOn(FakeState state, BlockPos pos) {
            calls.add("onSteppedOn(" + state.name() + ",@" + key(pos) + ")");
        }

        @Override
        public boolean stepOnBlock(BlockPos pos, FakeState state, boolean playSounds, boolean emitGameEvents,
                Vec3d movement) {
            calls.add("stepOnBlock(@" + key(pos) + "," + state.name() + ",play=" + playSounds
                    + ",emit=" + emitGameEvents + ")");
            return stepOnBlockResult;
        }

        @Override
        public void onSwimEffects() {
            calls.add("onSwimEffects");
        }

        @Override
        public void onAirTravelEffects() {
            calls.add("onAirTravelEffects");
        }

        @Override
        public void onEntityCollision(FakeState state, BlockPos pos) {
            calls.add("onEntityCollision(" + state.name() + ",@" + key(pos) + ")");
        }

        @Override
        public void onBlockCollision(FakeState state) {
            calls.add("onBlockCollision(" + state.name() + ")");
        }

        @Override
        public void multiplyVelocity(float factor) {
            calls.add("multiplyVelocity(" + factor + ")");
            lastMultiplier = factor;
        }

        @Override
        public void onFireStep(boolean firePresent) {
            calls.add("onFireStep(" + firePresent + ")");
            lastFirePresent = firePresent;
        }
    }

    private static final BlockPos LANDING = new BlockPos(3, 64, 5);
    private static final BlockPos STEPPING = new BlockPos(3, 65, 5);
    private static final BlockPos CLIP = new BlockPos(2, 64, 5);
    private static final BlockPos COLLIDE_A = new BlockPos(3, 64, 6);
    private static final BlockPos COLLIDE_B = new BlockPos(3, 64, 7);

    /**
     * 标准场景：水平被挡 + 下落落地 + 两个碰撞格 + 火。
     *
     * <pre>
     *   movement = (1.0, -0.5, 0.0)   adjusted = (1.5, 0.0, 0.0)   fallDistance = 3.0
     *   => d = 2.25 (> 1e-7 且 >= 1.0)  => 走射线分支
     *   => xMask=true, zMask=false      => horizontalCollision=true
     *   => movement.y != adjusted.y     => verticalCollision=true, groundCollision=true
     * </pre>
     */
    private static MoveInputs standardInputs() {
        return new MoveInputs(new Vec3d(1.0, -0.5, 0.0), new Vec3d(1.5, 0.0, 0.0),
                10.0, 64.0, -2.0,
                LANDING, STEPPING, 3.0F, 0.6F,
                true, true, true, false, false,
                true, false, false, true);
    }

    private static MoveEventLog standardLog() {
        MoveEventLog log = new MoveEventLog(16);
        log.add(MoveEventKind.AXIS_CLIP, CLIP.getX(), CLIP.getY(), CLIP.getZ(), MoveEventKind.AXIS_X);
        log.add(MoveEventKind.LANDING_RAYCAST_HIT, LANDING.getX(), LANDING.getY(), LANDING.getZ(), 1);
        log.add(MoveEventKind.COLLIDING_BLOCK, COLLIDE_A.getX(), COLLIDE_A.getY(), COLLIDE_A.getZ(), 0);
        log.add(MoveEventKind.COLLIDING_BLOCK, COLLIDE_B.getX(), COLLIDE_B.getY(), COLLIDE_B.getZ(), 0);
        log.add(MoveEventKind.FIRE_IN_BOX, 0, 0, 0, 0);
        return log;
    }

    private static Fake standardFake() {
        return new Fake()
                .state(LANDING, "landing")
                .state(STEPPING, "stepping")
                .state(COLLIDE_A, "collideA")
                .state(COLLIDE_B, "collideB")
                .shape(CLIP, "clipShape");
    }

    /**
     * <b>顺序的定点用例</b>：整条调用序列写死。
     * 任何"回放顺序与原版不同"的改动都会在这里断掉。
     */
    @Test
    void replaysInExactVanillaOrder() {
        Fake fake = standardFake();
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(standardInputs(), standardLog());

        assertEquals(List.of(
                "shapeAt(2,64,5)",
                "onLanding",
                "setPosition(11.5,64.0,-2.0)",
                "hasCollidedSoftly",
                "setCollisionFlags",
                "setOnGround(true)",
                "stateAt(3,64,5)",
                "isOnGround",
                "fall(0.0,true,landing,@3,64,5)",
                "isRemoved",
                "getVelocity",
                "setVelocity(0.0,-0.5,2.0)",
                "onEntityLand(landing)",
                "isOnGround",
                "onSteppedOn(landing,@3,64,5)",
                "stateAt(3,65,5)",
                "stepOnBlock(@3,64,5,landing,play=true,emit=false)",
                "stepOnBlock(@3,65,5,stepping,play=false,emit=true)",
                "isAlive",
                "stateAt(3,64,6)",
                "onEntityCollision(collideA,@3,64,6)",
                "onBlockCollision(collideA)",
                "isAlive",
                "stateAt(3,64,7)",
                "onEntityCollision(collideB,@3,64,7)",
                "onBlockCollision(collideB)",
                "multiplyVelocity(0.6)",
                "onFireStep(true)"), fake.calls);

        assertTrue(t.strictOk(), t.report());
        assertTrue(fake.lastFlags.horizontalCollision());
        assertTrue(fake.lastFlags.verticalCollision());
        assertTrue(fake.lastFlags.groundCollision());
        assertEquals(0.0, fake.lastVelocityX, "x 轴被挡 -> 清零");
        assertEquals(-0.5, fake.lastVelocityY, "y 分量原样保留");
        assertEquals(2.0, fake.lastVelocityZ, "z 轴没被挡 -> 原样保留");
        assertEquals(0.6F, fake.lastMultiplier);

        // 流水里的步骤序也必须与 MoveStep 的声明序一致
        List<MoveStep> seen = t.lines().stream().map(EventReplay.Line::step).distinct().toList();
        for (int i = 1; i < seen.size(); i++) {
            assertTrue(seen.get(i).order() > seen.get(i - 1).order(),
                    "流水里的步骤必须严格递增: " + seen);
        }
    }

    /** <b>硬要求</b>：落点方块状态只解析一次，且同一个对象贯穿 fall / onEntityLand / onSteppedOn / stepOnBlock。 */
    @Test
    void resolvesLandingStateOnceAndReusesTheObject() {
        Fake fake = standardFake();
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(standardInputs(), standardLog());

        assertEquals(1, fake.stateAtCount.get(LANDING).intValue(),
                "落点状态只允许解析一次（原版偏移 422）");
        assertTrue(t.strictOk(), t.report());
        // 四个落点相关的调用都必须拿到同一个 landing 对象
        List<String> landingCalls = fake.calls.stream().filter(s -> s.contains("landing")).toList();
        assertEquals(List.of("fall(0.0,true,landing,@3,64,5)", "onEntityLand(landing)",
                "onSteppedOn(landing,@3,64,5)", "stepOnBlock(@3,64,5,landing,play=true,emit=false)"),
                landingCalls, "落点状态必须只解析一次并被四处复用");
    }

    /** <b>硬要求</b>：碰撞几何走原始形状对象，不碰扁平 AABB。 */
    @Test
    void axisClipBlocksResolveTheRawShapeAndNeverAFlatAabb() {
        Fake fake = standardFake();
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(standardInputs(), standardLog());

        assertEquals(1, t.axisClips().size());
        assertTrue(t.axisClips().get(0).startsWith("axis=0@(2,64,5) shapeId=0x"),
                "必须记下原始形状对象的身份: " + t.axisClips());
        assertTrue(fake.calls.contains("shapeAt(2,64,5)"), "原生报告的裁剪方块必须回世界取原始 VoxelShape");
        assertEquals(0, fake.flatAabbCalls, "回放路径绝不能退化成扁平 AABB");
    }

    /** 移除即提前返回（偏移 448）：后面的步骤一个都不能发生。 */
    @Test
    void removedEntityStopsReplayMidway() {
        Fake fake = standardFake();
        fake.removed = true;
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(standardInputs(), standardLog());

        assertTrue(fake.calls.contains("isRemoved"));
        assertFalse(fake.calls.contains("getVelocity"), "移除后不再清水平速度");
        assertFalse(fake.calls.contains("onEntityLand(landing)"));
        assertFalse(fake.calls.contains("onSteppedOn(landing,@3,64,5)"));
        assertFalse(fake.calls.stream().anyMatch(s -> s.startsWith("multiplyVelocity")));
        assertFalse(fake.calls.stream().anyMatch(s -> s.startsWith("onFireStep")));
        assertEquals(MoveStep.REMOVED_EARLY_RETURN, t.lines().get(t.lines().size() - 1).step());
    }

    /** checkBlockCollision 每格开头查 isAlive()，为假立刻整段返回（不取方块状态）。 */
    @Test
    void blockCollisionScanStopsWhenEntityIsNotAlive() {
        Fake fake = standardFake();
        fake.alive = false;
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        replay.replay(standardInputs(), standardLog());

        assertTrue(fake.calls.contains("isAlive"));
        assertFalse(fake.calls.contains("onEntityCollision(collideA,@3,64,6)"), "死了就不再逐格碰撞");
        assertFalse(fake.calls.contains("stateAt(3,64,6)"), "isAlive 在取方块状态之前");
    }

    /**
     * <b>顺序由 Java 侧决定</b>：把原生事件在日志里写反，回放顺序不变
     * （原生写错顺序不会造成行为差异，只会让"消费不掉"的计数变化）。
     */
    @Test
    void logOrderDoesNotAffectReplayOrder() {
        Fake fake = standardFake();
        MoveEventLog shuffled = new MoveEventLog(16);
        shuffled.add(MoveEventKind.COLLIDING_BLOCK, COLLIDE_B.getX(), COLLIDE_B.getY(), COLLIDE_B.getZ(), 0);
        shuffled.add(MoveEventKind.FIRE_IN_BOX, 0, 0, 0, 0);
        shuffled.add(MoveEventKind.COLLIDING_BLOCK, COLLIDE_A.getX(), COLLIDE_A.getY(), COLLIDE_A.getZ(), 0);
        shuffled.add(MoveEventKind.LANDING_RAYCAST_HIT, LANDING.getX(), LANDING.getY(), LANDING.getZ(), 1);
        shuffled.add(MoveEventKind.AXIS_CLIP, CLIP.getX(), CLIP.getY(), CLIP.getZ(), MoveEventKind.AXIS_X);

        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(standardInputs(), shuffled);

        int onLanding = fake.calls.indexOf("onLanding");
        int firstCollision = indexOfPrefix(fake.calls, "onEntityCollision");
        assertTrue(onLanding >= 0 && firstCollision > onLanding, "onLanding 必须早于方块碰撞扫描");
        assertEquals(List.of("onEntityCollision(collideB,@3,64,7)", "onBlockCollision(collideB)",
                "onEntityCollision(collideA,@3,64,6)", "onBlockCollision(collideA)"),
                fake.calls.stream().filter(s -> s.startsWith("onEntityCollision") || s.startsWith("onBlockCollision"))
                        .toList(),
                "同一类事件内部按日志顺序消费");
        assertTrue(t.strictOk(), t.report());
    }

    /** 该发事件却没发：记进 anomalies 而不是静默。 */
    @Test
    void missingRaycastEventIsReportedWhenTheGuardHolds() {
        Fake fake = standardFake();
        MoveEventLog log = new MoveEventLog(16);
        log.add(MoveEventKind.FIRE_IN_BOX, 0, 0, 0, 0);
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(standardInputs(), log);

        assertEquals(1, t.anomalies().size(), t.anomalies().toString());
        assertTrue(t.anomalies().get(0).startsWith("MISSING:LANDING_RAYCAST_HIT"));
        assertFalse(t.strictOk());
        assertFalse(fake.calls.contains("onLanding"));
    }

    /** 多出来的事件（守卫不成立时原生仍发了射线命中）：必须被报出来，不能静默丢。 */
    @Test
    void unconsumedEventsAreReported() {
        Fake fake = standardFake();
        MoveEventLog log = standardLog();
        // fallDistance = 0 -> 射线分支整段不执行 -> 那条事件没人消费
        MoveInputs noFall = new MoveInputs(standardInputs().movement(), standardInputs().adjusted(),
                10.0, 64.0, -2.0, LANDING, STEPPING, 0.0F, 0.6F,
                true, true, true, false, false, true, false, false, true);
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(noFall, log);

        assertFalse(t.unconsumed().isEmpty(), "未消费的事件必须显式报出来");
        assertTrue(t.unconsumed().get(0).startsWith("LANDING_RAYCAST_HIT@"));
        assertFalse(t.strictOk());
    }

    /** 事件数组溢出 = fail-closed（少一个事件就是少调一次虚方法）。 */
    @Test
    void overflowedEventLogIsAnError() {
        Fake fake = standardFake();
        MoveEventLog tiny = new MoveEventLog(2);
        tiny.add(MoveEventKind.COLLIDING_BLOCK, 1, 1, 1, 0);
        tiny.add(MoveEventKind.COLLIDING_BLOCK, 2, 1, 1, 0);
        tiny.add(MoveEventKind.COLLIDING_BLOCK, 3, 1, 1, 0); // 溢出
        assertTrue(tiny.overflowed());

        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(standardInputs(), tiny);
        assertNotNull(t.error());
        assertTrue(t.error().contains("溢出"), t.error());
        assertFalse(t.strictOk());
    }

    /** 区块未加载时原版不扫方块碰撞；镜像事件只能说"没回放"，不能当成错误吞掉。 */
    @Test
    void regionNotLoadedSkipsTheBlockCollisionScanButReportsLeftovers() {
        Fake fake = standardFake();
        MoveInputs in = new MoveInputs(standardInputs().movement(), standardInputs().adjusted(),
                10.0, 64.0, -2.0, LANDING, STEPPING, 3.0F, 0.6F,
                true, true, true, false, false, true, false, false, false);
        EventReplay<FakeState, String> replay = new EventReplay<>(fake);
        EventReplay.Transcript t = replay.replay(in, standardLog());

        assertFalse(fake.calls.stream().anyMatch(s -> s.startsWith("onEntityCollision")));
        assertEquals(2, t.unconsumed().stream().filter(s -> s.startsWith("COLLIDING_BLOCK")).count());
    }

    /** 踩踏音效返回 false 且在水里 -> 走游泳分支；踩踏点在空中 -> 走空中效果分支。 */
    @Test
    void swimAndAirTravelBranches() {
        Fake swim = standardFake();
        swim.stepOnBlockResult = false;
        MoveInputs wet = new MoveInputs(standardInputs().movement(), standardInputs().adjusted(),
                10.0, 64.0, -2.0, LANDING, STEPPING, 3.0F, 0.6F,
                true, true, true, false, true, true, false, false, true);
        new EventReplay<>(swim).replay(wet, standardLog());
        assertTrue(swim.calls.contains("onSwimEffects"), swim.calls.toString());
        assertFalse(swim.calls.contains("onAirTravelEffects"));

        Fake air = standardFake();
        MoveInputs airborne = new MoveInputs(standardInputs().movement(), standardInputs().adjusted(),
                10.0, 64.0, -2.0, LANDING, STEPPING, 3.0F, 0.6F,
                true, true, true, false, false, false, true, false, true);
        new EventReplay<>(air).replay(airborne, standardLog());
        assertTrue(air.calls.contains("onAirTravelEffects"), air.calls.toString());
        assertFalse(air.calls.contains("onSwimEffects"));
        assertFalse(air.calls.stream().anyMatch(s -> s.startsWith("stepOnBlock")));
    }

    /** 载具上的实体不做踩踏音效（偏移 583）。 */
    @Test
    void vehicleSuppressesStepSounds() {
        Fake fake = standardFake();
        MoveInputs riding = new MoveInputs(standardInputs().movement(), standardInputs().adjusted(),
                10.0, 64.0, -2.0, LANDING, STEPPING, 3.0F, 0.6F,
                true, true, true, true, false, true, false, false, true);
        new EventReplay<>(fake).replay(riding, standardLog());
        assertFalse(fake.calls.stream().anyMatch(s -> s.startsWith("stepOnBlock")));
        assertFalse(fake.calls.contains("onSwimEffects"));
        assertFalse(fake.calls.contains("onAirTravelEffects"));
    }

    /** 完全不动（d <= 1e-7）：不设位置，但标志位 / 落地 / 火焰照旧。 */
    @Test
    void zeroDisplacementSkipsSetPositionButKeepsTheRest() {
        Fake fake = standardFake();
        MoveInputs still = new MoveInputs(new Vec3d(0.0, 0.0, 0.0), new Vec3d(0.0, 0.0, 0.0),
                10.0, 64.0, -2.0, LANDING, STEPPING, 3.0F, 0.6F,
                false, false, false, false, false, false, true, true, true);
        MoveEventLog log = new MoveEventLog(4);
        log.add(MoveEventKind.FIRE_IN_BOX, 0, 0, 0, 0);
        EventReplay.Transcript t = new EventReplay<>(fake).replay(still, log);

        assertFalse(fake.calls.stream().anyMatch(s -> s.startsWith("setPosition")));
        assertTrue(fake.calls.contains("setCollisionFlags"));
        assertTrue(fake.calls.contains("fall(0.0,false,landing,@3,64,5)"),
                "movement.y == adjusted.y -> verticalCollision=false -> onGround=false: " + fake.calls);
        assertTrue(fake.calls.contains("onFireStep(true)"));
        assertTrue(t.strictOk(), t.report());
        assertNull(t.error());
    }

    private static int indexOfPrefix(List<String> list, String prefix) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
