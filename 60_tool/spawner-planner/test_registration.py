import copy
from pathlib import Path
import unittest

from prepare_registration import validate_plan


class RegistrationTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {"schemaVersion": 2, "worldId": "example", "terrainFingerprint": "terrain",
                         "exportRevision": "revision", "cellCount": 11,
                         "spawn": {"x": 0, "z": 0},
                         "bounds": {"minX": -512, "minZ": -512, "width": 6400, "height": 1024, "cellSize": 16},
                         "progressionPolicy": {"mode": "road", "maxLevel": 45,
                             "levels": list(range(15, 46, 3)), "levelStepMeters": 300,
                             "road": {"nodes": [], "maxDistanceMeters": 3000,
                                      "candidateCenterMeters": 160, "corridorMeters": 224}}}
        self.plan = {k: self.manifest[k] for k in ("schemaVersion", "worldId", "terrainFingerprint", "exportRevision")}
        self.plan.update(previewOnly=True, radiusMeters=64, proposals=[], reasonCodes=["covered"] * 11)
        self.spawners = {}
        self.mobs = {"animal": ({"id": "animal", "category": "ENEMY", "level": 15,
                                "levels": [{"level": level} for level in range(15, 46, 3)]}, Path("animal.yml"))}
        self.cells = []
        for index, level in enumerate(range(15, 46, 3)):
            x, z, distance = 160 + index * 160, 160, index * 300
            entries = [{"mobId": "mob:animal", "level": level, "weight": 100}]
            spawner_id = "spawner" if level == 21 else f"spawner-{level}"
            proposal = {"proposalId": f"level-{level}", "spawnerId": spawner_id, "definitionStatus": "existing",
                        "masterUpdateRequired": False, "x": x + 8, "y": 70, "z": z + 8, "level": level,
                        "roadDistanceMeters": distance, "radiusMeters": 64, "spawnMobs": entries,
                        "coveredCellIndices": [index], "world": "plugins/AstralRecord/worlds/overworld/example"}
            self.plan["proposals"].append(proposal)
            self.cells.append({"x": x, "z": z, "status": "ok", "reasonCode": "eligible",
                               "candidateX": x + 8, "candidateY": 70, "candidateZ": z + 8,
                               "roadDistanceMeters": distance, "targetLevel": level})
            self.manifest["progressionPolicy"]["road"]["nodes"].append(
                {"x": x + 8, "z": z + 8, "distanceMeters": distance})
            self.spawners[spawner_id] = ({"id": spawner_id, "radiusMeters": 64, "spawnMobs": entries,
                            "spawnIntervalTicks": 100, "spawnChancePercent": 100,
                            "spawnLimit": {"maxAlivePerSpawner": 6, "maxNearbyMobs": 12, "spawnPerPlayer": 1}}, Path("spawner.yml"))
            if level == 21:
                self.proposal = proposal

    def validate(self, world="plugins/AstralRecord/worlds/overworld/example"):
        return validate_plan(self.manifest, self.plan, self.cells, self.spawners, self.mobs, world)

    def test_explicit_world_and_registered_masters_only(self):
        placements, files = self.validate()
        self.assertEqual(len(placements), 11)
        self.assertEqual(placements[2], {"id": "spawner", "world": "plugins/AstralRecord/worlds/overworld/example", "x": 488, "y": 70, "z": 168})
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
        with self.assertRaisesRegex(ValueError, "progression catalog"):
            self.validate()
        self.manifest["progressionPolicy"]["levels"] = [21]
        with self.assertRaisesRegex(ValueError, "progression catalog"):
            self.validate()
        self.manifest["progressionPolicy"]["levels"] = list(range(15, 46, 3))
        with self.assertRaisesRegex(ValueError, "source world"):
            self.validate("another_world")
        placements, _ = validate_plan(self.manifest, self.plan, self.cells, self.spawners, self.mobs,
                                      "old_world", self.proposal["world"])
        self.assertEqual(placements[0]["world"], "old_world")

    def test_unreached_levels_are_not_required_and_wrong_distance_level_is_rejected(self):
        self.plan["proposals"] = [p for p in self.plan["proposals"] if p["level"] <= 27]
        self.manifest["progressionPolicy"]["road"]["maxDistanceMeters"] = 1488
        self.assertEqual(5, len(self.validate()[0]))
        self.proposal["roadDistanceMeters"] = 599
        with self.assertRaisesRegex(ValueError, "fixed road-distance"):
            self.validate()
        self.proposal["roadDistanceMeters"] = 1500
        with self.assertRaisesRegex(ValueError, "measured road distance"):
            self.validate()

    def test_fabricated_distance_and_moved_candidate_are_rejected(self):
        # Changing distance and level together must not override the measured location.
        self.proposal.update(roadDistanceMeters=900, level=24)
        with self.assertRaisesRegex(ValueError, "measured candidate cell"):
            self.validate()
        self.cells[2].update(roadDistanceMeters=900, targetLevel=24)
        with self.assertRaisesRegex(ValueError, "measured road nodes"):
            self.validate()
        self.proposal.update(roadDistanceMeters=600, level=21, x=489)
        self.cells[2].update(roadDistanceMeters=600, targetLevel=21)
        with self.assertRaisesRegex(ValueError, "measured candidate cell"):
            self.validate()

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
        self.proposal["x"], self.proposal["z"] = 488, 168
        self.plan["proposals"].append(copy.deepcopy(self.proposal))
        with self.assertRaises(ValueError):
            self.validate()

    def test_stale_plan_and_incomplete_spawn_limits_are_rejected(self):
        self.plan["exportRevision"] = "old"
        with self.assertRaises(ValueError):
            self.validate()
        self.plan["exportRevision"] = "revision"
        del self.spawners["spawner"][0]["spawnLimit"]["maxNearbyMobs"]
        with self.assertRaisesRegex(ValueError, "Spawn limits"):
            self.validate()

    def test_extended_plan_recalculates_terrain_distance_and_requires_lv45(self):
        road = self.manifest["progressionPolicy"]["road"]
        road["terrainExtension"] = {"minSafeColumns": 64, "maxLocalSlope": 8,
            "maxStepHeight": 2, "maxDistanceMeters": 3808,
            "targetCells": [[480, 160], [496, 160]],
            "searchBounds": {"minX": -512, "minZ": -512, "maxX": 5888, "maxZ": 512}}
        for cell, node in zip(self.cells, road["nodes"]):
            cell.update(waterCount=0, safeCount=256, minY=70, maxY=70)
            node["kind"] = "road"
        road["nodes"][2]["kind"] = "terrain"
        # The proposal's cell is reached by one measured terrain step from a road.
        self.cells.append({"x": 464, "z": 160, "status": "ok", "candidateY": 70,
                           "safeCount": 256, "waterCount": 0, "minY": 70, "maxY": 70})
        self.cells.append({"x": 496, "z": 160, "status": "ok", "candidateY": 70,
                           "safeCount": 256, "waterCount": 0, "minY": 70, "maxY": 70})
        road["nodes"].append({"x": 472, "z": 168, "distanceMeters": 584, "kind": "road"})
        self.manifest["cellCount"] += 2
        self.plan["reasonCodes"].extend(["eligible", "eligible"])
        self.assertEqual(11, len(self.validate()[0]))
        self.proposal["roadDistanceMeters"] = self.cells[2]["roadDistanceMeters"] = 616
        with self.assertRaisesRegex(ValueError, "recalculated terrain distance"):
            self.validate()
        self.proposal["roadDistanceMeters"] = self.cells[2]["roadDistanceMeters"] = 600
        self.plan["proposals"] = [p for p in self.plan["proposals"] if p["level"] != 45]
        with self.assertRaisesRegex(ValueError, "actual Lv45"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
