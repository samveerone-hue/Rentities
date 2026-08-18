package me.balancinglight.rentities.gl;

import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

/**
 * Tiny pass-local cache for the GL state Rentities changes repeatedly while drawing entities.
 * It is deliberately not a global cache: GlStateGuard restores foreign GL state after the pass,
 * so this cache is reset at the beginning of every guarded pass.
 */
public final class GlStateCache {
    private int program = Integer.MIN_VALUE;
    private int vao = Integer.MIN_VALUE;
    private int texture2dUnit0 = Integer.MIN_VALUE;

    /**
     * Invalidates all cached bindings. Call at the start of every pass whose GL state
     * is externally owned (Minecraft/Sodium/etc.) because GlStateGuard restores the
     * real state independently of this cache.
     */
    public void reset() {
        program = Integer.MIN_VALUE;
        vao = Integer.MIN_VALUE;
        texture2dUnit0 = Integer.MIN_VALUE;
    }

    public void useProgram(int id) {
        if (program == id) return;
        glUseProgram(id);
        program = id;
    }

    public void bindVertexArray(int id) {
        if (vao == id) return;
        glBindVertexArray(id);
        vao = id;
    }

    public void bindTextureUnit0(int id) {
        if (texture2dUnit0 == id) return;
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, id);
        texture2dUnit0 = id;
    }

    public int program() { return program; }
    public int vao() { return vao; }
    public int texture2dUnit0() { return texture2dUnit0; }
}
