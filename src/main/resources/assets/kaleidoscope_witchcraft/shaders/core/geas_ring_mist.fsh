#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

float puff(vec2 uv, vec2 centre, vec2 size) {
    vec2 p = (uv - centre) / size + 0.5;
    return texture(Sampler0, clamp(p, 0.0, 1.0)).a;
}

void main() {
    // Paired low-contrast smoke and weaker bloom, without a star-like hot centre.
    // These smooth lobes use our existing glow; no Malum artwork is shipped.
    float smoke = 0.48 * puff(texCoord0, vec2(0.38, 0.48), vec2(0.76, 0.90))
                +0.35 * puff(texCoord0, vec2(0.59, 0.56), vec2(0.68, 0.72))
                +0.24 * puff(texCoord0, vec2(0.53, 0.34), vec2(0.52, 0.58));
    float bloom = 0.16 * texture(Sampler0, texCoord0).a;
    vec4 color = vertexColor * ColorModulator;
    color.a *= min(1.0, smoke + bloom);
    fragColor = color;
}
