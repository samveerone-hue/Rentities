#version 460 core
// Error vertex shader — renders unscanned entities as a glitchy magenta cube

layout(location = 0) in vec3 aPosition;

struct EntityInstance {
    float posX, posY, posZ;
    float rotationY;

    float limbSwing;
    float limbSwingAmount;
    float headYaw;
    float headPitch;
    float attackProgress;
    float bowPullProgress;
    float hurtTime;
    float deathTime;
    float sneakProgress;
    float swimProgress;

    int   flags;
    float riptideProgress;
    float sitProgress;
    float eatProgress;
    float swellAmount;
    float explodeProgress;
    float rollProgress;

    int   entityTypeIndex;
    int   animationCategory;
    int   textureArrayLayer;

    int   heldItemMain;
    int   heldItemOffhand;
    int   armorHead;
    int   armorChest;
    int   armorLegs;
    int   armorFeet;

    int   mountEntityID;

    float seatOffsetX;
    float seatOffsetY;
    float seatOffsetZ;

    float texScaleX;
    float texScaleY;

    /*
     * Existing head pivot slot.
     */
    float headPivotX;
    float headPivotY;
    float headPivotZ;
    float padding4;

    /*
     * Armor-stand pose slots.
     *
     * xyz = pitch, yaw, roll in radians
     * w   = padding
     */
    vec4 armorStandHeadPose;
    vec4 armorStandBodyPose;
    vec4 armorStandLeftArmPose;
    vec4 armorStandRightArmPose;
    vec4 armorStandLeftLegPose;
    vec4 armorStandRightLegPose;

    int packedLight;
    float slimeScaleXZ;
    float slimeScaleY;
    int materialFlags;
};
layout(std430, binding = 12) buffer EntityInstanceBuffer { EntityInstance instances[]; };

uniform mat4  uViewProjection;
uniform float uGameTime;
uniform int   uBaseInstance;

out vec3 vLocalPos;
out float vTime;

void main() {
    int idx = gl_InstanceID + uBaseInstance;
    EntityInstance inst = instances[idx];

    // Animate: bob up/down + slow spin to make it obvious
    float t   = uGameTime * 0.05;
    float bob = sin(t * 3.0) * 0.15;
    float spinY = t * 2.0;
    float sc = cos(spinY), ss = sin(spinY);

    // Error cube is 0.6 blocks wide centred at entity origin
    vec3 p = aPosition * 0.6;
    // Apply spin
    vec3 rotP = vec3(p.x*sc - p.z*ss, p.y + bob, p.x*ss + p.z*sc);

    vec4 world = vec4(rotP, 1.0);
    world.x += inst.posX;
    world.y += inst.posY + 0.9; // lift to roughly entity chest height
    world.z += inst.posZ;

    gl_Position = uViewProjection * world;
    vLocalPos   = aPosition;
    vTime       = uGameTime;
}

