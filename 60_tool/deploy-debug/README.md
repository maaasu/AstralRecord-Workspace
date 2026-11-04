# Dev更新の入口

Control管理を有効にしたPCで01/02/03を実行するときは、[既存ツールgate](../control-gate/README.md)の`controlGate`を同じ設定ファイルに追加し、事前にControl CLIへ対話ログインしてください。PowerShellの`dev-update.ps1`直接実行も同じgateを通ります。管理下のDev停止/稼働状態はRunnerが確認し、`-ServersStopped`/`-ServerAlreadyRunning`はその代替証明になりません。`deploy-debug.ps1`単独の直接配布は管理下で拒否します。

通常は `60_tool/01-deploy-debug.bat` を実行します。PowerShell 7が必要です。Control連携によるDevの自動停止・起動は、下記の`devWorkflow.controlDevLifecycle.enabled=true`で有効にします。未設定/falseは従来の手動運転です。Filebaseとマスタだけを更新する03の稼働中モードと、自動停止・起動は併用しません。

## ControlでDevだけを自動停止・起動する

WebとCLIをこの機能に対応する版へ更新し、既存のControlログイン、DevのP1運転設定、Managed bindingを利用します。Runner/Agentの更新は不要です。`devWorkflow.migration.serverIds`と`controlGate.bindings.servers[].runtimeServerId`の対象は正確に`Dev`とします。ローカル設定に追加してください。

```json
"controlDevLifecycle": {
  "enabled": true,
  "stopTimeoutSeconds": 120,
  "startTimeoutSeconds": 900,
  "pollIntervalSeconds": 2
}
```

01/02/03の停止モードは、全資源の保守予約→Devの正常停止確認→ビルド・配置・必要なseed→Dev起動→新sessionのready/世代確認→パッチ公開→予約解放の順です。Devが既に停止していれば停止命令を省略します。起動確認はプロセス起動とPluginの準備完了を別々に確認します。入場制限は自動解除しません。

新起動の確認にはAPI runtimeの`serverStartedAtUtc`応答が必要です。API更新を含む01を先に実施してください。02/03で旧APIを使う場合、起動時刻が確認できず公開へ進みません。Pluginの起動時刻と同じDev PCのUTC時計でStart要求時刻を保存し、APP PCのheartbeat受信時計を新起動の証明には使いません。

01はAPI/Filebaseを共有していてもプロセス対象をDevだけとし、Build/Channel1/Channel2が稼働中でも実行できます。Environmentと変更対象のAPI/Web/Filebaseディレクトリは予約したまま、Devだけを正常停止・起動します。手動運転でも01の停止確認対象はDevだけです。共有API・DB・Filebaseを更新する範囲は従来と同じです。02もDevのみを対象とします。03の共有構成は従来どおり他の共有RPGを事前に停止し、完了まで停止を維持してください。どのモードも非Devへの自動停止・起動命令は送りません。

停止タイムアウト・通信断・要確認状態では配布を止め、同じ予約と操作IDを保持します。自動Killや別IDでの停止・起動のやり直しは行いません。同じ設定とバッチで再開してください。ビルド/DB/配置失敗時はDevを停止したまま既存の復旧手順へ進み、配布失敗を成功扱いで起動しません。`-Plan`はAPIやプロセスを操作せず、自動運転の予定を表示します。非対話実行では`-AdmissionClosed`で入場停止を確認します。

## 手動運転を使う場合

DevとChannelがAPI・DBを共用する環境では、Managed設定の`controlGate.sharedApiRuntimeServerIds`へ共用する全RPGのruntime IDを登録します。現在の配置は`["Dev","Build","Channel1","Channel2"]`です。01/02はこの宣言でプロセス対象を拡大せず、Devだけの停止・起動を確認します。03の共有API/Filebase更新は全対象の停止を確認し、手動運転では起動段階で全対象を起動してからDevのready確認とパッチ公開を完了します。`devWorkflow.migration.serverIds`はDevのままです。共有構成の03は`-ServerAlreadyRunning`を拒否します。

1. Devのプレイヤーを退出させ、保存完了を待ってDevを停止する。01/02では他サーバーの停止は要求しない。共有構成の03では共用する全RPGも停止する。更新中はDevへ入場させない。
2. `01-deploy-debug.bat` を実行し、停止・入場制限の確認に応答する。
3. バッチがビルド・配置・必要なseedを終えると、Devの起動案内を表示して待機する。
4. Devを起動する。共有構成の03の手動運転では他の共用RPGを先に起動し、最後にDevを起動する。まだ入室しない。
5. 新しい起動sessionのready、スキルツリー世代を確認し、その設定をパッチとして公開する。プレイヤーの一括更新は行わない。
6. バッチの完了表示を確認して入室する。

JARコピーだけでは完了扱いにしません。API障害、起動待ちタイムアウト、世代不一致、公開拒否は非0終了です。サーバーを強制起動したり入場制限を自動解除したりしません。各プレイヤーの更新は次回ログイン時に行います。

## 03で稼働中のDevを使う

この節はAPIがDev専用の構成に適用します。`sharedApiRuntimeServerIds`を設定した共通API環境では稼働中モードを使用せず、上記の全対象停止・起動手順を使用してください。

03はFilebase同期とAPI seedの後、Dev Pluginの再読込処理とactivation試行が終了したことをruntimeの`publicationRevision`で確認します。個別activation失敗はPlugin既存仕様に従い警告扱いとなるため、この確認だけでworld/NPC等の全activation成功を保証するものではありません。Devのプロセスsessionが変わらなくても、seed後にrevisionが進み、runtimeがreadyかつ安定していればパッチ公開へ進みます。マスタの変更でスキルツリー世代IDが変わる場合も、その新世代を確認します。

この運用には、APIの`publicationRevision`応答と、再読込ごとにそのrevisionを再登録するPluginが必要です。先に更新後のAPIとPluginを配置して一度起動してください。Plugin設定の`masterData.autoReload.enabled`も有効にします。それ以降の03ではDevの再起動は不要です。再読込が確認できない場合は完了扱いにならず、待機タイムアウトになります。

この変更より前に作成された`WaitingForStartup` runには再読込前のrevision記録がありません。そのrunを再開する場合は、従来どおりDevを再起動して完了させてください。

Devを動かしたまま実行する場合も、プレイヤーの入場を閉じ、保存を終え、他の自動書込を停止し、別のマスタ再読込が進行していない状態で03を実行します。対話実行では確認文に応答します。非対話実行では確認を明示します。`-AutomaticWritersStopped`は他の自動書込と進行中の別再読込がないことの確認にも使います。

```powershell
.\03-master-data-reload.bat -ServerAlreadyRunning -AdmissionClosed -AutomaticWritersStopped
```

`-ServerAlreadyRunning`は`03`専用です。`01`の通常デプロイは引き続きDevを停止し、新しい起動sessionを待ちます。Devを停止した状態から実行する場合は従来どおり`-ServersStopped -AdmissionClosed`を使用します。

## 一度だけ行う設定

既存の `deploy-debug.config.json` に `devWorkflow` を追加しています。必要ならこのファイルを `deploy-debug.local.json` にコピーしてください。localが存在する場合はそちらが優先されます。明示の `-ConfigPath` も使用できます。配置先・API/Webの有効無効は既存設定を引き継ぎます。DevのJARとマスタだけなら `api.enabled` / `web.enabled` をfalseにして設定します。

`devWorkflow.migration.serverIds` に実際のDevの `api.serverId` を1つ設定します。接続設定の名前は互換性のため `migration` のままですが、用途は起動確認とパッチ公開です。`scope`・`accountIds`・`accountUserIds` は不要です。以前の設定に残っていても、一括更新やキャラクター一覧取得には使用しません。DBの再構築は不要です。

`baseUrl` と、共通APIキー・専用migrationキーを取得する環境変数名も設定します。キーは環境変数へ安全に設定し、JSONやログへ実値を保存しません。HTTPS証明書は通常の信頼設定を使用します。Devとチャンネルで同じAPI/マスターDBを共有している場合、APIマスタ更新の影響も共有する点は従来どおりです。

既存APIの設定を流用する場合は `migration.apiSettingsPath` に配置先 `appsettings.json` の絶対パスを指定できます。その場合は環境変数よりファイル参照を優先し、`ApiKey:Key` と `SkillTreeRuntime:MigrationKey` を毎回読み取ります。APIキーの複製や環境変数の手入力は不要で、ファイルのキー変更にも追従します。migrationキーは共通キー・runtimeキーと分離します。キー値をログや実行記録へ出力しません。

既存の閉域APIで証明書検証を省略する運用は `migration.allowPrivateApiInsecureTls=true` で明示できます。`baseUrl` は **HTTPSのプライベートIPv4またはループバックIPを直接指定** してください（例: `https://192.168.0.88:444`）。DNS名、公開IP、リンクローカル、HTTPにはこの例外を適用できません。省略時は設定した同一originへの要求だけを許可し、プロキシとリダイレクトを使いません。既定値falseでは通常の証明書検証を維持し、OS全体の信頼設定は変更しません。

| 設定 | 意味 |
|---|---|
| `runRoot` | 空なら設定ファイルと同じ場所の `runs`。実行ごとの保存先は自動作成 |
| `startupTimeoutSeconds` | 起動待ちの上限。既定設定は900秒 |
| `pollIntervalSeconds` | 起動確認の間隔。既定設定は5秒 |
| `seedMasterData` | Filebase同期後にAPIのdiff seedを実行。PluginOnlyでは省略 |

この環境のserverIdsはDevの `plugins/AstralRecord/config.yml` にある `api.serverId` に合わせて `Dev` に設定します。API接続先はAPP-01の配置済みWeb設定で確認した `https://app-01:444`、キーの参照先はAPP-01のAPI `appsettings.json` です。共通APIキーとmigrationキーは異なる値にします。Devで公開したパッチは同じAPI・DBを使う他チャンネルにも影響し、古い設定のチャンネルへの参加が制限されます。Devと本番を別の公開段階に保つ場合はAPI・DBを分離してください。

## 入口の整理

01/02/03/10のBATは、明示`-ConfigPath`、`deploy-debug.local.json`、`deploy-debug.config.json`の順に設定を選びます。10はPowerShell 7上でAPI/Web専用backendを実行し、Dev workflowは開始しません。新環境の配置とControl/TLS/Filebaseの未完了事項は[設定一覧](../CONFIGURATION.md)を参照してください。

Control管理markerがあるPCの10はManaged設定と`-PreflightOnly`を使用します。Managedの直接配布はbackendが拒否するため、実配布は01の管理workflowで行います。01 backendのPowerShell 7起動は修正済みです。Managed設定と、管理root登録・Host接続・TLS/Filebase切替の状況は上記設定一覧を確認してから実行してください。

- `01`：有効なAPI/Web/Plugin/Filebaseを従来どおりビルド・配置し、Dev起動待ちとパッチ公開まで実行。
- `02`：`01 -PluginOnly` のショートカット。既定ではPluginテストを省略し、`-TestMode` で変更できる。Filebase/seedは実行しない。
- `03`：`01 -MasterDataOnly` のショートカット。JARはビルドせず、01と同じ設定のFilebaseを同期してseed・起動または稼働中Pluginの再読込を待ち、パッチ公開。稼働中利用ではプレイヤー入場と他の自動書込を止める。
- `10`：リリース管理API/Webの専用入口を維持。Dev更新の停止待ち・公開登録を混ぜない。
- `17`：大きな仕様変更・復旧用のプレイヤー一括更新。通常更新とは別の [専用手順](../player-patch-migration/README.md)。

`03` の設定元は旧 `master-data-reload.config.json` から01と同じdeploy-debug設定へ統合しました。旧PowerShellは詳細操作用に残しています。旧 `-Mode rebuild` は日常入口には引き継がず、通常はdiff seedだけを使用します。

```powershell
# 読み取りだけの設定・経路確認
.\60_tool\01-deploy-debug.bat -Plan
# 普段の更新（停止後）
.\60_tool\01-deploy-debug.bat
```

停止・入場制限を別の自動化が確認済みの場合だけ、`-ServersStopped -AdmissionClosed` で開始時の対話を省略できます。停止を検知・実行するフラグではありません。

## Pluginテストの実行範囲

01/02に `-TestMode All|Selected|Skip` を指定できます。未指定時は従来どおり01が `All`、02が `Skip` です。API/Webなどの処理範囲は変わりません。

| モード | 動作 |
|---|---|
| `All` | Pluginの全テストをコンパイル・実行 |
| `Selected` | テストソースをコンパイルし、`-Tests` に指定したテストだけ実行 |
| `Skip` | テストのコンパイル・実行を両方省略 |

```powershell
# 全テストを実行して更新
.\60_tool\01-deploy-debug.bat -TestMode All
# 指定テストだけを実行してPluginを更新（カンマ区切り、クラス名のワイルドカードも可）
.\60_tool\02-deploy-debug-plugin-only.bat -TestMode Selected -Tests "ClassRepositoryTest,SkillPermissionServiceTest"
# テストを省略して更新
.\60_tool\01-deploy-debug.bat -TestMode Skip
# 02でも明示すれば全テストを実行
.\60_tool\02-deploy-debug-plugin-only.bat -TestMode All
# 実行前にモード・対象だけ確認（ビルド・配置なし）
.\60_tool\01-deploy-debug.bat -Plan -TestMode Selected -Tests "*RepositoryTest"
```

`-Tests` は `Selected` の場合だけ必須です。クラス名、パッケージ付きクラス名、`*` / `?`、`ClassTest#method`（複数メソッドは `+` 区切り）を指定できます。正規表現や `!` による除外は扱いません。空要素、不正な組合せ、Pluginをビルドしないモードでの指定は副作用の前に拒否します。指定全体に一致するテストがなければビルド失敗となり、配置しません。複数パターンのうち一部だけが一致する場合は、一致したテストを実行します。変更の影響からテストを自動選択する機能ではありません。

全モードで、Pluginの `pom.xml`、`src`、存在する場合は `.mvn` を実行ごとの一時フォルダへコピーし、そこで `clean package` を実行します。`target` と `dist` を通常のMaven実行と共有しないため、並行するテストや `clean` によるクラス消失・生成物上書きを防ぎます。文字コードの正規化もコピー側だけで実行します。`Skip` でも本体のコンパイルとJAR生成は省略せず、過去のclassやリソースを再利用しません。

配置するJARは今回の一時フォルダから取得し、配置成功後はそのフォルダだけを削除します。元のPluginプロジェクトにある `target`、`temp`、`dist` は保持します。ビルド失敗時は、ログに表示した `Plugin build workspace` の場所にソース・生成物・Surefireレポートを残します。`plugin.buildOutputPath` は `plugin.projectPath` 配下の出力先を指定します。

再開時も同じテストモード・指定を使用してください。テスト条件は実行記録の識別値に含め、条件が異なる未完了runの再利用を拒否します。未指定と従来の既定値を明示した場合は同じ条件として扱い、従来の未完了runも再開できます。

## 失敗時

Control管理下で、同じ設定・予約・runのまま外部処理前の隔離Build失敗を確認できる場合は、新しい予約を取得せず同じrunでビルドを再試行します。停止条件と排他ロックを再確認し、旧状態・進行記録・ログをrun内の `build-retries/attempt-<ID>` へハッシュ照合して保全します。再試行準備中に中断しても同じrunで再開できます。Database/Files段階、実行中のまま中断、設定や予約の不一致、seed・公開・移行・起動の証拠がある状態は自動再試行しません。

同じバッチ・設定・モードで再実行すると、未完了の実行記録を再利用します。配置成功後なら再ビルド・再コピーをせず、seed/起動待ち/パッチ公開から再開します。起動待ち中はDevを起動してください。公開要求の結果が不明でも、同じ設定世代への再送では同じパッチ番号を使います。

配布失敗時も01自身が復旧を案内します。新形式の `dev-deployment-progress.json` は子backendが `run.lock` を保持してBuild/Database/Files/Completedの境界を副作用の前に保存します。Legacyで隔離Build段階の失敗をbackendが記録済みなら旧runを自動保全して新規実行、Database/Files・実行中のまま中断・旧形式の記録なら確認メニューを表示します。強制終了では外部ビルド子プロセスが残る可能性があるため、Running記録だけで自動再ビルドしません。DB・配置先の整合と必要な外部復旧を確認済みの場合だけ `1` と `RECOVERY-CHECKED` を入力してください。この操作はDBや配置先のロールバックではありません。非対話では `-Recovery Restart -RecoveryChecked -ServersStopped -AdmissionClosed` を使用します。世代移行記録のあるrunは破棄できません。詳細は [失敗時の自己復旧](../maintenance/README.md#失敗時の自己復旧) を参照してください。

公開待ちでDevを再起動した場合、確認済みと同じ設定世代でreadyなら新しい起動sessionへ引き継いで公開を再試行します。旧版の一括更新が未完了のrunは17に同じ設定・runを指定して完了させてから01を再開します。詳細は [メンテナンス運用](../maintenance/README.md#メンテナンス運用) を参照してください。

配置完了を確認できない場合は上記の自己復旧判定に従います。DB更新・外部配置が始まっている、またはその有無が不明な実行は、状態確認なしにやり直しません。旧Devデプロイ処理自体をトランザクション化したわけではありません。同じrunを継続する場合の設定・モード変更は拒否し、安全な新規実行または確認済みRestartへ切り替えた場合だけ新しい設定を使用します。

01/02/03の入口では今回の設定ファイル・更新モード・テスト条件を表示します。前回が `PublishingPatch`、または配置完了・公開未着手を確認できない `WaitingForStartup` で設定識別値が異なる場合は、前回の進行状態・マスタ投入状態・記録先・両方の識別値を表示して停止します。配布失敗時の復旧経路では、その復旧判定に応じた案内を表示します。これは実行中プロセスの検出ではありません。識別値は設定ファイル全体のハッシュ、更新モード、テスト条件から作るため、識別値だけでは変更項目を特定できません。

前回が `AwaitingDeployment` かつseed未着手で、配布・公開・移行の記録もない場合だけ、ロック取得後に前回の記録を保全して新しい設定で開始します。状態不明や矛盾した記録がある場合は自動で切り替えません。Devの `WaitingForStartup` で配置完了マーカーがあり、公開結果・移行記録・配布journalがない場合は、設定不一致時に01/02/03自身が復旧メニューを表示します。元の条件で再開する場合は中止し、設定・モード・テスト条件を戻してDevを起動します。新しい条件でやり直す場合は、DB・配置先の状態と前回の子プロセス終了を確認し、Dev・自動書込みを停止して入場制限したうえで `1`、続いて `RECOVERY-CHECKED` を入力してください。旧記録を保全して新しいrunで再配布・必要なseedを実行します。この操作はDBや配置先のロールバックではありません。非対話では同じ入口に `-Recovery Restart -RecoveryChecked -ServersStopped -AdmissionClosed` を指定します。`-ServerAlreadyRunning` は外してください。`PublishingPatch` は公開要求の結果が不明な可能性があるため、この切り替えを認めず、元の設定・モード・テスト条件で再開します。配布失敗時の復旧判定は従来どおりです。

実行記録、ログ、履歴は自動削除しません。設定完了後の日常操作に `-Phase` や `-RunDirectory` の入力は不要です。プレイヤーの取得内容を維持できない変更では、ログイン時の更新を保留して管理対応が必要になります。17も破壊的な変更や非空legacyの承認は自動で行いません。

## 検証

`tests/dev-update-entry.tests.ps1` は一時fixtureの偽ビルドbackendと、実際の配布処理を使って入口のモード・対象制限を検証します。起動待ち・seed・失敗再開は `../maintenance/tests/update-workflow.tests.ps1` のmock APIで検証します。本番環境は操作しません。

`tests/plugin-test-selection.tests.ps1` は一時fixtureと偽Mavenを使って実backendの3モード・引数・既定値・再開条件を検証し、元のテストクラス・JAR・ソースを保持したまま隔離ビルドすることと、ビルド失敗時に古いJARを配置しないことを確認します。両テストともPowerShell 7で実行し、Mavenの実ビルドや実サーバーへの配置は行いません。
