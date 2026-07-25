# AstralRecord Skill Tree Editor

スキルツリーのノードマスター、配置・接続構造、Plugin の表示設定を編集する開発者専用ローカル Web アプリです。`30_web` には依存せず、ASP.NET Core がワークスペース内の JSON / YAML を直接扱います。

## 管理対象

| 種別 | パス |
| --- | --- |
| ノードマスター | `40_filebase/35.features.skilltree/nodes/*.json` |
| 配置・接続構造 | `40_filebase/35.features.skilltree/structures/*.json` |
| JSON Schema | `40_filebase/35.features.skilltree/schemas/*.schema.json` |
| nodeId採番high-water | `40_filebase/35.features.skilltree/node-id-sequence.json` |
| Plugin 表示設定 | `10_plugin/AstralRecord/src/main/resources/config.yml` の `skilltree.worldName` / `structureId` / `center` |
| 保存前バックアップ | `60_tool/skilltree-editor/.backups/` |
| Minecraftアイコンキャッシュ | `60_tool/skilltree-editor/.cache/minecraft-icons/` |

ノード ID は `node-id-sequence.json` のhigh-waterと既存最大値を照合して1000から自動採番され、作成後は変更できません。採番値はノードJSONより先に永続化し、削除しても戻さないため再利用されません（書込み失敗時の欠番は許容）。構造の X / Z をキャンバス座標、Y を配置インスペクターで編集します。edge は無向として扱い、保存時に端点を正規化します。

## 必要環境

- [.NET 10 SDK](https://dotnet.microsoft.com/download/dotnet/10.0)（`global.json` は `10.0.302`、互換Feature Bandへのroll-forwardを許可）
- Node.js 24 LTS / npm（`src/SkillTreeEditor.Client/.nvmrc` は `24.18.0`）

確認コマンド:

```powershell
dotnet --version
node --version
npm --version
```

## 初回セットアップ

```powershell
cd E:\AstralRecord-Workspace\60_tool\skilltree-editor\src\SkillTreeEditor.Client
npm ci
```

`package-lock.json` はコミット済みで、`npm ci` により同じ依存関係を再現します。依存パッケージを意図的に更新するときだけ `npm install` を使用し、lockfileも同時に更新します。

## 開発（2プロセス）

ターミナル1でバックエンドを起動します。

```powershell
cd E:\AstralRecord-Workspace\60_tool\skilltree-editor\src\SkillTreeEditor.Server
dotnet watch run
```

ターミナル2でViteを起動します。

```powershell
cd E:\AstralRecord-Workspace\60_tool\skilltree-editor\src\SkillTreeEditor.Client
npm run dev
```

ブラウザで `http://127.0.0.1:5173` を開きます。Vite は `/api` を `http://127.0.0.1:5274` へプロキシします。既定ではどちらもloopbackでのみ待ち受けます。

## 検証

```powershell
cd E:\AstralRecord-Workspace\60_tool\skilltree-editor\src\SkillTreeEditor.Client
npm run lint
npm test
npm run build

cd E:\AstralRecord-Workspace\60_tool\skilltree-editor
dotnet test SkillTreeEditor.slnx
dotnet build SkillTreeEditor.slnx
```

バックエンドはJSON Schema検証に加えて、次を検出します。

- nodeId / structureId 重複
- 同一nodeIdの複数配置、座標重複
- 自己接続、無向edge重複
- 存在しない、または未配置のnodeId参照
- rootNodeId未配置・不存在
- rootから到達不能な配置ノード
- node/structureのIDとファイル名の不一致
- nodeId high-waterのSchema違反、既存最大IDより小さい値
- 壊れたJSON Schema（未参照Schemaも全体検証でファイル単位に報告）
- Plugin中心座標と相対座標の加算による32-bit座標overflow

## Reactをビルドして単一起動

`60_tool/05-skilltree-editor.bat` はReactをビルドしてからASP.NET Coreを起動します。`node_modules` がない初回だけ `npm ci` も実行し、ビルドに失敗した場合はサーバーを起動しません。

Reactだけをビルドする場合は、次のBATを実行します。起動済みのASP.NET Coreは通常そのままでよく、ビルド後にブラウザを再読み込みしてください。

```powershell
E:\AstralRecord-Workspace\60_tool\06-skilltree-editor-build.bat
```

通常ビルド後にASP.NET Coreから静的ファイルを配信する場合:

```powershell
cd E:\AstralRecord-Workspace\60_tool\skilltree-editor\src\SkillTreeEditor.Client
npm ci
npm run build

cd ..\SkillTreeEditor.Server
dotnet run
```

`dist/index.html` を検出するとASP.NET CoreがSPAを配信します。コマンドから直接 `dotnet run` した場合はReactを自動ビルドしません。

配布用publishではフロントエンドの `npm ci` / `npm run build` を自動実行し、成果物をpublish先の `wwwroot` へ格納します。

```powershell
cd E:\AstralRecord-Workspace\60_tool\skilltree-editor
dotnet publish src\SkillTreeEditor.Server\SkillTreeEditor.Server.csproj -c Release -o publish
.\publish\SkillTreeEditor.Server.exe
```

ブラウザで `http://127.0.0.1:5274` を開きます。publish版は起動時のカレントディレクトリに依存せず、実行ファイルと同じディレクトリの `appsettings.json` と `wwwroot` を使用します。

## ワークスペースの上書き

通常はカレントディレクトリ、実行ファイル位置から上位へ探索し、`40_filebase`、`10_plugin`、`60_tool` が揃うディレクトリを採用します。別のworktreeを対象にするときは次のいずれかを使います。

```powershell
$env:ASTRALRECORD_WORKSPACE = 'C:\AstralRecord-Worktrees\my-task'
dotnet run --project .\src\SkillTreeEditor.Server
```

または:

```powershell
dotnet run --project .\src\SkillTreeEditor.Server -- --SkillTreeEditor:WorkspaceRoot=C:\AstralRecord-Worktrees\my-task
```

## 保存とバックアップ

- JSONはノード・構造・配置・edge・effect・採番メタデータごとの固定された意味順にキーを並べ、未知の追加キーは辞書順に安定化します。UTF-8（BOMなし）、2スペースインデント、LF、末尾改行ありです。同一内容の再保存はファイルを書き換えません。
- node / structure / Plugin設定の更新はworkspace共通の排他内で再読込・検証・保存するため、並行リクエストや複数Editorプロセスがノード削除と構造保存を競合させても未知nodeId参照を正本へ残しません。
- 既存JSON、削除対象JSON、`config.yml` は変更前に `.backups/<category>/<filename>.<timestamp>.bak` へコピーします。
- nodeId採番時はWindows/Linux共通のファイル排他内でhigh-waterと既存ノードを再読込し、`node-id-sequence.json` を `.backups/node-id-sequence/` へ退避してから原子的にhigh-waterを進め、その後ノードJSONを作成します。複数Editorプロセスから同じworkspaceを開いてもhigh-waterを巻き戻しません。
- 一時ファイルを同一ディレクトリへ書いてから置換するため、書込み途中のファイルを正本にしません。
- `.backups/` はGit管理対象外です。不要になったバックアップは手動で削除してください。
- `config.yml` は既存の改行コード、コメント、他のトップレベル設定を維持し、`skilltree:` 内の管理対象値だけを更新します。`worldName` にYAMLで使用できない制御文字は保存できません。
- 設定画面が更新するのはリポジトリ上の `10_plugin/AstralRecord/src/main/resources/config.yml` です。稼働環境の `plugins/AstralRecord/config.yml` と filebase へデプロイまたは同期した後、サーバーで `/masterdata reload` を実行してください。このツールは稼働サーバーへ直接書き込みません。

## 操作メモ

- 未配置ノードを左ペインからキャンバスへドラッグして配置します。
- キャンバスの空白を左ドラッグすると画面を移動します。Shiftを押しながら空白をドラッグすると範囲選択になります。
- キャンバスとノード一覧には、マスターの `icon` に指定したBukkit Materialの画像と、Minecraft装飾コードを除いた名前を表示します。
- キャンバスでノードを選択すると、右側でX/Y/Z、名前、Material、ポイント、タグ、Loreを直接編集できます。Effects、Schema、Raw JSONは「Effects・Schema・Raw JSONを編集」から編集します。
- キャンバス上のノードを右クリックすると、マスター編集、ROOT設定、nodeIdコピー、接続削除、配置削除を選べます。複数選択中の配置削除にも対応します。
- ノードのハンドル間をドラッグしてedgeを追加します。
- Shift / Ctrl / Cmdで複数選択、Deleteで配置またはedgeを削除します。
- ヘッダーの「一覧」「キャンバス」「詳細」で各ペインを表示・非表示にできます。ペイン間の境界をドラッグすると幅を変更でき、境界のダブルクリックで初期幅へ戻ります。
- Ctrl+Z / Ctrl+Y、またはヘッダーのボタンでUndo / Redoします。
- 「補助自動配置」はrootからのBFSレイヤー配置をX/Zへ明示反映します。通常の編集履歴に入るためUndoでき、結果は保存時に構造JSONの座標として確定します。
- ノードマスターはJSON Schemaから生成したフォームとRaw JSONの両方で編集できます。既存文書は `$schema` のファイル名から対応Schemaを選び、新規文書では最新の既定Schemaを選択できます。新しいSchema項目はフォームへ自動的に反映され、未対応の複雑な表現はRaw JSONで編集できます。
- `icon` は自由入力を維持しつつ、Paper 1.21.11でアイテムとして使用可能なMaterial候補を表示します。候補は `src/SkillTreeEditor.Client/src/data/minecraft-materials.1.21.11.json` に固定しているため、サーバーバージョンを変更するときに公式server data generatorの `generated/reports/items.json` から更新してください。既存タグも候補として表示します。スキルIDとステータス名はPlugin実装との同期が必要なため固定候補にはしていません。
- 検証に成功するまで構造JSONは保存されません。

## Minecraftアイコン

- アイコン画像は[MC Icons API](https://mc-icons.com/api)の `download/{id}/thumb` からASP.NET Core経由で取得します。`50_resourcepack` は参照しません。
- `NETHER_STAR` や `minecraft:nether_star` はMC Icons用の `nether_star` に正規化され、初回取得後は `.cache/minecraft-icons/` のPNGを利用します。キャッシュはGit管理対象外です。
- 外部サービスへ接続できない、またはMaterialに対応する画像がない場合も編集は継続でき、画面には `?` を表示します。接続を復旧してからヘッダーまたはインスペクターの「アイコン再読込」を押してください。
- 取得元を差し替える場合は `appsettings.json` の `SkillTreeEditor:MinecraftIconsBaseUrl` を変更します。互換先には同じ `download/{id}/thumb` 形式とPNG応答が必要です。
- 完全に取り直す場合はEditorを停止し、`.cache/minecraft-icons/` 内の対象PNGを削除してから再起動します。ノードマスターや構造JSONには影響しません。

## デプロイとリロード

このエディタが変更するのはリポジトリ内のfilebaseと `10_plugin/AstralRecord/src/main/resources/config.yml` です。稼働サーバーへは通常のデプロイ手順でfilebaseを反映し、その後Pluginで `/masterdata reload` を実行してください。Skill Treeもこの一括リロードに含まれます。

ソース側 `config.yml` の変更は既存のPlugin data folderへ自動コピーされません。表示ワールド・構造・中心座標を変えた場合は、稼働環境の `plugins/AstralRecord/config.yml` もデプロイまたは同期してからリロードしてください。エディタが稼働サーバーの設定へ直接書き込むことはありません。

JSON Schemaで表現できないBukkit MaterialやPluginのStatusTypeとの実在照合はPluginロード時にも行われます。`/masterdata reload` が返すエラーを修正してから運用へ反映してください。

## トラブルシューティング

### `A compatible .NET SDK was not found`

.NET 10 SDKをインストールし、`dotnet --list-sdks` に10.xが表示されることを確認してください。Runtimeだけではビルドできません。

### `npm` / `node` が見つからない

Node.js 24 LTSをインストールして新しいターミナルを開いてください。nvm利用時は本ディレクトリで `nvm use` を実行します。

### ASP.NET側で「frontend is not built」と表示される

`src/SkillTreeEditor.Client` で `npm ci` と `npm run build` を実行してください。開発時はASP.NETのURLではなくViteの `http://127.0.0.1:5173` を開きます。

### ワークスペースが見つからない

`ASTRALRECORD_WORKSPACE` または `SkillTreeEditor:WorkspaceRoot` に、`40_filebase`、`10_plugin`、`60_tool` を直接含むルートを指定してください。

### 保存が422になる

右側の検証パネルでエラーコードとJSON Pointerを確認します。nodeId変更、重複座標、無向edgeの逆順重複、rootから到達できないノードが代表例です。
