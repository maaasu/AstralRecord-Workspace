#!/usr/bin/env python3
"""Regenerate shared GUI control art with Python's standard library only.

The relief comes from the selected CC0 Kenney tile recorded in gui-source/.
The semantic glyphs are original pixel drawings. Generation never edits pack
registries or layout JSON. Use --check for byte comparison, --preview <png>
for a sheet containing native-16px, distributed-32px and magnified samples.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "gui-source/kenney-pixel-adventure/stone-tile-pixels.json"
JAVA = ROOT / "assets/astralrecord/textures/item/gui"
BEDROCK = ROOT / "bedrock/textures/ui/astralrecord/gui"

# Native design grid: 16px. Every pixel becomes 2x2 in the shipped 32px PNG.
# Deliberately restrained slate, weathered metal, cream and verdigris.
COLORS = {
    ".": (0, 0, 0, 0),
    "K": (23, 31, 38, 255),
    "G": (216, 179, 99, 255),
    "H": (247, 222, 160, 255),
    "T": (103, 188, 181, 255),
    "L": (177, 229, 210, 255),
    "R": (205, 105, 99, 255),
    "r": (246, 172, 146, 255),
    "S": (140, 164, 170, 255),
    "s": (96, 121, 133, 255),
    "W": (229, 235, 215, 255),
    "B": (127, 196, 245, 255),
    "b": (60, 115, 172, 255),
    "P": (205, 164, 251, 255),
    "p": (119, 82, 168, 255),
    "O": (230, 151, 76, 255),
    "o": (148, 85, 44, 255),
    "M": (159, 214, 111, 255),
    "m": (75, 125, 69, 255),
    "g": (157, 106, 46, 255),
    "I": (242, 249, 247, 255),
    "i": (69, 87, 108, 255),
    "A": (86, 222, 214, 255),
    "a": (39, 124, 126, 255),
    "C": (165, 117, 209, 255),
    "c": (68, 47, 97, 255),
    "U": (192, 153, 108, 255),
    "u": (92, 59, 50, 255),
}
BUTTON_COLORS = {
    "0": (0, 0, 0, 0),
    "1": (16, 23, 37, 255),
    "2": (36, 57, 80, 255),
    "3": (228, 187, 120, 255),
    "4": (114, 78, 50, 255),
    "5": (50, 75, 100, 255),
}
BUTTON_EDGE_SHADE = (187, 138, 80, 255)
RECESSED_COLORS = {
    "0": (0, 0, 0, 0),
    "1": (37, 54, 73, 255),
    "2": (23, 34, 51, 255),
    "3": (27, 42, 62, 255),
    "4": (50, 68, 86, 255),
    "5": (31, 47, 65, 255),
}
RECESSED_ROLES = {"disabled", "processing", "unset"}
FILLER_ROLES = ("frame", "panel")

# Patterns are centered in the inner 10x10 design area. Shapes communicate the
# action independently of color; no text glyphs depend on client language.
PATTERNS: dict[str, tuple[str, ...]] = {
    "next": (
        "....H.....", "....HH....", "....GGH...", ".HHHGGGH..",
        ".HGGGGGGH.", ".GGGGGGG..", "....GGG...", "....GG....", "....G.....",
    ),
    "up": (
        "....H.....", "...HHH....", "..HGGGH...", ".HGGGGGH..", "HGGGGGGGH.",
        "...GGG....", "...GGG....", "...GGG....", "...GGG....",
    ),
    "close": (
        ".rr....rr.", ".rRR..RRr.", "..RRRRRR..", "...RRRR...",
        "...RRRR...", "..RRRRRR..", ".RRR..RRR.", ".RR....RR.",
    ),
    "back": (
        "..H.......", ".HH.......", "HGGHHHH...", ".HGGGGGH..", "..G...GGH.",
        "......GGG.", "......GGG.", "...TGGGG..", "...TTGG...",
    ),
    "confirm": (
        "........L.", ".......LL.", "......LT..", ".L...LT...", ".LL.LT....",
        "..LTT.....", "...T......",
    ),
    "cancel": (
        "..rrrrr...", ".rRRRrRR..", "rR...r.RR.", "rR..r..RR.", "rR.r...RR.",
        "rRr....RR.", ".RRRRRRR..", "..RRRRR...",
    ),
    "disabled": (
        "...SSS....", "..S...S...", "..s...s...", ".SSSSSSS..", ".SssWsss..",
        ".SssWsss..", ".SssWsss..", ".Sssssss..", ".sssssss..",
    ),
    "processing": (
        ".SSSSSSSS.", "..S....S..", "..Ss..sS..", "...SssS...", "....ss....",
        "...ssss...", "..s.ss.s..", "..ssssss..", ".ssssssss.",
    ),
    "unset": (
        "....S.....", "...S.S....", "..S...S...", ".S.....S..", "S.......S.",
        ".S.....S..", "..S...S...", "...S.S....", "....S.....",
    ),
    "increase": (
        "...LLL....", "...LLL....", "...LTT....", "LLLLTTTTT.", "LLTTTTTTT.",
        "LLTTTTTTT.", "...TTT....", "...TTT....", "...TTT....",
    ),
    "decrease": (
        "..........", "..........", "..........", ".rrrrrrrr.", ".rRRRRRRR.",
        ".RRRRRRRR.", "..........", "..........", "..........",
    ),
}


# Larger silhouettes and a role-specific palette distinguish menu and setting items.
PICTOGRAMS: dict[str, tuple[str, ...]] = {
    "menu": (
        "Bbbb.TTTT", "Bbbb.TLLT", "Bbbb.TTTT", ".........",
        "GGGG.Pppp", "GHHG.Pppp", "GGGG.Pppp",
    ),
    "profile": (
        "....BBBB....", "...BBWWBb...", "...BWWBBb...", "...bBBBbb...",
        "....bbb.....", "..BBBBBBBB..", ".BBLLLLBBBB.", "BBLLLLTBBBBb",
        "BLLLLTTBBBbb", "bbbbbbbbbbbb",
    ),
    "quest": (
        "........UgU...",
        "......IHHUUU..",
        "....IHHHUUHH..",
        "...IHUHHHHHg..",
        "..IHUHHUHHHg..",
        "..IHUHUHHHg...",
        "..WHHUHHHgu...",
        "..WHHHHHgrR...",
        "IHHHHHHggrR...",
        "HHHHHHHggRr...",
        "UWUUUggu.RR...",
        ".UUUggu..R.R..",
    ),
    "settings": (
        ".......I...S..",
        "......IW..WSi.",
        "......SWISsSi.",
        "......SSSsss..",
        ".......Wss....",
        ".H.....Ws.....",
        "Hg....WSi.....",
        "Gog..WSi......",
        ".OOgWSi.......",
        "..ggSii.......",
        "..WSiIi.......",
        ".WSi..Ii......",
        "WSi....SS.....",
        "Si......s.....",
    ),
    "equipment": (
        "..IWI....SSI..",
        ".IWSiSSISsSSi.",
        "IWSSiiiSssWSSi",
        "WWSWSIWSSssiSi",
        "SsiISWSSsssi..",
        ".i.IWSSSsssi..",
        "...ISWSssssi..",
        "...WSSSSssi...",
        "...WSssWssi...",
        "...SSSSSssi...",
        "...IsiiSSsi...",
        "...SssIsssi...",
        "....iiiii.....",
    ),
    "item_protection": (
        "....IISS......",
        "...IWSSSsi....",
        "..IWS..iSSi...",
        "..WS....WSi...",
        "..WS....SSi...",
        "..SS....ssi...",
        ".IHHHHHHHHGg..",
        ".HHGGGGGGGOg..",
        ".HGGGOOOGoog..",
        ".HGOGuugGoog..",
        ".HGOGgugGoog..",
        ".HGGGOOOGoog..",
        ".GGGGGGOGog...",
        "..gggggggu....",
    ),
    "trash": (
        ".....IISS.....",
        "....WS..Si....",
        "..IISIIIWWSS..",
        ".IWSSSWSSSssi.",
        "WSSSSSSsssssi.",
        ".iiiiiiiiiiii.",
        "..SMSsMSsmMi..",
        "..SMSsMSsMMi..",
        "..SMSsMssmMi..",
        "..SMSsMssmMi..",
        "...SMSsssmMi..",
        "...SMMmsmMi...",
        "....Smmmmi....",
        ".....iiii.....",
    ),
    "guide": (
        ".........IIW..",
        "..IIWW..IWHHU.",
        ".IWHHUUIWHHHU.",
        ".WHgHUUWHUHHU.",
        ".WHHgUUWHUgHU.",
        ".WHHHUUWHHHuU.",
        ".WHUgUUWHHHHU.",
        ".WHHHUUWHHHUg.",
        ".IWHHUUWHHUgp.",
        "CgUWHUUWHUgp..",
        ".CpgUUUWWUgp..",
        "..ccpgUUUgp...",
        "....cpRrpg....",
        "......R.r.....",
    ),
    "warp": (
        "......SS......",
        "....SWSSsi....",
        "....SWSSsii...",
        "....SSLaSii...",
        "....SaAaAii...",
        "....SLaASii...",
        "....SSASsii...",
        "....SWSSsii...",
        "....SSsssii...",
        "...SSWWssiii..",
        "..SWSSSSSssii.",
        ".SSSSsssSSSSi.",
        ".SSssssiiiiii.",
        "..iiiiiiiiii..",
    ),
    "adventure": (
        ".........HG...",
        "........HHAH..",
        ".......HALaAG.",
        "......HHaTAAg.",
        ".....HGHAAAgo.",
        ".....GUGGGog..",
        "....HUUOoog...",
        "...HOUUoog....",
        "..HGOUuog.....",
        "..HOUuog......",
        ".HHGgug.......",
        ".HSSgo........",
        "..ggo.........",
    ),
    "mail": (
        "......II......",
        "..IIIIIIIIIW..",
        ".IWWWWWWWWWWS.",
        ".IISWWWWWWSSSi",
        ".IWISWWWSSWSSi",
        ".IWWISISSWWSSi",
        ".IWWWIRrWWWSSi",
        ".IWWWrrRRWWSSi",
        ".IWSSrRRRSWSSi",
        ".ISWWWRRWWSSSi",
        ".ISWWWWWWSSSSi",
        "..SsssssssSsi.",
        "...iiiiiiiii..",
    ),
    "skill": (
        "..........PA..",
        ".........PLA..",
        "........PLAAa.",
        ".......PLAAac.",
        ".....HPPAaaac.",
        ".....HCPAaccc.",
        "......Gcccg...",
        ".....HHGGgo...",
        "....GUgGgo....",
        "...GUuugo.....",
        "..GUug........",
        ".GUug.........",
        "GUug..........",
        "og............",
    ),
    "currency": (
        "........IHH...",
        "......IHHGGG..",
        ".....IHGOGOGG.",
        ".....HGOGHGOGg",
        ".....HGHHHGOGg",
        "...IHHHGGGGOGg",
        ".IHHGGHHGGGGog",
        "IHGOGOHHGoggo.",
        "HGOGHGOGgggo..",
        "HGHHHHOGGg....",
        "HGOGHGOGGo....",
        ".HGGGGGGgo....",
        "..GOGGGgo.....",
        "...gggo.......",
    ),
    "party": (
        ".........LAa..",
        "........IAaAa.",
        "........LAaAa.",
        "....PP..ATaaa.",
        "...IPCC..TAa..",
        "...PCpC..LAaa.",
        "...PCpC.LAAaaa",
        "....pC..AAAAaa",
        "....PC..AAsAaa",
        "...PCpCCaaAAai",
        "..IPCCpCaAAaai",
        ".IPCCPCcccaii.",
        ".PCPCPCccc....",
        "..pcccccc.....",
    ),
    "players": (
        "....IHGGH.....",
        "...IHGGGOgUU..",
        "..UHGGGGGOgUu.",
        ".UUWWWHHHHGUu.",
        ".UWIbBBHHHGUu.",
        ".UWbBBBHHGGUu.",
        ".UWbHBbHHHGUu.",
        ".UWbbBBHUHGUu.",
        ".UWbbbbHHHGUu.",
        ".UWTTaaHHHGUu.",
        ".UWHHHHGHGGUu.",
        ".UWHUHGHGGGUu.",
        ".UUUUUUUUUUu..",
        "..uuuuuuuuu...",
    ),
    "inventory": (
        "....OOO....", "...Oo.oO...", "...OOOOO...", "..OoOOOoO..",
        ".OOoOOOoOO.", ".OoOOOOOoO.", ".OoGGGGGoO.", ".OoGHHHGoO.",
        ".OoGGGGGoO.", ".ooooooooo.",
    ),
    "storage": (
        "..OOOOOOOO..", ".OGGGGGGGGO.", "OGOOOOOOOOGO", "GGGGGGGGGGGG",
        "ooOOOHHOOOoo", "oOOOOHGOOOOo", "oOOOOOOOOOOo", "oOoOOOOOOoOo",
        "oooooooooooo",
    ),
    "setting_damage_log": (
        "..........Ir..",
        ".........IWrR.",
        "........IWrRR.",
        ".......IWrRR..",
        "......IWrRS...",
        ".....IWrRS....",
        "....IWrRS.....",
        ".H..WrRS......",
        ".HgWrRS.......",
        "..HGgS........",
        "..IggGO.......",
        ".IWs..gg......",
        "SWs...........",
        "Si............",
    ),
    "setting_damage_size": (
        ".........IIB..",
        "........IbbbB.",
        "...........Bb.",
        ".........IBb..",
        "........IBbbb.",
        ".IIBB....BBBB.",
        "IBbbbB........",
        "IB...Bb.......",
        "....IBb.......",
        "...IBb........",
        "..IBb.........",
        ".IBb..........",
        "IBBBBBb.......",
        "bbbbbbb.......",
    ),
    "setting_damage_message": (
        "..IIIIIIIIS...",
        ".IWWWWWWWWSs..",
        "IWWHHWWWHWSsi.",
        "IWHHUHHWWWSsi.",
        "IWWHHHWWUWSsi.",
        "IWHUWWWGOWSsi.",
        "IWWHWWUOgWSsi.",
        "IWWHWUOgHWSsi.",
        "IWWUWOgHWWSsi.",
        "ISSSGgSSSSssi.",
        ".ISSOssiSSsi..",
        "..SSI.........",
        "..Si..........",
        "..i...........",
    ),
    "setting_particles": (
        "..........H...",
        "....r....HIH..",
        "...rrR....H...",
        "....R.........",
        ".......H......",
        "......HHG..A..",
        ".....HHH..ALA.",
        "..I..HrRG..A..",
        "..IOHHrGO.....",
        ".IOHHROOOog...",
        ".OOOGgOoogu...",
        "..OgoGoguu....",
        "..uggGguu.....",
        "....uuu.......",
    ),
    "setting_drop_log": (
        "....IUHHg.....",
        "...IHGOOGg....",
        "..IHGOuOOGg...",
        ".IHGOOuOGg....",
        ".HGOOOGGgUu...",
        ".GGGGgGOUggu..",
        ".uuuuuguuUGgu.",
        ".IHUUOHGUUGgu.",
        ".IUGUOGUGUGgu.",
        ".SUGGIHWUUGgu.",
        ".SUGGiSUUUGgu.",
        ".SUUUGUGUUGgu.",
        "..gUgUUUgGgu..",
        "...uuuuuuuu...",
    ),
    "setting_performance": (
        ".....III......",
        "....ISSsi.....",
        "...IWSWSSsi...",
        "..ISBAAAAAasi.",
        ".ISALWWWSAAasi",
        ".ISALMWSRAAasi",
        ".ISALWSRRAAasi",
        ".ISALWRSWAAasi",
        ".ISAAaaaaAAasi",
        ".ISSSSSSSSSssi",
        "..iSBWSSWWssi.",
        "...iSSSSSSssi.",
        "....iiiiiiii..",
    ),
    "setting_auto_save": (
        "...IBBBBBBB...",
        "..IBiBiBiBbb..",
        ".IBBBBBBBBBbb.",
        ".IBPPPBBBBBbb.",
        ".IBPbPTTBBBBb.",
        ".IBPbTLATBBBb.",
        ".IBPaAILATBBb.",
        ".IBPaALaaTBBb.",
        ".IBPaAAaaTBBb.",
        ".IBPTAAAABBBb.",
        ".IBBBBBBBBBbb.",
        ".IBWWWWWWWibb.",
        "..BSSSSSSSib..",
        "...bbbbbbbb...",
    ),
    "setting_buff_sidebar": (
        ".....IWO......",
        ".....WHg......",
        ".....Iag......",
        "....ISWi......",
        "...ILWWAi.....",
        "..ILWWAASi....",
        "..ILWAAaSi....",
        "..ILAAAAai....",
        "..ISAAAAai.A..",
        "...SSaaaaiALA.",
        "....iiiii..A..",
        ".I............",
        "IAI...........",
        ".A............",
    ),
    "setting_action_ring": (
        ".........L....",
        ".........L.IA.",
        ".......LAaLAa.",
        "......LAaLAa..",
        ".....LAaIAa...",
        ".....LALAa....",
        "....LAaAa.....",
        ".....Asa......",
        "....IWsa......",
        "...IWssa......",
        "..IWssa.......",
        ".IWssa........",
        "IWssa.........",
        "Wssa..........",
    ),
    "setting_skill_tree": (
        ".....IPCC.....",
        ".....PCcC.....",
        ".....PCcc.....",
        ".....cCc......",
        ".....Ugu......",
        ".....Ugu......",
        ".....Ugu......",
        "...UUgUgUU....",
        "..UUggUGgUU...",
        "..Ug..U..gu...",
        ".LAa..U..IBb..",
        ".ATa..G..Bbb..",
        "..a..Ggu..b...",
        "....Uggu......",
    ),
    "setting_night_vision": (
        ".....CPC......",
        "....PCcCC.....",
        "....CCHgc.....",
        ".....HGO......",
        ".....Gi.......",
        "....ISWIi.....",
        "...ISPWSsi....",
        "..ISPCCCsii...",
        "..IPcCcCsii...",
        "..IPCIGCcsi...",
        "..IPIGOgcci...",
        "...PIHHgcc....",
        "....CPccc.....",
        ".....ccc......",
    ),
    "setting_status_range": (
        "..IIII........",
        ".IWWWS........",
        ".IWSSi........",
        ".IW..SSSSSS...",
        ".IW...WWSSi...",
        ".IW....Sssi...",
        ".IW..IISSSSi..",
        ".IW.IWWWWWSSi.",
        ".IWIWSiiiSSSi.",
        ".IWSi....SSSi.",
        ".IWgHHGGGOggi.",
        ".IWGoGoGoGooi.",
        "..Sgoooooooi..",
        "...iiiiiiiii..",
    ),
    "setting_dps": (
        "......HGG.....",
        "......GOg..R..",
        "....IHgggg.Rr.",
        "...IHGGGOggR..",
        "..IHGGGggggo..",
        ".IHGuuuuugGgu.",
        ".IHGurruugGgu.",
        ".IHGurRrRgGgu.",
        ".IHGuuRrrgGgu.",
        ".IHGuuuuuOgou.",
        "..HGOGOGGggu..",
        "...GOGggGGgu..",
        "....ggggggu...",
        ".....uuuuu....",
    ),
    "setting_save_guide": (
        "...IHGggggg...",
        "..IHGGGGGgGU..",
        ".IHGbBBBBHGgU.",
        ".HGIBbbbBHGgu.",
        ".HGPpCPPPHGgu.",
        ".HGPCCCCPHGgu.",
        ".HGPpCPPPHGgu.",
        ".HGbBBBBBHGgu.",
        ".HGHHHHHHGGgu.",
        ".HGHHHMGHHGgu.",
        ".HGHHMMmHHgou.",
        ".HGHHHMmHHgou.",
        "..GWWWWWWUuu..",
        "...UUUUUUu....",
    ),
    "setting_action_ring_cooldown": (
        "......HGG.....",
        "......Gog.....",
        "....LHGGG.....",
        "...LAAaaaa....",
        "..IAALAAAaa...",
        ".IAAaiAiaAaa..",
        ".ILAaIGiaAaai.",
        ".ILAaIGHiAaai.",
        ".ILAaIHHiaaai.",
        ".ILAaiiiiAaai.",
        "..IAAAAAaaai..",
        "...AAAAaaai...",
        "....aaaaii....",
        ".....iiii.....",
    ),
    "setting_equipped_skill_cooldown": (
        "..IPPPPCC.....",
        ".IPCCCCCcp....",
        ".IPICCCCCcp...",
        ".PCICCPCccp...",
        ".PCICCPPccp...",
        ".PCICCCCIGGgg.",
        ".PCCCCCIHWWSg.",
        ".PCCCCC.HWGg..",
        ".PCCCCC..Gg...",
        ".PCCCcc..Gg...",
        ".Cccccc.HGgg..",
        "..ccccc.HHOGg.",
        "...ccc.IHGGgg.",
        ".......ggggg..",
    ),
    "setting_world_name": (
        ".........IW...",
        "..II....IWHG..",
        ".IWHGUUIWHGGu.",
        ".IHAmGUHAmGGu.",
        ".IHAAMUHAmGGu.",
        ".IHAMMUHAAMGu.",
        ".IHMmmAHAAAGu.",
        ".IHAMAAHBBAGu.",
        ".IHBBAAHBBAGu.",
        ".IHBBAAUBABGu.",
        ".IHAAGGUUHHGu.",
        ".IHGGU..UgGgu.",
        "..Ggu....ugu..",
        "...u..........",
    ),
    "setting_area_name": (
        ".....IHGG.....",
        "....IHGGGg....",
        "....Goggog....",
        "...IHGGGGgg...",
        "..IHGuuuuGgg..",
        ".IHGUSiSUUGgu.",
        ".IHGSIRRiSGgu.",
        ".IHGUWRrSUGgu.",
        ".IHGSBSiUUGgu.",
        ".IHGSbUSUSGgu.",
        "..HGGuuuuGgu..",
        "...GGGGGGgu...",
        "....gggggu....",
        ".....uuuu.....",
    ),
    "setting_ping": (
        ".........r....",
        ".......r..R...",
        ".....r..R.....",
        "......R.......",
        "......r.......",
        ".....rRR......",
        ".....rRR......",
        "......Gg......",
        ".....IGg......",
        "....IGGg......",
        "...IGggo......",
        "..ISWSSsSi....",
        ".IWSSsSSssi...",
        ".iiiiiiiiii...",
    ),
    "setting_screen_hud": (
        "...IIIIIIIII..",
        "..IISSWSSSSSi.",
        ".IWLaAAaaAasi.",
        ".ISALAAaaAasi.",
        ".SSALAAagHasi.",
        ".SSAAAaagGasi.",
        ".SSAAAaaaaasi.",
        ".SSAAaaaaiaSi.",
        ".SSSSSSSSSsSi.",
        "..iiiiiiiiii..",
        ".....SSsi.....",
        "....ISSssi....",
        "...WWSSsssi...",
        "...iiiiiiii...",
    ),
    "skill_unavailable": (
        "....rrr.....", "...rRRRr....", "..rR...Rr...", "..rR...Rr...",
        "..rR...Rr...", ".rrrrrrrrr..", "rRRRRRRRRRR.", "rRRRWWRRRRR.",
        "rRRRWWRRRRR.", "rRRRRWRRRRR.", ".RRRRRRRRR..", "..RRRRRRR...",
    ),
}


def mirror(pattern: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(row[::-1] for row in pattern)


PATTERNS["previous"] = mirror(PATTERNS["next"])
PATTERNS["down"] = PATTERNS["up"][::-1]
ROLES = (*PATTERNS, *FILLER_ROLES, *PICTOGRAMS)


def chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def png(pixels: list[list[tuple[int, int, int, int]]]) -> bytes:
    height, width = len(pixels), len(pixels[0])
    raw = b"".join(b"\0" + bytes(c for rgba in row for c in rgba) for row in pixels)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def scale(pixels: list[list[tuple[int, int, int, int]]], factor: int) -> list[list[tuple[int, int, int, int]]]:
    return [[color for color in row for _ in range(factor)] for row in pixels for _ in range(factor)]


def design(role: str, source: dict) -> list[list[tuple[int, int, int, int]]]:
    if role in FILLER_ROLES:
        # The item still occupies the slot; only the underlying GUI should show.
        return [[COLORS["."]] * 16 for _ in range(16)]

    recessed = role in RECESSED_ROLES
    pictogram = role in PICTOGRAMS
    if pictogram and not recessed:
        pixels = [[COLORS["."]] * 16 for _ in range(16)]
    else:
        palette = RECESSED_COLORS if recessed else BUTTON_COLORS
        pixels = [[palette[c] for c in row] for row in source["rows"]]

    pattern = PICTOGRAMS[role] if pictogram else PATTERNS[role]
    max_size = 14 if pictogram else 10
    width = max(map(len, pattern))
    if len(pattern) > max_size or width > max_size:
        raise ValueError(f"Glyph outside inner {max_size}x{max_size} design area: {role}")
    left = (16 - width) // 2
    top = (16 - len(pattern)) // 2

    # A contour keeps free-standing symbols legible over both editions' slot colors.
    # Framed controls keep their smaller, offset shadow.
    outline = ((0, -1), (-1, 0), (1, 0), (0, 1), (1, 1)) if pictogram else ((1, 1),)
    for y, row in enumerate(pattern):
        for x, c in enumerate(row):
            if c != ".":
                for dx, dy in outline:
                    pixels[top + y + dy][left + x + dx] = COLORS["K"]
    for y, row in enumerate(pattern):
        for x, c in enumerate(row):
            if c != ".":
                pixels[top + y][left + x] = COLORS[c]
    if not pictogram and not recessed:
        # Highlight the upper and left rim of navigation and confirmation buttons.
        for i in range(3, 13):
            pixels[13][i] = BUTTON_EDGE_SHADE
            pixels[i][13] = BUTTON_EDGE_SHADE
    return pixels


def preview(path: Path, designs: dict[str, list]) -> None:
    # Each cell shows native16, distributed32 and magnified64. A light strip
    # also exposes transparency and contour quality against a bright surface.
    width, cell_w, cell_h = 980, 140, 108
    height = ((len(ROLES) + 6) // 7) * cell_h
    canvas = [[(32, 39, 46, 255)] * width for _ in range(height)]
    for i, role in enumerate(ROLES):
        cx, cy = (i % 7) * cell_w, (i // 7) * cell_h
        for factor, ox, oy in ((1, 6, 8), (2, 27, 8), (4, 65, 8)):
            art = scale(designs[role], factor)
            for y, row in enumerate(art):
                for x, color in enumerate(row):
                    if color[3]:
                        canvas[cy + oy + y][cx + ox + x] = color
        for y in range(80, 104):
            for x in range(4, 136):
                canvas[cy + y][cx + x] = (213, 216, 200, 255)
        art = designs[role]
        for y, row in enumerate(art):
            for x, color in enumerate(row):
                if color[3]:
                    canvas[cy + 84 + y][cx + 9 + x] = color
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png(canvas))
    path.with_suffix(".json").write_text(json.dumps({"columns": 7, "cell_width": cell_w,
        "cell_height": cell_h, "roles": list(ROLES)}, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Compare existing art without writing")
    parser.add_argument("--preview", type=Path, help="Optional untracked review sheet path")
    args = parser.parse_args()
    source = json.loads(SOURCE.read_text(encoding="utf-8-sig"))
    if source["width"] != 16 or source["height"] != 16 or len(source["rows"]) != 16:
        raise ValueError("Source relief must be 16x16")
    mismatches = []
    designs = {}
    for role in ROLES:
        art = design(role, source)
        designs[role] = art
        data = png(scale(art, 2))
        for directory in (JAVA, BEDROCK):
            path = directory / f"gui_{role}.png"
            if args.check:
                if not path.exists() or path.read_bytes() != data:
                    mismatches.append(str(path.relative_to(ROOT)))
            else:
                directory.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if args.preview:
        preview(args.preview, designs)
    if mismatches:
        print("GUI regeneration differs:\n" + "\n".join(mismatches))
        return 1
    digest = hashlib.sha256(b"".join(png(scale(designs[r], 2)) for r in ROLES)).hexdigest()
    print(f"GUI art {'verified' if args.check else 'generated'}: {len(ROLES)} roles, both editions; SHA256 {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
