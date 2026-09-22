package cava.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Java 参照实现（{@link PushMath}）的**定点真值表**。
 *
 * <p>期望值全部由 {@code docs/CAVA-push-oracle-spec.md} 的 javap 偏移手推，**不跑原生**。
 * 原生侧同一张表在 {@code native/tests/push/cava_push_vectors.cpp} 里。
 */
class PushMathTest {

    private static long bits(double d) {
        return Double.doubleToRawLongBits(d);
    }

    private static double[] compute(double ax, double az, double bx, double bz) {
        double[] out = new double[3];
        PushMath.compute(ax, az, bx, bz, out);
        return out;
    }

    @Test
    void literalConstantsMatchBytecode() {
        // javap: ldc2_w #1991 // double 0.009999999776482582d   = (double)0.01F
        //        ldc2_w #1993 // double 0.05000000074505806d    = (double)0.05F
        assertEquals(bits((double) 0.01f), bits(PushMath.MIN_SEP_SQ));
        assertEquals(bits((double) 0.05f), bits(PushMath.FACTOR));
    }

    @Test
    void shortCircuitsBelowThreshold() {
        // TT-PA-0：f = 0.009 < (double)0.01F -> 原版整个分支不进，速度分毫不动
        double[] r = compute(0, 0, 0.009, 0);
        assertEquals(0.0, r[2], "hit");
        assertEquals(bits(0.0), bits(r[0]), "dx 必须是 +0.0");
        assertEquals(bits(0.0), bits(r[1]), "dz 必须是 +0.0");
        // TT-PA-9：阈值**恰好相等**时进分支（>= 是闭界）
        assertEquals(1.0, compute(0, 0, PushMath.MIN_SEP_SQ, 0)[2], "边界相等必须进");
        // TT-PA-9b：低一个 ulp 就出界
        assertEquals(0.0, compute(0, 0, Math.nextDown(PushMath.MIN_SEP_SQ), 0)[2], "低 1 ulp 必须不进");
    }

    @Test
    void exactValuesForPowerOfTwoSeparations() {
        // TT-PA-3  d=1.0 : f=1 -> sqrt=1 -> d=1 -> g=1/1=1（**不钳**）-> 1*1*0.05F
        double[] r1 = compute(0, 0, 1.0, 0);
        assertEquals(bits(PushMath.FACTOR), bits(r1[0]));
        assertEquals(bits(0.0), bits(r1[1]));
        // TT-PA-1  d=4.0 : f=4 -> sqrt=2 -> d=2 -> g=0.5 -> 2*0.5=1 -> 1*0.05F（同一个值）
        double[] r4 = compute(0, 0, 4.0, 0);
        assertEquals(bits(PushMath.FACTOR), bits(r4[0]));
        // TT-PA-4  负方向：符号原样穿过
        double[] rn = compute(0, 0, -4.0, 0);
        assertEquals(bits(-PushMath.FACTOR), bits(rn[0]));
        assertTrue(Math.copySign(1.0, rn[0]) < 0);
        // TT-PA-2  d=0.25 < 1 : g=1/sqrt(0.25)=2 > 1 -> **钳到 1** -> 0.5*0.05F
        double[] rq = compute(0, 0, 0.25, 0);
        assertEquals(0.02500000037252902984619140625, rq[0], 0.0);
        assertFalse(bits(rq[0]) == bits(PushMath.FACTOR), "钳位分支必须给出不同的值");
    }

    @Test
    void nanAndInfinityBranches() {
        // TT-PA-6  NaN 分隔 -> absMax 传播 NaN -> 守卫不进
        assertEquals(0.0, compute(0, 0, Double.NaN, 5.0)[2]);
        // TT-PA-7  无限远 -> f=inf 通过守卫 -> d=inf/inf=NaN；**不是"推不动"**
        double[] r = compute(0, 0, Double.POSITIVE_INFINITY, 0);
        assertEquals(1.0, r[2]);
        assertTrue(Double.isNaN(r[0]), "无限远给出 NaN 位移");
        assertEquals(bits(0.0), bits(r[1]), "dz 仍是 +0.0");
        // TT-PA-8  -0.0 分隔：absMax(-0.0,-0.0) = -0.0 -> 守卫不进
        assertEquals(0.0, compute(0, 0, -0.0, -0.0)[2]);
    }

    @Test
    void positiveZeroSignIsPreserved() {
        // TT-PA-5  d=+0.0 e=8 -> dx 必须**逐位是 +0.0**（Java 侧 addVelocity(-dx,...) 靠这个符号）
        double[] r = compute(0, 0, 0.0, 8.0);
        assertEquals(bits(0.0), bits(r[0]));
        assertTrue(r[1] > 0.0);
    }

    @Test
    void absMaxIsNotMathAbsOfMax() {
        // MathHelper.absMax 的两个 if 用 dcmpg：NaN 时 ifge 成立 -> 不取负 -> Math.max 传播 NaN
        assertTrue(Double.isNaN(PushMath.absMax(Double.NaN, 5.0)));
        assertTrue(Double.isNaN(PushMath.absMax(5.0, Double.NaN)));
        assertEquals(bits(-0.0), bits(PushMath.absMax(-0.0, -0.0)), "max(-0.0,-0.0) == -0.0");
        assertEquals(bits(0.0), bits(PushMath.absMax(0.0, -0.0)), "max(0.0,-0.0) == +0.0");
    }
}
