package me.balancinglight.rentities.entities;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;

/** Owns the shared GPU mesh resources and makes teardown explicit/idempotent. */
public final class EntityMeshGpuHandle {
    private int vao;
    private int vbo;
    private int ebo;

    public EntityMeshGpuHandle() {}
    public EntityMeshGpuHandle(int vao, int vbo, int ebo) { set(vao, vbo, ebo); }
    public void set(int vao, int vbo, int ebo) {
        delete();
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
    }
    public int vao() { return vao; }
    public int vbo() { return vbo; }
    public int ebo() { return ebo; }
    public boolean valid() { return vao != 0 && vbo != 0 && ebo != 0; }
    public void delete() {
        if (vao != 0) { glDeleteVertexArrays(vao); vao = 0; }
        if (vbo != 0) { glDeleteBuffers(vbo); vbo = 0; }
        if (ebo != 0) { glDeleteBuffers(ebo); ebo = 0; }
    }
}
