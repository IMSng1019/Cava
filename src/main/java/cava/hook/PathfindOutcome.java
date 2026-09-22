package cava.hook;

/**
 * {@code cava_pathfind} 返回值的分类（**纯函数，可单测**）。
 *
 * <p>ABI 约定（{@code cava_abi.h}）：{@code >0} = 节点数；{@code 0} = 无路径；
 * {@code <0} = 错误码。**只有 {@code >0} 才允许接管**：
 * <ul>
 *   <li>{@code 0}（无路径）：原版 {@code findPathToAny} 在目标集合非空时几乎不返回 null
 *       （oracle spec §4.3.1，10000 组里 null 出现 0 次）——"没找到路"的表达是**一条到最接近点的短路径**。
 *       所以 {@code 0} 不是等价结果，必须回退。</li>
 *   <li>{@code <0}：任何错误码（含 {@code CAVA_ERR_UNIMPLEMENTED}）都必须回退原逻辑。</li>
 *   <li>{@code rc > cap}：ABI 说 cap 不足返回 {@code CAVA_ERR_ARG} 且绝不部分写入；
 *       真出现 {@code rc > cap} 属于契约违反 ⇒ 也回退（不信任这次输出）。</li>
 * </ul>
 */
public final class PathfindOutcome {

    /** 分类结果。 */
    public enum Kind {
        /** 用原生结果替换原版返回值。 */
        TAKE_OVER,
        /** 回退原逻辑（{@code ci.cancel()} 根本不调用）。 */
        FALLBACK
    }

    private PathfindOutcome() {
    }

    /**
     * @param rc   {@code cava_pathfind} 的原始返回值
     * @param cap  调用时给的容量
     * @return {@link Kind#TAKE_OVER} 当且仅当 {@code 0 < rc <= cap}
     */
    public static Kind classify(int rc, int cap) {
        if (rc > 0 && rc <= cap) {
            return Kind.TAKE_OVER;
        }
        return Kind.FALLBACK;
    }

    /** 回退原因（日志用；{@code rc > 0} 时返回空串）。 */
    public static String fallbackReason(int rc, int cap) {
        if (rc > 0 && rc <= cap) {
            return "";
        }
        if (rc == 0) {
            return "CAVA 返回 0（无路径）—— 与原版『到最接近点的短路径』不等价，回退";
        }
        if (rc > cap) {
            return "CAVA 返回 " + rc + " > cap " + cap + "（违反 ABI：cap 不足应返回 CAVA_ERR_ARG）";
        }
        return "CAVA 返回错误码 " + rc;
    }
}
