"""Build the Orrery Heart Nyxeron body, spell shapes and sounds for both editions.

The same box lists drive the Java item models (ItemDisplay) and the Bedrock client entities, so
both editions show the same silhouettes. Java motion comes from display interpolation and animated
textures; Bedrock motion comes from Molang driven by the marker's style, size and timing fields.
The sound effects are synthesised here and written as mono Ogg Vorbis with a fixed stream serial,
so a fresh build is byte-identical. Run with --check to verify the committed files.

    uv run --no-project --python 3.12 --with pillow==13.0.0 --with numpy==2.2.6 \
        --with soundfile==0.13.1 python scripts/generate-nyxeron-assets.py --check
"""

import argparse
import json
import math
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

import numpy as np
import soundfile
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'assets' / 'astralrecord'
BEDROCK = ROOT / 'bedrock'
MARKER = ROOT / 'nyxeron-presentation-version.txt'
SOUNDS_JSON = JAVA / 'sounds.json'
PRESENTATION_VERSION = b'2'

# ---------------------------------------------------------------- shared constants
CORE_POSES = ('idle', 'cast', 'attack', 'guard', 'exposed')
RINGS = ('outer', 'mid', 'inner')
# Style ids are shared with NyxeronPackDisplay.Style and the Geyser extension (maxStyle 23).
STYLES = ('disc', 'band_1', 'band_2', 'band_3', 'band_4', 'spoke', 'seal', 'seal_done',
          'scar', 'impact', 'gaze', 'meteor', 'heart',
          'approach', 'fill_bar', 'bullet', 'ring_low', 'ring_high', 'beam_low', 'beam_high',
          'fragment', 'star', 'nova', 'supernova')
BEDROCK_ONLY = ('nova',)
NOVA_BULLETS = 48
# Java ItemDisplay scales from NyxeronPackDisplay and the core height.
CORE_SCALE = 2.0
RING_SCALE = 1.25
CORE_HEIGHT_PX = 2.35 * 16
BODY_TEX = 64
SKILL_TEX = 512
TEXTURE_FRAMES = 8
FACES = ('north', 'south', 'west', 'east', 'up', 'down')

# Body swatches (8x8 px each on a 64x64 atlas): name -> (index, base, shade, light)
BODY_SWATCHES = {
    'obsidian': (0, '#2a1840', '#170c26', '#3d2660'),
    'obsidian_edge': (1, '#1b0f2b', '#0d0617', '#2b1a43'),
    'vein': (2, '#b45cff', '#8a33e0', '#e2b8ff'),
    'eye': (3, '#f6ecff', '#d8c2ff', '#ffffff'),
    'gold': (4, '#e6b23a', '#a87418', '#fff0a6'),
    'copper': (5, '#c36c3b', '#8a4523', '#eaa26a'),
    'patina': (6, '#3f9a86', '#256656', '#7fd6be'),
    'amethyst': (7, '#9a6be0', '#6c43b0', '#d3b2ff'),
    'core': (8, '#ffe9a8', '#ffc65a', '#fffbe8'),
    'crying': (9, '#5a1e9c', '#3a0f6a', '#8a3fd8'),
    'crystal': (10, '#c7a5ff', '#9670e6', '#f1e6ff'),
    'star': (11, '#fff6d8', '#ffd98a', '#ffffff'),
}
# Swatches whose brightness breathes in the Java animated texture.
BODY_GLOW = ('vein', 'eye', 'core', 'crystal', 'star')


@dataclass
class Box:
    """Axis-aligned box in Java model units (block = 16, model centre = 8)."""
    lo: tuple
    hi: tuple
    swatch: str
    group: str = 'shell'
    glow: bool = False
    tint: bool = False
    yaw: float = 0.0  # rotation about the model centre's Y axis, degrees
    faces: tuple = FACES


def cube(cx, cy, cz, sx, sy, sz, swatch, **kw):
    """Box centred on (cx, cy, cz) offsets from the model centre."""
    return Box((8 + cx - sx / 2, 8 + cy - sy / 2, 8 + cz - sz / 2),
               (8 + cx + sx / 2, 8 + cy + sy / 2, 8 + cz + sz / 2), swatch, **kw)


# ---------------------------------------------------------------- body shapes
def core_boxes(pose):
    """Stepped obsidian heart with veins, a lens eye on four sides and pose accents.

    Petals, the guard cage and the motes are separate parts so both editions can open and close
    them smoothly instead of swapping models.
    """
    boxes = []
    exposed = pose == 'exposed'
    if not exposed:
        boxes += [
            cube(0, 0, 0, 10, 6, 6, 'obsidian', group='shell', tint=True),
            cube(0, 0, 0, 6, 10, 6, 'obsidian', group='shell', tint=True),
            cube(0, 0, 0, 6, 6, 10, 'obsidian', group='shell', tint=True),
            cube(0, 0, 0, 8.4, 8.4, 8.4, 'obsidian_edge', group='shell', tint=True),
            cube(0, 0, 0, 10.6, 1.0, 6.6, 'gold', group='shell', tint=True),
            cube(0, 0, 0, 6.6, 1.0, 10.6, 'gold', group='shell', tint=True),
            cube(0, 5.4, 0, 3, 1.2, 3, 'gold', group='shell', tint=True),
            cube(0, -5.4, 0, 3, 1.2, 3, 'gold', group='shell', tint=True),
        ]
        for sign in (-1, 1):
            boxes += [
                cube(sign * 5.05, 2.0, 0, 0.3, 2.2, 1.0, 'vein', group='glow_veins', glow=True),
                cube(sign * 5.05, -2.2, 1.2, 0.3, 1.6, 0.8, 'vein', group='glow_veins', glow=True),
                cube(0, 2.2, sign * 5.05, 1.0, 2.0, 0.3, 'vein', group='glow_veins', glow=True),
                cube(-1.4, -2.0, sign * 5.05, 0.8, 1.8, 0.3, 'vein', group='glow_veins', glow=True),
            ]
    eye = {'cast': 1.35, 'attack': 1.15, 'guard': 0.8}.get(pose, 1.0)
    if not exposed:
        for sign in (-1, 1):
            boxes += [
                cube(sign * 5.25, 0, 0, 0.4, 1.6 * eye, 3.0 * eye, 'eye', group='glow_eye', glow=True),
                cube(sign * 5.4, 0, 0, 0.3, 0.8 * eye, 1.0 * eye, 'crying', group='glow_eye', glow=True),
                cube(0, 0, sign * 5.25, 3.0 * eye, 1.6 * eye, 0.4, 'eye', group='glow_eye', glow=True),
                cube(0, 0, sign * 5.4, 1.0 * eye, 0.8 * eye, 0.3, 'crying', group='glow_eye', glow=True),
            ]
    spike = {'cast': 3.6, 'attack': 4.4, 'guard': 2.0, 'exposed': 1.2}.get(pose, 2.6)
    boxes += [
        cube(0, 6.0 + spike / 2, 0, 1.6, spike, 1.6, 'crying', group='crown', tint=True),
        cube(0, 6.0 + spike + 0.5, 0, 1.0, 1.0, 1.0, 'vein', group='glow_crown', glow=True),
        cube(0, -6.0 - spike / 2, 0, 1.6, spike, 1.6, 'crying', group='crown', tint=True),
    ]
    if exposed:
        boxes += [
            cube(0, 0, 0, 6.6, 6.6, 6.6, 'core', group='glow_exposed', glow=True),
            cube(0, 0, 0, 7.2, 1.2, 7.2, 'gold', group='glow_exposed', glow=True),
            cube(0, 0, 0, 1.2, 7.2, 7.2, 'gold', group='glow_exposed', glow=True),
        ]
        for x, y, z, sx, sy, sz in ((5.6, 2.4, 2.0, 2.2, 3.4, 2.6), (-5.4, -2.6, 1.0, 2.6, 2.8, 3.2),
                                    (1.6, 3.6, -5.6, 3.2, 2.4, 2.2), (-2.4, -4.4, -4.2, 2.4, 2.2, 2.8)):
            boxes.append(cube(x, y, z, sx, sy, sz, 'obsidian', group='shell_shard', tint=True))
    return boxes


def petal_boxes():
    """One petal plate centred on the model, thin along Z; the displays fan four of them out."""
    return [
        cube(0, 0, 0, 5.6, 7.0, 1.2, 'obsidian', group='petal'),
        cube(0, 0, 0, 3.4, 4.6, 1.6, 'vein', group='glow_petal', glow=True),
        cube(0, 4.2, 0, 1.8, 1.8, 1.8, 'gold', group='petal'),
    ]


PETAL_OFFSET = 8.0  # model units from the core centre; Java pushes petals 1 block at CORE_SCALE 2
CAGE_POINTS = ((7.2, 0, 0), (-7.2, 0, 0), (0, 0, 7.2), (0, 0, -7.2), (0, 7.6, 0), (0, -7.6, 0))


def cage_boxes():
    boxes = []
    for x, y, z in CAGE_POINTS:
        sx, sy, sz = (1.4, 4.2, 4.2) if x else (4.2, 1.4, 4.2) if y else (4.2, 4.2, 1.4)
        boxes.append(cube(x, y, z, sx, sy, sz, 'crystal', group='cage', glow=True))
        boxes.append(cube(x, y, z, sx * 0.5, sy * 0.5, sz * 0.5, 'star', group='cage', glow=True))
    return boxes


def mote_boxes():
    return [
        cube(0, 0, 0, 2.6, 2.6, 2.6, 'star', group='mote', glow=True),
        cube(0, 0, 0, 2.2, 2.2, 2.2, 'vein', group='mote', glow=True, yaw=45),
        cube(0, 0, 0, 0.8, 4.4, 0.8, 'star', group='mote', glow=True),
    ]


RING_SPEC = {
    # radius, radial thickness, height, base swatch, tick swatch, glow
    'outer': (22.0, 1.6, 1.2, 'copper', 'gold', False),
    'mid': (18.0, 1.3, 1.0, 'patina', 'gold', False),
    'inner': (13.0, 1.1, 1.6, 'amethyst', 'crying', True),
}


def ring_boxes(kind):
    radius, thick, height, base, tick, glow = RING_SPEC[kind]
    segments = 16
    length = 2 * radius * math.tan(math.pi / segments) * 1.04
    group = 'glow_ring_' + kind if glow else 'ring_' + kind
    boxes = []
    for k in range(segments):
        yaw = k * 360.0 / segments
        boxes.append(cube(radius, 0, 0, thick, height, length, base, group=group, glow=glow, yaw=yaw))
        if k % 2 == 0:
            boxes.append(cube(radius, height / 2 + 0.35, 0, thick * 0.7, 0.7, 1.2, tick,
                              group='glow_tick_' + kind, glow=True, yaw=yaw))
    if kind == 'outer':
        for yaw in (45, 135, 225, 315):
            boxes.append(cube(radius, 0, 0, 2.6, 2.6, 2.6, 'gold', group='ring_outer', yaw=yaw))
            boxes.append(cube(radius, 0, 0, 1.2, 3.2, 1.2, 'star', group='glow_tick_outer', glow=True, yaw=yaw))
    if kind == 'mid':
        for k in range(8):
            boxes.append(cube(radius - thick, 0, 0, 0.8, height * 0.8, 0.8, 'gold', group='ring_mid', yaw=22.5 + k * 45.0))
    return boxes


# ---------------------------------------------------------------- spell shapes
# Atlas regions (px) on the 512x512 spell texture.
REGIONS = {
    'disc': (0, 0), 'band_1': (128, 0), 'band_2': (256, 0), 'band_3': (384, 0),
    'band_4': (0, 128), 'heart': (128, 128), 'seal': (256, 128), 'seal_done': (384, 128),
    'scar': (0, 256), 'impact': (128, 256), 'gaze': (256, 256), 'meteor': (384, 256),
    'spoke': (0, 384),
}
# Solid swatches on the row below the spoke strip: name -> (index, colour, alpha, pulses)
SKILL_SWATCHES = {
    'pillar': (0, '#ffd25a', 110, False), 'post': (1, '#ff4a78', 235, True), 'shard': (2, '#fff4ff', 230, False),
    'star': (3, '#fff0a0', 255, True), 'violet': (4, '#b46eff', 230, True), 'mint': (5, '#7affc0', 220, False),
    'gaze_core': (6, '#bff4ff', 240, True), 'ember': (7, '#ff8a3a', 240, True),
    'cyan': (8, '#5ff0ff', 235, True), 'magenta': (9, '#ff5fd8', 235, True), 'white': (10, '#ffffff', 255, False),
    'approach': (11, '#ffe680', 245, True), 'fill': (12, '#ff6a8a', 200, True), 'nova_core': (13, '#fff2a8', 255, True),
    'supernova': (14, '#ffb347', 210, True), 'fragment': (15, '#bdf7ff', 250, True), 'deep': (16, '#7a3cff', 220, False),
    'cyan_glass': (17, '#5ff0ff', 120, False), 'magenta_glass': (18, '#ff5fd8', 120, False),
}
SWATCH_ROW = 384
BAND_RATIO = {'band_1': 8 / 16, 'band_2': 16 / 24, 'band_3': 24 / 32, 'band_4': 32 / 40.5, 'heart': 9 / 15}


@dataclass
class SkillPart:
    lo: tuple
    hi: tuple
    region: str = ''          # decal region (up/down faces only)
    swatch: str = ''          # solid swatch for 3D parts
    group: str = 'decal'
    faces: tuple = FACES
    yaw: float = 0.0


def decal(region, y=8.02):
    return SkillPart((0, y, 0), (16, y + 0.04, 16), region=region, faces=('up', 'down'))


def posts(radius_units, count, height, swatch, group='posts'):
    """Short upright posts on a rim; Bedrock pulses their height, Java pulses their texture."""
    parts = []
    for i in range(count):
        angle = (i + 0.5) * 2 * math.pi / count
        x, z = 8 + math.cos(angle) * radius_units, 8 + math.sin(angle) * radius_units
        parts.append(SkillPart((x - 0.12, 8.02, z - 0.12), (x + 0.12, 8.02 + height, z + 0.12),
                               swatch=swatch, group=group))
    return parts


def ring_wall(radius, thick, y0, y1, swatch, group, segments=16):
    """Vertical ring made of yawed segments (legal Java angles)."""
    length = 2 * radius * math.tan(math.pi / segments) * 1.06
    parts = []
    for k in range(segments):
        parts.append(SkillPart((8 + radius - thick / 2, y0, 8 - length / 2), (8 + radius + thick / 2, y1, 8 + length / 2),
                               swatch=swatch, group=group, yaw=k * 360.0 / segments))
    return parts


def orb(size, core, accent, group, cy=8.0):
    """Star bullet: an octagonal glowing core with rounded caps and four thin rays."""
    c, r, cap, ray, thin = size * 0.30, size * 0.28, size * 0.2, size * 0.5, size * 0.04
    return [
        SkillPart((8 - c, cy - c * 0.8, 8 - c), (8 + c, cy + c * 0.8, 8 + c), swatch=core, group=group),
        SkillPart((8 - r, cy - r * 0.8, 8 - r), (8 + r, cy + r * 0.8, 8 + r), swatch=accent, group=group, yaw=45),
        SkillPart((8 - cap, cy - c * 1.25, 8 - cap), (8 + cap, cy + c * 1.25, 8 + cap), swatch=core, group=group),
        SkillPart((8 - ray, cy - thin, 8 - thin), (8 + ray, cy + thin, 8 + thin), swatch='white', group=group),
        SkillPart((8 - thin, cy - thin, 8 - ray), (8 + thin, cy + thin, 8 + ray), swatch='white', group=group),
        SkillPart((8 - ray * 0.7, cy - thin, 8 - thin), (8 + ray * 0.7, cy + thin, 8 + thin), swatch='white', group=group, yaw=45),
        SkillPart((8 - thin, cy - thin, 8 - ray * 0.7), (8 + thin, cy + thin, 8 + ray * 0.7), swatch='white', group=group, yaw=45),
    ]


def anchored(lo_z, hi_z, edition):
    """Java bars are centred (the display anchors them by translation); Bedrock bars run forward (-Z)."""
    return (lo_z, hi_z) if edition == 'java' else (lo_z - 8, hi_z - 8)


def skill_parts(style, edition='java'):
    parts = []
    if style == 'disc':
        parts.append(decal('disc'))
        parts += posts(7.7, 16, 6.0, 'post')
    elif style.startswith('band_') or style == 'heart':
        parts.append(decal(style))
        swatch = 'violet' if style == 'heart' else 'post'
        parts += posts(7.7, 24, 5.0, swatch)
        parts += posts(7.7 * BAND_RATIO[style], 16, 5.0, swatch, group='posts_inner')
    elif style == 'spoke':
        parts.append(decal('spoke'))
        parts.append(SkillPart((7.85, 8.02, 0.2), (8.15, 16.02, 15.8), swatch='post', group='blade'))
    elif style in ('seal', 'seal_done'):
        parts.append(decal(style))
        swatch = 'pillar' if style == 'seal' else 'mint'
        width = 5.6 if style == 'seal' else 4.0
        lo, hi = 8 - width / 2, 8 + width / 2
        top = 24.0 if style == 'seal' else 16.0
        parts.append(SkillPart((lo, 8.04, lo), (hi, top, hi), swatch=swatch, group='pillar',
                               faces=('north', 'south', 'west', 'east')))
    elif style == 'scar':
        parts.append(decal('scar'))
        parts += posts(6.0, 8, 4.0, 'violet')
    elif style == 'impact':
        parts.append(SkillPart((0, 8.03, 0), (16, 8.07, 16), region='impact', faces=('up', 'down'), group='burst'))
        for x0, z0, x1, z1 in ((1.5, 7.85, 14.5, 8.15), (7.85, 1.5, 8.15, 14.5)):
            parts.append(SkillPart((x0, 8.04, z0), (x1, 16.04, z1), swatch='shard', group='burst'))
    elif style == 'gaze':
        parts.append(SkillPart((0, 8.0, 7.98), (16, 24.0, 8.02), region='gaze', faces=('north', 'south'), group='eye'))
        parts.append(SkillPart((6.5, 14.5, 7.6), (9.5, 17.5, 8.4), swatch='gaze_core', group='iris'))
    elif style == 'meteor':
        parts.append(decal('meteor'))
    elif style == 'approach':
        parts += ring_wall(7.85, 0.3, 8.0, 24.0, 'approach', 'ring')
    elif style == 'fill_bar':
        z0, z1 = anchored(0.0, 16.0, edition)
        parts.append(SkillPart((0, 8.0, z0), (16, 9.0, z1), swatch='fill', group='bar'))
        parts.append(SkillPart((6, 8.0, z0), (10, 9.4, z1), swatch='white', group='bar'))
    elif style == 'bullet':
        parts += orb(16.0, 'nova_core', 'approach', 'orb')
    elif style in ('ring_low', 'ring_high'):
        swatch = 'cyan' if style == 'ring_low' else 'magenta'
        parts += ring_wall(7.9, 0.22, 8.0, 24.0, swatch + '_glass', 'ring')
        parts += ring_wall(7.9, 0.3, 22.4, 24.0, swatch, 'ring')
        parts += ring_wall(7.9, 0.3, 8.0, 9.2, swatch, 'ring')
    elif style in ('beam_low', 'beam_high'):
        swatch = 'cyan' if style == 'beam_low' else 'magenta'
        z0, z1 = anchored(0.0, 16.0, edition)
        parts.append(SkillPart((0, 8.0, z0), (16, 24.0, z1), swatch=swatch + '_glass', group='beam'))
        parts.append(SkillPart((5, 11.0, z0), (11, 21.0, z1), swatch='white', group='beam'))
        parts.append(SkillPart((0, 23.0, z0), (16, 24.0, z1), swatch=swatch, group='beam'))
    elif style == 'fragment':
        parts.append(SkillPart((4, 8.03, 4), (12, 8.07, 12), swatch='fragment', faces=('up', 'down'), group='decal'))
        parts.append(SkillPart((6.2, 14.2, 6.2), (9.8, 21.8, 9.8), swatch='fragment', group='gem', yaw=45))
        parts.append(SkillPart((6.8, 12.0, 6.8), (9.2, 24.0, 9.2), swatch='white', group='gem'))
        parts.append(SkillPart((5.0, 17.2, 5.0), (11.0, 18.8, 11.0), swatch='approach', group='gem'))
    elif style == 'star':
        parts += orb(12.0, 'star', 'ember', 'fall')
        parts.append(SkillPart((7.3, 13.0, 7.3), (8.7, 32.0, 8.7), swatch='ember', group='fall'))
    elif style == 'supernova':
        # A small blazing core inside an octagonal corona, with long rays in eight directions and up/down.
        for size, height, swatch, yaw in ((6.0, 6.0, 'nova_core', 0.0), (5.4, 7.6, 'supernova', 45.0),
                                          (7.6, 4.4, 'ember', 0.0)):
            parts.append(SkillPart((8 - size / 2, 8 - height / 2, 8 - size / 2), (8 + size / 2, 8 + height / 2, 8 + size / 2),
                                   swatch=swatch, group='corona', yaw=yaw))
        for yaw in (0.0, 45.0):
            parts.append(SkillPart((0, 7.3, 7.3), (16, 8.7, 8.7), swatch='nova_core', group='corona', yaw=yaw))
            parts.append(SkillPart((7.3, 7.3, 0), (8.7, 8.7, 16), swatch='nova_core', group='corona', yaw=yaw))
            parts.append(SkillPart((1.5, 7.7, 7.7), (14.5, 8.3, 8.3), swatch='white', group='corona', yaw=yaw + 22.5))
        parts.append(SkillPart((7.3, 0, 7.3), (8.7, 16, 8.7), swatch='nova_core', group='corona'))
    elif style == 'nova':
        parts += orb(16.0, 'nova_core', 'approach', 'orb')
    return parts


# ---------------------------------------------------------------- textures
def hexrgb(value, alpha=255):
    value = value.lstrip('#')
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (alpha,)


def scaled(color, factor):
    return tuple(min(255, int(round(c * factor))) for c in color[:3]) + tuple(color[3:])


def body_texture(phase=0.0):
    atlas = Image.new('RGBA', (BODY_TEX, BODY_TEX), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    glow = 0.8 + 0.2 * math.cos(2 * math.pi * phase)
    for name, (index, base, shade, light) in BODY_SWATCHES.items():
        factor = glow if name in BODY_GLOW else 1.0
        x, y = (index % 8) * 8, (index // 8) * 8
        draw.rectangle((x, y, x + 7, y + 7), fill=scaled(hexrgb(base), factor))
        draw.line((x, y + 7, x + 7, y + 7), fill=scaled(hexrgb(shade), factor))
        draw.line((x + 7, y, x + 7, y + 7), fill=scaled(hexrgb(shade), factor))
        draw.line((x, y, x + 6, y), fill=scaled(hexrgb(light), factor))
        draw.rectangle((x + 2, y + 2, x + 3, y + 3), fill=scaled(hexrgb(light), factor))
        draw.point((x + 5, y + 4), fill=scaled(hexrgb(shade), factor))
        draw.point((x + 4, y + 5), fill=scaled(hexrgb(shade), factor))
    return atlas


def ring_band(px_out, ox, oy, size, inner_ratio, fill, rim, accent, dashed=True, phase=0.0, glow=1.0):
    c = (size - 1) / 2
    outer = c - 1
    inner = outer * inner_ratio
    rim = scaled(rim, glow)
    accent = scaled(accent, glow)
    for py in range(size):
        for px in range(size):
            d = math.hypot(px - c, py - c)
            if inner <= d <= outer:
                color = fill
                if d >= outer - 3 or (inner > 0 and d <= inner + 3):
                    color = rim
                elif dashed and abs(d - (inner + outer) / 2) < 1.2:
                    angle = math.atan2(py - c, px - c)
                    if int((angle + math.pi) / (2 * math.pi) * 48 + phase * 2) % 2 == 0:
                        color = accent
                px_out[ox + px, oy + py] = color


def skill_texture(phase=0.0):
    atlas = Image.new('RGBA', (SKILL_TEX, SKILL_TEX), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    pix = atlas.load()
    glow = 0.82 + 0.18 * math.cos(2 * math.pi * phase)
    danger = ((200, 40, 90, 92), (255, 85, 125, 238), (255, 175, 205, 190))
    ox, oy = REGIONS['disc']
    ring_band(pix, ox, oy, 128, 0.0, *danger, phase=phase, glow=glow)
    for k in range(8):
        angle = k * math.pi / 4 + phase * math.pi / 4
        for r in range(14, 50):
            pix[int(ox + 63.5 + math.cos(angle) * r), int(oy + 63.5 + math.sin(angle) * r)] = scaled(danger[2], glow)
    for name in ('band_1', 'band_2', 'band_3', 'band_4'):
        ox, oy = REGIONS[name]
        ring_band(pix, ox, oy, 128, BAND_RATIO[name], *danger, phase=phase, glow=glow)
    ox, oy = REGIONS['heart']
    ring_band(pix, ox, oy, 128, BAND_RATIO['heart'], (230, 60, 170, 92), (255, 120, 210, 240), (255, 200, 240, 200),
              phase=phase, glow=glow)
    ox, oy = REGIONS['seal']
    ring_band(pix, ox, oy, 128, 0.0, (255, 196, 80, 80), (255, 220, 110, 240), (255, 245, 190, 210), phase=-phase, glow=glow)
    for k in range(8):
        angle = k * math.pi / 4 - phase * math.pi / 4
        for r in range(6, 56):
            width = max(0, 4 - r // 14)
            for w in range(-width, width + 1):
                pix[int(ox + 63.5 + math.cos(angle) * r - math.sin(angle) * w * 0.5),
                    int(oy + 63.5 + math.sin(angle) * r + math.cos(angle) * w * 0.5)] = scaled((255, 235, 150, 220), glow)
    ox, oy = REGIONS['seal_done']
    ring_band(pix, ox, oy, 128, 0.0, (110, 255, 190, 70), (200, 255, 230, 235), (235, 255, 245, 200), phase=phase)
    ox, oy = REGIONS['scar']
    ring_band(pix, ox, oy, 128, 0.0, (120, 40, 200, 96), (190, 120, 255, 230), (220, 170, 255, 200), dashed=False, glow=glow)
    for k in range(5):
        a0 = k * 2 * math.pi / 5 - math.pi / 2 + phase * 2 * math.pi / 5
        a1 = (k + 2) * 2 * math.pi / 5 - math.pi / 2 + phase * 2 * math.pi / 5
        draw.line((ox + 63.5 + math.cos(a0) * 48, oy + 63.5 + math.sin(a0) * 48,
                   ox + 63.5 + math.cos(a1) * 48, oy + 63.5 + math.sin(a1) * 48), fill=scaled((225, 175, 255, 235), glow), width=3)
    ox, oy = REGIONS['impact']
    ring_band(pix, ox, oy, 128, 0.62, (255, 230, 255, 120), (255, 255, 255, 245), (255, 200, 240, 230), dashed=False)
    for k in range(12):
        angle = k * math.pi / 6 + phase * math.pi / 6
        draw.line((ox + 63.5 + math.cos(angle) * 12, oy + 63.5 + math.sin(angle) * 12,
                   ox + 63.5 + math.cos(angle) * 36, oy + 63.5 + math.sin(angle) * 36), fill=(255, 240, 255, 200), width=2)
    ox, oy = REGIONS['gaze']
    iris = 1.0 + 0.12 * math.sin(2 * math.pi * phase)
    for py in range(128):
        for px in range(128):
            nx, ny = (px - 63.5) / 60, (py - 63.5) / 34
            if abs(ny) <= (1 - nx * nx) and abs(nx) <= 1:
                d = math.hypot(px - 63.5, py - 63.5) / iris
                color = (180, 240, 255, 230)
                if d < 30:
                    color = (130, 70, 230, 240)
                if d < 16:
                    color = (30, 12, 60, 255)
                if d < 5:
                    color = (255, 255, 255, 255)
                if abs(ny) > (1 - nx * nx) - 0.12:
                    color = scaled((90, 200, 255, 250), glow)
                pix[ox + px, oy + py] = color
    ox, oy = REGIONS['meteor']
    ring_band(pix, ox, oy, 128, 0.0, (255, 120, 40, 90), (255, 160, 60, 240), (255, 220, 160, 200), phase=phase, glow=glow)
    draw.line((ox + 20, oy + 63.5, ox + 107, oy + 63.5), fill=(255, 230, 180, 220), width=3)
    draw.line((ox + 63.5, oy + 20, ox + 63.5, oy + 107), fill=(255, 230, 180, 220), width=3)
    ox, oy = REGIONS['spoke']
    for py in range(128):
        for px in range(128):
            d = abs(px - 63.5) / 63.5
            if d > 1:
                continue
            core = 1 - d
            color = (255, int(90 + 150 * core), int(90 + 60 * core), int(110 + 130 * core))
            if d > 0.9:
                color = scaled((255, 85, 125, 240), glow)
            if d < 0.12 and ((py + int(phase * 16)) // 8) % 2 == 0:
                color = (255, 245, 210, 250)
            pix[ox + px, oy + py] = color
    for name, (index, value, alpha, pulses) in SKILL_SWATCHES.items():
        x, y = 128 + index * 16, SWATCH_ROW
        base = hexrgb(value, alpha)
        if pulses:
            base = scaled(base, 0.86 + 0.14 * math.cos(2 * math.pi * phase))
        draw.rectangle((x, y, x + 15, y + 15), fill=base)
        draw.rectangle((x + 3, y + 3, x + 6, y + 6), fill=(255, 255, 255, min(255, alpha + 20)))
    return atlas


def animated(frames):
    """Stack frames vertically for a Java animated texture."""
    width, height = frames[0].size
    strip = Image.new('RGBA', (width, height * len(frames)), (0, 0, 0, 0))
    for i, frame in enumerate(frames):
        strip.paste(frame, (0, i * height))
    return strip


# ---------------------------------------------------------------- Java export
def swatch_uv(name, tex=BODY_TEX):
    index = BODY_SWATCHES[name][0]
    unit = 16 / tex
    x, y = (index % 8) * 8, (index // 8) * 8
    return [round(x * unit, 4), round(y * unit, 4), round((x + 8) * unit, 4), round((y + 8) * unit, 4)]


def rotate90(lo, hi, quarter):
    """Rotate a box by quarter*90 degrees about the model centre's Y axis (exact, axis-aligned)."""
    corners = [(lo[0], lo[2]), (hi[0], hi[2])]
    out = []
    for x, z in corners:
        dx, dz = x - 8, z - 8
        for _ in range(quarter % 4):
            dx, dz = -dz, dx
        out.append((8 + dx, 8 + dz))
    xs, zs = [c[0] for c in out], [c[1] for c in out]
    return (min(xs), lo[1], min(zs)), (max(xs), hi[1], max(zs))


def r4(values):
    return [round(v, 4) for v in values]


def element(lo, hi, yaw, faces_uv, glow, tint=False):
    quarter = int(round(yaw / 90.0))
    phi = yaw - quarter * 90.0
    lo, hi = rotate90(lo, hi, quarter)
    faces = {}
    for face, uv in faces_uv.items():
        entry = {'uv': uv, 'texture': '#0'}
        if tint:
            entry['tintindex'] = 0
        faces[face] = entry
    result = {'from': r4(lo), 'to': r4(hi), 'faces': faces}
    if abs(phi) > 1e-6:
        result['rotation'] = {'angle': round(phi, 4), 'axis': 'y', 'origin': [8, 8, 8]}
    if glow:
        result['shade'] = False
        result['light_emission'] = 15
    return result


def java_model(boxes, texture):
    elements = []
    for box in boxes:
        uv = swatch_uv(box.swatch)
        elements.append(element(box.lo, box.hi, box.yaw, {face: uv for face in box.faces}, box.glow, box.tint))
    return {
        'credit': 'AstralRecord generate-nyxeron-assets.py',
        'ambientocclusion': False,
        'textures': {'0': texture, 'particle': texture},
        'elements': elements,
    }


def skill_uv(part):
    unit = 16 / SKILL_TEX
    if part.region:
        x, y = REGIONS[part.region]
        return [x * unit, y * unit, (x + 128) * unit, (y + 128) * unit]
    index = SKILL_SWATCHES[part.swatch][0]
    x, y = 128 + index * 16, SWATCH_ROW
    return [x * unit + 0.125, y * unit + 0.125, (x + 16) * unit - 0.125, (y + 16) * unit - 0.125]


def skill_java_model(style):
    elements = []
    for part in skill_parts(style, 'java'):
        uv = r4(skill_uv(part))
        elements.append(element(part.lo, part.hi, part.yaw, {face: uv for face in part.faces}, True))
    return {
        'credit': 'AstralRecord generate-nyxeron-assets.py',
        'ambientocclusion': False,
        'textures': {'0': 'astralrecord:item/nyxeron_skill', 'particle': 'astralrecord:item/nyxeron_skill'},
        'elements': elements,
    }


def item_dispatch(name, frames, tinted):
    def model(path):
        entry = {'type': 'minecraft:model', 'model': 'astralrecord:item/' + path}
        if tinted:
            entry['tints'] = [{'type': 'minecraft:custom_model_data', 'index': 0, 'default': 16777215}]
        return entry
    if not frames:
        return {'model': model(name)}
    return {'model': {
        'type': 'minecraft:range_dispatch', 'property': 'minecraft:custom_model_data', 'index': 0,
        'fallback': model(frames[0]),
        'entries': [{'threshold': i, 'model': model(path)} for i, path in enumerate(frames) if i > 0],
    }}


# ---------------------------------------------------------------- Bedrock export
def bedrock_uv(item, skill=False):
    if skill:
        if item.region:
            x, y = REGIONS[item.region]
            return [x, y], [128, 128]
        index = SKILL_SWATCHES[item.swatch][0]
        return [128 + index * 16 + 2, SWATCH_ROW + 2], [12, 12]
    index = BODY_SWATCHES[item.swatch][0]
    return [(index % 8) * 8, (index // 8) * 8], [8, 8]


def bedrock_cube(lo, hi, scale, y_offset, uv, size, faces, yaw=0.0, pivot_y=None):
    """Java box (units, centre 8) -> Bedrock cube in pixels with X mirrored."""
    x0, x1 = -(hi[0] - 8) * scale, -(lo[0] - 8) * scale
    result = {
        'origin': r4([x0, (lo[1] - 8) * scale + y_offset, (lo[2] - 8) * scale]),
        'size': r4([x1 - x0, (hi[1] - lo[1]) * scale, (hi[2] - lo[2]) * scale]),
        'uv': {face: {'uv': uv, 'uv_size': size} for face in faces},
    }
    if abs(yaw) > 1e-6:
        result['pivot'] = r4([0, pivot_y if pivot_y is not None else y_offset, 0])
        result['rotation'] = [0, round(-yaw, 4), 0]
    return result


def body_cubes(boxes, scale, y_offset):
    cubes = []
    for box in boxes:
        uv, size = bedrock_uv(box)
        cubes.append(bedrock_cube(box.lo, box.hi, scale, y_offset, uv, size, box.faces, yaw=box.yaw, pivot_y=y_offset))
    return cubes


def body_geometry():
    h = CORE_HEIGHT_PX
    bones = [
        {'name': 'root', 'pivot': [0, 0, 0]},
        {'name': 'body', 'parent': 'root', 'pivot': [0, h, 0]},
        {'name': 'core', 'parent': 'body', 'pivot': [0, h, 0]},
    ]
    groups = {}
    seen = set()
    for pose in CORE_POSES:
        for box in core_boxes(pose):
            name = box.group
            if pose == 'cast' and name == 'glow_eye':
                name = 'glow_eye_cast'
            elif pose != 'idle' and name in ('glow_eye', 'crown', 'glow_crown'):
                continue
            key = (name, box.lo, box.hi, box.yaw)
            if key in seen:
                continue
            seen.add(key)
            groups.setdefault(name, []).append(box)
    for name, boxes in groups.items():
        bones.append({'name': name, 'parent': 'core', 'pivot': [0, h, 0], 'cubes': body_cubes(boxes, CORE_SCALE, h)})
    # Petals hang from a shared bone; each is pushed out along its own yaw and grows from the centre.
    bones.append({'name': 'petals', 'parent': 'body', 'pivot': [0, h, 0]})
    for k in range(4):
        moved = [Box((b.lo[0], b.lo[1], b.lo[2] + PETAL_OFFSET), (b.hi[0], b.hi[1], b.hi[2] + PETAL_OFFSET), b.swatch, b.group, b.glow,
                     b.tint, 0.0, b.faces) for b in petal_boxes()]
        bones.append({'name': 'petal_%d' % k, 'parent': 'petals', 'pivot': [0, h, 0], 'rotation': [0, k * 90, 0],
                      'cubes': body_cubes([b for b in moved if not b.glow], CORE_SCALE, h)})
        bones.append({'name': 'glow_petal_%d' % k, 'parent': 'petal_%d' % k, 'pivot': [0, h, 0],
                      'cubes': body_cubes([b for b in moved if b.glow], CORE_SCALE, h)})
    bones.append({'name': 'cage', 'parent': 'body', 'pivot': [0, h, 0], 'cubes': body_cubes(cage_boxes(), CORE_SCALE, h)})
    bones.append({'name': 'motes', 'parent': 'body', 'pivot': [0, h, 0]})
    mote_scale = CORE_SCALE * 0.6
    for k in range(4):
        # Motes orbit 26 px (about 1.6 blocks) from the core, matching the Java mote displays.
        angle = math.radians(k * 90)
        dx, dz = math.cos(angle) * 26.0 / mote_scale, math.sin(angle) * 26.0 / mote_scale
        moved = [Box((b.lo[0] + dx, b.lo[1], b.lo[2] + dz), (b.hi[0] + dx, b.hi[1], b.hi[2] + dz), b.swatch, b.group,
                     b.glow, b.tint, b.yaw, b.faces) for b in mote_boxes()]
        bones.append({'name': 'mote_%d' % k, 'parent': 'motes', 'pivot': [0, h, 0], 'cubes': body_cubes(moved, mote_scale, h)})
    for kind in RINGS:
        prec, tilt, spin = 'ring_%s_prec' % kind, 'ring_%s_tilt' % kind, 'ring_%s_spin' % kind
        bones += [
            {'name': prec, 'parent': 'body', 'pivot': [0, h, 0]},
            {'name': tilt, 'parent': prec, 'pivot': [0, h, 0]},
            {'name': spin, 'parent': tilt, 'pivot': [0, h, 0]},
        ]
        groups = {}
        for box in ring_boxes(kind):
            groups.setdefault(box.group, []).append(box)
        for name, boxes in groups.items():
            bones.append({'name': name, 'parent': spin, 'pivot': [0, h, 0], 'cubes': body_cubes(boxes, RING_SCALE, h)})
    return {
        'format_version': '1.12.0',
        'minecraft:geometry': [{
            'description': {
                'identifier': 'geometry.astralrecord.nyxeron', 'texture_width': BODY_TEX, 'texture_height': BODY_TEX,
                'visible_bounds_width': 7, 'visible_bounds_height': 7, 'visible_bounds_offset': [0, 2.4, 0],
            },
            'bones': bones,
        }],
    }


def skill_geometry(style):
    bones = [{'name': 'root', 'pivot': [0, 0, 0]}]
    if style == 'nova':
        for i in range(NOVA_BULLETS):
            cubes = []
            for part in skill_parts('nova', 'bedrock'):
                uv, size = bedrock_uv(part, skill=True)
                cubes.append(bedrock_cube(part.lo, part.hi, 1.0, 0.0, uv, size, part.faces, yaw=part.yaw))
            bones.append({'name': 'b%02d' % i, 'parent': 'root', 'pivot': [0, 0, 0], 'cubes': cubes})
    else:
        groups = {}
        for part in skill_parts(style, 'bedrock'):
            groups.setdefault(part.group, []).append(part)
        for name, parts in groups.items():
            cubes = []
            for part in parts:
                uv, size = bedrock_uv(part, skill=True)
                cubes.append(bedrock_cube(part.lo, part.hi, 1.0, 0.0, uv, size, part.faces, yaw=part.yaw))
            bone = {'name': name, 'parent': 'root', 'pivot': [0, 0, 0], 'cubes': cubes}
            if name == 'iris':
                bone['pivot'] = [0, 8, 0]
            if name == 'gem':
                bone['pivot'] = [0, 10, 0]
            bones.append(bone)
    return {
        'description': {
            'identifier': 'geometry.astralrecord.nyxeron_' + style, 'texture_width': SKILL_TEX,
            'texture_height': SKILL_TEX, 'visible_bounds_width': 100, 'visible_bounds_height': 40,
            'visible_bounds_offset': [0, 0, 0],
        },
        'bones': bones,
    }


def skill_geometries():
    return {'format_version': '1.12.0', 'minecraft:geometry': [skill_geometry(style) for style in STYLES]}


RATES = {'a': (18.0, 30.0), 'b': (-12.0, 44.0), 'c': (28.0, -60.0)}
TILTS = {  # default, attack, guard, exposed
    'a': (24, 0, 90, 62), 'b': (72, 0, 90, 28), 'c': (-48, 0, 0, 84),
}
RING_LETTER = {'outer': 'a', 'mid': 'b', 'inner': 'c'}


def body_entity():
    init = ['variable.body_yaw_ready = 0;', 'variable.ring_scale = 1.0;', 'variable.lift = 0.0;',
            'variable.petal = 0.0;', 'variable.cage = 0.0;', 'variable.mote_angle = 0.0;', 'variable.mote_r = 1.0;']
    for letter, offset in (('a', 0), ('b', 90), ('c', 45)):
        init.append('variable.prec_%s = %d;' % (letter, offset))
        init.append('variable.spin_%s = 0;' % letter)
        init.append('variable.tilt_%s = %d;' % (letter, TILTS[letter][0]))
    pre = [
        "variable.body_yaw_target = query.has_property('astralrecord:body_yaw_tenths') ? query.property('astralrecord:body_yaw_tenths') : -1;",
        "variable.body_yaw = variable.body_yaw_target < 0 ? query.body_y_rotation : (variable.body_yaw_ready ? math.lerprotate(variable.body_yaw, variable.body_yaw_target / 10.0, math.min(1, query.delta_time * 12)) : variable.body_yaw_target / 10.0);",
        'variable.body_yaw_ready = variable.body_yaw_target >= 0;',
        "variable.motion = query.has_property('astralrecord:motion') ? query.property('astralrecord:motion') : 0;",
        "variable.invulnerable = query.has_property('astralrecord:invulnerable') ? query.property('astralrecord:invulnerable') : 0;",
        'variable.rate = variable.motion == 1 ? 2.5 : (variable.motion == 2 ? 3.2 : (variable.motion == 3 ? 0.35 : (variable.motion == 4 ? 0.4 : 1.0)));',
        'variable.step = math.clamp(query.delta_time, 0.0, 0.1);',
        'variable.ease = math.min(1.0, variable.step * 5.0);',
        'variable.snap = math.min(1.0, variable.step * 9.0);',
    ]
    for letter, (prec, spin) in RATES.items():
        default, attack, guard, exposed = TILTS[letter]
        pre.append('variable.prec_%s = math.mod(variable.prec_%s + variable.step * %.1f * variable.rate, 360.0);'
                   % (letter, letter, prec))
        pre.append('variable.spin_%s = math.mod(variable.spin_%s + variable.step * %.1f * variable.rate, 360.0);'
                   % (letter, letter, spin))
        pre.append('variable.tilt_%s = math.lerp(variable.tilt_%s, variable.motion == 2 ? %d : (variable.motion == 3 ? %d : (variable.motion == 4 ? %d : %d)), variable.ease);'
                   % (letter, letter, attack, guard, exposed, default))
    pre += [
        'variable.ring_scale = math.lerp(variable.ring_scale, variable.motion == 1 ? 1.1 : (variable.motion == 2 ? 1.18 : (variable.motion == 3 ? 0.72 : (variable.motion == 4 ? 1.04 : 1.0))), variable.ease);',
        'variable.lift = math.lerp(variable.lift, variable.motion == 4 ? -11.2 : (variable.motion == 1 ? 4.0 : 0.0), variable.ease);',
        'variable.petal = math.lerp(variable.petal, variable.motion == 2 ? 1.0 : (variable.motion == 1 ? 0.45 : 0.0), variable.snap);',
        'variable.cage = math.lerp(variable.cage, variable.invulnerable ? 1.0 : 0.0, variable.ease);',
        'variable.mote_angle = math.mod(variable.mote_angle + variable.step * 70.0 * variable.rate, 360.0);',
        'variable.mote_r = math.lerp(variable.mote_r, variable.motion == 1 ? 0.7 : (variable.motion == 2 ? 1.45 : (variable.motion == 3 ? 0.55 : 1.0)), variable.ease);',
    ]
    return {
        'format_version': '1.10.0',
        'minecraft:client_entity': {'description': {
            'identifier': 'astralrecord:nyxeron',
            'materials': {'default': 'entity_alphatest', 'glow': 'entity_emissive_alpha'},
            'textures': {'default': 'textures/entity/nyxeron'},
            'geometry': {'default': 'geometry.astralrecord.nyxeron'},
            'animations': {
                'rings': 'animation.astralrecord.nyxeron.rings',
                'idle': 'animation.astralrecord.nyxeron.idle',
                'cast': 'animation.astralrecord.nyxeron.cast',
                'attack': 'animation.astralrecord.nyxeron.attack',
                'guard': 'animation.astralrecord.nyxeron.guard',
                'exposed': 'animation.astralrecord.nyxeron.exposed',
                'motion': 'controller.animation.astralrecord.nyxeron',
            },
            'scripts': {'initialize': init, 'pre_animation': pre, 'animate': ['rings', 'motion']},
            'render_controllers': ['controller.render.astralrecord.nyxeron'],
            'enable_attachables': False,
        }},
    }


def body_animations():
    rings = {
        'root': {'rotation': [0, 'variable.body_yaw - query.body_y_rotation', 0]},
        'body': {'position': [0, 'variable.lift + math.sin(query.life_time * 120.0) * 0.6', 0]},
        'core': {'rotation': [0, 'variable.spin_a * 0.3', 0]},
        'petals': {'rotation': [0, 'variable.spin_a * 0.3', 0], 'scale': ['math.max(0.001, variable.petal)'] * 3},
        'cage': {'rotation': [0, 'query.life_time * 50.0', 'math.sin(query.life_time * 90.0) * 8.0'],
                 'scale': ['math.max(0.001, variable.cage)'] * 3},
        'motes': {'rotation': [0, 'variable.mote_angle', 0], 'scale': ['variable.mote_r', 1, 'variable.mote_r']},
    }
    for k in range(4):
        rings['mote_%d' % k] = {'position': [0, 'math.sin(query.life_time * 160.0 + %d) * 1.6' % (k * 90), 0]}
    for kind, letter in RING_LETTER.items():
        rings['ring_%s_prec' % kind] = {'rotation': [0, 'variable.prec_%s' % letter, 0],
                                        'scale': ['variable.ring_scale'] * 3}
        rings['ring_%s_tilt' % kind] = {'rotation': ['variable.tilt_%s' % letter, 0, 0]}
        rings['ring_%s_spin' % kind] = {'rotation': [0, 'variable.spin_%s' % letter, 0]}
    beat = '1.0 + 0.06 * math.max(0, math.sin(query.life_time * 400.0))'
    return {'format_version': '1.8.0', 'animations': {
        'animation.astralrecord.nyxeron.rings': {'loop': True, 'bones': rings},
        'animation.astralrecord.nyxeron.idle': {'loop': True, 'bones': {'core': {'scale': [beat] * 3}}},
        'animation.astralrecord.nyxeron.cast': {'loop': True, 'bones': {
            'core': {'scale': ['1.06 + 0.08 * math.max(0, math.sin(query.life_time * 720.0))'] * 3},
            'glow_eye': {'scale': [1, 1.35, 1.35]}, 'crown': {'scale': [1, 1.4, 1]}}},
        'animation.astralrecord.nyxeron.attack': {'loop': True, 'bones': {
            'core': {'scale': 1.12}, 'glow_eye': {'scale': [1, 1.15, 1.15]}, 'crown': {'scale': [1, 1.7, 1]}}},
        'animation.astralrecord.nyxeron.guard': {'loop': True, 'bones': {
            'core': {'scale': 0.94}, 'glow_eye': {'scale': [1, 0.8, 0.8]}}},
        'animation.astralrecord.nyxeron.exposed': {'loop': True, 'bones': {
            'core': {'position': ['math.sin(query.life_time * 1500.0) * 0.4', 0, 'math.cos(query.life_time * 1300.0) * 0.4'],
                     'scale': [beat] * 3},
            'shell_shard': {'rotation': [0, 'query.life_time * 40.0', 0]}}},
    }}


def body_controller():
    states = {}
    names = ('idle', 'cast', 'attack', 'guard', 'exposed')
    for name in names:
        states[name] = {
            'animations': [name],
            'transitions': [{other: 'variable.motion == %d' % j} for j, other in enumerate(names) if other != name],
            'blend_transition': 0.25,
        }
    return {'format_version': '1.10.0', 'animation_controllers': {
        'controller.animation.astralrecord.nyxeron': {'initial_state': 'idle', 'states': states}}}


def body_render_controller():
    return {'format_version': '1.8.0', 'render_controllers': {'controller.render.astralrecord.nyxeron': {
        'geometry': 'Geometry.default',
        'materials': [{'*': 'Material.default'}, {'glow_*': 'Material.glow'}, {'cage': 'Material.glow'},
                      {'mote_*': 'Material.glow'}],
        'textures': ['Texture.default'],
        'part_visibility': [
            {'*': True},
            {'shell': 'variable.motion != 4'}, {'glow_veins': 'variable.motion != 4'},
            {'glow_eye': 'variable.motion != 4 && variable.motion != 1'},
            {'glow_eye_cast': 'variable.motion == 1'},
            {'glow_exposed': 'variable.motion == 4'}, {'shell_shard': 'variable.motion == 4'},
        ],
    }}}


def style_id(name):
    return STYLES.index(name)


def skill_entity():
    s = {name: style_id(name) for name in STYLES}
    grow = 'variable.style == %d || variable.style == %d || variable.style == %d' % (s['approach'], s['ring_low'], s['ring_high'])
    unit = 'variable.style == %d || variable.style == %d || variable.style == %d' % (s['bullet'], s['star'], s['nova'])
    beam = 'variable.style == %d || variable.style == %d' % (s['beam_low'], s['beam_high'])
    pre = [
        "variable.style = query.property('astralrecord:style');",
        "variable.param = query.has_property('astralrecord:param') ? query.property('astralrecord:param') : 0;",
        "variable.w = query.property('astralrecord:width');",
        "variable.h = query.property('astralrecord:height');",
        "variable.l = query.property('astralrecord:length');",
        'variable.t = query.life_time;',
        'variable.dur = math.max(0.05, variable.param / 20.0);',
        'variable.k = math.clamp(variable.t / variable.dur, 0.0, 1.0);',
        'variable.pulse = math.mod(variable.t, 0.8) / 0.8;',
        'variable.breath = math.sin(180.0 * variable.pulse);',
        'variable.travel = math.clamp(variable.t / 0.3, 0.0, 1.0);',
        'variable.grow = math.lerp(variable.w, variable.l, variable.k);',
        'variable.supernova = variable.w * math.lerp(0.2, 1.0, variable.k) * (1.0 + 0.05 * variable.breath);',
        'variable.sx = (%s) ? variable.grow : ((%s) ? 1.0 : (variable.style == %d ? variable.supernova : variable.w));'
        % (grow, unit, s['supernova']),
        'variable.sy = (%s) ? 1.0 : (variable.style == %d ? variable.w : (variable.style == %d ? variable.supernova : variable.h));'
        % (unit, s['fragment'], s['supernova']),
        'variable.sz = (%s) ? variable.grow : ((%s) ? 1.0 : (variable.style == %d ? variable.l * math.max(0.02, variable.k) : (variable.style == %d ? variable.w : (variable.style == %d ? variable.supernova : variable.l))));'
        % (grow, unit, s['fill_bar'], s['fragment'], s['supernova']),
        # Beams rotate at (param - 2048) / 8 degrees per second between server snapshots.
        'variable.spin = (%s) ? (variable.param - 2048) / 8.0 * variable.t : (variable.style == %d ? variable.t * 40.0 : 0.0);'
        % (beam, s['supernova']),
        'variable.nova_r = variable.h + variable.l * variable.t;',
        'variable.nova_p = %d / math.max(1, math.mod(variable.param, 16));' % NOVA_BULLETS,
        'variable.nova_w = math.floor(variable.param / 16);',
    ]
    return {'format_version': '1.10.0', 'minecraft:client_entity': {'description': {
        'identifier': 'astralrecord:nyxeron_skill',
        'materials': {'default': 'entity_emissive_alpha'},
        'textures': {'default': 'textures/entity/nyxeron_skill'},
        'geometry': {style: 'geometry.astralrecord.nyxeron_' + style for style in STYLES},
        'animations': {'shape': 'animation.astralrecord.nyxeron_skill.shape'},
        'scripts': {'pre_animation': pre, 'animate': ['shape']},
        'render_controllers': ['controller.render.astralrecord.nyxeron_skill'],
    }}}


def skill_animation():
    bones = {
        'root': {'scale': ['variable.sx', 'variable.sy', 'variable.sz'], 'rotation': [0, 'variable.spin', 0]},
        'posts': {'scale': [1, '0.65 + 0.35 * variable.breath', 1]},
        'posts_inner': {'scale': [1, '0.65 + 0.35 * variable.breath', 1]},
        'blade': {'scale': [1, '0.55 + 0.45 * variable.breath', 1]},
        'pillar': {'scale': ['0.9 + 0.12 * variable.breath', 1, '0.9 + 0.12 * variable.breath']},
        'burst': {'scale': ['math.lerp(0.45, 1.0, variable.travel)', 'math.lerp(0.6, 1.0, variable.travel) * (1.0 - 0.8 * math.clamp((variable.t - 0.35) / 0.25, 0.0, 1.0))',
                            'math.lerp(0.45, 1.0, variable.travel)']},
        'iris': {'scale': ['0.8 + 0.3 * variable.breath'] * 3},
        'orb': {'position': [0, 0, '-16.0 * variable.l * variable.t'], 'rotation': [0, 'variable.t * 240.0', 0],
                'scale': ['variable.w'] * 3},
        'fall': {'position': [0, '16.0 * variable.h * (1.0 - variable.k)', 0], 'scale': ['variable.w'] * 3},
        'gem': {'position': [0, 'math.sin(variable.t * 200.0) * 1.2', 0], 'rotation': [0, 'variable.t * 120.0', 0]},
        'ring': {'scale': [1, '0.92 + 0.08 * variable.breath', 1]},
        'bar': {'scale': [1, '0.8 + 0.4 * variable.breath', 1]},
        'beam': {'scale': [1, '0.92 + 0.08 * variable.breath', 1]},
        'corona': {'rotation': [0, 'variable.t * -70.0', 0]},
    }
    for i in range(NOVA_BULLETS):
        angle = math.radians(i * 360.0 / NOVA_BULLETS)
        bones['b%02d' % i] = {
            'position': ['variable.nova_r * %.4f' % (16 * math.sin(angle)), 0, 'variable.nova_r * %.4f' % (-16 * math.cos(angle))],
            'rotation': [0, 'variable.t * 240.0', 0],
            'scale': ['variable.w'] * 3,
        }
    return {'format_version': '1.8.0', 'animations': {'animation.astralrecord.nyxeron_skill.shape': {
        'loop': True, 'bones': bones}}}


def skill_render_controller():
    visibility = [{'*': True}]
    for i in range(NOVA_BULLETS):
        # Gaps are centred on bullet 0 and every nova_p bullets; the pattern is symmetric about the forward axis.
        visibility.append({'b%02d' % i: 'math.mod(%d, variable.nova_p) > variable.nova_w && math.mod(%d, variable.nova_p) < variable.nova_p - variable.nova_w' % (i, i)})
    return {'format_version': '1.8.0', 'render_controllers': {'controller.render.astralrecord.nyxeron_skill': {
        'arrays': {'geometries': {'Array.shapes': ['Geometry.' + style for style in STYLES]}},
        'geometry': "Array.shapes[math.clamp(query.property('astralrecord:style'), 0, %d)]" % (len(STYLES) - 1),
        'materials': [{'*': 'Material.default'}],
        'textures': ['Texture.default'],
        'part_visibility': visibility,
        'ignore_lighting': True,
    }}}


# ---------------------------------------------------------------- sounds
SR = 44100


def seconds(duration):
    return np.arange(int(SR * duration)) / SR


def phase_of(freq):
    """Integrate an instantaneous frequency array into a phase."""
    return 2 * np.pi * np.cumsum(freq) / SR


def osc(freq, duration, shape='sine'):
    t = seconds(duration)
    f = np.broadcast_to(np.asarray(freq, dtype=np.float64), t.shape)
    ph = phase_of(f)
    if shape == 'sine':
        return np.sin(ph)
    if shape == 'tri':
        return 2 / np.pi * np.arcsin(np.sin(ph))
    if shape == 'saw':
        # Band-limited by summing a few harmonics.
        return sum(np.sin(ph * k) / k for k in range(1, 9)) * 0.6
    raise ValueError(shape)


def sweep(f0, f1, duration, curve=1.0):
    t = seconds(duration)
    x = (t / max(t[-1], 1e-9)) ** curve
    return f0 * (f1 / f0) ** x


def decay(duration, rate, attack=0.004):
    t = seconds(duration)
    env = np.exp(-t * rate)
    if attack > 0:
        env *= np.clip(t / attack, 0, 1)
    return env


def swell(duration, peak=0.7, release=0.25):
    t = seconds(duration)
    rise = np.clip(t / (duration * peak), 0, 1) ** 1.6
    fall = np.clip((duration - t) / (duration * release), 0, 1)
    return rise * fall


def noise(duration, seed):
    return np.random.default_rng(seed).uniform(-1, 1, int(SR * duration))


def lowpass(x, cutoff):
    """One-pole lowpass with a constant or per-sample cutoff in Hz."""
    cut = np.broadcast_to(np.asarray(cutoff, dtype=np.float64), x.shape)
    alpha = (1 - np.exp(-2 * np.pi * cut / SR)).tolist()
    out = [0.0] * len(x)
    y = 0.0
    for i, (v, a) in enumerate(zip(x.tolist(), alpha)):
        y += a * (v - y)
        out[i] = y
    return np.asarray(out)


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def bell(freq, duration, ratio=1.4, index=3.0, rate=3.0, index_rate=4.0):
    t = seconds(duration)
    mod = index * np.exp(-t * index_rate) * np.sin(2 * np.pi * freq * ratio * t)
    return np.sin(2 * np.pi * freq * t + mod) * decay(duration, rate)


def pad(base, duration, seed, cutoff=1400.0):
    voices = [osc(base * d, duration, 'saw') for d in (1.0, 1.005, 0.995, 1.5, 2.0)]
    return lowpass(sum(voices) / len(voices), cutoff) * swell(duration)


def comb_block(x, delay, gain):
    y = x.copy()
    for start in range(delay, len(y), delay):
        end = min(len(y), start + delay)
        y[start:end] += gain * y[start - delay:end - delay]
    return y


def allpass_block(x, delay, gain):
    y = np.zeros_like(x)
    y[:delay] = -gain * x[:delay]
    for start in range(delay, len(y), delay):
        end = min(len(y), start + delay)
        y[start:end] = -gain * x[start:end] + x[start - delay:end - delay] + gain * y[start - delay:end - delay]
    return y


def reverb(x, mix=0.3, tail=1.0):
    padded = np.concatenate([x, np.zeros(int(SR * tail))])
    wet = sum(comb_block(padded, d, g) for d, g in ((1557, 0.78), (1617, 0.77), (1491, 0.79), (1422, 0.8))) / 4
    for d, g in ((225, 0.5), (556, 0.5)):
        wet = allpass_block(wet, d, g)
    return padded * (1 - mix) + wet * mix


def mix(*layers):
    length = max(len(layer) for layer in layers)
    out = np.zeros(length)
    for layer in layers:
        out[:len(layer)] += layer
    return out


def at(offset, layer):
    return np.concatenate([np.zeros(int(SR * offset)), layer])


def boom(duration, f0, f1, seed, rate=4.0, crack=0.6):
    body = osc(sweep(f0, f1, duration, 0.5), duration) * decay(duration, rate, 0.002)
    hiss = lowpass(noise(duration, seed), sweep(5000, 300, duration, 0.4)) * decay(duration, rate * 1.6, 0.001) * crack
    return body + hiss


def whoosh(duration, f0, f1, seed, level=1.0):
    n = noise(duration, seed)
    band = lowpass(n, sweep(f0, f1, duration)) - lowpass(n, sweep(f0 * 0.25, f1 * 0.25, duration))
    return band * swell(duration, 0.55, 0.45) * 3.0 * level


def sparkle(duration, seed, count, low, high, rate=9.0):
    rng = np.random.default_rng(seed)
    out = np.zeros(int(SR * duration))
    for _ in range(count):
        start = rng.uniform(0, duration * 0.7)
        freq = rng.uniform(low, high)
        tone = bell(freq, min(0.5, duration - start), ratio=2.0, index=1.5, rate=rate)
        begin = int(SR * start)
        out[begin:begin + len(tone)] += tone * rng.uniform(0.3, 0.7)
    return out


def sound_designs():
    s = {}
    s['intro'] = reverb(mix(bell(55, 3.0, 1.41, 4, 1.1) * 0.9, bell(110, 3.0, 2.0, 2, 1.4) * 0.5,
                            pad(110, 3.0, 1, 900) * 0.45), 0.4, 1.2)
    s['phase'] = reverb(mix(bell(65.4, 2.6, 1.41, 5, 1.0), boom(1.2, 90, 35, 2, 3.0, 0.5) * 0.8,
                            pad(98, 2.6, 2, 700) * 0.35), 0.35, 1.0)
    s['cue_band'] = reverb(mix(osc(sweep(80, 165, 1.3), 1.3, 'tri') * swell(1.3, 0.8, 0.2) * 0.7,
                               at(0.9, bell(440, 0.6, 1.4, 2, 5) * 0.5)), 0.25)
    vib = sweep(220, 880, 1.2, 1.3) * (1 + 0.02 * np.sin(2 * np.pi * 9 * seconds(1.2)))
    s['cue_spoke'] = reverb(osc(vib, 1.2, 'saw') * swell(1.2, 0.85, 0.15) * 0.45, 0.2)
    thump = osc(sweep(70, 40, 0.25, 0.5), 0.25) * decay(0.25, 14, 0.003)
    s['cue_heart'] = reverb(mix(thump, at(0.28, thump * 0.8)), 0.2)
    s['cue_brand'] = reverb(mix(bell(880, 1.2, 2.0, 2, 3), bell(1318.5, 1.2, 2.0, 2, 3) * 0.7,
                                at(0.08, bell(1760, 1.1, 2.0, 1.5, 3.5) * 0.6), sparkle(1.2, 3, 8, 2000, 4200) * 0.4), 0.35)
    drone = osc(146.8, 1.6, 'saw') + osc(155.6, 1.6, 'saw')
    s['cue_gaze'] = reverb(lowpass(drone, sweep(300, 2400, 1.6)) * swell(1.6, 0.8, 0.2) * 0.5, 0.35)
    s['cue_meteor'] = reverb(mix(osc(sweep(2400, 500, 1.2, 0.8), 1.2) * swell(1.2, 0.3, 0.6) * 0.4,
                                 whoosh(1.2, 600, 3000, 4, 0.6)), 0.25)
    rev = whoosh(1.4, 300, 9000, 5, 0.9) * np.linspace(0.2, 1.0, int(SR * 1.4)) ** 2
    s['cue_combo'] = reverb(mix(rev, at(1.3, boom(0.6, 120, 40, 6, 6, 0.8))), 0.3)
    chord = sum(osc(f, 1.0, 'tri') for f in (523.3, 659.3, 784.0, 1046.5)) / 4
    s['cue_nova'] = reverb(chord * swell(1.0, 0.75, 0.25) * 0.7, 0.35)
    arp = np.zeros(int(SR * 1.2))
    for i, f in enumerate((392, 523.3, 659.3, 784, 1046.5, 784, 659.3, 523.3)):
        tone = bell(f, 0.4, 3.0, 1.2, 8) * 0.5
        begin = int(SR * i * 0.1)
        arp[begin:begin + len(tone)] += tone[:len(arp) - begin]
    s['cue_spiral'] = reverb(mix(arp, whoosh(1.2, 400, 2500, 7, 0.4)), 0.35)
    s['cue_shock'] = reverb(mix(boom(0.5, 80, 45, 8, 7, 0.4), osc(sweep(60, 120, 1.0), 1.0, 'tri') * swell(1.0, 0.9, 0.1) * 0.4), 0.25)
    shing = sum(bell(f, 1.2, 2.76, 3, 2.5) for f in (1200, 1690, 2210)) / 3
    s['cue_sweep'] = reverb(mix(shing, whoosh(0.6, 2000, 6000, 9, 0.5)), 0.3)
    casc = np.zeros(int(SR * 1.6))
    for i, f in enumerate((1568, 1318.5, 1046.5, 880, 784, 659.3, 523.3)):
        tone = bell(f, 0.6, 2.0, 1.4, 6) * 0.45
        begin = int(SR * i * 0.13)
        casc[begin:begin + len(tone)] += tone[:len(casc) - begin]
    s['cue_starfall'] = reverb(mix(casc, pad(261.6, 1.6, 10, 1800) * 0.25), 0.4)
    s['count'] = mix(bell(1760, 0.18, 1.0, 0.5, 30) * 0.6, highpass(noise(0.02, 11), 3000) * 0.3)
    s['count_final'] = reverb(bell(2093, 0.35, 2.0, 1.0, 9) * 0.7, 0.2, 0.4)
    s['impact_band'] = reverb(boom(1.4, 110, 32, 12, 3.2, 0.9), 0.3)
    s['impact_spoke'] = reverb(mix(osc(sweep(1800, 200, 0.6, 0.6), 0.6, 'saw') * decay(0.6, 6) * 0.5,
                                   boom(0.4, 160, 60, 13, 9, 0.5) * 0.6), 0.25)
    s['impact_heart'] = reverb(mix(osc(sweep(90, 30, 0.9, 0.4), 0.9) * decay(0.9, 5, 0.002),
                                   lowpass(noise(0.9, 14), 400) * decay(0.9, 8) * 0.6), 0.25)
    s['impact_brand'] = reverb(mix(sparkle(1.0, 15, 18, 1800, 6000, 7) * 0.8, boom(0.5, 200, 80, 16, 8, 0.6) * 0.5), 0.3)
    s['impact_gaze'] = reverb(mix(lowpass(osc(sweep(400, 90, 1.0, 0.5), 1.0, 'saw'), 2500) * decay(1.0, 3.5) * 0.6,
                                  whoosh(1.0, 4000, 800, 17, 0.6)), 0.3)
    s['impact_meteor'] = reverb(boom(1.2, 140, 38, 18, 3.6, 1.1), 0.25)
    s['bullet'] = mix(bell(1975.5, 0.22, 2.0, 0.8, 18) * 0.35, highpass(noise(0.05, 19), 4000) * decay(0.05, 60) * 0.2)
    s['nova_burst'] = reverb(mix(whoosh(0.7, 900, 5000, 20, 0.8), bell(1046.5, 0.7, 1.5, 2, 5) * 0.35,
                                 bell(1568, 0.7, 1.5, 2, 6) * 0.25), 0.3)
    s['shock_low'] = reverb(mix(whoosh(0.9, 120, 900, 21, 1.1), osc(sweep(70, 45, 0.9), 0.9) * decay(0.9, 4) * 0.5), 0.2)
    s['shock_high'] = reverb(mix(whoosh(0.9, 2500, 9000, 22, 0.9), bell(2637, 0.8, 2.0, 1.2, 5) * 0.3), 0.25)
    glide = np.concatenate([sweep(1600, 300, 0.4), sweep(300, 1600, 0.4)])
    s['sweep_reverse'] = reverb(osc(glide, 0.8, 'tri') * swell(0.8, 0.2, 0.3) * 0.5, 0.25)
    s['fragment_drop'] = reverb(sparkle(0.5, 23, 5, 2500, 4500, 10) * 0.8, 0.35)
    up = np.zeros(int(SR * 0.55))
    for i, f in enumerate((1318.5, 1568, 2093, 2637)):
        tone = bell(f, 0.3, 2.0, 1.0, 10) * 0.5
        begin = int(SR * i * 0.06)
        up[begin:begin + len(tone)] += tone[:len(up) - begin]
    s['fragment_pickup'] = reverb(up, 0.3)
    riser = osc(sweep(80, 900, 3.0, 1.6), 3.0, 'saw') * np.linspace(0.05, 1.0, int(SR * 3.0)) ** 2
    s['supernova_charge'] = reverb(mix(lowpass(riser, sweep(400, 5000, 3.0)) * 0.5, whoosh(3.0, 200, 8000, 24, 0.7)), 0.3)
    s['supernova_blast'] = reverb(mix(boom(2.5, 120, 25, 25, 1.6, 1.3), bell(73.4, 2.5, 1.41, 6, 1.5) * 0.5), 0.35, 1.2)
    glass = sparkle(1.4, 26, 26, 2500, 7500, 6) * 0.9
    s['stagger'] = reverb(mix(glass, boom(0.6, 300, 90, 27, 7, 0.9) * 0.6,
                              at(0.25, sum(bell(f, 1.2, 2.0, 1.5, 3) for f in (523.3, 659.3, 784)) * 0.25)), 0.35)
    s['ritual_start'] = reverb(mix(pad(146.8, 2.4, 28, 1100) * 0.6, bell(73.4, 2.4, 1.41, 4, 1.4) * 0.7), 0.4, 1.0)
    s['seal_done'] = reverb(mix(bell(784, 1.4, 2.0, 2, 2.5) * 0.6, at(0.06, bell(1174.7, 1.3, 2.0, 2, 2.6) * 0.45)), 0.35)
    s['ritual_success'] = reverb(mix(*(at(i * 0.07, bell(f, 1.8, 2.0, 1.6, 2.2) * 0.35)
                                       for i, f in enumerate((523.3, 659.3, 784, 1046.5, 1318.5)))), 0.4)
    s['ritual_fail'] = reverb(mix(boom(2.0, 70, 22, 29, 1.8, 1.4), whoosh(2.0, 1500, 150, 30, 0.6)), 0.3)
    return s


def normalized(samples):
    peak = float(np.max(np.abs(samples))) or 1.0
    audible = np.nonzero(np.abs(samples) > peak * 0.003)[0]
    samples = samples[:audible[-1] + 1] if len(audible) else samples
    out = samples / peak * 0.89
    fade = min(len(out), int(SR * 0.012))
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    return out.astype(np.float32)


OGG_SERIAL = 0x4e595831  # 'NYX1'


def _ogg_crc_table():
    table = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04c11db7) if r & 0x80000000 else (r << 1)
        table.append(r & 0xffffffff)
    return table


OGG_CRC = _ogg_crc_table()


def fixed_serial(data):
    """libvorbis picks a random stream serial; pin it and refresh each page CRC."""
    data = bytearray(data)
    offset = 0
    while offset < len(data):
        if data[offset:offset + 4] != b'OggS':
            raise ValueError('not an Ogg page')
        segments = data[offset + 26]
        size = 27 + segments + sum(data[offset + 27:offset + 27 + segments])
        data[offset + 14:offset + 18] = OGG_SERIAL.to_bytes(4, 'little')
        data[offset + 22:offset + 26] = b'\0\0\0\0'
        crc = 0
        for byte in data[offset:offset + size]:
            crc = ((crc << 8) & 0xffffffff) ^ OGG_CRC[((crc >> 24) & 0xff) ^ byte]
        data[offset + 22:offset + 26] = crc.to_bytes(4, 'little')
        offset += size
    return bytes(data)


def ogg(samples):
    buffer = BytesIO()
    soundfile.write(buffer, normalized(samples), SR, format='OGG', subtype='VORBIS')
    return fixed_serial(buffer.getvalue())


def sounds_json(names):
    current = json.loads(SOUNDS_JSON.read_text(encoding='utf-8'))
    merged = {key: value for key, value in current.items() if not key.startswith('boss.nyxeron.')}
    for name in names:
        merged['boss.nyxeron.' + name] = {'sounds': ['astralrecord:boss/nyxeron/' + name]}
    return (json.dumps(merged, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


# ---------------------------------------------------------------- assemble
def dump(data):
    return (json.dumps(data, ensure_ascii=False, indent=1) + '\n').encode('utf-8')


def png(image):
    buffer = BytesIO()
    image.save(buffer, format='PNG', optimize=True)
    return buffer.getvalue()


def mcmeta(frametime):
    return dump({'animation': {'frametime': frametime, 'interpolate': True}})


def build():
    files = {}
    body_frames = [body_texture(i / TEXTURE_FRAMES) for i in range(TEXTURE_FRAMES)]
    skill_frames = [skill_texture(i / TEXTURE_FRAMES) for i in range(TEXTURE_FRAMES)]
    files[JAVA / 'textures/item/nyxeron.png'] = png(animated(body_frames))
    files[JAVA / 'textures/item/nyxeron.png.mcmeta'] = mcmeta(3)
    files[JAVA / 'textures/item/nyxeron_skill.png'] = png(animated(skill_frames))
    files[JAVA / 'textures/item/nyxeron_skill.png.mcmeta'] = mcmeta(2)
    files[BEDROCK / 'textures/entity/nyxeron.png'] = png(body_frames[0])
    files[BEDROCK / 'textures/entity/nyxeron_skill.png'] = png(skill_frames[0])
    core_names = ['nyxeron_core' if pose == 'idle' else 'nyxeron_core_' + pose for pose in CORE_POSES]
    for pose, name in zip(CORE_POSES, core_names):
        files[JAVA / ('models/item/%s.json' % name)] = dump(java_model(core_boxes(pose), 'astralrecord:item/nyxeron'))
    files[JAVA / 'items/nyxeron_core.json'] = dump(item_dispatch('nyxeron_core', core_names, True))
    parts = {'petal': petal_boxes(), 'cage': cage_boxes(), 'mote': mote_boxes()}
    parts.update({'ring_' + kind: ring_boxes(kind) for kind in RINGS})
    for suffix, boxes in parts.items():
        name = 'nyxeron_' + suffix
        files[JAVA / ('models/item/%s.json' % name)] = dump(java_model(boxes, 'astralrecord:item/nyxeron'))
        files[JAVA / ('items/%s.json' % name)] = dump(item_dispatch(name, None, False))
    for style in STYLES:
        if style in BEDROCK_ONLY:
            continue
        name = 'nyxeron_' + style
        files[JAVA / ('models/item/%s.json' % name)] = dump(skill_java_model(style))
        files[JAVA / ('items/%s.json' % name)] = dump(item_dispatch(name, None, False))
    files[BEDROCK / 'models/entity/nyxeron.geo.json'] = dump(body_geometry())
    files[BEDROCK / 'entity/nyxeron.entity.json'] = dump(body_entity())
    files[BEDROCK / 'animations/nyxeron.animation.json'] = dump(body_animations())
    files[BEDROCK / 'animation_controllers/nyxeron.animation_controllers.json'] = dump(body_controller())
    files[BEDROCK / 'render_controllers/nyxeron.render_controllers.json'] = dump(body_render_controller())
    files[BEDROCK / 'models/entity/nyxeron_skill.geo.json'] = dump(skill_geometries())
    files[BEDROCK / 'entity/nyxeron_skill.entity.json'] = dump(skill_entity())
    files[BEDROCK / 'animations/nyxeron_skill.animation.json'] = dump(skill_animation())
    files[BEDROCK / 'render_controllers/nyxeron_skill.render_controllers.json'] = dump(skill_render_controller())
    designs = sound_designs()
    for name, samples in designs.items():
        files[JAVA / ('sounds/boss/nyxeron/%s.ogg' % name)] = ogg(samples)
    files[SOUNDS_JSON] = sounds_json(designs)
    files[MARKER] = PRESENTATION_VERSION
    return files


def owned(files):
    """Existing generated paths, so retired models (the old 4-frame spells) are removed or reported."""
    found = set(JAVA.glob('models/item/nyxeron_*.json')) | set(JAVA.glob('items/nyxeron_*.json'))
    found |= set(JAVA.glob('sounds/boss/nyxeron/*.ogg'))
    return sorted(path for path in found if path not in files)


def validate(files):
    """Static checks: Java element bounds and legal single-axis angles, Bedrock bone parents, sounds."""
    for path, data in files.items():
        if path.suffix == '.ogg':
            samples, rate = soundfile.read(BytesIO(data))
            if rate != SR or samples.ndim != 1 or not 0.1 < len(samples) / SR < 5.0:
                raise ValueError('%s: unexpected audio %s Hz %s samples' % (path.name, rate, samples.shape))
            continue
        if path.suffix != '.json':
            continue
        doc = json.loads(data)
        for item in doc.get('elements', []):
            for value in item['from'] + item['to']:
                if not -16 <= value <= 32:
                    raise ValueError('%s: element outside -16..32: %s' % (path.name, item))
            rotation = item.get('rotation')
            if rotation and (rotation['angle'] not in (-45, -22.5, 22.5, 45) or rotation['axis'] != 'y'):
                raise ValueError('%s: unsupported rotation %s' % (path.name, rotation))
        for geometry in doc.get('minecraft:geometry', []):
            names = [bone['name'] for bone in geometry['bones']]
            if len(names) != len(set(names)):
                raise ValueError('%s: duplicate bone names in %s' % (path.name, geometry['description']['identifier']))
            for bone in geometry['bones']:
                if 'parent' in bone and (bone['parent'] not in names or bone['parent'] == bone['name']):
                    raise ValueError('%s: invalid parent %s' % (path.name, bone['parent']))


def same_content(path, data):
    """Compare text assets with LF line endings so CRLF checkouts verify like the pack build."""
    current = path.read_bytes()
    if path.suffix in ('.json', '.txt', '.mcmeta'):
        return current.replace(b'\r\n', b'\n') == data
    return current == data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='verify committed files instead of writing')
    args = parser.parse_args()
    files = build()
    validate(files)
    mismatched = []
    for path, data in sorted(files.items()):
        if args.check:
            if not path.exists() or not same_content(path, data):
                mismatched.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    stale = owned(files)
    if args.check:
        mismatched += ['(stale) ' + path.relative_to(ROOT).as_posix() for path in stale]
    else:
        for path in stale:
            path.unlink()
    if mismatched:
        raise SystemExit('Nyxeron assets are out of date:\n  ' + '\n  '.join(mismatched))
    print('%s %d Nyxeron files%s' % ('verified' if args.check else 'wrote', len(files),
                                     '' if args.check else ', removed %d stale' % len(stale)))


if __name__ == '__main__':
    main()
