package cava.push;

import cava.compat.ServerCoreAdapter;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2 第 2 核的开关、计数与参照比对。**任何失败路径都只会让调用方走原版，绝不改变行为。**
 *
 * <p>模式（{@code -Dcava.push.mode}）：
 * <ul>
 *   <li>{@code off}（默认）：什么都不做。</li>
 *   <li>{@code shadow}：算原生 + 算 Java 参照，逐位比对，**不接管**。</li>
 *   <li>{@code live}：原生结果**真的接管**（cancel + 自己调 addVelocity）。</li>
 * </ul>
 *
 * <p>{@code -Dcava.push.verify=true}：额外做一次"原生预测 vs 原版实际造成的速度增量"的比对。
 * 打开它时**不接管**（否则观察不到原版），所以它证明的是"原生值 == 原版值"，不是"接管了"。
 * 两者必须分开说 —— 这是 P2 上一轮定下的措辞纪律。
 *
 * <p>{@code -Dcava.push.canary=zero-push}：**只允许诊断用**。live 下把推挤位移强制成 (0,0)。
 * 它的作用是证明"这段代码真的在起作用"—— 能红的测试才是测试。
 */
public final class PushRuntime {

    private static final Logger LOG = LoggerFactory.getLogger("cava/push");

    public static final String PROP_MODE = "cava.push.mode";
    public static final String PROP_CANARY = "cava.push.canary";
    public static final String PROP_VERIFY = "cava.push.verify";
    public static final String PROP_BENCH = "cava.push.bench";
    public static final String PROP_BROADPHASE = "cava.push.broadphase";

    /** 推挤内核的模式（off / shadow / live）。 */
    public static final String MODE = norm(System.getProperty(PROP_MODE, "off"));
    /** 区段 broadphase 的模式（off / shadow / live）。默认跟随 {@link #MODE}。 */
    public static final String BROADPHASE_MODE = norm(System.getProperty(PROP_BROADPHASE, MODE));
    public static final boolean VERIFY = bool(System.getProperty(PROP_VERIFY, "false"));
    public static final boolean BENCH = bool(System.getProperty(PROP_BENCH, "false"));
    public static final String CANARY = norm(System.getProperty(PROP_CANARY, "off"));

    // ---- 推挤 ----
    public static final AtomicLong pushCalls = new AtomicLong();
    public static final AtomicLong pushTakeovers = new AtomicLong();
    public static final AtomicLong pushDeclined = new AtomicLong();
    public static final AtomicLong pushErrors = new AtomicLong();
    public static final AtomicLong pushShadowCompared = new AtomicLong();
    public static final AtomicLong pushShadowMismatch = new AtomicLong();
    public static final AtomicLong pushVerified = new AtomicLong();
    public static final AtomicLong pushVerifyMismatch = new AtomicLong();
    public static final AtomicLong pushVerifyZeroSignOnly = new AtomicLong();
    public static final AtomicLong pushCanary = new AtomicLong();
    public static final AtomicLong pushJavaRefNs = new AtomicLong();
    public static final AtomicLong pushNativeNs = new AtomicLong();
    public static final AtomicLong pushTimed = new AtomicLong();

    // ---- 区段 broadphase ----
    public static final AtomicLong bpCalls = new AtomicLong();
    public static final AtomicLong bpTakeovers = new AtomicLong();
    public static final AtomicLong bpFallback = new AtomicLong();
    public static final AtomicLong bpErrors = new AtomicLong();
    public static final AtomicLong bpDesync = new AtomicLong();
    public static final AtomicLong bpVisited = new AtomicLong();
    public static final AtomicLong bpCanary = new AtomicLong();
    public static final AtomicLong bpShadowCompared = new AtomicLong();
    public static final AtomicLong bpShadowMismatch = new AtomicLong();
    public static final AtomicLong bpNativeNs = new AtomicLong();
    public static final AtomicLong bpVanillaPlanNs = new AtomicLong();
    public static final AtomicLong bpTimed = new AtomicLong();

    private static final ThreadLocal<Boolean> IN_PUSH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** HEAD→RETURN 的暂存（复用，避免热路径分配）。 */
    public static final class Probe {
        public boolean active;
        public double selfX;
        public double selfZ;
        public double otherX;
        public double otherZ;
        public double predSelfDx;
        public double predSelfDz;
        public double predOtherDx;
        public double predOtherDz;
    }

    private static final ThreadLocal<Probe> PROBE = ThreadLocal.withInitial(Probe::new);

    private static volatile boolean hookInstalled;

    private PushRuntime() {
    }

    private static String norm(String s) {
        return s == null ? "off" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean bool(String s) {
        return s != null && (s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes"));
    }

    public static boolean pushLive() {
        return "live".equals(MODE);
    }

    public static boolean pushShadow() {
        return "shadow".equals(MODE);
    }

    public static boolean pushAny() {
        return pushLive() || pushShadow() || VERIFY;
    }

    public static boolean broadphaseLive() {
        return "live".equals(BROADPHASE_MODE);
    }

    public static boolean broadphaseShadow() {
        return "shadow".equals(BROADPHASE_MODE);
    }

    public static boolean reentrant() {
        return Boolean.TRUE.equals(IN_PUSH.get());
    }

    public static void enterPush() {
        IN_PUSH.set(Boolean.TRUE);
    }

    public static void exitPush() {
        IN_PUSH.set(Boolean.FALSE);
    }

    public static Probe probe() {
        return PROBE.get();
    }

    /**
     * ServerCore 的 {@code Entity.addVelocity} HEAD 短路：{@code isInactive && !world.isClient}。
     * 本核**不复制**这份语义，只**预测**它，用来判断"原版这一次到底会不会动速度"。
     * 反射入口由兼容层提供（{@link ServerCoreAdapter#isInactive(Object)}），探测失败一律当没装。
     */
    public static boolean addVelocityWouldBeCancelled(Object entity, boolean worldIsClient) {
        if (!ServerCoreAdapter.available()) {
            return false;
        }
        try {
            return ServerCoreAdapter.shouldCancelAddVelocity(ServerCoreAdapter.isInactive(entity), worldIsClient);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 与 {@code Double.compare == 0} 相同，但把"只差零的符号"单独归类（不计 mismatch）。 */
    public static boolean bitsAgree(double a, double b) {
        if (Double.compare(a, b) == 0) {
            return true;
        }
        return false;
    }

    public static boolean zeroSignOnly(double a, double b) {
        return a == 0.0 && b == 0.0 && Double.compare(a, b) != 0;
    }

    private static final ThreadLocal<int[]> BP_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<long[]> BP_REF = ThreadLocal.withInitial(() -> new long[2048]);

    /**
     * broadphase 的重入深度。**必须要有**：访问计划的 out 缓冲区是每个镜像实例一份，
     * 而 consumer.accept 完全可能触发一次嵌套的 forEachInBox（同一个缓存实例），
     * 那会把外层正在读的 out 冲掉。深度 &gt; 0 时直接让原版跑。
     */
    public static boolean bpReentrant() {
        return BP_DEPTH.get()[0] > 0;
    }

    public static void enterBp() {
        BP_DEPTH.get()[0]++;
    }

    public static void exitBp() {
        BP_DEPTH.get()[0]--;
    }

    /** shadow 模式下"原版访问计划"的参照缓冲（按字节码逐条转写用）。 */
    public static long[] bpRef() {
        return BP_REF.get();
    }

    private static final ThreadLocal<double[]> REF = ThreadLocal.withInitial(() -> new double[3]);

    /** Java 参照实现的暂存（避免热路径分配）。 */
    public static double[] refScratch() {
        return REF.get();
    }

    /** 热路径上的廉价检查：未装钩子才进同步块。 */
    public static void maybeInstall() {
        if (!hookInstalled) {
            installSummaryHook();
        }
    }

    /** 首次进入时装一次终局计数钩子（用 System.out：logback appender 在 shutdown 钩子里可能已关）。 */
    public static synchronized void installSummaryHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        String mode = MODE + "/bp=" + BROADPHASE_MODE + (VERIFY ? "+verify" : "")
                + (BENCH ? "+bench" : "") + ("off".equals(CANARY) ? "" : "+canary=" + CANARY);
        LOG.info("[cava/push] 模式={} 原生入口={}（{}）", mode, NativePush.available(), NativePush.detail());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println(summaryLine()), "cava-push-summary"));
    }

    public static String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("[cava/push] 终局计数 mode=").append(MODE).append(" broadphase=").append(BROADPHASE_MODE)
          .append(VERIFY ? " verify=true" : "").append(" canary=").append(CANARY)
          .append(" | pushCalls=").append(pushCalls.get())
          .append(" pushTakeovers=").append(pushTakeovers.get())
          .append(" pushDeclined=").append(pushDeclined.get())
          .append(" pushErrors=").append(pushErrors.get())
          .append(" pushCanary=").append(pushCanary.get())
          .append(" | shadowCompared=").append(pushShadowCompared.get())
          .append(" shadowMismatch=").append(pushShadowMismatch.get())
          .append(" verified=").append(pushVerified.get())
          .append(" verifyMismatch=").append(pushVerifyMismatch.get())
          .append(" verifyZeroSignOnly=").append(pushVerifyZeroSignOnly.get())
          .append(" | bpCalls=").append(bpCalls.get())
          .append(" bpTakeovers=").append(bpTakeovers.get())
          .append(" bpFallback=").append(bpFallback.get())
          .append(" bpErrors=").append(bpErrors.get())
          .append(" bpDesync=").append(bpDesync.get())
          .append(" bpVisited=").append(bpVisited.get())
          .append(" bpCanary=").append(bpCanary.get())
          .append(" bpCompared=").append(bpShadowCompared.get())
          .append(" bpMismatch=").append(bpShadowMismatch.get());
        long tn = pushTimed.get();
        if (tn > 0) {
            sb.append(" | pushTimed=").append(tn)
              .append(" javaRef=").append(pushJavaRefNs.get() / tn).append("ns")
              .append(" native=").append(pushNativeNs.get() / tn).append("ns");
        }
        long bt = bpTimed.get();
        if (bt > 0) {
            sb.append(" | bpTimed=").append(bt)
              .append(" nativePlan=").append(bpNativeNs.get() / bt).append("ns")
              .append(" vanillaPlan=").append(bpVanillaPlanNs.get() / bt).append("ns");
        }
        return sb.toString();
    }
}
