---
name: astralrecord-plugin-test
description: AstralRecord の Minecraft プラグイン `10_plugin/AstralRecord` 向けに、設計書を入力とする JUnit / MockBukkit の恒久テスト、使い捨て診断テスト、一時 Purpur/Paper サーバー、実サーバー clone による integration 検証基盤を整備する。設計トレーサビリティを保ったテスト追加・整理、手動確認の自動化、AI デバッグ用の最小再現を行い、プラグイン本体の機能仕様変更を主目的としないときに使う。
---

# AstralRecord Plugin Test

## Core Rule

`10_plugin/AstralRecord` のテストと検証基盤だけを扱います。恒久テストは採用済みの設計書を入力とし、各 test method から設計箇所と検証契約を追跡可能にします。設計契約ではない一時的な診断は恒久テストへ混ぜません。主目的が機能実装や仕様変更なら `$astralrecord-code` を使い、この skill ではテスト追加・MockBukkit 化・dev server 整備・live server clone を使う integration 検証・再現手順の固定化に集中します。

作業開始時に `git rev-parse --show-toplevel` を実行して現在の task checkout を `<task-root>` として解決し、以後の設計入力・ソース・script はすべて同じ `<task-root>` から読む。main workspace の固定パスへ読み替えない。live server の外部配置先だけは `scripts/dev-server.config.json` の設定値を正本とする。

## Required Context

1. `<task-root>\AGENTS.md`
2. `<task-root>\README.md` の AstralRecord Plugin セクション
3. `<task-root>\PLUGIN_GUIDE.md`
4. `<task-root>\.codex\skills\astralrecord-code\references\plugin-code.md`
5. `<task-root>\10_plugin\AstralRecord\scripts\dev-server.config.json`
6. `<task-root>\00_docs\10_Plugin設計書\README.md`

対象が `10_plugin/AstralRecord` 以外なら、この skill は使わず対象プロジェクト向け skill に切り替えます。

## Workflow

1. 検証目的を分類する
   - 採用済みの設計契約を継続して守る: 恒久テストにする
   - 不具合の切り分け、実装中の仮説、設計にない内部詳細を一度だけ確認する: 一時テストにする
   - 恒久的に守る価値があるのに設計書へ契約がない: 先に設計書を現行仕様へ同期してから恒久テストにする
2. 設計入力を先に読む
   - 恒久テストの期待値を実装コードや既存テストから写さず、許可された設計文書の採用済み記載から決める
   - 対象クラス、近傍の呼び出し元、依存 repository/service、関連 config は fixture と観測点を決めるために読む
   - `8-実装予定`、`9-未決事項`、review 記録、`TODO` は期待値の根拠にしない
3. 検証層を決める
   - 純ロジック確認: `JUnit`
   - Bukkit `Player` / `Inventory` / `Command` / `Event`: `MockBukkit`
   - Purpur/Paper 固有 API、Lifecycle、Pathfinder: 一時サーバースクリプト
   - ProtocolLib、実 plugin 構成、proxy、world を含む integration: 動作サーバー一式 clone
   - client 表示、視認性、操作感: 実クライアント確認を残す
4. 配置して実装する
   - テストは `src/test/java/...` に本体と同じ package で置く
   - MockBukkit の共通基盤は `src/test/java/.../support` に寄せる。test annotation のない support class はトレーサビリティ検証対象外とする
   - 一時サーバースクリプトは `10_plugin/AstralRecord/scripts/` に置く
   - 実サーバー clone の既定値は `scripts/dev-server.config.json` に持つ
   - `ProtocolLib` や実サーバ依存が強い場合は、プラグイン丸ごとロードより対象 class の分離を優先する
5. 検証して清掃する
   - 恒久テストは対象テスト、トレーサビリティ validator、全テストの順で実行する
   - 一時テストは後述の追加・実行・削除手順を省略しない
   - スクリプトは `-NoStart` 付きで準備まで確認する
   - live server clone 検証は `-UseLiveServerClone` と `-RefreshLiveServerClone` の必要有無を明示する

## Permanent Test Traceability

恒久テストでは、`@Test`、`@ParameterizedTest`、`@RepeatedTest`、`@TestFactory`、`@TestTemplate` を持つ各 method の連続 annotation stack の直前へ次の Javadoc を付ける。`@DisplayName`、`@Tag`、`@Timeout` 等は Javadoc と test annotation の間に置いてよいが、別の宣言や説明文を挟まない。

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
- 一つの method が複数 feature の契約を結合して検証する場合は、`設計入力:` と対応する `章・見出し:` の対を空の Javadoc 装飾行以外を挟まず物理的に隣接させ、必要数だけ繰り返す。`検証契約:` はそれらの結合条件が分かる一文にまとめる
- 一つの method は一つの設計契約を検証する。parameterized test と test factory は、全 case が同じ記載の同じ契約を検証するときだけまとめる
- 設計書名だけ、絶対パス、Windows の `\` 区切り、存在しない見出し、`8` / `9` カテゴリ、review、`TODO` を参照しない
- Kotlin の import alias / typealias を test annotation や無効化 annotation に付けない。`@Disabled` / `@Ignore` に加え、`@DisabledOnOs` / `@EnabledOnJre` など環境条件で skip し得る JUnit annotation で validator や Maven 実行を回避しない
- Maven Surefire の既定命名 `Test*`、`*Test`、`*Tests`、`*TestCase` に一致させる
- `<task-root>\10_plugin\AstralRecord\pom.xml` の `build/testSourceDirectory` を Maven 既定から変更せず、`maven-compiler-plugin` の `testIncludes` / `testExcludes` や Surefire の custom `includes` / `excludes`、group・engine filter、skip 設定で恒久テストを compile・実行対象外にしない

リポジトリルートから次を実行し、全 method の設計入力と見出し階層を検証する。

```text
python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py
```

この validator は test source を変更した場合だけの検査ではない。task diff が `astralrecord-code/references/plugin-code.md` の「Plugin Test Traceability Gate」に列挙した test source、Plugin POM、許可設計入力、test-policy path のいずれかを追加・変更・rename・削除した場合は、POM・設計書・テスト方針だけの変更でも review handoff 前に必ず実行する。Plugin の shade 出力先が main workspace に固定されているため、この検査を `mvn verify` で代用しない。

## Ad Hoc Test Lifecycle

設計契約ではない一度限りの診断は、ファイルと class を `AdHoc<目的>Test`（既定）または `<目的>OneShotTest` として追加し、恒久テストへ残さない。通常機能名と衝突し得る曖昧な接頭辞は一時診断の識別子にしない。次の順序を守る。

1. `AdHoc<目的>Test` または `<目的>OneShotTest` を追加する
2. `mvn -q -Dtest=<一時テストClass名> test` を実行し、手順1で選んだ実際の class 名のテストが検出・実行されたことを結果で確認する
3. 診断結果を実装修正または報告へ反映する。恒久契約だと判明した場合は、設計書を更新して通常名の恒久テストへ書き直す
4. 追加した ad-hoc test を削除する
5. `git status --short` で一時ファイルが残っていないことを確認する
6. `python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py` を実行する
7. `mvn -q test` を実行する

一時テストを `@Disabled` にして残す、Surefire 対象外の名前へ変える、support class に移して温存する、設計入力を捏造して恒久化することは禁止する。

## Heuristics

- `MockBukkit` で無理に `AstralRecord` 本体をロードしない。`ProtocolLib` や外部依存に引っかかるなら、対象 class を isolated にテストする。
- いきなり統合テストに行かず、純ロジック -> MockBukkit -> dev server -> live server clone の順で狭く確認する。
- DB/API/filebase 契約が絡む場合でも、最初の再現は repository mock や test double を優先する。
- Purpur でしか起きない現象は、サーバースクリプトで再現条件を固定し、ログの採取場所を決める。
- 現在の live server source root は `scripts/dev-server.config.json` を正本として扱う。
- `velocityEnabled: true` の環境では `paper-global.yml` など proxy 設定をスクリプトで再生成しない。live clone に含まれる設定をそのまま使う。

## Packet Integration Autotest

ProtocolLib や packet-only 表示の実動作を調べるときは、feature ごとに一時 probe plugin または probe command を用意し、live clone test server に配置する。目的は、Codex が player 接続後に server-side で対象動作を再実行し、packet の送信順・短時間 destroy・metadata 更新などを `logs/latest.log` から反復確認できる状態にすること。

Packet probe の標準要件:

- `ProtocolLib` の `PacketAdapter` を `ListenerPriority.MONITOR` で登録し、対象 packet type と feature 固有の entity id / packet field / marker を記録する。
- player が一度 test server に参加した後、probe が online player を使って対象 service/command を server-side で再実行できる入口を持つ。
- 自動実行が危険な場合は `/featureprobe <player>` のような明示 command を用意する。
- log marker は feature 名を含む安定した prefix にする。例: `ACTION_RING_PACKET`, `SKILLTREE_PACKET`, `MOB_NAMEPLATE_PACKET`。
- client 側の目視確認を完全には置き換えない。packet-level の再現証跡として扱う。

### Packet Test Bot

player 接続が必要な packet integration 検証では、ユーザーの Minecraft クライアント接続の代わりに packet test bot を使える。bot は `minecraft-protocol` の offline auth で test server に参加し、probe plugin が online player を対象に server-side 実行できる状態を作る。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20
```

log marker まで自動判定したい場合は `-ExpectLogPattern` を指定する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20 -ExpectLogPattern "ACTION_RING_PACKET spawn"
```

bot は packet-level の自律検証用であり、client 側の見た目確認を完全には置き換えない。画面上の配置・視認性・操作感を確認したい場合は実クライアント確認を併用する。

## Action Ring Packet Autotest Example

アクションリングの packet-only 表示を実サーバー寄りに再現するときは、専用 helper を使う。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\prepare-action-ring-packet-test.ps1 -UseLiveServerClone
```

準備後は次で起動する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\start-dev-server.ps1 -ServerRoot <task-root>\10_plugin\AstralRecord\.dev-server\integration-live-clone-actionring -SkipBuild -Background
```

この helper は `ActionRingPacketProbe.jar` を配置し、clone 側 SQL Server を無効化し、filebase を `<task-root>\40_filebase` に向け、`localhost:25578` へ Velocity なしで直接接続できるよう proxy 設定を補正する。

自律的な実動作確認では、プレイヤーが一度 test server に参加する。probe は online player を読み取り、`AstPlayerCache` の準備を待って、稼働中 AstralRecord plugin の `SkillActionRingService.toggle(AstPlayer)` を呼び出し、packet 証跡を `logs/latest.log` に記録する。

確認する log marker:

- `ACTION_RING_AUTOTEST opened player=<name>`: probe が online player に対して action ring を server-side で開いた。
- `ACTION_RING_PACKET spawn id=<id>`: action ring の packet-only entity が client に送信された。
- `ACTION_RING_PACKET_REPRODUCED ... spawn_to_destroy_ms=<ms>`: 設定された reproduction window 内に packet-only entity が destroy された。

再実行が必要で、player が online の場合は次を使う。

```text
/actionringprobe <player>
```

ユーザーの接続なしで action ring packet の自律確認を行う場合は、test server 起動後に bot を参加させる。`ActionRingPacketProbe` は join 後に online player を検出し、`SkillActionRingService.toggle(AstPlayer)` を自動実行する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File <task-root>\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20 -ExpectLogPattern "ACTION_RING_PACKET"
```

これは packet-level の再現証跡として扱う。client 側の目視確認を完全には置き換えないが、player 接続後に Codex が反復可能な server-side packet 実動作チェックを行うための入口として使う。

## Example Prompts

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord に JUnit / MockBukkit のテスト雛形を追加し、結果を報告してください。
```

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord の手動確認手順をテストと一時 Purpur サーバースクリプトへ落とし込み、結果を報告してください。
```

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord の inventory feature 向け MockBukkit テストを追加し、結果を報告してください。
```

```text
$astralrecord-plugin-test を使って、<task-root>\10_plugin\AstralRecord の設定済み動作サーバー一式を clone して integration dev server を準備し、結果を報告してください。
```

## Report Format

Write the result in Japanese.

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
