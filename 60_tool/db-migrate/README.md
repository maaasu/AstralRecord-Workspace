# Database Migration Tool

既存のゲームDBまたはHistoryDBへ、対応する manifest に明示された本番 migration だけを順番に適用し、適用後のテーブル・列・型・NULL性・主キー・check制約・索引キー順を検査する。

## 実行

```powershell
E:\AstralRecord-Workspace\60_tool\13-db-migrate.bat
E:\AstralRecord-Workspace\60_tool\15-history-db-migrate.bat
```

ゲームDB用manifestは、設定の `connectionStrings.sqlServer` が空の場合、`sourceApiAppsettingsPath` の `ConnectionStrings:SqlServer` から解決する。HistoryDB用manifestは `connectionStringName: History` と `expectedDatabase: HistoryDB` を明示し、`connectionStrings.history` または `ConnectionStrings:History` から解決する。接続文字列や秘密情報は画面へ表示しない。

同じDBに対する並行実行は `sp_getapplock` で直列化し、最大120秒待機する。`dbo.schema_migration` にmigration IDとSQL本文のSHA-256を記録するため、適用済みSQLは再実行せず、適用済みIDの内容変更は失敗させる。migration SQL は `GO` バッチに分割して実行するが、各SQLのトランザクション境界はmigration自身の定義に従う。

manifestの `preExistingMigrationFileNames` には、過去に適用済みでこのrunnerから再実行しないSQLを明示する。migrationディレクトリに未登録のSQLがあれば終了コード1で停止するため、新規SQLのmanifest登録漏れを検出できる。このツールはDBを削除せず、適用またはスキーマ検査が失敗した場合は終了コード1を返す。`--validate-only` はDBへ接続せず、manifestとSQLファイルだけを検査する。

`20260923_rename_archer_shared_skill_ids.sql` はプレイヤーの習得スキルIDと旧形式バインド参照を移行する。ゲームサーバーを停止して入場を閉じた状態でDB migrationを適用し、Filebaseの配置とMasterDataDBのseedが完了するまでサーバーを再開しない。途中で習得スキルのAPI整合処理が走らないようにする。

新しい本番SQLを追加するときは、同じ変更で対象DBのmanifestに実行順と `expectation` を登録する。未適用のSQLを `preExistingMigrationFileNames` に入れて検査だけを通してはいけない。HistoryDBの `20260921_player_activity.sql` は、プレイヤー行動履歴8表を適用し、`dbo.schema_migration` へSQL hash付きで記録する。後続の `20260924_boss_activity_duration.sql` はボス攻略・参加者の2表とダンジョン攻略時間を、`20260925_player_activity_user_observation_index.sql` はイベント履歴の代表アカウント名検索用索引を追加する。攻略時間は開始・終了時刻から求める永続化計算列で、過去のダンジョン記録と旧APIの書き込みにも対応する。HistoryDB migration → API → Web → Plugin の順で適用する。

登録漏れ・実行対象の取り違えは `tests/db-migrate.static.ps1`、ゲームDBの初回適用・履歴付き再実行・スキーマ不一致の拒否は `tests/db-migrate.integration.ps1`、HistoryDBの初回適用・履歴付き再実行・接続先DBの拒否は `tests/history-db-migrate.integration.ps1` で確認する。統合テストはローカルSQL Serverに一意名の使い捨てDBを作り、本番DBには接続しない。
