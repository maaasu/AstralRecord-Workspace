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

STYLES = {
    # Fill highlight, base, stroke. Keep the dark stroke visible on bright terrain.
    "normal": ((255, 204, 193), (252, 78, 86), (63, 13, 24)),
    "critical": ((255, 251, 213), (255, 178, 44), (68, 34, 11)),
    "shield": ((227, 253, 255), (78, 213, 249), (10, 40, 66)),
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


def atlas(path, java=False, palette=None):
    width = 112 if java else 128
    height = HEIGHT if java else 16
    rows = [[0] * (width * 4) for _ in range(height)]
    for idx, pattern in enumerate(PATTERNS):
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
    for name, parent in (("rise", None), ("billboard_yaw", "rise"),
                         ("billboard_pitch", "billboard_yaw"), ("impact", "billboard_pitch"),
                         ("line", "impact")):
        bones.append({"name": name, **({"parent": parent} if parent else {}), "pivot": [0, 0, 0]})
    controller = {
        "arrays": {"textures": {"Array.styles": ["Texture.normal", "Texture.critical", "Texture.shield"]}},
        "geometry": "Geometry.default",
        "materials": [{"*": "Material.default"}],
        "textures": ["Array.styles[variable.style]"],
        "color": {"r": 1, "g": 1, "b": 1, "a": "1.0 - variable.fade * variable.fade"},
        "ignore_lighting": True,
        "part_visibility": [],
    }
    for slot in range(SLOTS):
        # The front face is north in geometry space. Screen-right is negative X.
        x = round(-slot * 2.0 - 0.9, 3)
        for glyph in range(len(PATTERNS)):
            name = f"slot_{slot}_glyph_{glyph}"
            bones.append({
                "name": name, "parent": "line", "pivot": [0, 0, 0],
                "cubes": [{"origin": [x, -1.3, 0], "size": [1.8, 2.6, 0],
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
             "height": HEIGHT, "ascent": 8, "chars": ["0123456789,\u2212\u2726\u25c7"]},
            {"type": "reference", "id": "minecraft:default"},
        ],
    })
    atlas(JAVA / "textures/font/damage_number.png", java=True)
    for style, palette in STYLES.items():
        atlas(BEDROCK / f"textures/entity/damage_number/{style}.png", palette=palette)
    model_and_controller()
