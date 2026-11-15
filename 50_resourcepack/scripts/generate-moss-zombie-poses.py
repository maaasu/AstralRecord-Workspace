"""Generate the moss zombie attack poses for both editions from the shipped idle model.

Java: each pose is a separate item model. Bones are rotated about their Blockbench pivots;
every cube is written as an exact 90-degree-multiple remap (faces and UV rotation carried over)
plus at most one native element rotation of -45..45 degrees, so nothing is approximated.
Bedrock: the same poses are static bone animations blended by ``astralrecord:motion``.

  python scripts/generate-moss-zombie-poses.py            # write assets
  python scripts/generate-moss-zombie-poses.py --check    # verify shipped assets
  python scripts/generate-moss-zombie-poses.py --preview out.png   # render both editions (needs Pillow)
"""

import argparse
import json
import math
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_SOURCE = ROOT / 'blockbench/entities/midgard_moss_zombie.java.bbmodel'
BEDROCK_SOURCE = ROOT / 'blockbench/entities/midgard_moss_zombie.bedrock.bbmodel'
JAVA_BASE = ROOT / 'assets/astralrecord/models/item/midgard_moss_zombie.json'
JAVA_ITEM = ROOT / 'assets/astralrecord/items/midgard_moss_zombie.json'
JAVA_TEXTURE = ROOT / 'assets/astralrecord/textures/item/midgard_moss_zombie.png'
BEDROCK_ANIMATION = ROOT / 'bedrock/animations/midgard_moss_zombie.animation.json'
BEDROCK_GEO = ROOT / 'bedrock/models/entity/midgard_moss_zombie.geo.json'
BEDROCK_TEXTURE = ROOT / 'bedrock/textures/entity/midgard_moss_zombie.png'
ANIMATION_PREFIX = 'animation.astralrecord.midgard_moss_zombie.'
UUID_NAMESPACE = uuid.UUID('6f1c2b8e-4d0a-5c3e-9a71-2e5b8d4c7f10')

# Pose geometry is shrunk about the model centre so raised arms stay inside Java's -16..32 range;
# the fixed display scale grows by the same factor, so the in-game size is unchanged.
SHRINK = 0.75
BASE_DISPLAY_SCALE = 2.0

# Rotations in degrees, right-handed in Java model space (front is -z, left arm is +x).
# X > 0 swings a hanging arm forward then up; body X > 0 leans the torso back.
# Z > 0 swings a hanging arm toward +x. Child bones inherit the body rotation.
POSES = {
    'raise': {'body': ('x', 22.5), 'left_arm': ('x', 180.0), 'right_arm': ('x', 180.0)},
    'slam': {'body': ('x', -22.5), 'left_arm': ('x', 67.5), 'right_arm': ('x', 67.5)},
    'roar': {'body': ('x', 22.5), 'left_arm': ('z', 112.5), 'right_arm': ('z', -112.5)},
}
POSE_ORDER = ('raise', 'slam', 'roar')
BODY_CHILDREN = ('head', 'left_arm', 'right_arm')

# Java face -> (normal, native texture u axis, native texture v axis).
FACE_AXES = {
    'north': ((0, 0, -1), (-1, 0, 0), (0, -1, 0)),
    'south': ((0, 0, 1), (1, 0, 0), (0, -1, 0)),
    'east': ((1, 0, 0), (0, 0, -1), (0, -1, 0)),
    'west': ((-1, 0, 0), (0, 0, 1), (0, -1, 0)),
    'up': ((0, 1, 0), (1, 0, 0), (0, 0, 1)),
    'down': ((0, -1, 0), (1, 0, 0), (0, 0, -1)),
}
FACE_ORDER = ('north', 'east', 'south', 'west', 'up', 'down')


# ---------------------------------------------------------------- math helpers

def rot_matrix(axis, degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    if axis == 'x':
        return ((1, 0, 0), (0, c, -s), (0, s, c))
    if axis == 'y':
        return ((c, 0, s), (0, 1, 0), (-s, 0, c))
    return ((c, -s, 0), (s, c, 0), (0, 0, 1))


def mat_vec(m, v):
    return tuple(sum(m[r][k] * v[k] for k in range(3)) for r in range(3))


def mat_mul(a, b):
    return tuple(tuple(sum(a[r][k] * b[k][c] for k in range(3)) for c in range(3)) for r in range(3))


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


def sub(a, b):
    return tuple(x - y for x, y in zip(a, b))


class Affine:
    """p -> m p + t, restricted to rotations about one axis plus translation."""

    def __init__(self, axis=None, degrees=0.0, t=(0.0, 0.0, 0.0)):
        self.axis = axis
        self.degrees = degrees
        self.m = rot_matrix(axis or 'x', degrees if axis else 0.0)
        self.t = t

    @staticmethod
    def about(axis, degrees, pivot):
        m = rot_matrix(axis, degrees)
        return Affine(axis, degrees, sub(pivot, mat_vec(m, pivot)))

    def apply(self, p):
        return add(mat_vec(self.m, p), self.t)

    def then(self, outer):
        """Return outer o self (any rotation; pose_element splits it into right angles plus a remainder)."""
        result = Affine()
        result.m = mat_mul(outer.m, self.m)
        result.t = add(mat_vec(outer.m, self.t), outer.t)
        return result


def snap(v):
    return tuple(int(round(x)) for x in v)


def r4(x):
    value = round(x, 4)
    return 0.0 if value == 0 else value


# ---------------------------------------------------------------- Java poses

def bone_map(source):
    groups = {g['uuid']: g for g in source['groups']}
    index = {e['uuid']: i for i, e in enumerate(source['elements'])}
    bones, pivots = {}, {}

    def walk(node):
        group = groups[node['uuid']]
        pivots[group['name']] = tuple(group['origin'])
        for child in node['children']:
            if isinstance(child, str):
                bones[index[child]] = group['name']
            else:
                walk(child)

    for node in source['outliner']:
        if not isinstance(node, str):
            walk(node)
    return bones, pivots


def bone_transforms(pose, pivots):
    spec = POSES[pose]
    body_axis, body_angle = spec['body']
    body = Affine.about(body_axis, body_angle, pivots['body'])
    result = {'root': Affine(), 'left_leg': Affine(), 'right_leg': Affine(), 'body': body, 'head': body}
    for arm in ('left_arm', 'right_arm'):
        # Arms are children of body, exactly as the Bedrock bones are parented.
        axis, angle = spec[arm]
        result[arm] = Affine.about(axis, angle, pivots[arm]).then(body)
    return result


def texture_axes(face, rotation):
    _, u, v = FACE_AXES[face]
    neg = lambda a: tuple(-x for x in a)
    return {0: (u, v), 90: (v, neg(u)), 180: (neg(u), neg(v)), 270: (neg(v), u)}[rotation]


def remap_faces(faces, rigid):
    result = {}
    for face in FACE_ORDER:
        if face not in faces:
            continue
        data = dict(faces[face])
        normal, _, _ = FACE_AXES[face]
        u, v = texture_axes(face, data.pop('rotation', 0))
        target_normal = snap(mat_vec(rigid, normal))
        target_u, target_v = snap(mat_vec(rigid, u)), snap(mat_vec(rigid, v))
        target = next(name for name, axes in FACE_AXES.items() if axes[0] == target_normal)
        rotation = next(r for r in (0, 90, 180, 270) if texture_axes(target, r) == (target_u, target_v))
        ordered = {'uv': data['uv']}
        if rotation:
            ordered['rotation'] = rotation
        ordered['texture'] = data['texture']
        if 'tintindex' in data:
            ordered['tintindex'] = data['tintindex']
        result[target] = ordered
    return {face: result[face] for face in FACE_ORDER if face in result}


def shrink(p):
    return tuple(8.0 + (x - 8.0) * SHRINK for x in p)


def transpose(m):
    return tuple(tuple(m[c][r] for c in range(3)) for r in range(3))


def euler_zyx(x, y, z):
    """Element rotation fields (degrees) -> matrix Rz * Ry * Rx, as Blockbench exports them."""
    return mat_mul(rot_matrix('z', z), mat_mul(rot_matrix('y', y), rot_matrix('x', x)))


def to_euler_zyx(m):
    y = math.degrees(math.asin(max(-1.0, min(1.0, -m[2][0]))))
    x = math.degrees(math.atan2(m[2][1], m[2][2]))
    z = math.degrees(math.atan2(m[1][0], m[0][0]))
    return x, y, z


def right_angle_rotations():
    from itertools import permutations, product
    result = []
    for order in permutations(range(3)):
        for signs in product((1, -1), repeat=3):
            m = tuple(tuple(float(signs[r]) if c == order[r] else 0.0 for c in range(3)) for r in range(3))
            det = (m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                   + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]))
            if det > 0:
                result.append(m)
    return result


RIGHT_ANGLES = right_angle_rotations()


def element_rotation(element):
    rotation = element.get('rotation')
    if not rotation:
        centre = tuple((a + b) / 2.0 for a, b in zip(element['from'], element['to']))
        return rot_matrix('x', 0.0), centre
    if 'axis' in rotation:
        return rot_matrix(rotation['axis'], rotation['angle']), tuple(rotation['origin'])
    return euler_zyx(rotation.get('x', 0.0), rotation.get('y', 0.0), rotation.get('z', 0.0)), tuple(rotation['origin'])


def pose_element(element, transform):
    """Compose the element's own rotation with the bone pose without approximating either.

    The combined rotation Q is split into the nearest right-angle rotation K, applied exactly to the box
    and its faces, and a small remainder Q K^T written back as the element's x/y/z rotation.
    """
    own, origin = element_rotation(element)
    combined = mat_mul(transform.m, own)
    rigid = max(RIGHT_ANGLES, key=lambda k: sum(combined[r][c] * k[r][c] for r in range(3) for c in range(3)))
    residual = mat_mul(combined, transpose(rigid))
    moved = transform.apply(origin)
    corners = [add(mat_vec(rigid, sub(element[key], origin)), moved) for key in ('from', 'to')]
    lo = shrink(tuple(min(a, b) for a, b in zip(*corners)))
    hi = shrink(tuple(max(a, b) for a, b in zip(*corners)))
    result = {'name': element['name'], 'from': [r4(x) for x in lo], 'to': [r4(x) for x in hi]}
    x, y, z = to_euler_zyx(residual)
    if max(abs(x), abs(y), abs(z)) > 45.0 + 1e-6:
        raise ValueError('residual rotation out of range for ' + element['name'])
    if max(abs(x), abs(y), abs(z)) > 1e-6 or element.get('rotation'):
        result['rotation'] = {'origin': [r4(v) for v in shrink(moved)], 'x': r4(x), 'y': r4(y), 'z': r4(z)}
    result['faces'] = remap_faces(element['faces'], rigid)
    return result


def java_pose_model(base, source, pose):
    bones, pivots = bone_map(source)
    if len(bones) != len(base['elements']):
        raise ValueError('bbmodel and exported model element counts differ')
    for i, element in enumerate(base['elements']):
        if source['elements'][i]['name'] != element['name']:
            raise ValueError('bbmodel and exported model element order differs at %d' % i)
    transforms = bone_transforms(pose, pivots)
    elements = []
    for i, element in enumerate(base['elements']):
        elements.append(pose_element(element, transforms[bones[i]]))
        for key in ('from', 'to'):
            if any(x < -16 or x > 32 for x in elements[-1][key]):
                raise ValueError('%s %s out of Java range in %s' % (element['name'], key, pose))
    scale = r4(BASE_DISPLAY_SCALE / SHRINK)
    if base.get('display') != {'fixed': {'scale': [BASE_DISPLAY_SCALE] * 3}}:
        raise ValueError('unexpected display transform on the idle model')
    return {
        'ambientocclusion': base.get('ambientocclusion', True),
        'textures': base['textures'],
        'display': {'fixed': {'scale': [scale] * 3}},
        'elements': elements,
    }


def item_case(pose):
    return {
        'when': pose,
        'model': {
            'type': 'minecraft:model',
            'model': 'astralrecord:item/midgard_moss_zombie_%s' % pose,
            'tints': [{'type': 'minecraft:custom_model_data', 'index': 0, 'default': 16777215}],
        },
    }


def java_item(current):
    result = json.loads(json.dumps(current))
    cases = [case for case in result['model']['cases'] if case['when'] not in POSES]
    result['model']['cases'] = cases + [item_case(pose) for pose in POSE_ORDER]
    return result


# ---------------------------------------------------------------- Bedrock poses

def bedrock_rotations(pose):
    """Java right-handed degrees -> exported Bedrock bone rotation (X and Z are left-handed there)."""
    result = {}
    for bone, (axis, angle) in POSES[pose].items():
        rotation = [0.0, 0.0, 0.0]
        rotation['xyz'.index(axis)] = -angle
        result[bone] = [r4(x) for x in rotation]
    return result


def bedrock_animation_json(current):
    result = json.loads(json.dumps(current))
    animations = result['animations']
    for pose in POSE_ORDER:
        animations[ANIMATION_PREFIX + pose] = {
            'loop': True,
            'bones': {bone: {'rotation': rotation} for bone, rotation in bedrock_rotations(pose).items()},
        }
    return result


def stable_uuid(*parts):
    return str(uuid.uuid5(UUID_NAMESPACE, '/'.join(parts)))


def bbmodel_number(value):
    text = repr(float(value))
    return text[:-2] if text.endswith('.0') and text != '-0.0' else text


def bedrock_bbmodel(current):
    result = json.loads(json.dumps(current))
    bone_ids = {group['name']: group['uuid'] for group in result['groups']}
    names = {ANIMATION_PREFIX + pose for pose in POSE_ORDER}
    animations = [a for a in result['animations'] if a['name'] not in names]
    for pose in POSE_ORDER:
        name = ANIMATION_PREFIX + pose
        animators = {}
        for bone, (x, y, z) in bedrock_rotations(pose).items():
            # Blockbench keeps Bedrock X/Y rotations negated in its project file.
            animators[bone_ids[bone]] = {
                'name': bone,
                'type': 'bone',
                'keyframes': [{
                    'channel': 'rotation',
                    'data_points': [{'x': bbmodel_number(-x), 'y': bbmodel_number(-y), 'z': bbmodel_number(z)}],
                    'uuid': stable_uuid(name, bone, 'rotation', '0'),
                    'time': 0.0,
                    'interpolation': 'linear',
                    'color': -1,
                }],
            }
        animations.append({
            'name': name, 'uuid': stable_uuid(name), 'loop': 'loop', 'override': False, 'length': 0,
            'snapping': 100, 'selected': False, 'anim_time_update': '', 'blend_weight': '',
            'start_delay': '', 'loop_delay': '', 'animators': animators,
        })
    result['animations'] = animations
    return result


# ---------------------------------------------------------------- IO

def dump(value):
    return json.dumps(value, indent=2) + '\n'


def outputs():
    base = json.loads(JAVA_BASE.read_text(encoding='utf-8'))
    java_source = json.loads(JAVA_SOURCE.read_text(encoding='utf-8'))
    files = {}
    for pose in POSE_ORDER:
        path = JAVA_BASE.with_name('midgard_moss_zombie_%s.json' % pose)
        files[path] = dump(java_pose_model(base, java_source, pose))
    files[JAVA_ITEM] = dump(java_item(json.loads(JAVA_ITEM.read_text(encoding='utf-8'))))
    files[BEDROCK_ANIMATION] = dump(bedrock_animation_json(json.loads(BEDROCK_ANIMATION.read_text(encoding='utf-8'))))
    bedrock_source = json.loads(BEDROCK_SOURCE.read_text(encoding='utf-8'))
    files[BEDROCK_SOURCE] = dump(bedrock_bbmodel(bedrock_source))
    return files


def write(files):
    for path, text in files.items():
        # Platform line endings, matching the checked-out pack files (Git normalises them).
        path.write_text(text, encoding='utf-8')
        print('wrote', path.relative_to(ROOT))


def check(files):
    stale = []
    for path, text in files.items():
        if not path.exists():
            stale.append(path)
            continue
        same = path.read_bytes().decode('utf-8').replace('\r\n', '\n') == text
        if not same:
            stale.append(path)
    for path in stale:
        print('stale:', path.relative_to(ROOT))
    if not stale:
        print('moss zombie poses: %d files match' % len(files))
    return not stale


# ---------------------------------------------------------------- preview

def preview(path):
    from PIL import Image, ImageDraw

    java_texture = Image.open(JAVA_TEXTURE).convert('RGBA')
    bedrock_texture = Image.open(BEDROCK_TEXTURE).convert('RGBA')
    base = json.loads(JAVA_BASE.read_text(encoding='utf-8'))
    java_source = json.loads(JAVA_SOURCE.read_text(encoding='utf-8'))
    geo = json.loads(BEDROCK_GEO.read_text(encoding='utf-8'))['minecraft:geometry'][0]
    poses = ('idle',) + POSE_ORDER
    views = (('front 3/4', -35.0), ('side', -90.0))
    cell, pad = 300, 24
    sheet = Image.new('RGB', (cell * len(poses), (cell + pad) * len(views) * 2 + pad), (232, 232, 226))
    draw = ImageDraw.Draw(sheet)
    for column, pose in enumerate(poses):
        if pose == 'idle':
            model = base
        else:
            model = java_pose_model(base, java_source, pose)
        java_quads = java_model_quads(model, java_texture)
        bedrock_quads = bedrock_model_quads(geo, bedrock_texture, pose)
        for row, (label, yaw) in enumerate(views):
            for edition, quads in enumerate((java_quads, bedrock_quads)):
                y0 = pad + ((row * 2) + edition) * (cell + pad)
                tile = render(quads, yaw, cell)
                sheet.paste(tile, (column * cell, y0))
                draw.text((column * cell + 6, y0 - 16), '%s  %s  %s' % (
                    'Java' if edition == 0 else 'Bedrock', pose, label), fill=(30, 30, 30))
    sheet.save(path)
    print('preview', path)


def face_points(lo, hi, axes):
    normal, u, v = axes
    origin = [0.0, 0.0, 0.0]
    for k in range(3):
        if normal[k]:
            origin[k] = hi[k] if normal[k] > 0 else lo[k]
        elif u[k]:
            origin[k] = lo[k] if u[k] > 0 else hi[k]
        elif v[k]:
            origin[k] = lo[k] if v[k] > 0 else hi[k]
    size_u = sum(abs(u[k]) * (hi[k] - lo[k]) for k in range(3))
    size_v = sum(abs(v[k]) * (hi[k] - lo[k]) for k in range(3))
    return tuple(origin), size_u, size_v


def java_model_quads(model, texture):
    """Return textured texel quads in model units centred on the origin, y up, front -z."""
    quads = []
    scale = texture.width / 16.0
    display = model['display']['fixed']['scale'][0] / BASE_DISPLAY_SCALE
    for element in model['elements']:
        own, pivot = element_rotation(element)
        transform = Affine()
        transform.m, transform.t = own, sub(pivot, mat_vec(own, pivot))
        for face, data in element['faces'].items():
            normal, _, _ = FACE_AXES[face]
            u, v = texture_axes(face, data.get('rotation', 0))
            origin, size_u, size_v = face_points(element['from'], element['to'], (normal, u, v))
            u1, v1, u2, v2 = data['uv']
            quads.extend(texel_quads(texture, scale, (u1, v1, u2, v2), origin, u, v, size_u, size_v,
                                     lambda p, t=transform: tuple((x - 8.0) * display for x in t.apply(p)),
                                     mat_vec(transform.m, normal)))
    return quads


def texel_quads(texture, scale, uv, origin, u, v, size_u, size_v, place, normal):
    u1, v1, u2, v2 = uv
    count_u = max(1, int(round(abs(u2 - u1) * scale)))
    count_v = max(1, int(round(abs(v2 - v1) * scale)))
    result = []
    for i in range(count_u):
        for j in range(count_v):
            px = int(math.floor(u1 * scale + (i + 0.5) / count_u * (u2 - u1) * scale))
            py = int(math.floor(v1 * scale + (j + 0.5) / count_v * (v2 - v1) * scale))
            colour = texture.getpixel((min(texture.width - 1, max(0, px)), min(texture.height - 1, max(0, py))))
            if colour[3] < 128:
                continue
            corners = []
            for a, b in ((i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)):
                p = add(origin, add(tuple(c * size_u * a / count_u for c in u), tuple(c * size_v * b / count_v for c in v)))
                corners.append(place(p))
            result.append((corners, colour[:3], normal))
    return result


def bedrock_model_quads(geo, texture, pose):
    """Render in Blockbench's view space: geo x mirrored, X/Y rotations negated, Euler order ZYX."""
    def euler(rx, ry, rz):
        return mat_mul(rot_matrix('z', rz), mat_mul(rot_matrix('y', -ry), rot_matrix('x', -rx)))

    def view(p):
        return (-p[0], p[1], p[2])

    bones = {bone['name']: bone for bone in geo['bones']}
    animation = bedrock_rotations(pose) if pose != 'idle' else {}
    matrices = {}

    def world(name):
        if name in matrices:
            return matrices[name]
        bone = bones[name]
        pivot = view(bone.get('pivot', [0, 0, 0]))
        rx, ry, rz = bone.get('rotation', [0, 0, 0])
        ax, ay, az = animation.get(name, [0, 0, 0])
        m = euler(rx + ax, ry + ay, rz + az)
        local = (m, sub(pivot, mat_vec(m, pivot)))
        parent = world(bone['parent']) if bone.get('parent') else (rot_matrix('x', 0), (0, 0, 0))
        combined = (mat_mul(parent[0], local[0]), add(mat_vec(parent[0], local[1]), parent[1]))
        matrices[name] = combined
        return combined

    size = (texture.width, texture.height)
    tex_w, tex_h = geo['description']['texture_width'], geo['description']['texture_height']
    scale = (size[0] / tex_w, size[1] / tex_h)
    quads = []
    # Geo pixels are twice the Java units; the body_scale animation shrinks the root to 0.8333.
    body_scale = 0.8333333333 / 2.0
    for name, bone in bones.items():
        bm, bt = world(name)
        for cube in bone.get('cubes', []):
            ox, oy, oz = cube['origin']
            sx, sy, sz = cube['size']
            lo = view((ox + sx, oy, oz))
            hi = view((ox, oy + sy, oz + sz))
            lo, hi = tuple(min(a, b) for a, b in zip(lo, hi)), tuple(max(a, b) for a, b in zip(lo, hi))
            cm, ct = rot_matrix('x', 0), (0.0, 0.0, 0.0)
            if cube.get('rotation'):
                pivot = view(cube.get('pivot', [0, 0, 0]))
                cm = euler(*cube['rotation'])
                ct = sub(pivot, mat_vec(cm, pivot))
            for face, data in cube['uv'].items():
                # Bedrock per-face UV uses the same in-view orientation as Blockbench's Java faces.
                normal, u, v = FACE_AXES[face]
                origin, size_u, size_v = face_points(lo, hi, (normal, u, v))
                (fu, fv), (su, sv) = data['uv'], data['uv_size']
                uv = (fu * scale[0], fv * scale[1], (fu + su) * scale[0], (fv + sv) * scale[1])

                def place(p, cm=cm, ct=ct, bm=bm, bt=bt):
                    q = add(mat_vec(cm, p), ct)
                    q = add(mat_vec(bm, q), bt)
                    return tuple(x * body_scale for x in q)

                quads.extend(texel_quads(texture, 1.0, uv, origin, u, v, size_u, size_v, place,
                                         mat_vec(bm, mat_vec(cm, normal))))
    return quads


def render(quads, yaw, cell):
    from PIL import Image, ImageDraw

    image = Image.new('RGB', (cell, cell), (205, 214, 200))
    draw = ImageDraw.Draw(image)
    turn = rot_matrix('y', yaw)
    light = (-0.45, 0.75, -0.5)
    length = math.sqrt(sum(x * x for x in light))
    light = tuple(x / length for x in light)
    zoom = cell / 40.0
    items = []
    for corners, colour, normal in quads:
        projected = [mat_vec(turn, p) for p in corners]
        n = mat_vec(turn, normal)
        if n[2] > 1e-6:
            continue
        depth = sum(p[2] for p in projected) / 4.0
        shade = 0.55 + 0.45 * max(0.0, sum(a * b for a, b in zip(mat_vec(turn, normal), light)))
        points = [(cell / 2 - p[0] * zoom, cell * 0.95 - (p[1] + 8.0) * zoom) for p in projected]
        items.append((depth, points, tuple(int(c * shade) for c in colour)))
    items.sort(key=lambda item: -item[0])
    for _, points, colour in items:
        draw.polygon(points, fill=colour)
    return image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--preview')
    args = parser.parse_args()
    files = outputs()
    if args.check:
        ok = check(files)
        if args.preview:
            preview(args.preview)
        sys.exit(0 if ok else 1)
    write(files)
    if args.preview:
        preview(args.preview)


if __name__ == '__main__':
    main()
