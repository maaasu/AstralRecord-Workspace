---
name: astralrecord-plugin-test
description: AstralRecord の Minecraft プラグイン `10_plugin/AstralRecord` 向けに、JUnit / MockBukkit のテスト雛形追加、既存手動確認のテスト化、一時 Purpur/Paper サーバー起動スクリプト整備、実サーバー一式 clone を使う integration 検証基盤整備、AI デバッグ用の最小再現手順作成を行う skill。プラグイン本体の仕様変更ではなく、テスト・検証・再現基盤の整備をしたいときに使う。
---

# AstralRecord Plugin Test

## Core Rule

`10_plugin/AstralRecord` のテストと検証基盤だけを扱います。主目的が機能実装や仕様変更なら `$astralrecord-code` を使い、この skill ではテスト追加・MockBukkit 化・dev server 整備・live server clone を使う integration 検証・再現手順の固定化に集中します。

## Required Context

1. `E:\AstralRecord-Workspace\AGENTS.md`
2. `E:\AstralRecord-Workspace\README.md` の AstralRecord Plugin セクション
3. `E:\AstralRecord-Workspace\PLUGIN_GUIDE.md`
4. `E:\AstralRecord-Workspace\.codex\skills\astralrecord-code\references\plugin-code.md`
5. `E:\AstralRecord-Workspace\10_plugin\AstralRecord\scripts\dev-server.config.json`

対象が `10_plugin/AstralRecord` 以外なら、この skill は使わず対象プロジェクト向け skill に切り替えます。

## Workflow

1. 依頼を分類する
   - 純ロジック確認: `JUnit` の単体テスト
   - Bukkit API 周辺確認: `MockBukkit`
   - Purpur/Paper 実機寄り確認: 一時サーバースクリプト
   - 実サーバー構成込みの integration 確認: 動作サーバー一式 clone
   - 手動再現の固定化: 失敗手順をそのままテストまたはスクリプトへ落とす
2. 最小コンテキストを読む
   - 対象クラス、近傍の呼び出し元、依存 repository/service、関連 config を確認する
   - `ProtocolLib` や実サーバ依存が強い場合は、プラグイン丸ごとロードより対象クラスの分離を優先する
   - integration 系では `dev-server.config.json` の clone 元と前提設定を確認する
3. 配置を決める
   - テストは `src/test/java/...` に本体と同じ package で置く
   - MockBukkit の共通基盤は `src/test/java/.../support` に寄せる
   - 一時サーバースクリプトは `10_plugin/AstralRecord/scripts/` に置く
   - 実サーバー clone の既定値は `scripts/dev-server.config.json` に持つ
4. 実装する
   - まず再現しやすい純ロジックを単体テスト化する
   - Bukkit `Player` / `Inventory` / `Command` まわりは MockBukkit 化する
   - Purpur 固有 API、Lifecycle、Pathfinder、実 plugin 依存は dev server スクリプト側に逃がす
   - 実サーバー clone を使う場合は config / plugins / worlds / proxy 関連設定を手で再生成せず、そのまま複製して AstralRecord の jar だけ差し替える
   - 手動確認コマンドがある場合は、テスト対象を直接呼べる形へ少しだけ寄せる
5. 検証する
   - まず対象テストだけ `mvn -q -Dtest=<ClassName> test`
   - 必要なら `mvn -q test`
   - スクリプトは `-NoStart` 付きで準備まで確認する
   - live server clone 検証は `-UseLiveServerClone` と `-RefreshLiveServerClone` の必要有無を明示する
6. 自分で再利用できる形へ整える
   - 実行コマンド例を README または skill へ追記する
   - `$astralrecord-code` から辿れるように workspace skill README を更新する

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
powershell -NoProfile -ExecutionPolicy Bypass -File E:\AstralRecord-Workspace\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20
```

log marker まで自動判定したい場合は `-ExpectLogPattern` を指定する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File E:\AstralRecord-Workspace\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20 -ExpectLogPattern "ACTION_RING_PACKET spawn"
```

bot は packet-level の自律検証用であり、client 側の見た目確認を完全には置き換えない。画面上の配置・視認性・操作感を確認したい場合は実クライアント確認を併用する。

## Action Ring Packet Autotest Example

アクションリングの packet-only 表示を実サーバー寄りに再現するときは、専用 helper を使う。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File E:\AstralRecord-Workspace\10_plugin\AstralRecord\scripts\prepare-action-ring-packet-test.ps1 -UseLiveServerClone
```

準備後は次で起動する。

```text
powershell -NoProfile -ExecutionPolicy Bypass -File E:\AstralRecord-Workspace\10_plugin\AstralRecord\scripts\start-dev-server.ps1 -ServerRoot E:\AstralRecord-Workspace\10_plugin\AstralRecord\.dev-server\integration-live-clone-actionring -SkipBuild -Background
```

この helper は `ActionRingPacketProbe.jar` を配置し、clone 側 SQL Server を無効化し、filebase を `E:\AstralRecord-Workspace\40_filebase` に向け、`localhost:25578` へ Velocity なしで直接接続できるよう proxy 設定を補正する。

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
powershell -NoProfile -ExecutionPolicy Bypass -File E:\AstralRecord-Workspace\10_plugin\AstralRecord\scripts\run-packet-test-bot.ps1 -HostName localhost -Port 25578 -Username CodexPacketBot -StaySeconds 20 -ExpectLogPattern "ACTION_RING_PACKET"
```

これは packet-level の再現証跡として扱う。client 側の目視確認を完全には置き換えないが、player 接続後に Codex が反復可能な server-side packet 実動作チェックを行うための入口として使う。

## Example Prompts

```text
Use $astralrecord-plugin-test to add a JUnit and MockBukkit test scaffold for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

```text
Use $astralrecord-plugin-test to turn the manual verification steps for E:\AstralRecord-Workspace\10_plugin\AstralRecord into tests and a temporary Purpur server script, then report the result.
```

```text
Use $astralrecord-plugin-test to add a MockBukkit test for the inventory feature under E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

```text
Use $astralrecord-plugin-test to prepare an integration dev server by cloning the configured live server package for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
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
