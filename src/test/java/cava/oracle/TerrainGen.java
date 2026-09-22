package cava.oracle;

/**
 * 测试地形生成器。**完全由 (seed, scenario, 尺寸) 决定**，规格第 10.6 节冻结了算法。
 *
 * <p>调色板固定 16 项，下标 0 是 OUT_OF_WORLD（规格第 9.3 节的越界约定）。
 */
public final class TerrainGen {
    public static final int OUT_OF_WORLD = 0;
    public static final int AIR = 1;
    public static final int STONE = 2;
    public static final int DIRT = 3;
    public static final int SLAB = 4;
    public static final int WATER = 5;
    public static final int LAVA = 6;
    public static final int DOOR_CLOSED = 7;
    public static final int DOOR_OPEN = 8;
    public static final int FENCE = 9;
    public static final int SCAFFOLDING = 10;
    public static final int LEAVES = 11;
    public static final int HONEY = 12;
    public static final int RAIL = 13;
    public static final int CACTUS = 14;
    public static final int BUSH = 15;

    /** 场景编号（写进向量文件）。 */
    public static final int SC_FLAT = 0;
    public static final int SC_OBSTACLES = 1;
    public static final int SC_STAIRS = 2;
    public static final int SC_WATER = 3;
    public static final int SC_LAVA = 4;
    public static final int SC_DOORS = 5;
    public static final int SC_FENCE = 6;
    public static final int SC_SCAFFOLDING = 7;
    public static final int SC_MAZE = 8;
    public static final int SC_MIXED = 9;
    public static final int SCENARIO_COUNT = 10;

    public static final BlockKind[] PALETTE = buildPalette();

    private TerrainGen() {}

    private static BlockKind[] buildPalette() {
        BlockKind[] p = new BlockKind[16];
        // 0 OUT_OF_WORLD：air、LAND 可通行、无流体、无碰撞
        p[OUT_OF_WORLD] = new BlockKind(BlockKind.AIR | BlockKind.PATHFIND_LAND, BlockKind.FLUID_NONE, 0.0f, 0.0f);
        p[AIR] = new BlockKind(BlockKind.AIR | BlockKind.PATHFIND_LAND, BlockKind.FLUID_NONE, 0.0f, 0.0f);
        p[STONE] = new BlockKind(0, BlockKind.FLUID_NONE, 0.0f, 1.0f);
        p[DIRT] = new BlockKind(0, BlockKind.FLUID_NONE, 0.0f, 1.0f);
        p[SLAB] = new BlockKind(BlockKind.PATHFIND_LAND, BlockKind.FLUID_NONE, 0.0f, 0.5f);
        p[WATER] = new BlockKind(BlockKind.WATER_BLOCK | BlockKind.PATHFIND_LAND,
                BlockKind.FLUID_WATER, 0.0f, 0.0f);
        p[LAVA] = new BlockKind(0, BlockKind.FLUID_LAVA, 0.0f, 0.0f);
        p[DOOR_CLOSED] = new BlockKind(BlockKind.DOOR | BlockKind.DOOR_HAND, BlockKind.FLUID_NONE, 0.0f, 0.0f);
        p[DOOR_OPEN] = new BlockKind(BlockKind.DOOR | BlockKind.DOOR_OPEN | BlockKind.PATHFIND_LAND,
                BlockKind.FLUID_NONE, 0.0f, 0.0f);
        p[FENCE] = new BlockKind(BlockKind.FENCE_TAG, BlockKind.FLUID_NONE, 0.0f, 1.5f);
        p[SCAFFOLDING] = new BlockKind(BlockKind.PATHFIND_LAND, BlockKind.FLUID_NONE, 0.875f, 1.0f);
        p[LEAVES] = new BlockKind(BlockKind.LEAVES, BlockKind.FLUID_NONE, 0.0f, 1.0f);
        p[HONEY] = new BlockKind(BlockKind.HONEY, BlockKind.FLUID_NONE, 0.0f, 1.0f);
        p[RAIL] = new BlockKind(BlockKind.RAIL | BlockKind.PATHFIND_LAND, BlockKind.FLUID_NONE, 0.0f, 0.0625f);
        p[CACTUS] = new BlockKind(BlockKind.CACTUS_OR_BERRY, BlockKind.FLUID_NONE, 0.0f, 1.0f);
        p[BUSH] = new BlockKind(BlockKind.PATHFIND_LAND, BlockKind.FLUID_NONE, 0.0f, 0.0f);
        return p;
    }

    /**
     * 生成一块地形。全部随机性来自 seed；调用顺序固定，移植方照抄即可。
     */
    public static Terrain generate(long seed, int scenario, int originX, int originY, int originZ,
                                   int sizeX, int sizeY, int sizeZ, int groundY, int seaLevel) {
        Xorshift rng = new Xorshift(seed);
        byte[] blocks = new byte[sizeX * sizeY * sizeZ];
        // 基础：y < groundY -> DIRT，y == groundY -> STONE，其余 AIR
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int worldY = originY + y;
                    byte v = AIR;
                    if (worldY < groundY) {
                        v = DIRT;
                    } else if (worldY == groundY) {
                        v = STONE;
                    }
                    blocks[(y * sizeZ + z) * sizeX + x] = v;
                }
            }
        }
        switch (scenario) {
            case SC_FLAT -> { /* 什么都不加 */ }
            case SC_OBSTACLES -> {
                int n = 12 + rng.nextInt(24);
                for (int i = 0; i < n; i++) {
                    int x = rng.nextInt(sizeX);
                    int z = rng.nextInt(sizeZ);
                    int h = 1 + rng.nextInt(3);
                    int kind = rng.nextInt(4);
                    byte b = switch (kind) {
                        case 0 -> (byte) STONE;
                        case 1 -> (byte) FENCE;
                        case 2 -> (byte) LEAVES;
                        default -> (byte) BUSH;
                    };
                    for (int k = 0; k < h; k++) {
                        set(blocks, sizeX, sizeY, sizeZ, x, groundY + 1 + k - originY, z, b);
                    }
                }
            }
            case SC_STAIRS -> {
                int dir = rng.nextInt(2);
                int len = 4 + rng.nextInt(4);
                int x0 = 2 + rng.nextInt(Math.max(1, sizeX - 6));
                int z0 = 2 + rng.nextInt(Math.max(1, sizeZ - 6));
                for (int k = 0; k < len; k++) {
                    int x = dir == 0 ? x0 + k : x0;
                    int z = dir == 0 ? z0 : z0 + k;
                    for (int dz = 0; dz < 3; dz++) {
                        for (int dx = 0; dx < 3; dx++) {
                            int px = x + (dir == 1 ? dx : 0);
                            int pz = z + (dir == 0 ? dz : 0);
                            set(blocks, sizeX, sizeY, sizeZ, px, groundY + 1 + k - originY, pz, (byte) SLAB);
                        }
                    }
                }
            }
            case SC_WATER -> {
                int cx = 3 + rng.nextInt(Math.max(1, sizeX - 8));
                int cz = 3 + rng.nextInt(Math.max(1, sizeZ - 8));
                int rx = 2 + rng.nextInt(3);
                int rz = 2 + rng.nextInt(3);
                for (int x = cx - rx; x <= cx + rx; x++) {
                    for (int z = cz - rz; z <= cz + rz; z++) {
                        set(blocks, sizeX, sizeY, sizeZ, x, groundY - originY, z, (byte) WATER);
                        set(blocks, sizeX, sizeY, sizeZ, x, groundY + 1 - originY, z, (byte) WATER);
                    }
                }
            }
            case SC_LAVA -> {
                int cx = 3 + rng.nextInt(Math.max(1, sizeX - 8));
                int cz = 3 + rng.nextInt(Math.max(1, sizeZ - 8));
                for (int x = cx - 1; x <= cx + 1; x++) {
                    for (int z = cz - 1; z <= cz + 1; z++) {
                        set(blocks, sizeX, sizeY, sizeZ, x, groundY - originY, z, (byte) LAVA);
                    }
                }
            }
            case SC_DOORS -> {
                int wallX = 3 + rng.nextInt(Math.max(1, sizeX - 6));
                int gap = rng.nextInt(sizeZ);
                boolean open = rng.nextBoolean();
                for (int z = 0; z < sizeZ; z++) {
                    for (int k = 1; k <= 2; k++) {
                        set(blocks, sizeX, sizeY, sizeZ, wallX, groundY + k - originY, z, (byte) STONE);
                    }
                    set(blocks, sizeX, sizeY, sizeZ, wallX, groundY + 1 - originY, z, (byte) STONE);
                }
                for (int k = 1; k <= 2; k++) {
                    set(blocks, sizeX, sizeY, sizeZ, wallX, groundY + k - originY, gap,
                            open ? (byte) DOOR_OPEN : (byte) DOOR_CLOSED);
                }
            }
            case SC_FENCE -> {
                int z0 = 2 + rng.nextInt(Math.max(1, sizeZ - 4));
                for (int x = 1; x < sizeX - 1; x++) {
                    set(blocks, sizeX, sizeY, sizeZ, x, groundY + 1 - originY, z0, (byte) FENCE);
                }
                int gap = 1 + rng.nextInt(Math.max(1, sizeX - 2));
                set(blocks, sizeX, sizeY, sizeZ, gap, groundY + 1 - originY, z0, (byte) AIR);
            }
            case SC_SCAFFOLDING -> {
                int x0 = 2 + rng.nextInt(Math.max(1, sizeX - 6));
                int z0 = 2 + rng.nextInt(Math.max(1, sizeZ - 6));
                int h = 1 + rng.nextInt(3);
                for (int k = 0; k < h; k++) {
                    for (int x = x0; x < x0 + 3 && x < sizeX; x++) {
                        for (int z = z0; z < z0 + 3 && z < sizeZ; z++) {
                            set(blocks, sizeX, sizeY, sizeZ, x, groundY + 1 + k - originY, z,
                                    (byte) SCAFFOLDING);
                        }
                    }
                }
            }
            case SC_MAZE -> {
                for (int x = 0; x < sizeX; x++) {
                    for (int z = 0; z < sizeZ; z++) {
                        boolean wall = (x % 3 == 0 && z % 3 != 1) || (z % 3 == 0 && x % 3 != 1);
                        if (wall && rng.nextInt(10) < 8) {
                            for (int k = 1; k <= 2; k++) {
                                set(blocks, sizeX, sizeY, sizeZ, x, groundY + k - originY, z, (byte) STONE);
                            }
                        }
                    }
                }
            }
            default -> {
                for (int i = 0; i < 40; i++) {
                    int x = rng.nextInt(sizeX);
                    int z = rng.nextInt(sizeZ);
                    int kind = rng.nextInt(10);
                    byte b = switch (kind) {
                        case 0 -> (byte) STONE;
                        case 1 -> (byte) SLAB;
                        case 2 -> (byte) WATER;
                        case 3 -> (byte) LAVA;
                        case 4 -> (byte) FENCE;
                        case 5 -> (byte) SCAFFOLDING;
                        case 6 -> (byte) RAIL;
                        case 7 -> (byte) HONEY;
                        case 8 -> (byte) CACTUS;
                        default -> (byte) BUSH;
                    };
                    int h = 1 + rng.nextInt(3);
                    for (int k = 0; k < h; k++) {
                        set(blocks, sizeX, sizeY, sizeZ, x, groundY + 1 + k - originY, z, b);
                    }
                }
            }
        }
        return new Terrain(originX, originY, originZ, sizeX, sizeY, sizeZ, originY, seaLevel,
                PALETTE, blocks);
    }

    private static void set(byte[] blocks, int sizeX, int sizeY, int sizeZ, int x, int y, int z, byte v) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return;
        }
        blocks[(y * sizeZ + z) * sizeX + x] = v;
    }
}
