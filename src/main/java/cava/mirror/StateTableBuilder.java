package cava.mirror;

/**
 * 把 {@link StateProbe} 采样成 {@link StateTableData}（纯逻辑，可单测）。
 *
 * <p>没有任何 Minecraft 依赖，所以"每个状态 → 一条记录"的映射可以脱离服务器验证。
 * 真实采样（{@code McStateProbe}）只负责把原版谓词读出来，不参与这里的装箱。
 */
public final class StateTableBuilder {

    private StateTableBuilder() {
    }

    /** 构建结果。 */
    public record Result(StateTableData data, StateTableStats stats) {
    }

    /** 无碰撞盒的标记（= {@code CAVA_BOX_NONE}）。 */
    public static final int BOX_NONE = -1; // 写进 uint32 字段时按 0xFFFFFFFF 写

    /** 逐状态构建。 */
    public static Result build(StateProbe probe) {
        long begin = System.nanoTime();
        int n = probe.stateCount();
        if (n <= 0) {
            throw new IllegalArgumentException("stateCount=" + n);
        }
        int[] flags = new int[n];
        int[] boxOffset = new int[n];
        int[] boxCount = new int[n];
        int[] pathTypeIdx = new int[n];
        float[] malus = new float[n];

        StateTableStats stats = new StateTableStats();
        stats.stateCount = n;

        StateSample sample = new StateSample();
        float[] scratch = new float[6];
        // 先按每个状态最多 8 个盒估个容量，超了再长（真实最多的是 6 面台阶/栅栏之类）
        float[] boxes = new float[6 * 64];
        int boxTotal = 0;

        for (int id = 0; id < n; id++) {
            sample.reset();
            probe.probe(id, sample);
            if (MirrorFlags.unknownBits(sample.flags) != 0) {
                throw new IllegalStateException("state " + id + " 出现未定义位: 0x"
                        + Integer.toHexString(MirrorFlags.unknownBits(sample.flags)));
            }
            flags[id] = sample.flags;
            if (sample.commonType < 0 || sample.commonType >= PathTypes.COUNT) {
                throw new IllegalStateException("state " + id + " 的 commonType=" + sample.commonType + " 越界");
            }
            pathTypeIdx[id] = sample.commonType;
            malus[id] = PathTypes.defaultPenalty(sample.commonType);

            int bc = sample.boxCount;
            if (bc == 0) {
                boxOffset[id] = BOX_NONE;
                boxCount[id] = 0;
                stats.statesWithoutBoxes++;
            } else {
                boxOffset[id] = boxTotal;
                boxCount[id] = bc;
                if (bc > stats.maxBoxesPerState) {
                    stats.maxBoxesPerState = bc;
                }
                while ((boxTotal + bc) * 6 > boxes.length) {
                    float[] bigger = new float[boxes.length * 2];
                    System.arraycopy(boxes, 0, bigger, 0, boxTotal * 6);
                    boxes = bigger;
                }
                for (int b = 0; b < bc; b++) {
                    sample.boxInto(b, scratch);
                    System.arraycopy(scratch, 0, boxes, (boxTotal + b) * 6, 6);
                }
                boxTotal += bc;
            }
            for (MirrorFlags.Pred p : MirrorFlags.Pred.values()) {
                if ((sample.flags & p.bit()) != 0) {
                    stats.predCounts[p.ordinal()]++;
                }
            }
        }
        stats.boxTotal = boxTotal;
        float[] exactBoxes = new float[boxTotal * 6];
        System.arraycopy(boxes, 0, exactBoxes, 0, boxTotal * 6);
        stats.buildNanos = System.nanoTime() - begin;
        return new Result(new StateTableData(n, boxTotal, flags, boxOffset, boxCount, pathTypeIdx, malus, exactBoxes), stats);
    }

    /** 一个 state id → 可读名的转换（普查报告用；纯逻辑层不依赖 MC，所以由调用方给）。 */
    public interface IdNamer {
        String name(int stateId);
    }

    /**
     * 比较两张表的碰撞盒是否一致，返回人类可读的普查报告。
     *
     * <p>用途：量化"位置依赖形状"（ABI 表达不了）的状态数。**不在热路径上。**
     */
    public static String compareBoxes(StateTableData a, StateTableData b, IdNamer namer, int maxExamples) {
        if (a.stateCount != b.stateCount) {
            return "stateCount 不同: " + a.stateCount + " vs " + b.stateCount;
        }
        int same = 0;
        int different = 0;
        StringBuilder examples = new StringBuilder();
        for (int id = 0; id < a.stateCount; id++) {
            boolean eq = a.boxCount(id) == b.boxCount(id);
            if (eq) {
                for (int i = 0; i < a.boxCount(id) && eq; i++) {
                    for (int c = 0; c < 6; c++) {
                        if (a.box(i + a.boxOffset(id), c) != b.box(i + b.boxOffset(id), c)) {
                            eq = false;
                            break;
                        }
                    }
                }
            }
            if (eq) {
                same++;
            } else {
                different++;
                if (different <= maxExamples) {
                    examples.append("    id=").append(id)
                            .append(" ").append(namer == null ? "?" : namer.name(id))
                            .append(": boxes ").append(a.boxCount(id)).append(" vs ").append(b.boxCount(id))
                            .append('\n');
                }
            }
        }
        return "位置依赖碰撞形状普查：一致 " + same + " / 不同 " + different + " / 共 " + a.stateCount + '\n'
                + "  不同的前 " + Math.min(different, maxExamples) + " 个（邻居=空气 vs 邻居=自身）：\n" + examples;
    }
}
