# Set Effect 設計

## 役割

Set Effect は、同じ set ID の equipment を一定数装備したときに発動する能力を定義します。

## 設計方針

- 必要部位数による装備選択の制約に見合う効果を持たせます。
- 少数部位では方向性を示し、多数部位では完成形を補強します。
- 段階効果は累積する前提で、合計性能を確認します。
- 使用可能なステータスは `StatusType.kt` を正とします。

## progression

セットを標準的に成立させられる段階を基準にし、最も遅く入手する構成品より前に置きません。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\equipment\set_effect\docs.set_effect.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
