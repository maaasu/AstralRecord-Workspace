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
| 静的データ | アイテムの基本設定、説明、武器ステータスなど | YAML / JSON ファイル | `E:\AstralRecord-Workspace\40_filebase` |

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
- GUI名・GUI lore・プレイヤー向けメッセージでは、item ID・master ID・status ID などの内部識別子を表示してはならない。マスタの表示名を使用し、解決不能時は ID を代替表示せず「未登録の素材」などの汎用表示と操作不可の案内を使う。内部 ID の表示はログまたは管理者向け機能に限定する。
- GUI に共通のプレイヤーインベントリ挙動を追加・変更する場合は、個別 GUI の open/click/close に重複実装せず shared 側の holder / click support / service に寄せる。
- ホットバーの閉じるアイコンとインベントリ切替を使う GUI は `io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder` を holder に実装し、クリック処理は `HotbarShortcutClickSupport` に委譲する。個別の判定リストや GUI ごとの手作業設定を増やさない。

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

ステータスの詳細仕様は本ガイドに重複記載せず、正本は `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\feature\07-status` とします。近接・間接・魔法の攻撃種別に応じたダメージ計算は、Plugin 内部で行います。

ステータスID・日本語表示名・カテゴリ・表示書式の正本は`E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`です。Pluginの`StatusType.kt`は`.\60_tool\generate-status-types.ps1`で生成し、直接編集しません。ダメージ計算などのPlugin固有ロジックは従来どおりPluginソースと本設計書で管理します。

マスターデータタグのID・日本語表示名・適用対象の正本は`E:\AstralRecord-Workspace\40_filebase\76.shared.tag\v1.tags.yml`です。PluginでタグIDにより処理を分岐するときは`.\60_tool\generate-tag-types.ps1`が生成する`MasterTagIds.java`の定数を使用し、文字列を直接記述しません。

## パーティクル表示共通ルール

- Plugin production source のパーティクル表示は `io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService` を経由し、同 service 以外から `World#spawnParticle(...)` / `Player#spawnParticle(...)` を直接呼び出さない。
- `Particle.valueOf(...)` による直接解決は行わず、共有 resolver を使う。
- 固定の `Particle.*` 定数、共通の別名、既定値は `io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions` だけに定義する。
- `SharedParticleDefinition.data` は `Particle#getDataType()` が `Void` の場合は `null`、それ以外では要求型の非 `null` instance とする。

## メッセージ管理共通ルール

- プレイヤー向けメッセージ送信の正本は `io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService` とする。
- `Player#sendMessage(...)`・`AstPlayer#sendMessage(...)` の新規利用は禁止し、既存経路を変更する場合も `PlayerMessageService` 経由へ寄せる。
- システムメッセージは `PlayerMessageService` が付与する共通タグ付き形式を維持する。全体チャット・パーティーチャット・ダイレクトメッセージも同サービス経由で管理する。
- 所持金（ゴールド）を消費する操作の成功通知には、確定した実消費額を `（消費ゴールド: {0}）` の形式で必ず含める。新規・変更時は `player.properties` のプレースホルダーと全呼び出し側の引数を確認し、金額をメッセージへ直書きしない。

## テスト運用ルール

- ボス固有のギミック（フェーズ、周期処理、特殊攻撃など）の恒久テストは原則作成しない。意図どおりの体験かどうかは、実際にプレイヤーが操作して確認する。
- ボス制作中に切り分けのため一時テストが必要だと判断した場合だけ追加し、確認後は必ず削除する。共通のモデル・挑戦進行・フィールド管理など、ボス固有ギミックではない契約テストはこの制限の対象外とする。
- 通常のPluginテストは、本番の `<task-root>/40_filebase` を読み込まず、参照せず、接続しない。ロジックがマスタ定義に依存する場合は、必要な最小項目をテスト内の固定fixtureまたはtest doubleとして定義して実行する。
- YAML読込機構そのものを検証するテストに限り、`@TempDir` 等で作成したテスト専用入力を使ってよい。本番マスタファイルを入力にしてはならない。

## 共通基盤の設定スナップショットと入力正規化

### Filebase YAML 設定スナップショット

- `YamlDbConfigUtil.loadSnapshot(rootDir)` は `<rootDir>/config.yml` を読み、共有 cache へ公開しない。
- `YamlDbConfigUtil.withSnapshot` は準備済み `YamlDbConfig` を呼出スレッドと action の実行中だけ公開し、共有済み cache を変更しない。
- action の正常終了・例外終了にかかわらず、実行後は直前の thread-local snapshot を復元し、直前値がない場合は thread-local を除去する。
- `getConfig` は action 中の準備済み snapshot、共有 cache、再読込の順で解決する。

### Legacy color code 正規化

- filebase や設定由来の表示文字列に含まれる `&` 形式の legacy color code は `ColorCodeUtil` で `§` 形式へ変換してから表示する。
- 入力が `null` または空白なら fallback 文字列を使用する。
- `Component` 変換時に fallback color を指定した場合、入力側で色が指定されていない部分だけへ fallback color を適用し、明示済みの色は維持する。

### Material 名解決

- filebase や設定由来の Material 名は前後空白を除去し、`Locale.ROOT` で大文字へ正規化して `MaterialNameResolver` で解決する。
- 現行 Paper 名は `Material.matchMaterial` で解決し、Minecraft 更新前の互換名 `CHAIN` は `IRON_CHAIN` として扱う。
- 空値または未知の Material 名は推測で別 Material へ置換せず `null` を返し、呼び出し側の設計済み fallback に委ねる。
