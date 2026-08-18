package me.balancinglight.rentities;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rentities implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("rentities");
    public static final boolean IS_DEBUG = System.getProperty("rentities.debug") != null;

    public static volatile boolean IS_ENABLED    = false;
    public static volatile boolean IS_COMPATIBLE = false;
    public static volatile RendererCapabilityState CAPABILITIES;

    public static RentitiesConfig config = RentitiesConfig.loadOrCreate();

    @Override
    public void onInitializeClient() {
        LOGGER.info("Rentities initializing...");
        // when the first world loads and GL is ready.
    }

    public static void checkAndEnable() {
        if (CAPABILITIES == null) {
            CAPABILITIES = RendererCapabilityState.probe();
            IS_COMPATIBLE = CAPABILITIES.isProbed();
            LOGGER.info("[Rentities] renderer capability state: {}", CAPABILITIES.describe());
        }
        IS_ENABLED = IS_COMPATIBLE && config.entity_batching_enabled &&
                CAPABILITIES.choosePath(config) != RendererCapabilityState.RenderPath.VANILLA;
    }

    public static boolean shouldInterceptEntity() {
        if (!IS_COMPATIBLE || !config.entity_batching_enabled || CAPABILITIES == null) return false;
        return CAPABILITIES.choosePath(config) != RendererCapabilityState.RenderPath.VANILLA;
    }

    public static void disableFeature(RendererCapabilityState.Feature feature, Throwable error) {
        if (CAPABILITIES != null) CAPABILITIES.markFailed(feature, error);
        if (feature == RendererCapabilityState.Feature.GPU_BATCHING && CAPABILITIES != null &&
                CAPABILITIES.choosePath(config) == RendererCapabilityState.RenderPath.VANILLA) {
            IS_ENABLED = false;
        }
    }
}

