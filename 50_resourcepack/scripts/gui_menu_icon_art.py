"""Draw original high-contrast menu symbols on the 32px distribution grid."""

from __future__ import annotations

from math import ceil


Pixel = tuple[int, int, int, int]
Point = tuple[int, int]
SIZE = 32

TRANSPARENT = (0, 0, 0, 0)
INK = (17, 26, 39, 255)
STEEL_LIGHT = (228, 243, 247, 255)
STEEL = (164, 193, 212, 255)
STEEL_SHADE = (88, 123, 152, 255)
STONE_LIGHT = (187, 211, 208, 255)
STONE = (130, 161, 166, 255)
STONE_SHADE = (72, 105, 117, 255)
BLUE_LIGHT = (152, 218, 255, 255)
BLUE = (84, 166, 226, 255)
BLUE_SHADE = (44, 95, 165, 255)
TEAL_LIGHT = (188, 254, 223, 255)
TEAL = (94, 227, 196, 255)
TEAL_SHADE = (46, 139, 144, 255)
LAVENDER = (190, 149, 242, 255)
VIOLET = (115, 75, 170, 255)
RED_LIGHT = (255, 164, 133, 255)
RED = (204, 73, 85, 255)
RED_SHADE = (130, 39, 67, 255)
GOLD_LIGHT = (255, 228, 153, 255)
GOLD = (244, 182, 77, 255)
GOLD_SHADE = (157, 99, 45, 255)
PARCHMENT = (255, 238, 187, 255)
PARCHMENT_SHADE = (214, 178, 118, 255)
LEATHER_LIGHT = (210, 151, 95, 255)
LEATHER = (166, 107, 68, 255)
LEATHER_SHADE = (101, 63, 49, 255)


class Canvas:
    def __init__(self) -> None:
        self.pixels = [[TRANSPARENT] * SIZE for _ in range(SIZE)]

    def rectangle(self, left: int, top: int, right: int, bottom: int, color: Pixel) -> None:
        for y in range(top, bottom):
            self.pixels[y][left:right] = [color] * (right - left)

    def ellipse(self, left: int, top: int, right: int, bottom: int, color: Pixel) -> None:
        width = right - left
        height = bottom - top
        for y in range(top, bottom):
            for x in range(left, right):
                dx = (2 * (x - left) + 1 - width) / width
                dy = (2 * (y - top) + 1 - height) / height
                if dx * dx + dy * dy <= 1:
                    self.pixels[y][x] = color

    def polygon(self, points: tuple[Point, ...], color: Pixel) -> None:
        edges = tuple(zip(points, points[1:] + points[:1]))
        for y in range(min(point[1] for point in points), max(point[1] for point in points)):
            scan_y = y + 0.5
            intersections = sorted(
                x1 + (x2 - x1) * (scan_y - y1) / (y2 - y1)
                for (x1, y1), (x2, y2) in edges
                if min(y1, y2) <= scan_y < max(y1, y2)
            )
            for left, right in zip(intersections[::2], intersections[1::2]):
                start, end = ceil(left - 0.5), ceil(right - 0.5)
                self.pixels[y][start:end] = [color] * (end - start)

    def outlined(self) -> list[list[Pixel]]:
        # Two-pixel contour separates the pale artwork from bright slot themes.
        pixels = [[TRANSPARENT] * SIZE for _ in range(SIZE)]
        for y, row in enumerate(self.pixels):
            for x, color in enumerate(row):
                if not color[3]:
                    continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        if dx * dx + dy * dy <= 4 and 0 <= x + dx < SIZE and 0 <= y + dy < SIZE:
                            pixels[y + dy][x + dx] = INK
        for y, row in enumerate(self.pixels):
            for x, color in enumerate(row):
                if color[3]:
                    pixels[y][x] = color
        return pixels


def equipment(canvas: Canvas) -> None:
    canvas.polygon((
        (9, 6), (13, 6), (16, 10), (19, 6), (23, 6),
        (25, 18), (22, 25), (20, 28), (12, 28), (10, 25), (7, 18),
    ), STEEL_SHADE)
    canvas.polygon(((10, 10), (14, 11), (16, 14), (16, 24), (12, 24), (9, 18)), STEEL_LIGHT)
    canvas.polygon(((16, 14), (18, 11), (22, 10), (23, 18), (20, 24), (16, 24)), STEEL)
    canvas.polygon(((7, 4), (12, 4), (14, 8), (10, 15), (5, 15), (2, 11), (3, 7)), STEEL_SHADE)
    canvas.polygon(((7, 4), (11, 4), (12, 7), (9, 11), (4, 11), (3, 8)), STEEL)
    canvas.polygon(((7, 4), (11, 4), (7, 8), (3, 8)), STEEL_LIGHT)
    canvas.polygon(((20, 4), (25, 4), (29, 7), (30, 11), (27, 15), (22, 15), (18, 8)), STEEL_SHADE)
    canvas.polygon(((21, 4), (25, 4), (28, 7), (27, 11), (23, 11), (20, 7)), STEEL)
    canvas.rectangle(13, 25, 19, 28, GOLD)


def skill(canvas: Canvas) -> None:
    canvas.polygon((
        (16, 3), (25, 3), (27, 6), (21, 13), (27, 13),
        (25, 18), (8, 29), (11, 21), (5, 21), (4, 17),
    ), TEAL_SHADE)
    canvas.polygon(((16, 3), (25, 3), (19, 14), (26, 14),
                    (9, 28), (15, 18), (5, 18)), TEAL)
    canvas.polygon(((16, 3), (21, 3), (13, 15), (18, 15),
                    (9, 27), (13, 18), (5, 18)), TEAL_LIGHT)


def currency(canvas: Canvas) -> None:
    for y in (19, 12, 5):
        canvas.polygon(((3, y), (29, y), (29, y + 5), (24, y + 9),
                        (8, y + 9), (3, y + 5)), GOLD_SHADE)
        canvas.polygon(((3, y + 1), (9, y + 3), (22, y + 3), (21, y + 8),
                        (9, y + 8), (3, y + 5)), GOLD)
        canvas.polygon(((9, y - 2), (23, y - 2), (29, y + 1), (28, y + 4),
                        (23, y + 6), (9, y + 6), (4, y + 4), (3, y + 1)), GOLD_LIGHT)
    canvas.polygon(((12, 5), (21, 5), (24, 7), (20, 9), (12, 9), (8, 7)), GOLD)


def settings(canvas: Canvas) -> None:
    canvas.polygon((
        (12, 3), (20, 3), (20, 7), (22, 8), (25, 5), (28, 8),
        (25, 11), (26, 13), (29, 13), (29, 19), (26, 19), (25, 21),
        (28, 24), (25, 27), (22, 24), (20, 25), (20, 29), (12, 29),
        (12, 25), (10, 24), (7, 27), (4, 24), (7, 21), (6, 19),
        (3, 19), (3, 13), (6, 13), (7, 11), (4, 8), (7, 5),
        (10, 8), (12, 7),
    ), STEEL)
    canvas.polygon((
        (28, 8), (25, 11), (26, 13), (29, 13), (29, 19), (26, 19),
        (25, 21), (28, 24), (25, 27), (22, 24), (20, 25), (20, 29),
        (12, 29), (12, 25), (10, 24), (7, 27), (4, 24), (10, 22),
        (16, 21), (23, 16), (24, 10),
    ), STEEL_SHADE)
    canvas.polygon(((12, 3), (20, 3), (20, 6), (14, 7), (12, 11),
                    (8, 13), (7, 10), (4, 8), (7, 5), (10, 8), (12, 7)), STEEL_LIGHT)
    canvas.ellipse(9, 9, 23, 23, STEEL_SHADE)
    canvas.ellipse(11, 11, 21, 21, INK)


def adventure(canvas: Canvas) -> None:
    canvas.polygon(((11, 3), (21, 3), (27, 8), (29, 13), (29, 20), (24, 27),
                    (20, 29), (12, 29), (5, 24), (3, 20), (3, 11), (8, 5)), GOLD_SHADE)
    canvas.polygon(((11, 3), (21, 3), (27, 8), (28, 18), (24, 24), (19, 27),
                    (11, 27), (5, 23), (3, 19), (3, 11), (8, 5)), GOLD)
    canvas.polygon(((11, 3), (21, 3), (24, 5), (15, 5), (8, 8), (5, 16), (3, 18),
                    (3, 11), (8, 5)), GOLD_LIGHT)
    canvas.polygon(((11, 7), (20, 7), (24, 11), (25, 19), (20, 24), (12, 24),
                    (7, 20), (7, 12)), BLUE_SHADE)
    canvas.polygon(((11, 7), (20, 7), (24, 11), (12, 23), (7, 20), (7, 12)), BLUE)
    canvas.polygon(((22, 9), (19, 19), (11, 23), (13, 13)), INK)
    canvas.polygon(((22, 9), (19, 18), (14, 13)), PARCHMENT)
    canvas.polygon(((14, 15), (18, 19), (11, 23)), RED_LIGHT)


def guide(canvas: Canvas) -> None:
    canvas.polygon(((2, 7), (8, 6), (16, 10), (24, 6), (30, 7), (30, 26),
                    (24, 26), (16, 29), (8, 26), (2, 26)), LEATHER_SHADE)
    canvas.polygon(((3, 6), (8, 5), (15, 8), (15, 26), (8, 23), (3, 24)), PARCHMENT_SHADE)
    canvas.polygon(((3, 6), (8, 5), (13, 7), (13, 22), (8, 20), (3, 21)), PARCHMENT)
    canvas.polygon(((17, 8), (24, 5), (29, 6), (29, 24), (24, 23), (17, 26)), PARCHMENT_SHADE)
    canvas.polygon(((19, 8), (24, 5), (29, 6), (29, 21), (24, 20), (19, 22)), PARCHMENT)
    canvas.polygon(((5, 11), (9, 11), (11, 12), (11, 15), (9, 14), (5, 14)), LEATHER_SHADE)
    canvas.polygon(((21, 12), (24, 11), (27, 11), (27, 14), (24, 14), (21, 15)), LEATHER_SHADE)
    canvas.polygon(((14, 24), (18, 24), (18, 30), (16, 28), (14, 30)), RED)


def quest(canvas: Canvas) -> None:
    canvas.polygon(((8, 3), (24, 3), (24, 29), (16, 23), (8, 29)), RED_SHADE)
    canvas.polygon(((8, 3), (21, 3), (21, 25), (16, 22), (8, 28)), RED)
    canvas.polygon(((8, 3), (21, 3), (21, 6), (11, 6), (11, 25), (8, 28)), RED_LIGHT)
    canvas.rectangle(14, 8, 18, 15, PARCHMENT)
    canvas.rectangle(14, 17, 18, 21, PARCHMENT)


def party(canvas: Canvas) -> None:
    canvas.polygon(((19, 6), (25, 4), (28, 7), (28, 12), (26, 15), (20, 15), (18, 12)), BLUE_SHADE)
    canvas.polygon(((19, 6), (25, 4), (28, 7), (25, 9), (25, 13), (19, 13)), BLUE_LIGHT)
    canvas.rectangle(20, 9, 25, 13, PARCHMENT)
    canvas.polygon(((18, 20), (21, 17), (26, 17), (29, 20), (29, 26), (18, 26)), BLUE_SHADE)
    canvas.polygon(((18, 20), (21, 17), (26, 17), (26, 24), (18, 24)), BLUE)
    canvas.polygon(((6, 7), (12, 5), (16, 8), (16, 14), (13, 17), (7, 17), (5, 14), (5, 9)), VIOLET)
    canvas.polygon(((6, 7), (12, 5), (15, 8), (12, 10), (12, 15), (5, 14), (5, 9)), LAVENDER)
    canvas.rectangle(7, 10, 13, 14, PARCHMENT)
    canvas.polygon(((3, 22), (7, 19), (13, 19), (17, 22), (17, 28), (3, 28)), VIOLET)
    canvas.polygon(((3, 22), (7, 19), (13, 19), (14, 25), (3, 25)), LAVENDER)


def players(canvas: Canvas) -> None:
    canvas.polygon(((7, 3), (22, 3), (27, 7), (27, 26), (24, 29), (6, 29),
                    (4, 26), (4, 6)), BLUE_SHADE)
    canvas.polygon(((8, 5), (22, 5), (25, 8), (25, 26), (8, 26)), PARCHMENT_SHADE)
    canvas.polygon(((9, 5), (22, 5), (24, 7), (24, 24), (9, 24)), PARCHMENT)
    canvas.polygon(((7, 3), (11, 3), (11, 27), (6, 27), (4, 25), (4, 6)), BLUE)
    canvas.rectangle(6, 6, 9, 10, GOLD_LIGHT)
    canvas.rectangle(6, 21, 9, 25, GOLD)
    for y in (6, 16):
        canvas.ellipse(13, y, 18, y + 4, LEATHER_SHADE)
        canvas.polygon(((12, y + 8), (14, y + 6), (18, y + 6), (20, y + 8), (20, y + 9), (12, y + 9)),
                       LEATHER_SHADE)
        canvas.rectangle(21, y + 6, 25, y + 8, BLUE_SHADE)


def mail(canvas: Canvas) -> None:
    canvas.polygon(((5, 6), (27, 6), (29, 8), (29, 24), (27, 26),
                    (5, 26), (3, 24), (3, 8)), LEATHER)
    canvas.polygon(((5, 6), (27, 6), (28, 8), (28, 22), (26, 24),
                    (5, 24), (3, 22), (3, 8)), PARCHMENT_SHADE)
    canvas.polygon(((3, 8), (16, 17), (28, 8), (28, 11), (16, 20), (3, 11)), LEATHER_SHADE)
    canvas.polygon(((5, 6), (27, 6), (28, 8), (16, 16), (3, 8)), PARCHMENT)
    canvas.polygon(((14, 15), (18, 15), (20, 17), (20, 21), (18, 23),
                    (14, 23), (12, 21), (12, 17)), RED_SHADE)
    canvas.polygon(((14, 15), (18, 15), (19, 17), (19, 19),
                    (17, 21), (13, 21), (12, 19), (12, 17)), RED)
    canvas.rectangle(14, 16, 17, 19, RED_LIGHT)


def warp(canvas: Canvas) -> None:
    canvas.polygon(((13, 3), (25, 3), (29, 7), (29, 26), (26, 29), (14, 29),
                    (14, 24), (23, 24), (23, 9), (13, 9)), STONE_SHADE)
    canvas.polygon(((13, 3), (25, 3), (27, 6), (27, 24), (23, 26), (14, 26),
                    (14, 24), (23, 24), (23, 8), (13, 8)), STONE)
    canvas.polygon(((13, 3), (25, 3), (27, 6), (13, 6)), STONE_LIGHT)
    canvas.rectangle(25, 9, 28, 14, STONE_LIGHT)
    canvas.rectangle(25, 16, 28, 21, STONE)
    canvas.polygon(((3, 13), (13, 13), (13, 8), (24, 16), (13, 24),
                    (13, 19), (3, 19)), TEAL_SHADE)
    canvas.polygon(((3, 13), (13, 13), (13, 8), (23, 15), (13, 21),
                    (13, 18), (3, 18)), TEAL)
    canvas.polygon(((3, 13), (13, 13), (13, 8), (22, 14), (15, 13),
                    (15, 16), (3, 16)), TEAL_LIGHT)


def item_protection(canvas: Canvas) -> None:
    canvas.polygon(((8, 9), (12, 3), (20, 3), (24, 9), (24, 20), (8, 20)), STEEL_SHADE)
    canvas.polygon(((8, 9), (12, 3), (20, 3), (23, 7), (19, 8),
                    (14, 8), (11, 11), (11, 17), (8, 17)), STEEL_LIGHT)
    canvas.polygon(((12, 10), (14, 7), (18, 7), (20, 10), (20, 17), (12, 17)), TRANSPARENT)
    canvas.polygon(((7, 13), (25, 13), (27, 15), (27, 27),
                    (25, 29), (7, 29), (5, 27), (5, 15)), GOLD_SHADE)
    canvas.polygon(((7, 13), (24, 13), (25, 15), (25, 25),
                    (23, 27), (7, 27), (5, 25), (5, 15)), GOLD)
    canvas.polygon(((7, 13), (24, 13), (25, 15), (9, 17), (9, 25), (5, 25), (5, 15)), GOLD_LIGHT)
    canvas.ellipse(13, 17, 19, 23, INK)
    canvas.rectangle(14, 21, 18, 26, INK)


def trash(canvas: Canvas) -> None:
    canvas.polygon(((5, 12), (27, 12), (24, 29), (8, 29)), LEATHER_SHADE)
    canvas.polygon(((6, 12), (19, 12), (19, 27), (9, 27)), LEATHER_LIGHT)
    canvas.polygon(((19, 12), (26, 12), (23, 27), (19, 27)), LEATHER)
    canvas.polygon(((11, 15), (14, 15), (15, 26), (12, 26)), LEATHER_SHADE)
    canvas.polygon(((19, 15), (22, 15), (21, 26), (18, 26)), LEATHER_SHADE)
    canvas.polygon(((10, 4), (21, 4), (21, 8), (25, 8), (29, 10),
                    (28, 14), (4, 14), (3, 10), (7, 8), (10, 8)), STONE_SHADE)
    canvas.polygon(((10, 4), (19, 4), (19, 8), (25, 8), (28, 10),
                    (4, 10), (7, 8), (10, 8)), STONE_LIGHT)
    canvas.polygon(((8, 24), (25, 24), (24, 28), (8, 28)), STONE)


MENU_ICON_DRAWINGS = {
    "equipment": equipment,
    "skill": skill,
    "currency": currency,
    "settings": settings,
    "adventure": adventure,
    "guide": guide,
    "quest": quest,
    "party": party,
    "players": players,
    "mail": mail,
    "warp": warp,
    "item_protection": item_protection,
    "trash": trash,
}


def menu_icon(role: str) -> list[list[Pixel]]:
    canvas = Canvas()
    MENU_ICON_DRAWINGS[role](canvas)
    return canvas.outlined()
