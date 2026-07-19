# LootPool YAML スキーマ定義

LootPoolのスキーマ定義。

## スキーマ定義

| キー                  | 型       | 必須 | デフォルト        | 説明                                              |
|:--------------------|:--------|:--:|--------------|:------------------------------------------------|
| `schemaVersion`     | Integer | ○  | -            | スキーマのバージョン（2026-01-18時点は `1`）                   |
| `id`                | String  | ○  | -            | lootのテンプレートID。（例: `coin_small`）                 |
| `type`              | String  | ○  | -            | Loot種別（LOOT_POOL(lp)）                           |
| `pick`              | String  | ×  | contentsの要素数 | 1 roll 内の最大採用件数。固定値、範囲（例: `1` `1~0`）。範囲は実行ごとに閉区間で抽選し、降順指定は min/max を正規化する。空 contents の既定値は `0` |
| `contents[]`        | List    | ○  | -            | コンテンツの設定リスト（後述）                                 |
| `contents[].itemId` | String  | ○  | -            | ドロップするアイテムのID （例: item:iron_ingot ） ※参照値        |
| `contents[].rate`   | Double  | ○  | -            | content ごとの独立ドロップ率（0〜100%、小数可）。`0` は必ず空振り、`100` は必ず成功 |
| `contents[].amount` | String  | ×  | 1            | 固定値、範囲（例: `1` `1~0`）                            |


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
    amount: 1~0
  - itemId:
      ref: item:magic_crystal
    rate: 10
    amount: 1~3
```

## 抽選規則

1. 1 roll ごとに各 `contents[]` を `rate`% で独立判定する。全件失敗する空振りを許容する。
2. `pick` の固定値または範囲を実行時に確定し、その値を最大採用件数とする。
3. 成功候補が最大採用件数を超えた場合だけ、成功候補から無作為に `pick` 件へ絞る。
4. `pick` 未指定または null は `contents` の要素数とし、成功候補を件数で絞らない。
