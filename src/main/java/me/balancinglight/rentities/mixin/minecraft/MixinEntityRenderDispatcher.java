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
            Class<?> cls = state.getClass();
            while (cls != null && cls != Object.class) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    String typeName = f.getType().getSimpleName();
                    if (typeName.equals("ResourceLocation") || typeName.equals("Identifier")) {
                        f.setAccessible(true);
                        Object loc = f.get(state);
                        if (loc != null) {
                            if (Rentities.IS_DEBUG)
                                Rentities.LOGGER.info("Found texture loc via state field {} = {}", f.getName(), loc);
                            renderer.entityTextureLocs.put(type, loc);
                            return;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Texture lookup via render state failed for {}: {}", type, e.getMessage());
        }

        try {
            String targetKey = "entity.minecraft." + type.toShortString(); // "entity.minecraft.zombie"
            if (Rentities.IS_DEBUG)
                Rentities.LOGGER.info("Looking for renderer, targetKey={}", targetKey);

            Object entityRenderer = null;
            try {
                java.lang.reflect.Field rf = null;
                Class<?> dispatcherClass = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class;
                try { rf = dispatcherClass.getDeclaredField("field_4696"); }
                catch (NoSuchFieldException ignored) {
                    try { rf = dispatcherClass.getDeclaredField("renderers"); }
                    catch (NoSuchFieldException ignored2) {}
                }
                if (rf != null) {
                    rf.setAccessible(true);
                    java.util.Map<?,?> map = (java.util.Map<?,?>) rf.get(this);
                    if (Rentities.IS_DEBUG)
                        Rentities.LOGGER.info("field_4696 map size={}", map.size());
                    // 1.21.11 keys this map by EntityType, not a string identifier.
                    entityRenderer = map.get(type);
                    if (entityRenderer == null) {
                        // Compatibility fallback for unusual map implementations.
                        for (java.util.Map.Entry<?,?> entry : map.entrySet()) {
                            if (entry.getKey() == type || (entry.getKey() != null && type.equals(entry.getKey()))) {
                                entityRenderer = entry.getValue();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("field_4696 access failed: {}", e.getMessage());
            }

            if (entityRenderer == null) {
                if (Rentities.IS_DEBUG)
                    Rentities.LOGGER.warn("Could not find renderer for {} in renderers map", targetKey);
            } else {
                Class<?> rc = entityRenderer.getClass();
                while (rc != null && rc != Object.class) {
                    for (java.lang.reflect.Method m : rc.getDeclaredMethods()) {
                        if (m.getParameterCount() == 1 &&
                            (m.getName().equals("method_3885") ||
                             m.getName().equals("getTexture") ||
                             m.getName().equals("getTextureLocation") ||
                             m.getName().equals("method_4216"))) {
                            m.setAccessible(true);
                            try {
                                Object loc = m.invoke(entityRenderer, state);
                                if (Rentities.IS_DEBUG)
                                    Rentities.LOGGER.info("method_3885({}) = {}", targetKey, loc);
                                if (loc != null) {
                                    // the new GpuTexture pipeline in 1.21.11
                                    renderer.entityTextureLocs.put(type, loc);
                                    int glId = EntityGlTextureResolver.resolveGlId(loc);
                                    if (glId > 0) renderer.entityGlTexIds.put(type, glId);
                                    if (Rentities.IS_DEBUG)
                                        Rentities.LOGGER.info("Cached texture loc for {}: {}", type, loc);
                                    return;
                                }
                            } catch (Exception e) {
                                if (Rentities.IS_DEBUG)
                                    Rentities.LOGGER.warn("method_3885 invoke failed: {}", e.getMessage());
                            }
                        }
                    }
                    rc = rc.getSuperclass();
                }
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Texture lookup via renderer map failed for {}: {}", type, e.getMessage());
        }

        if (Rentities.IS_DEBUG)
            Rentities.LOGGER.warn("Could not resolve texture for {}", type);
        renderer.entityTexFailed.add(type);
    }
}
