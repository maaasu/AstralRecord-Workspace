# 09_README

このディレクトリは `feature/menu` の設計書です。採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/player/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/menu/view/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/guide/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/sell/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/storage/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（`E_5600`、shared GUI の `E_5601`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5601` から `P_5603`）

## ドキュメント一覧（推奨順）

1. [[09_0.00-概要]]
2. [[09_1.00-モデル定義]]
3. [[09_2.00-ユースケース]]
4. [[09_3.00-索引]]
5. [[09_3.01-イベント]]
6. [[09_3.02-サービス]]
7. [[09_3.03-コマンド]]
8. [[09_3.07-GUI・View]]
9. [[09_4.00-統合フロー]]
10. [[09_5.00-例外・ログ・運用]]
11. [[09_9.00-未決事項]]（必要時）

## 依存 feature

- `player` / `account`: online セッション、アカウントモード、プレイヤー一覧を参照する。
- `status` / `class` / `currency`: メインメニューとプレイヤー詳細の描画値を取得する。
- `inventory`: 装備 GUI、Bukkit inventory の dummy 表示、ホットバー表示を連携する。
- `player-setting`: プレイヤー設定 GUI を開く。
- `guide` / `mail` / `party` / `adventure-record`: メニュー項目の遷移先を提供する。
- `sell` / `storage`: `MenuView` を共有するが、売却・保管の業務処理は各 feature を正本とする。
- `player-interaction`: クラフトショートカットの DROP 入力を調停する。

## 更新ルール（変更時に必ず更新する章）

- 画面種別、メインメニュー配置、アイコン変更: [[09_1.00-モデル定義]]、[[09_3.07-GUI・View]]、[[09_4.00-統合フロー]]
- クラフトショートカットの入力・既定値変更: [[09_1.00-モデル定義]]、[[09_3.01-イベント]]、[[09_4.00-統合フロー]]、[[28_3.01-イベント]]
- `/menu`、`/player info`、`/trash`、`/enhance` 変更: [[09_3.03-コマンド]]、[[09_5.00-例外・ログ・運用]]
- 画面遷移時の dummy / restore 変更: [[09_3.02-サービス]]、[[09_4.00-統合フロー]]
- ゴミ箱の確認・返却・破棄変更: [[09_2.00-ユースケース]]、[[09_3.02-サービス]]、[[09_5.00-例外・ログ・運用]]
