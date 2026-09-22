package cava.parity;

import cava.ffm.CavaNative;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.block.Block;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 黄金轨迹的运行时采集侧（契约 4.2 / 任务 C8）。
 *
 * <p>开关（系统属性，全部来自契约 4.1）：
 * <pre>
 * -Dcava.parity.trace=&lt;dir&gt;   非空 = 采集并落盘到 &lt;dir&gt;/trace-&lt;label&gt;.ndjson
 * -Dcava.parity.ticks=N       &gt;0 = 采满 N tick 自动停服（server.stop(false)）
 * -Dcava.parity.label=...     运行标签（写进头行 + 文件名）
 * -Dcava.parity.world.radius=R 世界哈希扫描半径（区块），默认 8；0 = 只扫中心区块
 * </pre>
 *
 * <p><b>P0 范围</b>：只实现 {@code w}（世界哈希）。{@code e} / {@code p} / {@code bt} / {@code nt} / {@code x}
 * 一律写 {@code null}（契约要求「键必须都在」）。
 *
 * <p><b>世界哈希的两个已定决策</b>（都写进 docs/CAVA-java-notes.md）：
 * <ol>
 *   <li><b>用 state id 做键，禁止对象身份</b>：{@code Block.getRawIdFromState(state)} 是唯一允许的键。
 *       FerriteCore 会把内容相同的 BlockState 去重成同一实例，用 identity 做键会在装了 FerriteCore
 *       的整合包里产生假差异。</li>
 *   <li><b>扫描盒而不是「所有已加载区块」</b>：1.20.4 yarn 没有公开的『枚举已加载区块』API
 *       （{@code ServerChunkManager.threadedAnvilChunkStorage} 与 {@code ThreadedAnvilChunkStorage.loadedChunks}
 *       都是私有字段，P0 禁止 mixin）。而已加载集合本身会随玩家/加载时机抖动，会让两次运行产生假差异。
 *       因此 P0 用固定扫描盒（以 0,0 为中心、半径 R 的区块方阵，含三个维度），确定性最强。</li>
 * </ol>
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

    private static final Logger LOG = LoggerFactory.getLogger("cava/parity");
    private static final TickSampler INSTANCE = new TickSampler();

    public static TickSampler get() {
        return INSTANCE;
    }

    private GoldenTrace trace;
    private Path traceDir;
    private String label = "default";
    private long maxTicks;
    private int worldRadius = 8;
    private long rows;
    private long startTick;
    private boolean stopRequested;
    private long blocksHashed;
    private long hashNanos;

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
        if (label.isEmpty()) {
            label = CavaNative.get().available() ? "native" : "java";
        }
        startTick = server.getTicks();
        long seed = server.getSaveProperties().getGeneratorOptions().getSeed();
        String modsFingerprint = modsFingerprint();
        String mcVersion = modVersion("minecraft");
        String cavaVersion = modVersion("cava");
        try {
            trace = GoldenTrace.open(traceDir, label, modsFingerprint, mcVersion, cavaVersion, seed, startTick,
                    CavaNative.get().available());
        } catch (IOException e) {
            LOG.error("[cava/parity] 打不开轨迹文件，采集关闭: {}", e.toString());
            trace = null;
            return;
        }
        LOG.info("[cava/parity] 轨迹采集开启: {}  label={} native={} seed={} startTick={} maxTicks={} worldRadius={}",
                trace.path(), label, CavaNative.get().available(), seed, startTick, maxTicks, worldRadius);
        LOG.info("[cava/parity] modset 指纹 {} <- {} 个 mod", modsFingerprint, FabricLoader.getInstance().getAllMods().size());
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
            long tick = server.getTicks();
            t.tick(tick, world, null, null, null, null, null);
            rows++;
            if ((rows % 200) == 0) {
                t.flush();
                LOG.info("[cava/parity] 已采集 {} tick（世界哈希 {} 方块，均耗时 {} ms/tick）",
                        rows, blocksHashed, String.format("%.2f", hashNanos / 1_000_000.0 / rows));
            }
            if (maxTicks > 0 && rows >= maxTicks && !stopRequested) {
                stopRequested = true;
                t.flush();
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
    }

    // ------------------------------------------------------------------
    // 世界哈希
    // ------------------------------------------------------------------

    /**
     * 世界哈希 {@code w}：对 (dim, x, y, z, stateId) 逐项 FNV-1a 64。
     *
     * <p>迭代顺序 = (dim, chunkX, chunkZ, sectionIndex, x, y, z)，即按 (dim,x,y,z) 的字典序，
     * 与契约「按 (dim,x,y,z,stateId) 排序后逐项哈希」等价（同一坐标只有一个方块，
     * stateId 不参与排序比较）。
     */
    public long worldHash(MinecraftServer server) {
        long h = Fnv1a.begin();
        for (ServerWorld world : server.getWorlds()) {
            String dim = world.getRegistryKey().getValue().toString();
            long dimHash = Fnv1a.hashString(dim);
            int bottomSection = world.getBottomSectionCoord();
            for (int cx = -worldRadius; cx <= worldRadius; cx++) {
                for (int cz = -worldRadius; cz <= worldRadius; cz++) {
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
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
                                    // 键 = 方块状态的 state id（禁止对象身份！）
                                    int stateId = Block.getRawIdFromState(section.getBlockState(x, y, z));
                                    h = Fnv1a.updateLong(h, dimHash);
                                    h = Fnv1a.updateInt(h, wx);
                                    h = Fnv1a.updateInt(h, wy);
                                    h = Fnv1a.updateInt(h, cz * 16 + z);
                                    h = Fnv1a.updateInt(h, stateId);
                                    blocksHashed++;
                                }
                            }
                        }
                    }
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
}
