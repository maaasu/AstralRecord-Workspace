# Consumable 設計

## 役割

Consumable は、使用時に回復、補助、移動などの即時または一定時間の効果を与え、使用個数を消費する item です。

## 設計方針

- 使用目的を1つに絞り、効果、対象、使用条件を明確にします。
- 常用消耗品と攻略用消耗品を、入手性と効果量で分けます。
- 装備や skill の恒常的な役割を置き換えません。
- 使用効果として実装可能な値は Plugin と YAML スキーマを確認します。

## progression

- 標準使用対象となるモブやコンテンツの `P-1` から `P` を基準にします。
- 高難度報酬として得る消耗品は、入手元と同値から `+1` を許容します。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\consumable\_consumable.YAMLスキーマ定義.md`
