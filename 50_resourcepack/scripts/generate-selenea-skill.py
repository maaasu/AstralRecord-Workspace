"""Build matching Selenea spell geometry and texture atlases for both editions."""

import argparse
import json
import math
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parent.parent
STYLES = (
    'warning_circle', 'warning_line', 'warning_boundary', 'warning_safe',
    'impact', 'impact_line', 'wave', 'bolt', 'warning_edge',
    'warning_grimoire', 'impact_grimoire',
)
ATLAS_SIZE = 256
FACES = ('north', 'south', 'west', 'east', 'up', 'down')
PALETTE = {
    'ink': ('#21152f', '#3b2254', '#50316c'),
    'amethyst': ('#6732a1', '#9652cc', '#b56df0'),
    'lilac': ('#b15de9', '#db9dff', '#efc5ff'),
    'moonlight': ('#d8b9ff', '#f3defe', '#fdf1ff'),
    'wax': ('#9d5798', '#c874af', '#f29fd1'),
    'paper': ('#c6abdf', '#ecd6f1', '#fff2e5'),
    'safe': ('#225151', '#377f73', '#55a894'),
    'mint': ('#49a991', '#83dbb6', '#bcf2cd'),
    'silver': ('#9eaccc', '#d1dcf3', '#f5fcf4'),
}
SWATCHES = {name: (index * 8, 232) for index, name in enumerate(PALETTE)}
PATTERNS = {
    'circle': (0, 0, 128), 'boundary': (128, 0, 128), 'seal': (0, 128, 96),
    'page': (96, 128, 32), 'cover': (128, 128, 32), 'wave': (160, 128, 96),
}
DECALS = ('circle', 'boundary', 'seal', 'wave')
PIVOTS = {
    'left_leaf': (0, 4.5, 0), 'right_leaf': (0, 4.5, 0),
}


def texture():
    atlas = Image.new('RGBA', (ATLAS_SIZE, ATLAS_SIZE))
    draw = ImageDraw.Draw(atlas)
    for name, (shadow, color, highlight) in PALETTE.items():
        x, y = SWATCHES[name]
        draw.rectangle((x, y, x + 7, y + 7), fill=color)
        draw.line((x, y + 6, x + 7, y + 6), fill=shadow, width=2)
        draw.line((x, y + 1, x + 7, y + 1), fill=highlight, width=2)
        draw.rectangle((x + 4, y + 3, x + 6, y + 4), fill=highlight)

    for name in ('circle', 'boundary', 'seal'):
        offset_x, offset_y, size = PATTERNS[name]
        mid = (size - 1) / 2
        radius = mid - 1
        safe = name == 'seal'
        outside = name == 'boundary'
        palette = PALETTE['mint' if safe else 'lilac']
        under = PALETTE['safe' if safe else 'ink'][1]
        rune = Image.new('RGBA', (size, size))
        pen = ImageDraw.Draw(rune)
        pen.ellipse((1, 1, size - 2, size - 2), outline=palette[2], width=2)
        inset = 6 if safe else 5
        pen.ellipse((inset, inset, size - 1 - inset, size - 1 - inset), outline=under, width=3)
        pen.ellipse((inset + 4, inset + 4, size - inset - 5, size - inset - 5), outline=palette[0])
        for i in range(16):
            angle = i * math.tau / 16
            tip = radius - (0 if outside else 15 if i % 4 == 0 else 11)
            base = radius - (13 if outside else 3)
            points = [(mid + r * math.cos(angle + a), mid + r * math.sin(angle + a))
                      for r, a in ((tip, 0), (base, -0.044), (base, 0.044))]
            pen.polygon(points, fill=palette[2] if i % 4 == 0 else palette[0])
        if not outside:
            # A crescent cut from a solid disc and a separate four-point moon glint.
            crescent = 13 if safe else 19
            pen.ellipse((mid-crescent, mid-crescent, mid+crescent, mid+crescent), fill=palette[1])
            pen.ellipse((mid-crescent*0.55, mid-crescent*1.2, mid+crescent*1.35, mid+crescent*0.60), fill=(0, 0, 0, 0))
            x, y = mid + crescent * .31, mid - crescent * .21
            pen.polygon(((x, y-5), (x+2, y-1), (x+5, y), (x+2, y+1),
                         (x, y+5), (x-2, y+1), (x-5, y), (x-2, y-1)), fill=palette[2])
        atlas.paste(rune, (offset_x, offset_y))

    x, y, _ = PATTERNS['page']
    draw.rectangle((x, y, x + 31, y + 31), fill='#edcde9', outline='#ab71a7', width=2)
    draw.rectangle((x + 3, y + 3, x + 28, y + 28), outline='#dba9d5')
    for row, width in ((8, 14), (12, 18), (19, 16), (23, 10)):
        draw.line((x + 7, y + row, x + 7 + width, y + row), fill='#844c8c', width=2)
    draw.polygon(((x+17, y+12), (x+20, y+16), (x+17, y+20), (x+14, y+16)), fill='#f4e2fa')
    x, y, _ = PATTERNS['cover']
    draw.rectangle((x, y, x + 31, y + 31), fill='#3c2157', outline='#a867c8', width=2)
    draw.rectangle((x + 4, y + 4, x + 27, y + 27), outline='#704392')
    draw.polygon(((x+16, y+6), (x+25, y+16), (x+16, y+25), (x+7, y+16)), outline='#c281e0', width=2)
    draw.rectangle((x+14, y+13, x+17, y+18), fill='#f3c6ed')
    x, y, size = PATTERNS['wave']
    draw.ellipse((x, y, x + size - 1, y + size - 1), outline=PALETTE['lilac'][1])
    data = BytesIO()
    atlas.save(data, format='PNG', optimize=True)
    return data.getvalue()


@dataclass
class Part:
    center: tuple
    size: tuple
    material: str
    bone: str = 'root'
    yaw: float = 0
    roll: float = 0
    pattern: str | None = None

    def bounds(self):
        return ([c - s / 2 for c, s in zip(self.center, self.size)],
                [c + s / 2 for c, s in zip(self.center, self.size)])

    def uv(self, face):
        if self.pattern and face in ('up', 'down'):
            u, v, size = PATTERNS[self.pattern]
            return (u, v, size, size)
        u, v = SWATCHES[self.material]
        return (u + 1, v + 1, 6, 5)


def polar(radius, y, degrees):
    angle = math.radians(degrees)
    return (radius * math.sin(angle), y, radius * math.cos(angle))


def radial(parts, degrees, radius, y, size, material, bone='root'):
    # A square axis switch keeps all rotations valid in the Java block model.
    yaw = (degrees + 45) % 90 - 45
    if (degrees - yaw) % 180:
        size = (size[2], size[1], size[0])
    parts.append(Part(polar(radius, y, degrees), size, material, bone, yaw))


def sigil(parts, pattern):
    # Tiny thickness makes this readable from below as well as above.
    parts.append(Part((0, 0.06, 0), (16, 0.06, 16), 'safe' if pattern == 'seal' else 'ink', pattern=pattern))


def ring(parts, safe=False, outside=False):
    dark, light = ('safe', 'mint') if safe else ('ink', 'lilac')
    for index in range(16):
        degrees = index * 22.5
        cardinal = index % 4 == 0
        radial(parts, degrees, 7.52, .42, (2.25, .75, .58), dark)
        radial(parts, degrees, 7.69, .94, (1.65, .35, .28), light)
        radial(parts, degrees, 7.45, 2.25 if cardinal else 1.55,
               (.48, 3.0 if cardinal else 1.8, .50), light)
        if cardinal:
            radial(parts, degrees, 7.38 if outside else 6.98, 4.05, (.65, .65, .65),
                   'silver' if safe else 'moonlight')


def circle_warning(style, frame):
    parts = []
    safe = style == 'warning_safe'
    outside = style == 'warning_boundary'
    sigil(parts, 'seal' if safe else 'boundary' if outside else 'circle')
    ring(parts, safe, outside)
    for i in range(8):
        angle = i * 45
        radial(parts, angle, 6.7 if outside else 4.8, .85, (1.1, .25, .4),
               'mint' if safe else 'amethyst')
        if not outside:
            radial(parts, angle, 4.8, 1.1, (.40, .22, .40), 'silver' if safe else 'moonlight', 'runes')
    if frame is None:
        return parts
    return transform(parts, 'runes', scale=((.76, .91, 1.05, .91)[frame], 1, (.76, .91, 1.05, .91)[frame]))


def warning_line(frame, edge=False):
    parts = []
    x_scale = 8 if edge else 1
    for x in ((0,) if edge else (-7.24, 7.24)):
        parts.append(Part((x, .55, 0), (1.45 * x_scale, .95, 16), 'ink'))
        parts.append(Part((x, 1.12, 0), (.62 * x_scale, .23, 16), 'lilac'))
        for z in (-6.7, -2.2, 2.2, 6.7):
            parts.append(Part((x, 2.9, z), (.72 * x_scale, 3.35, .73), 'amethyst'))
            parts.append(Part((x, 4.75, z), (1.05 * x_scale, .63, .78), 'moonlight'))
    if edge:
        return parts
    for z in (-7.45, 7.45):
        parts.append(Part((0, .55, z), (13, .95, .98), 'ink'))
        parts.append(Part((0, 1.12, z), (13, .23, .36), 'lilac'))
    for x in (-4, 4):
        for z in (-4.5, -.7, 3.1):
            parts.append(Part((x, .48, z), (1.75, .55, .5), 'amethyst', yaw=45 if x > 0 else -45))
    for x in (-2.7, 0, 2.7):
        parts.append(Part((x, .46, 0), (1.1, .55, .76), 'moonlight', 'ink_sweep', yaw=45))
    if frame is None:
        return parts
    return transform(parts, 'ink_sweep', shift=(0, 0, (5.8, 2.3, -2.3, -5.8)[frame]))


def impact(frame):
    parts = []
    for index in range(16):
        degrees = index * 22.5
        radial(parts, degrees, 6.6, .22, (2, .36, 1), 'ink')
        if index % 2 == 0:
            radial(parts, degrees, 5.4, 2.05, (1.55, 3.8, 2.35), 'amethyst', 'burst')
            radial(parts, degrees, 5.74, 3.95, (.95, 3.5, 1.35), 'lilac', 'burst')
            radial(parts, degrees, 6.06, 5.8, (.48, 2.3, .65), 'moonlight', 'burst')
        else:
            radial(parts, degrees, 4.15, 1.05, (.82, 1.65, 2.95), 'wax', 'burst')
    parts.append(Part((0, 1.25, 0), (3.2, 2.4, 3.2), 'ink', 'eclipse', yaw=45))
    parts.append(Part((0, 2.46, 0), (2.6, .70, 2.6), 'lilac', 'eclipse', yaw=45))
    parts.append(Part((0, 2.88, 0), (1.4, .34, 1.4), 'moonlight', 'eclipse', yaw=45))
    if frame is None:
        return parts
    return transform(transform(parts, 'burst', scale=((.38, .63, .84, 1)[frame], (.34, .94, 1, .26)[frame], (.38, .63, .84, 1)[frame])),
                     'eclipse', scale=(1, (.38, 1.2, .75, .25)[frame], 1))


def impact_line(frame):
    parts = [Part((x, .35, 0), (1.2, .65, 16), 'ink') for x in (-7.25, 7.25)]
    for x in (-5.4, -2.7, 0, 2.7, 5.4):
        offset = abs(x) * .16
        parts.append(Part((x, .98, offset + .68), (2.25, 1.8, 2.45), 'amethyst', 'line_break'))
        parts.append(Part((x, 2.7, offset), (1.5, 4.1, 1.4), 'lilac', 'line_break'))
        parts.append(Part((x, 5.15, offset - .30), (.85, 2.5, .60), 'moonlight', 'line_break'))
    if frame is None:
        return parts
    return transform(parts, 'line_break', scale=(1, (.36, .94, .78, .30)[frame], 1),
                     shift=(0, 0, (5.05, 1.6, -1.6, -5.05)[frame]))


def wave(frame):
    parts = []
    sigil(parts, 'wave')
    for index in range(16):
        degrees = index * 22.5
        radial(parts, degrees, 7.69, 1.05, (2.4, 1.9, .26), 'ink')
        radial(parts, degrees, 7.76, 2.45, (2.2, 2.3, .22), 'amethyst', 'crest')
        radial(parts, degrees, 7.83, 4.01, (1.55, 1.55, .18), 'lilac', 'crest')
        if index % 2 == 0:
            radial(parts, degrees, 7.89, 5.08, (.73, .88, .16), 'moonlight', 'crest')
    if frame is None:
        return parts
    return transform(parts, 'crest', scale=(1, (.48, .80, 1, .72)[frame], 1))


def bolt(frame):
    parts = [Part((0, 0, -1.2), (3.8, 3.8, 5.5), 'amethyst'),
             Part((0, 0, -3.8), (2.9, 2.9, 2.2), 'lilac', yaw=45),
             Part((0, 0, -5.1), (1.68, 1.68, 2.65), 'moonlight'),
             Part((0, 0, 3.8), (2, 2, 5.8), 'ink'),
             Part((0, 0, 5.9), (.65, .65, 3.75), 'wax')]
    for axis in (0, 1):
        for side in (-1, 1):
            position = [0, 0, 1.3]
            position[axis] = side * 2.5
            size = [1.1, 1.1, 3.8]
            size[axis] = 3.4
            parts.append(Part(tuple(position), tuple(size), 'lilac', 'quill'))
            position[axis] = side * 4.2
            position[2] = 2.4
            parts.append(Part(tuple(position), (1.2, 1.2, 2.65), 'moonlight', 'quill'))
    if frame is None:
        return parts
    return transform(parts, 'quill', scale=((.65, .9, 1, .9)[frame], (.65, .9, 1, .9)[frame], 1))


def grimoire(frame, impact_spell):
    parts = []
    sigil(parts, 'circle')
    parts.append(Part((0, 4.7, 0), (.72, .7, 7.8), 'wax', 'tome'))
    for side, bone in ((-1, 'left_leaf'), (1, 'right_leaf')):
        parts.append(Part((side * 2.65, 4.42, 0), (5.05, .38, 7.65), 'ink', bone, pattern='cover'))
        parts.append(Part((side * 2.58, 4.77, 0), (4.63, .32, 7.12), 'lilac', bone))
        parts.append(Part((side * 2.46, 5.00, 0), (4.48, .21, 6.92), 'paper', bone, pattern='page'))
        for z in (-3.58, 3.58):
            parts.append(Part((side * 4.7, 4.69, z), (.82, .42, .51), 'moonlight', bone))
    for degrees in (0, 45, 90, 135, 180, 225, 270, 315):
        if impact_spell:
            radial(parts, degrees, 5.36, 3.4, (1.75, .45, 2.65), 'paper', 'pages')
            radial(parts, degrees, 5.37, 3.67, (.46, .19, 1.05), 'wax', 'pages')
        else:
            radial(parts, degrees, 6.7, 1.05, (.6, .78, .6), 'lilac', 'pages')
    return parts if frame is None else grimoire_pose(parts, frame, impact_spell)


def transform(parts, bone, scale=(1, 1, 1), shift=(0, 0, 0)):
    for part in parts:
        if part.bone == bone:
            part.center = tuple(c * k + d for c, k, d in zip(part.center, scale, shift))
            # The four frames only scale rotated boxes along Y or uniformly in their rotation plane.
            part.size = tuple(s * k for s, k in zip(part.size, scale))
    return parts


def grimoire_pose(parts, frame, impact_spell):
    height = ((.65, 2.8, 4.4, 3.05) if impact_spell else (0, .30, .65, .30))[frame]
    roll = ((22.5, 0, -22.5, 22.5) if impact_spell else (22.5, 22.5, 0, 22.5))[frame]
    for bone in ('tome', *PIVOTS):
        transform(parts, bone, shift=(0, height, 0))
    for part in parts:
        if part.bone in PIVOTS:
            part.roll = roll if part.bone == 'right_leaf' else -roll
    page_scale = ((.38, .64, .88, 1.18) if impact_spell else (.95, .86, .79, .86))[frame]
    page_lift = ((.10, 1.1, 2.6, .65) if impact_spell else (0, .16, .40, .16))[frame]
    return transform(parts, 'pages', scale=(page_scale, 1, page_scale), shift=(0, page_lift, 0))


def model(style, frame=None):
    if style in ('warning_circle', 'warning_boundary', 'warning_safe'):
        return circle_warning(style, frame)
    if style == 'warning_line':
        return warning_line(frame)
    if style == 'warning_edge':
        return warning_line(frame, edge=True)
    if style == 'impact':
        return impact(frame)
    if style == 'impact_line':
        return impact_line(frame)
    if style == 'wave':
        return wave(frame)
    if style == 'bolt':
        return bolt(frame)
    return grimoire(frame, style == 'impact_grimoire')


def rounded(values):
    return [round(value, 4) for value in values]


def java_model(parts, style, frame):
    elements = []
    for part in parts:
        start, end = part.bounds()
        faces = {}
        for face in FACES:
            # The decal's transparent sides must remain empty.
            if part.pattern in DECALS and face not in ('up', 'down'):
                continue
            u, v, w, h = part.uv(face)
            faces[face] = {'uv': rounded(q / 16 for q in (u, v, u + w, v + h)), 'texture': '#0'}
        element = {'from': rounded(v + 8 for v in start), 'to': rounded(v + 8 for v in end),
                   'shade': False, 'light_emission': 15, 'faces': faces}
        if part.yaw:
            element['rotation'] = {'origin': rounded(v + 8 for v in part.center), 'axis': 'y', 'angle': part.yaw}
        if part.roll:
            height = ((.65, 2.8, 4.4, 3.05) if style == 'impact_grimoire' else (0, .30, .65, .30))[frame]
            element['rotation'] = {'origin': [8, 12.5 + height, 8], 'axis': 'z', 'angle': part.roll}
        elements.append(element)
    return {'ambientocclusion': False, 'textures': {'0': 'astralrecord:item/selenea_skill'}, 'elements': elements}


def geometry(style):
    # Generate unanimated starting geometry, then supply continuous motion via Molang.
    bones = {'root': {'name': 'root', 'pivot': [0, 0, 0]}}
    for part in model(style):
        if part.bone not in bones:
            bones[part.bone] = {'name': part.bone, 'pivot': list(PIVOTS.get(part.bone, (0, 0, 0))),
                                'parent': 'tome' if part.bone in PIVOTS else 'root'}
        start, end = part.bounds()
        uv = {}
        for face in FACES:
            if part.pattern in DECALS and face not in ('up', 'down'):
                continue
            u, v, w, h = part.uv(face)
            if face in ('up', 'down'):
                uv[face] = {'uv': [u + w, v + h], 'uv_size': [-w, -h]}
            else:
                uv[face] = {'uv': [u, v], 'uv_size': [w, h]}
        cube = {'origin': rounded((-end[0], start[1], start[2])), 'size': rounded(part.size), 'uv': uv}
        if part.yaw:
            x, y, z = part.center
            cube.update(pivot=rounded((-x, y, z)), rotation=[0, -part.yaw, 0])
        bones[part.bone].setdefault('cubes', []).append(cube)
    return {
        'description': {'identifier': f'geometry.astralrecord.selenea_{style}', 'texture_width': ATLAS_SIZE,
                        'texture_height': ATLAS_SIZE, 'visible_bounds_width': 128, 'visible_bounds_height': 32,
                        'visible_bounds_offset': [0, 0, 0]},
        'bones': list(bones.values()),
    }


def skill_entity():
    return {'format_version': '1.10.0', 'minecraft:client_entity': {'description': {
        'identifier': 'astralrecord:selenea_skill',
        'materials': {'default': 'entity_emissive_alpha'},
        'textures': {'default': 'textures/entity/selenea_skill'},
        'geometry': {style: f'geometry.astralrecord.selenea_{style}' for style in STYLES},
        'animations': {'shape': 'animation.astralrecord.selenea_skill.shape'},
        'scripts': {'pre_animation': [
            "variable.style = query.property('astralrecord:style');",
            'variable.pulse = math.mod(query.life_time, 0.6) / 0.6;',
            'variable.travel = math.clamp(query.life_time / 0.35, 0.0, 1.0);',
            'variable.arc = math.sin(180.0 * variable.travel);',
            'variable.breath = math.sin(180.0 * variable.pulse);',
            'variable.rune_scale = 0.76 + 0.29 * variable.breath;',
            'variable.burst_scale = math.lerp(0.38, 1.0, variable.travel);',
            'variable.page_scale = variable.style == 10 ? math.lerp(0.38, 1.18, variable.travel) : 0.95 - 0.16 * variable.breath;',
            'variable.leaf_angle = variable.style == 10 ? 22.5 - 45.0 * variable.arc : 22.5 * (1.0 - variable.breath);',
        ], 'animate': ['shape']},
        'render_controllers': ['controller.render.astralrecord.selenea_skill'],
    }}}


def skill_animation():
    return {'format_version': '1.8.0', 'animations': {'animation.astralrecord.selenea_skill.shape': {
        'loop': True, 'bones': {
            'root': {'scale': [f"query.property('astralrecord:{axis}')" for axis in ('width', 'height', 'length')]},
            'runes': {'scale': ['variable.rune_scale', 1, 'variable.rune_scale']},
            'ink_sweep': {'position': [0, 0, 'math.lerp(5.8, -5.8, variable.pulse)']},
            'burst': {'scale': ['variable.burst_scale', '0.26 + 0.74 * variable.arc', 'variable.burst_scale']},
            'eclipse': {'scale': [1, '0.25 + 0.95 * variable.arc', 1]},
            'line_break': {'scale': [1, '0.30 + 0.64 * variable.arc', 1],
                           'position': [0, 0, 'math.lerp(5.05, -5.05, variable.travel)']},
            'crest': {'scale': [1, '0.48 + 0.52 * variable.breath', 1]},
            'quill': {'scale': ['0.65 + 0.35 * variable.breath', '0.65 + 0.35 * variable.breath', 1]},
            'tome': {'position': [0, 'variable.style == 10 ? 0.65 + 3.75 * variable.arc : 0.65 * variable.breath', 0]},
            'left_leaf': {'rotation': [0, 0, 'variable.leaf_angle']},
            'right_leaf': {'rotation': [0, 0, '-variable.leaf_angle']},
            'pages': {'scale': ['variable.page_scale', 1, 'variable.page_scale'],
                      'position': [0, 'variable.style == 10 ? 0.1 + 2.5 * variable.arc : 0.4 * variable.breath', 0]},
        },
    }}}


def skill_controller():
    return {'format_version': '1.8.0', 'render_controllers': {'controller.render.astralrecord.selenea_skill': {
        'arrays': {'geometries': {'Array.shapes': [f'Geometry.{style}' for style in STYLES]}},
        'geometry': f"Array.shapes[math.clamp(query.property('astralrecord:style'), 0, {len(STYLES) - 1})]",
        'materials': [{'*': 'Material.default'}], 'textures': ['Texture.default'], 'ignore_lighting': True,
    }}}


def json_bytes(value):
    # Keep geometry entries on single lines while keeping names and bone hierarchy easy to scan.
    lines = json.dumps(value, indent=2).splitlines()
    result = []
    entry = []
    indentation = 0
    for line in lines:
        stripped = line.lstrip()
        depth = len(line) - len(stripped)
        if entry:
            entry.append(stripped)
            if depth == indentation:
                result.append(' ' * indentation + ' '.join(entry))
                entry.clear()
        elif stripped == '{' and result and result[-1].strip().endswith(('"elements": [', '"cubes": [')):
            entry.append(stripped)
            indentation = depth
        elif stripped == '{' and result and result[-1].startswith(' ' * depth + '{ '):
            entry.append(stripped)
            indentation = depth
        else:
            result.append(line)
    return ('\n'.join(result) + '\n').encode()


def outputs():
    atlas = texture()
    yield 'assets/astralrecord/textures/item/selenea_skill.png', atlas
    yield 'bedrock/textures/entity/selenea_skill.png', atlas
    for style in STYLES:
        name = f'selenea_{style}'
        model_name = lambda frame: name if frame == 0 else f'{name}_frame_{frame}'
        lookup = lambda frame: {'type': 'minecraft:model', 'model': f'astralrecord:item/{model_name(frame)}'}
        if style == 'warning_edge' or style == 'warning_boundary':
            item = lookup(0)
            frames = (0,)
        else:
            item = {'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
                    'fallback': lookup(0),
                    'entries': [{'threshold': frame, 'model': lookup(frame)} for frame in (1, 2, 3)]}
            frames = (0, 1, 2, 3)
        yield f'assets/astralrecord/items/{name}.json', json_bytes({'model': item})
        for frame in frames:
            yield f'assets/astralrecord/models/item/{model_name(frame)}.json', json_bytes(java_model(model(style, frame), style, frame))
    yield 'bedrock/models/entity/selenea_skill.geo.json', json_bytes({
        'format_version': '1.12.0', 'minecraft:geometry': [geometry(style) for style in STYLES],
    })
    yield 'bedrock/entity/selenea_skill.entity.json', json_bytes(skill_entity())
    yield 'bedrock/animations/selenea_skill.animation.json', json_bytes(skill_animation())
    yield 'bedrock/render_controllers/selenea_skill.render_controllers.json', json_bytes(skill_controller())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Check the generated files without writing them.')
    args = parser.parse_args()
    mismatches = []
    for name, expected in outputs():
        path = ROOT / name
        if args.check:
            actual = path.read_bytes() if path.is_file() else None
            if actual is not None and path.suffix == '.json':
                actual = actual.replace(b"\r\n", b"\n")
            if actual != expected:
                mismatches.append(name)
        else:
            path.write_bytes(expected)
    if mismatches:
        parser.exit(1, 'Regenerate Selenea skill assets:\n' + '\n'.join(mismatches) + '\n')


if __name__ == '__main__':
    main()
