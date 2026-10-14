"""Derive 76 shop acquisitions from active shop YAML; cross-check the 57-row manifest."""
from __future__ import annotations

import argparse
from collections import defaultdict
import csv
import json
import math
from pathlib import Path

import yaml

OLD = [73, 74, 75, 77, 78, 79, 80] + list(range(86, 98))
ALL = {f'20a{i:05}' for i in OLD + list(range(400, 457))}


def read(path):
    return yaml.safe_load(path.read_text(encoding='utf-8-sig'))


def val(value):
    if isinstance(value, str) and '~' in value:
        a, b = map(float, value.split('~'))
        return (a + b) / 2
    return float(value)


def ref(row):
    r = row['itemId']
    return (r['ref'] if isinstance(r, dict) else r).removeprefix('item:')


def source_catalog(repo):
    result = defaultdict(list)
    for category, folder in [('gather', '42.features.gathering'), ('mob', '40.features.mob')]:
        for path in (repo / '40_filebase' / folder).rglob('*.yml'):
            master = read(path)
            if not isinstance(master, dict) or 'drops' not in master:
                continue
            for item in master['drops'].get('items', []):
                result[ref(item)].append({
                    'kind': category, 'path': path.relative_to(repo).as_posix(),
                    'id': master['id'], 'rate': float(item['rate']) / 100,
                    'amountMean': val(item['amount']),
                    'amountMin': float(str(item['amount']).split('~')[0]),
                    'health': master.get('maxHealth'),
                    'level': master.get('level')})
    return result


def item_catalog(repo):
    result = {}
    for path in (repo / '40_filebase/10.features.item').rglob('*.yml'):
        master = read(path)
        if isinstance(master, dict) and 'id' in master:
            result[master['id']] = master
    return result


def quantity_list(rows):
    return {ref(row): int(row['amount']) for row in rows}


def collect_shops(repo):
    shops = {}
    for name in ('weapon_craftsman_shop', 'armor_craftsman_shop'):
        path = repo / f'40_filebase/45.features.shop/v1.{name}.yml'
        master = read(path)
        for row in master['items']:
            iid = ref(row)
            if iid in ALL:
                assert iid not in shops
                shops[iid] = {'shopId': name, 'page': row['page'], 'slot': row['slot'],
                              'gold': row['priceGold'], 'items': quantity_list(row['requiredItems'])}
    assert set(shops) == ALL, (len(shops), ALL - set(shops))
    return shops


def check_manifest(repo, shops):
    path = repo / '00_docs/99_資料/field-equipment-final-integration/SHOP_CONNECTIONS.csv'
    with path.open(encoding='utf-8-sig', newline='') as file:
        rows = list(csv.DictReader(file))
    assert len(rows) == 57
    for row in rows:
        shop = shops[row['item_id']]
        materials = {part.split('x')[0]: int(part.split('x')[1])
                     for part in row['required_materials'].split(';')}
        assert materials == shop['items']
        assert int(row['price_gold']) == shop['gold']
        assert row['shop_id'] == shop['shopId']
        assert int(row['page']) == shop['page'] and int(row['logical_slot']) == shop['slot']
    return path.relative_to(repo).as_posix()


def select_source(material_id, catalog):
    choices = catalog[material_id]
    assert choices, material_id
    if material_id == '10a00100':
        choices = [s for s in choices if s['kind'] == 'mob' and s['rate'] == .05]
    if material_id == '10a00010':
        choices = [s for s in choices if s['kind'] == 'gather']
    assert choices
    rates = {(c['rate'], c['amountMean']) for c in choices}
    assert len(rates) == 1 or material_id == '10a00010', (material_id, rates)
    return choices[0]


def burden(items, sources, catalog):
    gather_breaks = 0.0
    gathering_seconds = 0.0
    expected_kills = 0.0
    source_rows = []
    stone = items.get('10a00010', 0)
    core_ore = sum(items.get(i, 0) for i in ('10a00086', '10a00087', '10a00089'))
    guaranteed_stone = 2 * core_ore
    extra_stone = max(0, stone - guaranteed_stone)
    for iid, quantity in items.items():
        source = select_source(iid, sources)
        if iid == '10a00010':
            quantity = extra_stone
        units = quantity / (source['rate'] * source['amountMean'])
        if source['kind'] == 'gather':
            assert source['health'] is not None
            ticks = max(0, math.ceil(source['health'] / 4) - 1) * 8
            gather_breaks += units
            gathering_seconds += units * ticks / 20
        else:
            expected_kills += units
        source_rows.append({'materialId': iid, 'required': items[iid],
                            'independentRequired': quantity, 'source': source['path'],
                            'sourceLevel': source['level'], 'dropRate': source['rate'],
                            'expectedEvents': round(units, 6)})
    bag_slots = sum(math.ceil(q / int(catalog[iid]['maxStack'])) for iid, q in items.items()) + 1
    return {'gatherBreaks': gather_breaks, 'gatherSecondsSpeed4': gathering_seconds,
            'expectedMobKills': expected_kills, 'bagSlotsWithOutput': bag_slots,
            'guaranteedStone': min(stone, guaranteed_stone),
            'extraStone': extra_stone, 'sources': source_rows}


def run(repo, out):
    shops = collect_shops(repo)
    manifest = check_manifest(repo, shops)
    sources = source_catalog(repo)
    catalog = item_catalog(repo)
    results = []
    for iid in sorted(shops):
        shop = shops[iid]
        gear = catalog[iid]
        evos = gear['equipment'].get('transcendence', [])
        assert len(evos) == 1
        evo = evos[0]
        evo_items = quantity_list(evo['requiredMaterials'])
        shop_burden = burden(shop['items'], sources, catalog)
        evo_burden = burden(evo_items, sources, catalog)
        series = ('eriva' if iid in {f'20a{i:05}' for i in OLD} else
                  'lapis' if int(iid[3:]) < 419 else
                  'lumina' if int(iid[3:]) < 438 else 'astra')
        results.append({'itemId': iid, 'series': series, 'slot': gear['equipment']['slot'],
                        'tag': gear['equipment'].get('tag', ''),
                        'requiredLevel': gear['equipment']['requiredLevel'],
                        'shop': shop, 'evolution': {'gold': evo['requiredCurrency'],
                                                  'items': evo_items},
                        'shopBurden': shop_burden, 'evolutionBurden': evo_burden})
    assert len(results) == 76
    (out / 'acquisition_76.json').write_text(json.dumps({
        'shopManifest': manifest, 'shopMasters': [
            '40_filebase/45.features.shop/v1.weapon_craftsman_shop.yml',
            '40_filebase/45.features.shop/v1.armor_craftsman_shop.yml'],
        'items': results}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    rows = []
    for row in results:
        a, b = row['shopBurden'], row['evolutionBurden']
        rows.append({'itemId': row['itemId'], 'series': row['series'], 'slot': row['slot'],
                     'tag': row['tag'], 'requiredLevel': row['requiredLevel'],
                     'shopGold': row['shop']['gold'], 'evolutionGold': row['evolution']['gold'],
                     'shopGatherBreaks': round(a['gatherBreaks'], 3),
                     'shopGatherSecondsSpeed4': round(a['gatherSecondsSpeed4'], 3),
                     'shopMobKillsExpected': round(a['expectedMobKills'], 3),
                     'evolutionGatherBreaks': round(b['gatherBreaks'], 3),
                     'evolutionMobKillsExpected': round(b['expectedMobKills'], 3),
                     'shopBagSlotsWithOutput': a['bagSlotsWithOutput'],
                     'evolutionBagSlotsWithOutput': b['bagSlotsWithOutput'],
                     'stoneCoveredByOre': a['guaranteedStone'],
                     'extraStoneEvents': a['extraStone']})
    with (out / 'acquisition_76.csv').open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0]), lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)
    by_id = {row['itemId']: row for row in rows}
    old = OLD[:7]
    loadout_rows = []
    for series, ids in [('eriva', old + OLD[7:]),
                        ('lapis', list(range(400, 419))),
                        ('lumina', list(range(419, 438))),
                        ('astra', list(range(438, 457)))]:
        for index in range(7):
            start = 7 if index < 3 else 11 if index < 5 else 15
            selected = [by_id[f'20a{ids[index]:05}']] + [
                by_id[f'20a{j:05}'] for j in ids[start:start + 4]]
            loadout_rows.append({
                'series': series, 'weaponId': selected[0]['itemId'],
                'weaponTag': selected[0]['tag'],
                'armorIds': ';'.join(r['itemId'] for r in selected[1:]),
                'shopGold': sum(int(r['shopGold']) for r in selected),
                'evolutionGold': sum(int(r['evolutionGold']) for r in selected),
                'shopGatherBreaks': round(sum(float(r['shopGatherBreaks']) for r in selected), 3),
                'shopGatherSecondsSpeed4': round(sum(float(r['shopGatherSecondsSpeed4']) for r in selected), 3),
                'shopMobKillsExpected': round(sum(float(r['shopMobKillsExpected']) for r in selected), 3),
                'evolutionGatherBreaks': round(sum(float(r['evolutionGatherBreaks']) for r in selected), 3),
                'evolutionMobKillsExpected': round(sum(float(r['evolutionMobKillsExpected']) for r in selected), 3),
                'maxSinglePurchaseBagSlots': max(int(r['shopBagSlotsWithOutput']) for r in selected)})
    with (out / 'loadout_28.csv').open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=list(loadout_rows[0]), lineterminator='\n')
        writer.writeheader()
        writer.writerows(loadout_rows)
    print(json.dumps({'items': len(rows), 'manifestRows': 57,
                      'loadouts': len(loadout_rows),
                      'maxBagSlots': max(r['shopBagSlotsWithOutput'] for r in rows),
                      'maxExtraStone': max(r['extraStoneEvents'] for r in rows)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    run(args.repo, args.out)
