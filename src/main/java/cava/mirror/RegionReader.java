package cava.mirror;

/**
 * 区域推送的**方块来源**（MC 绑定层与单测假世界之间的缝）。
 *
 * <p>实现者：{@code ServerWorldRegionReader}（真实 ServerWorld）与单测里的
 * {@code FakeRegionReader}。坐标一律是**世界方块坐标**。
 */
public interface RegionReader {

    /** 维度标识（用于"区域是不是同一个世界"的失效判定）。 */
    String dimensionId();

    /** 世界最底方块 Y（含）。 */
    int minY();

    /** 世界最高方块 Y（含）。 */
    int maxY();

    /** 当前游戏 tick（失效判定用；原版 {@code World.getTime()}）。 */
    long currentTick();

    /** 该长方体的所有区块是否都已加载（未加载 → 调用方必须回退，不许猜）。 */
    boolean isReady(int minX, int minY, int minZ, int dimX, int dimY, int dimZ);

    /**
     * 把长方体内的 state id 填进 {@code out}，索引顺序 = {@code ((y*dimZ)+z)*dimX+x}（**x 最快、y 最慢**）。
     *
     * <p>越界 / 世界高度之外的格子填 {@code airStateId}。
     * 区块未加载时**抛 {@link RegionSource.MirrorUnavailableException}**（显式失败，不静默）。
     */
    void fill(int minX, int minY, int minZ, int dimX, int dimY, int dimZ, int[] out, int airStateId);
}
