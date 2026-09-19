---
name: astralrecord-plugin-test
description: AstralRecord の Minecraft プラグイン `10_plugin/AstralRecord` 向けに、コンテンツ非依存の共通ロジックに限定した JUnit 恒久テスト、使い捨て診断テスト、一時 Purpur/Paper サーバー、実サーバーの複製による統合検証基盤を整備する。設計トレーサビリティを保った小規模テスト整理、AI デバッグ用の最小再現を行い、プラグイン本体の機能仕様変更を主目的としないときに使う。
---

# AstralRecord プラグインテスト

## 基本ルール

`10_plugin/AstralRecord` のテストと検証基盤だけを扱います。恒久テストは採用済み設計契約を入力とし、コンテンツ非依存の共通ロジックに限定し、各テストメソッドから設計箇所と検証契約を追跡可能にします。個別コンテンツや一時的な診断は恒久テストへ混ぜません。主目的が機能実装や仕様変更なら `$astralrecord-code` を使い、このスキルでは小規模な共通ロジックテスト、一時診断、開発サーバー、実サーバーの複製を使う再現手順に集中します。

作業開始時に `git rev-parse --show-toplevel` を実行して現在の作業用チェックアウトを `<task-root>` として解決し、以後の設計入力・ソース・スクリプトはすべて同じ `<task-root>` から読む。メインのワークスペースの固定パスへ読み替えない。実サーバーの外部配置先だけは `scripts/dev-server.config.json` の設定値を正本とする。

## 恒久テストの範囲と本番マスターデータの扱い

- 恒久テストは、コンテンツ非依存で複数機能から再利用される共通ロジックであり、純粋な計算・入力正規化・共通の状態遷移・不変条件・冪等性・補償・データ保全・権限境界のいずれかを守るものに限る。決定的に検証でき、不具合時の影響が大きい、または手作業で見逃しやすいことも必須とする。
- 個別スキル・個別モブスキル・ボスギミック・アイテム・クエストの固有ロジック、倍率・射程・対象数・クールダウン・個別パラメーター・ID一覧・マスタ値は恒久テストにしない。
- 表示文言、説明文、GUI配置、アイコン、パーティクル、サウンド、演出、視認性も恒久テストにしない。文字列を使うテストでも、共通パーサー・正規化・変換の不変条件を検証するものは対象にできる。
- 個別コンテンツの切り分けは一時テスト、ファイルベース 検証ツール、実サーバーまたは実クライアント確認で行う。一時テストは確認後に削除する。
- MockBukkitは、データ保全、権限、不可逆な状態遷移など、Bukkit境界を分離できない共通契約の例外的確認に限る。それ以外はBukkit アダプターを薄くし、抽出した純ロジックをJUnitで検証する。
- 通常のプラグイン JUnit / MockBukkitテストでは、本番 `<task-root>\40_filebase` を読み込まず、参照せず、接続しない。マスタ形状が必要なら、テスト内の最小固定テスト用データ、インラインのデータ、またはテストダブルを使う。本番ファイルから期待値を組み立てない。
- YAML読込機構自体のテストで `@TempDir` 等の隔離入力を使うことは許可するが、本番マスタファイルを入力にしてはならない。実サーバーの複製を使う明示的な統合検証は、通常の恒久テストとは別の検証層として扱う。

## 必須コンテキスト

1. `<task-root>\AGENTS.md`
2. `<task-root>\README.md` の 「AstralRecord Plugin」セクション
3. `<task-root>\PLUGIN_GUIDE.md`
4. `<task-root>\.codex\skills\astralrecord-code\references\plugin-code.md`
5. `<task-root>\10_plugin\AstralRecord\scripts\dev-server.config.json`
6. `<task-root>\00_docs\10_Plugin設計書\README.md`

対象が `10_plugin/AstralRecord` 以外なら、このスキルは使わず対象プロジェクト向けスキルに切り替えます。

## 手順

1. 検証目的を分類する
   - 採用済み設計契約であり、上記の恒久テスト適格性をすべて満たす: 恒久テストにする
   - 個別コンテンツ、表示、GUI、演出、マスタ値: 恒久テストを追加せず、専用検証ツールまたは手動確認にする
   - 不具合の切り分け、実装中の仮説、設計にない内部詳細を一度だけ確認する: 一時テストにする
   - 適格性を満す共通契約だが設計書にない: 先に設計書を現行仕様へ同期してから恒久テストにする
2. 設計入力を先に読む
   - 恒久テストの期待値を実装コードや既存テストから写さず、許可された設計文書の採用済み記載から決める
   - 対象クラス、近傍の呼び出し元、依存リポジトリ/サービス、関連設定はテスト用データと観測点を決めるために読む
   - `8-実装予定`、`9-未決事項`、レビュー記録、`TODO` は期待値の根拠にしない
3. 検証層を決める
   - 適格性を満す純ロジック確認: `JUnit`
   - データ保全・権限・不可逆な状態遷移でBukkit境界が不可欠: `MockBukkit`
   - Purpur/Paper 固有 API、ライフサイクル、Pathfinder: 一時サーバースクリプト
   - ProtocolLib、実プラグイン構成、プロキシ、ワールドを含む統合: 動作サーバー一式複製
   - クライアント表示、視認性、操作感: 実クライアント確認を残す
4. 配置して実装する
   - テストは `src/test/java/...` に本体と同じパッケージで置く
   - MockBukkit の共通基盤は `src/test/java/.../support` に寄せる。テスト用アノテーションのない補助クラスはトレーサビリティ検証対象外とする
   - 一時サーバースクリプトは `10_plugin/AstralRecord/scripts/` に置く
   - 実サーバー複製の既定値は `scripts/dev-server.config.json` に持つ
   - `ProtocolLib` や実サーバ依存が強い場合は、プラグイン丸ごとロードより対象クラスの分離を優先する
5. 検証して清掃する
   - 恒久テストは対象テスト、トレーサビリティ検証ツール、全テストの順で実行する
   - 一時テストは後述の追加・実行・削除手順を省略しない
   - スクリプトは `-NoStart` 付きで準備まで確認する
   - 実サーバーの複製検証は `-UseLiveServerClone` と `-RefreshLiveServerClone` の必要有無を明示する

## 恒久テストのトレーサビリティ

恒久テストでは、`@Test`、`@ParameterizedTest`、`@RepeatedTest`、`@TestFactory`、`@TestTemplate` を持つ各メソッドの連続アノテーションの並びの直前へ次の Javadoc を付ける。`@DisplayName`、`@Tag`、`@Timeout` 等は Javadoc とテスト用アノテーションの間に置いてよいが、別の宣言や説明文を挟まない。

```java
/**
 * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
 * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス取得
 * 検証契約: 指定したステータス種別について、現在値と最大値を同じスナップショットから返す。
 */
@Test
void returnsCurrentAndMaximumValuesFromOneSnapshot() {
    // ...
}
```

次を必須とする。

- `設計入力:` はリポジトリ相対の `/` 区切りパスにする。許可する文書は `PLUGIN_GUIDE.md` と `00_docs/10_Plugin設計書/**/*.md` だけとする
- `章・見出し:` は対象 Markdown に実在する H1 から対象節までを ` > ` で連結し、各要素へ `#` の見出しレベルを含める。H1 だけで済ませず、契約が書かれた子見出しまで指定する
- `検証契約:` は入力、条件、結果または不変条件が分かる具体的な一文にする。「動作を確認する」のような汎用文にしない
- 一つのメソッドが複数機能の契約を結合して検証する場合は、`設計入力:` と対応する `章・見出し:` の対を空の Javadoc 装飾行以外を挟まず物理的に隣接させ、必要数だけ繰り返す。`検証契約:` はそれらの結合条件が分かる一文にまとめる
- 一つのメソッドは一つの設計契約を検証する。パラメーター化テストとテストファクトリは、全ケースが同じ記載の同じ契約を検証するときだけまとめる
- 設計書名だけ、絶対パス、Windows の `\` 区切り、存在しない見出し、`8` / `9` カテゴリ、レビュー、`TODO` を参照しない
- Kotlin のインポート別名 / typealias をテスト用アノテーションや無効化アノテーションに付けない。`@Disabled` / `@Ignore` に加え、`@DisabledOnOs` / `@EnabledOnJre` など環境条件でスキップし得る JUnit アノテーションで検証ツールや Maven 実行を回避しない
- Maven Surefire の既定命名 `Test*`、`*Test`、`*Tests`、`*TestCase` に一致させる
- `<task-root>\10_plugin\AstralRecord\pom.xml` の `build/testSourceDirectory` を Maven 既定から変更せず、`maven-compiler-plugin` の `testIncludes` / `testExcludes` や Surefire の独自の `includes` / `excludes`、グループ・エンジン フィルター、スキップ設定で恒久テストをコンパイル・実行対象外にしない

リポジトリルートから次を実行し、全メソッドの設計入力と見出し階層を検証する。

```text
python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py
```

この検証ツールはテストソースを変更した場合だけの検査ではない。作業差分が `astralrecord-code/references/plugin-code.md` の「プラグインテストのトレーサビリティゲート」に列挙したテストソース、プラグイン POM、許可設計入力、テスト方針のパスのいずれかを追加・変更・名前変更・削除した場合は、POM・設計書・テスト方針だけの変更でもレビューへの引き渡し前に必ず実行する。プラグインの Shade 出力先がメインのワークスペースに固定されているため、この検査を `mvn verify` で代用しない。

## 一時テストのライフサイクル

設計契約ではない一度限りの診断は、ファイルとクラスを `AdHoc<目的>Test`（既定）または `<目的>OneShotTest` として追加し、恒久テストへ残さない。通常機能名と衝突し得る曖昧な接頭辞は一時診断の識別子にしない。次の順序を守る。

1. `AdHoc<目的>Test` または `<目的>OneShotTest` を追加する
2. `mvn -q -Dtest=<一時テストClass名> test` を実行し、手順1で選んだ実際のクラス名のテストが検出・実行されたことを結果で確認する
3. 診断結果を実装修正または報告へ反映する。恒久契約だと判明した場合は、設計書を更新して通常名の恒久テストへ書き直す
4. 追加した一時テストを削除する
5. `git status --short` で一時ファイルが残っていないことを確認する
6. `python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py` を実行する
7. `mvn -q test` を実行する

一時テストを `@Disabled` にして残す、Surefire 対象外の名前へ変える、補助クラスに移して温存する、設計入力を捏造して恒久化することは禁止する。

## 判断の目安

- 個別スキル、個別モブ、GUI、表示、表示コピー、説明文、演出、マスタ内容のテストを恒久化しない。新規コンテンツ変更の恒久テスト追加は原則0件とする。
- `MockBukkit` で無理に `AstralRecord` 本体をロードしない。`ProtocolLib` や外部依存に引っかかるなら、対象クラスを隔離してにテストする。
- いきなり統合テストに行かず、純ロジック -> MockBukkit -> 開発サーバー -> 実サーバーの複製の順で狭く確認する。
- DB/API/filebase 契約が絡む場合でも、最初の再現はリポジトリモックやテストダブルを優先する。
- Purpur でしか起きない現象は、サーバースクリプトで再現条件を固定し、ログの採取場所を決める。
- 現在の実サーバーのソースルートは `scripts/dev-server.config.json` を正本として扱う。
- `velocityEnabled: true` の環境では `paper-global.yml` などプロキシ設定をスクリプトで再生成しない。実サーバーの複製に含まれる設定をそのまま使う。

## パケット統合自動テスト

ProtocolLib やパケットのみを使った表示の実動作を調べるときは、機能ごとに一時検証用プラグインまたは検証用コマンドを用意し、実サーバーを複製したテストサーバーに配置する。目的は、Codex がプレイヤー接続後にサーバー側で対象動作を再実行し、パケットの送信順・短時間破棄・メタデータ更新などを `logs/latest.log` から反復確認できる状態にすること。

パケット検証処理の標準要件:

- `ProtocolLib` の `PacketAdapter` を `ListenerPriority.MONITOR` で登録し、対象パケット種別と機能固有のエンティティ ID / パケットのフィールド / 識別子を記録する。
- プレイヤーが一度テストサーバーに参加した後、検証処理がオンラインのプレイヤーを使って対象サービス/コマンドをサーバー側で再実行できる入口を持つ。
- 自動実行が危険な場合は `/featureprobe <player>` のような明示コマンドを用意する。
- ログ識別子は機能名を含む安定した接頭辞にする。例: `ACTION_RING_PACKET`, `SKILLTREE_PACKET`, `MOB_NAMEPLATE_PACKET`。
- クライアント側の目視確認を完全には置き換えない。パケット単位の再現証跡として扱う。

### パケットテストボットの利用

プレイヤー接続が必要なパケットの統合検証では、ユーザーの Minecraft クライアント接続の代わりにパケットテストボットを使える。ボットは `minecraft-protocol` のオフライン認証でテストサーバーに参加し、検証用プラグインがオンラインのプレイヤーを対象にサーバー側実行できる状態を作る。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20
```

ログ識別子まで自動判定したい場合は `-ExpectLogPattern` を指定する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20 -ExpectLogPattern "ACTION_RING_PACKET spawn"
```

ボットはパケット単位の自律検証用であり、クライアント側の見た目確認を完全には置き換えない。画面上の配置・視認性・操作感を確認したい場合は実クライアント確認を併用する。

## アクションリングパケット自動テストの例

アクションリングのパケットのみを使った表示を実サーバー寄りに再現するときは、専用補助処理を使う。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\prepare-action-ring-packet-test.ps1 -UseLiveServerClone
```

準備後は次で起動する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\start-dev-server.ps1 -ServerRoot <task-root>\10_plugin\AstralRecord\.dev-server\integration-live-clone-actionring -SkipBuild -Background
```

この補助処理は `ActionRingPacketProbe.jar` を配置し、複製側 SQL Serverを無効化し、ファイルベース を `<task-root>\40_filebase` に向け、`localhost:25578` へ Velocity なしで直接接続できるようプロキシ設定を補正する。

自律的な実動作確認では、プレイヤーが一度テストサーバーに参加する。検証処理はオンラインのプレイヤーを読み取り、`AstPlayerCache` の準備を待って、稼働中 AstralRecord プラグインの `SkillActionRingService.toggle(AstPlayer)` を呼び出し、パケット証跡を `logs/latest.log` に記録する。

確認するログ識別子:

- `ACTION_RING_AUTOTEST opened player=<name>`: 検証処理がオンラインのプレイヤーに対してアクションリングをサーバー側で開いた。
- `ACTION_RING_PACKET spawn id=<id>`: アクションリングのパケットのみを使ったエンティティがクライアントに送信された。
- `ACTION_RING_PACKET_REPRODUCED ... spawn_to_destroy_ms=<ms>`: 設定された再現確認期間内にパケットのみを使ったエンティティが破棄された。

再実行が必要で、プレイヤーがオンラインの場合は次を使う。

```text
/actionringprobe <player>
```

ユーザーの接続なしでアクションリングパケットの自律確認を行う場合は、テストサーバー起動後にボットを参加させる。`ActionRingPacketProbe` は参加後にオンラインのプレイヤーを検出し、`SkillActionRingService.toggle(AstPlayer)` を自動実行する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20 -ExpectLogPattern "ACTION_RING_PACKET"
```

これはパケット単位の再現証跡として扱う。クライアント側の目視確認を完全には置き換えないが、プレイヤー接続後に Codex が反復可能なサーバー側パケット実動作チェックを行うための入口として使う。

## 使用例

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord の既存テストを恒久テスト適格性で分類し、共通ロジックだけに整理してください。
```

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord の手動確認手順をテストと一時 Purpur サーバースクリプトへ落とし込み、結果を報告してください。
```

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord のインベントリの二重消費防止契約が恒久テスト適格性を満たすか判定し、適格な場合だけ最小のJUnitまたはMockBukkitテストを追加してください。
```

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord の設定済み動作サーバー一式を複製して統合開発サーバーを準備し、結果を報告してください。
```

## 報告形式

結果は日本語で記載する。

```markdown
## 実施結果
- <追加したテストやスクリプト>

## 実装ファイル
- `<path>`: <役割>

## 実行コマンド
- `<command>`: 成功 / 失敗 / 未実行

## 残課題
- なし / <MockBukkit では扱えない点や実サーバ確認が必要な点>
```
