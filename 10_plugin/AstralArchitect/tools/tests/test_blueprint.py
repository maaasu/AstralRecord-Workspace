from __future__ import annotations

import copy
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



if __name__ == "__main__":
    unittest.main()
