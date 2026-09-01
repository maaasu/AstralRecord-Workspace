# テーブル定義一覧

このディレクトリは SQL Server のテーブル定義 Markdown と DB 別 `init.sql` を管理する。

## DB 一覧

| DB | 役割 | 定義 |
|:--|:--|:--|
| `AstralRecord` | プレイヤー、アカウント、インベントリ、装備個体などの動的データ | `table-definitions/AstralRecord/` |
| `MasterDataDB` | filebase 由来の配信用マスタデータ | `table-definitions/MasterDataDB/` |
| `HistoryDB` | ログイン/ログアウトなどの履歴データ | `table-definitions/HistoryDB/` |

## init.sql

| DB | 初期化 SQL |
|:--|:--|
| `AstralRecord` | `AstralRecord/init.sql` |
| `MasterDataDB` | `MasterDataDB/init.sql` |
| `HistoryDB` | `HistoryDB/init.sql` |

## 本番 migration

| DB | migration | 内容 |
|:--|:--|:--|
| `AstralRecord` | `AstralRecord/migrations/20260802_add_learned_skills.sql` | 習得スキル個体、装着シジル、6プリセット、動的補償メールの追加 |
| `AstralRecord` | `AstralRecord/migrations/20260810_orb_enchant_effect_id.sql` | 装備エンチャントを共通マスタ effect ID 参照へ原子的に移行 |
| `AstralRecord` | `AstralRecord/migrations/20260811_equipment_orb_operation.sql` | オーブ操作台帳、個体出品range-lock索引、旧カテゴリデータ移行 |
| `AstralRecord` | `AstralRecord/migrations/20260812_account_dungeon_record.sql` | アカウント単位のダンジョン踏破記録を追加 |
| `AstralRecord` | `AstralRecord/migrations/20260815_trade_commit.sql` | プレイヤー間トレードの冪等確定台帳を追加 |
| `AstralRecord` | `AstralRecord/migrations/20260819_add_donor_permission.sql` | `dbo.user.permission` に DONOR(5) を追加 |
| `AstralRecord` | `AstralRecord/migrations/20260830_market_purchase_receipt.sql` | 購入再送用の更新 inventory entry ID receipt を追加。対応 API 配置前に適用 |
| `AstralRecord` | `AstralRecord/migrations/20260901_account_delete_receipt.sql` | アカウント削除の確定応答台帳を追加 |

## AstralRecord

| テーブル | 定義 |
|:--|:--|
| `dbo.user` | `AstralRecord/dbo.user.md` |
| `dbo.user_setting` | `AstralRecord/dbo.user_setting.md` |
| `dbo.player_mail_state` | `AstralRecord/dbo.player_mail_state.md` |
| `dbo.player_mail_delivery` | `AstralRecord/dbo.player_mail_delivery.md` |
| `dbo.account_mob_record` | `AstralRecord/dbo.account_mob_record.md` |
| `dbo.account_dungeon_record` | `AstralRecord/dbo.account_dungeon_record.md` |
| `dbo.account` | `AstralRecord/dbo.account.md` |
| `dbo.account_delete_receipt` | `AstralRecord/dbo.account_delete_receipt.md` |
| `dbo.account_class_progress` | `AstralRecord/dbo.account_class_progress.md` |
| `dbo.account_learned_skill` | `AstralRecord/dbo.account_learned_skill.md` |
| `dbo.account_learned_skill_sigil` | `AstralRecord/dbo.account_learned_skill_sigil.md` |
| `dbo.account_guide_step_progress` | `AstralRecord/dbo.account_guide_step_progress.md` |
| `dbo.account_skilltree_state` | `AstralRecord/dbo.account_skilltree_state.md` |
| `dbo.account_skilltree_unlocked_node` | `AstralRecord/dbo.account_skilltree_unlocked_node.md` |
| `dbo.login_bonus_claim` | `AstralRecord/dbo.login_bonus_claim.md` |
| `dbo.skill_bind_preset` | `AstralRecord/dbo.skill_bind_preset.md` |
| `dbo.inventory` | `AstralRecord/dbo.inventory.md` |
| `dbo.inventory_entry` | `AstralRecord/dbo.inventory_entry.md` |
| `dbo.equipment_instance` | `AstralRecord/dbo.equipment_instance.md` |
| `dbo.equipment_instance_stat_roll` | `AstralRecord/dbo.equipment_instance_stat_roll.md` |
| `dbo.equipment_instance_enchant` | `AstralRecord/dbo.equipment_instance_enchant.md` |
| `dbo.equipment_instance_rune` | `AstralRecord/dbo.equipment_instance_rune.md` |
| `dbo.equipment_orb_operation` | `AstralRecord/dbo.equipment_orb_operation.md` |
| `dbo.equipment_loadout` | `AstralRecord/dbo.equipment_loadout.md` |
| `dbo.equipment_loadout_slot` | `AstralRecord/dbo.equipment_loadout_slot.md` |
| `dbo.market_account_state` | `AstralRecord/dbo.market_account_state.md` |
| `dbo.market_listing` | `AstralRecord/dbo.market_listing.md` |
| `dbo.market_listing_source` | `AstralRecord/dbo.market_listing_source.md` |
| `dbo.market_transaction` | `AstralRecord/dbo.market_transaction.md` |
| `dbo.market_price_snapshot` | `AstralRecord/dbo.market_price_snapshot.md` |
| `dbo.trade_commit` | `AstralRecord/dbo.trade_commit.md` |
| `dbo.web_login_challenge` | `AstralRecord/dbo.web_login_challenge.md` |

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
