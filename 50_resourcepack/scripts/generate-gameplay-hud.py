#!/usr/bin/env python3
"""Generate the Java gameplay HUD type and icon atlases."""

import json
from importlib import import_module
from pathlib import Path

combat = import_module("generate-combat-hud-assets")
ROOT = Path(__file__).resolve().parents[1] / "assets/astralrecord"
CELL_WIDTH = 24
CELL_HEIGHT = 65

# Tabular digits share a five-pixel bitmap width. Narrow punctuation separates grouped numbers.
TYPE = {
    "A": "01110/10001/10001/11111/10001/10001/10001",
    "B": "11110/10001/10001/11110/10001/10001/11110",
    "C": "01111/10000/10000/10000/10000/10000/01111",
    "D": "11110/10001/10001/10001/10001/10001/11110",
    "E": "11111/10000/10000/11110/10000/10000/11111",
    "F": "11111/10000/10000/11110/10000/10000/10000",
    "G": "01111/10000/10000/10111/10001/10001/01110",
    "H": "10001/10001/10001/11111/10001/10001/10001",
    "I": "11111/00100/00100/00100/00100/00100/11111",
    "J": "00111/00010/00010/00010/00010/10010/01100",
    "K": "10001/10010/10100/11000/10100/10010/10001",
    "L": "10000/10000/10000/10000/10000/10000/11111",
    "M": "10001/11011/10101/10101/10001/10001/10001",
    "N": "10001/11001/10101/10011/10001/10001/10001",
    "O": "01110/10001/10001/10001/10001/10001/01110",
    "P": "11110/10001/10001/11110/10000/10000/10000",
    "Q": "01110/10001/10001/10001/10101/10010/01101",
    "R": "11110/10001/10001/11110/10100/10010/10001",
    "S": "01111/10000/10000/01110/00001/00001/11110",
    "T": "11111/00100/00100/00100/00100/00100/00100",
    "U": "10001/10001/10001/10001/10001/10001/01110",
    "V": "10001/10001/10001/10001/10001/01010/00100",
    "W": "10001/10001/10001/10101/10101/10101/01010",
    "X": "10001/10001/01010/00100/01010/10001/10001",
    "Y": "10001/10001/01010/00100/00100/00100/00100",
    "Z": "11111/00001/00010/00100/01000/10000/11111",
    "0": "01110/10001/10001/10101/10001/10001/01110",
    "1": "00100/01100/00100/00100/00100/00100/11111",
    "2": "01110/10001/00001/00010/00100/01000/11111",
    "3": "11110/00001/00001/01110/00001/00001/11110",
    "4": "00010/00110/01010/10010/11111/00010/00010",
    "5": "11111/10000/10000/11110/00001/00001/11110",
    "6": "01110/10000/10000/11110/10001/10001/01110",
    "7": "11111/00001/00010/00100/01000/01000/01000",
    "8": "01110/10001/10001/01110/10001/10001/01110",
    "9": "01110/10001/10001/01111/00001/00001/01110",
    "v": "00000/00000/10001/10001/10001/01010/00100",
    "m": "00000/00000/11010/10101/10101/10101/10101",
    "a": "00000/00000/01110/00001/01111/10001/01111",
    "x": "00000/00000/10001/01010/00100/01010/10001",
    "s": "00000/00000/01111/10000/01110/00001/11110",
    "[": "11/10/10/10/10/10/11",
    "]": "11/01/01/01/01/01/11",
    "!": "1/1/1/1/1/0/1",
    "?": "01110/10001/00001/00010/00100/00000/00100",
    "+": "00000/00000/00100/11111/00100/00000/00000",
    "-": "00000/00000/00000/11111/00000/00000/00000",
    "/": "00001/00001/00010/00100/01000/10000/10000",
    "(": "01/10/10/10/10/10/01",
    ")": "10/01/01/01/01/01/10",
    "%": "11001/11001/00010/00100/01000/10011/10011",
    ".": "0/0/0/0/0/0/1",
    ",": "0/0/0/0/0/1/1/1",
    ":": "0/0/1/0/0/1/0",
    "|": "1/1/1/1/1/1/1",
}

PROFILE = (
    "...*.../..***./.*+*+*./...*.../..***../.*+*+*./...*...",
    "..***../.*+++*./*++*++*/*+**++*/*++*++*/.*+++*./..***..",
    ".....**/....*+*/...*+*./*.*+*../.**+.../.**..../*..*...",
    "...*.../...*.../..*+*../**+*+**/..*+*../...*.../...*...",
    "...*.../..*+*../.*+++*./*++*++*/.*+++*./..*+*../...*...",
)
# Shade the same silhouettes as the standard heart, droplet and lightning HUD.
RESOURCES = (combat.HEART, combat.MANA, combat.ENERGY)


def draw(pixels, atlas_width, index, rows, palette, y_offset=0):
    for y, row in enumerate(rows, y_offset):
        for x, token in enumerate(row):
            if token not in palette:
                continue
            start = (y * atlas_width + index * CELL_WIDTH + x) * 4
            pixels[start : start + 4] = bytes(palette[token])


def main():
    shapes = {glyph: (pattern.split("/"), {"1": (255, 255, 255, 255)}, 1)
              for glyph, pattern in TYPE.items()}
    for index, icon in enumerate(PROFILE):
        shapes[chr(0xE940 + index)] = (icon.split("/"), {"*": (255, 255, 255, 255), "+": (165, 165, 165, 255)}, 1)
    for index, icon in enumerate(RESOURCES):
        shapes[chr(0xE900 + index)] = (icon, combat.PALETTE, 0)
    meter_colors = {"#": (125, 125, 125, 255), "+": (244, 244, 244, 255), "-": (66, 66, 66, 255)}
    for fill in range(11):
        meter = ("############", "#" + "+" * fill + "-" * (10 - fill) + "#", "############")
        shapes[chr(0xE910 + fill)] = (meter, meter_colors, 12)
        wide_fill = (22 * fill + 5) // 10
        wide_meter = ("#" * 24, "#" + "+" * wide_fill + "-" * (22 - wide_fill) + "#", "#" * 24)
        shapes[chr(0xE960 + fill)] = (wide_meter, meter_colors, 12)
    width = CELL_WIDTH * len(shapes)
    pixels = bytearray(width * CELL_HEIGHT * 4)
    for index, (rows, palette, top) in enumerate(shapes.values()):
        draw(pixels, width, index, rows, palette, top)
    (ROOT / "textures/font/gameplay_hud.png").write_bytes(combat.encode_png(pixels, width, CELL_HEIGHT))

    advance = {" ": 3}
    for bit in range(5):
        advance[chr(0xE950 + bit)] = 1 << bit
        advance[chr(0xE955 + bit)] = -(1 << bit)
    for name, ascent in (
        ("profile_hud_points", 65),
        ("profile_hud_player", 51),
        ("profile_hud_class", 37),
        ("gameplay_hud", 25),
        ("gameplay_hud_details", 8),
    ):
        providers = [
            {"type": "space", "advances": advance},
            {"type": "bitmap", "file": "astralrecord:font/gameplay_hud.png",
             "height": CELL_HEIGHT, "ascent": ascent, "chars": ["".join(shapes)]},
        ]
        (ROOT / f"font/{name}.json").write_text(
            json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
