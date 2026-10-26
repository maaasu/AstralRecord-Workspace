# ワールド配置の管理とDev移行

スポナー・NPC・テレポーターの座標を専用SQL Server DBへ保存し、APIから取得します。正本はDevの `WorldPlacementDevDB`、チャンネル向けは `WorldPlacementDB` です。どちらもゲームDB初期化の対象外です。モブ・召喚ルールの定義は既存Filebase、テレポーターの解放履歴はプレイヤーDBに残ります。

下書き・公開版の一覧はありません。`revision` は競合検出用の現在値で、編集履歴・公開世代を保持しません。Devで編集・確認した現在の配置を、明示操作でチャンネル用DBへ同期します。ゲーム内の生成済みモブやクールダウンはDBへ保存しません。

## 専用API

共有APIに変更を加えずDevで検証する場合、APIを `WorldPlacements:Standalone=true` で起動します。配置とhealthだけを公開し、共有マスタのSeederや他機能のバックグラウンド処理は起動しません。専用ディレクトリへAPIをpublishし、以下をそのディレクトリの非公開 `appsettings.json` に設定します。キーの実値と接続文字列はGitへ追加しません。

- `ConnectionStrings:WorldPlacementDev`: 明示的に `WorldPlacementDevDB` を指定する接続文字列。
- `WorldPlacements:Standalone`: `true`。
- `WorldPlacements:WriteKey`: 編集専用キー。通常APIキーとは別の値。
- `ApiKey:Key`: この専用APIの読み取りキー。
- `Kestrel:Endpoints:Http:Url`: `http://127.0.0.1:5286`。

Devのみ移行する段階では、`ConnectionStrings:WorldPlacement` と `WorldPlacements:SyncKey` は設定しません。共有のAPI・マスタ・チャンネル配置には反映されません。別PCから読む構成へ変更する場合は、HTTPSと認証を設定します。

専用APIは `start-dev-api.ps1 -ApiDirectory <publish先>` で非表示起動します。サーバー起動前にこのAPIを起動してください。起動失敗時の詳細はpublish先の `placement-api.stdout.log` / `placement-api.stderr.log` で確認します。

## 初期化と移行

1. 現在のDevを正常停止し、3つの配置YAMLをバックアップします。Control管理下のDevは既存のControl gateで停止・保護します。
2. 専用APIの非公開設定を準備します。以下の専用ツールでDev DBだけを作成・初期化します。既存の配置行がある場合は保持します。通常のゲームDB再構築ツールは使用しません。

```powershell
dotnet run --project 60_tool/world-placement/WorldPlacementDbTool.csproj -- --settings C:/server/AstralRecord-Dev/WorldPlacementApi/appsettings.json --environment dev --schema 00_docs/40_Database設計書/table-definitions/WorldPlacementDB/init.sql
```

3. Python環境へ `requirements.txt` を導入し、指定Devの3ファイルだけをエクスポートします。入力は `plugins/AstralRecord/mob_spawners.yml`、`npc_locations.yml`、`waystones.yml`。全ワールドを対象とし、未ロードのワールドも保持します。

```powershell
python 60_tool/world-placement/placements.py export --source-server C:/server/AstralRecord-Dev/10_Dev --filebase 40_filebase --world-aliases C:/path/world-aliases.json --output C:/path/dev-placement-export.json
```

`world-aliases.json` は物理ワールド名から既存論理ワールドIDへの対応です。例えば旧フォルダ名を保持中なら次を指定します。物理フォルダをリネームする処理は行いません。

```json
{"plugins/AstralRecord/worlds/overworld/vanaheim_supercontinent":"eriva_supercontinent"}
```

4. 件数と内容を確認して、専用APIを起動しDevへインポートします。

```powershell
python 60_tool/world-placement/placements.py import-dev --input C:/path/dev-placement-export.json --api-url http://127.0.0.1:5286 --settings C:/server/AstralRecord-Dev/WorldPlacementApi/appsettings.json --receipt C:/path/dev-import-receipt.json
```

インポート先はコード上Devに固定しています。既存データと一致すれば書き込みません。既存Devデータが異なる場合は、内容確認後の `--expected-revision` が必要です。元YAMLがエクスポート後に変わった場合も停止します。既存のテレポーターID、作成者、日時、名称、解放費用を保持します。配置IDは元レコードから決定的に生成し、同じ移行の再実行で変わりません。

5. Pluginの配置モードと専用API接続を設定し、Devを起動します。旧YAMLは復旧用バックアップとして残せますが、APIモードで編集元として使用しません。Plugin設定・再読込コマンドは [PLUGIN_GUIDE](../../PLUGIN_GUIDE.md) を参照してください。

## 編集とチャンネル更新

マスタデータエディタのワールド配置画面でDevの配置を編集します。エディタのAPI接続とキーはServer側だけに設定します。追加・移動・削除後、Devの配置再読込を実行して実際の動作を確認します。APIモードではゲーム内の配置編集を拒否し、エディタへ案内します。

チャンネルへ反映する際だけ、通常APIに `ConnectionStrings:WorldPlacement` と別の `WorldPlacements:SyncKey` を設定し、専用DBツールを `--environment channels` で実行します。Dev/Channels双方の現在revisionを取得し、`POST /api/world-placements/sync` に `expectedDevRevision` / `expectedChannelRevision` を渡して同期します。同期後、各チャンネルの配置を再読み込みします。同期は両DBを同じSQL Server上で扱う必要があります。

Devのインポート、API起動、通常マスタ再読込からチャンネル同期を自動実行することはありません。移行前のチャンネルは従来のローカル保存モードを継続できます。

## 検証

- `python -m unittest discover -s 60_tool/world-placement -p "test_*.py"`
- `60_tool/tests/world-placement-db-protection.tests.ps1`: DBへの接続前に不正な再構築・リセット先を拒否する検査。対象ツールを事前にビルドします。
- APIの配置・認証・競合検査、Pluginのsnapshot検査、エディタの操作検査はそれぞれのプロジェクトにあります。

この移行ツールは元YAMLを削除せず、共有マスタの公開も行いません。
