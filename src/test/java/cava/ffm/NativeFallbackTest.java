package cava.ffm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 纯 Java 回退路径：原生不可用时，P1 入口必须一律返回 {@link CavaNative#ERR_NATIVE_UNAVAILABLE}，
 * **调用方不需要自己判断状态**。
 *
 * <p>与 {@link PathfindAbiTest} 互补：那一类在有原生库时跑，这一类只在没有原生库的 JVM 里跑
 * （有原生库时 skip）。两者合起来保证「开/关两条路都被真实执行过」。
 */
class NativeFallbackTest {

    private static CavaNative nat;

    @BeforeAll
    static void setUp() {
        nat = CavaNative.get();
        nat.configure(Path.of(System.getProperty("user.dir", ".")), "test");
        nat.tryOpen();
        Assumptions.assumeFalse(nat.available(), "本 JVM 里原生可用（" + nat.status() + "），回退断言不适用");
    }

    @Test
    void wrappersReturnFallbackSignal() {
        assertFalse(nat.available());
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE, nat.pathfind(1L, MemorySegment.NULL, MemorySegment.NULL, 0));
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE, nat.mobProfileUpload(1L, MemorySegment.NULL));
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE, nat.mobProfileClear(1L));
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE,
                nat.stateTableUpload(1L, MemorySegment.NULL, 0, MemorySegment.NULL, 0));
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE,
                nat.regionUpload(1L, 1, 1, 1, 0, 0, 0, MemorySegment.NULL, 0));
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE, nat.regionClear(1L));
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE, nat.regionStateIdAt(1L, 0, 0, 0, MemorySegment.NULL));
        assertEquals(CavaNative.ERR_NATIVE_UNAVAILABLE, nat.bindingsIfOpen() == null ? CavaNative.ERR_NATIVE_UNAVAILABLE : 0);
    }

    @Test
    void numericFallsBackToJava() {
        // 原生不可用时 Numeric 必须走 Java 语义，且结果与 JLS 5.1.3 一致
        assertEquals(2147483647, Numeric.d2iSat(1e300));
        assertEquals(Integer.MIN_VALUE, Numeric.d2iSat(-1e300));
        assertEquals(0, Numeric.d2iSat(Double.NaN));
        assertEquals(Long.MAX_VALUE, Numeric.d2lSat(1e300));
        assertEquals(Double.doubleToRawLongBits(-0.0), Numeric.bitsOfDouble(-0.0));
    }
}
