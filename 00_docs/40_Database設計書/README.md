# Database 設計書

このディレクトリは AstralRecord の SQL Server 設計書を扱う。  
詳細なテーブル定義は `table-definitions/` に置き、ここでは DB ごとの目的、境界、運用フローを整理する。

## 対象 DB

| DB | 役割 | 詳細 |
|:--|:--|:--|
| `AstralRecord` | プレイヤー、アカウント、インベントリ、装備個体などの動的データ | [[テーブル定義一覧]] |
| `MasterDataDB` | `40_filebase` 由来の静的マスタデータを API 配信用に保持する DB | [[MasterDataDB設計]] / [[テーブル定義一覧]] |
| `HistoryDB` | ログイン/ログアウトなどの履歴データを保持する DB | [[テーブル定義一覧]] |

## 基本方針

- 動的データ、静的マスタデータ、履歴データは DB を分離する。
- filebase YAML は編集・レビューの正本、MasterDataDB は API が常時参照する配信用データストアとする。
- 履歴データは `HistoryDB` に保存し、`AstralRecord` DB へ直接テーブルを追加しない。
- API 起動時または Seeder API 実行時に filebase を読み、MasterDataDB へ upsert する。
- Plugin は DB へ直接接続せず、AstralRecord API 経由でデータを操作する。

## ドキュメント一覧

1. [[MasterDataDB設計]]
2. [[MasterDataDBテーブル一覧]]
3. [[MasterDataDB同期フロー]]
4. [[テーブル定義一覧]]

## 更新ルール

- DB / テーブルを追加・変更した場合は `table-definitions/<DB>/` と `<DB>/init.sql` を更新する。
- API の DTO / Entity / Repository を変更した場合は `00_docs/20_API設計書/` の対象 feature を更新する。
- filebase YAML スキーマを変更した場合は `40_filebase/` 配下の `docs.*.YAMLスキーマ定義.md` と Seeder の変換仕様を更新する。
