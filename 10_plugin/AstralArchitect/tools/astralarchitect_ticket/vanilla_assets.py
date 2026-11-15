"""Compile vanilla 1.21.11 blockstate and model definitions to textured quads."""

from __future__ import annotations

import json
from math import cos, isfinite, radians, sin
from pathlib import Path
import re
import struct
from zipfile import BadZipFile, ZipFile
import zlib

from .schematic import TicketError

_VERSION = "1.21.11"
_ASSETS = "assets/minecraft/"
_TEXTURES = _ASSETS + "textures/block/"
_TEXTURE_FILE = re.compile(r"(?:[a-z0-9_-]+/)*[a-z0-9_-]+\.png\Z")
_RESOURCE = re.compile(r"(?:minecraft:)?([a-z0-9_./-]+)\Z")
_DIRECTIONS = {
    "down": (0, -1, 0), "up": (0, 1, 0),
    "north": (0, 0, -1), "south": (0, 0, 1),
    "west": (-1, 0, 0), "east": (1, 0, 0),
}
# The model coordinates are east/up/south. These corners face outwards and
# start at the upper-left UV corner as seen from outside the corresponding face.
_CORNERS = {
    "down": ((0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)),
    "up": ((0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)),
    "north": ((1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)),
    "south": ((0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)),
    "west": ((0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)),
    "east": ((1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)),
}
_WHITE = (255, 255, 255)
_OPAQUE, _CUTOUT, _TRANSLUCENT = range(3)
_LAYERS = ("opaque", "cutout", "translucent")
_MAX_MEMBER = 8 * 1024 * 1024


def _resource_path(reference: str) -> str:
    match = _RESOURCE.fullmatch(reference)
    if not match or any(segment in {"", ".", ".."} for segment in match[1].split("/")):
        raise ValueError("invalid vanilla resource reference")
    return match[1]


def _condition(expression: dict, properties: dict[str, str]) -> bool:
    for name, expected in expression.items():
        if name == "OR":
            if not any(_condition(part, properties) for part in expected):
                return False
        elif name == "AND":
            if not all(_condition(part, properties) for part in expected):
                return False
        else:
            inverted = expected.startswith("!")
            options = expected.removeprefix("!").split("|")
            if (properties.get(name) in options) == inverted:
                return False
    return True


def _selected_models(blockstate: dict, properties: dict[str, str]) -> list[dict]:
    if "variants" in blockstate:
        partial = None
        for selector, model in blockstate["variants"].items():
            required = dict(pair.split("=", 1) for pair in selector.split(",") if pair)
            if all(properties.get(key) == value for key, value in required.items()):
                return [model[0] if isinstance(model, list) else model]
            if partial is None and all(key not in properties or properties[key] == value
                                       for key, value in required.items()):
                partial = model
        return [partial[0] if isinstance(partial, list) else partial] if partial is not None else []

    result = []
    for part in blockstate.get("multipart", []):
        if _condition(part.get("when", {}), properties):
            model = part["apply"]
            result.append(model[0] if isinstance(model, list) else model)
    return result


def _rotate(point: tuple, angles: tuple, origin: tuple = (0, 0, 0)) -> tuple:
    x, y, z = (coordinate - offset for coordinate, offset in zip(point, origin, strict=True))
    rx, ry, rz = angles
    if rx:
        c, s = cos(radians(rx)), sin(radians(rx))
        y, z = y * c - z * s, y * s + z * c
    if ry:
        c, s = cos(radians(ry)), sin(radians(ry))
        x, z = x * c + z * s, -x * s + z * c
    if rz:
        c, s = cos(radians(rz)), sin(radians(rz))
        x, y = x * c - y * s, x * s + y * c
    return x + origin[0], y + origin[1], z + origin[2]


def _rotate_direction(name: str, angles: tuple) -> str:
    vector = tuple(round(value) for value in _rotate(_DIRECTIONS[name], angles))
    return next(direction for direction, axis in _DIRECTIONS.items() if vector == axis)


def _element_transform(element: dict) -> tuple[tuple, tuple, tuple]:
    rotation = element.get("rotation", {})
    origin = tuple(rotation.get("origin", (8, 8, 8)))
    if "axis" in rotation:
        angles = tuple(rotation["angle"] if axis == rotation["axis"] else 0 for axis in "xyz")
    else:
        angles = tuple(rotation.get(axis, 0) for axis in "xyz")
    scale = [1.0, 1.0, 1.0]
    if rotation.get("rescale"):
        for axis, angle in enumerate(angles):
            component = abs(cos(radians(angle)))
            if angle and component > 1e-7:
                for other in range(3):
                    if other != axis:
                        scale[other] /= component
    return origin, angles, tuple(scale)


def _vertices(direction: str, lower: tuple, upper: tuple, transform: tuple, rotation: tuple) -> tuple:
    origin, angles, scale = transform
    vertices = []
    for corner in _CORNERS[direction]:
        point = tuple((upper if bit else lower)[axis] for axis, bit in enumerate(corner))
        if any(angles):
            point = _rotate(point, angles, origin)
            point = tuple(base + (value - base) * factor
                          for base, value, factor in zip(origin, point, scale, strict=True))
        if any(rotation):
            point = _rotate(point, rotation, (8, 8, 8))
        vertices.append(tuple(round(value / 16, 8) for value in point))
    return tuple(vertices)


def _uv_bounds(direction: str, lower: tuple, upper: tuple) -> tuple:
    x0, y0, z0 = lower
    x1, y1, z1 = upper
    return {
        "down": (x0, 16 - z1, x1, 16 - z0),
        "up": (x0, z0, x1, z1),
        "north": (16 - x1, 16 - y1, 16 - x0, 16 - y0),
        "south": (x0, 16 - y1, x1, 16 - y0),
        "west": (z0, 16 - y1, z1, 16 - y0),
        "east": (16 - z1, 16 - y1, 16 - z0, 16 - y0),
    }[direction]


def _uv_indices(direction: str, angles: tuple) -> tuple:
    target = _rotate_direction(direction, angles)
    target_corners = _CORNERS[target]
    return tuple(target_corners.index(tuple(round(value) for value in _rotate(source, angles, (0.5,) * 3)))
                 for source in _CORNERS[direction])


def _face_uv(face: dict, direction: str, lower: tuple, upper: tuple,
             rotation: tuple, locked: bool) -> tuple:
    bounds = face.get("uv")
    if bounds is None:
        bounds = _uv_bounds(direction, lower, upper)
    u0, v0, u1, v1 = (value / 16 for value in bounds)
    corners = ((u0, v0), (u0, v1), (u1, v1), (u1, v0))
    indices = _uv_indices(direction, rotation) if locked and any(rotation) else range(4)
    shift = face.get("rotation", 0) // 90
    return tuple(corners[(index + shift) % 4] for index in indices)


def _png_chunks(data: bytes):
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("invalid texture PNG")
    offset = 8
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        start = offset + 8
        if start + length + 4 > len(data):
            raise ValueError("truncated texture PNG")
        yield kind, data[start:start + length]
        offset = start + length + 4
        if kind == b"IEND":
            break


def _paeth(left: int, above: int, diagonal: int) -> int:
    candidate = left + above - diagonal
    dl, da, dd = abs(candidate - left), abs(candidate - above), abs(candidate - diagonal)
    if dl <= da and dl <= dd:
        return left
    return above if da <= dd else diagonal


def _png_rows(chunks: list, width: int, height: int, channels: int):
    stride = width * channels
    required = (stride + 1) * height
    if not 0 < required <= _MAX_MEMBER:
        raise ValueError("texture PNG dimensions exceed limit")
    inflater = zlib.decompressobj()
    raw = inflater.decompress(b"".join(value for kind, value in chunks if kind == b"IDAT"), required + 1)
    if not inflater.eof or len(raw) != required:
        raise ValueError("incorrect texture PNG data size")
    previous = bytearray(stride)
    for row in range(height):
        start = row * (stride + 1)
        filter_kind = raw[start]
        line = bytearray(raw[start + 1:start + stride + 1])
        if filter_kind not in range(5):
            raise ValueError("unknown texture PNG filter")
        if filter_kind:
            for index, value in enumerate(line):
                left = line[index - channels] if index >= channels else 0
                above = previous[index]
                if filter_kind == 1:
                    prediction = left
                elif filter_kind == 2:
                    prediction = above
                elif filter_kind == 3:
                    prediction = (left + above) // 2
                else:
                    diagonal = previous[index - channels] if index >= channels else 0
                    prediction = _paeth(left, above, diagonal)
                line[index] = (value + prediction) & 255
        previous = line
        yield line


def _alpha_kind(data: bytes) -> int:
    chunks = list(_png_chunks(data))
    width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", chunks[0][1])
    keyed = next((value for kind, value in chunks if kind == b"tRNS"), None)
    if color == 3:
        samples = set(keyed or ())
    elif color in (0, 2):
        samples = {0} if keyed else set()
    elif color in (4, 6) and depth == 8 and interlace == 0:
        channels = 2 if color == 4 else 4
        samples = set()
        for row in _png_rows(chunks, width, height, channels):
            samples.update(row[channels - 1::channels])
    else:
        return _CUTOUT
    if any(0 < value < 255 for value in samples):
        return _TRANSLUCENT
    return _CUTOUT if 0 in samples else _OPAQUE


class VanillaAssets:
    """Version-matched client JAR reader for local live view."""

    def __init__(self, jar: Path) -> None:
        try:
            self._archive = ZipFile(jar)
        except (OSError, BadZipFile) as error:
            raise TicketError(f"cannot open the Minecraft Java {_VERSION} client JAR") from error
        try:
            version = self._json("version.json")
            self._entries = sorted({
                entry.filename for entry in self._archive.infolist()
                if entry.filename.startswith(_TEXTURES)
                and _TEXTURE_FILE.fullmatch(entry.filename[len(_TEXTURES):])
                and entry.file_size <= _MAX_MEMBER
            })
            if version.get("id") != _VERSION or not self._entries:
                raise ValueError("incorrect version or missing block textures")
        except (KeyError, OSError, ValueError, TypeError, AttributeError, RuntimeError,
                BadZipFile, zlib.error) as error:
            self._archive.close()
            raise TicketError(f"expected a Minecraft Java {_VERSION} client JAR with block assets") from error
        self._texture_ids = {path: index for index, path in enumerate(self._entries)}
        self._texture_alpha: dict[int, int] = {}
        self._blockstates: dict[str, dict] = {}
        self._templates: dict[str, tuple[list, dict]] = {}
        self._models: dict[str, dict | None] = {}
        self._tints: dict[str, tuple] = {}

    def close(self) -> None:
        self._archive.close()

    def _read(self, path: str) -> bytes:
        entry = self._archive.getinfo(path)
        if entry.file_size > _MAX_MEMBER:
            raise ValueError("client JAR asset exceeds size limit")
        return self._archive.read(entry)

    def _json(self, path: str) -> dict:
        return json.loads(self._read(path))

    def texture(self, index: int) -> bytes | None:
        if 0 <= index < len(self._entries):
            try:
                return self._read(self._entries[index])
            except (OSError, ValueError, RuntimeError, BadZipFile, zlib.error):
                return None
        return None

    def _alpha(self, index: int) -> int:
        if index not in self._texture_alpha:
            self._texture_alpha[index] = _alpha_kind(self._read(self._entries[index]))
        return self._texture_alpha[index]

    def _template(self, reference: str, ancestry: tuple = ()) -> tuple[list, dict]:
        path = _resource_path(reference)
        if path in self._templates:
            return self._templates[path]
        if path in ancestry or len(ancestry) >= 32:
            raise ValueError("cyclic or too deep block model parent chain")
        value = self._json(f"{_ASSETS}models/{path}.json")
        parent = value.get("parent")
        elements, textures = self._template(parent, (*ancestry, path)) if parent else ([], {})
        result = value.get("elements", elements), {**textures, **value.get("textures", {})}
        self._templates[path] = result
        return result

    def _texture_id(self, reference: str, variables: dict) -> int:
        seen = set()
        while reference.startswith("#"):
            if reference in seen:
                raise ValueError("cyclic block texture reference")
            seen.add(reference)
            reference = variables[reference[1:]]
        return self._texture_ids[f"{_ASSETS}textures/{_resource_path(reference)}.png"]

    def _plains_tint(self, resource: str) -> tuple:
        if resource not in self._tints:
            chunks = list(_png_chunks(self._read(f"{_ASSETS}textures/colormap/{resource}.png")))
            header = struct.unpack(">IIBBBBB", chunks[0][1])
            if header != (256, 256, 8, 2, 0, 0, 0):
                raise ValueError("unexpected vanilla biome colormap")
            # The plains temperature is 0.8 and downfall is 0.4. Vanilla
            # multiplies rainfall by temperature before sampling the colormap.
            x, y = int((1 - 0.8) * 255), int((1 - 0.4 * 0.8) * 255)
            row = next(line for row_y, line in enumerate(_png_rows(chunks, 256, 256, 3)) if row_y == y)
            self._tints[resource] = tuple(row[x * 3:x * 3 + 3])
        return self._tints[resource]

    def _tint(self, block: str, face: dict) -> tuple:
        if face.get("tintindex", -1) < 0:
            return _WHITE
        if block == "spruce_leaves":
            return (97, 153, 97)
        if block == "birch_leaves":
            return (128, 167, 85)
        if block in {"grass_block", "short_grass", "tall_grass", "fern", "large_fern",
                     "potted_fern", "sugar_cane"}:
            return self._plains_tint("grass")
        if block.endswith("_leaves") or block in {"vine", "mangrove_propagule"}:
            return self._plains_tint("foliage")
        return _WHITE

    def _compile(self, state: str) -> dict | None:
        identifier, _, text = state.removesuffix("]").partition("[")
        if not identifier.startswith("minecraft:"):
            return None
        block = _resource_path(identifier)
        properties = dict(pair.split("=", 1) for pair in text.split(",") if pair)
        if block not in self._blockstates:
            self._blockstates[block] = self._json(f"{_ASSETS}blockstates/{block}.json")
        faces = []
        alpha = _OPAQUE
        solid_faces = set()
        for variant in _selected_models(self._blockstates[block], properties):
            elements, textures = self._template(variant["model"])
            angles = tuple(variant.get(axis, 0) for axis in "xyz")
            if any(type(angle) is not int or angle not in (0, 90, 180, 270) for angle in angles):
                raise ValueError("invalid blockstate rotation")
            rotation = tuple(-angle for angle in angles)
            locked = variant.get("uvlock", False)
            for element in elements:
                lower, upper = tuple(element["from"]), tuple(element["to"])
                transform = _element_transform(element)
                full_cube = lower == (0, 0, 0) and upper == (16, 16, 16) and not any(transform[1])
                for direction, face in element["faces"].items():
                    texture = self._texture_id(face["texture"], textures)
                    face_alpha = self._alpha(texture)
                    alpha = max(alpha, face_alpha)
                    cull = face.get("cullface")
                    if cull is not None:
                        cull = _rotate_direction(cull, rotation)
                    if full_cube and face_alpha == _OPAQUE and cull is not None:
                        solid_faces.add(cull)
                    vertices = _vertices(direction, lower, upper, transform, rotation)
                    uv = _face_uv(face, direction, lower, upper, rotation, locked)
                    if any(not isfinite(value) for point in (*vertices, *uv) for value in point):
                        raise ValueError("block model has a non-finite coordinate")
                    faces.append({
                        "vertices": vertices,
                        "uv": uv,
                        "texture": texture,
                        "cull": cull,
                        "tint": self._tint(block, face),
                        "shade": element.get("shade", True),
                    })
        if not faces:
            return None
        return {
            "state": state, "faces": faces, "layer": _LAYERS[alpha],
            "occludes": len(solid_faces) == 6,
        }

    def model(self, state: str) -> dict | None:
        if state not in self._models:
            try:
                self._models[state] = self._compile(state)
            except (KeyError, IndexError, OSError, ValueError, TypeError, AttributeError,
                    RuntimeError, OverflowError, BadZipFile, struct.error, zlib.error):
                # Air, fluids, and blocks with only special renderers may have
                # no mesh in the client block model files.
                self._models[state] = None
        return self._models[state]
