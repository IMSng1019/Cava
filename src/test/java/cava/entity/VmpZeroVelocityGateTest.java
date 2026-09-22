package cava.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/**
 * VMP 零位移短路的复刻验证。
 *
 * <p>三条关注点之一（另两条：事件顺序 / inactive 跳过）。
 * 语义证据 = {@code .research/} 里 VMP 1.20.4 的 {@code MixinEntity.java} 原文
 * + {@code cava.compat.VmpAdapter} 对本机 jar 的实测。
 */
class VmpZeroVelocityGateTest {

    private static Box box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Box(x1, y1, z1, x2, y2, z2);
    }

    @Test
    void freshGateCancelsExactlyZeroMovement() {
        VmpZeroVelocityGate gate = new VmpZeroVelocityGate();
        assertTrue(gate.onMoveHead(Vec3d.ZERO), "新实体的第一次零位移必须被取消");
        assertFalse(gate.boundingBoxChanged(), "cancel 分支里会复位（本来就是 false）");
        assertTrue(gate.onMoveHead(Vec3d.ZERO), "复位之后再问还是 true");
        assertFalse(gate.onMoveHead(new Vec3d(0.0, -0.0784, 0.0)), "非零位移不取消");
        assertTrue(gate.onMoveHead(Vec3d.ZERO), "非零位移不会改变标志");
    }

    /**
     * <b>黏滞语义</b>（本任务书点名的坑）：{@code boundingBoxChanged} 只在 cancel 分支复位，
     * 而 cancel 的前提是它已经是 false ⇒ 包围盒真变过一次之后，<b>该实体永久失去零位移短路</b>。
     */
    @Test
    void boundingBoxChangeDisablesShortcutForever() {
        VmpZeroVelocityGate gate = new VmpZeroVelocityGate();
        assertTrue(gate.onMoveHead(Vec3d.ZERO));

        gate.onSetBoundingBoxHeadBeforeAssign(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 2, 1, 1));
        assertTrue(gate.boundingBoxChanged(), "旧盒 != 新盒 -> 置位");

        for (int i = 0; i < 1000; i++) {
            assertFalse(gate.onMoveHead(Vec3d.ZERO),
                    "第 " + i + " 次零位移仍必须 false：复位只在 cancel 分支里，而 cancel 要求它已是 false");
        }
        assertTrue(gate.boundingBoxChanged(), "没有任何路径能复位它");
    }

    /** 包围盒"没变"（{@code Box.equals} 为真）不置位；差 1e-12 也算变。 */
    @Test
    void onlyARealBoundingBoxChangeSetsTheFlag() {
        VmpZeroVelocityGate equal = new VmpZeroVelocityGate();
        equal.onSetBoundingBoxHeadBeforeAssign(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 1, 1, 1));
        assertFalse(equal.boundingBoxChanged());
        assertTrue(equal.onMoveHead(Vec3d.ZERO));

        VmpZeroVelocityGate tiny = new VmpZeroVelocityGate();
        tiny.onSetBoundingBoxHeadBeforeAssign(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 1, 1, 1 + 1.0E-12));
        assertTrue(tiny.boundingBoxChanged(), "Box.equals 用 Double.compare，1e-12 也算变");
        assertFalse(tiny.onMoveHead(Vec3d.ZERO));
    }

    /**
     * <b>{@code -0.0} 不是零位移</b>：{@code Vec3d.equals} 用 {@code Double.compare}
     * （见 {@code MoveBytecodeTruthTest}）。写成 {@code x == 0.0} 会在负零上多取消一次。
     */
    @Test
    void negativeZeroIsNotZeroMovement() {
        VmpZeroVelocityGate gate = new VmpZeroVelocityGate();
        assertFalse(gate.onMoveHead(new Vec3d(-0.0, 0.0, 0.0)), "-0.0 分量不算 ZERO");
        assertFalse(gate.onMoveHead(new Vec3d(0.0, 0.0, -0.0)));
        assertTrue(gate.onMoveHead(Vec3d.ZERO), "标志没有被负零置位，正常零位移仍取消");
    }

    /**
     * <b>时序约束的反例演示</b>：如果把"比较"放到 {@code setBoundingBox} 赋值<b>之后</b>，
     * 比到的就是新盒自己，恒等 ⇒ 标志永远置不上 ⇒ 短路永远生效 ⇒ 比现服务器更快且行为不同。
     * 方法名 {@code onSetBoundingBoxHeadBeforeAssign} 就是为了让这种写法写不出来。
     */
    @Test
    void comparingAfterTheAssignmentWouldSilentlyNeverFire() {
        VmpZeroVelocityGate wrongOrder = new VmpZeroVelocityGate();
        Box incoming = box(0, 0, 0, 2, 1, 1);
        wrongOrder.onSetBoundingBoxHeadBeforeAssign(incoming, incoming); // 模拟"赋值之后再比较"
        assertFalse(wrongOrder.boundingBoxChanged(), "错误时序下标志恒为 false");
        assertTrue(wrongOrder.onMoveHead(Vec3d.ZERO),
                "于是短路永远生效 —— 这正是必须把约束写进方法名的原因");

        VmpZeroVelocityGate rightOrder = new VmpZeroVelocityGate();
        rightOrder.onSetBoundingBoxHeadBeforeAssign(box(0, 0, 0, 1, 1, 1), incoming);
        assertTrue(rightOrder.boundingBoxChanged());
        assertFalse(rightOrder.onMoveHead(Vec3d.ZERO));
    }

    /** 诊断计数：证明短路真的被问过（金丝雀式的"确实执行过"证据）。 */
    @Test
    void countersRecordQueries() {
        VmpZeroVelocityGate gate = new VmpZeroVelocityGate();
        gate.onMoveHead(Vec3d.ZERO);
        gate.onMoveHead(Vec3d.ZERO);
        gate.onSetBoundingBoxHeadBeforeAssign(box(0, 0, 0, 1, 1, 1), box(0, 0, 0, 1, 1, 1));
        assertEquals(2, gate.moveQueries());
        assertEquals(1, gate.boxUpdates());
    }
}
