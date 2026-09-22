package cava.compat;

import cava.CavaConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * VMP 适配器：复刻 {@code Entity.move} 的零位移短路。
 *
 * <p><b>证据（本机实读，不是记忆）</b>：
 * <ul>
 *   <li>{@code vmp.mixins.json} 的 {@code mixins} 数组里有
 *       {@code entity.move_zero_velocity.MixinEntity}（package {@code com.ishland.vmp.mixins}）。</li>
 *   <li>该类的 {@code @Inject} 元数据：{@code method=["move"], at=HEAD, cancellable=true}；
 *       {@code @Mixin(class_1297)}；另一个 {@code @Inject(method="setBoundingBox", at=HEAD)}。</li>
 *   <li>refmap {@code vmp-fabric-mc1.20.4-refmap.json} 实读：
 *       {@code "move" -> Lnet/minecraft/class_1297;method_5784(Lnet/minecraft/class_1313;Lnet/minecraft/class_243;)V}、
 *       {@code "setBoundingBox" -> Lnet/minecraft/class_1297;method_5857(Lnet/minecraft/class_238;)V}。</li>
 *   <li><b>不可配置</b>：{@code VMPMixinPlugin.shouldApplyMixin} 的常量池里<b>没有任何</b>以
 *       {@code com.ishland.vmp.mixins.entity.move_zero_velocity} 开头的门控前缀
 *       （其它的都有，例如 {@code ...playerwatching.optimize_nearby_entity_tracking_lookups}）；也没有
 *       {@code vmp.properties} 里的开关项（实读 testbed 的 vmp.properties 确认）。</li>
 * </ul>
 *
 * <p><b>零位移短路的确切条件（javap -c 逐条对齐）</b>：
 * <pre>
 *   // MixinEntity.onMove(MovementType, Vec3d, CallbackInfo)  @Inject(HEAD, cancellable)
 *   if (!this.boundingBoxChanged &amp;&amp; movement.equals(Vec3d.ZERO)) {
 *       ci.cancel();
 *       this.boundingBoxChanged = false;          // 取消后复位
 *   }
 *   // MixinEntity.onBoundingBoxChanged(Box, CallbackInfo)   @Inject(method="setBoundingBox", HEAD)
 *   if (!this.shadowBoundingBox.equals(boundingBox)) this.boundingBoxChanged = true;
 * </pre>
 * {@code Vec3d.equals} 用 {@code Double.compare} 逐分量比较（javap 实证），
 * 所以 <b>{@code -0.0} 不等于 {@code 0.0}</b> —— 复刻时必须用 {@link Double#compare}，不能用 {@code ==}。
 *
 * <p>本类不引用任何 Minecraft 类型。
 */
public final class VmpAdapter {

    public static final String MOD_ID = "vmp";
    public static final String MIXIN_CONFIG = "vmp.mixins.json";
    public static final String MIXIN_PACKAGE = "com.ishland.vmp.mixins";
    public static final String MIXIN_ENTITY_CLASS = MIXIN_PACKAGE + ".entity.move_zero_velocity.MixinEntity";
    public static final String MIXIN_ENTITY_SIMPLE = "entity.move_zero_velocity.MixinEntity";
    public static final String PLUGIN_CLASS = MIXIN_PACKAGE + ".VMPMixinPlugin";

    /** {@code Entity.move(MovementType, Vec3d)V}。 */
    public static final String INTERMEDIARY_MOVE = "method_5784";

    /** {@code Entity.setBoundingBox(Box)V}。 */
    public static final String INTERMEDIARY_SET_BOUNDING_BOX = "method_5857";

    private VmpAdapter() {
    }

    /** 探测结果。 */
    public record Schema(boolean mixinDeclared, boolean gatedByPlugin, List<String> gatePrefixes,
            boolean moveTargetInRefmap, boolean setBoundingBoxInRefmap, String refmapName) {

        public Schema {
            gatePrefixes = List.copyOf(gatePrefixes);
        }

        /** 声明了、且插件门控里没有它 → 无条件生效（= 不可配置）。 */
        public boolean unconditional() {
            return mixinDeclared && !gatedByPlugin;
        }

        public String describe() {
            return "declared=" + mixinDeclared + " gated=" + gatedByPlugin + " prefixes=" + gatePrefixes.size()
                    + " refmap=" + refmapName + "(move=" + moveTargetInRefmap + ",setBoundingBox="
                    + setBoundingBoxInRefmap + ")";
        }
    }

    /** 离线探测：读 jar 里的 mixins.json + 插件类 + refmap。 */
    public static Optional<Schema> probeJar(Path jar) {
        if (jar == null || !java.nio.file.Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String mixinJson = readEntry(zip, MIXIN_CONFIG);
            if (mixinJson == null) {
                return Optional.empty();
            }
            Map<String, Object> cfg = CavaConfig.parseObject(mixinJson);
            String pkg = cfg.get("package") instanceof String s ? s : MIXIN_PACKAGE;
            boolean declared = false;
            if (cfg.get("mixins") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s && (s.equals(MIXIN_ENTITY_SIMPLE) || (pkg + "." + s).equals(MIXIN_ENTITY_CLASS))) {
                        declared = true;
                    }
                }
            }
            String refmapName = cfg.get("refmap") instanceof String s ? s : "";
            List<String> prefixes = new ArrayList<>();
            Optional<ClassFileProbe.Info> plugin = ClassFileProbe.readFromJar(jar, PLUGIN_CLASS.replace('.', '/'));
            if (plugin.isPresent()) {
                for (String c : plugin.get().utf8Constants()) {
                    if (c.startsWith(MIXIN_PACKAGE + ".") && !c.equals(PLUGIN_CLASS) && c.length() > MIXIN_PACKAGE.length() + 1) {
                        if (!prefixes.contains(c)) {
                            prefixes.add(c);
                        }
                    }
                }
            }
            boolean gated = false;
            for (String p : prefixes) {
                if (MIXIN_ENTITY_CLASS.startsWith(p) || MIXIN_ENTITY_CLASS.equals(p)) {
                    gated = true;
                }
            }
            boolean moveOk = false;
            boolean boxOk = false;
            if (!refmapName.isEmpty()) {
                String refmap = readEntry(zip, refmapName);
                if (refmap != null) {
                    Map<String, String> mapping = refmapEntry(refmap, MIXIN_ENTITY_CLASS);
                    moveOk = mapping.getOrDefault("move", "").contains(INTERMEDIARY_MOVE);
                    boxOk = mapping.getOrDefault("setBoundingBox", "").contains(INTERMEDIARY_SET_BOUNDING_BOX);
                }
            }
            return Optional.of(new Schema(declared, gated, prefixes, moveOk, boxOk, refmapName));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** refmap 里某个 mixin 类的方法映射（{@code sourceName -> intermediary 签名}）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, String> refmapEntry(String refmapJson, String mixinClassName) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> root = CavaConfig.parseObject(refmapJson);
        Object mappings = root.get("mappings");
        if (mappings instanceof Map<?, ?> m) {
            // refmap 的键是**内部名**（a/b/C），调用方通常传点号名 -> 两种分隔符都试
            Object entry = m.get(mixinClassName);
            if (entry == null) {
                entry = m.get(mixinClassName.replace('.', '/'));
            }
            if (entry == null) {
                entry = m.get(mixinClassName.replace('/', '.'));
            }
            if (entry instanceof Map<?, ?> em) {
                for (Map.Entry<?, ?> e : em.entrySet()) {
                    if (e.getValue() instanceof String s) {
                        out.put(String.valueOf(e.getKey()), s);
                    }
                }
            }
        }
        return out;
    }

    private static String readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 纯函数：复刻 {@code onMove} 的取消判定。
     *
     * <p>{@code movement.equals(Vec3d.ZERO)} 用 {@code Double.compare} 逐分量比较。
     */
    public static boolean shouldCancelMove(boolean boundingBoxChanged, double x, double y, double z) {
        return !boundingBoxChanged && Double.compare(x, 0.0d) == 0 && Double.compare(y, 0.0d) == 0
                && Double.compare(z, 0.0d) == 0;
    }

    /** 纯状态机：把 mixin 的两个字段搬过来，单测可以整段回放。 */
    public static final class State {

        private boolean boundingBoxChanged;

        public boolean boundingBoxChanged() {
            return boundingBoxChanged;
        }

        /** {@code onMove}：返回 true 表示应当 cancel，并在 cancel 时复位标志。 */
        public boolean onMove(double x, double y, double z) {
            if (shouldCancelMove(boundingBoxChanged, x, y, z)) {
                boundingBoxChanged = false;
                return true;
            }
            return false;
        }

        /** {@code onBoundingBoxChanged}：包围盒变了才置位。 */
        public void onSetBoundingBox(boolean equalsPrevious) {
            if (!equalsPrevious) {
                boundingBoxChanged = true;
            }
        }
    }

    /** 报告用一行。 */
    public static String reportLine(Optional<Schema> schema, boolean present) {
        if (!present) {
            return "vmp 未安装";
        }
        return schema.map(s -> (s.unconditional() ? "无条件生效（不可配置）" : "被插件门控") + "：" + s.describe())
                .orElse("已安装但无法探测 mixins.json");
    }
}
