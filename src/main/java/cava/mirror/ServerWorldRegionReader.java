package cava.mirror;

import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * 真实服务器世界的区域读取（数据来源：{@code ServerWorld → WorldChunk.getSectionArray() →
 * ChunkSection.getBlockState → Block.getRawIdFromState}，与契约要求一致）。
 *
 * <p><b>性能要点</b>：按 **(区块, 区段)** 取一次 {@code ChunkSection} 再扫局部坐标，
 * 避免逐方块做区块查找；空区段（{@code isEmpty()}）整段跳过（全空气），这是最快的一档。
 *
 * <p>区块未加载 → 抛 {@code MirrorUnavailableException}（显式失败；不猜、不静默）。
 */
public final class ServerWorldRegionReader implements RegionReader {

    private final ServerWorld world;

    public ServerWorldRegionReader(ServerWorld world) {
        this.world = world;
    }

    public ServerWorld world() {
        return world;
    }

    @Override
    public String dimensionId() {
        return world.getRegistryKey().getValue().toString();
    }

    @Override
    public int minY() {
        return world.getBottomY();
    }

    @Override
    public int maxY() {
        return world.getTopY() - 1;
    }

    @Override
    public long currentTick() {
        return world.getTime();
    }

    @Override
    public boolean isReady(int minX, int minY, int minZ, int dimX, int dimY, int dimZ) {
        for (int cx = minX >> 4; cx <= (minX + dimX - 1) >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= (minZ + dimZ - 1) >> 4; cz++) {
                if (world.getChunkManager().getWorldChunk(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void fill(int minX, int minY, int minZ, int dimX, int dimY, int dimZ, int[] out, int airStateId) {
        int total = dimX * dimY * dimZ;
        java.util.Arrays.fill(out, 0, total, airStateId);
        int bottomSection = world.getBottomSectionCoord();
        int maxX = minX + dimX - 1;
        int maxY = minY + dimY - 1;
        int maxZ = minZ + dimZ - 1;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) {
                    throw new RegionSource.MirrorUnavailableException(
                            "区块未加载: (" + cx + "," + cz + ") 维度 " + dimensionId());
                }
                ChunkSection[] sections = chunk.getSectionArray();
                for (int i = 0; i < sections.length; i++) {
                    int secBottom = (bottomSection + i) * 16;
                    int y0 = Math.max(minY, secBottom);
                    int y1 = Math.min(maxY, secBottom + 15);
                    if (y0 > y1) {
                        continue;
                    }
                    ChunkSection sec = sections[i];
                    boolean empty = sec == null || sec.isEmpty();
                    if (empty) {
                        continue; // 已经是 airStateId
                    }
                    int lx0 = Math.max(0, minX - (cx << 4));
                    int lx1 = Math.min(15, maxX - (cx << 4));
                    int lz0 = Math.max(0, minZ - (cz << 4));
                    int lz1 = Math.min(15, maxZ - (cz << 4));
                    for (int wy = y0; wy <= y1; wy++) {
                        int ly = wy - secBottom;
                        for (int wz = lz0; wz <= lz1; wz++) {
                            int wzWorld = (cz << 4) + wz;
                            int index = ((wy - minY) * dimZ + (wzWorld - minZ)) * dimX + ((cx << 4) + lx0 - minX);
                            for (int lx = lx0; lx <= lx1; lx++, index++) {
                                out[index] = Block.getRawIdFromState(sec.getBlockState(lx, ly, wz));
                            }
                        }
                    }
                }
            }
        }
    }
}
