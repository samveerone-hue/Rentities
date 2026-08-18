package me.balancinglight.rentities.gl;

/** Upload backend for per-frame entity instance SSBO data. */
public interface InstanceBufferBackend {
    int id();
    long offsetOf(int slot);
    long slotSize();
    long addrOf(int slot);
    boolean persistent();
    void upload(int slot, long bytes);
    void delete();
}
