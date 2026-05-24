# 03_README

このディレクトリは `feature/player` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/player/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/player/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/player/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/player/save/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/player/*`

## ドキュメント一覧（推奨順）

1. [[03_0.00-概要]]
2. [[03_1.00-モデル定義]]
3. [[03_2.00-ユースケース]]
4. [[03_3.00-索引]]
5. [[03_4.00-統合フロー]]
6. [[03_5.00-例外・ログ・運用]]
7. [[03_9.00-未決事項]]（必要時）

`/test`・`/temp` は `src/main/java/io/github/maaasu/astralRecord/temp/command/` へ移動済みで本 feature の責務外。

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
