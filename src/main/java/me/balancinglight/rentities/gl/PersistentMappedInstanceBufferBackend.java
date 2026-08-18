package me.balancinglight.rentities.gl;

/** OpenGL 4.4 persistent/coherent mapped SSBO backend backed by the existing ring buffer. */
public final class PersistentMappedInstanceBufferBackend implements InstanceBufferBackend {
    private final GpuRingBuffer ring;
    public PersistentMappedInstanceBufferBackend(long bytesPerSlot, int slots) {
        this.ring = new GpuRingBuffer(bytesPerSlot, slots, true);
    }
    public int id() { return ring.id(); }
    public long offsetOf(int slot) { return ring.offsetOf(slot); }
    public long slotSize() { return ring.slotSize(); }
    public long addrOf(int slot) { return ring.addrOf(slot); }
    public boolean persistent() { return true; }
    public void upload(int slot, long bytes) { /* coherent mapped memory is already visible */ }
    public void delete() { ring.delete(); }
}
