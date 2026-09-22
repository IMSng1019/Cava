package cava.parity;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import cava.mirror.MirrorFlags;
import cava.oracle.BlockKind;
import cava.oracle.LandMaker;
import cava.oracle.MobProfile;
import cava.oracle.Navigator;
import cava.oracle.PathResult;
import cava.oracle.Terrain;
import cava.oracle.TerrainGen;
import cava.oracle.VectorGen;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * **单元层差分**：从 Java 侧跑出「参照实现（oracle）vs 原生内核」的差异报告。
 *
 * <p>与 {@code native/tests/cava_pathfind_vectors.cpp} 的分工（**两者都要，不可互替**）：
 * <ul>
 *   <li>C++ 那个测试直接调**内部 API** {@code cava::pathfind::solve()}，逐节点逐 float 全字段，
 *       10 条 shard 全量 —— 它证明的是「内核算法 == 参照实现」；</li>
 *   <li>本类走**生产路径**：{@code Java → FFM → cava_state_table_upload / cava_region_upload /
 *       cava_mob_profile_upload / cava_pathfind}。它额外覆盖了 C++ 测试**看不到**的那一层：
 *       ABI 胶水（palette→状态表、region 索引顺序、penalty 索引、node_budget、非法输入拒绝）。</li>
 * </ul>
 *
 * <p>三条独立断言（任一条红都是一个真实缺陷，且报告里能区分是哪一条）：
 * <ol>
 *   <li><b>语料完整性</b>：解析出来的输入必须能重建出同一个 terrain（{@code world_hash_low} 相等）
 *       且与 {@code VectorGen.buildCase(caseId)} 逐字段一致 —— 否则后面两条都在比错东西；</li>
 *   <li><b>参照实现 vs 冻结语料</b>：重跑 oracle，与 {@code vectors-NN.bin} 里存的期望逐位比
 *       （语料过期/参照实现漂移会在这里红，而不是伪装成"原生不一致"）；</li>
 *   <li><b>原生 vs 期望</b>：经 C ABI 调原生，与同一份期望比。</li>
 * </ol>
 *
 * <p><b>ABI 可观测字段的边界（如实写在报告里，不要当成"全字段通过"）</b>：
 * {@code CavaPathNode} 只导出 {@code x/y/z/heapIndex/g/f/type/flags}，所以本 harness 能比的是
 * 「节点数 + 每节点 (x,y,z,type,g,f)」。{@code pathLength / distanceToNearestTarget / penalty /
 * visited / expandedCount / traceHash / manhattanDistanceFromTarget / reachesTarget} 在冻结 ABI 上
 * **不可观测** —— 它们由 C++ 侧的 {@code cava_pathfind_vectors.exe} 覆盖（见 {@code tools/parity-unit.ps1} 会两条都跑）。
 *
 * <p>命令行（一条命令）：
 * <pre>
 * java --enable-preview --enable-native-access=ALL-UNNAMED \
 *      -Dcava.native.enabled=true -Dcava.native.path=&lt;cava.dll&gt; \
 *      -cp &lt;classes&gt; cava.parity.PathfindVectorDiff &lt;vectorDir&gt; [maxCases] [--no-oracle] [--verbose]
 * </pre>
 * 零差异打印 {@code ZERO DIFF over N cases} 并 exit 0；否则打印首个差异（第几组 / 哪个字段 / 期望 vs 实际）并 exit 1。
 */
public final class PathfindVectorDiff {

    /** 输出节点容量。原版节点预算上界 = range(32) * followRange(32) = 1024，8192 足够大。 */
    public static final int NODE_CAP = 8192;

    private PathfindVectorDiff() {
    }

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    /** 向量文件里的**输入**（= docs/CAVA-pathfind-oracle-spec.md §10 的冻结布局，大端）。 */
    public static final class CaseInput {
        public int caseId;
        public long caseSeed;
        public int scenario;
        public int makerKind;
        public int originX;
        public int originY;
        public int originZ;
        public int sizeX;
        public int sizeY;
        public int sizeZ;
        public int minY;
        public int seaLevel;
        public int groundY;
        public float width;
        public float height;
        public float stepHeight;
        public int safeFall;
        public int pflags;
        public double ex;
        public double ey;
        public double ez;
        public boolean canWalkOnFluid;
        public int penaltyMask;
        public final float[] penalty = new float[26];
        public int tx;
        public int ty;
        public int tz;
        public int range;
        public float maxRange;
        public int reachRadius;
        public float followRange;
        public int worldHashLow;
    }

    /** 期望路径上的一个节点（全字段，含 ABI 不导出的那些）。 */
    public static final class ExpNode {
        public int x;
        public int y;
        public int z;
        public int type;
        public boolean visited;
        public float pathLength;
        public float penalized;
        public float distance;
        public float heapWeight;
        public float penalty;
    }

    /** 向量文件里的**期望输出**。 */
    public static final class CaseOutput {
        public boolean found;
        public int nodeCount = -1;
        public boolean reachesTarget;
        public int expanded;
        public long traceHash;
        public float manhattan;
        public ExpNode[] nodes = new ExpNode[0];
    }

    /** 一组向量。 */
    public static final class Case {
        public int index;
        public CaseInput in;
        public CaseOutput expected;

        public int caseId() {
            return in.caseId;
        }
    }

    /** 一处差异。 */
    public record Mismatch(int index, int caseId, String field, String expected, String actual, String context) {
    }

    /** 一次比对的结果。 */
    public static final class Result {
        public final Path shard;
        public final int casesInFile;
        public int casesRun;
        public boolean oracleRerun;
        public String nativeSummary = "(未打开)";
        public final List<Mismatch> corpusMismatches = new ArrayList<>();
        public final List<Mismatch> oracleMismatches = new ArrayList<>();
        public final List<Mismatch> nativeMismatches = new ArrayList<>();
        public final Map<String, Long> nativeDiffByField = new LinkedHashMap<>();
        public final Map<String, Long> nativeDiffByScenario = new LinkedHashMap<>();
        public int foundCount;
        public int notFoundCount;

        Result(Path shard, int casesInFile) {
            this.shard = shard;
            this.casesInFile = casesInFile;
        }

        public boolean zeroDiff() {
            return corpusMismatches.isEmpty() && oracleMismatches.isEmpty() && nativeMismatches.isEmpty();
        }

        private static void appendMismatches(StringBuilder sb, String title, List<Mismatch> list, int limit) {
            if (list.isEmpty()) {
                return;
            }
            sb.append(System.lineSeparator()).append("  ").append(title)
                    .append("：共 ").append(list.size()).append(" 处，前 ").append(Math.min(limit, list.size())).append(" 处：");
            for (int i = 0; i < Math.min(limit, list.size()); i++) {
                Mismatch m = list.get(i);
                sb.append(System.lineSeparator()).append("    #").append(m.index()).append(" (caseId=").append(m.caseId())
                        .append(") ").append(m.field())
                        .append("  期望=").append(m.expected())
                        .append("  实际=").append(m.actual());
                if (m.context() != null && !m.context().isEmpty()) {
                    sb.append(System.lineSeparator()).append("        场景: ").append(m.context());
                }
            }
        }

        /** 人读报告。 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Cava 单元层差分：oracle 参照实现 vs 原生内核（经 Java FFM → C ABI）===");
            sb.append(System.lineSeparator()).append("向量文件   : ").append(shard.toAbsolutePath())
                    .append("（文件内 ").append(casesInFile).append(" 组）");
            sb.append(System.lineSeparator()).append("本次比对的组数: ").append(casesRun)
                    .append("（有路径 ").append(foundCount).append(" / 无路径 ").append(notFoundCount).append("）");
            sb.append(System.lineSeparator()).append("参照实现重跑  : ").append(oracleRerun ? "是" : "否（--no-oracle）");
            sb.append(System.lineSeparator()).append("原生         : ").append(nativeSummary);
            sb.append(System.lineSeparator()).append("比对字段     : nodeCount + 每节点 (x,y,z,type,g,f)");
            sb.append(System.lineSeparator()).append("ABI 不可观测 : pathLength / distanceToNearestTarget / penalty / visited /"
                    + " expandedCount / traceHash / manhattan / reachesTarget（由 C++ 侧 cava_pathfind_vectors 覆盖）");

            appendMismatches(sb, "① 语料完整性（输入重建/与 buildCase 不一致）", corpusMismatches, 5);
            appendMismatches(sb, "② 参照实现 vs 冻结语料", oracleMismatches, 5);
            appendMismatches(sb, "③ 原生 vs 期望", nativeMismatches, 10);

            sb.append(System.lineSeparator());
            if (zeroDiff()) {
                sb.append("ZERO DIFF over ").append(casesRun).append(" cases");
            } else {
                sb.append("DIFF FOUND over ").append(casesRun).append(" cases");
                if (!nativeDiffByField.isEmpty()) {
                    sb.append(System.lineSeparator()).append("  原生逐字段差异组数:");
                    for (Map.Entry<String, Long> e : nativeDiffByField.entrySet()) {
                        sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
                    }
                }
                if (!nativeDiffByScenario.isEmpty()) {
                    sb.append(System.lineSeparator()).append("  按场景分布:");
                    for (Map.Entry<String, Long> e : nativeDiffByScenario.entrySet()) {
                        sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
                    }
                }
                if (!nativeMismatches.isEmpty()) {
                    Mismatch f = nativeMismatches.get(0);
                    sb.append(System.lineSeparator()).append("  首个差异: 第 ").append(f.index()).append(" 组 (caseId=")
                            .append(f.caseId()).append(") 字段 ").append(f.field())
                            .append(" 期望=").append(f.expected()).append(" 实际=").append(f.actual());
                }
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------
    // 向量文件读取（大端，逐行对齐 VectorGen 的 writeInput/writeOutput）
    // ------------------------------------------------------------------

    /** 一个 shard 的头部信息。 */
    public record ShardHeader(String magic, int version, int caseCount, long masterSeed, int shardIndex, int shardCount,
                              int headerBytes) {
    }

    private static final class Reader {
        final byte[] b;
        int i;

        Reader(byte[] b) {
            this.b = b;
        }

        int u8() {
            return b[i++] & 0xFF;
        }

        int u16() {
            return (u8() << 8) | u8();
        }

        int i32() {
            return (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
        }

        long u32() {
            return i32() & 0xFFFFFFFFL;
        }

        long i64() {
            long hi = u32();
            long lo = u32();
            return (hi << 32) | lo;
        }

        float f32() {
            return Float.intBitsToFloat(i32());
        }

        double f64() {
            return Double.longBitsToDouble(i64());
        }

        void skip(int n) {
            i += n;
        }
    }

    /** 读 shard（{@code CVOV}）或 golden（{@code CVOG}）文件的头。 */
    public static ShardHeader readHeader(byte[] raw, boolean golden) {
        Reader r = new Reader(raw);
        StringBuilder magic = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            magic.append((char) r.u8());
        }
        int version = r.u16();
        r.skip(2);
        int caseCount = r.i32();
        long master = r.i64();
        int shardIndex = 0;
        int shardCount = 0;
        if (!golden) {
            shardIndex = r.i32();
            shardCount = r.i32();
        }
        return new ShardHeader(magic.toString(), version, caseCount, master, shardIndex, shardCount, r.i);
    }

    private static CaseInput readInput(Reader r) {
        CaseInput c = new CaseInput();
        c.caseId = r.i32();
        c.caseSeed = r.i64();
        c.scenario = r.u8();
        c.makerKind = r.u8();
        r.skip(2);
        c.originX = r.i32();
        c.originY = r.i32();
        c.originZ = r.i32();
        c.sizeX = r.u16();
        c.sizeY = r.u16();
        c.sizeZ = r.u16();
        c.minY = r.i32();
        c.seaLevel = r.i32();
        c.groundY = r.i32();
        c.width = r.f32();
        c.height = r.f32();
        c.stepHeight = r.f32();
        c.safeFall = r.i32();
        c.pflags = r.u8();
        r.skip(3);
        c.ex = r.f64();
        c.ey = r.f64();
        c.ez = r.f64();
        c.canWalkOnFluid = r.u8() != 0;
        r.skip(7);
        c.penaltyMask = (int) r.u32();
        for (int i = 0; i < 26; i++) {
            c.penalty[i] = r.f32();
        }
        c.tx = r.i32();
        c.ty = r.i32();
        c.tz = r.i32();
        c.range = r.u16();
        c.maxRange = r.f32();
        c.reachRadius = r.i32();
        c.followRange = r.f32();
        c.worldHashLow = (int) r.u32();
        return c;
    }

    private static CaseOutput readOutput(Reader r) {
        CaseOutput o = new CaseOutput();
        o.found = r.u8() != 0;
        int n = r.u16();
        o.reachesTarget = r.u8() != 0;
        o.expanded = r.i32();
        o.traceHash = r.i64();
        o.manhattan = r.f32();
        if (!o.found) {
            o.nodeCount = -1;
            return o;
        }
        o.nodeCount = n;
        o.nodes = new ExpNode[n];
        int px = 0;
        int py = 0;
        int pz = 0;
        for (int i = 0; i < n; i++) {
            ExpNode e = new ExpNode();
            if (i == 0) {
                e.x = r.i32();
                e.y = r.i32();
                e.z = r.i32();
            } else {
                e.x = px + (byte) r.u8();
                e.y = py + (byte) r.u8();
                e.z = pz + (byte) r.u8();
            }
            px = e.x;
            py = e.y;
            pz = e.z;
            e.type = r.u8();
            e.visited = (r.u8() & 1) != 0;
            e.pathLength = r.f32();
            e.penalized = r.f32();
            e.distance = r.f32();
            e.heapWeight = r.f32();
            e.penalty = r.f32();
            o.nodes[i] = e;
        }
        return o;
    }

    /** 解析一个 CVOV shard（最多 maxCases 组）。 */
    public static List<Case> readShard(Path file, int maxCases) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        ShardHeader h = readHeader(raw, false);
        if (!"CVOV".equals(h.magic())) {
            throw new IOException("magic 不是 CVOV: " + h.magic() + "（" + file + "）");
        }
        Reader r = new Reader(raw);
        r.i = h.headerBytes();
        List<Case> out = new ArrayList<>();
        int limit = maxCases <= 0 ? h.caseCount() : Math.min(maxCases, h.caseCount());
        for (int i = 0; i < limit; i++) {
            Case c = new Case();
            c.index = i;
            c.in = readInput(r);
            c.expected = readOutput(r);
            out.add(c);
        }
        return out;
    }

    /** 列出目录下所有 {@code vectors-NN.bin}（升序）。 */
    public static List<Path> shards(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("vectors-") && n.endsWith(".bin");
            }).sorted().forEach(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // BlockKind（oracle 调色板）→ CAVA_SF_*/CAVA_PF_*（= C++ 侧 map_bk_flags 的逐位对齐）
    // ------------------------------------------------------------------

    /**
     * 调色板条目 → 状态表 flags。
     *
     * <p>与 {@code native/tests/cava_pathfind_vectors.cpp::map_bk_flags} 逐位一致，
     * 位常量取自 {@link MirrorFlags}（Java 侧唯一定义，与 {@code cava_abi.h} 同源）。
     * 为什么不用 {@code MirrorFlags} 的 MC 采样路径：向量语料里没有 MC 方块状态，
     * 只有 oracle 自己的 19 位 BlockKind 谓词。
     */
    public static int cavaFlags(BlockKind k) {
        int f = 0;
        if (k.has(BlockKind.AIR)) {
            f |= MirrorFlags.SF_AIR;
        }
        if (k.has(BlockKind.TRAPDOOR)) {
            f |= MirrorFlags.PF_TRAPDOOR;
        }
        if (k.has(BlockKind.POWDER_SNOW)) {
            f |= MirrorFlags.PF_POWDER_SNOW;
        }
        if (k.has(BlockKind.CACTUS_OR_BERRY)) {
            f |= MirrorFlags.PF_CACTUS_OR_BERRY;
        }
        if (k.has(BlockKind.HONEY)) {
            f |= MirrorFlags.PF_HONEY;
        }
        if (k.has(BlockKind.COCOA)) {
            f |= MirrorFlags.PF_COCOA;
        }
        if (k.has(BlockKind.CAUTIOUS)) {
            f |= MirrorFlags.PF_CAUTIOUS;
        }
        if (k.has(BlockKind.DOOR)) {
            f |= MirrorFlags.SF_DOOR;
        }
        if (k.has(BlockKind.DOOR_OPEN)) {
            f |= MirrorFlags.SF_OPEN;
        }
        if (k.has(BlockKind.DOOR_HAND)) {
            f |= MirrorFlags.PF_DOOR_HAND;
        }
        if (k.has(BlockKind.RAIL)) {
            f |= MirrorFlags.PF_RAIL;
        }
        if (k.has(BlockKind.LEAVES)) {
            f |= MirrorFlags.PF_LEAVES;
        }
        if (k.has(BlockKind.FENCE_TAG)) {
            f |= MirrorFlags.PF_FENCES;
        }
        if (k.has(BlockKind.WALL_TAG)) {
            f |= MirrorFlags.PF_WALLS;
        }
        if (k.has(BlockKind.FENCE_GATE)) {
            f |= MirrorFlags.PF_FENCE_GATE;
        }
        if (k.has(BlockKind.FIRE_DAMAGE)) {
            f |= MirrorFlags.PF_FIRE_DAMAGE;
        }
        if (k.has(BlockKind.PATHFIND_LAND)) {
            f |= MirrorFlags.PF_PATH_THROUGH_LAND;
        }
        if (k.has(BlockKind.WATER_BLOCK)) {
            f |= MirrorFlags.PF_WATER_BLOCK;
        }
        if (k.fluid == BlockKind.FLUID_WATER) {
            f |= MirrorFlags.SF_FLUID | MirrorFlags.SF_WATER;
        }
        if (k.fluid == BlockKind.FLUID_LAVA) {
            f |= MirrorFlags.SF_FLUID | MirrorFlags.SF_LAVA;
        }
        return f;
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    private static String bits(float v) {
        return String.format(Locale.ROOT, "%s(0x%08x)", v, Float.floatToRawIntBits(v));
    }

    private static String ctx(CaseInput in) {
        String[] names = {"FLAT", "OBSTACLES", "STAIRS", "WATER", "LAVA", "DOORS", "FENCE",
                "SCAFFOLDING", "MAZE", "MIXED"};
        String sc = in.scenario >= 0 && in.scenario < names.length ? names[in.scenario] : ("#" + in.scenario);
        return String.format(Locale.ROOT,
                "scenario=%s maker=%s origin=(%d,%d,%d) size=%dx%dx%d start=(%.2f,%.2f,%.2f) target=(%d,%d,%d) "
                        + "range=%d maxRange=%.2f reach=%d follow=%.2f 地形下界Y=%d 海平面=%d",
                sc, in.makerKind == 0 ? "LAND" : "AMPHIBIOUS", in.originX, in.originY, in.originZ,
                in.sizeX, in.sizeY, in.sizeZ, in.ex, in.ey, in.ez, in.tx, in.ty, in.tz,
                in.range, in.maxRange, in.reachRadius, in.followRange, in.minY, in.seaLevel);
    }

    /** 语料完整性：解析出来的输入 vs {@code VectorGen.buildCase} 重建出来的输入。 */
    private static void checkCorpus(List<Case> cases, Result res, int limit) {
        for (Case c : cases) {
            CaseInput a = c.in;
            VectorGen.CaseSpec b;
            try {
                b = VectorGen.buildCase(a.caseId);
            } catch (Throwable t) {
                res.corpusMismatches.add(new Mismatch(c.index, a.caseId, "buildCase 抛异常",
                        "可重建", t.toString(), ctx(a)));
                continue;
            }
            List<Mismatch> found = new ArrayList<>();
            if (a.caseSeed != b.caseSeed) {
                found.add(new Mismatch(c.index, a.caseId, "caseSeed", Long.toUnsignedString(a.caseSeed, 16),
                        Long.toUnsignedString(b.caseSeed, 16), ctx(a)));
            }
            if (a.scenario != b.scenario || a.makerKind != b.makerKind) {
                found.add(new Mismatch(c.index, a.caseId, "scenario/maker", a.scenario + "/" + a.makerKind,
                        b.scenario + "/" + b.makerKind, ctx(a)));
            }
            if (a.sizeX != b.sizeX || a.sizeY != b.sizeY || a.sizeZ != b.sizeZ
                    || a.originX != b.originX || a.originY != b.originY || a.originZ != b.originZ
                    || a.groundY != b.groundY || a.seaLevel != b.seaLevel) {
                found.add(new Mismatch(c.index, a.caseId, "terrain 参数",
                        String.format(Locale.ROOT, "o=%d,%d,%d s=%d,%d,%d ground=%d sea=%d",
                                b.originX, b.originY, b.originZ, b.sizeX, b.sizeY, b.sizeZ, b.groundY, b.seaLevel),
                        String.format(Locale.ROOT, "o=%d,%d,%d s=%d,%d,%d ground=%d sea=%d",
                                a.originX, a.originY, a.originZ, a.sizeX, a.sizeY, a.sizeZ, a.groundY, a.seaLevel),
                        ctx(a)));
            }
            if (a.tx != b.target[0] || a.ty != b.target[1] || a.tz != b.target[2]) {
                found.add(new Mismatch(c.index, a.caseId, "target",
                        "(" + b.target[0] + "," + b.target[1] + "," + b.target[2] + ")",
                        "(" + a.tx + "," + a.ty + "," + a.tz + ")", ctx(a)));
            }
            if (Float.floatToRawIntBits(a.maxRange) != Float.floatToRawIntBits(b.maxRange)
                    || Float.floatToRawIntBits(a.followRange) != Float.floatToRawIntBits(b.followRange)
                    || a.range != b.range || a.reachRadius != b.reachRadius) {
                found.add(new Mismatch(c.index, a.caseId, "range/maxRange/reach/follow",
                        b.range + "/" + bits(b.maxRange) + "/" + b.reachRadius + "/" + bits(b.followRange),
                        a.range + "/" + bits(a.maxRange) + "/" + a.reachRadius + "/" + bits(a.followRange), ctx(a)));
            }
            // terrain 重建校验：worldHash 的低 32 位（VectorGen 只存了低 32 位）
            Terrain t = TerrainGen.generate(a.caseSeed ^ VectorGen.TERRAIN_SEED_SALT, a.scenario,
                    a.originX, a.originY, a.originZ, a.sizeX, a.sizeY, a.sizeZ, a.groundY, a.seaLevel);
            int low = (int) (t.worldHash() & 0xFFFFFFFFL);
            if (low != a.worldHashLow) {
                found.add(new Mismatch(c.index, a.caseId, "terrain.worldHash 低32位",
                        String.format(Locale.ROOT, "0x%08x（语料记录）", a.worldHashLow),
                        String.format(Locale.ROOT, "0x%08x（本次重建）", low), ctx(a)));
            }
            for (Mismatch m : found) {
                if (res.corpusMismatches.size() < limit) {
                    res.corpusMismatches.add(m);
                }
            }
        }
    }

    /** 参照实现重跑 vs 冻结语料（逐位）。 */
    private static void checkOracle(List<Case> cases, Result res, int limit) {
        for (Case c : cases) {
            VectorGen.CaseSpec spec = VectorGen.buildCase(c.in.caseId);
            VectorGen.runCase(spec);
            PathResult got = spec.result;
            CaseOutput exp = c.expected;
            List<Mismatch> found = new ArrayList<>();
            if (got.found != exp.found) {
                found.add(new Mismatch(c.index, c.in.caseId, "found", String.valueOf(exp.found), String.valueOf(got.found), ctx(c.in)));
            } else if (got.found) {
                if (got.nodes.length != exp.nodeCount) {
                    found.add(new Mismatch(c.index, c.in.caseId, "nodeCount", String.valueOf(exp.nodeCount),
                            String.valueOf(got.nodes.length), ctx(c.in)));
                }
                if (got.reachesTarget != exp.reachesTarget) {
                    found.add(new Mismatch(c.index, c.in.caseId, "reachesTarget", String.valueOf(exp.reachesTarget),
                            String.valueOf(got.reachesTarget), ctx(c.in)));
                }
                if (got.expandedCount != exp.expanded) {
                    found.add(new Mismatch(c.index, c.in.caseId, "expandedCount", String.valueOf(exp.expanded),
                            String.valueOf(got.expandedCount), ctx(c.in)));
                }
                if (got.traceHash != exp.traceHash) {
                    found.add(new Mismatch(c.index, c.in.caseId, "traceHash", Long.toUnsignedString(exp.traceHash, 16),
                            Long.toUnsignedString(got.traceHash, 16), ctx(c.in)));
                }
                if (Float.floatToRawIntBits(got.manhattanDistanceFromTarget) != Float.floatToRawIntBits(exp.manhattan)) {
                    found.add(new Mismatch(c.index, c.in.caseId, "manhattan", bits(exp.manhattan),
                            bits(got.manhattanDistanceFromTarget), ctx(c.in)));
                }
                int n = Math.min(got.nodes.length, exp.nodeCount);
                for (int i = 0; i < n; i++) {
                    PathResult.OutNode a = got.nodes[i];
                    ExpNode b = exp.nodes[i];
                    if (a.x != b.x || a.y != b.y || a.z != b.z) {
                        found.add(new Mismatch(c.index, c.in.caseId, "node[" + i + "].coord",
                                "(" + b.x + "," + b.y + "," + b.z + ")", "(" + a.x + "," + a.y + "," + a.z + ")", ctx(c.in)));
                    }
                    if (a.type != b.type) {
                        found.add(new Mismatch(c.index, c.in.caseId, "node[" + i + "].type", String.valueOf(b.type),
                                String.valueOf(a.type), ctx(c.in)));
                    }
                    if (a.visited != b.visited) {
                        found.add(new Mismatch(c.index, c.in.caseId, "node[" + i + "].visited", String.valueOf(b.visited),
                                String.valueOf(a.visited), ctx(c.in)));
                    }
                    float[][] pairs = {{a.pathLength, b.pathLength}, {a.penalizedPathLength, b.penalized},
                            {a.distanceToNearestTarget, b.distance}, {a.heapWeight, b.heapWeight}, {a.penalty, b.penalty}};
                    String[] names = {"pathLength", "penalizedPathLength", "distanceToNearestTarget", "heapWeight", "penalty"};
                    for (int k = 0; k < pairs.length; k++) {
                        if (Float.floatToRawIntBits(pairs[k][0]) != Float.floatToRawIntBits(pairs[k][1])) {
                            found.add(new Mismatch(c.index, c.in.caseId, "node[" + i + "]." + names[k],
                                    bits(pairs[k][1]), bits(pairs[k][0]), ctx(c.in)));
                        }
                    }
                }
            }
            for (Mismatch m : found) {
                if (res.oracleMismatches.size() < limit) {
                    res.oracleMismatches.add(m);
                }
            }
        }
    }

    /**
     * 跑差分。
     *
     * @param shard     {@code vectors-NN.bin}
     * @param maxCases  0 = 全部
     * @param withOracle 是否重跑参照实现（关掉只比"原生 vs 冻结语料"）
     */
    public static Result run(Path shard, int maxCases, boolean withOracle, boolean verbose) throws IOException {
        List<Case> cases = readShard(shard, maxCases);
        Result res = new Result(shard, readHeader(Files.readAllBytes(shard), false).caseCount());
        res.oracleRerun = withOracle;

        // ① 语料完整性
        checkCorpus(cases, res, 200);
        if (!res.corpusMismatches.isEmpty()) {
            res.casesRun = cases.size();
            return res;   // 语料都不可信，后面两条没有意义（诚实：直接返回，不假装"原生通过"）
        }

        // ② 参照实现重跑
        if (withOracle) {
            checkOracle(cases, res, 200);
            if (!res.oracleMismatches.isEmpty()) {
                res.casesRun = cases.size();
                return res;   // 参照实现与语料不一致时，③ 比的是"谁"就不清楚了
            }
        }

        // ③ 原生
        CavaNative nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "parity-harness");
        nat.setConfiguredEnabled(true);
        boolean open = nat.tryOpen();
        res.nativeSummary = nat.shortReport();
        if (!open) {
            res.nativeMismatches.add(new Mismatch(-1, -1, "native.notOpen",
                    "status=OPEN", "status=" + nat.status() + " detail=" + nat.detail(), null));
            return res;
        }

        try (Arena arena = Arena.ofShared()) {
            MemorySegment recs = CavaNative.allocateArray(arena, CavaLayouts.STATE_RECORD, TerrainGen.PALETTE.length);
            MemorySegment boxes = CavaNative.allocateArray(arena, CavaLayouts.COLLISION_BOX, TerrainGen.PALETTE.length);
            int maxVol = 0;
            for (Case c : cases) {
                maxVol = Math.max(maxVol, c.in.sizeX * c.in.sizeY * c.in.sizeZ);
            }
            MemorySegment ids = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, Math.max(1, maxVol));
            MemorySegment profile = arena.allocate(CavaLayouts.MOB_PROFILE);
            MemorySegment req = arena.allocate(CavaLayouts.PATH_REQUEST);
            MemorySegment out = CavaNative.allocateArray(arena, CavaLayouts.PATH_NODE, NODE_CAP);

            for (Case c : cases) {
                res.casesRun++;
                if (c.expected.found) {
                    res.foundCount++;
                } else {
                    res.notFoundCount++;
                }
                CaseInput in = c.in;

                // ---- 状态表（与 fillStateTable/dump 共用同一份代码，杜绝两处漂移）----
                int boxCursor = fillStateTable(recs, boxes);

                // ---- 地形（从语料输入重建；已在 ① 校验过 worldHash 低 32 位）----
                Terrain t = TerrainGen.generate(in.caseSeed ^ VectorGen.TERRAIN_SEED_SALT, in.scenario,
                        in.originX, in.originY, in.originZ, in.sizeX, in.sizeY, in.sizeZ, in.groundY, in.seaLevel);
                int vol = in.sizeX * in.sizeY * in.sizeZ;
                for (int i = 0; i < vol; i++) {
                    ids.set(ValueLayout.JAVA_INT, (long) i * 4, t.blocks[i] & 0xFF);
                }

                // ---- 档案（与 fillProfile/dump 共用同一份代码）----
                fillProfile(profile, in);

                // ---- 请求：node_budget = (int)((float)range * followRange)（Java 饱和转换 == cava_d2i_sat）----
                int budget = (int) ((float) in.range * in.followRange);
                req.fill((byte) 0);
                req.set(ValueLayout.JAVA_LONG, 0, 0L);                        // reserved1
                req.set(ValueLayout.JAVA_INT, 8, in.tx);
                req.set(ValueLayout.JAVA_INT, 12, in.ty);
                req.set(ValueLayout.JAVA_INT, 16, in.tz);
                req.set(ValueLayout.JAVA_INT, 20, in.reachRadius);
                req.set(ValueLayout.JAVA_FLOAT, 24, in.maxRange);
                req.set(ValueLayout.JAVA_INT, 28, 0);                          // flags
                req.set(ValueLayout.JAVA_INT, 40, budget);                     // max_visited_nodes

                List<Mismatch> found = new ArrayList<>();
                int rcUp = nat.stateTableUpload(nat.handle(), recs, TerrainGen.PALETTE.length, boxes, boxCursor);
                if (rcUp != CavaLayouts.CAVA_OK) {
                    found.add(new Mismatch(c.index, in.caseId, "state_table_upload", "CAVA_OK",
                            CavaLayouts.errorName(rcUp), ctx(in)));
                }
                int rcRegion = nat.regionUpload(nat.handle(), in.sizeX, in.sizeY, in.sizeZ,
                        in.originX, in.originY, in.originZ, ids, vol);
                if (rcRegion != CavaLayouts.CAVA_OK) {
                    found.add(new Mismatch(c.index, in.caseId, "region_upload", "CAVA_OK",
                            CavaLayouts.errorName(rcRegion), ctx(in)));
                }
                int rcProf = nat.mobProfileUpload(nat.handle(), profile);
                if (rcProf != CavaLayouts.CAVA_OK) {
                    found.add(new Mismatch(c.index, in.caseId, "mob_profile_upload", "CAVA_OK",
                            CavaLayouts.errorName(rcProf), ctx(in)));
                }
                if (!found.isEmpty()) {
                    collect(res, found);
                    continue;
                }

                int rc = nat.pathfind(nat.handle(), req, out, NODE_CAP);
                CaseOutput exp = c.expected;
                if (rc < 0) {
                    found.add(new Mismatch(c.index, in.caseId, "pathfind.rc", exp.found ? ">0" : "0",
                            CavaLayouts.errorName(rc), ctx(in)));
                } else if (!exp.found) {
                    if (rc != 0) {
                        found.add(new Mismatch(c.index, in.caseId, "nodeCount", "0（期望无路径）", String.valueOf(rc), ctx(in)));
                    }
                } else if (rc == 0) {
                    found.add(new Mismatch(c.index, in.caseId, "nodeCount", String.valueOf(exp.nodeCount), "0（原生返回无路径）", ctx(in)));
                } else {
                    if (rc != exp.nodeCount) {
                        found.add(new Mismatch(c.index, in.caseId, "nodeCount", String.valueOf(exp.nodeCount),
                                String.valueOf(rc), ctx(in)));
                    }
                    int n = Math.min(rc, exp.nodeCount);
                    for (int i = 0; i < n; i++) {
                        long base = (long) i * CavaLayouts.PATH_NODE.byteSize();
                        int x = out.get(ValueLayout.JAVA_INT, base);
                        int y = out.get(ValueLayout.JAVA_INT, base + 4);
                        int z = out.get(ValueLayout.JAVA_INT, base + 8);
                        float g = out.get(ValueLayout.JAVA_FLOAT, base + 16);
                        float f = out.get(ValueLayout.JAVA_FLOAT, base + 20);
                        int type = out.get(ValueLayout.JAVA_INT, base + 24);
                        ExpNode e = exp.nodes[i];
                        if (x != e.x || y != e.y || z != e.z) {
                            found.add(new Mismatch(c.index, in.caseId, "node[" + i + "].coord",
                                    "(" + e.x + "," + e.y + "," + e.z + ")", "(" + x + "," + y + "," + z + ")", ctx(in)));
                        }
                        if (type != e.type) {
                            found.add(new Mismatch(c.index, in.caseId, "node[" + i + "].type",
                                    String.valueOf(e.type), String.valueOf(type), ctx(in)));
                        }
                        if (Float.floatToRawIntBits(g) != Float.floatToRawIntBits(e.penalized)) {
                            found.add(new Mismatch(c.index, in.caseId, "node[" + i + "].g(penalizedPathLength)",
                                    bits(e.penalized), bits(g), ctx(in)));
                        }
                        if (Float.floatToRawIntBits(f) != Float.floatToRawIntBits(e.heapWeight)) {
                            found.add(new Mismatch(c.index, in.caseId, "node[" + i + "].f(heapWeight)",
                                    bits(e.heapWeight), bits(f), ctx(in)));
                        }
                    }
                }
                collect(res, found);
                if (verbose && (c.index + 1) % 500 == 0) {
                    System.out.printf(Locale.ROOT, "  ... %d/%d 组（差异 %d）%n", c.index + 1, cases.size(),
                            res.nativeMismatches.size());
                }
            }
        }
        // 故意不 close：同一个 JVM 里要能跑多腿（负控制腿要复用句柄）。进程退出即释放。
        return res;
    }

    private static void collect(Result res, List<Mismatch> found) {
        if (found.isEmpty()) {
            return;
        }
        res.nativeDiffByScenario.merge(scenarioName(found.get(0)), 1L, Long::sum);
        for (Mismatch m : found) {
            if (res.nativeMismatches.size() < 500) {
                res.nativeMismatches.add(m);
            }
            res.nativeDiffByField.merge(m.field(), 1L, Long::sum);
        }
    }

    private static final String[] SCENARIO_NAMES = {"FLAT", "OBSTACLES", "STAIRS", "WATER", "LAVA", "DOORS",
            "FENCE", "SCAFFOLDING", "MAZE", "MIXED"};

    /** 从 context 串里取出场景名（只用于统计聚合）。 */
    private static String scenarioName(Mismatch m) {
        String c = m.context();
        if (c == null) {
            return "(no-context)";
        }
        int i = c.indexOf("scenario=");
        if (i < 0) {
            return "(unknown)";
        }
        int j = c.indexOf(' ', i);
        return j < 0 ? c.substring(i) : c.substring(i, j);
    }

    /** {@code --dump <组号>}：把一组的两侧节点全量打出来（定位差异用）。 */
    public static void dumpCase(Path shard, int index, boolean withOracle) throws IOException {
        List<Case> cases = readShard(shard, index + 1);
        if (index >= cases.size()) {
            System.out.println("组号超范围：" + index + " >= " + cases.size());
            return;
        }
        Case c = cases.get(index);
        System.out.println("=== dump 第 " + index + " 组 (caseId=" + c.in.caseId + ") ===");
        System.out.println(ctx(c.in));
        Terrain t = TerrainGen.generate(c.in.caseSeed ^ VectorGen.TERRAIN_SEED_SALT, c.in.scenario,
                c.in.originX, c.in.originY, c.in.originZ, c.in.sizeX, c.in.sizeY, c.in.sizeZ, c.in.groundY, c.in.seaLevel);
        System.out.printf(Locale.ROOT, "terrain.worldHash 低32位 语料=0x%08x 重建=0x%08x%n",
                c.in.worldHashLow, (int) (t.worldHash() & 0xFFFFFFFFL));
        if (withOracle) {
            VectorGen.CaseSpec spec = VectorGen.buildCase(c.in.caseId);
            VectorGen.runCase(spec);
            PathResult pr = spec.result;
            System.out.println("oracle 本次重跑: found=" + pr.found + " nodes=" + (pr.found ? pr.nodes.length : -1)
                    + " reachesTarget=" + pr.reachesTarget + " expanded=" + pr.expandedCount
                    + " traceHash=" + Long.toUnsignedString(pr.traceHash, 16));
        }
        System.out.println("语料期望: found=" + c.expected.found + " nodes=" + c.expected.nodeCount
                + " reachesTarget=" + c.expected.reachesTarget + " expanded=" + c.expected.expanded
                + " traceHash=" + Long.toUnsignedString(c.expected.traceHash, 16));
        System.out.println("  期望节点（附：该坐标在**本次重建地形**里的 palette 下标 / 原版 common 类型）:");
        for (int i = 0; i < c.expected.nodes.length; i++) {
            ExpNode e = c.expected.nodes[i];
            System.out.printf(Locale.ROOT, "    [%d] (%d,%d,%d) type=%d palette=%d common=%d visited=%s pathLen=%s pen=%s dist=%s f=%s pen(field)=%s%n",
                    i, e.x, e.y, e.z, e.type, t.paletteIndexAt(e.x, e.y, e.z),
                    LandMaker.getCommonNodeType(t, e.x, e.y, e.z).ordinal(), e.visited, bits(e.pathLength), bits(e.penalized),
                    bits(e.distance), bits(e.heapWeight), bits(e.penalty));
        }

        CavaNative nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "parity-harness");
        nat.setConfiguredEnabled(true);
        if (!nat.tryOpen()) {
            System.out.println("原生未打开: " + nat.shortReport());
            return;
        }
        try (Arena arena = Arena.ofShared()) {
            MemorySegment recs = CavaNative.allocateArray(arena, CavaLayouts.STATE_RECORD, TerrainGen.PALETTE.length);
            MemorySegment boxes = CavaNative.allocateArray(arena, CavaLayouts.COLLISION_BOX, TerrainGen.PALETTE.length);
            int vol = c.in.sizeX * c.in.sizeY * c.in.sizeZ;
            MemorySegment ids = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, vol);
            MemorySegment profile = arena.allocate(CavaLayouts.MOB_PROFILE);
            MemorySegment req = arena.allocate(CavaLayouts.PATH_REQUEST);
            MemorySegment out = CavaNative.allocateArray(arena, CavaLayouts.PATH_NODE, NODE_CAP);
            int boxCursor = fillStateTable(recs, boxes);
            for (int i = 0; i < vol; i++) {
                ids.set(ValueLayout.JAVA_INT, (long) i * 4, t.blocks[i] & 0xFF);
            }
            fillProfile(profile, c.in);
            int budget = (int) ((float) c.in.range * c.in.followRange);
            req.fill((byte) 0);
            req.set(ValueLayout.JAVA_INT, 8, c.in.tx);
            req.set(ValueLayout.JAVA_INT, 12, c.in.ty);
            req.set(ValueLayout.JAVA_INT, 16, c.in.tz);
            req.set(ValueLayout.JAVA_INT, 20, c.in.reachRadius);
            req.set(ValueLayout.JAVA_FLOAT, 24, c.in.maxRange);
            req.set(ValueLayout.JAVA_INT, 40, budget);
            System.out.println("上传: state_table=" + CavaLayouts.errorName(
                    nat.stateTableUpload(nat.handle(), recs, TerrainGen.PALETTE.length, boxes, boxCursor))
                    + " region=" + CavaLayouts.errorName(nat.regionUpload(nat.handle(), c.in.sizeX, c.in.sizeY, c.in.sizeZ,
                    c.in.originX, c.in.originY, c.in.originZ, ids, vol))
                    + " profile=" + CavaLayouts.errorName(nat.mobProfileUpload(nat.handle(), profile)));

            // ---- 喂给原生的状态表（读回自己写的段）----
            System.out.println("  上传的 16 条状态记录（flags / box_offset / box_count / pathTypeIdx / malus）:");
            for (int i = 0; i < TerrainGen.PALETTE.length; i++) {
                long ro = (long) i * CavaLayouts.STATE_RECORD.byteSize();
                int fl = recs.get(ValueLayout.JAVA_INT, ro);
                System.out.printf(Locale.ROOT, "    [%2d] flags=0x%08x %-70s box=%d n=%d type=%d%n", i, fl,
                        MirrorFlags.names(fl).toString(), recs.get(ValueLayout.JAVA_INT, ro + 4),
                        recs.get(ValueLayout.JAVA_INT, ro + 8), recs.get(ValueLayout.JAVA_INT, ro + 12));
            }
            // ---- 原生的区域读回：逐格与本地 terrain 比 ----
            MemorySegment one = arena.allocate(ValueLayout.JAVA_INT);
            int regionBad = 0;
            String firstBad = null;
            for (int ly = 0; ly < c.in.sizeY; ly++) {
                for (int lz = 0; lz < c.in.sizeZ; lz++) {
                    for (int lx = 0; lx < c.in.sizeX; lx++) {
                        int wx = c.in.originX + lx;
                        int wy = c.in.originY + ly;
                        int wz = c.in.originZ + lz;
                        nat.regionStateIdAt(nat.handle(), wx, wy, wz, one);
                        int got = one.get(ValueLayout.JAVA_INT, 0);
                        int want = t.paletteIndexAt(wx, wy, wz);
                        if (got != want) {
                            regionBad++;
                            if (firstBad == null) {
                                firstBad = "(" + wx + "," + wy + "," + wz + ") 原生=" + got + " 本地=" + want;
                            }
                        }
                    }
                }
            }
            System.out.println("  区域读回校验：" + (regionBad == 0 ? "全部一致（" + vol + " 格）" : regionBad + " 格不一致，首个 " + firstBad));
            // ---- 原版参照实现对争议格子的类型（全新 cache，不受运行期状态影响）----
            LayoutMakerView v = oracleNodeTypes(c.in, t, c.expected);
            for (String line : v.lines) {
                System.out.println("    " + line);
            }
            int rc = nat.pathfind(nat.handle(), req, out, NODE_CAP);
            System.out.println("原生 cava_pathfind rc=" + rc + (rc < 0 ? " (" + CavaLayouts.errorName(rc) + ")" : ""));
            System.out.println("  原生节点:");
            for (int i = 0; i < Math.max(0, rc); i++) {
                long base = (long) i * CavaLayouts.PATH_NODE.byteSize();
                int nx = out.get(ValueLayout.JAVA_INT, base);
                int ny = out.get(ValueLayout.JAVA_INT, base + 4);
                int nz = out.get(ValueLayout.JAVA_INT, base + 8);
                System.out.printf(Locale.ROOT, "    [%d] (%d,%d,%d) heapIndex=%d type=%d palette=%d common=%d g=%s f=%s%n", i,
                        nx, ny, nz, out.get(ValueLayout.JAVA_INT, base + 12),
                        out.get(ValueLayout.JAVA_INT, base + 24),
                        t.paletteIndexAt(nx, ny, nz), LandMaker.getCommonNodeType(t, nx, ny, nz).ordinal(),
                        bits(out.get(ValueLayout.JAVA_FLOAT, base + 16)),
                        bits(out.get(ValueLayout.JAVA_FLOAT, base + 20)));
            }
            System.out.println("  起点周围地形（palette 下标）。origin=(" + t.originX + "," + t.originY + "," + t.originZ
                    + ") size=(" + t.sizeX + "," + t.sizeY + "," + t.sizeZ + ") minY=" + t.minY + " seaLevel=" + t.seaLevel
                    + " groundY=" + c.in.groundY);
            int sx = (int) Math.floor(c.in.ex);
            int sz = (int) Math.floor(c.in.ez);
            for (int y = c.in.groundY; y <= c.in.groundY + 2; y++) {
                StringBuilder sb = new StringBuilder();
                for (int z = sz - 2; z <= sz + 2; z++) {
                    sb.append("z=").append(z).append(':');
                    for (int x = sx - 2; x <= sx + 2; x++) {
                        sb.append(String.format(Locale.ROOT, " x%d=%d", x, t.paletteIndexAt(x, y, z)));
                    }
                    sb.append("  |");
                }
                System.out.println("    y=" + y + " | " + sb);
            }
        } finally {
            nat.close();
        }
    }

    private static final class LayoutMakerView {
        final List<String> lines = new ArrayList<>();
    }

    /** 用**全新** LandMaker（干净 cache）算争议格子的原版类型，用于分清"谁错了"。 */
    private static LayoutMakerView oracleNodeTypes(CaseInput in, Terrain t, CaseOutput exp) {
        LayoutMakerView out = new LayoutMakerView();
        VectorGen.CaseSpec spec = VectorGen.buildCase(in.caseId);
        MobProfile p = spec.profile;
        LandMaker maker = new LandMaker();
        maker.amphibious = in.makerKind == VectorGen.MAKER_AMPHIBIOUS;
        maker.penalizeDeepWater = p.penalizeDeepWater;
        maker.init(t, p);
        out.lines.add(String.format(Locale.ROOT,
                "profile: width=%s height=%s stepHeight=%s safeFall=%d onGround=%s touchingWater=%s canSwim=%s "
                        + "canOpenDoors=%s canEnterOpenDoors=%s canWalkOverFences=%s amphibious=%s penalizeDeepWater=%s "
                        + "penaltyMask=0x%06x",
                Float.toString(in.width), Float.toString(in.height), Float.toString(in.stepHeight), in.safeFall,
                (in.pflags & (1 << 6)) != 0, (in.pflags & (1 << 7)) != 0, (in.pflags & (1 << 2)) != 0,
                (in.pflags & 1) != 0, (in.pflags & (1 << 1)) != 0, (in.pflags & (1 << 3)) != 0,
                in.makerKind == VectorGen.MAKER_AMPHIBIOUS, p.penalizeDeepWater, in.penaltyMask));
        out.lines.add(String.format(Locale.ROOT, "entityBlock 尺寸（= floor(w+1), floor(h+1), floor(w+1)）: x=%.0f y=%.0f z=%.0f",
                Math.floor(in.width + 1.0f), Math.floor(in.height + 1.0f), Math.floor(in.width + 1.0f)));
        out.lines.add("每格：本地 palette / common / 全新 maker 的 getNodeType");
        for (int i = 0; i < exp.nodes.length; i++) {
            ExpNode e = exp.nodes[i];
            out.lines.add(String.format(Locale.ROOT, "    期望[%d] (%d,%d,%d) palette=%d common=%d freshType=%s penalty=%.1f",
                    i, e.x, e.y, e.z, t.paletteIndexAt(e.x, e.y, e.z),
                    LandMaker.getCommonNodeType(t, e.x, e.y, e.z).ordinal(),
                    maker.getNodeType(p, e.x, e.y, e.z).name(),
                    p.getPathfindingPenalty(maker.getNodeType(p, e.x, e.y, e.z))));
        }
        return out;
    }

    /** 负控制：把 CAN_SWIM 填到错误的 caps 位（= 已修的真实喂入 bug），比对器**必须**报差异。 */
    private static boolean wrongCaps() {
        return Boolean.getBoolean("cava.parity.selftest.wrongCaps");
    }

    /** 状态表填充（差分与 dump 共用）。返回 box_count。 */
    private static int fillStateTable(MemorySegment recs, MemorySegment boxes) {
        int boxCursor = 0;
        long recOff = 0;
        long boxOff = 0;
        for (int i = 0; i < TerrainGen.PALETTE.length; i++) {
            BlockKind k = TerrainGen.PALETTE[i];
            boolean empty = (k.collisionMaxY <= k.collisionMinY && k.collisionMaxY <= 0.0f);
            recs.set(ValueLayout.JAVA_INT, recOff, cavaFlags(k));
            recs.set(ValueLayout.JAVA_INT, recOff + 4, empty ? -1 : boxCursor);
            recs.set(ValueLayout.JAVA_INT, recOff + 8, empty ? 0 : 1);
            recs.set(ValueLayout.JAVA_INT, recOff + 12, 0);
            recs.set(ValueLayout.JAVA_FLOAT, recOff + 16, 0.0f);
            if (!empty) {
                boxes.set(ValueLayout.JAVA_FLOAT, boxOff, 0.0f);
                boxes.set(ValueLayout.JAVA_FLOAT, boxOff + 4, k.collisionMinY);
                boxes.set(ValueLayout.JAVA_FLOAT, boxOff + 8, 0.0f);
                boxes.set(ValueLayout.JAVA_FLOAT, boxOff + 12, 1.0f);
                boxes.set(ValueLayout.JAVA_FLOAT, boxOff + 16, k.collisionMaxY);
                boxes.set(ValueLayout.JAVA_FLOAT, boxOff + 20, 1.0f);
                boxCursor++;
                boxOff += CavaLayouts.COLLISION_BOX.byteSize();
            }
            recOff += CavaLayouts.STATE_RECORD.byteSize();
        }
        return boxCursor;
    }

    /** 档案填充（差分与 dump 共用）。 */
    private static void fillProfile(MemorySegment profile, CaseInput in) {
        profile.fill((byte) 0);
        int caps = 0;
        if ((in.pflags & 1) != 0) {
            caps |= 1 << 0;
        }
        if ((in.pflags & (1 << 1)) != 0) {
            caps |= 1 << 1;
        }
        if ((in.pflags & (1 << 2)) != 0) {
            // CAVA_NAV_CAN_SWIM == 1<<6（**不是** 1<<2，1<<2 是 CAN_FLOAT）。
            // 负控制开关：history 上这里真的填错过，填错时 10000 组里会红 11 组 / 126 处
            // —— 保留这个开关，是为了让"比对器能失败"永远可复现（见 --selftest）。
            caps |= wrongCaps() ? (1 << 2) : (1 << 6);
        }
        if ((in.pflags & (1 << 3)) != 0) {
            caps |= 1 << 5;
        }
        if ((in.pflags & (1 << 4)) != 0) {
            caps |= 1 << 3;
        }
        if ((in.pflags & (1 << 5)) != 0) {
            caps |= 1 << 4;
        }
        if ((in.pflags & (1 << 6)) != 0) {
            caps |= 1 << 8;
        }
        if ((in.pflags & (1 << 7)) != 0) {
            caps |= 1 << 9;
        }
        if (in.canWalkOnFluid) {
            caps |= 1 << 10;
        }
        long off = CavaLayouts.MobProfileOffset.PENALTY;
        for (int i = 0; i < 26; i++) {
            profile.set(ValueLayout.JAVA_FLOAT, off + 4L * i, in.penalty[i]);
        }
        profile.set(ValueLayout.JAVA_FLOAT, CavaLayouts.MobProfileOffset.RESERVED_MAX_FALL, 0.0f);
        profile.set(ValueLayout.JAVA_DOUBLE, CavaLayouts.MobProfileOffset.START_X, in.ex);
        profile.set(ValueLayout.JAVA_DOUBLE, CavaLayouts.MobProfileOffset.START_Y, in.ey);
        profile.set(ValueLayout.JAVA_DOUBLE, CavaLayouts.MobProfileOffset.START_Z, in.ez);
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.START_BLOCK_X, (int) Math.floor(in.ex));
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.START_BLOCK_Y, (int) Math.floor(in.ey));
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.START_BLOCK_Z, (int) Math.floor(in.ez));
        profile.set(ValueLayout.JAVA_FLOAT, CavaLayouts.MobProfileOffset.WIDTH, in.width);
        profile.set(ValueLayout.JAVA_FLOAT, CavaLayouts.MobProfileOffset.HEIGHT, in.height);
        profile.set(ValueLayout.JAVA_FLOAT, CavaLayouts.MobProfileOffset.STEP_HEIGHT, in.stepHeight);
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.SAFE_FALL_DISTANCE, in.safeFall);
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.MIN_Y, in.minY);
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.SEA_LEVEL, in.seaLevel);
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.CAPS, caps);
        profile.set(ValueLayout.JAVA_INT, CavaLayouts.MobProfileOffset.PENALTY_MASK, in.penaltyMask);
    }

    /** CLI。 */
    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        List<String> pos = new ArrayList<>();
        boolean withOracle = true;
        boolean verbose = false;
        int maxCases = 0;
        int dump = -1;
        for (int ai = 0; ai < args.length; ai++) {
            String a = args[ai];
            switch (a) {
                case "--no-oracle" -> withOracle = false;
                case "--verbose" -> verbose = true;
                case "--selftest" -> {
                    // 已在前面解析；这里只是为了不让它落进 pos
                }
                case "--dump" -> {
                    if (ai + 1 < args.length) {
                        dump = Integer.parseInt(args[++ai]);
                    }
                }
                case "--help", "-h" -> {
                    System.out.println("用法: PathfindVectorDiff <vectorDir|vectors-NN.bin> [maxCases] [--no-oracle] [--verbose]"
                            + System.lineSeparator() + "      PathfindVectorDiff <vectors-NN.bin> --dump <组号>");
                    return;
                }
                default -> {
                    if (a.matches("\\d+")) {
                        maxCases = Integer.parseInt(a);
                    } else {
                        pos.add(a);
                    }
                }
            }
        }
        boolean selftest = false;
        for (String a : args) {
            if ("--selftest".equals(a)) {
                selftest = true;
            }
        }
        if (dump >= 0 && !pos.isEmpty()) {
            Path shardFile = Files.isDirectory(Path.of(pos.get(0)))
                    ? shards(Path.of(pos.get(0))).get(0)
                    : Path.of(pos.get(0));
            dumpCase(shardFile, dump, withOracle);
            return;
        }
        if (pos.isEmpty()) {
            System.out.println("用法: PathfindVectorDiff <vectorDir|vectors-NN.bin> [maxCases] [--no-oracle] [--verbose]");
            System.exit(2);
            return;
        }
        Path target = Path.of(pos.get(0));
        List<Path> shardList;
        if (Files.isDirectory(target)) {
            shardList = shards(target);
        } else {
            shardList = List.of(target);
        }
        if (shardList.isEmpty()) {
            System.out.println("没有找到 vectors-*.bin：" + target);
            System.exit(2);
            return;
        }
        boolean allZero = true;
        long totalCases = 0;
        for (Path shard : shardList) {
            System.out.println("[shard] " + shard.getFileName());
            Result r = run(shard, maxCases, withOracle, verbose);
            System.out.println(r.report());
            System.out.println();
            allZero &= r.zeroDiff();
            totalCases += r.casesRun;
        }
        if (shardList.size() > 1) {
            System.out.println(allZero
                    ? "ALL SHARDS ZERO DIFF over " + totalCases + " cases（" + shardList.size() + " 个 shard）"
                    : "SHARD DIFFS FOUND（见上面的逐 shard 报告）");
        }

        // ---- 负控制：故意把 CAN_SWIM 填到错误的 caps 位，比对器**必须**报差异 ----
        boolean negativeOk = true;
        if (selftest) {
            String key = "cava.parity.selftest.wrongCaps";
            System.setProperty(key, "true");
            System.out.println("---- 负控制：-D" + key + "=true（CAN_SWIM 填到 1<<2 而不是 1<<6）----");
            // 负控制腿**必须跑够组数**：这个错误喂入的指纹是"126 处 / 11 组"，首个敏感组是 #1281
            // （场景 MIXED + 会游泳的 mob + 水格）。只跑前 50 组会得到"零差异"，
            // 从而把"比对器是瞎的"这个错误结论报出来 —— 本机实测踩过一次。
            Result bad = run(shardList.get(0), Math.max(maxCases, 2500), false, false);
            System.clearProperty(key);
            boolean detected = !bad.zeroDiff();
            negativeOk = detected;
            System.out.println("负控制结果: " + (detected ? "PASS（检出 " + bad.nativeMismatches.size()
                    + " 处差异，首个 " + (bad.nativeMismatches.isEmpty() ? "-" : bad.nativeMismatches.get(0).field()) + "）"
                    : "FAIL（错误喂入竟然零差异 ⇒ 比对器是瞎的）"));
            System.out.println();
        }
        System.exit(allZero && negativeOk ? 0 : 1);
    }
}
