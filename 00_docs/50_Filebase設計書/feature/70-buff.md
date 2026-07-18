# Buff 設計

## 役割

Buff は、一定時間または条件中にステータス補正を付与する共通効果です。

## 設計方針

- 強化・弱体の目的、対象ステータス、効果量、持続時間を一つの役割へ揃えます。
- 同じ効果を異なる ID で重複定義しません。
- 無期限効果は解除条件と付与元を確認します。
- 使用可能なステータスは `StatusType.kt` を正とします。

## progression

buff 自体ではなく、最も早い標準付与元の段階を基準にします。システム専用で進行に依存しない効果は `0` とします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\70.shared.buff\buff.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\status\model\StatusType.kt`
