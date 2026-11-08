#!/usr/bin/env python3
"""Generate the original Java player inventory background on its native pixel grid."""

from __future__ import annotations

import argparse
from pathlib import Path
from runpy import run_path

PANELS = run_path(str(Path(__file__).with_name("generate-gui-panels.py")))
Raster, png, frame, corners, cut_rect, slot, gem, logo, star, orbit = (
    PANELS[name] for name in ("Raster", "png", "frame", "corners", "cut_rect", "slot", "gem", "logo", "star", "orbit")
)
INK, NAVY, BLUE, RIDGE, GOLD_DARK, GOLD, BRIGHT, CELL_LIP, SLATE = (
    PANELS[name] for name in ("INK", "NAVY", "BLUE", "RIDGE", "GOLD_DARK", "GOLD", "BRIGHT", "CELL_LIP", "SLATE")
)
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "assets/minecraft/textures/gui/container/inventory.png"
RECIPE_BOOK = ROOT / "java-gui-native/assets/minecraft/textures/gui/sprites/recipe_book"


def inventory() -> Raster:
    draw = Raster(256, 256)
    frame(draw, 176, 166)
    # The client renders its character and the empty armor/offhand icons separately.
    draw.rectangle((25, 7, 75, 78), GOLD_DARK)
    draw.rectangle((26, 8, 74, 77), BLUE)
    draw.rectangle((27, 9, 73, 76), INK)
    for x, y in ((30, 17), (47, 11), (65, 13), (68, 26), (33, 41), (72, 45),
                 (32, 53), (43, 59), (37, 68), (67, 54), (56, 72), (71, 73)):
        draw.point((x, y), RIDGE)
    star(draw, 33, 25)
    star(draw, 67, 17, 3)
    orbit(draw, 62, 64, 10, 7, 0.0, GOLD_DARK)
    orbit(draw, 62, 64, 11, 3, -0.55, GOLD)
    gem(draw, 62, 64)
    for x in (26, 74):
        for y in (8, 77):
            draw.point((x, y), BRIGHT)

    # The inventory heading is opaque #404040 without shadow in both Java
    # endpoints. Give it a flush surface of the same color, leaving translations intact.
    cut_rect(draw, (96, 4, 171, 56), BLUE, 2)
    cut_rect(draw, (97, 5, 171, 55), SLATE, 1)
    for x, y in ((137, 22), (150, 23), (163, 21), (144, 50), (156, 49), (167, 51)):
        draw.point((x, y), CELL_LIP)
    star(draw, 164, 22)
    star(draw, 145, 50)
    star(draw, 84, 13)
    star(draw, 91, 54)
    orbit(draw, 86, 37, 7, 11, 0.0, GOLD_DARK)
    orbit(draw, 86, 37, 9, 4, -0.62, GOLD)
    gem(draw, 86, 37, 4)
    draw.paste(logo().pixels, 98, 54)
    star(draw, 134, 73, 2)
    star(draw, 154, 73, 3)
    draw.point((145, 76), RIDGE)
    draw.point((165, 70), RIDGE)

    for y in (8, 26, 44, 62):
        slot(draw, 8, y)
    slot(draw, 77, 62)
    for y in (18, 36):
        for x in (98, 116):
            slot(draw, x, y)
    slot(draw, 154, 28)
    for y in (84, 102, 120, 142):
        for column in range(9):
            slot(draw, 8 + column * 18, y)

    # Single boundary between functional groups. No detached line ornaments.
    draw.line((7, 81, 168, 81), GOLD_DARK)
    draw.line((7, 139, 168, 139), BLUE)
    gem(draw, 87, 2, 2)
    gem(draw, 87, 163, 2)
    corners(draw, 176, 166)
    return draw


def java_recipe_icon(highlighted: bool) -> Raster:
    draw = Raster(20, 18)
    # The shared button is visible on vanilla workbench/furnace backgrounds.
    # In survival it is drawn at (104, 61), over the plain navy below the nameplate.
    for y, row in enumerate((
        ".###...###.",
        "#####.#####",
        "#####.#####",
        "###########",
        "##..###..##",
    ), start=10):
        for x, pixel in enumerate(row, start=5):
            if pixel == "#":
                draw.point((x, y), NAVY)
    if highlighted:
        for x in (3, 17):
            draw.line((x, 10, x, 14), NAVY)
            draw.point((x + (1 if x == 3 else -1), 9), NAVY)
            draw.point((x + (1 if x == 3 else -1), 15), NAVY)
    return draw


def assets(draw: Raster) -> dict[Path, bytes]:
    result = {OUTPUT: png(draw.pixels)}
    for name, highlighted in (("button", False), ("button_highlighted", True)):
        button = java_recipe_icon(highlighted)
        for y, row in enumerate(button.pixels):
            for x, pixel in enumerate(row):
                if pixel[3] and draw.pixels[y + 61][x + 104] != pixel:
                    raise ValueError(f"Recipe button is visible on the inventory background: {name}")
        result[RECIPE_BOOK / f"{name}.png"] = png(button.pixels)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify the committed PNGs without writing them")
    parser.add_argument("--preview", type=Path, help="Optional enlarged background mock without client overlays")
    args = parser.parse_args()
    draw = inventory()
    for path, expected in assets(draw).items():
        if args.check:
            if not path.is_file() or path.read_bytes() != expected:
                raise SystemExit(f"Out-of-date Java inventory asset: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if args.preview:
        rows = [[pixel for pixel in row[:176] for _ in range(3)] for row in draw.pixels[:166]]
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        args.preview.write_bytes(png([row for row in rows for _ in range(3)]))
    print("Java inventory background and recipe buttons: 176x166 viewport")


if __name__ == "__main__":
    main()
