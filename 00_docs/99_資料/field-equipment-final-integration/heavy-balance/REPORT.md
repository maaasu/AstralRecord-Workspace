# 最終装備57品の実Mob・取得負担追加比較

対象: `E:\AstralRecord-Worktrees\field-equipment-final-integration`、読取時HEAD `35bbdf3475dc4c65ab0ca3cf1a994990f6b54145`。装備57品（ラピス/ルミナ/アストラ各19）と旧エリヴァ19品を比較した。数値監査の起点 `b046beab1f02c60fd4ccd6d6086f7ef49034327a` からの対象入力同一性: Git差分で確認済み。計算は明示した出力ディレクトリだけへ書き出す。ここには比較スクリプトと受入時の結果を保存した。

## 条件と再現

- `heavy_balance.py` は実YAMLの76装備×3強化段階の主値228行と、8実Mob profile×4系列×7武器種×3強化段階×4条件の戦闘2,688行を作る。近接3武器はガード4防具、遠隔2武器はハンター4防具、魔法2武器はメイジ4防具を装備する。
- 各比較Lvのプレイヤー非装備残差は、同Lv系列のソード+ガード4防具の+15で `00_docs/60_戦闘バランス設計書/02-成長曲線.md`のR/D/HPアンカーに一致するよう校正し、**同じ比較Lv内の全系列・全敵で固定**した。基本能力残差42.8は全Lv共通。Lvをまたいで単一のLv25残差を使うとLv15敵の通常攻撃が防御式上0になるため、Lvごとに校正した。
- エリヴァの旧二重min/maxは攻撃下限・上限それぞれを区間抽選する定義で、新系列の保存済み単一scalarとは分布が異なる。ここでは区間端の平均中心を比較用期待値とし、最大は両端上限を同時に取った攻撃側有利な境界とする。
- `scenario=legal` は装備requiredLevel≤比較Lv、`counterfactual_same_residual` は同条件の参考値のみ。Lv15合法はエリヴァ、Lv20はエリヴァ/ラピス、Lv25はその3系列、Lv30は4系列。
- 基準は実YAMLの期待主値、無刻印・ルーンなし・会心なし。`enchant_mean_opt` は上級刻印003の効果IDを最適選択し、効果量と本体乱数は**平均**、会心5.5%/150%、ルーンATTACK+5を条件付ける。`offense_max` は同じ候補から最大個体・刻印最大値を探索した無属性側の極値。`offense_max_aligned` は武器の希少属性最大値とその属性に合う刻印を探索し、敵弱点・耐性は0とした属性側の極値。効果IDの選択・入手確率は平均化しておらず、後二者は上限保証でも通常ドロップの期待値でもない。
- 共通式: `R=(非装備ATTACK+セット攻撃+装備内FLAT×SCALAR)×(1+(非装備基本能力+装備基本能力)/100)`、`防御倍率=max(0,1-D/R/2.5)^(log(.5)/log(.6))`。攻撃間隔Player1.0秒/敵1.5秒、命中はPlayer95%+4部位セット2%−敵EVASION、敵ACCURACY−Player回避3.5%。Player最終1.3、通常/skill構成60/40%とskill込み係数1.6を固定。Mobの実AI/skill/間隔、Shield、属性弱点、位置取り、行動ロス、100戦乱数は含まない。

### 実Mob profile

| 比較Lv | 役割 | id | 実Lv | HP | ATTACK | DEFENSE | profile |
|--:|:--|:--|--:|--:|--:|--:|:--|
| 15 | normal | eriva_reed_archer | 15 | 3,800 | 610 | 660 | `40_filebase/40.features.mob/enemy/v1.eriva_reed_archer.yml` |
| 15 | strong | eriva_stonehorn_ram | 15 | 6,900 | 710 | 850 | `40_filebase/40.features.mob/enemy/v1.eriva_stonehorn_ram.yml` |
| 20 | normal | flower_archer_skeleton | 20 | 8,000 | 900 | 1,394 | `40_filebase/40.features.mob/enemy/v1.flower_archer_skeleton.yml` |
| 20 | strong | manor_thorn_guard | 20 | 12,000 | 1,159 | 1,743 | `40_filebase/40.features.mob/enemy/v1.manor_thorn_guard.yml` |
| 25 | normal_proxy_lv24 | field_moss_fox | 24 | 11,160 | 2,000 | 1,986 | `40_filebase/40.features.mob/enemy/v1.field_moss_fox.yml` |
| 25 | boss_not_strong | mine_bell_knocker | 25 | 104,000 | 2,826 | 2,604 | `40_filebase/40.features.mob/boss/v1.mine_bell_knocker.yml` |
| 30 | normal | field_sunhare | 30 | 16,150 | 2,926 | 2,654 | `40_filebase/40.features.mob/enemy/v1.field_sunhare.yml` |
| 30 | strong | field_plain_ram | 30 | 20,900 | 3,270 | 3,484 | `40_filebase/40.features.mob/enemy/v1.field_plain_ram.yml` |

Lv15/20/30の強敵は同Lvの通常敵よりHP・攻撃・防御がすべて高い実ENEMY。Lv25のENEMYは同Lvprofileが0件なので通常枠はLv24の実個体を代理とし、強枠は唯一のLv25 BOSSである。Lv25の通常/強敵倍率を同Lvで測った結果とは扱わない。

### 校正した非装備残差

| 比較Lv | 校正系列 +15 | ATTACK | 基本能力 | DEFENSE | HP | R/D/HPアンカー |
|--:|:--|--:|--:|--:|--:|:--|
| 15 | eriva | 374.58 | 42.8 | 507 | 1800 | 767/797/2200 |
| 20 | lapis | 679.52 | 42.8 | 979 | 3440 | 1394/1449/4000 |
| 25 | lumina | 1195.88 | 42.8 | 1855 | 5610 | 2264/2355/6500 |
| 30 | astra | 1774.42 | 42.8 | 2792 | 8380 | 3318/3442/9500 |

### 希少属性抽選（実ソードYAML）

| 系列 | 本体属性 | 非0確率/行 | 1行最大補正 |
|:--|:--|--:|--:|
| eriva | なし | — | — |
| lapis | ICE | 3% | +4% |
| lumina | FIRE, LIGHTNING | 4% | +7% |
| astra | FIRE, ICE, LIGHTNING | 5% | +10% |

各属性行の非0確率であり、理想最大値を引く確率ではない。属性一致列は1属性を選び、その最大補正が適用される攻撃を仮定する。敵の弱点・耐性は加算しない。


## 戦闘比較（合法装備、ソード+ガード4防具、+15）

| 敵 | 系列 | 無刻印期待TTK | 平均刻印最適TTK | 無属性最大TTK | 属性一致最大TTK | 無刻印耐久 |
|:--|:--|--:|--:|--:|--:|--:|
| Lv15 normal | eriva | 4.49 | 2.94 | 1.76 | 1.76 | 16.13 |
| Lv15 strong | eriva | 10.19 | 6.20 | 3.53 | 3.53 | 11.40 |
| Lv20 normal | eriva | 7.49 | 4.81 | 3.02 | 3.02 | 21.57 |
| Lv20 normal | lapis | 5.87 | 2.82 | 1.64 | 2.12 | 29.59 |
| Lv20 strong | eriva | 15.37 | 8.90 | 5.19 | 5.19 | 11.87 |
| Lv20 strong | lapis | 11.28 | 4.99 | 2.78 | 3.59 | 14.50 |
| Lv25 normal_proxy_lv24 | eriva | 5.54 | 3.71 | 2.58 | 2.58 | 10.54 |
| Lv25 normal_proxy_lv24 | lapis | 4.79 | 2.48 | 1.52 | 1.93 | 11.82 |
| Lv25 normal_proxy_lv24 | lumina | 4.53 | 2.16 | 1.24 | 1.53 | 12.64 |
| Lv25 boss_not_strong | eriva | 69.91 | 43.51 | 28.65 | 28.65 | 5.70 |
| Lv25 boss_not_strong | lapis | 58.12 | 27.85 | 16.33 | 21.08 | 6.15 |
| Lv25 boss_not_strong | lumina | 54.22 | 24.04 | 13.11 | 16.37 | 6.54 |
| Lv30 normal | eriva | 5.42 | 3.69 | 2.63 | 2.63 | 10.33 |
| Lv30 normal | lapis | 4.89 | 2.60 | 1.65 | 2.01 | 11.16 |
| Lv30 normal | lumina | 4.68 | 2.30 | 1.34 | 1.66 | 11.69 |
| Lv30 normal | astra | 4.34 | 2.02 | 1.14 | 1.31 | 12.61 |
| Lv30 strong | eriva | 8.88 | 5.67 | 3.88 | 3.88 | 8.37 |
| Lv30 strong | lapis | 7.81 | 3.92 | 2.37 | 2.94 | 8.94 |
| Lv30 strong | lumina | 7.39 | 3.43 | 1.93 | 2.41 | 9.35 |
| Lv30 strong | astra | 6.75 | 2.95 | 1.63 | 1.87 | 10.00 |

表は秒。全7武器・+0/+15/+30と反実仮想列は `combat_cases.csv/json` を参照。Lv25 BOSSはHP104,000で、単独TTKが長いことを通常敵バランスの失敗と解釈しない。

### 序列と極端値

- +0/+15の無刻印期待、および平均/最大刻印条件は全7武器でエリヴァ→ラピス→ルミナ→アストラのTTK短縮順。+30無刻印期待のみ、Lv30通常/強敵×7武器の14群で旧エリヴァがラピスを僅かに上回る。Lv30通常ソードは旧 `R=3,146.45 / TTK=4.740秒`、ラピス `R=3,135.35 / TTK=4.769秒`（約0.60%遅い）。旧+30の対応能力成長が高Lv残差で効く。既存投資を保護する代わりに完全な単調性は満たさない。
- Lv30実通常敵でアストラ+30ソードの無属性最大は `R=5,007.07 / TTK=1.092秒`。RはLv30アンカー3,318の150.9%で130%目安を超える。ハンマーは固定の通常攻撃+120%を含み `TTK=0.680秒`。武器種固有の攻撃間隔/操作コストを置いていないため、ハンマーの実戦最短時間ではない。
- 希少属性だけを同属性攻撃へ合わせても、敵弱点なしでは無属性特化のNONE刻印/防具構成より通常遅い。上級刻印の候補・4枠による極値リスクは本体の属性希少値だけでは抑えられない。

## Shop取得負担

`acquisition.py` は実Shop 2 masterを正本とし、57件を主接続表 `SHOP_CONNECTIONS.csv` と価格・素材・ページ・slotまで照合した。旧19件も同じ実Shopから取得する。表は「ソード1本＋ガード4防具」の購入合計で、進化1回の費用は別列。+15/+30の強化試行費、刻印・ルーン取得費は未計上。

| 系列 | Shop Gold | 採集破壊回数 | 速度4純採集秒 | Mob期待討伐 | 進化Gold | 進化素材の採集/討伐 | 1回の購入最大BAG枠 |
|:--|--:|--:|--:|--:|--:|:--|--:|
| eriva | 1,040,000 | 0 | 0.0 | 557.14 | 7,500 | 0 / 285.71 | 3 |
| lapis | 1,352,000 | 177 | 414.0 | 0.00 | 12,500 | 87 / 0.00 | 5 |
| lumina | 1,560,000 | 134 | 381.6 | 98.17 | 47,000 | 84 / 100.64 | 4 |
| astra | 2,080,000 | 456 | 1,094.4 | 301.43 | 72,000 | 88 / 121.43 | 10 |

- 採集素材はsource profileのHPを読み、速度4なら1破壊あたり `0.4×(ceil(HP/4)−1)` 秒。初撃は即時、以後8tick間隔。移動・探索・再出現・道具修理は別。速度4ツールの初期取得費も別。Lv2鉱石を速度2の初期道具で代用できない。
- Mob素材はluck0・定義drop率と個数から `必要数/(確率×1回個数)`。すべて35%ではなくエリヴァ毛皮35%/樹脂5%、新Lv22素材は40%/45%、後期素材は35%。複数Mobから樹脂5%が落ちても、同一討伐で各rateを合算しない。討伐移動・待機・配置未確認は別。
- Astraの石ころ `10a00010` は主要鉱石1破壊で最低2個を同時に得る。全19商品は鉱石破壊だけで要求石ころが保証されるため、石ころ分の採集を二重計上しない。
- 素材maxStack64と購入前の完成品空き1枠で最大は `20a00450` の14枠。BAGは `StatusDefaults.INVENTORY_SLOTS=36` を基礎値として `PlayerInventoryState` が保持し、`StatusService` が確定ステータス値を反映する。設計上はLv5ごと+1、装備等補正があり上限なし。`NormalInventoryLayout.DEFAULT_CAPACITY=32` はBAG以外の通常inventory値で、可視24枠はページ表示数。14≤36（基礎）、14≤32（非BAG値）、14≤24（表示1ページ分）の数値比較は成り立つが、購入可否の容量判定には実効BAG値だけを使う。`inventory.bag.slotCapacity` という設定keyは現行repoに見つからない。根拠は `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/InventoryService.java:9028-9041`、`.../feature/inventory/state/PlayerInventoryState.java:60`、`.../feature/status/model/StatusDefaults.java:9`、`00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md:54`。既存アイテム占有を含めて購入時に必要空き枠を確認する。GoldはBAG素材枠に含めない。
- Gold取得時間は収入経路/純増Gold毎時が未定なので換算しない。採集・討伐の副産物売却を含めるなら同じ収入を重複計上しない。

## 判断

主値期待と+15の合法更新は系列順に伸び、旧エリヴァ+30はLv30の無刻印期待でラピスとほぼ同等か僅かに上回る。これは既存投資保護との明示的な交換条件として許容可否を決めるべき境界である。通常育成の本体をさらに弱くするとLv20以降の入手費（Gold・採集・討伐）に対する更新感を落とす。一方、最適上級刻印ではLv30でR130%目安超過と1秒前後の極短TTKが残る。本体数値だけで両方を解くのは難しく、共通刻印の倍率/付与上限・入手供給の設計と職別実戦測定で受入判断する。今回の数式は実戦100回の代わりにはならない。

## 再実行

PowerShellでPython 3.14とPyYAMLを使用する。`heavy_balance.py`、`acquisition.py`、`summarize.py`、`mob_selection.json` を同じディレクトリに置き、明示した `--out` にだけ出力する。

```powershell
$env:PYTHONIOENCODING='utf-8'
$python = 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe'
$scripts = 'E:\AstralRecord-Worktrees\field-equipment-final-integration\00_docs\99_資料\field-equipment-final-integration\heavy-balance' # このREPORTを生成した正本scriptの配置先
$repo = 'E:\AstralRecord-Worktrees\field-equipment-final-integration'
$out = 'C:\Users\kaede\AppData\Local\Temp\field-equipment-heavy-balance-output'
$sourceRevision = '7928471b0996e4e56940b90692310841c8f625bc' # 実行時のHEADに更新
& $python -B -X utf8 (Join-Path $scripts 'heavy_balance.py') --repo $repo --out $out
& $python -B -X utf8 (Join-Path $scripts 'acquisition.py') --repo $repo --out $out
& $python -B -X utf8 (Join-Path $scripts 'summarize.py') --repo $repo --out $out --source-revision $sourceRevision
# b046から対象入力の差分なしを別途確認した実行だけ、最後の行に --inputs-unchanged-since-baseline を付ける。
```
