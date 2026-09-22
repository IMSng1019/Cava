package cava.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 布局自检（契约 2.3 / 任务 C3）——防「JVM 段错误」的核心闸门。
 *
 * <p>Java 侧按 cava_abi.h 的声明顺序手写每个结构体的字段 (offset,size)，算出布局哈希：
 * <pre>
 * h = 0x811C9DC5
 * for each field in declaration order:
 *     h ^= (uint32)(offset & 0xFFFFFFFF); h *= 0x01000193
 *     h ^= (uint32)(size   & 0xFFFFFFFF); h *= 0x01000193
 *     h ^= (uint32)((offset >> 32) & 0xFFFFFFFF); h *= 0x01000193
 *     h ^= (uint32)((size   >> 32) & 0xFFFFFFFF); h *= 0x01000193
 * </pre>
 * {@code layout_hash_sum} = 全部导出结构体 layout_hash 的 uint32 无符号加法（回绕）。
 *
 * <p><b>唯一权威公式（2026-09-22 裁定）</b>：cava_abi.h 已明确废除早期的「逐字节」变体。
 * 本实现与原生 {@code native/src/cava_layout.cpp} 逐字段一致 —— 交叉验证值：
 * 9 个结构体 {@code layout_hash_sum == 0x6975CBF9}（Java 侧与真实 cava.dll 的
 * {@code cava_layout_report} + {@code cava_open} 都算出同值，实测）。
 *
 * <p>数组字段算**一个**字段；填充（{@code paddingLayout}）不是字段，但已经从后续字段的偏移里体现。
 */
public final class LayoutCheck {

    private LayoutCheck() {
    }

    /** Java 侧单个结构体的布局视图 + 布局哈希。 */
    public record StructInfo(String name, long size, long align, List<CavaLayouts.Field> fields, int hashU32) {
        public int fieldCount() {
            return fields.size();
        }
    }

    /** Java 侧完整结果。 */
    public record JavaSide(List<StructInfo> structs, long sumU32, String fieldTable) {
    }

    /** 原生 CavaLayoutEntry 解析结果。 */
    public record NativeEntry(long structSize, long structAlign, int fieldCount, int layoutHash, long[] offsets, long[] sizes) {
    }

    /** 原生 CavaLayoutReport 解析结果。 */
    public record NativeReport(int abiVersion, int buildFlags, int platform, int pointerSize, int entryCount,
                               long buildIdHash, List<NativeEntry> entries) {
    }

    /**
     * 比对结果。
     *
     * @param problems 真问题（任一非空 => {@code ok == false} => 整体回退纯 Java）
     * @param notes    不是问题但必须让用户看见的说明（当前为空，保留给后续 ABI 演进）
     */
    public record Result(boolean ok, JavaSide java, NativeReport report, List<String> problems, List<String> notes) {
        /** 应该填进 {@code CavaOpenParams.layout_hash_sum} 的值。 */
        public long sumToSendInOpenParams() {
            return java.sumU32();
        }
    }

    // ------------------------------------------------------------------
    // 哈希
    // ------------------------------------------------------------------

    private static int mix(int h, int value) {
        h ^= value;
        h *= CavaLayouts.FNV_PRIME_32;
        return h;
    }

    /** 唯一权威公式：每个字段按声明顺序做 4 次 {@code h ^= (uint32)v; h *= PRIME}。 */
    public static int layoutHashU32(List<CavaLayouts.Field> fields) {
        int h = CavaLayouts.FNV_OFFSET_BASIS_32;
        for (CavaLayouts.Field f : fields) {
            h = mix(h, (int) (f.offset() & 0xFFFFFFFFL));
            h = mix(h, (int) (f.size() & 0xFFFFFFFFL));
            h = mix(h, (int) ((f.offset() >>> 32) & 0xFFFFFFFFL));
            h = mix(h, (int) ((f.size() >>> 32) & 0xFFFFFFFFL));
        }
        return h;
    }

    /** uint32 无符号加法（回绕），结果为无符号 32 位值。 */
    public static long unsignedSum32(long... hashes) {
        long sum = 0;
        for (long h : hashes) {
            sum = (sum + (h & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        }
        return sum;
    }

    // ------------------------------------------------------------------
    // Java 侧
    // ------------------------------------------------------------------

    /** 算 Java 侧全部结构体的 (offset,size) 表与 layout_hash_sum。 */
    public static JavaSide javaSide() {
        List<StructInfo> structs = new ArrayList<>(CavaLayouts.STRUCTS.size());
        for (CavaLayouts.Struct s : CavaLayouts.STRUCTS) {
            structs.add(new StructInfo(s.name(), s.size(), s.align(), s.fields(), layoutHashU32(s.fields())));
        }
        long[] hashes = new long[structs.size()];
        for (int i = 0; i < structs.size(); i++) {
            hashes[i] = structs.get(i).hashU32();
        }
        return new JavaSide(List.copyOf(structs), unsignedSum32(hashes), fieldTable(structs));
    }

    /** 每字段 (offset,size) 的可读表（任务 C3 要求打日志）。 */
    public static String fieldTable(List<StructInfo> structs) {
        StringBuilder sb = new StringBuilder();
        for (StructInfo s : structs) {
            sb.append(String.format("  %-17s size=%-6d align=%d fields=%-2d hash=0x%08X%n",
                    s.name(), s.size(), s.align(), s.fieldCount(), s.hashU32()));
            for (CavaLayouts.Field f : s.fields()) {
                sb.append(String.format("      %-16s offset=%-6d size=%-6d%n", f.name(), f.offset(), f.size()));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 原生报告解析
    // ------------------------------------------------------------------

    /** 解析 cava_layout_report 填好的 CavaLayoutReport 内存。 */
    public static NativeReport parseReport(MemorySegment seg) {
        int abiVersion = seg.get(ValueLayout.JAVA_INT, 0);
        int buildFlags = seg.get(ValueLayout.JAVA_INT, 4);
        int platform = seg.get(ValueLayout.JAVA_INT, 8);
        int pointerSize = seg.get(ValueLayout.JAVA_INT, 12);
        int entryCount = seg.get(ValueLayout.JAVA_INT, 16);
        long buildIdHash = seg.get(ValueLayout.JAVA_LONG, 24);
        List<NativeEntry> entries = new ArrayList<>();
        int n = Math.min(Math.max(entryCount, 0), CavaLayouts.LAYOUT_REPORT_CAP);
        for (int i = 0; i < n; i++) {
            long base = CavaLayouts.REPORT_OFFSETS[7] + (long) i * CavaLayouts.ENTRY_SIZE;
            long structSize = seg.get(ValueLayout.JAVA_LONG, base + 8);
            long structAlign = seg.get(ValueLayout.JAVA_LONG, base + 16);
            int fieldCount = seg.get(ValueLayout.JAVA_INT, base + 24);
            int layoutHash = seg.get(ValueLayout.JAVA_INT, base + 28);
            long[] offsets = new long[CavaLayouts.LAYOUT_MAX_FIELDS];
            long[] sizes = new long[CavaLayouts.LAYOUT_MAX_FIELDS];
            for (int k = 0; k < CavaLayouts.LAYOUT_MAX_FIELDS; k++) {
                offsets[k] = seg.get(ValueLayout.JAVA_LONG, base + 32 + (long) k * 8);
                sizes[k] = seg.get(ValueLayout.JAVA_LONG, base + 288 + (long) k * 8);
            }
            entries.add(new NativeEntry(structSize, structAlign, fieldCount, layoutHash, offsets, sizes));
        }
        return new NativeReport(abiVersion, buildFlags, platform, pointerSize, entryCount, buildIdHash, List.copyOf(entries));
    }

    // ------------------------------------------------------------------
    // 比对
    // ------------------------------------------------------------------

    /**
     * 逐个 entry 比对：先按 (struct_size, field_count) 找对应结构体，再比 align、
     * entry.layout_hash、以及每个字段的 (offset,size)。任一不等 → 进 problems。
     */
    public static Result compare(JavaSide java, NativeReport report) {
        List<String> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (report.abiVersion() != CavaLayouts.ABI_VERSION) {
            problems.add("report.abi_version=" + report.abiVersion() + " 期望 " + CavaLayouts.ABI_VERSION);
        }
        if (report.pointerSize() != 8) {
            problems.add("report.pointer_size=" + report.pointerSize() + " 期望 8");
        }
        if (report.entryCount() != java.structs().size()) {
            problems.add("report.entry_count=" + report.entryCount() + " 期望 " + java.structs().size()
                    + "（每加一个导出结构体都要在 CavaLayouts 登记）");
        }

        boolean[] seen = new boolean[java.structs().size()];
        for (int i = 0; i < report.entries().size(); i++) {
            NativeEntry e = report.entries().get(i);
            StructInfo match = null;
            int matchIndex = -1;
            // 【2026-09-22 修正】**优先按下标一一对应**。
            // 两侧的顺序都约定为「cava_abi.h 的声明顺序」（= cava_layout.cpp 的 kLayouts 顺序）。
            // 原来只按 (struct_size, field_count) 找**第一个**匹配 —— P2 冻结后
            // CavaShapeRecord(32 字节/8 字段) 与 CavaPathNode(32 字节/8 字段) 完全同形，
            // 于是 CavaPathNode 被匹配两次、CavaShapeRecord 永远找不到对应 entry，
            // 整个原生库被判 LAYOUT_MISMATCH —— **测试静默 skip、原生路径整条不可用**。
            // layout_hash 也区分不了它们（同一个 (offset,size) 序列），所以只能靠下标。
            if (i < java.structs().size()) {
                StructInfo s = java.structs().get(i);
                if (s.size() == e.structSize() && s.fieldCount() == e.fieldCount()) {
                    match = s;
                    matchIndex = i;
                }
            }
            if (match == null) {
                for (int j = 0; j < java.structs().size(); j++) {
                    if (seen[j]) {
                        continue;
                    }
                    StructInfo s = java.structs().get(j);
                    if (s.size() == e.structSize() && s.fieldCount() == e.fieldCount()) {
                        match = s;
                        matchIndex = j;
                        break;
                    }
                }
            }
            if (match == null) {
                problems.add("entry[" + i + "]: 找不到匹配的 Java 结构体 (struct_size=" + e.structSize()
                        + ", field_count=" + e.fieldCount() + ")");
                continue;
            }
            if (matchIndex != i) {
                notes.add("entry[" + i + "] 按内容匹配到 Java 结构体[" + matchIndex + "] " + match.name()
                        + "（两侧声明顺序不一致，请检查 cava_abi.h / cava_layout.cpp / CavaLayouts 的顺序）");
            }
            seen[matchIndex] = true;
            if (e.structAlign() != match.align()) {
                problems.add(match.name() + ": struct_align=" + e.structAlign() + " 期望 " + match.align());
            }
            if (e.layoutHash() != match.hashU32()) {
                problems.add(match.name() + ": layout_hash=0x" + Integer.toHexString(e.layoutHash())
                        + " 与 Java 侧 0x" + Integer.toHexString(match.hashU32()) + " 不等");
            }
            for (int k = 0; k < match.fieldCount(); k++) {
                CavaLayouts.Field f = match.fields().get(k);
                if (e.offsets()[k] != f.offset() || e.sizes()[k] != f.size()) {
                    problems.add(match.name() + "." + f.name() + ": 原生 (offset=" + e.offsets()[k]
                            + ", size=" + e.sizes()[k] + ") != Java (offset=" + f.offset() + ", size=" + f.size() + ")");
                }
            }
        }
        for (int j = 0; j < java.structs().size(); j++) {
            if (!seen[j]) {
                problems.add("Java 结构体 " + java.structs().get(j).name() + " 在原生 report 里没有对应 entry");
            }
        }
        return new Result(problems.isEmpty(), java, report, List.copyOf(problems), List.copyOf(notes));
    }
}
