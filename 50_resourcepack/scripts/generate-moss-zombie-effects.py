"""Generate the moss zombie skill effects for both editions.

Every style is symmetric along its length axis, so the Java ItemDisplay and the Bedrock custom entity
show the same shape whichever way the model's forward axis is mapped. Directional telegraphs (the slam fan)
are composed on the server from several vine segments and spike clusters.

Model units: 16 = 1 block at scale 1. X/Z are centred on the effect origin, Y starts at the floor.
The server scales each display by (width, height, length) in blocks.

  python scripts/generate-moss-zombie-effects.py                  # write assets
  python scripts/generate-moss-zombie-effects.py --check          # verify shipped assets
  python scripts/generate-moss-zombie-effects.py --check --preview out.png
"""

import argparse
import json
import math
import random
from dataclasses import dataclass, field, replace
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAME = 'midgard_moss_zombie'
# Shared with MossZombieMotion.EffectStyle and the Geyser extension (astralrecord:style).
STYLES = ('warning_ring', 'warning_spore', 'warning_vine', 'impact_spikes', 'impact_spores')
FRAMES = 4
ATLAS = 128
FACES = ('north', 'south', 'west', 'east', 'up', 'down')
PALETTE = {
    'moss_dark': ((38, 64, 30), (46, 76, 34), (54, 86, 40)),
    'moss': ((64, 102, 40), (74, 116, 46), (86, 128, 52)),
    'moss_light': ((112, 156, 64), (128, 172, 74), (146, 186, 88)),
    'spore': ((182, 202, 84), (198, 216, 98), (212, 228, 116)),
    'spore_light': ((226, 236, 150), (238, 244, 176), (248, 250, 204)),
    'stone': ((110, 112, 106), (124, 126, 120), (138, 140, 132)),
    'stone_dark': ((74, 76, 72), (86, 88, 84), (98, 100, 94)),
    'root': ((82, 58, 36), (96, 68, 42), (110, 80, 50)),
    'warn': ((214, 146, 40), (230, 164, 52), (244, 186, 74)),
}
SWATCH_ROW = 120
SWATCHES = {name: (index * 8, SWATCH_ROW) for index, name in enumerate(PALETTE)}
DECALS = {'ring': (0, 0, 64), 'spore': (64, 0, 64)}
LIGHT = 12


# ---------------------------------------------------------------- texture

def texture():
    from PIL import Image, ImageDraw

    rng = random.Random(0x4D4F5353)
    atlas = Image.new('RGBA', (ATLAS, ATLAS), (0, 0, 0, 0))
    for name, (x, y) in SWATCHES.items():
        shades = PALETTE[name]
        for dy in range(8):
            for dx in range(8):
                atlas.putpixel((x + dx, y + dy), shades[rng.choice((0, 1, 1, 1, 2))] + (255,))
    atlas.alpha_composite(decal('ring', rng), DECALS['ring'][:2])
    atlas.alpha_composite(decal('spore', rng), DECALS['spore'][:2])
    data = BytesIO()
    atlas.save(data, format='PNG', optimize=True)
    return data.getvalue()


def decal(kind, rng):
    """64 px floor decal for a 16-unit disc: a translucent fill, a solid danger rim and moss speckles."""
    from PIL import Image, ImageDraw

    size = 64
    layer = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    pen = ImageDraw.Draw(layer)
    fill = (86, 128, 52, 64) if kind == 'ring' else (198, 216, 98, 56)
    band = (54, 86, 40, 214) if kind == 'ring' else (182, 202, 84, 214)
    pen.ellipse((1, 1, size - 2, size - 2), fill=fill)
    pen.ellipse((1, 1, size - 2, size - 2), outline=PALETTE['warn'][1] + (236,), width=2)
    pen.ellipse((4, 4, size - 5, size - 5), outline=band, width=3)
    centre = (size - 1) / 2
    for _ in range(90 if kind == 'ring' else 60):
        angle = rng.uniform(0, math.tau)
        radius = rng.uniform(4, 25)
        x, y = centre + math.cos(angle) * radius, centre + math.sin(angle) * radius
        colour = rng.choice(PALETTE['moss_light'] if kind == 'ring' else PALETTE['spore_light'])
        r = rng.choice((1, 1, 2))
        pen.ellipse((x - r, y - r, x + r, y + r), fill=colour + (150,))
    if kind == 'spore':
        for index in range(16):
            angle = index * math.tau / 16
            x, y = centre + math.cos(angle) * 23, centre + math.sin(angle) * 23
            pen.ellipse((x - 2, y - 2, x + 2, y + 2), fill=PALETTE['spore_light'][2] + (230,))
    return layer


# ---------------------------------------------------------------- shapes

@dataclass
class Part:
    center: tuple
    size: tuple
    material: str
    bone: str = 'root'
    yaw: float = 0.0
    decal: str | None = None

    def bounds(self):
        return ([c - s / 2 for c, s in zip(self.center, self.size)],
                [c + s / 2 for c, s in zip(self.center, self.size)])

    def uv(self, face):
        if self.decal:
            u, v, size = DECALS[self.decal]
            return (u, v, size, size)
        u, v = SWATCHES[self.material]
        return (u + 1, v + 1, 6, 6)


# Bones animated per frame on Java and by Molang on Bedrock: (scale x, y, z), (shift x, y, z) for frames 0-3.
BONE_FRAMES = {
    'creep': [((s, 1, s), (0, 0, 0)) for s in (0.72, 0.86, 1.0, 0.86)],
    'tufts': [((1, s, 1), (0, 0, 0)) for s in (0.8, 1.0, 1.2, 1.0)],
    'float': [((1, 1, 1), (0, y, 0)) for y in (0.0, 0.8, 1.6, 0.8)],
    'leaves': [((s, s, 1), (0, 0, 0)) for s in (0.75, 0.9, 1.05, 0.9)],
    'spikes': [((1, s, 1), (0, 0, 0)) for s in (0.3, 0.8, 1.15, 1.0)],
    'burst': [((s, s, s), (0, y, 0)) for s, y in ((0.45, 0.5), (0.75, 2.0), (1.0, 3.6), (1.15, 4.4))],
}


def polar(radius, y, degrees):
    angle = math.radians(degrees)
    return (radius * math.sin(angle), y, radius * math.cos(angle))


def radial(parts, degrees, radius, y, size, material, bone='root'):
    # Keep element yaws inside -45..45 (multiples of 22.5) by swapping the box axes for the other quadrants.
    yaw = (degrees + 45) % 90 - 45
    if (degrees - yaw) % 180:
        size = (size[2], size[1], size[0])
    parts.append(Part(polar(radius, y, degrees), size, material, bone, yaw))


def tuft(parts, degrees, radius, height, bone):
    radial(parts, degrees, radius, height / 2, (1.5, height, 1.0), 'moss', bone)
    radial(parts, degrees, radius, height + 0.35, (1.0, 0.7, 0.7), 'moss_light', bone)


def warning_ring():
    parts = [Part((0, 0.06, 0), (16, 0.06, 16), 'moss', decal='ring')]
    for index in range(16):
        degrees = index * 22.5
        tuft(parts, degrees, 7.35, 1.4 if index % 2 else 2.2, 'tufts')
        if index % 4 == 0:
            radial(parts, degrees, 7.0, 0.7, (1.5, 1.4, 1.5), 'stone', 'root')
            radial(parts, degrees, 7.0, 1.55, (1.0, 0.3, 1.0), 'moss_light', 'root')
    for index in range(8):
        radial(parts, index * 45 + 22.5, 4.2, 0.25, (1.8, 0.5, 1.0), 'moss_dark', 'creep')
    return parts


def warning_spore():
    parts = [Part((0, 0.06, 0), (16, 0.06, 16), 'spore', decal='spore')]
    for index in range(16):
        degrees = index * 22.5
        radial(parts, degrees, 7.35, 0.6, (1.2, 1.2, 1.2), 'spore' if index % 2 else 'moss', 'root')
    for index in range(8):
        degrees = index * 45
        radial(parts, degrees, 3.4 + (index % 2) * 1.6, 2.4 + (index % 3) * 0.8, (0.9, 0.9, 0.9), 'spore_light', 'float')
    return parts


def warning_vine():
    parts = []
    for side in (-1, 1):
        parts.append(Part((side * 4.6, 0.35, 0), (1.6, 0.7, 16), 'moss_dark'))
        parts.append(Part((side * 4.6, 0.9, 0), (0.9, 0.4, 16), 'moss'))
    parts.append(Part((0, 0.12, 0), (7.6, 0.24, 16), 'moss'))
    # Every leaf has a twin at (-x, -z), so a 180-degree turn of the display shows the same strip.
    for side in (-1, 1):
        for z in (-6, -3, 0, 3, 6):
            parts.append(Part((side * 2.4, 0.9, z + side * 0.6), (2.6, 1.0, 1.4), 'moss_light', 'leaves'))
        parts.append(Part((side * 6.4, 0.6, side * 5.0), (1.4, 1.2, 1.0), 'warn', 'leaves'))
        parts.append(Part((side * 6.4, 0.6, -side * 2.0), (1.4, 1.2, 1.0), 'moss_light', 'leaves'))
    return parts


def impact_spikes():
    # No floor decal: neighbouring clusters overlap, and coplanar decals would z-fight.
    parts = []
    spikes = [((0, 0), 14)] + [(polar(4.6, 0, d)[0::2], 9 + (i % 2) * 2) for i, d in enumerate(range(0, 360, 60))]
    for (x, z), height in spikes:
        parts.append(Part((x, height * 0.3, z), (3.0, height * 0.6, 3.0), 'moss_dark', 'spikes'))
        parts.append(Part((x, height * 0.75, z), (2.0, height * 0.3, 2.0), 'moss', 'spikes'))
        parts.append(Part((x, height * 0.95, z), (1.0, height * 0.1, 1.0), 'moss_light', 'spikes'))
    for index in range(8):
        radial(parts, index * 45 + 22.5, 7.0, 0.4, (1.2, 0.8, 1.2), 'stone_dark' if index % 2 else 'root')
    return parts


def impact_spores():
    parts = [Part((0, 0.06, 0), (16, 0.06, 16), 'spore', decal='spore')]
    parts.append(Part((0, 1.6, 0), (4.0, 3.2, 4.0), 'spore', 'burst'))
    for index in range(16):
        outer = index % 2 == 0
        radial(parts, index * 22.5, 5.0 if outer else 3.4, 1.2 + (index % 3), (1.6, 1.6, 1.6),
               'spore_light' if outer else 'spore', 'burst')
    return parts


BUILDERS = {
    'warning_ring': warning_ring, 'warning_spore': warning_spore, 'warning_vine': warning_vine,
    'impact_spikes': impact_spikes, 'impact_spores': impact_spores,
}


def posed(parts, frame):
    """Apply one frame's bone transforms (scale about the floor centre, then shift)."""
    result = []
    for part in parts:
        if part.bone not in BONE_FRAMES:
            result.append(part)
            continue
        (sx, sy, sz), (tx, ty, tz) = BONE_FRAMES[part.bone][frame]
        x, y, z = part.center
        w, h, d = part.size
        result.append(replace(part, center=(x * sx + tx, y * sy + ty, z * sz + tz), size=(w * sx, h * sy, d * sz)))
    return result


# ---------------------------------------------------------------- Java

def r4(values):
    return [round(v, 4) for v in values]


def java_model(parts):
    elements = []
    for part in parts:
        start, end = part.bounds()
        faces = {}
        for face in FACES:
            if part.decal and face not in ('up', 'down'):
                continue
            u, v, w, h = part.uv(face)
            faces[face] = {'uv': r4(q / 8 for q in (u, v, u + w, v + h)), 'texture': '#0'}
        element = {'from': r4((start[0] + 8, start[1] + 8, start[2] + 8)),
                   'to': r4((end[0] + 8, end[1] + 8, end[2] + 8)),
                   'shade': False, 'light_emission': LIGHT, 'faces': faces}
        if part.yaw:
            element['rotation'] = {'origin': r4(c + 8 for c in part.center), 'axis': 'y', 'angle': part.yaw}
        elements.append(element)
    return {'ambientocclusion': False, 'textures': {'0': f'astralrecord:item/{NAME}_skill',
                                                    'particle': f'astralrecord:item/{NAME}_skill'},
            'elements': elements}


def model_name(style, frame):
    return f'{NAME}_{style}' if frame == 0 else f'{NAME}_{style}_frame_{frame}'


def java_item(style):
    lookup = lambda frame: {'type': 'minecraft:model', 'model': f'astralrecord:item/{model_name(style, frame)}'}
    return {'model': {'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
                      'fallback': lookup(0),
                      'entries': [{'threshold': frame, 'model': lookup(frame)} for frame in range(1, FRAMES)]}}


# ---------------------------------------------------------------- Bedrock

def geometry(style):
    bones = {'root': {'name': 'root', 'pivot': [0, 0, 0]}}
    for part in BUILDERS[style]():
        if part.bone not in bones:
            bones[part.bone] = {'name': part.bone, 'parent': 'root', 'pivot': [0, 0, 0]}
        start, end = part.bounds()
        uv = {}
        for face in FACES:
            if part.decal and face not in ('up', 'down'):
                continue
            u, v, w, h = part.uv(face)
            uv[face] = {'uv': [u + w, v + h], 'uv_size': [-w, -h]} if face in ('up', 'down') \
                else {'uv': [u, v], 'uv_size': [w, h]}
        cube = {'origin': r4((-end[0], start[1], start[2])), 'size': r4(part.size), 'uv': uv}
        if part.yaw:
            x, y, z = part.center
            cube.update(pivot=r4((-x, y, z)), rotation=[0, -part.yaw, 0])
        bones[part.bone].setdefault('cubes', []).append(cube)
    return {
        'description': {'identifier': f'geometry.astralrecord.{NAME}_{style}', 'texture_width': ATLAS,
                        'texture_height': ATLAS, 'visible_bounds_width': 32, 'visible_bounds_height': 12,
                        'visible_bounds_offset': [0, 2, 0]},
        'bones': list(bones.values()),
    }


def skill_entity():
    return {'format_version': '1.10.0', 'minecraft:client_entity': {'description': {
        'identifier': f'astralrecord:{NAME}_skill',
        'materials': {'default': 'entity_emissive_alpha'},
        'textures': {'default': f'textures/entity/{NAME}_skill'},
        'geometry': {style: f'geometry.astralrecord.{NAME}_{style}' for style in STYLES},
        'animations': {'shape': f'animation.astralrecord.{NAME}_skill.shape'},
        'scripts': {'pre_animation': [
            # Warnings breathe on a 0.6 s loop (Java: 3 ticks per frame); impacts rise over 0.4 s (Java: 2 ticks per frame).
            'variable.pulse = math.mod(query.life_time, 0.6) / 0.6;',
            'variable.breath = math.sin(180.0 * variable.pulse);',
            'variable.travel = math.clamp(query.life_time / 0.4, 0.0, 1.0);',
            'variable.spike = variable.travel < 0.75 ? math.lerp(0.3, 1.15, variable.travel / 0.75)'
            ' : math.lerp(1.15, 1.0, (variable.travel - 0.75) / 0.25);',
            'variable.burst = math.lerp(0.45, 1.15, variable.travel);',
        ], 'animate': ['shape']},
        'render_controllers': [f'controller.render.astralrecord.{NAME}_skill'],
    }}}


def skill_animation():
    return {'format_version': '1.8.0', 'animations': {f'animation.astralrecord.{NAME}_skill.shape': {
        'loop': True, 'bones': {
            'root': {'scale': [f"query.property('astralrecord:{axis}')" for axis in ('width', 'height', 'length')]},
            'creep': {'scale': ['0.72 + 0.28 * variable.breath', 1, '0.72 + 0.28 * variable.breath']},
            'tufts': {'scale': [1, '0.8 + 0.4 * variable.breath', 1]},
            'float': {'position': [0, '1.6 * variable.breath', 0]},
            'leaves': {'scale': ['0.75 + 0.3 * variable.breath', '0.75 + 0.3 * variable.breath', 1]},
            'spikes': {'scale': [1, 'variable.spike', 1]},
            'burst': {'scale': ['variable.burst', 'variable.burst', 'variable.burst'],
                      'position': [0, 'math.lerp(0.5, 4.4, variable.travel)', 0]},
        },
    }}}


def skill_controller():
    return {'format_version': '1.8.0', 'render_controllers': {f'controller.render.astralrecord.{NAME}_skill': {
        'arrays': {'geometries': {'Array.shapes': [f'Geometry.{style}' for style in STYLES]}},
        'geometry': f"Array.shapes[math.clamp(query.property('astralrecord:style'), 0, {len(STYLES) - 1})]",
        'materials': [{'*': 'Material.default'}], 'textures': ['Texture.default'], 'ignore_lighting': True,
    }}}


# ---------------------------------------------------------------- outputs

def json_bytes(value):
    return (json.dumps(value, indent=2) + '\n').encode()


def outputs():
    atlas = texture()
    yield f'assets/astralrecord/textures/item/{NAME}_skill.png', atlas
    yield f'bedrock/textures/entity/{NAME}_skill.png', atlas
    for style in STYLES:
        yield f'assets/astralrecord/items/{NAME}_{style}.json', json_bytes(java_item(style))
        for frame in range(FRAMES):
            yield (f'assets/astralrecord/models/item/{model_name(style, frame)}.json',
                   json_bytes(java_model(posed(BUILDERS[style](), frame))))
    yield f'bedrock/models/entity/{NAME}_skill.geo.json', json_bytes(
        {'format_version': '1.12.0', 'minecraft:geometry': [geometry(style) for style in STYLES]})
    yield f'bedrock/entity/{NAME}_skill.entity.json', json_bytes(skill_entity())
    yield f'bedrock/animations/{NAME}_skill.animation.json', json_bytes(skill_animation())
    yield f'bedrock/render_controllers/{NAME}_skill.render_controllers.json', json_bytes(skill_controller())


# ---------------------------------------------------------------- preview

def preview(path):
    """Render every style and frame from above and from a low side angle, for Java models and Bedrock geometry."""
    from PIL import Image, ImageDraw

    image = Image.open(BytesIO(texture())).convert('RGBA')
    views = (('top', 80.0), ('low', 12.0))
    cell, pad = 220, 18
    rows = [(style, edition) for style in STYLES for edition in ('Java', 'Bedrock')]
    sheet = Image.new('RGB', (cell * FRAMES * len(views), (cell + pad) * len(rows)), (40, 44, 40))
    pen = ImageDraw.Draw(sheet)
    for row, (style, edition) in enumerate(rows):
        for frame in range(FRAMES):
            if edition == 'Java':
                quads = model_quads(java_model(posed(BUILDERS[style](), frame)), image)
            else:
                quads = geo_quads(geometry(style), image, frame)
            for column, (label, pitch) in enumerate(views):
                x = (column * FRAMES + frame) * cell
                y = row * (cell + pad) + pad
                sheet.paste(draw(quads, pitch, cell), (x, y))
                pen.text((x + 4, y - 14), f'{edition} {style} f{frame} {label}', fill=(230, 230, 220))
    sheet.save(path)
    print('preview', path)


def model_quads(model, image):
    quads = []
    for element in model['elements']:
        rotation = element.get('rotation')
        yaw = rotation['angle'] if rotation else 0.0
        origin = rotation['origin'] if rotation else (8, 8, 8)
        for face, data in element['faces'].items():
            u1, v1, u2, v2 = (q * image.width / 16 for q in data['uv'])
            quads += face_texels(element['from'], element['to'], face, (u1, v1, u2, v2), image,
                                 lambda p, yaw=yaw, origin=origin: centred(rotate_y(p, origin, yaw)))
    return quads


def geo_quads(geo, image, frame):
    """Bedrock geometry in Blockbench view space (x mirrored back), with the Java frame's bone transforms."""
    quads = []
    for bone in geo['bones']:
        scale, shift = BONE_FRAMES.get(bone['name'], [((1, 1, 1), (0, 0, 0))] * FRAMES)[frame]
        for cube in bone.get('cubes', []):
            ox, oy, oz = cube['origin']
            sx, sy, sz = cube['size']
            lo, hi = (-(ox + sx) + 8, oy + 8, oz + 8), (-ox + 8, oy + sy + 8, oz + sz + 8)
            yaw = -cube['rotation'][1] if 'rotation' in cube else 0.0
            pivot = cube.get('pivot', (0, 0, 0))
            origin = (-pivot[0] + 8, pivot[1] + 8, pivot[2] + 8)
            for face, data in cube['uv'].items():
                (u, v), (w, h) = data['uv'], data['uv_size']
                uv = (u + w, v + h, u, v) if face in ('up', 'down') else (u, v, u + w, v + h)

                def place(p, yaw=yaw, origin=origin, scale=scale, shift=shift):
                    x, y, z = centred(rotate_y(p, origin, yaw))
                    return (x * scale[0] + shift[0], y * scale[1] + shift[1], z * scale[2] + shift[2])
                quads += face_texels(lo, hi, face, uv, image, place)
    return quads


NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0),
           'up': (0, 1, 0), 'down': (0, -1, 0)}
AXES = {'north': ((-1, 0, 0), (0, -1, 0)), 'south': ((1, 0, 0), (0, -1, 0)), 'east': ((0, 0, -1), (0, -1, 0)),
        'west': ((0, 0, 1), (0, -1, 0)), 'up': ((1, 0, 0), (0, 0, 1)), 'down': ((1, 0, 0), (0, 0, -1))}


def centred(p):
    return (p[0] - 8, p[1] - 8, p[2] - 8)


def rotate_y(p, origin, degrees):
    if not degrees:
        return p
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    x, z = p[0] - origin[0], p[2] - origin[2]
    return (origin[0] + c * x + s * z, p[1], origin[2] - s * x + c * z)


def face_texels(lo, hi, face, uv, image, place):
    normal = NORMALS[face]
    u_axis, v_axis = AXES[face]
    corner = [0.0, 0.0, 0.0]
    for k in range(3):
        if normal[k]:
            corner[k] = hi[k] if normal[k] > 0 else lo[k]
        elif u_axis[k]:
            corner[k] = lo[k] if u_axis[k] > 0 else hi[k]
        else:
            corner[k] = lo[k] if v_axis[k] > 0 else hi[k]
    size_u = sum(abs(u_axis[k]) * (hi[k] - lo[k]) for k in range(3))
    size_v = sum(abs(v_axis[k]) * (hi[k] - lo[k]) for k in range(3))
    u1, v1, u2, v2 = uv
    steps_u = max(1, min(16, int(round(abs(u2 - u1)))))
    steps_v = max(1, min(16, int(round(abs(v2 - v1)))))
    shade = {'up': 1.0, 'down': 0.55, 'north': 0.8, 'south': 0.8, 'east': 0.68, 'west': 0.68}[face]
    result = []
    for i in range(steps_u):
        for j in range(steps_v):
            px = int(u1 + (i + 0.5) / steps_u * (u2 - u1))
            py = int(v1 + (j + 0.5) / steps_v * (v2 - v1))
            colour = image.getpixel((min(image.width - 1, max(0, px)), min(image.height - 1, max(0, py))))
            if colour[3] < 24:
                continue
            points = []
            for a, b in ((i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)):
                p = [corner[k] + u_axis[k] * size_u * a / steps_u + v_axis[k] * size_v * b / steps_v for k in range(3)]
                points.append(place(tuple(p)))
            result.append((points, tuple(int(c * shade) for c in colour[:3]), colour[3] / 255))
    return result


def draw(quads, pitch, cell):
    from PIL import Image, ImageDraw

    image = Image.new('RGB', (cell, cell), (56, 62, 54))
    overlay = Image.new('RGBA', (cell, cell), (0, 0, 0, 0))
    pen = ImageDraw.Draw(overlay)
    yaw = math.radians(30)
    tilt = math.radians(pitch)
    zoom = cell / 22
    items = []
    for points, colour, alpha in quads:
        projected = []
        for x, y, z in points:
            rx, rz = x * math.cos(yaw) - z * math.sin(yaw), x * math.sin(yaw) + z * math.cos(yaw)
            sy, depth = y * math.cos(tilt) - rz * math.sin(tilt), y * math.sin(tilt) + rz * math.cos(tilt)
            projected.append((cell / 2 + rx * zoom, cell * 0.62 - sy * zoom, depth))
        items.append((sum(p[2] for p in projected) / 4, [(p[0], p[1]) for p in projected], colour, alpha))
    items.sort(key=lambda item: -item[0])
    for _, points, colour, alpha in items:
        pen.polygon(points, fill=colour + (int(255 * alpha),))
    image.paste(overlay, (0, 0), overlay)
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Check the generated files without writing them.')
    parser.add_argument('--preview', help='Write a PNG contact sheet of every style and frame (needs Pillow).')
    args = parser.parse_args()
    mismatches = []
    for name, expected in outputs():
        path = ROOT / name
        if args.check:
            actual = path.read_bytes() if path.is_file() else None
            if actual is not None and path.suffix == '.json':
                actual = actual.replace(b'\r\n', b'\n')
            if actual != expected:
                mismatches.append(name)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if args.preview:
        preview(args.preview)
    if mismatches:
        parser.exit(1, 'Regenerate moss zombie effect assets:\n' + '\n'.join(mismatches) + '\n')
    if args.check:
        print('moss zombie effects: all files match')


if __name__ == '__main__':
    main()
