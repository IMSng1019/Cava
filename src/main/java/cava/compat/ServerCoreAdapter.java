package cava.compat;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ServerCore 适配器：<b>反射 + 软依赖</b>读它的公开接口，复刻它的
 * 「未激活实体不接收推挤」短路与激活范围语义。
 *
 * <p><b>重要勘误（任务书写的是错的，以本机 javap 为准）</b>：任务书说
 * "反射读 {@code Inactive}（方法 {@code servercore$isInactive}）"。实际 javap
 * （{@code javap -p -classpath server-servercore-fabric-1.5.0+1.20.4.jar ...}）是：
 * <pre>
 * public interface ...interfaces.activation_range.Inactive {
 *   public default void servercore$inactiveTick();          // 只有这一个方法
 * }
 * public interface ...interfaces.activation_range.ActivationEntity {
 *   ... public abstract boolean servercore$isInactive();     // isInactive 在这里
 * }
 * </pre>
 * {@code servercore$isInactive()} 由 {@code mixin.features.activation_range.EntityMixin} 实现
 * （同时实现 {@code Inactive} 与 {@code ActivationEntity} 两个接口）。
 * 所以 {@link #isInactive(Object)} 反射的是 <b>ActivationEntity</b>。
 *
 * <p><b>短路语义（javap -c 逐条对齐）</b>：{@code EntityMixin.servercore$ignorePushingWhileInactive(DDD, CallbackInfo)}
 * <pre>
 *   if (this.servercore$isInactive != 0 &amp;&amp; !this.world.isClient) ci.cancel();
 * </pre>
 * 注入点是 {@code Entity.addVelocity(DDD)V}（refmap 实读：
 * {@code "push(DDD)V" -> Lnet/minecraft/class_1297;method_5762(DDD)V}；
 * {@code method_5762} 在 Yarn 里的名字是 <b>{@code addVelocity}</b>，见
 * {@code mappings.tiny} 行 {@code m (DDD)V j method_5762 addVelocity}）。
 *
 * <p><b>为什么必须软依赖</b>：ServerCore 不在编译期依赖里；P2 上线前它是否还装、装的哪个版本
 * 都不能假设 —— 探测失败一律按"没装"处理，让位给它。
 *
 * <p>本类不引用任何 Minecraft 类型（实体与 world 都以 {@code Object}/反射处理）。
 */
public final class ServerCoreAdapter {

    public static final String MOD_ID = "servercore";

    /** 公开接口①：只有 {@code servercore$inactiveTick()}。 */
    public static final String INACTIVE_INTERFACE =
            "me.wesley1808.servercore.common.interfaces.activation_range.Inactive";

    /** 公开接口②：{@code servercore$isInactive()} 在这里。 */
    public static final String ACTIVATION_ENTITY_INTERFACE =
            "me.wesley1808.servercore.common.interfaces.activation_range.ActivationEntity";

    /** 承载实现的 mixin 类。 */
    public static final String MIXIN_ENTITY = "me.wesley1808.servercore.mixin.features.activation_range.EntityMixin";

    public static final String METHOD_INACTIVE_TICK = "servercore$inactiveTick";
    public static final String METHOD_IS_INACTIVE = "servercore$isInactive";

    /** {@code Entity.addVelocity(DDD)V} 的 intermediary 名（Yarn 名 addVelocity，旧称 push）。 */
    public static final String INTERMEDIARY_ADD_VELOCITY = "method_5762";

    /** {@code Entity.move(MovementType, Vec3d)V}。 */
    public static final String INTERMEDIARY_MOVE = "method_5784";

    /** {@code Entity.limitPistonMovement(Vec3d)Vec3d}（ServerCore 的另一个注入点）。 */
    public static final String INTERMEDIARY_LIMIT_PISTON_MOVEMENT = "method_18794";

    private static volatile Method isInactiveMethod;
    private static volatile boolean isInactiveResolved;

    private ServerCoreAdapter() {
    }

    /** 探测结果（全部来自真实 class 文件，不是假设）。 */
    public record Schema(boolean inactiveInterfacePresent, List<String> inactiveInterfaceMethods,
            boolean activationEntityPresent, List<String> activationEntityMethods,
            boolean isInactiveOnActivationEntity, boolean isInactiveOnInactiveInterface,
            boolean addVelocityTargetInRefmap, boolean mixinEntityPresent, String refmapName) {

        public Schema {
            inactiveInterfaceMethods = List.copyOf(inactiveInterfaceMethods);
            activationEntityMethods = List.copyOf(activationEntityMethods);
        }

        /** 我们需要的那个方法确实在 ActivationEntity 上 → 反射路径可用。 */
        public boolean usable() {
            return activationEntityPresent && isInactiveOnActivationEntity;
        }

        public String describe() {
            return "Inactive=" + inactiveInterfacePresent + inactiveInterfaceMethods
                    + " ActivationEntity=" + activationEntityPresent + activationEntityMethods
                    + " isInactive@ActivationEntity=" + isInactiveOnActivationEntity
                    + " isInactive@Inactive=" + isInactiveOnInactiveInterface
                    + " addVelocity@refmap=" + addVelocityTargetInRefmap + "(" + refmapName + ")";
        }
    }

    /** 离线探测：直接读 jar 里的 class 文件（单测用；不需要把对方类加载进 JVM）。 */
    public static Optional<Schema> probeJar(Path jar) {
        try {
            Optional<ClassFileProbe.Info> inact = ClassFileProbe.readFromJar(jar, INACTIVE_INTERFACE.replace('.', '/'));
            Optional<ClassFileProbe.Info> activ = ClassFileProbe.readFromJar(jar, ACTIVATION_ENTITY_INTERFACE.replace('.', '/'));
            Optional<ClassFileProbe.Info> mixin = ClassFileProbe.readFromJar(jar, MIXIN_ENTITY.replace('.', '/'));
            if (inact.isEmpty() && activ.isEmpty() && mixin.isEmpty()) {
                return Optional.empty();
            }
            List<String> im = inact.map(ClassFileProbe.Info::methodNames).orElse(List.of());
            List<String> am = activ.map(ClassFileProbe.Info::methodNames).orElse(List.of());
            // 注解里写的是 Yarn 风格名 "push(DDD)V"，intermediary 号只在 refmap 里 —— 必须读 refmap
            String refmapName = "";
            boolean addVelocity = false;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                var it = zip.entries();
                while (it.hasMoreElements()) {
                    String name = it.nextElement().getName();
                    if (!name.endsWith("refmap.json")) {
                        continue;
                    }
                    try (var in = zip.getInputStream(zip.getEntry(name))) {
                        String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        java.util.Map<String, String> entry = VmpAdapter.refmapEntry(json, MIXIN_ENTITY);
                        if (!entry.isEmpty()) {
                            refmapName = name;
                            for (String v : entry.values()) {
                                if (v.contains(INTERMEDIARY_ADD_VELOCITY)) {
                                    addVelocity = true;
                                }
                            }
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // refmap 读不到就保持 false —— 探测结论必须来自证据，不能猜
            }
            return Optional.of(new Schema(inact.isPresent(), im, activ.isPresent(), am,
                    am.contains(METHOD_IS_INACTIVE), im.contains(METHOD_IS_INACTIVE), addVelocity, mixin.isPresent(),
                    refmapName));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 在线探测：只 {@code Class.forName(..., false, cl)} 接口本身（不加载 Minecraft 类型）。 */
    public static Optional<Schema> probeLoader() {
        ClassLoader cl = contextClassLoader();
        Class<?> inact = loadOrNull(INACTIVE_INTERFACE, cl);
        Class<?> activ = loadOrNull(ACTIVATION_ENTITY_INTERFACE, cl);
        if (inact == null && activ == null) {
            return Optional.empty();
        }
        List<String> im = names(inact);
        List<String> am = names(activ);
        return Optional.of(new Schema(inact != null, im, activ != null, am,
                am.contains(METHOD_IS_INACTIVE), im.contains(METHOD_IS_INACTIVE), false, false, ""));
    }

    private static List<String> names(Class<?> c) {
        List<String> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (Method m : c.getMethods()) {
            out.add(m.getName());
        }
        return out;
    }

    private static Class<?> loadOrNull(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : ServerCoreAdapter.class.getClassLoader();
    }

    /** 解析并缓存 {@code ActivationEntity.servercore$isInactive()}。 */
    private static Method resolveIsInactive() {
        if (isInactiveResolved) {
            return isInactiveMethod;
        }
        synchronized (ServerCoreAdapter.class) {
            if (!isInactiveResolved) {
                Method m = null;
                Class<?> c = loadOrNull(ACTIVATION_ENTITY_INTERFACE, contextClassLoader());
                if (c != null) {
                    try {
                        m = c.getMethod(METHOD_IS_INACTIVE);
                    } catch (NoSuchMethodException ignored) {
                        m = null;
                    }
                }
                isInactiveMethod = m;
                isInactiveResolved = true;
            }
        }
        return isInactiveMethod;
    }

    /** 实体是否处于"未激活"状态。<b>任何异常都返回 false</b>（= 按原版语义继续，安全回退）。 */
    public static boolean isInactive(Object entity) {
        if (entity == null) {
            return false;
        }
        Method m = resolveIsInactive();
        if (m == null) {
            return false;
        }
        try {
            Object r = m.invoke(entity);
            return r instanceof Boolean b && b;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** 是否拿到了可用的反射路径。 */
    public static boolean available() {
        return resolveIsInactive() != null;
    }

    /**
     * 复刻 {@code EntityMixin.servercore$ignorePushingWhileInactive} 的判定：
     * 「未激活 <b>且</b> 不是客户端世界」时取消 {@code Entity.addVelocity}。
     *
     * <p>这一条是纯函数，单测直接对着 javap 的字节码断言。
     */
    public static boolean shouldCancelAddVelocity(boolean inactive, boolean worldIsClient) {
        return inactive && !worldIsClient;
    }

    /** 报告用一行。 */
    public static String reportLine(Optional<Schema> schema, boolean present) {
        if (!present) {
            return "servercore 未安装";
        }
        return schema.map(s -> (s.usable() ? "可用" : "存在但接口形状不符（schema mismatch）") + "：" + s.describe())
                .orElse("已安装但无法探测 class 文件");
    }
}
