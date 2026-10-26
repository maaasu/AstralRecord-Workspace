import json
import math
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

import numpy as np
from PIL import Image

from terrain_detail import render, render_chunk, unpack_index


def packed(values, bits):
    per_long = 64 // bits
    result = []
    for start in range(0, len(values), per_long):
        value = 0
        for index, item in enumerate(values[start:start + per_long]):
            value |= item << (index * bits)
        result.append(value)
    return tuple(result)


def tag(kind, name, payload):
    name = name.encode()
    return bytes((kind,)) + struct.pack(">H", len(name)) + name + payload


def compound(*children):
    return b"".join(children) + b"\0"


def string(value):
    raw = value.encode()
    return struct.pack(">H", len(raw)) + raw


def longs(values):
    return struct.pack(">i", len(values)) + b"".join(struct.pack(">Q", value) for value in values)


def chunk_nbt():
    names = ("minecraft:grass_block", "minecraft:water")
    palette = b"".join(compound(tag(8, "Name", string(name))) for name in names)
    states = [0] * 4096
    states[1] = 1
    section = compound(
        tag(1, "Y", struct.pack(">b", 0)),
        tag(10, "block_states", compound(
            tag(9, "palette", b"\x0a" + struct.pack(">i", 2) + palette),
            tag(12, "data", longs(packed(states, 4))),
        )),
    )
    heights = packed([1] * 256, 5)
    return b"\x0a\0\0" + compound(
        tag(3, "yPos", struct.pack(">i", 0)),
        tag(9, "sections", b"\x0a" + struct.pack(">i", 1) + section),
        tag(10, "Heightmaps", compound(tag(12, "WORLD_SURFACE", longs(heights)))),
    )


class TerrainDetailTests(unittest.TestCase):
    def test_block_palette_and_heightmap_are_per_block(self):
        self.assertEqual(unpack_index(packed([0, 1, 2, 3] * 64, 4), 3, 4), 3)
        rgba = np.zeros((16, 16, 4), dtype=np.uint8)
        heights = np.zeros((16, 16), dtype=np.int16)
        from terrain_detail import read_chunk_nbt
        render_chunk(read_chunk_nbt(chunk_nbt()), rgba, heights, 0, 0)
        self.assertEqual(tuple(rgba[0, 0]), (105, 155, 78, 255))
        self.assertEqual(tuple(rgba[0, 1]), (50, 104, 174, 255))
        self.assertEqual(int(heights[0, 1]), 0)

    def test_render_keeps_absent_chunk_transparent_and_fingerprints_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            world = base / "testworld"
            region = world / "region"
            region.mkdir(parents=True)
            payload = zlib.compress(chunk_nbt())
            blob = struct.pack(">i", len(payload) + 1) + b"\x02" + payload
            sectors = math.ceil(len(blob) / 4096)
            header = bytearray(8192)
            header[:4] = bytes((0, 0, 2, sectors))
            (region / "r.0.0.mca").write_bytes(header + blob + b"\0" * (sectors * 4096 - len(blob)))
            terrain = base / "terrain.json"
            terrain.write_text(json.dumps({"world": "testworld", "minX": 0, "minZ": 0,
                                           "width": 32, "height": 16, "cellSize": 16,
                                           "cells": [{"x": 0, "z": 0, "status": "ok"},
                                                     {"x": 16, "z": 0, "status": "absent"}]}), encoding="utf-8")
            output = base / "output"
            metadata = render(world, terrain, output)
            self.assertEqual(metadata["blocksPerPixel"], 1)
            self.assertEqual(metadata["width"], 32)
            with Image.open(output / "terrain-detail.png") as image:
                self.assertEqual(image.size, (32, 16))
                self.assertEqual(image.getpixel((16, 0))[3], 0)
                self.assertEqual(image.getpixel((1, 0))[3], 255)
            self.assertEqual(json.loads((output / "terrain-detail.json").read_text())["terrainFingerprint"], metadata["terrainFingerprint"])


if __name__ == "__main__":
    unittest.main()
