package me.balancinglight.rentities.mixin.minecraft;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.entities.EntityBatchRenderer;
import me.balancinglight.rentities.entities.EntityDirectExtractor;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.class_761", remap = false)
public class MixinLevelRenderer {

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void onInit(CallbackInfo ci) {
        Rentities.checkAndEnable();

        if (Rentities.IS_ENABLED && EntityBatchRenderer.INSTANCE == null) {
            new EntityBatchRenderer();
        }
    }

    @Inject(method = "close", at = @At("TAIL"), remap = false)
    private void onClose(CallbackInfo ci) {
        if (EntityBatchRenderer.INSTANCE != null) {
            EntityBatchRenderer.INSTANCE.delete();
        }
    }

    @Inject(method = "method_22710", at = @At("HEAD"), remap = false)
    private void captureWorldMatrices(
            @Coerce Object allocator,
            @Coerce Object deltaTracker,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            @Coerce Object basicProjectionMatrix,
            Matrix4f projectionMatrix,
            @Coerce Object fogBuffer,
            @Coerce Object fogColor,
            boolean renderSky,
            CallbackInfo ci) {
        if (!Rentities.IS_ENABLED || EntityBatchRenderer.INSTANCE == null) return;

        Vec3 camPos = camera.position();
        EntityBatchRenderer.cameraX = camPos.x;
        EntityBatchRenderer.cameraY = camPos.y;
        EntityBatchRenderer.cameraZ = camPos.z;

        EntityBatchRenderer.setViewMatrix(new Matrix4f(positionMatrix));
        EntityBatchRenderer.updateProjectionMatrix(new Matrix4f(projectionMatrix));
    }

    /**
     * {@code LevelRenderer.extractEntity} is the allocation site for the vanilla render state:
     * it delegates to {@code EntityRenderDispatcher.extractEntity}, which creates the state
     * object and populates it from the model, texture, equipment and name tag. Batched
     * entities never need any of that, so the extraction is replaced wholesale here rather
     * than cancelled after the fact at submit time.
     *
     * <p>The caller adds the returned state to a list and dereferences it, so a shared
     * sentinel is returned instead of null; the dispatcher mixin recognises it and cancels
     * the submit without touching it.
     */
    @Inject(method = "method_72914", at = @At("HEAD"), cancellable = true, remap = false)
    private void extractEntityDirect(Entity entity, float partialTick,
                                     CallbackInfoReturnable<EntityRenderState> cir) {
        if (!Rentities.IS_ENABLED) return;
        if (EntityDirectExtractor.tryExtract(entity, partialTick)) {
            cir.setReturnValue(EntityDirectExtractor.SENTINEL);
        }
    }

    @Inject(method = "method_62214", at = @At("TAIL"), remap = false)
    private void afterRenderEntities(CallbackInfo ci) {
        if (!Rentities.IS_ENABLED) return;
        EntityBatchRenderer.flushBatch();
    }
}
