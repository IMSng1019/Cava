package cava;

import cava.ffm.CavaNative;
import cava.parity.TickSampler;
import cava.subsystem.CavaSubsystem;
import cava.subsystem.EntitySubsystem;
import cava.subsystem.PathfindSubsystem;
import cava.subsystem.RedstoneSubsystem;
import cava.subsystem.SubsystemRegistry;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cava 的唯一入口（契约第 1 节：{@code cava.Cava}）。
 *
 * <p>P0 只做：读 config → 打开原生库 → 打印启动横幅 → 注册三个空子系统 → SERVER_STARTED 跑金丝雀自检。
 * **不注入任何游戏逻辑**。
 */
public class Cava implements ModInitializer {

    /** mod id。 */
    public static final String MOD_ID = "cava";

    /** 日志器。 */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 版本（来自 fabric.mod.json）。 */
    public static final String VERSION = FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");

    private static CavaConfig config;

    /** 当前配置（onInitialize 之后可用）。 */
    public static CavaConfig config() {
        return config;
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        config = CavaConfig.load(gameDir.resolve("config").resolve("cava.json"));

        CavaNative nat = CavaNative.get();
        nat.configure(gameDir, VERSION);
        nat.setConfiguredEnabled(config.nativeEnabled());
        boolean nativeOpen = nat.tryOpen();

        SubsystemRegistry registry = SubsystemRegistry.get();
        registry.register(new PathfindSubsystem());
        registry.register(new EntitySubsystem());
        registry.register(new RedstoneSubsystem());

        if (!nativeOpen) {
            registry.disableAll("native 不可用（" + nat.status() + "）：整体回退纯 Java");
        } else {
            for (CavaSubsystem s : registry.all()) {
                if (config.ownership(s.id()) == CavaConfig.Ownership.DEFER) {
                    s.disable("config/cava.json ownership=defer（让位给其它 mod）");
                }
            }
        }

        printBanner(nat, registry);

        TickSampler.init();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            registry.canaryProbeAll();
            if (Boolean.parseBoolean(System.getProperty("cava.canary.selftest", "false"))) {
                boolean ok = registry.canarySelfTest();
                LOGGER.info("[cava] 金丝雀框架自检 {}", ok ? "PASS" : "FAIL");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[cava] 服务端停止：关闭原生句柄");
            nat.close();
        });
    }

    private void printBanner(CavaNative nat, SubsystemRegistry registry) {
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator()).append("  ################ Cava ").append(VERSION)
                .append("  （Java 21 预览版 FFM + C++ 原生；P0 骨架，未注入任何游戏逻辑） ################");
        sb.append(System.lineSeparator()).append("  MC ").append(modVersion("minecraft"))
                .append(" / Fabric Loader ").append(modVersion("fabricloader"))
                .append(" / 平台 ").append(System.getProperty("os.name")).append('-').append(System.getProperty("os.arch"));
        sb.append(System.lineSeparator()).append(nat.banner());
        sb.append(System.lineSeparator());
        for (String line : config.describeLines()) {
            sb.append("  ").append(line).append(System.lineSeparator());
        }
        sb.append("  子系统归属表（contract §5：每项重叠都要有显式归属）").append(System.lineSeparator());
        sb.append(String.format("    %-9s %-14s %-8s %-8s %s%n", "subsystem", "ownership", "enabled", "hooks", "备注"));
        for (CavaSubsystem s : registry.all()) {
            sb.append(String.format("    %-9s %-14s %-8s %-8s %s%n", s.id(),
                    config.ownership(s.id()).jsonName(), s.enabled(), s.hooksInstalled(),
                    s.disabledReason().isEmpty() ? (s.enabled() ? "正常" : "P0 骨架（无钩子）") : s.disabledReason()));
        }
        sb.append("  ################################################################################");
        LOGGER.info(sb.toString());
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
