"""Render a read-only, one-block-per-pixel surface map from copied Anvil regions."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import re
import struct
import zlib
from pathlib import Path

import numpy as np
from PIL import Image


REGION_NAME = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca$")
U64 = (1 << 64) - 1


class NbtReader:
    """Read only the chunk tags needed for surface rendering."""

    def __init__(self, data: bytes):
        self.data = memoryview(data)
        self.pos = 0

    def take(self, count: int) -> memoryview:
        end = self.pos + count
        if count < 0 or end > len(self.data):
            raise ValueError("truncated NBT")
        result = self.data[self.pos:end]
        self.pos = end
        return result

    def number(self, fmt: str) -> int:
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))[0]

    def string(self) -> str:
        return self.take(self.number(">H")).tobytes().decode("utf-8")

    def payload(self, kind: int, keep: bool = True):
        if kind in (1, 2, 3, 4):
            fmt = {1: ">b", 2: ">h", 3: ">i", 4: ">q"}[kind]
            value = self.number(fmt)
            return value if keep else None
        if kind in (5, 6):
            self.take(4 if kind == 5 else 8)
            return None
        if kind == 8:
            value = self.string()
            return value if keep else None
        if kind in (7, 11, 12):
            count = self.number(">i")
            size = {7: 1, 11: 4, 12: 8}[kind]
            raw = self.take(count * size)
            if not keep:
                return None
            if kind == 12:
                return tuple(value[0] & U64 for value in struct.iter_unpack(">q", raw))
            return raw.tobytes()
        if kind == 9:
            element = self.number(">B")
            count = self.number(">i")
            if count < 0:
                raise ValueError("negative NBT list size")
            return [self.payload(element, keep) for _ in range(count)] if keep else self.skip_list(element, count)
        if kind == 10:
            result = {}
            while (child := self.number(">B")) != 0:
                name = self.string()
                selected = keep and name in {"sections", "Y", "block_states", "palette", "Name", "data", "Heightmaps", "WORLD_SURFACE", "yPos"}
                value = self.payload(child, selected)
                if selected:
                    result[name] = value
            return result if keep else None
        raise ValueError(f"unsupported NBT tag {kind}")

    def skip_list(self, element: int, count: int):
        for _ in range(count):
            self.payload(element, False)
        return None


def read_chunk_nbt(raw: bytes) -> dict:
    reader = NbtReader(raw)
    if reader.number(">B") != 10:
        raise ValueError("chunk NBT root must be a compound")
    reader.string()
    return reader.payload(10)


def unpack_index(data: tuple[int, ...], index: int, bits: int) -> int:
    per_long = 64 // bits
    return (data[index // per_long] >> ((index % per_long) * bits)) & ((1 << bits) - 1)


def color(block: str) -> tuple[int, int, int]:
    name = block.removeprefix("minecraft:")
    if name in ("water", "bubble_column") or name.endswith("_water"):
        return (50, 104, 174)
    if name in ("lava", "magma_block"):
        return (230, 103, 35)
    if name in ("grass_block", "short_grass", "tall_grass", "fern", "large_fern", "moss_block"):
        return (105, 155, 78)
    if "leaves" in name or name in ("vine", "azalea", "flowering_azalea"):
        return (60, 118, 55) if "spruce" not in name else (62, 103, 73)
    if name in ("snow", "snow_block", "powder_snow") or name.endswith("_ice") or name in ("ice", "packed_ice", "blue_ice"):
        return (219, 229, 227)
    if name in ("sand", "sandstone", "smooth_sandstone", "cut_sandstone"):
        return (217, 203, 146)
    if name in ("red_sand", "red_sandstone"):
        return (184, 103, 64)
    if name in ("dirt", "coarse_dirt", "rooted_dirt", "farmland", "mud"):
        return (131, 102, 72)
    if name in ("dirt_path", "gravel"):
        return (151, 139, 106)
    if name in ("stone", "andesite", "tuff", "calcite", "cobblestone") or "stone" in name or name in ("deepslate", "bedrock"):
        return (131, 132, 130)
    if "log" in name or "wood" in name or "planks" in name:
        return (118, 91, 61)
    if name in ("clay",):
        return (157, 166, 172)
    if name in ("netherrack", "crimson_nylium"):
        return (141, 74, 67)
    if "terracotta" in name:
        return (172, 119, 91)
    if name in ("air", "cave_air", "void_air"):
        return (0, 0, 0)
    return (139, 137, 122)


def read_region_chunks(path: Path):
    match = REGION_NAME.fullmatch(path.name)
    if match is None:
        return
    region_x, region_z = map(int, match.groups())
    with path.open("rb") as source:
        locations = source.read(4096)
        if len(locations) != 4096:
            raise ValueError(f"truncated MCA header: {path}")
        for index in range(1024):
            offset = int.from_bytes(locations[index * 4:index * 4 + 3], "big")
            sectors = locations[index * 4 + 3]
            if not offset or not sectors:
                continue
            chunk_x = region_x * 32 + index % 32
            chunk_z = region_z * 32 + index // 32
            yield chunk_x, chunk_z, source, offset, sectors


def load_chunk(source, offset: int, sectors: int) -> dict:
    source.seek(offset * 4096)
    length = int.from_bytes(source.read(4), "big")
    if length < 2 or length > sectors * 4096 - 4:
        raise ValueError("invalid MCA chunk length")
    compression = source.read(1)
    payload = source.read(length - 1)
    if len(payload) != length - 1:
        raise ValueError("truncated MCA chunk")
    if compression == b"\x02":
        raw = zlib.decompress(payload)
    elif compression == b"\x01":
        raw = gzip.decompress(payload)
    elif compression == b"\x03":
        raw = payload
    else:
        raise ValueError(f"unsupported MCA compression: {compression!r}")
    return read_chunk_nbt(raw)


def render_chunk(chunk: dict, rgba: np.ndarray, heights: np.ndarray, x0: int, z0: int):
    sections = {}
    for section in chunk.get("sections", []):
        if "Y" in section and "block_states" in section:
            state = section["block_states"]
            palette = [item["Name"] for item in state.get("palette", [])]
            if palette:
                sections[section["Y"]] = (palette, state.get("data", ()))
    if not sections:
        raise ValueError("no block sections")
    bottom = chunk.get("yPos", min(sections)) * 16
    heightmap = chunk.get("Heightmaps", {}).get("WORLD_SURFACE")
    if not heightmap:
        raise ValueError("WORLD_SURFACE heightmap missing")
    possible_bits = [bits for bits in range(1, 17) if math.ceil(256 / (64 // bits)) == len(heightmap)]
    if len(possible_bits) != 1:
        raise ValueError("WORLD_SURFACE heightmap has an unknown packing width")
    bits = possible_bits[0]
    palette_colors = {y: [color(name) for name in palette] for y, (palette, _) in sections.items()}
    for z in range(16):
        for x in range(16):
            index = z * 16 + x
            y = bottom + unpack_index(heightmap, index, bits) - 1
            section_y = y // 16
            if section_y not in sections:
                continue
            palette, data = sections[section_y]
            block_index = (y % 16) * 256 + z * 16 + x
            palette_index = unpack_index(data, block_index, max(4, (len(palette) - 1).bit_length())) if data else 0
            if palette_index >= len(palette) or palette[palette_index] in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
                continue
            rgb = palette_colors[section_y][palette_index]
            rgba[z0 + z, x0 + x] = (*rgb, 255)
            heights[z0 + z, x0 + x] = y


def render(world: Path, terrain: Path, output: Path) -> dict:
    raw = terrain.read_bytes()
    fingerprint = hashlib.sha256(raw).hexdigest()
    source = json.loads(raw)
    min_x, min_z = source["minX"], source["minZ"]
    width, height = source["width"], source["height"]
    if source.get("cellSize") != 16 or min_x % 16 or min_z % 16 or width % 16 or height % 16 or not 0 < width <= 8192 or not 0 < height <= 8192:
        raise ValueError("terrain bounds must be chunk-aligned and at most 8192 blocks per dimension")
    if world.name != source.get("world"):
        raise ValueError("world folder does not match terrain.world")
    ok = {(cell["x"] // 16, cell["z"] // 16) for cell in source["cells"] if cell["status"] == "ok"}
    rgba = np.zeros((height, width, 4), dtype=np.uint8)
    heights = np.zeros((height, width), dtype=np.int16)
    rendered = 0
    failures = []
    for region in sorted((world / "region").glob("r.*.*.mca")):
        for cx, cz, stream, offset, sectors in read_region_chunks(region):
            if (cx, cz) not in ok:
                continue
            x0, z0 = cx * 16 - min_x, cz * 16 - min_z
            if x0 < 0 or z0 < 0 or x0 + 16 > width or z0 + 16 > height:
                continue
            try:
                render_chunk(load_chunk(stream, offset, sectors), rgba, heights, x0, z0)
                rendered += 1
            except (OSError, ValueError, IndexError, KeyError, TypeError) as error:
                rgba[z0:z0 + 16, x0:x0 + 16] = 0
                heights[z0:z0 + 16, x0:x0 + 16] = 0
                failures.append(f"{cx},{cz}: {error}")
            if rendered % 10000 == 0 and rendered:
                print(f"rendered {rendered} chunks", flush=True)
    # Use a north-west light source; normalize slopes while preserving material color.
    valid = rgba[:, :, 3] != 0
    north = np.empty_like(heights)
    west = np.empty_like(heights)
    north[0] = heights[0]
    north[1:] = heights[:-1]
    west[:, 0] = heights[:, 0]
    west[:, 1:] = heights[:, :-1]
    north[~np.vstack((valid[0:1], valid[:-1]))] = heights[~np.vstack((valid[0:1], valid[:-1]))]
    west[~np.hstack((valid[:, 0:1], valid[:, :-1]))] = heights[~np.hstack((valid[:, 0:1], valid[:, :-1]))]
    slope = np.clip((heights.astype(np.int32) - north.astype(np.int32)) + (heights.astype(np.int32) - west.astype(np.int32)), -8, 8)
    shade = (1.0 + slope * 0.035).astype(np.float32)
    rgba[:, :, :3] = np.clip(rgba[:, :, :3].astype(np.float32) * shade[:, :, None], 0, 255).astype(np.uint8)
    rgba[~valid] = 0
    output.mkdir(parents=True, exist_ok=True)
    image = output / "terrain-detail.png"
    pending_image = output / "terrain-detail.png.part"
    Image.fromarray(rgba).save(pending_image, format="PNG", optimize=True)
    pending_image.replace(image)
    with image.open("rb") as image_stream:
        image_sha256 = hashlib.file_digest(image_stream, "sha256").hexdigest()
    metadata = {"schemaVersion": 1, "terrainFingerprint": fingerprint, "minX": min_x, "minZ": min_z,
                "width": width, "height": height, "blocksPerPixel": 1, "image": image.name,
                "imageSha256": image_sha256}
    pending_metadata = output / "terrain-detail.json.part"
    pending_metadata.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    pending_metadata.replace(output / "terrain-detail.json")
    print(f"rendered chunks: {rendered}; failed chunks: {len(failures)}; unrendered known chunks: {len(ok) - rendered}")
    for failure in failures[:20]:
        print(f"warning: {failure}")
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True, help="copied read-only world directory")
    parser.add_argument("--terrain", type=Path, required=True, help="completed continent-terrain.json")
    parser.add_argument("--output", type=Path, required=True, help="output directory")
    args = parser.parse_args()
    render(args.world, args.terrain, args.output)


if __name__ == "__main__":
    main()
