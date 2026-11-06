from math import cos, hypot, pi, radians, sin
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
JAVA_TEXTURE = ROOT / "assets/astralrecord/textures/font/weapon_attack.png"
BEDROCK_TEXTURES = ROOT / "bedrock/textures/entity/weapon_attack"
SIZE = 32
FRAME_COUNT = 6

WHITE = (248, 251, 255)
ICE = (132, 229, 255)
BLUE = (50, 125, 220)
GOLD = (255, 195, 81)
AMBER = (230, 104, 39)
MINT = (135, 255, 195)
JADE = (48, 193, 164)
LEMON = (255, 242, 165)
PINK = (255, 149, 244)
MAGENTA = (214, 65, 213)
LILAC = (194, 174, 255)
VIOLET = (128, 101, 247)


class Sprite:
    def __init__(self, opacity):
        self.pixels = bytearray(SIZE * SIZE * 4)
        self.opacity = opacity

    def dot(self, x, y, color):
        if not (1 <= x < SIZE - 1 and 1 <= y < SIZE - 1):
            raise ValueError(f"Effect exceeds the sprite margin at ({x}, {y})")
        offset = (y * SIZE + x) * 4
        self.pixels[offset:offset + 4] = bytes((*color, self.opacity))

    def line(self, start, end, color):
        x, y = start
        dx, dy = end[0] - x, end[1] - y
        steps = max(abs(dx), abs(dy), 1)
        for i in range(steps + 1):
            self.dot(round(x + i * dx / steps), round(y + i * dy / steps), color)

    def path(self, points, color):
        for start, end in zip(points, points[1:]):
            self.line(start, end, color)

    def polygon(self, points, color):
        edges = list(zip(points, points[1:] + points[:1]))
        for y in range(min(y for _, y in points), max(y for _, y in points) + 1):
            for x in range(min(x for x, _ in points), max(x for x, _ in points) + 1):
                crossings = sum(
                    x < ax + (y - ay) * (bx - ax) / (by - ay)
                    for (ax, ay), (bx, by) in edges
                    if (ay > y) != (by > y)
                )
                if crossings % 2:
                    self.dot(x, y, color)
        self.path(points + points[:1], color)

    def star(self, x, y, radius, color):
        self.line((x - radius, y), (x + radius, y), color)
        self.line((x, y - radius), (x, y + radius), color)


def ellipse(cx, cy, rx, ry, first=0, last=360):
    return [
        (round(cx + rx * cos(radians(angle))), round(cy + ry * sin(radians(angle))))
        for angle in range(first, last + 1, 5)
    ]


def sword(sprite, frame):
    # Sample a tapered crescent along a rotating sweep. Each ray shades its cut edge.
    start, extent, thickness = [
        (-150, 55, 2), (-135, 110, 4), (-115, 170, 5),
        (-65, 160, 3), (-5, 125, 2), (45, 85, 1),
    ][frame]
    for step in range(extent + 1):
        angle = radians(start + step)
        outer_x, outer_y = round(15 + 13 * cos(angle)), round(15 + 12 * sin(angle))
        width = thickness * sin(pi * step / extent)
        inner_x = round(15 + (13 - width) * cos(angle))
        inner_y = round(15 + (12 - width) * sin(angle))
        sprite.line((inner_x, inner_y), (outer_x, outer_y), BLUE)
    sprite.path(ellipse(15, 15, 13, 12, start, start + extent), ICE)
    if frame in (1, 2, 3):
        sprite.path(ellipse(15, 15, 13, 12, start + 20, start + extent - 20), WHITE)
        x, y = ellipse(15, 15, 15, 14, start + 40, start + 40)[0]
        sprite.dot(x, y, ICE)


def hammer(sprite, frame):
    # Falling streaks give way to a widening ground shock and airborne debris.
    if frame < 3:
        bottom = (13, 21, 24)[frame]
        sprite.polygon([(10, 3), (18, bottom - 8), (23, bottom), (14, bottom - 4)], AMBER)
        sprite.polygon([(12, 5), (17, bottom - 6), (21, bottom - 1), (16, bottom - 4)], GOLD)
        sprite.line((15, bottom - 7), (20, bottom - 1), LEMON)
        sprite.line((11, bottom - 8), (14, bottom - 2), GOLD)
    if frame >= 2:
        rx, ry = [(5, 2), (9, 3), (12, 4), (14, 4)][frame - 2]
        sprite.path(ellipse(16, 23, rx, ry), AMBER)
        sprite.path(ellipse(16, 23, rx, ry, 15, 165), GOLD)
        if frame < 5:
            sprite.path(ellipse(16, 22, rx - 2, 1, 180, 360), GOLD)
            sprite.star(16, 21, 3 if frame == 2 else 1, LEMON)
        for dx, dy in [(-2, 1), (1, 5), (3, 2), (-3, 4)]:
            x = 16 + dx * (frame - 1)
            y = 21 - dy - 2 * (frame - 2)
            sprite.dot(x, y, GOLD)
            if frame in (2, 3):
                sprite.dot(x + 1, y + 1, AMBER)


def spear(sprite, frame):
    # The leading point extends along a single axis, leaving two slim wakes.
    tail, tip, width = [(4, 15, 2), (4, 22, 3), (3, 28, 4),
                        (8, 29, 3), (15, 29, 2), (20, 29, 1)][frame]
    sprite.polygon([(tail, 17), (tip - 7, 15 - width), (tip, 15),
                    (tip - 8, 17 + width)], JADE)
    sprite.polygon([(tail + 2, 17), (tip - 5, 14), (tip, 15), (tip - 6, 17)], MINT)
    if frame < 4:
        sprite.line((tail + 4, 16), (tip, 15), WHITE)
        sprite.line((tail + 3, 12), (tip - 5, 11), MINT)
        sprite.line((tail, 21), (tip - 8, 20), JADE)


def bow(sprite, frame):
    # A rising arrow travels away from its release flash and leaves dashed streaks.
    tip_x, tip_y = [(14, 17), (19, 15), (24, 13), (28, 11), (29, 10), (29, 10)][frame]
    tail_x, tail_y = tip_x - 11, tip_y + 4
    if frame < 4:
        sprite.path([(tail_x, tail_y), (tip_x - 5, tip_y + 2), (tip_x, tip_y)], GOLD)
        sprite.path([(tip_x - 4, tip_y - 1), (tip_x, tip_y), (tip_x - 3, tip_y + 3)], LEMON)
        sprite.line((tail_x - 1, tail_y - 2), (tail_x + 2, tail_y - 1), AMBER)
        sprite.line((tail_x + 2, tail_y - 1), (tail_x, tail_y + 2), AMBER)
    if frame < 2:
        sprite.star(5, 18, 2 + frame, LEMON)
    if frame > 0:
        sprite.line((tail_x - min(tail_x - 2, 6), tail_y + 2), (tail_x - 2, tail_y + 1), AMBER)
        sprite.line((tip_x - 9, tip_y - 2), (tip_x - 5, tip_y - 3), GOLD)
        if frame >= 3:
            sprite.line((tip_x - 5, tip_y + 3), (tip_x, tip_y + 1), LEMON)


def wand(sprite, frame):
    radius = (2, 4, 7, 5, 3, 1)[frame]
    x, y = 15, 15
    sprite.polygon([(x, y - radius), (x + 2, y - 2), (x + radius, y),
                    (x + 2, y + 2), (x, y + radius), (x - 2, y + 2),
                    (x - radius, y), (x - 2, y - 2)], MAGENTA)
    sprite.star(x, y, radius, PINK)
    sprite.star(x, y, max(0, radius - 2), WHITE)
    for dx, dy in [(-3, -2), (4, -2), (3, 3), (-4, 3), (1, -4)]:
        x = 15 + dx * (frame + 2) // 2
        y = 15 + dy * (frame + 2) // 2
        sprite.star(x, y, 1 if 1 <= frame <= 3 else 0, PINK)


def staff(sprite, frame):
    # The inner rune stays centered while two concentric rings open around it.
    rx, ry = [(4, 3), (7, 5), (10, 8), (12, 10), (13, 11), (14, 12)][frame]
    sprite.path(ellipse(15, 15, rx, ry), VIOLET)
    sprite.path(ellipse(15, 15, rx - 2, ry - 2), LILAC)
    if frame < 5:
        sprite.path([(15, 15 - ry), (15 + rx - 2, 15), (15, 15 + ry),
                     (17 - rx, 15), (15, 15 - ry)], LILAC)
        sprite.star(15, 15, 1 + frame % 3, WHITE)
    for angle in (45, 135, 225, 315):
        x, y = ellipse(15, 15, rx, ry, angle, angle)[0]
        sprite.star(x, y, 1 if frame in (2, 3) else 0, GOLD)


STYLES = (sword, hammer, spear, bow, wand, staff)


def write_png(path: Path, width: int, height: int, pixels: bytes) -> None:
    if len(pixels) != width * height * 4:
        raise ValueError(f"{path} has an invalid RGBA buffer")

    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    rows = b"".join(b"\0" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows, level=9))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(png)


def main() -> None:
    JAVA_TEXTURE.parent.mkdir(parents=True, exist_ok=True)
    BEDROCK_TEXTURES.mkdir(parents=True, exist_ok=True)
    atlas_width = SIZE * len(STYLES)
    atlas_height = SIZE * (FRAME_COUNT + 1)
    atlas = bytearray(atlas_width * atlas_height * 4)
    for column, draw in enumerate(STYLES):
        frames = []
        for frame, opacity in enumerate((205, 255, 255, 230, 164, 92)):
            sprite = Sprite(opacity)
            draw(sprite, frame)
            frames.append(sprite.pixels)

        # Keep the power-of-two Bedrock texture height with two blank cells.
        bedrock_height = SIZE * 8
        write_png(BEDROCK_TEXTURES / f"{draw.__name__}.png", SIZE, bedrock_height,
                  b"".join(frames) + bytes(SIZE * (bedrock_height - SIZE * FRAME_COUNT) * 4))

        # Row zero retains the existing glyphs for clients using the original IDs.
        for row, pixels in enumerate([frames[2], *frames]):
            for y in range(SIZE):
                start = y * SIZE * 4
                dest = ((row * SIZE + y) * atlas_width + column * SIZE) * 4
                atlas[dest:dest + SIZE * 4] = pixels[start:start + SIZE * 4]
            # Bitmap glyph advance uses the rightmost nontransparent pixel.
            # Alpha 1 fixes the advance while staying below the text shader cutoff.
            anchor = ((row * SIZE) * atlas_width + column * SIZE + SIZE - 1) * 4
            atlas[anchor + 3] = 1

    write_png(JAVA_TEXTURE, atlas_width, atlas_height, atlas)


if __name__ == "__main__":
    main()
