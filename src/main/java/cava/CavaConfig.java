package cava;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code config/cava.json} 的读写与默认值（任务 C6）。
 *
 * <p>P0 只需要「能被读写 + 能被启动横幅打印出来」：本类不做兼容层判定，只承载开关。
 * 用自带的最小 JSON 解析器（不引第三方依赖），只支持本文件需要的子集：
 * 对象 / 字符串 / 整数 / 布尔。
 *
 * <p>本类不引用任何 Minecraft 类型（便于单测）。
 */
public final class CavaConfig {

    /** 子系统归属决策（契约第 5 节）。 */
    public enum Ownership {
        /** 自动判断（由兼容层决定让位还是原生优先）。 */
        AUTO,
        /** 原生优先（复刻：必要时关掉对方的 mixin 组）。 */
        NATIVE_FIRST,
        /** 让位（不动别人的实现，Cava 该子系统禁用）。 */
        DEFER;

        public static Ownership parse(String raw) {
            if (raw == null) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "native-first", "native_first", "nativefirst", "native" -> NATIVE_FIRST;
                case "defer", "yield", "off" -> DEFER;
                default -> AUTO;
            };
        }

        public String jsonName() {
            return switch (this) {
                case AUTO -> "auto";
                case NATIVE_FIRST -> "native-first";
                case DEFER -> "defer";
            };
        }
    }

    /** P0 的三个子系统 id。 */
    public static final List<String> SUBSYSTEMS = List.of("pathfind", "entity", "redstone");

    private final Path path;
    private boolean nativeEnabled = true;
    private final Map<String, Ownership> ownership = new LinkedHashMap<>();
    private int parityTicks;
    private String parityTrace = "";
    private String parityLabel = "";
    private int parityWorldRadius = 8;
    private String loadNote = "";

    private CavaConfig(Path path) {
        this.path = path;
        for (String id : SUBSYSTEMS) {
            ownership.put(id, Ownership.AUTO);
        }
    }

    /** 读配置；文件不存在则写一份默认值（并在 {@link #loadNote()} 里记录）。 */
    public static CavaConfig load(Path file) {
        CavaConfig c = new CavaConfig(file);
        try {
            if (Files.isRegularFile(file)) {
                c.apply(parseObject(Files.readString(file, StandardCharsets.UTF_8)));
                c.loadNote = "已读取 " + file;
            } else {
                Files.createDirectories(file.getParent());
                c.save();
                c.loadNote = "文件不存在，已写入默认值: " + file;
            }
        } catch (IOException | RuntimeException e) {
            c.loadNote = "读取 " + file + " 失败（沿用默认值）: " + e;
        }
        return c;
    }

    private void apply(Map<String, Object> root) {
        Object nativeObj = root.get("native");
        if (nativeObj instanceof Map<?, ?> m && m.get("enabled") instanceof Boolean b) {
            nativeEnabled = b;
        }
        Object ownObj = root.get("ownership");
        if (ownObj instanceof Map<?, ?> m) {
            for (String id : SUBSYSTEMS) {
                Object v = m.get(id);
                if (v instanceof String s) {
                    ownership.put(id, Ownership.parse(s));
                }
            }
        }
        Object parityObj = root.get("parity");
        if (parityObj instanceof Map<?, ?> m) {
            if (m.get("trace") instanceof String s) {
                parityTrace = s;
            }
            if (m.get("label") instanceof String s) {
                parityLabel = s;
            }
            if (m.get("ticks") instanceof Long l) {
                parityTicks = (int) Math.max(0, Math.min(Integer.MAX_VALUE, l));
            }
            if (m.get("worldRadius") instanceof Long l) {
                parityWorldRadius = (int) Math.max(0, Math.min(64, l));
            }
        }
    }

    /** 写回文件（原子性不保证，但顺序固定、可 diff）。 */
    public void save() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            loadNote = "写 " + path + " 失败: " + e;
        }
    }

    /** 固定字段顺序的 JSON（P0 不引 Gson，手写足够）。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"native\": {\n    \"enabled\": ").append(nativeEnabled).append("\n  },\n");
        sb.append("  \"ownership\": {\n");
        int i = 0;
        for (Map.Entry<String, Ownership> e : ownership.entrySet()) {
            sb.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue().jsonName()).append('"');
            sb.append(++i < ownership.size() ? ",\n" : "\n");
        }
        sb.append("  },\n");
        sb.append("  \"parity\": {\n");
        sb.append("    \"trace\": \"").append(parityTrace).append("\",\n");
        sb.append("    \"ticks\": ").append(parityTicks).append(",\n");
        sb.append("    \"label\": \"").append(parityLabel).append("\",\n");
        sb.append("    \"worldRadius\": ").append(parityWorldRadius).append("\n  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    public Path path() {
        return path;
    }

    public String loadNote() {
        return loadNote;
    }

    public boolean nativeEnabled() {
        return nativeEnabled;
    }

    public void setNativeEnabled(boolean v) {
        this.nativeEnabled = v;
    }

    public Ownership ownership(String id) {
        return ownership.getOrDefault(id, Ownership.AUTO);
    }

    public void setOwnership(String id, Ownership value) {
        ownership.put(id, value);
    }

    public int parityTicks() {
        return parityTicks;
    }

    public String parityTrace() {
        return parityTrace;
    }

    public String parityLabel() {
        return parityLabel;
    }

    public int parityWorldRadius() {
        return parityWorldRadius;
    }

    // ------------------------------------------------------------------
    // 最小 JSON 解析器（对象 / 字符串 / 整数 / 布尔 / null）
    // ------------------------------------------------------------------

    /** 解析一个 JSON 对象；非法输入抛 {@link IllegalArgumentException}。 */
    public static Map<String, Object> parseObject(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Map<String, Object> map = p.object();
        p.skipWs();
        if (!p.eof()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容 @" + p.pos);
        }
        return map;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (eof()) {
                throw new IllegalArgumentException("JSON 提前结束");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (eof() || s.charAt(pos) != c) {
                throw new IllegalArgumentException("期望 '" + c + "' @" + pos);
            }
            pos++;
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("对象里期望 ',' 或 '}' @" + pos);
            }
        }

        Object value() {
            char c = peek();
            if (c == '{') {
                return object();
            }
            if (c == '"') {
                return string();
            }
            if (c == 't' || c == 'f') {
                return bool();
            }
            if (c == 'n') {
                literal("null");
                return null;
            }
            return number();
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("字符串未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("未知转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Boolean bool() {
            if (peek() == 't') {
                literal("true");
                return Boolean.TRUE;
            }
            literal("false");
            return Boolean.FALSE;
        }

        void literal(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("期望 " + lit + " @" + pos);
            }
            pos += lit.length();
        }

        Long number() {
            int start = pos;
            if (peek() == '-' || peek() == '+') {
                pos++;
            }
            while (!eof() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == 'e'
                    || s.charAt(pos) == 'E' || s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            String raw = s.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("期望数字 @" + start);
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException e) {
                return (long) Double.parseDouble(raw);
            }
        }
    }

    /** 供启动横幅/日志打印的行。 */
    public List<String> describeLines() {
        List<String> lines = new ArrayList<>();
        lines.add("config 文件     : " + path + "（" + loadNote + "）");
        lines.add("native.enabled  : " + nativeEnabled + "（系统属性 -Dcava.native.enabled 优先）");
        StringBuilder own = new StringBuilder("ownership       : ");
        for (Map.Entry<String, Ownership> e : ownership.entrySet()) {
            own.append(e.getKey()).append('=').append(e.getValue().jsonName()).append("  ");
        }
        lines.add(own.toString());
        lines.add("parity          : trace=\"" + parityTrace + "\" ticks=" + parityTicks
                + " label=\"" + parityLabel + "\" worldRadius=" + parityWorldRadius);
        return lines;
    }
}
