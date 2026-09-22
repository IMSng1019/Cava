package cava.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cava.ffm.CavaNative;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * **真实注册表**上的镜像侧验证（在进程内 bootstrap Minecraft，不需要起服务器）。
 *
 * <p>这是本轮唯一能拿到"真实状态数 / 真实 air id / 真实形状"的地方：
 * <ol>
 *   <li>契约点名的待验假设 {@code Block.getRawIdFromState(AIR) == 0}；</li>
 *   <li>真实状态数 + 真实状态表构建/上传耗时（验收要求"真实耗时与状态数"）；</li>
 *   <li>{@code MirrorFlags.commonNodeType(flags)} 与"直接按原版方块算出来的类型"**逐个状态**对拍
 *       —— 任何一位填错都会在这里红；</li>
 *   <li>序号表与真实 {@code PathNodeType.values()} 对拍；</li>
 *   <li>位置依赖形状普查（ABI 表达不了的部分的量化）；</li>
 *   <li>哪些方块类覆写了"带 pos / 带 ShapeContext"的形状与通行性方法（风险清单）。</li>
 * </ol>
 */
class McStateTableProbeTest {

    private static boolean booted;
    private static String bootFailure = "";

    @BeforeAll
    static void bootstrapMinecraft() {
        try {
            SharedConstants.createGameVersion();
            Bootstrap.initialize();
            booted = true;
        } catch (Throwable t) {
            bootFailure = t.getClass().getName() + ": " + t.getMessage();
            System.out.println("[mirror] MC bootstrap 失败: " + bootFailure);
            t.printStackTrace(System.out);
        }
        System.out.println("[mirror] MC bootstrap: booted=" + booted + " failure=" + bootFailure);
        Assumptions.assumeTrue(booted, "MC 注册表 bootstrap 失败（测试跳过，不算通过）: " + bootFailure);
    }

    @Test
    void airStateIdIsZeroMeasured() {
        int airId = Block.getRawIdFromState(Blocks.AIR.getDefaultState());
        int caveAir = Block.getRawIdFromState(Blocks.CAVE_AIR.getDefaultState());
        int voidAir = Block.getRawIdFromState(Blocks.VOID_AIR.getDefaultState());
        System.out.println("[mirror] 真实状态数 = " + Block.STATE_IDS.size());
        System.out.println("[mirror] Block.getRawIdFromState(AIR) = " + airId
                + "（CAVE_AIR=" + caveAir + ", VOID_AIR=" + voidAir + "）");
        System.out.println("[mirror] airStateIdIsZero = " + (airId == 0));
        assertEquals(0, airId, "契约假设：air 的 state id 必须是 0");
    }

    @Test
    void realStateTableBuildAndUpload() {
        long t0 = System.nanoTime();
        boolean built = BlockStateTable.get().build();
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(built, "构建状态表失败: " + BlockStateTable.get().failure());
        StateTableStats stats = BlockStateTable.get().stats();
        System.out.println("[mirror] 状态表构建（真实）：状态 " + stats.stateCount + " 条 / 碰撞盒 " + stats.boxTotal
                + " 个 / 无盒状态 " + stats.statesWithoutBoxes + " / 单状态最多 " + stats.maxBoxesPerState + " 个盒");
        System.out.println("[mirror] 构建耗时(含 JIT 未预热) = " + buildMs + " ms；"
                + "内部计时 = " + String.format("%.2f", stats.buildNanos / 1_000_000.0) + " ms");
        System.out.println("[mirror] 内存估算：records " + (stats.stateCount * 20L / 1024) + " KiB + boxes "
                + (stats.boxTotal * 24L / 1024) + " KiB");
        long t1 = System.nanoTime();
        boolean uploaded = BlockStateTable.get().uploadIfNeeded();
        long upMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("[mirror] 上传 " + (uploaded ? "成功" : "失败(" + BlockStateTable.get().failure() + ")")
                + "：wall=" + upMs + " ms，内部 FFM 计时 = "
                + String.format("%.3f", stats.uploadNanos / 1_000_000.0) + " ms"
                + "；原生可用=" + CavaNative.get().available());
        System.out.println("[mirror] " + stats.report().replace('\n', '|'));
    }

    @Test
    void flagsAgreeWithVanillaTypeForEveryState() {
        McStateProbe probe = new McStateProbe();
        StateSample sample = new StateSample();
        int checked = 0;
        List<String> mismatches = new ArrayList<>();
        for (int id = 0; id < probe.stateCount(); id++) {
            sample.reset();
            probe.probe(id, sample);
            int derived = MirrorFlags.commonNodeType(sample.flags);
            if (derived != sample.commonType) {
                if (mismatches.size() < 10) {
                    mismatches.add("id=" + id + " " + Block.getStateFromRawId(id).getBlock()
                            + " flags=" + MirrorFlags.names(sample.flags)
                            + " 位推=" + PathTypes.name(derived)
                            + " 原版算=" + PathTypes.name(sample.commonType));
                }
            }
            checked++;
        }
        System.out.println("[mirror] 逐状态对拍 flags→commonNodeType：共 " + checked + " 条，不一致 " + mismatches.size());
        for (String m : mismatches) {
            System.out.println("[mirror]   " + m);
        }
        assertEquals(0, mismatches.size(), "flags 推出来的类型必须与按原版方块直接算的一致");
    }

    @Test
    void ordinalsMatchRealEnum() {
        PathNodeType[] values = PathNodeType.values();
        assertEquals(PathTypes.COUNT, values.length);
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (!values[i].name().equals(PathTypes.name(i))) {
                problems.add("ordinal " + i + ": 原版 " + values[i].name() + " vs 表 " + PathTypes.name(i));
            }
            if (values[i].getDefaultPenalty() != PathTypes.defaultPenalty(i)) {
                problems.add("ordinal " + i + " (" + values[i].name() + "): 惩罚 " + values[i].getDefaultPenalty()
                        + " vs 表 " + PathTypes.defaultPenalty(i));
            }
        }
        System.out.println("[mirror] PathNodeType 序号/惩罚对拍：" + values.length + " 条，问题 " + problems.size());
        for (String p : problems) {
            System.out.println("[mirror]   " + p);
        }
        assertEquals(0, problems.size(), "序号表必须与真实枚举逐个一致");
    }

    @Test
    void positionDependentShapeCensus() {
        String census = BlockStateTable.census();
        System.out.println("[mirror] " + census.replace('\n', '|'));
        // 结论性断言：不管差多少，两张表的状态数必须一致（否则记录下标会错位）
        StateTableData empty = StateTableBuilder.build(new McStateProbe(ProbeWorldView.MODE_EMPTY)).data();
        StateTableData self = StateTableBuilder.build(new McStateProbe(ProbeWorldView.MODE_SELF)).data();
        assertEquals(empty.stateCount, self.stateCount);
    }

    @Test
    void contextSensitiveBlockClasses() throws Exception {
        Set<String> pathfind = new TreeSet<>();
        Set<String> shape4 = new TreeSet<>();
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
            }
        }
        System.out.println("[mirror] 注册方块数 = " + blocks);
        System.out.println("[mirror] 覆写 canPathfindThrough(BlockView,BlockPos,NavigationType) 的方块类 "
                + pathfind.size() + " 个: " + pathfind);
        System.out.println("[mirror] 覆写 getCollisionShape(BlockState,BlockView,BlockPos,ShapeContext) 的方块类 "
                + shape4.size() + " 个: " + shape4);
        // 这些覆写意味着"每个状态一个常量"在最坏情况下不够精确 —— 记下来（不是失败）
        assertTrue(blocks > 0);
    }

    /**
     * 区域推送的**主导成本**：每个方块一次 {@code Block.getRawIdFromState}（{@code IdList} 的
     * 身份表查找）。这里用真实状态对象实测它的单次成本 —— 乘上窗口方块数就是推送的下界。
     *
     * <p>未实测：真实 {@code ChunkSection.getBlockState} 的那一段（构造 ChunkSection 需要 biome
     * {@code Registry}，1.20.4 的 biome 是动态注册表，单测里拿不到；见 docs §3 的"未验证"）。
     */
    @Test
    void rawIdLookupCost() {
        BlockState[] states = new BlockState[1024];
        int n = Math.min(states.length, Block.STATE_IDS.size());
        for (int i = 0; i < n; i++) {
            states[i] = Block.getStateFromRawId(i);
        }
        long best = Long.MAX_VALUE;
        int reps = 200;
        long sink = 0;
        for (int rep = 0; rep < reps; rep++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                sink += Block.getRawIdFromState(states[i]);
            }
            long dt = System.nanoTime() - t0;
            if (dt < best) {
                best = dt;
            }
        }
        System.out.println("[mirror] Block.getRawIdFromState 实测：最好 " + best + " ns / " + n + " 次 = "
                + String.format("%.2f", best / (double) n) + " ns/次（sink=" + sink + "）");
        assertTrue(best > 0);
    }
}
