package me.balancinglight.rentities.gl;

import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_RGB;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_RGB;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER_BINDING;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.GL_BLEND_EQUATION_ALPHA;
import static org.lwjgl.opengl.GL20C.GL_BLEND_EQUATION_RGB;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.glBlendEquationSeparate;
import static org.lwjgl.opengl.GL20C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glGetIntegeri;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL40C.glBindBufferBase;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

/**
 * Snapshots and restores every piece of global GL state that the entity pass touches.
 *
 * <p>Sodium, Nvidium and Blaze3D all keep a CPU-side mirror of the GL context and skip
 * redundant calls. Raw GL issued from this mod is invisible to those mirrors, so the only
 * safe contract is: whatever the context looked like when we took over, it looks exactly
 * the same when we hand it back. Restoring the captured values (rather than resetting to
 * "sane defaults") keeps every foreign state cache coherent without needing to know
 * anything about its internals.
 *
 * <p>Never touches the read framebuffer, depth range, viewport, or anything else the pass
 * does not modify — capturing state you do not change is wasted {@code glGet} sync points.
 */
public final class GlStateGuard {

    /** SSBO binding points owned by the entity pass. */
    private final int[] ssboBindings;
    private final int[] ssboPrev;

    private int prevProgram;
    private int prevVao;
    private int prevArrayBuffer;
    private int prevIndirectBuffer;
    private int prevDrawFramebuffer;

    private boolean prevDepthTest;
    private int prevDepthFunc;
    private boolean prevDepthMask;

    private boolean prevCullFace;
    private int prevCullFaceMode;

    private boolean prevBlend;
    private int prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha;
    private int prevBlendEqRgb, prevBlendEqAlpha;

    private boolean prevScissor;
    private boolean prevStencil;

    private boolean prevColorR, prevColorG, prevColorB, prevColorA;

    private int prevActiveTexture;
    private int prevTexture2dUnit0;

    private boolean captured;

    public GlStateGuard(int... ssboBindings) {
        this.ssboBindings = ssboBindings.clone();
        this.ssboPrev = new int[ssboBindings.length];
    }

    public void capture() {
        prevProgram         = glGetInteger(GL_CURRENT_PROGRAM);
        prevVao             = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        prevArrayBuffer     = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        prevIndirectBuffer  = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        prevDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
        prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);

        prevCullFace     = glIsEnabled(GL_CULL_FACE);
        prevCullFaceMode = glGetInteger(GL_CULL_FACE_MODE);

        prevBlend            = glIsEnabled(GL_BLEND);
        prevBlendSrcRgb      = glGetInteger(GL_BLEND_SRC_RGB);
        prevBlendDstRgb      = glGetInteger(GL_BLEND_DST_RGB);
        prevBlendSrcAlpha    = glGetInteger(GL_BLEND_SRC_ALPHA);
        prevBlendDstAlpha    = glGetInteger(GL_BLEND_DST_ALPHA);
        prevBlendEqRgb       = glGetInteger(GL_BLEND_EQUATION_RGB);
        prevBlendEqAlpha     = glGetInteger(GL_BLEND_EQUATION_ALPHA);

        prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        prevStencil = glIsEnabled(GL_STENCIL_TEST);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, mask);
            prevColorR = mask.get(0) != 0;
            prevColorG = mask.get(1) != 0;
            prevColorB = mask.get(2) != 0;
            prevColorA = mask.get(3) != 0;
        }

        // GL_TEXTURE_BINDING_2D is per active unit. The pass only ever binds to unit 0, so
        // that is the only unit to capture — but the query has to be made with unit 0 active,
        // otherwise a foreign binding from some other unit gets restored onto unit 0.
        prevActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        prevTexture2dUnit0 = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(prevActiveTexture);

        for (int i = 0; i < ssboBindings.length; i++) {
            ssboPrev[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, ssboBindings[i]);
        }

        captured = true;
    }

    public void restore() {
        if (!captured) return;
        captured = false;

        for (int i = 0; i < ssboBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, ssboBindings[i], ssboPrev[i]);
        }

        glBindVertexArray(prevVao);
        glBindBuffer(GL_ARRAY_BUFFER, prevArrayBuffer);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, prevIndirectBuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFramebuffer);
        glUseProgram(prevProgram);

        setEnabled(GL_DEPTH_TEST, prevDepthTest);
        glDepthFunc(prevDepthFunc);
        glDepthMask(prevDepthMask);

        setEnabled(GL_CULL_FACE, prevCullFace);
        glCullFace(prevCullFaceMode);

        setEnabled(GL_BLEND, prevBlend);
        glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
        glBlendEquationSeparate(prevBlendEqRgb, prevBlendEqAlpha);

        setEnabled(GL_SCISSOR_TEST, prevScissor);
        setEnabled(GL_STENCIL_TEST, prevStencil);

        glColorMask(prevColorR, prevColorG, prevColorB, prevColorA);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, prevTexture2dUnit0);
        glActiveTexture(prevActiveTexture);
    }

    /** Draw framebuffer that was bound when {@link #capture()} ran. */
    public int capturedDrawFramebuffer() {
        return prevDrawFramebuffer;
    }

    private static void setEnabled(int cap, boolean enabled) {
        if (enabled) glEnable(cap); else glDisable(cap);
    }
}
