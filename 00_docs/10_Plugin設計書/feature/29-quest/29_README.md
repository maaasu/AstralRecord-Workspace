# 29_README

このディレクトリは `feature/quest` の設計書です。
クエスト単体定義、NPCクエストボード、プレイヤーの受領状態、進行度、報酬受取を扱います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/quest/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/gathering/service/GatheringService.java`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/player/event/PlayerJoinEventHandler.java`

## 関連データ

- `40_filebase/47.features.quest/*`: クエスト定義
- `40_filebase/48.features.quest_board/*`: NPCクエストボード定義

## ドキュメント一覧（推奨順）

1. [[29_0.00-概要]]
2. [[29_1.00-モデル定義]]
3. [[29_2.00-ユースケース]]
4. [[29_3.00-メソッド仕様]]
5. [[29_4.00-統合フロー]]
6. [[29_5.00-例外・ログ・運用]]
7. [[29_9.00-未決事項]]

## 依存 feature

- `mob`: NPC interaction、Mob討伐進行
- `gathering`: 採取進行
- `inventory`: 受領条件アイテム、報酬アイテム、Gold
- `item`: stack報酬の解決、equipment / rune instanceの準備・補償削除
- `account` / `class`: EXP報酬
- `status`: `QUEST_LIMIT` による最大受領数
- `skilltree`: EXP報酬によるレベル変化後の派生状態再計算
- `player`: ログイン時ロード、ログアウト時release、account mode guard

## 更新ルール（変更時に必ず更新する章）

- クエスト状態、表示状態、繰り返し、報酬、受領条件を変更した場合:
  - [[29_1.00-モデル定義]]
  - [[29_2.00-ユースケース]]
- NPC interaction、進行加算、GUI操作を変更した場合:
  - [[29_3.00-メソッド仕様]]
  - [[29_4.00-統合フロー]]
- API永続化、保存世代、debounce、報酬補償を変更した場合:
  - [[29_3.00-メソッド仕様]]
  - [[29_4.00-統合フロー]]
  - [[29_5.00-例外・ログ・運用]]
- 未実装の目標種別、分散transaction、GUI改善を決定した場合:
  - [[29_9.00-未決事項]]
