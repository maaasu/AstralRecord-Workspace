# 14_README

このディレクトリは `feature/combat` の設計書です。採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/event/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/model/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/*`
- `10_plugin/AstralRecord/src/main/resources/logger.properties`（`E_5900`）
- `10_plugin/AstralRecord/src/main/resources/player.properties`（`P_5350` から `P_5353`）

## ドキュメント一覧（推奨順）

1. [[14_0.00-概要]]
2. [[14_1.00-モデル定義]]
3. [[14_2.00-ユースケース]]
4. [[14_3.00-索引]]
5. [[14_3.01-イベント]]
6. [[14_3.02-サービス]]
7. [[14_4.00-統合フロー]]
8. [[14_5.00-例外・ログ・運用]]
9. [[14_9.00-未決事項]]（必要時）

## 依存 feature

- `status`: 攻撃・防御・属性・状態異常 status と player HP / shield 更新を扱う。
- `player`: `AstPlayer` 解決、死亡中判定、独自 death flow を扱う。
- `mob` / `boss`: `MobInstance`、threat、drop、boss damage / death を扱う。
- `skill` / `condition`: active skill、temporary modifier、condition damage の発生元となる。
- `item`: 攻撃命中・被弾時の equipment durability を扱う。
- `player-setting` / `display`: damage number と詳細 message の表示可否・描画を扱う。
- `player-interaction`: player 起点の通常攻撃と他 action の入力排他を正本とする。

## 更新ルール（変更時に必ず更新する章）

- attack type / element / scaling / result 項目変更: [[14_1.00-モデル定義]]、[[14_3.02-サービス]]
- 計算式・補正順序・hit / critical 変更: [[14_0.00-概要]]、[[14_3.02-サービス]]、[[14_4.00-統合フロー]]
- shield / HP / threat / death / durability 反映変更: [[14_3.02-サービス]]、[[14_4.00-統合フロー]]
- Bukkit event priority・action ring 抑止境界変更: [[14_3.01-イベント]]、[[14_4.00-統合フロー]]、[[28_3.01-イベント]]
- log / damage message / display 変更: [[14_5.00-例外・ログ・運用]]
