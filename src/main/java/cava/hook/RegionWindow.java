package cava.hook;

/**
 * 一次区域推送的窗口（**纯计算，可单测**）。
 *
 * <p>为什么窗口是注入流的责任：{@code cava_region_upload} 只接受"一个有界长方体"，
 * 而原版的搜索范围由 {@code maxRange} 与节点预算共同界定。窗口给小了，区域外的方块在原生侧
 * 等价于"无碰撞的空格"⇒ 路径会穿墙；窗口给大了，每次寻路都要 memcpy 几 MB。
 *
 * <p><b>当前策略（保守，待整合轮调优）</b>：以起点为中心、每轴 {@code ceil(maxRange)+1} 的半长，
 * Y 再夹到 {@code [bottomY, topY]}。依据：
 * <ul>
 *   <li>导航器只在 {@code current.getDistance(start) < maxRange} 时展开邻居（oracle spec §4.4），
 *       所以**可达节点必在以起点为球心、半径 maxRange 的球内**；</li>
 *   <li>松弛另有 {@code successor.pathLength < maxRange} 门槛（§4.5），不会把搜索推到球外；</li>
 *   <li>因此 ±ceil(maxRange)+1 的立方体是可达球的外接盒，**不会漏方块**。</li>
 * </ul>
 *
 * <p>⚠️ 本策略**未在真实服务器上验证**（原生内核仍保守回退）；体积上限 {@code maxRegionBlocks}
 * 一旦超出就直接回退原逻辑，不做部分推送。
 */
public final class RegionWindow {

    /** 一个待推送的长方体。索引顺序由 {@code RegionSource.push} 负责（x 最快、y 最慢）。 */
    public record Window(int minX, int minY, int minZ, int dimX, int dimY, int dimZ, long volume) {

        public boolean fits(long maxBlocks) {
            return volume > 0 && volume <= maxBlocks;
        }

        @Override
        public String toString() {
            return "window[" + minX + "," + minY + "," + minZ + " +" + dimX + "x" + dimY + "x" + dimZ
                    + " = " + volume + "]";
        }
    }

    private RegionWindow() {
    }

    /**
     * 以 {@code (startX,startY,startZ)} 为中心算窗口。
     *
     * @param maxRange 导航器的 maxRange（float，原样）；NaN / 非正 ⇒ 返回 {@code null}（回退）
     * @param bottomY  世界下界（含）
     * @param topY     世界上界（含）；{@code topY < bottomY} 时按 {@code bottomY} 处理
     * @return 窗口；参数不可用时返回 {@code null}（调用方必须回退原逻辑）
     */
    public static Window compute(int startX, int startY, int startZ,
                                 float maxRange, int bottomY, int topY, long maxBlocks) {
        if (!(maxRange > 0.0f) || Float.isInfinite(maxRange)) {
            return null;   // NaN / 0 / 负数 / +Inf 都不可用
        }
        // ceil 用整数运算做，避免 Math.ceil 的 double 往返（这里不能有任何浮点不确定性）
        int half = (int) maxRange;
        if ((float) half < maxRange) {
            half++;
        }
        half += 1;
        if (half <= 0) {
            return null;
        }
        int lo = Math.min(bottomY, topY);
        int hi = Math.max(bottomY, topY);
        long yMin = (long) startY - half;
        long yMax = (long) startY + half + 1;   // 半开区间 [yMin, yMax)
        if (yMin < lo) {
            yMin = lo;
        }
        if (yMax > (long) hi + 1) {
            yMax = (long) hi + 1;
        }
        if (yMax <= yMin) {
            return null;
        }
        long xMin = (long) startX - half;
        long xMax = (long) startX + half + 1;
        long zMin = (long) startZ - half;
        long zMax = (long) startZ + half + 1;

        int dimX = (int) (xMax - xMin);
        int dimY = (int) (yMax - yMin);
        int dimZ = (int) (zMax - zMin);
        long volume = (long) dimX * dimY * dimZ;
        if (volume <= 0 || volume > Integer.MAX_VALUE) {
            return null;
        }
        if (volume > maxBlocks) {
            return null;   // 交给调用方回退；**绝不部分推送**（部分区域 = 静默穿墙）
        }
        return new Window((int) xMin, (int) yMin, (int) zMin, dimX, dimY, dimZ, volume);
    }
}
