# AstralRecord Workspace

AstralRecord のモノレポです。各プロジェクトの作業ルールは、ルート `AGENTS.md`、この README、workspace-local skill の `references/`、および本リポジトリ直下の各 `*_GUIDE.md` を優先してください。

## プロジェクト一覧

| Project | Role | Main Stack | Rules |
|:--|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft Plugin | Java, Kotlin, Paper/Spigot, Maven | [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) / `$astralrecord-code` |
| `20_api/AstralRecordApi/` | REST API | ASP.NET Core, C#, SQL Server | [API_GUIDE.md](API_GUIDE.md) / `$astralrecord-code` |
| `30_web/AstralRecordWeb/` | Web Site | ASP.NET Core Razor Pages | この README の「AstralRecord Web」 |
| `00_docs/40_Database設計書/` | SQL Server schema / table docs | SQL Server, Markdown | `00_docs/40_Database設計書/README.md` |
| `40_filebase/` | File-based master data | YAML, Markdown | この README の「AstralRecord Filebase」 |
| `50_resourcepack/` | Minecraft Resource Pack（現在は開発停止・将来用に保持） | JSON, PNG, PowerShell | この README の「AstralRecord Resource Pack」 |

## コミットルール

コミット対象の選別、除外対象、コミットメッセージ形式は [COMMIT_RULES.md](COMMIT_RULES.md) を参照してください。

## AstralRecord API

`20_api/AstralRecordApi/` は Plugin と Web が利用する REST API です。SQL Server とアプリケーション間の契約を提供します。詳細仕様、エンドポイント一覧、実装ルールは [API_GUIDE.md](API_GUIDE.md) を参照してください。

主な master data endpoint 例:
- `GET /api/gathering`
- `GET /api/gathering/{gatheringId}`
- `GET /api/gathering-spawner`
- `GET /api/gathering-spawner/{spawnerId}`
- `POST /api/equipment/durability`

## AstralRecord Plugin

`10_plugin/AstralRecord/` は Minecraft MMO RPG「AstralRecord」のサーバープラグインです。コード追加・修正全般は `$astralrecord-code` を使い、詳細ルールは [PLUGIN_GUIDE.md](PLUGIN_GUIDE.md) と `.codex/skills/astralrecord-code/references/plugin-code.md` を参照してください。

設計方針として、Plugin はライトビハインド方式を採用しています。そのためクラッシュ発生時の未反映データや書き込み途中の整合性までは考慮対象としておらず、クラッシュ耐性に関する考慮は不要です。

- AstralRecord Plugin では legacy color code の共通定義として `io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil` を使い、`org.bukkit.ChatColor` は新規利用しません。
- プレイヤー向けメッセージ送信は `io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService` を正本とし、`Player#sendMessage(...)` や `AstPlayer#sendMessage(...)` を新規利用しません。
- 全体チャット・パーティーチャット・ダイレクトメッセージは Plugin 管理メッセージとして扱い、将来の Web / Discord 連携を見据えて `PlayerMessageService` 経由で拡張します。
## AstralRecord Web

`30_web/AstralRecordWeb/AstralRecordWeb/` は管理・公開用の Web UI を提供します。

### 役割

- 管理・公開用の Web UI を管理する。
- API を利用して管理画面や公開画面を構成する。

### ディレクトリ方針

- Razor Pages は `Pages/` に置く。
- `.cshtml` と `.cshtml.cs` はセットで管理する。
- 画面ごとの責務をページ単位で閉じる。

### 実装方針

- Razor Pages の Page Model パターンを守る。
- 既存の UI、レイアウト、ナビゲーション構造に合わせる。
- API 依存がある画面では契約変更の影響を確認する。

### ドキュメント運用

- 大きな導線変更や運用変更があれば、必要に応じて README や関連資料を更新する。

### 補助プロンプト

- `.agents/prompts/pages.md`: Razor Pages 追加・変更時の確認観点と更新手順を扱う。

## AstralRecord Filebase

`40_filebase/` は YAML などの file 系マスタデータと、そのスキーマ資料を管理します。SQL Server の DB / テーブル定義は管理しません（DB 定義は `00_docs/40_Database設計書/` を対象）。

### 作業方針

- file マスタを変更する場合は、Plugin と API の読み込み処理、Resource Pack の参照、関連ドキュメントへの影響を確認する。
- `config.yml` のパス解決ルールと各 YAML スキーマ定義を優先する。
- マスタデータの ID、カテゴリ、参照先が実装やリソースパックと矛盾しないか確認する。
- ステータスID・日本語名・表示メタデータは`40_filebase/75.shared.status/v1.status_types.yml`を正本とし、変更後はルートの`generate-status-types.ps1`でKotlin/C#/TypeScript型を再生成する。

### スキーマ定義ファイルの配置

- 各フォルダ直下のスキーマ定義 Markdown は `docs.<name>.YAMLスキーマ定義.md` の形式で配置する（例: `bundle/docs.bundle.YAMLスキーマ定義.md`）。
- ファイル名の先頭に `docs.` を付け、スキーマ定義であることを明示する慣習です。
- `.` 先頭は Obsidian で非表示となるため使用しない。

## AstralRecord Resource Pack

`50_resourcepack/` は将来の作成再開に備えて保持している Minecraft Java Edition 向けリソースパックの雛形です。現在は開発を停止しているため、通常の実装・設計・マスターデータ作業では対象外として無視してください。リソースパックの作成や修正を再開するときに、この節と `50_resourcepack/AGENTS.md` を確認してください。

### 対象バージョン

- Minecraft Java Edition: 1.21.11
- Resource pack format: 75
- サーバープラグイン側バージョン情報の参照元: `10_plugin/AstralRecord/pom.xml`

Minecraft の対象バージョンは、`10_plugin/AstralRecord/pom.xml` に定義されている `io.papermc.paper:paper-api` のバージョンから判断します。`pom.xml` のバージョンが変わった場合は、`pack.mcmeta` の `pack_format` 更新が必要か確認してください。

現在確認済みの値:

- `paper-api`: `1.21.11-R0.1-SNAPSHOT`
- Minecraft Java Edition: `1.21.11`
- Resource pack `pack_format`: `75`

### ディレクトリ構成

```text
50_resourcepack/
  pack.mcmeta
  assets/
    astralrecord/
      lang/
      models/
        item/
      textures/
        item/
      sounds/
    minecraft/
      models/
      textures/
```

独自アセットは原則として `astralrecord` 名前空間に配置します。`minecraft` 名前空間は、バニラアセットを意図的に上書きする場合だけ使用してください。

### 必ず確認する情報

アセット、モデル、メタデータを編集する前に、存在する場合は以下のファイルを確認してください。

- `resourcepack.config.json`
- `10_plugin/AstralRecord/pom.xml`
- `10_plugin/AstralRecord/src/main/resources/plugin.yml`
- `40_filebase/config.yml`

参照先の管理には `resourcepack.config.json` を使います。基本はこのディレクトリからの相対パスを優先してください。相対パスで解決できない環境では、`resourcepack.config.json` の `absolutePathOverrides` を確認してください。

### アセット作成ルール

- ファイル名とディレクトリ名は小文字で統一する。
- 独自アセットには `astralrecord` 名前空間を使う。
- `minecraft` 名前空間は、バニラアセットを意図的に上書きする場合だけ使う。
- テクスチャは `.png`、モデルは `.json` を基本とする。
- モデル識別子、テクスチャ名、言語キーは、プラグインや DB 定義のアイテム識別子と対応させる。
- ユーザーが明示的に求めた場合、または作業に必要な場合を除き、大きなバイナリアセットは追加しない。
- 生成された zip は、リリース運用で必要な場合を除きコミットしない。

### ビルド

このディレクトリから実行します。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-resourcepack.ps1
```

生成先は `dist/AstralRecordResourcePack.zip` です。

### 検証

構造を変更した後は、以下を確認してください。

- `pack.mcmeta` が有効な JSON であること。
- モデルファイルが有効な JSON であること。
- モデルから参照しているテクスチャが存在すること。
- `scripts/build-resourcepack.ps1` で `dist/AstralRecordResourcePack.zip` を作成できること。

### GitHub Copilot

`.github/copilot-instructions.md` は参照用のブリッジファイルです。リソースパックの指示を変更する場合は、この README の本セクションを更新してください。
