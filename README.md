# AstralRecord Workspace

AstralRecord のモノレポです。各プロジェクトの作業ルールはルート `AGENTS.md`、この README、workspace-local skill の `references/`、対象プロジェクト直下の `AGENTS.md` が存在する場合はその内容を優先してください。

## プロジェクト一覧

| Project | Role | Main Stack | Rules |
|:--|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft Plugin | Java, Kotlin, Paper/Spigot, Maven | この README の「AstralRecord Plugin」と `$astralrecord-code` |
| `20_api/AstralRecordApi/` | REST API | ASP.NET Core, C#, SQL Server | この README の「AstralRecord API」と `$astralrecord-code` |
| `30_web/AstralRecordWeb/` | Web Site | ASP.NET Core Razor Pages | `30_web/AstralRecordWeb/AGENTS.md` |
| `40_database/` | SQL Server schema / table docs | SQL Server, Markdown | `40_database/AGENTS.md` |
| `50_filebase/` | File-based master data | YAML, Markdown | `50_filebase/AGENTS.md` |
| `60_resourcepack/` | Minecraft Resource Pack | JSON, PNG, PowerShell | `60_resourcepack/AGENTS.md` |

## コミットルール

コミット対象の選別、除外対象、コミットメッセージ形式は [COMMIT_RULES.md](COMMIT_RULES.md) を参照してください。

## AstralRecord API

`20_api/AstralRecordApi/` は Plugin や Web が利用する REST API です。SQL Server とアプリケーションの間の契約を提供します。

### 役割概要

- 可変データは SQL Server から取得する。
- 静的データはファイルシステム上のデータ定義を参照する。
- ランタイムは .NET 10、フレームワークは ASP.NET Core Web API。

### 設定

設定は `20_api/AstralRecordApi/AstralRecordApi/appsettings.json` と `20_api/AstralRecordApi/AstralRecordApi/appsettings.Development.json` で管理します。

- `ConnectionStrings:SqlServer`: SQL Server 接続文字列
- `FileDatabase:RootPath`: 静的データファイルのルートパス

### API 実装ルール

- API 追加・変更時は `.codex/skills/astralrecord-code/references/api-code.md` を参照する。
- 詳細仕様は `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` 配下を確認する。
- API 契約変更は Plugin / Web / Database / Filebase への影響を前提に扱う。

### API ドキュメント

各 API の詳細仕様は `00_docs/20_API設計書/feature/` 配下の設計書を参照してください。

| エンドポイント | 役割 | ドキュメント |
|---|---|---|
| GET `/api/health` | ヘルスチェック | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/user/{uuid}` | ユーザー情報取得 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| POST `/api/user` | ユーザー作成 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| PUT `/api/user/{uuid}` | ユーザー情報更新 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| GET `/api/account?user_id={user_id}` | ユーザー配下のアカウント一覧取得 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| GET `/api/account/{uuid}` | アカウント取得 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| POST `/api/account` | アカウント作成 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| PUT `/api/account/{uuid}` | アカウント更新 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| GET `/api/inventory?account_id={account_id}` | アカウント配下のインベントリ一覧取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/{inventoryId}` | インベントリ本体取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| POST `/api/inventory` | インベントリ本体作成 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| PUT `/api/inventory/{inventoryId}` | インベントリ本体更新 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/{inventoryId}/entries` | インベントリエントリ一覧取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| POST `/api/inventory/{inventoryId}/entries` | インベントリエントリ作成 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| PUT `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ更新 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| DELETE `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ削除 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/item` | アイテム一覧取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| GET `/api/item/{itemId}` | アイテム取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| POST `/api/equipment/instances` | 装備インスタンス作成 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| GET `/api/equipment/instances/{instanceId}` | 装備インスタンス取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/enchant` | エンチャント適用 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| DELETE `/api/equipment/enchant` | エンチャント削除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/enhance` | 装備強化 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/transcendence` | 超越適用 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/rune` | ルーン装着 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| DELETE `/api/equipment/rune` | ルーン解除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| GET `/api/equipment/loadouts?account_id={account_id}` | 装備プリセット一覧取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| GET `/api/equipment/loadouts/{loadoutId}` | 装備プリセット取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| POST `/api/equipment/loadouts` | 装備プリセット作成 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| PUT `/api/equipment/loadouts/{loadoutId}` | 装備プリセット更新 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| DELETE `/api/equipment/loadouts/{loadoutId}` | 装備プリセット削除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| POST `/api/equipment/loadouts/{loadoutId}/activate` | 装備プリセット有効化 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| GET `/api/equipment/loadouts/{loadoutId}/slots` | 装備プリセットスロット一覧取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| PUT `/api/equipment/loadouts/{loadoutId}/slots` | 装備プリセットスロット登録・更新 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| DELETE `/api/equipment/loadouts/{loadoutId}/slots/{slotType}/{slotIndex}` | 装備プリセットスロット解除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| POST `/api/rune/instances` | ルーンインスタンス作成 | `00_docs/20_API設計書/feature/15-rune/3-エンドポイント仕様/15_3.00-索引.md` |
| GET `/api/rune/instances/{instanceId}` | ルーンインスタンス取得 | `00_docs/20_API設計書/feature/15-rune/3-エンドポイント仕様/15_3.00-索引.md` |
| GET `/api/recipe` | レシピ一覧取得 | `00_docs/20_API設計書/feature/12-recipe/3-エンドポイント仕様/12_3.00-索引.md` |
| GET `/api/recipe/{recipeId}` | レシピ取得 | `00_docs/20_API設計書/feature/12-recipe/3-エンドポイント仕様/12_3.00-索引.md` |
| GET `/api/class` | クラス一覧取得 | `00_docs/20_API設計書/feature/10-class/3-エンドポイント仕様/10_3.00-索引.md` |
| GET `/api/class/{classId}` | クラス取得 | `00_docs/20_API設計書/feature/10-class/3-エンドポイント仕様/10_3.00-索引.md` |
| GET `/api/skill` | スキル一覧取得 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.00-索引.md` |
| GET `/api/skill/{skillId}` | スキル取得 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.00-索引.md` |
| GET `/api/buff` | バフ一覧取得 | `00_docs/20_API設計書/feature/05-buff/3-エンドポイント仕様/05_3.00-索引.md` |
| GET `/api/buff/{buffId}` | バフ取得 | `00_docs/20_API設計書/feature/05-buff/3-エンドポイント仕様/05_3.00-索引.md` |
| GET `/api/loot/pool` | ルートプール一覧取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/pool/{poolId}` | ルートプール取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/table` | ルートテーブル一覧取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/table/{tableId}` | ルートテーブル取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| POST `/api/master-data/seed` | filebase から MasterDataDB を同期 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/master-data/seed-runs` | Seeder 実行履歴取得 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/master-data/health` | MasterDataDB の参照可能状態取得 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |

### Scalar API UI

サーバー起動後、以下の URL でインタラクティブな API ドキュメントを確認できます。

```text
http://localhost:{port}/scalar
```

OpenAPI スペック(JSON):

```text
http://localhost:{port}/openapi/v1.json
```

## AstralRecord Plugin

`10_plugin/AstralRecord/` は Minecraft MMO RPG「AstralRecord」のサーバープラグインです。コード追加・修正全般は `$astralrecord-code` を使い、実装時の詳細ルールは `.codex/skills/astralrecord-code/references/plugin-code.md` を正本として扱います。

### 技術スタック

| 分類 | 内容 |
|:--|:--|
| 言語 | Java / Kotlin |
| API | PaperAPI および関連ライブラリ |
| データベース | SQL Server (T-SQL) |
| ORM | JetBrains Exposed |
| 外部 Web API | AstralRecord API（`E:\AstralRecord-Workspace\20_api\AstralRecordApi`） |

### データ管理方針

データの性質に応じて保存先を厳格に区別します。ただし、DB への直接接続・直接アクセスは原則禁止し、AstralRecord API 経由でデータを操作することを基本方針とします。

| データ種別 | 内容例 | 管理手法 | ディレクトリパス |
|:--|:--|:--|:--|
| 動的データ | プレイヤーレベル、経験値、所持アイテム、座標など | SQL Server | `E:\AstralRecord-Workspace\40_database` |
| 静的データ | アイテムの基本設定、説明、武器ステータスなど | YAML ファイル | `E:\AstralRecord-Workspace\50_filebase` |

- SQL Server 定義は `E:\AstralRecord-Workspace\40_database` を参照する。
- file 系マスタデータは `E:\AstralRecord-Workspace\50_filebase` を参照する。
- API 仕様はこの README の「AstralRecord API」と `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` を参照する。

### ソースコード構成

`src/main/java`・`src/main/kotlin` 配下のパッケージは責務ごとに分離します。

| ディレクトリ | 役割 |
|:--|:--|
| `core/` | コマンド・イベント登録の起点だけを置く。ロジックは置かない |
| `feature/<feature>/` | 機能単位の実装を置く。新機能は必ずこの配下で完結させる |
| `infrastructure/` | 横断的な技術要素のみ。ゲームロジックは含めない |
| `src/main/resources/` | `plugin.yml`・`config.yml`・properties 類 |

### 実装方針

- ビジネスロジックは Java を優先する。
- Kotlin は `data class` を使う Model、Repository 実装、GUI / View などで選んでよい。
- 新規追加時は、対象ディレクトリ内の既存ファイルの言語と責務分離を崩さない。
- Bukkit / Paper API のスレッド制約を守る。
- プレイヤー表現は原則 `AstPlayer` を使い、`org.bukkit.entity.Player` を直接引き回すのは最小限に留める。

### コーディングルール

- 機能追加は `feature/<feature>/` 配下に閉じる。
- DB アクセスは repository 層に閉じる。生 SQL や ORM を feature 側に直接書かない。
- API・DB 契約に関わる変更は、関連プロジェクト（`20_api/AstralRecordApi` / `40_database` / `50_filebase`）の同期更新の要否を必ず確認する。
- enum で管理済みの値（種別名・表示名・コードなど）の文字列ハードコーディングは禁止。
- `System.out.println` の使用は禁止。ログ出力は既存の logger 経由で行う。
- ログメッセージの追加・変更は、文字列を直書きせず `LogId` と `logger.properties` をセットで更新する。
- プレイヤー向けメッセージの追加・変更は、文字列を直書きせず `MsgId` と `player.properties` をセットで更新する。

### JavaDoc / KDoc 規約

- メソッドを新規作成または仕様変更した場合は、日本語の JavaDoc / KDoc を必ず追加する。
- `public` 修飾子を持ち、かつ外部（他クラス・他パッケージ）から呼び出されるメソッドは JavaDoc / KDoc の記載を必須とする。
- 引数・戻り値・スローし得る例外・前提条件・副作用を明記する。
- シグネチャや仕様を変更する場合は、JavaDoc / KDoc も同時に更新する。

### ステータスシステム

ステータスの詳細仕様は README に重複記載せず、正本は `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status` とします。攻撃は近接・間接・魔法の 3 種別で構成され、武器には攻撃力（ATTACK）のみをステータスとして持たせ、職業ごとに内部でダメージ計算を行います。
