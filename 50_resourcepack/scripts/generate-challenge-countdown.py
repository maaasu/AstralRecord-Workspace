"""Generate deterministic original countdown artwork, licensed atlas typography and short SFX.
Requires Pillow, numpy and imageio-ffmpeg. No downloaded fonts or assets.
"""
from pathlib import Path
import json, math, subprocess, tempfile, wave
import numpy as np
from PIL import Image, ImageDraw
import imageio_ffmpeg
ROOT=Path(__file__).resolve().parents[1]
def write_json(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=True,indent=2)+'\n',encoding='utf-8')
def save(path,im):
    path.parent.mkdir(parents=True,exist_ok=True); im.save(path,optimize=False)
font=json.loads((ROOT/'assets/astralrecord/font/location_hud_text.json').read_text())['providers'][1]
atlas=Image.open(ROOT/'assets/astralrecord/textures/font/location_hud_text.png').convert('RGBA')
rows=font['chars']; cw=atlas.width//len(rows[0]); ch=atlas.height//len(rows)
glyphs={c:atlas.crop((x*cw,y*ch,x*cw+cw,y*ch+ch)) for y,row in enumerate(rows) for x,c in enumerate(row) if c!='\0'}
def text(im,s,y,size,color):
    width=len(s)*size; x=(im.width-width)//2
    for c in s:
        g=glyphs.get(c,glyphs['?']).resize((size,size),Image.Resampling.LANCZOS)
        ink=Image.new('RGBA',g.size,color); ink.putalpha(g.getchannel('A')); im.alpha_composite(ink,(x,y)); x+=size
def numeral(im, value, y, height, color):
    # Original vector strokes keep the large numerals crisp, unlike enlarged caption pixels.
    overlay=Image.new('RGBA',(im.width*4,im.height*4)); d=ImageDraw.Draw(overlay)
    scale=height/38*4; width=24*scale; gap=6*scale
    left=(overlay.width-(len(value)*width+(len(value)-1)*gap))/2
    for c in value:
        def line(points):
            pts=[(left+x*scale,(y*4)+v*scale) for x,v in points]
            stroke=max(1,round(2.6*scale));d.line(pts,fill=color,width=stroke,joint='curve')
            r=stroke/2
            for x,v in (pts[0],pts[-1]): d.ellipse((x-r,v-r,x+r,v+r),fill=color)
        def curve(a,b,c,e):
            line([tuple((1-t)**3*a[i]+3*(1-t)**2*t*b[i]+3*(1-t)*t*t*c[i]+t**3*e[i] for i in (0,1)) for t in np.linspace(0,1,28)])
        if c=='0':
            d.ellipse((left+3*scale,y*4+2*scale,left+21*scale,y*4+36*scale),outline=color,width=round(2.6*scale))
        elif c=='1': line([(7,9),(13,3),(13,35)]);line([(6,35),(20,35)])
        elif c=='2': curve((3,9),(4,-3),(24,-3),(22,10));curve((22,10),(20,18),(6,24),(3,35));line([(3,35),(23,35)])
        elif c=='3': curve((3,5),(24,-6),(29,16),(12,18));curve((12,18),(29,17),(27,44),(3,32))
        elif c=='4': line([(19,36),(19,2),(2,25),(24,25)])
        elif c=='5': line([(22,3),(5,3),(4,18)]);curve((4,18),(29,7),(28,46),(3,32))
        elif c=='6': curve((20,3),(4,-3),(-2,35),(12,35));curve((12,35),(29,36),(26,12),(9,17));line([(9,17),(4,21)])
        elif c=='7': line([(2,3),(23,3),(9,36)])
        elif c=='8':
            for top,bottom in [(2,18),(18,36)]:d.ellipse((left+3*scale,y*4+top*scale,left+21*scale,y*4+bottom*scale),outline=color,width=round(2.6*scale))
        elif c=='9': curve((4,35),(20,41),(26,3),(12,3));curve((12,3),(-5,2),(-2,26),(15,21));line([(15,21),(20,17)])
        left+=width+gap
    im.alpha_composite(overlay.resize(im.size,Image.Resampling.LANCZOS))

providers=[]
for kind,color in [('boss',(243,201,112,255)),('dungeon',(113,225,218,255))]:
    for seconds in range(11):
        im=Image.new('RGBA',(256,192)); d=ImageDraw.Draw(im)
        center=(128,94); r=66
        d.ellipse((57,23,199,165),fill=(8,18,30,185),outline=(*color[:3],90),width=2)
        for seg in range(10):
            d.arc((62,28,194,160),-90+seg*36+4,-90+(seg+1)*36-4,fill=color if seg<seconds else (65,79,90,170),width=5)
        if seconds:
            numeral(im,str(seconds),65 if seconds<=3 else 69,52 if seconds<=3 else 44,color if seconds<=3 else (245,242,226,255))
            text(im,'開始まで',126,10,color)
        else:
            d.polygon([(128,49),(135,73),(157,80),(135,87),(128,111),(121,87),(99,80),(121,73)],fill=color)
            text(im,'挑戦開始',123,14,color)
        path=f'challenge_countdown/{kind}_{seconds}'
        save(ROOT/f'assets/astralrecord/textures/gui/{path}.png',im)
        save(ROOT/f'bedrock/textures/ui/astralrecord/{path}.png',im)
        providers.append({'type':'bitmap','file':f'astralrecord:gui/{path}.png','height':24,'ascent':12,'chars':[chr(0xE900+(0 if kind=='boss' else 16)+seconds)]})
write_json(ROOT/'assets/astralrecord/font/challenge_countdown.json',{'providers':providers+[{'type':'space','advances':{'\ue9ff':6}}]})
# Pad above-name glyph cells instead of shipping a platform font. Ink stays at the top.
name_atlas=Image.new('RGBA',(len(rows[0])*18,len(rows)*144))
for y,row in enumerate(rows):
    for x,c in enumerate(row):
        if c in glyphs: name_atlas.alpha_composite(glyphs[c].resize((18,22),Image.Resampling.LANCZOS),(x*18,y*144))
save(ROOT/'assets/astralrecord/textures/font/challenge_countdown_name.png',name_atlas)
write_json(ROOT/'assets/astralrecord/font/challenge_countdown_name.json',{'providers':[{'type':'space','advances':{' ':2,'\u3000':6}},{'type':'bitmap','file':'astralrecord:font/challenge_countdown_name.png','height':48,'ascent':48,'chars':rows}]})
# Each cue ends independently; the server remains authoritative over all ten seconds.
sr=48000
def bell(f,t): return (1-np.exp(-350*t))*np.exp(-7*t)*(np.sin(2*np.pi*f*t)+.24*np.sin(2*np.pi*f*2.005*t)*np.exp(-5*t)+.09*np.sin(2*np.pi*f*3.98*t)*np.exp(-14*t))
with tempfile.TemporaryDirectory() as tmp:
    sounds=json.loads((ROOT/'assets/astralrecord/sounds.json').read_text())
    for cue,f in [('tick',440),('three',523.251),('two',587.33),('one',659.255),('start',0)]:
        t=np.arange(int(sr*(1.5 if cue=='start' else .55)))/sr
        a=sum(.16*bell(p,t) for p in (261.626,391.995,523.251)) if not f else (.13 if cue=='tick' else .20)*bell(f,t)
        a+=.055*np.sin(2*np.pi*95*t)*np.exp(-25*t)*(1-np.exp(-400*t))
        a*=np.minimum(1,(len(t)-np.arange(len(t)))/480)
        wav=Path(tmp)/f'{cue}.wav'
        with wave.open(str(wav),'wb') as w:
            w.setnchannels(1);w.setsampwidth(2);w.setframerate(sr);w.writeframes((np.clip(a,-.8,.8)*32767).astype('<i2').tobytes())
        out=ROOT/f'assets/astralrecord/sounds/challenge/countdown/{cue}.ogg';out.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-loglevel','error','-y','-i',str(wav),'-c:a','libvorbis','-q:a','5','-fflags','+bitexact','-flags:a','+bitexact',str(out)],check=True)
        sounds[f'challenge.countdown.{cue}']={'sounds':[f'astralrecord:challenge/countdown/{cue}']}
    write_json(ROOT/'assets/astralrecord/sounds.json',sounds)
(ROOT/'challenge-countdown-version.txt').write_text('1',encoding='utf-8')
print('Generated 22 paired countdown frames, name font and 5 short original cues.')
