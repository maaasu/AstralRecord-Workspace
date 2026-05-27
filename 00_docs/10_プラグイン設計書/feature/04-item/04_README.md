# 04_README

このディレクトリは `feature/item` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/item/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/item/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/item/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/item/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/item/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/item/view/*`

## ドキュメント一覧（推奨順）

1. [[04_0.00-概要]]
2. [[04_1.00-モデル定義]]
3. [[04_2.00-ユースケース]]
4. [[04_3.00-索引]]
5. [[04_4.00-統合フロー]]
6. [[04_5.00-例外・ログ・運用]]
7. [[04_9.00-未決事項]]（必要時）

## 依存 feature

- `loot`
  - [[04_3.02-サービス]].ItemStackFactory のバンドル lore 構築で [[06_3.02-サービス]].ロード済みルート取得 を参照する。
- `inventory`
  - `/item get` でアイテムをインベントリへ付与する際に `InventoryService.addItemToNormalInventory` を呼び出す。
- `account`
  - 装備/ルーンインスタンスの所有者識別に [[02_1.00-モデル定義]].アカウントモデル.uuid を使用する。
- `player`
  - [[04_3.01-イベント]].アイテム操作ブロック で [[03_1.00-モデル定義]].プレイヤーキャッシュ からアカウントモードを参照する。
- `status`
  - 装備/ルーンの Lore 表示で `StatusType` を解決し、表示名・カラー分類に使用する。

## 更新ルール（変更時に必ず更新する章）

- アイテム取得・キャッシュ処理の処理順変更:
  - [[04_3.02-サービス]]
  - [[04_4.00-統合フロー]]
  - [[04_3.04-リポジトリ]]（API 入出力が変わる場合）
- [[04_1.00-モデル定義]].アイテムモデル 項目追加・削除:
  - [[04_1.00-モデル定義]]
  - [[04_3.04-リポジトリ]]
- 装備インスタンス / ルーンインスタンスの構造変更:
  - [[04_1.00-モデル定義]]
  - [[04_3.02-サービス]]
  - [[04_3.04-リポジトリ]]
- コマンド仕様変更（`/item` 系）:
  - [[04_3.03-コマンド]]
  - [[04_3.02-サービス]]（サービス呼び出しが変わる場合）
  - [[04_5.00-例外・ログ・運用]]（運用影響がある場合）
- ItemStack 表示・パケット書き換え方針の変更:
  - [[04_3.02-サービス]]
  - [[04_3.08-アダプタ・リスナー]]
  - [[04_5.00-例外・ログ・運用]]
- ログIDや障害対応手順の変更:
  - [[04_5.00-例外・ログ・運用]]
  - [[04_9.00-未決事項]]（未確定事項がある場合）

## 2026-05-27 実装反映

- bundle アイテムは `ItemInteractionBlockEventHandler` で右クリック時に `BundleUseService` を起動し、開封処理を行う。
- バンドル開封後も vanilla 側の使用・設置・消費は抑止する。
- bundle の loot 参照は `loot_table:` 接頭辞付き ID を許容し、plugin 側で table / pool 構造を解決する。
- 追加メモ: [[04_90.01-bundle開封実装メモ]]
