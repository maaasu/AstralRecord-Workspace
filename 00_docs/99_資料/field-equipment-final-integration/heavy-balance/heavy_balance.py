"""Read-only, reproducible sensitivity comparison of field equipment and real mob profiles."""
from __future__ import annotations

import argparse
import csv
import itertools
import json
import math
from pathlib import Path

import yaml

GAMMA = math.log(.5) / math.log(.6)
OLD = [73, 74, 75, 77, 78, 79, 80] + list(range(86, 98))
SERIES = {'eriva': OLD, 'lapis': list(range(400, 419)),
          'lumina': list(range(419, 438)), 'astra': list(range(438, 457))}
SET_BONUS = {'eriva': 6, 'lapis': 6, 'lumina': 8, 'astra': 10}
RESIDUAL = {'attack': 0, 'primary': 42.8, 'defense': 0, 'hp': 0}
ANCHORS = {15: {'series': 'eriva', 'R': 767, 'D': 797, 'HP': 2200},
           20: {'series': 'lapis', 'R': 1394, 'D': 1449, 'HP': 4000},
           25: {'series': 'lumina', 'R': 2264, 'D': 2355, 'HP': 6500},
           30: {'series': 'astra', 'R': 3318, 'D': 3442, 'HP': 9500}}
ATTACK = ['MELEE_ATTACK'] * 3 + ['RANGED_ATTACK'] * 2 + ['MAGIC_ATTACK'] * 2
ABILITY = ['STRENGTH'] * 3 + ['DEXTERITY'] * 2 + ['INTELLIGENCE'] * 2
ARMOR_START = [7] * 3 + [11] * 2 + [15] * 2
PROFILE_CACHE = {}


def read_yaml(path):
    return yaml.safe_load(path.read_text(encoding='utf-8-sig'))


def gear(repo, item_id):
    folder = repo / '40_filebase/10.features.item/20.equipment'
    path, = folder.glob(f'v1.20a{item_id:05}.*.yml')
    return read_yaml(path)


def bounds(value):
    if isinstance(value, dict):
        a = bounds(value['min'])
        b = bounds(value['max'])
        return a[0], b[1], (a[2] + b[2]) / 2
    if isinstance(value, str) and '~' in value:
        a, b = map(float, value.split('~'))
        return a, b, (a + b) / 2
    v = float(value)
    return v, v, v


def stat(item, status, level, mode='mean', default=None):
    row = next((s for s in item['equipment']['stats'] if s['status'] == status), None)
    if row is None:
        if default is None:
            raise ValueError(f'{item["id"]}: missing {status}')
        return default
    lo, hi, mean = bounds(row['value'])
    if 'random' in row:
        r = row['random']
        lo += r['offsetMin']
        hi += r['offsetMax']
        mean = (lo + hi) / 2 * (1 - r.get('zeroChance', 0))
        if r.get('zeroChance', 0):
            lo = min(0, lo)
    for step in item['equipment']['enhance']['levels']:
        if step['level'] <= level:
            for inc in step.get('statIncrease', []):
                if inc['status'] == status:
                    x = bounds(inc['value'])
                    lo += x[0]
                    hi += x[1]
                    mean += x[2]
    return {'min': lo, 'max': hi, 'mean': mean}[mode]


def defense_multiplier(attack, defense):
    return max(0, 1 - defense / attack / 2.5) ** GAMMA if attack > 0 else 0


def offensive_effects(enchant, attack_status, ability_status, roll_mode, element_status=None):
    useful = {'ATTACK', attack_status, ability_status, 'ACCURACY', 'CRITICAL_RATE',
              'CRITICAL_DAMAGE', 'NORMAL_ATTACK_DAMAGE_INCREASE',
              'SKILL_DAMAGE_INCREASE', 'FINAL_DAMAGE_MULTIPLIER',
              'NONE_DAMAGE_INCREASE'}
    if element_status:
        useful.add(element_status)
        useful.remove('NONE_DAMAGE_INCREASE')
    entries = enchant['targets'][0]['entries']
    index = 2 if roll_mode == 'mean' else 1
    return [dict(id=e['effectId'], status=e['status'], type=e['type'],
                 value=bounds(e['value'])[index]) for e in entries if e['status'] in useful]


def gear_profile(weapon, armor, level, mode, attack_status, ability_status):
    key = (id(weapon), tuple(map(id, armor)), level, mode)
    if key not in PROFILE_CACHE:
        PROFILE_CACHE[key] = (
            stat(weapon, attack_status, level, mode),
            stat(weapon, ability_status, level, mode),
            stat(weapon, 'NORMAL_ATTACK_DAMAGE_INCREASE', level, mode, default=0),
            RESIDUAL['defense'] + sum(stat(p, 'DEFENSE', level, mode) for p in armor),
            RESIDUAL['hp'] + sum(stat(p, 'MAX_HEALTH', level, mode, default=0) for p in armor))
    return PROFILE_CACHE[key]


def evaluate(weapon, armor, level, mode, set_attack, mob, attack_status,
             ability_status, effects=(), armor_ability=0, armor_none=0,
             rune_attack=0, crit=False, element_status=None):
    main, primary, native_normal, defense, hp = gear_profile(
        weapon, armor, level, mode, attack_status, ability_status)
    enchant = {(e['status'], e['type']): e['value'] for e in effects}

    def local(status, flat):
        return (flat + enchant.get((status, 'FLAT'), 0)) * enchant.get((status, 'SCALAR'), 1)

    attack = ((RESIDUAL['attack'] + set_attack + local('ATTACK', rune_attack)
               + local(attack_status, main))
              * (1 + (RESIDUAL['primary'] + primary + armor_ability
                      + enchant.get((ability_status, 'FLAT'), 0)) / 100))
    native_element = (stat(weapon, element_status, level, mode, default=0)
                      if element_status else 0)
    element_bonus = (local(element_status, native_element) if element_status
                     else armor_none + local('NONE_DAMAGE_INCREASE', 0))
    element = 1 + element_bonus / 100
    final = 1.3 + enchant.get(('FINAL_DAMAGE_MULTIPLIER', 'FLAT'), 0) / 100
    base_hit = attack * defense_multiplier(attack, mob['defense']) * element * final
    normal = 1 + (native_normal + enchant.get(('NORMAL_ATTACK_DAMAGE_INCREASE', 'FLAT'), 0)) / 100
    skill = 1 + enchant.get(('SKILL_DAMAGE_INCREASE', 'FLAT'), 0) / 100
    critical = (1 + (5.5 + enchant.get(('CRITICAL_RATE', 'FLAT'), 0)) / 100
                * (.5 + enchant.get(('CRITICAL_DAMAGE', 'FLAT'), 0) / 100)) if crit else 1
    hit_rate = min(1, max(0, (95 + 2 - mob.get('evasion', 3)
                              + enchant.get(('ACCURACY', 'FLAT'), 0)) / 100))
    dps = base_hit * (.6 * normal + .4 * skill) * 1.6 * hit_rate * critical
    enemy_hit = min(1, max(0, (mob.get('accuracy', 95) - 3.5) / 100))
    enemy_dps = mob['attack'] * defense_multiplier(mob['attack'], defense) * enemy_hit / 1.5
    return {'R': attack, 'D': defense, 'HP': hp, 'normalHit': base_hit * normal,
            'expectedDps': dps, 'TTK': mob['hp'] / dps if dps > 0 else math.inf,
            'survival': hp / enemy_dps if enemy_dps > 0 else math.inf,
            'hitRate': hit_rate, 'criticalFactor': critical,
            'nativeNormalBonus': native_normal, 'elementStatus': element_status or 'NONE',
            'nativeElementBonus': native_element}


def optimize(weapon, armor, level, set_attack, mob, attack_status, ability_status,
             slots, enchant, roll_mode, element_status=None):
    options = offensive_effects(enchant, attack_status, ability_status, roll_mode,
                                element_status)
    ability_roll = 6.5 if roll_mode == 'mean' else 12
    none_roll = 5.25 if roll_mode == 'mean' else 10
    none_scalar = 1.125 if roll_mode == 'mean' else 1.24
    armor_options = ([(4 * ability_roll, 0)] if element_status else
                     [(ability_roll * n, none_roll * (4 - n)) for n in range(5)]
                     if slots == 1 else [(4 * ability_roll, 4 * none_roll)]
                     if slots == 2 else [(4 * ability_roll, 4 * none_roll * none_scalar)])
    best = None
    for combination in itertools.combinations(options, slots):
        for armor_ability, armor_none in armor_options:
            row = evaluate(weapon, armor, level, roll_mode, set_attack, mob,
                           attack_status, ability_status, combination,
                           armor_ability, armor_none, rune_attack=5, crit=True,
                           element_status=element_status)
            if best is None or row['TTK'] < best['TTK']:
                best = row | {'weaponEffects': [e['id'] for e in combination],
                              'armorAbility': armor_ability, 'armorNone': armor_none}
    best['searchedWeaponCombinations'] = math.comb(len(options), slots)
    return best


def compact(row):
    return {k: (round(v, 6) if math.isfinite(v) else None) if isinstance(v, float) else v
            for k, v in row.items()}


def write_csv(path, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]), lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def load_mob_cases(repo, out, mob_selection):
    selections = json.loads(mob_selection.read_text(encoding='utf-8'))
    result = []
    for selection in selections:
        path = repo / selection['profile']
        master = read_yaml(path)
        assert master['id'] == selection['id']
        assert master['category'] == selection['category']
        source_level = selection['sourceLevel']
        assert source_level == master['level'] or any(
            row['level'] == source_level for row in master.get('levels', []))
        stats = {s['status']: s['value'] for s in master.get('baseStats', [])}
        row = next((r for r in master.get('levels', []) if r['level'] == source_level), {})
        stats.update({s['status']: s['value'] for s in row.get('baseStats', [])})
        assert {'MAX_HEALTH', 'ATTACK', 'DEFENSE'} <= stats.keys()
        result.append({'id': master['id'], 'level': selection['level'],
                       'sourceLevel': source_level, 'role': selection['role'],
                       'category': master['category'], 'profile': selection['profile'],
                       'hp': stats['MAX_HEALTH'], 'attack': stats['ATTACK'],
                       'defense': stats['DEFENSE'],
                       'accuracy': stats.get('ACCURACY', 95),
                       'evasion': stats.get('EVASION', 3)})
    assert len(result) == 8
    (out / 'mob_cases.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    return result


def calibrated_residual(items):
    result = {}
    for level, target in ANCHORS.items():
        series = target['series']
        weapon = items[series][0]
        armor = items[series][7:11]
        main = stat(weapon, 'MELEE_ATTACK', 15)
        ability = stat(weapon, 'STRENGTH', 15)
        armor_def = sum(stat(p, 'DEFENSE', 15) for p in armor)
        armor_hp = sum(stat(p, 'MAX_HEALTH', 15, default=0) for p in armor)
        result[level] = {
            'attack': target['R'] / (1 + (42.8 + ability) / 100) - main - SET_BONUS[series],
            'primary': 42.8, 'defense': target['D'] - armor_def,
            'hp': target['HP'] - armor_hp,
            'calibrationSeries': series, 'calibrationEnhance': 15,
            'anchor': {'R': target['R'], 'D': target['D'], 'HP': target['HP']}}
    return result


def native_element_status(weapon):
    for status in ('FIRE_DAMAGE_INCREASE', 'ICE_DAMAGE_INCREASE',
                   'LIGHTNING_DAMAGE_INCREASE'):
        if any(s['status'] == status for s in weapon['equipment']['stats']):
            return status
    return None


def run(repo, out, mob_selection):
    global RESIDUAL
    mobs = load_mob_cases(repo, out, mob_selection)
    enchant = read_yaml(repo / '40_filebase/12.features.enchant/v1.enchant003.yml')
    all_items = {name: [gear(repo, i) for i in ids] for name, ids in SERIES.items()}
    residuals = calibrated_residual(all_items)
    (out / 'player_residuals.json').write_text(json.dumps(residuals, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    gear_rows = []
    for name, items in all_items.items():
        for i, item in enumerate(items):
            status = ATTACK[i] if i < 7 else 'DEFENSE'
            for level in (0, 15, 30):
                gear_rows.append({'series': name, 'itemId': item['id'],
                                  'slot': item['equipment']['slot'], 'tag': item['equipment'].get('tag', ''),
                                  'enhance': level, 'primaryStatus': status,
                                  'minimum': stat(item, status, level, 'min'),
                                  'expected': stat(item, status, level),
                                  'maximum': stat(item, status, level, 'max'),
                                  'enchantSlots': item['equipment']['enchant']['maxSlots']})
    write_csv(out / 'equipment_76x3.csv', gear_rows)
    cases = []
    for mob in mobs:
        RESIDUAL = residuals[mob['level']]
        PROFILE_CACHE.clear()
        for name, items in all_items.items():
            for w in range(7):
                weapon = items[w]
                armor = items[ARMOR_START[w]:ARMOR_START[w] + 4]
                slots = weapon['equipment']['enchant']['maxSlots']
                native = native_element_status(weapon)
                for level in (0, 15, 30):
                    base = evaluate(weapon, armor, level, 'mean', SET_BONUS[name], mob,
                                    ATTACK[w], ABILITY[w])
                    mean_opt = optimize(weapon, armor, level, SET_BONUS[name], mob,
                                        ATTACK[w], ABILITY[w], slots, enchant, 'mean')
                    peak = optimize(weapon, armor, level, SET_BONUS[name], mob,
                                    ATTACK[w], ABILITY[w], slots, enchant, 'max')
                    aligned = optimize(weapon, armor, level, SET_BONUS[name], mob,
                                       ATTACK[w], ABILITY[w], slots, enchant, 'max', native)
                    for mode, row in [('expected_unenchanted', base),
                                      ('enchant_mean_opt', mean_opt), ('offense_max', peak),
                                      ('offense_max_aligned', aligned)]:
                        cases.append({'mobLevel': mob['level'], 'mobRole': mob['role'],
                                      'mobId': mob['id'], 'mobSourceLevel': mob['sourceLevel'],
                                      'series': name, 'weaponId': weapon['id'],
                                      'weaponTag': weapon['equipment']['tag'], 'enhance': level,
                                      'mode': mode, 'enchantSlots': slots,
                                      'requiredLevel': weapon['equipment']['requiredLevel'],
                                      'scenario': ('legal' if weapon['equipment']['requiredLevel'] <= mob['level']
                                                   else 'counterfactual_same_residual'),
                                      **compact(row)})
    (out / 'combat_cases.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8', newline='\n')
    slim = [{k: row[k] for k in ('mobLevel', 'mobRole', 'mobId', 'mobSourceLevel', 'series',
            'weaponId', 'weaponTag', 'enhance', 'mode', 'enchantSlots', 'requiredLevel',
            'scenario', 'R', 'D', 'HP',
            'TTK', 'survival', 'hitRate', 'criticalFactor', 'nativeNormalBonus',
            'elementStatus', 'nativeElementBonus')}
            for row in cases]
    write_csv(out / 'combat_cases.csv', slim)
    print(json.dumps({'gearRows': len(gear_rows), 'combatCases': len(cases),
                      'mobs': [m['id'] for m in mobs]}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--mob-selection', type=Path,
                        default=Path(__file__).resolve().with_name('mob_selection.json'))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    run(args.repo, args.out, args.mob_selection)
