# LootTable YAML スキーマ定義

LootTableのスキーマ定義。

## スキーマ定義

| キー                | 型            | 必須 | デフォルト | 説明                              |
|:------------------|:-------------|:--:|-------|:--------------------------------|
| `schemaVersion`   | Integer      | ○  | -     | スキーマのバージョン（2026-01-18時点は `1`）   |
| `id`              | String       | ○  | -     | lootのテンプレートID。（例: `coin_small`） |
| `type`            | String       | ○  | -     | Loot種別（LOOT_TABLE(lt)）          |
| `rolls`           | String       | ×  | 1     | 抽選回数。固定値、範囲（例: `1` `1~4`）       |
| `pools`           | List<String> | ○  | -     | poolの設定  ※参照値                   |

### 参照（ref）
他DBからtableを参照する場合は `loot_table:` prefix を使用します（aliases: `lt`）。

## YAML 例

```yaml
schemaVersion: 1
id: loot_table_example
type: LOOT_TABLE
rolls: 1
pools:
  - ref: loot_table_example_pool1
  - ref: loot_table_example_pool2
```