# 27_README

このディレクトリは `feature/quest` の設計書です。  
クエスト単体定義、NPCクエストボード、プレイヤーの受領状態、進行度、報酬受取を扱います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/quest/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/event/MobInteractionEventHandler.java`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobCombatService.java`
- `src/main/java/io/github/maaasu/astralRecord/feature/gathering/service/GatheringService.java`
- `40_filebase/47.features.quest/*`
- `40_filebase/48.features.quest_board/*`

## ドキュメント一覧

1. [[27_0.00-概要]]
2. [[27_1.00-モデル定義]]
3. [[27_2.00-ユースケース]]
4. [[27_3.00-メソッド仕様]]
5. [[27_4.00-統合フロー]]
6. [[27_5.00-例外・ログ・運用]]
7. [[27_9.00-未決事項]]

## 依存 feature

- `mob`: NPC interaction、Mob討伐進行
- `gathering`: 採取進行
- `inventory`: 受領条件アイテム、報酬アイテム、Gold
- `account` / `class`: EXP報酬
- `status`: `QUEST_LIMIT` による最大受領数

## 更新ルール

- クエスト状態、繰り返し、報酬、受領条件を変更した場合は [[27_1.00-モデル定義]] を更新する。
- NPC interaction、進行加算、GUI操作を変更した場合は [[27_3.00-メソッド仕様]] と [[27_4.00-統合フロー]] を更新する。
- クエスト状態は `account-quest` API 経由で SQL Server に保存する。既存の `quest-states/*.yml` は初回読み込み時の移行用に限り使用する。
