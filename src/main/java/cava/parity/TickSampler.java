package cava.parity;

import cava.ffm.CavaNative;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 黄金轨迹的运行时采集侧（契约 4.2 / 任务 C8）。**能进生产 jar**。
 *
 * <p>开关（系统属性，全部来自契约 4.1，外加本流新增的三个"排除范围"开关）：
 * <pre>
 * -Dcava.parity.trace=&lt;dir&gt;            非空 = 采集并落盘到 &lt;dir&gt;/trace-&lt;label&gt;.ndjson
 * -Dcava.parity.ticks=N               &gt;0 = 采满 N tick 自动停服（server.stop(false)）
 * -Dcava.parity.label=...             运行标签（写进头行 + 文件名）
 * -Dcava.parity.world.radius=R        世界哈希扫描半径（区块），默认 8；0 = 只扫中心区块
 * -Dcava.parity.exclude.spawn.radius=E 世界哈希**排除**出生点方形半径（区块），默认 3
 * -Dcava.parity.entities=on|off       e 字段是否进入比对（默认 off = 契约 4.2 的"默认排除 entities"）
 * -Dcava.parity.detail=true           额外落盘 detail-&lt;label&gt;.ndjson（逐实体哈希 + 区块增量哈希）
 * </pre>
 *
 * <p><b>w 的定义</b>（契约 4.2）：对**固定扫描盒**内的所有区块的方块状态按 (dim,x,y,z,stateId) 逐项 FNV-1a 64；
 * 用 state id 做键（禁止对象身份：FerriteCore 会把内容相同的 BlockState 去重成同一实例）。
 * 扫描盒 = 以原点为中心、半径 R 的区块方阵（含三个维度），**再减去出生点附近的 (2E+1)² 方形**——
 * 后者是 captain 的实测结论要求的（同一份代码两次运行，差异全部紧贴出生点，见
 * docs/CAVA-determinism-report.md §3），排除范围写进 trace 头保证可复现。
 *
 * <p><b>e 的定义</b>：扫描盒内每个实体 (类型注册名, id, x/y/z/motion/yaw/pitch 的 double/float **原始位模式**,
 * onGround)，按 (类型, x, y, z, id) 排序后逐项 FNV-1a。排序是为了**去掉枚举顺序的依赖**
 * （已加载集合的迭代顺序不是稳定序）。契约 4.2 要求默认排除它 —— 排除与否写进头行的 excl/incl。
 *
 * <p><b>p 的定义（与契约措辞的差异，必须知道）</b>：契约 4.2 写的是"本次 tick 内所有寻路调用的 …"。
 * 拦截"调用"需要一个注入点，而 {@code cava.mixin} / {@code cava.hook} **不归本流所有**（并行纪律）。
 * 所以这里采集的是**等价可观测量**：每 tick 结束时所有 mob 当前导航路径的
 * (实体键, 节点数, 逐节点 x/y/z/type) —— 同一场景下它由同一次 findPathToAny 的返回值决定，
 * 区别只是"调用了什么"vs"结果是什么"。头行的 {@code hash} 字段注明 {@code p=navstate}，
 * 任何报告都必须按这个定义读。真实"调用级"节点序列仍由单元层（10000 组向量）逐位覆盖。
 *
 * <p><b>bt / nt 一律写 null</b>：方块 tick / 邻居更新事件的计数**同样需要注入点**（TIS 的 microTiming 在专用
 * 服务端不落盘，见 docs/CAVA-parity-fixtures.md 的实测勘误）。红石层目前靠 w（红石功率是方块状态的一部分）
 * 覆盖，这一点在 docs/CAVA-parity-notes.md 里如实标注。
 */
public final class TickSampler {

    /** 轨迹输出目录。 */
    public static final String PROP_TRACE = "cava.parity.trace";
    /** 采满多少 tick 自动停服。 */
    public static final String PROP_TICKS = "cava.parity.ticks";
    /** 运行标签。 */
    public static final String PROP_LABEL = "cava.parity.label";
    /** 世界哈希扫描半径（区块）。 */
    public static final String PROP_WORLD_RADIUS = "cava.parity.world.radius";
    /** 世界哈希排除的出生点方形半径（区块）。 */
    public static final String PROP_EXCLUDE_SPAWN = "cava.parity.exclude.spawn.radius";
    /** e 字段是否参与比对（on/off）。 */
    public static final String PROP_ENTITIES = "cava.parity.entities";
    /** 是否额外落盘逐实体 / 逐区块明细。 */
    public static final String PROP_DETAIL = "cava.parity.detail";
    /**
     * 世界哈希覆盖哪些维度：{@code all}（契约默认）/ {@code overworld}（仅主世界）。
     *
     * <p>为什么要这个开关：契约 4.2 要求扫描盒"覆盖 3 个维度"，但实测本存档的 DIM-1/DIM1
     * **一个区块都没生成**（testbed/parity 的 nether/end 目录下没有 region 文件），
     * 于是第一 tick 会在两个维度里各强制生成 ~240 个区块 —— 又慢（分钟级），
     * 又可能被 c2me 的异步区块系统引入**生成时序**噪声。场景层的脚本化场景全部在主世界，
     * 所以场景层固定用 {@code overworld}，并把这件事写进 trace 头的 hash 字段（可复现）。
     */
    public static final String PROP_DIMS = "cava.parity.dims";

    private static final Logger LOG = LoggerFactory.getLogger("cava/parity");
    private static final TickSampler INSTANCE = new TickSampler();

    public static TickSampler get() {
        return INSTANCE;
    }

    private GoldenTrace trace;
    private GoldenTrace detail;
    private Path traceDir;
    private String label = "default";
    private long maxTicks;
    private int worldRadius = 8;
    private int excludeSpawnRadius = 3;
    private boolean entitiesIncluded;
    private boolean detailEnabled;
    private boolean overworldOnly;
    private long rows;
    private long startTick;
    private boolean stopRequested;
    private long blocksHashed;
    private long hashNanos;
    private long entitiesHashed;
    private long pathsHashed;

    /** 上一 tick 的 (dim<<32|chunkKey) -> 区块哈希，用于 detail 的区块增量。 */
    private final Map<Long, Long> lastChunkHashes = new HashMap<>();
    private final Map<Long, Long> currentChunkHashes = new HashMap<>();

    private TickSampler() {
    }

    /** 注册 Fabric 事件（在 Cava.onInitialize 里调用一次）。 */
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(INSTANCE::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(INSTANCE::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(INSTANCE::onEndTick);
    }

    /** 采集是否开启。 */
    public boolean active() {
        return trace != null;
    }

    private void onServerStarted(MinecraftServer server) {
        String dirProp = System.getProperty(PROP_TRACE, "").trim();
        if (dirProp.isEmpty()) {
            LOG.info("[cava/parity] 未开启轨迹采集（-D{}=<dir> 才开）", PROP_TRACE);
            return;
        }
        traceDir = Path.of(dirProp);
        label = System.getProperty(PROP_LABEL, "").trim();
        maxTicks = parseLong(System.getProperty(PROP_TICKS, "0"), 0);
        worldRadius = (int) parseLong(System.getProperty(PROP_WORLD_RADIUS, "8"), 8);
        excludeSpawnRadius = (int) parseLong(System.getProperty(PROP_EXCLUDE_SPAWN, "3"), 3);
        entitiesIncluded = switch (System.getProperty(PROP_ENTITIES, "off").trim().toLowerCase()) {
            case "on", "true", "1", "yes" -> true;
            default -> false;
        };
        detailEnabled = parseBool(System.getProperty(PROP_DETAIL, "false"));
        overworldOnly = "overworld".equalsIgnoreCase(System.getProperty(PROP_DIMS, "all").trim());
        if (label.isEmpty()) {
            label = CavaNative.get().available() ? "native" : "java";
        }
        startTick = server.getTicks();
        long seed = server.getSaveProperties().getGeneratorOptions().getSeed();
        String modsFingerprint = modsFingerprint();
        String mcVersion = modVersion("minecraft");
        String cavaVersion = modVersion("cava");
        GoldenTrace.Meta meta = new GoldenTrace.Meta(
                worldRadius,
                excludeSpawnRadius,
                entitiesIncluded ? "" : "entities",
                entitiesIncluded ? "entities" : "",
                "dims=" + (overworldOnly ? "overworld" : "all")
                        + ";w=blocks(dim,x,y,z,stateId);e=entity-pos-bits;p=navstate(x,y,z,type);bt=null;nt=null");
        try {
            trace = GoldenTrace.open(traceDir, label, modsFingerprint, mcVersion, cavaVersion, seed, startTick,
                    CavaNative.get().available(), meta);
            if (detailEnabled) {
                detail = GoldenTrace.openDetail(traceDir, label, meta);
            }
        } catch (IOException e) {
            LOG.error("[cava/parity] 打不开轨迹文件，采集关闭: {}", e.toString());
            trace = null;
            detail = null;
            return;
        }
        LOG.info("[cava/parity] 轨迹采集开启: {}  label={} native={} seed={} startTick={} maxTicks={} "
                        + "worldRadius={} excludeSpawnRadius={} entities={} detail={} dims={}",
                trace.path(), label, CavaNative.get().available(), seed, startTick, maxTicks,
                worldRadius, excludeSpawnRadius, entitiesIncluded ? "included" : "excluded", detailEnabled,
                overworldOnly ? "overworld" : "all");
        LOG.info("[cava/parity] modset 指纹 {} <- {} 个 mod", modsFingerprint,
                FabricLoader.getInstance().getAllMods().size());
    }

    private void onEndTick(MinecraftServer server) {
        GoldenTrace t = trace;
        if (t == null) {
            return;
        }
        try {
            long begin = System.nanoTime();
            String world = Fnv1a.hex16(worldHash(server));
            hashNanos += System.nanoTime() - begin;

            StringBuilder entDetail = null;
            String entity = null;
            StringBuilder pathDetail = null;
            String path = null;
            if (detailEnabled) {
                entDetail = new StringBuilder();
                pathDetail = new StringBuilder();
            }
            long eh = Fnv1a.begin();
            long ph = Fnv1a.begin();
            long entCount = 0;
            long pathCount = 0;
            for (ServerWorld w : server.getWorlds()) {
                if (overworldOnly && w.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
                    continue;
                }
                for (Entity e : collectEntities(w)) {
                    long h = entityHash(e);
                    eh = Fnv1a.updateString(eh, entityKey(e));
                    eh = Fnv1a.updateLong(eh, h);
                    entCount++;
                    entitiesHashed++;
                    if (entDetail != null) {
                        appendDetail(entDetail, entityKey(e), Fnv1a.hex16(h), posString(e));
                    }
                    if (e instanceof MobEntity mob) {
                        EntityNavigation nav = mob.getNavigation();
                        // 注意：MC 的 Path 与 java.nio.file.Path 同名，所以这里必须写全限定名
                        net.minecraft.entity.ai.pathing.Path cur = nav == null ? null : nav.getCurrentPath();
                        if (cur != null) {
                            long nh = pathHash(cur);
                            ph = Fnv1a.updateString(ph, entityKey(e));
                            ph = Fnv1a.updateLong(ph, nh);
                            pathCount++;
                            pathsHashed++;
                            if (pathDetail != null) {
                                appendDetail(pathDetail, entityKey(e), Fnv1a.hex16(nh), "nodes=" + cur.getLength());
                            }
                        }
                    }
                }
            }
            entity = entCount == 0 ? Fnv1a.hex16(Fnv1a.begin()) : Fnv1a.hex16(eh);
            path = pathCount == 0 ? Fnv1a.hex16(Fnv1a.begin()) : Fnv1a.hex16(ph);

            long tick = server.getTicks();
            String extra = "ent=" + entCount + ";paths=" + pathCount;
            t.tick(tick, world, entity, path, null, null, extra);
            if (detail != null) {
                detail.detailLine(tick, entDetail, pathDetail, chunkDelta(server));
            }
            rows++;
            if ((rows % 200) == 0) {
                t.flush();
                if (detail != null) {
                    detail.flush();
                }
                LOG.info("[cava/parity] 已采集 {} tick（世界哈希 {} 方块 / {} 实体 / {} 路径，均耗时 {} ms/tick）",
                        rows, blocksHashed, entitiesHashed, pathsHashed,
                        String.format("%.2f", hashNanos / 1_000_000.0 / rows));
            }
            if (maxTicks > 0 && rows >= maxTicks && !stopRequested) {
                stopRequested = true;
                t.flush();
                if (detail != null) {
                    detail.flush();
                }
                LOG.info("[cava/parity] 已采满 {} tick，停服（server.stop(false)）", maxTicks);
                // 注意：vanilla 里这个布尔是 waitForServer（不是「是否保存」）；确定性还需要 /save-off 与固定种子
                server.stop(false);
            }
        } catch (Throwable e) {
            LOG.error("[cava/parity] 采集 tick 失败，关闭采集（不影响服务器）", e);
            close();
        }
    }

    private void onServerStopping(MinecraftServer server) {
        close();
    }

    private void close() {
        GoldenTrace t = trace;
        trace = null;
        if (t != null) {
            t.close();
            LOG.info("[cava/parity] 轨迹落盘: {} （{} 行）", t.path(), t.lineCount());
        }
        GoldenTrace d = detail;
        detail = null;
        if (d != null) {
            d.close();
            LOG.info("[cava/parity] 明细落盘: {} （{} 行）", d.path(), d.lineCount());
        }
    }

    // ------------------------------------------------------------------
    // 实体采集
    // ------------------------------------------------------------------

    /** 扫描盒内的实体，按 (类型, x, y, z, id) 排序 —— 去掉"已加载集合迭代顺序"这个不稳定因素。 */
    private List<Entity> collectEntities(ServerWorld world) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : world.iterateEntities()) {
            if (inScanBox(world, e.getBlockX(), e.getBlockZ())) {
                out.add(e);
            }
        }
        out.sort((a, b) -> {
            int c = entityTypeName(a).compareTo(entityTypeName(b));
            if (c != 0) {
                return c;
            }
            c = Double.compare(a.getX(), b.getX());
            if (c != 0) {
                return c;
            }
            c = Double.compare(a.getY(), b.getY());
            if (c != 0) {
                return c;
            }
            c = Double.compare(a.getZ(), b.getZ());
            if (c != 0) {
                return c;
            }
            return Integer.compare(a.getId(), b.getId());
        });
        return out;
    }

    /** 是否落在扫描盒内（出生点方形排除对实体同样生效，保证两侧盒定义一致）。 */
    private boolean inScanBox(ServerWorld world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (Math.abs(cx) > worldRadius || Math.abs(cz) > worldRadius) {
            return false;
        }
        return excludeSpawnRadius <= 0 || Math.abs(cx) > excludeSpawnRadius || Math.abs(cz) > excludeSpawnRadius;
    }

    private static String entityTypeName(Entity e) {
        var id = Registries.ENTITY_TYPE.getId(e.getType());
        return id == null ? "unknown" : id.toString();
    }

    /** 明细用的实体键：类型#id（稳定、可 grep）。 */
    private static String entityKey(Entity e) {
        return entityTypeName(e) + "#" + e.getId();
    }

    private static String posString(Entity e) {
        return String.format("(%.2f,%.2f,%.2f)", e.getX(), e.getY(), e.getZ());
    }

    /** 实体哈希：位模式，禁止用数值（否则跨语言/跨实现不保真）。 */
    static long entityHash(Entity e) {
        long h = Fnv1a.begin();
        h = Fnv1a.updateString(h, entityTypeName(e));
        h = Fnv1a.updateInt(h, e.getId());
        h = Fnv1a.updateDouble(h, e.getX());
        h = Fnv1a.updateDouble(h, e.getY());
        h = Fnv1a.updateDouble(h, e.getZ());
        h = Fnv1a.updateDouble(h, e.getVelocity().x);
        h = Fnv1a.updateDouble(h, e.getVelocity().y);
        h = Fnv1a.updateDouble(h, e.getVelocity().z);
        h = Fnv1a.updateLong(h, Float.floatToRawIntBits(e.getYaw()) & 0xFFFFFFFFL);
        h = Fnv1a.updateLong(h, Float.floatToRawIntBits(e.getPitch()) & 0xFFFFFFFFL);
        h = Fnv1a.updateByte(h, e.isOnGround() ? 1 : 0);
        return h;
    }

    /** 导航路径哈希：节点数 + 逐节点 (x,y,z,type)。只读 PathNode 的公开字段（x/y/z/type）。 */
    static long pathHash(net.minecraft.entity.ai.pathing.Path path) {
        long h = Fnv1a.begin();
        int n = path.getLength();
        h = Fnv1a.updateInt(h, n);
        for (int i = 0; i < n; i++) {
            PathNode node = path.getNode(i);
            h = Fnv1a.updateInt(h, node.x);
            h = Fnv1a.updateInt(h, node.y);
            h = Fnv1a.updateInt(h, node.z);
            h = Fnv1a.updateInt(h, node.type == null ? -1 : node.type.ordinal());
        }
        return h;
    }

    private static void appendDetail(StringBuilder sb, String key, String hash, String note) {
        if (sb.length() > 0) {
            sb.append(',');
        }
        sb.append('[').append(GoldenTrace.json(key)).append(',').append(GoldenTrace.json(hash))
                .append(',').append(GoldenTrace.json(note)).append(']');
    }

    /** 本 tick 变了的区块（相对上一 tick）—— 差异定位用，正常时为空。 */
    private String chunkDelta(MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Long> e : currentChunkHashes.entrySet()) {
            Long prev = lastChunkHashes.get(e.getKey());
            if (prev == null || !prev.equals(e.getValue())) {
                long key = e.getKey();
                long dim = key >>> 32;
                int cxz = (int) (key & 0xFFFFFFFFL);
                int cx = cxz >> 16;
                int cz = (short) (cxz & 0xFFFF);
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append('[').append(GoldenTrace.json(dimName(dim) + ":" + cx + "," + cz)).append(',')
                        .append(GoldenTrace.json(Fnv1a.hex16(e.getValue()))).append(']');
            }
        }
        Map<Long, Long> swap = new HashMap<>(lastChunkHashes);
        lastChunkHashes.clear();
        lastChunkHashes.putAll(currentChunkHashes);
        currentChunkHashes.clear();
        // 上一 tick 之后被卸载的区块也算"变了"：它在 current 里消失，prev 里有
        for (Long k : swap.keySet()) {
            if (!lastChunkHashes.containsKey(k)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                long dim = k >>> 32;
                int cxz = (int) (k & 0xFFFFFFFFL);
                sb.append('[').append(GoldenTrace.json(dimName(dim) + ":" + (cxz >> 16) + "," + (short) (cxz & 0xFFFF) + " (unloaded)"))
                        .append(',').append(GoldenTrace.json("gone")).append(']');
            }
        }
        return sb.toString();
    }

    private static String dimName(long dimHash) {
        return Long.toHexString(dimHash);
    }

    // ------------------------------------------------------------------
    // 世界哈希
    // ------------------------------------------------------------------

    /**
     * 世界哈希 {@code w}：对 (dim, x, y, z, stateId) 逐项 FNV-1a 64（契约 4.2）。
     *
     * <p>迭代顺序 = (dim, chunkX, chunkZ, sectionIndex, x, y, z)，即按 (dim,x,y,z) 的字典序，
     * 与契约「按 (dim,x,y,z,stateId) 排序后逐项哈希」等价（同一坐标只有一个方块，
     * stateId 不参与排序比较）。
     *
     * <p>顺带为每个区块算一个独立哈希（写进 detail 的区块增量），成本 = 每方块多一次乘法。
     */
    public long worldHash(MinecraftServer server) {
        long h = Fnv1a.begin();
        for (ServerWorld world : server.getWorlds()) {
            if (overworldOnly && world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
                continue;
            }
            String dim = world.getRegistryKey().getValue().toString();
            long dimHash = Fnv1a.hashString(dim);
            int bottomSection = world.getBottomSectionCoord();
            for (int cx = -worldRadius; cx <= worldRadius; cx++) {
                for (int cz = -worldRadius; cz <= worldRadius; cz++) {
                    if (excludeSpawnRadius > 0 && Math.abs(cx) <= excludeSpawnRadius
                            && Math.abs(cz) <= excludeSpawnRadius) {
                        continue;   // 出生点附近：实测非确定，默认排除（范围写进 trace 头）
                    }
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    long chunkHash = Fnv1a.begin();
                    ChunkSection[] sections = chunk.getSectionArray();
                    for (int i = 0; i < sections.length; i++) {
                        ChunkSection section = sections[i];
                        // 空段 = 全空气：两次运行的空段集合一致，跳过不改变可比性，成本大降
                        if (section == null || section.isEmpty()) {
                            continue;
                        }
                        int y0 = (bottomSection + i) * 16;
                        for (int x = 0; x < 16; x++) {
                            int wx = cx * 16 + x;
                            for (int y = 0; y < 16; y++) {
                                int wy = y0 + y;
                                for (int z = 0; z < 16; z++) {
                                    int wz = cz * 16 + z;
                                    // 键 = 方块状态的 state id（禁止对象身份！）
                                    int stateId = Block.getRawIdFromState(section.getBlockState(x, y, z));
                                    chunkHash = Fnv1a.updateInt(chunkHash, stateId);
                                    h = Fnv1a.updateLong(h, dimHash);
                                    h = Fnv1a.updateInt(h, wx);
                                    h = Fnv1a.updateInt(h, wy);
                                    h = Fnv1a.updateInt(h, wz);
                                    h = Fnv1a.updateInt(h, stateId);
                                    blocksHashed++;
                                }
                            }
                        }
                    }
                    long key = (dimHash << 32) | (((long) cx & 0xFFFF) << 16) | ((long) cz & 0xFFFF);
                    currentChunkHashes.put(key, chunkHash);
                }
            }
        }
        return h;
    }

    /** modset 指纹：所有 mod 的 id@version 排序后 FNV-1a 64（hex16）。 */
    public static String modsFingerprint() {
        List<String> entries = new ArrayList<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            entries.add(c.getMetadata().getId() + "@" + c.getMetadata().getVersion().getFriendlyString());
        }
        entries.sort(String::compareTo);
        long h = Fnv1a.begin();
        for (String e : entries) {
            h = Fnv1a.updateString(h, e);
            h = Fnv1a.updateByte(h, '\n');
        }
        return Fnv1a.hex16(h);
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static long parseLong(String raw, long def) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static boolean parseBool(String raw) {
        String v = raw.trim().toLowerCase();
        return v.equals("true") || v.equals("on") || v.equals("1") || v.equals("yes");
    }
}
