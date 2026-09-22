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

    /**
     * 绑定到某个世界。
     *
     * <p><b>为什么需要这个方法（2026-09-22 补，由注入流实测暴露）</b>：镜像的实现是按世界构造的
     * （要读 {@code ServerWorld} 的区块与高度），但冻结接口里原本没有任何办法<b>把世界递进来</b>，
     * 注入流只能靠反射去撞实现的内部字段 —— 那是"靠运行期静默回退掩盖接口不对"，
     * 属于必须消灭的形态。**请实现者在这里完成绑定，调用者在每次要用之前确保已绑定到正确的世界。**
     *
     * <p>生命周期约定：一个世界一个实例；换维度/换世界时重新绑定。实现必须能处理
     * "同一世界重复绑定"（幂等）。
     */
    void bind(net.minecraft.server.world.ServerWorld world);

    /**
     * 把一份**已经填好的** {@code CavaMobProfile} 内容上传给原生，供本次求解使用。
     *
     * <p><b>权威生产者（captain 2026-09-22 裁决）</b>：
     * 档案里含实体位姿与 26 项惩罚表，而<b>只有注入点拿得到这些东西</b>（它有 {@code MobEntity} 与
     * {@code PathNodeMaker} 的上下文）。所以：
     * <ul>
     *   <li><b>生产者 = 注入流</b>（它已经把值算好了）；</li>
     *   <li><b>镜像流只负责"写进原生"</b>（它持有 `handle` 与 arena 的生命周期）。</li>
     * </ul>
     * 这样两边不会各推一份，也不需要"互斥桥"这种中间层。
     * <b>之前"镜像流负责产出档案"的设计已作废</b> —— 它拿不到位姿，只能恒返回"未就绪"。
     *
     * @param uploader 一个把已填好的档案写进给定内存段的回调（调用方负责填值，避免两边各自解释布局）
     * @return 成功与否；false 表示调用方必须回退
     */
    boolean uploadProfileForSolve(long handle, java.util.function.Consumer<java.lang.foreign.MemorySegment> uploader);

    /**
     * flags 语义对于"本类型生物"是否已就绪。
     *
     * @param caps 该生物的 {@code CAVA_NAV_*} 能力位（由注入流给出；镜像流据此判断是否需要重算）
     */
    boolean isFlagsReadyFor(int caps);

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

    /* 说明：原设计里"镜像流产出生物档案"的两个方法（isProfileReadyForSolve(profileKey) /
     * uploadProfileForSolve(handle, profileKey)）**已于 2026-09-22 作废并删除**。
     * 原因：档案需要实体位姿与惩罚表，而镜像流拿不到它们 —— 那个设计必然恒返回"未就绪"，
     * 表现就是"原生一次都不会被调用"（注入流实测 20201 次 profile-not-ready）。
     * 取代它的是上面的 uploadProfileForSolve(handle, uploader) 与 isFlagsReadyFor(int caps)：
     * **生产者是注入流，镜像流只负责写进原生。** */

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
