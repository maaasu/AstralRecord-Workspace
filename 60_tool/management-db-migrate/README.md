# ManagementDB Migration Tool

`20260917_web_credentials.sql` と `20260920_trusted_admin_browser.sql` の明示登録済み migration だけを非破壊で適用・検査する専用runnerです。ゲーム用 `db-migrate` の対象や保護条件は変更しません。

`ConnectionStrings:Management` を配置先APIの `appsettings.json` から優先して読み、未設定時は同じ `ConnectionStrings:SqlServer` のDB名だけを `ManagementDB` に置換します。レビュー済みSQL本文のSHA-256、接続先、SQLの `USE`、各バッチ後のDB名を検査し、ManagementDB以外へ接続・移動しません。`dbo.player` と `dbo.schema_migration` がない初回環境では変更せず、`ManagementDB/init.sql` の適用を案内して停止します。

通常は `01-deploy-debug.bat` または `10-release-management-deploy.bat` がAPI/Web配置前に自動実行します。単独の手動復旧時だけ、次を実行します。

```powershell
.\14-management-db-migrate.bat
```

`--validate-only` はmanifestとSQLだけを検査し、DBへ接続・変更しません。統合テストは `ASTRALRECORD_SQLSERVER_TEST_CONNECTION` を設定したローカルSQL Server上のランダム名DBだけを作成・削除します。
