package me.balancinglight.rentities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;

public class RentitiesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("rentities.json");

    // Entity batching
    public boolean entity_batching_enabled = true;
    public boolean entity_scan_mode        = true;
    public boolean entity_batching_debug        = false;
    public boolean entity_batching_debug_solid  = false;

    // GPU entity rendering
    // GPU frustum culling is the original Rentities fast path; automatically falls back if unavailable.
    // The CPU-instanced renderer remains the correctness fallback.
    public boolean entity_gpu_culling_enabled = true;

    // GPU animation LOD
    public boolean fast_animation_lod_enabled = true;
    public float fast_animation_lod_medium_distance = 48.0f;
    public float fast_animation_lod_far_distance = 96.0f;
    public float fast_animation_lod_medium_scale = 0.35f;

    public static RentitiesConfig loadOrCreate() {
        if (CONFIG_PATH.toFile().exists()) {
            try (Reader r = new FileReader(CONFIG_PATH.toFile())) {
                RentitiesConfig cfg = GSON.fromJson(r, RentitiesConfig.class);
                if (cfg != null) return cfg;
            } catch (Exception e) {
                Rentities.LOGGER.warn("Failed to load rentities config, using defaults: {}", e.getMessage());
            }
        }
        RentitiesConfig cfg = new RentitiesConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try (Writer w = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            Rentities.LOGGER.warn("Failed to save rentities config: {}", e.getMessage());
        }
    }
}
