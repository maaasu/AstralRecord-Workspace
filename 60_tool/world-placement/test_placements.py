import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import yaml

import placements


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.server = self.root / "Dev"
        self.plugin = self.server / "plugins" / "AstralRecord"
        self.plugin.mkdir(parents=True)
        self.filebase = self.root / "filebase"
        worlds = self.filebase / "60.features.world"
        worlds.mkdir(parents=True)
        (worlds / "v1.test.yml").write_text("id: test\nbaseWorldPath: worlds/new\n", encoding="utf-8")
        self.spawner = {"id": "test_spawner", "world": "worlds/old", "x": -1, "y": 73, "z": 4}
        self.npc = {"id": "merchant", "world": "worlds/old", "x": -1.125, "y": 73, "z": 4.5,
                    "yaw": 123.456, "pitch": -3.5}
        self.waystone = {"id": "ws-existing-unlock-id", "name": "帰還地点", "worldName": "worlds/old",
                         "x": 1, "y": 73, "z": 4, "createdAt": "2026-08-01T16:33:08.928898200Z",
                         "createdBy": "00000000-0000-0000-0000-000000000001", "lockEnabled": True,
                         "unlockGold": 100, "icon": "ENDER_PEARL"}
        self.write_sources()

    def write_sources(self):
        for filename, key, row in (("mob_spawners.yml", "spawners", self.spawner),
                                   ("npc_locations.yml", "npcs", self.npc),
                                   ("waystones.yml", "waystones", self.waystone)):
            (self.plugin / filename).write_text(yaml.safe_dump({key: [row]}, allow_unicode=True), encoding="utf-8")

    def export(self):
        return placements.export_legacy(self.server, self.filebase, {"worlds/old": "test"})

    def test_unloaded_world_and_waystone_unlock_identity_survive_export(self):
        result = self.export()
        self.assertEqual({"spawner": 1, "npc": 1, "waystone": 1}, placements.counts(result["placements"]))
        waypoint = next(p for p in result["placements"] if p["kind"] == "waystone")
        self.assertEqual(self.waystone["id"], waypoint["waystone"]["id"])
        self.assertEqual(self.waystone["createdAt"], waypoint["waystone"]["createdAt"])
        self.assertEqual("worlds/old", waypoint["worldName"])
        self.assertEqual("test", waypoint["worldId"])
        npc = next(p for p in result["placements"] if p["kind"] == "npc")
        self.assertEqual((-1.125, 4.5), (npc["x"], npc["z"]))

    def test_import_identity_is_stable_across_exports(self):
        self.assertEqual(self.export(), self.export())

    def test_unknown_world_is_not_silently_dropped(self):
        with self.assertRaisesRegex(ValueError, "World mapping required"):
            placements.export_legacy(self.server, self.filebase, {})

    def test_duplicate_yaml_keys_rejected(self):
        (self.plugin / "mob_spawners.yml").write_text("spawners: []\nspawners: []\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Duplicate YAML key"):
            self.export()

    def test_missing_file_is_not_interpreted_as_empty(self):
        (self.plugin / "npc_locations.yml").unlink()
        with self.assertRaises(FileNotFoundError):
            self.export()

    def test_duplicate_placements_rejected(self):
        (self.plugin / "mob_spawners.yml").write_text(yaml.safe_dump({"spawners": [self.spawner, self.spawner]}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Duplicate placement"):
            self.export()

    def test_nonfinite_coordinate_rejected(self):
        self.spawner["x"] = float("nan")
        self.write_sources()
        with self.assertRaisesRegex(ValueError, "finite"):
            self.export()

    def test_existing_different_dev_data_requires_explicit_revision(self):
        with patch.object(placements, "request_api", return_value={"revision": "4", "placements": [{"placementId": "existing"}]}) as api:
            with self.assertRaisesRegex(ValueError, "already has placements"):
                placements.import_dev(self.export(), "http://127.0.0.1", {})
            self.assertEqual(1, api.call_count)

    def test_idempotent_import_does_not_write(self):
        exported = self.export()
        with patch.object(placements, "request_api", return_value={"revision": "4", "placements": exported["placements"]}) as api:
            self.assertFalse(placements.import_dev(exported, "http://127.0.0.1", {})["changed"])
            self.assertEqual(1, api.call_count)

    def test_initial_import_uses_current_revision_and_only_dev(self):
        exported = self.export()
        with patch.object(placements, "request_api", side_effect=[{"revision": "0", "placements": []},
                          {"revision": "1", "placements": exported["placements"]}]) as api:
            result = placements.import_dev(exported, "http://127.0.0.1", {})
            self.assertTrue(result["changed"])
            self.assertEqual("0", api.call_args.args[3]["expectedRevision"])

    def test_remote_plain_http_rejected_before_credentials(self):
        with self.assertRaisesRegex(ValueError, "loopback"):
            placements.request_api("http://example.test", {})


if __name__ == "__main__":
    unittest.main()
