# AstralRecord Filebase Guide

対象: `50_filebase/`

## 役割

- YAML などの file 系マスタデータと、そのスキーマ資料を管理する。
- SQL Server の DB / テーブル定義は管理しない。DB 定義は `40_database/` を対象にする。

## 作業方針

- file マスタを変更する場合は、Plugin と API の読み込み処理、Resource Pack の参照、関連ドキュメントへの影響を確認する。
- `config.yml` のパス解決ルールと各 YAML スキーマ定義を優先する。
- マスタデータの ID、カテゴリ、参照先が実装やリソースパックと矛盾しないか確認する。

