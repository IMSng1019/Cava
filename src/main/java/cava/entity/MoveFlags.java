package cava.entity;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * {@code Entity.move} 里四个碰撞标志位的<b>逐分支复刻</b>
 * （字节码偏移 283–403）。
 *
 * <p><b>证据（javap 实读）</b>：
 * <pre>
 *  283: movement.x ; vec3d.x ; MathHelper.approximatelyEquals(DD)Z ; ifne 293 → bl  = !approximatelyEquals(...)
 *  304: movement.z ; vec3d.z ; MathHelper.approximatelyEquals(DD)Z ; ifne 314 → bl2 = !approximatelyEquals(...)
 *  333: horizontalCollision = bl || bl2
 *  345: movement.y ; vec3d.y ; dcmpl ; ifeq 353                            → verticalCollision = movement.y != vec3d.y
 *  357: verticalCollision && (movement.y &lt; 0.0)                            → groundCollision
 *  386: horizontalCollision ? hasCollidedSoftly(vec3d) : false             → collidedSoftly
 * </pre>
 *
 * <p><b>三个必须自己看字节码才能发现的坑</b>：
 * <ol>
 *   <li>{@code verticalCollision} 用的是裸 {@code dcmpl/ifeq}（{@code !=}），
 *       <b>不是</b> {@code approximatelyEquals} —— 三个标志里只有 x/z 用近似比较。</li>
 *   <li>{@code MathHelper.approximatelyEquals} 的阈值是
 *       {@code Math.abs(b - a) < 9.999999747378752E-6}
 *       （= {@code (double)1.0E-5f}，<b>不是</b> {@code 1.0E-7}）。
 *       照抄"1e-7"会让水平碰撞标志在 1e-7..1e-5 的缝隙里与现服务器不同。</li>
 *   <li>{@code groundCollision} 与 {@code setOnGround} 的第二个参数用的是
 *       {@code vec3d}（原生输出），而比较用的是 {@code movement}（原生输入）。</li>
 * </ol>
 *
 * <p>本类<b>直接调用</b> {@link MathHelper#approximatelyEquals(double, double)}，
 * 不重写阈值 —— 阈值只有原版一处定义（FerriteCore 之类 mod 也不会改它，
 * 但"少一处可漂移的副本"本身就是收益）。
 */
public record MoveFlags(boolean horizontalCollision, boolean verticalCollision, boolean groundCollision,
        boolean collidedSoftly) {

    /**
     * 偏移 283–403 的纯函数部分（不含 {@code hasCollidedSoftly} 的虚调用；
     * 那个由 {@link EventReplay} 在 {@link MoveStep#SET_COLLISION_FLAGS} 那一步按需调用）。
     *
     * @param movement 过完 {@code adjustMovementForSneaking} 的位移（原版 {@code aload_2}）
     * @param adjusted 碰撞求解后的位移（原版 {@code aload_3}）
     */
    public static MoveFlags compute(Vec3d movement, Vec3d adjusted, boolean collidedSoftly) {
        boolean bl = !MathHelper.approximatelyEquals(movement.x, adjusted.x);
        boolean bl2 = !MathHelper.approximatelyEquals(movement.z, adjusted.z);
        boolean horizontal = bl || bl2;
        boolean vertical = movement.y != adjusted.y;
        boolean ground = vertical && movement.y < 0.0;
        return new MoveFlags(horizontal, vertical, ground, horizontal && collidedSoftly);
    }

    /** 拆成 {@link EntityFlags} 的位（镜像打包用）。 */
    public int toBits() {
        int bits = 0;
        if (horizontalCollision) {
            bits |= EntityFlags.HORIZONTAL_COLLISION;
        }
        if (verticalCollision) {
            bits |= EntityFlags.VERTICAL_COLLISION;
        }
        if (groundCollision) {
            bits |= EntityFlags.GROUND_COLLISION;
        }
        if (collidedSoftly) {
            bits |= EntityFlags.COLLIDED_SOFTLY;
        }
        return bits;
    }

    /** 偏移 283/304 的两个布尔量，{@link EventReplay} 清水平速度时要用。 */
    public static boolean[] horizontalAxisMask(Vec3d movement, Vec3d adjusted) {
        return new boolean[] {
                !MathHelper.approximatelyEquals(movement.x, adjusted.x),
                !MathHelper.approximatelyEquals(movement.z, adjusted.z),
        };
    }
}
