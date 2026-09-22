package cava.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** VMP 适配器：零位移短路语义（纯函数/状态机）+ "不可配置"（真实 jar 探测）。 */
class VmpAdapterTest {

    @Test
    void zeroMovementShortCircuitUsesDoubleCompare() {
        assertTrue(VmpAdapter.shouldCancelMove(false, 0.0d, 0.0d, 0.0d));
        assertFalse(VmpAdapter.shouldCancelMove(true, 0.0d, 0.0d, 0.0d), "包围盒变过就不短路");
        assertFalse(VmpAdapter.shouldCancelMove(false, 0.0d, -0.0d, 0.0d),
                "Vec3d.equals 用 Double.compare -> -0.0 不等于 0.0（javap 实证），不能写成 == 0");
        assertFalse(VmpAdapter.shouldCancelMove(false, -0.0d, 0.0d, 0.0d));
        assertFalse(VmpAdapter.shouldCancelMove(false, 1.0E-9d, 0.0d, 0.0d));
    }

    @Test
    void stateMachineReplaysMixinFields() {
        VmpAdapter.State st = new VmpAdapter.State();
        assertFalse(st.boundingBoxChanged());
        assertTrue(st.onMove(0, 0, 0), "初始状态 + 零位移 -> 短路");
        assertFalse(st.boundingBoxChanged());
        st.onSetBoundingBox(true);
        assertFalse(st.boundingBoxChanged(), "包围盒没变 -> 不置位");
        st.onSetBoundingBox(false);
        assertTrue(st.boundingBoxChanged(), "包围盒变了 -> 置位");
        // 注意：字节码里只有 cancel 分支才会把标志复位，而 cancel 又要求标志已经是 false
        // => 一旦包围盒变过一次，这个实体的零位移短路就**永久失效**（黏滞标志）。
        //    这是 VMP 的真实语义（javap -c 实证），P2 复刻时必须照抄，否则行为会变。
        assertFalse(st.onMove(0, 0, 0), "置位后即使零位移也不短路");
        assertTrue(st.boundingBoxChanged(), "没短路就不复位");
        assertFalse(st.onMove(0.5d, 0, 0));
        assertFalse(st.onMove(0, 0, 0), "黏滞：此后再也不会短路");
        st.onSetBoundingBox(true);
        assertFalse(st.onMove(0, 0, 0), "onSetBoundingBox 只置位、从不复位");
    }

    @Test
    void probeJarShowsUnconditionalMixin() {
        Path jar = TestPaths.modpackJar("vmp").orElse(null);
        Assumptions.assumeTrue(jar != null, "本机没有 vmp jar，跳过");
        Optional<VmpAdapter.Schema> s = VmpAdapter.probeJar(jar);
        assertTrue(s.isPresent());
        VmpAdapter.Schema sc = s.get();
        assertTrue(sc.mixinDeclared(), "entity.move_zero_velocity.MixinEntity 必须在 mixins 数组里");
        assertTrue(sc.unconditional(), "该 mixin 不在 VMPMixinPlugin 的门控前缀里 -> 无条件生效 = 不可配置");
        assertFalse(sc.gatedByPlugin());
        assertTrue(sc.moveTargetInRefmap(), "refmap 必须把 move 指到 method_5784");
        assertTrue(sc.setBoundingBoxInRefmap(), "refmap 必须把 setBoundingBox 指到 method_5857");
        assertEquals("vmp-fabric-mc1.20.4-refmap.json", sc.refmapName());
        assertTrue(sc.gatePrefixes().size() > 0, "插件里应当能探到别的门控前缀（反证探测真的在看常量池）");
        assertFalse(sc.gatePrefixes().stream().anyMatch(p -> VmpAdapter.MIXIN_ENTITY_CLASS.startsWith(p)));
    }

    @Test
    void missingJarIsHandledGracefully() {
        assertTrue(VmpAdapter.probeJar(null).isEmpty());
        assertTrue(VmpAdapter.probeJar(Path.of("no-such.jar")).isEmpty());
        assertTrue(VmpAdapter.refmapEntry("{}", "x").isEmpty());
        assertFalse(VmpAdapter.reportLine(Optional.empty(), false).contains("null"));
    }
}
