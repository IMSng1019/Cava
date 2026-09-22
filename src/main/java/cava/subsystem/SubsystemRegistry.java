package cava.subsystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子系统注册表 + 金丝雀自检（契约第 3 节 / 任务 C6）。
 *
 * <p>启动流程：{@code Cava.onInitialize} → {@code CavaNative.tryOpen()} →
 * 失败则 {@link #disableAll(String)}；成功则 SERVER_STARTED 时跑 {@link #canaryProbeAll()}，
 * 计数没动 → 该子系统 {@code disable} 并打 **ERROR**（不是崩溃）。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class SubsystemRegistry {

    private static final Logger LOG = LoggerFactory.getLogger("cava/subsystem");
    private static final SubsystemRegistry INSTANCE = new SubsystemRegistry();

    public static SubsystemRegistry get() {
        return INSTANCE;
    }

    private final List<CavaSubsystem> subsystems = new ArrayList<>();

    private SubsystemRegistry() {
    }

    /** 注册（幂等：同 id 只留第一个）。 */
    public synchronized void register(CavaSubsystem subsystem) {
        for (CavaSubsystem s : subsystems) {
            if (s.id().equals(subsystem.id())) {
                LOG.warn("[cava] 子系统 {} 已注册，忽略重复注册", subsystem.id());
                return;
            }
        }
        subsystems.add(subsystem);
    }

    public synchronized List<CavaSubsystem> all() {
        return Collections.unmodifiableList(new ArrayList<>(subsystems));
    }

    public synchronized Optional<CavaSubsystem> byId(String id) {
        return subsystems.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** 原生不可用：全部禁用（回退纯 Java）。 */
    public synchronized void disableAll(String reason) {
        for (CavaSubsystem s : subsystems) {
            s.disable(reason);
        }
        LOG.info("[cava] 全部子系统已禁用：{}", reason);
    }

    /**
     * SERVER_STARTED 时的金丝雀自检。
     *
     * @return 通过探测的子系统数
     */
    public synchronized int canaryProbeAll() {
        int passed = 0;
        for (CavaSubsystem s : subsystems) {
            if (!s.enabled()) {
                LOG.info("[cava] 金丝雀跳过 {}：{}（P0 骨架或已被禁用，不参与判定）", s.id(),
                        s.disabledReason().isEmpty() ? "enabled=false" : s.disabledReason());
                continue;
            }
            if (!s.hooksInstalled()) {
                LOG.info("[cava] 金丝雀跳过 {}：未安装钩子（hooksInstalled=false）", s.id());
                continue;
            }
            long before = s.canaryCount();
            try {
                s.canaryProbe();
            } catch (Throwable t) {
                s.disable("金丝雀探测抛出异常: " + t);
                LOG.error("[cava] 子系统 {} 金丝雀探测抛出异常，已禁用（回退纯 Java）", s.id(), t);
                continue;
            }
            if (s.canaryCount() == before) {
                s.disable("金丝雀探测后计数未变（钩子没生效）");
                LOG.error("[cava] 子系统 {} 的钩子金丝雀**没有动**（{} -> {}），已禁用并回退纯 Java",
                        s.id(), before, s.canaryCount());
            } else {
                passed++;
                LOG.info("[cava] 子系统 {} 金丝雀通过（{} -> {}）", s.id(), before, s.canaryCount());
            }
        }
        LOG.info("[cava] 金丝雀自检结束：{} 个通过 / {} 个注册", passed, subsystems.size());
        return passed;
    }

    /**
     * 金丝雀框架自检（{@code -Dcava.canary.selftest=true}）。
     *
     * <p>用两个合成子系统把「命中 → 通过」与「未命中 → disable + ERROR」两条路都走一遍，
     * 生产路径不受影响（合成实例不注册进全局表）。
     *
     * @return true = 两条路径行为都正确
     */
    public boolean canarySelfTest() {
        LOG.info("[cava] === 金丝雀框架自检 ===");
        SubsystemRegistry probe = new SubsystemRegistry();
        AbstractSubsystem good = new AbstractSubsystem("selftest-good", true);
        AbstractSubsystem bad = new AbstractSubsystem("selftest-bad", true);
        probe.register(good);
        probe.register(bad);
        // good：探测时计数 +1（模拟钩子生效）；bad：探测不动（模拟钩子没生效）
        good.canary().setProbe(good.canary()::hit);
        bad.canary().setProbe(() -> {
        });
        probe.canaryProbeAll();
        boolean ok = good.enabled() && good.canaryCount() == 1 && !bad.enabled() && bad.disabledReason().contains("计数未变");
        LOG.info("[cava] 金丝雀框架自检结果: {}（good.enabled={} good.count={} bad.enabled={} bad.reason=\"{}\")",
                ok ? "PASS" : "FAIL", good.enabled(), good.canaryCount(), bad.enabled(), bad.disabledReason());
        return ok;
    }

    /** 启动报告用的一行行状态。 */
    public synchronized List<String> describeAll() {
        List<String> lines = new ArrayList<>();
        for (CavaSubsystem s : subsystems) {
            lines.add(s.describe());
        }
        return lines;
    }
}
