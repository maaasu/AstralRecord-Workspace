#!/usr/bin/env python3
"""Regenerate shared GUI control art with Python's standard library only.

The relief comes from the selected CC0 Kenney tile recorded in gui-source/.
The semantic glyphs are original pixel drawings. Generation never edits pack
registries or layout JSON. Use --check for byte comparison, --preview <png>
for a sheet containing native-16px, distributed-32px and magnified samples.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "gui-source/kenney-pixel-adventure/stone-tile-pixels.json"
JAVA = ROOT / "assets/astralrecord/textures/item/gui"
BEDROCK = ROOT / "bedrock/textures/ui/astralrecord/gui"

# Native design grid: 16px. Every pixel becomes 2x2 in the shipped 32px PNG.
# Deliberately restrained slate, weathered metal, cream and verdigris.
COLORS = {
    ".": (0, 0, 0, 0),
    "K": (23, 31, 38, 255),
    "G": (216, 179, 99, 255),
    "H": (247, 222, 160, 255),
    "T": (103, 188, 181, 255),
    "L": (177, 229, 210, 255),
    "R": (205, 105, 99, 255),
    "r": (246, 172, 146, 255),
    "S": (140, 164, 170, 255),
    "s": (96, 121, 133, 255),
    "W": (229, 235, 215, 255),
}
BUTTON_COLORS = {
    "0": COLORS["."],
    "1": (29, 42, 47, 255),
    "2": (41, 75, 76, 255),
    "3": (255, 211, 118, 255),
    "4": (118, 96, 61, 255),
    "5": (48, 86, 87, 255),
}
BUTTON_EDGE_SHADE = (184, 127, 59, 255)
RECESSED_COLORS = {
    "0": COLORS["."],
    "1": (54, 67, 72, 255),
    "2": (44, 55, 61, 255),
    "3": (49, 61, 67, 255),
    "4": (62, 76, 81, 255),
    "5": (46, 57, 63, 255),
}
RECESSED_ROLES = {"disabled", "frame", "panel", "processing", "unset"}

# Patterns are centered in the inner 10x10 design area. Shapes communicate the
# action independently of color; no text glyphs depend on client language.
PATTERNS: dict[str, tuple[str, ...]] = {
    "next": (
        "....H.....", "....HH....", "....GGH...", ".HHHGGGH..",
        ".HGGGGGGH.", ".GGGGGGG..", "....GGG...", "....GG....", "....G.....",
    ),
    "up": (
        "....H.....", "...HHH....", "..HGGGH...", ".HGGGGGH..", "HGGGGGGGH.",
        "...GGG....", "...GGG....", "...GGG....", "...GGG....",
    ),
    "close": (
        ".rr....rr.", ".rRR..RRr.", "..RRRRRR..", "...RRRR...",
        "...RRRR...", "..RRRRRR..", ".RRR..RRR.", ".RR....RR.",
    ),
    "back": (
        "..H.......", ".HH.......", "HGGHHHH...", ".HGGGGGH..", "..G...GGH.",
        "......GGG.", "......GGG.", "...TGGGG..", "...TTGG...",
    ),
    "confirm": (
        "........L.", ".......LL.", "......LT..", ".L...LT...", ".LL.LT....",
        "..LTT.....", "...T......",
    ),
    "cancel": (
        "..rrrrr...", ".rRRRrRR..", "rR...r.RR.", "rR..r..RR.", "rR.r...RR.",
        "rRr....RR.", ".RRRRRRR..", "..RRRRR...",
    ),
    "disabled": (
        "...SSS....", "..S...S...", "..s...s...", ".SSSSSSS..", ".SssWsss..",
        ".SssWsss..", ".SssWsss..", ".Sssssss..", ".sssssss..",
    ),
    "processing": (
        ".SSSSSSSS.", "..S....S..", "..Ss..sS..", "...SssS...", "....ss....",
        "...ssss...", "..s.ss.s..", "..ssssss..", ".ssssssss.",
    ),
    "unset": (
        "....S.....", "...S.S....", "..S...S...", ".S.....S..", "S.......S.",
        ".S.....S..", "..S...S...", "...S.S....", "....S.....",
    ),
    "menu": (
        ".HHHHHHHH.", ".HWWWWWWG.", ".GTTTWWWG.", ".GWWWWWWG.", ".GTTTTTWG.",
        ".GWWWWWWG.", ".GTTTTTWG.", ".GGGGGGGG.",
    ),
    "profile": (
        "...HHHH...", "..HWWWWG..", "..HWWWWG..", "...GGGG...", "....TT....",
        "..LTTTTT..", ".LTTTTTTT.", ".TTTTTTTT.", ".TTTTTTTT.",
    ),
    "quest": (
        ".HHHHHHH..", ".HWWWWWHG.", ".HWWGWWGG.", ".HWWGWWWG.", ".HWWGWWWG.",
        ".HWWWWWWG.", ".HWWGWWWG.", ".HWWWWWWG.", ".GGGGGGGG.",
    ),
    "settings": (
        "...HHHH...", ".HHHssHHH.", ".HssSSssH.", "HHsTTTTsHH", "HssT..TssH",
        "GssT..TssG", "GGsTTTTsGG", ".GssSSssG.", ".GGGssGGG.", "...GGGG...",
    ),
    "equipment": (
        "...HHHH...", "..HWWWWG..", ".HWWWWWWG.", ".HWTTTTWG.", ".HWTTTTWG.",
        ".HG....GG.", ".HG....GG.", ".GG....GG.",
    ),
    "trash": (
        "...HHHH...", ".HHHHHHHH.", "..GTTTTG..", "..GTGTGG..", "..GTGTGG..",
        "..GTGTGG..", "..GTGTGG..", "..GGGGGG..",
    ),
    "guide": (
        ".HHHH.HHH.", "HWWWHHWWWH", "HWTTGHWTTG", "HWWWGHWWWG", "HWTTGHWTTG",
        "HWWWGHWWWG", "HWWWGHWWWG", ".GGGG.GGG.", "....GG....",
    ),
    "warp": (
        "...HHHH...", "..HTTTTG..", ".HT....TG.", ".HT.TT.TG.", ".HT.TL.TG.",
        ".HT.TT.TG.", ".HT....TG.", ".GTTTTTTG.", ".GGGGGGGG.",
    ),
    "adventure": (
        "....H.....", "...HTH....", "..HTTTH...", ".HTTTTTH..", "HTTTWGGGH.",
        ".HTTGGGH..", "..HTGGH...", "...HGH....", "....G.....",
    ),
    "mail": (
        ".HHHHHHHH.", ".HWWWWWWG.", ".HHWWWWGG.", ".HWHWWGWG.", ".HWWGGWWG.",
        ".HWWWWWWG.", ".HWWWWWWG.", ".GGGGGGGG.",
    ),
    "skill": (
        "....L.....", "....LT....", ".L..LT..L.", "..LLLLLT..", "LLLLWTTTTT",
        "..LTTTTT..", ".L..TT..T.", "....TT....", "....T.....",
    ),
    "currency": (
        "...HHHH...", "..HWWWWG..", ".HWGGWWWG.", ".HWG.GWWG.", ".HWG.GWWG.",
        ".HWG.GWWG.", ".HWWGGWWG.", "..GWWWGG..", "...GGGG...",
    ),
    "party": (
        "..HH..LL..", ".HWWGLTTT.", ".HWWGLTTT.", "..GG..TT..", "..........",
        ".HHHHLLLL.", "HWWWWTTTTT", "HWWWWTTTTT", ".GGGGTTTT.",
    ),
    "players": (
        "....HH....", ".LLHWWGRR.", ".TTLWWGRR.", "....GG....", ".LL.HH.RR.",
        "LTTLWWGRRR", "TTTHWWGRRR", "TTTHWWGRRR", "...GGGG...",
    ),
    "inventory": (
        "...HHHH...", "...H..G...", "..HHHHHH..", ".HTTTTTTG.", ".HTTTTTTG.",
        ".HTHHHHTG.", ".HTHTGHTG.", ".HTHHHHTG.", "..GGGGGG..",
    ),
    "storage": (
        "..HHHHHH..", ".HWWWWWWG.", ".HWGGGGWG.", ".HGGGGGGG.", ".HWWWGWWG.",
        ".HWWGHWWG.", ".HWWWGWWG.", ".GGGGGGGG.",
    ),
    "increase": (
        "...LLL....", "...LLL....", "...LTT....", "LLLLTTTTT.", "LLTTTTTTT.",
        "LLTTTTTTT.", "...TTT....", "...TTT....", "...TTT....",
    ),
    "decrease": (
        "..........", "..........", "..........", ".rrrrrrrr.", ".rRRRRRRR.",
        ".RRRRRRRR.", "..........", "..........", "..........",
    ),
}


def mirror(pattern: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(row[::-1] for row in pattern)


PATTERNS["previous"] = mirror(PATTERNS["next"])
PATTERNS["down"] = PATTERNS["up"][::-1]
ROLES = (
    "previous", "next", "up", "down", "close", "back", "confirm", "cancel",
    "disabled", "frame", "panel", "processing", "unset", "menu", "profile",
    "quest", "settings", "equipment", "trash", "guide", "warp", "adventure",
    "mail", "skill", "currency", "party", "players", "inventory", "storage",
    "increase", "decrease",
)


def chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def png(pixels: list[list[tuple[int, int, int, int]]]) -> bytes:
    height, width = len(pixels), len(pixels[0])
    raw = b"".join(b"\0" + bytes(c for rgba in row for c in rgba) for row in pixels)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def scale(pixels: list[list[tuple[int, int, int, int]]], factor: int) -> list[list[tuple[int, int, int, int]]]:
    return [[color for color in row for _ in range(factor)] for row in pixels for _ in range(factor)]


def design(role: str, source: dict) -> list[list[tuple[int, int, int, int]]]:
    recessed = role in RECESSED_ROLES
    palette = RECESSED_COLORS if recessed else BUTTON_COLORS
    pixels = [[palette[c] for c in row] for row in source["rows"]]
    if role == "frame":
        return pixels
    if role == "panel":
        for x in range(4, 12):
            pixels[7][x] = (34, 43, 48, 255)
            pixels[8][x] = RECESSED_COLORS["4"]
        return pixels
    pattern = PATTERNS[role]
    if len(pattern) > 10 or any(len(row) > 10 for row in pattern):
        raise ValueError(f"Glyph outside inner 10x10 design area: {role}")
    left = (16 - max(map(len, pattern))) // 2
    top = (16 - len(pattern)) // 2
    # A one-design-pixel shadow, not a blurred or antialiased halo.
    for y, row in enumerate(pattern):
        for x, c in enumerate(row):
            if c != ".":
                pixels[top + y + 1][left + x + 1] = COLORS["K"]
    for y, row in enumerate(pattern):
        for x, c in enumerate(row):
            if c != ".":
                pixels[top + y][left + x] = COLORS[c]
    if not recessed:
        # Highlight the upper and left rim. The amber lower and right rim gives it depth.
        for i in range(3, 13):
            pixels[13][i] = BUTTON_EDGE_SHADE
            pixels[i][13] = BUTTON_EDGE_SHADE
    return pixels


def preview(path: Path, designs: dict[str, list]) -> None:
    # Each cell shows native16, distributed32 and magnified64. A light strip
    # also exposes transparency and contour quality against a bright surface.
    width, cell_w, cell_h = 980, 140, 108
    height = ((len(ROLES) + 6) // 7) * cell_h
    canvas = [[(32, 39, 46, 255)] * width for _ in range(height)]
    for i, role in enumerate(ROLES):
        cx, cy = (i % 7) * cell_w, (i // 7) * cell_h
        for factor, ox, oy in ((1, 6, 8), (2, 27, 8), (4, 65, 8)):
            art = scale(designs[role], factor)
            for y, row in enumerate(art):
                for x, color in enumerate(row):
                    if color[3]:
                        canvas[cy + oy + y][cx + ox + x] = color
        for y in range(80, 104):
            for x in range(4, 136):
                canvas[cy + y][cx + x] = (213, 216, 200, 255)
        art = designs[role]
        for y, row in enumerate(art):
            for x, color in enumerate(row):
                if color[3]:
                    canvas[cy + 84 + y][cx + 9 + x] = color
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png(canvas))
    path.with_suffix(".json").write_text(json.dumps({"columns": 7, "cell_width": cell_w,
        "cell_height": cell_h, "roles": list(ROLES)}, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Compare existing art without writing")
    parser.add_argument("--preview", type=Path, help="Optional untracked review sheet path")
    args = parser.parse_args()
    source = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    if source["width"] != 16 or source["height"] != 16 or len(source["rows"]) != 16:
        raise ValueError("Source relief must be 16x16")
    mismatches = []
    designs = {}
    for role in ROLES:
        art = design(role, source)
        designs[role] = art
        data = png(scale(art, 2))
        for directory in (JAVA, BEDROCK):
            path = directory / f"gui_{role}.png"
            if args.check:
                if not path.exists() or path.read_bytes() != data:
                    mismatches.append(str(path.relative_to(ROOT)))
            else:
                directory.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if args.preview:
        preview(args.preview, designs)
    if mismatches:
        print("GUI regeneration differs:\n" + "\n".join(mismatches))
        return 1
    digest = hashlib.sha256(b"".join(png(scale(designs[r], 2)) for r in ROLES)).hexdigest()
    print(f"GUI art {'verified' if args.check else 'generated'}: {len(ROLES)} roles, both editions; SHA256 {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
