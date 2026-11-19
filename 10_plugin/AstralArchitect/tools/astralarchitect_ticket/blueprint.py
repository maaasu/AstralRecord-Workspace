"""Compile reusable blueprint parts into ordinary ticket operations."""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
import json
from math import prod

from .schematic import (
    MAX_OPERATION_BYTES,
    MAX_OPERATIONS,
    MAX_TICKET_VOLUME,
    TicketError,
    validate_block_state,
)


class BlueprintError(TicketError):
    """Raised when a blueprint violates the version 1 contract."""


Coordinates = tuple[int, int, int]

_COORDINATE_LIMIT = 2**31
_AXES = {"x": 0, "z": 2}
_STAGES = ("rough", "detail")
_DIRECTIONS = {"north", "south", "east", "west", "up", "down"}
_HORIZONTAL_DIRECTIONS = _DIRECTIONS - {"up", "down"}
_REFLECTIONS = {"x": {"east": "west", "west": "east"}, "z": {"north": "south", "south": "north"}}
_HANDED = {"left": "right", "right": "left"}
_ROTATIONS = frozenset(map(str, range(16)))
_STAIR_SHAPES = {"straight", "inner_left", "inner_right", "outer_left", "outer_right"}
_RAIL_CORNERS = {
    frozenset(shape.split("_")): shape
    for shape in ("north_east", "north_west", "south_east", "south_west")
}
_RAIL_BLOCKS = {"rail", "activator_rail", "detector_rail", "powered_rail"}
_INVARIANT_PROPERTIES = frozenset("""
    age attached attachment berries bites bloom bottom candles can_summon
    charges conditional crafting creaking_heart_state delay disarmed distance drag dusted
    eggs enabled extended eye face
    flower_amount half hanging has_book has_record hatch honey_level hydration in_wall instrument
    inverted layers leaves level lit locked mode moisture natural note occupied ominous
    open part persistent pickles power powered sculk_sensor_phase segment_amount short
    signal_fire snowy stage thickness tilt tip triggered trial_spawner_state unstable
    vault_state vertical_direction waterlogged waxed
""".split())
_INVARIANT_TYPE_VALUES = {"top", "bottom", "double", "single"}
_EXPLICIT_FACING_SUFFIXES = (
    "_stairs", "_door", "_trapdoor", "_fence_gate", "_button", "_bed", "_wall_sign",
    "_wall_hanging_sign", "_wall_banner", "_wall_head", "_wall_skull", "_wall_fan",
    "_shulker_box", "_glazed_terracotta", "_wall_torch", "_wall_coral_fan",
    "_shelf", "_copper_golem_statue", "_amethyst_bud", "_copper_chest", "_lightning_rod",
)
_EXPLICIT_FACING_BLOCKS = {
    "amethyst_cluster", "anvil", "attached_melon_stem", "attached_pumpkin_stem",
    "barrel", "bee_nest", "beehive", "bell", "blast_furnace",
    "big_dripleaf", "big_dripleaf_stem", "calibrated_sculk_sensor", "campfire",
    "chest", "chiseled_bookshelf", "chipped_anvil", "cocoa", "command_block",
    "carved_pumpkin", "chain_command_block", "comparator", "copper_chest",
    "damaged_anvil", "decorated_pot", "dried_ghast",
    "dispenser", "dropper", "end_portal_frame", "end_rod", "ender_chest", "furnace",
    "grindstone", "hopper", "jack_o_lantern", "ladder", "leaf_litter", "lectern", "lever",
    "lightning_rod",
    "loom", "observer", "pink_petals", "piston", "piston_head", "repeater",
    "repeating_command_block",
    "shulker_box", "small_dripleaf", "smoker",
    "stonecutter", "sticky_piston", "soul_campfire", "tripwire_hook", "trapped_chest",
    "vault", "wall_torch", "wildflowers",
}


@dataclass(frozen=True, slots=True)
class _Operation:
    kind: str
    points: tuple[Coordinates, ...]
    block: str
    label: str

    def visits(self) -> int:
        if self.kind == "set":
            return 1
        first, second = self.points
        spans = [abs(start - end) + 1 for start, end in zip(first, second)]
        return prod(spans) if self.kind == "fill" else max(spans)


@dataclass(frozen=True, slots=True)
class _Placement:
    part: str
    at: Coordinates
    mirror: str | None
    count: int
    step: Coordinates
    label: str


def _fields(
    value: object, required: set[str], label: str, *, optional: frozenset[str] = frozenset(),
) -> dict[str, object]:
    if not isinstance(value, dict):
        raise BlueprintError(f"{label} must be an object")
    missing = required - value.keys()
    unknown = value.keys() - required - optional
    if missing:
        raise BlueprintError(f"{label} is missing required properties: {', '.join(sorted(missing))}")
    if unknown:
        raise BlueprintError(f"{label} contains unknown properties: {', '.join(sorted(map(str, unknown)))}")
    return value


def _list(value: object, label: str) -> list:
    if not isinstance(value, list):
        raise BlueprintError(f"{label} must be an array")
    if len(value) > MAX_OPERATIONS:
        raise BlueprintError(f"{label} contains more than {MAX_OPERATIONS} entries")
    return value


def _checked_coordinates(x: int, y: int, z: int, label: str) -> Coordinates:
    if any(not -_COORDINATE_LIMIT <= component < _COORDINATE_LIMIT for component in (x, y, z)):
        raise BlueprintError(f"{label} is outside the signed 32-bit coordinate range")
    return x, y, z


def _coordinates(value: object, label: str) -> Coordinates:
    if not isinstance(value, list) or len(value) != 3 or any(type(component) is not int for component in value):
        raise BlueprintError(f"{label} must be an array of three integers")
    return _checked_coordinates(*value, label)


def _state_properties(block: str, label: str) -> tuple[str, dict[str, str]]:
    base, separator, raw_properties = block.partition("[")
    properties: dict[str, str] = {}
    if separator:
        for assignment in raw_properties[:-1].split(","):
            key, value = assignment.split("=")
            if key in properties:
                raise BlueprintError(f"{label} contains duplicate block property {key!r}")
            properties[key] = value
    return base, properties


def _roof_axis_samples(points: list[int]) -> Iterator[tuple[int, int, int, int]]:
    """Yield the two control indices and integer weights at each sample."""
    for index, (start, end) in enumerate(zip(points, points[1:])):
        span = end - start
        for distance in range(span):
            yield index, index + 1, span - distance, distance
    last = len(points) - 1
    yield last, last, 1, 0


def _expand_roof_sheet(value: object, label: str, remaining_operations: int) -> list[_Operation]:
    roof = _fields(value, {"op", "at", "offsets", "sections", "block", "slab"}, label)
    at_x, at_y, at_z = _coordinates(roof["at"], f"{label}.at")
    materials = []
    for key in ("block", "slab"):
        material_label = f"{label}.{key}"
        try:
            material = validate_block_state(roof[key], material_label)
        except TicketError as exc:
            raise BlueprintError(str(exc)) from exc
        if "[" in material:
            raise BlueprintError(f"{material_label} must be a block name without properties")
        materials.append(material)
    block, slab = materials
    if block in {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}:
        raise BlueprintError(f"{label}.block must be a non-air block name")
    if block.endswith(("_slab", "_stairs")):
        raise BlueprintError(f"{label}.block requires a full cube, not a slab or stairs")
    if not slab.endswith("_slab"):
        raise BlueprintError(f"{label}.slab must be a block name ending in _slab")

    offsets = _list(roof["offsets"], f"{label}.offsets")
    sections = _list(roof["sections"], f"{label}.sections")
    if not offsets or not sections:
        raise BlueprintError(f"{label}.offsets and .sections must be nonempty")
    for index, offset in enumerate(offsets):
        if type(offset) is not int:
            raise BlueprintError(f"{label}.offsets must contain only integers")
        if index and offset <= offsets[index - 1]:
            raise BlueprintError(f"{label}.offsets must be strictly ascending")
        _checked_coordinates(offset, 0, 0, f"{label}.offsets[{index}]")

    section_zs: list[int] = []
    height_rows: list[list[int]] = []
    for index, section in enumerate(sections):
        section_label = f"{label}.sections[{index}]"
        fields = _fields(section, {"z", "heights"}, section_label)
        z = fields["z"]
        if type(z) is not int:
            raise BlueprintError(f"{section_label}.z must be an integer")
        if section_zs and z <= section_zs[-1]:
            raise BlueprintError(f"{label}.sections z coordinates must be strictly ascending")
        _checked_coordinates(0, 0, z, f"{section_label}.z")
        heights = _list(fields["heights"], f"{section_label}.heights")
        if len(heights) != len(offsets) or any(type(height) is not int for height in heights):
            raise BlueprintError(f"{section_label}.heights must contain one integer per offset")
        section_zs.append(z)
        height_rows.append(heights)

    width = offsets[-1] - offsets[0] + 1
    depth = section_zs[-1] - section_zs[0] + 1
    operation_error = f"{label} expands beyond the {MAX_OPERATIONS} local operation limit"
    visit_error = f"{label} expands beyond {MAX_TICKET_VOLUME} block visits"
    # Each column emits at least one operation and visits at least one block.
    if width * depth > remaining_operations:
        raise BlueprintError(operation_error)
    if width * depth > MAX_TICKET_VOLUME:
        raise BlueprintError(visit_error)

    first_x, first_z = at_x + offsets[0], at_z + section_zs[0]
    # Interpolation stays within the control heights and includes the extrema.
    lowest = min(map(min, height_rows)) - 1
    highest = max(map(max, height_rows)) - 1
    _checked_coordinates(first_x, at_y + lowest // 2, first_z, label)
    _checked_coordinates(first_x + width - 1, at_y + highest // 2, first_z + depth - 1, label)

    x_samples = list(_roof_axis_samples(offsets))
    tops: list[list[int]] = []
    for front, back, front_weight, back_weight in _roof_axis_samples(section_zs):
        front_row, back_row = height_rows[front], height_rows[back]
        row = []
        for left, right, left_weight, right_weight in x_samples:
            numerator = (
                front_weight * (front_row[left] * left_weight + front_row[right] * right_weight)
                + back_weight * (back_row[left] * left_weight + back_row[right] * right_weight)
            )
            denominator = (front_weight + back_weight) * (left_weight + right_weight)
            # Round once, toward positive infinity at a tie, even below zero.
            row.append((2 * numerator + denominator) // (2 * denominator))
        tops.append(row)

    operations = []
    visits = 0
    top_slab, bottom_slab = f"{slab}[type=top]", f"{slab}[type=bottom]"
    for z_index, row in enumerate(tops):
        z = first_z + z_index
        for x_index, top in enumerate(row):
            x = first_x + x_index
            lowest_top = top
            if x_index:
                lowest_top = min(lowest_top, row[x_index - 1])
            if x_index + 1 < width:
                lowest_top = min(lowest_top, row[x_index + 1])
            if z_index:
                lowest_top = min(lowest_top, tops[z_index - 1][x_index])
            if z_index + 1 < depth:
                lowest_top = min(lowest_top, tops[z_index + 1][x_index])

            # Occupied half layers form the half-open interval [bottom, top).
            bottom = lowest_top - 1
            visits += (top - 1) // 2 - bottom // 2 + 1
            if visits > MAX_TICKET_VOLUME:
                raise BlueprintError(visit_error)
            runs = []
            if bottom % 2:
                runs.append((bottom // 2, bottom // 2, top_slab))
            first_full, past_full = (bottom + 1) // 2, top // 2
            if first_full < past_full:
                runs.append((first_full, past_full - 1, block))
            if top % 2:
                runs.append((top // 2, top // 2, bottom_slab))
            if len(operations) + len(runs) > remaining_operations:
                raise BlueprintError(operation_error)
            for first_y, last_y, material in runs:
                start = (x, at_y + first_y, z)
                if first_y == last_y:
                    operations.append(_Operation("set", (start,), material, label))
                else:
                    end = (x, at_y + last_y, z)
                    operations.append(_Operation("fill", (start, end), material, label))
    return operations


def _parse_operation(value: object, label: str) -> _Operation:
    kind = value.get("op") if isinstance(value, dict) else None
    if kind not in ("set", "fill", "line"):
        raise BlueprintError(f"{label}.op must be set, fill, line, or roof_sheet")
    coordinate_keys = ("at",) if kind == "set" else ("from", "to")
    operation = _fields(value, {"op", "block", *coordinate_keys}, label)
    try:
        block = validate_block_state(operation["block"], f"{label}.block")
    except TicketError as exc:
        raise BlueprintError(str(exc)) from exc
    _state_properties(block, f"{label}.block")
    return _Operation(
        kind, tuple(_coordinates(operation[key], f"{label}.{key}") for key in coordinate_keys),
        block, label,
    )


def _parse_placement(value: object, label: str, parts: dict) -> _Placement:
    placement = _fields(value, {"part", "at"}, label, optional=frozenset({"mirror", "repeat"}))
    name = placement["part"]
    if not isinstance(name, str) or name not in parts:
        raise BlueprintError(f"{label}.part must name a defined part")
    mirror = placement.get("mirror")
    if "mirror" in placement and mirror not in ("x", "z"):
        raise BlueprintError(f"{label}.mirror must be x or z")
    count, step = 1, (0, 0, 0)
    if "repeat" in placement:
        repeat = _fields(placement["repeat"], {"count", "step"}, f"{label}.repeat")
        count = repeat["count"]
        if type(count) is not int or not 1 <= count <= MAX_OPERATIONS:
            raise BlueprintError(f"{label}.repeat.count must be an integer from 1 to {MAX_OPERATIONS}")
        step = _coordinates(repeat["step"], f"{label}.repeat.step")
    return _Placement(name, _coordinates(placement["at"], f"{label}.at"), mirror, count, step, label)


def _reflect_rail(shape: str, reflected: dict[str, str]) -> str | None:
    if shape in {"north_south", "east_west"}:
        return shape
    if shape.startswith("ascending_"):
        direction = shape.removeprefix("ascending_")
        if direction in _HORIZONTAL_DIRECTIONS:
            return "ascending_" + reflected.get(direction, direction)
    if shape in _RAIL_CORNERS.values():
        return _RAIL_CORNERS[frozenset(reflected.get(direction, direction) for direction in shape.split("_"))]
    return None


def _reflect_property(
    name: str, key: str, value: str, axis: str, reflected: dict[str, str],
) -> str | None:
    if key == "facing" and value in _DIRECTIONS:
        return reflected.get(value, value)
    if key == "orientation" and name in {"jigsaw", "crafter"}:
        first, _, second = value.partition("_")
        if (first in {"up", "down"} and second in _HORIZONTAL_DIRECTIONS) or (
            first in _HORIZONTAL_DIRECTIONS and second == "up"
        ):
            return f"{reflected.get(first, first)}_{reflected.get(second, second)}"
    if key == "rotation" and value in _ROTATIONS:
        return str(((0 if axis == "x" else 8) - int(value)) % 16)
    if key == "shape" and name.endswith("_stairs") and value in _STAIR_SHAPES:
        curve, separator, hand = value.partition("_")
        return curve + separator + _HANDED[hand] if separator else value
    if key == "shape" and name in _RAIL_BLOCKS:
        return _reflect_rail(value, reflected)
    if key == "hinge" and name.endswith("_door") and value in _HANDED:
        return _HANDED[value]
    if key == "side_chain" and name.endswith("_shelf"):
        if value in _HANDED or value in {"center", "unconnected"}:
            return _HANDED.get(value, value)
    if key == "type":
        if name.endswith("chest") and value in _HANDED:
            return _HANDED[value]
        if name == "piston_head" and value in {"normal", "sticky"}:
            return value
        if value in _INVARIANT_TYPE_VALUES:
            return value
    if key == "axis" and value in {"x", "y", "z"}:
        return value
    if key in _DIRECTIONS and value in {"true", "false", "none", "low", "tall", "side", "up"}:
        return value
    if key in _INVARIANT_PROPERTIES:
        return value
    return None


def _require_orientation(name: str, properties: dict[str, str], label: str) -> None:
    required = None
    if name in {"jigsaw", "crafter"}:
        required = "orientation"
    elif name in _RAIL_BLOCKS:
        required = "shape"
    elif (name.endswith(("_sign", "_banner", "_head", "_skull")) and "_wall_" not in name
          and not name.startswith("wall_") and name != "piston_head"):
        required = "rotation"
    elif (name in _EXPLICIT_FACING_BLOCKS or name.endswith(_EXPLICIT_FACING_SUFFIXES)
          or name.startswith("wall_")):
        required = "facing"
    if required and required not in properties:
        raise BlueprintError(f"{label} requires explicit {required!r} when mirroring minecraft:{name}")
    if name.endswith("_door") and "hinge" not in properties:
        raise BlueprintError(f"{label} requires explicit 'hinge' when mirroring minecraft:{name}")


def _reflect_block(block: str, axis: str, label: str) -> str:
    base, properties = _state_properties(block, label)
    namespace, name = base.split(":", 1)
    if namespace != "minecraft":
        raise BlueprintError(f"{label} cannot mirror a block from namespace {namespace!r}")
    _require_orientation(name, properties, label)
    reflected = _REFLECTIONS[axis]
    transformed = {}
    order = []
    for key, value in properties.items():
        new_value = _reflect_property(name, key, value, axis, reflected)
        if new_value is None:
            raise BlueprintError(f"{label} cannot mirror block property {key}={value} on {base}")
        new_key = reflected.get(key, key)
        transformed[new_key] = new_value
        order.append(key if new_key in properties else new_key)
    if not transformed:
        return base
    assignments = ",".join(f"{key}={transformed[key]}" for key in order)
    return f"{base}[{assignments}]"


def _translate(
    point: Coordinates, origin: Coordinates,
    placement: _Placement, index: int, label: str,
) -> dict[str, int]:
    mirrored_axis = _AXES.get(placement.mirror)
    translated = (
        offset + at + index * step + (-local if axis == mirrored_axis else local)
        for axis, (offset, at, step, local) in enumerate(zip(origin, placement.at, placement.step, point))
    )
    return dict(zip(("x", "y", "z"), _checked_coordinates(*translated, label)))


def compile_blueprint(plan: object, stage: str) -> list[dict[str, object]]:
    """Validate a v1 blueprint and emit rough operations before any detail operations."""
    if stage not in _STAGES:
        raise BlueprintError("blueprint stage must be rough or detail")
    fields = _fields(plan, {"version", "origin", "parts", "placements"}, "blueprint")
    if type(fields["version"]) is not int or fields["version"] != 1:
        raise BlueprintError("blueprint.version must be 1")
    origin = _coordinates(fields["origin"], "blueprint.origin")

    raw_parts = fields["parts"]
    if not isinstance(raw_parts, dict) or not raw_parts or len(raw_parts) > MAX_OPERATIONS:
        raise BlueprintError(f"blueprint.parts must contain from 1 to {MAX_OPERATIONS} named parts")
    parts: dict[str, dict[str, list[_Operation]]] = {}
    operation_count = 0
    for name, raw_part in raw_parts.items():
        if not isinstance(name, str) or not name.strip():
            raise BlueprintError("each blueprint part must have a nonempty name")
        label = f"blueprint.parts[{name!r}]"
        part = _fields(raw_part, set(_STAGES), label)
        operations_by_stage = {}
        for phase in _STAGES:
            phase_label = f"{label}.{phase}"
            operations = []
            for index, operation in enumerate(_list(part[phase], phase_label)):
                operation_label = f"{phase_label}[{index}]"
                remaining = MAX_OPERATIONS - operation_count
                if not remaining:
                    raise BlueprintError(f"blueprint defines more than {MAX_OPERATIONS} local operations")
                if isinstance(operation, dict) and operation.get("op") == "roof_sheet":
                    expanded = _expand_roof_sheet(operation, operation_label, remaining)
                    operations.extend(expanded)
                    operation_count += len(expanded)
                else:
                    operations.append(_parse_operation(operation, operation_label))
                    operation_count += 1
            operations_by_stage[phase] = operations
        if not any(operations_by_stage.values()):
            raise BlueprintError(f"{label} must define at least one operation")
        parts[name] = operations_by_stage

    placements = [
        _parse_placement(placement, f"blueprint.placements[{index}]", parts)
        for index, placement in enumerate(_list(fields["placements"], "blueprint.placements"))
    ]
    statistics = {
        name: (
            sum(len(operations) for operations in part.values()),
            sum(operation.visits() for operations in part.values() for operation in operations),
        )
        for name, part in parts.items()
    }
    expanded_count = expanded_visits = 0
    for placement in placements:
        count, visits = statistics[placement.part]
        expanded_count += placement.count * count
        expanded_visits += placement.count * visits
        if expanded_count > MAX_OPERATIONS:
            raise BlueprintError(f"{placement.label} expands beyond {MAX_OPERATIONS} operations")
        if expanded_visits > MAX_TICKET_VOLUME:
            raise BlueprintError(f"{placement.label} expands beyond {MAX_TICKET_VOLUME} block visits")

    result = []
    serialized_bytes = 2
    reflected_blocks: dict[tuple[str, str], str] = {}
    for phase in _STAGES:
        for placement in placements:
            operations = parts[placement.part][phase]
            if not operations:
                continue
            blocks = []
            for operation in operations:
                block = operation.block
                if placement.mirror:
                    key = (block, placement.mirror)
                    if key not in reflected_blocks:
                        label = f"{placement.label} ({operation.label}.block)"
                        reflected_blocks[key] = _reflect_block(block, placement.mirror, label)
                    block = reflected_blocks[key]
                blocks.append(block)
            if phase == "detail" and stage == "rough":
                # Each repeated coordinate is affine in the repeat index.
                # The two ends bound every skipped detail placement.
                for operation in operations:
                    label = f"{placement.label} ({operation.label})"
                    for point in operation.points:
                        _translate(point, origin, placement, 0, label)
                        _translate(point, origin, placement, placement.count - 1, label)
                continue
            for index in range(placement.count):
                for operation, block in zip(operations, blocks):
                    label = f"{placement.label} ({operation.label})"
                    points = [
                        _translate(point, origin, placement, index, label)
                        for point in operation.points
                    ]
                    output = {"op": operation.kind}
                    if operation.kind == "set":
                        output.update(points[0])
                    else:
                        output.update({"from": points[0], "to": points[1]})
                    output["block"] = block
                    serialized_bytes += bool(result) + len(json.dumps(output, separators=(",", ":")))
                    if serialized_bytes > MAX_OPERATION_BYTES:
                        raise BlueprintError(f"blueprint output exceeds {MAX_OPERATION_BYTES} operation bytes")
                    result.append(output)
    if not result:
        raise BlueprintError(f"blueprint stage {stage!r} produces no operations")
    return result
