package cava.mirror;

import net.minecraft.server.world.ServerWorld;

/**
 * 镜像侧实例的**唯一公开入口**（captain 冻结，2026-09-22）。
 *
 * <p><b>为什么需要这个类</b>：{@link RegionSource} 是接口，注入流需要一个**契约层面的、
 * 不依赖实现类名**的办法拿到实例。此前冻结契约里没有这个入口，注入流的处置是
 * "按类名试工厂方法"的反射 —— 而**反射成功靠运气，和静默回退是同一种失败模式**
 * （实测表现：`reasons={mirror-missing=20201}`、`nativeCalls=0`，原生一次都没被调用）。
 *
 * <p>把入口固化在这里之后：
 * <ul>
 *   <li>注入流只依赖本类，**不需要反射、也不需要知道实现类叫什么**；</li>
 *   <li>镜像流换实现类不影响调用方。</li>
 * </ul>
 *
 * <p><b>生命周期</b>：进程内单例。{@link #forWorld(ServerWorld)} 会（重新）绑定世界，
 * 同一世界重复调用必须**幂等**；不同世界调用即切换绑定。
 * 绑定是**跨世界切换安全**的：注入流每次求解前都会重新绑定它当前正在用的世界。
 */
public final class MirrorFactory {

    private MirrorFactory() {
    }

    private static volatile RegionSource instance;

    private static RegionSource fallback() {
        // 单一实现。将来若有多种实现，在这里按平台/配置选择，调用方不受影响。
        return RegionMirror.create();
    }

    /**
     * 取进程内单例（未绑定任何世界）。
     *
     * <p>注意：只用它取实例；真正求解前**必须**先 {@link #forWorld(ServerWorld)}，
     * 否则镜像侧会以"未绑定世界"为由让本次求解回退（这是预期行为，不是错误）。
     */
    public static RegionSource instance() {
        RegionSource local = instance;
        if (local == null) {
            synchronized (MirrorFactory.class) {
                local = instance;
                if (local == null) {
                    local = fallback();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 绑定到指定世界并返回同一实例。**幂等**：同一个世界重复调用不产生副作用。
     *
     * <p>这是注入流在每次求解前应当调用的入口。
     */
    public static RegionSource forWorld(ServerWorld world) {
        RegionSource src = instance();
        src.bind(world);
        return src;
    }
}
