# 26-boss

## 理想仕様ドキュメント

ボス機能の薄い設計部分を補強する正本として、以下の日本語仕様を追加する。

1. [[26_0.01-理想仕様]]
2. [[26_1.01-理想モデル定義]]
3. [[26_2.01-理想ユースケース]]
4. [[26_3.07-理想サービス補足]]
5. [[26_4.01-理想統合フロー]]
6. [[26_5.01-理想例外・ログ・運用]]
7. [[26_9.01-理想仕様の残課題]]

これらは現実装との差分を含む理想仕様であり、実装済みの箇所と将来追加の箇所を分けて扱う。

# 実装同期メモ

2026-06-24 時点の初回実装では、`BossChallengeService` / `BossFieldInstanceService` / `BossEntryEventHandler` / `BossCommand` を追加し、スニーク受付、ハブ転送、ボスフィールド生成、ボススポーン、制限時間終了、参加者不在終了、討伐終了、管理者停止、`challenge` 付きボスマスタ読み込みを実装した。

未実装または簡略実装の範囲は、死亡回数・復帰制御、入口パーティクル表示、起動時の残存フィールド掃除、ボス専用報酬確定である。攻撃力スケーリングは `MobInstance` のセッション内攻撃倍率として実装済み。討伐成功時は 15 秒間ボスフィールドに留まり、参加者別ダメージ統計を TextDisplay で表示してから退場・フィールド破棄を行う。

2026-07-09 の性能改修で、受付時に参加者を先に `boss.hubWorldId` へ非同期転送し、ハブ転送完了後にボスフィールドのフォルダコピーを非同期で実行し、Bukkit ワールドロードだけメインスレッドへ戻す流れへ変更した。フィールド準備が長引く場合も参加者はハブで待機し、準備完了後にボスフィールドへ入場する。ボス挑戦地点には、通常スポーン地点とは別のパーティクルリングと TextDisplay を表示する。入口演出は近傍プレイヤーがいる場合だけ描画する。

# 26-boss

このディレクトリは `feature/boss` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/boss/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/boss/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/boss/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/boss/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/boss/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/boss/task/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/boss/view/*`
- `src/main/resources/config.yml`

## ドキュメント一覧（推奨順）

1. [[26_0.00-概要]]
2. [[26_1.00-モデル定義]]
3. [[26_2.00-ユースケース]]
4. [[26_3.00-索引]]
5. [[26_4.00-統合フロー]]
6. [[26_5.00-例外・ログ・運用]]
7. [[26_9.00-未決事項]]

## 依存 feature

- `mob`: `MobCategory.BOSS`、`MobTemplate`、Mob 生成、Mob 撃破、ドロップ抽選。
- `world`: `WorldMasterData`、`WorldType.BOSS_FIELD`、ワールドロード、ワールド解決。
- `party`: パーティーメンバー、リーダー判定、パーティー解散・離脱抑制。
- `player`: `AstPlayer`、オンライン状態、死亡制御、テレポート対象。
- `combat`: `DamageService` による有効ダメージ反映とダメージ集計。
- `status`: `MAX_HEALTH` / `ATTACK` などのボス基礎ステータス。
- `loot` / `item` / `currency`: ボス討伐成功時の報酬確定と付与。
- filebase `40_filebase/40.features.mob/boss/*.yml`: ボスマスター。
- filebase `40_filebase/60.features.world/*.yml`: ボスフィールド生成元。

## 更新ルール（変更時に必ず更新する章）

- ボスマスター `challenge` 形式、`config.yml` の `boss` 設定を変更した場合:
  - [[26_1.00-モデル定義]]
  - [[26_3.02-サービス]]
  - [[26_5.00-例外・ログ・運用]]
- 挑戦受付、ハブ集合、ソロ/パーティー入場の挙動を変更した場合:
  - [[26_2.00-ユースケース]]
  - [[26_3.01-イベント]]
  - [[26_3.02-サービス]]
  - [[26_4.00-統合フロー]]
- ボスフィールド生成・破棄・起動時掃除を変更した場合:
  - [[26_1.00-モデル定義]]
  - [[26_3.02-サービス]]
  - [[26_3.05-タスク・スケジューラ]]
  - [[26_5.00-例外・ログ・運用]]
- 報酬対象、ダメージ集計、結果表示を変更した場合:
  - [[26_2.00-ユースケース]]
  - [[26_3.02-サービス]]
  - [[26_4.00-統合フロー]]
  - mob / combat / loot / currency の関連設計書

## 実装メモ

- 本設計はボス機能の正式設計書であり、今回の変更では plugin 実装は行わない。
- ボス定義は既存の `MobTemplate` を拡張し、戦闘定義と挑戦定義を別マスターへ分離しない。
- ボスフィールドは `WorldMasterData.worldType = BOSS_FIELD` かつ `instanceEnabled = true` のワールドから生成する。
