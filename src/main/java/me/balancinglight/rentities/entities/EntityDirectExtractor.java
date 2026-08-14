package me.balancinglight.rentities.entities;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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
 * weight for an instance the GPU animates from 40 floats.
 *
 * <p>Cancelling at {@code EntityRenderDispatcher.submit} — where the previous revision hooked
 * — is far too late: the state has already been created and populated. Hooking
 * {@code LevelRenderer.extractEntity} instead means the state is never built at all.
 * The hook still has to hand a non-null state back to the caller, which adds it to a list and
 * dereferences it, so a single reusable {@link #SENTINEL} is returned for every batched
 * entity in every frame: one object for the process lifetime instead of one per entity per
 * frame, and the dispatcher recognises it and cancels before doing any work.
 */
public final class EntityDirectExtractor {

    /**
     * Returned in place of a real render state. Never populated and never rendered; it exists
     * only so the vanilla caller has a non-null object to add to its list.
     */
    public static final EntityRenderState SENTINEL = new EntityRenderState();

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

        // The texture is resolved from a render state the first time an entity of this type is
        // seen, so until that has happened the type keeps taking the vanilla path. This costs
        // one extraction per type per session, not per entity per frame.
        if (!renderer.entityTextureLocs.containsKey(type)) return false;

        long ptr = EntityBatchRenderer.reserveInstance(type);
        if (ptr == 0L) return false;

        write(ptr, entity, type, partialTick);
        return true;
    }

    private static void write(long ptr, Entity entity, EntityType<?> type, float partialTick) {
        Vec3 pos = entity.getPosition(partialTick);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_X,
                (float) (pos.x - EntityBatchRenderer.cameraX));
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_Y,
                (float) (pos.y - EntityBatchRenderer.cameraY));
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_POSITION_Z,
                (float) (pos.z - EntityBatchRenderer.cameraZ));

        float bodyYawDeg;
        float limbSwing = 0f, limbSwingAmt = 0f;
        float headYawRel = 0f, headPitchRel = 0f;
        float attackProgress = 0f, swimProgress = 0f, sneakProgress = 0f;
        float hurtTime = 0f, deathTime = 0f;

        // Armour stands have no walk animation; feeding the biped animator live inputs makes
        // them sway, so their animation inputs stay pinned at zero exactly as on the
        // render-state path.
        if (entity instanceof LivingEntity living && type != EntityType.ARMOR_STAND) {
            bodyYawDeg = lerpDegrees(partialTick, living.yBodyRotO, living.yBodyRot);
            limbSwing = living.walkAnimation.position(partialTick);
            limbSwingAmt = Math.min(living.walkAnimation.speed(partialTick), 1.0f);
            headYawRel = (float) Math.toRadians(
                    wrapDegrees(lerpDegrees(partialTick, living.yHeadRotO, living.yHeadRot) - bodyYawDeg));
            headPitchRel = (float) Math.toRadians(living.getViewXRot(partialTick));
            attackProgress = living.getAttackAnim(partialTick);
            swimProgress = living.getSwimAmount(partialTick);
            if (living.hurtTime > 0) hurtTime = 10f;
            if (living.deathTime > 0) deathTime = Math.min(living.deathTime, 20f);
        } else {
            bodyYawDeg = entity.getViewYRot(partialTick);
        }
        if (entity.isCrouching()) sneakProgress = 1f;

        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_ROTATION_Y,
                (float) (Math.toRadians(bodyYawDeg) - Math.PI));
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_LIMB_SWING,      limbSwing);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_LIMB_SWING_AMT,  limbSwingAmt);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_HEAD_YAW,        headYawRel);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_HEAD_PITCH,      headPitchRel);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_ATTACK_PROGRESS, attackProgress);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_BOW_PULL,        0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_HURT_TIME,       hurtTime);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_DEATH_TIME,      deathTime);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SNEAK_PROGRESS,  sneakProgress);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SWIM_PROGRESS,   swimProgress);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_RIPTIDE,         0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SIT_PROGRESS,    0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_EAT_PROGRESS,    0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SWELL_AMOUNT,    0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_EXPLODE_PROGRESS,0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_ROLL_PROGRESS,   0f);

        int flags = 0;
        if (entity.isInvisible()) flags |= EntityInstance.FLAG_IS_INVISIBLE;
        if (entity.onGround())    flags |= EntityInstance.FLAG_ON_GROUND;
        if (entity.isInWater())   flags |= EntityInstance.FLAG_IS_IN_WATER;
        if (type == EntityType.PLAYER) flags |= EntityInstance.FLAG_IS_PLAYER;
        if (EntityBatchRegistry.hasZombieArms(type)) flags |= EntityInstance.FLAG_ZOMBIE_ARMS;
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_FLAGS, flags);

        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ENTITY_TYPE,
                EntityBatchRegistry.getEntityTypeIndex(type));
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ANIM_CATEGORY,
                EntityBatchRegistry.getCategory(type).glslId);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_TEXTURE_LAYER, 0);

        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_HELD_MAIN,       EntityInstance.NO_ITEM);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_HELD_OFFHAND,    EntityInstance.NO_ITEM);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_HEAD,      EntityInstance.NO_ARMOR);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_CHEST,     EntityInstance.NO_ARMOR);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_LEGS,      EntityInstance.NO_ARMOR);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_ARMOR_FEET,      EntityInstance.NO_ARMOR);
        MemoryUtil.memPutInt(ptr + EntityInstance.OFFSET_MOUNT_ID,        EntityInstance.NO_MOUNT);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SEAT_OFFSET_X, 0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SEAT_OFFSET_Y, 0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_SEAT_OFFSET_Z, 0f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_TEX_SCALE_X,   1f);
        MemoryUtil.memPutFloat(ptr + EntityInstance.OFFSET_TEX_SCALE_Y,   1f);
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
