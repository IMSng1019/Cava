package cava.hook;

import java.util.List;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个**可复现的合成寻路场景**：金丝雀探测与性能 bench 共用同一套参数。
 *
 * <p>为什么要有它：金丝雀"主动触发一次目标方法"必须真的走一遍原版入口
 * （{@code PathNodeNavigator.findPathToAny}），而不是直接调 {@code hook.hit()}——
 * 后者只能证明计数器会加，不能证明注入生效。这里的场景在**真实世界 + 真实生物**上
 * 构造一个真实的导航请求，因此它经过的代码路径与生物 AI 发起的一次寻路完全相同。
 *
 * <p>参数固定（{@link #NAV_RANGE} / {@link #MAX_RANGE} / {@link #REACH_RANGE} / {@link #FOLLOW_RANGE}），
 * 所以 bench 的两个腿（native on / off）在天真意义上是同一条路径。
 */
public final class PathfindScenario {

    /** {@code PathNodeNavigator} 构造器的 range（决定节点预算 = range * followRange）。 */
    public static final int NAV_RANGE = 8;
    /** 终点可接受范围。 */
    public static final int REACH_RANGE = 1;
    /** 原版 maxRange。 */
    public static final float MAX_RANGE = 16.0f;
    /** 原版 followRange。 */
    public static final float FOLLOW_RANGE = 16.0f;
    /** 起点与终点的水平距离（方块）。 */
    public static final int TARGET_DX = 6;

    private static final Logger LOG = LoggerFactory.getLogger("cava/pathfind");

    private PathfindScenario() {
    }

    /** 造一个与生物 AI 用的同级 {@code ChunkCache}（形状照抄 {@code PathFinder}）。 */
    public static ChunkCache newCache(ServerWorld world, BlockPos start) {
        int i = (int) (FOLLOW_RANGE + 8.0f);
        BlockPos min = new BlockPos(start.getX() - i, world.getBottomY(), start.getZ() - i);
        BlockPos max = new BlockPos(start.getX() + i, world.getTopY() - 1, start.getZ() + i);
        return new ChunkCache(world, min, max);
    }

    /** 终点：起点水平 {@link #TARGET_DX} 格、同高（贴地由原版 getStart/终点判定自己处理）。 */
    public static BlockPos target(BlockPos start) {
        return start.add(TARGET_DX, 0, 0);
    }

    /**
     * 触发一次真实的 {@code findPathToAny}。
     *
     * @return 原版返回的 Path（可能为 null，那也是合法结果）
     */
    public static Path invoke(ServerWorld world, MobEntity mob) {
        BlockPos start = mob.getBlockPos();
        LandPathNodeMaker maker = new LandPathNodeMaker();
        PathNodeNavigator navigator = new PathNodeNavigator(maker, NAV_RANGE);
        ChunkCache cache = newCache(world, start);
        return navigator.findPathToAny(cache, mob, Set.of(target(start)), MAX_RANGE, REACH_RANGE, FOLLOW_RANGE);
    }

    /**
     * 找一个已加载的生物；没有就**造一个并返回 created=true**（调用方用完负责 {@code discard()}）。
     *
     * @return 长度 2 的数组：[0] = MobEntity 或 null，[1] = 是否由本方法创建（Boolean）
     */
    public static Object[] findOrCreateMob(ServerWorld world) {
        BlockPos spawn = world.getSpawnPos();
        Box box = new Box(spawn).expand(96.0);
        List<Entity> nearby = world.getOtherEntities(null, box, e -> e instanceof MobEntity);
        for (Entity e : nearby) {
            if (e instanceof MobEntity m && m.isAlive()) {
                return new Object[]{m, Boolean.FALSE};
            }
        }
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
        MobEntity mob = EntityType.PIG.create(world);
        if (mob == null) {
            return new Object[]{null, Boolean.FALSE};
        }
        mob.refreshPositionAndAngles(spawn.getX() + 0.5, topY, spawn.getZ() + 0.5, 0.0f, 0.0f);
        if (!world.spawnEntity(mob)) {
            LOG.warn("[cava/pathfind] 金丝雀探测：合成生物 spawnEntity 失败");
            return new Object[]{null, Boolean.FALSE};
        }
        // 注意：**不要**在探测前 discard —— 调用方在 finally 里 discard，
        // 这样生物在整个探测期间都是"活的"，最接近真实 AI 发起寻路的上下文。
        return new Object[]{mob, Boolean.TRUE};
    }
}
