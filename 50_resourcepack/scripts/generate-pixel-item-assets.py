"""Regenerate original RGBA item icons from their editable palette and pixel grid."""
import argparse
import binascii
import hashlib
import json
from pathlib import Path
import re
import struct
import zlib


def encode(spec):
    width, height = spec['width'], spec['height']
    rows = spec['pixels']
    if type(width) is not int or type(height) is not int or not (1 <= width <= 256 and 1 <= height <= 256):
        raise ValueError('Pixel dimensions must be integers from 1 through 256.')
    if len(rows) != height or any(len(row) != width for row in rows):
        raise ValueError('Pixel grid does not match its dimensions.')
    palette = {key: bytes.fromhex(color.lstrip('#')) for key, color in spec['palette'].items()}
    if any(len(key) != 1 or len(color) != 4 for key, color in palette.items()):
        raise ValueError('Palette keys must be single characters with RGBA colors.')
    raw = b''.join(b'\0' + b''.join(palette[key] for key in row) for row in rows)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ids', nargs='*', help='Asset names; omission selects every pixel source.')
    parser.add_argument('--check', action='store_true', help='Verify both PNGs and source SHA without writing.')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    source = root/'blockbench/pixel-art'
    ids = args.ids or sorted(path.name.removesuffix('.pixel.json') for path in source.glob('*.pixel.json'))
    for id_ in ids:
        if not re.fullmatch(r'[a-z0-9]+(?:_[a-z0-9]+)*', id_):
            raise ValueError('Invalid asset name: ' + id_)
        path = source/f'{id_}.pixel.json'
        spec = json.loads(path.read_text(encoding='utf-8-sig'))
        if spec['id'] != id_:
            raise ValueError('Pixel source ID does not match its filename.')
        png = encode(spec)
        digest = hashlib.sha256(png).hexdigest()
        outputs = [root/f'assets/astralrecord/textures/item/custom/{id_}.png', root/f'bedrock/textures/items/{id_}.png']
        if args.check:
            if spec.get('png_sha256') != digest or any(out.read_bytes() != png for out in outputs):
                raise ValueError('Pixel source and PNG differ: ' + id_)
        else:
            for out in outputs:
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(png)
            spec['png_sha256'] = digest
            path.write_text(json.dumps(spec, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
        print(f'{id_}: {digest}')


if __name__ == '__main__':
    main()
