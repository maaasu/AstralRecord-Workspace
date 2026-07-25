# 27-condition

プレイヤーとカスタムMobへ共通適用する状態異常機能の設計書です。

## 対象実装

- `feature/condition/model/*`
- `feature/condition/service/*`
- `feature/condition/display/*`
- `feature/condition/event/*`
- `feature/condition/task/*`

## 正本

- 状態異常種別・既定値: [[27_1.00-モデル定義]]
- 付与・重複・DoT計算: [[27_3.02-サービス]]
- 表示: [[27_3.07-表示]]
- スキルYAML: `40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md`

属性ダメージと状態異常は独立した仕組みであり、火属性攻撃だから自動的に燃焼することはない。スキルの `conditions` で明示された場合だけ状態異常を付与する。
