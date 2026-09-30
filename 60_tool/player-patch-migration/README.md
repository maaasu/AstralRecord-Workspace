# プレイヤーパッチの手動一括更新

`../17-player-patch-migration.bat` は、大きな仕様変更や復旧時に運用者が明示して使う入口です。通常の01/02/03/16は配置とパッチ公開まで行い、プレイヤーはログイン時に更新されます。17はファイル配置・サーバー停止起動・パッチ公開を行いません。PowerShell 7が必要です。

## 実行手順

1. `player-patch-migration.example.json` を `player-patch-migration.local.json` にコピーし、接続先、認証、対象サーバーと対象範囲を設定して `enabled=true` にする。
2. 対象サーバーへ同じ設定を配置し、01/16などでパッチを公開する。
3. プレイヤーをログアウトさせて保存完了を待ち、入場制限を維持する。対象サーバー自体は起動したままにする。
4. 下記のPreviewで確認し、同じ設定・実行記録先のCommitで反映する。

```powershell
# 事前確認のみ（Phaseを省略してもPreview）
.\60_tool\17-player-patch-migration.bat -Phase Preview -AdmissionClosed -RunDirectory E:\AstralRecord-Maintenance\player-patch-20260930
# 上で確認した同じ対象へ反映
.\60_tool\17-player-patch-migration.bat -Phase Commit -AdmissionClosed -RunDirectory E:\AstralRecord-Maintenance\player-patch-20260930
```

通常起動ではCommitになりません。`-AdmissionClosed` はログアウトと入場制限を運用者が確認した宣言です。APIも接続中の対象を拒否します。途中で通信が失敗した場合は、**同じ設定ファイルと同じRunDirectory**を指定して再実行してください。成功済みの結果と要求ごとのIDを保持し、応答が不明な要求も同じIDで再送します。起動sessionや設定世代が変わると停止するため、作業中の再起動・再読込は避けてください。

## 対象範囲と接続設定

認証・HTTPSの制約は [通常更新の接続設定](../maintenance/README.md#パッチ公開の接続設定) と共通です。共通APIキーと専用migrationキーを使い、秘密情報はログや実行記録へ保存しません。

- `serverIds`: 更新先の実際のサーバーID一覧。全てreadyで同じ設定世代でなければ停止する。利用者をそのサーバーに限定する設定ではない。
- `scope=AllCandidates`: DB全体の更新候補を対象にする。
- `scope=ExplicitAccounts`: `accountIds` のキャラクターUUID、または `accountUserIds` のユーザーUUID配下の全キャラクターを対象にする。少なくともどちらかを指定する。
- `accountIds`: `AllCandidates` でも空配列を指定する。ユーザー本人のUUIDとは別のID。
- `accountUserIds`: `ExplicitAccounts` でだけ指定できる。新規runで一覧を確定し、再試行では対象を増減しない。

`-ConfigPath` には既存のmaintenance設定、またはdeploy-debug設定も指定できます。その場合も `migration`（Devでは `devWorkflow.migration`）に `scope` と対象情報が必要です。新規の通常更新設定にはこれらを用意する必要はありません。

以前の01/16で開始済みの一括更新は、当時の設定と画面に表示された実際のrunパスを17に渡して再開します。記録やCommit開始マーカーを削除しないでください。17で完了後、元の01/16を再実行すると配布済みの内容を再コピーせずにパッチ公開へ進みます。

## 更新できる範囲

取得済みノードを全て維持する更新だけを行います。全候補を固定し、100件ずつ事前確認し、全件が成功した場合だけCommitへ進みます。Commitだけで開始した場合も事前確認を通ります。

取得コスト・条件・経路が新しい設定に合わない場合は停止します。**ノード削除、消費ポイントの職業付替え、世代情報のない既存データの承認は自動処理しません。** 大きな仕様変更でも、この保持更新で対応できない場合は既存の管理APIで変更内容を明示する手順が必要です。世代情報がなく空のデータはログイン時の初期登録に任せます。

対応APIでは、成功したキャラクターについて未反映のWeb編集要求を同じ保存処理で取消します。更新を拒否されたキャラクターの要求は取消しません。新しいデータを古い設定へ戻すための自動手段ではありません。

実行先には対象・要求ID・結果と診断ログが保存されます。配置先サーバーや配布元と重なる場所は指定できません。同時実行はrun単位で拒否します。旧配置runを再利用する場合は配置完了状態を要求し、Commit開始後はファイルだけを戻すRestoreを引き続き拒否します。

## 検証

`tests/entry.tests.ps1` と `../maintenance/tests/migration.tests.ps1` は一時ファイルと偽のAPI応答だけを使います。実サーバーへ接続しません。
