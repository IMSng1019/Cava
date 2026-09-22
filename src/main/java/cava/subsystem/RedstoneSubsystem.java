package cava.subsystem;

/**
 * redstone 子系统（P0 骨架，**不装任何钩子**）。
 *
 * <p>P0 明确禁止 mixin 原版方法，所以这里只有注册骨架：{@code hooksInstalled() == false}、
 * {@code enabled() == false}。P1 起在这里挂 {@code cava.hook.*} 的注入点并实现金丝雀探测目标。
 */
public final class RedstoneSubsystem extends AbstractSubsystem {

    /** 子系统 id。 */
    public static final String ID = "redstone";

    public RedstoneSubsystem() {
        super(ID, false);
    }
}
