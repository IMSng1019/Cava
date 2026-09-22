package cava.compat;

/**
 * 一个"重叠点"：别人的某条补丁 / 某个规则，与 Cava 的某个子系统落在了同一条语义路径上。
 *
 * <p>每个重叠点都必须有<b>显式归属</b>（契约第 5 节）。{@link #stage()} 记录这个决定现在处于什么阶段：
 * 本轮已拍板、还是等 P2/P3 复刻时再定。
 *
 * @param subsystem    "pathfind" / "entity" / "redstone" / "mirror"（mirror 不在 CavaConfig.SUBSYSTEMS 里，只登记不裁决）
 * @param modId        重叠来源的 mod id
 * @param key          稳定键（mixin 组名 / 类名 / 规则名），报告与单测都按它匹配
 * @param defaultOwner {@code auto} 时的默认归属
 * @param stage        决策阶段
 * @param overlap      一句话描述重叠内容（含 javap / jar 证据要点）
 */
public record OverlapPoint(String subsystem, String modId, String key, Owner defaultOwner, Stage stage,
        String overlap) {

    /** 决策阶段。 */
    public enum Stage {
        /** 本轮已拍板，配置可覆盖。 */
        DECIDED("decided"),
        /** 等 P2（实体）复刻时再定；本轮保持现状 = 让位。 */
        PENDING_P2("pending-p2"),
        /** 等 P3（红石）复刻时再定；本轮保持现状 = 让位。 */
        PENDING_P3("pending-p3"),
        /** 不在 Cava 的注入点上，登记但不裁决。 */
        NOT_HOOKED("not-hooked");

        private final String json;

        Stage(String json) {
            this.json = json;
        }

        public String jsonName() {
            return json;
        }
    }

    public boolean affectsCavaSubsystem() {
        return stage != Stage.NOT_HOOKED;
    }

    /** 报告里的一行（竖线已被替换，字段固定）。 */
    public String describe() {
        return subsystem + " " + modId + " " + key + " -> " + defaultOwner.jsonName() + " (" + stage.jsonName() + ")";
    }
}
