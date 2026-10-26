"""Contract and safety tests for the whole-continent preview export."""
import json
import math
import tempfile
import unittest
from pathlib import Path

from continent import ContinentPlanner, REASONS, _mob_name


HERE = Path(__file__).resolve().parent
FILEBASE = HERE.parents[1] / "40_filebase"
POLICY = HERE / "policies" / "eriva-continent.json"
ROAD_POLICY = HERE / "policies" / "eriva-westroad.json"


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

    def test_level_specific_display_names_for_existing_and_draft(self):
        with tempfile.TemporaryDirectory() as temp:
            planner = self.make_planner(terrain(), Path(temp))
            self.assertEqual("エリヴァ・グラスボア Lv.16",
                             planner.spawners["eriva_grassboar_lv16_spawner"]["spawnMobs"][0]["name"])
            self.assertEqual("コケギツネ Lv.26",
                             planner.spawners["field_moss_fox_lv26_spawner"]["spawnMobs"][0]["name"])
            self.assertEqual("基礎名", _mob_name({"id": "fallback", "name": "&a基礎名",
                                                  "levels": [{"level": 26}]}, 26))

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

    def test_spawn_circle_excludes_hazardous_ground_cells_including_tangent(self):
        with tempfile.TemporaryDirectory() as temp:
            data = terrain(-1408, 1280, 512, 512)
            hazard = next(c for c in data["cells"] if (c["x"], c["z"]) == (-1248, 1536))
            hazard["groundCounts"] = {"minecraft:grass_block": 255, "minecraft:magma_block": 1}
            planner = self.make_planner(data, Path(temp))
            self.assertTrue(planner.hazardous_cell_indices)
            for x, z in ((-1248 - 64, 1544), (-1232 + 64, 1544),
                         (-1240, 1536 - 64), (-1240, 1552 + 64)):
                with self.subTest(tangent=(x, z)):
                    self.assertFalse(planner._circle_avoids_hazard(x, z, 64))
            for x, z in ((-1248 - 64.1, 1544), (-1232 + 64.1, 1544),
                         (-1240, 1536 - 64.1), (-1240, 1552 + 64.1)):
                with self.subTest(beyond=(x, z)):
                    self.assertTrue(planner._circle_avoids_hazard(x, z, 64))
            plan = planner.plan(64)
            self.assertTrue(plan["proposals"])
            self.assertTrue(all(planner._circle_avoids_hazard(p["x"] + .5, p["z"] + .5, 64)
                                for p in plan["proposals"]))
            hazard_index = planner.terrain["cells"].index(hazard)
            self.assertEqual("uncovered", plan["reasonCodes"][hazard_index])
            self.assertIn("危険地形", REASONS["uncovered"])

    def test_hazard_detection_accepts_count_histograms_and_representative_materials(self):
        base = {"status": "ok", "ground": "minecraft:grass_block", "surface": "minecraft:air"}
        for key, value in (("groundCounts", {"minecraft:magma_block": 1}),
                           ("surfaceCounts", {"minecraft:lava": 1}),
                           ("ground", "minecraft:magma_block"), ("surface", "minecraft:lava")):
            with self.subTest(key=key):
                self.assertTrue(ContinentPlanner._has_hazardous_surface({**base, key: value}))
        self.assertFalse(ContinentPlanner._has_hazardous_surface(base))


class RoadProgressionTests(unittest.TestCase):
    def make_planner(self, root, data, extension=None):
        filebase = root / "filebase"
        world_dir = filebase / "60.features.world"
        mob_dir = filebase / "40.features.mob" / "enemy"
        spawner_dir = filebase / "41.features.mob.spawner"
        for directory in (world_dir, mob_dir, spawner_dir):
            directory.mkdir(parents=True)
        (world_dir / "world.yml").write_text(
            "id: eriva_supercontinent\ndisplayName: Eriva\nbaseWorldPath: eriva_supercontinent\n"
            "spawnLocation: {x: -1149.5, y: 107, z: 1480.5}\n"
            "adventureGuide: {recommendedLevelMin: 15}\n", encoding="utf-8")
        policy = json.loads(ROAD_POLICY.read_text(encoding="utf-8"))
        policy["road"].pop("terrainExtension", None)
        if extension:
            policy["road"]["terrainExtension"] = extension
        policy_path = root / "policy.json"
        policy_path.write_text(json.dumps(policy), encoding="utf-8")
        levels = policy["road"]["levels"]
        (mob_dir / "road.yml").write_text(
            "id: road_test_enemy\ncategory: ENEMY\nname: Road Test\nlevel: 15\n" +
            "levels:\n" + "".join(f"- level: {level}\n  name: Road Test Lv.{level}\n" for level in levels),
            encoding="utf-8")
        for habitat, ids in policy["fieldSpawnerIds"].items():
            for level, spawner_id in zip(levels, ids):
                (spawner_dir / f"{spawner_id}.yml").write_text(
                    f"id: {spawner_id}\nregion: test {habitat}\nradiusMeters: 64\n"
                    f"spawnMobs:\n- mobId: mob:road_test_enemy\n  level: {level}\n  weight: 100\n"
                    "spawnLimit: {maxAlivePerSpawner: 3}\n", encoding="utf-8")
        terrain_path = root / "terrain.json"
        terrain_path.write_text(json.dumps(data), encoding="utf-8")
        return ContinentPlanner(terrain_path, filebase, policy_path)

    @staticmethod
    def road_terrain():
        data = terrain(-1408, 992, 768, 768)
        for cell in data["cells"]:
            # Spawn-connected north road, plus a disconnected diagonal and a NE road.
            if (cell["x"] == -1152 and 1120 <= cell["z"] <= 1472) or \
                    (cell["x"], cell["z"]) in {(-1136, 1104), (-896, 1472)}:
                cell["groundCounts"] = {"minecraft:dirt_path": 16,
                                         "minecraft:grass_block": 240}
        return data

    def test_connected_road_only_and_reserved_future(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            planner = self.make_planner(root, self.road_terrain())
            self.assertNotIn((-1136, 1104), planner.road_distances)
            self.assertNotIn((-896, 1472), planner.road_distances)
            self.assertEqual(23, len(planner.road_nodes))
            self.assertEqual(352, planner.max_road_distance)
            expanded = next(c for c in planner.cells if (c["x"], c["z"]) == (-896, 1472))
            self.assertEqual("eligible", expanded["reasonCode"])
            far = next(c for c in planner.cells if (c["x"], c["z"]) == (-656, 992))
            self.assertEqual("reserved_future", far["reasonCode"])
            self.assertIsNone(far["roadDistanceMeters"])
            end = next(c for c in planner.cells if (c["x"], c["z"]) == (-1152, 1120))
            self.assertEqual(18, end["targetLevel"])
            self.assertEqual(352, end["roadDistanceMeters"])
            self.assertEqual({"エリヴァ南西街道"}, {c["regionName"] for c in planner.cells if c["targetLevel"] is not None})
            plan = planner.plan(64)
            self.assertTrue(plan["proposals"])
            self.assertTrue(all(p["definitionStatus"] == "existing" and p["level"] == p["targetLevel"]
                                for p in plan["proposals"]))
            self.assertEqual(0, plan["unknownLimitProposalCount"])
            manifest = planner.export(root / "export")
            self.assertEqual("continent-redesign", manifest["mode"])
            self.assertEqual("road", manifest["progressionPolicy"]["mode"])
            self.assertEqual(300, manifest["progressionPolicy"]["levelStepMeters"])
            self.assertEqual(23, len(manifest["progressionPolicy"]["road"]["nodes"]))
            self.assertEqual(22, len(manifest["progressionPolicy"]["road"]["edges"]))
            self.assertEqual({"64"}, set(manifest["files"]["proposals"]))
            for proposal in plan["proposals"]:
                self.assertTrue(planner._circle_within_road_scope(
                    proposal["x"] + .5, proposal["z"] + .5, 64))

    def test_fixed_distance_bands_do_not_compress_when_road_length_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            short = self.make_planner(root / "short", self.road_terrain())
            data = self.road_terrain()
            for cell in data["cells"]:
                if cell["x"] == -1152 and cell["z"] <= 1472:
                    cell["groundCounts"] = {"minecraft:dirt_path": 16}
            longer = self.make_planner(root / "longer", data)
            self.assertGreater(longer.max_road_distance, short.max_road_distance)
            for planner in (short, longer):
                cells = {(c["x"], c["z"]): c for c in planner.cells}
                self.assertEqual(15, cells[(-1152, 1184)]["targetLevel"])  # 288m
                self.assertEqual(18, cells[(-1152, 1168)]["targetLevel"])  # 304m
                self.assertEqual(18, cells[(-1152, 1120)]["targetLevel"])

    def test_truthful_unknown_and_water_reasons_outside_road_scope(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            data = self.road_terrain()
            by_pos = {(c["x"], c["z"]): c for c in data["cells"]}
            by_pos[(-656, 992)] = {"x": -656, "z": 992, "status": "absent"}
            by_pos[(-672, 992)] = {"x": -672, "z": 992, "status": "failed"}
            by_pos[(-688, 992)].update({"surface": "minecraft:water", "ground": "minecraft:sand",
                                       "waterCount": 256})
            data["cells"] = list(by_pos.values())
            planner = self.make_planner(root, data)
            reasons = {(c["x"], c["z"]): c["reasonCode"] for c in planner.cells}
            self.assertEqual("absent", reasons[(-656, 992)])
            self.assertEqual("failed", reasons[(-672, 992)])
            self.assertEqual("water", reasons[(-688, 992)])

    def test_terrain_continuation_avoids_water_and_reaches_lv45_without_projection_jumps(self):
        with tempfile.TemporaryDirectory() as temp:
            data = terrain(-1408, -2048, 768, 3840)
            for cell in data["cells"]:
                if cell["x"] == -1152 and 1120 <= cell["z"] <= 1472:
                    cell["groundCounts"] = {"minecraft:dirt_path": 16}
                # River has one land crossing, so the route must go around it.
                if cell["z"] == 0 and cell["x"] != -896:
                    cell.update(waterCount=256, ground="minecraft:water")
            extension = {"targetCell": [-1152, -1808], "minSafeColumns": 64,
                         "maxLocalSlope": 8, "maxStepHeight": 2, "maxDistanceMeters": 4208,
                         "searchBounds": {"minX": -1408, "maxX": -640, "minZ": -2048, "maxZ": 1792}}
            planner = self.make_planner(Path(temp), data, extension)
            self.assertIn((-896, 0), planner.extension_path)
            self.assertTrue(all(z != 0 or x == -896 for x, z in planner.extension_path))
            self.assertGreaterEqual(planner.max_road_distance, 3000)
            self.assertIn(45, {p["level"] for p in planner.plan(64)["proposals"]})
            grid = {(c["x"], c["z"]): c for c in planner.cells}
            for p, distance in planner.terrain_progress.items():
                for q in planner._neighbors(p):
                    if q in planner.terrain_progress and planner._terrain_step(grid[p], grid[q], extension):
                        self.assertLessEqual(abs(distance - planner.terrain_progress[q]), 16)

    def test_terrain_continuation_refuses_disconnected_target(self):
        with tempfile.TemporaryDirectory() as temp:
            data = self.road_terrain()
            for cell in data["cells"]:
                if cell["z"] == 1088:
                    cell.update(waterCount=256, ground="minecraft:water")
            extension = {"targetCell": [-1152, 1024], "minSafeColumns": 64,
                         "maxLocalSlope": 8, "maxStepHeight": 2, "maxDistanceMeters": 3808,
                         "searchBounds": {"minX": -1408, "maxX": -640, "minZ": 992, "maxZ": 1760}}
            with self.assertRaisesRegex(ValueError, "No terrain continuation"):
                self.make_planner(Path(temp), data, extension)

    def test_terrain_continuation_branches_from_original_roads_without_extra_edges(self):
        with tempfile.TemporaryDirectory() as temp:
            data = terrain(-1408, -2048, 768, 3840)
            for cell in data["cells"]:
                if cell["x"] == -1152 and 1120 <= cell["z"] <= 1472:
                    cell["groundCounts"] = {"minecraft:dirt_path": 16}
            extension = {"targetCells": [[-1152, -1808], [-800, 800]],
                         "minSafeColumns": 64, "maxLocalSlope": 8, "maxStepHeight": 2,
                         "maxDistanceMeters": 4208,
                         "searchBounds": {"minX": -1408, "maxX": -640, "minZ": -2048, "maxZ": 1792}}
            root = Path(temp)
            planner = self.make_planner(root / "branches", data, extension)
            north = self.make_planner(root / "north", data,
                                      {**extension, "targetCells": [extension["targetCells"][0]]})
            self.assertEqual(north.extension_path, planner.extension_paths[0])
            self.assertEqual((-1152, -1808), planner.extension_paths[0][-1])
            self.assertEqual((-800, 800), planner.extension_paths[1][-1])
            self.assertLess(planner.terrain_progress[(-800, 800)], 3000)
            self.assertGreaterEqual(planner.terrain_progress[(-1152, -1808)], 3000)
            self.assertEqual({p: north.road_distances[p] for p in north.measured_roads},
                             {p: planner.road_distances[p] for p in planner.measured_roads})
            self.assertEqual(set().union(*map(set, planner.extension_paths)) | planner.measured_roads,
                             set(planner.road_distances))
            nodes = [(node["x"] - 8, node["z"] - 8) for node in planner.road_nodes]
            actual = {frozenset((nodes[a], nodes[b])) for a, b in planner.road_edges}
            measured = {frozenset((p, q)) for p in planner.measured_roads
                        for q in planner._neighbors(p) if q in planner.measured_roads}
            selected = {frozenset((a, b)) for path in planner.extension_paths
                        for a, b in zip(path, path[1:])}
            self.assertEqual(measured | selected, actual)
            self.assertEqual(len(actual), len(planner.road_edges))
            self.assertTrue(all(abs(a[0] - b[0]) + abs(a[1] - b[1]) == 16
                                for edge in actual for a, b in [tuple(edge)]))

    def test_terrain_extension_rejects_invalid_target_lists(self):
        with tempfile.TemporaryDirectory() as temp:
            data = self.road_terrain()
            extension = {"targetCells": [[-896, 1024]], "minSafeColumns": 64,
                         "maxLocalSlope": 8, "maxStepHeight": 2, "maxDistanceMeters": 3808,
                         "searchBounds": {"minX": -1408, "maxX": -640, "minZ": 992, "maxZ": 1760}}
            cases = [
                ({**extension, "targetCell": [-896, 1024]}, "mutually exclusive"),
                ({**extension, "targetCells": []}, "target cells"),
                ({**extension, "targetCells": [[-896, 1024], [-896, 1024]]}, "unique"),
                ({**extension, "targetCells": [[-895, 1024]]}, "target cells"),
                ({**extension, "targetCells": [[True, 1024]]}, "target cells"),
                ({**extension, "targetCells": [[-1152, 1120]]}, "new measured cells"),
                ({**extension, "targetCells": [[-896, 976]]}, "new measured cells"),
                (extension, "maximum level distance"),
            ]
            for index, (settings, message) in enumerate(cases):
                with self.subTest(index=index), self.assertRaisesRegex(ValueError, message):
                    self.make_planner(Path(temp) / str(index), data, settings)


if __name__ == "__main__":
    unittest.main()
