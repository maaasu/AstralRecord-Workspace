# 09_README

このディレクトリは `feature/menu` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/menu/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/view/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/menu/model/*`

## ドキュメント一覧（推奨順）

1. [[09_0.00-概要]]
2. [[09_1.00-モデル定義]]
3. [[09_2.00-ユースケース]]
4. [[09_3.00-索引]]
5. [[09_4.00-統合フロー]]
6. [[09_5.00-例外・ログ・運用]]
7. [[09_9.00-未決事項]]（必要時）

## 依存 feature

- `player`
- `account`
- `inventory`
- `currency`
- `status`

## 更新ルール（変更時に必ず更新する章）

- 画面構成・遷移・ショートカット仕様の変更:
  - [[09_1.00-モデル定義]]
  - [[09_3.07-GUI・View]]
  - [[09_4.00-統合フロー]]
- `/menu` コマンドやイベント起点の変更:
  - [[09_3.01-イベント]]
  - [[09_3.03-コマンド]]
  - [[09_5.00-例外・ログ・運用]]
- 永続設定（ショートカット設定等）の変更:
  - [[09_3.04-リポジトリ]]
  - [[09_4.00-統合フロー]]
