# AstralRecord Database Guide

対象: `40_database/`

## 役割

- SQL Server の DB、テーブル、カラム、制約、リレーションの定義資料を管理する。
- file 系マスタデータは管理しない。YAML などの file マスタは `50_filebase/` を対象にする。

## 作業方針

- DB / テーブル定義を変更する場合は、Plugin と API の契約影響を確認する。
- 可変データと静的マスタデータを混在させない。
- スキーマ資料と実装側 Entity / Repository / SQL Server 接続前提が一致しているか確認する。

