#!/usr/bin/env python3
"""Generate/check the Java quest HUD from the distributed bitmap and pinned vanilla shaders.

Generation: --clients-dir DIR containing SHA-1-verified 1.21.11/26.1/26.2/26.3.jar.
Checking and the static preview need only the committed pack assets (Pillow for preview).
This is a resource-pack preview, never a Minecraft client screenshot.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

PACK = Path(__file__).resolve().parents[1]
ROOT = PACK.parent
VARIANTS = [
    ('1.21.11', 'ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd', '0d0fbb2b68614be09056a25cecfbf068ab05b947003c9efa3bb3ad813d544dbd', '', 'rendertype_text'),
    ('26.1', '191771837687b766537a8c4607cb6fad79c533a1', '668142b13a06c3c2f731b7083715c0d11f11e44083bea933c0937b70c2daf6ff', 'quest_hud_84/', 'rendertype_text'),
    ('26.2', '2dc72797acbc1b63fc16a11c4ac393605f453754', '4bae202567d0e3020ab948c24dcd8acffaa23460622dc01f8cbc929301e062e5', 'quest_hud_88/', 'text'),
    ('26.3', 'e877b6a07acd633fb3bb475002175cec036e7b87', 'c5aab3569c07200a3d24c40f6ed2a5716b6694acd015b81e763398b7ff3ddc1f', 'quest_hud_97/', 'text'),
]
START = '    // BEGIN ASTRALRECORD QUEST HUD\n'
END = '    // END ASTRALRECORD QUEST HUD\n'


def injection(version):
    # R=252/253 carries x bit 8; G carries x low bits; B=row*16+quad width.
    guard = '#if defined(IS_GUI)' if version in ('26.2', '26.3') else '#if 1'
    vertex = 'gl_VertexIndex' if version == '26.3' else 'gl_VertexID'
    fog = '' if version in ('26.2', '26.3') else '        sphericalVertexDistance = 0.0;\n        cylindricalVertexDistance = 0.0;\n'
    return START + f'''{guard}
    // A negative-ascent provider puts only our glyphs below the ActionBar viewport.
    // Check orthographic projection as well: matching world/display colors stay vanilla.
    vec4 questScreen = ModelViewMat * vec4(Position, 1.0);
    float questHeight = 2.0 / abs(ProjMat[1][1]);
    ivec3 questCode = ivec3(round(Color.rgb * 255.0));
    int questWidth = questCode.b & 15;
    bool questProjection = abs(ProjMat[3][3] - 1.0) < 0.001 && abs(ProjMat[2][3]) < 0.001;
    bool questMarker = (questCode.r == 252 || questCode.r == 253)
        && questCode.b >= 1 && questCode.b <= 25 && questWidth >= 1 && questWidth <= 9;
    if (questProjection && questMarker && questScreen.y > questHeight + 4000.0
            && questScreen.y < questHeight + 4120.0) {{
        int questCorner = {vertex} & 3; // TL, BL, BR, TR in the vanilla glyph quad.
        float questX = float((questCode.r - 252) * 256 + questCode.g);
        float questY = 48.0 + float(questCode.b >> 4) * 13.0;
        vec2 questPixel = vec2(questX + (questCorner >= 2 ? float(questWidth) : 0.0),
            questY + (questCorner == 1 || questCorner == 2 ? 37.0 : 0.0));
        vec2 questSize = vec2(2.0 / abs(ProjMat[0][0]), questHeight);
        gl_Position.xy = (vec2(-1.0, 1.0) + questPixel / questSize * vec2(2.0, -2.0)) * gl_Position.w;
        vertexColor = vec4(questCode.b < 16 ? vec3(1.0, 0.82, 0.34) : vec3(1.0), Color.a);
{fog}    }}
#endif
''' + END


def generated_fonts():
    font = json.loads((PACK / 'assets/astralrecord/font/profile_hud_class_name_compact.json').read_text())
    for provider in font['providers']:
        if provider['type'] == 'bitmap':
            provider['ascent'] = -4096
    spacing = {'providers': [{'type': 'space', 'advances': {chr(0xE000 + n): -n for n in range(1, 11)}}]}
    return {'quest_hud': font, 'quest_hud_space': spacing}


def assets(clients, check):
    for name, data in generated_fonts().items():
        path = PACK / f'assets/astralrecord/font/{name}.json'
        output = (json.dumps(data, ensure_ascii=True, indent=2) + '\n').encode()
        if check:
            assert path.read_bytes() == output, f'Stale {path}'
        else:
            path.write_bytes(output)
    for version, jar_sha, shader_sha, directory, stem in VARIANTS:
        relative = f'assets/minecraft/shaders/core/{stem}.vsh'
        path = PACK / directory / relative
        if clients:
            data = (clients / (version + '.jar')).read_bytes()
            assert hashlib.sha1(data).hexdigest() == jar_sha, version + ' client SHA-1 mismatch'
            with zipfile.ZipFile(clients / (version + '.jar')) as z:
                source = z.read(relative).decode()
        else:
            assert check, 'Generation requires --clients-dir'
            source = path.read_text()
            block = injection(version)
            assert source.count(block) == 1, version + ' HUD branch modified'
            source = source.replace(block, '')
        assert hashlib.sha256(source.encode()).hexdigest() == shader_sha, version + ' vanilla shader changed'
        output = source.rsplit('}', 1)[0] + injection(version) + '}' + source.rsplit('}', 1)[1]
        if check:
            assert path.read_text() == output, f'Stale {path}'
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(output)


def verify_geometry():
    # The glyph's original ActionBar start (including total combat advance) is discarded.
    # Check both old/new ActionBar y, multiple GUI sizes/scales, vertices and both marker rows.
    count = 0
    for width, height in [(320, 180), (427, 240), (640, 360), (960, 540), (1920, 1080)]:
        for actionbar_y in (height - 68, height - 72):
            for old_x in (-500, 0, 80, 1000):
                for row in (0, 1):
                    for x in (8, 64, 255, 256, 278):
                        for advance in range(2, 11):
                            code = (252 + (x >> 8), x & 255, row * 16 + advance - 1)
                            assert actionbar_y + 7 + 4096 > height + 4000
                            assert actionbar_y + 7 + 4096 + 37 < height + 4120
                            for corner in range(4):
                                qx = (code[0] - 252) * 256 + code[1] + (advance - 1 if corner >= 2 else 0)
                                qy = 48 + (code[2] >> 4) * 13 + (37 if corner in (1, 2) else 0)
                                clip = (-1 + qx * 2 / width, 1 - qy * 2 / height)
                                assert abs((clip[0] + 1) * width / 2 - qx) < 1e-10
                                assert abs((1 - clip[1]) * height / 2 - qy) < 1e-10
                                count += 1
    print(f'Quest glyph geometry: {count} vertex cases passed; non-marker vanilla source hashes preserved.')


def preview(path):
    from PIL import Image, ImageDraw
    font = generated_fonts()['quest_hud']
    provider = next(p for p in font['providers'] if p['type'] == 'bitmap')
    atlas = Image.open(PACK / 'assets/astralrecord/textures/font/profile_hud_class_name_compact.png').convert('RGBA')
    cells = {ch: (col, row) for row, chars in enumerate(provider['chars']) for col, ch in enumerate(chars) if ch != '\0'}
    advances = {int(k, 16): int(v) for k, v in [line.split() for line in (ROOT / '10_plugin/AstralRecord/src/main/resources/class-name-glyph-advances.txt').read_text().splitlines()]}
    def advance(ch):
        value = advances[ord(ch)]
        return value * 3 // 4 if ch in (' ', '\u3000') else ((value - 1) * 3 + 3) // 4 + 1
    image = Image.new('RGBA', (640, 360), (22, 31, 39, 255))
    draw = ImageDraw.Draw(image)
    draw.text((8, 8), 'STATIC RESOURCE-PACK PREVIEW / NOT CLIENT CAPTURE', fill='white')
    for row, text in enumerate(('薬草採集の依頼', '月見草を採集\u3000あと2個')):
        x = 8
        for ch in text:
            step = advance(ch)
            if ch in cells:
                col, rr = cells[ch]
                tile = atlas.crop((col * 9, rr * 37, col * 9 + step - 1, rr * 37 + 37))
                color = Image.new('RGBA', tile.size, (255, 209, 87, 255) if row == 0 else (255, 255, 255, 255))
                color.putalpha(tile.getchannel('A'))
                image.alpha_composite(color, (x, 48 + row * 13))
            x += step
    path.parent.mkdir(parents=True, exist_ok=True)
    image.convert('RGB').save(path)
    print(f'Static asset preview: {path}')


def expanded_shader(version, directory, stem, clients):
    jar = clients / (version + '.jar')
    with zipfile.ZipFile(jar) as archive:
        def expand(source):
            def include(match):
                namespace, name = match.group(1).split(':')
                text = archive.read(f'assets/{namespace}/shaders/include/{name}').decode()
                return expand(re.sub(r'^#version.*$', '', text, flags=re.M))
            return re.sub(r'#(?:moj_import|include) <([^>]+)>', include, source)
        return expand((PACK / directory / f'assets/minecraft/shaders/core/{stem}.vsh').read_text())


def compile_shaders(clients, compiler):
    import subprocess
    import tempfile
    assert clients, '--compile-with requires --clients-dir'
    with tempfile.TemporaryDirectory(prefix='astral-quest-glsl-') as work:
        work = Path(work)
        for version, _, _, directory, stem in VARIANTS:
            source = expanded_shader(version, directory, stem, clients)
            modes = ('GUI', 'WORLD', 'SEE_THROUGH') if version in ('26.2', '26.3') else ('WORLD',)
            for mode in modes:
                define = '' if mode == 'WORLD' else f'\n#define IS_{mode}\n'
                path = work / f'{version}-{mode}.vert'
                path.write_text(source.replace('#version 330', '#version 330' + define))
                command = [str(compiler), '-S', 'vert']
                if version == '26.3':
                    command += ['-V', '--auto-map-bindings', '-o', str(work / 'verify.spv')]
                result = subprocess.run(command + [str(path)], capture_output=True, text=True)
                print(f'{version} {mode}: {result.stdout.strip()} {result.stderr.strip()}')
                assert result.returncode == 0 and 'WARNING' not in result.stdout + result.stderr, command
    print('All 8 vanilla/HUD vertex shader variants compiled without warnings.')


def gpu_check(clients):
    # Optional independent off-screen OpenGL check. 26.3's ShaderC/Vulkan spelling is
    # aliased only in this OpenGL harness; --compile-with compiles its untouched source.
    import moderngl
    import struct
    assert clients, '--gpu-check requires --clients-dir'
    context = moderngl.create_standalone_context(backend='egl')
    context.simple_framebuffer((1, 1)).use()
    identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    checked = 0
    for version, _, _, directory, stem in VARIANTS:
        source = expanded_shader(version, directory, stem, clients)
        defines = '\n#define IS_GUI\n' if version in ('26.2', '26.3') else ''
        if version == '26.3':
            defines += '#define gl_VertexIndex gl_VertexID\n'
        source = source.replace('#version 330', '#version 330' + defines)
        source = source.replace('void main() {', 'out vec4 questTestPosition;\nvoid main() {')
        index = source.rfind('}')
        source = source[:index] + '    questTestPosition = gl_Position;\n' + source[index:]
        program = context.program(vertex_shader=source, varyings=['questTestPosition', 'vertexColor'])
        data = identity + identity + [1, 1, 1, 1] + [0, 0, 0, 0] if version == '26.3' else identity + [1, 1, 1, 1] + [0, 0, 0, 0] + identity
        transforms = context.buffer(struct.pack('40f', *data))
        transforms.bind_to_uniform_block(0)
        program['DynamicTransforms'].binding = 0
        lightmap = context.texture((16, 16), 4, bytes([255] * (16 * 16 * 4)))
        lightmap.use(0)
        if 'Sampler2' in program:
            program['Sampler2'].value = 0
        for width, height in ((320, 180), (640, 360), (1920, 1080)):
            projection_data = [2 / width, 0, 0, 0, 0, -2 / height, 0, 0, 0, 0, -.001, 0, -1, 1, 0, 1]
            projection = context.buffer(struct.pack('16f', *projection_data))
            projection.bind_to_uniform_block(1)
            program['Projection'].binding = 1
            for old_x in (-400, 0, 900):
                for x, row, quad_width in ((8, 0, 9), (256, 1, 2), (278, 1, 8)):
                    # Positive controls plus ordinary text/color, invalid width, wrong ascent.
                    for mode in ('quest', 'normal_y', 'normal_color', 'invalid_width', 'wrong_ascent'):
                        marker = [252 + (x >> 8), x & 255, row * 16 + quad_width]
                        if mode == 'normal_color':
                            marker = [255, 255, 255]
                        if mode == 'invalid_width':
                            marker[2] = 0
                        y = 40 if mode == 'normal_y' else height + (5000 if mode == 'wrong_ascent' else 4031)
                        positions, colors = [], []
                        for corner in range(4):
                            positions += [old_x + (quad_width if corner >= 2 else 0), y + (37 if corner in (1, 2) else 0), 0]
                            colors += [c / 255 for c in marker] + [1]
                        buffers = [context.buffer(struct.pack('12f', *positions)), context.buffer(struct.pack('16f', *colors))]
                        content = [(buffers[0], '3f', 'Position'), (buffers[1], '4f', 'Color')]
                        if 'UV2' in program:
                            uv = context.buffer(struct.pack('8i', *([240, 240] * 4)))
                            buffers.append(uv)
                            content.append((uv, '2i', 'UV2'))
                        vao = context.vertex_array(program, content)
                        output = context.buffer(reserve=128)
                        vao.transform(output, vertices=4)
                        values = struct.unpack('32f', output.read())
                        for corner in range(4):
                            actual = values[corner * 8:corner * 8 + 4]
                            if mode == 'quest':
                                xx = x + (quad_width if corner >= 2 else 0)
                                yy = 48 + row * 13 + (37 if corner in (1, 2) else 0)
                            else:
                                xx, yy = positions[corner * 3:corner * 3 + 2]
                            expected = [-1 + 2 * xx / width, 1 - 2 * yy / height, 0, 1]
                            assert all(abs(a - b) < 1e-5 for a, b in zip(actual, expected)), (version, mode, actual, expected)
                            checked += 1
                        vao.release()
                        output.release()
                        for buffer in buffers:
                            buffer.release()
            projection.release()
        program.release()
        transforms.release()
        lightmap.release()
    print(f'EGL transform feedback: {checked} vertices passed on {context.info["GL_RENDERER"]}; not a client capture.')
    context.release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--clients-dir', type=Path)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--preview', type=Path)
    parser.add_argument('--compile-with', type=Path, help='Optional glslangValidator executable')
    parser.add_argument('--gpu-check', action='store_true', help='Optional moderngl EGL transform feedback')
    args = parser.parse_args()
    assets(args.clients_dir, args.check)
    verify_geometry()
    if args.compile_with:
        compile_shaders(args.clients_dir, args.compile_with)
    if args.gpu_check:
        gpu_check(args.clients_dir)
    if args.preview:
        preview(args.preview)


if __name__ == '__main__':
    main()
