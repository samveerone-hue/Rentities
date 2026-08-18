package me.balancinglight.rentities;

import me.balancinglight.rentities.compat.ClientShaderCompatibilityBackend;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL43C.GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS;
import static org.lwjgl.opengl.GL30C.glGetStringi;
import static org.lwjgl.opengl.GL30C.GL_NUM_EXTENSIONS;

/**
 * Central capability/health state for Rentities' rendering backends.
 *
 * <p>Features fail independently. A failed GPU optimization falls back to the next
 * renderer path instead of cancelling Minecraft's entity render and making an entity
 * disappear.</p>
 */
public final class RendererCapabilityState {
    public enum RenderPath { GPU_BATCHING, CPU_INSTANCED, VANILLA }
    public enum Feature { GPU_BATCHING, INDIRECT_CULLING, PERSISTENT_MAPPING, CUSTOM_SHADER, MESH_GPU, STATE_CACHE }

    private boolean probed;
    private boolean gl43;
    private boolean ssbo;
    private boolean compute;
    private boolean indirect;
    private boolean persistentMapping;
    private boolean customShader;
    private boolean meshGpu;
    private boolean irisLoaded;

    private boolean gpuBatchingHealthy = true;
    private boolean indirectHealthy = true;
    private boolean persistentHealthy = true;
    private boolean customShaderHealthy = true;
    private boolean meshHealthy = true;
    private boolean stateCacheHealthy = true;

    private String gpuBatchingReason = "ready";
    private String indirectReason = "ready";
    private String persistentReason = "ready";
    private String customShaderReason = "ready";
    private String meshReason = "ready";
    private String stateCacheReason = "ready";

    public static RendererCapabilityState probe() {
        RendererCapabilityState s = new RendererCapabilityState();
        s.probed = true;
        try {
            String version = glGetString(GL_VERSION);
            String vendor = glGetString(GL_VENDOR);
            s.gl43 = isAtLeast43(version);
            s.ssbo = s.gl43 && glGetInteger(GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS) > 0;
            s.compute = s.gl43 && hasExtension("GL_ARB_compute_shader");
            s.persistentMapping = s.gl43 && hasExtension("GL_ARB_buffer_storage");
            s.indirect = s.gl43 && s.persistentMapping && s.compute && hasExtension("GL_ARB_multi_draw_indirect");
            ClientShaderCompatibilityBackend shaderBackend = new ClientShaderCompatibilityBackend();
            s.irisLoaded = !shaderBackend.allowsCustomRentitiesShader();
            s.customShader = s.gl43 && glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) > 0 && shaderBackend.allowsCustomRentitiesShader();

            Rentities.LOGGER.info("[Rentities] Capabilities: GL={}, vendor={}, SSBO={}, compute={}, indirect={}, persistent={}, iris={}",
                    version, vendor, s.ssbo, s.compute, s.indirect, s.persistentMapping, s.irisLoaded);

            if (!s.gl43 || !s.ssbo || !s.compute || !s.indirect) {
                s.gpuBatchingHealthy = false;
                s.gpuBatchingReason = "OpenGL 4.3 compute/SSBO/indirect support unavailable";
            }
            // Rentities' custom shader path must not compete with Iris until a real shader backend exists.
            if (s.irisLoaded) {
                s.customShader = false;
                s.customShaderHealthy = false;
                s.customShaderReason = "Iris detected; custom Rentities shader backend is disabled for compatibility";
            }
            s.meshGpu = s.gl43;
            if (!s.meshGpu) {
                s.meshHealthy = false;
                s.meshReason = "OpenGL 4.3+ DSA unavailable";
            }
        } catch (Throwable t) {
            s.gpuBatchingHealthy = false;
            s.customShaderHealthy = false;
            s.meshHealthy = false;
            s.gpuBatchingReason = "capability probe failed: " + t.getClass().getSimpleName();
            s.customShaderReason = s.gpuBatchingReason;
            s.meshReason = s.gpuBatchingReason;
            Rentities.LOGGER.error("[Rentities] Capability probe failed; using safe fallback", t);
        }
        return s;
    }

    private static boolean isAtLeast43(String version) {
        if (version == null) return false;
        try {
            String[] p = version.split("\\.", 3);
            int major = Integer.parseInt(p[0].replaceAll("[^0-9]", ""));
            int minor = p.length > 1 ? Integer.parseInt(p[1].replaceAll("[^0-9].*", "")) : 0;
            return major > 4 || (major == 4 && minor >= 3);
        } catch (Exception ignored) {
            return version.contains("4.3") || version.contains("4.4") || version.contains("4.5") || version.contains("4.6");
        }
    }

    private static boolean hasExtension(String name) {
        try {
            int count = glGetInteger(GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String e = glGetStringi(GL_EXTENSIONS, i);
                if (name.equals(e)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean isProbed() { return probed; }
    public boolean customShaderAllowed() { return customShader && customShaderHealthy; }
    public boolean meshGpuAllowed() { return meshGpu && meshHealthy; }
    public boolean persistentMappingAllowed() { return persistentMapping && persistentHealthy; }
    public boolean gpuBatchingAllowed(RentitiesConfig cfg) {
        return cfg.entity_batching_enabled && gpuBatchingHealthy && customShaderAllowed() && meshGpuAllowed();
    }
    public boolean indirectAllowed(RentitiesConfig cfg) { return cfg.gpu_frustum_culling_enabled && indirect && indirectHealthy && gpuBatchingHealthy; }

    public RenderPath choosePath(RentitiesConfig cfg) {
        if (!cfg.entity_batching_enabled) return RenderPath.VANILLA;
        if (!customShaderAllowed() || !meshGpuAllowed()) return RenderPath.VANILLA;
        if (gpuBatchingAllowed(cfg)) return RenderPath.GPU_BATCHING;
        return RenderPath.CPU_INSTANCED;
    }

    public void markFailed(Feature feature, Throwable error) {
        String reason = error == null ? "runtime validation failed" :
                error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        switch (feature) {
            case GPU_BATCHING -> { gpuBatchingHealthy = false; gpuBatchingReason = reason; }
            case INDIRECT_CULLING -> { indirectHealthy = false; indirectReason = reason; }
            case PERSISTENT_MAPPING -> { persistentHealthy = false; persistentReason = reason; }
            case CUSTOM_SHADER -> { customShaderHealthy = false; customShaderReason = reason; }
            case MESH_GPU -> { meshHealthy = false; meshReason = reason; }
            case STATE_CACHE -> { stateCacheHealthy = false; stateCacheReason = reason; }
        }
        Rentities.LOGGER.error("[Rentities] Disabled feature {} for safety: {}", feature, reason, error);
    }

    public void resetTransientHealth() {
        // Intentionally does not re-enable failed features mid-frame. Restart/world reload is the safe recovery boundary.
    }

    public String describe() {
        return "batching=" + gpuBatchingHealthy + " (" + gpuBatchingReason + ")" +
                ", culling=" + indirectHealthy + " (" + indirectReason + ")" +
                ", persistent=" + persistentHealthy + " (" + persistentReason + ")" +
                ", shader=" + customShaderHealthy + " (" + customShaderReason + ")" +
                ", mesh=" + meshHealthy + " (" + meshReason + ")";
    }
}
