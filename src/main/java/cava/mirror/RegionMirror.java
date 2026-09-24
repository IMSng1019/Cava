package cava.mirror;

import cava.ffm.CavaLayouts;
import cava.ffm.CavaNative;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区域镜像：{@link RegionSource} 的实现（方块状态表 + 区域推送 + 档案落盘）。
 *
 * <p><b>职责边界（captain 2026-09-22 返工后的契约）</b>：
 * <ul>
 *   <li>区域推送：本类负责（读世界 → 填 id → FFM 上传）；</li>
 *   <li>生物档案：**生产者是注入流**（它有实体位姿与 26 项惩罚表），本类只负责
 *       "在正确的 arena 生命周期里把已填好的 {@code CavaMobProfile} 写进原生"；</li>
 *   <li>flags 就绪判定：{@link #isFlagsReadyFor(int)}。</li>
 * </ul>
 *
 * <p><b>并发约束（captain 用字节码核过，已写进文档）</b>：原版 {@code EntityNavigation.findPathToAny}
 * 是直接 {@code invokevirtual} 调 {@code PathNodeNavigator.findPathToAny}，整条链上没有 executor
 * 交接 ⇒ **原生路径假定在主线程（调用线程）上跑**。本类的 push/clear 因此用一把对象锁保护
 * （防御性，不是吞吐瓶颈）；若将来有 mod 把寻路挪到工作线程，这条约束要重评。
 *
 * <p><b>三个已定决策</b>：
 * <ol>
 *   <li>窗口尺寸由 {@link RegionRect#forSolve} 统一推导（边距出处写在那个类里），
 *       并有"推送耗时 vs 窗口尺寸"实测表（{@code RegionMirrorPerfTest}）；</li>
 *   <li><b>复用只在"同一次推送所在的 tick 内"生效</b>（{@link #pushReusingSameTick}）：
 *       跨 tick 复用要求失效源**完备**，而 2026-09-24 的实测证明它过去并不完备
 *       （失效方法一次都没被调用 ⇒ mob 穿墙）；现在方块变更由
 *       {@link SectionOriginRegistry}（{@code ChunkSection.setBlockState} 主钩子）驱动，
 *       但跨 tick 复用仍然**默认关**、要显式开（{@link #PROP_CROSSTICK_REUSE}）并带金丝雀计数；</li>
 *   <li>区段卸载 / 世界变更：本轮只做"最简失效"，{@link #onSectionUnloaded} /
 *       {@link #onBlockChanged} / {@link #onWorldChanged} 是留给后续脏跟踪流的**口子**。</li>
 * </ol>
 *
 * <p>所有失败（未 bind / 原生不可用 / 表未上传 / 区块未加载 / 区域过大 / 原生错误码）都抛
 * {@link MirrorUnavailableException} —— 调用方据此**回退原逻辑**，绝不静默降级。
 */
public final class RegionMirror implements RegionSource {

    /** 区域体积上限（方块数）；超过即回退。默认 2M 方块 = 8 MB int 数组。 */
    public static final String PROP_MAX_VOLUME = "cava.mirror.region.max.volume";
    /**
     * 形状守卫开关（captain 裁决 1，**默认开**）：区域里出现"位置/上下文相关碰撞形状"的方块时
     * 直接回退原逻辑。设 {@code -Dcava.mirror.shape.guard=false} 可关掉（只用于 A/B 对比）。
     */
    public static final String PROP_SHAPE_GUARD = "cava.mirror.shape.guard";
    /**
     * 跨 tick 复用开关（**默认 false**，2026-09-24 P1-FIX 加）。
     *
     * <p>打开它 = 声明"镜像失效源已经完备"。当前只有方块变更钩子
     * （{@link SectionOriginRegistry}）与换世界/换维度两个来源，**区段卸载还没有来源**
     * ⇒ 默认关。打开时必须先看 {@link #crossTickReuseHits()} / {@link #crossTickReuseBlocked()}
     * 两个金丝雀计数，并跑 {@code detour128} 的 reuse 腿做逐字段比对。
     */
    public static final String PROP_CROSSTICK_REUSE = "cava.mirror.reuse.crosstick";
    /** 默认上限。 */
    public static final int DEFAULT_MAX_VOLUME = 1 << 21;

    private static final long O_WIDTH =
            CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("width"));
    private static final long O_HEIGHT =
            CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("height"));
    private static final long O_STEP =
            CavaLayouts.MOB_PROFILE.byteOffset(MemoryLayout.PathElement.groupElement("step_height"));

    private static final Logger LOG = LoggerFactory.getLogger("cava/mirror");

    private final RegionUploader uploader;
    private final StateTableGate table;
    private final Object lock = new Object();

    /** bind() 之后才有；未 bind 时所有需要世界的操作都会显式失败。 */
    private volatile RegionReader reader;
    /** 当前绑定的世界（用身份比较实现"同世界重复绑定幂等"）。 */
    private volatile ServerWorld boundWorld;

    private int[] buffer = new int[1 << 14];

    private RegionRect lastRect;
    private String lastDim = "";
    private long lastTick = Long.MIN_VALUE;
    private int lastCells;

    private long pushes;
    private long failures;
    private long reuseSkips;
    private long reuseSameTickHits;
    private long crossTickReuseHits;
    private long crossTickReuseBlocked;
    private long cellsCopied;
    private long fillNanos;
    private long allocNanos;
    private long uploadNanos;
    private long totalNanos;
    private long invalidationCount;
    private String lastInvalidation = "(无)";
    private volatile PushDetail lastDetail;

    private long binds;
    private long rebinds;
    private long flagsReadyCalls;
    private long flagsReadyOk;
    private int lastCaps;
    private volatile String flagsNotReadyReason = "(尚未判定)";
    private long profileUploads;
    private long profileRefusals;
    private long shapeGuardRejections;
    private long shapeGuardScans;
    private long shapeGuardNanos;
    private final AtomicBoolean unknownBitsReported = new AtomicBoolean();

    public RegionMirror(RegionUploader uploader, StateTableGate table) {
        this.uploader = uploader;
        this.table = table;
    }

    /**
     * 单测/探针用：**直接给 reader**（等价于"已经 bind 到某个世界"）。
     *
     * <p>有意公开：{@code RegionReader} 是镜像侧读世界的唯一缝，测试与离线探针
     * （{@code cava.mirror.probe.McProbeMain}）都靠它脱离真实服务器跑。
     */
    public RegionMirror(RegionReader reader, RegionUploader uploader, StateTableGate table) {
        this.uploader = uploader;
        this.table = table;
        this.reader = reader;
    }

    /** 生产入口：真实原生出口 + 真实状态表。**调用方必须先 {@link #bind}。** */
    public static RegionMirror create() {
        return new RegionMirror(new NativeRegionUploader(), BlockStateTable.get());
    }

    /** 便利入口：创建并绑定到一个世界。 */
    public static RegionMirror forWorld(ServerWorld world) {
        RegionMirror m = create();
        m.bind(world);
        return m;
    }

    /** 一次推送的耗时分解（实测台账用）。 */
    public record PushDetail(RegionRect rect, long fillNanos, long allocNanos, long uploadNanos, long totalNanos) {
        /** 总耗时（毫秒，便于直接打表）。 */
        public double totalMillis() {
            return totalNanos / 1_000_000.0;
        }
    }

    // ------------------------------------------------------------------
    // 绑定（RegionSource.bind）
    // ------------------------------------------------------------------

    /**
     * 绑定世界。**同一世界重复绑定幂等**；换世界/换维度会顺手清掉原生区域缓存
     * （旧区域属于旧维度，留着只会得到"看起来正常但地形错了"的结果）。
     */
    @Override
    public void bind(ServerWorld world) {
        if (world == null) {
            throw new MirrorUnavailableException("bind(null)：调用方必须给一个 ServerWorld");
        }
        synchronized (lock) {
            if (world == boundWorld) {
                binds++;
                return;
            }
            boolean rebind = boundWorld != null;
            boundWorld = world;
            reader = new ServerWorldRegionReader(world);
            binds++;
            if (rebind) {
                rebinds++;
                clearLocked();
                invalidate("换世界/换维度");
            }
            LOG.info("[cava/mirror] bind 世界 {}（第 {} 次绑定，重绑 {} 次）",
                    world.getRegistryKey().getValue(), binds, rebinds);
        }
    }

    /** 已绑定的世界（未绑定返回 null；诊断用）。 */
    public ServerWorld boundWorld() {
        return boundWorld;
    }

    private RegionReader requireReader() {
        RegionReader r = reader;
        if (r == null) {
            throw new MirrorUnavailableException("尚未 bind(world)：镜像侧必须先绑定世界（RegionSource.bind）");
        }
        return r;
    }

    // ------------------------------------------------------------------
    // flags 就绪（RegionSource.isFlagsReadyFor）
    // ------------------------------------------------------------------

    /**
     * flags 语义对给定 {@code caps} 是否就绪。
     *
     * <p><b>结论：19 个谓词位与 caps 无关</b>（逐条理由见 docs/CAVA-mirror-notes.md §2.4）：
     * 它们全部来自 {@code getCommonNodeType} 的 16 步，而那 16 步只用 state 与该 pos 的流体；
     * 真正与实体能力有关的部分（{@code adjustNodeType} 的开门/穿门、越栅栏、两栖默认类型、
     * 惩罚表选型）由内核在**求解时**用 {@code CavaMobProfile.caps} + {@code penalty[26]} 处理
     * （内核出处：{@code common_node_type} / {@code adjust_node_type} /
     * {@code amphibious_default_node_type} / {@code node_type_raw}）。
     * 所以 caps 变化**不需要重算、也不需要让状态表失效**。
     *
     * <p>本方法只检查"镜像侧自己是否真的就绪"：表已构建+上传、表自检通过
     * （{@code commonNodeType(flags) == path_type_idx} 对全部状态成立 —— 这条能抓住位填错）、
     * 原生可用。
     */
    @Override
    public boolean isFlagsReadyFor(int caps) {
        flagsReadyCalls++;
        lastCaps = caps;
        int unknown = caps & ~NavCaps.KNOWN_MASK;
        if (unknown != 0 && unknownBitsReported.compareAndSet(false, true)) {
            // 不阻塞：caps 里出现未定义位只说明调用方用了更新的 ABI，与本镜像的位无关。
            LOG.warn("[cava/mirror] caps=0x{} 含本版本不认识的位 0x{}（不阻塞 flags 判定）",
                    Integer.toHexString(caps), Integer.toHexString(unknown));
        }
        if (!table.uploadIfNeeded()) {
            flagsNotReadyReason = "状态表未就绪: " + table.failure();
            return false;
        }
        if (!table.selfConsistent()) {
            flagsNotReadyReason = "状态表自检失败：commonNodeType(flags) 与 path_type_idx 对不上";
            LOG.error("[cava/mirror] {}", flagsNotReadyReason);
            return false;
        }
        if (!uploader.available()) {
            flagsNotReadyReason = "原生不可用";
            return false;
        }
        flagsNotReadyReason = null;
        flagsReadyOk++;
        return true;
    }

    /** 最近一次 flags 判定的结果说明（null = 就绪）。 */
    public String flagsNotReadyReason() {
        return flagsNotReadyReason;
    }

    // ------------------------------------------------------------------
    // 档案落盘（RegionSource.uploadProfileForSolve）
    // ------------------------------------------------------------------

    /**
     * 把**注入流已填好**的 {@code CavaMobProfile} 写进原生。
     *
     * <p>本类持有 arena 生命周期：分配 {@code sizeof(CavaMobProfile)}=192 字节 → 交给 {@code filler}
     * 填值 → **本地先做一次与原生同规则的校验**（width/height/step，省掉一次注定失败的 FFM 调用，
     * 且日志能指出是谁填错了）→ {@code cava_mob_profile_upload}。
     *
     * <p>注入流的推荐用法：
     * {@code mirror.uploadProfileForSolve(handle, McMobProfileCapture.filler(mob, world))}。
     */
    @Override
    public boolean uploadProfileForSolve(long handle, Consumer<MemorySegment> filler) {
        if (filler == null) {
            profileRefusals++;
            LOG.error("[cava/mirror] uploadProfileForSolve 的 filler 为 null —— 调用方必须提供填值回调（回退）");
            return false;
        }
        if (!uploader.available()) {
            profileRefusals++;
            LOG.error("[cava/mirror] 原生不可用，档案无法上传（回退）");
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(CavaLayouts.MOB_PROFILE);
            seg.fill((byte) 0);
            try {
                filler.accept(seg);
            } catch (Throwable t) {
                profileRefusals++;
                LOG.error("[cava/mirror] 填充 CavaMobProfile 的回调抛异常 —— 本次求解必须回退", t);
                return false;
            }
            float width = seg.get(ValueLayout.JAVA_FLOAT, O_WIDTH);
            float height = seg.get(ValueLayout.JAVA_FLOAT, O_HEIGHT);
            float step = seg.get(ValueLayout.JAVA_FLOAT, O_STEP);
            if (!(width > 0.0f) || !(height > 0.0f) || !(step >= 0.0f)) {
                profileRefusals++;
                LOG.error("[cava/mirror] 填好的 CavaMobProfile 非法：width={} height={} step_height={}"
                        + "（原生会返回 CAVA_ERR_ARG；这里提前拒绝，回退原逻辑）", width, height, step);
                return false;
            }
            int rc = uploader.mobProfileUpload(handle, seg);
            if (rc != CavaLayouts.CAVA_OK) {
                profileRefusals++;
                LOG.error("[cava/mirror] cava_mob_profile_upload → {}（本次求解必须回退）", CavaLayouts.errorName(rc));
                return false;
            }
            profileUploads++;
            return true;
        } catch (Throwable t) {
            profileRefusals++;
            LOG.error("[cava/mirror] 上传生物档案抛出异常（本次求解必须回退）", t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 推送
    // ------------------------------------------------------------------

    @Override
    public Pushed push(int minX, int minY, int minZ, int dimX, int dimY, int dimZ) {
        return push(new RegionRect(minX, minY, minZ, dimX, dimY, dimZ));
    }

    /** 推送一个矩形（每次都会真的重推；失败抛异常）。 */
    public Pushed push(RegionRect rect) {
        RegionReader r = requireReader();
        synchronized (lock) {
            long begin = System.nanoTime();
            int maxVolume = Integer.getInteger(PROP_MAX_VOLUME, DEFAULT_MAX_VOLUME);
            if (rect.volume() > maxVolume) {
                failures++;
                throw new MirrorUnavailableException("区域过大: " + rect.volume() + " > " + maxVolume + " "
                        + rect.describe());
            }
            if (!table.uploadIfNeeded()) {
                failures++;
                throw new MirrorUnavailableException("状态表未就绪: " + table.failure());
            }
            if (!uploader.available()) {
                failures++;
                throw new MirrorUnavailableException("原生不可用");
            }
            if (!r.isReady(rect.minX(), rect.minY(), rect.minZ(), rect.dimX(), rect.dimY(), rect.dimZ())) {
                failures++;
                throw new MirrorUnavailableException("区块未加载，无法推送区域: " + rect.describe());
            }
            int cells = (int) rect.volume();
            ensureBuffer(cells);
            // 方块变更失效源：本次推送覆盖到的区段要**全部登记**（含空区段），
            // 这样 ChunkSection.setBlockState 的钩子才能把"区段局部坐标"还原成世界坐标。
            SectionOriginRegistry.beginWindow();

            long t0 = System.nanoTime();
            r.fill(rect.minX(), rect.minY(), rect.minZ(), rect.dimX(), rect.dimY(), rect.dimZ(),
                    buffer, table.airStateId());
            long t1 = System.nanoTime();

            // 形状守卫（captain 裁决 1）：区域里只要有一个"形状与位置/上下文相关"的方块，
            // 冻结 ABI 表达不了它 ⇒ 宁可不加速，也不出错。
            if (shapeGuardEnabled()) {
                long g0 = System.nanoTime();
                shapeGuardScans++;
                int guarded = 0;
                int firstId = -1;
                for (int i = 0; i < cells; i++) {
                    if (table.isShapeGuarded(buffer[i])) {
                        guarded++;
                        if (firstId < 0) {
                            firstId = buffer[i];
                        }
                    }
                }
                shapeGuardNanos += System.nanoTime() - g0;
                if (guarded > 0) {
                    failures++;
                    shapeGuardRejections++;
                    throw new MirrorUnavailableException("区域含位置/上下文相关形状的方块 " + guarded
                            + " 处（首例 state id=" + firstId + "）—— 冻结 ABI 的一组盒表达不了它，"
                            + "按 captain 裁决回退原逻辑（可用 -D" + PROP_SHAPE_GUARD + "=false 关闭守卫做对比）");
                }
            }

            long allocCost;
            long uploadCost;
            int rc;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = CavaNative.allocateArray(arena, ValueLayout.JAVA_INT, cells);
                MemorySegment.copy(buffer, 0, seg, ValueLayout.JAVA_INT, 0L, cells);
                long t2 = System.nanoTime();
                rc = uploader.upload(uploader.handle(), rect.dimX(), rect.dimY(), rect.dimZ(),
                        rect.minX(), rect.minY(), rect.minZ(), seg, cells);
                long t3 = System.nanoTime();
                allocCost = t2 - t1;
                uploadCost = t3 - t2;
            }
            if (rc != CavaLayouts.CAVA_OK) {
                failures++;
                SectionOriginRegistry.abortWindow();   // 上传失败：原生里仍是**旧内容**（ABI 不部分写入）⇒ 旧登记必须留着
                throw new MirrorUnavailableException("cava_region_upload → " + CavaLayouts.errorName(rc)
                        + " " + rect.describe());
            }
            // 上传成功之后才发布登记：此后这个矩形内的方块写入都会让复用失效。
            SectionOriginRegistry.publish(rect);
            long end = System.nanoTime();
            fillNanos += t1 - t0;
            allocNanos += allocCost;
            uploadNanos += uploadCost;
            totalNanos += end - begin;
            pushes++;
            cellsCopied += cells;
            lastRect = rect;
            lastDim = r.dimensionId();
            lastTick = r.currentTick();
            lastCells = cells;
            lastDetail = new PushDetail(rect, t1 - t0, allocCost, uploadCost, end - begin);
            if (pushes <= 3 || (pushes % 2000) == 0) {
                LOG.info("[cava/mirror] 区域推送 #{} {} cells={} 填={}us 分配拷贝={}us 上传={}us 总={}us",
                        pushes, rect.describe(), cells, (t1 - t0) / 1000, allocCost / 1000, uploadCost / 1000,
                        (end - begin) / 1000);
            }
            return new Pushed(rect.dimX(), rect.dimY(), rect.dimZ(), rect.minX(), rect.minY(), rect.minZ(),
                    cells, end - begin);
        }
    }

    /**
     * 同矩形 / 同维度 / 同 tick（**或**显式打开的跨 tick）时复用上一次推送。
     *
     * <p><b>为什么默认只允许"同 tick"复用（2026-09-24 P1-FIX 改，理由是构造性的）</b>：
     * <ol>
     *   <li>跨 tick 复用要正确，前提是"自上次上传以来的所有世界变更都能让镜像失效"——
     *       也就是失效源必须**完备**。2026-09-22 那一版把它写成结构性保证，靠的是
     *       {@code onBlockChanged / onSectionUnloaded / onWorldChanged} 三个入口；
     *       但实测（{@code docs/CAVA-pathfind-perf.md} §6.3）证明**这三个入口在生产路径里
     *       一次都没被调用过**，于是"有变更就不复用"这条保证是空的 ⇒
     *       {@code detour128/reuse} 腿原生路径**穿过 8 格实心石头**。</li>
     *   <li>1.20.4 的世界变更只发生在服务端主线程、且发生在 tick 内 ⇒
     *       **"同 tick 复用"原理上不可能拿到陈旧地形**（上一次推送与本次复用在同一个 tick 内，
     *       中间不可能插入跨 tick 的地形变更）。这一条不需要任何失效源就能成立。</li>
     * </ol>
     * 于是现在的默认是：{@code lastTick == r.currentTick()}（同 tick）**加上**失效事件哨兵
     * （同 tick 内的方块变化由 {@link SectionOriginRegistry} 的主钩子补上）。两者缺一不可：
     * 只有同 tick 会漏 tick 内的改动，只有失效哨兵会漏"根本没有事件源"的情况。
     *
     * <p><b>跨 tick 复用现在是显式可选项</b>：{@code -D}{@link #PROP_CROSSTICK_REUSE}{@code =true}
     * 打开，并且带金丝雀计数 {@link #crossTickReuseHits()}（"跨 tick 复用真的命中了几次"）。
     * **没有金丝雀证据就不许把它设成默认**。
     */
    public Pushed pushReusingSameTick(RegionRect rect) {
        RegionReader r = requireReader();
        synchronized (lock) {
            /* 复用条件（2026-09-24 P1-FIX 定稿）：
             *   rect 相同 + 维度相同 + 表与上传器可用 + （同 tick 或 显式打开跨 tick）
             *   + **没有失效事件**（lastTick != Long.MIN_VALUE 是"没有失效事件"的哨兵：
             *     成功推送后写成真实 tick；invalidate() 把它写回 Long.MIN_VALUE）。
             *
             *   ⚠️ 历史注记（不要照抄旧注释）：2026-09-22 那版明确写着
             *   "不要再把 lastTick == r.currentTick() 加回来"，理由是"按 tick 判定会漏同一 tick 内的
             *   方块变化"。那条理由**只对了一半**：漏 tick 内变化是真的，但当时的结论
             *   "所以只能当可选快路径、被失效源取代"是错的 —— 失效源当时根本不存在。
             *   现在两件事**都要**：同 tick 限制挡住跨 tick 陈旧，失效钩子挡住 tick 内改动。
             *   （旧版还抱怨"复用永远不可能命中"：那不中的原因是另一处接线缺失，
             *   而"命中"本身不是目标，**正确**才是。）*/
            if (lastRect != null && lastRect.equals(rect) && lastDim.equals(r.dimensionId())
                    && lastTick != Long.MIN_VALUE && table.ready() && uploader.available()) {
                long tick = r.currentTick();
                boolean sameTick = lastTick == tick;
                if (sameTick) {
                    reuseSkips++;
                    reuseSameTickHits++;
                    return new Pushed(rect.dimX(), rect.dimY(), rect.dimZ(), rect.minX(), rect.minY(),
                            rect.minZ(), lastCells, 0L);
                }
                if (crossTickReuseEnabled()) {
                    reuseSkips++;
                    crossTickReuseHits++;
                    if (crossTickReuseHits <= 3 || (crossTickReuseHits % 2000) == 0) {
                        LOG.warn("[cava/mirror] 跨 tick 复用命中 #{}（上次推送 tick={} 当前 tick={}）"
                                        + "—— 这是 -D{}=true 的行为，正确性依赖失效源完备",
                                crossTickReuseHits, lastTick, tick, PROP_CROSSTICK_REUSE);
                    }
                    return new Pushed(rect.dimX(), rect.dimY(), rect.dimZ(), rect.minX(), rect.minY(),
                            rect.minZ(), lastCells, 0L);
                }
                crossTickReuseBlocked++;
            }
            return push(rect);
        }
    }

    /** 跨 tick 复用开关（默认 false；打开要带金丝雀证据，见 {@link #pushReusingSameTick}）。 */
    public static boolean crossTickReuseEnabled() {
        return "true".equalsIgnoreCase(System.getProperty(PROP_CROSSTICK_REUSE, "false"));
    }

    /** 从"起点/终点 + 生物体型"直接推一个窗口（调用方不必自己算边距）。 */
    public Pushed pushForSolve(int sx, int sy, int sz, int tx, int ty, int tz,
                               float width, float height, int safeFallDistance) {
        RegionReader r = requireReader();
        /* **必须走复用路径**：同一片地形上的连续求解（同一 tick 内多只生物、或同 tick 地形没变）不应重推。
         * 2026-09-22 实测：这里原本直接调 push()，于是 6000 次求解 = 6000 次推送、**复用命中 0 次**。
         * 复用是否成立的判定在 pushReusingSameTick 里：
         *   **同矩形 + 同维度 + 同 tick + 没有失效事件**（2026-09-24 P1-FIX 的安全默认；
         *   跨 tick 复用要显式开 -Dcava.mirror.reuse.crosstick=true 并看金丝雀计数）。*/
        return pushReusingSameTick(RegionRect.forSolve(sx, sy, sz, tx, ty, tz, width, height, safeFallDistance,
                r.minY(), r.maxY()));
    }

    @Override
    public void clear() {
        synchronized (lock) {
            clearLocked();
        }
    }

    private void clearLocked() {
        lastRect = null;
        lastDim = "";
        lastTick = Long.MIN_VALUE;
        SectionOriginRegistry.clearWindow();
        if (uploader.available()) {
            int rc = uploader.clear(uploader.handle());
            if (rc != CavaLayouts.CAVA_OK) {
                LOG.warn("[cava/mirror] cava_region_clear → {}", CavaLayouts.errorName(rc));
            }
        }
    }

    // ------------------------------------------------------------------
    // 失效口子（后续脏跟踪流的接入点）
    // ------------------------------------------------------------------

    /** 某个区段被卸载 —— 本轮只让同 tick 复用失效。 */
    public void onSectionUnloaded(int chunkX, int chunkZ) {
        invalidate("区段卸载 (" + chunkX + "," + chunkZ + ")");
    }

    /** 某个方块变了 —— 本轮只让同 tick 复用失效。 */
    public void onBlockChanged(int x, int y, int z) {
        invalidate("方块变化 (" + x + "," + y + "," + z + ")");
    }

    /** 换世界 / 换维度 —— 本轮清掉原生区域缓存。 */
    public void onWorldChanged(String reason) {
        clear();
        invalidate("世界变更: " + reason);
    }

    private void invalidate(String reason) {
        lastTick = Long.MIN_VALUE;
        invalidationCount++;
        lastInvalidation = reason;
    }

    // ------------------------------------------------------------------
    // 台账
    // ------------------------------------------------------------------

    private void ensureBuffer(int cells) {
        if (buffer.length < cells) {
            buffer = new int[Math.max(cells, buffer.length * 2)];
        }
    }

    /** 最近一次推送的耗时分解。 */
    public PushDetail lastDetail() {
        return lastDetail;
    }

    // ------------------------------------------------------------------
    // P1-CROSS：推送成本的**累计出口**（跨 tick 复用这一注的判决需要一个数：
    // "被省掉的那些推送，每次本来要花多少钱"）。
    // 为什么不能从 report() 里拿：它只给"平均值"，而窗口内的增量要按 reset 前后的差算。
    // ------------------------------------------------------------------

    /** 累计推送耗时（ns；与 {@link #pushes()} 相除 = 每次冷推送的平均成本）。 */
    public long pushNanos() {
        synchronized (lock) {
            return totalNanos;
        }
    }

    /** 累计推送耗时里"读世界填缓冲"的那一段（ns）。 */
    public long pushFillNanos() {
        synchronized (lock) {
            return fillNanos;
        }
    }

    /** 累计推送耗时里"分配 + 拷贝到原生内存"的那一段（ns）。 */
    public long pushAllocNanos() {
        synchronized (lock) {
            return allocNanos;
        }
    }

    /** 累计推送耗时里"跨界上传"的那一段（ns）。 */
    public long pushUploadNanos() {
        synchronized (lock) {
            return uploadNanos;
        }
    }

    /** 已推送次数。 */
    public long pushes() {
        return pushes;
    }

    /** 失败次数（每次都会抛异常给调用方）。 */
    public long failures() {
        return failures;
    }

    /** 复用命中次数（同 tick + 跨 tick 的总和）。 */
    public long reuseSkips() {
        return reuseSkips;
    }

    /** **同 tick** 复用命中次数（新的安全默认路径）。 */
    public long reuseSameTickHits() {
        return reuseSameTickHits;
    }

    /** 跨 tick 复用命中次数（**金丝雀**；只有显式打开 {@link #PROP_CROSSTICK_REUSE} 才会增长）。 */
    public long crossTickReuseHits() {
        return crossTickReuseHits;
    }

    /** 因"跨 tick 且开关关着"而被拒绝复用的次数（= 这个开关打开后能多省多少次推送的上界）。 */
    public long crossTickReuseBlocked() {
        return crossTickReuseBlocked;
    }

    /** 累计推送的方块数。 */
    public long cellsCopied() {
        return cellsCopied;
    }

    /** 累计失效次数（区段卸载 / 方块变化 / 世界变更）。 */
    public long invalidationCount() {
        return invalidationCount;
    }

    /** 最近一次失效原因。 */
    public String lastInvalidation() {
        return lastInvalidation;
    }

    /** 绑定次数。 */
    public long binds() {
        return binds;
    }

    /** 重绑次数（换世界或换维度）。 */
    public long rebinds() {
        return rebinds;
    }

    /** flags 判定次数。 */
    public long flagsReadyCalls() {
        return flagsReadyCalls;
    }

    /** flags 判定通过次数。 */
    public long flagsReadyOk() {
        return flagsReadyOk;
    }

    /** 档案上传台账。 */
    public String profileStats() {
        return "profileUploads=" + profileUploads + " profileRefusals=" + profileRefusals;
    }

    /** 形状守卫是否开启（默认开；{@code -Dcava.mirror.shape.guard=false} 关闭）。 */
    public static boolean shapeGuardEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP_SHAPE_GUARD, "true"));
    }

    /** 形状守卫拒绝次数（每次都会让调用方回退）。 */
    public long shapeGuardRejections() {
        return shapeGuardRejections;
    }

    /** 形状守卫扫描的累计耗时（诊断）。 */
    public long shapeGuardNanos() {
        return shapeGuardNanos;
    }

    /** 多行台账（启动/收尾报告用）。 */
    public String report() {
        RegionReader r = reader;
        synchronized (lock) {
            long n = Math.max(1, pushes);
            return "区域镜像: 推送 " + pushes + " 次 / 失败 " + failures + " / 复用 " + reuseSkips
                    + "（同tick " + reuseSameTickHits + " / 跨tick " + crossTickReuseHits
                    + "，跨tick被拒 " + crossTickReuseBlocked + "，开关="
                    + (crossTickReuseEnabled() ? "**开**" : "关") + "）"
                    + " / 方块 " + cellsCopied + '\n'
                    + "  平均每次: 总 " + String.format("%.2f", totalNanos / 1000.0 / n) + " us"
                    + "（填 " + String.format("%.2f", fillNanos / 1000.0 / n) + " us"
                    + " / 分配拷贝 " + String.format("%.2f", allocNanos / 1000.0 / n) + " us"
                    + " / 上传 " + String.format("%.2f", uploadNanos / 1000.0 / n) + " us）" + '\n'
                    + "  失效 " + invalidationCount + " 次，最近: " + lastInvalidation + '\n'
                    + "  bind " + binds + " 次（重绑 " + rebinds + "）当前维度: "
                    + (r == null ? "(未绑定)" : r.dimensionId()) + '\n'
                    + "  flags 判定 " + flagsReadyCalls + " 次 / 通过 " + flagsReadyOk + "（最近 caps=0x"
                    + Integer.toHexString(lastCaps) + "，未就绪原因: "
                    + (flagsNotReadyReason == null ? "无" : flagsNotReadyReason) + "）" + '\n'
                    + "  " + profileStats() + '\n'
                    + "  形状守卫: " + (shapeGuardEnabled() ? "开" : "关") + "，拒绝 " + shapeGuardRejections
                    + " 次 / 扫描 " + shapeGuardScans + " 次，累计 "
                    + String.format("%.2f", shapeGuardNanos / 1000.0) + " us";
        }
    }
}
