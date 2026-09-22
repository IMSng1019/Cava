package cava.oracle;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试向量生成器。**所有随机性都来自 MASTER_SEED 与 caseId**，见规格第 10 节。
 *
 * <p>产物（全部大端字节序，Java DataOutputStream 默认）：
 * <ul>
 *   <li>vectors-%02d.bin —— 配方式（只存 caseSeed，世界可由它重建）</li>
 *   <li>golden-00.bin —— 全量式（连调色板与体素一起存）</li>
 *   <li>manifest.txt —— 人读的格式说明</li>
 * </ul>
 */
public final class VectorGen {
    public static final long MASTER_SEED = 0x1F2E3D4C5B6A7988L;
    public static final long TERRAIN_SEED_SALT = 0xA5A5A5A5A5A5A5A5L;
    public static final int DEFAULT_CASES = 10000;
    public static final int GOLDEN_CASES = 60;
    public static final int SHARD_LIMIT_BYTES = 4_000_000;

    public static final int MAKER_LAND = 0;
    public static final int MAKER_AMPHIBIOUS = 1;

    private VectorGen() {}

    // ------------------------------------------------------------------ case 描述

    public static final class CaseSpec {
        public int caseId;
        public long caseSeed;
        public int scenario;
        public int makerKind;
        public int originX;
        public int originY;
        public int originZ;
        public int sizeX;
        public int sizeY;
        public int sizeZ;
        public int groundY;
        public int seaLevel;
        public MobProfile profile;
        public int[] target;
        public int range;
        public float maxRange;
        public int reachRadius;
        public float followRange;
        public Terrain terrain;
        public PathResult result;
    }

    public static final class Stats {
        public int cases;
        public int found;
        /** Path.reachesTarget() == false 的组数 —— 即 findPathToAny 走了 found 非空分支（真的抵达了目标）。 */
        public int reachedBranch;
        /** Path.reachesTarget() == true 的组数 —— 走了回退分支（目标没抵达，返回的是「最接近」的路径）。 */
        public int fallbackBranch;
        public long totalNodes;
        public int maxNodes;
        public long totalExpanded;
        public int maxExpanded;
        public final int[] scenarioCount = new int[TerrainGen.SCENARIO_COUNT];
        public final int[] scenarioFound = new int[TerrainGen.SCENARIO_COUNT];
        public float minF = Float.MAX_VALUE;
        public float maxF = -Float.MAX_VALUE;
        public double sumF;
        public long fCount;
        public int byteCount;
    }

    // ------------------------------------------------------------------ 构建

    public static long mix(long a, long b) {
        long z = a ^ (b * 0x9E3779B97F4A7C15L);
        z ^= z >>> 29;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 32;
        z *= 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static CaseSpec buildCase(int caseId) {
        long seed = mix(MASTER_SEED, caseId);
        Xorshift rng = new Xorshift(seed);
        CaseSpec c = new CaseSpec();
        c.caseId = caseId;
        c.caseSeed = seed;
        c.scenario = rng.nextInt(TerrainGen.SCENARIO_COUNT);
        c.makerKind = rng.nextInt(2);
        c.sizeX = 14 + rng.nextInt(5);
        c.sizeZ = 14 + rng.nextInt(5);
        c.sizeY = 14 + rng.nextInt(4);
        c.originX = -400 + rng.nextInt(801);
        c.originZ = -400 + rng.nextInt(801);
        c.originY = 40 + rng.nextInt(60);
        c.seaLevel = c.originY + 6 + rng.nextInt(6);
        c.groundY = c.originY + 5 + rng.nextInt(3);

        MobProfile p = new MobProfile();
        p.width = rng.pick(new float[] {0.4f, 0.5f, 0.6f, 0.7f, 0.9f, 1.2f, 1.4f});
        p.height = rng.pick(new float[] {0.7f, 1.0f, 1.3f, 1.8f, 2.1f, 2.9f});
        p.stepHeight = rng.pick(new float[] {0.0f, 0.5f, 0.6f, 1.0f, 1.0625f});
        p.safeFallDistance = rng.pick(new int[] {0, 1, 2, 3, 4});
        p.canOpenDoors = rng.nextBoolean();
        p.canEnterOpenDoors = rng.nextBoolean();
        p.canSwim = rng.nextBoolean();
        p.canWalkOverFences = rng.nextBoolean();
        p.amphibious = c.makerKind == MAKER_AMPHIBIOUS;
        p.penalizeDeepWater = p.amphibious && rng.nextBoolean();
        p.onGround = true;
        int overrides = rng.nextInt(6);
        float[] values = {0.0f, 0.0f, 1.0f, 2.0f, 4.0f, 8.0f, 16.0f, -1.0f};
        for (int i = 0; i < overrides; i++) {
            p.setPathfindingPenalty(rng.pick(Pnt.VALUES), rng.pick(values));
        }
        c.profile = p;

        c.terrain = TerrainGen.generate(seed ^ TERRAIN_SEED_SALT, c.scenario,
                c.originX, c.originY, c.originZ, c.sizeX, c.sizeY, c.sizeZ, c.groundY, c.seaLevel);

        int sx = 0;
        int sz = 0;
        boolean ok = false;
        for (int attempt = 0; attempt < 24 && !ok; attempt++) {
            sx = rng.nextInt(c.sizeX);
            sz = rng.nextInt(c.sizeZ);
            BlockKind k = c.terrain.kindAt(c.originX + sx, c.groundY + 1, c.originZ + sz);
            ok = k.has(BlockKind.AIR) || k.has(BlockKind.PATHFIND_LAND);
        }
        p.x = c.originX + sx + 0.5;
        p.y = c.groundY + 1;
        p.z = c.originZ + sz + 0.5;
        p.touchingWater = c.terrain.fluidIsWater(c.originX + sx, c.groundY + 1, c.originZ + sz)
                || c.terrain.fluidIsWater(c.originX + sx, c.groundY, c.originZ + sz);

        int tx = sx + 6;
        int tz = sz + 6;
        for (int attempt = 0; attempt < 32; attempt++) {
            int cx = rng.nextInt(c.sizeX);
            int cz = rng.nextInt(c.sizeZ);
            if (Math.abs(cx - sx) + Math.abs(cz - sz) >= 5) {
                tx = cx;
                tz = cz;
                break;
            }
        }
        int ty = c.groundY + rng.nextInt(3);
        c.target = new int[] {c.originX + tx, ty, c.originZ + tz};

        c.range = rng.pick(new int[] {8, 16, 24, 32});
        c.followRange = rng.pick(new float[] {2.0f, 4.0f, 8.0f, 12.0f, 16.0f, 24.0f, 32.0f});
        c.maxRange = rng.pick(new float[] {3.0f, 4.0f, 5.0f, 6.0f, 8.0f, 12.0f, 16.0f, 24.0f, 32.0f});
        c.reachRadius = rng.pick(new int[] {0, 0, 1, 1, 2});
        return c;
    }

    public static void runCase(CaseSpec c) {
        LandMaker maker = new LandMaker();
        maker.amphibious = c.makerKind == MAKER_AMPHIBIOUS;
        maker.penalizeDeepWater = c.profile.penalizeDeepWater;
        Navigator nav = new Navigator(maker, c.range);
        List<int[]> targets = new ArrayList<>();
        targets.add(c.target);
        c.result = nav.findPathToAny(c.terrain, c.profile, targets, c.maxRange, c.reachRadius, c.followRange);
    }

    // ------------------------------------------------------------------ 序列化

    private static void writeInput(DataOutputStream out, CaseSpec c) throws IOException {
        out.writeInt(c.caseId);
        out.writeLong(c.caseSeed);
        out.writeByte(c.scenario);
        out.writeByte(c.makerKind);
        out.writeShort(0);
        out.writeInt(c.originX);
        out.writeInt(c.originY);
        out.writeInt(c.originZ);
        out.writeShort(c.sizeX);
        out.writeShort(c.sizeY);
        out.writeShort(c.sizeZ);
        out.writeInt(c.originY);       // minY（世界底面）
        out.writeInt(c.seaLevel);
        out.writeInt(c.groundY);
        out.writeFloat(c.profile.width);
        out.writeFloat(c.profile.height);
        out.writeFloat(c.profile.stepHeight);
        out.writeInt(c.profile.safeFallDistance);
        int flags = 0;
        if (c.profile.canOpenDoors) flags |= 1;
        if (c.profile.canEnterOpenDoors) flags |= 1 << 1;
        if (c.profile.canSwim) flags |= 1 << 2;
        if (c.profile.canWalkOverFences) flags |= 1 << 3;
        if (c.profile.amphibious) flags |= 1 << 4;
        if (c.profile.penalizeDeepWater) flags |= 1 << 5;
        if (c.profile.onGround) flags |= 1 << 6;
        if (c.profile.touchingWater) flags |= 1 << 7;
        out.writeByte(flags);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        out.writeDouble(c.profile.x);
        out.writeDouble(c.profile.y);
        out.writeDouble(c.profile.z);
        out.writeBoolean(c.profile.canWalkOnFluid);
        for (int i = 0; i < 7; i++) {
            out.writeByte(0);
        }
        int mask = 0;
        for (int i = 0; i < Pnt.VALUES.length; i++) {
            if (c.profile.isOverridden(Pnt.VALUES[i])) {
                mask |= 1 << i;
            }
        }
        out.writeInt(mask);
        for (Pnt t : Pnt.VALUES) {
            out.writeFloat(c.profile.getPathfindingPenalty(t));
        }
        out.writeInt(c.target[0]);
        out.writeInt(c.target[1]);
        out.writeInt(c.target[2]);
        out.writeShort(c.range);
        out.writeFloat(c.maxRange);
        out.writeInt(c.reachRadius);
        out.writeFloat(c.followRange);
        out.writeInt((int) (c.terrain.worldHash() & 0xFFFFFFFFL));
    }

    private static void writeOutput(DataOutputStream out, PathResult r) throws IOException {
        out.writeBoolean(r.found);
        out.writeShort(r.found ? r.nodes.length : 0xFFFF);
        out.writeBoolean(r.reachesTarget);
        out.writeInt(r.expandedCount);
        out.writeLong(r.traceHash);
        out.writeFloat(r.manhattanDistanceFromTarget);
        if (!r.found) {
            return;
        }
        for (int i = 0; i < r.nodes.length; i++) {
            PathResult.OutNode n = r.nodes[i];
            if (i == 0) {
                out.writeInt(n.x);
                out.writeInt(n.y);
                out.writeInt(n.z);
            } else {
                PathResult.OutNode prev = r.nodes[i - 1];
                out.writeByte(n.x - prev.x);
                out.writeByte(n.y - prev.y);
                out.writeByte(n.z - prev.z);
            }
            out.writeByte(n.type);
            out.writeByte(n.visited ? 1 : 0);
            out.writeFloat(n.pathLength);
            out.writeFloat(n.penalizedPathLength);
            out.writeFloat(n.distanceToNearestTarget);
            out.writeFloat(n.heapWeight);
            out.writeFloat(n.penalty);
        }
    }

    private static void writeCase(DataOutputStream out, CaseSpec c) throws IOException {
        writeInput(out, c);
        writeOutput(out, c.result);
    }

    // ------------------------------------------------------------------ 主流程

    public static Stats generate(Path resourcesDir, int caseCount) throws IOException {
        Files.createDirectories(resourcesDir);
        Stats stats = new Stats();
        int shardIndex = 0;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(buffer);
        header.writeBytes("CVOV");
        header.writeShort(1);
        header.writeShort(0);
        header.writeInt(0);            // caseCount，最后回填
        header.writeLong(MASTER_SEED);
        header.writeInt(0);            // shardIndex，最后回填
        header.writeInt(0);            // shardCount，最后回填
        int shardCaseStart = 0;
        List<Path> written = new ArrayList<>();
        int totalShards = 0;

        for (int caseId = 0; caseId < caseCount; caseId++) {
            CaseSpec c = buildCase(caseId);
            runCase(c);
            ByteArrayOutputStream caseBuf = new ByteArrayOutputStream();
            DataOutputStream caseOut = new DataOutputStream(caseBuf);
            writeCase(caseOut, c);
            caseOut.flush();
            byte[] bytes = caseBuf.toByteArray();
            if (buffer.size() + bytes.length > SHARD_LIMIT_BYTES && buffer.size() > 1024) {
                totalShards++;
                Path file = resourcesDir.resolve(String.format("vectors-%02d.bin", shardIndex));
                byte[] raw = buffer.toByteArray();
                raw[8] = (byte) ((caseId - shardCaseStart) >>> 24);
                raw[9] = (byte) ((caseId - shardCaseStart) >>> 16);
                raw[10] = (byte) ((caseId - shardCaseStart) >>> 8);
                raw[11] = (byte) (caseId - shardCaseStart);
                raw[20] = (byte) (shardIndex >>> 24);
                raw[21] = (byte) (shardIndex >>> 16);
                raw[22] = (byte) (shardIndex >>> 8);
                raw[23] = (byte) shardIndex;
                Files.write(file, raw);
                written.add(file);
                stats.byteCount += raw.length;
                shardIndex++;
                shardCaseStart = caseId;
                buffer = new ByteArrayOutputStream();
            }
            buffer.write(bytes, 0, bytes.length);
            accumulate(stats, c);
        }
        if (buffer.size() > 0) {
            totalShards++;
            Path file = resourcesDir.resolve(String.format("vectors-%02d.bin", shardIndex));
            byte[] raw = buffer.toByteArray();
            int n = caseCount - shardCaseStart;
            raw[8] = (byte) (n >>> 24);
            raw[9] = (byte) (n >>> 16);
            raw[10] = (byte) (n >>> 8);
            raw[11] = (byte) n;
            raw[20] = (byte) (shardIndex >>> 24);
            raw[21] = (byte) (shardIndex >>> 16);
            raw[22] = (byte) (shardIndex >>> 8);
            raw[23] = (byte) shardIndex;
            Files.write(file, raw);
            written.add(file);
            stats.byteCount += raw.length;
        }
        // 回填 shardCount
        for (Path file : written) {
            byte[] raw = Files.readAllBytes(file);
            raw[24] = (byte) (totalShards >>> 24);
            raw[25] = (byte) (totalShards >>> 16);
            raw[26] = (byte) (totalShards >>> 8);
            raw[27] = (byte) totalShards;
            Files.write(file, raw);
        }
        writeGolden(resourcesDir);
        Files.writeString(resourcesDir.resolve("manifest.txt"), manifest(totalShards, caseCount));
        return stats;
    }

    private static void accumulate(Stats stats, CaseSpec c) {
        stats.cases++;
        stats.scenarioCount[c.scenario]++;
        if (c.result.found) {
            stats.found++;
            if (c.result.reachesTarget) {
                stats.fallbackBranch++;
            } else {
                stats.reachedBranch++;
            }
            stats.scenarioFound[c.scenario]++;
            stats.totalNodes += c.result.nodes.length;
            stats.maxNodes = Math.max(stats.maxNodes, c.result.nodes.length);
            for (PathResult.OutNode n : c.result.nodes) {
                float f = n.heapWeight;
                if (f < stats.minF) {
                    stats.minF = f;
                }
                if (f > stats.maxF) {
                    stats.maxF = f;
                }
                stats.sumF += f;
                stats.fCount++;
            }
        }
        stats.totalExpanded += c.result.expandedCount;
        stats.maxExpanded = Math.max(stats.maxExpanded, c.result.expandedCount);
    }

    private static void writeGolden(Path resourcesDir) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeBytes("CVOG");
        out.writeShort(1);
        out.writeShort(0);
        out.writeInt(GOLDEN_CASES);
        out.writeLong(MASTER_SEED);
        for (int i = 0; i < GOLDEN_CASES; i++) {
            CaseSpec c = buildCase(i);
            runCase(c);
            writeInput(out, c);
            out.writeShort(TerrainGen.PALETTE.length);
            for (BlockKind k : TerrainGen.PALETTE) {
                out.writeShort(k.flags);
                out.writeByte(k.fluid);
                out.writeByte(0);
                out.writeFloat(k.collisionMinY);
                out.writeFloat(k.collisionMaxY);
            }
            out.write(c.terrain.blocks);
            writeOutput(out, c.result);
        }
        out.flush();
        Files.write(resourcesDir.resolve("golden-00.bin"), buffer.toByteArray());
    }

    private static String manifest(int shards, int cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Cava P1 寻路测试向量\n");
        sb.append("# 由 cava.oracle.VectorGen 生成，格式见 docs/CAVA-pathfind-oracle-spec.md 第 10 节。\n");
        sb.append("# 全部整数/浮点均为 **大端**（Java DataOutputStream 默认）。\n");
        sb.append("masterSeed=").append(Long.toUnsignedString(MASTER_SEED, 16)).append('\n');
        sb.append("cases=").append(cases).append('\n');
        sb.append("shards=").append(shards).append('\n');
        sb.append("goldenCases=").append(GOLDEN_CASES).append('\n');
        sb.append("terrainSeedSalt=").append(Long.toUnsignedString(TERRAIN_SEED_SALT, 16)).append('\n');
        sb.append("复现：javac --release 21 -encoding UTF-8 -d out (Get-ChildItem -Recurse src/test/java/cava/oracle/*.java)\n");
        sb.append("      java -cp out cava.oracle.OracleSelfTest\n");
        return sb.toString();
    }
}
