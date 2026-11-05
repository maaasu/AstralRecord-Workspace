from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
JAVA_TEXTURE = ROOT / "assets/astralrecord/textures/font/weapon_attack.png"
BEDROCK_TEXTURES = ROOT / "bedrock/textures/entity/weapon_attack"

PALETTE = {
    ".": (0, 0, 0, 0),
    "w": (242, 249, 255, 255),
    "c": (151, 224, 255, 255),
    "b": (74, 159, 224, 255),
    "s": (103, 86, 72, 255),
    "g": (244, 184, 76, 255),
    "y": (255, 225, 134, 255),
    "o": (224, 126, 55, 255),
    "t": (120, 211, 190, 255),
    "a": (184, 237, 218, 255),
    "p": (185, 153, 238, 255),
    "v": (119, 103, 205, 255),
    "d": (79, 67, 142, 255),
}

# Hand-authored 16px pixel sprites. Dot rows stay transparent; the restrained
# outlines and two-step highlights preserve the same silhouettes in both packs.
SPRITES = {
    "sword": [
        "................",
        "...........ww...",
        "..........wccw..",
        ".........wcc....",
        ".......wwc......",
        ".....wwc........",
        "...wwcc.........",
        "..wcc...........",
        ".wcc............",
        ".wcc............",
        "..wcc...........",
        "...wwcc.........",
        ".....wwc........",
        ".......wwc......",
        ".........wcc....",
        "...........ww...",
    ],
    "hammer": [
        "................",
        "................",
        "..ooyyyyoo......",
        ".oyyggggyyyo....",
        ".oygggggggyo....",
        "..ooyyyyoo......",
        ".....s..........",
        ".....ss.........",
        "......s.........",
        "......s.........",
        "......ss........",
        ".......s........",
        ".......s........",
        "......ss........",
        "................",
        "................",
    ],
    "spear": [
        "........w.......",
        ".......wt.......",
        "......wtt.......",
        ".....wtta.......",
        "....wtta........",
        "...wtta.........",
        "....s...........",
        "....s...........",
        ".....s..........",
        ".....s..........",
        "......s.........",
        "......s.........",
        ".......s........",
        ".......s........",
        "........s.......",
        "................",
    ],
    "bow": [
        "......yyy.......",
        ".....y...y......",
        "....y....y......",
        "...y.....y......",
        "..y......y......",
        ".y.......y......",
        ".y........y.....",
        ".y.........y....",
        ".y..........y...",
        ".y.........y....",
        ".y........y.....",
        ".y.......y......",
        "..y......y......",
        "...y....y.......",
        "....y..y........",
        ".....yy.........",
    ],
    "wand": [
        "................",
        ".......p........",
        "......ppp.......",
        ".......p........",
        "....p..p..p.....",
        ".....p...p......",
        "......p.p.......",
        ".......p........",
        "......p.........",
        ".....ps.........",
        "....ps..........",
        "...ps...........",
        "..ps............",
        ".ps.............",
        "................",
        "................",
    ],
    "staff": [
        ".......y........",
        "......yvy.......",
        ".....yvvvy......",
        "......yvy.......",
        ".......y........",
        "....p..d..p.....",
        ".....p.d.p......",
        "......pdp.......",
        ".......d........",
        "......dv........",
        ".....d.v........",
        "....d..v........",
        "...d...v........",
        "..d....v........",
        ".d.....v........",
        "................",
    ],
}


def render(name: str, rows: list[str]) -> bytes:
    if len(rows) != 16 or any(len(row) != 16 for row in rows):
        raise ValueError(f"{name} must be exactly 16x16")
    pixels = bytearray()
    for y, row in enumerate(rows):
        for x, symbol in enumerate(row):
            pixels.extend(PALETTE[symbol])
    return pixels


def write_png(path: Path, width: int, height: int, pixels: bytes) -> None:
    if len(pixels) != width * height * 4:
        raise ValueError(f"{path} has an invalid RGBA buffer")

    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    rows = b"".join(b"\0" + pixels[y * width * 4:(y + 1) * width * 4] for y in range(height))
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows, level=9))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(png)


def main() -> None:
    JAVA_TEXTURE.parent.mkdir(parents=True, exist_ok=True)
    BEDROCK_TEXTURES.mkdir(parents=True, exist_ok=True)
    atlas_width = 16 * len(SPRITES)
    atlas = bytearray(atlas_width * 16 * 4)
    for index, (name, rows) in enumerate(SPRITES.items()):
        sprite = render(name, rows)
        write_png(BEDROCK_TEXTURES / f"{name}.png", 16, 16, sprite)
        for y in range(16):
            start = y * 16 * 4
            destination = (y * atlas_width + index * 16) * 4
            atlas[destination:destination + 16 * 4] = sprite[start:start + 16 * 4]
    write_png(JAVA_TEXTURE, atlas_width, 16, atlas)


if __name__ == "__main__":
    main()
