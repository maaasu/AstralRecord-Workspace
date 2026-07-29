from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


TOOLS_ROOT = Path(__file__).resolve().parents[1]
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

from astralarchitect_ticket import nbt  # noqa: E402
from astralarchitect_ticket import cli as cli_module  # noqa: E402
from astralarchitect_ticket import schematic as schematic_module  # noqa: E402
from astralarchitect_ticket.schematic import (  # noqa: E402
    Bounds,
    Point,
    candidate_lock,
    encode_varints,
    read_schematic,
)


class TicketCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.ticket_dir = self.root / "AstralArchitect" / "tickets" / "test-ticket"
        self.ticket_dir.mkdir(parents=True)
        lock_directory = self.ticket_dir.parent.parent / ".locks"
        lock_directory.mkdir()
        (lock_directory / "test-ticket.lock").write_bytes(b"\0")
        self.source_path = self.ticket_dir / "source.schem"
        self.candidate_path = self.ticket_dir / "candidate.schem"
        self._write_synthetic_schematic(self.source_path)
        shutil.copyfile(self.source_path, self.candidate_path)
        source_hash = self._sha256(self.source_path)
        metadata = {
            "schemaVersion": 1,
            "id": "test-ticket",
            "name": "tiny-test",
            "state": "CREATED",
            "ownerUuid": "00000000-0000-0000-0000-000000000001",
            "ownerName": "builder",
            "worldUuid": "00000000-0000-0000-0000-000000000002",
            "worldName": "world",
            "bounds": {
                "min": {"x": 100, "y": 64, "z": 200},
                "max": {"x": 102, "y": 65, "z": 201},
            },
            "anchor": {"x": 101, "y": 64, "z": 200},
            "anchorBlockState": "minecraft:stone",
            "blockCount": 12,
            "sourceSha256": source_hash,
            "candidateSha256": source_hash,
        }
        (self.ticket_dir / "ticket.json").write_text(
            json.dumps(metadata), encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def _write_synthetic_schematic(path: Path) -> None:
        # Index order is x + z*width + y*width*length.
        palette = {
            "minecraft:chest[facing=north,type=single,waterlogged=false]": 0,
            "minecraft:stone": 1,
            "minecraft:air": 2,
        }
        block_ids = [0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2]
        block_entity = nbt.Tag(
            nbt.TAG_COMPOUND,
            {
                "Pos": nbt.Tag(nbt.TAG_INT_ARRAY, [0, 0, 0]),
                "Id": nbt.Tag(nbt.TAG_STRING, "minecraft:chest"),
                "CustomName": nbt.Tag(nbt.TAG_STRING, '{"text":"keep me"}'),
            },
        )
        schematic = {
            "Version": nbt.Tag(nbt.TAG_INT, 3),
            "DataVersion": nbt.Tag(nbt.TAG_INT, 4325),
            "Metadata": nbt.Tag(
                nbt.TAG_COMPOUND,
                {
                    "AstralTest": nbt.Tag(nbt.TAG_STRING, "preserve-this"),
                    "ModifiedUtf8": nbt.Tag(nbt.TAG_STRING, "日本\u0000🏰"),
                    "UnknownLong": nbt.Tag(nbt.TAG_LONG, 9_223_372_036_854_775_000),
                    "WorldEdit": nbt.Tag(
                        nbt.TAG_COMPOUND,
                        {
                            "Version": nbt.Tag(nbt.TAG_STRING, "2.15.2"),
                            "Origin": nbt.Tag(nbt.TAG_INT_ARRAY, [101, 64, 200]),
                        },
                    ),
                },
            ),
            "Width": nbt.Tag(nbt.TAG_SHORT, 3),
            "Height": nbt.Tag(nbt.TAG_SHORT, 2),
            "Length": nbt.Tag(nbt.TAG_SHORT, 2),
            "Offset": nbt.Tag(nbt.TAG_INT_ARRAY, [-1, 0, 0]),
            "Blocks": nbt.Tag(
                nbt.TAG_COMPOUND,
                {
                    "Palette": nbt.Tag(
                        nbt.TAG_COMPOUND,
                        {name: nbt.Tag(nbt.TAG_INT, value) for name, value in palette.items()},
                    ),
                    "Data": nbt.Tag(nbt.TAG_BYTE_ARRAY, encode_varints(block_ids)),
                    "BlockEntities": nbt.Tag(
                        nbt.TAG_LIST, nbt.ListValue(nbt.TAG_COMPOUND, [block_entity])
                    ),
                    "UnknownBytes": nbt.Tag(nbt.TAG_BYTE_ARRAY, b"\x00\xff\x80"),
                },
            ),
        }
        # This is the exact root envelope emitted by WorldEdit's v3 writer:
        # an unnamed root compound with one nested "Schematic" compound.
        root = nbt.Tag(nbt.TAG_COMPOUND, {"Schematic": nbt.Tag(nbt.TAG_COMPOUND, schematic)})
        nbt.write_gzip(path, nbt.Document("", root))

    def _run(self, *arguments: str, stdin: str | None = None) -> tuple[subprocess.CompletedProcess[str], dict]:
        process = subprocess.run(
            [sys.executable, str(TOOLS_ROOT / "ticket_cli.py"), *arguments],
            input=stdin,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertTrue(process.stdout, process.stderr)
        try:
            payload = json.loads(process.stdout)
        except json.JSONDecodeError:
            self.fail(f"stdout was not JSON: {process.stdout!r}; stderr={process.stderr!r}")
        return process, payload

    def test_info_palette_get_slice_and_surface_are_json(self) -> None:
        process, info = self._run("info", str(self.ticket_dir))
        self.assertEqual(0, process.returncode)
        self.assertTrue(info["ok"])
        self.assertEqual(12, info["bounds"]["volume"])
        self.assertTrue(info["hashes"]["sourceMatchesTicket"])
        self.assertTrue(info["hashes"]["candidateMatchesTicket"])

        process, palette = self._run("palette", str(self.ticket_dir))
        self.assertEqual(0, process.returncode)
        self.assertEqual(3, len(palette["palette"]))
        self.assertEqual(6, next(item["count"] for item in palette["palette"] if item["block"] == "minecraft:air"))

        process, block = self._run("get-block", str(self.ticket_dir), "101", "64", "200")
        self.assertEqual(0, process.returncode)
        self.assertEqual("minecraft:stone", block["block"])
        self.assertFalse(block["hasBlockEntity"])

        process, slice_payload = self._run("slice", str(self.ticket_dir), "--y", "64")
        self.assertEqual(0, process.returncode)
        self.assertEqual(2, len(slice_payload["rows"]))

        process, windowed_slice = self._run(
            "slice",
            str(self.ticket_dir),
            "--y",
            "64",
            "--x-min",
            "101",
            "--x-max",
            "102",
            "--z-min",
            "201",
            "--z-max",
            "201",
        )
        self.assertEqual(0, process.returncode)
        self.assertEqual(
            {"xMin": 101, "xMax": 102, "zMin": 201, "zMax": 201},
            windowed_slice["window"],
        )
        self.assertEqual(1, len(windowed_slice["rows"]))

        process, surface = self._run("surface", str(self.ticket_dir))
        self.assertEqual(0, process.returncode)
        self.assertEqual(2, len(surface["rows"]))

    def test_inspection_window_enforces_output_cell_limit(self) -> None:
        ticket = schematic_module.load_ticket(self.ticket_dir)
        with patch.object(cli_module, "_MAX_INSPECTION_CELLS", 3):
            with self.assertRaisesRegex(schematic_module.TicketError, "at most 3 cells"):
                cli_module._inspection_window(ticket, None, None, None, None)

    def test_apply_ops_maps_world_coordinates_and_preserves_unknown_nbt(self) -> None:
        source_before = self._sha256(self.source_path)
        operations_path = self.root / "operations.json"
        operations_path.write_text(
            json.dumps(
                {
                    "operations": [
                        {
                            "op": "fill",
                            "from": {"x": 100, "y": 65, "z": 200},
                            "to": {"x": 102, "y": 65, "z": 200},
                            "block": "minecraft:glass",
                            "expect": "minecraft:air",
                        },
                        {
                            "op": "set",
                            "x": 101,
                            "y": 65,
                            "z": 201,
                            "block": "minecraft:oak_planks",
                            "expect": "minecraft:air",
                        },
                        {
                            "op": "replace",
                            "from": {"x": 100, "y": 64, "z": 201},
                            "to": {"x": 102, "y": 64, "z": 201},
                            "match": "minecraft:stone",
                            "block": "minecraft:cobblestone",
                        },
                    ]
                }
            ),
            encoding="utf-8",
        )
        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", str(operations_path)
        )
        self.assertEqual(0, process.returncode, payload)
        self.assertTrue(payload["written"])
        self.assertEqual(7, payload["changedBlockCount"])
        self.assertEqual(source_before, self._sha256(self.source_path))

        process, diff = self._run("diff", str(self.ticket_dir))
        self.assertEqual(0, process.returncode)
        self.assertEqual(7, diff["changedBlockCount"])
        by_position = {(item["x"], item["y"], item["z"]): item for item in diff["changes"]}
        self.assertEqual("minecraft:oak_planks", by_position[(101, 65, 201)]["candidate"])
        self.assertEqual("minecraft:cobblestone", by_position[(100, 64, 201)]["candidate"])

        bounds = Bounds(Point(100, 64, 200), Point(102, 65, 201))
        candidate = read_schematic(self.candidate_path, bounds)
        metadata = candidate.schematic_compound["Metadata"].value
        self.assertEqual("preserve-this", metadata["AstralTest"].value)
        self.assertEqual("日本\u0000🏰", metadata["ModifiedUtf8"].value)
        self.assertEqual(9_223_372_036_854_775_000, metadata["UnknownLong"].value)
        self.assertEqual(b"\x00\xff\x80", candidate.blocks_compound["UnknownBytes"].value)
        self.assertIn("minecraft:air", candidate.palette)  # Unused palette entries are retained.

    def test_expect_failure_is_all_or_nothing(self) -> None:
        before = self._sha256(self.candidate_path)
        operations = json.dumps(
            [
                {
                    "op": "set",
                    "x": 102,
                    "y": 65,
                    "z": 201,
                    "block": "minecraft:glass",
                    "expect": "minecraft:air",
                },
                {
                    "op": "set",
                    "x": 101,
                    "y": 64,
                    "z": 200,
                    "block": "minecraft:dirt",
                    "expect": "minecraft:air",
                },
            ]
        )
        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=operations
        )
        self.assertNotEqual(0, process.returncode)
        self.assertFalse(payload["ok"])
        self.assertIn("expect failed", payload["error"])
        self.assertEqual(before, self._sha256(self.candidate_path))

    def test_block_entity_edit_is_rejected_without_write(self) -> None:
        before = self._sha256(self.candidate_path)
        operation = json.dumps(
            {"op": "set", "x": 100, "y": 64, "z": 200, "block": "minecraft:air"}
        )
        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=operation
        )
        self.assertNotEqual(0, process.returncode)
        self.assertIn("block entity", payload["error"])
        self.assertEqual(before, self._sha256(self.candidate_path))

    def test_unknown_operation_property_is_rejected_without_write(self) -> None:
        before = self._sha256(self.candidate_path)
        operation = json.dumps(
            {
                "op": "set",
                "x": 101,
                "y": 65,
                "z": 200,
                "block": "minecraft:glass",
                "expects": "minecraft:air",
            }
        )
        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=operation
        )
        self.assertNotEqual(0, process.returncode)
        self.assertIn("unknown properties", payload["error"])
        self.assertEqual(before, self._sha256(self.candidate_path))

    def test_apply_ops_refuses_concurrent_candidate_lock(self) -> None:
        operation = json.dumps(
            {"op": "set", "x": 101, "y": 65, "z": 200, "block": "minecraft:glass"}
        )
        with candidate_lock(self.ticket_dir):
            process, payload = self._run(
                "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=operation
            )
        self.assertNotEqual(0, process.returncode)
        self.assertIn("another process", payload["error"])

    def test_apply_ops_limits_total_expanded_block_visits(self) -> None:
        ticket = schematic_module.load_ticket(self.ticket_dir)
        operation = {
            "op": "fill",
            "from": {"x": 100, "y": 65, "z": 200},
            "to": {"x": 102, "y": 65, "z": 201},
            "block": "minecraft:air",
        }
        with patch.object(schematic_module, "_MIN_OPERATION_VISIT_BUDGET", 12):
            with self.assertRaisesRegex(
                schematic_module.TicketError, "total expansion budget"
            ):
                schematic_module.apply_operations(ticket, [operation, operation, operation])

    def test_ndjson_line_and_error_envelope(self) -> None:
        ndjson = "\n".join(
            [
                json.dumps(
                    {
                        "op": "line",
                        "from": {"x": 100, "y": 65, "z": 200},
                        "to": {"x": 102, "y": 65, "z": 201},
                        "block": "minecraft:gold_block",
                        "expect": "minecraft:air",
                    }
                ),
                json.dumps(
                    {
                        "op": "set",
                        "x": 100,
                        "y": 65,
                        "z": 201,
                        "block": "minecraft:torch[lit=true]",
                    }
                ),
            ]
        )
        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=ndjson
        )
        self.assertEqual(0, process.returncode, payload)
        self.assertTrue(payload["written"])

        process, payload = self._run("get-block", str(self.ticket_dir), "999", "64", "200")
        self.assertNotEqual(0, process.returncode)
        self.assertFalse(payload["ok"])
        self.assertEqual("TicketError", payload["errorType"])

    def test_source_hash_mismatch_blocks_all_commands(self) -> None:
        with self.source_path.open("ab") as stream:
            stream.write(b"tamper")
        process, payload = self._run("info", str(self.ticket_dir))
        self.assertNotEqual(0, process.returncode)
        self.assertFalse(payload["ok"])
        self.assertIn("source.schem SHA-256", payload["error"])


if __name__ == "__main__":
    unittest.main()
