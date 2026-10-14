"""実YAMLからラピスの+0/+15/+30比較表を生成・照合する。"""
from argparse import ArgumentParser
from pathlib import Path
from io import StringIO
import csv
import json
import math
import re

import yaml


ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
GEAR = ROOT / '40_filebase/10.features.item/20.equipment'
OLD_IDS = [73, 74, 75, 77, 78, 79, 80] + list(range(86, 98))
FIELDS = ['item_id', 'name', 'status', 'type', 'base', 'minimum', 'maximum',
          'ordinary_minimum', 'ordinary_maximum', 'step', 'zero_chance',
          'expected', 'plus15_expected', 'plus30_expected']


def load(item_id):
    path, = GEAR.glob(f'v1.20a{item_id:05}.*.yml')
    return yaml.safe_load(path.read_text(encoding='utf-8-sig'))


def value_bounds(value):
    if isinstance(value, dict):
        low = value_bounds(value['min'])
        high = value_bounds(value['max'])
        return low[0], high[1], (low[2] + high[2]) / 2
    if isinstance(value, str) and '~' in value:
        a, b = map(float, value.split('~'))
        return a, b, (a + b) / 2
    number = float(value)
    return number, number, number


def statistic(item, status, level):
    entry = next((s for s in item['equipment']['stats'] if s['status'] == status), None)
    if entry is None:
        return 0.0, 0.0, 0.0
    low, high, mean = value_bounds(entry['value'])
    if 'random' in entry:
        random = entry['random']
        low += random['offsetMin']
        high += random['offsetMax']
        zero_chance = random.get('zeroChance', 0)
        mean = (low + high) / 2 * (1 - zero_chance)
        if zero_chance:
            low = min(0, low)
    for row in item['equipment']['enhance']['levels']:
        if row['level'] <= level:
            for increase in row.get('statIncrease', []):
                if increase['status'] == status:
                    a, b, center = value_bounds(increase['value'])
                    low += a
                    high += b
                    mean += center
    return low, high, mean


def clean_name(item):
    return re.sub(r'&[0-9a-fklmnor]', '', item['name'])


def number(value):
    if value is None:
        return ''
    if isinstance(value, str):
        return value
    return f'{value:g}'


def equipment_csv(items):
    output = StringIO(newline='')
    writer = csv.DictWriter(output, fieldnames=FIELDS, lineterminator='\n')
    writer.writeheader()
    for item in items:
        for stat in item['equipment']['stats']:
            random = stat.get('random')
            low, high, expected = statistic(item, stat['status'], 0)
            ordinary_min = stat['value'] + random['offsetMin'] if random else low
            ordinary_max = stat['value'] + random['offsetMax'] if random else high
            values = [item['id'], clean_name(item), stat['status'], stat['type'],
                      stat['value'], low, high, ordinary_min, ordinary_max,
                      random['step'] if random else None,
                      random.get('zeroChance', 0) if random else 0, expected,
                      statistic(item, stat['status'], 15)[2],
                      statistic(item, stat['status'], 30)[2]]
            writer.writerow(dict(zip(FIELDS, map(number, values), strict=True)))
    return output.getvalue()


def armor_totals(items, level):
    statuses = dict.fromkeys(s['status'] for item in items for s in item['equipment']['stats'])
    return {status: sum(statistic(item, status, level)[2] for item in items)
            for status in statuses}


def defense_multiplier(attack, defense):
    gamma = math.log(.5) / math.log(.6)
    return max(0, 1 - defense / attack / 2.5) ** gamma


def combat_case(label, sword, armor, level):
    fixed_attack, fixed_primary, fixed_defense, fixed_health = 1145.8775510204082, 42.8, 1690, 5610
    main = statistic(sword, 'MELEE_ATTACK', level)[2]
    strength = statistic(sword, 'STRENGTH', level)[2]
    armor_stats = armor_totals(armor, level)
    attack = (fixed_attack + 6 + main) * (1 + (fixed_primary + strength) / 100)
    defense = fixed_defense + armor_stats['DEFENSE']
    hp = fixed_health + armor_stats['MAX_HEALTH']
    hit = attack * defense_multiplier(attack, 2264) * 1.3
    player_dps = hit * .92 * 1.6
    enemy_dps = 2355 * defense_multiplier(2355, defense) * .915 / 1.5
    return {'series': label, 'enhanceLevel': level, 'R': round(attack, 6),
            'D': round(defense, 6), 'HP': round(hp, 6),
            'TTK': round(13000 / player_dps, 6),
            'survival': round(hp / enemy_dps, 6)}


def balance_json(old, new):
    groups = [('eriva_bastion', 'lapis_bastion'),
              ('eriva_stride', 'lapis_stride'),
              ('eriva_arcana', 'lapis_arcana')]
    comparison = []
    for group, (old_name, new_name) in enumerate(groups):
        old_armor = old[7 + 4 * group:11 + 4 * group]
        new_armor = new[7 + 4 * group:11 + 4 * group]
        for level in (0, 15, 30):
            comparison.append({'enhanceLevel': level, 'stats': {
                old_name: armor_totals(old_armor, level),
                new_name: armor_totals(new_armor, level)}})
    cases = [combat_case(name, items[0], items[7:11], level)
             for name, items in [('eriva', old), ('lapis', new)]
             for level in (0, 15, 30)]
    report = {
        'note': 'YAML-derived armor totals and synthetic combat sensitivity at +0/+15/+30; no enchant/rune/class/buff. Old range uses expected endpoint center.',
        'comparison': comparison,
        'combatReference': {
            'model': 'Lv25 fixed non-equipment residual and standard enemy; not a verified player build or actual combat TTK',
            'fixedNonEquipment': {'attack': 1145.8775510204082, 'strength': 42.8, 'defense': 1690, 'hp': 5610},
            'enemy': {'level': 25, 'hp': 13000, 'attack': 2355, 'defense': 2264},
            'assumptions': {'playerHit': .92, 'enemyHit': .915, 'playerIntervalSeconds': 1,
                            'enemyIntervalSeconds': 1.5, 'playerFinalMultiplier': 1.3,
                            'mixedDpsFactor': 1.6, 'critical': False},
            'cases': cases}}
    return json.dumps(report, ensure_ascii=False, indent=2) + '\n'


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='保存済み比較表との一致だけ確認する')
    args = parser.parse_args()
    old = [load(item_id) for item_id in OLD_IDS]
    new = [load(item_id) for item_id in range(400, 419)]
    results = {'EQUIPMENT_STATS.csv': equipment_csv(new),
               'BALANCE_CHECK.json': balance_json(old, new)}
    for name, content in results.items():
        path = HERE / name
        if args.check:
            assert path.read_text(encoding='utf-8-sig') == content, f'outdated comparison: {name}'
        else:
            path.write_text(content, encoding='utf-8', newline='\n')
    print('PASS: YAML-derived +0/+15/+30 comparison matches' if args.check else
          'Wrote YAML-derived +0/+15/+30 comparison')


if __name__ == '__main__':
    main()
