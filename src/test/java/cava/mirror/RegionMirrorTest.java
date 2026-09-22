package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaLayouts;
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
        int nextProfileRc;
        float lastProfileFirstFloat = Float.NaN;
        float lastProfileWidth = Float.NaN;
        long lastHandle = Long.MIN_VALUE;

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
            lastHandle = handle;
            lastProfileFirstFloat = profile.get(ValueLayout.JAVA_FLOAT, 0L);
            lastProfileWidth = profile.get(ValueLayout.JAVA_FLOAT, 148L);   // CavaMobProfile.width 的 offset
            return nextProfileRc;
        }
    }

    private static final class FakeGate implements StateTableGate {
        boolean ok = true;
        boolean ready = true;
        boolean consistent = true;
        final java.util.Set<Integer> guardedIds = new java.util.HashSet<>();

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

        @Override
        public boolean selfConsistent() {
            return consistent;
        }

        @Override
        public boolean isShapeGuarded(int stateId) {
            return guardedIds.contains(stateId);
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
        System.clearProperty(RegionMirror.PROP_SHAPE_GUARD);
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

    // ------------------------------------------------------------------
    // 新契约（captain 2026-09-22 返工）：档案由注入流填值，镜像只负责写进原生
    // ------------------------------------------------------------------

    @Test
    void profileUploadWritesWhatTheFillerWrote() {
        MobProfileSpec spec = new MobProfileSpec(
                PathTypes.defaultPenalties(), 0.0f,
                1.5, 64.0, -2.5, 1, 64, -3,
                0.6f, 1.8f, 0.0f, 3, -64, 63,
                NavCaps.CAN_OPEN_DOORS | NavCaps.CAN_SWIM, NavCaps.PENALTY_ALL_SET,
                MobProfileSpec.KIND_LAND, true);
        assertTrue(mirror.uploadProfileForSolve(12345L, spec::writeTo), "合法档案必须上传成功");
        assertEquals(1, uploader.profileUploads);
        assertEquals(-1.0f, uploader.lastProfileFirstFloat, 0.0f, "penalty[0] = BLOCKED = -1.0f");
        assertEquals(0.6f, uploader.lastProfileWidth, 0.0f, "width 必须原样写到布局的 width 偏移");
        assertEquals(12345L, uploader.lastHandle, "必须用调用方给的 handle，不是 uploader.handle()");
        assertTrue(mirror.profileStats().contains("profileUploads=1"), mirror.profileStats());
    }

    @Test
    void profileUploadRefusesNullThrowingAndIllegal() {
        assertFalse(mirror.uploadProfileForSolve(1L, null), "null 回调必须拒绝");
        assertFalse(mirror.uploadProfileForSolve(1L, seg -> {
            throw new IllegalStateException("filler 内部炸了");
        }), "回调抛异常必须转成 false（回退），不许逃逸");
        assertFalse(mirror.uploadProfileForSolve(1L, seg -> {
            // 什么都不填 = 全 0 = width/height 为 0（原生会 CAVA_ERR_ARG）
        }), "全 0 档案必须提前拒绝");
        assertEquals(0, uploader.profileUploads, "这三种情况都不该调到原生");
        assertTrue(mirror.profileStats().contains("profileRefusals=3"), mirror.profileStats());
    }

    @Test
    void profileUploadSurfacesNativeErrorCode() {
        uploader.nextProfileRc = CavaLayouts.CAVA_ERR_ARG;
        assertEquals(false, mirror.uploadProfileForSolve(1L, seg -> {
            seg.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 148L, 0.6f);  // width
            seg.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 152L, 1.8f);  // height
        }), "原生返回错误码时必须 false");
        assertEquals(1, uploader.profileUploads);
    }

    // ------------------------------------------------------------------
    // isFlagsReadyFor：19 个谓词位与 caps 无关（理由见 docs §2.4）
    // ------------------------------------------------------------------

    @Test
    void flagsReadyRequiresTableSelfCheckAndNative() {
        assertTrue(mirror.isFlagsReadyFor(NavCaps.CAN_OPEN_DOORS), "一切正常时必须**真的**返回 true");
        assertEquals(1, mirror.flagsReadyOk());

        gate.ok = false;
        assertFalse(mirror.isFlagsReadyFor(0), "表未就绪必须 false");
        assertTrue(mirror.flagsNotReadyReason().contains("状态表未就绪"), mirror.flagsNotReadyReason());
        gate.ok = true;

        gate.consistent = false;
        assertFalse(mirror.isFlagsReadyFor(0), "表自检失败必须 false");
        assertTrue(mirror.flagsNotReadyReason().contains("自检"), mirror.flagsNotReadyReason());
        gate.consistent = true;

        uploader.available = false;
        assertFalse(mirror.isFlagsReadyFor(0), "原生不可用必须 false");
        uploader.available = true;

        assertTrue(mirror.isFlagsReadyFor(0));
        assertNull(mirror.flagsNotReadyReason());
    }

    @Test
    void capsNeverChangesFlagsReadiness() {
        int[] capsSamples = {
                0,
                NavCaps.CAN_OPEN_DOORS,
                NavCaps.CAN_ENTER_OPEN_DOORS | NavCaps.CAN_OPEN_DOORS,
                NavCaps.CAN_SWIM | NavCaps.TOUCHING_WATER,
                NavCaps.AMPHIBIOUS | NavCaps.PENALIZE_DEEP_WATER,
                NavCaps.CAN_WALK_OVER_FENCES,
                NavCaps.CAN_FLOAT | NavCaps.CAN_PATHFIND_THROUGH,
                0x7FFFFFFF,          // 含未定义位：不阻塞，只 WARN 一次
                NavCaps.KNOWN_MASK,
        };
        for (int caps : capsSamples) {
            assertTrue(mirror.isFlagsReadyFor(caps), "caps=0x" + Integer.toHexString(caps) + " 不该影响就绪判定");
        }
        assertEquals(capsSamples.length, mirror.flagsReadyCalls());
    }

    // ------------------------------------------------------------------
    // bind：未绑定必须显式失败；null 必须拒绝
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 形状守卫（captain 裁决 1：绝不静默发散）
    // ------------------------------------------------------------------

    @Test
    void shapeGuardRejectsRegionWithGuardedState() {
        gate.guardedIds.add(encode(11, 60, -5));
        RegionSource.MirrorUnavailableException e = assertThrows(RegionSource.MirrorUnavailableException.class,
                () -> mirror.push(10, 60, -5, 3, 2, 4));
        assertTrue(e.getMessage().contains("位置/上下文相关形状"), e.getMessage());
        assertEquals(0, uploader.uploads.size(), "被守卫的区域绝不许上传");
        assertEquals(1, mirror.shapeGuardRejections());
        assertEquals(1, mirror.failures());
    }

    @Test
    void shapeGuardAllowsCleanRegion() {
        gate.guardedIds.add(encode(99, 99, 99));   // 区域里没有它
        RegionSource.Pushed pushed = mirror.push(10, 60, -5, 3, 2, 4);
        assertEquals(24, pushed.stateCount());
        assertEquals(0, mirror.shapeGuardRejections());
    }

    @Test
    void shapeGuardCanBeDisabledForAbComparison() {
        System.setProperty(RegionMirror.PROP_SHAPE_GUARD, "false");
        gate.guardedIds.add(encode(11, 60, -5));
        RegionSource.Pushed pushed = mirror.push(10, 60, -5, 3, 2, 4);
        assertEquals(24, pushed.stateCount(), "关掉守卫后应当照常推送（A/B 对比用）");
        assertEquals(1, uploader.uploads.size());
        assertTrue(mirror.report().contains("形状守卫: 关"), mirror.report());
    }

    @Test
    void pushWithoutBindFailsLoudly() {
        RegionMirror unbound = new RegionMirror(uploader, gate);
        RegionSource.MirrorUnavailableException e = assertThrows(RegionSource.MirrorUnavailableException.class,
                () -> unbound.push(0, 0, 0, 2, 2, 2));
        assertTrue(e.getMessage().contains("bind"), e.getMessage());
        assertThrows(RegionSource.MirrorUnavailableException.class, () -> unbound.bind(null));
        assertNull(unbound.boundWorld());
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
