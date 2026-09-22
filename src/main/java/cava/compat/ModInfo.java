package cava.compat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个被探测到的 mod 的元数据（只从它自己的 {@code fabric.mod.json} 读，不假设任何东西）。
 *
 * <p>来源有两种：真实游戏里的 Fabric Loader（{@code ModProbe.fromLoader}）与
 * 离线 jar 目录（{@code ModProbe.fromDirectory}，单测与离线报告用）。
 */
public record ModInfo(String id, String version, String environment, List<String> mixins,
        Map<String, Object> custom, String source) {

    public ModInfo {
        mixins = mixins == null ? List.of() : List.copyOf(mixins);
        custom = custom == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(custom));
    }

    /** 是否声明了某个 custom 键（例如 {@code lithium:options}）。 */
    public boolean hasCustom(String key) {
        return custom.containsKey(key);
    }

    /** custom 键的原始值（未做类型假设）。 */
    public Object customValue(String key) {
        return custom.get(key);
    }

    /** {@code environment} 字段；缺省按 {@code "*"}。 */
    public String environmentOrDefault() {
        return environment == null || environment.isEmpty() ? "*" : environment;
    }

    /** 是否只在客户端加载（专用服务器不会加载它）。 */
    public boolean isClientOnly() {
        return "client".equals(environmentOrDefault());
    }

    /** 一行报告用文本；竖线一律换掉，避免破坏报告的分隔符。 */
    public String describe() {
        return id + " " + version + " env=" + environmentOrDefault() + " src=" + source;
    }
}
