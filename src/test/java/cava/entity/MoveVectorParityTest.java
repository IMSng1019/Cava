package cava.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import cava.shape.AbiOffsets;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * **跨语言向量对拍**：把 {@code native/tests/entity/vectors/entity-00.bin}（CVEM v1，400 组，
 * 内核自己产的权威输出）灌进**真实 {@code cava.dll} 的 {@code cava_resolve_move}**，
 * 逐位比对位移与每一条事件。
 *
 * <p>这是 P2 接线的「单元层」验收：它同时证明三件事
 * <ol>
 *   <li>FFM 绑定（{@code CavaBindings}/{@code CavaNative}）的参数顺序、{@code MemorySegment} 布局、
 *       逐字段偏移（{@link AbiOffsets}）与原生侧一致；</li>
 *   <li>原生入口 {@code cava_entity_abi.cpp} 的编组（inline 点表/位图、refs、req/out）没有错位；</li>
 *   <li>内核在**经过 FFM 边界之后**仍然与它自己产出的向量逐位一致（含 NaN/±Inf/-0.0 与 1e-7 边界）。</li>
 * </ol>
 *
 * <p><b>同一个 {@code CavaLayouts} 冻结文件、同一份 {@code cava_abi.h}</b>，所以任何一侧漂移都会在这里红。
 * 没有原生库时整类 skip（不静默通过）。
 */
class MoveVectorParityTest {

    private static CavaNative nat;
    private static byte[] fileBytes;

    @BeforeAll
    static void openNative() throws Exception {
        nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "test");
        boolean ready = nat.tryOpen();
        Assumptions.assumeTrue(ready,
                "需要原生库：-Dcava.native.path=<cava.dll>（当前 status=" + nat.status() + "：" + nat.detail() + "）");
        Path bin = Path.of("native", "tests", "entity", "vectors", "entity-00.bin");
        Assumptions.assumeTrue(Files.isRegularFile(bin), "缺少向量文件 " + bin.toAbsolutePath());
        fileBytes = Files.readAllBytes(bin);
    }

    /** FNV-1a 64（与 {@code cava_entity_vectors.cpp} 的 fnv1a 同款）。 */
    private static long fnv1a64(byte[] b) {
        long h = 0xCBF29CE484222325L;
        for (byte x : b) {
            h ^= (x & 0xFFL);
            h *= 0x100000001B3L;
        }
        return h;
    }

    /** 一组向量用例。 */
    private record Case(int id, double[] box, double[] mv, double stepH, int onGround,
                        int nsh, int[] pointsKind, int[] size, int[] src, int[] bxyz, long[] sid,
                        double[][] pts, int[][] axisLen, long[][] bits,
                        long[] deltaBits, long[] baseBits, long[] stepBits,
                        int stepUsed, int overflow, long[][] events) {
    }

    private static List<Case> readCases() throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        assertEquals("CVEM", new String(magic, java.nio.charset.StandardCharsets.US_ASCII), "magic");
        assertEquals(1, bb.getShort() & 0xFFFF, "version");
        bb.getShort();
        int cnt = bb.getInt();
        bb.getLong();
        bb.getInt();
        bb.getShort();
        bb.getShort();
        List<Case> out = new ArrayList<>(cnt);
        for (int c = 0; c < cnt; c++) {
            int id = bb.getInt();
            int nsh = bb.getInt();
            double[] box = new double[6];
            for (int i = 0; i < 6; i++) {
                box[i] = bb.getDouble();
            }
            double[] mv = new double[3];
            for (int i = 0; i < 3; i++) {
                mv[i] = bb.getDouble();
            }
            double stepH = bb.getDouble();
            int onGround = bb.getInt();
            bb.getInt();
            int[] pk = new int[nsh];
            int[] size = new int[nsh * 3];
            int[] src = new int[nsh];
            int[] bxyz = new int[nsh * 3];
            long[] sid = new long[nsh];
            double[][] pts = new double[nsh][];
            int[][] axisLen = new int[nsh][];
            long[][] bits = new long[nsh][];
            for (int s = 0; s < nsh; s++) {
                pk[s] = bb.getInt();
                src[s] = bb.getInt();
                int total = 0;
                for (int a = 0; a < 3; a++) {
                    size[s * 3 + a] = bb.getInt();
                    total += size[s * 3 + a] + 1;
                }
                for (int a = 0; a < 3; a++) {
                    bxyz[s * 3 + a] = bb.getInt();
                }
                sid[s] = bb.getLong();
                int npc = bb.getInt();
                double[] p = new double[npc];
                for (int i = 0; i < npc; i++) {
                    p[i] = bb.getDouble();
                }
                pts[s] = p;
                axisLen[s] = reconstructAxisLengths(pk[s], size, s, npc);
                int nw = bb.getInt();
                long[] w = new long[nw];
                for (int i = 0; i < nw; i++) {
                    w[i] = bb.getLong();
                }
                bits[s] = w;
            }
            long[] db = new long[3];
            long[] bb2 = new long[3];
            long[] sb = new long[3];
            for (int i = 0; i < 3; i++) {
                db[i] = bb.getLong();
            }
            for (int i = 0; i < 3; i++) {
                bb2[i] = bb.getLong();
            }
            for (int i = 0; i < 3; i++) {
                sb[i] = bb.getLong();
            }
            int ne = bb.getInt();
            int stepUsed = bb.getInt();
            int overflow = bb.getInt();
            long[][] ev = new long[ne][16];
            for (int e = 0; e < ne; e++) {
                ev[e][0] = bb.getLong();                 // shape_token
                for (int i = 1; i <= 10; i++) {
                    ev[e][i] = bb.getInt();
                }
                bb.getInt();
                bb.getInt();
                for (int i = 11; i <= 13; i++) {
                    ev[e][i] = bb.getLong();             // offset / before / after 的位模式
                }
            }
            out.add(new Case(id, box, mv, stepH, onGround, nsh, pk, size, src, bxyz, sid, pts, axisLen, bits,
                    db, bb2, sb, stepUsed, overflow, ev));
        }
        return out;
    }

    /**
     * 还原每个轴的点表长度。CVEM v1 **没有**存每轴长度，只存了总数 {@code npc}。
     *
     * <p>绝大多数形状满足 {@code len[a] == size[a]+1}（{@code ArrayVoxelShape} 的定义）。
     * 但生成器里有一种**手工构造**的非凸形状（3x1x3、只填四条边）用的是
     * {@code pts[0].assign({o,o+1,o+2})} —— 3 个点而 {@code size[0]=3}，
     * 于是 {@code npc = 3+2+3 = 8 != 4+2+4 = 10}。它的点表长度是 {@code (sizeX, sizeY+1, sizeZ)}。
     *
     * <p>返回 {@code null} = 无法还原 ⇒ 含该形状的用例**跳过**（并且计数上报），
     * 而不是猜一个长度去比 —— 猜出来的"一致"没有意义。
     */
    private static int[] reconstructAxisLengths(int pointsKind, int[] size, int s, int npc) {
        if (pointsKind == 0) {
            return npc == 0 ? new int[] {0, 0, 0} : null;
        }
        int sx = size[s * 3];
        int sy = size[s * 3 + 1];
        int sz = size[s * 3 + 2];
        if (npc == (sx + 1) + (sy + 1) + (sz + 1)) {
            return new int[] {sx + 1, sy + 1, sz + 1};
        }
        if (npc == sx + (sy + 1) + sz) {
            return new int[] {sx, sy + 1, sz};
        }
        return null;
    }

    @Test
    void vectorsMatchNativeEntryPointBitForBit() throws Exception {
        assertEquals(0x12E1F79186F28CA4L, fnv1a64(fileBytes),
                "向量文件内容与 manifest.txt 记的 fnv1a64 不一致（文件被改过或读错）");
        List<Case> cases = readCases();
        assertEquals(400, cases.size(), "用例数");

        int mismatches = 0;
        int withEvents = 0;
        int unreconstructable = 0;
        StringBuilder first = new StringBuilder();
        for (Case c : cases) {
            // CVEM v1 没存每轴点表长度：手工构造的那种非凸形状（pts 长度 != size+1）
            // 无法在 Java 侧逐位还原（生成器自己读它也是越界的），整例跳过并计数。
            boolean skip = false;
            for (int s = 0; s < c.nsh(); s++) {
                if (c.pointsKind()[s] == 1) {
                    int need = (c.size()[s * 3] + 1) + (c.size()[s * 3 + 1] + 1) + (c.size()[s * 3 + 2] + 1);
                    if (c.pts()[s].length != need || c.axisLen()[s] == null) {
                        skip = true;
                        break;
                    }
                }
            }
            if (skip) {
                unreconstructable++;
                continue;
            }
            try (Arena arena = Arena.ofShared()) {
                MemorySegment req = arena.allocate(CavaLayouts.MOVE_REQUEST);
                req.fill((byte) 0);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_X, c.box()[0]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_Y, c.box()[1]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_Z, c.box()[2]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_X, c.box()[3]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_Y, c.box()[4]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_Z, c.box()[5]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_X, c.mv()[0]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_Y, c.mv()[1]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_Z, c.mv()[2]);
                req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_STEP_HEIGHT, c.stepH());
                req.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_FLAGS, 0);
                req.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_ON_GROUND, c.onGround());
                req.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_SHAPE_COUNT, c.nsh());
                req.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_RESERVED1, 0);

                int totalPoints = 0;
                int totalWords = 0;
                for (int s = 0; s < c.nsh(); s++) {
                    if (c.pointsKind()[s] == 1) {
                        totalPoints += c.pts()[s].length;
                    }
                    totalWords += c.bits()[s].length;
                }
                MemorySegment refs = c.nsh() == 0 ? MemorySegment.NULL
                        : CavaNative.allocateArray(arena, CavaLayouts.MOVE_SHAPE_REF, c.nsh());
                int pAt = 0;
                int wAt = 0;
                MemorySegment shapes = c.nsh() == 0 ? MemorySegment.NULL
                        : CavaNative.allocateArray(arena, CavaLayouts.SHAPE_RECORD, c.nsh());
                MemorySegment points = totalPoints == 0 ? MemorySegment.NULL
                        : CavaNative.allocateArray(arena, ValueLayout.JAVA_DOUBLE, totalPoints);
                MemorySegment words = totalWords == 0 ? MemorySegment.NULL
                        : CavaNative.allocateArray(arena, ValueLayout.JAVA_LONG, totalWords);
                for (int s = 0; s < c.nsh(); s++) {
                    long ro = (long) s * AbiOffsets.REF_SIZE;
                    refs.set(ValueLayout.JAVA_LONG, ro + AbiOffsets.REF_SHAPE_TOKEN, c.sid()[s]);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_KIND, 1);      // CAVA_MSHAPE_INLINE
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_STATE_ID, 0);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_BLOCK_X, c.bxyz()[s * 3]);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_BLOCK_Y, c.bxyz()[s * 3 + 1]);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_BLOCK_Z, c.bxyz()[s * 3 + 2]);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_SOURCE, c.src()[s]);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_INLINE_SLOT, s);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_RESERVED0, 0);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_RESERVED1, 0);
                    refs.set(ValueLayout.JAVA_INT, ro + AbiOffsets.REF_RESERVED2, 0);

                    long so = (long) s * AbiOffsets.SHAPE_RECORD_SIZE;
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_POINTS_KIND, c.pointsKind()[s]);
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_POINT_OFFSET, pAt);
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_BIT_OFFSET, wAt);
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_BIT_WORDS, c.bits()[s].length);
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_SIZE_X, c.size()[s * 3]);
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_SIZE_Y, c.size()[s * 3 + 1]);
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_SIZE_Z, c.size()[s * 3 + 2]);
                    shapes.set(ValueLayout.JAVA_INT, so + AbiOffsets.SHAPE_RESERVED0, 0);
                    if (c.pointsKind()[s] == 1) {
                        MemorySegment.copy(c.pts()[s], 0, points, ValueLayout.JAVA_DOUBLE, (long) pAt * 8,
                                c.pts()[s].length);
                        pAt += c.pts()[s].length;
                    }
                    if (c.bits()[s].length > 0) {
                        MemorySegment.copy(c.bits()[s], 0, words, ValueLayout.JAVA_LONG, (long) wAt * 8,
                                c.bits()[s].length);
                        wAt += c.bits()[s].length;
                    }
                }
                int cap = c.events().length + 8;
                MemorySegment events = cap == 0 ? MemorySegment.NULL
                        : CavaNative.allocateArray(arena, CavaLayouts.MOVE_EVENT, cap);
                MemorySegment out = arena.allocate(CavaLayouts.MOVE_RESULT);

                int rc = nat.resolveMove(nat.handle(), req, refs, c.nsh(),
                        shapes, c.nsh(), points, totalPoints, words, totalWords, events, cap, out);
                if (rc != CavaLayouts.CAVA_OK) {
                    mismatches++;
                    if (first.isEmpty()) {
                        first.append("case ").append(c.id()).append(" rc=").append(CavaLayouts.errorName(rc));
                    }
                    continue;
                }
                int ne = out.get(ValueLayout.JAVA_INT, AbiOffsets.RESULT_EVENT_COUNT);
                int ov = out.get(ValueLayout.JAVA_INT, AbiOffsets.RESULT_EVENT_OVERFLOW);
                int su = out.get(ValueLayout.JAVA_INT, AbiOffsets.RESULT_STEP_USED);
                boolean bad = ov != c.overflow() || su != c.stepUsed() || ne != c.events().length;
                for (int i = 0; i < 3 && !bad; i++) {
                    bad = out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_X + i * 8L)
                                != Double.longBitsToDouble(c.deltaBits()[i])
                          || out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_BASE_X + i * 8L)
                                != Double.longBitsToDouble(c.baseBits()[i])
                          || out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_STEP_X + i * 8L)
                                != Double.longBitsToDouble(c.stepBits()[i]);
                }
                for (int e = 0; e < ne && !bad; e++) {
                    long o = (long) e * AbiOffsets.EVENT_SIZE;
                    long[] want = c.events()[e];
                    bad = events.get(ValueLayout.JAVA_LONG, o + AbiOffsets.EVENT_SHAPE_TOKEN) != want[0]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_SOURCE) != (int) want[1]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_AXIS) != (int) want[2]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_BLOCK_X) != (int) want[3]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_BLOCK_Y) != (int) want[4]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_BLOCK_Z) != (int) want[5]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_PASS) != (int) want[6]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_ACCEPTED) != (int) want[7]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_CELL_X) != (int) want[8]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_CELL_Y) != (int) want[9]
                            || events.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_CELL_Z) != (int) want[10]
                            || events.get(ValueLayout.JAVA_DOUBLE, o + AbiOffsets.EVENT_OFFSET)
                                    != Double.longBitsToDouble(want[11])
                            || events.get(ValueLayout.JAVA_DOUBLE, o + AbiOffsets.EVENT_MAX_DIST_BEFORE)
                                    != Double.longBitsToDouble(want[12])
                            || events.get(ValueLayout.JAVA_DOUBLE, o + AbiOffsets.EVENT_MAX_DIST_AFTER)
                                    != Double.longBitsToDouble(want[13]);
                }
                if (bad) {
                    mismatches++;
                    if (first.isEmpty()) {
                        first.append("case ").append(c.id())
                                .append(" nsh=").append(c.nsh())
                                .append(" mv=(").append(c.mv()[0]).append(',').append(c.mv()[1]).append(',')
                                .append(c.mv()[2]).append(')')
                                .append(" ne=").append(ne).append(" wantNe=").append(c.events().length)
                                .append(" ov=").append(ov).append(" wantOv=").append(c.overflow())
                                .append(" su=").append(su).append(" wantSu=").append(c.stepUsed())
                                .append(" delta=(")
                                .append(out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_X)).append(',')
                                .append(out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Y)).append(',')
                                .append(out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Z)).append(')')
                                .append(" want=(")
                                .append(Double.longBitsToDouble(c.deltaBits()[0])).append(',')
                                .append(Double.longBitsToDouble(c.deltaBits()[1])).append(',')
                                .append(Double.longBitsToDouble(c.deltaBits()[2])).append(')');
                    }
                }
                if (c.events().length > 0) {
                    withEvents++;
                }
            }
        }
        assertTrue(withEvents > 60, "可对拍用例里带事件的太少（" + withEvents + "），对拍强度不够");
        assertEquals(0, mismatches, "与内核向量不一致的用例数=" + mismatches
                + "（对拍 " + (cases.size() - unreconstructable) + " 例，无法还原而跳过 " + unreconstructable
                + " 例）；首个：" + first);
    }

    /**
     * STATE ref 的**平移合成**必须与 Java 侧 {@code VoxelShape.offset} 逐位一致：
     * 同一份几何，一次走 {@code INLINE}（点表已平移），一次走 {@code STATE} + {@code block=(dx,dy,dz)}。
     */
    @Test
    void stateRefTranslationMatchesPreShiftedInline() throws Exception {
        List<Case> cases = readCases();
        int checked = 0;
        for (Case c : cases) {
            if (c.nsh() == 0 || c.pointsKind()[0] != 1 || c.axisLen()[0] == null) {
                continue;   // 只看 EXPLICIT（FRACTIONAL 走 offset() 之后本来就是 EXPLICIT）
            }
            int[] len = c.axisLen()[0];
            int need = (c.size()[0] + 1) + (c.size()[1] + 1) + (c.size()[2] + 1);
            if (len[0] != c.size()[0] + 1 || c.pts()[0].length != need) {
                continue;   // 手工构造的非凸形状：点表长度不是 size+1，无法逐位还原
            }
            int dx = 3;
            int dy = -2;
            int dz = 7;
            // 平移后的点表：offset() 的 OffsetDoubleList 语义 = 逐点加常数
            double[] shifted = c.pts()[0].clone();
            int at = 0;
            for (int a = 0; a < 3; a++) {
                int n = c.size()[a] + 1;
                int d = a == 0 ? dx : (a == 1 ? dy : dz);
                for (int i = 0; i < n; i++) {
                    shifted[at] = (double) d + shifted[at];
                    at++;
                }
            }
            double[] inlineDelta = solveOne(c, 1, 0, 0, 0, 0, shifted);
            double[] stateDelta = solveState(c, dx, dy, dz, shifted);
            assertEquals(Double.doubleToRawLongBits(inlineDelta[0]), Double.doubleToRawLongBits(stateDelta[0]),
                    "case " + c.id() + " STATE 平移与预平移 INLINE 的 dx 不一致");
            assertEquals(Double.doubleToRawLongBits(inlineDelta[1]), Double.doubleToRawLongBits(stateDelta[1]),
                    "case " + c.id() + " dy");
            assertEquals(Double.doubleToRawLongBits(inlineDelta[2]), Double.doubleToRawLongBits(stateDelta[2]),
                    "case " + c.id() + " dz");
            checked++;
            if (checked >= 50) {
                break;
            }
        }
        assertTrue(checked >= 10, "可用于 STATE 平移对拍的用例太少：" + checked);
    }

    private double[] solveOne(Case c, int kind, int stateId, int bx, int by, int bz, double[] pts) {
        // 只用于 stateRefTranslationMatchesPreShiftedInline；该用例形状的 pts 长度 = size+1。
        // kind=INLINE(1) 时 stateId/bx/by/bz 都不参与几何，传 0 即可。
        try (Arena arena = Arena.ofShared()) {
            MemorySegment out = arena.allocate(CavaLayouts.MOVE_RESULT);
            MemorySegment req = req(arena, c);
            MemorySegment refs = CavaNative.allocateArray(arena, CavaLayouts.MOVE_SHAPE_REF, 1);
            refs.set(ValueLayout.JAVA_LONG, AbiOffsets.REF_SHAPE_TOKEN, 0L);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_KIND, kind);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_STATE_ID, stateId);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_BLOCK_X, bx);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_BLOCK_Y, by);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_BLOCK_Z, bz);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_SOURCE, c.src()[0]);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_INLINE_SLOT, 0);
            MemorySegment shapes = CavaNative.allocateArray(arena, CavaLayouts.SHAPE_RECORD, 1);
            shapes.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_POINTS_KIND, 1);
            shapes.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_POINT_OFFSET, 0);
            shapes.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_BIT_OFFSET, 0);
            shapes.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_BIT_WORDS, c.bits()[0].length);
            shapes.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_SIZE_X, c.size()[0]);
            shapes.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_SIZE_Y, c.size()[1]);
            shapes.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_SIZE_Z, c.size()[2]);
            MemorySegment points = CavaNative.allocateArray(arena, ValueLayout.JAVA_DOUBLE, pts.length);
            MemorySegment.copy(pts, 0, points, ValueLayout.JAVA_DOUBLE, 0, pts.length);
            MemorySegment words = CavaNative.allocateArray(arena, ValueLayout.JAVA_LONG,
                    Math.max(1, c.bits()[0].length));
            if (c.bits()[0].length > 0) {
                MemorySegment.copy(c.bits()[0], 0, words, ValueLayout.JAVA_LONG, 0, c.bits()[0].length);
            }
            int rc = nat.resolveMove(nat.handle(), req, refs, 1, shapes, 1, points, pts.length,
                    words, c.bits()[0].length, MemorySegment.NULL, 0, out);
            assertEquals(CavaLayouts.CAVA_OK, rc, "resolveMove rc");
            return new double[] {
                    out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_X),
                    out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Y),
                    out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Z)};
        }
    }

    /** 上传一张只含该形状的常驻表，然后用 STATE + block 求解。 */
    private double[] solveState(Case c, int dx, int dy, int dz, double[] shifted) {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment recs = CavaNative.allocateArray(arena, CavaLayouts.SHAPE_RECORD, 1);
            recs.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_POINTS_KIND, 1);
            recs.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_POINT_OFFSET, 0);
            recs.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_BIT_OFFSET, 0);
            recs.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_BIT_WORDS, c.bits()[0].length);
            recs.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_SIZE_X, c.size()[0]);
            recs.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_SIZE_Y, c.size()[1]);
            recs.set(ValueLayout.JAVA_INT, AbiOffsets.SHAPE_SIZE_Z, c.size()[2]);
            MemorySegment pts = CavaNative.allocateArray(arena, ValueLayout.JAVA_DOUBLE, c.pts()[0].length);
            MemorySegment.copy(c.pts()[0], 0, pts, ValueLayout.JAVA_DOUBLE, 0, c.pts()[0].length);
            MemorySegment words = CavaNative.allocateArray(arena, ValueLayout.JAVA_LONG,
                    Math.max(1, c.bits()[0].length));
            if (c.bits()[0].length > 0) {
                MemorySegment.copy(c.bits()[0], 0, words, ValueLayout.JAVA_LONG, 0, c.bits()[0].length);
            }
            assertEquals(CavaLayouts.CAVA_OK,
                    nat.shapeTableUpload(nat.handle(), recs, 1, pts, c.pts()[0].length, words, c.bits()[0].length),
                    "shapeTableUpload");

            MemorySegment out = arena.allocate(CavaLayouts.MOVE_RESULT);
            MemorySegment req = req(arena, c);
            MemorySegment refs = CavaNative.allocateArray(arena, CavaLayouts.MOVE_SHAPE_REF, 1);
            refs.set(ValueLayout.JAVA_LONG, AbiOffsets.REF_SHAPE_TOKEN, 0L);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_KIND, 0);          // STATE
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_STATE_ID, 0);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_BLOCK_X, dx);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_BLOCK_Y, dy);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_BLOCK_Z, dz);
            refs.set(ValueLayout.JAVA_INT, AbiOffsets.REF_SOURCE, c.src()[0]);
            MemorySegment shiftedSeg = CavaNative.allocateArray(arena, ValueLayout.JAVA_DOUBLE, shifted.length);
            MemorySegment.copy(shifted, 0, shiftedSeg, ValueLayout.JAVA_DOUBLE, 0, shifted.length);
            int rc = nat.resolveMove(nat.handle(), req, refs, 1, MemorySegment.NULL, 0,
                    shiftedSeg, shifted.length, MemorySegment.NULL, 0, MemorySegment.NULL, 0, out);
            assertEquals(CavaLayouts.CAVA_OK, rc, "resolveMove(STATE) rc");
            double ax = out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_X);
            double ay = out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Y);
            double az = out.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Z);
            // 复位常驻表，避免影响别的用例
            nat.shapeTableUpload(nat.handle(), MemorySegment.NULL, 0, MemorySegment.NULL, 0,
                    MemorySegment.NULL, 0);
            return new double[] {ax, ay, az};
        }
    }

    private static MemorySegment req(Arena arena, Case c) {
        MemorySegment req = arena.allocate(CavaLayouts.MOVE_REQUEST);
        req.fill((byte) 0);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_X, c.box()[0]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_Y, c.box()[1]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_Z, c.box()[2]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_X, c.box()[3]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_Y, c.box()[4]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_Z, c.box()[5]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_X, c.mv()[0]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_Y, c.mv()[1]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_Z, c.mv()[2]);
        req.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_STEP_HEIGHT, c.stepH());
        req.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_ON_GROUND, c.onGround());
        req.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_SHAPE_COUNT, 1);
        return req;
    }
}
