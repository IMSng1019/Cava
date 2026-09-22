package cava.subsystem;

import cava.canary.HookCanary;

/**
 * 子系统骨架的公共实现：可禁用、有金丝雀计数、无钩子（P0）。
 *
 * <p>P0 三个子系统都是它的实例：{@code enabled() == false}，{@code hooksInstalled() == false}，
 * 因此启动时的金丝雀自检会把它们标成「P0 骨架，跳过」而不是误报 ERROR。
 */
public class AbstractSubsystem implements CavaSubsystem {

    private final String id;
    private final HookCanary canary;
    private final boolean hooksInstalled;
    private volatile boolean enabled;
    private volatile String disabledReason = "";

    public AbstractSubsystem(String id, boolean hooksInstalled) {
        this.id = id;
        this.hooksInstalled = hooksInstalled;
        this.canary = new HookCanary(id + ":main");
        // P0：没有钩子就只能是 disabled —— 契约要求 enabled()==false 时钩子完全不介入
        this.enabled = hooksInstalled;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean nativeReady() {
        return cava.ffm.CavaNative.get().available();
    }

    @Override
    public void canaryProbe() {
        // P0 没有目标方法可触发：不动计数（这也是 hooksInstalled==false 时不该跑探测的原因）
        canary.probeNow();
    }

    @Override
    public long canaryCount() {
        return canary.count();
    }

    @Override
    public void disable(String reason) {
        enabled = false;
        disabledReason = reason == null ? "(未给原因)" : reason;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String disabledReason() {
        return disabledReason;
    }

    @Override
    public boolean hooksInstalled() {
        return hooksInstalled;
    }

    public HookCanary canary() {
        return canary;
    }
}
