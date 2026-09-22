package cava.oracle;

/**
 * 可复现伪随机（xorshift64*）。**规格第 10.6 节冻结了这个算法** —— 任何改动都会让测试向量失效。
 */
public final class Xorshift {
    private long state;

    public Xorshift(long seed) {
        this.state = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
    }

    public long next() {
        long x = this.state;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        this.state = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    /** [0, bound)。 */
    public int nextInt(int bound) {
        return (int) ((next() >>> 1) % (long) bound);
    }

    /** [0,1)，24 位精度。 */
    public float nextFloat() {
        return (float) ((next() >>> 40) * (1.0 / 16777216.0));
    }

    public boolean nextBoolean() {
        return (next() >>> 63) != 0;
    }

    /** 从数组中确定性地挑一个元素。 */
    public <T> T pick(T[] values) {
        return values[nextInt(values.length)];
    }

    public float pick(float[] values) {
        return values[nextInt(values.length)];
    }

    public int pick(int[] values) {
        return values[nextInt(values.length)];
    }
}
