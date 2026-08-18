package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative asynchronous visibility prefilter.
 *
 * <p>This worker only evaluates immutable coordinates and an optional distance limit. It does
 * not access Minecraft world/chunk state and therefore cannot perform unsafe background raycasts.
 * The GPU frustum culler remains authoritative for screen visibility. Unknown/stale results fail
 * open, while explicit blacklist entries only exclude an entity from batching (never rendering).
 */
public final class AsyncVisibilityManager {
    private final ExecutorService executor;
    private final Map<Long, Result> results = new ConcurrentHashMap<>();
    private long frame;

    public AsyncVisibilityManager() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Rentities-Visibility");
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return t;
        });
    }

    public void beginFrame(long frame) {
        this.frame = frame;
        if (results.size() > 8192) {
            results.entrySet().removeIf(e -> frame - e.getValue().frame() > 120);
        }
    }

    /** Returns false only for a fresh, explicit distance rejection. Unknown results are true. */
    public boolean shouldBatch(Entity entity, double cameraX, double cameraY, double cameraZ,
                               boolean enabled, int refreshFrames, int maxAgeFrames, double maxDistance) {
        if (!enabled || entity == null) return true;
        long id = entity.getId();
        Result r = results.get(id);
        if (r != null && frame - r.frame() <= maxAgeFrames) return r.visible();

        if (executor.isShutdown()) return true;
        long due = r == null ? Long.MIN_VALUE : r.frame();
        if (r != null && frame - due < Math.max(1, refreshFrames)) return true;

        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        long targetFrame = frame;
        CompletableFuture.runAsync(() -> {
            boolean visible = true;
            if (maxDistance > 0.0) {
                double dx = ex - cameraX;
                double dy = ey - cameraY;
                double dz = ez - cameraZ;
                visible = dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance;
            }
            results.put(id, new Result(visible, targetFrame));
        }, executor).exceptionally(t -> {
            Rentities.LOGGER.debug("[Rentities] async visibility failed for {}: {}", id, t.toString());
            return null;
        });
        return true; // fail-open until a fresh result exists
    }

    public void shutdown() {
        executor.shutdownNow();
        results.clear();
    }

    private record Result(boolean visible, long frame) {}
}
