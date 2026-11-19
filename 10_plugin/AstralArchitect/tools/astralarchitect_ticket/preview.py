"""Render an approximate Minecraft block scene as a deterministic local PNG."""

from __future__ import annotations

from dataclasses import dataclass, replace
from functools import lru_cache
from hashlib import blake2b
from itertools import islice
import math
import struct
from typing import Sequence
import zlib

from .schematic import Bounds, Ticket, TicketError, is_air


Vector = tuple[float, float, float]
Box = tuple[float, float, float, float, float, float]
Color = tuple[int, int, int]
_CLAY_COLOR: Color = (160, 160, 160)
_CLAY_BACKGROUND: Color = (228, 228, 228)
_FULL: Box = (0, 0, 0, 1, 1, 1)
_CUBE = (_FULL,)
_DIRECTIONS = ("east", "south", "west", "north")
_MAX_IMAGE_EDGE = 1024
_MAX_CELL_VISITS = 50_000_000
_MAX_CAMERA_COORDINATE = 1_000_000_000
_CAMERA_FOV = math.tan(math.radians(24))
_EPSILON = 1e-7

_WOOD = {
    "oak": (165, 129, 83), "spruce": (110, 78, 48),
    "birch": (197, 180, 132), "jungle": (162, 116, 83),
    "acacia": (171, 91, 53), "dark_oak": (70, 47, 29),
    "mangrove": (120, 56, 55), "cherry": (213, 158, 153),
    "pale_oak": (207, 205, 192), "crimson": (108, 49, 72),
    "warped": (54, 115, 112), "bamboo": (198, 171, 84),
}
_DYE = {
    "white": (227, 228, 227), "orange": (218, 112, 30),
    "magenta": (175, 67, 163), "light_blue": (71, 165, 207),
    "yellow": (226, 185, 39), "lime": (122, 175, 36),
    "pink": (216, 119, 156), "gray": (73, 77, 81),
    "light_gray": (153, 155, 148), "cyan": (32, 136, 149),
    "purple": (123, 61, 171), "blue": (56, 72, 162),
    "brown": (117, 78, 52), "green": (83, 110, 40),
    "red": (164, 52, 46), "black": (37, 37, 42),
}
_MATERIALS = {
    "stone": (133, 134, 133), "smooth_stone": (153, 153, 151),
    "cobblestone": (115, 117, 114), "mossy_cobblestone": (105, 120, 91),
    "stone_brick": (120, 122, 120), "mossy_stone_brick": (105, 122, 94),
    "cracked_stone_brick": (114, 114, 110),
    "deepslate": (72, 72, 79), "cobbled_deepslate": (69, 68, 74),
    "polished_deepslate": (75, 74, 81), "deepslate_brick": (70, 70, 78),
    "deepslate_tile": (55, 55, 64),
    "blackstone": (52, 48, 56), "polished_blackstone": (58, 52, 61),
    "polished_blackstone_brick": (56, 51, 59),
    "tuff": (108, 111, 100), "polished_tuff": (113, 118, 106), "tuff_brick": (105, 112, 103),
    "brick": (161, 90, 75), "mud_brick": (154, 126, 94),
    "nether_brick": (60, 33, 41), "red_nether_brick": (129, 37, 42),
    "granite": (152, 105, 88), "polished_granite": (161, 111, 97),
    "andesite": (137, 137, 140), "polished_andesite": (139, 140, 145),
    "diorite": (170, 168, 166), "polished_diorite": (193, 189, 188),
    "quartz": (225, 220, 208), "smooth_quartz": (232, 225, 213),
    "calcite": (222, 219, 211), "end_stone_brick": (218, 222, 160),
    "sandstone": (215, 197, 146), "smooth_sandstone": (220, 200, 150),
    "red_sandstone": (184, 103, 48), "sand": (220, 206, 162),
    "gravel": (138, 134, 130), "dirt": (125, 91, 61),
    "coarse_dirt": (102, 78, 58), "rooted_dirt": (135, 102, 76),
    "dirt_path": (143, 111, 64), "grass_block": (101, 149, 60),
    "moss_block": (94, 116, 45), "moss_carpet": (94, 116, 45),
    "packed_mud": (130, 99, 75), "mud": (62, 57, 64),
    "terracotta": (153, 97, 73),
    "copper": (182, 102, 74), "cut_copper": (180, 98, 70),
    "exposed_cut_copper": (161, 125, 103), "weathered_cut_copper": (97, 150, 115),
    "oxidized_cut_copper": (72, 151, 129),
    "prismarine": (94, 157, 151), "prismarine_brick": (102, 163, 155),
    "dark_prismarine": (46, 86, 77),
    "yellow_wool": (218, 173, 37), "gold_block": (228, 187, 52),
    "iron_block": (211, 212, 206), "chain": (74, 77, 84), "iron_chain": (74, 77, 84),
    "lantern": (225, 149, 57), "soul_lantern": (87, 184, 198),
    "glowstone": (200, 154, 85), "sea_lantern": (172, 209, 203),
    "water": (57, 120, 185), "glass": (176, 204, 215),
    "tinted_glass": (99, 83, 118),
    "bookshelf": (139, 98, 59), "crafting_table": (133, 94, 61),
    "barrel": (115, 81, 53), "flower_pot": (153, 88, 72),
    "white_concrete": (215, 216, 208), "budding_amethyst": (126, 90, 168),
}


@dataclass(frozen=True, slots=True)
class BlockModel:
    boxes: tuple[Box, ...]
    color: Color
    pattern: str
    opacity: float = 1.0


def _orient(box: Box, facing: str) -> Box:
    x0, y0, z0, x1, y1, z1 = box
    if facing == "south":
        return 1 - z1, y0, x0, 1 - z0, y1, x1
    if facing == "west":
        return 1 - x1, y0, 1 - z1, 1 - x0, y1, 1 - z0
    if facing == "north":
        return z0, y0, 1 - x1, z1, y1, 1 - x0
    return box


def _stair_shape(properties: dict[str, str]) -> tuple[Box, ...]:
    lower_y = 0.0 if properties.get("half") == "top" else 0.5
    upper_y = lower_y + 0.5
    base_y = 0.5 - lower_y
    base: Box = (0, base_y, 0, 1, base_y + 0.5, 1)
    facing = properties.get("facing", "east")
    shape = properties.get("shape", "straight")
    turn_z = 0.0 if shape.endswith("_left") else 0.5
    if shape.startswith("outer_"):
        corner = (0.5, lower_y, turn_z, 1, upper_y, turn_z + 0.5)
        return base, _orient(corner, facing)
    rise = _orient((0.5, lower_y, 0, 1, upper_y, 1), facing)
    if shape.startswith("inner_"):
        corner = (0, lower_y, turn_z, 0.5, upper_y, turn_z + 0.5)
        return base, rise, _orient(corner, facing)
    return base, rise


def _connected_shape(name: str, properties: dict[str, str]) -> tuple[Box, ...]:
    boxes: list[Box] = []
    if name.endswith(("_pane", "iron_bars")):
        boxes.append((7/16, 0, 7/16, 9/16, 1, 9/16))
        branches: tuple[Box, ...] = ((9/16, 0, 7/16, 1, 1, 9/16),)
    elif name.endswith("_wall"):
        if properties.get("up", "true") == "true":
            boxes.append((4/16, 0, 4/16, 12/16, 1, 12/16))
        branches = ((0.5, 0, 5/16, 1, 13/16, 11/16),)
    else:
        boxes.append((6/16, 0, 6/16, 10/16, 1, 10/16))
        branches = ((10/16, 6/16, 7/16, 1, 9/16, 9/16), (10/16, 12/16, 7/16, 1, 15/16, 9/16))
    for direction in _DIRECTIONS:
        connection = properties.get(direction, "none")
        if connection not in {"none", "false"}:
            for box in branches:
                if name.endswith("_wall") and connection == "tall":
                    box = box[:4] + (1, box[5])
                boxes.append(_orient(box, direction))
    return tuple(boxes)


def _block_shape(name: str, properties: dict[str, str]) -> tuple[Box, ...]:
    if name.endswith("_stairs"):
        return _stair_shape(properties)
    if name.endswith("_slab"):
        slab = properties.get("type", "bottom")
        if slab == "double":
            return _CUBE
        base = 0.5 if slab == "top" else 0
        return ((0, base, 0, 1, base + 0.5, 1),)
    if name.endswith(("_pane", "_wall", "_fence")) or name == "iron_bars":
        return _connected_shape(name, properties)
    if name.endswith("_door"):
        facing = properties.get("facing", "east")
        if properties.get("open") == "true":
            turn = 1 if properties.get("hinge") == "right" else -1
            facing = _DIRECTIONS[(_DIRECTIONS.index(facing) + turn) % 4] if facing in _DIRECTIONS else "east"
        return (_orient((0, 0, 0, 3/16, 1, 1), facing),)
    if name.endswith("_trapdoor"):
        if properties.get("open") == "true":
            return (_orient((1 - 3/16, 0, 0, 1, 1, 1), properties.get("facing", "east")),)
        top = properties.get("half") == "top"
        return ((0, 1 - 3/16 if top else 0, 0, 1, 1 if top else 3/16, 1),)
    if name.endswith("_carpet"):
        return ((0, 0, 0, 1, 1/16, 1),)
    if name in {"dirt_path", "farmland"}:
        return ((0, 0, 0, 1, 15/16, 1),)
    if name in {"lantern", "soul_lantern"}:
        offset = 1/16 if properties.get("hanging") == "true" else 0
        return ((5/16, offset, 5/16, 11/16, 7/16 + offset, 11/16),
                (6/16, 7/16 + offset, 6/16, 10/16, 9/16 + offset, 10/16))
    if name in {"chain", "iron_chain"}:
        axis = properties.get("axis", "y")
        if axis == "x":
            return ((0, 6/16, 6/16, 1, 10/16, 10/16),)
        if axis == "z":
            return ((6/16, 6/16, 0, 10/16, 10/16, 1),)
        return ((6/16, 0, 6/16, 10/16, 1, 10/16),)
    return _CUBE


def _material(name: str) -> tuple[Color, str]:
    stem = name.removeprefix("waxed_")
    suffix = next((suffix for suffix in ("_stairs", "_slab", "_wall", "_fence", "_trapdoor", "_door")
                   if stem.endswith(suffix)), "")
    if suffix:
        stem = stem.removesuffix(suffix)
    if stem.endswith(("bricks", "tiles")):
        stem = stem[:-1]
    if stem in _MATERIALS:
        pattern = "brick" if stem.endswith(("brick", "tile")) or stem == "cobblestone" else "stone"
        return _MATERIALS[stem], pattern

    wood_stem = stem.removeprefix("stripped_")
    for wood, color in _WOOD.items():
        if wood_stem == wood or wood_stem in {wood + ending for ending in ("_planks", "_log", "_wood", "_stem", "_hyphae")}:
            if wood_stem.endswith(("_log", "_wood", "_stem", "_hyphae")):
                darken = 0.90 if stem.startswith("stripped_") else 0.66
                return (int(color[0] * darken), int(color[1] * darken), int(color[2] * darken)), "bark"
            return color, "wood"
    for dye, color in _DYE.items():
        if name in {dye + suffix for suffix in ("_stained_glass", "_stained_glass_pane", "_wool", "_carpet", "_terracotta", "_concrete")}:
            return color, "stone"
    if name.endswith("_leaves"):
        return (88, 131, 67), "stone"
    noise = blake2b(stem.encode(), digest_size=3).digest()
    return (93 + noise[0] % 74, 92 + noise[1] % 70, 82 + noise[2] % 67), "stone"


@lru_cache(maxsize=4096)
def block_model(state: str) -> BlockModel | None:
    """Return geometry and material shared by still and live previews."""
    if is_air(state):
        return None
    identifier, _, properties_text = state.removesuffix("]").partition("[")
    namespace, _, name = identifier.partition(":")
    if namespace != "minecraft":
        color, pattern = _material(identifier)
        return BlockModel(_CUBE, color, pattern)
    properties = dict(entry.split("=") for entry in properties_text.split(",") if entry)
    color, pattern = _material("glass" if name == "glass_pane" else name)
    if "glass" in name:
        return BlockModel(_block_shape(name, properties), color, "glass", 0.34)
    if name == "water":
        return BlockModel(((0, 0, 0, 1, 0.90, 1),), color, "water", 0.58)
    if name == "grass_block":
        return BlockModel(_CUBE, color, "grass")
    return BlockModel(_block_shape(name, properties), color, pattern)


def _dot(a: Vector, b: Vector) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _cross(a: Vector, b: Vector) -> Vector:
    return a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]


def _unit(vector: Vector) -> Vector:
    magnitude = math.sqrt(_dot(vector, vector))
    return vector[0] / magnitude, vector[1] / magnitude, vector[2] / magnitude


def _interval(origin: Vector, reciprocal: Vector, box: Box) -> tuple[float, float, int] | None:
    near = -math.inf
    far = math.inf
    face = -1
    for axis in range(3):
        position = origin[axis]
        inv = reciprocal[axis]
        if math.isinf(inv):
            if not box[axis] <= position < box[axis + 3]:
                return None
            continue
        a = (box[axis] - position) * inv
        b = (box[axis + 3] - position) * inv
        if inv < 0:
            a, b = b, a
        if a > near:
            near, face = a, axis
        far = min(far, b)
        if near >= far:
            return None
    return near, far, face


def _shade(block: BlockModel, axis: int, direction: Vector, point: Vector, x: int, y: int, z: int) -> Color:
    local_x, local_y, local_z = point
    if axis == 0:
        u, v = local_z, local_y
        light = 0.77 if direction[0] < 0 else 0.88
    elif axis == 1:
        u, v = local_x, local_z
        light = 1.12 if direction[1] < 0 else 0.61
    else:
        u, v = local_x, local_y
        light = 0.91 if direction[2] < 0 else 0.70

    if block.pattern == "clay":
        gray = int(block.color[0] * light)
        return gray, gray, gray

    iu, iv = int(u * 16), int(v * 16)
    noise = (iu * 43 ^ iv * 137 ^ x * 199 ^ y * 157 ^ z * 193) % 11
    light *= 0.96 + noise * 0.011
    if block.pattern == "brick":
        if iv % 8 == 0 or (iu + (iv // 8) * 8) % 16 == 0:
            light *= 0.82
    elif block.pattern == "wood":
        if iv % 4 == 0:
            light *= 0.83
    elif block.pattern == "bark":
        if iu % 5 in {0, 1}:
            light *= 0.86
    elif block.pattern == "glass":
        if iu in {0, 15} or iv in {0, 15} or (iu - iv) % 16 == 4:
            light *= 1.20
    elif iu in {0, 15} or iv in {0, 15}:
        light *= 0.93
    r, g, b = block.color
    if block.pattern == "grass" and ((axis != 1 and local_y < 0.82) or (axis == 1 and direction[1] > 0)):
        r, g, b = _MATERIALS["dirt"]
    return min(255, int(r * light)), min(255, int(g * light)), min(255, int(b * light))


def _nearest_block_face(
    block: BlockModel,
    eye: Vector,
    inv: Vector,
    x: int,
    y: int,
    z: int,
    entered: float,
    axis: int,
    leaves: float,
) -> tuple[float, int] | None:
    if block.boxes == _CUBE:
        return entered, axis
    local_eye = eye[0] - x, eye[1] - y, eye[2] - z
    nearest = None
    for box in block.boxes:
        hit = _interval(local_eye, inv, box)
        if hit is None:
            continue
        near, far, face = hit
        if far > entered and near < leaves and (nearest is None or near < nearest[0]):
            nearest = max(near, entered), face
    return nearest


def _trace(
    scene: Sequence[str],
    eye: Vector,
    ray: Vector,
    size: tuple[int, int, int],
    scene_box: Box,
    background: Color,
    *,
    clay_models: dict[str, BlockModel | None] | None = None,
) -> tuple[Color, int]:
    inv = tuple(1 / value if value else math.inf for value in ray)
    interval = _interval(eye, inv, scene_box)
    if interval is None or interval[1] <= 0:
        return background, 0
    entered, far, axis = interval
    entered = max(0.0, entered)
    starts = tuple(eye[i] + ray[i] * entered for i in range(3))
    # Start on the interior side of a cell boundary, including a ray entering
    # exactly at the ticket's maximum edge.
    x, y, z = (
        min(size[i] - 1, max(0, math.floor(math.nextafter(value, math.copysign(math.inf, ray[i])))))
        for i, value in enumerate(starts)
    )
    dx, dy, dz = (1 if value > 0 else -1 for value in ray)
    tx = (x + (dx > 0) - eye[0]) * inv[0] if ray[0] else math.inf
    ty = (y + (dy > 0) - eye[1]) * inv[1] if ray[1] else math.inf
    tz = (z + (dz > 0) - eye[2]) * inv[2] if ray[2] else math.inf
    rx, ry, rz = (abs(value) for value in inv)
    width, _, length = size
    plane = width * length
    carried_r = carried_g = carried_b = 0.0
    remaining = 1.0
    previous_transparent = None
    visited = 0
    while entered < far - _EPSILON / 2:
        visited += 1
        state = scene[x + width * z + plane * y]
        block = block_model(state) if clay_models is None else clay_models[state]
        leaves = min(tx, ty, tz)
        hit = None
        if block is not None and leaves - entered > _EPSILON / 2:
            hit = _nearest_block_face(block, eye, inv, x, y, z, entered, axis, leaves)
        if hit is None:
            previous_transparent = None
        elif state != previous_transparent:
            distance, face = hit
            point = (eye[0] + distance * ray[0] - x,
                     eye[1] + distance * ray[1] - y,
                     eye[2] + distance * ray[2] - z)
            r, g, b = _shade(block, face, ray, point, x, y, z)
            if block.opacity == 1.0 and remaining == 1.0:
                return (r, g, b), visited
            contribution = remaining * block.opacity
            carried_r += contribution * r
            carried_g += contribution * g
            carried_b += contribution * b
            remaining -= contribution
            if remaining < 1 / 255:
                return (int(carried_r), int(carried_g), int(carried_b)), visited
            previous_transparent = state
        entered = leaves
        if tx <= leaves:
            axis, x, tx = 0, x + dx, tx + rx
        if ty <= leaves:
            axis, y, ty = 1, y + dy, ty + ry
        if tz <= leaves:
            axis, z, tz = 2, z + dz, tz + rz
        if not (0 <= x < size[0] and 0 <= y < size[1] and 0 <= z < size[2]):
            break
    r, g, b = background
    return (
        int(carried_r + remaining * r),
        int(carried_g + remaining * g),
        int(carried_b + remaining * b),
    ), visited


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(data, zlib.crc32(tag)))


def _camera_vector(world_point: Vector, bounds: Bounds) -> Vector:
    if len(world_point) != 3 or any(not math.isfinite(value) or abs(value) > _MAX_CAMERA_COORDINATE for value in world_point):
        raise TicketError("preview camera coordinates must be finite and within ±1,000,000,000")
    minimum = bounds.minimum
    return world_point[0] - minimum.x, world_point[1] - minimum.y, world_point[2] - minimum.z


def render_preview(
    ticket: Ticket,
    scene: Sequence[str],
    *,
    eye: Vector,
    target: Vector,
    width: int,
    height: int,
    clip_y: int | None = None,
    style: str = "material",
) -> bytes:
    """Draw a material or clay still from a world-coordinate camera."""
    if style not in ("material", "clay"):
        raise TicketError("preview style must be material or clay")
    if not 1 <= width <= _MAX_IMAGE_EDGE or not 1 <= height <= _MAX_IMAGE_EDGE:
        raise TicketError(f"preview image width and height must be between 1 and {_MAX_IMAGE_EDGE}")
    bounds = ticket.bounds
    if len(scene) != bounds.volume:
        raise TicketError("preview block array has the wrong length")
    local_eye = _camera_vector(eye, bounds)
    local_target = _camera_vector(target, bounds)
    direction = (local_target[0] - local_eye[0], local_target[1] - local_eye[1], local_target[2] - local_eye[2])
    if _dot(direction, direction) < _EPSILON ** 2:
        raise TicketError("preview eye and target must be distinct")
    forward = _unit(direction)
    up_reference = (0.0, 0.0, 1.0) if math.hypot(forward[0], forward[2]) < _EPSILON else (0.0, 1.0, 0.0)
    right = _unit(_cross(forward, up_reference))
    up = _cross(right, forward)
    clip_height = bounds.height
    if clip_y is not None:
        clip_height = min(clip_height, clip_y - bounds.minimum.y + 1)
        if clip_height < 1:
            raise TicketError("preview clip Y is below the ticket")
    size = (bounds.width, clip_height, bounds.length)
    scene_box = (0.0, 0.0, 0.0, *size)
    clay_models = None
    if style == "clay":
        clay_models = {}
        cell_count = bounds.width * bounds.length * clip_height
        for state in set(islice(scene, cell_count)):
            model = block_model(state)
            clay_models[state] = None if model is None else replace(model, color=_CLAY_COLOR, pattern="clay")
    row_bytes = 1 + width * 3
    image = bytearray(row_bytes * height)
    columns = [((2 * column + 1) - width) / height * _CAMERA_FOV for column in range(width)]
    total_visited = 0
    for row in range(height):
        vertical = (height - (2 * row + 1)) / height * _CAMERA_FOV
        basis_x = forward[0] + up[0] * vertical
        basis_y = forward[1] + up[1] * vertical
        basis_z = forward[2] + up[2] * vertical
        if clay_models is None:
            background = (190 + row * 31 // height, 209 + row * 22 // height, 225 + row * 13 // height)
        else:
            background = _CLAY_BACKGROUND
        offset = row * row_bytes + 1
        for horizontal in columns:
            ray = basis_x + right[0] * horizontal, basis_y + right[1] * horizontal, basis_z + right[2] * horizontal
            (r, g, b), visited = _trace(
                scene, local_eye, ray, size, scene_box, background, clay_models=clay_models,
            )
            total_visited += visited
            if total_visited > _MAX_CELL_VISITS:
                raise TicketError("preview exceeds the rendering budget; reduce image size or set --clip-y")
            image[offset] = r
            image[offset + 1] = g
            image[offset + 2] = b
            offset += 3

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", header)
            + _png_chunk(b"IDAT", zlib.compress(image)) + _png_chunk(b"IEND", b""))
