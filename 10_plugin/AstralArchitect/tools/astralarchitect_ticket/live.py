"""Loopback-only live viewer for a draft of one AstralArchitect ticket."""

from __future__ import annotations

from collections import deque
from hmac import compare_digest
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from itertools import groupby
import json
from pathlib import Path
import re
import secrets
from threading import Condition, Thread
from typing import TYPE_CHECKING
from urllib.parse import parse_qs

from .preview import block_model
from .schematic import (
    MAX_OPERATION_BYTES,
    MAX_OPERATIONS,
    MAX_TICKET_VOLUME,
    Ticket,
    TicketError,
    reject_reparse_chain,
    require_editable_ticket,
    simulate_operations,
)

if TYPE_CHECKING:
    from .schematic import ProposedOperations
    from .vanilla_assets import VanillaAssets

_LOCAL_ADDRESS = "127.0.0.1"
_TEXTURE_ROUTE = re.compile(r"/api/textures/([0-9]{1,5})\.png")
_MAX_PATCH_CELLS = 32_768
_PATCH_HISTORY = 16
_VIEW_ROOT = Path(__file__).with_name("live_view")
_ASSETS = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/renderer.js": ("renderer.js", "text/javascript; charset=utf-8"),
    "/styles.css": ("styles.css", "text/css; charset=utf-8"),
}


def json_bytes(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode("ascii")


class LiveDraft:
    """One editable in-memory snapshot and its accepted operations."""

    def __init__(self, ticket: Ticket, assets: VanillaAssets | None = None) -> None:
        require_editable_ticket(ticket)
        if ticket.bounds.volume > MAX_TICKET_VOLUME:
            raise TicketError(f"live viewer supports at most {MAX_TICKET_VOLUME} ticket cells")
        self.ticket = ticket
        self.assets = assets
        self.minecraft_version = "1.21.11" if assets is not None else None
        self.condition = Condition()
        self.closing = False
        self.initial = ticket.candidate.states()
        self.states = self.initial
        self.palette: list[dict] = []
        self.palette_ids: dict[str, int] = {}
        self.blocks = [self._palette_id(state) for state in self.states]
        self.revision = 0
        self.changed_count = 0
        self.history: deque[dict] = deque(maxlen=_PATCH_HISTORY)
        self.operations: list[object] = []
        self.operation_bytes = 2  # The JSON array brackets
        self.visits = 0

    def _palette_model(self, state: str) -> dict:
        model = self.assets.model(state) if self.assets is not None else None
        if model is None:
            approximate = block_model(state)
            model = {
                "state": state,
                "color": approximate.color if approximate else (0, 0, 0),
                "opacity": approximate.opacity if approximate else 0,
                "boxes": approximate.boxes if approximate else (),
            }
        return model

    def _palette_id(self, state: str) -> int:
        existing = self.palette_ids.get(state)
        if existing is not None:
            return existing
        index = len(self.palette)
        self.palette.append(self._palette_model(state))
        self.palette_ids[state] = index
        return index

    def snapshot(self) -> dict:
        bounds = self.ticket.bounds
        origin = bounds.minimum
        with self.condition:
            return {
                "ticketId": self.ticket.metadata["id"],
                "origin": (origin.x, origin.y, origin.z),
                "size": (bounds.width, bounds.height, bounds.length),
                "revision": self.revision,
                "changedBlockCount": self.changed_count,
                "minecraftVersion": self.minecraft_version,
                "palette": self.palette.copy(),
                "runs": [(block_id, sum(1 for _ in run)) for block_id, run in groupby(self.blocks)],
            }

    def changes(self, since: int) -> dict:
        with self.condition:
            self.condition.wait_for(lambda: self.revision != since or self.closing, timeout=8)
            if since == self.revision:
                return {"revision": self.revision, "updates": []}
            if (
                since > self.revision or not self.history
                or since < self.history[0]["revision"] - 1
            ):
                return {"revision": self.revision, "reset": True}
            return {
                "revision": self.revision,
                "updates": [patch for patch in self.history if patch["revision"] > since],
            }

    def submit(self, operations: object, payload_size: int, *, replace: bool = False) -> dict:
        if not isinstance(operations, list) or not operations:
            raise TicketError("live operations must be a nonempty JSON array")
        with self.condition:
            if self.closing:
                raise TicketError("live session is closing")
            previous_ops = [] if replace else self.operations
            if len(previous_ops) + len(operations) > MAX_OPERATIONS:
                raise TicketError("too many cumulative live operations for apply-ops")
            export_size = payload_size
            if previous_ops:
                export_size += self.operation_bytes - 1  # Splice the two JSON arrays with a comma.
            if export_size > MAX_OPERATION_BYTES:
                raise TicketError("cumulative live operations exceed the 16 MiB apply-ops limit")
            plan = simulate_operations(
                self.ticket, operations, base_states=self.initial if replace else self.states,
                previous_visits=0 if replace else self.visits,
            )
            if replace:
                # A reset allows a fresh palette. Bake it before publishing the new revision.
                palette_ids = {state: index for index, state in enumerate(dict.fromkeys(plan.states))}
                palette = [
                    self.palette[self.palette_ids[state]] if state in self.palette_ids else self._palette_model(state)
                    for state in palette_ids
                ]
                blocks = [palette_ids[state] for state in plan.states]
                changed = sum(old != new for old, new in zip(self.states, plan.states))
                self.palette, self.palette_ids, self.blocks = palette, palette_ids, blocks
                self.changed_count = len(plan.changed_indices)
                self.history.clear()
            else:
                changed = self._append_patch(plan)
            self.states = plan.states
            self.revision += 1
            previous_ops.extend(operations)
            self.operations = previous_ops
            self.operation_bytes = export_size
            self.visits = plan.visits
            self.condition.notify_all()
            return {
                "ok": True,
                "revision": self.revision,
                "batchChangedBlockCount": changed,
                "changedBlockCount": self.changed_count,
            }

    def _append_patch(self, plan: ProposedOperations) -> int:
        # Bake every new model before publishing any block or revision change.
        new_states = dict.fromkeys(
            plan.states[index] for index in plan.changed_indices
            if plan.states[index] not in self.palette_ids
        )
        new_models = [self._palette_model(state) for state in new_states]
        new_palette_start = len(self.palette)
        self.palette.extend(new_models)
        self.palette_ids.update((state, new_palette_start + offset) for offset, state in enumerate(new_states))
        patch = [] if len(plan.changed_indices) <= _MAX_PATCH_CELLS else None
        for index in plan.changed_indices:
            was_changed = self.states[index] != self.initial[index]
            is_changed = plan.states[index] != self.initial[index]
            self.changed_count += is_changed - was_changed
            block_id = self.palette_ids[plan.states[index]]
            self.blocks[index] = block_id
            if patch is not None:
                patch.append((index, block_id))
        if patch is None:
            self.history.clear()
        else:
            self.history.append({
                "revision": self.revision + 1,
                "changedBlockCount": self.changed_count,
                "palette": self.palette[new_palette_start:],
                "changes": patch,
            })
        return len(plan.changed_indices)

    def export(self) -> dict:
        with self.condition:
            if not self.operations:
                raise TicketError("no live operations to export")
            return {
                "ok": True,
                "revision": self.revision,
                "baseCandidateSha256": self.ticket.candidate_hash,
                "operationCount": len(self.operations),
                "operations": self.operations.copy(),
            }

    def close(self) -> None:
        with self.condition:
            self.closing = True
            self.condition.notify_all()


class LiveServer(ThreadingHTTPServer):
    """Serve a browser viewer and a separately authenticated CLI channel."""

    def __init__(self, ticket: Ticket, assets: VanillaAssets | None = None) -> None:
        self.draft = LiveDraft(ticket, assets)
        self.token = secrets.token_urlsafe(32)
        self.assets = {
            route: ((_VIEW_ROOT / name).read_bytes(), content_type)
            for route, (name, content_type) in _ASSETS.items()
        }
        super().__init__((_LOCAL_ADDRESS, 0), _LiveHandler)

    @property
    def authority(self) -> str:
        return f"{_LOCAL_ADDRESS}:{self.server_port}"

    @property
    def url(self) -> str:
        return f"http://{self.authority}/"

    def credentials(self) -> dict:
        return {
            "version": 1,
            "ticket": str(self.draft.ticket.directory),
            "port": self.server_port,
            "token": self.token,
        }

    def serve(self) -> None:
        try:
            self.serve_forever(poll_interval=0.1)
        except KeyboardInterrupt:
            pass
        finally:
            self.draft.close()


class _LiveHandler(BaseHTTPRequestHandler):
    server: LiveServer

    def setup(self) -> None:
        self.request.settimeout(20)
        super().setup()

    def log_message(self, format: str, *args: object) -> None:
        # The foreground CLI emits one JSON line announcing the ready server.
        pass

    def _send(self, status: int, data: bytes, content_type: str) -> None:
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
            )
            self.end_headers()
            self.wfile.write(data)
        except (ConnectionError, TimeoutError):
            # A closed tab or a cancelled long poll can abandon its response.
            pass

    def _json(self, status: int, value: dict) -> None:
        self._send(status, json_bytes(value), "application/json; charset=utf-8")

    def _failure(self, status: int, message: str) -> None:
        self._json(status, {"ok": False, "error": message})

    def _check_host(self) -> bool:
        if self.headers.get_all("Host") == [self.server.authority]:
            return True
        self._failure(403, "live viewer requires its loopback host")
        return False

    def _authorized(self) -> bool:
        value = self.headers.get("Authorization", "")
        expected = "Bearer " + self.server.token
        if compare_digest(value.encode("latin-1"), expected.encode("ascii")):
            return True
        self._failure(403, "live session credentials are required")
        return False

    def do_GET(self) -> None:
        if not self._check_host():
            return
        path, _, query = self.path.partition("?")
        if texture_match := _TEXTURE_ROUTE.fullmatch(path):
            assets = self.server.draft.assets
            data = assets.texture(int(texture_match[1])) if assets is not None else None
            if data is None:
                self._failure(404, "unknown Minecraft texture")
            else:
                self._send(200, data, "image/png")
        elif path in self.server.assets:
            data, content_type = self.server.assets[path]
            self._send(200, data, content_type)
        elif path == "/api/state":
            self._json(200, self.server.draft.snapshot())
        elif path == "/api/changes":
            try:
                params = parse_qs(query, max_num_fields=1)
                since = int(params.get("since", [""])[0])
                if since < 0:
                    raise ValueError
            except ValueError:
                self._failure(400, "a nonnegative revision is required")
                return
            self._json(200, self.server.draft.changes(since))
        elif path == "/api/export":
            if self._authorized():
                try:
                    self._json(200, self.server.draft.export())
                except TicketError as error:
                    self._failure(400, str(error))
        else:
            self._failure(404, "unknown live viewer path")

    def do_POST(self) -> None:
        if not self._check_host() or not self._authorized():
            return
        if self.path == "/api/stop":
            self.server.draft.close()
            self._json(200, {"ok": True})
            Thread(target=self.server.shutdown, daemon=True).start()
            return
        if self.path not in {"/api/ops", "/api/replace"}:
            self._failure(404, "unknown live viewer path")
            return
        try:
            length = int(self.headers.get("Content-Length", ""))
            if not 0 < length <= MAX_OPERATION_BYTES or self.headers.get("Transfer-Encoding"):
                raise TicketError("invalid live operations size; limit is 16 MiB")
            body = self.rfile.read(length)
            if len(body) != length:
                raise TicketError("live operations were incomplete")
            operations = json.loads(body)
            result = self.server.draft.submit(operations, len(json_bytes(operations)), replace=self.path == "/api/replace")
        except (ValueError, TimeoutError, RecursionError) as error:
            self._failure(400, str(error))
            return
        self._json(200, result)


def request_live(
    ticket_dir: Path, session: str, method: str, endpoint: str, body: bytes | None = None
) -> dict:
    """Use an absolute session file to call a known endpoint on local loopback."""

    path = Path(session)
    if not path.is_absolute() or ".." in path.parts or not path.is_file():
        raise TicketError("--session must be an absolute path to a live session JSON file")
    reject_reparse_chain(path, "live session path")
    with path.open("rb") as stream:
        raw = stream.read(16_385)
    try:
        if len(raw) > 16_384:
            raise ValueError
        credentials = json.loads(raw)
        port = credentials["port"]
        token = credentials["token"]
        if (
            credentials["version"] != 1 or Path(credentials["ticket"]) != ticket_dir
            or type(port) is not int or not 1 <= port <= 65_535
            or not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", token)
        ):
            raise ValueError
    except (TypeError, KeyError, ValueError) as error:
        raise TicketError("--session is invalid or belongs to a different ticket") from error
    connection = http.client.HTTPConnection(_LOCAL_ADDRESS, port, timeout=30)
    try:
        connection.request(method, endpoint, body=body, headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
        })
        with connection.getresponse() as response:
            reply = json.loads(response.read(MAX_OPERATION_BYTES + 1025))
            if not isinstance(reply, dict):
                raise ValueError
            if response.status != 200 or not reply.get("ok"):
                raise TicketError(str(reply.get("error") or "live request failed"))
            return reply
    except TicketError:
        raise
    except (OSError, http.client.HTTPException, ValueError) as error:
        raise TicketError("live session is unavailable; start a new live viewer") from error
    finally:
        connection.close()
