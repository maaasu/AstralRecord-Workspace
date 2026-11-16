#!/usr/bin/env python3
"""Rebuild the Java action-ring cooldown atlas from the existing countdown art."""
import argparse
import io
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

PACK = Path(__file__).resolve().parents[1]
ASSETS = PACK / 'assets/astralrecord'
ATLAS = ASSETS / 'textures/font/action_ring_cooldown.png'
FONT = ASSETS / 'font/action_ring_cooldown.json'
TILE = 112
PATTERNS = (
    '111101101101101101111', '010110010010010010111', '111001001111100100111',
    '111001001111001001111', '101101101111001001001', '111100100111001001111',
    '111100100111101101111', '111001001001001001001', '111101101111101101111',
    '111101101111001001111', '000010010111010010000',
)


def glyphs():
    """Generate 24 tiles, each 14 GUI px wide after scaling."""
    atlas = Image.new('RGBA', (8 * TILE, 3 * TILE))
    count = ASSETS / 'textures/gui/challenge_countdown'
    for index in range(12):
        kind = 'dungeon' if index == 11 else 'boss'
        stage = 10 if index == 11 else index
        rim = Image.open(count / f'{kind}_{stage}.png').convert('RGBA').crop((57, 23, 200, 166))
        mask = Image.new('L', rim.size, 255)
        ImageDraw.Draw(mask).ellipse((13, 13, 129, 129), fill=0)
        rim.putalpha(Image.composite(rim.getchannel('A'), Image.new('L', rim.size), mask))
        if index == 11:
            rim.putalpha(rim.getchannel('A').point(lambda alpha: (alpha * 72 + 50) // 100))
        if index == 0:
            alpha = rim.getchannel('A').point(lambda value: (value * 60 + 50) // 100)
            rim = ImageEnhance.Color(rim).enhance(0)
            rim.putalpha(alpha)
        tile = Image.new('RGBA', (TILE, TILE))
        draw = ImageDraw.Draw(tile)
        draw.ellipse((5, 5, 106, 106), fill=(8, 17, 27, 231 if index else 171))
        tile.alpha_composite(rim.resize(tile.size, Image.Resampling.LANCZOS))
        draw = ImageDraw.Draw(tile)
        if index == 11:
            draw.polygon(((56, 44), (68, 56), (56, 68), (44, 56)), fill='#97e8ea')
        elif index == 0:
            draw.ellipse((51, 51, 60, 60), fill='#a8b0b5')
        paste(atlas, tile, index)
    hub = Image.new('RGBA', (TILE, TILE))
    draw = ImageDraw.Draw(hub)
    # A guide behind the individual slots, not a separate cooldown meter.
    draw.ellipse((17, 17, 94, 94), outline=(173, 144, 78, 104), width=1)
    draw.ellipse((23, 23, 88, 88), outline=(169, 136, 65, 58), width=1)
    draw.line((48, 56, 64, 56), fill=(187, 159, 96, 160), width=1)
    draw.line((56, 48, 56, 64), fill=(187, 159, 96, 160), width=1)
    paste(atlas, hub, 12)
    for number in range(11):
        tile = Image.new('RGBA', (TILE, TILE))
        if number < 10:
            number_ink(tile, number, 56, 8)
        else:
            for digit, center in zip((9, 9, 9, 10), (23, 45, 67, 89)):
                number_ink(tile, digit, center, 6)
        paste(atlas, tile, 13 + number)
    # Every BitmapProvider advance is 15. This alpha=1 sentinel measures the
    # right edge but vanilla text fragments discard it at normal opacity.
    for i in range(24):
        atlas.putpixel(((i % 8 + 1) * TILE - 1, i // 8 * TILE + TILE - 1), (255,255,255,1))
    return atlas


def paste(atlas, tile, index):
    atlas.paste(tile, (index % 8 * TILE, index // 8 * TILE))


def number_ink(tile, digit, center_x, pixel):
    ink = Image.new('L', tile.size)
    draw = ImageDraw.Draw(ink)
    x0, y0 = center_x - 3 * pixel // 2, 56 - 7 * pixel // 2
    for i, bit in enumerate(PATTERNS[digit]):
        if bit == '1':
            x, y = x0 + (i % 3) * pixel, y0 + (i // 3) * pixel
            draw.rectangle((x, y, x + pixel - 1, y + pixel - 1), fill=255)
    shadow = Image.new('RGBA', tile.size, '#111928')
    shadow.putalpha(ink.filter(ImageFilter.MaxFilter(7)))
    face = Image.new('RGBA', tile.size, '#fff0c7')
    face.putalpha(ink)
    tile.alpha_composite(shadow)
    tile.alpha_composite(face)


def outputs():
    rows = [''.join(chr(0xE100 + i + x) for x in range(8)) for i in (0, 8, 16)]
    font = {'providers': [
        {'type': 'space', 'advances': {chr(0xE118): -15}},
        {'type': 'bitmap', 'file': 'astralrecord:font/action_ring_cooldown.png',
         'height': 14, 'ascent': -4096, 'chars': rows},
    ]}
    atlas = glyphs()
    for row in range(3):
        for col in range(8):
            rect = (col * TILE, row * TILE, (col + 1) * TILE, (row + 1) * TILE)
            assert atlas.crop(rect).getchannel('A').getbbox()[2] == TILE
    image = io.BytesIO()
    atlas.save(image, 'PNG', optimize=True)
    return {ATLAS: image.getvalue(), FONT: (json.dumps(font, indent=2, ensure_ascii=True) + '\n').encode()}


def preview(path):
    atlas = Image.open(ATLAS).convert('RGBA')
    tile = lambda index: atlas.crop((index % 8 * TILE, index // 8 * TILE,
        (index % 8 + 1) * TILE, (index // 8 + 1) * TILE))
    out = Image.new('RGBA', (252, 252), '#18222b')
    out.alpha_composite(tile(12).resize((224,224), Image.Resampling.LANCZOS), (14,14))
    for i, (base, nums) in enumerate(((4,(3,)), (11,()), (7,(7,)), (0,()),
                                      (8,(3,0,0)), (11,()), (3,(2,)), (11,()))):
        angle = math.tau * i / 8
        x = round(126 + 84 * math.sin(angle) - 28)
        y = round(126 - 84 * math.cos(angle) - 28)
        out.alpha_composite(tile(base).resize((56,56), Image.Resampling.LANCZOS), (x,y))
        for column, digit in enumerate(nums):
            shift = (10 if column else -10) if len(nums) == 2 else (column - 1) * 16 if len(nums) == 3 else 0
            out.alpha_composite(tile(13 + digit).resize((56,56), Image.Resampling.LANCZOS), (x+shift,y))
    path.parent.mkdir(parents=True, exist_ok=True)
    out.convert('RGB').save(path)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--preview', type=Path)
    args = parser.parse_args()
    for path, data in outputs().items():
        if args.check:
            if path.suffix == '.png':
                with Image.open(path) as committed, Image.open(io.BytesIO(data)) as generated:
                    assert committed.tobytes() == generated.tobytes(), f'Stale {path}'
            else:
                assert path.read_text(encoding='utf8') == data.decode(), f'Stale {path}'
        else:
            path.write_bytes(data)
    if args.preview:
        preview(args.preview)
    print('Action ring cooldown font, source-derived atlas, and zero advance validated.')
