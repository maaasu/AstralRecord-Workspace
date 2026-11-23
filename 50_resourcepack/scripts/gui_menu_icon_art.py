"""Draw original high-contrast menu symbols on the 32px distribution grid."""

from __future__ import annotations

from math import ceil


Pixel = tuple[int, int, int, int]
Point = tuple[int, int]
SIZE = 32

TRANSPARENT = (0, 0, 0, 0)
INK = (17, 26, 39, 255)
WHITE = (249, 253, 255, 255)
SILVER = (213, 229, 244, 255)
BLUE = (131, 209, 255, 255)
TEAL = (107, 244, 223, 255)
LAVENDER = (218, 180, 255, 255)
CORAL = (255, 134, 144, 255)
GOLD = (255, 202, 89, 255)
CREAM = (255, 239, 184, 255)
GREEN = (160, 233, 176, 255)


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
        (10, 5), (13, 5), (14, 8), (18, 8), (19, 5), (22, 5),
        (29, 10), (26, 17), (22, 15), (22, 27), (10, 27),
        (10, 15), (6, 17), (3, 10),
    ), WHITE)


def skill(canvas: Canvas) -> None:
    canvas.polygon((
        (17, 3), (26, 3), (20, 13), (27, 13), (8, 29),
        (13, 19), (5, 19),
    ), TEAL)


def currency(canvas: Canvas) -> None:
    canvas.ellipse(3, 18, 29, 29, GOLD)
    canvas.ellipse(3, 14, 29, 23, INK)
    canvas.ellipse(3, 11, 29, 20, GOLD)
    canvas.ellipse(3, 6, 29, 16, INK)
    canvas.ellipse(3, 3, 29, 13, CREAM)


def settings(canvas: Canvas) -> None:
    canvas.polygon((
        (12, 3), (20, 3), (20, 7), (22, 8), (25, 5), (28, 8),
        (25, 11), (26, 13), (29, 13), (29, 19), (26, 19), (25, 21),
        (28, 24), (25, 27), (22, 24), (20, 25), (20, 29), (12, 29),
        (12, 25), (10, 24), (7, 27), (4, 24), (7, 21), (6, 19),
        (3, 19), (3, 13), (6, 13), (7, 11), (4, 8), (7, 5),
        (10, 8), (12, 7),
    ), SILVER)
    canvas.ellipse(11, 11, 21, 21, INK)


def adventure(canvas: Canvas) -> None:
    canvas.ellipse(3, 3, 29, 29, GOLD)
    canvas.ellipse(7, 7, 25, 25, BLUE)
    canvas.polygon(((22, 10), (19, 19), (10, 22), (13, 13)), INK)
    canvas.polygon(((22, 10), (19, 19), (13, 13)), WHITE)


def guide(canvas: Canvas) -> None:
    canvas.polygon(((3, 6), (8, 5), (14, 8), (14, 27), (8, 24), (3, 25)), CREAM)
    canvas.polygon(((18, 8), (24, 5), (29, 6), (29, 25), (24, 24), (18, 27)), CREAM)
    canvas.rectangle(6, 11, 11, 14, INK)
    canvas.rectangle(21, 11, 26, 14, INK)
    canvas.rectangle(6, 16, 11, 19, INK)
    canvas.rectangle(21, 16, 26, 19, INK)


def quest(canvas: Canvas) -> None:
    canvas.polygon(((8, 3), (24, 3), (24, 29), (16, 23), (8, 29)), CORAL)
    canvas.rectangle(14, 7, 18, 15, INK)
    canvas.rectangle(14, 17, 18, 21, INK)


def party(canvas: Canvas) -> None:
    canvas.ellipse(18, 5, 28, 15, BLUE)
    canvas.polygon(((17, 20), (21, 17), (26, 17), (29, 20), (29, 25), (17, 25)), BLUE)
    canvas.ellipse(5, 6, 15, 16, LAVENDER)
    canvas.polygon(((3, 22), (7, 18), (13, 18), (17, 22), (17, 27), (3, 27)), LAVENDER)


def players(canvas: Canvas) -> None:
    canvas.polygon(((6, 3), (20, 3), (26, 9), (26, 29), (6, 29)), BLUE)
    for y in (8, 19):
        canvas.ellipse(10, y, 15, y + 5, INK)
        canvas.polygon(((8, y + 8), (10, y + 6), (15, y + 6), (17, y + 8), (17, y + 9), (8, y + 9)), INK)
        canvas.rectangle(19, y + 5, 24, y + 8, INK)


def mail(canvas: Canvas) -> None:
    canvas.polygon(((5, 6), (27, 6), (29, 8), (29, 24), (27, 26),
                    (5, 26), (3, 24), (3, 8)), CREAM)
    canvas.polygon(((4, 8), (16, 16), (28, 8), (28, 12), (16, 20), (4, 12)), INK)


def warp(canvas: Canvas) -> None:
    canvas.rectangle(16, 3, 29, 7, WHITE)
    canvas.rectangle(25, 7, 29, 25, WHITE)
    canvas.rectangle(16, 25, 29, 29, WHITE)
    canvas.polygon(((3, 13), (14, 13), (14, 8), (24, 16), (14, 24), (14, 19), (3, 19)), TEAL)


def item_protection(canvas: Canvas) -> None:
    canvas.ellipse(8, 3, 24, 22, GOLD)
    canvas.ellipse(12, 7, 20, 19, TRANSPARENT)
    canvas.polygon(((7, 13), (25, 13), (27, 15), (27, 27),
                    (25, 29), (7, 29), (5, 27), (5, 15)), GOLD)
    canvas.ellipse(13, 17, 19, 23, INK)
    canvas.rectangle(14, 21, 18, 26, INK)


def trash(canvas: Canvas) -> None:
    canvas.polygon(((5, 12), (27, 12), (24, 29), (8, 29)), GREEN)
    canvas.rectangle(3, 7, 29, 11, SILVER)
    canvas.rectangle(12, 3, 20, 7, SILVER)
    canvas.rectangle(10, 16, 14, 25, INK)
    canvas.rectangle(18, 16, 22, 25, INK)


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
