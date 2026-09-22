package cava.parity;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 黄金轨迹 NDJSON 写入器（契约 4.2）。**能进生产 jar**（cava.parity 是运行时采集侧）。
 *
 * <p>格式：第一行头 {@code {"t":"h",...}}，之后每 tick 一行 {@code {"t":"k",...}}。
 * 字段缺省即 {@code null}，但**键必须都在**（离线比对器按固定 schema 读）。
 * 行尾统一 {@code \n}，UTF-8，逐 tick 追加。
 */
public final class GoldenTrace implements Closeable {

    /** 轨迹格式版本（写进头）。 */
    public static final int VERSION = 1;

    private final Path path;
    private final Writer writer;
    private long written;
    private boolean closed;

    private GoldenTrace(Path path, Writer writer) {
        this.path = path;
        this.writer = writer;
    }

    /** 打开 {@code <dir>/trace-<label>.ndjson} 并写头行。 */
    public static GoldenTrace open(Path dir, String label, String mods, String mcVersion, String cavaVersion,
                                   long seed, long startTick, boolean nativeOn) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("trace-" + sanitize(label) + ".ndjson");
        Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), 1 << 16);
        GoldenTrace t = new GoldenTrace(file, w);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":\"h\",\"v\":").append(VERSION)
                .append(",\"label\":").append(json(label))
                .append(",\"native\":").append(nativeOn)
                .append(",\"mods\":").append(json(mods))
                .append(",\"mc\":").append(json(mcVersion))
                .append(",\"cava\":").append(json(cavaVersion))
                .append(",\"seed\":").append(seed)
                .append(",\"startTick\":").append(startTick)
                .append('}');
        t.writeLine(sb.toString());
        return t;
    }

    /** 标签里不能出现的字符换成 {@code _}（文件名安全）。 */
    public static String sanitize(String label) {
        if (label == null || label.isBlank()) {
            return "default";
        }
        return label.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 写一行 tick。
     *
     * @param tick  tick 序号（= 头里的 startTick + 序号）
     * @param world 世界哈希 hex16，可为 null
     * @param entity 实体哈希 hex16，可为 null
     * @param pathfind 寻路哈希 hex16，可为 null
     * @param blockTicks 方块 tick 事件数，可为 null
     * @param neighborUpdates 邻居更新事件数，可为 null
     * @param extra 扩展抽样哈希，可为 null
     */
    public void tick(long tick, String world, String entity, String pathfind,
                     Long blockTicks, Long neighborUpdates, String extra) throws IOException {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"t\":\"k\",\"k\":").append(tick)
                .append(",\"w\":").append(world == null ? "null" : json(world))
                .append(",\"e\":").append(entity == null ? "null" : json(entity))
                .append(",\"p\":").append(pathfind == null ? "null" : json(pathfind))
                .append(",\"bt\":").append(blockTicks == null ? "null" : blockTicks.toString())
                .append(",\"nt\":").append(neighborUpdates == null ? "null" : neighborUpdates.toString())
                .append(",\"x\":").append(extra == null ? "null" : json(extra))
                .append('}');
        writeLine(sb.toString());
    }

    private void writeLine(String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        written++;
        // 每 100 行刷一次：崩了也不至于全丢，同时不至于每 tick 一次 syscall
        if ((written % 100) == 0) {
            writer.flush();
        }
    }

    /** 已写行数（含头）。 */
    public long lineCount() {
        return written;
    }

    public Path path() {
        return path;
    }

    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // 关闭阶段的 IO 失败不该影响停服流程
        }
    }

    /** JSON 字符串转义（只做必需的最小转义）。 */
    static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
