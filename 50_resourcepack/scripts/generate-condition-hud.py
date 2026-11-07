#!/usr/bin/env python3
"""Generate the Java action bar condition icons and countdown font."""

import json
from importlib import import_module
from pathlib import Path

combat = import_module("generate-combat-hud-assets")
gameplay = import_module("generate-gameplay-hud")
ROOT = Path(__file__).resolve().parents[1] / "assets/astralrecord"
CELL_WIDTH = 10
CELL_HEIGHT = 79

# U+E970 through U+E977 follow the plugin's ConditionType declaration order.
# Palette columns are outline, base color, highlight and accent.
ICONS = {
    "BURNING": (
        (0x5A2830, 0xEC6237, 0xFFDB8A, 0xFF9E49),
        ".....#....",
        "....#+#...",
        "...#++#...",
        "...#++##..",
        "..#+++*#..",
        ".##+**+#..",
        ".#++*o++#.",
        "#++*oo*++#",
        "#++*oo*++#",
        ".#++**++#.",
        "..######..",
    ),
    "FROZEN": (
        (0x275572, 0x7CCDF2, 0xE2FAFF, 0x4A93C7),
        "....##....",
        "..o.*+.o..",
        "..#.*+.#..",
        "...#*+#...",
        "#o+****+o#",
        ".#**oo**#.",
        "#o+****+o#",
        "...#*+#...",
        "..#.*+.#..",
        "..o.*+.o..",
        "....##....",
    ),
    "CHILLED": (
        (0x244B62, 0x7BCBDC, 0xD3F8F0, 0x438C9F),
        "..........",
        "......##..",
        "...###**#.",
        "..#+++++#.",
        "...#####..",
        "....###..#",
        "#***+++**#",
        ".########.",
        "......##..",
        ".#o+++**#.",
        "..######..",
    ),
    "SHOCKED": (
        (0x5F4431, 0xFFB852, 0xFFF6AC, 0xE48F54),
        ".....#...o",
        "....#+#..#",
        "...#+*#...",
        "..#+*#....",
        ".#+*##..o.",
        "#+*****+#.",
        "########.#",
        "....#*+#.o",
        "...#*+#...",
        "..#++#....",
        "..###.....",
    ),
    "POISON": (
        (0x493151, 0xB278CB, 0xE7BAED, 0x8758A8),
        "...####...",
        "..#****#..",
        ".#*++++*#.",
        "#++o++o++#",
        "#++#++#++#",
        "#++++++++#",
        ".#++##++#.",
        ".#+++o++#.",
        "..#+o++#..",
        "..#o#o+#..",
        "...####...",
    ),
    "BLINDNESS": (
        (0x302B47, 0x8E8BB6, 0xE4D6F5, 0x7160A3),
        "........o.",
        ".......o*.",
        ".#....o*#.",
        "..##.o*#..",
        ".#++o*++#.",
        "#*+o*#++*#",
        ".#o*#+++#.",
        "..*#####..",
        ".o*..##...",
        "o*........",
        "..........",
    ),
    "WEAKNESS": (
        (0x492B43, 0xDC8BA6, 0xF9C4CC, 0xAA5F87),
        "....##....",
        "...#*+#...",
        "...#++#...",
        "....++....",
        "...#+o#...",
        "...#++#...",
        ".###+o###.",
        "#**++++**#",
        ".#*o++o*#.",
        "..#*++*#..",
        "...####...",
    ),
    "HEALING_INHIBITION": (
        (0x245451, 0x68CC9D, 0xDAFBE0, 0xF58686),
        "...####.o.",
        "...#**#o#.",
        "...#**o#..",
        ".##**o###.",
        "#***o****#",
        "#**o*****#",
        ".#o*++*##.",
        "..o#+*#...",
        ".o##+*#...",
        "o#..##....",
        "..........",
    ),
}


def main():
    numbers = "0123456789s+"
    glyphs = "".join(chr(0xE970 + index) for index in range(len(ICONS))) + numbers
    cell_width = CELL_WIDTH * gameplay.SCALE
    width = cell_width * len(glyphs)
    height = CELL_HEIGHT * gameplay.SCALE
    pixels = bytearray(width * height * 4)
    for index, (colors, *rows) in enumerate(ICONS.values()):
        palette = {token: color.to_bytes(3, "big") + b"\xff"
                   for token, color in zip("#+*o", colors, strict=True)}
        gameplay.draw(pixels, width, (index * cell_width, 0), gameplay.double_pixels(rows), palette)
    for index, character in enumerate(numbers, start=len(ICONS)):
        rows = gameplay.detail_type(gameplay.TYPE[character])
        gameplay.draw(pixels, width, (index * cell_width, 2 * gameplay.SCALE), rows, gameplay.WHITE)

    (ROOT / "textures/font/condition_hud.png").write_bytes(combat.encode_png(pixels, width, height))
    providers = [
        gameplay.space_provider(),
        {"type": "bitmap", "file": "astralrecord:font/condition_hud.png",
         "height": CELL_HEIGHT, "ascent": CELL_HEIGHT, "chars": [glyphs]},
    ]
    (ROOT / "font/condition_hud.json").write_text(
        json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
