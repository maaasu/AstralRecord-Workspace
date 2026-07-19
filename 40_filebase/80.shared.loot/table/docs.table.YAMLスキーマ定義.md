# LootTable YAML スキーマ定義

LootTableのスキーマ定義。

## スキーマ定義

| キー                | 型            | 必須 | デフォルト | 説明                              |
|:------------------|:-------------|:--:|-------|:--------------------------------|
| `schemaVersion`   | Integer      | ○  | -     | スキーマのバージョン（2026-01-18時点は `1`）   |
| `id`              | String       | ○  | -     | lootのテンプレートID。（例: `coin_small`） |
| `type`            | String       | ○  | -     | Loot種別（LOOT_TABLE(lt)）          |
| `rolls`           | String       | ×  | 1     | 抽選回数。固定値または範囲（例: `1` `1~0`）。範囲は実行ごとに閉区間で抽選し、降順指定は min/max を正規化する |
| `pools`           | List<String> | ○  | -     | poolの設定  ※参照値                   |

### 参照（ref）
`pools[]` から LootPool を参照する場合は `loot_pool:` prefix を使用します（alias: `lp`）。

## YAML 例

```yaml
schemaVersion: 1
id: loot_table_example
type: LOOT_TABLE
rolls: 1
pools:
  - ref: loot_pool:loot_table_example_pool1
  - ref: loot_pool:loot_table_example_pool2
```
