# メンテナンス配布とスキルツリー世代移行

`../16-maintenance.bat` が共通入口です。PowerShell 7 (`pwsh`) が必要です。既定は書込みもAPI通信も行わない `Plan` です。停止・起動と入場制御は既存のサーバー管理方法で行い、配布と起動後の移行を同じ実行記録ディレクトリでつなぎます。このツール自体はサーバーを停止・起動しません。

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

FilebaseはAPIが参照する配置先の同期・Seederとは別です。必要な場合はAPI側も同じリリースのFilebaseを配布し、既存のseed手順を実行してからPlugin起動/マスタ反映・世代確認へ進んでください。共有APIをDevとChannelで使う場合、APIのマスタ更新自体も影響を共有します。Channelをメンテナンスまで固定するには、その `masterData.autoReload.enabled` とFilebase/APIの分離方針も合わせて設定します。このツールは各serverの設定を書き換えません。

## メンテナンス運用

以下はワークスペース直下からの例です。`E:\AstralRecord-Maintenance\release-20260922` は実行ごとに変えるローカル記録先の例で、全サーバールートから独立させます。

```powershell
# 1. 設定と対象を表示（変更なし）
.\60_tool\16-maintenance.bat -Phase Plan

# 2. 入場停止、全員退出と保存完了、関係サーバー停止後に実行
# Dev自動ビルドの配置処理や自動再起動も一時停止する
.\60_tool\16-maintenance.bat -Phase Deploy -ServersStopped -RunDirectory E:\AstralRecord-Maintenance\release-20260922

# 3. APIマスタを必要に応じ反映し、サーバーを起動。入場停止は維持する
# キーは環境変数に安全に設定しておく。設定JSONやコマンド例に実値を保存しない
.\60_tool\16-maintenance.bat -Phase MigratePreview -AdmissionClosed -RunDirectory E:\AstralRecord-Maintenance\release-20260922
.\60_tool\16-maintenance.bat -Phase MigrateCommit -AdmissionClosed -RunDirectory E:\AstralRecord-Maintenance\release-20260922
# 4. 全対象成功を確認して入場再開
```

`-ServersStopped` / `-AdmissionClosed` は運用者による確認宣言です。プロセス停止や入場制御の自動検知ではありません。全員オフラインでもワールドは書込みされるため、配布時はサーバーを停止してください。JARを読むDevも、配置ファイルを書き込むPluginも配布元の一貫性確保の対象です。

配布時は全ソースを実行記録先へ先に固定し、SHA-256でコピー中の変更を検査します。各配布先へ準備後、旧ファイル/ディレクトリを各サーバーの `.astral-maintenance/<run ID>/<index>/backup` に移動して新しい内容へ交換します。古いJSONやregionが残らない置換方式です。全ソース・新旧ワールドを保持する空き容量が必要です。実行途中に失敗した場合は非0終了し、`deployment.json` に途中状態を残します。失敗時に入場再開してはいけません。

同じ実行記録先でDeployを繰り返すことは拒否します。部分配布はRestoreで戻してから別の記録先でやり直します。サーバーごとの排他ファイルにより、このツール同士の同時配布を拒否します。他の自動デプロイツールとは排他共有しないため停止してください。

同じRunDirectoryでは設定ファイルのSHA-256も固定します。配布後にmigrationを有効化するのではなく、最初から今回の配布・移行の設定を揃えてください。設定を変更した場合は元の内容へ戻して再試行します。

## 世代移行設定

`migration.enabled=true` にして、次を設定します。

- `baseUrl`: APIのHTTPS URL。ローカル試験のloopbackのみHTTPを許可します。証明書検証は無効にしません。
- `apiKeyEnvironmentVariable` / `migrationKeyEnvironmentVariable`: 共通APIキーと専用migrationキーを格納した環境変数名。
- `serverIds`: APIへ登録される実際の `api.serverId` 一覧。全てreadyで同一世代でなければ停止します。配布用のIDと同名である必要はありません。
- `scope=ExplicitAccounts`: `accountIds` のアカウントUUIDだけが対象。空配列は禁止します。MinecraftユーザーUUIDではありません。
- `scope=AllCandidates`: DB全体の世代不一致候補が対象。本番メンテナンス用です。**serverIdsは移行先の指定であり、候補をそのサーバーの利用者に限定しません。**

候補は全ページを取得して固定し、削除ノードなし・消費元付替えなしの保持移行を最大100件ずつPREVIEWします。全件が成功した場合にCOMMITへ進めます。Commit単独で開始してもpreview検証を経由します。`operationId` と入力は実行記録先に保存し、通信失敗時も **同じRunDirectory** で再実行します。成功済みを維持し、確定結果不明の要求も同じoperation IDで再送します。対象・移行先世代・保存状態の競合は無条件で上書きしません。

候補が0件なら変更なしで成功します。ExplicitAccountsに指定したアカウントが候補にない場合は保存状態を照会し、既に同世代、または世代NULL・空状態ならSKIP表示します。存在しないアカウントやその他の不一致はエラーです。`skilltree-migration-result.json` に成功/失敗とアカウント別の状態を残します。`REQUESTED` は結果不明であり、同じRunDirectoryのMigrateCommitで再送します。移行先の起動sessionや世代が変わった場合は自動再開せず停止するので、処理中に再起動・再リロードしないでください。

保持ノードのコスト/条件や経路などがAPIの検証を通らない場合は移行しません。非空legacyのbaseline承認、ノード削除、CP消費元補完は自動で判断せず、既存の管理API手順で扱います。世代NULLかつ空状態は参加時の自動bindに任せます。世代付きの空状態は世代移行の対象です。

## Devだけを更新する場合

別の `dev.local.json` を用意し、配布を全て無効、`migration.serverIds` をDevだけ、`scope=ExplicitAccounts` として開発用アカウントUUIDを列挙します。Devユーザーを退出させて保存完了を確認し、`/masterdata reload` 後に実行します。

```powershell
.\60_tool\16-maintenance.bat -ConfigPath E:\AstralRecord-Workspace\60_tool\maintenance\dev.local.json -Phase MigrateCommit -AdmissionClosed -RunDirectory E:\AstralRecord-Maintenance\dev-20260922-01
```

配布を伴わない移行だけでも実行できます。共有DBの同じアカウントをDevの世代へ移すと、旧Channelに参加できなくなります。開発アカウントの分離、または開発DBの分離を行ってください。

## 復旧と記録

世代COMMIT開始前であれば、関係サーバーを再び停止してファイルを戻せます。

```powershell
.\60_tool\16-maintenance.bat -Phase Restore -ServersStopped -RunDirectory E:\AstralRecord-Maintenance\release-20260922
```

Restoreは適用済み/途中の項目を逆順で復元し、新しい側も `displaced` として残します。COMMIT開始後は、実際にDBへ届いたか不明な場合もファイルだけのRestoreを拒否します。移行結果とDBを確認して別途復旧してください。APIのマスタ更新やDBバックアップ/復元はこのファイルRestoreの対象外です。

実行記録・バックアップは自動削除しません。設定や移行要求のファイルは信頼できる運用者だけが変更できる場所で保管してください。運用確認後の保持期間・削除は別途管理します。本バッチ作成時のテストは一時fixtureとmock APIだけで行い、本番配布・世代移行は行いません。

## 検証

```powershell
pwsh -NoProfile -File .\60_tool\maintenance\tests\distribution.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\entry.tests.ps1
pwsh -NoProfile -File .\60_tool\maintenance\tests\migration.tests.ps1
```

対象外はサーバープロセス管理、Proxyの入場制御、API/Webのデプロイ、DBスキーマ変更です。
