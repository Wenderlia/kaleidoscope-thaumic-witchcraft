#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float GameTime;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    // Lodestone 1.8.3.852 distorted_texture defaults; the second wave uses updated u.
    vec2 uv = texCoord0;
    float time = GameTime * 2000.0;
    uv.x += cos(uv.y * 5.0 + time) / 200.0;
    uv.y += sin(uv.x * 7.0 + time) / 200.0;
    vec4 color = texture(Sampler0, clamp(uv, 0.0, 1.0)) * vertexColor * ColorModulator;
    if (color.a == 0.0) discard;
    fragColor = color;
}
