package cava.harden;

import cava.ffm.CavaNative;
import cava.ffm.NativeStatus;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 加固层的独立命令行探针（不依赖 Minecraft，不依赖 Fabric，<b>也不引用 {@code java.lang.foreign}</b>
 * —— 契约要求 FFM 只出现在 {@code cava.ffm}，所以真正的原生调用走
 * {@link CavaNative#probePathfind(long, int)} 这个 ffm 侧的小诊断入口）。
 *
 * <p>用途（P4-A 的验收证据就是它的真实输出）：
 * <ol>
 *   <li><b>回滚实测</b>：{@code -Dcava.native.enabled=false} ⇒ 状态必须是
 *       {@link NativeStatus#DISABLED_BY_FLAG}、可用性 false、P1 入口返回
 *       {@link NativeCallGuard#ERR_NATIVE_UNAVAILABLE}，<b>零 ERROR</b>。</li>
 *   <li><b>加固计数可查询</b>：打印 {@link CavaNative#hardeningReport()}。</li>
 *   <li><b>熔断现场演示</b>（{@code breaker} 参数）：用伪造句柄连续打 N 次，
 *       打印每次的返回码、状态与原生尝试计数，证明"熔断后不再尝试原生"且只打 1 条 ERROR。</li>
 * </ol>
 *
 * <p>用法：
 * <pre>
 *   java --enable-preview --enable-native-access=ALL-UNNAMED -cp &lt;classes;deps&gt; \
 *        -Dcava.native.path=natives/windows-x64/cava.dll cava.harden.HardenProbe [breaker]
 * </pre>
 * 退出码：0 = 与期望一致；1 = 与期望不符；2 = 探针自身抛异常。
 */
public final class HardenProbe {

    private HardenProbe() {
    }

    private static int failures;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("[harden-probe] 探针自身抛出异常: " + t);
            t.printStackTrace(System.out);
            System.exit(2);
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(String[] args) {
        boolean breakerDemo = false;
        for (String a : args) {
            if ("breaker".equalsIgnoreCase(a)) {
                breakerDemo = true;
            }
        }

        CavaNative nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "harden-probe");
        boolean open = nat.tryOpen();

        line("=== Cava HardenProbe ===");
        line("cava.native.enabled     : " + System.getProperty(CavaNative.PROP_ENABLED, "(未设置 → 默认 true)"));
        line("breaker.threshold       : " + nat.breakerThreshold()
                + "   (-D" + CircuitBreaker.PROP_THRESHOLD + ")");
        line("watchdog.threshold      : " + CallWatchdog.fmtMicros(nat.watchdogThresholdNanos())
                + "   (-D" + CallWatchdog.PROP_THRESHOLD_MICROS + ")");
        line("status                  : " + nat.status());
        line("available()             : " + nat.available());
        line("detail                  : " + nat.detail());
        if (open) {
            line("build_id                : " + nat.buildId());
            line("handle                  : " + nat.handle());
        }

        if (!open && nat.status() == NativeStatus.DISABLED_BY_FLAG) {
            line("");
            line("回滚语义：-D" + CavaNative.PROP_ENABLED + "=false ⇒ DISABLED_BY_FLAG（正常路径，不是错误）");
            expect(!nat.available(), "未打开时 available() 必须是 false");
        } else if (!open) {
            line("");
            line("注意：原生不是被 flag 关掉的，而是加载失败 —— status=" + nat.status());
            expect(!nat.available(), "未打开时 available() 必须是 false");
        } else {
            expect(nat.status() == NativeStatus.OPEN, "原生打开时状态必须是 OPEN，实际 " + nat.status());
        }

        // ---- 原生入口行为（回滚实验的核心断言） ----
        line("");
        line("--- 原生入口行为（pathfind，全 0 请求 + 64 张节点）---");
        int rc = nat.probePathfind(nat.handle(), 64);
        line("pathfind(handle=" + nat.handle() + ") -> " + rc);
        if (!open) {
            expect(rc == NativeCallGuard.ERR_NATIVE_UNAVAILABLE,
                    "回滚后 P1 入口必须返回 ERR_NATIVE_UNAVAILABLE(" + NativeCallGuard.ERR_NATIVE_UNAVAILABLE
                            + ")，实际 " + rc);
        } else {
            expect(rc < 0, "原生可用但请求非法（未上传档案）时必须返回错误码，实际 " + rc);
        }

        // ---- 熔断现场演示（可选；会真的熔断本进程的原生路径） ----
        if (breakerDemo) {
            int threshold = nat.breakerThreshold();
            expect(threshold > 0, "熔断阈值必须 > 0（实际 " + threshold + "）");

            line("");
            line("--- A) 软失败：契约内的拒绝不熔断（真实原生调用，伪造句柄） ---");
            long attemptsBeforeSoft = nat.hardening().attempts();
            for (int i = 1; i <= 6; i++) {
                int r = nat.probePathfind(0xDEAD_BEEFL, 4);
                line(String.format(Locale.ROOT,
                        "  第 %d 次 pathfind(伪造句柄) -> %d | tripped=%s softFailures=%d attempts=%d",
                        i, r, nat.hardening().breaker().tripped(),
                        nat.hardening().breaker().softFailures(), nat.hardening().attempts()));
            }
            expect(!nat.hardening().breaker().tripped(),
                    "CAVA_ERR_NULL 是调用方输入问题（ABI 明文）⇒ 软失败，不许熔断");
            expect(nat.hardening().attempts() - attemptsBeforeSoft == 6, "软失败照样是真实原生调用");
            expect(nat.hardening().breaker().errorEmissions() == 0, "软失败不打 ERROR");

            line("");
            line("--- B) 硬失败：注入 CAVA_ERR_INTERNAL(-6)（模拟「原生自己坏了」）---");
            long attemptsBefore = nat.hardening().attempts();
            for (int i = 1; i <= threshold + 2; i++) {
                int r = nat.hardening().call("cava_pathfind", () -> NativeCallGuard.CODE_INTERNAL);
                line(String.format(Locale.ROOT,
                        "  第 %d 次注入 -> %d | status=%-20s attempts=%d tripped=%s errorEmits=%d",
                        i, r, nat.status(), nat.hardening().attempts(),
                        nat.hardening().breaker().tripped(), nat.hardening().breaker().errorEmissions()));
            }
            expect(nat.status() == NativeStatus.DISABLED_BY_BREAKER,
                    "连续 " + threshold + " 次硬失败后状态必须是 DISABLED_BY_BREAKER，实际 " + nat.status());
            expect(nat.hardening().breaker().errorEmissions() == 1,
                    "熔断只允许打 1 条 ERROR，实际 " + nat.hardening().breaker().errorEmissions());

            line("");
            line("--- C) 熔断之后：连真实入口都不再尝试 ---");
            long attemptsAtTrip = nat.hardening().attempts();
            long unavailBefore = nat.hardening().unavailableCalls();
            boolean allUnavailable = true;
            for (int i = 0; i < 100; i++) {
                allUnavailable &= (nat.probePathfind(nat.handle(), 64) == NativeCallGuard.ERR_NATIVE_UNAVAILABLE);
            }
            line("  熔断后连打 100 次真实 pathfind(有效句柄)：全部 -100 = " + allUnavailable
                    + "；原生尝试次数 " + nat.hardening().attempts() + "（熔断时 " + attemptsAtTrip + "）");
            expect(allUnavailable, "熔断后 100 次调用必须全部返回 -100 = ERR_NATIVE_UNAVAILABLE");
            expect(nat.hardening().attempts() == attemptsAtTrip, "熔断后原生尝试次数必须一动不动");
            expect(nat.hardening().unavailableCalls() >= 100,
                    "原生回退计数必须增长（这是上线验收要看的那个数），实际 "
                            + nat.hardening().unavailableCalls());
        }

        line("");
        line(nat.hardeningReport());
        line("");
        line("HARDEN-PROBE: " + (failures == 0 ? "PASS" : "FAIL (" + failures + ")"));
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            line("  !! 断言失败: " + what);
        } else {
            line("  ok: " + what);
        }
    }

    private static void line(String s) {
        System.out.println(s);
    }
}
