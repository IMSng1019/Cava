package cava.mirror;

/**
 * P1 的**镜像侧窄接口**（captain 冻结，2026-09-22）。
 *
 * <p>为什么要单独冻结这一层：镜像侧（方块状态表 + 区域推送）与注入侧（从原版调用点取输入、
 * 调原生、把结果转回 {@code Path}）由**两个并行的流**分别实现。没有这层接口，两边会长出
 * 两套不兼容的形状，整合时必然返工。
 *
 * <p><b>职责边界</b>：
 * <ul>
 *   <li>本接口的**实现者** = 镜像流（{@code cava.mirror}）。它知道怎么从真实世界读方块、
 *       怎么把状态表推给原生。</li>
 *   <li>本接口的**调用者** = 注入流（{@code cava.hook}）。它**只**通过这里的方法拿数据，
 *       不自己去读 {@code BlockState}，也不自己维护状态表。</li>
 * </ul>
 *
 * <p><b>纪律</b>：
 * <ul>
 *   <li>方块状态一律用 **state id**（= {@code Block.getRawIdFromState}）表达，
 *       <b>禁止暴露 {@code BlockState} 对象</b> —— 调用方一旦拿到对象就可能拿它当键，
 *       而 FerriteCore 的去重会让内容相同的状态共享实例。</li>
 *   <li>所有方法都可能因为"原生不可用 / 世界未就绪 / 区域越界"而返回<b>失败</b>；
 *       调用方必须把失败当作"回退原逻辑"，<b>不要</b>把它当作可忽略的小错。</li>
 * </ul>
 */
public interface RegionSource {

    /** 一次区域推送的结果。 */
    record Pushed(int dimX, int dimY, int dimZ,
                  int originX, int originY, int originZ,
                  int stateCount, long elapsedNanos) {
    }

    /**
     * 把"以 {@code (minX,minY,minZ)} 为原点、尺寸 {@code (dimX,dimY,dimZ)}"的长方体推给原生。
     *
     * <p>索引顺序必须是 <b>x 最快、y 最慢</b>（与 {@code cava_abi.h} 的
     * {@code cava_region_upload} 约定一致）。
     *
     * @return 推送结果；<b>失败时抛 {@link MirrorUnavailableException}</b>，调用方据此回退
     */
    Pushed push(int minX, int minY, int minZ, int dimX, int dimY, int dimZ);

    /** 清掉原生侧的区域缓存。幂等。 */
    void clear();

    /**
     * 该生物档案对应的**方块状态 flags 语义**是否已就绪。
     *
     * <p>原版 {@code getCommonNodeType} 依赖实体上下文（开门能力、能否越过栅栏、体型……），
     * 所以"一个状态一个 pathType"不足以表达。镜像流负责把这个差异消化掉；
     * 在它确认"这个 profile 语义已就绪"之前，<b>注入流必须回退原逻辑</b>。
     *
     * <p><b>注意通道</b>：原生内核**只读 {@code CavaStateRecord.flags}**，
     * <b>不读</b> {@code path_type_idx} / {@code malus}（2026-09-22 由镜像流 grep 实证）。
     * 所以"就绪"指的是 <b>flags 的 19 个谓词位已按该 profile 填对</b>，
     * 而不是某张 pathType 表已上传。
     *
     * @param profileKey 生物档案的稳定标识（镜像流决定其构成，例如 caps 的组合）
     */
    boolean isProfileReadyForSolve(long profileKey);

    /**
     * 上传当前生物的档案（{@code CavaMobProfile}）以供**本次求解**使用。
     *
     * <p><b>语义（captain 2026-09-22 裁决，不要按字面之外的方式理解）</b>：
     * {@code CavaMobProfile} 里含实体的<b>当前位姿</b>（{@code start_x/y/z}），而
     * {@code CavaPathRequest} 里<b>没有</b>起点字段。因此该档案
     * <b>必须在每次求解前重新上传</b>，<b>不得跨 tick 复用</b> —— 否则起点是陈旧的，
     * 会算出一条"看起来正常但起点错了"的路径。
     *
     * <p>成本是可接受的：192 字节 + 一次 FFM 调用（实测边界 ≈ 14–16 ns/次），
     * 相对一次完整寻路可忽略。
     *
     * @return 成功与否；false 表示调用方必须回退
     */
    boolean uploadProfileForSolve(long handle, long profileKey);

    /** 镜像侧不可用（原生关闭 / 表未上传 / 世界未就绪 / 参数非法）。 */
    class MirrorUnavailableException extends RuntimeException {
        public MirrorUnavailableException(String message) {
            super(message);
        }

        public MirrorUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
