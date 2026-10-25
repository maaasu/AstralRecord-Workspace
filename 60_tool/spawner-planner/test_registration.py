import copy
from pathlib import Path
import unittest

from prepare_registration import validate_plan


class RegistrationTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {"schemaVersion": 2, "worldId": "example", "terrainFingerprint": "terrain",
                         "exportRevision": "revision", "cellCount": 1,
                         "spawn": {"x": 0, "z": 0},
                         "bounds": {"minX": -512, "minZ": -512, "width": 1024, "height": 1024},
                         "progressionPolicy": {"mode": "road", "maxLevel": 45, "levels": [21],
                             "road": {"nodes": [{"x": 160, "z": 160}], "candidateCenterMeters": 160, "corridorMeters": 224}}}
        self.entries = [{"mobId": "mob:animal", "level": 21, "weight": 100}]
        self.proposal = {"proposalId": "one", "spawnerId": "spawner", "definitionStatus": "existing",
                         "masterUpdateRequired": False, "x": 160, "y": 70, "z": 160, "level": 21,
                         "radiusMeters": 64, "spawnMobs": self.entries, "coveredCellIndices": [0],
                         "world": "plugins/AstralRecord/worlds/overworld/example"}
        self.plan = {k: self.manifest[k] for k in ("schemaVersion", "worldId", "terrainFingerprint", "exportRevision")}
        self.plan.update(previewOnly=True, radiusMeters=64, proposals=[self.proposal], reasonCodes=["covered"])
        self.spawners = {"spawner": ({"id": "spawner", "radiusMeters": 64, "spawnMobs": self.entries,
                            "spawnIntervalTicks": 100, "spawnChancePercent": 100,
                            "spawnLimit": {"maxAlivePerSpawner": 6, "maxNearbyMobs": 12, "spawnPerPlayer": 1}}, Path("spawner.yml"))}
        self.mobs = {"animal": ({"id": "animal", "category": "ENEMY", "level": 15,
                                "levels": [{"level": 21}]}, Path("animal.yml"))}
        self.cells = [{"reasonCode": "eligible"}]
        self.manifest["progressionPolicy"]["levels"] = list(range(15, 46, 3))
        for level in self.manifest["progressionPolicy"]["levels"]:
            if level == 21:
                continue
            entries = [{"mobId": "mob:animal", "level": level, "weight": 100}]
            proposal = copy.deepcopy(self.proposal)
            proposal.update(proposalId=f"level-{level}", spawnerId=f"spawner-{level}",
                            x=160 + level, level=level, spawnMobs=entries)
            self.plan["proposals"].append(proposal)
            master = copy.deepcopy(self.spawners["spawner"][0])
            master.update(id=f"spawner-{level}", spawnMobs=entries)
            self.spawners[f"spawner-{level}"] = (master, Path("spawner.yml"))
            self.mobs["animal"][0]["levels"].append({"level": level})

    def validate(self, world="plugins/AstralRecord/worlds/overworld/example"):
        return validate_plan(self.manifest, self.plan, self.cells, self.spawners, self.mobs, world)

    def test_explicit_world_and_registered_masters_only(self):
        placements, files = self.validate()
        self.assertEqual(len(placements), 11)
        self.assertEqual(placements[0], {"id": "spawner", "world": "plugins/AstralRecord/worlds/overworld/example", "x": 160, "y": 70, "z": 160})
        self.assertEqual(files, {Path("spawner.yml"), Path("animal.yml")})
        for world in (".", "../other", "C:/server/world", "/world", "a//world", ""):
            with self.subTest(world=world), self.assertRaises(ValueError):
                self.validate(world)

    def test_missing_profile_and_draft_are_rejected(self):
        self.mobs["animal"][0]["levels"] = [{"level": 15}]
        with self.assertRaises(ValueError):
            self.validate()
        self.mobs["animal"][0]["levels"] = [{"level": 21}]
        self.proposal["definitionStatus"] = "draft"
        with self.assertRaises(ValueError):
            self.validate()

    def test_incomplete_levels_and_unacknowledged_world_mapping_are_rejected(self):
        self.manifest["progressionPolicy"]["levels"] = [15, 21, 45]
        with self.assertRaisesRegex(ValueError, "every declared"):
            self.validate()
        self.manifest["progressionPolicy"]["levels"] = [21]
        with self.assertRaisesRegex(ValueError, "every declared"):
            self.validate()
        self.manifest["progressionPolicy"]["levels"] = list(range(15, 46, 3))
        with self.assertRaisesRegex(ValueError, "source world"):
            self.validate("another_world")
        placements, _ = validate_plan(self.manifest, self.plan, self.cells, self.spawners, self.mobs,
                                      "old_world", self.proposal["world"])
        self.assertEqual(placements[0]["world"], "old_world")

    def test_future_geometry_protection_and_duplicates_are_rejected(self):
        self.cells[0]["reasonCode"] = "reserved_future"
        with self.assertRaises(ValueError):
            self.validate()
        self.cells[0]["reasonCode"] = "eligible"
        self.proposal["x"] = self.proposal["z"] = 0
        with self.assertRaises(ValueError):
            self.validate()
        self.proposal["x"] = self.proposal["z"] = 400
        with self.assertRaises(ValueError):
            self.validate()
        self.proposal["x"] = self.proposal["z"] = 160
        self.plan["proposals"].append(copy.deepcopy(self.proposal))
        with self.assertRaises(ValueError):
            self.validate()

    def test_stale_plan_and_incomplete_spawn_limits_are_rejected(self):
        self.plan["exportRevision"] = "old"
        with self.assertRaises(ValueError):
            self.validate()
        self.plan["exportRevision"] = "revision"
        del self.spawners["spawner"][0]["spawnLimit"]["maxNearbyMobs"]
        with self.assertRaises(ValueError):
            self.validate()


if __name__ == "__main__":
    unittest.main()
