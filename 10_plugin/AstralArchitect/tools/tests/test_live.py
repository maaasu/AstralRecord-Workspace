"""Exercise the live sandbox through the workspace wrapper and its HTTP viewer API."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
from queue import Queue
import subprocess
import sys
from threading import Barrier, Thread
import unittest
from unittest.mock import Mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen

TOOLS_ROOT = Path(__file__).resolve().parents[1]
WRAPPER = TOOLS_ROOT.parents[2] / ".codex/skills/astralarchitect-builder/scripts/invoke_ticket_cli.py"
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

from tests import test_ticket_cli as fixtures  # noqa: E402
from astralarchitect_ticket.live import LiveDraft  # noqa: E402
from astralarchitect_ticket.schematic import Bounds, Point, Ticket, TicketError, read_schematic  # noqa: E402


def states(snapshot: dict) -> list[str]:
    return [
        snapshot["palette"][palette_id]["state"]
        for palette_id, length in snapshot["runs"]
        for _ in range(length)
    ]


def set_top(state: str, expected: str | None = None) -> dict[str, object]:
    operation = {"op": "set", "x": 100, "y": 65, "z": 200, "block": state}
    if expected is not None:
        operation["expect"] = expected
    return operation


class LiveDraftVolumeTest(unittest.TestCase):
    def test_volume_limit_matches_cli(self) -> None:
        origin = Point(0, 0, 0)
        ticket = Mock(spec=Ticket, metadata={"state": "CREATED"})

        # Stop at state materialization so this boundary test needs no large schematic.
        class StatesRequested(Exception):
            pass

        ticket.candidate.states.side_effect = StatesRequested
        ticket.bounds = Bounds(origin, Point(399, 124, 399))
        with self.assertRaises(StatesRequested):
            LiveDraft(ticket)
        ticket.candidate.states.assert_called_once_with()
        ticket.candidate.states.reset_mock()

        ticket.bounds = Bounds(origin, Point(400, 124, 399))
        with self.assertRaisesRegex(TicketError, "supports at most 20000000 ticket cells"):
            LiveDraft(ticket)
        ticket.candidate.states.assert_not_called()


class LiveCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = fixtures.TicketCliTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.session = self.fixture.root / "live-session.json"
        self.operations = self.fixture.root / "operations.json"
        self.process = subprocess.Popen(
            self.command("live", "--session", str(self.session)),
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8",
        )
        self.addCleanup(self._stop)
        first_line: Queue[str] = Queue(maxsize=1)
        assert self.process.stdout is not None
        Thread(target=lambda: first_line.put(self.process.stdout.readline()), daemon=True).start()
        raw = first_line.get(timeout=10)
        if not raw:
            assert self.process.stderr is not None
            self.fail(f"live server exited before announcing its URL: {self.process.stderr.read()}")
        self.ready = json.loads(raw)
        self.assertTrue(self.ready["ok"], self.ready)
        self.url = self.ready["url"].rstrip("/")

    def command(self, *arguments: str) -> list[str]:
        return [sys.executable, str(WRAPPER), "--ticket", str(self.fixture.ticket_dir), "--", *arguments]

    def run_cli(self, command: str, *arguments: str) -> tuple[subprocess.CompletedProcess, dict]:
        process = subprocess.run(
            self.command(command, "--session", str(self.session), *arguments),
            capture_output=True, encoding="utf-8", timeout=10,
        )
        return process, json.loads(process.stdout) if process.stdout else {}

    def submit(self, *operations: dict[str, object]) -> tuple[subprocess.CompletedProcess, dict]:
        self.operations.write_text(json.dumps(operations), encoding="utf-8")
        return self.run_cli("live-ops", "--ops", str(self.operations))

    def get(self, path: str) -> dict:
        with urlopen(self.url + path, timeout=12) as response:
            return json.load(response)

    def _stop(self) -> None:
        try:
            if self.process.poll() is None and self.session.is_file():
                self.run_cli("live-stop")
                self.process.wait(timeout=10)
        finally:
            if self.process.poll() is None:
                self.process.terminate()
                self.process.wait(timeout=5)
            self.process.stdout.close()
            self.process.stderr.close()

    def test_stop_uses_session_identity_when_ticket_has_moved(self) -> None:
        moved = self.fixture.root / "moved-ticket"
        self.fixture.ticket_dir.rename(moved)
        process, result = self.run_cli("live-stop")
        self.assertEqual(0, process.returncode, result or process.stderr)
        self.assertEqual(0, self.process.wait(timeout=10))
        self.assertTrue((moved / "candidate.schem").is_file())

    def test_successive_updates_and_export_match_existing_apply_ops(self) -> None:
        source = self.fixture.source_path.read_bytes()
        before = self.fixture.candidate_path.read_bytes()
        first = self.get("/api/state")
        self.assertEqual((0, [100, 64, 200], [3, 2, 2]), (first["revision"], first["origin"], first["size"]))
        self.assertEqual("minecraft:air", states(first)[6])

        with ThreadPoolExecutor(max_workers=1) as thread:
            waiting = thread.submit(self.get, "/api/changes?since=0")
            process, result = self.submit(set_top("minecraft:prismarine_bricks", "minecraft:air"))
            self.assertEqual(0, process.returncode, result)
            patch = waiting.result(timeout=12)["updates"][0]
        self.assertEqual((1, 1), (patch["revision"], patch["changedBlockCount"]))
        self.assertEqual([[6, len(first["palette"])]], patch["changes"])
        self.assertEqual("minecraft:prismarine_bricks", patch["palette"][0]["state"])

        process, result = self.submit(set_top("minecraft:glass", "minecraft:prismarine_bricks"))
        self.assertEqual(0, process.returncode, result)
        second = self.get("/api/state")
        self.assertEqual(2, second["revision"])
        self.assertEqual("minecraft:glass", states(second)[6])
        self.assertEqual([1, 2], [item["revision"] for item in self.get("/api/changes?since=0")["updates"]])
        self.assertEqual(before, self.fixture.candidate_path.read_bytes())
        self.assertEqual(source, self.fixture.source_path.read_bytes())

        export = self.fixture.root / "export.json"
        process, result = self.run_cli("live-export", "--output", str(export))
        self.assertEqual(0, process.returncode, result)
        self.assertEqual(2, result["operationCount"])
        self.assertEqual(self.ready["baseCandidateSha256"], result["baseCandidateSha256"])
        self.assertEqual([
            set_top("minecraft:prismarine_bricks", "minecraft:air"),
            set_top("minecraft:glass", "minecraft:prismarine_bricks"),
        ], json.loads(export.read_text(encoding="utf-8")))
        duplicate, _ = self.run_cli("live-export", "--output", str(export))
        self.assertNotEqual(0, duplicate.returncode)
        protected_output = self.fixture.ticket_dir / "export.json"
        protected, result = self.run_cli("live-export", "--output", str(protected_output))
        self.assertNotEqual(0, protected.returncode)
        self.assertIn("outside AstralArchitect ticket data", result["error"])
        self.assertFalse(protected_output.exists())

        applied = subprocess.run(
            self.command("apply-ops", "--ops", str(export), "--expect-candidate-sha256",
                         self.ready["baseCandidateSha256"]),
            capture_output=True, encoding="utf-8", timeout=10,
        )
        self.assertEqual(0, applied.returncode, applied.stdout or applied.stderr)
        bounds = Bounds(Point(100, 64, 200), Point(102, 65, 201))
        actual = read_schematic(self.fixture.candidate_path, bounds)
        self.assertEqual(states(second), actual.states())

    def test_plan_replaces_draft_and_exports_only_latest_operations(self) -> None:
        original = self.get("/api/state")
        candidate = self.fixture.candidate_path.read_bytes()
        self.assertEqual(0, self.submit(set_top("minecraft:glass"))[0].returncode)
        path = self.fixture.root / "plan.json"
        rough = {"op": "set", "at": [1, 0, 0], "block": "minecraft:stone"}
        detail = {"op": "set", "at": [0, 0, 0], "block": "minecraft:gold_block"}
        plan = {"version": 1, "origin": [100, 65, 200],
                "parts": {"bay": {"rough": [rough], "detail": [detail]}},
                "placements": [{"part": "bay", "at": [0, 0, 0]}]}
        path.write_text(json.dumps(plan), encoding="utf-8")
        process, result = self.run_cli("live-ops", "--plan", str(path))
        self.assertEqual(0, process.returncode, result)
        self.assertEqual(("detail", 2), (result["stage"], result["operationCount"]))
        process, result = self.run_cli("live-ops", "--plan", str(path), "--stage", "rough")
        self.assertEqual(0, process.returncode, result)
        self.assertEqual(("rough", 1, 3), (result["stage"], result["batchChangedBlockCount"], result["revision"]))
        expected = states(original)
        expected[7] = "minecraft:stone"
        accepted = self.get("/api/state")
        self.assertEqual(expected, states(accepted))
        self.assertTrue(self.get("/api/changes?since=2")["reset"])
        rough["at"] = [100, 0, 0]
        path.write_text(json.dumps(plan), encoding="utf-8")
        self.assertNotEqual(0, self.run_cli("live-ops", "--plan", str(path))[0].returncode)
        self.assertEqual(accepted, self.get("/api/state"))
        self.assertEqual(0, self.submit(set_top("minecraft:glass"))[0].returncode)
        self.assertTrue(self.get("/api/changes?since=2")["reset"])
        self.assertEqual([4], [p["revision"] for p in self.get("/api/changes?since=3")["updates"]])
        export = self.fixture.root / "export.json"
        process, result = self.run_cli("live-export", "--output", str(export))
        self.assertEqual(0, process.returncode, result)
        self.assertEqual([dict(set_top("minecraft:stone"), x=101), set_top("minecraft:glass")],
                         json.loads(export.read_text(encoding="utf-8")))
        self.assertEqual(candidate, self.fixture.candidate_path.read_bytes())

    def test_rejected_batch_leaves_the_draft_and_ticket_unchanged(self) -> None:
        before = self.get("/api/state")
        process, result = self.submit(
            set_top("minecraft:glass", "minecraft:air"),
            {"op": "set", "x": 100, "y": 64, "z": 200, "block": "minecraft:stone"},
        )
        self.assertNotEqual(0, process.returncode, result)
        self.assertIn("block entity", result["error"])
        self.assertEqual(before, self.get("/api/state"))
        self.assertEqual(self.fixture.source_path.read_bytes(), self.fixture.candidate_path.read_bytes())

    def test_viewer_cannot_mutate_or_be_loaded_with_foreign_host(self) -> None:
        mutation = Request(
            self.url + "/api/ops",
            json.dumps([set_top("minecraft:glass")]).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(HTTPError) as failure:
            urlopen(mutation, timeout=3)
        self.assertEqual(403, failure.exception.code)
        failure.exception.close()
        with self.assertRaises(HTTPError) as failure:
            urlopen(Request(self.url + "/api/state", headers={"Host": "example.test"}), timeout=3)
        self.assertEqual(403, failure.exception.code)
        failure.exception.close()
        self.assertEqual(0, self.get("/api/state")["revision"])


    def test_concurrent_expect_checks_and_expired_patch_history(self) -> None:
        credentials = json.loads(self.session.read_text(encoding="utf-8"))
        headers = {"Authorization": "Bearer " + credentials["token"], "Content-Type": "application/json"}

        def send(operation: dict) -> tuple[int, dict]:
            request = Request(self.url + "/api/ops", json.dumps([operation]).encode("ascii"), headers=headers)
            try:
                with urlopen(request, timeout=5) as response:
                    return response.status, json.load(response)
            except HTTPError as response:
                with response:
                    return response.code, json.load(response)

        barrier = Barrier(2)

        def submit_racing_edit(block: str) -> tuple[int, dict]:
            barrier.wait(timeout=5)
            return send(set_top(block, "minecraft:air"))

        materials = ["minecraft:glass", "minecraft:dirt"]
        with ThreadPoolExecutor(max_workers=2) as thread:
            outcomes = list(thread.map(submit_racing_edit, materials))
        self.assertEqual([200, 400], sorted(status for status, _ in outcomes))
        [winner] = [materials[index] for index, (status, _) in enumerate(outcomes) if status == 200]
        first = self.get("/api/state")
        self.assertEqual((1, 1, winner), (first["revision"], first["changedBlockCount"], states(first)[6]))

        for _ in range(18):
            status, response = send(set_top(winner))
            self.assertEqual(200, status, response)
        self.assertEqual({"revision": 19, "reset": True}, self.get("/api/changes?since=0"))
        latest = self.get("/api/changes?since=18")["updates"]
        self.assertEqual(1, len(latest))
        self.assertEqual((19, 1, []), (latest[0]["revision"], latest[0]["changedBlockCount"], latest[0]["changes"]))
        self.assertEqual(winner, states(self.get("/api/state"))[6])
