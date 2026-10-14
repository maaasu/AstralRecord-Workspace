"""manifestと採集定義から供給下限を計算する。移動・配置・実入手時間はモデル外。"""
from collections import defaultdict
from pathlib import Path
import json
import math

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def load(path):
    return yaml.safe_load(path.read_text(encoding='utf-8-sig'))


def run():
    manifest = json.loads((HERE/'material_manifest.json').read_text(encoding='utf-8'))
    spawner_path = '40_filebase/43.features.gathering.spawner/v1.verdia_cave_mining_spawner.yml'
    spawner = load(ROOT/spawner_path)
    weight_sum = sum(row['weight'] for row in spawner['spawnGatherings'])
    weight_by_source = {}
    node_rows = []
    for entry in spawner['spawnGatherings']:
        source_id = entry['gatheringId'].removeprefix('gathering:')
        source = load(ROOT/f'40_filebase/42.features.gathering/mining/v1.{source_id}.yml')
        probability = entry['weight']/weight_sum
        weight_by_source[source_id] = probability
        node_rows.append(dict(sourceId=source_id, weight=entry['weight'], probability=probability,
                              maxHealth=source['maxHealth'], hitsAtSpeed4=math.ceil(source['maxHealth']/4),
                              hitsAtSpeed10=math.ceil(source['maxHealth']/10)))
    totals, evolution_totals = defaultdict(int), defaultdict(int)
    for product in manifest['products']:
        for material in product['requiredItems']:
            totals[material['itemId']] += material['amount']
        for material in product['evolution']['requiredMaterials']:
            evolution_totals[material['itemId']] += material['amount']
    source_by_item = {s['itemId']: s for s in manifest['materialSources']}
    ore_ids = {key for key, s in source_by_item.items() if s['kind']=='gathering' and key!='10a00010'}
    mean_node_seconds = {speed: sum(row['probability']*row[f'hitsAtSpeed{speed}']*.4 for row in node_rows)
                         for speed in (4, 10)}

    def supply_lower_bound(with_evolution):
        ore_rows=[]
        for key in sorted(ore_ids):
            qty=totals[key]+(evolution_totals[key] if with_evolution else 0)
            probability=weight_by_source[source_by_item[key]['sourceId']]
            ore_rows.append(dict(itemId=key, amount=qty, probability=probability, expectedNodesForThisQuota=qty/probability))
        # E[max(N_i)] >= max(E[N_i]); joint completion takes at least this in expectation.
        nodes=max(r['expectedNodesForThisQuota'] for r in ore_rows)
        return dict(ores=ore_rows, expectedNodeLowerBound=nodes,
                    activeMinutesLowerBound={str(speed): nodes*seconds/60 for speed, seconds in mean_node_seconds.items()},
                    stoneMeanAtNodeLowerBound=nodes*3)

    mob_rows=[]
    for key, source in sorted(source_by_item.items()):
        if source['kind']=='mob':
            qty=totals[key]
            mob_rows.append(dict(itemId=key, purchaseAmount=qty, evolutionAmount=evolution_totals[key],
                                 dropRatePercent=source['dropRatePercent'], minMobLevel=source['sourceMinLevel'],
                                 expectedKillsForPurchases=qty/(source['dropRatePercent']/100)))
    report=dict(model='Uncontested weighted cave-node sampling; max quota expectation is a lower bound, not measured completion time',
                spawnerPath=spawner_path, spawnerDefinition=spawner,
                assumptions=dict(secondsPerHit=.4, luckBonus=0, oreDropAmount=1, stoneAmount='2~4',stoneMean=3,
                                 exclusions=['travel','respawn wait','competition','tool repair','Gold acquisition','mob placement']),
                nodes=node_rows, meanNodeSeconds=mean_node_seconds, purchaseMaterialTotals=dict(sorted(totals.items())),
                evolutionMaterialTotals=dict(sorted(evolution_totals.items())),
                purchaseOnly=supply_lower_bound(False), purchaseAndEvolution=supply_lower_bound(True),
                mobSources=mob_rows, expectedMobKillsForAllPurchases=sum(r['expectedKillsForPurchases'] for r in mob_rows),
                purchaseGold=sum(p['priceGold'] for p in manifest['products']),
                evolutionGold=sum(p['evolution']['requiredCurrency'] for p in manifest['products']),
                limits=['gathering level is not a player-level requirement',
                        'world/spawner placement and source access not accepted at runtime',
                        '5-tick spawner evaluation is not a guaranteed respawn time',
                        'purchase planning conservatively budgets 32 slots; current base INVENTORY_SLOTS is 36 and output space is checked before consumption'])
    (HERE/'supply_reference.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:report[k] for k in ('purchaseOnly','purchaseAndEvolution','expectedMobKillsForAllPurchases','purchaseGold','evolutionGold')},ensure_ascii=False,indent=2))


if __name__ == '__main__':
    run()
