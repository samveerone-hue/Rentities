package me.balancinglight.rentities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

public class RentitiesConfig {
    public enum RendererBackendMode { AUTO, VANILLA, CPU, GPU, INDIRECT }

    public RendererBackendMode rendererBackend = RendererBackendMode.AUTO;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("rentities.json");

    public boolean entity_batching_enabled = true;
    public boolean entity_scan_mode = true;
    public boolean entity_batching_debug = false;
    public boolean gpu_frustum_culling_enabled = true;
    public boolean entity_batching_debug_solid = false;

    public boolean async_render_preparation_enabled = true;
    public boolean async_visibility_enabled = false;
    public int async_visibility_refresh_frames = 4;
    public int async_visibility_max_age_frames = 12;
    public double async_visibility_max_distance = 0.0;
    public CopyOnWriteArrayList<String> entity_batching_whitelist = new CopyOnWriteArrayList<>();
    public boolean entity_batching_whitelist_only = false;
    public CopyOnWriteArrayList<String> entity_batching_blacklist = new CopyOnWriteArrayList<>();

    public boolean fast_animation_lod_enabled = false;
    public float fast_animation_lod_medium_distance = 48.0f;
    public float fast_animation_lod_far_distance = 96.0f;
    public float fast_animation_lod_medium_scale = 0.35f;

    public static RentitiesConfig loadOrCreate() {
        if (Files.isRegularFile(CONFIG_PATH)) {
            try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                RentitiesConfig cfg = GSON.fromJson(r, RentitiesConfig.class);
                if (cfg != null) {
                    cfg.normalizeLists();
                    return cfg;
                }
            } catch (Exception e) {
                Rentities.LOGGER.warn("Failed to load rentities config, using defaults: {}", e.getMessage());
            }
        }
        RentitiesConfig cfg = new RentitiesConfig();
        cfg.save();
        return cfg;
    }

    private void normalizeLists() {
        if (entity_batching_whitelist == null) entity_batching_whitelist = new CopyOnWriteArrayList<>();
        else if (!(entity_batching_whitelist instanceof CopyOnWriteArrayList))
            entity_batching_whitelist = new CopyOnWriteArrayList<>(new ArrayList<>(entity_batching_whitelist));
        if (entity_batching_blacklist == null) entity_batching_blacklist = new CopyOnWriteArrayList<>();
        else if (!(entity_batching_blacklist instanceof CopyOnWriteArrayList))
            entity_batching_blacklist = new CopyOnWriteArrayList<>(new ArrayList<>(entity_batching_blacklist));
    }

    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Path temp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                GSON.toJson(this, w);
            }
            try {
                Files.move(temp, CONFIG_PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Rentities.LOGGER.warn("Failed to save rentities config: {}", e.getMessage());
        }
    }
}
