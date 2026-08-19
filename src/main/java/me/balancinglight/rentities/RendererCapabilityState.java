package me.balancinglight.rentities;

import me.balancinglight.rentities.compat.ClientShaderCompatibilityBackend;

import static org.lwjgl.opengl.GL11C.GL_VERSION;
import static org.lwjgl.opengl.GL11C.GL_VENDOR;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetString;
import static org.lwjgl.opengl.GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL43C.GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS;
import static org.lwjgl.opengl.GL30C.GL_NUM_EXTENSIONS;
import static org.lwjgl.opengl.GL30C.GL_EXTENSIONS;
import static org.lwjgl.opengl.GL30C.glGetStringi;

/** Central capability/health state for Rentities rendering backends. */
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

            // Compute shaders and multi-draw indirect are core in OpenGL 4.3.
            // Extension strings are used only as a compatibility fallback for unusual drivers.
            s.compute = s.gl43 || hasExtension("GL_ARB_compute_shader");
            s.indirect = s.gl43 || hasExtension("GL_ARB_multi_draw_indirect");
            // Buffer storage / persistent mapping became core in 4.4.
            s.persistentMapping = isAtLeast(4, 4, version) || hasExtension("GL_ARB_buffer_storage");

            ClientShaderCompatibilityBackend shaderBackend = new ClientShaderCompatibilityBackend();
            s.irisLoaded = !shaderBackend.allowsCustomRentitiesShader();
            s.customShader = s.gl43
                    && glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS) > 0
                    && shaderBackend.allowsCustomRentitiesShader();

            Rentities.LOGGER.info(
                    "[Rentities] Capabilities: GL={}, vendor={}, SSBO={}, compute={}, indirect={}, persistent={}, iris={}",
                    version, vendor, s.ssbo, s.compute, s.indirect, s.persistentMapping, s.irisLoaded);

            if (!s.gl43 || !s.ssbo) {
                s.gpuBatchingHealthy = false;
                s.gpuBatchingReason = "OpenGL 4.3+ SSBO support unavailable";
            }
            if (!s.compute || !s.indirect) {
                s.indirectHealthy = false;
                s.indirectReason = "Compute/indirect draw support unavailable";
            }
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
            s.indirectHealthy = false;
            s.customShaderHealthy = false;
            s.meshHealthy = false;
            s.gpuBatchingReason = "capability probe failed: " + t.getClass().getSimpleName();
            s.indirectReason = s.gpuBatchingReason;
            s.customShaderReason = s.gpuBatchingReason;
            s.meshReason = s.gpuBatchingReason;
            Rentities.LOGGER.error("[Rentities] Capability probe failed; using safe fallback", t);
        }
        return s;
    }

    private static boolean isAtLeast43(String version) {
        return isAtLeast(4, 3, version);
    }

    private static boolean isAtLeast(int requiredMajor, int requiredMinor, String version) {
        if (version == null || version.isBlank()) return false;
        try {
            String token = version.trim().split("\\s+", 2)[0];
            String[] parts = token.split("\\.", 3);
            if (parts.length < 2) return false;
            int major = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
            String minorText = parts[1].replaceAll("[^0-9].*", "");
            if (minorText.isEmpty()) return false;
            int minor = Integer.parseInt(minorText);
            return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor);
        } catch (RuntimeException ignored) {
            try {
                String token = version.trim().split("\\s+", 2)[0];
                String[] parts = token.split("\\.", 3);
                if (parts.length < 2) return false;
                int major = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
                int minor = Integer.parseInt(parts[1].replaceAll("[^0-9].*", ""));
                return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor);
            } catch (RuntimeException ignoredAgain) {
                return false;
            }
        }
    }

    private static boolean hasExtension(String name) {
        try {
            int count = glGetInteger(GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                if (name.equals(glGetStringi(GL_EXTENSIONS, i))) return true;
            }
        } catch (Throwable ignored) {
            // Conservative false: inability to inspect extensions must not enable an unsafe path.
        }
        return false;
    }

    public boolean isProbed() { return probed; }
    public boolean customShaderAllowed() { return customShader && customShaderHealthy; }
    public boolean meshGpuAllowed() { return meshGpu && meshHealthy; }
    public boolean persistentMappingAllowed() { return persistentMapping && persistentHealthy; }

    public boolean gpuBatchingAllowed(RentitiesConfig cfg) {
        return cfg != null && cfg.entity_batching_enabled && gpuBatchingHealthy && customShaderAllowed() && meshGpuAllowed();
    }

    public boolean indirectAllowed(RentitiesConfig cfg) {
        return cfg != null && cfg.gpu_frustum_culling_enabled && indirect && indirectHealthy && gpuBatchingHealthy;
    }

    public RenderPath choosePath(RentitiesConfig cfg) {
        if (!gpuBatchingAllowed(cfg)) return RenderPath.VANILLA;
        return RenderPath.GPU_BATCHING;
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

    public void resetTransientHealth() { }

    public String describe() {
        return "batching=" + gpuBatchingHealthy + " (" + gpuBatchingReason + ")" +
                ", culling=" + indirectHealthy + " (" + indirectReason + ")" +
                ", persistent=" + persistentHealthy + " (" + persistentReason + ")" +
                ", shader=" + customShaderHealthy + " (" + customShaderReason + ")" +
                ", mesh=" + meshHealthy + " (" + meshReason + ")";
    }
}
