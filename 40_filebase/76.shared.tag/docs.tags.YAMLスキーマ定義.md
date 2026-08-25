# タグ共有カタログ YAML スキーマ定義

## 目的

マスターデータで使用できるタグ ID、日本語表示名、意味、設定可能な対象を一元管理します。タグ ID は既存マスターおよび Plugin の分岐条件との互換性を保つため、大文字・小文字を含めて変更しません。

## 正本

- カタログ: `v1.tags.yml`
- JSON Schema: `schemas/tag-catalog.v1.schema.json`

## ルート

| 項目 | 型 | 必須 | 説明 |
|:--|:--|:--|:--|
| `schemaVersion` | Integer | ○ | 現在は `1` |
| `categories[]` | List | ○ | タグの意味分類 |
| `targets[]` | List | ○ | タグを設定できるマスター上の対象 |
| `tags[]` | List | ○ | タグ定義 |

## tags[]

| 項目 | 型 | 必須 | 説明 |
|:--|:--|:--|:--|
| `id` | String | ○ | マスターへ保存する不変 ID。大文字・小文字を区別する |
| `displayName` | String | ○ | エディターなどで表示する日本語名 |
| `description` | String | ○ | タグの用途・意味 |
| `category` | String | ○ | `categories[].id` の参照 |
| `appliesTo[]` | List\<String\> | ○ | `targets[].id` の参照。対象外のマスターには設定しない |

## 運用ルール

- 既存 ID は再採番・大小文字変更を行わない。
- 新規タグはこのカタログへ追加してからマスターで使用する。
- 削除済み ID は別の意味で再利用しない。
- Plugin の処理分岐に使用するタグは生成定数を利用し、文字列を直接記述しない。
- `EQUIPMENT` と `GATHERING_REQUIRED_TOOL` は同じ `equipment.tag` を介して照合される。
- Rune の `rune.targetTags[]` は `EQUIPMENT` 対象タグを `equipment.tag` と照合する。
- スキルツリーエディターは `SKILLTREE_NODE` 対象の定義だけを候補表示し、保存時は日本語名ではなく `id` を JSON に保持する。
