package cava.oracle;

import java.util.HashMap;
import java.util.Map;

/**
 * net.minecraft.entity.ai.pathing.LandPathNodeMaker（class_14 / efl）的逐分支复刻，
 * 并用 {@link #amphibious} / {@link #penalizeDeepWater} 覆盖 AmphibiousPathNodeMaker（class_15 / efa）。
 *
 * <p>证据：javap -p -c 的字节码行号在 docs/CAVA-pathfind-oracle-spec.md 里逐条标注。
 * **不要重排任何分支、不要把 float 提升为 double。**
 */
public class LandMaker extends NodeMaker {
    public boolean amphibious;
    public boolean penalizeDeepWater;

    private float oldWalkablePenalty;
    private float oldWaterBorderPenalty;

    /** Long2ObjectMap<PathNodeType>，键 = BlockPos.asLong(x,y,z)。 */
    private final Map<Long, Pnt> nodeTypes = new HashMap<>();

    public static long asLong(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF)) | ((long) (z & 0x3FFFFFF) << 12);
    }

    @Override
    public void init(Terrain world, MobProfile entity) {
        super.init(world, entity);
        if (this.amphibious) {
            // AmphibiousPathNodeMaker.init 会改写生物自己的惩罚表
            entity.setPathfindingPenalty(Pnt.WATER, 0.0f);
            this.oldWalkablePenalty = entity.getPathfindingPenalty(Pnt.WALKABLE);
            entity.setPathfindingPenalty(Pnt.WALKABLE, 6.0f);
            this.oldWaterBorderPenalty = entity.getPathfindingPenalty(Pnt.WATER_BORDER);
            entity.setPathfindingPenalty(Pnt.WATER_BORDER, 4.0f);
        }
    }

    @Override
    public void clear() {
        if (this.amphibious) {
            // 注意：原版**不还原 WATER**
            this.entity.setPathfindingPenalty(Pnt.WALKABLE, this.oldWalkablePenalty);
            this.entity.setPathfindingPenalty(Pnt.WATER_BORDER, this.oldWaterBorderPenalty);
        }
        this.nodeTypes.clear();
        super.clear();
    }

    public boolean isAmphibious() {
        return this.amphibious;
    }

    // ------------------------------------------------------------------ 节点类型

    /** LandPathNodeMaker.getCommonNodeType（规格第 5.4.6 节，分支顺序即优先级）。 */
    public static Pnt getCommonNodeType(Terrain w, int x, int y, int z) {
        BlockKind k = w.kindAt(x, y, z);
        if (k.has(BlockKind.AIR)) {
            return Pnt.OPEN;
        }
        if (k.has(BlockKind.TRAPDOOR)) {
            return Pnt.TRAPDOOR;
        }
        if (k.has(BlockKind.POWDER_SNOW)) {
            return Pnt.POWDER_SNOW;
        }
        if (k.has(BlockKind.CACTUS_OR_BERRY)) {
            return Pnt.DAMAGE_OTHER;
        }
        if (k.has(BlockKind.HONEY)) {
            return Pnt.STICKY_HONEY;
        }
        if (k.has(BlockKind.COCOA)) {
            return Pnt.COCOA;
        }
        if (k.has(BlockKind.CAUTIOUS)) {
            return Pnt.DAMAGE_CAUTIOUS;
        }
        if (k.fluid == BlockKind.FLUID_LAVA) {
            return Pnt.LAVA;
        }
        if (k.has(BlockKind.FIRE_DAMAGE)) {
            return Pnt.DAMAGE_FIRE;
        }
        if (k.has(BlockKind.DOOR)) {
            if (k.has(BlockKind.DOOR_OPEN)) {
                return Pnt.DOOR_OPEN;
            }
            return k.has(BlockKind.DOOR_HAND) ? Pnt.DOOR_WOOD_CLOSED : Pnt.DOOR_IRON_CLOSED;
        }
        if (k.has(BlockKind.RAIL)) {
            return Pnt.RAIL;
        }
        if (k.has(BlockKind.LEAVES)) {
            return Pnt.LEAVES;
        }
        if (k.has(BlockKind.FENCE_TAG) || k.has(BlockKind.WALL_TAG)
                || (k.has(BlockKind.FENCE_GATE) && !k.has(BlockKind.FENCE_GATE_OPEN))) {
            return Pnt.FENCE;
        }
        if (!k.has(BlockKind.PATHFIND_LAND)) {
            return Pnt.BLOCKED;
        }
        if (k.fluid == BlockKind.FLUID_WATER) {
            return Pnt.WATER;
        }
        return Pnt.OPEN;
    }

    /** LandPathNodeMaker.getNodeTypeFromNeighbors（规格第 5.4.5 节）。 */
    public static Pnt getNodeTypeFromNeighbors(Terrain w, int x, int y, int z, Pnt fallback) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int px = x + dx;
                    int py = y + dy;
                    int pz = z + dz;
                    BlockKind k = w.kindAt(px, py, pz);
                    if (k.has(BlockKind.CACTUS_OR_BERRY)) {
                        return Pnt.DANGER_OTHER;
                    }
                    if (k.has(BlockKind.FIRE_DAMAGE)) {
                        return Pnt.DANGER_FIRE;
                    }
                    if (w.fluidIsWater(px, py, pz)) {
                        return Pnt.WATER_BORDER;
                    }
                    if (k.has(BlockKind.CAUTIOUS)) {
                        return Pnt.DAMAGE_CAUTIOUS;
                    }
                }
            }
        }
        return fallback;
    }

    /** LandPathNodeMaker.getLandNodeType（规格第 5.4.4 节）。 */
    public static Pnt getLandNodeType(Terrain w, int x, int y, int z) {
        Pnt common = getCommonNodeType(w, x, y, z);
        if (common != Pnt.OPEN || y < w.minY + 1) {
            return common;
        }
        Pnt below = getCommonNodeType(w, x, y - 1, z);
        return switch (below) {
            case OPEN, WATER, LAVA, WALKABLE -> Pnt.OPEN;
            case DAMAGE_FIRE -> Pnt.DAMAGE_FIRE;
            case DAMAGE_OTHER -> Pnt.DAMAGE_OTHER;
            case STICKY_HONEY -> Pnt.STICKY_HONEY;
            case POWDER_SNOW -> Pnt.DANGER_POWDER_SNOW;
            case DAMAGE_CAUTIOUS -> Pnt.DAMAGE_CAUTIOUS;
            case TRAPDOOR -> Pnt.DANGER_TRAPDOOR;
            default -> getNodeTypeFromNeighbors(w, x, y, z, Pnt.WALKABLE);
        };
    }

    /**
     * LandPathNodeMaker.adjustNodeType。
     *
     * <p><b>重要</b>：findNearbyNodeTypes 里传给本方法的是**实体的方块坐标**（字节码 70: aload 7，
     * 即形参 BlockPos），不是当前体素的坐标 —— 这是原版的真实行为。
     */
    public Pnt adjustNodeType(Terrain w, int x, int y, int z, Pnt type) {
        boolean b = canEnterOpenDoors();
        Pnt result = type;
        if (result == Pnt.DOOR_WOOD_CLOSED && canOpenDoors() && b) {
            result = Pnt.WALKABLE_DOOR;
        }
        if (result == Pnt.DOOR_OPEN && !b) {
            result = Pnt.BLOCKED;
        }
        if (result == Pnt.RAIL
                && !w.kindAt(x, y, z).has(BlockKind.RAIL)
                && !w.kindAt(x, y - 1, z).has(BlockKind.RAIL)) {
            result = Pnt.UNPASSABLE_RAIL;
        }
        return result;
    }

    /** LandPathNodeMaker.getDefaultNodeType（Amphibious 版本见下）。 */
    public Pnt getDefaultNodeType(Terrain w, int x, int y, int z) {
        return getLandNodeType(w, x, y, z);
    }

    /** AmphibiousPathNodeMaker.getDefaultNodeType 的覆盖。 */
    protected Pnt getAmphibiousDefaultNodeType(Terrain w, int x, int y, int z) {
        Pnt common = getCommonNodeType(w, x, y, z);
        if (common == Pnt.WATER) {
            for (int d = 0; d < 6; d++) {
                if (getCommonNodeType(w, x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d]) == Pnt.BLOCKED) {
                    return Pnt.WATER_BORDER;
                }
            }
            return Pnt.WATER;
        }
        return getLandNodeType(w, x, y, z);
    }

    /** LandPathNodeMaker.findNearbyNodeTypes（规格第 5.4.2 节）。 */
    public Pnt findNearbyNodeTypes(Terrain w, int x, int y, int z, boolean[] set, Pnt def,
                                   int entityX, int entityY, int entityZ) {
        Pnt first = def;
        for (int dx = 0; dx < this.entityBlockXSize; dx++) {
            for (int dy = 0; dy < this.entityBlockYSize; dy++) {
                for (int dz = 0; dz < this.entityBlockZSize; dz++) {
                    int px = dx + x;
                    int py = dy + y;
                    int pz = dz + z;
                    Pnt base = this.amphibious
                            ? getAmphibiousDefaultNodeType(w, px, py, pz)
                            : getLandNodeType(w, px, py, pz);
                    Pnt type = adjustNodeType(w, entityX, entityY, entityZ, base);
                    if (dx == 0 && dy == 0 && dz == 0) {
                        first = type;
                    }
                    set[type.ordinal()] = true;
                }
            }
        }
        return first;
    }

    /** LandPathNodeMaker.getNodeType(BlockView,int,int,int,MobEntity)（规格第 5.4.1 节）。 */
    public Pnt getNodeType(Terrain w, int x, int y, int z, MobProfile e) {
        boolean[] set = new boolean[Pnt.VALUES.length];
        Pnt best = Pnt.BLOCKED;
        best = findNearbyNodeTypes(w, x, y, z, set, best, e.blockX(), e.blockY(), e.blockZ());
        if (set[Pnt.FENCE.ordinal()]) {
            return Pnt.FENCE;
        }
        if (set[Pnt.UNPASSABLE_RAIL.ordinal()]) {
            return Pnt.UNPASSABLE_RAIL;
        }
        Pnt chosen = Pnt.BLOCKED;
        for (Pnt t : Pnt.VALUES) {
            if (!set[t.ordinal()]) {
                continue;
            }
            if (e.getPathfindingPenalty(t) < 0.0f) {
                return t;
            }
            if (e.getPathfindingPenalty(t) >= e.getPathfindingPenalty(chosen)) {
                chosen = t;
            }
        }
        if (best == Pnt.OPEN && e.getPathfindingPenalty(chosen) == 0.0f && this.entityBlockXSize <= 1) {
            return Pnt.OPEN;
        }
        return chosen;
    }

    /** getNodeType(MobEntity,int,int,int)：带 nodeTypes 缓存。 */
    public Pnt getNodeType(MobProfile e, int x, int y, int z) {
        long key = asLong(x, y, z);
        Pnt v = this.nodeTypes.get(key);
        if (v == null) {
            v = getNodeType(this.world, x, y, z, e);
            this.nodeTypes.put(key, v);
        }
        return v;
    }

    // ------------------------------------------------------------------ getStart

    @Override
    public PNode getStart() {
        int y = this.entity.blockY();
        int bx = this.entity.blockX();
        int bz = this.entity.blockZ();
        BlockKind state = this.world.kindAt(bx, y, bz);
        if (this.entity.canWalkOnFluid && this.world.fluidAt(bx, y, bz) != BlockKind.FLUID_NONE) {
            while (this.entity.canWalkOnFluid && this.world.fluidAt(bx, y, bz) != BlockKind.FLUID_NONE) {
                y++;
                state = this.world.kindAt(bx, y, bz);
            }
            y--;
        } else if (canSwim() && this.entity.touchingWater) {
            while (this.world.kindAt(bx, y, bz).has(BlockKind.WATER_BLOCK)
                    || this.world.fluidIsWater(bx, y, bz)) {
                y++;
                state = this.world.kindAt(bx, y, bz);
            }
            y--;
        } else if (this.entity.onGround) {
            y = Mth.floor(this.entity.y + 0.5);
        } else {
            int px = this.entity.blockX();
            int py = this.entity.blockY();
            int pz = this.entity.blockZ();
            while (this.world.kindAt(px, py, pz).has(BlockKind.AIR)
                    || this.world.kindAt(px, py, pz).has(BlockKind.PATHFIND_LAND)) {
                if (py <= this.world.minY) {
                    break;
                }
                py--;
            }
            y = py + 1;
        }
        int ex = this.entity.blockX();
        int ez = this.entity.blockZ();
        if (!canPathThrough(ex, y, ez)) {
            double minX = this.entity.boxMinX();
            double minZ = this.entity.boxMinZ();
            double maxX = this.entity.boxMaxX();
            double maxZ = this.entity.boxMaxZ();
            if (canPathThrough(Mth.floor(minX), y, Mth.floor(minZ))) {
                return getStart(Mth.floor(minX), y, Mth.floor(minZ));
            }
            if (canPathThrough(Mth.floor(minX), y, Mth.floor(maxZ))) {
                return getStart(Mth.floor(minX), y, Mth.floor(maxZ));
            }
            if (canPathThrough(Mth.floor(maxX), y, Mth.floor(minZ))) {
                return getStart(Mth.floor(maxX), y, Mth.floor(minZ));
            }
            if (canPathThrough(Mth.floor(maxX), y, Mth.floor(maxZ))) {
                return getStart(Mth.floor(maxX), y, Mth.floor(maxZ));
            }
        }
        return getStart(ex, y, ez);
    }

    protected PNode getStart(int x, int y, int z) {
        PNode node = getNode(x, y, z);
        node.type = getNodeType(this.entity, x, y, z);
        node.penalty = this.entity.getPathfindingPenalty(node.type);
        return node;
    }

    protected boolean canPathThrough(int x, int y, int z) {
        Pnt type = getNodeType(this.entity, x, y, z);
        return type == Pnt.OPEN || this.entity.getPathfindingPenalty(type) >= 0.0f;
    }

    @Override
    public TargetNode getNode(double x, double y, double z) {
        return asTargetNode(getNode(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
    }

    // ------------------------------------------------------------------ getSuccessors

    @Override
    public int getSuccessors(PNode[] out, PNode node) {
        int count = 0;
        int x = node.x;
        int y = node.y;
        int z = node.z;
        Pnt above = getNodeType(this.entity, x, y + 1, z);
        Pnt here = getNodeType(this.entity, x, y, z);
        int maxYStep = 0;
        if (!(this.entity.getPathfindingPenalty(above) < 0.0f) && here != Pnt.STICKY_HONEY) {
            maxYStep = Mth.floor(Math.max(1.0f, this.entity.stepHeight));
        }
        double feetY = getFeetY(x, y, z);

        PNode south = getPathNode(x, y + 1, z + 1, maxYStep, feetY, Dir.SOUTH, here);
        if (isValidAdjacentSuccessor(south, node)) {
            out[count++] = south;
        }
        PNode west = getPathNode(x - 1, y + 1, z, maxYStep, feetY, Dir.WEST, here);
        if (isValidAdjacentSuccessor(west, node)) {
            out[count++] = west;
        }
        PNode east = getPathNode(x + 1, y + 1, z, maxYStep, feetY, Dir.EAST, here);
        if (isValidAdjacentSuccessor(east, node)) {
            out[count++] = east;
        }
        PNode north = getPathNode(x, y + 1, z - 1, maxYStep, feetY, Dir.NORTH, here);
        if (isValidAdjacentSuccessor(north, node)) {
            out[count++] = north;
        }

        PNode northWest = getPathNode(x - 1, y + 1, z - 1, maxYStep, feetY, Dir.NORTH, here);
        if (isValidDiagonalSuccessor(node, west, north, northWest)) {
            out[count++] = northWest;
        }
        PNode northEast = getPathNode(x + 1, y + 1, z - 1, maxYStep, feetY, Dir.NORTH, here);
        if (isValidDiagonalSuccessor(node, east, north, northEast)) {
            out[count++] = northEast;
        }
        PNode southWest = getPathNode(x - 1, y + 1, z + 1, maxYStep, feetY, Dir.SOUTH, here);
        if (isValidDiagonalSuccessor(node, west, south, southWest)) {
            out[count++] = southWest;
        }
        PNode southEast = getPathNode(x + 1, y + 1, z + 1, maxYStep, feetY, Dir.SOUTH, here);
        if (isValidDiagonalSuccessor(node, east, south, southEast)) {
            out[count++] = southEast;
        }

        if (this.amphibious) {
            count = amphibiousExtras(out, count, node, here, maxYStep, feetY);
        }
        return count;
    }

    /** AmphibiousPathNodeMaker.getSuccessors 追加的 UP / DOWN 两个邻居（规格第 8.3 节）。 */
    private int amphibiousExtras(PNode[] out, int count, PNode node, Pnt here, int maxYStep, double feetY) {
        int x = node.x;
        int y = node.y;
        int z = node.z;
        PNode up = getPathNode(x, y + 1, z, Math.max(0, maxYStep - 1), feetY, Dir.UP, here);
        PNode down = getPathNode(x, y - 1, z, maxYStep, feetY, Dir.DOWN, here);
        if (isValidAquaticAdjacentSuccessor(up, node)) {
            out[count++] = up;
        }
        if (isValidAquaticAdjacentSuccessor(down, node) && here != Pnt.TRAPDOOR) {
            out[count++] = down;
        }
        for (int i = 0; i < count; i++) {
            PNode n = out[i];
            if (n.type == Pnt.WATER && this.penalizeDeepWater && n.y < this.world.seaLevel - 10) {
                n.penalty += 1.0f;
            }
        }
        return count;
    }

    private boolean isValidAquaticAdjacentSuccessor(PNode target, PNode host) {
        return isValidAdjacentSuccessor(target, host) && target.type == Pnt.WATER;
    }

    /** LandPathNodeMaker.isValidAdjacentSuccessor（规格第 2.3 节）。 */
    protected boolean isValidAdjacentSuccessor(PNode target, PNode host) {
        if (target == null) {
            return false;
        }
        if (target.visited) {
            return false;
        }
        return target.penalty >= 0.0f || host.penalty < 0.0f;
    }

    /** LandPathNodeMaker.isValidDiagonalSuccessor（规格第 2.4 节）。 */
    protected boolean isValidDiagonalSuccessor(PNode host, PNode sideA, PNode sideB, PNode diag) {
        if (diag == null || sideB == null || sideA == null) {
            return false;
        }
        if (diag.visited) {
            return false;
        }
        if (sideB.y > host.y) {
            return false;
        }
        if (sideA.y > host.y) {
            return false;
        }
        if (sideA.type == Pnt.WALKABLE_DOOR) {
            return false;
        }
        if (sideB.type == Pnt.WALKABLE_DOOR) {
            return false;
        }
        if (diag.type == Pnt.WALKABLE_DOOR) {
            return false;
        }
        boolean flag = sideB.type == Pnt.FENCE && sideA.type == Pnt.FENCE
                && (double) this.entity.width < 0.5;
        if (diag.penalty < 0.0f) {
            return false;
        }
        if (sideB.y >= host.y && sideB.penalty < 0.0f && flag) {
            return false;
        }
        if (sideA.y >= host.y && sideA.penalty < 0.0f && flag) {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ getPathNode

    /** LandPathNodeMaker.getPathNode（规格第 7.1 节）。 */
    protected PNode getPathNode(int x, int y, int z, int maxYStep, double prevFeetY, int dir, Pnt nodeType) {
        PNode result = null;
        double feetY = getFeetY(x, y, z);
        if (feetY - prevFeetY > getStepHeight()) {
            return null;
        }
        Pnt type = getNodeType(this.entity, x, y, z);
        float penalty = this.entity.getPathfindingPenalty(type);
        double halfWidth = (double) this.entity.width / 2.0;
        if (penalty >= 0.0f) {
            result = getNodeWith(x, y, z, type, penalty);
        }
        if (isBlockedType(nodeType) && result != null && result.penalty >= 0.0f && !isBlockedNode(result)) {
            result = null;
        }
        if (type == Pnt.WALKABLE || (isAmphibious() && type == Pnt.WATER)) {
            return result;
        }
        if (result == null || result.penalty < 0.0f) {
            if (maxYStep > 0
                    && !(type == Pnt.FENCE && !canWalkOverFences())
                    && type != Pnt.UNPASSABLE_RAIL
                    && type != Pnt.TRAPDOOR
                    && type != Pnt.POWDER_SNOW) {
                PNode up = getPathNode(x, y + 1, z, maxYStep - 1, prevFeetY, dir, nodeType);
                if (up != null && (up.type == Pnt.OPEN || up.type == Pnt.WALKABLE)
                        && this.entity.width < 1.0f) {
                    double dx = (x - Dir.offX(dir)) + 0.5;
                    double dz = (z - Dir.offZ(dir)) + 0.5;
                    Box box = new Box(
                            dx - halfWidth,
                            getFeetY(Mth.floor(dx), y + 1, Mth.floor(dz)) + 0.001,
                            dz - halfWidth,
                            dx + halfWidth,
                            (double) this.entity.height + getFeetY(up.x, up.y, up.z) - 0.002,
                            dz + halfWidth);
                    if (checkBoxCollision(box)) {
                        result = null;
                    }
                }
            }
        }
        if (!isAmphibious() && type == Pnt.WATER && !canSwim()) {
            if (getNodeType(this.entity, x, y - 1, z) != Pnt.WATER) {
                return result;
            }
            while (y > this.world.minY) {
                y--;
                type = getNodeType(this.entity, x, y, z);
                if (type != Pnt.WATER) {
                    return result;
                }
                result = getNodeWith(x, y, z, type, this.entity.getPathfindingPenalty(type));
            }
        }
        if (type == Pnt.OPEN) {
            int fall = 0;
            int y0 = y;
            while (type == Pnt.OPEN) {
                y--;
                if (y < this.world.minY) {
                    return getBlockedNode(x, y0, z);
                }
                int previousFall = fall++;
                if (previousFall >= this.entity.safeFallDistance) {
                    return getBlockedNode(x, y, z);
                }
                type = getNodeType(this.entity, x, y, z);
                penalty = this.entity.getPathfindingPenalty(type);
                if (type != Pnt.OPEN && penalty >= 0.0f) {
                    result = getNodeWith(x, y, z, type, penalty);
                    break;
                }
                if (penalty < 0.0f) {
                    return getBlockedNode(x, y, z);
                }
            }
        }
        if (isBlockedType(type) && result == null) {
            result = getNode(x, y, z);
            result.visited = true;
            result.type = type;
            result.penalty = type.getDefaultPenalty();
        }
        return result;
    }

    protected PNode getNodeWith(int x, int y, int z, Pnt type, float penalty) {
        PNode node = getNode(x, y, z);
        node.type = type;
        node.penalty = Math.max(node.penalty, penalty);
        return node;
    }

    protected PNode getBlockedNode(int x, int y, int z) {
        PNode node = getNode(x, y, z);
        node.type = Pnt.BLOCKED;
        node.penalty = -1.0f;
        return node;
    }

    protected static boolean isBlockedType(Pnt type) {
        return type == Pnt.FENCE || type == Pnt.DOOR_WOOD_CLOSED || type == Pnt.DOOR_IRON_CLOSED;
    }

    /** LandPathNodeMaker.isBlocked(PathNode)（规格第 7.1 节）。 */
    protected boolean isBlockedNode(PNode node) {
        Box box = this.entity.boundingBox();
        double vx = node.x - this.entity.x + box.getLengthX() / 2.0;
        double vy = node.y - this.entity.y + box.getLengthY() / 2.0;
        double vz = node.z - this.entity.z + box.getLengthZ() / 2.0;
        double length = Math.sqrt(vx * vx + vy * vy + vz * vz);
        int steps = Mth.ceil(length / box.getAverageSideLength());
        // 原版是 v.multiply((double)(1.0f / (float)steps))；steps == 0 时结果是 +Inf/NaN，
        // 循环不执行 —— 这里刻意保持同样的语义，不加保护分支。
        double scale = (double) (1.0f / (float) steps);
        vx *= scale;
        vy *= scale;
        vz *= scale;
        Box current = box;
        for (int i = 1; i <= steps; i++) {
            current = current.offset(vx, vy, vz);
            if (checkBoxCollision(current)) {
                return false;
            }
        }
        return true;
    }

    /** LandPathNodeMaker.checkBoxCollision —— 原版带 Box 结果缓存，但缓存是纯记忆化，语义等价。 */
    protected boolean checkBoxCollision(Box box) {
        return !this.world.isSpaceEmpty(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    protected double getStepHeight() {
        return Math.max(1.125, (double) this.entity.stepHeight);
    }

    /** LandPathNodeMaker.getFeetY(BlockPos)（实例方法）。 */
    public double getFeetY(int x, int y, int z) {
        if ((canSwim() || isAmphibious()) && this.world.fluidIsWater(x, y, z)) {
            return (double) y + 0.5;
        }
        return this.world.staticFeetY(x, y, z);
    }
}
