# AstralRecord — 開発ガイドライン

Minecraft MMO RPG プラグイン「AstralRecord」の開発に関する方針・構成をまとめたドキュメントです。
GitHub Copilot・JetBrains AI Assistant・Junie 向けの補助プロンプトは `.agents/prompts/` で管理し、コード実装の AI 手順は workspace-local skill `$astralrecord-code` で管理します。

---

## プロジェクト概要

本プロジェクトは Minecraft の MMO RPG「**AstralRecord**」のサーバープラグインです。

---

## 技術スタック

| 分類             | 内容                                                                   |
|:---------------|:---------------------------------------------------------------------|
| **言語**         | Java / Kotlin                                                        |
| **API**        | PaperAPI およびその関連ライブラリ                                                |
| **データベース**     | SQL Server (T-SQL)                                                   |
| **ORM**        | Jetbrains Exposed                                                    |
| **外部 Web API** | AstralRecord API（`E:\AstralRecord-Workspace\20_api\AstralRecordApi`） |

---

## データ管理方針

データの性質に応じて保存先を厳格に区別し、適切なパスを参照すること。
ただし、**DB への直接接続・直接アクセスは原則禁止**とし、後述の「AstralRecord API」経由でデータを操作することを基本方針とする。

| データ種別     | 内容例                        | 管理手法           | ディレクトリパス                                |
|:----------|:---------------------------|:---------------|:----------------------------------------|
| **動的データ** | プレイヤーレベル、経験値、所持アイテム、座標など   | **SQL Server** | `E:\AstralRecord-Workspace\40_database` |
| **静的データ** | アイテムの基本設定（名前、説明、武器ステータス）など | **YAMLファイル**   | `E:\AstralRecord-Workspace\50_filebase` |

- **SQL Server**: 頻繁に更新・書き込みが発生するプレイヤー固有のデータ。
- **YAML**: ソースコードから読み取られるマスタデータ（アイテム定義など）。

テーブル定義・YAML スキーマ定義など、データ構造を確認する場合は以下を参照すること。

- **SQL Server 定義**: `E:\AstralRecord-Workspace\40_database`
- **file 系マスタデータ**: `E:\AstralRecord-Workspace\50_filebase`

---

## AstralRecord API

本プロジェクトでは、DB（静的データ・動的データを含む）へのアクセスを **AstralRecord API** 経由で行うことを基本方針とします。
DB に直接接続してデータを操作するのではなく、API を呼び出してデータを取得・操作する形を原則とすること。

- **プロジェクト**: `E:\AstralRecord-Workspace\20_api\AstralRecordApi`
- **API ドキュメント**: `E:\AstralRecord-Workspace\20_api\AstralRecordApi\docs\api\` 配下を参照
- **ランタイム**: .NET 10 / ASP.NET Core Web API

### エンドポイント

最新のエンドポイント一覧・仕様は必ず以下を参照すること。エンドポイントをソースコードに記述する前に確認すること。

- `E:\AstralRecord-Workspace\20_api\AstralRecordApi\README.md` — エンドポイント一覧
- `E:\AstralRecord-Workspace\20_api\AstralRecordApi\docs\api\` — 各エンドポイントの詳細仕様

### データアクセス原則

- **動的データ**（プレイヤー情報等）および**静的データ**（アイテム定義等）のいずれも、AstralRecord API 経由で取得・操作すること。
- Jetbrains Exposed や YAML ファイルへの直接アクセスは、API では提供できない内部処理（プラグイン固有の初期化・内部キャッシュ更新など）に限定する。
- API の仕様変更・エンドポイント追加が発生した場合は、必ず `20_api/AstralRecordApi/README.md` および `20_api/AstralRecordApi/docs/api/` を確認してから実装すること。

---

## プロジェクト構造と適用ルール

各ディレクトリの役割と、変更を行う際に参照すべきルールを以下に示す。
**コード追加・修正全般は `$astralrecord-code` を使うこと。ログ、メッセージ、DB 連携など専用の補助プロンプトがある場合だけ追加で参照すること。**

| ディレクトリ                                  | 役割                                      | 参照ルール                           |
|:----------------------------------------|:----------------------------------------|:--------------------------------|
| `E:\AstralRecord-Workspace\50_filebase` | 静的データ（YAML）                             | `.agents/prompts/database.md`   |
| `E:\AstralRecord-Workspace\40_database` | 動的データ（SQL）                              | `.agents/prompts/database.md`   |
| `src`                                   | ソースコード（Java / Kotlin）                   | `$astralrecord-code`            |
| `src`（ログ出力）                             | ログ出力・LogId・logger.properties 定義         | `.agents/prompts/logger.md`     |
| `src`（プレイヤーメッセージ）                       | プレイヤー向けメッセージ・MsgId・player.properties 定義 | `.agents/prompts/player_msg.md` |

---

## ソースコード構成（src/）

`src/main/java`・`src/main/kotlin` 配下のパッケージは、責務ごとに以下の方針で分離する。

| ディレクトリ                | 役割                                  |
|:----------------------|:------------------------------------|
| `core/`               | コマンド・イベント登録の起点だけを置く。ロジックは置かない      |
| `feature/<feature>/`  | 機能単位の実装を置く。新機能は必ずこの配下で完結させる         |
| `infrastructure/`     | 横断的な技術要素のみ。ゲームロジックは含めない             |
| `src/main/resources/` | `plugin.yml`・`config.yml`・properties 類 |

---

## 実装方針

### 言語選定

- ビジネスロジックは **Java** を優先する。
- 次の用途では **Kotlin** を選んでよい。
  - `data class` を使う Model
  - Repository 実装
  - GUI / View
- 新規追加時は、対象ディレクトリ内の **既存ファイルの言語と責務分離を崩さない**。

### API / スレッド

- Bukkit / Paper API のスレッド制約（メインスレッド要求・非同期不可など）を守る。

### プレイヤー表現

- プレイヤー表現は原則 `AstPlayer` を使う。
- `org.bukkit.entity.Player` を直接引き回すのは最小限に留める。

---

## コーディングルール

- 機能追加は `feature/<feature>/` 配下に閉じる。
- DB アクセスは repository 層に閉じる。生 SQL や ORM を feature 側に直接書かない。
- API・DB 契約に関わる変更は、関連プロジェクト（`20_api/AstralRecordApi` / `40_database` / `50_filebase`）の同期更新の要否を必ず確認する。
- **enum で管理済みの値**（種別名・表示名・コードなど）の文字列ハードコーディングは禁止。
- `System.out.println` の使用は禁止。ログ出力は既存の logger 経由で行う。
- ログメッセージの追加・変更は、文字列を直書きせず `LogId` と `logger.properties` を **セットで** 更新する。
- プレイヤー向けメッセージの追加・変更は `MsgId` と `player.properties` を **セットで** 更新する。
  - `sendInfo` / `sendSuccess` / `sendError` / `sendMessage` に文字列リテラルを直接渡さない。

---

## JavaDoc / KDoc 規約

- メソッドを新規作成または仕様変更した場合は、**日本語の JavaDoc / KDoc を必ず追加する**。
- **`public` 修飾子を持ち、かつ外部（他クラス・他パッケージ）から呼び出されるメソッドは JavaDoc / KDoc の記載を必須とする。**
  - 引数・戻り値・スローし得る例外・前提条件・副作用を明記する。
  - シグネチャや仕様を変更する場合は、JavaDoc / KDoc も同時に更新する。
- 内部利用のみの `public` メソッドであっても、外部から利用される可能性があるなら同様に記載することを推奨する。

---

## ステータスシステム

本プロジェクトのステータスシステムは、本格的 MMO RPG として設計されています。
プレイヤーのステータスはレベル・装備・バフ等により加算・乗算補正が適用され、最終値が決定します。
リソースは **HP / MP / エネルギー** の 3 系統です。

### 攻撃種別と職業スケーリング

攻撃は以下の 3 種別で構成され、それぞれが `StatusType` として定義されています。
**武器には「攻撃力（ATTACK）」のみ**をステータスとして持たせ、
職業ごとに内部でダメージ計算を行います。

| 攻撃種別   | StatusType       | スケーリング能力値         | 説明           |
|:-------|:-----------------|:------------------|:-------------|
| 近接攻撃   | `MELEE_ATTACK`   | 筋力（STRENGTH）      | 剣・斧等の近接武器    |
| 間接攻撃   | `RANGED_ATTACK`  | 器用さ（DEXTERITY）    | 弓・投擲等の遠距離武器  |
| 魔法攻撃   | `MAGIC_ATTACK`   | 知力（INTELLIGENCE）  | 魔法・杖等の魔法武器   |

### ステータス種別一覧（29種）

| カテゴリ    | ステータス                                                                    | 概要                      |
|:--------|:-------------------------------------------------------------------------|:------------------------|
| リソース    | MAX_HEALTH / MAX_MANA / MAX_ENERGY                                       | 最大 HP / MP / EN         |
| 基本能力値   | STR / DEX / INT / VIT / AGI / LUK                                        | 攻撃種別スケーリング・派生ステータスに影響   |
| 攻撃      | ATK / MELEE / RANGED / MAGIC / CRIT / S.CRIT / FINAL_DMG / ACC / ATK_SPD | 攻撃力・種別攻撃力・会心・超会心・最終ダメージ |
| 防御      | DEF / MDEF / EVA                                                         | 物理防御・魔法防御・回避            |
| ユーティリティ | HP_REGEN / MP_REGEN / EN_REGEN / MOV_SPD / CDR                           | 回復・移動速度・CD 短縮           |

> **詳細仕様**: `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status` を参照してください。

---

## status 記載方針（README）

- `status` の詳細仕様は本READMEに重複記載しない。
- README には参照先のみを記載し、正本は `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status` とする。

