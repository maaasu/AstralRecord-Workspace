#!/usr/bin/env python3
"""Generate/check the Java quest HUD from the distributed bitmap and pinned vanilla shaders.

Generation: --clients-dir DIR containing SHA-1-verified 1.21.11/26.1/26.2/26.3.jar.
Checking and the static preview need only the committed pack assets (Pillow for preview).
This is a resource-pack preview, never a Minecraft client screenshot.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib

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
WIDTH = 172
ROW_HEIGHT = 13
PADDING = 3
MAX_ROWS = 10
LEFT = 8
TOP = 48
EDGE_MARGIN = 4
PROFILE_HALF_WIDTH = 154
PROFILE_BOTTOM_BAND = 134
FRAME_ALPHA = 104
FRAME_CORNER_ALPHA = 164
GUI_SIZES = ((320, 180), (320, 240), (426, 240), (640, 360), (960, 540), (1920, 1080))
BAR_WIDTH = 18
BAR_LEFT = LEFT + 122
TEXT_COLORS = ((255, 215, 140), (242, 244, 246), (167, 238, 220), (185, 193, 201))
SHAPE_COLORS = ((151, 142, 118), (100, 105, 109), (115, 122, 132), (255, 224, 168), (167, 238, 220))
PIXEL_TEXTURE = PACK / 'assets/astralrecord/textures/font/quest_hud_pixel.png'


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
            // Minecraft bakes the entire 18x74 cell, including transparent margins.
            size = vec2(9.0, 37.0);
            tint = palette == 0 ? {title} : palette == 1 ? {objective}
                : palette == 2 ? {complete} : {overflow};
        }} else if (questFrame) {{
            // Snap only the frame inside the scaled panel, preserving one pixel strokes.
            vec2 first = ceil(vec2(left, top) - 0.0001);
            vec2 last = floor(vec2(left + {WIDTH}.0 * scale, top + panelHeight * scale) + 0.0001);
            int edge = questCode.b;
            if (edge < 2) {{
                origin = vec2(first.x + 2.0, edge == 0 ? first.y : last.y - 1.0);
                size = vec2(last.x - first.x - 4.0, 1.0);
            }} else if (edge < 4) {{
                origin = vec2(edge == 2 ? first.x : last.x - 1.0, first.y + 2.0);
                size = vec2(1.0, last.y - first.y - 4.0);
            }} else {{
                origin = vec2((edge & 1) == 0 ? first.x + 1.0 : last.x - 2.0,
                    edge < 6 ? first.y + 1.0 : last.y - 2.0);
                size = vec2(1.0);
            }}
            if (any(lessThan(last - first, vec2(5.0)))) {{
                size = vec2(0.0);
            }}
            tint = {frame};
        }} else if (questType == 221) {{
            origin = vec2(left + 5.0, rowY - 1.0);
            size = vec2({WIDTH - 10}.0, 1.0);
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
            size = vec2(remaining ? {BAR_WIDTH}.0 - filled : filled, 1.0);
            tint = remaining ? {track} : questType == 225 ? {complete_fill} : {fill};
        }}
        vec2 questPixel = origin + size * vec2(questCorner >= 2 ? 1.0 : 0.0,
            questCorner == 1 || questCorner == 2 ? 1.0 : 0.0);
        vec2 panelOrigin = vec2(left, top);
        if (!questFrame) {{
            questPixel = panelOrigin + (questPixel - panelOrigin) * scale;
        }}
        gl_Position.xy = (vec2(-1.0, 1.0) + questPixel / questSize * vec2(2.0, -2.0)) * gl_Position.w;
        float opacity = questFrame ? (questCode.b < 4 ? {FRAME_ALPHA}.0 : {FRAME_CORNER_ALPHA}.0) / 255.0 : 1.0;
        vertexColor = vec4(tint, Color.a * opacity);
{fog}    }}
#endif
''' + END


def generated_fonts():
    font = json.loads((PACK / 'assets/astralrecord/font/profile_hud_class_name_compact.json').read_text())
    for provider in font['providers']:
        if provider['type'] == 'bitmap':
            provider['ascent'] = -4096
    font['providers'].append({'type': 'bitmap', 'file': 'astralrecord:font/quest_hud_pixel.png',
                              'height': 9, 'ascent': -4096, 'chars': ['\ue010\ue011\ue012\ue013']})
    spacing = {'providers': [{'type': 'space', 'advances': {chr(0xE000 + n): -n for n in range(1, 11)}}]}
    return {'quest_hud': font, 'quest_hud_space': spacing}


def pixel_texture():
    # A solid white cell for rectangles, then the three HUD markers.
    icons = [
        ['######'] * 9,
        ['.mmmm.', 'mwwwwm', 'mwrrwm', 'mwrrwm', 'mwrrwm', 'mwrrwm', 'mwrwwm', 'mww..m', 'mm...m'],
        ['......', '......', '..gg..', '.g..g.', 'g....g', 'g....g', '.g..g.', '..gg..', '......'],
        ['......', '......', '..tt..', '.tttt.', 'tttttt', 'tttttt', '.tttt.', '..tt..', '......'],
    ]
    colors = {'.': (0, 0, 0, 0), '#': (255, 255, 255, 255), 'm': (63, 37, 42, 255),
              'w': (249, 212, 186, 255), 'r': (191, 95, 81, 255),
              'g': (182, 197, 204, 255), 't': (167, 238, 220, 255)}
    pixels = b''.join(b'\0' + bytes(value for icon in icons for key in icon[row] for value in colors[key])
                      for row in range(9))

    def chunk(name, value):
        return struct.pack('>I', len(value)) + name + value + struct.pack('>I', zlib.crc32(name + value))

    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 24, 9, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


def assets(clients, check):
    for name, data in generated_fonts().items():
        path = PACK / f'assets/astralrecord/font/{name}.json'
        output = (json.dumps(data, ensure_ascii=True, indent=2) + '\n').encode()
        if check:
            assert path.read_bytes() == output, f'Stale {path}'
        else:
            path.write_bytes(output)
    if check:
        assert PIXEL_TEXTURE.read_bytes() == pixel_texture(), f'Stale {PIXEL_TEXTURE}'
    else:
        PIXEL_TEXTURE.write_bytes(pixel_texture())
    for version, jar_sha, shader_sha, directory, stem in VARIANTS:
        relative = f'assets/minecraft/shaders/core/{stem}.vsh'
        path = PACK / directory / relative
        if clients:
            data = (clients / (version + '.jar')).read_bytes()
            assert hashlib.sha1(data).hexdigest() == jar_sha, version + ' client SHA-1 mismatch'
            with zipfile.ZipFile(clients / (version + '.jar')) as z:
                source = z.read(relative).decode()
        else:
            assert check, 'Generation requires --clients-dir'
            source = path.read_text()
            block = injection(version)
            assert source.count(block) == 1, version + ' HUD branch modified'
            source = source.replace(block, '')
        assert hashlib.sha256(source.encode()).hexdigest() == shader_sha, version + ' vanilla shader changed'
        output = source.rsplit('}', 1)[0] + injection(version) + '}' + source.rsplit('}', 1)[1]
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
        return quad(green - LEFT, row_y, 9, 37, TEXT_COLORS[(red - 180) % 4])
    if red == 220 and blue < 8:
        first_x, first_y = math.ceil(x - 0.0001), math.ceil(y - 0.0001)
        last_x = math.floor(x + WIDTH * scale + 0.0001)
        last_y = math.floor(y + panel_height * scale + 0.0001)
        if blue < 2:
            rect = (first_x + 2, first_y if blue == 0 else last_y - 1, last_x - first_x - 4, 1)
        elif blue < 4:
            rect = (first_x if blue == 2 else last_x - 1, first_y + 2, 1, last_y - first_y - 4)
        else:
            rect = (first_x + 1 if blue % 2 == 0 else last_x - 2,
                    first_y + 1 if blue < 6 else last_y - 2, 1, 1)
        if min(last_x - first_x, last_y - first_y) < 5:
            rect = (*rect[:2], 0, 0)
        return rect, SHAPE_COLORS[0]
    if row >= rows:
        return None
    if red == 221 and blue == 0:
        return quad(5, row_y - 1, WIDTH - 10, 1, SHAPE_COLORS[1])
    if red == 224 and blue < 3:
        return quad(6, row_y + 1, 6, 9, (255, 255, 255))
    fill, shift = blue % 32, blue // 32 * 2
    if red in (222, 223, 225) and fill <= BAR_WIDTH:
        bar_x = BAR_LEFT - LEFT + shift
        if red == 222:
            return quad(bar_x + fill, row_y + 6, BAR_WIDTH - fill, 1, SHAPE_COLORS[2])
        return quad(bar_x, row_y + 6, fill, 1, SHAPE_COLORS[3 if red == 223 else 4])
    return None


def marker_alpha(marker):
    red, _, blue = marker
    return (FRAME_ALPHA if blue < 4 else FRAME_CORNER_ALPHA) if red == 220 else 255


def verify_geometry():
    # World text cannot meet the negative-ascent window, regardless of color.
    count = 0
    for width, height in GUI_SIZES:
        for actionbar_y in (height - 68, height - 72):
            assert actionbar_y + 7 + 4096 > height + 4000
            assert actionbar_y + 7 + 4096 + 37 < height + 4120
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
                assert (x, y, w, h) == tuple(map(int, (x, y, w, h))) and min(w, h) == 1
                assert marker_alpha((220, rows, edge)) == (104 if edge < 4 else 164)
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
                                                fill * scale, scale)
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
    atlas = Image.open(PACK / 'assets/astralrecord/textures/font/profile_hud_class_name_compact.png').convert('RGBA')
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
    solid = icons.crop((0, 0, 6, 9))
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
                icon_x = (kind + 1) * 6
                draw_marker((224, shape_row, kind), icons.crop((icon_x, 0, icon_x + 6, 9)))
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
                           (255, 255, 255), (220, 11, 0), (219, 25, 10)]
                for marker in markers:
                    for mode in ('quest', 'normal_y', 'wrong_ascent'):
                        geometry = marker_geometry(marker, width, height) if mode == 'quest' else None
                        quad_width = 9 if marker[0] < 220 else 6
                        quad_height = 37 if marker[0] < 220 else 9
                        y = 40 if mode == 'normal_y' else height + (5000 if mode == 'wrong_ascent' else 4031)
                        positions, colors = [], []
                        for corner in range(4):
                            positions += [old_x + (quad_width if corner >= 2 else 0),
                                          y + (quad_height if corner in (1, 2) else 0), 0]
                            colors += [c / 255 for c in marker] + [1]
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
                                assert all(abs(a - b / 255) < 1e-5 for a, b in zip(actual_tint, (*tint, marker_alpha(marker))))
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
    parser.add_argument('--gpu-check', action='store_true', help='Optional moderngl OpenGL transform feedback')
    args = parser.parse_args()
    assets(args.clients_dir, args.check)
    verify_geometry()
    if args.compile_with:
        compile_shaders(args.clients_dir, args.compile_with)
    if args.gpu_check:
        gpu_check(args.clients_dir)
    if args.preview:
        preview(args.preview)


if __name__ == '__main__':
    main()
