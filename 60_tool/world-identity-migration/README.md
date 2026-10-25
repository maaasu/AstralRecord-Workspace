# エリヴァ超大陸のワールド識別子移行

`vanaheim_supercontinent` を `eriva_supercontinent` に変更する一回限りのオフライン移行です。`migrate.py` は既定で dry-run です。Filebase の配置、API seed、サーバー停止・起動は行いません。

## 対象

- Dev の `plugins/AstralRecord/worlds/overworld/vanaheim_supercontinent` を同階層の新名へ移す。
- `level.dat` と `level.dat_old` の NBT `LevelName` のパスだけを変更する。`uid.dat` と `xaeromap.txt` の ID は保持する。
- GDLauncher `MainInstance/instance/xaero` の `Multiplayer_mc.astralrecord.com` にある minimap と world-map の旧次元フォルダを新名へ移す。地図 ZIP の内部は変更しない。
- 同サーバーの minimap `config.txt` にある旧 `dimensionType` 一行を更新する。

Xaero の地図は `instance/config/xaero` ではなく `instance/xaero` に保存されています。旧 IP アドレスなど別サーバー名の地図は履歴として維持します。保存階層は [Xaero's World Map の配布ページ](https://modrinth.com/plugin/xaeros-world-map) に記載された `xaero/world-map/Multiplayer_[server_address]` と実ファイルを照合しました。

## 順序

1. プレイヤーの入場を閉じ、Dev と GDLauncher クライアントを正常終了する。Dev は Control Web の Server 操作から Stop する。Control CLI の単独 Stop は公開されていない。Dev の `session.lock` が解放され、ポート 25567 が閉じ、対象 Java プロセスが終了したことを確認する。
2. `40_filebase` の新 world ID と全参照を配布し、API の diff seed を行う計画を確定する。旧マスタのまま Dev を再起動しない。現行の共有 API 構成では `03-master-data-reload.bat` は Dev 以外の共有 RPG も停止を要求する。`01-deploy-debug.bat` は Dev のみを正常停止・起動して Filebase 同期と seed を含めるが、他のビルド・配置も行う。選んだ更新フローの前提条件を確認する。
3. 退避先の空き容量を確認する。移行スクリプトの dry-run でソース、衝突、NBT 値、Xaero 構造を確認する。
4. サーバーとクライアントが停止したまま `--apply` を実行する。バックアップは指定ディレクトリ内の時刻付きフォルダへ作成される。既存の新名フォルダがあれば停止する。
5. 新マスタの配置・API diff seed・API 応答確認を終えてから Dev を起動する。新フォルダの world がロードされ、座標・`uid.dat`・`xaeromap.txt` ID が維持されることを確認する。
6. クライアントを起動して同じ `mc.astralrecord.com` へ入り、地図の既知領域と waypoint を確認する。今回観測した旧次元の waypoint ファイルは空です。

実行例（`--apply` を付けない限り変更しない）:

```powershell
python 60_tool/world-identity-migration/migrate.py `
  --server-root 'C:\server\AstralRecord-Dev\10_Dev' `
  --client-root 'C:\Users\user\AppData\Roaming\gdlauncher_carbon\data\instances\MainInstance\instance' `
  --backup-root 'C:\AstralRecord-Backups'
```

dry-run と停止確認の後、同じ引数に `--apply` を追加する。バックアップ先ディレクトリは事前に作成し、Dev とクライアントの外に置く。適用時はポート、Java プロセス、主要ファイルの排他ロック、保存容量を再確認する。検査不能な Java プロセスがあれば安全側で停止する。

途中で失敗した場合、Dev とクライアントを再起動せず、表示されたバックアップと `manifest.json` を確認する。新旧フォルダを合成せず、必要に応じてバックアップ一式を元のパスへ戻す。`manifest.json` の `status` が `complete` になるまでは移行完了と扱わない。
