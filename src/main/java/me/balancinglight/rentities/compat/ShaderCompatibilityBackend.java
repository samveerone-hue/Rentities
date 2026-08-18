package me.balancinglight.rentities.compat;

public interface ShaderCompatibilityBackend {
    boolean allowsCustomRentitiesShader();
    String reason();
}
