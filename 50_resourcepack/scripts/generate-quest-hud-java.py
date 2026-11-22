#!/usr/bin/env python3
"""Generate/check the Java quest and location HUDs from pinned vanilla shaders.

Generation: --clients-dir DIR containing SHA-1-verified 1.21.11/26.1/26.2/26.3.jar.
Checking and the static preview need the committed pack assets and Pillow.
This is a resource-pack preview, never a Minecraft client screenshot.
"""
import argparse
import hashlib
import io
import io
import json
import math
from pathlib import Path
import re
import struct
import sys
import zipfile

PACK = Path(__file__).resolve().parents[1]
ROOT = PACK.parent
VARIANTS = [
    ('1.21.11', 'ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd', '0d0fbb2b68614be09056a25cecfbf068ab05b947003c9efa3bb3ad813d544dbd', '', 'rendertype_text'),
    ('26.1', '191771837687b766537a8c4607cb6fad79c533a1', '668142b13a06c3c2f731b7083715c0d11f11e44083bea933c0937b70c2daf6ff', 'quest_hud_84/', 'rendertype_text'),
    ('26.2', '2dc72797acbc1b63fc16a11c4ac393605f453754', '4bae202567d0e3020ab948c24dcd8acffaa23460622dc01f8cbc929301e062e5', 'quest_hud_88/', 'text'),
    ('26.3', 'e877b6a07acd633fb3bb475002175cec036e7b87', 'c5aab3569c07200a3d24c40f6ed2a5716b6694acd015b81e763398b7ff3ddc1f', 'quest_hud_97/', 'text'),
]
START = '    // BEGIN ASTRALRECORD QUEST HUD\n'
END = '    // END ASTRALRECORD QUEST HUD\n'
LOCATION_START = '    // BEGIN ASTRALRECORD LOCATION HUD V5\n'
LOCATION_END = '    // END ASTRALRECORD LOCATION HUD V5\n'
RING_START = '    // BEGIN ASTRALRECORD ACTION RING COOLDOWN HUD\n'
RING_END = '    // END ASTRALRECORD ACTION RING COOLDOWN HUD\n'
RING_MARKER = 226
RING_RADIUS = 21
RING_SLOT = 14
RING_SIZE = RING_RADIUS * 2 + RING_SLOT
RING_HOTBAR_HALF = 91
RING_OFFHAND_WIDTH = 30
RING_HUD_GAP = 8
RING_COMPACT_SIZE = 32
RING_WIDE_CENTER_FROM_BOTTOM = 48
RING_SLOT_OFFSETS = ((0, -1), (2**-.5, -2**-.5), (1, 0), (2**-.5, 2**-.5),
                     (0, 1), (-2**-.5, 2**-.5), (-1, 0), (-2**-.5, -2**-.5))
WIDTH = 172
ROW_HEIGHT = 13
PADDING = 3
MAX_ROWS = 10
LEFT = 8
TOP = 48
EDGE_MARGIN = 4
PROFILE_HALF_WIDTH = 154
PROFILE_BOTTOM_BAND = 134
PANEL_ALPHA = 132
BRASS_ALPHA = 236
HEADING_ALPHA = 112
ACCENT_ALPHA = 255
GUI_SIZES = ((320, 180), (320, 240), (426, 240), (640, 360), (960, 540), (1920, 1080))
BAR_WIDTH = 18
BAR_LEFT = LEFT + 122
TEXT_COLORS = ((249, 210, 133), (245, 236, 216), (177, 228, 202), (190, 170, 139))
SHAPE_COLORS = ((183, 145, 84), (72, 51, 31), (94, 75, 53), (236, 186, 99), (161, 227, 172))
PANEL_COLOR = (29, 24, 18)
ACCENT_COLOR = (242, 202, 128)
# Keep these indices aligned with LocationHudGlyphs.Palette.
LOCATION_TEXT_COLORS = ((242, 244, 246), (178, 183, 181), (246, 216, 160), (216, 219, 217),
                        (251, 174, 179), (215, 198, 248), (249, 230, 149), (255, 255, 255),
                        (120, 237, 172), (255, 117, 125), (255, 224, 107))
LOCATION_MAX_ROWS = 32
LOCATION_ROW_HEIGHT = 12
LOCATION_TEXT_WIDTH = 9
LOCATION_TEXT_HEIGHT = 11
HUD_GAP = 8
ASSETS = PACK / 'assets/astralrecord'
PIXEL_TEXTURE = ASSETS / 'textures/font/quest_hud_pixel.png'
HD_TEXTURE = ASSETS / 'textures/font/quest_hud_text_hd.png'
HD_FONT_SHA256 = 'faa5f3656a78b2e2d450d27fe8382c778bc2b6bb5ea29c986664a6a435056ceb'
HD_PIXEL_SHA256 = 'e0aac050153cc047448708e1999627c3299b8c1f8fbadcf6234c39b75cb01893'
HD_ICONS_PIXEL_SHA256 = 'a3eaba80a30dfd2a3b6f2a99b6c2a31b4b9588d45c2f93143cdbb0b8017b9868'


def shader_color(color):
    return 'vec3(' + ', '.join(f'{component / 255:.8f}' for component in color) + ')'


def injection(version):
    guard = '#if defined(IS_GUI)' if version in ('26.2', '26.3') else '#if 1'
    vertex = 'gl_VertexIndex' if version == '26.3' else 'gl_VertexID'
    fog = '' if version in ('26.2', '26.3') else '        sphericalVertexDistance = 0.0;\n        cylindricalVertexDistance = 0.0;\n'
    title, objective, complete, overflow = map(shader_color, TEXT_COLORS)
    frame, divider, track, fill, complete_fill = map(shader_color, SHAPE_COLORS)
    return START + f'''{guard}
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
        && questCode.g >= {LEFT} && questCode.g < {LEFT + WIDTH};
    bool questShapeMarker = questFrame ? questCode.b < 8 : questRow < questRows
        && ((questType == 221 && questCode.b == 0)
            || (questType >= 222 && questType <= 223 && (questCode.b & 31) <= {BAR_WIDTH})
            || (questType == 224 && questCode.b < 3)
            || (questType == 225 && (questCode.b & 31) <= {BAR_WIDTH}));
    bool questProjection = abs(ProjMat[3][3] - 1.0) < 0.001 && abs(ProjMat[2][3]) < 0.001;
    if (questProjection && questRows >= 1 && questRows <= {MAX_ROWS} && (questTextMarker || questShapeMarker)
            && questScreen.y > questHeight + 4000.0
            && questScreen.y < questHeight + 4120.0) {{
        int questCorner = {vertex} & 3; // TL, BL, BR, TR in the vanilla glyph quad.
        vec2 questSize = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        float panelHeight = float({PADDING * 2} + {ROW_HEIGHT} * questRows);
        float left = clamp(questSize.x - {WIDTH}.0, 0.0, {LEFT}.0);
        // The profile is centered on the combat ActionBar and may extend to center - 154.
        // Include its highest optional class-points row and a four-pixel gap.
        float profileLeft = questSize.x * 0.5 - {PROFILE_HALF_WIDTH}.0;
        float limit = questHeight - {EDGE_MARGIN}.0;
        if (profileLeft < left + {WIDTH}.0) {{
            limit = questHeight - {PROFILE_BOTTOM_BAND}.0;
        }}
        float scale = clamp(min((questSize.x - left) / {WIDTH}.0,
            (limit - {EDGE_MARGIN}.0) / panelHeight), 0.0, 1.0);
        float top = clamp(limit - panelHeight * scale, {EDGE_MARGIN}.0, {TOP}.0);
        float rowY = top + float({PADDING} + questRow * {ROW_HEIGHT});
        vec2 origin;
        vec2 size;
        vec3 tint;
        if (questText) {{
            int palette = (questType - 180) & 3;
            origin = vec2(left + float(questCode.g - {LEFT}), rowY);
            // The dedicated 36x36 atlas retains the original 9x9 logical ink cell.
            size = vec2(9.0, 9.0);
            tint = palette == 0 ? {title} : palette == 1 ? {objective}
                : palette == 2 ? {complete} : {overflow};
        }} else if (questFrame) {{
            // The existing eight frame quads supply the backing, four brass rails,
            // two glints and the first heading band without a new server marker.
            vec2 first = ceil(vec2(left, top) - 0.0001);
            vec2 last = floor(vec2(left + {WIDTH}.0 * scale, top + panelHeight * scale) + 0.0001);
            vec2 span = last - first;
            int edge = questCode.b;
            tint = {frame};
            if (edge == 0) {{
                origin = first + vec2(1.0, 1.0);
                size = span - vec2(2.0);
                tint = {shader_color(PANEL_COLOR)};
            }} else if (edge < 3) {{
                origin = vec2(first.x + 1.0, edge == 1 ? first.y : last.y - 1.0);
                size = vec2(span.x - 2.0, 1.0);
            }} else if (edge < 5) {{
                origin = vec2(edge == 3 ? first.x : last.x - 1.0, first.y + 1.0);
                size = vec2(1.0, span.y - 2.0);
            }} else if (edge < 7) {{
                origin = vec2(edge == 5 ? first.x + 2.0 : last.x - 9.0, first.y + 1.0);
                size = vec2(7.0, 1.0);
                tint = {shader_color(ACCENT_COLOR)};
            }} else {{
                origin = first + vec2(2.0, 2.0);
                size = vec2(span.x - 4.0, max(1.0, floor({ROW_HEIGHT}.0 * scale) - 1.0));
                tint = {divider};
            }}
            if (any(lessThan(span, vec2(20.0, 5.0)))) {{
                size = vec2(0.0);
            }}
        }} else if (questType == 221) {{
            origin = vec2(left + 2.0, rowY - 1.0);
            size = vec2({WIDTH - 4}.0, {ROW_HEIGHT - 1}.0);
            tint = {divider};
        }} else if (questType == 224) {{
            origin = vec2(left + 6.0, rowY + 1.0);
            size = vec2(6.0, 9.0);
            tint = vec3(1.0);
        }} else {{
            float filled = float(questCode.b & 31);
            float shift = float(questCode.b >> 5) * 2.0;
            bool remaining = questType == 222;
            origin = vec2(left + {BAR_LEFT - LEFT}.0 + shift + (remaining ? filled : 0.0), rowY + 6.0);
            size = vec2(remaining ? {BAR_WIDTH}.0 - filled : filled, 2.0);
            tint = remaining ? {track} : questType == 225 ? {complete_fill} : {fill};
        }}
        vec2 questPixel = origin + size * vec2(questCorner >= 2 ? 1.0 : 0.0,
            questCorner == 1 || questCorner == 2 ? 1.0 : 0.0);
        vec2 panelOrigin = vec2(left, top);
        if (!questFrame) {{
            questPixel = panelOrigin + (questPixel - panelOrigin) * scale;
        }}
        gl_Position.xy = (vec2(-1.0, 1.0) + questPixel / questSize * vec2(2.0, -2.0)) * gl_Position.w;
        float opacity = questFrame ? (questCode.b == 0 ? {PANEL_ALPHA}.0
            : questCode.b <= 4 ? {BRASS_ALPHA}.0
            : questCode.b <= 6 ? {ACCENT_ALPHA}.0 : {HEADING_ALPHA}.0) / 255.0
            : questType == 221 ? {HEADING_ALPHA}.0 / 255.0 : 1.0;
        vertexColor = vec4(tint, Color.a * opacity);
{fog}    }}
#endif
''' + END


def location_injection(version):
    guard = '#if defined(IS_GUI)' if version in ('26.2', '26.3') else '#if 1'
    vertex = 'gl_VertexIndex' if version == '26.3' else 'gl_VertexID'
    fog = '' if version in ('26.2', '26.3') else '        sphericalVertexDistance = 0.0;\n        cylindricalVertexDistance = 0.0;\n'
    palette = ',\n            '.join(f'ivec3({r}, {g}, {b})' for r, g, b in LOCATION_TEXT_COLORS)
    return LOCATION_START + f'''{guard}
    // Shares the Quest negative-ascent window with a disjoint RGB marker range.
    // Red encodes row-count pairs and palette, green is x.
    // Blue: row bits 0-4, shadow bit 5, performance palette bit 6, row-count parity bit 7.
    int locationRows = (questType / 8) * 2 + 1 + (questCode.b >> 7);
    int locationStyle = (questType & 7) + ((questCode.b & 64) >> 3);
    int locationRow = questCode.b & 31;
    bool locationShadow = (questCode.b & 32) != 0;
    bool locationIcon = locationStyle == 7;
    if (questProjection && questType < 128 && questCode.g < {WIDTH} && questCode.b < 256
            && locationStyle < {len(LOCATION_TEXT_COLORS)} && locationRow < locationRows
            && !(locationShadow && locationIcon)
            && questScreen.y > questHeight + 4000.0 && questScreen.y < questHeight + 4120.0) {{
        const ivec3 palette[{len(LOCATION_TEXT_COLORS)}] = ivec3[{len(LOCATION_TEXT_COLORS)}](
            {palette});
        vec2 screen = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        float right = screen.x - {LEFT}.0;
        float height = float({LOCATION_ROW_HEIGHT} * locationRows);
        float limit = questHeight - {EDGE_MARGIN}.0;
        // Keep the unchanged Quest geometry and its gap available on narrower screens.
        float leftLimit = {LEFT + WIDTH + HUD_GAP}.0;
        if (max(leftLimit, right - {WIDTH}.0) < screen.x * 0.5 + {PROFILE_HALF_WIDTH}.0) {{
            limit = questHeight - {PROFILE_BOTTOM_BAND}.0;
        }}
        float scale = clamp(min((right - leftLimit) / {WIDTH}.0,
            (limit - {EDGE_MARGIN}.0) / height), 0.0, 1.0);
        vec2 windowOrigin = vec2(right - {WIDTH}.0 * scale,
            clamp(limit - height * scale, {EDGE_MARGIN}.0, {TOP}.0));
        vec2 origin = vec2(float(questCode.g), float({LOCATION_ROW_HEIGHT} * locationRow));
        origin += locationShadow ? vec2(1.0) : vec2(0.0, locationIcon ? 1.0 : 0.0);
        // The buff bitmap has 10 ink pixels at the top of a 79-pixel cell.
        vec2 size = locationIcon ? vec2(10.0, 79.0) : vec2({LOCATION_TEXT_WIDTH}.0, {LOCATION_TEXT_HEIGHT}.0);
        ivec3 tint = palette[locationStyle];
        int corner = {vertex} & 3;
        vec2 pixel = windowOrigin + (origin + size * vec2(corner >= 2 ? 1.0 : 0.0,
            corner == 1 || corner == 2 ? 1.0 : 0.0)) * scale;
        gl_Position.xy = (vec2(-1.0, 1.0) + pixel / screen * vec2(2.0, -2.0)) * gl_Position.w;
        vertexColor = vec4(vec3(locationShadow ? tint / 4 : tint) / 255.0, Color.a);
{fog}    }}
#endif
''' + LOCATION_END


def ring_injection(version):
    guard = '#if defined(IS_GUI)' if version in ('26.2', '26.3') else '#if 1'
    vertex = 'gl_VertexIndex' if version == '26.3' else 'gl_VertexID'
    fog = '' if version in ('26.2', '26.3') else '        sphericalVertexDistance = 0.0;\n        cylindricalVertexDistance = 0.0;\n'
    offsets = ', '.join(f'vec2({x:.8f}, {y:.8f})' for x, y in RING_SLOT_OFFSETS)
    return RING_START + f'''{guard}
    // Red=ring, green=actual slot, blue=plate or numeral column.
    // Keep the marker separate from Quest, Location and ordinary text.
    if (questProjection && questType == {RING_MARKER} && questCode.g >= 0 && questCode.g <= 8
            && questCode.b >= 0 && (questCode.g == 8 ? questCode.b == 0 : questCode.b <= 5)
            && questScreen.y > questHeight + 4000.0 && questScreen.y < questHeight + 4120.0) {{
        vec2 viewportSize = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        float hotbarRight = viewportSize.x * 0.5 + {RING_HOTBAR_HALF}.0;
        float profileRight = viewportSize.x * 0.5 + {PROFILE_HALF_WIDTH}.0;
        float combatRight = profileRight + {RING_HUD_GAP}.0;
        float rightLimit = viewportSize.x - {EDGE_MARGIN}.0;
        // Bound the right-side Location HUD for any row count.
        float locationLeft = max({LEFT + WIDTH + HUD_GAP}.0, viewportSize.x - {LEFT + WIDTH}.0);
        float locationBottom = min({TOP + LOCATION_ROW_HEIGHT * LOCATION_MAX_ROWS}.0,
            questHeight - (locationLeft < profileRight ? {PROFILE_BOTTOM_BAND}.0 : {EDGE_MARGIN}.0));
        bool wide = combatRight + {RING_SIZE}.0 <= rightLimit
            && (combatRight + {RING_SIZE}.0 <= locationLeft
                || questHeight - {RING_WIDE_CENTER_FROM_BOTTOM + RING_SIZE / 2:.1f} >= locationBottom + {EDGE_MARGIN}.0);
        // The compact ring sits below the combat row and clears either main-hand setting.
        float left = wide ? combatRight : hotbarRight + {RING_OFFHAND_WIDTH + 3}.0;
        if (!wide && questHeight - {RING_COMPACT_SIZE + EDGE_MARGIN}.0 < locationBottom + {EDGE_MARGIN}.0) {{
            rightLimit = min(rightLimit, locationLeft);
        }}
        float width = wide ? {RING_SIZE}.0 : clamp(rightLimit - left, 0.0, {RING_COMPACT_SIZE}.0);
        float scale = width / {RING_SIZE}.0;
        vec2 center = vec2(left + width * 0.5,
            questHeight - (wide ? {RING_WIDE_CENTER_FROM_BOTTOM}.0 : width * 0.5 + {EDGE_MARGIN}.0));
        float ringTop = center.y - width * 0.5;
        bool fits = width >= 28.0 && ringTop >= {EDGE_MARGIN}.0;
        const vec2 offsets[8] = vec2[8]({offsets});
        float size = (questCode.g == 8 ? {RING_SIZE}.0 : {RING_SLOT}.0) * scale;
        if (questCode.g < 8) {{
            center += offsets[questCode.g] * {RING_RADIUS}.0 * scale;
            float column = questCode.b == 2 ? -2.5 : questCode.b == 3 ? 2.5
                : questCode.b == 4 ? -4.0 : questCode.b == 5 ? 4.0 : 0.0;
            center.x += column * scale;
        }}
        if (!fits) {{
            size = 0.0; // Preserve the zero-advance protocol but never obscure existing HUDs.
        }}
        int corner = {vertex} & 3;
        vec2 position = center + size * vec2(corner >= 2 ? 0.5 : -0.5,
            corner == 1 || corner == 2 ? 0.5 : -0.5);
        gl_Position.xy = (vec2(-1.0, 1.0) + position / viewportSize * vec2(2.0, -2.0)) * gl_Position.w;
        vertexColor = vec4(1.0, 1.0, 1.0, Color.a);
{fog}    }}
#endif
''' + RING_END


def ring_geometry(marker, width, height):
    red, slot, layer = marker
    if red != RING_MARKER or not 0 <= slot <= 8 or not 0 <= layer <= (0 if slot == 8 else 5):
        return None
    profile_right = width * .5 + PROFILE_HALF_WIDTH
    combat_right = profile_right + RING_HUD_GAP
    right_limit = width - EDGE_MARGIN
    location_left = max(LEFT + WIDTH + HUD_GAP, width - LEFT - WIDTH)
    location_bottom = min(TOP + LOCATION_ROW_HEIGHT * LOCATION_MAX_ROWS,
        height - (PROFILE_BOTTOM_BAND if location_left < profile_right else EDGE_MARGIN))
    wide_right = combat_right + RING_SIZE
    wide_top = height - RING_WIDE_CENTER_FROM_BOTTOM - RING_SIZE * .5
    wide = wide_right <= right_limit and (wide_right <= location_left or wide_top >= location_bottom + EDGE_MARGIN)
    left = combat_right if wide else width * .5 + RING_HOTBAR_HALF + RING_OFFHAND_WIDTH + 3
    if not wide and height - RING_COMPACT_SIZE - EDGE_MARGIN < location_bottom + EDGE_MARGIN:
        right_limit = min(right_limit, location_left)
    size = RING_SIZE if wide else max(0, min(right_limit - left, RING_COMPACT_SIZE))
    scale = size / RING_SIZE
    cx, cy = left + size * .5, height - (RING_WIDE_CENTER_FROM_BOTTOM if wide else size * .5 + EDGE_MARGIN)
    ring_top = cy - size * .5
    fits = size >= 28 and ring_top >= EDGE_MARGIN
    if slot != 8:
        dx, dy = RING_SLOT_OFFSETS[slot]
        cx += (dx * RING_RADIUS + (0, 0, -2.5, 2.5, -4, 4)[layer]) * scale
        cy += dy * RING_RADIUS * scale
        size = RING_SLOT * scale
    if not fits:
        size = 0
    return (cx - size * .5, cy - size * .5, size, size), (255, 255, 255, 255)


def verify_ring_geometry():
    for width, height in GUI_SIZES + ((312, 180), (443, 250), (444, 250), (667, 180),
                                     (668, 180), (720, 180), (720, 540), (796, 180)):
        ring, _ = ring_geometry((RING_MARKER, 8, 0), width, height)
        left, top, size, _ = ring
        assert size >= 28 and top >= EDGE_MARGIN and left + size <= width - EDGE_MARGIN
        assert left >= width * .5 + RING_HOTBAR_HALF + RING_OFFHAND_WIDTH
        if math.isclose(size, RING_SIZE):
            assert left == width * .5 + PROFILE_HALF_WIDTH + RING_HUD_GAP
        else:
            assert size <= RING_COMPACT_SIZE and math.isclose(top + size, height - EDGE_MARGIN)
        for rows in range(1, LOCATION_MAX_ROWS + 1):
            lx, ly, lscale = location_placement(width, height, rows)
            assert left + size <= lx + 1e-8 or top >= ly + LOCATION_ROW_HEIGHT * rows * lscale
        for slot in range(8):
            base, _ = ring_geometry((RING_MARKER, slot, 0), width, height)
            digit_l, _ = ring_geometry((RING_MARKER, slot, 2), width, height)
            digit_r, _ = ring_geometry((RING_MARKER, slot, 3), width, height)
            triple_l, _ = ring_geometry((RING_MARKER, slot, 4), width, height)
            triple_r, _ = ring_geometry((RING_MARKER, slot, 5), width, height)
            assert triple_l[0] < digit_l[0] < base[0] < digit_r[0] < triple_r[0]
            assert triple_l[1] == digit_l[1] == base[1] == digit_r[1] == triple_r[1]
            # Three 3px digit faces occupy 11px of a 14px plate before scaling.
            scale = base[2] / RING_SLOT
            assert math.isclose(triple_r[0] - triple_l[0], 8 * scale)
            assert 8 + 3 < RING_SLOT
    for width, height in ((300, 180), (311, 180), (640, 60)):
        assert ring_geometry((RING_MARKER, 8, 0), width, height)[0][2] == 0
    assert ring_geometry((RING_MARKER, 8, 1), 640, 360) is None
    assert ring_geometry((RING_MARKER, 9, 0), 640, 360) is None
    print('Action ring: right-side placement, offhand clearance, text offsets and Location separation passed.')


def generated_fonts():
    font = json.loads((PACK / 'assets/astralrecord/font/profile_hud_class_name_compact.json').read_text())
    for provider in font['providers']:
        if provider['type'] == 'bitmap':
            provider.update(file='astralrecord:font/quest_hud_text_hd.png', height=9, ascent=-4096)
    solid = {'type': 'bitmap', 'file': 'astralrecord:font/quest_hud_pixel.png',
             'height': 9, 'ascent': -4096, 'chars': ['\ue010\ue011\ue012\ue013']}
    font['providers'].append(solid)
    spacing = {'providers': [{'type': 'space', 'advances': {chr(0xE000 + n): -n for n in range(1, 12)}}]}
    buff = json.loads((PACK / 'assets/astralrecord/font/buff_hud.json').read_text())
    icons = next(provider for provider in buff['providers'] if provider['type'] == 'bitmap')
    icons['ascent'] = -4096
    return {'quest_hud': font, 'quest_hud_space': spacing,
            'location_hud_icons': {'providers': [icons]}}


def pixel_texture():
    from PIL import Image
    retina=Image.new('RGBA',(48,18))
    patterns=[
     ['    bbbb    ','  bbbbbbbb  ',' bbPPPPPPbb ','bbPppppppPbb','bPPPPPPPPPPb','bPpmmmmmmPPb','bPPPPPPPPPPb','bPpmmmmmmPPb','bPPPPPPPPPPb','bPpmmmmmmPPb','bPPPPPPPPPPb','bPpmmmmPPPPb','bPPPPPPPPPPb','bbPPPPPPPPbb',' bbbbbbbbbb ','  bbbbbb    ','   bbbbb    ','    bbb     '],
     ['            ','            ','     hh     ','    hHHh    ','   hHllHh   ','  hHh  hHh  ',' hHh    hHh ','hHh      hHh','Hh        hH','Hh        hH','hHh      hHh',' hHh    hHh ','  hHh  hHh  ','   hHllHh   ','    hHHh    ','     hh     ','            ','            '],
     ['            ','            ','     gg     ','    gGGg    ','   gGGGGg   ','  gGGGGGGg  ',' gGccGGGGGg ','gGGGccGGcGGg','GGGGGcccGGGG','GGGGGGcGGGGG','gGGGGGGGGGGg',' gGGGGGGGGg ','  gGGGGGGg  ','   gGGGGg   ','    gGGg    ','     gg     ','            ','            ']]
    pal={'b':'#7a5025','P':'#fadea1','p':'#d2a76a','m':'#a76533','h':'#8d6e3b','H':'#e1ae5a','l':'#fbe2a0','g':'#52785d','G':'#81bb8e','c':'#e0f5d0'}
    retina.paste((255,255,255,255),(0,0,12,18))
    rgb=lambda s:tuple(bytes.fromhex(s[1:]))+(255,)
    for i,pat in enumerate(patterns,1):
     assert len(pat)==18 and all(len(v)==12 for v in pat)
     for y,line in enumerate(pat):
      for x,c in enumerate(line):
       if c in pal:retina.putpixel((i*12+x,y),rgb(pal[c]))
    assert hashlib.sha256(retina.tobytes()).hexdigest() == HD_ICONS_PIXEL_SHA256
    output = io.BytesIO()
    retina.save(output, format='PNG', optimize=True)
    return output.getvalue()


def rasterize_hd(font_path):
    """Reproduce the quest-only bitmap from the pinned upstream JP bold font."""
    from PIL import Image, ImageDraw, ImageFont
    assert hashlib.sha256(font_path.read_bytes()).hexdigest() == HD_FONT_SHA256, 'Unexpected font source'
    data = json.loads((ASSETS / 'font/profile_hud_class_name_compact.json').read_text())
    g = next(provider for provider in data['providers'] if provider['type'] == 'bitmap')
    fn=font_path
    font=ImageFont.truetype(str(fn),52,index=0)
    src=Image.open(ASSETS/'textures/font/profile_hud_class_name_compact.png').convert('RGBA')
    out=Image.new('RGBA',(src.width*2,len(g['chars'])*36))
    n=0; fallback=0
    for row,line in enumerate(g['chars']):
     for col,c in enumerate(line):
      if c=='\0': continue
      old=src.crop((col*18,row*74,(col+1)*18,row*74+18)).getchannel('A')
      b=old.getbbox()
      if not b:continue
      x0,y0,x1,y1=b
      w=(x1-x0)*2;h=(y1-y0)*2
      if c not in ['…','・','。','、','.','/','-','−','—','＿','_']:
       raw=Image.new('L',(100,100))
       ImageDraw.Draw(raw).text((14,0),c,font=font,fill=255)
       bb=raw.getbbox()
       if bb:
        cv=raw.crop(bb).resize((w,h),Image.Resampling.LANCZOS)
        # Preserve the legacy bitmap provider's rounding and measured right edge.
        if cv.getbbox()[2]!=w:cv.putpixel((w-1,h//2),1)
        n+=1
       else:cv=old.crop(b).resize((w,h),Image.Resampling.NEAREST);fallback+=1
      else:
       cv=old.crop(b).resize((w,h),Image.Resampling.NEAREST);fallback+=1
      assert cv.getbbox()[2]==w
      tile=Image.new('RGBA',(w,h),'white');tile.putalpha(cv)
      out.paste(tile,(col*36+x0*2,row*36+y0*2))
    tex=ASSETS/'textures/font/quest_hud_text_hd.png';out.save(tex,optimize=True)
    assert hashlib.sha256(out.tobytes()).hexdigest() == HD_PIXEL_SHA256, 'Use Pillow 13.0.0 / FreeType 2.14.3'


def verify_font_geometry():
    from PIL import Image
    old = Image.open(ASSETS / 'textures/font/profile_hud_class_name_compact.png').convert('RGBA').getchannel('A')
    new = Image.open(HD_TEXTURE).convert('RGBA')
    assert new.size == (2304, 1872)
    assert hashlib.sha256(new.tobytes()).hexdigest() == HD_PIXEL_SHA256
    new = new.getchannel('A')
    provider = next(p for p in generated_fonts()['quest_hud']['providers'] if p['type'] == 'bitmap')
    for row, chars in enumerate(provider['chars']):
        for col, char in enumerate(chars):
            if char == '\0':
                continue
            before = old.crop((col * 18, row * 74, col * 18 + 18, row * 74 + 74)).getbbox()
            after = new.crop((col * 36, row * 36, col * 36 + 36, row * 36 + 36)).getbbox()
            assert (after[2] if after else 0) == 2 * (before[2] if before else 0), char
    print('Quest font asset pixels and legacy glyph advances verified.')


def assets(clients, check):
    for name, data in generated_fonts().items():
        path = PACK / f'assets/astralrecord/font/{name}.json'
        output = json.dumps(data, ensure_ascii=True, indent=2) + '\n'
        if check:
            assert path.read_text(encoding='utf-8') == output, f'Stale {path}'
        else:
            path.write_text(output, encoding='utf-8')
    if check:
        assert PIXEL_TEXTURE.read_bytes() == pixel_texture(), f'Stale {PIXEL_TEXTURE}'
    else:
        PIXEL_TEXTURE.write_bytes(pixel_texture())
    for version, jar_sha, shader_sha, directory, stem in VARIANTS:
        relative = f'assets/minecraft/shaders/core/{stem}.vsh'
        path = PACK / directory / relative
        block = injection(version) + location_injection(version) + ring_injection(version)
        if clients:
            data = (clients / (version + '.jar')).read_bytes()
            assert hashlib.sha1(data).hexdigest() == jar_sha, version + ' client SHA-1 mismatch'
            with zipfile.ZipFile(clients / (version + '.jar')) as z:
                source = z.read(relative).decode()
        else:
            assert check, 'Generation requires --clients-dir'
            source = path.read_text()
            assert source.count(block) == 1, version + ' HUD branch modified'
            source = source.replace(block, '')
        assert hashlib.sha256(source.encode()).hexdigest() == shader_sha, version + ' vanilla shader changed'
        output = source.rsplit('}', 1)[0] + block + '}' + source.rsplit('}', 1)[1]
        if check:
            assert path.read_text() == output, f'Stale {path}'
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(output)


def panel_placement(width, height, rows):
    panel_height = PADDING * 2 + ROW_HEIGHT * rows
    x = min(LEFT, max(0, width - WIDTH))
    limit = height - (PROFILE_BOTTOM_BAND if width * 0.5 - PROFILE_HALF_WIDTH < x + WIDTH else EDGE_MARGIN)
    scale = max(0, min(1, (width - x) / WIDTH, (limit - EDGE_MARGIN) / panel_height))
    y = min(TOP, max(EDGE_MARGIN, limit - panel_height * scale))
    return x, y, scale


def marker_geometry(marker, width, height):
    red, green, blue = marker
    text = 180 <= red < 220
    rows = (red - 180) // 4 + 1 if text else green if red == 220 else green >> 4
    row = blue if text else green & 15
    if not 1 <= rows <= MAX_ROWS:
        return None
    panel_height = PADDING * 2 + ROW_HEIGHT * rows
    x, y, scale = panel_placement(width, height, rows)
    row_y = PADDING + row * ROW_HEIGHT

    def quad(left, top, width, height, tint):
        return (x + left * scale, y + top * scale, width * scale, height * scale), tint

    if text and row < rows and LEFT <= green < LEFT + WIDTH:
        return quad(green - LEFT, row_y, 9, 9, TEXT_COLORS[(red - 180) % 4])
    if red == 220 and blue < 8:
        first_x, first_y = math.ceil(x - 0.0001), math.ceil(y - 0.0001)
        last_x = math.floor(x + WIDTH * scale + 0.0001)
        last_y = math.floor(y + panel_height * scale + 0.0001)
        w, h = last_x - first_x, last_y - first_y
        tint = SHAPE_COLORS[0]
        if blue == 0:
            rect = (first_x + 1, first_y + 1, w - 2, h - 2)
            tint = PANEL_COLOR
        elif blue < 3:
            rect = (first_x + 1, first_y if blue == 1 else last_y - 1, w - 2, 1)
        elif blue < 5:
            rect = (first_x if blue == 3 else last_x - 1, first_y + 1, 1, h - 2)
        elif blue < 7:
            rect = (first_x + 2 if blue == 5 else last_x - 9, first_y + 1, 7, 1)
            tint = ACCENT_COLOR
        else:
            rect = (first_x + 2, first_y + 2, w - 4, max(1, math.floor(ROW_HEIGHT * scale) - 1))
            tint = SHAPE_COLORS[1]
        if w < 20 or h < 5:
            rect = (*rect[:2], 0, 0)
        return rect, tint
    if row >= rows:
        return None
    if red == 221 and blue == 0:
        return quad(2, row_y - 1, WIDTH - 4, ROW_HEIGHT - 1, SHAPE_COLORS[1])
    if red == 224 and blue < 3:
        return quad(6, row_y + 1, 6, 9, (255, 255, 255))
    fill, shift = blue % 32, blue // 32 * 2
    if red in (222, 223, 225) and fill <= BAR_WIDTH:
        bar_x = BAR_LEFT - LEFT + shift
        if red == 222:
            return quad(bar_x + fill, row_y + 6, BAR_WIDTH - fill, 2, SHAPE_COLORS[2])
        return quad(bar_x, row_y + 6, fill, 2, SHAPE_COLORS[3 if red == 223 else 4])
    return None


def marker_alpha(marker):
    red, _, blue = marker
    return (PANEL_ALPHA if blue == 0 else BRASS_ALPHA if blue <= 4 else ACCENT_ALPHA if blue <= 6 else HEADING_ALPHA) if red == 220 else HEADING_ALPHA if red == 221 else 255


def location_placement(width, height, rows):
    right = width - LEFT
    left_limit = LEFT + WIDTH + HUD_GAP
    limit = height - (PROFILE_BOTTOM_BAND if max(left_limit, right - WIDTH) < width / 2 + PROFILE_HALF_WIDTH
                      else EDGE_MARGIN)
    window_height = LOCATION_ROW_HEIGHT * rows
    scale = max(0, min(1, (right - left_limit) / WIDTH, (limit - EDGE_MARGIN) / window_height))
    return right - WIDTH * scale, min(TOP, max(EDGE_MARGIN, limit - window_height * scale)), scale


def location_geometry(marker, width, height):
    red, green, blue = marker
    if not (0 <= red < 128 and 0 <= green < WIDTH and 0 <= blue < 256):
        return None
    rows = (red // 8) * 2 + 1 + (blue >> 7)
    style = (red & 7) + (8 if blue & 64 else 0)
    row = blue & 31
    shadow = bool(blue & 32)
    icon = style == 7
    if style >= len(LOCATION_TEXT_COLORS) or row >= rows or (shadow and icon):
        return None
    left, top, scale = location_placement(width, height, rows)
    x = green + int(shadow)
    y = row * LOCATION_ROW_HEIGHT + int(shadow or icon)
    w, h = (10, 79) if icon else (LOCATION_TEXT_WIDTH, LOCATION_TEXT_HEIGHT)
    color = LOCATION_TEXT_COLORS[style]
    if shadow:
        color = tuple(channel // 4 for channel in color)
    return (left + x * scale, top + y * scale, w * scale, h * scale), (*color, 255)


def verify_location_geometry():
    # Include both vanilla overlay positions and the taller buff glyph quad.
    for height in (180, 240, 540):
        for offset in (68, 72):
            y = height - offset + 7 + 4096
            assert height + 4000 < y and y + 79 < height + 4120
    for width, height in GUI_SIZES:
        for rows in range(1, LOCATION_MAX_ROWS + 1):
            left, top, scale = location_placement(width, height, rows)
            right = left + WIDTH * scale
            bottom = top + rows * LOCATION_ROW_HEIGHT * scale
            assert math.isclose(right, width - LEFT) and EDGE_MARGIN <= top <= TOP and 0 < scale <= 1
            for quest_rows in range(1, MAX_ROWS + 1):
                quest_left, _, quest_scale = panel_placement(width, height, quest_rows)
                assert quest_left + WIDTH * quest_scale + HUD_GAP <= left + 1e-8
            if left < width / 2 + PROFILE_HALF_WIDTH:
                assert bottom <= height - PROFILE_BOTTOM_BAND + 1e-8
            for row in range(rows):
                row_top = top + row * LOCATION_ROW_HEIGHT * scale
                row_bottom = row_top + LOCATION_ROW_HEIGHT * scale
                for style, color in enumerate(LOCATION_TEXT_COLORS):
                    red = ((rows - 1) // 2) * 8 + (style & 7)
                    blue = ((rows - 1) % 2) * 128 + row + (64 if style >= 8 else 0)
                    (x, y, w, h), tint = location_geometry((red, 10, blue), width, height)
                    assert math.isclose(x, left + 10 * scale) and tint == (*color, 255)
                    shadow = location_geometry((red, 10, blue + 32), width, height)
                    if style == 7:
                        assert shadow is None and math.isclose(y, row_top + scale)
                        assert math.isclose(w, 10 * scale) and math.isclose(h, 79 * scale)
                        assert y + 10 * scale <= row_bottom
                    else:
                        assert math.isclose(y, row_top) and math.isclose(h, LOCATION_TEXT_HEIGHT * scale)
                        assert math.isclose(w, LOCATION_TEXT_WIDTH * scale)
                        (sx, sy, sw, sh), shade = shadow
                        assert math.isclose(sx, x + scale) and math.isclose(sy, y + scale)
                        assert (sw, sh) == (w, h) and math.isclose(sy + sh, row_bottom)
                        assert shade == (*(channel // 4 for channel in color), 255)
    assert location_placement(640, 360, 9) == (460, 48, 1)
    for marker in ((0, 172, 0), (0, 10, 1), (0, 10, 33), (123, 10, 64), (120, 10, 256), (127, 10, 32),
                   (128, 15, 2), (143, 1, 4), (145, 23, 3), (146, 10, 0), (161, 10, 15), (180, 10, 0)):
        assert location_geometry(marker, 640, 360) is None
    print('Location HUD marker bounds, row spacing, shadows and Quest/Profile separation passed.')


def verify_geometry():
    # World text cannot meet the negative-ascent window, regardless of color.
    count = 0
    for width, height in GUI_SIZES:
        for actionbar_y in (height - 68, height - 72):
            assert actionbar_y + 7 + 4096 > height + 4000
            assert actionbar_y + 7 + 4096 + 9 < height + 4120
        for rows in range(1, MAX_ROWS + 1):
            height_px = PADDING * 2 + ROW_HEIGHT * rows
            left, top, scale = panel_placement(width, height, rows)
            right, bottom = left + WIDTH * scale, top + height_px * scale
            assert EDGE_MARGIN <= top <= TOP and 0 < scale <= 1 and right <= width and bottom <= height
            # Optional points use ascent 65 on the ActionBar at H-72 with a +7 glyph offset.
            if right > width * 0.5 - 154:
                assert bottom + 4 <= height - 72 + 7 - 65 + 1e-8
            for edge in range(8):
                (x, y, w, h), _ = marker_geometry((220, rows, edge), width, height)
                assert left <= x < x + w <= right + 1e-8 and top <= y < y + h <= bottom + 1e-8
                assert (x, y, w, h) == tuple(map(int, (x, y, w, h))) and (min(w, h) == 1 if 1 <= edge <= 6 else min(w, h) > 1)
                assert marker_alpha((220, rows, edge)) == (PANEL_ALPHA if edge == 0 else BRASS_ALPHA if edge <= 4 else ACCENT_ALPHA if edge <= 6 else HEADING_ALPHA)
                count += 4
            for row in range(rows):
                for style in range(4):
                    for glyph_x in (LEFT, 27, LEFT + WIDTH - 1):
                        red = 180 + (rows - 1) * 4 + style
                        (x, y, w, _), tint = marker_geometry((red, glyph_x, row), width, height)
                        assert (x, y, w) == (left + (glyph_x - LEFT) * scale,
                                             top + (PADDING + row * ROW_HEIGHT) * scale, 9 * scale)
                        assert y + 9 * scale <= bottom + 1e-8  # Actual text ink height, excluding transparent UVs.
                        assert tint == TEXT_COLORS[style]
                        count += 4
                shape_row = rows * 16 + row
                for shift in (0, 3, 7):
                    for fill in range(1, BAR_WIDTH):
                        variant = shift * 32 + fill
                        (x, y, w, h), _ = marker_geometry((223, shape_row, variant), width, height)
                        (tx, ty, tw, th), _ = marker_geometry((222, shape_row, variant), width, height)
                        assert (x, y, w, h) == (left + (BAR_LEFT - LEFT + shift * 2) * scale,
                                                top + (PADDING + row * ROW_HEIGHT + 6) * scale,
                                                fill * scale, 2 * scale)
                        assert math.isclose(tx, x + w) and (ty, tw, th) == (y, (BAR_WIDTH - fill) * scale, h)
                        count += 8
    assert panel_placement(640, 360, 10) == (LEFT, TOP, 1)
    assert panel_placement(720, 240, 10) == (LEFT, TOP, 1)  # No horizontal overlap.
    assert panel_placement(320, 240, 10) == panel_placement(426, 240, 10) == (LEFT, 4, 0.75)
    assert marker_geometry((220, 11, 0), 640, 360) is None
    assert marker_geometry((219, 25, 160), 640, 360) is None
    assert marker_geometry((221, 16, 1), 640, 360) is None
    print(f'Quest glyph geometry: {count} vertex cases passed; non-marker vanilla source hashes preserved.')


def preview(path):
    from PIL import Image, ImageChops, ImageDraw
    font = json.loads((PACK / 'assets/astralrecord/font/quest_hud.json').read_text(encoding='utf-8'))
    provider = next(p for p in font['providers'] if p['type'] == 'bitmap')
    atlas = Image.open(HD_TEXTURE).convert('RGBA')
    rows = provider['chars']
    cell_width = atlas.width // len(rows[0])
    cell_height = atlas.height // len(rows)
    cells = {ch: (col, row) for row, chars in enumerate(rows) for col, ch in enumerate(chars) if ch != '\0'}
    metrics = ROOT / '10_plugin/AstralRecord/src/main/resources/class-name-glyph-advances.txt'
    advances = {int(key, 16): int(value) for key, value in map(str.split, metrics.read_text().splitlines())}

    def advance(ch):
        value = advances.get(ord(ch), advances[ord('?')])
        return value * 3 // 4 if ch in (' ', '\u3000') else ((value - 1) * 3 + 3) // 4 + 1

    def fit(text, maximum):
        if sum(map(advance, text)) <= maximum:
            return text
        while text and sum(map(advance, text + '…')) > maximum:
            text = text[:-1]
        return text + '…'

    samples = [
        [
            (0, '苔甲羅の討伐依頼', '', -1),
            (1, 'ミッドガルズ・モスシェル', '7/10', 70),
            (0, 'グラスボア討伐依頼', '', -1),
            (1, 'ミズガルズ・グラスボア', '3/10', 30),
            (0, 'フォレストウルフ討伐依頼', '', -1),
            (1, 'フォレストウルフ', '1/12', 8),
        ],
        [
            (0, 'アルタ始源遺跡の討伐依頼', '1/6', -1),
            (2, '遺跡のスケルトン Lv.15', '3/3', 100),
            (1, '遺跡のスケルトン・アーチャー', '2/3', 67),
            (1, '遺跡の突撃ピグリン Lv.15', '0/3', 0),
            (1, 'ヴァタールスケルトン Lv.15', '1/3', 33),
            (1, 'アイヌア Lv.15', '0/3', 0),
            (1, 'アイヌリンダレ Lv.15', '1/3', 33),
            (0, '苔甲羅の討伐依頼', '', -1),
            (1, 'ミッドガルズ・モスシェル', '7/10', 70),
            (3, '他1件の依頼', '', -1),
        ],
    ]
    scale = 2
    image = Image.new('RGBA', (760, 380), (46, 59, 72, 255))
    draw = ImageDraw.Draw(image)
    draw.text((16, 12), 'JAVA RESOURCE-PACK ASSET PREVIEW / GUI SCALE 2', fill='white')
    icons = Image.open(PIXEL_TEXTURE).convert('RGBA')
    solid = icons.crop((0, 0, 12, 18))
    for sample_index, sample in enumerate(samples):
        panel_offset = sample_index * 380

        def draw_marker(marker, tile):
            (x, y, width, height), tint = marker_geometry(marker, 640, 360)
            tinted = ImageChops.multiply(tile.convert('RGB'), Image.new('RGB', tile.size, tint))
            opacity = marker_alpha(marker)
            tinted.putalpha(tile.getchannel('A').point(lambda a: a * opacity // 255 if a * opacity >= 26 * 255 else 0))
            tinted = tinted.resize((width * scale, height * scale), Image.Resampling.NEAREST)
            image.alpha_composite(tinted, (panel_offset + x * scale, (y - 2) * scale))

        def draw_text(value, x, row, marker):
            for ch in value:
                if ch not in (' ', '\u3000'):
                    column, grid_row = cells.get(ch, cells['?'])
                    left, top = column * cell_width, grid_row * cell_height
                    tile = atlas.crop((left, top, left + cell_width, top + cell_height))
                    draw_marker((marker, x, row), tile)
                x += advance(ch)

        count = len(sample)
        for edge in range(8):
            draw_marker((220, count, edge), solid)
        for row, (kind, label, value, progress) in enumerate(sample):
            text_marker = 180 + (count - 1) * 4 + kind
            shape_row = count * 16 + row
            if kind == 0 and row > 0:
                draw_marker((221, shape_row, 0), solid)
            if kind < 3:
                icon_x = (kind + 1) * 12
                draw_marker((224, shape_row, kind), icons.crop((icon_x, 0, icon_x + 12, 18)))
            else:
                draw_text('…', LEFT + 5, row, text_marker)
            text_right = LEFT + WIDTH - 5
            label_x = LEFT + 18
            value = fit(value, text_right - label_x - 22)
            value_x = text_right - sum(map(advance, value))
            label = fit(label, (value_x - 5 if value else text_right) - label_x)
            bar_space = value_x - 3 - BAR_WIDTH - BAR_LEFT
            bar_shift = max(0, min(bar_space // 2, 7))
            if (progress >= 0 and value and bar_space >= 0
                    and sum(map(advance, label)) <= BAR_LEFT + bar_shift * 2 - 3 - label_x):
                fill = (progress * BAR_WIDTH + 50) // 100
                variant = bar_shift * 32 + fill
                if fill < BAR_WIDTH:
                    draw_marker((222, shape_row, variant), solid)
                if fill > 0:
                    draw_marker((225 if kind == 2 else 223, shape_row, variant), solid)
            draw_text(label, label_x, row, text_marker)
            draw_text(value, value_x, row, text_marker)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.convert('RGB').save(path)
    print(f'Static asset preview: {path}')


def expanded_shader(version, directory, stem, clients):
    jar = clients / (version + '.jar')
    with zipfile.ZipFile(jar) as archive:
        def expand(source):
            def include(match):
                namespace, name = match.group(1).split(':')
                text = archive.read(f'assets/{namespace}/shaders/include/{name}').decode()
                return expand(re.sub(r'^#version.*$', '', text, flags=re.M))
            return re.sub(r'#(?:moj_import|include) <([^>]+)>', include, source)
        return expand((PACK / directory / f'assets/minecraft/shaders/core/{stem}.vsh').read_text())


def compile_shaders(clients, compiler):
    import subprocess
    import tempfile
    assert clients, '--compile-with requires --clients-dir'
    with tempfile.TemporaryDirectory(prefix='astral-quest-glsl-') as work:
        work = Path(work)
        for version, _, _, directory, stem in VARIANTS:
            source = expanded_shader(version, directory, stem, clients)
            modes = ('GUI', 'WORLD', 'SEE_THROUGH') if version in ('26.2', '26.3') else ('WORLD',)
            for mode in modes:
                define = '' if mode == 'WORLD' else f'\n#define IS_{mode}\n'
                path = work / f'{version}-{mode}.vert'
                path.write_text(source.replace('#version 330', '#version 330' + define))
                command = [str(compiler), '-S', 'vert']
                if version == '26.3':
                    command += ['-V', '--auto-map-bindings', '-o', str(work / 'verify.spv')]
                result = subprocess.run(command + [str(path)], capture_output=True, text=True)
                print(f'{version} {mode}: {result.stdout.strip()} {result.stderr.strip()}')
                assert result.returncode == 0 and 'WARNING' not in result.stdout + result.stderr, command
    print('All 8 vanilla/HUD vertex shader variants compiled without warnings.')


def gpu_check(clients):
    # Optional independent off-screen OpenGL check. 26.3's ShaderC/Vulkan spelling is
    # aliased only in this OpenGL harness; --compile-with compiles its untouched source.
    import moderngl
    assert clients, '--gpu-check requires --clients-dir'
    context = (moderngl.create_standalone_context() if sys.platform == 'win32'
               else moderngl.create_standalone_context(backend='egl'))
    context.simple_framebuffer((1, 1)).use()
    identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    checked = 0
    for version, _, _, directory, stem in VARIANTS:
        source = expanded_shader(version, directory, stem, clients)
        defines = '\n#define IS_GUI\n' if version in ('26.2', '26.3') else ''
        if version == '26.3':
            defines += '#define gl_VertexIndex gl_VertexID\n'
        source = source.replace('#version 330', '#version 330' + defines)
        source = source.replace('void main() {', 'out vec4 questTestPosition;\nvoid main() {')
        index = source.rfind('}')
        source = source[:index] + '    questTestPosition = gl_Position;\n' + source[index:]
        program = context.program(vertex_shader=source, varyings=['questTestPosition', 'vertexColor'])
        data = identity + identity + [1, 1, 1, 1] + [0, 0, 0, 0] if version == '26.3' else identity + [1, 1, 1, 1] + [0, 0, 0, 0] + identity
        transforms = context.buffer(struct.pack('40f', *data))
        transforms.bind_to_uniform_block(0)
        program['DynamicTransforms'].binding = 0
        lightmap = context.texture((16, 16), 4, bytes([255] * (16 * 16 * 4)))
        lightmap.use(0)
        if 'Sampler2' in program:
            program['Sampler2'].value = 0
        for width, height in GUI_SIZES:
            projection_data = [2 / width, 0, 0, 0, 0, -2 / height, 0, 0, 0, 0, -.001, 0, -1, 1, 0, 1]
            projection = context.buffer(struct.pack('16f', *projection_data))
            projection.bind_to_uniform_block(1)
            program['Projection'].binding = 1
            for old_x in (-400, 0, 900):
                markers = [(180, 25, 0), (218, 168, 9), (220, 10, 0), (220, 10, 1),
                           (220, 10, 3), (220, 10, 7), (221, 165, 0), (222, 168, 11),
                           (223, 168, 107), (224, 168, 2), (225, 169, 242),
                           (255, 255, 255), (220, 11, 0), (219, 25, 10),
                           (0, 10, 0), (0, 10, 16), (0, 10, 17), (0, 172, 0),
                           (65, 84, 24), (66, 10, 24), (67, 150, 24), (68, 84, 24), (69, 25, 24),
                           (126, 156, 159), (126, 156, 191), (120, 156, 223), (120, 156, 255), (127, 10, 159), (127, 10, 191),
                           (RING_MARKER, 0, 0), (RING_MARKER, 1, 1), (RING_MARKER, 2, 2),
                           (RING_MARKER, 3, 3), (RING_MARKER, 4, 4), (RING_MARKER, 5, 5),
                           (RING_MARKER, 8, 0), (RING_MARKER, 8, 1), (RING_MARKER, 9, 0),
                           (64, 150, 32), (65, 150, 40), (66, 150, 56), (122, 156, 63),
                           (123, 10, 32), (120, 10, 64),
                           (128, 3, 9), (143, 3, 9), (145, 30, 9), (146, 10, 0), (161, 156, 15)]
                alpha = 204 / 255
                for marker in markers:
                    for mode in ('quest', 'normal_y', 'wrong_ascent'):
                        geometry = None
                        if mode == 'quest':
                            quest = marker_geometry(marker, width, height)
                            geometry = ((quest[0], (*quest[1], marker_alpha(marker))) if quest
                                else location_geometry(marker, width, height) or ring_geometry(marker, width, height))
                        if marker[0] < 128 and marker[0] % 8 == 7:
                            quad_width, quad_height = 10, 79
                        elif marker[0] < 128:
                            quad_width, quad_height = LOCATION_TEXT_WIDTH, LOCATION_TEXT_HEIGHT
                        elif 180 <= marker[0] < 220:
                            quad_width, quad_height = 9, 9
                        elif marker[0] == RING_MARKER:
                            quad_width = quad_height = RING_SLOT
                        else:
                            quad_width, quad_height = 6, 9
                        y = 40 if mode == 'normal_y' else height + (5000 if mode == 'wrong_ascent' else 4031)
                        positions, colors = [], []
                        for corner in range(4):
                            positions += [old_x + (quad_width if corner >= 2 else 0),
                                          y + (quad_height if corner in (1, 2) else 0), 0]
                            colors += [c / 255 for c in marker] + [alpha]
                        buffers = [context.buffer(struct.pack('12f', *positions)), context.buffer(struct.pack('16f', *colors))]
                        content = [(buffers[0], '3f', 'Position'), (buffers[1], '4f', 'Color')]
                        if 'UV2' in program:
                            uv = context.buffer(struct.pack('8i', *([240, 240] * 4)))
                            buffers.append(uv)
                            content.append((uv, '2i', 'UV2'))
                        vao = context.vertex_array(program, content)
                        output = context.buffer(reserve=128)
                        vao.transform(output, vertices=4)
                        values = struct.unpack('32f', output.read())
                        for corner in range(4):
                            actual = values[corner * 8:corner * 8 + 4]
                            if geometry:
                                (x, y, w, h), tint = geometry
                                xx = x + (w if corner >= 2 else 0)
                                yy = y + (h if corner in (1, 2) else 0)
                                actual_tint = values[corner * 8 + 4:corner * 8 + 8]
                                assert all(abs(a - b / 255) < 1e-5 for a, b in zip(actual_tint[:3], tint[:3]))
                                assert abs(actual_tint[3] - alpha * tint[3] / 255) < 1e-5
                            else:
                                xx, yy = positions[corner * 3:corner * 3 + 2]
                            expected = [-1 + 2 * xx / width, 1 - 2 * yy / height, 0, 1]
                            assert all(abs(a - b) < 1e-5 for a, b in zip(actual, expected)), (version, mode, actual, expected)
                            checked += 1
                        vao.release()
                        output.release()
                        for buffer in buffers:
                            buffer.release()
            projection.release()
        program.release()
        transforms.release()
        lightmap.release()
    print(f'OpenGL transform feedback: {checked} vertices passed on {context.info["GL_RENDERER"]}; not a client capture.')
    context.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--clients-dir', type=Path)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--preview', type=Path)
    parser.add_argument('--compile-with', type=Path, help='Optional glslangValidator executable')
    parser.add_argument('--raster-font', type=Path, help='Rerasterize the quest-only atlas from the pinned Noto Sans CJK JP Bold font')
    parser.add_argument('--gpu-check', action='store_true', help='Optional moderngl OpenGL transform feedback')
    args = parser.parse_args()
    if args.raster_font:
        if args.check:
            parser.error('--raster-font cannot be used with --check')
        rasterize_hd(args.raster_font)
    assets(args.clients_dir, args.check)
    verify_font_geometry()
    verify_geometry()
    verify_location_geometry()
    verify_ring_geometry()
    if args.compile_with:
        compile_shaders(args.clients_dir, args.compile_with)
    if args.gpu_check:
        gpu_check(args.clients_dir)
    if args.preview:
        preview(args.preview)


if __name__ == '__main__':
    main()
