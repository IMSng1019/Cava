package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 区域推送的性能实测（**真实原生库 + 假世界**）。
 *
 * <p>测的是"镜像侧自有成本"：填 id（假世界 = 纯数组写，所以这是**下界**）+ 分配/拷贝 + FFM 上传。
 * 真实世界里 fill 还要读 {@code ChunkSection}（那部分由 {@code McStateTableProbeTest} 单独实测）。
 *
 * <p>输出前缀 {@code [perf]}，从 {@code build/test-results/test/*.xml} 里读。
 */
class RegionMirrorPerfTest {

    private static CavaNative nat;
    private static boolean ready;

    @BeforeAll
    static void openNative() {
        nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "test");
        ready = nat.tryOpen();
        Assumptions.assumeTrue(ready, "需要真实原生库（-Dcava.native.path=...）：status=" + nat.status());
    }

    /** 合成状态表（64 条，含盒），走真实 cava_state_table_upload。 */
    private static final class SyntheticGate implements StateTableGate {
        private boolean uploaded;
        private String failure = "(未上传)";

        @Override
        public boolean uploadIfNeeded() {
            if (uploaded) {
                return true;
            }
            StateProbe probe = new StateProbe() {
                @Override
                public int stateCount() {
                    return 64;
                }

                @Override
                public void probe(int id, StateSample out) {
                    out.set(MirrorFlags.Pred.PF_PATH_THROUGH_LAND, (id & 1) == 0);
                    out.set(MirrorFlags.Pred.SF_SOLID, (id & 1) != 0);
                    if ((id & 3) == 1) {
                        out.addBox(0f, 0f, 0f, 1f, 1f, 1f);
                    }
                    out.commonType = (id & 1) == 0 ? PathTypes.OPEN : PathTypes.BLOCKED;
                }
            };
            StateTableBuilder.Result r = StateTableBuilder.build(probe);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment recs = CavaNative.allocateArray(arena, CavaLayouts.STATE_RECORD, r.data().stateCount);
                r.data().writeRecords(recs);
                MemorySegment boxes = MemorySegment.NULL;
                if (r.data().boxTotal > 0) {
                    boxes = CavaNative.allocateArray(arena, CavaLayouts.COLLISION_BOX, r.data().boxTotal);
                    r.data().writeBoxes(boxes);
                }
                int rc = nat.stateTableUpload(nat.handle(), recs, r.data().stateCount, boxes, r.data().boxTotal);
                uploaded = rc == CavaLayouts.CAVA_OK;
                failure = "rc=" + CavaLayouts.errorName(rc);
            }
            return uploaded;
        }

        @Override
        public boolean ready() {
            return uploaded;
        }

        @Override
        public int airStateId() {
            return 0;
        }

        @Override
        public String failure() {
            return failure;
        }
    }

    /** 假世界：id = 坐标编码（决定论、零 IO）。 */
    private static final class CountingReader implements RegionReader {
        long cells;

        @Override
        public String dimensionId() {
            return "perf:overworld";
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 319;
        }

        @Override
        public long currentTick() {
            return 1;
        }

        @Override
        public boolean isReady(int minX, int minY, int minZ, int dimX, int dimY, int dimZ) {
            return true;
        }

        @Override
        public void fill(int minX, int minY, int minZ, int dimX, int dimY, int dimZ, int[] out, int airStateId) {
            int i = 0;
            for (int y = 0; y < dimY; y++) {
                for (int z = 0; z < dimZ; z++) {
                    for (int x = 0; x < dimX; x++) {
                        out[i++] = (x ^ y ^ z) & 63;
                    }
                }
            }
            cells += i;
        }
    }

    @Test
    void pushCostVersusWindowSize() {
        CountingReader reader = new CountingReader();
        RegionMirror mirror = new RegionMirror(reader, new NativeRegionUploader(), new SyntheticGate());
        // 预热（JIT + 原生侧 vector 分配）：用最大窗口，保证所有分支都被编译过
        for (int i = 0; i < 300; i++) {
            mirror.push(new RegionRect(0, 60, 0, 64, 40, 64));
        }

        Map<String, double[]> table = new LinkedHashMap<>();
        int[][] windows = {
                {16, 16, 16}, {24, 16, 24}, {32, 24, 32}, {48, 24, 48}, {64, 40, 64}, {96, 48, 96}
        };
        int reps = 200;
        int rounds = 4;
        System.out.println("[perf] === 区域推送耗时 vs 窗口尺寸（假世界 fill + 真实 FFM 上传，reps=" + reps
                + " x rounds=" + rounds + "，取**最快一轮**以避开 JIT/GC 噪声）===");
        System.out.println("[perf] 窗口(dimX x dimY x dimZ)  方块数   总us/次   fill_us   分配拷贝_us   FFM上传_us   ns/方块");
        for (int[] w : windows) {
            RegionRect rect = new RegionRect(0, 64 - w[1] / 2, 0, w[0], w[1], w[2]);
            long vol = rect.volume();
            double[] bestRow = null;
            for (int round = 0; round < rounds; round++) {
                long fill = 0;
                long alloc = 0;
                long upload = 0;
                long total = 0;
                for (int i = 0; i < reps; i++) {
                    mirror.push(rect);
                    RegionMirror.PushDetail d = mirror.lastDetail();
                    fill += d.fillNanos();
                    alloc += d.allocNanos();
                    upload += d.uploadNanos();
                    total += d.totalNanos();
                }
                double[] row = {
                        vol,
                        total / 1000.0 / reps,
                        fill / 1000.0 / reps,
                        alloc / 1000.0 / reps,
                        upload / 1000.0 / reps,
                        (double) total / reps / vol
                };
                if (bestRow == null || row[1] < bestRow[1]) {
                    bestRow = row;
                }
            }
            table.put(w[0] + "x" + w[1] + "x" + w[2], bestRow);
            System.out.printf("[perf] %-24s %8d %9.2f %9.2f %12.2f %11.2f %10.3f%n",
                    w[0] + "x" + w[1] + "x" + w[2], (long) bestRow[0], bestRow[1], bestRow[2], bestRow[3],
                    bestRow[4], bestRow[5]);
        }
        System.out.println("[perf] 累计推送 " + mirror.pushes() + " 次 / 失败 " + mirror.failures());
        System.out.println("[perf] " + mirror.report().replace('\n', ' '));
        // 断言：最小窗口的推送成本必须远小于一次寻路的量级（原版寻路通常在几十~几百 us）
        assertTrue(table.get("16x16x16")[1] < 1000.0, "16^3 窗口推送应当 < 1 ms");
        assertTrue(table.get("64x40x64")[5] < 100.0, "每方块成本应当 < 100 ns");
    }
}
