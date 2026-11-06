#!/usr/bin/env python3
"""Generate original, vanilla-aligned chest panels and their Java font.

This script uses no downloaded imagery. The restrained stone, gold and teal
palette is shared with the GUI icons; every slot retains the vanilla 18px grid.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUTLINE = "#26343d"
EDGE = "#455762"
LIGHT = "#c4cec8"
FACE = "#adbcb8"
GOLD = "#d8b363"
TEAL = "#377e80"


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


def panel(rows: int) -> Raster:
    """Return a native-sized panel with pixel-exact container slot positions."""
    height = 114 + 18 * rows
    image = Raster(176, height)
    draw = image
    draw.rectangle((1, 0, 174, height - 1), fill=OUTLINE)
    draw.rectangle((0, 1, 175, height - 2), fill=OUTLINE)
    draw.rectangle((2, 2, 173, height - 3), fill=EDGE)
    draw.line((3, 2, 172, 2), fill="#8ca4aa")
    draw.line((2, 3, 2, height - 4), fill="#8ca4aa")
    draw.rectangle((4, 4, 171, height - 5), fill=FACE)
    # A dark title strip keeps cream labels readable regardless of the original title color.
    draw.rectangle((5, 5, 170, 14), fill=OUTLINE)
    draw.line((5, 15, 170, 15), fill="#607985")
    draw.line((6, 16, 169, 16), fill=GOLD)
    # Keep native black/dark-gray inventory labels legible on pale stone.
    divider_y = 18 * rows + 20
    draw.line((6, divider_y - 3, 169, divider_y - 3), fill="#607985")
    draw.line((6, divider_y - 2, 169, divider_y - 2), fill=GOLD)
    draw.rectangle((5, divider_y - 1, 170, divider_y + 9), fill=LIGHT)
    for x in (3, 172):
        for y in (3, height - 4):
            draw.point((x, y), fill=GOLD)
    # Small teal seams indicate the content block without intruding into labels.
    for y in range(22, 18 * rows + 15, 8):
        draw.line((3, y, 3, y + 2), fill=TEAL)
        draw.line((172, y, 172, y + 2), fill=TEAL)

    def slot(x: int, y: int) -> None:
        draw.rectangle((x, y, x + 17, y + 17), fill="#607985")
        draw.rectangle((x + 1, y + 1, x + 16, y + 16), fill="#879d9e")
        draw.line((x, y, x + 16, y), fill=OUTLINE)
        draw.line((x, y, x, y + 16), fill=OUTLINE)
        draw.line((x + 1, y + 17, x + 17, y + 17), fill="#d6dfd5")
        draw.line((x + 17, y + 1, x + 17, y + 17), fill="#d6dfd5")

    for row in range(rows):
        for col in range(9):
            slot(7 + 18 * col, 17 + 18 * row)
    for row in range(3):
        for col in range(9):
            slot(7 + 18 * col, 31 + 18 * rows + 18 * row)
    for col in range(9):
        slot(7 + 18 * col, 89 + 18 * rows)
    return image


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
    result[ROOT / "assets/astralrecord/font/gui.json"] = (
        json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n"
    ).encode("utf-8")
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
    args = parser.parse_args()
    mismatches = []
    for path, expected in assets().items():
        if args.check:
            if not path.exists() or path.read_bytes() != expected:
                mismatches.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if mismatches:
        raise SystemExit("Out-of-date GUI assets: " + ", ".join(mismatches))
    if args.preview:
        preview(args.preview)
    print("GUI panels: 6 row layouts and font are byte-consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
