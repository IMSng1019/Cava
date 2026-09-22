package cava.hook;

import java.lang.reflect.Method;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生物档案的**归属桥**：确定"谁把 {@code CavaMobProfile} 推给原生"。
 *
 * <p><b>背景（重复劳动的处置，已上报 captain）</b>：captain 冻结的
 * {@code RegionSource.uploadProfileForSolve(handle, profileKey)} 语义是"镜像流负责推档案"，
 * 而镜像流（P1-Java-A）确实实现了它（{@code cava.mirror.McMobProfileCapture} +
 * {@code cava.mirror.MobProfiles} 注册表）。但**档案里的位姿只有注入点才拿得到**，
 * 所以两边都会长出"从 {@code MobEntity} 造档案"的代码。
 *
 * <p>本类给出的裁决（可被 captain 一句话推翻）：
 * <ol>
 *   <li>**优先走镜像流**：反射调 {@code McMobProfileCapture.of(mob, world)} →
 *       {@code MobProfiles.define(spec)} 拿到 {@code profileKey}，然后
 *       {@code mirror.uploadProfileForSolve(handle, key)} 由镜像流完成上传。
 *       此时注入流**不再自己上传**（绝不两边各推一份）。</li>
 *   <li>镜像流不可用（类不存在 / 抛异常 / 尚未落盘）时，才退化到注入流自带的
 *       {@link MobInputs} + {@code CavaNative.mobProfileUpload}（{@link MobProfileData}）。</li>
 * </ol>
 *
 * <p>两条路**互斥**，所以不存在"同时推两份"的窗口。
 */
public final class PathfindProfileBridge {

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private static volatile boolean probed;
    private static volatile Method captureOf;
    private static volatile Method define;
    private static volatile String problem = "(尚未解析)";

    private PathfindProfileBridge() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        synchronized (PathfindProfileBridge.class) {
            if (probed) {
                return;
            }
            try {
                ClassLoader cl = PathfindProfileBridge.class.getClassLoader();
                Class<?> capture = Class.forName("cava.mirror.McMobProfileCapture", false, cl);
                Class<?> profiles = Class.forName("cava.mirror.MobProfiles", false, cl);
                Method of = capture.getMethod("of", MobEntity.class, ServerWorld.class);
                Method def = profiles.getMethod("define", of.getReturnType());
                captureOf = of;
                define = def;
                problem = "OK";
            } catch (Throwable t) {
                captureOf = null;
                define = null;
                problem = t.getClass().getSimpleName() + ": " + t.getMessage();
                LOG.info("[cava/pathfind] 镜像流的生物档案接口不可用（{}）—— "
                        + "退化到注入流自带的档案上传（两条路互斥，不会重复推送）", problem);
            }
            probed = true;
        }
    }

    /** 镜像流是否提供档案服务。 */
    public static boolean mirrorProvidesProfile() {
        probe();
        return captureOf != null && define != null;
    }

    public static String describe() {
        probe();
        return "mirrorProfile=" + (mirrorProvidesProfile() ? "available" : "unavailable(" + problem + ")");
    }

    /**
     * 走镜像流：抓档案 → 注册 → 返回 profileKey。
     *
     * @return profileKey；镜像流不可用或注册失败时返回 {@code null}（调用方退化/回退）
     */
    public static Long defineViaMirror(MobEntity mob, ServerWorld world) {
        probe();
        Method of = captureOf;
        Method def = define;
        if (of == null || def == null) {
            return null;
        }
        try {
            Object spec = of.invoke(null, mob, world);
            if (spec == null) {
                return null;
            }
            Object key = def.invoke(null, spec);
            return key instanceof Long l ? l : null;
        } catch (Throwable t) {
            LOG.warn("[cava/pathfind] 镜像流档案注册失败（本次退化到注入流档案）：{}", t.toString());
            return null;
        }
    }
}
