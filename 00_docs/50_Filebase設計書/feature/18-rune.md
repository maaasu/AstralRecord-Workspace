# Rune 設計

## 役割

Rune は、対応する equipment へステータス補正を追加する拡張 item です。skill は付与しません。

## 設計方針

- 対象 slot、必要強化レベル、付与能力を一つの用途として整合させます。
- `targetSlots` は装備スロット、必要な場合の `targetTags` は装備種別タグとして分離し、両方を指定した場合は AND 条件で判定します。
- ルーンの適合条件はルーン側で定義し、装備ごとのルーン ID ホワイトリストは持たせません。
- equipment 本体のカテゴリ役割を無条件で置き換えません。
- 汎用性が高い rune ほど、1装備あたりの性能配分を抑えます。
- 使用可能なステータスは `StatusType.kt` を正とします。

## progression

標準的に装着可能になる段階を基準にし、対象 equipment と同値から `+1` を基本とします。

## 正本参照

- 戦闘・ゲームバランス: ステータス補正や装備更新に関わる値を追加・変更する場合は、`E:\AstralRecord-Workspace\00_docs\60_戦闘バランス設計書\README.md` を入口に該当資料を参照します。
- YAML: `E:\AstralRecord-Workspace\40_filebase\10.features.item\rune\docs.rune.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
