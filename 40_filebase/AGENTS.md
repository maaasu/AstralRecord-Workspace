# AstralRecord Filebase Guide

対象: `40_filebase/`

## 役割

- YAML などの file 系マスタデータと、そのスキーマ資料を管理する。
- SQL Server の DB / テーブル定義は管理しない。DB 定義は `00_docs/40_Database設計書/` を対象にする。

## 作業方針

- マスタ作成前に `00_docs/50_Filebase設計書/README.md`、対象カテゴリの `feature/<number>-<category>.md`、`作成時チェックリスト.md` を読む。
- file マスタを変更する場合は、Plugin と API の読み込み処理、Resource Pack の参照、関連ドキュメントへの影響を確認する。
- `config.yml` のパス解決ルールと各 YAML スキーマ定義を優先する。
- マスタデータの ID、カテゴリ、参照先が実装やリソースパックと矛盾しないか確認する。
- 本番向け YAML の先頭コメントへ、`00_docs/50_Filebase設計書/README.md` で定義された `motif` と `progression` を記載する。
