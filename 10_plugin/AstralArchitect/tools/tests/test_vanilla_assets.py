"""Focused tests for the vanilla Java block model compiler."""

from __future__ import annotations

import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from zipfile import ZipFile
import zlib

TOOLS_ROOT = Path(__file__).resolve().parents[1]
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

from astralarchitect_ticket.schematic import TicketError
from astralarchitect_ticket.vanilla_assets import VanillaAssets


def _chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(
        ">I", zlib.crc32(kind + payload)
    )


def _png(color: tuple, size: int = 1) -> bytes:
    header = struct.pack(">IIBBBBB", size, size, 8, 6 if len(color) == 4 else 2, 0, 0, 0)
    rows = (bytes((0,)) + bytes(color) * size) * size
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", header)
            + _chunk(b"IDAT", zlib.compress(rows)) + _chunk(b"IEND", b""))


def _asset(jar: ZipFile, name: str, value: dict | bytes) -> None:
    jar.writestr("assets/minecraft/" + name,
                 json.dumps(value) if isinstance(value, dict) else value)


class VanillaAssetTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "client.jar"

    def test_rejects_wrong_minecraft_version(self) -> None:
        with ZipFile(self.path, "w") as jar:
            jar.writestr("version.json", '{"id":"1.21.10"}')
            _asset(jar, "textures/block/part.png", _png((200, 100, 10)))
        with self.assertRaisesRegex(TicketError, r"1\.21\.11"):
            VanillaAssets(self.path)

    def test_inheritance_multipart_rotation_and_stable_texture_selection(self) -> None:
        part_png = _png((200, 100, 10))
        with ZipFile(self.path, "w") as jar:
            jar.writestr("version.json", '{"id":"1.21.11"}')
            _asset(jar, "textures/block/part.png", part_png)
            _asset(jar, "textures/block/../ignored.png", _png((0, 0, 0)))
            _asset(jar, "models/block/template.json", {
                "elements": [{"from": [0, 0, 0], "to": [16, 8, 4],
                              "faces": {"north": {"texture": "#face", "cullface": "north"}}}],
            })
            _asset(jar, "models/block/part.json", {
                "parent": "minecraft:block/template", "textures": {"face": "block/part"},
            })
            _asset(jar, "blockstates/oak_fence.json", {
                "multipart": [
                    {"apply": {"model": "block/part"}},
                    {"when": {"east": "true"}, "apply": [{"model": "block/part", "y": 90}]},
                    {"when": {"west": "true"}, "apply": {"model": "block/part", "y": 270}},
                ],
            })
        assets = VanillaAssets(self.path)
        self.addCleanup(assets.close)
        model = assets.model("minecraft:oak_fence[east=true,west=false]")
        self.assertEqual("opaque", model["layer"])
        self.assertEqual(["north", "east"], [face["cull"] for face in model["faces"]])
        self.assertEqual({1}, {point[0] for point in model["faces"][1]["vertices"]})
        self.assertEqual((0, 0.5), model["faces"][0]["uv"][0])
        self.assertEqual(part_png, assets.texture(model["faces"][0]["texture"]))
        self.assertIsNone(assets.texture(1))
        self.assertIsNone(assets.model("minecraft:water"))

    def test_invalid_dispatch_and_truncated_texture_fall_back(self) -> None:
        with ZipFile(self.path, "w") as jar:
            jar.writestr("version.json", '{"id":"1.21.11"}')
            _asset(jar, "textures/block/good.png", _png((100, 125, 80)))
            _asset(jar, "textures/block/truncated.png", b"\x89PNG\r\n\x1a\n")
            for block, angle, texture in (("bad_rotation", 45, "good"), ("bad_texture", 0, "truncated")):
                _asset(jar, f"models/block/{block}.json", {"elements": [{
                    "from": [0, 0, 0], "to": [16, 16, 16],
                    "faces": {"north": {"texture": f"block/{texture}", "cullface": "north"}},
                }]})
                _asset(jar, f"blockstates/{block}.json", {
                    "variants": {"": {"model": f"block/{block}", "y": angle}},
                })
        assets = VanillaAssets(self.path)
        self.addCleanup(assets.close)
        self.assertIsNone(assets.model("minecraft:bad_rotation"))
        self.assertIsNone(assets.model("minecraft:bad_texture"))

    def test_rotated_grass_plane_uses_cutout_and_plains_color(self) -> None:
        with ZipFile(self.path, "w") as jar:
            jar.writestr("version.json", '{"id":"1.21.11"}')
            _asset(jar, "textures/block/grass.png", _png((200, 100, 10, 0)))
            _asset(jar, "textures/colormap/grass.png", _png((17, 79, 110), 256))
            _asset(jar, "models/block/grass.json", {"elements": [{
                "from": [0.8, 0, 8], "to": [15.2, 16, 8],
                "rotation": {"origin": [8, 8, 8], "y": 45, "rescale": True},
                "shade": False,
                "faces": {"north": {"texture": "block/grass", "tintindex": 0}},
            }]})
            _asset(jar, "blockstates/short_grass.json", {
                "variants": {"": {"model": "minecraft:block/grass"}},
            })
        assets = VanillaAssets(self.path)
        self.addCleanup(assets.close)
        model = assets.model("minecraft:short_grass")
        self.assertEqual("cutout", model["layer"])
        self.assertFalse(model["occludes"])
        face = model["faces"][0]
        self.assertEqual((17, 79, 110), face["tint"])
        self.assertFalse(face["shade"])
        self.assertEqual({0.05, 0.95}, {point[0] for point in face["vertices"]})
        self.assertEqual({0.05, 0.95}, {point[2] for point in face["vertices"]})


if __name__ == "__main__":
    unittest.main()
