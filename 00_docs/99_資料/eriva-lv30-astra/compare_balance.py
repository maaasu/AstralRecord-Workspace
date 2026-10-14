"""実YAMLから19品比較と、限定条件での刻印構成の参考比較を再生成する。"""
from __future__ import annotations
from copy import deepcopy
from itertools import combinations
from math import comb, log
import csv
import subprocess
from pathlib import Path
import json
import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
LUMINA_SHA = '660f86bf8e96e1494e22dc5f742fb2b8491e4951'
LAPIS_SHA = '2bdcea3c4009092b73aedc14388f326275151b2d'
GEAR = '40_filebase/10.features.item/20.equipment'
ENCHANT = yaml.safe_load((ROOT / '40_filebase/12.features.enchant/v1.enchant003.yml').read_text(encoding='utf-8'))
WEAPON = ENCHANT['targets'][0]['entries']
BY_ID = {row['effectId']: row for row in WEAPON}
GAMMA = log(.5) / log(.6)
LV25_RESIDUAL = dict(attack=1145.8775510204082, primary=42.8, defense=1690, hp=5610)
MOB25 = dict(hp=13000, attack=2355, defense=2264)
MOB30 = dict(hp=19000, attack=3442, defense=3318)


def item(root: Path, item_id: str):
    if root in (LUMINA_SHA, LAPIS_SHA):
        paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', root, GEAR], cwd=ROOT, encoding='utf-8').splitlines()
        path, = [p for p in paths if Path(p).name.startswith(f'v1.{item_id}.')]
        return yaml.safe_load(subprocess.check_output(['git', 'show', f'{root}:{path}'], cwd=ROOT, encoding='utf-8'))
    path, = (root / GEAR).glob(f'v1.{item_id}.*.yml')
    return yaml.safe_load(path.read_text(encoding='utf-8-sig'))


def bounds(value):
    if isinstance(value, dict):
        lo, _, lm = bounds(value['min'])
        _, hi, hm = bounds(value['max'])
        return lo, hi, (lm + hm) / 2
    if isinstance(value, str) and '~' in value:
        lo, hi = map(float, value.split('~'))
        return lo, hi, (lo + hi) / 2
    v = float(value)
    return v, v, v


def stat(data, status, enhance, mode='mean'):
    row, = (s for s in data['equipment']['stats'] if s['status'] == status)
    lo, hi, mean = bounds(row['value'])
    if 'random' in row:
        random = row['random']
        lo += random['offsetMin']
        hi += random['offsetMax']
        mean = (lo + hi) / 2 * (1 - random.get('zeroChance', 0))
        if random.get('zeroChance', 0):
            lo = min(0, lo)
    for level in data['equipment']['enhance']['levels']:
        if level['level'] <= enhance:
            for addition in level.get('statIncrease', []):
                if addition['status'] == status:
                    delta = bounds(addition['value'])
                    lo += delta[0]
                    hi += delta[1]
                    mean += delta[2]
    return dict(min=lo, max=hi, mean=mean)[mode]


def defense_multiplier(attack, defense):
    return max(0, 1 - defense / attack / 2.5) ** GAMMA if attack > 0 else 0


def weapon_stat_values(weapon, level, mode):
    return stat(weapon, 'MELEE_ATTACK', level, mode), stat(weapon, 'STRENGTH', level, mode)


def armor_values(armor, level, mode):
    return (sum(stat(piece, 'DEFENSE', level, mode) for piece in armor),
            sum(stat(piece, 'MAX_HEALTH', level, mode) for piece in armor))


def reference(weapon, armor, level, mode, set_attack, residual, mob, weapon_effects=(),
              armor_strength=0, armor_none=0, rune=0, crit=False, hit_rate=.92):
    main, primary = weapon_stat_values(weapon, level, mode)
    armor_defense, armor_hp = armor_values(armor, level, mode)
    ench = {(entry['status'], entry['type']): bounds(entry['value'])[1] for entry in weapon_effects}

    def on_weapon(status, flat_base):
        return (flat_base + ench.get((status, 'FLAT'), 0)) * ench.get((status, 'SCALAR'), 1)

    typed = on_weapon('MELEE_ATTACK', main)
    generic = on_weapon('ATTACK', rune) if weapon_effects else rune
    r = (residual['attack'] + set_attack + generic + typed) * (
        1 + (residual['primary'] + primary + armor_strength + ench.get(('STRENGTH', 'FLAT'), 0)) / 100)
    defense = residual['defense'] + armor_defense
    hp = residual['hp'] + armor_hp
    element = 1 + (armor_none + on_weapon('NONE_DAMAGE_INCREASE', 0)) / 100
    final = (1.3 + ench.get(('FINAL_DAMAGE_MULTIPLIER', 'FLAT'), 0) / 100)
    # DamageCalculator: source/critical are applied before defense, but its multiplier uses R,
    # not source/critical-adjusted damage. Products commute under this fixed model.
    base_hit = r * defense_multiplier(r, mob['defense']) * element * final
    normal = 1 + ench.get(('NORMAL_ATTACK_DAMAGE_INCREASE', 'FLAT'), 0) / 100
    skill = 1 + ench.get(('SKILL_DAMAGE_INCREASE', 'FLAT'), 0) / 100
    critical = (1 + (5.5 + ench.get(('CRITICAL_RATE', 'FLAT'), 0)) / 100 *
                (.5 + ench.get(('CRITICAL_DAMAGE', 'FLAT'), 0) / 100)) if crit else 1
    hit = min(1, hit_rate + ench.get(('ACCURACY', 'FLAT'), 0) / 100)
    dps = base_hit * (.6 * normal + .4 * skill) * 1.6 * hit * critical
    survival_dps = mob['attack'] * defense_multiplier(mob['attack'], defense) * .915 / 1.5
    return dict(R=r, D=defense, HP=hp, normalHit=base_hit * normal,
                TTK=mob['hp'] / dps, survival=hp / survival_dps,
                hitRate=hit, criticalFactor=critical)


def compact(row):
    return {k: round(v, 6) for k, v in row.items()}


def optimize(weapon, armor, set_attack, residual, mob, slots, hit_rate=.92):
    best = None
    max_r, max_r_effects = 0, []
    armor_builds = ([(12 * n, 10 * (4 - n)) for n in range(5)] if slots == 1
                    else [(48, 40)] if slots == 2 else [(48, 49.6)])
    # For 3/4 armor slots the only direct melee offense entries are STR +12,
    # NONE +10 and NONE SCALAR 1.24. The fourth slot adds no direct DPS here.
    for choices in combinations(WEAPON, slots):
        for strength, none in armor_builds:
            result = reference(weapon, armor, 30, 'max', set_attack, residual, mob,
                               choices, strength, none, rune=5, crit=True,
                               hit_rate=hit_rate)
            key = result['TTK']
            if result['R'] > max_r:
                max_r = result['R']
                max_r_effects = [e['effectId'] for e in choices]
            if best is None or key < best['TTK']:
                best = dict(result, weaponEffects=[e['effectId'] for e in choices],
                            armorStrength=strength, armorNone=none)
    return compact({k: v for k, v in best.items() if isinstance(v, (float, int))}) | {
        'weaponEffects': best['weaponEffects'], 'armorStrength': best['armorStrength'],
        'armorNone': best['armorNone'], 'weaponCombinationCount': comb(len(WEAPON), slots),
        'maximumRInSearch': round(max_r, 6), 'maximumRWeaponEffects': max_r_effects}


def run():
    eriva = item(ROOT, '20a00073')
    lumina = item(LUMINA_SHA, '20a00419')
    lapis = item(LAPIS_SHA, '20a00400')
    eriva_armor = [item(ROOT, f'20a{i:05}') for i in range(86, 90)]
    lumina_armor = [item(LUMINA_SHA, f'20a{i:05}') for i in range(426, 430)]
    lapis_armor = [item(LAPIS_SHA, f'20a{i:05}') for i in range(407, 411)]
    astra = item(ROOT, '20a00438')
    astra_armor = [item(ROOT, f'20a{i:05}') for i in range(445, 449)]
    old_ids = [73, 74, 75, 77, 78, 79, 80] + list(range(86, 98))
    columns = ['eriva_id', 'lapis_id', 'lumina_id', 'astra_id', 'name', 'primary_status'] + [
        f'{series}_{level}_{measure}' for series in ['eriva', 'lapis', 'lumina', 'astra']
        for level in [0, 15, 30] for measure in ['min', 'max', 'mean']]
    with (HERE/'balance_comparison.csv').open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=columns, lineterminator='\n')
        writer.writeheader()
        for old, low, middle, new in zip(old_ids, range(400, 419), range(419, 438), range(438, 457)):
            datas = [('eriva', item(ROOT, f'20a{old:05}')),
                     ('lapis', item(LAPIS_SHA, f'20a{low:05}')),
                     ('lumina', item(LUMINA_SHA, f'20a{middle:05}')),
                     ('astra', item(ROOT, f'20a{new:05}'))]
            primary = datas[3][1]['equipment']['stats'][0]['status']
            row = {f'{series}_id': data['id'] for series, data in datas}
            row.update(name=datas[3][1]['name'], primary_status=primary)
            for series, data in datas:
                for level in (0, 15, 30):
                    for measure in ('min', 'max', 'mean'):
                        row[f'{series}_{level}_{measure}'] = round(stat(data, primary, level, measure), 6)
            writer.writerow(row)
    fixed = LV25_RESIDUAL
    baseline = {}
    optimum = {}
    for name, weapon, armor, atk, slots in (
            ('eriva', eriva, eriva_armor, 6, 1),
            ('lapis', lapis, lapis_armor, 6, 2),
            ('lumina', lumina, lumina_armor, 8, 3),
            ('astra', astra, astra_armor, 10, 4)):
        baseline[name] = {str(lv): compact(reference(weapon, armor, lv, 'mean', atk, fixed, MOB25))
                          for lv in (0, 15, 30)}
        optimum[name] = optimize(weapon, armor, atk, fixed, MOB25, slots)
    # The old 1.760734 line intentionally reproduces the Lumina reference assumptions:
    # max rolls, typed FLAT +48 / SCALAR x1.70 / STR +12; 3 offensive armor entries,
    # rune +5, 92% hit, no critical. It is not a measured TTK.
    legacy_ids = ('weapon_melee_attack_flat', 'weapon_melee_attack_scalar', 'weapon_strength_flat')
    legacy = reference(lumina, lumina_armor, 30, 'max', 8, fixed, MOB25,
                       [BY_ID[i] for i in legacy_ids], 48, 49.6, rune=5, crit=False)
    corrected = reference(lumina, lumina_armor, 30, 'max', 8, fixed, MOB25,
                          [BY_ID[i] for i in legacy_ids], 48, 49.6, rune=5, crit=True)
    # Distinct Lv30 anchor calibration, not the shared-residual comparison above.
    residual30 = dict(attack=3318 / (1 + (42.8 + stat(astra, 'STRENGTH', 15)) / 100)
                            - stat(astra, 'MELEE_ATTACK', 15) - 10,
                      primary=42.8, defense=3442-armor_values(astra_armor, 15, 'mean')[0],
                      hp=9500-armor_values(astra_armor, 15, 'mean')[1])
    anchored30 = {str(lv): compact(reference(astra, astra_armor, lv, 'mean', 10, residual30, MOB30))
                  for lv in (0, 15, 30)}
    anchor_opt = optimize(astra, astra_armor, 10, residual30, MOB30, 4)
    # Proposed values are applied only to in-memory copies; no lower-series YAML is edited.
    proposed_lapis, proposed_armor = deepcopy(lapis), deepcopy(lapis_armor)
    for row in proposed_lapis['equipment']['stats']:
        if row['status'] == 'MELEE_ATTACK':
            row.update(value=170, random=dict(offsetMin=-7, offsetMax=7, step=1))
        elif row['status'] == 'STRENGTH':
            row.update(value=12, random=dict(offsetMin=-2, offsetMax=2, step=1))
    for row in proposed_lapis['equipment']['enhance']['levels']:
        row['statIncrease'] = [dict(status='MELEE_ATTACK', type='FLAT', value=3 if row['level']<=15 else 2)]
    for index, piece in enumerate(proposed_armor):
        for row in piece['equipment']['enhance']['levels']:
            row['statIncrease'] = [dict(status='DEFENSE', type='FLAT',
                                       value=([2,3,2,1] if row['level']<=15 else [1,2,1,1])[index])]
    proposed_baseline = {str(level): compact(reference(proposed_lapis, proposed_armor, level, 'mean', 6, fixed, MOB25))
                         for level in (0,15,30)}
    proposed_search = optimize(proposed_lapis, proposed_armor, 6, fixed, MOB25, 2)
    report = dict(model='synthetic fixed residual; sword + matching guard armor; NONE damage',
                          luminaSourceCommit=LUMINA_SHA,
                          lapisSourceCommit=LAPIS_SHA,
                          shared25Residual=fixed, shared25Mob=MOB25,
                          baselineSameResidualSameMob=baseline,
                          optimizedSameResidualSameMob=optimum,
                          legacyLuminaNoCrit=compact(legacy),
                          correctedLuminaBaseCrit=compact(corrected),
                          distinctLv30Residual=residual30, distinctLv30Mob=MOB30,
                          lv30AnchoredBaseline=anchored30,
                          lv30AnchoredSearch=anchor_opt,
                          proposedLapisBaseline=proposed_baseline, proposedLapisSearch=proposed_search,
                          assumptions={'normalShare': .6, 'skillShare': .4,
                                       'mixedDpsFactor': 1.6, 'hitRate': .92,
                                       'enemyHitRate': .915, 'finalMultiplier': 1.3,
                                       'critRateIfEnabled': .055, 'critDamage': 1.5,
                                       'weaponAttributeBonus': 'excluded for NONE route',
                                       'setAccuracyPlus2': 'not added to fixed .92 hit assumption'},
                          limits=['Search uses enchant003 weapon entries at their maxima and direct melee offense armor entries; not all possible character builds',
                                  'Baseline cases omit criticals/enchant/rune to reproduce the previous reference; search enables base criticals',
                                  'No skill timing, attack degradation, Shield, source placement, 100-battle simulation or accessory optimization',
                                  'Legacy item min/max are attack ranges; new random stats are one saved scalar per instance'])
    (HERE/'combat_reference.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    proposal = dict(status='proposal_only_not_applied', sourceCommits=dict(lapis=LAPIS_SHA, lumina=LUMINA_SHA),
                    owner='central equipment integration; do not edit other series from Astra task',
                    reason='Current Lapis base240/+30=405 exceeds Lumina180/270 and Astra260/350; compare on shared residual and enemy',
                    lapisWeapon=dict(primaryBase=170, random=dict(offsetMin=-7,offsetMax=7,step=1),
                                     primaryIncreasePerLevel1To15=3, primaryIncreasePerLevel16To30=2,
                                     abilityBase=12, abilitySpread=2, abilityEnhanceIncrease=0,
                                     expectedPrimaryAt0_15_30=[170,215,245]),
                    lapisArmor=dict(keepBaseDefense=True, earlyDefensePerPart=[2,3,2,1], lateDefensePerPart=[1,2,1,1],
                                    enhancementResourceIncrease=0, fixedSeriesResources=dict(health=560,energy=115,mana=180),
                                    expectedGuardDefenseAt0_15_30=[350,470,545]),
                    luminaProposal='Keep current values for this first monotonicity repair; final lower-series balance is central ownership',
                    astraDecision='Use base260±8, ability16±2, primary growth3/3, four enchant slots; do not raise to match current Lapis405',
                    baselineSameConditions=proposed_baseline, optimizedSameConditions=proposed_search,
                    comparisonOrder=['eriva','proposed_lapis','lumina','astra'],
                    ttkAt30=[baseline['eriva']['30']['TTK'],proposed_baseline['30']['TTK'],baseline['lumina']['30']['TTK'],baseline['astra']['30']['TTK']],
                    searchedMinimumTtk=[optimum['eriva']['TTK'],proposed_search['TTK'],optimum['lumina']['TTK'],optimum['astra']['TTK']],
                    limitations=['A proposal only; no lower-series YAML modified',
                                 'Monotonicity repair does not make every update 15-25%; first extra enchant slot still creates a large extreme jump',
                                 'Four-slot optimal firepower remains above standard anchor targets and requires central acceptance',
                                 'Material exchange shops requested; new weapon loot/Bundle connections unnecessary in this scope'])
    (HERE/'cross_series_proposal.json').write_text(json.dumps(proposal,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    run()
