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
WRAPPER = (TOOLS_ROOT.parents[2] / ".codex" / "skills" / "astralarchitect-builder"
           / "scripts" / "invoke_ticket_cli.py")
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

    def _run(self, *arguments: str, stdin: str | None = None,
             entrypoint: Path = TOOLS_ROOT / "ticket_cli.py") -> tuple[subprocess.CompletedProcess[str], dict]:
        process = subprocess.run(
            [sys.executable, str(entrypoint), *arguments],
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

    def _preview_args(self, output: Path) -> tuple[str, ...]:
        return (
            "preview", str(self.ticket_dir),
            "--eye", "105", "67", "196",
            "--target", "101", "65", "201",
            "--width", "96", "--height", "72", "--output", str(output),
        )

    def test_preview_picks_follow_visible_shapes_in_order_without_changing_png(self) -> None:
        slab = "minecraft:oak_slab[type=bottom]"
        operations = json.dumps([
            {"op": "set", "x": x, "y": 65, "z": z, "block": block}
            for x, z, block in ((101, 200, slab), (101, 201, "minecraft:gold_block"),
                                (102, 200, "minecraft:glass"), (102, 201, "minecraft:stone"))
        ])
        before = [self._sha256(path) for path in (self.source_path, self.candidate_path)]
        camera = ("--eye", "101.5", "65.5", "198", "--target", "101.5", "65.5", "201",
                  "--width", "7", "--height", "9", "--ops", "-")
        slab_pick = {"pixel": {"x": 3, "y": 5}, "position": {"x": 101, "y": 65, "z": 200}, "block": slab}
        expected = [
            slab_pick,
            {"pixel": {"x": 0, "y": 0}, "position": None, "block": None},
            {"pixel": {"x": 3, "y": 3}, "position": {"x": 101, "y": 65, "z": 201},
             "block": "minecraft:gold_block"},
            {"pixel": {"x": 0, "y": 4}, "position": {"x": 102, "y": 65, "z": 200},
             "block": "minecraft:glass"},
            slab_pick,
        ]
        for style in ("material", "clay"):
            with self.subTest(style=style):
                plain = self.root / f"{style}.png"
                picked = self.root / f"{style}-picks.png"
                args = ("preview", str(self.ticket_dir), *camera, "--style", style)
                process, result = self._run(*args, "--output", str(plain), stdin=operations)
                self.assertEqual(0, process.returncode, result)
                self.assertNotIn("picks", result)
                process, result = self._run(*args, "--output", str(picked), "--pick", "3", "5",
                                            "--pick", "0", "0", "--pick", "3", "3", "--pick", "0", "4",
                                            "--pick", "3", "5", stdin=operations)
                self.assertEqual(0, process.returncode, result)
                self.assertEqual(expected, result["picks"])
                self.assertEqual(plain.read_bytes(), picked.read_bytes())
        self.assertEqual(before, [self._sha256(path) for path in (self.source_path, self.candidate_path)])

    def test_preview_rejects_outside_pick_without_writing_png(self) -> None:
        for x, y in ((-1, 0), (0, -1), (96, 0), (0, 72)):
            with self.subTest(x=x, y=y):
                output = self.root / "invalid.png"
                process, result = self._run(*self._preview_args(output), "--pick", "0", "0", "--pick", str(x), str(y))
                self.assertNotEqual(0, process.returncode)
                self.assertIn(f"--pick pixel ({x}, {y}) is outside", result["error"])
                self.assertFalse(output.exists())

    def test_wrapper_preview_picks_the_clipped_scene(self) -> None:
        candidate = schematic_module.load_ticket(self.ticket_dir).candidate
        states = candidate.states()
        states[candidate.index(1, 1, 0)] = "minecraft:gold_block"
        candidate.set_states(states)
        nbt.write_gzip(self.candidate_path, candidate.document)
        before = [self._sha256(path) for path in (self.source_path, self.candidate_path)]
        ops = json.dumps({"op": "set", "x": 101, "y": 65, "z": 200, "block": "minecraft:glass"})
        camera = ("--eye", "101.5", "68", "200.5", "--target", "101.5", "64", "200.5",
                  "--width", "1", "--height", "1", "--pick", "0", "0", "--pick", "0", "0")
        for label, options, y, block in (
            ("full", ("--ops", "-"), 65, "minecraft:glass"),
            ("cut", ("--ops", "-", "--clip-y", "64"), 64, "minecraft:stone"),
            ("source", ("--source",), 64, "minecraft:stone"),
            ("candidate", (), 65, "minecraft:gold_block"),
        ):
            with self.subTest(label=label):
                process, result = self._run("--ticket", str(self.ticket_dir), "--", "preview", *camera, *options,
                                            "--output", str(self.root / f"{label}.png"), entrypoint=WRAPPER,
                                            stdin=ops if "--ops" in options else None)
                self.assertEqual(0, process.returncode, result)
                expected = {"pixel": {"x": 0, "y": 0}, "position": {"x": 101, "y": y, "z": 200}, "block": block}
                self.assertEqual([expected, expected], result["picks"])
        self.assertEqual(before, [self._sha256(path) for path in (self.source_path, self.candidate_path)])

    def test_preview_picks_mirrored_blueprint_stage_positions_and_states(self) -> None:
        design = {
            "version": 1, "origin": [102, 65, 200],
            "parts": {"stairs": {
                "rough": [{"op": "set", "at": [1, 0, 1],
                           "block": "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]"}],
                "detail": [{"op": "set", "at": [1, 0, 0],
                            "block": "minecraft:stone_stairs[facing=east,half=bottom,shape=straight]"}],
            }},
            "placements": [{"part": "stairs", "at": [0, 0, 0], "mirror": "x"}],
        }
        path = self.root / "mirrored.json"
        path.write_text(json.dumps(design), encoding="utf-8")
        before = [self._sha256(path) for path in (self.source_path, self.candidate_path)]
        for stage, z, material in (("rough", 201, "oak"), ("detail", 200, "stone")):
            with self.subTest(stage=stage):
                process, result = self._run(
                    "preview", str(self.ticket_dir), "--eye", "101.25", "65.75", "198",
                    "--target", "101.25", "65.75", "201", "--width", "1", "--height", "1", "--pick", "0", "0",
                    "--plan", str(path), "--stage", stage, "--output", str(self.root / f"{stage}.png"),
                )
                self.assertEqual(0, process.returncode, result)
                self.assertEqual(stage, result["stage"])
                self.assertEqual([{
                    "pixel": {"x": 0, "y": 0}, "position": {"x": 101, "y": 65, "z": z},
                    "block": f"minecraft:{material}_stairs[facing=west,half=bottom,shape=straight]",
                }], result["picks"])
        self.assertEqual(before, [self._sha256(path) for path in (self.source_path, self.candidate_path)])

    def test_preview_proposal_matches_applied_scene_without_writing_ticket(self) -> None:
        filenames = ("source.schem", "candidate.schem", "ticket.json")
        before = {name: self._sha256(self.ticket_dir / name) for name in filenames}
        original_image = self.root / "original.png"
        proposed_image = self.root / "proposed.png"
        committed_image = self.root / "committed.png"
        operation = json.dumps({
            "op": "fill", "from": {"x": 100, "y": 65, "z": 200},
            "to": {"x": 102, "y": 65, "z": 201},
            "block": "minecraft:prismarine_bricks", "expect": "minecraft:air",
        })
        process, _ = self._run(*self._preview_args(original_image), "--source")
        self.assertEqual(0, process.returncode)
        process, preview = self._run(*self._preview_args(proposed_image), "--ops", "-", stdin=operation)
        self.assertEqual(0, process.returncode, preview)
        self.assertEqual("proposed", preview["scene"])
        self.assertEqual(6, preview["changedBlockCount"])
        self.assertEqual(before["candidate.schem"], preview["baseCandidateSha256"])
        self.assertEqual(preview["imageSha256"], self._sha256(proposed_image))
        self.assertTrue(proposed_image.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"))
        self.assertNotEqual(original_image.read_bytes(), proposed_image.read_bytes())
        self.assertEqual(before, {name: self._sha256(self.ticket_dir / name) for name in filenames})
        self.assertEqual(set(filenames), {path.name for path in self.ticket_dir.iterdir()})

        process, result = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-",
            "--expect-candidate-sha256", preview["baseCandidateSha256"], stdin=operation
        )
        self.assertEqual(0, process.returncode, result)
        self.assertEqual(preview["changedBlockCount"], result["changedBlockCount"])
        process, result = self._run(*self._preview_args(committed_image))
        self.assertEqual(0, process.returncode, result)
        self.assertEqual(proposed_image.read_bytes(), committed_image.read_bytes())

        process, failure = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-",
            "--expect-candidate-sha256", preview["baseCandidateSha256"], stdin=operation
        )
        self.assertNotEqual(0, process.returncode)
        self.assertIn("differs from the preview base", failure["error"])

    def test_blueprint_revision_and_detail_review_match_final_candidate(self) -> None:
        design = {
            "version": 1, "origin": [101, 65, 200],
            "parts": {"pier": {
                "rough": [{"op": "set", "at": [0, 0, 0], "block": "minecraft:stone"}],
                "detail": [{"op": "set", "at": [0, 0, 1], "block": "minecraft:gold_block"}],
            }},
            "placements": [{"part": "pier", "at": [0, 0, 0]}],
        }
        path = self.root / "design.json"
        path.write_text(json.dumps(design), encoding="utf-8")
        base = self._sha256(self.candidate_path)
        apply = ("apply-ops", str(self.ticket_dir), "--plan", str(path), "--expect-candidate-sha256", base)
        for stage in ("rough", "detail"):
            process, preview = self._run(*self._preview_args(self.root / (stage + ".png")),
                                         "--plan", str(path), "--stage", stage)
            self.assertEqual(0, process.returncode, preview)
            self.assertEqual(stage, preview["stage"])
            self.assertEqual(1 if stage == "rough" else 2, preview["changedBlockCount"])
            self.assertEqual(base, self._sha256(self.candidate_path))
            process, layer = self._run("slice", str(self.ticket_dir), "--y", "65",
                                       "--x-min", "101", "--x-max", "101",
                                       "--z-min", "201", "--z-max", "201",
                                       "--plan", str(path), "--stage", stage)
            self.assertEqual(0, process.returncode, layer)
            self.assertEqual("proposed", layer["schematic"])
            expected = "minecraft:air" if stage == "rough" else "minecraft:gold_block"
            self.assertEqual(expected, layer["rows"][0]["runs"][0]["block"])
            if stage == "rough":
                process, _ = self._run(*apply, "--expect-stage-sha256", preview["stageSha256"])
                self.assertNotEqual(0, process.returncode)
        design["placements"][0]["at"] = [1, 0, 0]
        path.write_text(json.dumps(design), encoding="utf-8")
        process, _ = self._run(*apply, "--expect-stage-sha256", preview["stageSha256"])
        self.assertNotEqual(0, process.returncode)
        revised = self.root / "revised.png"
        process, approved = self._run(*self._preview_args(revised), "--plan", str(path))
        self.assertEqual(0, process.returncode, approved)
        process, applied = self._run(*apply, "--expect-stage-sha256", approved["stageSha256"])
        self.assertEqual(0, process.returncode, applied)
        self.assertEqual(2, applied["changedBlockCount"])
        final = self.root / "final.png"
        process, _ = self._run(*self._preview_args(final))
        self.assertEqual(0, process.returncode)
        self.assertEqual(revised.read_bytes(), final.read_bytes())
        process, failure = self._run(*apply, "--expect-stage-sha256", approved["stageSha256"])
        self.assertIn("differs from the preview base", failure["error"])

    def test_slice_plan_reapplies_part_rough_only_in_detail_stage(self) -> None:
        design = {
            "version": 1, "origin": [101, 65, 200],
            "parts": {
                "pier": {
                    "reapplyRough": True,
                    "rough": [{"op": "set", "at": [0, 0, 0], "block": "minecraft:stone"}],
                    "detail": [{"op": "set", "at": [0, 0, 1], "block": "minecraft:diamond_block"}],
                },
                "overlay": {
                    "rough": [{"op": "set", "at": [0, 0, 0], "block": "minecraft:gold_block"}],
                    "detail": [],
                },
            },
            "placements": [{"part": part, "at": [0, 0, 0]} for part in ("pier", "overlay")],
        }
        path = self.root / "design.json"
        path.write_text(json.dumps(design), encoding="utf-8")
        base = self._sha256(self.candidate_path)
        for stage, blocks in (
            ("rough", ["minecraft:gold_block", "minecraft:air"]),
            ("detail", ["minecraft:stone", "minecraft:diamond_block"]),
        ):
            with self.subTest(stage=stage):
                process, layer = self._run(
                    "slice", str(self.ticket_dir), "--y", "65", "--x-min", "101", "--x-max", "101",
                    "--z-min", "200", "--z-max", "201", "--plan", str(path), "--stage", stage,
                )
                self.assertEqual(0, process.returncode, layer)
                self.assertEqual("proposed", layer["schematic"])
                self.assertEqual(blocks, [row["runs"][0]["block"] for row in layer["rows"]])
        self.assertEqual(base, self._sha256(self.candidate_path))

    def test_preview_rejects_invalid_operation_and_unsafe_output(self) -> None:
        output = self.root / "proposed.png"
        operation = json.dumps({
            "op": "set", "x": 100, "y": 64, "z": 200, "block": "minecraft:air"
        })
        process, result = self._run(*self._preview_args(output), "--ops", "-", stdin=operation)
        self.assertNotEqual(0, process.returncode)
        self.assertIn("block entity", result["error"])
        self.assertFalse(output.exists())
        process, result = self._run(*self._preview_args(self.ticket_dir / "forbidden.png"))
        self.assertNotEqual(0, process.returncode)
        self.assertIn("outside AstralArchitect ticket data", result["error"])
        owner_file = self.root / "existing.txt"
        owner_file.write_bytes(b"existing")
        stream_path = owner_file.with_name(owner_file.name + ":test.png")
        process, result = self._run(*self._preview_args(stream_path))
        self.assertNotEqual(0, process.returncode)
        self.assertIn("standalone PNG", result["error"])
        self.assertEqual(b"existing", owner_file.read_bytes())

        output.write_bytes(b"existing")
        process, result = self._run(*self._preview_args(output))
        self.assertNotEqual(0, process.returncode)
        self.assertEqual(b"existing", output.read_bytes())

    def test_preview_revalidates_parent_after_rendering(self) -> None:
        output = self.root / "preview.png"
        args = cli_module._parser().parse_args(self._preview_args(output))

        # Model an output parent replaced by a link during a long render.
        with patch.object(
            cli_module, "reject_reparse_chain",
            side_effect=(None, schematic_module.TicketError("output parent became a symlink")),
        ), patch.object(cli_module, "render_preview", return_value=b"PNG"):
            with self.assertRaisesRegex(schematic_module.TicketError, "became a symlink"):
                cli_module.execute(args)
        self.assertFalse(output.exists())

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
        self.assertIn("minecraft:air", candidate.palette)  # Two air blocks remain in use.

    def test_replace_removes_retired_block_state_from_palette(self) -> None:
        bounds = Bounds(Point(100, 64, 200), Point(102, 65, 201))
        candidate = read_schematic(self.candidate_path, bounds)
        states = candidate.states()
        states[6] = "minecraft:chain[axis=y,waterlogged=false]"
        states[7] = "minecraft:oak_planks"
        candidate.set_states(states)
        nbt.write_gzip(self.candidate_path, candidate.document)
        source_before = self._sha256(self.source_path)
        operation = json.dumps({
            "op": "replace",
            "match": "minecraft:chain[axis=y,waterlogged=false]",
            "block": "minecraft:iron_chain[axis=y,waterlogged=false]",
        })

        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=operation
        )

        self.assertEqual(0, process.returncode, payload)
        self.assertTrue(payload["written"])
        self.assertEqual(1, payload["changedBlockCount"])
        candidate = read_schematic(self.candidate_path, bounds)
        self.assertNotIn("minecraft:chain[axis=y,waterlogged=false]", candidate.palette)
        self.assertEqual("minecraft:iron_chain[axis=y,waterlogged=false]", candidate.states()[6])
        self.assertEqual("minecraft:oak_planks", candidate.states()[7])
        self.assertEqual(list(range(len(candidate.palette))), sorted(candidate.palette.values()))
        self.assertEqual(source_before, self._sha256(self.source_path))

    def test_noop_edit_removes_unused_palette_and_is_idempotent(self) -> None:
        bounds = Bounds(Point(100, 64, 200), Point(102, 65, 201))
        candidate = read_schematic(self.candidate_path, bounds)
        states_before = candidate.states()
        # Put the unused retired ID in the middle to exercise Java's dense-ID contract.
        fixture_palette = {
            "minecraft:chest[facing=north,type=single,waterlogged=false]": 0,
            "minecraft:stone": 1,
            "minecraft:chain[axis=y,waterlogged=false]": 2,
            "minecraft:air": 3,
        }
        candidate.blocks_compound["Palette"].value = {
            name: nbt.Tag(nbt.TAG_INT, value) for name, value in fixture_palette.items()
        }
        candidate.blocks_compound["Data"].value = encode_varints(
            [fixture_palette[state] for state in states_before]
        )
        candidate.blocks_compound["PaletteMax"] = nbt.Tag(nbt.TAG_INT, 4)
        nbt.write_gzip(self.candidate_path, candidate.document)
        source_before = self._sha256(self.source_path)
        metadata_before = (self.ticket_dir / "ticket.json").read_bytes()
        operation = json.dumps({
            "op": "set", "x": 101, "y": 64, "z": 200,
            "block": "minecraft:stone", "expect": "minecraft:stone",
        })

        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=operation
        )

        self.assertEqual(0, process.returncode, payload)
        self.assertTrue(payload["written"])
        self.assertEqual(0, payload["changedBlockCount"])
        candidate = read_schematic(self.candidate_path, bounds)
        self.assertNotIn("minecraft:chain[axis=y,waterlogged=false]", candidate.palette)
        self.assertEqual(states_before, candidate.states())
        self.assertEqual(list(range(len(candidate.palette))), sorted(candidate.palette.values()))
        self.assertEqual(3, candidate.blocks_compound["PaletteMax"].value)
        self.assertEqual(source_before, self._sha256(self.source_path))
        self.assertEqual(metadata_before, (self.ticket_dir / "ticket.json").read_bytes())
        clean_hash = self._sha256(self.candidate_path)

        process, payload = self._run(
            "apply-ops", str(self.ticket_dir), "--ops", "-", stdin=operation
        )
        self.assertEqual(0, process.returncode, payload)
        self.assertFalse(payload["written"])
        self.assertEqual(clean_hash, self._sha256(self.candidate_path))

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
