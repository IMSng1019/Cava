package cava.oracle;

/**
 * 原版 net.minecraft.util.math.MathHelper 里寻路用到的几个方法的逐位复刻。
 *
 * <p>证据（javap -p -c net.minecraft.util.math.MathHelper）：
 * <pre>
 * public static float sqrt(float f);
 *    0: fload_0 ; f2d ; invokestatic java/lang/Math.sqrt:(D)D ; d2f ; freturn
 *
 * public static int floor(float f);
 *    0: fload_0 ; f2i ; istore_1
 *    3: fload_0 ; iload_1 ; i2f ; fcmpg ; ifge 16
 *   10: iload_1 ; iconst_1 ; isub ; goto 17
 *   16: iload_1
 *   17: ireturn
 *
 * public static int floor(double d);   // 同构，dcmpg
 * public static int ceil(float f);     // fcmpl / ifle，返回 i+1
 * public static int ceil(double d);    // dcmpl / ifle，返回 i+1
 * </pre>
 *
 * <p>注意 (int) 转换在 Java 里是**饱和**的（NaN -> 0，越界 -> MIN/MAX），这里靠 Java 自身的
 * f2i / d2i 语义保持一致，不要自己写截断。
 */
public final class Mth {
    private Mth() {}

    /** MathHelper.sqrt(float)。 */
    public static float sqrt(float f) {
        return (float) Math.sqrt((double) f);
    }

    /** MathHelper.floor(float)。 */
    public static int floor(float f) {
        int i = (int) f;
        return f < (float) i ? i - 1 : i;
    }

    /** MathHelper.floor(double)。 */
    public static int floor(double d) {
        int i = (int) d;
        return d < (double) i ? i - 1 : i;
    }

    /** MathHelper.ceil(float)。 */
    public static int ceil(float f) {
        int i = (int) f;
        return f > (float) i ? i + 1 : i;
    }

    /** MathHelper.ceil(double)。 */
    public static int ceil(double d) {
        int i = (int) d;
        return d > (double) i ? i + 1 : i;
    }
}
