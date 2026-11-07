import json
import struct
import tempfile
import unittest
import zlib
from argparse import Namespace
from pathlib import Path

from refresh_world_map import publish, sha256
from test_terrain_detail import chunk_nbt


class RefreshWorldMapTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.filebase = self.root / "filebase"
        (self.filebase / "60.features.world").mkdir(parents=True)

    def fixture(self, world_id):
        (self.filebase / "60.features.world" / f"{world_id}.yml").write_text(
            f"id: {world_id}\ndisplayName: Test World\nspawnLocation: {{x: 3, y: 72, z: 7}}\n", encoding="utf-8"
        )
        snapshot = self.root / f"snapshot-{world_id}"
        copied_world = snapshot / world_id
        region = copied_world / "region"
        region.mkdir(parents=True)
        payload = zlib.compress(chunk_nbt())
        chunk = struct.pack(">i", len(payload) + 1) + b"\x02" + payload
        header = b"\x00\x00\x02\x01" + bytes(8188)
        (region / "r.0.0.mca").write_bytes(header + chunk.ljust(4096, b"\x00"))
        analysis = snapshot / "analysis"
        analysis.mkdir()
        (analysis / "snapshot-receipt.json").write_text(json.dumps({
            "sourceServer": str(self.root / "dev"),
            "targetWorld": str(copied_world),
        }), encoding="utf-8")
        terrain = analysis / "continent-terrain.json"
        terrain.write_text(json.dumps({
            "schemaVersion": 2, "world": world_id, "capturedAt": "2026-11-07T00:00:00Z",
            "minX": 0, "minZ": 0, "width": 32, "height": 16, "cellSize": 16,
            "cells": [{"x": 0, "z": 0, "status": "ok", "safeCount": 0},
                      {"x": 16, "z": 0, "status": "absent"}],
        }), encoding="utf-8")
        cache = self.root / "cache"
        args = Namespace(world_id=world_id, filebase=self.filebase, snapshot_root=snapshot,
                         cache_root=cache, policy=None, terrain_only=False, java="java", scan_timeout_seconds=3600)
        return args, terrain, cache / "worlds" / world_id

    def test_publishes_complete_world_specific_generations_and_reuses_identical_export(self):
        for world_id in ("verdia", "skyhaven_isle"):
            args, terrain, world_cache = self.fixture(world_id)
            publish(args)
            revision = (world_cache / "current.txt").read_text().strip()
            self.assertRegex(revision, r"^[a-f0-9]{64}$")
            output = world_cache / revision
            self.assertEqual({file.name for file in output.iterdir()}, {
                "manifest.json", "cells.json", "regions.json", "terrain-detail.json", "terrain-detail.png",
            })
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["spawn"], {"x": 3, "y": 72, "z": 7})
            self.assertEqual(manifest["mode"], "terrain-only")
            self.assertEqual(manifest["radiusOptions"], [])
            self.assertEqual(manifest["files"]["proposals"], {})
            for name in ("manifest", "cells", "regions"):
                document = json.loads((output / f"{name}.json").read_text(encoding="utf-8"))
                self.assertEqual(document["exportRevision"], revision)
                self.assertEqual(document["worldId"], world_id)
                self.assertEqual(document["terrainFingerprint"], sha256(terrain))
            publish(args)
            self.assertEqual({entry.name for entry in world_cache.iterdir()}, {"current.txt", revision})

    def test_failed_refresh_keeps_previous_generation(self):
        args, terrain, world_cache = self.fixture("eriva_supercontinent")
        args.terrain_only = True
        publish(args)
        pointer = world_cache / "current.txt"
        revision = pointer.read_text().strip()
        terrain.write_text('{"schemaVersion": 2, "world": "wrong_world"}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "selected world"):
            publish(args)
        self.assertEqual(pointer.read_text().strip(), revision)
        self.assertEqual({entry.name for entry in world_cache.iterdir()}, {"current.txt", revision})

    def test_legacy_receipt_rejects_publishing_inside_source_server(self):
        args, terrain, _ = self.fixture("verdia")
        source_server = self.root / "dev"
        (terrain.parent / "snapshot-receipt.json").write_text(json.dumps({
            "sourceWorld": str(source_server / "plugins" / "AstralRecord" / "worlds" / "verdia"),
            "targetWorld": str(args.snapshot_root / "verdia"),
        }), encoding="utf-8")
        args.cache_root = source_server / "map-cache"
        with self.assertRaisesRegex(ValueError, "Cache must be separate"):
            publish(args)
        self.assertFalse(source_server.exists())


if __name__ == "__main__":
    unittest.main()
