# 06_README

このディレクトリは `feature/loot` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/loot/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/loot/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/loot/model/*`

## ドキュメント一覧（推奨順）

1. [[06_0.00-概要]]
2. [[06_1.00-モデル定義]]
3. [[06_2.00-ユースケース]]
4. [[06_3.00-索引]]
5. [[06_4.00-統合フロー]]
6. [[06_5.00-例外・ログ・運用]]
7. [[06_9.00-未決事項]]（必要時）

## 依存 feature

- `item`
  - `ItemStackFactory` のバンドル lore 構築（`appendBundleLootLore`）で [[06_3.02-サービス]].ロード済みルート取得 を参照する。
  - `ItemBundle.lootTableId` が本 feature のキャッシュキーとなる。

## 更新ルール（変更時に必ず更新する章）

- ルートテーブル取得・キャッシュ処理の処理順変更:
  - [[06_3.02-サービス]]
  - [[06_4.00-統合フロー]]
  - [[06_3.04-リポジトリ]]（API 入出力が変わる場合）
- [[06_1.00-モデル定義]].ルートテーブル / ルートエントリ 項目追加・削除:
  - [[06_1.00-モデル定義]]
  - [[06_3.04-リポジトリ]]
- ログIDや障害対応手順の変更:
  - [[06_5.00-例外・ログ・運用]]
  - [[06_9.00-未決事項]]（未確定事項がある場合）
