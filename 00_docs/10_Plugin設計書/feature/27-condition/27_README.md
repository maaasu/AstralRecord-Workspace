# 27_README

このディレクトリは、プレイヤーと AstralRecord 管理 Mob に共通適用する状態異常機能の設計書です。
採番・命名・参照ルールは [[README]] に従います。

属性ダメージと状態異常は独立しており、属性一致だけでは状態異常を付与しません。スキルなどの呼び出し元が `ConditionApplyRequest` を明示的に渡した場合だけ付与します。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/service/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/display/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/task/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/infrastructure/logging/LogId.java`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`
- `10_plugin/AstralRecord/src/test/java/io/github/maaasu/astralRecord/feature/condition/*`

## ドキュメント一覧（推奨順）

1. [[27_0.00-概要]]
2. [[27_1.00-モデル定義]]
3. [[27_2.00-ユースケース]]
4. [[27_3.00-索引]]
5. [[27_3.01-イベント]]
6. [[27_3.02-サービス]]
7. [[27_3.05-タスク・スケジューラ]]
8. [[27_3.07-表示]]
9. [[27_4.00-統合フロー]]
10. [[27_5.00-例外・ログ・運用]]
11. [[27_9.00-未決事項]]

## 依存 feature

- `combat`: `AstEntity` を共通対象型として使い、`DamageService` が DoT、シールド、HP、死亡処理を担当する。
- `status`: 付与確率・耐性・DoT 補正値を提供し、移動速度と HP / MP / EN / Shield 回復へ状態異常効果を反映する。
- `skill`: スキル定義の `conditions` から付与要求を作り、発動可否と詠唱時間へ状態異常効果を反映する。
- `mob`: 管理 Mob、Boss / NPC 判定、AI 実行可否、Mob のバニラ炎上表示保護を提供する。
- `player`: `AstPlayerCache`、死亡判定、quit / death 時の状態破棄を提供する。
- `item` / `equipment`: 通常攻撃可否の確認元となる。
- `shared/effect`: 近傍 viewer 向けパーティクル表示を担当する。

## 更新ルール（変更時に必ず更新する章）

- 状態異常種別、既定時間、効果、表示優先順位を変更した場合:
  - [[27_1.00-モデル定義]]
  - [[27_3.02-サービス]]
  - [[27_3.07-表示]]
- 付与確率、Boss 補正、同種更新、DoT 計算を変更した場合:
  - [[27_2.00-ユースケース]]
  - [[27_3.02-サービス]]
  - [[27_4.00-統合フロー]]
- Bukkit イベント、行動制限、quit / death cleanup を変更した場合:
  - [[27_3.01-イベント]]
  - [[27_4.00-統合フロー]]
  - [[27_5.00-例外・ログ・運用]]
- tick / display / cleanup の周期、処理上限、起動停止順を変更した場合:
  - [[27_3.05-タスク・スケジューラ]]
  - [[27_4.00-統合フロー]]
  - [[27_5.00-例外・ログ・運用]]
- ActionBar、PotionEffect、fire ticks、BlockDisplay、particle を変更した場合:
  - [[27_3.07-表示]]
  - [[27_5.00-例外・ログ・運用]]
- ログ ID、テンプレート、operation を変更した場合:
  - [[27_3.01-イベント]]
  - [[27_5.00-例外・ログ・運用]]
