# 11_README

このディレクトリは `feature/playersetting` の設計書です。採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/cache/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/gui/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/OptimisticLockConflictException.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playersetting/PlayerSettingMsgId.java`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（`W_5310` から `W_5312`、`E_5310` から `E_5314`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5320` から `P_5326`）

## ドキュメント一覧（推奨順）

1. [[11_0.00-概要]]
2. [[11_1.00-モデル定義]]
3. [[11_2.00-ユースケース]]
4. [[11_3.00-索引]]
5. [[11_3.01-イベント]]
6. [[11_3.02-サービス]]
7. [[11_3.03-コマンド]]
8. [[11_3.04-リポジトリ]]
9. [[11_3.06-キャッシュ]]
10. [[11_3.07-GUI・View]]
11. [[11_4.00-統合フロー]]
12. [[11_5.00-例外・ログ・運用]]
13. [[11_9.00-未決事項]]（必要時）

## 依存 feature

- `user` / `player`: user UUID、online session、join / quit lifecycle、管理者権限を利用する。
- `menu`: 設定 GUI の入口、共通 navigation、hotbar shortcut click support を利用する。
- `hud`: `PERFORMANCE_INFO_DISPLAY` を sidebar / tab list に反映する。
- `combat`: damage log の表示可否を参照する。
- `loot` / `particle` / `temp-block`: drop log、particle 密度、temporary display の表示可否を参照する。
- `inventory`: `AUTO_SAVE_MESSAGE` を autosave 通知へ反映する。
- AstralRecordApi `/api/player-setting`: user 単位の設定と optimistic lock version を永続化する。

## 更新ルール（変更時に必ず更新する章）

- 設定 key・型・既定値変更: [[11_1.00-モデル定義]]、[[11_3.02-サービス]]、[[11_3.03-コマンド]]、[[11_3.07-GUI・View]]
- API payload・楽観ロック変更: [[11_3.02-サービス]]、[[11_3.04-リポジトリ]]、[[11_4.00-統合フロー]]、API 設計書
- login / quit / session token 変更: [[11_3.01-イベント]]、[[11_3.02-サービス]]、[[11_4.00-統合フロー]]
- GUI slot・draft 保存変更: [[11_3.01-イベント]]、[[11_3.07-GUI・View]]、[[11_4.00-統合フロー]]
- ログ・メッセージ変更: [[11_5.00-例外・ログ・運用]]
