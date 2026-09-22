package cava.mirror;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生物档案注册表：{@code profileKey → MobProfileSpec}。
 *
 * <p><b>为什么需要注册表</b>：{@code RegionSource.uploadProfileForSolve(handle, profileKey)} 只拿到一个
 * {@code long}，而 {@code CavaMobProfile} 的内容（体型 / 惩罚表 / 位姿）只有调用方（注入流）手里有。
 * 所以约定：**调用方在每次求解前先 {@link #define(MobProfileSpec)}，再 upload**。
 *
 * <p><b>profileKey 的构成与"什么时候会变"</b>（{@code docs/CAVA-mirror-notes.md} §2.3 有同一张表）：
 * {@code key = FNV-1a64(navKind, width, height, stepHeight, safeFallDistance, minY, seaLevel, caps,
 * penaltyMask, penalty[0..25] 的原始位模式)}。也就是说它**只在**下列情况变化：
 * 能力位（开门/游泳/越栅栏/两栖）、体型、台阶高、安全坠落距离、世界上下界、惩罚表。
 * <b>位姿不在 key 里</b>（每 tick 都变），所以同一 key 的 spec 必须**每次用当前位姿重定义**。
 */
public final class MobProfiles {

    private static final Logger LOG = LoggerFactory.getLogger("cava/mirror");
    private static final Map<Long, MobProfileSpec> REGISTRY = new ConcurrentHashMap<>();
    private static volatile long epoch;

    private MobProfiles() {
    }

    /** 登记（覆盖同 key 的旧 spec —— 位姿会随之更新）。返回 key。 */
    public static long define(MobProfileSpec spec) {
        long key = spec.independentKey();
        REGISTRY.put(key, spec);
        epoch++;
        return key;
    }

    /** 直接按 key 覆盖（key 由 {@link #keyOf} 预先算出时用）。 */
    public static void put(long key, MobProfileSpec spec) {
        REGISTRY.put(key, spec);
        epoch++;
    }

    /** key 对应的 spec（可能已过时；上传前请重新 define）。 */
    public static MobProfileSpec get(long key) {
        return REGISTRY.get(key);
    }

    /** 忘掉一个 key（生物卸载）。 */
    public static void forget(long key) {
        REGISTRY.remove(key);
    }

    /** 清空（世界卸载 / 关服）。 */
    public static void clear() {
        REGISTRY.clear();
        epoch++;
    }

    /** 当前注册数。 */
    public static int size() {
        return REGISTRY.size();
    }

    /** 注册表变动次数（诊断用）。 */
    public static long epoch() {
        return epoch;
    }

    /** 记一次"key 未注册"的日志（每个 key 只报一次，避免刷屏）。 */
    static void reportMissing(long key) {
        if (MISSING.add(key)) {
            LOG.error("[cava/mirror] profileKey=0x{} 未注册 —— 调用方必须先 MobProfiles.define(spec)；"
                    + "本次求解必须回退原逻辑", Long.toHexString(key));
        }
    }

    private static final java.util.Set<Long> MISSING = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
