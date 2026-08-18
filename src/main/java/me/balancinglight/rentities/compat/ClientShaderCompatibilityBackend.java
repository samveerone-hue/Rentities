package me.balancinglight.rentities.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Compatibility policy for custom entity shaders. Iris is detected without a compile-time
 * dependency; until a proper Iris shader backend exists, Rentities yields to the vanilla/Iris path.
 */
public final class ClientShaderCompatibilityBackend implements ShaderCompatibilityBackend {
    private final boolean iris;
    public ClientShaderCompatibilityBackend() { this.iris = FabricLoader.getInstance().isModLoaded("iris"); }
    public boolean allowsCustomRentitiesShader() { return !iris; }
    public String reason() { return iris ? "Iris detected" : "no shader compatibility conflict detected"; }
}
