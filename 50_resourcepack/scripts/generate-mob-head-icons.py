#!/usr/bin/env python3
"""Render actual mob head geometry into the shared JE/BE inventory icons.

Requires Pillow and NumPy. No network or game session is used during generation.
The immutable Mojang source revision and texture hashes are in gui-source/mob-heads.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "gui-source/mob-heads"
MANIFEST = SOURCE / "heads.json"
DESTINATIONS = (ROOT / "assets/astralrecord/textures/item/gui",
                ROOT / "bedrock/textures/ui/astralrecord/gui")


def rotation(angles):
    x, y, z = np.radians(angles)
    # Bedrock rotations use a left-handed X/Z convention.
    x, z = -x, -z
    rx = np.array([[1, 0, 0], [0, np.cos(x), -np.sin(x)], [0, np.sin(x), np.cos(x)]])
    ry = np.array([[np.cos(y), 0, np.sin(y)], [0, 1, 0], [-np.sin(y), 0, np.cos(y)]])
    rz = np.array([[np.cos(z), -np.sin(z), 0], [np.sin(z), np.cos(z), 0], [0, 0, 1]])
    return rz @ ry @ rx


def face_uv(cube, bone):
    uv = cube.get("uv", [0, 0])
    if isinstance(uv, dict):
        return uv
    u, v = uv
    w, h, d = cube["size"]
    faces = {
        "west": {"uv": [u, v + d], "uv_size": [d, h]},
        "north": {"uv": [u + d, v + d], "uv_size": [w, h]},
        "east": {"uv": [u + d + w, v + d], "uv_size": [d, h]},
        "south": {"uv": [u + 2 * d + w, v + d], "uv_size": [w, h]},
        "up": {"uv": [u + d + w, v + d], "uv_size": [-w, -d]},
        "down": {"uv": [u + d + 2 * w, v], "uv_size": [-w, d]},
    }
    if cube.get("mirror", bone.get("mirror", False)):
        faces["east"], faces["west"] = faces["west"], faces["east"]
        for f in faces.values():
            f["uv"][0] += f["uv_size"][0]
            f["uv_size"][0] *= -1
    return faces


def quads(spec):
    result = []
    for layer in spec["layers"]:
        if "model" in layer:
            geometry = json.loads((SOURCE / layer["model"]).read_text(encoding="utf-8"))["minecraft:geometry"][0]
        else:
            geometry = layer["geometry"]
        texture = np.asarray(Image.open(SOURCE / layer["texture"]).convert("RGBA")).copy()
        # Vanilla TGA alpha is the material's tint mask, not cutout transparency.
        if layer["texture"].endswith(".tga"):
            texture[:, :, 3] = 255
        desc = geometry.get("description", geometry)
        tw = desc.get("texture_width", desc.get("texturewidth", texture.shape[1]))
        th = desc.get("texture_height", desc.get("textureheight", texture.shape[0]))
        bones = {b["name"]: b for b in geometry["bones"]}
        selected = set(spec["roots"])
        for _ in bones:
            selected.update(b["name"] for b in bones.values()
                            if b.get("parent") in selected and b["name"] not in spec.get("exclude", []))
        selected.difference_update(spec.get("exclude", []))

        def transform(points, bone):
            pivot = np.array(bone.get("pivot", [0, 0, 0]))
            points = (points - pivot) @ rotation(bone.get("rotation", bone.get("bind_pose_rotation", [0, 0, 0]))).T + pivot
            return transform(points, bones[bone["parent"]]) if bone.get("parent") in bones else points

        for name, bone in bones.items():
            if name not in selected:
                continue
            for cube in bone.get("cubes", []):
                inflate = cube.get("inflate", bone.get("inflate", 0))
                a = np.array(cube["origin"], dtype=float) - inflate
                b = np.array(cube["origin"], dtype=float) + cube["size"] + inflate
                x0, y0, z0 = a
                x1, y1, z1 = b
                corners = {
                    "north": [[x1,y1,z0],[x0,y1,z0],[x0,y0,z0],[x1,y0,z0]],
                    "south": [[x0,y1,z1],[x1,y1,z1],[x1,y0,z1],[x0,y0,z1]],
                    "west": [[x0,y1,z0],[x0,y1,z1],[x0,y0,z1],[x0,y0,z0]],
                    "east": [[x1,y1,z1],[x1,y1,z0],[x1,y0,z0],[x1,y0,z1]],
                    "up": [[x0,y1,z0],[x1,y1,z0],[x1,y1,z1],[x0,y1,z1]],
                    "down": [[x0,y0,z1],[x1,y0,z1],[x1,y0,z0],[x0,y0,z0]],
                }
                for face, data in face_uv(cube, bone).items():
                    points = np.array(corners[face], dtype=float)
                    if "rotation" in cube:
                        pivot = np.array(cube.get("pivot", [0, 0, 0]))
                        points = (points - pivot) @ rotation(cube["rotation"]).T + pivot
                    points = transform(points, bone)
                    u, v = data["uv"]
                    du, dv = data.get("uv_size", [0, 0])
                    coords = np.array([[u,v],[u+du,v],[u+du,v+dv],[u,v+dv]], dtype=float)
                    turns = int(data.get("uv_rotation", 0) / 90) + (2 if face in ("up", "down") else 0)
                    coords = np.roll(coords, -turns, axis=0)
                    coords *= [texture.shape[1] / tw, texture.shape[0] / th]
                    result.append((points, coords, texture, layer.get("opacity", 1.0)))
    if not result:
        raise ValueError("No head geometry selected")
    return result


def render(spec, size=32):
    faces = quads(spec)
    yaw, pitch = math.radians(30), math.radians(22.5)
    right = np.array([-math.cos(yaw), 0, math.sin(yaw)])
    eye = np.array([-math.sin(yaw)*math.cos(pitch), math.sin(pitch), -math.cos(yaw)*math.cos(pitch)])
    up = np.cross(eye, right)
    view = np.stack([right, -up, eye]).T
    projected = [p @ view for p, *_ in faces]
    all_points = np.concatenate(projected)
    low, high = all_points[:, :2].min(axis=0), all_points[:, :2].max(axis=0)
    center = (low + high) / 2
    scale = (size - 4) / max(high - low)
    canvas = np.zeros((size, size, 4), dtype=float)
    depth = np.full((size, size), -np.inf)
    # Alpha layers are drawn after opaque faces, furthest faces first.
    order = sorted(range(len(faces)), key=lambda i: (faces[i][3] < 1, projected[i][:, 2].mean()))
    for i in order:
        points, uv, texture, opacity = faces[i]
        p = projected[i].copy()
        p[:, :2] = (p[:, :2] - center) * scale + size / 2
        normal = np.cross(points[1]-points[0], points[2]-points[0])
        length = np.linalg.norm(normal)
        if length < 1e-8:
            continue
        # Cubes wind inward; retain camera-facing outward normals (planes are two sided).
        normal = -normal / length
        if np.dot(normal, eye) <= 0:
            continue
        light = 0.72 + 0.28 * max(0, float(np.dot(normal, np.array([-0.2, 0.9, -0.4]) / math.sqrt(1.01))))
        for ids in ([0,1,2], [0,2,3]):
            tri = p[ids]
            tex_uv = uv[ids]
            x0, y0 = np.maximum(np.floor(tri[:, :2].min(axis=0)).astype(int), 0)
            x1, y1 = np.minimum(np.ceil(tri[:, :2].max(axis=0)).astype(int), size-1)
            if x1 < x0 or y1 < y0:
                continue
            matrix = np.vstack([tri[:, :2].T, np.ones(3)])
            if abs(np.linalg.det(matrix)) < 1e-8:
                continue
            yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
            weights = np.linalg.inv(matrix) @ np.stack([xx.ravel()+0.5, yy.ravel()+0.5, np.ones(xx.size)])
            z = tri[:, 2] @ weights
            sampled = tex_uv.T @ weights
            tx = np.clip(np.floor(sampled[0]).astype(int), 0, texture.shape[1]-1)
            ty = np.clip(np.floor(sampled[1]).astype(int), 0, texture.shape[0]-1)
            colors = texture[ty, tx].astype(float)
            colors[:, :3] *= light
            colors[:, 3] *= opacity
            mask = (weights.min(axis=0) >= -1e-8) & (z > depth[yy.ravel(), xx.ravel()] + 1e-8) & (colors[:, 3] > 0)
            ys, xs = yy.ravel()[mask], xx.ravel()[mask]
            fg, bg = colors[mask], canvas[ys,xs]
            alpha = fg[:,3:4]/255
            out_alpha = alpha + bg[:,3:4]/255*(1-alpha)
            canvas[ys,xs,:3] = (fg[:,:3]*alpha + bg[:,:3]*bg[:,3:4]/255*(1-alpha)) / np.maximum(out_alpha,1e-8)
            canvas[ys,xs,3:4] = out_alpha*255
            depth[ys,xs] = z[mask]
    image = Image.fromarray(np.clip(np.rint(canvas), 0, 255).astype('uint8'))
    if not image.getbbox():
        raise ValueError("Empty head render")
    return image


def preview(images, path):
    columns, cell_w, cell_h = 5, 220, 136
    board = Image.new("RGB", (columns*cell_w, math.ceil(len(images)/columns)*cell_h), "#18202e")
    draw = ImageDraw.Draw(board)
    for i, (name, picture) in enumerate(images.items()):
        x, y = i % columns * cell_w, i // columns * cell_h
        draw.text((x+8,y+7), name, fill="#f2e9cf")
        board.paste(picture.resize((80,80), Image.Resampling.NEAREST), (x+8,y+29), picture.resize((80,80), Image.Resampling.NEAREST))
        for bx, bg in [(x+108, "#292d36"), (x+160, "#8b8b8b")]:
            draw.rectangle((bx,y+40,bx+35,y+75), fill=bg, outline="#d1d1d1")
            small = picture.resize((16,16), Image.Resampling.NEAREST)
            board.paste(small, (bx+10,y+50), small)
        draw.text((x+103,y+87), "16px JE / BE", fill="#b1bdcc")
    path.parent.mkdir(parents=True, exist_ok=True)
    board.save(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    for source in json.loads((SOURCE / "provenance.json").read_text(encoding="utf-8")):
        if "path" in source and hashlib.sha256((SOURCE / source["path"]).read_bytes()).hexdigest() != source["sha256"]:
            raise ValueError("Source hash mismatch: " + source["path"])
    icons = json.loads(MANIFEST.read_text(encoding="utf-8"))["icons"]
    images = {}
    for name, spec in icons.items():
        image = render(spec)
        images[name] = image
        output = io.BytesIO()
        image.save(output, format="PNG", optimize=False)
        for directory in DESTINATIONS:
            path = directory / ("gui_mob_head_" + name + ".png")
            if args.check:
                if not path.is_file() or path.read_bytes() != output.getvalue():
                    raise ValueError("Stale or missing head icon: " + str(path))
            else:
                directory.mkdir(parents=True, exist_ok=True)
                path.write_bytes(output.getvalue())
    registry = json.loads((ROOT / "gui-icons.json").read_text(encoding="utf-8"))
    registered = {i["role"] for i in registry["icons"] if i["base_item"] == "minecraft:paper"}
    if {"mob_head_"+name for name in icons} - registered:
        raise ValueError("Missing GUI registry head roles")
    if args.preview:
        preview(images, args.preview)
    print(f"{'Checked' if args.check else 'Generated'} {len(images)} mob head icons for Java and Bedrock.")


if __name__ == "__main__":
    main()
