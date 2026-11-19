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
            ({"op": "replace"}, "must be set, fill, or line"),
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


if __name__ == "__main__":
    unittest.main()
