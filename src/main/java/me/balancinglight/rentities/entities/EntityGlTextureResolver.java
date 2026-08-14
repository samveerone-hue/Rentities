package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Resolves OpenGL texture names from Minecraft {@code ResourceLocation} keys.
 * Method and field lookups are cached once; per-location GL ids are resolved once per session.
 */
final class EntityGlTextureResolver {

    private static Object textureManager = null;
    private static Method bindTexMethod = null;
    private static Method getTexMethod = null;
    private static MethodHandle gpuTexGetter = null;
    private static MethodHandle gpuTexIdGetter = null;

    private EntityGlTextureResolver() {}

    static int resolveGlId(Object loc) {
        if (loc == null) return 0;
        ensureMethods(loc);
        if (textureManager == null || bindTexMethod == null || getTexMethod == null) return 0;

        try {
            bindTexMethod.invoke(textureManager, loc);
            Object texObj = getTexMethod.invoke(textureManager, loc);
            if (texObj == null) return 0;

            Object gpuTex = resolveGpuTexture(texObj);
            if (gpuTex == null) return 0;

            int glId = readGpuTexId(gpuTex);
            if (glId > 0) return glId;
        } catch (Throwable t) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn("resolveGlId failed for {}: {}", loc, t.getMessage());
            }
        }

        return org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
    }

    private static void ensureMethods(Object loc) {
        if (textureManager != null && bindTexMethod != null && getTexMethod != null) return;

        var mc = Minecraft.getInstance();
        if (mc == null) return;
        var tm = mc.getTextureManager();
        if (tm == null) return;

        textureManager = tm;
        Class<?> locClass = loc.getClass();

        for (Method m : tm.getClass().getMethods()) {
            if (m.getParameterCount() != 1 || !m.getParameterTypes()[0].isAssignableFrom(locClass)) continue;
            String name = m.getName();
            if (bindTexMethod == null && (name.equals("method_4615") || name.equals("bindTexture"))) {
                bindTexMethod = m;
            }
            if (getTexMethod == null && (name.equals("method_4619") || name.equals("getTexture"))) {
                getTexMethod = m;
            }
        }
    }

    private static Object resolveGpuTexture(Object texObj) throws Throwable {
        if (gpuTexGetter != null) {
            return gpuTexGetter.invoke(texObj);
        }

        for (Method m : texObj.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals("GpuTexture")) {
                MethodHandle mh = MethodHandles.lookup().unreflect(m);
                gpuTexGetter = mh.asType(mh.type().changeReturnType(Object.class));
                return gpuTexGetter.invoke(texObj);
            }
        }
        return null;
    }

    private static int readGpuTexId(Object gpuTex) throws Throwable {
        if (gpuTexIdGetter != null) {
            return (int) gpuTexIdGetter.invoke(gpuTex);
        }

        for (Field f : gpuTex.getClass().getDeclaredFields()) {
            if (f.getType() != int.class) continue;
            f.setAccessible(true);
            int glId = f.getInt(gpuTex);
            if (glId > 0) {
                gpuTexIdGetter = MethodHandles.lookup().unreflectGetter(f);
                return glId;
            }
        }
        return 0;
    }
}
