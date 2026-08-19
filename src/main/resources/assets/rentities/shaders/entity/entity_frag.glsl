#version 460 core

in vec2      vTexCoord;
in flat int  vFlags;
in vec3      vNormal;
in float     vHurtAlpha;
in float     vGlintAnim;
in flat int  vPackedLight;
in flat int  vMaterialFlags;

out vec4 fragColor;

// Minecraft entity texture bound directly per draw call — no atlas
uniform sampler2D uEntityTexture;
uniform int uSlimeOverlay;

#define FLAG_IS_INVISIBLE 64
#define FLAG_HAS_GLINT    4

void main() {
    if ((vFlags & FLAG_IS_INVISIBLE) != 0) discard;

    vec4 tex = texture(uEntityTexture, vTexCoord);

    // Discard fully transparent pixels (entity textures have alpha cutouts)
    if (tex.a < 0.05) discard;

    float blockLight = float((vPackedLight >> 4) & 15) / 15.0;
    float skyLight = float((vPackedLight >> 20) & 15) / 15.0;
    // Use the packed Minecraft light level directly. The old artificial 0.08 floor
    // made dark areas visibly brighter than vanilla.
    float light = max(blockLight, skyLight);
    vec3 color = tex.rgb * light;

    // Hurt flash (red overlay)
    if (vHurtAlpha > 0.0) {
        color = mix(color, vec3(1.0, 0.0, 0.0), vHurtAlpha);
    }

    // Enchantment glint
    if ((vFlags & FLAG_HAS_GLINT) != 0) {
        float glint = sin(vTexCoord.x * 20.0 + vGlintAnim) * 0.5 + 0.5;
        color += vec3(0.5, 0.2, 0.8) * glint * 0.3;
    }

    float alpha = tex.a;
    if (uSlimeOverlay != 0 && (vMaterialFlags & FLAG_SLIME) != 0) {
        // Vanilla has a translucent outer slime shell around its opaque inner model.
        alpha *= 0.65;
    }
    fragColor = vec4(color, alpha);
}
