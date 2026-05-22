# DB 設計書

このディレクトリは AstralRecord の SQL Server 設計書を扱う。  
詳細なテーブル定義の正本は `40_database/` に置き、ここでは DB ごとの目的、境界、運用フローを整理する。

## 対象 DB

| DB | 役割 | 詳細 |
|:--|:--|:--|
| `AstralRecord` | プレイヤー、アカウント、インベントリ、装備/ルーン個体などの動的データ | [[テーブル定義一覧]] |
| `MasterDataDB` | filebase 由来の静的マスタデータを API 配信用に保持する DB | [[MasterDataDB設計]] / [[テーブル定義一覧]] |

## 基本方針

- 動的データと静的マスタデータを同一 DB / 同一キャッシュ責務に混ぜない。
- filebase YAML は編集・レビューの正本、MasterDataDB は API が常時参照する配信用データストアとする。
- API 起動時または Seeder API 実行時に filebase を読み、MasterDataDB へ upsert する。
- API は MasterDataDB から必要なマスタを必要な単位で取得する。全マスタを常時メモリ保持しない。
- プラグインは API 経由で必要なマスタのみ取得し、短命・用途別キャッシュに留める。

## ドキュメント一覧

1. [[MasterDataDB設計]]
2. [[MasterDataDBテーブル一覧]]
3. [[MasterDataDB同期フロー]]
4. [[テーブル定義一覧]]

## 更新ルール

- MasterDataDB のテーブルを追加・変更した場合は `40_database/MasterDataDB/` と本ディレクトリを更新する。
- AstralRecord のテーブルを追加・変更した場合も `table-definitions/` の閲覧用ミラーを更新する。
- filebase YAML スキーマを変更した場合は `00_docs/50_Filebase設計書/` と Seeder の変換仕様を更新する。
- API のレスポンス DTO を変更した場合は `00_docs/20_API設計書/` の対象 feature も更新する。
