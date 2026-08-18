package me.balancinglight.rentities.mixin.minecraft;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.entities.EntityBatchRenderer;
import me.balancinglight.rentities.entities.EntityMeshBaker;
import me.balancinglight.rentities.entities.EntityBatchRegistry;
import me.balancinglight.rentities.entities.EntityAnimationCategory;
import me.balancinglight.rentities.entities.EntityDirectExtractor;
import me.balancinglight.rentities.entities.EntityGlTextureResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.lang.reflect.Method;

@Mixin(targets = "net/minecraft/class_898", remap = false)
public abstract class MixinEntityRenderDispatcher {

    @Inject(method = "method_72976", at = @At("HEAD"), cancellable = true, remap = false)
    private void interceptEntityRender(
            @Coerce Object state,
            @Coerce Object light,
            double x, double y, double z,
            @Coerce Object poseStack,
            @Coerce Object buffers,
            CallbackInfo ci) {

        if (!Rentities.shouldInterceptEntity()) return;

        /*
         * Some entities are rendered as part of another renderer rather than
         * as normal world entities. Mob spawner previews are the important
         * example: the preview mob is rendered using a temporary local
         * PoseStack and must not be converted into a world-space Rentities
         * instance.
         *
         * EntityDirectExtractor maintains this guard with a ThreadLocal so
         * nested/special renders can temporarily opt out of batching.
         */
        if (EntityDirectExtractor.isSkippingBatching()) return;

        if (state == null) return;

        // Already queued by the direct extraction hook — nothing was ever populated on this
        // object, so bail out before anything reads it.
        if (state == EntityDirectExtractor.SENTINEL) {
            ci.cancel();
            return;
        }

        EntityType<?> type = EntityBatchRenderer.getEntityType(state);
        if (type == null) return;

        EntityAnimationCategory category = EntityBatchRegistry.getCategory(type);
        if (category == EntityAnimationCategory.CPU_ANIMATED) return;

        EntityBatchRenderer renderer = EntityBatchRenderer.INSTANCE;
        if (renderer == null) return;

        if (!renderer.hasMeshFor(type)) {
            EntityMeshBaker.MeshStatus status = renderer.getMeshBaker().ensureMeshFor(type);

            // UNKNOWN/BUILDING means extraction is not ready yet. Let vanilla render this
            // frame; the retry/backoff in the baker will attempt extraction again later.
            if (status != EntityMeshBaker.MeshStatus.READY || !renderer.hasMeshFor(type)) {
                return;
            }
        }

        if (renderer != null && !renderer.entityTextureLocs.containsKey(type)
                && !renderer.entityTexFailed.contains(type)) {
            tryResolveTexture(renderer, type, state);
        }

        // Texture resolution is part of the preflight contract: an unresolved texture must
        // never cancel vanilla rendering and leave only a hitbox visible.
        if (!renderer.canBatchEntity(type)) return;

        boolean queued = EntityBatchRenderer.queueEntityStateDirect(state, x, y, z, type);
        if (Rentities.IS_DEBUG)
            Rentities.LOGGER.info("Queued entity type={}, category={}, queued={}", type, category, queued);
        if (queued) {
            ci.cancel();
        }
    }

    private void tryResolveTexture(EntityBatchRenderer renderer, EntityType<?> type, Object state) {
        try {
            Object entityRenderer = null;
            Class<?> dispatcherClass = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class;
            java.lang.reflect.Field rf = null;
            try { rf = dispatcherClass.getDeclaredField("field_4696"); }
            catch (NoSuchFieldException ignored) {
                try { rf = dispatcherClass.getDeclaredField("renderers"); }
                catch (NoSuchFieldException ignored2) {}
            }
            if (rf != null) {
                rf.setAccessible(true);
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) rf.get(this);
                entityRenderer = map.get(type);
            }

            if (entityRenderer == null) {
                if (Rentities.IS_DEBUG) Rentities.LOGGER.warn(
                        "Could not find renderer for {} in EntityRenderDispatcher map", type);
                return;
            }

            // 1.21.11: living-entity textures are resolved from the render state.
            // Never select an arbitrary Identifier field: render states may contain
            // unrelated resource identifiers.
            Method textureMethod = findTextureGetter(entityRenderer.getClass(), state.getClass());
            if (textureMethod != null) {
                textureMethod.setAccessible(true);
                Object loc = textureMethod.invoke(entityRenderer, state);
                if (loc != null) {
                    int glId = EntityGlTextureResolver.resolveGlId(loc);
                    if (glId > 0) {
                        renderer.entityTextureLocs.put(type, loc);
                        renderer.entityGlTexIds.put(type, glId);
                        renderer.entityTexFailed.remove(type);
                        return;
                    }
                }
            }

            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn(
                    "State-based texture resolution failed for {}", type);
        } catch (Throwable e) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn(
                        "Texture lookup via render state failed for {}: {}", type, e.toString());
            }
        }
    }

    private static java.lang.reflect.Method findTextureGetter(Class<?> cls, Class<?> stateClass) {
        String[] names = {"method_3885", "getTexture", "getTextureLocation", "method_4216"};
        for (String name : names) {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                    if (!m.getParameterTypes()[0].isAssignableFrom(stateClass)) continue;
                    return m;
                }
            }
        }
        return null;
    }
}
