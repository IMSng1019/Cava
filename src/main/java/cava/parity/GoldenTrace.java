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

    /**
     * 头行的**排除/包含范围**（captain 的确定性结论要求：排除范围必须写进 trace 头才算可复现）。
     *
     * <p>这是对契约 4.2 头行字段的**追加**（不是修改）：原有 8 个键一个不动，新增
     * {@code radius / spawnExcl / excl / incl / hash} 五个键。老 trace 仍然能被解析
     * （{@link cava.parity.TraceDiff} 按键取值，缺键=null）。
     *
     * @param radius            世界哈希扫描半径（区块）
     * @param spawnExclRadius   世界哈希排除的出生点方形半径（区块）；0 = 不排除
     * @param excl              逗号分隔的"默认不比对"字段（如 {@code entities}）
     * @param incl              逗号分隔的"显式比对"字段
     * @param hashDefs          各哈希字段的定义（人读，防止把 p 当成"调用级"节点序列）
     */
    public record Meta(int radius, int spawnExclRadius, String excl, String incl, String hashDefs) {
        /** 契约默认：半径 8、排除出生点 3 区块、默认排除 entities。 */
        public static final Meta DEFAULT = new Meta(8, 3, "entities", "", "w=blocks;e=entities;p=navstate");
    }

    private final Path path;
    private final Writer writer;
    private long written;
    private boolean closed;

    private GoldenTrace(Path path, Writer writer) {
        this.path = path;
        this.writer = writer;
    }

    /** 打开 {@code <dir>/trace-<label>.ndjson} 并写头行（契约默认 Meta）。 */
    public static GoldenTrace open(Path dir, String label, String mods, String mcVersion, String cavaVersion,
                                   long seed, long startTick, boolean nativeOn) throws IOException {
        return open(dir, label, mods, mcVersion, cavaVersion, seed, startTick, nativeOn, Meta.DEFAULT);
    }

    /** 打开 {@code <dir>/trace-<label>.ndjson} 并写头行（含排除范围）。 */
    public static GoldenTrace open(Path dir, String label, String mods, String mcVersion, String cavaVersion,
                                   long seed, long startTick, boolean nativeOn, Meta meta) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("trace-" + sanitize(label) + ".ndjson");
        Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), 1 << 16);
        GoldenTrace t = new GoldenTrace(file, w);
        t.writeLine(header(label, mods, mcVersion, cavaVersion, seed, startTick, nativeOn, meta));
        return t;
    }

    /**
     * 头行 JSON（契约 4.2 的 8 个键 + {@link Meta} 追加的 5 个键）。
     * 追加强制理由：captain 的确定性结论要求"排除范围写进 trace 头（可复现）"。
     */
    public static String header(String label, String mods, String mcVersion, String cavaVersion,
                                long seed, long startTick, boolean nativeOn, Meta meta) {
        Meta m = meta == null ? Meta.DEFAULT : meta;
        return "{\"t\":\"h\",\"v\":" + VERSION
                + ",\"label\":" + json(label)
                + ",\"native\":" + nativeOn
                + ",\"mods\":" + json(mods)
                + ",\"mc\":" + json(mcVersion)
                + ",\"cava\":" + json(cavaVersion)
                + ",\"seed\":" + seed
                + ",\"startTick\":" + startTick
                + ",\"radius\":" + m.radius()
                + ",\"spawnExcl\":" + m.spawnExclRadius()
                + ",\"excl\":" + json(m.excl())
                + ",\"incl\":" + json(m.incl())
                + ",\"hash\":" + json(m.hashDefs())
                + '}';
    }

    /** 打开明细文件 {@code <dir>/detail-<label>.ndjson}（逐实体哈希 + 区块增量哈希，差异定位用）。 */
    public static GoldenTrace openDetail(Path dir, String label, Meta meta) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("detail-" + sanitize(label) + ".ndjson");
        Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), 1 << 16);
        GoldenTrace t = new GoldenTrace(file, w);
        t.writeLine(header(label, "detail", "", "", 0L, 0L, false, meta)
                .replace("{\"t\":\"h\"", "{\"t\":\"h-detail\""));
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

    /**
     * 明细行：{@code {"t":"d","k":<tick>,"ent":[[key,hash,note],...],"paths":[...],"dc":[[chunk,hash],...]}}。
     *
     * <p>{@code dc} 只写**相对上一 tick 发生变化**的区块 —— 脚本场景（tick freeze/sprint）下它通常是空的；
     * 一旦有内容，两侧比对就直接给出"哪个区块坐标"这个答案。
     */
    public void detailLine(long tick, StringBuilder entities, StringBuilder paths, String chunkDelta) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"t\":\"d\",\"k\":").append(tick)
                .append(",\"ent\":[").append(entities == null ? "" : entities).append(']')
                .append(",\"paths\":[").append(paths == null ? "" : paths).append(']')
                .append(",\"dc\":[").append(chunkDelta == null ? "" : chunkDelta).append("]}");
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
