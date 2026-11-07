#!/usr/bin/env python3
"""Build and preview the Java action-bar buff icon font from 10px PNG originals."""

import argparse
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re

from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1]
ASSETS = PACK / "assets/astralrecord"
ATLAS = ASSETS / "textures/font/buff_hud.png"
FONT = ASSETS / "font/buff_hud.json"
FINGERPRINT = PACK / "buff-hud-assets.sha256"
SIZE = 10
CELL_HEIGHT = 79
COLUMNS = 16
FIRST_GLYPH = 0xEA00
CAPACITY = 256
TEXTURE_PATH = re.compile(r"astralrecord:(buff_hud/[a-z0-9]+(?:_[a-z0-9]+)*\.png)")
FALLBACKS = ["astralrecord:buff_hud/general_buff.png", "astralrecord:buff_hud/general_debuff.png"]


def registry() -> list[str]:
    config = json.loads((PACK / "resourcepack.config.json").read_text(encoding="utf-8"))
    reference = "astralRecordPluginResources"
    resources = config["absolutePathOverrides"].get(reference) or config["references"][reference]
    icons = json.loads((PACK / resources / "buff-hud-icons.json").read_text(encoding="utf-8"))
    if not isinstance(icons, list) or icons[:2] != FALLBACKS or len(icons) > CAPACITY:
        raise ValueError("buff-hud-icons.json must start with both fallback icons and reserve U+EA00 through U+EAFF.")
    return icons


def font_assets(icons: list[str]) -> tuple[dict[Path, bytes], list[Image.Image]]:
    hashes = []
    sprites = []
    used = set()
    for texture in icons:
        match = TEXTURE_PATH.fullmatch(texture) if isinstance(texture, str) else None
        if not match or texture in used:
            raise ValueError(f"Invalid or duplicate buff HUD texture: {texture}")
        used.add(texture)
        source = (ASSETS / "textures" / match[1]).read_bytes()
        with Image.open(BytesIO(source)) as sprite:
            if sprite.format != "PNG" or sprite.mode != "RGBA" or sprite.size != (SIZE, SIZE):
                raise ValueError(f"{texture} must be a {SIZE}x{SIZE} RGBA PNG.")
            if sprite.getchannel("A").crop((SIZE - 1, 0, SIZE, SIZE)).getbbox() is None:
                raise ValueError(f"{texture} requires a visible last column for an {SIZE + 1}px advance.")
            sprites.append(sprite.copy())
        hashes.append(f"{texture} {sha256(source).hexdigest()}")

    columns = min(COLUMNS, len(icons))
    chars = "".join(chr(FIRST_GLYPH + index) for index in range(len(icons)))
    rows = [chars[i : i + columns].ljust(columns, "\0") for i in range(0, len(chars), columns)]
    atlas = Image.new("RGBA", (columns * SIZE, len(rows) * CELL_HEIGHT))
    for index, sprite in enumerate(sprites):
        atlas.paste(sprite, (index % columns * SIZE, index // columns * CELL_HEIGHT))

    buffer = BytesIO()
    atlas.save(buffer, format="PNG", optimize=True)
    atlas_bytes = buffer.getvalue()
    spaces = {chr(0xE950 + index): 2**index for index in range(5)}
    spaces.update({chr(0xE955 + index): -(2**index) for index in range(5)})
    definition = {"providers": [
        {"type": "space", "advances": spaces},
        {
            "type": "bitmap",
            "file": "astralrecord:font/buff_hud.png",
            "height": CELL_HEIGHT,
            "ascent": CELL_HEIGHT,
            "chars": rows,
        },
    ]}
    font_bytes = (json.dumps(definition, ensure_ascii=True, indent=2) + "\n").encode("utf-8")
    hashes.extend([sha256(atlas_bytes).hexdigest(), sha256(font_bytes).hexdigest()])
    fingerprint = sha256("\n".join(hashes).encode("ascii")).hexdigest()
    return {ATLAS: atlas_bytes, FONT: font_bytes, FINGERPRINT: fingerprint.encode("ascii")}, sprites


def preview(sprites: list[Image.Image], path: Path) -> None:
    strip = Image.new("RGBA", (min(len(sprites), COLUMNS) * (SIZE + 1) + 12, SIZE * 2 + 14), (88, 110, 120))
    draw = ImageDraw.Draw(strip)
    draw.rectangle((0, SIZE + 7, strip.width, strip.height), fill=(49, 54, 33))
    for index, sprite in enumerate(sprites[:COLUMNS]):
        for y in (4, SIZE + 10):
            strip.alpha_composite(sprite, (6 + index * (SIZE + 1), y))
    enlarged = strip.resize((strip.width * 4, strip.height * 4), Image.Resampling.NEAREST)
    image = Image.new("RGBA", (enlarged.width, enlarged.height + strip.height + 8), (25, 29, 38))
    image.paste(strip, (0, 0))
    image.paste(enlarged, (0, strip.height + 8))
    image.save(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify the committed generated assets")
    parser.add_argument("--preview", type=Path, help="write an actual-size and enlarged icon strip")
    args = parser.parse_args()
    assets, sprites = font_assets(registry())
    for file, content in assets.items():
        if args.check:
            if not file.is_file():
                parser.error(f"Missing generated asset: {file.relative_to(PACK)}")
            actual = file.read_bytes()
            if file != ATLAS:
                actual = actual.replace(b"\r\n", b"\n")
            if actual != content:
                parser.error(f"Outdated generated asset: {file.relative_to(PACK)}")
        else:
            file.write_bytes(content)
    if args.preview:
        preview(sprites, args.preview)
    print(f"{len(sprites)} buff HUD glyphs, {len(assets[ATLAS]):,} atlas bytes")


if __name__ == "__main__":
    main()
