"""Write a compact human review from the generated combat and acquisition tables."""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
import yaml

BASELINE = 'b046beab1f02c60fd4ccd6d6086f7ef49034327a'
ORDER = ('eriva', 'lapis', 'lumina', 'astra')


def load_csv(out, name):
    with (out / name).open(encoding='utf-8-sig', newline='') as file:
        return list(csv.DictReader(file))


def num(value, digits=2):
    return '—' if value is None or value == '' else f'{float(value):,.{digits}f}'


def main(repo, out, source_revision, inputs_unchanged):
    head = source_revision
    combat = load_csv(out, 'combat_cases.csv')
    acquisition = load_csv(out, 'acquisition_76.csv')
    loadouts = load_csv(out, 'loadout_28.csv')
    mobs = json.loads((out / 'mob_cases.json').read_text(encoding='utf-8'))
    residuals = json.loads((out / 'player_residuals.json').read_text(encoding='utf-8'))
    by = {(r['mobLevel'], r['mobRole'], r['series'], r['weaponTag'], r['enhance'], r['mode']): r
          for r in combat}
    groups = defaultdict(dict)
    for row in combat:
        key = (row['mobLevel'], row['mobRole'], row['weaponTag'], row['enhance'], row['mode'])
        groups[key][row['series']] = float(row['TTK'])
    inversions = [{'mobLevel': k[0], 'mobRole': k[1], 'weaponTag': k[2],
                   'enhance': int(k[3]), 'mode': k[4], 'ttkBySeries': v}
                  for k, v in groups.items() if any(v[ORDER[i]] < v[ORDER[i + 1]]
                                                    for i in range(3))]
    assert len(inversions) == 14
    assert all(r['mobLevel'] == '30' and r['enhance'] == 30
               and r['mode'] == 'expected_unenchanted' for r in inversions)
    source = next(r for r in combat if r['mobLevel'] == '30' and r['mobRole'] == 'normal'
                  and r['series'] == 'astra' and r['weaponTag'] == 'SWORD'
                  and r['enhance'] == '30' and r['mode'] == 'offense_max')
    max_bag = max(acquisition, key=lambda r: int(r['shopBagSlotsWithOutput']))
    summary = {
        'repo': str(repo), 'head': head, 'reviewBaseline': BASELINE,
        'inputsUnchangedSinceBaseline': inputs_unchanged,
        'combatRows': len(combat), 'gearRows': len(load_csv(out, 'equipment_76x3.csv')),
        'shopRows': len(acquisition), 'newShopRows': 57, 'loadoutRows': len(loadouts),
        'inversionCount': len(inversions), 'inversions': inversions,
        'astraLv30SwordMaxNoneR': float(source['R']),
        'astraLv30SwordMaxNoneTtk': float(source['TTK']),
        'astraLv30RAnchorRatio': float(source['R']) / 3318,
        'maximumSinglePurchaseBagSlots': int(max_bag['shopBagSlotsWithOutput']),
        'maximumSinglePurchaseItem': max_bag['itemId'],
        'limits': ['analytical expectation, not 100 real fights',
                   'Lv25 exact ordinary ENEMY does not exist; uses Lv24 proxy',
                   'Lv25 strong slot is a BOSS, not ordinary strong ENEMY',
                   'optimal enchant effect IDs are conditional selections; obtainability not priced',
                   'mob AI, skills, actual attack intervals, movement and tool travel omitted']}
    (out / 'SUMMARY.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')

    lines = [
        '# 最終装備57品の実Mob・取得負担追加比較', '',
        f'対象: `{repo}`、読取時HEAD `{head}`。装備57品（ラピス/ルミナ/アストラ各19）と旧エリヴァ19品を比較した。'
        f'数値監査の起点 `{BASELINE}` からの対象入力同一性: '
        + ('Git差分で確認済み。' if inputs_unchanged else 'この実行では未検証。')
        + '計算は明示した出力ディレクトリだけへ書き出す。ここには比較スクリプトと受入時の結果を保存した。', '',
        '## 条件と再現', '',
        '- `heavy_balance.py` は実YAMLの76装備×3強化段階の主値228行と、8実Mob profile×4系列×7武器種×3強化段階×4条件の戦闘2,688行を作る。'
        '近接3武器はガード4防具、遠隔2武器はハンター4防具、魔法2武器はメイジ4防具を装備する。',
        '- 各比較Lvのプレイヤー非装備残差は、同Lv系列のソード+ガード4防具の+15で `00_docs/60_戦闘バランス設計書/02-成長曲線.md`'
        'のR/D/HPアンカーに一致するよう校正し、**同じ比較Lv内の全系列・全敵で固定**した。基本能力残差42.8は全Lv共通。'
        'Lvをまたいで単一のLv25残差を使うとLv15敵の通常攻撃が防御式上0になるため、Lvごとに校正した。',
        '- エリヴァの旧二重min/maxは攻撃下限・上限それぞれを区間抽選する定義で、新系列の保存済み単一scalarとは分布が異なる。'
        'ここでは区間端の平均中心を比較用期待値とし、最大は両端上限を同時に取った攻撃側有利な境界とする。',
        '- `scenario=legal` は装備requiredLevel≤比較Lv、`counterfactual_same_residual` は同条件の参考値のみ。'
        'Lv15合法はエリヴァ、Lv20はエリヴァ/ラピス、Lv25はその3系列、Lv30は4系列。',
        '- 基準は実YAMLの期待主値、無刻印・ルーンなし・会心なし。'
        '`enchant_mean_opt` は上級刻印003の効果IDを最適選択し、効果量と本体乱数は**平均**、会心5.5%/150%、ルーンATTACK+5を条件付ける。'
        '`offense_max` は同じ候補から最大個体・刻印最大値を探索した無属性側の極値。'
        '`offense_max_aligned` は武器の希少属性最大値とその属性に合う刻印を探索し、敵弱点・耐性は0とした属性側の極値。'
        '効果IDの選択・入手確率は平均化しておらず、後二者は上限保証でも通常ドロップの期待値でもない。',
        '- 共通式: `R=(非装備ATTACK+セット攻撃+装備内FLAT×SCALAR)×(1+(非装備基本能力+装備基本能力)/100)`、'
        '`防御倍率=max(0,1-D/R/2.5)^(log(.5)/log(.6))`。'
        '攻撃間隔Player1.0秒/敵1.5秒、命中はPlayer95%+4部位セット2%−敵EVASION、敵ACCURACY−Player回避3.5%。'
        'Player最終1.3、通常/skill構成60/40%とskill込み係数1.6を固定。Mobの実AI/skill/間隔、Shield、属性弱点、位置取り、行動ロス、100戦乱数は含まない。',
        '', '### 実Mob profile', '',
        '| 比較Lv | 役割 | id | 実Lv | HP | ATTACK | DEFENSE | profile |',
        '|--:|:--|:--|--:|--:|--:|--:|:--|']
    for m in mobs:
        lines.append(f"| {m['level']} | {m['role']} | {m['id']} | {m['sourceLevel']} | {m['hp']:,} | {m['attack']:,} | {m['defense']:,} | `{m['profile']}` |")
    lines += ['', 'Lv15/20/30の強敵は同Lvの通常敵よりHP・攻撃・防御がすべて高い実ENEMY。'
              'Lv25のENEMYは同Lvprofileが0件なので通常枠はLv24の実個体を代理とし、'
              '強枠は唯一のLv25 BOSSである。Lv25の通常/強敵倍率を同Lvで測った結果とは扱わない。',
              '', '### 校正した非装備残差', '',
              '| 比較Lv | 校正系列 +15 | ATTACK | 基本能力 | DEFENSE | HP | R/D/HPアンカー |',
              '|--:|:--|--:|--:|--:|--:|:--|']
    for level in (15, 20, 25, 30):
        r = residuals[str(level)]
        a = r['anchor']
        lines.append(f"| {level} | {r['calibrationSeries']} | {r['attack']:.2f} | 42.8 | {r['defense']:.0f} | {r['hp']:.0f} | {a['R']}/{a['D']}/{a['HP']} |")
    lines += ['', '### 希少属性抽選（実ソードYAML）', '',
              '| 系列 | 本体属性 | 非0確率/行 | 1行最大補正 |',
              '|:--|:--|--:|--:|']
    for series, iid in [('eriva', 73), ('lapis', 400), ('lumina', 419), ('astra', 438)]:
        path, = (repo / '40_filebase/10.features.item/20.equipment').glob(f'v1.20a{iid:05}.*.yml')
        master = yaml.safe_load(path.read_text(encoding='utf-8-sig'))
        rare = [s for s in master['equipment']['stats'] if s['status'] in
                ('FIRE_DAMAGE_INCREASE', 'ICE_DAMAGE_INCREASE', 'LIGHTNING_DAMAGE_INCREASE')]
        if rare:
            label = ', '.join(s['status'].removesuffix('_DAMAGE_INCREASE') for s in rare)
            chance = (1 - rare[0]['random']['zeroChance']) * 100
            maximum = rare[0]['value'] + rare[0]['random']['offsetMax']
            lines.append(f'| {series} | {label} | {chance:.0f}% | +{maximum:g}% |')
        else:
            lines.append(f'| {series} | なし | — | — |')
    lines += ['', '各属性行の非0確率であり、理想最大値を引く確率ではない。'
              '属性一致列は1属性を選び、その最大補正が適用される攻撃を仮定する。'
              '敵の弱点・耐性は加算しない。', '']
    lines += ['', '## 戦闘比較（合法装備、ソード+ガード4防具、+15）', '',
              '| 敵 | 系列 | 無刻印期待TTK | 平均刻印最適TTK | 無属性最大TTK | 属性一致最大TTK | 無刻印耐久 |',
              '|:--|:--|--:|--:|--:|--:|--:|']
    for m in mobs:
        for series in ORDER:
            key = (str(m['level']), m['role'], series, 'SWORD', '15')
            base = by[key + ('expected_unenchanted',)]
            if base['scenario'] != 'legal':
                continue
            mean = by[key + ('enchant_mean_opt',)]
            peak = by[key + ('offense_max',)]
            aligned = by[key + ('offense_max_aligned',)]
            lines.append(f"| Lv{m['level']} {m['role']} | {series} | {num(base['TTK'])} | {num(mean['TTK'])} | {num(peak['TTK'])} | {num(aligned['TTK'])} | {num(base['survival'])} |")
    lines += ['', '表は秒。全7武器・+0/+15/+30と反実仮想列は `combat_cases.csv/json` を参照。'
              'Lv25 BOSSはHP104,000で、単独TTKが長いことを通常敵バランスの失敗と解釈しない。',
              '', '### 序列と極端値', '']
    old = by[('30', 'normal', 'eriva', 'SWORD', '30', 'expected_unenchanted')]
    lapis = by[('30', 'normal', 'lapis', 'SWORD', '30', 'expected_unenchanted')]
    astra_peak = by[('30', 'normal', 'astra', 'SWORD', '30', 'offense_max')]
    astra_hammer = by[('30', 'normal', 'astra', 'HAMMER', '30', 'offense_max')]
    lines += [f'- +0/+15の無刻印期待、および平均/最大刻印条件は全7武器でエリヴァ→ラピス→ルミナ→アストラのTTK短縮順。'
              f'+30無刻印期待のみ、Lv30通常/強敵×7武器の14群で旧エリヴァがラピスを僅かに上回る。'
              f'Lv30通常ソードは旧 `R={num(old["R"])} / TTK={num(old["TTK"],3)}秒`、'
              f'ラピス `R={num(lapis["R"])} / TTK={num(lapis["TTK"],3)}秒`（約{(float(lapis["TTK"])/float(old["TTK"])-1)*100:.2f}%遅い）。'
              '旧+30の対応能力成長が高Lv残差で効く。既存投資を保護する代わりに完全な単調性は満たさない。',
              f'- Lv30実通常敵でアストラ+30ソードの無属性最大は `R={num(astra_peak["R"])} / TTK={num(astra_peak["TTK"],3)}秒`。'
              f'RはLv30アンカー3,318の{float(astra_peak["R"])/3318*100:.1f}%で130%目安を超える。'
              f'ハンマーは固定の通常攻撃+120%を含み `TTK={num(astra_hammer["TTK"],3)}秒`。'
              '武器種固有の攻撃間隔/操作コストを置いていないため、ハンマーの実戦最短時間ではない。',
              '- 希少属性だけを同属性攻撃へ合わせても、敵弱点なしでは無属性特化のNONE刻印/防具構成より通常遅い。'
              '上級刻印の候補・4枠による極値リスクは本体の属性希少値だけでは抑えられない。',
              '', '## Shop取得負担', '',
              '`acquisition.py` は実Shop 2 masterを正本とし、57件を主接続表 `SHOP_CONNECTIONS.csv` と価格・素材・ページ・slotまで照合した。'
              '旧19件も同じ実Shopから取得する。表は「ソード1本＋ガード4防具」の購入合計で、進化1回の費用は別列。'
              '+15/+30の強化試行費、刻印・ルーン取得費は未計上。', '',
              '| 系列 | Shop Gold | 採集破壊回数 | 速度4純採集秒 | Mob期待討伐 | 進化Gold | 進化素材の採集/討伐 | 1回の購入最大BAG枠 |',
              '|:--|--:|--:|--:|--:|--:|:--|--:|']
    for series in ORDER:
        row = next(r for r in loadouts if r['series'] == series and r['weaponTag'] == 'SWORD')
        lines.append(f"| {series} | {int(row['shopGold']):,} | {num(row['shopGatherBreaks'],0)} | {num(row['shopGatherSecondsSpeed4'],1)} | {num(row['shopMobKillsExpected'],2)} | {int(row['evolutionGold']):,} | {num(row['evolutionGatherBreaks'],0)} / {num(row['evolutionMobKillsExpected'],2)} | {row['maxSinglePurchaseBagSlots']} |")
    lines += ['',
              '- 採集素材はsource profileのHPを読み、速度4なら1破壊あたり `0.4×(ceil(HP/4)−1)` 秒。'
              '初撃は即時、以後8tick間隔。移動・探索・再出現・道具修理は別。速度4ツールの初期取得費も別。'
              'Lv2鉱石を速度2の初期道具で代用できない。',
              '- Mob素材はluck0・定義drop率と個数から `必要数/(確率×1回個数)`。'
              'すべて35%ではなくエリヴァ毛皮35%/樹脂5%、新Lv22素材は40%/45%、後期素材は35%。'
              '複数Mobから樹脂5%が落ちても、同一討伐で各rateを合算しない。討伐移動・待機・配置未確認は別。',
              '- Astraの石ころ `10a00010` は主要鉱石1破壊で最低2個を同時に得る。'
              '全19商品は鉱石破壊だけで要求石ころが保証されるため、石ころ分の採集を二重計上しない。',
              f'- 素材maxStack64と購入前の完成品空き1枠で最大は `{max_bag["itemId"]}` の{max_bag["shopBagSlotsWithOutput"]}枠。'
              'BAGは `StatusDefaults.INVENTORY_SLOTS=36` を基礎値として `PlayerInventoryState` が保持し、'
              '`StatusService` が確定ステータス値を反映する。設計上はLv5ごと+1、装備等補正があり上限なし。'
              '`NormalInventoryLayout.DEFAULT_CAPACITY=32` はBAG以外の通常inventory値で、可視24枠はページ表示数。'
              '14≤36（基礎）、14≤32（非BAG値）、14≤24（表示1ページ分）の数値比較は成り立つが、'
              '購入可否の容量判定には実効BAG値だけを使う。`inventory.bag.slotCapacity` という設定keyは現行repoに見つからない。'
              '根拠は `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java:9028-9041`、'
              '`.../feature/inventory/state/PlayerInventoryState.java:60`、'
              '`.../feature/status/model/StatusDefaults.java:9`、'
              '`00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md:54`。'
              '既存アイテム占有を含めて購入時に必要空き枠を確認する。GoldはBAG素材枠に含めない。',
              '- Gold取得時間は収入経路/純増Gold毎時が未定なので換算しない。'
              '採集・討伐の副産物売却を含めるなら同じ収入を重複計上しない。',
              '', '## 判断', '',
              '主値期待と+15の合法更新は系列順に伸び、旧エリヴァ+30はLv30の無刻印期待でラピスとほぼ同等か僅かに上回る。'
              'これは既存投資保護との明示的な交換条件として許容可否を決めるべき境界である。'
              '通常育成の本体をさらに弱くするとLv20以降の入手費（Gold・採集・討伐）に対する更新感を落とす。'
              '一方、最適上級刻印ではLv30でR130%目安超過と1秒前後の極短TTKが残る。'
              '本体数値だけで両方を解くのは難しく、共通刻印の倍率/付与上限・入手供給の設計と職別実戦測定で受入判断する。'
              '今回の数式は実戦100回の代わりにはならない。',
              '', '## 再実行', '',
              'PowerShellでPython 3.14とPyYAMLを使用する。`heavy_balance.py`、`acquisition.py`、'
              '`summarize.py`、`mob_selection.json` を同じディレクトリに置き、明示した `--out` にだけ出力する。', '',
              '```powershell',
              "$env:PYTHONIOENCODING='utf-8'",
              "$python = 'C:\\Users\\kaede\\AppData\\Local\\Python\\pythoncore-3.14-64\\python.exe'",
              f"$scripts = '{Path(__file__).resolve().parent}' # このREPORTを生成した正本scriptの配置先",
              "$repo = 'E:\\AstralRecord-Worktrees\\field-equipment-final-integration'",
              "$out = 'C:\\Users\\kaede\\AppData\\Local\\Temp\\field-equipment-heavy-balance-output'",
              "$sourceRevision = '7928471b0996e4e56940b90692310841c8f625bc' # 実行時のHEADに更新",
              "& $python -B -X utf8 (Join-Path $scripts 'heavy_balance.py') --repo $repo --out $out",
              "& $python -B -X utf8 (Join-Path $scripts 'acquisition.py') --repo $repo --out $out",
              "& $python -B -X utf8 (Join-Path $scripts 'summarize.py') --repo $repo --out $out --source-revision $sourceRevision",
              '# b046から対象入力の差分なしを別途確認した実行だけ、最後の行に --inputs-unchanged-since-baseline を付ける。',
              '```', '']
    (out / 'REPORT.md').write_text('\n'.join(lines), encoding='utf-8', newline='\n')
    print(json.dumps({'summary': 'PASS', 'inversions': len(inversions),
                      'combatRows': len(combat), 'acquisitionRows': len(acquisition)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--source-revision', required=True,
                        help='repoの読取時HEADを明示する。省略・推定はしない。')
    parser.add_argument('--inputs-unchanged-since-baseline', action='store_true',
                        help='起点b046から対象入力のGit差分なしを別途確認した場合だけ指定する。')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    main(args.repo, args.out, args.source_revision, args.inputs_unchanged_since_baseline)
