#!/usr/bin/env python3
"""Preview the Bedrock quest HUD layout with the pack frame and substitute glyphs."""

import argparse
import json
import struct
import io
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
COLORS = {"6": "#ffaa00", "f": "#ffffff", "a": "#55ff55", "7": "#aaaaaa"}


def load_glyphs():
    path = ROOT / "assets/astralrecord/font/profile_hud_class_name_compact.json"
    font = json.loads(path.read_text(encoding="utf-8"))
    bitmap = next(provider for provider in font["providers"] if provider["type"] == "bitmap")
    rows = bitmap["chars"]
    texture = ROOT / "assets/astralrecord/textures/font/profile_hud_class_name_compact.png"
    atlas = Image.open(texture).convert("RGBA")
    source_height = atlas.height // len(rows)
    height = bitmap["height"]
    if source_height != height:
        atlas = atlas.resize(
            (atlas.width * height // source_height, height * len(rows)), Image.Resampling.BOX
        )
    width = atlas.width // len(rows[0])
    return {
        character: atlas.crop((x * width, y * height, (x + 1) * width, y * height + 9))
        for y, row in enumerate(rows)
        for x, character in enumerate(row)
        if character != "\0"
    }


def draw_text(image, origin, value, color, glyphs):
    x, y = origin
    for character in value:
        if character == " ":
            x += 3
            continue
        glyph = glyphs.get(character, glyphs["?"])
        alpha = glyph.getchannel("A")
        box = alpha.getbbox()
        if box is None:
            continue
        shadow = Image.new("RGBA", glyph.size, "#161009")
        shadow.putalpha(alpha)
        image.alpha_composite(shadow, (x + 1, y + 1))
        tinted = Image.new("RGBA", glyph.size, color)
        tinted.putalpha(alpha)
        image.alpha_composite(tinted, (x, y))
        x += box[2] + 1


def native_pages(font_source):
    """Read ordinary Bedrock font PNGs from a font folder or a local vanilla brarchive."""
    if font_source.is_dir():
        return {p.name: p.read_bytes() for p in font_source.glob('*.png')}
    blob = font_source.read_bytes()
    count, version = struct.unpack_from('<II', blob, 8)
    data_start = 16 + count * 256
    if version != 1 or data_start > len(blob):
        raise ValueError("このフォントアーカイブ形式には対応していません")
    pages = {}
    for entry in range(count):
        start = 16 + entry * 256
        name = blob[start + 1:start + 1 + blob[start]].decode('utf-8')
        if not (name.startswith('glyph_') or name == 'default8.png'):
            continue
        offset, size = struct.unpack_from('<II', blob, start + 248)
        page = blob[data_start + offset:data_start + offset + size]
        if not page.startswith(b'\x89PNG\r\n\x1a\n'):
            raise ValueError(f'PNGとして読み込めない字形ページ: {name}')
        pages[name.lower()] = page
    return pages


def native_preview(path, ui, font_source, backdrop):
    """Draw a cropped native bitmap-font example from the published Bedrock label definition."""
    pages = native_pages(font_source)
    frame = ui['frame']
    label = frame['controls'][0]['quest_text']
    assert frame['type'] == 'panel' and 'texture' not in frame
    assert label['shadow'] and label['text_alignment'] == 'left'
    samples = [('6', 'グラスボア討伐依頼'), ('f', '◇ ミズガルズ・グラスボア  3/10')]
    scale = 4
    text_x, text_y = label['offset']
    frame_x, frame_y = frame['offset']
    pitch = 10 + label['line_padding']
    variant = next(iter(ui['panel']['controls'][len(samples)].values()))
    height = variant['$frame_height'] + 6
    layer = Image.new('RGBA', ((frame_x + frame['size'][0] + 4) * scale, height * scale))
    opened = {}
    for num, (code, text) in enumerate(samples):
        pen = float(frame_x + text_x)
        y = frame_y + text_y + pitch * num
        for char in text:
            if char == ' ':
                pen += 4
                continue
            cp = ord(char)
            page = f'glyph_{cp >> 8:02x}.png'
            if page not in opened:
                opened[page] = Image.open(io.BytesIO(pages[page])).convert('RGBA')
            sheet = opened[page]
            cell = sheet.width // 16
            left, top = (cp % 16) * cell, ((cp & 255) // 16) * cell
            alpha = sheet.crop((left, top, left + cell, top + cell)).getchannel('A')
            ink = alpha.getbbox()
            if ink is None:
                raise ValueError(f'クライアントに字形がありません: {char}')
            alpha = alpha.crop((ink[0], 0, ink[2], cell))
            logical_width = alpha.width * 8 / cell
            alpha = alpha.resize((round(logical_width * scale), 8 * scale), Image.Resampling.NEAREST)
            for shift, color in [(1, '#252525'), (0, COLORS[code])]:
                glyph = Image.new('RGBA', alpha.size, color)
                glyph.putalpha(alpha)
                layer.alpha_composite(glyph, (round((pen + shift) * scale), (y + shift) * scale))
            pen += logical_width + 1
        assert pen < frame_x + frame['size'][0]
    assert layer.getchannel('A').histogram()[0] > layer.width * layer.height * 0.75
    if backdrop:
        with Image.open(backdrop) as source:
            ground = ImageOps.fit(source.convert('RGBA'), layer.size, method=Image.Resampling.LANCZOS)
        layer = Image.alpha_composite(ground, layer)
    path.parent.mkdir(parents=True, exist_ok=True)
    layer.save(path)
    print(f'Bedrock native pack font / JSON UI crop: {path}')


def render_preview(output_path, packed=None, font_source=None, backdrop=None):
    from contextlib import nullcontext
    with zipfile.ZipFile(packed) if packed else nullcontext() as archive:
        def resource(name):
            return archive.read(name) if archive else (ROOT / "bedrock" / name).read_bytes()
        ui = json.loads(resource("ui/astralrecord_quest_hud.json"))
        assert resource("quest-tracking-hud-version.txt") == b"2"
        texture = Image.open(io.BytesIO(resource("textures/ui/astralrecord/quest_hud_frame.png"))).convert("RGBA")
        slicing = json.loads(resource("textures/ui/astralrecord/quest_hud_frame.json"))
    frame = ui["frame"]
    label = frame["controls"][0]["quest_text"]
    variants = [next(iter(control.values())) for control in ui["panel"]["controls"][1:]]
    heights = [variant["$frame_height"] for variant in variants]
    frame_width = frame["size"][0]
    frame_x, frame_y = frame["offset"]
    text_x, text_y = label["offset"]
    row_pitch = 10 + label["line_padding"]
    assert heights == [6 + row * row_pitch for row in range(1, 11)]
    for row, variant in enumerate(variants, 1):
        assert variant["$row_tip_prefix"].endswith(f":{row:02}|")

    assert frame["type"] == "panel" and "texture" not in frame
    assert texture.size == tuple(slicing["base_size"])
    assert texture.getchannel("A").getbbox() is None, "背景は四隅・縁を含めて完全透過"

    if font_source:
        native_preview(output_path, ui, font_source, backdrop)
        return
    glyphs = load_glyphs()
    if "◆" not in glyphs:
        filled = glyphs["◇"].copy()
        alpha = filled.getchannel("A")
        left, top, right, bottom = alpha.getbbox()
        ImageDraw.floodfill(alpha, ((left + right) // 2, (top + bottom) // 2), 255)
        filled.putalpha(alpha)
        glyphs["◆"] = filled

    objectives = [
        "a◆ 坑道へ向かう  1/1",
        "a◆ 採掘道具を準備  1/1",
        "f◇ 鉄鉱石を採掘  2/5",
        "f◇ コウモリを討伐  1/3",
        "f◇ 松明を設置  0/4",
        "f◇ 貨車を調べる  0/1",
        "f◇ トロッコを修理  0/2",
        "f◇ 鉱石を納品  0/5",
    ]
    herb = ["6薬草採集の依頼", "f◇ 月見草を採集  3/5"]
    guard = ["6街道の見回り", "a◆ 街道を巡回  1/1"]
    delivery = ["6荷物の配達", "f◇ 荷物を届ける  0/1"]
    cases = [
        ("1 QUEST / OBJECTIVE OVERFLOW", False, False, [
            "6鉱山の調査  2/11", *objectives, "7他3件の条件",
        ]),
        ("3 QUESTS / 6 ROWS", True, True, herb + guard + delivery),
        ("2 QUESTS / QUEST OVERFLOW", True, True, [
            "6鉱山の調査  2/6", *objectives[:6], *herb, "7他1件の依頼",
        ]),
        ("CLEARED / 0 ROWS", True, True, []),
    ]
    output = Image.new("RGB", (960, 584), "#182029")
    for index, (heading, paper, coordinates, rows) in enumerate(cases):
        scene = Image.new("RGBA", (478, 270), "#33443f")
        draw = ImageDraw.Draw(scene)
        draw.polygon([(0, 165), (140, 72), (340, 123), (478, 85), (478, 270), (0, 270)], fill="#4c6048")
        draw.polygon([(0, 205), (195, 132), (375, 202), (478, 146), (478, 270), (0, 270)], fill="#5a6a51")
        draw.text((222, 12), heading, fill="#ffffff")
        draw.text((222, 28), "Static pack layout / substitute font", fill="#e0e7db")
        y = 6
        if paper:
            draw.rectangle((4, y, 28, y + 44), outline="#91ab9f")
            draw.text((35, y + 8), "Paper-doll padding", fill="#c0d0c4")
            y += 50
        if coordinates:
            draw.text((4, y), "Position: 16, 64, -8", fill="#ffffff")
            y += 10
        draw.text((4, y), "Days played: 12", fill="#ffffff")
        y += 10

        if rows:
            height = heights[len(rows) - 1]
            text_area = Image.new("RGBA", (frame_width - 8, height - 6))
            for row, value in enumerate(rows):
                draw_text(text_area, (0, row * row_pitch), value[1:], COLORS[value[0]], glyphs)
            scene.alpha_composite(text_area, (frame_x + text_x, y + frame_y + text_y))
            y += height

        draw.text((4, y + 1), "[Chat] A normal message", fill="#e2ebee")
        output.paste(scene.convert("RGB"), (2 + (index % 2) * 480, 2 + (index // 2) * 272))

    ImageDraw.Draw(output).text(
        (8, 557),
        "Native Bedrock glyph sizes, Pocket safe areas and HUD binding behavior require client verification.",
        fill="#bfcbd1",
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output.save(output_path)
    print(f"Saved static layout preview: {output_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview", type=Path, required=True)
    parser.add_argument("--pack", type=Path, help="Validate and preview the built .mcpack")
    parser.add_argument("--bedrock-font", type=Path, help="Local vanilla font directory or font.brarchive")
    parser.add_argument("--background", type=Path, help="Comparison scene for the native-font crop")
    args = parser.parse_args()
    render_preview(args.preview, args.pack, args.bedrock_font, args.background)
