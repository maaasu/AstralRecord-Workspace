"""Build matching six-frame skill sprites for Java bitmap text and Bedrock entity UVs."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
CELL = 32
GRID = 64
ATLAS = CELL * GRID
FRAMES = 6
PHASES = ("cast", "trail", "impact", "area")
GLYPH_START = 0xE400
MAX_SKILLS = 170
BEDROCK_GEOMETRY = "geometry.astralrecord.skill_effect"
BEDROCK_CONTROLLER = "controller.render.astralrecord.skill_effect"
PALETTES = {
    "amber": (250, 173, 66), "astral": (87, 156, 255), "crimson": (255, 78, 109),
    "flame": (255, 117, 51), "frost": (144, 225, 255), "ghost": (141, 219, 224),
    "indigo": (115, 121, 255), "jade": (96, 221, 127), "mint": (131, 252, 214),
    "orchid": (199, 133, 255), "pearl": (235, 244, 255), "plum": (201, 93, 221),
    "rose": (253, 156, 199), "rust": (233, 116, 75), "silver": (204, 213, 238),
    "sky": (108, 215, 255), "smoke": (153, 166, 199), "solar": (255, 224, 115),
    "steel": (115, 164, 205), "violet": (167, 109, 247), "volt": (218, 246, 100),
}
MOTIFS = {
    "veil", "shield", "slash", "arrow", "bolt", "nova", "breath", "fracture",
    "wing", "weave", "lance", "chain", "sigil", "vortex", "bloom", "meteor",
    "orbit", "clock", "rain", "target", "portal", "orb", "cross", "fortress",
    "eclipse", "prism",
}


def rgba(rgb, alpha):
    return (*rgb, alpha)


def mix(a, b, fraction):
    return tuple(round(x + (y - x) * fraction) for x, y in zip(a, b, strict=True))


def polar(radius, angle, y_scale=1):
    angle = math.radians(angle)
    return radius * math.cos(angle), radius * math.sin(angle) * y_scale


def arc(radius, start, end, y_scale=1):
    return [polar(radius, start + (end - start) * i / 24, y_scale) for i in range(25)]


class Sprite:
    """Draw translucent pixel paths with a common pivot and straight-alpha output."""

    def __init__(self, profile, phase, frame):
        self.data = bytearray(CELL * CELL * 4)
        self.motif = profile["motif"]
        self.primary = PALETTES[profile["primary"]]
        self.accent = PALETTES[profile["accent"]]
        self.light = mix(self.primary, (255, 255, 255), 0.73)
        self.shadow = mix(self.primary, (31, 24, 67), 0.58)
        self.frame = frame
        self.opacity = (0.68, 0.96, 1, 0.92, 0.63, 0.32)[frame]
        self.signature = int.from_bytes(hashlib.sha256(profile["id"].encode()).digest()[:2], "big")
        self.angle = (self.signature % 17) - 8
        self.zoom = {
            "cast": (0.57, 0.79, 1, 0.94, 0.81, 0.69),
            "trail": (0.9, 0.99, 1, 0.88, 0.75, 0.6),
            "impact": (0.48, 0.76, 1, 1.12, 1.18, 1.2),
            "area": (0.55, 0.72, 0.91, 1.04, 1.11, 1.16),
        }[phase][frame]

    def dot(self, x, y, color):
        x, y = round(15.5 + x), round(15.5 + y)
        if not (1 <= x < CELL - 1 and 1 <= y < CELL - 1):
            return
        offset = (y * CELL + x) * 4
        red, green, blue, alpha = color
        alpha = round(alpha * self.opacity)
        under = self.data[offset + 3] * (255 - alpha) // 255
        total = alpha + under
        if total:
            for index, channel in enumerate((red, green, blue)):
                self.data[offset + index] = (channel * alpha + self.data[offset + index] * under) // total
            self.data[offset + 3] = total

    def line(self, a, b, color, weight=1):
        start_x, start_y = a
        end_x, end_y = b
        steps = max(1, math.ceil(max(abs(end_x - start_x), abs(end_y - start_y)) * 1.3))
        previous = None
        for i in range(steps + 1):
            x = round(start_x + (end_x - start_x) * i / steps + 0.5) - 0.5
            y = round(start_y + (end_y - start_y) * i / steps + 0.5) - 0.5
            if previous == (x, y):
                continue
            previous = x, y
            self.dot(x, y, color)
            if weight > 1:
                for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    self.dot(x + dx, y + dy, color)

    def path(self, points, color, weight=1):
        for a, b in zip(points, points[1:]):
            self.line(a, b, color, weight)

    def transform(self, points, angle=0):
        cosine, sine = math.cos(math.radians(angle)), math.sin(math.radians(angle))
        return [
            ((x * cosine - y * sine) * self.zoom, (x * sine + y * cosine) * self.zoom)
            for x, y in points
        ]

    def lit(self, points, angle=0, strong=True):
        points = self.transform(points, angle)
        self.path(points, rgba(self.shadow, 125), 2)
        self.path(points, rgba(self.primary if strong else self.accent, 185), 2)
        self.path(points, rgba(self.light if strong else self.accent, 235))

    def star(self, x, y, radius, color):
        self.line((x - radius, y), (x + radius, y), color)
        self.line((x, y - radius), (x, y + radius), color)

    def sparks(self, count, radius, spread, rotation=0):
        for index in range(count):
            turn = (self.signature % 80) + index * 360 / count + rotation
            distance = radius + ((self.signature >> (index % 12)) & 3) * spread
            x, y = polar(distance, turn)
            self.dot(x, y, rgba(self.accent, 191))
            if (index + self.frame) % 3 == 0:
                self.star(x, y, 1, rgba(self.light, 172))

    def mark(self, angle=0):
        motif = self.motif
        if motif == "slash":
            self.lit(arc(11, 205, 350, 0.65), angle - 22 + self.frame * 7)
            self.lit(arc(7.5, 222, 317, 0.7), angle - 22 + self.frame * 7, False)
        elif motif in ("arrow", "lance"):
            self.lit([(-11, 2), (11, -2)], angle)
            if motif == "arrow":
                self.lit([(5, -6), (11, -2), (6, 3)], angle)
                self.lit([(-10, -2), (-6, 1), (-9, 6)], angle, False)
            else:
                self.lit([(-2, -2), (10, -2), (3, 2), (-6, 3)], angle)
                self.lit([(-8, 5), (2, 4)], angle, False)
        elif motif in ("shield", "fortress"):
            if motif == "shield":
                self.lit([(-8, -7), (0, -10), (8, -7), (7, 2), (0, 10), (-7, 2), (-8, -7)])
            else:
                self.lit([(-10, 8), (-10, -7), (-6, -7), (-6, -3), (-2, -3), (-2, -8),
                          (2, -8), (2, -3), (6, -3), (6, -7), (10, -7), (10, 8), (-10, 8)])
            self.lit([(0, -5), (4, 0), (0, 6), (-4, 0), (0, -5)], strong=False)
        elif motif in ("bolt", "fracture"):
            self.lit([(-11, 5), (-5, 0), (-1, 3), (2, -4), (6, -2), (11, -8)], angle)
            self.lit([(-2, 2), (0, 7), (5, 9)], angle, False)
            if motif == "fracture":
                self.lit([(0, 0), (-4, -4), (-9, -5)], angle, False)
                self.lit([(4, -4), (6, -9)], angle, False)
        elif motif in ("wing", "veil", "breath"):
            length = 11 if motif == "wing" else 8
            for side in (-1, 1):
                self.lit([(0, 4), (side * 3, 0), (side * 7, -2), (side * length, -8),
                          (side * (length - 2), 0), (side * 4, 6), (0, 4)], angle)
                self.lit([(side * 3, 3), (side * (length - 2), -2)], angle, False)
            if motif == "breath":
                self.lit(arc(11, 27, 153, 0.85), angle, False)
        elif motif in ("prism", "sigil", "clock", "target"):
            polygon = {"prism": 3, "sigil": 6, "clock": 12, "target": 4}[motif]
            points = [polar(9, -90 + 360 * index / polygon) for index in range(polygon + 1)]
            self.lit(points, angle)
            if motif == "prism":
                for index in (0, 1, 2):
                    self.lit([points[index], (0, 2)], angle, False)
            elif motif == "clock":
                self.lit([(0, -5), (0, 0), (5, 3)], angle + self.frame * 10, False)
            elif motif == "target":
                self.lit([(-4, 0), (4, 0)], angle, False)
                self.lit([(0, -4), (0, 4)], angle, False)
            else:
                self.lit([(0, -6), (5, 3), (-5, 3), (0, -6)], angle, False)
        elif motif in ("nova", "orb", "eclipse"):
            self.lit(arc(8, 0, 360), angle, motif != "eclipse")
            if motif == "eclipse":
                self.lit(arc(7, 165, 322, 0.77), angle)
            elif motif == "orb":
                self.lit(arc(10, 180, 355, 0.36), angle - 26, False)
                self.star(0, 0, 2, rgba(self.light, 235))
            else:
                for index in range(8):
                    heading = angle + index * 45
                    self.lit([polar(4, heading), polar(11, heading)], strong=index % 2 == 0)
        elif motif in ("vortex", "portal", "orbit", "weave"):
            tilt = angle - 22 + self.frame * (13 if motif == "vortex" else 5)
            if motif == "vortex":
                self.lit([(r * math.cos(i / 2.7), r * math.sin(i / 2.7))
                          for i in range(23) for r in [1 + 0.42 * i]], tilt)
            else:
                self.lit(arc(11, 0, 360, 0.46), tilt)
                if motif != "portal":
                    self.lit(arc(10, 0, 360, 0.46), -tilt, False)
                else:
                    self.lit(arc(8, 10, 315, 0.6), tilt + 71, False)
                if motif == "weave":
                    self.lit([(-5, 1), (0, -4), (5, 1), (0, 5), (-5, 1)], tilt, False)
        elif motif == "chain":
            for index, (x, y) in enumerate(((-7, 4), (0, 0), (7, -4))):
                points = [(dx + x, dy + y) for dx, dy in arc(4, 0, 360, 0.55)]
                self.lit(points, angle, index != 1)
        elif motif == "bloom":
            for index in range(5):
                self.lit([(0, 0), (4, 4), (9, 0), (4, -4), (0, 0)], index * 72 + angle,
                         index % 2 == 0)
            self.star(0, 0, 2, rgba(self.light, 232))
        elif motif in ("meteor", "rain"):
            for index, x in enumerate((-6, 0, 6) if motif == "rain" else (-3, 2, 6)):
                self.lit([(x + 4, -11 + index), (x - 1, 4 + index), (x + 2, 2 + index)],
                         angle, index == 1)
            if motif == "meteor":
                self.lit(arc(5.5, 0, 360, 0.7), angle)
        else:
            self.lit([(0, -9), (0, 9)], angle)
            self.lit([(-6, -2), (6, -2)], angle)
            self.lit(arc(9, 15, 165, 0.68), angle, False)

    def render(self, phase):
        frame = self.frame
        if phase == "cast":
            spin = self.angle + frame * 8
            self.lit(arc(13, 35, 137, 0.93), spin, False)
            self.lit(arc(13, 212, 311, 0.93), spin, False)
            self.mark(self.angle)
            self.sparks(5, 9 + frame * 0.6, 0.7, spin)
        elif phase == "trail":
            spin = self.angle - 10
            self.mark(spin)
            self.lit([(-12, 4), (-8, 5), (-4, 4)], spin, False)
            self.lit([(-12, -1), (-8, 0)], spin, False)
            self.sparks(3, 8 + frame * 0.65, 0.4, spin)
        elif phase == "impact":
            spin = self.angle + frame * 11
            for index in range(5 + self.signature % 3):
                heading = spin + index * 360 / (5 + self.signature % 3)
                self.lit([polar(9, heading), polar(12, heading + (index % 3 - 1) * 5)],
                         strong=index % 2 == 0)
            self.mark(self.angle)
            self.sparks(6, 10 + frame * 0.6, 0.6, spin)
        else:
            spin = self.angle + frame * 6
            self.lit(arc(12, 0, 360), spin, False)
            for index in range(4):
                angle = spin + index * 90
                self.lit([polar(10, angle - 15), polar(13, angle), polar(10, angle + 15)])
            self.zoom *= 0.66
            self.mark(self.angle)
            self.sparks(5, 9 + frame * 0.55, 0.6, spin)
        return self.data


def png(width, height, pixels):
    def chunk(kind, value):
        return (struct.pack(">I", len(value)) + kind + value
                + struct.pack(">I", zlib.crc32(kind + value)))

    stride = width * 4
    rows = (b"\0" + pixels[start:start + stride] for start in range(0, len(pixels), stride))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), level=9))
            + chunk(b"IEND", b""))


def load_profiles():
    profiles = json.loads((ROOT / "skill-effects.json").read_text(encoding="utf-8"))
    ids = [profile["id"] for profile in profiles]
    if not 0 < len(ids) <= MAX_SKILLS or len(set(ids)) != len(ids):
        raise ValueError("Skill slots must be unique and fit the allocated atlas.")
    art_profiles = {(p["motif"], p["primary"], p["accent"]) for p in profiles}
    if len(art_profiles) != len(profiles):
        raise ValueError("Every skill must have a distinct combination of motif and colors.")
    for profile in profiles:
        if not re.fullmatch(r"[a-z0-9_]+", profile["id"]) or profile["motif"] not in MOTIFS:
            raise ValueError(f"Invalid skill profile: {profile}")
        if profile["primary"] not in PALETTES or profile["accent"] not in PALETTES:
            raise ValueError(f"Invalid skill palette: {profile}")
    filebase = ROOT.parent / "40_filebase" / "30.features.skill"
    expected = {
        re.search(r"^id: ([a-z0-9_]+)$", path.read_text(encoding="utf-8"), re.MULTILINE)[1]
        for path in filebase.glob("*.yml")
    }
    if expected != set(ids):
        raise ValueError(f"Skill catalog mismatch: missing={sorted(expected - set(ids))}, extra={sorted(set(ids) - expected)}")
    slots = ROOT / "skill-effect-slots.txt"
    if slots.exists():
        published = slots.read_text(encoding="utf-8").splitlines()
        if ids[:len(published)] != published:
            raise ValueError("Published skill slot numbers must retain their original order.")
    return profiles


def copy_cell(target, width, x, y, cell):
    for line in range(CELL):
        offset = ((y * CELL + line) * width + x * CELL) * 4
        target[offset:offset + CELL * 4] = cell[line * CELL * 4:(line + 1) * CELL * 4]


def bedrock_assets(styles):
    geometries = []
    aliases = {}
    for style in range(styles):
        alias = f"style_{style}"
        identifier = f"{BEDROCK_GEOMETRY}.{alias}"
        aliases[alias] = identifier
        bones = [
            {"name": "billboard", "pivot": [0, 0, 0]},
            {"name": "sprite", "parent": "billboard", "pivot": [0, 0, 0]},
        ]
        for frame in range(FRAMES):
            row, column = divmod(style * FRAMES + frame, GRID)
            bones.append({
                "name": f"frame_{frame}", "parent": "sprite", "pivot": [0, 0, 0],
                "cubes": [{"origin": [-8, -8, -0.125], "size": [16, 16, 0.125],
                           "uv": {"south": {"uv": [column * CELL, row * CELL], "uv_size": [CELL, CELL]}}}],
            })
        geometries.append({
            "description": {"identifier": identifier, "texture_width": ATLAS, "texture_height": ATLAS,
                            "visible_bounds_width": 16, "visible_bounds_height": 16,
                            "visible_bounds_offset": [0, 0, 0]},
            "bones": bones,
        })
    entity = {"format_version": "1.10.0", "minecraft:client_entity": {"description": {
        "identifier": "astralrecord:skill_effect",
        "materials": {"default": "entity_emissive_alpha"},
        "textures": {"default": "textures/entity/skill_effect"},
        "geometry": aliases,
        "animations": {"motion": "animation.astralrecord.skill_effect.motion"},
        "scripts": {
            "initialize": ["variable.age = 0.0;"],
            "pre_animation": [
                "variable.style = query.property('astralrecord:style');",
                "variable.sprite_scale = query.property('astralrecord:size') / 16.0;",
                f"variable.frame = math.clamp(math.floor(variable.age * 20.0), 0, {FRAMES - 1});",
                "variable.age = variable.age + query.delta_time;",
            ],
            "animate": ["motion"],
        },
        "render_controllers": [{BEDROCK_CONTROLLER: f"variable.style >= 0 && variable.style < {styles}"}],
    }}}
    controller = {"format_version": "1.8.0", "render_controllers": {BEDROCK_CONTROLLER: {
        "arrays": {"geometries": {"Array.styles": [f"Geometry.{alias}" for alias in aliases]}},
        "geometry": "Array.styles[variable.style]",
        "materials": [{"*": "Material.default"}],
        "textures": ["Texture.default"],
        "part_visibility": [{f"frame_{frame}": f"variable.frame == {frame}"} for frame in range(FRAMES)],
        "ignore_lighting": True,
    }}}
    # Keep each generated geometry on one line so adding a skill appends four lines.
    geometry_lines = ("    " + json.dumps(geometry, separators=(",", ":")) for geometry in geometries)
    geometry = '{\n  "format_version": "1.12.0",\n  "minecraft:geometry": [\n' + ",\n".join(geometry_lines) + "\n  ]\n}\n"
    return {
        "bedrock/entity/skill_effect.entity.json": (json.dumps(entity, indent=2) + "\n").encode(),
        "bedrock/models/entity/skill_effect_v2.geo.json": geometry.encode(),
        "bedrock/render_controllers/skill_effect.render_controllers.json": (json.dumps(controller, indent=2) + "\n").encode(),
    }


def generate(profiles):
    bedrock = bytearray(ATLAS * ATLAS * 4)
    frame_hashes = set()
    for slot, profile in enumerate(profiles):
        for phase_index, phase in enumerate(PHASES):
            for frame in range(FRAMES):
                index = (slot * len(PHASES) + phase_index) * FRAMES + frame
                pixels = Sprite(profile, phase, frame).render(phase)
                visible = sum(alpha >= 32 for alpha in pixels[3::4])
                if not 12 <= visible <= CELL * CELL * 0.64:
                    raise ValueError(f"Poor silhouette coverage for {profile['id']} {phase} frame {frame}: {visible}")
                frame_hash = hashlib.sha256(pixels).digest()
                if frame_hash in frame_hashes:
                    raise ValueError(f"Duplicate frame for {profile['id']} {phase} frame {frame}")
                frame_hashes.add(frame_hash)
                copy_cell(bedrock, ATLAS, index % GRID, index // GRID, pixels)
    java = bedrock.copy()
    for index in range(len(profiles) * len(PHASES) * FRAMES):
        offset = ((index // GRID * CELL) * ATLAS + index % GRID * CELL + CELL - 1) * 4
        java[offset + 3] = 1
    font = {"providers": [{"type": "bitmap", "file": "astralrecord:font/skill_effect.png",
                           "height": 16, "ascent": 14,
                           "chars": ["".join(chr(GLYPH_START + row * GRID + column)
                                             for column in range(GRID)) for row in range(GRID)]}]}
    return {
        "skill-effect-slots.txt": "".join(profile["id"] + "\n" for profile in profiles).encode(),
        "assets/astralrecord/font/skill_effect.json": (json.dumps(font, indent=2) + "\n").encode(),
        "assets/astralrecord/textures/font/skill_effect.png": png(ATLAS, ATLAS, java),
        "bedrock/textures/entity/skill_effect.png": png(ATLAS, ATLAS, bedrock),
        **bedrock_assets(len(profiles) * len(PHASES)),
    }


def composite(pixels, background):
    for offset in range(0, len(pixels), 4):
        alpha = pixels[offset + 3] / 255
        pixels[offset:offset + 4] = bytes((*mix(background, pixels[offset:offset + 3], alpha), 255))
    return pixels


def preview(profiles, selected):
    """Show every skill at its peak, or all six frames of each phase for one skill."""
    if selected:
        width = FRAMES * CELL
        height = len(PHASES) * CELL
        image = bytearray(width * height * 4)
        for row, phase in enumerate(PHASES):
            for frame in range(FRAMES):
                pixels = Sprite(selected, phase, frame).render(phase)
                copy_cell(image, width, frame, row, composite(pixels, (38, 49, 61)))
    else:
        slots_per_row = 5
        columns = slots_per_row * len(PHASES)
        width = columns * CELL
        height = math.ceil(len(profiles) / slots_per_row) * CELL
        image = bytearray(width * height * 4)
        for slot, profile in enumerate(profiles):
            row, column = divmod(slot, slots_per_row)
            background = (29, 39, 47) if row % 2 == 0 else (91, 96, 103)
            for index, phase in enumerate(PHASES):
                pixels = Sprite(profile, phase, 2).render(phase)
                x = column * len(PHASES) + index
                copy_cell(image, width, x, row, composite(pixels, background))
    return png(width, height, image)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check all generated files without rewriting them.")
    parser.add_argument("--preview", type=Path, help="Write a PNG contact sheet showing each slot's four phases.")
    parser.add_argument("--preview-skill", help="Show all frames of this skill in the preview.")
    args = parser.parse_args()
    profiles = load_profiles()
    selected = next((profile for profile in profiles if profile["id"] == args.preview_skill), None)
    if args.preview_skill and (not args.preview or selected is None):
        parser.error("--preview-skill requires --preview and an existing skill ID")
    for relative, content in generate(profiles).items():
        path = ROOT / relative
        if args.check:
            current = path.read_bytes()
            if path.suffix in (".json", ".txt"):
                current = current.replace(b"\r\n", b"\n")
            if current != content:
                raise SystemExit(f"Regenerate stale skill effect asset: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if args.preview:
        args.preview.write_bytes(preview(profiles, selected))
    print(f"Verified {len(profiles)} skill effects and {len(profiles) * len(PHASES) * FRAMES} sprite frames.")


if __name__ == "__main__":
    main()
