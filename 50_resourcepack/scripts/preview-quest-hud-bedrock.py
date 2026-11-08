#!/usr/bin/env python3
"""Preview the Bedrock quest HUD layout with the pack frame and substitute glyphs."""

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw

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


def resize_frame(texture, width, height, slice_size):
    """Resize the pack's nine-slice texture without changing the edge thickness."""
    source_x = (0, slice_size, texture.width - slice_size, texture.width)
    source_y = (0, slice_size, texture.height - slice_size, texture.height)
    dest_x = (0, slice_size, width - slice_size, width)
    dest_y = (0, slice_size, height - slice_size, height)
    output = Image.new("RGBA", (width, height))
    for row in range(3):
        for column in range(3):
            tile = texture.crop(
                (source_x[column], source_y[row], source_x[column + 1], source_y[row + 1])
            )
            size = (dest_x[column + 1] - dest_x[column], dest_y[row + 1] - dest_y[row])
            output.paste(tile.resize(size, Image.Resampling.NEAREST), (dest_x[column], dest_y[row]))
    return output


def render_preview(output_path):
    definition = ROOT / "bedrock/ui/astralrecord_quest_hud.json"
    ui = json.loads(definition.read_text(encoding="utf-8"))
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

    frame_path = ROOT / "bedrock" / frame["texture"]
    texture = Image.open(frame_path.with_suffix(".png")).convert("RGBA")
    slicing = json.loads(frame_path.with_suffix(".json").read_text(encoding="utf-8"))
    slice_size = slicing["nineslice_size"]
    assert texture.size == tuple(slicing["base_size"])
    assert texture.crop(
        (slice_size, slice_size, texture.width - slice_size, texture.height - slice_size)
    ).getchannel("A").getbbox() is None

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
            scene.alpha_composite(resize_frame(texture, frame_width, height, slice_size), (frame_x, y + frame_y))
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
    render_preview(parser.parse_args().preview)
