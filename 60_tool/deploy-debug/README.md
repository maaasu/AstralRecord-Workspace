# Dev更新の入口

通常は `60_tool/01-deploy-debug.bat` を一度実行します。PowerShell 7が必要です。Devの停止・再起動は運用者が行います。

1. Devのプレイヤーを退出させ、保存完了を待ってDevを停止する。更新中は入場させない。
2. `01-deploy-debug.bat` を実行し、停止・入場制限の確認に応答する。
3. バッチがビルド・配置・必要なseedを終えると、Devの起動案内を表示して待機する。
4. Devを起動する。まだ入室しない。
5. 新しい起動sessionのready、スキルツリー世代を確認し、設定した開発アカウントだけを自動移行する。
6. バッチの完了表示を確認して入室する。

JARコピーだけでは完了扱いにしません。API障害、起動待ちタイムアウト、世代不一致、移行拒否は非0終了です。サーバーを強制起動したり入場制限を自動解除したりしません。

## 一度だけ行う設定

既存の `deploy-debug.config.json` に `devWorkflow` を追加しています。必要ならこのファイルを `deploy-debug.local.json` にコピーしてください。localが存在する場合はそちらが優先されます。明示の `-ConfigPath` も使用できます。配置先・API/Webの有効無効は既存設定を引き継ぎます。DevのJARとマスタだけなら `api.enabled` / `web.enabled` をfalseにして設定します。

`devWorkflow.migration.serverIds` に実際のDevの `api.serverId` を1つ、`accountIds` に開発用 **アカウントUUID** を設定します。MinecraftユーザーUUIDではありません。`ExplicitAccounts` 固定で、全DB候補を移行する `AllCandidates` はDev入口では拒否します。DBの再構築は不要です。

`baseUrl` と、共通APIキー・専用migrationキーを取得する環境変数名も設定します。キーは環境変数へ安全に設定し、JSONやログへ実値を保存しません。HTTPS証明書は通常の信頼設定を使用します。Devとチャンネルで同じAPI/マスターDBを共有している場合、APIマスタ更新の影響も共有する点は従来どおりです。

| 設定 | 意味 |
|---|---|
| `runRoot` | 空なら設定ファイルと同じ場所の `runs`。実行ごとの保存先は自動作成 |
| `startupTimeoutSeconds` | 起動待ちの上限。既定設定は900秒 |
| `pollIntervalSeconds` | 起動確認の間隔。既定設定は5秒 |
| `seedMasterData` | 配置後・Dev起動前にAPIのdiff seedを実行。PluginOnlyでは省略 |

初期設定のserverIds/accountIdsは空です。推測で実データを移行しないため、設定が揃うまでは配置前に停止します。共通APIキーとmigrationキーは異なる値にします。

## 入口の整理

- `01`：有効なAPI/Web/Plugin/Filebaseを従来どおりビルド・配置し、Dev起動待ちと世代移行まで実行。
- `02`：`01 -PluginOnly` のショートカット。従来どおりPluginテストを省略。Filebase/seedは実行しない。
- `03`：`01 -MasterDataOnly` のショートカット。JARはビルドせず、01と同じ設定のFilebaseを同期してseed・起動待ち・移行。今回の停止運用へ統一し、ゲーム内reload操作は不要。
- `10`：リリース管理API/Webの専用入口を維持。Dev更新の停止待ち・移行を混ぜない。

`03` の設定元は旧 `master-data-reload.config.json` から01と同じdeploy-debug設定へ統合しました。旧PowerShellは詳細操作用に残しています。旧 `-Mode rebuild` は日常入口には引き継がず、通常はdiff seedだけを使用します。

```powershell
# 読み取りだけの設定・経路確認
.\60_tool\01-deploy-debug.bat -Plan
# 普段の更新（停止後）
.\60_tool\01-deploy-debug.bat
```

停止・入場制限を別の自動化が確認済みの場合だけ、`-ServersStopped -AdmissionClosed` で開始時の対話を省略できます。停止を検知・実行するフラグではありません。

## 失敗時

同じバッチ・設定・モードで再実行すると、未完了の実行記録を再利用します。配置成功後なら再ビルド・再コピーをせず、seed/起動待ち/移行から再開します。起動待ち中はDevを起動し、移行中の失敗ではDevを再起動せずに再実行してください。COMMITの結果不明でも同じoperation IDを使います。

配置中に失敗し完了記録がない場合は、配布状況が確定できないため自動でやり直しません。実行ログを確認して復旧してください。今回の変更で旧Devデプロイ処理自体をトランザクション化したわけではありません。途中で設定・モードを変更することも拒否します。

実行記録、ログ、履歴は自動削除しません。設定完了後の日常操作に `-Phase` や `-RunDirectory` の入力は不要です。破壊的な世代変更や非空legacyの承認は自動で行わず、通常の互換性検証に失敗した場合のみ管理対応が必要です。

## 検証

`tests/dev-update-entry.tests.ps1` は一時fixtureの偽ビルドbackendと、実際の配布処理を使って入口のモード・対象制限を検証します。起動待ち・seed・失敗再開は `../maintenance/tests/update-workflow.tests.ps1` のmock APIで検証します。本番環境は操作しません。
