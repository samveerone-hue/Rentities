package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Resolves default entity textures from {@link EntityRenderer} instances at mesh bake time,
 * so batched entities never need a vanilla render state just to discover a texture location.
 */
public final class EntityTextureBootstrap {

    private EntityTextureBootstrap() {}

    public static void bootstrap(EntityBatchRenderer renderer,
                                 Map<EntityType<?>, EntityRenderer<?, ?>> rendererMap) {
        if (renderer == null || rendererMap == null) return;

        for (EntityType<?> type : EntityBatchRegistry.REGISTRY_TYPES()) {
            if (EntityBatchRegistry.getCategory(type) == EntityAnimationCategory.CPU_ANIMATED) continue;
            if (renderer.entityTextureLocs.containsKey(type)) continue;
            if (renderer.entityTexFailed.contains(type)) continue;

            EntityRenderer<?, ?> entityRenderer = rendererMap.get(type);
            if (entityRenderer == null) continue;

            Object loc = resolveTexture(entityRenderer, type);
            if (loc != null) {
                renderer.entityTextureLocs.put(type, loc);
                int glId = EntityGlTextureResolver.resolveGlId(loc);
                if (glId > 0) {
                    renderer.entityGlTexIds.put(type, glId);
                }
                if (Rentities.IS_DEBUG) {
                    Rentities.LOGGER.info("Bootstrapped texture for {}: {}", type, loc);
                }
            } else {
                renderer.entityTexFailed.add(type);
                if (Rentities.IS_DEBUG) {
                    Rentities.LOGGER.warn("Texture bootstrap failed for {}", type);
                }
            }
        }
    }

    private static Object resolveTexture(EntityRenderer<?, ?> renderer, EntityType<?> type) {
        Entity stub = EntityFactory.getOrCreateDummy(type);
        if (stub != null) {
            Object loc = invokeTextureMethod(renderer, stub);
            if (loc != null) return loc;
        }
        return findTextureField(renderer);
    }

    private static Object invokeTextureMethod(EntityRenderer<?, ?> renderer, Entity stub) {
        EntityType<?> type = stub.getType();
        for (Method m : renderer.getClass().getMethods()) {
            if (!m.getName().equals("method_3885") && !m.getName().equals("getTextureLocation")) continue;

            Class<?>[] params = m.getParameterTypes();
            try {
                if (params.length == 1 && params[0].isInstance(stub)) {
                    return m.invoke(renderer, stub);
                }
                if (params.length == 2 && params[0].isInstance(stub) && params[1] == float.class) {
                    return m.invoke(renderer, stub, 0.0f);
                }
            } catch (Throwable t) {
                if (Rentities.IS_DEBUG) {
                    Rentities.LOGGER.warn("getTextureLocation invoke failed for {}: {}", type, t.getMessage());
                }
            }
        }
        return null;
    }

    private static Object findTextureField(EntityRenderer<?, ?> renderer) {
        Class<?> cls = renderer.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                String typeName = f.getType().getSimpleName();
                if (!typeName.equals("ResourceLocation") && !typeName.equals("Identifier")) continue;
                try {
                    f.setAccessible(true);
                    Object loc = f.get(renderer);
                    if (loc != null) return loc;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }
}
