package cava.ffm;

/**
 * 数值工具的唯一入口（任务 C5）。
 *
 * <p>契约铁律：Java 的 {@code (double)->int/long} 越界**饱和**，C++ 直接转是 UB。
 * 因此所有「double 落到整数」的转换都必须走这里；原生可用时走 {@code cava_d2i_sat} /
 * {@code cava_d2l_sat}，不可用时回退纯 Java。
 *
 * <p><b>为什么 Java 回退是等价的</b>（JLS 5.1.3 narrowing primitive conversion）：
 * <ul>
 *   <li>NaN → 0（与 cava_abi.h 里写的「NaN -> 0，与 Java 的 (int)Double.NaN == 0 一致」相同）；</li>
 *   <li>数值过小（含 -inf）→ {@code Integer.MIN_VALUE} / {@code Long.MIN_VALUE}；</li>
 *   <li>数值过大（含 +inf）→ {@code Integer.MAX_VALUE} / {@code Long.MAX_VALUE}；</li>
 *   <li>其余情况向零取整。</li>
 * </ul>
 * 即 Java 的强制转换**本身就是**饱和语义，不需要手写 clamp。原生实现必须复刻这张表。
 *
 * <p>位模式工具用 {@link Double#doubleToRawLongBits}（**不是** {@code doubleToLongBits}）：
 * raw 版本保留 NaN 的载荷位，差分测试的位模式哈希必须逐位一致。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class Numeric {

    private Numeric() {
    }

    /** 饱和 double→int：原生可用走原生，否则走 JLS 5.1.3 的等价语义。 */
    public static int d2iSat(double v) {
        CavaBindings b = CavaNative.get().bindingsIfOpen();
        if (b != null) {
            try {
                return b.d2iSat(v);
            } catch (Throwable t) {
                CavaNative.get().onNativeCallFailure("cava_d2i_sat", t);
            }
        }
        // Java 的 (int) 强制转换就是饱和转换（NaN→0，越界→MIN/MAX）——见类注释
        return (int) v;
    }

    /** 饱和 double→long：同上。 */
    public static long d2lSat(double v) {
        CavaBindings b = CavaNative.get().bindingsIfOpen();
        if (b != null) {
            try {
                return b.d2lSat(v);
            } catch (Throwable t) {
                CavaNative.get().onNativeCallFailure("cava_d2l_sat", t);
            }
        }
        return (long) v;
    }

    /** double 的原始位模式（保留 NaN 载荷）。 */
    public static long bitsOfDouble(double v) {
        CavaBindings b = CavaNative.get().bindingsIfOpen();
        if (b != null) {
            try {
                return b.bitsOfDouble(v);
            } catch (Throwable t) {
                CavaNative.get().onNativeCallFailure("cava_bits_of_double", t);
            }
        }
        return Double.doubleToRawLongBits(v);
    }

    /** 位模式还原 double。 */
    public static double doubleOfBits(long bits) {
        CavaBindings b = CavaNative.get().bindingsIfOpen();
        if (b != null) {
            try {
                return b.doubleOfBits(bits);
            } catch (Throwable t) {
                CavaNative.get().onNativeCallFailure("cava_double_of_bits", t);
            }
        }
        return Double.longBitsToDouble(bits);
    }

    /** 当前数值工具是否走原生路径（诊断/自检用）。 */
    public static boolean usingNative() {
        return CavaNative.get().available();
    }
}
