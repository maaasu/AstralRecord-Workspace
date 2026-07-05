# 12_README

このディレクトリは `feature/mob` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/mob/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/spawner/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/textdisplay/*`

## ドキュメント一覧

1. [[12_0.00-概要]]
2. [[12_1.00-モデル定義]]
3. [[12_2.00-ユースケース]]
4. [[12_3.00-索引]]
5. [[12_4.00-統合フロー]]
6. [[12_5.00-例外・ログ・運用]]
7. [[12_9.00-未決事項]]（必要時）

## 依存 feature

- `status`
- `loot`
- `item`
- `skill`
- `player`
- `currency`

## filebase

- `40_filebase/40.features.mob/`
  - `boss/v1.*.yml`
  - `enemy/v1.*.yml`
  - `npc/v1.*.yml`

## 更新ルール

- spawn / despawn / 実体制御を変更した場合:
  - [[12_3.02-サービス]]
  - [[12_3.05-実体Mob制御]]
  - [[12_4.00-統合フロー]]
- NPC interaction や導線を変更した場合:
  - [[12_1.00-モデル定義]]
  - [[12_3.04-リポジトリ]]
  - 関連先 feature の設計書
- ドロップや戦闘挙動を変更した場合:
  - [[12_3.03-戦闘]]
  - [[12_5.00-例外・ログ・運用]]

## 実装メモ

- 2026-06-09: spawner の正本実装は `feature/spawner/*` にあり、mob docs では関連挙動のみ扱う。
- 2026-06-22: NPC と同時に配置する固定 TextDisplay は `feature/textdisplay/*` で扱い、配置データは `text_displays.yml` に保存する。
- 2026-06-23: NPC interaction の `gui.type` は `SHOP` / `SELL` / `CLASS` / `STORAGE` / `EQUIPMENT_ENHANCE` を扱う。NPC `entityType` は Bukkit EntityType に加えて Bukkit block Material も指定でき、block 指定時は配置座標を水平中心にした `Interaction` + `BlockDisplay` fakeblock に通常 NPC と同じ display text、ambient particle、左クリック/右クリック interaction を付与する。fakeblock の描画はプレイヤー方向へ回転させず、下端を配置 Y 座標に接地させた `0.75` 倍の固定表示とする。`CHEST` / `TRAPPED_CHEST` / `ENDER_CHEST` は BlockDisplay で描画されないため、表示用 Material は `BARREL` に正規化する。
- 2026-07-04: NPC interaction に `command` アクションを追加し、プレイヤーとしてコマンドを実行できるようにした。`skill_tree` ワールドの帰還 NPC は `skilltree back` を呼び出して `BASE` ワールドへ戻す。
