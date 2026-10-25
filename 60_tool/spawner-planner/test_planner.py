"""配置計画の安全域・入力完全性・再実行の契約を人工地形で検証する。"""
import json
import math
import tempfile
import unittest
from pathlib import Path

import yaml

from planner import Planner


class PlannerContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.filebase = self.root / "masters"
        self.write_yaml("60.features.world/world.yml", {"id": "test", "baseWorldPath": "worlds/test",
            "spawnLocation": {"x": 32.5, "y": 70, "z": 32.5}, "adventureGuide": {"recommendedLevelMin": 2}})
        self.write_yaml("40.features.mob/enemy/mob.yml", {"id": "mob", "level": 2, "levels": [], "name": "Creature"})
        self.write_yaml("41.features.mob.spawner/spawner.yml", {"id": "test_spawner", "radiusMeters": 32,
            "spawnMobs": [{"mobId": "mob:mob", "level": 2, "weight": 1}], "spawnLimit": {"maxAlivePerSpawner": 4}})
        self.policy = self.root / "policy.json"
        self.policy.write_text(json.dumps({"schemaVersion": 1, "worldId": "test", "regionName": "test",
            "spawnSafeRadius": 16, "maxLocalSlope": 4, "minSafeColumns": 8,
            "habitats": {"grass": ["test_spawner"]}, "notes": []}), encoding="utf-8")
        self.terrain = self.root / "terrain.json"
        cells = [{"x": x, "z": z, "y": 70, "candidateX": x+2, "candidateY": 70, "candidateZ": z+2,
                  "surface": "minecraft:grass_block", "ground": "minecraft:grass_block", "biome": "minecraft:plains",
                  "safeCount": 16, "safeColumnY": [70]*16, "walkableBelowSurface": [], "waterCount": 0, "treeCount": 0, "minY": 70, "maxY": 70, "status": "ok"}
                 for z in range(0, 192, 4) for x in range(0, 192, 4)]
        self.data = {"schemaVersion": 1, "world": "test", "minX": 0, "minZ": 0, "width": 192,
                     "height": 192, "cellSize": 4, "capturedAt": "test", "cells": cells}
        self.save()

    def write_yaml(self, name, value):
        path = self.filebase / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(yaml.safe_dump(value), encoding="utf-8")

    def save(self):
        self.terrain.write_text(json.dumps(self.data), encoding="utf-8")

    def planner(self):
        return Planner(self.terrain, self.filebase, self.policy)

    def test_every_cell_has_one_reason_and_safe_zone_cannot_be_crossed(self):
        planner = self.planner()
        plan = planner.plan(32, 200)
        self.assertGreater(len(plan["proposals"]), 0)
        self.assertEqual(sum(plan["counts"].values()), len(self.data["cells"]))
        self.assertEqual(len(plan["reasonCodes"]), len(self.data["cells"]))
        for p in plan["proposals"]:
            self.assertGreaterEqual(math.hypot(p["x"]+.5-32.5, p["z"]+.5-32.5), 48)
            self.assertGreaterEqual(p["x"]+.5-32, 0)
            self.assertLessEqual(p["x"]+.5+32, 192)
        proposals = {p["proposalId"]: p for p in plan["proposals"]}
        for index, key in plan["coveredBy"].items():
            c, p = self.data["cells"][int(index)], proposals[key]
            for x in (c["x"], c["x"]+4):
                for z in (c["z"], c["z"]+4):
                    self.assertLessEqual(math.hypot(x-p["x"]-.5, z-p["z"]-.5), 32)

    def test_replay_is_deterministic_and_never_changes_inputs(self):
        before = self.terrain.read_bytes()
        planner = self.planner()
        self.assertEqual(planner.plan(48, 10), planner.plan(48, 10))
        self.assertEqual(before, self.terrain.read_bytes())
        self.assertLessEqual(len(planner.plan(32, 1)["proposals"]), 1)

    def test_missing_or_duplicate_cells_fail_closed(self):
        self.data["cells"][-1] = self.data["cells"][0]
        self.save()
        with self.assertRaisesRegex(ValueError, "missing, duplicate"):
            self.planner()

    def test_unknown_and_disconnected_cells_are_never_counted_as_covered(self):
        for c in self.data["cells"]:
            if c["x"] == 80:
                c.update(status="unknown", reason="failed", safeCount=None)
        self.save()
        planner = self.planner()
        plan = planner.plan(32, 200)
        self.assertGreater(plan["counts"]["unknown"], 0)
        self.assertGreater(plan["counts"]["disconnected"], 0)
        for i, c in enumerate(planner.cells):
            if c["x"] >= 80:
                self.assertNotEqual(plan["reasonCodes"][i], "covered")

    def test_mismatched_world_and_nonfinite_candidates_are_rejected(self):
        self.data["world"] = "different"
        self.save()
        with self.assertRaisesRegex(ValueError, "identities"):
            self.planner()
        self.data["world"] = "test"
        self.data["cells"][0]["candidateY"] = float("nan")
        self.save()
        with self.assertRaisesRegex(ValueError, "finite"):
            self.planner()

    def test_narrow_bridge_connects_regions_without_becoming_a_spawn_site(self):
        for c in self.data["cells"]:
            if c["x"] == 80:
                c.update(safeCount=0, safeColumnY=[None]*16, minY=None, maxY=None, candidateX=None, candidateY=None, candidateZ=None)
                if c["z"] == 32:
                    c.update(safeCount=4, safeColumnY=[None]*8+[70]*4+[None]*4, minY=70, maxY=70, candidateX=82, candidateY=70, candidateZ=34,
                             ground="minecraft:stone_brick_slab")
        self.save()
        planner = self.planner()
        self.assertTrue(all(c["connected"] for c in planner.cells if c["x"] > 80))
        bridge = next(c for c in planner.cells if c["x"] == 80 and c["z"] == 32)
        self.assertEqual(bridge["reasonCode"], "unsafe")

    def test_artificial_floor_in_forest_is_not_a_habitat_candidate(self):
        for c in self.data["cells"]:
            c.update(ground="minecraft:stone_bricks", biome="minecraft:forest")
        self.save()
        planner = self.planner()
        self.assertEqual(planner.plan()["targetCells"], 0)

    def test_spawn_under_roof_reaches_ground_without_connecting_rooftop(self):
        for c in self.data["cells"]:
            if 28 <= c["x"] <= 36 and 28 <= c["z"] <= 36:
                c.update(y=75, candidateY=75, safeColumnY=[75]*16, minY=75, maxY=75,
                         ground="minecraft:quartz_slab", walkableBelowSurface=[
                             {"x": c["x"]+dx, "y": 70, "z": c["z"]+dz}
                             for dz in range(4) for dx in range(4)])
        self.save()
        planner = self.planner()
        self.assertEqual(planner.connection_start, {"x": 32, "y": 70, "z": 32})
        self.assertGreater(planner.plan()["targetCells"], 1000)
        self.assertFalse(next(c for c in planner.cells if c["x"] == 32 and c["z"] == 32)["connected"])
        for c in self.data["cells"]:
            c["walkableBelowSurface"] = []
        self.save()
        self.assertEqual(self.planner().plan()["targetCells"], 0)


if __name__ == "__main__":
    unittest.main()
