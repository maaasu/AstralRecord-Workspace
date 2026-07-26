# Rune 設計

## 役割

Rune は、対応する equipment へステータスまたは skill を追加する拡張 item です。

## 設計方針

- 対象 slot、必要強化レベル、付与能力を一つの用途として整合させます。
- equipment 本体のカテゴリ役割を無条件で置き換えません。
- 汎用性が高い rune ほど、1装備あたりの性能配分を抑えます。
- 使用可能なステータスは `StatusType.kt` を正とします。

## progression

標準的に装着可能になる段階を基準にし、対象 equipment と同値から `+1` を基本とします。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\rune\docs.rune.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
