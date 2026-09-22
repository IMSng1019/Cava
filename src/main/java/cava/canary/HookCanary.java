package cava.canary;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 钩子金丝雀（契约第 3 节 / 任务 C6）。
 *
 * <p>每个注入点持有一个实例：钩子被命中时调 {@link #hit()}，启动自检时用
 * {@link #probeNow()} 主动触发一次目标方法，看计数有没有动。**计数没动 = 这个钩子没生效**
 * → 该子系统必须 {@code disable} 并打 ERROR（不是崩溃）。
 *
 * <p>本类不引用任何 Minecraft 类型（P0 只有自检和框架）。
 */
public final class HookCanary {

    private final String hookId;
    private final AtomicLong hits = new AtomicLong();
    private volatile Runnable probe;
    private volatile String lastProbeResult = "(未探测)";

    public HookCanary(String hookId) {
        this.hookId = hookId;
    }

    public String hookId() {
        return hookId;
    }

    /** 钩子命中时调用（注入点里第一句）。 */
    public long hit() {
        return hits.incrementAndGet();
    }

    public long count() {
        return hits.get();
    }

    public void reset() {
        hits.set(0);
    }

    /** 设置一次性探测目标（P1 起由各子系统在注册时设置）。 */
    public void setProbe(Runnable target) {
        this.probe = target;
    }

    /**
     * 主动触发一次探测，返回**计数是否增加**。
     *
     * @return true = 金丝雀动了（钩子生效）；false = 没动（或没有探测目标）
     */
    public boolean probeNow() {
        Runnable target = probe;
        if (target == null) {
            lastProbeResult = "没有探测目标（P0 骨架：尚未安装钩子）";
            return false;
        }
        long before = hits.get();
        try {
            target.run();
        } catch (Throwable t) {
            lastProbeResult = "探测抛出异常: " + t;
            return false;
        }
        long after = hits.get();
        boolean moved = after > before;
        lastProbeResult = moved ? ("计数 " + before + " -> " + after) : ("计数未变（仍为 " + before + "）");
        return moved;
    }

    /** 用给定目标探测一次（等价 setProbe + probeNow）。 */
    public boolean probe(Runnable target) {
        setProbe(target);
        return probeNow();
    }

    public String lastProbeResult() {
        return lastProbeResult;
    }

    @Override
    public String toString() {
        return hookId + "=" + hits.get();
    }
}
