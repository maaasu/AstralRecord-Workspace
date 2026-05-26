# 12_README

このディレクトリは `feature/mob` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/mob/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/mob/view/*`

## ドキュメント一覧（推奨順）

1. [[12_0.00-概要]]
2. [[12_1.00-モデル定義]]
3. [[12_2.00-ユースケース]]
4. [[12_3.00-索引]]
5. [[12_4.00-統合フロー]]
6. [[12_5.00-例外・ログ・運用]]
7. [[12_9.00-未決事項]]（必要時）

## 依存 feature

- `status`
  - `StatusType` を [[12_1.00-モデル定義]].Mob ベースステータス の `status` キーとして直接利用する。
  - 独自ダメージ計算で `STRENGTH` / `DEXTERITY` / `INTELLIGENCE` / `ATTACK` / `DEFENSE` / `MAGIC_DEFENSE` などを参照する。
- `loot`
  - `drops.lootTable` 参照解決の入力に [[06_3.02-サービス]] を利用する想定（読み取り側）。
- `item`
  - `drops.items[].itemId` 参照解決の入力に item feature を利用する想定（読み取り側）。
- `skill`
  - `ai.combat.skills` 参照解決の入力に skill feature を利用する想定（読み取り側）。
- `player`
  - ターゲット選択・ヘイト管理・ダメージ授受の対象として `AstPlayer` を扱う。

## 対応 API feature

- `00_docs/20_API設計書/feature/16-mob/`

## マスタデータの所在

- `40_filebase/40.features.mob/`
  - `boss/v1.*.yml` — `master_type = mob.boss`
  - `enemy/v1.*.yml` — `master_type = mob.enemy`
  - `npc/v1.*.yml` — `master_type = mob.npc`

## 更新ルール（変更時に必ず更新する章）

- スポーン・デスポーン・パケット表示の処理順変更:
  - [[12_3.02-サービス]]
  - [[12_3.05-パケット表示]]
  - [[12_4.00-統合フロー]]
- ターゲット選定・戦闘ロジック・ノックバック式の変更:
  - [[12_3.03-戦闘]]
  - [[12_4.00-統合フロー]]
- ドロップ抽選方針の変更:
  - [[12_3.03-戦闘]]
  - [[12_5.00-例外・ログ・運用]]（運用影響がある場合）
- [[12_1.00-モデル定義]] の項目追加・削除:
  - [[12_1.00-モデル定義]]
  - [[12_3.04-リポジトリ]]（API 入出力が変わる場合）
- 必要ライブラリ・依存追加:
  - [[12_0.00-概要]]
  - [[12_9.00-未決事項]]（暫定運用の場合）
