package cava.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * <b>从字节码推出的真值的定点用例</b>。
 *
 * <p>P1 的教训："测试通过"不等于"理解正确"。随机向量是参照实现自己产的，
 * 参照实现错了就一起错。所以每一个"容易读反"的分支都必须有一个
 * <b>只能由真值满足</b>的写死断言。本类里的每个期望值都来自本流自己跑的
 * {@code javap -p -c net.minecraft.entity.Entity / Vec3d / Box / MathHelper}，
 * 数值在注释里给出字节码出处。
 *
 * <p>本类只碰 {@code net.minecraft.util.math} 下的纯数学类，<b>不需要 bootstrap MC</b>。
 */
class MoveBytecodeTruthTest {

    /**
     * {@code MathHelper.approximatelyEquals(DD)Z} 的阈值。
     *
     * <p>javap（{@code MathHelper.txt:368}）：
     * <pre>
     *   0: dload_2 ; 1: dload_0 ; 2: dsub ; 3: Math.abs(D)D
     *   6: ldc2_w #166  // double 9.999999747378752E-6d
     *   9: dcmpg ; 10: ifge 17 ; 13: iconst_1 ...
     * </pre>
     * 也就是 {@code Math.abs(b - a) < (double)1.0E-5f} —— <b>不是 {@code 1.0E-7}</b>。
     * 照记忆写成 1e-7 会让 {@code horizontalCollision} 在 1e-7..1e-5 的缝隙里与现服务器不同。
     */
    @Test
    void approximatelyEqualsThresholdIsOneE5fNotOneE7() {
        double threshold = 9.999999747378752E-6;

        // 阈值本身的定点：恰好等于阈值 -> false（严格小于）
        assertFalse(MathHelper.approximatelyEquals(0.0, threshold),
                "abs(b-a) == 阈值 时必须 false（字节码是 ifge -> 返回 false）");
        assertTrue(MathHelper.approximatelyEquals(0.0, Math.nextDown(threshold)),
                "比阈值小一个 ulp 必须 true");

        // 只有 1e-5 阈值能通过的取值：写成 1e-7 的实现会在这里返回 false
        assertTrue(MathHelper.approximatelyEquals(0.0, 5.0E-6),
                "5e-6 必须在阈值内 —— 这一条专门用来打死『1e-7』这个错误记忆");
        assertTrue(MathHelper.approximatelyEquals(1.0, 1.0 + 9.0E-6));
        assertFalse(MathHelper.approximatelyEquals(1.0, 1.0 + 1.1E-5));
        // 1e-7 附近的取值：两种阈值都给 true，不能用来区分
        assertTrue(MathHelper.approximatelyEquals(0.0, 1.0E-7));
    }

    /**
     * {@code Vec3d.equals} 用 {@code Double.compare} 逐分量比较（{@code Vec3d.txt:627}），
     * 所以 <b>{@code -0.0} 不等于 {@code 0.0}</b>。
     * VMP 的零位移短路正是靠 {@code movement.equals(Vec3d.ZERO)} 判定，
     * 复刻时图省事写 {@code movement.x == 0.0} 就会在负零上多发一次短路（多取消一次 move）。
     */
    @Test
    void vec3dZeroDoesNotEqualNegativeZero() {
        assertTrue(Vec3d.ZERO.equals(new Vec3d(0.0, 0.0, 0.0)));
        assertFalse(Vec3d.ZERO.equals(new Vec3d(-0.0, 0.0, 0.0)), "-0.0 != 0.0（Double.compare）");
        assertFalse(Vec3d.ZERO.equals(new Vec3d(0.0, -0.0, 0.0)));
        assertFalse(Vec3d.ZERO.equals(new Vec3d(0.0, 0.0, -0.0)));
        assertTrue(-0.0 == 0.0, "对照组：Java 的 == 认为它们相等 —— 所以不能用 ==");
    }

    /** {@code Box.equals} 同样是 {@code Double.compare}（{@code Box.txt:338}），六个分量逐一比。 */
    @Test
    void boxEqualsAlsoUsesDoubleCompare() {
        Box a = new Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        Box same = new Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        Box negativeZero = new Box(-0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        Box bigger = new Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0 + 1.0E-12);

        assertTrue(a.equals(same));
        assertFalse(a.equals(negativeZero), "-0.0 与 0.0 在 Box.equals 里不相等");
        assertFalse(a.equals(bigger), "1e-12 的差也算『变了』—— 标志会被置位");
    }

    /**
     * {@link MoveStep} 的顺序 = 原版 {@code Entity.move} 的执行顺序。
     * 断言两条：偏移严格递增；关键字面量与 javap 输出一致。
     */
    @Test
    void moveStepOrderMatchesBytecodeOffsets() {
        MoveStep[] steps = MoveStep.values();
        List<String> bad = new ArrayList<>();
        for (int i = 1; i < steps.length; i++) {
            if (steps[i].bytecodeOffset() <= steps[i - 1].bytecodeOffset()) {
                bad.add(steps[i - 1] + "(" + steps[i - 1].bytecodeOffset() + ") >= "
                        + steps[i] + "(" + steps[i].bytecodeOffset() + ")");
            }
        }
        assertEquals(List.of(), bad, "MoveStep 的声明顺序必须与字节码偏移升序一致");

        // 写死的字面量（javap Entity.txt 的偏移列，见 docs/CAVA-p2-java-notes.md 第 2 节）。
        // 括号里是该步骤真正那条 invokevirtual 的偏移，用于人工复核步骤边界。
        assertEquals(0, MoveStep.VMP_ZERO_VELOCITY.bytecodeOffset());
        assertEquals(132, MoveStep.COLLISION_SOLVE.bytecodeOffset(), "adjustMovementForCollisions 在 132");
        assertEquals(197, MoveStep.LANDING_RAYCAST.bytecodeOffset(), "World.raycast 在 197，onLanding 在 214");
        assertEquals(218, MoveStep.SET_POSITION.bytecodeOffset(), "setPosition 在 245");
        assertEquals(283, MoveStep.SET_COLLISION_FLAGS.bytecodeOffset(), "hasCollidedSoftly 在 391");
        assertEquals(412, MoveStep.SET_ON_GROUND.bytecodeOffset());
        assertEquals(416, MoveStep.FALL.bytecodeOffset(), "getBlockState 在 422，fall 在 445");
        assertEquals(448, MoveStep.REMOVED_EARLY_RETURN.bytecodeOffset());
        assertEquals(468, MoveStep.HORIZONTAL_VELOCITY_ZERO.bytecodeOffset(), "setVelocity 在 515");
        assertEquals(525, MoveStep.ENTITY_LAND.bytecodeOffset(), "onEntityLand 在 544");
        assertEquals(547, MoveStep.STEPPED_ON.bytecodeOffset(), "onSteppedOn 在 565");
        assertEquals(568, MoveStep.MOVE_EFFECT_BOOKKEEPING.bytecodeOffset(),
                "getMoveEffect 守卫在 568；getSteppingPos 在 626，distanceTraveled 记账在 705");
        assertEquals(737, MoveStep.STEP_ON_BLOCK_MAIN.bytecodeOffset(), "stepOnBlock 在 749");
        assertEquals(755, MoveStep.STEP_ON_BLOCK_SECOND.bytecodeOffset(), "stepOnBlock 在 774");
        assertEquals(796, MoveStep.SWIM_EFFECTS.bytecodeOffset(), "playSwimSound 在 819");
        assertEquals(841, MoveStep.AIR_TRAVEL_EFFECTS.bytecodeOffset(), "addAirTravelEffects 在 850");
        assertEquals(853, MoveStep.BLOCK_COLLISION.bytecodeOffset(), "tryCheckBlockCollision 在 854");
        assertEquals(857, MoveStep.VELOCITY_MULTIPLIER.bytecodeOffset(), "setVelocity 在 875");
        assertEquals(881, MoveStep.FIRE_BOX.bytecodeOffset(), "getStatesInBoxIfLoaded 在 895");
    }

    /**
     * <b>三个碰撞标志里只有 x/z 用近似比较</b>：{@code verticalCollision} 是裸 {@code !=}
     * （字节码 345: {@code dcmpl; ifeq}）。
     *
     * <p>这条差异只能用"小于近似的阈值、但不等于"的量区分：
     * y 差 1e-9 → verticalCollision <b>为真</b>；x 差 1e-9 → horizontalCollision <b>为假</b>。
     */
    @Test
    void verticalCollisionUsesPlainNotEqualsWhileHorizontalUsesApproximate() {
        Vec3d movement = new Vec3d(1.0, 1.0, 1.0);
        Vec3d adjusted = new Vec3d(1.0 + 1.0E-9, 1.0 + 1.0E-9, 1.0 + 1.0E-9);
        MoveFlags flags = MoveFlags.compute(movement, adjusted, false);

        assertFalse(flags.horizontalCollision(), "x/z 用 approximatelyEquals：1e-9 视为没被挡");
        assertTrue(flags.verticalCollision(), "y 用 !=：1e-9 也算被挡");
        assertFalse(flags.groundCollision(), "groundCollision 还要 movement.y < 0，这里 movement.y=1.0");

        // groundCollision 需要 movement.y < 0
        MoveFlags falling = MoveFlags.compute(new Vec3d(0.0, -0.5, 0.0), new Vec3d(0.0, 0.0, 0.0), false);
        assertTrue(falling.verticalCollision());
        assertTrue(falling.groundCollision());
        MoveFlags rising = MoveFlags.compute(new Vec3d(0.0, 0.5, 0.0), new Vec3d(0.0, 0.0, 0.0), false);
        assertTrue(rising.verticalCollision());
        assertFalse(rising.groundCollision(), "向上被挡不算 land");
    }

    /** {@link MoveFlags#toBits()} 必须与 {@link EntityFlags} 的位一一对应（唯一定义处）。 */
    @Test
    void moveFlagsToBitsMatchesEntityFlags() {
        MoveFlags all = new MoveFlags(true, true, true, true);
        assertEquals(EntityFlags.HORIZONTAL_COLLISION | EntityFlags.VERTICAL_COLLISION
                | EntityFlags.GROUND_COLLISION | EntityFlags.COLLIDED_SOFTLY, all.toBits());
        assertEquals(0, new MoveFlags(false, false, false, false).toBits());
    }

    /** 位布局本身的哨兵：位号一旦被重排，镜像与原生（将来）就会全错位。 */
    @Test
    void entityFlagBitsAreFrozen() {
        assertEquals(1, EntityFlags.ON_GROUND);
        assertEquals(1 << 1, EntityFlags.HORIZONTAL_COLLISION);
        assertEquals(1 << 2, EntityFlags.VERTICAL_COLLISION);
        assertEquals(1 << 3, EntityFlags.GROUND_COLLISION);
        assertEquals(1 << 4, EntityFlags.COLLIDED_SOFTLY);
        assertEquals(1 << 10, EntityFlags.SERVERCORE_INACTIVE);
        assertEquals(11, EntityFlags.BIT_COUNT);
        for (int i = 0; i < EntityFlags.BIT_COUNT; i++) {
            assertEquals(EntityFlags.bit(EntityFlags.nameAt(i)), EntityFlags.bitAt(i),
                    "名字 -> 位 与 位号 -> 位 必须一致");
        }
    }

    /** 事件种类的归属步骤必须与 {@link MoveStep} 的定义一致（顺序不在事件里重复定义）。 */
    @Test
    void eventKindsReferenceTheirSteps() {
        assertEquals(MoveStep.LANDING_RAYCAST, MoveEventKind.LANDING_RAYCAST_HIT.step());
        assertEquals(MoveStep.BLOCK_COLLISION, MoveEventKind.COLLIDING_BLOCK.step());
        assertEquals(MoveStep.FIRE_BOX, MoveEventKind.FIRE_IN_BOX.step());
        assertEquals(MoveStep.COLLISION_SOLVE, MoveEventKind.AXIS_CLIP.step());
    }
}
