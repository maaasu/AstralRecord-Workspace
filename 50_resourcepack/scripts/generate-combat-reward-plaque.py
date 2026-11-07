"""Build the combat reward artwork from the shared item tooltip assets."""

from __future__ import annotations

import argparse
import json
from io import BytesIO
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "assets/astralrecord"
BEDROCK = ROOT / "bedrock"
WIDTH = 192
JAVA_ASCENT = 24
JAVA_LINE_PITCH = 10
JAVA_BOTTOM_PAD = 11
BEDROCK_ATLAS_SIZE = 256
BEDROCK_FRAME_TOP = 30
BEDROCK_FRAME_BOTTOM = 140
LORE_FILL = (21, 26, 40, 255)
PANEL_FILL_ALPHA = round(255 * 0.28)
LORE_GOLD = (168, 138, 86, 255)
LORE_HIGHLIGHT = (221, 188, 119, 255)
RARE_EDGE = (151, 122, 196, 255)
RARE_CORNER = (211, 185, 247, 255)
RARE_FLASH = (255, 233, 255, 255)
FRAME_STYLES = (("normal", 0xE710), ("rare", 0xE720), ("rare_glint", 0xE750))

# E700..E708. The two organic drops are the only silhouettes not in the lore atlas.
LORE_ICONS = (0xD, None, 0xE, 0x1, 0xA, None, 0xB, 0xF, 0xC)
HIDE_PIXELS = (
    "                ",
    "   ooooo        ",
    "  olmlllooo     ",
    "  olhhmmlllooo  ",
    "  omhmmmlllmso  ",
    "   ommmmlhhmso  ",
    "   olllllhmmmo  ",
    "  olhhlmhhmmso  ",
    "  olhmmlmmssoo  ",
    " olmmmlmmsso    ",
    " ommmslmsoo     ",
    "  oosslmso      ",
    "    ooooso      ",
    "        o       ",
)
FOOD_PIXELS = (
    "                ",
    "     ooooo      ",
    "   oohhhhloo    ",
    "  ohhllllhhlo   ",
    "  ohllmmmlhho   ",
    " ollmmmmlmlho   ",
    " olmmssmmmllo   ",
    " olmsmmmlmhoo   ",
    " olmmmlmlho     ",
    "  olllmlho      ",
    "   oohhhlo      ",
    "     oooo       ",
)


def rgba(path: Path) -> Image.Image:
    with Image.open(path) as source:
        return source.convert("RGBA")


def panel_tile(lore: Image.Image, style: str) -> Image.Image:
    if style == "normal":
        return lore
    tile = lore.copy()
    for y in range(tile.height):
        for x in range(tile.width):
            color = lore.getpixel((x, y))
            if color == LORE_GOLD:
                tile.putpixel((x, y), RARE_EDGE)
            elif color == LORE_HIGHLIGHT:
                upper_right = y < 4 and x >= tile.width - 4
                color = RARE_FLASH if style == "rare_glint" and upper_right else RARE_CORNER
                tile.putpixel((x, y), color)
    return tile


def stretch_lore(tile: Image.Image, height: int) -> Image.Image:
    """Stretch the center and edges, preserving the actual four lore corners."""
    border = 4
    source = (0, border, tile.width - border, tile.width)
    width = WIDTH - 2
    columns = (0, border, width - border, width)
    rows = (0, border, height - border, height)
    frame = Image.new("RGBA", (width, height))
    for row in range(3):
        for col in range(3):
            patch = tile.crop((source[col], source[row], source[col + 1], source[row + 1]))
            size = (columns[col + 1] - columns[col], rows[row + 1] - rows[row])
            frame.paste(patch.resize(size, Image.Resampling.NEAREST), (columns[col], rows[row]))
    image = Image.new("RGBA", (WIDTH, height))
    # Last opaque column x=190 gives the Java bitmap exactly 192px of advance.
    image.paste(frame, (1, 0))
    return image


def apply_panel_opacity(image: Image.Image) -> None:
    """Change only the navy fill after the frame and sparkle have been drawn."""
    pixels = image.load()
    translucent_fill = (*LORE_FILL[:3], PANEL_FILL_ALPHA)
    for y in range(image.height):
        for x in range(image.width):
            if pixels[x, y] == LORE_FILL:
                pixels[x, y] = translucent_fill


def loot_sprite(rows: tuple[str, ...], colors: tuple[str, ...]) -> Image.Image:
    sprite = Image.new("RGBA", (16, 16))
    palette = dict(zip("osmlh", (tuple(bytes.fromhex(color)) + (255,) for color in colors)))
    for y, row in enumerate(rows):
        for x, pixel in enumerate(row):
            if pixel != " ":
                sprite.putpixel((x, y), palette[pixel])
    return sprite


def loot_icons(lore: Image.Image) -> Image.Image:
    hide = loot_sprite(HIDE_PIXELS, ("0b0f18", "6d4833", "996747", "c69464", "efd2a1"))
    food = loot_sprite(FOOD_PIXELS, ("0b0f18", "783a47", "ad5760", "d98078", "f7c8a5"))
    strip = Image.new("RGBA", (16 * len(LORE_ICONS), 16))
    for index, lore_index in enumerate(LORE_ICONS):
        if lore_index is None:
            icon = hide if index == 1 else food
        else:
            left = lore_index * 16
            icon = lore.crop((left, 0, left + 16, 16))
        strip.paste(icon, (16 * index, 0))
    return strip


def legacy_divider() -> Image.Image:
    """Keep the E709 bitmap advance for an older RPG using the refreshed pack."""
    image = Image.new("RGBA", (151, 6))
    image.paste((76, 72, 86, 255), (0, 3, 150, 4))
    return image


def rare_glint_sprite() -> Image.Image:
    sprite = Image.new("RGBA", (16, 16))
    for y in range(5, 10):
        for x in range(5, 10):
            distance = abs(x - 7) + abs(y - 7)
            if distance <= 2:
                sprite.putpixel((x, y), RARE_FLASH if distance < 2 else (*RARE_CORNER[:3], 136))
    return sprite


def java_height(lines: int) -> int:
    return JAVA_ASCENT + (lines - 1) * JAVA_LINE_PITCH + JAVA_BOTTOM_PAD


def font_json() -> dict:
    providers = []
    for style, first_glyph in FRAME_STYLES:
        for lines in range(5, 11):
            providers.append({
                "type": "bitmap",
                "file": f"astralrecord:font/combat_reward_plaque_{style}_{lines}.png",
                "height": java_height(lines),
                "ascent": JAVA_ASCENT,
                "chars": [chr(first_glyph + lines - 5)],
            })
    providers.extend((
        {"type": "bitmap", "file": "astralrecord:font/combat_reward_plaque_icons.png",
         "height": 10, "ascent": 8, "chars": ["".join(chr(0xE700 + i) for i in range(len(LORE_ICONS)))]},
        {"type": "bitmap", "file": "astralrecord:font/combat_reward_plaque_divider.png",
         "height": 6, "ascent": 4, "chars": ["\ue709"]},
        {"type": "space", "advances": {chr(0xE731): WIDTH, **{
            chr(0xE740 + bit): 1 << bit for bit in range(8)
        }}},
        {"type": "reference", "id": "minecraft:default"},
    ))
    return {"providers": providers}


def png(image: Image.Image) -> bytes:
    output = BytesIO()
    image.save(output, format="PNG", optimize=False, compress_level=9)
    return output.getvalue()


def outputs() -> dict[Path, bytes]:
    lore_frame = rgba(BEDROCK / "textures/ui/purpleBorder.png")
    if lore_frame.size != (16, 16):
        raise ValueError("The shared lore frame must be 16 by 16 pixels")
    java_font = JAVA / "textures/font"
    glint = rare_glint_sprite()
    result: dict[Path, bytes] = {}
    for style, _ in FRAME_STYLES:
        tile = panel_tile(lore_frame, style)
        for lines in range(5, 11):
            frame = stretch_lore(tile, java_height(lines))
            if style == "rare_glint":
                frame.alpha_composite(glint.crop((5, 5, 10, 10)), (WIDTH - 9, 1))
            apply_panel_opacity(frame)
            result[java_font / f"combat_reward_plaque_{style}_{lines}.png"] = png(frame)
        if style != "rare_glint":
            texture = Image.new("RGBA", (BEDROCK_ATLAS_SIZE, BEDROCK_ATLAS_SIZE))
            frame = stretch_lore(tile, BEDROCK_FRAME_BOTTOM - BEDROCK_FRAME_TOP)
            apply_panel_opacity(frame)
            texture.paste(frame, (0, BEDROCK_FRAME_TOP))
            if style == "rare":
                texture.paste(glint, (WIDTH, 0))
            result[BEDROCK / f"textures/entity/combat_reward_plaque/{style}.png"] = png(texture)

    icons = loot_icons(rgba(java_font / "tooltip_icons.png"))
    result[java_font / "combat_reward_plaque_icons.png"] = png(icons)
    result[java_font / "combat_reward_plaque_divider.png"] = png(legacy_divider())
    result[JAVA / "font/combat_reward_plaque.json"] = (json.dumps(font_json(), ensure_ascii=True, indent=2) + "\n").encode()
    atlas = Image.new("RGBA", (BEDROCK_ATLAS_SIZE, BEDROCK_ATLAS_SIZE))
    atlas.paste(icons, (0, 0))
    result[BEDROCK / "font/glyph_E7.png"] = png(atlas)
    result[ROOT / "combat-reward-plaque-version.txt"] = b"1"
    return result


def preview(path: Path) -> None:
    java_font = JAVA / "textures/font"
    short = rgba(java_font / "combat_reward_plaque_normal_5.png")
    tall = rgba(java_font / "combat_reward_plaque_rare_glint_10.png")
    atlas = rgba(BEDROCK / "textures/entity/combat_reward_plaque/rare.png")
    icons = rgba(java_font / "combat_reward_plaque_icons.png")
    bedrock_frame = atlas.crop((0, BEDROCK_FRAME_TOP, WIDTH, BEDROCK_FRAME_BOTTOM))
    bedrock_y = 8 + short.height + 6
    icons_y = tall.height + 14
    height = max(bedrock_y + bedrock_frame.height, icons_y + icons.height) + 8
    sheet = Image.new("RGBA", (WIDTH * 2 + 24, height))
    sheet.paste(short, (8, 8))
    sheet.paste(tall, (WIDTH + 16, 8))
    sheet.paste(bedrock_frame, (8, bedrock_y))
    sheet.paste(icons, (WIDTH + 16, icons_y))
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.Resampling.NEAREST).save(path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if generated files differ")
    parser.add_argument("--preview", type=Path, help="show the actual texture and icon pixels as PNG")
    args = parser.parse_args()
    stale = []
    for path, content in outputs().items():
        if args.check:
            current = path.read_bytes() if path.is_file() else None
            if current is not None and path.suffix == ".json":
                current = current.replace(b"\r\n", b"\n")
            if current != content:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if stale:
        raise SystemExit("Generated reward plaque assets differ: " + ", ".join(stale))
    if args.preview:
        preview(args.preview)


if __name__ == "__main__":
    main()
