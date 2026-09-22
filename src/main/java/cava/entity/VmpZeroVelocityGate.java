package cava.entity;

import cava.compat.VmpAdapter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * VMP 的「零位移短路」复刻（{@code com.ishland.vmp.mixins.entity.move_zero_velocity.MixinEntity}）。
 *
 * <h2>为什么必须复刻</h2>
 * 它<b>改了行为</b>，而且是<b>不可配置</b>的：{@code VMPMixinPlugin.shouldApplyMixin} 的常量池里
 * 没有任何以 {@code ...entity.move_zero_velocity} 开头的门控前缀（其它 mixin 都有），
 * {@code config/vmp.properties} 里也没有对应开关（这两条由 {@code cava.compat.VmpAdapter} 实测过）。
 * ⇒ 现在这台服务器上每个实体每 tick 都在跑这个短路。不复刻它，我们<b>会比现服务器更快且行为不同</b>，
 * 逐 tick 差分必爆（少跑的那部分里包含 {@code wasOnFire} 的刷新、{@code horizontalCollision} 的
 * 保持/清零、{@code setVelocity} 等等）。
 *
 * <h2>黏滞语义（本类存在的最大理由）</h2>
 * VMP 源码（{@code .research/} 里的原文件）：
 * <pre>
 *   {@literal @}Inject(method = "move", at = {@literal @}At("HEAD"), cancellable = true)
 *   private void onMove(MovementType movementType, Vec3d movement, CallbackInfo ci) {
 *       if (!boundingBoxChanged &amp;&amp; movement.equals(Vec3d.ZERO)) {
 *           ci.cancel();
 *           boundingBoxChanged = false;                 // ← 复位只发生在 cancel 分支里
 *       }
 *   }
 *   {@literal @}Inject(method = "setBoundingBox", at = {@literal @}At("HEAD"))
 *   private void onBoundingBoxChanged(Box boundingBox, CallbackInfo ci) {
 *       if (!this.boundingBox.equals(boundingBox)) boundingBoxChanged = true;
 *   }
 * </pre>
 * {@code boundingBoxChanged} 初值 {@code false}；置位的唯一路径是 {@code setBoundingBox}；
 * 复位的唯一路径在 cancel 分支内，而 cancel 的前提是它<b>已经是 false</b>。
 * ⇒ <b>包围盒一旦真的变过一次，这个实体就永久失去零位移短路</b>。
 * 只实现"零位移就跳过"会比现服务器更快、行为不同，而且<b>只在实体第一次换碰撞盒之后</b>才显形
 * （例如生物长大、玩家姿势切换、载具上下），是那种"跑一万 tick 才炸一次"的差异。
 *
 * <h2>两条致命的时序约束（已写进方法名，不靠注释提醒）</h2>
 * <ol>
 *   <li>{@link #onMoveHead}：必须在 {@code Entity.move} 的 <b>HEAD</b> 调用，
 *       也就是在 {@code noClip} 分支之前、在一切副作用之前。</li>
 *   <li>{@link #onSetBoundingBoxHeadBeforeAssign}：必须在 {@code Entity.setBoundingBox}
 *       的 HEAD、<b>字段赋值之前</b>调用 —— 原版是
 *       {@code public final void setBoundingBox(Box box) { this.boundingBox = box; }}，
 *       VMP 注入在 HEAD 时读到的 {@code this.boundingBox} 仍是<b>旧值</b>，
 *       所以比较的是"旧盒 vs 新盒"。赋值之后再比较就恒等，标志永远置不上。</li>
 * </ol>
 *
 * <p><b>状态机只有一份</b>：本类是 {@link VmpAdapter.State} 的薄包装，
 * 不重新实现 {@code shouldCancelMove}（避免第二份语义副本）。
 * 相等性直接调用 {@link Box#equals(Object)} / {@link VmpAdapter.State#onMove}
 * —— 它们内部用 {@code Double.compare}，所以 <b>{@code -0.0} 不等于 {@code 0.0}</b>
 * （{@code Vec3d.equals} 的 javap 已实证），不能图省事写成 {@code ==}。
 */
public final class VmpZeroVelocityGate {

    private final VmpAdapter.State state = new VmpAdapter.State();

    /** 包装层转发给 {@link VmpAdapter.State} 的调用次数（诊断用：证明短路真的被问过）。 */
    private long moveQueries;
    private long boxUpdates;

    /**
     * 在 {@code Entity.move(MovementType, Vec3d)} 的 <b>HEAD</b> 调用（VMP 的注入点）。
     *
     * @return true = 原版整段与原生路径都必须被取消（本次调用已经把黏滞标志复位）
     */
    public boolean onMoveHead(Vec3d movement) {
        moveQueries++;
        return state.onMove(movement.x, movement.y, movement.z);
    }

    /**
     * 在 {@code Entity.setBoundingBox(Box)} 的 <b>HEAD、赋值之前</b>调用。
     *
     * <p>方法名里的 {@code HeadBeforeAssign} 就是那条会致命的时序约束。
     *
     * @param boxBeforeAssign {@code this.boundingBox} 的当前值（尚未被新盒覆盖）
     * @param incoming        即将写入的新盒
     */
    public void onSetBoundingBoxHeadBeforeAssign(Box boxBeforeAssign, Box incoming) {
        boxUpdates++;
        state.onSetBoundingBox(boxBeforeAssign.equals(incoming));
    }

    /** 黏滞标志当前值（诊断/差分证据：true = 该实体的零位移短路已永久失效）。 */
    public boolean boundingBoxChanged() {
        return state.boundingBoxChanged();
    }

    public long moveQueries() {
        return moveQueries;
    }

    public long boxUpdates() {
        return boxUpdates;
    }
}
