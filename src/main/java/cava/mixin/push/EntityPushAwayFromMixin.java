package cava.mixin.push;

import cava.push.NativePush;
import cava.push.PushMath;
import cava.push.PushRuntime;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P2 第 2 核：{@code Entity.pushAwayFrom(Entity)}（intermediary {@code method_5697}）。
 *
 * <p><b>边界（契约）</b>：原生只算几何 {@code (p,q)}；**对象集合与谓词全部留在 Java**：
 * {@code isConnectedThroughVehicle / noClip / hasPassengers / isPushable / addVelocity}
 * 都是 Java 侧的虚调用，与 ServerCore 在 {@code addVelocity} 上的 HEAD 短路天然共存。
 *
 * <p><b>回退默认安全</b>：原生不可用 / 返回错误码 / 任何异常 ⇒ **不 cancel**，原版照跑。
 *
 * <p><b>与 VMP 的关系</b>：这一支完全不在 {@code Entity.move} 上，VMP 的零位移黏滞状态机
 * 与之不相交；本 mixin 也**不碰** {@code setBoundingBox}。
 */
@Mixin(Entity.class)
public abstract class EntityPushAwayFromMixin {

    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void cava$pushAwayFrom(Entity other, CallbackInfo ci) {
        if (!PushRuntime.pushAny()) {
            return;
        }
        PushRuntime.maybeInstall();
        if (PushRuntime.reentrant()) {
            return;
        }
        final Entity self = (Entity) (Object) this;

        // 原版 0-23 的两次早退：isConnectedThroughVehicle（引用比较 getRootVehicle）与两个 noClip。
        // 全是纯读，放在原生调用之前 —— 短路时连原生都不必进。
        final boolean skip = self.isConnectedThroughVehicle(other) || other.noClip || self.noClip;

        if (PushRuntime.VERIFY) {
            cava$verifyHead(self, other, skip);
            return;                                  // verify 模式**不接管**，让原版跑
        }
        if (skip) {
            PushRuntime.pushDeclined.incrementAndGet();
            ci.cancel();                             // 与原版等价（原版在这里 return）
            return;
        }

        final long t0 = PushRuntime.BENCH ? System.nanoTime() : 0L;
        final int hit = NativePush.pushAwayFrom(self.getX(), self.getZ(), other.getX(), other.getZ());
        if (PushRuntime.BENCH) {
            PushRuntime.pushNativeNs.addAndGet(System.nanoTime() - t0);
        }
        if (hit < 0) {
            PushRuntime.pushErrors.incrementAndGet();
            return;                                  // 原生失败 ⇒ 整段回退（不 cancel）
        }
        PushRuntime.pushCalls.incrementAndGet();

        if (PushRuntime.pushShadow()) {
            cava$shadowCompare(self, other, hit);
            return;                                  // shadow 不接管
        }

        if (hit == 0) {
            PushRuntime.pushTakeovers.incrementAndGet();
            ci.cancel();                             // 原版此时什么都不做
            return;
        }

        double dx = NativePush.lastDx();
        double dz = NativePush.lastDz();
        if ("zero-push".equals(PushRuntime.CANARY)) {
            dx = 0.0;
            dz = 0.0;
            PushRuntime.pushCanary.incrementAndGet();
        }

        // 原版 123-166：两次独立的 (hasPassengers, isPushable) 判定 + 两次 addVelocity。
        // 这两个 getter 都是纯读；**addVelocity 走正常虚分派**，ServerCore 的短路照旧生效。
        final boolean pushSelf = !self.hasPassengers() && self.isPushable();
        final boolean pushOther = !other.hasPassengers() && other.isPushable();
        PushRuntime.enterPush();
        try {
            if (pushSelf) {
                self.addVelocity(-dx, 0.0, -dz);     // 字节码 137-144：dneg / dconst_0 / dneg
            }
            if (pushOther) {
                other.addVelocity(dx, 0.0, dz);      // 字节码 161-166
            }
        } finally {
            PushRuntime.exitPush();
        }
        PushRuntime.pushTakeovers.incrementAndGet();
        ci.cancel();
    }

    /** shadow：原生与 Java 参照逐位比对（**不接管**）。 */
    private void cava$shadowCompare(Entity self, Entity other, int hit) {
        final double[] ref = PushRuntime.refScratch();
        final long t0 = PushRuntime.BENCH ? System.nanoTime() : 0L;
        PushMath.compute(self.getX(), self.getZ(), other.getX(), other.getZ(), ref);
        if (PushRuntime.BENCH) {
            PushRuntime.pushJavaRefNs.addAndGet(System.nanoTime() - t0);
            PushRuntime.pushTimed.incrementAndGet();
        }
        final double ndx = hit == 1 ? NativePush.lastDx() : 0.0;
        final double ndz = hit == 1 ? NativePush.lastDz() : 0.0;
        final int nhit = hit == 1 ? 1 : 0;
        PushRuntime.pushShadowCompared.incrementAndGet();
        if (nhit != (int) ref[2]
                || !PushRuntime.bitsAgree(ndx, ref[0])
                || !PushRuntime.bitsAgree(ndz, ref[1])) {
            PushRuntime.pushShadowMismatch.incrementAndGet();
        }
    }

    /**
     * verify：在原版**将要跑**的那一刻记下两实体的速度与原生预测，交给 RETURN 注入比对。
     * 打开它时**不接管** —— 否则观察不到原版实际做了什么。
     */
    private void cava$verifyHead(Entity self, Entity other, boolean skip) {
        final PushRuntime.Probe p = PushRuntime.probe();
        p.active = false;
        if (skip) {
            return;
        }
        final int hit = NativePush.pushAwayFrom(self.getX(), self.getZ(), other.getX(), other.getZ());
        if (hit < 0) {
            PushRuntime.pushErrors.incrementAndGet();
            return;
        }
        PushRuntime.pushCalls.incrementAndGet();
        final double dx = hit == 1 ? NativePush.lastDx() : 0.0;
        final double dz = hit == 1 ? NativePush.lastDz() : 0.0;
        // 短路求值顺序照抄原版：hit==0 时原版根本不会问 hasPassengers/isPushable。
        final boolean a = hit == 1 && !self.hasPassengers() && self.isPushable();
        final boolean b = hit == 1 && !other.hasPassengers() && other.isPushable();
        final boolean scSelf = PushRuntime.addVelocityWouldBeCancelled(self, self.getWorld().isClient());
        final boolean scOther = PushRuntime.addVelocityWouldBeCancelled(other, other.getWorld().isClient());
        p.selfX = self.getVelocity().x;
        p.selfZ = self.getVelocity().z;
        p.otherX = other.getVelocity().x;
        p.otherZ = other.getVelocity().z;
        p.predSelfDx = (a && !scSelf) ? -dx : 0.0;
        p.predSelfDz = (a && !scSelf) ? -dz : 0.0;
        p.predOtherDx = (b && !scOther) ? dx : 0.0;
        p.predOtherDz = (b && !scOther) ? dz : 0.0;
        p.active = true;
    }

    /** verify：把原版实际造成的速度增量与原生预测逐位比对。 */
    @Inject(method = "pushAwayFrom", at = @At("RETURN"))
    private void cava$pushAwayFromReturn(Entity other, CallbackInfo ci) {
        final PushRuntime.Probe p = PushRuntime.probe();
        if (!p.active) {
            return;
        }
        p.active = false;
        if (!PushRuntime.VERIFY) {
            return;
        }
        final Entity self = (Entity) (Object) this;
        final double dsx = self.getVelocity().x - p.selfX;
        final double dsz = self.getVelocity().z - p.selfZ;
        final double dox = other.getVelocity().x - p.otherX;
        final double doz = other.getVelocity().z - p.otherZ;
        PushRuntime.pushVerified.incrementAndGet();
        if (cava$differs(dsx, p.predSelfDx) || cava$differs(dsz, p.predSelfDz)
                || cava$differs(dox, p.predOtherDx) || cava$differs(doz, p.predOtherDz)) {
            PushRuntime.pushVerifyMismatch.incrementAndGet();
        }
    }

    /** ±0.0 之差单独计数：通过一次加法观测不到零的符号，这不是"值不同"。 */
    private static boolean cava$differs(double observed, double predicted) {
        if (PushRuntime.bitsAgree(observed, predicted)) {
            return false;
        }
        if (PushRuntime.zeroSignOnly(observed, predicted)) {
            PushRuntime.pushVerifyZeroSignOnly.incrementAndGet();
            return false;
        }
        return true;
    }
}
