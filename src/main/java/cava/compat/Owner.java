package cava.compat;

/**
 * 一个重叠点的最终归属（契约第 5 节：{@code native} / {@code mod} / {@code vanilla}）。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #NATIVE} —— Cava 在原生侧复刻对方语义，对方那条补丁不再执行（必要时关掉它的 mixin 组）。</li>
 *   <li>{@link #MOD}    —— 让位：对方继续跑，Cava 对应子系统<b>完全不介入</b>。</li>
 *   <li>{@link #VANILLA}—— 没有任何 mod 占这个点，走原版实现；Cava 将来可以自己接管。</li>
 * </ul>
 */
public enum Owner {
    NATIVE("native"),
    MOD("mod"),
    VANILLA("vanilla");

    private final String json;

    Owner(String json) {
        this.json = json;
    }

    public String jsonName() {
        return json;
    }

    /** 子系统级裁决用：只要有一个点不是 NATIVE，子系统整体就必须让位。 */
    public static Owner merge(Owner a, Owner b) {
        if (a == MOD || b == MOD) {
            return MOD;
        }
        if (a == NATIVE || b == NATIVE) {
            return NATIVE;
        }
        return VANILLA;
    }
}
