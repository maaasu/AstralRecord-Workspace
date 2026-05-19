# Recipe YAML スキーマ定義

クラフト・強化・調合などのレシピを定義するリソース。

素材にはアイテムIDの直接指定のほか、equipment の強化素材と同様に参照値（`ref:`）による指定も可能です。

---

## スキーマ定義

| キー                     | 型            | 必須 | デフォルト     | 説明                                                              |
|:-----------------------|:-------------|:--:|:----------|:----------------------------------------------------------------|
| `schemaVersion`        | Integer      | ○  | -         | スキーマのバージョン（2026-04-02時点は `1`）                                   |
| `id`                   | String       | ○  | -         | レシピのテンプレートID（例: `iron_sword_recipe`）                            |
| `type`                 | String       | ○  | -         | リソース種別（`RECIPE`）                                                |
| `category`             | String       | ○  | -         | レシピ種別（後述 `RecipeCategory`）                                      |
| `name`                 | String       | ×  | Null      | レシピの表示名（UI表示用。色コード利用可能）                                         |
| `lore`                 | List<String> | ×  | emptyList | レシピの説明文（§ または & の色コード利用可能）                                      |
| `tags`                 | List<String> | ×  | emptyList | 検索・分類用タグ（例: `blacksmith`, `alchemy`）                            |
| `result.itemId`        | String       | △  | -         | 生成されるアイテムのID（参照値。例: `ref: item:iron_sword`）。`ENHANCE` では不使用（後述） |
| `result.amount`        | Integer      | ×  | 1         | 生成数。`ENHANCE` では不使用                                             |
| `ingredients[]`        | List         | ○  | -         | 素材リスト                                                           |
| `ingredients[].itemId` | String       | ○  | -         | 素材アイテムのID（参照値。例: `ref: item:iron_ingot`）                        |
| `ingredients[].amount` | Integer      | ○  | -         | 必要数                                                             |
| `requiredLevel`        | Integer      | ×  | 0         | レシピ使用に必要なプレイヤーレベル（`0` で制限なし）                                    |
| `requiredClasses[]`    | List<String> | ×  | -         | レシピ使用可能クラスIDのリスト（任意）。未指定時は全クラス使用可                               |
| `requiredCurrency`     | Integer      | ×  | 0         | レシピ実行に必要な通貨量（ゴールドなど）                                            |
| `stationId`            | String       | ×  | Null      | 使用に必要な作業台・施設のID（例: `blacksmith_station`）。Null の場合はどこでも実行可       |
| `successRate`          | Float        | ×  | 100.0     | 成功確率（`0.00` 〜 `100.00`）。`100.0` で必ず成功                           |
| `failAction`           | String       | ×  | NONE      | 失敗時の挙動（後述 `FailAction`）                                         |
| `cooldownTicks`        | Long         | ×  | 0         | レシピ実行後のクールダウン（tick 単位。20 tick = 1 秒）                            |
| `onSuccess[].sound`    | String       | ×  | Null      | 成功時に鳴るサウンド                                                      |
| `onSuccess[].particle` | String       | ×  | Null      | 成功時のパーティクル                                                      |
| `onFail[].sound`       | String       | ×  | Null      | 失敗時に鳴るサウンド                                                      |
| `onFail[].particle`    | String       | ×  | Null      | 失敗時のパーティクル                                                      |

### RecipeCategory

| 値           | 説明                                           | `result` 必須 |
|:------------|:---------------------------------------------|:------------|
| `CRAFT`     | 通常クラフト（素材を組み合わせてアイテムを生成）                     | ○           |
| `ENHANCE`   | 強化レシピ（既存アイテムを素材で強化。equipment の enhance と連携可） | ×（不使用）      |
| `ALCHEMY`   | 調合レシピ（ポーション・消耗品などの生成）                        | ○           |
| `DISMANTLE` | 分解レシピ（アイテムを素材に分解）                            | ○           |

> **`ENHANCE` における `result` について**  
> `category: ENHANCE` の場合、強化対象はプレイヤーが持ち込んだアイテム自体です。プラグイン側は「持ち込まれたアイテムを強化して返す」処理を行うため、`result.itemId` / `result.amount` は参照されません。`ENHANCE` レシピでは `result` ブロックを省略してください。

### FailAction

| 値           | 説明                          |
|:------------|:----------------------------|
| `NONE`      | 何も起きない（素材と通貨のみ消費）           |
| `DOWNGRADE` | 結果アイテムの品質・強化レベルが1段階下がる      |
| `DESTROY`   | 素材が消滅する（結果アイテムは生成されない）      |

### 参照（ref）
他DBからレシピを参照する場合は `recipe:` prefix を使用します（aliases: `rc`）。

---

## YAML 例

### 例: 通常クラフト

```yaml
schemaVersion: 1
id: iron_sword_recipe
type: RECIPE
category: CRAFT
name: "&f鉄の剣のレシピ"
lore:
  - "&7鉄インゴットを使って剣を作る。"
tags:
  - blacksmith
  - weapon
result:
  itemId:
    ref: item:iron_sword
  amount: 1
ingredients:
  - itemId:
      ref: item:iron_ingot
    amount: 3
  - itemId:
      ref: item:bone_fragment
    amount: 1
requiredLevel: 5
requiredCurrency: 50
stationId: blacksmith_station
successRate: 100.0
failAction: NONE
onSuccess:
  sound: block.anvil.use
  particle: crit
```

### 例: 強化レシピ（equipment の enhance と連携）

```yaml
schemaVersion: 1
id: iron_sword_enhance_lv3_recipe
type: RECIPE
category: ENHANCE
name: "&f鉄の剣 強化Lv3 レシピ"
lore:
  - "&7鉄の剣をさらに強化する。失敗するとレベルが下がる。"
tags:
  - blacksmith
  - enhance
# result は ENHANCE では不使用のため省略
ingredients:
  - itemId:
      ref: item:iron_ingot
    amount: 6
  - itemId:
      ref: item:magic_crystal
    amount: 1
requiredLevel: 10
requiredCurrency: 500
stationId: blacksmith_station
successRate: 75.0
failAction: DOWNGRADE
onSuccess:
  sound: block.anvil.use
  particle: crit
onFail:
  sound: block.anvil.break
  particle: smoke
```

### 例: 調合レシピ

```yaml
schemaVersion: 1
id: healing_potion_recipe
type: RECIPE
category: ALCHEMY
name: "&a回復ポーションのレシピ"
lore:
  - "&7素材を調合して回復ポーションを作る。"
tags:
  - alchemy
  - consumable
result:
  itemId:
    ref: item:healing_potion_small
  amount: 2
ingredients:
  - itemId:
      ref: item:magic_crystal
    amount: 1
  - itemId:
      ref: item:bone_fragment
    amount: 2
requiredLevel: 3
requiredCurrency: 20
stationId: alchemy_station
successRate: 100.0
failAction: NONE
cooldownTicks: 100
onSuccess:
  sound: block.brewing_stand.brew
  particle: spell_witch
```
