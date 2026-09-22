package cava.entity;

/**
 * 实体镜像的<b>窄输入接口</b>：把"某个世界的实体列表"抽象成"逐行填充暂存"。
 *
 * <p>这样 {@link EntityMirror} 不引用任何 Minecraft 类型，单测用内存假世界即可覆盖；
 * 真正的 MC 适配（{@code ServerWorld.getEntities()} / {@code Entity} 字段读取）
 * 由注入流实现这一个接口即可 —— 接口只有 2 个方法。
 *
 * <p><b>只读契约（重要）</b>：实现方不得在 {@link #sample} 里修改实体。
 * 镜像每 tick 打包是纯观测行为；契约明确要求"不要主动唤醒、不要回写"。
 */
public interface EntitySource {

    /** 本 tick 可供打包的实体数。 */
    int size();

    /**
     * 只读地把第 {@code index} 个实体填进 {@code out}（{@code out} 由调用方复用，已 {@code reset()}）。
     *
     * @return false = 这一行不可用（{@link SkipReason#UNSAMPLABLE}）
     */
    boolean sample(int index, EntitySample out);

    /**
     * 一个"永远读不出来"的空源，便于单测与占位。
     *
     * <p>注意本接口有 2 个抽象方法，<b>不是函数式接口</b>，所以这里必须用匿名类，
     * 不能写成 lambda（写 lambda 会编译失败 —— 这是好事，说明接口没有被悄悄收窄）。
     */
    static EntitySource empty() {
        return new EntitySource() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public boolean sample(int index, EntitySample out) {
                return false;
            }
        };
    }
}
