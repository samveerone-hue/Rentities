package me.balancinglight.rentities.render;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.RentitiesConfig;

/** Single authoritative renderer-path selector. Unsupported paths fail open to vanilla. */
public final class RendererBackendManager {
    public enum Backend { VANILLA, GPU_INSTANCED, GPU_INDIRECT }

    public Backend select() {
        RentitiesConfig cfg = Rentities.config;
        if (cfg == null || !cfg.entity_batching_enabled) return Backend.VANILLA;
        if (cfg.rendererBackend == RentitiesConfig.RendererBackendMode.VANILLA ||
            cfg.rendererBackend == RentitiesConfig.RendererBackendMode.CPU) return Backend.VANILLA;
        boolean gpu = Rentities.CAPABILITIES == null || Rentities.CAPABILITIES.gpuBatchingAllowed(Rentities.config);
        if (!gpu) return Backend.VANILLA;
        if (cfg.rendererBackend == RentitiesConfig.RendererBackendMode.INDIRECT) {
            boolean indirect = Rentities.CAPABILITIES != null &&
                    Rentities.CAPABILITIES.indirectAllowed(cfg) && cfg.gpu_frustum_culling_enabled;
            return indirect ? Backend.GPU_INDIRECT : Backend.GPU_INSTANCED;
        }
        if (cfg.rendererBackend == RentitiesConfig.RendererBackendMode.GPU ||
            cfg.rendererBackend == RentitiesConfig.RendererBackendMode.AUTO) return Backend.GPU_INSTANCED;
        return Backend.VANILLA;
    }
}
