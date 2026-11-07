"""Generate the deterministic Java and Bedrock combat reward plaque artwork."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "assets/astralrecord"
BEDROCK = ROOT / "bedrock"
WIDTH, HEIGHT = 192, 144

PALETTE = {
    "void": (7, 13, 28, 0),
    "navy_dark": (13, 24, 44, 255),
    "navy": (22, 37, 61, 248),
    "navy_light": (31, 47, 72, 248),
    "gold_shadow": (91, 53, 20, 255),
    "gold_dark": (151, 101, 36, 255),
    "gold": (223, 177, 83, 255),
    "gold_light": (255, 226, 145, 255),
    "cream": (255, 246, 211, 255),
    "green": (121, 224, 119, 255),
    "purple_shadow": (61, 28, 100, 255),
    "purple_dark": (93, 49, 151, 255),
    "purple": (150, 92, 223, 255),
    "purple_light": (226, 192, 255, 255),
}


def rect(draw: ImageDraw.ImageDraw, xy: tuple[int, int, int, int], color: str) -> None:
    draw.rectangle(xy, fill=PALETTE[color])


def line(draw: ImageDraw.ImageDraw, xy: tuple[int, int, int, int], color: str) -> None:
    draw.line(xy, fill=PALETTE[color], width=1)


def star(draw: ImageDraw.ImageDraw, cx: int, cy: int, color: str, small: bool = False) -> None:
    rect(draw, (cx, cy - 4, cx, cy + 4), color)
    rect(draw, (cx - 4, cy, cx + 4, cy), color)
    if not small:
        rect(draw, (cx - 2, cy - 2, cx + 2, cy + 2), "cream")


def shield(draw: ImageDraw.ImageDraw, rare: bool) -> None:
    """Draw the crossed-swords crest centered above the upper frame rail."""
    # stepped shield silhouette, shadow, metal rim, and inset
    for dx, dy, color in ((0, 2, "gold_shadow"), (0, 1, "gold_dark"), (0, 0, "gold")):
        x0, y0 = 82 + dx, 2 + dy
        rect(draw, (x0 + 4, y0, x0 + 20, y0 + 2), color)
        rect(draw, (x0 + 2, y0 + 2, x0 + 22, y0 + 15), color)
        rect(draw, (x0 + 4, y0 + 15, x0 + 20, y0 + 19), color)
        rect(draw, (x0 + 7, y0 + 19, x0 + 17, y0 + 22), color)
        rect(draw, (x0 + 10, y0 + 22, x0 + 14, y0 + 24), color)
    rect(draw, (88, 5, 103, 17), "navy_dark")
    rect(draw, (86, 7, 105, 17), "navy_dark")
    rect(draw, (89, 17, 102, 20), "navy_dark")
    rect(draw, (92, 20, 99, 22), "navy_dark")
    # crossed short swords and central gold hilt details
    line(draw, (90, 7, 101, 17), "cream")
    line(draw, (101, 7, 90, 17), "gold_light")
    rect(draw, (88, 6, 92, 7), "gold_light")
    rect(draw, (99, 6, 103, 7), "cream")
    rect(draw, (89, 16, 93, 17), "gold")
    rect(draw, (98, 16, 102, 17), "gold")
    rect(draw, (94, 10, 97, 13), "gold_light")
    # small laurel-like gold pixels at both sides of the crest
    for side in (-1, 1):
        for x, y in ((78, 9), (76, 12), (78, 15), (80, 18), (82, 20)):
            xx = x if side < 0 else WIDTH - x - 2
            rect(draw, (xx, y, xx + 2, y + 2), "gold")
            rect(draw, (xx, y, xx, y), "gold_light")
    if rare:
        rect(draw, (84, 23, 85, 24), "purple_light")
        rect(draw, (106, 23, 107, 24), "purple_light")


def frame(rare: bool, sparkle: bool = True, panel_height: int = HEIGHT,
          bedrock_art: bool = False) -> Image.Image:
    image = Image.new("RGBA", (WIDTH, panel_height), PALETTE["void"])
    d = ImageDraw.Draw(image)
    bottom = panel_height - 6

    # Main recessed panel, clipped corners, and solid dark text field.
    rect(d, (5, 31, 186, bottom + 2), "gold_shadow")
    rect(d, (4, 30, 187, bottom), "gold_dark")
    rect(d, (6, 32, 185, bottom - 2), "gold")
    rect(d, (8, 34, 183, bottom - 4), "navy_dark")
    rect(d, (10, 36, 181, bottom - 6), "navy")
    rect(d, (12, 38, 179, bottom - 8), "navy_light")
    rect(d, (13, 39, 178, bottom - 9), "navy")

    # Stepped corner cuts and the antique double bevel.
    for xside in (0, 1):
        left = xside == 0
        for y, inset in ((30, 5), (31, 3), (32, 2), (33, 1), (34, 0),
                         (bottom - 4, 0), (bottom - 3, 1), (bottom - 2, 2),
                         (bottom - 1, 3), (bottom, 5)):
            x = 4 + inset if left else 187 - inset
            rect(d, (x, y, x + (0 if left else 0), y), "navy_dark")
        # Fine inner gold highlight follows the upper and lower rails.
    line(d, (10, 33, 80, 33), "gold_light")
    line(d, (112, 33, 181, 33), "gold_light")
    line(d, (9, 35, 9, bottom - 6), "gold_light")
    line(d, (182, 35, 182, bottom - 6), "gold_light")
    line(d, (10, bottom - 3, 181, bottom - 3), "gold_light")
    line(d, (7, 31, 80, 31), "gold_dark")
    line(d, (112, 31, 184, 31), "gold_dark")
    line(d, (6, 36, 6, bottom - 6), "gold_dark")
    line(d, (185, 36, 185, bottom - 6), "gold_dark")
    line(d, (8, bottom, 183, bottom), "gold_dark")

    # Plaque wings at top meet the crest, leaving a clear line of text below it.
    rect(d, (18, 28, 80, 31), "gold_dark")
    rect(d, (112, 28, 173, 31), "gold_dark")
    rect(d, (19, 27, 80, 28), "gold_light")
    rect(d, (112, 27, 172, 28), "gold_light")
    shield(d, rare)

    # Gold divider/star; text occupies clear navy field above it.
    # A clear slot is reserved here: Java inserts U+E709 as its own centered
    # bitmap line, while Bedrock's normal overlay uses a textual separator.

    # Java uses a framed item slot that follows the final text line. Bedrock
    # lays out the row in its entity geometry, so avoid a raster rule there.
    row_top = 77
    row_bottom = min(92, bottom - 4)
    if not bedrock_art:
        rect(d, (13, row_top - 2, 178, row_bottom), "navy_dark")
        rect(d, (14, row_top - 1, 177, row_bottom - 1), "navy")
        line(d, (15, row_top - 2, 176, row_top - 2), "gold_dark" if rare else "gold_shadow")
        rect(d, (15, row_top, 33, row_bottom - 2), "navy_light")
        line(d, (34, row_top - 1, 34, row_bottom - 2), "gold_dark" if rare else "gold_shadow")

    if rare:
        # Purple inset corner facets, tiny twinkles, and the loot row bevel.
        for side in (0, 1):
            x = 13 if side == 0 else 173
            rect(d, (x, 36, x + 3, 38), "purple_dark")
            rect(d, (x + (0 if side == 0 else 2), 38, x + (2 if side == 0 else 3), 42), "purple")
            rect(d, (x + (2 if side == 0 else 0), 42, x + 6, 44), "purple_dark")
        if not bedrock_art:
            line(d, (16, row_top, 176, row_top), "purple_light")
            line(d, (16, row_bottom - 1, 176, row_bottom - 1), "purple_dark")
        if sparkle:
            if not bedrock_art:
                star(d, 16, row_top - 4, "purple_light", small=True)
                star(d, 175, row_top - 4, "purple_light", small=True)
                star(d, 16, row_bottom + 1, "gold_light", small=True)
                star(d, 176, row_bottom + 1, "gold_light", small=True)
    return image


# 16x16 pixel silhouettes, indexed in the Java/Bedrock E700-E708 contract.
ICON_PIXELS = {
    0: ["................", ".....######.....", "....########....", "...##########...", "...##########...", "...##########...", "....########....", ".....######.....", "......####......", "................", "................", "................", "................", "................", "................", "................"],
    1: ["................", "....########....", "...##########...", "..###########...", "..##########....", "...########.....", "....######......", ".....#####......", ".....####.......", "....#####.......", "...######.......", "..######........", "..#####.........", "..####..........", "................", "................"],
    2: [".......#........", "......###.......", ".....#####......", "...#########....", ".....#####......", "......###.......", ".......#........", "....#######.....", ".....#####......", "....#######.....", "......###.......", ".....#####......", "...#########....", ".......#........", "................", "................"],
    3: ["........##......", ".......####.....", "......####......", ".....####.......", "....####........", "...####.........", "..####..........", ".####...........", "...#............", "..###...........", ".#####..........", "..###...........", "..##............", ".##.............", "................", "................"],
    4: ["......####......", ".....######.....", ".....######.....", "......####......", "......####......", "......####......", ".....######.....", "....########....", "....########....", ".....######.....", "......####......", "......####......", "......####......", "......####......", "................", "................"],
    5: ["................", "................", "....########....", "...##########...", "..############..", "..############..", "..############..", "...##########...", "....########....", "......####......", ".....######.....", "................", "................", "................", "................", "................"],
    6: [".......##.......", "......####......", ".....######.....", "....########....", "...##########...", "..############..", "..############..", "...##########...", "....########....", ".....######.....", "......####......", ".......##.......", "................", "................", "................", "................"],
    7: [".....######.....", "....########....", "...##########...", "..############..", "..############..", "...##########...", "....########....", ".....######.....", "......####......", "......####......", "......####......", "......####......", "................", "................", "................", "................"],
    8: ["...##########...", "..############..", "..##........##..", "..##........##..", "..##........##..", "..##........##..", "..##........##..", "..############..", "...##########...", "......####......", ".....######.....", "....########....", "................", "................", "................", "................"],
}


def paint_icons() -> Image.Image:
    image = Image.new("RGBA", (144, 16), PALETTE["void"])
    draw = ImageDraw.Draw(image)
    colors = ["cream", "gold", "purple_light", "gold_light", "purple", "gold", "gold_light", "gold", "gold"]
    for index, rows in ICON_PIXELS.items():
        for y, row in enumerate(rows):
            for x, pixel in enumerate(row):
                if pixel == ".":
                    continue
                color = colors[index]
                # Gold/dark outline gives tiny glyphs contrast on both navy and world backgrounds.
                draw.point((index * 16 + x, y), fill=PALETTE[color])
    return image


def divider_glyph() -> Image.Image:
    image = Image.new("RGBA", (151, 6), PALETTE["void"])
    d = ImageDraw.Draw(image)
    rect(d, (0, 3, 67, 3), "gold_dark")
    rect(d, (0, 2, 67, 2), "gold_light")
    rect(d, (83, 3, 149, 3), "gold_dark")
    rect(d, (83, 2, 149, 2), "gold_light")
    rect(d, (75, 0, 75, 4), "gold")
    rect(d, (73, 2, 77, 2), "gold")
    rect(d, (74, 1, 76, 3), "cream")
    return image


def rare_glint_sprite() -> Image.Image:
    image = Image.new("RGBA", (16, 16), PALETTE["void"])
    d = ImageDraw.Draw(image)
    rect(d, (7, 0, 8, 15), "purple_dark")
    rect(d, (0, 7, 15, 8), "purple_dark")
    rect(d, (7, 2, 8, 13), "purple_light")
    rect(d, (2, 7, 13, 8), "purple_light")
    rect(d, (6, 6, 9, 9), "cream")
    rect(d, (7, 1, 8, 3), "gold_light")
    rect(d, (1, 7, 3, 8), "gold_light")
    return image


def font_json() -> dict:
    providers = []
    for prefix, base in (("normal", 0xE710), ("rare", 0xE720), ("rare_glint", 0xE750)):
        for line_count in range(5, 11):
            providers.append({
                "type": "bitmap",
                "file": f"astralrecord:font/combat_reward_plaque_{prefix}_{line_count}.png",
                "height": 46 + (line_count - 1) * 10 + 12,
                "ascent": 46,
                "chars": [chr(base + line_count - 5)],
            })
    providers.extend((
        {"type": "bitmap", "file": "astralrecord:font/combat_reward_plaque_icons.png",
         "height": 10, "ascent": 8, "chars": ["".join(chr(0xE700 + i) for i in range(9))]},
        {"type": "bitmap", "file": "astralrecord:font/combat_reward_plaque_divider.png",
         "height": 6, "ascent": 4, "chars": ["\ue709"]},
        {"type": "space", "advances": {
            chr(0xE731): 192,
            chr(0xE740): 1, chr(0xE741): 2, chr(0xE742): 4, chr(0xE743): 8,
            chr(0xE744): 16, chr(0xE745): 32, chr(0xE746): 64, chr(0xE747): 128,
        }},
        {"type": "reference", "id": "minecraft:default"},
    ))
    return {"providers": providers}


def outputs() -> dict[Path, bytes]:
    result: dict[Path, bytes] = {}
    for rare, prefix in ((False, "normal"), (True, "rare")):
        bedrock_texture = Image.new("RGBA", (256, 256), PALETTE["void"])
        bedrock_texture.alpha_composite(frame(rare, True, HEIGHT, bedrock_art=True), (0, 0))
        if rare:
            bedrock_texture.alpha_composite(rare_glint_sprite(), (192, 0))
        result[BEDROCK / f"textures/entity/combat_reward_plaque/{prefix}.png"] = png(bedrock_texture)
        for line_count in range(5, 11):
            crop_height = 46 + (line_count - 1) * 10 + 12
            line_frame = frame(rare, prefix == "rare_glint", crop_height)
            result[JAVA / f"textures/font/combat_reward_plaque_{prefix}_{line_count}.png"] = png(line_frame)
            if prefix != "normal":
                glint = frame(True, True, crop_height)
                # E750-E755 pairs with E720-E725 for a gentle corner-glint pulse.
                result[JAVA / f"textures/font/combat_reward_plaque_rare_glint_{line_count}.png"] = png(glint)
    result[JAVA / "textures/font/combat_reward_plaque_icons.png"] = png(paint_icons())
    result[JAVA / "textures/font/combat_reward_plaque_divider.png"] = png(divider_glyph())
    result[JAVA / "font/combat_reward_plaque.json"] = (json.dumps(font_json(), ensure_ascii=True, indent=2) + "\n").encode()
    icon_atlas = Image.new("RGBA", (256, 256), PALETTE["void"])
    icons = paint_icons()
    icon_atlas.alpha_composite(icons, (0, 0))
    result[BEDROCK / "font/glyph_E7.png"] = png(icon_atlas)
    result[ROOT / "combat-reward-plaque-version.txt"] = b"1"
    return result


def preview_panel(rare: bool) -> Image.Image:
    style = "rare_glint" if rare else "normal"
    with Image.open(JAVA / f"textures/font/combat_reward_plaque_{style}_5.png") as source:
        image = source.copy()
    d = ImageDraw.Draw(image)
    d.fontmode = "1"
    try:
        font = ImageFont.truetype("msgothic.ttc", 9)
    except OSError:
        font = ImageFont.load_default()
    header = [("討伐報酬", "cream"), ("ミズガルズ・グラスボア", "green"), ("経験値 +1", "gold_light")]
    for index, (label, color) in enumerate(header):
        d.text((WIDTH // 2, 46 + index * 10), label, font=font, fill=PALETTE[color], anchor="ms")
    image.alpha_composite(divider_glyph(), (20, 72))
    icon_atlas = paint_icons()
    icon_index = 2 if rare else 1
    icon = icon_atlas.crop((icon_index * 16, 0, (icon_index + 1) * 16, 16))
    image.alpha_composite(icon.resize((10, 10), Image.Resampling.NEAREST), (16, 78))
    name = "星屑の結晶" if rare else "グラスボアの毛皮"
    suffix = " ×1 | 0.1% ◆ レア" if rare else " ×1 | 35%"
    d.text((40, 86), name, font=font, fill=PALETTE["purple_light" if rare else "green"], anchor="ls")
    d.text((40 + int(d.textlength(name, font=font)), 86), suffix, font=font,
           fill=PALETTE["cream"], anchor="ls")
    return image


def png(image: Image.Image) -> bytes:
    from io import BytesIO
    output = BytesIO()
    image.save(output, format="PNG", optimize=False, compress_level=9)
    return output.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if generated files differ")
    parser.add_argument("--preview", type=Path, help="write a side-by-side preview PNG")
    args = parser.parse_args()
    generated = outputs()
    if args.check:
        stale = []
        for path, content in generated.items():
            current = path.read_bytes() if path.is_file() else None
            if current is not None and path.suffix == ".json":
                current = current.replace(b"\r\n", b"\n")
            if current != content:
                stale.append(str(path.relative_to(ROOT)))
        if stale:
            raise SystemExit("Generated reward plaque assets differ: " + ", ".join(stale))
    else:
        for path, content in generated.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if args.preview:
        normal = preview_panel(False)
        rare = preview_panel(True)
        preview = Image.new("RGBA", (WIDTH * 2 + 16, max(normal.height, rare.height) + 8), (90, 150, 220, 255))
        preview.alpha_composite(normal, (0, 4))
        preview.alpha_composite(rare, (WIDTH + 16, 4))
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        preview.resize((preview.width * 3, preview.height * 3), Image.Resampling.NEAREST).save(
            args.preview, format="PNG", optimize=False, compress_level=9)


if __name__ == "__main__":
    main()
