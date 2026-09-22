package cava.entity;

import cava.compat.ServerCoreAdapter;

/**
 * 「该实体本 tick 是否被 ServerCore 激活范围跳过」的可注入探测点。
 *
 * <p>生产实现是 {@link #serverCore()}：直接调用已存在的
 * {@link ServerCoreAdapter#isInactive(Object)}（反射 + 软依赖，ServerCore 没装时恒 false）。
 * 单测用假的 lambda，因此实体打包逻辑可以完全脱离服务器验证。
 *
 * <p><b>为什么不让 {@code EntityMirror} 直接调 ServerCoreAdapter</b>：
 * 那样单测就必须真的装一个 ServerCore；而且"探测"与"打包"耦合之后，
 * 「inactive 实体照样被打包、只是标记为跳过」这条语义就没法在没有 ServerCore 的环境里回归。
 */
@FunctionalInterface
public interface InactivityProbe {

    /** 永不 inactive（ServerCore 未安装时的等价语义）。 */
    InactivityProbe NONE = entity -> false;

    /**
     * @param entity 源方的实体对象（{@link EntitySample#handle}），可能是 {@code null}
     * @return true = 本 tick 被 ServerCore 整 tick 跳过
     */
    boolean isInactive(Object entity);

    /** 生产实现：走 {@link ServerCoreAdapter}。 */
    static InactivityProbe serverCore() {
        return ServerCoreAdapter::isInactive;
    }
}
