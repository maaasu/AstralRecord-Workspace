#!/usr/bin/env python3
"""Generate Bedrock HUD hotbar textures without changing its layout or inputs."""

from __future__ import annotations

import argparse
from pathlib import Path
from runpy import run_path

SCRIPTS = Path(__file__).resolve().parent
TEXTURES = SCRIPTS.parent / "bedrock/textures/ui"
PANELS = run_path(str(SCRIPTS / "generate-gui-panels.py"))
Raster = PANELS["Raster"]
png = PANELS["png"]

FRAME = "#26343e"
EDGE = "#627b89"
HIGHLIGHT = "#adc3c6"
FACE = "#364a55"
SHADE = "#31414d"
GOLD = "#bf9656"


def slot() -> Raster:
    image = Raster(20, 22)
    image.rectangle((0, 0, 19, 21), fill=FRAME)
    image.rectangle((1, 1, 18, 20), fill=EDGE)
    image.rectangle((2, 2, 17, 19), fill=FACE)
    image.line((2, 2, 17, 2), fill=HIGHLIGHT)
    image.line((2, 3, 2, 19), fill=SHADE)
    image.line((3, 19, 17, 19), fill=SHADE)
    for x in (1, 18):
        image.point((x, 1), fill=GOLD)
        image.point((x, 20), fill=GOLD)
    return image


def cap() -> Raster:
    image = Raster(1, 22)
    image.line((0, 0, 0, 21), fill=EDGE)
    image.point((0, 0), fill=FRAME)
    image.point((0, 21), fill=FRAME)
    return image


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check the generated textures without writing")
    parser.add_argument("--preview", type=Path, help="Save a layout mock for all animation frames")
    args = parser.parse_args()

    java = run_path(str(SCRIPTS / "generate-java-hotbar.py"))
    frame_count = java["FRAME_COUNT"]
    frames = [java["selection_frame"](index, 24) for index in range(frame_count)]
    strip = Raster(frame_count * 24, 24)
    for index, frame in enumerate(frames):
        strip.paste(frame.pixels, index * 24, 0)

    slot_image = slot()
    slot_bytes = png(slot_image.pixels)
    cap_bytes = png(cap().pixels)
    assets = {TEXTURES / f"hotbar_{index}.png": slot_bytes for index in range(9)}
    assets.update({
        TEXTURES / "hotbar_start_cap.png": cap_bytes,
        TEXTURES / "hotbar_end_cap.png": cap_bytes,
        TEXTURES / "astralrecord/hotbar_selection.png": png(strip.pixels),
    })
    for path, expected in assets.items():
        if args.check:
            if not path.is_file() or path.read_bytes() != expected:
                raise SystemExit(f"Out-of-date Bedrock hotbar texture: {path}")
        else:
            path.write_bytes(expected)

    if args.preview:
        preview = Raster(192, 30 * frame_count, (29, 38, 46, 255))
        for index, frame in enumerate(frames):
            top = index * 30 + 4
            for column in range(9):
                preview.paste(slot_image.pixels, 6 + 20 * column, top)
            preview.paste(frame.pixels, 84, top - 1)
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        rows = [[pixel for pixel in row for _ in range(2)] for row in preview.pixels]
        args.preview.write_bytes(png([row for row in rows for _ in range(2)]))

    print(f"Bedrock hotbar: 9 slots, 2 end caps and {frame_count} selection frames")


if __name__ == "__main__":
    main()
