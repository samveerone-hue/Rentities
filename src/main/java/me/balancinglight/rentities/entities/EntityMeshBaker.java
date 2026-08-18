package me.balancinglight.rentities.entities;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL15C.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL45C.*;

// Extracts entity meshes from vanilla renderers and bakes them into a shared GPU VBO.
public class EntityMeshBaker {

    public static final int VERTEX_STRIDE = 36; // 9 floats × 4 bytes
    public static final int MAX_BONES = 10;
    private static final int INITIAL_ENTITY_TYPE_CAPACITY = 64;

    // Pivot data: [typeIdx * MAX_BONES + boneIdx] * 4 floats (x,y,z,0).
    // Capacity grows with the registry instead of imposing a fixed 256-type limit.
    private float[] bonePivotData =
            new float[INITIAL_ENTITY_TYPE_CAPACITY * MAX_BONES * 4];
    private boolean[] bonePivotWritten =
            new boolean[INITIAL_ENTITY_TYPE_CAPACITY * MAX_BONES];
    private int pivotSSBOId = 0;
    private int currentBakingTypeIdx = -1;

    private final EntityMeshGpuHandle gpuMesh = new EntityMeshGpuHandle();

    public static class MeshInfo {
        public final int vertexOffset; // byte offset in VBO
        public final int indexOffset;  // byte offset in EBO
        public final int indexCount;

        public MeshInfo(int vertexOffset, int indexOffset, int indexCount) {
            this.vertexOffset = vertexOffset;
            this.indexOffset = indexOffset;
            this.indexCount = indexCount;
        }
    }

    private final Map<EntityType<?>, MeshInfo> meshInfoMap = new HashMap<>();
    private final Map<EntityType<?>, CpuMesh> cpuMeshes = new LinkedHashMap<>();
    private final Map<EntityType<?>, MeshStatus> meshStatus = new HashMap<>();
    private final Map<EntityType<?>, Integer> failureCounts = new HashMap<>();
    private final Map<EntityType<?>, Long> retryAfterNanos = new HashMap<>();

    private enum BakeState { NOT_STARTED, BAKING, READY, FAILED }
    private volatile BakeState bakeState = BakeState.NOT_STARTED;

    public enum MeshStatus { UNKNOWN, BUILDING, READY }

    private record CpuMesh(float[] vertices, int[] indices) {}
    private record ExtractedMesh(float[] vertices, int[] indices, float[] pivots) {}

    private final ExecutorService cacheExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rentities-cache-save");
        t.setDaemon(true);
        return t;
    });

    // Bone name → bone index maps per category
    // These match vanilla ModelPart child names exactly
    private static final Map<String, Integer> BIPED_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> QUADRUPED_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> HORSE_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> BIRD_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> ARTHROPOD_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> INSECT_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> WORM_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> FISH_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> SLIME_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> GHAST_BONES = new LinkedHashMap<>();
    private static final Map<String, Integer> CREEPER_BONES = new LinkedHashMap<>();

    static {
        // Vanilla ModelPart child names from decompiled sources
        BIPED_BONES.put("head",       0);
        BIPED_BONES.put("hat",        0); // same bone as head
        BIPED_BONES.put("body",       1);
        BIPED_BONES.put("left_arm",   2);
        BIPED_BONES.put("right_arm",  3);
        BIPED_BONES.put("left_leg",   4);
        BIPED_BONES.put("right_leg",  5);
        // Iron golem extras — still use closest bone
        BIPED_BONES.put("nose",       0);
        BIPED_BONES.put("left_ear",   0);
        BIPED_BONES.put("beard",      1);

        QUADRUPED_BONES.put("head",       0);
        QUADRUPED_BONES.put("body",       1);
        QUADRUPED_BONES.put("leg1",       2); // front left
        QUADRUPED_BONES.put("leg2",       3); // front right
        QUADRUPED_BONES.put("leg3",       4); // back left
        QUADRUPED_BONES.put("leg4",       5); // back right
        // 1.21.11 names
        QUADRUPED_BONES.put("left_front_leg",  2);
        QUADRUPED_BONES.put("right_front_leg", 3);
        QUADRUPED_BONES.put("left_hind_leg",   4);
        QUADRUPED_BONES.put("right_hind_leg",  5);
        // More variants
        QUADRUPED_BONES.put("left_front_leg_tip",  2);
        QUADRUPED_BONES.put("right_front_leg_tip", 3);
        QUADRUPED_BONES.put("left_hind_leg_tip",   4);
        QUADRUPED_BONES.put("right_hind_leg_tip",  5);
        QUADRUPED_BONES.put("tail",       1); // tail = body bone
        QUADRUPED_BONES.put("mane",       0);
        QUADRUPED_BONES.put("upper_body", 1);

        HORSE_BONES.put("head",        0);
        HORSE_BONES.put("body",        1);
        HORSE_BONES.put("front_left_leg",  2);
        HORSE_BONES.put("front_right_leg", 3);
        HORSE_BONES.put("back_left_leg",   4);
        HORSE_BONES.put("back_right_leg",  5);
        HORSE_BONES.put("tail",        6);
        HORSE_BONES.put("neck",        0);
        HORSE_BONES.put("mane",        0);
        HORSE_BONES.put("left_ear",    0);
        HORSE_BONES.put("right_ear",   0);

        BIRD_BONES.put("head",       0);
        BIRD_BONES.put("body",       1);
        BIRD_BONES.put("left_wing",  2);
        BIRD_BONES.put("right_wing", 3);
        BIRD_BONES.put("left_leg",   4);
        BIRD_BONES.put("right_leg",  5);
        BIRD_BONES.put("beak",       0);
        BIRD_BONES.put("left_foot",  4);
        BIRD_BONES.put("right_foot", 5);

        ARTHROPOD_BONES.put("head",        0);
        ARTHROPOD_BONES.put("body",        1);
        ARTHROPOD_BONES.put("right_middle_front_leg", 2);
        ARTHROPOD_BONES.put("left_middle_front_leg",  3);
        ARTHROPOD_BONES.put("right_middle_leg",  4);
        ARTHROPOD_BONES.put("left_middle_leg",   5);
        ARTHROPOD_BONES.put("right_back_leg",    6);
        ARTHROPOD_BONES.put("left_back_leg",     7);

        INSECT_BONES.put("body",          0);
        INSECT_BONES.put("torso",         0);
        INSECT_BONES.put("right_wing",    1);
        INSECT_BONES.put("left_wing",     2);
        INSECT_BONES.put("front_legs",    3);
        INSECT_BONES.put("middle_legs",   3);
        INSECT_BONES.put("back_legs",     3);
        INSECT_BONES.put("stinger",       0);
        INSECT_BONES.put("left_antenna",  0);
        INSECT_BONES.put("right_antenna", 0);

        WORM_BONES.put("body",    0);
        WORM_BONES.put("segment", 0);

        FISH_BONES.put("body",     0);
        FISH_BONES.put("tail",     1);
        FISH_BONES.put("top_fin",  0);
        FISH_BONES.put("back_fin", 0);

        SLIME_BONES.put("cube",           0);
        SLIME_BONES.put("inside_cube",    0);
        SLIME_BONES.put("left_eye",       0);
        SLIME_BONES.put("right_eye",      0);
        SLIME_BONES.put("mouth",          0);

        GHAST_BONES.put("body",       0);
        GHAST_BONES.put("tentacle0",  1);
        GHAST_BONES.put("tentacle1",  2);
        GHAST_BONES.put("tentacle2",  3);
        GHAST_BONES.put("tentacle3",  4);
        GHAST_BONES.put("tentacle4",  5);
        GHAST_BONES.put("tentacle5",  6);
        GHAST_BONES.put("tentacle6",  7);
        GHAST_BONES.put("tentacle7",  8);
        GHAST_BONES.put("tentacle8",  9);

        CREEPER_BONES.put("head",        0);
        CREEPER_BONES.put("body",        1);
        CREEPER_BONES.put("leg1",        2);
        CREEPER_BONES.put("leg2",        3);
        CREEPER_BONES.put("leg3",        4);
        CREEPER_BONES.put("leg4",        5);
    }

    public synchronized void bake() {
        if (bakeState == BakeState.BAKING || bakeState == BakeState.READY) return;
        bakeState = BakeState.BAKING;

        try {
            if (!Rentities.config.entity_scan_mode && loadFromCache()) {
                Rentities.LOGGER.info("[EntityCache] Using cached mesh data — skipping bake");
                bootstrapTextures(Minecraft.getInstance().getEntityRenderDispatcher());
                return;
            }

            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.info("Starting Entity Mesh Baking...");
            }

            List<float[]> allVertices = new ArrayList<>();
            List<int[]> allIndices = new ArrayList<>();
            int vertexCount = 0;
            int indexCount = 0;

            var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            if (dispatcher == null) {
                throw new IllegalStateException("EntityRenderDispatcher is NULL");
            }

            var rendererMap = getRendererMap(dispatcher);
            if (rendererMap == null) {
                throw new IllegalStateException("Unable to access EntityRenderDispatcher renderer map");
            }

            for (EntityType<?> type : EntityBatchRegistry.REGISTRY_TYPES()) {
                EntityAnimationCategory category = EntityBatchRegistry.getCategory(type);
                if (category == EntityAnimationCategory.CPU_ANIMATED) continue;

                currentBakingTypeIdx = EntityBatchRegistry.getEntityTypeIndex(type);
                ensurePivotCapacity(currentBakingTypeIdx);
                clearBonePivotSlot(currentBakingTypeIdx);
                meshStatus.put(type, MeshStatus.BUILDING);

                float[] vertices = null;
                try {
                    var renderer = rendererMap.get(type);
                    if (renderer instanceof LivingEntityRenderer livingRenderer) {
                        vertices = extractFromLivingRenderer(
                                livingRenderer,
                                category,
                                new EntityMeshCapturingConsumer(),
                                new PoseStack());
                    }
                } catch (Throwable t) {
                    if (Rentities.IS_DEBUG) {
                        Rentities.LOGGER.error("Failed to bake mesh for {}", type, t);
                    }
                }

                if (vertices == null || vertices.length == 0) {
                    meshStatus.remove(type);
                    continue;
                }

                int[] localIndices = generateIndices(vertices.length / 9, 0);
                int[] rebasedIndices = Arrays.copyOf(localIndices, localIndices.length);
                for (int j = 0; j < rebasedIndices.length; j++) {
                    rebasedIndices[j] += vertexCount;
                }

                meshInfoMap.put(type, new MeshInfo(
                        vertexCount * VERTEX_STRIDE,
                        indexCount * 4,
                        rebasedIndices.length));
                cpuMeshes.put(type, new CpuMesh(vertices, localIndices));
                meshStatus.put(type, MeshStatus.READY);

                allVertices.add(vertices);
                allIndices.add(rebasedIndices);
                vertexCount += vertices.length / 9;
                indexCount += rebasedIndices.length;
            }

            uploadToGPU(allVertices, allIndices, vertexCount, indexCount);
            uploadPivotSSBO();
            bootstrapTextures(dispatcher, rendererMap);
            pendingTextureSave = true;
            currentBakingTypeIdx = -1;

            saveToCache();
            bakeState = BakeState.READY;
        } catch (Throwable t) {
            currentBakingTypeIdx = -1;
            bakeState = BakeState.FAILED;
            Rentities.LOGGER.error("Entity mesh bake failed; it can be retried", t);
        }
    }

    /** Set to true after bake to request a texture cache save from EntityBatchRenderer. */
    public volatile boolean pendingTextureSave = false;

    @SuppressWarnings("rawtypes")
    public void ensureTexturesBootstrapped() {
        if (texturesBootstrapped) return;
        bootstrapTextures(Minecraft.getInstance().getEntityRenderDispatcher());
        texturesBootstrapped = true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer<?, ?>> getRendererMap(
            net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher) {
        if (cachedRendererMap != null) return cachedRendererMap;
        if (dispatcher == null) return null;
        try {
            Field f = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class.getDeclaredField("field_4696");
            f.setAccessible(true);
            cachedRendererMap = (Map<EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer<?, ?>>) f.get(dispatcher);
            return cachedRendererMap;
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("Failed to access renderer map: {}", e.getMessage());
            return null;
        }
    }

    private static volatile Map<EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer<?, ?>> cachedRendererMap = null;

    private boolean texturesBootstrapped = false;

    private static void bootstrapTextures(net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher) {
        bootstrapTextures(dispatcher, getRendererMap(dispatcher));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void bootstrapTextures(
            net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
            Map<EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer<?, ?>> rendererMap) {
        if (dispatcher == null || rendererMap == null) return;
        EntityBatchRenderer renderer = EntityBatchRenderer.INSTANCE;
        if (renderer == null) return;
        EntityTextureBootstrap.bootstrap(renderer, (Map) rendererMap);
    }

    /**
     * Extracts geometry from a LivingEntityRenderer by walking its model's
     * named children and rendering each into the capturing consumer.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private float[] extractFromLivingRenderer(LivingEntityRenderer renderer,
                                               EntityAnimationCategory category,
                                               EntityMeshCapturingConsumer consumer,
                                               PoseStack poseStack) {
        if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Extracting from renderer: {}", renderer.getClass().getName());
        @SuppressWarnings("rawtypes") EntityModel model = getModelFromRenderer(renderer);
        if (model == null) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("Could not find EntityModel in renderer {}", renderer.getClass().getName());
            return null;
        }

        Map<String, Integer> boneMap = getBoneMap(category);
        consumer.reset();

        // Get root ModelPart (the model itself is a ModelPart tree)
        ModelPart root = getRootPart(model);
        if (root == null) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Root ModelPart is NULL for renderer {}", renderer.getClass().getName());
            
            // Try fallback: check for any ModelPart field on the model itself
            for (Field f : model.getClass().getDeclaredFields()) {
                if (f.getType() == ModelPart.class) {
                    try {
                        f.setAccessible(true);
                        root = (ModelPart) f.get(model);
                        if (root != null) {
                            if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Found fallback root ModelPart in field {}", f.getName());
                            break;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        if (root == null) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.warn("Final root ModelPart is NULL for renderer {}", renderer.getClass().getName());
            return null;
        }

        // Reset all bone rotations to bind pose
        resetPose(root);

        // Root transform derivation:
        // - translateAndRotate() divides pivots by 16 (pixels → blocks)
        // - Cube vertices are ALSO in block units after pose transform
        // - Shader expects vertices in PIXEL units, Y-up, feet at y=0
        //
        // We need: final_y = -16 * block_y + 24
        // i.e. scale Y by -16 (flip+scale back to pixels) then shift 24 pixels up
        //
        // Verified: arm_l pivot (5/16, 2/16, 0) → shader space (5, 22, 0) ✓
        //           feet (−,12/16+12/16,−) = (−,1.5,−) → shader space y=0 ✓
        //           head top (−,−8/16−0,−) = (−,−0.5,−) → shader space y=32 ✓
        //
        // In PoseStack (post-multiply): scale first, then translate in scaled space
        poseStack.pushPose();
        poseStack.scale(16.0f, -16.0f, 16.0f);   // scale up + flip Y
        poseStack.translate(0.0f, -1.5f, 0.0f);   // shift so feet land at y=0

        renderPartTree(root, boneMap, 0, consumer, poseStack, true);
        
        poseStack.popPose();

        float[] captured = consumer.bakeAndReset();
        if (Rentities.IS_DEBUG) {
            if (captured.length > 0) {
                Rentities.LOGGER.info("Extraction finished: captured {} vertices", captured.length / 9);
            } else {
                Rentities.LOGGER.warn("Extraction finished: captured 0 vertices!");
            }
        }
        return captured.length > 0 ? captured : null;
    }

    /**
     * Recursively walks a ModelPart tree, rendering each named part
     * with its assigned bone index from the bone map.
     *
     * KEY INVARIANT: Named bones are extracted in BONE-LOCAL space.
     * The shader pivot math assumes each bone's vertices are centered
     * at the bone's own origin (0,0,0 = pivot point). If we applied
     * translateAndRotate() for named bones, the vertices would be in
     * world/body space and the shader's pivot rotations would orbit wrongly.
     *
     * Non-named sub-parts (e.g. hat inside head) DO get their relative
     * transform applied so their position within the bone is correct.
     */
    private void renderPartTree(ModelPart part, Map<String, Integer> boneMap,
                                 int inheritedBone, EntityMeshCapturingConsumer consumer,
                                 PoseStack poseStack, boolean isRoot) {
        try {
            Field childrenField = getChildrenField();
            if (childrenField == null) return;

            @SuppressWarnings("unchecked")
            Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);
            if (children == null) return;

            if (isRoot) renderPartCubesDirectly(part, consumer, poseStack);

            for (Map.Entry<String, ModelPart> entry : children.entrySet()) {
                String name = entry.getKey();
                ModelPart child = entry.getValue();
                int boneIdx = boneMap.getOrDefault(name, inheritedBone);
                consumer.setBone(boneIdx);
                poseStack.pushPose();
                child.translateAndRotate(poseStack);

                // translateAndRotate() with reset pose (rotation=0) only applies the
                // pivot translation.  After the root scale(16,-16,16)+translate(0,-1.5,0)
                // the matrix's translation column gives the pivot in shader pixel space.
                if (currentBakingTypeIdx >= 0
                        && boneIdx >= 0 && boneIdx < MAX_BONES) {
                    Matrix4f m = poseStack.last().pose();
                    int base = (currentBakingTypeIdx * MAX_BONES + boneIdx) * 4;
                    int pivotIndex = currentBakingTypeIdx * MAX_BONES + boneIdx;
                    // A zero pivot is valid. Use an explicit written bit instead of treating
                    // (0,0,0) as the sentinel for an uninitialized bone.
                    if (!bonePivotWritten[pivotIndex]) {
                        bonePivotData[base]   = m.m30();
                        bonePivotData[base+1] = m.m31();
                        bonePivotData[base+2] = m.m32();
                        bonePivotData[base+3] = 0.0f;
                        bonePivotWritten[pivotIndex] = true;
                    }
                }

                renderPartCubesDirectly(child, consumer, poseStack);
                renderPartTree(child, boneMap, boneIdx, consumer, poseStack, false);
                poseStack.popPose();
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) Rentities.LOGGER.error("renderPartTree error: {}", e.getMessage());
        }
    }

    private Field cachedChildrenField = null;
    private Field cachedCubesField = null;
    private Method cachedCubeRenderMethod = null;
    private Field getChildrenField() {
        if (cachedChildrenField != null) return cachedChildrenField;
        for (String name : new String[]{"field_3661", "children", "n"}) {
            Field f = getCachedField(ModelPart.class, name);
            if (f != null && Map.class.isAssignableFrom(f.getType())) {
                cachedChildrenField = f;
                return f;
            }
        }
        for (Field f : ModelPart.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                cachedChildrenField = f;
                return f;
            }
        }
        return null;
    }

    /**
     * Renders the cubes of a ModelPart directly into the consumer.
     * Avoids ModelPart.render() recursion and double-transform issues.
     */
    private void renderPartCubesDirectly(ModelPart part, EntityMeshCapturingConsumer consumer,
                                   PoseStack poseStack) {
        try {
            if (cachedCubesField == null) {
                for (String name : new String[]{"field_3663", "cubes", "m"}) {
                    Field f = getCachedField(ModelPart.class, name);
                    if (f != null && List.class.isAssignableFrom(f.getType())) {
                        cachedCubesField = f;
                        break;
                    }
                }
            }

            if (cachedCubesField == null) return;

            @SuppressWarnings("unchecked")
            List<?> cubes = (List<?>) cachedCubesField.get(part);
            if (cubes == null || cubes.isEmpty()) return;

            for (Object cube : cubes) {
                if (cachedCubeRenderMethod == null ||
                        !cachedCubeRenderMethod.getDeclaringClass().isAssignableFrom(cube.getClass())) {
                    cachedCubeRenderMethod = null;
                    for (Method cand : cube.getClass().getDeclaredMethods()) {
                        if (cand.getParameterCount() == 5
                                && VertexConsumer.class.isAssignableFrom(cand.getParameterTypes()[1])) {
                            cand.setAccessible(true);
                            cachedCubeRenderMethod = cand;
                            break;
                        }
                    }
                }

                if (cachedCubeRenderMethod != null) {
                    cachedCubeRenderMethod.invoke(
                            cube,
                            poseStack.last(),
                            consumer,
                            0xF000F0,
                            0,
                            0xFFFFFFFF);
                }
            }
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn("renderPartCubesDirectly failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Extracts the EntityModel from a LivingEntityRenderer using reflection.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private EntityModel getModelFromRenderer(LivingEntityRenderer renderer) {
        // Try field named "model" first (official)
        try {
            Field f = renderer.getClass().getField("model");
            f.setAccessible(true);
            return (EntityModel) f.get(renderer);
        } catch (Exception ignored) {}

        // Then try "field_4744" (intermediary)
        try {
            Field f = getCachedField(renderer.getClass(), "field_4744");
            if (f != null) return (EntityModel) f.get(renderer);
        } catch (Exception ignored) {}

        // Fallback: search for any EntityModel field
        for (Field f : getAllFields(renderer.getClass())) {
            if (EntityModel.class.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(renderer);
                    if (val != null) {
                        if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Found EntityModel field: {} in {}", f.getName(), renderer.getClass().getSimpleName());
                        return (EntityModel) val;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private ModelPart getRootPart(EntityModel<?> model) {
        if (model == null) return null;
        
        // Strategy 1: Search for any public method root()
        try {
            for (Method m : model.getClass().getMethods()) {
                if (m.getName().equals("root") || m.getName().equals("method_62471") || m.getName().equals("a")) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == ModelPart.class) {
                        ModelPart root = (ModelPart) m.invoke(model);
                        if (root != null) return root;
                    }
                }
            }
        } catch (Exception ignored) {}

        // Strategy 2: Search for ANY field of type ModelPart (prioritize name "root")
        List<ModelPart> candidates = new ArrayList<>();
        try {
            Class<?> current = model.getClass();
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    if (f.getType() == ModelPart.class) {
                        f.setAccessible(true);
                        ModelPart part = (ModelPart) f.get(model);
                        if (part != null) {
                            if (f.getName().equalsIgnoreCase("root") || f.getName().equals("field_52912")) {
                                return part; // Highest priority
                            }
                            candidates.add(part);
                        }
                    }
                }
                current = current.getSuperclass();
            }
        } catch (Exception ignored) {}

        // If multiple candidates, pick the one that has the most children in its map
        ModelPart best = null;
        int maxChildren = -1;
        for (ModelPart p : candidates) {
            int c = countChildren(p);
            if (c > maxChildren) {
                maxChildren = c;
                best = p;
            }
        }

        return best;
    }

    private int countChildren(ModelPart part) {
        try {
            for (String name : new String[]{"field_3661", "children", "n"}) {
                Field f = getCachedField(ModelPart.class, name);
                if (f != null && Map.class.isAssignableFrom(f.getType())) {
                    Map<?, ?> map = (Map<?, ?>) f.get(part);
                    return map != null ? map.size() : 0;
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void resetPose(ModelPart part) {
        try {
            // Try known method names for resetPose
            // In 1.21.11, resetPose is method_41923 (Yarn) or 'c' (Mojang)
            for (String name : new String[]{"resetPose", "method_41923", "c"}) {
                try {
                    Method m = part.getClass().getMethod(name);
                    m.invoke(part);
                    return;
                } catch (NoSuchMethodException ignored) {}
            }
            
            // Manually zero rotations if method not found
            part.xRot = 0; part.yRot = 0; part.zRot = 0;
            // Search for children field by name/type
            Field childrenField = null;
            for (String name : new String[]{"children", "field_3661", "d"}) {
                childrenField = getCachedField(ModelPart.class, name);
                if (childrenField != null && Map.class.isAssignableFrom(childrenField.getType())) break;
            }
            
            if (childrenField != null) {
                @SuppressWarnings("unchecked")
                Map<String, ModelPart> children = (Map<String, ModelPart>) childrenField.get(part);
                if (children != null) children.values().forEach(this::resetPose);
            }
        } catch (Exception ignored) {}
    }

    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();

    private static Field getCachedField(Class<?> startClass, String name) {
        String key = startClass.getName() + "#" + name;
        if (FIELD_CACHE.containsKey(key)) return FIELD_CACHE.get(key);
        
        Class<?> cls = startClass;
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
                return f;
            } catch (NoSuchFieldException e) { 
                cls = cls.getSuperclass(); 
            }
        }
        
        // Final fallback: search for field by type if name search failed
        // For example, if "field_3661" (Map) or "field_3663" (List) changes
        if (name.equals("field_3661")) { // Map<String, ModelPart>
            for (Field f : startClass.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    FIELD_CACHE.put(key, f);
                    if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Fell back to Map field {} for field_3661", f.getName());
                    return f;
                }
            }
        } else if (name.equals("field_3663")) { // List<Cube>
            for (Field f : startClass.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    FIELD_CACHE.put(key, f);
                    if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Fell back to List field {} for field_3663", f.getName());
                    return f;
                }
            }
        }

        FIELD_CACHE.put(key, null);
        return null;
    }

    private static List<Field> getAllFields(Class<?> cls) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
            c = c.getSuperclass();
        }
        return fields;
    }

    private static Map<String, Integer> getBoneMap(EntityAnimationCategory category) {
        return switch (category) {
            case BIPED, FLOATING, FLOATING_SPINNING, SHULKER, STRIDER -> BIPED_BONES;
            case QUADRUPED, GOAT, SNIFFER, ARMADILLO, AQUATIC_LEGS, SWIMMING, FROG -> QUADRUPED_BONES;
            case HORSE -> HORSE_BONES;
            case BIRD -> BIRD_BONES;
            case ARTHROPOD -> ARTHROPOD_BONES;
            case INSECT -> INSECT_BONES;
            case WORM -> WORM_BONES;
            case FISH -> FISH_BONES;
            case SLIME -> SLIME_BONES;
            case GHAST -> GHAST_BONES;
            case CREEPER -> CREEPER_BONES;
            default -> BIPED_BONES;
        };
    }


    private void uploadToGPU(List<float[]> allVertices, List<int[]> allIndices,
                              int totalVertices, int totalIndices) {
        gpuMesh.delete();
        int vaoId = glCreateVertexArrays();
        int vboId = glCreateBuffers();
        int eboId = glCreateBuffers();
        gpuMesh.set(vaoId, vboId, eboId);

        long vboSize = (long) totalVertices * VERTEX_STRIDE;
        long eboSize = (long) totalIndices * 4L;
        glNamedBufferData(vboId, vboSize, GL_STATIC_DRAW);
        glNamedBufferData(eboId, eboSize, GL_STATIC_DRAW);

        ByteBuffer vbuf = MemoryUtil.memAlloc((int) vboSize);
        ByteBuffer ibuf = MemoryUtil.memAlloc((int) eboSize);
        try {
            FloatBuffer vf = vbuf.asFloatBuffer();
            long vertexCursor = 0;
            long indexCursor = 0;

            for (int i = 0; i < allVertices.size(); i++) {
                float[] verts = allVertices.get(i);
                int[] inds = allIndices.get(i);

                vf.put(verts);
                for (int idx : inds) {
                    if (idx < 0 || idx >= totalVertices) {
                        if (Rentities.IS_DEBUG) {
                            Rentities.LOGGER.error("INDEX OUT OF BOUNDS: {} not in [0,{})",
                                    idx, totalVertices);
                        }
                    }
                }
                ibuf.asIntBuffer().put(inds);

                vertexCursor += (long) verts.length * 4L;
                indexCursor += (long) inds.length * 4L;
                if (i + 1 < allVertices.size()) {
                    vf = vbuf.asFloatBuffer();
                    vf.position((int)(vertexCursor / 4L));
                    ibuf.asIntBuffer().position((int)(indexCursor / 4L));
                }
            }

            glNamedBufferSubData(vboId, 0, vbuf);
            glNamedBufferSubData(eboId, 0, ibuf);
        } finally {
            MemoryUtil.memFree(vbuf);
            MemoryUtil.memFree(ibuf);
        }

        glVertexArrayVertexBuffer(vaoId, 0, vboId, 0, VERTEX_STRIDE);
        glEnableVertexArrayAttrib(vaoId, 0);
        glVertexArrayAttribFormat(vaoId, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vaoId, 0, 0);
        glEnableVertexArrayAttrib(vaoId, 1);
        glVertexArrayAttribFormat(vaoId, 1, 3, GL_FLOAT, false, 12);
        glVertexArrayAttribBinding(vaoId, 1, 0);
        glEnableVertexArrayAttrib(vaoId, 2);
        glVertexArrayAttribFormat(vaoId, 2, 2, GL_FLOAT, false, 24);
        glVertexArrayAttribBinding(vaoId, 2, 0);
        glEnableVertexArrayAttrib(vaoId, 3);
        glVertexArrayAttribFormat(vaoId, 3, 1, GL_FLOAT, false, 32);
        glVertexArrayAttribBinding(vaoId, 3, 0);
        glVertexArrayElementBuffer(vaoId, eboId);
    }

    private int[] generateIndices(int vertexCount, int baseVertex) {
        int quadCount = vertexCount / 4;
        int[] idx = new int[quadCount * 6];
        for (int i = 0; i < quadCount; i++) {
            int b = baseVertex + i*4, o = i*6;
            idx[o]=b; idx[o+1]=b+1; idx[o+2]=b+2;
            idx[o+3]=b; idx[o+4]=b+2; idx[o+5]=b+3;
        }
        return idx;
    }

    private void ensurePivotCapacity(int typeIndex) {
        int requiredTypes = typeIndex + 1;
        int currentTypes = bonePivotData.length / (MAX_BONES * 4);
        if (requiredTypes <= currentTypes) return;

        int newTypes = Math.max(requiredTypes, currentTypes * 2);
        bonePivotData = Arrays.copyOf(
                bonePivotData, newTypes * MAX_BONES * 4);
        bonePivotWritten = Arrays.copyOf(
                bonePivotWritten, newTypes * MAX_BONES);
    }

    private void clearBonePivotSlot(int typeIndex) {
        if (typeIndex < 0) return;
        ensurePivotCapacity(typeIndex);
        int start = typeIndex * MAX_BONES;
        Arrays.fill(bonePivotWritten, start, start + MAX_BONES, false);
        Arrays.fill(bonePivotData, typeIndex * MAX_BONES * 4,
                (typeIndex + 1) * MAX_BONES * 4, 0.0f);
    }

    /**
     * Dynamically extract and register a missing mesh without requiring Scan Mode or a restart.
     * Must be called from the Minecraft render thread because it touches live renderer/model data and GL buffers.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized MeshStatus ensureMeshFor(EntityType<?> type) {
        if (type == null) return MeshStatus.UNKNOWN;
        if (cpuMeshes.containsKey(type)) return MeshStatus.READY;

        long now = System.nanoTime();
        long retryAt = retryAfterNanos.getOrDefault(type, 0L);
        if (now < retryAt) return MeshStatus.UNKNOWN;

        MeshStatus known = meshStatus.get(type);
        if (known == MeshStatus.BUILDING) return MeshStatus.BUILDING;

        var dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        if (dispatcher == null) {
            recordExtractionFailure(type, now);
            return MeshStatus.UNKNOWN;
        }

        Map<EntityType<?>, net.minecraft.client.renderer.entity.EntityRenderer<?, ?>> rendererMap =
                getRendererMap(dispatcher);
        if (rendererMap == null) {
            recordExtractionFailure(type, now);
            return MeshStatus.UNKNOWN;
        }

        var renderer = rendererMap.get(type);
        if (!(renderer instanceof LivingEntityRenderer livingRenderer)) {
            // This is not a permanent failure. A renderer may be initialized later,
            // and generic renderer adapters can be added without poisoning this type.
            recordExtractionFailure(type, now);
            return MeshStatus.UNKNOWN;
        }

        EntityAnimationCategory category = EntityBatchRegistry.getCategory(type);
        if (category == EntityAnimationCategory.CPU_ANIMATED) {
            // The CPU path owns these types; leave them to vanilla rather than marking
            // them as an extraction failure.
            return MeshStatus.UNKNOWN;
        }

        meshStatus.put(type, MeshStatus.BUILDING);
        currentBakingTypeIdx = EntityBatchRegistry.getEntityTypeIndex(type);
        ensurePivotCapacity(currentBakingTypeIdx);

        // Extract into an isolated pivot buffer so a failed extraction cannot partially
        // mutate the authoritative pivot table.
        float[] previousPivots = Arrays.copyOf(bonePivotData, bonePivotData.length);
        boolean[] previousWritten = Arrays.copyOf(bonePivotWritten, bonePivotWritten.length);

        try {
            clearBonePivotSlot(currentBakingTypeIdx);

            float[] vertices = extractFromLivingRenderer(
                    livingRenderer,
                    category,
                    new EntityMeshCapturingConsumer(),
                    new PoseStack());

            if (vertices == null || vertices.length == 0) {
                bonePivotData = previousPivots;
                bonePivotWritten = previousWritten;
                recordExtractionFailure(type, System.nanoTime());
                meshStatus.remove(type);
                return MeshStatus.UNKNOWN;
            }

            int[] indices = generateIndices(vertices.length / 9, 0);
            CpuMesh mesh = new CpuMesh(vertices, indices);

            // Commit mesh + pivots together only after extraction succeeds.
            cpuMeshes.put(type, mesh);
            meshStatus.put(type, MeshStatus.READY);
            failureCounts.remove(type);
            retryAfterNanos.remove(type);

            rebuildGpuBuffersFromCpuMeshes();
            uploadPivotSSBO();
            bootstrapTextures(dispatcher, rendererMap);
            saveCurrentMeshesToCacheAsync();
            return MeshStatus.READY;
        } catch (Throwable t) {
            bonePivotData = previousPivots;
            bonePivotWritten = previousWritten;
            meshStatus.remove(type);
            recordExtractionFailure(type, System.nanoTime());
            Rentities.LOGGER.error("Dynamic mesh extraction failed for {}", type, t);
            return MeshStatus.UNKNOWN;
        } finally {
            currentBakingTypeIdx = -1;
        }
    }

    private void recordExtractionFailure(EntityType<?> type, long now) {
        int failures = failureCounts.merge(type, 1, Integer::sum);
        int shift = Math.min(Math.max(failures - 1, 0), 5);
        long delay = TimeUnit.SECONDS.toNanos(1L << shift);
        delay = Math.min(delay, TimeUnit.SECONDS.toNanos(30L));
        retryAfterNanos.put(type, now + delay);
        meshStatus.remove(type);
    }

    private void rebuildGpuBuffersFromCpuMeshes() {
        List<float[]> vertices = new ArrayList<>();
        List<int[]> indices = new ArrayList<>();
        meshInfoMap.clear();
        int vertexCount = 0;
        int indexCount = 0;
        for (Map.Entry<EntityType<?>, CpuMesh> entry : cpuMeshes.entrySet()) {
            CpuMesh mesh = entry.getValue();
            int[] baseIndices = mesh.indices();
            int[] rebased = Arrays.copyOf(baseIndices, baseIndices.length);
            for (int i = 0; i < rebased.length; i++) rebased[i] += vertexCount;
            int vByte = vertexCount * VERTEX_STRIDE;
            int iByte = indexCount * 4;
            meshInfoMap.put(entry.getKey(), new MeshInfo(vByte, iByte, rebased.length));
            vertices.add(mesh.vertices());
            indices.add(rebased);
            vertexCount += mesh.vertices().length / 9;
            indexCount += rebased.length;
        }
        uploadToGPU(vertices, indices, vertexCount, indexCount);
        if (pivotSSBOId != 0) uploadPivotSSBO();
    }

    private Map<EntityType<?>, CpuMesh> snapshotCpuMeshes() {
        return new LinkedHashMap<>(cpuMeshes);
    }

    private void saveCurrentMeshesToCacheAsync() {
        getCacheFile(); // resolve on the render thread
        Map<EntityType<?>, CpuMesh> snapshot = snapshotCpuMeshes();
        cacheExecutor.execute(() -> saveCpuMeshesToCache(snapshot));
    }

    private void saveCpuMeshesToCache(Map<EntityType<?>, CpuMesh> meshes) {
        saveToCacheInternal(meshes);
    }

    public int getVaoId() { return gpuMesh.vao(); }
    public Map<EntityType<?>, MeshInfo> getMeshInfoMap() { return meshInfoMap; }
    public boolean isBaked() { return bakeState == BakeState.READY; }
    public BakeState getBakeState() { return bakeState; }


    /**
     * Uploads the bone pivot table to a static SSBO (binding 13).
     * Must be called AFTER bake() completes.
     * Returns the GL buffer id.
     */
    public int uploadPivotSSBO() {
        if (pivotSSBOId == 0) pivotSSBOId = glCreateBuffers();
        ByteBuffer buf = MemoryUtil.memAlloc(bonePivotData.length * 4);
        buf.asFloatBuffer().put(bonePivotData).flip();
        glNamedBufferData(pivotSSBOId, buf, GL_STATIC_DRAW);
        MemoryUtil.memFree(buf);
        if (Rentities.IS_DEBUG) Rentities.LOGGER.info("Uploaded bone pivot SSBO: {} entries", bonePivotData.length / 4);
        return pivotSSBOId;
    }

    public int getPivotSSBOId() { return pivotSSBOId; }

    private static final int CACHE_MAGIC   = 0xECAC1021;
    private static final int CACHE_VERSION = 4;

    /** Cache file location: .minecraft/rentities_entity_mesh_cache.bin */
    private static java.io.File cacheFile = null;

    private static java.io.File getCacheFile() {
        if (cacheFile == null) {
            cacheFile = new java.io.File(
                net.minecraft.client.Minecraft.getInstance().gameDirectory,
                "rentities_entity_mesh_cache.bin");
        }
        return cacheFile;
    }

    /**
     * Serialises the baked mesh data to disk.
     * Call after bake() succeeds.
     */
    public void saveToCache() {
        getCacheFile(); // resolve while Minecraft is on the render thread
        cacheExecutor.execute(() -> saveToCacheInternal(snapshotCpuMeshes()));
    }

    private void saveToCacheInternal(Map<EntityType<?>, CpuMesh> meshes) {
        java.io.File f = getCacheFile();
        java.nio.file.Path target = f.toPath();
        java.nio.file.Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(
                        java.nio.file.Files.newOutputStream(
                                temp,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)))) {
            out.writeInt(CACHE_MAGIC);
            out.writeInt(CACHE_VERSION);
            out.writeInt(meshes.size());

            int vertexOffset = 0;
            int indexOffset = 0;
            for (Map.Entry<EntityType<?>, CpuMesh> entry : meshes.entrySet()) {
                EntityType<?> type = entry.getKey();
                CpuMesh mesh = entry.getValue();

                String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(type).toString();
                byte[] idBytes = id.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(idBytes.length);
                out.write(idBytes);

                float[] verts = mesh.vertices();
                int[] inds = mesh.indices();
                int[] rebased = Arrays.copyOf(inds, inds.length);
                for (int i = 0; i < rebased.length; i++) rebased[i] += vertexOffset;

                out.writeInt(verts.length);
                for (float v : verts) out.writeFloat(v);
                out.writeInt(rebased.length);
                for (int idx : rebased) out.writeInt(idx);

                out.writeInt(vertexOffset * VERTEX_STRIDE);
                out.writeInt(indexOffset * 4);
                out.writeInt(rebased.length);

                vertexOffset += verts.length / 9;
                indexOffset += rebased.length;
            }

            out.writeInt(bonePivotData.length);
            for (float p : bonePivotData) out.writeFloat(p);

            out.flush();
        } catch (Exception e) {
            Rentities.LOGGER.error("[EntityCache] Failed to serialize cache: {}", e.getMessage());
            return;
        }

        try {
            try {
                java.nio.file.Files.move(
                        temp, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                java.nio.file.Files.move(
                        temp, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Rentities.LOGGER.info("[EntityCache] Saved {} entity types to {}", meshes.size(), f.getPath());
        } catch (Exception e) {
            Rentities.LOGGER.error("[EntityCache] Failed to save cache: {}", e.getMessage());
        }
    }

    /**
     * Loads previously saved mesh data directly to GPU without running the
     * full vanilla model extraction pipeline.
     * Returns true if cache was loaded successfully.
     */
    public boolean loadFromCache() {
        java.io.File f = getCacheFile();
        if (!f.exists()) return false;

        cpuMeshes.clear();
        meshInfoMap.clear();
        meshStatus.clear();

        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {

            if (in.readInt() != CACHE_MAGIC)   { Rentities.LOGGER.warn("[EntityCache] Bad magic"); return false; }
            if (in.readInt() != CACHE_VERSION)  { Rentities.LOGGER.warn("[EntityCache] Version mismatch"); return false; }

            int typeCount = in.readInt();
            List<float[]> allVertices = new ArrayList<>(typeCount);
            List<int[]>   allIndices  = new ArrayList<>(typeCount);

            Map<String, EntityType<?>> typesById = new HashMap<>();
            for (EntityType<?> candidate : EntityBatchRegistry.REGISTRY_TYPES()) {
                Object key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(candidate);
                if (key != null) typesById.put(key.toString(), candidate);
            }
            int totalVertexCount = 0, totalIndexCount = 0;

            for (int i = 0; i < typeCount; i++) {
                // Type ID
                int idLen = in.readInt();
                byte[] idBytes = new byte[idLen];
                in.readFully(idBytes);
                String id = new String(idBytes, java.nio.charset.StandardCharsets.UTF_8);
                // Find entity type by matching the saved ID string against all known types.
                // Avoids ResourceLocation/reflection issues — same iteration used in bake().
                EntityType<?> type = typesById.get(id);
                if (type == null) {
                    Rentities.LOGGER.warn("[EntityCache] Unknown entity type: {}, skipping", id);
                    // Still need to read past the data
                    int vLen = in.readInt(); for (int j=0;j<vLen;j++) in.readFloat();
                    int iLen = in.readInt(); for (int j=0;j<iLen;j++) in.readInt();
                    in.readInt(); in.readInt(); in.readInt(); // meshInfo
                    continue;
                }

                // Vertices
                int vLen = in.readInt();
                float[] verts = new float[vLen];
                for (int j = 0; j < vLen; j++) verts[j] = in.readFloat();

                // Indices
                int iLen = in.readInt();
                int[] inds = new int[iLen];
                for (int j = 0; j < iLen; j++) inds[j] = in.readInt();
                cpuMeshes.put(type, new CpuMesh(verts, inds));
                meshStatus.put(type, MeshStatus.READY);

                // Mesh info in the cache contains global byte offsets. The authoritative
                // CPU mesh map stores local indices so rebuilding can safely rebase once.
                int vOffset = in.readInt();
                int iOffset = in.readInt();
                int iCount  = in.readInt();
                int baseVertex = vOffset / VERTEX_STRIDE;
                for (int j = 0; j < inds.length; j++) {
                    inds[j] -= baseVertex;
                }

                meshInfoMap.put(type, new MeshInfo(vOffset, iOffset, iCount));

                allVertices.add(verts);
                int[] rebasedForUpload = Arrays.copyOf(inds, inds.length);
                for (int j = 0; j < rebasedForUpload.length; j++) {
                    rebasedForUpload[j] += totalVertexCount;
                }
                allIndices.add(rebasedForUpload);
                totalVertexCount += verts.length / 9;
                totalIndexCount  += iLen;
            }

            // Pivot table
            int pivotLen = in.readInt();
            if (pivotLen > bonePivotData.length) {
                int requiredTypes = (pivotLen + MAX_BONES * 4 - 1) / (MAX_BONES * 4);
                ensurePivotCapacity(requiredTypes - 1);
            }
            for (int i = 0; i < pivotLen; i++)
                bonePivotData[i] = in.readFloat();
            Arrays.fill(bonePivotWritten, true);

            uploadToGPU(allVertices, allIndices, totalVertexCount, totalIndexCount);
            bakeState = BakeState.READY;
            Rentities.LOGGER.info("[EntityCache] Loaded {} entity types from cache", meshInfoMap.size());
            return true;

        } catch (Exception e) {
            Rentities.LOGGER.error("[EntityCache] Failed to load cache: {}", e.getMessage());
            meshInfoMap.clear();
            bakeState = BakeState.FAILED;
            return false;
        }
    }

    public static boolean cacheExists() { return getCacheFile().exists(); }
    public static void deleteCache()    { getCacheFile().delete(); }
    public static void deleteTextureCache() { EntityTextureAtlas.deleteTextureCache(); }

    public void delete() {
        cacheExecutor.shutdown();
        gpuMesh.delete();
    }
}
