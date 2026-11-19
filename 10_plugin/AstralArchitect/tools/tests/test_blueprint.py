from __future__ import annotations

import copy
import json
import unittest
from unittest.mock import patch

from astralarchitect_ticket.blueprint import BlueprintError, compile_blueprint
from astralarchitect_ticket.schematic import MAX_OPERATIONS


def _set(block: str, at: list[int] | None = None) -> dict:
    return {"op": "set", "at": [0, 0, 0] if at is None else at, "block": block}


def _plan(rough: list[dict], detail: list[dict] | None = None) -> dict:
    return {
        "version": 1,
        "origin": [0, 0, 0],
        "parts": {"piece": {"rough": rough, "detail": [] if detail is None else detail}},
        "placements": [{"part": "piece", "at": [0, 0, 0]}],
    }


def _roof(
    offsets: list[int], sections: list[tuple[int, list[int]]], at: list[int] | None = None,
) -> dict:
    return {
        "op": "roof_sheet",
        "at": [0, 0, 0] if at is None else at,
        "offsets": offsets,
        "sections": [{"z": z, "heights": heights} for z, heights in sections],
        "block": "minecraft:dark_prismarine",
        "slab": "minecraft:dark_prismarine_slab",
    }


def _roof_layers(operations: list[dict]) -> dict[tuple[int, int], set[int]]:
    layers = {}
    for operation in operations:
        first = operation if operation["op"] == "set" else operation["from"]
        last = operation if operation["op"] == "set" else operation["to"]
        column = layers.setdefault((first["x"], first["z"]), set())
        for y in range(first["y"], last["y"] + 1):
            if not operation["block"].endswith("[type=top]"):
                column.add(2 * y)
            if not operation["block"].endswith("[type=bottom]"):
                column.add(2 * y + 1)
    return layers


class BlueprintTest(unittest.TestCase):
    def test_repeat_translation_and_stage_order(self) -> None:
        plan = _plan([_set("minecraft:stone", [2, 1, -3])], [_set("minecraft:gold_block")])
        plan["origin"] = [100, 60, 200]
        plan["placements"] = [
            {"part": "piece", "at": [5, 0, 4], "mirror": "x", "repeat": {"count": 2, "step": [7, 1, -2]}},
            {"part": "piece", "at": [-1, 3, 0], "mirror": "z"},
        ]
        unchanged = copy.deepcopy(plan)
        rough = [
            {"op": "set", "x": 103, "y": 61, "z": 201, "block": "minecraft:stone"},
            {"op": "set", "x": 110, "y": 62, "z": 199, "block": "minecraft:stone"},
            {"op": "set", "x": 101, "y": 64, "z": 203, "block": "minecraft:stone"},
        ]
        detail = [
            {"op": "set", "x": 105, "y": 60, "z": 204, "block": "minecraft:gold_block"},
            {"op": "set", "x": 112, "y": 61, "z": 202, "block": "minecraft:gold_block"},
            {"op": "set", "x": 99, "y": 63, "z": 200, "block": "minecraft:gold_block"},
        ]
        self.assertEqual(compile_blueprint(plan, "rough"), rough)
        self.assertEqual(compile_blueprint(plan, "detail"), rough + detail)
        self.assertEqual(plan, unchanged)

    def test_rough_compiles_before_detail_is_authored(self) -> None:
        plan = _plan([_set("minecraft:stone")])
        expected = [{"op": "set", "x": 0, "y": 0, "z": 0, "block": "minecraft:stone"}]
        self.assertEqual(compile_blueprint(plan, "rough"), expected)
        self.assertEqual(compile_blueprint(plan, "detail"), expected)

    def test_reapply_rough_matches_explicit_detail_prefix_with_overlapping_repeats(self) -> None:
        rough = [_set("minecraft:oak_stairs[facing=east,shape=inner_left]", [1, 0, 0])]
        detail = [_set("minecraft:gold_block")]
        plan = _plan(rough, detail)
        plan["parts"]["other"] = {
            "rough": [_set("minecraft:andesite")],
            "detail": [_set("minecraft:diamond_block", [2, 0, 0])],
        }
        plan["placements"] = [
            {"part": "piece", "at": [1, 0, 0], "mirror": "x", "repeat": {"count": 2, "step": [1, 0, 0]}},
            {"part": "other", "at": [0, 0, 0]},
        ]
        explicit = copy.deepcopy(plan)
        explicit["parts"]["piece"]["detail"] = [*rough, *detail]
        expected_rough = compile_blueprint(plan, "rough")
        plan["parts"]["piece"]["reapplyRough"] = True
        unchanged = copy.deepcopy(plan)

        self.assertEqual(compile_blueprint(plan, "rough"), expected_rough)
        operations = compile_blueprint(plan, "detail")
        self.assertEqual(operations, compile_blueprint(explicit, "detail"))
        mirrored = "minecraft:oak_stairs[facing=west,shape=inner_right]"
        self.assertEqual(
            {operation["x"]: operation["block"] for operation in operations},
            {0: mirrored, 1: mirrored, 2: "minecraft:diamond_block"},
        )
        self.assertEqual(plan, unchanged)

    def test_reapply_rough_false_matches_omission_in_both_stages(self) -> None:
        plan = _plan([_set("minecraft:stone")], [_set("minecraft:gold_block")])
        explicit_false = copy.deepcopy(plan)
        explicit_false["parts"]["piece"]["reapplyRough"] = False
        for stage in ("rough", "detail"):
            with self.subTest(stage=stage):
                self.assertEqual(compile_blueprint(explicit_false, stage), compile_blueprint(plan, stage))

    def test_reapply_rough_requires_a_boolean(self) -> None:
        plan = _plan([_set("minecraft:stone")])
        for value in (None, 0, 1, 0.0, "false", [], {}):
            with self.subTest(value=value):
                plan["parts"]["piece"]["reapplyRough"] = value
                with self.assertRaisesRegex(BlueprintError, r"reapplyRough must be a boolean"):
                    compile_blueprint(plan, "rough")

    def test_reapply_rough_counts_expanded_roof_operations_even_in_unused_part(self) -> None:
        plan = _plan([_set("minecraft:stone")])
        roof = _roof([0, 1], [(0, [1, 1])])
        plan["parts"]["roof"] = {"rough": [roof], "detail": [], "reapplyRough": True}
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", 5):
            self.assertEqual(len(compile_blueprint(plan, "rough")), 1)
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", 4):
            with self.assertRaisesRegex(BlueprintError, "more than 4 local operations"):
                compile_blueprint(plan, "rough")

    def test_reapply_rough_counts_toward_placement_operation_and_visit_limits(self) -> None:
        plan = _plan([{"op": "fill", "from": [0, 0, 0], "to": [2, 0, 0], "block": "minecraft:stone"}])
        plan["placements"][0]["repeat"] = {"count": 2, "step": [1, 0, 0]}
        for limit, value, error in (
            ("MAX_OPERATIONS", 3, "expands beyond 3 operations"),
            ("MAX_TICKET_VOLUME", 6, "expands beyond 6 block visits"),
        ):
            with self.subTest(limit=limit), patch(f"astralarchitect_ticket.blueprint.{limit}", value):
                part = plan["parts"]["piece"]
                part["reapplyRough"] = False
                self.assertEqual(len(compile_blueprint(plan, "rough")), 2)
                part["reapplyRough"] = True
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(plan, "rough")

    def test_reapply_rough_counts_toward_detail_output_byte_limit(self) -> None:
        plan = _plan([_set("minecraft:stone")], [_set("minecraft:gold_block")])
        ordinary_detail = compile_blueprint(plan, "detail")
        byte_limit = len(json.dumps(ordinary_detail, separators=(",", ":")).encode("utf-8"))
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATION_BYTES", byte_limit):
            self.assertEqual(compile_blueprint(plan, "detail"), ordinary_detail)
            plan["parts"]["piece"]["reapplyRough"] = True
            self.assertEqual(compile_blueprint(plan, "rough"), ordinary_detail[:1])
            with self.assertRaisesRegex(BlueprintError, "output exceeds.*operation bytes"):
                compile_blueprint(plan, "detail")

    def test_reapply_rough_keeps_detail_validation_in_rough_stage(self) -> None:
        plan = _plan([_set("minecraft:stone")])
        part = plan["parts"]["piece"]
        part["reapplyRough"] = True
        plan["origin"] = [2**31 - 2, 0, 0]
        plan["placements"][0].update({"mirror": "x", "repeat": {"count": 2, "step": [1, 0, 0]}})
        for operation, error in (
            ({"op": "set", "block": "minecraft:gold_block"}, "missing.*at"),
            (_set("example:brick"), "cannot mirror a block from namespace"),
            (_set("minecraft:gold_block", [-1, 0, 0]), "outside the signed 32-bit coordinate range"),
        ):
            with self.subTest(operation=operation):
                part["detail"] = [operation]
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(plan, "rough")

    def test_fill_and_line_endpoints_keep_their_order(self) -> None:
        plan = _plan([
            {"op": kind, "from": [-3, 2, 7], "to": [5, 5, -1], "block": "minecraft:stone"}
            for kind in ("fill", "line")
        ])
        plan["placements"][0]["mirror"] = "x"
        self.assertEqual(
            compile_blueprint(plan, "rough"),
            [
                {"op": kind, "from": {"x": 3, "y": 2, "z": 7},
                 "to": {"x": -5, "y": 5, "z": -1}, "block": "minecraft:stone"}
                for kind in ("fill", "line")
            ],
        )

    def test_reflect_block_states(self) -> None:
        cases = [
            ("x", "oak_stairs[facing=east,half=top,shape=inner_left,waterlogged=false]",
             "oak_stairs[facing=west,half=top,shape=inner_right,waterlogged=false]"),
            ("z", "oak_stairs[facing=east,shape=outer_right]", "oak_stairs[facing=east,shape=outer_left]"),
            ("z", "oak_door[facing=north,hinge=left,half=upper]", "oak_door[facing=south,hinge=right,half=upper]"),
            ("x", "chest[facing=west,type=left]", "chest[facing=east,type=right]"),
            ("z", "oak_fence[north=true,south=false,east=true,west=false]",
             "oak_fence[north=false,south=true,east=true,west=false]"),
            ("x", "stone_brick_wall[east=tall]", "stone_brick_wall[west=tall]"),
            ("x", "oak_log[axis=x]", "oak_log[axis=x]"),
            ("x", "oak_shelf[facing=east,side_chain=left]", "oak_shelf[facing=west,side_chain=right]"),
            ("z", "carved_pumpkin[facing=north]", "carved_pumpkin[facing=south]"),
            ("z", "jack_o_lantern[facing=north]", "jack_o_lantern[facing=south]"),
            ("z", "oak_button[face=wall,facing=north,powered=false]",
             "oak_button[face=wall,facing=south,powered=false]"),
            ("x", "oak_sign[rotation=3]", "oak_sign[rotation=13]"),
            ("z", "oak_sign[rotation=3]", "oak_sign[rotation=5]"),
            ("z", "rail[shape=south_east]", "rail[shape=north_east]"),
            ("x", "powered_rail[shape=ascending_east,powered=false]",
             "powered_rail[shape=ascending_west,powered=false]"),
            ("z", "crafter[orientation=up_north,triggered=false]",
             "crafter[orientation=up_south,triggered=false]"),
        ]
        for axis, original, expected in cases:
            with self.subTest(axis=axis, original=original):
                plan = _plan([_set("minecraft:" + original)])
                plan["placements"][0]["mirror"] = axis
                self.assertEqual(compile_blueprint(plan, "rough")[0]["block"], "minecraft:" + expected)

    def test_refuses_unknown_or_underspecified_mirrored_states(self) -> None:
        for block, error in (
            ("minecraft:stone[orientation=north]", "cannot mirror block property"),
            ("example:brick", "cannot mirror a block from namespace"),
            ("minecraft:oak_stairs[half=top]", "requires explicit 'facing'"),
            ("minecraft:carved_pumpkin", "requires explicit 'facing'"),
            ("minecraft:jack_o_lantern", "requires explicit 'facing'"),
            ("minecraft:stone_button[face=wall]", "requires explicit 'facing'"),
            ("minecraft:waxed_weathered_lightning_rod", "requires explicit 'facing'"),
            ("minecraft:oak_door[facing=north]", "requires explicit 'hinge'"),
        ):
            with self.subTest(block=block):
                plan = _plan([_set("minecraft:stone")], [_set(block)])
                plan["placements"][0]["mirror"] = "x"
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(plan, "rough")

    def test_rejects_bad_schema_and_invalid_local_operations(self) -> None:
        for field, value, error in (
            ("version", True, "version must be 1"),
            ("origin", [0, False, 0], "three integers"),
            ("placements", [{"part": "missing", "at": [0, 0, 0]}], "defined part"),
            ("placements", [{"part": "piece", "at": [0, 0, 0], "mirror": None}], "must be x or z"),
        ):
            with self.subTest(field=field, value=value):
                plan = _plan([_set("minecraft:stone")])
                plan[field] = value
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(plan, "rough")
        for operation, error in (
            ({"op": "replace"}, "must be set, fill, line, or roof_sheet"),
            ({"op": "set", "x": 0, "y": 0, "z": 0, "block": "minecraft:stone"}, "missing.*at"),
            ({"op": "set", "at": [0, 0, 0], "expect": "minecraft:air", "block": "minecraft:stone"},
             "unknown properties: expect"),
            (_set("minecraft:rail[shape=east_west,shape=west_east]"), "duplicate block property"),
        ):
            with self.subTest(operation=operation):
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(_plan([operation]), "rough")

    def test_rejects_expansion_before_emitting_operations(self) -> None:
        plan = _plan([_set("minecraft:stone"), _set("minecraft:gold_block")])
        plan["placements"][0]["repeat"] = {"count": MAX_OPERATIONS, "step": [1, 0, 0]}
        with self.assertRaisesRegex(BlueprintError, "expands beyond.*operations"):
            compile_blueprint(plan, "rough")
        plan = _plan([{
            "op": "fill", "from": [0, 0, 0], "to": [1000, 1000, 1000], "block": "minecraft:stone"
        }])
        with self.assertRaisesRegex(BlueprintError, "expands beyond.*block visits"):
            compile_blueprint(plan, "rough")
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATION_BYTES", 10):
            with self.assertRaisesRegex(BlueprintError, "output exceeds.*operation bytes"):
                compile_blueprint(_plan([_set("minecraft:stone")]), "rough")

    def test_rejects_coordinate_overflow_in_repeated_detail(self) -> None:
        plan = _plan([_set("minecraft:stone")], [_set("minecraft:gold_block", [1, 0, 0])])
        plan["origin"] = [2**31 - 3, 0, 0]
        plan["placements"][0]["repeat"] = {"count": 3, "step": [1, 0, 0]}
        with self.assertRaisesRegex(BlueprintError, "outside the signed 32-bit coordinate range"):
            compile_blueprint(plan, "rough")

    def test_roof_single_cell_uses_the_correct_half_at_signed_heights(self) -> None:
        for height, y, slab_type in ((1, 10, "bottom"), (0, 9, "top"), (-1, 9, "bottom"), (-2, 8, "top")):
            with self.subTest(height=height):
                plan = _plan([_roof([-2], [(3, [height])], [5, 10, 7])])
                self.assertEqual(compile_blueprint(plan, "rough"), [{
                    "op": "set", "x": 3, "y": y, "z": 10,
                    "block": f"minecraft:dark_prismarine_slab[type={slab_type}]",
                }])

    def test_roof_interpolates_both_axes_and_rounds_once_including_ties(self) -> None:
        roof = _roof([-1, 1, 3], [(-2, [0, 1, -2]), (0, [0, 0, -1]), (1, [2, -1, -2])])
        tops = {
            (-1, -2): 0, (0, -2): 1, (1, -2): 1, (2, -2): 0, (3, -2): -2,
            (-1, -1): 0, (0, -1): 0, (1, -1): 1, (2, -1): 0, (3, -1): -1,
            (-1, 0): 0, (0, 0): 0, (1, 0): 0, (2, 0): 0, (3, 0): -1,
            (-1, 1): 2, (0, 1): 1, (1, 1): -1, (2, 1): -1, (3, 1): -2,
        }
        expected = {}
        for (x, z), top in tops.items():
            adjacent = ((x - 1, z), (x + 1, z), (x, z - 1), (x, z + 1))
            bottom = min(top, *(tops.get(point, top) for point in adjacent)) - 1
            expected[x, z] = set(range(bottom, top))
        self.assertEqual(_roof_layers(compile_blueprint(_plan([roof]), "rough")), expected)

    def test_roof_slabs_border_one_vertical_fill(self) -> None:
        roof = _roof([0, 1], [(-1, [-2, 7])], [2, 10, 4])
        self.assertEqual(compile_blueprint(_plan([roof]), "rough"), [
            {"op": "set", "x": 2, "y": 8, "z": 3, "block": "minecraft:dark_prismarine_slab[type=top]"},
            {"op": "set", "x": 3, "y": 8, "z": 3, "block": "minecraft:dark_prismarine_slab[type=top]"},
            {"op": "fill", "from": {"x": 3, "y": 9, "z": 3},
             "to": {"x": 3, "y": 12, "z": 3}, "block": "minecraft:dark_prismarine"},
            {"op": "set", "x": 3, "y": 13, "z": 3, "block": "minecraft:dark_prismarine_slab[type=bottom]"},
        ])

    def test_roof_in_detail_mirrors_and_repeats_as_ordinary_operations(self) -> None:
        roof = _roof([0], [(-2, [0]), (-1, [2])], [2, 10, 3])
        plan = _plan([_set("minecraft:stone")], [roof])
        plan["origin"] = [10, 20, 30]
        plan["placements"] = [
            {"part": "piece", "at": [5, 1, 4], "mirror": "x",
             "repeat": {"count": 2, "step": [4, 2, -1]}},
            {"part": "piece", "at": [0, 0, 0], "mirror": "z"},
        ]
        rough = compile_blueprint(plan, "rough")
        detail = compile_blueprint(plan, "detail")
        self.assertEqual([operation["block"] for operation in rough], ["minecraft:stone"] * 3)
        self.assertEqual(detail[:len(rough)], rough)
        expected = []
        for x, y, z, z_step in ((13, 30, 35, 1), (17, 32, 34, 1), (12, 29, 29, -1)):
            expected.extend([
                {"op": "set", "x": x, "y": y, "z": z, "block": "minecraft:dark_prismarine_slab[type=top]"},
                {"op": "set", "x": x, "y": y, "z": z + z_step,
                 "block": "minecraft:dark_prismarine_slab[type=top]"},
                {"op": "set", "x": x, "y": y + 1, "z": z + z_step, "block": "minecraft:dark_prismarine"},
            ])
        self.assertEqual(detail[len(rough):], expected)

    def test_roof_limits_apply_to_every_part_and_to_repeated_placements(self) -> None:
        roof = _roof([0, 2], [(0, [0, 0])])
        plan = _plan([_set("minecraft:stone")])
        plan["parts"]["unused_a"] = {"rough": [roof], "detail": []}
        plan["parts"]["unused_b"] = {"rough": [], "detail": [roof]}
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", 6):
            with self.assertRaisesRegex(BlueprintError, "local operation limit"):
                compile_blueprint(plan, "rough")
            plan = _plan([roof])
            plan["placements"][0]["repeat"] = {"count": 3, "step": [3, 0, 0]}
            with self.assertRaisesRegex(BlueprintError, "expands beyond 6 operations"):
                compile_blueprint(plan, "rough")
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", 3):
            plan = _plan([_roof([0, 1], [(0, [-2, 7])])])
            with self.assertRaisesRegex(BlueprintError, "local operation limit"):
                compile_blueprint(plan, "rough")

    def test_roof_rejects_large_footprints_and_vertical_jumps(self) -> None:
        huge = _roof([0, 2**31 - 1], [(0, [0, 0]), (2**31 - 1, [0, 0])])
        with patch("astralarchitect_ticket.blueprint._roof_axis_samples") as sample:
            with self.assertRaisesRegex(BlueprintError, "local operation limit"):
                compile_blueprint(_plan([huge]), "rough")
            sample.assert_not_called()
        plan = _plan([_set("minecraft:stone")])
        plan["parts"]["unused"] = {"rough": [], "detail": [_roof([0, 1], [(0, [0, 2**31])])]}
        with self.assertRaisesRegex(BlueprintError, "block visits"):
            compile_blueprint(plan, "rough")
        plan = _plan([_roof([0, 1], [(0, [0, 8])])])
        plan["placements"][0]["repeat"] = {"count": 2, "step": [2, 0, 0]}
        with patch("astralarchitect_ticket.blueprint.MAX_TICKET_VOLUME", 10):
            with self.assertRaisesRegex(BlueprintError, "expands beyond 10 block visits"):
                compile_blueprint(plan, "rough")

    def test_roof_validates_coordinates_for_unused_and_skipped_parts(self) -> None:
        for roof in (
            _roof([1], [(0, [0])], [2**31 - 1, 0, 0]),
            _roof([0], [(-1, [0])], [0, 0, -(2**31)]),
            _roof([0], [(0, [0])], [0, -(2**31), 0]),
            _roof([0], [(0, [3])], [0, 2**31 - 1, 0]),
        ):
            with self.subTest(roof=roof):
                plan = _plan([_set("minecraft:stone")])
                plan["parts"]["unused"] = {"rough": [], "detail": [roof]}
                with self.assertRaisesRegex(BlueprintError, "signed 32-bit coordinate range"):
                    compile_blueprint(plan, "rough")
        plan = _plan([_set("minecraft:stone")], [_roof([-1], [(0, [0])])])
        plan["placements"][0].update({
            "at": [2**31 - 3, 0, 0], "mirror": "x", "repeat": {"count": 3, "step": [1, 0, 0]},
        })
        with self.assertRaisesRegex(BlueprintError, "signed 32-bit coordinate range"):
            compile_blueprint(plan, "rough")

    def test_roof_rejects_invalid_control_points_and_materials(self) -> None:
        roof = _roof([0, 1], [(0, [0, 1]), (1, [1, 0])])
        for changes, error in (
            ({"offsets": []}, "nonempty"),
            ({"offsets": [True, 1]}, "only integers"),
            ({"offsets": [1, 0]}, "strictly ascending"),
            ({"offsets": [0, 2**31]}, "signed 32-bit coordinate range"),
            ({"sections": []}, "nonempty"),
            ({"sections": [{"z": 0, "heights": [1]}]}, "one integer per offset"),
            ({"sections": [{"z": 0, "heights": [1, False]}]}, "one integer per offset"),
            ({"sections": [{"z": True, "heights": [1, 2]}]}, "must be an integer"),
            ({"sections": [{"z": 0, "heights": [1, 2], "extra": 0}]}, "unknown properties"),
            ({"sections": [{"z": 1, "heights": [1, 2]}] * 2}, "strictly ascending"),
            ({"extra": 0}, "unknown properties"),
            ({"block": "minecraft:oak_log[axis=x]"}, "without properties"),
            ({"block": "minecraft:air"}, "non-air"),
            ({"block": "minecraft:stone_slab"}, "full cube"),
            ({"block": "minecraft:stone_stairs"}, "full cube"),
            ({"slab": "minecraft:stone"}, "ending in _slab"),
            ({"slab": "minecraft:stone_slab[type=top]"}, "without properties"),
        ):
            with self.subTest(changes=changes):
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(_plan([{**roof, **changes}]), "rough")


class RoofStairsTest(unittest.TestCase):
    @staticmethod
    def _supported_roof(rows: list[list[int]], bottom: int = -4) -> dict:
        roof = _roof(list(range(len(rows[0]))), list(enumerate(rows)))
        for section in roof["sections"]:
            section["undersides"] = [bottom] * len(rows[0])
        return roof

    def test_stairs_face_uphill_in_both_axes_and_follow_placement_mirrors(self) -> None:
        for rows, facing, reflected_x, reflected_z in (
            ([[1, 2, 3]] * 3, "east", "west", "east"),
            ([[3, 2, 1]] * 3, "west", "east", "west"),
            ([[height] * 3 for height in (-3, -2, -1)], "south", "south", "north"),
            ([[height] * 3 for height in (-1, -2, -3)], "north", "north", "south"),
        ):
            roof = self._supported_roof(rows, -6)
            roof.update(at=[2, 8, 4], stairs="minecraft:oak_stairs")
            plan = _plan([_set("minecraft:stone")], [roof])
            plan["origin"] = [10, 20, 30]
            for axis, expected_facing in ((None, facing), ("x", reflected_x), ("z", reflected_z)):
                with self.subTest(facing=facing, mirror=axis):
                    plan["placements"][0] = {"part": "piece", "at": [1, 2, 3]}
                    if axis:
                        plan["placements"][0]["mirror"] = axis
                    rough = compile_blueprint(plan, "rough")
                    detail = compile_blueprint(plan, "detail")
                    self.assertEqual(len(rough), 1)
                    self.assertEqual(detail[:len(rough)], rough)
                    self.assertEqual([op for op in detail if ":oak_stairs[" in op["block"]], [{
                        "op": "set", "x": 8 if axis == "x" else 14,
                        "y": 30 + (rows[1][1] - 2) // 2, "z": 28 if axis == "z" else 38,
                        "block": f"minecraft:oak_stairs[facing={expected_facing},half=bottom,shape=straight]",
                    }])

    def test_level_uphill_neighbor_requires_a_continuing_slope(self) -> None:
        for next_top in (3, 4):
            with self.subTest(next_top=next_top):
                roof = self._supported_roof([[1, 2, 2, next_top]] * 3, -1)
                roof["stairs"] = "minecraft:stone_stairs"
                self.assertEqual([
                    op for op in compile_blueprint(_plan([roof]), "rough") if ":stone_stairs[" in op["block"]
                ], [{"op": "set", "x": 1, "y": 0, "z": 1,
                     "block": "minecraft:stone_stairs[facing=east,half=bottom,shape=straight]"}])

    def test_stairs_replace_a_single_course_with_implicit_or_explicit_undersides(self) -> None:
        for heights, undersides in (([1, 2, 3], [0, 0, 1]), ([0, 2, 4], [-1, -1, 1])):
            for explicit in (False, True):
                with self.subTest(heights=heights, explicit=explicit):
                    roof = _roof([0, 1, 2], [(0, heights), (2, heights)])
                    if explicit:
                        for section in roof["sections"]:
                            section["undersides"] = undersides
                    plan = _plan([roof])
                    expected = compile_blueprint(plan, "rough")
                    index = expected.index({"op": "set", "x": 1, "y": 0, "z": 1, "block": roof["block"]})
                    expected[index]["block"] = "minecraft:stone_stairs[facing=east,half=bottom,shape=straight]"
                    roof["stairs"] = "minecraft:stone_stairs"
                    self.assertEqual(compile_blueprint(plan, "rough"), expected)

    def test_parallel_grades_can_cross_courses_of_different_heights(self) -> None:
        roof = self._supported_roof([[20, 21, 24], [20, 22, 23], [18, 19, 20]], 16)
        roof["stairs"] = "minecraft:stone_stairs"
        self.assertEqual([
            op for op in compile_blueprint(_plan([roof]), "rough") if ":stone_stairs[" in op["block"]
        ], [{"op": "set", "x": 1, "y": 10, "z": 1,
             "block": "minecraft:stone_stairs[facing=east,half=bottom,shape=straight]"}])

    def test_preserves_ambiguous_or_unsupported_cells_and_their_reserved_halves(self) -> None:
        for label, rows in (
            ("flat", [[2, 2, 2]] * 3),
            ("ridge", [[1, 2, 1]] * 3),
            ("valley", [[3, 2, 3]] * 3),
            ("shoulder", [[1, 2, 2, 2]] * 3),
            ("steep downhill", [[-1, 2, 3]] * 3),
            ("steep uphill", [[1, 2, 5]] * 3),
            ("steep uphill beyond level neighbor", [[1, 2, 2, 5]] * 3),
            ("top slab", [[2, 3, 4]] * 3),
            ("thin strip", [[1, 2, 3]] * 2),
            ("ambiguous diagonal slope", [[0, 1, 2], [1, 2, 3], [2, 3, 4]]),
            ("side shoulder", [[1, 3, 2], [1, 2, 3], [1, 2, 3]]),
        ):
            with self.subTest(label=label):
                roof = self._supported_roof(rows)
                plan = _plan([roof])
                original = compile_blueprint(plan, "rough")
                roof["stairs"] = "minecraft:stone_stairs"
                self.assertEqual(compile_blueprint(plan, "rough"), original)

        for label, low, column, row, bottom in (
            ("no contact below a two-layer drop", 0, 1, 1, 0),
            ("reserved lower half", 1, 1, 1, 1),
            ("no lateral contact in the lower half", 1, 1, 0, 1),
            ("no uphill contact in the upper half", 1, 2, 1, 2),
        ):
            with self.subTest(label=label):
                roof = self._supported_roof([[low, 2, 3]] * 3)
                roof["sections"][row]["undersides"][column] = bottom
                plan = _plan([roof])
                original = compile_blueprint(plan, "rough")
                roof["stairs"] = "minecraft:stone_stairs"
                self.assertEqual(compile_blueprint(plan, "rough"), original)

        roof = _roof([0, 2], [(0, [1, 3]), (1, [1, 3])])
        plan = _plan([roof])
        original = compile_blueprint(plan, "rough")
        roof["stairs"] = "minecraft:stone_stairs"
        self.assertEqual(compile_blueprint(plan, "rough"), original)

    def test_trimmed_patch_preserves_adjacent_pillar_and_open_edges(self) -> None:
        roof = self._supported_roof([[0, 1, 2, 3, 4]] * 6)
        for section, span in zip(roof["sections"], [[0, 4]] * 4 + [[1, 2], [1, 3]]):
            section["span"] = span
        roof["stairs"] = "minecraft:stone_stairs"
        pillar = _set("minecraft:oak_log", [3, 0, 4])
        operations = compile_blueprint(_plan([pillar, roof]), "rough")
        self.assertEqual(operations[0], {"op": "set", "x": 3, "y": 0, "z": 4, "block": "minecraft:oak_log"})
        self.assertNotIn((3, 4), _roof_layers(operations[1:]))
        self.assertEqual([op for op in operations if ":stone_stairs[" in op["block"]], [{
            "op": "set", "x": 2, "y": 0, "z": z,
            "block": "minecraft:stone_stairs[facing=east,half=bottom,shape=straight]",
        } for z in (1, 2)])

    def test_only_the_top_cell_is_removed_from_an_interpolated_fill(self) -> None:
        roof = _roof([0, 4], [(0, [0, 4]), (2, [0, 4])])
        for section in roof["sections"]:
            section["undersides"] = [-5, -5]
        plan = _plan([roof])
        legacy = compile_blueprint(plan, "rough")
        full_run = {"op": "fill", "from": {"x": 2, "y": -2, "z": 1},
                    "to": {"x": 2, "y": 0, "z": 1}, "block": "minecraft:dark_prismarine"}
        index = legacy.index(full_run)
        roof["stairs"] = "example:carved_stairs"
        operations = compile_blueprint(plan, "rough")
        self.assertEqual(operations, [
            *legacy[:index],
            {**full_run, "to": {"x": 2, "y": -1, "z": 1}},
            {"op": "set", "x": 2, "y": 0, "z": 1,
             "block": "example:carved_stairs[facing=east,half=bottom,shape=straight]"},
            *legacy[index + 1:],
        ])
        self.assertEqual(_roof_layers(operations), _roof_layers(legacy))

    def test_stair_split_counts_against_local_and_repeated_operation_limits(self) -> None:
        roof = self._supported_roof([[1, 2, 3]] * 3)
        plan = _plan([roof])
        legacy_count = len(compile_blueprint(plan, "rough"))
        roof["stairs"] = "minecraft:stone_stairs"
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", legacy_count):
            with self.assertRaisesRegex(BlueprintError, "local operation limit"):
                compile_blueprint(plan, "rough")
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", legacy_count + 1):
            self.assertEqual(len(compile_blueprint(plan, "rough")), legacy_count + 1)
        plan["placements"][0]["repeat"] = {"count": 2, "step": [4, 0, 0]}
        limit = 2 * legacy_count + 1
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", limit):
            with self.assertRaisesRegex(BlueprintError, f"expands beyond {limit} operations"):
                compile_blueprint(plan, "rough")

    def test_rejects_invalid_stair_materials_even_without_eligible_cells(self) -> None:
        roof = _roof([0], [(0, [0])])
        for material, error in (
            (None, "namespaced"), (False, "namespaced"), ("stone_stairs", "namespaced"),
            ("minecraft:stone", "ending in _stairs"), ("minecraft:stone_slab", "ending in _stairs"),
            ("minecraft:stone_stairs[facing=east]", "without properties"),
        ):
            with self.subTest(material=material):
                roof["stairs"] = material
                with self.assertRaisesRegex(BlueprintError, rf"\.stairs must be a .*{error}"):
                    compile_blueprint(_plan([roof]), "rough")


class RoofUndersidesTest(unittest.TestCase):
    def test_explicit_undersides_leave_space_below_and_use_both_slab_halves(self) -> None:
        roof = _roof([0, 1], [(0, [0, 3])])
        self.assertEqual(_roof_layers(compile_blueprint(_plan([roof]), "rough")), {
            (0, 0): {-1}, (1, 0): {-1, 0, 1, 2},
        })

        roof["sections"][0]["undersides"] = [-1, 2]
        stone = _set("minecraft:stone", [1, 0, 0])
        self.assertEqual(compile_blueprint(_plan([stone, roof]), "rough"), [
            {"op": "set", "x": 1, "y": 0, "z": 0, "block": "minecraft:stone"},
            {"op": "set", "x": 0, "y": -1, "z": 0,
             "block": "minecraft:dark_prismarine_slab[type=top]"},
            {"op": "set", "x": 1, "y": 1, "z": 0,
             "block": "minecraft:dark_prismarine_slab[type=bottom]"},
        ])

    def test_bilinear_rounding_and_mirrored_shape_and_endpoints(self) -> None:
        roof = _roof([1, 3], [(2, [1, 4]), (4, [2, 6])], [4, 0, 7])
        roof["sections"][0]["undersides"] = [-5, -2]
        roof["sections"][1]["undersides"] = [-2, 1]
        intervals = {
            (5, 9): (-5, 1), (6, 9): (-3, 3), (7, 9): (-2, 4),
            (5, 10): (-3, 2), (6, 10): (-2, 3), (7, 10): (0, 5),
            (5, 11): (-2, 2), (6, 11): (0, 4), (7, 11): (1, 6),
        }
        plan = _plan([roof])
        self.assertEqual(_roof_layers(compile_blueprint(plan, "rough")), {
            column: set(range(bottom, top)) for column, (bottom, top) in intervals.items()
        })

        for axis, first_x, first_z in (("x", 15, 39), ("z", 25, 21)):
            with self.subTest(axis=axis):
                plan["placements"][0].update(at=[20, 2, 30], mirror=axis)
                operations = compile_blueprint(plan, "rough")
                self.assertEqual(operations[1], {
                    "op": "fill",
                    "from": {"x": first_x, "y": 0, "z": first_z},
                    "to": {"x": first_x, "y": 1, "z": first_z},
                    "block": "minecraft:dark_prismarine",
                })
                self.assertEqual(_roof_layers(operations), {
                    (20 + (-x if axis == "x" else x), 30 + (-z if axis == "z" else z)):
                        set(range(bottom + 4, top + 4))
                    for (x, z), (bottom, top) in intervals.items()
                })

    def test_rejects_invalid_underside_rows(self) -> None:
        for rows, error in (
            (([0, 1], None), "sections must all specify undersides"),
            ((None, [0, 1]), "sections must all specify undersides"),
            (("invalid", [0, 1]), r"\.undersides must be an array"),
            (([0], [0, 1]), r"\.undersides must contain one integer per offset"),
            (([False, 1], [0, 1]), r"\.undersides must contain one integer per offset"),
            (([1, 1], [0, 1]), r"\.undersides must be lower"),
            (([0, 3], [0, 1]), r"\.undersides must be lower"),
        ):
            with self.subTest(rows=rows):
                roof = _roof([0, 1], [(0, [1, 2]), (1, [1, 2])])
                for section, row in zip(roof["sections"], rows):
                    if row is not None:
                        section["undersides"] = row
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(_plan([roof]), "rough")

    def test_coordinate_bounds_apply_to_occupied_blocks_after_roof_offset(self) -> None:
        limit = 2**31
        for at_y, bottom, top, block_y in (
            (1, -2 * limit - 1, -2 * limit, -limit),
            (0, 2 * limit - 1, 2 * limit, limit - 1),
        ):
            with self.subTest(at_y=at_y, bottom=bottom):
                roof = _roof([0], [(0, [top])], [0, at_y, 0])
                roof["sections"][0]["undersides"] = [bottom]
                self.assertEqual(_roof_layers(compile_blueprint(_plan([roof]), "rough")), {
                    (0, 0): {2 * block_y + 1},
                })

        for bottom, top in ((-2 * limit - 1, -2 * limit + 1), (2 * limit - 1, 2 * limit + 1)):
            with self.subTest(bottom=bottom, top=top):
                roof = _roof([0], [(0, [top])])
                roof["sections"][0]["undersides"] = [bottom]
                with self.assertRaisesRegex(BlueprintError, "outside the signed 32-bit coordinate range"):
                    compile_blueprint(_plan([roof]), "rough")

    def test_rejects_excess_area_and_cumulative_visits(self) -> None:
        roof = _roof([0, 1], [(0, [4, 4])])
        roof["sections"][0]["undersides"] = [0, 0]
        plan = _plan([roof])
        with patch("astralarchitect_ticket.blueprint.MAX_TICKET_VOLUME", 1):
            with patch("astralarchitect_ticket.blueprint._roof_axis_samples") as samples:
                with self.assertRaisesRegex(BlueprintError, "expands beyond 1 block visits"):
                    compile_blueprint(plan, "rough")
                samples.assert_not_called()
        with patch("astralarchitect_ticket.blueprint.MAX_TICKET_VOLUME", 3):
            with self.assertRaisesRegex(BlueprintError, "expands beyond 3 block visits"):
                compile_blueprint(plan, "rough")


class RoofSpanTest(unittest.TestCase):
    def test_full_span_matches_legacy_operations(self) -> None:
        for explicit in (False, True):
            with self.subTest(explicit=explicit):
                roof = _roof([-2, 0, 3], [(-1, [-2, 3, 8]), (1, [2, 4, -2])], [4, 10, -5])
                if explicit:
                    for section in roof["sections"]:
                        section["undersides"] = [top - 3 for top in section["heights"]]
                legacy = compile_blueprint(_plan([roof]), "rough")
                for section in roof["sections"]:
                    section["span"] = [-2, 3]
                self.assertEqual(compile_blueprint(_plan([roof]), "rough"), legacy)

    def test_taper_preserves_window_and_pillar_and_keeps_column_order(self) -> None:
        roof = _roof([-2, 2], [(0, [1, 1]), (2, [1, 1])])
        roof["sections"][0]["span"] = [-2, 2]
        roof["sections"][1]["span"] = [0, 0]
        operations = compile_blueprint(_plan([
            _set("minecraft:blue_stained_glass", [2, 0, 1]),
            _set("minecraft:oak_log", [1, 0, 2]),
            roof,
        ]), "rough")
        columns = [(x, z) for z in range(3) for x in range(z - 2, 3 - z)]
        self.assertEqual([(op["x"], op["z"]) for op in operations[2:]], columns)
        self.assertEqual(_roof_layers(operations[2:]), {column: {0} for column in columns})
        blocks = {(op["x"], op["y"], op["z"]): op["block"] for op in operations}
        self.assertEqual(blocks[2, 0, 1], "minecraft:blue_stained_glass")
        self.assertEqual(blocks[1, 0, 2], "minecraft:oak_log")

    def test_span_endpoints_round_ties_toward_positive_x(self) -> None:
        roof = _roof([-3, 3], [(-2, [1, 1]), (0, [1, 1])])
        roof["sections"][0]["span"] = [-2, 2]
        roof["sections"][1]["span"] = [-1, 1]
        self.assertEqual(_roof_layers(compile_blueprint(_plan([roof]), "rough")), {
            (x, z): {0}
            for z, left, right in ((-2, -2, 2), (-1, -1, 2), (0, -1, 1))
            for x in range(left, right + 1)
        })

    def test_implicit_underside_uses_only_occupied_orthogonal_neighbors(self) -> None:
        roof = _roof([0, 1, 2], [(0, [-8, 3, -8]), (1, [5, -8, -8]), (2, [1, 7, -8])])
        for section, span in zip(roof["sections"], ([1, 1], [0, 0], [0, 1])):
            section["span"] = span
        self.assertEqual(_roof_layers(compile_blueprint(_plan([roof]), "rough")), {
            (1, 0): {2}, (0, 1): set(range(5)), (0, 2): {0}, (1, 2): set(range(7)),
        })

    def test_explicit_underside_interpolates_within_the_span(self) -> None:
        roof = _roof([0, 2], [(0, [5, 5]), (2, [5, 5])])
        roof["sections"][0].update(span=[0, 1], undersides=[-2, 0])
        roof["sections"][1].update(span=[1, 2], undersides=[0, 2])
        expected = {(0, 0): -2, (1, 0): -1, (1, 1): 0, (2, 1): 1, (1, 2): 1, (2, 2): 2}
        self.assertEqual(_roof_layers(compile_blueprint(_plan([roof]), "rough")), {
            column: set(range(bottom, 5)) for column, bottom in expected.items()
        })

    def test_mirror_transforms_emitted_columns_in_place(self) -> None:
        roof = _roof([-2, 3], [(0, [1, 1]), (2, [1, 1])], [3, 0, 5])
        roof["sections"][0]["span"] = [-2, 0]
        roof["sections"][1]["span"] = [1, 1]
        plan = _plan([roof])
        operations = compile_blueprint(plan, "rough")
        for axis in ("x", "z"):
            with self.subTest(axis=axis):
                plan["placements"][0]["mirror"] = axis
                self.assertEqual(compile_blueprint(plan, "rough"), [
                    {**operation, axis: -operation[axis]} for operation in operations
                ])

    def test_rejects_invalid_and_missing_spans(self) -> None:
        for span, error in (
            (None, "two integers"), ("0,1", "two integers"), ((0, 1), "two integers"),
            ([], "two integers"), ([0], "two integers"), ([0, 1, 2], "two integers"),
            ([0.0, 1], "two integers"), ([False, 1], "two integers"), ([0, True], "two integers"),
            ([1, 0], "left endpoint"), ([-3, 0], "offset range"), ([0, 3], "offset range"),
        ):
            with self.subTest(span=span):
                roof = _roof([-2, 2], [(0, [1, 1])])
                roof["sections"][0]["span"] = span
                with self.assertRaisesRegex(BlueprintError, error):
                    compile_blueprint(_plan([roof]), "rough")
        for index in (0, 1):
            with self.subTest(index=index):
                roof = _roof([-2, 2], [(0, [1, 1]), (1, [1, 1])])
                roof["sections"][index]["span"] = [0, 0]
                with self.assertRaisesRegex(BlueprintError, "all specify span or all omit"):
                    compile_blueprint(_plan([roof]), "rough")

    def test_remaining_operation_limit_counts_columns_and_runs(self) -> None:
        roof = _roof([0, 5], [(0, [0, 0])])
        roof["sections"][0]["span"] = [2, 2]
        prefix = [_set("minecraft:stone", [0, 0, z]) for z in range(5)]
        with patch("astralarchitect_ticket.blueprint.MAX_OPERATIONS", 6):
            operations = compile_blueprint(_plan([*prefix, roof]), "rough")
            self.assertEqual(len(operations), 6)
            self.assertEqual(_roof_layers(operations[5:]), {(2, 0): {-1}})
            roof["sections"][0]["span"] = [2, 3]
            with self.assertRaisesRegex(BlueprintError, "local operation limit"):
                compile_blueprint(_plan([*prefix, roof]), "rough")
            roof["sections"][0].update(span=[2, 2], undersides=[-3, -3])
            with self.assertRaisesRegex(BlueprintError, "local operation limit"):
                compile_blueprint(_plan([*prefix, roof]), "rough")

    def test_interpolation_rectangle_limits_apply_before_sampling(self) -> None:
        roof = _roof([0, 5], [(0, [0, 0])])
        roof["sections"][0]["span"] = [2, 2]
        for limit, error in (("MAX_OPERATIONS", "local operation limit"),
                             ("MAX_TICKET_VOLUME", "block visits")):
            with self.subTest(limit=limit):
                with patch(f"astralarchitect_ticket.blueprint.{limit}", 5):
                    with patch("astralarchitect_ticket.blueprint._roof_axis_samples") as sample:
                        with self.assertRaisesRegex(BlueprintError, error):
                            compile_blueprint(_plan([roof]), "rough")
                        sample.assert_not_called()


if __name__ == "__main__":
    unittest.main()
