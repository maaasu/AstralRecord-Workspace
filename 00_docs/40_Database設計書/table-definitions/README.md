# テーブル定義一覧

このディレクトリは SQL Server のテーブル定義 Markdown と DB 別 `init.sql` を管理する。

## DB 一覧

| DB | 役割 | 定義 |
|:--|:--|:--|
| `AstralRecord` | プレイヤー、アカウント、インベントリ、装備/ルーン個体などの動的データ | `table-definitions/AstralRecord/` |
| `MasterDataDB` | filebase 由来の配信用マスタデータ | `table-definitions/MasterDataDB/` |
| `HistoryDB` | ログイン/ログアウトなどの履歴データ | `table-definitions/HistoryDB/` |

## init.sql

| DB | 初期化 SQL |
|:--|:--|
| `AstralRecord` | `AstralRecord/init.sql` |
| `MasterDataDB` | `MasterDataDB/init.sql` |
| `HistoryDB` | `HistoryDB/init.sql` |

## AstralRecord

| テーブル | 定義 |
|:--|:--|
| `dbo.user` | `AstralRecord/dbo.user.md` |
| `dbo.user_setting` | `AstralRecord/dbo.user_setting.md` |
| `dbo.player_mail_state` | `AstralRecord/dbo.player_mail_state.md` |
| `dbo.account_mob_record` | `AstralRecord/dbo.account_mob_record.md` |
| `dbo.account` | `AstralRecord/dbo.account.md` |
| `dbo.account_skilltree_state` | `AstralRecord/dbo.account_skilltree_state.md` |
| `dbo.account_skilltree_unlocked_node` | `AstralRecord/dbo.account_skilltree_unlocked_node.md` |
| `dbo.skill_bind_preset` | `AstralRecord/dbo.skill_bind_preset.md` |
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

## HistoryDB

| テーブル | 定義 |
|:--|:--|
| `dbo.user_history` | `HistoryDB/dbo.user_history.md` |
