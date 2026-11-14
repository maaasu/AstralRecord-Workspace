"""Rebuild/check the approved reception animation textures (Pillow and NumPy)."""

import argparse
import io
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
JAVA = Path("assets/astralrecord/textures/item/world")
BEDROCK = Path("bedrock/textures/entity/world")
FRAME_COUNT = 32
STYLES = (
    ("boss_reception_altar", "boss", (144, 60, 152, 76), 16),
    ("dungeon_reception_gate", "dungeon", (16, 16, 80, 96), 128),
)


def gate_tile(rgb, n, bedrock):
    v, u = np.mgrid[:80, :64]
    y, x = (v - 39.5) / 40, (u - 31.5) / 32
    r, a = np.hypot(x, y), np.arctan2(y, x)
    p = n / FRAME_COUNT * math.tau
    edge = np.exp(-np.minimum.reduce([u, 63 - u, v, 79 - v]) / 1.6)
    swirl = (0.5 + 0.5 * np.cos(9.2 * r - 2 * a + p)) ** 9
    swirl *= np.minimum(1, r * 4) * np.minimum(1, np.maximum(0, (1.26 - r) * 4))
    ripple = np.exp(-((r - (0.12 + 1.18 * n / FRAME_COUNT)) / 0.045) ** 2)
    ripple *= math.sin(math.pi * n / FRAME_COUNT) ** 1.8
    bands = 0.5 + 0.5 * np.sin(14 * y + 1.2 * np.sin(4 * x) + p)
    highlight = swirl * 0.62 + ripple * 0.25 + edge * (0.53 + 0.12 * bands)
    color = rgb * 0.27 + np.array([15, 34, 38]) + highlight[:, :, None] * [42, 183, 161]
    alpha = 0.23 + 0.20 * bands + 0.46 * swirl + 0.15 * ripple + 0.46 * edge
    rng = np.random.default_rng(699)
    for sx, sy, offset in zip(rng.integers(2,62,23), rng.integers(2,78,23), rng.uniform(0,math.tau,23)):
        power = (0.5 + 0.5 * math.sin(p + float(offset))) ** 3
        color[sy, sx] = np.maximum(color[sy, sx], np.array([88, 229, 192]) * power)
        alpha[sy, sx] = max(alpha[sy, sx], power)
    if not bedrock:
        bayer = np.array([[0,8,2,10],[12,4,14,6],[3,11,1,9],[15,7,13,5]])
        alpha = np.where(alpha > (bayer[(v // 2) % 4, (u // 2) % 4] + 0.5) / 16, 1, 0)
        color = color * 0.89
    return np.clip(np.dstack((color, alpha * 255)), 0, 255).astype("uint8")


def boss_tile(rgb, n, bedrock):
    y, x = np.mgrid[:16, :8]
    p = n / FRAME_COUNT * 2 * math.pi
    moving = (0.5 + 0.5 * np.cos(0.41 * y + 0.32 * x + p)) ** 5
    soft = (0.5 + 0.5 * np.cos(0.66 * y - 0.7 * x + p)) ** 3
    color = np.clip(rgb.astype(float) * (0.64 + 0.28 * soft[:, :, None]) +
                    moving[:, :, None] * [128, 108, 74] + [20, 17, 16], 0, 255).astype("uint8")
    alpha = (np.clip(68 + 125 * moving + 48 * soft, 0, 255) if bedrock else
             np.where(((x + 2 * y + 1) % 7 < 1) & (moving < 0.37), 0, 255)).astype("uint8")
    return np.dstack((color, alpha))


def png(image):
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=True)
    return output.getvalue()


def assets():
    for model, style, (x0, y0, x1, y1), height in STYLES:
        tile = boss_tile if style == "boss" else gate_tile
        width = x1 - x0
        java_base = np.asarray(Image.open(ROOT / JAVA / f"{model}.png").convert("RGBA"))
        bedrock_base = np.asarray(Image.open(ROOT / BEDROCK / f"{model}.png").convert("RGBA"))
        if java_base.shape != (256, 256, 4) or not np.array_equal(java_base, bedrock_base):
            raise ValueError(f"Expected matching 256×256 reception textures for {model}")
        rgb = java_base[y0:y1, x0:x1, :3]
        strip = Image.new("RGBA", (width, height * FRAME_COUNT))
        for n in range(FRAME_COUNT):
            strip.paste(Image.fromarray(tile(rgb, n, False)), (0, n * height))
            frame = bedrock_base.copy()
            frame[y0:y1, x0:x1] = tile(rgb, n, True)
            yield BEDROCK / "reception_animation" / f"{style}_{n:02}.png", png(Image.fromarray(frame))
        name = JAVA / f"{model}_animation.png"
        yield name, png(strip)
        meta = {"animation": {"frametime": 2, "width": width, "height": height, "frames": list(range(FRAME_COUNT))}}
        yield Path(str(name) + ".mcmeta"), (json.dumps(meta, indent=2) + "\n").encode()


def validate_bindings():
    model_dir = ROOT / "assets/astralrecord/models/world"
    for name, style, (x0, y0, x1, y1), height in STYLES:
        model = json.loads((model_dir / f"{name}.json").read_bytes())
        assert model["textures"]["reception_animation"] == f"astralrecord:item/world/{name}_animation"
        faces = [
            face for obj in model["elements"] for face in obj["faces"].values()
            if face["texture"] == "#reception_animation"
        ]
        assert len(faces) == (6 if style == "boss" else 2)
        assert all(face["uv"] == [0, 0, 16, 16*(y1-y0)/height] for face in faces)
    b = ROOT / "bedrock"
    entity = json.loads((b / "entity/reception_display.entity.json").read_bytes())["minecraft:client_entity"]["description"]
    controller = json.loads((b / "render_controllers/reception_display.render_controllers.json").read_bytes())["render_controllers"]
    controller = controller[entity["render_controllers"][0]]
    frames = controller["arrays"]["textures"]["Array.styles"]
    assert len(frames) == FRAME_COUNT * len(STYLES) == len(set(frames))
    assert controller["textures"] == [
        "Array.styles[(query.property('astralrecord:reception_style') < 1.0 ? 0.0 : 32.0) "
        "+ math.mod(math.floor(query.life_time * 10.0), 32.0)]"
    ]
    for group, (_, style, _, _) in enumerate(STYLES):
        for n, ref in enumerate(frames[group*FRAME_COUNT:(group+1)*FRAME_COUNT]):
            assert entity["textures"][ref.removeprefix("Texture.")] == (
                f"textures/entity/world/reception_animation/{style}_{n:02}"
            )
    geometry = json.loads((b / "models/entity/reception_display.geo.json").read_bytes())["minecraft:geometry"]
    altar, gate = ({bone["name"]: bone for bone in g["bones"]} for g in geometry)
    assert [len(altar[k]["cubes"]) for k in ("body", "emissive", "sword", "sword_emissive", "sword_light")] == [27, 4, 15, 4, 1]
    assert altar["sword"]["parent"] == gate["portal"]["parent"] == "yaw"
    assert altar["sword_light"]["parent"] == altar["sword_emissive"]["parent"] == "sword"
    assert len(gate["portal"]["cubes"]) == 1
    assert entity["materials"]["portal"] == entity["materials"]["sword_light"] == "entity_alphablend"
    animations = json.loads((b / "animations/reception_display.animation.json").read_bytes())["animations"]
    idle = animations[entity["animations"]["sword_idle"]]
    assert idle["loop"] is True and idle["animation_length"] == 3.2 and list(idle["bones"]) == ["sword"]
    points = idle["bones"]["sword"]["position"]
    assert points["0.0"] == points["3.2"] and max(p[1] for p in points.values()) == 0.74


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify existing assets instead of replacing them")
    args = parser.parse_args()
    stale = []
    validate_bindings()
    for name, content in assets():
        path = ROOT / name
        if args.check:
            if not path.is_file():
                stale.append(str(name))
            elif path.suffix == ".png":
                # PNG encoders differ between Pillow/zlib revisions; compare pixels.
                with Image.open(path) as current, Image.open(io.BytesIO(content)) as expected:
                    if current.mode != expected.mode or current.size != expected.size or current.tobytes() != expected.tobytes():
                        stale.append(str(name))
            elif path.read_text(encoding="utf-8") != content.decode("utf-8"):
                stale.append(str(name))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if stale:
        raise SystemExit("Stale or missing reception assets:\n" + "\n".join(stale))
    print("Reception animation validated." if args.check else "Reception animation regenerated.")


if __name__ == "__main__":
    main()
