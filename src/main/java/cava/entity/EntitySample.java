package cava.entity;

/**
 * SoA 打包的<b>单行暂存</b>（可复用，零分配）。
 *
 * <p>字段顺序就是将来 ABI 结构体的字段顺序；ABI 冻结前这里只有 Java 侧一份。
 * 位含义见 {@link EntityFlags}（唯一定义处）。
 *
 * <p><b>严格只读</b>：本类只承载"从实体读出来的东西"。
 * 没有任何方法会写回实体 —— 镜像绝不主动唤醒 / 回写
 * （ServerCore 未激活实体的位置不变是<b>预期行为</b>，契约明确禁止我们改写）。
 */
public final class EntitySample {

    /** 源方自己的对象，对镜像不透明；只用于 {@link InactivityProbe}。可为 null。 */
    public Object handle;

    /** {@code Entity.getId()}。 */
    public int id;

    /** 实体类型 id（{@code Registries.ENTITY_TYPE.getRawId(type)}）。 */
    public int typeId;

    public double x;
    public double y;
    public double z;

    /** {@code Entity.getVelocity()} 的三个分量。 */
    public double vx;
    public double vy;
    public double vz;

    /** {@code Entity.getBoundingBox()} 的六个分量（位置依赖，不要用 width/height 反推）。 */
    public double minX;
    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;

    /** {@link EntityFlags} 的位或。 */
    public int flags;

    public void reset() {
        handle = null;
        id = 0;
        typeId = 0;
        x = 0;
        y = 0;
        z = 0;
        vx = 0;
        vy = 0;
        vz = 0;
        minX = 0;
        minY = 0;
        minZ = 0;
        maxX = 0;
        maxY = 0;
        maxZ = 0;
        flags = 0;
    }

    public void setPosition(double px, double py, double pz) {
        this.x = px;
        this.y = py;
        this.z = pz;
    }

    public void setVelocity(double sx, double sy, double sz) {
        this.vx = sx;
        this.vy = sy;
        this.vz = sz;
    }

    public void setBoundingBox(double bx0, double by0, double bz0, double bx1, double by1, double bz1) {
        this.minX = bx0;
        this.minY = by0;
        this.minZ = bz0;
        this.maxX = bx1;
        this.maxY = by1;
        this.maxZ = bz1;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}
