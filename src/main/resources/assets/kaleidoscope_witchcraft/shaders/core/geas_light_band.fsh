#version 150
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float DensityPower;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    // Filled body and diffuse aureole have separate density profiles.
    // The native reference supplies layered emission, not opaque surface lighting.
    float end = smoothstep(0.0, 0.05, min(texCoord0.y, 1.0 - texCoord0.y));
    vec4 density = texture(Sampler0, vec2(texCoord0.x, 0.5));
    density.a = pow(density.a, DensityPower);
    vec4 color = density * vertexColor * ColorModulator;
    color.a *= end;
    if (color.a == 0.0) discard;
    fragColor = color;
}
