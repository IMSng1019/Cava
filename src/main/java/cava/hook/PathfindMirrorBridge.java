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
 * 把镜像流（P1-Java-A）的 {@link RegionSource} 实现接进注入流。
 *
 * <p><b>为什么用反射而不是直接 new</b>：接口（{@code cava.mirror.RegionSource}）是 captain 冻结的，
 * 但实现在**另一个并行流**里，且它是**按世界**构造的（{@code RegionMirror.forWorld(ServerWorld)}）。
 * 直接 {@code import} 会让注入流在镜像流改动时编译失败；反射让"实现还没到/换了形状"退化成
 * **运行期回退原逻辑**，而不是编译期停摆 —— 这正是契约 §3 要求的回退语义。
 *
 * <p>查找顺序（类名由 {@code cava.mirror.class} 指定，默认 {@code cava.mirror.RegionMirror}）：
 * <ol>
 *   <li>显式注入的实例（{@link #override}，给单测与将来的启动接线用）；</li>
 *   <li>{@code public static RegionSource forWorld(ServerWorld)}（A 的实际形状，按世界缓存）；</li>
 *   <li>{@code public static RegionSource instance()} / {@code getInstance()}（单例形状）；</li>
 *   <li>{@code public RegionSource()}（无参构造）。</li>
 * </ol>
 *
 * <p><b>接口边界请求（已上报 captain）</b>：{@code forWorld} / {@code pushForSolve} 都**不在**冻结接口里，
 * 注入流只能靠反射撞。建议把"按世界取镜像"提升为冻结契约的一部分。
 */
public final class PathfindMirrorBridge {

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private static volatile boolean resolvedGlobal;
    private static volatile RegionSource globalInstance;
    private static volatile String problem = "(尚未解析)";
    private static final Map<World, RegionSource> PER_WORLD = new ConcurrentHashMap<>();
    private static volatile boolean explicitOverride;
    private static volatile Method forWorldMethod;
    private static volatile boolean forWorldProbed;

    private PathfindMirrorBridge() {
    }

    /**
     * 解析当前世界对应的镜像实现。**任何异常都不逃逸**（回退语义：拿不到镜像 = 不接管）。
     *
     * @param world 当前寻路所在的世界（注入点里来自 {@code mob.getWorld()}）；可为 null
     */
    public static RegionSource get(World world) {
        if (explicitOverride) {
            return globalInstance;
        }
        if (world instanceof ServerWorld sw) {
            RegionSource cached = PER_WORLD.get(world);
            if (cached != null) {
                return cached;
            }
            RegionSource made = tryForWorld(sw);
            if (made != null) {
                PER_WORLD.put(world, made);
                return made;
            }
        }
        return getGlobal();
    }

    /** 不依赖世界的解析（供启动日志/无世界上下文使用）。 */
    public static RegionSource getGlobal() {
        if (explicitOverride) {
            return globalInstance;
        }
        if (resolvedGlobal) {
            return globalInstance;
        }
        synchronized (PathfindMirrorBridge.class) {
            if (resolvedGlobal) {
                return globalInstance;
            }
            try {
                globalInstance = resolveGlobal();
                problem = globalInstance == null ? "(未发现可直接构造的实现；按世界构造的形状走 forWorld)" : "OK";
            } catch (Throwable t) {
                globalInstance = null;
                problem = t.getClass().getName() + ": " + t.getMessage();
                LOG.warn("[cava/pathfind] 解析镜像实现失败（回退原逻辑）：{}", problem);
            }
            resolvedGlobal = true;
            return globalInstance;
        }
    }

    /** 解析状态一行摘要（启动日志用）。 */
    public static String describe() {
        getGlobal();
        String fw = forWorldProbed ? (forWorldMethod != null ? "forWorld(ServerWorld)=found" : "forWorld(ServerWorld)=absent")
                : "forWorld(ServerWorld)=unprobed";
        return "mirrorClass=" + PathfindSwitches.mirrorClassName()
                + " global=" + (globalInstance != null)
                + " perWorld=" + PER_WORLD.size()
                + " " + fw
                + " problem=" + problem;
    }

    /** 显式注入（单测 / 将来的启动接线）。 */
    public static void override(RegionSource source) {
        synchronized (PathfindMirrorBridge.class) {
            globalInstance = source;
            resolvedGlobal = true;
            explicitOverride = source != null;
            problem = source == null ? "(显式置空)" : "OK(显式注入)";
        }
    }

    /** 测试用：忘掉缓存。 */
    public static void reset() {
        synchronized (PathfindMirrorBridge.class) {
            resolvedGlobal = false;
            globalInstance = null;
            explicitOverride = false;
            PER_WORLD.clear();
            forWorldMethod = null;
            forWorldProbed = false;
            problem = "(尚未解析)";
        }
    }

    private static RegionSource tryForWorld(ServerWorld world) {
        Method m = forWorld();
        if (m == null) {
            return null;
        }
        try {
            Object o = m.invoke(null, world);
            if (o instanceof RegionSource rs) {
                LOG.info("[cava/pathfind] 镜像实现已接入（按世界）：{} world={}", rs.getClass().getName(), world.getRegistryKey().getValue());
                return rs;
            }
            return null;
        } catch (Throwable t) {
            LOG.warn("[cava/pathfind] forWorld(world) 调用失败（回退原逻辑）：{}", t.toString());
            return null;
        }
    }

    private static Method forWorld() {
        if (forWorldProbed) {
            return forWorldMethod;
        }
        synchronized (PathfindMirrorBridge.class) {
            if (forWorldProbed) {
                return forWorldMethod;
            }
            try {
                Class<?> clazz = Class.forName(PathfindSwitches.mirrorClassName(), true,
                        PathfindMirrorBridge.class.getClassLoader());
                Method m = clazz.getMethod("forWorld", ServerWorld.class);
                if (Modifier.isStatic(m.getModifiers()) && RegionSource.class.isAssignableFrom(m.getReturnType())) {
                    forWorldMethod = m;
                }
            } catch (Throwable ignored) {
                forWorldMethod = null;
            }
            forWorldProbed = true;
            return forWorldMethod;
        }
    }

    private static RegionSource resolveGlobal() throws Exception {
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
