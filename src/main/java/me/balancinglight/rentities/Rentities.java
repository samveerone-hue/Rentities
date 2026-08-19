package me.balancinglight.rentities;

import me.balancinglight.rentities.render.RendererBackendManager;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rentities implements ClientModInitializer {
    private static final RendererBackendManager BACKEND_MANAGER = new RendererBackendManager();


    public static final Logger LOGGER = LoggerFactory.getLogger("rentities");
    public static final boolean IS_DEBUG = System.getProperty("rentities.debug") != null;

    public static volatile boolean IS_ENABLED    = false;
    public static volatile boolean IS_COMPATIBLE = false;
    public static volatile RendererCapabilityState CAPABILITIES;

    public static volatile RentitiesConfig config = RentitiesConfig.loadOrCreate();

    @Override
    public void onInitializeClient() {
        LOGGER.info("Rentities initializing...");
        // when the first world loads and GL is ready.
    }

    public static synchronized void checkAndEnable() {
        if (CAPABILITIES == null) {
            CAPABILITIES = RendererCapabilityState.probe();
            IS_COMPATIBLE = CAPABILITIES.isProbed();
            LOGGER.info("[Rentities] renderer capability state: {}", CAPABILITIES.describe());
        }
        IS_ENABLED = IS_COMPATIBLE && BACKEND_MANAGER.select() != RendererBackendManager.Backend.VANILLA;
    }

    public static boolean shouldInterceptEntity() {
        if (!IS_COMPATIBLE || config == null) return false;
        return BACKEND_MANAGER.select() != RendererBackendManager.Backend.VANILLA;
    }

    public static void disableFeature(RendererCapabilityState.Feature feature, Throwable error) {
        if (CAPABILITIES != null) CAPABILITIES.markFailed(feature, error);
    }
}

