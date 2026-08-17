package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import me.balancinglight.rentities.gl.GlShader;
import me.balancinglight.rentities.gl.GpuRingBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL20C.glUniform4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42C.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;
import static org.lwjgl.opengl.GL45C.glBindBufferRange;

/**
 * GPU frustum culling and indirect draw-command generation for the entity batch.
 *
 * <p>The CPU never learns how many entities survive culling. It writes one draw group and
 * one zeroed indirect command per entity type, dispatches the cull, and issues
 * {@code glMultiDrawElementsIndirect} against the command buffer the compute shader just
 * filled in. Culled instances stay in the instance SSBO — they are simply never referenced
 * by the compacted index list — so culling costs no upload bandwidth and no readback.
 *
 * <p>Buffer layout per frame slot:
 * <pre>
 *   binding 12  instance data      host-written, ring-buffered  (owned by EntityBatchRenderer)
 *   binding 14  draw groups        host-written, ring-buffered
 *   binding 15  indirect commands  host-written template, GPU-incremented instanceCount
 *   binding 16  visible indices    device-local, GPU-written, read by the vertex shader
 * </pre>
 */
public final class EntityCullingPipeline {

    public static final int GROUP_SSBO_BINDING   = 14;
    public static final int CMD_SSBO_BINDING     = 15;
    public static final int VISIBLE_SSBO_BINDING = 16;

    /** Bytes per DrawElementsIndirectCommand — five uints, and the MDI stride. */
    public static final int CMD_STRIDE = 20;
    private static final int GROUP_STRIDE = 16;
    private static final int MAX_GROUPS = 256;
    private static final int LOCAL_SIZE = 128;

    /**
     * Entity models reach outside the collision box (raised arms, wings, mounted riders),
     * and the bounding sphere is also the animation slack budget, so it is scaled up rather
     * than fitted tightly. Popping is far more expensive to debug than a few extra draws.
     */
    private static final float BOUNDS_SLACK = 1.35f;

    private final GpuRingBuffer groupBuffer;
    private final GpuRingBuffer cmdBuffer;
    private final GpuRingBuffer visibleBuffer;

    private GlShader cullShader;
    private int uFrustumPlanes = -1;
    private int uGroupCount = -1;
    private int uInstanceCount = -1;

    private final FloatBuffer planeBuffer = MemoryUtil.memAllocFloat(6 * 4);
    private final Vector4f plane = new Vector4f();

    private int slot;
    private int groupCount;
    private long groupAddr;
    private long cmdAddr;

    public EntityCullingPipeline(int slots, int maxInstances) {
        this.groupBuffer   = new GpuRingBuffer((long) MAX_GROUPS * GROUP_STRIDE, slots, true);
        this.cmdBuffer     = new GpuRingBuffer((long) MAX_GROUPS * CMD_STRIDE, slots, true);
        this.visibleBuffer = new GpuRingBuffer((long) maxInstances * 4, slots, false);
        compile();
    }

    private void compile() {
        try {
            cullShader = GlShader.builder()
                    .comp(GlShader.loadResource("entity/entity_cull.comp"))
                    .compile();
            uFrustumPlanes = cullShader.getUniformLocation("uFrustumPlanes");
            uGroupCount    = cullShader.getUniformLocation("uGroupCount");
            uInstanceCount = cullShader.getUniformLocation("uInstanceCount");
        } catch (Exception e) {
            Rentities.LOGGER.error("[Entity] Cull compute shader failed to build — "
                    + "falling back to CPU-issued instanced draws", e);
            cullShader = null;
        }
    }

    public boolean isAvailable() {
        return cullShader != null;
    }

    /** Starts a new frame on {@code slot}; the caller must already have waited on its fence. */
    public void begin(int slot) {
        this.slot = slot;
        this.groupCount = 0;
        this.groupAddr = groupBuffer.addrOf(slot);
        this.cmdAddr = cmdBuffer.addrOf(slot);
    }

    /**
     * Appends a draw group and its zeroed indirect command.
     *
     * @param indexCount    indices in the type's mesh
     * @param indexOffset   byte offset of the mesh's first index in the shared element buffer
     * @param firstInstance index of the group's first instance in the instance SSBO
     * @param instanceCount queued instances of this type, before culling
     * @param type          entity type, used for the bounding sphere
     * @return the command index, or -1 if the group table is full
     */
    public int addGroup(int indexCount, int indexOffset, int firstInstance, int instanceCount,
                        net.minecraft.world.entity.EntityType<?> type) {
        if (groupCount >= MAX_GROUPS) return -1;
        int g = groupCount++;

        float halfWidth = type != null ? type.getWidth() * 0.5f : 0.5f;
        float height    = type != null ? type.getHeight() : 2.0f;
        float centreY   = height * 0.5f;
        float radius    = (float) Math.sqrt(2.0 * halfWidth * halfWidth + centreY * centreY)
                          * BOUNDS_SLACK;

        long gp = groupAddr + (long) g * GROUP_STRIDE;
        MemoryUtil.memPutInt(gp,      firstInstance);
        MemoryUtil.memPutInt(gp + 4,  instanceCount);
        MemoryUtil.memPutFloat(gp + 8,  radius);
        MemoryUtil.memPutFloat(gp + 12, centreY);

        long cp = cmdAddr + (long) g * CMD_STRIDE;
        MemoryUtil.memPutInt(cp,      indexCount);
        MemoryUtil.memPutInt(cp + 4,  0);                 // instanceCount — filled by the cull
        MemoryUtil.memPutInt(cp + 8,  indexOffset / 4);   // firstIndex is in elements, not bytes
        MemoryUtil.memPutInt(cp + 12, 0);                 // baseVertex: indices are absolute
        MemoryUtil.memPutInt(cp + 16, firstInstance);     // gl_BaseInstance → visible-index base
        return g;
    }

    public int groupCount() {
        return groupCount;
    }

    /**
     * Runs the cull for {@code instanceCount} queued instances.
     *
     * @param viewProjection camera-relative view-projection matrix, matching instance space
     */
    public void dispatch(int instanceCount, Matrix4f viewProjection) {
        if (cullShader == null || groupCount == 0 || instanceCount == 0) return;

        glUseProgram(cullShader.id);

        for (int i = 0; i < 6; i++) {
            // JOML returns normalised planes whose normals point into the frustum, so a point
            // inside has a positive signed distance — which is what the shader's test assumes.
            viewProjection.frustumPlane(i, plane);
            planeBuffer.put(i * 4,     plane.x);
            planeBuffer.put(i * 4 + 1, plane.y);
            planeBuffer.put(i * 4 + 2, plane.z);
            planeBuffer.put(i * 4 + 3, plane.w);
        }
        glUniform4fv(uFrustumPlanes, planeBuffer);
        glUniform1ui(uGroupCount, groupCount);
        glUniform1ui(uInstanceCount, instanceCount);

        bindSsbo(GROUP_SSBO_BINDING, groupBuffer);
        bindSsbo(CMD_SSBO_BINDING, cmdBuffer);
        bindSsbo(VISIBLE_SSBO_BINDING, visibleBuffer);

        glDispatchCompute((instanceCount + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);

        // The command buffer is consumed by the draw as indirect state and the index list as
        // an SSBO read, so both hazards have to be named here.
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /** Binds the command buffer for indirect draws; the visible index SSBO stays bound. */
    public void bindForDraw() {
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, cmdBuffer.id());
    }

    /** Byte offset of command {@code index} within the bound indirect buffer. */
    public long indirectOffset(int index) {
        return cmdBuffer.offsetOf(slot) + (long) index * CMD_STRIDE;
    }

    private void bindSsbo(int binding, GpuRingBuffer buffer) {
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, binding, buffer.id(),
                buffer.offsetOf(slot), buffer.slotSize());
    }

    public void delete() {
        if (cullShader != null) cullShader.delete();
        groupBuffer.delete();
        cmdBuffer.delete();
        visibleBuffer.delete();
        MemoryUtil.memFree(planeBuffer);
    }
}
