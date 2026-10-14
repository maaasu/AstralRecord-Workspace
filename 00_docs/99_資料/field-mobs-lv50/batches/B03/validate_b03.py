"""B03専用のマスタ静的検証。サーバー・DB・build・登録・ファイル更新は行わない。"""
from collections import defaultdict
import json
import math
from pathlib import Path
import re

import yaml

ROOT = Path(__file__).resolve().parents[5]
HERE = Path(__file__).resolve().parent
MANIFEST = json.loads((HERE / 'REGISTRATION.json').read_text(encoding='utf-8'))


class UniqueKeys(yaml.SafeLoader):
    """変更YAMLの重複キーを黙って上書きしない。"""


def mapping(loader, node, deep=False):
    """同一Map内のキー重複を拒否する。"""
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f'duplicate YAML key: {key}')
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeys.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
masters = defaultdict(list)
all_yaml = sorted((ROOT / '40_filebase').rglob('*.yml'))
for path in all_yaml:
    data = yaml.safe_load(path.read_text(encoding='utf-8-sig'))
    if isinstance(data, dict) and 'id' in data:
        namespace = path.relative_to(ROOT / '40_filebase').parts[0]
        if namespace == '80.shared.loot':
            namespace += '/' + path.parent.name
        masters[(namespace, str(data['id']))].append(path.relative_to(ROOT).as_posix())
duplicates = {key: value for key, value in masters.items() if len(value) > 1}
assert not duplicates, duplicates
print(f'PASS: parse all {len(all_yaml)} YAML; {len(masters)} top-level master IDs, no namespace duplicates')

status_data = yaml.safe_load((ROOT / '40_filebase/75.shared.status/v1.status_types.yml').read_text(encoding='utf-8-sig'))
tag_data = yaml.safe_load((ROOT / '40_filebase/76.shared.tag/v1.tags.yml').read_text(encoding='utf-8-sig'))


def id_rows(value):
    """共有カタログのIDを含むMapを再帰的に列挙する。"""
    if isinstance(value, dict):
        if 'id' in value:
            yield value
        for child in value.values():
            yield from id_rows(child)
    elif isinstance(value, list):
        for child in value:
            yield from id_rows(child)


statuses = {row['id'] for row in id_rows(status_data)}
mob_tags = {row['id'] for row in id_rows(tag_data) if 'MOB' in row.get('appliesTo', [])}
new_items = {}
for item in MANIFEST['materials']:
    paths = masters[('10.features.item', item['id'])]
    assert len(paths) == 1
    path = ROOT / paths[0]
    text = path.read_text(encoding='utf-8')
    data = yaml.load(text, Loader=UniqueKeys)
    assert re.fullmatch(r'10a0022[0-2]', data['id'])
    assert data['schemaVersion'] == 1 and data['category'] == 'material'
    assert data['maxStack'] == 64 and data['saleValue'] >= 0
    assert data['rarity'] in ('COMMON', 'UNCOMMON')
    assert data['name'].endswith(item['name']) and data['lore']
    assert '#   motif:' in text and '#   progression:' in text
    new_items[data['id']] = data

mob_keys = {'schemaVersion', 'id', 'type', 'category', 'name', 'level', 'entityType', 'icon',
            'variant', 'lore', 'tags', 'baseStats', 'levels', 'ai', 'drops'}
for skill in MANIFEST['skills']:
    path = ROOT / f"40_filebase/40.features.mob/enemy/v1.{skill['mobId']}.yml"
    text = path.read_text(encoding='utf-8')
    mob = yaml.load(text, Loader=UniqueKeys)
    assert set(mob) <= mob_keys
    assert mob['id'] == skill['mobId'] and mob['category'] == 'ENEMY' and mob['type'] == 'MOB'
    assert mob['schemaVersion'] == 1 and mob['icon'] == mob['entityType'] + '_SPAWN_EGG'
    assert set(mob['tags']) <= mob_tags
    levels = [row['level'] for row in mob['levels']]
    assert levels == skill['levels'] and len(set(levels)) == 4 and max(levels) <= 50
    assert 4 <= max(levels) - min(levels) <= 10 and mob['level'] == min(levels)
    assert '#   motif:' in text and '#   progression:' in text
    previous = None
    for profile in mob['levels']:
        stats = {row['status']: row['value'] for row in profile['baseStats']}
        assert set(stats) <= statuses and len(stats) == len(profile['baseStats'])
        assert all(math.isfinite(value) and value > 0 for value in stats.values())
        assert stats['ACCURACY'] == 95 and stats['EVASION'] == 3
        if previous:
            assert all(stats[key] > previous[key] for key in ('MAX_HEALTH', 'ATTACK', 'DEFENSE'))
        previous = stats
    assert mob['levels'][0]['baseStats'] == mob['baseStats']
    combat = mob['ai']['combat']
    assert combat['normalAttack']['range'] == 2 and combat['normalAttack']['intervalTicks'] > 0
    assert len(combat['skills']) == 1
    binding = combat['skills'][0]
    assert binding['id'] == skill['implementationId'] and 'params' not in binding
    assert binding['castTimeTicks'] == 12 and binding['activationRange'] == 3.6
    java_path = ROOT / ('10_plugin/AstralRecord/src/main/java/' + skill['executorClass'].replace('.', '/') + '.java')
    source = java_path.read_text(encoding='utf-8')
    assert f'"{binding["id"]}"' in source
    assert 'clearCasterState' in source and 'public void stop()' in source
    assert 'conditionService.canRunAi' in source and 'damageService.isMobCombatTarget' in source
    assert 'warnings.remove(context.mob().instanceId(), this)' in source
    assert 'context.mob().scriptedAction(false)' in source and 'finally { finish(); }' in source
    expected = {item['id']: item for item in MANIFEST['materials'] if item['mobId'] == mob['id']}
    assert 'lootTable' not in mob['drops'] and len(mob['drops']['items']) == len(expected)
    for drop in mob['drops']['items']:
        ident = drop['itemId']['ref'].removeprefix('item:')
        assert ident in expected and ident in new_items
        assert drop['rate'] == expected[ident]['rate'] and drop['amount'] == expected[ident]['amount']
        assert 0 <= drop['rate'] <= 100 and drop['luckAffected'] and not drop['hidden']
    print(f'PASS: {mob["id"]}: levels {levels}, full stat profiles, tags/icons, normal attack, skill and drops')

for ident in MANIFEST['reservedUnusedMaterials']:
    assert ('10.features.item', ident) not in masters
print('PASS: 5 changed YAML, 3 item references, 2 Executor references; 7 reserved material IDs remain unused')
print('EXPECTED INTEGRATION GAP: 2 skill IDs are not centrally registered; REGISTRATION.json requires destroy/stop hooks')


def defense(r, d):
    """防御比2.5を境にする現行計算式を、非会心の事前試算へ使う。"""
    return max(0, 1 - d / r / 2.5) ** 1.35691545


print('BALANCE: anchored player theoretical DPS; survival lower bound assumes skills add without normal-attack loss')
for skill in MANIFEST['skills']:
    mob = yaml.safe_load((ROOT / f"40_filebase/40.features.mob/enemy/v1.{skill['mobId']}.yml").read_text(encoding='utf-8'))
    ratio = 1.15 if skill['mobId'] == 'field_plain_ram' else 1.1
    normal_seconds = mob['ai']['combat']['normalAttack']['intervalTicks'] / 20
    cooldown_seconds = mob['ai']['combat']['skills'][0]['cooldownTicks'] / 20
    for profile in mob['levels']:
        level = profile['level']
        s = {row['status']: row['value'] for row in profile['baseStats']}
        standard_hp = 19000 + (level - 30) * 1200
        player_hp = standard_hp / 2
        player_r = 3318 + (level - 30) * 210.8
        player_d = 3442 + (level - 30) * 217.4
        player_dps = player_r * defense(player_r, s['DEFENSE']) * 1.3 * .92 * 1.6
        normal_dps = s['ATTACK'] * defense(s['ATTACK'], player_d) * .915 / normal_seconds
        skill_dps = s['ATTACK'] * ratio * defense(s['ATTACK'], player_d) * .915 / cooldown_seconds
        warning_ticks = 12 if skill['mobId'] == 'field_plain_ram' else 20
        timing = mob['ai']['combat']['skills'][0]
        attack_duty = 1 - (timing['castTimeTicks'] + warning_ticks) / timing['cooldownTicks']
        duty_survival = player_hp / (normal_dps * attack_duty + skill_dps)
        print(f"{mob['id']} Lv{level}: TTK={s['MAX_HEALTH']/player_dps:.2f}s, normal survival={player_hp/normal_dps:.2f}s, additive skill bound={player_hp/(normal_dps+skill_dps):.2f}s, duty survival={duty_survival:.2f}s")
