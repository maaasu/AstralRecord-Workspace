# 26_README

このディレクトリは `feature/boss` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/gui/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/boss/view/*`
- `10_plugin/AstralRecord/src/main/resources/config.yml`
- `10_plugin/AstralRecord/src/main/resources/player.properties`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`

## 関連データ

- `40_filebase/40.features.mob/boss/*.yml`（ボス Mob と `challenge` 設定）
- `40_filebase/40.features.mob/boss/docs.boss.YAMLスキーマ定義.md`
- `40_filebase/60.features.world/*.yml`（ハブ・ボスフィールドの `WorldMasterData`）

## ドキュメント一覧（推奨順）

1. [[26_0.00-概要]]
2. [[26_1.00-モデル定義]]
3. [[26_2.00-ユースケース]]
4. [[26_3.00-索引]]
5. [[26_3.01-イベント]]
6. [[26_3.02-サービス]]
7. [[26_3.03-コマンド]]
8. [[26_3.05-タスク・スケジューラ]]
9. [[26_3.06-キャッシュ・セッション]]
10. [[26_4.00-統合フロー]]
11. [[26_5.00-例外・ログ・運用]]
12. [[26_9.00-未決事項]]（必要時）

## 依存 feature

- `mob`: `MobCategory.BOSS`、`MobTemplate.challenge`、Mob 生成・破棄、固定受取人向け報酬処理を利用する。
- `world`: `WorldMasterData` 検証、ハブ解決、runtime world 登録、非同期転送を利用する。
- `party`: 受付時のオンラインメンバー固定、リーダー判定、フィールド入場直前の所属再確認に利用する。
- `player`: gameplay mode 判定、死亡・復帰、プレイヤーメッセージを利用する。
- `combat`: ボスへの有効ダメージ、ボス討伐、参加者死亡の通知元とする。
- `status`: 復帰時の HP / MP / EN / Shield 全回復を担当する。
- `hud`: 準備中・戦闘中の挑戦情報を Sidebar に表示する。
- `player-interaction`: `SNEAK`、`RIGHT_CLICK`、`DROP_ITEM` の候補調停を担当する。

## 更新ルール（変更時に必ず更新する章）

- `challenge` の項目、既定値、参加人数補正を変更した場合:
  - [[26_1.00-モデル定義]]
  - [[26_3.02-サービス]]
  - [[26_5.00-例外・ログ・運用]]
- 受付、ハブ転送、参加者確定、入場条件を変更した場合:
  - [[26_2.00-ユースケース]]
  - [[26_3.01-イベント]]
  - [[26_3.02-サービス]]
  - [[26_4.00-統合フロー]]
- フィールドコピー、ロード、チャンク準備、破棄、起動時掃除を変更した場合:
  - [[26_3.02-サービス]]
  - [[26_3.05-タスク・スケジューラ]]
  - [[26_4.00-統合フロー]]
  - [[26_5.00-例外・ログ・運用]]
- 死亡・復帰、報酬対象、結果表示、Sidebar を変更した場合:
  - [[26_1.00-モデル定義]]
  - [[26_2.00-ユースケース]]
  - [[26_3.02-サービス]]
  - [[26_4.00-統合フロー]]
- コマンド、入力候補、中止 GUI、メッセージ、ログを変更した場合:
  - [[26_3.01-イベント]]
  - [[26_3.03-コマンド]]
  - [[26_5.00-例外・ログ・運用]]
