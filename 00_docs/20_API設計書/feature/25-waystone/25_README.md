# 25-waystone

このディレクトリは API `account-waystone` 機能の設計をまとめる。

## 目次

| 区分 | ドキュメント |
|:--|:--|
| 概要 | [[25_0.00-概要]] |
| モデル定義 | [[25_1.00-モデル定義]] |
| エンドポイント仕様 | [[25_3.00-索引]] |

## 変更時の同期対象

- API: `AccountWaystoneController` / `AccountWaystoneRepository` / `AccountWaystoneModels`
- DB: `dbo.account_waystone_unlock`
- Plugin: 2026-06-23 に `10_plugin/AstralRecord` から `feature/waystone` 実装を削除済み。再実装時に接続先を再定義する。
