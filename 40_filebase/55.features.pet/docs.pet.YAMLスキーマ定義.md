# Pet YAML スキーマ定義

単一 `v1.pets.yml` にルール・成長曲線・全種類を定義します。`config.yml` のdatabase/sourceは `pet`、マスターIDは `pets` です。構造検証の正本は `schemas/pet-master.v1.schema.json` です。

## 共通フィールド

| キー | 型 | 説明 |
|:--|:--|:--|
| `schemaVersion` | Integer | `1` 固定 |
| `id` | String | `pets` 固定 |
| `rules` | Object | 共通ルール |
| `experience` | Object | 必要経験値係数、専用獲得経験値式、個体の成長補正 |
| `species[]` | List | wolf / cat / chicken 各1定義 |

## rules

| キー | 型 | 説明 |
|:--|:--|:--|
| `maxLevel` | Integer | 成長度上限 |
| `wildMaxTier` / `breedMaxTier` | Integer | 野生と配合のステータスティア上限 |
| `skillSlotLevels[]` | Integer[3] | 1〜3枠目の解放成長度。昇順、最初は1 |
| `breedMinLevel` | Integer | 双方の必要成長度。2枠目の解放成長度以上 |
| `breedCooldownHours` | Number | 配合後、両親に設定する待機時間 |
| `mutationChance` | Number | 各ステータスが親の高いティアから1段階上がる確率（0〜1） |
| `wildPotentialChance` | Number | 野生で各ステータスに潜在能力が付く確率（0〜1） |
| `potentialGrowthMultiplier` | Number | 潜在能力の成長増分倍率 |
| `potentialCountWeights[]` | Number[3] | 親の多い方の潜在数から−1、維持、＋1する重み。両親0個なら野生と同じ抽選 |
| `maleChance` | Number | オスの確率。残りはメス |
| `eggDropChance` | Number | 敵討伐1回の卵抽選確率（0〜1）。味方ペット・NPCは対象外 |
| `sizeGrowth` | Object | `initialScale`から`matureScale`へ`matureLevel`まで線形補間する外見サイズ倍率。正の値、成体倍率は初期倍率以上で2以下、到達成長度は2〜最大成長度 |
| `autonomousAttackRange` | Number | 召喚ペットの周囲で敵を探す半径。1〜32ブロック。主人から24ブロック以内で追跡する |
| `threatMultiplier` | Number | PET攻撃のHP/Shield由来脅威へ一度だけ掛ける倍率（0〜1） |
| `reviveOrbItemId` | String | `item:`参照。`PET_REVIVE` オーブ |
| `facilityId` | String | 孵化・配合・施設復活を提供するNPC ID |
| `breedMaterials[]` / `reviveMaterials[]` | List | `itemId`（item:参照）と`quantity`（正整数）。実操作で消費する素材 |

配合は同種・異性・双方生存を必須とします。親は消費しません。配合由来の譲渡制限は個体の出自で判定し、アイテムマスターの `unTradeable` だけでは判定しません。

## experience

| キー | 型 | 説明 |
|:--|:--|:--|
| `base` / `quadratic` | Integer | 基本値と成長度二乗の係数 |
| `tierInterval` / `tierBonus` | Integer | 何レベルごとの節目加算か、その加算量 |
| `wave[]` | Integer[] | 成長度1から繰り返す波形加算 |
| `milestoneInterval` | Integer | 大きな節目の間隔 |
| `milestoneBase` / `milestoneLinear` | Integer | 大きな節目の基本値と成長度係数 |
| `activityRate` | Number | 専用式の獲得経験値に成長補正と合わせて一度掛ける活動比率（0〜1） |
| `combatGain` | Object | 討伐獲得EXPの`base`・`perLevel`。非負の有限値、少なくとも片方は正 |
| `gatheringGain` | Object | 採集獲得EXPの`base`・`perLevel`。非負の有限値、少なくとも片方は正 |
| `growthRateMin` | Number | 個体の成長補正下限。0より大きく1未満の有限値 |

ペットの獲得EXPは、討伐時は`combatGain`と敵の実効レベル、採集時は`gatheringGain`と採集マスターの`level`から計算します。プレイヤーが獲得したEXP、EXP倍率、プレイヤーと対象のレベル差補正は参照しません。

獲得EXP係数は、最大の対象レベル`2147483647`でも`base + perLevel × 2147483647 < 9223372036854775807`を満たす値にします。APIと編集サイトの保存時検証で整数範囲への変換時のオーバーフローを拒否します。

```text
活動EXP = floor(base + perLevel × 対象レベル)
各基礎能力の比率 = clamp((BaseValue − 種族の全ティア最小Min)
  / (種族の全ティア最大Max − 全ティア最小Min), 0, 1)
成長補正 = 1 − (1 − growthRateMin) × 5基礎能力の比率平均
獲得EXP = floor(活動EXP × activityRate × 成長補正)
```

成長補正は生命力・攻撃力・防御力・回避力・支援力の生まれつきの基礎能力から求めます。基礎能力が高いほど獲得EXP倍率が低くなり、全項目が最低値なら1、全項目が最高値なら`growthRateMin`です。潜在能力と育成後の`currentValue`は計算に含めません。孵化済み個体の説明に成長補正を表示し、卵には表示しません。各能力の成長増分にはこの補正を掛けません。

成長度 `L` から次の成長度へ必要な経験値は次のとおりです。

```text
base + quadratic × L²
  + floor(L / tierInterval) × tierBonus
  + wave[(L − 1) % wave.length]
  + (L % milestoneInterval == 0 ? milestoneBase + L × milestoneLinear : 0)
```

累積経験値は成長度1から対象成長度の直前までを合算します。個体に保存する`details.experience`は現在の成長度内の残量で、最大成長度では0です。プレイヤーの個人差を作るhash加算はペットには付けず、基礎の曲線を共有します。放置、ログアウト、死亡、未装備中には時間経過だけで経験値を加算しません。

## species

| キー | 型 | 説明 |
|:--|:--|:--|
| `id` / `displayName` | String | 種類IDと日本語表示名 |
| `entityType` | String | `WOLF` / `CAT` / `CHICKEN` |
| `eggItemId` / `petItemId` | String | `item:`参照。卵と孵化後個体のitem ID |
| `dropWeight` | Number | 卵当選後の種類抽選重み |
| `names[]` | String[] | 50個以上の名前候補。種類内は重複不可。孵化時に1個抽選 |
| `sizeMin` / `sizeMax` | Number | 孵化時に抽選する基礎個体サイズ。`sizeGrowth`の倍率を掛けても個体差を保持し、性能の補正には使用しない |
| `hatchMaterials[]` | List | 孵化時に消費する`itemId`と`quantity` |
| `stats` | Object | ステータスごとの独立遺伝・成長・主人からの換算設定 |
| `skills[]` | List | 8個。T0〜T3がそれぞれ2個 |
| `basicAttack` | Object | 全種必須。`damageRatio`、`cooldownSeconds`、`range`を正数で指定する基本攻撃 |

外見の実倍率は`details.size × (initialScale + (matureScale − initialScale) × clamp((level − 1) / (matureLevel − 1), 0, 1))`です。初期設定はLv1で基礎サイズの半分、Lv20で基礎サイズと一致し、それ以降は固定です。犬・猫の首輪色と猫の毛柄は孵化時（配合卵は卵作成時）に抽選して個体詳細へ保存し、卵の公開応答には出しません。

## stats

`VITALITY` / `POWER` / `DEFENSE` / `EVASION` / `SUPPORT` はペットの遺伝項目IDです。主人の共有StatusType IDと同じ体系ではなく、下記の参照へ変換します。

| 遺伝項目 | 実性能への変換 |
|:--|:--|
| VITALITY | 主人のMAX_HEALTH × 継承係数 |
| POWER | 主人の攻撃参照値 × 継承係数 |
| DEFENSE | 主人のDEFENSE × 継承係数 |
| EVASION | 主人の回避率に係数を加算。ペットの回避は20%上限 |
| SUPPORT | 支援効果のvalueへ `1 + 継承係数` を乗算 |

| キー | 型 | 説明 |
|:--|:--|:--|
| `tiers[]` | List | T0〜T10の11段階。`tier,min,max,weight` |
| `valueWeightPower` | Number | ティア内の整数値 `v` の抽選重み `(max − v + 1)^valueWeightPower` |
| `growthPerLevel` | Number | 成長度が1上がると増える個体能力 |
| `inheritanceMin` / `inheritanceMax` | Number | 主人からの継承係数の下限・上限 |
| `scale` | Number | 飽和式の調整定数。正数 |

`min <= max` とし、次ティアの `min` は前ティアの `max` より大きくします。野生上限を超えたティアは野生抽選から除外します。

```text
現在の個体能力 = 基礎個体値 + growthPerLevel × (成長度 − 1) × 潜在倍率
潜在倍率 = 潜在あり ? potentialGrowthMultiplier : 1
継承係数 = inheritanceMin
  + (inheritanceMax − inheritanceMin) × 個体能力 / (scale + 個体能力)
```

通常配合はステータスごとに高いティアの親を選び、同ティアなら高い基礎個体値をそのまま継承します。変異時は1段階上の範囲から抽選します。親の育成済み現在値を基礎値としてコピーしません。

## skills

| キー | 型 | 説明 |
|:--|:--|:--|
| `id` / `name` | String | 個体へ保存するスキルIDと日本語名 |
| `tier` / `weight` | Integer / Number | T0〜T3と解放抽選の重み |
| `trigger` | String | `OWNER_HIT` / `OWNER_DODGE` / `PERIODIC` |
| `effect` | String | 下記の効果型 |
| `cooldownSeconds` | Number | クールダウン。PERIODICでは発動周期 |
| `value` | Number | 倍率・割合。5%は0.05、追撃120%は1.2 |
| `chance` | Number | 起点を満たした際の発動確率（0〜1） |
| `durationSeconds` | Number | バフ／継続回復の持続時間 |
| `hitCount` | Integer | 連撃回数 |
| `attackCount` | Integer | 発動までに必要な主人の命中回数 |

効果型は `FOLLOW_UP`、`ATTACK_SPEED`、`ATTACK_BUFF`、`HUNT_ORDER`、`DEFENSE_BREAK`、`EVADE_BUFF`、`MOON_DANCE`、`DEFENSE_BUFF`、`COUNTER`、`DAMAGE_REDUCTION`、`HEAL_HP`、`HEAL_MP`、`HEAL_ENERGY`、`ENERGY_SAVING`、`HEAL_ALL` です。

ATTACK_SPEEDはペット自身、BUFF・REDUCTION・SAVING・HEALは主人を対象とします。HUNT_ORDERは主人とペット双方の攻撃を同じvalueで強化し、MOON_DANCEは主人の防御と回避を同じvalueで強化します。HEALのvalueは各最大リソースに対する割合です。HEAL_ALLのdurationSecondsを指定すると継続回復します。追撃・反撃の攻撃が再びOWNER_HITを発火する循環は許可しません。

## 基本攻撃

`species[].basicAttack` は全種類で必須の種族行動で、スキル枠を消費しない。`damageRatio` は算出済みペット攻撃力への倍率、`cooldownSeconds` は秒、`range` は攻撃距離。主人の直接攻撃への追撃と自律攻撃で同じ個体クールダウンを使う。初期設定は犬0.35/3秒、猫0.25/4秒、ニワトリ0.10/5秒とし、射程は各3ブロック。
