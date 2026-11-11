"""Build the Orrery Heart Nyxeron body, rings and spell shapes for both editions.

The same box lists drive the Java item models (ItemDisplay) and the Bedrock client
entities, so both editions show the same silhouettes. Run with --check to verify that
the committed files match a fresh build.
"""

import argparse
import json
import math
from dataclasses import dataclass, field
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'assets' / 'astralrecord'
BEDROCK = ROOT / 'bedrock'
MARKER = ROOT / 'nyxeron-presentation-version.txt'

# ---------------------------------------------------------------- shared constants
CORE_POSES = ('idle', 'cast', 'attack', 'guard', 'exposed')
RINGS = ('outer', 'mid', 'inner')
STYLES = ('disc', 'band_1', 'band_2', 'band_3', 'band_4', 'spoke', 'seal', 'seal_done',
          'scar', 'impact', 'gaze', 'meteor', 'heart')
FRAMES = 4
# Java ItemDisplay scales from NyxeronPackDisplay (core 1.6, rings 1.25) and the core height.
CORE_SCALE = 2.0
RING_SCALE = 1.25
CORE_HEIGHT_PX = 2.35 * 16
BODY_TEX = 64
SKILL_TEX = 512
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
    """Stepped obsidian heart with veins, a lens eye on four sides and pose accents."""
    boxes = []
    exposed = pose == 'exposed'
    if not exposed:
        # Rounded voxel sphere: three slabs plus an inner cube.
        boxes += [
            cube(0, 0, 0, 10, 6, 6, 'obsidian', group='shell', tint=True),
            cube(0, 0, 0, 6, 10, 6, 'obsidian', group='shell', tint=True),
            cube(0, 0, 0, 6, 6, 10, 'obsidian', group='shell', tint=True),
            cube(0, 0, 0, 8.4, 8.4, 8.4, 'obsidian_edge', group='shell', tint=True),
        ]
        # Gold equatorial band and polar caps.
        boxes += [
            cube(0, 0, 0, 10.6, 1.0, 6.6, 'gold', group='shell', tint=True),
            cube(0, 0, 0, 6.6, 1.0, 10.6, 'gold', group='shell', tint=True),
            cube(0, 5.4, 0, 3, 1.2, 3, 'gold', group='shell', tint=True),
            cube(0, -5.4, 0, 3, 1.2, 3, 'gold', group='shell', tint=True),
        ]
        # Crying-obsidian veins running over the slabs.
        for sign in (-1, 1):
            boxes += [
                cube(sign * 5.05, 2.0, 0, 0.3, 2.2, 1.0, 'vein', group='glow_veins', glow=True),
                cube(sign * 5.05, -2.2, 1.2, 0.3, 1.6, 0.8, 'vein', group='glow_veins', glow=True),
                cube(0, 2.2, sign * 5.05, 1.0, 2.0, 0.3, 'vein', group='glow_veins', glow=True),
                cube(-1.4, -2.0, sign * 5.05, 0.8, 1.8, 0.3, 'vein', group='glow_veins', glow=True),
            ]
    # Lens eye on the four sides (bigger while casting, white-hot while attacking).
    eye = {'cast': 1.35, 'attack': 1.15, 'guard': 0.8}.get(pose, 1.0)
    if not exposed:
        for sign in (-1, 1):
            boxes += [
                cube(sign * 5.25, 0, 0, 0.4, 1.6 * eye, 3.0 * eye, 'eye', group='glow_eye', glow=True),
                cube(sign * 5.4, 0, 0, 0.3, 0.8 * eye, 1.0 * eye, 'crying', group='glow_eye', glow=True),
                cube(0, 0, sign * 5.25, 3.0 * eye, 1.6 * eye, 0.4, 'eye', group='glow_eye', glow=True),
                cube(0, 0, sign * 5.4, 1.0 * eye, 0.8 * eye, 0.3, 'crying', group='glow_eye', glow=True),
            ]
    # Crown spikes at the poles.
    spike = {'cast': 3.6, 'attack': 4.4, 'guard': 2.0, 'exposed': 1.2}.get(pose, 2.6)
    boxes += [
        cube(0, 6.0 + spike / 2, 0, 1.6, spike, 1.6, 'crying', group='crown', tint=True),
        cube(0, 6.0 + spike + 0.5, 0, 1.0, 1.0, 1.0, 'vein', group='glow_crown', glow=True),
        cube(0, -6.0 - spike / 2, 0, 1.6, spike, 1.6, 'crying', group='crown', tint=True),
    ]
    if pose == 'attack':
        # Flared petals: the heart opens on four sides.
        for angle in (0, 90, 180, 270):
            boxes.append(cube(0, 0, 7.2, 4.0, 5.0, 1.2, 'obsidian', group='petal', tint=True, yaw=angle))
            boxes.append(cube(0, 0, 7.9, 2.4, 3.0, 0.3, 'vein', group='glow_petal', glow=True, yaw=angle))
    if pose == 'guard':
        # Amethyst cage: six crystals around the shell.
        for x, y, z in ((7.2, 0, 0), (-7.2, 0, 0), (0, 0, 7.2), (0, 0, -7.2), (0, 7.6, 0), (0, -7.6, 0)):
            sx, sy, sz = (1.4, 4.2, 4.2) if x else (4.2, 1.4, 4.2) if y else (4.2, 4.2, 1.4)
            boxes.append(cube(x, y, z, sx, sy, sz, 'crystal', group='glow_guard', glow=True))
    if exposed:
        # Shell broken open: bright core with gold cracks and drifting shards.
        boxes += [
            cube(0, 0, 0, 6.6, 6.6, 6.6, 'core', group='glow_exposed', glow=True),
            cube(0, 0, 0, 7.2, 1.2, 7.2, 'gold', group='glow_exposed', glow=True),
            cube(0, 0, 0, 1.2, 7.2, 7.2, 'gold', group='glow_exposed', glow=True),
        ]
        for x, y, z, sx, sy, sz in ((5.6, 2.4, 2.0, 2.2, 3.4, 2.6), (-5.4, -2.6, 1.0, 2.6, 2.8, 3.2),
                                    (1.6, 3.6, -5.6, 3.2, 2.4, 2.2), (-2.4, -4.4, -4.2, 2.4, 2.2, 2.8)):
            boxes.append(cube(x, y, z, sx, sy, sz, 'obsidian', group='shell_shard', tint=True))
    return boxes


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
        # Four gold satellites riding the outer ring.
        for yaw in (45, 135, 225, 315):
            boxes.append(cube(radius, 0, 0, 2.6, 2.6, 2.6, 'gold', group='ring_outer', yaw=yaw))
            boxes.append(cube(radius, 0, 0, 1.2, 3.2, 1.2, 'star', group='glow_tick_outer', glow=True, yaw=yaw))
    if kind == 'mid':
        # Zodiac teeth on the inner edge.
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
SKILL_SWATCHES = {
    'pillar': (0, '#ffd25a', 110), 'post': (1, '#ff4a78', 235), 'shard': (2, '#fff4ff', 230),
    'star': (3, '#fff0a0', 255), 'violet': (4, '#b46eff', 230), 'mint': (5, '#7affc0', 220),
    'gaze_core': (6, '#bff4ff', 240), 'ember': (7, '#ff8a3a', 240),
}
BAND_RATIO = {'band_1': 8 / 16, 'band_2': 16 / 24, 'band_3': 24 / 32, 'band_4': 32 / 40.5, 'heart': 9 / 15}


@dataclass
class SkillPart:
    lo: tuple
    hi: tuple
    region: str = ''          # decal region (up/down faces only)
    swatch: str = ''          # solid swatch for 3D parts
    group: str = 'decal'
    faces: tuple = FACES


def decal(region, y=8.02):
    return SkillPart((0, y, 0), (16, y + 0.04, 16), region=region, faces=('up', 'down'))


def posts(radius_units, count, height, swatch, frame, group='posts'):
    """Short upright posts on a rim; height pulses by frame."""
    pulse = (0.65, 0.85, 1.0, 0.85)[frame]
    parts = []
    for i in range(count):
        angle = (i + 0.5) * 2 * math.pi / count
        x, z = 8 + math.cos(angle) * radius_units, 8 + math.sin(angle) * radius_units
        parts.append(SkillPart((x - 0.12, 8.02, z - 0.12), (x + 0.12, 8.02 + height * pulse, z + 0.12),
                               swatch=swatch, group=group))
    return parts


def skill_parts(style, frame):
    parts = []
    if style == 'disc':
        parts.append(decal('disc'))
        parts += posts(7.7, 16, 6.0, 'post', frame)
    elif style.startswith('band_') or style == 'heart':
        parts.append(decal(style))
        swatch = 'violet' if style == 'heart' else 'post'
        parts += posts(7.7, 24, 5.0, swatch, frame)
        parts += posts(7.7 * BAND_RATIO[style], 16, 5.0, swatch, frame, group='posts_inner')
    elif style == 'spoke':
        parts.append(decal('spoke'))
        blade = (0.55, 0.8, 1.0, 0.8)[frame]
        parts.append(SkillPart((7.85, 8.02, 0.2), (8.15, 8.02 + 8.0 * blade, 15.8), swatch='post', group='blade'))
    elif style in ('seal', 'seal_done'):
        parts.append(decal(style))
        swatch = 'pillar' if style == 'seal' else 'mint'
        width = (5.0, 5.6, 6.0, 5.6)[frame] if style == 'seal' else 4.0
        lo, hi = 8 - width / 2, 8 + width / 2
        top = 24.0 if style == 'seal' else 16.0
        parts.append(SkillPart((lo, 8.04, lo), (hi, top, hi), swatch=swatch, group='pillar',
                               faces=('north', 'south', 'west', 'east')))
    elif style == 'scar':
        parts.append(decal('scar'))
        parts += posts(6.0, 8, 4.0, 'violet', frame)
    elif style == 'impact':
        grow = (0.45, 0.7, 0.9, 1.0)[frame]
        parts.append(SkillPart((8 - 8 * grow, 8.03, 8 - 8 * grow), (8 + 8 * grow, 8.07, 8 + 8 * grow),
                               region='impact', faces=('up', 'down'), group='burst'))
        height = (5.0, 8.0, 7.0, 4.0)[frame]
        for angle in (0, 90):
            rad = math.radians(angle)
            half = 6.5 * grow
            x0, z0 = 8 - math.cos(rad) * half, 8 - math.sin(rad) * half
            x1, z1 = 8 + math.cos(rad) * half, 8 + math.sin(rad) * half
            parts.append(SkillPart((min(x0, x1) - 0.15, 8.04, min(z0, z1) - 0.15),
                                   (max(x0, x1) + 0.15, 8.04 + height, max(z0, z1) + 0.15),
                                   swatch='shard', group='burst'))
    elif style == 'gaze':
        # Upright eye facing the arena centre (+Z).
        parts.append(SkillPart((0, 8.0, 7.98), (16, 24.0, 8.02), region='gaze', faces=('north', 'south'),
                               group='eye'))
        iris = (2.4, 3.0, 3.6, 3.0)[frame]
        parts.append(SkillPart((8 - iris / 2, 16 - iris / 2, 7.6), (8 + iris / 2, 16 + iris / 2, 8.4),
                               swatch='gaze_core', group='iris'))
    elif style == 'meteor':
        parts.append(decal('meteor'))
        height = (19.0, 13.0, 7.0, 1.5)[frame]
        size = 2.4
        parts.append(SkillPart((8 - size / 2, 8 + height, 8 - size / 2), (8 + size / 2, 8 + height + size, 8 + size / 2),
                               swatch='star', group='star'))
        parts.append(SkillPart((8 - 0.4, 8 + height + size, 8 - 0.4), (8 + 0.4, min(32.0, 8 + height + size + 5.0), 8 + 0.4),
                               swatch='ember', group='star'))
    return parts


# ---------------------------------------------------------------- textures
def hexrgb(value, alpha=255):
    value = value.lstrip('#')
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (alpha,)


def body_texture():
    atlas = Image.new('RGBA', (BODY_TEX, BODY_TEX), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    for name, (index, base, shade, light) in BODY_SWATCHES.items():
        x, y = (index % 8) * 8, (index // 8) * 8
        draw.rectangle((x, y, x + 7, y + 7), fill=hexrgb(base))
        draw.line((x, y + 7, x + 7, y + 7), fill=hexrgb(shade))
        draw.line((x + 7, y, x + 7, y + 7), fill=hexrgb(shade))
        draw.line((x, y, x + 6, y), fill=hexrgb(light))
        # Small stepped highlight cluster so faces read as blocky material.
        draw.rectangle((x + 2, y + 2, x + 3, y + 3), fill=hexrgb(light))
        draw.point((x + 5, y + 4), fill=hexrgb(shade))
        draw.point((x + 4, y + 5), fill=hexrgb(shade))
    return atlas


def ring_band(draw, ox, oy, size, inner_ratio, fill, rim, accent, dashed=True):
    c = (size - 1) / 2
    outer = c - 1
    inner = outer * inner_ratio
    for py in range(size):
        for px in range(size):
            d = math.hypot(px - c, py - c)
            if inner <= d <= outer:
                color = fill
                if d >= outer - 3 or (inner > 0 and d <= inner + 3):
                    color = rim
                elif dashed and abs(d - (inner + outer) / 2) < 1.2:
                    angle = math.atan2(py - c, px - c)
                    if int((angle + math.pi) / (2 * math.pi) * 48) % 2 == 0:
                        color = accent
                draw.point((ox + px, oy + py), fill=color)


def skill_texture():
    atlas = Image.new('RGBA', (SKILL_TEX, SKILL_TEX), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    danger = ((200, 40, 90, 92), (255, 85, 125, 238), (255, 175, 205, 190))
    ox, oy = REGIONS['disc']
    ring_band(draw, ox, oy, 128, 0.0, *danger)
    # Rune spokes inside the disc.
    for k in range(8):
        angle = k * math.pi / 4
        for r in range(14, 50):
            draw.point((ox + 63.5 + math.cos(angle) * r, oy + 63.5 + math.sin(angle) * r), fill=danger[2])
    for name in ('band_1', 'band_2', 'band_3', 'band_4'):
        ox, oy = REGIONS[name]
        ring_band(draw, ox, oy, 128, BAND_RATIO[name], *danger)
    ox, oy = REGIONS['heart']
    ring_band(draw, ox, oy, 128, BAND_RATIO['heart'], (230, 60, 170, 92), (255, 120, 210, 240), (255, 200, 240, 200))
    ox, oy = REGIONS['seal']
    ring_band(draw, ox, oy, 128, 0.0, (255, 196, 80, 80), (255, 220, 110, 240), (255, 245, 190, 210))
    for k in range(8):
        angle = k * math.pi / 4
        for r in range(6, 56):
            width = max(0, 4 - r // 14)
            for w in range(-width, width + 1):
                draw.point((ox + 63.5 + math.cos(angle) * r - math.sin(angle) * w * 0.5,
                            oy + 63.5 + math.sin(angle) * r + math.cos(angle) * w * 0.5), fill=(255, 235, 150, 220))
    ox, oy = REGIONS['seal_done']
    ring_band(draw, ox, oy, 128, 0.0, (110, 255, 190, 70), (200, 255, 230, 235), (235, 255, 245, 200))
    ox, oy = REGIONS['scar']
    ring_band(draw, ox, oy, 128, 0.0, (120, 40, 200, 96), (190, 120, 255, 230), (220, 170, 255, 200), dashed=False)
    for k in range(5):
        a0 = k * 2 * math.pi / 5 - math.pi / 2
        a1 = (k + 2) * 2 * math.pi / 5 - math.pi / 2
        draw.line((ox + 63.5 + math.cos(a0) * 48, oy + 63.5 + math.sin(a0) * 48,
                   ox + 63.5 + math.cos(a1) * 48, oy + 63.5 + math.sin(a1) * 48), fill=(225, 175, 255, 235), width=3)
    ox, oy = REGIONS['impact']
    ring_band(draw, ox, oy, 128, 0.62, (255, 230, 255, 120), (255, 255, 255, 245), (255, 200, 240, 230), dashed=False)
    for k in range(12):
        angle = k * math.pi / 6
        draw.line((ox + 63.5 + math.cos(angle) * 12, oy + 63.5 + math.sin(angle) * 12,
                   ox + 63.5 + math.cos(angle) * 36, oy + 63.5 + math.sin(angle) * 36), fill=(255, 240, 255, 200), width=2)
    ox, oy = REGIONS['gaze']
    for py in range(128):
        for px in range(128):
            nx, ny = (px - 63.5) / 60, (py - 63.5) / 34
            if abs(ny) <= (1 - nx * nx) and abs(nx) <= 1:
                d = math.hypot(px - 63.5, py - 63.5)
                color = (180, 240, 255, 230)
                if d < 30:
                    color = (130, 70, 230, 240)
                if d < 16:
                    color = (30, 12, 60, 255)
                if d < 5:
                    color = (255, 255, 255, 255)
                if abs(ny) > (1 - nx * nx) - 0.12:
                    color = (90, 200, 255, 250)
                draw.point((ox + px, oy + py), fill=color)
    ox, oy = REGIONS['meteor']
    ring_band(draw, ox, oy, 128, 0.0, (255, 120, 40, 90), (255, 160, 60, 240), (255, 220, 160, 200))
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
                color = (255, 85, 125, 240)
            if d < 0.12 and (py // 8) % 2 == 0:
                color = (255, 245, 210, 250)
            draw.point((ox + px, oy + py), fill=color)
    for name, (index, value, alpha) in SKILL_SWATCHES.items():
        x, y = 128 + index * 16, 384
        draw.rectangle((x, y, x + 15, y + 15), fill=hexrgb(value, alpha))
        draw.rectangle((x + 3, y + 3, x + 6, y + 6), fill=(255, 255, 255, min(255, alpha + 20)))
    return atlas


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


def java_element(box):
    quarter = int(round(box.yaw / 90.0))
    phi = box.yaw - quarter * 90.0
    lo, hi = rotate90(box.lo, box.hi, quarter)
    uv = swatch_uv(box.swatch)
    faces = {}
    for face in box.faces:
        entry = {'uv': uv, 'texture': '#0'}
        if box.tint:
            entry['tintindex'] = 0
        faces[face] = entry
    element = {'from': r4(lo), 'to': r4(hi), 'faces': faces}
    if abs(phi) > 1e-6:
        element['rotation'] = {'angle': round(phi, 4), 'axis': 'y', 'origin': [8, 8, 8]}
    if box.glow:
        element['shade'] = False
        element['light_emission'] = 15
    return element


def java_model(boxes, texture):
    return {
        'credit': 'AstralRecord generate-nyxeron-assets.py',
        'ambientocclusion': False,
        'textures': {'0': texture, 'particle': texture},
        'elements': [java_element(box) for box in boxes],
    }


def skill_uv(part):
    if part.region:
        x, y = REGIONS[part.region]
        unit = 16 / SKILL_TEX
        return [x * unit, y * unit, (x + 128) * unit, (y + 128) * unit]
    index = SKILL_SWATCHES[part.swatch][0]
    x, y = 128 + index * 16, 384
    unit = 16 / SKILL_TEX
    return [x * unit + 0.125, y * unit + 0.125, (x + 16) * unit - 0.125, (y + 16) * unit - 0.125]


def skill_java_model(style, frame):
    elements = []
    for part in skill_parts(style, frame):
        uv = r4(skill_uv(part))
        elements.append({
            'from': r4(part.lo), 'to': r4(part.hi), 'shade': False, 'light_emission': 15,
            'faces': {face: {'uv': uv, 'texture': '#0'} for face in part.faces},
        })
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
def bedrock_uv(box_or_part, skill=False):
    if skill:
        if box_or_part.region:
            x, y = REGIONS[box_or_part.region]
            return [x, y], [128, 128]
        index = SKILL_SWATCHES[box_or_part.swatch][0]
        return [128 + index * 16 + 2, 384 + 2], [12, 12]
    index = BODY_SWATCHES[box_or_part.swatch][0]
    return [(index % 8) * 8, (index // 8) * 8], [8, 8]


def bedrock_cube(lo, hi, scale, y_offset, uv, size, faces, yaw=0.0, pivot_y=None):
    """Java box (units, centre 8) -> Bedrock cube in pixels with X mirrored."""
    x0, x1 = -(hi[0] - 8) * scale, -(lo[0] - 8) * scale
    cube = {
        'origin': r4([x0, (lo[1] - 8) * scale + y_offset, (lo[2] - 8) * scale]),
        'size': r4([x1 - x0, (hi[1] - lo[1]) * scale, (hi[2] - lo[2]) * scale]),
        'uv': {face: {'uv': uv, 'uv_size': size} for face in faces},
    }
    if abs(yaw) > 1e-6:
        cube['pivot'] = r4([0, pivot_y if pivot_y is not None else y_offset, 0])
        cube['rotation'] = [0, round(-yaw, 4), 0]
    return cube


def body_geometry():
    bones = [
        {'name': 'root', 'pivot': [0, 0, 0]},
        {'name': 'body', 'parent': 'root', 'pivot': [0, CORE_HEIGHT_PX, 0]},
        {'name': 'core', 'parent': 'body', 'pivot': [0, CORE_HEIGHT_PX, 0]},
    ]
    # Core: union of the pose variants, each group toggled by the render controller.
    groups = {}
    seen = set()
    for pose in CORE_POSES:
        for box in core_boxes(pose):
            name = box.group
            if pose == 'cast' and name == 'glow_eye':
                name = 'glow_eye_cast'
            elif pose != 'idle' and name in ('glow_eye', 'crown', 'glow_crown'):
                # Pose-scaled eye/crown variants: reuse idle and scale by animation.
                continue
            elif pose == 'idle' and name in ('glow_eye',):
                name = 'glow_eye'
            key = (name, box.lo, box.hi, box.yaw)
            if key in seen:
                continue
            seen.add(key)
            groups.setdefault(name, []).append(box)
    for name, boxes in groups.items():
        cubes = []
        for box in boxes:
            uv, size = bedrock_uv(box)
            cubes.append(bedrock_cube(box.lo, box.hi, CORE_SCALE, CORE_HEIGHT_PX, uv, size, box.faces,
                                      yaw=box.yaw, pivot_y=CORE_HEIGHT_PX))
        bones.append({'name': name, 'parent': 'core', 'pivot': [0, CORE_HEIGHT_PX, 0], 'cubes': cubes})
    for kind in RINGS:
        prec, tilt, spin = 'ring_%s_prec' % kind, 'ring_%s_tilt' % kind, 'ring_%s_spin' % kind
        bones += [
            {'name': prec, 'parent': 'body', 'pivot': [0, CORE_HEIGHT_PX, 0]},
            {'name': tilt, 'parent': prec, 'pivot': [0, CORE_HEIGHT_PX, 0]},
            {'name': spin, 'parent': tilt, 'pivot': [0, CORE_HEIGHT_PX, 0]},
        ]
        groups = {}
        for box in ring_boxes(kind):
            groups.setdefault(box.group, []).append(box)
        for name, boxes in groups.items():
            cubes = []
            for box in boxes:
                uv, size = bedrock_uv(box)
                cubes.append(bedrock_cube(box.lo, box.hi, RING_SCALE, CORE_HEIGHT_PX, uv, size, box.faces,
                                          yaw=box.yaw, pivot_y=CORE_HEIGHT_PX))
            bones.append({'name': name, 'parent': spin, 'pivot': [0, CORE_HEIGHT_PX, 0], 'cubes': cubes})
    return {
        'format_version': '1.12.0',
        'minecraft:geometry': [{
            'description': {
                'identifier': 'geometry.astralrecord.nyxeron', 'texture_width': BODY_TEX, 'texture_height': BODY_TEX,
                'visible_bounds_width': 6, 'visible_bounds_height': 6, 'visible_bounds_offset': [0, 2.4, 0],
            },
            'bones': bones,
        }],
    }


def skill_geometries():
    geometries = []
    for style in STYLES:
        groups = {}
        for part in skill_parts(style, 2):
            groups.setdefault(part.group, []).append(part)
        bones = [{'name': 'root', 'pivot': [0, 0, 0]}]
        for name, parts in groups.items():
            cubes = []
            for part in parts:
                uv, size = bedrock_uv(part, skill=True)
                cubes.append(bedrock_cube(part.lo, part.hi, 1.0, 0.0, uv, size, part.faces))
            bone = {'name': name, 'parent': 'root', 'pivot': [0, 0, 0], 'cubes': cubes}
            if name == 'iris':
                bone['pivot'] = [0, 8, 0]
            bones.append(bone)
        geometries.append({
            'description': {
                'identifier': 'geometry.astralrecord.nyxeron_' + style, 'texture_width': SKILL_TEX,
                'texture_height': SKILL_TEX, 'visible_bounds_width': 96, 'visible_bounds_height': 32,
                'visible_bounds_offset': [0, 0, 0],
            },
            'bones': bones,
        })
    return {'format_version': '1.12.0', 'minecraft:geometry': geometries}


RATES = {'a': (18.0, 30.0), 'b': (-12.0, 44.0), 'c': (28.0, -60.0)}
TILTS = {  # default, attack, guard, exposed
    'a': (24, 0, 90, 62), 'b': (72, 0, 90, 28), 'c': (-48, 0, 0, 84),
}
RING_LETTER = {'outer': 'a', 'mid': 'b', 'inner': 'c'}


def body_entity():
    init = ['variable.body_yaw_ready = 0;', 'variable.ring_scale = 1.0;', 'variable.lift = 0.0;']
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
    ]
    for letter, (prec, spin) in RATES.items():
        default, attack, guard, exposed = TILTS[letter]
        pre.append('variable.prec_%s = math.mod(variable.prec_%s + variable.step * %.1f * variable.rate, 360.0);'
                   % (letter, letter, prec))
        pre.append('variable.spin_%s = math.mod(variable.spin_%s + variable.step * %.1f * variable.rate, 360.0);'
                   % (letter, letter, spin))
        pre.append('variable.tilt_%s = math.lerp(variable.tilt_%s, variable.motion == 2 ? %d : (variable.motion == 3 ? %d : (variable.motion == 4 ? %d : %d)), variable.ease);'
                   % (letter, letter, attack, guard, exposed, default))
    pre.append('variable.ring_scale = math.lerp(variable.ring_scale, variable.motion == 1 ? 1.1 : (variable.motion == 2 ? 1.18 : (variable.motion == 3 ? 0.72 : (variable.motion == 4 ? 1.04 : 1.0))), variable.ease);')
    pre.append('variable.lift = math.lerp(variable.lift, variable.motion == 4 ? -11.2 : (variable.motion == 1 ? 4.0 : 0.0), variable.ease);')
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
        'body': {'position': [0, 'variable.lift', 0]},
        'core': {'rotation': [0, 'variable.spin_a * 0.3', 0]},
    }
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
        'animation.astralrecord.nyxeron.guard': {'loop': True, 'animation_length': 2.0, 'bones': {
            'glow_guard': {'rotation': {'0.0': [0, 0, 0], '1.0': [0, 180, 0], '2.0': [0, 360, 0]}},
            'core': {'scale': 0.94}, 'glow_eye': {'scale': [1, 0.8, 0.8]}}},
        'animation.astralrecord.nyxeron.exposed': {'loop': True, 'bones': {
            'core': {'position': ['math.sin(query.life_time * 1500.0) * 0.4', 0, 'math.cos(query.life_time * 1300.0) * 0.4'],
                     'scale': [beat] * 3},
            'shell_shard': {'rotation': [0, 'query.life_time * 40.0', 0]}}},
    }}


def body_controller():
    states = {}
    names = ('idle', 'cast', 'attack', 'guard', 'exposed')
    for index, name in enumerate(names):
        states[name] = {
            'animations': [name],
            'transitions': [{other: 'variable.motion == %d' % j} for j, other in enumerate(names) if other != name],
            'blend_transition': 0.12,
        }
    return {'format_version': '1.10.0', 'animation_controllers': {
        'controller.animation.astralrecord.nyxeron': {'initial_state': 'idle', 'states': states}}}


def body_render_controller():
    return {'format_version': '1.8.0', 'render_controllers': {'controller.render.astralrecord.nyxeron': {
        'geometry': 'Geometry.default',
        'materials': [{'*': 'Material.default'}, {'glow_*': 'Material.glow'}],
        'textures': ['Texture.default'],
        'part_visibility': [
            {'*': True},
            {'shell': 'variable.motion != 4'}, {'glow_veins': 'variable.motion != 4'},
            {'glow_eye': 'variable.motion != 4 && variable.motion != 1'},
            {'glow_eye_cast': 'variable.motion == 1'},
            {'petal': 'variable.motion == 2'}, {'glow_petal': 'variable.motion == 2'},
            {'glow_guard': 'variable.invulnerable'},
            {'glow_exposed': 'variable.motion == 4'}, {'shell_shard': 'variable.motion == 4'},
        ],
    }}}


def skill_entity():
    return {'format_version': '1.10.0', 'minecraft:client_entity': {'description': {
        'identifier': 'astralrecord:nyxeron_skill',
        'materials': {'default': 'entity_emissive_alpha'},
        'textures': {'default': 'textures/entity/nyxeron_skill'},
        'geometry': {style: 'geometry.astralrecord.nyxeron_' + style for style in STYLES},
        'animations': {'shape': 'animation.astralrecord.nyxeron_skill.shape'},
        'scripts': {
            'pre_animation': [
                "variable.style = query.property('astralrecord:style');",
                'variable.pulse = math.mod(query.life_time, 0.8) / 0.8;',
                'variable.breath = math.sin(180.0 * variable.pulse);',
                'variable.travel = math.clamp(query.life_time / 0.45, 0.0, 1.0);',
                'variable.fall = math.clamp(query.life_time / 3.0, 0.0, 1.0);',
            ],
            'animate': ['shape'],
        },
        'render_controllers': ['controller.render.astralrecord.nyxeron_skill'],
    }}}


def skill_animation():
    return {'format_version': '1.8.0', 'animations': {'animation.astralrecord.nyxeron_skill.shape': {
        'loop': True,
        'bones': {
            'root': {'scale': ["query.property('astralrecord:width')", "query.property('astralrecord:height')",
                               "query.property('astralrecord:length')"]},
            'posts': {'scale': [1, '0.65 + 0.35 * variable.breath', 1]},
            'posts_inner': {'scale': [1, '0.65 + 0.35 * variable.breath', 1]},
            'blade': {'scale': [1, '0.55 + 0.45 * variable.breath', 1]},
            'pillar': {'scale': ['0.9 + 0.12 * variable.breath', 1, '0.9 + 0.12 * variable.breath']},
            'burst': {'scale': ['math.lerp(0.45, 1.0, variable.travel)', 'math.lerp(0.6, 1.0, variable.travel)',
                                'math.lerp(0.45, 1.0, variable.travel)']},
            'iris': {'scale': ['0.8 + 0.3 * variable.breath'] * 3},
            'star': {'position': [0, 'math.lerp(14.0, -6.5, variable.fall)', 0]},
        },
    }}}


def skill_render_controller():
    return {'format_version': '1.8.0', 'render_controllers': {'controller.render.astralrecord.nyxeron_skill': {
        'arrays': {'geometries': {'Array.shapes': ['Geometry.' + style for style in STYLES]}},
        'geometry': "Array.shapes[math.clamp(query.property('astralrecord:style'), 0, %d)]" % (len(STYLES) - 1),
        'materials': [{'*': 'Material.default'}],
        'textures': ['Texture.default'],
        'ignore_lighting': True,
    }}}


# ---------------------------------------------------------------- assemble
def dump(data):
    return (json.dumps(data, ensure_ascii=False, indent=1) + '\n').encode('utf-8')


def png(image):
    buffer = BytesIO()
    image.save(buffer, format='PNG', optimize=True)
    return buffer.getvalue()


def build():
    files = {}
    body_png = png(body_texture())
    skill_png = png(skill_texture())
    files[JAVA / 'textures/item/nyxeron.png'] = body_png
    files[JAVA / 'textures/item/nyxeron_skill.png'] = skill_png
    files[BEDROCK / 'textures/entity/nyxeron.png'] = body_png
    files[BEDROCK / 'textures/entity/nyxeron_skill.png'] = skill_png
    core_names = ['nyxeron_core' if pose == 'idle' else 'nyxeron_core_' + pose for pose in CORE_POSES]
    for pose, name in zip(CORE_POSES, core_names):
        files[JAVA / ('models/item/%s.json' % name)] = dump(java_model(core_boxes(pose), 'astralrecord:item/nyxeron'))
    files[JAVA / 'items/nyxeron_core.json'] = dump(item_dispatch('nyxeron_core', core_names, True))
    for kind in RINGS:
        name = 'nyxeron_ring_' + kind
        files[JAVA / ('models/item/%s.json' % name)] = dump(java_model(ring_boxes(kind), 'astralrecord:item/nyxeron'))
        files[JAVA / ('items/%s.json' % name)] = dump(item_dispatch(name, None, False))
    for style in STYLES:
        name = 'nyxeron_' + style
        frames = [name] + ['%s_frame_%d' % (name, i) for i in range(1, FRAMES)]
        for i, frame in enumerate(frames):
            files[JAVA / ('models/item/%s.json' % frame)] = dump(skill_java_model(style, i))
        files[JAVA / ('items/%s.json' % name)] = dump(item_dispatch(name, frames, False))
    files[BEDROCK / 'models/entity/nyxeron.geo.json'] = dump(body_geometry())
    files[BEDROCK / 'entity/nyxeron.entity.json'] = dump(body_entity())
    files[BEDROCK / 'animations/nyxeron.animation.json'] = dump(body_animations())
    files[BEDROCK / 'animation_controllers/nyxeron.animation_controllers.json'] = dump(body_controller())
    files[BEDROCK / 'render_controllers/nyxeron.render_controllers.json'] = dump(body_render_controller())
    files[BEDROCK / 'models/entity/nyxeron_skill.geo.json'] = dump(skill_geometries())
    files[BEDROCK / 'entity/nyxeron_skill.entity.json'] = dump(skill_entity())
    files[BEDROCK / 'animations/nyxeron_skill.animation.json'] = dump(skill_animation())
    files[BEDROCK / 'render_controllers/nyxeron_skill.render_controllers.json'] = dump(skill_render_controller())
    files[MARKER] = b'1'
    return files


def validate(files):
    """Static checks: Java element bounds and legal single-axis angles, Bedrock bone parents."""
    for path, data in files.items():
        if path.suffix != '.json':
            continue
        doc = json.loads(data)
        for element in doc.get('elements', []):
            for value in element['from'] + element['to']:
                if not -16 <= value <= 32:
                    raise ValueError('%s: element outside -16..32: %s' % (path.name, element))
            rotation = element.get('rotation')
            if rotation and (rotation['angle'] not in (-45, -22.5, 22.5, 45) or rotation['axis'] != 'y'):
                raise ValueError('%s: unsupported rotation %s' % (path.name, rotation))
        for geometry in doc.get('minecraft:geometry', []):
            names = [bone['name'] for bone in geometry['bones']]
            if len(names) != len(set(names)):
                raise ValueError('%s: duplicate bone names in %s' % (path.name, geometry['description']['identifier']))
            for bone in geometry['bones']:
                if 'parent' in bone and (bone['parent'] not in names or bone['parent'] == bone['name']):
                    raise ValueError('%s: invalid parent %s' % (path.name, bone['parent']))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='verify committed files instead of writing')
    args = parser.parse_args()
    files = build()
    validate(files)
    mismatched = []
    for path, data in sorted(files.items()):
        if args.check:
            if not path.exists() or path.read_bytes() != data:
                mismatched.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if mismatched:
        raise SystemExit('Nyxeron assets are out of date:\n  ' + '\n  '.join(mismatched))
    print('%s %d Nyxeron files' % ('verified' if args.check else 'wrote', len(files)))


if __name__ == '__main__':
    main()
