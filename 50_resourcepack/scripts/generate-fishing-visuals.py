"""Generate the shared fishing geometry, palette, and client selectors."""

import argparse
import json
import math
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[1]
JAVA = "assets/astralrecord"
BEDROCK = "bedrock"
NAMESPACE = "astralrecord"
SEGMENTS = 10
FACES = ("north", "south", "east", "west", "up", "down")
PALETTE = {
    "line": "24354a",
    "thread": "536b7e",
    "wine": "77233d",
    "red": "bf3045",
    "coral": "ed5257",
    "rose": "ff9885",
    "cream": "fff2ce",
    "ivory": "dfbf9b",
    "brass": "c4925a",
    "deep": "246687",
    "aqua": "40a8bf",
    "teal": "76d7df",
    "foam": "c8faf3",
}


def cube(center, size, side, top=None, yaw=0):
    return center, size, side, top or side, yaw


LINE = cube((0, 0, 0), (16, 16, 16), "line", "thread")
HOOK = [
    cube((0, -1.15, 0), (1.35, 0.55, 1.35), "wine", "red"),
    cube((0, -0.46, 0), (2.85, 0.85, 2.85), "wine", "red"),
    cube((0, 0.23, 0), (3.3, 0.53, 3.3), "ivory", "cream"),
    cube((0, 0.91, 0), (2.85, 0.83, 2.85), "red", "coral"),
    cube((0, 1.54, 0), (2.0, 0.43, 2.0), "coral", "rose"),
    cube((0, 1.97, 0), (0.7, 0.43, 0.7), "brass", "cream"),
    cube((0, 0.98, 1.44), (0.75, 0.36, 0.06), "rose"),
    cube((0, 0.98, -1.44), (0.75, 0.36, 0.06), "rose"),
]


def ring_stroke(radius, step, length, height, thickness, y, side, top):
    angle = math.radians(step * 45)
    yaw = (step * 45 + 90) % 180 - 90
    return cube((radius * math.sin(angle), y, radius * math.cos(angle)),
                (length, height, thickness), side, top, yaw)


SPLASH_RIPPLE = [
    ring_stroke(5.85, step, 4.55 if step % 2 == 0 else 4.25, 0.21, 0.54, 0.12,
                "aqua", "foam" if step % 2 == 0 else "teal")
    for step in range(8)
]
SPLASH_CROWN = [
    cube((1.7, 1.12, 0.45), (0.76, 2.24, 0.66), "aqua", "foam"),
    cube((1.7, 2.41, 0.45), (0.5, 0.38, 0.48), "teal", "foam"),
    cube((-1.1, 0.91, 1.5), (0.63, 1.82, 0.75), "teal", "foam"),
    cube((-0.78, 0.72, -1.75), (0.74, 1.44, 0.66), "aqua", "teal"),
    cube((-2.52, 1.46, -1.42), (0.45, 0.64, 0.43), "teal", "foam"),
    cube((1.55, 1.09, -2.5), (0.43, 0.54, 0.46), "teal", "foam"),
    cube((0, 0.31, 0), (2.8, 0.48, 2.8), "aqua", "teal"),
]
WAKE = [
    ring_stroke(4.0, step, 2.75, 0.14, 0.42, 0.08, "deep", "teal")
    for step in (0, 1, 2, 4, 5, 6)
] + [
    ring_stroke(2.25, step, 1.65, 0.16, 0.4, 0.1, "aqua", "foam")
    for step in (0, 1, 4, 5)
]


def swatch(color):
    index = list(PALETTE).index(color)
    return index % 4 * 4, index // 4 * 4


def palette_png():
    def chunk(name, data):
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))

    colors = [bytes.fromhex(color) for color in PALETTE.values()]
    colors += [colors[0]] * (16 - len(colors))
    scanlines = b"".join(b"\0" + b"".join(colors[y // 4 * 4 + x // 4] for x in range(16))
                         for y in range(16))
    header = struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
            chunk(b"IDAT", zlib.compress(scanlines, level=9)) + chunk(b"IEND", b""))


def vector(values):
    return [round(value, 5) for value in values]


def java_model(cubes):
    elements = []
    for center, size, side, top, yaw in cubes:
        center = [axis + 8 for axis in center]
        # Java elements need their box dimensions swapped for a quarter turn.
        if yaw == -90:
            size = (size[2], size[1], size[0])
            yaw = 0
        element = {
            "from": vector(axis - width / 2 for axis, width in zip(center, size)),
            "to": vector(axis + width / 2 for axis, width in zip(center, size)),
            "shade": False,
        }
        if yaw:
            element["rotation"] = {"origin": vector(center), "axis": "y", "angle": yaw}
        faces = {}
        for face in FACES:
            u, v = swatch(top if face == "up" else side)
            faces[face] = {"uv": [u + 1, v + 1, u + 3, v + 3], "texture": "#palette"}
        element["faces"] = faces
        elements.append(element)
    return {"ambientocclusion": False, "textures": {"palette": "astralrecord:item/fishing_visual"},
            "elements": elements}


def java_selector(fallback, styles):
    def model(name):
        return {"type": "minecraft:model", "model": name}

    return {"model": {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                      "cases": [{"when": "fishing_" + style, "model": model("astralrecord:item/fishing_" + style)}
                                for style in styles],
                      "fallback": model(fallback)}}


def bedrock_cube(cuboid):
    center, size, side, top, yaw = cuboid
    # In the yaw-zero entity pose the geometry's negative Z is world positive Z.
    center = [center[0], center[1], -center[2]]
    faces = {}
    for face in FACES:
        u, v = swatch(top if face == "up" else side)
        faces[face] = {"uv": [u + 1, v + 1], "uv_size": [2, 2]}
    result = {"origin": vector(axis - width / 2 for axis, width in zip(center, size)),
              "size": list(size), "uv": faces}
    if yaw:
        result.update({"pivot": vector(center), "rotation": [0, -yaw, 0]})
    return result


def bone(name, parent=None, cubes=()):
    result = {"name": name, "pivot": [0, 0, 0]}
    if parent:
        result["parent"] = parent
    if cubes:
        result["cubes"] = [bedrock_cube(cuboid) for cuboid in cubes]
    return result


def rope_geometry_and_motion():
    # The position, rotation, and length each get their own pivot so that
    # length changes stretch along the rope and never scale world offsets.
    rope = cube((0, 0, 0), (0.4, 0.4, 16), "line", "thread")
    bones = [bone("bobber", cubes=HOOK)]
    motion = {"bobber": {"position": ["16 * v.dx", "16 * v.dy", "-16 * v.dz"]}}
    for index in range(SEGMENTS):
        start, end = index / SEGMENTS, (index + 1) / SEGMENTS
        sag0, sag1 = (4 * t * (1 - t) for t in (start, end))
        midpoint = (start + end) / 2
        average_sag = (sag0 + sag1) / 2
        segment = f"span_{index}"
        heading, length = f"{segment}_heading", f"{segment}_length"
        vertical_delta = f"v.dy / {SEGMENTS} + ({sag0 - sag1:.4f}) * v.sag"
        bones.extend([bone(segment), bone(heading, segment), bone(length, heading, [rope])])
        motion[segment] = {"position": [f"{16 * midpoint:.2f} * v.dx",
                                         f"{16 * midpoint:.2f} * v.dy - {16 * average_sag:.3f} * v.sag",
                                         f"-{16 * midpoint:.2f} * v.dz"]}
        motion[heading] = {"rotation": [f"-math.atan2({vertical_delta}, v.horizontal_span)", "v.yaw", 0]}
        motion[length] = {"scale": [1, 1, f"math.sqrt(v.horizontal_span * v.horizontal_span + "
                                          f"math.pow({vertical_delta}, 2)) + 0.012"]}
    return bones, motion


def geometry(name, bones, width, height):
    return {"description": {"identifier": "geometry.astralrecord.fishing_" + name,
                            "texture_width": 16, "texture_height": 16,
                            "visible_bounds_width": width, "visible_bounds_height": height}, "bones": bones}


def bedrock_files():
    bones, rope_motion = rope_geometry_and_motion()
    geometries = {
        "format_version": "1.12.0",
        "minecraft:geometry": [
            geometry("rig", bones, 256, 256),
            geometry("splash", [bone("ripple", cubes=SPLASH_RIPPLE), bone("crown", cubes=SPLASH_CROWN)], 2, 2),
            geometry("wake", [bone("wake", cubes=WAKE)], 2, 2),
        ],
    }
    animation_id = "animation.astralrecord.fishing_visual"
    splash_size = "math.lerp(0.45, 1, v.splash_age)"
    crown_size = "math.lerp(0.75, 1, v.splash_age)"
    wake_size = "math.lerp(0.55, 1, v.wake_age)"
    animations = {"format_version": "1.8.0", "animations": {
        animation_id + ".rig": {"loop": True, "bones": rope_motion},
        animation_id + ".splash": {"loop": True, "bones": {
            "ripple": {"scale": [splash_size, 1, splash_size]},
            "crown": {"scale": [crown_size, "0.25 + 0.85 * math.sin(180 * v.splash_age)", crown_size]},
        }},
        animation_id + ".wake": {"loop": True, "bones": {"wake": {"scale": [wake_size, 1, wake_size]}}},
    }}
    controller = "controller.render.astralrecord.fishing_visual"
    style_names = ("rig", "splash", "wake")
    entity = {"format_version": "1.10.0", "minecraft:client_entity": {"description": {
        "identifier": NAMESPACE + ":fishing_visual",
        "materials": {"default": "entity"},
        "textures": {"default": "textures/entity/fishing_visual"},
        "geometry": {style: "geometry.astralrecord.fishing_" + style for style in style_names},
        "animations": {style: animation_id + "." + style for style in style_names},
        "scripts": {
            "pre_animation": [
                "v.style = q.property('astralrecord:style');",
                "v.dx = q.property('astralrecord:dx');",
                "v.dy = q.property('astralrecord:dy');",
                "v.dz = q.property('astralrecord:dz');",
                "v.sag = q.property('astralrecord:sag');",
                f"v.horizontal_span = math.sqrt(v.dx * v.dx + v.dz * v.dz) / {SEGMENTS};",
                "v.yaw = -math.atan2(v.dx, v.dz);",
                "v.splash_age = math.clamp(q.life_time / 0.5, 0, 1);",
                "v.wake_age = math.clamp(q.life_time / 0.3, 0, 1);",
            ],
            "animate": [{style: f"v.style == {index}"} for index, style in enumerate(style_names)],
        },
        "render_controllers": [controller],
    }}}
    render_controllers = {"format_version": "1.8.0", "render_controllers": {controller: {
        "arrays": {"geometries": {"Array.styles": ["Geometry." + style for style in style_names]}},
        "geometry": "Array.styles[math.clamp(q.property('astralrecord:style'), 0, 2)]",
        "materials": [{"*": "Material.default"}], "textures": ["Texture.default"],
    }}}
    return {
        f"{BEDROCK}/entity/fishing_visual.entity.json": entity,
        f"{BEDROCK}/models/entity/fishing_visual.geo.json": geometries,
        f"{BEDROCK}/animations/fishing_visual.animation.json": animations,
        f"{BEDROCK}/render_controllers/fishing_visual.render_controllers.json": render_controllers,
    }


def outputs():
    for style, cubes in {"line": [LINE], "hook": HOOK, "splash": SPLASH_RIPPLE + SPLASH_CROWN,
                         "wake": WAKE}.items():
        yield f"{JAVA}/models/item/fishing_{style}.json", java_model(cubes)
    for carrier, fallback, styles in [
        ("black_concrete", "minecraft:block/black_concrete", ["line"]),
        ("tripwire_hook", "minecraft:item/tripwire_hook", ["hook"]),
        ("light_blue_carpet", "minecraft:block/light_blue_carpet", ["splash", "wake"]),
    ]:
        yield f"assets/minecraft/items/{carrier}.json", java_selector(fallback, styles)
    yield from bedrock_files().items()
    palette = palette_png()
    yield f"{JAVA}/textures/item/fishing_visual.png", palette
    yield f"{BEDROCK}/textures/entity/fishing_visual.png", palette


def json_text(value, depth=0):
    """Keep vector and face data readable without spreading each coordinate over a line."""
    indent = "  " * depth
    if isinstance(value, dict):
        inline = json.dumps(value, ensure_ascii=False)
        if len(inline) < 75 and not any(isinstance(part, dict) for part in value.values()):
            return inline
        lines = [f'{indent}  {json.dumps(key)}: {json_text(part, depth + 1)}' for key, part in value.items()]
        return "{\n" + ",\n".join(lines) + "\n" + indent + "}"
    if isinstance(value, list) and any(isinstance(part, (dict, list)) for part in value):
        lines = [f"{indent}  {json_text(part, depth + 1)}" for part in value]
        return "[\n" + ",\n".join(lines) + "\n" + indent + "]"
    return json.dumps(value, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="check generated assets without updating them")
    args = parser.parse_args()
    stale = []
    for name, value in outputs():
        path = ROOT / name
        data = value if isinstance(value, bytes) else (json_text(value) + "\n").encode("utf-8")
        if args.check:
            current = path.read_bytes() if path.is_file() else None
            if current is not None and not isinstance(value, bytes):
                current = current.replace(b"\r\n", b"\n")
            if current != data:
                stale.append(name)
        else:
            path.write_bytes(data)
    if stale:
        parser.exit(1, "Outdated fishing assets:\n" + "\n".join(stale) + "\n")


if __name__ == "__main__":
    main()
