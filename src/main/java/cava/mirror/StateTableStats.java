package cava.mirror;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态表与区域推送的**实测台账**（供启动报告 / docs 落盘用，全部是真实测量值）。
 */
public final class StateTableStats {

    /** 状态数（= 记录数）。 */
    public int stateCount;
    /** 碰撞盒总数（所有状态相加）。 */
    public int boxTotal;
    /** 没有任何碰撞盒的状态数。 */
    public int statesWithoutBoxes;
    /** 单状态最多几个碰撞盒。 */
    public int maxBoxesPerState;
    /** 每个谓词命中多少状态（下标 = {@link MirrorFlags.Pred#ordinal()}）。 */
    public int[] predCounts = new int[MirrorFlags.PRED_COUNT];
    /** 构建（读注册表 + 算形状）耗时。 */
    public long buildNanos;
    /** 上传（FFM 调用）耗时。 */
    public long uploadNanos;
    /** 实测 air 的 state id（契约点名的待验假设）。 */
    public int airStateId = -1;
    /** 实测 {@code Block.getRawIdFromState(AIR) == 0}。 */
    public boolean airIsZero;
    /** 自由文本备注（例如"哪些方块覆写了带 pos 的碰撞形状"）。 */
    public final List<String> notes = new ArrayList<>();

    /** 上传后的表版本号（每次成功上传 +1；供 profileKey / 失效判定用）。 */
    public int tableEpoch;

    /** 一个简短的多行报告。 */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("状态数=").append(stateCount)
          .append(" 碰撞盒=").append(boxTotal)
          .append(" 无碰撞盒状态=").append(statesWithoutBoxes)
          .append(" 单状态最多盒=").append(maxBoxesPerState).append('\n');
        sb.append("air state id=").append(airStateId).append("（== 0 ? ").append(airIsZero).append("）").append('\n');
        sb.append("构建耗时=").append(buildNanos / 1_000_000.0).append(" ms")
          .append(" 上传耗时=").append(uploadNanos / 1_000_000.0).append(" ms")
          .append(" epoch=").append(tableEpoch).append('\n');
        for (MirrorFlags.Pred p : MirrorFlags.Pred.values()) {
            sb.append("  ").append(String.format("%-24s", p.name()))
              .append(String.format("%6d", predCounts[p.ordinal()]))
              .append(p.kernelReads() ? "  (内核读)" : "  (派生/未读)")
              .append('\n');
        }
        for (String n : notes) {
            sb.append("  note: ").append(n).append('\n');
        }
        return sb.toString();
    }
}
