package cava.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * P1 ABI 边界语义测试（cava_pathfind / mob_profile / state_table / region）。
 *
 * <p>需要真实原生库：用 {@code -Dcava.native.path=<cava.dll>} 指过去，或者让 jar 里带
 * {@code natives/<平台>/cava.dll}。**没有原生库时整类 skip**（不静默通过：
 * JUnit 会报告 skipped，而纯 Java 回退路径由 {@link NativeFallbackTest} 覆盖）。
 *
 * <p>期望值来自 {@code native/include/cava_abi.h} 的明文约定，不是从实现反推的：
 * <ul>
 *   <li>profile 任一字段非法（width&lt;=0 / height&lt;=0 / NaN / 未上传）→ {@code CAVA_ERR_ARG}；</li>
 *   <li>非法上传**不改变已有档案**；</li>
 *   <li>句柄非法 → 安全错误码，**绝不段错误**；</li>
 *   <li>区域外的 {@code state_id} 查询 → {@code CAVA_OK} + out = -1。</li>
 * </ul>
 */
class PathfindAbiTest {

    private static CavaNative nat;
    private static boolean ready;

    @BeforeAll
    static void openNative() {
        nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "test");
        ready = nat.tryOpen();
        Assumptions.assumeTrue(ready,
                "需要原生库：-Dcava.native.path=<cava.dll> 或 jar 内 natives/<平台>/cava.dll（当前 status="
                        + nat.status() + "：" + nat.detail() + "）");
    }

    private static long off(String field) {
        return CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    /** 造一个最小档案：全 0 + 指定 width/height（其余字段为 0 不影响参数校验）。 */
    private static MemorySegment profile(Arena arena, float width, float height) {
        MemorySegment p = arena.allocate(CavaLayouts.MOB_PROFILE);
        p.fill((byte) 0);
        p.set(ValueLayout.JAVA_FLOAT, off("width"), width);
        p.set(ValueLayout.JAVA_FLOAT, off("height"), height);
        return p;
    }

    @Test
    void invalidProfileIsRejected() {
        try (Arena arena = Arena.ofShared()) {
            assertEquals(CavaLayouts.CAVA_ERR_ARG, nat.mobProfileUpload(nat.handle(), profile(arena, 0.0f, 1.0f)),
                    "width == 0 必须 CAVA_ERR_ARG");
            assertEquals(CavaLayouts.CAVA_ERR_ARG, nat.mobProfileUpload(nat.handle(), profile(arena, -1.0f, 1.0f)),
                    "width < 0 必须 CAVA_ERR_ARG");
            assertEquals(CavaLayouts.CAVA_ERR_ARG, nat.mobProfileUpload(nat.handle(), profile(arena, Float.NaN, 1.0f)),
                    "width = NaN 必须 CAVA_ERR_ARG");
            assertEquals(CavaLayouts.CAVA_ERR_ARG, nat.mobProfileUpload(nat.handle(), profile(arena, 1.0f, 0.0f)),
                    "height == 0 必须 CAVA_ERR_ARG");
            assertEquals(CavaLayouts.CAVA_ERR_ARG, nat.mobProfileUpload(nat.handle(), profile(arena, 1.0f, Float.NaN)),
                    "height = NaN 必须 CAVA_ERR_ARG");
        }
    }

    @Test
    void pathfindWithoutProfileIsRejected() {
        try (Arena arena = Arena.ofShared()) {
            assertEquals(CavaLayouts.CAVA_OK, nat.mobProfileClear(nat.handle()), "clear 必须幂等且成功");
            MemorySegment req = arena.allocate(CavaLayouts.PATH_REQUEST);
            req.fill((byte) 0);
            MemorySegment out = CavaNative.allocateArray(arena, CavaLayouts.PATH_NODE, 64);
            int rc = nat.pathfind(nat.handle(), req, out, 64);
            assertTrue(rc < 0, "没有档案时 cava_pathfind 必须返回错误码，实际 " + CavaLayouts.errorName(rc));
            assertTrue(rc == CavaLayouts.CAVA_ERR_ARG || rc == CavaLayouts.CAVA_ERR_UNIMPLEMENTED,
                    "期望 CAVA_ERR_ARG 或 CAVA_ERR_UNIMPLEMENTED，实际 " + CavaLayouts.errorName(rc));
        }
    }

    @Test
    void stateTableAndRegionRoundTrip() {
        try (Arena arena = Arena.ofShared()) {
            // 1 条状态记录（id = 0）：无碰撞盒、默认类型 0、malus 0
            MemorySegment rec = CavaNative.allocateArray(arena, CavaLayouts.STATE_RECORD, 1);
            rec.fill((byte) 0);
            rec.set(ValueLayout.JAVA_INT, 4, -1); // box_offset = CAVA_BOX_NONE (0xFFFFFFFF)
            rec.set(ValueLayout.JAVA_INT, 8, 0);  // box_count
            assertEquals(CavaLayouts.CAVA_OK, nat.stateTableUpload(nat.handle(), rec, 1, MemorySegment.NULL, 0),
                    "1 条状态记录 + 0 碰撞盒应当接受");

            // 推一个 1x1x1 区域，内容 = state id 7
            MemorySegment ids = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, 1);
            ids.set(ValueLayout.JAVA_INT, 0, 7);
            assertEquals(CavaLayouts.CAVA_OK,
                    nat.regionUpload(nat.handle(), 1, 1, 1, 0, 0, 0, ids, 1), "1x1x1 区域应当接受");

            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            assertEquals(CavaLayouts.CAVA_OK, nat.regionStateIdAt(nat.handle(), 0, 0, 0, out), "区域内的查询");
            assertEquals(7, out.get(ValueLayout.JAVA_INT, 0), "推什么 id 就该查回什么 id");

            // 清掉区域后：区域外 → CAVA_OK + out = -1（头文件明文约定）
            assertEquals(CavaLayouts.CAVA_OK, nat.regionClear(nat.handle()));
            assertEquals(CavaLayouts.CAVA_OK, nat.regionStateIdAt(nat.handle(), 0, 0, 0, out));
            assertEquals(-1, out.get(ValueLayout.JAVA_INT, 0), "区域外 state_id 必须是 -1");
        }
    }

    @Test
    void regionUploadRejectsBadDims() {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment ids = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, 1);
            assertEquals(CavaLayouts.CAVA_ERR_ARG, nat.regionUpload(nat.handle(), 0, 1, 1, 0, 0, 0, ids, 1),
                    "dim_x = 0 必须 CAVA_ERR_ARG");
            assertEquals(CavaLayouts.CAVA_ERR_ARG, nat.regionUpload(nat.handle(), 1, 1, 1, 0, 0, 0, ids, 0),
                    "id_count 与维度不符必须 CAVA_ERR_ARG");
        }
    }

    @Test
    void forgedHandleNeverCrashes() {
        try (Arena arena = Arena.ofShared()) {
            long forged = 0xDEAD_BEEFL;
            MemorySegment req = arena.allocate(CavaLayouts.PATH_REQUEST);
            MemorySegment out = CavaNative.allocateArray(arena, CavaLayouts.PATH_NODE, 4);
            MemorySegment prof = profile(arena, 1.0f, 1.0f);
            MemorySegment rec = CavaNative.allocateArray(arena, CavaLayouts.STATE_RECORD, 1);
            MemorySegment ids = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, 1);
            MemorySegment oneInt = arena.allocate(ValueLayout.JAVA_INT);

            assertTrue(nat.pathfind(forged, req, out, 4) < 0, "伪造句柄 pathfind 必须 <0");
            assertTrue(nat.mobProfileUpload(forged, prof) < 0, "伪造句柄 upload 必须 <0");
            assertTrue(nat.mobProfileClear(forged) < 0, "伪造句柄 profile_clear 必须 <0");
            assertTrue(nat.stateTableUpload(forged, rec, 1, MemorySegment.NULL, 0) < 0, "伪造句柄 state_table 必须 <0");
            assertTrue(nat.regionUpload(forged, 1, 1, 1, 0, 0, 0, ids, 1) < 0, "伪造句柄 region_upload 必须 <0");
            assertTrue(nat.regionClear(forged) < 0, "伪造句柄 region_clear 必须 <0");
            assertTrue(nat.regionStateIdAt(forged, 0, 0, 0, oneInt) < 0, "伪造句柄 region_query 必须 <0");
            // 句柄 0 也算非法
            assertTrue(nat.pathfind(0L, req, out, 4) < 0, "句柄 0 必须 <0");
            // 走到这里说明 JVM 没被段错误带走
        }
    }

    @Test
    void openStateIsConsistent() {
        assertEquals(NativeStatus.OPEN, nat.status());
        assertTrue(nat.available());
        assertEquals(nat.handle(), nat.handle(), "句柄稳定");
        assertEquals(CavaLayouts.ABI_VERSION, nat.abiVersion(), "原生 ABI 版本");
        assertEquals(nat.javaLayoutSum(), nat.nativeLayoutSum(), "java/native layout_hash_sum");
        assertEquals(0x1C12265EL, nat.javaLayoutSum(), "9 个结构体的 layout_hash_sum");
    }
}
