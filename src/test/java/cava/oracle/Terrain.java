package cava.oracle;

/**
 * 一个「内存里的 3D 世界」，是参照实现唯一的方块查询入口。
 *
 * <p>越界约定（规格第 9.3 节）：调色板下标 0 固定为 OUT_OF_WORLD —— AIR、LAND 可通行、无流体、
 * 碰撞高度 0。所有越界查询都返回下标 0。
 *
 * <p>碰撞用扁平 AABB（碰撞盒的 minY/maxY），不做体素近似。
 */
public final class Terrain {
    public final int originX;
    public final int originY;
    public final int originZ;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    public final int minY;
    public final int seaLevel;
    public final BlockKind[] palette;
    public final byte[] blocks;

    public Terrain(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
                   int minY, int seaLevel, BlockKind[] palette, byte[] blocks) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.minY = minY;
        this.seaLevel = seaLevel;
        this.palette = palette;
        this.blocks = blocks;
    }

    public int index(int x, int y, int z) {
        int lx = x - this.originX;
        int ly = y - this.originY;
        int lz = z - this.originZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= this.sizeX || ly >= this.sizeY || lz >= this.sizeZ) {
            return -1;
        }
        return (ly * this.sizeZ + lz) * this.sizeX + lx;
    }

    public int paletteIndexAt(int x, int y, int z) {
        int i = index(x, y, z);
        return i < 0 ? 0 : (this.blocks[i] & 0xFF);
    }

    public BlockKind kindAt(int x, int y, int z) {
        return this.palette[paletteIndexAt(x, y, z)];
    }

    public int fluidAt(int x, int y, int z) {
        return kindAt(x, y, z).fluid;
    }

    public boolean fluidIsWater(int x, int y, int z) {
        return fluidAt(x, y, z) == BlockKind.FLUID_WATER;
    }

    public boolean fluidIsLava(int x, int y, int z) {
        return fluidAt(x, y, z) == BlockKind.FLUID_LAVA;
    }

    public boolean fluidIsEmpty(int x, int y, int z) {
        return fluidAt(x, y, z) == BlockKind.FLUID_NONE;
    }

    /** 静态 getFeetY(BlockView, pos)：below = pos.down()；shape 空则 +0.0，否则 +shape.getMax(Y)。 */
    public double staticFeetY(int x, int y, int z) {
        return (double) (y - 1) + kindAt(x, y - 1, z).collisionMaxY;
    }

    /** World.isSpaceEmpty(entity, box) 的方块部分（只判方块碰撞，不判实体）。 */
    public boolean isSpaceEmpty(double aMinX, double aMinY, double aMinZ,
                                double aMaxX, double aMaxY, double aMaxZ) {
        int x0 = Mth.floor(aMinX);
        int x1 = Mth.floor(aMaxX);
        int y0 = Mth.floor(aMinY);
        int y1 = Mth.floor(aMaxY);
        int z0 = Mth.floor(aMinZ);
        int z1 = Mth.floor(aMaxZ);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockKind k = kindAt(x, y, z);
                    if (k.collisionMaxY <= k.collisionMinY && k.collisionMaxY <= 0.0f) {
                        continue;
                    }
                    double bMinX = x;
                    double bMinY = y + k.collisionMinY;
                    double bMinZ = z;
                    double bMaxX = x + 1.0;
                    double bMaxY = y + k.collisionMaxY;
                    double bMaxZ = z + 1.0;
                    // 原版 Box.intersects：严格不等号
                    if (aMinX < bMaxX && aMaxX > bMinX
                            && aMinY < bMaxY && aMaxY > bMinY
                            && aMinZ < bMaxZ && aMaxZ > bMinZ) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** FNV-1a 64（规格第 10.5 节），遍历顺序 z 外层、y 中层、x 内层。 */
    public long worldHash() {
        long h = 0xCBF29CE484222325L;
        for (int y = 0; y < this.sizeY; y++) {
            for (int z = 0; z < this.sizeZ; z++) {
                for (int x = 0; x < this.sizeX; x++) {
                    h ^= (long) (this.blocks[(y * this.sizeZ + z) * this.sizeX + x] & 0xFF);
                    h *= 0x100000001B3L;
                }
            }
        }
        return h;
    }
}
