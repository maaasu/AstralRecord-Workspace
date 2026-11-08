#!/usr/bin/env python3
"""Generate Celestial Java container atlases from SHA-1-pinned Mojang clients."""
from __future__ import annotations

import argparse
import hashlib
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path
from runpy import run_path
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
THEME = run_path(str(HERE / "generate-gui-panels.py"))
NATIVE_ROOT = ROOT / "java-gui-native"
VANILLA = {
    "1.21.11": "ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd",
    "26.1": "191771837687b766537a8c4607cb6fad79c533a1",
    "26.2": "2dc72797acbc1b63fc16a11c4ac393605f453754",
    "26.3": "e877b6a07acd633fb3bb475002175cec036e7b87",
}
CONTAINERS = Path("assets/minecraft/textures/gui/container")
BOOK = NATIVE_ROOT / "assets/minecraft/textures/gui/sprites/recipe_book/button.png"
FLAT = (198, 198, 198, 255)
SLOT_EDGE = (55, 55, 55, 255)
PAPER = "#c3b18b"
PAPER_EDGE = "#dec597"
MACHINERY = "#a69a81"
LABEL = "#404040"

Box = tuple[int, int, int, int]
Point = tuple[int, int]


@dataclass(frozen=True)
class Screen:
    width: int = 176
    height: int = 166
    label_y: int = 72
    machinery: tuple[Box, ...] = ()
    accents: tuple[Point, ...] = ()
    book: Point | None = None


SCREENS = {
    "generic_54": Screen(height=222, label_y=128),
    "shulker_box": Screen(),
    "crafting_table": Screen(machinery=((28, 18, 147, 72), (5, 30, 25, 54)),
                             accents=((159, 46),), book=(5, 34)),
    "furnace": Screen(machinery=((49, 18, 144, 70), (18, 30, 41, 54)),
                      accents=((155, 46),), book=(20, 34)),
    "blast_furnace": Screen(machinery=((49, 18, 144, 70), (18, 30, 41, 54)),
                            accents=((155, 46),), book=(20, 34)),
    "smoker": Screen(machinery=((49, 18, 144, 70), (18, 30, 41, 54)),
                     accents=((155, 46),), book=(20, 34)),
    "anvil": Screen(machinery=((17, 17, 171, 68),)),
    "smithing": Screen(machinery=((7, 16, 120, 68),), accents=((145, 40),)),
    "brewing_stand": Screen(machinery=((12, 16, 125, 72),), accents=((150, 41),)),
    "enchanting_table": Screen(machinery=((10, 13, 170, 72),)),
    "dispenser": Screen(machinery=((57, 17, 117, 71),), accents=((32, 43), (144, 43))),
    "hopper": Screen(height=133, label_y=39, machinery=((41, 17, 135, 37),),
                     accents=((24, 27), (152, 27))),
    "grindstone": Screen(machinery=((28, 15, 150, 72),), accents=((158, 61),)),
    "cartography_table": Screen(machinery=((10, 13, 168, 72),)),
    "crafter": Screen(machinery=((22, 15, 159, 71),)),
    "loom": Screen(machinery=((5, 11, 170, 71),)),
    "stonecutter": Screen(machinery=((18, 12, 163, 72),)),
    "horse": Screen(machinery=((6, 16, 171, 73),)),
    "nautilus": Screen(machinery=((6, 16, 98, 73),), accents=((128, 44),)),
    "beacon": Screen(230, 219, -1, ((6, 4, 223, 131),)),
    "villager": Screen(276, 166, 72, ((4, 16, 101, 160), (133, 28, 251, 67))),
}
NAMES = {
    "generic_54": "Large Chest", "shulker_box": "Shulker Box",
    "crafting_table": "Crafting", "furnace": "Furnace", "blast_furnace": "Blast Furnace",
    "smoker": "Smoker", "anvil": "Repair & Name", "smithing": "Upgrade Gear",
    "brewing_stand": "Brewing Stand", "enchanting_table": "Enchant", "dispenser": "Dispenser",
    "hopper": "Item Hopper", "grindstone": "Repair & Disenchant",
    "cartography_table": "Cartography Table", "crafter": "Crafter", "loom": "Loom",
    "stonecutter": "Stonecutter", "horse": "Horse", "nautilus": "Nautilus",
    "beacon": "Beacon", "villager": "Trades",
}


def image_from(data: bytes) -> Image.Image:
    with Image.open(BytesIO(data)) as image:
        return image.convert("RGBA")


def frame(spec: Screen) -> Image.Image:
    draw = THEME["Raster"](spec.width, spec.height)
    THEME["frame"](draw, spec.width, spec.height)
    THEME["corners"](draw, spec.width, spec.height)
    THEME["gem"](draw, spec.width // 2, spec.height - 3, 2)
    return image_from(THEME["png"](draw.pixels))


def close_to_edge(x: int, y: int, screen: Screen) -> bool:
    return x < 5 or x >= screen.width - 5 or y < 5 or y >= screen.height - 5


def replace_flat(image: Image.Image, source: Image.Image, box: Box, fill: str) -> None:
    top_left_x, top_left_y, right, bottom = box
    draw = ImageDraw.Draw(image)
    for y in range(top_left_y, bottom + 1):
        for x in range(top_left_x, right + 1):
            if source.getpixel((x, y)) == FLAT:
                draw.point((x, y), fill=fill)


def label_plate(image: Image.Image, source: Image.Image, y: int, width: int, left: int = 6) -> None:
    # Keep the full native text run on a light background and preserve any active widgets.
    replace_flat(image, source, (left, y - 1, width - 7, y + 8), PAPER)
    replace_flat(image, source, (left, y + 9, width - 7, y + 9), PAPER_EDGE)


def slot_pattern(size: int, x: int, y: int) -> tuple[int, int, int, int]:
    if y == 0 and x < size - 1 or x == 0 and y < size - 1:
        return SLOT_EDGE
    if y == size - 1 and x > 0 or x == size - 1 and y > 0:
        return 255, 255, 255, 255
    return 139, 139, 139, 255


def recolor_slots(source: Image.Image, result: Image.Image, screen: Screen) -> int:
    raw = source.load()
    draw = ImageDraw.Draw(result)
    cells = 0
    for y in range(7, screen.height - 17):
        for x in range(5, screen.width - 17):
            if raw[x, y] != SLOT_EDGE or raw[x + 1, y + 1] != (139, 139, 139, 255):
                continue
            for size in (18, 26):
                if x + size > screen.width or y + size > screen.height:
                    continue
                if not all(raw[x + dx, y + dy] == slot_pattern(size, dx, dy)
                           for dy in range(size) for dx in range(size)):
                    continue
                end_x, end_y = x + size - 1, y + size - 1
                draw.rectangle((x, y, end_x, end_y), fill=THEME["CELL_DARK"])
                draw.rectangle((x + 1, y + 1, end_x - 1, end_y - 1), fill=THEME["CELL"])
                draw.line((x + 1, end_y, end_x, end_y), fill=THEME["CELL_LIP"])
                draw.line((end_x, y + 1, end_x, end_y - 1), fill=THEME["CELL_LIP"])
                draw.point((x, y), fill=THEME["GOLD_DARK"])
                draw.point((end_x, end_y), fill=THEME["GOLD"])
                cells += 1
                break
    return cells


def ornament(image: Image.Image, source: Image.Image, center: Point) -> None:
    x, y = center
    art = THEME["Raster"](21, 15)
    THEME["orbit"](art, 10, 7, 9, 5, 0, THEME["GOLD_DARK"])
    THEME["orbit"](art, 10, 7, 9, 3, -0.48, THEME["GOLD"])
    THEME["gem"](art, 10, 7)
    art = image_from(THEME["png"](art.pixels))
    for iy in range(art.height):
        for ix in range(art.width):
            point = x + ix - 10, y + iy - 7
            shade = art.getpixel((ix, iy))
            if shade[3] and source.getpixel(point) == FLAT:
                image.putpixel(point, shade)


def decorate(source: Image.Image, name: str, spec: Screen) -> tuple[Image.Image, int]:
    result = source.copy()
    art = frame(spec)
    for y in range(spec.height):
        for x in range(spec.width):
            vanilla = source.getpixel((x, y))
            if name == "generic_54" and y == 125:
                # The chest GUI never samples row 125 between the two pieces.
                continue
            if vanilla == FLAT or close_to_edge(x, y, spec):
                shade = art.getpixel((x, y))
                if name == "generic_54" and 6 <= y < 137 and (x < 5 or x >= spec.width - 5):
                    shade = art.getpixel((x, 15))
                result.putpixel((x, y), shade)

    for left, top, right, bottom in spec.machinery:
        replace_flat(result, source, (left, top, right, bottom), MACHINERY)

    if name == "anvil":
        # The native widget always paints over this marker at (59, 20).
        # Give any transparent widget pixels a matching neutral backing.
        marker = (255, 0, 0, 255)
        pixels = ImageDraw.Draw(result)
        for y in range(20, 36):
            for x in range(59, 169):
                if source.getpixel((x, y)) == marker:
                    pixels.point((x, y), fill=MACHINERY)

    title_plates = {"anvil": (6, 58), "smithing": (15, 42), "loom": (4, 6),
                    "stonecutter": (5, 6)}
    if name != "beacon":
        y, left = title_plates.get(name, (6, 6))
        label_plate(result, source, y, spec.width, left)
    if spec.label_y >= 0:
        label_plate(result, source, spec.label_y, spec.width, 105 if name == "villager" else 6)

    for center in spec.accents:
        ornament(result, source, center)
    cells = recolor_slots(source, result, spec)

    # Compare the original offscreen regions pixel for pixel, including alpha and atlas UVs.
    if result.size != source.size:
        raise ValueError(f"Invalid atlas size for {name}: {result.size}")
    for box in ((spec.width, 0, source.width, source.height),
                (0, spec.height, spec.width, source.height)):
        if result.crop(box).tobytes() != source.crop(box).tobytes():
            raise ValueError(f"Unused/alternate UVs changed for {name}: {box}")
    return result, cells


def preview(assets: dict[str, Image.Image], anvil_field: Image.Image, path: Path) -> None:
    font = ImageFont.load_default(size=16)
    font_small = ImageFont.load_default(size=11)
    book = image_from(BOOK.read_bytes())
    variants = [
        ("generic_54", rows, f"{rows} {'ROW' if rows == 1 else 'ROWS'} - CHEST / BARREL" if rows < 4 else f"{rows} ROWS - CHEST")
        for rows in (1, 3, 6)
    ]
    variants += [(name, 0, NAMES[name].upper()) for name in SCREENS if name != "generic_54"]
    columns, pitch_x, pitch_y = 4, 300, 252
    height = ((len(variants) + columns - 1) // columns) * pitch_y + 44
    sheet = Image.new("RGB", (columns * pitch_x, height), THEME["INK"])
    print_on = ImageDraw.Draw(sheet)
    print_on.text((12, 12), "ASTRALRECORD  /  JAVA CONTAINER TEXTURE STUDY",
                  font=font, fill=THEME["CREAM"])
    for index, (name, rows, description) in enumerate(variants):
        spec = SCREENS[name]
        panel = assets[name].crop((0, 0, spec.width, spec.height))
        if rows:
            split = 17 + 18 * rows
            crop = Image.new("RGBA", (176, split + 96))
            crop.paste(panel.crop((0, 0, 176, split)), (0, 0))
            crop.paste(panel.crop((0, 126, 176, 222)), (0, split))
            panel = crop
        elif spec.book:
            panel.alpha_composite(book, dest=spec.book)
        if name == "anvil":
            panel.alpha_composite(anvil_field, dest=(59, 20))
        ink = ImageDraw.Draw(panel)
        title = NAMES[name]
        if rows == 1:
            title = "Chest"
        title_positions = {"anvil": (60, 6), "smithing": (44, 15), "loom": (8, 4),
                           "stonecutter": (8, 5), "crafting_table": (29, 6),
                           "villager": (53, 6)}
        if name == "beacon":
            for x, label in ((62, "Primary Power"), (169, "Secondary Power")):
                ink.text((x, 9), label, anchor="mt", font=font_small, fill="#e0e0e0")
        elif name in {"furnace", "blast_furnace", "smoker", "villager"}:
            x, y = title_positions.get(name, (88, 6))
            ink.text((x, y - 1), title, anchor="mt", font=font_small, fill=LABEL)
        else:
            x, y = title_positions.get(name, (8, 6))
            ink.text((x, y - 1), title, font=font_small, fill=LABEL)
        if name == "villager":
            ink.text((187, 5), "Librarian", anchor="mt", font=font_small, fill=LABEL)
        if spec.label_y >= 0:
            label_y = split + 1 if rows else spec.label_y - 1
            ink.text((107 if name == "villager" else 8, label_y),
                     "Inventory", font=font_small, fill=LABEL)
        x, y = (index % columns) * pitch_x + 12, (index // columns) * pitch_y + 57
        print_on.text((x, y - 18), description, font=font_small, fill=THEME["BRIGHT"])
        sheet.paste(panel, (x, y), panel)
    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clients-dir", type=Path, required=True, help="Directory containing the four Mojang client JARs")
    parser.add_argument("--check", action="store_true", help="Verify the tracked atlases without writing them")
    parser.add_argument("--preview", type=Path, help="Optional contact sheet of the generated atlases")
    args = parser.parse_args()
    clients = {}
    versions = {}
    for version, expected_sha in VANILLA.items():
        path = args.clients_dir / f"{version}.jar"
        if not path.is_file():
            path = args.clients_dir / f"AstralRecord-minecraft-client-{version}.jar"
        clients[version] = path
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha1").hexdigest() != expected_sha:
                raise ValueError(f"Client checksum differs: {path}")
        with ZipFile(path) as client:
            versions[version] = {
                name: image_from(client.read((CONTAINERS / f"{name}.png").as_posix())) for name in SCREENS
            }

    assets = {}
    for name, spec in SCREENS.items():
        variants = {version: decorate(images[name], name, spec) for version, images in versions.items()}
        result, cells = variants["1.21.11"]
        for version, (variant, variant_cells) in variants.items():
            if variant.tobytes() != result.tobytes() or variant_cells != cells:
                raise ValueError(f"Client texture requires version overlay: {name} / {version}")
        if cells < 36:
            raise ValueError(f"Missing player slot grid for {name}: detected {cells}")
        assets[name] = result
        print(f"{name}: {result.width}x{result.height}, viewport {spec.width}x{spec.height}, "
              f"{cells} validated slot frames")

    for name, result in assets.items():
        output = NATIVE_ROOT / CONTAINERS / f"{name}.png"
        if args.check:
            with BytesIO() as encoded:
                result.save(encoded, format="PNG", optimize=True)
                if not output.is_file() or output.read_bytes() != encoded.getvalue():
                    raise SystemExit(f"Out-of-date Java container atlas: {output}")
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            result.save(output, optimize=True)

    if args.preview:
        with ZipFile(clients["26.3"]) as client:
            anvil_field = image_from(client.read(
                "assets/minecraft/textures/gui/sprites/container/anvil/text_field_disabled.png"
            ))
        if anvil_field.size != (110, 16):
            raise ValueError(f"Invalid anvil field dimensions: {anvil_field.size}")
        preview(assets, anvil_field, args.preview)


if __name__ == "__main__":
    main()
