#!/usr/bin/env python3
"""Generate the 16-pixel Cartograph slot art shared by Java and Bedrock."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "assets/astralrecord/textures/item/gui"
BEDROCK = ROOT / "bedrock/textures/ui/astralrecord/gui"
TRANSPARENT = (0, 0, 0, 0)
ROOM_COLORS = {
    "entrance": ("#122c2f", "#22625a", "#64c6a6", "#c2f0cf"),
    "cleared": ("#122c2f", "#22625a", "#64c6a6", "#c2f0cf"),
    "available": ("#372815", "#6e4c25", "#dda755", "#ffdda1"),
    "active": ("#3e211d", "#834333", "#e48d5c", "#ffcc9c"),
    "locked": ("#20282e", "#323e48", "#7c8990", "#a2b0b6"),
    "current": ("#0f343f", "#216875", "#68d9e0", "#e2fcf9"),
}
HEADINGS = ("north", "north_east", "east", "south_east", "south", "south_west", "west", "north_west")
PATH_DIRECTIONS = (("n", 0, -1), ("e", 1, 0), ("s", 0, 1), ("w", -1, 0))


def color(value):
    return tuple(bytes.fromhex(value.removeprefix("#"))) + (255,)


def image():
    return [[TRANSPARENT for _ in range(16)] for _ in range(16)]


def point(pixels, x, y, ink):
    if 0 <= x < 16 and 0 <= y < 16:
        pixels[y][x] = color(ink)


def line(pixels, x0, y0, x1, y1, ink, width=1):
    steps = max(abs(x1 - x0), abs(y1 - y0), 1)
    for step in range(steps + 1):
        x = round(x0 + (x1 - x0) * step / steps)
        y = round(y0 + (y1 - y0) * step / steps)
        for dx in range(width):
            for dy in range(width):
                point(pixels, x + dx, y + dy, ink)


def rectangle(pixels, x0, y0, x1, y1, ink):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            point(pixels, x, y, ink)


def polygon(pixels, vertices, ink):
    for y in range(16):
        for x in range(16):
            crossing = False
            for a, b in zip(vertices, vertices[1:] + vertices[:1]):
                if (a[1] > y + .5) != (b[1] > y + .5):
                    edge = a[0] + (y + .5 - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
                    if x + .5 < edge:
                        crossing = not crossing
            if crossing:
                point(pixels, x, y, ink)


def arrow(pixels, heading, ink):
    angle = HEADINGS.index(heading) * math.pi / 4
    dx, dy = math.sin(angle), -math.cos(angle)
    px, py = -dy, dx
    # A broad tip and narrow tail remain legible at the native 16-pixel size.
    shape = []
    for forward, side in ((5, 0), (-1, 3), (-1, 1), (-4, 1), (-4, -1), (-1, -1), (-1, -3)):
        shape.append((round(7.5 + forward * dx + side * px),
                      round(7.5 + forward * dy + side * py)))
    polygon(pixels, [(x, y + 1) for x, y in shape], "#101a23")
    polygon(pixels, shape, ink)


def path_icon(open_directions="", closed_directions=""):
    pixels = image()
    for directions, (shadow, middle, light) in (
        (closed_directions, ("#292f37", "#525d67", "#929ba3")),
        (open_directions, ("#223c44", "#4e9a95", "#a0d5bb")),
    ):
        for symbol, dx, dy in PATH_DIRECTIONS:
            if symbol not in directions:
                continue
            for offset in range(9):
                for across in range(-2, 2):
                    x = 7 + dx * (offset - 1) + dy * across
                    y = 7 + dy * (offset - 1) - dx * across
                    point(pixels, x, y, shadow if across == -2 else middle if across == 1 else light)
    return pixels


def direction_suffix(mask):
    return "".join(name for bit, (name, _, _) in enumerate(PATH_DIRECTIONS) if mask & (1 << bit))


def room_icon(state, heading=None):
    pixels = image()
    ink, face, rim, sign = ROOM_COLORS[state]
    polygon(pixels, [(3, 1), (12, 1), (14, 3), (14, 12), (12, 14),
                     (3, 14), (1, 12), (1, 3)], ink)
    polygon(pixels, [(4, 2), (11, 2), (13, 4), (13, 11), (11, 13),
                     (4, 13), (2, 11), (2, 4)], face)
    for a, b in (((2, 6), (2, 4)), ((2, 4), (4, 2)), ((4, 2), (11, 2)),
                 ((11, 2), (13, 4)), ((13, 4), (13, 6)), ((2, 9), (2, 11)),
                 ((2, 11), (4, 13)), ((4, 13), (11, 13)), ((11, 13), (13, 11)),
                 ((13, 11), (13, 9))):
        line(pixels, *a, *b, rim)
    rectangle(pixels, 4, 5, 11, 11, ink)
    if heading:
        arrow(pixels, heading, sign)
    elif state == "cleared":
        line(pixels, 4, 8, 6, 10, sign, 2)
        line(pixels, 6, 10, 11, 5, sign, 2)
    elif state == "entrance":
        for a, b in (((5, 10), (5, 6)), ((5, 6), (7, 4)), ((7, 4), (9, 4)),
                     ((9, 4), (11, 6)), ((11, 6), (11, 10))):
            line(pixels, *a, *b, sign)
        rectangle(pixels, 7, 8, 9, 10, sign)
    elif state == "available":
        for a, b in (((8, 4), (11, 7)), ((11, 7), (8, 10)),
                     ((8, 10), (5, 7)), ((5, 7), (8, 4))):
            line(pixels, *a, *b, sign)
        point(pixels, 8, 7, sign)
    elif state == "active":
        rectangle(pixels, 7, 5, 8, 8, sign)
        rectangle(pixels, 7, 10, 8, 10, sign)
    else:
        for a, b in (((6, 7), (6, 5)), ((6, 5), (7, 4)), ((7, 4), (9, 4)),
                     ((9, 4), (10, 5)), ((10, 5), (10, 7))):
            line(pixels, *a, *b, sign)
        rectangle(pixels, 5, 7, 11, 10, sign)
        point(pixels, 8, 8, ink)
    return pixels


def direction_icon(heading):
    pixels = image()
    polygon(pixels, [(7, 1), (11, 3), (14, 7), (12, 12), (8, 14),
                     (4, 12), (1, 8), (3, 4)], "#142335")
    for a, b in zip(((7, 1), (11, 3), (14, 7), (12, 12), (8, 14), (4, 12), (1, 8), (3, 4)),
                    ((11, 3), (14, 7), (12, 12), (8, 14), (4, 12), (1, 8), (3, 4), (7, 1))):
        line(pixels, *a, *b, "#bb8a50")
    arrow(pixels, heading, "#b2e9e5")
    return pixels


def all_icons():
    result = {f"cartograph_room_{state}": room_icon(state)
              for state in ("entrance", "cleared", "available", "active", "locked")}
    for heading in HEADINGS:
        result[f"cartograph_room_current_{heading}"] = room_icon("current", heading)
        result[f"cartograph_direction_{heading}"] = direction_icon(heading)
    for mask in range(1, 16):
        directions = direction_suffix(mask)
        result[f"cartograph_path_open_{directions}"] = path_icon(open_directions=directions)
        result[f"cartograph_path_closed_{directions}"] = path_icon(closed_directions=directions)
        for open_mask in range(1, mask):
            # A connection passing through this slot has two distinct neighbouring slots.
            if open_mask.bit_count() < 2 or (open_mask & mask) != open_mask:
                continue
            opened = direction_suffix(open_mask)
            closed = direction_suffix(mask ^ open_mask)
            result[f"cartograph_path_mixed_{opened}_{closed}"] = path_icon(opened, closed)
    return result


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)


def png(pixels):
    scaled = [[pixel for pixel in row for _ in range(2)] for row in pixels for _ in range(2)]
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel) for row in scaled)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    icons = all_icons()
    registry = json.loads((ROOT / "gui-icons.json").read_text(encoding="utf-8-sig"))
    registered = {entry["role"] for entry in registry["icons"] if entry["role"].startswith("cartograph_")}
    if registered != icons.keys():
        raise ValueError(f"Cartograph registry mismatch: missing={icons.keys()-registered}, extra={registered-icons.keys()}")
    mismatches = []
    for role, pixels in icons.items():
        data = png(pixels)
        for directory in (JAVA, BEDROCK):
            target = directory / f"gui_{role}.png"
            if args.check:
                if not target.exists() or target.read_bytes() != data:
                    mismatches.append(str(target.relative_to(ROOT)))
            else:
                directory.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
    if args.preview:
        # Contact sheet uses only the same generated PNGs; no client asset assumptions.
        from PIL import Image, ImageDraw
        roles = list(icons)
        sheet = Image.new("RGB", (8 * 82, math.ceil(len(roles) / 8) * 84), "#273747")
        draw = ImageDraw.Draw(sheet)
        for index, role in enumerate(roles):
            tile = Image.open(JAVA / f"gui_{role}.png").convert("RGBA")
            sheet.paste(tile.resize((64, 64), Image.Resampling.NEAREST),
                        ((index % 8) * 82 + 9, (index // 8) * 84 + 3),
                        tile.resize((64, 64), Image.Resampling.NEAREST))
            draw.text(((index % 8) * 82 + 2, (index // 8) * 84 + 69),
                      role.removeprefix("cartograph_")[:12], fill="white")
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        sheet.save(args.preview)
    if mismatches:
        raise SystemExit("Cartograph art differs:\n" + "\n".join(mismatches))
    print(f"Cartograph art {'verified' if args.check else 'generated'}: {len(icons)} roles, both editions; "
          f"SHA256 {hashlib.sha256(b''.join(png(p) for p in icons.values())).hexdigest()}")


if __name__ == "__main__":
    main()
