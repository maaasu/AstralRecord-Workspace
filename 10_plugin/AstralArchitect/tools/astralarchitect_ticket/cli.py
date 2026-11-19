"""JSON command-line interface for safe AstralArchitect ticket inspection/editing."""

from __future__ import annotations

import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import sys
from typing import Sequence

from .blueprint import compile_blueprint
from .live import LiveServer, json_bytes, request_live
from .preview import PreviewHit, render_preview
from .schematic import (
    MAX_OPERATION_BYTES,
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
    simulate_operations,
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
    slice_scene = slice_parser.add_mutually_exclusive_group()
    slice_scene.add_argument("--source", action="store_true", help="inspect source.schem instead")
    slice_scene.add_argument("--plan", help="inspect a blueprint on the current candidate")
    slice_parser.add_argument("--stage", choices=("rough", "detail"), help="blueprint stage (default: detail)")
    _add_horizontal_window_arguments(slice_parser)

    surface = command("surface", "show the top non-air block for every X/Z column")
    surface.add_argument("--source", action="store_true", help="inspect source.schem instead")
    _add_horizontal_window_arguments(surface)

    diff = command("diff", "compare source.schem and candidate.schem")
    diff.add_argument("--limit", type=int, default=1000, help="maximum detailed changes to return")
    diff.add_argument("--offset", type=int, default=0, help="skip this many changed blocks")

    preview = command("preview", "capture a 3D PNG of a ticket or proposed operations")
    scene = preview.add_mutually_exclusive_group()
    scene.add_argument("--source", action="store_true", help="render source.schem instead")
    scene.add_argument("--ops", help="simulate a JSON/NDJSON file, or - for stdin")
    scene.add_argument("--plan", help="simulate a modular blueprint JSON file, or - for stdin")
    preview.add_argument("--stage", choices=("rough", "detail"), help="blueprint preview stage (default: detail)")
    preview.add_argument("--eye", type=float, nargs=3, required=True, metavar=("X", "Y", "Z"))
    preview.add_argument("--target", type=float, nargs=3, required=True, metavar=("X", "Y", "Z"))
    preview.add_argument("--width", type=int, default=720, help="output width in pixels")
    preview.add_argument("--height", type=int, default=540, help="output height in pixels")
    preview.add_argument("--clip-y", type=int, help="hide blocks above this world Y")
    preview.add_argument("--style", choices=("material", "clay"), default="material",
                         help="still preview style (default: material)")
    preview.add_argument("--pick", action="append", type=int, nargs=2, metavar=("X", "Y"),
                         help="report the front block at a PNG pixel (top left is 0 0, repeatable)")
    preview.add_argument("--output", required=True, help="absolute path for a new PNG outside ticket data")

    live = command("live", "start a local browser viewer for an editable draft")
    live.add_argument("--session", required=True, help="absolute path for a new session JSON file")
    live.add_argument("--minecraft-jar", help="absolute path to the Minecraft Java 1.21.11 client JAR")

    live_ops = command("live-ops", "submit one operation batch to a running live viewer")
    live_ops.add_argument("--session", required=True, help="absolute path to the live session file")
    live_input = live_ops.add_mutually_exclusive_group(required=True)
    live_input.add_argument("--ops", help="append JSON/NDJSON operations, or - for stdin")
    live_input.add_argument("--plan", help="replace the draft with a blueprint JSON file, or - for stdin")
    live_ops.add_argument("--stage", choices=("rough", "detail"), help="blueprint stage (default: detail)")

    live_export = command("live-export", "export the current live draft's ordered operations")
    live_export.add_argument("--session", required=True, help="absolute path to the live session file")
    live_export.add_argument("--output", required=True, help="absolute path for a new operations JSON")

    live_stop = command("live-stop", "stop the live viewer")
    live_stop.add_argument("--session", required=True, help="absolute path to the live session file")

    apply_ops = command("apply-ops", "atomically apply JSON/NDJSON operations to candidate.schem")
    proposal = apply_ops.add_mutually_exclusive_group(required=True)
    proposal.add_argument("--ops", help="absolute JSON/NDJSON file path, or - for stdin")
    proposal.add_argument("--plan", help="apply the detail stage of the reviewed blueprint")
    apply_ops.add_argument("--expect-stage-sha256", help="stageSha256 from the detail preview (required with --plan)")
    apply_ops.add_argument("--expect-candidate-sha256", help="candidate hash from preview")
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
    proposal: list[object] | None = None,
) -> dict[str, object]:
    if not ticket.bounds.minimum.y <= world_y <= ticket.bounds.maximum.y:
        raise TicketError(f"Y coordinate {world_y} is outside ticket bounds")
    selected_x_min, selected_x_max, selected_z_min, selected_z_max = _inspection_window(
        ticket, x_min, x_max, z_min, z_max
    )
    schematic = _schematic(ticket, use_source)
    state_names = schematic.states() if proposal is None else simulate_operations(ticket, proposal).states
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
            "schematic": "proposed" if proposal is not None else ("source" if use_source else "candidate"),
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


def _read_input(value: str, option: str) -> bytes:
    label = "operations" if option == "--ops" else "blueprint"
    if value == "-":
        raw = sys.stdin.buffer.read(MAX_OPERATION_BYTES + 1)
    else:
        path = Path(value).expanduser()
        if not path.is_absolute():
            raise TicketError(f"{option} file path must be absolute, or - for stdin")
        try:
            resolved = path.resolve(strict=True)
        except OSError as exc:
            raise TicketError(f"{label} file does not exist") from exc
        reject_reparse_chain(path, f"{label} path")
        if not resolved.is_file():
            raise TicketError(f"{label} path must be a regular non-link file")
        try:
            with resolved.open("rb") as stream:
                raw = stream.read(MAX_OPERATION_BYTES + 1)
        except OSError as exc:
            raise TicketError(f"cannot read {label} file") from exc
    if len(raw) > MAX_OPERATION_BYTES:
        raise TicketError(f"{label} input exceeds 16 MiB")
    return raw


def _unique_fields(items: list[tuple[str, object]]) -> dict[str, object]:
    result = {}
    for name, value in items:
        if name in result:
            raise TicketError(f"duplicate blueprint field: {name}")
        result[name] = value
    return result


def _proposal(args: argparse.Namespace) -> tuple[list[object], dict[str, object]]:
    if args.plan is None:
        if getattr(args, "expect_stage_sha256", None) is not None:
            raise TicketError("--expect-stage-sha256 requires --plan")
        return _read_operations(args.ops), {}
    raw = _read_input(args.plan, "--plan")
    stage = getattr(args, "stage", None) or "detail"
    try:
        design = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=_unique_fields)
        operations = compile_blueprint(design, stage)
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise TicketError(f"invalid blueprint: {exc}") from exc
    # Also bind the resulting operations in case the compiler changed after review.
    fingerprint = hashlib.sha256(stage.encode("ascii") + b"\0")
    fingerprint.update(raw)
    fingerprint.update(b"\0")
    fingerprint.update(json_bytes(operations))
    digest = fingerprint.hexdigest()
    if args.command == "apply-ops":
        if not args.expect_candidate_sha256 or not args.expect_stage_sha256:
            raise TicketError("--plan requires both hashes from the detail preview")
        if args.expect_stage_sha256 != digest:
            raise TicketError("blueprint or stage differs from the detail preview; preview again before applying")
    return operations, {"stageSha256": digest, "stage": stage, "operationCount": len(operations)}


def _read_operations(value: str) -> list[object]:
    raw = _read_input(value, "--ops")
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


def _output_path(raw: str, ticket_dir: Path, suffix: str, label: str) -> Path:
    path = Path(raw)
    if (
        not path.is_absolute() or ".." in path.parts
        or path.suffix.casefold() != suffix or ":" in path.name
    ):
        raise TicketError(f"{label} must be an absolute path for a standalone {suffix[1:].upper()} without '..'")
    reject_reparse_chain(path.parent, f"{label} parent")
    parent = path.parent.resolve(strict=True)
    parts = tuple(part.casefold() for part in parent.parts)
    plugin_data = ticket_dir.parent.parent
    if parent.is_relative_to(plugin_data) or any(
        first == "astralarchitect" and second in {"tickets", "trash", ".locks"}
        for first, second in zip(parts, parts[1:])
    ):
        raise TicketError(f"{label} must be outside AstralArchitect ticket data")
    output = parent / path.name
    if output.exists() or output.is_symlink():
        raise TicketError(f"{label} already exists; choose a new path")
    return output


def _preview(ticket: Ticket, args: argparse.Namespace) -> dict[str, object]:
    _output_path(args.output, ticket.directory, ".png", "--output")
    result = _base(ticket, "preview")
    result.update({
        "scene": "source" if args.source else "candidate",
        "sourceSha256": ticket.source_hash,
        "baseCandidateSha256": ticket.candidate_hash,
        "eye": args.eye,
        "target": args.target,
        "width": args.width,
        "height": args.height,
        "clipY": args.clip_y,
    })
    if args.stage is not None and args.plan is None:
        raise TicketError("--stage requires --plan")
    if args.ops is None and args.plan is None:
        states = _schematic(ticket, args.source).states()
    else:
        operations, description = _proposal(args)
        plan = simulate_operations(ticket, operations)
        result.update(description)
        states = plan.states
        result.update({
            "scene": "proposed",
            "touchedBlockCount": plan.touched_count,
            "changedBlockCount": len(plan.changed_indices),
        })
    picks = [(x, y) for x, y in args.pick] if args.pick else []
    pick_hits: dict[tuple[int, int], PreviewHit | None] = dict.fromkeys(picks)
    png = render_preview(
        ticket, states, eye=tuple(args.eye), target=tuple(args.target),
        width=args.width, height=args.height, clip_y=args.clip_y, style=args.style,
        pick_hits=pick_hits,
    )
    if picks:
        pick_results = []
        for x, y in picks:
            position, state = pick_hits[x, y] or (None, None)
            pick_results.append({
                "pixel": {"x": x, "y": y},
                "position": _point_json(position) if position is not None else None,
                "block": state,
            })
        result["picks"] = pick_results
    if args.style == "clay":
        result["style"] = args.style
    # Rendering can take several seconds, so validate the destination again.
    output = _output_path(args.output, ticket.directory, ".png", "--output")
    _write_new_file(output, png)
    result["output"] = str(output)
    result["imageSha256"] = hashlib.sha256(png).hexdigest()
    return result


def _write_new_file(output: Path, data: bytes, mode: int = 0o666) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    descriptor = os.open(output, flags, mode)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
    except OSError:
        output.unlink(missing_ok=True)
        raise


def _minecraft_client_jar(explicit: str | None) -> Path | None:
    if explicit is not None:
        jar = Path(explicit)
        if not jar.is_absolute() or ".." in jar.parts or jar.suffix.casefold() != ".jar" or not jar.is_file():
            raise TicketError("--minecraft-jar must be an absolute path to a Minecraft Java 1.21.11 client JAR")
        reject_reparse_chain(jar, "Minecraft client JAR path")
        return jar

    minecraft = Path.home() / ".minecraft"
    if sys.platform == "win32" and os.environ.get("APPDATA"):
        minecraft = Path(os.environ["APPDATA"]) / ".minecraft"
    elif sys.platform == "darwin":
        minecraft = Path.home() / "Library" / "Application Support" / "minecraft"
    locations = [minecraft / "versions" / "1.21.11" / "1.21.11.jar"]
    if os.environ.get("LOCALAPPDATA"):
        locations.append(Path(os.environ["LOCALAPPDATA"]) / "AstralArchitect" / "minecraft" / "1.21.11.jar")
    for jar in locations:
        if jar.is_file():
            reject_reparse_chain(jar, "Minecraft client JAR path")
            return jar
    return None


def _serve_live(args: argparse.Namespace) -> None:
    ticket = load_ticket(args.ticket)
    _output_path(args.session, ticket.directory, ".json", "--session")
    jar = _minecraft_client_jar(args.minecraft_jar)
    with ExitStack() as stack:
        assets = None
        if jar is not None:
            from .vanilla_assets import VanillaAssets

            assets = VanillaAssets(jar)
            stack.callback(assets.close)
        server = stack.enter_context(LiveServer(ticket, assets))
        session = _output_path(args.session, ticket.directory, ".json", "--session")
        _write_new_file(session, json_bytes(server.credentials()), mode=0o600)
        result = _base(ticket, "live")
        result.update({
            "url": server.url,
            "session": str(session),
            "baseCandidateSha256": ticket.candidate_hash,
            "minecraftVersion": server.draft.minecraft_version,
        })
        print(json_bytes(result).decode("ascii"), flush=True)
        server.serve()


def _live_command(args: argparse.Namespace) -> dict[str, object]:
    ticket = Path(args.ticket)
    if not ticket.is_absolute() or ".." in ticket.parts:
        raise TicketError("ticket path must be absolute and must not contain '..'")
    if args.command == "live-stop":
        # Match the original path in the credentials without reading ticket data.
        result = request_live(ticket, args.session, "POST", "/api/stop")
        return {"command": args.command, "ticketId": ticket.name, **result}
    reject_reparse_chain(ticket, "ticket path")
    ticket = ticket.resolve(strict=True)
    if args.command == "live-ops":
        if args.stage is not None and args.plan is None:
            raise TicketError("--stage requires --plan")
        operations, metadata = _proposal(args)
        path = "/api/replace" if args.plan is not None else "/api/ops"
        result = request_live(ticket, args.session, "POST", path, json_bytes(operations))
        result.update(metadata)
    else:
        _output_path(args.output, ticket, ".json", "--output")
        result = request_live(ticket, args.session, "GET", "/api/export")
        output = _output_path(args.output, ticket, ".json", "--output")
        _write_new_file(output, json_bytes(result.pop("operations")))
        result["output"] = str(output)
    return {"command": args.command, "ticketId": ticket.name, **result}


def execute(args: argparse.Namespace) -> dict[str, object] | None:
    if args.command == "live":
        _serve_live(args)
        return None
    if args.command in {"live-ops", "live-export", "live-stop"}:
        return _live_command(args)
    if args.command == "apply-ops":
        preflight = load_ticket(args.ticket)
        with candidate_lock(preflight.directory):
            ticket = load_ticket(preflight.directory)
            if ticket.candidate_hash != preflight.candidate_hash:
                raise TicketError("candidate.schem changed while waiting for the edit lock")
            if args.expect_candidate_sha256 is not None and ticket.candidate_hash != args.expect_candidate_sha256:
                raise TicketError("candidate.schem differs from the preview base; preview again before applying")
            result = _base(ticket, "apply-ops")
            operations, description = _proposal(args)
            result.update(apply_operations(ticket, operations))
            result.update(description)
            result["requiresMinecraftValidation"] = True
            return result

    ticket = load_ticket(args.ticket)
    if args.command == "preview":
        return _preview(ticket, args)
    if args.command == "info":
        return _info(ticket)
    if args.command == "palette":
        return _palette(ticket, args.source)
    if args.command == "get-block":
        return _get_block(ticket, args.source, Point(args.x, args.y, args.z))
    if args.command == "slice":
        if args.stage is not None and args.plan is None:
            raise TicketError("--stage requires --plan")
        operations = _proposal(args)[0] if args.plan is not None else None
        return _slice(
            ticket,
            args.source,
            args.y,
            args.x_min,
            args.x_max,
            args.z_min,
            args.z_max,
            operations,
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
    if output is not None:
        print(json_bytes(output).decode("ascii"))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
