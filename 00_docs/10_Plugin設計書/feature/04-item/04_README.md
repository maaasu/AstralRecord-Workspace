# 04_README

このディレクトリは `feature/item` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/executor/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/gui/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/view/*`

## ドキュメント一覧（推奨順）

1. [[04_0.00-概要]]
2. [[04_1.00-モデル定義]]
3. [[04_2.00-ユースケース]]
4. [[04_3.00-索引]]
5. [[04_3.01-イベント]]
6. [[04_3.02-サービス]]
7. [[04_3.03-コマンド]]
8. [[04_3.04-リポジトリ]]
9. [[04_3.08-アダプタ・リスナー]]
10. [[04_4.00-統合フロー]]
11. [[04_5.00-例外・ログ・運用]]
12. [[04_9.00-未決事項]]（必要時）

## 依存 feature

- `loot`
  - [[04_3.02-サービス]].ItemStackFactory のバンドル lore 構築は [[06_3.02-サービス]].ロード済み優先ルート取得 を呼び、未ロード時は API 取得を試みる。
  - bundle 開封は `loot_table:` 接頭辞を正規化し、解決済み table / pool 構造を `LootRollService` で抽選する。
- `inventory`
  - `/item get` でアイテムをインベントリへ付与する際に `InventoryService.addItemToNormalInventory` を呼び出す。
- `account`
  - 装備/ルーンインスタンスの所有者識別に [[02_1.00-モデル定義]].アカウントモデル.uuid を使用する。
- `player`
  - [[04_3.01-イベント]].アイテム操作ブロック で [[03_1.00-モデル定義]].プレイヤーキャッシュ からアカウントモードを参照する。
- `status`
  - 装備/ルーンの Lore 表示で `StatusType` を解決し、表示名・カラー分類に使用する。
  - ステータス表示名は `StatusType` のカテゴリ色を使用し、ステータス値はカテゴリにかかわらず共通色で表示する。
  - ステータス範囲値は半角 `~` ではなく全角 `～` で表示し、値自体は太字の共通色で表示する。
  - アイテムカテゴリの保存・API 値は英語 ID のままとし、プレイヤー向け表示では `ItemCategory.displayNameJa` の日本語名を使用する。
- `currency`
  - `currency` カテゴリの item 表現は本 feature が保持するが、所持通貨の取得・加算・表示用 ItemStack 一覧の正本は currency feature とする。
- `player-interaction`
  - クリック入力の候補収集、優先順位、勝者一件実行は[[28_README]]を正本とし、本featureはitem useとweapon / combat候補を提供する。`HOTBAR_SLOT`後の使用待機解除は非競合observerとして扱う。
  - item use は `ITEM_USE`、weapon / combat は `FALLBACK` 候補として登録し、tick token と勝者結果で二重実行を防ぐ。

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
- item use・武器候補の成立条件やクリック抑止方針の変更:
  - [[04_2.00-ユースケース]]
  - [[04_3.01-イベント]]
  - [[04_4.00-統合フロー]]
  - [[28_3.02-サービス]]
- bundle の開封、抽選、取得内容表示、オーバーフロー処理の変更:
  - [[04_2.00-ユースケース]]
  - [[04_3.02-サービス]]
  - [[04_4.00-統合フロー]]
  - [[06_3.02-サービス]]
- `PlayerItemHeldEvent`によるbundle / potion使用待機解除、または`HOTBAR_SLOT`調停との境界変更:
  - [[04_3.01-イベント]]
  - [[04_4.00-統合フロー]]
  - [[28_3.01-イベント]]
