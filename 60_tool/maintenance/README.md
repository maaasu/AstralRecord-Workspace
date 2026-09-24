# メンテナンス配布とスキルツリー世代移行

`../16-maintenance.bat` が本番更新の入口です。PowerShell 7 (`pwsh`) が必要です。既定の `Workflow` は配布・seed・手動起動の案内と待機・世代移行まで一度の実行で進めます。停止・起動と入場制限の解除は運用者が行います。変更なしで確認する場合だけ `-Phase Plan` を使用します。

## 初回設定

`maintenance.example.json` を `maintenance.local.json` としてコピーし、配布元・配布先の **Minecraftサーバールート** の絶対パスを設定してください。UNCパスも使用できます。ローカル設定はGit管理対象外です。サンプルは全機能無効です。

- `servers[].enabled`: 配布対象に含めるサーバー。有効なら `rootPath` は必須です。
- `distributions[].enabled`: 個々の配布を有効化します。無効な配布先サーバーは明示的にSKIP表示します。有効な配布のsourceが無効ならエラーです。
- `source` / `targets`: `servers[].id`。Dev→Build/Channel と Build→Dev/Channel を設定できます。
- `relativePath`: サーバールートからの相対パス。配布元・配布先は同じ相対配置を使用します。ワイルドカード、親への遡り、リンク・ジャンクションは拒否します。

| kind | relativePath | 動作 |
|---|---|---|
| Jar | `plugins/AstralRecord.jar` など単一JAR | Devのビルド済みJARを全配布先へ同一内容で配置 |
| Placement | `plugins/AstralRecord/mob_spawners.yml` | Mobスポナー配置を置換 |
| Placement | `plugins/AstralRecord/gathering_spawners.yml` | 採集スポナー配置を置換 |
| Placement | `plugins/AstralRecord/npc_locations.yml` | NPC配置を置換 |
| Placement | `plugins/AstralRecord/waystones.yml` | Waystone/Teleporter定義を置換 |
| Filebase | `filebase` | スキルツリーJSONを含む専用Filebaseディレクトリを置換 |
| World | `plugins/AstralRecord/worlds/hub/skill_tree` など | Buildのワールドをディレクトリ単位で置換 |

配置ファイルはPluginの `getDataFolder()` 直下が保存先です。`config.yml`、APIキー、server ID、ログ、プレイヤーDB、その他のPluginデータは一括配布しません。JAR名は安定した配置名に揃え、古い別名JARをpluginsに残さないでください。配置ファイル内のワールド名・座標が配布先と一致する必要があります。

Worldは `level.dat` のある実ワールドだけを指定します。Buildを正本とするワールドだけを列挙してください。配布先の地形・建築・エンティティはBuildの内容に置換されます。`uid.dat` と `playerdata` / `stats` / `advancements` は配布先のものを保持し、Buildからはコピーしません。`session.lock` はコピーせず、起動時に生成させます。新しい配布先はワールドUUIDも新規生成させます。一時ダンジョンなどは対象にしません。

Filebase配布は各serverのローカル配置が対象です。APIが別の共有Filebaseを参照する場合は、その配置先も同じリリースに揃えてください。Workflowは `seedMasterData=true` なら配置後にAPIのseedも実行します。共有APIをDevとChannelで使う場合、APIのマスタ更新自体も影響を共有します。Channelをメンテナンスまで固定するには、その `masterData.autoReload.enabled` とFilebase/APIの分離方針も合わせて設定します。このツールは各serverの設定を書き換えません。

## メンテナンス運用

1. 入場制限をかけ、保存完了後に対象チャンネルを停止します。コピー元のDev/Build、更新対象のLobby/Proxyも必要に応じ停止し、自動ビルド配置などの書込みを止めます。
2. `16-maintenance.bat` を一度実行し、ワールド配布とネットワークプラグイン配布をそれぞれ選択します（`1`＝配布する、`2`＝配布しない）。その後、停止・入場制限の確認に応答します。
3. 設定した配布とseedが完了すると、バッチが起動案内を表示して自動待機します。
4. サーバーを起動します。入場制限は維持します。
5. 対象チャンネルが新しい起動sessionでreadyかつ同一世代になったことを確認し、バッチが対象アカウントを移行します。
6. バッチの完了表示後に接続制限を解除します。Lobby/Proxy自体の起動確認は管理画面で行ってください。

```powershell
# 任意の確認（変更なし）
.\60_tool\16-maintenance.bat -Phase Plan
# 通常の実行
.\60_tool\16-maintenance.bat
# 対話なしで配布対象を指定（停止・入場制限は別途確認）
.\60_tool\16-maintenance.bat -WorldCopy Skip -NetworkPlugins Include
# ワールド・ネットワークを除いたプランの確認
.\60_tool\16-maintenance.bat -Phase Plan -WorldCopy Skip -NetworkPlugins Skip
```

ワールドとネットワークの選択は独立です。ワールドを省略すると `kind=World` のコピー・ハッシュ検査を行いません。ネットワークを省略すると `networkPlugins` に設定したLobby／Proxy／Geyser拡張のJARを配置しません。Devの通常プラグインJAR・配置設定・Filebase（有効な場合）は従来どおり対象です。ネットワーク選択は設定済み項目を一括で切り替え、ビルドは行いません。

選択は実行フォルダーの `deployment-selection.json` に保存し、子Deployへ引き継ぎます。途中再開では聞き直さず保存値を使用し、同じrunの選択変更は拒否します。新しいWorkflow状態にも選択のfingerprintを保持します。既存の選択記録がない旧runは従来どおり両方Includeとして扱います。設定JSONや既存runの記録を手で書き換えないでください。

自動実行では `-WorldCopy Include|Skip` と `-NetworkPlugins Include|Skip` を両方指定してください（新規Workflowで未指定なら停止）。`-Phase Plan` と詳細 `-Phase Deploy` は互換性のため新規時の未指定をIncludeとして扱います。Restore／移行フェーズでは選択引数を受け付けず、実際の配布journalから処理します。省略しても残る配布対象・配布元の停止、自動書込み停止、入場制限は必要です。全ての配布が無効になる選択ではDeployは従来どおり停止します。

`workflow.runRoot` が空なら設定JSONと同じ場所の `runs` へ実行記録を自動作成します。`startupTimeoutSeconds` / `pollIntervalSeconds` は起動待ち時間と確認間隔、`seedMasterData` は配布後のAPI diff seedです。起動案内はseed成功後に表示します。共有API側のFilebaseが今回のリリースと一致していることが前提です。起動確認対象は `migration.serverIds` なので、今回更新する全チャンネルの実server IDを列挙してください。

配置完了後の失敗は同じバッチで続きから再開します。設定・対象や移行途中の起動sessionを変更しないでください。配布失敗時は再実行時の自己復旧判定へ進みます。実行記録を直接削除・編集する必要はありません。部分配布は復旧成功前に新規実行へ進みません。

### 失敗時の自己復旧

01/16を再実行すると、排他ロックを取得し、前回の記録から次を判定します。

| 状態 | 動作 |
|---|---|
| 01の新形式記録で、隔離されたBuild段階の失敗をbackendが記録済み | DB更新・外部配置前として、旧記録を保全して自動で新規実行 |
| 16の全項目がPending/Prepared/Unchangedで退避先も未使用 | 元の配置先は未置換。準備データを残し自動で新規実行 |
| 配置完了の証拠あり | 再配布せずseed・起動待ち・移行から再開 |
| 16が部分配布済み | `1: バックアップから復旧して新規実行 / 2: 中止` を表示。全対象停止・入場制限の確認後に既存Restoreを実行し、成功時だけ新規実行 |
| 16のRestore完了済み | 旧記録を保全して自動で新規実行 |
| 01のDatabase/Files段階・Build隔離未確認・実行中のまま中断、旧形式・欠損した進行記録 | 自動でやり直さない。DB・配置先の適用状況と必要な外部復旧、前回の子プロセス終了を運用者が確認後、`1`と`RECOVERY-CHECKED`の入力で新規実行へ切替 |
| 世代移行のmarker/state/result/対象記録あり | 記録を破棄しない。同じrunの移行再開・個別調査を行う |

旧runフォルダー・ログ・バックアップ・準備データは削除しません。`active-run.json`だけを旧run内の `active-run.before-recovery-<ID>.json` へ退避し、理由を隣接JSONに保存します。新しいrunには通常の停止・入場制限確認と配布対象選択を適用します。新規実行への切替は、失敗原因の自動修正を意味しません。

無人実行では、不明な状態を勝手に確認済みにしません。`-ServersStopped -AdmissionClosed`を両方指定した呼出しも、未承認の復旧については対話待ちせず、必要な引数を表示して停止します。運用者が確認済みの場合だけ、次の引数を明示します。

```powershell
# 01: DB・配置先の整合/必要な外部復旧・子プロセス終了を確認済みの場合だけ
.\60_tool\01-deploy-debug.bat -Recovery Restart -RecoveryChecked -ServersStopped -AdmissionClosed
# 16: 部分配布をバックアップから復元して新規実行（全対象停止が前提）
.\60_tool\16-maintenance.bat -Recovery Restore -ServersStopped -AdmissionClosed -WorldCopy Include -NetworkPlugins Include
```

`-RecoveryChecked`は検証を自動で代行するスイッチではなく、運用者の確認宣言です。DB処理の一部はSQL確定後に履歴・スキーマ検査を行うため、失敗ログだけで未適用と判断しないでください。設定が変わった状態のファイル復元は元の設定へ戻してから行います。復旧が失敗した場合や未知のjournal形式では停止し、旧runを保持します。

配置後にサーバーを再起動した場合、01/16のWorkflowは同じ定義世代でreadyになっていることを確認し、保存済み移行記録の全件がCOMMIT未送信（`CommitStatus=PENDING`かつ結果なし）の場合だけ起動sessionを引き継ぎます。設定・対象server集合・移行先世代とsnapshot・全accountの元世代/version/node baseline・元snapshotを再照合し、起動情報をもう一度確認してから、同じoperation IDで全件PREVIEWをやり直します。旧移行記録は `.before-runtime-refresh-<ID>` として保全します。詳細MigratePreview/MigrateCommitではこの自動引継ぎを有効にしません。

`REQUESTED`（送信結果不明）、`APPLIED`、COMMIT拒否などが1件でもある状態から、さらに別sessionへは自動移行しません。世代変更・保存状態変更・runtime未応答・移行記録欠損も自動解除しません。先に移行記録のsessionを保存し、Workflow側は成功後に追従するため、途中中断で両記録のsessionが異なっていても、移行記録が既に採用した同じ起動sessionでは既存の再試行規則を維持します。COMMIT開始マーカーを消したり、operation IDを振り直したりはしません。

`-ServersStopped` / `-AdmissionClosed` は運用者による確認宣言です。プロセス停止や入場制御の自動検知ではありません。全員オフラインでもワールドは書込みされるため、配布時はサーバーを停止してください。JARを読むDevも、配置ファイルを書き込むPluginも配布元の一貫性確保の対象です。

配布時は全ソースを実行記録先へ先に固定し、SHA-256でコピー中の変更を検査します。各配布先へ準備後、旧ファイル/ディレクトリを各サーバーの `.astral-maintenance/<run ID>/<index>/backup` に移動して新しい内容へ交換します。古いJSONやregionが残らない置換方式です。全ソース・新旧ワールドを保持する空き容量が必要です。実行途中に失敗した場合は非0終了し、`deployment.json` に途中状態を残します。失敗時に入場再開してはいけません。

同じ実行記録先でDeployを繰り返すことは拒否します。部分配布はRestoreで戻してから別の記録先でやり直します。サーバーごとの排他ファイルにより、このツール同士の同時配布を拒否します。他の自動デプロイツールとは排他共有しないため停止してください。

同じRunDirectoryでは設定ファイルのSHA-256も固定します。配布後にmigrationを有効化するのではなく、最初から今回の配布・移行の設定を揃えてください。設定を変更した場合は元の内容へ戻して再試行します。

配布先が既に同じ内容なら、SHA-256とファイル/ディレクトリ一覧で一致を確認し、`UNCHANGED` と表示してステージ作成・コピー・バックアップ・置換を省略します。Worldの比較からはサーバー固有の `uid.dat` / `session.lock` / `playerdata` / `stats` / `advancements` を除き、そのまま保持します。日時やサイズだけの判定ではありません。適用段階でも共有内容のSHA-256を再検査し、途中で変化したら停止します。全体の `Deployed` は変更項目の適用と変更なし項目の再検証がすべて成功した場合だけ保存します。Restoreは `Unchanged` 項目に手を加えません。

変更されたWorldも、プレイヤーデータ保持前の重複した全体ハッシュ検査を、リンク/ジャンクションのメタデータ検査に置き換えています。置換直前の元データ検査・ステージ検査・配置後検査・復旧時のバックアップ検査は維持します。追加設定は不要です。配布元のスナップショット作成は従来どおり行うため、未変更項目が多い更新で特に転送量を削減できます。初回や全ワールド変更時の所要時間短縮は限定的で、実環境の時間はネットワークとデータ量に依存します。

## 世代移行設定

`migration.enabled=true` にして、次を設定します。

- `baseUrl`: APIのHTTPS URL。ローカル試験のloopbackのみHTTPを許可します。既定では通常の証明書検証を維持します。
- `allowPrivateApiInsecureTls`（既定false）: 閉域APIで証明書検証を省略する明示設定。true時はHTTPSのRFC1918 IPv4またはループバックIP直指定だけを許可し、DNS名・公開IP・リンクローカル・HTTPは拒否する。設定した同一origin以外への適用も拒否し、プロキシとリダイレクトを使わない。通常の証明書検証とOSの信頼ストアには影響しない。
- `apiKeyEnvironmentVariable` / `migrationKeyEnvironmentVariable`: 共通APIキーと専用migrationキーを格納した環境変数名。
- `apiSettingsPath`（任意）: 既存APIの `appsettings.json` の絶対パス。指定時は環境変数より優先し、共通APIキーと専用migrationキーをファイルから毎回読み取る。実値は実行記録に保存しない。
- `serverIds`: APIへ登録される実際の `api.serverId` 一覧。全てreadyで同一世代でなければ停止します。配布用のIDと同名である必要はありません。
- `scope=ExplicitAccounts`: `accountIds` のアカウントUUID、または `accountUserIds` のユーザー配下を対象にする。どちらかを1件以上指定する。`accountIds` はMinecraftユーザーUUIDではありません。
- `accountUserIds`（任意）: `ExplicitAccounts` で指定ユーザー配下の全キャラクターを対象にするUUID一覧。指定時は `accountIds` を空にできる。新規runで一覧を解決し、途中再試行では対象を増減しない。APIが別ユーザーのアカウントを返した場合は拒否する。
- `scope=AllCandidates`: DB全体の世代不一致候補が対象。本番メンテナンス用です。**serverIdsは移行先の指定であり、候補をそのサーバーの利用者に限定しません。**

候補は全ページを取得して固定し、削除ノードなし・消費元付替えなしの保持移行を最大100件ずつPREVIEWします。全件が成功した場合にCOMMITへ進めます。Commit単独で開始してもpreview検証を経由します。`operationId` と入力は実行記録先に保存し、通信失敗時も **同じRunDirectory** で再実行します。成功済みを維持し、確定結果不明の要求も同じoperation IDで再送します。対象・移行先世代・保存状態の競合は無条件で上書きしません。

移行先APIが対応している場合、未適用のWebスキルツリー編集操作（`PENDING_ONLINE`、`PENDING_OFFLINE`、`CLAIMED`）はPREVIEWでは保持し、対象accountのCOMMIT成功時にAPIが同じDBトランザクションで`CANCELED`にします。バッチからユーザー本人の取消操作は不要です。offline・保存状態・定義などの検証でCOMMITが拒否されたaccountの編集操作は取消されません。この動作を使う前に、対応版APIを配置してください。

候補が0件なら変更なしで成功します。ExplicitAccountsに指定したアカウントが候補にない場合は保存状態を照会し、既に同世代、または世代NULL・空状態ならSKIP表示します。存在しないアカウントやその他の不一致はエラーです。`skilltree-migration-result.json` に成功/失敗とアカウント別の状態を残します。`REQUESTED` は結果不明であり、同じRunDirectoryのMigrateCommitで再送します。詳細MigratePreview/MigrateCommitは起動sessionや世代が変わると停止します。01/16のWorkflowだけは上記の同一定義・COMMIT未送信条件で再起動を引き継ぎますが、処理中の再起動・再リロードは避けてください。

保持ノードのコスト/条件や経路などがAPIの検証を通らない場合は移行しません。非空legacyのbaseline承認、ノード削除、CP消費元補完は自動で判断せず、既存の管理API手順で扱います。世代NULLかつ空状態は参加時の自動bindに任せます。世代付きの空状態は世代移行の対象です。

## Devだけを更新する場合

通常は [01のDev更新](../deploy-debug/README.md) を使用してください。Devを停止し、01を実行し、起動案内後にDevを起動すると、開発用アカウントの移行まで完了します。毎回16や移行用JSONを選び直す必要はありません。

移行だけを行う詳細操作では、別の `dev.local.json` を用意し、配布を全て無効、`migration.serverIds` をDevだけ、`scope=ExplicitAccounts` として開発用アカウントUUIDを列挙できます。Devユーザーを退出させて保存完了と新世代のロードを確認します。

```powershell
.\60_tool\16-maintenance.bat -ConfigPath E:\AstralRecord-Workspace\60_tool\maintenance\dev.local.json -Phase MigrateCommit -AdmissionClosed -RunDirectory E:\AstralRecord-Maintenance\dev-20260922-01
```

配布を伴わない移行だけでも実行できます。共有DBの同じアカウントをDevの世代へ移すと、旧Channelに参加できなくなります。開発アカウントの分離、または開発DBの分離を行ってください。

## 復旧と記録

実行フォルダーには `diagnostics-<役割>-<PID>-<一意ID>.jsonl` を自動保存します（追加設定不要）。Workflow・Entry・Deployのログは分かれており、再実行しても過去ログを上書きしません。親の `child.launch` / `child.exit` と子のPIDを、時刻と同じ実行フォルダーで照合してください。`distribution.log` は従来の画面出力を追記保存します。

診断ログはUTC時刻、PID、処理段階、配布元/先、項目番号、コピー・ファイルごとのSHA-256検査の開始/完了、状態保存、子プロセス終了コードを記録します。例外は型・内部例外の型・HResult・カテゴリ・スクリプト行/列・スタックを記録します。秘密情報を避けるため、例外メッセージ全文、実行行本文、設定内容、環境変数、HTTPのヘッダー/本文は保存しません。パスは記録されるため、ログは運用者だけが読める場所で保管してください。

各イベントをその場でファイルへ追記して閉じるため、画面を閉じた場合も直前までの処理を追えます。ただし強制終了・OS停止・電源断では終了イベントや原因そのものを記録できない場合があります。開始に対応する完了がない処理とWindowsイベントログを照合してください。ログ書込み失敗は警告し、配布のトランザクション状態は変更しません。ログは実行フォルダー確定後から保存され、設定読込みやそれ以前の入力検証エラーは画面出力を確認してください。過去の中断原因を遡って復元する機能や未完了状態の解除機能ではありません。

世代COMMIT開始前であれば、実行中の待機バッチを終了し、関係サーバーを再び停止してファイルを戻せます。起動待ち・移行中は同じrunの詳細操作を排他で拒否します。復元済みのrunから起動待ちや移行を再開することも拒否します。

```powershell
.\60_tool\16-maintenance.bat -Phase Restore -ServersStopped -RunDirectory E:\AstralRecord-Maintenance\release-20260922
```

Restoreは適用済み/途中の項目を逆順で復元し、新しい側も `displaced` として残します。COMMIT開始後は、実際にDBへ届いたか不明な場合もファイルだけのRestoreを拒否します。移行結果とDBを確認して別途復旧してください。APIのマスタ更新やDBバックアップ/復元はこのファイルRestoreの対象外です。

`-Phase Deploy` / `MigratePreview` / `MigrateCommit` / `Restore` と `-RunDirectory` は詳細操作用として維持します。通常のWorkflowではRunDirectoryを指定しません。詳細操作の記録は自動Workflowと混ぜず、ログに表示された実際のrunパスを指定してください。

## ネットワークプラグインの任意配布（ビルドなし）

`networkPlugins.destinations[].deployDirectory` に値がある項目だけ配布します。空欄の項目はSKIPです。`networkPlugins` 自体を省略しても構いません。

| artifact | 配置先ディレクトリ |
|---|---|
| `AstralRecordLobby.jar` | Lobbyサーバーの `plugins` |
| `AstralRecordProxy.jar` | Proxyサーバーの `plugins` |
| `AstralRecordGeyserExtension.jar` | Proxy等の `plugins/Geyser-Velocity/extensions` |

`sourceDirectory` は空なら、このツールと同じチェックアウトの `60_tool/network-plugin-build/output` です。別の出力フォルダを使う場合だけ絶対パスで指定します。設定した配置先または出力JARがない場合は配布開始前にエラーにします。ビルド、12バッチの呼び出し、依存JARのダウンロードは行いません。Geyser本体ではなく、ネットワークビルドが生成するAstralRecordのGeyser拡張が対象です。

各JARは従来の配布と同じスナップショット・バックアップ・復元対象です。他のPluginやconfigは変更しません。複数のLobby/Proxyへ配る場合は同じartifactの行を追加して配置先を指定します。起動確認APIはチャンネルのスキルツリーruntimeだけを確認するため、Lobby/Proxy/Geyserの起動成否まで自動検証するものではありません。

実行記録・バックアップは自動削除しません。設定や移行要求のファイルは信頼できる運用者だけが変更できる場所で保管してください。運用確認後の保持期間・削除は別途管理します。本バッチ作成時のテストは一時fixtureとmock APIだけで行い、本番配布・世代移行は行いません。

## 検証

```powershell
pwsh -NoProfile -File .\60_tool\maintenance\tests\distribution.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\entry.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\migration.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\update-workflow.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\network-distribution.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\diagnostics.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\unchanged-distribution.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\deployment-selection.tests.ps1
```

対象外はサーバープロセス管理、Proxyの入場制御、API/Webのデプロイ、DBスキーマ変更です。
