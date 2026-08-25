# AstralRecord Filebase Guide

対象: `40_filebase/`

## 役割

- YAML / JSON などの file 系マスタデータと、そのスキーマ資料を管理する。
- SQL Server の DB / テーブル定義は管理しない。DB 定義は `00_docs/40_Database設計書/` を対象にする。

## 作業方針

- マスタデータを作成・修正する前に `00_docs/50_Filebase設計書/README.md`、対象カテゴリの `feature/<number>-<category>.md`、`作成時チェックリスト.md` を読む。
- ステータス、効果量、敵強度、出現密度、報酬量、装備更新などの戦闘・ゲームバランスに関わる値を作成・修正する場合は、`00_docs/60_戦闘バランス設計書/README.md` を入口に該当資料を確認する。
- file マスタを変更する場合は、Plugin と API の読み込み処理、Resource Pack の参照、関連ドキュメントへの影響を確認する。
- `config.yml` のパス解決ルールと、対象カテゴリの YAML スキーマ定義または JSON Schema を優先する。
- マスタデータの ID、カテゴリ、参照先が実装やリソースパックと矛盾しないか確認する。
- 本番向け YAML の先頭コメントへ、`00_docs/50_Filebase設計書/README.md` で定義された `motif` と `progression` を記載する。JSON はコメントを持てないため、対象 JSON Schema に設計メタデータが定義されている場合だけ、そのフィールドへ記載する。
