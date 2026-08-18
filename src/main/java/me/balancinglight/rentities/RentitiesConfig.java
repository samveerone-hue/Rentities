package me.balancinglight.rentities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;

public class RentitiesConfig {
    public enum RendererBackendMode {
        AUTO,
        VANILLA,
        CPU,
        GPU,
        INDIRECT
    }

    public RendererBackendMode rendererBackend = RendererBackendMode.AUTO;


    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("rentities.json");

    // Entity batching
    public boolean entity_batching_enabled = true;
    public boolean entity_scan_mode        = true;
    public boolean entity_batching_debug        = false;
    public boolean gpu_frustum_culling_enabled = true;
    public boolean entity_batching_debug_solid  = false;

    // Async preparation / conservative visibility
    public boolean async_render_preparation_enabled = true;
    public boolean async_visibility_enabled = false;
    public int async_visibility_refresh_frames = 4;
    public int async_visibility_max_age_frames = 12;
    public double async_visibility_max_distance = 0.0;
    public java.util.List<String> entity_batching_whitelist = new java.util.ArrayList<>();
    public boolean entity_batching_whitelist_only = false;
    public java.util.List<String> entity_batching_blacklist = new java.util.ArrayList<>();

    // GPU animation / distance LOD
    public boolean fast_animation_lod_enabled = false;
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
