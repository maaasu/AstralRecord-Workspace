# 魚マスタ YAML スキーマ定義

正本 JSON Schema: `schemas/fishing-fish.v1.schema.json`。1 ファイルに魚 1 種、ファイル名 `v1.<id>.yml`。
編集時は未知キーとコメントを保持する。必須キーと既知キーの値は Seeder が検証する。

`schemaVersion: 1`、`type: FISH`、`id`、日本語 `name` と `entityType`（`COD`、`SALMON`、`TROPICAL_FISH`、`PUFFERFISH`）を必須とする。`maxHealth` は有限で 0 超 100000 以下。`swimSpeed`、`fleeSpeed`、`landFleeSpeed`、`hopSpeed` は有限で 0 超 1 以下。`pullPerTick` は 0 以上 100 以下、`biteChancePercent` は 0 超 100 以下。

釣果は `caughtItemId: {ref: "item:<id>"}` と `caughtAmount`（1～64）。参照アイテムは存在し、equipment / pet / pet_egg 以外のスタック可能なカテゴリで、`caughtAmount <= maxStack` を満たす。追加報酬を使用する場合のみ `lootTableId: {ref: "loot_table:<id>"}` を指定する。参照は Seeder が検証する。API 応答では参照の `ref` 値を文字列で返す。
