#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float Rounded;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float v = texCoord0.y;
    float width = v;
    if (Rounded > 0.5 && v > 0.6) {
        float end = (v - 0.6) / 0.4;
        width *= 1.0 - end * end;
    }
    if (abs(texCoord0.x - 0.5) * 2.0 > width) discard;
    // Our existing glow's centre row supplies a broad concentration profile.
    // Keep the original U: remapping to the clipped width changes the reference's taper.
    vec4 color = texture(Sampler0, vec2(texCoord0.x, 0.5)) * vertexColor * ColorModulator;
    if (color.a == 0.0) discard;
    fragColor = color;
}
