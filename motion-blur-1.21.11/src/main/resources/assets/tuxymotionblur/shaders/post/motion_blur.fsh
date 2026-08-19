#version 330

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(std140) uniform BlurConfig {
    float BlendFactor;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 currentColor = texture(InSampler, texCoord);
    vec4 previousColor = texture(PrevSampler, texCoord);

    // Persistent render targets start transparent. Treat an empty history buffer
    // as the current frame so loading into a world never flashes dark.
    float historyValid = step(0.5, previousColor.a);
    float amount = clamp(BlendFactor * historyValid, 0.0, 0.97);

    fragColor = mix(currentColor, previousColor, amount);
    fragColor.a = 1.0;
}
