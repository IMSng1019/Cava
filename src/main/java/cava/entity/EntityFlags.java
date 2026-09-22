package cava.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 实体镜像里 {@code flags} 字段的<b>唯一位布局定义</b>。
 *
 * <p><b>为什么单独一个类</b>（P1 的硬教训）：同一份常量/位布局只允许定义一处。
 * P1 曾出现 {@code CAVA_PNT_*} 序号表与真实枚举 ordinal 从第 5 位起全错位，
 * 而"路径照样算得出来、只是与原版不一致" —— 运行期无法发现。
 * 所以这里既是位号的唯一定义，也是 {@link #names(int)} 的唯一实现，
 * 打包端与读取端都只能引用本类。
 *
 * <p><b>确定性纪律</b>：本类的位含义必须与将来 {@code cava_abi.h} 的
 * {@code CAVA_EF_*} 位<b>逐位一致</b>；ABI 冻结前这里先只做 Java 侧，
 * 冻结时由本类与头文件同时改（见 {@code docs/CAVA-p2-java-notes.md} 的「等 ABI」清单）。
 *
 * <p><b>明确不属于本类的东西</b>：VMP 会写 {@code Entity.velocityDirty}
 * （{@code com.ishland.vmp.mixins.entitytracker} 的 {@code vmp$tickAlways()}）。
 * 那是 <b>VMP 的状态位，不是我们的</b> —— 契约点名的坑，镜像里<b>不镜像</b>该字段。
 */
public final class EntityFlags {

    /** {@code Entity.isOnGround()}。 */
    public static final int ON_GROUND = 1;

    /** {@code Entity.horizontalCollision}（{@code Entity.move} 偏移 333 赋值）。 */
    public static final int HORIZONTAL_COLLISION = 1 << 1;

    /** {@code Entity.verticalCollision}（偏移 354；注意它用的是 {@code !=} 而不是 approximatelyEquals）。 */
    public static final int VERTICAL_COLLISION = 1 << 2;

    /** {@code Entity.groundCollision}（偏移 379：{@code verticalCollision && movement.y < 0.0}）。 */
    public static final int GROUND_COLLISION = 1 << 3;

    /** {@code Entity.collidedSoftly}（偏移 398/403）。 */
    public static final int COLLIDED_SOFTLY = 1 << 4;

    /** {@code Entity.hasVehicle()}。 */
    public static final int HAS_VEHICLE = 1 << 5;

    /** {@code Entity.isRemoved()}。 */
    public static final int REMOVED = 1 << 6;

    /** {@code Entity.noClip}（{@code move} 偏移 1 的整段提前返回分支）。 */
    public static final int NO_CLIP = 1 << 7;

    /** {@code Entity.isTouchingWater()}。 */
    public static final int TOUCHING_WATER = 1 << 8;

    /** {@code Entity.wasOnFire}（{@code move} 偏移 44 写入）。 */
    public static final int WAS_ON_FIRE = 1 << 9;

    /**
     * <b>观测位，不是原版标志</b>：ServerCore 的激活范围判定为 true
     * （{@code ActivationEntity.servercore$isInactive()}）。
     *
     * <p>语义：该实体本 tick 被 {@code ServerLevel.tickNonPassenger} 整个跳过，
     * <b>位置不变是预期行为</b>。镜像<b>仍然打包它</b>（它照样参与碰撞），
     * 但原生侧不得推进它、不得唤醒它、不得回写。
     */
    public static final int SERVERCORE_INACTIVE = 1 << 10;

    /** 所有已定义位。 */
    public static final int ALL = ON_GROUND | HORIZONTAL_COLLISION | VERTICAL_COLLISION | GROUND_COLLISION
            | COLLIDED_SOFTLY | HAS_VEHICLE | REMOVED | NO_CLIP | TOUCHING_WATER | WAS_ON_FIRE
            | SERVERCORE_INACTIVE;

    private static final String[] NAMES = {
            "ON_GROUND", "HORIZONTAL_COLLISION", "VERTICAL_COLLISION", "GROUND_COLLISION",
            "COLLIDED_SOFTLY", "HAS_VEHICLE", "REMOVED", "NO_CLIP", "TOUCHING_WATER", "WAS_ON_FIRE",
            "SERVERCORE_INACTIVE",
    };

    /** 位号 -> 名字的唯一映射（诊断用；顺序必须与上面的常量一致）。 */
    public static final int BIT_COUNT = NAMES.length;

    private EntityFlags() {
    }

    /** 名字 -> 位号；未知名返回 0。 */
    public static int bit(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return 1 << i;
            }
        }
        return 0;
    }

    /** 位号（0 基）-> 位掩码。 */
    public static int bitAt(int index) {
        if (index < 0 || index >= BIT_COUNT) {
            throw new IllegalArgumentException("位号越界: " + index);
        }
        return 1 << index;
    }

    /** 名字（0 基位号）。 */
    public static String nameAt(int index) {
        return NAMES[index];
    }

    /** 诊断用：把 flags 拆成名字列表，并标出未定义的位。 */
    public static List<String> names(int flags) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            if ((flags & (1 << i)) != 0) {
                out.add(NAMES[i]);
            }
        }
        int unknown = flags & ~ALL;
        if (unknown != 0) {
            out.add("UNKNOWN(0x" + Integer.toHexString(unknown) + ")");
        }
        return out;
    }

    public static String describe(int flags) {
        return String.join("|", names(flags));
    }
}
