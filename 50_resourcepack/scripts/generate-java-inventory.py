#!/usr/bin/env python3
"""Generate the original Java player inventory background on its native pixel grid."""

from __future__ import annotations

import argparse
from pathlib import Path
from runpy import run_path

PANELS = run_path(str(Path(__file__).with_name("generate-gui-panels.py")))
Raster = PANELS["Raster"]
png = PANELS["png"]
OUTLINE, EDGE, LIGHT, FACE, GOLD, TEAL = (
    PANELS[name] for name in ("OUTLINE", "EDGE", "LIGHT", "FACE", "GOLD", "TEAL")
)
OUTPUT = Path(__file__).resolve().parents[1] / "assets/minecraft/textures/gui/container/inventory.png"


def inventory() -> Raster:
    # Both supported endpoint clients use the top-left 176x166 region of this texture.
    draw = Raster(256, 256)
    draw.rectangle((3, 0, 173, 165), fill=OUTLINE)
    draw.rectangle((1, 1, 174, 164), fill=OUTLINE)
    draw.rectangle((0, 3, 175, 162), fill=OUTLINE)
    draw.rectangle((2, 2, 173, 163), fill=EDGE)
    draw.line((3, 2, 172, 2), fill="#8ca4aa")
    draw.line((2, 3, 2, 162), fill="#8ca4aa")
    draw.rectangle((4, 4, 171, 161), fill=FACE)
    for x in (3, 172):
        for y in (3, 162):
            draw.point((x, y), fill=GOLD)

    # The player model is rendered over the recessed preview well by the client.
    draw.rectangle((25, 7, 75, 78), fill=OUTLINE)
    draw.rectangle((26, 8, 74, 77), fill="#33464e")
    draw.rectangle((27, 9, 73, 76), fill="#384e54")
    draw.line((26, 78, 75, 78), fill=LIGHT)
    draw.line((75, 8, 75, 78), fill=LIGHT)

    # Preserve space above the grid for the native dark crafting label.
    draw.line((7, 80, 168, 80), fill=EDGE)
    draw.line((7, 81, 168, 81), fill=GOLD)
    draw.line((7, 138, 168, 138), fill=EDGE)
    draw.line((7, 139, 168, 139), fill=LIGHT)

    def slot(item_x: int, item_y: int) -> None:
        left, top = item_x - 1, item_y - 1
        right, bottom = item_x + 16, item_y + 16
        draw.rectangle((left, top, right, bottom), fill="#607985")
        draw.rectangle((item_x, item_y, right - 1, bottom - 1), fill="#879d9e")
        draw.line((left, top, right - 1, top), fill=OUTLINE)
        draw.line((left, top, left, bottom - 1), fill=OUTLINE)
        draw.line((left + 1, bottom, right, bottom), fill="#d6dfd5")
        draw.line((right, top + 1, right, bottom), fill="#d6dfd5")

    for y in (8, 26, 44, 62):
        slot(8, y)
    slot(77, 62)
    for y in (18, 36):
        for x in (98, 116):
            slot(x, y)
    slot(154, 28)
    for y in (84, 102, 120, 142):
        for column in range(9):
            slot(8 + 18 * column, y)

    # Crafting direction remains visible with both input and result slots empty.
    draw.rectangle((135, 33, 145, 37), fill=OUTLINE)
    for x in range(144, 151):
        radius = 150 - x
        draw.line((x, 35 - radius, x, 35 + radius), fill=OUTLINE)
    draw.line((136, 34, 145, 34), fill=TEAL)
    draw.line((136, 35, 148, 35), fill=TEAL)
    draw.line((145, 32, 145, 37), fill=TEAL)
    draw.point((146, 33), fill=TEAL)
    draw.point((147, 34), fill=TEAL)
    return draw


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify the committed PNG without writing it")
    parser.add_argument("--preview", type=Path, help="Optional enlarged background mock without client overlays")
    args = parser.parse_args()
    pixels = inventory().pixels
    expected = png(pixels)
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != expected:
            raise SystemExit(f"Out-of-date Java inventory background: {OUTPUT}")
    else:
        OUTPUT.write_bytes(expected)
    if args.preview:
        rows = [[pixel for pixel in row[:176] for _ in range(3)] for row in pixels[:166]]
        args.preview.write_bytes(png([row for row in rows for _ in range(3)]))
    print("Java inventory background: 256x256 texture, 176x166 viewport")


if __name__ == "__main__":
    main()
