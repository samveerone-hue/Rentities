package me.balancinglight.rentities.mixin.minecraft;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.entities.EntityBatchRenderer;
import me.balancinglight.rentities.entities.EntityDirectExtractor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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

        EntityBatchRenderer.beginWorldRender(positionMatrix, projectionMatrix);
    }

    @Inject(method = "method_72914", at = @At("HEAD"), cancellable = true, remap = false)
    private void extractEntityDirect(Entity entity, float partialTick,
                                     CallbackInfoReturnable<EntityRenderState> cir) {
        if (!Rentities.IS_ENABLED) return;
        // Keep a single camera snapshot for the whole world render pass. Updating cameraX/Y/Z
        // per entity makes batched entities move when camera mods adjust zoom/camera state mid-pass.
        EntityBatchRenderer.ensurePrepared();
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
