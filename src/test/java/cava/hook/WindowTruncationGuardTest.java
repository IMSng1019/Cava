package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 窗口截断检测单测（缺陷 2 的修复件）。**不引用 Minecraft 类型**，用合成节点序列。
 *
 * <p>用到的两个真实场景形状（来自 {@code docs/CAVA-pathfind-perf.md} §6.2 的实测回执）：
 * <ul>
 *   <li>{@code detour128}：起点 (32,71,0)、目标 (160,71,0)、窗口 x 28..164 / y 67..75 / z -4..4；
 *       原生返回 64 个节点、末节点 (95,71,0)、曼哈顿 65 —— **必须判回退**；</li>
 *   <li>{@code maze63}：起点 (33,71,-159)、目标 (93,71,-99)、窗口 x 29..97 / z -163..-95；
 *       原生返回 503 个节点、末节点 (83,71,-111)、曼哈顿 22（两腿实测逐字段一致的预算耗尽场景）——
 *       **必须不判回退**（否则每个 maze63 求解都要白付一次 Java 全搜索）。</li>
 * </ul>
 */
class WindowTruncationGuardTest {

    @BeforeEach
    void resetPropsBefore() {
        clearProps();
    }

    @AfterEach
    void resetPropsAfter() {
        clearProps();
    }

    private static void clearProps() {
        System.clearProperty(PathfindSwitches.PROP_WINDOW_GUARD);
        System.clearProperty(PathfindSwitches.PROP_WINDOW_NOT_REACHED);
    }

    private static NativeNodeCodec.Node node(int x, int y, int z) {
        // type 序号 = PathNodeType.WALKABLE 的 ordinal（= 2，见 cava_abi.h 的 CAVA_PNT_*）
        return new NativeNodeCodec.Node(x, y, z, -1, 0.0f, 0.0f, 2, 0);
    }

    /** 从 (sx,sy,sz) 到 (ex,ey,ez) 的轴向节点序列（模拟一条直路）。 */
    private static List<NativeNodeCodec.Node> line(int sx, int sy, int sz, int ex, int ey, int ez) {
        List<NativeNodeCodec.Node> out = new ArrayList<>();
        int x = sx;
        int y = sy;
        int z = sz;
        out.add(node(x, y, z));
        while (x != ex || y != ey || z != ez) {
            if (x != ex) {
                x += Integer.signum(ex - x);
            } else if (z != ez) {
                z += Integer.signum(ez - z);
            } else {
                y += Integer.signum(ey - y);
            }
            out.add(node(x, y, z));
        }
        return out;
    }

    private static WindowTruncationGuard.Verdict detourWindow(List<NativeNodeCodec.Node> nodes) {
        return WindowTruncationGuard.check(28, 67, -4, 137, 9, 9, -64, 319,
                nodes, 160, 71, 0, 1);
    }

    private static WindowTruncationGuard.Verdict mazeWindow(List<NativeNodeCodec.Node> nodes) {
        return WindowTruncationGuard.check(29, 67, -163, 69, 9, 69, -64, 319,
                nodes, 93, 71, -99, 1);
    }

    /** 缺陷 2 的最小复现：原生停在墙前（64 节点、曼哈顿 65）⇒ 必须回退。 */
    @Test
    void detour128TruncatedClosestPointMustFallBack() {
        List<NativeNodeCodec.Node> nodes = line(32, 71, 0, 95, 71, 0);
        assertEquals(64, nodes.size());
        WindowTruncationGuard.Verdict v = detourWindow(nodes);
        assertTrue(v.fallback(), "必须回退");
        assertEquals(WindowTruncationGuard.REASON_NOT_REACHED_EARLY, v.reason());
    }

    /** maze63：走了 503 步、曼哈顿 22（预算耗尽，两腿本来就逐字段一致）⇒ 不许回退。 */
    @Test
    void maze63StructuralClosestPointMustNotFallBack() {
        List<NativeNodeCodec.Node> nodes = new ArrayList<>();
        // 503 个节点、末节点 (83,71,-111)：用一条折线模拟"绕了很多路但没到"
        for (int i = 0; i < 502; i++) {
            nodes.add(node(33 + (i % 50), 71, -159 + (i % 48)));
        }
        nodes.add(node(83, 71, -111));
        assertEquals(503, nodes.size());
        WindowTruncationGuard.Verdict v = mazeWindow(nodes);
        assertFalse(v.fallback(), "结构性最近点不许回退（实测两腿一致）");
        assertEquals(WindowTruncationGuard.REASON_NOT_REACHED_STRUCTURAL, v.reason());
    }

    /** 抵达目标（曼哈顿 ≤ reachRange）且不贴边 ⇒ 通过。 */
    @Test
    void reachedPathPasses() {
        WindowTruncationGuard.Verdict v = detourWindow(line(32, 71, 0, 159, 71, 0));
        assertFalse(v.fallback());
        assertEquals("", v.reason());
    }

    /** 判据 1：任一节点贴到窗口边界 ⇒ 回退（含"贴着边界的最近点"）。 */
    @Test
    void nodeOnWindowShellFallsBack() {
        List<NativeNodeCodec.Node> nodes = line(32, 71, 0, 159, 71, 0);
        nodes.add(node(95, 71, -4));   // z = 窗口下界
        WindowTruncationGuard.Verdict v = detourWindow(nodes);
        assertTrue(v.fallback());
        assertEquals(WindowTruncationGuard.REASON_SHELL, v.reason());
    }

    /** 判据 2：目标抵达球没有完整落在窗口内 ⇒ 回退。 */
    @Test
    void goalBallOutsideWindowFallsBack() {
        List<NativeNodeCodec.Node> nodes = line(32, 71, 0, 159, 71, 0);
        // 目标 z=4 而窗口 z 上界=4 ⇒ z+reachRange 越界
        WindowTruncationGuard.Verdict v = WindowTruncationGuard.check(28, 67, -4, 137, 9, 9, -64, 319,
                nodes, 160, 71, 4, 1);
        assertTrue(v.fallback());
        assertEquals(WindowTruncationGuard.REASON_GOAL_SHELL, v.reason());
    }

    /** y 面贴在**世界高度**上不算截断（窗口 Y 被世界高度裁剪，原版也停在那儿）。 */
    @Test
    void worldClampedYFaceIsNotAWindowShell() {
        List<NativeNodeCodec.Node> nodes = line(40, -64, 0, 159, -64, 0);
        WindowTruncationGuard.Verdict v = WindowTruncationGuard.check(28, -64, -4, 137, 9, 9, -64, 319,
                nodes, 159, -64, 0, 0);
        assertFalse(v.fallback(), "贴世界底不是窗口截断");
    }

    /** 关掉检测（可证伪对照用）：同样的截断路径不再回退。 */
    @Test
    void guardOffDisablesEverything() {
        System.setProperty(PathfindSwitches.PROP_WINDOW_GUARD, "false");
        WindowTruncationGuard.Verdict v = detourWindow(line(32, 71, 0, 95, 71, 0));
        assertFalse(v.fallback());
    }

    /** {@code always} 策略：结构性最近点也回退（最保守档，给 captain 留的旋钮）。 */
    @Test
    void alwaysPolicyFallsBackOnAnyNotReached() {
        System.setProperty(PathfindSwitches.PROP_WINDOW_NOT_REACHED, "always");
        List<NativeNodeCodec.Node> nodes = new ArrayList<>();
        for (int i = 0; i < 502; i++) {
            nodes.add(node(33 + (i % 50), 71, -159 + (i % 48)));
        }
        nodes.add(node(83, 71, -111));
        WindowTruncationGuard.Verdict v = mazeWindow(nodes);
        assertTrue(v.fallback());
        assertEquals(WindowTruncationGuard.REASON_NOT_REACHED, v.reason());
    }

    /** 非法策略串一律回默认 early-stop（开关解析绝不能让服务端起不来）。 */
    @Test
    void invalidPolicyFallsBackToDefault() {
        System.setProperty(PathfindSwitches.PROP_WINDOW_NOT_REACHED, "nonsense");
        assertEquals("early-stop", PathfindSwitches.windowNotReachedPolicy());
    }
}
