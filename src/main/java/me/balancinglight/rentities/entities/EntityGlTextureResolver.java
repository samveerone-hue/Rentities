package me.balancinglight.rentities.entities;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves OpenGL texture names from Minecraft 1.21.11 texture identifiers.
 *
 * 1.21.11 no longer exposes the old TextureManager.bindTexture path. In particular,
 * The old TextureManager bind/destroy reflection path is deliberately not used.
 *
 * The 1.21.11 path is:
 *   TextureManager.getTexture (method_4619)
 *       -> AbstractTexture.getGlTexture (method_68004)
 *       -> concrete GlTexture integer handle
 *
 * We resolve the GL name without mutating Minecraft's currently bound texture.
 */
public final class EntityGlTextureResolver {

    private static volatile Object textureManager;
    private static volatile Method getTexMethod;
    private static volatile MethodHandle glTextureGetter;
    private static volatile MethodHandle glTextureIdGetter;

    private static final Map<String, Integer> GL_ID_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Object> GL_TEXTURE_OBJECT_CACHE = new ConcurrentHashMap<>();

    private EntityGlTextureResolver() {}

        private static Object resolveGpuTextureObject(Object loc) {
        try {
            if (textureManager == null || getTexMethod == null) return null;
            return getTexMethod.invoke(textureManager, loc);
        } catch (Throwable ignored) {
            return null;
        }
    }

public static int resolveGlId(Object loc) {
        if (loc == null) return 0;
        // OpenGL texture queries are only valid while the client render context is current.
        if (!RenderSystem.isOnRenderThread()) return 0;

        String key = String.valueOf(loc);
        Integer cached = GL_ID_CACHE.get(key);
        Object cachedTexture = GL_TEXTURE_OBJECT_CACHE.get(key);
        if (cached != null && cachedTexture != null && cached > 0 && GL11.glIsTexture(cached)) {
            Object currentTexture = resolveGpuTextureObject(loc);
            if (currentTexture != null && currentTexture == cachedTexture) {
                return cached;
            }
        }

try {
            ensureMethods(loc);
            if (textureManager == null || getTexMethod == null) return 0;

            Object texObj = getTexMethod.invoke(textureManager, loc);
            if (texObj == null) return 0;

            Object gpuTex = resolveGpuTexture(texObj);
            if (gpuTex == null) return 0;

            int glId = readGpuTexId(gpuTex);
            if (glId > 0 && org.lwjgl.opengl.GL11.glIsTexture(glId)) {
                GL_ID_CACHE.put(key, glId);
                Object currentTexture = resolveGpuTextureObject(loc);
                if (currentTexture != null) {
                    GL_TEXTURE_OBJECT_CACHE.put(key, currentTexture);
                }
                return glId;
            }
        } catch (Throwable t) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn(
                        "[Rentities] Texture ID resolution failed for {}: {}",
                        loc, t.toString());
            }
        }
        return 0;
    }

    public static void invalidateCache() {
        GL_ID_CACHE.clear();
        GL_TEXTURE_OBJECT_CACHE.clear();
        textureManager = null;
        getTexMethod = null;
        glTextureGetter = null;
        glTextureIdGetter = null;
    }

    private static synchronized void ensureMethods(Object loc) {
        if (textureManager != null && getTexMethod != null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        Object tm = mc.getTextureManager();
        if (tm == null) return;

        textureManager = tm;

        // Minecraft 1.21.11: TextureManager#getTexture -> intermediary method_4619.
        Class<?> locClass = loc.getClass();
        for (Method m : tm.getClass().getMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isAssignableFrom(locClass)) continue;

            String name = m.getName();
            if (name.equals("method_4619") || name.equals("getTexture")) {
                getTexMethod = m;
                return;
            }
        }
    }

    private static synchronized Object resolveGpuTexture(Object texObj) throws Throwable {
        if (glTextureGetter != null) {
            return glTextureGetter.invoke(texObj);
        }

        // Minecraft 1.21.11 AbstractTexture#getGlTexture is method_68004.
        for (Method m : texObj.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType().getName().equals("com.mojang.blaze3d.textures.GpuTexture")
                    || m.getName().equals("method_68004")
                    || m.getName().equals("getGlTexture")) {
                MethodHandle mh = MethodHandles.privateLookupIn(m.getDeclaringClass(), MethodHandles.lookup()).unreflect(m);
                glTextureGetter = mh.asType(
                        mh.type().changeReturnType(Object.class));
                return glTextureGetter.invoke(texObj);
            }
        }

        // Defensive fallback for wrappers that don't expose the inherited getter publicly.
        Class<?> cls = texObj.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField("field_56974");
                f.setAccessible(true);
                return f.get(texObj);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static synchronized int readGpuTexId(Object gpuTex) throws Throwable {
        if (glTextureIdGetter != null) {
            return (int) glTextureIdGetter.invoke(gpuTex);
        }

        Class<?> cls = gpuTex.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                String n = f.getName();
                // Prefer the actual GL handle field when mappings expose it by name.
                if (!n.equals("id") && !n.equals("glId") && !n.equals("texture")
                        && !n.equals("textureId") && !n.equals("handle")
                        && !n.equals("field_64522")) {
                    continue;
                }
                f.setAccessible(true);
                int id = f.getInt(gpuTex);
                if (id > 0) {
                    glTextureIdGetter = MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup()).unreflectGetter(f);
                    return id;
                }
            }
            cls = cls.getSuperclass();
        }

        // Last-resort compatibility path: GlTexture has a single primary int handle
        // in the OpenGL backend. Do not select arbitrary non-positive fields.
        cls = gpuTex.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() != int.class) continue;
                f.setAccessible(true);
                int id = f.getInt(gpuTex);
                if (id > 0 && org.lwjgl.opengl.GL11.glIsTexture(id)) {
                    glTextureIdGetter = MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup()).unreflectGetter(f);
                    return id;
                }
            }
            cls = cls.getSuperclass();
        }

        return 0;
    }
}
