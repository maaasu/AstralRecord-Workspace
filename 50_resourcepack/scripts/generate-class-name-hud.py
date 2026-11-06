#!/usr/bin/env python3
"""Generate the bitmap font and exact advances for full Java HUD class names.

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
ATLAS = PACK / "assets/astralrecord/textures/font/profile_hud_class_name.png"
DEFINITION = PACK / "assets/astralrecord/font/profile_hud_class_name.json"
METRICS = WORKSPACE / "10_plugin/AstralRecord/src/main/resources/class-name-glyph-advances.txt"
CLASS_DIRECTORY = WORKSPACE / "40_filebase/20.features.class"
COLUMNS = 64
CELL_WIDTH = 12
CELL_HEIGHT = 39
INK_HEIGHT = 12
RASTER_ORIGIN = (3, 12)
SPACES = {" ": 4, "\u3000": 12}


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


def assets(font_path: Path, names: list[str]) -> tuple[dict[Path, bytes], dict[str, Image.Image]]:
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
    advances = SPACES | {char: glyph.getbbox()[2] + 1 for char, glyph in glyphs.items()}
    for name in names:
        missing = set(name) - advances.keys()
        if missing:
            raise ValueError(f"Class name {name!r} lacks glyphs: {''.join(sorted(missing))}")

    rows = [chars[start : start + COLUMNS].ljust(COLUMNS, "\0") for start in range(0, len(chars), COLUMNS)]
    alpha = Image.new("L", (CELL_WIDTH * COLUMNS, CELL_HEIGHT * len(rows)))
    for index, glyph in enumerate(glyphs.values()):
        alpha.paste(glyph, (index % COLUMNS * CELL_WIDTH, index // COLUMNS * CELL_HEIGHT))
    atlas = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    atlas.putalpha(alpha)
    buffer = BytesIO()
    atlas.save(buffer, format="PNG", optimize=True)

    definition = {
        "providers": [
            {"type": "space", "advances": SPACES},
            {
                "type": "bitmap",
                "file": "astralrecord:font/profile_hud_class_name.png",
                "height": CELL_HEIGHT,
                "ascent": CELL_HEIGHT,
                "chars": rows,
            },
        ],
    }
    metrics = "".join(f"{ord(char):04X} {advance}\n" for char, advance in sorted(advances.items()))
    return {
        ATLAS: buffer.getvalue(),
        DEFINITION: (json.dumps(definition, ensure_ascii=True, indent=2) + "\n").encode("utf-8"),
        METRICS: metrics.encode("ascii"),
    }, glyphs


def preview(names: list[str], glyphs: dict[str, Image.Image], path: Path) -> None:
    widths = {char: glyph.getbbox()[2] + 1 for char, glyph in glyphs.items()} | SPACES
    width = max(sum(widths[char] for char in name) for name in names)
    image = Image.new("RGB", (width + 16, 17 * len(names) + 8), (25, 29, 38))
    for row, name in enumerate(names):
        x = 8
        for char in name:
            if char in glyphs:
                glyph = glyphs[char].crop((0, 0, CELL_WIDTH, INK_HEIGHT))
                y = 4 + row * 17
                image.paste((0, 0, 0), (x + 1, y + 1, x + CELL_WIDTH + 1, y + INK_HEIGHT + 1), glyph)
                image.paste((243, 227, 182), (x, y, x + CELL_WIDTH, y + INK_HEIGHT), glyph)
            x += widths[char]
    image.resize((image.width * 3, image.height * 3), Image.Resampling.NEAREST).save(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, epilog=FONT_SOURCE)
    parser.add_argument("font", type=Path, help="local pinned DotGothic16-Regular.ttf")
    parser.add_argument("--check", action="store_true", help="verify the committed generated files")
    parser.add_argument("--preview", type=Path, help="write a sample of all full Filebase class names")
    args = parser.parse_args()
    names = class_names()
    outputs, glyphs = assets(args.font, names)
    if args.check:
        # Text checkouts can use CRLF according to the local Git settings.
        differences = [
            path.relative_to(WORKSPACE)
            for path, expected in outputs.items()
            if not path.is_file()
            or (path.read_bytes() if path == ATLAS else path.read_bytes().replace(b"\r\n", b"\n")) != expected
        ]
        if differences:
            parser.error("generated files differ: " + ", ".join(str(path) for path in differences))
    else:
        for path, content in outputs.items():
            path.write_bytes(content)
    if args.preview:
        preview(names, glyphs, args.preview)
    print(f"{len(glyphs)} glyphs, {len(names)} full class names, {len(outputs[ATLAS]):,} atlas bytes")


if __name__ == "__main__":
    main()
