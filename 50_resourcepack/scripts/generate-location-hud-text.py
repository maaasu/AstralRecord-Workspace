#!/usr/bin/env python3
"""Generate the 4x Java location HUD bitmap font with existing compact advances.

Download FONT_SOURCE and run with Pillow 13.0.0 (FreeType 2.14.3):
    uv run --with pillow==13.0.0 python 50_resourcepack/scripts/generate-location-hud-text.py FONT.ttf
Use --check alone to verify committed metrics or --check FONT.ttf to compare regenerated pixels.
"""

import argparse
from hashlib import sha256
import json
from pathlib import Path

from PIL import Image, ImageFont


PACK = Path(__file__).resolve().parents[1]
FONT_DIRECTORY = PACK / "assets/astralrecord/font"
TEXTURE_DIRECTORY = PACK / "assets/astralrecord/textures/font"
LICENSE = PACK / "licenses/OFL-NotoSansJP.txt"
METRICS = PACK.parent / "10_plugin/AstralRecord/src/main/resources/class-name-glyph-advances.txt"
SOURCE_FONT = "profile_hud_class_name_compact"
OUTPUT_FONT = "location_hud_text"
FONT_SOURCE = (
    "https://raw.githubusercontent.com/google/fonts/295d98a7a0c17c68f1341eaeea354e7960ea70d3/"
    "ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf"
)
FONT_SHA256 = "c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f"
LICENSE_SHA256 = "babcfe66c8a098b2fa279bc724a3a342f8124f77ce18941fbcc1bbb39823cded"
SCALE = 4
CELL_WIDTH = 9
CELL_HEIGHT = 11
INK_HEIGHT = 9
LATIN_CAP_HEIGHT = 8
RASTER_SIZE = 144
WEIGHT = 570


def compact_advances():
    """Match ClassNameGlyphs.advance for spaces and bitmap glyphs."""
    advances = {}
    for line in METRICS.read_text(encoding="ascii").splitlines():
        codepoint, width = line.split()
        char = chr(int(codepoint, 16))
        advance = int(width)
        if char in (" ", "\u3000"):
            advances[char] = advance * 3 // 4
        else:
            advances[char] = ((advance - 1) * 3 + 3) // 4 + 1
    return advances


def cells(alpha, rows):
    width = alpha.width // len(rows[0])
    height = alpha.height // len(rows)
    for y, row in enumerate(rows):
        for x, char in enumerate(row):
            yield char, (x * width, y * height, (x + 1) * width, (y + 1) * height)


def source_definition():
    compact = json.loads((FONT_DIRECTORY / f"{SOURCE_FONT}.json").read_text(encoding="utf-8"))
    spaces, bitmap = compact["providers"]
    return {
        "providers": [
            spaces,
            {
                "type": "bitmap",
                "file": f"astralrecord:font/{OUTPUT_FONT}.png",
                "height": CELL_HEIGHT,
                "ascent": -4096,
                "chars": bitmap["chars"],
            },
        ]
    }


def render_glyph(font, char, left, right):
    bitmap, (_, offset_y) = font.getmask2(char, mode="L", anchor="ls")
    raster = Image.frombytes("L", bitmap.size, bytes(bitmap))
    box = raster.getbbox()
    if box is None:
        raise ValueError(f"No visible glyph for U+{ord(char):04X}")

    # Align Latin to its cap-height baseline and CJK to the fullwidth body.
    # This keeps lowercase and punctuation at their natural heights.
    raster = raster.crop(box)
    cjk = ord(char) >= 0x3000
    cap_height = -font.getbbox("H", anchor="ls")[1]
    ratio = SCALE * (INK_HEIGHT / 137 if cjk else LATIN_CAP_HEIGHT / cap_height)
    baseline = (8 if cjk else INK_HEIGHT) * SCALE
    max_height = (INK_HEIGHT if cjk else CELL_HEIGHT) * SCALE
    height = max(1, min(max_height, round(raster.height * ratio)))
    top = max(0, min(max_height - height, baseline + round((offset_y + box[1]) * ratio)))
    glyph = raster.resize((right - left, height), Image.Resampling.LANCZOS)
    coverage = Image.new("L", (right, CELL_HEIGHT * SCALE))
    coverage.paste(glyph, (left, top))

    # Preserve thin strokes under Minecraft's point sampling at GUI scale 1.
    averaged = coverage.resize((right // SCALE, CELL_HEIGHT), Image.Resampling.BOX)
    averaged = averaged.resize(coverage.size, Image.Resampling.NEAREST)
    coverage = Image.blend(coverage, averaged, 0.45)
    ink = coverage.point(lambda pixel: 0 if pixel < 15 else min(255, pixel * 7 // 5))
    bounds = ink.getbbox()
    if bounds is None or bounds[2] != right:
        # The final nontransparent column determines the game's bitmap advance.
        edge = [coverage.getpixel((right - 1, row)) for row in range(top, top + height)]
        peak = max(edge)
        ink.putpixel((right - 1, top + edge.index(peak)), max(32, peak))
    return ink


def render_atlas(font_path, rows, advances):
    if sha256(font_path.read_bytes()).hexdigest() != FONT_SHA256:
        raise ValueError(f"Expected pinned Noto Sans JP from {FONT_SOURCE}")
    font = ImageFont.truetype(font_path, RASTER_SIZE, layout_engine=ImageFont.Layout.BASIC)
    font.set_variation_by_axes([WEIGHT])
    with Image.open(TEXTURE_DIRECTORY / f"{SOURCE_FONT}.png") as source:
        compact = source.getchannel("A")
    alpha = Image.new("L", (len(rows[0]) * CELL_WIDTH * SCALE, len(rows) * CELL_HEIGHT * SCALE))
    for (char, bounds), (_, compact_bounds) in zip(cells(alpha, rows), cells(compact, rows), strict=True):
        if char == "\0":
            continue
        # Retain compact side bearings, especially the offset of brackets and punctuation.
        left = compact.crop(compact_bounds).getbbox()[0] * SCALE // 2
        if char.isascii() and char.isalnum():
            left = max(0, left - 1)
        right = (advances[char] - 1) * SCALE
        alpha.paste(render_glyph(font, char, left, right), bounds[:2])
    atlas = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    atlas.putalpha(alpha)
    return atlas


def verify_atlas(atlas, definition, advances):
    spaces, bitmap = definition["providers"]
    rows = bitmap["chars"]
    size = (len(rows[0]) * CELL_WIDTH * SCALE, len(rows) * CELL_HEIGHT * SCALE)
    if atlas.mode != "RGBA" or atlas.size != size:
        raise ValueError(f"Expected an RGBA atlas of size {size}")
    if atlas.getextrema()[:3] != ((255, 255),) * 3:
        raise ValueError("Expected white glyphs for shader tinting")
    measured = dict(spaces["advances"])
    alpha = atlas.getchannel("A")
    for char, bounds in cells(alpha, rows):
        ink = alpha.crop(bounds).getbbox()
        if char == "\0" and ink is None:
            continue
        if ink is None or char in measured or char == "\0":
            raise ValueError(f"Missing, duplicate or misplaced glyph U+{ord(char):04X}")
        # BitmapProvider rounds the ink width divided by the raster scale, then adds one.
        measured[char] = (ink[2] + SCALE // 2) // SCALE + 1
        if measured[char] != advances.get(char):
            raise ValueError(f"Incorrect advance for U+{ord(char):04X}: {measured[char]}")
        if ord(char) >= 0x3000 and ink[3] > INK_HEIGHT * SCALE:
            raise ValueError(f"Japanese glyph exceeds the ink height: U+{ord(char):04X}")
    if measured != advances:
        raise ValueError("Atlas repertoire or space advances differ from the plugin metrics")


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=FONT_SOURCE)
    parser.add_argument("font", type=Path, nargs="?", help="pinned local Noto Sans JP variable TTF")
    parser.add_argument("--check", action="store_true", help="verify committed atlas and definition")
    args = parser.parse_args()
    if not args.check and args.font is None:
        parser.error("provide the pinned TTF to regenerate, or use --check")

    try:
        if sha256(LICENSE.read_bytes().replace(b"\r\n", b"\n")).hexdigest() != LICENSE_SHA256:
            raise ValueError("The bundled OFL notice differs from the pinned upstream license")
        definition = source_definition()
        _, bitmap = definition["providers"]
        advances = compact_advances()
        font_file = FONT_DIRECTORY / f"{OUTPUT_FONT}.json"
        texture_file = TEXTURE_DIRECTORY / f"{OUTPUT_FONT}.png"
        rendered = render_atlas(args.font, bitmap["chars"], advances) if args.font else None
        if args.check:
            if json.loads(font_file.read_text(encoding="utf-8")) != definition:
                raise ValueError(f"Font definition differs: {font_file}")
            with Image.open(texture_file) as atlas:
                verify_atlas(atlas, definition, advances)
                if rendered is not None and (atlas.size != rendered.size or atlas.tobytes() != rendered.tobytes()):
                    raise ValueError(f"Atlas pixels differ: {texture_file}")
        else:
            verify_atlas(rendered, definition, advances)
            font_file.write_text(json.dumps(definition, ensure_ascii=True, indent=2) + "\n", encoding="utf-8", newline="\n")
            rendered.save(texture_file, optimize=True)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(f"Location HUD font: {len(advances)} advances verified at {SCALE}x resolution.")


if __name__ == "__main__":
    main()
