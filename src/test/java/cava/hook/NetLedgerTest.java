package cava.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** P1-NET 的账本件单测：{@link CallStats} / {@link TickTimeRecorder} / 分流判据。 */
class NetLedgerTest {

    @Test
    void callStatsPercentilesAndHistogram() {
        CallStats s = new CallStats();
        assertEquals(-1L, s.percentile(0.5), "没有样本时必须是 -1，不能是 0（0 会被当成真实值）");
        for (int i = 1; i <= 100; i++) {
            s.add(i);
        }
        assertEquals(100L, s.count());
        assertEquals(1L, s.min());
        assertEquals(100L, s.max());
        // 分位口径：idx = round(q*(n-1)) ⇒ n=100 时 p50 落在第 51 个样本（1..100 ⇒ 51）
        assertEquals(51L, s.percentile(0.50));
        assertEquals(90L, s.percentile(0.90));
        assertEquals(99L, s.percentile(0.99));
        assertEquals(50.5, s.avg(), 1e-9);
        // 桶口径：v < edge 落进该桶（标签就是"小于该边界"）；最后一个是">= 最大边界"
        String hist = s.histogram(new long[] {10, 50, 100});
        assertEquals("<10:9;<50:40;<100:50;+:1", hist);
    }

    @Test
    void callStatsRespectsSampleCapAndReportsOverflow() {
        CallStats s = new CallStats();
        for (int i = 0; i < CallStats.MAX_SAMPLES + 7; i++) {
            s.add(5);
        }
        assertEquals(CallStats.MAX_SAMPLES + 7L, s.count());
        assertEquals(7L, s.overflowCount());
        assertEquals(5L, s.percentile(0.99));
        // 溢出的样本仍要算进最后一个桶（不能静默丢）
        assertTrue(s.histogram(new long[] {1, 2}).endsWith(":" + (CallStats.MAX_SAMPLES + 7)));
    }

    @Test
    void callStatsReset() {
        CallStats s = new CallStats();
        s.add(3);
        s.reset();
        assertEquals(0L, s.count());
        assertEquals(-1L, s.max());
        assertEquals(0.0, s.avg(), 1e-9);
    }

    @Test
    void tickRecorderNeedsTwoTicksAndSkipsImplausibleGaps() {
        TickTimeRecorder r = TickTimeRecorder.get();
        r.reset();
        r.onEndTick();                       // 第一次只做锚点
        assertEquals(0L, r.ticks());
        r.onEndTick();                       // 第二次才有样本
        assertEquals(1L, r.ticks());
        assertTrue(r.report().startsWith("TICKSTAT ticks=1 "), r.report());
        // 冻结窗口（>60 s）必须进 skipped 而不是样本
        r.reset();
        r.onEndTick();
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        r.onEndTick();
        assertEquals(1L, r.ticks());
        assertEquals(0L, r.skipped());
        r.reset();
        assertEquals(0L, r.ticks());
    }

    @Test
    void chebyshevDistanceIsTheStepLowerBound() {
        assertEquals(0, PathfindHook.chebyshev(10, 64, 10, 10, 64, 10));
        assertEquals(5, PathfindHook.chebyshev(10, 64, 10, 15, 64, 10));
        assertEquals(5, PathfindHook.chebyshev(10, 64, 10, 10, 64, 5));
        assertEquals(7, PathfindHook.chebyshev(10, 64, 10, 17, 70, 3));
        assertEquals(9, PathfindHook.chebyshev(0, 0, 0, -9, 0, 2), "负数方向也必须是对称的");
    }

    @Test
    void gateSwitchDefaultsToTheDataDrivenValueAndCanBeDisabled() {
        // 默认值本身是**数据出处**的一部分（docs/CAVA-p1-net-notes.md §5）：
        // 真实 AI 负载上"按规模分流"不能把总账翻正（每个距离桶都是净亏，阈值只能砍前缀）⇒
        // 默认 = 0 = 不分流（与已测行为一致）；要试别的负载用 -D 显式打开，不用改代码。
        assertEquals(0L, PathfindSwitches.DEFAULT_GATE_MIN_BLOCKS,
                "默认必须是 0：分流已实测**不能**改善本负载的净收益（见 docs/CAVA-p1-net-notes.md §5）");
        String old = System.getProperty(PathfindSwitches.PROP_GATE_MIN);
        try {
            System.setProperty(PathfindSwitches.PROP_GATE_MIN, "0");
            assertEquals(0L, PathfindSwitches.gateMinBlocks());
            System.setProperty(PathfindSwitches.PROP_GATE_MIN, "7");
            assertEquals(7L, PathfindSwitches.gateMinBlocks());
            System.setProperty(PathfindSwitches.PROP_GATE_MIN, "不是数字");
            assertEquals(PathfindSwitches.DEFAULT_GATE_MIN_BLOCKS, PathfindSwitches.gateMinBlocks(),
                    "非法值必须回默认（开关解析绝不能让服务端起不来）");
        } finally {
            if (old == null) {
                System.clearProperty(PathfindSwitches.PROP_GATE_MIN);
            } else {
                System.setProperty(PathfindSwitches.PROP_GATE_MIN, old);
            }
        }
    }
}
