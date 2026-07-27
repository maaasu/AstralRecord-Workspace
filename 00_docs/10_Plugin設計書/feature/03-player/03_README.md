# 03_README

このディレクトリは `feature/player` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/death/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/save/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/loginbonus/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/class/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playerclass/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/shared/interaction/*`

## ドキュメント一覧（推奨順）

1. [[03_0.00-概要]]
2. [[03_1.00-モデル定義]]
3. [[03_2.00-ユースケース]]
4. [[03_3.00-索引]]
5. [[03_3.01-イベント]]
6. [[03_3.02-サービス]]
7. [[03_3.03-コマンド]]
8. [[03_3.04-キャッシュ]]
9. [[03_3.05-保存]]
10. [[03_3.06-モデル操作]]
11. [[03_4.00-統合フロー]]
12. [[03_5.00-例外・ログ・運用]]
13. [[03_9.00-未決事項]]（必要時）

`/test`・`/temp` は `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/temp/command/` へ移動済みで本 feature の責務外。

## 依存 feature

- `user`
  - [[01_3.02-サービス]].ユーザ取得 で [[01_1.00-モデル定義]].ユーザモデル を取得する。
  - [[01_1.00-モデル定義]].ユーザモデル を [[03_1.00-モデル定義]].プレイヤーセッション に保持する。
- `account`
  - [[02_3.01-サービス]].選択アカウント取得 で [[02_1.00-モデル定義]].アカウントモデル を取得する。
  - [[02_1.00-モデル定義]].アカウントモデル を [[03_1.00-モデル定義]].プレイヤーセッション に保持する。
- `inventory`
  - 通常プレイヤーのインベントリGUI反映と保存タスクで連携する。
- `status`
  - ログイン時は [[07_3.02-サービス]].ステータス再計算、ドッジ時は [[07_3.02-サービス]].EN消費 で連携する。
- `class` / `playerclass`
  - [[03_1.00-モデル定義]].プレイヤーセッション の `classId` とクラス ID ごとの独立した `classLevel` / `classExperience` を保持する。職業マスタ、職業 GUI、職業コマンドの正本は本 feature ではなく class / playerclass 側とする。
- `player-interaction`
  - 右クリック、左クリック、block mutation、item drop、hotbar slot変更、sneak変更の共通入口と候補仲裁は[[28_README]]を正本とする。共通gatewayは`shared/interaction`へ配置し、player featureは入力ロック、player-mode drop guard、wall-cling / dodge fallback、プレイヤー状態、退出ライフサイクルを提供する。

- `loginbonus`
  - 実装は独立パッケージだが、設計書では player join 導線として本 feature に含める。
  - ログイン時の自動表示と NPC の `LOGIN_BONUS` action から `LoginBonusService` を呼び出す。

## 更新ルール（変更時に必ず更新する章）

- ログイン・ログアウト処理変更:
  - [[03_3.01-イベント]]
  - [[03_3.02-サービス]]
  - [[03_4.00-統合フロー]]
- [[03_1.00-モデル定義]].プレイヤーセッション 項目追加・削除:
  - [[03_1.00-モデル定義]]
  - [[03_3.06-モデル操作]]
- 保存契機や保存タスク変更:
  - [[03_3.05-保存]]
  - [[03_5.00-例外・ログ・運用]]
- 全体チャット・ダイレクトメッセージ・プレイヤー向け通知の管理方式変更:
  - [[03_1.00-モデル定義]]
  - [[03_3.01-イベント]]
  - [[03_3.02-サービス]]
  - [[03_4.00-統合フロー]]
- 共通入力ingress、入力token相関、player側DROP_ITEM / SNEAK候補、退出時のpending入力破棄を変更:
  - [[03_3.01-イベント]]
  - [[03_4.00-統合フロー]]
  - [[28_3.01-イベント]]
  - [[28_4.00-統合フロー]]
