# MasterDataDB

filebase YAML を API 配信用の SQL Server マスタとして保持する DB 定義。

## ファイル

| パス | 内容 |
|:--|:--|
| `init.sql` | MasterDataDB の DDL |
| `dbo.master_data_source/master_data_source.md` | source 定義テーブル |
| `dbo.master_data_entry/master_data_entry.md` | マスタ本体テーブル |
| `dbo.master_data_reference/master_data_reference.md` | マスタ間参照テーブル |
| `dbo.master_data_seed_run/master_data_seed_run.md` | Seeder 実行履歴テーブル |

## 関連 docs

- `00_docs/40_DB設計書/master-data/MasterDataDB設計.md`
- `00_docs/50_Filebase設計書/master-data/Filebase概要.md`
