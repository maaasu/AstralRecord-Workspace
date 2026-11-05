"""Build the shared eight-sector texture and the JE/Bedrock models that use it."""

import json
import math
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "assets/astralrecord"
BEDROCK = ROOT / "bedrock"
SIZE = 128
COLUMNS = 4
SLOT_COUNT = 8
WIDTH, HEIGHT = SIZE * COLUMNS, SIZE * 2
# Keep the slot's icon at radius 42, and the outer detail within radius 61.
COLORS = {"normal": 0xA6E8FF, "selected": 0xFFCB70, "cooldown": 0x737C90}


def sector_pixel(x, y, angle):
    x, y = x + 0.5 - SIZE / 2, SIZE / 2 - y - 0.5
    across = x * math.cos(angle) - y * math.sin(angle)
    along = x * math.sin(angle) + y * math.cos(angle)
    distance = math.hypot(x, y)
    edge = along * math.sin(math.pi / 8) - abs(across) * math.cos(math.pi / 8)

    # A pixel-cut star on the outer edge and a smaller inset stud.
    diamond = abs(across) + abs(along - 56)
    if diamond <= 4.5:
        return (249, 255) if diamond <= 1.5 else (132, 235)
    if abs(across) + abs(along - 31) <= 1.6:
        return (181, 236)

    if edge < 0.9 or distance < 27 or distance > 55.3:
        return (0, 0)
    if distance > 54.2 or distance < 28.3 or edge < 1.9:
        return (75, 220)
    if distance > 52.5 or distance < 29.4 or edge < 2.8:
        return (225, 245)
    if distance > 51.2 or distance < 30.6 or edge < 3.8:
        return (122, 205)
    if distance > 48.5:
        return (46 + (x + y) % 2 * 6, 169)
    return (24 + int((55 - distance) / 5) * 3, 150)


def make_atlas():
    rows = [bytearray(WIDTH * 4) for _ in range(HEIGHT)]
    sectors = []
    for slot in range(SLOT_COUNT):
        tile_x = (slot % COLUMNS) * SIZE
        tile_y = (slot // COLUMNS) * SIZE
        angle = slot * math.tau / SLOT_COUNT
        points = []
        for y in range(SIZE):
            for x in range(SIZE):
                shade, alpha = sector_pixel(x, y, angle)
                if alpha:
                    points.append((x, y))
                    start = (tile_x + x) * 4
                    rows[tile_y + y][start:start + 4] = bytes((int(shade),) * 3 + (alpha,))
        left, top = (min(point[axis] for point in points) for axis in range(2))
        right, bottom = (max(point[axis] for point in points) + 1 for axis in range(2))
        sectors.append((left, top, right, bottom, tile_x + left, tile_y + top))

    return png(WIDTH, HEIGHT, rows), sectors


def png(width, height, rows):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(b"\0" + row for row in rows), 9))
            + chunk(b"IEND", b""))


def panel_textures():
    # Bake the neutral ring as one face for Bedrock's standard alpha-test material.
    rows = [bytearray(SIZE * 4) for _ in range(SIZE)]
    tint = [((COLORS["normal"] >> shift) & 255) for shift in (16, 8, 0)]
    for slot in range(SLOT_COUNT):
        angle = slot * math.tau / SLOT_COUNT
        for y in range(SIZE):
            for x in range(SIZE):
                shade, alpha = sector_pixel(x, y, angle)
                if alpha >= 200 or alpha and (x + y) % 2 == 0:
                    rows[y][x * 4:x * 4 + 4] = bytes([int(shade * color / 255) for color in tint] + [255])
    write(BEDROCK / "textures/entity/action_ring_rim.png", png(SIZE, SIZE, rows))

    decor = []
    for y in range(8):
        row = bytearray()
        for x in range(32):
            column = x % 8
            if x < 24:
                shades = ((18, 26, 46), (249, 184, 92), (135, 228, 241))
                color = shades[x // 8]
                rgb = tuple(max(0, component - 25) for component in color) if y >= 6 else color
                row.extend((*rgb, 255))
            else:
                radius = abs(column - 3.5) + abs(y - 3.5)
                row.extend((255, 238, 171, 255) if radius <= 2 else
                           (255, 183, 75, 255) if radius <= 4 else (0, 0, 0, 0))
        decor.append(row)
    overlay = png(32, 8, decor)
    write(JAVA / "textures/item/action_ring_panel.png", overlay)
    write(BEDROCK / "textures/entity/action_ring_panel.png", overlay)


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def write_json(path, data):
    write(path, (json.dumps(data, indent=2) + "\n").encode("utf-8"))


def java_models(sectors):
    elements = []
    for slot, (left, top, right, bottom, u, v) in enumerate(sectors):
        elements.append({
            "from": [left / 8, 16 - bottom / 8, 8],
            "to": [right / 8, 16 - top / 8, 8],
            "shade": False,
            "faces": {"south": {
                "texture": "#ring",
                "tintindex": slot,
                "uv": [u * 16 / WIDTH, v * 16 / HEIGHT,
                       (u + right - left) * 16 / WIDTH, (v + bottom - top) * 16 / HEIGHT],
            }},
        })
    write_json(JAVA / "models/item/action_ring.json", {
        "gui_light": "front", "textures": {"ring": "astralrecord:item/action_ring"}, "elements": elements,
    })
    write_json(JAVA / "items/action_ring.json", {"model": {
        "type": "minecraft:model", "model": "astralrecord:item/action_ring",
        "tints": [{"type": "minecraft:custom_model_data", "index": slot, "default": COLORS["normal"]}
                  for slot in range(SLOT_COUNT)],
    }})


def bedrock_models(sectors):
    bones = [
        {"name": "yaw", "pivot": [0, 0, 0]},
        {"name": "pitch", "parent": "yaw", "pivot": [0, 0, 0]},
        {"name": "wheel", "parent": "pitch", "pivot": [0, 0, 0]},
    ]
    for slot, (left, top, right, bottom, u, v) in enumerate(sectors):
        # The north face is seen with negative model X to screen right.
        bones.append({"name": f"slot_{slot}", "parent": "wheel", "pivot": [0, 0, 0], "cubes": [{
            "origin": [8 - right / 8, 8 - bottom / 8, 0],
            "size": [(right - left) / 8, (bottom - top) / 8, 0],
            "uv": {"north": {"uv": [u, v], "uv_size": [right - left, bottom - top]}},
        }]})
    write_json(BEDROCK / "models/entity/action_ring.geo.json", {
        "format_version": "1.12.0", "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.astralrecord.action_ring",
                "texture_width": WIDTH, "texture_height": HEIGHT,
                "visible_bounds_width": 5, "visible_bounds_height": 5, "visible_bounds_offset": [0, 0, 0],
            },
            "bones": bones,
        }],
    })
    def prop(name):
        return f"query.property('astralrecord:ring_{name}')"

    write_json(BEDROCK / "animations/action_ring.animation.json", {
        "format_version": "1.8.0", "animations": {"animation.astralrecord.action_ring": {
            "loop": True,
            "bones": {
                "yaw": {"rotation": [0, f"{prop('yaw')} - query.body_y_rotation", 0]},
                "pitch": {"rotation": [prop("pitch"), 0, 0]},
                "wheel": {"rotation": [0, 0, prop("roll")], "scale": [prop("size")] * 3},
            },
        }},
    })

    controllers = {}
    for style, rgb in COLORS.items():
        visibility = []
        for slot in range(SLOT_COUNT):
            selected = f"{prop('selected')} == {slot + 1}"
            cooldown = f"math.mod(math.floor({prop('cooldowns')} / {1 << slot}), 2) == 1"
            expression = {
                "normal": f"!({selected}) && !({cooldown})",
                "selected": selected,
                "cooldown": f"!({selected}) && ({cooldown})",
            }[style]
            visibility.append({f"slot_{slot}": expression})

        controllers[f"controller.render.astralrecord.action_ring.{style}"] = {
            "geometry": "Geometry.default", "materials": [{"*": "Material.default"}],
            "textures": ["Texture.default"], "ignore_lighting": True,
            "color": dict(zip("rgba", [((rgb >> shift) & 255) / 255 for shift in (16, 8, 0)] + [1])),
            "part_visibility": visibility,
        }
    write_json(BEDROCK / "render_controllers/action_ring.render_controllers.json", {
        "format_version": "1.8.0", "render_controllers": controllers,
    })
    write_json(BEDROCK / "entity/action_ring.entity.json", {
        "format_version": "1.10.0", "minecraft:client_entity": {"description": {
            "identifier": "astralrecord:action_ring",
            "materials": {"default": "entity_alphablend"},
            "textures": {"default": "textures/entity/action_ring"},
            "geometry": {"default": "geometry.astralrecord.action_ring"},
            "animations": {"pose": "animation.astralrecord.action_ring"},
            "scripts": {"animate": ["pose"]},
            "render_controllers": list(controllers),
        }},
    })


def main():
    atlas, sectors = make_atlas()
    write(JAVA / "textures/item/action_ring.png", atlas)
    write(BEDROCK / "textures/entity/action_ring.png", atlas)
    java_models(sectors)
    bedrock_models(sectors)
    panel_textures()


if __name__ == "__main__":
    main()
