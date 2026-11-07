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
    "B": (127, 196, 245, 255),
    "b": (60, 115, 172, 255),
    "P": (205, 164, 251, 255),
    "p": (119, 82, 168, 255),
    "O": (230, 151, 76, 255),
    "o": (148, 85, 44, 255),
    "M": (159, 214, 111, 255),
    "m": (75, 125, 69, 255),
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
RECESSED_ROLES = {"disabled", "processing", "unset", "setting_save_guide"}
FILLER_ROLES = ("frame", "panel")

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
    "increase": (
        "...LLL....", "...LLL....", "...LTT....", "LLLLTTTTT.", "LLTTTTTTT.",
        "LLTTTTTTT.", "...TTT....", "...TTT....", "...TTT....",
    ),
    "decrease": (
        "..........", "..........", "..........", ".rrrrrrrr.", ".rRRRRRRR.",
        ".RRRRRRRR.", "..........", "..........", "..........",
    ),
}


# Larger silhouettes and a role-specific palette distinguish menu and setting items.
PICTOGRAMS: dict[str, tuple[str, ...]] = {
    "menu": (
        "Bbbb.TTTT", "Bbbb.TLLT", "Bbbb.TTTT", ".........",
        "GGGG.Pppp", "GHHG.Pppp", "GGGG.Pppp",
    ),
    "profile": (
        "....BBBB....", "...BBWWBb...", "...BWWBBb...", "...bBBBbb...",
        "....bbb.....", "..BBBBBBBB..", ".BBLLLLBBBB.", "BBLLLLTBBBBb",
        "BLLLLTTBBBbb", "bbbbbbbbbbbb",
    ),
    "quest": (
        "..rrrrrrr..", "..rRRRRrR..", "..rRWWRrR..", "..rRWWRrR..",
        "..rRWWRrR..", "..rRRRRrR..", "..rRWWRrR..", "..rRRRRrR..",
        "..rRRRRrR..", "..rRR.RrR..", "..rr...RR..",
    ),
    "settings": (
        "...SS.SS...", "...WW.SS...", ".SSWWWSSSS.", "SWWWWSSSSSS",
        "SWWW...SSSS", ".WW..B..SS.", "SWW.BBB.SSS", "SWSS...SSSS",
        ".SSSSSSSSS.", "...SS.SS...",
    ),
    "equipment": (
        "..SS....SS..", ".WWSS..SSSS.", "WWSWWWWSSSSS", "WW.WWSSSS.SS",
        "...WWSSSS...", "...WWSSSS...", "...WWSSSS...", "...WWSSSS...",
        "...WWSSSS...", "...SSSSSS...",
    ),
    "trash": (
        "....SSS....", "..SSSSSSS..", ".SWWWWWWSS.", "..mMMMMMm..",
        "..mMsMsMm..", "..mMsMsMm..", "..mMsMsMm..", "..mMMMMMm..",
        "...mmmmM...",
    ),
    "guide": (
        ".HWWWHWWWH.", "oHHHHGHHHHp", "oHWWHGHWWHp", "oHWGHGHGGHp",
        "oHWWGGHWWGp", "oHWWGHWWWHp", "oHHHGGHHHGp", ".ooGGGpppp.",
        ".....Pp....",
    ),
    "warp": (
        ".....TT.....", "...TLLLLT...", "..TLT..TLT..", ".TLT....TLT.",
        ".LT......TL.", ".LT...H..TL.", ".LT...HH.TL.", ".LT.HHHHHTL.",
        ".TLT..HHTL.", "..TLT.HLT..", "...TLLLLT...", ".....TT.....",
    ),
    "adventure": (
        "....HHH....", "..HHBBbHH..", ".HBBWWBBbH.", ".HBBBWrBbH.",
        "HBBBWrRBbbG", "HBBWrRRbbbG", "HBBWWRbbbbG", ".HBWbbbbbG.",
        ".HbbbbbbbG.", "..GGbbbGG..", "....GGG....",
    ),
    "mail": (
        ".HWWWWWWWWH.", "HGGWWWWWWGGH", "HWHGWWWWGHWH", "HWWHGGGGHWWH",
        "HWWWHooHWWWH", "HWWWRrrRWWWH", "HWWWRrrRWWWH", "GGGGGRRGGGGG",
        ".oooooooooo.",
    ),
    "skill": (
        "......LL...", ".....LLT...", "....LLT....", "...LLTT....",
        "..LLTTTTT..", ".LLLLLTT...", ".....TT....", "....TT.....",
        "...TT......", "..T........",
    ),
    "currency": (
        "......HHHH..", ".....HGGGOH.", ".....HGHHOG.", "..HHHHHGOGG.",
        ".HGGGOHOGG..", ".HGHGOGGGoo.", ".HGHGOG.ooo.", ".HGGOOG.....",
        "..GGGG......", "..OOOO......",
    ),
    "party": (
        "..PP....BB..", ".PHHP..BWWB.", ".PHHP..BWWB.", "..pp....bb..",
        ".PPPP..BBBB.", "PPPPp.BBBBbb", "PPPpp.BBBbbb", "ppppp.bbbbbb",
    ),
    "players": (
        ".BBBBBBBBBB.", ".BWWWWWWWWb.", ".BPPWbbbbWb.", ".BppWWWWWWb.",
        ".BWWWWWWWWb.", ".BLTWbbbbWb.", ".BTTWWWWWWb.", ".BWWWWWWWWb.",
        ".BOGWbbbbWb.", ".BooWWWWWWb.", ".bbbbbbbbbb.",
    ),
    "inventory": (
        "....OOO....", "...Oo.oO...", "...OOOOO...", "..OoOOOoO..",
        ".OOoOOOoOO.", ".OoOOOOOoO.", ".OoGGGGGoO.", ".OoGHHHGoO.",
        ".OoGGGGGoO.", ".ooooooooo.",
    ),
    "storage": (
        "..OOOOOOOO..", ".OGGGGGGGGO.", "OGOOOOOOOOGO", "GGGGGGGGGGGG",
        "ooOOOHHOOOoo", "oOOOOHGOOOOo", "oOOOOOOOOOOo", "oOoOOOOOOoOo",
        "oooooooooooo",
    ),
    "setting_damage_log": (
        "...HHHHHHH..", "..HWWWWWWGH.", "..HWbbWWWGH.", ".rHWbWWWWGH.",
        "rRHWbWWWWGH.", "rRHWbWbWWGH.", ".RHWbWbWbGH.", "..HWWWWWWGH.",
        "..GGGGGGGGG.",
    ),
    "setting_damage_size": (
        "..BB......B.", ".BBBB....BB.", "BB..BB...BB.", "BB..BB...BB.",
        "BBBBBB...BB.", "BB..BB...BB.", "BB..BB...BB.", "............",
        "....HHHH....", ".....HH.....",
    ),
    "setting_damage_message": (
        ".BBBBBBBBBB.", "BWWWWWWWWWWb", "BWbbbbbbbbWb", "BWWWWWWWWWWb",
        "BWbbbbbWWWWb", "BWWWWWWWWWWb", ".bbbbbbbbbb.", "...bb.......",
        "..b.........",
    ),
    "setting_particles": (
        "..H.....P...", ".HHH....P...", "..H....PPP..", "....H...P...",
        "...HHH..P...", "..HHHHH.....", "...HHH...T..", "....H...TTT.",
        ".P.......T..",
    ),
    "setting_drop_log": (
        "..HHHHHHHH..", ".HWWWWWWWWG.", ".HWOOOOWWWG.", ".HWOOoWWWWG.",
        ".HWooOWbWWG.", ".HWWWWWWWWG.", ".HWbbbbbbWG.", ".HWWWWWWWWG.",
        ".GGGGGGGGGG.",
    ),
    "setting_performance": (
        "....SSSS....", "..SSWWWWSS..", ".SWWWWMWWWS.", ".SWrWWmMMWS.",
        "SWWWWMmWWWWS", "SWWWMmWWWWWS", "SWWWmWWWWWWS", ".SSSSSSSSSS.",
    ),
    "setting_auto_save": (
        ".BBBBBBBBB..", ".BWSSSSSWBb.", ".BWSSSSSWBBb", ".BBBBBBBBBBb",
        ".BWWWWWWWWBb", ".BWLLLLLLWBb", ".BWLWWWWLWBb", ".BWLWWWWLWBb",
        ".BBBBBBBBBBb",
    ),
    "setting_buff_sidebar": (
        ".SSSSSSSSSS.", ".SWWWWLTTTS.", ".SWSSWLTTTS.", ".SWWWWLTTTS.",
        ".SWSSWMMMMS.", ".SWWWWMMMMS.", ".SWSSWPPppS.", ".SWWWWPPppS.",
        ".SSSSSSSSSS.",
    ),
    "setting_action_ring": (
        "....TTTT....", "..TTLLLTTT..", ".TTL....LTT.", "TT......LTT.",
        "TL...HH...LT", "TL...HH...LT", "TL........LT", "TT........TT",
        ".TT......TT.", "..TTLLLLTT..", "....TTTT....",
    ),
    "setting_skill_tree": (
        "....PPPP....", "....PPPP....", ".....pp.....", "..pppppppp..",
        "..p......p..", "BBBB....TTTT", "BBBB....TTTT", "..b......T..",
        "..b......T..", ".bbb....TTT.",
    ),
    "setting_night_vision": (
        "....HHHH....", "...HHH......", "..HHH....B..", ".HHH....BBB.",
        ".HHH.....B..", ".HHHH.......", "..HHHHH..P..", "...HHHHHH...",
        "....HHHH....",
    ),
    "setting_status_range": (
        "B..........r", "B.B......r.r", "B.BB....rr.r", "B.BBBBBrrr.r",
        "B.BB....rr.r", "B.B......r.r", "B..........r",
    ),
    "setting_dps": (
        "..........M.", ".....M...MM.", ".....MM..MM.", ".M..MMM.MMM.",
        ".MMMMMMMMMM.", ".mmMMmmMMMM.", ".....m...mm.", "SSSSSSSSSSSS",
    ),
    "setting_save_guide": (
        ".SSSSS.SSSSS", ".SWWWWsWWWWS", ".SWWsWsWsWWS", ".SWWWWsWWWWS",
        ".SWssWsWssWS", ".SWWWWsWWWWS", ".SSSSSsSSSSS",
    ),
    "skill_unavailable": (
        "....rrr.....", "...rRRRr....", "..rR...Rr...", "..rR...Rr...",
        "..rR...Rr...", ".rrrrrrrrr..", "rRRRRRRRRRR.", "rRRRWWRRRRR.",
        "rRRRWWRRRRR.", "rRRRRWRRRRR.", ".RRRRRRRRR..", "..RRRRRRR...",
    ),
}


def mirror(pattern: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(row[::-1] for row in pattern)


PATTERNS["previous"] = mirror(PATTERNS["next"])
PATTERNS["down"] = PATTERNS["up"][::-1]
ROLES = (*PATTERNS, *FILLER_ROLES, *PICTOGRAMS)


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
    if role in FILLER_ROLES:
        # The item still occupies the slot; only the underlying GUI should show.
        return [[COLORS["."]] * 16 for _ in range(16)]

    recessed = role in RECESSED_ROLES
    pictogram = role in PICTOGRAMS
    if pictogram and not recessed:
        pixels = [[COLORS["."]] * 16 for _ in range(16)]
    else:
        palette = RECESSED_COLORS if recessed else BUTTON_COLORS
        pixels = [[palette[c] for c in row] for row in source["rows"]]

    pattern = PICTOGRAMS[role] if pictogram else PATTERNS[role]
    max_size = 12 if pictogram else 10
    width = max(map(len, pattern))
    if len(pattern) > max_size or width > max_size:
        raise ValueError(f"Glyph outside inner {max_size}x{max_size} design area: {role}")
    left = (16 - width) // 2
    top = (16 - len(pattern)) // 2

    # A contour keeps free-standing symbols legible over both editions' slot colors.
    # Framed controls keep their smaller, offset shadow.
    outline = ((0, -1), (-1, 0), (1, 0), (0, 1), (1, 1)) if pictogram else ((1, 1),)
    for y, row in enumerate(pattern):
        for x, c in enumerate(row):
            if c != ".":
                for dx, dy in outline:
                    pixels[top + y + dy][left + x + dx] = COLORS["K"]
    for y, row in enumerate(pattern):
        for x, c in enumerate(row):
            if c != ".":
                pixels[top + y][left + x] = COLORS[c]
    if not pictogram and not recessed:
        # Highlight the upper and left rim of navigation and confirmation buttons.
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
