# 14_README

このディレクトリは `feature/combat` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/combat/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/combat/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/combat/event/*`

## ドキュメント一覧（推奨順）

1. [[14_0.00-概要]]
2. [[14_1.00-モデル定義]]
3. [[14_2.00-ユースケース]]
4. [[14_3.00-索引]]
5. [[14_5.00-例外・ログ・運用]]
6. [[14_9.00-未決事項]]

## 依存 feature

- `status`
  - 被弾者・攻撃者のステータス参照、HP/MP/エネルギーの実値更新先として利用する。
- `player`
  - 攻撃者・被弾者が `AstPlayer` の場合の解決に利用する。
- `mob`
  - 攻撃者・被弾者が `MobInstance` の場合の解決と HP 反映に利用する。
- `skill`
  - スキル由来のダメージ発生経路として、将来 `DamageService` を呼び出す。

## 更新ルール（変更時に必ず更新する章）

- 攻撃種別 / ダメージ種別の増減:
  - [[14_1.00-モデル定義]]
- 属性種別 / 属性補正の増減:
  - [[14_1.00-モデル定義]]
  - [[14_3.02-サービス]]
- ダメージ計算式・補正適用順序の変更:
  - [[14_0.00-概要]]
  - [[14_3.02-サービス]]
- 受信するイベント種別・登録優先度の変更:
  - [[14_3.01-イベント]]
  - [[14_5.00-例外・ログ・運用]]
