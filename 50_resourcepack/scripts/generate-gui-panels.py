#!/usr/bin/env python3
"""Generate Celestial GUI panels, the Java font, and Bedrock inventory artwork."""

from __future__ import annotations

import argparse
from functools import reduce
import importlib.util
import json
import math
from operator import or_
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
INK = "#101725"
NAVY = "#17243a"
BLUE = "#253852"
RIDGE = "#3d526b"
GOLD_DARK = "#6f4b37"
GOLD = "#bb8a50"
BRIGHT = "#e4bb78"
CREAM = "#f7dba5"
LAPIS = "#346389"
GLINT = "#8ccbd1"
CELL = "#3a4a54"
CELL_DARK = "#212d3d"
CELL_LIP = "#697a7d"
SLATE = "#404040"


class Raster:
    """Tiny axis-aligned pixel canvas; generation needs only Python stdlib."""

    def __init__(self, width: int, height: int, color=(0, 0, 0, 0)) -> None:
        self.width, self.height = width, height
        self.pixels = [[color] * width for _ in range(height)]

    def point(self, pos: tuple[int, int], fill: str) -> None:
        x, y = pos
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[y][x] = tuple(bytes.fromhex(fill.lstrip("#"))) + (255,)

    def rectangle(self, box: tuple[int, int, int, int], fill: str) -> None:
        x0, y0, x1, y1 = box
        color = tuple(bytes.fromhex(fill.lstrip("#"))) + (255,)
        for y in range(max(0, y0), min(self.height, y1 + 1)):
            self.pixels[y][max(0, x0):min(self.width, x1 + 1)] = [color] * max(0, min(self.width, x1 + 1) - max(0, x0))

    def line(self, box: tuple[int, int, int, int], fill: str) -> None:
        self.rectangle(box, fill)

    def paste(self, pixels: list[list], left: int, top: int) -> None:
        for y, row in enumerate(pixels):
            for x, color in enumerate(row):
                if color[3] and 0 <= left + x < self.width and 0 <= top + y < self.height:
                    self.pixels[top + y][left + x] = color


def png(pixels: list[list]) -> bytes:
    """Encode fixed-filter RGBA pixels reproducibly."""
    height, width = len(pixels), len(pixels[0])
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    data = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(data, 9)) + chunk(b"IEND", b"")


LETTERS = {
    "A": (14, 17, 17, 31, 17, 17, 17),
    "R": (30, 17, 17, 30, 20, 18, 17),
    "a": (0, 0, 6, 1, 7, 9, 7),
    "c": (0, 0, 7, 8, 8, 8, 7),
    "d": (1, 1, 7, 9, 9, 9, 7),
    "e": (0, 0, 6, 9, 15, 8, 7),
    "l": (1, 1, 1, 1, 1, 1, 1),
    "o": (0, 0, 6, 9, 9, 9, 6),
    "r": (0, 0, 11, 12, 8, 8, 8),
    "s": (0, 0, 7, 8, 6, 1, 14),
    "t": (0, 4, 14, 4, 4, 4, 3),
}
WORD = "AstralRecord"


def cut_rect(draw, box, color, cut=2):
    left, top, right, bottom = box
    for y in range(top, bottom + 1):
        inset = max(cut - (y - top), cut - (bottom - y), 0)
        draw.line((left + inset, y, right - inset, y), color)


def diamond(draw, x, y, radius, color):
    for offset in range(-radius, radius + 1):
        width = radius - abs(offset)
        draw.line((x - width, y + offset, x + width, y + offset), color)


def star(draw, x, y, size=2):
    draw.line((x - size, y, x + size, y), GOLD)
    draw.line((x, y - size, x, y + size), GOLD)
    draw.point((x, y), CREAM)


def gem(draw, x, y, size=3):
    diamond(draw, x, y, size, GOLD_DARK)
    diamond(draw, x, y - 1, size - 1, BRIGHT)
    diamond(draw, x, y, size - 1, GOLD)
    diamond(draw, x, y, size - 2, LAPIS)
    draw.point((x, y - 1), GLINT)


def logo() -> Raster:
    draw = Raster(70, 15)
    cut_rect(draw, (1, 1, 68, 14), INK, 5)
    cut_rect(draw, (1, 0, 68, 13), GOLD_DARK, 5)
    cut_rect(draw, (2, 0, 67, 12), GOLD, 5)
    cut_rect(draw, (3, 1, 66, 12), BLUE, 4)
    cut_rect(draw, (4, 2, 65, 11), NAVY, 4)
    draw.line((9, 0, 60, 0), BRIGHT)
    gem(draw, 2, 7, 2)
    gem(draw, 67, 7, 2)
    widths = [max(LETTERS[c]).bit_length() for c in WORD]
    x = (70 - sum(widths) - len(WORD) + 1) // 2
    for letter, width in zip(WORD, widths):
        for y, row in enumerate(LETTERS[letter]):
            for offset in range(width):
                if row & (1 << (width - offset - 1)):
                    draw.point((x + offset, y + 4), BRIGHT if y >= 5 else CREAM)
        x += width + 1
    return draw


CORNER = (
    "..HHHGGGHH.",
    ".HGDDGGD.G.",
    "HGDNNGD.G..",
    "HDNLNGDG...",
    "HDNNNGG....",
    "GGDGGD.....",
    "GGDGD......",
    "GDDG.......",
    "HG.........",
    ".G.........",
    ".G.........",
)
CORNER_COLORS = {"H": BRIGHT, "G": GOLD, "D": GOLD_DARK, "N": NAVY, "L": GLINT}


def corners(draw, width, height, size=11):
    for u, v in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)):
        x_sign = 1 if u == 0 else -1
        y_sign = 1 if v == 0 else -1
        for y, row in enumerate(CORNER[:size]):
            for x, pixel in enumerate(row[:size]):
                if pixel in CORNER_COLORS:
                    draw.point((u + x_sign * x, v + y_sign * y), CORNER_COLORS[pixel])


def frame(draw, width, height):
    cut_rect(draw, (0, 0, width - 1, height - 1), INK, 3)
    cut_rect(draw, (1, 1, width - 2, height - 2), BLUE, 2)
    cut_rect(draw, (2, 2, width - 3, height - 3), RIDGE, 2)
    cut_rect(draw, (3, 3, width - 4, height - 4), INK, 2)
    cut_rect(draw, (4, 4, width - 5, height - 5), NAVY, 1)


def orbit(draw, x, y, width, height, tilt, color):
    for angle in range(360):
        t = math.radians(angle)
        a, b = width * math.cos(t), height * math.sin(t)
        u = x + a * math.cos(tilt) - b * math.sin(tilt)
        v = y + a * math.sin(tilt) + b * math.cos(tilt)
        draw.point((round(u), round(v)), color)


def slot(draw, x, y):
    draw.rectangle((x - 1, y - 1, x + 16, y + 16), CELL_DARK)
    draw.rectangle((x, y, x + 15, y + 15), CELL)
    draw.line((x, y + 16, x + 16, y + 16), CELL_LIP)
    draw.line((x + 16, y, x + 16, y + 15), CELL_LIP)
    draw.point((x - 1, y - 1), GOLD_DARK)
    draw.point((x + 16, y + 16), GOLD)


def bedrock_panel():
    draw = Raster(24, 24)
    frame(draw, 24, 24)
    corners(draw, 24, 24, 8)
    return draw


def recipe_icon():
    draw = Raster(19, 13)
    orbit(draw, 9, 6, 7, 5, 0.0, GOLD_DARK)
    orbit(draw, 9, 6, 7, 2, -0.39, GOLD)
    gem(draw, 9, 6, 4)
    star(draw, 15, 3, 1)
    return draw


LABEL_FACE = "#b6b8b4"
LABEL_LIGHT = "#d3d3c6"


def panel(rows: int) -> Raster:
    width, height = 176, 114 + 18 * rows
    draw = Raster(width, height)
    frame(draw, width, height)
    draw.rectangle((5, 5, 170, 14), INK)
    draw.line((6, 15, 169, 15), GOLD_DARK)

    # The lower inventory title is rendered in dark gray by the client.
    # Keep a broad, bright, opaque field for localised player-inventory labels.
    divider = 18 * rows + 17
    cut_rect(draw, (6, divider, 169, divider + 12), BLUE)
    cut_rect(draw, (6, divider + 1, 96, divider + 11), LABEL_FACE)
    draw.line((8, divider + 1, 94, divider + 1), LABEL_LIGHT)
    draw.paste(logo().pixels[:13], 98, divider)

    content_y = (18 + 18 * row for row in range(rows))
    bag_top = 31 + 18 * rows
    inventory_y = [bag_top, bag_top + 18, bag_top + 36, bag_top + 58]
    for y in [*content_y, *inventory_y]:
        for column in range(9):
            slot(draw, 8 + 18 * column, y)

    gem(draw, 87, 2, 2)
    gem(draw, 87, height - 3, 2)
    corners(draw, width, height)
    return draw


CHAPTER_GLYPHS = ROOT / "gui-source/chapter-glyphs.json"
CHAPTER_STEPS = (("案内",), ("受取",), ("箱を", "開く"), ("武器", "を買う"), ("装備",), ("討伐",))
CHAPTER_NAME = "グラスボアを1体倒そう"
CHAPTER_LABELS = ("チャプター一覧", "手順を見る", "第1章", CHAPTER_NAME, "次のステップ", "攻略結果",
                  "現在", "完了", "一覧", "戻る", *(line for step in CHAPTER_STEPS for line in step))


def chapter_font_source(client_jar, assets_dir):
    """Extract binary 16x16 masks in Java 1.21.11 Japanese provider order."""
    from minecraft_pixel_font import CLIENT_SHA1, bitmap_advance, load_glyphs

    letters = sorted(set("".join(CHAPTER_LABELS)))
    masks = load_glyphs(client_jar, assets_dir, {ord(c) for c in letters})
    glyphs = {}
    for letter in letters:
        mask = masks[ord(letter)]
        if mask.size != (16, 16) or not set(mask.tobytes()).issubset({0, 255}) or not mask.getbbox():
            raise ValueError(f"Expected a non-empty 1-bit 16x16 source for {letter}")
        # These fixed labels use full-width Japanese glyphs (8+1 logical pixels).
        # Their only ASCII character is the vanilla bitmap digit one (5+1 pixels).
        advance = bitmap_advance(mask) if letter.isascii() else 9
        rows = mask.convert("1").tobytes().hex()
        glyphs[letter] = [advance, rows]
    return {"source": "Minecraft Java Edition 1.21.11 (asset index 29), ja_jp, uniform=false",
            "client_sha1": CLIENT_SHA1, "height": 16, "pixels_per_gui_unit": 2,
            "providers": ["minecraft:font/ascii.png", "minecraft:font/unifont_jp.zip", "minecraft:font/unifont.zip"],
            "license": "licenses/LICENSE-Unifont-17.0.01.txt (Unihex); Mojang Studios (ASCII)",
            "glyphs": glyphs}


def chapter_label(draw, glyphs, text, center_x, y, color=CREAM, *, compact=False):
    """Render native 1-bit glyphs at 2x, without filtering, added bold or outlines."""
    rows = [tuple(int(glyphs[c][1][i:i + 4], 16) for i in range(0, 64, 4)) for c in text]
    if compact:
        # Use the ink bounds to fit the captions within the 18px slot columns.
        ink = [reduce(or_, glyph_rows) for glyph_rows in rows]
        offsets = [16 - mask.bit_length() for mask in ink]
        advances = [mask.bit_length() - (mask & -mask).bit_length() + 2 for mask in ink]
    else:
        offsets = [0] * len(text)
        advances = [glyphs[c][0] * 2 for c in text]
    width = sum(advances) - (1 if compact else 2)
    left, top = round(center_x * 2 - width / 2), y * 2
    if left < 0 or left + width > draw.width or top < 0 or top + 16 > draw.height:
        raise ValueError(f"Chapter text exceeds the menu: {text}")
    rgba = tuple(bytes.fromhex(color[1:])) + (255,)
    for glyph_rows, offset, advance in zip(rows, offsets, advances):
        for row, bits in enumerate(glyph_rows):
            for column in range(16):
                if bits & (0x8000 >> column):
                    draw.pixels[top + row][left + column - offset] = rgba
        left += advance


def chapter_panel(variant, glyphs):
    """Overlay only the upper 125 GUI pixels. Inventory and hotbar remain native."""
    base = panel(6)
    base.rectangle((5, 17, 170, 124), NAVY)
    # Clear the standard grid while retaining its gold frame and the title band.
    base.rectangle((4, 16, 171, 124), NAVY)
    def field(left, top, right, bottom, face):
        cut_rect(base, (left, top, right, bottom), RIDGE, 2)
        cut_rect(base, (left+1, top+1, right-1, bottom-1), face, 2)
    def button_slot(index):
        slot(base, 8 + 18 * (index % 9), 18 + 18 * (index // 9))
    overview = variant == "list"
    if overview:
        field(25, 17, 150, 88, BLUE)
        field(66, 49, 109, 73, NAVY)
        button_slot(22)
    else:
        field(8, 17, 167, 36, BLUE)
        # The book is physically in slot 4. Put the chapter name on its own line.
        button_slot(4)
        field(8, 38, 167, 50, INK)
        field(25, 53, 150, 70, "#254448")
        button_slot(22)
        for index in range(28, 34):
            button_slot(index)
    divider_y = 102 if overview else 107
    base.line((8, divider_y, 167, divider_y), GOLD_DARK)
    for index in ([49] if overview else [45, 49]):
        button_slot(index)
    # Slot 51 is drawn by the plugin only while a reward is actually claimable.
    art = Raster(352, 250)
    art.pixels = [[pixel for pixel in row for _ in range(2)] for row in base.pixels[:125] for _ in range(2)]
    if overview:
        chapter_label(art, glyphs, "チャプター一覧", 88, 28)
        chapter_label(art, glyphs, "手順を見る", 88, 79, "#bad9dd")
    else:
        chapter_label(art, glyphs, "第1章", 43, 22, "#bad9dd")
        chapter_label(art, glyphs, "完了" if variant == "complete" else "現在", 130, 22, BRIGHT)
        chapter_label(art, glyphs, CHAPTER_NAME, 88, 40)
        chapter_label(art, glyphs, "攻略結果" if variant == "complete" else "次のステップ", 52, 57)
        for i, step in enumerate(CHAPTER_STEPS):
            # Match the column and height of the slot that handles caption clicks.
            caption = Raster(36, 36)
            top = 4 if len(step) == 1 else 0
            for line, text in enumerate(step):
                chapter_label(caption, glyphs, text, 9, top + line * 9, "#d7e5e7", compact=True)
            art.paste(caption.pixels, 50 + i * 36, 180)
        chapter_label(art, glyphs, "一覧", 34, 112, "#bad9dd")
    chapter_label(art, glyphs, "戻る", 106, 112, "#bad9dd")
    return art


def assets() -> dict[Path, bytes]:
    result: dict[Path, bytes] = {}
    providers: list[dict] = [
        {"type": "space", "advances": {"\uea00": -8, "\uea01": -169}}
    ]
    for rows in range(1, 7):
        image = panel(rows)
        result[ROOT / f"assets/astralrecord/textures/gui/panel_{rows}.png"] = png(image.pixels)
        providers.append({
            "type": "bitmap",
            "file": f"astralrecord:gui/panel_{rows}.png",
            "height": image.height,
            "ascent": 13,
            "chars": [chr(0xEA0F + rows)],
        })
    providers[0]["advances"].update({"\uea02": -177, "\uea03": -1})
    data = json.loads(CHAPTER_GLYPHS.read_text(encoding="utf-8"))
    if set(data["glyphs"]) != set("".join(CHAPTER_LABELS)):
        raise ValueError("Chapter label alphabet changed; refresh the chapter font source")
    for i, variant in enumerate(("list", "active", "complete")):
        texture = f"gui/panel_chapter_{variant}.png"
        result[ROOT / f"assets/astralrecord/textures/{texture}"] = png(chapter_panel(variant, data["glyphs"]).pixels)
        pair = chr(0xEA20 + i*2) + chr(0xEA21 + i*2)
        providers.append({"type": "bitmap", "file": f"astralrecord:{texture}",
                          "height": 125, "ascent": 13, "chars": [pair]})
    result[ROOT / "assets/astralrecord/font/gui.json"] = (
        json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n"
    ).encode("utf-8")
    bedrock = ROOT / "bedrock/textures/ui/astralrecord"
    result[bedrock / "inventory_panel.png"] = png(bedrock_panel().pixels)
    result[bedrock / "inventory_panel.json"] = (
        json.dumps({"nineslice_size": 8, "base_size": [24, 24]}, indent=2) + "\n"
    ).encode("utf-8")
    result[bedrock / "inventory_wordmark.png"] = png(logo().pixels)
    result[bedrock / "inventory_recipe_icon.png"] = png(recipe_icon().pixels)
    return result


def preview(path: Path) -> None:
    """Render a native-grid layout mock; it is not a Minecraft screenshot."""
    canvas = Raster(600, 266, (23, 31, 38, 255))
    patterns = {
        "A":"010/101/111/101/101", "C":"011/100/100/100/011", "D":"110/101/101/101/110",
        "E":"111/100/110/100/111", "F":"111/100/110/100/100", "G":"011/100/101/101/011",
        "H":"101/101/111/101/101",
        "I":"111/010/010/010/111", "K":"101/101/110/101/101", "L":"100/100/100/100/111",
        "M":"101/111/111/101/101", "N":"101/111/111/111/101", "O":"010/101/101/101/010",
        "P":"110/101/110/100/100", "R":"110/101/110/101/101", "S":"011/100/010/001/110",
        "T":"111/010/010/010/010", "U":"101/101/101/101/111", "V":"101/101/101/101/010",
        "W":"101/101/111/111/101", "X":"101/101/010/101/101", "Y":"101/101/010/010/010",
        "1":"010/110/010/010/111", "3":"110/001/010/001/110", "6":"011/100/110/101/010",
        "8":"010/101/010/101/010", "-":"000/000/111/000/000", "/":"001/001/010/100/100",
        " ":"000/000/000/000/000",
    }
    def label(text: str, left: int, top: int, color: str) -> None:
        for i, char in enumerate(text.upper()):
            for y, row in enumerate(patterns.get(char, patterns[" "]).split("/")):
                for x, pixel in enumerate(row):
                    if pixel == "1":
                        canvas.point((left + i * 4 + x, top + y), color)
    label("GUI PANEL LAYOUT MOCK - NATIVE 18PX GRID - NOT A GAME SCREENSHOT", 12, 12, "#f7dea0")
    spec = importlib.util.spec_from_file_location("gui_art", Path(__file__).with_name("generate-gui-assets.py"))
    art = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(art)
    source = json.loads(art.SOURCE.read_text(encoding="utf-8-sig"))
    for rows, left, top in ((6, 12, 32), (3, 210, 32), (1, 408, 32)):
        image = panel(rows)
        canvas.paste(image.pixels, left, top)
        label(f"ASTRALRECORD / {rows} ROWS", left + 8, top + 6, "#f7dea0")
        label("INVENTORY", left + 8, top + 18 * rows + 22, "#404040")
        for index, role in enumerate(("menu", "equipment", "quest", "currency", "settings")):
            canvas.paste(art.design(role, source), left + 8 + 18 * index, top + 18)
    path.parent.mkdir(parents=True, exist_ok=True)
    pixels = [[color for color in row for _ in range(2)] for row in canvas.pixels for _ in range(2)]
    path.write_bytes(png(pixels))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check generated bytes without writing them")
    parser.add_argument("--preview", type=Path, help="Optional layout mock output, never a client screenshot")
    parser.add_argument("--refresh-chapter-font", action="store_true", help="Extract vanilla chapter glyphs from the supplied client assets (Pillow)")
    parser.add_argument("--client-jar", type=Path, help="Official 1.21.11 client JAR for font extraction")
    parser.add_argument("--assets-dir", type=Path, help="Minecraft assets directory with indexes/29.json and objects/")
    parser.add_argument("--chapter-only", action="store_true", help="Write or check only Java chapter panels and gui.json")
    args = parser.parse_args()
    if args.refresh_chapter_font:
        if args.client_jar is None or args.assets_dir is None:
            parser.error("--refresh-chapter-font requires --client-jar and --assets-dir")
        source = json.dumps(chapter_font_source(args.client_jar, args.assets_dir), ensure_ascii=False, indent=2) + "\n"
        if args.check:
            if CHAPTER_GLYPHS.read_text(encoding="utf-8") != source:
                raise SystemExit("Chapter glyphs differ from the specified client")
        else:
            CHAPTER_GLYPHS.write_text(source, encoding="utf-8")
    mismatches = []
    for path, expected in assets().items():
        if args.chapter_only and not (path.name.startswith("panel_chapter_") or path.name == "gui.json"):
            continue
        if args.check:
            actual = path.read_bytes() if path.exists() else None
            if actual is not None and path.suffix == ".json":
                actual = actual.replace(b"\r\n", b"\n")
            if actual != expected:
                mismatches.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if mismatches:
        raise SystemExit("Out-of-date GUI assets: " + ", ".join(mismatches))
    if args.preview:
        preview(args.preview)
    print("GUI panels: six Java layouts, font and Bedrock inventory artwork")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
