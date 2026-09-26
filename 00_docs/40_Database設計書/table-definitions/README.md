# テーブル定義一覧

このディレクトリは SQL Server のテーブル定義 Markdown と DB 別 `init.sql` を管理する。

## DB 一覧

| DB | 役割 | 定義 |
|:--|:--|:--|
| `AstralRecord` | プレイヤー、アカウント、インベントリ、装備個体などの動的データ | `table-definitions/AstralRecord/` |
| `MasterDataDB` | filebase 由来の配信用マスタデータ | `table-definitions/MasterDataDB/` |
| `HistoryDB` | ログイン/ログアウトなどの履歴データ | `table-definitions/HistoryDB/` |
| `ManagementDB` | リセットしないプレイヤー識別・運営管理情報 | `table-definitions/ManagementDB/` |

## init.sql

| DB | 初期化 SQL |
|:--|:--|
| `AstralRecord` | `AstralRecord/init.sql` |
| `MasterDataDB` | `MasterDataDB/init.sql` |
| `HistoryDB` | `HistoryDB/init.sql` |
| `ManagementDB` | `ManagementDB/init.sql` |

## 本番 migration

| DB | migration | 内容 |
|:--|:--|:--|
| `AstralRecord` | `AstralRecord/migrations/20260905_account_learned_skill_operation.sql` | スキル mutation の冪等操作台帳を追加 |
| `AstralRecord` | `AstralRecord/migrations/20260910_market_listing_create_receipt.sql` | 出品作成の冪等結果台帳を追加。対応 API 配置前に適用 |
| `AstralRecord` | `AstralRecord/migrations/20260913_account_rebirth_progress.sql` | 転生進行カラムと初期制約を追加 |
| `AstralRecord` | `AstralRecord/migrations/20260915_expand_rebirth_experience_remainder.sql` | 100EXP単位の変換に合わせて転生EXP端数の許容範囲を0～99へ拡張。対応 API 配置前に適用 |
| `AstralRecord` | `AstralRecord/migrations/20260921_skilltree_safe_editor.sql` | 実ロード世代・server session・Plugin評価view・編集操作台帳を追加。対応API/Plugin/Web配置前に適用 |
| `AstralRecord` | `AstralRecord/migrations/20260921_skilltree_batch_editor.sql` | スキルツリー一括変更JSONとaction制約。safe_editor適用後、新API配置前に適用 |
| `AstralRecord` | `AstralRecord/migrations/20260926_market_web_purchase.sql` | Web購入要求の冪等台帳を追加。対応API/Plugin/Web配置前に適用 |
| `ManagementDB` | `ManagementDB/migrations/20260916_managed_network_and_bans.sql` | 設定・BAN・監査3表を追加。ManagementDB専用手順でAPI配置前に適用 |
| `ManagementDB` | `ManagementDB/migrations/20260917_web_credentials.sql` | Web固定認証とID単位ログイン試行記録を追加。ManagementDB専用手順でAPI配置前に適用 |
| `ManagementDB` | `ManagementDB/migrations/20260920_trusted_admin_browser.sql` | 信頼済みブラウザのトークン管理を追加。ManagementDB専用手順でAPI配置前に適用 |
| `HistoryDB` | `HistoryDB/migrations/20260921_player_activity.sql` | プレイヤー行動履歴8表を追加。HistoryDB用manifestでAPI配置前に適用 |

AstralRecordの本番 migration は `60_tool/db-migrate/db-migrate.config.json`、HistoryDBの本番 migration は `60_tool/db-migrate/history-db-migrate.config.json` の manifest で管理する。`01-deploy-debug.bat` は API 配置前に両manifestとManagementDB専用manifestの適用・対象スキーマ検査を実行する。どれかが失敗した場合、API/Webの配置を開始しない。`04-db-rebuild.bat` は既存データを削除する再構築用であり、稼働中DBの差分適用には使用しない。

開発中に使い終え、今後再実行しない旧migration SQLは保持しない。新規DBの定義は各DBの `init.sql` を正本とし、現在適用が必要な上記migrationだけを管理する。

player-state snapshot は既存DB向け migration を持たない。新しい `init.sql` でDBを作成するか、`60_tool/11-db-reset-except-release-notes.bat` で Release Note 2表を退避し、3DBを最新 `init.sql` から再作成して導入する。

`ManagementDB` はプレイヤー識別・Web管理権限・ネットワーク設定・BAN・Web固定認証・信頼済みブラウザを保持する独立DBです。既存3DB固定の `60_tool/db-migrate` と `60_tool/db-reset-except-release-notes` は対象外です。新規環境は `ManagementDB/init.sql`、既存環境は `ManagementDB/migrations/20260916_managed_network_and_bans.sql`、`20260917_web_credentials.sql`、`20260920_trusted_admin_browser.sql` をAPI配置前に別途適用します。適用コマンドと確認SQLは [ネットワーク運用](../../10_Plugin設計書/feature/33-network/33_5-例外・ログ・運用.md) を参照します。

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
| `dbo.player_state_snapshot` | `AstralRecord/dbo.player_state_snapshot.md` |
| `dbo.account_class_progress` | `AstralRecord/dbo.account_class_progress.md` |
| `dbo.account_learned_skill` | `AstralRecord/dbo.account_learned_skill.md` |
| `dbo.account_learned_skill_operation` | `AstralRecord/dbo.account_learned_skill_operation.md` |
| `dbo.account_learned_skill_sigil` | `AstralRecord/dbo.account_learned_skill_sigil.md` |
| `dbo.account_guide_step_progress` | `AstralRecord/dbo.account_guide_step_progress.md` |
| `dbo.account_skilltree_state` | `AstralRecord/dbo.account_skilltree_state.md` |
| `dbo.account_skilltree_unlocked_node` | `AstralRecord/dbo.account_skilltree_unlocked_node.md` |
| `dbo.skilltree_definition_generation` | `AstralRecord/dbo.skilltree_definition_generation.md` |
| `dbo.skilltree_account_session` | `AstralRecord/dbo.skilltree_account_session.md` |
| `dbo.skilltree_server_runtime` | `AstralRecord/dbo.skilltree_server_runtime.md` |
| `dbo.skilltree_server_player_view` | `AstralRecord/dbo.skilltree_server_player_view.md` |
| `dbo.skilltree_operation` | `AstralRecord/dbo.skilltree_operation.md` |
| `dbo.skilltree_migration_operation` | `AstralRecord/dbo.skilltree_migration_operation.md` |
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
| `dbo.market_web_purchase` | `AstralRecord/dbo.market_web_purchase.md` |
| `dbo.market_price_snapshot` | `AstralRecord/dbo.market_price_snapshot.md` |
| `dbo.trade_commit` | `AstralRecord/dbo.trade_commit.md` |
| `dbo.schema_migration` | `AstralRecord/dbo.schema_migration.md` |
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
| `dbo.player_activity_batch` | `HistoryDB/dbo.player_activity.md` |
| `dbo.player_ip_observation` | `HistoryDB/dbo.player_activity.md` |
| `dbo.player_trade_activity` / `dbo.player_trade_activity_item` | `HistoryDB/dbo.player_activity.md` |
| `dbo.dungeon_clear_activity` / `dbo.dungeon_clear_participant` | `HistoryDB/dbo.player_activity.md` |
| `dbo.mob_damage_summary` / `dbo.mob_player_death` | `HistoryDB/dbo.player_activity.md` |

## ManagementDB

| テーブル | 定義 |
|---|---|
| `dbo.player` | `ManagementDB/dbo.player.md` |
| `dbo.schema_migration` | `ManagementDB/dbo.schema_migration.md` |
| `dbo.network_settings` | `ManagementDB/dbo.network_settings.md` |
| `dbo.network_ban` | `ManagementDB/dbo.network_ban.md` |
| `dbo.network_management_audit` | `ManagementDB/dbo.network_management_audit.md` |
| `dbo.web_credential` | `ManagementDB/dbo.web_credential.md` |
| `dbo.web_credential_login_attempt` | `ManagementDB/dbo.web_credential_login_attempt.md` |
| `dbo.web_trusted_browser` | `ManagementDB/dbo.web_trusted_browser.md` |
