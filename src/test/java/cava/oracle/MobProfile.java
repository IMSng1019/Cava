package cava.oracle;

/**
 * 原版 MobEntity 在寻路里被读到的全部状态（= 生物档案）。
 *
 * <p>惩罚表语义逐字复刻 MobEntity（规格第 5.4.7 节）：
 * 没有覆盖就返回 PathNodeType.getDefaultPenalty()；setPathfindingPenalty 是直接覆盖。
 */
public final class MobProfile {
    public float width;
    public float height;
    public float stepHeight;
    public int safeFallDistance;

    public boolean canOpenDoors;
    public boolean canEnterOpenDoors;
    public boolean canSwim;
    public boolean canWalkOverFences;
    public boolean amphibious;
    public boolean penalizeDeepWater;

    public double x;
    public double y;
    public double z;
    public boolean onGround;
    public boolean touchingWater;
    public boolean canWalkOnFluid;

    private final float[] penalty = new float[Pnt.VALUES.length];
    private final boolean[] penaltySet = new boolean[Pnt.VALUES.length];

    public float getPathfindingPenalty(Pnt type) {
        int i = type.ordinal();
        return this.penaltySet[i] ? this.penalty[i] : type.getDefaultPenalty();
    }

    public void setPathfindingPenalty(Pnt type, float value) {
        int i = type.ordinal();
        this.penalty[i] = value;
        this.penaltySet[i] = true;
    }

    /** 该 PathNodeType 是否被显式覆盖过（用于向量文件里的 penaltyMask）。 */
    public boolean isOverridden(Pnt type) {
        return this.penaltySet[type.ordinal()];
    }

    public int blockX() {
        return Mth.floor(this.x);
    }

    public int blockY() {
        return Mth.floor(this.y);
    }

    public int blockZ() {
        return Mth.floor(this.z);
    }

    // EntityDimensions.getBoxAt(Vec3d)：注意 f = width / 2.0f 是 **float** 除法
    public double boxMinX() {
        float f = this.width / 2.0f;
        return this.x - (double) f;
    }

    public double boxMinY() {
        return this.y;
    }

    public double boxMinZ() {
        float f = this.width / 2.0f;
        return this.z - (double) f;
    }

    public double boxMaxX() {
        float f = this.width / 2.0f;
        return this.x + (double) f;
    }

    public double boxMaxY() {
        return this.y + (double) this.height;
    }

    public double boxMaxZ() {
        float f = this.width / 2.0f;
        return this.z + (double) f;
    }

    public Box boundingBox() {
        return new Box(boxMinX(), boxMinY(), boxMinZ(), boxMaxX(), boxMaxY(), boxMaxZ());
    }

    public MobProfile copy() {
        MobProfile p = new MobProfile();
        p.width = this.width;
        p.height = this.height;
        p.stepHeight = this.stepHeight;
        p.safeFallDistance = this.safeFallDistance;
        p.canOpenDoors = this.canOpenDoors;
        p.canEnterOpenDoors = this.canEnterOpenDoors;
        p.canSwim = this.canSwim;
        p.canWalkOverFences = this.canWalkOverFences;
        p.amphibious = this.amphibious;
        p.penalizeDeepWater = this.penalizeDeepWater;
        p.x = this.x;
        p.y = this.y;
        p.z = this.z;
        p.onGround = this.onGround;
        p.touchingWater = this.touchingWater;
        p.canWalkOnFluid = this.canWalkOnFluid;
        System.arraycopy(this.penalty, 0, p.penalty, 0, this.penalty.length);
        System.arraycopy(this.penaltySet, 0, p.penaltySet, 0, this.penaltySet.length);
        return p;
    }
}
