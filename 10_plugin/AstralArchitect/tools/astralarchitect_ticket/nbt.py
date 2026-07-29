"""Small, lossless NBT reader/writer used by the ticket CLI.

The implementation covers every tag type from the Java Edition NBT format.
Unknown compounds and metadata are represented as generic :class:`Tag` values
and are written back unchanged when a schematic is edited.
"""

from __future__ import annotations

from dataclasses import dataclass
import gzip
import io
from pathlib import Path
import struct
from typing import BinaryIO


TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12

_VALID_TYPES = set(range(TAG_END, TAG_LONG_ARRAY + 1))


class NbtError(ValueError):
    """Raised when an NBT stream is malformed or exceeds a safety limit."""


@dataclass(slots=True)
class ListValue:
    """NBT list payload, including its element type."""

    element_type: int
    items: list["Tag"]


@dataclass(slots=True)
class Tag:
    """A typed NBT payload."""

    type_id: int
    value: object


@dataclass(slots=True)
class Document:
    """An NBT document's named root tag."""

    root_name: str
    root: Tag


class _Reader:
    def __init__(self, stream: BinaryIO, *, max_depth: int, max_elements: int) -> None:
        self.stream = stream
        self.max_depth = max_depth
        self.max_elements = max_elements

    def read_exact(self, length: int) -> bytes:
        if length < 0:
            raise NbtError("negative byte length")
        data = self.stream.read(length)
        if len(data) != length:
            raise NbtError("unexpected end of NBT data")
        return data

    def unpack(self, fmt: str) -> object:
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.read_exact(size))[0]

    def read_string(self) -> str:
        length = int(self.unpack(">H"))
        try:
            return _decode_modified_utf8(self.read_exact(length))
        except UnicodeError as exc:
            raise NbtError("invalid modified UTF-8 string in NBT data") from exc

    def checked_length(self, value: int, label: str) -> int:
        if value < 0:
            raise NbtError(f"negative {label} length")
        if value > self.max_elements:
            raise NbtError(f"{label} length exceeds safety limit")
        return value

    def read_payload(self, type_id: int, depth: int) -> Tag:
        if depth > self.max_depth:
            raise NbtError("NBT nesting exceeds safety limit")
        if type_id == TAG_BYTE:
            return Tag(type_id, int(self.unpack(">b")))
        if type_id == TAG_SHORT:
            return Tag(type_id, int(self.unpack(">h")))
        if type_id == TAG_INT:
            return Tag(type_id, int(self.unpack(">i")))
        if type_id == TAG_LONG:
            return Tag(type_id, int(self.unpack(">q")))
        if type_id == TAG_FLOAT:
            return Tag(type_id, float(self.unpack(">f")))
        if type_id == TAG_DOUBLE:
            return Tag(type_id, float(self.unpack(">d")))
        if type_id == TAG_BYTE_ARRAY:
            length = self.checked_length(int(self.unpack(">i")), "byte array")
            return Tag(type_id, self.read_exact(length))
        if type_id == TAG_STRING:
            return Tag(type_id, self.read_string())
        if type_id == TAG_LIST:
            element_type = int(self.unpack(">B"))
            if element_type not in _VALID_TYPES:
                raise NbtError(f"unknown NBT list element type: {element_type}")
            length = self.checked_length(int(self.unpack(">i")), "list")
            if element_type == TAG_END and length != 0:
                raise NbtError("non-empty TAG_End list")
            return Tag(
                type_id,
                ListValue(
                    element_type,
                    [self.read_payload(element_type, depth + 1) for _ in range(length)],
                ),
            )
        if type_id == TAG_COMPOUND:
            entries: dict[str, Tag] = {}
            while True:
                child_type = int(self.unpack(">B"))
                if child_type == TAG_END:
                    break
                if child_type not in _VALID_TYPES:
                    raise NbtError(f"unknown NBT tag type: {child_type}")
                name = self.read_string()
                if name in entries:
                    raise NbtError(f"duplicate compound key: {name}")
                if len(entries) >= self.max_elements:
                    raise NbtError("compound entry count exceeds safety limit")
                entries[name] = self.read_payload(child_type, depth + 1)
            return Tag(type_id, entries)
        if type_id == TAG_INT_ARRAY:
            length = self.checked_length(int(self.unpack(">i")), "int array")
            return Tag(type_id, [int(self.unpack(">i")) for _ in range(length)])
        if type_id == TAG_LONG_ARRAY:
            length = self.checked_length(int(self.unpack(">i")), "long array")
            return Tag(type_id, [int(self.unpack(">q")) for _ in range(length)])
        raise NbtError(f"unsupported NBT tag type: {type_id}")


class _Writer:
    def __init__(self, stream: BinaryIO) -> None:
        self.stream = stream

    def pack(self, fmt: str, value: object) -> None:
        try:
            self.stream.write(struct.pack(fmt, value))
        except (struct.error, TypeError) as exc:
            raise NbtError(f"NBT value is outside {fmt} range") from exc

    def write_string(self, value: object) -> None:
        if not isinstance(value, str):
            raise NbtError("NBT string payload is not a string")
        encoded = _encode_modified_utf8(value)
        if len(encoded) > 0xFFFF:
            raise NbtError("NBT string is too long")
        self.pack(">H", len(encoded))
        self.stream.write(encoded)

    def write_payload(self, tag: Tag) -> None:
        type_id = tag.type_id
        value = tag.value
        if type_id == TAG_BYTE:
            self.pack(">b", value)
        elif type_id == TAG_SHORT:
            self.pack(">h", value)
        elif type_id == TAG_INT:
            self.pack(">i", value)
        elif type_id == TAG_LONG:
            self.pack(">q", value)
        elif type_id == TAG_FLOAT:
            self.pack(">f", value)
        elif type_id == TAG_DOUBLE:
            self.pack(">d", value)
        elif type_id == TAG_BYTE_ARRAY:
            if not isinstance(value, (bytes, bytearray)):
                raise NbtError("NBT byte array payload is not bytes")
            self.pack(">i", len(value))
            self.stream.write(value)
        elif type_id == TAG_STRING:
            self.write_string(value)
        elif type_id == TAG_LIST:
            if not isinstance(value, ListValue):
                raise NbtError("NBT list payload is invalid")
            if value.element_type not in _VALID_TYPES:
                raise NbtError("NBT list element type is invalid")
            if value.element_type == TAG_END and value.items:
                raise NbtError("non-empty TAG_End list")
            self.pack(">B", value.element_type)
            self.pack(">i", len(value.items))
            for item in value.items:
                if item.type_id != value.element_type:
                    raise NbtError("NBT list contains a mismatched tag type")
                self.write_payload(item)
        elif type_id == TAG_COMPOUND:
            if not isinstance(value, dict):
                raise NbtError("NBT compound payload is invalid")
            for name, child in value.items():
                if not isinstance(child, Tag) or child.type_id == TAG_END:
                    raise NbtError("NBT compound child is invalid")
                self.pack(">B", child.type_id)
                self.write_string(name)
                self.write_payload(child)
            self.pack(">B", TAG_END)
        elif type_id == TAG_INT_ARRAY:
            if not isinstance(value, list):
                raise NbtError("NBT int array payload is invalid")
            self.pack(">i", len(value))
            for item in value:
                self.pack(">i", item)
        elif type_id == TAG_LONG_ARRAY:
            if not isinstance(value, list):
                raise NbtError("NBT long array payload is invalid")
            self.pack(">i", len(value))
            for item in value:
                self.pack(">q", item)
        else:
            raise NbtError(f"unsupported NBT tag type: {type_id}")


def _decode_modified_utf8(data: bytes) -> str:
    """Decode the Java modified UTF-8 used by DataInput.readUTF."""

    units: list[int] = []
    index = 0
    while index < len(data):
        first = data[index]
        if first <= 0x7F:
            units.append(first)
            index += 1
            continue
        if first & 0xE0 == 0xC0:
            if index + 1 >= len(data):
                raise UnicodeDecodeError("mutf-8", data, index, len(data), "incomplete sequence")
            second = data[index + 1]
            if second & 0xC0 != 0x80:
                raise UnicodeDecodeError("mutf-8", data, index, index + 2, "bad continuation")
            unit = ((first & 0x1F) << 6) | (second & 0x3F)
            if unit != 0 and unit < 0x80:
                raise UnicodeDecodeError("mutf-8", data, index, index + 2, "overlong sequence")
            units.append(unit)
            index += 2
            continue
        if first & 0xF0 == 0xE0:
            if index + 2 >= len(data):
                raise UnicodeDecodeError("mutf-8", data, index, len(data), "incomplete sequence")
            second, third = data[index + 1], data[index + 2]
            if second & 0xC0 != 0x80 or third & 0xC0 != 0x80:
                raise UnicodeDecodeError("mutf-8", data, index, index + 3, "bad continuation")
            unit = ((first & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F)
            if unit < 0x800:
                raise UnicodeDecodeError("mutf-8", data, index, index + 3, "overlong sequence")
            units.append(unit)
            index += 3
            continue
        raise UnicodeDecodeError("mutf-8", data, index, index + 1, "invalid leading byte")

    characters: list[str] = []
    index = 0
    while index < len(units):
        unit = units[index]
        if 0xD800 <= unit <= 0xDBFF and index + 1 < len(units):
            second = units[index + 1]
            if 0xDC00 <= second <= 0xDFFF:
                characters.append(chr(0x10000 + ((unit - 0xD800) << 10) + (second - 0xDC00)))
                index += 2
                continue
        characters.append(chr(unit))
        index += 1
    return "".join(characters)


def _encode_modified_utf8(value: str) -> bytes:
    """Encode a Python string as Java DataOutput.writeUTF payload bytes."""

    units: list[int] = []
    for character in value:
        codepoint = ord(character)
        if codepoint <= 0xFFFF:
            units.append(codepoint)
        else:
            codepoint -= 0x10000
            units.extend((0xD800 | (codepoint >> 10), 0xDC00 | (codepoint & 0x3FF)))

    output = bytearray()
    for unit in units:
        if 0x0001 <= unit <= 0x007F:
            output.append(unit)
        elif unit == 0 or unit <= 0x07FF:
            output.extend((0xC0 | (unit >> 6), 0x80 | (unit & 0x3F)))
        else:
            output.extend(
                (
                    0xE0 | (unit >> 12),
                    0x80 | ((unit >> 6) & 0x3F),
                    0x80 | (unit & 0x3F),
                )
            )
    return bytes(output)


def loads(data: bytes, *, max_depth: int = 64, max_elements: int = 20_000_000) -> Document:
    """Parse one uncompressed named NBT document."""

    stream = io.BytesIO(data)
    reader = _Reader(stream, max_depth=max_depth, max_elements=max_elements)
    root_type = int(reader.unpack(">B"))
    if root_type != TAG_COMPOUND:
        raise NbtError("NBT root must be a compound")
    root_name = reader.read_string()
    root = reader.read_payload(root_type, 0)
    if stream.read(1):
        raise NbtError("trailing bytes after NBT root")
    return Document(root_name, root)


def dumps(document: Document) -> bytes:
    """Serialize one uncompressed named NBT document."""

    if document.root.type_id != TAG_COMPOUND:
        raise NbtError("NBT root must be a compound")
    stream = io.BytesIO()
    writer = _Writer(stream)
    writer.pack(">B", document.root.type_id)
    writer.write_string(document.root_name)
    writer.write_payload(document.root)
    return stream.getvalue()


def read_gzip(path: Path, *, max_uncompressed_bytes: int) -> Document:
    """Read a gzip-compressed NBT file with a decompressed size limit."""

    if max_uncompressed_bytes <= 0:
        raise NbtError("invalid decompressed size limit")
    try:
        with gzip.open(path, "rb") as stream:
            data = stream.read(max_uncompressed_bytes + 1)
    except (OSError, EOFError) as exc:
        raise NbtError("file is not a valid gzip-compressed NBT document") from exc
    if len(data) > max_uncompressed_bytes:
        raise NbtError("decompressed NBT exceeds safety limit")
    return loads(data)


def write_gzip(path: Path, document: Document) -> None:
    """Write deterministic gzip-compressed NBT to *path*."""

    data = dumps(document)
    with path.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as stream:
            stream.write(data)
        raw.flush()
        # Ensure the replacement file reaches disk before os.replace is called.
        import os

        os.fsync(raw.fileno())
