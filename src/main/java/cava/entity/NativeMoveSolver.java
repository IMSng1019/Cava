package cava.entity;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import cava.shape.AbiOffsets;
import cava.shape.MoveShapeBatch;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import net.minecraft.util.math.Vec3d;

/**
 * {@code cava_resolve_move} 的**唯一**调用点：负责 FFM 的分配、编组、调用与解码。
 *
 * <h2>为什么 {@code step_height} 恒传 0</h2>
 * 冻结 ABI 的 {@code cava_resolve_move} 只接受**一个** {@code refs[]} 形状列表，
 * 而原版 {@code method_17835} 的台阶分支会用**不同的 {@code box.stretch(...)}** 重新跑
 * {@code world.getBlockCollisions}（最多 4 次，见 oracle spec §6.2 的注）。
 * 也就是说「一份形状列表算完整台阶分支」在原理上就不成立。
 *
 * <p>接线采用**可证明正确**的用法：
 * <ol>
 *   <li>恒传 {@code step_height = 0.0} ⇒ 内核**不会**进台阶分支，返回的就是第 0 趟（纯碰撞）的结果，
 *       也就是原版的局部变量 {@code result}；</li>
 *   <li>{@link EntityMoveRuntime} 在 Java 侧用原版的判据（{@code bl/bl2/bl3/bl4} 与
 *       {@code getStepHeight() > 0.0F}）判断「原版这次会不会进台阶分支」；</li>
 *   <li>不会进 ⇒ 原生结果**就是**原版结果，直接采用；
 *       会进 ⇒ 丢掉原生结果，改调原版私有的 {@code adjustMovementForCollisions(Vec3d)}
 *       （**与纯 Java 完全一致的值**，不是近似回退）。</li>
 * </ol>
 * 这条差异写进 {@code docs/CAVA-p2-wiring-notes.md}，并列为对 ABI 的观察项。
 *
 * <h2>数组分配</h2>
 * 一律 {@link CavaNative#allocateArray}：{@code arena.allocate(JAVA_INT, n)} 是「一个 int、值 n」，
 * 只有 4 字节，拿去当数组会让原生写越界并把 JVM 打成段错误（本项目复现过）。
 * Arena 是**长生命周期**的（每次调用只重新分配容量不够的段），避免每条实体每 tick 都建 Arena。
 */
public final class NativeMoveSolver {

    /** 事件数组默认容量（超了内核置 event_overflow=1，调用方回退）。 */
    public static final int DEFAULT_EVENT_CAP = 256;

    private NativeMoveSolver() {
    }

    /** 一次求解的原始输出（不解释语义，只解码）。 */
    public static final class Out {
        /** 原生返回码；{@code CAVA_OK} 之外一律回退。 */
        public int rc = CavaNative.ERR_NATIVE_UNAVAILABLE;
        public int eventCount;
        public int eventOverflow;
        public int stepUsed;
        public double deltaX;
        public double deltaY;
        public double deltaZ;
        public double baseX;
        public double baseY;
        public double baseZ;
        public double stepX;
        public double stepY;
        public double stepZ;
        /** 解码后的事件条数（只统计 BLOCK 来源且被采纳的裁剪事件）。 */
        public int decodedEvents;
        /** 事件里的 shape_token 是否都落在本批 {@code shapes[]} 范围内（回执校验）。 */
        public boolean tokensInRange = true;
        /** 事件里出现过的最大 shape_token（回执证据）。 */
        public long maxToken = -1;

        public Vec3d delta() {
            return new Vec3d(deltaX, deltaY, deltaZ);
        }

        public Vec3d base() {
            return new Vec3d(baseX, baseY, baseZ);
        }
    }

    /** 长生命周期 arena + 可增长的段（主线程调用，无并发）。 */
    private static Arena arena;
    private static MemorySegment reqSeg;
    private static MemorySegment refSeg;
    private static MemorySegment inlineShapeSeg;
    private static MemorySegment inlinePointSeg;
    private static MemorySegment inlineBitSeg;
    private static MemorySegment eventSeg;
    private static MemorySegment outSeg;
    private static int refCap;
    private static int eventCapHeld;
    private static int inlineShapeCap;
    private static int inlinePointCap;
    private static int inlineBitCap;

    private static void ensureArena() {
        if (arena == null) {
            arena = Arena.ofShared();
            reqSeg = arena.allocate(CavaLayouts.MOVE_REQUEST);
            outSeg = arena.allocate(CavaLayouts.MOVE_RESULT);
        }
    }

    private static void ensureRefs(int n) {
        if (n > refCap) {
            refCap = Math.max(32, Integer.highestOneBit(n - 1) << 1);
            refSeg = CavaNative.allocateArray(arena, CavaLayouts.MOVE_SHAPE_REF, refCap);
        }
    }

    private static void ensureEvents(int n) {
        if (n > eventCapHeld) {
            eventCapHeld = n;
            eventSeg = CavaNative.allocateArray(arena, CavaLayouts.MOVE_EVENT, n);
        }
    }

    private static void ensureInlineShapes(int n) {
        if (n > inlineShapeCap) {
            inlineShapeCap = Math.max(16, Integer.highestOneBit(Math.max(1, n - 1)) << 1);
            inlineShapeSeg = CavaNative.allocateArray(arena, CavaLayouts.SHAPE_RECORD, inlineShapeCap);
        }
    }

    private static void ensureInlinePoints(int n) {
        if (n > inlinePointCap) {
            inlinePointCap = Math.max(256, Integer.highestOneBit(Math.max(1, n - 1)) << 1);
            inlinePointSeg = CavaNative.allocateArray(arena, ValueLayout.JAVA_DOUBLE, inlinePointCap);
        }
    }

    private static void ensureInlineBits(int n) {
        if (n > inlineBitCap) {
            inlineBitCap = Math.max(256, Integer.highestOneBit(Math.max(1, n - 1)) << 1);
            inlineBitSeg = CavaNative.allocateArray(arena, ValueLayout.JAVA_LONG, inlineBitCap);
        }
    }

    /**
     * 调用一次 {@code cava_resolve_move}。
     *
     * @param stepHeight 恒传 {@code 0.0}（见类注释）；保留参数只为让调用点读起来像原版
     * @param log        事件解码目标；{@code null} = 不解码（只取位移）
     */
    public static Out solve(long handle, double minX, double minY, double minZ,
                            double maxX, double maxY, double maxZ,
                            double moveX, double moveY, double moveZ,
                            double stepHeight, boolean onGround,
                            MoveShapeBatch batch, int eventCap, MoveEventLog log) {
        Out out = new Out();
        CavaNative nat = CavaNative.get();
        if (nat.bindingsIfOpen() == null) {
            out.rc = CavaNative.ERR_NATIVE_UNAVAILABLE;
            return out;
        }
        ensureArena();
        int refCount = batch.count();
        int inlineCount = batch.inlineCount();
        int inlinePoints = batch.inlinePointCount();
        int inlineBits = batch.inlineBitCount();
        ensureRefs(Math.max(1, refCount));
        ensureEvents(eventCap);
        if (inlineCount > 0) {
            ensureInlineShapes(inlineCount);
            if (inlinePoints > 0) {
                ensureInlinePoints(inlinePoints);
            }
            if (inlineBits > 0) {
                ensureInlineBits(inlineBits);
            }
        }

        reqSeg.set(ValueLayout.JAVA_LONG, AbiOffsets.REQ_RESERVED0, 0L);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_X, minX);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_Y, minY);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MIN_Z, minZ);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_X, maxX);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_Y, maxY);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MAX_Z, maxZ);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_X, moveX);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_Y, moveY);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_MOVE_Z, moveZ);
        reqSeg.set(ValueLayout.JAVA_DOUBLE, AbiOffsets.REQ_STEP_HEIGHT, stepHeight);
        reqSeg.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_FLAGS, 0);
        reqSeg.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_ON_GROUND, onGround ? 1 : 0);
        reqSeg.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_SHAPE_COUNT, refCount);
        reqSeg.set(ValueLayout.JAVA_INT, AbiOffsets.REQ_RESERVED1, 0);

        MemorySegment refs = refCount == 0 ? MemorySegment.NULL : refSeg;
        MemorySegment inShapes = MemorySegment.NULL;
        MemorySegment inPoints = MemorySegment.NULL;
        MemorySegment inBits = MemorySegment.NULL;
        if (refCount > 0) {
            batch.writeRefs(refSeg);
        }
        if (inlineCount > 0) {
            inShapes = inlineShapeSeg;
            batch.writeInlineShapes(inlineShapeSeg);
            if (inlinePoints > 0) {
                inPoints = inlinePointSeg;
                batch.writeInlinePoints(inlinePointSeg);
            }
            if (inlineBits > 0) {
                inBits = inlineBitSeg;
                batch.writeInlineBits(inlineBitSeg);
            }
        }

        out.rc = nat.resolveMove(handle, reqSeg, refs, refCount,
                inShapes, inlineCount, inPoints, inlinePoints, inBits, inlineBits,
                eventSeg, eventCap, outSeg);
        if (out.rc != CavaLayouts.CAVA_OK) {
            return out;
        }
        out.stepUsed = outSeg.get(ValueLayout.JAVA_INT, AbiOffsets.RESULT_STEP_USED);
        out.eventCount = outSeg.get(ValueLayout.JAVA_INT, AbiOffsets.RESULT_EVENT_COUNT);
        out.eventOverflow = outSeg.get(ValueLayout.JAVA_INT, AbiOffsets.RESULT_EVENT_OVERFLOW);
        out.deltaX = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_X);
        out.deltaY = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Y);
        out.deltaZ = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_DELTA_Z);
        out.baseX = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_BASE_X);
        out.baseY = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_BASE_Y);
        out.baseZ = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_BASE_Z);
        out.stepX = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_STEP_X);
        out.stepY = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_STEP_Y);
        out.stepZ = outSeg.get(ValueLayout.JAVA_DOUBLE, AbiOffsets.RESULT_STEP_Z);

        if (log != null) {
            if (out.eventOverflow == 0) {
                for (int i = 0; i < out.eventCount; i++) {
                    long o = (long) i * AbiOffsets.EVENT_SIZE;
                    int src = eventSeg.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_SOURCE);
                    int axis = eventSeg.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_AXIS);
                    int bx = eventSeg.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_BLOCK_X);
                    int by = eventSeg.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_BLOCK_Y);
                    int bz = eventSeg.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_BLOCK_Z);
                    int accepted = eventSeg.get(ValueLayout.JAVA_INT, o + AbiOffsets.EVENT_ACCEPTED);
                    long tok = eventSeg.get(ValueLayout.JAVA_LONG, o + AbiOffsets.EVENT_SHAPE_TOKEN);
                    if (tok > out.maxToken) {
                        out.maxToken = tok;
                    }
                    if (tok < 0 || tok >= refCount) {
                        out.tokensInRange = false;
                    }
                    // 回放只需要「哪个轴被哪个方块夹住」；source != BLOCK 的命中没有方块坐标。
                    if (src == MoveShapeBatch.SRC_BLOCK && accepted == 1) {
                        log.add(MoveEventKind.AXIS_CLIP, bx, by, bz, axis);
                        out.decodedEvents++;
                    }
                }
            }
        }
        return out;
    }

    /** 诊断：当前持有的段容量（单测/报告用）。 */
    public static String capacityReport() {
        return "refCap=" + refCap + " eventCap=" + eventCapHeld
                + " inlineShapes=" + inlineShapeCap + " inlinePoints=" + inlinePointCap
                + " inlineBits=" + inlineBitCap;
    }
}
