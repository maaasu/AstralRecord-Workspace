#!/usr/bin/env python3
"""Safely invoke the AstralArchitect ticket CLI for one active ticket."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import stat
import subprocess
import sys
from typing import Sequence


ALLOWED_COMMANDS = frozenset(
    {"info", "palette", "get-block", "slice", "surface", "diff", "apply-ops"}
)
EDITABLE_STATES = frozenset({"CREATED", "READY", "ROLLED_BACK"})
TICKET_ID_PATTERN = re.compile(r"[a-z0-9][a-z0-9-]{7,79}\Z")
REQUIRED_TICKET_FILES = ("ticket.json", "source.schem", "candidate.schem")
SKILL_ROOT = Path(__file__).resolve().parents[1]
WORKSPACE_ROOT = SKILL_ROOT.parents[2]
TRUSTED_TOOL_PATH = WORKSPACE_ROOT / "10_plugin" / "AstralArchitect" / "tools" / "ticket_cli.py"


class SafetyError(ValueError):
    """Raised when an invocation crosses the skill safety boundary."""


def _has_parent_reference(raw: str) -> bool:
    normalized = raw.replace("\\", "/")
    return ".." in normalized.split("/")


def _is_reparse_point(path: Path) -> bool:
    info = path.lstat()
    if path.is_symlink():
        return True
    attributes = getattr(info, "st_file_attributes", 0)
    flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return bool(flag and attributes & flag)


def _assert_no_reparse_points(path: Path) -> None:
    current = Path(path.anchor)
    parts = path.parts[1:] if path.anchor else path.parts
    for part in parts:
        current = current / part
        if current.exists() and _is_reparse_point(current):
            raise SafetyError(f"Reparse points and symbolic links are not allowed: {current}")


def _resolve_absolute_existing(raw: str, expected_kind: str) -> Path:
    if not raw or _has_parent_reference(raw):
        raise SafetyError(f"{expected_kind} path must not contain '..'")

    value = Path(raw)
    if not value.is_absolute():
        raise SafetyError(f"{expected_kind} path must be absolute: {raw}")

    _assert_no_reparse_points(value)
    try:
        resolved = value.resolve(strict=True)
    except OSError as error:
        raise SafetyError(f"{expected_kind} path does not exist: {raw}") from error
    _assert_no_reparse_points(resolved)
    return resolved


def _assert_regular_file(path: Path, label: str) -> None:
    if not path.is_file() or _is_reparse_point(path):
        raise SafetyError(f"{label} must be a regular non-reparse file: {path}")


def validate_ticket_path(raw: str) -> tuple[Path, dict[str, object]]:
    """Validate and return an active AstralArchitect ticket directory."""
    ticket = _resolve_absolute_existing(raw, "Ticket")
    if not ticket.is_dir():
        raise SafetyError(f"Ticket path must be a directory: {ticket}")
    if any(part.casefold() == "trash" for part in ticket.parts):
        raise SafetyError("Tickets under trash are never accessible through this skill")
    if ticket.parent.name.casefold() != "tickets":
        raise SafetyError("Ticket directory must be an immediate child of a 'tickets' directory")
    if ticket.parent.parent.name.casefold() != "astralarchitect":
        raise SafetyError("Ticket must belong to an AstralArchitect plugin data directory")
    if not TICKET_ID_PATTERN.fullmatch(ticket.name):
        raise SafetyError(f"Invalid AstralArchitect ticket ID: {ticket.name}")

    for filename in REQUIRED_TICKET_FILES:
        _assert_regular_file(ticket / filename, filename)

    metadata_path = ticket / "ticket.json"
    try:
        with metadata_path.open("rb") as stream:
            raw_metadata = stream.read(1024 * 1024 + 1)
        if len(raw_metadata) > 1024 * 1024:
            raise SafetyError("ticket.json exceeds the 1 MiB safety limit")
        metadata = json.loads(raw_metadata.decode("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SafetyError(f"ticket.json is not valid UTF-8 JSON: {metadata_path}") from error
    if not isinstance(metadata, dict):
        raise SafetyError("ticket.json root must be an object")
    if metadata.get("id") != ticket.name:
        raise SafetyError("ticket.json id does not match the ticket directory name")
    if str(metadata.get("state", "")).upper() == "TRASHED":
        raise SafetyError("TRASHED tickets are never accessible through this skill")
    return ticket, metadata


def resolve_tool_path(ticket: Path) -> Path:
    """Resolve the workspace-owned CLI rather than executable code beside untrusted ticket data."""
    if ticket.parent.parent.name.casefold() != "astralarchitect":
        raise SafetyError("Ticket must belong to an AstralArchitect plugin data directory")
    tool = TRUSTED_TOOL_PATH
    if not tool.exists():
        raise SafetyError("Workspace-trusted AstralArchitect ticket CLI was not found")
    _assert_no_reparse_points(tool)
    tool = tool.resolve(strict=True)

    _assert_regular_file(tool, "Tool")
    if tool.suffix.casefold() != ".py" or tool.name.casefold() != "ticket_cli.py":
        raise SafetyError("Tool must be a regular file named ticket_cli.py")
    expected_root = (WORKSPACE_ROOT / "10_plugin" / "AstralArchitect" / "tools").resolve(strict=True)
    if tool.parent != expected_root:
        raise SafetyError("Tool escaped the trusted AstralArchitect tools directory")
    return tool


def _normalize_cli_args(values: Sequence[str]) -> list[str]:
    args = list(values)
    if args and args[0] == "--":
        args.pop(0)
    if not args:
        raise SafetyError("A ticket CLI command is required")
    if args[0] not in ALLOWED_COMMANDS:
        raise SafetyError(
            f"Command '{args[0]}' is not allowed; choose from {', '.join(sorted(ALLOWED_COMMANDS))}"
        )
    if any(value == "--ticket" or value.startswith("--ticket=") for value in args[1:]):
        raise SafetyError("Forwarded arguments must not override --ticket")
    return args


def build_invocation(argv: Sequence[str]) -> list[str]:
    """Build a validated, shell-free subprocess argument vector."""
    parser = argparse.ArgumentParser(
        description="Safely invoke the AstralArchitect ticket CLI."
    )
    parser.add_argument("--ticket", required=True, help="Absolute active ticket directory")
    parser.add_argument("cli_args", nargs=argparse.REMAINDER, help="Arguments after --")
    options = parser.parse_args(list(argv))

    ticket, metadata = validate_ticket_path(options.ticket)
    cli_args = _normalize_cli_args(options.cli_args)
    if cli_args[0] == "apply-ops":
        state = str(metadata.get("state", "")).upper()
        if state not in EDITABLE_STATES:
            raise SafetyError(
                f"candidate.schem cannot be edited while the ticket state is {state or 'UNKNOWN'}"
            )
    tool = resolve_tool_path(ticket)
    command, *command_args = cli_args
    return [sys.executable, str(tool), command, str(ticket), *command_args]


def main(argv: Sequence[str] | None = None) -> int:
    """Validate the invocation and return the delegated CLI exit code."""
    try:
        command = build_invocation(sys.argv[1:] if argv is None else argv)
        completed = subprocess.run(command, shell=False, check=False)
        return completed.returncode
    except SafetyError as error:
        print(f"Safety error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
