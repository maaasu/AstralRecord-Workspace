"""Validate JE/BE waystone parity and render an offscreen actual-asset preview.

Run with Python containing Pillow and NumPy:
    python 50_resourcepack/scripts/validate-world-display.py \
        --java-pack target/bedrock-teleporter-qa/packs-a/AstralRecordResourcePack.zip \
        --bedrock-pack target/bedrock-teleporter-qa/packs-a/AstralRecordResourcePack.mcpack \
        --self-test
The report and contact sheet are written under target/bedrock-teleporter-qa.
This is a matching-cuboid/UV software preview, not a native Minecraft render.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont


FACES = ("north", "east", "south", "west", "up", "down")
NORMALS = {
    "north": (0, 0, -1), "east": (1, 0, 0), "south": (0, 0, 1),
    "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0),
}
# Corners in UV order: upper-left, upper-right, lower-right, lower-left.
CORNERS = {
    "north": ((1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)),
    "east": ((1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)),
    "south": ((0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)),
    "west": ((0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)),
    "up": ((0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)),
    "down": ((0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)),
}
SHADE = {"north": .79, "east": .85, "south": .95, "west": .73,
         "up": 1.10, "down": .58}
VIEWS = (("Front (+Z)", (0, .38, 1)),
         ("Three-quarter (+X,+Z)", (1, .8, 1)),
         ("Rear (-X,-Z)", (-1, .7, -1)))


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def vector(values):
    return tuple(round(float(value), 7) for value in values)


def load_assets(root: Path):
    assets = root / "assets/astralrecord"
    be = root / "bedrock"
    je_model = read_json(assets / "models/world/waystone.json")
    be_geometry = read_json(be / "models/entity/world_display.geo.json")
    be_model = next(g for g in be_geometry["minecraft:geometry"]
                    if g["description"]["identifier"] ==
                    "geometry.astralrecord.world.waystone")
    java = {}
    bedrock = {}
    for state in ("locked", "unlocked"):
        variant = read_json(assets / f"models/world/waystone_{state}.json")
        java[state] = {
            "variant": variant,
            "path": assets / f"textures/item/world/waystone_{state}.png",
        }
        bedrock[state] = {
            "path": be / f"textures/entity/world/waystone_{state}.png",
        }
    return je_model, be_model, java, bedrock, (
        read_json(be / "entity/world_display.entity.json"),
        read_json(be / "render_controllers/world_display.render_controllers.json"),
    )


def compare_assets(root, je_model, be_model, java, bedrock, routing):
    errors = []
    elements = je_model["elements"]
    cubes = [cube for bone in be_model["bones"] for cube in bone["cubes"]]
    if len(elements) != len(cubes):
        errors.append(f"cube count: JE {len(elements)}, BE {len(cubes)}")
    for index, (element, cube) in enumerate(zip(elements, cubes)):
        name = element.get("name", f"cube_{index}")
        if element.get("rotation"):
            errors.append(f"{name}: JE element rotation is unsupported by preview")
        if any(face_data.get("rotation", 0) for face_data in element["faces"].values()):
            errors.append(f"{name}: JE face rotation is unsupported by preview")
        if any(key in cube for key in ("rotation", "pivot", "inflate")):
            errors.append(f"{name}: BE cube rotation/pivot/inflate is unsupported by preview")
        if cube.get("mirror"):
            errors.append(f"{name}: BE mirrored UV is unsupported by preview")
        lo = np.array(element["from"], dtype=float) - (8, 0, 8)
        hi = np.array(element["to"], dtype=float) - (8, 0, 8)
        if vector(lo) != vector(cube["origin"]):
            errors.append(f"{name}: cube origin differs")
        if vector(hi - lo) != vector(cube["size"]):
            errors.append(f"{name}: cube size differs")
        for face in FACES:
            je_face = element["faces"].get(face)
            be_face = cube["uv"].get(face)
            if je_face is None or be_face is None:
                errors.append(f"{name}/{face}: missing face")
                continue
            if je_face.get("texture") != "#surface":
                errors.append(f"{name}/{face}: JE face texture must reference #surface")
            uv = np.array(je_face["uv"], dtype=float) * 4
            expected = (vector(uv[:2]), vector(uv[2:] - uv[:2]))
            actual = (vector(be_face["uv"]), vector(be_face["uv_size"]))
            if expected != actual:
                errors.append(f"{name}/{face}: UV JE {expected}, BE {actual}")
    description = be_model["description"]
    if (description["texture_width"], description["texture_height"]) != (64, 64):
        errors.append("BE waystone geometry texture dimensions are not 64x64")
    entity, controller_file = routing
    entity = entity["minecraft:client_entity"]["description"]
    controller = controller_file["render_controllers"]["controller.render.astralrecord.world_display"]
    for bone in be_model["bones"]:
        if any(key in bone for key in ("rotation", "binding", "parent")) or bone.get("mirror") or \
                vector(bone.get("pivot", (0, 0, 0))) != (0, 0, 0):
            errors.append(f"{bone.get('name', '<unnamed>')}: BE bone transform is unsupported by preview")
    if entity.get("identifier") != "astralrecord:world_display":
        errors.append("BE client entity identifier is incorrect")
    if entity.get("render_controllers") != ["controller.render.astralrecord.world_display"]:
        errors.append("BE client entity controller binding is incorrect")
    if entity.get("materials") != {"default": "entity"}:
        errors.append("BE client entity material binding is incorrect")
    if controller.get("materials") != [{"*": "Material.default"}]:
        errors.append("BE render controller material binding is incorrect")
    style = entity.get("properties", {}).get("astralrecord:style")
    if style != {"type": "int", "range": [0, 3], "default": 0, "client_sync": True}:
        errors.append("BE style property declaration is incorrect")
    selector = "math.clamp(query.property('astralrecord:style'), 0, 3)"
    if controller.get("geometry") != f"Array.models[{selector}]":
        errors.append("BE render controller geometry selector is incorrect")
    if controller.get("textures") != [f"Array.styles[{selector}]"]:
        errors.append("BE render controller texture selector is incorrect")
    if entity["geometry"].get("waystone") != description["identifier"]:
        errors.append("BE waystone geometry route is incorrect")
    if controller["arrays"]["geometries"]["Array.models"][:2] != ["Geometry.waystone"] * 2:
        errors.append("BE style 0/1 geometry routes are incorrect")
    if controller["arrays"]["textures"]["Array.styles"][:2] != [
            "Texture.waystone_locked", "Texture.waystone_unlocked"]:
        errors.append("BE style 0/1 texture routes are incorrect")
    hashes = {}
    for state in ("locked", "unlocked"):
        variant = java[state]["variant"]
        if variant.get("parent") != "astralrecord:world/waystone":
            errors.append(f"JE {state} variant has incorrect parent")
        if variant.get("textures", {}).get("surface") != f"astralrecord:item/world/waystone_{state}":
            errors.append(f"JE {state} variant has incorrect texture")
        if entity["textures"].get(f"waystone_{state}") != f"textures/entity/world/waystone_{state}":
            errors.append(f"BE {state} texture route is incorrect")
        jb = java[state]["path"].read_bytes()
        bb = bedrock[state]["path"].read_bytes()
        hashes[state] = {"JE": hashlib.sha256(jb).hexdigest(),
                         "BE": hashlib.sha256(bb).hexdigest()}
        if jb != bb:
            errors.append(f"{state}: JE and BE texture bytes differ")
        for edition, path in (("JE", java[state]["path"]),
                              ("BE", bedrock[state]["path"])):
            with Image.open(path) as image:
                if image.size != (64, 64):
                    errors.append(f"{edition} {state} texture is {image.size}, not 64x64")
    return {"pass": not errors, "cube_count_je": len(elements),
            "cube_count_be": len(cubes), "texture_sha256": hashes,
            "errors": errors}


def validate_lodestone_item(item):
    errors = []
    select = item.get("model", {})
    if (select.get("type"), select.get("property"), select.get("index")) != (
            "minecraft:select", "minecraft:custom_model_data", 0):
        errors.append("Java lodestone item is not a custom_model_data string select at index 0")
    for state in ("locked", "unlocked"):
        wanted = f"astralrecord:world/waystone_{state}"
        resolved = None
        for case in select.get("cases", []):
            when = case.get("when", [])
            if wanted in (when if isinstance(when, list) else [when]):
                resolved = case.get("model")
                break
        if resolved != {"type": "minecraft:model", "model": wanted}:
            errors.append(f"Java lodestone {state} string selector does not resolve to {wanted}")
    return errors


def verify_pack(root, archive_path, edition):
    """Compare only waystone-related packaged entries with their source files."""
    if edition == "JE":
        names = [f"assets/astralrecord/models/world/waystone{suffix}.json"
                 for suffix in ("", "_locked", "_unlocked")]
        names += [f"assets/astralrecord/textures/item/world/waystone_{state}.png"
                  for state in ("locked", "unlocked")]
        source = root
    else:
        names = ["entity/world_display.entity.json",
                 "models/entity/world_display.geo.json",
                 "render_controllers/world_display.render_controllers.json"]
        names += [f"textures/entity/world/waystone_{state}.png"
                  for state in ("locked", "unlocked")]
        source = root / "bedrock"
    errors = []
    verified = []
    try:
        with zipfile.ZipFile(archive_path) as archive:
            entries = archive.namelist()
            for name in names:
                if entries.count(name) != 1:
                    errors.append(f"{edition} pack entry {name} occurs {entries.count(name)} times")
                    continue
                package_bytes = archive.read(name)
                file_bytes = (source / name).read_bytes()
                if name.endswith(".json"):
                    try:
                        packaged = json.loads(package_bytes.decode("utf-8-sig"))
                        original = json.loads(file_bytes.decode("utf-8-sig"))
                    except (UnicodeError, json.JSONDecodeError) as exc:
                        errors.append(f"{edition} pack entry {name} is invalid JSON: {exc}")
                        continue
                    equal = packaged == original
                else:
                    equal = package_bytes == file_bytes
                if equal:
                    verified.append(name)
                else:
                    errors.append(f"{edition} pack entry {name} differs from source")
            if edition == "JE":
                item_path = "assets/minecraft/items/lodestone.json"
                if entries.count(item_path) != 1:
                    errors.append(f"JE pack entry {item_path} occurs {entries.count(item_path)} times")
                else:
                    try:
                        item = json.loads(archive.read(item_path).decode("utf-8-sig"))
                        item_errors = validate_lodestone_item(item)
                        errors.extend(item_errors)
                        if not item_errors:
                            verified.append(item_path)
                    except (UnicodeError, json.JSONDecodeError) as exc:
                        errors.append(f"JE pack entry {item_path} is invalid JSON: {exc}")
    except (OSError, zipfile.BadZipFile) as exc:
        errors.append(f"Cannot read {edition} pack {archive_path}: {exc}")
    return {"pass": not errors, "archive": str(archive_path),
            "verified_entries": verified, "errors": errors}


def run_negative_self_tests(root, je_model, be_model, java, bedrock, routing):
    """In-memory bad fixtures; never modify source assets or archives."""
    checks = []

    def expect_error(label, expected, *, change_model=None, change_route=None):
        model = copy.deepcopy(be_model)
        route = copy.deepcopy(routing)
        if change_model:
            change_model(model)
        if change_route:
            change_route(route)
        actual = compare_assets(root, je_model, model, java, bedrock, route)["errors"]
        if not any(expected in error for error in actual):
            raise AssertionError(f"{label}: missing expected error {expected!r}; got {actual!r}")
        checks.append(label)

    def wrong_identifier(route):
        route[0]["minecraft:client_entity"]["description"]["identifier"] = "bad:world_display"

    def wrong_binding(route):
        route[0]["minecraft:client_entity"]["description"]["render_controllers"] = []

    def wrong_geometry_selector(route):
        controller = route[1]["render_controllers"]["controller.render.astralrecord.world_display"]
        controller["geometry"] = "Geometry.waystone"

    def wrong_texture_selector(route):
        controller = route[1]["render_controllers"]["controller.render.astralrecord.world_display"]
        controller["textures"] = ["Texture.waystone_locked"]

    def wrong_material(route):
        route[0]["minecraft:client_entity"]["description"]["materials"] = {"default": "missing"}

    def wrong_controller_material(route):
        controller = route[1]["render_controllers"]["controller.render.astralrecord.world_display"]
        controller["materials"] = []

    def rotated_cube(model):
        model["bones"][0]["cubes"][0]["rotation"] = [0, 45, 0]

    def rotated_bone(model):
        model["bones"][0]["rotation"] = [0, 45, 0]

    def inflated_cube(model):
        model["bones"][0]["cubes"][0]["inflate"] = 1

    def shifted_bone_pivot(model):
        model["bones"][0]["pivot"] = [1, 0, 0]

    expect_error("identifier", "identifier is incorrect", change_route=wrong_identifier)
    expect_error("controller binding", "controller binding is incorrect", change_route=wrong_binding)
    expect_error("geometry expression", "geometry selector is incorrect", change_route=wrong_geometry_selector)
    expect_error("texture expression", "texture selector is incorrect", change_route=wrong_texture_selector)
    expect_error("material", "material binding is incorrect", change_route=wrong_material)
    expect_error("controller material", "controller material binding is incorrect",
                 change_route=wrong_controller_material)
    expect_error("cube rotation", "cube rotation/pivot/inflate", change_model=rotated_cube)
    expect_error("cube inflate", "cube rotation/pivot/inflate", change_model=inflated_cube)
    expect_error("bone rotation", "bone transform", change_model=rotated_bone)
    expect_error("bone pivot", "bone transform", change_model=shifted_bone_pivot)
    wrong_java = copy.deepcopy(je_model)
    wrong_java["elements"][0]["faces"]["north"]["texture"] = "#missing"
    texture_errors = compare_assets(root, wrong_java, be_model, java, bedrock, routing)["errors"]
    if not any("JE face texture must reference #surface" in error for error in texture_errors):
        raise AssertionError("JE missing face texture was not rejected")
    checks.append("JE face texture")
    bad_item = {"model": {"type": "minecraft:select",
                          "property": "minecraft:custom_model_data", "index": 0,
                          "cases": [{"when": "astralrecord:world/waystone_locked",
                                     "model": {"type": "minecraft:model", "model": "bad:model"}}]}}
    item_errors = validate_lodestone_item(bad_item)
    if not any("locked string selector" in error for error in item_errors) or \
            not any("unlocked string selector" in error for error in item_errors):
        raise AssertionError(f"lodestone selector fixture was not rejected: {item_errors}")
    checks.append("lodestone selectors")
    return checks


def camera_axes(direction):
    toward = np.asarray(direction, dtype=float)
    toward /= np.linalg.norm(toward)
    right = np.cross((0, 1, 0), toward)
    right /= np.linalg.norm(right)
    up = np.cross(toward, right)
    return right, up, toward


def render_model(elements, cubes, texture, edition, direction, width=346, height=362):
    right, up, toward = camera_axes(direction)
    objects = []
    all_points = []
    for index, (element, cube) in enumerate(zip(elements, cubes)):
        if edition == "JE":
            lo = np.asarray(element["from"], dtype=float) - (8, 0, 8)
            hi = np.asarray(element["to"], dtype=float) - (8, 0, 8)
        else:
            lo = np.asarray(cube["origin"], dtype=float)
            hi = lo + np.asarray(cube["size"], dtype=float)
        for face in FACES:
            if np.dot(NORMALS[face], toward) <= 0:
                continue
            corners = np.asarray(CORNERS[face], dtype=float)
            points = lo + corners * (hi - lo)
            all_points.extend(points)
            if edition == "JE":
                uv = np.asarray(element["faces"][face]["uv"], dtype=float) * 4
            else:
                be_uv = cube["uv"][face]
                uv = np.r_[be_uv["uv"], np.asarray(be_uv["uv"]) + be_uv["uv_size"]]
            objects.append((points, uv, face))
    origin = np.array((0, 14.5, 0), dtype=float)
    points = np.asarray(all_points) - origin
    ranges = np.stack((points @ right, -(points @ up)), axis=1)
    low, high = ranges.min(axis=0), ranges.max(axis=0)
    scale = min((width - 34) / max(high[0] - low[0], 1),
                (height - 36) / max(high[1] - low[1], 1))
    center = (low + high) / 2
    pixels = np.zeros((height, width, 3), dtype=np.uint8)
    pixels[:] = (24, 31, 43)
    depth = np.full((height, width), -1e9, dtype=float)
    for points, uv, face in objects:
        rel = points - origin
        projected = np.stack((rel @ right, -(rel @ up)), axis=1)
        projected = (projected - center) * scale + (width / 2, height / 2)
        x0, y0 = np.floor(projected.min(axis=0)).astype(int)
        x1, y1 = np.ceil(projected.max(axis=0)).astype(int)
        x0, y0 = max(0, x0), max(0, y0)
        x1, y1 = min(width - 1, x1), min(height - 1, y1)
        if x1 < x0 or y1 < y0:
            continue
        # An orthographic cuboid face is a parallelogram, so one affine map
        # gives texture coordinates and depth over the entire visible quad.
        p0, p1, _, p3 = projected
        inverse = np.linalg.inv(np.stack((p1 - p0, p3 - p0), axis=1))
        yy, xx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        st = inverse @ np.stack(((xx + .5 - p0[0]).ravel(),
                                 (yy + .5 - p0[1]).ravel()))
        s, t = st.reshape(2, *xx.shape)
        inside = (s >= 0) & (s <= 1) & (t >= 0) & (t <= 1)
        z = rel @ toward
        z_at = z[0] + s * (z[1] - z[0]) + t * (z[3] - z[0])
        local_depth = depth[y0:y1 + 1, x0:x1 + 1]
        visible = inside & (z_at > local_depth + 1e-7)
        if not visible.any():
            continue
        u = np.clip((uv[0] + s * (uv[2] - uv[0])).astype(int), 0, texture.shape[1] - 1)
        v = np.clip((uv[1] + t * (uv[3] - uv[1])).astype(int), 0, texture.shape[0] - 1)
        color = np.clip(texture[v, u].astype(float) * SHADE[face], 0, 255).astype(np.uint8)
        pixels[y0:y1 + 1, x0:x1 + 1][visible] = color[visible]
        local_depth[visible] = z_at[visible]
    return Image.fromarray(pixels, "RGB")


def create_preview(output, je_model, be_model, java, bedrock):
    width, height = 346, 362
    pad, header, title = 12, 46, 50
    sheet = Image.new("RGB", (pad + 3 * (width + pad),
                              title + 4 * (height + header + pad)), (13, 20, 31))
    draw = ImageDraw.Draw(sheet)
    font_path = Path("C:/Windows/Fonts/arial.ttf")
    font = ImageFont.truetype(str(font_path), 19) if font_path.exists() else ImageFont.load_default()
    small = ImageFont.truetype(str(font_path), 15) if font_path.exists() else ImageFont.load_default()
    draw.text((pad, 12), "Waystone | actual JE and BE assets | offscreen orthographic preview",
              font=font, fill=(233, 241, 249))
    elements = je_model["elements"]
    cubes = [cube for bone in be_model["bones"] for cube in bone["cubes"]]
    for row, (edition, state) in enumerate((("JE", "locked"), ("BE", "locked"),
                                             ("JE", "unlocked"), ("BE", "unlocked"))):
        path = (java if edition == "JE" else bedrock)[state]["path"]
        with Image.open(path) as image:
            texture = np.asarray(image.convert("RGB"))
        y = title + row * (height + header + pad)
        for col, (view_name, direction) in enumerate(VIEWS):
            x = pad + col * (width + pad)
            render = render_model(elements, cubes, texture, edition, direction, width, height)
            sheet.paste(render, (x, y + header))
            draw.text((x + 6, y + 3), f"{edition}  {state.upper()}", font=font,
                      fill=(227, 234, 247))
            draw.text((x + 6, y + 25), view_name, font=small, fill=(155, 184, 213))
    sheet.save(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="50_resourcepack directory")
    parser.add_argument("--output", type=Path,
                        help="QA directory (default: repository target/bedrock-teleporter-qa)")
    parser.add_argument("--java-pack", type=Path, help="Optional built Java ZIP to verify")
    parser.add_argument("--bedrock-pack", type=Path, help="Optional built Bedrock mcpack to verify")
    parser.add_argument("--self-test", action="store_true",
                        help="Run in-memory negative fixtures for validation rules")
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output or root.parent / "target/bedrock-teleporter-qa"
    output.mkdir(parents=True, exist_ok=True)
    je_model, be_model, java, bedrock, routing = load_assets(root)
    report = compare_assets(root, je_model, be_model, java, bedrock, routing)
    report["preview_kind"] = "matching cuboid and UV software rendering; not a native Minecraft render"
    report["packaging"] = {}
    for edition, path in (("JE", args.java_pack), ("BE", args.bedrock_pack)):
        if path is not None:
            package_result = verify_pack(root, path.resolve(), edition)
            report["packaging"][edition] = package_result
            if not package_result["pass"]:
                report["pass"] = False
                report["errors"].extend(package_result["errors"])
    if args.self_test:
        try:
            checks = run_negative_self_tests(root, je_model, be_model, java, bedrock, routing)
            report["negative_self_tests"] = {"pass": True, "checks": checks}
        except AssertionError as exc:
            report["negative_self_tests"] = {"pass": False, "error": str(exc)}
            report["errors"].append(f"Negative self-test failure: {exc}")
            report["pass"] = False
    report_path = output / "waystone-validation.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    preview_path = output / "waystone-je-be-preview.png"
    create_preview(preview_path, je_model, be_model, java, bedrock)
    print(f"{'PASS' if report['pass'] else 'FAIL'}: {report_path}")
    print(f"Preview: {preview_path}")
    for error in report["errors"]:
        print(f"ERROR: {error}")
    return 0 if report["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
