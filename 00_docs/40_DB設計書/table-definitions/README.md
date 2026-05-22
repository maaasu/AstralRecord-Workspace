# テーブル定義一覧

このディレクトリは `40_database/` 配下のテーブル定義 Markdown を `00_docs` から確認できるようにした閲覧用ミラーです。  
DDL の正本は各 DB の `40_database/**/` 側に置くが、設計レビューではこのディレクトリからカラム・制約・インデックス定義を確認できる。

## DB 一覧

| DB | テーブル定義 |
|:--|:--|
| `AstralRecord` | `table-definitions/AstralRecord/` |
| `MasterDataDB` | `table-definitions/MasterDataDB/` |

## AstralRecord

| テーブル | 定義 |
|:--|:--|
| `dbo.user` | `AstralRecord/dbo.user.md` |
| `dbo.account` | `AstralRecord/dbo.account.md` |
| `dbo.inventory` | `AstralRecord/dbo.inventory.md` |
| `dbo.inventory_entry` | `AstralRecord/dbo.inventory_entry.md` |
| `dbo.equipment_instance` | `AstralRecord/dbo.equipment_instance.md` |
| `dbo.equipment_instance_stat_roll` | `AstralRecord/dbo.equipment_instance_stat_roll.md` |
| `dbo.equipment_instance_enchant` | `AstralRecord/dbo.equipment_instance_enchant.md` |
| `dbo.equipment_instance_rune` | `AstralRecord/dbo.equipment_instance_rune.md` |
| `dbo.equipment_loadout` | `AstralRecord/dbo.equipment_loadout.md` |
| `dbo.equipment_loadout_slot` | `AstralRecord/dbo.equipment_loadout_slot.md` |
| `dbo.rune_instance` | `AstralRecord/dbo.rune_instance.md` |
| `dbo.rune_instance_stat_roll` | `AstralRecord/dbo.rune_instance_stat_roll.md` |

## MasterDataDB

| テーブル | 定義 |
|:--|:--|
| `dbo.master_data_source` | `MasterDataDB/dbo.master_data_source.md` |
| `dbo.master_data_entry` | `MasterDataDB/dbo.master_data_entry.md` |
| `dbo.master_data_reference` | `MasterDataDB/dbo.master_data_reference.md` |
| `dbo.master_data_seed_run` | `MasterDataDB/dbo.master_data_seed_run.md` |
