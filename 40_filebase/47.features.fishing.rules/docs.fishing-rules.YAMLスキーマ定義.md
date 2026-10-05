# 釣りルールマスタ YAML スキーマ定義

正本 JSON Schema: `schemas/fishing-rules.v1.schema.json`。1 ファイルにルール 1 つ、ファイル名 `v1.<id>.yml`。`schemaVersion: 1`、`type: FISHING_RULES` と全数値キーを必須とする。
編集時は未知キーとコメントを保持する。必須キーと既知キーの値は Seeder が検証する。

`searchRadius` は 0 超 32 以下。`fishCountMin`、`fishCountMax` は 1～32 で前者は後者以下。すべての `*Ticks` は 1～72000、ただし `attackTicks` は 2 以上、かつ `attackTicks <= turnTicks`、`nibbleCycleTicks <= nibbleTicks`。`reelMultiplier` は 0 超 100 以下、`slackDistance` は 0～100、`nibbleDistance` は 0～1。浮動小数値は有限値に限る。
