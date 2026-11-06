#!/usr/bin/env python3
"""Generate the Java gameplay HUD type and icon atlases."""

import json
from importlib import import_module
from pathlib import Path

combat = import_module("generate-combat-hud-assets")
ROOT = Path(__file__).resolve().parents[1] / "assets/astralrecord"
CELL_WIDTH = 13
CELL_HEIGHT = 36

# The digits all use four columns. Narrow punctuation gives grouped numbers room to breathe.
TYPE = {
    "A": "0110/1001/1001/1111/1001/1001/1001",
    "B": "1110/1001/1001/1110/1001/1001/1110",
    "C": "0111/1000/1000/1000/1000/1000/0111",
    "D": "1110/1001/1001/1001/1001/1001/1110",
    "E": "1111/1000/1000/1110/1000/1000/1111",
    "F": "1111/1000/1000/1110/1000/1000/1000",
    "G": "0111/1000/1000/1011/1001/1001/0111",
    "H": "1001/1001/1001/1111/1001/1001/1001",
    "I": "1111/0110/0110/0110/0110/0110/1111",
    "J": "0011/0001/0001/0001/1001/1001/0110",
    "K": "1001/1010/1100/1100/1010/1001/1001",
    "L": "1000/1000/1000/1000/1000/1000/1111",
    "M": "1001/1111/1111/1001/1001/1001/1001",
    "N": "1001/1101/1101/1011/1011/1001/1001",
    "O": "0110/1001/1001/1001/1001/1001/0110",
    "P": "1110/1001/1001/1110/1000/1000/1000",
    "Q": "0110/1001/1001/1001/1001/1010/0101",
    "R": "1110/1001/1001/1110/1010/1001/1001",
    "S": "0111/1000/1000/0110/0001/0001/1110",
    "T": "1111/0110/0110/0110/0110/0110/0110",
    "U": "1001/1001/1001/1001/1001/1001/0110",
    "V": "1001/1001/1001/1001/1001/0110/0110",
    "W": "1001/1001/1001/1001/1111/1111/1001",
    "X": "1001/1001/0110/0110/0110/1001/1001",
    "Y": "1001/1001/1001/0110/0100/0100/0100",
    "Z": "1111/0001/0010/0110/0100/1000/1111",
    "0": "0110/1001/1001/1001/1001/1001/0110",
    "1": "0100/1100/0100/0100/0100/0100/1111",
    "2": "0110/1001/0001/0010/0100/1000/1111",
    "3": "1110/0001/0001/0110/0001/0001/1110",
    "4": "0010/0110/1010/1010/1111/0010/0010",
    "5": "1111/1000/1000/1110/0001/0001/1110",
    "6": "0110/1000/1000/1110/1001/1001/0110",
    "7": "1111/0001/0010/0010/0100/0100/0100",
    "8": "0110/1001/1001/0110/1001/1001/0110",
    "9": "0110/1001/1001/0111/0001/0001/0110",
    "v": "0000/0000/1001/1001/1001/0110/0110",
    "m": "0000/0000/1101/1111/1001/1001/1001",
    "a": "0000/0000/0110/0001/0111/1001/0111",
    "x": "0000/0000/1001/0110/0110/0110/1001",
    "s": "0000/0000/0111/1000/0110/0001/1110",
    "[": "11/10/10/10/10/10/11",
    "]": "11/01/01/01/01/01/11",
    "!": "1/1/1/1/1/0/1",
    "?": "0110/1001/0001/0010/0100/0000/0100",
    "+": "0000/0000/0010/0111/0010/0000/0000",
    "-": "0000/0000/0000/1111/0000/0000/0000",
    "/": "0001/0001/0010/0010/0100/1000/1000",
    "(": "01/10/10/10/10/10/01",
    ")": "10/01/01/01/01/01/10",
    "%": "1001/0001/0010/0010/0100/1000/1001",
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
        shapes[chr(0xE910 + fill)] = (meter, meter_colors, 11)
    width = CELL_WIDTH * len(shapes)
    pixels = bytearray(width * CELL_HEIGHT * 4)
    for index, (rows, palette, top) in enumerate(shapes.values()):
        draw(pixels, width, index, rows, palette, top)
    (ROOT / "textures/font/gameplay_hud.png").write_bytes(combat.encode_png(pixels, width, CELL_HEIGHT))

    advance = {" ": 3}
    for bit in range(5):
        advance[chr(0xE950 + bit)] = 1 << bit
        advance[chr(0xE955 + bit)] = -(1 << bit)
    for name, ascent in (("profile_hud_player", 36), ("profile_hud_class", 24), ("gameplay_hud", 14)):
        providers = [
            {"type": "space", "advances": advance},
            {"type": "bitmap", "file": "astralrecord:font/gameplay_hud.png",
             "height": CELL_HEIGHT, "ascent": ascent, "chars": ["".join(shapes)]},
        ]
        (ROOT / f"font/{name}.json").write_text(
            json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
