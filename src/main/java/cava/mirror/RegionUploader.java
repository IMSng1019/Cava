package cava.mirror;

import java.lang.foreign.MemorySegment;

/**
 * 原生出口的抽象（抽出来是为了：单测能用假实现；性能分解能逐段计时）。
 */
public interface RegionUploader {

    /** 原生是否可用（不可用 → 调用方回退）。 */
    boolean available();

    /** 当前句柄。 */
    long handle();

    /**
     * {@code cava_region_upload}。返回原生错误码（0 = OK）。
     *
     * @param ids {@code dimX*dimY*dimZ} 个 int32 的段（{@code allocateArray} 分配，与 count 同源）
     */
    int upload(long handle, int dimX, int dimY, int dimZ,
               int originX, int originY, int originZ, MemorySegment ids, int count);

    /** {@code cava_region_clear}（幂等）。 */
    int clear(long handle);

    /** {@code cava_mob_profile_upload}。 */
    int mobProfileUpload(long handle, MemorySegment profile);
}
