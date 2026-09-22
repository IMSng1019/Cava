package cava.mirror.probe;

import cava.mirror.BlockStateTable;
import cava.mirror.McStateProbe;
import cava.mirror.MirrorFlags;
import cava.mirror.PathTypes;
import cava.mirror.StateProbe;
import cava.mirror.StateSample;
import cava.mirror.StateTableBuilder;
import cava.mirror.StateTableData;
import cava.mirror.StateTableStats;
import cava.ffm.CavaNative;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.registry.Registries;

/**
 * 独立探针主体：**由加宽后的类加载器加载**，所以可以直接写 MC 类型。
 *
 * <p>为什么不用 JUnit 直接跑：普通测试 JVM 没有 Fabric 的 access widener，
 * {@code Bootstrap.initialize()} 会因 {@code RegistryEntry$Reference.setRegistryKey} 是包私有而
 * {@code IllegalAccessError}（已实测）。这里用 {@link WideningLauncher} 把 {@code net.minecraft.**}
 * 的成员全部加宽成 public 来绕过 —— **只在探针里用，不影响产品代码**。
 *
 * <p>跑法见 {@code docs/CAVA-mirror-notes.md} §5。
 */
public final class McProbeMain {

    public static void main(String[] args) {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        System.out.println("[probe] MC 注册表 bootstrap 成功");

        int airId = Block.getRawIdFromState(Blocks.AIR.getDefaultState());
        int caveAir = Block.getRawIdFromState(Blocks.CAVE_AIR.getDefaultState());
        int voidAir = Block.getRawIdFromState(Blocks.VOID_AIR.getDefaultState());
        System.out.println("[probe] 真实状态数 = " + Block.STATE_IDS.size());
        System.out.println("[probe] getRawIdFromState(AIR) = " + airId + " (CAVE_AIR=" + caveAir + ", VOID_AIR=" + voidAir + ")");

        // ---- 真实状态表：构建 + 上传 ----
        long t0 = System.nanoTime();
        boolean built = BlockStateTable.get().build();
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[probe] build=" + built + " wall=" + buildMs + " ms");
        StateTableStats stats = BlockStateTable.get().stats();
        if (stats != null) {
            System.out.println("[probe] records=" + stats.stateCount + " boxes=" + stats.boxTotal
                    + " noBoxStates=" + stats.statesWithoutBoxes + " maxBoxPerState=" + stats.maxBoxesPerState);
            System.out.println("[probe] buildNanos=" + stats.buildNanos + " (" + (stats.buildNanos / 1_000_000.0) + " ms)");
            System.out.println("[probe] 内存估算 records=" + (stats.stateCount * 20L / 1024) + " KiB boxes="
                    + (stats.boxTotal * 24L / 1024) + " KiB");
        }
        CavaNative.get().configure(Path.of(System.getProperty("user.dir", ".")), "probe");
        boolean opened = CavaNative.get().tryOpen();
        System.out.println("[probe] native opened=" + opened + " status=" + CavaNative.get().status()
                + " handle=" + CavaNative.get().handle());
        long t1 = System.nanoTime();
        boolean uploaded = BlockStateTable.get().uploadIfNeeded();
        long upWall = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("[probe] upload=" + uploaded + " wall=" + upWall + " ms ffm="
                + (stats == null ? -1 : stats.uploadNanos / 1_000_000.0) + " ms failure=" + BlockStateTable.get().failure());

        // ---- isFlagsReadyFor：真实表下对多种 caps 必须**真的**返回 true ----
        int[] capsSamples = {0, cava.mirror.NavCaps.CAN_OPEN_DOORS,
                cava.mirror.NavCaps.AMPHIBIOUS | cava.mirror.NavCaps.PENALIZE_DEEP_WATER,
                cava.mirror.NavCaps.CAN_WALK_OVER_FENCES, cava.mirror.NavCaps.KNOWN_MASK, 0x7FFFFFFF};
        cava.mirror.RegionMirror flagsMirror = cava.mirror.RegionMirror.create();
        for (int caps : capsSamples) {
            boolean ok = flagsMirror.isFlagsReadyFor(caps);
            System.out.println("[probe] isFlagsReadyFor(0x" + Integer.toHexString(caps) + ") = " + ok
                    + (ok ? "" : "  原因: " + flagsMirror.flagsNotReadyReason()));
        }
        System.out.println("[probe] 状态表自检不一致条数 = " + cava.mirror.BlockStateTable.get().selfCheckMismatches());

        // ---- flags 推类型 vs 原版直接算 ----
        McStateProbe probe = new McStateProbe();
        StateSample sample = new StateSample();
        List<String> mismatches = new ArrayList<>();
        for (int id = 0; id < probe.stateCount(); id++) {
            sample.reset();
            probe.probe(id, sample);
            int derived = MirrorFlags.commonNodeType(sample.flags);
            if (derived != sample.commonType && mismatches.size() < 12) {
                mismatches.add("id=" + id + " " + Block.getStateFromRawId(id).getBlock() + " flags="
                        + MirrorFlags.names(sample.flags) + " 位推=" + PathTypes.name(derived) + " 原版=" 
                        + PathTypes.name(sample.commonType));
            }
        }
        System.out.println("[probe] flags→commonNodeType 逐状态对拍：共 " + probe.stateCount() + " 条，不一致 "
                + mismatches.size());
        for (String m : mismatches) {
            System.out.println("[probe]   " + m);
        }

        // ---- 序号表 vs 真实枚举 ----
        PathNodeType[] values = PathNodeType.values();
        int ordinalProblems = 0;
        for (int i = 0; i < values.length; i++) {
            if (!values[i].name().equals(PathTypes.name(i)) || values[i].getDefaultPenalty() != PathTypes.defaultPenalty(i)) {
                ordinalProblems++;
                System.out.println("[probe]   序号/惩罚不一致 ordinal=" + i + " 原版=" + values[i].name() + "/"
                        + values[i].getDefaultPenalty() + " 表=" + PathTypes.name(i) + "/" + PathTypes.defaultPenalty(i));
            }
        }
        System.out.println("[probe] PathNodeType 对拍：values=" + values.length + " 问题=" + ordinalProblems);

        // ---- 位置依赖形状普查 ----
        System.out.println("[probe] " + BlockStateTable.census().replace('\n', '|'));

        // ---- 覆写"带 pos/ShapeContext"的方块类 ----
        Set<String> pathfind = new TreeSet<>();
        Set<String> shape4 = new TreeSet<>();
        Set<String> shape3 = new TreeSet<>();
        int blocks = 0;
        for (Block b : Registries.BLOCK) {
            blocks++;
            for (Method m : b.getClass().getDeclaredMethods()) {
                if (m.getName().equals("canPathfindThrough") && m.getParameterCount() == 3) {
                    pathfind.add(b.getClass().getSimpleName());
                }
                if (m.getName().equals("getCollisionShape") && m.getParameterCount() == 4) {
                    shape4.add(b.getClass().getSimpleName());
                }
                if (m.getName().equals("getCollisionShape") && m.getParameterCount() == 3) {
                    shape3.add(b.getClass().getSimpleName());
                }
            }
        }
        System.out.println("[probe] 方块数=" + blocks);
        System.out.println("[probe] 覆写 canPathfindThrough(BlockView,BlockPos,NavigationType)=" + pathfind.size() + " " + pathfind);
        System.out.println("[probe] 覆写 getCollisionShape(BlockState,BlockView,BlockPos,ShapeContext)=" + shape4.size() + " " + shape4);
        System.out.println("[probe] 覆写 getCollisionShape(BlockState,BlockView,BlockPos)=" + shape3.size() + " " + shape3);

        // ---- 碰撞盒到底会不会随邻居/上下文变？（守卫范围的关键证据）----
        net.minecraft.block.Block[] probeBlocks = {
                net.minecraft.block.Blocks.OAK_FENCE, net.minecraft.block.Blocks.COBBLESTONE_WALL,
                net.minecraft.block.Blocks.IRON_BARS, net.minecraft.block.Blocks.SCAFFOLDING,
                net.minecraft.block.Blocks.STONE, net.minecraft.block.Blocks.WATER,
                net.minecraft.block.Blocks.OAK_FENCE_GATE, net.minecraft.block.Blocks.OAK_STAIRS};
        for (net.minecraft.block.Block b : probeBlocks) {
            System.out.println("[probe] shapes " + net.minecraft.registry.Registries.BLOCK.getId(b).getPath()
                    + ": " + cava.mirror.McStateProbe.debugShapes(b.getDefaultState()));
        }

        // ---- getRawIdFromState 单次成本 ----
        BlockState[] states = new BlockState[1024];
        int n = Math.min(states.length, Block.STATE_IDS.size());
        for (int i = 0; i < n; i++) {
            states[i] = Block.getStateFromRawId(i);
        }
        long best = Long.MAX_VALUE;
        long sink = 0;
        for (int rep = 0; rep < 500; rep++) {
            long a = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink += Block.getRawIdFromState(states[i]);
            }
            long dt = System.nanoTime() - a;
            if (dt < best) {
                best = dt;
            }
        }
        System.out.println("[probe] getRawIdFromState: " + best + " ns / " + n + " = "
                + String.format("%.2f", best / (double) n) + " ns/次 (sink=" + sink + ")");

        // 备选：本地 IdentityHashMap 缓存（palette 会让同状态反复出现）
        java.util.IdentityHashMap<BlockState, Integer> cache = new java.util.IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            cache.put(states[i], i);
        }
        long best2 = Long.MAX_VALUE;
        long sink2 = 0;
        for (int rep = 0; rep < 500; rep++) {
            long a = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Integer v = cache.get(states[i]);
                sink2 += v == null ? -1 : v;
            }
            long dt = System.nanoTime() - a;
            if (dt < best2) {
                best2 = dt;
            }
        }
        System.out.println("[probe] IdentityHashMap 缓存命中: " + best2 + " ns / " + n + " = "
                + String.format("%.2f", best2 / (double) n) + " ns/次 (sink=" + sink2 + ")");

        // ---- 逐位台账 ----
        if (stats != null) {
            System.out.println("[probe] 逐位命中状态数:");
            for (MirrorFlags.Pred p : MirrorFlags.Pred.values()) {
                System.out.println("[probe]   " + p.name() + " = " + stats.predCounts[p.ordinal()]);
            }
        }

        // ---- 真实世界口径的推送成本（每方块都走一次 getRawIdFromState）----
        cava.mirror.RegionReader realistic = new cava.mirror.RegionReader() {
            @Override
            public String dimensionId() {
                return "probe:overworld";
            }

            @Override
            public int minY() {
                return -64;
            }

            @Override
            public int maxY() {
                return 319;
            }

            @Override
            public long currentTick() {
                return 1;
            }

            @Override
            public boolean isReady(int minX, int minY, int minZ, int dimX, int dimY, int dimZ) {
                return true;
            }

            @Override
            public void fill(int minX, int minY, int minZ, int dimX, int dimY, int dimZ, int[] out, int airStateId) {
                int i = 0;
                for (int y = 0; y < dimY; y++) {
                    for (int z = 0; z < dimZ; z++) {
                        for (int x = 0; x < dimX; x++) {
                            out[i++] = Block.getRawIdFromState(Block.getStateFromRawId((x * 31 + y * 7 + z) % 4096));
                        }
                    }
                }
            }
        };
        cava.mirror.RegionMirror realMirror = new cava.mirror.RegionMirror(
                realistic, new cava.mirror.NativeRegionUploader(), BlockStateTable.get());
        int[][] wins = {{19, 14, 13}, {49, 12, 9}, {64, 40, 64}};
        for (int[] w : wins) {
            cava.mirror.RegionRect rect = new cava.mirror.RegionRect(0, 50, 0, w[0], w[1], w[2]);
            best = Long.MAX_VALUE;
            for (int round = 0; round < 4; round++) {
                long t = System.nanoTime();
                for (int i = 0; i < 200; i++) {
                    realMirror.push(rect);
                }
                long dt = (System.nanoTime() - t) / 200;
                if (dt < best) {
                    best = dt;
                }
            }
            System.out.println("[probe] 真实口径推送 " + w[0] + "x" + w[1] + "x" + w[2] + " cells=" + rect.volume()
                    + " = " + String.format("%.2f", best / 1000.0) + " us/次（" 
                    + String.format("%.3f", (double) best / rect.volume()) + " ns/方块）");
        }
        System.out.println("[probe] DONE");
    }

    private McProbeMain() {
    }
}
