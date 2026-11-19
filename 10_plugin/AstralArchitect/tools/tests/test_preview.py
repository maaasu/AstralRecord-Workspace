"""Behavioral checks for the camera used by the ticket preview."""

from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from astralarchitect_ticket import cli, preview
from astralarchitect_ticket.preview import render_preview
from astralarchitect_ticket.schematic import Bounds, Point, TicketError


def _pixel(png: bytes) -> tuple[int, ...]:
    """Decode the single pixel in a test render and verify each chunk's CRC."""
    if png[:8] != b"\x89PNG\r\n\x1a\n":
        raise AssertionError("missing PNG signature")
    offset = 8
    image_data = bytearray()
    while offset < len(png):
        length, kind = struct.unpack_from(">I4s", png, offset)
        chunk = png[offset + 8:offset + 8 + length]
        crc, = struct.unpack_from(">I", png, offset + 8 + length)
        if crc != zlib.crc32(chunk, zlib.crc32(kind)):
            raise AssertionError("invalid PNG chunk CRC")
        if kind == b"IHDR" and struct.unpack(">IIBBBBB", chunk) != (1, 1, 8, 2, 0, 0, 0):
            raise AssertionError("expected a 1x1 RGB PNG")
        if kind == b"IDAT":
            image_data.extend(chunk)
        offset += 12 + length
    filter_type, red, green, blue = zlib.decompress(image_data)
    if filter_type != 0:
        raise AssertionError("expected an unfiltered PNG row")
    return red, green, blue


class PreviewCameraTests(unittest.TestCase):
    def test_world_coordinate_camera_and_occlusion(self) -> None:
        ticket = SimpleNamespace(bounds=Bounds(Point(-5, 62, 10), Point(-5, 62, 12)))
        blocks = ["minecraft:red_wool", "minecraft:air", "minecraft:blue_wool"]
        front = _pixel(render_preview(ticket, blocks, eye=(-4.5, 62.5, 6),
                                      target=(-4.5, 62.5, 11), width=1, height=1))
        back = _pixel(render_preview(ticket, blocks, eye=(-4.5, 62.5, 17),
                                     target=(-4.5, 62.5, 11), width=1, height=1))
        self.assertGreater(front[0], front[2])
        self.assertGreater(back[2], back[0])

        blocks[0] = "minecraft:glass"
        glass = _pixel(render_preview(ticket, blocks, eye=(-4.5, 62.5, 6),
                                      target=(-4.5, 62.5, 11), width=1, height=1))
        self.assertGreater(glass[2], glass[0])
        self.assertLess(glass[0], 100)

    def test_camera_cut_reveals_lower_layer(self) -> None:
        ticket = SimpleNamespace(bounds=Bounds(Point(0, 62, 0), Point(0, 63, 0)))
        blocks = ["minecraft:red_wool", "minecraft:blue_wool"]
        camera = dict(eye=(0.5, 75, 0.5), target=(0.5, 60, 0.5), width=1, height=1)
        above = _pixel(render_preview(ticket, blocks, **camera))
        below = _pixel(render_preview(ticket, blocks, clip_y=62, **camera))
        self.assertGreater(above[2], above[0])
        self.assertGreater(below[0], below[2])
        self.assertEqual(blocks, ["minecraft:red_wool", "minecraft:blue_wool"])

    def test_slab_and_stair_block_states_change_visible_geometry(self) -> None:
        ticket = SimpleNamespace(bounds=Bounds(Point(0, 0, 0), Point(0, 0, 0)))
        slab_camera = dict(eye=(0.75, 0.25, -2), target=(0.75, 0.25, 0.5), width=1, height=1)
        stair_camera = dict(eye=(0.75, 0.75, -2), target=(0.75, 0.75, 0.5), width=1, height=1)
        sky = _pixel(render_preview(ticket, ["minecraft:cave_air"], **slab_camera))
        for camera, visible, hidden in (
            (slab_camera, "stone_slab[type=bottom]", "stone_slab[type=top]"),
            (stair_camera, "stone_stairs[facing=east,half=bottom,shape=straight]",
             "stone_stairs[facing=west,half=bottom,shape=straight]"),
        ):
            with self.subTest(visible=visible, hidden=hidden):
                image = _pixel(render_preview(ticket, ["minecraft:" + visible], **camera))
                self.assertNotEqual(image, sky)
                image = _pixel(render_preview(ticket, ["minecraft:" + hidden], **camera))
                self.assertEqual(image, sky)

    def test_clay_removes_material_color_and_pattern(self) -> None:
        ticket = SimpleNamespace(bounds=Bounds(Point(0, 0, 0), Point(0, 0, 0)))
        camera = dict(eye=(2, 2, -3), target=(0.5, 0.5, 0.5), width=16, height=16)
        wood = ["minecraft:oak_planks"]
        brick = ["minecraft:bricks"]
        material = render_preview(ticket, wood, **camera)
        self.assertNotEqual(material, render_preview(ticket, brick, **camera))
        self.assertEqual(render_preview(ticket, wood, style="clay", **camera),
                         render_preview(ticket, brick, style="clay", **camera))
        self.assertEqual(render_preview(ticket, wood, style="material", **camera), material)
        with self.assertRaisesRegex(TicketError, "preview style"):
            render_preview(ticket, wood, style="invalid", **camera)

    def test_clay_preserves_slab_open_space_and_glass_opacity(self) -> None:
        ticket = SimpleNamespace(bounds=Bounds(Point(0, 0, 0), Point(0, 0, 0)))
        slab = ["minecraft:stone_slab[type=bottom]"]
        for height in (0.25, 0.75):
            with self.subTest(height=height):
                camera = dict(eye=(0.5, height, -2), target=(0.5, height, 0.5), width=1, height=1)
                air = _pixel(render_preview(ticket, ["minecraft:cave_air"], style="clay", **camera))
                solid = _pixel(render_preview(ticket, ["minecraft:stone"], style="clay", **camera))
                half = _pixel(render_preview(ticket, slab, style="clay", **camera))
                glass = _pixel(render_preview(ticket, ["minecraft:glass"], style="clay", **camera))
                self.assertLess(solid[0], air[0])
                self.assertEqual(half, solid if height < 0.5 else air)
                self.assertEqual(glass, tuple(int(0.34 * face + (1.0 - 0.34) * sky)
                                              for face, sky in zip(solid, air)))

    def test_preview_cli_forwards_and_reports_clay_style(self) -> None:
        command = ["preview", "ticket", "--eye", "0", "1", "2", "--target", "0", "0", "0",
                   "--width", "1", "--height", "1", "--output", "preview.png"]
        states = ["minecraft:stone"]
        ticket = SimpleNamespace(directory=Path("ticket"), metadata={"id": "test"},
                                 source_hash="source", candidate_hash="candidate",
                                 candidate=SimpleNamespace(states=lambda: states))
        for style in ("material", "clay"):
            with self.subTest(style=style):
                options = ["--style", "clay"] if style == "clay" else []
                args = cli._parser().parse_args(command + options)
                self.assertEqual(args.command, "preview")
                with patch.object(cli, "render_preview", return_value=b"png") as render, \
                     patch.object(cli, "_output_path", return_value=Path("preview.png")), \
                     patch.object(cli, "_write_new_file"):
                    result = cli._preview(ticket, args)
                render.assert_called_once_with(ticket, states, eye=(0.0, 1.0, 2.0),
                                               target=(0.0, 0.0, 0.0), width=1, height=1,
                                               clip_y=None, style=style)
                if style == "clay":
                    self.assertEqual(result["style"], "clay")
                else:
                    self.assertNotIn("style", result)

    def test_preview_limits_empty_cell_traversal(self) -> None:
        ticket = SimpleNamespace(bounds=Bounds(Point(0, 0, 0), Point(0, 0, 2)))
        blocks = ["minecraft:air"] * 3
        with patch.object(preview, "_MAX_CELL_VISITS", 2):
            with self.assertRaisesRegex(TicketError, "rendering budget"):
                render_preview(ticket, blocks, eye=(0.5, 0.5, -2),
                               target=(0.5, 0.5, 0.5), width=1, height=1)

    def test_bad_view_raises_ticket_error(self) -> None:
        ticket = SimpleNamespace(bounds=Bounds(Point(0, 0, 0), Point(0, 0, 0)))
        blocks = ["minecraft:stone"]
        with self.assertRaisesRegex(TicketError, "distinct"):
            render_preview(ticket, blocks, eye=(0, 1, 2), target=(0, 1, 2), width=1, height=1)
        with self.assertRaisesRegex(TicketError, "finite"):
            render_preview(ticket, blocks, eye=(0, 1, 2), target=(float("nan"), 1, 2), width=1, height=1)


if __name__ == "__main__":
    unittest.main()
