package me.balancinglight.rentities.mixin.minecraft;

import me.balancinglight.rentities.entities.EntityDirectExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.class_824", remap = false)
public abstract class MixinBlockEntityRenderDispatcher {

    /**
     * Block-entity renderers can render temporary entities.
     *
     * The classic example is a mob spawner: its mob preview is rendered
     * through the entity renderer, but the entity is NOT a world entity
     * that should be submitted to Rentities as a normal GPU instance.
     *
     * Disable Rentities batching for the entire block-entity submission
     * so nested entity renders fall back to vanilla rendering. In modern
     * mappings method_3555 is BlockEntityRenderDispatcher#render, which is the
     * correct outer boundary for mob-spawner previews.
     */
    @Inject(
            method = "method_3555",
            at = @At("HEAD"),
            remap = false
    )
    private void rentities$beginBlockEntityRender(CallbackInfo ci) {
        EntityDirectExtractor.setSkipBatching(true);
    }

    @Inject(
            method = "method_3555",
            at = @At("RETURN"),
            remap = false
    )
    private void rentities$endBlockEntityRender(CallbackInfo ci) {
        EntityDirectExtractor.setSkipBatching(false);
    }
}
