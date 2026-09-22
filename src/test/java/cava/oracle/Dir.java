package cava.oracle;

/**
 * net.minecraft.util.math.Direction 的枚举序与偏移（规格第 2.7 节，证据为 Direction$1 的
 * field_11054 映射表）。
 *
 * <p>**枚举序就是 DOWN, UP, NORTH, SOUTH, WEST, EAST** —— WaterPathNodeMaker 的阶段 1 依赖它。
 */
public final class Dir {
    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    public static final int[] DX = {0, 0, 0, 0, -1, 1};
    public static final int[] DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] DZ = {0, 0, -1, 1, 0, 0};

    /** Direction.Type.HORIZONTAL 的迭代顺序（规格第 2.8 节）：NORTH, EAST, SOUTH, WEST。 */
    public static final int[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};

    private Dir() {}

    public static int offX(int dir) {
        return DX[dir];
    }

    public static int offY(int dir) {
        return DY[dir];
    }

    public static int offZ(int dir) {
        return DZ[dir];
    }

    /** rotateYClockwise：NORTH->EAST, EAST->SOUTH, SOUTH->WEST, WEST->NORTH。 */
    public static int rotateYClockwise(int dir) {
        return switch (dir) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> throw new IllegalStateException("Unable to get clockwise Y-rotation of " + dir);
        };
    }
}
