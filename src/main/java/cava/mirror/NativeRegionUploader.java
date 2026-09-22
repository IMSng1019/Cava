package cava.mirror;

import cava.ffm.CavaNative;
import java.lang.foreign.MemorySegment;

/** {@link RegionUploader} 的真实实现：走 {@code cava.ffm.CavaNative}（已封装好，不自己写 downcall）。 */
public final class NativeRegionUploader implements RegionUploader {

    @Override
    public boolean available() {
        return CavaNative.get().available();
    }

    @Override
    public long handle() {
        return CavaNative.get().handle();
    }

    @Override
    public int upload(long handle, int dimX, int dimY, int dimZ,
                      int originX, int originY, int originZ, MemorySegment ids, int count) {
        return CavaNative.get().regionUpload(handle, dimX, dimY, dimZ, originX, originY, originZ, ids, count);
    }

    @Override
    public int clear(long handle) {
        return CavaNative.get().regionClear(handle);
    }

    @Override
    public int mobProfileUpload(long handle, MemorySegment profile) {
        return CavaNative.get().mobProfileUpload(handle, profile);
    }
}
