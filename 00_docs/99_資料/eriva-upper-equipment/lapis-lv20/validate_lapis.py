"""Read-only validation of the Lv20 package; does not build or contact a server."""
from pathlib import Path
from copy import deepcopy
from decimal import Decimal
import json
import math
import re
import subprocess
import sys
import yaml

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[4]
DOC = Path(__file__).resolve().parent
BASE = 'ce523363ca0060fcdf76c59f3e8b061d88ab3041'
PACKAGE_BASE = '0a30bedc1d8bf7eec4c2ca5f654dd621985e1f1a'
GEAR = ROOT / '40_filebase/10.features.item/20.equipment'
EXPECTED = [f'20a{i:05}' for i in range(400, 419)]
SETS = ['lapis_bastion', 'lapis_stride', 'lapis_arcana']

class UniqueLoader(yaml.SafeLoader):
    pass

def mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        assert key not in result, f'duplicate YAML key: {key}'
        result[key] = loader.construct_object(value_node, deep=deep)
    return result

UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)

def load(path):
    return yaml.load(path.read_text(encoding='utf-8-sig'), Loader=UniqueLoader)

def load_from_text(text):
    return yaml.load(text, Loader=UniqueLoader)

data, masters, root_ids = {}, {}, {}
for path in (ROOT / '40_filebase').rglob('*.yml'):
    value = load(path)
    data[path] = value
    if not isinstance(value, dict):
        continue
    parts = path.relative_to(ROOT / '40_filebase').parts
    namespace = parts[0] + ('/' + parts[1] if parts[0] == '80.shared.loot' else '')
    rows = [(namespace, value)] if 'id' in value else [
        (namespace + ':' + key, row) for key, entries in value.items()
        if isinstance(entries, list) for row in entries if isinstance(row, dict) and 'id' in row]
    for scope, row in rows:
        key = (scope, str(row['id']))
        assert key not in masters, f'duplicate namespace ID: {key}'
        masters[key] = path
    if 'id' in value:
        root_ids.setdefault(str(value['id']), []).append(path)

statuses = {s['id'] for s in load(ROOT / '40_filebase/75.shared.status/v1.status_types.yml')['statuses']}
tags = {s['id'] for s in load(ROOT / '40_filebase/76.shared.tag/v1.tags.yml')['tags'] if s['category'] == 'EQUIPMENT'}
manifest = json.loads((DOC / 'MATERIAL_MANIFEST.json').read_text(encoding='utf-8'))
assert [r['itemId'] for r in manifest['items']] == EXPECTED
assert manifest['baseCommit'] == PACKAGE_BASE
all_primary_slots, random_count = set(), 0
equipment, paths = {}, []

def validate_random(stat):
    r = stat['random']
    assert set(r) <= {'offsetMin', 'offsetMax', 'step', 'zeroChance'}
    assert {'offsetMin', 'offsetMax', 'step'} <= set(r)
    assert set(stat) == {'status', 'type', 'value', 'random'}
    assert isinstance(stat['value'], (int, float)) and not isinstance(stat['value'], bool)
    vals = [Decimal(str(stat['value']))] + [Decimal(str(r[k])) for k in ['offsetMin', 'offsetMax', 'step']]
    for d in vals:
        assert d.is_finite() and d.normalize().as_tuple().exponent >= -4
    base, low, high, step = vals
    assert low <= high and step > 0 and (high - low) % step == 0
    assert (high - low) / step + 1 <= 1000000
    for bound in [base + low, base + high]:
        assert Decimal(str(float(bound))) == bound and math.isfinite(float(bound))
    assert low == high or float(step) >= math.ulp(max(abs(float(base + low)), abs(float(base + high))))
    z = Decimal(str(r.get('zeroChance', 0)))
    assert z.is_finite() and 0 <= z <= 1
    assert z == 0 or not base + low <= 0 <= base + high

def validate_materials(rows):
    ids = [r['itemId'] for r in rows]
    assert len(ids) == len(set(ids))
    for r in rows:
        assert len(root_ids.get(r['itemId'], [])) == 1, r['itemId']
        item = data[root_ids[r['itemId']][0]]
        assert item['category'] == 'material' and 0 < r['amount'] <= item.get('maxStack', 64)

for i, row in enumerate(manifest['items']):
    iid = row['itemId']
    path = ROOT / row['masterPath']
    paths.append(path)
    d, e = load(path), load(path)['equipment']
    equipment[iid] = d
    assert root_ids[iid] == [path]
    assert re.fullmatch(r'v1\.' + iid + r'\.lapis_[a-z_]+\.yml', path.name)
    assert d['schemaVersion'] == 1 and d['id'] == iid and d['category'] == 'equipment'
    assert d['rarity'] == 'RARE' and d['maxStack'] == 1
    assert d['name'] == '&b' + row['name']
    assert d['iconGlint'] is True and 'customModelData' not in d
    assert e['requiredLevel'] == 20 and e['enchant'] == {'maxSlots': 2}
    assert path.read_text(encoding='utf-8').startswith('# design:\n#   motif: "鉱物 / ラピスの青い石"\n#   progression: 20\n')
    assert len({s['status'] for s in e['stats']}) == len(e['stats'])
    for stat in e['stats']:
        assert stat['status'] in statuses and stat['type'] == 'FLAT'
        if 'random' in stat:
            validate_random(stat)
            random_count += 1
        else:
            assert isinstance(stat['value'], (int, float))
    old = data[next(GEAR.glob(f'v1.{row["sourceTemplate"]}.*.yml'))]['equipment']
    if i < 7:
        tag = ['SWORD', 'HAMMER', 'SPEAR', 'SHORTBOW', 'LONGBOW', 'WAND', 'STAFF'][i]
        attack = 'MELEE_ATTACK' if i < 3 else 'RANGED_ATTACK' if i < 5 else 'MAGIC_ATTACK'
        assert e['slot'] == 'WEAPON' and e['tag'] == tag and tag in tags and e['handType'] == 'ONE'
        assert e['stats'][0] == {'status': attack, 'type': 'FLAT', 'value': 170, 'random': {'offsetMin': -7, 'offsetMax': 7, 'step': 1}}
        assert e['stats'][1]['value'] == 12 and e['stats'][1]['random'] == {'offsetMin': -2, 'offsetMax': 2, 'step': 1}
        assert e['rune']['maxSlots'] == {'random': '0~1'}
        ice = e['stats'][-1]
        assert ice == {'status': 'ICE_DAMAGE_INCREASE', 'type': 'FLAT', 'value': 0, 'random': {'offsetMin': 1, 'offsetMax': 4, 'step': 1, 'zeroChance': 0.97}}
        assert e['stats'][2:-1] == old['stats'][2:], 'weapon identity changed'
    else:
        j = i - 7
        assert e['slot'] == ['HEAD', 'CHEST', 'LEGS', 'FEET'][j % 4]
        assert e['setId'] == SETS[j // 4] and 'rune' not in e
        assert d['icon'] == ['LEATHER_HELMET', 'LEATHER_CHESTPLATE', 'LEATHER_LEGGINGS', 'LEATHER_BOOTS'][j % 4]
        assert re.fullmatch(r'#[0-9A-F]{6}', d['appearance']['color'])
        assert e['stats'][0]['status'] == 'DEFENSE'
        assert e['stats'][0]['random'] == {'offsetMin': -[4, 8, 5, 4][j % 4], 'offsetMax': [4, 8, 5, 4][j % 4], 'step': 1}
        assert all('random' not in s for s in e['stats'][1:])
    levels = e['enhance']['levels']
    assert e['enhance']['maxLevel'] == 15 and [r['level'] for r in levels] == list(range(1, 31))
    for lv in levels:
        n = lv['level']
        rate = 1 if n == 1 else .75 if n <= 4 else .5 if n <= 6 else .35 if n <= 8 else .2 if n == 9 else .05
        assert lv['successRate'] == rate and lv['failAction'] == 'NONE'
        if i < 7:
            amount = 3 if n <= 15 else 2
        else:
            part = (i - 7) % 4
            amount = ([2, 3, 2, 1] if n <= 15 else [1, 2, 1, 1])[part]
        assert lv['statIncrease'] == [{'status': e['stats'][0]['status'], 'type': 'FLAT', 'value': amount}]
        assert all(s['status'] in statuses and 'random' not in s for s in lv['statIncrease'])
    evo = e['transcendence']
    assert len(evo) == 1 and evo[0]['rank'] == 1 and evo[0]['requiredEnhanceLevel'] == 15
    assert evo[0]['overrides']['enhance']['maxLevel'] == 30
    assert evo[0]['requiredMaterials'] == row['evolution']['requiredItems']
    assert evo[0]['requiredCurrency'] == row['evolution']['requiredCurrency'] == 2500
    baseline_text = subprocess.check_output(
        ['git', 'show', f'{BASE}:{row["masterPath"]}'], cwd=ROOT, encoding='utf-8')
    expected_master = deepcopy(load_from_text(baseline_text))
    expected_equipment = expected_master['equipment']
    if i < 7:
        expected_equipment['stats'][0]['value'] = 170
        expected_equipment['stats'][0]['random'].update(offsetMin=-7, offsetMax=7)
        expected_equipment['stats'][1]['value'] = 12
    for level in expected_equipment['enhance']['levels']:
        n = level['level']
        amount = (3 if n <= 15 else 2) if i < 7 else (
            [2, 3, 2, 1][(i - 7) % 4] if n <= 15 else [1, 2, 1, 1][(i - 7) % 4])
        level['statIncrease'] = [{'status': expected_equipment['stats'][0]['status'], 'type': 'FLAT', 'value': amount}]
    assert d == expected_master, f'unexpected content change: {path.name}'
    validate_materials(evo[0]['requiredMaterials'])
    p = row['primaryRoute']
    a = row['optionalMobRoute']
    validate_materials(p['requiredItems'])
    validate_materials(a['requiredItems'])
    assert p['outputAmount'] == a['outputAmount'] == 1
    assert p['requiredCurrency'] == a['requiredCurrency'] > 0
    assert all(int(r['itemId'][3:]) < 200 for r in p['requiredItems'])
    assert a['requiredSourceMobLevel'] == 22 and a['status'] == 'proposal_only_pending_runtime_placement'
    assert a['shopPlacement'] is None
    slot = p['shopPlacement']
    assert (slot['shopId'], slot['page'], slot['logicalSlot']) not in all_primary_slots
    all_primary_slots.add((slot['shopId'], slot['page'], slot['logicalSlot']))
    assert slot['shopId'] in root_ids and slot['page'] == 1 and 0 <= slot['logicalSlot'] <= 27

for sid in SETS:
    path = GEAR / 'set_effect' / f'v1.{sid}.yml'
    d = load(path)
    paths.append(path)
    assert root_ids[sid] == [path] and d['schemaVersion'] == 1
    assert [r['count'] for r in d['pieces']] == [2, 4]
    attack = ['MELEE_ATTACK', 'RANGED_ATTACK', 'MAGIC_ATTACK'][SETS.index(sid)]
    assert d['pieces'][0]['stats'] == [{'status': attack, 'type': 'FLAT', 'value': 6}]
    assert d['pieces'][1]['stats'] == [{'status': 'ACCURACY', 'type': 'FLAT', 'value': 2}]
    assert sum(e['equipment'].get('setId') == sid for e in equipment.values()) == 4

for src in manifest['sources']:
    item = data[root_ids[src['itemId']][0]]
    assert re.sub(r'&[0-9a-fklmnor]', '', item['name']) == src['name']
    source = data[root_ids[src['sourceId']][0]]
    if src['sourceKind'] == 'gathering':
        assert source['level'] == src['sourceGatheringLevel'] and source['maxHealth'] == src['hp']
        assert src['requiredToolTag'] in source['requiredToolTags']
        drops = source['drops']['items']
        spawners = [v for p,v in data.items() if p.parent.name == '43.features.gathering.spawner']
        weights = [r['weight'] / sum(s['weight'] for s in v['spawnGatherings']) for v in spawners for r in v['spawnGatherings'] if r['gatheringId'] == 'gathering:' + src['sourceId']]
        assert src['spawnerWeightFraction'] in weights
    else:
        assert min(r['level'] for r in source['levels']) == src['sourceMinMobLevel']
        drops = source['drops']['items']
    def item_ref(row):
        value = row['itemId']
        return value['ref'] if isinstance(value, dict) else value
    selected = [r for r in drops if item_ref(r) == 'item:' + src['itemId']]
    assert len(selected) == 1 and float(selected[0]['rate']) == src['dropRatePercent']
    assert str(selected[0]['amount']) == str(src['amount'])

for start in [7, 11, 15]:
    primary = [r['primaryRoute']['requiredItems'] for r in manifest['items'][start:start+4]]
    total = [sum(m['amount'] for m in ms) for ms in primary]
    assert total[1] > max(total[0],total[2],total[3])
    assert sum(m['amount'] for m in primary[1] if m['itemId'] in ['10a00086','10a00087','10a00088','10a00089']) > 0

diff = subprocess.check_output(['git', '-c', 'core.quotepath=false', 'diff', '--name-only', BASE], cwd=ROOT, encoding='utf-8').splitlines()
untracked = subprocess.check_output(['git', '-c', 'core.quotepath=false', 'ls-files', '--others', '--exclude-standard'], cwd=ROOT, encoding='utf-8').splitlines()
allow_masters = {p.relative_to(ROOT).as_posix() for p in paths if p.parent == GEAR}
allow_docs = DOC.relative_to(ROOT).as_posix() + '/'
for p in set(diff + untracked):
    assert p in allow_masters or p.startswith(allow_docs), f'out of scope: {p}'
assert random_count == 33
print(json.dumps({'result':'PASS','baseCommit':BASE,'yamlFiles':len(data),'namespaceIds':len(masters),'equipment':19,'sets':3,'randomStats':random_count,'primaryRoutes':19,'optionalMobRoutes':19,'evolutionRoutes':19,'materialSources':10,'scope':'assigned masters and dedicated documents only','limits':'static validation; no runtime, DB, heavy build, deployment, develop merge or push'},ensure_ascii=False,indent=2))
