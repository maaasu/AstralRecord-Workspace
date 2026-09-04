# DB Reset Except Release Notes Tool

`11-db-reset-except-release-notes.bat` は、`04-db-rebuild.bat` のように `60_tool` のどのカレントディレクトリからでも実行できるDB操作入口です。

## リセット対象

次の3 DBへ同じSQLを順番に実行します。

- `AstralRecord`: `dbo.release_note` と `dbo.release_notification_outbox` を保持し、それ以外のユーザーテーブルを空にする
- `MasterDataDB`: 全ユーザーテーブルを空にする
- `HistoryDB`: 全ユーザーテーブルを空にする

スキーマは削除しません。実行時は対象DBを一時的に `SINGLE_USER WITH ROLLBACK IMMEDIATE` にして、稼働中の接続による書き込み競合を排除します。完了または失敗時には、開始前のアクセスモードへ戻します。その後、外部キーを一時的に無効化して削除し、元から有効だった外部キーを元の信頼状態へ戻します。identity列があるテーブルは、空の状態で次の採番が1になるように再シードします。

`release_notification_outbox` は `release_note` の外部キーを持ち、送信状態・送信日時・DiscordメッセージIDを保持します。そのため送信情報を維持するには、親の `release_note` もセットで保持する必要があります。

## 実行

通常は確認入力が必要です。

```bat
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat
```

表示された内容を確認し、`RESET` と入力してください。

自動実行などで確認を省略する場合は、明示的に `--yes` を付けます。

```bat
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat --yes
```

別の設定ファイルを使う場合は、`04-db-rebuild.bat` と同じく `--config` を指定できます。

```powershell
dotnet run --project E:\AstralRecord-Workspace\60_tool\db-reset-except-release-notes\DbResetExceptReleaseNotesTool.csproj -- --config E:\path\to\db-reset-except-release-notes.config.json --yes
```

## 接続設定

既定の設定ファイルは `db-reset-except-release-notes.config.json` です。各接続文字列は次の優先順位で解決します。

1. 専用設定ファイルの `connectionStrings.*`
2. `sourceApiAppsettingsPath` で指定したAPI `appsettings.json` の `ConnectionStrings:*`

接続文字列の実値はコミットせず、サーバー側設定またはユーザー専用の設定ファイルで指定してください。ツールのサマリーには接続文字列を表示しません。

対象DB名が `AstralRecord`、`MasterDataDB`、`HistoryDB` 以外の場合は、誤接続防止のため実行を拒否します。

## SQL単体実行

[`reset-db-except-release-notes.sql`](./reset-db-except-release-notes.sql) はSQL Server Management Studioなどから、対象DBへ接続して実行できます。DB名を確認して、次の順に1回ずつ実行してください。

1. `AstralRecord`
2. `MasterDataDB`
3. `HistoryDB`

SQLファイルにも対象DB名の検査があるため、`master` などへ接続したまま実行することはできません。

## 注意事項

- `SINGLE_USER WITH ROLLBACK IMMEDIATE` により、対象DBの他の接続と実行中トランザクションは切断・ロールバックされます。実行前にAPI、Web、Pluginなど、対象DBへ書き込むプロセスを停止してください。
- これは破壊的操作です。必要な場合はDBバックアップを先に取得してください。
- ツールは3 DBへの接続をすべて開けることを確認してから、最初のDBをリセットします。ただし各DBのリセットは個別トランザクションのため、後続DBで失敗した場合に先行DBだけリセット済みとなる可能性があります。
- `--yes` は確認とBATの終了時 `pause` を省略します。対象DB検査、排他化、SQLトランザクションは省略しません。

## リセット後の復旧

`MasterDataDB` は空になるため、APIが起動している状態で次を実行してfilebaseから再投入してください。APIキーの設定が必要です。

```bat
E:\AstralRecord-Workspace\60_tool\03-master-data-reload.bat
```

APIを停止した状態で実行した場合は、再起動時に `MasterDataDB` が空であることを検知してSeederが実行されます。Plugin側でMasterDataをキャッシュしている場合は、必要に応じて既存のMasterData再読込手順も実行してください。
