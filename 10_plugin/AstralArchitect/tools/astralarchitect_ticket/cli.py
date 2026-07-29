"""JSON command-line interface for safe AstralArchitect ticket inspection/editing."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Sequence

from .schematic import (
    Point,
    Ticket,
    TicketError,
    apply_operations,
    candidate_lock,
    index_to_point,
    is_air,
    load_ticket,
    point_to_index,
    reject_reparse_chain,
    run_length_encode,
)

_MAX_INSPECTION_CELLS = 16_384


class JsonArgumentParser(argparse.ArgumentParser):
    """Argument parser that reports failures through the JSON error envelope."""

    def error(self, message: str) -> None:
        raise TicketError(message)


def _parser() -> JsonArgumentParser:
    parser = JsonArgumentParser(
        prog="ticket_cli.py",
        description="Inspect or atomically edit an AstralArchitect candidate.schem.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    def command(name: str, help_text: str) -> argparse.ArgumentParser:
        child = subparsers.add_parser(name, help=help_text)
        child.add_argument("ticket", help="absolute path to plugins/AstralArchitect/tickets/<id>")
        return child

    command("info", "show ticket, bounds, hashes, and schematic summary")

    palette = command("palette", "show the source or candidate block palette")
    palette.add_argument("--source", action="store_true", help="inspect source.schem instead")

    get_block = command("get-block", "read one block at world coordinates")
    get_block.add_argument("x", type=int)
    get_block.add_argument("y", type=int)
    get_block.add_argument("z", type=int)
    get_block.add_argument("--source", action="store_true", help="inspect source.schem instead")

    slice_parser = command("slice", "read one horizontal Y slice as run-length encoded rows")
    slice_parser.add_argument("--y", type=int, required=True, help="world Y coordinate")
    slice_parser.add_argument("--source", action="store_true", help="inspect source.schem instead")
    _add_horizontal_window_arguments(slice_parser)

    surface = command("surface", "show the top non-air block for every X/Z column")
    surface.add_argument("--source", action="store_true", help="inspect source.schem instead")
    _add_horizontal_window_arguments(surface)

    diff = command("diff", "compare source.schem and candidate.schem")
    diff.add_argument("--limit", type=int, default=1000, help="maximum detailed changes to return")
    diff.add_argument("--offset", type=int, default=0, help="skip this many changed blocks")

    apply_ops = command("apply-ops", "atomically apply JSON/NDJSON operations to candidate.schem")
    apply_ops.add_argument("--ops", required=True, help="absolute JSON/NDJSON file path, or - for stdin")
    return parser


def _add_horizontal_window_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--x-min", type=int, help="inclusive minimum world X")
    parser.add_argument("--x-max", type=int, help="inclusive maximum world X")
    parser.add_argument("--z-min", type=int, help="inclusive minimum world Z")
    parser.add_argument("--z-max", type=int, help="inclusive maximum world Z")


def _schematic(ticket: Ticket, use_source: bool):
    return ticket.source if use_source else ticket.candidate


def _base(ticket: Ticket, command: str) -> dict[str, object]:
    return {
        "ok": True,
        "command": command,
        "ticketId": ticket.metadata["id"],
    }


def _point_json(point: Point) -> dict[str, int]:
    return {"x": point.x, "y": point.y, "z": point.z}


def _bounds_json(ticket: Ticket) -> dict[str, object]:
    return {
        "min": _point_json(ticket.bounds.minimum),
        "max": _point_json(ticket.bounds.maximum),
        "width": ticket.bounds.width,
        "height": ticket.bounds.height,
        "length": ticket.bounds.length,
        "volume": ticket.bounds.volume,
    }


def _info(ticket: Ticket) -> dict[str, object]:
    result = _base(ticket, "info")
    recorded_candidate_hash = ticket.metadata.get("candidateSha256")
    result.update(
        {
            "name": ticket.metadata.get("name"),
            "state": ticket.metadata.get("state"),
            "owner": {
                "uuid": ticket.metadata.get("ownerUuid"),
                "name": ticket.metadata.get("ownerName"),
            },
            "world": {
                "uuid": ticket.metadata.get("worldUuid"),
                "name": ticket.metadata.get("worldName"),
            },
            "bounds": _bounds_json(ticket),
            "anchor": ticket.metadata.get("anchor"),
            "anchorBlockState": ticket.metadata.get("anchorBlockState"),
            "hashes": {
                "sourceSha256": ticket.source_hash,
                "sourceMatchesTicket": True,
                "candidateSha256": ticket.candidate_hash,
                "recordedCandidateSha256": recorded_candidate_hash,
                "candidateMatchesTicket": isinstance(recorded_candidate_hash, str)
                and ticket.candidate_hash == recorded_candidate_hash,
            },
            "sourcePaletteSize": len(ticket.source.palette),
            "candidatePaletteSize": len(ticket.candidate.palette),
            "candidateBlockEntityCount": len(ticket.candidate.block_entity_indices),
        }
    )
    return result


def _palette(ticket: Ticket, use_source: bool) -> dict[str, object]:
    schematic = _schematic(ticket, use_source)
    counts: dict[int, int] = {}
    for palette_id in schematic.block_ids:
        counts[palette_id] = counts.get(palette_id, 0) + 1
    entries = [
        {"id": palette_id, "block": state_name, "count": counts.get(palette_id, 0)}
        for state_name, palette_id in sorted(schematic.palette.items(), key=lambda item: item[1])
    ]
    result = _base(ticket, "palette")
    result.update({"schematic": "source" if use_source else "candidate", "palette": entries})
    return result


def _get_block(ticket: Ticket, use_source: bool, point: Point) -> dict[str, object]:
    schematic = _schematic(ticket, use_source)
    index = point_to_index(ticket, point)
    result = _base(ticket, "get-block")
    result.update(
        {
            "schematic": "source" if use_source else "candidate",
            "position": _point_json(point),
            "block": schematic.state_by_index(index),
            "hasBlockEntity": index in schematic.block_entity_indices,
        }
    )
    return result


def _inspection_window(
    ticket: Ticket,
    x_min: int | None,
    x_max: int | None,
    z_min: int | None,
    z_max: int | None,
) -> tuple[int, int, int, int]:
    selected_x_min = ticket.bounds.minimum.x if x_min is None else x_min
    selected_x_max = ticket.bounds.maximum.x if x_max is None else x_max
    selected_z_min = ticket.bounds.minimum.z if z_min is None else z_min
    selected_z_max = ticket.bounds.maximum.z if z_max is None else z_max
    if selected_x_min > selected_x_max or selected_z_min > selected_z_max:
        raise TicketError("inspection window minimum must not exceed maximum")
    if (
        selected_x_min < ticket.bounds.minimum.x
        or selected_x_max > ticket.bounds.maximum.x
        or selected_z_min < ticket.bounds.minimum.z
        or selected_z_max > ticket.bounds.maximum.z
    ):
        raise TicketError("inspection window is outside ticket bounds")
    cell_count = (selected_x_max - selected_x_min + 1) * (selected_z_max - selected_z_min + 1)
    if cell_count > _MAX_INSPECTION_CELLS:
        raise TicketError(
            f"inspection window has {cell_count} cells; use --x-min/--x-max/--z-min/--z-max "
            f"to request at most {_MAX_INSPECTION_CELLS} cells"
        )
    return selected_x_min, selected_x_max, selected_z_min, selected_z_max


def _slice(
    ticket: Ticket,
    use_source: bool,
    world_y: int,
    x_min: int | None,
    x_max: int | None,
    z_min: int | None,
    z_max: int | None,
) -> dict[str, object]:
    if not ticket.bounds.minimum.y <= world_y <= ticket.bounds.maximum.y:
        raise TicketError(f"Y coordinate {world_y} is outside ticket bounds")
    selected_x_min, selected_x_max, selected_z_min, selected_z_max = _inspection_window(
        ticket, x_min, x_max, z_min, z_max
    )
    schematic = _schematic(ticket, use_source)
    state_names = schematic.states()
    local_y = world_y - ticket.bounds.minimum.y
    rows: list[dict[str, object]] = []
    for world_z in range(selected_z_min, selected_z_max + 1):
        local_z = world_z - ticket.bounds.minimum.z
        values = [
            state_names[
                schematic.index(world_x - ticket.bounds.minimum.x, local_y, local_z)
            ]
            for world_x in range(selected_x_min, selected_x_max + 1)
        ]
        rows.append(
            {
                "z": world_z,
                "xStart": selected_x_min,
                "runs": run_length_encode(values),
            }
        )
    result = _base(ticket, "slice")
    result.update(
        {
            "schematic": "source" if use_source else "candidate",
            "y": world_y,
            "window": {
                "xMin": selected_x_min,
                "xMax": selected_x_max,
                "zMin": selected_z_min,
                "zMax": selected_z_max,
            },
            "rowEncoding": "runs advance in +X order",
            "rows": rows,
        }
    )
    return result


def _surface(
    ticket: Ticket,
    use_source: bool,
    x_min: int | None,
    x_max: int | None,
    z_min: int | None,
    z_max: int | None,
) -> dict[str, object]:
    selected_x_min, selected_x_max, selected_z_min, selected_z_max = _inspection_window(
        ticket, x_min, x_max, z_min, z_max
    )
    schematic = _schematic(ticket, use_source)
    state_names = schematic.states()
    rows: list[dict[str, object]] = []
    for world_z in range(selected_z_min, selected_z_max + 1):
        local_z = world_z - ticket.bounds.minimum.z
        runs: list[dict[str, object]] = []
        for world_x in range(selected_x_min, selected_x_max + 1):
            local_x = world_x - ticket.bounds.minimum.x
            top_y: int | None = None
            top_block = "minecraft:air"
            for local_y in range(schematic.height - 1, -1, -1):
                state_name = state_names[schematic.index(local_x, local_y, local_z)]
                if not is_air(state_name):
                    top_y = ticket.bounds.minimum.y + local_y
                    top_block = state_name
                    break
            if runs and runs[-1]["y"] == top_y and runs[-1]["block"] == top_block:
                runs[-1]["xEnd"] = world_x
            else:
                runs.append({"xStart": world_x, "xEnd": world_x, "y": top_y, "block": top_block})
        rows.append({"z": world_z, "runs": runs})
    result = _base(ticket, "surface")
    result.update(
        {
            "schematic": "source" if use_source else "candidate",
            "window": {
                "xMin": selected_x_min,
                "xMax": selected_x_max,
                "zMin": selected_z_min,
                "zMax": selected_z_max,
            },
            "rowEncoding": "each run spans inclusive xStart..xEnd; null y means an all-air column",
            "rows": rows,
        }
    )
    return result


def _diff(ticket: Ticket, limit: int, offset: int) -> dict[str, object]:
    if limit < 0 or limit > 100_000:
        raise TicketError("--limit must be between 0 and 100000")
    if offset < 0:
        raise TicketError("--offset must be non-negative")
    source_states = ticket.source.states()
    candidate_states = ticket.candidate.states()
    changed_count = 0
    changes: list[dict[str, object]] = []
    for index, (source_state, candidate_state) in enumerate(zip(source_states, candidate_states, strict=True)):
        if source_state == candidate_state:
            continue
        if changed_count >= offset and len(changes) < limit:
            point = index_to_point(ticket, index)
            changes.append(
                {
                    "x": point.x,
                    "y": point.y,
                    "z": point.z,
                    "source": source_state,
                    "candidate": candidate_state,
                }
            )
        changed_count += 1
    result = _base(ticket, "diff")
    result.update(
        {
            "changedBlockCount": changed_count,
            "offset": offset,
            "returned": len(changes),
            "truncated": offset + len(changes) < changed_count,
            "changes": changes,
        }
    )
    return result


def _read_operations(value: str) -> list[object]:
    if value == "-":
        raw = sys.stdin.buffer.read(16 * 1024 * 1024 + 1)
    else:
        path = Path(value).expanduser()
        if not path.is_absolute():
            raise TicketError("--ops file path must be absolute, or - for stdin")
        try:
            resolved = path.resolve(strict=True)
        except OSError as exc:
            raise TicketError("operations file does not exist") from exc
        reject_reparse_chain(path, "operations path")
        if not resolved.is_file():
            raise TicketError("operations path must be a regular non-link file")
        try:
            raw = resolved.read_bytes()
        except OSError as exc:
            raise TicketError("cannot read operations file") from exc
    if len(raw) > 16 * 1024 * 1024:
        raise TicketError("operations input exceeds 16 MiB")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise TicketError("operations input is not UTF-8") from exc
    if not text.strip():
        raise TicketError("operations input is empty")

    try:
        value_json = json.loads(text)
    except json.JSONDecodeError:
        operations: list[object] = []
        for line_number, line in enumerate(text.splitlines(), 1):
            if not line.strip():
                continue
            try:
                operations.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise TicketError(f"invalid NDJSON on line {line_number}: {exc.msg}") from exc
        return operations

    if isinstance(value_json, list):
        return value_json
    if isinstance(value_json, dict) and "operations" in value_json:
        operations_value = value_json["operations"]
        if not isinstance(operations_value, list):
            raise TicketError("operations property must be an array")
        return operations_value
    if isinstance(value_json, dict) and "op" in value_json:
        return [value_json]
    raise TicketError("operations JSON must be an array, an operations object, one operation, or NDJSON")


def execute(args: argparse.Namespace) -> dict[str, object]:
    if args.command == "apply-ops":
        preflight = load_ticket(args.ticket)
        with candidate_lock(preflight.directory):
            ticket = load_ticket(preflight.directory)
            if ticket.candidate_hash != preflight.candidate_hash:
                raise TicketError("candidate.schem changed while waiting for the edit lock")
            result = _base(ticket, "apply-ops")
            result.update(apply_operations(ticket, _read_operations(args.ops)))
            result["requiresMinecraftValidation"] = True
            return result

    ticket = load_ticket(args.ticket)
    if args.command == "info":
        return _info(ticket)
    if args.command == "palette":
        return _palette(ticket, args.source)
    if args.command == "get-block":
        return _get_block(ticket, args.source, Point(args.x, args.y, args.z))
    if args.command == "slice":
        return _slice(
            ticket,
            args.source,
            args.y,
            args.x_min,
            args.x_max,
            args.z_min,
            args.z_max,
        )
    if args.command == "surface":
        return _surface(
            ticket,
            args.source,
            args.x_min,
            args.x_max,
            args.z_min,
            args.z_max,
        )
    if args.command == "diff":
        return _diff(ticket, args.limit, args.offset)
    raise TicketError(f"unknown command: {args.command}")


def main(argv: Sequence[str] | None = None) -> int:
    """Run the CLI, always emitting exactly one JSON document to stdout."""

    try:
        args = _parser().parse_args(argv)
        output = execute(args)
        exit_code = 0
    except TicketError as exc:
        output = {"ok": False, "error": str(exc), "errorType": type(exc).__name__}
        exit_code = 2
    except (OSError, ValueError) as exc:
        output = {"ok": False, "error": str(exc), "errorType": type(exc).__name__}
        exit_code = 3
    print(json.dumps(output, ensure_ascii=False, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
