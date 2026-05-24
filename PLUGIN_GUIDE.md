# AstralRecord Plugin

`10_plugin/AstralRecord/` は Minecraft MMO RPG「AstralRecord」のサーバープラグインです。コード追加・修正全般は `$astralrecord-code` を使い、実装時の詳細ルールは `.codex/skills/astralrecord-code/references/plugin-code.md` を正本として扱います。

## 技術スタック

| 分類 | 内容 |
|:--|:--|
| 言語 | Java / Kotlin |
| API | PaperAPI および関連ライブラリ |
| データベース | SQL Server（T-SQL） |
| ORM | JetBrains Exposed |
| 外部 Web API | AstralRecord API (`E:\AstralRecord-Workspace\20_api\AstralRecordApi`) |

## データ管理方針

データの性質に応じて保存先を厳格に区別します。ただし、DB への直接接続・直接アクセスは原則禁止し、AstralRecord API 経由でデータを操作することを基本方針とします。

| データ種別 | 内容例 | 管理場所 | ディレクトリパス |
|:--|:--|:--|:--|
| 動的データ | プレイヤーレベル、経験値、所持アイテム、座標など | SQL Server | `E:\AstralRecord-Workspace\00_docs\40_Database設計書` |
| 静的データ | アイテムの基本設定、説明、武器ステータスなど | YAML ファイル | `E:\AstralRecord-Workspace\40_filebase` |

- SQL Server 定義は `E:\AstralRecord-Workspace\00_docs\40_Database設計書` を参照する。
- file 系マスタデータは `E:\AstralRecord-Workspace\40_filebase` を参照する。
- API 仕様は [API_GUIDE.md](API_GUIDE.md) と `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` を参照する。

## ソースコード構成

`src/main/java`・`src/main/kotlin` 配下のパッケージは責務ごとに分類します。

| ディレクトリ | 役割 |
|:--|:--|
| `core/` | コマンド・イベント登録の起点だけを置く。ロジックは置かない |
| `feature/<feature>/` | 機能単位の実装を置く。新機能は必ずこの配下で完結させる |
| `infrastructure/` | 横断的な技術要素のみ。ゲームロジックは含めない |
| `src/main/resources/` | `plugin.yml`・`config.yml`・properties 類 |

## 実装方針

- ビジネスロジックは Java を優先する。
- Kotlin は `data class` を使う Model、Repository 実装、UI / View などで選んでよい。
- 新規追加時は、対象ディレクトリ内の既存ファイルの言語と責務分離を崩さない。
- Bukkit / Paper API のスレッド制約を守る。
- プレイヤー表現は原則 `AstPlayer` を使い、`org.bukkit.entity.Player` を直接引き回すのは最小限に留める。

## コーディングルール

- 機能追加は `feature/<feature>/` 配下に閉じる。
- DB アクセスは repository 層に閉じる。生 SQL や ORM を feature 側に直接書かない。
- API・DB 契約に関わる変更は、関連プロジェクト（`20_api/AstralRecordApi` / `00_docs/40_Database設計書` / `40_filebase`）との同期更新の要否を必ず確認する。
- enum で管理済みの値（種別名・表示名・コードなど）の文字列ハードコーディングは禁止。
- `System.out.println` の使用は禁止。ログ出力は既存の logger 経由で行う。
- ログメッセージの追加・変更は、文字列を直書きせず `LogId` と `logger.properties` をセットで更新する。
- プレイヤー向けメッセージの追加・変更は、文字列を直書きせず `MsgId` と `player.properties` をセットで更新する。

## コマンド引数規約

- 番号と名称で列挙管理されている値（権限・モードなど）は、引数を名称（英語ID）の予測変換優先で受け取る。数値指定も許容する。
- プレイヤー（または対象主体）指定は引数の **最後** に置く。
- プレイヤー指定を省略した場合は実行者自身を対象とする。
- プレイヤー指定が省略された状態でコンソールから実行された場合はエラーを返す（`PlayerMsgId.P_5305`）。

## JavaDoc / KDoc 要件

- メソッドを新規作成、または仕様変更した場合は、日本語の JavaDoc / KDoc を必ず追加する。
- `public` 修飾子を持ち、かつ外部（他クラス・他パッケージ）から呼び出されるメソッドは JavaDoc / KDoc の記載を必須とする。
- 引数・戻り値・スローし得る例外・前提条件・副作用を記載する。
- シグネチャや仕様を変更する場合は、JavaDoc / KDoc も同時に更新する。

## ステータスシステム

ステータスの詳細仕様は本ガイドに重複記載せず、正本は `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status` とします。攻撃・防御・魔法の 3 種別で構成され、武器には攻撃力（ATTACK）のみをステータスとして持たせ、職業ごとに内部でダメージ計算を行います。
