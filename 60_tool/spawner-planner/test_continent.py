"""Contract and safety tests for the whole-continent preview export."""
import json
import math
import tempfile
import unittest
from pathlib import Path

from continent import ContinentPlanner


HERE = Path(__file__).resolve().parent
FILEBASE = HERE.parents[1] / "40_filebase"
POLICY = HERE / "policies" / "eriva-continent.json"


def terrain(min_x=-1312, min_z=1376, width=256, height=256, biome="minecraft:plains"):
    cells = []
    for z in range(min_z, min_z + height, 16):
        for x in range(min_x, min_x + width, 16):
            cells.append({"x": x, "z": z, "status": "ok", "y": 80,
                          "candidateX": x + 8, "candidateY": 80, "candidateZ": z + 8,
                          "surface": "minecraft:air", "ground": "minecraft:grass_block",
                          "biome": biome, "safeCount": 256, "waterCount": 0,
                          "treeCount": 0, "minY": 80, "maxY": 80})
    return {"schemaVersion": 2, "world": "eriva_supercontinent", "cellSize": 16,
            "capturedAt": "2026-10-25T02:41:37Z",
            "minX": min_x, "minZ": min_z, "width": width, "height": height,
            "connectivity": "not_evaluated", "cells": cells}


class ContinentTests(unittest.TestCase):
    def make_planner(self, data, directory):
        path = directory / "terrain.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        return ContinentPlanner(path, FILEBASE, POLICY)

    def test_complete_preview_is_deterministic_and_has_full_cell_reasons(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            data = terrain()
            data["cells"][0] = {"x": data["minX"], "z": data["minZ"], "status": "failed", "reason": "test"}
            planner = self.make_planner(data, root)
            plan = planner.plan(32)
            self.assertEqual(256, len(plan["reasonCodes"]))
            self.assertEqual("failed", plan["reasonCodes"][0])
            self.assertTrue(plan["previewOnly"])
            self.assertGreater(len(plan["proposals"]), 0)
            self.assertEqual(plan, planner.plan(32))
            self.assertEqual(plan["targetCells"], plan["coveredCells"] + plan["uncoveredCells"])
            self.assertTrue(all(p["newCoveredCells"] == len(p["coveredCellIndices"])
                                for p in plan["proposals"]))
            self.assertTrue(all(data["cells"][j]["status"] == "ok"
                                for p in plan["proposals"] for j in p["coveredCellIndices"]))
            failed_x, failed_z = data["minX"], data["minZ"]
            for proposal in plan["proposals"]:
                px, pz, radius = proposal["x"] + .5, proposal["z"] + .5, proposal["radiusMeters"]
                nearest_x = min(max(px, failed_x), failed_x + 16)
                nearest_z = min(max(pz, failed_z), failed_z + 16)
                self.assertGreater((nearest_x - px) ** 2 + (nearest_z - pz) ** 2, radius ** 2)
                self.assertGreaterEqual(math.hypot(px + 1149.5, pz - 1480.5), radius + 48)
            manifest = planner.export(root / "export")
            self.assertEqual(data["capturedAt"], manifest["capturedAt"])
            self.assertEqual({"32", "48", "64"}, set(manifest["files"]["proposals"]))
            for filename in ("cells.json", "regions.json", "proposals-32.json",
                             "proposals-48.json", "proposals-64.json"):
                value = json.loads((root / "export" / filename).read_text(encoding="utf-8"))
                self.assertEqual(manifest["exportRevision"], value["exportRevision"])
                self.assertEqual(manifest["terrainFingerprint"], value["terrainFingerprint"])

    def test_existing_enemy_levels_and_draft_snow_spawner(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            planner = self.make_planner(terrain(1904, -2096, 256, 256, "minecraft:snowy_plains"), root)
            plan = planner.plan(32)
            drafts = [p for p in plan["proposals"] if p["definitionStatus"] == "draft"]
            self.assertTrue(drafts)
            self.assertTrue(all(p["masterUpdateRequired"] and p["originalRadiusMeters"] is None
                                and p["draftDefinition"]["registrationRequired"] for p in drafts))
            self.assertTrue(all(p["level"] in {32, 34, 36, 38} for p in drafts))
            self.assertTrue(all(p["spawnMobs"][0]["level"] == p["level"] for p in drafts))
            self.assertIsNone(plan["maxAliveUpperBound"])
            self.assertEqual(len(drafts), plan["unknownLimitProposalCount"])

    def test_missing_or_duplicate_cell_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            data = terrain(width=128, height=128)
            data["cells"].pop()
            with self.assertRaisesRegex(ValueError, "every cell"):
                self.make_planner(data, root)
            data = terrain(width=128, height=128)
            data["cells"][1] = data["cells"][0]
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                self.make_planner(data, root)

    def test_water_gap_separates_coarse_land_components(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            data = terrain(400, 960, 256, 256)
            for cell in data["cells"]:
                if cell["x"] == 528:
                    cell.update({"surface": "minecraft:water", "ground": "minecraft:sand",
                                 "waterCount": 256, "biome": "minecraft:river"})
            planner = self.make_planner(data, root)
            plan = planner.plan(48)
            self.assertEqual(2, len(planner.land_components))
            self.assertGreater(len(plan["proposals"]), 0)
            for proposal in plan["proposals"]:
                self.assertTrue(all(planner.cells[j]["landComponentId"] == proposal["landComponentId"]
                                    for j in proposal["coveredCellIndices"]))


if __name__ == "__main__":
    unittest.main()
