# LootPool YAML スキーマ定義

LootPoolのスキーマ定義。

## スキーマ定義

| キー                  | 型       | 必須 | デフォルト        | 説明                                              |
|:--------------------|:--------|:--:|--------------|:------------------------------------------------|
| `schemaVersion`     | Integer | ○  | -            | スキーマのバージョン（2026-01-18時点は `1`）                   |
| `id`                | String  | ○  | -            | lootのテンプレートID。（例: `coin_small`）                 |
| `type`              | String  | ○  | -            | Loot種別（LOOT_POOL(lp)）                           |
| `pick`              | Integer | ×  | contentsの要素数 | 固定値、範囲（例: `1` `1~4`）※範囲の最大値はcontentsの要素数を超えないこと |
| `contents[]`        | List    | ○  | -            | コンテンツの設定リスト（後述）                                 |
| `contents[].itemId` | String  | ○  | -            | ドロップするアイテムのID （例: item:iron_ingot ） ※参照値        |
| `contents[].rate`   | Double  | ○  | -            | 固定値（0 ~ 100） #小数あり                              |
| `contents[].amount` | String  | ×  | 1            | 固定値、範囲（例: `1` `1~4`）                            |


## YAML 例

```yaml
schemaVersion: 1
id: iron_ingot_pool
type: LOOT_POOL
pick: 1
contents:
  - itemId: 
      ref: item:iron_ingot
    rate: 100
    amount: 
      min: 1
      max: 5
  - itemId:
      ref: item:gold_ingot
    rate: 50
    amount: 1~3
```