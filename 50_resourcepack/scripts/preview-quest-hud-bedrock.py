#!/usr/bin/env python3
"""Preview the quest JSON UI layout using pack glyphs as a native-font substitute.

Requires Pillow. This is a static definition preview, never a Minecraft capture.
"""
import argparse
from pathlib import Path
import json
from PIL import Image,ImageDraw
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--preview', type=Path, required=True)
args = parser.parse_args()
ROOT = Path(__file__).resolve().parents[1]
ui=json.loads((ROOT/'bedrock/ui/astralrecord_quest_hud.json').read_text())['panel']
label=ui['controls'][1]['quest_text'];ox,oy=label['offset'];panel_height=ui['size'][1]
font=json.loads((ROOT/'assets/astralrecord/font/profile_hud_class_name_compact.json').read_text())
bmp=next(p for p in font['providers'] if p['type']=='bitmap')
atlas=Image.open(ROOT/'assets/astralrecord/textures/font/profile_hud_class_name_compact.png').convert('RGBA')
rows=bmp['chars'];cw=atlas.width//len(rows[0]);ch=atlas.height//len(rows)
glyphs={c:atlas.crop((i*cw,j*ch,(i+1)*cw,(j+1)*ch)).crop((0,0,cw,9)) for j,row in enumerate(rows) for i,c in enumerate(row) if c!='\0'}
def text(im,xy,s,color):
 x,y=xy
 for c in s:
  if c==' ':x+=3;continue
  g=glyphs.get(c,glyphs['?']);box=g.getbbox();w=box[2] if box else 0
  tinted=Image.new('RGBA',g.size,color);tinted.putalpha(g.getchannel('A'))
  im.alpha_composite(tinted,(x,y));x+=w+1
 return x
out=Image.new('RGB',(1280,760),'#101820')
for k,(paper,coords,visible) in enumerate([(False,False,True),(True,True,True),(True,False,True),(True,True,False)]):
 im=Image.new('RGBA',(640,360),'#202a35');d=ImageDraw.Draw(im)
 d.text((8,8),f'PACK-DEFINITION LAYOUT: paper={paper}, coordinates={coords}, quest={visible}',fill='white')
 y=32
 if paper:d.rectangle((8,y,32,y+46),outline='#678eaa');d.text((40,y+8),'vanilla paper-doll padding (50px)',fill='#8ca7b8');y+=50
 if coords:d.text((8,y),'Position: 16, 64, -8',fill='white');y+=10
 d.text((8,y),'Days played: 12',fill='white');y+=10
 if visible:
  text(im,(ox,y+oy),'薬草採集の依頼','#ffaa00')
  text(im,(ox,y+oy+12),'月見草を採集 あと2個','#ffffff')
 y+=panel_height
 d.text((8,y),'[Chat] normal chat stays below the quest slot',fill='#b5d8e8')
 d.text((8,340),'Static layout only; pack atlas substitutes native Bedrock font. Not a client capture.',fill='#8194a3')
 out.paste(im.convert('RGB'),((k%2)*640,(k//2)*380))
args.preview.parent.mkdir(parents=True, exist_ok=True)
out.save(args.preview)
print(f'Saved static layout preview: {args.preview}')
