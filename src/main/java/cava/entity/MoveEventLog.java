package cava.entity;

import java.util.Arrays;
import java.util.List;

/**
 * 原生返回的<b>事件小数组</b>在 Java 侧的收窄表示。
 *
 * <p><b>只固定语义，不固定字节布局</b>（P2-K 的 ABI 还没冻结）：
 * 将来 {@code CavaBindings} 把原生缓冲区解码进来时，只需要调用
 * {@link #add(MoveEventKind, int, int, int, int)}。字节序 / 结构体形状由 ABI 定，
 * 本类不参与 —— 避免出现"第二份布局定义"（P1 事故的根源）。
 *
 * <p><b>容量语义</b>：原生侧 {@code cap} 不足时契约要求返回错误码而<b>不是部分写入</b>；
 * Java 侧同样 fail-closed：写满之后置 {@link #overflowed()}，绝不悄悄丢事件。
 * 回放看到 {@code overflowed()} 必须报错（少一个事件 = 少一次虚方法调用）。
 */
public final class MoveEventLog {

    private static final int INITIAL = 16;

    private final int capacity;

    private byte[] kinds = new byte[0];
    private int[] xs = new int[0];
    private int[] ys = new int[0];
    private int[] zs = new int[0];
    private int[] payloads = new int[0];
    private int size;
    private boolean overflowed;

    /** @param capacity 原生侧那个小数组的容量（诊断用；0 = 不限） */
    public MoveEventLog(int capacity) {
        this.capacity = capacity;
        reset();
    }

    public MoveEventLog() {
        this(0);
    }

    /** 每 tick 复用前清空。 */
    public void reset() {
        int cap = capacity > 0 ? capacity : INITIAL;
        if (kinds.length < cap) {
            kinds = new byte[cap];
            xs = new int[cap];
            ys = new int[cap];
            zs = new int[cap];
            payloads = new int[cap];
        }
        size = 0;
        overflowed = false;
    }

    /**
     * 追加一个事件（由 ABI 解码端调用）。写满则置 {@link #overflowed()} 并忽略本条。
     */
    public void add(MoveEventKind kind, int x, int y, int z, int payload) {
        if (size == kinds.length) {
            if (capacity > 0) {
                overflowed = true;
                return;
            }
            int cap = Math.max(INITIAL, kinds.length * 2);
            kinds = Arrays.copyOf(kinds, cap);
            xs = Arrays.copyOf(xs, cap);
            ys = Arrays.copyOf(ys, cap);
            zs = Arrays.copyOf(zs, cap);
            payloads = Arrays.copyOf(payloads, cap);
        }
        kinds[size] = (byte) kind.ordinal();
        xs[size] = x;
        ys[size] = y;
        zs[size] = z;
        payloads[size] = payload;
        size++;
    }

    public int capacity() {
        return capacity;
    }

    public boolean overflowed() {
        return overflowed;
    }

    public int size() {
        return size;
    }

    public MoveEventKind kind(int index) {
        return MoveEventKind.values()[kinds[index]];
    }

    public int x(int index) {
        return xs[index];
    }

    public int y(int index) {
        return ys[index];
    }

    public int z(int index) {
        return zs[index];
    }

    public int payload(int index) {
        return payloads[index];
    }

    /** 诊断用一行（差分日志里直接可见）。 */
    public List<String> describe() {
        return java.util.stream.IntStream.range(0, size)
                .mapToObj(i -> kind(i) + "(" + xs[i] + "," + ys[i] + "," + zs[i] + ",p=" + payloads[i] + ")")
                .toList();
    }
}
