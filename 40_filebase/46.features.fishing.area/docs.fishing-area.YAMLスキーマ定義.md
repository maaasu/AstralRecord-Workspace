# 釣り場マスタ YAML スキーマ定義

正本 JSON Schema: `schemas/fishing-area.v1.schema.json`。1 ファイルに釣り場 1 つ、ファイル名 `v1.<id>.yml`。
編集時は未知キーとコメントを保持する。必須キーと既知キーの値は Seeder が検証する。

`schemaVersion: 1`、`type: FISHING_AREA`、`id`、日本語 `name`、`worldId: {ref: "world:<id>"}`、`rulesId: {ref: "fishing_rules:<id>"}`、`enabled`（真偽値）、非空の `levelRanges` を必須とする。無効な定義も構造・参照を検証する。有効な釣り場は同一 world に 1 件まで。

各 `levelRanges` は `minLevel` と `maxLevel`（ともに 0 以上の整数、終端を含む、`maxLevel >= minLevel`）、非空の `fish` を持つ。最終帯の上限には `2147483647` を使える。同じ釣り場内の帯は重複不可。`fish` の各要素は `fishId: {ref: "fishing_fish:<id>"}` と `chancePercent`（0 超 100 以下）を持つ。1 帯内の魚 ID は重複不可、確率合計は 100（許容誤差 `1e-6`）。これは魚が出現する際の抽選率であり、最終釣果率は魚の食いつき確率やプレイヤー操作にも左右される。竿のレベル以下で `minLevel` が最も高い帯を選ぶ。帯の終了レベルを超えても、上位定義がない場合は下位の最高定義へ戻る。竿以下の帯が1件もない場合は釣りを開始しない。単一レベル定義は `minLevel = maxLevel` として記述できる。API 応答の参照は `ref` 値の文字列。
