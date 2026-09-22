package cava.entity;

/**
 * 每 tick 的实体观测入口：把「打包 → 取上一 tick 快照 → 冻结核对 → 交换」这套
 * <b>顺序敏感的记账</b>收进一个类里，调用方只有 {@link #tick(int, EntitySource)} 一个动作。
 *
 * <p>P1 的教训之一是"会致命的调用时序约束不要只写在注释里"。这里的时序坑是：
 * {@link TickSkipLedger} 每 tick 会被 {@link EntityMirror#pack} 清空，
 * 所以"上一 tick 的 inactive 集合"必须<b>在 pack 之前</b>取快照。
 * 本类把这个顺序固化，调用方拿不到弄反的机会。
 *
 * <p><b>本轮只观测，不接管</b>：本类不 inject 任何东西、不改任何实体字段。
 * 它的产物（镜像 + 台账 + 冻结核对）就是后续逐 tick 差分的证据面。
 */
public final class EntityTickObserver {

    private final InactivityProbe probe;

    private final EntityMirror mirrorA = new EntityMirror();
    private final EntityMirror mirrorB = new EntityMirror();
    private EntityMirror current = mirrorA;
    private EntityMirror spare = mirrorB;

    private TickSkipLedger.Snapshot previousInactive;
    private EntityMirror.FrozenReport lastFrozen =
            new EntityMirror.FrozenReport(0, 0, java.util.List.of(), 0, "尚未观测");

    public EntityTickObserver() {
        this(InactivityProbe.serverCore());
    }

    public EntityTickObserver(InactivityProbe probe) {
        this.probe = probe == null ? InactivityProbe.NONE : probe;
    }

    /**
     * 观测一个 tick。<b>必须在 tick 开始时调用一次，且每 tick 只调一次。</b>
     */
    public void tick(int tick, EntitySource source) {
        TickSkipLedger.Snapshot before = current.ledger().snapshot(SkipReason.SERVERCORE_INACTIVE);
        spare.pack(tick, source, probe);
        EntityMirror.FrozenReport frozen = spare.checkInactiveStability(current, before);
        EntityMirror tmp = current;
        current = spare;
        spare = tmp;
        this.previousInactive = before;
        this.lastFrozen = frozen;
    }

    /** 本 tick 的镜像（只读）。 */
    public EntityMirror mirror() {
        return current;
    }

    /** 上一 tick 的镜像（只读；用于差分）。 */
    public EntityMirror previousMirror() {
        return spare;
    }

    public TickSkipLedger ledger() {
        return current.ledger();
    }

    public TickSkipLedger.Snapshot previousInactive() {
        return previousInactive;
    }

    public EntityMirror.FrozenReport lastFrozen() {
        return lastFrozen;
    }

    /** 一行日志：状态量全部来自本 tick 实测，不含估计值。 */
    public String reportLine() {
        return "tick=" + current.tick() + " entities=" + current.size() + " | " + ledger().report()
                + " | " + lastFrozen.report();
    }
}
