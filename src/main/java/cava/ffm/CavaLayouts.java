package cava.ffm;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code native/include/cava_abi.h} 里 4 个导出结构体的 FFM 布局（**手写，不用 jextract**）。
 *
 * <p>偏移一律用 {@link MemoryLayout#byteOffset(PathElement...)} 从布局本身算出来，再与
 * {@code C 编译器在 Windows x64 上的实际 offsetof} 断言比对（见 {@link #checkAgainstCAbi()}），
 * 任何不一致都会让 {@link CavaNative#tryOpen()} 直接回退纯 Java。
 *
 * <p>P0 导出布局的结构体（契约 2.3）：
 * <pre>
 * CavaLayoutEntry  { int32, int32, uint64, uint64, uint32, uint32, uint64[32], uint64[32] }  size=544
 * CavaLayoutReport { int32*6, uint64, CavaLayoutEntry[64] }                                  size=34848
 * CavaOpenParams   { int32, int32, uint64, int64, int64 }                                    size=32
 * CavaOpenResult   { int32, int32, uint64, int64 }                                           size=24
 * </pre>
 *
 * <p><b>数组字段算「一个字段」</b>：CavaLayoutReport.entries[64] 是一个字段（offset=32, size=34816），
 * 不展开成 64 个字段——否则字段数会超过 {@code CAVA_LAYOUT_MAX_FIELDS=32}。布局哈希按这个定义算。
 */
public final class CavaLayouts {

    private CavaLayouts() {
    }

    /** 与 cava_abi.h 的 CAVA_ABI_VERSION 一致。 */
    public static final int ABI_VERSION = 1;

    /** 与 cava_abi.h 的 CAVA_LAYOUT_MAX_FIELDS 一致。 */
    public static final int LAYOUT_MAX_FIELDS = 32;

    /** 与 cava_abi.h 的 CAVA_LAYOUT_REPORT_CAP 一致。 */
    public static final int LAYOUT_REPORT_CAP = 64;

    /** CAVA_LAYOUT_FNV_OFFSET。 */
    public static final int FNV_OFFSET_BASIS_32 = 0x811C9DC5;

    /** CAVA_LAYOUT_FNV_PRIME。 */
    public static final int FNV_PRIME_32 = 0x01000193;

    // ------------------------------------------------------------------
    // 结构体布局（字段名与 cava_abi.h 逐字对应）
    // ------------------------------------------------------------------

    public static final StructLayout LAYOUT_ENTRY = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("abi_version"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.JAVA_LONG.withName("struct_size"),
            ValueLayout.JAVA_LONG.withName("struct_align"),
            ValueLayout.JAVA_INT.withName("field_count"),
            ValueLayout.JAVA_INT.withName("layout_hash"),
            // 数组一律「一个 layout + 一个 count」，Java 侧读的时候按索引取
            MemoryLayout.sequenceLayout(LAYOUT_MAX_FIELDS, ValueLayout.JAVA_LONG).withName("field_offsets"),
            MemoryLayout.sequenceLayout(LAYOUT_MAX_FIELDS, ValueLayout.JAVA_LONG).withName("field_sizes")
    ).withName("CavaLayoutEntry");

    public static final StructLayout LAYOUT_REPORT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("abi_version"),
            ValueLayout.JAVA_INT.withName("build_flags"),
            ValueLayout.JAVA_INT.withName("platform"),
            ValueLayout.JAVA_INT.withName("pointer_size"),
            ValueLayout.JAVA_INT.withName("entry_count"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.JAVA_LONG.withName("build_id_hash"),
            MemoryLayout.sequenceLayout(LAYOUT_REPORT_CAP, LAYOUT_ENTRY).withName("entries")
    ).withName("CavaLayoutReport");

    public static final StructLayout OPEN_PARAMS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("abi_version"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_LONG.withName("layout_hash_sum"),
            ValueLayout.JAVA_LONG.withName("reserved0"),
            ValueLayout.JAVA_LONG.withName("reserved1")
    ).withName("CavaOpenParams");

    public static final StructLayout OPEN_RESULT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("status"),
            ValueLayout.JAVA_INT.withName("abi_version"),
            ValueLayout.JAVA_LONG.withName("native_layout_sum"),
            ValueLayout.JAVA_LONG.withName("reserved0")
    ).withName("CavaOpenResult");

    /** 一个结构体字段的 (名字, 偏移, 大小)。 */
    public record Field(String name, long offset, long size) {
        @Override
        public String toString() {
            return String.format("%-16s offset=%-5d size=%d", name, offset, size);
        }
    }

    /** 一个结构体的 Java 侧布局视图。 */
    public record Struct(String name, StructLayout layout, long size, long align, List<Field> fields) {
        public int fieldCount() {
            return fields.size();
        }
    }

    /** P0 必须导出布局的 4 个结构体，顺序 = cava_abi.h 的声明顺序。 */
    public static final List<Struct> STRUCTS = List.of(
            struct(LAYOUT_ENTRY),
            struct(LAYOUT_REPORT),
            struct(OPEN_PARAMS),
            struct(OPEN_RESULT)
    );

    private static Struct struct(StructLayout layout) {
        List<Field> fields = new ArrayList<>();
        for (MemoryLayout member : layout.memberLayouts()) {
            String name = member.name().orElseThrow(() -> new IllegalStateException("未命名字段: " + member));
            long offset = layout.byteOffset(PathElement.groupElement(name));
            fields.add(new Field(name, offset, member.byteSize()));
        }
        return new Struct(layout.name().orElse("?"), layout, layout.byteSize(), layout.byteAlignment(), List.copyOf(fields));
    }

    // ------------------------------------------------------------------
    // 期望值（Windows x64 / Linux x64 的 System V AMD64 ABI 都是同一套自然对齐规则）
    // 这些数字由 CMake 侧用 offsetof() 生成，Java 侧启动时必须逐个断言。
    // ------------------------------------------------------------------

    /** CavaLayoutEntry 期望偏移（C 编译器 offsetof 实测）。 */
    public static final long[] ENTRY_OFFSETS = {0, 4, 8, 16, 24, 28, 32, 288};
    /** CavaLayoutEntry 期望大小。 */
    public static final long[] ENTRY_SIZES = {4, 4, 8, 8, 4, 4, LAYOUT_MAX_FIELDS * 8L, LAYOUT_MAX_FIELDS * 8L};
    /** CavaLayoutEntry 期望 sizeof。 */
    public static final long ENTRY_SIZE = 544;
    /** CavaLayoutEntry 期望 alignof。 */
    public static final long ENTRY_ALIGN = 8;

    /** CavaLayoutReport 期望偏移。 */
    public static final long[] REPORT_OFFSETS = {0, 4, 8, 12, 16, 20, 24, 32};
    /** CavaLayoutReport 期望大小。 */
    public static final long[] REPORT_SIZES = {4, 4, 4, 4, 4, 4, 8, LAYOUT_REPORT_CAP * ENTRY_SIZE};
    /** CavaLayoutReport 期望 sizeof。 */
    public static final long REPORT_SIZE = 34848;
    /** CavaLayoutReport 期望 alignof。 */
    public static final long REPORT_ALIGN = 8;

    /** CavaOpenParams 期望偏移。 */
    public static final long[] PARAMS_OFFSETS = {0, 4, 8, 16, 24};
    /** CavaOpenParams 期望大小。 */
    public static final long[] PARAMS_SIZES = {4, 4, 8, 8, 8};
    /** CavaOpenParams 期望 sizeof。 */
    public static final long PARAMS_SIZE = 32;
    /** CavaOpenParams 期望 alignof。 */
    public static final long PARAMS_ALIGN = 8;

    /** CavaOpenResult 期望偏移。 */
    public static final long[] RESULT_OFFSETS = {0, 4, 8, 16};
    /** CavaOpenResult 期望大小。 */
    public static final long[] RESULT_SIZES = {4, 4, 8, 8};
    /** CavaOpenResult 期望 sizeof。 */
    public static final long RESULT_SIZE = 24;
    /** CavaOpenResult 期望 alignof。 */
    public static final long RESULT_ALIGN = 8;

    /** CAVA_OK。 */
    public static final int CAVA_OK = 0;
    /** CAVA_ERR_ABI_VERSION。 */
    public static final int CAVA_ERR_ABI_VERSION = -1;
    /** CAVA_ERR_LAYOUT。 */
    public static final int CAVA_ERR_LAYOUT = -2;
    /** CAVA_ERR_NULL。 */
    public static final int CAVA_ERR_NULL = -3;
    /** CAVA_ERR_ARG。 */
    public static final int CAVA_ERR_ARG = -4;
    /** CAVA_ERR_OOM。 */
    public static final int CAVA_ERR_OOM = -5;
    /** CAVA_ERR_INTERNAL。 */
    public static final int CAVA_ERR_INTERNAL = -6;
    /** CAVA_ERR_UNIMPLEMENTED。 */
    public static final int CAVA_ERR_UNIMPLEMENTED = -7;

    /** CAVA_OPEN_FLAG_SAFE_ASSERTS。 */
    public static final int OPEN_FLAG_SAFE_ASSERTS = 1;
    /** CAVA_OPEN_FLAG_DETERMINISTIC。 */
    public static final int OPEN_FLAG_DETERMINISTIC = 1 << 1;

    /** CAVA_PLATFORM_WINDOWS_X64。 */
    public static final int PLATFORM_WINDOWS_X64 = 1;
    /** CAVA_PLATFORM_LINUX_X64。 */
    public static final int PLATFORM_LINUX_X64 = 3;

    /** 把 C 错误码翻成人话。 */
    public static String errorName(int code) {
        return switch (code) {
            case CAVA_OK -> "CAVA_OK";
            case CAVA_ERR_ABI_VERSION -> "CAVA_ERR_ABI_VERSION";
            case CAVA_ERR_LAYOUT -> "CAVA_ERR_LAYOUT";
            case CAVA_ERR_NULL -> "CAVA_ERR_NULL";
            case CAVA_ERR_ARG -> "CAVA_ERR_ARG";
            case CAVA_ERR_OOM -> "CAVA_ERR_OOM";
            case CAVA_ERR_INTERNAL -> "CAVA_ERR_INTERNAL";
            case CAVA_ERR_UNIMPLEMENTED -> "CAVA_ERR_UNIMPLEMENTED";
            default -> "CAVA_ERR_UNKNOWN(" + code + ")";
        };
    }

    /**
     * 把 Java 侧算出的 (offset,size) 与 C 编译器期望值逐个比对。
     *
     * @return 问题列表；空表示完全一致。
     */
    public static List<String> checkAgainstCAbi() {
        List<String> problems = new ArrayList<>();
        checkStruct(problems, "CavaLayoutEntry", LAYOUT_ENTRY, ENTRY_SIZE, ENTRY_ALIGN, ENTRY_OFFSETS, ENTRY_SIZES);
        checkStruct(problems, "CavaLayoutReport", LAYOUT_REPORT, REPORT_SIZE, REPORT_ALIGN, REPORT_OFFSETS, REPORT_SIZES);
        checkStruct(problems, "CavaOpenParams", OPEN_PARAMS, PARAMS_SIZE, PARAMS_ALIGN, PARAMS_OFFSETS, PARAMS_SIZES);
        checkStruct(problems, "CavaOpenResult", OPEN_RESULT, RESULT_SIZE, RESULT_ALIGN, RESULT_OFFSETS, RESULT_SIZES);
        return problems;
    }

    private static void checkStruct(List<String> problems, String name, StructLayout layout, long size, long align,
                                    long[] offsets, long[] sizes) {
        if (layout.byteSize() != size) {
            problems.add(name + ": sizeof=" + layout.byteSize() + " 期望 " + size);
        }
        if (layout.byteAlignment() != align) {
            problems.add(name + ": alignof=" + layout.byteAlignment() + " 期望 " + align);
        }
        List<Field> fields = struct(layout).fields();
        if (fields.size() != offsets.length) {
            problems.add(name + ": 字段数=" + fields.size() + " 期望 " + offsets.length);
            return;
        }
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (f.offset() != offsets[i]) {
                problems.add(name + "." + f.name() + ": offset=" + f.offset() + " 期望 " + offsets[i]);
            }
            if (f.size() != sizes[i]) {
                problems.add(name + "." + f.name() + ": size=" + f.size() + " 期望 " + sizes[i]);
            }
        }
    }

    /** 多层缩进的 (offset,size) 表，供日志/自检打印。 */
    public static String describeAll() {
        StringBuilder sb = new StringBuilder();
        for (Struct s : STRUCTS) {
            sb.append(String.format("  %-17s size=%-6d align=%d fields=%d%n", s.name(), s.size(), s.align(), s.fieldCount()));
            for (Field f : s.fields()) {
                sb.append("      ").append(f).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    /** 单个结构体的字段表。 */
    public static String describe(Struct s) {
        StringBuilder sb = new StringBuilder(String.format("%s size=%d align=%d fields=%d", s.name(), s.size(), s.align(), s.fieldCount()));
        for (Field f : s.fields()) {
            sb.append(System.lineSeparator()).append("    ").append(f);
        }
        return sb.toString();
    }
}
