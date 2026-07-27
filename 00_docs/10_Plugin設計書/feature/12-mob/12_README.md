# 12_README

このディレクトリは `feature/mob` と、その配置・スポーン表示を担う `feature/spawner` / `feature/textdisplay`、同じ world object 基盤を使う `feature/gathering` の設計書です。採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/spawner/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/textdisplay/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/gathering/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（Mob の `5700` 系、採集配置の shared `E_6400` / `E_6401`、採集 packet の shared `W_9010`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（Mob / NPC / TextDisplay / gathering の player message）

## ドキュメント一覧（推奨順）

1. [[12_0.00-概要]]
2. [[12_1.00-モデル定義]]
3. [[12_2.00-ユースケース]]
4. [[12_3.00-索引]]
5. [[12_3.01-イベント]]
6. [[12_3.02-サービス]]
7. [[12_3.03-コマンド]]
8. [[12_3.04-リポジトリ]]
9. [[12_3.05-実体Mob制御]]
10. [[12_3.06-戦闘]]
11. [[12_4.00-統合フロー]]
12. [[12_5.00-例外・ログ・運用]]
13. [[12_9.00-未決事項]]（必要時）

## 依存 feature

- `status` / `combat`: Mob と player の status、独自 damage、HP / shield 減算を扱う。
- `skill`: Mob skill caster と `combat.skills` の発動を扱う。
- `loot` / `item` / `currency` / `account`: 討伐報酬を解決・付与する。
- `player` / `player-setting`: target、viewer、地域状態、drop log 表示設定を参照する。
- `quest`: 採集完了時に `QuestService.recordGathering` へ進捗通知する。採集定義・採集 session・スポーンの所有者ではない。
- `boss`: boss challenge、scaling、死亡処理を連携する。
- `player-interaction`: NPC / fakeblock / spawner / block mutation の候補収集と勝者選択を正本とする。
- AstralRecordApi `/api/mob` と `40_filebase/40.features.mob/{boss,enemy,npc,spawner}/*`: Mob template の永続正本。
- `40_filebase/42.features.gathering/*` / `40_filebase/43.features.gathering.spawner/*`: 採集 object と採集 spawner の定義正本。
- plugin data folder の `mob_spawners.yml`、`gathering_spawners.yml`、`npc_placements.yml`、`text_displays.yml`: 実行環境ごとの配置状態を保持する。
- Paper API / ProtocolLib: 実体 Mob、Pathfinder、packet-only spawner display を提供する。

## 更新ルール（変更時に必ず更新する章）

- template / AI / interaction / drop schema 変更: [[12_1.00-モデル定義]]、[[12_3.04-リポジトリ]]、filebase schema、API 設計書
- spawn / despawn / movement / viewer 変更: [[12_3.02-サービス]]、[[12_3.05-実体Mob制御]]、[[12_4.00-統合フロー]]
- `/mob` / `/textdisplay` 変更: [[12_3.03-コマンド]]、[[12_5.00-例外・ログ・運用]]
- damage / threat / knockback / drop 変更: [[12_3.06-戦闘]]、[[12_4.00-統合フロー]]、[[12_5.00-例外・ログ・運用]]
- NPC・spawner 入力候補または account mode 制約変更: [[12_1.00-モデル定義]]、[[12_3.02-サービス]]、[[12_4.00-統合フロー]]、[[28_3.01-イベント]]、[[28_3.02-サービス]]
- 配置 YAML または packet display 変更: [[12_3.02-サービス]]、[[12_3.04-リポジトリ]]、[[12_4.00-統合フロー]]
- 採集定義・tool tag・採集 session・drop・quest 通知変更: [[12_1.00-モデル定義]]、[[12_2.00-ユースケース]]、[[12_3.01-イベント]]、[[12_3.02-サービス]]、[[12_4.00-統合フロー]]
- 採集 spawner の出現条件・管理者表示・配置保存変更: [[12_1.00-モデル定義]]、[[12_3.01-イベント]]、[[12_3.02-サービス]]、[[12_3.03-コマンド]]、[[12_3.04-リポジトリ]]、[[12_4.00-統合フロー]]
