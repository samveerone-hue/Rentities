package me.balancinglight.rentities.gl;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL44C.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL44C.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL45C.glCreateBuffers;
import static org.lwjgl.opengl.GL45C.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45C.glUnmapNamedBuffer;
import static org.lwjgl.opengl.GL45C.nglMapNamedBufferRange;
import static org.lwjgl.opengl.GL11C.glGetInteger;

/**
 * One immutable buffer object carved into N equally sized, alignment-padded slots.
 *
 * <p>Using a single allocation instead of N buffer objects means the ring advance is an
 * offset change ({@code glBindBufferRange}) rather than a buffer-object rebind, which on
 * the NVIDIA driver avoids re-validating the descriptor for the binding point.
 *
 * <p>Host-visible slots are mapped once with {@code GL_MAP_PERSISTENT_BIT |
 * GL_MAP_COHERENT_BIT} and never unmapped until deletion, so there is no map/unmap
 * round-trip (each of which is an implicit sync point) in the frame loop. Coherent
 * mapping on NVIDIA gives write-combined, uncached host memory: writes must be
 * sequential and never read back, which is exactly the access pattern of the instance
 * staging copy.
 *
 * <p>Device-local slots (no host mapping) are used for buffers only the GPU writes, such
 * as the compacted visible-instance index list.
 */
public final class GpuRingBuffer {

    private final int id;
    private final int slots;
    private final long slotSize;
    private final long stride;
    private final long baseAddr;

    public GpuRingBuffer(long bytesPerSlot, int slots, boolean hostVisible) {
        this.slots = slots;
        this.slotSize = bytesPerSlot;
        int align = Math.max(glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT), 256);
        // SSBO alignment applies to each glBindBufferRange offset. EntityInstance.STRIDE
        // is the logical struct stride inside the bound slot; the ring stride is rounded up
        // independently to satisfy the hardware alignment requirement.
        this.stride = ((bytesPerSlot + align - 1) / align) * align;

        this.id = glCreateBuffers();
        long total = stride * slots;
        if (hostVisible) {
            int flags = GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | GL_MAP_WRITE_BIT;
            glNamedBufferStorage(id, total, flags);
            this.baseAddr = nglMapNamedBufferRange(id, 0, total, flags);
        } else {
            glNamedBufferStorage(id, total, 0);
            this.baseAddr = 0L;
        }
    }

    public int id() { return id; }

    public int slots() { return slots; }

    /** Usable bytes per slot (the requested size, not the padded stride). */
    public long slotSize() { return slotSize; }

    public long offsetOf(int slot) { return stride * slot; }

    /** Address of the mapped slot, or 0 for a device-local buffer. */
    public long addrOf(int slot) { return baseAddr == 0 ? 0 : baseAddr + stride * slot; }

    public void delete() {
        if (id == 0) return;
        if (baseAddr != 0) glUnmapNamedBuffer(id);
        glDeleteBuffers(id);
    }
}
