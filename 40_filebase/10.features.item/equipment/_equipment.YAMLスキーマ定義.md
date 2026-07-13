# EQUIPMENT (装備) YAML スキーマ定義

武器、防具、アクセサリー、ツールなどの「装備して性能を変化させるアイテム」を表現します。
通常のアイテム共通項目（`schemaVersion` / `id` / `category` / `name` など）は `item.YAMLスキーマ定義.md` を参照し、本書では Equipment 固有項目のみ定義します。

> **StatusType について**: `status` フィールドに使用できるステータス名の一覧は [`file/00.meta/StatusType.md`](../../00.meta/StatusType.md) を参照してください。

## スキーマ定義
| キー                                                        | 型             | 必須 | デフォルト | 説明                                                                                                                                             |
|:----------------------------------------------------------|:--------------|:--:|:------|:-----------------------------------------------------------------------------------------------------------------------------------------------|
| `equipment[].slot`                                        | String        | ○  | -     | 装備スロット種別（後述）。                                                                                                                                  |
| `equipment[].handType`                                    | String        | ×  | ONE   | 手持ち装備の手数。`slot=WEAPON` の場合に使用（`ONE` / `TWO`）。                                                                                                  |
| `equipment[].tag`                                         | String        | ×  | -     | equipment固有の補助情報。`slot=ACCESSORY` では必須とし、`AMULET` / `TALISMAN` / `CHARM` / `CORE` / `RELIC` のいずれかを指定する。Toolでは「ツルハシ」「斧」などの分類にも使用する |
| `equipment[].requiredLevel`                               | Integer       | ×  | 0     | 装備に必要なプレイヤーレベル。`0` で制限なし。                                                                                                                      |
| `equipment[].requiredClasses[]`                           | List<String>  | ×  | -     | 装備可能クラスIDのリスト（任意）。未指定時は全クラス装備可。                                                                                                                |
| `equipment[].setId`                                       | String        | ×  | -     | このアイテムが属するセット効果ID（架空例: `example_guardian_set`）。セット効果定義は `set_effect/_set_effect.YAMLスキーマ定義.md` を参照。                                                  |
| `equipment[].stats[]`                                     | List          | ×  | -     | 装備中に適用される基礎ステータス補正のリスト（後述）。                                                                                                                    |
| `equipment[].stats[].status`                              | String        | ×  | -     | 対象ステータス（`StatusType`）。例: `ATTACK` / `DEFENSE` / `MOVEMENT_SPEED`。                                                                              |
| `equipment[].stats[].type`                                | String        | ×  | -     | 補正方式（`FLAT` / `SCALAR`）。`FLAT` は加算、`SCALAR` は乗算係数。                                                                                             |
| `equipment[].stats[].value`                               | String/Object | ×  | -     | ステータスの下限値/上限値。固定値（例: `12`）、固定範囲（例: `10~20`）、または `min/max` オブジェクト（後述）で指定。`SCALAR` の場合は `0.10 = +10%`。                                           |
| `equipment[].stats[].value.min`                           | String        | ×  | -     | ステータス下限値。固定値または範囲（例: `10` / `10~20`）。                                                                                                          |
| `equipment[].stats[].value.max`                           | String        | ×  | -     | ステータス上限値。固定値または範囲（例: `21` / `21~50`）。                                                                                                          |
| `equipment[].durability.max`                              | Integer       | ×  | -     | 最大耐久値（任意）。指定しない場合は耐久管理なし。                                                                                                                      |
| `equipment[].durability.consume`                          | Integer       | ×  | 1     | 1回の使用/攻撃で減る耐久値。`durability.max` を指定した場合に使用。                                                                                                    |
| `equipment[].onUse[].leftClickCooldownTicks`              | Integer       | ×  | -     | 左クリック使用時クールタイム。                                                                                                                                |
| `equipment[].onUse[].leftClickSkillId`                    | String        | ×  | -     | 左クリック時に発動するスキル。                                                                                                                                |
| `equipment[].onUse[].rightClickCooldownTicks`             | Integer       | ×  | -     | 右クリック使用時クールタイム。                                                                                                                                |
| `equipment[].onUse[].rightClickSkillId`                   | String        | ×  | -     | 右クリック時に発動するスキル。                                                                                                                                |
| `equipment[].skills[]`                                    | List<String>  | ×  | -     | 装備にスキルを適用する。id指定。※参照値                                                                                                                          |
| `equipment[].enchant`                                     | Object        | ×  | -     | エンチャントシステム設定。指定しない場合はエンチャント不可。                                                                                                                 |
| `equipment[].enchant.maxSlots`                            | Integer       | ×  | 1     | 装備に付与できるエンチャントの最大スロット数。固定値。                                                                                                                    |
| `equipment[].enchant.pools[]`                             | List          | ×  | -     | エンチャントプールのリスト。素材ごとに異なるステータス候補を定義できる。                                                                                                           |
| `equipment[].enchant.pools[].id`                          | String        | ×  | -     | 任意のプール識別子。未指定時でも API の `poolIndex` により上から `0,1,2...` が自動採番される。                                                                                 |
| `equipment[].enchant.pools[].recipeId`                    | String        | ×  | -     | 強化に使用するレシピID（参照値。例: `ref: recipe:iron_sword_enhant`）。`requiredMaterials` / `requiredCurrency` / `successRate` / `failAction` はレシピ側で定義するため省略可。  |
| `equipment[].enchant.pools[].requiredMaterial.itemId`     | String        | ×  | -     | このプールを発動させるために必要な素材アイテムID。                                                                                                                     |
| `equipment[].enchant.pools[].requiredMaterial.amount`     | Integer       | ×  | 1     | 必要な素材の個数。                                                                                                                                      |
| `equipment[].enchant.pools[].requiredCurrency`            | Integer       | ×  | 0     | エンチャント実行に必要な通貨量（ゴールドなど）。                                                                                                                       |
| `equipment[].enchant.pools[].entries[]`                   | List          | ×  | -     | このプールが持つステータス候補のリスト。実行時にリストからランダムに1つ選ばれて付与される。                                                                                                 |
| `equipment[].enchant.pools[].entries[].status`            | String        | ×  | -     | 付与されるステータス（`StatusType`）。                                                                                                                      |
| `equipment[].enchant.pools[].entries[].type`              | String        | ×  | -     | 補正方式（`FLAT` / `SCALAR`）。                                                                                                                       |
| `equipment[].enchant.pools[].entries[].value`             | String        | ×  | -     | 付与される値。固定値または範囲（例: `5` / `3~8`）。                                                                                                               |
| `equipment[].enchant.pools[].entries[].weight`            | Integer       | ×  | 1     | 抽選重み。値が大きいほど選ばれやすい。                                                                                                                            |
| `equipment[].enhance.maxLevel`                            | Integer       | ×  | -     | 強化の最大レベル。指定しない場合は強化不可。固定値。                                                                                                                     |
| `equipment[].enhance.levels[].level`                      | Integer       | ×  | 0     | 強化レベル（1 〜 `maxLevel`）。                                                                                                                         |
| `equipment[].enhance.levels[].statIncrease[]`             | List          | ×  | -     | この強化レベルで上昇するステータス幅のリスト（前レベルからの差分）。プラグインはこの値を累積してステータス補正を計算する。                                                                                  |
| `equipment[].enhance.levels[].statIncrease[].status`      | String        | ×  | -     | 対象ステータス（`StatusType`）。                                                                                                                         |
| `equipment[].enhance.levels[].statIncrease[].type`        | String        | ×  | -     | 補正方式（`FLAT` / `SCALAR`）。                                                                                                                       |
| `equipment[].enhance.levels[].statIncrease[].value`       | String        | ×  | -     | 上昇幅。固定値または範囲（例: `3` / `2~5`）。                                                                                                                  |
| `equipment[].enhance.levels[].durabilityBonus`            | Integer       | ×  | -     | この強化レベルで加算される最大耐久値。                                                                                                                            |
| `equipment[].enhance.levels[].recipeId`                   | String        | ×  | -     | 強化に使用するレシピID（参照値。例: `ref: recipe:iron_sword_enhance`）。`requiredMaterials` / `requiredCurrency` / `successRate` / `failAction` はレシピ側で定義するため省略可。 |
| `equipment[].enhance.levels[].requiredMaterials[].itemId` | String        | ×  | -     | 強化に必要な素材アイテムID。`recipeId` を指定した場合は省略可。                                                                                                         |
| `equipment[].enhance.levels[].requiredMaterials[].amount` | Integer       | ×  | -     | 必要な素材の個数。`recipeId` を指定した場合は省略可。                                                                                                               |
| `equipment[].enhance.levels[].requiredCurrency`           | Integer       | ×  | -     | 強化に必要な通貨量（ゴールドなど）。`recipeId` を指定した場合は省略可。                                                                                                      |
| `equipment[].enhance.levels[].successRate`                | Float         | ×  | 1.0   | 強化成功率（`0.0` 〜 `1.0`）。`1.0` で必ず成功。`recipeId` を指定した場合は省略可。                                                                                       |
| `equipment[].enhance.levels[].failAction`                 | String        | ×  | NONE  | 強化失敗時の挙動（`NONE` / `DOWNGRADE` / `DESTROY`）。`recipeId` を指定した場合は省略可。                                                                             |
| `equipment[].rune`                                        | Object        | ×  | -     | ルーンスロットシステム設定。指定しない場合はルーン装着不可。                                                                                                                 |
| `equipment[].rune.maxSlots`                               | Integer       | ×  | 0     | 装備に装着できるルーンの最大スロット数。`0` で装着不可。固定値。                                                                                                             |
| `equipment[].rune.maxSlots.random[].min`                  | Integer       | ×  | -     | 固定値（例: `1`）。装備作成時にスロット数をランダムに決定する最小値。「`rune.maxSlots.random: 1~3`」も可能。指定時maxSlotsの指定は必要なし。                                                     |
| `equipment[].rune.maxSlots.random[].max`                  | Integer       | ×  | -     | 固定値（例: `3`）。装備作成時にスロット数をランダムに決定する最大値。「`rune.maxSlots.random: 1~3`」も可能。指定時maxSlotsの指定は必要なし。                                                     |
| `equipment[].rune.allowedRuneIds[]`                       | List<String>  | ×  | -     | 装着を許可するルーンIDのリスト。未指定時はスロット種別が合致するルーンをすべて許可。                                                                                                    |
| `equipment[].transcendence[]`                             | List          | ×  | -     | 状態変化（進化・覚醒・超越など）の定義リスト。指定した素材を消費することでアイテムの各種パラメータを上書きする。                                                                                       |
| `equipment[].transcendence[].name`                        | String        | ×  | -     | 状態変化の名称（例: `進化` / `覚醒` / `超越`）。ゲーム内UIに表示される。                                                                                                   |
| `equipment[].transcendence[].rank`                        | Integer       | ×  | -     | 状態変化の強さ指標。数値が大きいほど上位の状態変化。同一装備内で一意である必要がある。プラグインはこの値を使い、現在の状態変化より `rank` が低い状態変化への遷移を禁止する。                                                     |
| `equipment[].transcendence[].recipeId`                    | String        | ×  | -     | 状態変化に使用するレシピID（参照値。例: `ref: recipe:iron_sword_evolution`）。`requiredMaterials` / `requiredCurrency` はレシピ側で定義するため省略可。                            |
| `equipment[].transcendence[].requiredMaterials[].itemId`  | String        | ×  | -     | 状態変化に必要な素材アイテムID。`recipeId` を指定した場合は省略可。                                                                                                       |
| `equipment[].transcendence[].requiredMaterials[].amount`  | Integer       | ×  | -     | 必要な素材の個数。`recipeId` を指定した場合は省略可。                                                                                                               |
| `equipment[].transcendence[].requiredCurrency`            | Integer       | ×  | 0     | 状態変化に必要な通貨量（ゴールドなど）。`recipeId` を指定した場合は省略可。                                                                                                    |
| `equipment[].transcendence[].overrides.name`              | String        | ×  | -     | 状態変化後のアイテム名称。未指定時は変更なし。                                                                                                                        |
| `equipment[].transcendence[].overrides.enhance.maxLevel`  | Integer       | ×  | -     | 状態変化後に上書きする `enhance.maxLevel`。未指定時は変更なし。                                                                                                      |
| `equipment[].transcendence[].overrides.enchant.maxSlots`  | Integer       | ×  | -     | 状態変化後に上書きする `enchant.maxSlots`。未指定時は変更なし。                                                                                                      |

### equipment[].slot
以下のいずれかの値を指定します。

- `WEAPON`
- `SUBWEAPON`
- `HEAD`
- `CHEST`
- `LEGS`
- `FEET`
- `ACCESSORY`
- `TOOL`

### equipment[].handType
`slot=WEAPON` のときに指定します。

### equipment[].tag（アクセサリ）

`equipment[].slot=ACCESSORY` のときは、配置可能な種類別アクセサリ枠を次の値で指定します。同じ tag の複数枠はゲーム側が空き枠を選択します。

| 値 | 装備可能数 | GUI 空枠素材 |
|:--|--:|:--|
| `AMULET` | 1 | チェスト付きトロッコ |
| `TALISMAN` | 2 | かまど付きトロッコ |
| `CHARM` | 3 | トロッコ |
| `CORE` | 1 | ホッパー付きトロッコ |
| `RELIC` | 2 | TNT付きトロッコ |

- `ONE` : 片手装備
- `TWO` : 両手装備

### equipment[].stats[].type
- `FLAT` : 定数加算
- `SCALAR`: 乗算（ベース値に対して）

### equipment[].stats[].value

`value` は、プラグイン側で扱う 1 つのステータスの下限値 / 上限値を表します。API 側で範囲内の値を 1 つの固定値に解決するものではありません。

指定方法は以下の 3 通りです。

1. 固定値
`value: 12`

2. 固定範囲
`value: 10~20`

3. 下限値 / 上限値を個別に指定
`value: { min: 10~20, max: 21~50 }`

3 の形式では、装備個体ごとに `min` と `max` をそれぞれの範囲から決定できます。

### equipment[].enhance.levels[].failAction
強化失敗時の挙動を指定します。

- `NONE` : 何も起きない（素材・通貨のみ消費）
- `DOWNGRADE` : 強化レベルが 1 下がる
- `DESTROY` : 装備が消滅する

### statIncrease について

`statIncrease` は各強化レベルで前レベルから上昇するステータス幅（差分）を指定します。プラグインはこの値を Lv1 から対象レベルまで累積することで、そのレベルの装備が持つ最終的なステータス補正を算出します。

#### 具体例（ATTACK の場合）

|  強化Lv  | `statIncrease.value`（前Lvからの上昇幅） | 累積補正値（プラグインが計算） |
|:------:|:-------------------------------:|:---------------:|
| 0（未強化） |                —                |        0        |
|   1    |            3（0 → 3）             |        3        |
|   2    |            2（3 → 5）             |        5        |
|   3    |            3（5 → 8）             |        8        |

- プラグインは `statIncrease` を累積してステータス補正を計算するため、絶対値を別途指定する必要はありません。
- `statIncrease` を省略した場合、そのレベルでの上昇幅はゼロとして扱われます。

### equipment[].enchant について

エンチャントシステムは、指定した素材を消費することで装備にランダムなステータスを付与する仕組みです。

- `pools` に素材とステータス候補リスト（`entries`）を定義します。
- プレイヤーが素材を使用してエンチャントを実行すると、対応するプールの `entries` からランダムに1つだけ選ばれてステータスが付与されます。
- `entries[].weight` で各ステータスの選ばれやすさを調整できます（重み付き抽選）。
- `maxSlots` で装備に同時に付与できるエンチャント数の上限を設定します。上限に達している場合は既存のエンチャントをランダムに 1 件選んで上書きします。
- `pools[].id` は任意の識別子です。未指定でも API は `poolIndex` を 0 始まりで自動採番します。
- 複数の `pools` を定義することで、素材の種類によって異なるステータス候補を提供できます。

### 強化素材の指定方法
強化レベルごとの素材は以下の2通りで指定できます。

1. **直接指定**（`requiredMaterials` / `requiredCurrency` / `successRate` / `failAction` を直接記述）
2. **レシピ参照**（`recipeId` に `ref: recipe:<id>` を指定し、素材・通貨・成功率・失敗挙動をレシピ側で一元管理）

両方を指定した場合は `recipeId` が優先されます。

### equipment[].transcendence について

状態変化システムは、指定した素材（またはレシピ）を消費することでアイテムの各種パラメータを上書きする仕組みです。「進化」「覚醒」「超越」など、ゲームデザインに応じた名称を自由に設定できます。

- `name` で状態変化の名称を指定します（例: `進化` / `覚醒` / `超越`）。
- 素材はレシピ参照（`recipeId`）または直接指定（`requiredMaterials` / `requiredCurrency`）のいずれかで指定します。両方を指定した場合は `recipeId` が優先されます。
- `overrides` に上書きしたいパラメータのみ指定します。未指定のパラメータは変更されません。
- 上書き可能なパラメータ: `name`（アイテム名称）/ `enhance.maxLevel` / `enchant.maxSlots` / `rune.maxSlots`。
- 複数の状態変化を定義した場合、リストの順番に段階的に適用できます（例: 進化→覚醒→超越）。
- `rank` で各状態変化の強さ指標を指定します。プラグインは現在の `rank` より低い `rank` の状態変化への遷移を禁止し、整合性を保ちます（例: 覚醒 rank:2 → 進化 rank:1 への逆行を防止）。

### equipment[].rune について

ルーンスロットシステムは、ルーンアイテムを装備のスロットに嵌め込むことでステータス補正やスキルを付与する仕組みです。

- `maxSlots` で装備に同時に装着できるルーン数の上限を設定します。固定値のほか、`random` を使用して装備作成時にスロット数をランダムに決定することもできます（例: `random: 1~3`）。
- ルーン側の `requiredEnhanceLevel` が装備の現在の強化レベル以下でなければ装着できません。
- ルーン側の `targetSlots` に装備の `slot` 種別（または `ANY`）が含まれている場合のみ装着可能です。
- `allowedRuneIds` を指定した場合、リストに含まれるルーンIDのみ装着を許可します。

### 参照（ref）
Equipment の追加効果で Buff を参照する場合は `buff:` prefix を使用します（aliases: `bf`）。

- 例: `ref: buff:haste_small`

強化レシピを参照する場合は `recipe:` prefix を使用します（aliases: `rc`）。

- 例: `ref: recipe:iron_sword_enhance_lv1`

ルーンを参照する場合は `rune:` prefix を使用します（aliases: `rn`）。

- 例: `ref: rune:rune_attack_small`

セット効果を参照する場合は `set:` prefix を使用します（aliases: `st`）。セット効果スキーマの詳細は `set_effect/_set_effect.YAMLスキーマ定義.md` を参照。

- 架空例: `set:example_guardian_set`

## YAML 例

### 例1: 基本的な武器（tag あり）

```yaml
schemaVersion: 1
id: bronze_sword
category: EQUIPMENT
name: "&fブロンズソード"
icon: IRON_SWORD
rarity: COMMON
lore:
	- "&7初心者向けの片手剣。"

maxStack: 1
equipment:
	slot: WEAPON
	handType: ONE
	tag: SWORD
	requiredLevel: 3
	stats:
		- status: ATTACK
		  type: FLAT
		  value: 12
		- status: CRITICAL_RATE
		  type: FLAT
		  value: 0.03
    onUse:
        leftClickCooldownTicks: 80
        leftClickSkillId: 
            ref: skill:lefthand_slash
	durability:
		max: 120
		consume: 1
```

### 例2: ツール（tag あり）

```yaml
schemaVersion: 1
id: iron_pickaxe
category: EQUIPMENT
name: "&f鉄のツルハシ"
icon: IRON_PICKAXE
rarity: COMMON
lore:
  - "&7採掘に使う鉄製のツルハシ。"

maxStack: 1
equipment:
  slot: TOOL
  tag: PICKAXE
  stats:
    - status: MINING_SPEED
      type: FLAT
      value: 5
  durability:
    max: 200
    consume: 1
```

### 例3: アクセサリー（value の min / max を個別ランダム指定）

```yaml
schemaVersion: 1
id: silver_amulet
category: EQUIPMENT
name: "&fシルバーアミュレット"
icon: DIAMOND
rarity: UNCOMMON
lore:
  - "&7魔力を高める銀製のアミュレット。"

maxStack: 1
equipment:
  slot: ACCESSORY
  tag: AMULET
  stats:
    - status: MAGIC_ATTACK
      type: FLAT
      value:
        min: 8~10
        max: 12~16
```

### 例4: 強化対応武器（直接指定）

```yaml
schemaVersion: 1
id: iron_sword
category: EQUIPMENT
name: "&f鉄の剣"
icon: IRON_SWORD
rarity: UNCOMMON
lore:
  - "&7強化することでさらに力を引き出せる剣。"

maxStack: 1
equipment:
  slot: WEAPON
  handType: ONE
  tag: SWORD
  requiredLevel: 5
  stats:
    - status: ATTACK
      type: FLAT
      value: 20
  durability:
    max: 150
    consume: 1
  enhance:
    maxLevel: 5
    levels:
      - level: 1
        statIncrease:
          - status: ATTACK
            type: FLAT
            value: 3
        durabilityBonus: 10
        requiredMaterials:
          - itemId: iron_ingot
            amount: 2
        requiredCurrency: 100
        successRate: 1.0
        failAction: NONE
      - level: 2
        statIncrease:
          - status: ATTACK
            type: FLAT
            value: 2
        durabilityBonus: 10
        requiredMaterials:
          - itemId: iron_ingot
            amount: 4
        requiredCurrency: 200
        successRate: 0.9
        failAction: NONE
      - level: 3
        statIncrease:
          - status: ATTACK
            type: FLAT
            value: 3
          - status: CRITICAL_RATE
            type: FLAT
            value: 1.0
        durabilityBonus: 20
        requiredMaterials:
          - itemId: iron_ingot
            amount: 6
          - itemId: magic_crystal
            amount: 1
        requiredCurrency: 500
        successRate: 0.75
        failAction: DOWNGRADE
```

### 例5: エンチャント対応装備

以下は架空の装備マスタを新規作成する場合の記述例です。既存ファイルへの追記例ではありません。

```yaml
schemaVersion: 1
id: example_enchanted_chestplate
category: EQUIPMENT
name: "&f鉄の胸当て"
icon: IRON_CHESTPLATE
rarity: COMMON
lore:
  - "&7鍛冶師が作った標準的な胸当て。"

maxStack: 1
equipment:
  slot: CHEST
  requiredLevel: 5
  stats:
    - status: DEFENSE
      type: FLAT
      value: 15
  durability:
    max: 200
    consume: 1
  enchant:
    maxSlots: 2
    pools:
      - requiredMaterial:
          itemId: magic_crystal
          amount: 1
        requiredCurrency: 200
        entries:
          - status: DEFENSE
            type: FLAT
            value: 3~8
            weight: 10
          - status: MAX_HEALTH
            type: FLAT
            value: 10~20
            weight: 10
          - status: MAGIC_DEFENSE
            type: FLAT
            value: 3~6
            weight: 8
          - status: HP_REGEN
            type: FLAT
            value: 2~5
            weight: 5
      - requiredMaterial:
          itemId: astral_dust
          amount: 3
        requiredCurrency: 500
        entries:
          - status: DEFENSE
            type: SCALAR
            value: 0.05~0.10
            weight: 8
          - status: EVASION
            type: FLAT
            value: 2~5
            weight: 5
          - status: CRITICAL_RATE
            type: FLAT
            value: 1~3
            weight: 3
```

### 例6: ルーンスロット対応装備

```yaml
schemaVersion: 1
id: runic_iron_sword
category: EQUIPMENT
name: "&fルーン鉄の剣"
icon: IRON_SWORD
rarity: UNCOMMON
lore:
  - "&7ルーンを嵌め込むことで力を引き出せる剣。"

maxStack: 1
equipment:
  slot: WEAPON
  handType: ONE
  tag: SWORD
  requiredLevel: 5
  stats:
    - status: ATTACK
      type: FLAT
      value: 20
  durability:
    max: 150
    consume: 1
  enhance:
    maxLevel: 5
    levels:
      - level: 1
        statIncrease:
          - status: ATTACK
            type: FLAT
            value: 3
        recipeId:
          ref: recipe:runic_iron_sword_enhance_lv1
  rune:
    maxSlots: 2
```

### 例6-2: ルーンスロット数ランダム対応装備

```yaml
schemaVersion: 1
id: runic_iron_sword_random
category: EQUIPMENT
name: "&fルーン鉄の剣（ランダムスロット）"
icon: IRON_SWORD
rarity: UNCOMMON
lore:
  - "&7ルーンを嵌め込むことで力を引き出せる剣。"
  - "&7スロット数は作成時にランダムで決まる。"

maxStack: 1
equipment:
  slot: WEAPON
  handType: ONE
  tag: SWORD
  requiredLevel: 5
  stats:
    - status: ATTACK
      type: FLAT
      value: 20
  durability:
    max: 150
    consume: 1
  rune:
    maxSlots:
      random: 1~3
```

### 例7: ルーンスロット対応装備（装着ルーン制限あり）

```yaml
schemaVersion: 1
id: sacred_chestplate
category: EQUIPMENT
name: "&6聖なる胸当て"
icon: GOLDEN_CHESTPLATE
rarity: RARE
lore:
  - "&7特定のルーンのみ受け付ける神聖な胸当て。"
  - "&e強化レベル3以上のルーンを装着可能。"

maxStack: 1
equipment:
  slot: CHEST
  requiredLevel: 10
  stats:
    - status: DEFENSE
      type: FLAT
      value: 30
  enhance:
    maxLevel: 5
    levels:
      - level: 1
        statIncrease:
          - status: DEFENSE
            type: FLAT
            value: 5
        recipeId:
          ref: recipe:sacred_chestplate_enhance_lv1
  rune:
    maxSlots: 3
    allowedRuneIds:
      - ref: rune:rune_defense_medium
      - ref: rune:rune_fire_blade
```

### 例8: 状態変化対応装備（進化・覚醒）

```yaml
schemaVersion: 1
id: iron_sword_evolvable
category: EQUIPMENT
name: "&f鉄の剣"
icon: IRON_SWORD
rarity: UNCOMMON
lore:
  - "&7進化・覚醒することでさらなる力を引き出せる剣。"

maxStack: 1
equipment:
  slot: WEAPON
  handType: ONE
  tag: SWORD
  requiredLevel: 5
  stats:
    - status: ATTACK
      type: FLAT
      value: 20
  enhance:
    maxLevel: 5
  enchant:
    maxSlots: 1
  rune:
    maxSlots: 1
  transcendence:
    - name: 進化
      rank: 1
      requiredMaterials:
        - itemId: magic_crystal
          amount: 10
        - itemId: iron_ingot
          amount: 20
      requiredCurrency: 5000
      overrides:
        name: "&a進化した鉄の剣"
        enhance:
          maxLevel: 10
        enchant:
          maxSlots: 2
        rune:
          maxSlots: 2
    - name: 覚醒
      rank: 2
      recipeId:
        ref: recipe:iron_sword_awakening
      overrides:
        name: "&b覚醒した鉄の剣"
        enhance:
          maxLevel: 15
        enchant:
          maxSlots: 3
        rune:
          maxSlots: 3
```

### 例9: 強化対応武器（レシピ参照）

```yaml
schemaVersion: 1
id: iron_sword
category: EQUIPMENT
name: "&f鉄の剣"
icon: IRON_SWORD
rarity: UNCOMMON
lore:
  - "&7強化することでさらに力を引き出せる剣。"

maxStack: 1
equipment:
  slot: WEAPON
  handType: ONE
  tag: SWORD
  requiredLevel: 5
  stats:
    - status: ATTACK
      type: FLAT
      value: 20
  durability:
    max: 150
    consume: 1
  enhance:
    maxLevel: 3
    levels:
      - level: 1
        statIncrease:
          - status: ATTACK
            type: FLAT
            value: 3
        durabilityBonus: 10
        recipeId:
          ref: recipe:iron_sword_enhance_lv1
      - level: 2
        statIncrease:
          - status: ATTACK
            type: FLAT
            value: 2
        durabilityBonus: 10
        recipeId:
          ref: recipe:iron_sword_enhance_lv2
      - level: 3
        statIncrease:
          - status: ATTACK
            type: FLAT
            value: 3
          - status: CRITICAL_RATE
            type: FLAT
            value: 1.0
        durabilityBonus: 20
        recipeId:
          ref: recipe:iron_sword_enhance_lv3
```
