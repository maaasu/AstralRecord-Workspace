# 05_README

このディレクトリは `feature/buff` の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/buff/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/buff/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/buff/model/*`

## ドキュメント一覧（推奨順）

1. [[05_0.00-概要]]
2. [[05_1.00-モデル定義]]
3. [[05_2.00-ユースケース]]
4. [[05_3.00-索引]]
5. [[05_4.00-統合フロー]]
6. [[05_5.00-例外・ログ・運用]]
7. [[05_9.00-未決事項]]（必要時）

## 依存 feature

- `player`
  - [[03_1.00-モデル定義]].プレイヤーキャッシュ に保持される `AstPlayer.activeBuffs` を直接操作対象とする。
- `status`
  - `StatusService` が `BuffService` を内部生成・所有し、ステータス再計算時に [[05_3.02-サービス]].バフ補正合計取得 を呼ぶ。
- `item`
  - `PotionUseService` が `ItemConsumable.effects` の `BUFF` タイプから `StatusService.applyBuff` を呼び、付与成功時にバフ獲得通知を表示する。

## 更新ルール（変更時に必ず更新する章）

- バフ付与・解除・期限切れ判定の処理順変更:
  - [[05_3.02-サービス]]
  - [[05_4.00-統合フロー]]
- [[05_1.00-モデル定義]].バフタイプ / アクティブバフ 項目追加・削除:
  - [[05_1.00-モデル定義]]
  - [[05_3.04-リポジトリ]]（API 入出力が変わる場合）
- バフ補正計算式変更:
  - [[05_3.02-サービス]]
  - [[05_5.00-例外・ログ・運用]]（運用影響がある場合）
- ログIDや障害対応手順の変更:
  - [[05_5.00-例外・ログ・運用]]
  - [[05_9.00-未決事項]]（未確定事項がある場合）
