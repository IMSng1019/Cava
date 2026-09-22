package cava.shape;

/**
 * 常驻形状表的实测规模与耗时（**真实数字，探针/日志/文档共用同一份**）。
 *
 * <p>字段名刻意与 docs 里的报告项一一对应：真实状态数 / 形状数 / 点表规模 / 位图规模 / 构建耗时 / 上传耗时。
 */
public final class ShapeTableStats {

    /** 状态总数（= {@code Block.STATE_IDS.size()}）。 */
    public int stateCount;
    /** 有空碰撞形状的状态数。 */
    public int statesWithShape;
    /** 不同 {@code VoxelSet} 实例的个数（= 去重后的"形状数"，身份用 {@code ==}）。 */
    public int distinctVoxelSets;
    /** 记录总数（= stateCount）。 */
    public int recordCount;
    /** 点表 double 总数（EXPLICIT 形状贡献 sizeX+sizeY+sizeZ+3，FRACTIONAL 贡献 0）。 */
    public int pointTotal;
    /** 位图 uint64 总数。 */
    public int bitWordTotal;
    /** 体素总数（诊断：位图规模的真实来源）。 */
    public long cellTotal;
    /** EXPLICIT / FRACTIONAL 各多少个状态。 */
    public int explicitStates;
    public int fractionalStates;
    /** 建表耗时（纳秒）。 */
    public long buildNanos;
    /** 上传耗时（纳秒，只含 FFM 调用本身）。 */
    public long uploadNanos;
    /** 上传后原生侧记录数（回执，用来证明"传上去的就是建出来的"）。 */
    public int uploadedRecords = -1;

    public String report() {
        return "形状表: 状态=" + stateCount
                + " 有形状=" + statesWithShape
                + " 不同VoxelSet=" + distinctVoxelSets
                + " 记录=" + recordCount
                + " 点表double=" + pointTotal
                + " 位图uint64=" + bitWordTotal
                + " 体素=" + cellTotal
                + " (EXPLICIT=" + explicitStates + " FRACTIONAL=" + fractionalStates + ")"
                + " 构建=" + ms(buildNanos) + "ms 上传=" + ms(uploadNanos) + "ms"
                + " 回执记录=" + uploadedRecords;
    }

    private static String ms(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }
}
