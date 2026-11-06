#!/usr/bin/env python3
"""Generate original Java HUD hotbar sprites and the shared animated selection art."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from runpy import run_path

PANELS = run_path(str(Path(__file__).with_name("generate-gui-panels.py")))
Raster = PANELS["Raster"]
png = PANELS["png"]
OUTPUT = Path(__file__).resolve().parents[1] / "assets/minecraft/textures/gui/sprites/hud"
FRAME_COUNT = 12
FRAME_TIME = 2
OUTLINE = "#26343d"
GOLD = "#ffd376"
TEAL = "#58ddd1"


def slot(draw: Raster, x: int, y: int) -> None:
    draw.rectangle((x - 1, y - 1, x + 16, y + 16), fill="#25343e")
    draw.rectangle((x, y, x + 15, y + 15), fill="#3b4f5b")
    draw.line((x, y + 16, x + 16, y + 16), fill="#637985")
    draw.line((x + 16, y, x + 16, y + 16), fill="#637985")


def hotbar() -> Raster:
    draw = Raster(182, 22)
    draw.rectangle((1, 0, 180, 21), fill=OUTLINE)
    draw.rectangle((0, 1, 181, 20), fill=OUTLINE)
    draw.rectangle((1, 1, 180, 20), fill="#465b67")
    for x in range(3, 164, 20):
        slot(draw, x, 3)
    for x in range(21, 162, 20):
        draw.point((x, 10), fill="#8095a0")
    return draw


def offhand(right: bool) -> Raster:
    draw = Raster(29, 24)
    left = 0 if right else 1
    draw.rectangle((left + 1, 1, left + 26, 22), fill=OUTLINE)
    draw.rectangle((left, 2, left + 27, 21), fill=OUTLINE)
    draw.rectangle((left + 1, 2, left + 26, 21), fill="#465b67")
    slot(draw, 10 if right else 3, 4)
    cap = 4 if right else 24
    draw.point((cap, 9), fill="#b68b4c")
    draw.line((cap - 1, 10, cap + 1, 10), fill="#b68b4c")
    draw.point((cap, 11), fill="#b68b4c")
    return draw


def selection_frame(frame: int, height: int) -> Raster:
    """Return a 24px-wide frame, with an unpainted center for the item."""
    draw = Raster(24, height)
    bottom = height - 1
    # Dark outer bevel remains visible against pale scenery.
    draw.line((4, 0, 19, 0), fill=OUTLINE)
    draw.line((4, bottom, 19, bottom), fill=OUTLINE)
    draw.line((0, 4, 0, bottom - 3), fill=OUTLINE)
    draw.line((23, 4, 23, bottom - 3), fill=OUTLINE)
    # Two parallel materials read as a solid gold frame with an inset teal seam.
    draw.line((3, 1, 20, 1), fill=GOLD)
    draw.line((3, bottom - 1, 20, bottom - 1), fill=GOLD)
    draw.line((1, 3, 1, bottom - 2), fill=GOLD)
    draw.line((22, 3, 22, bottom - 2), fill=GOLD)
    draw.line((4, 2, 19, 2), fill="#377e80")
    draw.line((4, bottom - 2, 19, bottom - 2), fill="#377e80")
    draw.line((2, 4, 2, bottom - 3), fill="#377e80")
    draw.line((21, 4, 21, bottom - 3), fill="#377e80")
    # Small amber corner gems brighten in sequence independently of the sparks.
    glow = ("#c48648", "#e3a956", GOLD, "#ffe593", "#fff3c7", "#ffe593", GOLD, "#e3a956")
    corners = ((2, 2), (21, 2), (21, bottom - 1), (2, bottom - 1))
    for index, (x, y) in enumerate(corners):
        color = glow[(frame * len(glow) // FRAME_COUNT + 2 * index) % len(glow)]
        draw.point((x, y), fill=color)
        draw.point((x - 1 if x == 2 else x + 1, y), fill="#8d6039")
        draw.point((x, y - 1 if y == 2 else y + 1), fill=color)

    # Twin cyan sparks orbit opposite each other. Their tails remain on the rim.
    track = (
        [(x, 2) for x in range(3, 21)]
        + [(21, y) for y in range(3, bottom - 1)]
        + [(x, bottom - 2) for x in range(20, 2, -1)]
        + [(2, y) for y in range(bottom - 2, 2, -1)]
    )
    step = frame * len(track) // FRAME_COUNT
    for lead, core in ((step, "#d0fff3"), (step + len(track) // 2, "#fff0b1")):
        for distance, color in ((4, "#45a9a7"), (3, "#45a9a7"), (2, TEAL), (1, TEAL), (0, core)):
            draw.point(track[(lead - distance) % len(track)], fill=color)
    return draw


def assets() -> dict[Path, bytes]:
    result = {
        OUTPUT / "hotbar.png": png(hotbar().pixels),
        OUTPUT / "hotbar_offhand_left.png": png(offhand(False).pixels),
        OUTPUT / "hotbar_offhand_right.png": png(offhand(True).pixels),
        OUTPUT / "hotbar_selection.png": png(
            [row for frame in range(FRAME_COUNT) for row in selection_frame(frame, 23).pixels]
        ),
    }
    metadata = {"animation": {"width": 24, "height": 23, "frametime": FRAME_TIME}}
    result[OUTPUT / "hotbar_selection.png.mcmeta"] = (json.dumps(metadata, indent=2) + "\n").encode()
    return result


def preview(path: Path) -> None:
    canvas = Raster(250, 4 * 29, (22, 28, 35, 255))
    for row, frame in enumerate((0, 3, 6, 9)):
        top = 3 + row * 29
        canvas.paste(hotbar().pixels, 34, top)
        canvas.paste(offhand(False).pixels, 5, top - 1)
        canvas.paste(offhand(True).pixels, 216, top - 1)
        canvas.paste(selection_frame(frame, 23).pixels, 34 - 1 + 80, top - 1)
    pixels = [[pixel for pixel in row for _ in range(3)] for row in canvas.pixels]
    path.write_bytes(png([row for row in pixels for _ in range(3)]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify generated bytes without writing assets")
    parser.add_argument("--preview", type=Path, help="Optional enlarged comparison of four animation frames")
    args = parser.parse_args()
    for path, expected in assets().items():
        if args.check:
            if not path.is_file():
                raise SystemExit(f"Missing Java hotbar asset: {path}")
            actual = path.read_bytes()
            if path.suffix == ".mcmeta":
                actual = actual.replace(b"\r\n", b"\n")
            if actual != expected:
                raise SystemExit(f"Out-of-date Java hotbar asset: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if args.preview:
        preview(args.preview)
    print(f"Java HUD hotbar: {FRAME_COUNT} selection frames at {FRAME_TIME} ticks each")


if __name__ == "__main__":
    main()
