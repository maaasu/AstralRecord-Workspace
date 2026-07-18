# 27_README

このディレクトリは `feature/condition` の設計書です。
状態異常、行動制限、無敵など、一定時間主体へ付与される戦闘状態を扱います。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/condition/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/condition/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/condition/task/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/condition/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/condition/display/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/combat/model/DamageElement.*`
- `src/main/java/io/github/maaasu/astralRecord/feature/combat/model/DamageElementProfile.*`

## ドキュメント一覧（推奨順）

1. [[27_0.00-概要]]
2. [[27_1.00-モデル定義]]
3. [[27_2.00-ユースケース]]
4. [[27_3.00-索引]]
5. [[27_3.02-サービス]]
6. [[27_3.05-タスク・スケジューラ]]
7. [[27_3.07-表示]]
8. [[27_4.00-統合フロー]]
9. [[27_5.00-例外・ログ・運用]]
10. [[27_9.00-未決事項]]

## 依存 feature

- `combat`
  - 状態異常の DoT と属性ダメージを `DamageService` 経由で適用する。
- `status`
  - プレイヤーと Mob の攻撃力、防御力、移動速度、回復力などを参照する。
- `mob`
  - `MobInstance` への状態異常保持、炎上表示、移動停止、AI 抑止で連携する。
- `player`
  - `AstPlayer` への状態異常保持、移動/攻撃/スキル制限、本人通知で連携する。
- `skill`
  - スキル命中時の状態異常付与条件、付与確率、付与元情報を受け取る。
- `buff`
  - 能力補正バフとは責務を分ける。状態異常表示・DoT・行動制限の正本は `condition` とする。

## filebase

- `40_filebase/30.features.skill/`
  - `params.damageElement`
  - `params.conditions[]`
  - 状態異常付与を伴う攻撃スキルの静的定義として利用する。

## 更新ルール（変更時に必ず更新する章）

- 状態異常種別、重複規則、耐性規則の増減:
  - [[27_1.00-モデル定義]]
  - [[27_5.00-例外・ログ・運用]]
- DoT、属性補正、ダメージ適用順序の変更:
  - [[27_3.02-サービス]]
  - [[27_4.00-統合フロー]]
  - [[14_1.00-モデル定義]]
  - [[14_3.02-サービス]]
- 表示粒度、パーティクル、Display 表示の変更:
  - [[27_3.07-表示]]
  - [[27_5.00-例外・ログ・運用]]
- プレイヤー/Mob/NPC の適用対象ポリシー変更:
  - [[27_1.00-モデル定義]]
  - [[27_3.02-サービス]]
