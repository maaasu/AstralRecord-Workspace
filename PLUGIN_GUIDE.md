# AstralRecord Plugin

`10_plugin/AstralRecord/` は Minecraft MMO RPG「AstralRecord」のサーバープラグインです。コード追加・修正全般は `$astralrecord-code` を使い、実装時の詳細ルールは `.codex/skills/astralrecord-code/references/plugin-code.md` を正本として扱います。

## 技術スタック

| 分類 | 内容 |
|:--|:--|
| 言語 | Java / Kotlin |
| API | PaperAPI および関連ライブラリ |
| データベース | AstralRecord API を介した SQL Server（T-SQL） |
| 外部 Web API | AstralRecord API (`E:\AstralRecord-Workspace\20_api\AstralRecordApi`) |

## データ管理方針

データの性質に応じて保存先を厳格に区別します。Plugin は SQL Server に直接接続せず、動的データも静的マスタも AstralRecord API 経由で取得・更新します。

| データ種別 | 内容例 | 管理場所 | ディレクトリパス |
|:--|:--|:--|:--|
| 動的データ | プレイヤーレベル、経験値、所持アイテム、座標など | SQL Server | `E:\AstralRecord-Workspace\00_docs\40_Database設計書` |
| 静的データ | アイテムの基本設定、説明、武器ステータスなど | YAML / JSON ファイル | `E:\AstralRecord-Workspace\40_filebase` |

- SQL Server 定義は `E:\AstralRecord-Workspace\00_docs\40_Database設計書` を参照する。
- file 系マスタデータは `E:\AstralRecord-Workspace\40_filebase` を参照する。
- API 仕様は [API_GUIDE.md](API_GUIDE.md) と `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` を参照する。

移行対象の SkillTree node/structure、shop、recipe、quest/quest_board、gathering/gathering spawner、mob spawner、dungeon は API の `GET /api/master-data/runtime-filebase` から一括取得する。Plugin は起動時に非同期で取得し、マスタ再読込時にも取得し直す。元の YAML/JSON テキストをメモリ上で既存 parser に渡し、Plugin 側へマスタのファイルコピーを作らない。取得・解析・検証を終えた準備 snapshot だけを一括公開し、失敗時は旧 snapshot を維持する。Plugin 自身の `config.yml`、world/map、管理者が設置した配置情報などのローカルファイルは引き続き Plugin が管理する。

Plugin 設定の `database.sqlserver`、`database.file`、`database.pool` と SQL/File/YamlDb 接続基盤は使用しない。API 側の `FileDatabase:RootPath` は維持し、先に API と filebase を配置してから Plugin を展開する。各 Minecraft サーバーへ移行対象マスタを配布する必要はない。item/skill の既存 API と、Plugin が公開済み SkillTree runtime snapshot を API へ登録する世代運用は維持する。

## マスターデータ自動再読込

- Plugin は `masterData.autoReload.enabled` が有効な場合、起動完了後に非同期 Scheduler で `GET /api/master-data/health` の定期監視を開始する。監視間隔は `masterData.autoReload.pollIntervalSeconds` とし、1秒未満は1秒へ補正する。
- HTTP クライアント、APIキー、タイムアウト、TLS は `ApiRequestUtil` の共有設定を使い、Bukkit メインスレッドを待機させない。
- 初回の health 応答は比較基準としてだけ記録し、再読込を開始しない。以後、`lastSeedRunStatus = SUCCEEDED`、`lastSeedRunId != null`、`lastSucceededAt != null` を満たす未適用の Seeder 実行だけを対象とする。`RUNNING`、`FAILED`、null ID、不完全な成功応答は再読込しない。
- 再読込は `AstralRecord.reloadMasterData()` だけを入口とし、同じ Seeder 実行の成功済み再読込を重複させない。失敗した再読込は適用済みにせず、後続の定期監視で再試行できる状態を維持する。
- 再読込revisionは全snapshotの公開後にactivation処理を試行し終えた時点で進める。個別activationの例外は既存どおり警告ログを記録して再読込処理を継続するため、revisionは全activation成功の保証ではない。
- 完了結果には公開件数と、ペット・NPC・採集・ワールドのうち実行時反映に失敗した対象を試行順に保持する。部分反映失敗時は、手動再読込の実行者へ通常完了の代わりに警告を表示し、自動再読込ではオンラインの管理者（実効権限99）へ対象をまとめて一度通知する。個別例外の詳細は警告ログに残し、部分反映失敗だけを理由に自動再試行は行わない。
- 再読込中に別の成功済み Seeder 実行を確認した場合は開始を保留する。実行中の再読込が完了した後に最新 health を再取得し、未適用であれば追走する。先行する手動再読込を待った場合も、その再読込が検知済み Seeder を含む保証がないため、完了後に再確認する。
- API障害、タイムアウト、HTTPエラー、JSON解析失敗はログへ記録し、サーバー処理を停止せず次回監視を継続する。Plugin停止時は定期 task と処理中の health 要求を停止し、完了 callback から新しい処理を開始しない。
- `/masterdata reload` の手動運用は自動監視とは独立して維持する。

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
- データアクセスは repository 層に閉じる。Plugin から SQL Server へ直接接続せず、生 SQL や ORM を使用しない。
- API・DB 契約に関わる変更は、関連プロジェクト（`20_api/AstralRecordApi` / `00_docs/40_Database設計書` / `40_filebase`）との同期更新の要否を必ず確認する。
- enum で管理済みの値（種別名・表示名・コードなど）の文字列ハードコーディングは禁止。
- `System.out.println` の使用は禁止。ログ出力は既存の logger 経由で行う。
- ログメッセージの追加・変更は、文字列を直書きせず `LogId` と `logger.properties` をセットで更新する。
- プレイヤー向けメッセージの追加・変更は、文字列を直書きせず `MsgId` と `player.properties` をセットで更新する。
- 通常の AstralRecord item tooltip では、共通 Lore のカテゴリ表示の右へ ` | <item ID>` を濃い灰色で表示する。それ以外の GUI 名・GUI lore・プレイヤー向けメッセージでは、item ID・master ID・status ID などの内部識別子を表示してはならない。マスタの表示名を使用し、解決不能時は ID を代替表示せず「未登録の素材」などの汎用表示と操作不可の案内を使う。管理者向け機能では必要に応じて内部 ID を表示できる。
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
- `player.properties` のプレースホルダーへカラーコード付きのアイテム名などを代入する場合は、置換値の直後に `&r` を置き、続く本文へ意図した色・装飾を再指定する。置換値のカラーコードがプレースホルダー外の本文へ侵食しない定義にする。

## テスト運用ルール

- 恒久テストは、採用済み設計契約であることに加え、コンテンツ非依存の共通ロジックであり、疑似サーバーを起動せず決定的に検証でき、不具合時の影響が大きいものに限る。対象は、純粋な計算、入力正規化、共通の状態遷移・不変条件、冪等性、補償、データ保全、権限境界とする。
- 個別スキル・個別Mobスキル・ボスギミック・アイテム・クエストなどのコンテンツ固有ロジックは、倍率、射程、対象数、クールダウン、個別params、ID一覧を含めて恒久テストにしない。表示文言、Lore、GUI配置、アイコン、particle、sound、演出、視認性も恒久テストの対象外とする。
- 共通の文字列parser・正規化・プレースホルダー展開などは、表示コピーではなく変換ロジックの不変条件を検証する場合に限り恒久テストにできる。
- 個別コンテンツの切り分けや疑似サーバーでの再現が必要な場合は、一時テストとして追加し、確認後に削除する。見た目・操作感・演出は実サーバーまたは実クライアント、マスタ内容は専用validatorで確認する。
- MockBukkit等の疑似サーバーを起動するテストは `AdHoc<目的>Test` または `<目的>OneShotTest` の一時診断に限り使用できる。実行に必要な共通基盤や依存関係も一時変更として扱い、結果確認後にすべて削除し、テストコード・support source・ビルド依存をコミットしない。恒久化が必要な契約はBukkit adapterから純ロジックを分離し、疑似サーバーを使わないJUnitテストへ書き直す。
- 通常のPluginテストと一時的な疑似サーバーテストは、本番の `<task-root>/40_filebase` を読み込まず、参照せず、接続しない。ロジックがマスタ定義に依存する場合は、必要な最小項目をテスト内の固定fixtureまたはtest doubleとして定義して実行する。
- YAML読込機構そのものを検証するテストに限り、`@TempDir` 等で作成したテスト専用入力を使ってよい。本番マスタファイルを入力にしてはならない。

## スキル魔法陣の共有状態

- `SkillMagicCircleRegistry` は発動者UUIDごとに、論理的に現存するスキル魔法陣の個体を数える。1回の登録は一意の魔法陣UUIDを返し、同じ発動者の複数登録はそれぞれ1個として数える。別の発動者の個数には影響しない。
- 魔法陣の終了時は登録時のUUIDで解除する。同じUUIDの重複解除は個数を変えない。全消去後は、すべての発動者の個数が0になる。
- 持続する魔法陣は設置後から終了まで登録し、タスクの満了・中断、接触による消費、所有者のライフサイクル解除、プラグイン停止で解除する。発動時だけの瞬間演出や、単純な範囲表示リングは登録しない。

## 共通基盤の設定スナップショットと入力正規化

### APIマスタ原稿スナップショット

- `GET /api/master-data/runtime-filebase` の一括応答を既存 parser に渡す間は、準備済みデータを呼出スレッドの thread-local にだけ公開し、共有済み snapshot を変更しない。
- 正常終了・例外終了にかかわらず、準備処理後は直前の thread-local を復元し、直前値がない場合は除去する。
- 全対象の取得・解析・検証に成功した場合だけ共有 snapshot を公開する。失敗時に空の成功結果へ置き換えず、共有 cache を変更せずに直前の公開済み snapshot を保つ。

### Legacy color code 正規化

- filebase や設定由来の表示文字列に含まれる `&` 形式の legacy color code は `ColorCodeUtil` で `§` 形式へ変換してから表示する。
- 入力が `null` または空白なら fallback 文字列を使用する。
- `Component` 変換時に fallback color を指定した場合、入力側で色が指定されていない部分だけへ fallback color を適用し、明示済みの色は維持する。

### Material 名解決

- filebase や設定由来の Material 名は前後空白を除去し、`Locale.ROOT` で大文字へ正規化して `MaterialNameResolver` で解決する。
- 現行 Paper 名は `Material.matchMaterial` で解決し、Minecraft 更新前の互換名 `CHAIN` は `IRON_CHAIN` として扱う。
- 空値または未知の Material 名は推測で別 Material へ置換せず `null` を返し、呼び出し側の設計済み fallback に委ねる。

## スキルツリー世代を含むmaster公開

health監視からのauto reloadは継続する。スキルツリーのruntime登録はmaster公開完了後だけ行い、公開中は保留する。tree/class/参照skillの公開失敗は旧snapshotへ復元し、混在世代をWebへ配信しない。各serverの反映完了は個別runtimeで確認する。マスタ再読込処理の完了ごとに`publicationRevision`を進め、世代IDが同じ場合も新revisionをruntimeへ登録する。このrevisionは個別activationの成功までは保証しない。Seeder成功だけで全serverが更新済みとは判断しない。運用手順は`00_docs/20_API設計書/feature/20-skilltree/20_5.00-安全編集の導入と検証.md`を参照する。

### スキルツリーruntime同期

5秒周期のオンラインaccount評価を100tickへ分散し、10tick窓ごと・最大32件で既存sessionの更新、viewのACK、pending操作照会を1 HTTP要求にまとめる。32件を超えた要求も入力順に排出し、残りを周期末で送る。停止時は未送信bufferを排出して対応accountのinflightを解除する。通信中accountを重複送信せず、終了・失効sessionを取得し直さない。master公開中または未保存stateではviewを送らずsessionだけ更新する。応答のaccount集合が入力と違う場合はACKを採用しない。通信失敗・view拒否時は次の周期で全体viewを再送する。

master公開開始と停止は同期epochを進め、開始前に捕捉した未送信要求と遅延ACKを採用しない。APIのbatch/ACKエンドポイントが404/405の場合だけ旧session更新・全体view・pending照会へ戻る。旧APIでは最初の未対応検出時からaccountごとの独立した非同期taskで送信し、各accountのACK・失敗・inflight解除を他accountの完了から独立させる。遅延・500・timeoutのaccountがあっても正常accountのrenewと次周期の同期を妨げない。未対応エンドポイントは1分後に再確認する。権限拒否や通信障害を未対応と推測してfallbackしない。

runtime要求は専用runtimeキーを送る。view省略は同じaccount sessionのACK済みJSON全体と新しい評価が同一の場合だけ行い、UTF-8 JSON全体のSHA-256と世代・保存version・評価fingerprint・編集可否・単調sequenceを送る。APIは保存済みJSONと照合した場合だけview鮮度を更新する。位置が変わった場合もJSONが変わるので全体を送る。各accountのrenewed/viewAcceptedを個別に確認し、bulk成功後のACK・claim・inflight解除もaccountごとの非同期taskへ分離する。一方のclaim待機で他accountのACK・完了・次周期のrenewを止めない。bulk HTTPは1要求を維持する。viewのACK以外の応答を捨てる軽量登録には`view/ack`を使う。秘密lease tokenを診断文字列へ含めない。
