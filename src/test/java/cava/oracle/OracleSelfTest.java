package cava.oracle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P1-Oracle 自测入口（不需要 Gradle）。
 *
 * <pre>
 * javac --release 21 -encoding UTF-8 -d out (Get-ChildItem -Recurse src/test/java/cava/oracle/*.java)
 * java -cp out cava.oracle.OracleSelfTest
 * </pre>
 *
 * 它会（a）跑一遍测试向量生成并落盘，（b）跑若干语义不变式自检，（c）打印统计。
 */
public final class OracleSelfTest {
    private static final String DEFAULT_OUT = "src/test/resources/cava/oracle";

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        Path outDir = Path.of(args.length > 0 ? args[0] : DEFAULT_OUT);
        int cases = args.length > 1 ? Integer.parseInt(args[1]) : VectorGen.DEFAULT_CASES;

        System.out.println("=== Cava P1 pathfind oracle self-test ===");
        System.out.println("out        = " + outDir.toAbsolutePath());
        System.out.println("cases      = " + cases);
        System.out.println("masterSeed = 0x" + Long.toUnsignedString(VectorGen.MASTER_SEED, 16));

        runInvariants();

        long t0 = System.nanoTime();
        VectorGen.Stats stats = VectorGen.generate(outDir, cases);
        long t1 = System.nanoTime();

        System.out.println();
        System.out.println("--- 统计 ---");
        System.out.printf("组数            : %d%n", stats.cases);
        System.out.printf("有路径          : %d (%.2f%%)%n", stats.found, 100.0 * stats.found / stats.cases);
        System.out.printf("无路径(Path=null): %d (%.2f%%)  <- Land maker 有目标时从不返回 null%n",
                stats.cases - stats.found, 100.0 * (stats.cases - stats.found) / stats.cases);
        System.out.printf("真的抵达目标    : %d (%.2f%%)  <- Path.reachesTarget() == false%n",
                stats.reachedBranch, 100.0 * stats.reachedBranch / stats.cases);
        System.out.printf("未抵达(回退路径): %d (%.2f%%)  <- Path.reachesTarget() == true（原版语义是反的）%n",
                stats.fallbackBranch, 100.0 * stats.fallbackBranch / stats.cases);
        System.out.printf("平均路径节点数  : %.3f%n", stats.found == 0 ? 0.0 : (double) stats.totalNodes / stats.found);
        System.out.printf("最大路径节点数  : %d%n", stats.maxNodes);
        System.out.printf("平均展开节点数  : %.3f%n", (double) stats.totalExpanded / stats.cases);
        System.out.printf("最大展开节点数  : %d%n", stats.maxExpanded);
        System.out.printf("f 值 min/max/avg: %.4f / %.4f / %.4f (样本 %d)%n",
                stats.fCount == 0 ? 0.0f : stats.minF,
                stats.fCount == 0 ? 0.0f : stats.maxF,
                stats.fCount == 0 ? 0.0 : stats.sumF / stats.fCount,
                stats.fCount);
        System.out.println("逐场景（组数 / 有路径）：");
        String[] names = {"FLAT", "OBSTACLES", "STAIRS", "WATER", "LAVA", "DOORS", "FENCE",
                "SCAFFOLDING", "MAZE", "MIXED"};
        for (int i = 0; i < names.length; i++) {
            System.out.printf("  %-12s %5d / %5d%n", names[i], stats.scenarioCount[i], stats.scenarioFound[i]);
        }
        System.out.printf("生成耗时        : %.3f s%n", (t1 - t0) / 1e9);

        System.out.println();
        System.out.println("--- 落盘文件 ---");
        long total = 0;
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(outDir)) {
            stream.sorted().forEach(files::add);
        }
        for (Path f : files) {
            long size = Files.size(f);
            total += size;
            System.out.printf("  %-28s %8d bytes%n", f.getFileName(), size);
        }
        System.out.printf("  合计 %d bytes (%.2f MB)%n", total, total / 1048576.0);

        fValueSample(outDir);
    }

    /** f 值分布抽样：从 shard 0 里读回首 N 个 case 的末节点 f（顺带验证文件可读）。 */
    private static void fValueSample(Path outDir) throws IOException {
        System.out.println();
        System.out.println("--- f 值分布抽样（shard 00 前若干条路径的末节点 heapWeight/f） ---");
        Path shard = outDir.resolve("vectors-00.bin");
        if (!Files.exists(shard)) {
            System.out.println("  (vectors-00.bin 不存在，跳过)");
            return;
        }
        byte[] raw = Files.readAllBytes(shard);
        System.out.printf("  shard 头: magic=%s version=%d caseCount=%d shardIndex=%d shardCount=%d%n",
                new String(raw, 0, 4, java.nio.charset.StandardCharsets.US_ASCII),
                readU16(raw, 4), readU32(raw, 8), readU32(raw, 20), readU32(raw, 24));
        int shown = 0;
        int printed = 0;
        int pos = 28;
        while (pos < raw.length && printed < 10) {
            int[] cursor = new int[] {pos};
            CaseView cv = readCaseView(raw, cursor);
            pos = cursor[0];
            if (cv.found && cv.nodes.length > 0) {
                PathResult.OutNode last = cv.nodes[cv.nodes.length - 1];
                System.out.printf("  case %5d: nodes=%3d expanded=%4d reachesTarget=%s  endf=%.5f endg=%.5f endh=%.5f penalty=%.3f%n",
                        cv.caseId, cv.nodes.length, cv.expandedCount, cv.reachesTarget,
                        last.heapWeight, last.penalizedPathLength, last.distanceToNearestTarget, last.penalty);
            } else {
                System.out.printf("  case %5d: 无路径 expanded=%4d%n", cv.caseId, cv.expandedCount);
            }
            printed++;
            shown++;
            if (shown > 40) {
                break;
            }
        }
    }

    private static final class CaseView {
        int caseId;
        boolean found;
        boolean reachesTarget;
        int expandedCount;
        PathResult.OutNode[] nodes = new PathResult.OutNode[0];
    }

    private static CaseView readCaseView(byte[] raw, int[] cursor) {
        int p = cursor[0];
        CaseView v = new CaseView();
        v.caseId = readI32(raw, p);
        p += 4 + 8 + 1 + 1 + 2;
        p += 4 * 3;
        p += 2 * 3;
        p += 4 * 3;
        p += 4 + 4 + 4;
        p += 4 + 1 + 3;
        p += 8 * 3;
        p += 1 + 7;
        p += 4 + 4 * Pnt.VALUES.length;
        p += 4 * 3;
        p += 2 + 4 + 4 + 4;
        p += 4;
        v.found = raw[p] != 0;
        int nodeCount = readU16(raw, p + 1);
        v.reachesTarget = raw[p + 3] != 0;
        v.expandedCount = readI32(raw, p + 4);
        p += 8 + 8 + 4;
        if (v.found && nodeCount != 0xFFFF) {
            v.nodes = new PathResult.OutNode[nodeCount];
            int x = readI32(raw, p);
            int y = readI32(raw, p + 4);
            int z = readI32(raw, p + 8);
            p += 12;
            for (int i = 0; i < nodeCount; i++) {
                if (i > 0) {
                    x += raw[p];
                    y += raw[p + 1];
                    z += raw[p + 2];
                    p += 3;
                }
                PathResult.OutNode n = new PathResult.OutNode();
                n.x = x;
                n.y = y;
                n.z = z;
                n.type = raw[p] & 0xFF;
                n.visited = raw[p + 1] != 0;
                p += 2;
                n.pathLength = readF32(raw, p);
                n.penalizedPathLength = readF32(raw, p + 4);
                n.distanceToNearestTarget = readF32(raw, p + 8);
                n.heapWeight = readF32(raw, p + 12);
                n.penalty = readF32(raw, p + 16);
                p += 20;
                v.nodes[i] = n;
            }
        }
        cursor[0] = p;
        return v;
    }

    private static int readU16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int readI32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static int readU32(byte[] b, int p) {
        return readI32(b, p);
    }

    private static float readF32(byte[] b, int p) {
        return Float.intBitsToFloat(readI32(b, p));
    }

    // ------------------------------------------------------------------ 不变式

    private static int checks;
    private static int failures;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] " + what);
        }
    }

    /** 定点真值表专用：跑一行并打印实际值（captain 要求可贴进报告）。 */
    private static void row(boolean expected, boolean actual, String label) {
        check(actual == expected, label);
        System.out.printf("    %-6s %-42s actual=%-5s expected=%s%n",
                actual == expected ? "[ok]" : "[FAIL]", label, actual, expected);
    }

    private static void runInvariants() {
        System.out.println();
        System.out.println("--- 语义不变式自检 ---");

        // 1) PNode.hash 的坏打包（规格 3.1）
        check(PNode.hash(0, 1, 0) == PNode.hash(0, 257, 0), "hash: y 只有 8 位（1 与 257 撞）");
        check(PNode.hash(0, -1, 0) == PNode.hash(0, 255, 0), "hash: y=-1 与 y=255 撞");
        check(PNode.hash(-1, 0, 0) != PNode.hash(1, 0, 0), "hash: x 的符号位参与");
        check(PNode.hash(0, 0, -1) != PNode.hash(0, 0, 1), "hash: z 的符号位参与");
        check(PNode.hash(32767, 0, 0) == PNode.hash(-1 & 0x7FFF, 0, 0)
                || PNode.hash(32767, 0, 0) != PNode.hash(-1, 0, 0), "hash: x 掩码 15 位");

        // 2) 同一个坏哈希 -> 同一个 PathNode 实例（规格 3.3）
        LandMaker maker = new LandMaker();
        MobProfile profile = new MobProfile();
        profile.width = 0.6f;
        profile.height = 1.8f;
        Terrain dummy = TerrainGen.generate(1L, TerrainGen.SC_FLAT, 0, 64, 0, 4, 4, 4, 66, 70);
        maker.init(dummy, profile);
        PNode a = maker.getNode(5, 10, 7);
        PNode b = maker.getNode(5, 266, 7);
        check(a == b, "PathNodeMaker 缓存：hash 相同 -> 同一实例（y 与 y+256）");
        PNode c = maker.getNode(6, 10, 7);
        check(a != c, "PathNodeMaker 缓存：hash 不同 -> 不同实例");

        // 3) MinHeap 相等权重的次序（规格 1.6）
        MinHeap heap = new MinHeap();
        PNode n0 = new PNode(0, 0, 0);
        PNode n1 = new PNode(1, 0, 0);
        PNode n2 = new PNode(2, 0, 0);
        heap.push(n0);
        heap.push(n1);
        heap.push(n2);
        PNode p0 = heap.pop();
        PNode p1 = heap.pop();
        PNode p2 = heap.pop();
        check(p0 == n0, "MinHeap 相等权重：第 1 次 pop 是下标 0");
        check(p1 == n2, "MinHeap 相等权重：第 2 次 pop 是原末尾元素（非 FIFO）");
        check(p2 == n1, "MinHeap 相等权重：第 3 次 pop 是 n1");
        check(heap.isEmpty(), "MinHeap 相等权重：三次 pop 后为空");

        // 4) shiftDown 在左右孩子相等时选右孩子
        //    根权重 0，左孩子 1，右孩子 1 -> pop 后根被替成右孩子
        MinHeap h2 = new MinHeap();
        PNode root = new PNode(0, 0, 0);
        PNode left = new PNode(1, 0, 0);
        PNode right = new PNode(2, 0, 0);
        PNode tail = new PNode(3, 0, 0);
        h2.push(root);
        h2.push(left);
        h2.push(right);
        h2.push(tail);
        h2.pop();
        check(h2.getStart() == tail || h2.getStart() == right || h2.getStart() == left,
                "MinHeap shiftDown：pop 后根来自尾部/子节点");

        // 5) clear() 只置 count（规格 1.3）
        MinHeap h3 = new MinHeap();
        PNode only = new PNode(0, 0, 0);
        h3.push(only);
        h3.clear();
        check(h3.isEmpty(), "MinHeap clear() 后 isEmpty");
        check(only.isInHeap(), "MinHeap clear() **不**重置 heapIndex（原版行为）");

        // 6) Mth 语义
        check(Mth.floor(-1.5) == -2, "Mth.floor(-1.5) == -2");
        check(Mth.floor(2.0) == 2, "Mth.floor(2.0) == 2");
        check(Mth.ceil(2.0) == 2, "Mth.ceil(2.0) == 2");
        check(Mth.ceil(2.1) == 3, "Mth.ceil(2.1) == 3");
        check(Mth.sqrt(4.0f) == 2.0f, "Mth.sqrt(4) == 2");
        check(Float.floatToRawIntBits(Mth.sqrt(2.0f)) == Float.floatToRawIntBits((float) Math.sqrt(2.0)),
                "Mth.sqrt 与 (float)Math.sqrt 逐位一致");

        // 7) 确定性：同一 case 跑两遍，全部 float 位模式一致
        VectorGen.CaseSpec c1 = VectorGen.buildCase(7);
        VectorGen.runCase(c1);
        VectorGen.CaseSpec c2 = VectorGen.buildCase(7);
        VectorGen.runCase(c2);
        check(c1.result.found == c2.result.found, "确定性：found 一致");
        check(c1.result.traceHash == c2.result.traceHash, "确定性：traceHash 一致");
        boolean same = true;
        if (c1.result.found && c2.result.found) {
            if (c1.result.nodes.length != c2.result.nodes.length) {
                same = false;
            } else {
                for (int i = 0; i < c1.result.nodes.length; i++) {
                    PathResult.OutNode x = c1.result.nodes[i];
                    PathResult.OutNode y = c2.result.nodes[i];
                    same &= x.x == y.x && x.y == y.y && x.z == y.z && x.type == y.type
                            && Float.floatToRawIntBits(x.heapWeight) == Float.floatToRawIntBits(y.heapWeight)
                            && Float.floatToRawIntBits(x.penalizedPathLength) == Float.floatToRawIntBits(y.penalizedPathLength)
                            && Float.floatToRawIntBits(x.distanceToNearestTarget) == Float.floatToRawIntBits(y.distanceToNearestTarget)
                            && Float.floatToRawIntBits(x.pathLength) == Float.floatToRawIntBits(y.pathLength)
                            && Float.floatToRawIntBits(x.penalty) == Float.floatToRawIntBits(y.penalty);
                }
            }
        }
        check(same, "确定性：逐节点逐 float 位模式一致");

        // 8) Pnt 表与 ordinal
        check(Pnt.VALUES.length == 26, "PathNodeType 有 26 个常量");
        check(Pnt.BLOCKED.ordinal() == 0 && Pnt.DANGER_TRAPDOOR.ordinal() == 25, "ordinal 0/25 正确");
        check(Pnt.WATER.getDefaultPenalty() == 8.0f && Pnt.DAMAGE_FIRE.getDefaultPenalty() == 16.0f,
                "WATER=8.0f / DAMAGE_FIRE=16.0f");
        check(Pnt.BLOCKED.getDefaultPenalty() == -1.0f, "BLOCKED=-1.0f");

        // 9) 邻居顺序：Land 的第 0 个候选是 (x, y+1, z+1)
        LandMaker flat = new LandMaker();
        MobProfile walker = new MobProfile();
        walker.width = 0.6f;
        walker.height = 1.8f;
        walker.stepHeight = 0.6f;
        walker.safeFallDistance = 3;
        walker.onGround = true;
        walker.x = 0.5;
        walker.y = 11;
        walker.z = 0.5;
        Terrain flatWorld = TerrainGen.generate(2L, TerrainGen.SC_FLAT, -4, 10, -4, 9, 6, 9, 10, 16);
        flat.init(flatWorld, walker);
        PNode center = flat.getNode(0, 11, 0);
        center.type = flat.getNodeType(walker, 0, 11, 0);
        center.penalty = walker.getPathfindingPenalty(center.type);
        PNode[] out = new PNode[32];
        int n = flat.getSuccessors(out, center);
        check(n >= 1, "平地至少有 1 个后继");
        check(out[0] != null && out[0].z == center.z + 1, "Land 第 0 个后继是 z+1（SOUTH）");
        check(n >= 2 && out[1] != null && out[1].x == center.x - 1, "Land 第 1 个后继是 x-1（WEST）");
        check(n >= 3 && out[2] != null && out[2].x == center.x + 1, "Land 第 2 个后继是 x+1（EAST）");
        check(n >= 4 && out[3] != null && out[3].z == center.z - 1, "Land 第 3 个后继是 z-1（NORTH）");

        // 10) isValidDiagonalSuccessor 的定点真值表 —— 锁住一个本项目读反过的分支
        //     真值来自字节码 627-719。关键：154/179 是 ifeq 188，即 flag5 == 0 时才返回 false
        //     -> 第三个合取项是 !flag5。见规格 §2.4 / §9.6。
        checkDiagonalTruthTable();

        System.out.printf("  自检：%d 项，失败 %d 项%n", checks, failures);
    }

    /**
     * isValidDiagonalSuccessor 的定点用例：体型宽度 x 两侧栅栏组合 x 高度/惩罚/visited。
     * 每一行的期望值都由字节码推出来，不是从实现反推的。
     */
    private static void checkDiagonalTruthTable() {
        MobProfile p = new MobProfile();
        p.height = 1.0f;
        LandMaker maker = new LandMaker();
        Terrain dummy = TerrainGen.generate(3L, TerrainGen.SC_FLAT, 0, 64, 0, 4, 4, 4, 66, 70);
        p.width = 0.4f;
        maker.init(dummy, p);

        p.width = 0.4f;
        row(true, diag(maker, Pnt.FENCE, -1.0f, 0, Pnt.FENCE, -1.0f, 0, 0.0f, false, Pnt.OPEN),
                "窄(0.4) + 两侧栅栏(y=host.y)");
        p.width = 0.9f;
        row(false, diag(maker, Pnt.FENCE, -1.0f, 0, Pnt.FENCE, -1.0f, 0, 0.0f, false, Pnt.OPEN),
                "宽(0.9) + 两侧栅栏(y=host.y)");
        p.width = 0.5f;
        row(false, diag(maker, Pnt.FENCE, -1.0f, 0, Pnt.FENCE, -1.0f, 0, 0.0f, false, Pnt.OPEN),
                "width == 0.5 边界（严格 < 0.5 才算窄）");
        p.width = 0.49999f;
        row(true, diag(maker, Pnt.FENCE, -1.0f, 0, Pnt.FENCE, -1.0f, 0, 0.0f, false, Pnt.OPEN),
                "width == 0.49999");
        p.width = 0.4f;
        row(false, diag(maker, Pnt.FENCE, -1.0f, 0, Pnt.WALKABLE, 0.0f, 0, 0.0f, false, Pnt.OPEN),
                "窄 + 只有一侧栅栏（flag5 要求两侧都是）");
        p.width = 0.9f;
        row(true, diag(maker, Pnt.FENCE, -1.0f, -1, Pnt.FENCE, -1.0f, -1, 0.0f, false, Pnt.OPEN),
                "宽 + 两侧栅栏但 y 都低于 host");
        row(true, diag(maker, Pnt.FENCE, 0.0f, 0, Pnt.FENCE, 0.0f, 0, 0.0f, false, Pnt.OPEN),
                "宽 + 两侧 FENCE 类型但 penalty 为 0");
        row(false, diag(maker, Pnt.FENCE, -1.0f, 0, Pnt.FENCE, -1.0f, 0, -1.0f, false, Pnt.OPEN),
                "diag.penalty < 0");
        row(false, diag(maker, Pnt.WALKABLE, 0.0f, 0, Pnt.WALKABLE, 0.0f, 0, 0.0f, true, Pnt.OPEN),
                "diag.visited");
        row(false, diag(maker, Pnt.WALKABLE, 0.0f, 0, Pnt.WALKABLE, 0.0f, 1, 0.0f, false, Pnt.OPEN),
                "sideB.y > host.y");
        row(false, diag(maker, Pnt.WALKABLE, 0.0f, 1, Pnt.WALKABLE, 0.0f, 0, 0.0f, false, Pnt.OPEN),
                "sideA.y > host.y");
        row(false, diag(maker, Pnt.WALKABLE_DOOR, 0.0f, 0, Pnt.WALKABLE, 0.0f, 0, 0.0f, false, Pnt.OPEN),
                "sideA == WALKABLE_DOOR");
        row(false, diag(maker, Pnt.WALKABLE, 0.0f, 0, Pnt.WALKABLE, 0.0f, 0, 0.0f, false, Pnt.WALKABLE_DOOR),
                "diag == WALKABLE_DOOR");
        row(false, maker.isValidDiagonalSuccessor(new PNode(0, 0, 0), null, new PNode(0, 0, 0), new PNode(0, 0, 0)),
                "sideA == null");
    }

    private static boolean diag(LandMaker maker, Pnt sideAType, float sideAPenalty, int sideAY,
                                Pnt sideBType, float sideBPenalty, int sideBY,
                                float diagPenalty, boolean diagVisited, Pnt diagType) {
        PNode host = new PNode(0, 0, 0);
        // PathNode.x/y/z 是 final（与原版一致），所以高度差要在构造时给。
        PNode sideA = new PNode(-1, sideAY, 0);
        PNode sideB = new PNode(0, sideBY, -1);
        PNode diag = new PNode(-1, 0, -1);
        sideA.type = sideAType;
        sideA.penalty = sideAPenalty;
        sideB.type = sideBType;
        sideB.penalty = sideBPenalty;
        diag.type = diagType;
        diag.penalty = diagPenalty;
        diag.visited = diagVisited;
        return maker.isValidDiagonalSuccessor(host, sideA, sideB, diag);
    }
}
