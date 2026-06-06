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
