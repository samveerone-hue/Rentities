package me.balancinglight.rentities.entities;

/** SSBO layout; STRIDE must match entity_vert.glsl and entity_cull.comp. */
public final class EntityInstance {

    /*
     * 160 bytes of existing state
     * + 6 x 16-byte armor-stand pose slots
     * = 256 bytes.
     */
    public static final int STRIDE = 256;

    // Byte offsets
    public static final int OFFSET_POSITION_X       = 0;
    public static final int OFFSET_POSITION_Y       = 4;
    public static final int OFFSET_POSITION_Z       = 8;
    public static final int OFFSET_ROTATION_Y       = 12;

    public static final int OFFSET_LIMB_SWING       = 16;
    public static final int OFFSET_LIMB_SWING_AMT   = 20;
    public static final int OFFSET_HEAD_YAW         = 24;
    public static final int OFFSET_HEAD_PITCH       = 28;
    public static final int OFFSET_ATTACK_PROGRESS  = 32;
    public static final int OFFSET_BOW_PULL         = 36;
    public static final int OFFSET_HURT_TIME        = 40;
    public static final int OFFSET_DEATH_TIME       = 44;
    public static final int OFFSET_SNEAK_PROGRESS   = 48;
    public static final int OFFSET_SWIM_PROGRESS    = 52;

    public static final int OFFSET_FLAGS            = 56;
    public static final int OFFSET_RIPTIDE          = 60;
    public static final int OFFSET_SIT_PROGRESS     = 64;
    public static final int OFFSET_EAT_PROGRESS     = 68;
    public static final int OFFSET_SWELL_AMOUNT     = 72;
    public static final int OFFSET_EXPLODE_PROGRESS = 76;
    public static final int OFFSET_ROLL_PROGRESS    = 80;

    public static final int OFFSET_ENTITY_TYPE      = 84;
    public static final int OFFSET_ANIM_CATEGORY    = 88;
    public static final int OFFSET_TEXTURE_LAYER    = 92;

    public static final int OFFSET_HELD_MAIN        = 96;
    public static final int OFFSET_HELD_OFFHAND     = 100;
    public static final int OFFSET_ARMOR_HEAD       = 104;
    public static final int OFFSET_ARMOR_CHEST      = 108;
    public static final int OFFSET_ARMOR_LEGS       = 112;
    public static final int OFFSET_ARMOR_FEET       = 116;

    public static final int OFFSET_MOUNT_ID         = 120;
    public static final int OFFSET_SEAT_OFFSET_X    = 124;
    public static final int OFFSET_SEAT_OFFSET_Y    = 128;
    public static final int OFFSET_SEAT_OFFSET_Z    = 132;

    public static final int OFFSET_TEX_SCALE_X      = 136;
    public static final int OFFSET_TEX_SCALE_Y      = 140;

    /*
     * Existing 16-byte head-pivot slot.
     *
     * The shader reads this as:
     *   headPivotX, headPivotY, headPivotZ, padding4
     */
    public static final int OFFSET_HEAD_PIVOT        = 144;
    public static final int OFFSET_HEAD_PIVOT_X      = 144;
    public static final int OFFSET_HEAD_PIVOT_Y      = 148;
    public static final int OFFSET_HEAD_PIVOT_Z      = 152;

    /*
     * Armor-stand rotations.
     *
     * Each slot is four floats:
     *   x = pitch, y = yaw, z = roll, w = padding
     *
     * All three rotation components are already converted to radians by
     * EntityDirectExtractor.
     */
    public static final int OFFSET_ARMOR_STAND_HEAD_POSE       = 160;
    public static final int OFFSET_ARMOR_STAND_BODY_POSE       = 176;
    public static final int OFFSET_ARMOR_STAND_LEFT_ARM_POSE   = 192;
    public static final int OFFSET_ARMOR_STAND_RIGHT_ARM_POSE  = 208;
    public static final int OFFSET_ARMOR_STAND_LEFT_LEG_POSE   = 224;
    public static final int OFFSET_ARMOR_STAND_RIGHT_LEG_POSE  = 240;

    // Flag bits
    public static final int FLAG_IS_BLOCKING  = 1;
    public static final int FLAG_IS_GLIDING   = 2;
    public static final int FLAG_HAS_GLINT    = 4;
    public static final int FLAG_IS_IN_WATER  = 8;
    public static final int FLAG_IS_ALEX      = 16;
    public static final int FLAG_IS_PLAYER    = 32;
    public static final int FLAG_IS_INVISIBLE = 64;
    public static final int FLAG_ON_GROUND    = 128;
    public static final int FLAG_ZOMBIE_ARMS  = 256;

    /**
     * Entity is an ArmorStand and must use the six explicit armor-stand pose slots
     * instead of the generic biped animation.
     */
    public static final int FLAG_ARMOR_STAND = 512;

    // Sentinel values
    public static final int NO_MOUNT = -1;
    public static final int NO_ITEM  = -1;
    public static final int NO_ARMOR = -1;

    // Max instances in SSBO
    public static final int MAX_INSTANCES = 50_000;
    public static final long SSBO_SIZE = (long) MAX_INSTANCES * STRIDE;

    private EntityInstance() {}
}
