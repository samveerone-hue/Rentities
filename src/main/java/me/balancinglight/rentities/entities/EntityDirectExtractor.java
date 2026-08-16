package me.balancinglight.rentities.entities;

import me.balancinglight.rentities.Rentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ArmorStandRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState;
import net.minecraft.core.Rotations;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
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
    public static final ArmorStandRenderState SENTINEL_ARMOR_STAND_STATE = null;

    /**
     * Kept as the normal dispatcher sentinel used by the existing mixin.
     */
    public static final net.minecraft.client.renderer.entity.state.EntityRenderState SENTINEL =
            new net.minecraft.client.renderer.entity.state.EntityRenderState();

    private EntityDirectExtractor() {}

    /**
     * Writes {@code entity} into the batch queue if it can be GPU-instanced.
     *
     * @return true if the entity was queued and vanilla extraction must be skipped
     */
    public static boolean tryExtract(Entity entity, float partialTick) {
        EntityBatchRenderer renderer = EntityBatchRenderer.INSTANCE;
        if (renderer == null || entity == null) return false;

        EntityType<?> type = entity.getType();
        if (EntityBatchRegistry.getCategory(type) == EntityAnimationCategory.CPU_ANIMATED) return false;
        if (!renderer.hasMeshFor(type)) return false;
        if (!renderer.entityTextureLocs.containsKey(type)) return false;

        long ptr = EntityBatchRenderer.reserveInstance(type);
        if (ptr == 0L) return false;

        write(ptr, entity, type, partialTick);
        return true;
    }

    private static void write(long ptr, Entity entity, EntityType<?> type, float partialTick) {
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
                                living.getViewXRot(partialTick));

                attackProgress = living.getAttackAnim(partialTick);
                swimProgress = living.getSwimAmount(partialTick);

                if (living.hurtTime > 0) {
                    hurtTime = 10f;
                }

                if (living.deathTime > 0) {
                    deathTime = Math.min(living.deathTime, 20f);
                }
            } else {
                bodyYawDeg = entity.getViewYRot(partialTick);
            }
        }

        if (!(entity instanceof ArmorStand) && entity.isCrouching()) {
            sneakProgress = 1f;
        }

        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_ROTATION_Y,
                (float) (Math.toRadians(bodyYawDeg) - Math.PI));

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
                0f);
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
                0f);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SIT_PROGRESS,
                0f);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_EAT_PROGRESS,
                0f);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_SWELL_AMOUNT,
                0f);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_EXPLODE_PROGRESS,
                0f);
        MemoryUtil.memPutFloat(
                ptr + EntityInstance.OFFSET_ROLL_PROGRESS,
                0f);

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

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_HELD_MAIN,
                EntityInstance.NO_ITEM);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_HELD_OFFHAND,
                EntityInstance.NO_ITEM);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_ARMOR_HEAD,
                EntityInstance.NO_ARMOR);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_ARMOR_CHEST,
                EntityInstance.NO_ARMOR);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_ARMOR_LEGS,
                EntityInstance.NO_ARMOR);

        MemoryUtil.memPutInt(
                ptr + EntityInstance.OFFSET_ARMOR_FEET,
                EntityInstance.NO_ARMOR);

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
        } catch (Throwable t) {
            if (Rentities.IS_DEBUG) {
                Rentities.LOGGER.warn(
                        "Failed to extract ArmorStandRenderState: {}",
                        t.getMessage());
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
