package cava.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * <b>拉取式重构的定点用例</b>：证明每个输入是在<b>它自己那一"步"</b>被拉取的，
 * 而不是提前打包。
 *
 * <p>三条关注点：
 * <ol>
 *   <li>拉取序列 == 原版字节码偏移序（写死，见 {@link MoveStep} 的偏移注释）；</li>
 *   <li>四处"只有回放中途才成立"的输入，拉取时前面那一步<b>真的已经执行过</b>
 *       （{@code setOnGround} → {@code landingPos}；{@code setPosition} → {@code regionLoaded}；
 *       记账 → {@code stepSoundDistanceExceeded}）；</li>
 *   <li>只在原版会读它的分支里拉取（{@code steppingPos} 只在 moveEffect 分支内）。</li>
 * </ol>
 *
 * <p>用例复用 {@link EventReplayTest} 的假回调与定值输入（同包，
 * 不需要服务器、不需要 bootstrap MC）。
 */
class MoveInputPullOrderTest {

    /** 包装 {@link MoveInputs}，并在每个拉取点记录"那一刻前面已经发生过什么"。 */
    static final class Tracing implements MoveInputSource {
        final MoveInputs base;
        final EventReplayTest.Fake fake;
        final List<MoveStep> landingPullSteps = new ArrayList<>();

        boolean landingSeenAfterSetOnGround;
        boolean regionSeenAfterSetPosition;
        boolean stepSoundSeenAfterBookkeeping;

        Tracing(MoveInputs base, EventReplayTest.Fake fake) {
            this.base = base;
            this.fake = fake;
        }

        private boolean happened(String prefix) {
            return fake.calls.stream().anyMatch(s -> s.startsWith(prefix));
        }

        @Override
        public BlockPos landingPos() {
            landingSeenAfterSetOnGround = happened("setOnGround(");
            return base.landingPos();
        }

        @Override
        public boolean regionLoaded() {
            regionSeenAfterSetPosition = happened("setPosition(");
            return base.regionLoaded();
        }

        @Override
        public boolean stepSoundDistanceExceeded() {
            stepSoundSeenAfterBookkeeping = happened("bookkeeping(");
            return base.stepSoundDistanceExceeded();
        }

        @Override public Vec3d movement() { return base.movement(); }
        @Override public Vec3d adjusted() { return base.adjusted(); }
        @Override public double posX() { return base.posX(); }
        @Override public double posY() { return base.posY(); }
        @Override public double posZ() { return base.posZ(); }
        @Override public float fallDistance() { return base.fallDistance(); }
        @Override public BlockPos steppingPos() { return base.steppingPos(); }
        @Override public boolean moveEffectHasAny() { return base.moveEffectHasAny(); }
        @Override public boolean hasVehicle() { return base.hasVehicle(); }
        @Override public boolean steppingEqualsLanding() { return base.steppingEqualsLanding(); }
        @Override public boolean moveEffectPlaysSounds() { return base.moveEffectPlaysSounds(); }
        @Override public boolean moveEffectEmitsGameEvents() { return base.moveEffectEmitsGameEvents(); }
        @Override public boolean touchingWater() { return base.touchingWater(); }
        @Override public float velocityMultiplier() { return base.velocityMultiplier(); }
    }

    private static EventReplay.Transcript run(EventReplayTest.Fake fake, MoveInputSource src,
            MoveEventLog log) {
        return new EventReplay<EventReplayTest.FakeState, String>(fake).replay(src, log);
    }

    /**
     * <b>拉取序列的定点用例</b>：整条序列写死（名字 + 它归属的 {@link MoveStep}）。
     * 任何"提前打包"或"顺序错位"的改动都会在这里断掉。
     */
    @Test
    void pullOrderIsTheVanillaOffsetOrder() {
        EventReplayTest.Fake fake = EventReplayTest.standardFake();
        Tracing src = new Tracing(EventReplayTest.standardInputs(), fake);
        EventReplay.Transcript t = run(fake, src, EventReplayTest.standardLog());

        assertEquals(List.of(
                "COLLISION_SOLVE:movement",                       // 偏移 129
                "COLLISION_SOLVE:adjusted",                       // 偏移 135
                "LANDING_RAYCAST:fallDistance",                   // 偏移 152
                "SET_POSITION:posX",                              // 偏移 219
                "SET_POSITION:posY",                              // 偏移 228
                "SET_POSITION:posZ",                              // 偏移 237
                "FALL:landingPos",                                // 偏移 416（在 412 之后）
                "MOVE_EFFECT_BOOKKEEPING:moveEffectHasAny",       // 偏移 576
                "MOVE_EFFECT_BOOKKEEPING:hasVehicle",             // 偏移 583
                "MOVE_EFFECT_BOOKKEEPING:steppingPos",            // 偏移 626
                "MOVE_EFFECT_BOOKKEEPING:stepSoundDistanceExceeded", // 偏移 708–717（在 675–705 之后）
                "STEP_ON_BLOCK_MAIN:steppingEqualsLanding",       // 偏移 728–735
                "STEP_ON_BLOCK_MAIN:moveEffectPlaysSounds",       // 偏移 742
                "STEP_ON_BLOCK_SECOND:moveEffectEmitsGameEvents", // 偏移 768
                "BLOCK_COLLISION:regionLoaded",                   // checkBlockCollision 偏移 67
                "VELOCITY_MULTIPLIER:velocityMultiplier"),        // 偏移 858
                t.pullTexts());

        // 拉取的步骤序必须单调不减（顺序唯一事实来源 = MoveStep.ordinal()）
        List<EventReplay.Pull> pulls = t.pulls();
        for (int i = 1; i < pulls.size(); i++) {
            assertTrue(pulls.get(i).step().order() >= pulls.get(i - 1).step().order(),
                    "拉取顺序必须随 MoveStep 单调不减: " + t.pullTexts());
        }
        assertTrue(t.strictOk(), t.report());
    }

    /**
     * <b>核心时序证据 #1</b>：{@code landingPos} 是在 {@code setOnGround}（412）<b>之后</b>拉的。
     * 预打包模型做不到这一点（那时 {@code supportingBlockPos} 还是旧的）。
     */
    @Test
    void landingPosIsPulledAfterSetOnGround() {
        EventReplayTest.Fake fake = EventReplayTest.standardFake();
        Tracing src = new Tracing(EventReplayTest.standardInputs(), fake);
        run(fake, src, EventReplayTest.standardLog());

        assertTrue(src.landingSeenAfterSetOnGround,
                "landingPos 必须在 setOnGround 之后拉（那一步会改写 supportingBlockPos）");
    }

    /**
     * <b>核心时序证据 #2</b>：{@code regionLoaded} 是在 {@code setPosition}（218）<b>之后</b>拉的
     * —— {@code checkBlockCollision} 扫的是移动之后的碰撞箱。
     */
    @Test
    void regionLoadedIsPulledAfterSetPosition() {
        EventReplayTest.Fake fake = EventReplayTest.standardFake();
        Tracing src = new Tracing(EventReplayTest.standardInputs(), fake);
        run(fake, src, EventReplayTest.standardLog());

        assertTrue(src.regionSeenAfterSetPosition,
                "isRegionLoaded 必须在 setPosition 之后拉（扫的是移动后的盒子）");
    }

    /**
     * <b>核心时序证据 #3</b>：{@code distanceTraveled > nextStepSoundDistance} 是在记账
     * （589–705，写 {@code distanceTraveled}）<b>之后</b>拉的 —— 否则读到的是上一 tick 的值。
     */
    @Test
    void stepSoundIsPulledAfterBookkeeping() {
        EventReplayTest.Fake fake = EventReplayTest.standardFake();
        Tracing src = new Tracing(EventReplayTest.standardInputs(), fake);
        run(fake, src, EventReplayTest.standardLog());

        assertTrue(src.stepSoundSeenAfterBookkeeping,
                "stepSoundDistanceExceeded 必须在 moveEffectBookkeeping 之后拉");
    }

    /** {@code steppingPos}（626）只在 {@code moveEffect} 分支内拉 —— 分支不进就不拉。 */
    @Test
    void steppingPosIsOnlyPulledInsideTheMoveEffectBranch() {
        // (a) hasVehicle = true -> 整支跳过
        EventReplayTest.Fake riding = EventReplayTest.standardFake();
        MoveInputs ridingIn = new MoveInputs(EventReplayTest.standardInputs().movement(),
                EventReplayTest.standardInputs().adjusted(), 10.0, 64.0, -2.0,
                EventReplayTest.LANDING, EventReplayTest.STEPPING, 3.0F, 0.6F,
                true, true, true, true, false, true, false, true);
        EventReplay.Transcript t1 = run(riding, new Tracing(ridingIn, riding), EventReplayTest.standardLog());
        assertFalse(t1.pullTexts().contains("MOVE_EFFECT_BOOKKEEPING:steppingPos"),
                "载具上不解析 steppingPos: " + t1.pullTexts());
        assertFalse(t1.pullTexts().contains("MOVE_EFFECT_BOOKKEEPING:stepSoundDistanceExceeded"));
        assertTrue(t1.strictOk(), t1.report());

        // (b) moveEffectHasAny = false -> 连 hasVehicle 都不问（&& 短路）
        EventReplayTest.Fake still = EventReplayTest.standardFake();
        MoveInputs stillIn = new MoveInputs(new Vec3d(0.0, 0.0, 0.0), new Vec3d(0.0, 0.0, 0.0),
                10.0, 64.0, -2.0, EventReplayTest.LANDING, EventReplayTest.STEPPING, 3.0F, 0.6F,
                false, false, false, false, false, false, true, true);
        EventReplay.Transcript t2 = run(still, new Tracing(stillIn, still), EventReplayTest.standardLog());
        assertFalse(t2.pullTexts().contains("MOVE_EFFECT_BOOKKEEPING:hasVehicle"),
                "hasAny 为假时 hasVehicle 不该被问（原版 && 短路）: " + t2.pullTexts());
        assertFalse(t2.pullTexts().contains("MOVE_EFFECT_BOOKKEEPING:steppingPos"));
    }

    /** 完全不动（d <= 1e-7）：{@code fallDistance} / 位置 / landingPos 一次都不该拉。 */
    @Test
    void zeroDisplacementPullsNothingFromTheMovementBlock() {
        EventReplayTest.Fake fake = EventReplayTest.standardFake();
        MoveInputs still = new MoveInputs(new Vec3d(0.0, 0.0, 0.0), new Vec3d(0.0, 0.0, 0.0),
                10.0, 64.0, -2.0, EventReplayTest.LANDING, EventReplayTest.STEPPING, 3.0F, 0.6F,
                false, false, false, false, false, false, true, true);
        EventReplay.Transcript t = run(fake, new Tracing(still, fake), EventReplayTest.standardLog());

        assertFalse(t.pullTexts().contains("LANDING_RAYCAST:fallDistance"), t.pullTexts().toString());
        assertFalse(t.pullTexts().contains("SET_POSITION:posX"), t.pullTexts().toString());
        assertTrue(t.pullTexts().contains("FALL:landingPos"),
                "位置没动也要落点（原版就是这么走的）: " + t.pullTexts());
    }
}
