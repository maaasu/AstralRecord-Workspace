import gzip
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import migrate


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.server = root / "10_Dev"
        self.client = root / "instance"
        self.backup = root / "backups"
        self.backup.mkdir()
        (self.client / "mods").mkdir(parents=True)
        self.world = self.server / "plugins/AstralRecord/worlds/overworld" / migrate.OLD
        self.world.mkdir(parents=True)
        old_name = f"plugins/AstralRecord/worlds/overworld/{migrate.OLD}".encode()
        data = b"\x0a\x00\x00\x08\x00\x09LevelName" + len(old_name).to_bytes(2, "big") + old_name + b"\x00"
        for name in ("level.dat", "level.dat_old"):
            (self.world / name).write_bytes(gzip.compress(data))
        (self.world / "uid.dat").write_bytes(b"preserved-uid")
        (self.world / "xaeromap.txt").write_text("id:1140358035\n", encoding="utf-8")
        (self.world / "session.lock").write_bytes(b"\0")
        xaero = self.client / "xaero"
        mini = xaero / "minimap" / migrate.SERVER_KEY
        worldmap = xaero / "world-map" / migrate.SERVER_KEY
        self.old_mini = mini / f"dim%minecraft$plugins%astralrecord%worlds%overworld%{migrate.OLD}"
        self.old_map = worldmap / f"minecraft$plugins%astralrecord%worlds%overworld%{migrate.OLD}"
        self.old_mini.mkdir(parents=True)
        (self.old_mini / "mw$default_1.txt").write_text("#\n", encoding="utf-8")
        (self.old_map / "mw$default").mkdir(parents=True)
        (self.old_map / "mw$default" / "0_0.zip").write_bytes(b"map-zip-untouched")
        (mini / "config.txt").write_text(
            f"dimensionType:minecraft$plugins/astralrecord/worlds/overworld/{migrate.OLD}:minecraft$overworld\n",
            encoding="utf-8",
        )

    def run_script(self, apply=False):
        argv = ["migrate.py", "--server-root", str(self.server), "--client-root", str(self.client),
                "--backup-root", str(self.backup)]
        if apply:
            argv.append("--apply")
        with patch("sys.argv", argv), patch.object(migrate, "process_check"), patch.object(migrate, "lock_probe"):
            return migrate.main()

    def test_dry_run_and_apply_preserve_map_identity(self):
        self.assertEqual(self.run_script(), 0)
        self.assertTrue(self.world.exists())
        self.assertEqual(self.run_script(apply=True), 0)
        new_world = self.world.with_name(migrate.NEW)
        self.assertFalse(self.world.exists())
        self.assertEqual((new_world / "uid.dat").read_bytes(), b"preserved-uid")
        self.assertEqual((new_world / "xaeromap.txt").read_text(encoding="utf-8"), "id:1140358035\n")
        for name in ("level.dat", "level.dat_old"):
            unpacked = gzip.decompress((new_world / name).read_bytes())
            self.assertIn(f"overworld/{migrate.NEW}".encode(), unpacked)
            self.assertNotIn(migrate.OLD.encode(), unpacked)
        self.assertEqual((self.old_map.with_name(self.old_map.name.replace(migrate.OLD, migrate.NEW)) /
                          "mw$default/0_0.zip").read_bytes(), b"map-zip-untouched")
        self.assertFalse(self.old_mini.exists())
        self.assertEqual(len(list(self.backup.iterdir())), 1)

    def test_destination_collision_blocks_changes(self):
        self.world.with_name(migrate.NEW).mkdir()
        with self.assertRaisesRegex(RuntimeError, "Destination collision"):
            self.run_script(apply=True)
        self.assertTrue(self.world.exists())
        self.assertFalse(list(self.backup.iterdir()))


if __name__ == "__main__":
    unittest.main()
