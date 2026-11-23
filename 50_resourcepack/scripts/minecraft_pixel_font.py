"""Build the quest typeface from the Java 1.21.11 default ASCII and Japanese Unihex glyphs."""

import hashlib
import io
import json
import zipfile
from pathlib import Path

from PIL import Image


CLIENT_SHA1 = "ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd"
OBJECTS = {
    "minecraft:font/include/unifont.json": "402ded0eebd448033ef415e861a17513075f80e7",
    "minecraft:font/unifont.zip": "ccd5ac4767ce0a9c71d1dd62f2dc25449789b5dd",
    "minecraft:font/unifont_jp.zip": "590470ab0f17afb73a4e41d9cb56fdbe069d275a",
}
ASCII_FILE = "minecraft:font/ascii.png"
ASCII_ADVANCES_SHA256 = "bc757638f1a2e46edc0781f327cc8a21e8542c9860f98383abd7ca145b4b5060"
SPACE = {32: 4, 0x3000: 8}
PIXEL_ASCENT = -3840


def resource(assets_dir, name):
    digest = OBJECTS[name]
    data = (assets_dir / "objects" / digest[:2] / digest).read_bytes()
    if hashlib.sha1(data).hexdigest() != digest:
        raise ValueError(f"公式アセットのSHA-1が一致しません: {name}")
    return data


def ascii_source(client_jar):
    with client_jar.open("rb") as file:
        if hashlib.file_digest(file, "sha1").hexdigest() != CLIENT_SHA1:
            raise ValueError("Minecraft Java 1.21.11 の公式 client.jar を指定してください")
    with zipfile.ZipFile(client_jar) as archive:
        providers = json.loads(archive.read("assets/minecraft/font/include/default.json"))["providers"]
        chars = next(p["chars"] for p in providers if p.get("file") == ASCII_FILE)
        mask = Image.open(io.BytesIO(archive.read("assets/minecraft/textures/font/ascii.png"))).convert("RGBA").getchannel("A")
    return chars, mask


def ascii_cells(chars, mask, wanted):
    glyphs = {}
    for y, line in enumerate(chars):
        for x, ch in enumerate(line):
            cp = ord(ch)
            if 32 < cp < 127 and cp in wanted:
                tile = mask.crop((x * 8, y * 8, x * 8 + 8, y * 8 + 8))
                glyphs[cp] = tile.resize((16, 16), Image.Resampling.NEAREST)
    return glyphs


def unihex_mask(encoded, codepoint, overrides):
    digits = len(encoded) // 16
    width = digits * 4
    rows = [int(encoded[y * digits:(y + 1) * digits], 16) for y in range(16)]
    ink = 0
    for row in rows:
        ink |= row
    bounds = next(((left, right) for start, end, left, right in overrides if start <= codepoint <= end), None)
    if bounds is None:
        bounds = (width - ink.bit_length(), width - (ink & -ink).bit_length()) if ink else (0, width - 1)
    left, right = bounds
    if right - left + 1 > 16:
        return None
    pixels = bytes(255 if x + left <= min(right, width - 1) and row & (1 << (width - x - left - 1)) else 0
                   for row in rows for x in range(16))
    return Image.frombytes("L", (16, 16), pixels)


def load_glyphs(client_jar: Path, assets_dir: Path, needed: set[int]):
    """Return 16×16 binary masks; absent/oversize characters use the caller's fallback."""
    glyphs = {cp: Image.new("L", (16, 16)) for cp in needed.intersection(SPACE)}
    glyphs.update(ascii_cells(*ascii_source(client_jar), needed))
    remaining = needed - glyphs.keys()
    for provider in json.loads(resource(assets_dir, "minecraft:font/include/unifont.json"))["providers"]:
        if provider["type"] != "unihex" or any(k != "jp" or not v for k, v in provider.get("filter", {}).items()):
            continue
        rules = [(ord(r["from"]), ord(r["to"]), r["left"], r["right"]) for r in provider.get("size_overrides", ())]
        with zipfile.ZipFile(io.BytesIO(resource(assets_dir, provider["hex_file"]))) as archive:
            with archive.open(next(n for n in archive.namelist() if n.endswith(".hex"))) as font:
                for line in font:
                    cp, data = line.strip().split(b":")
                    cp = int(cp, 16)
                    if cp in remaining:
                        remaining.remove(cp)
                        tile = unihex_mask(data, cp, rules)
                        if tile is not None and tile.getbbox():
                            glyphs[cp] = tile
    return glyphs


def bitmap_advance(tile):
    # Vanilla BitmapProvider rounds height/cellHeight * rightmost ink before adding one.
    return (tile.getbbox()[2] + 1) // 2 + 1


def check_assets(pack, client_jar=None):
    """Verify all packed masks and their cursor cancellation without needing a game installation."""
    base = pack / "assets/astralrecord"
    font = json.loads((base / "font/quest_hud_pixel_text.json").read_text(encoding="utf-8"))
    spaces, ascii_bitmap, jp_bitmap = font["providers"]
    assert spaces == {"type": "space", "advances": {chr(cp): n for cp, n in SPACE.items()}}
    assert ascii_bitmap["file"] == ASCII_FILE and all(p["height"] == 8 and p["ascent"] == PIXEL_ASCENT
                                                     for p in (ascii_bitmap, jp_bitmap))
    assert jp_bitmap["file"] == "astralrecord:font/quest_hud_pixel_text.png"
    path = pack.parent / "10_plugin/AstralRecord/src/main/resources/quest-hud-pixel-advances.txt"
    advances = {int(cp, 16): int(step) for cp, step in map(str.split, path.read_text().splitlines())}
    ascii_codes = {ord(ch) for line in ascii_bitmap["chars"] for ch in line if ch != "\0"}
    measured = dict(SPACE)
    if client_jar:
        chars, mask = ascii_source(client_jar)
        assert [[(i, c) for i, c in enumerate(row) if ord(c) in ascii_codes] for row in chars] == [
            [(i, c) for i, c in enumerate(row) if c != "\0"] for row in ascii_bitmap["chars"]]
        measured.update({cp: bitmap_advance(tile) for cp, tile in ascii_cells(chars, mask, ascii_codes).items()})
    else:
        # Verify the pinned measurements even when the local client jar is unavailable.
        latin_metrics = "".join(f"{cp:04X} {advances[cp]}\n" for cp in sorted(ascii_codes))
        assert hashlib.sha256(latin_metrics.encode("ascii")).hexdigest() == ASCII_ADVANCES_SHA256
        measured.update({cp: advances[cp] for cp in ascii_codes})
    rows = jp_bitmap["chars"]
    with Image.open(base / "textures/font/quest_hud_pixel_text.png") as atlas:
        mask = atlas.getchannel("A")
        assert len(rows) and atlas.size == (len(rows[0]) * 16, len(rows) * 16)
        assert all(len(row) == len(rows[0]) for row in rows)
        assert sum(mask.histogram()[1:255]) == 0, "輪郭に中間透明度の画素があります"
        for y, row in enumerate(rows):
            for x, c in enumerate(row):
                if c != "\0":
                    assert ord(c) not in measured, f"Duplicate glyph: {c}"
                    cell = mask.crop((x * 16, y * 16, x * 16 + 16, y * 16 + 16))
                    measured[ord(c)] = bitmap_advance(cell)
    assert measured == advances and all(0 < step <= 11 for step in measured.values())
    print(f"Quest pixel font: {len(measured)} advances, binary ink, max advance {max(measured.values())}.")


def generate(pack, client_jar, assets_dir, verify=False):
    base = pack / "assets/astralrecord"
    reference = json.loads((base / "font/quest_hud.json").read_text(encoding="utf-8"))
    old_rows = next(p["chars"] for p in reference["providers"] if p.get("file", "").endswith("quest_hud_text_hd.png"))
    needed = {ord(ch) for row in old_rows for ch in row if ch != "\0"} | set(SPACE)
    glyphs = load_glyphs(client_jar, assets_dir, needed)
    missing = needed - glyphs.keys()
    if missing:
        raise ValueError("元の文字集合を再現できません: " + " ".join(f"U+{cp:04X}" for cp in sorted(missing)))
    ascii_rows, _ = ascii_source(client_jar)
    ascii_rows = ["".join(ch if 32 < ord(ch) < 127 and ord(ch) in needed else "\0" for ch in row)
                  for row in ascii_rows]
    cps = sorted(cp for cp in needed if cp not in SPACE and not 32 < cp < 127)
    columns = 64
    rows = ["".join(map(chr, cps[i:i+columns])).ljust(columns, "\0") for i in range(0, len(cps), columns)]
    mask = Image.new("L", (16 * columns, 16 * len(rows)))
    for i, cp in enumerate(cps):
        mask.paste(glyphs[cp], (i % columns * 16, i // columns * 16))
    atlas = Image.new("RGBA", mask.size, "white")
    atlas.putalpha(mask)
    image_out = io.BytesIO()
    atlas.save(image_out, format="PNG", optimize=True)
    providers = [
        {"type": "space", "advances": {chr(cp): width for cp, width in SPACE.items()}},
        {"type": "bitmap", "file": ASCII_FILE, "height": 8, "ascent": PIXEL_ASCENT, "chars": ascii_rows},
        {"type": "bitmap", "file": "astralrecord:font/quest_hud_pixel_text.png", "height": 8,
         "ascent": PIXEL_ASCENT, "chars": rows},
    ]
    metrics = {cp: SPACE[cp] if cp in SPACE else bitmap_advance(glyphs[cp]) for cp in sorted(needed)}
    assert max(metrics.values()) <= 11 and metrics[ord("1")] == 6
    with zipfile.ZipFile(io.BytesIO(resource(assets_dir, "minecraft:font/unifont.zip"))) as archive:
        license_text = archive.read("LICENSE.txt").decode("utf-8").replace("\r\n", "\n")
    result = {
        base / "font/quest_hud_pixel_text.json": json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n",
        base / "textures/font/quest_hud_pixel_text.png": image_out.getvalue(),
        pack.parent / "10_plugin/AstralRecord/src/main/resources/quest-hud-pixel-advances.txt":
            "".join(f"{cp:04X} {width}\n" for cp, width in metrics.items()),
        pack / "licenses/LICENSE-Unifont-17.0.01.txt": license_text,
    }
    for path, content in result.items():
        if verify:
            if isinstance(content, str):
                assert path.read_text(encoding="utf-8") == content, f"Stale {path}"
            else:
                with Image.open(path) as stored:
                    assert stored.size == atlas.size and stored.convert("RGBA").tobytes() == atlas.tobytes(), path
        elif isinstance(content, str):
            path.write_text(content, encoding="utf-8", newline="\n")
        else:
            path.write_bytes(content)
    return metrics
