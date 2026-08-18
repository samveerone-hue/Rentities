package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Resolves entity textures using Minecraft 1.21.11's render-state API.
 *
 * In 1.21.11 LivingEntityRenderer.getTexture(...) consumes the render state, not the
 * entity itself. Using the old entity-argument lookup can silently return no texture
 * for ordinary mobs while player renderers appear to work through their skin state.
 */
public final class EntityTextureBootstrap {
    private EntityTextureBootstrap() {}

    public static void bootstrap(EntityBatchRenderer renderer,
                                 Map<EntityType<?>, EntityRenderer<?, ?>> rendererMap) {
        if (renderer == null || rendererMap == null) return;

        for (EntityType<?> type : EntityBatchRegistry.REGISTRY_TYPES()) {
            if (EntityBatchRegistry.getCategory(type) == EntityAnimationCategory.CPU_ANIMATED) continue;
            if (renderer.entityTextureLocs.containsKey(type) && renderer.entityGlTexIds.containsKey(type)) continue;
            if (renderer.entityTexFailed.contains(type)) continue;

            EntityRenderer<?, ?> entityRenderer = rendererMap.get(type);
            if (entityRenderer == null) continue;

            Object loc = resolveTexture(entityRenderer, type);
            if (loc == null) {
                if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Texture bootstrap failed for {}", type);
                continue;
            }

            int glId = EntityGlTextureResolver.resolveGlId(loc);
            if (glId > 0) {
                renderer.entityTextureLocs.put(type, loc);
                renderer.entityGlTexIds.put(type, glId);
                renderer.entityTexFailed.remove(type);
                if (Rentities.IS_DEBUG) {
                    Rentities.LOGGER.info("Bootstrapped texture for {}: {} -> GL {}", type, loc, glId);
                }
            } else if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn("Texture resolved for {} but has no valid GL texture: {}", type, loc);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object resolveTexture(EntityRenderer renderer, EntityType<?> type) {
        Entity stub = EntityFactory.getOrCreateDummy(type);
        if (stub == null) return null;

        try {
            // Minecraft 1.21.11: create + update the renderer's actual render-state object,
            // then call the state-based getTexture method (method_3885 for LivingEntityRenderer).
            Object state = renderer.createRenderState();
            Method update = findOneArgOrTwoArg(renderer.getClass(), "updateRenderState", "method_62354", state.getClass(), stub.getClass());
            if (update != null) {
                update.setAccessible(true);
                if (update.getParameterCount() == 3) {
                    update.invoke(renderer, stub, state, 0.0f);
                } else {
                    update.invoke(renderer, stub, state);
                }
            } else {
                Method finalUpdate = findMethod(renderer.getClass(), "method_62354", 3);
                if (finalUpdate != null) {
                    finalUpdate.setAccessible(true);
                    finalUpdate.invoke(renderer, stub, state, 0.0f);
                }
            }

            Object loc = invokeTextureGetter(renderer, state);
            if (loc != null) return loc;
        } catch (Throwable t) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn("State-based texture resolution failed for {}: {}", type, t.toString());
            }
        }
        return null;
    }

    private static Method invokeTextureMethod(Class<?> cls, String name, Object state) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!(m.getName().equals(name)
                        || m.getName().equals("method_3885")
                        || m.getName().equals("getTexture")
                        || m.getName().equals("getTextureLocation"))) continue;
                if (!m.getParameterTypes()[0].isInstance(state)) continue;
                return m;
            }
        }
        return null;
    }

    private static Object invokeTextureGetter(Object renderer, Object state) throws Exception {
        String[] names = {"method_3885", "getTexture", "getTextureLocation", "method_4216"};
        for (String name : names) {
            Method m = invokeTextureMethod(renderer.getClass(), name, state);
            if (m == null) continue;
            m.setAccessible(true);
            Object value = m.invoke(renderer, state);
            if (value != null) return value;
        }
        return null;
    }

    private static Method findOneArgOrTwoArg(Class<?> cls, String named, String intermediary,
                                              Class<?> stateClass, Class<?> entityClass) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(named) && !m.getName().equals(intermediary)) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0].isAssignableFrom(entityClass)
                        && p[1].isAssignableFrom(stateClass) && p[2] == float.class) return m;
                if (p.length == 2 && p[0].isAssignableFrom(entityClass)
                        && p[1].isAssignableFrom(stateClass)) return m;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, int arity) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
            }
        }
        return null;
    }

}
