package cava.hook;

import java.util.List;

/**
 * **窗口截断检测**（缺陷 2 的修复件，2026-09-24 由 P1-FIX 流加）。纯函数、可单测、不引用 MC 类型。
 *
 * <p><b>要解决的问题</b>：镜像窗口 = 起点终点包围盒 + 边距（{@code RegionRect.forSolve}），
 * 而原版 A\* 的最优路径**可以离开这个盒子**。实测（{@code docs/CAVA-pathfind-perf.md} §6.2）：
 * {@code detour128/repush} 腿原生返回 {@code nodes=64 end=(95,71,0) manh=65}，而 native 关闭时是
 * {@code nodes=128 end=(159,71,0) manh=1} —— 原生把"到最接近点"的截断路径当成了答案。
 *
 * <p>本类做的是**保守检测 + 回退 Java**（{@code PathfindHook} 拿到回退判定就返回 null 让原逻辑跑）。
 * **不扩窗口**：窗口策略是 captain 要拍板的事，不是注入流的默认动作。
 *
 * <h2>为什么"检测"只能是启发式（诚实说明，写给 captain）</h2>
 * 要**判定**"这次原生结果与全视野原版一致"，需要知道原生搜索的**访问集合**有没有落到窗口外。
 * 冻结的 ABI（{@code cava_abi.h}，14 结构体 / {@code layout_hash_sum=0x1C12265E}）**不导出**这个信息：
 * {@code CavaPathNode.flags} 恒为 0（原生侧注释："CAVA_PATH_NODE_* 当前没有定义任何位"），
 * 内核算出来的 {@code SolveResult.expanded_count} 也没有出口。因此：
 * <ul>
 *   <li><b>能 sound 判定的</b>：返回路径的节点**贴到窗口边界**（说明路径被窗口推着走）；
 *       目标点的"抵达球"（曼哈顿半径 {@code reachRange}）**没有完整落在窗口内**（目标测试本身被截断）。</li>
 *   <li><b>只能启发式判定的</b>："原生没抵达目标"这件事。原版没抵达 ⟺ 末节点与目标的曼哈顿距离
 *       {@code > reachRange}（本机 {@code javap -p -c} 复核：{@code found} 集合非空 ⟺ 存在被弹出的节点
 *       满足 {@code getManhattanDistance(t) <= (float)reachRange}；返回路径的末节点就是
 *       {@code TargetPathNode.getNearestNode()}，所以"末节点在半径内" ⟺ 原版走 found 分支）。
 *       但"没抵达"既可能是窗口截断，也可能是预算耗尽（此时原版也会没抵达，例如 maze63：
 *       两腿都是 {@code nodes=503 manh=22}，逐字段一致）⇒ 不能一律回退，否则把已经证明一致的
 *       场景也拖回 Java（实测 maze63 回退后 on 腿 = 原生 576µs + Java 1813µs ≈ 4 倍于接管路径）。</li>
 * </ul>
 *
 * <h2>三条判据（分别计数）</h2>
 * <ol>
 *   <li>{@code window-shell}：任一节点落在窗口边界面上（x/z 面；y 面只在**该边界不是世界高度**
 *       时才判——窗口 Y 会被 {@code world.getBottomY()/getTopY()} 裁剪，贴世界边界是合法的）。</li>
 *   <li>{@code goal-shell}：{@code target ± reachRange} 没有完整落在窗口内（x/y/z 任一轴）。</li>
 *   <li>{@code not-reached}（= 判据 2/3 的合并实现，按策略细分）：
 *       末节点与目标曼哈顿距离 {@code > reachRange} 时，
 *       <ul>
 *         <li>{@code always} 策略：一律回退（最保守）；</li>
 *         <li><b>{@code early-stop}（默认）</b>：只有当"搜索**停得太早**"时才回退 ——
 *             路径步数 {@code rc - 1} 小于起点到目标的**欧氏直线距离** {@code d0}
 *             （每一步至少走 1 格 ⇒ {@code rc-1} 是本次搜索走过路程的下界），
 *             也就是说搜索连直线距离都没走完就停了 ⇒ 它的停止**很可能是视野边界造成的**，
 *             而不是"结构上真的过不去"（迷宫死胡同那种：实测 maze63 走了 503 步、
 *             {@code d0≈120} ⇒ 结构性的最近点，判据不触发，保持接管）；
 *             触发时按 {@code not-reached-early-stop} 计数并回退。</li>
 *         <li>不触发的那一档按 {@code not-reached-structural} **计数但不回退** ——
 *             回执里能直接看到"有多少次原生交回的是结构性最近点"。</li>
 *       </ul></li>
 * </ol>
 *
 * <p><b>已知残余风险（未验证，写进文档）</b>：{@code early-stop} 是启发式 ——
 * 若某次窗口截断发生在"搜索已经走过超过直线距离、但仍在窗口内绕"的场景（大迷宫里被切掉一角），
 * 本判据会漏检（假阴性）。要把它变成 sound，需要 ABI 导出访问集合规模/是否触边
 * （原生侧已有 {@code expanded_count}）—— **那是 captain 的 ABI 决策，不是本流的**。
 * 反过来，判据触发时的回退只会**多花**一次原生调用，不会改变可观测结果（回退 = 跑原版逻辑）。
 */
public final class WindowTruncationGuard {

    /** 判据原因（分别计数用）。 */
    public static final String REASON_SHELL = "window-shell";
    public static final String REASON_GOAL_SHELL = "goal-shell";
    public static final String REASON_NOT_REACHED = "not-reached";
    public static final String REASON_NOT_REACHED_EARLY = "not-reached-early-stop";
    public static final String REASON_NOT_REACHED_STRUCTURAL = "not-reached-structural";

    /** 判定结果；{@code reason} 为空 = 没意见（不回退、也不计数）。 */
    public record Verdict(boolean fallback, String reason) {
        /** 检测通过（原生结果可用）。 */
        public static final Verdict PASS = new Verdict(false, "");
    }

    private WindowTruncationGuard() {
    }

    /**
     * @param originX/Y/Z  本次求解所用镜像窗口的原点（{@code RegionSource.Pushed}）
     * @param worldMinY    世界最低方块 Y（{@code world.getBottomY()}）
     * @param worldMaxY    世界最高方块 Y（{@code world.getTopY() - 1}）
     * @param nodes        原生返回的节点序列（Path 顺序：起点 → 末节点）
     * @return 判定；{@code fallback()==true} ⇒ 调用方必须整体回退 Java
     */
    public static Verdict check(int originX, int originY, int originZ,
                                int dimX, int dimY, int dimZ,
                                int worldMinY, int worldMaxY,
                                List<NativeNodeCodec.Node> nodes,
                                int targetX, int targetY, int targetZ, int reachRange) {
        if (!PathfindSwitches.windowGuardEnabled()) {
            return Verdict.PASS;
        }
        if (nodes == null || nodes.isEmpty() || dimX <= 0 || dimY <= 0 || dimZ <= 0) {
            return Verdict.PASS;
        }
        final int maxX = originX + dimX - 1;
        final int maxY = originY + dimY - 1;
        final int maxZ = originZ + dimZ - 1;

        // (1) 路径贴到窗口边界面（含贴着边界的"最接近点"）
        for (NativeNodeCodec.Node n : nodes) {
            if (n.x() <= originX || n.x() >= maxX || n.z() <= originZ || n.z() >= maxZ) {
                return new Verdict(true, REASON_SHELL);
            }
            if ((n.y() <= originY && originY > worldMinY) || (n.y() >= maxY && maxY < worldMaxY)) {
                return new Verdict(true, REASON_SHELL);
            }
        }

        // (2) 目标"抵达球"没有完整落在窗口内 ⇒ 原版的抵达判定本身可能被截断
        if (targetX - reachRange < originX || targetX + reachRange > maxX
                || targetY - reachRange < originY || targetY + reachRange > maxY
                || targetZ - reachRange < originZ || targetZ + reachRange > maxZ) {
            return new Verdict(true, REASON_GOAL_SHELL);
        }

        // (3) 原生没抵达目标
        NativeNodeCodec.Node last = nodes.get(nodes.size() - 1);
        int manh = Math.abs(last.x() - targetX) + Math.abs(last.y() - targetY) + Math.abs(last.z() - targetZ);
        if (manh <= reachRange) {
            return Verdict.PASS;   // 抵达（原版会走 found 分支；路径本身另有 shell 判据把关）
        }
        String policy = PathfindSwitches.windowNotReachedPolicy();
        if ("off".equals(policy)) {
            return Verdict.PASS;
        }
        if ("always".equals(policy)) {
            return new Verdict(true, REASON_NOT_REACHED);
        }
        // early-stop（默认）：走的路程下界 < 起点→目标的欧氏直线距离 ⇒ 停得太早
        NativeNodeCodec.Node first = nodes.get(0);
        double dx = (double) targetX - (double) first.x();
        double dy = (double) targetY - (double) first.y();
        double dz = (double) targetZ - (double) first.z();
        double straight = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int stepsLowerBound = nodes.size() - 1;
        if ((double) stepsLowerBound < straight) {
            return new Verdict(true, REASON_NOT_REACHED_EARLY);
        }
        return new Verdict(false, REASON_NOT_REACHED_STRUCTURAL);
    }

    /** 判据清单（回执表头用；作为单个 {@code key=value,...} 记号，回调执解析器要求无空格）。 */
    public static String describe() {
        return "windowGuard=" + (PathfindSwitches.windowGuardEnabled() ? "on" : "off")
                + ",notReached=" + PathfindSwitches.windowNotReachedPolicy();
    }
}
