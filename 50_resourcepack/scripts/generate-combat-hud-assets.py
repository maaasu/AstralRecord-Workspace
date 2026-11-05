#!/usr/bin/env python3
"""Generate the compact Java combat HUD bitmap font without external libraries."""

from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / "assets/astralrecord/font/combat_hud.json"
ATLAS = ROOT / "assets/astralrecord/textures/font/combat_hud.png"
CELL_WIDTH = 18
CELL_HEIGHT = 14

GLYPHS = [0xE900, 0xE901, 0xE902, *range(0xE910, 0xE91B), *range(0xE920, 0xE924)]

# A small shaded palette keeps the icons legible at native UI scale.
PALETTE = {
    "K": (73, 23, 38, 255),
    "R": (205, 47, 70, 255),
    "r": (244, 93, 102, 255),
    "H": (255, 177, 157, 255),
    "B": (24, 55, 103, 255),
    "C": (51, 150, 217, 255),
    "c": (105, 211, 247, 255),
    "W": (211, 247, 255, 255),
    "D": (100, 65, 23, 255),
    "G": (214, 146, 36, 255),
    "g": (255, 197, 67, 255),
    "Y": (255, 237, 141, 255),
}

HEART = (
    ".KK...KK.",
    "KrrK.KrrK",
    "KrHHKrHrK",
    "KrrrrrrrK",
    ".KrrrrrK.",
    "..KrrrK..",
    "...KrK...",
    "....K....",
)

MANA = (
    "....B....",
    "...BCB...",
    "..BCcCB..",
    ".BCcWcCB.",
    "BCcWWWcCB",
    "BCcWWCcCB",
    "BCcWCCcCB",
    ".BCcCCCB.",
    "..BCCCB..",
    "...BBB...",
)

ENERGY = (
    ".....DD..",
    "....DgD..",
    "...DgYD..",
    "..DgYYD..",
    ".DgYYYD..",
    ".DgGDDD..",
    "..DgGgD..",
    "..DgYD...",
    ".DgYD....",
    ".DDD.....",
)

CREST = (
    ".....D.....",
    "....DGD....",
    "..DDgYgDD..",
    ".DgGgYgGgD.",
    "DgGgYYYgGgD",
    ".DgGgYgGgD.",
    "..DDgYgDD..",
    "....DGD....",
    ".....D.....",
)

DIVIDER = (
    "......D......",
    "..D...G...D..",
    ".DGGGgYgGGGD.",
    "..D...G...D..",
    "......D......",
)


def draw_pattern(pixels: bytearray, atlas_width: int, cell: int, pattern: tuple[str, ...], top: int, glint: int = -1) -> None:
    width = len(pattern[0])
    left = cell * CELL_WIDTH + (CELL_WIDTH - width) // 2
    for y, row in enumerate(pattern):
        if len(row) != width:
            raise ValueError("Pattern rows must have equal width")
        for x, token in enumerate(row):
            if token == ".":
                continue
            color = PALETTE[token]
            if glint >= 0 and token in "GgY" and x == glint:
                color = (255, 255, 229, 255)
            offset = ((top + y) * atlas_width + left + x) * 4
            pixels[offset : offset + 4] = bytes(color)


def draw_meter(pixels: bytearray, atlas_width: int, cell: int, step: int) -> None:
    left = cell * CELL_WIDTH
    fill = (14 * step + 5) // 10
    for y in range(9, 12):
        for x in range(17):
            if y == 10 and 2 <= x < 2 + fill:
                color = (245, 247, 250, 255)
            elif y == 10 and 2 <= x <= 15:
                color = (75, 79, 85, 220)
            elif x in (0, 16):
                color = (170, 174, 180, 255)
            else:
                color = (106, 110, 116, 220)
            offset = (y * atlas_width + left + x) * 4
            pixels[offset : offset + 4] = bytes(color)


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)


def encode_png(pixels: bytearray, width: int, height: int) -> bytes:
    rows = b"".join(b"\x00" + pixels[y * width * 4 : (y + 1) * width * 4] for y in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(rows, 9)) + png_chunk(b"IEND", b"")


def main() -> None:
    width = CELL_WIDTH * len(GLYPHS)
    pixels = bytearray(width * CELL_HEIGHT * 4)
    draw_pattern(pixels, width, 0, HEART, 2)
    draw_pattern(pixels, width, 1, MANA, 1)
    draw_pattern(pixels, width, 2, ENERGY, 1)
    for step in range(11):
        draw_meter(pixels, width, 3 + step, step)
    for frame, glint in enumerate((2, 5, -1)):
        draw_pattern(pixels, width, 14 + frame, CREST, 2, glint)
    draw_pattern(pixels, width, 17, DIVIDER, 5)

    ATLAS.parent.mkdir(parents=True, exist_ok=True)
    FONT.parent.mkdir(parents=True, exist_ok=True)
    ATLAS.write_bytes(encode_png(pixels, width, CELL_HEIGHT))
    FONT.write_text(json.dumps({"providers": [
        {"type": "bitmap", "file": "astralrecord:font/combat_hud.png", "height": CELL_HEIGHT,
         "ascent": 10, "chars": ["".join(chr(code) for code in GLYPHS)]},
        {"type": "reference", "id": "minecraft:default"},
    ]}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
