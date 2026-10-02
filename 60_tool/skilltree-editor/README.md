# AstralRecord Master Data Editor

Filebase の YAML / JSON マスターと、スキルツリーのノード・配置・接続構造、Plugin の表示設定を編集する開発者専用ローカル Web アプリです。日本語フォーム、原稿編集、装備比較、クラス成長グラフを同じアプリで使えます。`30_web` には依存せず、ASP.NET Core がワークスペース内のファイルを直接扱います。既存の起動バッチ・プロジェクト名・配置先は `skilltree-editor` のままです。

## 管理対象

| 種別 | パス |
| --- | --- |
| Filebase マスター・設定 | `40_filebase/**/*.yml` / `*.yaml` / `*.json`（保護対象を除く） |
| 日本語フォームの定義元（読取） | 各カテゴリの `docs.*.YAMLスキーマ定義.md` と JSON Schema |
| ノードマスター | `40_filebase/35.features.skilltree/nodes/*.json` |
| 配置・接続構造 | `40_filebase/35.features.skilltree/structures/*.json` |
| JSON Schema（読取専用） | `40_filebase/**/*.schema.json` |
| スキルツリー表示シミュレーション用クラス階層 | `40_filebase/20.features.class/*.yml` |
| スキル表示情報・候補 | `40_filebase/30.features.skill/**/*.yml` |
| ステータス表示情報・候補（生成元） | `40_filebase/75.shared.status/v1.status_types.yml` |
| タグ表示情報・候補・保存時検証（生成元） | `40_filebase/76.shared.tag/v1.tags.yml` |
| nodeId採番high-water | `40_filebase/35.features.skilltree/node-id-sequence.json` |
| Plugin 表示設定 | `10_plugin/AstralRecord/src/main/resources/config.yml` の `skilltree.worldName` / `structureId` / `center` |
| 保存前バックアップ | `60_tool/skilltree-editor/.backups/` |
| Minecraftアイコンキャッシュ | `60_tool/skilltree-editor/.cache/minecraft-icons/` |

ノード ID は `node-id-sequence.json` のhigh-waterと既存最大値を照合して1000から自動採番され、作成後は変更できません。採番値はノードJSONより先に永続化し、削除しても戻さないため再利用されません（書込み失敗時の欠番は許容）。構造の X / Z をキャンバス座標、Y を配置インスペクターで編集します。edge は無向として扱い、保存時に端点を正規化します。

## Filebase マスターの編集

上部の「Filebase マスター」で、アイテム、クラス、スキル、モブ、スポナー、採集、ショップ、クエスト、ワールド、バフ、ドロップ報酬、レシピ、共有カタログなどのファイルを選びます。「スキルツリー」へ切り替えると従来のキャンバス編集を使えます。切替時も編集中の内容は保持されます。

- 左のカテゴリ・サブフォルダ・名前 / ID / パス検索・YAML / JSON 形式で一覧を絞り込みます。50件ごとのページ表示です。
- 「日本語フォーム」は短い項目を編集領域の幅に合わせて横並びに表示します。左ペインは「ファイル一覧」と「レコード内の構造」を切り替えられ、「ナビを隠す」で入力欄を広げられます。狭い画面ではファイルを開くとナビを閉じます。
- 構造ツリーとパンくずで選択した Map / 配列だけを編集します。深い階層でも入力欄の幅は変わりません。「フォーム内検索」は日本語名・キー・値を検索し、結果から該当項目へ移動します。同じファイルを開き直すと、このアプリ内で最後に選んだ有効な階層を復元します。
- 定義書の項目名・型・必須条件と元のキーを併記し、説明と型変更・削除の操作は必要なときに開きます。定義済み任意項目や自由形式のキーは、各Mapの「項目を追加」から追加できます。
- オブジェクトの配列は要約と操作を表示し、25件ずつ一覧から選択した要素を編集します。追加・削除・複製・順序変更に対応します。配列要素内でUndo / Redoした場合は、位置変更後の別要素を誤編集しないよう配列の一覧へ戻ります。
- `implementationId`、RPC、固有 `params` のキーや参照 ID は元の表記で扱います。フォームで未定義のキーも保持し、「原稿 YAML / JSON」でコメントを含む全原稿を編集できます。元のYAMLの解析に失敗していても、原稿から修復できます。
- 「＋ 新規」は既存ファイルの原稿テンプレート、JSON Schema の必須項目生成、直接入力から作成します。「原稿を読込」でローカルのファイルを編集中の原稿または新規ファイルへ取り込めます。読込だけでは保存しません。
- 「保存済みファイルを複製」は保存済み原稿を新規ファイルの下書きにします。アイテムはID・ファイル名を自動採番でき、他のマスターは原稿内のIDと保存先を新しいものへ変更してから作成します。同じIDや保存先への作成は検証で拒否します。
- アイテムは `10.material` / `20.equipment` などのカテゴリに合わせて ID と `v<schemaVersion>.<id>.<slug>.yml` を自動生成します。通常の英字は `a` から繰り上げ、`z` はデバッグ用として別に指定します。クラスは `schemaVersion` / `order` / `id` からファイル名を生成できます。採番と保存先は作成成功時に確定します。
- 「差分」で保存済み原稿と編集中の原稿を比較し、「検証」でエラー箇所を確認してから明示保存します。Ctrl+S は保存、ツールバーの矢印はUndo / Redoです。未保存のファイル切替・ページ終了には確認が入ります。
- 検証結果の項目パスを押すと、非表示の階層も開いて入力欄へ移動します。不正な数値の入力中は、入力が失われないよう別ノードへの移動とフォーム全体のUndo/Redoを止めます。数値を修正すると、移動や履歴操作を再開できます。入力欄内の標準のUndoは利用できます。
- 「参照一覧」は検出した参照元ファイルと項目を表示し、参照元へ移動できます。ID入力には既存マスターの名前付き候補を表示します。参照されているファイルは削除を拒否します。
- 「現在の原稿を書出」は未保存の変更を含む原稿、「保存済みを書出」はディスク上の原稿をダウンロードします。「このカテゴリの定義書」から定義資料のMarkdown原稿を確認できます。

`*.schema.json` と `node-id-sequence.json` は読取専用です。Schemaの更新はリポジトリの変更として行い、定義と既存マスターを検証してください。ノードの新規作成・複製は自動採番を維持するため「スキルツリー」で行います。既存ファイルの `id` / `nodeId` / `structureId` は保存時に変更できません。IDの変更は新規ファイルとして作成し、参照元も整合させてください。

マスター保存は構文、定義書から読み取った型・必須、適用できるJSON Schema、カテゴリ・ID・ファイル名、同じマスター種別のID重複を検証します。スキルツリーには既存の構造検証も適用します。参照検出と汎用定義書の検証は、Plugin / API の全実装固有条件を代替するものではありません。運用へ反映するときは既存のFilebase検証・デプロイ・再読込も行ってください。

## 装備比較とクラス成長グラフ

「比較・成長グラフ」は保存済みマスターを使います。マスター変更後は「マスター再読込」で反映します。

- 「装備を比較」は要求プレイヤーLvの中心と前後幅、スロット、装備タグ、名前 / IDで絞り、下限〜上限の比較表とステータスの棒グラフを表示します。チェックした装備だけの比較とCSV出力に対応します。
- 強化Lvの差分を累積し、Plugin と同じく FLAT は加算、基礎 SCALAR は最後の定義を採用します。累積した強化 SCALAR があれば基礎 SCALAR を上書きし、FLAT に乗算します。SCALARだけのステータスは補正を持ちません。個体の乱数、エンチャント、ルーン、セット、超越、装備可否は比較計算に含めません。
- 「クラス成長」はアカウントUUID、開始プレイヤーLv、開始クラスLv、表示上限、比較する職業（最大12件）を指定して計算します。プレイヤーLvに対するクラスLv・累積クラスEXP・クラスのステータス補正を切り替え、指定Lvの数値表とCSVも確認できます。
- プレイヤー必要経験値のUUID由来の差、クラスの `expRate` / `maxLevel`、`baseStats` / `growthPerLevel` を反映します。通常育成・転生なし、開始Lv到達直後から同じEXPをプレイヤーと1職へ加算する仮定です。職業ごとの線は独立した育成シナリオで、転職条件や過去の育成履歴を含みません。「計算の根拠と前提」で式とPluginソースの対応を確認できます。

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
- 共有タグカタログに未定義、または`SKILLTREE_NODE`へ適用できないノードタグ
- Plugin中心座標と相対座標の加算による32-bit座標overflow

## Reactをビルドして単一起動

`60_tool/05-skilltree-editor.bat` はReactをビルドしてからASP.NET Coreを起動します。`node_modules` がない、またはTypeScript/Viteの実行ファイルが欠けている場合は `npm ci` を実行します。依存関係の部分破損などで最初のビルドに失敗した場合は、`node_modules` を削除して `npm ci` から1回だけ再試行し、それでも失敗した場合はサーバーを起動しません。

BAT起動時の `PATH` にnpmが含まれていない場合は、Node.jsの標準インストール先（`C:\Program Files\nodejs` またはユーザー単位のインストール先）も自動検出します。ビルドまたはサーバー起動に失敗した場合は、原因を確認できるようウィンドウを閉じずに停止します。

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

- マスターの原稿保存は入力したキー順・コメント・改行を保持してUTF-8（BOMなし）で保存します。同内容は書き換えません。更新・削除前の原稿は `.backups/master-data/` へ保存します。
- YAMLフォームで構造が変わらず値だけを変更した場合は、元の原稿へ値を反映してコメント・順序・改行を保持します。キーや配列要素の追加などで全体を再生成する場合は、元のコメントを先頭へ移したことを画面に警告します。差分を確認し、コメントの位置を維持したい場合は原稿で編集してください。
- Filebase マスター画面の更新・削除は読込時のSHA-256 revisionを照合します。他のエディタや外部編集による変更があれば409で拒否するので、再読込して差分を確認してください。画面の複製は読込時の保存済み原稿から編集可能な新規原稿を作成するため、複製元を再読込してから実行すると最新の内容を使えます。workspace共通の排他と同一ディレクトリへの原子的なファイル置換を使用します。
- スキルツリー画面で保存するJSONはノード・構造・配置・edge・effect・採番メタデータごとの固定された意味順にキーを並べ、未知の追加キーは辞書順に安定化します。UTF-8（BOMなし）、2スペースインデント、LF、末尾改行ありです。同一内容の再保存はファイルを書き換えません。
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
- キャンバスとノード一覧には、マスターの `icon` に指定したBukkit Materialの画像と、Minecraft装飾コードを除いた名前を表示します。タグは共有カタログの日本語名・説明、ステータス効果は日本語名と値、スキル効果は日本語名と説明をノードのホバー情報へ表示し、ノード一覧と詳細には概要も直接表示します。
- キャンバスでノードを選択すると、右側でX/Y/Z、名前、Material、ポイント、タグ、Loreを直接編集できます。Loreは任意項目で、空にして保存するとJSONからキーを省略します。Effects、Schema、Raw JSONは「Effects・Schema・Raw JSONを編集」から編集します。
- ノード編集画面の「複製して新規作成」では、編集中の内容をすべて引き継ぎ、nodeIdだけを保存時に自動採番したノードを作成できます。基本ステータスなど、同一定義を複数ノードで使う場合に利用します。
- ノード一覧・ホバー・詳細には、ステータス効果などに加えてCP/PPの消費種別と消費量を色分けして表示します。`unlockCondition.classId` があるノードは、クラスマスターの表示名を使って `CP[クラス名] コスト` 形式で表示します。
- キャンバス上のノードを右クリックすると、マスター編集、ROOT設定、nodeIdコピー、接続削除、配置削除を選べます。複数選択中の配置削除にも対応します。
- ノードのハンドル間をドラッグしてedgeを追加します。
- Shift / Ctrl / Cmdで複数選択、Deleteで配置またはedgeを削除します。
- ヘッダーの「一覧」「キャンバス」「詳細」で各ペインを表示・非表示にできます。ペイン間の境界をドラッグすると幅を変更でき、境界のダブルクリックで初期幅へ戻ります。
- 「ゲーム内表示シミュレーション」を有効にすると、現在の職業とプレイヤーレベルを指定して、`unlockCondition` を満たすノードだけを一覧・キャンバスへ表示します。クラス条件は現在職から filebase の転職前提を再帰的に辿り、祖先職も成立扱いにします。edge は両端ノードが表示対象の場合だけ表示します。
- キャンバス上部の「ノードサイズ」で32～140pxへ変更できます。設定はブラウザのlocalStorageへ保持し、構造JSONの1ブロック単位座標には影響しません。72px未満では名前とコストを省略し、ホバーで詳細を確認します。
- Ctrl+Z / Ctrl+Y、またはヘッダーのボタンでUndo / Redoします。
- 「補助自動配置」はrootからのBFSレイヤー配置をX/Zへ明示反映します。通常の編集履歴に入るためUndoでき、結果は保存時に構造JSONの座標として確定します。
- ノードマスターはJSON Schemaから生成したフォームとRaw JSONの両方で編集できます。既存文書は `$schema` のファイル名から対応Schemaを選び、新規文書では最新の既定Schemaを選択できます。新しいSchema項目はフォームへ自動的に反映され、未対応の複雑な表現はRaw JSONで編集できます。
- `icon` は自由入力を維持しつつ、Paper 1.21.11でアイテムとして使用可能なMaterial候補を表示します。候補は `src/SkillTreeEditor.Client/src/data/minecraft-materials.1.21.11.json` に固定しているため、サーバーバージョンを変更するときに公式server data generatorの `generated/reports/items.json` から更新してください。タグとステータスは共有カタログから生成した日本語名・説明付き候補、スキルはfilebaseから読み取った日本語名付き候補を表示しますが、JSONへ保存する値はいずれもIDです。
- 検証に成功するまで構造JSONは保存されません。

## Minecraftアイコン

- Filebase一覧に保存済みの `icon` の画像を表示します。ファイル見出しではフォームの編集中の値をプレビューし、原稿モードでは保存済みのアイコンを表示します。
- 日本語フォームの `icon` には編集中の値のプレビューを表示し、「画像一覧から選択」で画像とMaterial IDを並べた選択ダイアログを開きます。ID検索、48件ごとのページ切替、選択中表示に対応します。
- スキルツリーのノード編集フォームと配置インスペクターでも同じ選択ダイアログを使えます。選択は編集中の原稿へ反映し、ファイルへの反映には従来どおり保存操作が必要です。
- キャンセル・Escapeでは値を変更しません。自由入力も可能です。「画像を再読込」は取得に失敗した画像の再試行に使います。

- アイコン画像は[MC Icons API](https://mc-icons.com/api)の `download/{id}/thumb` からASP.NET Core経由で取得します。取得できない場合は、[mc-assets のMinecraft 1.21.11固定版](https://github.com/Owen1212055/mc-assets/blob/551e4a68f23a59eecc84a310902f7e10864945f9/README.md) のゲーム内在庫表示画像を使用します。固定版の `item-assets/{MATERIAL}.png` は256×256のネイティブ描画で、現行の候補1,505件すべてに対応するファイルがあります。`50_resourcepack` は参照しません。
- `NETHER_STAR` や `minecraft:nether_star` はMC Icons用の `nether_star` に正規化され、初回取得後は `.cache/minecraft-icons/` のPNGを利用します。キャッシュはGit管理対象外です。
- 両方の取得先へ接続できない、またはMaterialに対応する画像がない場合も編集は継続でき、画面には `?` を表示します。取得済みの画像はローカルキャッシュから表示できます。接続を復旧してから「画像を再読込」を押してください。
- 主取得元は `appsettings.json` の `SkillTreeEditor:MinecraftIconsBaseUrl`、代替取得元は `SkillTreeEditor:MinecraftIconsFallbackBaseUrl` で設定します。主取得元は `download/{id}/thumb`、代替取得元は大文字の `{MATERIAL}.png` とPNG応答が必要です。代替URLを空にすると代替取得を無効化します。
- PaperのMaterial候補を更新するときは、代替画像の固定コミットも同じMinecraft版へ合わせて更新し、追加候補の画像を確認してください。
- 完全に取り直す場合はEditorを停止し、`.cache/minecraft-icons/` 内の対象PNGを削除してから再起動します。ノードマスターや構造JSONには影響しません。

## デプロイとリロード

このエディタが変更するのはリポジトリ内のfilebaseと `10_plugin/AstralRecord/src/main/resources/config.yml` です。稼働サーバーへは通常のデプロイ手順でfilebaseを反映し、その後Pluginで `/masterdata reload` を実行してください。Skill Treeもこの一括リロードに含まれます。

ソース側 `config.yml` の変更は既存のPlugin data folderへ自動コピーされません。表示ワールド・構造・中心座標を変えた場合は、稼働環境の `plugins/AstralRecord/config.yml` もデプロイまたは同期してからリロードしてください。エディタが稼働サーバーの設定へ直接書き込むことはありません。

JSON Schemaで表現できないBukkit MaterialやスキルIDとの実在照合はPluginロード時にも行われます。ステータス候補は共有ステータスカタログと`.\60_tool\generate-status-types.ps1`、タグ候補は共有タグカタログと`.\60_tool\generate-tag-types.ps1`で更新してください。タグ生成では全filebaseの未定義タグと用途不一致も検査します。`/masterdata reload` が返すエラーを修正してから運用へ反映してください。

## 定義変更時の保守

マスターのキー・型・必須・列挙・参照・命名、読込・変換、装備比較やレベル成長式を変更した場合は、定義とこのサイトを同じ変更で整合させます。対象スキルから [Filebase 編集サイトの同期規則](../../.codex/skills/_shared/filebase-editor-sync.md) を読み、影響するServer / Clientと検証を確認してください。

定義書やJSON Schemaから生成する項目は、元資料の変更と「一覧と定義メタデータを再読込」で反映を確認します。特殊な候補・参照・検証・採番は `MasterDataCatalog` / `MasterDataValidation` / `MasterDataService`、画面は `MasterDataForm` / `MasterDataEditor`、計算は `MasterAnalyticsService` / `masterAnalytics.ts` を更新します。個別マスターの性能値・名前・説明文を既存契約内で調整するだけなら、動的反映を確認し、重複した定義をサイトのコードへ追加しません。

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
