package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 区域推送测试（**脱离服务器**：假 Reader + 假 Uploader + 假状态表闸门）。
 *
 * <p>重点验三件事：
 * <ol>
 *   <li><b>索引顺序</b>：{@code ((y*dimZ)+z)*dimX+x}（x 最快、y 最慢）—— 用"坐标编码成 id"的假世界反解验证；</li>
 *   <li><b>失败必须显式</b>：区块未加载 / 表未就绪 / 原生不可用 / 区域过大 / 原生错误码 → 一律抛
 *       {@link RegionSource.MirrorUnavailableException}（调用方据此回退）；</li>
 *   <li><b>失效口子</b>：同 tick 复用只在同矩形/同维度/同 tick 时命中，任何 onXxx 通知都会让它失效。</li>
 * </ol>
 */
class RegionMirrorTest {

    /** 把世界坐标编码进 id，便于反解索引顺序。 */
    private static int encode(int x, int y, int z) {
        return ((x & 0xFF) << 16) | ((y & 0xFF) << 8) | (z & 0xFF);
    }

    private static final class FakeReader implements RegionReader {
        boolean ready = true;
        long tick = 100;
        int fillCalls;

        @Override
        public String dimensionId() {
            return "test:overworld";
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
            return tick;
        }

        @Override
        public boolean isReady(int minX, int minY, int minZ, int dimX, int dimY, int dimZ) {
            return ready;
        }

        @Override
        public void fill(int minX, int minY, int minZ, int dimX, int dimY, int dimZ, int[] out, int airStateId) {
            fillCalls++;
            if (!ready) {
                throw new RegionSource.MirrorUnavailableException("区块未加载");
            }
            for (int y = 0; y < dimY; y++) {
                for (int z = 0; z < dimZ; z++) {
                    for (int x = 0; x < dimX; x++) {
                        out[((y * dimZ) + z) * dimX + x] = encode(minX + x, minY + y, minZ + z);
                    }
                }
            }
        }
    }

    private static final class FakeUploader implements RegionUploader {
        boolean available = true;
        int nextRc;
        final List<int[]> uploads = new ArrayList<>();
        final List<int[]> shapes = new ArrayList<>();
        int clears;
        int profileUploads;
        float lastProfileFirstFloat = Float.NaN;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public long handle() {
            return 0x1_0000_0001L;
        }

        @Override
        public int upload(long handle, int dimX, int dimY, int dimZ, int originX, int originY, int originZ,
                          MemorySegment ids, int count) {
            int[] copy = new int[count];
            MemorySegment.copy(ids, ValueLayout.JAVA_INT, 0L, copy, 0, count);
            uploads.add(copy);
            shapes.add(new int[] {dimX, dimY, dimZ, originX, originY, originZ});
            return nextRc;
        }

        @Override
        public int clear(long handle) {
            clears++;
            return 0;
        }

        @Override
        public int mobProfileUpload(long handle, MemorySegment profile) {
            profileUploads++;
            lastProfileFirstFloat = profile.get(ValueLayout.JAVA_FLOAT, 0L);
            return 0;
        }
    }

    private static final class FakeGate implements StateTableGate {
        boolean ok = true;
        boolean ready = true;

        @Override
        public boolean uploadIfNeeded() {
            return ok;
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public int airStateId() {
            return 0;
        }

        @Override
        public String failure() {
            return "(fake)";
        }
    }

    private FakeReader reader;
    private FakeUploader uploader;
    private FakeGate gate;
    private RegionMirror mirror;

    @BeforeEach
    void setUp() {
        reader = new FakeReader();
        uploader = new FakeUploader();
        gate = new FakeGate();
        mirror = new RegionMirror(reader, uploader, gate);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RegionMirror.PROP_MAX_VOLUME);
        MobProfiles.clear();
    }

    @Test
    void indexOrderIsXFastestYSlowest() {
        RegionSource.Pushed pushed = mirror.push(10, 60, -5, 3, 2, 4);
        assertEquals(1, uploader.uploads.size());
        int[] ids = uploader.uploads.get(0);
        assertEquals(24, ids.length);
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 3; x++) {
                    int index = ((y * 4) + z) * 3 + x;
                    assertEquals(encode(10 + x, 60 + y, -5 + z), ids[index],
                            "索引 (" + x + "," + y + "," + z + ") 顺序错了");
                }
            }
        }
        assertEquals(24, pushed.stateCount());
        assertTrue(pushed.elapsedNanos() >= 0);
        assertNotNull(mirror.lastDetail());
        assertEquals(1, mirror.pushes());
        assertEquals(0, mirror.failures());
    }

    @Test
    void unloadedChunkFailsLoudly() {
        reader.ready = false;
        RegionSource.MirrorUnavailableException e = assertThrows(RegionSource.MirrorUnavailableException.class,
                () -> mirror.push(0, 0, 0, 4, 4, 4));
        assertTrue(e.getMessage().contains("区块未加载"), e.getMessage());
        assertEquals(1, mirror.failures());
        assertEquals(0, uploader.uploads.size());
    }

    @Test
    void tableNotReadyFailsLoudly() {
        gate.ok = false;
        assertThrows(RegionSource.MirrorUnavailableException.class, () -> mirror.push(0, 0, 0, 4, 4, 4));
        assertEquals(0, reader.fillCalls);
    }

    @Test
    void nativeUnavailableFailsLoudly() {
        uploader.available = false;
        assertThrows(RegionSource.MirrorUnavailableException.class, () -> mirror.push(0, 0, 0, 4, 4, 4));
    }

    @Test
    void tooLargeRegionFailsLoudly() {
        System.setProperty(RegionMirror.PROP_MAX_VOLUME, "8");
        RegionSource.MirrorUnavailableException e = assertThrows(RegionSource.MirrorUnavailableException.class,
                () -> mirror.push(0, 0, 0, 4, 4, 4));
        assertTrue(e.getMessage().contains("区域过大"), e.getMessage());
        assertEquals(0, reader.fillCalls);
    }

    @Test
    void nativeErrorCodeFailsLoudly() {
        uploader.nextRc = -4; // CAVA_ERR_ARG
        RegionSource.MirrorUnavailableException e = assertThrows(RegionSource.MirrorUnavailableException.class,
                () -> mirror.push(0, 0, 0, 2, 2, 2));
        assertTrue(e.getMessage().contains("CAVA_ERR_ARG"), e.getMessage());
        assertEquals(1, mirror.failures());
    }

    @Test
    void sameTickReuseOnlyForIdenticalRegion() {
        RegionRect rect = new RegionRect(0, 60, 0, 8, 8, 8);
        mirror.pushReusingSameTick(rect);
        assertEquals(1, uploader.uploads.size());
        mirror.pushReusingSameTick(rect);
        assertEquals(1, uploader.uploads.size(), "同 tick 同矩形应当复用");
        assertEquals(1, mirror.reuseSkips());
        // 换了 tick -> 重推
        reader.tick++;
        mirror.pushReusingSameTick(rect);
        assertEquals(2, uploader.uploads.size(), "换 tick 必须重推");
        // 通知失效 -> 同 tick 也要重推
        mirror.onBlockChanged(1, 2, 3);
        mirror.pushReusingSameTick(rect);
        assertEquals(3, uploader.uploads.size(), "失效通知后必须重推");
        assertEquals(1, mirror.invalidationCount());
        assertTrue(mirror.lastInvalidation().contains("方块变化"));
    }

    @Test
    void clearIsForwarded() {
        mirror.push(0, 0, 0, 2, 2, 2);
        mirror.clear();
        assertEquals(1, uploader.clears);
    }

    @Test
    void profileUploadUsesRegistryAndReportsMissingKey() {
        assertFalse(mirror.isProfileReadyForSolve(12345L), "未注册的 key 必须 not ready");
        assertFalse(mirror.uploadProfileForSolve(1L, 12345L), "未注册的 key 必须失败（调用方回退）");
        assertEquals(0, uploader.profileUploads);

        MobProfileSpec spec = new MobProfileSpec(
                PathTypes.defaultPenalties(), 0.0f,
                1.5, 64.0, -2.5, 1, 64, -3,
                0.6f, 1.8f, 0.0f, 3, -64, 63,
                NavCaps.CAN_OPEN_DOORS | NavCaps.CAN_SWIM, NavCaps.PENALTY_ALL_SET,
                MobProfileSpec.KIND_LAND, true);
        long key = MobProfiles.define(spec);
        assertTrue(mirror.isProfileReadyForSolve(key));
        assertTrue(mirror.uploadProfileForSolve(1L, key));
        assertEquals(1, uploader.profileUploads);
        assertEquals(-1.0f, uploader.lastProfileFirstFloat, 0.0f, "penalty[0] = BLOCKED = -1.0f");

        // 飞行/水生档案 -> not ready（内核未实现）
        MobProfileSpec unsupported = new MobProfileSpec(
                PathTypes.defaultPenalties(), 0.0f,
                0.0, 64.0, 0.0, 0, 64, 0,
                0.6f, 1.8f, 0.0f, 3, -64, 63, 0, NavCaps.PENALTY_ALL_SET,
                MobProfileSpec.KIND_UNSUPPORTED, true);
        assertFalse(mirror.isProfileReadyForSolve(MobProfiles.define(unsupported)));
    }

    @Test
    void pushForSolveUsesDocumentedWindow() {
        RegionSource.Pushed p = mirror.pushForSolve(0, 64, 0, 10, 66, -4, 0.6f, 1.8f, 3);
        int[] shape = uploader.shapes.get(0);
        assertEquals(19, shape[0]);  // x: -4..14
        assertEquals(14, shape[1]);  // y: 57..70（向下 7 = safeFallDistance 3 + 4；向上 4）
        assertEquals(13, shape[2]);  // z: -8..4
        assertEquals(-4, shape[3]);
        assertEquals(57, shape[4]);
        assertEquals(-8, shape[5]);
        assertEquals(19 * 14 * 13, p.stateCount());
    }
}
