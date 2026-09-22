package cava.hook;

import cava.mirror.RegionSource;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把镜像流（P1-Java-A）的 {@link RegionSource} 接进注入流。
 *
 * <p><b>世界绑定走契约方法</b>（captain 2026-09-22 裁决）：拿到实例后一律调用
 * {@link RegionSource#bind(ServerWorld)}，**不再反射去撞实现的内部构造入口**。
 * 之前那版靠反射调 {@code RegionMirror.forWorld(ServerWorld)}，属于"接口不对就用运行期回退掩盖"，已删除。
 *
 * <p><b>仍然保留的一处反射</b>：**如何拿到一个 {@code RegionSource} 实例**。
 * 冻结接口里没有静态工厂，而注入流不能直接 {@code new} 另一个流的具体实现类
 * （编译期耦合 + 并行期一改就断）。所以这里按 {@code cava.mirror.class}（默认 {@code cava.mirror.RegionMirror}）
 * 依次尝试 {@code public static instance()} / {@code getInstance()} / 无参构造。
 * 这三条都只碰**公开的构造入口**，不读任何内部字段。
 * <b>接口请求</b>：建议把 {@code static RegionSource instance()} 写进冻结契约，这段反射就能彻底删掉。
 */
public final class PathfindMirrorBridge {

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private static volatile boolean resolved;
    private static volatile RegionSource instance;
    private static volatile String problem = "(尚未解析)";
    private static final Map<World, RegionSource> PER_WORLD = new ConcurrentHashMap<>();
    private static volatile boolean explicitOverride;

    private PathfindMirrorBridge() {
    }

    /**
     * 取得**已绑定到 {@code world}** 的镜像实现。**任何异常都不逃逸**（回退语义：拿不到 = 不接管）。
     *
     * @param world 当前寻路所在的世界（注入点里来自 {@code mob.getWorld()}）；可为 null
     */
    public static RegionSource get(World world) {
        RegionSource src = resolveInstance();
        if (src == null) {
            return null;
        }
        if (explicitOverride) {
            return src;
        }
        if (world instanceof ServerWorld sw) {
            RegionSource cached = PER_WORLD.get(world);
            if (cached != null) {
                return cached;
            }
            try {
                src.bind(sw);   // 契约方法；实现必须能处理同一世界重复绑定
                PER_WORLD.put(world, src);
                return src;
            } catch (Throwable t) {
                LOG.warn("[cava/pathfind] RegionSource.bind(world) 失败（回退原逻辑）：{}", t.toString());
                return null;
            }
        }
        return src;
    }

    /** 不绑定世界地取实例（启动日志用）。 */
    public static RegionSource getGlobal() {
        return resolveInstance();
    }

    /** 解析状态一行摘要。 */
    public static String describe() {
        resolveInstance();
        return "mirrorClass=" + PathfindSwitches.mirrorClassName()
                + " instance=" + (instance != null)
                + " boundWorlds=" + PER_WORLD.size()
                + " problem=" + problem;
    }

    /** 显式注入（单测 / 将来的启动接线）。 */
    public static void override(RegionSource source) {
        synchronized (PathfindMirrorBridge.class) {
            instance = source;
            resolved = true;
            explicitOverride = source != null;
            problem = source == null ? "(显式置空)" : "OK(显式注入)";
        }
    }

    /** 测试用：忘掉缓存。 */
    public static void reset() {
        synchronized (PathfindMirrorBridge.class) {
            resolved = false;
            instance = null;
            explicitOverride = false;
            PER_WORLD.clear();
            problem = "(尚未解析)";
        }
    }

    private static RegionSource resolveInstance() {
        if (resolved) {
            return instance;
        }
        synchronized (PathfindMirrorBridge.class) {
            if (resolved) {
                return instance;
            }
            try {
                instance = construct();
                problem = instance == null
                        ? "(未发现实例入口：需要 public static instance()/getInstance() 或无参构造)"
                        : "OK";
            } catch (Throwable t) {
                instance = null;
                problem = t.getClass().getName() + ": " + t.getMessage();
                LOG.warn("[cava/pathfind] 解析镜像实现失败（回退原逻辑）：{}", problem);
            }
            resolved = true;
            if (instance != null) {
                LOG.info("[cava/pathfind] 镜像实现已接入：{}", instance.getClass().getName());
            }
            return instance;
        }
    }

    private static RegionSource construct() throws Exception {
        String className = PathfindSwitches.mirrorClassName();
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, PathfindMirrorBridge.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
        if (!RegionSource.class.isAssignableFrom(clazz)) {
            problem = className + " 不是 RegionSource 的实现";
            LOG.warn("[cava/pathfind] {} —— 回退原逻辑", problem);
            return null;
        }
        for (String factory : new String[]{"instance", "getInstance"}) {
            try {
                Method m = clazz.getMethod(factory);
                if (Modifier.isStatic(m.getModifiers()) && RegionSource.class.isAssignableFrom(m.getReturnType())) {
                    return (RegionSource) m.invoke(null);
                }
            } catch (NoSuchMethodException ignored) {
                // 试下一个
            }
        }
        try {
            Constructor<?> ctor = clazz.getConstructor();
            return (RegionSource) ctor.newInstance();
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
