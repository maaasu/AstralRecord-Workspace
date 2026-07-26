# 07_README

このディレクトリは `feature/status` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/status/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/status/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/status/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/status/model/*`
- `src/main/resources/player.properties`（`P_5100` から `P_5106`）
- `40_filebase/75.shared.status/v1.status_types.yml`（ステータスID・表示メタデータの正本）
- `generate-status-types.ps1`（`StatusType.kt`を含む各言語型の生成入口）

## ドキュメント一覧（推奨順）

1. [[07_0.00-概要]]
2. [[07_1.00-モデル定義]]
3. [[07_2.00-ユースケース]]
4. [[07_3.00-索引]]
5. [[07_4.00-統合フロー]]
6. [[07_5.00-例外・ログ・運用]]
7. [[07_9.00-未決事項]]（必要時）

## 依存 feature

- `player`
  - [[03_1.00-モデル定義]].プレイヤーセッション に [[07_1.00-モデル定義]].ステータススナップショット を保持する。
  - [[03_1.00-モデル定義]].プレイヤーキャッシュ からオンラインプレイヤーを取得し、定期回復を行う。
- `account`
  - [[02_1.00-モデル定義]].アカウントモード に応じてステータス補正を加算する。
  - [[02_1.00-モデル定義]].プレイヤーレベル進行 の `level` に応じてステータス補正を加算する。
- `buff`
  - 有効バフを削除・参照し、ステータス補正へ反映する。
- `hud`
  - HUD 表示は status の現在 HP/MP/EN を参照する。
  - HUD 側の責務分離（`service` / `view`）は [[10_0.00-概要]], [[10_3.00-索引]] を参照する。
- `player-interaction`
  - `HOTBAR_SLOT`の入力所有権は[[28_README]]を正本とし、status側の再計算は非競合observerとして扱う。

## 更新ルール（変更時に必ず更新する章）

- ステータス計算式・基礎値・補正条件の変更:
  - [[07_1.00-モデル定義]]
  - [[07_3.02-サービス]]
  - [[07_4.00-統合フロー]]
- `/status` 表示やメッセージ変更:
  - [[07_3.03-コマンド]]
  - [[07_5.00-例外・ログ・運用]]
- 定期回復周期や回復条件の変更:
  - [[07_3.05-タスク]]
  - [[07_4.00-統合フロー]]
- スナップショット項目追加・削除:
  - [[07_1.00-モデル定義]]
  - [[07_3.09-モデル操作]]
- ステータスID・日本語名・カテゴリ・単位・小数桁・範囲可否の変更:
  - `40_filebase/75.shared.status/v1.status_types.yml`
  - `.\generate-status-types.ps1`による生成物
  - [[07_1.00-モデル定義]]
- `PlayerItemHeldEvent`後のステータス再計算、または`HOTBAR_SLOT`調停との境界変更:
  - [[07_4.00-統合フロー]]
  - [[28_3.01-イベント]]
