package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code cava_pathfind} 返回值的分类（纯函数）。
 *
 * <p>这是"回退默认安全"的核心判据：**只有 {@code 0 < rc <= cap} 才允许接管**。
 * 尤其 {@code 0}（无路径）**不是**等价结果 —— 原版在目标集合非空时几乎不返回 null，
 * 它的"没找到"表达是一条到最接近点的短路径（oracle spec 4.3.1）。
 */
class PathfindOutcomeTest {

    @Test
    void positiveWithinCapTakesOver() {
        assertEquals(PathfindOutcome.Kind.TAKE_OVER, PathfindOutcome.classify(1, 64));
        assertEquals(PathfindOutcome.Kind.TAKE_OVER, PathfindOutcome.classify(64, 64));
    }

    @Test
    void zeroIsNotAnEquivalentResult() {
        assertEquals(PathfindOutcome.Kind.FALLBACK, PathfindOutcome.classify(0, 64));
        assertTrue(PathfindOutcome.fallbackReason(0, 64).contains("无路径"));
    }

    @Test
    void everyErrorCodeFallsBack() {
        for (int rc : new int[]{-1, -2, -3, -4, -5, -6, -7, -100, -101, Integer.MIN_VALUE}) {
            assertEquals(PathfindOutcome.Kind.FALLBACK, PathfindOutcome.classify(rc, 64), "rc=" + rc);
        }
    }

    @Test
    void moreThanCapIsAContractViolationAndFallsBack() {
        assertEquals(PathfindOutcome.Kind.FALLBACK, PathfindOutcome.classify(65, 64));
        assertTrue(PathfindOutcome.fallbackReason(65, 64).contains("违反 ABI"));
    }
}
