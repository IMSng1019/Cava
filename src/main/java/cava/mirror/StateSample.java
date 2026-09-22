package cava.mirror;

/**
 * 单个方块状态的**可变采样缓冲**（{@link StateProbe} 的输出）。
 *
 * <p>故意做成可复用对象：状态表构建要跑成千上万次，每次分配数组既慢又制造 GC 压力。
 * 不引用任何 Minecraft 类型。
 */
public final class StateSample {

    /** 已置位的 flags（{@link MirrorFlags}）。 */
    public int flags;

    /**
     * {@code getCommonNodeType(state)} 的结果序号（{@link PathTypes}）。
     *
     * <p><b>语义（本轮最重要的定义，见 docs/CAVA-mirror-notes.md §2）</b>：它**只**是
     * {@code LandPathNodeMaker.getCommonNodeType}（spec §5.4.6 的 16 步）的结果 ——
     * 那 16 步**完全不含实体上下文、也不含位置上下文**（只用 state + 该 pos 的流体）。
     * 实体相关的部分（开门能力/越栅栏/体型/惩罚表）走 {@link MirrorFlags} 的位 + 生物档案，
     * 位置相关的部分（下方方块、3×3×3 邻居）由原生侧按区域镜像自己算。
     *
     * <p><b>内核当前不读这个字段</b>（实测：{@code cava_pf_kernel.cpp} 只用 flags 推类型），
     * 这里照填是为了文档完整 + 将来可能启用。
     */
    public int commonType = PathTypes.BLOCKED;

    /** 碰撞盒个数（扁平 AABB，非体素近似）。 */
    public int boxCount;

    private float[] boxCoords = new float[6 * 4];

    /** 复位（保留已分配的数组）。 */
    public void reset() {
        flags = 0;
        commonType = PathTypes.BLOCKED;
        boxCount = 0;
    }

    /** 置位/清位。 */
    public void set(MirrorFlags.Pred pred, boolean value) {
        flags = MirrorFlags.with(flags, pred, value);
    }

    /**
     * 追加一个碰撞盒（**相对方块原点**，与 {@code CavaCollisionBox} 的字段顺序一致）。
     *
     * <p>只收 {@code min < max} 的盒；原版 {@code VoxelShape} 的盒一定满足，
     * 但空形状 / 退化盒要在调用方就过滤掉（内核用 {@code box_count==0} 判"无碰撞"）。
     */
    public void addBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int need = (boxCount + 1) * 6;
        if (need > boxCoords.length) {
            float[] bigger = new float[Math.max(need, boxCoords.length * 2)];
            System.arraycopy(boxCoords, 0, bigger, 0, boxCount * 6);
            boxCoords = bigger;
        }
        int o = boxCount * 6;
        boxCoords[o] = minX;
        boxCoords[o + 1] = minY;
        boxCoords[o + 2] = minZ;
        boxCoords[o + 3] = maxX;
        boxCoords[o + 4] = maxY;
        boxCoords[o + 5] = maxZ;
        boxCount++;
    }

    /** 第 i 个盒的第 c 个分量（c = 0..5，顺序 minX,minY,minZ,maxX,maxY,maxZ）。 */
    public float box(int i, int c) {
        return boxCoords[i * 6 + c];
    }

    /** 第 i 个盒的 6 个分量拷进 out[0..5]。 */
    public void boxInto(int i, float[] out) {
        System.arraycopy(boxCoords, i * 6, out, 0, 6);
    }
}
