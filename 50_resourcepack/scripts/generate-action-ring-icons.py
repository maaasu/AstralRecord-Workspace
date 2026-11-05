#!/usr/bin/env python3
"""Draw the action ring skill sprites and export their shared numeric catalog.

Run from any directory with Python 3. Check in the three generated outputs
together. Tile 0 is transparent. IDs 1 through 254 address the same artwork
in the Java atlas and the Bedrock bundle.
"""

import argparse
import base64
import json
from pathlib import Path
import re
import struct
import zlib


WORKSPACE = Path(__file__).resolve().parents[2]
PACK = WORKSPACE / "50_resourcepack"
CATALOG = WORKSPACE / "10_plugin/AstralRecord/src/main/resources/action-ring-icons.json"
ATLAS = PACK / "assets/astralrecord/textures/item/action_ring_icons.png"
BEDROCK_TILES = PACK / "bedrock/action-ring-icon-tiles.json"
CELL = 32
ATLAS_SIZE = 512
MAX_ID = 254

# Each palette progresses from deep shadows to bright glints.
PALETTES = {
    "amber": ("663353", "e76c43", "ffab56", "fff2b0"),
    "bronze": ("513650", "b87c57", "eab77e", "ffefc6"),
    "ember": ("661e42", "e44255", "ff8059", "ffd490"),
    "gold": ("725034", "e5a52c", "ffe565", "fffbce"),
    "ice": ("24527c", "469dd5", "8de9ff", "ebffff"),
    "jade": ("245754", "3aaf77", "84ee9b", "e3ffcd"),
    "lilac": ("4b4483", "9170cf", "cfb2ff", "fff0ff"),
    "mint": ("235c68", "36b7a6", "8df2cf", "ebffde"),
    "pearl": ("4f577c", "a4afd0", "e2eaf8", "ffffff"),
    "rose": ("66345f", "d668a9", "ffa6d8", "fff0f8"),
    "sky": ("353775", "627cdd", "8fbfff", "edf9ff"),
    "violet": ("402b73", "8858c9", "bd88ff", "f5dcff"),
}

OUTLINE = "161427"
ACCENT_PALETTES = {
    "blade": "pearl",
    "bolt": "gold",
    "cross": "jade",
    "eye": "rose",
    "feather": "mint",
    "flame": "amber",
    "heart": "rose",
    "ice": "ice",
    "leaf": "jade",
    "meteor": "amber",
    "moon": "lilac",
    "phoenix": "amber",
    "shield": "ice",
    "spark": "gold",
    "star": "gold",
}


# Each group combines an authored silhouette and palette, optionally adding an accent.
# Catalog IDs are retained on regeneration. Add new skills below and rerun this script.
ART = """
administrator
just_dodge dash mint
shield_recharge shield ice bolt
adventurer
astral_edge twin ice star
blast_arrow arrow amber burst
lightning_bolt bolt gold spark
mana_burst burst sky star
meditation flame lilac ring
quick_shot bow bronze
smash axe ice impact
archer
air_shift feather mint swirl
heal_arrow_alpha arrow jade cross
starweave_followup bow sky star
archmage
aegis_circle circle ice shield
astral_ray ray sky star
astral_spear spear violet star
bind_circle circle rose eye
celestial_circle circle sky star
clear_circle circle pearl feather
gravity_nova vortex violet ring
heal_circle circle jade cross
meteor_circle circle amber meteor
orbital_volley orbit rose
phoenix_resonance phoenix amber
reborn_protect_circle circle gold phoenix
renewal_circle circle mint leaf
repulsion_circle circle lilac impact
starfall meteor sky star
stasis_circle circle violet ice
vigor_circle circle gold bolt
hunter
arrow_rain volley bronze
build_up bow gold impact
crash_arrow arrow ice shield
fade_shot volley pearl swirl
heal_arrow arrow mint heart
spell_step dash lilac orb
mage
arcane_flow book violet swirl
blink portal rose
fireball meteor amber
frost_ball orb ice ice
frost_blizzard vortex ice ice
heal_aura burst jade cross
sparking vortex gold bolt
melee
dual_crest crest bronze
paladin
blessed_lance spear pearl ring
defense_conversion crest violet ring
divine_chaser ray gold star
fortress castle sky
guard_convert shield sky swirl
guardian_bulwark shield pearl star
guardian_chain chain sky
guardian_protect shield ice heart
guardian_renewal shield jade leaf
holy_control pillar gold swirl
holy_field circle pearl pillar
holy_reprieve pillar pearl cross
holy_smash slash pearl burst
holy_smite pillar amber bolt
shield shield gold ring
shield_bash shield bronze impact
shield_impact shield violet burst
phantom_archer
curse_weaving chain violet eye
dread_bloom bloom violet eye
echo_volley volley rose orb
eclipse_shot bow violet moon
mist_retreat dash pearl moon
shadow_stitch eye violet arrow
soul_pierce spear mint eye
spectral_attunement arrow violet swirl
spectral_sentry bow mint cross
wraith_step dash violet arrow
sharpshooter
fire_arrow arrow amber flame
glacial_trap bloom ice shield
ice_arrow arrow ice ice
inheritance_mastery bow jade arrow
phantom_shot volley violet eye
piercing_line ray pearl arrow
ricochet_arrow arrow gold bolt
spreading_ambition volley jade leaf
thermal_break twin amber ice
swordmaster
blade_tempest vortex pearl blade
crescent_duet moon lilac blade
crimson_drive dash ember flame
flame_lotus bloom ember flame
frost_bloom ice ice blade
gale_reaper slash mint feather
heartseeker blade gold heart
mirage_retreat dash ice blade
overdrive flame ember bolt
revenge_accel dash gold blade
riposte slash sky shield
seijaku_issen blade ice ray
serene_mend bloom jade ring
star_cleave slash gold star
thunder_line bolt gold blade
trinity_edge trinity violet blade
vortex_edge vortex violet blade
swordsman
bastion_strike crest ice impact
challenging_roar horn bronze
exept_stamp axe bronze impact
flame_rush twin ember flame
shield_activate shield jade bolt
shield_drain slash ice orb
wizard
arcane_lance spear violet impact
arcane_overload burst violet orb
burn_meteor_strike meteor ember flame
cinder_lance spear amber flame
elemental_ball orb violet star
elemental_prism crystal lilac ray
emulate_spark volley gold spark
lightning_strike bolt amber impact
mana_siphon vortex sky orb
mana_veil crest lilac orb
meteor meteor ember impact
prism_condition circle rose crystal
prismatic_dart crystal rose arrow
rime_nova burst ice ice
self_heal heart rose cross
spectral_orrery orbit sky
storm_chain chain gold bolt
triad_convergence trinity rose orb
"""

# Sprites are layers of opaque pixel polygons in a 24×24 drawing space.
# Layer names refer to shadows (s), base (b), light (l) and shine (h).
SHAPES = {
    "blade": (
        ("s", "1,22 4,23 11,15 13,17 15,15 8,8 6,10 9,13"),
        ("b", "9,12 18,3 23,1 21,7 12,15"),
        ("l", "9,12 18,3 23,1 12,13"),
        ("h", "12,10 18,4 20,4 15,9"),
        ("b", "7,9 14,15 12,17 6,11"),
    ),
    "twin": (
        ("s", "1,20 1,15 7,9 15,5 21,4 14,9 8,15 7,21 12,19 18,14 22,8 23,13 19,19 12,23 5,23"),
        ("b", "2,19 2,15 8,9 16,6 21,4 13,11 7,17 6,21"),
        ("l", "2,16 8,10 16,6 13,9 8,12 4,17"),
        ("b", "6,22 12,20 19,14 22,8 22,14 18,19 12,22"),
        ("h", "13,19 18,15 21,11 20,15 17,18"),
    ),
    "slash": (
        ("s", "1,19 5,22 11,22 17,19 21,14 23,6 19,10 17,13 10,17 4,18"),
        ("b", "3,19 10,19 18,14 22,7 22,11 19,17 15,20 10,22 5,21"),
        ("l", "3,19 10,18 17,14 22,7 20,13 16,17 10,20 5,20"),
        ("h", "7,19 10,18 16,15 14,17 10,19"),
        ("b", "1,14 6,13 9,10 7,14 4,16"),
    ),
    "spear": (
        ("s", "1,22 3,23 17,9 16,7"),
        ("b", "3,20 15,8 16,9 4,21"),
        ("b", "12,6 18,5 23,1 20,9 17,12 12,12"),
        ("l", "13,7 18,5 22,2 19,7 14,10"),
        ("h", "15,6 20,4 16,8"),
        ("l", "8,11 13,11 12,16 9,16"),
    ),
    "arrow": (
        ("s", "1,22 2,17 5,14 8,15 17,6 15,4 23,1 21,10 19,8 10,17 10,19 6,23"),
        ("b", "2,21 3,17 6,15 8,16 18,6 19,8 9,18 9,20 6,22"),
        ("l", "5,19 18,6 20,6 7,20"),
        ("l", "16,4 22,2 21,8 19,6"),
        ("h", "19,4 22,2 20,6"),
    ),
    "volley": (
        ("s", "1,9 8,2 10,1 10,5 3,12"),
        ("l", "2,9 9,2 9,5 3,11"),
        ("s", "3,16 13,6 17,5 16,9 6,19"),
        ("b", "4,16 14,6 16,6 15,9 6,18"),
        ("h", "8,13 15,6 16,6 10,13"),
        ("s", "10,20 20,10 23,9 22,13 13,22"),
        ("l", "11,20 21,10 22,10 21,13 13,21"),
    ),
    "bow": (
        ("s", "3,4 8,3 20,15 20,20 16,23 10,22 5,19 2,15 1,9"),
        ("b", "3,4 7,4 4,8 4,13 7,17 11,20 16,21 19,17 20,18 19,22 15,23 8,21 3,16 1,10"),
        ("l", "3,6 5,5 3,10 5,15 9,19 14,21 12,22 7,20 3,16 2,11"),
        ("h", "7,5 19,17 18,18 6,6"),
        ("l", "5,18 19,4 16,3 22,1 21,7 20,5 6,19"),
    ),
    "axe": (
        ("s", "1,20 13,8 16,11 4,23"),
        ("b", "2,20 14,8 15,10 4,21"),
        ("s", "8,3 14,1 20,3 23,8 22,14 19,17 17,12 13,9 8,8"),
        ("b", "9,3 14,2 20,4 22,8 22,13 19,16 18,11 13,8 9,7"),
        ("l", "10,3 15,2 20,4 22,8 22,13 20,15 20,9 18,6 14,4"),
        ("h", "16,3 20,4 21,7 19,6"),
    ),
    "bolt": (
        ("s", "13,1 22,1 15,9 21,10 5,23 9,13 3,12"),
        ("b", "13,2 20,2 13,10 19,10 7,21 11,12 5,12"),
        ("l", "13,2 18,2 11,11 17,11 8,19 12,12 6,11"),
        ("h", "13,3 16,3 11,9 8,10"),
    ),
    "shield": (
        ("s", "2,4 11,1 21,4 22,12 19,18 12,23 5,19 2,14"),
        ("b", "3,5 11,2 20,5 21,12 18,17 12,22 6,18 3,13"),
        ("l", "3,5 11,2 20,5 19,7 11,4 5,6 5,14 3,12"),
        ("s", "11,5 18,7 18,13 15,17 11,19 7,16 6,13 6,7"),
        ("b", "11,6 12,6 12,18 9,17 7,13 7,8"),
        ("h", "11,8 13,11 16,12 13,13 11,16 10,13 8,12 10,11"),
    ),
    "crest": (
        ("s", "2,6 11,3 21,6 20,15 17,19 11,23 5,19 2,13"),
        ("b", "3,7 11,4 20,7 19,14 16,18 11,21 6,18 3,13"),
        ("l", "3,7 11,4 20,7 18,8 11,6 5,8 5,14 3,12"),
        ("s", "7,17 6,15 10,11 10,5 16,1 16,7 13,12 16,14 15,16 11,14"),
        ("h", "11,6 15,2 15,7 10,13 9,12"),
        ("l", "7,12 15,15 14,16 7,14"),
    ),
    "castle": (
        ("s", "1,3 6,3 6,7 9,7 9,3 14,3 14,7 17,7 17,3 22,3 22,22 1,22"),
        ("b", "2,4 5,4 5,8 10,8 10,4 13,4 13,8 18,8 18,4 21,4 21,21 2,21"),
        ("l", "2,4 4,4 4,9 10,9 10,5 12,5 12,9 18,9 18,5 20,5 20,11 2,11"),
        ("s", "8,21 8,15 11,12 14,15 14,21"),
        ("h", "3,12 7,12 7,13 3,13"),
        ("h", "16,16 20,16 20,17 16,17"),
    ),
    "circle": (
        ("s", "1,14 3,8 8,4 15,4 20,7 22,13 21,17 17,21 7,21 2,18"),
        ("b", "2,14 4,8 8,5 15,5 20,9 21,14 19,18 16,20 8,20 4,18"),
        ("l", "3,14 5,9 9,6 14,6 18,8 20,12 18,11 15,8 9,8 6,10 5,15 7,17 13,19 9,19 5,18"),
        ("h", "2,14 5,13 6,15 4,16"),
        ("h", "16,17 20,14 20,16 17,19"),
        ("b", "7,13 11,9 16,13 12,17"),
        ("h", "10,13 12,11 14,13 12,15"),
    ),
    "portal": (
        ("s", "3,9 6,4 11,2 17,3 21,7 22,15 18,21 12,23 7,20 4,15"),
        ("b", "4,9 7,5 11,3 17,4 20,7 21,15 17,20 12,22 8,19 5,15"),
        ("l", "5,9 7,5 11,3 16,4 18,6 13,5 9,6 7,10 8,15 6,14"),
        ("s", "8,11 11,7 16,7 18,10 18,15 15,18 11,19 9,16"),
        ("h", "4,8 7,5 9,5 7,7"),
        ("l", "9,18 12,20 17,18 19,15 18,19 13,21"),
        ("b", "10,14 13,10 13,13 16,13 13,17 13,14"),
    ),
    "moon": (
        ("s", "15,1 20,2 14,5 10,10 10,14 13,17 18,17 23,12 22,18 18,22 12,23 6,21 2,16 2,10 5,5 10,2"),
        ("b", "13,2 18,2 13,5 9,10 9,15 12,18 18,18 22,14 21,18 17,22 12,22 6,20 3,16 3,10 6,5"),
        ("l", "13,2 17,2 12,4 7,8 6,13 8,17 12,19 9,19 5,17 4,14 4,10 7,6"),
        ("h", "10,4 13,3 9,6 6,9 6,8"),
        ("h", "13,20 17,19 20,17 18,20 16,21"),
    ),
    "heart": (
        ("s", "1,8 4,4 8,3 12,6 15,3 19,3 22,6 23,10 21,14 12,22 4,15 1,12"),
        ("b", "2,8 5,4 8,4 12,7 15,4 19,4 21,6 22,10 20,14 12,21 5,15 2,11"),
        ("l", "3,8 5,5 8,5 12,8 15,5 19,5 20,6 17,6 13,10 10,10 7,7 5,8 4,11"),
        ("h", "5,7 7,6 8,7 6,9 5,9"),
        ("s", "11,18 17,15 21,10 20,14 12,21"),
    ),
    "flame": (
        ("s", "12,1 16,4 16,9 19,7 22,12 22,17 18,22 10,23 4,20 2,15 5,8 7,12 9,10 10,5"),
        ("b", "12,2 15,5 15,10 19,8 21,12 21,17 17,21 10,22 5,19 3,15 5,10 7,14 10,11 11,6"),
        ("l", "11,21 7,18 7,14 11,13 13,8 15,13 18,12 19,16 16,20"),
        ("h", "11,20 10,17 13,14 13,17 16,16 15,19"),
    ),
    "meteor": (
        ("s", "23,1 21,9 16,13 16,18 13,21 8,23 4,22 1,19 1,14 4,10 9,9 15,4 14,8"),
        ("b", "22,2 20,9 15,13 13,11 17,7 14,8"),
        ("l", "22,2 20,7 14,11 12,11"),
        ("b", "8,10 13,11 16,14 16,17 13,20 8,22 4,21 2,18 2,14 4,11"),
        ("s", "8,12 12,12 14,14 13,17 10,18 8,20 5,19 4,17 4,14"),
        ("l", "4,12 8,11 9,13 6,16 3,16"),
        ("h", "4,13 7,12 7,13 5,14"),
        ("b", "10,15 12,15 11,17 9,17"),
    ),
    "dash": (
        ("s", "2,7 12,7 9,3 14,3 23,12 14,21 9,21 12,17 2,17 7,12"),
        ("b", "3,8 13,8 11,4 14,4 22,12 14,20 11,20 14,16 4,16 8,12"),
        ("l", "4,8 15,9 12,5 14,5 21,12 18,13 14,11 7,11"),
        ("h", "13,6 16,8 19,11 17,11"),
        ("l", "1,12 5,12 3,14 1,14"),
        ("b", "3,19 8,19 7,20 3,20"),
    ),
    "feather": (
        ("s", "2,21 6,16 5,12 8,7 15,3 22,2 22,8 19,15 13,18 8,18 4,23"),
        ("b", "3,21 7,16 6,12 9,7 15,4 21,3 21,8 18,14 13,17 8,17 4,22"),
        ("l", "7,16 9,9 15,5 21,3 19,8 14,10 17,10 13,13 10,13"),
        ("h", "9,13 15,7 19,5 18,7 12,11"),
    ),
    "ice": (
        ("s", "10,1 13,1 13,7 18,3 20,5 17,9 22,10 22,13 16,13 20,18 18,20 13,16 13,23 10,23 10,16 5,21 3,18 7,13 1,13 1,10 7,10 3,5 5,3 10,7"),
        ("b", "11,2 12,2 12,9 18,4 19,5 15,10 21,11 21,12 15,12 19,18 18,19 12,14 12,22 11,22 11,14 5,20 4,18 9,12 2,12 2,11 9,11 4,5 5,4 11,9"),
        ("l", "6,6 12,10 17,6 14,11 18,17 12,14 6,17 10,12"),
        ("h", "10,10 12,9 14,11 12,14 10,13"),
    ),
    "vortex": (
        ("s", "6,2 14,1 20,4 23,9 23,15 19,21 12,23 6,21 2,17 1,12 3,8 8,6 13,7 16,10 15,14 12,16 9,15 8,12 11,11 11,13 13,12 12,10 8,10 5,12 5,16 8,19 13,19 18,16 19,11 17,7 12,4"),
        ("b", "8,2 14,2 19,5 22,9 22,15 18,20 12,22 6,20 3,17 2,12 4,8 8,7 13,8 15,10 14,14 12,15 10,14 9,12 11,12 12,13 14,11 12,9 8,9 4,12 4,16 8,20 13,20 18,17 20,12 18,7 13,4"),
        ("l", "10,2 14,2 19,5 21,9 21,14 18,18 14,20 10,20 15,18 18,15 19,11 17,7 13,4"),
        ("h", "4,9 8,7 11,8 8,8 5,10 3,13"),
    ),
    "orbit": (
        ("s", "2,7 7,3 14,1 20,3 22,6 22,11 19,17 13,21 7,22 3,20 1,16 2,13 4,13 4,17 7,19 12,18 17,15 20,10 20,7 17,5 13,4 8,5"),
        ("b", "3,7 8,4 14,2 19,4 21,6 21,11 18,17 13,20 7,21 4,19 2,16 3,14 3,17 7,20 13,19 18,15 20,10 20,6 17,4 13,3"),
        ("l", "9,19 14,18 18,15 20,11 21,11 18,17 14,20 9,21"),
        ("b", "8,10 11,7 15,8 17,11 15,15 11,16 8,14"),
        ("l", "9,10 11,8 14,9 15,10 12,12 9,13"),
        ("h", "10,10 11,9 13,9 11,11"),
        ("h", "3,9 5,7 7,9 5,11"),
    ),
    "orb": (
        ("s", "7,3 15,2 20,5 22,10 21,16 17,21 10,22 4,20 1,15 1,9"),
        ("b", "7,4 15,3 19,6 21,10 20,16 16,20 10,21 5,19 2,15 2,9"),
        ("l", "7,4 14,4 17,6 15,9 8,13 4,13 3,10"),
        ("h", "7,5 12,5 11,7 6,9 4,9"),
        ("s", "19,11 20,14 18,18 14,20 8,20 6,18 11,18 16,15"),
        ("l", "15,17 18,15 16,18 12,19"),
    ),
    "crystal": (
        ("s", "10,1 15,2 22,9 19,17 11,23 3,17 1,10"),
        ("b", "10,2 15,3 21,9 18,17 11,22 4,17 2,10"),
        ("l", "10,2 13,4 10,11 4,14 2,10"),
        ("h", "10,3 11,4 8,9 4,10"),
        ("s", "13,4 20,9 18,16 11,22 12,13"),
        ("l", "12,13 19,10 17,15 13,18"),
        ("b", "4,15 10,12 10,20 6,18"),
    ),
    "burst": (
        ("s", "11,1 14,7 20,3 19,9 23,11 19,14 21,20 15,18 12,23 9,18 3,21 5,15 1,12 6,9 3,4 9,7"),
        ("b", "11,2 14,8 19,4 18,10 22,11 18,14 20,19 14,17 12,22 10,17 4,20 6,14 2,12 7,10 4,5 10,8"),
        ("l", "11,5 13,10 16,8 16,11 19,12 15,13 16,17 13,15 12,19 10,15 7,17 8,14 5,12 9,11 7,8 11,10"),
        ("h", "11,9 13,11 15,12 13,13 12,16 10,13 8,12 10,11"),
    ),
    "cross": (
        ("s", "8,2 15,2 15,8 21,8 21,15 15,15 15,21 8,21 8,15 2,15 2,8 8,8"),
        ("b", "9,3 14,3 14,9 20,9 20,14 14,14 14,20 9,20 9,14 3,14 3,9 9,9"),
        ("l", "9,3 12,3 12,11 20,11 20,12 12,12 12,19 10,19 10,12 3,12 3,10 9,10"),
        ("h", "10,4 11,4 11,11 4,11 4,10 10,10"),
    ),
    "star": (
        ("s", "11,1 15,7 22,10 16,14 12,22 8,15 1,11 8,7"),
        ("b", "11,2 14,8 21,10 15,13 12,21 9,14 2,11 9,8"),
        ("l", "11,3 12,10 19,10 12,13 12,19 10,13 4,11 10,10"),
        ("h", "11,7 12,10 15,11 12,12 11,15 10,12 8,11 10,10"),
    ),
    "eye": (
        ("s", "1,11 5,6 10,4 15,4 20,7 23,11 19,16 14,19 9,19 4,16"),
        ("b", "2,11 6,7 10,5 15,5 19,8 22,11 18,15 14,18 9,18 5,15"),
        ("l", "3,11 7,8 11,6 15,6 19,9 16,8 11,8 6,10 4,12"),
        ("s", "11,7 15,8 17,11 15,15 11,17 8,14 7,11"),
        ("h", "10,9 12,8 14,9 12,12 10,11"),
    ),
    "book": (
        ("s", "1,4 6,3 11,6 17,3 22,4 22,18 17,18 11,22 6,19 1,19"),
        ("b", "2,5 6,4 11,7 17,4 21,5 21,17 16,17 11,21 6,18 2,18"),
        ("l", "3,6 6,5 10,8 10,18 6,16 3,16"),
        ("l", "12,8 17,5 20,6 20,16 16,16 12,18"),
        ("h", "4,6 6,6 9,8 9,9 6,8 4,8"),
        ("h", "13,8 17,6 19,6 19,7 16,8 13,10"),
        ("s", "4,12 6,12 9,14 9,15 6,14 4,14"),
        ("s", "13,13 17,11 19,11 19,12 16,13 13,15"),
    ),
    "bloom": (
        ("s", "10,22 4,18 1,11 6,12 4,5 10,8 12,1 15,8 21,5 19,12 23,11 21,17 16,21"),
        ("b", "10,21 5,17 2,12 8,14 5,6 11,10 12,2 15,10 20,6 17,14 22,12 20,17 15,20"),
        ("l", "12,19 9,15 10,11 12,4 14,10 15,14"),
        ("h", "12,8 13,13 12,16 11,13"),
        ("l", "3,13 8,15 11,19 7,18"),
        ("l", "21,13 19,17 14,19 17,15"),
    ),
    "phoenix": (
        ("s", "11,7 8,4 3,3 1,1 2,8 6,13 10,14 9,17 5,22 11,21 13,23 15,20 19,21 16,17 15,13 20,10 23,3 19,5 15,5 14,4"),
        ("b", "11,8 8,5 4,4 2,2 3,8 7,12 11,13 10,17 7,21 11,20 13,22 15,19 17,20 15,17 14,12 19,10 22,4 19,6 16,6 14,5"),
        ("l", "2,3 6,6 10,7 12,11 8,10 5,8"),
        ("l", "14,8 18,7 21,5 19,9 15,11"),
        ("h", "12,8 14,7 15,8 13,10 13,15 12,17 11,15"),
    ),
    "pillar": (
        ("s", "6,21 6,18 8,16 8,8 6,6 6,3 16,3 18,6 16,8 16,16 18,18 18,21"),
        ("b", "7,20 7,18 9,16 9,8 7,6 7,4 16,4 17,6 15,8 15,16 17,18 17,20"),
        ("l", "7,4 16,4 16,5 8,5"),
        ("l", "9,8 12,8 12,17 9,17"),
        ("h", "9,9 10,9 10,16 9,16"),
        ("l", "7,18 15,18 16,19 7,19"),
    ),
    "chain": (
        ("s", "1,14 5,10 9,10 10,11 11,10 10,8 10,5 14,1 18,1 22,5 22,9 18,13 14,13 13,12 12,13 13,15 13,18 9,22 5,22 1,18"),
        ("b", "2,14 5,11 8,11 11,14 12,15 12,18 9,21 5,21 2,18"),
        ("b", "11,5 14,2 18,2 21,5 21,9 18,12 14,12 11,9"),
        ("s", "4,15 6,13 8,13 10,15 10,17 8,19 6,19 4,17"),
        ("s", "13,6 15,4 17,4 19,6 19,8 17,10 15,10 13,8"),
        ("l", "2,15 5,12 8,12 8,13 6,13 3,16 3,18 2,17"),
        ("l", "11,6 14,3 18,3 18,4 15,4 12,7 12,8 11,8"),
        ("h", "7,14 14,7 16,9 9,16"),
    ),
    "horn": (
        ("s", "2,17 4,14 9,15 14,12 17,8 17,3 21,4 23,9 22,13 16,19 9,21 4,20"),
        ("b", "3,17 5,15 9,16 15,13 18,9 18,4 21,5 22,9 21,13 16,18 9,20 4,19"),
        ("l", "5,16 10,17 16,14 20,9 20,7 21,7 21,11 18,15 15,17 9,19 5,18"),
        ("h", "8,17 11,17 15,15 13,17 9,18"),
        ("s", "18,4 21,5 22,8 20,9 18,7"),
    ),
    "ray": (
        ("s", "1,19 5,14 12,10 19,2 23,1 22,5 15,14 9,19 4,22"),
        ("b", "2,19 6,15 13,10 20,3 22,2 21,5 14,14 9,18 4,21"),
        ("l", "3,19 10,14 15,9 21,3 18,8 15,12 10,16"),
        ("h", "5,18 10,15 15,10 19,6 15,11 11,16"),
        ("b", "3,12 8,8 10,4 10,8 7,11"),
        ("l", "12,19 17,16 20,12 19,16 15,19"),
    ),
    "trinity": (
        ("s", "10,21 8,15 1,10 1,4 7,6 10,12 9,7 12,1 15,7 14,12 17,6 23,4 23,10 16,15 14,21 12,23"),
        ("b", "10,20 9,14 2,10 2,5 7,7 10,13 10,7 12,2 14,7 14,13 17,7 22,5 22,10 15,14 14,20 12,22"),
        ("l", "11,19 11,8 12,4 13,8 13,19 12,21"),
        ("amber", "2,6 6,8 9,13 6,12 3,10"),
        ("ice", "22,6 18,8 15,13 18,12 21,10"),
        ("h", "12,7 13,13 12,17 11,13"),
    ),
    "leaf": (
        ("s", "2,19 5,15 5,11 8,7 13,4 21,2 21,10 18,16 14,19 8,18 4,22"),
        ("b", "3,19 6,15 6,11 9,7 14,4 20,3 20,10 17,16 14,18 8,17 4,21"),
        ("l", "7,14 8,10 13,6 19,4 17,8 13,11 11,10 11,12"),
        ("h", "7,15 12,11 17,7 15,10 10,14"),
    ),
    "ring": (
        ("s", "8,2 15,2 21,7 22,14 18,20 13,22 7,21 2,16 1,9"),
        ("b", "8,3 15,3 20,7 21,14 17,19 13,21 7,20 3,16 2,9"),
        ("void", "9,7 14,7 17,10 17,14 14,17 9,17 6,14 6,10"),
        ("l", "3,9 8,4 14,4 17,6 14,6 9,6 5,10 4,13"),
        ("h", "6,7 9,5 12,5 10,6 8,6"),
    ),
    "impact": (
        ("s", "1,17 8,11 3,5 8,6 11,10 16,2 20,4 16,11 23,9 23,13 16,16 18,21 13,22 10,18 5,21"),
        ("b", "2,17 10,12 5,6 8,7 11,11 16,3 19,5 15,12 22,10 22,13 15,15 17,21 14,21 11,17 5,20"),
        ("h", "6,17 11,13 16,7 14,13 18,12 14,15 14,18 11,16"),
    ),
    "seal": (
        ("s", "7,2 16,2 21,7 21,16 16,21 7,21 2,16 2,7"),
        ("b", "7,3 16,3 20,7 20,16 16,20 7,20 3,16 3,7"),
        ("s", "8,5 15,5 18,8 18,15 15,18 8,18 5,15 5,8"),
        ("l", "7,7 9,7 12,10 15,7 16,7 16,9 13,12 16,15 16,16 14,16 11,13 8,16 7,16 7,14 10,11 7,8"),
        ("h", "7,7 8,7 12,11 15,8 16,8 12,12 8,16 7,15 11,11"),
    ),
}
SHAPES.update({
    "spark": SHAPES["star"],
    "swirl": SHAPES["vortex"],
})

def specs():
    entries = {
        "__weapon_normal_attack__": ("blade", "pearl"),
        "__unavailable__": ("seal", "ember"),
    }
    for line in ART.strip().splitlines():
        name, *art = line.split()
        if not art:
            group = name
            continue
        skill_id = f"{group}_{name}"
        if skill_id in entries:
            raise ValueError(f"Duplicate sprite definition: {skill_id}")
        entries[skill_id] = art

    for path in (WORKSPACE / "40_filebase/30.features.skill").glob("*.yml"):
        content = path.read_text(encoding="utf-8-sig")
        match = re.search(r"^id:\s*([a-z0-9_]+)\s*$", content, re.MULTILINE)
        if match and match[1] not in entries:
            raise ValueError(f"Add a sprite definition for {match[1]}")
    return entries


def catalog_for(entries):
    catalog = json.loads(CATALOG.read_text(encoding="utf-8")) if CATALOG.exists() else {}
    if catalog.keys() - entries.keys():
        raise ValueError("Keep the art for published catalog IDs so clients can still resolve them")
    if any(type(value) is not int or not 1 <= value <= MAX_ID for value in catalog.values()):
        raise ValueError("Published catalog IDs must be integers between 1 and 254")
    if len(set(catalog.values())) != len(catalog):
        raise ValueError("Published catalog IDs must be unique")

    next_id = max(catalog.values(), default=0) + 1
    for key in entries:
        if key not in catalog:
            if next_id > MAX_ID:
                raise ValueError("The action ring icon catalog is full")
            catalog[key] = next_id
            next_id += 1
    return dict(sorted(catalog.items(), key=lambda entry: entry[1]))


def rgba(color):
    return bytes.fromhex(color + "ff")


def polygon_points(points):
    vertices = [tuple(map(int, point.split(","))) for point in points.split()]
    edges = list(zip(vertices, vertices[1:] + vertices[:1]))
    # Sample pixel centers against the edges. Coordinates have their origin at top left.
    for y in range(min(y for _, y in vertices), max(y for _, y in vertices) + 1):
        for x in range(min(x for x, _ in vertices), max(x for x, _ in vertices) + 1):
            inside = False
            for (ax, ay), (bx, by) in edges:
                if (ay > y + 0.5) != (by > y + 0.5):
                    boundary = ax + (y + 0.5 - ay) * (bx - ax) / (by - ay)
                    if x + 0.5 < boundary:
                        inside = not inside
            if inside:
                yield x, y


def draw_shape(name, palette, origin, size):
    ink = dict(zip("sblh", PALETTES[palette]))
    points = {}
    for shade, vertices in SHAPES[name]:
        for point in polygon_points(vertices):
            if shade == "void":
                points.pop(point, None)
            else:
                color = ink[shade] if shade in ink else PALETTES[shade][2]
                points[point] = rgba(color)

    # Use nearest-neighbor scaling to retain solid pixel edges at every sprite size.
    ox, oy = origin
    colors = {}
    for y in range(size):
        for x in range(size):
            source = (x * 24 // size, y * 24 // size)
            if source in points:
                colors[(ox + x, oy + y)] = points[source]

    outline = rgba(OUTLINE)
    layer = {}
    for x, y in colors:
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            layer[(x + dx, y + dy)] = outline
    layer.update(colors)
    return layer


def sprite(art):
    shape, palette, *accents = art
    if not accents:
        points = draw_shape(shape, palette, (4, 4), 24)
    else:
        accent, = accents
        accent_palette = ACCENT_PALETTES.get(accent, palette)
        if shape == "circle":
            # The center glyph distinguishes the school of each spell circle.
            points = draw_shape(shape, palette, (3, 3), 26)
            points.update(draw_shape(accent, accent_palette, (10, 9), 12))
        else:
            points = draw_shape(shape, palette, (3, 3), 24)
            points.update(draw_shape(accent, accent_palette, (16, 16), 13))

    pixels = bytearray(CELL * CELL * 4)
    for (x, y), color in points.items():
        if not (2 <= x < CELL - 2 and 2 <= y < CELL - 2):
            raise ValueError(f"Sprite has less than two transparent edge pixels: {art}")
        offset = (y * CELL + x) * 4
        pixels[offset:offset + 4] = color
    return pixels


def png(width, height, pixels):
    def chunk(name, data):
        return (
            struct.pack(">I", len(data))
            + name + data
            + struct.pack(">I", zlib.crc32(name + data))
        )

    stride = width * 4
    scanlines = b"".join(
        b"\0" + pixels[row:row + stride] for row in range(0, len(pixels), stride)
    )
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(scanlines, level=9))
        + chunk(b"IEND", b"")
    )


def outputs():
    entries = specs()
    catalog = catalog_for(entries)
    atlas = bytearray(ATLAS_SIZE * ATLAS_SIZE * 4)
    bundle = {}
    drawn = {}
    for name, code in catalog.items():
        pixels = sprite(entries[name])
        key = bytes(pixels)
        if key in drawn:
            raise ValueError(f"Identical art for {name} and {drawn[key]}")
        drawn[key] = name
        bundle[str(code)] = base64.b64encode(png(CELL, CELL, pixels)).decode("ascii")
        origin_x = (code % (ATLAS_SIZE // CELL)) * CELL
        origin_y = (code // (ATLAS_SIZE // CELL)) * CELL
        for row in range(CELL):
            offset = ((origin_y + row) * ATLAS_SIZE + origin_x) * 4
            source = row * CELL * 4
            atlas[offset:offset + CELL * 4] = pixels[source:source + CELL * 4]
    return {
        CATALOG: (json.dumps(catalog, indent=2) + "\n").encode("utf-8"),
        ATLAS: png(ATLAS_SIZE, ATLAS_SIZE, atlas),
        BEDROCK_TILES: (json.dumps(bundle, separators=(",", ":")) + "\n").encode("utf-8"),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify the checked-in files")
    args = parser.parse_args()
    for path, data in outputs().items():
        if args.check:
            actual = path.read_bytes() if path.exists() else b""
            if path.suffix == ".json":
                actual = actual.replace(b"\r\n", b"\n")
            if actual != data:
                parser.exit(1, f"Generated output differs: {path}\n")
        else:
            path.write_bytes(data)


if __name__ == "__main__":
    main()
