"""Author the shared action-ring HUD layers and Java bitmap font from the skill sprites."""

import argparse
import base64
from hashlib import sha256
from io import BytesIO
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "action-ring-hud"
SIZE = 96
SCALE = 3
CENTER = 48
SLOTS = 8
COLUMNS = 16
GOLD = (255, 197, 91, 255)
TIMER = (101, 230, 246, 255)
STATES = {
    "cooldown": ((255, 184, 92, 255), "clock"),
    "mana": ((193, 151, 255, 255), "drop"),
    "energy": ((108, 246, 178, 255), "bolt"),
    "blocked": ((252, 119, 134, 255), "cross"),
}


def point(slot, radius):
    angle = math.tau * slot / SLOTS
    return CENTER + math.sin(angle) * radius, CENTER - math.cos(angle) * radius


class Canvas:
    def __init__(self):
        self.image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE))
        self.draw = ImageDraw.Draw(self.image)

    @staticmethod
    def scaled(values):
        return tuple(round(value * SCALE) for value in values)

    def polygon(self, points, color, outline=None):
        self.draw.polygon([self.scaled(p) for p in points], fill=color, outline=outline, width=SCALE)

    def line(self, points, color, width=1):
        self.draw.line([self.scaled(p) for p in points], fill=color, width=round(width * SCALE))

    def ellipse(self, x, y, radius, color, outline=None, width=1):
        self.draw.ellipse(self.scaled((x-radius, y-radius, x+radius, y+radius)),
                          fill=color, outline=outline, width=round(width * SCALE))

    def arc(self, x, y, radius, start, stop, color, width=1):
        self.draw.arc(self.scaled((x-radius, y-radius, x+radius, y+radius)),
                      start, stop, color, round(width * SCALE))

    def rect(self, box, color, radius=0):
        self.draw.rounded_rectangle(self.scaled(box), round(radius*SCALE), fill=color)

    def image96(self):
        return self.image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def diamond(x, y, r):
    return [(x, y-r), (x+r, y), (x, y+r), (x-r, y)]


def cut_square(x, y, r, cut):
    return [(x-r+cut,y-r), (x+r-cut,y-r), (x+r,y-r+cut), (x+r,y+r-cut),
            (x+r-cut,y+r), (x-r+cut,y+r), (x-r,y+r-cut), (x-r,y-r+cut)]


def opening(frame):
    art = Canvas()
    progress = (frame + 1) / 8
    ease = 1 - (1-progress)**2
    radius = 22 + 21 * ease
    fade = round(255 * ease)
    # Interior rings remain faint so the scenery and crosshair read through the center.
    art.ellipse(CENTER, CENTER, 22 * ease, (7, 17, 29, round(36*ease)),
                (57, 112, 132, round(125*ease)))
    art.ellipse(CENTER, CENTER, radius-2, None, (13, 35, 57, round(195*ease)))
    for slot in range(SLOTS):
        angle = slot * 45 - 90
        extent = 7 + 13 * ease
        art.arc(CENTER, CENTER, radius, angle-extent, angle+extent, (13, 30, 47, fade), 2.4)
        art.arc(CENTER, CENTER, radius-0.4, angle-extent, angle+extent, (77, 144, 170, fade))
        art.arc(CENTER, CENTER, radius-0.4, angle-9*ease, angle+9*ease, (153, 219, 239, fade))
        x, y = point(slot, 14 + 16 * ease)
        border = (60, 113, 141, round(219 * ease))
        art.polygon(cut_square(x,y,10.4*ease,3.2*ease), (8,17,30,round(100*ease)), border)
        art.polygon(cut_square(x,y,8.7*ease,2.6*ease), (13,26,42,round(108*ease)))
        dx, dy = point(slot, radius)
        art.polygon(diamond(dx,dy,2.1*ease), (151,225,242,fade))
    return art.image96()


def confirmed(frame):
    art = Canvas()
    progress = frame / 7
    x, y = CENTER, 40
    pulse = 17 + math.sin(math.pi * progress) * 4
    art.ellipse(x, y, pulse+3, None, (255, 186, 77, round(95*(1-progress))), 1.2)
    art.ellipse(x, y, 16, (19, 24, 40, 144), (167, 108, 53, 255), 1.8)
    art.ellipse(x, y, 13.3, None, (255, 217, 151, 166))
    for quarter in range(4):
        angle = quarter * 90 - 90
        art.arc(x,y,17,angle-19,angle+19,GOLD,1.4)
        phase = math.radians(angle) + math.pi*progress/6
        dx, dy = x+math.cos(phase)*pulse, y+math.sin(phase)*pulse
        art.polygon(diamond(dx,dy,1.7), (255, 229, 174, 255))
    return art.image96()


def selected(slot, flash=None):
    art = Canvas()
    x, y = point(slot, 30)
    angle = slot * 45 - 90
    art.arc(CENTER,CENTER,43,angle-19,angle+19,(98,55,31,255),3.3)
    art.arc(CENTER,CENTER,43,angle-16,angle+16,GOLD,1.6)
    art.polygon(cut_square(x,y,11.2,3.4), (50,34,27,104), GOLD)
    art.polygon(cut_square(x,y,9.3,2.6), (26,28,42,112))
    dx, dy = point(slot,43)
    art.polygon(diamond(dx,dy,3.5), (96,53,31,255), GOLD)
    art.polygon(diamond(dx,dy,1.7), (255,239,190,255))
    if flash is not None:
        pulse = (244, 168, 96)[flash]
        art.arc(CENTER,CENTER,40-flash,angle-16,angle+16,(255,226,162,pulse),1.4)
        art.polygon(diamond(dx,dy,2.4-flash*0.25), (255,245,213,pulse))
    return art.image96()


def status(slot, name):
    art = Canvas()
    color, symbol = STATES[name]
    x, y = point(slot, 41)
    art.polygon(diamond(x,y,5.5), (7,15,26,255), color)
    if symbol == "clock":
        art.ellipse(x,y,2.7,None,color,0.8)
        art.line([(x,y-1.6),(x,y),(x+1.1,y+0.8)],color,0.8)
    elif symbol == "drop":
        art.polygon([(x,y-3),(x+2.2,y),(x+1.5,y+2.2),(x-1.5,y+2.2),(x-2.2,y)],color)
    elif symbol == "bolt":
        art.polygon([(x+0.6,y-3.2),(x-2.2,y+0.3),(x-0.1,y+0.3),
                     (x-0.6,y+3.2),(x+2.2,y-0.5),(x+0.2,y-0.5)],color)
    else:
        art.line([(x-1.6,y-1.6),(x+1.6,y+1.6)],color,1.2)
        art.line([(x+1.6,y-1.6),(x-1.6,y+1.6)],color,1.2)
    return art.image96()


def gauge(x, y, width, fraction, color):
    art = Canvas()
    left, right = x - width/2, x + width/2
    art.rect((left, y-1.9, right, y+1.9), (9,18,32,255), 1.6)
    if fraction:
        art.rect((left+0.9, y-0.9, left+0.9+(width-1.8)*fraction, y+0.9), color, 0.55)
    return art.image96()


def layers():
    for frame in range(8):
        yield f"opening_{frame}", 0xe401+frame, opening(frame)
        yield f"confirmed_{frame}", 0xe409+frame, confirmed(frame)
    for slot in range(SLOTS):
        yield f"selected_{slot}", 0xe420+slot, selected(slot)
        for frame in range(3):
            yield f"selected_{slot}_{frame}", 0xe4d0+slot*3+frame, selected(slot,frame)
        for index, name in enumerate(STATES):
            yield f"{name}_{slot}", 0xe428+index*8+slot, status(slot,name)
        x, y = point(slot, 17)
        for step in range(1,11):
            yield f"gauge_{slot}_{step}", 0xe470+slot*10+step-1, gauge(x,y,11,step/10,GOLD)
    for step in range(25):
        yield f"timer_{step}", 0xe450+step, gauge(CENTER,50,20,step/24,TIMER)


def skill_layer(tile, slot):
    x, y = (CENTER, 40) if slot == 8 else point(slot, 30)
    size = 22 if slot == 8 else 18
    cell = Image.new("RGBA", (SIZE, SIZE))
    cell.alpha_composite(tile.resize((size,size),Image.Resampling.LANCZOS),
                         (round(x-size/2),round(y-size/2)))
    return cell


def png(image):
    stream = BytesIO()
    image.save(stream, format="PNG", optimize=True)
    return stream.getvalue()


def atlas(cells):
    rows = math.ceil(len(cells) / COLUMNS)
    image = Image.new("RGBA", (SIZE * COLUMNS, SIZE * rows))
    chars = []
    for row in range(rows):
        text = []
        for col in range(COLUMNS):
            index = row*COLUMNS+col
            code, cell = cells[index] if index < len(cells) else (0, None)
            text.append(chr(code))
            if cell is not None:
                image.paste(cell,(col*SIZE,row*SIZE))
                # Bitmap advances use rightmost nontransparent source pixel. The
                # one-alpha pixel is visually negligible and fixes advance at 49.
                image.putpixel(((col+1)*SIZE-1,(row+1)*SIZE-1),(0,0,0,1))
        chars.append("".join(text))
    return image, chars


def outputs():
    tiles = json.loads((ROOT/"bedrock/action-ring-icon-tiles.json").read_bytes())
    skills = {int(key):base64.b64decode(value) for key,value in tiles.items()}
    skill_images = {key: Image.open(BytesIO(data)).convert("RGBA") for key,data in skills.items()}
    shared = list(layers())
    artifacts = {ART/f"{name}.png":png(image) for name, _, image in shared}
    providers = [{"type":"space","advances":{"\ue400":-49}}]

    def add_atlas(name, cells):
        image, chars = atlas(cells)
        artifacts[ART/"java"/f"{name}.png"] = png(image)
        providers.append({"type":"bitmap","file":f"astralrecord:font/action_ring_hud/{name}.png",
                          "height":48,"ascent":24,"chars":chars})

    add_atlas("ring", [(code, image) for _, code, image in shared])
    for slot in range(9):
        add_atlas(f"skills_{slot}", [(0xe800+slot*256+key,skill_layer(image,slot))
                                    for key,image in sorted(skill_images.items())])
    artifacts[ART/"java/action_ring_hud.json"] = (
        json.dumps({"providers":providers},indent=2)+"\n").encode()
    artifacts[ART/"java/skill_tiles.sha256.json"] = (
        json.dumps({key:sha256(data).hexdigest() for key,data in sorted(skills.items())},indent=2)+"\n").encode()
    return artifacts


def preview(path):
    tiles = json.loads((ROOT/"bedrock/action-ring-icon-tiles.json").read_bytes())
    shared = {name:image for name, _, image in layers()}
    sheet = Image.new("RGB",(784,224),(18,27,37))
    draw = ImageDraw.Draw(sheet)
    for col, name in enumerate(("Opening / 3","Selecting","Confirmed / 2","Confirmed / 7")):
        x = 8 + col*194
        draw.text((x+3,8), name, fill=(203,224,236))
        if col == 0:
            image = shared["opening_2"].copy()
        elif col == 1:
            image = shared["opening_7"].copy()
            for slot,key in enumerate((5,6,7,8,9,10,11,1)):
                tile=Image.open(BytesIO(base64.b64decode(tiles[str(key)]))).convert("RGBA")
                if slot == 1:
                    image.alpha_composite(shared["selected_1_1"])
                image.alpha_composite(skill_layer(tile,slot))
            for layer in ("cooldown_2","mana_4","energy_6","blocked_7","gauge_2_6","timer_18"):
                image.alpha_composite(shared[layer])
        else:
            image = shared["confirmed_1" if col == 2 else "confirmed_6"].copy()
            tile=Image.open(BytesIO(base64.b64decode(tiles["6"]))).convert("RGBA")
            image.alpha_composite(skill_layer(tile,8))
        image = image.resize((192,192), Image.Resampling.NEAREST)
        sheet.paste(image,(x,25),image)
    path.parent.mkdir(parents=True,exist_ok=True)
    sheet.save(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    for path, data in outputs().items():
        if args.check:
            actual = path.read_bytes() if path.exists() else b""
            if path.suffix == ".json":
                actual = actual.replace(b"\r\n",b"\n")
            if actual != data:
                parser.exit(1, f"Generated output differs: {path}\n")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if args.preview:
        preview(args.preview)


if __name__ == "__main__":
    main()
