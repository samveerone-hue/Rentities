package me.balancinglight.rentities.gl;

import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.GL44C.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;

/** Centralized GPU producer/consumer synchronization for Rentities. */
public final class GpuSync {
    private GpuSync() {}

    public static void afterComputeBeforeIndirectDraw() {
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
    }

    public static void afterCpuUploadBeforeShaderRead() {
        glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT
                | GL_BUFFER_UPDATE_BARRIER_BIT
                | GL_SHADER_STORAGE_BARRIER_BIT
                | GL_COMMAND_BARRIER_BIT);
    }

    public static void afterComputeBeforeVertexRead() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
    }
}
