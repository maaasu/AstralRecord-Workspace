#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;
    // BEGIN ASTRALRECORD QUEST HUD
#if defined(IS_GUI)
    // A negative-ascent provider puts only our glyphs below the ActionBar viewport.
    // Check orthographic projection as well: matching world/display colors stay vanilla.
    vec4 questScreen = ModelViewMat * vec4(Position, 1.0);
    float questHeight = 2.0 / abs(ProjMat[1][1]);
    ivec3 questCode = ivec3(round(Color.rgb * 255.0));
    int questWidth = questCode.b & 15;
    bool questProjection = abs(ProjMat[3][3] - 1.0) < 0.001 && abs(ProjMat[2][3]) < 0.001;
    bool questMarker = (questCode.r == 252 || questCode.r == 253)
        && questCode.b >= 1 && questCode.b <= 25 && questWidth >= 1 && questWidth <= 9;
    if (questProjection && questMarker && questScreen.y > questHeight + 4000.0
            && questScreen.y < questHeight + 4120.0) {
        int questCorner = gl_VertexIndex & 3; // TL, BL, BR, TR in the vanilla glyph quad.
        float questX = float((questCode.r - 252) * 256 + questCode.g);
        float questY = 48.0 + float(questCode.b >> 4) * 13.0;
        vec2 questPixel = vec2(questX + (questCorner >= 2 ? float(questWidth) : 0.0),
            questY + (questCorner == 1 || questCorner == 2 ? 37.0 : 0.0));
        vec2 questSize = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        gl_Position.xy = (vec2(-1.0, 1.0) + questPixel / questSize * vec2(2.0, -2.0)) * gl_Position.w;
        vertexColor = vec4(questCode.b < 16 ? vec3(1.0, 0.82, 0.34) : vec3(1.0), Color.a);
    }
#endif
    // END ASTRALRECORD QUEST HUD
}
