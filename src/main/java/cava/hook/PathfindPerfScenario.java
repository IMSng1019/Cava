package cava.hook;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1-PERF：**大搜索空间**的脚本化合成寻路场景（长路径 / slalom / 迷宫 / 窗口边界探测）。
 *
 * <p><b>为什么另起一个类而不是改 {@link PathfindScenario}</b>：现成的 bench 场景（起点终点隔 6 格、
 * NAV_RANGE=8、followRange=16）实测只展开 **5 个节点** —— 在那个尺度上 native 的耗时主要是
 * 跨界固定成本，"更快/更慢"都没有代表性。本类把场景参数做成**显式的、可参数化的预设**，
 * 并把"搜索真的展开"变成**硬门禁**（{@code minNodes}，达不到就 ok=false 而不是给个好看的数字）。
 *
 * <p><b>地形怎么造</b>：预设里有地板范围、墙矩形、以及**字面量迷宫布局**（'#'/' ' 行）。
 * {@link #build} 幂等铺设（先铺地板、再清空上方 8 层、再按矩形/字面量放墙）。
 * 布局是源码里的常量，**没有任何随机数**：同一个预设在任何世界里铺出来都一样。
 *
 * <p><b>不是真实 AI 负载</b>：这是**合成负载**（直接构造 {@code ChunkCache} + {@code PathNodeNavigator}
 * 并调 {@code findPathToAny}），不代表本整合包的真实生物 AI 负载 —— 原因见
 * {@code docs/CAVA-pathfind-perf.md} 的"生物存活实测"一节。
 */
public final class PathfindPerfScenario {

    /** 所有预设共用的地板高度。 */
    public static final int FLOOR_Y = 70;
    /** 地板之上清空的层数（镜像窗口需要 floorY-7..floorY+4 干净）。 */
    public static final int CLEAR_HEIGHT = 8;
    /** 墙高（3 层石墙：猪跳不过、也爬不上）。 */
    public static final int WALL_HEIGHT = 3;
    /** bench 专用生物的 tag。 */
    public static final String PERF_TAG = "cava_perf";
    /**
     * **可证伪对照用的诊断开关**：把终点的 Z 坐标平移 N 格（默认 0）。
     * 只给"故意改一条腿的场景参数，diff 必须变红"这条对照用；正常测量不要设它。
     */
    public static final String PROP_TARGET_OFFSET = "cava.pathfind.perf.targetOffset";

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private PathfindPerfScenario() {
    }

    /** 一个轴对齐的墙矩形（含端点，方块坐标）。 */
    public record Rect(int x0, int z0, int x1, int z1) {
    }

    /**
     * 一个预设：地板范围 + 墙 + 起点/终点 + 求解参数。
     *
     * @param minNodes "搜索真的展开了"的下界（每次调用的平均节点数）；达不到 = 这个场景没测到东西
     */
    public record Preset(String name, int floorY,
                         int minX, int maxX, int minZ, int maxZ,
                         List<Rect> walls, String[] maze, int mazeX, int mazeZ,
                         int startX, int startZ, int targetX, int targetZ,
                         int navRange, int reachRange, float maxRange, float followRange,
                         int minNodes, String why) {

        /** 水平距离（起点→终点），方块）。 */
        public double horizontalDistance() {
            double dx = targetX - startX;
            double dz = targetZ - startZ;
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** 节点预算（= 原版 {@code (int)((float)range * followRange)}，oracle spec §4.1）。 */
        public int budget() {
            return (int) ((float) navRange * followRange);
        }

        public BlockPos start() {
            return new BlockPos(startX, floorY + 1, startZ);
        }

        public String describe() {
            return "preset=" + name + " floor=" + minX + ".." + maxX + "/" + minZ + ".." + maxZ
                    + " start=(" + startX + "," + (floorY + 1) + "," + startZ + ")"
                    + " target=(" + targetX + "," + (floorY + 1) + "," + targetZ + ")"
                    + " navRange=" + navRange + " followRange=" + followRange + " budget=" + budget()
                    + " maxRange=" + maxRange + " reachRange=" + reachRange
                    + " minNodes=" + minNodes + " why=" + why;
        }
    }

    // ------------------------------------------------------------------
    // 字面量迷宫布局（确定性生成后**冻结在源码里**，运行期零随机）
    // ------------------------------------------------------------------

    /** MAZE41：41x41 字面量布局（'#'=石墙，'.'=通道）；最短通路 200 个节点（BFS 实测）。 */
    private static final String[] MAZE41 = {
            "#########################################",
            "#...#.....#.................#...........#",
            "###.#.#.###.#########.#.###.#######.###.#",
            "#...#.#.#...#...#...#.#.#...........#...#",
            "#.#####.#.###.#.#.#.###.#######.#######.#",
            "#.....#.#...#.#...#.....#.....#.#.....#.#",
            "#####.#.###.#.#########.#.###.###.###.###",
            "#...#.#.....#.......#...#.#.#.......#...#",
            "#.###.#############.#####.#.###########.#",
            "#...#.....#...#...#.......#...#...#...#.#",
            "#.#.#####.#.#.#.#.#######.###.#.#.#.#.#.#",
            "#.#...#.#...#...#.......#.....#.#...#...#",
            "#.#.#.#.###############.#######.#######.#",
            "#.#.#.#.....#.........#.......#.#.....#.#",
            "###.#.#.#####.#.#####.#######.#.#.#####.#",
            "#...#.#.......#.#...#.......#.#.#...#...#",
            "#.###.#########.#.#.#####.###.#.###.#.###",
            "#.#.......#...#...#.#.....#...#...#.#...#",
            "#.#######.#.#.#####.#.#####.###.#.#.###.#",
            "#.......#...#.......#.....#.#...#.#...#.#",
            "#.#####.#################.#.#.###.#.#.#.#",
            "#.....#.......#...........#.#.#...#.#...#",
            "#####.#######.#####.#######.#.#.#########",
            "#...#.......#.....#.#...#...#.#.#...#...#",
            "#.#.#####.#######.###.#.#.###.#.#.#.#.#.#",
            "#.#...#...#...#...#...#...#...#...#...#.#",
            "#.#####.###.#.#.###.###################.#",
            "#.......#...#...#...#...#...#...#...#...#",
            "#########.###.###.###.#.#.#.#.#.#.#.#.#.#",
            "#.....#...#...#...#...#...#...#.#.#...#.#",
            "#.###.#.###.###.###.###########.#.#######",
            "#...#...#...#...#...#.......#...#.......#",
            "#.#.#####.###.###.###.###.###.###.#####.#",
            "#.#.#.....#...#...#...#...#...#.......#.#",
            "###.#######.###.###.###.###.###########.#",
            "#...#.......#.....#.#.....#.....#...#...#",
            "#.###.#######.###.#.###########.#.#.#.#.#",
            "#.#...#.....#.#...#...........#...#...#.#",
            "#.#.###.###.###.###.#####.#############.#",
            "#.......#.......#.......#...............#",
            "#########################################",
    };

    /** MAZE63：63x63 字面量布局；最短通路 604 个节点（BFS 实测）。 */
    private static final String[] MAZE63 = {
            "###############################################################",
            "#...#...........#...................#...................#...#.#",
            "###.###########.#.#################.#.#########.###.###.#.#.#.#",
            "#.#.......#...#...#...#...#...#...#...#.......#...#.#.#...#.#.#",
            "#.#######.#.#.#.###.#.#.#.#.#.#.#.#####.#####.#####.#.#####.#.#",
            "#...#...#...#.#.#...#...#...#...#.......#...#.......#.......#.#",
            "#.#.#.#.#####.###.#########################.#########.#######.#",
            "#.#...#.....#.....#...............#.......#.......#...#...#...#",
            "#.#########.###################.###.#.###.#.#.#####.###.#.#.#.#",
            "#...#...................#...#...#...#.#.#.#.#.#.....#...#.#.#.#",
            "#.#.#########.#########.#.#.#.###.###.#.#.###.#.#####.#.#.###.#",
            "#.#.#.....#...#.....#.#...#...#...#...#.......#.....#.#.#.....#",
            "#.#.#.###.#####.###.#.#######.#.###.#########.#####.#.#.#####.#",
            "#.#.....#.........#.#.........#.#.#.#...#...#...#...#.#...#.#.#",
            "#.#####.#########.#.#.#######.#.#.#.#.#.#.#.#####.###.###.#.#.#",
            "#.#...#...#.....#.#.#.#.....#.#...#...#...#.......#...#...#...#",
            "#.#.#.#####.###.###.#.#.###.#####.#####################.###.###",
            "#.#.#.......#.......#.#...#.#...#.......#...........#...#...#.#",
            "#.#.#################.###.#.#.#.#######.#.###.#####.#.###.###.#",
            "#.#...#...#...#.......#...#...#.......#.#.#...#...#...#...#...#",
            "#####.#.#.#.#.#####.###.###########.###.###.###.#######.###.#.#",
            "#...#.#.#...#.....#.#...#...#...#...#...#...#.#.......#.#...#.#",
            "#.#.#.#.#########.###.###.#.#.###.###.###.###.#.###.###.#####.#",
            "#.#...#.#.#...#...#...#...#...#...#...#...#.......#...#.......#",
            "#.#####.#.#.#.#.###.###.#.#####.###.###.#############.#######.#",
            "#.......#.#.#.#.#...#...#.....#.#...#...#...#...#...#...#...#.#",
            "#.#######.#.#.#.#.###########.#.#.###.###.#.#.#.#.#.#.#.#.###.#",
            "#...#...#.#.#...#.#...#...#...#...#.......#...#.#.#...#...#...#",
            "###.#.#.#.#.#####.#.#.#.#.#.#.###########.#####.#.#####.###.###",
            "#...#.#...#.....#...#...#...#.#.....#...#...#...#.#.#...#...#.#",
            "#.###.###.#####.#############.#.###.#.#.#.###.###.#.#.###.###.#",
            "#...#...#.#.......#...#...#...#.#.#...#.#.#...#...#...#...#...#",
            "###.###.###.#####.#.#.###.#.###.#.#####.###.###.###.###.###.#.#",
            "#...#...#...#...#...#.....#.#...#...#.#.....#...#...#...#...#.#",
            "#.###.###.###.###########.#.#.###.#.#.#######.#.#####.###.#####",
            "#.....#...#...#.......#...#...#...#.......#...#.......#...#...#",
            "#######.###.###.###.###.#######.#######.###.#############.#.#.#",
            "#.......#...#...#...#...#.....#.....#...#...#...#.......#...#.#",
            "#.#######.###.###.###.###.###.###.#.#.###.#####.#.###.#.#####.#",
            "#.....#...#...#...#...#...#.....#.#.#.#.#.#...#...#...#.......#",
            "#.###.#.###.###.###.###.#######.###.#.#.#.#.#.#####.#########.#",
            "#...#.#.#...#...#...#.......#.......#...#...#.....#.....#.....#",
            "#####.#.#.###.###.###.#####.###########.#########.#####.#######",
            "#.....#...#...#...#.......#...#.....#.#.....#...#.#...#.......#",
            "#.#######.#.###.###.#########.#.###.#.#####.#.#.#.#.#.#.#####.#",
            "#.#.....#.#.#...#...#.......#...#...#.........#.#...#.#.#...#.#",
            "#.#.###.###.#.#######.#####.#####.#############.#####.###.#.#.#",
            "#.#...#...#...........#...#.......#.......#...#...#...#...#.#.#",
            "#.#.#####.#########.###.#.#.#######.#####.#.#.#.###.###.###.#.#",
            "#...#.....#.....#...#...#.#.#.......#...#...#.#.#...#...#...#.#",
            "#.###.###.#.###.#####.###.###.#######.#.#####.###.###.###.###.#",
            "#.#...#...#...#.........#.........#...#.......#...#...#...#...#",
            "###.#########.#################.###.#######.###.###.###.#####.#",
            "#...#...........#...#...#...#...#...#...#...#...#...#.......#.#",
            "#.###.###########.#.#.#.#.#.#.###.###.###.###.###.#########.#.#",
            "#.....#.......#...#...#...#.#.#...#.#.....#...#...#...#...#.#.#",
            "#######.#####.#.#.#########.###.###.#.#####.#####.#.#.###.#.#.#",
            "#...#...#.......#.#...#.........#.........#.#...#...#...#.#...#",
            "#.#.#.###########.#.#.#######.#############.#.#.#####.#.#.###.#",
            "#.#...#.......#...#.#.#...#...#.......#...#...#.....#.#.#.#...#",
            "#.#####.#####.#####.#.#.#.#####.#####.#.#.#########.###.#.#.###",
            "#...........#.......#...#...........#...#...............#.....#",
            "###############################################################",
    };

    // ------------------------------------------------------------------
    // 预设
    // ------------------------------------------------------------------

    private static final List<Preset> PRESETS = List.of(
            new Preset("long128", FLOOR_Y, 24, 168, -48, 48,
                    List.of(), null, 0, 0, 32, 0, 160, 0,
                    8, 1, 384.0f, 192.0f, 50,
                    "长直路：水平 128 格、平坦地面、无遮挡（搜索展开最少的那种「长路径」）"),
            new Preset("long128hash", FLOOR_Y, 24, 168, -48, 48,
                    List.of(), null, 0, 0, 32, -32, 160, -32,
                    8, 1, 384.0f, 192.0f, 50,
                    "**故意的几何陷阱**：起点/终点在原版 PathNode.hash 下同键（32 与 160 在 z<0 时挤到同一位）"
                            + "⇒ 原版第 1 个节点就判『已抵达』。用来证明场景有效性门禁能报红，不是性能场景"),
            new Preset("detour128", FLOOR_Y, 24, 168, -48, 48,
                    List.of(new Rect(96, -48, 96, 24)), null, 0, 0, 32, 0, 160, 0,
                    32, 1, 512.0f, 192.0f, 50,
                    "窗口边界探测：唯一的通路缺口在 z=25..48，远超「起点终点包围盒 + 4」的镜像窗口"),
            new Preset("slalom", FLOOR_Y, 24, 168, -48, 48,
                    List.of(new Rect(80, -48, 80, 16), new Rect(112, -16, 112, 48)), null, 0, 0,
                    32, -32, 160, 32,
                    32, 1, 512.0f, 192.0f, 100,
                    "长路径 + 强制绕行：两道 33/65 格长的墙把最优路径推到 ~140 节点，且绕行仍在包围盒内"),
            new Preset("maze41", FLOOR_Y, 96, 136, 96, 136,
                    List.of(), MAZE41, 96, 96, 97, 97, 135, 135,
                    42, 1, 512.0f, 192.0f, 200,
                    "41x41 完美迷宫，对角穿越（最短通路 200 节点，唯一通路 ⇒ 必须真的搜索）"),
            new Preset("maze63", FLOOR_Y, 32, 94, -160, -98,
                    List.of(), MAZE63, 32, -160, 33, -159, 93, -99,
                    42, 1, 512.0f, 192.0f, 200,
                    "63x63 完美迷宫，对角穿越（最短通路 604 节点）"));

    public static List<Preset> presets() {
        return PRESETS;
    }

    public static Preset byName(String name) {
        for (Preset p : PRESETS) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public static String names() {
        StringBuilder sb = new StringBuilder();
        for (Preset p : PRESETS) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(p.name());
        }
        return sb.toString();
    }

    /** 终点（含诊断偏移 {@link #PROP_TARGET_OFFSET}）。 */
    public static BlockPos target(Preset p) {
        int off = 0;
        String raw = System.getProperty(PROP_TARGET_OFFSET);
        if (raw != null && !raw.isBlank()) {
            try {
                off = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                LOG.warn("[cava/pathfind] {}={} 不是整数 ⇒ 按 0 处理", PROP_TARGET_OFFSET, raw);
            }
        }
        return new BlockPos(p.targetX(), p.floorY() + 1, p.targetZ() + off);
    }

    /** 当前诊断偏移（0 = 正常）。 */
    public static int targetOffset() {
        String raw = System.getProperty(PROP_TARGET_OFFSET);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 铺场景（幂等）
    // ------------------------------------------------------------------

    /** 铺设结果。 */
    public record Built(int cells, int changed, long siteHash) {
    }

    /**
     * 幂等铺设一个预设：先地板、再清空上方 {@link #CLEAR_HEIGHT} 层、最后按矩形与字面量放墙。
     * 返回改了对方块数与**铺完之后的站点指纹**（同一预设在任何世界都得到同一个指纹）。
     */
    public static Built build(ServerWorld world, Preset p) {
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int cells = 0;
        int changed = 0;
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                cells++;
                pos.set(x, p.floorY(), z);
                if (!world.getBlockState(pos).isOf(Blocks.STONE)) {
                    world.setBlockState(pos, stone, Block.NOTIFY_LISTENERS);
                    changed++;
                }
                for (int dy = 1; dy <= CLEAR_HEIGHT; dy++) {
                    pos.set(x, p.floorY() + dy, z);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockState(pos, air, Block.NOTIFY_LISTENERS);
                        changed++;
                    }
                }
            }
        }
        for (Rect r : p.walls()) {
            for (int x = r.x0(); x <= r.x1(); x++) {
                for (int z = r.z0(); z <= r.z1(); z++) {
                    changed += wall(world, x, z, p.floorY());
                }
            }
        }
        if (p.maze() != null) {
            String[] rows = p.maze();
            for (int r = 0; r < rows.length; r++) {
                String row = rows[r];
                for (int c = 0; c < row.length(); c++) {
                    if (row.charAt(c) == '#') {
                        changed += wall(world, p.mazeX() + c, p.mazeZ() + r, p.floorY());
                    }
                }
            }
        }
        long hash = siteHash(world, p);
        return new Built(cells, changed, hash);
    }

    private static int wall(ServerWorld world, int x, int z, int floorY) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int changed = 0;
        for (int dy = 1; dy <= WALL_HEIGHT; dy++) {
            pos.set(x, floorY + dy, z);
            if (!world.getBlockState(pos).isOf(Blocks.STONE)) {
                world.setBlockState(pos, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                changed++;
            }
        }
        return changed;
    }

    /**
     * 站点指纹：把「地板 + 墙高 3 层」的 state id 按固定顺序做 FNV-1a 64。
     * 用途：把"测的到底是哪块地形"钉进回执 —— 地形被改过、数据包没铺上、预设改了，指纹都会变。
     */
    public static long siteHash(ServerWorld world, Preset p) {
        long h = 0xcbf29ce484222325L;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                for (int dy = 0; dy <= WALL_HEIGHT; dy++) {
                    pos.set(x, p.floorY() + dy, z);
                    h ^= Block.getRawIdFromState(world.getBlockState(pos));
                    h *= 0x100000001b3L;
                }
            }
        }
        return h;
    }

    /**
     * 站点自检：起点/终点脚下必须有地板、身位必须是空气；采样若干墙格必须真的有碰撞。
     *
     * @return null = 通过；否则是第一条问题的描述
     */
    public static String verifySite(ServerWorld world, Preset p, BlockPos target) {
        String bad = verifyCell(world, p.startX(), p.floorY(), p.startZ(), false);
        if (bad != null) {
            return "起点 " + bad;
        }
        bad = verifyCell(world, target.getX(), p.floorY(), target.getZ(), false);
        if (bad != null) {
            return "终点 " + bad;
        }
        // 采样墙格（矩形墙 + 迷宫墙各取前若干个）
        int checked = 0;
        for (Rect r : p.walls()) {
            for (int x = r.x0(); x <= r.x1() && checked < 32; x++) {
                for (int z = r.z0(); z <= r.z1() && checked < 32; z++) {
                    bad = verifyCell(world, x, p.floorY(), z, true);
                    if (bad != null) {
                        return "墙(" + x + "," + z + ") " + bad;
                    }
                    checked++;
                }
            }
        }
        if (p.maze() != null) {
            String[] rows = p.maze();
            for (int r = 0; r < rows.length && checked < 128; r++) {
                for (int c = 0; c < rows[r].length() && checked < 128; c++) {
                    if (rows[r].charAt(c) == '#') {
                        bad = verifyCell(world, p.mazeX() + c, p.mazeZ() + r, p.floorY(), true);
                        if (bad != null) {
                            return "迷宫墙(" + (p.mazeX() + c) + "," + (p.mazeZ() + r) + ") " + bad;
                        }
                        checked++;
                    }
                }
            }
        }
        return null;
    }

    /**
     * **全站点逐格核对**（场景有效性的真正门禁）：地板全实、通道层全空、墙格全实。
     *
     * <p>为什么不信 {@code build()} 的 {@code changed} 计数：那个数只说明"这次写了多少格"，
     * 而本整合包上实测存在**写入被盖回**的现象（c2me 的异步 chunk io；先读后写同一区块仍可能中招），
     * 所以只有"逐格读回来核对"才算证据。
     *
     * @return null = 完全符合预设；否则是第一条不一致
     */
    public static String verifySiteFull(ServerWorld world, Preset p) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                pos.set(x, p.floorY(), z);
                if (world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                    return "地板缺失 @(" + x + "," + p.floorY() + "," + z + ")";
                }
                for (int dy = 1; dy <= WALL_HEIGHT; dy++) {
                    pos.set(x, p.floorY() + dy, z);
                    boolean solid = !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
                    boolean want = isWallCell(p, x, z);
                    if (solid != want) {
                        return (want ? "墙缺失" : "通道被挡") + " @(" + x + "," + (p.floorY() + dy) + "," + z + ")";
                    }
                }
            }
        }
        return null;
    }

    private static boolean isWallCell(Preset p, int x, int z) {
        for (Rect r : p.walls()) {
            if (x >= r.x0() && x <= r.x1() && z >= r.z0() && z <= r.z1()) {
                return true;
            }
        }
        if (p.maze() != null) {
            int c = x - p.mazeX();
            int r = z - p.mazeZ();
            String[] rows = p.maze();
            if (r >= 0 && r < rows.length && c >= 0 && c < rows[r].length()) {
                return rows[r].charAt(c) == '#';
            }
        }
        return false;
    }

    private static String verifyCell(ServerWorld world, int x, int floorY, int z, boolean expectSolid) {
        BlockPos below = new BlockPos(x, floorY, z);
        BlockPos feet = new BlockPos(x, floorY + 1, z);
        boolean floorSolid = !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
        boolean feetFree = world.getBlockState(feet).getCollisionShape(world, feet).isEmpty();
        if (!floorSolid) {
            return "脚下无地板 @(" + x + "," + floorY + "," + z + ")";
        }
        if (expectSolid && feetFree) {
            return "墙格其实是空的 @(" + x + "," + (floorY + 1) + "," + z + ")";
        }
        if (!expectSolid && !feetFree) {
            return "身位被挡 @(" + x + "," + (floorY + 1) + "," + z + ")";
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 生物
    // ------------------------------------------------------------------

    /**
     * 找到（或造出）**恰好站在预设起点**的 bench 专用猪。
     *
     * <p>为什么不复用 {@code PathfindScenario.findOrCreateMob}：它接受"出生点 96 格内任意一只生物"，
     * 在真实服务端上可能是别的生物、站在别的地方 —— 那样量出来的不是这个预设。
     * 这里**只认带 {@link #PERF_TAG} 且正好站在起点的猪**，其余一律 discard。
     *
     * @return 生物；null = 造不出来（调用方必须判失败）
     */
    public static MobEntity realizeMob(ServerWorld world, Preset p) {
        BlockPos want = p.start();
        List<PigEntity> found = new ArrayList<>(
                world.getEntitiesByType(EntityType.PIG, e -> e.getCommandTags().contains(PERF_TAG)));
        PigEntity keep = null;
        for (PigEntity pig : found) {
            if (keep == null && pig.isAlive() && pig.getBlockPos().equals(want)) {
                keep = pig;
            } else {
                pig.discard();
            }
        }
        if (keep != null) {
            return keep;
        }
        PigEntity pig = EntityType.PIG.create(world);
        if (pig == null) {
            return null;
        }
        pig.refreshPositionAndAngles(p.startX() + 0.5, p.floorY() + 1, p.startZ() + 0.5, 0.0f, 0.0f);
        pig.setAiDisabled(true);
        pig.setSilent(true);
        pig.setPersistent();
        pig.setInvulnerable(true);
        pig.addCommandTag(PERF_TAG);
        if (!world.spawnEntity(pig)) {
            return null;
        }
        return pig;
    }

    // ------------------------------------------------------------------
    // 求解
    // ------------------------------------------------------------------

    /** 与生物 AI 同级形状的 {@code ChunkCache}（照抄 {@link PathfindScenario#newCache}）。 */
    public static ChunkCache newCache(ServerWorld world, BlockPos start, float followRange) {
        int i = (int) (followRange + 8.0f);
        BlockPos min = new BlockPos(start.getX() - i, world.getBottomY(), start.getZ() - i);
        BlockPos max = new BlockPos(start.getX() + i, world.getTopY() - 1, start.getZ() + i);
        return new ChunkCache(world, min, max);
    }

    /** 触发一次真实的 {@code findPathToAny}（与生物 AI 完全同一条入口）。 */
    public static Path invoke(ServerWorld world, MobEntity mob, Preset p, BlockPos target) {
        LandPathNodeMaker maker = new LandPathNodeMaker();
        PathNodeNavigator navigator = new PathNodeNavigator(maker, p.navRange());
        ChunkCache cache = newCache(world, mob.getBlockPos(), p.followRange());
        return navigator.findPathToAny(cache, mob, Set.of(target), p.maxRange(), p.reachRange(), p.followRange());
    }

    /** 造好一套求解器（供 bench 把 ChunkCache 构建与求解分开计时）。 */
    public static PathNodeNavigator newNavigator(Preset p) {
        return new PathNodeNavigator(new LandPathNodeMaker(), p.navRange());
    }

    /**
     * **环境指纹（诊断用，本流踩过坑）**：把"世界怎么读"与"ChunkCache 怎么读"并排打出来。
     *
     * <p>为什么必须有它：原版 {@code findPathToAny} 只通过 {@code ChunkCache} 看世界，而 {@code ChunkCache}
     * 对**未加载**的区块返回 {@code EmptyChunk}（= 空气，没有地板）⇒ 只要目标落在
     * {@code followRange + 8} 之外，原版就永远找不到路，返回一条 1 个节点的"最接近点"路径。
     * 那种情况下两条腿"一致"但**什么都没测到** —— 这个探针就是为了让这种情形一眼可见，
     * 而不是被当成"两腿一致"的结论。
     *
     * @return 形如 {@code world=stone/stone/stone cache=stone/air/stone cacheChunks=100/100} 的一行
     */
    public static String envProbe(ServerWorld world, ChunkCache cache, Preset p, BlockPos startPos, BlockPos target) {
        int midX = (startPos.getX() + target.getX()) / 2;
        int midZ = (startPos.getZ() + target.getZ()) / 2;
        BlockPos[] pts = {
                new BlockPos(startPos.getX() + 1, p.floorY(), startPos.getZ()),
                new BlockPos(midX, p.floorY(), midZ),
                new BlockPos(target.getX() - 1, p.floorY(), target.getZ()),
        };
        StringBuilder w = new StringBuilder();
        StringBuilder c = new StringBuilder();
        for (BlockPos pt : pts) {
            w.append(stateName(world.getBlockState(pt))).append('/');
            c.append(stateName(cache.getBlockState(pt))).append('/');
        }
        int i = (int) (p.followRange() + 8.0f);
        int cx0 = (startPos.getX() - i) >> 4;
        int cx1 = (startPos.getX() + i) >> 4;
        int cz0 = (startPos.getZ() - i) >> 4;
        int cz1 = (startPos.getZ() + i) >> 4;
        int loaded = 0;
        int total = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                total++;
                if (world.getChunkManager().isChunkLoaded(cx, cz)) {
                    loaded++;
                }
            }
        }
        return "world=[" + w + "] cache=[" + c + "] cacheChunks=" + loaded + "/" + total
                + " cacheSpan=[" + (startPos.getX() - i) + ".." + (startPos.getX() + i) + "]x["
                + (startPos.getZ() - i) + ".." + (startPos.getZ() + i) + "]";
    }

    /**
     * **场景有效性门禁**：ChunkCache 里能不能看见站点地板？
     *
     * <p>原版只看得到 {@code followRange + 8} 半径内的区块（外面一律 EmptyChunk = 空气）。
     * 只要目标或路径落在那个半径外，原版就会返回"1 个节点的最接近路径"，
     * 两条腿即使一致也**什么都没测到**。所以这里把"缓存能不能看见地板"作为硬条件：
     *
     * @return null = 看得见；否则是第一条不一致的描述
     */
    public static String cacheBlind(ServerWorld world, ChunkCache cache, Preset p, BlockPos startPos, BlockPos target) {
        int midX = (startPos.getX() + target.getX()) / 2;
        int midZ = (startPos.getZ() + target.getZ()) / 2;
        BlockPos[] pts = {
                new BlockPos(startPos.getX() + 1, p.floorY(), startPos.getZ()),
                new BlockPos(midX, p.floorY(), midZ),
                new BlockPos(target.getX() - 1, p.floorY(), target.getZ()),
        };
        for (BlockPos pt : pts) {
            boolean worldSolid = !world.getBlockState(pt).getCollisionShape(world, pt).isEmpty();
            boolean cacheSolid = !cache.getBlockState(pt).getCollisionShape(cache, pt).isEmpty();
            if (worldSolid && !cacheSolid) {
                return "ChunkCache 看不见地板 @" + pt.toShortString() + "（world=solid cache=air）";
            }
        }
        return null;
    }

    private static String stateName(net.minecraft.block.BlockState s) {
        if (s.isAir()) {
            return "air";
        }
        return s.getBlock().getTranslationKey().replace("block.minecraft.", "");
    }

    /** 原版 {@code getStart()} 的结果（类型 + 坐标），诊断用。 */
    public static String startNodeProbe(ServerWorld world, MobEntity mob, Preset p) {
        try {
            LandPathNodeMaker maker = new LandPathNodeMaker();
            maker.init(newCache(world, mob.getBlockPos(), p.followRange()), mob);
            net.minecraft.entity.ai.pathing.PathNode start = maker.getStart();
            if (start == null) {
                return "(null)";
            }
            return start.type + "@(" + start.x + "," + start.y + "," + start.z + ")";
        } catch (Throwable t) {
            return "(probe-failed:" + t.getClass().getSimpleName() + ")";
        }
    }
}
