# DB Reset Except Release Notes Tool

`11-db-reset-except-release-notes.bat` は、Release Note の公開・通知状態だけを保持し、`AstralRecord`、`MasterDataDB`、`HistoryDB` を最新の `init.sql` から再作成するDB操作入口です。

## 再作成対象と保持対象

- `AstralRecord`: `dbo.release_note` と `dbo.release_notification_outbox` を保持し、その他のデータとスキーマを再作成する
- `MasterDataDB`: データとスキーマを再作成する
- `HistoryDB`: データとスキーマを再作成する

再作成には次の3ファイルを使います。ツールのビルド出力へコピーされた同じファイルが実行時の入力です。

- `00_docs/40_Database設計書/table-definitions/AstralRecord/init.sql`
- `00_docs/40_Database設計書/table-definitions/MasterDataDB/init.sql`
- `00_docs/40_Database設計書/table-definitions/HistoryDB/init.sql`

`AstralRecord` を削除する前に、保持対象の2表へ排他ロックを取り、同一の `SERIALIZABLE` トランザクション内で同じSQL Server上の一時退避DBへコピーします。3DBの再作成後、`release_note`、`release_notification_outbox` の順で復元し、両表の件数が退避時と一致した場合だけ一時退避DBを削除します。

再作成した `AstralRecord` は、復元と一時退避DBの削除が終わるまでツール自身の接続で `SINGLE_USER` に保持します。処理が途中で失敗した場合は `AstralRecord` を `OFFLINE` にして不完全なDBへの接続を防ぎ、一時退避DBの名前と退避件数、復旧コマンドを標準エラーへ表示します。

本番実行ではSQL Serverのセッションロックで同じDBに対するリセットを直列化し、設定したAPI/Webルートへ実行ID付きの `app_offline.htm` を配置します。通常実行は既存マーカーが1つでもあると停止します。`--restore-backup` は退避DB名に記録された実行IDと全マーカーが完全一致する場合だけ、失敗した処理の隔離を引き継ぎます。完了時も同じ実行IDのマーカーだけを削除します。

## 実行

通常は確認入力が必要です。

```bat
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat
```

表示された内容を確認し、`RESET` と入力してください。

確認を省略する場合は `--yes` を付けます。

```bat
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat --yes
```

別の設定ファイルを使う場合は `--config` を指定できます。

```powershell
dotnet run --project E:\AstralRecord-Workspace\60_tool\db-reset-except-release-notes\DbResetExceptReleaseNotesTool.csproj -- --config E:\path\to\db-reset-except-release-notes.config.json --yes
```

途中失敗時は、エラーに表示された一時退避DBを指定して再作成と復元を再実行します。

```bat
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat --yes --restore-backup AstralRecord_ReleaseNotesBackup_<表示された識別子>
```

未完了の一時退避DBが残っている場合、通常モードは新しい退避を開始せず、`--restore-backup` の指定を求めて停止します。

保持対象表が存在せず一時退避DBを作らない処理で途中失敗した場合は、表示された実行IDを指定して隔離を引き継ぎます。`--resume-operation` は、設定した全API/Webルートに同じ実行IDの保守マーカーが既に残っている場合だけ実行できます。

```bat
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat --yes --resume-operation <表示された実行ID>
```

## 接続設定

既定の設定ファイルは `db-reset-except-release-notes.config.json` です。各接続文字列は次の優先順位で解決します。

1. 専用設定ファイルの `connectionStrings.*`
2. `sourceApiAppsettingsPath` で指定したAPI `appsettings.json` の `ConnectionStrings:*`

接続文字列の実値はコミットせず、サーバー側設定またはユーザー専用の設定ファイルで指定してください。ツールのサマリーには接続文字列を表示しません。

対象DB名が `AstralRecord`、`MasterDataDB`、`HistoryDB` 以外の場合は、誤接続防止のため実行を拒否します。最初のDBを変更する前に、3接続すべての `master` へ接続できることと、3つの `init.sql` が対象DBを作成することを検査します。

## 注意事項

- 各DBの再作成では `SINGLE_USER WITH ROLLBACK IMMEDIATE` を使い、既存DBを削除してから `init.sql` を `GO` 単位で実行します。ツールはAPI/Webを `app_offline.htm` で停止し、再作成中の `AstralRecord` も隔離します。Minecraft Pluginが保持するプレイヤー状態の再送を避けるため、Minecraftサーバーは実行前に停止してください。
- これは破壊的操作です。Release Note 2表以外の既存データは保持しません。
- 3DBの再作成は単一トランザクションではありません。途中失敗時は一部のDBだけが再作成済みとなることがあります。原因を直した後、表示された `--restore-backup` 付きコマンドを実行してください。
- 復元に失敗した場合は、表示された一時退避DBを削除しないでください。保持データの復旧元です。復旧モードが成功すると自動で削除されます。
- `--yes` は確認とBAT終了時の `pause` を省略します。接続、対象DB名、入力SQL、復元件数の検査は省略しません。

## 再作成後の復旧

`MasterDataDB` は空で作成されます。API起動時のSeeder、またはAPI起動後に次のコマンドを実行してfilebaseから再投入してください。

```bat
E:\AstralRecord-Workspace\60_tool\03-master-data-reload.bat
```

Plugin側でMasterDataをキャッシュしている場合は、既存のMasterData再読込手順を実行してください。

## 統合テスト

次のテストは、設定されたSQL Serverにランダムな `_Integration_<識別子>` 付きの3DBだけを作成します。実行排他、保守マーカーの所有権、最新 `init.sql` の実行、Release Noteの退避・復元、一般データの消去、取消receipt列、途中失敗時の `OFFLINE` 隔離、`--restore-backup` による復旧を確認し、最後にテストDBと一時マーカーを削除します。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File E:\AstralRecord-Workspace\60_tool\db-reset-except-release-notes\tests\db-reset-except-release-notes.integration.ps1
```
