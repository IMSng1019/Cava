package cava.push;

/**
 * {@code Entity.pushAwayFrom(Entity)} 几何部分的 **Java 参照实现**（第二份独立转写）。
 *
 * <p>它存在的唯一理由：影子模式需要一个能与原生内核**逐位对拍**的独立实现。
 * 两份实现都照 {@code docs/CAVA-push-oracle-spec.md} 的 javap 偏移逐条写，
 * 任何一处读反都会在 {@code cava.push.PushMathTest} / 真实服务端的 shadow 计数里露出来。
 *
 * <p>**它永远不参与接管**：live 只用原生。参照实现只用于比对与回退诊断。
 */
public final class PushMath {

    /** 原版字面量 {@code ldc2_w // double 0.009999999776482582d}（偏移 55）= {@code (double)0.01F}。 */
    public static final double MIN_SEP_SQ = 0.00999999977648258209228515625;

    /** 原版字面量 {@code ldc2_w // double 0.05000000074505806d}（偏移 110/117）= {@code (double)0.05F}。 */
    public static final double FACTOR = 0.0500000007450580596923828125;

    private PushMath() {
    }

    /**
     * 偏移 24-121 的逐条转写。
     *
     * @param out 长度 ≥ 3 的输出：{@code out[0]=dx(}即原版局部量 d{@code ), out[1]=dz, out[2]=hit}
     */
    public static void compute(double thisX, double thisZ, double otherX, double otherZ, double[] out) {
        double d = otherX - thisX;                 // 24..33  d = entity.getX() - this.getX()
        double e = otherZ - thisZ;                 // 34..43  e = entity.getZ() - this.getZ()
        double f = absMax(d, e);                   // 45..51
        if (!(f >= MIN_SEP_SQ)) {                  // 53..59  dcmpl + iflt（NaN 也走不进）
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return;
        }
        f = Math.sqrt(f);                          // 62..67
        d = d / f;                                 // 69..73
        e = e / f;                                 // 74..79
        double g = 1.0 / f;                        // 81..85
        if (g > 1.0) {                             // 87..95  dcmpl + ifle
            g = 1.0;
        }
        d = d * g;                                 // 97..101
        e = e * g;                                 // 102..107
        d = d * FACTOR;                            // 109..114
        e = e * FACTOR;                            // 115..121
        out[0] = d;
        out[1] = e;
        out[2] = 1.0;
    }

    /** {@code MathHelper.absMax(double,double)}（字节码 272-291）：两个独立取负 + {@code Math.max}。 */
    public static double absMax(double a, double b) {
        if (a < 0.0) {
            a = -a;
        }
        if (b < 0.0) {
            b = -b;
        }
        return Math.max(a, b);
    }
}
