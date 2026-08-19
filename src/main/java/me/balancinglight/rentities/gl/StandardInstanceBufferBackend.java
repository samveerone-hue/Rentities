package me.balancinglight.rentities.gl;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.GL44C.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.glGetInteger;

/**
 * Portable SSBO backend. CPU writes into a direct ring staging area and uploads only the
 * used slot before the draw. It is slower than persistent mapping but provides a real
 * GPU-batching fallback without changing the instance format.
 */
public final class StandardInstanceBufferBackend implements InstanceBufferBackend {
    private final int id;
    private final int slots;
    private final long slotSize;
    private final long stride;
    private final long stagingAddr;

    public StandardInstanceBufferBackend(long bytesPerSlot, int slots) {
        if (bytesPerSlot <= 0 || slots <= 0) {
            throw new IllegalArgumentException("bytesPerSlot and slots must be positive");
        }
        this.slots = slots;
        this.slotSize = bytesPerSlot;
        int queriedAlign = glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        int align = Math.max(queriedAlign > 0 ? queriedAlign : 256, 256);
        this.stride = ((bytesPerSlot + align - 1) / align) * align;
        long total = Math.multiplyExact(stride, (long) slots);

        int createdId = 0;
        long allocated = 0L;
        try {
            createdId = glCreateBuffers();
            glNamedBufferStorage(createdId, total, GL_DYNAMIC_STORAGE_BIT);
            allocated = MemoryUtil.nmemAlloc(total);
            MemoryUtil.memSet(allocated, 0, total);
        } catch (Throwable t) {
            if (allocated != 0L) MemoryUtil.nmemFree(allocated);
            if (createdId != 0) glDeleteBuffers(createdId);
            throw t;
        }
        this.id = createdId;
        this.stagingAddr = allocated;
    }

    public int id() { return id; }
    public long offsetOf(int slot) { return stride * slot; }
    public long slotSize() { return slotSize; }
    public long addrOf(int slot) { return stagingAddr + stride * slot; }
    public boolean persistent() { return false; }

    /** Uploads the exact active instance range; renderer synchronizes shader visibility. */
    public void upload(int slot, long bytes) {
        if (bytes <= 0) return;
        if (slot < 0 || slot >= slots) {
            throw new IndexOutOfBoundsException("slot=" + slot + ", slots=" + slots);
        }
        if (bytes > slotSize) {
            throw new IllegalArgumentException("upload exceeds slot size: " + bytes + " > " + slotSize);
        }
        glNamedBufferSubData(id, offsetOf(slot), MemoryUtil.memByteBuffer(addrOf(slot), Math.toIntExact(bytes)));
    }

    public void delete() {
        if (stagingAddr != 0) MemoryUtil.nmemFree(stagingAddr);
        glDeleteBuffers(id);
    }
}
