"""Regenerate the small original glyph atlases and Bedrock's slot meshes.

Only Python's standard library is needed. The generated assets are tracked;
the pack build does not require Python.
"""

from pathlib import Path
import json
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "assets/astralrecord"
BEDROCK = ROOT / "bedrock"
SLOTS = 17
CELL = 8
HEIGHT = 10

# Five columns by seven rows. The atlases add a one-pixel stroke.
PATTERNS = (
    ".###./##.##/##.##/##.##/##.##/##.##/.###.",
    "..##./.###./..##./..##./..##./..##./.####",
    ".###./##.##/...##/..##./.##../##.../#####",
    "####./...##/...##/.###./...##/...##/####.",
    "...##/..###/.####/##.##/#####/...##/...##",
    "#####/##.../##.../####./...##/##.##/.###.",
    ".###./##.../##.../####./##.##/##.##/.###.",
    "#####/...##/...##/..##./..##./.##../.##..",
    ".###./##.##/##.##/.###./##.##/##.##/.###.",
    ".###./##.##/##.##/.####/...##/...##/.###.",
    "...../...../...../...../...../..##./..#..",
    "...../...../...../#####/...../...../.....",
    "...../..#../..#../#####/..#../..#../.....",
    "...../..#../.#.#./#...#/.#.#./..#../.....",
)

ICONS = {
    "\u25b2": "..#../..##./.#.#./.####/##.##/##.##/.###.",  # Flame
    "\u26a1": "..##./.##../.##../.###./..##./.##../.#...",  # Lightning
    "\u25cb": "...#./..#.#/...#./.#.../#.#.#/.#.../.....",  # Bubbles
    "\u25c8": "..#../.#.#./#.#.#/.###./#.#.#/.#.#./..#..",  # Status rune
    "\u273f": ".#.#./.#.#./#...#/#...#/..#../.###./.###.",  # Paw
}

# Texture order matches the style codes in DisplayTextService.
STYLE_TEXTURES = ("normal", "critical", "shield", "burning", "shocked", "poison", "condition", "pet", "pet")
PALETTES = {
    # Fill highlight, base, stroke. Keep the dark stroke visible on bright terrain.
    "normal": ((255, 204, 193), (252, 78, 86), (63, 13, 24)),
    "critical": ((255, 251, 213), (255, 178, 44), (68, 34, 11)),
    "shield": ((227, 253, 255), (78, 213, 249), (10, 40, 66)),
    "burning": ((255, 231, 191), (255, 166, 107), (79, 27, 12)),
    "shocked": ((240, 228, 255), (196, 167, 255), (44, 20, 75)),
    "poison": ((234, 255, 171), (175, 227, 101), (37, 54, 14)),
    "condition": ((255, 225, 245), (223, 160, 218), (68, 25, 64)),
    "pet": ((216, 255, 235), (112, 226, 188), (13, 62, 53)),
}
STYLE_ICONS = {
    "burning": "\u25b2",
    "shocked": "\u26a1",
    "poison": "\u25cb",
    "condition": "\u25c8",
    "pet": "\u273f",
}


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def write_json(path, obj):
    write(path, (json.dumps(obj, ensure_ascii=True, indent=2) + "\n").encode())


def png(path, width, height, rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    write(path, b"\x89PNG\r\n\x1a\n"
          + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
          + chunk(b"IDAT", zlib.compress(b"".join(b"\0" + bytes(row) for row in rows), 9))
          + chunk(b"IEND", b""))


def atlas(path, patterns, palette=None):
    java = palette is None
    width = len(patterns) * CELL if java else 128
    height = HEIGHT if java else 16
    rows = [[0] * (width * 4) for _ in range(height)]
    for idx, pattern in enumerate(patterns):
        fill = {(x + 1, y + 1) for y, line in enumerate(pattern.split("/"))
                for x, pixel in enumerate(line) if pixel == "#"}
        for y in range(HEIGHT):
            for x in range(CELL):
                if (x, y) in fill:
                    if java:
                        rgb = (255, 255, 255) if y <= 2 else (238, 238, 238)
                    else:
                        rgb = tuple(round(top + (bottom - top) * max(0, y - 2) / 5)
                                    for top, bottom in zip(*palette[:2]))
                elif any((x + dx, y + dy) in fill
                         for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1))):
                    rgb = (45, 45, 45) if java else palette[2]
                else:
                    continue
                offset = (idx * CELL + x) * 4
                rows[y][offset:offset + 4] = (*rgb, 255)
    png(path, width, height, rows)


def model_and_controller():
    geometry = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {"identifier": "geometry.astralrecord.damage_number",
                            "texture_width": 128, "texture_height": 16,
                            "visible_bounds_width": 6.5, "visible_bounds_height": 4,
                            "visible_bounds_offset": [0, 0.8, 0]},
            "bones": [],
        }],
    }
    bones = geometry["minecraft:geometry"][0]["bones"]
    for name, parent in (("rise", None), ("billboard", "rise"), ("impact", "billboard"), ("line", "impact")):
        bones.append({"name": name, **({"parent": parent} if parent else {}), "pivot": [0, 0, 0]})
    controller = {
        "arrays": {"textures": {"Array.styles": [f"Texture.{style}" for style in STYLE_TEXTURES]}},
        "geometry": "Geometry.default",
        "materials": [{"*": "Material.default"}],
        "textures": ["Array.styles[variable.style]"],
        "color": {"r": 1, "g": 1, "b": 1, "a": "1.0 - variable.fade * variable.fade"},
        "ignore_lighting": True,
        "part_visibility": [],
    }
    for slot in range(SLOTS):
        # The front face is north in geometry space. Screen-right is negative X.
        x = round(-slot * 3.0 - 1.4, 3)
        for glyph in range(len(PATTERNS)):
            name = f"slot_{slot}_glyph_{glyph}"
            bones.append({
                "name": name, "parent": "line", "pivot": [0, 0, 0],
                "cubes": [{"origin": [x, -1.8, 0], "size": [2.8, 3.6, 0.125],
                           "uv": {"north": {"uv": [glyph * CELL, 0], "uv_size": [CELL, HEIGHT]}}}],
            })
            controller["part_visibility"].append({
                name: f"variable.length > {slot} && query.property('astralrecord:glyph_{slot}') == {glyph}"
            })
    # Keep the repeated entries on single lines so slot/glyph diffs stay readable.
    def compact_entries(obj, key, path):
        lines = json.dumps(obj, indent=2).splitlines()
        start = next(i for i, line in enumerate(lines) if line.strip() == f'"{key}": [')
        prefix = lines[start][:-len(lines[start].lstrip())]
        end = next(i for i in range(start + 1, len(lines)) if lines[i].startswith(prefix + "]"))
        entries = json.loads("[" + "\n".join(lines[start + 1:end]) + "]")
        lines[start + 1:end] = [prefix + "  " + json.dumps(entry) + ("," if i < len(entries) - 1 else "")
                                for i, entry in enumerate(entries)]
        write(path, ("\n".join(lines) + "\n").encode())

    compact_entries(geometry, "bones", BEDROCK / "models/entity/damage_number.geo.json")
    compact_entries({"format_version": "1.8.0",
                     "render_controllers": {"controller.render.astralrecord.damage_number": controller}},
                    "part_visibility", BEDROCK / "render_controllers/damage_number.render_controllers.json")


if __name__ == "__main__":
    write_json(JAVA / "font/damage_number.json", {
        "providers": [
            {"type": "space", "advances": {" ": 4}},
            {"type": "bitmap", "file": "astralrecord:font/damage_number.png",
             "height": HEIGHT, "ascent": 8, "chars": ["0123456789,\u2212\u2726\u25c7" + "".join(ICONS)]},
            {"type": "reference", "id": "minecraft:default"},
        ],
    })
    atlas(JAVA / "textures/font/damage_number.png", PATTERNS + tuple(ICONS.values()))
    for style, palette in PALETTES.items():
        # Styles with an icon reuse the shield mark's cell and mesh.
        patterns = PATTERNS[:-1] + (ICONS[STYLE_ICONS[style]],) if style in STYLE_ICONS else PATTERNS
        atlas(BEDROCK / f"textures/entity/damage_number/{style}.png", patterns, palette)
    model_and_controller()
