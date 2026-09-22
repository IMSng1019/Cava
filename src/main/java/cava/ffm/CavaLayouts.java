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

    // ------------------------------------------------------------------
    // P1 追加的 5 个结构体（2026-09-22，captain 随 ABI 扩展一起登记）
    // **必须与 native/src/cava_layout.cpp 的 kLayouts 同时改动**，
    // 否则两边 layout_hash_sum 不等 → cava_open 返回 CAVA_ERR_LAYOUT → 整体回退。
    // 偏移与大小全部由 C 编译器 offsetof/sizeof 实测，不是手算。
    // ------------------------------------------------------------------

    public static final StructLayout PATH_REQUEST = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("reserved1"),
            ValueLayout.JAVA_INT.withName("tx"),
            ValueLayout.JAVA_INT.withName("ty"),
            ValueLayout.JAVA_INT.withName("tz"),
            ValueLayout.JAVA_INT.withName("reach_range"),
            ValueLayout.JAVA_FLOAT.withName("max_range"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.JAVA_INT.withName("reserved2"),
            ValueLayout.JAVA_INT.withName("max_visited_nodes"),
            // 头文件里尾部填充是**具名字段** pad0/pad1（不是 C 匿名填充），所以这里也用具名
            // int32 —— 整个结构体零内部填充，两边字段数/大小完全一致（实测 12 个字段 / 48 字节）。
            ValueLayout.JAVA_INT.withName("pad0"),
            ValueLayout.JAVA_INT.withName("pad1"),
            ValueLayout.JAVA_INT.withName("pad2")
    ).withName("CavaPathRequest");

    public static final StructLayout PATH_NODE = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("x"),
            ValueLayout.JAVA_INT.withName("y"),
            ValueLayout.JAVA_INT.withName("z"),
            ValueLayout.JAVA_INT.withName("heapIndex"),
            ValueLayout.JAVA_FLOAT.withName("g"),
            ValueLayout.JAVA_FLOAT.withName("f"),
            ValueLayout.JAVA_INT.withName("type"),
            ValueLayout.JAVA_INT.withName("flags")
    ).withName("CavaPathNode");

    /**
     * 与 CAVA_PNT_COUNT 一致（Yarn 1.20.4 {@code PathNodeType} 的 26 个 ordinal）。
     *
     * <p><b>惩罚表 {@code penalty[26]} 按 CAVA_PNT_* 索引</b>，而 CAVA_PNT_* 的数值就是
     * {@code PathNodeType.ordinal()}。两者错一位 = 整张惩罚表错位，**而且路径照样能算出来** ——
     * 属于最难发现的 parity bug。这里的 26 必须与头文件、与 {@code PathNodeType.values().length} 三方一致。
     */
    public static final int PNT_COUNT = 26;

    /**
     * 字段顺序与 {@code cava_abi.h} 的 {@code CavaMobProfile} **逐字一致**，且刻意让
     * float[26]+float = 108、再加一个 int32 顶到 112，使 3 个 double 落在 8 字节边界上。
     * 这样布局内部**没有任何填充**，可以不用 {@code paddingLayout}。
     * <b>不要重排</b>：交错放置会同时触发 FFM 的 "Invalid alignment constraint"。
     */
    public static final StructLayout MOB_PROFILE = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(PNT_COUNT, ValueLayout.JAVA_FLOAT).withName("penalty"),
            // 占位字段：内核当前不读（真正生效的是 safe_fall_distance）。
            // 名字带 reserved_ 就是为了让下一个人一眼看出它不是活字段。
            ValueLayout.JAVA_FLOAT.withName("reserved_max_fall_distance"),
            // C 会在 double 前插入 4 字节填充；Java 的 structLayout 不会自动插，
            // 必须显式写出来（padding 元素没有名字，struct() 只收集有名字的成员）。
            MemoryLayout.paddingLayout(4),
            ValueLayout.JAVA_DOUBLE.withName("start_x"),
            ValueLayout.JAVA_DOUBLE.withName("start_y"),
            ValueLayout.JAVA_DOUBLE.withName("start_z"),
            ValueLayout.JAVA_INT.withName("start_block_x"),
            ValueLayout.JAVA_INT.withName("start_block_y"),
            ValueLayout.JAVA_INT.withName("start_block_z"),
            ValueLayout.JAVA_FLOAT.withName("width"),
            ValueLayout.JAVA_FLOAT.withName("height"),
            ValueLayout.JAVA_FLOAT.withName("step_height"),
            ValueLayout.JAVA_INT.withName("safe_fall_distance"),
            ValueLayout.JAVA_INT.withName("min_y"),
            ValueLayout.JAVA_INT.withName("sea_level"),
            ValueLayout.JAVA_INT.withName("caps"),
            ValueLayout.JAVA_INT.withName("penalty_mask"),
            ValueLayout.JAVA_INT.withName("reserved0"),
            ValueLayout.JAVA_INT.withName("reserved1"),
            // 尾部补齐到 alignof 的整数倍（C: 192）。
            MemoryLayout.paddingLayout(4)
    ).withName("CavaMobProfile");

    public static final StructLayout STATE_RECORD = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_INT.withName("box_offset"),
            ValueLayout.JAVA_INT.withName("box_count"),
            ValueLayout.JAVA_INT.withName("path_type_idx"),
            ValueLayout.JAVA_FLOAT.withName("malus")
    ).withName("CavaStateRecord");

    public static final StructLayout COLLISION_BOX = MemoryLayout.structLayout(
            ValueLayout.JAVA_FLOAT.withName("min_x"),
            ValueLayout.JAVA_FLOAT.withName("min_y"),
            ValueLayout.JAVA_FLOAT.withName("min_z"),
            ValueLayout.JAVA_FLOAT.withName("max_x"),
            ValueLayout.JAVA_FLOAT.withName("max_y"),
            ValueLayout.JAVA_FLOAT.withName("max_z")
    ).withName("CavaCollisionBox");

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

    /** 必须导出布局的 9 个结构体，顺序 = cava_abi.h 的声明顺序（也是 cava_layout.cpp 的 kLayouts 顺序）。 */
    public static final List<Struct> STRUCTS = List.of(
            struct(LAYOUT_ENTRY),
            struct(LAYOUT_REPORT),
            struct(OPEN_PARAMS),
            struct(OPEN_RESULT),
            struct(PATH_REQUEST),
            struct(PATH_NODE),
            struct(MOB_PROFILE),
            struct(STATE_RECORD),
            struct(COLLISION_BOX)
    );

    private static Struct struct(StructLayout layout) {
        List<Field> fields = new ArrayList<>();
        for (MemoryLayout member : layout.memberLayouts()) {
            // 填充元素（MemoryLayout.paddingLayout）**无法命名**（JDK 21 的 paddingLayout(long)
            // 没有 withName 变体，实测），所以这里跳过匿名成员：填充不是"字段"，
            // 但它的字节数已经体现在后面字段的偏移里。
            if (member.name().isEmpty()) {
                continue;
            }
            String name = member.name().get();
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

    // --- P1 的 5 个结构体（C 编译器 offsetof/sizeof 实测，MinGW g++ 15.2 / x86_64-w64-mingw32）---

    /** CavaPathRequest 期望偏移。 */
    public static final long[] PATH_REQUEST_OFFSETS = {0, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52};
    /** CavaPathRequest 期望大小。 */
    public static final long[] PATH_REQUEST_SIZES = {8, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};
    /** CavaPathRequest 期望 sizeof。 */
    public static final long PATH_REQUEST_SIZE = 56;
    /** CavaPathRequest 期望 alignof。 */
    public static final long PATH_REQUEST_ALIGN = 8;

    /** CavaPathNode 期望偏移。 */
    public static final long[] PATH_NODE_OFFSETS = {0, 4, 8, 12, 16, 20, 24, 28};
    /** CavaPathNode 期望大小。 */
    public static final long[] PATH_NODE_SIZES = {4, 4, 4, 4, 4, 4, 4, 4};
    /** CavaPathNode 期望 sizeof。 */
    public static final long PATH_NODE_SIZE = 32;
    /** CavaPathNode 期望 alignof。 */
    public static final long PATH_NODE_ALIGN = 4;

    /** CavaMobProfile 期望偏移（数组 penalty[26] 算一个字段：offset=0, size=104）。中间有一处 C 隐式填充（108→112）。 */
    public static final long[] MOB_PROFILE_OFFSETS = {0, 104, 112, 120, 128, 136, 140, 144, 148, 152, 156, 160, 164, 168, 172, 176, 180, 184};
    /** CavaMobProfile 期望大小。 */
    public static final long[] MOB_PROFILE_SIZES = {104, 4, 8, 8, 8, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4};
    /** CavaMobProfile 期望 sizeof。 */
    public static final long MOB_PROFILE_SIZE = 192;
    /** CavaMobProfile 期望 alignof。 */
    public static final long MOB_PROFILE_ALIGN = 8;

    /**
     * {@code CavaMobProfile} 各字段在结构体内的偏移（= C 编译器实测值）。
     *
     * <p><b>为什么要有这些常量</b>：调用方本来用
     * {@code MOB_PROFILE.byteOffset(groupElement("字段名"))} 取偏移 —— 那是**按字符串查字段名**，
     * 字段一改名就会在运行期抛异常。2026-09-22 给 {@code max_fall_distance} 加
     * {@code reserved_} 前缀时就撞了一次。
     * <b>关键偏移一律用这里的常量，不要再按名字查。</b>
     */
    public static final class MobProfileOffset {
        private MobProfileOffset() {
        }

        /** penalty[26]（数组算一个字段）。 */
        public static final long PENALTY = 0;
        /** 占位字段，内核不读（真正生效的是 SAFE_FALL_DISTANCE）。 */
        public static final long RESERVED_MAX_FALL = 104;
        public static final long START_X = 112;
        public static final long START_Y = 120;
        public static final long START_Z = 128;
        public static final long START_BLOCK_X = 136;
        public static final long START_BLOCK_Y = 140;
        public static final long START_BLOCK_Z = 144;
        public static final long WIDTH = 148;
        public static final long HEIGHT = 152;
        public static final long STEP_HEIGHT = 156;
        public static final long SAFE_FALL_DISTANCE = 160;
        public static final long MIN_Y = 164;
        public static final long SEA_LEVEL = 168;
        public static final long CAPS = 172;
        public static final long PENALTY_MASK = 176;
    }

    /** CavaStateRecord 期望偏移。 */
    public static final long[] STATE_RECORD_OFFSETS = {0, 4, 8, 12, 16};
    /** CavaStateRecord 期望大小。 */
    public static final long[] STATE_RECORD_SIZES = {4, 4, 4, 4, 4};
    /** CavaStateRecord 期望 sizeof。 */
    public static final long STATE_RECORD_SIZE = 20;
    /** CavaStateRecord 期望 alignof。 */
    public static final long STATE_RECORD_ALIGN = 4;

    /** CavaCollisionBox 期望偏移。 */
    public static final long[] COLLISION_BOX_OFFSETS = {0, 4, 8, 12, 16, 20};
    /** CavaCollisionBox 期望大小。 */
    public static final long[] COLLISION_BOX_SIZES = {4, 4, 4, 4, 4, 4};
    /** CavaCollisionBox 期望 sizeof。 */
    public static final long COLLISION_BOX_SIZE = 24;
    /** CavaCollisionBox 期望 alignof。 */
    public static final long COLLISION_BOX_ALIGN = 4;

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
        checkStruct(problems, "CavaPathRequest", PATH_REQUEST, PATH_REQUEST_SIZE, PATH_REQUEST_ALIGN,
                PATH_REQUEST_OFFSETS, PATH_REQUEST_SIZES);
        checkStruct(problems, "CavaPathNode", PATH_NODE, PATH_NODE_SIZE, PATH_NODE_ALIGN,
                PATH_NODE_OFFSETS, PATH_NODE_SIZES);
        checkStruct(problems, "CavaMobProfile", MOB_PROFILE, MOB_PROFILE_SIZE, MOB_PROFILE_ALIGN,
                MOB_PROFILE_OFFSETS, MOB_PROFILE_SIZES);
        checkStruct(problems, "CavaStateRecord", STATE_RECORD, STATE_RECORD_SIZE, STATE_RECORD_ALIGN,
                STATE_RECORD_OFFSETS, STATE_RECORD_SIZES);
        checkStruct(problems, "CavaCollisionBox", COLLISION_BOX, COLLISION_BOX_SIZE, COLLISION_BOX_ALIGN,
                COLLISION_BOX_OFFSETS, COLLISION_BOX_SIZES);
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
