#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

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
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
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
            // The dedicated 36x36 atlas retains the original 9x9 logical ink cell.
            size = vec2(9.0, 9.0);
            tint = palette == 0 ? vec3(0.97647059, 0.82352941, 0.52156863) : palette == 1 ? vec3(0.96078431, 0.92549020, 0.84705882)
                : palette == 2 ? vec3(0.69411765, 0.89411765, 0.79215686) : vec3(0.74509804, 0.66666667, 0.54509804);
        } else if (questFrame) {
            // The existing eight frame quads supply the backing, four brass rails,
            // two glints and the first heading band without a new server marker.
            vec2 first = ceil(vec2(left, top) - 0.0001);
            vec2 last = floor(vec2(left + 172.0 * scale, top + panelHeight * scale) + 0.0001);
            vec2 span = last - first;
            int edge = questCode.b;
            tint = vec3(0.71764706, 0.56862745, 0.32941176);
            if (edge == 0) {
                origin = first + vec2(1.0, 1.0);
                size = span - vec2(2.0);
                tint = vec3(0.11372549, 0.09411765, 0.07058824);
            } else if (edge < 3) {
                origin = vec2(first.x + 1.0, edge == 1 ? first.y : last.y - 1.0);
                size = vec2(span.x - 2.0, 1.0);
            } else if (edge < 5) {
                origin = vec2(edge == 3 ? first.x : last.x - 1.0, first.y + 1.0);
                size = vec2(1.0, span.y - 2.0);
            } else if (edge < 7) {
                origin = vec2(edge == 5 ? first.x + 2.0 : last.x - 9.0, first.y + 1.0);
                size = vec2(7.0, 1.0);
                tint = vec3(0.94901961, 0.79215686, 0.50196078);
            } else {
                origin = first + vec2(2.0, 2.0);
                size = vec2(span.x - 4.0, max(1.0, floor(13.0 * scale) - 1.0));
                tint = vec3(0.28235294, 0.20000000, 0.12156863);
            }
            if (any(lessThan(span, vec2(20.0, 5.0)))) {
                size = vec2(0.0);
            }
        } else if (questType == 221) {
            origin = vec2(left + 2.0, rowY - 1.0);
            size = vec2(168.0, 12.0);
            tint = vec3(0.28235294, 0.20000000, 0.12156863);
        } else if (questType == 224) {
            origin = vec2(left + 6.0, rowY + 1.0);
            size = vec2(6.0, 9.0);
            tint = vec3(1.0);
        } else {
            float filled = float(questCode.b & 31);
            float shift = float(questCode.b >> 5) * 2.0;
            bool remaining = questType == 222;
            origin = vec2(left + 122.0 + shift + (remaining ? filled : 0.0), rowY + 6.0);
            size = vec2(remaining ? 18.0 - filled : filled, 2.0);
            tint = remaining ? vec3(0.36862745, 0.29411765, 0.20784314) : questType == 225 ? vec3(0.63137255, 0.89019608, 0.67450980) : vec3(0.92549020, 0.72941176, 0.38823529);
        }
        vec2 questPixel = origin + size * vec2(questCorner >= 2 ? 1.0 : 0.0,
            questCorner == 1 || questCorner == 2 ? 1.0 : 0.0);
        vec2 panelOrigin = vec2(left, top);
        if (!questFrame) {
            questPixel = panelOrigin + (questPixel - panelOrigin) * scale;
        }
        gl_Position.xy = (vec2(-1.0, 1.0) + questPixel / questSize * vec2(2.0, -2.0)) * gl_Position.w;
        float opacity = questFrame ? (questCode.b == 0 ? 132.0
            : questCode.b <= 4 ? 236.0
            : questCode.b <= 6 ? 255.0 : 112.0) / 255.0
            : questType == 221 ? 112.0 / 255.0 : 1.0;
        vertexColor = vec4(tint, Color.a * opacity);
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;
    }
#endif
    // END ASTRALRECORD QUEST HUD
    // BEGIN ASTRALRECORD LOCATION HUD V5
#if 1
    // Shares the Quest negative-ascent window with a disjoint RGB marker range.
    // Red encodes row-count pairs and palette, green is x.
    // Blue: row bits 0-4, shadow bit 5, performance palette bit 6, row-count parity bit 7.
    int locationRows = (questType / 8) * 2 + 1 + (questCode.b >> 7);
    int locationStyle = (questType & 7) + ((questCode.b & 64) >> 3);
    int locationRow = questCode.b & 31;
    bool locationShadow = (questCode.b & 32) != 0;
    bool locationIcon = locationStyle == 7;
    if (questProjection && questType < 128 && questCode.g < 172 && questCode.b < 256
            && locationStyle < 11 && locationRow < locationRows
            && !(locationShadow && locationIcon)
            && questScreen.y > questHeight + 4000.0 && questScreen.y < questHeight + 4120.0) {
        const ivec3 palette[11] = ivec3[11](
            ivec3(242, 244, 246),
            ivec3(178, 183, 181),
            ivec3(246, 216, 160),
            ivec3(216, 219, 217),
            ivec3(251, 174, 179),
            ivec3(215, 198, 248),
            ivec3(249, 230, 149),
            ivec3(255, 255, 255),
            ivec3(120, 237, 172),
            ivec3(255, 117, 125),
            ivec3(255, 224, 107));
        vec2 screen = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        float right = screen.x - 8.0;
        float height = float(12 * locationRows);
        float limit = questHeight - 4.0;
        // Keep the unchanged Quest geometry and its gap available on narrower screens.
        float leftLimit = 188.0;
        if (max(leftLimit, right - 172.0) < screen.x * 0.5 + 154.0) {
            limit = questHeight - 134.0;
        }
        float scale = clamp(min((right - leftLimit) / 172.0,
            (limit - 4.0) / height), 0.0, 1.0);
        vec2 windowOrigin = vec2(right - 172.0 * scale,
            clamp(limit - height * scale, 4.0, 48.0));
        vec2 origin = vec2(float(questCode.g), float(12 * locationRow));
        origin += locationShadow ? vec2(1.0) : vec2(0.0, locationIcon ? 1.0 : 0.0);
        // The buff bitmap has 10 ink pixels at the top of a 79-pixel cell.
        vec2 size = locationIcon ? vec2(10.0, 79.0) : vec2(9.0, 11.0);
        ivec3 tint = palette[locationStyle];
        int corner = gl_VertexID & 3;
        vec2 pixel = windowOrigin + (origin + size * vec2(corner >= 2 ? 1.0 : 0.0,
            corner == 1 || corner == 2 ? 1.0 : 0.0)) * scale;
        gl_Position.xy = (vec2(-1.0, 1.0) + pixel / screen * vec2(2.0, -2.0)) * gl_Position.w;
        vertexColor = vec4(vec3(locationShadow ? tint / 4 : tint) / 255.0, Color.a);
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;
    }
#endif
    // END ASTRALRECORD LOCATION HUD V5
    // BEGIN ASTRALRECORD ACTION RING COOLDOWN HUD
#if 1
    // Red=ring, green=actual slot, blue=plate or numeral column.
    // Keep the marker separate from Quest, Location and ordinary text.
    if (questProjection && questType == 226 && questCode.g >= 0 && questCode.g <= 8
            && questCode.b >= 0 && (questCode.g == 8 ? questCode.b == 0 : questCode.b <= 5)
            && questScreen.y > questHeight + 4000.0 && questScreen.y < questHeight + 4120.0) {
        vec2 viewportSize = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        float hotbarRight = viewportSize.x * 0.5 + 91.0;
        float profileRight = viewportSize.x * 0.5 + 154.0;
        float combatRight = profileRight + 8.0;
        float rightLimit = viewportSize.x - 4.0;
        // Bound the right-side Location HUD for any row count.
        float locationLeft = max(188.0, viewportSize.x - 180.0);
        float locationBottom = min(432.0,
            questHeight - (locationLeft < profileRight ? 134.0 : 4.0));
        bool wide = combatRight + 56.0 <= rightLimit
            && (combatRight + 56.0 <= locationLeft
                || questHeight - 76.0 >= locationBottom + 4.0);
        // The compact ring sits below the combat row and clears either main-hand setting.
        float left = wide ? combatRight : hotbarRight + 33.0;
        if (!wide && questHeight - 36.0 < locationBottom + 4.0) {
            rightLimit = min(rightLimit, locationLeft);
        }
        float width = wide ? 56.0 : clamp(rightLimit - left, 0.0, 32.0);
        float scale = width / 56.0;
        vec2 center = vec2(left + width * 0.5,
            questHeight - (wide ? 48.0 : width * 0.5 + 4.0));
        float ringTop = center.y - width * 0.5;
        bool fits = width >= 28.0 && ringTop >= 4.0;
        const vec2 offsets[8] = vec2[8](vec2(0.00000000, -1.00000000), vec2(0.70710678, -0.70710678), vec2(1.00000000, 0.00000000), vec2(0.70710678, 0.70710678), vec2(0.00000000, 1.00000000), vec2(-0.70710678, 0.70710678), vec2(-1.00000000, 0.00000000), vec2(-0.70710678, -0.70710678));
        float size = (questCode.g == 8 ? 56.0 : 14.0) * scale;
        if (questCode.g < 8) {
            center += offsets[questCode.g] * 21.0 * scale;
            float column = questCode.b == 2 ? -2.5 : questCode.b == 3 ? 2.5
                : questCode.b == 4 ? -4.0 : questCode.b == 5 ? 4.0 : 0.0;
            center.x += column * scale;
        }
        if (!fits) {
            size = 0.0; // Preserve the zero-advance protocol but never obscure existing HUDs.
        }
        int corner = gl_VertexID & 3;
        vec2 position = center + size * vec2(corner >= 2 ? 0.5 : -0.5,
            corner == 1 || corner == 2 ? 0.5 : -0.5);
        gl_Position.xy = (vec2(-1.0, 1.0) + position / viewportSize * vec2(2.0, -2.0)) * gl_Position.w;
        vertexColor = vec4(1.0, 1.0, 1.0, Color.a);
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;
    }
#endif
    // END ASTRALRECORD ACTION RING COOLDOWN HUD
}
