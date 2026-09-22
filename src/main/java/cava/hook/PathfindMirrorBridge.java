package cava.hook;

import cava.mirror.MirrorFactory;
import cava.mirror.RegionSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把镜像流的 {@link RegionSource} 接进注入流。
 *
 * <p><b>全程走契约，零反射</b>（captain 2026-09-22 裁决，提交 {@code d014f11}）：
 * 实例入口是 {@link MirrorFactory}，世界绑定用契约方法 {@link RegionSource#bind(ServerWorld)}
 * （{@code MirrorFactory.forWorld} 内部就会调它）。
 *
 * <p><b>这里曾经有两处反射，两处都被实测证伪</b>（都可复现，见 {@code docs/CAVA-p1-inject-notes.md} §3.2/§4.3）：
 * <ol>
 *   <li>反射读实现类的私有字段（{@code forWorld} 的内部形状）——
 *       "成功靠运气"，与静默回退是同一种失败模式；</li>
 *   <li>反射按类名试 {@code instance()/getInstance()/无参构造} ——
 *       A 的 {@code RegionMirror} 当时三个都没有，于是真实服务端上
 *       实测 {@code reasons={mirror-missing=20201}}、{@code nativeCalls=0}。
 *       **它看起来像"子系统缺失"，其实是"缺 API"。**</li>
 * </ol>
 * 现在是编译期强耦合：{@code MirrorFactory} 一旦改名/消失，**编译就红**，
 * 不会再伪装成运行期的静默回退。
 */
public final class PathfindMirrorBridge {

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private static volatile RegionSource explicitOverride;
    private static volatile String lastProblem = "";

    private PathfindMirrorBridge() {
    }

    /**
     * 取得**已绑定到 {@code world}** 的镜像实现。
     *
     * <p>这是"每次求解前"应当调用的入口；{@code MirrorFactory} 保证同一世界重复绑定幂等。
     * <b>任何异常都不逃逸</b>（回退语义：拿不到 = 不接管）。
     *
     * @param world 当前寻路所在的世界（注入点里来自 {@code mob.getWorld()}）；可为 null
     */
    public static RegionSource get(World world) {
        RegionSource over = explicitOverride;
        if (over != null) {
            return over;
        }
        try {
            if (world instanceof ServerWorld sw) {
                return MirrorFactory.forWorld(sw);
            }
            return MirrorFactory.instance();
        } catch (Throwable t) {
            String p = t.getClass().getName() + ": " + t.getMessage();
            if (!p.equals(lastProblem)) {
                lastProblem = p;
                LOG.warn("[cava/pathfind] 取镜像实例/绑定世界失败（回退原逻辑）：{}", p);
            }
            return null;
        }
    }

    /** 不绑定世界地取实例（启动日志用）。 */
    public static RegionSource getGlobal() {
        RegionSource over = explicitOverride;
        if (over != null) {
            return over;
        }
        try {
            return MirrorFactory.instance();
        } catch (Throwable t) {
            lastProblem = t.getClass().getName() + ": " + t.getMessage();
            return null;
        }
    }

    /** 解析状态一行摘要。 */
    public static String describe() {
        RegionSource src = getGlobal();
        return "mirrorFactory=cava.mirror.MirrorFactory"
                + " instance=" + (src != null)
                + " impl=" + (src == null ? "(none)" : src.getClass().getName())
                + (lastProblem.isEmpty() ? "" : " problem=" + lastProblem);
    }

    /** 显式注入（**只给单测**；生产路径一律走 {@link MirrorFactory}）。 */
    public static void override(RegionSource source) {
        explicitOverride = source;
    }

    /** 测试用：清掉显式注入。 */
    public static void reset() {
        explicitOverride = null;
        lastProblem = "";
    }
}
