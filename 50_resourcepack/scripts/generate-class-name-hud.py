#!/usr/bin/env python3
"""Generate the bitmap fonts and exact advances for full Java HUD class names.

Download the pinned original from FONT_SOURCE, then run:
    uv run --python 3.14 --with pillow==13.0.0 --with fonttools==4.68.0 \
        --with pyyaml==6.0.3 python 50_resourcepack/scripts/generate-class-name-hud.py FONT.ttf
Add --check to compare committed outputs or --preview FILE.png to render a class-name sample.
"""

import argparse
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont
import yaml

FONT_SOURCE = (
    "https://raw.githubusercontent.com/fontworks-fonts/DotGothic16/"
    "14517183ab2f75e8bccafc5a0bff6685d268c687/fonts/ttf/DotGothic16-Regular.ttf"
)
FONT_SHA256 = "155da8f318553c11d9dffc2affbc7c2114c6a46f9740bcf639ed5568af92be71"
PACK = Path(__file__).resolve().parents[1]
WORKSPACE = PACK.parent
METRICS = WORKSPACE / "10_plugin/AstralRecord/src/main/resources/class-name-glyph-advances.txt"
CLASS_DIRECTORY = WORKSPACE / "40_filebase/20.features.class"
LEGACY_FONT = "profile_hud_class_name"
COMPACT_FONT = "profile_hud_class_name_compact"
COLUMNS = 64
CELL_WIDTH = 12
CELL_HEIGHT = 39
INK_HEIGHT = 12
RASTER_ORIGIN = (3, 12)
SPACES = {" ": 4, "\u3000": 12}
COMPACT_CELL_WIDTH = 9
COMPACT_CELL_HEIGHT = 37
COMPACT_INK_HEIGHT = 9
COMPACT_SPACES = {" ": 3, "\u3000": 9}


def repertoire() -> str:
    """Use printable ASCII and JIS X 0208 punctuation, kana and level-one kanji."""
    chars = {chr(codepoint) for codepoint in range(0x21, 0x7F)}
    for row in (1, 4, 5, *range(16, 48)):
        for column in range(1, 95):
            encoded = bytes((0xA0 + row, 0xA0 + column))
            try:
                chars.add(encoded.decode("euc_jp"))
            except UnicodeDecodeError:
                continue
    return "".join(sorted(chars - SPACES.keys()))


def class_names() -> list[str]:
    """Read the display names and strip the color codes retained by the game renderer."""
    files = sorted(CLASS_DIRECTORY.glob("*.yml")) + sorted(CLASS_DIRECTORY.glob("*.yaml"))
    return [
        re.sub(r"(?i)[&§][0-9A-FK-OR]", "", yaml.safe_load(file.read_text(encoding="utf-8"))["name"])
        for file in files
    ]


def render_glyph(font: ImageFont.FreeTypeFont, char: str) -> Image.Image:
    bitmap = Image.new("L", (20, 20))
    ImageDraw.Draw(bitmap).text(RASTER_ORIGIN, char, anchor="ls", font=font, fill=255)
    bitmap = bitmap.point(lambda pixel: 255 if pixel >= 100 else 0)
    box = bitmap.getbbox()
    if box is None:
        raise ValueError(f"No visible pixels for U+{ord(char):04X}")
    x0, y0, x1, y1 = box
    left = x0 - RASTER_ORIGIN[0]
    if left < 0 or x1 - RASTER_ORIGIN[0] > CELL_WIDTH:
        raise ValueError(f"Glyph exceeds cell width: U+{ord(char):04X}")
    glyph = bitmap.crop(box)
    if glyph.height > INK_HEIGHT:
        glyph = glyph.resize((glyph.width, INK_HEIGHT), Image.Resampling.NEAREST)
    top = max(0, min(y0 - 1, INK_HEIGHT - glyph.height))
    cell = Image.new("L", (CELL_WIDTH, CELL_HEIGHT))
    cell.paste(glyph, (left, top))
    return cell


def compact_glyph(legacy: Image.Image) -> Image.Image:
    legacy_right = legacy.getbbox()[2]
    # The plugin calculates the same width from the legacy advance (right edge plus one).
    right = (legacy_right * 3 + 3) // 4
    coverage = legacy.crop((0, 0, legacy_right, INK_HEIGHT)).resize(
        (right, COMPACT_INK_HEIGHT), Image.Resampling.LANCZOS
    )
    glyph = coverage.point(lambda pixel: 255 if pixel >= 115 else 0)
    box = glyph.getbbox()
    if box is None or box[2] != right:
        # Keep the strongest part of a thin stroke if quantization removed the right edge.
        edge = [coverage.getpixel((right - 1, row)) for row in range(COMPACT_INK_HEIGHT)]
        glyph.putpixel((right - 1, edge.index(max(edge))), 255)
    cell = Image.new("L", (COMPACT_CELL_WIDTH, COMPACT_CELL_HEIGHT))
    cell.paste(glyph)
    return cell


def font_assets(
    stem: str, rows: list[str], glyphs: dict[str, Image.Image], spaces: dict[str, int]
) -> dict[Path, bytes]:
    cell_width, cell_height = next(iter(glyphs.values())).size
    alpha = Image.new("L", (cell_width * COLUMNS, cell_height * len(rows)))
    for index, glyph in enumerate(glyphs.values()):
        alpha.paste(glyph, (index % COLUMNS * cell_width, index // COLUMNS * cell_height))

    atlas = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    atlas.putalpha(alpha)
    buffer = BytesIO()
    atlas.save(buffer, format="PNG", optimize=True)

    definition = {
        "providers": [
            {"type": "space", "advances": spaces},
            {
                "type": "bitmap",
                "file": f"astralrecord:font/{stem}.png",
                "height": cell_height,
                "ascent": cell_height,
                "chars": rows,
            },
        ],
    }
    return {
        PACK / f"assets/astralrecord/textures/font/{stem}.png": buffer.getvalue(),
        PACK / f"assets/astralrecord/font/{stem}.json": (
            json.dumps(definition, ensure_ascii=True, indent=2) + "\n"
        ).encode("utf-8"),
    }


def assets(
    font_path: Path, names: list[str]
) -> tuple[dict[Path, bytes], dict[str, Image.Image], dict[str, Image.Image]]:
    if sha256(font_path.read_bytes()).hexdigest() != FONT_SHA256:
        raise ValueError(f"Expected the pinned DotGothic16 font from {FONT_SOURCE}")
    chars = repertoire()
    with TTFont(font_path) as source:
        cmap = source.getBestCmap()
        unsupported = [char for char in chars if ord(char) not in cmap]
    if unsupported:
        raise ValueError(f"Font lacks requested glyphs: {''.join(unsupported)}")
    font = ImageFont.truetype(font_path, 12, layout_engine=ImageFont.Layout.BASIC)
    glyphs = {char: render_glyph(font, char) for char in chars}
    compact = {char: compact_glyph(glyph) for char, glyph in glyphs.items()}
    advances = SPACES | {char: glyph.getbbox()[2] + 1 for char, glyph in glyphs.items()}
    for name in names:
        missing = set(name) - advances.keys()
        if missing:
            raise ValueError(f"Class name {name!r} lacks glyphs: {''.join(sorted(missing))}")

    rows = [chars[start : start + COLUMNS].ljust(COLUMNS, "\0") for start in range(0, len(chars), COLUMNS)]
    metrics = "".join(f"{ord(char):04X} {advance}\n" for char, advance in sorted(advances.items()))
    outputs = font_assets(LEGACY_FONT, rows, glyphs, SPACES) | font_assets(COMPACT_FONT, rows, compact, COMPACT_SPACES)
    outputs[METRICS] = metrics.encode("ascii")
    return outputs, glyphs, compact


def preview(names: list[str], glyphs: dict[str, Image.Image], compact: dict[str, Image.Image], path: Path) -> None:
    columns = (("12 px", glyphs, SPACES), ("9 px", compact, COMPACT_SPACES))
    legacy_advances = SPACES | {char: glyph.getbbox()[2] + 1 for char, glyph in glyphs.items()}
    column_width = max(sum(legacy_advances[char] for char in name) for name in names) + 16
    image = Image.new("RGB", (column_width * len(columns), 17 * (len(names) + 1) + 8), (25, 29, 38))
    pen = ImageDraw.Draw(image)
    for column, (label, images, spaces) in enumerate(columns):
        start = 8 + column * column_width
        pen.text((start, 4), label, fill=(177, 187, 205))
        for row, name in enumerate(names, start=1):
            x = start
            for char in name:
                if char in spaces:
                    x += spaces[char]
                    continue
                glyph = images[char]
                right = glyph.getbbox()[2]
                mask = glyph.crop((0, 0, right, INK_HEIGHT))
                y = 4 + row * 17
                image.paste((0, 0, 0), (x + 1, y + 1), mask)
                image.paste((243, 227, 182), (x, y), mask)
                x += right + 1
    image.resize((image.width * 3, image.height * 3), Image.Resampling.NEAREST).save(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, epilog=FONT_SOURCE)
    parser.add_argument("font", type=Path, help="local pinned DotGothic16-Regular.ttf")
    parser.add_argument("--check", action="store_true", help="verify the committed generated files")
    parser.add_argument("--preview", type=Path, help="write a sample of all full Filebase class names")
    args = parser.parse_args()
    names = class_names()
    outputs, glyphs, compact = assets(args.font, names)
    differences = []
    for path, expected in outputs.items():
        actual = path.read_bytes() if path.is_file() else b""
        # Text checkouts can use CRLF according to the local Git settings.
        if path.suffix != ".png":
            actual = actual.replace(b"\r\n", b"\n")
        if actual == expected:
            continue
        if args.check:
            differences.append(path.relative_to(WORKSPACE))
        else:
            path.write_bytes(expected)
    if differences:
        parser.error("generated files differ: " + ", ".join(str(path) for path in differences))
    if args.preview:
        preview(names, glyphs, compact, args.preview)
    print(f"{len(glyphs)} glyphs per font, {len(names)} full class names")


if __name__ == "__main__":
    main()
