"""Validate the countdown asset and wire contract without launching Minecraft."""
from pathlib import Path
import json, math
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
assert (ROOT/'challenge-countdown-version.txt').read_text()=='1'
font=json.loads((ROOT/'assets/astralrecord/font/challenge_countdown.json').read_text())
ui=json.loads((ROOT/'bedrock/ui/hud_screen.json').read_text())
controls=ui['astralrecord_countdown_route']['controls'][1]['countdown']['controls']
by_name={k:v for child in controls for k,v in child.items()}
assert ui['astralrecord_countdown_route']['controls'][0].get('normal@hud.hud_title_text') is not None
for kind,k,base in [('boss','b',0xE900),('dungeon','d',0xE910)]:
    for seconds in range(11):
        je=ROOT/f'assets/astralrecord/textures/gui/challenge_countdown/{kind}_{seconds}.png'
        be=ROOT/f'bedrock/textures/ui/astralrecord/challenge_countdown/{kind}_{seconds}.png'
        assert je.read_bytes()==be.read_bytes()
        im=Image.open(je); assert im.size==(256,192)
        provider=next(p for p in font['providers'] if p.get('chars')==[chr(base+seconds)])
        assert provider['height']==24 and provider['ascent']==12
        # Minecraft's bitmap advance trims trailing alpha, then rounds and adds one.
        advance=math.floor(im.getbbox()[2]*24/192+.5)+1+6
        assert advance==32,(kind,seconds,advance)
        expression=by_name[f'{kind}_{seconds}']['bindings'][1]['source_property_name']
        assert expression==f"((#hud_title_text_string - '§r') = 'arcd1:{k}:{seconds}')"
        assert by_name[f'{kind}_{seconds}']['offset']==[0,-8]
        for logical_height in (180,240,360):
            top=logical_height/2-56+im.getbbox()[1]/2
            assert top>0 and top+im.getbbox()[3]/2<logical_height
assert by_name['name']['offset']==[0,-62]
name=json.loads((ROOT/'assets/astralrecord/font/challenge_countdown_name.json').read_text())['providers'][1]
assert name['height']==name['ascent']==48
assert '…' in ''.join(name['chars'])
sounds=json.loads((ROOT/'assets/astralrecord/sounds.json').read_text())
for cue in ('tick','three','two','one','start'):
    assert sounds[f'challenge.countdown.{cue}']['sounds']==[f'astralrecord:challenge/countdown/{cue}']
    data=(ROOT/f'assets/astralrecord/sounds/challenge/countdown/{cue}.ogg').read_bytes()
    assert data.startswith(b'OggS')
print('PASS: 22 paired frames, JE advance/centering, Geyser reset tokens, small-screen bounds and 5 sound definitions.')
