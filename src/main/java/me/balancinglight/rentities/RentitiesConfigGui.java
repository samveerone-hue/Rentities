package me.balancinglight.rentities;

import me.balancinglight.rentities.entities.EntityBatchRenderer;
import me.balancinglight.rentities.entities.EntityMeshBaker;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;


public class RentitiesConfigGui implements ConfigEntryPoint {

    private static final RentitiesConfigStore store = new RentitiesConfigStore();
    private final StorageEventHandler saveConfig = store::save;
    private final StorageEventHandler noSave = () -> {};

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        Rentities.LOGGER.info("[Rentities] Sodium config registration started");
        var page = builder.createOptionPage()
                .setName(Component.literal("Rentities"));

        // Master switch — immediately returns all entity rendering to vanilla when disabled.
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:enabled"))
                .setName(Component.literal("Enable Rentities"))
                .setTooltip(Component.literal(
                    "Master switch. OFF disables Rentities interception and restores vanilla entity rendering."))
                .setDefaultValue(true)
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(
                    value -> {
                        store.getData().rentities_enabled = value;
                        store.save();
                        if (!value) {
                            Rentities.IS_ENABLED = false;
                            if (EntityBatchRenderer.INSTANCE != null) {
                                EntityBatchRenderer.INSTANCE.delete();
                            }
                        } else {
                            Rentities.checkAndEnable();
                            if (Rentities.IS_ENABLED && EntityBatchRenderer.INSTANCE == null) {
                                new EntityBatchRenderer();
                            }
                        }
                    },
                    () -> store.getData().rentities_enabled
                )
                .setStorageHandler(noSave)
        );

        // Master toggle — takes effect immediately, no reload needed
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:entity_batching"))
                .setName(Component.literal("GPU Entity Batching"))
                .setTooltip(Component.literal(
                    "Renders entities using GPU instancing. " +
                    "Batches thousands of entities into a single draw call. " +
                    "Requires an NVIDIA GPU."))
                .setDefaultValue(true)
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(
                    value -> {
                        store.getData().entity_batching_enabled = value;
                        store.save();
                        Rentities.checkAndEnable();
                        if (!value) {
                            // Disable: delete renderer so entities fall back to vanilla immediately
                            if (EntityBatchRenderer.INSTANCE != null) {
                                EntityBatchRenderer.INSTANCE.delete();
                            }
                        } else {
                            // Enable: create renderer if not already running
                            if (EntityBatchRenderer.INSTANCE == null) {
                                new EntityBatchRenderer();
                            }
                        }
                    },
                    () -> store.getData().entity_batching_enabled
                )
                .setStorageHandler(noSave) // already saved manually above
        );

        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:async_preparation"))
                .setName(Component.literal("Async Render Preparation"))
                .setTooltip(Component.literal("Prepares immutable entity-type metadata off-thread. Unknown results always fall back safely."))
                .setDefaultValue(true)
                .setImpact(OptionImpact.LOW)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(value -> { store.getData().async_render_preparation_enabled = value; store.save(); },
                            () -> store.getData().async_render_preparation_enabled)
                .setStorageHandler(noSave)
        );

        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:async_visibility"))
                .setName(Component.literal("Async Conservative Visibility"))
                .setTooltip(Component.literal("Optional background distance visibility hint. Unknown or stale results are always kept visible."))
                .setDefaultValue(false)
                .setImpact(OptionImpact.MEDIUM)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(value -> { store.getData().async_visibility_enabled = value; store.save(); },
                            () -> store.getData().async_visibility_enabled)
                .setStorageHandler(noSave)
        );

        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:async_whitelist_only"))
                .setName(Component.literal("Batch Whitelist Only"))
                .setTooltip(Component.literal("When enabled, only entity IDs listed in the config whitelist may use GPU batching; others use vanilla."))
                .setDefaultValue(false)
                .setImpact(OptionImpact.MEDIUM)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(value -> {
                    store.getData().entity_batching_whitelist_only = value; store.save();
                }, () -> store.getData().entity_batching_whitelist_only)
                .setStorageHandler(noSave)
        );

        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:gpu_culling"))
                .setName(Component.literal("GPU Frustum Culling"))
                .setTooltip(Component.literal(
                    "Uses the compute shader to reject off-screen entities before indirect draws. " +
                    "Disables independently and falls back to CPU instancing if it fails."))
                .setDefaultValue(true)
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(
                    value -> { store.getData().gpu_frustum_culling_enabled = value; store.save(); },
                    () -> store.getData().gpu_frustum_culling_enabled
                )
                .setStorageHandler(noSave)
        );

        // Scan mode — needs reload to take effect (changes what happens at world load)
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:scan_mode"))
                .setName(Component.literal("Entity Scan Mode"))
                .setTooltip(Component.literal(
                    "ON: scans entity meshes and saves to disk. " +
                    "OFF: loads from saved cache. " +
                    "Rejoin your world after changing this."))
                .setDefaultValue(true)
                .setImpact(OptionImpact.VARIES)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(
                    value -> {
                        store.getData().entity_scan_mode = value;
                        store.save();
                    },
                    () -> store.getData().entity_scan_mode
                )
                .setStorageHandler(noSave)
        );

        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:animation_lod"))
                .setName(Component.literal("Fast Animation LOD"))
                .setTooltip(Component.literal(
                    "Reduces animation work at distance while keeping GPU entity batching."))
                .setDefaultValue(false)
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(c -> Rentities.IS_COMPATIBLE)
                .setBinding(
                    value -> {
                        store.getData().fast_animation_lod_enabled = value;
                        store.save();
                    },
                    () -> store.getData().fast_animation_lod_enabled
                )
                .setStorageHandler(noSave)
        );

        // Cache status — read only
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:cache_status"))
                .setName(Component.literal("Mesh Cache: " +
                    (EntityMeshBaker.cacheExists() ? "§aSaved" : "§cNot saved")))
                .setTooltip(Component.literal(
                    "Shows whether a mesh cache file exists on disk."))
                .setDefaultValue(false)
                .setImpact(OptionImpact.VARIES)
                .setEnabledProvider(c -> false)
                .setBinding(v -> {}, () -> EntityMeshBaker.cacheExists())
                .setStorageHandler(noSave)
        );

        // Delete cache
        page.addOption(
            builder.createBooleanOption(Identifier.parse("rentities:delete_cache"))
                .setName(Component.literal("Delete Mesh Cache"))
                .setTooltip(Component.literal(
                    "Deletes the saved mesh cache. " +
                    "Re-enable Scan Mode to rebuild it."))
                .setDefaultValue(false)
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(c -> EntityMeshBaker.cacheExists())
                .setBinding(
                    value -> { if (value) { EntityMeshBaker.deleteCache(); } },
                    () -> false
                )
                .setStorageHandler(noSave)
        );

        builder.registerModOptions("rentities")
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(0x76B900))
                .addPage(page);
        Rentities.LOGGER.info("[Rentities] Sodium config registration complete");
    }
}
