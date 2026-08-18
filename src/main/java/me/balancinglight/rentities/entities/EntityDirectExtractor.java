package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.core.Rotations;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Extracts instance data straight off the {@link Entity}, bypassing vanilla render states.
 *
 * <p>Vanilla's per-frame cost for an entity is dominated by what happens <em>before</em> any
 * draw call is issued: {@code EntityRenderer.createRenderState} allocates a state object,
 * {@code extractRenderState} walks the model hierarchy, resolves the texture, copies item
 * stacks and equipment, builds the name-tag component and computes leash/shadow data. All of
 * that exists only to feed the immediate-mode vertex consumer, and every byte of it is dead
 * weight for an instance the GPU animates from its instance payload.
 *
 * <p>Cancelling at {@code EntityRenderDispatcher.submit} — where the previous revision hooked
 * — is far too late: the state has already been created and populated. Hooking
 * {@code LevelRenderer.extractEntity} instead means the state is never built at all.
 * The hook still has to hand a non-null state back to the caller, which adds it to a list and
 * dereferences it, so a single reusable {@link #SENTINEL} is returned for every batched
 * entity in every frame.
 */
public final class EntityDirectExtractor {

    /**
     * Returned in place of a real render state. Never populated and never rendered; it exists
     * only so the vanilla caller has a non-null object to add to its list.
     */

    /**
     * Kept as the normal dispatcher sentinel used by the existing mixin.
     */
    public static final net.minecraft.client.renderer.entity.state.EntityRenderState SENTINEL =
            new net.minecraft.client.renderer.entity.state.EntityRenderState();

    /**
     * Prevents entities rendered as part of special renderers, such as mob-spawner
     * previews, from being captured by Rentities.
     *
     * ThreadLocal is used because rendering happens on the client render thread and
     * the guard must not leak between unrelated rendering operations.
     */
    private static final ThreadLocal<Integer> SKIP_BATCHING_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    public static void setSkipBatching(boolean skip) {
        int depth = SKIP_BATCHING_DEPTH.get();
        if (skip) {
            SKIP_BATCHING_DEPTH.set(depth + 1);
        } else {
            SKIP_BATCHING_DEPTH.set(Math.max(0, depth - 1));
        }
    }

    public static boolean isSkippingBatching() {
        return SKIP_BATCHING_DEPTH.get() > 0;
    }

    private EntityDirectExtractor() {}
    /**
     * Writes {@code entity} into the batch queue if it can be GPU-instanced.
     *
     * @return true if the entity was queued and vanilla extraction must be skipped
     */
    public static boolean tryExtract(Entity entity, float partialTick) {
        if (isSkippingBatching()) return false;

        EntityBatchRenderer renderer = EntityBatchRenderer.INSTANCE;
        if (renderer == null || entity == null) return false;

        EntityType<?> type = entity.getType();
        if (EntityBatchRegistry.getCategory(type) == EntityAnimationCategory.CPU_ANIMATED) return false;
        if (!renderer.asyncAllowsBatch(type)) return false;
        if (!renderer.asyncVisibilityAllows(entity)) return false;
        if (!renderer.hasMeshFor(type)) return false;
        // Never cancel vanilla unless the complete GPU preflight is valid. A texture
        // ResourceLocation alone is insufficient; the GL texture must be live.
        if (!renderer.canBatchEntity(type)) return false;

        /*
         * The current GPU mesh path deliberately bakes only the entity itself.
         * It does not render vanilla item/armor model layers. Equipment is still
         * extracted into the instance payload for future support, but using those
         * IDs without a matching item-model renderer makes equipped mobs appear
         * naked / empty-handed. Let vanilla render any equipped LivingEntity until
         * the item-model layer is actually implemented.
         *
         * This is also important for enchanted armor, shields, bows, tools, and
         * custom item models: vanilla must own those layers rather than silently
         * dropping them.
         */
        if (entity instanceof LivingEntity living && hasRenderableEquipment(living)) {
            return false;
        }

        long ptr = EntityBatchRenderer.reserveInstance(type);
        if (ptr == 0L) return false;

        if (!write(ptr, entity, type, partialTick)) {
            EntityBatchRenderer.releaseReservedInstance(type);
            return false;
        }
        return true;
    }

    private static boolean write(long ptr, Entity entity, EntityType<?> type, float partialTick) {
        try {
            return writeInternal(ptr, entity, type, partialTick);
        } catch (Throwable t) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn("[Rentities] Direct entity extraction failed for {}: {}", type, t.getMessage());
            }
            return false;
        }
    }

    private static boolean writeInternal(long ptr, Entity entity, EntityType<?> type, float partialTick) {
        Vec3 pos = entity.getPosition(partialTick);

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_POSITION_X,
                (float) (pos.x - EntityBatchRenderer.cameraX));
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_POSITION_Y,
                (float) (pos.y - EntityBatchRenderer.cameraY));
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_POSITION_Z,
                (float) (pos.z - EntityBatchRenderer.cameraZ));

        float bodyYawDeg;
        float limbSwing = 0f;
        float limbSwingAmt = 0f;
        float headYawRel = 0f;
        float headPitchRel = 0f;
        float attackProgress = 0f;
        float swimProgress = 0f;
        float sneakProgress = 0f;
        float hurtTime = 0f;
        float deathTime = 0f;
        float bowPull = 0f;
        float riptide = 0f;
        float sitProgress = 0f;
        float eatProgress = 0f;
        float swellAmount = 0f;
        float explodeProgress = 0f;
        float rollProgress = 0f;

        /*
         * Armor-stand pose data is not derived from the normal LivingEntity animation
         * inputs. Vanilla transfers the entity's six Rotations into ArmorStandRenderState
         * inside ArmorStandRenderer.extractRenderState(...).
         *
         * We intentionally invoke that exact vanilla extraction path here so /data changes,
         * marker/small/etc. state and all NBT-backed pose values are interpreted exactly as
         * Minecraft itself interprets them.
         */
        ArmorStandRenderState armorStandState = null;
        if (entity instanceof ArmorStand armorStand) {
            armorStandState = extractArmorStandRenderState(armorStand, partialTick);
            bodyYawDeg = armorStand.getYRot(partialTick);

            // Explicitly initialize all six pose vectors to identity before extraction.
            writeArmorStandPose(
                    ptr + EntityInstance.OFFSET_ARMOR_STAND_HEAD_POSE,
                    0.0f, 0.0f, 0.0f);
            writeArmorStandPose(
                    ptr + EntityInstance.OFFSET_ARMOR_STAND_BODY_POSE,
                    0.0f, 0.0f, 0.0f);
            writeArmorStandPose(
                    ptr + EntityInstance.OFFSET_ARMOR_STAND_LEFT_ARM_POSE,
                    0.0f, 0.0f, 0.0f);
            writeArmorStandPose(
                    ptr + EntityInstance.OFFSET_ARMOR_STAND_RIGHT_ARM_POSE,
                    0.0f, 0.0f, 0.0f);
            writeArmorStandPose(
                    ptr + EntityInstance.OFFSET_ARMOR_STAND_LEFT_LEG_POSE,
                    0.0f, 0.0f, 0.0f);
            writeArmorStandPose(
                    ptr + EntityInstance.OFFSET_ARMOR_STAND_RIGHT_LEG_POSE,
                    0.0f, 0.0f, 0.0f);

            if (armorStandState != null) {
                writeArmorStandPose(
                        ptr + EntityInstance.OFFSET_ARMOR_STAND_HEAD_POSE,
                        armorStandState.headPose);

                writeArmorStandPose(
                        ptr + EntityInstance.OFFSET_ARMOR_STAND_BODY_POSE,
                        armorStandState.bodyPose);

                writeArmorStandPose(
                        ptr + EntityInstance.OFFSET_ARMOR_STAND_LEFT_ARM_POSE,
                        armorStandState.leftArmPose);

                writeArmorStandPose(
                        ptr + EntityInstance.OFFSET_ARMOR_STAND_RIGHT_ARM_POSE,
                        armorStandState.rightArmPose);

                writeArmorStandPose(
                        ptr + EntityInstance.OFFSET_ARMOR_STAND_LEFT_LEG_POSE,
                        armorStandState.leftLegPose);

                writeArmorStandPose(
                        ptr + EntityInstance.OFFSET_ARMOR_STAND_RIGHT_LEG_POSE,
                        armorStandState.rightLegPose);
            }
        } else {
            // Non-armor-stand entities have identity armor-stand pose fields.
            clearArmorStandPose(ptr);

            if (entity instanceof LivingEntity living) {
                bodyYawDeg = lerpDegrees(
                        partialTick,
                        living.yBodyRotO,
                        living.yBodyRot);

                limbSwing = living.walkAnimation.position(partialTick);
                limbSwingAmt = Math.min(
                        living.walkAnimation.speed(partialTick),
                        1.0f);

                headYawRel = (float) Math.toRadians(
                        wrapDegrees(
                                lerpDegrees(
                                        partialTick,
                                        living.yHeadRotO,
                                        living.yHeadRot) - bodyYawDeg));

                headPitchRel =
                        (float) Math.toRadians(
                                net.minecraft.util.Mth.lerp(
                                        partialTick,
                                        living.xRotO,
                                        living.getXRot()));

                attackProgress = living.getAttackAnim(partialTick);
                swimProgress = living.getSwimAmount(partialTick);

                if (living.hurtTime > 0) {
                    hurtTime = 10f;
                }

                if (living.deathTime > 0) {
                    deathTime = Math.min(living.deathTime, 20f);
                }

                /*
                 * Bow pull — vanilla draws over 20 ticks, 0 → 1.
                 * LivingEntity#getTicksUsingItem(partialTick) mirrors the RenderState's
                 * useItemTicks used by vanilla item rendering.
                 */
                ItemStack useItem = living.getUseItem();
                if (living.isUsingItem() && !useItem.isEmpty()) {
                    ItemUseAnimation useAnim = useItem.getUseAnimation();

                    if (useAnim == ItemUseAnimation.BOW) {
                        bowPull = net.minecraft.util.Mth.clamp(
                                living.getTicksUsingItem(partialTick) / 20f, 0f, 1f);
                    } else if (useAnim == ItemUseAnimation.EAT
                            || useAnim == ItemUseAnimation.DRINK) {
                        int remaining = Math.max(0, living.getUseItemRemainingTicks());
                        int maxUseDuration = Math.max(1, useItem.getUseDuration(living));
                        float charge = 1f - Math.min(remaining, maxUseDuration) / (float) maxUseDuration;
                        eatProgress = net.minecraft.util.Mth.clamp(charge, 0f, 1f);
                    }
                }

                if (entity instanceof Creeper creeper) {
                    // Vanilla interpolated swelling — matches CreeperRenderer.extractRenderState.
                    swellAmount = creeper.getSwelling(partialTick);

                    // Explosion/death animation uses the vanilla death timeline (0..20 ticks).
                    if (creeper.deathTime > 0) {
                        explodeProgress = net.minecraft.util.Mth.clamp(
                                creeper.deathTime / 20f, 0f, 1f);
                    }
                }

                if (entity instanceof Armadillo armadillo) {
                    // Roll-up progress = time spent rolling, driven by vanilla's state.
                    if (armadillo.rollUpAnimationState.isStarted()) {
                        rollProgress = net.minecraft.util.Mth.clamp(
                                (float) armadillo.rollUpAnimationState.getTimeInMillis(partialTick) / 1000f,
                                0f, 1f);
                    }
                }

                if (entity instanceof TamableAnimal tamable && tamable.isInSittingPose()) {
                    sitProgress = 1f;
                }

                if (living.isAutoSpinAttack()) {
                    riptide = 1f;
                }
            } else {
                bodyYawDeg = lerpDegrees(
                        partialTick,
                        entity.yRotO,
                        entity.getYRot());
            }
        }

        if (!(entity instanceof ArmorStand) && entity.isCrouching()) {
            sneakProgress = 1f;
        }

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_ROTATION_Y,
                (float) Math.toRadians(180.0f - bodyYawDeg));

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_LIMB_SWING,
                limbSwing);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_LIMB_SWING_AMT,
                limbSwingAmt);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_HEAD_YAW,
                headYawRel);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_HEAD_PITCH,
                headPitchRel);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_ATTACK_PROGRESS,
                attackProgress);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_BOW_PULL,
                bowPull);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_HURT_TIME,
                hurtTime);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_DEATH_TIME,
                deathTime);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SNEAK_PROGRESS,
                sneakProgress);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SWIM_PROGRESS,
                swimProgress);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_RIPTIDE,
                riptide);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SIT_PROGRESS,
                sitProgress);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_EAT_PROGRESS,
                eatProgress);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SWELL_AMOUNT,
                swellAmount);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_EXPLODE_PROGRESS,
                explodeProgress);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_ROLL_PROGRESS,
                rollProgress);

        int flags = 0;

        if (entity.isInvisible()) {
            flags |= EntityInstance.FLAG_IS_INVISIBLE;
        }

        if (entity.onGround()) {
            flags |= EntityInstance.FLAG_ON_GROUND;
        }

        if (entity.isInWater()) {
            flags |= EntityInstance.FLAG_IS_IN_WATER;
        }

        if (type == EntityType.PLAYER) {
            flags |= EntityInstance.FLAG_IS_PLAYER;
        }

        if (EntityBatchRegistry.hasZombieArms(type)) {
            flags |= EntityInstance.FLAG_ZOMBIE_ARMS;
        }

        /*
         * This flag lets the shader distinguish an armor stand from an ordinary BIPED.
         * Armor stands must use their explicit six-part pose rather than the generic
         * walk/head animation.
         */
        if (type == EntityType.ARMOR_STAND) {
            flags |= EntityInstance.FLAG_ARMOR_STAND;
        }

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_FLAGS,
                flags);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_ENTITY_TYPE,
                EntityBatchRegistry.getEntityTypeIndex(type));

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_ANIM_CATEGORY,
                EntityBatchRegistry.getCategory(type).glslId);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_TEXTURE_LAYER,
                0);

        /*
         * Equipment extraction.
         *
         * Rentities' GPU mesh pipeline bakes the bare entity model and cannot currently
         * render arbitrary item models (the shader keeps these int fields for future use but
         * never samples them). We therefore:
         *
         *   1. Extract the true equipment state from the LivingEntity's slots so the data
         *      is correct and future-proof.
         *   2. Encode each slot as a stable integer ID that the renderer can later map to a
         *      mesh/texture — currently a datapack-independent raw item ID.
         *   3. Keep the NO_ITEM / NO_ARMOR sentinels as the fallback so the GPU instance
         *      payload is always valid.
         *
         * Nothing is invented: the IDs come from BuiltInRegistries.ITEM and the empty-stack
         * test matches vanilla's ItemStack#isEmpty.
         */
        int heldMain = EntityInstance.NO_ITEM;
        int heldOffhand = EntityInstance.NO_ITEM;
        int armorHead = EntityInstance.NO_ARMOR;
        int armorChest = EntityInstance.NO_ARMOR;
        int armorLegs = EntityInstance.NO_ARMOR;
        int armorFeet = EntityInstance.NO_ARMOR;

        if (entity instanceof LivingEntity livingEquip) {
            heldMain = itemId(livingEquip.getItemBySlot(EquipmentSlot.MAINHAND));
            heldOffhand = itemId(livingEquip.getItemBySlot(EquipmentSlot.OFFHAND));
            armorHead = itemId(livingEquip.getItemBySlot(EquipmentSlot.HEAD));
            armorChest = itemId(livingEquip.getItemBySlot(EquipmentSlot.CHEST));
            armorLegs = itemId(livingEquip.getItemBySlot(EquipmentSlot.LEGS));
            armorFeet = itemId(livingEquip.getItemBySlot(EquipmentSlot.FEET));
        }

        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_HELD_MAIN,  heldMain);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_HELD_OFFHAND, heldOffhand);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_HEAD,  armorHead);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_CHEST, armorChest);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_LEGS,  armorLegs);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_FEET,  armorFeet);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_MOUNT_ID,
                EntityInstance.NO_MOUNT);

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SEAT_OFFSET_X,
                0f);

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SEAT_OFFSET_Y,
                0f);

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SEAT_OFFSET_Z,
                0f);

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_TEX_SCALE_X,
                1f);

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_TEX_SCALE_Y,
                1f);

        return true;
    }

    /**
     * Runs vanilla's exact ArmorStandRenderer -> ArmorStandRenderState extraction path.
     *
     * This is deliberately preferred over reading ArmorStand#getHeadPose()/etc. directly:
     * it keeps Rentities aligned with the render-state representation used by vanilla
     * 1.21.11 and ensures the state has gone through the same extraction logic as the
     * normal renderer.
     */
    private static ArmorStandRenderState extractArmorStandRenderState(
            ArmorStand armorStand,
            float partialTick) {

        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) return null;

            EntityRenderer<? super ArmorStand, ?> renderer =
                    minecraft.getEntityRenderDispatcher().getRenderer(armorStand);

            if (!(renderer instanceof ArmorStandRenderer armorStandRenderer)) {
                if (Rentities.IS_DEBUG) {
                    Rentities.LOGGER.warn(
                            "Expected ArmorStandRenderer for armor stand, got {}",
                            renderer != null ? renderer.getClass().getName() : "null");
                }
                return null;
            }

            ArmorStandRenderState state =
                    armorStandRenderer.createRenderState();

            armorStandRenderer.extractRenderState(
                    armorStand,
                    state,
                    partialTick);

            return state;
        } catch (Exception e) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn(
                        "Failed to extract ArmorStandRenderState",
                        e);
            }
            return null;
        }
    }

    private static void writeArmorStandPose(
            long ptr,
            Rotations rotation) {

        if (rotation == null) {
            writeArmorStandPose(ptr, 0f, 0f, 0f);
            return;
        }

        // Rotations stores pitch(x), yaw(y), roll(z) in degrees.
        // Convert once on the CPU; the shader works entirely in radians.
        writeArmorStandPose(
                ptr,
                (float) Math.toRadians(rotation.x()),
                (float) Math.toRadians(rotation.y()),
                (float) Math.toRadians(rotation.z()));
    }

    private static void writeArmorStandPose(
            long ptr,
            float pitch,
            float yaw,
            float roll) {

        MemoryUtil.memPutFloat(ptr, pitch);
        MemoryUtil.memPutFloat(ptr + 4L, yaw);
        MemoryUtil.memPutFloat(ptr + 8L, roll);
        MemoryUtil.memPutFloat(ptr + 12L, 0f);
    }

    private static void clearArmorStandPose(long ptr) {
        final int[] offsets = {
                EntityInstance.OFFSET_ARMOR_STAND_HEAD_POSE,
                EntityInstance.OFFSET_ARMOR_STAND_BODY_POSE,
                EntityInstance.OFFSET_ARMOR_STAND_LEFT_ARM_POSE,
                EntityInstance.OFFSET_ARMOR_STAND_RIGHT_ARM_POSE,
                EntityInstance.OFFSET_ARMOR_STAND_LEFT_LEG_POSE,
                EntityInstance.OFFSET_ARMOR_STAND_RIGHT_LEG_POSE
        };

        for (int offset : offsets) {
            writeArmorStandPose(ptr + offset, 0f, 0f, 0f);
        }
    }

    /**
     * Maps an {@link ItemStack} to a stable int ID for the instance payload.
     *
     * <p>The ID is the raw {@link net.minecraft.resources.ResourceLocation} hash sequence
     * from {@link BuiltInRegistries#ITEM} — datapack-independent and stable within a
     * session. Empty stacks map to {@link EntityInstance#NO_ITEM} / {@link EntityInstance#NO_ARMOR}
     * so the absence of equipment keeps the existing sentinel semantics.
     *
     * <p>The GPU mesh pipeline currently bakes bare entity meshes and the shader does not
     * sample the item/armor fields, so these IDs are for future item-model rendering. We
     * deliberately fall back to the existing sentinel values instead of inventing arbitrary
     * render IDs.
     */
    private static boolean hasRenderableEquipment(LivingEntity living) {
        return !living.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()
                || !living.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()
                || !living.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                || !living.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                || !living.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                || !living.getItemBySlot(EquipmentSlot.FEET).isEmpty();
    }

    private static int itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EntityInstance.NO_ITEM;
        }
        return BuiltInRegistries.ITEM.getId(stack.getItem());
    }

    private static float lerpDegrees(float t, float from, float to) {
        return from + wrapDegrees(to - from) * t;
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
