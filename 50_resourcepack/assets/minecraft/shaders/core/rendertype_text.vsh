#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    // BEGIN ASTRALRECORD QUEST HUD
#if 1
    // A negative-ascent provider puts only our glyphs below the ActionBar viewport.
    // Check orthographic projection as well: matching world/display colors stay vanilla.
    vec4 questScreen = ModelViewMat * vec4(Position, 1.0);
    float questHeight = 2.0 / abs(ProjMat[1][1]);
    ivec3 questCode = ivec3(round(Color.rgb * 255.0));
    int questType = questCode.r;
    // Text uses red for row count and palette, green for x, blue for row.
    // Shapes use red for kind, green for row count and row, blue for the shape variant.
    bool questText = questType >= 180 && questType < 220;
    bool questFrame = questType == 220;
    int questRows = questText ? (questType - 180) / 4 + 1
        : (questFrame ? questCode.g : questCode.g >> 4);
    int questRow = questText ? questCode.b : questCode.g & 15;
    bool questTextMarker = questText && questRow < questRows
        && questCode.g >= 8 && questCode.g < 180;
    bool questShapeMarker = questFrame ? questCode.b < 8 : questRow < questRows
        && ((questType == 221 && questCode.b == 0)
            || (questType >= 222 && questType <= 223 && (questCode.b & 31) <= 18)
            || (questType == 224 && questCode.b < 3)
            || (questType == 225 && (questCode.b & 31) <= 18));
    bool questProjection = abs(ProjMat[3][3] - 1.0) < 0.001 && abs(ProjMat[2][3]) < 0.001;
    if (questProjection && questRows >= 1 && questRows <= 10 && (questTextMarker || questShapeMarker)
            && questScreen.y > questHeight + 4000.0
            && questScreen.y < questHeight + 4120.0) {
        int questCorner = gl_VertexID & 3; // TL, BL, BR, TR in the vanilla glyph quad.
        vec2 questSize = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        float panelHeight = float(6 + 13 * questRows);
        float left = clamp(questSize.x - 172.0, 0.0, 8.0);
        // The profile is centered on the combat ActionBar and may extend to center - 154.
        // Include its highest optional class-points row and a four-pixel gap.
        float profileLeft = questSize.x * 0.5 - 154.0;
        float limit = questHeight - 4.0;
        if (profileLeft < left + 172.0) {
            limit = questHeight - 134.0;
        }
        float scale = clamp(min((questSize.x - left) / 172.0,
            (limit - 4.0) / panelHeight), 0.0, 1.0);
        float top = clamp(limit - panelHeight * scale, 4.0, 48.0);
        float rowY = top + float(3 + questRow * 13);
        vec2 origin;
        vec2 size;
        vec3 tint;
        if (questText) {
            int palette = (questType - 180) & 3;
            origin = vec2(left + float(questCode.g - 8), rowY);
            // Minecraft bakes the entire 18x74 cell, including transparent margins.
            size = vec2(9.0, 37.0);
            tint = palette == 0 ? vec3(1.00000000, 0.84313725, 0.54901961) : palette == 1 ? vec3(0.94901961, 0.95686275, 0.96470588)
                : palette == 2 ? vec3(0.65490196, 0.93333333, 0.86274510) : vec3(0.72549020, 0.75686275, 0.78823529);
        } else if (questFrame) {
            // Snap only the frame inside the scaled panel, preserving one pixel strokes.
            vec2 first = ceil(vec2(left, top) - 0.0001);
            vec2 last = floor(vec2(left + 172.0 * scale, top + panelHeight * scale) + 0.0001);
            int edge = questCode.b;
            if (edge < 2) {
                origin = vec2(first.x + 2.0, edge == 0 ? first.y : last.y - 1.0);
                size = vec2(last.x - first.x - 4.0, 1.0);
            } else if (edge < 4) {
                origin = vec2(edge == 2 ? first.x : last.x - 1.0, first.y + 2.0);
                size = vec2(1.0, last.y - first.y - 4.0);
            } else {
                origin = vec2((edge & 1) == 0 ? first.x + 1.0 : last.x - 2.0,
                    edge < 6 ? first.y + 1.0 : last.y - 2.0);
                size = vec2(1.0);
            }
            if (any(lessThan(last - first, vec2(5.0)))) {
                size = vec2(0.0);
            }
            tint = vec3(0.59215686, 0.55686275, 0.46274510);
        } else if (questType == 221) {
            origin = vec2(left + 5.0, rowY - 1.0);
            size = vec2(162.0, 1.0);
            tint = vec3(0.39215686, 0.41176471, 0.42745098);
        } else if (questType == 224) {
            origin = vec2(left + 6.0, rowY + 1.0);
            size = vec2(6.0, 9.0);
            tint = vec3(1.0);
        } else {
            float filled = float(questCode.b & 31);
            float shift = float(questCode.b >> 5) * 2.0;
            bool remaining = questType == 222;
            origin = vec2(left + 122.0 + shift + (remaining ? filled : 0.0), rowY + 6.0);
            size = vec2(remaining ? 18.0 - filled : filled, 1.0);
            tint = remaining ? vec3(0.45098039, 0.47843137, 0.51764706) : questType == 225 ? vec3(0.65490196, 0.93333333, 0.86274510) : vec3(1.00000000, 0.87843137, 0.65882353);
        }
        vec2 questPixel = origin + size * vec2(questCorner >= 2 ? 1.0 : 0.0,
            questCorner == 1 || questCorner == 2 ? 1.0 : 0.0);
        vec2 panelOrigin = vec2(left, top);
        if (!questFrame) {
            questPixel = panelOrigin + (questPixel - panelOrigin) * scale;
        }
        gl_Position.xy = (vec2(-1.0, 1.0) + questPixel / questSize * vec2(2.0, -2.0)) * gl_Position.w;
        float opacity = questFrame ? (questCode.b < 4 ? 104.0 : 164.0) / 255.0 : 1.0;
        vertexColor = vec4(tint, Color.a * opacity);
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;
    }
#endif
    // END ASTRALRECORD QUEST HUD
}
