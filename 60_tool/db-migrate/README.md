# Database Migration Tool

既存の `AstralRecord` DBへ、`db-migrate.config.json` の manifest に明示された本番 migration だけを順番に適用し、適用後のテーブル・列・型・NULL性・主キー・check制約・索引キー順を検査する。

## 実行

```powershell
E:\AstralRecord-Workspace\60_tool\13-db-migrate.bat
```

接続文字列は、設定の `connectionStrings.sqlServer` が空の場合、`sourceApiAppsettingsPath` の `ConnectionStrings:SqlServer` から解決する。接続文字列や秘密情報は画面へ表示しない。

同じDBに対する並行実行は `sp_getapplock` で直列化し、最大120秒待機する。`dbo.schema_migration` にmigration IDとSQL本文のSHA-256を記録するため、適用済みSQLは再実行せず、適用済みIDの内容変更は失敗させる。migration SQL は `GO` バッチに分割して実行するが、各SQLのトランザクション境界はmigration自身の定義に従う。

manifestの `preExistingMigrationFileNames` には、過去に適用済みでこのrunnerから再実行しないSQLを明示する。migrationディレクトリに未登録のSQLがあれば終了コード1で停止するため、新規SQLのmanifest登録漏れを検出できる。このツールはDBを削除せず、適用またはスキーマ検査が失敗した場合は終了コード1を返す。`--validate-only` はDBへ接続せず、manifestとSQLファイルだけを検査する。
