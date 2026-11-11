#version 150
#moj_import <fog.glsl>
uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float GrayAmount;
in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    if (texel.a < 0.1) discard;
    vec4 lit = texel * vertexColor * ColorModulator;
    lit.rgb = mix(overlayColor.rgb, lit.rgb, overlayColor.a);
    lit *= lightMapColor;
    vec4 fogged = linear_fog(lit, vertexDistance, FogStart, FogEnd, FogColor);
    float luminance = dot(fogged.rgb, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(mix(fogged.rgb, vec3(luminance), GrayAmount), fogged.a);
}
