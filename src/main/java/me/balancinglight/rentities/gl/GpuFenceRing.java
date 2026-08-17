package me.balancinglight.rentities.gl;

import me.balancinglight.rentities.Rentities;

import static org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL32C.glClientWaitSync;
import static org.lwjgl.opengl.GL32C.glDeleteSync;
import static org.lwjgl.opengl.GL32C.glFenceSync;
import static org.lwjgl.opengl.GL11C.glFinish;

/**
 * Per-slot GPU fences for a {@link GpuRingBuffer}.
 *
 * <p>A persistently mapped buffer gets no driver-side renaming: writing a slot the GPU may
 * still be reading is a data race that shows up as one-frame-old or torn instance data,
 * not as a GL error. The ring therefore has exactly one rule — before writing slot i, wait
 * until the fence inserted after the last draw that read slot i has signalled.
 *
 * <p>The wait is a real blocking wait, not a poll-and-continue. With three slots the CPU
 * is at most two frames ahead of the GPU, so the wait only ever fires when the GPU is
 * genuinely more than two frames behind — at which point stalling is correct, because
 * running further ahead only grows latency. {@code GL_SYNC_FLUSH_COMMANDS_BIT} is passed
 * on the first attempt so the fence cannot deadlock behind an unflushed command buffer.
 */
public final class GpuFenceRing {

    private static final long WAIT_CHUNK_NS = 1_000_000_000L; // 1s per wait call

    private final long[] fences;

    public GpuFenceRing(int slots) {
        this.fences = new long[slots];
    }

    /** Blocks until the GPU is done with everything that read {@code slot}. */
    public void waitFor(int slot) {
        long fence = fences[slot];
        if (fence == 0L) return;

        int flags = GL_SYNC_FLUSH_COMMANDS_BIT;
        int spins = 0;
        while (true) {
            int result = glClientWaitSync(fence, flags, WAIT_CHUNK_NS);
            if (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED) break;
            if (result == GL_WAIT_FAILED) {
                Rentities.LOGGER.error(
                        "[Entity] glClientWaitSync failed on ring slot {}; forcing GPU completion",
                        slot);
                glFinish();
                break;
            }
            // GL_TIMEOUT_EXPIRED — the flush bit must not be repeated (spec: it is only
            // honoured on the first call for a given sync object).
            flags = 0;
            if (++spins == 1) {
                Rentities.LOGGER.warn("[Entity] GPU more than {} frames behind on ring slot {}",
                        fences.length, slot);
            }
        }
        glDeleteSync(fence);
        fences[slot] = 0L;
    }

    /** Records that all commands issued so far must complete before {@code slot} is reused. */
    public void signal(int slot) {
        if (fences[slot] != 0L) glDeleteSync(fences[slot]);
        fences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public void deleteAll() {
        for (int i = 0; i < fences.length; i++) {
            if (fences[i] != 0L) {
                glDeleteSync(fences[i]);
                fences[i] = 0L;
            }
        }
    }
}
