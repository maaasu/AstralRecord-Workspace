# 08_README

このディレクトリは `feature/inventory` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/command/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/repository/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/inventory/state/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/storage/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（inventory 固有 `W_5250` から `W_5257`、shared GUI の `E_5601`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`

## ドキュメント一覧（推奨順）

1. [[08_0.00-概要]]
2. [[08_1.00-モデル定義]]
3. [[08_2.00-ユースケース]]
4. [[08_3.00-索引]]
5. [[08_3.01-イベント]]
6. [[08_3.02-サービス]]
7. [[08_3.03-コマンド]]
8. [[08_3.04-リポジトリ]]
9. [[08_3.05-タスク・補助]]
10. [[08_4.00-統合フロー]]
11. [[08_5.00-例外・ログ・運用]]
12. [[08_9.00-未決事項]]（必要時）

## 依存 feature

- `player`: join / quit / 保存トリガと `AstPlayer` セッションを利用する。
- `account`: アカウントモードに応じて通常・ツール用インベントリを切り替える。
- `item`: entry と Bukkit `ItemStack` の相互変換、装備・ルーン instance を利用する。
- `menu`: 装備・強化・修理・ストレージ画面を共有する。
- `status`: `INVENTORY_SLOTS` を BAG の実効容量へ反映する。
- `player-interaction`: `HOTBAR_SLOT` の競合調停を担当する。inventory の装備表示更新は非競合 observer として参加する。

`feature/storage` は独立した実装パッケージだが、所持品 state と永続化を共有するため本 feature の設計範囲に含める。ホットバー保存・ショートカット表示・Bukkit スロット操作も本 feature を正本とする。

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
- ストレージの数量規則・絞り込み・並び順の変更:
  - [[08_2.00-ユースケース]]
  - [[08_3.02-サービス]]
  - [[08_4.00-統合フロー]]
- `PlayerItemHeldEvent`による装備表示同期、または`HOTBAR_SLOT`調停との境界変更:
  - [[08_3.01-イベント]]
  - [[08_4.00-統合フロー]]
  - [[28_3.01-イベント]]
