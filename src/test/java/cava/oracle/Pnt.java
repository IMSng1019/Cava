package cava.oracle;

/**
 * 原版 net.minecraft.entity.ai.pathing.PathNodeType（class_7 / efc）的镜像。
 *
 * <p>ordinal 与 defaultPenalty 逐条取自 Yarn 1.20.4 字节码的 static{} 初始化
 * （见 docs/CAVA-pathfind-oracle-spec.md 第 8 节）。**不要改动顺序或数值。**
 *
 * <p>本类不引用任何 net.minecraft 类型。
 */
public enum Pnt {
    BLOCKED(-1.0f),            // 0
    OPEN(0.0f),                // 1
    WALKABLE(0.0f),            // 2
    WALKABLE_DOOR(0.0f),       // 3
    TRAPDOOR(0.0f),            // 4
    POWDER_SNOW(-1.0f),        // 5
    DANGER_POWDER_SNOW(0.0f),  // 6
    FENCE(-1.0f),              // 7
    LAVA(-1.0f),               // 8
    WATER(8.0f),               // 9
    WATER_BORDER(8.0f),        // 10
    RAIL(0.0f),                // 11
    UNPASSABLE_RAIL(-1.0f),    // 12
    DANGER_FIRE(8.0f),         // 13
    DAMAGE_FIRE(16.0f),        // 14
    DANGER_OTHER(8.0f),        // 15
    DAMAGE_OTHER(-1.0f),       // 16
    DOOR_OPEN(0.0f),           // 17
    DOOR_WOOD_CLOSED(-1.0f),   // 18
    DOOR_IRON_CLOSED(-1.0f),   // 19
    BREACH(4.0f),              // 20
    LEAVES(-1.0f),             // 21
    STICKY_HONEY(8.0f),        // 22
    COCOA(0.0f),               // 23
    DAMAGE_CAUTIOUS(0.0f),     // 24
    DANGER_TRAPDOOR(0.0f);     // 25

    public static final Pnt[] VALUES = values();

    public final float defaultPenalty;

    Pnt(float defaultPenalty) {
        this.defaultPenalty = defaultPenalty;
    }

    /** 对应 PathNodeType.getDefaultPenalty()。 */
    public float getDefaultPenalty() {
        return this.defaultPenalty;
    }
}
