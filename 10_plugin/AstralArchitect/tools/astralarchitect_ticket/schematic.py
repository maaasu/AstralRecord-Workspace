"""Validated AstralArchitect ticket and Sponge Schematic v3 operations."""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
from typing import Iterable, Iterator

from . import nbt


class TicketError(ValueError):
    """Raised when a ticket or requested edit violates the file contract."""


_BLOCK_STATE = re.compile(
    r"^[a-z0-9_.-]+:[a-z0-9_./-]+(?:\[[a-z0-9_]+=[a-z0-9_.-]+(?:,[a-z0-9_]+=[a-z0-9_.-]+)*\])?$"
)
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_TICKET_ID = re.compile(r"^[a-z0-9][a-z0-9-]{7,79}$")
_EDITABLE_STATES = {"CREATED", "READY", "ROLLED_BACK"}
_MIN_OPERATION_VISIT_BUDGET = 5_000_000


@dataclass(frozen=True, slots=True)
class Point:
    x: int
    y: int
    z: int


@dataclass(frozen=True, slots=True)
class Bounds:
    minimum: Point
    maximum: Point

    @property
    def width(self) -> int:
        return self.maximum.x - self.minimum.x + 1

    @property
    def height(self) -> int:
        return self.maximum.y - self.minimum.y + 1

    @property
    def length(self) -> int:
        return self.maximum.z - self.minimum.z + 1

    @property
    def volume(self) -> int:
        return self.width * self.height * self.length

    def contains(self, point: Point) -> bool:
        return (
            self.minimum.x <= point.x <= self.maximum.x
            and self.minimum.y <= point.y <= self.maximum.y
            and self.minimum.z <= point.z <= self.maximum.z
        )


@dataclass(slots=True)
class Schematic:
    """A generic NBT document plus decoded block palette indices."""

    document: nbt.Document
    schematic_compound: dict[str, nbt.Tag]
    blocks_compound: dict[str, nbt.Tag]
    width: int
    height: int
    length: int
    palette: dict[str, int]
    block_ids: list[int]
    block_entity_indices: set[int]

    @property
    def volume(self) -> int:
        return self.width * self.height * self.length

    def index(self, local_x: int, local_y: int, local_z: int) -> int:
        return local_x + local_z * self.width + local_y * self.width * self.length

    def state_by_index(self, index: int) -> str:
        reverse = {palette_id: state for state, palette_id in self.palette.items()}
        try:
            return reverse[self.block_ids[index]]
        except KeyError as exc:
            raise TicketError(f"block data references missing palette id {self.block_ids[index]}") from exc

    def states(self) -> list[str]:
        reverse = {palette_id: state for state, palette_id in self.palette.items()}
        try:
            return [reverse[palette_id] for palette_id in self.block_ids]
        except KeyError as exc:
            raise TicketError(f"block data references missing palette id {exc.args[0]}") from exc

    def set_states(self, states: list[str]) -> None:
        if len(states) != self.volume:
            raise TicketError("replacement block array has the wrong length")
        next_id = max(self.palette.values(), default=-1) + 1
        for state_name in dict.fromkeys(states):
            if state_name not in self.palette:
                self.palette[state_name] = next_id
                next_id += 1

        palette_tag = self.blocks_compound["Palette"]
        palette_tag.value.clear()
        for state_name, palette_id in self.palette.items():
            palette_tag.value[state_name] = nbt.Tag(nbt.TAG_INT, palette_id)

        self.block_ids = [self.palette[state_name] for state_name in states]
        self.blocks_compound["Data"].value = encode_varints(self.block_ids)

        palette_max = self.blocks_compound.get("PaletteMax")
        if palette_max is not None:
            if palette_max.type_id not in {nbt.TAG_INT, nbt.TAG_SHORT}:
                raise TicketError("Blocks.PaletteMax has an invalid tag type")
            palette_max.value = max(self.palette.values(), default=-1) + 1


@dataclass(slots=True)
class Ticket:
    directory: Path
    metadata: dict[str, object]
    bounds: Bounds
    source_path: Path
    candidate_path: Path
    source_hash: str
    candidate_hash: str
    source: Schematic
    candidate: Schematic


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


@contextmanager
def candidate_lock(directory: Path) -> Iterator[None]:
    """Acquire the cross-process lock shared with the Java plugin."""

    lock_directory = directory.parent.parent / ".locks"
    reject_reparse_chain(lock_directory, "candidate lock directory")
    if not lock_directory.is_dir() or _is_reparse_point(lock_directory):
        raise TicketError("plugin-managed candidate lock directory is missing or invalid")
    lock_path = lock_directory / f"{directory.name}.lock"
    _regular_file(lock_path, "candidate lock")
    before_open = lock_path.lstat()
    flags = os.O_RDWR
    no_follow = getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(lock_path, flags | no_follow)
    except OSError as exc:
        raise TicketError("cannot open the candidate edit lock") from exc

    stream = os.fdopen(descriptor, "r+b", buffering=0)
    locked = False
    try:
        opened = os.fstat(stream.fileno())
        after_open = lock_path.lstat()
        if _is_reparse_point(lock_path) or not stat.S_ISREG(opened.st_mode):
            raise TicketError("candidate lock must remain a regular non-link file")
        if opened.st_size < 1:
            raise TicketError("candidate lock file is not initialized")
        if getattr(opened, "st_nlink", 1) != 1:
            raise TicketError("candidate lock must not be a hard link")
        before_identity = (before_open.st_dev, before_open.st_ino)
        opened_identity = (opened.st_dev, opened.st_ino)
        after_identity = (after_open.st_dev, after_open.st_ino)
        if before_identity != opened_identity or opened_identity != after_identity:
            raise TicketError("candidate lock changed while it was being opened")
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.lockf(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 0)
            locked = True
        except OSError as exc:
            raise TicketError("candidate.schem is being edited or validated by another process") from exc
        yield
    finally:
        if locked:
            try:
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt

                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl

                    fcntl.lockf(stream.fileno(), fcntl.LOCK_UN, 1, 0)
            except OSError:
                pass
        stream.close()


def _is_reparse_point(path: Path) -> bool:
    try:
        result = path.lstat()
    except OSError as exc:
        raise TicketError(f"cannot inspect path: {path}") from exc
    attributes = getattr(result, "st_file_attributes", 0)
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return path.is_symlink() or bool(attributes & reparse_flag)


def is_reparse_point(path: Path) -> bool:
    """Return whether *path* is a symlink, junction, or other reparse point."""

    return _is_reparse_point(path)


def reject_reparse_chain(path: Path, label: str) -> None:
    current = path
    while True:
        if _is_reparse_point(current):
            raise TicketError(f"{label} must not pass through a symlink or junction")
        parent = current.parent
        if parent == current:
            return
        current = parent


def _regular_file(path: Path, label: str) -> None:
    if not path.exists() or not path.is_file() or _is_reparse_point(path):
        raise TicketError(f"{label} must be a regular non-link file")


def _assert_no_reparse_points(path: Path) -> None:
    current = Path(path.anchor)
    parts = path.parts[1:] if path.anchor else path.parts
    for part in parts:
        current = current / part
        if current.exists() and _is_reparse_point(current):
            raise TicketError(f"symbolic links and reparse points are not allowed: {current}")


def _read_json_file(path: Path) -> dict[str, object]:
    _regular_file(path, "ticket.json")
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise TicketError("cannot read ticket.json") from exc
    if len(raw) > 1024 * 1024:
        raise TicketError("ticket.json exceeds 1 MiB")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise TicketError("ticket.json is not valid UTF-8 JSON") from exc
    if not isinstance(value, dict):
        raise TicketError("ticket.json root must be an object")
    return value


def _json_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TicketError(f"{label} must be an integer")
    return value


def _point(value: object, label: str) -> Point:
    if not isinstance(value, dict):
        raise TicketError(f"{label} must be an object")
    return Point(
        _json_int(value.get("x"), f"{label}.x"),
        _json_int(value.get("y"), f"{label}.y"),
        _json_int(value.get("z"), f"{label}.z"),
    )


def _metadata_bounds(metadata: dict[str, object]) -> Bounds:
    raw_bounds = metadata.get("bounds")
    if not isinstance(raw_bounds, dict):
        raise TicketError("ticket.json bounds must be an object")
    bounds = Bounds(_point(raw_bounds.get("min"), "bounds.min"), _point(raw_bounds.get("max"), "bounds.max"))
    if bounds.width <= 0 or bounds.height <= 0 or bounds.length <= 0:
        raise TicketError("ticket bounds are inverted")
    if bounds.volume > 20_000_000:
        raise TicketError("ticket volume exceeds CLI safety limit")
    if _json_int(metadata.get("blockCount"), "blockCount") != bounds.volume:
        raise TicketError("ticket blockCount does not match bounds")
    return bounds


def _compound(tag: nbt.Tag | None, label: str) -> dict[str, nbt.Tag]:
    if tag is None or tag.type_id != nbt.TAG_COMPOUND or not isinstance(tag.value, dict):
        raise TicketError(f"{label} must be an NBT compound")
    return tag.value


def _number(compound: dict[str, nbt.Tag], key: str, allowed: set[int]) -> int:
    tag = compound.get(key)
    if tag is None or tag.type_id not in allowed or isinstance(tag.value, bool) or not isinstance(tag.value, int):
        raise TicketError(f"Schematic.{key} has an invalid tag type")
    return tag.value


def _dimension(compound: dict[str, nbt.Tag], key: str) -> int:
    tag = compound.get(key)
    value = _number(compound, key, {nbt.TAG_SHORT, nbt.TAG_INT})
    # Sponge v3 dimensions are unsigned 16-bit values even though NBT only has
    # a signed TAG_Short representation. WorldEdit writes 32768..65535 by
    # casting them to a Java short.
    if tag is not None and tag.type_id == nbt.TAG_SHORT:
        value &= 0xFFFF
    if value <= 0:
        raise TicketError(f"Schematic.{key} must be positive")
    return value


def decode_varints(data: bytes, expected_count: int) -> list[int]:
    values: list[int] = []
    value = 0
    shift = 0
    for byte in data:
        value |= (byte & 0x7F) << shift
        if byte & 0x80:
            shift += 7
            if shift >= 35:
                raise TicketError("Blocks.Data contains an overlong VarInt")
            continue
        if value > 0x7FFFFFFF:
            raise TicketError("Blocks.Data contains a negative or oversized palette id")
        values.append(value)
        if len(values) > expected_count:
            raise TicketError("Blocks.Data contains more blocks than the dimensions")
        value = 0
        shift = 0
    if shift:
        raise TicketError("Blocks.Data ends in an incomplete VarInt")
    if len(values) != expected_count:
        raise TicketError("Blocks.Data count does not match the dimensions")
    return values


def encode_varints(values: Iterable[int]) -> bytes:
    output = bytearray()
    for raw_value in values:
        if raw_value < 0 or raw_value > 0x7FFFFFFF:
            raise TicketError("palette id is outside the supported VarInt range")
        value = raw_value
        while True:
            byte = value & 0x7F
            value >>= 7
            if value:
                output.append(byte | 0x80)
            else:
                output.append(byte)
                break
    return bytes(output)


def _extract_block_entity_indices(blocks: dict[str, nbt.Tag], width: int, height: int, length: int) -> set[int]:
    entities_tag = blocks.get("BlockEntities")
    if entities_tag is None:
        return set()
    if entities_tag.type_id != nbt.TAG_LIST or not isinstance(entities_tag.value, nbt.ListValue):
        raise TicketError("Blocks.BlockEntities must be an NBT list")
    if entities_tag.value.element_type != nbt.TAG_COMPOUND:
        if entities_tag.value.items:
            raise TicketError("Blocks.BlockEntities list must contain compounds")
        return set()
    indices: set[int] = set()
    for entity_tag in entities_tag.value.items:
        entity = _compound(entity_tag, "block entity")
        position = entity.get("Pos")
        if position is None or position.type_id != nbt.TAG_INT_ARRAY or not isinstance(position.value, list) or len(position.value) != 3:
            raise TicketError("block entity Pos must be an int array of length 3")
        x, y, z = position.value
        if not all(isinstance(item, int) and not isinstance(item, bool) for item in (x, y, z)):
            raise TicketError("block entity Pos contains a non-integer")
        if not (0 <= x < width and 0 <= y < height and 0 <= z < length):
            raise TicketError("block entity Pos is outside schematic dimensions")
        indices.add(x + z * width + y * width * length)
    return indices


def read_schematic(path: Path, bounds: Bounds) -> Schematic:
    _regular_file(path, path.name)
    compressed_limit = 512 * 1024 * 1024
    try:
        if path.stat().st_size > compressed_limit:
            raise TicketError(f"{path.name} exceeds the compressed size limit")
    except OSError as exc:
        raise TicketError(f"cannot stat {path.name}") from exc
    decompressed_limit = min(compressed_limit, max(16 * 1024 * 1024, bounds.volume * 128 + 16 * 1024 * 1024))
    try:
        document = nbt.read_gzip(path, max_uncompressed_bytes=decompressed_limit)
    except nbt.NbtError as exc:
        raise TicketError(f"{path.name}: {exc}") from exc

    root = _compound(document.root, "NBT root")
    if document.root_name == "Schematic":
        schematic_compound = root
    elif "Schematic" in root:
        schematic_compound = _compound(root.get("Schematic"), "Schematic")
    else:
        raise TicketError(f"{path.name} is not a Sponge Schematic document")

    version = _number(schematic_compound, "Version", {nbt.TAG_INT})
    if version != 3:
        raise TicketError(f"{path.name} uses Sponge Schematic version {version}, expected 3")
    width = _dimension(schematic_compound, "Width")
    height = _dimension(schematic_compound, "Height")
    length = _dimension(schematic_compound, "Length")
    if (width, height, length) != (bounds.width, bounds.height, bounds.length):
        raise TicketError(
            f"{path.name} dimensions {width}x{height}x{length} do not match ticket bounds "
            f"{bounds.width}x{bounds.height}x{bounds.length}"
        )

    blocks = _compound(schematic_compound.get("Blocks"), "Schematic.Blocks")
    palette_compound = _compound(blocks.get("Palette"), "Schematic.Blocks.Palette")
    palette: dict[str, int] = {}
    ids: set[int] = set()
    for state_name, value_tag in palette_compound.items():
        if not isinstance(state_name, str) or not _BLOCK_STATE.fullmatch(state_name):
            raise TicketError(f"invalid block state in palette: {state_name!r}")
        if value_tag.type_id != nbt.TAG_INT or isinstance(value_tag.value, bool) or not isinstance(value_tag.value, int):
            raise TicketError(f"palette id for {state_name} is not an NBT int")
        if value_tag.value < 0 or value_tag.value in ids:
            raise TicketError("palette ids must be unique non-negative integers")
        ids.add(value_tag.value)
        palette[state_name] = value_tag.value
    if not palette:
        raise TicketError("schematic palette is empty")

    data_tag = blocks.get("Data")
    if data_tag is None or data_tag.type_id != nbt.TAG_BYTE_ARRAY or not isinstance(data_tag.value, (bytes, bytearray)):
        raise TicketError("Schematic.Blocks.Data must be an NBT byte array")
    block_ids = decode_varints(bytes(data_tag.value), width * height * length)
    missing = set(block_ids).difference(ids)
    if missing:
        raise TicketError(f"block data references missing palette ids: {sorted(missing)[:10]}")

    entity_indices = _extract_block_entity_indices(blocks, width, height, length)
    return Schematic(
        document,
        schematic_compound,
        blocks,
        width,
        height,
        length,
        palette,
        block_ids,
        entity_indices,
    )


def _required_int_array(compound: dict[str, nbt.Tag], key: str, label: str) -> tuple[int, int, int]:
    tag = compound.get(key)
    if tag is None or tag.type_id != nbt.TAG_INT_ARRAY or not isinstance(tag.value, list) or len(tag.value) != 3:
        raise TicketError(f"{label}.{key} must be an int array of length 3")
    if not all(isinstance(item, int) and not isinstance(item, bool) for item in tag.value):
        raise TicketError(f"{label}.{key} contains a non-integer")
    return tag.value[0], tag.value[1], tag.value[2]


def _worldedit_origin(schematic: Schematic) -> tuple[int, int, int]:
    metadata = _compound(schematic.schematic_compound.get("Metadata"), "Schematic.Metadata")
    worldedit = _compound(metadata.get("WorldEdit"), "Schematic.Metadata.WorldEdit")
    return _required_int_array(worldedit, "Origin", "Schematic.Metadata.WorldEdit")


def _validate_schematic_geometry(
    bounds: Bounds,
    anchor: Point,
    source: Schematic,
    candidate: Schematic,
) -> None:
    expected_offset = (
        bounds.minimum.x - anchor.x,
        bounds.minimum.y - anchor.y,
        bounds.minimum.z - anchor.z,
    )
    source_offset = _required_int_array(source.schematic_compound, "Offset", "Schematic")
    candidate_offset = _required_int_array(candidate.schematic_compound, "Offset", "Schematic")
    if source_offset != expected_offset:
        raise TicketError("source.schem Offset does not match ticket bounds and anchor")
    if candidate_offset != source_offset:
        raise TicketError("candidate.schem Offset does not match source.schem")

    expected_origin = (anchor.x, anchor.y, anchor.z)
    source_origin = _worldedit_origin(source)
    candidate_origin = _worldedit_origin(candidate)
    if source_origin != expected_origin:
        raise TicketError("source.schem WorldEdit Origin does not match ticket anchor")
    if candidate_origin != source_origin:
        raise TicketError("candidate.schem WorldEdit Origin does not match source.schem")


def _validate_immutable_schematic_content(source: Schematic, candidate: Schematic) -> None:
    source_data_version = _number(source.schematic_compound, "DataVersion", {nbt.TAG_INT})
    candidate_data_version = _number(candidate.schematic_compound, "DataVersion", {nbt.TAG_INT})
    if candidate_data_version != source_data_version:
        raise TicketError("candidate.schem DataVersion does not match source.schem")
    if "Entities" in source.schematic_compound or "Entities" in candidate.schematic_compound:
        raise TicketError("entities are not supported in AstralArchitect tickets")
    if "Biomes" in source.schematic_compound or "Biomes" in candidate.schematic_compound:
        raise TicketError("biomes are not supported in AstralArchitect tickets")

    source_entities = source.blocks_compound.get("BlockEntities")
    candidate_entities = candidate.blocks_compound.get("BlockEntities")
    if source_entities != candidate_entities:
        raise TicketError("candidate.schem BlockEntities do not match source.schem")
    if source.block_entity_indices != candidate.block_entity_indices:
        raise TicketError("candidate.schem block entity positions do not match source.schem")
    source_states = source.states()
    candidate_states = candidate.states()
    for index in source.block_entity_indices:
        if source_states[index] != candidate_states[index]:
            raise TicketError("candidate.schem changes a block entity block state")

    mutable_block_keys = {"Palette", "PaletteMax", "Data"}
    source_fixed = {
        key: value for key, value in source.blocks_compound.items() if key not in mutable_block_keys
    }
    candidate_fixed = {
        key: value for key, value in candidate.blocks_compound.items() if key not in mutable_block_keys
    }
    if source_fixed != candidate_fixed:
        raise TicketError("candidate.schem changes unsupported Blocks metadata")


def load_ticket(directory: Path | str) -> Ticket:
    """Load and fully validate a live ticket and its two schematic files."""

    raw_directory = str(directory)
    if ".." in raw_directory.replace("\\", "/").split("/"):
        raise TicketError("ticket directory must not contain '..'")
    requested = Path(directory)
    if not requested.is_absolute():
        raise TicketError("ticket directory must be an absolute path")
    _assert_no_reparse_points(requested)
    try:
        resolved = requested.resolve(strict=True)
    except OSError as exc:
        raise TicketError("ticket directory does not exist") from exc
    reject_reparse_chain(requested, "ticket directory")
    if not resolved.is_dir() or _is_reparse_point(resolved):
        raise TicketError("ticket directory must be a non-link directory")
    _assert_no_reparse_points(resolved)
    if any(part.casefold() == "trash" for part in resolved.parts):
        raise TicketError("tickets under trash are not accessible")
    if resolved.parent.name.casefold() != "tickets":
        raise TicketError("ticket directory must be directly below an AstralArchitect tickets directory")
    if resolved.parent.parent.name.casefold() != "astralarchitect":
        raise TicketError("tickets directory must belong to an AstralArchitect data folder")
    if not _TICKET_ID.fullmatch(resolved.name):
        raise TicketError("ticket directory name is invalid")

    metadata = _read_json_file(resolved / "ticket.json")
    if _json_int(metadata.get("schemaVersion"), "schemaVersion") != 1:
        raise TicketError("unsupported ticket schemaVersion")
    ticket_id = metadata.get("id")
    if not isinstance(ticket_id, str) or ticket_id != resolved.name:
        raise TicketError("ticket id does not match its directory name")
    state = metadata.get("state")
    if not isinstance(state, str):
        raise TicketError("ticket state must be a string")
    bounds = _metadata_bounds(metadata)
    anchor = _point(metadata.get("anchor"), "anchor")
    if not bounds.contains(anchor):
        raise TicketError("ticket anchor is outside bounds")

    source_path = resolved / "source.schem"
    candidate_path = resolved / "candidate.schem"
    _regular_file(source_path, "source.schem")
    _regular_file(candidate_path, "candidate.schem")

    expected_source_hash = metadata.get("sourceSha256")
    if not isinstance(expected_source_hash, str) or not _SHA256.fullmatch(expected_source_hash):
        raise TicketError("ticket sourceSha256 is missing or invalid")
    source_hash = sha256_file(source_path)
    if source_hash != expected_source_hash:
        raise TicketError("source.schem SHA-256 does not match ticket.json")

    candidate_hash_before = sha256_file(candidate_path)
    source = read_schematic(source_path, bounds)
    candidate = read_schematic(candidate_path, bounds)
    _validate_schematic_geometry(bounds, anchor, source, candidate)
    _validate_immutable_schematic_content(source, candidate)
    source_hash_after = sha256_file(source_path)
    if source_hash != source_hash_after:
        raise TicketError("source.schem changed while it was being read")
    candidate_hash_after = sha256_file(candidate_path)
    if candidate_hash_before != candidate_hash_after:
        raise TicketError("candidate.schem changed while it was being read")

    return Ticket(
        resolved,
        metadata,
        bounds,
        source_path,
        candidate_path,
        source_hash,
        candidate_hash_after,
        source,
        candidate,
    )


def point_to_index(ticket: Ticket, point: Point) -> int:
    if not ticket.bounds.contains(point):
        raise TicketError(f"coordinate {point.x},{point.y},{point.z} is outside ticket bounds")
    return ticket.candidate.index(
        point.x - ticket.bounds.minimum.x,
        point.y - ticket.bounds.minimum.y,
        point.z - ticket.bounds.minimum.z,
    )


def index_to_point(ticket: Ticket, index: int) -> Point:
    layer = ticket.bounds.width * ticket.bounds.length
    local_y, layer_index = divmod(index, layer)
    local_z, local_x = divmod(layer_index, ticket.bounds.width)
    return Point(
        ticket.bounds.minimum.x + local_x,
        ticket.bounds.minimum.y + local_y,
        ticket.bounds.minimum.z + local_z,
    )


def validate_block_state(value: object, label: str = "block") -> str:
    if not isinstance(value, str) or len(value) > 512 or not _BLOCK_STATE.fullmatch(value):
        raise TicketError(f"{label} must be a namespaced Minecraft block state")
    return value


def _reject_unknown_operation_keys(
    operation: dict[str, object], allowed: set[str], label: str
) -> None:
    unknown = sorted(set(operation).difference(allowed))
    if unknown:
        raise TicketError(f"{label} contains unknown properties: {', '.join(unknown)}")


def _operation_point(value: object, label: str) -> Point:
    return _point(value, label)


def _set_point(operation: dict[str, object], label: str) -> Point:
    return Point(
        _json_int(operation.get("x"), f"{label}.x"),
        _json_int(operation.get("y"), f"{label}.y"),
        _json_int(operation.get("z"), f"{label}.z"),
    )


def _cuboid(first: Point, second: Point) -> Iterator[Point]:
    for y in range(min(first.y, second.y), max(first.y, second.y) + 1):
        for z in range(min(first.z, second.z), max(first.z, second.z) + 1):
            for x in range(min(first.x, second.x), max(first.x, second.x) + 1):
                yield Point(x, y, z)


def _line(first: Point, second: Point) -> Iterator[Point]:
    """Yield a deterministic 3-D Bresenham line including both endpoints."""

    x, y, z = first.x, first.y, first.z
    x2, y2, z2 = second.x, second.y, second.z
    dx, dy, dz = abs(x2 - x), abs(y2 - y), abs(z2 - z)
    sx, sy, sz = (1 if x2 >= x else -1), (1 if y2 >= y else -1), (1 if z2 >= z else -1)
    yield Point(x, y, z)
    if dx >= dy and dx >= dz:
        p_y, p_z = 2 * dy - dx, 2 * dz - dx
        while x != x2:
            x += sx
            if p_y >= 0:
                y += sy
                p_y -= 2 * dx
            if p_z >= 0:
                z += sz
                p_z -= 2 * dx
            p_y += 2 * dy
            p_z += 2 * dz
            yield Point(x, y, z)
    elif dy >= dx and dy >= dz:
        p_x, p_z = 2 * dx - dy, 2 * dz - dy
        while y != y2:
            y += sy
            if p_x >= 0:
                x += sx
                p_x -= 2 * dy
            if p_z >= 0:
                z += sz
                p_z -= 2 * dy
            p_x += 2 * dx
            p_z += 2 * dz
            yield Point(x, y, z)
    else:
        p_y, p_x = 2 * dy - dz, 2 * dx - dz
        while z != z2:
            z += sz
            if p_y >= 0:
                y += sy
                p_y -= 2 * dz
            if p_x >= 0:
                x += sx
                p_x -= 2 * dz
            p_y += 2 * dy
            p_x += 2 * dx
            yield Point(x, y, z)


def _expect(states: list[str], index: int, expected: object, label: str, point: Point) -> None:
    if expected is None:
        return
    expected_state = validate_block_state(expected, f"{label}.expect")
    if states[index] != expected_state:
        raise TicketError(
            f"{label} expect failed at {point.x},{point.y},{point.z}: "
            f"expected {expected_state}, found {states[index]}"
        )


def apply_operations(ticket: Ticket, operations: list[object]) -> dict[str, object]:
    """Apply validated operations in memory and atomically replace candidate.schem."""

    state = ticket.metadata.get("state")
    if state not in _EDITABLE_STATES:
        raise TicketError(f"ticket state {state!r} is not editable")
    if not operations:
        raise TicketError("operations list is empty")
    if len(operations) > 100_000:
        raise TicketError("too many operations")

    states = ticket.candidate.states()
    original_states = states.copy()
    touched: set[int] = set()
    visited = 0
    visit_budget = max(ticket.bounds.volume, _MIN_OPERATION_VISIT_BUDGET)
    for operation_index, raw_operation in enumerate(operations):
        label = f"operations[{operation_index}]"
        if not isinstance(raw_operation, dict):
            raise TicketError(f"{label} must be an object")
        operation: dict[str, object] = raw_operation
        kind = operation.get("op")
        if kind == "set":
            _reject_unknown_operation_keys(
                operation, {"op", "x", "y", "z", "block", "expect"}, label
            )
            points: Iterable[Point] = [_set_point(operation, label)]
            block = validate_block_state(operation.get("block"), f"{label}.block")
            expected = operation.get("expect")
            match_state = None
        elif kind in {"fill", "line"}:
            _reject_unknown_operation_keys(
                operation, {"op", "from", "to", "block", "expect"}, label
            )
            first = _operation_point(operation.get("from"), f"{label}.from")
            second = _operation_point(operation.get("to"), f"{label}.to")
            points = _cuboid(first, second) if kind == "fill" else _line(first, second)
            block = validate_block_state(operation.get("block"), f"{label}.block")
            expected = operation.get("expect")
            match_state = None
        elif kind == "replace":
            _reject_unknown_operation_keys(
                operation,
                {"op", "from", "to", "match", "block", "fromBlock", "toBlock"},
                label,
            )
            first_raw, second_raw = operation.get("from"), operation.get("to")
            if (first_raw is None) != (second_raw is None):
                raise TicketError(f"{label}.from and {label}.to must be supplied together")
            if first_raw is None:
                first, second = ticket.bounds.minimum, ticket.bounds.maximum
            else:
                first = _operation_point(first_raw, f"{label}.from")
                second = _operation_point(second_raw, f"{label}.to")
            points = _cuboid(first, second)
            match_state = validate_block_state(
                operation.get("match", operation.get("fromBlock")), f"{label}.match"
            )
            block = validate_block_state(
                operation.get("block", operation.get("toBlock")), f"{label}.block"
            )
            expected = None
        else:
            raise TicketError(f"{label}.op must be set, fill, replace, or line")

        expanded = 0
        for point in points:
            expanded += 1
            visited += 1
            if expanded > ticket.bounds.volume:
                raise TicketError(f"{label} expands beyond the ticket volume")
            if visited > visit_budget:
                raise TicketError(
                    f"operations exceed the total expansion budget of {visit_budget} block visits"
                )
            index = point_to_index(ticket, point)
            if match_state is not None and states[index] != match_state:
                continue
            _expect(states, index, expected, label, point)
            if index in ticket.candidate.block_entity_indices and states[index] != block:
                raise TicketError(
                    f"{label} would modify a block entity at {point.x},{point.y},{point.z}; "
                    "block entity editing is not supported"
                )
            states[index] = block
            touched.add(index)

    changed_indices = {index for index in touched if original_states[index] != states[index]}
    if not changed_indices:
        return {
            "written": False,
            "touchedBlockCount": len(touched),
            "changedBlockCount": 0,
            "candidateSha256": ticket.candidate_hash,
        }

    # Detect a second process changing candidate.schem after this process loaded it.
    if sha256_file(ticket.candidate_path) != ticket.candidate_hash:
        raise TicketError("candidate.schem changed before write; no changes were written")
    if sha256_file(ticket.source_path) != ticket.source_hash:
        raise TicketError("source.schem changed before write; no changes were written")

    ticket.candidate.set_states(states)
    fd, temporary_name = tempfile.mkstemp(
        prefix=".candidate.schem.", suffix=".tmp", dir=ticket.directory
    )
    os.close(fd)
    temporary_path = Path(temporary_name)
    try:
        nbt.write_gzip(temporary_path, ticket.candidate.document)
        # Parse the completed file before exposing it as candidate.schem.
        verified = read_schematic(temporary_path, ticket.bounds)
        if verified.states() != states:
            raise TicketError("candidate verification failed before atomic replacement")
        try:
            os.chmod(temporary_path, stat.S_IMODE(ticket.candidate_path.stat().st_mode))
        except OSError:
            # Mode copying is advisory on Windows and does not affect atomicity.
            pass
        if sha256_file(ticket.candidate_path) != ticket.candidate_hash:
            raise TicketError("candidate.schem changed during write; no changes were written")
        if sha256_file(ticket.source_path) != ticket.source_hash:
            raise TicketError("source.schem changed during write; no changes were written")
        os.replace(temporary_path, ticket.candidate_path)
    except (OSError, nbt.NbtError) as exc:
        raise TicketError(f"failed to atomically write candidate.schem: {exc}") from exc
    finally:
        try:
            temporary_path.unlink(missing_ok=True)
        except OSError:
            pass

    return {
        "written": True,
        "touchedBlockCount": len(touched),
        "changedBlockCount": len(changed_indices),
        "candidateSha256": sha256_file(ticket.candidate_path),
    }


def is_air(state_name: str) -> bool:
    base = state_name.split("[", 1)[0]
    return base in {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def run_length_encode(values: list[str]) -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for value in values:
        if result and result[-1]["block"] == value:
            result[-1]["count"] = int(result[-1]["count"]) + 1
        else:
            result.append({"block": value, "count": 1})
    return result
