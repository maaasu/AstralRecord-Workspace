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
CELL_WIDTH = 24
CELL_HEIGHT = 30
JAVA_HEIGHT = 10
BEDROCK_WIDTH = 384
BEDROCK_HEIGHT = 48
BEDROCK_GLYPHS = "0123456789,\u2212\u2726\u25c7"

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
JAVA_GLYPHS = BEDROCK_GLYPHS + "".join(STYLE_ICONS.values())
# The Java client tints this grayscale palette to match the damage type.
JAVA_PALETTE = ((255, 255, 255), (218, 218, 218), (45, 45, 45))


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def write_text(path, content):
    if path.is_file() and b"\r\n" in path.read_bytes():
        content = content.replace("\n", "\r\n")
    write(path, content.encode("utf-8"))


def write_json(path, obj):
    write_text(path, json.dumps(obj, ensure_ascii=True, indent=2) + "\n")


def png(path, width, height, rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    write(path, b"\x89PNG\r\n\x1a\n"
          + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
          + chunk(b"IDAT", zlib.compress(b"".join(b"\0" + bytes(row) for row in rows), 9))
          + chunk(b"IEND", b""))


def read_glyphs():
    """Load one labeled pixel grid per glyph. # is fill, + outline, and . transparent."""
    source = Path(__file__).with_name("damage-number-glyphs.txt")
    glyphs = {}
    for block in source.read_text(encoding="utf-8").strip().split("\n\n"):
        character, *rows = block.splitlines()
        if len(character) != 1 or character not in JAVA_GLYPHS or character in glyphs:
            raise ValueError(f"Unexpected glyph {character!r} in {source.name}")
        if len(rows) != CELL_HEIGHT or any(
            len(row) != CELL_WIDTH or not set(row).issubset(".+#") for row in rows
        ):
            raise ValueError(f"Invalid {CELL_WIDTH}x{CELL_HEIGHT} mask for {character!r}")
        glyphs[character] = rows
    if glyphs.keys() != set(JAVA_GLYPHS):
        raise ValueError(f"Missing glyphs in {source.name}: {set(JAVA_GLYPHS) - glyphs.keys()}")
    return glyphs


def atlas(path, characters, glyphs, palette, width, height):
    rows = [bytearray(width * 4) for _ in range(height)]
    highlight, base, outline = palette
    for y in range(CELL_HEIGHT):
        progress = max(0.0, min(1.0, (y - 5) / 19.0))
        fill = tuple(round(top + (bottom - top) * progress)
                     for top, bottom in zip(highlight, base))
        lit_edge = tuple(round(channel + (255 - channel) * 0.20) for channel in fill)
        for index, character in enumerate(characters):
            mask = glyphs[character]
            for x, pixel in enumerate(mask[y]):
                if pixel == "#":
                    rgb = lit_edge if y > 0 and mask[y - 1][x] != "#" else fill
                elif pixel == "+":
                    rgb = outline
                else:
                    continue
                offset = (index * CELL_WIDTH + x) * 4
                rows[y][offset:offset + 4] = (*rgb, 255)
    png(path, width, height, rows)


def model_and_controller():
    geometry = {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {"identifier": "geometry.astralrecord.damage_number",
                            "texture_width": BEDROCK_WIDTH, "texture_height": BEDROCK_HEIGHT,
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
        # The billboard shows the south face to the camera. Screen-right is positive X.
        x = round(slot * 3.0 - 1.4, 3)
        for glyph in range(len(BEDROCK_GLYPHS)):
            name = f"slot_{slot}_glyph_{glyph}"
            bones.append({
                "name": name, "parent": "line", "pivot": [0, 0, 0],
                "cubes": [{"origin": [x, -1.8, -0.125], "size": [2.8, 3.6, 0.125],
                           "uv": {"south": {"uv": [glyph * CELL_WIDTH, 0],
                                            "uv_size": [CELL_WIDTH, CELL_HEIGHT]}}}],
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
        write_text(path, "\n".join(lines) + "\n")

    compact_entries(geometry, "bones", BEDROCK / "models/entity/damage_number.geo.json")
    compact_entries({"format_version": "1.8.0",
                     "render_controllers": {"controller.render.astralrecord.damage_number": controller}},
                    "part_visibility", BEDROCK / "render_controllers/damage_number.render_controllers.json")


if __name__ == "__main__":
    glyphs = read_glyphs()
    write_json(JAVA / "font/damage_number.json", {
        "providers": [
            {"type": "space", "advances": {" ": 4}},
            {"type": "bitmap", "file": "astralrecord:font/damage_number.png",
             "height": JAVA_HEIGHT, "ascent": 8, "chars": [JAVA_GLYPHS]},
            {"type": "reference", "id": "minecraft:default"},
        ],
    })
    atlas(JAVA / "textures/font/damage_number.png", JAVA_GLYPHS, glyphs, JAVA_PALETTE,
          len(JAVA_GLYPHS) * CELL_WIDTH, CELL_HEIGHT)
    for style, palette in PALETTES.items():
        # Styles with an icon reuse the shield mark's cell and mesh.
        characters = BEDROCK_GLYPHS[:-1] + STYLE_ICONS.get(style, BEDROCK_GLYPHS[-1])
        atlas(BEDROCK / f"textures/entity/damage_number/{style}.png", characters, glyphs, palette,
              BEDROCK_WIDTH, BEDROCK_HEIGHT)
    model_and_controller()
