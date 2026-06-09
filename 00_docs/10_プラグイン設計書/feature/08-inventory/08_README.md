# 08_README

このディレクトリは `feature/inventory` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/inventory/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/inventory/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/inventory/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/inventory/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/inventory/state/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/storage/*`

## ドキュメント一覧（推奨順）

1. [[08_0.00-概要]]
2. [[08_1.00-モデル定義]]
3. [[08_2.00-ユースケース]]
4. [[08_3.00-索引]]
5. [[08_4.00-統合フロー]]
6. [[08_5.00-例外・ログ・運用]]
7. [[08_9.00-未決事項]]（必要時）

## 依存 feature

- `player`
- `account`
- `item`
- `menu`

## 実装メモ

- 2026-06-09: `storage` は独立実装を持つが、docs では inventory の拡張範囲として扱う。ストレージ entry の正本、`/storage`、収納・取り出し処理は本 feature の対象実装パスに含める。
- 2026-06-09: 旧 `15-hotbar-action` のホットバー保存・ショートカット表示・Bukkit スロット操作は本 feature を正本とする。

## 更新ルール（変更時に必ず更新する章）

- インベントリ構造やスロット仕様の変更:
  - [[08_1.00-モデル定義]]
  - [[08_3.02-サービス]]
  - [[08_4.00-統合フロー]]
- `/inventory` コマンドや表示メッセージ変更:
  - [[08_3.03-コマンド]]
  - [[08_5.00-例外・ログ・運用]]
- 保存・復元処理の変更:
  - [[08_1.00-モデル定義]]
  - [[08_3.02-サービス]]
  - [[08_3.04-リポジトリ]]
  - [[08_3.05-タスク・補助]]
  - [[08_4.00-統合フロー]]
  - [[08_5.00-例外・ログ・運用]]
