package cava.mirror;

/**
 * 一个有界长方体区域（**x 最快、y 最慢**的索引约定见 {@link RegionReader#fill}）。
 *
 * <p>还包含"求解窗口该开多大"的**唯一推导处**（{@link #forSolve}）—— 这是性能与正确性的交点：
 * 太小会让原生把区域外当成"无碰撞的空气"（结果与原版不一致），太大会让每次寻路的推送成本爆掉。
 *
 * <p><b>边距是怎么推出来的</b>（每一项都能在原版字节码里找到出处，spec §5.4.5 / §7.1）：
 * <ul>
 *   <li>水平 ±4：{@code getNodeTypeFromNeighbors} 扫 3×3×3（±1）；{@code findNearbyNodeTypes} 从节点起
 *       扫 {@code floor(width+1)} 格（体型 ≤ 1.4 时 +2）；第 8 步的 Box 以「节点角点」为中心、
 *       再向外扩 halfWidth；{@code isBlocked(PathNode)} 还会沿射线走。±4 覆盖 width ≤ 1.4，
 *       更宽的生物按 {@code floor(width/2)+2} 增大。</li>
 *   <li>向上 +4：第 8 步递归 {@code y+1}；{@code findNearbyNodeTypes} 的 {@code floor(height+1)} ≤ 3（height ≤ 2）；
 *       目标节点自身 ±1。</li>
 *   <li>向下 {@code safeFallDistance + 4}：第 10 步在 {@code type == OPEN} 时一直往下找，
 *       最多 {@code safeFallDistance} 层；再加 3×3×3 的下方邻居与 {@code getFeetY} 的 y-1。</li>
 * </ul>
 *
 * <p>Y 会**裁剪到世界高度**（原版在 {@code world.getBottomY()} 处停下）；X/Z 不裁剪（世界无界），
 * 但调用方要保证区块已加载 —— 否则 {@code RegionReader.fill} 会显式抛异常。
 */
public record RegionRect(int minX, int minY, int minZ, int dimX, int dimY, int dimZ) {

    public RegionRect {
        if (dimX <= 0 || dimY <= 0 || dimZ <= 0) {
            throw new IllegalArgumentException("维度必须 > 0: dimX=" + dimX + " dimY=" + dimY + " dimZ=" + dimZ);
        }
    }

    /** 体积（方块数）。 */
    public long volume() {
        return (long) dimX * (long) dimY * (long) dimZ;
    }

    /** 最大 X（含）。 */
    public int maxX() {
        return minX + dimX - 1;
    }

    /** 最大 Y（含）。 */
    public int maxY() {
        return minY + dimY - 1;
    }

    /** 最大 Z（含）。 */
    public int maxZ() {
        return minZ + dimZ - 1;
    }

    /** 由两个**含端点**的角点构造。 */
    public static RegionRect ofCorners(int x0, int y0, int z0, int x1, int y1, int z1) {
        int lx = Math.min(x0, x1);
        int ly = Math.min(y0, y1);
        int lz = Math.min(z0, z1);
        return new RegionRect(lx, ly, lz,
                Math.abs(x1 - x0) + 1, Math.abs(y1 - y0) + 1, Math.abs(z1 - z0) + 1);
    }

    /** 水平边距（见类注释）。 */
    public static int horizontalMargin(float width) {
        int byWidth = (int) Math.floor(width / 2.0f) + 2;
        return Math.max(4, byWidth);
    }

    /** 向上边距。 */
    public static int upMargin(float height) {
        return Math.max(4, (int) Math.floor(height + 1.0f) + 1);
    }

    /** 向下边距。 */
    public static int downMargin(int safeFallDistance) {
        return Math.max(0, safeFallDistance) + 4;
    }

    /**
     * 起点↔终点包围盒 + 边距，Y 裁剪到世界高度。
     *
     * @param worldMinY 世界最底方块 Y（{@code world.getBottomY()}）
     * @param worldMaxY 世界最高方块 Y（含，{@code world.getTopY()-1}）
     */
    public static RegionRect forSolve(int sx, int sy, int sz, int tx, int ty, int tz,
                                      float width, float height, int safeFallDistance,
                                      int worldMinY, int worldMaxY) {
        int h = horizontalMargin(width);
        int up = upMargin(height);
        int down = downMargin(safeFallDistance);
        int x0 = Math.min(sx, tx) - h;
        int x1 = Math.max(sx, tx) + h;
        int z0 = Math.min(sz, tz) - h;
        int z1 = Math.max(sz, tz) + h;
        int y0 = Math.min(sy, ty) - down;
        int y1 = Math.max(sy, ty) + up;
        if (y0 < worldMinY) {
            y0 = worldMinY;
        }
        if (y1 > worldMaxY) {
            y1 = worldMaxY;
        }
        if (y1 < y0) {
            y1 = y0;
        }
        return ofCorners(x0, y0, z0, x1, y1, z1);
    }

    /** 人类可读。 */
    public String describe() {
        return "[" + minX + ".." + maxX() + "][" + minY + ".." + maxY() + "][" + minZ + ".." + maxZ()
                + "] " + dimX + "x" + dimY + "x" + dimZ + "=" + volume();
    }
}
