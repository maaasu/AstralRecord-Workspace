from math import cos, pi, radians, sin
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
JAVA_TEXTURE = ROOT / "assets/astralrecord/textures/font/weapon_attack.png"
BEDROCK_TEXTURES = ROOT / "bedrock/textures/entity/weapon_attack"
SIZE = 32
FRAME_COUNT = 6

WHITE = (255, 255, 249)
SILVER = (205, 205, 201)
STEEL = (141, 140, 135)
GOLD = (255, 188, 69)
LEMON = (255, 240, 159)
BRONZE = (173, 100, 54)
STONE = (130, 118, 105)
PINK = (255, 154, 232)
MAGENTA = (205, 72, 191)
LILAC = (202, 153, 255)
VIOLET = (138, 77, 228)


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


# Offsets and velocities in sprite pixels. Sparse square chips echo vanilla crit
# particles and drift apart as the main shape dissolves.
BURST = (
    (-5, -4, -1, -1), (4, -3, 2, -1), (3, 4, 1, 1),
    (-4, 3, -2, 1), (0, -6, 0, -1), (6, 1, 1, 0),
    (-7, 1, -1, 0), (1, 6, 1, 1), (5, -6, 1, -1),
    (-2, 6, -1, 1), (-5, -6, -1, -1), (6, 4, 1, 1),
)


def chips(sprite, frame, center, palette, paths=BURST):
    cx, cy = center
    for i, (ox, oy, dx, dy) in enumerate(paths):
        if frame >= 4 and (i + frame) % 3 == 0:
            continue
        x, y = cx + ox + dx * frame, cy + oy + dy * frame
        color = palette[i % len(palette)]
        sprite.dot(x, y, color)
        if frame <= 2 and i % 3 == 0:
            sprite.star(x, y, 1, color)
        elif frame <= 3 and i % 3 == 1:
            sprite.dot(x + 1, y, color)


def flare(sprite, x, y, horizontal, vertical, rim, core=WHITE):
    sprite.polygon([
        (x - horizontal, y), (x - 2, y - 1), (x, y - vertical),
        (x + 1, y - 2), (x + horizontal, y), (x + 2, y + 1),
        (x, y + vertical), (x - 1, y + 2),
    ], rim)
    sprite.line((x - horizontal + 2, y), (x + horizontal - 2, y), core)
    sprite.line((x, y - vertical + 2), (x, y + vertical - 2), core)


def sword(sprite, frame):
    # Shallow, slanted sweep with a broad cutting edge and needle-thin ends.
    start, extent, width = [
        (-158, 80, 2), (-162, 133, 4), (-162, 156, 5),
        (-146, 147, 3), (-112, 106, 2), (-69, 76, 1),
    ][frame]
    for step in range(extent + 1):
        angle = radians(start + step)
        x = 16 + 14 * cos(angle)
        y = 19 + 10 * sin(angle) - (x - 16) / 4
        taper = width * sin(pi * step / extent)
        inner = (round(x - taper * cos(angle)), round(y - taper * sin(angle)))
        edge = (round(x), round(y))
        sprite.line(inner, edge, STEEL if frame >= 4 else SILVER)
        if 8 < step < extent - 6 and frame <= 3:
            sprite.dot(*edge, WHITE)
    chips(sprite, frame, (15, 16), (WHITE, GOLD, SILVER, LEMON))
    if frame in (1, 2):
        sprite.star(23, 7, 2, WHITE)


HAMMER_DEBRIS = (
    (-5, -3, -2, -2), (4, -4, 2, -2), (-2, -5, -1, -3),
    (2, -4, 1, -3), (-7, 0, -1, -1), (6, -1, 1, -1),
    (-4, 3, -2, 1), (4, 3, 2, 1),
)


def hammer(sprite, frame):
    rx, ry = [(5, 2), (8, 3), (10, 4), (12, 4), (13, 4), (14, 4)][frame]
    sprite.path(ellipse(16, 21, rx, ry, 195, 345), BRONZE)
    sprite.path(ellipse(16, 21, rx, ry, 5, 175), GOLD)
    if frame < 4:
        sprite.path(ellipse(16, 21, rx - 2, max(1, ry - 2), 25, 155), LEMON)
    if frame <= 2:
        flare(sprite, 16, 20, (7, 11, 7)[frame], (7, 10, 7)[frame], GOLD)
        sprite.line((13, 18), (8, 10), LEMON)
        sprite.line((18, 18), (24, 12), GOLD)
    chips(sprite, frame, (16, 21), (GOLD, STONE, LEMON, BRONZE), HAMMER_DEBRIS)
    for ox, oy, dx, dy in HAMMER_DEBRIS[:4]:
        x, y = 16 + ox + dx * frame, 21 + oy + dy * frame
        if frame < 4:
            sprite.polygon([(x, y - 1), (x + 2, y), (x + 1, y + 2), (x - 1, y + 1)], STONE)
            sprite.line((x - 1, y + 1), (x + 2, y), LEMON)


def spear(sprite, frame):
    # A narrow puncture flashes on its axis, surrounded by short crit wakes.
    tail, tip = [(6, 20), (4, 25), (2, 30), (6, 29), (11, 28), (15, 27)][frame]
    sprite.polygon([(tail, 17), (tip - 9, 14), (tip, 15), (tip - 9, 17)], SILVER)
    sprite.line((tail + 3, 16), (tip, 15), WHITE)
    sprite.line((tail + 2, 12), (tip - 8, 11), STEEL)
    sprite.line((tail + 1, 20), (tip - 6, 19), LEMON)
    if frame < 4:
        flare(sprite, 17 + frame, 15, 5, (3, 5, 7, 4)[frame], LEMON)
    chips(sprite, frame, (15, 15), (WHITE, LEMON, SILVER), BURST[:8])


def bow(sprite, frame):
    if frame < 4:
        flare(sprite, 15, 16, (7, 10, 8, 4)[frame], (4, 6, 5, 3)[frame], GOLD)
        sprite.line((4, 20), (11, 18), LEMON)
        sprite.line((21, 13), (27, 11), SILVER)
    chips(sprite, frame, (15, 15), (WHITE, GOLD, LEMON, SILVER))


def wand(sprite, frame):
    if frame < 5:
        radius = (4, 7, 10, 7, 4)[frame]
        flare(sprite, 15, 15, radius - 1, radius, MAGENTA)
        sprite.star(15, 15, radius - 2, PINK)
        sprite.star(15, 15, 2, WHITE)
    chips(sprite, frame, (15, 15), (PINK, WHITE, MAGENTA, LILAC))


def staff(sprite, frame):
    rx, ry = [(5, 4), (8, 6), (11, 8), (12, 10), (13, 11), (14, 12)][frame]
    sprite.path(ellipse(15, 15, rx, ry, 15, 165), GOLD)
    sprite.path(ellipse(15, 15, rx, ry, 195, 345), VIOLET)
    if frame < 4:
        sprite.path([(15, 15 - ry), (15 + rx, 15), (15, 15 + ry),
                     (15 - rx, 15), (15, 15 - ry)], LILAC)
        flare(sprite, 15, 15, 3 + frame, 3 + frame, GOLD)
    chips(sprite, frame, (15, 15), (GOLD, LILAC, WHITE, VIOLET))


def bow_trail(sprite, frame):
    if frame < 3:
        sprite.line((5, 18), (15, 15), GOLD)
        sprite.line((9, 17), (18, 14), WHITE)
        sprite.star(18, 14, 2, LEMON)
    chips(sprite, frame, (15, 15), (LEMON, WHITE, BRONZE, GOLD), BURST[:8])


def wand_trail(sprite, frame):
    if frame < 4:
        flare(sprite, 15, 15, 5 - frame, 5 - frame, PINK)
        sprite.line((6, 20), (11, 17), MAGENTA)
        sprite.line((19, 12), (23, 9), LILAC)
    chips(sprite, frame, (15, 15), (PINK, WHITE, MAGENTA, LILAC), BURST[:9])


def staff_trail(sprite, frame):
    if frame < 4:
        radius = (4, 6, 7, 8)[frame]
        sprite.path([(15 - radius, 14), (16, 15 - radius), (16 + radius, 15)], VIOLET)
        sprite.path([(15 - radius, 16), (16, 15 + radius), (16 + radius, 15)], GOLD)
        sprite.star(16, 15, 2, LILAC)
    chips(sprite, frame, (15, 15), (GOLD, VIOLET, WHITE, LILAC), BURST[2:])


def bow_impact(sprite, frame):
    if frame < 4:
        flare(sprite, 15, 15, (8, 11, 8, 5)[frame], (8, 10, 8, 5)[frame], LEMON)
        sprite.line((6, 22), (13, 17), GOLD)
        sprite.line((17, 13), (23, 7), WHITE)
    chips(sprite, frame, (15, 15), (WHITE, GOLD, LEMON, SILVER))


WAND_SCATTER = (
    (-2, -4, 0, -2), (2, -4, 1, -1), (4, 2, 2, 0),
    (5, 5, 1, 1), (-2, 5, 0, 2), (-4, 2, -2, 1),
    (-5, -2, -1, -1), (0, -7, 1, -1), (6, -1, 1, -1),
    (-4, 5, -1, 1), (-2, 8, 0, 1),
)


def wand_impact(sprite, frame):
    if frame < 4:
        radius = (6, 9, 11, 7)[frame]
        flare(sprite, 15, 15, radius, radius, MAGENTA)
        sprite.line((15 - radius, 15 - radius), (15 + radius, 15 + radius), PINK)
        sprite.line((15 - radius, 15 + radius), (15 + radius, 15 - radius), PINK)
        sprite.star(15, 15, 2, WHITE)
    chips(sprite, frame, (15, 15), (LILAC, PINK, WHITE, MAGENTA), WAND_SCATTER)


def staff_impact(sprite, frame):
    radius = (6, 9, 11, 12, 13, 14)[frame]
    for quadrant in range(0, 360, 90):
        turn = quadrant + frame * 5
        end = turn + (65 if frame < 4 else 45)
        sprite.path(ellipse(15, 15, radius, radius - 1, turn + 5, end), VIOLET)
        sprite.path(ellipse(15, 15, radius - 2, radius - 3, turn + 35, turn + 55), GOLD)
        if 2 <= frame <= 4:
            x, y = ellipse(15, 15, radius, radius - 1, end, end)[0]
            sprite.star(x, y, 1, LILAC)
    if frame < 4:
        flare(sprite, 15, 15, (5, 9, 7, 4)[frame], (7, 10, 8, 5)[frame], GOLD)
        sprite.path([(8, 15), (15, 8), (22, 15), (15, 22), (8, 15)], LILAC)
    chips(sprite, frame, (15, 15), (GOLD, LILAC, WHITE, VIOLET))


STYLES = (
    sword, hammer, spear, bow, wand, staff,
    bow_trail, wand_trail, staff_trail, bow_impact, wand_impact, staff_impact,
)
ATLAS_COLUMNS = 6
FRAME_OPACITIES = (235, 255, 255, 230, 190, 145)


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
    atlas_width = SIZE * ATLAS_COLUMNS
    atlas_height = SIZE * (1 + FRAME_COUNT * (len(STYLES) // ATLAS_COLUMNS))
    atlas = bytearray(atlas_width * atlas_height * 4)
    for style, draw in enumerate(STYLES):
        frames = []
        for frame, opacity in enumerate(FRAME_OPACITIES):
            sprite = Sprite(opacity)
            draw(sprite, frame)
            frames.append(sprite.pixels)

        # Keep the power-of-two Bedrock texture height with two blank cells.
        bedrock_height = SIZE * 8
        write_png(BEDROCK_TEXTURES / f"{draw.__name__}.png", SIZE, bedrock_height,
                  b"".join(frames) + bytes(SIZE * (bedrock_height - SIZE * FRAME_COUNT) * 4))

        column = style % ATLAS_COLUMNS
        first_row = 1 + FRAME_COUNT * (style // ATLAS_COLUMNS)
        cells = list(enumerate(frames, start=first_row))
        # Row zero retains the existing glyphs for clients using the original IDs.
        if style < ATLAS_COLUMNS:
            cells.insert(0, (0, frames[2]))
        for row, pixels in cells:
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
