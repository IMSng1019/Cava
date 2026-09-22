package cava.oracle;

/** net.minecraft.util.math.Box 的极小镜像（只保留寻路用到的部分）。 */
public final class Box {
    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public double getLengthX() {
        return this.maxX - this.minX;
    }

    public double getLengthY() {
        return this.maxY - this.minY;
    }

    public double getLengthZ() {
        return this.maxZ - this.minZ;
    }

    /** Box.getAverageSideLength() = (lenX + lenY + lenZ) / 3.0。 */
    public double getAverageSideLength() {
        return (getLengthX() + getLengthY() + getLengthZ()) / 3.0;
    }

    public Box offset(double dx, double dy, double dz) {
        return new Box(this.minX + dx, this.minY + dy, this.minZ + dz,
                this.maxX + dx, this.maxY + dy, this.maxZ + dz);
    }
}
