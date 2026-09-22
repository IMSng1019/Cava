package cava.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaNative;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.util.math.ChunkSectionPos;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 跨语言逐位对拍：{@code native/tests/push/vectors/*.bin} → **真实 cava.dll**。
 *
 * <p>三条独立链必须给出同一个答案：
 * <ol>
 *   <li>C++ 内核算的（向量的 expected 字段就是它写的）；</li>
 *   <li>Java 参照（pushAwayFrom）/ 原版 API 转写（区段计划）；</li>
 *   <li>**真实 DLL** 通过 FFM 再算一遍。</li>
 * </ol>
 * 向量文件的 fnv1a64 写死在这里 —— 读错文件会立刻红（P2 第 1 核的教训）。
 */
class PushVectorParityTest {

    private static final long FNV_PUSH = 0x04a5df6731488658L;
    private static final long FNV_PLAN = 0x7b645a195c8d5f38L;

    private static Path vectorsDir() {
        Path p = Path.of("native", "tests", "push", "vectors");
        Assumptions.assumeTrue(Files.isDirectory(p), "向量目录不存在：" + p.toAbsolutePath());
        return p;
    }

    private static long fnv1a64(byte[] b) {
        long h = 0xCBF29CE484222325L;
        for (byte x : b) {
            h ^= (x & 0xFFL);
            h *= 0x100000001B3L;
        }
        return h;
    }

    private static void assumeNative() {
        CavaNative.get().tryOpen();
        Assumptions.assumeTrue(NativePush.available(), "原生 push 入口不可用：" + NativePush.detail());
    }

    @Test
    void pushVectorsMatchRealDllBitForBit() throws Exception {
        Path f = vectorsDir().resolve("push-00.bin");
        Assumptions.assumeTrue(Files.isRegularFile(f), "缺 " + f);
        byte[] bytes = Files.readAllBytes(f);
        assertEquals(FNV_PUSH, fnv1a64(bytes), "push-00.bin 的 fnv1a64 与 manifest 不一致（读错文件了？）");
        assumeNative();

        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        assertEquals("CVPU", new String(magic, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, bb.getInt(), "版本");
        int n = bb.getInt();
        assertTrue(n > 0);
        int compared = 0;
        int mismatch = 0;
        int shortCircuit = 0;
        for (int i = 0; i < n; i++) {
            double ax = bb.getDouble();
            double az = bb.getDouble();
            double bx = bb.getDouble();
            double bz = bb.getDouble();
            double edx = bb.getDouble();
            double edz = bb.getDouble();
            int ehit = bb.getInt();
            int hit = NativePush.pushAwayFrom(ax, az, bx, bz);
            assertTrue(hit >= 0, "原生返回错误码 " + hit + " @case " + i);
            int nhit = hit == 1 ? 1 : 0;
            double ndx = hit == 1 ? NativePush.lastDx() : 0.0;
            double ndz = hit == 1 ? NativePush.lastDz() : 0.0;
            // 交叉验证：Java 参照算出的必须与向量文件里 C++ 写的逐位相同
            double[] ref = new double[3];
            PushMath.compute(ax, az, bx, bz, ref);
            assertEquals(ehit, (int) ref[2], "Java 参照 vs 向量 hit @case " + i);
            if (ehit == 1) {
                assertEquals(Double.doubleToRawLongBits(edx), Double.doubleToRawLongBits(ref[0]),
                        "Java 参照 vs 向量 dx @case " + i);
                assertEquals(Double.doubleToRawLongBits(edz), Double.doubleToRawLongBits(ref[1]),
                        "Java 参照 vs 向量 dz @case " + i);
            }
            // 主断言：真实 DLL vs 向量
            if (nhit != ehit
                    || Double.doubleToRawLongBits(ndx) != Double.doubleToRawLongBits(edx)
                    || Double.doubleToRawLongBits(ndz) != Double.doubleToRawLongBits(edz)) {
                mismatch++;
            }
            if (ehit == 0) {
                shortCircuit++;
            }
            compared++;
        }
        System.out.printf("[cava/push] push 向量对拍：compared=%d shortCircuit=%d mismatch=%d%n",
                compared, shortCircuit, mismatch);
        assertEquals(0, mismatch, "真实 DLL 与向量文件逐位不一致");
        assertEquals(2048, compared);
    }

    @Test
    void sectionPlanMatchesRealDllAndVanillaApi() throws Exception {
        Path f = vectorsDir().resolve("plan-00.bin");
        Assumptions.assumeTrue(Files.isRegularFile(f), "缺 " + f);
        byte[] bytes = Files.readAllBytes(f);
        assertEquals(FNV_PLAN, fnv1a64(bytes), "plan-00.bin 的 fnv1a64 与 manifest 不一致");
        assumeNative();

        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bb.get(magic);
        assertEquals("CVPL", new String(magic, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, bb.getInt());
        int cases = bb.getInt();

        int compared = 0;
        int mismatch = 0;
        int vanillaMismatch = 0;
        int totalVisited = 0;
        try (Arena arena = Arena.ofShared()) {
            for (int c = 0; c < cases; c++) {
                double[] box = new double[6];
                for (int i = 0; i < 6; i++) {
                    box[i] = bb.getDouble();
                }
                int cnt = bb.getInt();
                long[] pos = new long[cnt];
                for (int i = 0; i < cnt; i++) {
                    pos[i] = bb.getLong();
                }
                int expN = bb.getInt();
                long[] exp = new long[Math.max(0, expN)];
                for (int i = 0; i < expN; i++) {
                    exp[i] = bb.getLong();
                }

                MemorySegment boxSeg = arena.allocate(48);
                for (int i = 0; i < 6; i++) {
                    boxSeg.set(ValueLayout.JAVA_DOUBLE, i * 8L, box[i]);
                }
                MemorySegment posSeg = arena.allocateArray(ValueLayout.JAVA_LONG, Math.max(1, cnt));
                for (int i = 0; i < cnt; i++) {
                    posSeg.setAtIndex(ValueLayout.JAVA_LONG, i, pos[i]);
                }
                int outCap = Math.max(8, cnt + 8);
                MemorySegment outSeg = arena.allocateArray(ValueLayout.JAVA_LONG, outCap);
                MemorySegment outCount = arena.allocate(4);
                int got = NativePush.sectionPlan(boxSeg, posSeg, cnt, outSeg, outCap, outCount);
                assertTrue(got >= 0, "原生返回错误码 " + got + " @case " + c);
                if (got != expN) {
                    mismatch++;
                    continue;
                }
                for (int i = 0; i < got; i++) {
                    if (outSeg.getAtIndex(ValueLayout.JAVA_LONG, i) != exp[i]) {
                        mismatch++;
                        break;
                    }
                }
                // 独立链：用**原版自己的 ChunkSectionPos + LongAVLTreeSet** 把 forEachInBox 转写一遍
                LongAVLTreeSet set = new LongAVLTreeSet();
                for (long p : pos) {
                    set.add(p);
                }
                long[] vp = vanillaPlan(set, box, cnt + 8);
                totalVisited += vp.length;
                if (vp.length != expN) {
                    vanillaMismatch++;
                } else {
                    for (int i = 0; i < expN; i++) {
                        if (vp[i] != exp[i]) {
                            vanillaMismatch++;
                            break;
                        }
                    }
                }
                compared++;
            }
        }
        System.out.printf("[cava/push] plan 向量对拍：cases=%d mismatch=%d vanillaApiMismatch=%d visited=%d%n",
                compared, mismatch, vanillaMismatch, totalVisited);
        assertEquals(0, mismatch, "真实 DLL 与向量文件不一致");
        assertEquals(0, vanillaMismatch, "原版 API 转写与向量文件不一致（打包/窗口常数读反了？）");
        assertEquals(256, compared);
    }

    /** 字节码 33-146 的逐条转写；只用原版 API（ChunkSectionPos + LongAVLTreeSet.subSet）。 */
    private static long[] vanillaPlan(LongAVLTreeSet set, double[] box, int cap) {
        int xMin = ChunkSectionPos.getSectionCoord(box[0] - 2.0);
        int yMin = ChunkSectionPos.getSectionCoord(box[1] - 4.0);
        int zMin = ChunkSectionPos.getSectionCoord(box[2] - 2.0);
        int xMax = ChunkSectionPos.getSectionCoord(box[3] + 2.0);
        int yMax = ChunkSectionPos.getSectionCoord(box[4] + 0.0);
        int zMax = ChunkSectionPos.getSectionCoord(box[5] + 2.0);
        long[] out = new long[cap];
        int k = 0;
        for (int x = xMin; x <= xMax; x++) {
            long minKey = ChunkSectionPos.asLong(x, 0, 0);
            long maxKey = ChunkSectionPos.asLong(x, -1, -1);
            for (long pos : set.subSet(minKey, maxKey + 1)) {
                int y = ChunkSectionPos.unpackY(pos);
                int z = ChunkSectionPos.unpackZ(pos);
                if (y < yMin || y > yMax || z < zMin || z > zMax) {
                    continue;
                }
                out[k++] = pos;
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }

    @Test
    void packSectionMatchesVanilla() {
        // 打包常量必须与原版 ChunkSectionPos.asLong 逐位相同（三轴、含负数与边界）
        int[][] cases = {{0, 0, 0}, {1, 2, 3}, {-1, -1, -1}, {-2, 5, -7}, {2097151, 524287, 2097151}, {-2097152, -524288, -2097152}};
        for (int[] c : cases) {
            // 原版打包公式（javap: x:22b<<42 | z:22b<<20 | y:20b）
            long vanilla = (((long) c[0] & 0x3FFFFFL) << 42) | (((long) c[2] & 0x3FFFFFL) << 20) | ((long) c[1] & 0xFFFFFL);
            assertEquals(vanilla, ChunkSectionPos.asLong(c[0], c[1], c[2]));
        }
    }
}
