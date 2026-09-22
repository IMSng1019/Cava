package cava.entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 实体 SoA 镜像：<b>每 tick 一次性打包</b>，只读，绝不回写。
 *
 * <p>字段：id / 类型 / 位置 / 速度 / 碰撞盒 / 标志（位布局见 {@link EntityFlags}）。
 * 打包端 = {@link #pack}，读取端 = 各 {@code id(i)/x(i)/...} 与 {@link #readInto}。
 * <b>上传端故意留空</b> —— P2-K 的 ABI 尚未冻结，先只做"打包"与"读取"两端
 * （{@code docs/CAVA-p2-java-notes.md} 的「等 ABI」清单）。
 *
 * <h2>三条硬语义（都有单测）</h2>
 * <ol>
 *   <li><b>只读</b>：{@link EntitySource} 只有 {@code sample()} 一个读方法，
 *       本类没有任何写回实体的路径 —— "不要主动唤醒、不要回写"是结构性保证，不是纪律。</li>
 *   <li><b>inactive 实体照样打包</b>：ServerCore 让一批实体整 tick 不 tick，
 *       但它们<b>仍然存在、仍然参与碰撞</b>。若因为 inactive 就把它们从镜像里剔掉，
 *       碰撞结果会变 —— 那是行为差异，不是优化。所以：打包 + 置
 *       {@link EntityFlags#SERVERCORE_INACTIVE} + 记台账，三件事一起做。</li>
 *   <li><b>位置不变是预期</b>：{@link #checkInactiveStability} 把
 *       "连续两 tick 都 inactive 的实体位置/速度位模式必须逐位不变"做成<b>可核对的证据</b>，
 *       而不是靠人记住。违反只上报、不抛异常（真机上可能有载具等例外路径）。</li>
 * </ol>
 */
public final class EntityMirror {

    private static final int INITIAL = 64;

    private int[] ids = new int[INITIAL];
    private int[] typeIds = new int[INITIAL];
    private int[] flags = new int[INITIAL];
    private double[] xs = new double[INITIAL];
    private double[] ys = new double[INITIAL];
    private double[] zs = new double[INITIAL];
    private double[] vxs = new double[INITIAL];
    private double[] vys = new double[INITIAL];
    private double[] vzs = new double[INITIAL];
    private double[] minXs = new double[INITIAL];
    private double[] minYs = new double[INITIAL];
    private double[] minZs = new double[INITIAL];
    private double[] maxXs = new double[INITIAL];
    private double[] maxYs = new double[INITIAL];
    private double[] maxZs = new double[INITIAL];

    private int size;
    private int tick = Integer.MIN_VALUE;

    private int[] mapKeys = new int[0];
    private int[] mapVals = new int[0];
    private boolean[] mapUsed = new boolean[0];
    private int mapMask;

    private final TickSkipLedger ledger = new TickSkipLedger();
    private final EntitySample scratch = new EntitySample();

    /** 上一 tick 连续 inactive 实体的冻结核对结果。 */
    public record FrozenReport(int pairsChecked, int violations, List<Integer> violatingIds, int newlyInactive,
            String note) {

        public FrozenReport {
            violatingIds = List.copyOf(violatingIds);
        }

        public boolean ok() {
            return violations == 0;
        }

        public String report() {
            return "冻结核对: 连续 inactive 对=" + pairsChecked + " 位模式变化=" + violations
                    + " 本 tick 新进入 inactive=" + newlyInactive
                    + (violatingIds.isEmpty() ? "" : " 例=" + violatingIds) + " (" + note + ")";
        }
    }

    /**
     * 每 tick 一次性打包（<b>必须在 tick 开始时调用</b>：它会 {@code ledger.begin(tick)}，
     * 台账是"本 tick"的，晚调用会把上一 tick 的记录冲掉）。
     *
     * @param tick   本 tick 序号（写进台账，差分脚本按它对齐）
     * @param source 窄输入接口（MC 适配由注入流实现）
     * @param probe  inactive 探测（生产用 {@code InactivityProbe.serverCore()}，可传 null = 不探测）
     */
    public void pack(int tick, EntitySource source, InactivityProbe probe) {
        this.tick = tick;
        this.size = 0;
        this.ledger.begin(tick);

        int n = source.size();
        ensureCapacity(n);
        for (int i = 0; i < n; i++) {
            scratch.reset();
            if (!source.sample(i, scratch)) {
                ledger.record(scratch.id, SkipReason.UNSAMPLABLE);
                continue;
            }
            if (scratch.has(EntityFlags.REMOVED)) {
                ledger.record(scratch.id, SkipReason.REMOVED);
                continue;
            }
            if (probe != null && probe.isInactive(scratch.handle)) {
                // 只置观测位 + 记台账；实体照样进镜像（见类注释第 2 条）
                scratch.flags |= EntityFlags.SERVERCORE_INACTIVE;
                ledger.record(scratch.id, SkipReason.SERVERCORE_INACTIVE);
            }
            store(scratch);
        }
        rebuildIndex();
    }

    /** 清空（不含台账）。 */
    public void clear() {
        size = 0;
        Arrays.fill(mapUsed, false);
    }

    public int size() {
        return size;
    }

    public int tick() {
        return tick;
    }

    public TickSkipLedger ledger() {
        return ledger;
    }

    public int id(int index) {
        return ids[index];
    }

    public int typeId(int index) {
        return typeIds[index];
    }

    public int flags(int index) {
        return flags[index];
    }

    public boolean flag(int index, int flag) {
        return (flags[index] & flag) != 0;
    }

    public double x(int index) {
        return xs[index];
    }

    public double y(int index) {
        return ys[index];
    }

    public double z(int index) {
        return zs[index];
    }

    public double vx(int index) {
        return vxs[index];
    }

    public double vy(int index) {
        return vys[index];
    }

    public double vz(int index) {
        return vzs[index];
    }

    public double minX(int index) {
        return minXs[index];
    }

    public double minY(int index) {
        return minYs[index];
    }

    public double minZ(int index) {
        return minZs[index];
    }

    public double maxX(int index) {
        return maxXs[index];
    }

    public double maxY(int index) {
        return maxYs[index];
    }

    public double maxZ(int index) {
        return maxZs[index];
    }

    /**
     * 读取端（全保真）：把第 {@code index} 行写进 {@code out}。
     * double 的<b>原始位模式</b>用 {@code Double.doubleToRawLongBits(out.x)} 取 —— 轨迹哈希必须用它，
     * 不能用格式化后的十进制。
     */
    public void readInto(int index, EntitySample out) {
        out.handle = null;
        out.id = ids[index];
        out.typeId = typeIds[index];
        out.x = xs[index];
        out.y = ys[index];
        out.z = zs[index];
        out.vx = vxs[index];
        out.vy = vys[index];
        out.vz = vzs[index];
        out.minX = minXs[index];
        out.minY = minYs[index];
        out.minZ = minZs[index];
        out.maxX = maxXs[index];
        out.maxY = maxYs[index];
        out.maxZ = maxZs[index];
        out.flags = flags[index];
    }

    /** 按实体 id 找行号；找不到返回 -1。 */
    public int indexOfId(int entityId) {
        if (mapUsed.length == 0) {
            return -1;
        }
        int i = mix(entityId) & mapMask;
        while (mapUsed[i]) {
            if (mapKeys[i] == entityId) {
                return mapVals[i];
            }
            i = (i + 1) & mapMask;
        }
        return -1;
    }

    /**
     * 冻结核对：<b>连续两 tick 都被 ServerCore 跳过</b>的实体，
     * 位置 / 速度 / 碰撞盒的 12 个 double 必须逐位不变（原始位模式比较）。
     *
     * <p>这是"范围外实体整 tick 不 tick ⇒ 位置不变是预期行为"这句话的可执行版本：
     * 变了就说明有别的路径动了它（活塞、载具、别的 mod），需要人去看，而不是猜。
     * <b>只上报，不抛异常</b>；同时统计"本 tick 新进入 inactive"的数量 ——
     * 新进入的那一 tick 位置本来就允许变（它上一 tick 还在 tick）。
     */
    public FrozenReport checkInactiveStability(EntityMirror previous, TickSkipLedger.Snapshot previousInactive) {
        if (previous == null || previousInactive == null) {
            return new FrozenReport(0, 0, List.of(), 0, "无上一 tick 数据");
        }
        int checked = 0;
        int violations = 0;
        int newlyInactive = 0;
        List<Integer> bad = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            int id = ids[i];
            boolean nowInactive = (flags[i] & EntityFlags.SERVERCORE_INACTIVE) != 0;
            boolean wasInactive = previousInactive.contains(id);
            if (nowInactive && !wasInactive) {
                newlyInactive++;
            }
            if (!nowInactive || !wasInactive) {
                continue;
            }
            int p = previous.indexOfId(id);
            if (p < 0) {
                violations++;
                if (bad.size() < 16) {
                    bad.add(id);
                }
                continue;
            }
            checked++;
            if (differsAt(previous, p, i)) {
                violations++;
                if (bad.size() < 16) {
                    bad.add(id);
                }
            }
        }
        return new FrozenReport(checked, violations, bad, newlyInactive, "servercore-inactive 冻结");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private boolean differsAt(EntityMirror p, int pi, int ci) {
        return Double.doubleToRawLongBits(p.xs[pi]) != Double.doubleToRawLongBits(xs[ci])
                || Double.doubleToRawLongBits(p.ys[pi]) != Double.doubleToRawLongBits(ys[ci])
                || Double.doubleToRawLongBits(p.zs[pi]) != Double.doubleToRawLongBits(zs[ci])
                || Double.doubleToRawLongBits(p.vxs[pi]) != Double.doubleToRawLongBits(vxs[ci])
                || Double.doubleToRawLongBits(p.vys[pi]) != Double.doubleToRawLongBits(vys[ci])
                || Double.doubleToRawLongBits(p.vzs[pi]) != Double.doubleToRawLongBits(vzs[ci])
                || Double.doubleToRawLongBits(p.minXs[pi]) != Double.doubleToRawLongBits(minXs[ci])
                || Double.doubleToRawLongBits(p.minYs[pi]) != Double.doubleToRawLongBits(minYs[ci])
                || Double.doubleToRawLongBits(p.minZs[pi]) != Double.doubleToRawLongBits(minZs[ci])
                || Double.doubleToRawLongBits(p.maxXs[pi]) != Double.doubleToRawLongBits(maxXs[ci])
                || Double.doubleToRawLongBits(p.maxYs[pi]) != Double.doubleToRawLongBits(maxYs[ci])
                || Double.doubleToRawLongBits(p.maxZs[pi]) != Double.doubleToRawLongBits(maxZs[ci]);
    }

    private void store(EntitySample s) {
        ids[size] = s.id;
        typeIds[size] = s.typeId;
        flags[size] = s.flags;
        xs[size] = s.x;
        ys[size] = s.y;
        zs[size] = s.z;
        vxs[size] = s.vx;
        vys[size] = s.vy;
        vzs[size] = s.vz;
        minXs[size] = s.minX;
        minYs[size] = s.minY;
        minZs[size] = s.minZ;
        maxXs[size] = s.maxX;
        maxYs[size] = s.maxY;
        maxZs[size] = s.maxZ;
        size++;
    }

    private void ensureCapacity(int n) {
        if (n <= ids.length) {
            return;
        }
        int cap = Math.max(INITIAL, Integer.highestOneBit(Math.max(1, n - 1)) << 1);
        ids = Arrays.copyOf(ids, cap);
        typeIds = Arrays.copyOf(typeIds, cap);
        flags = Arrays.copyOf(flags, cap);
        xs = Arrays.copyOf(xs, cap);
        ys = Arrays.copyOf(ys, cap);
        zs = Arrays.copyOf(zs, cap);
        vxs = Arrays.copyOf(vxs, cap);
        vys = Arrays.copyOf(vys, cap);
        vzs = Arrays.copyOf(vzs, cap);
        minXs = Arrays.copyOf(minXs, cap);
        minYs = Arrays.copyOf(minYs, cap);
        minZs = Arrays.copyOf(minZs, cap);
        maxXs = Arrays.copyOf(maxXs, cap);
        maxYs = Arrays.copyOf(maxYs, cap);
        maxZs = Arrays.copyOf(maxZs, cap);
    }

    private void rebuildIndex() {
        int cap = Integer.highestOneBit(Math.max(16, size * 2 - 1)) << 1;
        if (mapUsed.length != cap) {
            mapKeys = new int[cap];
            mapVals = new int[cap];
            mapUsed = new boolean[cap];
        } else {
            Arrays.fill(mapUsed, false);
        }
        mapMask = cap - 1;
        for (int i = 0; i < size; i++) {
            int k = mix(ids[i]) & mapMask;
            while (mapUsed[k]) {
                k = (k + 1) & mapMask;
            }
            mapUsed[k] = true;
            mapKeys[k] = ids[i];
            mapVals[k] = i;
        }
    }

    /** 32 位整数混合（fibonacci hashing），保证相邻 id 不聚集。 */
    private static int mix(int v) {
        return (v * 0x9E3779B9) >>> 1;
    }
}
