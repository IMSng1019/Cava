package cava.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口（保留；服务端不加载）。
 *
 * <p>P0 不注入任何客户端逻辑：Cava 是服务端 mod，客户端类只是模板保留下来的占位。
 */
public class CavaClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("cava/client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[cava/client] 客户端入口已加载（P0 无客户端逻辑）");
    }
}
